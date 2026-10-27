package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.net.GitHubChannel
import com.pluginbrowser.plugin.PluginManager

/**
 * 浏览器 WebViewClient：负责 URL 拦截、页面事件分发、历史记录与 JS 插件注入。
 */
class BrowserWebViewClient(private val owner: Owner) : WebViewClient() {

    /** 这些后缀的直链点开就下载，不尝试在页面里渲染 */
    private val DOWNLOAD_EXTENSIONS = setOf(
        "apk", "zip", "rar", "7z", "tar", "gz", "xz",
        "exe", "dmg", "iso",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "epub", "mobi", "torrent",
        "mp3", "flac", "wav", "m4a", "mp4", "mkv", "avi", "mov",
    )

    interface Owner {
        val browserSettings: SettingsRepository
        val browserStore: BrowserStore
        val browserPluginManager: PluginManager
        val browserTabManager: TabManager

        /** GitHub 专用通道（自定义 DNS / 代理），Application 级单例 */
        val githubChannel: GitHubChannel

        fun onPageStarted(tab: Tab)
        fun onPageFinished(tab: Tab)
        /** 进度被这里改写时（例如首帧可见后推高进度）需要刷新一次界面 */
        fun onProgressChanged(tab: Tab, progress: Int)
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

        /** WebView 渲染进程崩溃：必须丢弃这个 WebView，否则整页白屏且无法恢复 */
        fun onRenderProcessGone(tab: Tab)

        /** 在协程作用域执行（记录历史等） */
        fun runIo(block: suspend () -> Unit)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // 首页用 pb:// 把「点空白处聚焦地址栏」的意图交给原生
        if (url.startsWith("pb://")) return owner.onInternalScheme(url)

        if (url.startsWith("http://") || url.startsWith("https://")) {
            // 直链文件（.apk/.zip/.pdf…）直接交给下载器。
            // 交给 WebView 的话，服务端 Content-Type 不规范时只会显示一屏乱码或空白
            if (looksLikeDownload(url)) {
                return owner.onDownloadRequested(
                    url,
                    view.settings.userAgentString,
                    null,
                    null,
                    -1L,
                )
            }
            return false
        }
        if (url.startsWith("file://") || url.startsWith("about:")) return false

        // 自定义 scheme（tel: / mailto: / intent: / 各家 App 的 deep link）
        return owner.openExternally(url)
    }

    /** 按路径后缀判断是不是"点开就该下载"的直链 */
    private fun looksLikeDownload(url: String): Boolean {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()
            ?.lowercase()
            ?: return false
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return false
        return name.substringAfterLast('.') in DOWNLOAD_EXTENSIONS
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val tab = owner.browserTabManager.findTab(view) ?: return
        if (!url.startsWith("data:")) {
            tab.url = url
            tab.failedUrl = null
            tab.translated = false
            tab.committed = false
            tab.adCssInjected = false
        }
        tab.progress = 5
        // 记录来源页面 & 让嗅探器知道当前在哪个站
        if (url.startsWith("http")) ResourceSniffer.currentPageUrl = url
        // 拦截插件在后台线程拿不到 view.url（跨线程访问会直接抛异常），
        // 这里在主线程把当前页面告诉它，供第三方资源判定使用
        if (url.startsWith("http")) owner.browserPluginManager.enabledAdBlocker()?.onPageStarted(url)
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
        // 二次注入：首帧时样式可能被页面脚本清掉，加载完再补一次
        injectAdBlockCss(view, url)
        owner.onPageFinished(tab)
    }

    /** 首帧可见前就把元素隐藏样式塞进去，避免广告闪一下再消失 */
    override fun onPageCommitVisible(view: WebView, url: String) {
        super.onPageCommitVisible(view, url)
        val tab = owner.browserTabManager.findTab(view)
        if (tab != null && !tab.committed) {
            tab.committed = true
            // 内容已经画出来了，进度条别还卡在 5% 让人以为没动静
            if (tab.progress < COMMIT_PROGRESS) {
                tab.progress = COMMIT_PROGRESS
                owner.onProgressChanged(tab, COMMIT_PROGRESS)
            }
        }
        injectAdBlockCss(view, url)
    }

    private fun injectAdBlockCss(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        val tab = owner.browserTabManager.findTab(view)
        if (tab?.adCssInjected == true) return
        val plugin = owner.browserPluginManager.enabledAdBlocker() ?: return
        val js = runCatching { plugin.cosmeticScript(url) }.getOrNull() ?: return
        tab?.adCssInjected = true
        runCatching { view.evaluateJavascript(js, null) }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        // 嗅探媒体资源（内部只做后缀判断，开销很小）
        ResourceSniffer.record(request.url.toString())

        val plugins = owner.browserPluginManager.enabledNativePlugins()
        for (i in plugins.indices) {
            val resp = plugins[i].shouldInterceptRequest(view, request)
            if (resp != null) return resp
        }

        // 广告拦完之后再看要不要走 GitHub 通道：被拦掉的请求不该再花流量
        return owner.githubChannel.intercept(request)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail?,
    ): Boolean {
        val tab = owner.browserTabManager.findTab(view) ?: return true
        owner.onRenderProcessGone(tab)
        // 返回 true 表示已处理，避免系统直接杀掉整个应用
        return true
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

    private companion object {
        /** 首帧可见时进度条的最低位置：内容都出来了，不该还停在「刚开始」 */
        const val COMMIT_PROGRESS = 60
    }
}
