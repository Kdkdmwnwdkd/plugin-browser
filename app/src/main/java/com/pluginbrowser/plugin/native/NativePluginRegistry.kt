package com.pluginbrowser.plugin.native

import android.content.Context
import com.pluginbrowser.plugin.api.NativePlugin

/** 内置原生插件注册表 */
object NativePluginRegistry {

    fun create(context: Context): List<NativePlugin> = listOf(
        AdBlockPlugin(context),
    )
}
