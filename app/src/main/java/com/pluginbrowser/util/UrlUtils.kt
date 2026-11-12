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

    /**
     * 只用来跨站追踪的参数。
     *
     * 这些字段页面自己一个都不用：utm_* 是投放平台打给人看的，gclid/fbclid 是广告点击号，
     * spm/share_* 是国内几家 App 的分享链。留着它们，转发出去的链接就等于把
     * 「我从哪来、点什么进来的」一起送出去。
     */
    private val TRACKING_PARAMS = setOf(
        "gclid", "gclsrc", "dclid", "fbclid", "msclkid", "yclid", "twclid", "igshid",
        "ttclid", "li_fat_id", "epik", "s_kwcid", "ef_id",
        "spm", "scm", "share_source", "share_medium", "share_plat", "share_tag",
        "share_token", "from_source", "from_spmid", "vd_source", "buvid", "timestamp",
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "vero_id", "wickedid",
        "oly_anon_id", "oly_enc_id", "trk", "trkCampaign",
    )

    private fun isTrackingParam(name: String): Boolean =
        name.startsWith("utm_", ignoreCase = true) || TRACKING_PARAMS.contains(name)

    /**
     * 去掉链接里的追踪参数。
     *
     * 没动过就原样返回同一个字符串——调用方拿 `cleaned == raw` 判断「这页很干净」，
     * 不用再猜。锚点和其余参数的顺序都保留，只删该删的那几段。
     */
    fun stripTracking(raw: String): String {
        if (raw.isBlank()) return raw
        val hash = raw.indexOf('#').takeIf { it >= 0 }?.let { raw.substring(it) } ?: ""
        val noHash = if (hash.isEmpty()) raw else raw.substring(0, raw.length - hash.length)
        val q = noHash.indexOf('?')
        if (q < 0) return raw
        val base = noHash.substring(0, q)
        val query = noHash.substring(q + 1)
        if (query.isEmpty()) return raw

        val parts = query.split('&').filter { it.isNotEmpty() }
        val kept = parts.filterNot { isTrackingParam(it.substringBefore('=').decodeIfNeeded()) }
        if (kept.size == parts.size) return raw
        return if (kept.isEmpty()) base + hash else base + "?" + kept.joinToString("&") + hash
    }

    private fun String.decodeIfNeeded(): String = try {
        Uri.decode(this)
    } catch (e: Exception) {
        this
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
