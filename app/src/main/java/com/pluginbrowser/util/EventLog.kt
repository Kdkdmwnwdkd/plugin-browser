package com.pluginbrowser.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环形事件时间线。
 *
 * 崩溃堆栈只能回答「炸在哪一行」，回答不了「死之前发生了什么」。而最常见的
 * 那类「用着用着突然回桌面」根本没有堆栈——进程是被系统 SIGKILL 掉的。
 * 那种情况唯一能留下证据的，就是死之前最后几十秒的事件流水。
 *
 * 三条约束：
 *  1. 写日志不能拖慢主线程 → 内存环形缓冲，异步批量落盘；
 *  2. 不能无限长 → 文件超过上限就砍掉前半段；
 *  3. 崩溃时要把最近的事件一并塞进 crash.log，一份文件就能看完。
 */
object EventLog {

    private const val CAPACITY = 300
    private const val MAX_FILE_BYTES = 160 * 1024
    private const val FLUSH_DELAY_MS = 800L
    private const val HEARTBEAT_MS = 30_000L

    private val ring = arrayOfNulls<String>(CAPACITY)
    private var head = 0
    private var count = 0
    private val lock = Any()
    private val pending = ArrayList<String>(64)

    @Volatile
    private var file: File? = null

    @Volatile
    private var appContext: Context? = null

    private val dirty = AtomicBoolean(false)

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pb-events").apply { isDaemon = true }
    }

    /** 标签数由上层注入，避免 util 反向依赖 browser 包 */
    @Volatile
    var tabCountProvider: (() -> Int)? = null

    /** 由 Application 调用，越早越好 */
    fun init(context: Context) {
        if (file != null) return
        val ctx = context.applicationContext
        appContext = ctx
        file = File(ctx.filesDir, "events.log")
        io.execute {
            runCatching { trimIfNeeded() }
        }
        log(
            "APP",
            "start v${versionName(ctx)} sdk=${Build.VERSION.SDK_INT} " +
                "${Build.MANUFACTURER}/${Build.MODEL}",
        )
    }

    /** 记一条事件。允许在任意线程、任意时刻调用（未 init 时静默丢弃）。 */
    fun log(tag: String, message: String) {
        if (file == null) return
        val line = "${stamp()} | $tag | $message"
        synchronized(lock) {
            ring[head] = line
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
            pending.add(line)
        }
        scheduleFlush()
    }

    /** 最近 n 条，从旧到新 */
    fun recent(n: Int): List<String> = synchronized(lock) {
        val take = minOf(n, count)
        if (take <= 0) return emptyList()
        val start = (head - take + CAPACITY) % CAPACITY
        (0 until take).mapNotNull { ring[(start + it) % CAPACITY] }
    }

    /** 整个文件（尾部 24KB），给「异常日志」界面看 */
    fun dump(): String {
        val f = file ?: return ""
        return runCatching {
            if (!f.exists()) "" else f.readText().takeLast(24 * 1024)
        }.getOrDefault("")
    }

    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            head = 0
            count = 0
            pending.clear()
        }
        runCatching { file?.delete() }
    }

    /**
     * 心跳：定期记录内存水位与标签数。
     *
     * 这行是整份日志里信息密度最高的——被系统杀掉前后，avail/sys 会明显塌陷，
     * 一看就知道是内存压力，而不是网络或代码问题。
     */
    fun startHeartbeat() {
        if (file == null) return
        io.scheduleAtFixedRate(
            { runCatching { beat() } },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun beat() {
        val ctx = appContext ?: return
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val javaMax = rt.maxMemory() / 1024 / 1024
        val native = runCatching { Debug.getNativeHeapAllocatedSize() / 1024 / 1024 }.getOrDefault(0L)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        var avail = 0L
        var low = false
        if (am != null) {
            runCatching { am.getMemoryInfo(info) }
                .onSuccess {
                    avail = info.availMem / 1024 / 1024
                    low = info.lowMemory
                }
        }
        val tabs = tabCountProvider?.invoke() ?: -1
        log(
            "HB",
            "java=${javaUsed}/${javaMax}MB native=${native}MB sysAvail=${avail}MB " +
                "low=$low tabs=$tabs",
        )
    }

    /* ---------------- 落盘 ---------------- */

    private fun scheduleFlush() {
        if (!dirty.compareAndSet(false, true)) return
        io.schedule(
            {
                dirty.set(false)
                runCatching { flush() }
            },
            FLUSH_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun flush() {
        val batch: List<String>
        synchronized(lock) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
        }
        val f = file ?: return
        runCatching {
            trimIfNeeded()
            f.appendText(batch.joinToString("\n", postfix = "\n"))
        }
    }

    /** 超限就只留后半段，防止长期使用把日志撑爆 */
    private fun trimIfNeeded() {
        val f = file ?: return
        if (!f.exists() || f.length() <= MAX_FILE_BYTES) return
        val text = f.readText()
        val keepFrom = text.length - MAX_FILE_BYTES / 2
        val cut = text.indexOf('\n', keepFrom.coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        f.writeText("--- trimmed ---\n" + text.substring(cut))
    }

    private fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
