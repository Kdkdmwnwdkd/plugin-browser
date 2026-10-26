package com.pluginbrowser.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import com.pluginbrowser.R
import com.pluginbrowser.data.SuggestionItem

/**
 * 地址栏下拉建议（书签 / 历史 / 搜索）。
 * 数据由 BrowserFragment 查询后通过 submit() 手动更新，
 * 因此 Filter 为空实现（仅满足 AutoCompleteTextView 的 Filterable 要求）。
 */
class SuggestionAdapter(context: Context) : BaseAdapter(), Filterable {

    private val inflater = LayoutInflater.from(context)
    private val items = mutableListOf<SuggestionItem>()

    fun submit(list: List<SuggestionItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): SuggestionItem? = items.getOrNull(position)

    override fun getItemId(position: Int): Long = position.toLong()

    /** 建议数据手动维护，过滤为空实现 */
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults()
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_suggestion, parent, false)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.suggestion_icon)
        val text = view.findViewById<TextView>(R.id.suggestion_text)
        text.text = item.title
        icon.setImageResource(
            when {
                item.isSearch -> R.drawable.ic_search
                item.isBookmark -> R.drawable.ic_bookmark
                else -> R.drawable.ic_history
            },
        )
        return view
    }
}
