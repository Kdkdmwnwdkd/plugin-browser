package com.pluginbrowser.plugin.store

import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.pluginbrowser.R
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.PluginManifest
import com.pluginbrowser.plugin.PluginType
import com.pluginbrowser.plugin.UserScriptMeta
import com.pluginbrowser.util.NetFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网页一键安装脚本：.user.js（油猴脚本）与 .pbp（插件包）。
 *
 * 流程刻意拆成两段：先拉内容读出元数据，弹确认框（名称/版本/作者/生效范围/来源），
 * 用户点头才落盘。装错脚本比下错文件麻烦——确认框这道闸不能省。
 *
 * 浏览器里点开直链、插件管理里粘贴链接，最终都汇到这里。
 */
object ScriptInstaller {

    fun installFromUrl(fragment: Fragment, pluginManager: PluginManager, url: String) {
        val isPbp = url.substringBefore('?').substringAfterLast('/')
            .lowercase().endsWith(".pbp")
        toast(fragment, fragment.getString(R.string.plugin_installing))
        fragment.lifecycleScope.launch {
            val fetched = runCatching {
                withContext(Dispatchers.IO) {
                    if (isPbp) {
                        val bytes = NetFetcher.bytes(url)
                            ?: error(fragment.getString(R.string.plugin_fetch_failed))
                        FetchedPbp(pluginManager.previewFromBytes(bytes).getOrThrow(), bytes)
                    } else {
                        val source = NetFetcher.text(url)
                            ?: error(fragment.getString(R.string.plugin_fetch_failed))
                        val meta = UserScriptMeta.parse(source)
                            ?: error(fragment.getString(R.string.plugin_not_userscript))
                        FetchedUserscript(source, meta)
                    }
                }
            }
            if (!fragment.isAdded) return@launch
            fetched.onSuccess { showInstallDialog(fragment, pluginManager, it, url) }
                .onFailure {
                    toast(fragment, fragment.getString(R.string.plugin_install_failed, it.message ?: ""))
                }
        }
    }

    /** 插件管理里的「从链接安装」入口：输入框拿链接，剩下与直链安装同一套 */
    fun showUrlDialog(fragment: Fragment, pluginManager: PluginManager) {
        val ctx = fragment.context ?: return
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_install_url, null)
        val input = view.findViewById<TextInputEditText>(R.id.input_install_url)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.plugins_install_url)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.plugin_install_confirm) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.startsWith("http")) installFromUrl(fragment, pluginManager, url)
                else toast(fragment, ctx.getString(R.string.plugin_fetch_failed))
            }
            .show()
    }

    private fun showInstallDialog(
        fragment: Fragment,
        pluginManager: PluginManager,
        payload: Fetched,
        url: String,
    ) {
        val ctx = fragment.context ?: return
        val manifest = when (payload) {
            is FetchedPbp -> payload.manifest
            is FetchedUserscript -> previewManifest(payload.meta)
        }
        val doInstall: () -> PluginManifest = when (payload) {
            is FetchedPbp -> { { pluginManager.installFromBytes(payload.bytes).getOrThrow() } }
            is FetchedUserscript -> { { pluginManager.installFromUserScript(payload.source).getOrThrow() } }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.plugin_install_title)
            .setMessage(installSummary(fragment, manifest, url))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.plugin_install_confirm) { _, _ ->
                fragment.lifecycleScope.launch {
                    val done = runCatching {
                        withContext(Dispatchers.IO) { doInstall() }
                    }
                    if (!fragment.isAdded) return@launch
                    done.onSuccess { m ->
                        toast(fragment, ctx.getString(R.string.plugin_install_success, m.name))
                    }.onFailure {
                        toast(fragment, ctx.getString(R.string.plugin_install_failed, it.message ?: ""))
                    }
                }
            }
            .show()
    }

    private fun installSummary(fragment: Fragment, manifest: PluginManifest, url: String): String =
        buildString {
            appendLine("名称：${manifest.name}")
            appendLine("版本：${manifest.version}")
            if (manifest.author.isNotBlank()) appendLine("作者：${manifest.author}")
            val desc = manifest.description
            if (desc.isNotBlank() && desc != "暂无描述") appendLine("简介：${desc.take(120)}")
            val scope = if (manifest.matches.contains("*://*/*")) {
                "全部网站"
            } else {
                "${manifest.matches.size} 个网址"
            }
            appendLine("生效范围：$scope")
            appendLine("来源：$url")
            append(fragment.getString(R.string.plugin_install_note))
        }

    /** 元数据合成展示用 manifest（尚不落盘，与真实安装产物保持同构） */
    private fun previewManifest(meta: UserScriptMeta.Meta): PluginManifest {
        val matches = (meta.matches + meta.includes.mapNotNull { UserScriptMeta.includeToMatch(it) })
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("*://*/*") }
        return PluginManifest(
            id = UserScriptMeta.idFor(meta),
            name = meta.name.ifBlank { "未命名脚本" },
            version = meta.version ?: "-",
            author = meta.author ?: "",
            description = meta.description ?: "",
            type = PluginType.JS,
            entry = "user.js",
            matches = matches,
            runAt = if (meta.runAt?.lowercase() == "document-start") "document_start" else "document_end",
            permissions = meta.grants,
            builtin = false,
            defaultEnabled = true,
            sourceDir = "pending",
        )
    }

    private sealed class Fetched

    private class FetchedPbp(val manifest: PluginManifest, val bytes: ByteArray) : Fetched()

    private class FetchedUserscript(val source: String, val meta: UserScriptMeta.Meta) : Fetched()

    private fun toast(fragment: Fragment, message: String) {
        fragment.context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}
