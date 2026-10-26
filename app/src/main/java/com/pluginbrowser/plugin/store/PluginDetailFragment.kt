package com.pluginbrowser.plugin.store

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.plugin.PluginInfo
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.plugin.PluginType

/** 插件详情页 */
class PluginDetailFragment : Fragment(R.layout.fragment_plugin_detail) {

    companion object {
        private const val ARG_ID = "plugin_id"
        fun newInstance(id: String) = PluginDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    private val pluginManager: PluginManager
        get() = PluginBrowserApp.from(requireContext()).pluginManager

    private var info: PluginInfo? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        val id = arguments?.getString(ARG_ID) ?: return
        info = pluginManager.plugin(id)
        val plugin = info ?: return

        val icon = view.findViewById<ImageView>(R.id.detail_icon)
        icon.setImageResource(
            if (plugin.manifest.type == PluginType.JS) R.drawable.ic_extension else R.drawable.ic_widget,
        )
        view.findViewById<TextView>(R.id.detail_name).text = plugin.name
        toolbar.title = plugin.name
        view.findViewById<TextView>(R.id.detail_version).text =
            "${plugin.manifest.author} · v${plugin.manifest.version}"
        view.findViewById<TextView>(R.id.detail_desc).text =
            plugin.manifest.description.ifBlank { getString(R.string.plugins_default_desc) }

        val typeText = if (plugin.manifest.type == PluginType.JS) {
            getString(R.string.plugins_type_js)
        } else {
            getString(R.string.plugins_type_native)
        }
        val infoText = buildString {
            append("ID：${plugin.manifest.id}\n")
            append("类型：$typeText\n")
            if (plugin.external) append("来源：外部 APK 插件\n")
            if (plugin.manifest.builtin) append("${getString(R.string.plugins_builtin_tag)}\n")
            if (plugin.manifest.entry != null) {
                append("${getString(R.string.plugins_field_entry)}：${plugin.manifest.entry}\n")
            }
            val matches = plugin.manifest.matches.filter { it.isNotBlank() }
            append("${getString(R.string.plugins_field_matches)}：")
            append(matches.joinToString("、").ifBlank { getString(R.string.plugins_none) })
        }
        view.findViewById<TextView>(R.id.detail_info).text = infoText

        val permissions = plugin.manifest.permissions
        view.findViewById<TextView>(R.id.detail_permissions).text =
            "${getString(R.string.plugins_field_permissions)}：" +
            (permissions.joinToString("、").ifBlank { getString(R.string.plugins_none) })

        val switch = view.findViewById<SwitchCompat>(R.id.detail_switch)
        switch.isChecked = plugin.enabled
        switch.setOnCheckedChangeListener { _, checked ->
            pluginManager.setEnabled(plugin.id, checked)
        }

        val uninstall = view.findViewById<MaterialButton>(R.id.btn_uninstall)
        uninstall.isVisible = !plugin.manifest.builtin && plugin.manifest.type == PluginType.JS
        uninstall.setOnClickListener {
            if (pluginManager.uninstall(plugin.id)) {
                Toast.makeText(requireContext(), R.string.archive_cleared, Toast.LENGTH_SHORT).show()
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                Toast.makeText(requireContext(), R.string.plugins_uninstall_builtin, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
