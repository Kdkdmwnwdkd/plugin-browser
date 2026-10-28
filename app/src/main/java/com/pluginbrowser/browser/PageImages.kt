package com.pluginbrowser.browser

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 页面图片清单。
 *
 * 提取在页面里做（见 SiteScripts.COLLECT_IMAGES），这里只负责把结果解出来 +
 * 生成落盘用的文件名。之所以不传 Bitmap 回来：一个长文页几十张大图，
 * 全传进原生就是几十 MB 的内存峰值，中低端机直接 OOM。
 */
object PageImages {

    data class Image(val url: String, val width: Int, val height: Int)

    /** BrowserFragment 提取后暂存在这里，避免用 Bundle 传几百条 URL 撑爆事务 */
    @Volatile
    var pending: List<Image> = emptyList()

    private val IMAGE_MIME = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
        "heic" to "image/heic",
    )

    /** evaluateJavascript 的返回值是「JSON 字符串字面量」，解两层才拿到对象 */
    fun parse(raw: String?): List<Image> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val text = runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                else -> raw
            }
        }.getOrNull() ?: return emptyList()

        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (!obj.optBoolean("ok")) return emptyList()
        val array = obj.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<Image>(array.length())
        val seen = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank() || !seen.add(url)) continue
            out.add(Image(url, item.optInt("w"), item.optInt("h")))
        }
        return out
    }

    fun mimeOf(url: String): String {
        val ext = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").lowercase()
        return IMAGE_MIME[ext] ?: "image/jpeg"
    }

    /**
     * 落盘文件名。
     *
     * 很多站点的图片是 /2024/06/xxx.jpg 这种，直接用末段会撞名；
     * 撞了也不是错误（下载器会自动加 (1)），但一堆同名文件很难认，
     * 所以再加个序号前缀，保证一屏里能分得清是哪张。
     */
    fun fileNameOf(url: String, index: Int): String {
        val path = url.substringBefore('?').substringBefore('#')
        // %20 是路径里的空格，先还原再统一替换，否则文件名里会留一串 %20
        var name = path.substringAfterLast('/').replace("%20", " ", ignoreCase = true)
            .trim()
            .ifBlank { "image" }
        // 去掉非法字符，顺便挡掉过长的名字
        name = name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(60)
        if (name.isEmpty()) name = "image"
        if (!name.contains('.')) name = "$name.jpg"
        return "%02d_%s".format(index + 1, name)
    }
}
