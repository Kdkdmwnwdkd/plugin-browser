package com.pluginbrowser.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 自己的下载引擎。
 *
 * 之前把下载整个丢给系统 DownloadManager，带来两个硬伤：
 *   1. 它不认 App 自己配的代理和 DNS，浏览器走代理、下载走直连，GitHub 的包基本下不动；
 *   2. 部分 ROM 上系统下载器会莫名其妙限速，还要保持别的 App 在后台。
 *
 * 所以这里自己发请求：复用 GitHub 通道的代理/DoH，支持 Range 断点续传，
 * 进度实时往外抛，前台服务负责在后台活着。
 */
class DownloadEngine private constructor(private val context: Context) {

    /** 对外暴露的实时状态，UI 与通知都从这里读 */
    data class TaskState(
        val id: Long,
        val name: String,
        val url: String,
        val total: Long,
        val downloaded: Long,
        val status: Int,
        val speed: Long = 0,
        val error: String? = null,
    ) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    private val store = BrowserStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val states = ConcurrentHashMap<Long, TaskState>()
    private val queue = LinkedBlockingDeque<Long>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val calls = ConcurrentHashMap<Long, okhttp3.Call>()
    /** 分片模式下一个任务有多条连接，取消时要一起掐掉 */
    private val multiCalls = ConcurrentHashMap<Long, MutableList<Call>>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 本次会话刚下完的文件名，供通知提示用（打开过就清空） */
    private val finishedNames = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var idleListener: (() -> Unit)? = null

    /**
     * 半成品放 filesDir 而不是 cacheDir：
     * cacheDir 在存储紧张时会被系统清掉，下载到一半文件没了就只能从头再来。
     */
    private val partDir: File by lazy {
        File(context.filesDir, "dl").apply { mkdirs() }
    }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /* ---------------- 对外 ---------------- */

    suspend fun enqueue(
        name: String,
        url: String,
        mime: String,
        size: Long,
    ): Long {
        val id = store.insertDownload(name, url, "", mime, dmId = 0, size = size)
        states[id] = TaskState(id, name, url, size, 0, DownloadItem.STATUS_PENDING)
        queue.add(id)
        pump()
        return id
    }

    /** 进程重启后把没下完的任务接上（.part 文件在，就接着下） */
    suspend fun resumeUnfinished() {
        val list = store.unfinishedDownloads()
        for (item in list) {
            if (states.containsKey(item.id) || queue.contains(item.id)) continue
            states[item.id] = TaskState(
                item.id, item.name, item.url, item.size,
                item.downloaded, DownloadItem.STATUS_PENDING,
            )
            queue.add(item.id)
        }
        pump()
    }

    fun retry(id: Long, item: DownloadItem) {
        if (jobs.containsKey(id)) return
        states[id] = TaskState(id, item.name, item.url, item.size, 0, DownloadItem.STATUS_PENDING)
        queue.add(id)
        pump()
    }

    fun cancel(id: Long) {
        cancelled.add(id)
        queue.remove(id)
        calls.remove(id)?.cancel()
        multiCalls.remove(id)?.forEach { runCatching { it.cancel() } }
        jobs.remove(id)?.cancel()
        states.remove(id)
        partFile(id).delete()
        metaFile(id).delete()
        pump()
    }

    fun forget(id: Long) {
        states.remove(id)
    }

    fun snapshot(): Map<Long, TaskState> = HashMap(states)

    /** 同一地址已在下载（内存里正在跑，或数据库里有未完成的记录） */
    suspend fun isDownloading(url: String): Boolean {
        val running = states.values.any {
            it.url == url && (it.status == DownloadItem.STATUS_RUNNING || it.status == DownloadItem.STATUS_PENDING)
        }
        return running || store.hasActiveDownload(url)
    }

    fun takeFinished(): List<String> {
        synchronized(finishedNames) {
            val copy = finishedNames.toList()
            finishedNames.clear()
            return copy
        }
    }

    fun hasActive(): Boolean = jobs.isNotEmpty() || queue.isNotEmpty()

    fun setOnIdleListener(listener: (() -> Unit)?) {
        idleListener = listener
    }

    /* ---------------- 调度 ---------------- */

    /**
     * 一次只下一个。
     * 手机上下载多半是同一个热点/代理，并发只会互相抢带宽，还容易让代理限流。
     */
    @Synchronized
    private fun pump() {
        while (jobs.size < MAX_CONCURRENT) {
            val id = queue.poll() ?: break
            if (jobs.containsKey(id)) continue
            // 先占坑再启动：否则任务可能在 jobs[id] = job 之前就跑完并移除自己，
            // 留下一个永远不会释放的槽位，队列就卡死了
            val job = scope.launch(start = CoroutineStart.LAZY) { runTask(id) }
            jobs[id] = job
            job.start()
        }
        if (jobs.isEmpty() && queue.isEmpty()) idleListener?.invoke()
    }

    private suspend fun runTask(id: Long) {
        val item = store.downloadById(id)
        if (item == null) {
            jobs.remove(id)
            return
        }
        try {
            store.updateDownloadStatus(id, DownloadItem.STATUS_RUNNING)
            states.compute(id) { _, old ->
                (old ?: TaskState(id, item.name, item.url, item.size, 0, DownloadItem.STATUS_RUNNING))
                    .copy(status = DownloadItem.STATUS_RUNNING, error = null)
            }
            val saved = download(id, item)
            finishedNames.add(item.name)
            store.updateDownloadStatus(id, DownloadItem.STATUS_DONE, saved.size)
            store.updateDownloadPath(id, saved.path)
            states.compute(id) { _, old ->
                (old ?: TaskState(id, item.name, item.url, saved.size, saved.size, DownloadItem.STATUS_DONE))
                    .copy(
                        status = DownloadItem.STATUS_DONE,
                        downloaded = saved.size,
                        total = saved.size,
                        speed = 0,
                    )
            }
        } catch (e: Exception) {
            // 用户主动取消的（Call 被 cancel）不算失败，记录本来就要删
            val wasCancelled = cancelled.remove(id) != null
            if (!wasCancelled) {
                val message = e.message ?: e.javaClass.simpleName
                store.updateDownloadStatus(id, DownloadItem.STATUS_FAILED)
                states.compute(id) { _, old ->
                    (old ?: TaskState(id, item.name, item.url, item.size, 0, DownloadItem.STATUS_FAILED))
                        .copy(status = DownloadItem.STATUS_FAILED, speed = 0, error = message)
                }
            }
        } finally {
            calls.remove(id)
            jobs.remove(id)
            pump()
        }
    }

    /* ---------------- 真正的下载 ---------------- */

    private class Saved(val path: String, val size: Long)

    /** 一个分片。[start, end] 都是闭区间，和 HTTP Range 的语义一致 */
    private class Segment(val start: Long, val end: Long) {
        val done = AtomicLong(0)
    }

    private class Probe(val total: Long, val rangeable: Boolean)

    /**
     * 下载入口：先决定「分片」还是「单连接」。
     *
     * 单连接在跨境链路上基本跑不过 1–2 MB/s，GitHub 的 release 包动辄几十上百 MB，
     * 这是下载慢最直接的原因。分片的前提是服务端支持 Range，所以要先探一次。
     */
    private suspend fun download(id: Long, item: DownloadItem): Saved {
        val part = partFile(id)
        val meta = metaFile(id)

        // m3u8 是播放列表不是文件：整条流要按分片拉下来再拼，走单独的路径
        if (Hls.isPlaylist(item.url)) return hlsDownload(id, item, part)

        // 1. 上次就是按分片下的：必须继续按分片走。
        //    退回单连接会把整段数据写到偏移 0，文件直接报废。
        val saved = readMeta(meta)
        if (saved != null) {
            return try {
                segmentedDownload(id, item, part, meta, saved.first, saved.second)
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) throw e
                // 有些 CDN 对单 IP 的并发连接限流，分段反而一路 429/503。
                // 这种情况下如果还几乎没下到东西，退回单连接重来一次——
                // 慢总比失败强。
                val done = saved.second.sumOf { it.done.get() }
                if (done < FALLBACK_MAX_BYTES) {
                    runCatching { meta.delete() }
                    runCatching { part.delete() }
                    singleDownload(id, item, part, 0)
                } else {
                    throw e
                }
            }
        }

        // 2. 全新任务：探一次服务端支不支持分段
        val legacy = if (part.exists()) part.length() else 0L
        if (legacy == 0L) {
            val probe = runCatching { probeRange(item) }.getOrNull()
            if (probe != null && probe.rangeable && probe.total >= MIN_SPLIT_BYTES) {
                val segs = planSegments(probe.total).map { Segment(it.first, it.second) }
                if (segs.size > 1) {
                    writeMeta(meta, probe.total, segs)
                    return segmentedDownload(id, item, part, meta, probe.total, segs)
                }
            }
        }

        // 3. 老版本留下的半成品没有 meta，文件长度就是「顺序下到哪」。
        //    这种不能改成分片——已有的是连续前缀，分片会从各个偏移同时写，中间就空了。
        return singleDownload(id, item, part, legacy)
    }

    /* ---------------- m3u8 / HLS ---------------- */

    /**
     * 按播放列表下整条流。
     *
     * 不支持断点续传：分片流没有「字节偏移」这个概念，每个分片自带边界，
     * 拼到一半的 .ts 也没法判断拼到第几片，所以每次都从第一片重来。
     * 换来的是这个功能真的能用——以前 m3u8 只能复制链接，等于没有。
     */
    private suspend fun hlsDownload(id: Long, item: DownloadItem, part: File): Saved {
        runCatching { metaFile(id).delete() }
        val result = Hls.download(
            client = clientFor(item.url),
            playlistUrl = item.url,
            userAgent = userAgent,
            out = part,
            onCall = { registerCall(id, it) },
            onProgress = { done, total, speed -> publish(id, done, total, speed) },
        )
        val name = if (!result.fmp4) {
            if (item.name.contains('.')) item.name else "${item.name}.ts"
        } else {
            item.name.removeSuffix(".ts") + ".mp4"
        }
        val mime = if (result.fmp4) "video/mp4" else "video/mp2t"
        return Saved(saveToDownloads(name, mime, part), result.bytes)
    }

    /* ---------------- 分片下载 ---------------- */

    private suspend fun segmentedDownload(
        id: Long,
        item: DownloadItem,
        part: File,
        meta: File,
        total: Long,
        segs: List<Segment>,
    ): Saved {
        publish(id, segs.sumOf { it.done.get() }, total, 0)
        val raf = RandomAccessFile(part, "rw")
        val gate = AtomicLong(0)
        try {
            coroutineScope {
                // 进度落盘与下载解耦：每 1.2 秒写一次 meta。
                // 进程被杀最多重下 1.2 秒的量，比每个缓冲区都 fsync 划算得多。
                val saver = launch {
                    while (true) {
                        delay(META_SAVE_MS)
                        writeMeta(meta, total, segs)
                    }
                }
                try {
                    (0 until segs.size).map { index ->
                        launch(Dispatchers.IO) {
                            downloadSegment(id, item, raf, segs, index, total, gate)
                        }
                    }.joinAll()
                } finally {
                    saver.cancel()
                    writeMeta(meta, total, segs)
                }
            }
            val done = segs.sumOf { it.done.get() }
            if (done != total) throw IOException("未下完（$done/$total）")
            publish(id, total, total, 0)
            val path = saveToDownloads(item.name, item.mime, part)
            runCatching { meta.delete() }
            return Saved(path, total)
        } finally {
            runCatching { raf.close() }
        }
    }

    private suspend fun downloadSegment(
        id: Long,
        item: DownloadItem,
        raf: RandomAccessFile,
        segs: List<Segment>,
        index: Int,
        total: Long,
        gate: AtomicLong,
    ) {
        val seg = segs[index]
        val buffer = ByteArray(BUFFER_BYTES)
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            var pos = seg.start + seg.done.get()
            if (pos > seg.end) return

            val call = clientFor(item.url)
                .newCall(baseRequest(item).header("Range", "bytes=$pos-${seg.end}").build())
            registerCall(id, call)
            try {
                call.execute().use { response ->
                    if (response.code != 206) throw IOException("服务器拒绝分段 ${response.code}")
                    val (rangeStart, _) = parseContentRange(response.header("Content-Range"))
                    if (rangeStart != pos) throw IOException("分段位置不符")
                    val input = response.body?.byteStream() ?: throw IOException("响应为空")

                    var lastAt = SystemClock.elapsedRealtime()
                    var lastBytes = segs.sumOf { it.done.get() }

                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        // 位置写：多个分片共用一个 FileChannel，各写各的偏移，互不干扰
                        raf.channel.write(ByteBuffer.wrap(buffer, 0, read), pos)
                        pos += read
                        seg.done.addAndGet(read.toLong())

                        val now = SystemClock.elapsedRealtime()
                        if (now - gate.get() >= PROGRESS_MS) {
                            // CAS 抢一下，四个分片同时到点时只有一个真正去更新 UI 和数据库
                            val prev = gate.get()
                            if (gate.compareAndSet(prev, now)) {
                                val done = segs.sumOf { it.done.get() }
                                val speed = if (now > lastAt) {
                                    (done - lastBytes) * 1000 / (now - lastAt)
                                } else {
                                    0L
                                }
                                publish(id, done, total, speed.coerceAtLeast(0))
                                store.updateDownloadProgress(id, done, total)
                                lastAt = now
                                lastBytes = done
                            }
                        }
                    }
                }
                if (seg.start + seg.done.get() > seg.end) return
                throw IOException("连接提前结束")
            } catch (e: Exception) {
                // 用户取消导致的异常不算失败，直接往上抛
                if (!currentCoroutineContext().isActive) throw e
                attempt++
                if (attempt >= SEGMENT_RETRY) throw e
                delay(400L * attempt)
            } finally {
                unregisterCall(id, call)
            }
        }
    }

    private fun registerCall(id: Long, call: Call) {
        multiCalls.computeIfAbsent(id) {
            java.util.Collections.synchronizedList(mutableListOf<Call>())
        }.add(call)
    }

    private fun unregisterCall(id: Long, call: Call) {
        val list = multiCalls[id] ?: return
        synchronized(list) { list.remove(call) }
    }

    /* ---------------- 分片元数据 ---------------- */

    private fun metaFile(id: Long): File = File(partDir, "$id.meta")

    private fun writeMeta(file: File, total: Long, segs: List<Segment>) {
        runCatching {
            val arr = JSONArray()
            segs.forEach {
                arr.put(JSONObject().put("s", it.start).put("e", it.end).put("d", it.done.get()))
            }
            val json = JSONObject().put("total", total).put("segs", arr).toString()
            // 先写临时文件再改名：写到一半被杀时，旧 meta 仍然完整可读
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    private fun readMeta(file: File): Pair<Long, List<Segment>>? {
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val total = obj.optLong("total", -1L)
            if (total <= 0) return null
            val arr = obj.optJSONArray("segs") ?: return null
            val segs = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Segment(o.getLong("s"), o.getLong("e")).also { s ->
                    // 越界保护：meta 被改坏时宁可从 0 重来，也不要写错位置
                    s.done.set(o.optLong("d", 0).coerceIn(0, s.end - s.start + 1))
                }
            }
            if (segs.isEmpty()) return null
            total to segs
        }.getOrNull()
    }

    /** 服务端支不支持分段、总共多大。HEAD 不可用时退化成 1 字节的 Range 试探 */
    private fun probeRange(item: DownloadItem): Probe {
        val client = clientFor(item.url)
        val head = runCatching {
            client.newCall(baseRequest(item).head().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val accept = response.header("Accept-Ranges")?.contains("bytes", true) == true
                Probe(len, accept)
            }
        }.getOrNull()

        if (head != null && head.total > 0 && head.rangeable) return head

        val ranged = runCatching {
            client.newCall(baseRequest(item).header("Range", "bytes=0-0").build())
                .execute().use { response ->
                    if (response.code != 206) return@use Probe(-1L, false)
                    val (_, total) = parseContentRange(response.header("Content-Range"))
                    Probe(total, total > 0)
                }
        }.getOrNull()
        return ranged ?: head ?: Probe(-1L, false)
    }

    private fun baseRequest(item: DownloadItem): Request.Builder =
        Request.Builder()
            .url(item.url)
            .header("User-Agent", userAgent)
            .apply {
                CookieManager.getInstance().getCookie(item.url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("Cookie", it) }
                // 图片站几乎都有防盗链：不带 Referer 直接 403。
                // 拿不到页面地址，用图片自己的源顶上，多数 CDN 认这个
                if (item.mime.startsWith("image/")) {
                    originOf(item.url)?.let { header("Referer", it) }
                }
            }

    private fun originOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val slash = url.indexOf('/', schemeEnd + 3)
        return if (slash < 0) url else url.substring(0, slash)
    }

    /* ---------------- 单连接（不支持分段时） ---------------- */

    private suspend fun singleDownload(
        id: Long,
        item: DownloadItem,
        part: File,
        legacyOffset: Long,
    ): Saved {
        var offset = legacyOffset
        if (offset > 0) states.compute(id) { _, old -> old?.copy(downloaded = offset) }

        val client = clientFor(item.url)
        val request = baseRequest(item)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        val call = client.newCall(request)
        calls[id] = call
        var response = call.execute()

        // 续传位置对不上（文件变了 / 服务器只认完整请求），丢掉重来
        if (response.code == 416 && offset > 0) {
            response.close()
            part.delete()
            offset = 0
            val retry = client.newCall(baseRequest(item).build())
            calls[id] = retry
            response = retry.execute()
        }

        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("响应为空")

        val total: Long
        if (response.code == 206) {
            val (rangeStart, rangeTotal) = parseContentRange(response.header("Content-Range"))
            if (rangeStart >= 0 && rangeStart != offset) {
                response.close()
                part.delete()
                throw IOException("断点位置已失效，请重新下载")
            }
            total = rangeTotal
        } else {
            // 服务器没理 Range，给的是整个文件，只能从头写
            if (offset > 0) {
                part.delete()
                offset = 0
            }
            total = body.contentLength()
        }

        if (total > 0) {
            states.compute(id) { _, old -> old?.copy(total = total, downloaded = offset) }
        }

        var done = offset
        var lastFlush = 0L
        var lastSpeedAt = SystemClock.elapsedRealtime()
        var lastSpeedBytes = offset
        var speed = 0L

        FileOutputStream(part, offset > 0).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (currentCoroutineContext().isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSpeedAt >= 500) {
                        speed = (done - lastSpeedBytes) * 1000 / (now - lastSpeedAt)
                        lastSpeedAt = now
                        lastSpeedBytes = done
                    }
                    if (now - lastFlush >= 300) {
                        publish(id, done, total, speed)
                        store.updateDownloadProgress(id, done, total)
                        lastFlush = now
                    }
                }
                out.flush()
            }
        }

        if (total > 0 && done < total) {
            publish(id, done, total, 0)
            throw IOException("连接中断（$done/$total）")
        }

        publish(id, done, if (total > 0) total else done, 0)
        val path = saveToDownloads(item.name, item.mime, part)
        return Saved(path, if (total > 0) total else done)
    }

    private fun publish(id: Long, downloaded: Long, total: Long, speed: Long) {
        states.compute(id) { _, old ->
            old?.copy(
                downloaded = downloaded,
                total = if (total > 0) total else old.total,
                status = DownloadItem.STATUS_RUNNING,
                speed = speed,
            )
        }
    }

    private fun clientFor(url: String): OkHttpClient {
        // GitHub 域名复用加速通道（代理 / DoH），其他域名用默认栈
        val channel = runCatching {
            PluginBrowserApp.from(context).gitHubChannel.clientFor(url)
        }.getOrNull()
        return channel?.newBuilder()
            // 下载大文件不要再往 HTTP 缓存里塞一份
            ?.cache(null)
            ?.followRedirects(true)
            ?.followSslRedirects(true)
            // 下载不能有总时长上限，只有单次读超时
            ?.callTimeout(0, TimeUnit.SECONDS)
            ?.readTimeout(30, TimeUnit.SECONDS)
            ?.build()
            ?: defaultClient
    }

    // 只取一次：这个调用会拉起 WebView 内核，别每个请求都问一遍
    private val userAgent: String by lazy {
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }
            .getOrDefault("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
    }

    private fun partFile(id: Long): File = File(partDir, "$id.part")

    /**
     * 落到系统「下载/PluginBrowser」。
     * API 29+ 走 MediaStore（应用不需要任何存储权限，文件管理器也能看到）；
     * 更老的系统直接写公共目录。
     */
    private fun saveToDownloads(name: String, mime: String, part: File): String {
        // 图片进相册：落在下载目录里图库看不见，用户会以为没保存成功。
        // 走 MediaStore.Images 不需要存储权限，相册和文件管理器都能看到
        if (mime.startsWith("image/")) {
            val path = runCatching { saveImage(name, mime, part) }.getOrNull()
            if (path != null) return path
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PluginBrowser")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw IOException("无法写入下载目录")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            part.delete()
            return uri.toString()
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PluginBrowser",
        ).apply { mkdirs() }
        val target = uniqueFile(dir, name)
        if (!part.renameTo(target)) {
            part.inputStream().use { input ->
                target.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            part.delete()
        }
        return target.absolutePath
    }

    /**
     * 图片落到 Pictures/PluginBrowser。
     *
     * 和下载目录分开的原因是「保存图片」这个动作的心理预期是进相册；
     * 放 Downloads 里用户要去文件管理器翻，等于白存。
     */
    private fun saveImage(name: String, mime: String, part: File): String {
        val safeMime = mime.ifBlank { "image/jpeg" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, safeMime)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PluginBrowser",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在相册创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw IOException("无法写入相册")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            part.delete()
            return uri.toString()
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "PluginBrowser",
        ).apply { mkdirs() }
        val target = uniqueFile(dir, name)
        if (!part.renameTo(target)) {
            part.inputStream().use { input ->
                target.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            part.delete()
        }
        return target.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val next = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            candidate = File(dir, next)
            index++
        }
        return candidate
    }

    companion object {
        private const val MAX_CONCURRENT = 1

        /** 小于这个尺寸不值得分片：多几次握手反而更慢 */
        private const val MIN_SPLIT_BYTES = 8L * 1024 * 1024

        /** 分片进度落盘间隔：进程被杀最多重下这么久的量 */
        private const val META_SAVE_MS = 1_200L

        /** UI 与数据库的刷新间隔 */
        private const val PROGRESS_MS = 300L

        private const val SEGMENT_RETRY = 3
        private const val BUFFER_BYTES = 64 * 1024

        /** 分段一失败就退回单连接的门槛：已下超过这个量就不折腾了，直接报错让用户重试 */
        private const val FALLBACK_MAX_BYTES = 2L * 1024 * 1024

        @Volatile
        private var instance: DownloadEngine? = null

        fun get(context: Context): DownloadEngine =
            instance ?: synchronized(this) {
                instance ?: DownloadEngine(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 解析 `Content-Range: bytes 100-999/1000`。
 * @return (起始字节, 总字节)，解析不出来给 -1
 */
internal fun parseContentRange(value: String?): Pair<Long, Long> {
    val text = value?.trim().orEmpty()
    if (!text.startsWith("bytes", ignoreCase = true)) return -1L to -1L
    val spec = text.substringAfter(' ').trim()
    if (spec.isEmpty()) return -1L to -1L
    val start = spec.substringBefore('-').trim().toLongOrNull() ?: -1L
    val total = spec.substringAfterLast('/', "").trim().toLongOrNull() ?: -1L
    return start to total
}

private const val PART_TARGET_BYTES = 4L * 1024 * 1024
private const val PART_MIN_BYTES = 2L * 1024 * 1024

/**
 * 把 [total] 字节切成若干闭区间分片。
 *
 * 每段目标 4 MB，最多 4 路。为什么不是 8 路、16 路：
 * 手机上的下载几乎都经过同一条代理或同一个基站出口，再多连接只会互相排队，
 * 还容易触发服务端/代理的单 IP 并发限制。4 路在实测里已经能吃掉大部分等待。
 */
internal fun planSegments(
    total: Long,
    maxParts: Int = 4,
    targetBytes: Long = PART_TARGET_BYTES,
    minBytes: Long = PART_MIN_BYTES,
): List<Pair<Long, Long>> {
    if (total <= 0) return emptyList()
    var parts = (total / targetBytes).toInt().coerceAtMost(maxParts).coerceAtLeast(1)
    while (parts > 1 && total / parts < minBytes) parts--
    if (parts <= 1) return listOf(0L to total - 1)
    val size = total / parts
    return (0 until parts).map { i ->
        val start = i * size
        // 最后一段吃掉除不尽的余数，保证 [0, total-1] 无缝覆盖
        val end = if (i == parts - 1) total - 1 else start + size - 1
        start to end
    }
}
