package com.pluginbrowser.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。自用没人帮忙看后台，出了问题至少能在「设置 → 关于」里翻到。
 * 只保留最近 200KB，避免无限增长。
 */
class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { write(e) }
        previous?.uncaughtException(t, e)
    }

    private fun write(e: Throwable) {
        val file = File(context.filesDir, "crash.log")
        if (file.exists() && file.length() > 200 * 1024) file.delete()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file.appendText("=== $stamp ===\n${Log.getStackTraceString(e)}\n")
    }

    companion object {
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger(context.applicationContext))
        }

        /** 供「关于」页面读取 */
        fun read(context: Context): String? {
            val file = File(context.filesDir, "crash.log")
            if (!file.exists() || file.length() == 0L) return null
            return runCatching { file.readText().takeLast(6000) }.getOrNull()
        }

        fun clear(context: Context) {
            runCatching { File(context.filesDir, "crash.log").delete() }
        }
    }
}
