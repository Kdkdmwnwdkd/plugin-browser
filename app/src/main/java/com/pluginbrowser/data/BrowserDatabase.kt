package com.pluginbrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 浏览器本地数据库：书签 / 历史 / 下载 */
class BrowserDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                created INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE history(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                visited INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE downloads(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                path TEXT NOT NULL,
                mime TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0,
                status INTEGER NOT NULL DEFAULT 0,
                dm_id INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_history_url ON history(url)")
        db.execSQL("CREATE INDEX idx_download_url ON downloads(url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN downloaded INTEGER NOT NULL DEFAULT 0")
            // 2.3.0 起下载改由自己的引擎执行，系统下载管理器里遗留的任务没人推进了，
            // 留着只会永远显示「下载中」，直接标失败让用户重下
            db.execSQL("UPDATE downloads SET status = 3 WHERE dm_id > 0 AND status IN (0, 1)")
        }
    }

    companion object {
        const val DB_NAME = "browser.db"
        const val DB_VERSION = 2
    }
}

/** 便捷写入辅助 */
internal fun ContentValues.putAll(pairs: Map<String, Any?>): ContentValues =
    apply {
        pairs.forEach { (k, v) ->
            when (v) {
                is String? -> put(k, v)
                is Long -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, if (v) 1L else 0L)
                null -> putNull(k)
                else -> put(k, v.toString())
            }
        }
    }
