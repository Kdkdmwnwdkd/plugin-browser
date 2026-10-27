package com.pluginbrowser.plugin.native

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.pluginbrowser.adblock.AdBlockEngine
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.api.NativePlugin
import com.pluginbrowser.plugin.api.PluginMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * 内置原生插件：广告拦截。
 *
 * 规则来自 EasyList + EasyList China 的编译产物（assets/adblock），
 * 请求侧拦截子资源，页面侧注入元素隐藏样式。
 */
class AdBlockPlugin(private val context: Context) : NativePlugin {

    override val metadata = PluginMetadata(
        id = PluginManager.ADBLOCK_PLUGIN_ID,
        name = "广告拦截",
        version = "2.0.0",
        author = "青鸢",
        description = "EasyList 规则引擎：拦广告与追踪请求，并按站点隐藏广告位。可在设置中开关。",
        permissions = listOf("networkIntercept"),
        builtin = true,
    )

    val engine = AdBlockEngine(context)

    /** 异步加载规则，不占启动主线程 */
    fun loadAsync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { engine.load() }
    }

    val blockedCount: Int get() = engine.blockedCount.toInt()
    val ruleCount: Int get() = engine.hostRuleCount

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val pageUrl = runCatching { view.url }.getOrNull()
        if (!engine.shouldBlock(request.url.toString(), pageUrl)) return null
        // 空响应替代广告请求；补 CORS 头，避免页面脚本读响应时抛错
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

    /** 生成注入用的脚本：把元素隐藏规则塞进一个 style 标签 */
    fun cosmeticScript(pageUrl: String?): String? {
        val css = engine.cssFor(pageUrl) ?: return null
        if (css.isEmpty()) return null
        val literal = JSONObject.quote(css)
        return "(function(){var e=document.getElementById('pb-ab');" +
            "if(!e){e=document.createElement('style');e.id='pb-ab';" +
            "(document.head||document.documentElement).appendChild(e);}" +
            "e.textContent=$literal;})();"
    }
}
