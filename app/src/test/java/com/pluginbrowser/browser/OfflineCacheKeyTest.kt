package com.pluginbrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照 key：同一页面去锚点命中同一份，不同页面绝不撞车 */
class OfflineCacheKeyTest {

    @Test
    fun `锚点不产生新快照`() {
        assertEquals(
            OfflineCache.key("https://a.com/post"),
            OfflineCache.key("https://a.com/post#comments"),
        )
    }

    @Test
    fun `不同路径不同快照`() {
        assertFalse(OfflineCache.key("https://a.com/a") == OfflineCache.key("https://a.com/b"))
    }

    @Test
    fun `key 是稳定的十六进制`() {
        val k = OfflineCache.key("https://example.com/x")
        assertEquals(40, k.length)
        assertTrue(k.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
