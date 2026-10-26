package com.pluginbrowser.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新：查清单 → 多源下载 → 校验 → 拉起系统安装器。
 *
 * 清单与安装包都托管在 GitHub 仓库/Release 上，前面套一层加速代理，
 * 所以发新版只需要改仓库里的 version.json，客户端立刻能看到。
 */
object UpdateManager {

    private const val TAG = "PB/Update"
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 15000

    /** 清单地址：按顺序尝试，任一可用即可（国内优先走加速代理） */
    private val MANIFEST_URLS = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/Kdkdmwnwdkd/plugin-browser/main/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/Kdkdmwnwdkd/plugin-browser/main/version.json",
        "https://raw.githubusercontent.com/Kdkdmwnwdkd/plugin-browser/main/version.json",
        // GitHub API 作为兜底：实时内容，不受 raw CDN 缓存影响（返回 base64 包装）
        "https://api.github.com/repos/Kdkdmwnwdkd/plugin-browser/contents/version.json?ref=main",
    )

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val size: Long,
        val sha256: String?,
        val apkUrls: List<String>,
    )

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        data class UpToDate(val currentVersion: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** 当前安装版本号 */
    fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    } catch (e: Exception) {
        0
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** 检查更新（网络请求在 IO 线程） */
    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        var lastError = "网络不可用"
        var best: UpdateInfo? = null
        val current = currentVersionCode(context)

        for (url in MANIFEST_URLS) {
            val raw = runCatching { httpGet(url) }.getOrElse {
                lastError = it.message ?: "请求失败"
                null
            } ?: continue

            val info = runCatching { parse(raw) }.getOrElse {
                lastError = "清单格式错误"
                null
            } ?: continue

            // 取所有源里版本号最大的一个：某个源被缓存成旧版时不会误判
            if (best == null || info.versionCode > best!!.versionCode) best = info
            if (info.versionCode > current) break
        }

        val latest = best ?: return@withContext Result.Failed(lastError)
        return@withContext if (latest.versionCode > current) {
            Result.Available(latest)
        } else {
            Result.UpToDate(currentVersionName(context))
        }
    }

    /**
     * 逐源下载到应用缓存目录，成功返回文件。
     * onProgress 回调 0..100，可能被调用多次。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): kotlin.Result<File> = withContext(Dispatchers.IO) {
        val dir = context.externalCacheDir ?: context.cacheDir
        dir.mkdirs()
        val target = File(dir, "update-${info.versionName}.apk")
        var lastError = "没有可用的下载源"

        for (url in info.apkUrls) {
            try {
                if (target.exists()) target.delete()
                downloadTo(url, target, info.size, onProgress)
                val digest = sha256(target)
                if (info.sha256 != null && !digest.equals(info.sha256, ignoreCase = true)) {
                    lastError = "校验失败（文件可能被截断）"
                    target.delete()
                    continue
                }
                return@withContext kotlin.Result.success(target)
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                lastError = e.message ?: "下载失败"
                if (target.exists()) target.delete()
            }
        }
        kotlin.Result.failure(Exception(lastError))
    }

    /**
     * 拉起系统安装器。返回 false 表示需要先授予「安装未知应用」权限，
     * 此时已跳转到对应的系统设置页。
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            false
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private fun parse(raw: String): UpdateInfo {
        val text = if (raw.contains("\"encoding\"") && raw.contains("\"content\"")) {
            // GitHub Contents API：{"encoding":"base64","content":"..."}
            String(android.util.Base64.decode(JSONObject(raw).getString("content"), android.util.Base64.DEFAULT))
        } else {
            raw
        }
        val json = JSONObject(text)
        val apk = json.optJSONArray("apk")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList()
        require(apk.isNotEmpty()) { "清单缺少 apk 地址" }
        return UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName", "?"),
            notes = json.optString("notes", ""),
            size = json.optLong("size", 0L),
            sha256 = json.optString("sha256").takeIf { it.length == 64 },
            apkUrls = apk,
        )
    }

    private fun httpGet(url: String): String {
        // 加一次性参数：gh-proxy / raw.githubusercontent 都会按 URL 缓存清单
        val busted = url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis()
        val conn = (URL(busted).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "PluginBrowser-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadTo(url: String, target: File, expectedSize: Long, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PluginBrowser-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var read = 0L
            var lastPercent = -1
            var lastTick = 0L

            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceIn(0, 100)
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent && now - lastTick > 120) {
                                lastPercent = percent
                                lastTick = now
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (total > 0 && read < total * 0.98) error("下载不完整（$read/$total）")
            onProgress(100)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
