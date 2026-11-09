package com.pluginbrowser.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 元数据块解析：油猴脚本安装的地基，规则宽进严出 */
class UserScriptMetaTest {

    @Test
    fun `标准元数据块解析`() {
        val source = """
            // ==UserScript==
            // @name         网盘直链
            // @namespace    https://example.com/
            // @version      1.2.3
            // @author       张三
            // @description  解析直链
            // @match        *://*.pan.baidu.com/*
            // @match        https://www.alipan.com/*
            // @grant        GM_xmlhttpRequest
            // @grant        GM_setValue
            // @run-at       document-start
            // @require      https://cdn.example.com/jquery.min.js
            // @resource     style https://cdn.example.com/style.css
            // ==/UserScript==
            console.log(1);
        """.trimIndent()

        val meta = UserScriptMeta.parse(source)
        assertNotNull(meta)
        assertEquals("网盘直链", meta!!.name)
        assertEquals("1.2.3", meta.version)
        assertEquals("张三", meta.author)
        assertEquals(2, meta.matches.size)
        assertEquals(listOf("GM_xmlhttpRequest", "GM_setValue"), meta.grants)
        assertEquals("document-start", meta.runAt)
        assertEquals(listOf("https://cdn.example.com/jquery.min.js"), meta.requires)
        assertEquals(listOf<Pair<String, String>>("style" to "https://cdn.example.com/style.css"), meta.resources)
    }

    @Test
    fun `grant 为 none 时权限清空`() {
        val source = """
            // ==UserScript==
            // @name x
            // @grant        none
            // ==/UserScript==
        """.trimIndent()
        assertTrue(UserScriptMeta.parse(source)!!.grants.isEmpty())
    }

    @Test
    fun `没有元数据块返回 null`() {
        assertNull(UserScriptMeta.parse("var a = 1;"))
        assertNull(UserScriptMeta.parse("// 随便一行注释"))
    }

    @Test
    fun `只有元数据块但没有 name 也算有效（交给调用方兜底命名）`() {
        val source = """
            // ==UserScript==
            // @match *://example.com/*
            // ==/UserScript==
        """.trimIndent()
        val meta = UserScriptMeta.parse(source)
        assertNotNull(meta)
        assertEquals("", meta!!.name)
        assertEquals(listOf("*://example.com/*"), meta.matches)
    }

    @Test
    fun `非 http 的 require 不下载`() {
        val source = """
            // ==UserScript==
            // @name x
            // @require file:///etc/passwd
            // @require ./relative.js
            // @require https://cdn.example.com/lib.js
            // ==/UserScript==
        """.trimIndent()
        assertEquals(listOf("https://cdn.example.com/lib.js"), UserScriptMeta.parse(source)!!.requires)
    }

    @Test
    fun `include 转 match`() {
        assertEquals(
            "*://*.example.com/*",
            UserScriptMeta.includeToMatch("*.example.com/*"),
        )
        assertEquals("https://a.com/*", UserScriptMeta.includeToMatch("https://a.com/*"))
        // 正则形式的 include 转不了，宁缺勿滥
        assertNull(UserScriptMeta.includeToMatch("/\\.example\\.com\\//"))
    }

    @Test
    fun `id 从 namespace 主机名合成`() {
        val meta = UserScriptMeta.parse(
            """
            // ==UserScript==
            // @name 网盘直链
            // @namespace https://tools.example.com/userscript
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals("tools.example.com", UserScriptMeta.idFor(meta))
    }

    @Test
    fun `id 从 name 规范化并清洗非法字符`() {
        val meta = UserScriptMeta.parse(
            """
            // ==UserScript==
            // @name 我的天才脚本!! 2026
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals("2026", UserScriptMeta.idFor(meta).takeLast(4))
        assertTrue(UserScriptMeta.idFor(meta).all { it.isLetterOrDigit() || it in "._-" })
    }

    @Test
    fun `id 退化兜底`() {
        val meta = UserScriptMeta.parse(
            """
            // ==UserScript==
            // @name ───纯符号───
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals("script", UserScriptMeta.idFor(meta))
    }
}
