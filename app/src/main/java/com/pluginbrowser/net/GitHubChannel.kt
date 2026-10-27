package com.pluginbrowser.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.pluginbrowser.data.SettingsRepository
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub 专用通道。
 *
 * 系统 WebView 用的是 Chromium 自己的网络栈：DNS 怎么解析、连接怎么建、走不走代理，
 * 全都改不了。而 GitHub 在国内慢，卡点恰好都在这几处——DNS 被塞假地址、
 * 跨境链路丢包、没有代理可用。
 *
 * 所以这里对 GitHub 家族域名自己发请求（OkHttp），换来三件事：
 *   1. 自定义 DNS：系统解析 + DoH 兜底，绕开污染；
 *   2. 可配置 HTTP / SOCKS5 代理；
 *   3. 磁盘缓存，带 hash 的静态资源第二次直接命中。
 *
 * 接管范围刻意收得很窄：只接 GET（POST 拿不到 body，重放不了），
 * 任何异常立刻返回 null 交回原生栈，连续失败还会熔断 60 秒。
 * 也就是说最差情况是「和以前一样慢」，不会变成「打不开」。
 */
class GitHubChannel(context: Context, private val settings: SettingsRepository) {

    private val cacheDir = File(context.applicationContext.cacheDir, "gh-http")

    /** 熔断：连续失败这么多次就歇一会儿，别让每个请求都卡在 connectTimeout 上 */
    private val failures = AtomicInteger()

    @Volatile
    private var blockedUntil = 0L

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var clientKey: String = ""

    /** DoH 请求必须用普通 DNS 解析，否则自己解析自己，递归打转 */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }

    private class DnsEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, DnsEntry>()

    /* ---------------- 对外 ---------------- */

    fun handles(host: String?): Boolean = isGitHubHost(host)

    /** 设置改动后调用：丢掉旧的客户端，让新的代理 / DNS 配置生效 */
    @Synchronized
    fun reset() {
        client = null
        clientKey = ""
        blockedUntil = 0L
        failures.set(0)
        dnsCache.clear()
    }

    /**
     * 拿一次 GitHub 首页的响应头，给设置页做「测试连接用」。
     * 同步调用，请在后台线程跑。
     */
    fun probe(): String {
        val transport = proxyOf(settings.proxyUrl.trim())
            ?.let { if (it.type() == Proxy.Type.SOCKS) "SOCKS 代理" else "HTTP 代理" }
            ?: "直连"
        val started = System.currentTimeMillis()
        return try {
            val probeClient = client().newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            probeClient.newCall(Request.Builder().url(PROBE_URL).build()).execute().use { resp ->
                val cost = System.currentTimeMillis() - started
                "已连通 · HTTP ${resp.code} · ${cost}ms · $transport"
            }
        } catch (e: Exception) {
            val cost = System.currentTimeMillis() - started
            "失败 · ${e.javaClass.simpleName}: ${e.message ?: "无详情"} · ${cost}ms · $transport"
        }
    }

    /**
     * 尝试接管一个请求。返回 null 表示交给 WebView 原生栈。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        // 这里是全站每个请求的必经之路，域名判定放最前面：
        // 非 GitHub 的请求在一次 endsWith 之后就返回了，不碰任何配置和请求头
        val uri = request.url
        if (!handles(uri.host)) return null

        if (!settings.githubAccel) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
        // 分段请求（视频、大文件续传）不接手：206 + Content-Range 交给原生栈更稳
        if (request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }) return null
        if (System.currentTimeMillis() < blockedUntil) return null

        val url = uri.toString()
        val builder = Request.Builder().url(url)
        request.requestHeaders.forEach { (rawKey, value) ->
            if (rawKey.lowercase() in DROP_REQUEST_HEADERS) return@forEach
            runCatching { builder.header(rawKey, value) }
        }
        // Cookie 统一以 WebView 的 CookieManager 为准，否则两边各存一份登录态，迟早对不上
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.header("Cookie", it) }

        val response = try {
            client().newCall(builder.build()).execute()
        } catch (e: Exception) {
            noteFailure()
            return null
        }
        noteSuccess()

        // 3xx / 304：WebResourceResponse 表达不了，交回原生栈让 WebView 自己跟。
        // 多一跳，但地址栏、前进后退、缓存校验都还对得上。
        if (response.code in 300..399) {
            response.close()
            return null
        }

        val body = response.body
        if (body == null) {
            response.close()
            return null
        }

        val contentType = response.header("Content-Type").orEmpty()
        // 没有 Content-Type 就猜不准，猜错会把 HTML 当二进制存下来——宁可不接管
        if (contentType.isBlank()) {
            response.close()
            return null
        }
        val mime = contentType.substringBefore(';').trim().ifEmpty { mimeOf(uri.path.orEmpty()) }
        val charset = charsetFor(mime, contentType)

        val headers = HashMap<String, String>(16)
        response.headers.forEach { (rawKey, value) ->
            val key = rawKey.lowercase()
            if (key in DROP_RESPONSE_HEADERS) return@forEach
            val prev = headers[rawKey]
            headers[rawKey] = if (prev.isNullOrEmpty()) value else "$prev, $value"
        }

        // Set-Cookie 必须单独回写：多值拼成一行会解析失败，登录态就丢了
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val cm = CookieManager.getInstance()
            setCookies.forEach { runCatching { cm.setCookie(url, it) } }
            runCatching { cm.flush() }
        }

        val stream = ClosingStream(body.byteStream()) { response.close() }
        return WebResourceResponse(
            mime,
            charset,
            response.code,
            reasonOf(response.code),
            headers,
            stream,
        )
    }

    /* ---------------- DNS ---------------- */

    /**
     * 系统解析优先，被污染的域名先问 DoH。
     *
     * raw / camo / avatars 这几个域名的污染是常态，而且污染返回的是「能连上但证书不对」的
     * 假地址，系统解析本身不会失败，所以只能按域名预先分流，不能等失败再兜底。
     *
     * OkHttp 不做 DNS 缓存，而 DoH 是一次完整的 HTTPS 请求（几百毫秒），
     * 所以结果自己存一份，否则每个新连接都要额外付这笔钱。
     */
    private fun resolve(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))

        val now = System.currentTimeMillis()
        dnsCache[hostname]?.let { if (it.expiresAt > now) return it.addresses }

        val result = lookup(hostname)
        if (dnsCache.size > 256) dnsCache.clear()
        dnsCache[hostname] = DnsEntry(result, now + DNS_TTL_MS)
        return result
    }

    private fun lookup(hostname: String): List<InetAddress> {
        val system = runCatching { InetAddress.getAllByName(hostname).toList() }
            .getOrNull()
            .orEmpty()

        if (!settings.dohEnabled) {
            if (system.isNotEmpty()) return system
            throw java.net.UnknownHostException("无法解析 $hostname")
        }

        // 被污染的域名：DoH 的结果排在前面，系统结果留在后面兜底。
        // 实测 DoH 给的边缘节点不一定可达，所以两份都要留着，让 OkHttp 顺着列表试。
        if (hostname in DOH_FIRST) {
            val merged = (dohLookupOrNull(hostname).orEmpty() + system).distinct()
            if (merged.isNotEmpty()) return merged
        }
        if (system.isNotEmpty()) return system
        dohLookupOrNull(hostname)?.let { return it }
        throw java.net.UnknownHostException("无法解析 $hostname")
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.contains(':')) return true
        for (c in host) {
            if (c != '.' && c !in '0'..'9') return false
        }
        return true
    }

    private fun dohLookupOrNull(host: String): List<InetAddress>? {
        for (endpoint in DOH_ENDPOINTS) {
            val result = runCatching { queryDoh(endpoint, host) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return null
    }

    private fun queryDoh(endpoint: String, host: String): List<InetAddress>? {
        val separator = if (endpoint.contains('?')) "&" else "?"
        val req = Request.Builder()
            .url("$endpoint${separator}name=$host&type=1")
            .header("Accept", "application/dns-json")
            .build()
        dohClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            val answers = JSONObject(text).optJSONArray("Answer") ?: return null
            val out = ArrayList<InetAddress>(4)
            for (i in 0 until answers.length()) {
                val item = answers.optJSONObject(i) ?: continue
                if (item.optInt("type") != 1) continue
                runCatching { InetAddress.getByName(item.optString("data")) }
                    .getOrNull()
                    ?.let { out.add(it) }
            }
            return out.ifEmpty { null }
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 缓存命中后这条路径每个请求都会走，所以先无锁查，只有配置变了才重建 */
    private fun client(): OkHttpClient {
        val key = "${settings.proxyUrl.trim()}|${settings.dohEnabled}"
        client?.let { if (clientKey == key) return it }
        return buildClient(key)
    }

    @Synchronized
    private fun buildClient(key: String): OkHttpClient {
        client?.let { if (clientKey == key) return it }

        val builder = OkHttpClient.Builder()
            .dns(dns)
            .cache(runCatching { Cache(File(cacheDir, "cache"), CACHE_BYTES) }.getOrNull())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 重定向自己跟会打乱地址栏和历史记录，交回 WebView
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        proxyOf(settings.proxyUrl.trim())?.let { builder.proxy(it) }
        return builder.build().also {
            client = it
            clientKey = key
        }
    }

    private fun noteFailure() {
        if (failures.incrementAndGet() >= FAILURE_LIMIT) {
            blockedUntil = System.currentTimeMillis() + BLOCK_MS
            failures.set(0)
        }
    }

    private fun noteSuccess() {
        failures.set(0)
    }

    private fun charsetFor(mime: String, contentType: String): String? {
        if (!isTextual(mime)) return null
        val declared = CHARSET_RE.find(contentType)?.groupValues?.getOrNull(1)
        return (declared ?: "utf-8").lowercase()
    }

    private fun isTextual(mime: String): Boolean =
        mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") ||
            mime.contains("xml") || mime.contains("svg")

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "html", "htm" -> "text/html"
        else -> "application/octet-stream"
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    /** WebView 不一定会关闭返回的流，包一层保证 OkHttp 的连接被归还 */
    private class ClosingStream(inner: InputStream, private val onClose: () -> Unit) :
        FilterInputStream(inner) {
        override fun close() {
            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }

    companion object {
        private const val PROBE_URL = "https://github.com/"

        private const val CACHE_BYTES = 96L * 1024 * 1024
        private const val FAILURE_LIMIT = 4
        private const val BLOCK_MS = 60_000L
        private const val DNS_TTL_MS = 5 * 60_000L

        /**
         * 这几个域名在国内基本都被污染，系统解析会返回假地址且不会报错，
         * 所以必须主动先问 DoH。
         */
        private val DOH_FIRST = setOf(
            "raw.githubusercontent.com",
            "camo.githubusercontent.com",
            "user-images.githubusercontent.com",
            "avatars.githubusercontent.com",
            "gist.githubusercontent.com",
            "objects.githubusercontent.com",
        )

        /** 都是 JSON 接口，比自己拼 DNS wire format 省事，国内也都可达 */
        private val DOH_ENDPOINTS = listOf(
            "https://dns.alidns.com/resolve",
            "https://doh.pub/dns-query",
        )

        /** 这些头交给 OkHttp 自己管，透传会打架 */
        private val DROP_REQUEST_HEADERS = setOf(
            "host", "connection", "keep-alive", "proxy-connection",
            "content-length", "transfer-encoding", "upgrade", "http2-settings",
            // 让 OkHttp 用 gzip 并自动解压，免得把压缩体塞给 WebView
            "accept-encoding",
        )

        /** 响应侧：前四个和 body 已经对不上了；Set-Cookie 单独回写 */
        private val DROP_RESPONSE_HEADERS = setOf(
            "content-encoding", "content-length", "transfer-encoding",
            "connection", "keep-alive", "set-cookie",
        )

        private val CHARSET_RE = Regex("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)

        /**
         * 解析代理串。支持 `127.0.0.1:7890`、`http://host:port`、`socks5://host:port`。
         * 用 createUnresolved：域名交给代理解析，比本地解析再连准得多。
         */
        fun proxyOf(spec: String): Proxy? {
            val text = spec.trim()
            if (text.isEmpty()) return null
            val withScheme = if (text.contains("://")) text else "http://$text"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            // Java 的 URI 对 IPv6 会带方括号，InetSocketAddress 不认
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val scheme = uri.scheme?.lowercase().orEmpty()
            val socks = scheme.startsWith("socks")
            val port = if (uri.port > 0) uri.port else if (socks) 1080 else 7890
            val type = if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress.createUnresolved(host, port))
        }

        /** 只认 GitHub 自己的域名，别把 `github.com.evil.tld` 之类也算进来 */
        fun isGitHubHost(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            if (host.equals("github.com", ignoreCase = true)) return true
            if (host.equals("www.github.com", ignoreCase = true)) return true
            // 每次都用 endsWith(ignoreCase) 而不是先 lowercase，省掉一个临时字符串
            return host.endsWith(".github.com", ignoreCase = true) ||
                host.endsWith(".githubusercontent.com", ignoreCase = true) ||
                // CSS/JS 走的是 github.githubassets.com，域名根其实叫 githubassets.com
                host.endsWith(".githubassets.com", ignoreCase = true)
        }
    }
}
