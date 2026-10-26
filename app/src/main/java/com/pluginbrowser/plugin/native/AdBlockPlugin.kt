package com.pluginbrowser.plugin.native

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.api.NativePlugin
import com.pluginbrowser.plugin.api.PluginMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * 内置原生插件：广告拦截
 * 基于 assets/blocklist.txt 域名名单，拦截子资源请求（不拦截主文档，避免误伤站点）。
 */
class AdBlockPlugin(private val context: Context) : NativePlugin {

    override val metadata = PluginMetadata(
        id = PluginManager.ADBLOCK_PLUGIN_ID,
        name = "广告拦截",
        version = "1.0.0",
        author = "插件浏览器",
        description = "拦截广告联盟与跟踪域名的子资源请求，加速页面加载、减少流量消耗。可在设置中开关。",
        permissions = listOf("networkIntercept"),
        builtin = true,
    )

    private val blockedHosts = HashSet<String>()
    @Volatile
    private var ready = false

    /** 异步加载拦截名单 */
    fun loadAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { blockedHosts.add(it) }
                }
            }
            ready = true
        }
    }

    val blockedCount: Int get() = blockedHosts.size

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!ready || request.isForMainFrame) return null
        val host = request.url.host?.lowercase() ?: return null
        if (!isBlocked(host)) return null
        // 以空响应替代广告请求；带上 CORS 头，避免站点脚本因读取响应头报错
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * 逐级查父域：a.b.example.com → b.example.com → example.com。
     * 相比 `blockedHosts.any { host.endsWith(".$it") }`，每条子资源从 O(名单长度) 降到 O(域名层数)。
     */
    private fun isBlocked(host: String): Boolean {
        var candidate = host
        while (true) {
            if (candidate in blockedHosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }
}
