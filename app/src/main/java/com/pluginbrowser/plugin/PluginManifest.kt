package com.pluginbrowser.plugin

import org.json.JSONObject

enum class PluginType { JS, NATIVE }

/**
 * 插件清单（对应插件包内的 manifest.json）。
 * 两种插件共用一套描述结构，原生插件的清单由代码合成。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val type: PluginType,
    /** JS 插件入口文件（相对插件目录） */
    val entry: String?,
    /** 生效网址匹配模式，支持 * 通配 */
    val matches: List<String>,
    /** document_start / document_end */
    val runAt: String,
    /**
     * 权限声明（grant）。桥接层据此校验 JS 插件调用受限能力的权限：
     * http / clipboard / notify / tabs；storage 不需要声明。
     * manifest.json 中字段名为 "grant"（Tampermonkey 习惯），
     * 兼容旧名 "permissions"。
     */
    val permissions: List<String>,
    /** 预加载的依赖 JS（相对插件目录，在用户脚本前注入，等效于 UserScript 的 @require） */
    val requires: List<String> = emptyList(),
    /** 命名资源（名称 → 插件目录内文件，供 GM_getResourceText 同步读取，等效于 @resource） */
    val resources: Map<String, String> = emptyMap(),
    val builtin: Boolean,
    /** 默认是否启用 */
    val defaultEnabled: Boolean,
    /** 插件目录（文件系统路径），或 "assets:plugins/<name>" */
    val sourceDir: String,
) {
    companion object {

        fun fromJson(json: JSONObject, sourceDir: String): PluginManifest {
            val id = json.optString("id").trim()
            val name = json.optString("name").trim()
            require(id.isNotEmpty()) { "manifest.json 缺少 id 字段" }
            require(name.isNotEmpty()) { "manifest.json 缺少 name 字段" }
            val type = when (json.optString("type", "js").lowercase()) {
                "js", "userscript", "" -> PluginType.JS
                else -> PluginType.NATIVE
            }
            return PluginManifest(
                id = id,
                name = name,
                version = json.optString("version", "1.0.0"),
                author = json.optString("author", "未知作者"),
                description = json.optString("description", "暂无描述"),
                type = type,
                entry = json.optString("entry").ifBlank { "user.js" },
                matches = json.optJSONArray("matches")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: listOf("*://*/*"),
                runAt = json.optString("runAt", "document_end"),
                permissions = json.optJSONArray("grant")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: json.optJSONArray("permissions")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                requires = json.optJSONArray("requires")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                resources = json.optJSONObject("resources")?.let { obj ->
                    obj.keys().asSequence().associateWith { obj.getString(it) }
                } ?: emptyMap(),
                builtin = json.optBoolean("builtin", false),
                defaultEnabled = json.optBoolean("defaultEnabled", true),
                sourceDir = sourceDir,
            )
        }

        /** 从 NativePlugin 元数据合成清单 */
        fun fromMetadata(
            id: String,
            name: String,
            version: String,
            author: String,
            description: String,
            permissions: List<String>,
            builtin: Boolean,
            external: Boolean,
        ) = PluginManifest(
            id = id,
            name = name,
            version = version,
            author = author,
            description = description,
            type = PluginType.NATIVE,
            entry = null,
            matches = emptyList(),
            runAt = "document_end",
            permissions = permissions,
            builtin = builtin,
            defaultEnabled = true,
            sourceDir = if (external) "external-apk" else "builtin",
        )

        /** 判断 URL 是否匹配任一模式 */
        fun matchesUrl(patterns: List<String>, url: String): Boolean {
            if (patterns.isEmpty()) return false
            if (url.startsWith("about:") || url.startsWith("data:")) return false
            return patterns.any { patternToRegex(it).containsMatchIn(url) }
        }

        /**
         * 匹配模式转正则：
         *  "*."  → "(.*\.)?"（可选子域名）
         *  "*"   → ".*"
         *  "?"   → "."
         *  其余字符原样转义
         */
        fun patternToRegex(pattern: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> {
                        if (i + 1 < pattern.length && pattern[i + 1] == '.') {
                            sb.append("(.*\\.)?")
                            i++
                        } else {
                            sb.append(".*")
                        }
                    }
                    '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex("^" + sb.toString() + "$", RegexOption.IGNORE_CASE)
        }
    }
}
