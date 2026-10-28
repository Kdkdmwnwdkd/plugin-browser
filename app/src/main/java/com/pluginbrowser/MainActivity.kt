package com.pluginbrowser

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import kotlin.math.abs
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

    /* 手势：边缘滑动与双击。动作本身由 BrowserFragment 执行 */
    private var edgeSide = SIDE_NONE
    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private var lastTapAt = 0L

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
        // 上次要是横着出去的，这里给个交代：至少让下次能看到是哪里崩的
        maybeShowLastCrash()
    }

    /**
     * 上次进程异常结束时弹一次说明。
     *
     * 自用场景没人看 Logcat，App 又「点一下就没了」的时候，唯一的线索就是这里。
     * 同一份崩溃只弹一次，避免每次启动都挡一下。
     */
    private fun maybeShowLastCrash() {
        // 每个进程只查一次：旋转屏幕会重建 Activity，不能每次重建都弹一遍
        if (crashCheckedInProcess) return
        crashCheckedInProcess = true
        val summary = com.pluginbrowser.util.CrashLogger.lastRunCrash(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(summary)
            .setNeutralButton("恢复标签") { _, _ ->
                // 崩溃后标签本来全丢了，这里直接从上次会话铺回来
                browserFragment()?.restoreLastSession()
            }
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", summary))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

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

    /**
     * 手势分发：从左右边缘朝内滑出设定距离，执行用户配置的动作。
     * 在 dispatch 层拦截，页面内部横向滚动不受影响；纵向意图明显时主动让行。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::settings.isInitialized || !settings.gestureEnabled) {
            return super.dispatchTouchEvent(ev)
        }

        val density = resources.displayMetrics.density
        val edgeWidth = settings.gestureEdgeWidthDp * density
        val needDistance = settings.gestureDistanceDp * density

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = android.os.SystemClock.uptimeMillis()
                val doubleTapAction = settings.gestureDoubleTap
                if (doubleTapAction != ACTION_NONE && now - lastTapAt in 40..320) {
                    lastTapAt = 0
                    if (performGesture(doubleTapAction)) return true
                }
                lastTapAt = now

                val screenWidth = resources.displayMetrics.widthPixels
                edgeSide = when {
                    ev.x <= edgeWidth -> SIDE_LEFT
                    ev.x >= screenWidth - edgeWidth -> SIDE_RIGHT
                    else -> SIDE_NONE
                }
                edgeStartX = ev.x
                edgeStartY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (edgeSide != SIDE_NONE &&
                    abs(ev.y - edgeStartY) > abs(ev.x - edgeStartX) * 1.2f
                ) {
                    // 明显在上下滑，交还给页面滚动
                    edgeSide = SIDE_NONE
                }
            }

            MotionEvent.ACTION_UP -> {
                val side = edgeSide
                edgeSide = SIDE_NONE
                if (side == SIDE_NONE) return super.dispatchTouchEvent(ev)

                val dx = ev.x - edgeStartX
                val dy = abs(ev.y - edgeStartY)
                if (abs(dx) < needDistance || abs(dx) < dy * 1.5f) {
                    return super.dispatchTouchEvent(ev)
                }
                val action = when (side) {
                    SIDE_LEFT -> if (dx > 0) settings.gestureLeftEdge else ACTION_NONE
                    else -> if (dx < 0) settings.gestureRightEdge else ACTION_NONE
                }
                if (action != ACTION_NONE && performGesture(action)) return true
            }

            MotionEvent.ACTION_CANCEL -> edgeSide = SIDE_NONE
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun performGesture(action: String): Boolean =
        browserFragment()?.performGestureAction(action) == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 使用期间打这个标记；正常退到后台会清掉。
        // 下次启动还亮着 → 上次是在前台「突然没了」，正是要抓的那种崩溃
        com.pluginbrowser.util.CrashLogger.markRunning(this)
    }

    override fun onStop() {
        com.pluginbrowser.util.CrashLogger.markClean(this)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 系统开始喊内存紧张了：把看不见的标签全部降级，先保住正在看的这一页
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            ::tabManager.isInitialized
        ) {
            tabManager.trimInactive()
        }
    }

    override fun onDestroy() {
        // 释放 WebView 原生内存（重建/退出都不会自动回收）。
        // 但只在真退出时销毁：配置变化、系统回收重建时销毁掉，重开就是一片空白
        if (::tabManager.isInitialized) {
            if (isFinishing) {
                tabManager.destroyAll()
                com.pluginbrowser.util.CrashLogger.markClean(this)
            } else {
                tabManager.pauseAll()
            }
        }
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

    companion object {
        private const val SIDE_NONE = 0
        private const val SIDE_LEFT = 1
        private const val SIDE_RIGHT = 2
        private const val ACTION_NONE = "none"

        /** 崩溃提示每个进程只弹一次（Activity 重建不算新进程） */
        private var crashCheckedInProcess = false
    }
}
