package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.webkit.WebView
import java.util.UUID

/** 标签页模型 */
class Tab(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var url: String = "",
    val incognito: Boolean = false,
    var progress: Int = 0,
    var favicon: Bitmap? = null,
    /** 最近一次加载失败的原始 URL（展示错误页时保留） */
    var failedUrl: String? = null,
    /** 当前页面是否已整页翻译（用于菜单显示"还原原文"） */
    var translated: Boolean = false,
    /**
     * 首帧是否已经画到屏幕上。
     * 一旦为 true，就绝不能再因为「某些子资源没下完」而判定加载失败——
     * 用户已经在看内容了，换成错误页是纯粹的体验灾难。
     */
    var committed: Boolean = false,
    /** 本页是否已经注入过元素隐藏样式（避免同一页重复传几十 KB 的 CSS） */
    var adCssInjected: Boolean = false,
    /**
     * WebView 是否还活着。
     *
     * 标签被关掉后 WebView 会 destroy()，但界面上可能还留着它最后一帧画面。
     * 那种 WebView 再 loadUrl 是彻底没反应的——地址栏能打字、页面纹丝不动。
     * 所以销毁时打个标记，后面所有「拿当前标签去加载」的地方都先看这个标记。
     */
    var alive: Boolean = true,
) {
    lateinit var webView: WebView

    val isCreated: Boolean get() = ::webView.isInitialized

    /** 能拿去加载页面：既创建过，也没被销毁 */
    val usable: Boolean get() = isCreated && alive
}
