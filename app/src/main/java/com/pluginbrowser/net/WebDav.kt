package com.pluginbrowser.net

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 最小可用的 WebDAV 客户端：只做 PUT 和 GET。
 *
 * 不做 PROPFIND、不列目录、不同步冲突——备份这件事只需要「覆盖上传」和「整包拉回」，
 * 剩下的都是把简单问题做复杂。服务器兼容性靠两点：不要求特定属性，失败时把状态码原样报出来。
 *
 * 支持 Nextcloud / 坚果云 / Synology / 群晖 / Alist 这类标准实现。
 */
object WebDav {

    data class Config(
        val baseUrl: String,
        val user: String,
        val password: String,
        val fileName: String,
    ) {
        /** 拼出文件完整地址：base 结尾有没有斜杠都认 */
        fun fileUrl(): String {
            val base = baseUrl.trim().trimEnd('/')
            val name = if (fileName.isBlank()) "kite-backup.json" else fileName.trim()
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "$base/$encoded"
        }
    }

    class DavException(val code: Int, message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 覆盖上传，返回字节数 */
    fun upload(cfg: Config, bytes: ByteArray): Int {
        val request = Request.Builder()
            .url(cfg.fileUrl())
            .put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { auth(cfg) }
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw fail(resp.code, "上传")
            return bytes.size
        }
    }

    fun download(cfg: Config): ByteArray {
        val request = Request.Builder()
            .url(cfg.fileUrl())
            .get()
            .apply { auth(cfg) }
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) throw DavException(404, "云端还没有备份文件")
            if (!resp.isSuccessful) throw fail(resp.code, "下载")
            return resp.body?.bytes() ?: throw DavException(resp.code, "云端返回了空内容")
        }
    }

    private fun Request.Builder.auth(cfg: Config): Request.Builder {
        if (cfg.user.isNotEmpty()) {
            header("Authorization", Credentials.basic(cfg.user, cfg.password))
        }
        return this
    }

    /**
     * 把状态码翻成人话。
     *
     * 401/403 是账号密码或权限，404 是路径不对，405 是服务器不允许 PUT，
     * 507 是网盘满了——这几种占了实际用起来会遇到的九成，直接说清楚比抛「HTTP 500」有用。
     */
    private fun fail(code: Int, action: String): DavException = when (code) {
        401 -> DavException(code, "账号或密码不对")
        403 -> DavException(code, "这个账号没有写入权限")
        404 -> DavException(code, "路径不存在，检查一下服务器地址")
        405 -> DavException(code, "服务器不允许这个操作（可能是只读目录）")
        409 -> DavException(code, "目标目录不存在，先在网盘里建好文件夹")
        507 -> DavException(code, "网盘空间不够了")
        in 500..599 -> DavException(code, "服务器出错了（$code）")
        else -> DavException(code, "${action}失败（HTTP $code）")
    }
}
