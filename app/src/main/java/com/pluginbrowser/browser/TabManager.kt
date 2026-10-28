package com.pluginbrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.site.SitePreferences
import com.pluginbrowser.site.SiteRuleEngine
import com.pluginbrowser.util.EventLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多标签管理器（由 MainActivity 持有，跨 Fragment 共享）。
 * 每个 Tab 持有独立 WebView；切换标签即替换容器中的子 View。
 */
class TabManager(
    private val activity: FragmentActivity,
    private val listener: Listener,
) {

    interface Listener {
        fun onTabsChanged()
        fun onCurrentTabChanged(tab: Tab?)
    }

    /** 由 BrowserFragment 注入：WebView 创建后配置客户端与 Bridge */
    var webViewConfigurator: ((Tab, WebView) -> Unit)? = null

    /** 下载拦截：返回 true 表示已处理（contentDisposition / mimeType 可能为空） */
    var onDownloadHandler: ((String, String, String?, String?, Long) -> Boolean)? = null

    val tabs = ArrayList<Tab>()
    var currentIndex = -1
        private set

    /** 无痕模式：新建标签默认为无痕标签 */
    var incognitoMode = false

    private val settings = SettingsRepository(activity)
    private val sitePrefs = SitePreferences(activity)
    private val siteRules = SiteRuleEngine(activity)
    private val sessionPrefs = activity.getSharedPreferences("pb_session", Context.MODE_PRIVATE)

    init {
        // 心跳要能报出「当时开了几个标签」，这是判断内存压力的关键分母
        EventLog.tabCountProvider = { tabs.size }
    }

    /* ---------------- 标签操作 ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String? = null, incognito: Boolean = incognitoMode, select: Boolean = true): Tab {
        // 标签数上限：超出时先回收最早的非当前标签，避免每个标签一个 WebView 把内存吃满
        if (tabs.size >= MAX_TABS) {
            val victim = tabs.indexOfFirst { it !== currentTab() }
            if (victim >= 0) closeTab(victim)
        }
        val tab = Tab(url = url ?: "", incognito = incognito)
        val wv = KiteWebView(webViewContext())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 当前标签的渲染进程保持 IMPORTANT：被降级后切回来要整页重新布局，观感就是"卡一下"
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        with(wv.settings) {
            javaScriptEnabled = settings.javascriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            // 弹窗必须由用户手势触发，避免广告页无手势批量开标签
            javaScriptCanOpenWindowsAutomatically = false
            // 音视频需用户手势才播放，避免后台偷跑流量
            mediaPlaybackRequiresUserGesture = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (settings.desktopUa) {
                userAgentString = DESKTOP_UA
            } else {
                when (settings.uaPreset) {
                    "quark" -> userAgentString = SettingsRepository.UA_QUARK
                    "baidu" -> userAgentString = SettingsRepository.UA_BAIDU
                }
            }
        }
        wv.setDownloadListener { dlUrl, userAgent, cd, mime, len ->
            onDownloadHandler?.invoke(dlUrl, userAgent, cd, mime, len)
        }
        // 站点适配里必须「加载前」生效的那部分。放在 loadUrl 之前：
        // 自动播放策略是文档创建时读取的，页面跑起来再改对当前页无效
        siteRules.applyTo(wv, url)
        sitePrefs.applyTo(wv, url)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, settings.thirdPartyCookies)
        webViewConfigurator?.invoke(tab, wv)
        tab.webView = wv

        tabs.add(tab)
        if (select) {
            val previous = currentTab()
            currentIndex = tabs.size - 1
            if (previous !== null && previous !== tab) demote(previous)
            listener.onCurrentTabChanged(tab)
        }
        listener.onTabsChanged()
        EventLog.log(
            "TAB",
            "new #${System.identityHashCode(tab)} total=${tabs.size} " +
                "incog=$incognito url=${if (incognito) "-" else hostOf(url)}",
        )
        if (!url.isNullOrBlank()) wv.loadUrl(url)
        return tab
    }

    fun currentTab(): Tab? = tabs.getOrNull(currentIndex)

    /** 当前标签的 WebView（未创建、已销毁或没有标签时返回 null），手势与查找用 */
    fun currentWebViewOrNull(): WebView? = currentTab()?.takeIf { it.usable }?.webView

    /**
     * 拿一个「现在就能加载页面」的标签。
     *
     * 界面上残留的是已销毁 WebView 时，currentTab() 依然非空，直接 loadUrl 会毫无反应。
     * 这里统一兜底：不可用就新开一个，保证调用方永远拿得到一个能动的 WebView。
     */
    fun requireLoadableTab(): Tab {
        val tab = currentTab()
        if (tab != null && tab.usable) return tab
        return newTab(url = settings.homeUrl)
    }

    fun findTab(webView: WebView): Tab? = tabs.firstOrNull { it.usable && it.webView === webView }

    fun switchTo(index: Int) {
        if (index !in tabs.indices || index == currentIndex) return
        val previous = currentTab()
        currentIndex = index
        if (previous !== currentTab()) demote(previous)
        promote(currentTab())
        listener.onCurrentTabChanged(currentTab())
        listener.onTabsChanged()
    }

    /**
     * 后台标签的渲染进程降级。
     *
     * 每个 WebView 都是一整个渲染进程，三个重页面同时「活着」，中低端机很容易被
     * 系统判定内存不足直接杀掉（表现就是「用着用着突然回到桌面」，没有报错弹窗）。
     * 降级后系统可以优先回收看不见的那些，切回来再提上去，页面状态不丢。
     */
    private fun demote(tab: Tab?) {
        if (tab == null || !tab.usable) return
        runCatching { tab.webView.onPause() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                tab.webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }
        }
    }

    private fun promote(tab: Tab?) {
        if (tab == null || !tab.usable) return
        runCatching { tab.webView.onResume() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                tab.webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
        }
    }

    /** 内存吃紧时只留当前标签，其余全部降级 */
    fun trimInactive() {
        EventLog.log("TAB", "trimInactive total=${tabs.size}")
        tabs.forEach { if (it !== currentTab()) demote(it) }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        tab.alive = false
        EventLog.log("TAB", "close #${System.identityHashCode(tab)} left=${tabs.size - 1}")
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        runCatching { tab.webView.destroy() }
        tabs.removeAt(index)
        currentIndex = if (tabs.isEmpty()) -1 else index.coerceAtMost(tabs.size - 1)
        if (tabs.isEmpty()) {
            newTab(url = settings.homeUrl, incognito = incognitoMode)
        } else {
            listener.onCurrentTabChanged(currentTab())
        }
        listener.onTabsChanged()
    }

    fun closeAll() {
        tabs.toList().forEach { tab ->
            tab.alive = false
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            runCatching { tab.webView.destroy() }
        }
        tabs.clear()
        currentIndex = -1
        newTab(url = settings.homeUrl, incognito = incognitoMode)
        listener.onTabsChanged()
    }

    /** 把当前标签的 WebView 挂到容器上 */
    fun attachTo(container: ViewGroup) {
        container.removeAllViews()
        val tab = currentTab() ?: return
        // 销毁过的 WebView 不能再挂回界面：挂上去就是一张不动的静止画面
        if (!tab.alive) return
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        container.addView(
            tab.webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        tab.webView.onResume()
    }

    /** 容器里挂的到底是不是当前标签的 WebView（界面与标签错位时靠它发现） */
    fun isAttached(container: ViewGroup): Boolean {
        val tab = currentTab() ?: return false
        return tab.alive && tab.webView.parent === container
    }

    /**
     * 把 WebView 从容器里摘下来（不销毁）。
     *
     * 打开设置、插件商店这类页面是 replace 掉整个 BrowserFragment，容器会被回收。
     * 如果 WebView 还挂在那个正在销毁的 ViewGroup 上，它就同时持有一个死掉的父容器，
     * 回来重新 addView 时轻则空白、重则崩在 ViewRootImpl 里。
     */
    fun detachFrom(container: ViewGroup) {
        tabs.forEach { tab ->
            if (tab.isCreated && tab.webView.parent === container) {
                runCatching { container.removeView(tab.webView) }
            }
        }
        runCatching { container.removeAllViews() }
    }

    /**
     * WebView 的 prefers-color-scheme 只认自己的 Configuration，不跟随 AppCompatDelegate。
     * App 强制浅色、系统却是深色时，会出现「浏览器 UI 浅色、页面深色」的矛盾，
     * 所以手动给它一个与 App 主题一致的 context。
     *
     * createConfigurationContext 会复制一整套 Resources，开销不小，按主题缓存一份复用。
     */
    private var themeContext: Pair<String, Context>? = null

    private fun webViewContext(): Context {
        val mode = settings.theme
        if (mode != "light" && mode != "dark") return activity
        themeContext?.let { if (it.first == mode) return it.second }
        val config = Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return activity.createConfigurationContext(config).also { themeContext = mode to it }
    }

    fun pauseAll() {
        tabs.forEach { runCatching { it.webView.onPause() } }
    }

    /** 释放全部标签的 WebView（Activity 销毁时调用，避免原生内存泄漏） */
    fun destroyAll() {
        tabs.forEach { tab ->
            tab.alive = false
            if (!tab.isCreated) return@forEach
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            runCatching {
                tab.webView.stopLoading()
                tab.webView.loadUrl("about:blank")
                tab.webView.destroy()
            }
        }
        tabs.clear()
        currentIndex = -1
    }

    /* ---------------- 会话保存与恢复 ---------------- */

    fun saveSession() {
        val arr = JSONArray()
        tabs.forEach { tab ->
            if (!tab.incognito && tab.url.isNotBlank() && !tab.url.startsWith("data:")) {
                arr.put(JSONObject().put("url", tab.url))
            }
        }
        sessionPrefs.edit().putString(KEY_SESSION, arr.toString()).apply()
    }

    /** 恢复上次会话；返回是否有可恢复内容 */
    fun restoreSession(): Boolean {
        val raw = sessionPrefs.getString(KEY_SESSION, null) ?: return false
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return false
            for (i in 0 until arr.length()) {
                val url = arr.getJSONObject(i).optString("url")
                newTab(url = url, incognito = false, select = i == 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isHomeUrl(url: String): Boolean =
        url.isBlank() || url == settings.homeUrl || url.startsWith("file:///android_asset/home.html")

    /** 日志只留域名：完整 URL 里可能带 token、会话 ID */
    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "blank"
        return runCatching {
            val u = android.net.Uri.parse(url)
            u.host ?: u.scheme ?: "-"
        }.getOrDefault("-")
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 标签数上限（每个标签持有独立 WebView）。
         *
         * 一个标签就是一个渲染进程，手机上同时活着的重页面越多，被系统直接回收的概率越高。
         * 20 个纯属理论值，真机上到五六个就开始吃不消，收到 8 个，超了先回收最早的非当前标签。
         */
        private const val MAX_TABS = 8

        private const val KEY_SESSION = "session_tabs"
    }
}
