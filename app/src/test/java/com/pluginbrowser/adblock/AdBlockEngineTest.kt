package com.pluginbrowser.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 用真实规则集验证匹配结果，避免"以为拦了其实没拦" */
class AdBlockEngineTest {

    private fun engine(): AdBlockEngine {
        val root = File("src/main/assets/adblock")
        assertTrue("规则文件缺失：${root.absolutePath}", root.exists())
        val e = AdBlockEngine(null)
        e.parseHosts(File(root, "hosts.txt").readLines().asSequence())
        e.parseRules(File(root, "rules.tsv").readLines().asSequence())
        e.parseCosmetic(File(root, "cosmetic.tsv").readLines().asSequence())
        e.markReady()
        return e
    }

    @Test
    fun `解析主机名`() {
        assertTrue(AdBlockEngine.hostOf("https://a.b.com/x/y?z=1") == "a.b.com")
        assertTrue(AdBlockEngine.hostOf("http://example.com:8080/p") == "example.com")
        assertTrue(AdBlockEngine.hostOf("about:blank") == null)
    }

    @Test
    fun `拦截常见广告与统计域名`() {
        val e = engine()
        val page = "https://www.example.com/article"
        assertTrue(e.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page))
        assertTrue(e.shouldBlock("https://pagead2.googlesyndication.com/pagead/show_ads.js", page))
        assertTrue(e.shouldBlock("https://hm.baidu.com/hm.js?abc", page))
        assertTrue(e.shouldBlock("https://www.google-analytics.com/analytics.js", page))
        assertTrue(e.shouldBlock("https://www.googletagmanager.com/gtm.js?id=x", page))
    }

    @Test
    fun `不误伤正常资源`() {
        val e = engine()
        val page = "https://www.baidu.com/"
        assertFalse(e.shouldBlock("https://www.baidu.com/img/flexible/logo/pc/result.png", page))
        assertFalse(e.shouldBlock("https://static.zhihu.com/heifetz/main.app.js", page))
        assertFalse(e.shouldBlock("https://s1.hdslb.com/bfs/static/entry.css", page))
    }

    @Test
    fun `子域逐级命中`() {
        val e = engine()
        assertTrue(e.shouldBlock("https://a.b.c.g.doubleclick.net/x.js", "https://foo.com"))
    }

    @Test
    fun `元素隐藏样式非空`() {
        val e = engine()
        val css = e.cssFor("https://www.example.com/")
        assertNotNull(css)
        assertTrue(css!!.contains("display:none"))
    }

    @Test
    fun `规则规模合理`() {
        val e = engine()
        assertTrue("host 规则过少：${e.hostRuleCount}", e.hostRuleCount > 80000)
    }

    /**
     * 第三方规则的判定结果和页面有关，缓存不能只按资源地址存：
     * 以前同一张图在 A 站放行一次，B 站再问就直接拿到「放行」的旧结论。
     */
    @Test
    fun `同一资源在不同页面上的第三方判定不串味`() {
        val e = AdBlockEngine(null)
        e.parseHosts(sequenceOf("nonexistent.example"))
        // flags=01：不是例外规则，且只在第三方场景生效；token=banner
        e.parseRules(sequenceOf("01\t\tbanner\tbanner"))
        e.markReady()

        val url = "https://ad.example.com/banner.js"
        assertFalse("同站资源不算第三方，不该拦", e.shouldBlock(url, "https://ad.example.com/"))
        assertTrue("换到别的站点上就是第三方，必须拦", e.shouldBlock(url, "https://news.example.org/"))
    }
}
