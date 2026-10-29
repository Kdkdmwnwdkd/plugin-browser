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
        // 堆栈只回答「炸在哪一行」，回答不了「死之前发生了什么」。
        // 把最近的事件流水一起落进来，一份文件就能看完来龙去脉。
        val trail = runCatching {
            EventLog.recent(80).joinToString("\n")
        }.getOrDefault("")
        val head = if (trail.isBlank()) "" else "\n--- 崩溃前事件 ---\n$trail\n--- 堆栈 ---\n"
        // 带上版本号：同一份文件里会堆着好几个版本的崩溃，不带版本就分不清
        // 「这次崩了」还是「上个月崩的」，看日志的人（还是我自己）会被旧记录骗
        file.appendText("=== $stamp · ${versionTag()} ===$head${Log.getStackTraceString(e)}\n")
    }

    private fun versionTag(): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName}(${info.versionCode})"
    }.getOrDefault("v?")

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
                // 没有堆栈 ≠ 没问题。把事件流水的尾巴一起带上，才看得出是内存塌了、
                // 还是某个页面刚打开就没了。
                val tail = runCatching {
                    EventLog.dump().lines().filter { it.isNotBlank() }.takeLast(18).joinToString("\n")
                }.getOrDefault("")
                if (tail.isBlank()) {
                    "上次没有任何记录，进程被系统直接结束（通常是内存不足）。"
                } else {
                    "上次没有留下堆栈，说明进程是被系统结束的（多半是内存不够）。\n" +
                        "结束前最后几行：\n$tail"
                }
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
