package com.pluginbrowser.adblock

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量广告拦截引擎，读 EasyList 的编译产物（assets/adblock）。
 *
 * 规则分三层，按命中成本从低到高查：
 *  1. 纯域名（`||ads.example.com^`）→ HashSet，逐级查父域，O(域名层数)
 *  2. 带路径的域名规则（`||example.com/banner/` + 通配）→ 按域名锚点建索引
 *  3. 通用规则（以斜杠开头的路径片段、`-ad-banner.` 这类）→ 按规则里的词元建索引，再用 URL 的词元反查
 *
 * 例外规则（`@@`）单独索引，命中即放行。
 */
class AdBlockEngine(private val context: Context?) {

    /* ---------------- 数据结构 ---------------- */

    private class Rule(
        val pattern: String,
        val hostAnchor: String?,
        val thirdOnly: Boolean,
    ) {
        /** 纯 contains 就能判断的规则，省掉正则编译 */
        val plainPrefix: String? = if (pattern.isEmpty() || pattern == "^") {
            ""
        } else if (pattern.none { it == '*' || it == '^' }) {
            pattern
        } else if (pattern.endsWith("^") && pattern.dropLast(1).none { it == '*' || it == '^' }) {
            pattern.dropLast(1)
        } else {
            null
        }

        @Volatile
        private var regex: Regex? = null

        fun matches(url: String, host: String, thirdParty: Boolean): Boolean {
            if (thirdOnly && !thirdParty) return false
            if (hostAnchor != null && !hostMatches(host, hostAnchor)) return false
            val prefix = plainPrefix
            if (prefix != null) return prefix.isEmpty() || url.contains(prefix)
            val rx = regex ?: compile(pattern).also { regex = it }
            return rx.containsMatchIn(url)
        }
    }

    private val blockHosts = HashSet<String>(65536)
    private val blockByHost = HashMap<String, ArrayList<Rule>>(8192)
    private val blockByToken = HashMap<String, ArrayList<Rule>>(8192)
    private val blockSlop = ArrayList<Rule>(1024)

    private val exceptHosts = HashSet<String>(2048)
    private val exceptByHost = HashMap<String, ArrayList<Rule>>(1024)
    private val exceptByToken = HashMap<String, ArrayList<Rule>>(1024)
    private val exceptSlop = ArrayList<Rule>(512)

    private val cosmeticGeneric = StringBuilder()
    private val cosmeticByDomain = HashMap<String, ArrayList<String>>(8192)

    /** 用户手动放行的域名 */
    private val whitelist = HashSet<String>()

    private val cache = ConcurrentHashMap<String, Boolean>(1024)

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var blockedCount: Long = 0L
        private set

    val hostRuleCount: Int get() = blockHosts.size

    /* ---------------- 加载 ---------------- */

    /** 单测用：解析完手动置位 */
    internal fun markReady() { ready = blockHosts.isNotEmpty() || blockByHost.isNotEmpty() }

    /** 在 IO 线程调用；重复调用无副作用 */
    fun load() {
        if (ready) return
        val ctx = context ?: return
        runCatching { loadHosts(ctx) }
        runCatching { loadRules(ctx) }
        runCatching { loadCosmetic(ctx) }
        ready = blockHosts.isNotEmpty()
    }

    private fun loadHosts(ctx: Context) {
        ctx.assets.open("adblock/hosts.txt").bufferedReader().useLines { lines ->
            parseHosts(lines)
        }
    }

    /** 供单测直接喂行数据 */
    internal fun parseHosts(lines: Sequence<String>) {
        lines.forEach { line ->
            val h = line.trim()
            if (h.isNotEmpty() && h[0] != '#') blockHosts.add(h)
        }
    }

    private fun loadRules(ctx: Context) {
        ctx.assets.open("adblock/rules.tsv").bufferedReader().useLines { lines ->
            parseRules(lines)
        }
    }

    internal fun parseRules(lines: Sequence<String>) {
        run {
            lines.forEach { line ->
                if (line.isEmpty()) return@forEach
                val p = line.split('\t')
                if (p.size < 4) return@forEach
                val flags = p[0]
                val exception = flags.startsWith("1")
                val thirdOnly = flags.length > 1 && flags[1] == '1'
                val hostAnchor = p[1].ifEmpty { null }
                val token = p[2]
                val pattern = p[3]
                val rule = Rule(pattern, hostAnchor, thirdOnly)
                val hostMap = if (exception) exceptByHost else blockByHost
                val tokenMap = if (exception) exceptByToken else blockByToken
                val slop = if (exception) exceptSlop else blockSlop

                when {
                    hostAnchor != null -> hostMap.getOrPut(hostAnchor) { ArrayList(4) }.add(rule)
                    token.isNotEmpty() -> tokenMap.getOrPut(token) { ArrayList(2) }.add(rule)
                    else -> slop.add(rule)
                }
            }
        }
    }

    private fun loadCosmetic(ctx: Context) {
        ctx.assets.open("adblock/cosmetic.tsv").bufferedReader().useLines { lines ->
            parseCosmetic(lines)
        }
    }

    internal fun parseCosmetic(lines: Sequence<String>) {
        val generic = StringBuilder()
        run {
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@forEach
                val domain = line.substring(0, tab)
                val selector = line.substring(tab + 1)
                if (selector.isEmpty()) return@forEach
                if (domain.isEmpty()) {
                    if (generic.length < 240_000) generic.append(selector).append(',')
                } else {
                    cosmeticByDomain.getOrPut(domain) { ArrayList(8) }.add(selector)
                }
            }
        }
        val s = generic.toString().trimEnd(',')
        if (s.isNotEmpty()) cosmeticGeneric.append(s).append("{display:none!important}")
    }

    /* ---------------- 判定 ---------------- */

    fun shouldBlock(url: String, pageUrl: String?): Boolean {
        if (!ready) return false
        if (url.startsWith("data:") || url.startsWith("blob:")) return false
        val cached = cache[url]
        if (cached != null) return cached

        val verdict = runCatching { evaluate(url, pageUrl) }.getOrDefault(false)
        if (cache.size > 8192) cache.clear()
        cache[url] = verdict
        if (verdict) blockedCount++
        return verdict
    }

    private fun evaluate(url: String, pageUrl: String?): Boolean {
        val host = hostOf(url) ?: return false
        if (host in whitelist) return false

        val pageHost = pageUrl?.let { hostOf(it) }
        val thirdParty = isThirdParty(host, pageHost)
        val lowerUrl = url.lowercase()

        // 例外优先：命中任何 @@ 规则就放行
        if (anyMatch(exceptHosts, exceptByHost, exceptByToken, exceptSlop, host, lowerUrl, thirdParty)) {
            return false
        }
        return anyMatch(blockHosts, blockByHost, blockByToken, blockSlop, host, lowerUrl, thirdParty)
    }

    private fun anyMatch(
        hosts: HashSet<String>,
        byHost: Map<String, ArrayList<Rule>>,
        byToken: Map<String, ArrayList<Rule>>,
        slop: List<Rule>,
        host: String,
        url: String,
        thirdParty: Boolean,
    ): Boolean {
        var h = host
        while (true) {
            if (h in hosts) return true
            val list = byHost[h]
            if (list != null) {
                for (i in list.indices) if (list[i].matches(url, host, thirdParty)) return true
            }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }

        for (token in tokensOf(url)) {
            val list = byToken[token] ?: continue
            for (i in list.indices) if (list[i].matches(url, host, thirdParty)) return true
        }

        for (i in slop.indices) if (slop[i].matches(url, host, thirdParty)) return true
        return false
    }

    /** 抽出 URL 里长度 ≥ 4 的字母数字片段，用于反查词元索引 */
    private fun tokensOf(s: String): List<String> {
        val out = ArrayList<String>(12)
        var start = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val ok = c in 'a'..'z' || c in '0'..'9'
            if (ok) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= 4) out.add(s.substring(start, i))
                start = -1
            }
            i++
        }
        if (start >= 0 && s.length - start >= 4) out.add(s.substring(start))
        return out
    }

    /* ---------------- 元素隐藏 ---------------- */

    /** 生成当前站点要注入的 CSS；没有规则时返回 null */
    fun cssFor(pageUrl: String?): String? {
        if (!ready || pageUrl == null) return null
        val host = hostOf(pageUrl) ?: return null
        if (host in whitelist) return null

        val sb = StringBuilder(cosmeticGeneric)
        var h = host
        while (true) {
            val list = cosmeticByDomain[h]
            if (list != null) {
                for (i in list.indices) {
                    sb.append(list[i]).append("{display:none!important}")
                }
            }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        return sb.toString().ifEmpty { null }
    }

    /* ---------------- 白名单 ---------------- */

    fun addWhitelist(host: String) {
        whitelist.add(host.lowercase())
        cache.clear()
    }

    fun isWhitelisted(host: String): Boolean = host.lowercase() in whitelist

    companion object {
        /** 轻量取主机名，避免依赖 android.net.Uri，方便单测 */
        internal fun hostOf(url: String): String? {
            val scheme = url.indexOf("://")
            if (scheme < 0) return null
            val start = scheme + 3
            var end = start
            while (end < url.length) {
                val c = url[end]
                if (c == '/' || c == '?' || c == '#' || c == ':') break
                end++
            }
            if (end <= start) return null
            return url.substring(start, end).lowercase()
        }

        private fun hostMatches(host: String, anchor: String): Boolean {
            if (anchor.isEmpty()) return true
            if (host == anchor) return true
            return host.length > anchor.length &&
                host.endsWith(anchor) &&
                host[host.length - anchor.length - 1] == '.'
        }

        private fun isThirdParty(host: String, pageHost: String?): Boolean {
            if (pageHost.isNullOrEmpty() || host == pageHost) return false
            return baseDomain(host) != baseDomain(pageHost)
        }

        private fun baseDomain(h: String): String {
            val parts = h.split('.')
            return if (parts.size >= 2) parts[parts.size - 2] + "." + parts[parts.size - 1] else h
        }

        private fun compile(pattern: String): Regex {
            val sb = StringBuilder(pattern.length + 16)
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^a-z0-9_\\-.%]|$)")
                    '.', '\\', '[', ']', '{', '}', '(', ')', '+', '?', '$', '|' -> {
                        sb.append('\\').append(c)
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
