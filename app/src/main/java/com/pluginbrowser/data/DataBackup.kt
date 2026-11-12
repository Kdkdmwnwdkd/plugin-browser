package com.pluginbrowser.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据备份：书签 / 历史 / 设置 打包成一个 JSON。
 *
 * 自用场景下这是唯一的"后路"——换机、刷机、误删都靠它。
 *
 * 内容之外还多了两条路：写到本地文件（SAF），或者整包扔给 WebDAV。
 * 两条路用的是同一份快照，所以云端和本地文件永远是同一个格式。
 */
object DataBackup {

    data class Stats(val bookmarks: Int, val history: Int)

    data class Snapshot(val json: String, val stats: Stats)

    private const val PREFS_SETTINGS = "pb_settings"

    /**
     * 这些键不进备份。
     *
     * WebDAV 的账号密码是「取钥匙的方式」，不是钥匙本身保管的东西。把它们写进
     * 备份文件再传回同一个网盘，等于把钥匙挂在保险箱门上。
     */
    private val SKIP_KEYS = setOf(
        "webdav_url", "webdav_user", "webdav_password", "webdav_file",
    )

    fun snapshot(context: Context): Snapshot {
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
            if (v != null && k !in SKIP_KEYS) settings.put(k, v)
        }
        root.put("settings", settings)

        db.close()
        return Snapshot(root.toString(2), Stats(bookmarks.length(), history.length()))
    }

    fun export(context: Context, uri: Uri): Stats {
        val snap = snapshot(context)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(snap.json.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("打不开输出文件")
        return snap.stats
    }

    /** 覆盖式恢复：现有书签与历史会被清空 */
    fun import(context: Context, uri: Uri): Stats {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("打不开备份文件")
        return restore(context, text)
    }

    fun restore(context: Context, text: String): Stats {
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
