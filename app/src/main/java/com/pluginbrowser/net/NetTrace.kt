package com.pluginbrowser.net

import java.util.concurrent.atomic.AtomicInteger

/**
 * 网络请求的「黑匣子」。
 *
 * 之前排查「GitHub 慢」全靠猜：到底是 DNS、TLS、代理、还是通道压根没接管，
 * 看代码推不出来，只能改一版让用户试一版。
 *
 * 这里只做一件事：把每个 GitHub 域请求的决策结果留下来——
 * 走了通道还是交回系统、耗时多少、状态码、为什么没接管。
 * 界面上按域名聚合成一句「这个页面 12 个请求，8 个走通道，4 个因为并发已满回落」，
 * 一眼就能定位瓶颈在哪一层。
 *
 * 成本约束：shouldInterceptRequest 是热路径，所以只做一次数组写入，
 * 不加锁、不格式化字符串、不做任何 IO。
 */
object NetTrace {

    const val VIA_CHANNEL = "通道"
    const val VIA_SYSTEM = "系统"
    const val VIA_PLUGIN = "插件"

    data class Entry(
        val time: Long,
        val host: String,
        val path: String,
        val via: String,
        /** 从发请求到拿到响应头的毫秒数；系统栈拿不到，记 -1 */
        val ms: Long,
        val code: Int,
        val note: String,
        val main: Boolean,
    )

    private const val CAP = 120
    private const val PATH_MAX = 56

    private val items = arrayOfNulls<Entry>(CAP)
    private val cursor = AtomicInteger(0)

    fun record(
        host: String?,
        path: String?,
        via: String,
        ms: Long,
        code: Int = -1,
        note: String = "",
        main: Boolean = false,
    ) {
        val p = if (path.isNullOrEmpty()) "/" else if (path.length > PATH_MAX) {
            path.substring(0, PATH_MAX) + "…"
        } else {
            path
        }
        val i = cursor.getAndIncrement()
        items[Math.floorMod(i, CAP)] = Entry(
            time = System.currentTimeMillis(),
            host = host ?: "-",
            path = p,
            via = via,
            ms = ms,
            code = code,
            note = note,
            main = main,
        )
    }

    /** 旧 → 新 */
    fun snapshot(): List<Entry> {
        val n = cursor.get()
        val size = minOf(n, CAP)
        if (size <= 0) return emptyList()
        val out = ArrayList<Entry>(size)
        for (k in size - 1 downTo 0) {
            val idx = Math.floorMod(n - 1 - k, CAP)
            items[idx]?.let { out.add(it) }
        }
        return out
    }

    fun clear() {
        items.fill(null)
        cursor.set(0)
    }

    /** 最近 n 条里走通道的条数，用于「命中率」那一行 */
    fun channelStats(n: Int = 50): Pair<Int, Int> {
        val all = snapshot().takeLast(n)
        return all.count { it.via == VIA_CHANNEL } to all.size
    }
}
