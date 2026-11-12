package com.pluginbrowser.gecko

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.pluginbrowser.R
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.util.UrlUtils
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * GeckoView 原型。
 *
 * 这个页面存在的唯一目的，是回答一个还没被回答过的问题：
 * 换成自己的内核之后，中文网页到底还能不能正常打开。
 *
 * 所以它刻意做得很小——一个地址栏、三个按钮、一个 GeckoView。
 * 不接书签、不接历史、不接标签管理、不接广告拦截。那些都在主线里，
 * 等这一个页面证明「能开、能滚、能后退」之后，才谈得上搬。
 *
 * 为什么不直接改 BrowserFragment：
 * 主线上那个 WebView 是所有功能的地基（TabManager、下载、长截图、注入脚本
 * 全挂在它身上）。地基换掉而新地基还没验证过，等于把能用的版本弄坏。
 * 分支 + 独立 Activity，坏也只坏这一个页面。
 */
class GeckoPrototypeActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backBtn: TextView
    private lateinit var forwardBtn: TextView
    private lateinit var engineLabel: TextView

    private var session: GeckoSession? = null
    private var canBack = false
    private var canForward = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gecko_proto)

        geckoView = findViewById(R.id.geo_view)
        address = findViewById(R.id.geo_address)
        progress = findViewById(R.id.geo_progress)
        titleView = findViewById(R.id.geo_title)
        backBtn = findViewById(R.id.geo_back)
        forwardBtn = findViewById(R.id.geo_forward)
        engineLabel = findViewById(R.id.geo_engine)

        wireToolbar()
        openSession()

        // 版本号直接打在界面上：截图发给别人时，对方一眼知道这是哪个内核。
        // 注意 GeckoView 不是独立安装的包，packageManager 查不到它——
        // 它就是打进 APK 里的一个库，所以这里只能跟 build.gradle.kts 里的版本手工对齐。
        engineLabel.text = "GeckoView $GECKO_VERSION"

        if (savedInstanceState == null) {
            load(intent?.dataString?.takeIf { it.isNotBlank() } ?: HOME)
        }
    }

    /* ---------------- 会话 ---------------- */

    private fun openSession() {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .build()
        val s = GeckoSession(settings)
        s.open(runtimeOf(this))

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?) {
                if (url.isNullOrBlank()) return
                // 用户正在输入时不抢焦点，否则地址栏每跳一次就把光标顶走
                if (!address.hasFocus()) {
                    address.setText(url)
                    address.setSelection(address.text.length)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                canBack = canGoBack
                backBtn.alpha = if (canGoBack) 1f else 0.3f
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                canForward = canGoForward
                forwardBtn.alpha = if (canGoForward) 1f else 0.3f
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progress.visibility = View.GONE
                if (!success) {
                    Toast.makeText(
                        this@GeckoPrototypeActivity,
                        "这个页面没打开：${address.text}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                titleView.text = title.orEmpty()
            }
        }

        geckoView.setSession(s)
        session = s
    }

    /* ---------------- 工具栏 ---------------- */

    private fun wireToolbar() {
        backBtn.setOnClickListener { if (canBack) session?.goBack() }
        forwardBtn.setOnClickListener { if (canForward) session?.goForward() }
        findViewById<TextView>(R.id.geo_reload).setOnClickListener { session?.reload() }

        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(address.text.toString())
                address.clearFocus()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canBack) session?.goBack() else finish()
            }
        })
    }

    private fun load(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val engine = SettingsRepository(this).searchEngine
        session?.loadUri(UrlUtils.normalize(input, engine))
    }

    override fun onDestroy() {
        session?.let {
            geckoView.setSession(null)
            runCatching { it.close() }
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val HOME = "https://www.bing.com"

        /** 与 app/build.gradle.kts 里的 geckoview 版本保持一致 */
        private const val GECKO_VERSION = "135"

        /**
         * GeckoRuntime 是进程级单例，重复 create 会让内核初始化两次（几秒钟）。
         * 这里手动缓存；进程被杀之后自然重建，不需要持久化。
         */
        @Volatile
        private var shared: GeckoRuntime? = null

        private fun runtimeOf(context: Context): GeckoRuntime =
            shared ?: synchronized(this) {
                shared ?: GeckoRuntime.create(
                    context.applicationContext,
                    GeckoRuntimeSettings.Builder()
                        .javaScriptEnabled(true)
                        .build(),
                ).also { shared = it }
            }
    }
}
