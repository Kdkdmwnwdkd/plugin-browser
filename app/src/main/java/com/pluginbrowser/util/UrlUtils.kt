package com.pluginbrowser.util

import android.net.Uri

/** URL 解析与归一化工具 */
object UrlUtils {

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val HOST_REGEX = Regex("^[a-zA-Z0-9.-]+(:\\d+)?$")

    /** 判断输入是否"看起来是一个网址"而非搜索词 */
    fun looksLikeUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        if (s.contains(' ')) return false
        if (s.contains("://")) return true
        if (s.startsWith("localhost", ignoreCase = true)) return true
        if (s.startsWith("about:")) return true
        val hostPart = s.substringBefore('/').substringBefore('?')
        if (!HOST_REGEX.matches(hostPart)) return false
        val dot = hostPart.indexOf('.')
        return dot > 0 && dot < hostPart.length - 1
    }

    /** 把地址栏输入归一化为可加载的 URL */
    fun normalize(input: String, searchEngine: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        return when {
            looksLikeUrl(s) -> if (s.contains("://")) s else "https://$s"
            else -> searchUrl(searchEngine, s)
        }
    }

    fun searchUrl(engine: String, query: String): String {
        val q = Uri.encode(query)
        return when (engine) {
            "google" -> "https://www.google.com/search?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "duckduckgo" -> "https://duckduckgo.com/?q=$q"
            else -> "https://www.baidu.com/s?wd=$q"
        }
    }

    /** 提取用于展示的主机名（去掉 www.） */
    fun hostOf(url: String): String = try {
        val host = Uri.parse(url).host
        if (host.isNullOrEmpty()) url else host.removePrefix("www.")
    } catch (e: Exception) {
        url
    }

    fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    fun isWebPage(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 从 Content-Disposition / URL 推断下载文件名 */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let { cd ->
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (m != null && m.groupValues[1].isNotBlank()) {
                return m.groupValues[1].trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            }
        }
        val last = url.substringBefore('?').substringAfterLast('/')
        if (last.isNotBlank() && last.contains('.')) return last.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val ext = mimeType?.substringAfterLast('/', "")?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "bin"
        return "download_${System.currentTimeMillis() / 1000}.$ext"
    }
}
