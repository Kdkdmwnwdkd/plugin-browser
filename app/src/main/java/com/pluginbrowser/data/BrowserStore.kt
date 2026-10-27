package com.pluginbrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.pluginbrowser.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览器数据仓库：书签 / 历史 / 下载 / 地址栏建议 */
class BrowserStore(context: Context) {

    private val db = BrowserDatabase(context).writableDatabase

    /* ---------------- 书签 ---------------- */

    suspend fun addBookmark(title: String, url: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().putAll(
            mapOf("title" to title, "url" to url, "created" to System.currentTimeMillis()),
        )
        db.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun removeBookmark(url: String) = withContext(Dispatchers.IO) {
        db.delete("bookmarks", "url = ?", arrayOf(url))
    }

    suspend fun isBookmarked(url: String): Boolean = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }
    }

    suspend fun bookmarks(): List<ArchiveItem> = withContext(Dispatchers.IO) {
        queryList(
            "SELECT _id, title, url, created FROM bookmarks ORDER BY created DESC",
        ) { c -> ArchiveItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) }
    }

    /* ---------------- 历史 ---------------- */

    suspend fun addHistory(title: String, url: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().putAll(
            mapOf(
                "title" to title.ifBlank { url },
                "url" to url,
                "visited" to System.currentTimeMillis(),
            ),
        )
        db.insert("history", null, cv)
    }

    suspend fun history(limit: Int = 300): List<ArchiveItem> = withContext(Dispatchers.IO) {
        queryList(
            "SELECT _id, title, url, MAX(visited) FROM history GROUP BY url ORDER BY visited DESC LIMIT ?",
            arrayOf(limit.toString()),
        ) { c -> ArchiveItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        db.delete("history", null, null)
    }

    /** 删除指定 URL 的全部历史记录 */
    suspend fun clearHistoryFor(url: String) = withContext(Dispatchers.IO) {
        db.delete("history", "url = ?", arrayOf(url))
    }

    /* ---------------- 地址栏建议 ---------------- */

    suspend fun suggestions(query: String, searchEngine: String): List<SuggestionItem> =
        withContext(Dispatchers.IO) {
            val like = "%${query.trim()}%"
            val out = mutableListOf<SuggestionItem>()

            // 书签优先
            queryList(
                "SELECT title, url FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY created DESC LIMIT 5",
                arrayOf(like, like),
            ) { c -> ArchiveItem(0, c.getString(0), c.getString(1), 0) }
                .forEach { out.add(SuggestionItem(it.title, it.url, isBookmark = true)) }

            // 历史去重
            val seen = out.map { it.url }.toMutableSet()
            queryList(
                "SELECT title, url FROM history WHERE url LIKE ? OR title LIKE ? GROUP BY url ORDER BY visited DESC LIMIT 6",
                arrayOf(like, like),
            ) { c -> ArchiveItem(0, c.getString(0), c.getString(1), 0) }
                .filter { seen.add(it.url) }
                .forEach { out.add(SuggestionItem(it.title, it.url, isBookmark = false)) }

            // 搜索建议置顶
            val q = query.trim()
            if (q.isNotEmpty()) {
                out.add(0, SuggestionItem(q, UrlUtils.searchUrl(searchEngine, q), isBookmark = false, isSearch = true))
            }
            out
        }

    /* ---------------- 下载 ---------------- */

    suspend fun insertDownload(
        name: String,
        url: String,
        path: String,
        mime: String,
        dmId: Long,
        size: Long = 0L,
    ): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().putAll(
            mapOf(
                "name" to name,
                "url" to url,
                "path" to path,
                "mime" to mime,
                "size" to size,
                "downloaded" to 0L,
                "status" to DownloadItem.STATUS_PENDING,
                "dm_id" to dmId,
                "created" to System.currentTimeMillis(),
            ),
        )
        db.insert("downloads", null, cv)
    }

    /** 进度落库（节流后调用，别每读一块就写一次） */
    suspend fun updateDownloadProgress(id: Long, downloaded: Long, total: Long) =
        withContext(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put("downloaded", downloaded)
                if (total > 0) put("size", total)
            }
            db.update("downloads", cv, "_id = ?", arrayOf(id.toString()))
        }

    suspend fun updateDownloadStatus(id: Long, status: Int, size: Long = -1) =
        withContext(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put("status", status)
                if (size >= 0) put("size", size)
            }
            db.update("downloads", cv, "_id = ?", arrayOf(id.toString()))
        }

    suspend fun updateDownloadPath(id: Long, path: String) = withContext(Dispatchers.IO) {
        db.update(
            "downloads",
            ContentValues().apply { put("path", path) },
            "_id = ?",
            arrayOf(id.toString()),
        )
    }

    suspend fun hasActiveDownload(url: String): Boolean = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT 1 FROM downloads WHERE url = ? AND status IN (0, 1) LIMIT 1",
            arrayOf(url),
        ).use { it.moveToFirst() }
    }

    suspend fun downloadById(id: Long): DownloadItem? = withContext(Dispatchers.IO) {
        queryList(
            "SELECT _id, name, url, path, mime, size, status, dm_id, created, downloaded FROM downloads WHERE _id = ?",
            arrayOf(id.toString()),
        ) { c -> readDownload(c) }.firstOrNull()
    }

    /** 有待继续的任务（App 被杀后重进、点「重试」） */
    suspend fun unfinishedDownloads(): List<DownloadItem> = withContext(Dispatchers.IO) {
        queryList(
            "SELECT _id, name, url, path, mime, size, status, dm_id, created, downloaded FROM downloads " +
                "WHERE status IN (0, 1) AND dm_id = 0 ORDER BY created ASC",
        ) { c -> readDownload(c) }
    }

    suspend fun updateDownload(id: Long, status: Int, size: Long) = withContext(Dispatchers.IO) {
        val cv = ContentValues().putAll(mapOf("status" to status, "size" to size))
        db.update("downloads", cv, "_id = ?", arrayOf(id.toString()))
    }

    suspend fun updateDownloadByDmId(dmId: Long, status: Int, size: Long) = withContext(Dispatchers.IO) {
        val cv = ContentValues().putAll(mapOf("status" to status, "size" to size))
        db.update("downloads", cv, "dm_id = ?", arrayOf(dmId.toString()))
    }

    suspend fun downloads(): List<DownloadItem> = withContext(Dispatchers.IO) {
        queryList(
            "SELECT _id, name, url, path, mime, size, status, dm_id, created, downloaded FROM downloads ORDER BY created DESC",
        ) { c -> readDownload(c) }
    }

    private fun readDownload(c: android.database.Cursor) = DownloadItem(
        id = c.getLong(0),
        name = c.getString(1),
        url = c.getString(2),
        path = c.getString(3),
        mime = c.getString(4),
        size = c.getLong(5),
        status = c.getInt(6),
        dmId = c.getLong(7),
        created = c.getLong(8),
        downloaded = c.getLong(9),
    )

    suspend fun deleteDownload(id: Long) = withContext(Dispatchers.IO) {
        db.delete("downloads", "_id = ?", arrayOf(id.toString()))
    }

    suspend fun clearFinishedDownloads() = withContext(Dispatchers.IO) {
        db.delete("downloads", "status = ?", arrayOf(DownloadItem.STATUS_DONE.toString()))
    }

    /* ---------------- 清除数据 ---------------- */

    /** 清除浏览数据：历史 + 下载记录（书签按产品定义保留） */
    suspend fun clearBrowsingData() = withContext(Dispatchers.IO) {
        db.delete("history", null, null)
        db.delete("downloads", null, null)
    }

    /* ---------------- 内部工具 ---------------- */

    private fun <T> queryList(
        sql: String,
        args: Array<String>? = null,
        mapper: (android.database.Cursor) -> T,
    ): List<T> {
        val result = mutableListOf<T>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) result.add(mapper(c))
        }
        return result
    }
}
