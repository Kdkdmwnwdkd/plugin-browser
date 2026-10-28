package com.pluginbrowser.settings

import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pluginbrowser.data.DataBackup
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.translate.TranslateSettings
import com.pluginbrowser.translate.TranslateSettingsFragment
import com.pluginbrowser.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页（Preference 框架） */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var settings: SettingsRepository
    private val store by lazy { BrowserStore(requireContext()) }
    private val pluginManager: PluginManager
        get() = PluginBrowserApp.from(requireContext()).pluginManager

    /** 备份导出/恢复走系统文件选择器，不需要存储权限 */
    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { doExport(it) } }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { doImport(it) } }

    private val channel get() = PluginBrowserApp.from(requireContext()).gitHubChannel
    private var probing = false

    private fun proxySummary(value: String): String =
        if (value.isBlank()) {
            "仅对 GitHub 生效。留空直连，可填 127.0.0.1:7890 或 socks5://127.0.0.1:1080"
        } else {
            "仅对 GitHub 生效 · 当前：$value"
        }

    /** 走一遍和浏览时完全相同的通道，省得用户改完代理也不知道到底通没通 */
    private fun probeConnection() {
        if (probing) return
        val pref = findPreference<Preference>("net_probe") ?: return
        probing = true
        pref.summary = "正在测试…"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { channel.probe() }
            pref.summary = text
            probing = false
        }
    }

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
        preferenceScreen?.dropIconSpace()

        findPreference<ListPreference>("search_engine")?.setOnPreferenceChangeListener { _, newValue ->
            settings.searchEngine = newValue as String
            true
        }

        findPreference<Preference>("home_url")?.setOnPreferenceChangeListener { _, newValue ->
            settings.homeUrl = newValue as String
            true
        }

        findPreference<ListPreference>("ua_preset")?.setOnPreferenceChangeListener { _, newValue ->
            settings.uaPreset = newValue as String
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

        findPreference<SwitchPreferenceCompat>("ask_before_download")?.setOnPreferenceChangeListener { _, newValue ->
            settings.askBeforeDownload = newValue as Boolean
            // 重新打开开关时，把「不再询问」的站点也一并清掉，否则设了跟没设一样
            if (newValue as Boolean) settings.forgetDownloadTrust()
            true
        }

        findPreference<SwitchPreferenceCompat>("github_accel")?.setOnPreferenceChangeListener { _, newValue ->
            settings.githubAccel = newValue as Boolean
            channel.reset()
            // 配置变了就重新确认一次这条路通不通，别等用户去点页面才发现
            if (newValue) channel.warmUp(force = true)
            true
        }

        findPreference<SwitchPreferenceCompat>("github_catch_pages")?.setOnPreferenceChangeListener { _, newValue ->
            settings.githubCatchPages = newValue as Boolean
            channel.reset()
            true
        }

        findPreference<EditTextPreference>("proxy_url")?.apply {
            summary = proxySummary(settings.proxyUrl)
            setOnPreferenceChangeListener { pref, newValue ->
                val text = (newValue as String).trim()
                settings.proxyUrl = text
                pref.summary = proxySummary(text)
                channel.reset()
                channel.warmUp(force = true)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("doh_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            settings.dohEnabled = newValue as Boolean
            channel.reset()
            channel.warmUp(force = true)
            true
        }

        findPreference<Preference>("net_probe")?.setOnPreferenceClickListener {
            probeConnection()
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

        findPreference<Preference>("plugin_store")?.setOnPreferenceClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, com.pluginbrowser.plugin.store.PluginStoreFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
            true
        }

        findPreference<Preference>("site_rules")?.setOnPreferenceClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, com.pluginbrowser.site.SiteRulesFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
            true
        }

        findPreference<Preference>("gesture_settings")?.setOnPreferenceClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, GestureSettingsFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
            true
        }

        findPreference<Preference>("engine_check")?.setOnPreferenceClickListener {
            com.pluginbrowser.compat.EngineSheet().show(
                parentFragmentManager,
                com.pluginbrowser.compat.EngineSheet.TAG,
            )
            true
        }

        findPreference<Preference>("translate_service")?.apply {
            summary = TranslateSettings(requireContext()).provider.label
            setOnPreferenceClickListener {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.main_container, TranslateSettingsFragment())
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
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

        findPreference<Preference>("backup_export")?.setOnPreferenceClickListener {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            exportBackup.launch("kite-backup-$stamp.json")
            true
        }

        findPreference<Preference>("backup_import")?.setOnPreferenceClickListener {
            runCatching { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }
            true
        }

        // 版本号从安装包读，发版不用再回来改文案
        findPreference<Preference>("about")?.apply {
            val version = UpdateManager.currentVersionName(requireContext())
            summary = getString(R.string.settings_about_summary, version)
            setOnPreferenceClickListener {
                Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show()
                true
            }
        }

        findPreference<Preference>("crash_log")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            val crash = com.pluginbrowser.util.CrashLogger.read(ctx)
            val events = com.pluginbrowser.util.EventLog.dump()
            val text = buildString {
                if (!crash.isNullOrBlank()) {
                    append("== 崩溃堆栈 ==\n").append(crash.takeLast(4000)).append("\n\n")
                }
                if (events.isNotBlank()) {
                    append("== 事件时间线 ==\n").append(events.takeLast(4000))
                }
            }.trim()
            if (text.isEmpty()) {
                Toast.makeText(ctx, "还没有记录到异常", Toast.LENGTH_SHORT).show()
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("运行日志")
                    .setMessage(text)
                    .setPositiveButton("复制") { _, _ ->
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
            true
        }
    }

    private fun doExport(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { DataBackup.export(ctx, uri) } }
            val msg = result.fold(
                { getString(R.string.backup_exported, it.bookmarks, it.history) },
                { getString(R.string.backup_failed, it.message ?: "未知错误") },
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { DataBackup.import(ctx, uri) } }
            val msg = result.fold(
                { getString(R.string.backup_imported, it.bookmarks, it.history) },
                { getString(R.string.backup_failed, it.message ?: "未知错误") },
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
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
        findPreference<SwitchPreferenceCompat>("ask_before_download")?.isChecked = settings.askBeforeDownload
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
                    status.text = runCatching {
                        getString(R.string.update_downloading, percent)
                    }.getOrDefault("$percent%")
                }
            }
            dialog.dismiss()
            result.onSuccess { file ->
                // 下载可能要几十秒，这期间用户完全可能退出设置页甚至退出 App。
                // 这里以前直接用 requireContext()，一旦 detached 就是
                // 「Fragment not attached to a context」——闪退就是这么来的
                val ctx = context ?: return@onSuccess
                val started = runCatching { UpdateManager.install(ctx, file) }.getOrDefault(false)
                Toast.makeText(
                    ctx,
                    if (started) R.string.update_install_hint else R.string.update_permission_hint,
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                val ctx = context ?: return@onFailure
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.update_download_failed, error.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
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
