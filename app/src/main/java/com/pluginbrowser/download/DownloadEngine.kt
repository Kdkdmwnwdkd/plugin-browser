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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

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
        jobs.remove(id)?.cancel()
        states.remove(id)
        partFile(id).delete()
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

    private suspend fun download(id: Long, item: DownloadItem): Saved {
        val part = partFile(id)
        var offset = if (part.exists()) part.length() else 0L
        if (offset > 0) states.compute(id) { _, old -> old?.copy(downloaded = offset) }

        val client = clientFor(item.url)
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", userAgent)
            .apply {
                if (offset > 0) header("Range", "bytes=$offset-")
                CookieManager.getInstance().getCookie(item.url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("Cookie", it) }
            }
            .build()

        val call = client.newCall(request)
        calls[id] = call
        var response = call.execute()

        // 续传位置对不上（文件变了 / 服务器只认完整请求），丢掉重来
        if (response.code == 416 && offset > 0) {
            response.close()
            part.delete()
            offset = 0
            val retry = client.newCall(
                Request.Builder()
                    .url(item.url)
                    .header("User-Agent", userAgent)
                    .apply {
                        CookieManager.getInstance().getCookie(item.url)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { header("Cookie", it) }
                    }
                    .build(),
            )
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
