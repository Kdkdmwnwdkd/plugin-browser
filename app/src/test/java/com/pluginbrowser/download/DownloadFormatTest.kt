package com.pluginbrowser.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** 下载确认框与进度行的文案/大小解析，纯 JVM 逻辑，不需要真机 */
class DownloadFormatTest {

    @Test
    fun `大小文案按量级切换单位`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("11.0 MB", formatBytes(11L * 1024 * 1024))
        assertEquals("1.50 GB", formatBytes(1024L * 1024 * 1024 * 3 / 2))
    }

    @Test
    fun `负值表示未知大小`() {
        assertEquals("未知大小", formatBytes(-1))
        assertEquals("未知大小", formatBytes(Long.MIN_VALUE))
    }

    @Test
    fun `类型标签优先用扩展名`() {
        assertEquals("APK", typeLabel("app-release.apk", "application/vnd.android.package-archive"))
        assertEquals("ZIP", typeLabel("source.zip", null))
        assertEquals("APK", typeLabel("Kite-2.3.0-debug.apk", null))
    }

    @Test
    fun `没有扩展名时退回 mime 尾段`() {
        assertEquals("PDF", typeLabel("download", "application/pdf"))
        assertEquals("", typeLabel("download", null))
    }

    @Test
    fun `Content-Range 的尾段是真实总量`() {
        assertEquals(11_400_000L, totalFromHeaders("bytes 0-0/11400000", "1"))
    }

    @Test
    fun `不支持 Range 时用 Content-Length`() {
        assertEquals(2048L, totalFromHeaders(null, "2048"))
        assertEquals(2048L, totalFromHeaders("bytes 0-0/*", "2048"))
    }

    @Test
    fun `都拿不到时返回负一`() {
        assertEquals(-1L, totalFromHeaders(null, null))
        assertEquals(-1L, totalFromHeaders(null, "0"))
        assertEquals(-1L, totalFromHeaders("bytes 0-0/*", "-1"))
    }
}
