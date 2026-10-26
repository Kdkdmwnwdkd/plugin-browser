package com.pluginbrowser.sample

import android.app.Activity
import android.os.Bundle

/**
 * 原生插件入口 Activity。
 * 它没有真实界面，唯一作用是让宿主 App 通过
 * Intent("com.pluginbrowser.plugin.NATIVE") 发现本插件 APK，
 * 并从 meta-data 中读取插件实现类的全名。
 */
class PluginEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
