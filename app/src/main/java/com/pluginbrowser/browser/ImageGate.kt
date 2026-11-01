package com.pluginbrowser.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.pluginbrowser.data.SettingsRepository
import java.io.ByteArrayInputStream

/**
 * 无图模式的总闸。
 *
 * 三档：始终加载 / 仅 Wi-Fi 加载 / 不加载。
 *
 * 拦截放在 [android.webkit.WebViewClient.shouldInterceptRequest]，这是唯一能在
 * **请求真正发出之前**叫停的位置。只靠页面 CSS 把图片藏起来，流量照样走完，
 * 那就只是「看不见」而不是「省流量」——地铁上、流量快用完时这种区别很要命。
 *
 * 单张放行靠白名单：页面里的图片占位脚本在被点的那张图上回调 [JavascriptInterface]，
 * 原生记下这个 URL，下一次同地址请求直接放行进真实网络。
 * 为什么不在 URL 后面挂 `?__pb_img=1` 再在拦截里剥掉：那样得自己重建请求，
 * Cookie、Referer、UA、压缩协商全要手工搬一遍，出问题的面比省下的代码大得多。
 */
class ImageGate(
    context: Context,
    private val settings: SettingsRepository,
    /**
     * 站点级「必须加载图片」的例外。
     *
     * 站点信息里单独把某站设成加载图片的人，是明确表过态的；
     * 全局无图模式不该把它盖掉，否则那个开关等于假的。
     */
    private val siteAllows: (String) -> Boolean = { false },
) {

    private val appContext = context.applicationContext

    /** 放行过的图片地址 → 过期时刻。上限 [MAX_ALLOWED] 条，超了先丢最旧的 */
    private val allowed = LinkedHashMap<String, Long>()

    private var meteredAt = 0L
    private var meteredValue = false

    val mode: String
        get() = settings.imageMode

    /** 当前这一档是不是「现在就要省图」 */
    val saving: Boolean
        get() = when (mode) {
            SettingsRepository.IMAGE_NEVER -> true
            SettingsRepository.IMAGE_WIFI -> isMetered()
            else -> false
        }

    /**
     * 该不该把这次请求换成空白图。
     *
     * 类型判断双条件：Accept 头以 image/ 开头，或路径后缀是图片。
     * 只认后缀会漏掉大量 CDN 的无后缀地址（`/img/abc123`），只认头又怕个别版本不给头。
     * 主文档永远不拦——判断失手把 HTML 换成 1 像素 GIF，用户看到的就是白屏。
     */
    fun shouldBlock(request: WebResourceRequest): Boolean {
        if (!saving) return false
        if (request.isForMainFrame) return false
        val url = request.url?.toString() ?: return false
        if (!url.startsWith("http")) return false
        val host = request.url.host.orEmpty()
        if (host.isNotEmpty() && siteAllows(host)) return false
        if (isAllowed(url)) return false
        return looksLikeImage(request, url)
    }

    /** 页面里点了某张被挡住的图：只放行这一个地址 */
    fun allow(url: String) {
        if (url.isBlank()) return
        synchronized(allowed) {
            val now = System.currentTimeMillis()
            allowed[url] = now + ALLOW_TTL_MS
            if (allowed.size > MAX_ALLOWED) {
                val stale = allowed.entries.take(allowed.size - MAX_ALLOWED)
                stale.forEach { allowed.remove(it.key) }
            }
        }
    }

    /** 切换档位、刷新页面时清空，免得旧白名单让「无图」偶尔漏一张 */
    fun reset() {
        synchronized(allowed) { allowed.clear() }
    }

    private fun isAllowed(url: String): Boolean {
        synchronized(allowed) {
            val until = allowed[url] ?: return false
            if (until > System.currentTimeMillis()) return true
            allowed.remove(url)
            return false
        }
    }

    private fun looksLikeImage(request: WebResourceRequest, url: String): Boolean =
        isImageUrl(url, request.requestHeaders?.get("Accept"))

    /**
     * 是否走的是计费网络。
     *
     * 用 isActiveNetworkMetered 而不是「是不是 Wi-Fi」：手机热点、部分按流量计费的
     * 家庭宽带也会被判成计费网络，正是用户说「别耗我流量」时想省的那一类。
     * 结果缓存 4 秒——一次页面加载会问几百次，每次都去 binder 查一遍纯属浪费。
     */
    private fun isMetered(): Boolean {
        val now = System.currentTimeMillis()
        if (now - meteredAt < METERED_TTL_MS) return meteredValue
        val value = runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                @Suppress("DEPRECATION")
                cm.isActiveNetworkMetered
            }
        }.getOrDefault(false)
        meteredAt = now
        meteredValue = value
        return value
    }

    companion object {
        private const val IMAGE_EXT_JOINED = "jpg,jpeg,png,gif,webp,bmp,avif,svg,ico,jfif"
        private val IMAGE_EXT = IMAGE_EXT_JOINED.split(',').toSet()
        private const val MAX_ALLOWED = 200
        private const val ALLOW_TTL_MS = 5 * 60 * 1000L
        private const val METERED_TTL_MS = 4_000L

        /**
         * 这次请求是不是图片。
         *
         * 双条件：Accept 头以 image/ 开头，或路径后缀是图片。
         * 只认后缀会漏掉大量 CDN 的无后缀地址（`/img/abc123`），只认头又怕个别版本不给头。
         * 抽成纯函数是为了能单独测：判断错了的代价不是「少省一张图」，
         * 而是把 JS/CSS 换成 1 像素 GIF,整站直接坏掉。
         */
        fun isImageUrl(url: String, accept: String?): Boolean {
            if (accept != null && accept.lowercase().trimStart().startsWith("image/")) return true
            val path = url.substringBefore('#').substringBefore('?')
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXT
        }

        /**
         * 1×1 透明 GIF。比返回 204 好：204 会让部分站点触发 onerror，重试几次反而更费流量。
         *
         * 用 lazy 而不是直接初始化：android.util.Base64 在 JVM 单测里是空壳，
         * 放进 companion 的初始化块会让整个类的纯逻辑测试全部炸在 ExceptionInInitializerError。
         */
        private val BLANK_GIF: ByteArray by lazy {
            android.util.Base64.decode(
                "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
                android.util.Base64.DEFAULT,
            )
        }

        fun blankResponse(): WebResourceResponse = WebResourceResponse(
            "image/gif",
            null,
            200,
            "OK",
            mapOf(
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
            ),
            ByteArrayInputStream(BLANK_GIF),
        )
    }
}

/**
 * 给页面用的窄接口：只暴露「放行这张图」。
 *
 * 单独建一个对象而不是并进 PBAndroid，是因为后者带插件 API 的权限模型，
 * 这个能力跟插件无关，混进去会让授权表多出个说不清的条目。
 */
class ImageGateBridge(private val gate: ImageGate) {

    @JavascriptInterface
    fun allow(url: String) {
        gate.allow(url)
    }
}
