package com.pluginbrowser.plugin

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.pluginbrowser.plugin.api.BrowserHost
import com.pluginbrowser.plugin.api.NativePlugin
import com.pluginbrowser.plugin.native.AdBlockPlugin
import com.pluginbrowser.plugin.native.ExternalPluginLoader
import com.pluginbrowser.plugin.native.NativePluginRegistry
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件管理器（双运行时调度核心）。
 *
 *  - JS 插件：userscript 形式，页面加载完成后注入 pb-bridge.js + 用户脚本；
 *  - 原生插件：内置注册 + 外部 APK 发现（DexClassLoader 动态加载）；
 *  - 安装：导入 .pbp 插件包（zip：manifest.json + 脚本文件）。
 */
class PluginManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        const val ADBLOCK_PLUGIN_ID = "builtin.adblock"
        private const val TAG = "PB/PluginManager"
        private const val PREFS_NAME = "pb_plugin_states"
        private const val PLUGIN_ROOT = "plugins"

        /** 插件 id 白名单：id 会被直接用作磁盘目录名 */
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsPlugins = mutableListOf<PluginManifest>()
    private val nativePlugins = mutableListOf<NativePlugin>()
    private val externalIds = mutableSetOf<String>()
    private val loadedNativeIds = mutableSetOf<String>()

    private var bridgeScriptCache: String? = null
    private val scriptCache = mutableMapOf<String, String>()

    /** 浏览器宿主提供者（MainActivity 注册），供 PB.tabs.open 等 API 使用 */
    var hostProvider: (() -> BrowserHost?)? = null

    /** 插件列表变化监听（插件商店 UI 用） */
    var onPluginsChanged: (() -> Unit)? = null

    private val fallbackHost = object : BrowserHost {
        override val currentUrl: String? = null
        override fun openTab(url: String, incognito: Boolean) {}
        override fun showToast(message: String) {}
        override fun getSetting(key: String, default: String?): String? = default
        override val isIncognito: Boolean = false
    }

    val host: BrowserHost get() = hostProvider?.invoke() ?: fallbackHost

    init {
        reload()
        // 注册内置原生插件
        NativePluginRegistry.create(context).forEach { p ->
            nativePlugins.add(p)
            if (p is AdBlockPlugin) p.loadAsync(scope)
        }
    }

    /* ---------------- 扫描与加载 ---------------- */

    /** 重新扫描：assets 内置 JS 插件 + 用户安装的 JS 插件 */
    @Synchronized
    fun reload() {
        jsPlugins.clear()
        scriptCache.clear()
        runCatching {
            context.assets.list(PLUGIN_ROOT)?.forEach { name ->
                runCatching { loadManifestFromAssets("$PLUGIN_ROOT/$name") }
                    .onFailure { Log.w(TAG, "skip builtin plugin $name: ${it.message}") }
            }
        }
        pluginRoot().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            runCatching { loadManifestFromDir(dir) }
                .onFailure { Log.w(TAG, "skip invalid plugin dir ${dir.name}: ${it.message}") }
        }
    }

    private fun loadManifestFromAssets(assetDir: String) {
        jsPlugins.add(readManifestFromAssets(assetDir))
    }

    private fun readManifestFromAssets(assetDir: String): PluginManifest {
        val json = context.assets.open("$assetDir/manifest.json").bufferedReader().use { it.readText() }
        return PluginManifest.fromJson(JSONObject(json), "assets:$assetDir")
    }

    private fun loadManifestFromDir(dir: File) {
        val manifestFile = File(dir, "manifest.json")
        require(manifestFile.exists()) { "缺少 manifest.json" }
        val manifest = PluginManifest.fromJson(JSONObject(manifestFile.readText()), dir.absolutePath)
        require(manifest.type == PluginType.JS) { "安装的插件必须是 JS 类型（type=js）" }
        jsPlugins.add(manifest)
    }

    /** 发现并加载外部原生插件 APK（由 Application 启动时调用） */
    @Synchronized
    fun loadExternalNativePlugins() {
        val discovered = ExternalPluginLoader.discover(context, javaClass.classLoader!!)
        var changed = false
        for (p in discovered) {
            if (p.metadata.id in loadedNativeIds) continue
            nativePlugins.add(p)
            externalIds.add(p.metadata.id)
            loadedNativeIds.add(p.metadata.id)
            runCatching { p.onLoad(host) }
                .onFailure { Log.w(TAG, "onLoad failed: ${p.metadata.id}", it) }
            changed = true
        }
        if (changed) notifyPluginsChanged()
    }

    /* ---------------- 安装 / 卸载 ---------------- */

    /** 从 .pbp（zip）插件包安装，返回 manifest 或失败原因 */
    fun installFromZip(uri: Uri): Result<PluginManifest> = runCatching {
        val tmp = File(context.cacheDir, "plugin_import")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = safeResolve(tmp, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: error("无法读取所选文件")

        // manifest.json 允许在 zip 根目录，或唯一一级子目录（常见于「右键压缩文件夹」）
        val root = manifestRootOf(tmp)
        val manifest = PluginManifest.fromJson(
            JSONObject(File(root, "manifest.json").readText()),
            root.absolutePath,
        )
        require(manifest.type == PluginType.JS) { "仅支持导入 JS 脚本插件（type=js）" }
        // id 会成为磁盘目录名，必须白名单校验，否则 ../ 之类的 id 可把文件写到 filesDir 之外
        require(ID_PATTERN.matches(manifest.id)) {
            "非法的插件 id：${manifest.id}（仅允许字母、数字、点、下划线、连字符，且不能以符号开头）"
        }
        require(manifest.id !in builtinIds()) { "插件 id 与内置插件冲突：${manifest.id}" }
        val entryName = manifest.entry ?: "user.js"
        require(File(root, entryName).exists()) { "入口文件 $entryName 不存在" }
        manifest.requires.forEach { req ->
            require(File(root, req).exists()) { "依赖文件缺失: $req" }
        }
        manifest.resources.values.forEach { res ->
            require(File(root, res).exists()) { "资源文件缺失: $res" }
        }

        val dest = safeResolve(pluginRoot(), manifest.id)
        if (dest.exists()) dest.deleteRecursively()
        root.copyRecursively(dest, overwrite = true)
        tmp.deleteRecursively()

        reload()
        notifyPluginsChanged()
        manifest
    }.onFailure { Log.w(TAG, "install failed", it) }

    /** 定位插件包根目录：根目录优先，其次唯一的子目录 */
    private fun manifestRootOf(dir: File): File {
        if (File(dir, "manifest.json").exists()) return dir
        val subDirs = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (subDirs.size == 1 && File(subDirs[0], "manifest.json").exists()) return subDirs[0]
        error("插件包中缺少 manifest.json")
    }

    private fun safeResolve(base: File, name: String): File {
        val basePath = base.canonicalFile
        val f = File(basePath, name).canonicalFile
        // 用「父路径 + 分隔符」比较，避免 plugins_evil 命中 plugins 的前缀
        require(f.path == basePath.path || f.path.startsWith(basePath.path + File.separator)) {
            "非法的文件路径: $name"
        }
        return f
    }

    /** 内置（assets）插件 id，用于阻止导入时同名覆盖 */
    private fun builtinIds(): Set<String> = runCatching {
        context.assets.list(PLUGIN_ROOT)?.mapNotNull { name ->
            runCatching { readManifestFromAssets("$PLUGIN_ROOT/$name").id }.getOrNull()
        }.orEmpty().toSet()
    }.getOrElse { emptySet() }

    /** 卸载用户安装的 JS 插件（内置插件不可卸载） */
    fun uninstall(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        val dir = safeResolve(pluginRoot(), id)
        val ok = dir.exists() && dir.deleteRecursively()
        if (ok) {
            prefs.edit().remove(id).apply()
            reload()
            notifyPluginsChanged()
        }
        return ok
    }

    /* ---------------- 启用 / 禁用 ---------------- */

    fun isEnabled(id: String, defaultEnabled: Boolean = true): Boolean =
        prefs.getBoolean(id, defaultEnabled)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(id, enabled).apply()
        notifyPluginsChanged()
    }

    fun toggle(id: String, defaultEnabled: Boolean = true): Boolean {
        val next = !isEnabled(id, defaultEnabled)
        setEnabled(id, next)
        return next
    }

    /* ---------------- 查询 ---------------- */

    fun allPlugins(): List<PluginInfo> {
        val out = mutableListOf<PluginInfo>()
        jsPlugins.forEach { m ->
            out.add(PluginInfo(manifest = m, enabled = isEnabled(m.id, m.defaultEnabled)))
        }
        nativePlugins.forEach { p ->
            val external = p.metadata.id in externalIds
            val manifest = PluginManifest.fromMetadata(
                id = p.metadata.id,
                name = p.metadata.name,
                version = p.metadata.version,
                author = p.metadata.author,
                description = p.metadata.description,
                permissions = p.metadata.permissions,
                builtin = p.metadata.builtin,
                external = external,
            )
            out.add(PluginInfo(manifest = manifest, enabled = isEnabled(p.metadata.id), nativePlugin = p, external = external))
        }
        return out
    }

    fun plugin(id: String): PluginInfo? = allPlugins().find { it.id == id }

    fun enabledNativePlugins(): List<NativePlugin> =
        nativePlugins.filter { isEnabled(it.metadata.id) }

    /** 广告拦截插件实例（只取启用的）；供 WebView 注入元素隐藏样式 */
    fun enabledAdBlocker(): AdBlockPlugin? {
        if (!isEnabled(ADBLOCK_PLUGIN_ID)) return null
        return nativePlugins.firstOrNull { it is AdBlockPlugin } as? AdBlockPlugin
    }

    /* ---------------- JS 脚本组装与注入 ---------------- */

    /** require 库会泄漏到页面的全局变量（加载后捕获给用户脚本、再还原页面原值） */
    private val LEAKED_GLOBALS = listOf("jQuery", "$", "Swal", "swal", "md5")

    /**
     * 查询指定 JS 插件声明的权限集合（grant）。
     * 供桥接层校验 http / clipboard / notify / tabs 等受限能力调用。
     */
    @Synchronized
    fun grantsOf(pluginId: String): Set<String> =
        jsPlugins.firstOrNull { it.id == pluginId }?.permissions?.toSet() ?: emptySet()

    /**
     * 插件对指定 URL 是否「应当生效」：已启用 + JS 类型 + @match 命中。
     * JS Bridge 用它做来源校验——页面里的 __plugin 是页面可写的，
     * 只认「当前 URL 确实命中该插件」的调用。
     */
    @Synchronized
    fun isPluginActiveFor(pluginId: String, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return jsPlugins.any {
            it.id == pluginId && it.type == PluginType.JS &&
                isEnabled(it.id, it.defaultEnabled) &&
                PluginManifest.matchesUrl(it.matches, url)
        }
    }

    /**
     * 生成注入到指定页面的脚本分块（按顺序逐个 evaluateJavascript）：
     *  [0] API 垫片（bridge，仅一次）
     *  之后每个命中的插件一块：依赖库 + 资源/元数据预注入 + 用户脚本。
     * earlyOnly = true 时仅取声明 runAt=document_start 的脚本（页面开始加载时注入）；
     * 为 false 时取其余脚本（页面加载完成后注入）。
     */
    fun jsScriptFor(url: String, earlyOnly: Boolean = false): List<String> {
        val matched = jsPlugins.filter {
            isEnabled(it.id, it.defaultEnabled) && PluginManifest.matchesUrl(it.matches, url)
        }.filter { (it.runAt == "document_start") == earlyOnly }
        if (matched.isEmpty()) return emptyList()

        val chunks = mutableListOf(bridgeScript())
        for (m in matched) {
            chunks.add(pluginChunk(m))
        }
        return chunks
    }

    /** 页面开始加载时注入的脚本（runAt=document_start，尽力而为） */
    fun earlyScriptFor(url: String): List<String> = jsScriptFor(url, earlyOnly = true)

    /**
     * 单个插件的完整注入块。结构：
     *  1. 备份 require 库会泄漏的全局变量；
     *  2. 执行依赖库（manifest.requires，等效 @require）；
     *  3. 捕获依赖库暴露的全局，还原页面原值（避免污染宿主页面，等效 Tampermonkey 沙箱）；
     *  4. 预注入命名资源（GM_getResourceText 同步读取）与脚本元数据（GM_info）；
     *  5. 以参数方式调用用户脚本（new Function），脚本内的 $/jQuery/Swal/md5 解析到依赖库，
     *     且脚本顶层声明不会泄漏到页面（等效沙箱隔离）。
     */
    private fun pluginChunk(m: PluginManifest): String {
        val sb = StringBuilder()
        sb.append("\n;(function(){try{window.PB&&window.PB.setPluginId('")
        sb.append(m.id.replace("'", "\\'"))
        sb.append("');\n")
        sb.append("var __pbOld={};")
        sb.append(LEAKED_GLOBALS.joinToString(",", "[", "]") { "'$it'" })
        sb.append(".forEach(function(k){__pbOld[k]=window[k];});\n")
        for (req in m.requires) {
            val code = readPluginFile(m, req) ?: continue
            sb.append("/* require: ").append(req).append(" */\n")
            sb.append(code).append("\n")
        }
        sb.append("var jQuery=window.jQuery,\$=window.\$,Swal=window.Swal,md5=window.md5;")
        sb.append(LEAKED_GLOBALS.joinToString(",", "[", "]") { "'$it'" })
        sb.append(".forEach(function(k){window[k]=__pbOld[k];});\n")
        sb.append(resourcePrelude(m))
        sb.append("new Function('jQuery','$','Swal','md5',")
        sb.append(jsStringLiteral(readScript(m)))
        sb.append(").call(window,jQuery,$,Swal,md5);\n")
        sb.append("}catch(e){console.error('[PB plugin ")
        sb.append(m.id)
        sb.append("]',e);}})();\n")
        return sb.toString()
    }

    /** 资源与元数据预注入：GM_getResourceText 同步读取 __PB_RESOURCES__，GM_info.script 读 __PB_META__ */
    private fun resourcePrelude(m: PluginManifest): String {
        if (m.resources.isEmpty()) return ""
        val sb = StringBuilder("window.__PB_RESOURCES__={")
        m.resources.entries.forEachIndexed { i, (name, path) ->
            val text = readPluginFile(m, path)
            if (i > 0) sb.append(",")
            sb.append(jsStringLiteral(name)).append(":")
            sb.append(if (text != null) jsStringLiteral(text) else "null")
        }
        sb.append("};\n")
        sb.append("window.__PB_META__={name:").append(jsStringLiteral(m.name))
        sb.append(",version:").append(jsStringLiteral(m.version))
        sb.append(",author:").append(jsStringLiteral(m.author))
        sb.append(",icon:").append(jsStringLiteral("")).append("};\n")
        sb.append("if(window.GM_info){window.GM_info.script=window.__PB_META__;}\n")
        return sb.toString()
    }

    /** 读取插件包内文件（支持 assets 内置插件与 filesDir 已安装插件） */
    private fun readPluginFile(m: PluginManifest, relativePath: String): String? = try {
        if (m.sourceDir.startsWith("assets:")) {
            val assetPath = m.sourceDir.removePrefix("assets:").trimEnd('/') + "/" + relativePath
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } else {
            val root = File(m.sourceDir).canonicalFile
            val f = File(root, relativePath).canonicalFile
            // 防路径穿越：按「父路径 + 分隔符」比较，避免 plugins_evil 命中 plugins 前缀
            if (!f.path.startsWith(root.path + File.separator)) null
            else if (f.exists()) f.readText() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "read plugin file failed: ${m.id}/$relativePath", e)
        null
    }

    /** 把任意文本编码为 JS 字符串字面量（JSON 编码，并转义 JS 的行分隔符） */
    private fun jsStringLiteral(text: String): String =
        org.json.JSONObject.quote(text)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    private fun bridgeScript(): String =
        bridgeScriptCache ?: context.assets.open("js/pb-bridge.js").bufferedReader()
            .use { it.readText() }
            .also { bridgeScriptCache = it }

    private fun readScript(m: PluginManifest): String {
        scriptCache[m.id]?.let { return it }
        val src = if (m.sourceDir.startsWith("assets:")) {
            val assetPath = m.sourceDir.removePrefix("assets:") + "/" + (m.entry ?: "user.js")
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } else {
            File(m.sourceDir, m.entry ?: "user.js").readText()
        }
        scriptCache[m.id] = src
        return src
    }

    /* ---------------- 工具 ---------------- */

    fun pluginRoot(): File = File(context.filesDir, PLUGIN_ROOT).apply { mkdirs() }

    private fun notifyPluginsChanged() {
        onPluginsChanged?.invoke()
    }
}
