package com.pluginbrowser.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.pluginbrowser.MainActivity
import com.pluginbrowser.R
import com.pluginbrowser.data.ArchiveItem
import com.pluginbrowser.data.BookmarkImporter
import com.pluginbrowser.data.BrowserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 书签 / 历史记录列表（mode 参数区分） */
class BookmarksFragment : Fragment(R.layout.fragment_archive) {

    companion object {
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_HISTORY = "history"
        fun newInstance(mode: String) = BookmarksFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
        private const val ARG_MODE = "mode"
    }

    private val mode: String get() = arguments?.getString(ARG_MODE) ?: MODE_BOOKMARKS
    private val store by lazy { BrowserStore(requireContext()) }
    private lateinit var adapter: ArchiveAdapter

    /** 导入别人导出的书签 HTML；只处理书签页，历史页不显示这个入口 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { BookmarkImporter.parse(it.readText()) }
                }.getOrNull().orEmpty()
            }
            if (entries.isEmpty()) {
                Toast.makeText(requireContext(), "这个文件里没有找到书签", Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                entries.forEach { store.addBookmark(it.title, it.url) }
            }
            load()
            Toast.makeText(requireContext(), "已导入 ${entries.size} 个书签", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(
            if (mode == MODE_BOOKMARKS) R.string.bookmarks_title else R.string.history_title,
        )
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_archive)
        if (mode != MODE_BOOKMARKS) {
            toolbar.menu.findItem(R.id.action_import)?.isVisible = false
        }
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = if (mode == MODE_BOOKMARKS) "搜索书签" else "搜索历史"
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                adapter.filter(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // 边打边筛：历史动辄上千条，让人打完再回车等于逼他去翻列表
                adapter.filter(newText.orEmpty())
                return true
            }
        })
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_import) {
                // 有的文件管理器把 .html 报成 octet-stream，限定类型反而选不中文件
                runCatching { importLauncher.launch(arrayOf("*/*")) }
                return@setOnMenuItemClickListener true
            }
            if (item.itemId == R.id.action_clear) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (mode == MODE_BOOKMARKS) {
                            // 逐条删除书签
                            store.bookmarks().forEach { store.removeBookmark(it.url) }
                        } else {
                            store.clearHistory()
                        }
                    }
                    load()
                }
                true
            } else false
        }

        val recycler = view.findViewById<RecyclerView>(R.id.archive_recycler)
        adapter = ArchiveAdapter(
            onOpen = { item -> (requireActivity() as MainActivity).openUrl(item.url) },
            onDelete = { item ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (mode == MODE_BOOKMARKS) store.removeBookmark(item.url)
                        else {
                            // 删除该 URL 的全部历史记录
                            store.clearHistoryFor(item.url)
                        }
                    }
                    load()
                }
            },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                if (mode == MODE_BOOKMARKS) store.bookmarks() else store.history()
            }
            adapter.submit(list, mode == MODE_BOOKMARKS)
            view?.findViewById<TextView>(R.id.empty_view)?.isVisible = list.isEmpty()
        }
    }

    private class ArchiveAdapter(
        val onOpen: (ArchiveItem) -> Unit,
        val onDelete: (ArchiveItem) -> Unit,
    ) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

        private val data = mutableListOf<ArchiveItem>()
        private var all: List<ArchiveItem> = emptyList()
        private var bookmarkMode = true
        private var query: String = ""

        fun submit(list: List<ArchiveItem>, bookmarkMode: Boolean) {
            all = list
            this.bookmarkMode = bookmarkMode
            applyFilter()
        }

        fun filter(text: String) {
            query = text.trim()
            applyFilter()
        }

        private fun applyFilter() {
            data.clear()
            data.addAll(
                if (query.isEmpty()) all
                else all.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.url.contains(query, ignoreCase = true)
                },
            )
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.archive_icon)
            val title: TextView = view.findViewById(R.id.archive_title)
            val url: TextView = view.findViewById(R.id.archive_url)
            val delete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_archive, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.icon.setImageResource(
                if (bookmarkMode) R.drawable.ic_bookmark else R.drawable.ic_history,
            )
            holder.title.text = item.title.ifBlank { item.url }
            holder.url.text = item.url
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.delete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = data.size
    }
}
