package com.pluginbrowser.adblock

import android.content.Context
import com.pluginbrowser.util.NetFetcher
import java.io.File

/**
 * 广告规则的「活水」：订阅的远端名单 + 用户自己的拦截/放行域名。
 *
 * 内置名单打在安装包里（assets/adblock），坏一个漏一个都得等更新；
 * 这里补两条通路：
 *  - 订阅：设置里填一个名单链接，启动或手动刷新时拉取，只在本地留一份编译后的副本；
 *    拉不到就用上次的，删掉副本就回到内置名单；
 *  - 自定义：一行一个域名，`||x^` 拦、`@@||x^` 放，跟 EasyList 习惯一致。
 *
 * 只编译「按域名拦」这类规则（纯域名、`||域名^`、`@@||域名^`）——
 * 完整 EasyList 的 URL 正则层由内置产物承担，订阅名单选常见广告域名清单最合适。
 */
object AdBlockLists {

    private const val PREFS = "adblock_lists"
    private const val KEY_SUB_URL = "subscription_url"
    private const val KEY_SUB_TIME = "subscription_updated_at"
    private const val KEY_SUB_COUNT = "subscription_entries"

    private const val REFRESH_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3 天

    fun subscriptionUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SUB_URL, "").orEmpty()

    fun setSubscriptionUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUB_URL, url.trim()).apply()
    }

    fun lastUpdatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SUB_TIME, 0L)

    fun lastEntryCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SUB_COUNT, 0)

    fun userRulesFile(context: Context): File =
        File(File(context.filesDir, "adblock"), "user-rules.txt")

    fun subscriptionFile(context: Context): File =
        File(File(context.filesDir, "adblock"), "subscription-hosts.txt")

    /**
     * 从订阅地址刷新：拉取 → 编译筛选 → 原子替换本地副本 → 记时间戳。
     * 网络失败时本地副本原样保留（下次启动引擎仍读它）。
     */
    fun refresh(context: Context, url: String): Result<Int> = runCatching {
        val text = NetFetcher.text(url) ?: error("获取失败（网络或链接不可用）")
        val entries = compileFilter(text)
        require(entries.isNotEmpty()) { "名单里没有可用的按域名拦截规则" }
        val file = subscriptionFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.joinToString("\n"))
        if (!tmp.renameTo(file)) {
            // renameTo 跨不了已存在文件，退一步覆盖写
            file.writeText(entries.joinToString("\n"))
            tmp.delete()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SUB_TIME, System.currentTimeMillis())
            .putInt(KEY_SUB_COUNT, entries.size)
            .apply()
        entries.size
    }

    /** 距上次刷新超过 3 天（首次不设阈值，交给手动） */
    fun dueForAutoRefresh(context: Context): Boolean {
        if (subscriptionUrl(context).isBlank()) return false
        val at = lastUpdatedAt(context)
        return at != 0L && System.currentTimeMillis() - at > REFRESH_INTERVAL_MS
    }

    /**
     * 订阅名单编译：只留能落进域名词表的行。
     * 返回保留的原始行（数量即 entries），写入文件供引擎直接逐行读。
     */
    internal fun compileFilter(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("#") }
        .map { line ->
            // hosts 风格「0.0.0.0 malware.cn」取最后一个字段当域名
            if (line.contains(' ')) line.split(Regex("\\s+")).last() else line
        }
        .filter { line ->
            when {
                line.startsWith("@@||") && line.endsWith("^") -> isDomain(anchorDomain(line))
                line.startsWith("||") && line.endsWith("^") -> isDomain(anchorDomain(line))
                line.startsWith("@@") || line.startsWith("/") || line.endsWith("$") -> false
                else -> isDomain(line.removePrefix("http://").removePrefix("https://").substringBefore('/'))
            }
        }
        .toList()

    private fun anchorDomain(line: String): String =
        line.removePrefix("@@").removePrefix("||").removeSuffix("^")

    private fun isDomain(host: String): Boolean =
        host.contains('.') && !host.contains('*') && !host.contains('^') &&
            !host.startsWith("-") && !host.endsWith("-") && !host.endsWith(".") &&
            host.none { it !in "abcdefghijklmnopqrstuvwxyz0123456789.-" }

    /* ---------------- 用户自定义 ---------------- */

    /** 读出用户规则原文（安装包内外都拿到同一份口径） */
    fun readUserRules(context: Context): List<String> {
        val f = userRulesFile(context)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** 重写用户规则（UI 编辑器整块保存） */
    fun writeUserRules(context: Context, lines: List<String>) {
        val f = userRulesFile(context)
        f.parentFile?.mkdirs()
        f.writeText(lines.joinToString("\n"))
    }

    /**
     * 单行用户规则 → 引擎语义（供引擎与单测共用）：
     * `@@||x^` / `@@x` → 放行域名；`||x^` / 纯域名 → 拦截域名；其余按通用 URL 模式拦。
     */
    internal fun classify(line: String): Pair<Boolean, String>? {
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("#")) return null
        val allow = s.startsWith("@@")
        val body = s.removePrefix("@@")
        if (body.startsWith("||")) {
            val host = body.removePrefix("||").removeSuffix("^")
            return if (isDomain(host)) allow to host else null
        }
        if (isDomain(body)) return allow to body
        // 通用 URL 模式：允许 `*` 和 `^` 通配，交给引擎的正则层
        return allow to body
    }
}
