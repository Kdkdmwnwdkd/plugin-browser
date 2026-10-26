package com.pluginbrowser.settings

import android.os.Bundle
import android.webkit.CookieManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.BrowserStore
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.translate.TranslateSettings
import com.pluginbrowser.translate.TranslateSettingsFragment
import com.pluginbrowser.update.UpdateManager
import kotlinx.coroutines.launch

/** 设置页（Preference 框架） */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var settings: SettingsRepository
    private val store by lazy { BrowserStore(requireContext()) }
    private val pluginManager: PluginManager
        get() = PluginBrowserApp.from(requireContext()).pluginManager

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): android.view.View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.let { toolbar ->
            toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
        return view
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        settings = SettingsRepository(requireContext())
        setPreferencesFromResource(R.xml.pref_settings, rootKey)

        findPreference<ListPreference>("search_engine")?.setOnPreferenceChangeListener { _, newValue ->
            settings.searchEngine = newValue as String
            true
        }

        findPreference<Preference>("home_url")?.setOnPreferenceChangeListener { _, newValue ->
            settings.homeUrl = newValue as String
            true
        }

        findPreference<SwitchPreferenceCompat>("javascript")?.setOnPreferenceChangeListener { _, newValue ->
            settings.javascriptEnabled = newValue as Boolean
            true
        }

        findPreference<SwitchPreferenceCompat>("third_party_cookies")?.setOnPreferenceChangeListener { _, newValue ->
            settings.thirdPartyCookies = newValue as Boolean
            true
        }

        findPreference<SwitchPreferenceCompat>("block_ads")?.setOnPreferenceChangeListener { _, newValue ->
            // 唯一数据源：插件启用状态（不再往 pb_settings 写一份 blockAds）
            pluginManager.setEnabled(PluginManager.ADBLOCK_PLUGIN_ID, newValue as Boolean)
            true
        }

        findPreference<SwitchPreferenceCompat>("desktop_ua")?.setOnPreferenceChangeListener { _, newValue ->
            settings.desktopUa = newValue as Boolean
            Toast.makeText(requireContext(), "新标签页生效", Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, newValue ->
            settings.theme = newValue as String
            settings.applyTheme()
            true
        }

        findPreference<Preference>("clear_data")?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_clear_data)
                .setMessage(R.string.settings_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    lifecycleScopeLaunch { clearBrowsingData() }
                }
                .show()
            true
        }

        findPreference<Preference>("translate_service")?.apply {
            summary = TranslateSettings(requireContext()).provider.label
            setOnPreferenceClickListener {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.main_container, TranslateSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }
        }

        findPreference<Preference>("check_update")?.apply {
            summary = getString(
                R.string.update_check_summary,
                UpdateManager.currentVersionName(requireContext()),
            )
            setOnPreferenceClickListener {
                checkForUpdate()
                true
            }
        }

        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_about_summary),
                Toast.LENGTH_LONG,
            ).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Preference 框架读的是自己的默认值，业务值在 pb_settings / pb_plugin_states，
        // 每次进入都按真实值回填，避免「关了再进仍显示开」
        findPreference<ListPreference>("search_engine")?.value = settings.searchEngine
        findPreference<EditTextPreference>("home_url")?.text = settings.homeUrl
        findPreference<SwitchPreferenceCompat>("javascript")?.isChecked = settings.javascriptEnabled
        findPreference<SwitchPreferenceCompat>("third_party_cookies")?.isChecked = settings.thirdPartyCookies
        findPreference<SwitchPreferenceCompat>("block_ads")?.isChecked =
            pluginManager.isEnabled(PluginManager.ADBLOCK_PLUGIN_ID, true)
        findPreference<SwitchPreferenceCompat>("desktop_ua")?.isChecked = settings.desktopUa
        findPreference<ListPreference>("theme")?.value = settings.theme
    }

    /* ---------------- 应用内更新 ---------------- */

    private fun checkForUpdate() {
        val app = requireContext().applicationContext
        Toast.makeText(requireContext(), R.string.update_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = UpdateManager.check(app)) {
                is UpdateManager.Result.Available -> if (isAdded) showUpdateDialog(result.info)
                is UpdateManager.Result.UpToDate -> if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_latest, result.currentVersion),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is UpdateManager.Result.Failed -> if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_check_failed, result.reason),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        val sizeText = if (info.size > 0) {
            String.format(java.util.Locale.US, "%.1f MB", info.size / 1024.0 / 1024.0)
        } else {
            ""
        }
        val message = buildString {
            if (sizeText.isNotEmpty()) {
                append(getString(R.string.update_size, sizeText))
                append("\n\n")
            }
            append(info.notes.ifBlank { getString(R.string.update_no_notes) })
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(message)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(info) }
            .show()
    }

    private fun startDownload(info: UpdateManager.UpdateInfo) {
        val ctx = requireContext()
        val app = ctx.applicationContext
        val content = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val status = content.findViewById<TextView>(R.id.update_status)
        val bar = content.findViewById<ProgressBar>(R.id.update_progress)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.update_downloading_title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = UpdateManager.download(app, info) { percent ->
                // 回调在 IO 线程，转回主线程更新进度
                bar.post {
                    bar.progress = percent
                    status.text = getString(R.string.update_downloading, percent)
                }
            }
            dialog.dismiss()
            result.onSuccess { file ->
                if (!isAdded) return@onSuccess
                val started = UpdateManager.install(requireContext(), file)
                Toast.makeText(
                    requireContext(),
                    if (started) R.string.update_install_hint else R.string.update_permission_hint,
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.update_download_failed, error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private suspend fun clearBrowsingData() {
        store.clearBrowsingData()
        // 清空 WebView 缓存与 Cookie
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        val tabManager = (requireActivity() as com.pluginbrowser.MainActivity).tabManager
        tabManager.tabs.forEach { tab ->
            if (tab.isCreated) {
                tab.webView.clearCache(true)
                tab.webView.clearHistory()
            }
        }
        Toast.makeText(requireContext(), R.string.archive_cleared, Toast.LENGTH_SHORT).show()
    }
}
