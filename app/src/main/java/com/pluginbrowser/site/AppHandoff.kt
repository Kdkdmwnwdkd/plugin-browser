package com.pluginbrowser.site

import android.content.Context

/**
 * 外部 App 跳转的记账本。
 *
 * 解决两件以前没管的事：
 * 1. 网页一点就跳走、回来还得重新找位置 —— 现在先问「在 App 打开还是留在网页」，
 *    并可按站点记住，下次不再问；
 * 2. 跳出去再回来，用户常常忘了自己刚才在干什么 —— 回来时给一条提示，能一键再打开。
 *
 * 只记最近一次，且有时效（见 FRESH_MS）：早上跳走的 App，晚上回来不该再弹提示。
 */
class AppHandoff(context: Context) {

    enum class Choice {
        ASK, APP, WEB;

        companion object {
            fun of(raw: String?): Choice = when (raw) {
                "app" -> APP
                "web" -> WEB
                else -> ASK
            }
        }
    }

    data class Last(val url: String, val host: String, val at: Long)

    private val prefs =
        context.applicationContext.getSharedPreferences("pb_handoff", Context.MODE_PRIVATE)

    /** 这个站点选过什么；没选过就是 ASK */
    fun choice(host: String?): Choice {
        if (host.isNullOrBlank()) return Choice.ASK
        return Choice.of(prefs.getString("host.$host", null))
    }

    fun setChoice(host: String, choice: Choice) {
        if (host.isBlank() || choice == Choice.ASK) return
        prefs.edit().putString("host.$host", if (choice == Choice.APP) "app" else "web").apply()
    }

    fun forgetChoice(host: String) {
        prefs.edit().remove("host.$host").apply()
    }

    fun remember(url: String, host: String) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_HOST, host)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 取「刚刚跳出去过」的记录。
     *
     * 取完即清：同一次跳转只该提示一次，否则每次 onResume 都弹一条，
     * 用户会以为界面在抽风。
     */
    fun takeFresh(now: Long = System.currentTimeMillis()): Last? {
        val url = prefs.getString(KEY_URL, null)
        val host = prefs.getString(KEY_HOST, null)
        val at = prefs.getLong(KEY_AT, 0L)
        if (url.isNullOrBlank() || at <= 0L) return null
        prefs.edit().remove(KEY_URL).remove(KEY_HOST).remove(KEY_AT).apply()
        if (now - at > FRESH_MS || now < at) return null
        return Last(url, host.orEmpty(), at)
    }

    fun clear() {
        prefs.edit().remove(KEY_URL).remove(KEY_HOST).remove(KEY_AT).apply()
    }

    companion object {
        /** 两分钟内算「刚刚」：足够覆盖切出去看一眼再回来的场景 */
        const val FRESH_MS = 120_000L
        private const val KEY_URL = "last_url"
        private const val KEY_HOST = "last_host"
        private const val KEY_AT = "last_at"
    }
}
