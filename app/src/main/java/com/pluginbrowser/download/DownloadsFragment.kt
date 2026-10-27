package com.pluginbrowser.download

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
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
    private val tracker by lazy { DownloadTracker.get(requireContext()) }
    private lateinit var adapter: DownloadAdapter
    private var items: List<DownloadItem> = emptyList()

    private var ticker: Job? = null
    private val lastSample = HashMap<Long, Sample>()

    private data class Sample(val bytes: Long, val at: Long)

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
        }
    }

    /* ---------------- 进度 ---------------- */

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(600)
            }
        }
    }

    /**
     * 系统 DownloadManager 里有完整进度（已下载字节 / 总量 / 状态），
     * 以前列表只显示一句「下载中」，用户根本看不出下到哪了。
     */
    private suspend fun tick() {
        val pending = items.filter {
            it.status == DownloadItem.STATUS_RUNNING || it.status == DownloadItem.STATUS_PENDING
        }
        if (pending.isEmpty()) {
            lastSample.clear()
            syncRows(emptyMap())
            // 刚入队的任务可能还没写进数据库，等它一下，别让列表看起来什么都没发生
            if (tracker.hasActive()) load()
            return
        }

        val ids = pending.mapNotNull { it.dmId.takeIf { id -> id > 0 } }
        if (ids.isEmpty()) return
        val snapshot = withContext(Dispatchers.IO) { tracker.progressOf(ids) }
        val now = SystemClock.elapsedRealtime()
        val views = HashMap<Long, DownloadAdapter.ViewProgress>(ids.size)
        var needReload = false

        for (item in pending) {
            val p = snapshot[item.dmId]
            if (p == null) {
                // 系统里已经没有这条任务：可能被通知栏取消或清理，下一轮列表刷新会跟上
                continue
            }
            when (p.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    withContext(Dispatchers.IO) {
                        store.updateDownloadByDmId(item.dmId, DownloadItem.STATUS_DONE, p.total)
                    }
                    tracker.markFinished(item.dmId)
                    lastSample.remove(item.dmId)
                    needReload = true
                }
                DownloadManager.STATUS_FAILED -> {
                    withContext(Dispatchers.IO) {
                        store.updateDownloadByDmId(item.dmId, DownloadItem.STATUS_FAILED, p.total)
                    }
                    tracker.markFinished(item.dmId)
                    lastSample.remove(item.dmId)
                    needReload = true
                }
                else -> {
                    val prev = lastSample[item.dmId]
                    lastSample[item.dmId] = Sample(p.downloaded, now)
                    val speed = if (prev != null && now > prev.at && p.downloaded >= prev.bytes) {
                        (p.downloaded - prev.bytes) * 1000 / (now - prev.at)
                    } else {
                        0L
                    }
                    views[item.dmId] = DownloadAdapter.ViewProgress(
                        percent = p.percent,
                        indeterminate = p.indeterminate,
                        downloaded = p.downloaded,
                        total = p.total,
                        speed = speed,
                    )
                }
            }
        }

        if (needReload) {
            lastSample.clear()
            load()
        } else {
            syncRows(views)
        }
    }

    /** 把进度写进已经渲染出来的行，避免每 600ms 整表重绘 */
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
                tracker.cancel(item.dmId)
                lifecycleScope.launch {
                    store.deleteDownload(item.id)
                    load()
                    toast(getString(R.string.downloads_cancelled))
                }
            }
            .show()
    }

    private fun open(item: DownloadItem) {
        // 文件已落在系统下载目录，API 29+ 应用不能直接读该路径，
        // 优先用 DownloadManager 授权过的 content:// URI
        val dmUri = runCatching {
            val dm = requireContext()
                .getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.getUriForDownloadedFile(item.dmId)
        }.getOrNull()
        if (dmUri != null) {
            launchView(dmUri, item.mime)
            return
        }

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
        launchView(uri, item.mime)
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
            progress.keys.retainAll(data.map { it.dmId }.toSet())
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
            val p = progress[item.dmId]

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
                DownloadItem.STATUS_FAILED -> ctx.getString(R.string.downloads_status_failed)
                DownloadItem.STATUS_RUNNING -> runningText(ctx, p)
                else -> ctx.getString(R.string.downloads_status_pending)
            }
        }

        private fun runningText(ctx: Context, p: ViewProgress?): String {
            if (p == null) return ctx.getString(R.string.downloads_status_running)
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
