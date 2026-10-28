package com.pluginbrowser.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点适配的纯逻辑：域名匹配、scheme 判定、规则表完整性。
 *
 * 这些判断在每次导航的主线程上都要跑一遍，错了的后果是「该拦的没拦」或者
 * 「把正常页面也拦了」，两种都很难在真机上一眼看出来，所以用测试钉住。
 */
class SiteRuleTest {

    /* ---------------- 域名匹配 ---------------- */

    @Test
    fun `子域名命中父域名规则`() {
        val zhihu = SiteRules.byId("jump_zhihu")!!
        assertTrue(zhihu.matches("zhihu.com"))
        assertTrue(zhihu.matches("www.zhihu.com"))
        assertTrue(zhihu.matches("m.zhihu.com"))
        assertTrue(zhihu.matches("zhuanlan.zhihu.com"))
    }

    @Test
    fun `后缀相同但不同域的不会误命中`() {
        val zhihu = SiteRules.byId("jump_zhihu")!!
        assertFalse(zhihu.matches("notzhihu.com"))
        assertFalse(zhihu.matches("zhihu.com.evil.net"))
        assertFalse(zhihu.matches(null))
        assertFalse(zhihu.matches(""))
    }

    @Test
    fun `hostOf 只认 http 且统一小写`() {
        assertEquals("m.zhihu.com", SiteRuleEngine.hostOf("https://m.zhihu.com/question/1"))
        assertEquals("github.com", SiteRuleEngine.hostOf("HTTPS://GitHub.com/a"))
        assertEquals("a.b.com", SiteRuleEngine.hostOf("http://a.b.com:8080/x"))
        assertNull(SiteRuleEngine.hostOf("intent://x#Intent;scheme=zhihu;end"))
        assertNull(SiteRuleEngine.hostOf("about:blank"))
        assertNull(SiteRuleEngine.hostOf(null))
    }

    /* ---------------- App 唤起 scheme ---------------- */

    @Test
    fun `认得常见的 App 唤起 scheme`() {
        assertTrue(SiteRuleEngine.isAppWakeScheme("intent://view/1#Intent;end"))
        assertTrue(SiteRuleEngine.isAppWakeScheme("market://details?id=com.zhihu.android"))
        assertTrue(SiteRuleEngine.isAppWakeScheme("taobao://item.taobao.com/item.htm?id=1"))
        assertTrue(SiteRuleEngine.isAppWakeScheme("zhihu://questions/1"))
        assertTrue(SiteRuleEngine.isAppWakeScheme("bilibili://video/123"))
    }

    @Test
    fun `普通链接和电话不会被当成 App 唤起`() {
        assertFalse(SiteRuleEngine.isAppWakeScheme("https://www.zhihu.com/question/1"))
        assertFalse(SiteRuleEngine.isAppWakeScheme("tel:10086"))
        assertFalse(SiteRuleEngine.isAppWakeScheme("mailto:a@b.com"))
    }

    /* ---------------- 规则表 ---------------- */

    @Test
    fun `规则表 id 唯一且每条都真的做点什么`() {
        val ids = SiteRules.ALL.map { it.id }
        assertEquals("规则 id 重复了", ids.size, ids.toSet().size)
        SiteRules.ALL.forEach { rule ->
            assertTrue("${rule.id} 没配域名", rule.hosts.isNotEmpty())
            assertTrue("${rule.id} 没有任何动作", rule.actions.isNotEmpty())
            assertTrue("${rule.id} 没写说明", rule.summary.isNotBlank())
        }
    }

    @Test
    fun `视频规则一定带横屏，跳转规则一定带拦截`() {
        SiteRules.ALL.filter { SiteAction.FULLSCREEN_LANDSCAPE in it.actions }.forEach {
            assertTrue("${it.id} 少了允许自动播放", SiteAction.MEDIA_AUTOPLAY in it.actions)
        }
        SiteRules.ALL.filter { SiteAction.BLOCK_APP_JUMP in it.actions }.forEach {
            assertTrue("${it.id} 拦了跳转却没隐藏横幅", SiteAction.HIDE_APP_BANNER in it.actions)
        }
    }

    /* ---------------- 阅读模式 ---------------- */

    @Test
    fun `阅读页会转义标题里的标签`() {
        val article = ReaderMode.Article(
            title = "<script>alert(1)</script>标题",
            site = "mp.weixin.qq.com",
            byline = "",
            url = "https://mp.weixin.qq.com/s/abc",
            html = "<p>正文</p>",
            length = 1200,
        )
        val html = ReaderMode.html(article, 1f, dark = false)
        assertFalse("标题没转义，会把页面结构打坏", html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        // 正文是提取出来的 HTML 片段，必须原样保留
        assertTrue(html.contains("<p>正文</p>"))
        assertTrue(html.contains("pb://reader/exit"))
    }

    @Test
    fun `深色和浅色都给出对应的底色`() {
        val a = ReaderMode.Article("t", "s", "", "https://a.com", "<p>x</p>", 500)
        assertTrue(ReaderMode.html(a, 1f, dark = true).contains("#121212"))
        assertTrue(ReaderMode.html(a, 1f, dark = false).contains("#fbfaf7"))
    }

    @Test
    fun `字号缩放会真的改到 px`() {
        val a = ReaderMode.Article("t", "s", "", "https://a.com", "<p>x</p>", 500)
        val small = ReaderMode.html(a, 0.8f, dark = false)
        val big = ReaderMode.html(a, 1.4f, dark = false)
        assertTrue(small != big)
        assertTrue(small.contains("13.6px"))
        assertTrue(big.contains("23.8px"))
    }
}
