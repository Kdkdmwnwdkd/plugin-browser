package com.pluginbrowser.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据备份：书签 / 历史 / 设置 打包成一个 JSON。
 *
 * 自用场景下这是唯一的"后路"——换机、刷机、误删都靠它。
 */
object DataBackup {

    data class Stats(val bookmarks: Int, val history: Int)

    private const val PREFS_SETTINGS = "pb_settings"

    fun export(context: Context, uri: Uri): Stats {
        val db = BrowserDatabase(context).writableDatabase
        val root = JSONObject()
        root.put("app", "kite")
        root.put("format", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val bookmarks = JSONArray()
        db.rawQuery("SELECT title,url,created FROM bookmarks ORDER BY created", null).use { c ->
            while (c.moveToNext()) {
                bookmarks.put(
                    JSONObject()
                        .put("title", c.getString(0))
                        .put("url", c.getString(1))
                        .put("created", c.getLong(2)),
                )
            }
        }
        root.put("bookmarks", bookmarks)

        val history = JSONArray()
        db.rawQuery("SELECT title,url,visited FROM history ORDER BY visited DESC LIMIT 5000", null).use { c ->
            while (c.moveToNext()) {
                history.put(
                    JSONObject()
                        .put("title", c.getString(0))
                        .put("url", c.getString(1))
                        .put("visited", c.getLong(2)),
                )
            }
        }
        root.put("history", history)

        val settings = JSONObject()
        context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (v != null) settings.put(k, v)
        }
        root.put("settings", settings)

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("打不开输出文件")

        db.close()
        return Stats(bookmarks.length(), history.length())
    }

    /** 覆盖式恢复：现有书签与历史会被清空 */
    fun import(context: Context, uri: Uri): Stats {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("打不开备份文件")

        val root = runCatching { JSONObject(text) }.getOrElse { error("不是有效的备份文件") }
        if (root.optString("app") != "kite") error("这不是青鸢的备份")

        val db = BrowserDatabase(context).writableDatabase
        db.beginTransaction()
        var bookmarks = 0
        var history = 0
        try {
            db.delete("bookmarks", null, null)
            db.delete("history", null, null)

            val bm = root.optJSONArray("bookmarks") ?: JSONArray()
            for (i in 0 until bm.length()) {
                val o = bm.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                db.execSQL(
                    "INSERT OR REPLACE INTO bookmarks(title,url,created) VALUES(?,?,?)",
                    arrayOf(o.optString("title", url), url, o.optLong("created", System.currentTimeMillis())),
                )
                bookmarks++
            }

            val hs = root.optJSONArray("history") ?: JSONArray()
            for (i in 0 until hs.length()) {
                val o = hs.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                db.execSQL(
                    "INSERT INTO history(title,url,visited) VALUES(?,?,?)",
                    arrayOf(o.optString("title", url), url, o.optLong("visited", System.currentTimeMillis())),
                )
                history++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }

        val settings = root.optJSONObject("settings")
        if (settings != null) {
            val editor = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            val keys = settings.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = settings.get(k)) {
                    is String -> editor.putString(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                    else -> Unit
                }
            }
            editor.apply()
        }
        return Stats(bookmarks, history)
    }
}
