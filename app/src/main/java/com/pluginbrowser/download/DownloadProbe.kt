package com.pluginbrowser.download

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.pluginbrowser.util.UrlUtils
import java.util.concurrent.TimeUnit

/**
 * 下载前的轻量探测：拿真实文件名、总大小、类型。
 *
 * DownloadListener 给的 contentLength 基本永远是 -1，而下不下载、下多大正是用户
 * 最想先知道的两件事，所以这里补一次 HEAD。服务器不支持 HEAD（403/405/501）时
 * 退化成 `Range: bytes=0-0`，只读响应头不读正文。
 */
object DownloadProbe {

    data class Info(val size: Long, val fileName: String?, val mime: String?)    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * @param base 可选的外部通道（GitHub 加速的 OkHttp），沿用它的 DNS / 代理 / 缓存，
     *             只把重定向和超时改成探测需要的值。GitHub 直链常要跟一次 302。
     */
    suspend fun probe(
        url: String,
        userAgent: String,
        referer: String?,
        base: OkHttpClient? = null,
    ): Info =
        withContext(Dispatchers.IO) {
            val http = base?.newBuilder()
                ?.followRedirects(true)
                ?.followSslRedirects(true)
                ?.callTimeout(6, TimeUnit.SECONDS)
                ?.connectTimeout(4, TimeUnit.SECONDS)
                ?.readTimeout(6, TimeUnit.SECONDS)
                ?.build()
                ?: client
            var size = -1L
            var name: String? = null
            var mime: String? = null

            fun read(resp: okhttp3.Response) {
                if (size <= 0) {
                    size = totalFromHeaders(
                        resp.header("Content-Range"),
                        resp.header("Content-Length"),
                    )
                }
                if (name == null) {
                    name = resp.header("Content-Disposition")
                        ?.let { UrlUtils.guessFileName(url, it, null) }
                }
                if (mime == null) {
                    mime = resp.header("Content-Type")?.substringBefore(';')?.trim()?.ifBlank { null }
                }
            }

            fun newRequest(): Request.Builder {
                val b = Request.Builder().url(url).header("User-Agent", userAgent)
                referer?.takeIf { it.startsWith("http") }?.let { b.header("Referer", it) }
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { b.header("Cookie", it) }
                return b
            }

            runCatching {
                http.newCall(newRequest().head().build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HEAD ${resp.code}")
                    read(resp)
                }
            }

            if (size <= 0) {
                runCatching {
                    http.newCall(newRequest().header("Range", "bytes=0-0").build())
                        .execute().use { resp ->
                            if (resp.isSuccessful || resp.code == 206) read(resp)
                        }
                }
            }

            Info(size, name, mime)
        }
}

/**
 * 从响应头推断文件总量，拿不到返回 -1。
 *
 * 分段请求（Range: bytes=0-0）只有 `Content-Range: bytes 0-0/11400000` 的尾段带总量，
 * 而且不支持 Range 的服务器会直接回 200 + Content-Length，所以两处都要看。
 */
internal fun totalFromHeaders(contentRange: String?, contentLength: String?): Long {
    val rangeTotal = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
    if (rangeTotal != null && rangeTotal > 0) return rangeTotal
    val length = contentLength?.toLongOrNull() ?: -1L
    return if (length > 0) length else -1L
}
