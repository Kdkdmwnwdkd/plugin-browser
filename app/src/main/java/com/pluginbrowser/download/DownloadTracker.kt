package com.pluginbrowser.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.util.UrlUtils
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.DownloadItem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载跟踪器：委托系统 DownloadManager 执行下载，并在本地数据库记录状态。
 * 由 Application 持有，全局注册完成广播。
 */
class DownloadTracker private constructor(private val context: Context) {

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val store = BrowserStore(context)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dmId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (dmId < 0) return
            PluginBrowserApp.appScope.launch { syncStatus(dmId) }
        }
    }

    fun register() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** 启动下载并异步记录；返回是否已成功入队 */
    fun enqueue(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ): Boolean {
        val fileName = UrlUtils.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(url)
            addRequestHeader("User-Agent", userAgent)
            // 需要登录态的直链要带上当前 Cookie，否则常见 403
            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { addRequestHeader("Cookie", it) }
            setMimeType(mimeType ?: "application/octet-stream")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT <= 28) {
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "PluginBrowser/$fileName",
                )
            } else {
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
        }
        val dmId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            null
        } ?: return false

        val path = localPath(fileName)
        // 写库放到 IO 线程，避免在 UI 线程 runBlocking 造成卡顿/ANR
        PluginBrowserApp.appScope.launch {
            runCatching { store.insertDownload(fileName, url, path, mimeType ?: "", dmId) }
        }
        return true
    }

    /** 下载完成/状态变化后同步本地记录 */
    private suspend fun syncStatus(dmId: Long) {
        val query = DownloadManager.Query().setFilterById(dmId)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val localStatus = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadItem.STATUS_DONE
                DownloadManager.STATUS_FAILED -> DownloadItem.STATUS_FAILED
                DownloadManager.STATUS_RUNNING -> DownloadItem.STATUS_RUNNING
                else -> DownloadItem.STATUS_PENDING
            }
            store.updateDownloadByDmId(dmId, localStatus, size)
        }
    }

    /** 文件最终路径（用于打开文件） */
    fun localPath(fileName: String): String =
        if (Build.VERSION.SDK_INT <= 28) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "PluginBrowser/$fileName",
            ).absolutePath
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName).absolutePath
        }

    companion object {
        @Volatile
        private var instance: DownloadTracker? = null

        fun get(context: Context): DownloadTracker =
            instance ?: synchronized(this) {
                instance ?: DownloadTracker(context.applicationContext).also { instance = it }
            }
    }
}
