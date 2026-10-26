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

    var blockAds: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ADS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ADS, value).apply()

    var desktopUa: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_UA, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_UA, value).apply()

    /** system / light / dark */
    var theme: String
        get() = prefs.getString(KEY_THEME, "system")!!
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

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

        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_HOME_URL = "home_url"
        private const val KEY_JS = "javascript"
        private const val KEY_TPC = "third_party_cookies"
        private const val KEY_BLOCK_ADS = "block_ads"
        private const val KEY_DESKTOP_UA = "desktop_ua"
        private const val KEY_THEME = "theme"
    }
}
