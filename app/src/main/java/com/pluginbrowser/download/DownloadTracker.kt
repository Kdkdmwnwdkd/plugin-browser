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
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载跟踪器：委托系统 DownloadManager 执行下载，并在本地数据库记录状态。
 * 由 Application 持有，全局注册完成广播。
 */
class DownloadTracker private constructor(private val context: Context) {

    /** 单个任务的实时进度，供下载列表轮询展示 */
    data class Progress(val status: Int, val downloaded: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

        /** 服务器没给 Content-Length 时只能显示不确定进度 */
        val indeterminate: Boolean get() = total <= 0
    }

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val store = BrowserStore(context)

    /** 正在下载的任务：dmId -> 原始 URL，用来拦住重复点击 */
    private val active = ConcurrentHashMap<Long, String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dmId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (dmId < 0) return
            active.remove(dmId)
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

    /** 同一地址是否已经在下载，避免连点产生两个完全一样的任务 */
    fun isDownloading(url: String): Boolean = active.containsValue(url)

    /** 是否还有任务在跑（列表刷新用：刚入队时数据库可能还没写完） */
    fun hasActive(): Boolean = active.isNotEmpty()

    /**
     * 启动下载并异步记录。
     * @param expectedSize 已经知道的总大小（-1 表示未知），仅用于记录，实际进度仍以系统为准
     * @return 系统分配的下载 id；重复点击或入队失败返回 null
     */
    fun enqueue(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        expectedSize: Long = -1,
        preferredName: String? = null,
    ): Long? {
        if (active.containsValue(url)) return null

        val fileName = preferredName?.takeIf { it.isNotBlank() }
            ?: UrlUtils.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(url)
            addRequestHeader("User-Agent", userAgent)
            // 需要登录态的直链要带上当前 Cookie，否则常见 403
            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { addRequestHeader("Cookie", it) }
            setMimeType(mimeType ?: "application/octet-stream")
            // 下载中就在通知栏显示带进度的通知，完成后保留「下载完成」
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 统一落到系统「下载/PluginBrowser」：文件管理器、其他 App 都能看到。
            // DownloadManager 以系统身份写入，不受应用分区存储限制。
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "PluginBrowser/$fileName",
            )
        }
        val dmId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            null
        } ?: return null

        active[dmId] = url
        val path = localPath(fileName)
        // 写库放到 IO 线程，避免在 UI 线程 runBlocking 造成卡顿/ANR
        PluginBrowserApp.appScope.launch {
            runCatching {
                store.insertDownload(
                    fileName,
                    url,
                    path,
                    mimeType ?: "",
                    dmId,
                    if (expectedSize > 0) expectedSize else 0L,
                )
            }
        }
        return dmId
    }

    /** 批量查询进度。列表页每几百毫秒调一次，只查还在跑的任务 */
    fun progressOf(ids: List<Long>): Map<Long, Progress> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Progress>(ids.size)
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val doneCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getLong(idCol) else continue
                    out[id] = Progress(
                        status = if (statusCol >= 0) c.getInt(statusCol) else DownloadManager.STATUS_PENDING,
                        downloaded = if (doneCol >= 0) c.getLong(doneCol) else 0L,
                        total = if (totalCol >= 0) c.getLong(totalCol) else 0L,
                    )
                }
            }
        }
        return out
    }

    /** 取消下载并删掉系统里的记录（含已落到下载目录的临时文件） */
    fun cancel(dmId: Long) {
        active.remove(dmId)
        runCatching { dm.remove(dmId) }
    }

    /** 某个任务是否还挂着（用于列表刷新时判断要不要继续轮询） */
    fun isTracked(dmId: Long): Boolean = active.containsKey(dmId)

    fun markFinished(dmId: Long) {
        active.remove(dmId)
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

    /** 文件最终路径（系统下载目录）。API 29+ 应用无权限直接读，打开请用 DownloadManager 的 URI */
    fun localPath(fileName: String): String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "PluginBrowser/$fileName",
    ).absolutePath

    companion object {
        @Volatile
        private var instance: DownloadTracker? = null

        fun get(context: Context): DownloadTracker =
            instance ?: synchronized(this) {
                instance ?: DownloadTracker(context.applicationContext).also { instance = it }
            }
    }
}
