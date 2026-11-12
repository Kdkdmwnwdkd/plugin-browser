package com.pluginbrowser.site

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.pluginbrowser.R
import com.pluginbrowser.settings.dropIconSpace

/**
 * 站点适配设置页。
 *
 * 规则是数据（SiteRules.ALL），这里只负责渲染成开关，不写任何站点专属逻辑——
 * 以后加站点只要在规则表里加一条，这一页自动多一行。
 */
class SiteRulesFragment : PreferenceFragmentCompat() {

    private lateinit var engine: SiteRuleEngine

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        return view
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        engine = SiteRuleEngine(requireContext())

        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        val ruleSwitches = ArrayList<Pair<SiteRule, SwitchPreferenceCompat>>()

        val master = SwitchPreferenceCompat(requireContext()).apply {
            key = "site_master"
            title = "启用站点适配"
            summary = "按站点决定加载方式和注入内容。关掉后所有规则都不生效，但各自的选择会保留"
            isChecked = engine.masterEnabled
            // 值只由 SiteRuleEngine 持有（pb_site_rules）。不关掉的话，Preference
            // 还会按 key 往默认偏好文件里另存一份，同一个开关两处真相。
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                engine.masterEnabled = on
                ruleSwitches.forEach { (_, sw) -> sw.isEnabled = on }
                true
            }
        }
        screen.addPreference(master)

        SiteGroup.values().forEach { group ->
            val rules = SiteRules.ALL.filter { it.group == group }
            if (rules.isEmpty()) return@forEach
            val category = PreferenceCategory(requireContext()).apply { title = group.title }
            screen.addPreference(category)
            rules.forEach { rule ->
                val item = SwitchPreferenceCompat(requireContext()).apply {
                    key = "site_rule_${rule.id}"
                    title = rule.name
                    summary = "${rule.summary}\n${rule.hostLabel}"
                    isChecked = engine.isEnabled(rule)
                    isEnabled = engine.masterEnabled
                    isPersistent = false
                    setOnPreferenceChangeListener { _, newValue ->
                        engine.setEnabled(rule.id, newValue as Boolean)
                        true
                    }
                }
                ruleSwitches.add(rule to item)
                category.addPreference(item)
            }
        }

        // 先挂到 screen 上，再往里加子项。
        // PreferenceGroup 只有 attached 之后才持有 PreferenceManager；对一个还没挂上去的组
        // 调 addPreference，内部会走 onAttachedToHierarchy(null)，在分配 id 时直接
        // NullPointerException —— 也就是 getNextId() on a null object reference。
        val about = PreferenceCategory(requireContext()).apply { title = "说明" }
        screen.addPreference(about)

        about.addPreference(Preference(requireContext()).apply {
            title = "单个网站的设置不在这里"
            summary = "打开某个网站后，点地址栏左边的锁：可以单独设桌面版、字号、JavaScript、图片，" +
                "也能看证书、给相机/麦克风/定位授权、清除这个站的数据"
            isSelectable = false
        })
        about.addPreference(Preference(requireContext()).apply {
            title = "这些开关都立即生效"
            summary = "当前页面重新加载一次即可看到效果；自动播放、后台播放这类 WebView 设置在下一次加载时套用"
            isSelectable = false
        })
        about.addPreference(Preference(requireContext()).apply {
            key = "site_reset"
            title = "恢复默认"
            summary = "把所有站点规则恢复成出厂状态"
            setOnPreferenceClickListener {
                engine.resetToDefault()
                // 原地刷新，不再 replace 一个新实例：那样会把整页重跑一遍，
                // 当前滚动位置也一起丢掉。
                master.isChecked = engine.masterEnabled
                ruleSwitches.forEach { (rule, sw) ->
                    sw.isEnabled = engine.masterEnabled
                    sw.isChecked = engine.isEnabled(rule)
                }
                true
            }
        })

        // 这些行都没有图标，留着 Material 默认的 56dp 图标位就是一条空白带
        screen.dropIconSpace()
    }
}
