package com.pluginbrowser.download

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.util.UrlUtils
import kotlinx.coroutines.launch

/**
 * 点击下载链接时的确认框。
 *
 * 以前是点一下直接进 DownloadManager，用户既没机会说「不下」，也不知道文件多大。
 * 这里先把文件名、来源站点、大小摆出来，确认后再入队。
 */
class DownloadPrompt(
    private val fragment: Fragment,
    private val settings: SettingsRepository,
) {

    private var dialog: AlertDialog? = null

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun show(
        url: String,
        fileName: String,
        userAgent: String,
        knownSize: Long,
        mime: String?,
        referer: String?,
        onStart: (name: String, size: Long) -> Unit,
    ) {
        val ctx = fragment.context ?: return
        val host = UrlUtils.hostOf(url)

        // 连点两下会触发两次回调，别叠出两个一模一样的确认框
        if (dialog?.isShowing == true) return

        // 设置里关掉了，或这个站点勾过「不再询问」，直接下
        if (!settings.askBeforeDownload || host in settings.downloadTrustedHosts()) {
            onStart(fileName, knownSize)
            return
        }

        val view = fragment.layoutInflater.inflate(R.layout.dialog_download_confirm, null)
        val nameView = view.findViewById<TextView>(R.id.download_file_name)
        val metaView = view.findViewById<TextView>(R.id.download_file_meta)
        val pathView = view.findViewById<TextView>(R.id.download_file_path)
        val trustView = view.findViewById<MaterialCheckBox>(R.id.download_trust)

        var finalName = fileName
        var finalSize = knownSize

        fun renderMeta(size: Long, type: String?) {
            metaView.text = buildString {
                append(host)
                append(" · ")
                append(if (size > 0) formatBytes(size) else "大小读取中…")
                if (!type.isNullOrBlank()) {
                    append(" · ")
                    append(type)
                }
            }
        }

        nameView.text = finalName
        renderMeta(finalSize, typeLabel(finalName, mime))
        pathView.text = "保存到：下载/PluginBrowser/"

        val d = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.download_confirm_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_confirm_start, null)
            .create()
        dialog = d

        d.setOnShowListener {
            // 不用 setPositiveButton 的自动关闭：要在关闭前把勾选和探测结果一起收走
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (trustView.isChecked) settings.trustDownloadHost(host)
                d.dismiss()
                onStart(finalName, finalSize)
            }
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        d.show()

        // 大小未知时补一次探测，结果直接刷到对话框上，不耽误用户先看文件名
        if (knownSize <= 0) {
            fragment.lifecycleScope.launch {
                // GitHub 的直链走加速通道，配了代理/DoH 时探测也能快一点
                val channel = runCatching {
                    com.pluginbrowser.PluginBrowserApp.from(ctx).gitHubChannel.clientFor(url)
                }.getOrNull()
                val info = DownloadProbe.probe(url, userAgent, referer, channel)
                if (dialog !== d || !d.isShowing) return@launch
                if (info.size > 0) finalSize = info.size
                info.fileName?.takeIf { it.isNotBlank() }?.let { finalName = it }
                nameView.text = finalName
                renderMeta(finalSize, typeLabel(finalName, info.mime ?: mime))
            }
        }
    }
}
