package com.pluginbrowser.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.SpannableString
import android.graphics.Typeface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.pm.PackageManager
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.pluginbrowser.MainActivity
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.download.DownloadEngine
import com.pluginbrowser.download.DownloadPrompt
import com.pluginbrowser.download.DownloadService
import com.pluginbrowser.download.DownloadsFragment
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.api.PluginMenuItem
import com.pluginbrowser.plugin.js.JsBridge
import com.pluginbrowser.plugin.store.PluginStoreFragment
import com.pluginbrowser.settings.SettingsFragment
import com.pluginbrowser.site.AppHandoff
import com.pluginbrowser.site.ElementBlocker
import com.pluginbrowser.site.PermKind
import com.pluginbrowser.site.ReaderMode
import com.pluginbrowser.site.SiteAction
import com.pluginbrowser.site.SiteInfoSheet
import com.pluginbrowser.site.SitePermissions
import com.pluginbrowser.site.SitePreferences
import com.pluginbrowser.site.SiteRuleEngine
import com.pluginbrowser.site.SiteScripts
import com.pluginbrowser.translate.TranslateManager
import com.pluginbrowser.translate.TranslateSettingsFragment
import com.pluginbrowser.ui.BookmarksFragment
import com.pluginbrowser.util.AddressSubmit
import com.pluginbrowser.util.UrlUtils
import kotlinx.coroutines.launch

/**
 * 浏览器主界面：地址栏、多标签 WebView 容器、底部工具栏、
 * 溢出菜单（含原生插件菜单项）、下载、长按上下文菜单、全屏视频。
 */
class BrowserFragment : Fragment(R.layout.fragment_browser),
    BrowserWebViewClient.Owner,
    BrowserWebChromeClient.Owner {

    /* ---------------- 依赖 ---------------- */

    private val settings by lazy { SettingsRepository(requireContext()) }
    private val store by lazy { BrowserStore(requireContext()) }
    private val pluginManager: PluginManager
        get() = PluginBrowserApp.from(requireContext()).pluginManager
    private val tabManager: TabManager get() = (requireActivity() as MainActivity).tabManager
    private val translateManager by lazy { TranslateManager(requireContext()) }

    private lateinit var webViewClient: BrowserWebViewClient
    private lateinit var webChromeClient: BrowserWebChromeClient
    private lateinit var suggestionAdapter: SuggestionAdapter

    /* ---------------- 视图 ---------------- */

    private lateinit var progressBar: View
    private lateinit var addressBarRow: View
    private lateinit var securityIcon: ImageView
    private lateinit var addressInput: AutoCompleteTextView
    private lateinit var incognitoBadge: TextView
    private lateinit var webContainer: FrameLayout
    private lateinit var bottomBar: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnTabs: View
    private lateinit var btnMenu: ImageButton
    private lateinit var tabCountBadge: TextView
    private lateinit var handoffBar: View
    private lateinit var handoffText: TextView
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var findQuery: String = ""

    /* ---------------- 状态 ---------------- */

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    /** 全屏视频是不是被站点规则锁成了横屏（只有锁过才需要还原） */
    private var landscapeLocked = false
    /** 阅读模式当前文章：调字号时不用重新提取 */
    private var readerArticle: ReaderMode.Article? = null
    private var readerScale = 1f
    /** 刚跳去过外部 App 的地址，用于「再打开」 */
    private var handoffTarget: String? = null
    private val handoff by lazy { AppHandoff(requireContext()) }
    private val hideHandoffBar = Runnable {
        if (::handoffBar.isInitialized) handoffBar.isVisible = false
    }
    private var pendingImageUrl: String? = null
    private var pendingLinkUrl: String? = null
    private var pendingDownload: PendingDownload? = null
    private lateinit var downloadPrompt: DownloadPrompt
    private var pluginActionMap = mutableMapOf<String, PluginMenuItem>()
    private val suggestionHandler = Handler(Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String?,
        val mimeType: String?,
        val fileName: String = "",
        val size: Long = -1,
    )

    /* ---------------- 结果回调 ---------------- */

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            callback.onReceiveValue(arrayOf(uri))
        } else {
            callback.onReceiveValue(null)
        }
    }

    private val notifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不影响其他逻辑，插件下次调用 notify 时会重新检查 */ }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (granted) {
            startDownload(pending)
        } else {
            toast(getString(R.string.downloads_permission_needed))
        }
    }

    /** 只影响通知栏进度是否可见，拒绝了也照常下载 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast(getString(R.string.download_notification_denied))
    }

    /* ---------------- 返回键 ---------------- */

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val tab = tabManager.currentTab()
            val url = tab?.let { it.failedUrl ?: it.url }.orEmpty()
            when {
                customView != null -> exitFullscreen()
                // 错误页优先判断。
                // 之前这条排在「空白/首页」后面，而错误页的失败地址有可能就是
                // about:blank（页面还没起来就挂了），isHomeUrl 一认，返回键直接把
                // App 关掉——用户点了个打不开的链接，回头整个浏览器没了。
                tab?.failedUrl != null -> {
                    val wv = currentWebView()
                    if (wv?.canGoBack() == true) wv.goBack() else navigateTo(settings.homeUrl)
                }
                // 首页（或空标签）按返回就退出，不要停在一片空白上
                url.isBlank() || tabManager.isHomeUrl(url) -> {
                    isEnabled = false
                    requireActivity().finish()
                }
                currentWebView()?.canGoBack() == true -> currentWebView()?.goBack()
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    /**
     * 两级超时。
     *
     * 以前是「20 秒还没到 100% 就当失败」——但 GitHub 这类站点会把几十个请求打到
     * 境外 CDN，弱网下一个资源挂住，页面其实早就画出来了，却被换成错误页。
     * 现在拆开：
     *  - 首帧 12 秒还不出现，说明 DNS / TLS / 握手真的卡住了，才报错；
     *  - 已经可见的话只等资源，60 秒后收掉进度条，内容留给用户看。
     */
    private var firstPaintTimeout: Runnable? = null
    private var settleTimeout: Runnable? = null
    private var navWatchdog: Runnable? = null
    private var navRetried = false

    private fun scheduleLoadTimeouts(tab: Tab) {
        val host = view ?: return
        clearLoadTimeouts()

        val firstPaint = Runnable {
            // WebView 可能已经跟着标签一起销毁了，这时候再碰它就不是「没反应」而是直接崩
            if (!tab.usable) return@Runnable
            if (tab.failedUrl == null && tab.url.isNotBlank() &&
                !tab.committed && tab.progress <= 10
            ) {
                runCatching { tab.webView.stopLoading() }
                onErrorPageNeeded(tab, android.webkit.WebViewClient.ERROR_TIMEOUT)
            }
        }
        val settle = Runnable {
            if (!tab.usable) return@Runnable
            if (tab.failedUrl == null && tab.progress < 100) {
                runCatching { tab.webView.stopLoading() }
                tab.progress = 100
                if (tab === tabManager.currentTab()) refreshUi()
            }
        }
        firstPaintTimeout = firstPaint
        settleTimeout = settle
        host.postDelayed(firstPaint, FIRST_PAINT_TIMEOUT_MS)
        host.postDelayed(settle, SETTLE_TIMEOUT_MS)
    }

    private fun clearLoadTimeouts() {
        val host = view ?: return
        firstPaintTimeout?.let { host.removeCallbacks(it) }
        settleTimeout?.let { host.removeCallbacks(it) }
        firstPaintTimeout = null
        settleTimeout = null
    }

    /* ---------------- 生命周期 ---------------- */

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        webViewClient = BrowserWebViewClient(this)
        webChromeClient = BrowserWebChromeClient(this)
        suggestionAdapter = SuggestionAdapter(requireContext())
        downloadPrompt = DownloadPrompt(this, settings)

        progressBar = view.findViewById(R.id.progress_bar)
        addressBarRow = view.findViewById(R.id.address_bar_row)
        securityIcon = view.findViewById(R.id.security_icon)
        securityIcon.setOnClickListener { showSiteInfo() }
        addressInput = view.findViewById(R.id.address_input)
        incognitoBadge = view.findViewById(R.id.incognito_badge)
        webContainer = view.findViewById(R.id.web_container)
        bottomBar = view.findViewById(R.id.bottom_bar)
        btnBack = view.findViewById(R.id.btn_back)
        btnForward = view.findViewById(R.id.btn_forward)
        btnReload = view.findViewById(R.id.btn_reload)
        btnHome = view.findViewById(R.id.btn_home)
        btnTabs = view.findViewById(R.id.btn_tabs)
        btnMenu = view.findViewById(R.id.btn_menu)
        tabCountBadge = view.findViewById(R.id.tab_count_badge)
        handoffBar = view.findViewById(R.id.handoff_bar)
        handoffText = view.findViewById(R.id.handoff_text)
        view.findViewById<View>(R.id.handoff_close).setOnClickListener {
            handoffBar.removeCallbacks(hideHandoffBar)
            handoffBar.isVisible = false
        }
        view.findViewById<View>(R.id.handoff_reopen).setOnClickListener {
            val target = handoffTarget
            handoffBar.isVisible = false
            if (target.isNullOrBlank()) {
                toast("那个链接已经失效了")
            } else {
                launchExternal(target, SiteRuleEngine.hostOf(tabManager.currentTab()?.url).orEmpty())
            }
        }

        setupAddressBar()
        setupFindBar()
        setupBottomBar()

        // 配置 WebView 工厂（必须在创建标签之前）
        tabManager.webViewConfigurator = { tab, wv -> configureWebView(tab, wv) }
        tabManager.selectionActionHandler = { action, text ->
            view?.post { handleSelectionAction(action, text) }
        }
        tabManager.elementPickedHandler = { selector, label ->
            view?.post {
                if (selector.isBlank()) toast("已退出屏蔽模式") else onElementPicked(selector, label)
            }
        }
        tabManager.onDownloadHandler = { url, ua, cd, mime, len ->
            onDownloadRequested(url, ua, cd, mime, len)
        }

        if (tabManager.tabs.isEmpty()) {
            if (!tabManager.restoreSession()) {
                tabManager.newTab(url = settings.homeUrl)
            }
        }
        tabManager.attachTo(webContainer)
        refreshUi()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onStart() {
        super.onStart()
        if (!::webContainer.isInitialized) return
        // 只有真的错位时才重新挂，避免每次回到前台都白白 detach/attach 一遍
        if (!tabManager.isAttached(webContainer)) {
            // 错位的另一种可能是「容器里那张 WebView 已经销毁了」，换一张能用的
            tabManager.requireLoadableTab()
            tabManager.attachTo(webContainer)
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        currentWebView()?.onResume()
        refreshUi()
        showHandoffBar()
    }

    override fun onPause() {
        // 配了「后台播放」的站点（B站、YouTube）切出去别暂停：
        // WebView.onPause() 会把媒体一起停掉，锁屏听播客/听课就断了
        val backgroundPlay =
            siteRules.has(tabManager.currentTab()?.url, SiteAction.BACKGROUND_PLAY)
        if (!backgroundPlay) currentWebView()?.onPause()
        tabManager.saveSession()
        super.onPause()
    }

    override fun onDestroyView() {
        tabManager.selectionActionHandler = null
        tabManager.elementPickedHandler = null
        tabManager.pauseAll()
        clearLoadTimeouts()
        cancelNavigateWatchdog()
        // 全屏时锁过横屏的话，界面销毁前一定要放开：
        // 转屏、被系统回收都会走到这里，不放就会留在一个转不回来的横屏上
        if (landscapeLocked) {
            landscapeLocked = false
            runCatching {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (customView != null) {
            (customView?.parent as? ViewGroup)?.removeView(customView)
            customView = null
            showSystemBars()
        }
        // 容器马上要销毁了，先把 WebView 摘下来，别让它抱着一个死掉的父 View
        if (::webContainer.isInitialized) tabManager.detachFrom(webContainer)
        if (::downloadPrompt.isInitialized) downloadPrompt.dismiss()
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        super.onDestroyView()
    }

    /* ---------------- 界面组装 ---------------- */

    private fun setupAddressBar() {
        addressInput.setAdapter(suggestionAdapter)
        addressInput.threshold = 1
        addressInput.addTextChangedListener { editable ->
            updateAddressAction()
            querySuggestions(editable?.toString() ?: "")
        }
        // 输入法给的「前往 / 搜索 / 完成」都算提交；有的输入法（尤其带皮肤的三方键盘）
        // 干脆只发一个原始回车键事件，连 actionId 都不给，所以两种都要接住。
        addressInput.setOnEditorActionListener { _, actionId, event ->
            if (isSubmitAction(actionId, event)) {
                navigate()
                true
            } else {
                false
            }
        }
        addressInput.setOnKeyListener { _, keyCode, event ->
            val enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (enter && event.action == KeyEvent.ACTION_DOWN) {
                navigate()
                true
            } else {
                false
            }
        }
        addressInput.setOnItemClickListener { _, _, position, _ ->
            suggestionAdapter.getItem(position)?.let { navigateTo(it.url) }
        }
        addressInput.setOnFocusChangeListener { _, hasFocus ->
            updateAddressAction()
            if (hasFocus && suggestionAdapter.count > 0) {
                addressInput.postDelayed({
                    if (addressInput.hasFocus()) addressInput.showDropDown()
                }, 120)
            }
        }
        btnReload.setOnClickListener {
            // 正在输入时这颗按钮是「前往」，其余时候才是刷新 / 停止
            if (isAddressEditing()) navigate() else onReloadClicked()
        }
    }

    /** 地址栏里正有内容、并且焦点也在它身上 */
    private fun isAddressEditing(): Boolean =
        addressInput.hasFocus() && !addressInput.text.isNullOrBlank()

    /**
     * 判断这次 IME 回调是不是「提交地址栏内容」。
     *
     * 老代码只认 IME_ACTION_GO / IME_NULL，三方输入法发 DONE、SEARCH，
     * 或者干脆只发一个 KEYCODE_ENTER 的时候就被漏掉了——表现就是
     * 打完网址按回车，键盘收起来，页面一动不动。
     */
    private fun isSubmitAction(actionId: Int, event: KeyEvent?): Boolean =
        AddressSubmit.isSubmit(
            actionId = actionId,
            keyCode = event?.keyCode ?: 0,
            keyDown = event?.action == KeyEvent.ACTION_DOWN,
        )

    /** 地址栏右侧那颗按钮的图标：编辑时是「前往」，加载时是「停止」，平时是「刷新」 */
    private fun updateAddressAction() {
        if (!::btnReload.isInitialized || !::addressInput.isInitialized) return
        if (isAddressEditing()) {
            btnReload.setImageResource(R.drawable.ic_go)
            btnReload.contentDescription = getString(R.string.address_go)
            return
        }
        val loading = (tabManager.currentTab()?.progress ?: 0) in 1..99
        btnReload.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        btnReload.contentDescription = getString(if (loading) R.string.menu_stop else R.string.menu_refresh)
    }

    /* ---------------- 整页翻译 ---------------- */

    private fun toggleTranslate() {
        val tab = tabManager.currentTab() ?: return
        val webView = currentWebView() ?: return

        if (!translateManager.isConfigured()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.translate_need_config)
                .setMessage(R.string.translate_need_config_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.translate_go_settings) { _, _ ->
                    openScreen(TranslateSettingsFragment())
                }
                .show()
            return
        }

        if (tab.translated) {
            lifecycleScope.launch {
                translateManager.restore(webView)
                tab.translated = false
                toast(getString(R.string.translate_restored))
            }
            return
        }

        val content = layoutInflater.inflate(R.layout.dialog_translate_progress, null)
        val status = content.findViewById<TextView>(R.id.translate_status)
        val bar = content.findViewById<ProgressBar>(R.id.translate_progress)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_translate)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = translateManager.translatePage(webView) { done, total ->
                bar.progress = if (total == 0) 0 else done * 100 / total
                status.text = getString(R.string.translate_progress, done, total)
            }
            dialog.dismiss()
            result.onSuccess { count ->
                tab.translated = true
                toast(getString(R.string.translate_done, count))
            }.onFailure { error ->
                toast(getString(R.string.translate_failed, error.message ?: ""))
            }
        }
    }

    /* ---------------- 长截图 ---------------- */

    private fun captureLongScreenshot() {
        val webView = currentWebView() ?: return
        val content = layoutInflater.inflate(R.layout.dialog_capture_progress, null)
        val status = content.findViewById<TextView>(R.id.capture_status)
        val bar = content.findViewById<ProgressBar>(R.id.capture_progress)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.screenshot_title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = LongScreenshot.capture(webView) { done, total ->
                bar.progress = if (total == 0) 0 else done * 100 / total
                status.text = getString(R.string.screenshot_progress, done, total)
            }
            dialog.dismiss()
            result.onSuccess { bitmap ->
                val uri = LongScreenshot.saveToGallery(requireContext(), bitmap)
                bitmap.recycle()
                toast(
                    if (uri != null) getString(R.string.screenshot_saved)
                    else getString(R.string.screenshot_failed),
                )
            }.onFailure {
                toast(getString(R.string.screenshot_failed))
            }
        }
    }

    /* ---------------- 页内查找 ---------------- */

    /* ---------------- 会话恢复 ---------------- */

    /**
     * 崩溃/被系统杀掉之后，把上次的标签铺回来。
     *
     * 先关掉当前的空白首页：不关的话「恢复 5 个标签」会变成「首页 + 5 个」，
     * 而且首页会占着第一个位置，用户还得自己滑过去。
     */
    fun restoreLastSession() {
        if (!isAdded) return
        val blanks = tabManager.tabs.filter {
            it.url.isBlank() || tabManager.isHomeUrl(it.url)
        }
        blanks.forEach { tab ->
            val index = tabManager.tabs.indexOf(tab)
            if (index >= 0) tabManager.closeTab(index)
        }
        val restored = tabManager.restoreSession()
        if (!restored) {
            toast("没有可以恢复的标签")
            return
        }
        tabManager.currentTab()?.let { onCurrentTabChanged(it) }
        toast("已恢复上次的标签")
    }

    /* ---------------- 屏蔽页面元素 ---------------- */

    /**
     * 页面里的点选结果通过这个接口回来。
     *
     * 只暴露两个方法，参数都当字符串处理，不碰文件与剪贴板：
     * 任何页面都能调它，暴露面越小越好。
     */
    private class ElementPickerBridge(private val manager: TabManager) {
        @android.webkit.JavascriptInterface
        fun picked(selector: String, label: String) {
            manager.elementPickedHandler?.invoke(selector, label)
        }

        @android.webkit.JavascriptInterface
        fun cancelled() {
            manager.elementPickedHandler?.invoke("", "")
        }
    }

    private fun startElementPick() {
        val tab = tabManager.currentTab() ?: return
        if (!tab.usable || !tab.url.startsWith("http")) {
            toast("先打开一个网页")
            return
        }
        tab.webView.evaluateJavascript(SiteScripts.ELEMENT_PICK) { result ->
            if (!isAdded) return@evaluateJavascript
            toast(
                if (result?.contains("already") == true) "已经在屏蔽模式：点要隐藏的内容"
                else "点一下要隐藏的内容，按返回可取消",
            )
        }
    }

    private fun onElementPicked(selector: String, label: String) {
        val host = SiteRuleEngine.hostOf(tabManager.currentTab()?.url).orEmpty()
        if (host.isBlank()) return
        if (elementBlocker.add(host, selector)) {
            toast("已屏蔽「${label.ifBlank { "这个元素" }}」，以后打开 $host 也会隐藏")
            // 同一条选择器可能命中页面上多个同类元素，再注一次让它们一起消失
            tabManager.currentTab()?.takeIf { it.usable }?.let { tab ->
                elementBlocker.cssFor(host)?.let { css ->
                    tab.webView.evaluateJavascript(SiteScripts.hideCss(css), null)
                }
            }
        } else {
            toast("这个元素已经在屏蔽列表里了")
        }
    }

    /* ---------------- 站点信息（地址栏左侧的锁） ---------------- */

    private fun showSiteInfo() {
        val tab = tabManager.currentTab() ?: return
        val url = tab.url
        if (!url.startsWith("http")) {
            toast("先在地址栏打开一个网站")
            return
        }
        if (!tab.usable) return
        val fm = requireActivity().supportFragmentManager
        if (fm.findFragmentByTag(TAG_SITE_INFO) != null) return
        SiteInfoSheet().apply {
            pageUrl = url
            onApply = { reload ->
                sitePreferences.applyTo(tab.webView, tab.url)
                if (reload) runCatching { tab.webView.reload() }
            }
        }.show(fm, TAG_SITE_INFO)
    }

    /* ---------------- 保存页面图片 ---------------- */

    /**
     * 保存图片：先把页面里的图收集上来，再让用户挑。
     *
     * 不直接「一键全部保存」：一个长文页几十张，其中一半是头像、图标和表情，
     * 全存进相册等于制造垃圾。给个九宫格让人自己挑，比什么自动化都准。
     */
    private fun pickImages() {
        val tab = tabManager.currentTab() ?: return
        if (!tab.usable || !tab.url.startsWith("http")) {
            toast("这个页面没有可保存的图片")
            return
        }
        tab.webView.evaluateJavascript(SiteScripts.COLLECT_IMAGES) { raw ->
            if (!isAdded) return@evaluateJavascript
            val list = PageImages.parse(raw)
            if (list.isEmpty()) {
                toast("这个页面没有找到可保存的图片")
                return@evaluateJavascript
            }
            PageImages.pending = list
            if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_IMAGES) == null) {
                ImagePickerSheet().show(requireActivity().supportFragmentManager, TAG_IMAGES)
            }
        }
    }

    /* ---------------- 阅读模式 ---------------- */

    /**
     * 阅读模式：提取正文 → 用本地页面重渲染。
     *
     * 提取在页面上下文里做完（打分、清 junk、拼 HTML 都在 JS 里），
     * 原生只负责渲染那一页，这样即使站点结构千差万别，也不用来回传 DOM。
     */
    private fun toggleReaderMode() {
        val tab = tabManager.currentTab() ?: return
        if (tab.readerMode) {
            exitReaderMode()
            return
        }
        if (!tab.usable) {
            toast("页面已经关闭了")
            return
        }
        if (!tab.url.startsWith("http")) {
            toast("这个页面不支持阅读模式")
            return
        }
        toast("正在提取正文…")
        tab.webView.evaluateJavascript(SiteScripts.READER_EXTRACT) { raw ->
            if (!isAdded) return@evaluateJavascript
            val article = ReaderMode.parse(raw)
            if (article == null) {
                // 宁可明确说没找到，也不要渲染一页空白让人以为卡死
                toast("没在这个页面找到正文，换个页面试试")
                return@evaluateJavascript
            }
            readerArticle = article
            readerScale = 1f
            renderReader(tab, article)
        }
    }

    private fun renderReader(tab: Tab, article: ReaderMode.Article) {
        if (!tab.usable) return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        tab.readerMode = true
        tab.readerUrl = article.url
        tab.webView.loadDataWithBaseURL(
            article.url,
            ReaderMode.html(article, readerScale, dark),
            "text/html",
            "utf-8",
            null,
        )
        refreshUi()
    }

    private fun exitReaderMode() {
        val tab = tabManager.currentTab() ?: return
        val url = tab.readerUrl ?: tab.url
        tab.readerMode = false
        tab.readerUrl = null
        readerArticle = null
        if (tab.usable && url.startsWith("http")) tab.webView.loadUrl(url)
        refreshUi()
    }

    private fun changeReaderFont(delta: Float) {
        val tab = tabManager.currentTab() ?: return
        val article = readerArticle ?: return
        readerScale = (readerScale + delta).coerceIn(0.8f, 1.6f)
        renderReader(tab, article)
    }

    private fun setupFindBar() {
        findBar = view?.findViewById(R.id.find_bar) ?: return
        findInput = findBar.findViewById(R.id.find_input)
        findCount = findBar.findViewById(R.id.find_count)

        findBar.findViewById<ImageButton>(R.id.find_close).setOnClickListener { closeFindBar() }
        findBar.findViewById<ImageButton>(R.id.find_next).setOnClickListener { findNext(true) }
        findBar.findViewById<ImageButton>(R.id.find_prev).setOnClickListener { findNext(false) }

        findInput.addTextChangedListener { editable ->
            startFind(editable?.toString().orEmpty())
        }
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext(true)
                true
            } else {
                false
            }
        }
    }

    private fun showFindBar() {
        if (!::findBar.isInitialized) return
        findBar.isVisible = true
        findInput.requestFocus()
        findInput.selectAll()
        findInput.postDelayed({
            if (!isAdded) return@postDelayed
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
        if (findQuery.isNotBlank()) startFind(findQuery)
    }

    private fun startFind(query: String) {
        findQuery = query
        val webView = currentWebView() ?: return
        if (query.isBlank()) {
            webView.clearMatches()
            findCount.text = ""
            return
        }
        // findNext 的计数通过监听回传，会随输入实时变化
        webView.setFindListener { active, total, _ ->
            findCount.text = if (total == 0) getString(R.string.find_no_result) else "$active/$total"
        }
        webView.findAllAsync(query)
    }

    private fun findNext(forward: Boolean) {
        val webView = currentWebView() ?: return
        if (findQuery.isBlank()) return
        webView.findNext(forward)
    }

    private fun closeFindBar() {
        findBar.isVisible = false
        currentWebView()?.clearMatches()
        findInput.setText("")
        findInput.clearFocus()
        findCount.text = ""
        findQuery = ""
        hideKeyboard()
    }

    /** 地址栏里域名压成灰、路径加粗：一眼能看出当前在哪个站 */
    private fun styledUrl(url: String): CharSequence {
        if (url.isBlank()) return ""
        // 非网页地址（baiduboxapp://、intent://…）没有"域名 + 路径"可讲：
        // styledUrl 会把 host 之后那一整串参数加粗，最后被地址栏截成
        // 「!C%22matrix%22%3A%22main%22%7D」这种谁也看不懂的碎片
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
        if (scheme != null && scheme !in WEB_SCHEMES) {
            val label = runCatching { Uri.parse(url).host }.getOrNull()
            return if (label.isNullOrBlank()) {
                getString(R.string.url_app_link)
            } else {
                getString(R.string.url_app_link_host, label)
            }
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (host.isNullOrBlank()) return url
        val start = url.indexOf(host)
        if (start < 0) return url
        val sp = SpannableString(url)
        val onSurface = MaterialColors.getColor(
            addressInput,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val onVariant = MaterialColors.getColor(
            addressInput,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        sp.setSpan(
            ForegroundColorSpan(onVariant),
            start,
            start + host.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val rest = start + host.length
        if (rest < url.length) {
            sp.setSpan(
                ForegroundColorSpan(onSurface),
                rest,
                url.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sp.setSpan(StyleSpan(Typeface.BOLD), rest, url.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun setupBottomBar() {
        btnBack.setOnClickListener { currentWebView()?.goBack() }
        btnForward.setOnClickListener { currentWebView()?.goForward() }
        btnHome.setOnClickListener { currentWebView()?.loadUrl(settings.homeUrl) }
        btnTabs.setOnClickListener { openTabSwitcher() }
        btnMenu.setOnClickListener { showMenu() }
    }

    /* ---------------- 地址栏与导航 ---------------- */

    private fun querySuggestions(q: String) {
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        if (q.isBlank()) {
            suggestionAdapter.clear()
            return
        }
        val runnable = Runnable {
            val query = q.trim()
            lifecycleScope.launch {
                val items = store.suggestions(query, settings.searchEngine)
                suggestionAdapter.submit(items)
                if (addressInput.hasFocus() && items.isNotEmpty()) {
                    addressInput.showDropDown()
                }
            }
        }
        suggestionRunnable = runnable
        suggestionHandler.postDelayed(runnable, 200)
    }

    private fun navigate() {
        val input = addressInput.text?.toString() ?: return
        if (input.isBlank()) return
        navigateTo(UrlUtils.normalize(input, settings.searchEngine))
    }

    private fun navigateTo(url: String) {
        if (url.isBlank()) return
        hideKeyboard()
        addressInput.dismissDropDown()
        addressInput.clearFocus()
        suggestionAdapter.clear()

        // 当前标签的 WebView 可能已经销毁（界面上只剩它的最后一帧），
        // 那种 WebView 再 loadUrl 是没有任何反应的，必须换一个能用的
        val tab = tabManager.requireLoadableTab()
        if (tab !== tabManager.currentTab()) onCurrentTabChanged(tab)

        tab.failedUrl = null
        // 先把目标地址写进标签并刷新一次界面：
        // 加载再慢，地址栏里也得先看到自己输的地址、进度条得先动起来，
        // 而不是像以前那样「按了回车，键盘收起，页面和地址栏都跟没发生过一样」
        tab.url = url
        tab.committed = false
        tab.progress = START_PROGRESS
        refreshUi()

        // 容器里挂的必须就是当前标签的 WebView，否则加载的是看不见的那一个
        if (!tabManager.isAttached(webContainer)) tabManager.attachTo(webContainer)

        runCatching { tab.webView.stopLoading() }
        // 地址栏直接输网址不会经过 shouldOverrideUrlLoading，站点配置得在这里补一次
        siteRules.applyTo(tab.webView, url)
        sitePreferences.applyTo(tab.webView, url)
        val ok = runCatching { tab.webView.loadUrl(url) }.isSuccess
        if (!ok) {
            onErrorPageNeeded(tab, android.webkit.WebViewClient.ERROR_UNKNOWN)
            return
        }
        armNavigateWatchdog(tab, url)
    }

    /**
     * 导航看门狗。
     *
     * WebView 最讨厌的一种状态是「请求发出去了，然后什么都没发生」：
     * 不报错、不提交首帧、进度永远停在 10%，旧页面就一直晾在屏幕上。
     * 原来只有 onPageStarted 之后才会挂超时，可这种卡法恰恰连 onPageStarted 都不来，
     * 于是页面就能一直转下去。这里从「按下回车」那一刻开始计时，跟 onPageStarted 无关。
     */
    private fun armNavigateWatchdog(tab: Tab, target: String) {
        cancelNavigateWatchdog()
        val host = view ?: return
        navRetried = false
        val startedAt = android.os.SystemClock.elapsedRealtime()

        val runnable = object : Runnable {
            private var lastProgress = tab.progress

            override fun run() {
                if (tab.failedUrl != null) return
                if (!tab.usable) return
                if (tab !== tabManager.currentTab()) return
                // 首帧都画出来了就不叫「打不开」，剩下的交给 60 秒收尾定时器
                if (tab.committed) return
                // 进度已经在走：只是慢，不是死了。onPageStarted 一到，两级的
                // 首帧 / 收尾超时就已经接管了，这里直接收工，不再插手
                if (tab.progress != lastProgress || tab.progress > START_PROGRESS) {
                    return
                }

                val waited = android.os.SystemClock.elapsedRealtime() - startedAt
                if (!navRetried && waited < NAV_GIVE_UP_MS) {
                    // 连「开始加载」都没有：八成是 DNS 或连接卡住，重发一次
                    navRetried = true
                    runCatching { tab.webView.stopLoading() }
                    runCatching { tab.webView.loadUrl(target) }
                    host.postDelayed(this, NAV_WATCHDOG_MS)
                } else {
                    showStalledError(tab)
                }
            }
        }
        navWatchdog = runnable
        host.postDelayed(runnable, NAV_WATCHDOG_MS)
    }

    private fun cancelNavigateWatchdog() {
        navWatchdog?.let { view?.removeCallbacks(it) }
        navWatchdog = null
        navRetried = false
    }

    /** 重试过一次还是没动静：给错误页，至少能一键重来，而不是无限转圈 */
    private fun showStalledError(tab: Tab) {
        cancelNavigateWatchdog()
        if (!tab.usable) return
        runCatching { tab.webView.stopLoading() }
        onErrorPageNeeded(tab, android.webkit.WebViewClient.ERROR_TIMEOUT)
    }

    private fun onReloadClicked() {
        val tab = tabManager.currentTab() ?: return
        if (!tab.usable) {
            reload()
            return
        }
        if (tab.progress in 1..99) {
            tab.webView.stopLoading()
        } else {
            reload()
        }
    }

    private fun reload() {
        val tab = tabManager.currentTab() ?: return
        val target = tab.failedUrl ?: tab.url
        tab.failedUrl = null
        if (target.isNotBlank()) {
            tab.committed = false
            tab.progress = START_PROGRESS
            refreshUi()
            if (!tab.usable) {
                // 这个标签的 WebView 已经销毁，换一个能加载的再来
                navigateTo(target)
                return
            }
            runCatching { tab.webView.loadUrl(target) }
            armNavigateWatchdog(tab, target)
        }
    }

    /** 打开 URL（书签、外部链接、插件 API 调用） */
    fun openUrl(url: String, incognito: Boolean = false) {
        if (url.isBlank()) return
        // 只影响本次新建的标签，不再改写全局 incognitoMode（否则之后所有新标签都变无痕）
        val tab = tabManager.newTab(url = url, incognito = incognito)
        onCurrentTabChanged(tab)
        armNavigateWatchdog(tab, url)
    }

    fun currentTabUrl(): String? {
        val tab = tabManager.currentTab() ?: return null
        return tab.failedUrl ?: tab.url
    }

    /**
     * 手势动作入口（MainActivity 调用）。
     * 返回 false 表示这个动作当前做不了（比如没有可后退的历史），
     * 调用方会把事件继续交给页面，避免"划了没反应还吃掉手势"。
     */
    fun performGestureAction(action: String): Boolean {
        val webView = currentWebView()
        return when (action) {
            "back" -> if (webView != null && webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                false
            }
            "forward" -> if (webView != null && webView.canGoForward()) {
                webView.goForward()
                true
            } else {
                false
            }
            "reload" -> {
                reload()
                true
            }
            "home" -> {
                navigateTo(settings.homeUrl)
                true
            }
            "new_tab" -> {
                openUrl(settings.homeUrl)
                true
            }
            "close_tab" -> {
                val index = tabManager.currentIndex
                if (index in tabManager.tabs.indices) {
                    tabManager.closeTab(index)
                    true
                } else {
                    false
                }
            }
            "scroll_top" -> {
                webView?.scrollTo(0, 0)
                webView != null
            }
            "scroll_bottom" -> {
                webView?.scrollTo(0, webView.contentHeight)
                webView != null
            }
            else -> false
        }
    }

    private fun currentWebView(): WebView? {
        val tab = tabManager.currentTab() ?: return null
        return if (tab.isCreated) tab.webView else null
    }

    /* ---------------- 溢出菜单 ---------------- */

    /** 功能面板：底部升起的四列图标网格，替代原来的长条 PopupMenu */
    private fun showMenu() {
        pluginActionMap = mutableMapOf()
        val pluginActions = (requireActivity() as MainActivity).pluginMenuItems()
            .mapIndexed { index, (item, _) ->
                val id = "plugin:$index"
                pluginActionMap[id] = item
                BrowserMenuSheet.Action(id, R.drawable.ic_widget, item.title)
            }

        if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_MENU) != null) return
        runCatching {
            BrowserMenuSheet().apply {
                state = BrowserMenuSheet.State(
                    translated = tabManager.currentTab()?.translated == true,
                    desktopUa = settings.desktopUa,
                    readerMode = tabManager.currentTab()?.readerMode == true,
                    plugins = pluginActions,
                )
                onAction = { id -> handleMenuAction(id) }
            }.show(requireActivity().supportFragmentManager, TAG_MENU)
        }.onFailure {
            toast(getString(R.string.menu_open_failed))
        }
    }

    private fun handleMenuAction(action: String) {
        if (action.startsWith("plugin:")) {
            pluginActionMap[action]?.let {
                (requireActivity() as MainActivity).dispatchPluginMenuItem(it)
            }
            return
        }
        when (action) {
            "refresh" -> reload()
            "translate" -> toggleTranslate()
            "reader" -> toggleReaderMode()
            "find" -> showFindBar()
            "share" -> shareCurrentUrl()
            "add_bookmark" -> showAddBookmarkDialog()
            "desktop" -> toggleDesktopUa()
            "new_tab" -> tabManager.newTab(url = settings.homeUrl)
            "new_incognito" -> {
                tabManager.incognitoMode = true
                tabManager.newTab(url = settings.homeUrl)
            }
            "bookmarks" -> openScreen(
                BookmarksFragment.newInstance(BookmarksFragment.MODE_BOOKMARKS),
            )
            "history" -> openScreen(
                BookmarksFragment.newInstance(BookmarksFragment.MODE_HISTORY),
            )
            "screenshot" -> captureLongScreenshot()
            "images" -> pickImages()
            "block_element" -> startElementPick()
            "sniffer" -> {
                if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_SNIFFER) == null) {
                    ResourceSnifferFragment().show(
                        requireActivity().supportFragmentManager,
                        TAG_SNIFFER,
                    )
                }
            }
            "diag" -> {
                if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_DIAG) == null) {
                    DiagnosticsFragment().show(
                        requireActivity().supportFragmentManager,
                        TAG_DIAG,
                    )
                }
            }
            "downloads" -> openScreen(DownloadsFragment())
            "plugins" -> openScreen(PluginStoreFragment())
            "settings" -> openScreen(SettingsFragment())
        }
    }

    private fun openScreen(fragment: Fragment) {
        // commitAllowingStateLoss：菜单是从底部弹层里点的，弹层收起、Activity 刚好
        // 走到 onSaveInstanceState 时，普通 commit 会直接抛
        // 「Can not perform this action after onSaveInstanceState」把整个 App 带走。
        // 打开设置页这种事晚一点生效无所谓，不能因此崩掉。
        runCatching {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }.onFailure {
            toast("打开失败，请返回后重试")
        }
    }

    private fun openTabSwitcher() {
        if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_TABS) != null) return
        TabSwitcherFragment().show(requireActivity().supportFragmentManager, TAG_TABS)
    }

    private fun toggleDesktopUa() {
        settings.desktopUa = !settings.desktopUa
        toast(if (settings.desktopUa) "已切换为桌面版 UA" else "已切换为移动版 UA")
        reload()
    }

    private fun shareCurrentUrl() {
        val url = currentTabUrl()
        if (url.isNullOrBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    /* ---------------- 书签 ---------------- */

    private fun showAddBookmarkDialog() {
        val tab = tabManager.currentTab() ?: return
        val url = tab.failedUrl ?: tab.url
        if (url.isBlank() || url.startsWith("data:")) return

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_bookmark, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.input_name)
        val urlInput = dialogView.findViewById<TextInputEditText>(R.id.input_url)
        nameInput.setText(tab.title.ifBlank { UrlUtils.hostOf(url) })
        urlInput.setText(url)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmark_add)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text?.toString()?.trim() ?: ""
                lifecycleScope.launch {
                    if (store.isBookmarked(url)) {
                        toast(getString(R.string.bookmark_exists))
                    } else {
                        store.addBookmark(name.ifBlank { url }, url)
                        toast(getString(R.string.bookmark_added))
                    }
                }
            }
            .show()
    }

    /* ---------------- 下载 ---------------- */

    override fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ): Boolean {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("暂不支持保存该类型的下载")
            return true
        }
        val engine = DownloadEngine.get(requireContext())
        val fileName = UrlUtils.guessFileName(url, contentDisposition, mimeType)
        val referer = tabManager.currentTab()?.url
        lifecycleScope.launch {
            if (engine.isDownloading(url)) {
                toast(getString(R.string.download_duplicate))
                return@launch
            }
            downloadPrompt.show(
                url = url,
                fileName = fileName,
                userAgent = userAgent,
                knownSize = contentLength,
                mime = mimeType,
                referer = referer,
            ) { name, size ->
                beginDownload(
                    PendingDownload(url, userAgent, contentDisposition, mimeType, name, size),
                )
            }
        }
        return true
    }

    /** 用户确认后（或站点已信任时）才真正入队 */
    private fun beginDownload(pending: PendingDownload) {
        val needPermission = android.os.Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingDownload = pending
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownload(pending)
    }

    private fun startDownload(pending: PendingDownload) {
        val engine = DownloadEngine.get(requireContext())
        val name = pending.fileName.ifBlank {
            UrlUtils.guessFileName(pending.url, pending.contentDisposition, pending.mimeType)
        }
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            runCatching {
                engine.enqueue(
                    name = name,
                    url = pending.url,
                    mime = pending.mimeType ?: "",
                    size = pending.size,
                )
                // 前台服务负责后台续跑；没有它，切走 App 下载就会被系统掐断
                DownloadService.start(requireContext())
            }.onFailure {
                toast(getString(R.string.sniffer_download_failed))
                return@launch
            }
            notifyDownloadStarted(name)
        }
    }

    private fun notifyDownloadStarted(name: String) {
        // 顺手给个「查看」入口，不然用户不知道进度去哪儿看
        val root = view
        if (root == null) {
            toast(getString(R.string.download_started, name))
            return
        }
        com.google.android.material.snackbar.Snackbar
            .make(
                root,
                getString(R.string.download_started, name),
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
            )
            .setAnchorView(R.id.bottom_bar)
            .setAction(R.string.downloads_title) { openScreen(DownloadsFragment()) }
            .show()
    }

    /** API 33+ 没有通知权限的话，前台服务的进度条看不见，但下载照常 */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /* ---------------- 网页权限（相机 / 麦克风 / 定位） ---------------- */

    /**
     * 一次网页权限申请。
     *
     * PermissionRequest 和 GeolocationPermissions.Callback 是两套回调，
     * 但用户看到的应当是同一个「允不允许这个网站用某个能力」，所以统一收在这里，
     * 走同一条「查记忆 → 问人 → 申请系统权限 → 回填」的流程。
     */
    private data class PendingWebPermission(
        val request: PermissionRequest?,
        val geo: android.webkit.GeolocationPermissions.Callback?,
        val origin: String,
        val host: String,
        val kinds: List<PermKind>,
    )

    private var pendingWebPermission: PendingWebPermission? = null
    private val sitePermissions by lazy { SitePermissions(requireContext()) }
    /** 本次进程内通过的 HTTP 认证；密码不落盘 */
    private val authCache = HashMap<String, Pair<String, String>>()
    /** 用户明确选择「继续访问」的证书异常站点，仅本次进程有效 */
    private val sslAllowed = HashSet<String>()

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // 不看返回值：部分机型回调给 false，但 checkSelfPermission 已经是 GRANTED，
        // 统一用 checkSelfPermission 复核，才不会出现「点了允许网页还是拒绝」
        val pending = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        settleWebPermission(pending)
    }

    override fun onWebPermissionRequest(request: PermissionRequest) {
        val wanted = request.resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> PermKind.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> PermKind.MIC
                else -> null
            }
        }.distinct()
        if (wanted.isEmpty()) {
            runCatching { request.deny() }
            return
        }
        val host = SiteRuleEngine.hostOf(tabManager.currentWebViewOrNull()?.url).orEmpty()
        askWebPermission(PendingWebPermission(request, null, "", host, wanted))
    }

    override fun onWebGeolocation(
        origin: String,
        callback: android.webkit.GeolocationPermissions.Callback,
    ) {
        val host = SiteRuleEngine.hostOf(origin).orEmpty()
        askWebPermission(
            PendingWebPermission(null, callback, origin, host, listOf(PermKind.LOCATION)),
        )
    }

    private fun askWebPermission(p: PendingWebPermission) {
        val allowed = p.kinds.filter {
            sitePermissions.decision(p.host, it) == SitePermissions.Decision.ALLOW
        }
        val denied = p.kinds.filter {
            sitePermissions.decision(p.host, it) == SitePermissions.Decision.DENY
        }
        val ask = p.kinds.filter { it !in allowed && it !in denied }
        if (ask.isEmpty()) {
            settleWebPermission(p.copy(kinds = allowed))
            return
        }
        val ctx = context
        if (ctx == null || !isAdded) {
            settleWebPermission(p.copy(kinds = allowed))
            return
        }

        val remember = CheckBox(ctx).apply {
            text = "记住这个网站的选择"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
        }
        val who = p.host.ifBlank { "此网站" }
        val names = ask.joinToString("、") { it.title }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("允许「$who」使用$names？")
            .setMessage(ask.joinToString("\n") { "· ${it.title}：${it.detail}" })
            .setView(remember)
            .setNegativeButton("拒绝") { _, _ ->
                if (remember.isChecked) {
                    ask.forEach { sitePermissions.set(p.host, it, SitePermissions.Decision.DENY) }
                }
                settleWebPermission(p.copy(kinds = allowed))
            }
            .setPositiveButton("允许") { _, _ ->
                if (remember.isChecked) {
                    ask.forEach { sitePermissions.set(p.host, it, SitePermissions.Decision.ALLOW) }
                }
                requestAndroidPermissions(p.copy(kinds = allowed + ask))
            }
            .setOnCancelListener { settleWebPermission(p.copy(kinds = allowed)) }
            .show()
    }

    private fun requestAndroidPermissions(p: PendingWebPermission) {
        val needed = p.kinds.flatMap { androidPermsOf(it) }
            .distinct()
            .filter {
                ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
            }
        if (needed.isEmpty()) {
            settleWebPermission(p)
            return
        }
        pendingWebPermission = p
        webPermissionLauncher.launch(needed.toTypedArray())
    }

    /** 把最终结果写回网页：该给资源的给资源，该拒的明确拒 */
    private fun settleWebPermission(p: PendingWebPermission) {
        // 回调可能在页面切走之后才回来，此时 requireContext() 会抛
        if (!isAdded) return
        val allowed = p.kinds.filter { kind ->
            val perms = androidPermsOf(kind)
            perms.isEmpty() || perms.any {
                ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        val request = p.request
        if (request != null) {
            val resources = request.resources.filter { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> PermKind.CAMERA in allowed
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> PermKind.MIC in allowed
                    else -> false
                }
            }
            runCatching {
                if (resources.isEmpty()) request.deny() else request.grant(resources.toTypedArray())
            }
            if (resources.isEmpty()) {
                toast("权限被拒绝，网页里的相关功能会不可用")
            }
            return
        }
        p.geo?.invoke(p.origin, PermKind.LOCATION in allowed, false)
        if (PermKind.LOCATION !in allowed) toast("未获得定位权限，网页无法获取你的位置")
    }

    private fun androidPermsOf(kind: PermKind): List<String> = when (kind) {
        PermKind.CAMERA -> listOf(android.Manifest.permission.CAMERA)
        PermKind.MIC -> listOf(android.Manifest.permission.RECORD_AUDIO)
        PermKind.LOCATION -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        PermKind.NOTIFICATION -> emptyList()
    }

    /* ---------------- HTTP 认证 ---------------- */

    override fun onWebHttpAuth(
        view: WebView,
        host: String,
        realm: String,
        handler: HttpAuthHandler,
    ) {
        // 同一个页面常常连续要两次（先空凭据探一下），缓存住避免弹两遍
        authCache["$host|$realm"]?.let {
            handler.proceed(it.first, it.second)
            return
        }
        val ctx = context
        if (ctx == null || !isAdded) {
            handler.cancel()
            return
        }
        val name = EditText(ctx).apply {
            hint = "用户名"
            setSingleLine()
        }
        val pass = EditText(ctx).apply {
            hint = "密码"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(pass)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("需要登录")
            .setMessage("$host\n${realm.ifBlank { "此页面" }} 要求输入用户名和密码。")
            .setView(box)
            .setNegativeButton("取消") { _, _ -> handler.cancel() }
            .setPositiveButton("登录") { _, _ ->
                val u = name.text?.toString().orEmpty()
                val p = pass.text?.toString().orEmpty()
                authCache["$host|$realm"] = u to p
                handler.proceed(u, p)
            }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    /* ---------------- 证书异常 ---------------- */

    override fun onWebSslError(host: String, error: SslError, handler: SslErrorHandler) {
        val realHost = SiteRuleEngine.hostOf(runCatching { error.url }.getOrNull())
            ?: SiteRuleEngine.hostOf("https://$host")
            ?: host
        if (realHost in sslAllowed) {
            handler.proceed()
            return
        }
        val ctx = context
        if (ctx == null || !isAdded) {
            handler.cancel()
            return
        }
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> "证书已过期"
            SslError.SSL_IDMISMATCH -> "证书上的域名和当前网址不一致"
            SslError.SSL_UNTRUSTED -> "证书签发者不被系统信任（自签名证书常见）"
            SslError.SSL_DATE_INVALID -> "证书的生效时间不合法（多半是手机时间不对）"
            SslError.SSL_NOTYETVALID -> "证书还没到生效时间"
            SslError.SSL_INVALID -> "证书内容无效"
            else -> "证书校验失败"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("连接不安全")
            .setMessage(
                "$realHost 的$reason。\n\n" +
                    "继续访问时，你在这个网站上输入的内容可能被第三方看到或篡改，" +
                    "不要输入密码、验证码或支付信息。",
            )
            .setPositiveButton("继续访问（不安全）") { _, _ ->
                sslAllowed.add(realHost)
                handler.proceed()
            }
            .setNegativeButton("返回") { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    /* ---------------- 文件选择（Web 表单上传） ---------------- */

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams,
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        val acceptTypes = params.acceptTypes?.filter { it.isNotBlank() }
        val isImage = acceptTypes?.any { it.startsWith("image") } == true
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (isImage) "image/*" else "*/*"
            if (!acceptTypes.isNullOrEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            }
        }
        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            fileChooserCallback = null
            false
        }
    }

    /* ---------------- 选中文字后的动作 ---------------- */

    /**
     * 系统选择菜单只给了「复制 / 全选 / 网页搜索」，其中网页搜索还会跳去系统浏览器。
     * 这里补的三项都留在本应用内：搜索开新标签（不打断正在读的文章）、
     * 翻译开新标签、分享走系统分享面板。
     */
    private fun handleSelectionAction(action: KiteWebView.SelectionAction, text: String) {
        if (!isAdded || text.isBlank()) return
        when (action) {
            KiteWebView.SelectionAction.SEARCH ->
                openUrl(UrlUtils.searchUrl(settings.searchEngine, text))
            KiteWebView.SelectionAction.TRANSLATE ->
                openUrl("https://fanyi.baidu.com/#auto/zh/" + Uri.encode(text))
            KiteWebView.SelectionAction.SHARE -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching {
                    startActivity(Intent.createChooser(send, "分享所选文字"))
                }
            }
        }
    }

    /* ---------------- 全屏视频 ---------------- */

    override fun onEnterFullscreen(view: View) {
        if (customView != null) return
        customView = view
        (requireActivity().window.decorView as? FrameLayout)?.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addressBarRow.isVisible = false
        bottomBar.isVisible = false
        // 视频站点：进全屏直接锁横屏。这是「适配」和「原样跑」差别最明显的一处——
        // 不锁的话用户得先手动转手机，转完还要等一次重排
        if (siteRules.has(tabManager.currentTab()?.url, SiteAction.FULLSCREEN_LANDSCAPE)) {
            runCatching {
                requireActivity().requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                landscapeLocked = true
            }
        }
        hideSystemBars()
    }

    override fun onExitFullscreenRequest() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        addressBarRow.isVisible = true
        bottomBar.isVisible = true
        if (landscapeLocked) {
            landscapeLocked = false
            // UNSPECIFIED 才会回到「跟随系统旋转」，USER 会把用户自己的锁定也一起废掉
            runCatching {
                requireActivity().requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        showSystemBars()
    }

    private fun hideSystemBars() {
        val window = runCatching { requireActivity().window }.getOrNull() ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            }
        }
    }

    private fun showSystemBars() {
        val window = runCatching { requireActivity().window }.getOrNull() ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { window.decorView.systemUiVisibility = 0 }
        }
    }

    private fun exitFullscreen() {
        onExitFullscreenRequest()
    }

    /* ---------------- 弹出窗口（window.open） ---------------- */

    override fun openPopupWindow(): WebView? {
        val tab = tabManager.newTab(url = "about:blank")
        onCurrentTabChanged(tab)
        return tab.webView
    }

    /* ---------------- WebView 配置 ---------------- */

    private fun configureWebView(tab: Tab, wv: WebView) {
        wv.webViewClient = webViewClient
        wv.webChromeClient = webChromeClient
        val bridge = JsBridge(
            context = requireContext(),
            scope = PluginBrowserApp.appScope,
            host = activity as? MainActivity,
            onNotifyPermissionNeeded = { requestNotifyPermission() },
            reply = { js ->
                wv.post { runCatching { wv.evaluateJavascript(js, null) } }
            },
            currentUrl = { runCatching { wv.url }.getOrNull() },
            isPluginActive = pluginManager::isPluginActiveFor,
            grantsOf = pluginManager::grantsOf,
        )
        wv.addJavascriptInterface(bridge, JsBridge.JS_NAME)
        wv.addJavascriptInterface(ElementPickerBridge(tabManager), "__pbPicker")
        val manager = tabManager
        (wv as? KiteWebView)?.onSelectionAction = { action, text ->
            manager.selectionActionHandler?.invoke(action, text)
        }
        registerForContextMenu(wv)
    }

    private fun requestNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifyPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /* ---------------- 页面事件（Owner 回调） ---------------- */

    /**
     * 链接刚被点开、还没交给 WebView 加载。
     *
     * 首页上的 GitHub 图标就是这条路径：`<a href>` 由 WebView 自己导航，
     * 不经过地址栏的 navigateTo，所以以前既没有进度反馈、也没有看门狗，
     * 一旦系统 DNS 卡住，界面会停在首页一动不动好几秒——用户只能以为「没点上」。
     */
    override fun onNavigationStarted(webView: android.webkit.WebView, url: String) {
        // 一定要按 WebView 找回它自己的标签：后台标签也会跳转（重定向、自动刷新），
        // 以前这里是拿「当前标签」套用，结果就是地址栏显示 github.com、页面却是首页
        val tab = tabManager.findTab(webView) ?: return
        if (!tab.usable) return
        if (tab.url == url && tab.progress in 1..99) return
        tab.failedUrl = null
        tab.url = url
        tab.committed = false
        tab.progress = START_PROGRESS
        // 后台标签只更新数据，不碰界面
        if (tab === tabManager.currentTab()) {
            refreshUi()
            armNavigateWatchdog(tab, url)
        }
    }

    override fun onPageStarted(tab: Tab) {
        // 导航真的开始了，看门狗可以下班，改由两级超时接管
        cancelNavigateWatchdog()
        refreshUi()
        scheduleLoadTimeouts(tab)
    }

    override fun onPageFinished(tab: Tab) {
        cancelNavigateWatchdog()
        clearLoadTimeouts()
        // 页面主体已经结束，剩下的零散资源不该再让进度条吊着
        if (tab.progress < 100) tab.progress = 100
        if (tab === tabManager.currentTab()) refreshUi()
        // 跳转后旧的高亮会失效，若查找栏还开着就重新搜一次
        if (::findBar.isInitialized && findBar.isVisible && findQuery.isNotBlank()) {
            startFind(findQuery)
        }
    }

    override fun onProgressChanged(tab: Tab, progress: Int) = refreshUi()

    override fun onReceivedTitle(tab: Tab, title: String) {
        // 标题变化时刷新标签切换器缓存即可，主界面地址栏显示 URL
        refreshUi()
    }

    override fun onReceivedIcon(tab: Tab, icon: android.graphics.Bitmap) {
        // 图标暂仅保存在 Tab 中
    }

    override fun onErrorPageNeeded(tab: Tab, errorCode: Int) {
        // 标签已经关掉/WebView 已销毁：连「显示错误页」这件事都做不了，别硬来
        if (!tab.usable) return
        val current = tab.url
        // tab.url 有可能已经被错误页自己的 about:blank 覆盖过，优先用记下来的失败地址
        val failed = if (current.isBlank() || current == "about:blank") {
            tab.failedUrl ?: "about:blank"
        } else {
            current
        }
        tab.failedUrl = failed
        runCatching { tab.webView.stopLoading() }
        val html = ErrorPages.html(errorCode, failed, errorHintFor(failed))
        runCatching { tab.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
        if (tab === tabManager.currentTab()) refreshUi()
    }

    /**
     * GitHub 打不开时最常见的原因是「网页也走独立通道」开着，但代理没在跑。
     * 那个开关藏在设置里，用户十有八九记不住，直接在错误页上点名。
     */
    private fun errorHintFor(url: String): String? {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
        if (!settings.githubAccel) return null
        val github = host == "github.com" || host.endsWith(".github.com")
        if (!github || !settings.githubCatchPages) return null
        return "「网页也走独立通道」正开着，如果代理没启动，GitHub 就会一直连不上；到 设置 → 网络 关掉它再试"
    }

    override fun onRenderProcessGone(tab: Tab) {
        // 渲染进程没了，这个 WebView 已经是一具尸体，只能丢掉重建。
        // 先把地址记下来，重建后原样打开，别让用户自己再输一遍
        val url = tab.url
        val index = tabManager.tabs.indexOf(tab)
        if (index < 0) return
        tabManager.closeTab(index)
        val current = tabManager.currentTab()
        if (url.isNotBlank() && current != null && tabManager.isHomeUrl(current.url)) {
            current.failedUrl = null
            current.url = url
            current.committed = false
            current.progress = START_PROGRESS
            refreshUi()
            current.webView.loadUrl(url)
            armNavigateWatchdog(current, url)
        } else {
            toast(getString(R.string.renderer_crashed))
        }
    }

    override fun onInternalScheme(url: String): Boolean {
        if (url.startsWith(ReaderMode.SCHEME)) {
            when {
                url.startsWith("${ReaderMode.SCHEME}/font") -> {
                    val d = runCatching {
                        Uri.parse(url).getQueryParameter("d")?.toFloat()
                    }.getOrNull() ?: 0f
                    changeReaderFont(if (d >= 0f) 0.08f else -0.08f)
                }
                // 退出 / 未知指令都回到原文，别把用户困在一个死页面上
                else -> exitReaderMode()
            }
            return true
        }
        if (url.startsWith(ErrorPages.RETRY_SCHEME)) {
            reload()
            return true
        }
        if (url.startsWith("pb://focus-address")) {
            addressInput.requestFocus()
            addressInput.setSelection(0, addressInput.text?.length ?: 0)
            addressInput.postDelayed({
                if (!isAdded) return@postDelayed
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(addressInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
        return true
    }

    override fun openExternally(url: String): Boolean {
        // 很多"用 App 打开"的链接会把真实地址塞在参数里，先解出来
        extractHttpFromDeepLink(url)?.let { direct ->
            offerDirectDownload(direct)
            return true
        }
        // 唤起 App 的链接不再默默交出去：先问一句，并可按站点记住。
        // 以前是直接 startActivity，用户被拽走再回来，浏览位置和上下文全没了
        if (url.startsWith("intent:", ignoreCase = true) || SiteRuleEngine.isAppWakeScheme(url)) {
            return confirmAppOpen(url)
        }
        // tel: / mailto: / 厂商深度链接这类没有「网页版」可选，保持直接交给系统
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            // 系统里没有 App 能接这个 scheme（没装百度、没装知乎，或者是个野 scheme）。
            //
            // 这里以前返回 false，等于把控制权交回 WebView——WebView 会真的去加载
            // baiduboxapp:// 这种根本不存在的东西，必然失败，然后把当前页面整个换成
            // 一屏「无法访问此网页」。用户只是点了个「用 App 打开」，原来的页面就没了，
            // 还得自己翻历史找回来。现在吞掉这次跳转，页面留在原地。
            //
            // 顺手看一眼这个页面有没有藏着安装包直链：很多推广页把 apk 地址写在内联脚本里，
            // 有就弹出来让用户选，没有就提示一句「没有应用能打开」。
            offerPageDownloads()
            true
        }
    }

    /**
     * 打开 App 前的确认。
     *
     * 记住的选择按站点存：同一个站点的第二次跳转不再问，但不影响别的站点。
     * 「留在网页」这一项是整件事的重点——很多站点的网页版完全能用，
     * 用户只是被弹窗推着走。
     */
    private fun confirmAppOpen(url: String): Boolean {
        val host = SiteRuleEngine.hostOf(tabManager.currentTab()?.url).orEmpty()
        when (handoff.choice(host)) {
            AppHandoff.Choice.WEB -> {
                toast("已留在网页，可在 设置 → 站点适配 里改回来")
                return true
            }
            AppHandoff.Choice.APP -> return launchExternal(url, host)
            AppHandoff.Choice.ASK -> Unit
        }

        val ctx = context ?: return launchExternal(url, host)
        val remember = CheckBox(ctx).apply {
            text = "记住这个网站的选择"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("要在 App 里打开吗")
            .setMessage(
                if (host.isBlank()) "这个链接会唤起别的应用。"
                else "$host 想唤起别的应用。留在网页可以继续用浏览器看。",
            )
            .setView(remember)
            .setNegativeButton("留在网页") { _, _ ->
                if (remember.isChecked && host.isNotBlank()) {
                    handoff.setChoice(host, AppHandoff.Choice.WEB)
                }
            }
            .setPositiveButton("打开 App") { _, _ ->
                if (remember.isChecked && host.isNotBlank()) {
                    handoff.setChoice(host, AppHandoff.Choice.APP)
                }
                launchExternal(url, host)
            }
            .show()
        return true
    }

    /** 真正跳出去；记住这一跳，回来时好提示 */
    private fun launchExternal(url: String, host: String): Boolean {
        val ok = if (url.startsWith("intent:", ignoreCase = true)) {
            openIntentUrl(url)
        } else {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            }.getOrDefault(false)
        }
        if (!ok) {
            toast("没有找到能打开这个链接的应用，已留在网页")
            return true
        }
        handoffTarget = url
        handoff.remember(url, host)
        return true
    }

    /** 回到前台时提示「刚从 App 回来」，12 秒后自动收起 */
    private fun showHandoffBar() {
        if (!::handoffBar.isInitialized) return
        val last = handoff.takeFresh()
        if (last == null) {
            handoffBar.isVisible = false
            return
        }
        handoffTarget = last.url
        handoffText.text = "刚从「${last.host.ifBlank { "外部 App" }}」回来"
        handoffBar.isVisible = true
        handoffBar.removeCallbacks(hideHandoffBar)
        handoffBar.postDelayed(hideHandoffBar, 12_000L)
    }

    /** 深度链接的 query 里带 http(s) 地址时取出来（常见于导流下载页） */
    private fun extractHttpFromDeepLink(raw: String): String? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val names = uri.queryParameterNames.orEmpty()
        for (name in names) {
            val value = runCatching { uri.getQueryParameter(name) }.getOrNull() ?: continue
            val decoded = Uri.decode(value)
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return null
    }

    private fun offerDirectDownload(link: String) {
        val name = link.substringBefore('?').substringAfterLast('/').ifBlank { link }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.deep_link_found)
            .setMessage(name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sniffer_download) { _, _ ->
                val ua = currentWebView()?.settings?.userAgentString.orEmpty()
                onDownloadRequested(link, ua, null, null, -1L)
            }
            .show()
    }

    /**
     * 深度链接调起失败时，在页面里找可下载的文件。
     * 推广页常见做法：按钮只挂 deep link，但 HTML 里仍留着 apk 直链。
     */
    private fun offerPageDownloads() {
        val webView = currentWebView() ?: return
        val js = """
            (function () {
              var out = [], seen = {};
              // innerHTML 里 & 会变成 &amp;、引号变成 &quot;，直接抓到会带脏尾巴
              function clean(u) {
                var cut = u.search(/&quot;|&#39;|&lt;|&gt;|["'<>]/);
                if (cut >= 0) u = u.slice(0, cut);
                u = u.replace(/&amp;/g, '&');
                return u.replace(/[),.;]+$/, '');
              }
              function push(raw) {
                var u = clean(raw || '');
                if (!u || seen[u] || out.length >= 8) return;
                if (!/^https?:/i.test(u)) return;
                seen[u] = 1; out.push(u);
              }
              var as = document.querySelectorAll('a[href]');
              for (var i = 0; i < as.length; i++) {
                var h = as[i].href || '';
                if (/\.(apk|zip|rar|7z|pdf|docx?|xlsx?|pptx?|epub)([?#]|${'$'})/i.test(h)) push(h);
              }
              var html = document.documentElement.innerHTML || '';
              var re = /https?:\/\/[^\s"'<>\\]+\.(?:apk|zip|rar|7z|pdf)(?:\?[^\s"'<>\\]*)?/gi;
              var m;
              while ((m = re.exec(html)) !== null) push(m[0]);
              return JSON.stringify(out);
            })()
        """
        webView.evaluateJavascript(js) { raw ->
            if (!isAdded) return@evaluateJavascript
            val all = runCatching {
                val inner = (org.json.JSONTokener(raw).nextValue() as? String).orEmpty()
                val arr = org.json.JSONArray(inner)
                (0 until arr.length()).map { arr.getString(it) }.distinct()
            }.getOrDefault(emptyList())

            // 有安装包就只列安装包，别让 PDF、压缩包混进来干扰
            val apks = all.filter { it.substringBefore('?').endsWith(".apk", ignoreCase = true) }
            val candidates = if (apks.isNotEmpty()) apks else all

            if (candidates.isEmpty()) {
                toast(getString(R.string.no_app_for_link))
                return@evaluateJavascript
            }

            val labels = candidates.map { link ->
                val name = link.substringBefore('?').substringAfterLast('/')
                if (name.isBlank()) link else name
            }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.page_download_title)
                .setItems(labels) { _, which ->
                    val link = candidates[which]
                    onDownloadRequested(link, webView.settings.userAgentString, null, null, -1L)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openIntentUrl(url: String): Boolean {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (intent != null) {
            try {
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                startActivity(intent)
                return true
            } catch (_: Exception) {
                // 目标 App 没装：优先用它带的 http 回退地址
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    navigateTo(fallback)
                    return true
                }
                val pkg = intent.`package`
                if (!pkg.isNullOrBlank()) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                        return true
                    } catch (_: Exception) {
                        // 没有应用商店，往下走提示
                    }
                }
            }
        }
        return false
    }

    override fun runIo(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    /* ---------------- Owner 接口实现 ---------------- */

    override val browserSettings: SettingsRepository get() = settings
    override val browserStore: BrowserStore get() = store
    override val browserPluginManager: PluginManager get() = pluginManager
    override val browserTabManager: TabManager get() = tabManager
    override val githubChannel
        get() = com.pluginbrowser.PluginBrowserApp.from(requireContext()).gitHubChannel

    override val siteRules: SiteRuleEngine by lazy { SiteRuleEngine(requireContext()) }
    override val sitePreferences: SitePreferences by lazy { SitePreferences(requireContext()) }
    override val elementBlocker: ElementBlocker by lazy { ElementBlocker(requireContext()) }

    /** 拦掉一次 App 跳转：必须让用户知道是「拦了」而不是「点了没反应」 */
    override fun onAppJumpBlocked(host: String) {
        if (!isAdded) return
        val engine = siteRules
        val ruleName = engine.rulesFor("https://$host/").firstOrNull()?.name
        toast(
            if (ruleName != null) "已拦截「$ruleName」的 App 跳转，留在网页里"
            else "已拦截跳转 App",
        )
    }

    /* ---------------- 当前标签切换 ---------------- */

    fun onCurrentTabChanged(tab: Tab?) {
        if (!isAdded || view == null) return
        tabManager.attachTo(webContainer)
        refreshUi()
    }

    /* ---------------- 界面刷新 ---------------- */

    fun refreshUi() {
        if (!isAdded || view == null) return
        val tab = tabManager.currentTab()
        val wv = if (tab?.isCreated == true) tab.webView else null

        btnBack.isEnabled = wv?.canGoBack() == true
        btnForward.isEnabled = wv?.canGoForward() == true
        val active = MaterialColors.getColor(btnBack, com.google.android.material.R.attr.colorOnSurface)
        val disabled = ContextCompat.getColor(requireContext(), R.color.pb_icon_disabled)
        btnBack.imageTintList = ColorStateList.valueOf(if (btnBack.isEnabled) active else disabled)
        btnForward.imageTintList = ColorStateList.valueOf(if (btnForward.isEnabled) active else disabled)
        btnBack.alpha = 1f
        btnForward.alpha = 1f

        tabCountBadge.text = tabManager.tabs.size.toString()

        val displayUrl = tab?.let { it.failedUrl ?: it.url } ?: ""
        // 首页不暴露 file:///android_asset/... 这种内部地址，直接留空显示提示语
        val isHome = tabManager.isHomeUrl(displayUrl)
        if (!addressInput.hasFocus()) {
            addressInput.setText(if (isHome) "" else styledUrl(displayUrl))
            addressInput.setSelection(addressInput.text?.length ?: 0)
        }

        securityIcon.isVisible = !isHome
        val secure = UrlUtils.isSecure(displayUrl)
        securityIcon.setImageResource(
            if (secure) R.drawable.ic_lock else R.drawable.ic_lock_open,
        )

        incognitoBadge.isVisible = tab?.incognito == true

        val progress = (tab?.progress ?: 0).coerceIn(0, 100)
        progressBar.isVisible = progress in 1..99
        progressBar.animate()
            .scaleX(progress / 100f)
            .setDuration(PROGRESS_ANIM_MS)
            .start()

        // 加载中显示停止图标，编辑地址时显示「前往」
        updateAddressAction()
    }

    /* ---------------- 长按上下文菜单 ---------------- */

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?,
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val wv = v as? WebView ?: return
        val result = wv.hitTestResult
        when (result.type) {
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                pendingImageUrl = result.extra
                menu.add(0, MENU_SAVE_IMAGE, 0, getString(R.string.save_image))
                menu.add(0, MENU_OPEN_IMAGE_NEW_TAB, 1, getString(R.string.open_new_tab))
                menu.add(0, MENU_COPY_IMAGE_URL, 2, "复制图片地址")
            }
            WebView.HitTestResult.ANCHOR_TYPE -> {
                pendingLinkUrl = result.extra
                menu.add(0, MENU_OPEN_LINK_NEW_TAB, 0, getString(R.string.open_new_tab))
                menu.add(0, MENU_OPEN_LINK_BACKGROUND, 1, "后台打开")
                menu.add(0, MENU_COPY_LINK, 2, getString(R.string.copy_link))
                menu.add(0, MENU_SHARE_LINK, 3, "分享链接")
            }
            else -> {
                menu.add(0, MENU_COPY_TEXT, 0, getString(R.string.copy_text))
            }
        }
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE_IMAGE -> {
                pendingImageUrl?.let { url ->
                    val ua = currentWebView()?.settings?.userAgentString ?: ""
                    onDownloadRequested(url, ua, null, "image/*", 0)
                    toast(getString(R.string.image_saved))
                }
                return true
            }
            MENU_OPEN_IMAGE_NEW_TAB -> {
                pendingImageUrl?.let { openUrl(it) }
                return true
            }
            MENU_OPEN_LINK_NEW_TAB -> {
                pendingLinkUrl?.let { openUrl(it) }
                return true
            }
            MENU_COPY_LINK -> {
                pendingLinkUrl?.let { copyToClipboard(it); toast(getString(R.string.link_copied)) }
                return true
            }
            MENU_OPEN_LINK_BACKGROUND -> {
                pendingLinkUrl?.let { url ->
                    tabManager.newTab(url = url, select = false)
                    toast("已在新标签打开")
                }
                return true
            }
            MENU_SHARE_LINK -> {
                pendingLinkUrl?.let { url ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    runCatching { startActivity(Intent.createChooser(send, "分享链接")) }
                }
                return true
            }
            MENU_COPY_IMAGE_URL -> {
                pendingImageUrl?.let { copyToClipboard(it); toast("图片地址已复制") }
                return true
            }
            MENU_COPY_TEXT -> {
                currentWebView()?.evaluateJavascript(
                    "(function(){return (window.getSelection()||'').toString();})()",
                ) { value ->
                    val text = value?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\n", "\n") ?: ""
                    if (text.isNotBlank()) {
                        copyToClipboard(text)
                        toast(getString(R.string.text_copied))
                    }
                }
                return true
            }
        }
        return super.onContextItemSelected(item)
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PB", text))
    }

    /* ---------------- 工具 ---------------- */

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressInput.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun toast(resId: Int) = Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()

    companion object {
        /** 能当「网址」看的 scheme；其余一律按「应用链接」显示，不暴露参数串 */
        private val WEB_SCHEMES = setOf("http", "https", "file", "about", "data", "pb")
        private const val TAG_TABS = "tab_switcher"
        private const val TAG_SNIFFER = "resource_sniffer"
        private const val TAG_DIAG = "net_diagnostics"
        private const val TAG_IMAGES = "page_images"
        private const val TAG_SITE_INFO = "site_info"
        private const val TAG_MENU = "browser_menu"
        private const val PROGRESS_ANIM_MS = 160L
        /** 首帧迟迟不出现才判定打不开（跨境首连十几秒是常态，别太早翻脸） */
        private const val FIRST_PAINT_TIMEOUT_MS = 18_000L
        /** 首帧已出现后的兜底时长：只收进度条，不报错 */
        private const val SETTLE_TIMEOUT_MS = 60_000L
        /** 按下回车后多久还没动静就判定「卡住了」 */
        private const val NAV_WATCHDOG_MS = 10_000L
        /** 导航看门狗的放弃时间：跨境的 GitHub 首次连接 10 秒很正常，别急着报错 */
        private const val NAV_GIVE_UP_MS = 24_000L
        /** 刚发起导航时的进度起点，让进度条立刻出现 */
        private const val START_PROGRESS = 5
        private const val MENU_GROUP_PLUGIN = 1
        private const val MENU_PLUGIN_ID_BASE = 10000
        private const val MENU_SAVE_IMAGE = 2001
        private const val MENU_OPEN_IMAGE_NEW_TAB = 2002
        private const val MENU_OPEN_LINK_NEW_TAB = 2003
        private const val MENU_COPY_LINK = 2004
        private const val MENU_COPY_TEXT = 2005
        private const val MENU_OPEN_LINK_BACKGROUND = 2007
        private const val MENU_SHARE_LINK = 2008
        private const val MENU_COPY_IMAGE_URL = 2009
    }
}
