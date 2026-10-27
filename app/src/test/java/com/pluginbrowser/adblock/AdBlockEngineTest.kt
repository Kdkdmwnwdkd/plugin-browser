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
}
