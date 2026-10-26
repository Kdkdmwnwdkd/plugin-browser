package com.pluginbrowser.plugin.native

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.pluginbrowser.plugin.api.NativePlugin
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 外部原生插件发现与加载。
 *
 * 插件以独立 APK 形式发布（参考 sample-plugin 模块）：
 *  - 声明一个入口 Activity，action = com.pluginbrowser.plugin.NATIVE；
 *  - 通过 meta-data "com.pluginbrowser.plugin.ENTRY" 指定 NativePlugin 实现类全名；
 *  - 插件 APK 以 compileOnly 依赖 plugin-api，运行时不打包接口类，由宿主提供。
 *
 * 加载方式：DexClassLoader + 宿主类加载器为父加载器，因此插件可以引用宿主提供的接口。
 */
object ExternalPluginLoader {

    const val ACTION = "com.pluginbrowser.plugin.NATIVE"
    const val META_ENTRY = "com.pluginbrowser.plugin.ENTRY"

    private const val TAG = "PB/ExternalPlugin"

    fun discover(context: Context, parentLoader: ClassLoader): List<NativePlugin> {
        val pm = context.packageManager
        val infos = try {
            pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities failed", e)
            emptyList()
        }
        val result = mutableListOf<NativePlugin>()
        val dexDir = File(context.codeCacheDir, "pb_dx").apply { if (!exists()) mkdirs() }

        for (ri in infos) {
            try {
                val activityInfo = ri.activityInfo ?: continue
                val entry = activityInfo.metaData?.getString(META_ENTRY) ?: continue
                val appInfo = activityInfo.applicationInfo ?: continue
                val apkPath = appInfo.sourceDir ?: continue
                // 跳过宿主自身
                if (apkPath == context.applicationInfo.sourceDir) continue

                val loader = DexClassLoader(apkPath, dexDir.absolutePath, null, parentLoader)
                val clazz = loader.loadClass(entry)
                val plugin = clazz.getDeclaredConstructor().newInstance() as? NativePlugin
                if (plugin != null) {
                    result.add(plugin)
                    Log.i(TAG, "loaded native plugin: ${plugin.metadata.id} from ${appInfo.packageName}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "load plugin apk failed: ${ri.activityInfo?.packageName}", e)
            }
        }
        return result
    }
}
