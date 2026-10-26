package com.pluginbrowser.translate

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlin.coroutines.resume

/**
 * 整页翻译的调度：注入脚本收集文本 → 分批调用供应商 → 回填 → 可还原。
 *
 * 免费额度普遍有 QPS 限制，所以分批之后还要按供应商插不同间隔。
 */
class TranslateManager(context: Context) {

    private val appContext = context.applicationContext
    private val settings = TranslateSettings(appContext)

    fun isConfigured(): Boolean = settings.isConfigured()

    fun providerLabel(): String = settings.provider.label

    suspend fun translatePage(
        webView: WebView,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<Int> = withContext(Dispatchers.Main) {
        try {
            val script = appContext.assets.open("translate.js")
                .bufferedReader().use { it.readText() }
            webView.evaluateJavascript(script, null)

            val raw = evaluate(webView, "JSON.stringify(window.__PB_TR.collect())")
                ?: return@withContext Result.failure(IOException("页面没有响应翻译脚本"))
            val jsonText = (JSONTokener(raw).nextValue() as? String).orEmpty()
            val items = if (jsonText.isBlank()) JSONArray() else JSONArray(jsonText)
            if (items.length() == 0) {
                return@withContext Result.failure(IOException("这一页没有可翻译的文字"))
            }

            // 页面给的 id 形如 "b0"/"t3"，必须原样带回去：
            // 传整数下标会让页面的 apply 在 pair.id.slice(1) 处抛异常，整页无变化
            val ids = (0 until items.length()).map { items.getJSONObject(it).optString("id") }
            val texts = (0 until items.length()).map { items.getJSONObject(it).getString("t") }
            val batches = batch(texts)

            val translated = arrayOfNulls<String>(texts.size)
            var done = 0
            for (batch in batches) {
                val payload = batch.map { it.second }
                val result = withContext(Dispatchers.IO) {
                    Translators.translate(settings.provider, settings, payload, settings.target)
                }
                batch.forEachIndexed { index, pair -> translated[pair.first] = result[index] }
                done += batch.size
                onProgress(done, texts.size)
                if (done < texts.size) withContext(Dispatchers.IO) { delay(requestGap()) }
            }

            val pairs = JSONArray()
            ids.forEachIndexed { index, id ->
                pairs.put(
                    JSONObject()
                        .put("id", id)
                        // 某段翻译失败就退回原文，避免把页面清空
                        .put("t", translated.getOrNull(index) ?: texts.getOrElse(index) { "" }),
                )
            }

            // 回填结果必须校验：曾经出现过"提示已翻译 N 段、页面却一行没变"
            val check = evaluate(webView, "JSON.stringify(window.__PB_TR.apply($pairs))")
            val applied = parseApplied(check)
            if (applied == 0) {
                return@withContext Result.failure(
                    IOException("译文没有写入页面，可能是页面脚本重建了内容，请稍后重试"),
                )
            }

            Result.success(applied)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restore(webView: WebView) = withContext(Dispatchers.Main) {
        evaluate(webView, "window.__PB_TR.restore()")
        Unit
    }

    /** evaluateJavascript 的返回值是 JSON 编码过的字符串，这里剥两层后取 applied */
    private fun parseApplied(raw: String?): Int = runCatching {
        val inner = (JSONTokener(raw ?: "").nextValue() as? String).orEmpty()
        JSONObject(inner).optInt("applied", 0)
    }.getOrDefault(0)

    /** 单批不超过 20 段 / 1200 字符，避免触发长度与频率限制 */
    private fun batch(texts: List<String>): List<List<Pair<Int, String>>> {
        val batches = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        var chars = 0
        texts.forEachIndexed { index, text ->
            if (current.isNotEmpty() && (chars + text.length > 1200 || current.size >= 20)) {
                batches.add(current)
                current = mutableListOf()
                chars = 0
            }
            current.add(index to text)
            chars += text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /** 百度免费版 QPS=1，其余供应商稍快一些 */
    private fun requestGap(): Long = when (settings.provider) {
        TranslateProvider.BAIDU -> 1_100L
        TranslateProvider.LIBRE -> 400L
        else -> 250L
    }

    private suspend fun evaluate(webView: WebView, js: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(js) { value -> continuation.resume(value) }
        }
}
