package com.pluginbrowser.plugin.js

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pluginbrowser.R
import com.pluginbrowser.plugin.api.BrowserHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * JS Bridge：页面内 PB API 与原生能力的桥梁。
 * 每个 WebView 注入一个实例（@JavascriptInterface 名 "PBAndroid"）。
 *
 * 方法在 WebView 的 JavaBridge 线程被调用；
 * 涉及 UI 的操作（如 evaluateJavascript 回传）统一 post 到主线程。
 */
class JsBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: BrowserHost?,
    private val onNotifyPermissionNeeded: () -> Unit,
    private val reply: (String) -> Unit,
    /** 当前 WebView 的 URL（来源校验用，页面脚本无法篡改） */
    private val currentUrl: () -> String? = { null },
    /**
     * 判断某插件 id 是否「对当前页面生效」（已启用 + JS 插件 + @match 命中）。
     * 页面里的 __plugin 参数是页面可写的，必须回到原生侧核对，
     * 否则任意站点都能冒用已安装插件的 grant 与存储。
     */
    private val isPluginActive: (String, String) -> Boolean = { _, _ -> false },
    /** 查询插件声明的 grant 集合（PluginManager.grantsOf），未声明则受限方法拒绝执行 */
    private val grantsOf: (String) -> Set<String> = { emptySet() },
) {

    companion object {
        private const val TAG = "PB/JsBridge"
        const val CHANNEL_ID = "pb_plugin"
        const val JS_NAME = "PBAndroid"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 受限方法 → 所需 grant（storage 类方法不在此列，所有插件默认可用） */
    private val methodGrants = mapOf(
        "http.request" to "http",
        "clipboard.write" to "clipboard",
        "notify" to "notify",
        "tabs.open" to "tabs",
    )

    @JavascriptInterface
    fun postMessage(json: String) {
        try {
            val obj = JSONObject(json)
            val id = obj.getLong("id")
            val method = obj.getString("method")
            val params = obj.optJSONObject("params") ?: JSONObject()
            val pluginId = params.optString("__plugin", "unknown")
            dispatch(id, method, pluginId, params)
        } catch (e: Exception) {
            Log.w(TAG, "bad message: $json", e)
        }
    }

    private fun dispatch(id: Long, method: String, pluginId: String, params: JSONObject) {
        // 1) 来源校验：插件必须对当前页面生效，防止任意站点冒用其他插件的权限与存储
        val url = currentUrl().orEmpty()
        if (!isPluginActive(pluginId, url)) {
            Log.w(TAG, "plugin not active for page: plugin=$pluginId url=$url method=$method")
            replyValue(id, JSONObject().put("error", "plugin \"$pluginId\" is not active for this page"))
            return
        }
        // 2) grant 校验
        val requiredGrant = methodGrants[method]
        if (requiredGrant != null && requiredGrant !in grantsOf(pluginId)) {
            Log.w(TAG, "permission denied: plugin=$pluginId method=$method needs grant=$requiredGrant")
            replyValue(id, JSONObject().put("error", "permission denied: grant \"$requiredGrant\" not declared"))
            return
        }
        when (method) {
            "storage.get" -> replyValue(id, storageGet(pluginId, params.optString("key")))
            "storage.set" -> {
                storageSet(pluginId, params.optString("key"), params.opt("value"))
                replyValue(id, true)
            }
            "storage.remove" -> {
                storageRemove(pluginId, params.optString("key"))
                replyValue(id, true)
            }
            "storage.keys" -> replyValue(id, storageKeys(pluginId))
            "http.request" -> scope.launch { replyValue(id, httpRequest(params)) }
            "clipboard.write" -> {
                clipboardWrite(params.optString("text"))
                replyValue(id, true)
            }
            "notify" -> notify(pluginId, params.optString("title"), params.optString("body"), id)
            "tabs.open" -> {
                val target = params.optString("url")
                mainHandler.post { host?.openTab(target) }
                replyValue(id, true)
            }
            else -> replyValue(id, JSONObject().put("error", "unknown method: $method"))
        }
    }

    private fun replyValue(id: Long, value: Any?) {
        val payload = JSONObject()
        payload.put("id", id)
        payload.put("result", value ?: JSONObject.NULL)
        val js = "window.__PB_REPLY__(" + JSONObject.quote(payload.toString()) + ")"
        mainHandler.post { reply(js) }
    }

    /* ---------------- storage（按插件 id 隔离） ---------------- */

    private fun prefsOf(pluginId: String) =
        context.getSharedPreferences("pb_plugin_data_$pluginId", Context.MODE_PRIVATE)

    private fun storageGet(pluginId: String, key: String): Any? {
        val raw = prefsOf(pluginId).getString(key, null) ?: return null
        return parseJson(raw)
    }

    private fun parseJson(raw: String): Any = try {
        when (raw.firstOrNull()) {
            '{' -> JSONObject(raw)
            '[' -> JSONArray(raw)
            else -> JSONTokener(raw).nextValue()
        }
    } catch (e: Exception) {
        raw
    }

    private fun storageSet(pluginId: String, key: String, value: Any?) {
        val text = when (value) {
            null -> ""
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString()
        }
        prefsOf(pluginId).edit().putString(key, text).apply()
    }

    private fun storageRemove(pluginId: String, key: String) {
        prefsOf(pluginId).edit().remove(key).apply()
    }

    private fun storageKeys(pluginId: String): JSONArray =
        JSONArray(prefsOf(pluginId).all.keys.toList())

    /* ---------------- http（无视页面 CORS） ---------------- */

    private fun httpRequest(params: JSONObject): JSONObject {
        val result = JSONObject()
        return try {
            val url = params.optString("url")
            require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 http/https 请求" }
            val method = params.optString("method", "GET").uppercase()
            val headers = params.optJSONObject("headers") ?: JSONObject()
            val body = if (params.isNull("data")) null else params.opt("data")?.toString()

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            headers.keys().forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
            if (body != null && method in setOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""
            val respHeaders = JSONObject()
            conn.headerFields?.forEach { (k, v) -> if (k != null) respHeaders.put(k, JSONArray(v)) }
            conn.disconnect()
            result.put("status", code)
                .put("statusText", conn.responseMessage.orEmpty())
                .put("headers", respHeaders)
                .put("body", text)
        } catch (e: Exception) {
            Log.w(TAG, "http request failed", e)
            result.put("error", e.message ?: "request failed")
        }
    }

    /* ---------------- 剪贴板 ---------------- */

    private fun clipboardWrite(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PB plugin", text))
    }

    /* ---------------- 通知 ---------------- */

    private fun notify(pluginId: String, title: String, body: String, id: Long) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                onNotifyPermissionNeeded()
                replyValue(id, JSONObject().put("error", "notification permission denied"))
                return
            }
        }
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "插件通知",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pb)
            .setContentTitle(title.ifBlank { "插件通知" })
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(pluginId.hashCode(), notification) }
            .onFailure { Log.w(TAG, "notify failed: plugin=$pluginId", it) }
        replyValue(id, true)
    }
}
