# 保留 @JavascriptInterface 方法（混淆会破坏 JS Bridge 反射调用）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# 保留原生插件入口类（外部 APK 经 DexClassLoader 反射加载）
-keep class * implements com.pluginbrowser.plugin.api.NativePlugin { *; }
