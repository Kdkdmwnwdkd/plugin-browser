package com.pluginbrowser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** 浏览器设置仓库（SharedPreferences） */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pb_settings", Context.MODE_PRIVATE)

    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, "baidu")!!
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()

    var homeUrl: String
        get() = prefs.getString(KEY_HOME_URL, DEFAULT_HOME)!!
        set(value) = prefs.edit().putString(KEY_HOME_URL, value.ifBlank { DEFAULT_HOME }).apply()

    var javascriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS, true)
        set(value) = prefs.edit().putBoolean(KEY_JS, value).apply()

    var thirdPartyCookies: Boolean
        get() = prefs.getBoolean(KEY_TPC, false)
        set(value) = prefs.edit().putBoolean(KEY_TPC, value).apply()

    var desktopUa: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_UA, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_UA, value).apply()

    /** system / light / dark */
    var theme: String
        get() = prefs.getString(KEY_THEME, "system")!!
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** 浏览器标识：default / quark / baidu。部分站点只对特定浏览器返回直链 */
    var uaPreset: String
        get() = prefs.getString(KEY_UA_PRESET, "default")!!
        set(value) = prefs.edit().putString(KEY_UA_PRESET, value).apply()

    /* ---------------- 手势 ---------------- */

    var gestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_ENABLED, value).apply()

    /** 左边缘向右滑触发的动作 */
    var gestureLeftEdge: String
        get() = prefs.getString(KEY_GESTURE_LEFT, "back")!!
        set(value) = prefs.edit().putString(KEY_GESTURE_LEFT, value).apply()

    /** 右边缘向左滑触发的动作 */
    var gestureRightEdge: String
        get() = prefs.getString(KEY_GESTURE_RIGHT, "forward")!!
        set(value) = prefs.edit().putString(KEY_GESTURE_RIGHT, value).apply()

    /** 双击页面触发的动作，默认关闭（会影响页面内选词） */
    var gestureDoubleTap: String
        get() = prefs.getString(KEY_GESTURE_DOUBLE, "none")!!
        set(value) = prefs.edit().putString(KEY_GESTURE_DOUBLE, value).apply()

    /** 边缘判定宽度（dp）。ListPreference 存的是字符串，这里统一按字符串读 */
    var gestureEdgeWidthDp: Int
        get() = prefs.getString(KEY_GESTURE_EDGE_WIDTH, null)?.toIntOrNull() ?: 22
        set(value) = prefs.edit().putString(KEY_GESTURE_EDGE_WIDTH, value.toString()).apply()

    /** 横向滑出距离（dp） */
    var gestureDistanceDp: Int
        get() = prefs.getString(KEY_GESTURE_DISTANCE, null)?.toIntOrNull() ?: 72
        set(value) = prefs.edit().putString(KEY_GESTURE_DISTANCE, value.toString()).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    companion object {
        const val DEFAULT_HOME = "file:///android_asset/home.html"

        const val UA_QUARK =
            "Mozilla/5.0 (Linux; U; Android 13; zh-CN; SM-G9910 Build/TP1A.220624.014) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/111.0.0.0 " +
                "Mobile Safari/537.36 Quark/6.9.2.360"

        const val UA_BAIDU =
            "Mozilla/5.0 (Linux; Android 13; SM-G9910 Build/TP1A.220624.014; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/111.0.0.0 " +
                "Mobile Safari/537.36 baiduboxapp/13.42.0.10"

        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_HOME_URL = "home_url"
        private const val KEY_JS = "javascript"
        private const val KEY_TPC = "third_party_cookies"
        private const val KEY_DESKTOP_UA = "desktop_ua"
        private const val KEY_THEME = "theme"
        private const val KEY_UA_PRESET = "ua_preset"
        private const val KEY_GESTURE_ENABLED = "gesture_enabled"
        private const val KEY_GESTURE_LEFT = "gesture_left_edge"
        private const val KEY_GESTURE_RIGHT = "gesture_right_edge"
        private const val KEY_GESTURE_DOUBLE = "gesture_double_tap"
        private const val KEY_GESTURE_EDGE_WIDTH = "gesture_edge_width"
        private const val KEY_GESTURE_DISTANCE = "gesture_distance"
    }
}
