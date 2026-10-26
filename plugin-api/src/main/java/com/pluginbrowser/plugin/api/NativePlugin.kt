package com.pluginbrowser.plugin.api

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView

/**
 * 插件元数据。所有插件（JS / 原生 / 外部 APK）都归一化成该结构供 UI 展示。
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** 权限声明，如 "storage" / "network" / "contentScript" */
    val permissions: List<String> = emptyList(),
    /** true 表示随 App 内置，不可卸载（只能禁用） */
    val builtin: Boolean = false,
    /** 图标（绘制资源 id），宿主可选用 */
    val iconRes: Int = 0,
)

/** 插件贡献给浏览器菜单的菜单项 */
data class PluginMenuItem(
    val id: String,
    val title: String,
    /** 排序权重，越小越靠前 */
    val order: Int = 100,
)

/**
 * 浏览器宿主能力：原生插件通过它与浏览器交互。
 * 由宿主 App 在加载插件时注入。
 */
interface BrowserHost {
    /** 当前标签页 URL */
    val currentUrl: String?

    /** 新开标签页并加载 URL */
    fun openTab(url: String, incognito: Boolean = false)

    /** 弹出 Toast 提示 */
    fun showToast(message: String)

    /** 读取浏览器设置 */
    fun getSetting(key: String, default: String? = null): String?

    /** 当前是否处于无痕模式 */
    val isIncognito: Boolean
}

/**
 * 原生插件接口。
 *
 * 原生插件有三种来源：
 *  1. 宿主 App 内置（NativePluginRegistry 注册）；
 *  2. 以独立 APK 形式发布，通过 Intent Action `com.pluginbrowser.plugin.NATIVE` 被宿主发现，
 *     由 DexClassLoader 动态加载（参考 sample-plugin 模块）；
 *  3. 源码集成：fork 本项目后在 NativePluginRegistry 中追加。
 *
 * 所有回调均在 UI 线程调用；shouldInterceptRequest 可能被 WebView 高频调用，
 * 实现时请做好性能与线程安全。
 */
interface NativePlugin {

    val metadata: PluginMetadata

    /** 插件被加载（安装/启用）时调用 */
    fun onLoad(host: BrowserHost) {}

    /** 插件被卸载/禁用时调用 */
    fun onUnload() {}

    /**
     * 拦截网络请求。返回非 null 则以该响应替代原请求（可用于广告拦截等）。
     * 仅在 API 24+ 可用（本项目 minSdk 即 24）。
     */
    fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null

    /** 页面开始加载 */
    fun onPageStarted(view: WebView, url: String) {}

    /** 页面加载完成 */
    fun onPageFinished(view: WebView, url: String) {}

    /**
     * 处理下载请求。返回 true 表示已自行处理，浏览器不再接管。
     */
    fun onDownloadStart(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    ): Boolean = false

    /** 贡献菜单项（显示在浏览器溢出菜单中） */
    fun menuItems(): List<PluginMenuItem> = emptyList()

    /** 菜单项被点击。返回 true 表示已消费 */
    fun onMenuItemClicked(item: PluginMenuItem): Boolean = false
}
