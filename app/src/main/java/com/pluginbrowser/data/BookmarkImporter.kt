package com.pluginbrowser.data

/**
 * Chrome / Edge / Safari 导出的书签是 Netscape 格式的 HTML。
 *
 * 只认 <a href="...">title</a>：文件夹、图标、ADD_DATE 这些都不需要，
 * 目标是「把别人的书签搬过来能用」，不是还原目录结构。
 * 用正则而不是 HTML 解析器：这个格式由浏览器生成，结构极其固定，
 * 引入 jsoup 只为这一件事不值得。
 */
object BookmarkImporter {

    data class Entry(val title: String, val url: String)

    private val LINK = Regex(
        """<a\s[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAGS = Regex("<[^>]+>")

    fun parse(html: String): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        LINK.findAll(html).forEach { match ->
            val url = decode(match.groupValues[1]).trim()
            // javascript: / place: / chrome:// 这类搬过来也用不了，直接丢
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            if (out.containsKey(url)) return@forEach
            val raw = decode(match.groupValues[2]).replace(TAGS, " ")
            val title = raw.replace(Regex("\\s+"), " ").trim()
            out[url] = Entry(title.ifBlank { url }, url)
        }
        return out.values.toList()
    }

    private fun decode(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
}
