package com.pluginbrowser.site

import android.content.Context

/**
 * 用户自己点出来的元素屏蔽规则。
 *
 * 和广告拦截（AdBlockEngine）分开：那是按订阅规则拦请求，这是「这个站的这块东西我不看」，
 * 按 host 存在本地，只影响当前站点。两者可以同时生效。
 *
 * 选择器按「最具体」存（带 nth-child），因为中文站点大量用哈希类名，
 * 只有标签名+结构位置才稳定。代价是站点改版后可能失效——失效只是不隐藏，不会误伤正文。
 */
class ElementBlocker(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectors(host: String): List<String> =
        prefs.getString(key(host), null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun add(host: String, selector: String): Boolean {
        if (host.isBlank() || selector.isBlank()) return false
        val current = selectors(host)
        if (selector in current) return false
        // 上限 60 条：再多说明这个站的 DOM 是每次刷新都变的，存了也没意义，
        // 还会让注入的 CSS 越滚越大
        val next = (current + selector).takeLast(MAX_RULES)
        prefs.edit().putString(key(host), next.joinToString("\n")).apply()
        return true
    }

    fun remove(host: String, selector: String) {
        val next = selectors(host) - selector
        prefs.edit().putString(key(host), next.joinToString("\n")).apply()
    }

    fun clear(host: String) {
        prefs.edit().remove(key(host)).apply()
    }

    fun count(host: String): Int = selectors(host).size

    /** 拼成一段 CSS；多条规则合成一个选择器组，只注入一次 */
    fun cssFor(host: String): String? {
        val list = selectors(host)
        if (list.isEmpty()) return null
        val safe = list.filter { !it.contains('{') && !it.contains('}') && it.length < 300 }
        if (safe.isEmpty()) return null
        return safe.joinToString(",") + "{display:none !important;}"
    }

    private fun key(host: String) = "block.$host"

    companion object {
        private const val PREFS = "pb_element_block"
        private const val MAX_RULES = 60
    }
}
