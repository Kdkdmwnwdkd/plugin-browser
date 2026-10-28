package com.pluginbrowser.compat

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository

/**
 * 浏览器内核体检。
 *
 * 存在的理由很实在：Kite 只是一层壳，网页长什么样由手机里的 WebView 决定，
 * 而 WebView 跟着系统走、我升不了。以前每次出问题我都只能靠猜——「你那边内核可能是旧的吧」，
 * 猜错了还耽误事。这个面板把该问的机器自己答了：内核哪个版本、支持哪些新特性、
 * 厂商有没有已知坑，最后拼成一段文本，用户复制发过来，我这边就不用再猜。
 *
 * 不做任何行为改动，纯读取 + 一次性的能力探测。
 */
class EngineSheet : BottomSheetDialogFragment() {

    private var probeWebView: WebView? = null
    private var probing = false

    /** 探测结果，跑过一次就留着，重复打开不用重跑 */
    private var probes: List<Pair<String, String>>? = null

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_engine, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.engine_copy).setOnClickListener { copyReport() }
        view.findViewById<MaterialButton>(R.id.engine_hide).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.engine_probe).setOnClickListener { runProbe() }
        render()
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        // 高度要按「窗口可用高度」算，不能用 displayMetrics.heightPixels。
        //
        // 后者是物理屏高，含状态栏和导航栏。这块屏物理 1280、应用窗口只有 1048，
        // 按 0.9 算出来 1152 直接超出可见区，底部那排按钮被推到屏幕外（bounds 全是 0，点不着）。
        // 父容器就是 CoordinatorLayout，它的高度才是真正能用的空间。
        sheet.post {
            val avail = (sheet.parent as? View)?.height?.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val target = (avail * 0.92f).toInt()
            sheet.layoutParams = sheet.layoutParams.apply { height = target }
            // 根是 match_parent + 内部 ScrollView 带 weight=1，BottomSheet 会用无限高去测它，
            // weight 分配不到空间，ScrollView 按内容全高撑开，同样会把按钮顶出屏幕。
            view?.layoutParams = view?.layoutParams?.apply { height = target }
        }
    }

    override fun onDestroyView() {
        releaseProbeWebView()
        super.onDestroyView()
    }

    /* ---------------- 取环境 ---------------- */

    private data class Snapshot(
        val enginePackage: String?,
        val engineVersion: String?,
        val engineMajor: Int,
        val grade: EngineInfo.Grade,
        val vendor: EngineInfo.Vendor,
        val ua: String,
        val freeMemMb: Long,
        val totalMemMb: Long,
    )

    private fun snapshot(): Snapshot {
        val ctx = requireContext().applicationContext
        val pkg = currentWebViewPackage()
        val version = pkg?.versionName
        val major = EngineInfo.parseMajor(version)
        val ua = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx) }
            .getOrDefault("(读取失败)")

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        val totalMb = runCatching {
            val f = java.io.File("/proc/meminfo")
            f.readLines().firstOrNull { it.startsWith("MemTotal") }
                ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: 0L
        }.getOrDefault(0L)

        return Snapshot(
            enginePackage = pkg?.packageName,
            engineVersion = version,
            engineMajor = major,
            grade = EngineInfo.gradeOf(major, pkg?.packageName),
            vendor = EngineInfo.vendorOf(Build.BRAND, Build.MANUFACTURER),
            ua = ua,
            freeMemMb = if (mi.availMem > 0) mi.availMem / 1024 / 1024 else 0,
            totalMemMb = totalMb,
        )
    }

    /**
     * 系统 WebView 的包信息。
     * API 26 起有官方入口；24/25 只能反射 WebViewFactory，拿不到就当没有，
     * 不要去猜一个版本号——猜错了比不显示更糟。
     */
    private fun currentWebViewPackage(): PackageInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        }
        return runCatching {
            val cls = Class.forName("android.webkit.WebViewFactory")
            val m = cls.getDeclaredMethod("getLoadedPackageInfo")
            m.isAccessible = true
            m.invoke(null) as? PackageInfo
        }.getOrNull()
    }

    /**
     * 自己的版本号。
     *
     * 不用 BuildConfig：AGP 8 起默认不生成这个类，为了两行字符串去打开 buildConfig
     * 会改变整个构建产物结构。从 PackageManager 读一样准，而且拿的是真正装上的那个版本。
     */
    private fun selfVersion(): String = runCatching {
        val ctx = requireContext()
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val name = pi.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "$name（$code）"
    }.getOrDefault("读取失败")

    /* ---------------- 渲染 ---------------- */

    private fun render() {
        val root = view?.findViewById<LinearLayout>(R.id.engine_content) ?: return
        root.removeAllViews()
        val snap = snapshot()

        view?.findViewById<TextView>(R.id.engine_subtitle)?.text =
            "${snap.vendor.label} · Android ${Build.VERSION.RELEASE} · 内核 ${snap.engineMajor}"

        /* 结论 */
        val head = TextView(requireContext()).apply {
            text = EngineInfo.headline(snap.grade)
            setTextColor(gradeColor(snap.grade))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(head)
        root.addView(
            TextView(requireContext()).apply {
                text = EngineInfo.action(snap.grade)
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(dp(3).toFloat(), 1f)
            },
        )

        /* 引擎 */
        root.addView(sectionTitle("网页内核"))
        row(root, "提供者", snap.enginePackage ?: "未找到")
        row(root, "版本", snap.engineVersion ?: "未知")
        row(root, "主版本", if (snap.engineMajor > 0) "${snap.engineMajor}" else "无法识别")
        // UA 不截断：它是判断「内核自称什么」的唯一原始凭证，截在中间反而会误导——
        // 之前截 150 字，正好切在 "Chrome/8"，看着像 Chrome 8。
        row(root, "标识", snap.ua)

        /* 系统 */
        root.addView(sectionTitle("这台机器"))
        row(root, "厂商", "${Build.MANUFACTURER} / ${Build.BRAND}")
        row(root, "机型", Build.MODEL)
        row(root, "系统", "Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        val display = Build.DISPLAY.orEmpty()
        if (display.isNotBlank()) row(root, "版本串", display)
        row(root, "架构", Build.SUPPORTED_ABIS.joinToString(", "))
        if (snap.totalMemMb > 0) {
            row(root, "内存", "可用 ${snap.freeMemMb}M / 共约 ${snap.totalMemMb}M")
        }

        /* 应用 */
        root.addView(sectionTitle("青鸢 Kite"))
        val self = selfVersion()
        row(root, "版本", self)
        row(root, "目标 SDK", "${requireContext().applicationInfo.targetSdkVersion}")
        val s = SettingsRepository(requireContext())
        row(root, "当前 UA", if (s.desktopUa) "桌面版" else "移动版")
        row(root, "JavaScript", if (s.javascriptEnabled) "已开启" else "已关闭")

        /* 实测 */
        root.addView(sectionTitle(if (probes == null) "实际能力（点下方按钮跑一次）" else "实际能力"))
        val list = probes
        if (list == null) {
            root.addView(
                TextView(requireContext()).apply {
                    text = "还没测。版本号只能说明内核自称是谁，实测才知道它真支持什么——" +
                        "有些 ROM 会把 UA 里的 Chrome 版本改高，光看数字会误判。"
                    setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
            )
        } else {
            val failed = EngineInfo.failedProbes(list)
            root.addView(
                TextView(requireContext()).apply {
                    // 显示中文名，不要露 structuredClone 这种内部键——用户看不懂，
                    // 复制出去的报��也读不懂。
                    text = if (failed.isEmpty()) {
                        "全部通过，这台机器的内核对现代网页是完整的。"
                    } else {
                        "缺 ${failed.size} 项：" + failed.joinToString("、") { PROBE_LABEL[it] ?: it }
                    }
                    setTextColor(
                        if (failed.isEmpty()) gradeColor(EngineInfo.Grade.GOOD)
                        else gradeColor(EngineInfo.Grade.OLD),
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, 0, 0, dp(4))
                },
            )
            list.forEach { (k, v) ->
                row(root, PROBE_LABEL[k] ?: k, if (v == "true") "✓" else "✗")
            }
        }

        /* 厂商提示 */
        val notes = EngineInfo.notesFor(snap.vendor, snap.grade)
        if (notes.isNotEmpty()) {
            root.addView(sectionTitle("这个品牌要知道的"))
            notes.forEach { n ->
                root.addView(
                    TextView(requireContext()).apply {
                        text = "· $n"
                        setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setLineSpacing(dp(3).toFloat(), 1f)
                        setPadding(0, dp(2), 0, dp(2))
                    },
                )
            }
        }
    }

    private fun gradeColor(grade: EngineInfo.Grade): Int = when (grade) {
        EngineInfo.Grade.GOOD -> 0xFF1E8E3E.toInt()
        EngineInfo.Grade.FAIR -> 0xFFB26A00.toInt()
        EngineInfo.Grade.OLD -> 0xFFC5221F.toInt()
        EngineInfo.Grade.ANCIENT, EngineInfo.Grade.MISSING -> 0xFFC5221F.toInt()
    }

    /* ---------------- 能力探测 ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun runProbe() {
        if (probing) return
        val host = view?.findViewById<FrameLayout>(R.id.engine_probe_host) ?: return
        probing = true
        probes = null
        render()
        // 给一个立刻可见的反馈：探测要走一趟 WebView 加载，几百毫秒到两秒都有可能，
        // 没有反馈的话用户会以为按钮没点着。
        Toast.makeText(requireContext(), "正在检测…", Toast.LENGTH_SHORT).show()

        releaseProbeWebView()
        val wv = WebView(requireContext())
        probeWebView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.domStorageEnabled = false
        wv.setBackgroundColor(0x00000000)
        wv.alpha = 0f
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(PROBE_JS) { raw ->
                    probing = false
                    probes = EngineInfo.parseProbe(raw)
                    if (isAdded) {
                        render()
                        if (probes == null) {
                            Toast.makeText(requireContext(), "没拿到结果，内核可能太旧", Toast.LENGTH_SHORT).show()
                        }
                    }
                    releaseProbeWebView()
                }
            }
        }
        host.addView(wv, ViewGroup.LayoutParams(1, 1))
        // 空白页即可，探测只用到 eval / CSS.supports / canvas
        wv.loadData("<html><body></body></html>", "text/html", "utf-8")
        // 兜底：某些 ROM 的 WebView 会因为省电策略或者内核异常一直不回调 onPageFinished，
        // 那样 probing 会永远为 true，按钮从此点不动。到点就放开，让用户能重试。
        host.postDelayed({
            if (probing) {
                probing = false
                if (isAdded) {
                    render()
                    Toast.makeText(requireContext(), "检测超时，可以再试一次", Toast.LENGTH_SHORT).show()
                }
                releaseProbeWebView()
            }
        }, 8000)
    }

    private fun releaseProbeWebView() {
        val wv = probeWebView ?: return
        probeWebView = null
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
    }

    /* ---------------- 复制 ---------------- */

    private fun copyReport() {
        val snap = snapshot()
        val sb = StringBuilder()
        sb.appendLine("【青鸢 Kite 环境体检】")
        sb.appendLine("Kite ${selfVersion()}")
        sb.appendLine("机型 ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统 Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        sb.appendLine("版本串 ${Build.DISPLAY}")
        sb.appendLine("架构 ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("内核提供者 ${snap.enginePackage ?: "无"}")
        sb.appendLine("内核版本 ${snap.engineVersion ?: "未知"} (主版本 ${snap.engineMajor})")
        sb.appendLine("判定 ${snap.grade.name}")
        if (snap.totalMemMb > 0) sb.appendLine("内存 可用 ${snap.freeMemMb}M / 共 ${snap.totalMemMb}M")
        val list = probes
        if (list != null) {
            val failed = EngineInfo.failedProbes(list)
            sb.appendLine(
                "实测 " + if (failed.isEmpty()) {
                    "全部通过"
                } else {
                    "缺 " + failed.joinToString("、") { PROBE_LABEL[it] ?: it }
                },
            )
        } else {
            sb.appendLine("实测 未运行")
        }
        sb.append("UA ").appendLine(snap.ua)

        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kite-env", sb.toString()))
        Toast.makeText(requireContext(), "已复制，发给我就行", Toast.LENGTH_SHORT).show()
    }

    /* ---------------- 小工具 ---------------- */

    private fun sectionTitle(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.06f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun row(parent: LinearLayout, label: String, value: String) {
        val ctx = requireContext()
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(4), 0, dp(4))
        }
        line.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                width = dp(96)
                // 标签列不能折行：折了之后「深拷贝」会断成「深拷 / 贝」，
                // 右列对不上，看着像排版坏了。超出就省略，反正下面有中文全名。
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        line.addView(
            TextView(ctx).apply {
                text = value
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        parent.addView(line)
    }

    private fun colorOf(attr: Int): Int {
        val tv = TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, tv, true)) tv.data else 0xFF888888.toInt()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TAG = "engine_check"

        private val PROBE_LABEL = mapOf(
            "nullish" to "空值合并",
            "classField" to "类字段",
            "optionalChain" to "可选链",
            "bigint" to "BigInt",
            "structuredClone" to "深拷贝",
            "flexGap" to "flex gap",
            "aspectRatio" to "宽高比",
            "isSelector" to ":is()",
            "backdropFilter" to "背景模糊",
            "dvh" to "100dvh",
            "containerQuery" to "容器查询",
            "webgl2" to "WebGL2",
        )

        /**
         * 每项单独 try，任一项语法不被支持也不影响其余项。
         * 全部用 function(){} 而不是箭头函数，避免脚本自身在旧内核上解析失败。
         */
        private const val PROBE_JS = """(function(){
var r=[];
function ok(k,f){var v=false;try{v=!!f()}catch(e){v=false}r.push(k+'='+v)}
ok('nullish',function(){return eval('(function(){return (null??1)===1})()')});
ok('classField',function(){return eval('(function(){class A{static x=1}return A.x===1})()')});
ok('optionalChain',function(){return eval('(function(){var o={};return o.a?.b===undefined})()')});
ok('bigint',function(){return typeof BigInt!=='undefined'});
ok('structuredClone',function(){return typeof structuredClone!=='undefined'});
ok('flexGap',function(){return CSS.supports('gap','1px')});
ok('aspectRatio',function(){return CSS.supports('aspect-ratio','1/1')});
ok('isSelector',function(){return CSS.supports('selector(:is(a))')});
ok('backdropFilter',function(){return CSS.supports('backdrop-filter','blur(1px)')});
ok('dvh',function(){return CSS.supports('height','100dvh')});
ok('containerQuery',function(){return CSS.supports('container-type','inline-size')});
ok('webgl2',function(){var c=document.createElement('canvas');return !!(c.getContext&&c.getContext('webgl2'))});
return r.join(';')})()"""
    }
}
