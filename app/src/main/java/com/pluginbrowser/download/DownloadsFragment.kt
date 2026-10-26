package com.pluginbrowser.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pluginbrowser.MainActivity
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.DownloadItem
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 下载管理列表 */
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private val store by lazy { BrowserStore(requireContext()) }
    private lateinit var adapter: DownloadAdapter
    private var items: List<DownloadItem> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_downloads)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear) {
                lifecycleScope.launch {
                    store.clearFinishedDownloads()
                    load()
                    toast(getString(R.string.archive_cleared))
                }
                true
            } else false
        }

        val recycler = view.findViewById<RecyclerView>(R.id.downloads_recycler)
        adapter = DownloadAdapter(
            onClick = { open(it) },
            onDelete = { item ->
                lifecycleScope.launch {
                    store.deleteDownload(item.id)
                    load()
                }
            },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<View>(R.id.empty_view).let { empty ->
            // 空视图随数据切换
            adapter.onCountChanged = { count -> empty.isVisible = count == 0 }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { store.downloads() }
            adapter.submit(items)
        }
    }

    private fun open(item: DownloadItem) {
        val file = File(item.path)
        if (!file.exists()) {
            toast(getString(R.string.downloads_open_failed))
            return
        }
        val uri = FileProvider.getUriForFile(
            requireContext(),
            requireContext().packageName + ".fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mime.ifBlank { "*/*" })
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.downloads_open_failed))
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private class DownloadAdapter(
        val onClick: (DownloadItem) -> Unit,
        val onDelete: (DownloadItem) -> Unit,
    ) : RecyclerView.Adapter<DownloadAdapter.VH>() {

        var onCountChanged: ((Int) -> Unit)? = null
        private val data = mutableListOf<DownloadItem>()

        fun submit(list: List<DownloadItem>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
            onCountChanged?.invoke(data.size)
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.download_name)
            val meta: TextView = view.findViewById(R.id.download_meta)
            val status: TextView = view.findViewById(R.id.download_status)
            val delete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            holder.meta.text = item.url
            holder.status.text = when (item.status) {
                DownloadItem.STATUS_DONE -> holder.itemView.context.getString(R.string.downloads_status_done) +
                    if (item.size > 0) " · ${sizeText(item.size)}" else ""
                DownloadItem.STATUS_FAILED -> holder.itemView.context.getString(R.string.downloads_status_failed)
                DownloadItem.STATUS_RUNNING -> holder.itemView.context.getString(R.string.downloads_status_running)
                else -> holder.itemView.context.getString(R.string.downloads_status_pending)
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.delete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = data.size

        private fun sizeText(size: Long): String = when {
            size >= 1024 * 1024 -> String.format("%.1f MB", size / 1024.0 / 1024.0)
            size >= 1024 -> String.format("%.0f KB", size / 1024.0)
            else -> "$size B"
        }
    }
}
