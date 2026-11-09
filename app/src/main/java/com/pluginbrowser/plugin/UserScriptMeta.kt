package com.pluginbrowser.plugin

/**
 * 用户脚本元数据解析（Tampermonkey `==UserScript==` 块）。
 *
 * 独立于插件管理器，纯函数、无 Android 依赖，方便单测。
 * 解析规则按 Tampermonkey 的宽容程度来：键不区分大小写、缺块也能装
 * （缺 match 默认全站，和油猴安装时的提示一致）。
 */
object UserScriptMeta {

    data class Meta(
        val name: String,
        val version: String?,
        val author: String?,
        val description: String?,
        val namespace: String?,
        val matches: List<String>,
        val includes: List<String>,
        val runAt: String?,
        val grants: List<String>,
        val requires: List<String>,
        /** @resource 的 键 → URL，保持脚本里出现的顺序 */
        val resources: List<Pair<String, String>>,
    )

    private val KEY_LINE = Regex("^\\s*//\\s*@(\\S+)\\s+(.*\\S)\\s*$")
    private val BLOCK_START = Regex("^\\s*//\\s*==UserScript==\\s*$", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("^\\s*//\\s*==/UserScript==\\s*$", RegexOption.IGNORE_CASE)

    /** 解析失败返回 null：不是用户脚本（例如把 jsdelivr 的 README 下下来了） */
    fun parse(source: String): Meta? {
        val lines = source.lineSequence().toList()
        val start = lines.indexOfFirst { BLOCK_START.containsMatchIn(it) }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { BLOCK_END.containsMatchIn(it) }
        val header = lines.drop(start + 1).let { if (end >= 0) it.take(end) else it }

        val values = mutableMapOf<String, MutableList<String>>()
        for (line in header) {
            val m = KEY_LINE.find(line) ?: continue
            values.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() }
                .add(m.groupValues[2].trim())
        }
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> values[k]?.firstOrNull()?.takeIf { it.isNotBlank() } }
        fun all(vararg keys: String): List<String> =
            keys.flatMap { k -> values[k].orEmpty() }

        val name = first("name")
        // 没有任何一行像元数据的（连 @name 都没有）按无效处理，避免装进去一个「脚本」
        if (name == null && values.isEmpty()) return null

        return Meta(
            name = name ?: "",
            version = first("version"),
            author = first("author"),
            description = first("description"),
            namespace = first("namespace"),
            matches = all("match").filter { it.contains("://") },
            includes = all("include"),
            runAt = first("run-at", "runat"),
            grants = all("grant").filter { it.isNotBlank() && !it.equals("none", true) },
            requires = all("require").filter { it.startsWith("http") },
            resources = all("resource")
                .mapNotNull { entry -> entry.split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } }
                .map { (k, v) -> k.trim() to v.trim() }
                .filter { it.second.startsWith("http") },
        )
    }

    /**
     * 从元数据合成插件 id：id 直接作磁盘目录名与 storage 隔离键，
     * 优先 namespace 的主机名（油猴脚本之间天然不撞车），退化到 name 的规范化形式。
     * 只用 java.net.URI 解析（无 Android 依赖，单测可直接跑）。
     */
    fun idFor(meta: Meta): String {
        val host = meta.namespace
            ?.takeIf { it.contains("://") || it.startsWith("//") }
            ?.let { ns -> runCatching { java.net.URI(ns).host }.getOrNull() }
        val base = host?.takeIf { it.isNotBlank() } ?: meta.name
        val cleaned = base.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(60)
        if (cleaned.isNotEmpty() && cleaned[0].isLetterOrDigit()) return cleaned
        return "script"
    }

    /**
     * @include 转 @match 语义的宽松近似：
     *  - 自带 scheme 的按 match 处理；
     *  - 正则形式（/…/ 或 /^…$/）放弃转换，返回 null 让调用方跳过（宁缺勿滥）；
     *  - 其余按通配符 glob 前置默认 scheme。
     */
    fun includeToMatch(include: String): String? {
        val s = include.trim()
        if (s.startsWith("/") || s.endsWith("/")) return null
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("*://")) return s
        if (s.contains("://")) return null
        return "*://$s"
    }
}
