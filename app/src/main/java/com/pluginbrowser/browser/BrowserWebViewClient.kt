package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.plugin.PluginManager

/**
 * 浏览器 WebViewClient：负责 URL 拦截、页面事件分发、历史记录与 JS 插件注入。
 */
class BrowserWebViewClient(private val owner: Owner) : WebViewClient() {

    interface Owner {
        val browserSettings: SettingsRepository
        val browserStore: BrowserStore
        val browserPluginManager: PluginManager
        val browserTabManager: TabManager

        fun onPageStarted(tab: Tab)
        fun onPageFinished(tab: Tab)
        fun onErrorPageNeeded(tab: Tab, errorCode: Int)
        fun openExternally(url: String): Boolean

        /** 处理 pb:// 内部指令（如 pb://focus-address），返回 true 表示已消费 */
        fun onInternalScheme(url: String): Boolean
        fun onDownloadRequested(
            url: String,
            userAgent: String,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ): Boolean

        /** 在协程作用域执行（记录历史等） */
        fun runIo(block: suspend () -> Unit)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // 首页用 pb:// 把「点空白处聚焦地址栏」的意图交给原生
        if (url.startsWith("pb://")) return owner.onInternalScheme(url)
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("about:")
        ) {
            return false
        }
        // 自定义 scheme（tel: / mailto: / intent: 等）交给外部应用
        return owner.openExternally(url)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val tab = owner.browserTabManager.findTab(view) ?: return
        if (!url.startsWith("data:")) {
            tab.url = url
            tab.failedUrl = null
        }
        tab.progress = 5
        owner.browserPluginManager.enabledNativePlugins().forEach { it.onPageStarted(view, url) }
        // 提前注入声明 document_start 的 JS 插件（尽力而为：晚于页面脚本执行时仍会在加载完成后生效）
        if (url.startsWith("http")) {
            owner.browserPluginManager.earlyScriptFor(url).forEach { chunk ->
                runCatching { view.evaluateJavascript(chunk, null) }
            }
        }
        owner.onPageStarted(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val tab = owner.browserTabManager.findTab(view) ?: return
        tab.progress = 100

        // 记录历史（无痕标签不记录）
        if (url.startsWith("http") && !tab.incognito) {
            owner.runIo {
                owner.browserStore.addHistory(tab.title, url)
            }
        }

        // 注入 JS 插件（API 垫片 + 命中的用户脚本，按分块顺序执行）
        if (url.startsWith("http")) {
            owner.browserPluginManager.jsScriptFor(url).forEach { chunk ->
                runCatching { view.evaluateJavascript(chunk, null) }
            }
        }

        owner.browserPluginManager.enabledNativePlugins().forEach { it.onPageFinished(view, url) }
        owner.onPageFinished(tab)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        for (plugin in owner.browserPluginManager.enabledNativePlugins()) {
            val resp = plugin.shouldInterceptRequest(view, request)
            if (resp != null) return resp
        }
        return null
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        // 只处理主文档错误
        if (!request.isForMainFrame) return
        val tab = owner.browserTabManager.findTab(view) ?: return
        owner.onErrorPageNeeded(tab, error.errorCode)
    }
}
