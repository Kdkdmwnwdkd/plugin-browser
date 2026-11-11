package com.pluginbrowser.site

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pluginbrowser.PluginBrowserApp
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.translate.AutoTranslatePolicy
import com.pluginbrowser.util.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * 点地址栏左边的锁 → 这个网站到底是什么情况。
 *
 * 以前这颗锁只是个装饰：是不是 HTTPS 靠图标颜色猜，出了事（要权限没反应、
 * 手机版被强行跳桌面版）没有任何地方能查、能改。这里把三件事放在一起：
 * 连接与证书、这个站的加载偏好、这个站的权限。
 */
class SiteInfoSheet : BottomSheetDialogFragment() {

    /** 打开时的页面地址 */
    var pageUrl: String = ""

    /** 设置改完后通知宿主套用；参数表示要不要立即重载当前页 */
    var onApply: ((Boolean) -> Unit)? = null

    /** 切到「自动翻译」：宿主立刻翻当前页，不用等下次刷新 */
    var onAutoTranslate: ((String) -> Unit)? = null

    /** 切到「从不」：宿主把已经翻好的页面还原回原文 */
    var onCancelAutoTranslate: (() -> Unit)? = null

    private val host: String get() = SiteRuleEngine.hostOf(pageUrl).orEmpty()

    private lateinit var prefs: SitePreferences
    private lateinit var perms: SitePermissions
    private lateinit var settings: SettingsRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_site_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = SitePreferences(requireContext())
        perms = SitePermissions(requireContext())
        settings = SettingsRepository(requireContext())

        bindIdentity(view)
        bindSettings(view)
        bindTranslate(view)
        bindPermissions(view)
        bindAdBlock(view)
        bindBlocked(view)
        bindClear(view)
        loadCertificate(view)
    }

    private fun bindIdentity(view: View) {
        view.findViewById<TextView>(R.id.site_host).text = host.ifBlank { "本机页面" }
        val secure = pageUrl.startsWith("https://")
        val rule = SiteRuleEngine(requireContext()).describe(pageUrl)
        val state = buildString {
            append(if (secure) "连接已加密 · HTTPS" else "连接未加密 · HTTP")
            if (rule != "无") append(" · 已应用「$rule」")
        }
        view.findViewById<TextView>(R.id.site_state).text = state
    }

    private fun bindSettings(view: View) {
        val desktop = view.findViewById<SwitchMaterial>(R.id.sw_desktop)
        desktop.isChecked = prefs.desktop(host) ?: settings.desktopUa
        desktop.setOnCheckedChangeListener { _, checked ->
            prefs.setDesktop(host, checked)
            onApply?.invoke(true)
        }

        val js = view.findViewById<SwitchMaterial>(R.id.sw_js)
        js.isChecked = prefs.javascript(host) ?: settings.javascriptEnabled
        js.setOnCheckedChangeListener { _, checked ->
            prefs.setJavascript(host, checked)
            onApply?.invoke(true)
            if (!checked) toast("已关闭 JavaScript，页面可能显示不全")
        }

        val images = view.findViewById<SwitchMaterial>(R.id.sw_images)
        images.isChecked = prefs.images(host) ?: true
        images.setOnCheckedChangeListener { _, checked ->
            prefs.setImages(host, checked)
            onApply?.invoke(true)
        }

        val seek = view.findViewById<SeekBar>(R.id.zoom_seek)
        val value = view.findViewById<TextView>(R.id.zoom_value)
        val initial = prefs.textZoom(host).takeIf { it > 0 } ?: 100
        seek.progress = zoomToProgress(initial)
        value.text = "$initial%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = "${progressToZoom(progress)}%"
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                // 松手才落盘 + 重载：拖动过程中每一帧都重载会把页面刷成幻灯片
                prefs.setTextZoom(host, progressToZoom(bar.progress))
                onApply?.invoke(true)
            }
        })
    }

    /**
     * 翻译三态：手动 / 自动 / 从不。
     *
     * 用点击循环而不是开关，是因为「手动」和「从不」是两个不同意思的「关」：
     * 前者是「我还没决定」，后者是「别再问我」。合成一个开关，用户想表达
     * 「这个站以后别自动翻」时就只能一直关着，而自动翻译的默认值恰恰也是关。
     */
    private fun bindTranslate(view: View) {
        val row = view.findViewById<View>(R.id.row_translate)
        val label = view.findViewById<TextView>(R.id.translate_value)
        fun render() {
            label.text = AutoTranslatePolicy.label(prefs.translateMode(host))
        }
        render()
        row.setOnClickListener {
            val next = when (AutoTranslatePolicy.normalize(prefs.translateMode(host))) {
                AutoTranslatePolicy.ALWAYS -> AutoTranslatePolicy.NEVER
                AutoTranslatePolicy.NEVER -> AutoTranslatePolicy.MANUAL
                else -> AutoTranslatePolicy.ALWAYS
            }
            prefs.setTranslateMode(host, next)
            render()
            // 切成「自动」要立刻对这一页生效，否则用户以为没反应；
            // 切成「从不」只需拦住下一次，不用把已经翻好的页面折腾回去
            if (next == AutoTranslatePolicy.ALWAYS) {
                onApply?.invoke(false)
                dismissAllowingStateLoss()
                // 面板和翻译进度框都是窗口，错开一帧弹出，避免叠在一起再互相抢焦点
                activity?.window?.decorView?.post { onAutoTranslate?.invoke(host) }
            } else if (next == AutoTranslatePolicy.NEVER) {
                onCancelAutoTranslate?.invoke()
            }
        }
    }

    private fun bindPermissions(view: View) {
        val rows = listOf(
            Triple(R.id.perm_camera, R.id.perm_camera_value, PermKind.CAMERA),
            Triple(R.id.perm_mic, R.id.perm_mic_value, PermKind.MIC),
            Triple(R.id.perm_location, R.id.perm_location_value, PermKind.LOCATION),
        )
        rows.forEach { (rowId, valueId, kind) ->
            val row = view.findViewById<LinearLayout>(rowId)
            val label = view.findViewById<TextView>(valueId)
            fun render() {
                label.text = when (perms.decision(host, kind)) {
                    SitePermissions.Decision.ASK -> "询问"
                    SitePermissions.Decision.ALLOW -> "允许"
                    SitePermissions.Decision.DENY -> "已拒绝"
                }
            }
            render()
            row.setOnClickListener {
                val next = when (perms.decision(host, kind)) {
                    SitePermissions.Decision.ASK -> SitePermissions.Decision.ALLOW
                    SitePermissions.Decision.ALLOW -> SitePermissions.Decision.DENY
                    SitePermissions.Decision.DENY -> SitePermissions.Decision.ASK
                }
                perms.set(host, kind, next)
                render()
            }
        }
    }

    /**
     * 本站的广告拦截开关。
     *
     * 这是误杀的逃生口：规则拦得狠，偶尔会把正常站的图片或接口一起拦掉，
     * 以前只有「全局关掉」一条路，等于让别的站跟着裸奔。按站点关，刷新即生效。
     */
    private fun bindAdBlock(view: View) {
        val row = view.findViewById<View>(R.id.row_adblock)
        val label = view.findViewById<TextView>(R.id.adblock_value)
        val globalOn = PluginBrowserApp.from(requireContext()).pluginManager
            .isEnabled(PluginManager.ADBLOCK_PLUGIN_ID)

        fun render() {
            label.text = when {
                !globalOn -> "全局已关闭"
                prefs.adblock(host) == false -> "本站已关闭"
                else -> "开启"
            }
        }
        render()

        row.setOnClickListener {
            if (!globalOn) {
                toast("广告拦截在设置里被关掉了，先去打开它")
                return@setOnClickListener
            }
            val off = prefs.adblock(host) == false
            // 恢复时写 null 而不是 true：回到「跟随全局」。
            // 写死 true 的话，用户以后在设置里关掉全局开关，这个站还会留着一个看不见的覆盖。
            prefs.setAdblock(host, if (off) null else false)
            render()
            // 已经拦掉的请求不会自己回来，必须重载一次才能看到广告位恢复
            onApply?.invoke(true)
        }
    }

    private fun bindBlocked(view: View) {
        val blocker = ElementBlocker(requireContext())
        val label = view.findViewById<TextView>(R.id.blocked_value)
        fun render() {
            val n = blocker.count(host)
            label.text = if (n == 0) "无" else "$n 条"
        }
        render()
        view.findViewById<View>(R.id.row_blocked).setOnClickListener {
            val n = blocker.count(host)
            if (n == 0) {
                toast("还没有屏蔽过这个站点的元素")
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("恢复 $n 条屏蔽规则？")
                .setMessage("恢复后被屏蔽的广告位、浮层会重新出现。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部恢复") { _, _ ->
                    blocker.clear(host)
                    render()
                    onApply?.invoke(true)
                    toast("已恢复 $n 条规则")
                }
                .show()
        }
    }

    private fun bindClear(view: View) {
        view.findViewById<View>(R.id.btn_clear_site).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除 ${host.ifBlank { "本站" }} 的数据？")
                .setMessage("会清掉这个网站的 Cookie、本地存储、站点设置和权限记忆。\n其他网站不受影响，但你会被这个站退出登录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ ->
                    clearSiteData()
                    toast("已清除本站数据")
                    dismissAllowingStateLoss()
                }
                .show()
        }
    }

    private fun clearSiteData() {
        val targetHost = host
        // CookieManager / WebStorage 都是 WebView 的 API，必须在主线程调用。
        // 原来整段扔在 Dispatchers.IO 上，还套着 runCatching ——
        // 一旦因为线程不对抛异常，表现就是「点了清除，什么都没清」，且没有任何痕迹。
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val cm = CookieManager.getInstance()
                // http 和 https 是两个独立的 cookie 作用域，只清当前那个会漏一半
                listOf("https://$targetHost", "http://$targetHost").forEach { origin ->
                    cm.getCookie(origin).orEmpty()
                        .split(';')
                        .map { it.substringBefore('=').trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { name -> cm.setCookie(origin, "$name=; Max-Age=0; Path=/") }
                }
                cm.flush()
            }.onFailure { EventLog.log("SITE", "clear cookie failed: ${it.javaClass.simpleName}") }

            runCatching {
                val ws = WebStorage.getInstance()
                // 不走 getOrigins 再过滤：那是异步回调，还得自己拼字符串比对。
                // 站点只有这两种源，按名字删，命中就删、没有也不报错。
                ws.deleteOrigin("https://$targetHost")
                ws.deleteOrigin("http://$targetHost")
            }.onFailure { EventLog.log("SITE", "clear storage failed: ${it.javaClass.simpleName}") }

            withContext(Dispatchers.IO) {
                prefs.reset(targetHost)
                perms.clearHost(targetHost)
            }
        }
    }

    private fun loadCertificate(view: View) {
        val label = view.findViewById<TextView>(R.id.site_cert)
        if (!pageUrl.startsWith("https://")) {
            label.text = "未使用 HTTPS，页面内容在网络上是明文传输的"
            return
        }
        label.text = "证书：读取中…"
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { fetchCertificate(pageUrl) }
            if (!isAdded) return@launch
            label.text = info ?: "证书：读取失败（可能经过代理，或站点用了自签名证书）"
        }
    }

    /**
     * 单独发一次 HEAD 拿证书。
     *
     * WebView 不把证书暴露给调用方，只能自己再连一次。
     * 这次连接和页面本身可能走不同的链路（比如页面走了 GitHub 通道），
     * 所以拿不到时如实说「读不到」，不能假装站点没有证书。
     */
    private fun fetchCertificate(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "HEAD"
        conn.instanceFollowRedirects = true
        conn.connect()
        try {
            val cert = conn.serverCertificates.firstOrNull() as? X509Certificate
                ?: return@runCatching null
            val subject = cert.subjectDN.name.substringAfter("CN=", "").substringBefore(',')
            val issuer = cert.issuerDN.name.substringAfter("CN=", "").substringBefore(',')
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            "证书：${subject.ifBlank { "未知" }}　签发者：${issuer.ifBlank { "未知" }}\n" +
                "有效期至 ${fmt.format(cert.notAfter)}"
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    private fun zoomToProgress(zoom: Int): Int =
        ((zoom - MIN_ZOOM) * 100f / (MAX_ZOOM - MIN_ZOOM)).toInt().coerceIn(0, 100)

    private fun progressToZoom(progress: Int): Int {
        val raw = MIN_ZOOM + (MAX_ZOOM - MIN_ZOOM) * progress / 100f
        return ((raw / 5).toInt() * 5).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun toast(message: String) {
        if (isAdded) android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 200
    }
}
