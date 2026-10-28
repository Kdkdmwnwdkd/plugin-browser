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

    /* ---------------- 分片规划 ---------------- */

    @Test
    fun `小文件不分片`() {
        assertEquals(listOf(0L to 99L), planSegments(100))
        assertEquals(listOf(0L to 1023L), planSegments(1024))
        // 刚好不到 8MB：一段下完比开四条连接更快
        assertEquals(1, planSegments(8L * 1024 * 1024 - 1).size)
    }

    @Test
    fun `够大就切成多段`() {
        assertEquals(2, planSegments(8L * 1024 * 1024).size)
        assertEquals(3, planSegments(12L * 1024 * 1024).size)
        // 上限 4 路：再多会被代理或服务端的单 IP 并发限制吃掉
        assertEquals(4, planSegments(16L * 1024 * 1024).size)
        assertEquals(4, planSegments(500L * 1024 * 1024).size)
    }

    @Test
    fun `分片首尾相接且完整覆盖`() {
        val cases = listOf(
            8L * 1024 * 1024,
            8L * 1024 * 1024 + 1,
            9_999_999L,
            33_333_333L,
            1_073_741_824L,
        )
        for (total in cases) {
            val segs = planSegments(total)
            assertEquals("起点必须是 0", 0L, segs.first().first)
            assertEquals("终点必须是 total-1", total - 1, segs.last().second)
            for (i in 1 until segs.size) {
                assertEquals("第 $i 段必须紧接上一段", segs[i - 1].second + 1, segs[i].first)
            }
            val covered = segs.sumOf { it.second - it.first + 1 }
            assertEquals("不能有空洞也不能重叠", total, covered)
        }
    }

    @Test
    fun `零与负数不产生分片`() {
        assertEquals(emptyList<Pair<Long, Long>>(), planSegments(0))
        assertEquals(emptyList<Pair<Long, Long>>(), planSegments(-1))
    }

    @Test
    fun `每段不少于下限`() {
        // 9MB / 2 段 = 4.5MB，满足 2MB 下限；不该出现 3 段 3MB 这种碎切
        planSegments(9L * 1024 * 1024).forEach {
            assert(it.second - it.first + 1 >= 2L * 1024 * 1024)
        }
    }
}
