package com.pluginbrowser.download

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pluginbrowser.R
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 下载管理列表 */
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {

    private val store by lazy { BrowserStore(requireContext()) }
    private val engine by lazy { DownloadEngine.get(requireContext()) }
    private lateinit var adapter: DownloadAdapter
    private var items: List<DownloadItem> = emptyList()

    private var ticker: Job? = null

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
            onDelete = { removeOrCancel(it) },
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
        startTicker()
    }

    override fun onPause() {
        super.onPause()
        ticker?.cancel()
        ticker = null
    }

    private fun load() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { store.downloads() }
            adapter.submit(items)
            rebindRows()
            // 有没下完的就把引擎和服务叫起来（App 被杀过、或者服务被系统回收）
            if (items.any { it.status == DownloadItem.STATUS_RUNNING || it.status == DownloadItem.STATUS_PENDING }) {
                engine.resumeUnfinished()
                DownloadService.start(requireContext())
            }
        }
    }

    /* ---------------- 进度 ---------------- */

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    /** 进度直接读引擎内存，比每 500ms 查一次数据库靠谱得多 */
    private suspend fun tick() {
        val pending = items.filter {
            it.status == DownloadItem.STATUS_RUNNING || it.status == DownloadItem.STATUS_PENDING
        }
        if (pending.isEmpty()) return

        val snapshot = engine.snapshot()
        val views = HashMap<Long, DownloadAdapter.ViewProgress>(pending.size)
        var needReload = false

        for (item in pending) {
            val state = snapshot[item.id] ?: continue
            when (state.status) {
                DownloadItem.STATUS_DONE, DownloadItem.STATUS_FAILED -> needReload = true
                else -> views[item.id] = DownloadAdapter.ViewProgress(
                    percent = state.percent,
                    indeterminate = state.total <= 0,
                    downloaded = state.downloaded,
                    total = state.total,
                    speed = state.speed,
                )
            }
        }

        if (needReload) load() else syncRows(views)
    }

    /** 把进度写进已经渲染出来的行，避免每 500ms 整表重绘 */
    private fun syncRows(views: Map<Long, DownloadAdapter.ViewProgress>) {
        adapter.submitProgress(views)
        rebindRows()
    }

    private fun rebindRows() {
        val recycler = view?.findViewById<RecyclerView>(R.id.downloads_recycler) ?: return
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val holder = recycler.getChildViewHolder(child) as? DownloadAdapter.VH ?: continue
            adapter.bindProgress(holder, recycler.getChildAdapterPosition(child))
        }
    }

    /* ---------------- 操作 ---------------- */

    private fun removeOrCancel(item: DownloadItem) {
        val running = item.status == DownloadItem.STATUS_RUNNING ||
            item.status == DownloadItem.STATUS_PENDING
        if (!running) {
            lifecycleScope.launch {
                store.deleteDownload(item.id)
                load()
            }
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.downloads_cancel_title)
            .setMessage(item.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.downloads_cancel_action) { _, _ ->
                engine.cancel(item.id)
                lifecycleScope.launch {
                    store.deleteDownload(item.id)
                    load()
                    toast(getString(R.string.downloads_cancelled))
                }
            }
            .show()
    }

    private fun open(item: DownloadItem) {
        if (item.status == DownloadItem.STATUS_FAILED) {
            retry(item)
            return
        }
        if (item.status != DownloadItem.STATUS_DONE) return

        val uri = resolveUri(item)
        if (uri == null) {
            toast(getString(R.string.downloads_open_failed))
            return
        }
        launchView(uri, item.mime)
    }

    /** 新下载是 content://（MediaStore）或绝对路径，旧记录还在系统下载管理器里 */
    private fun resolveUri(item: DownloadItem): android.net.Uri? {
        if (item.dmId > 0) {
            val dmUri = runCatching {
                val dm = requireContext()
                    .getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.getUriForDownloadedFile(item.dmId)
            }.getOrNull()
            if (dmUri != null) return dmUri
        }
        if (item.path.startsWith("content://") || item.path.startsWith("file://")) {
            return runCatching { android.net.Uri.parse(item.path) }.getOrNull()
        }
        val file = File(item.path)
        if (!file.exists()) {
            // 文件被用户在文件管理器里删了
            return null
        }
        return runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                file,
            )
        }.getOrNull()
    }

    private fun retry(item: DownloadItem) {
        lifecycleScope.launch {
            store.updateDownloadStatus(item.id, DownloadItem.STATUS_PENDING)
            engine.retry(item.id, item)
            DownloadService.start(requireContext())
            load()
            toast(getString(R.string.download_started, item.name))
        }
    }

    private fun launchView(uri: android.net.Uri, mime: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
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

        data class ViewProgress(
            val percent: Int,
            val indeterminate: Boolean,
            val downloaded: Long,
            val total: Long,
            val speed: Long,
        )

        var onCountChanged: ((Int) -> Unit)? = null
        private val data = mutableListOf<DownloadItem>()
        private val progress = HashMap<Long, ViewProgress>()

        fun submit(list: List<DownloadItem>) {
            data.clear()
            data.addAll(list)
            progress.keys.retainAll(data.map { it.id }.toSet())
            notifyDataSetChanged()
            onCountChanged?.invoke(data.size)
        }

        fun submitProgress(next: Map<Long, ViewProgress>) {
            progress.clear()
            progress.putAll(next)
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.download_name)
            val meta: TextView = view.findViewById(R.id.download_meta)
            val status: TextView = view.findViewById(R.id.download_status)
            val progressBar: ProgressBar = view.findViewById(R.id.download_progress)
            val delete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position !in data.indices) return
            val item = data[position]
            holder.name.text = item.name
            holder.meta.text = item.url
            holder.itemView.setOnClickListener { onClick(item) }
            holder.delete.setOnClickListener { onDelete(item) }
            bindProgress(holder, position)
        }

        fun bindProgress(holder: VH, position: Int) {
            if (position !in data.indices) return
            val item = data[position]
            val ctx: Context = holder.itemView.context
            val active = item.status == DownloadItem.STATUS_RUNNING ||
                item.status == DownloadItem.STATUS_PENDING
            val p = progress[item.id]

            holder.progressBar.isVisible = active
            if (active) {
                // 还没查到时用不确定动画，避免显示一条 0% 的死进度
                holder.progressBar.isIndeterminate = p?.indeterminate ?: true
                holder.progressBar.progress = p?.percent ?: 0
            }

            holder.status.text = when (item.status) {
                DownloadItem.STATUS_DONE ->
                    ctx.getString(R.string.downloads_status_done) +
                        if (item.size > 0) " · ${formatBytes(item.size)}" else ""
                DownloadItem.STATUS_FAILED -> ctx.getString(R.string.downloads_tap_retry)
                DownloadItem.STATUS_RUNNING -> runningText(ctx, p, item)
                else -> ctx.getString(R.string.downloads_status_pending)
            }
        }

        private fun runningText(ctx: Context, p: ViewProgress?, item: DownloadItem): String {
            if (p == null) {
                // 引擎还没接手，先按数据库里的进度画
                return if (item.downloaded > 0) {
                    "${formatBytes(item.downloaded)}  ·  " + ctx.getString(R.string.downloads_status_running)
                } else {
                    ctx.getString(R.string.downloads_status_running)
                }
            }
            val sb = StringBuilder()
            if (p.indeterminate) {
                sb.append(formatBytes(p.downloaded))
            } else {
                sb.append(p.percent).append('%')
                sb.append(" · ").append(formatBytes(p.downloaded))
                sb.append(" / ").append(formatBytes(p.total))
            }
            if (p.speed > 0) sb.append(" · ").append(formatBytes(p.speed)).append("/s")
            return sb.toString()
        }

        override fun getItemCount(): Int = data.size
    }
}
