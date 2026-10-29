package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.net.GitHubChannel
import com.pluginbrowser.net.NetTrace
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.site.SiteAction
import com.pluginbrowser.site.SiteRuleEngine
import com.pluginbrowser.site.SiteScripts
import com.pluginbrowser.util.EventLog

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

        /** 站点适配规则（哪些站该拦跳转、该横屏、该隐藏 App 横幅） */
        val siteRules: SiteRuleEngine

        /** 站点级加载偏好（桌面版 / 字号 / JS / 图片），按 host 覆盖全局设置 */
        val sitePreferences: com.pluginbrowser.site.SitePreferences

        /** 用户自己点出来的元素屏蔽规则 */
        val elementBlocker: com.pluginbrowser.site.ElementBlocker

        /** 拦掉了一次 App 跳转，交给界面提示用户「留在网页里了」 */
        fun onAppJumpBlocked(host: String)

        fun onPageStarted(tab: Tab)
        fun onPageFinished(tab: Tab)
        /**
         * 页内链接被点开、即将交给 WebView 加载。
         *
         * 这条回调存在的意义只有一个：让界面**立刻**有反应。
         * WebView 从「调用 loadUrl」到「onPageStarted」之间可能隔着好几秒的系统 DNS，
         * 这期间地址栏、进度条全都纹丝不动，用户看到的就是「点了没反应」。
         *
         * 必须带上发起导航的 WebView：后台标签也会继续跳转（重定向、自动刷新），
         * 拿「当前标签」去套用后台标签的地址，地址栏就会显示 A 站、页面却是 B 站。
         */
        fun onNavigationStarted(webView: WebView, url: String)
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

        /** HTTP 基本认证（路由器后台、内网服务常见） */
        fun onWebHttpAuth(view: WebView, host: String, realm: String, handler: HttpAuthHandler)
        /** 证书异常（过期、域名不匹配、自签名） */
        fun onWebSslError(host: String, error: SslError, handler: SslErrorHandler)

        /**
         * 在应用级协程作用域执行后台任务（记录历史等）。
         *
         * 契约：block 里**不得访问任何 View 或 Fragment 成员**。
         * 它可能在 Fragment 视图已经销毁之后才跑，碰 UI 就会崩。
         */
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
            // GitHub 慢在 DNS，先把预热排上；同时让 UI 立刻进入「正在加载」状态，
            // 不要等 onPageStarted——那一等可能就是好几秒，看起来像点了个死链接
            if (GitHubChannel.isGitHubHost(runCatching { request.url.host }.getOrNull())) {
                owner.githubChannel.warmUp()
            }
            // 自动播放、UA、字号这类只能在加载前改的设置，在这里先套好
            owner.siteRules.applyTo(view, url)
            owner.sitePreferences.applyTo(view, url)
            owner.onNavigationStarted(view, url)
            return false
        }
        if (url.startsWith("file://") || url.startsWith("about:")) return false

        // 站点适配：这个站点配了「拦 App 跳转」，就把唤醒 App 的请求吞掉。
        // 不拦的话 WebView 会把 intent:// 交给系统，用户直接被拽到应用商店，
        // 再回来时浏览位置全丢——这类站点的网页版其实是能用的。
        if (SiteRuleEngine.isAppWakeScheme(url) &&
            owner.siteRules.has(view.url, SiteAction.BLOCK_APP_JUMP)
        ) {
            owner.onAppJumpBlocked(SiteRuleEngine.hostOf(view.url).orEmpty())
            return true
        }

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

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String,
    ) {
        owner.onWebHttpAuth(view, host, realm, handler)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        owner.onWebSslError(error.url.orEmpty(), error, handler)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val tab = owner.browserTabManager.findTab(view) ?: return
        // 错误页是用 loadDataWithBaseURL 加载的，WebView 会额外报一次 about:blank。
        // 如果连它一起写进 tab.url，地址栏就变成 about:blank，重试也会去重试空白页。
        if (!url.startsWith("data:") && url != "about:blank") {
            // 从阅读页点进了别的链接：阅读模式已经名存实亡，把标记撤掉，
            // 否则菜单会一直显示「退出阅读」，点了却退不回去
            if (tab.readerMode && url != tab.readerUrl) tab.readerMode = false
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
        // 主文档真正开跑的时刻：和上一次 NAV click 的差就是「系统 DNS + 排队」耗时
        EventLog.log("NAV", "start ${hostOf(url)}")
        owner.siteRules.applyTo(view, url)
        owner.onPageStarted(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // WebView 的回调跑在主线程的 Handler 消息里，这里抛出的任何异常都没有人接，
        // 唯一的结局就是整个进程被杀——用户看到「浏览器自己没了」，还带不走任何现场。
        // 一次收尾工作没做完（历史少记一条、样式少注一次）远好过一次崩溃，所以整段兜住。
        runCatching { handlePageFinished(view, url) }
            .onFailure {
                EventLog.log("NAV", "onPageFinished 异常 ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    private fun handlePageFinished(view: WebView, url: String) {
        val tab = owner.browserTabManager.findTab(view) ?: return
        tab.progress = 100
        EventLog.log("NAV", "done ${hostOf(url)}")

        // 记录历史（无痕标签不记录）
        // 阅读模式那页的 base url 和原文一样，再记一次就是重复条目
        if (url.startsWith("http") && !tab.incognito && !tab.readerMode) {
            owner.runIo {
                owner.browserStore.addHistory(tab.title, url)
            }
        }

        // 注入 JS 插件（API 垫片 + 命中的用户脚本，按分块顺序执行）
        // 阅读页是本地的干净正文，用户脚本按站点匹配进来的样式反而会把它改坏
        if (url.startsWith("http") && !tab.readerMode) {
            owner.browserPluginManager.jsScriptFor(url).forEach { chunk ->
                runCatching { view.evaluateJavascript(chunk, null) }
            }
        }

        owner.browserPluginManager.enabledNativePlugins().forEach { it.onPageFinished(view, url) }
        // 二次注入：首帧时样式可能被页面脚本清掉，加载完再补一次
        injectAdBlockCss(view, url)
        injectBlockedElements(view, url)
        injectSiteGuards(view, url, tab)
        injectViewportFix(view, url, tab)
        owner.onPageFinished(tab)
    }

    /**
     * 把排版和缩放权还给用户。
     *
     * 桌面版模式不注：那时用户明确要的是 980px 的完整网站，
     * 再强行套移动 viewport 等于把页面拆了。
     */
    private fun injectViewportFix(view: WebView, url: String, tab: Tab) {
        if (!url.startsWith("http") || tab.readerMode) return
        if (owner.browserSettings.desktopUa) return
        runCatching { view.evaluateJavascript(SiteScripts.VIEWPORT_FIX, null) }
    }

    /**
     * 站点适配的页面脚本：隐藏「打开 App」横幅、拦掉它们的点击。
     *
     * 阅读模式那一页不注——它是本地渲染的干净正文，页面里没有任何 App 浮层，
     * 再注一遍只是白白跑一次 DOM 全量扫描。
     */
    private fun injectSiteGuards(view: WebView, url: String, tab: Tab) {
        if (!url.startsWith("http") || tab.readerMode) return
        if (!owner.siteRules.has(url, SiteAction.HIDE_APP_BANNER)) return
        runCatching { view.evaluateJavascript(SiteScripts.APP_BANNER, null) }
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
        injectBlockedElements(view, url)
    }

    /**
     * 用户屏蔽过的元素。
     *
     * 首帧和加载完成各来一次：很多站点的浮层是异步插进来的，
     * 只注入一次的话广告会在首帧之后自己冒出来。
     */
    private fun injectBlockedElements(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        val tab = owner.browserTabManager.findTab(view)
        if (tab?.readerMode == true) return
        val host = SiteRuleEngine.hostOf(url) ?: return
        val css = owner.elementBlocker.cssFor(host) ?: return
        runCatching { view.evaluateJavascript(SiteScripts.hideCss(css), null) }
    }

    private fun injectAdBlockCss(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        val tab = owner.browserTabManager.findTab(view)
        // 阅读页里没有广告，但元素隐藏规则是按站点写的，套上去会误伤正文
        if (tab?.readerMode == true) return
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
            if (resp != null) {
                NetTrace.record(
                    host = request.url.host,
                    path = request.url.path,
                    via = NetTrace.VIA_PLUGIN,
                    ms = -1,
                    note = "插件接管",
                    main = request.isForMainFrame,
                )
                return resp
            }
        }

        // 广告拦完之后再看要不要走 GitHub 通道：被拦掉的请求不该再花流量。
        // 这里顺手把决策结果喂给诊断面板——「为什么这个资源没加速」以前只能猜。
        val channel = owner.githubChannel
        val host = request.url.host
        val started = android.os.SystemClock.elapsedRealtime()
        val resp = channel.intercept(request)
        val cost = android.os.SystemClock.elapsedRealtime() - started
        val note = channel.takeNote().orEmpty()
        // 只记 GitHub 域和主文档：普通站点整页十几条三方请求会把列表冲得没法看
        if (channel.handles(host) || request.isForMainFrame) {
            val code = if (note.startsWith("ok ")) {
                note.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: -1
            } else {
                -1
            }
            NetTrace.record(
                host = host,
                path = request.url.path,
                via = if (resp != null) NetTrace.VIA_CHANNEL else NetTrace.VIA_SYSTEM,
                ms = cost,
                code = code,
                note = note,
                main = request.isForMainFrame,
            )
        }
        return resp
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail?,
    ): Boolean {
        val crashed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            detail?.didCrash() ?: false
        } else {
            false
        }
        val tab = owner.browserTabManager.findTab(view)
        // 这条以前只 toast 一句，日志里什么都不留。
        // 「卡着卡着就回桌面」最缺的就是这一行。
        EventLog.log(
            "CRASH",
            "renderer gone crashed=$crashed host=${hostOf(tab?.url)} tabs=${owner.browserTabManager.tabs.size}",
        )
        if (tab == null) return true
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
        EventLog.log("NAV", "error code=${error.errorCode} ${hostOf(request.url.toString())}")
        owner.onErrorPageNeeded(tab, error.errorCode)
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return runCatching {
            val u = android.net.Uri.parse(url)
            u.host ?: u.scheme ?: "-"
        }.getOrDefault("-")
    }

    private companion object {
        /** 首帧可见时进度条的最低位置：内容都出来了，不该还停在「刚开始」 */
        const val COMMIT_PROGRESS = 60
    }
}
