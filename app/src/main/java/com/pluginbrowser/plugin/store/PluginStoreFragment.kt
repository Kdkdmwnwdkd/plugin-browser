package com.pluginbrowser.plugin.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.plugin.PluginInfo
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.PluginType

/** 插件管理（商店）：列表 / 启用禁用 / 导入 .pbp */
class PluginStoreFragment : Fragment(R.layout.fragment_plugin_store) {

    private val pluginManager: PluginManager
        get() = PluginBrowserApp.from(requireContext()).pluginManager

    private lateinit var adapter: PluginListAdapter

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = pluginManager.installFromZip(uri)
        result.onSuccess { manifest ->
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.plugins_import_success, manifest.name),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }.onFailure { e ->
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.plugins_import_failed, e.message ?: "未知错误"),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val recycler = view.findViewById<RecyclerView>(R.id.plugins_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PluginListAdapter(
            sectionJs = getString(R.string.plugins_section_installed),
            sectionNative = getString(R.string.plugins_section_native),
            sectionExternal = getString(R.string.plugins_section_external),
            onToggle = { info, on ->
                pluginManager.setEnabled(info.id, on)
                // 已加载页面不会自动撤销/追加注入，提示用户刷新
                android.widget.Toast.makeText(
                    requireContext(),
                    if (on) "已启用，刷新页面后生效" else "已停用，刷新页面后移除",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                refresh()
            },
            onClick = { info ->
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.main_container, PluginDetailFragment.newInstance(info.id))
                    .addToBackStack(null)
                    .commit()
            },
        )
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btn_import).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        pluginManager.onPluginsChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        pluginManager.onPluginsChanged = null
    }

    private fun refresh() {
        if (!isAdded) return
        val plugins = pluginManager.allPlugins()
        adapter.submit(plugins)
        view?.findViewById<TextView>(R.id.empty_view)?.isVisible = plugins.isEmpty()
    }
}

/** 插件列表适配器：分组（JS / 内置原生 / 外部原生） */
class PluginListAdapter(
    private val sectionJs: String,
    private val sectionNative: String,
    private val sectionExternal: String,
    private val onToggle: (PluginInfo, Boolean) -> Unit,
    private val onClick: (PluginInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_PLUGIN = 1
    }

    private sealed class Row {
        data class Section(val title: String) : Row()
        data class Plugin(val info: PluginInfo) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(plugins: List<PluginInfo>) {
        rows.clear()
        val js = plugins.filter { it.manifest.type == PluginType.JS }
        val builtinNative = plugins.filter { it.manifest.type == PluginType.NATIVE && !it.external }
        val external = plugins.filter { it.external }
        if (js.isNotEmpty()) rows.add(Row.Section(sectionJs))
        js.forEach { rows.add(Row.Plugin(it)) }
        if (builtinNative.isNotEmpty()) rows.add(Row.Section(sectionNative))
        builtinNative.forEach { rows.add(Row.Plugin(it)) }
        if (external.isNotEmpty()) rows.add(Row.Section(sectionExternal))
        external.forEach { rows.add(Row.Plugin(it)) }
        notifyDataSetChanged()
    }

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
    }

    class PluginVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.plugin_icon)
        val name: TextView = view.findViewById(R.id.plugin_name)
        val tag: TextView = view.findViewById(R.id.plugin_type_tag)
        val author: TextView = view.findViewById(R.id.plugin_author)
        val desc: TextView = view.findViewById(R.id.plugin_desc)
        val switch: MaterialSwitch = view.findViewById(R.id.plugin_switch)
        val divider: View = view.findViewById(R.id.row_divider)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Plugin -> TYPE_PLUGIN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_SECTION) {
            SectionVH(LayoutInflater.from(parent.context).inflate(R.layout.item_section, parent, false))
        } else {
            PluginVH(LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionVH).title.text = row.title
            is Row.Plugin -> {
                val info = row.info
                holder as PluginVH
                val ctx = holder.itemView.context
                // 用名称首字做标识，比通用图标更容易区分
                holder.icon.text = info.name.take(1)
                holder.icon.alpha = if (info.enabled) 1f else 0.45f
                holder.divider.isVisible = position < rows.size - 1 && rows[position + 1] is Row.Plugin
                holder.name.text = info.name
                holder.name.alpha = if (info.enabled) 1f else 0.5f
                holder.tag.isVisible = info.manifest.builtin
                holder.tag.text = ctx.getString(R.string.plugins_builtin_tag)
                holder.author.text = buildString {
                    append("v")
                    append(info.manifest.version)
                    append(" · ")
                    append(info.manifest.author)
                }
                holder.desc.text = info.manifest.description
                holder.desc.alpha = if (info.enabled) 1f else 0.5f
                holder.switch.isChecked = info.enabled
                holder.switch.setOnClickListener { onToggle(info, holder.switch.isChecked) }
                holder.itemView.setOnClickListener { onClick(info) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size
}
