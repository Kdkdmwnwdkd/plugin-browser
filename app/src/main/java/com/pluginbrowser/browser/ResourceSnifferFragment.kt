package com.pluginbrowser.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pluginbrowser.R
import com.pluginbrowser.download.DownloadTracker

/**
 * 资源嗅探面板：列出当前浏览过程中捕获到的媒体文件，点右侧按钮直接下载。
 * m3u8 这类流媒体 DownloadManager 下不了，改为复制地址。
 */
class ResourceSnifferFragment : BottomSheetDialogFragment() {

    private lateinit var adapter: ResourceAdapter

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_resource_sniffer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.sniffer_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ResourceAdapter { item -> onAction(item) }
        recycler.adapter = adapter

        val host = ResourceSniffer.currentPageUrl
            ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            .orEmpty()
        view.findViewById<TextView>(R.id.sniffer_host).text = if (host.isBlank()) {
            getString(R.string.sniffer_scope_all)
        } else {
            getString(R.string.sniffer_scope, host)
        }

        view.findViewById<TextView>(R.id.sniffer_clear).setOnClickListener {
            ResourceSniffer.clear()
            refresh()
        }

        refresh()
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
        }
    }

    private fun refresh() {
        val items = ResourceSniffer.snapshot()
        adapter.submit(items)
        val root = view ?: return
        root.findViewById<TextView>(R.id.sniffer_empty)?.isVisible = items.isEmpty()
        root.findViewById<RecyclerView>(R.id.sniffer_recycler)?.isVisible = items.isNotEmpty()
    }

    private fun onAction(item: ResourceSniffer.Item) {
        if (item.kind == ResourceSniffer.Kind.STREAM) {
            // m3u8 / mpd 是播放列表，DownloadManager 存下来也没法看
            copyLink(item.url)
            Toast.makeText(requireContext(), R.string.sniffer_stream_copied, Toast.LENGTH_LONG).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sniffer_download_title)
            .setMessage(fileNameOf(item))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sniffer_download) { _, _ ->
                val ua = WebSettings.getDefaultUserAgent(requireContext())
                val started = DownloadTracker.get(requireContext()).enqueue(
                    item.url,
                    ua,
                    null,
                    ResourceSniffer.mimeOf(item),
                )
                Toast.makeText(
                    requireContext(),
                    if (started) R.string.sniffer_download_started else R.string.sniffer_download_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    private fun fileNameOf(item: ResourceSniffer.Item): String =
        item.url.substringBefore('?').substringAfterLast('/').ifBlank { item.url }

    private fun copyLink(url: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PB resource", url))
    }

    private class ResourceAdapter(
        private val onAction: (ResourceSniffer.Item) -> Unit,
    ) : RecyclerView.Adapter<ResourceAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val kind: TextView = view.findViewById(R.id.res_kind)
            val name: TextView = view.findViewById(R.id.res_name)
            val meta: TextView = view.findViewById(R.id.res_meta)
            val action: ImageButton = view.findViewById(R.id.res_action)
        }

        private val items = mutableListOf<ResourceSniffer.Item>()

        fun submit(list: List<ResourceSniffer.Item>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_resource, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context

            holder.kind.text = ctx.getString(
                when (item.kind) {
                    ResourceSniffer.Kind.VIDEO -> R.string.sniffer_kind_video
                    ResourceSniffer.Kind.AUDIO -> R.string.sniffer_kind_audio
                    ResourceSniffer.Kind.STREAM -> R.string.sniffer_kind_stream
                    ResourceSniffer.Kind.IMAGE -> R.string.sniffer_kind_image
                    ResourceSniffer.Kind.FILE -> R.string.sniffer_kind_file
                },
            )
            holder.name.text = item.url.substringBefore('?').substringAfterLast('/').ifBlank { item.url }
            holder.meta.text = if (item.kind == ResourceSniffer.Kind.STREAM) {
                ctx.getString(R.string.sniffer_stream_meta, item.host)
            } else {
                item.host
            }

            holder.action.setImageResource(
                if (item.kind == ResourceSniffer.Kind.STREAM) R.drawable.ic_link else R.drawable.ic_download,
            )
            holder.itemView.setOnClickListener { onAction(item) }
            holder.action.setOnClickListener { onAction(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
