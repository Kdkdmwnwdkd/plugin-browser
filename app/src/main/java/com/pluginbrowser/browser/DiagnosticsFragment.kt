package com.pluginbrowser.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.net.NetTrace
import com.pluginbrowser.util.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络诊断面板。
 *
 * 目的很单纯：让「慢」这件事可测量。以前只有一个「测试连接」按钮给出一个总耗时，
 * 看完还是不知道慢在哪一层；这里把每个 GitHub 请求的决策摊开——
 * 走通道还是交回系统、耗时多少、为什么没接管。
 *
 * 不改变任何行为，纯观测。
 */
class DiagnosticsFragment : BottomSheetDialogFragment() {

    private var probeRunning = false

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_diagnostics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.diag_copy).setOnClickListener { copyAll() }
        view.findViewById<MaterialButton>(R.id.diag_probe).setOnClickListener { probe() }
        view.findViewById<MaterialButton>(R.id.diag_clear).setOnClickListener {
            NetTrace.clear()
            render()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.88f).toInt()
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    /* ---------------- 渲染 ---------------- */

    private fun render() {
        val root = view?.findViewById<LinearLayout>(R.id.diag_content) ?: return
        root.removeAllViews()

        val ctx = context ?: return
        val settings = SettingsRepository(ctx)
        val channel = PluginBrowserApp.from(requireContext()).gitHubChannel

        root.addView(sectionTitle(getString(R.string.diag_section_route)))
        val proxy = settings.proxyUrl.trim()
        row(root, getString(R.string.diag_route_warm), if (channel.isWarm()) {
            getString(R.string.diag_warm_ok)
        } else {
            getString(R.string.diag_warm_unknown)
        })
        row(
            root,
            getString(R.string.diag_route_proxy),
            if (proxy.isEmpty()) getString(R.string.diag_proxy_none) else proxy,
        )
        row(
            root,
            getString(R.string.diag_route_doh),
            if (settings.dohEnabled) getString(R.string.diag_on) else getString(R.string.diag_off),
        )
        row(
            root,
            getString(R.string.diag_route_accel),
            if (settings.githubAccel) getString(R.string.diag_on) else getString(R.string.diag_off),
        )
        row(
            root,
            getString(R.string.diag_route_pages),
            if (settings.githubCatchPages) getString(R.string.diag_on) else getString(R.string.diag_off),
        )
        row(root, getString(R.string.diag_route_cache), cacheText(ctx))

        val (hit, total) = NetTrace.channelStats(50)
        row(
            root,
            getString(R.string.diag_route_hit),
            if (total == 0) {
                getString(R.string.diag_hit_none)
            } else {
                getString(R.string.diag_hit_value, hit, total)
            },
        )

        root.addView(sectionTitle(getString(R.string.diag_section_requests)))
        val entries = NetTrace.snapshot().takeLast(50).reversed()
        if (entries.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = getString(R.string.diag_empty)
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            }
            root.addView(empty)
        } else {
            entries.forEach { e -> root.addView(requestRow(ctx, e)) }
        }

        val hint = TextView(ctx).apply {
            text = getString(R.string.diag_hint)
            setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(hint)

        view?.findViewById<TextView>(R.id.diag_subtitle)?.text =
            getString(R.string.diag_subtitle, entries.size)
    }

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.06f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun row(parent: LinearLayout, label: String, value: String) {
        val ctx = requireContext()
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        line.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                width = dp(78)
            },
        )
        line.addView(
            TextView(ctx).apply {
                text = value
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        parent.addView(line)
    }

    private fun requestRow(ctx: Context, e: NetTrace.Entry): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val viaColor = when (e.via) {
            NetTrace.VIA_CHANNEL -> colorOf(com.google.android.material.R.attr.colorPrimary)
            NetTrace.VIA_PLUGIN -> colorOf(com.google.android.material.R.attr.colorSecondary)
            else -> colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        top.addView(
            TextView(ctx).apply {
                text = e.via
                setTextColor(viaColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                width = dp(32)
            },
        )
        top.addView(
            TextView(ctx).apply {
                text = if (e.main) "${e.host}${e.path}  ·主文档" else "${e.host}${e.path}"
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        top.addView(
            TextView(ctx).apply {
                text = when {
                    e.ms >= 0 -> "${e.ms}ms"
                    else -> "—"
                }
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(8), 0, 0, 0)
            },
        )
        wrap.addView(top)

        val detail = buildString {
            append(timeOf(e.time))
            if (e.code > 0) append("  ").append(e.code)
            if (e.note.isNotBlank()) append("  ").append(e.note)
        }
        wrap.addView(
            TextView(ctx).apply {
                text = detail
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(32), dp(2), 0, 0)
            },
        )

        val divider = View(ctx).apply {
            setBackgroundColor(colorOf(com.google.android.material.R.attr.colorOutlineVariant))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(wrap)
        }
        box.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return box
    }

    /* ---------------- 动作 ---------------- */

    private fun probe() {
        if (probeRunning) return
        val btn = view?.findViewById<MaterialButton>(R.id.diag_probe) ?: return
        probeRunning = true
        btn.isEnabled = false
        val old = btn.text
        btn.text = getString(R.string.diag_probing)

        lifecycleScope.launch {
            val channel = PluginBrowserApp.from(requireContext()).gitHubChannel
            val result = withContext(Dispatchers.IO) {
                runCatching { channel.probe() }
                    .getOrElse { "测试失败：${it.javaClass.simpleName}" }
            }
            probeRunning = false
            if (!isAdded) return@launch
            btn.isEnabled = true
            btn.text = old
            Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show()
            EventLog.log("DIAG", "probe: " + result.replace('\n', ' ').take(160))
            render()
        }
    }

    private fun copyAll() {
        val text = buildString {
            appendLine("== 线路 ==")
            NetTrace.snapshot().takeLast(50).forEach { e ->
                append(timeOf(e.time)).append(' ')
                append(e.via).append(' ')
                append(e.host).append(e.path)
                if (e.ms >= 0) append(" ${e.ms}ms")
                if (e.code > 0) append(" ${e.code}")
                if (e.note.isNotBlank()) append(" | ").append(e.note)
                appendLine()
            }
            appendLine("== 事件 ==")
            EventLog.recent(40).forEach { appendLine(it) }
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
        Toast.makeText(requireContext(), R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }

    /* ---------------- 工具 ---------------- */

    private fun cacheText(ctx: Context): String {
        val dir = File(ctx.cacheDir, "gh-http")
        val bytes = cacheSize(dir)
        if (bytes <= 0) return getString(R.string.diag_cache_empty)
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1) {
            "${bytes / 1024} KB"
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    /** OkHttp 缓存是两层哈希目录，手写两层遍历，比 walkTopDown 快得多 */
    private fun cacheSize(dir: File): Long {
        if (!dir.isDirectory) return 0
        var sum = 0L
        dir.listFiles()?.forEach { level1 ->
            if (level1.isDirectory) {
                level1.listFiles()?.forEach { f -> if (f.isFile) sum += f.length() }
            } else {
                sum += level1.length()
            }
        }
        return sum
    }

    private fun timeOf(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    private fun colorOf(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        val color = if (tv.resourceId != 0) {
            ContextCompat.getColor(requireContext(), tv.resourceId)
        } else {
            tv.data
        }
        // 主题没定义这个 attr 时 resolve 出来是 0（全透明），字会直接看不见
        return if (color == 0) android.graphics.Color.GRAY else color
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
