package com.pluginbrowser.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.AutoCompleteTextView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.pluginbrowser.MainActivity
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.download.DownloadTracker
import com.pluginbrowser.download.DownloadsFragment
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.api.PluginMenuItem
import com.pluginbrowser.plugin.js.JsBridge
import com.pluginbrowser.plugin.store.PluginStoreFragment
import com.pluginbrowser.settings.SettingsFragment
import com.pluginbrowser.translate.TranslateManager
import com.pluginbrowser.translate.TranslateSettingsFragment
import com.pluginbrowser.ui.BookmarksFragment
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
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var findQuery: String = ""

    /* ---------------- 状态 ---------------- */

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var pendingImageUrl: String? = null
    private var pendingLinkUrl: String? = null
    private var pendingDownload: PendingDownload? = null
    private val suggestionHandler = Handler(Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String?,
        val mimeType: String?,
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

    /* ---------------- 返回键 ---------------- */

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                currentWebView()?.canGoBack() == true -> currentWebView()?.goBack()
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    /* ---------------- 生命周期 ---------------- */

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        webViewClient = BrowserWebViewClient(this)
        webChromeClient = BrowserWebChromeClient(this)
        suggestionAdapter = SuggestionAdapter(requireContext())

        progressBar = view.findViewById(R.id.progress_bar)
        addressBarRow = view.findViewById(R.id.address_bar_row)
        securityIcon = view.findViewById(R.id.security_icon)
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

        setupAddressBar()
        setupFindBar()
        setupBottomBar()

        // 配置 WebView 工厂（必须在创建标签之前）
        tabManager.webViewConfigurator = { tab, wv -> configureWebView(tab, wv) }
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
        tabManager.attachTo(webContainer)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        currentWebView()?.onResume()
        refreshUi()
    }

    override fun onPause() {
        currentWebView()?.onPause()
        tabManager.saveSession()
        super.onPause()
    }

    override fun onDestroyView() {
        tabManager.pauseAll()
        suggestionRunnable?.let { suggestionHandler.removeCallbacks(it) }
        super.onDestroyView()
    }

    /* ---------------- 界面组装 ---------------- */

    private fun setupAddressBar() {
        addressInput.setAdapter(suggestionAdapter)
        addressInput.threshold = 1
        addressInput.addTextChangedListener { editable -> querySuggestions(editable?.toString() ?: "") }
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_NULL ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
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
            if (hasFocus && suggestionAdapter.count > 0) {
                addressInput.postDelayed({
                    if (addressInput.hasFocus()) addressInput.showDropDown()
                }, 120)
            }
        }
        btnReload.setOnClickListener { onReloadClicked() }
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

    /* ---------------- 页内查找 ---------------- */

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

    private fun setupBottomBar() {
        btnBack.setOnClickListener { currentWebView()?.goBack() }
        btnForward.setOnClickListener { currentWebView()?.goForward() }
        btnHome.setOnClickListener { currentWebView()?.loadUrl(settings.homeUrl) }
        btnTabs.setOnClickListener { openTabSwitcher() }
        btnMenu.setOnClickListener { anchor -> showOverflowMenu(anchor) }
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
        val tab = tabManager.currentTab() ?: return
        tab.failedUrl = null
        tab.webView.loadUrl(url)
    }

    private fun onReloadClicked() {
        val tab = tabManager.currentTab() ?: return
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
        if (target.isNotBlank()) tab.webView.loadUrl(target)
    }

    /** 打开 URL（书签、外部链接、插件 API 调用） */
    fun openUrl(url: String, incognito: Boolean = false) {
        if (url.isBlank()) return
        // 只影响本次新建的标签，不再改写全局 incognitoMode（否则之后所有新标签都变无痕）
        val tab = tabManager.newTab(url = url, incognito = incognito)
        onCurrentTabChanged(tab)
    }

    fun currentTabUrl(): String? {
        val tab = tabManager.currentTab() ?: return null
        return tab.failedUrl ?: tab.url
    }

    private fun currentWebView(): WebView? {
        val tab = tabManager.currentTab() ?: return null
        return if (tab.isCreated) tab.webView else null
    }

    /* ---------------- 溢出菜单 ---------------- */

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_browser, popup.menu)

        val desktopItem = popup.menu.findItem(R.id.action_desktop_ua)
        desktopItem.isCheckable = true
        desktopItem.isChecked = settings.desktopUa

        popup.menu.findItem(R.id.action_translate)?.title = getString(
            if (tabManager.currentTab()?.translated == true) R.string.menu_restore_translate
            else R.string.menu_translate,
        )

        // 原生插件贡献的菜单项
        val pluginMenuMap = mutableMapOf<Int, PluginMenuItem>()
        var nextId = MENU_PLUGIN_ID_BASE
        (requireActivity() as MainActivity).pluginMenuItems().forEach { (item, _) ->
            pluginMenuMap[nextId] = item
            popup.menu.add(MENU_GROUP_PLUGIN, nextId, item.order, item.title)
            nextId++
        }

        popup.setOnMenuItemClickListener { menuItem ->
            val pluginItem = pluginMenuMap[menuItem.itemId]
            if (pluginItem != null) {
                (requireActivity() as MainActivity).dispatchPluginMenuItem(pluginItem)
                return@setOnMenuItemClickListener true
            }
            when (menuItem.itemId) {
                R.id.action_refresh -> { reload(); true }
                R.id.action_find -> { showFindBar(); true }
                R.id.action_translate -> { toggleTranslate(); true }
                R.id.action_add_bookmark -> { showAddBookmarkDialog(); true }
                R.id.action_share -> { shareCurrentUrl(); true }
                R.id.action_desktop_ua -> { toggleDesktopUa(); true }
                R.id.action_new_tab -> {
                    tabManager.newTab(url = settings.homeUrl)
                    true
                }
                R.id.action_new_incognito_tab -> {
                    tabManager.incognitoMode = true
                    tabManager.newTab(url = settings.homeUrl)
                    true
                }
                R.id.action_bookmarks -> {
                    openScreen(BookmarksFragment.newInstance(BookmarksFragment.MODE_BOOKMARKS))
                    true
                }
                R.id.action_history -> {
                    openScreen(BookmarksFragment.newInstance(BookmarksFragment.MODE_HISTORY))
                    true
                }
                R.id.action_sniffer -> {
                    if (requireActivity().supportFragmentManager.findFragmentByTag(TAG_SNIFFER) == null) {
                        ResourceSnifferFragment().show(requireActivity().supportFragmentManager, TAG_SNIFFER)
                    }
                    true
                }
                R.id.action_downloads -> { openScreen(DownloadsFragment()); true }
                R.id.action_plugins -> { openScreen(PluginStoreFragment()); true }
                R.id.action_settings -> { openScreen(SettingsFragment()); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun openScreen(fragment: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
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
        val pending = PendingDownload(url, userAgent, contentDisposition, mimeType)
        val needPermission = android.os.Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingDownload = pending
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return true
        }
        startDownload(pending)
        return true
    }

    private fun startDownload(pending: PendingDownload) {
        val started = DownloadTracker.get(requireContext())
            .enqueue(pending.url, pending.userAgent, pending.contentDisposition, pending.mimeType)
        if (!started) {
            toast("下载启动失败")
        } else {
            toast(getString(R.string.downloads_status_running))
        }
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
    }

    override fun onExitFullscreenRequest() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        addressBarRow.isVisible = true
        bottomBar.isVisible = true
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
        registerForContextMenu(wv)
    }

    private fun requestNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifyPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /* ---------------- 页面事件（Owner 回调） ---------------- */

    override fun onPageStarted(tab: Tab) = refreshUi()

    override fun onPageFinished(tab: Tab) {
        refreshUi()
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
        val failed = tab.url.ifBlank { "about:blank" }
        tab.failedUrl = failed
        tab.webView.stopLoading()
        val html = ErrorPages.html(errorCode, failed)
        tab.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onInternalScheme(url: String): Boolean {
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
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
            true
        } catch (e: Exception) {
            toast("未能找到可以处理该链接的应用")
            false
        }
    }

    override fun runIo(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    /* ---------------- Owner 接口实现 ---------------- */

    override val browserSettings: SettingsRepository get() = settings
    override val browserStore: BrowserStore get() = store
    override val browserPluginManager: PluginManager get() = pluginManager
    override val browserTabManager: TabManager get() = tabManager

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
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.35f
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.35f

        tabCountBadge.text = tabManager.tabs.size.toString()

        val displayUrl = tab?.let { it.failedUrl ?: it.url } ?: ""
        // 首页不暴露 file:///android_asset/... 这种内部地址，直接留空显示提示语
        val isHome = tabManager.isHomeUrl(displayUrl)
        if (!addressInput.hasFocus()) {
            addressInput.setText(if (isHome) "" else displayUrl)
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

        // 加载中显示停止图标
        val loading = progress in 1..99
        btnReload.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
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
            }
            WebView.HitTestResult.ANCHOR_TYPE -> {
                pendingLinkUrl = result.extra
                menu.add(0, MENU_OPEN_LINK_NEW_TAB, 0, getString(R.string.open_new_tab))
                menu.add(0, MENU_COPY_LINK, 1, getString(R.string.copy_link))
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
        private const val TAG_TABS = "tab_switcher"
        private const val TAG_SNIFFER = "resource_sniffer"
        private const val PROGRESS_ANIM_MS = 160L
        private const val MENU_GROUP_PLUGIN = 1
        private const val MENU_PLUGIN_ID_BASE = 10000
        private const val MENU_SAVE_IMAGE = 2001
        private const val MENU_OPEN_IMAGE_NEW_TAB = 2002
        private const val MENU_OPEN_LINK_NEW_TAB = 2003
        private const val MENU_COPY_LINK = 2004
        private const val MENU_COPY_TEXT = 2005
    }
}
