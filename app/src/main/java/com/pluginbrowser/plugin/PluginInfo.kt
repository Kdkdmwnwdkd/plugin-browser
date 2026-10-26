package com.pluginbrowser.plugin

import com.pluginbrowser.plugin.api.NativePlugin

/**
 * 插件信息（UI 层统一视图）。
 * JS 插件与原生插件都归一化为该结构展示。
 */
data class PluginInfo(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val nativePlugin: NativePlugin? = null,
    /** 来自外部 APK 安装 */
    val external: Boolean = false,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
}
