package com.pluginbrowser.sample

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.pluginbrowser.plugin.api.BrowserHost
import com.pluginbrowser.plugin.api.NativePlugin
import com.pluginbrowser.plugin.api.PluginMenuItem
import com.pluginbrowser.plugin.api.PluginMetadata
import java.io.ByteArrayInputStream

/**
 * 原生插件开发范本：HelloPlugin
 *
 * 演示能力：
 *  1. 生命周期回调（onLoad / onUnload / onPageStarted / onPageFinished）
 *  2. 贡献浏览器菜单项
 *  3. 拦截网络请求（此处演示屏蔽 example.com 下的跟踪像素）
 *
 * 开发自己的原生插件：
 *  1. 新建 Android 应用模块（或独立工程）；
 *  2. 以 compileOnly 方式依赖 plugin-api（运行时不打包，由宿主提供接口类）；
 *  3. 实现 NativePlugin，并在 Manifest 中按本模块的方式声明入口；
 *  4. 安装插件 APK 后，宿主 App 的「插件管理」中即会出现该插件。
 */
class SamplePlugin : NativePlugin {

    override val metadata = PluginMetadata(
        id = "com.pluginbrowser.sample",
        name = "Hello 原生插件",
        version = "1.0.0",
        author = "PluginBrowser 示例",
        description = "原生插件开发范本：演示生命周期回调、菜单项扩展与请求拦截。",
        permissions = listOf("pageEvents", "networkIntercept"),
    )

    private var host: BrowserHost? = null

    override fun onLoad(host: BrowserHost) {
        this.host = host
        host.showToast("Hello 原生插件已加载")
    }

    override fun onUnload() {
        host = null
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        // 演示：屏蔽 example.com 域名下的 1x1 跟踪像素
        if (!request.isForMainFrame && url.contains("example.com") && url.contains("/pixel")) {
            return WebResourceResponse(
                "image/gif",
                "utf-8",
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String) {
        // 可在此注入脚本、修改请求头等
    }

    override fun onPageFinished(view: WebView, url: String) {
        // 示例：页面加载完成后可做 DOM 增强（此处仅演示钩子存在）
    }

    override fun menuItems(): List<PluginMenuItem> = listOf(
        PluginMenuItem(id = "hello", title = "和插件打个招呼", order = 900),
    )

    override fun onMenuItemClicked(item: PluginMenuItem): Boolean {
        host?.showToast("你好，这是来自原生插件的问候！")
        return true
    }
}
