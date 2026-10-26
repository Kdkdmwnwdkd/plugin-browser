package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.pluginbrowser.MainActivity
import com.pluginbrowser.R

/** 标签页切换器（网格卡片 + 缩略图） */
class TabSwitcherFragment : Fragment(R.layout.fragment_tab_switcher) {

    private val tabManager: TabManager get() = (requireActivity() as MainActivity).tabManager
    private lateinit var adapter: TabAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.menu_tabs)
        updateIncognitoMenuTitle(toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle_incognito -> {
                    tabManager.incognitoMode = !tabManager.incognitoMode
                    updateIncognitoMenuTitle(toolbar)
                    adapter.refresh()
                    true
                }
                R.id.action_close_all -> {
                    tabManager.closeAll()
                    adapter.refresh()
                    true
                }
                else -> false
            }
        }

        val recycler = view.findViewById<RecyclerView>(R.id.tabs_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = TabAdapter(
            tabs = tabManager.tabs,
            currentIndex = { tabManager.currentIndex },
            onSelect = { index ->
                tabManager.switchTo(index)
                requireActivity().onBackPressedDispatcher.onBackPressed()
            },
            onClose = { index ->
                tabManager.closeTab(index)
                adapter.refresh()
            },
        )
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fab_new_tab).setOnClickListener {
            tabManager.newTab(url = settings().homeUrl, incognito = tabManager.incognitoMode)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        updateEmptyView(view)
    }

    private fun settings() = com.pluginbrowser.data.SettingsRepository(requireContext())

    private fun updateIncognitoMenuTitle(toolbar: MaterialToolbar) {
        val item = toolbar.menu.findItem(R.id.action_toggle_incognito)
        item.title = getString(
            if (tabManager.incognitoMode) R.string.tabs_incognito_on else R.string.tabs_incognito_off,
        )
    }

    private fun updateEmptyView(view: View) {
        val empty = view.findViewById<TextView>(R.id.empty_view)
        val recycler = view.findViewById<RecyclerView>(R.id.tabs_recycler)
        val isEmpty = tabManager.tabs.isEmpty()
        empty.isVisible = isEmpty
        recycler.isVisible = !isEmpty
        adapter.onCountChanged = { count ->
            empty.isVisible = count == 0
            recycler.isVisible = count > 0
        }
    }

    private class TabAdapter(
        private val tabs: List<Tab>,
        private val currentIndex: () -> Int,
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit,
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        var onCountChanged: ((Int) -> Unit)? = null

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.tab_thumbnail)
            val title: TextView = view.findViewById(R.id.tab_title)
            val url: TextView = view.findViewById(R.id.tab_url)
            val badge: TextView = view.findViewById(R.id.tab_incognito_badge)
            val close: ImageButton = view.findViewById(R.id.btn_close_tab)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab = tabs[position]
            holder.title.text = tab.title.ifBlank { holder.itemView.context.getString(R.string.new_tab_page) }
            holder.url.text = tab.url.ifBlank { "" }
            holder.badge.isVisible = tab.incognito

            val bmp = capture(tab.webView)
            if (bmp != null) {
                holder.thumbnail.setImageBitmap(bmp)
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                holder.thumbnail.setImageDrawable(null)
            }

            holder.itemView.setOnClickListener { onSelect(position) }
            holder.close.setOnClickListener { onClose(position) }
        }

        override fun getItemCount(): Int = tabs.size

        fun refresh() {
            notifyDataSetChanged()
            onCountChanged?.invoke(itemCount)
        }

        @Suppress("DEPRECATION")
        private fun capture(webView: android.webkit.WebView): Bitmap? = try {
            webView.isDrawingCacheEnabled = true
            webView.buildDrawingCache(true)
            val cache = webView.drawingCache
            val bmp = if (cache != null) Bitmap.createBitmap(cache) else null
            webView.isDrawingCacheEnabled = false
            webView.destroyDrawingCache()
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
