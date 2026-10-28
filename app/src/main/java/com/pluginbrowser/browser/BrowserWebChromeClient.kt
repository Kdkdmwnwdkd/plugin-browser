package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** 浏览器 WebChromeClient：进度、标题、图标、文件选择、全屏视频、多窗口、网页权限 */
class BrowserWebChromeClient(private val owner: Owner) : WebChromeClient() {

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
}
