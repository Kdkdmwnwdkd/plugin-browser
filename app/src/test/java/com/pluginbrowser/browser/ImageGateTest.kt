package com.pluginbrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无图模式的类型判断。
 *
 * 这里判错的代价不对称：漏判只是少省一张图，误判会把 JS/CSS 换成 1 像素 GIF,
 * 整页直接坏掉。所以宁可宽进（Accept 头说了算），也不能把非图片当图片。
 */
class ImageGateTest {

    @Test
    fun `常见图片后缀都认`() {
        for (ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg", "ico")) {
            assertTrue(
                "应识别 $ext",
                ImageGate.isImageUrl("https://cdn.example.com/a/b.$ext", null),
            )
        }
    }

    @Test
    fun `大小写和查询串不影响判断`() {
        assertTrue(ImageGate.isImageUrl("https://cdn.example.com/A.JPG", null))
        assertTrue(ImageGate.isImageUrl("https://cdn.example.com/a.png?x=1&y=2", null))
        assertTrue(ImageGate.isImageUrl("https://cdn.example.com/a.png#frag", null))
    }

    @Test
    fun `无后缀的 CDN 地址靠 Accept 头兜住`() {
        assertTrue(
            ImageGate.isImageUrl(
                "https://img.example.com/abc123",
                "image/avif,image/webp,*/*",
            ),
        )
    }

    @Test
    fun `没有后缀也没有 Accept 头时不拦`() {
        assertFalse(ImageGate.isImageUrl("https://img.example.com/abc123", null))
    }

    @Test
    fun `脚本样式和文档绝不能被当成图片`() {
        val notImage = listOf(
            "https://example.com/app.js",
            "https://example.com/style.css",
            "https://example.com/index.html",
            "https://example.com/api/data.json",
            "https://example.com/page",
        )
        for (url in notImage) {
            assertFalse("误判成图片：$url", ImageGate.isImageUrl(url, "text/html,*/*"))
        }
    }

    @Test
    fun `Accept 头大小写和空格都兼容`() {
        assertTrue(ImageGate.isImageUrl("https://x/a", "IMAGE/PNG"))
        assertTrue(ImageGate.isImageUrl("https://x/a", "  image/webp,*/*"))
    }

    @Test
    fun `后缀出现在查询串里不算数`() {
        assertFalse(ImageGate.isImageUrl("https://example.com/view?file=a.png", "text/html"))
    }
}
