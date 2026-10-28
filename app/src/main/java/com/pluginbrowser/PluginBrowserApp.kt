package com.pluginbrowser

import android.app.Application
import android.content.Context
import com.pluginbrowser.data.SettingsRepository
import com.pluginbrowser.download.DownloadTracker
import com.pluginbrowser.net.GitHubChannel
import com.pluginbrowser.plugin.PluginManager
import com.pluginbrowser.util.CrashLogger
import com.pluginbrowser.util.EventLog
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
        // 事件时间线必须最先起：后面任何一步出问题，都得有人记下来
        EventLog.init(this)
        EventLog.startHeartbeat()
        CrashLogger.install(this)
        pluginManager = PluginManager(this, appScope)
        gitHubChannel = GitHubChannel(this, SettingsRepository(this))
        // 发现已安装的外部原生插件 APK
        pluginManager.loadExternalNativePlugins()
        // 注册下载完成广播跟踪
        DownloadTracker.get(this).register()
        // 启动即预热 GitHub 通道：DNS 和 TLS 先行，用户真去点的时候就不用再等这一两秒
        gitHubChannel.warmUp()
        EventLog.log("APP", "init done, warmUp dispatched")
    }

    /**
     * 系统内存压力回调。
     *
     * 这是「卡退」最直接的线索来源：LMK 动手之前一定会先走几轮 trim，
     * 等级一路往上爬（RUNNING_LOW → RUNNING_CRITICAL → COMPLETE），
     * 等真被杀掉时，日志里就有完整的压力爬升曲线。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val name = when (level) {
            TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            TRIM_MEMORY_MODERATE -> "MODERATE"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }
        EventLog.log("TRIM", name)
    }

    companion object {
        private lateinit var instance: PluginBrowserApp

        fun from(context: Context): PluginBrowserApp = instance

        /** 应用级协程作用域：插件加载、JS Bridge 网络请求等后台任务 */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
