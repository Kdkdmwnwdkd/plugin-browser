package com.pluginbrowser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS（m3u8）下载：把播放列表里的分片一个个拉下来拼成一个文件。
 *
 * 为什么必须自己做：m3u8 本身只是一张清单，DownloadManager 存下来是个文本，
 * 用播放器打不开。分片是 mp4 的 fMP4 或 mpeg-ts，按顺序首尾相接就是一个能播的文件，
 * 不需要 ffmpeg 重新封装。
 *
 * 支持的范围：
 * - master playlist（选码率最高的一路）/ media playlist
 * - 相对路径、协议相对路径、绝对路径
 * - EXT-X-KEY: AES-128（显式 IV 或按分片序号推）
 * - EXT-X-MAP（fMP4 的初始化段）
 *
 * 明确不支持：只给音频轨的 master playlist（EXT-X-MEDIA TYPE=AUDIO）——
 * 那种流需要把音视频重新封装，没有 ffmpeg 做不到，硬拼出来是无声的。
 */
object Hls {

    private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
    /** 分片上限：防止 m3u8 被塞进几万条把内存吃光 */
    private const val MAX_SEGMENTS = 20000

    class Output(val bytes: Long, val fmp4: Boolean)

    fun isPlaylist(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    /* ---------------- 解析 ---------------- */

    data class Variant(val url: String, val bandwidth: Long)

    data class Key(val url: String, val iv: ByteArray?)

    data class Media(
        val segments: List<String>,
        val key: Key?,
        val initSegment: String?,
    ) {
        val fmp4: Boolean get() = initSegment != null
    }

    /** master playlist 里的每一路码流；不是 master 就返回空 */
    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(':', "")
                val bandwidth = regexLong(attrs, "BANDWIDTH")
                // 下一行非注释行才是地址；中间可能还夹着 #EXT-X-MEDIA 之类的标签
                var j = i + 1
                while (j < lines.size && lines[j].startsWith("#")) j++
                if (j < lines.size) {
                    out.add(Variant(resolve(baseUrl, lines[j]), bandwidth))
                    i = j
                }
            }
            i++
        }
        return out
    }

    /** 解析具体的分片列表 */
    fun parseMedia(text: String, baseUrl: String): Media {
        val segments = ArrayList<String>()
        var key: Key? = null
        var init: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = line.substringAfter(':', "")
                    if (attrs.contains("METHOD=NONE", ignoreCase = true)) {
                        key = null
                    } else if (attrs.contains("AES-128", ignoreCase = true)) {
                        val uri = regexString(attrs, "URI")
                        if (uri != null) {
                            key = Key(resolve(baseUrl, uri), hexIv(ivOf(attrs)))
                        }
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    regexString(line.substringAfter(':', ""), "URI")?.let {
                        init = resolve(baseUrl, it)
                    }
                }
                line.startsWith("#") -> Unit
                else -> if (segments.size < MAX_SEGMENTS) segments.add(resolve(baseUrl, line))
            }
        }
        return Media(segments, key, init)
    }

    /**
     * 相对地址解析。
     *
     * 不用 java.net.URI.resolve：它对 //host/path（协议相对）和带空格的地址都不友好，
     * 而这两种在播放列表里都很常见。
     */
    fun resolve(baseUrl: String, uri: String): String {
        val u = uri.trim()
        if (u.isEmpty()) return baseUrl
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return u
        val scheme = baseUrl.substring(0, schemeEnd + 3)
        if (u.startsWith("//")) return scheme.substring(0, schemeEnd + 1) + u
        val authorityEnd = baseUrl.indexOf('/', schemeEnd + 3).let {
            if (it < 0) baseUrl.length else it
        }
        val origin = baseUrl.substring(0, authorityEnd)
        if (u.startsWith("/")) return origin + u
        val path = baseUrl.substring(authorityEnd).substringBefore('?').substringBefore('#')
        val dir = path.substringBeforeLast('/', "")
        return origin + dir + "/" + u
    }

    /**
     * AES-128 的 IV。
     *
     * 没写 IV 时按规范用「分片序号」当 IV（大端 16 字节），
     * 这是 HLS 里最容易被忽略的一处，漏了就是一坨解不开的乱码。
     */
    fun ivFor(sequence: Long, explicit: ByteArray?): ByteArray {
        if (explicit != null) return explicit
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 0) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    /* ---------------- 下载 ---------------- */

    /**
     * 拉完整条流写进 [out]。
     *
     * 进度按「已写字节」上报；总量估算方式是第一片下完后按平均片大小乘片数，
     * 所以百分比在开头几十秒内会先跳到估算值再到真实值——
     * 比一直显示 0% 强，也不会把总大小写死成错的值。
     */
    suspend fun download(
        client: OkHttpClient,
        playlistUrl: String,
        userAgent: String,
        out: File,
        onCall: (Call) -> Unit,
        onProgress: (downloaded: Long, total: Long, speed: Long) -> Unit,
    ): Output = withContext(Dispatchers.IO) {
        val first = fetchText(client, playlistUrl, userAgent, onCall)
        val variants = parseMaster(first, playlistUrl)
        val mediaUrl: String
        val mediaText: String
        if (variants.isEmpty()) {
            mediaUrl = playlistUrl
            mediaText = first
        } else {
            // 码率最高的一路：存下来是为了看，不是为了省流量
            val best = variants.maxByOrNull { it.bandwidth } ?: variants.first()
            mediaUrl = best.url
            mediaText = fetchText(client, mediaUrl, userAgent, onCall)
        }
        val media = parseMedia(mediaText, mediaUrl)
        require(media.segments.isNotEmpty()) { "播放列表里没有分片" }

        val keyBytes = media.key?.let { fetchBytes(client, it.url, userAgent, onCall) }
        if (media.key != null && keyBytes != null && keyBytes.size != 16) {
            throw IllegalStateException("加密密钥长度不是 16 字节，无法解密")
        }

        out.delete()
        var written = 0L
        var total = 0L
        var seq = 0L
        val started = System.currentTimeMillis()
        FileOutputStream(out).use { sink ->
            // fMP4 的初始化段必须写在最前面，否则整段无法解析
            media.initSegment?.let { initUrl ->
                val bytes = fetchBytes(client, initUrl, userAgent, onCall)
                sink.write(bytes)
                written += bytes.size
            }
            media.segments.forEachIndexed { index, url ->
                val bytes = fetchBytes(client, url, userAgent, onCall)
                val payload = if (media.key != null && keyBytes != null) {
                    decrypt(bytes, keyBytes, ivFor(seq, media.key.iv))
                } else {
                    bytes
                }
                sink.write(payload)
                written += payload.size
                seq++
                if (index == 0 && total == 0L) {
                    total = payload.size.toLong() * media.segments.size
                }
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                onProgress(written, total, written * 1000 / elapsed)
            }
            sink.flush()
        }
        Output(out.length(), media.fmp4)
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    }.getOrElse {
        // 解密失败时把原文写出去，至少文件是完整的；总比整条任务失败强
        data
    }

    /* ---------------- 网络 ---------------- */

    private fun fetchText(
        client: OkHttpClient,
        url: String,
        userAgent: String,
        onCall: (Call) -> Unit,
    ): String {
        val bytes = fetchBytes(client, url, userAgent, onCall)
        return String(bytes, Charsets.UTF_8)
    }

    private fun fetchBytes(
        client: OkHttpClient,
        url: String,
        userAgent: String,
        onCall: (Call) -> Unit,
    ): ByteArray {
        // 分片服务器普遍查 Referer，空着就是 403：带上播放列表自己的源，
        // 比什么都不带成功率高得多，也不用把页面地址一路传进来
        val referer = originOf(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        val call = client.newCall(request)
        onCall(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: error("空响应")
            val length = body.contentLength()
            if (length > MAX_PLAYLIST_BYTES && url.contains(".m3u8", true)) {
                throw IllegalStateException("播放列表过大")
            }
            return body.bytes()
        }
    }

    private fun originOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val slash = url.indexOf('/', schemeEnd + 3)
        return if (slash < 0) url else url.substring(0, slash)
    }

    /* ---------------- 属性解析 ---------------- */

    private fun regexLong(attrs: String, name: String): Long =
        Regex("$name=(\\d+)").find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

    private fun regexString(attrs: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.getOrNull(1)

    /**
     * IV 有两种写法：带引号的 0x…（少数实现）和不带引号的 0x…（规范写法）。
     * 只认带引号的那种，会漏掉绝大多数加密流，解出来就是乱码。
     */
    private fun ivOf(attrs: String): String? =
        regexString(attrs, "IV")
            ?: Regex("IV=(0[xX][0-9A-Fa-f]+)").find(attrs)?.groupValues?.getOrNull(1)

    private fun hexIv(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val hex = raw.removePrefix("0x").removePrefix("0X")
        if (hex.length != 32) return null
        return runCatching {
            ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
