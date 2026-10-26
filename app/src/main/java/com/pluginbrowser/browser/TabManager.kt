package com.pluginbrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import com.pluginbrowser.data.SettingsRepository
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
    private val sessionPrefs = activity.getSharedPreferences("pb_session", Context.MODE_PRIVATE)

    /* ---------------- 标签操作 ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String? = null, incognito: Boolean = incognitoMode, select: Boolean = true): Tab {
        // 标签数上限：超出时先回收最早的非当前标签，避免每个标签一个 WebView 把内存吃满
        if (tabs.size >= MAX_TABS) {
            val victim = tabs.indexOfFirst { it !== currentTab() }
            if (victim >= 0) closeTab(victim)
        }
        val tab = Tab(url = url ?: "", incognito = incognito)
        val wv = WebView(webViewContext())
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, settings.thirdPartyCookies)
        webViewConfigurator?.invoke(tab, wv)
        tab.webView = wv

        tabs.add(tab)
        if (select) {
            currentIndex = tabs.size - 1
            listener.onCurrentTabChanged(tab)
        }
        listener.onTabsChanged()
        if (!url.isNullOrBlank()) wv.loadUrl(url)
        return tab
    }

    fun currentTab(): Tab? = tabs.getOrNull(currentIndex)

    /** 当前标签的 WebView（未创建或没有标签时返回 null），手势与查找用 */
    fun currentWebViewOrNull(): WebView? = currentTab()?.takeIf { it.isCreated }?.webView

    fun findTab(webView: WebView): Tab? = tabs.firstOrNull { it.isCreated && it.webView === webView }

    fun switchTo(index: Int) {
        if (index !in tabs.indices || index == currentIndex) return
        currentIndex = index
        listener.onCurrentTabChanged(currentTab())
        listener.onTabsChanged()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
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

    /**
     * WebView 的 prefers-color-scheme 只认自己的 Configuration，不跟随 AppCompatDelegate。
     * App 强制浅色、系统却是深色时，会出现「浏览器 UI 浅色、页面深色」的矛盾，
     * 所以手动给它一个与 App 主题一致的 context。
     */
    private fun webViewContext(): Context {
        val mode = settings.theme
        if (mode != "light" && mode != "dark") return activity
        val config = Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return activity.createConfigurationContext(config)
    }

    fun pauseAll() {
        tabs.forEach { runCatching { it.webView.onPause() } }
    }

    /** 释放全部标签的 WebView（Activity 销毁时调用，避免原生内存泄漏） */
    fun destroyAll() {
        tabs.forEach { tab ->
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

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 标签数上限（每个标签持有独立 WebView，过多会 OOM） */
        private const val MAX_TABS = 20

        private const val KEY_SESSION = "session_tabs"
    }
}
