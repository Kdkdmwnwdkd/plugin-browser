package com.pluginbrowser.util

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 极简文本/字节抓取，专供「网页一键安装脚本」这类用户主动发起的场景：
 * 跟随重定向（HttpURLConnection 不跨协议跳）、30s 超时、16MB 上限。
 * 不是通用网络库——错误一律返回 null，调用方给用户看「获取失败」就够了。
 */
object NetFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    fun text(url: String): String? = bytes(url)?.let { String(it, Charsets.UTF_8) }

    fun bytes(url: String): ByteArray? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = open(current) ?: return null
            val code = runCatching { conn.responseCode }.getOrNull()
                ?: run { conn.disconnect(); return null }
            when (code) {
                in 200..299 -> return readCapped(conn)
                301, 302, 303, 307, 308 -> {
                    val location = runCatching { conn.getHeaderField("Location") }.getOrNull()
                        ?: run { conn.disconnect(); return null }
                    conn.disconnect()
                    current = resolve(current, location) ?: return null
                }
                else -> {
                    conn.disconnect()
                    return null
                }
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false // 跨协议（http↔https）不跟，手动来
            setRequestProperty("User-Agent", "PluginBrowser userscript-installer")
        }
    }.getOrNull()

    private fun resolve(base: String, location: String): String? =
        runCatching { URI(base).resolve(location).toString() }.getOrNull()

    /** 读满或读完，超过上限直接放弃 */
    private fun readCapped(conn: HttpURLConnection): ByteArray? = try {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return null
                buf.write(chunk, 0, n)
            }
        }
        buf.toByteArray()
    } catch (e: Exception) {
        null
    }
}
