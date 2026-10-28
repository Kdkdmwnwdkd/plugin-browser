package com.pluginbrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u8 解析与地址解析。
 *
 * 这段逻辑一旦错，表现是「下载出来的文件放不了」或者「任务失败但看不出原因」，
 * 真机上排查成本很高，所以把几种真实存在的写法都钉在测试里。
 */
class HlsTest {

    @Test
    fun `认得 m3u8 链接，包括带参数和大小写`() {
        assertTrue(Hls.isPlaylist("https://a.com/v/index.m3u8"))
        assertTrue(Hls.isPlaylist("https://a.com/v/index.M3U8?token=1"))
        assertFalse(Hls.isPlaylist("https://a.com/v/index.mp4"))
        assertFalse(Hls.isPlaylist("https://a.com/v/m3u8"))
    }

    @Test
    fun `master 列表挑出各路码流并解析地址`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        val variants = Hls.parseMaster(text, "https://cdn.a.com/v/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals("https://cdn.a.com/v/360/index.m3u8", variants[0].url)
        assertEquals(800000L, variants[0].bandwidth)
        assertEquals("https://cdn.a.com/v/720/index.m3u8", variants[1].url)
        assertEquals(2400000L, variants[1].bandwidth)
    }

    @Test
    fun `media 列表取出分片、密钥与初始化段`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:9.0,
            seg0.ts
            #EXTINF:9.0,
            /abs/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = Hls.parseMedia(text, "https://cdn.a.com/v/media.m3u8")
        assertEquals(2, media.segments.size)
        assertEquals("https://cdn.a.com/v/seg0.ts", media.segments[0])
        assertEquals("https://cdn.a.com/abs/seg1.ts", media.segments[1])
        assertEquals("https://cdn.a.com/v/key.bin", media.key?.url)
        assertEquals(16, media.key?.iv?.size)
        assertEquals(0, media.key?.iv?.get(0)?.toInt())
        assertEquals(15, media.key?.iv?.get(15)?.toInt())
        assertFalse(media.fmp4)
    }

    @Test
    fun `METHOD=NONE 会清掉之前设置的密钥`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k1.bin"
            #EXTINF:9.0,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:9.0,
            b.ts
        """.trimIndent()
        val media = Hls.parseMedia(text, "https://a.com/v/x.m3u8")
        assertNull("最后一段是明文的，密钥不该还留着", media.key)
        assertEquals(2, media.segments.size)
    }

    @Test
    fun `fMP4 的初始化段会被识别出来`() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg1.m4s
        """.trimIndent()
        val media = Hls.parseMedia(text, "https://a.com/v/x.m3u8")
        assertEquals("https://a.com/v/init.mp4", media.initSegment)
        assertTrue(media.fmp4)
    }

    @Test
    fun `相对地址四种写法都要能解`() {
        val base = "https://a.com/v/playlist.m3u8"
        assertEquals("https://a.com/v/seg.ts", Hls.resolve(base, "seg.ts"))
        assertEquals("https://a.com/s/seg.ts", Hls.resolve(base, "/s/seg.ts"))
        assertEquals("https://cdn.b.com/x.ts", Hls.resolve(base, "//cdn.b.com/x.ts"))
        assertEquals("http://c.com/x.ts", Hls.resolve(base, "http://c.com/x.ts"))
        // 二级目录
        assertEquals(
            "https://a.com/v/720/s.ts",
            Hls.resolve("https://a.com/v/720/index.m3u8", "s.ts"),
        )
    }

    @Test
    fun `没有显式 IV 时按分片序号推`() {
        assertArrayEquals(ByteArray(16) { if (it == 15) 5 else 0 }, Hls.ivFor(5, null))
        assertArrayEquals(ByteArray(16) { if (it == 14) 1 else 0 }, Hls.ivFor(256, null))
        val explicit = ByteArray(16) { 7 }
        assertArrayEquals(explicit, Hls.ivFor(999, explicit))
    }

    @Test
    fun `空播放列表不会崩，密钥地址缺失也不会崩`() {
        val media = Hls.parseMedia("#EXTM3U\n", "https://a.com/x.m3u8")
        assertTrue(media.segments.isEmpty())
        assertNull(media.key)
        assertNotNull(Hls.parseMaster("#EXTM3U", "https://a.com/x.m3u8"))
    }
}
