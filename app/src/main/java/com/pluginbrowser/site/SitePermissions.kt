package com.pluginbrowser.site

import android.content.Context

/**
 * 站点权限记忆。
 *
 * 为什么必须按站点存：网页要的权限和 App 不是一回事。
 * 用户允许了 A 站用相机，不代表愿意让 B 站也能开摄像头；
 * 反过来一次拒绝如果不记住，下次进同一个页面又弹一次，弹三次人就会把浏览器卸了。
 */
enum class PermKind(val title: String, val detail: String) {
    CAMERA("相机", "扫码、拍照上传、视频通话"),
    MIC("麦克风", "语音输入、语音通话"),
    LOCATION("位置", "地图、附近门店、本地天气"),
    NOTIFICATION("通知", "网页消息提醒"),
}

class SitePermissions(context: Context) {

    enum class Decision { ASK, ALLOW, DENY }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun decision(host: String, kind: PermKind): Decision {
        val raw = prefs.getString("$host|${kind.name}", null) ?: return Decision.ASK
        return runCatching { Decision.valueOf(raw) }.getOrDefault(Decision.ASK)
    }

    fun set(host: String, kind: PermKind, decision: Decision) {
        prefs.edit().putString("$host|${kind.name}", decision.name).apply()
    }

    fun clearHost(host: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$host|") }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** 诊断/站点信息面板用：这个站有哪些非默认设置 */
    fun summary(host: String): String {
        val items = PermKind.entries.mapNotNull { kind ->
            when (decision(host, kind)) {
                Decision.ASK -> null
                Decision.ALLOW -> "${kind.title}允许"
                Decision.DENY -> "${kind.title}拒绝"
            }
        }
        return if (items.isEmpty()) "全部询问" else items.joinToString(" · ")
    }

    private companion object {
        const val PREFS = "pb_site_perms"
    }
}
