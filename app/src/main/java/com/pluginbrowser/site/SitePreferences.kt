package com.pluginbrowser.site

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.pluginbrowser.translate.AutoTranslatePolicy

/**
 * 单个站点的加载偏好。
 *
 * 和 SitePermissions 分开：那边是「能不能用相机」，这边是「这个站该怎么显示」。
 * 全部用三态，null 表示跟随全局设置 —— 这样用户改全局 UA 时，
 * 没单独设过的站点会跟着变，而不是被历史选择钉死。
 */
class SitePreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null = 跟随全局；true = 强制桌面版；false = 强制手机版 */
    fun desktop(host: String): Boolean? = optBool("d.$host")

    /** 0 = 跟随全局；50..200 是百分比 */
    fun textZoom(host: String): Int = prefs.getInt("z.$host", 0)

    fun javascript(host: String): Boolean? = optBool("j.$host")

    fun images(host: String): Boolean? = optBool("i.$host")

    /**
     * 本站的广告拦截开关。
     *
     * null / true = 跟随全局（拦）；false = 这个站不拦。
     * 只做「关」这一档，是因为全局关掉时广告插件根本不会被调用，
     * 站点级再写一个「强制开」也执行不到，只会让用户以为设了没生效。
     */
    fun adblock(host: String): Boolean? = optBool("a.$host")

    /** @see com.pluginbrowser.translate.AutoTranslatePolicy */
    fun translateMode(host: String): Int =
        AutoTranslatePolicy.normalize(prefs.getInt("t.$host", AutoTranslatePolicy.MANUAL))

    /** 这个站点是否已经问过「以后自动翻译」——问过就不再问，说「不用」也是回答 */
    fun translateAsked(host: String): Boolean = prefs.getBoolean("tq.$host", false)

    fun setDesktop(host: String, value: Boolean?) = putBool("d.$host", value)

    fun setTextZoom(host: String, value: Int) {
        prefs.edit().putInt("z.$host", value.coerceIn(0, 200)).apply()
    }

    fun setJavascript(host: String, value: Boolean?) = putBool("j.$host", value)

    fun setImages(host: String, value: Boolean?) = putBool("i.$host", value)

    fun setAdblock(host: String, value: Boolean?) = putBool("a.$host", value)

    fun setTranslateMode(host: String, mode: Int) {
        prefs.edit()
            .putInt("t.$host", AutoTranslatePolicy.normalize(mode))
            // 用户自己动过这个开关，就当成已经回答过，别再弹「以后自动翻？」
            .putBoolean("tq.$host", true)
            .apply()
    }

    fun hasOverride(host: String): Boolean =
        desktop(host) != null || textZoom(host) > 0 || javascript(host) != null || images(host) != null

    fun reset(host: String) {
        prefs.edit()
            .remove("d.$host").remove("z.$host").remove("j.$host").remove("i.$host")
            .remove("t.$host").remove("tq.$host").remove("a.$host")
            .apply()
    }

    /**
     * 套用到 WebView。
     *
     * 这些设置都是「文档创建时读取」的，页面已经跑起来再改对当前页无效，
     * 所以调用点必须在 loadUrl 之前（导航开始、onPageStarted 各来一次最稳）。
     *
     * 注意：JS 关掉后页面多半直接白屏，这属于用户自己的选择，
     * 但 SiteInfoSheet 里必须写清楚，不能让人以为是浏览器坏了。
     */
    fun applyTo(webView: WebView, url: String?) {
        val host = SiteRuleEngine.hostOf(url) ?: return
        if (!hasOverride(host)) return
        val s: WebSettings = webView.settings

        desktop(host)?.let { want ->
            webView.settings.userAgentString =
                if (want) DESKTOP_UA else webView.settings.userAgentString.let { current ->
                    // 从桌面版切回手机版：全局也可能是桌面版，这里只负责「本页」
                    if (current == DESKTOP_UA) MOBILE_UA else current
                }
        }
        textZoom(host).takeIf { it > 0 }?.let { s.textZoom = it }
        javascript(host)?.let { s.javaScriptEnabled = it }
        images(host)?.let { s.loadsImagesAutomatically = it }
    }

    private fun optBool(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    private fun putBool(key: String, value: Boolean?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putBoolean(key, value)
        }.apply()
    }

    companion object {
        private const val PREFS = "pb_site_prefs"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
