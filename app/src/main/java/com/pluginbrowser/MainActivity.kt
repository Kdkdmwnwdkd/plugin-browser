package com.pluginbrowser

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.pluginbrowser.browser.BrowserFragment
import com.pluginbrowser.browser.Tab
import com.pluginbrowser.browser.TabManager
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.api.BrowserHost
import com.pluginbrowser.plugin.api.PluginMenuItem

/**
 * 主界面：仅作为 Fragment 容器，持有跨 Fragment 共享的 TabManager，
 * 并向插件系统提供 BrowserHost 实现。
 */
class MainActivity : AppCompatActivity(), TabManager.Listener, BrowserHost {

    lateinit var tabManager: TabManager
        private set

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsRepository(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()

        tabManager = TabManager(this, this)

        // 向插件系统提供宿主能力
        PluginBrowserApp.from(this).pluginManager.hostProvider = { this }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, BrowserFragment())
                .commitNow()
        }

        handleIntent(intent)
    }

    /** 状态栏/导航栏跟随页面底色，避免首页深色、状态栏浅色那种断层 */
    private fun applySystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val value = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
        val surface = if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = surface
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        // 释放 WebView 原生内存（重建/退出都不会自动回收）
        if (::tabManager.isInitialized) tabManager.destroyAll()
        // Application 级的 PluginManager 持有 hostProvider={ this }，不置空会泄漏整个 Activity
        if (::tabManager.isInitialized) {
            PluginBrowserApp.from(this).pluginManager.hostProvider = null
        }
        super.onDestroy()
    }

    /** 处理外部 App 分享进来的链接 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data: Uri = intent.data ?: return
        val url = data.toString()
        intent.action = Intent.ACTION_MAIN
        intent.data = null
        // 回到浏览器界面再打开
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        browserFragment()?.openUrl(url)
    }

    private fun browserFragment(): BrowserFragment? =
        supportFragmentManager.findFragmentById(R.id.main_container) as? BrowserFragment

    /** 供书签/历史/插件调用：打开 URL */
    fun openUrl(url: String) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        browserFragment()?.openUrl(url)
    }

    /* ---------------- TabManager.Listener ---------------- */

    override fun onTabsChanged() {
        browserFragment()?.refreshUi()
    }

    override fun onCurrentTabChanged(tab: Tab?) {
        browserFragment()?.onCurrentTabChanged(tab)
    }

    /* ---------------- BrowserHost（供原生插件调用） ---------------- */

    override val currentUrl: String?
        get() = browserFragment()?.currentTabUrl()

    override fun openTab(url: String, incognito: Boolean) {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        browserFragment()?.openUrl(url, incognito)
    }

    override fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun getSetting(key: String, default: String?): String? = when (key) {
        "search_engine" -> settings.searchEngine
        "home_url" -> settings.homeUrl
        "theme" -> settings.theme
        else -> default
    }

    override val isIncognito: Boolean
        get() = tabManager.incognitoMode

    /** 供 BrowserFragment 调用：把原生插件菜单项分发出去 */
    fun pluginMenuItems(): List<Pair<PluginMenuItem, com.pluginbrowser.plugin.api.NativePlugin>> {
        val pm: PluginManager = PluginBrowserApp.from(this).pluginManager
        return pm.enabledNativePlugins().flatMap { p ->
            p.menuItems().map { it to p }
        }
    }

    fun dispatchPluginMenuItem(item: PluginMenuItem): Boolean {
        val pm: PluginManager = PluginBrowserApp.from(this).pluginManager
        return pm.enabledNativePlugins().any { it.onMenuItemClicked(item) }
    }
}
