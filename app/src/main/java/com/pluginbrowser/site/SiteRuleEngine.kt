package com.pluginbrowser.site

import android.content.Context
import android.webkit.WebView

/**
 * 站点适配引擎：按 host 决定这个页面该用什么配置、注什么脚本。
 *
 * 两个原则：
 * 1. 只读判断，不做 IO —— 它在 shouldOverrideUrlLoading / onPageStarted 的主线程路径上，
 *    算一次匹配必须是纯内存操作；
 * 2. 一条 host 可以命中多条规则（例如 zhihu.com 既拦 App 跳转，专栏页又适合阅读模式），
 *    动作取并集，而不是命中一条就返回。
 */
class SiteRuleEngine(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 总开关：关掉后所有规则都不生效，但各自的选择保留 */
    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    fun isEnabled(rule: SiteRule): Boolean =
        prefs.getBoolean(keyOf(rule.id), rule.defaultEnabled)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyOf(id), enabled).apply()
    }

    fun resetToDefault() {
        prefs.edit().clear().apply()
    }

    /** 当前 url 命中的规则（已按用户开关过滤） */
    fun rulesFor(url: String?): List<SiteRule> {
        if (!masterEnabled) return emptyList()
        val host = hostOf(url) ?: return emptyList()
        return SiteRules.ALL.filter { isEnabled(it) && it.matches(host) }
    }

    fun actionsFor(url: String?): Set<SiteAction> {
        val rules = rulesFor(url)
        if (rules.isEmpty()) return emptySet()
        val out = HashSet<SiteAction>()
        rules.forEach { out.addAll(it.actions) }
        return out
    }

    fun has(url: String?, action: SiteAction): Boolean =
        rulesFor(url).any { action in it.actions }

    /**
     * 把这些规则里「只能靠原生改」的部分套到这个 WebView 上。
     *
     * 调用时机比内容更关键：WebSettings 是在文档开始加载时读取的，
     * 页面跑起来之后再改 mediaPlaybackRequiresUserGesture 对当前页无效，
     * 所以必须在 shouldOverrideUrlLoading 和 onPageStarted 各来一次。
     */
    fun applyTo(webView: WebView, url: String?) {
        val actions = actionsFor(url)
        val autoplay = SiteAction.MEDIA_AUTOPLAY in actions
        runCatching {
            webView.settings.mediaPlaybackRequiresUserGesture = !autoplay
        }
    }

    /** 诊断面板 / 日志用的一句话描述 */
    fun describe(url: String?): String {
        val rules = rulesFor(url)
        if (rules.isEmpty()) return "无"
        return rules.joinToString("、") { it.name }
    }

    private fun keyOf(id: String) = "rule.$id"

    companion object {
        private const val PREFS = "pb_site_rules"
        private const val KEY_MASTER = "master_enabled"

        /** 从任意字符串里抠出 host（内部约定：取不到就返回 null，调用方不必再判空） */
        fun hostOf(url: String?): String? {
            if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return null
            // 用 java.net.URI 而不是 android.net.Uri：这条路径在主线程的每次导航上都会跑，
            // 纯 Java 实现既快又不依赖 Android 运行时，顺便能被单元测试覆盖
            val parsed = runCatching { java.net.URI(url).host }.getOrNull()
            if (!parsed.isNullOrBlank()) return parsed.lowercase()
            // 含中文、空格这类非规范 URL，URI 会直接抛，手工切一刀兜底
            return runCatching {
                val authority = url.substringAfter("://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                val host = authority.substringAfter('@').substringBefore(':')
                host.ifBlank { null }?.lowercase()
            }.getOrNull()
        }

        /** 唤起 App 的 scheme，命中且规则允许时直接吞掉 */
        fun isAppWakeScheme(url: String): Boolean {
            val lower = url.lowercase()
            if (lower.startsWith("intent:")) return true
            if (lower.startsWith("market:")) return true
            if (lower.startsWith("itms-apps:")) return true
            if (lower.startsWith("openapp.")) return true
            // 各家自己的 scheme：不枚举的话会一个个漏过去
            return APP_SCHEMES.any { lower.startsWith("$it:") }
        }

        private val APP_SCHEMES = listOf(
            "taobao", "tmall", "alipay", "alipays", "taobaolive",
            "zhihu", "weibo", "sinaweibo", "bilibili", "xhsdiscover",
            "snssdk1128", "snssdk2329", "kwai", "vipshop", "pptv", "sohu",
            "iqiyi", "youku", "qqvideo", "tenvideo", "mgtv", "douyin",
        )
    }
}
