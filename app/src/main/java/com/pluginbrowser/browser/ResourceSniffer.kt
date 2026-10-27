package com.pluginbrowser.browser

import android.webkit.MimeTypeMap

/**
 * 资源嗅探：从网络请求里挑出可下载的媒体文件。
 *
 * 只记录「后缀能确定类型」的请求，图片默认不记（一个页面几十张，噪音太大）。
 * 记录发生在 shouldInterceptRequest 的后台线程，所以整体加锁 + 上限保护。
 */
object ResourceSniffer {

    enum class Kind { VIDEO, AUDIO, STREAM, IMAGE, FILE }

    data class Item(
        val url: String,
        val host: String,
        val kind: Kind,
        val time: Long,
    )

    private const val MAX_ITEMS = 300

    /** 保留插入顺序，超出上限时丢最早的 */
    private val items = LinkedHashMap<String, Item>(64, 0.75f, false)

    /** 当前页面地址（主线程 onPageStarted 时写入，用于给记录标注来源） */
    @Volatile
    var currentPageUrl: String? = null

    /**
     * 记录一个请求。
     *
     * 这个方法在 WebView 的网络线程上、每个请求都要走一遍（一个页面几百次），
     * 所以：类型判断用纯字符扫描（不分配字符串），只有确实命中媒体后缀时才进锁。
     * 绝大多数 CSS/JS/接口请求在加锁之前就返回了。
     */
    fun record(url: String) {
        if (url.length > 2048) return
        val kind = kindOf(url) ?: return
        if (kind == Kind.IMAGE) return
        synchronized(this) {
            if (items.containsKey(url)) return
            items[url] = Item(url, hostOf(url), kind, System.currentTimeMillis())
            if (items.size > MAX_ITEMS) {
                items.keys.firstOrNull()?.let { items.remove(it) }
            }
        }
    }

    /** 最新的在前 */
    @Synchronized
    fun snapshot(): List<Item> = items.values.toList().asReversed()

    @Synchronized
    fun clear() = items.clear()

    fun mimeOf(item: Item): String {
        val ext = item.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (item.kind) {
            Kind.VIDEO -> "video/*"
            Kind.AUDIO -> "audio/*"
            Kind.STREAM -> "application/vnd.apple.mpegurl"
            Kind.IMAGE -> "image/*"
            Kind.FILE -> "application/octet-stream"
        }
    }

    /**
     * 判断 URL 指向的资源类型。全过程不做字符串切片，避免每个请求都产生一堆临时对象。
     */
    private fun kindOf(url: String): Kind? {
        if (url.length < 8 || !url.startsWith("http")) return null
        // 扩展名只可能出现在 ? / # 之前
        var end = url.length
        val q = url.indexOf('?')
        if (q >= 0) end = q
        val h = url.indexOf('#')
        if (h in 0 until end) end = h

        // 从尾部往前找最后一个 '.'，遇到 '/' 说明路径里没有扩展名
        var dot = -1
        var i = end - 1
        while (i >= 0) {
            val c = url[i]
            if (c == '.') {
                dot = i
                break
            }
            if (c == '/') return null
            i--
        }
        if (dot < 0) return null
        val len = end - dot - 1
        if (len !in 1..5) return null

        val from = dot + 1
        return when {
            extIs(url, from, end, "mp4") || extIs(url, from, end, "m4v") ||
                extIs(url, from, end, "webm") || extIs(url, from, end, "mkv") ||
                extIs(url, from, end, "mov") || extIs(url, from, end, "flv") ||
                extIs(url, from, end, "ts") || extIs(url, from, end, "avi") -> Kind.VIDEO
            extIs(url, from, end, "m3u8") || extIs(url, from, end, "mpd") -> Kind.STREAM
            extIs(url, from, end, "mp3") || extIs(url, from, end, "m4a") ||
                extIs(url, from, end, "aac") || extIs(url, from, end, "ogg") ||
                extIs(url, from, end, "wav") || extIs(url, from, end, "flac") ||
                extIs(url, from, end, "opus") -> Kind.AUDIO
            extIs(url, from, end, "jpg") || extIs(url, from, end, "jpeg") ||
                extIs(url, from, end, "png") || extIs(url, from, end, "gif") ||
                extIs(url, from, end, "webp") || extIs(url, from, end, "bmp") ||
                extIs(url, from, end, "svg") -> Kind.IMAGE
            extIs(url, from, end, "pdf") || extIs(url, from, end, "zip") ||
                extIs(url, from, end, "apk") || extIs(url, from, end, "epub") ||
                extIs(url, from, end, "rar") || extIs(url, from, end, "7z") ||
                extIs(url, from, end, "doc") || extIs(url, from, end, "docx") ||
                extIs(url, from, end, "xls") || extIs(url, from, end, "xlsx") ||
                extIs(url, from, end, "ppt") || extIs(url, from, end, "pptx") ||
                extIs(url, from, end, "txt") -> Kind.FILE
            else -> null
        }
    }

    /** [from, to) 区间是否等于 ext（忽略大小写），不产生临时字符串 */
    private fun extIs(url: String, from: Int, to: Int, ext: String): Boolean {
        if (to - from != ext.length) return false
        for (k in ext.indices) {
            var c = url[from + k]
            if (c in 'A'..'Z') c = c + 32
            if (c != ext[k]) return false
        }
        return true
    }

    /** 轻量取主机名，避免 java.net.URI 的解析开销 */
    private fun hostOf(url: String): String {
        val scheme = url.indexOf("://")
        val start = if (scheme < 0) 0 else scheme + 3
        var end = start
        while (end < url.length) {
            val c = url[end]
            if (c == '/' || c == '?' || c == '#' || c == ':') break
            end++
        }
        return if (end <= start) "" else url.substring(start, end)
    }
}
