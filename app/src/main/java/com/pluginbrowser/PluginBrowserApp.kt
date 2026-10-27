package com.pluginbrowser

import android.app.Application
import android.content.Context
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.download.DownloadTracker
import com.pluginbrowser.net.GitHubChannel
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 插件浏览器 Application。
 * 全局持有插件管理器（双运行时）与应用级协程作用域。
 */
class PluginBrowserApp : Application() {

    lateinit var pluginManager: PluginManager
        private set

    /** GitHub 专用通道：自定义 DNS / 代理，全局一份（连接池和缓存要复用） */
    lateinit var gitHubChannel: GitHubChannel
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
        pluginManager = PluginManager(this, appScope)
        gitHubChannel = GitHubChannel(this, SettingsRepository(this))
        // 发现已安装的外部原生插件 APK
        pluginManager.loadExternalNativePlugins()
        // 注册下载完成广播跟踪
        DownloadTracker.get(this).register()
    }

    companion object {
        private lateinit var instance: PluginBrowserApp

        fun from(context: Context): PluginBrowserApp = instance

        /** 应用级协程作用域：插件加载、JS Bridge 网络请求等后台任务 */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
