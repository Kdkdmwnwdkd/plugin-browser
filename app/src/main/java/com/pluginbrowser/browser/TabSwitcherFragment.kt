package com.pluginbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pluginbrowser.MainActivity
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository

/**
 * 标签页切换器：从底部掀起的模态面板。
 * 用 BottomSheet 而不是整页替换，切标签不再"跳页面"，手势也更贴近浏览器直觉。
 */
class TabSwitcherFragment : BottomSheetDialogFragment() {

    private val tabManager: TabManager get() = (requireActivity() as MainActivity).tabManager

    private lateinit var adapter: TabAdapter
    private lateinit var filterAll: TextView
    private lateinit var filterIncognito: TextView
    private lateinit var incognitoButton: ImageButton

    private var incognitoOnly = false

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tab_switcher, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.tabs_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = TabAdapter(
            currentIndex = { tabManager.currentIndex },
            onSelect = { index ->
                tabManager.switchTo(index)
                dismiss()
            },
            onClose = { index ->
                tabManager.closeTab(index)
                refresh()
            },
        )
        recycler.adapter = adapter

        view.findViewById<TextView>(R.id.btn_done).setOnClickListener { dismiss() }

        incognitoButton = view.findViewById(R.id.btn_incognito)
        incognitoButton.setOnClickListener {
            tabManager.incognitoMode = !tabManager.incognitoMode
            updateIncognitoTint()
        }

        filterAll = view.findViewById(R.id.filter_all)
        filterIncognito = view.findViewById(R.id.filter_incognito)
        filterAll.setOnClickListener { setFilter(false) }
        filterIncognito.setOnClickListener { setFilter(true) }

        view.findViewById<TextView>(R.id.btn_close_all).setOnClickListener {
            tabManager.closeAll()
            refresh()
        }

        view.findViewById<MaterialButton>(R.id.btn_new_tab).setOnClickListener {
            tabManager.newTab(
                url = SettingsRepository(requireContext()).homeUrl,
                incognito = tabManager.incognitoMode,
            )
            dismiss()
        }

        updateIncognitoTint()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        // 占屏 92%，留出状态栏一点呼吸空间
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
        }
    }

    private fun setFilter(incognito: Boolean) {
        incognitoOnly = incognito
        refresh()
    }

    private fun refresh() {
        val entries = tabManager.tabs.mapIndexedNotNull { index, tab ->
            if (!incognitoOnly || tab.incognito) TabAdapter.Entry(index, tab) else null
        }
        adapter.submit(entries)

        val root = view ?: return
        root.findViewById<TextView>(R.id.empty_view)?.isVisible = entries.isEmpty()
        root.findViewById<RecyclerView>(R.id.tabs_recycler)?.isVisible = entries.isNotEmpty()

        val active = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val inactive = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        filterAll.setTextColor(if (incognitoOnly) inactive else active)
        filterIncognito.setTextColor(if (incognitoOnly) active else inactive)
        filterAll.typeface = if (incognitoOnly) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        filterIncognito.typeface = if (incognitoOnly) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun updateIncognitoTint() {
        val on = tabManager.incognitoMode
        incognitoButton.setColorFilter(
            themeColor(
                if (on) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ),
        )
        incognitoButton.contentDescription = getString(
            if (on) R.string.tabs_incognito_on else R.string.tabs_incognito_off,
        )
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(requireContext(), value.resourceId)
        } else {
            value.data
        }
    }

    private class TabAdapter(
        private val currentIndex: () -> Int,
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit,
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        class Entry(val index: Int, val tab: Tab)

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.tab_card)
            val thumbnail: ImageView = view.findViewById(R.id.tab_thumbnail)
            val title: TextView = view.findViewById(R.id.tab_title)
            val url: TextView = view.findViewById(R.id.tab_url)
            val badge: TextView = view.findViewById(R.id.tab_incognito_badge)
            val close: ImageButton = view.findViewById(R.id.btn_close_tab)
        }

        private val items = mutableListOf<Entry>()

        fun submit(entries: List<Entry>) {
            items.clear()
            items.addAll(entries)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val tab = entry.tab
            val ctx = holder.itemView.context

            holder.title.text = tab.title.ifBlank { ctx.getString(R.string.new_tab_page) }
            holder.url.text = tab.url.ifBlank { "" }
            holder.badge.isVisible = tab.incognito

            // 当前标签 2dp 强调色描边，其余 1dp 细线
            val active = entry.index == currentIndex()
            val density = holder.itemView.resources.displayMetrics.density
            holder.card.strokeWidth = ((if (active) 2 else 1) * density).toInt()
            holder.card.strokeColor = themeColor(
                ctx,
                if (active) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant,
            )

            // 已销毁的 WebView 再取绘制缓存，轻则空白，重则直接崩在 native 层
            val bitmap = if (tab.usable) capture(tab.webView) else null
            if (bitmap != null) {
                holder.thumbnail.setImageBitmap(bitmap)
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                holder.thumbnail.setImageDrawable(null)
            }

            holder.itemView.setOnClickListener { onSelect(entry.index) }
            holder.close.setOnClickListener { onClose(entry.index) }
        }

        override fun getItemCount(): Int = items.size

        private fun themeColor(context: Context, attr: Int): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(attr, value, true)
            return if (value.resourceId != 0) {
                ContextCompat.getColor(context, value.resourceId)
            } else {
                value.data
            }
        }

        @Suppress("DEPRECATION")
        private fun capture(webView: android.webkit.WebView): Bitmap? = try {
            webView.isDrawingCacheEnabled = true
            webView.buildDrawingCache(true)
            val cache = webView.drawingCache
            val bitmap = if (cache != null) Bitmap.createBitmap(cache) else null
            webView.isDrawingCacheEnabled = false
            webView.destroyDrawingCache()
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
