package com.pluginbrowser.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘 + 异常退出检测。
 *
 * 两件事分开看：
 *   1. Java 层未捕获异常：直接写堆栈，这是最有用的一份；
 *   2. 进程被系统杀掉 / native 崩溃：既不会走 uncaughtException，也不会留下堆栈。
 *
 * 第 2 种只能靠「上次是不是正常退出」来推断：启动时把一个 running 标记置真，
 * 正常销毁时置假。下次启动还是真，就说明上次是横着出去的。
 * 自用没人帮忙看后台，至少得让下次打开时能看见发生了什么。
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
        private const val KEY_RUNNING = "run_running"
        private const val KEY_SHOWN = "run_shown_crash"
        private const val PREFS = "pb_run_state"

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

        /** Activity 起来时调用：标记「这次进程在跑」 */
        fun markRunning(context: Context) {
            prefs(context).edit().putBoolean(KEY_RUNNING, true).commit()
        }

        /** 正常退出（用户关掉最后一个界面）时调用 */
        fun markClean(context: Context) {
            prefs(context).edit().putBoolean(KEY_RUNNING, false).commit()
        }

        /**
         * 上次是不是异常结束的；是的话返回一段能给人看的摘要，否则 null。
         *
         * 同一份崩溃只提示一次：读到的内容和上次提示过的指纹相同就跳过，
         * 免得用户每次打开都被同一个对话框挡一下。
         */
        fun lastRunCrash(context: Context): String? {
            val p = prefs(context)
            val wasRunning = p.getBoolean(KEY_RUNNING, false)
            val log = read(context)
            val fingerprint = (log ?: "").takeLast(200).hashCode().toString()
            val prevShown = p.getString(KEY_SHOWN, null)
            p.edit()
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_SHOWN, fingerprint)
                .commit()
            if (!wasRunning) return null
            if (prevShown == fingerprint) return null
            return if (log.isNullOrBlank()) {
                "上次没有留下堆栈。进程是被系统直接回收的，通常意味着内存不够（同时开的标签太多，或某个页面太重）。"
            } else {
                summarize(log)
            }
        }

        /** 从堆栈里挑最能说明问题的一行：异常类型 + 第一条 cause */
        private fun summarize(log: String): String {
            val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.lastOrNull { it.startsWith("Caused by") }
                ?: lines.lastOrNull { Regex("^[a-z][\\w.]*Exception|^[a-z][\\w.]*Error").containsMatchIn(it) }
                ?: lines.lastOrNull()
                ?: "未知错误"
            val frames = lines.filter { it.contains("at com.pluginbrowser") || it.contains("at android.") }
                .take(6)
            return (listOf(head) + frames).joinToString("\n")
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
