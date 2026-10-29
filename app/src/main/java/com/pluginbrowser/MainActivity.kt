package com.pluginbrowser

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
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

    /** 已经接管、正在跟手的边缘拖动：此时事件不再下发给网页 */
    private var edgeDragging = false
    /** 跟手期间被平移的那一层（当前标签的网页容器） */
    private var edgeView: View? = null
    /** 本次手势最终要执行的动作，在按下时决定，避免抬手指时才反悔 */
    private var edgeAction = ACTION_NONE
    private var touchSlop = 0
    /** 边缘拖动的速度采样：轻快一划就该生效，不用非滑够 72dp */
    private var velocity: VelocityTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsRepository(this)
        settings.applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

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

    override fun onResume() {
        super.onResume()
        // 布局此时可能还没算完，先试一次，下一帧再补一次
        refreshGestureExclusion()
        findViewById<View>(R.id.main_container)?.post { refreshGestureExclusion() }
    }

    /**
     * 声明「屏幕左右这几 dp 归我」，从系统返回手势手里抢回边缘滑动。
     *
     * 这是个真实存在的冲突，不是理论问题：本应用边缘手势默认 22dp，
     * 而 Android 10 起系统返回手势会先吃掉边缘约 20dp 的滑动，两者完全重叠。
     * 用户从边缘往里滑，起点落在 20dp 内就被系统判成「返回」（在首页即退出 App），
     * 落在 20~22dp 之间才轮到我们后退网页。同一种手法两种结果，体感就是
     * 「有时候莫名其妙退出去」。
     *
     * 只在当前页面确实还能后退/前进时才占位；不能后退时让给系统，
     * 跟 Chrome 的行为一致 —— 不会出现「滑了半天什么都不发生」。
     */
    fun refreshGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val root = findViewById<View>(R.id.main_container) ?: return
        if (!::settings.isInitialized || !settings.gestureEnabled) {
            root.systemGestureExclusionRects = emptyList()
            return
        }
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        val edge = (settings.gestureEdgeWidthDp * resources.displayMetrics.density)
            .toInt()
            .coerceIn(1, w / 4)
        // 系统对排除区总量有 200dp 上限，两侧各 22dp 远在限额内
        val rects = ArrayList<Rect>(2)
        if (gestureReady(SIDE_LEFT)) rects.add(Rect(0, 0, edge, h))
        if (gestureReady(SIDE_RIGHT)) rects.add(Rect(w - edge, 0, w, h))
        root.systemGestureExclusionRects = rects
    }

    private fun gestureReady(side: Int): Boolean {
        val action = if (side == SIDE_LEFT) settings.gestureLeftEdge else settings.gestureRightEdge
        if (action == ACTION_NONE) return false
        return browserFragment()?.canPerformGestureAction(action) == true
    }

    /**
     * 手势分发：从左右边缘朝内滑出设定距离，执行用户配置的动作。
     *
     * 跟手：越过 touchSlop 之后就不再把事件交给网页，改为实时平移网页容器，
     * 松手时按位移决定「执行」还是「弹回」。原来的做法是全程静默、抬手才动，
     * 手指滑过去屏幕上什么都没发生，松手才突然跳 —— 这是「手感糙」的主要来源。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::settings.isInitialized || !settings.gestureEnabled) {
            return super.dispatchTouchEvent(ev)
        }

        val density = resources.displayMetrics.density
        val rootWidth = findViewById<View>(R.id.main_container)?.width
            ?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val edgeWidth = settings.gestureEdgeWidthDp * density
        val needDistance = settings.gestureDistanceDp * density

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                val doubleTapAction = settings.gestureDoubleTap
                if (doubleTapAction != ACTION_NONE && now - lastTapAt in 40..320) {
                    lastTapAt = 0
                    if (performGesture(doubleTapAction)) return true
                }
                lastTapAt = now

                // 用窗口宽度而不是物理屏宽：刘海屏/带侧边手势条的机型两者不相等，
                // 按物理宽度算会把右边缘判定区推到屏幕外
                edgeSide = when {
                    ev.x <= edgeWidth -> SIDE_LEFT
                    ev.x >= rootWidth - edgeWidth -> SIDE_RIGHT
                    else -> SIDE_NONE
                }
                edgeStartX = ev.x
                edgeStartY = ev.y
                edgeDragging = false
                edgeAction = ACTION_NONE

                if (edgeSide != SIDE_NONE) {
                    val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
                    vt.clear()
                    vt.addMovement(ev)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (edgeSide == SIDE_NONE) return super.dispatchTouchEvent(ev)
                velocity?.addMovement(ev)
                val dx = ev.x - edgeStartX
                val dy = abs(ev.y - edgeStartY)

                if (!edgeDragging) {
                    // 纵向意图明显，交还给网页滚动
                    if (dy > abs(dx) * 1.2f) {
                        edgeSide = SIDE_NONE
                        return super.dispatchTouchEvent(ev)
                    }
                    if (abs(dx) < touchSlop) return super.dispatchTouchEvent(ev)

                    val action = actionFor(edgeSide, dx)
                    if (action == ACTION_NONE) {
                        edgeSide = SIDE_NONE
                        return super.dispatchTouchEvent(ev)
                    }

                    // 从这里开始接管。网页已经收到 DOWN 和几个 MOVE，必须补一个
                    // CANCEL，否则它会停在「按下未抬起」的状态：按钮一直高亮，
                    // 下一次触摸还可能被并进同一段手势
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()

                    edgeDragging = true
                    edgeAction = action
                    edgeView = browserFragment()?.gestureTargetView()
                }

                // 跟手平移。超过 1/3 屏宽就不再跟，给出「到顶了」的手感
                val limit = resources.displayMetrics.widthPixels * 0.33f
                edgeView?.translationX = dx.coerceIn(-limit, limit) * 0.85f
                return true
            }

            MotionEvent.ACTION_UP -> {
                val side = edgeSide
                val dragging = edgeDragging
                val action = edgeAction
                edgeSide = SIDE_NONE
                edgeDragging = false
                edgeAction = ACTION_NONE
                if (!dragging) {
                    velocity?.recycle()
                    velocity = null
                    return super.dispatchTouchEvent(ev)
                }

                var vx = 0f
                velocity?.apply {
                    computeCurrentVelocity(1000)
                    vx = xVelocity
                }
                velocity?.recycle()
                velocity = null

                val dx = ev.x - edgeStartX
                val dy = abs(ev.y - edgeStartY)
                // 轻快一划也算数：只需要滑过一小段，速度够就行。
                // 条件是「速度和位移同一方向」，否则往回甩也会被当成前进
                val fling = abs(vx) >= density * 700f &&
                    abs(dx) >= touchSlop * 3 &&
                    (vx > 0) == (dx > 0)
                val passed = (abs(dx) >= needDistance || fling) && abs(dx) >= dy * 1.5f
                val view = edgeView
                edgeView = null
                when {
                    passed && side != SIDE_NONE && action != ACTION_NONE && performGesture(action) -> {
                        // 动作已生效，页面内容马上要换。先把这层推出去再复位，
                        // 视觉上就是「滑走 → 新页面」，比直接跳变自然
                        if (view != null) {
                            view.animate()
                                .translationX(if (dx > 0) view.width * 0.32f else -view.width * 0.32f)
                                .setDuration(120)
                                .withEndAction { view.translationX = 0f }
                                .start()
                        }
                    }
                    else -> view?.animate()?.translationX(0f)?.setDuration(160)?.start()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                edgeSide = SIDE_NONE
                edgeDragging = false
                edgeAction = ACTION_NONE
                velocity?.recycle()
                velocity = null
                val view = edgeView
                edgeView = null
                view?.animate()?.translationX(0f)?.setDuration(160)?.start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 按下位置在某一侧、且方向是朝屏幕内侧时，该执行哪个动作 */
    private fun actionFor(side: Int, dx: Float): String = when (side) {
        SIDE_LEFT -> if (dx > 0) settings.gestureLeftEdge else ACTION_NONE
        SIDE_RIGHT -> if (dx < 0) settings.gestureRightEdge else ACTION_NONE
        else -> ACTION_NONE
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
