package com.pluginbrowser.translate

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 各家翻译接口的调用实现。
 * 统一约定：输入若干段文本，返回**等长**的译文列表；失败抛 IOException，信息尽量可读。
 */
object Translators {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 40_000

    suspend fun translate(
        provider: TranslateProvider,
        settings: TranslateSettings,
        texts: List<String>,
        target: TranslateTarget,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        fun field(key: String): String =
            settings.value(provider, provider.fields.first { it.key == key })

        return when (provider) {
            TranslateProvider.BAIDU -> baidu(field("appid"), field("secret"), texts, target)
            TranslateProvider.CAIYUN -> caiyun(field("token"), texts, target)
            TranslateProvider.OPENAI -> openAi(field("base"), field("key"), field("model"), texts, target)
            TranslateProvider.LIBRE -> libre(field("url"), field("key"), texts, target)
        }
    }

    /* ---------------- 百度大模型翻译 ---------------- */

    private fun baidu(appId: String, secret: String, texts: List<String>, target: TranslateTarget): List<String> {
        val cleanId = appId.trim()
        val candidates = secretCandidates(secret)

        // 密钥若被粘贴带入不可见字符，签名一定对不上；逐个形态试一次，成功即用
        fun once(q: String): List<String> {
            var last: IOException? = null
            for ((index, candidate) in candidates.withIndex()) {
                try {
                    return baiduOnce(cleanId, candidate, q, target)
                } catch (e: IOException) {
                    last = e
                    if (!isSignError(e)) throw e           // 额度/频率类错误重试无意义
                    if (index > 0) Thread.sleep(350)       // 免费版 QPS=1，连续重试留间隔
                }
            }
            throw last ?: IOException("百度翻译失败")
        }

        // 官方支持 q 用 \n 分段，一次返回等长数组；段数对不上时退回逐段
        val joined = texts.joinToString("\n")
        val parsed = runCatching { once(joined) }.getOrNull()
        if (parsed != null && parsed.size == texts.size) return parsed

        val out = ArrayList<String>(texts.size)
        var lastError: IOException? = null
        var success = 0
        for (q in texts) {
            val one = runCatching { once(q) }.getOrElse {
                lastError = it as? IOException ?: IOException(it.message ?: "翻译失败")
                null
            }
            if (one != null) {
                out.add(one.firstOrNull() ?: q)
                success++
            } else {
                out.add(q)
            }
        }
        // 一段都没成，说明是配置问题，别静默返回原文骗用户"翻译成功"
        if (success == 0) throw lastError ?: IOException("百度翻译失败")
        return out
    }

    /** 同一份密钥的几种等价形态，用于兜住粘贴带进来的不可见字符 */
    private fun secretCandidates(secret: String): List<String> {
        val set = LinkedHashSet<String>()
        fun add(value: String) {
            if (value.isNotEmpty()) set.add(value)
        }
        add(secret)
        add(secret.trim())
        add(secret.filterNot { it.isWhitespace() })
        add(secret.filter { it.code in 0x21..0x7E })   // 只保留可见 ASCII
        if (set.isEmpty()) set.add("")
        return set.toList()
    }

    private fun isSignError(e: IOException): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("54001") || msg.contains("invalid sign")
    }

    /**
     * 百度大模型翻译：APPID + 密钥 + MD5 签名。
     * 注意 salt 必须是**数字**，传字符串会报 53001。
     */
    private fun baiduOnce(appId: String, secret: String, q: String, target: TranslateTarget): List<String> {
        val salt = System.currentTimeMillis() % 10_000_000_000L
        val sign = md5(appId + q + salt + secret)

        val body = JSONObject()
            .put("appid", appId)
            .put("from", "auto")
            .put("to", target.code)
            .put("q", q)
            .put("salt", salt)
            .put("sign", sign)

        val resp = postJson(
            "https://fanyi-api.baidu.com/ait/api/aiTextTranslate",
            body.toString(),
            emptyMap(),
        )
        val json = JSONObject(resp)
        val code = json.optInt("error_code", 0)
        if (code != 0) {
            throw IOException("百度翻译 $code：${baiduHint(code, json.optString("error_msg"))}")
        }
        val array = json.optJSONArray("trans_result")
            ?: json.optJSONObject("data")?.optJSONArray("trans_result")
            ?: throw IOException("百度返回缺少 trans_result：${resp.take(200)}")

        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(item.optString("dst"))
        }
        if (out.isEmpty()) throw IOException("百度返回空结果")
        return out
    }

    private fun baiduHint(code: Int, raw: String): String = when (code) {
        54001 -> "APPID 与密钥不匹配，或密钥含空格/隐藏字符（大小写、0 与 O 也要核对）"
        54003 -> "请求过于频繁（免费版限 1 次/秒），稍等再试"
        54004 -> "账户额度不足"
        52001 -> "请求超时，检查网络"
        52003 -> "APPID 无效或服务未开通"
        53001 -> "请求参数格式错误"
        else -> raw.ifBlank { "未知错误" }
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /* ---------------- 彩云小译 ---------------- */

    private fun caiyun(token: String, texts: List<String>, target: TranslateTarget): List<String> {
        val transType = when (target) {
            TranslateTarget.ZH -> "auto2zh"
            TranslateTarget.EN -> "auto2en"
            TranslateTarget.JA -> "auto2ja"
            TranslateTarget.KO -> "auto2ko"
        }
        val body = JSONObject()
            .put("source", JSONArray(texts))
            .put("trans_type", transType)
            .put("request_id", UUID.randomUUID().toString())
            .put("detect", true)

        val resp = postJson(
            "https://api.interpreter.caiyunai.com/v1/translator",
            body.toString(),
            mapOf("x-authorization" to "token $token"),
        )
        val json = JSONObject(resp)
        val array = json.optJSONArray("target")
            ?: throw IOException("彩云返回缺少 target：${resp.take(200)}")
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) out.add(array.optString(i))
        if (out.size != texts.size) throw IOException("彩云返回段数不一致（${out.size}/${texts.size}）")
        return out
    }

    /* ---------------- OpenAI 兼容 ---------------- */

    private fun openAi(base: String, apiKey: String, model: String, texts: List<String>, target: TranslateTarget): List<String> {
        val root = base.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        val url = if (root.endsWith("/chat/completions")) root else "$root/chat/completions"

        val array = JSONArray(texts)
        val system = "你是翻译引擎。把用户给的 JSON 数组中的每一项翻译成${target.label}，" +
            "逐项对应、保持数量与顺序，只输出 JSON 数组本身，不要解释、不要代码块。"

        val body = JSONObject()
            .put("model", model.ifBlank { "gpt-4o-mini" })
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", array.toString())),
            )

        val resp = postJson(url, body.toString(), mapOf("Authorization" to "Bearer $apiKey"))
        val json = JSONObject(resp)
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: throw IOException("接口返回缺少 choices：${resp.take(200)}")

        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val resultArray = JSONArray(cleaned)
        val out = ArrayList<String>(resultArray.length())
        for (i in 0 until resultArray.length()) out.add(resultArray.optString(i))
        if (out.size != texts.size) {
            throw IOException("模型返回段数不一致（${out.size}/${texts.size}），请换个模型或减少单批段数")
        }
        return out
    }

    /* ---------------- LibreTranslate ---------------- */

    private fun libre(base: String, apiKey: String, texts: List<String>, target: TranslateTarget): List<String> {
        val root = base.trimEnd('/').ifBlank { "https://libretranslate.com" }
        val url = if (root.endsWith("/translate")) root else "$root/translate"

        val body = JSONObject()
            .put("q", texts.joinToString("\n"))
            .put("source", "auto")
            .put("target", target.code)
            .put("format", "text")
        if (apiKey.isNotBlank()) body.put("api_key", apiKey)

        val resp = postJson(url, body.toString(), emptyMap())
        val json = JSONObject(resp)
        val text = when (val node = json.opt("translatedText")) {
            is String -> node
            is JSONArray -> (0 until node.length()).joinToString("\n") { node.optString(it) }
            else -> throw IOException("LibreTranslate 返回异常：${resp.take(200)}")
        }
        val lines = text.split("\n")
        if (lines.size != texts.size) throw IOException("LibreTranslate 返回行数不一致（${lines.size}/${texts.size}）")
        return lines
    }

    /* ---------------- 通用 ---------------- */

    private fun postJson(url: String, body: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code：${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
