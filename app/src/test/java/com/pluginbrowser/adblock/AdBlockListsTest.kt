package com.pluginbrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 订阅名单编译与用户规则分类：只有「能落进域名词表」的行才有资格进引擎 */
class AdBlockListsTest {

    @Test
    fun `订阅名单只保留域名行`() {
        val text = """
            ! EasyList 注释行
            # 另一种注释
            ads.example.com
            ||tracker.net^
            @@||safe.org^
            /banner/ads/
            ##.ad-banner
            fonts.googleapis.com${'$'}script
            0.0.0.0 malware.cn
            127.0.0.1 tracker2.cn
            ==UserScript==
        """.trimIndent()

        val kept = AdBlockLists.compileFilter(text)
        // hosts 风格的 IP 前缀行：取域名后仍可保留（127.0.0.1/0.0.0.0 是惯例）
        assertEquals(
            listOf(
                "ads.example.com",
                "||tracker.net^",
                "@@||safe.org^",
                "malware.cn",
                "tracker2.cn",
            ),
            kept,
        )
    }

    @Test
    fun `含路径或通配的行不进订阅产物`() {
        val text = """
            ||cdn.tracker.io/path/
            *.wildcard.com
            -ad-banner.
        """.trimIndent()
        assertTrue(AdBlockLists.compileFilter(text).isEmpty())
    }

    @Test
    fun `用户规则分类`() {
        assertEquals(false to "ads.example.com", AdBlockLists.classify("||ads.example.com^"))
        assertEquals(false to "ads.example.com", AdBlockLists.classify("ads.example.com"))
        assertEquals(true to "my-bank.com", AdBlockLists.classify("@@||my-bank.com^"))
        assertEquals(true to "my-bank.com", AdBlockLists.classify("@@my-bank.com"))
        assertNull(AdBlockLists.classify("! 注释"))
        assertNull(AdBlockLists.classify(""))
        assertNull(AdBlockLists.classify("||*.wildcard^"))
        // 通用 URL 模式放行给引擎正则层
        assertEquals(false to "/banner/", AdBlockLists.classify("/banner/"))
    }

    @Test
    fun `引擎加载用户规则后按语义拦截`() {
        val engine = AdBlockEngine(null)
        engine.parseUserRules(
            sequenceOf("||ads.example.com^", "@@||my-bank.com^", "/tracker.gif"),
        )
        engine.markReady()

        assertTrue(engine.shouldBlock("https://ads.example.com/pix.gif", "https://news.com"))
        assertFalse(engine.shouldBlock("https://ads.my-bank.com/x", "https://news.com"))
        assertTrue(engine.shouldBlock("https://cdn.example.com/tracker.gif", "https://news.com"))
        // 没写进规则的域名照常放行
        assertFalse(engine.shouldBlock("https://normal.com/a.png", "https://news.com"))
    }

    @Test
    fun `订阅域名与内置白名单叠加`() {
        val engine = AdBlockEngine(null)
        engine.parseDownloaded(sequenceOf("doubleclick.net", "# comment", ""))
        engine.markReady()
        assertTrue(engine.shouldBlock("https://ad.doubleclick.net/a", "https://news.com"))
        assertFalse(engine.shouldBlock("https://example.com/a", "https://news.com"))
    }
}
