package com.pluginbrowser.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** 断点续传依赖 Content-Range 的解析，这里把边界钉住 */
class DownloadEngineTest {

    @Test
    fun `206 响应解析起始位置与总量`() {
        val (start, total) = parseContentRange("bytes 1024-2047/4096")
        assertEquals(1024L, start)
        assertEquals(4096L, total)
    }

    @Test
    fun `大小为星号时总量未知`() {
        val (start, total) = parseContentRange("bytes 0-0/*")
        assertEquals(0L, start)
        assertEquals(-1L, total)
    }

    @Test
    fun `兼容大小写与多余空格`() {
        val (start, total) = parseContentRange("  BYTES  100-199 / 200 ")
        assertEquals(100L, start)
        assertEquals(200L, total)
    }

    @Test
    fun `非法输入返回负一`() {
        assertEquals(-1L to -1L, parseContentRange(null))
        assertEquals(-1L to -1L, parseContentRange(""))
        assertEquals(-1L to -1L, parseContentRange("items 0-1/2"))
        assertEquals(-1L to -1L, parseContentRange("bytes"))
    }
}
