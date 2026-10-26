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

    @Synchronized
    fun record(url: String) {
        if (url.length > 2048) return
        val kind = kindOf(url) ?: return
        if (kind == Kind.IMAGE) return
        if (items.containsKey(url)) return

        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        items[url] = Item(url, host, kind, System.currentTimeMillis())

        if (items.size > MAX_ITEMS) {
            items.keys.firstOrNull()?.let { items.remove(it) }
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

    private fun kindOf(url: String): Kind? {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot < 0) return null
        val extLength = path.length - dot - 1
        if (extLength !in 1..5) return null

        return when (path.substring(dot + 1).lowercase()) {
            "mp4", "m4v", "webm", "mkv", "mov", "flv", "ts", "avi" -> Kind.VIDEO
            "m3u8", "mpd" -> Kind.STREAM
            "mp3", "m4a", "aac", "ogg", "wav", "flac", "opus" -> Kind.AUDIO
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> Kind.IMAGE
            "pdf", "zip", "apk", "epub", "rar", "7z",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> Kind.FILE
            else -> null
        }
    }
}
