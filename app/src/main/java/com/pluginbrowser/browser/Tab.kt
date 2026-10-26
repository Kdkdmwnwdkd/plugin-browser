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
) {
    lateinit var webView: WebView

    val isCreated: Boolean get() = ::webView.isInitialized
}
