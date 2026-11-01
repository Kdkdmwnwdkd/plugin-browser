package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** 浏览器 WebChromeClient：进度、标题、图标、文件选择、全屏视频、多窗口、网页权限 */
class BrowserWebChromeClient(
    private val owner: Owner,
    /**
     * 这个 client 所属的标签。
     *
     * 大多数回调都带 WebView 参数、能反查出标签，唯独 onConsoleMessage 没有——
     * 而滚动上报正是从 console 通道回来的。以前所有标签共用一个 client、
     * 靠 findTab(view) 反查，接上报时才发现这条路走不通，于是按标签各建一个。
     */
    private val tab: Tab? = null,
) : WebChromeClient() {

    interface Owner {
        val browserTabManager: TabManager

        fun onProgressChanged(tab: Tab, progress: Int)
        fun onReceivedTitle(tab: Tab, title: String)
        fun onReceivedIcon(tab: Tab, icon: Bitmap)
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean

        fun onEnterFullscreen(view: View)
        fun onExitFullscreenRequest()
        fun openPopupWindow(): WebView?

        /** 网页申请相机/麦克风。不覆写的话 WebView 直接拒绝，页面里一点提示都没有 */
        fun onWebPermissionRequest(request: PermissionRequest)

        /** 网页申请定位 */
        fun onWebGeolocation(origin: String, callback: GeolocationPermissions.Callback)

        /** 页面真实滚动位置（见 SiteScripts.SCROLL_REPORT） */
        fun onPageScroll(tab: Tab, y: Int, viewportHeight: Int)
    }

    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        val text = msg?.message() ?: return false
        if (!text.startsWith(SCROLL_PREFIX)) return false
        val target = tab ?: return true
        val body = text.substring(SCROLL_PREFIX.length)
        val comma = body.indexOf(',')
        val y = (if (comma >= 0) body.substring(0, comma) else body).trim().toIntOrNull()
        if (y != null) {
            val vh = if (comma >= 0) body.substring(comma + 1).trim().toIntOrNull() ?: 0 else 0
            owner.onPageScroll(target, y, vh)
        }
        // 拦掉：这是我们自己的信令，不该出现在 logcat 里当噪音
        return true
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        val tab = owner.browserTabManager.findTab(view) ?: return
        tab.progress = newProgress
        owner.onProgressChanged(tab, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        val tab = owner.browserTabManager.findTab(view) ?: return
        if (!title.isNullOrBlank()) tab.title = title
        owner.onReceivedTitle(tab, title ?: "")
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        val tab = owner.browserTabManager.findTab(view) ?: return
        tab.favicon = icon
        icon?.let { owner.onReceivedIcon(tab, it) }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = owner.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        owner.onEnterFullscreen(view)
    }

    override fun onHideCustomView() {
        owner.onExitFullscreenRequest()
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        owner.onWebPermissionRequest(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        owner.onWebGeolocation(origin, callback)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        val newWebView = owner.openPopupWindow() ?: return false
        (resultMsg.obj as? WebView.WebViewTransport)?.let { transport ->
            transport.webView = newWebView
            resultMsg.sendToTarget()
        }
        return true
    }

    private companion object {
        const val SCROLL_PREFIX = "PBSCROLL:"
    }
}
