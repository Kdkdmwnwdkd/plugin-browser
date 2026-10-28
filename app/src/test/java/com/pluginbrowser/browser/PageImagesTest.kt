package com.pluginbrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片落盘名的生成规则：撞名会很烦，带空格或斜杠会写失败 */
class PageImagesTest {

    @Test
    fun `文件名带序号并保留扩展名`() {
        assertEquals("01_a.jpg", PageImages.fileNameOf("https://x.com/img/a.jpg", 0))
        assertEquals("12_pic.png", PageImages.fileNameOf("https://x.com/p/pic.png?w=300", 11))
    }

    @Test
    fun `空格和特殊字符会被替换掉`() {
        val name = PageImages.fileNameOf("https://x.com/img/%20p ic 1.png", 0)
        assertTrue("不能有空格", !name.contains(' '))
        assertEquals("01_p_ic_1.png", name)
    }

    @Test
    fun `没有扩展名时补 jpg`() {
        assertEquals("03_image.jpg", PageImages.fileNameOf("https://x.com/img/", 2))
        assertEquals("04_photo.jpg", PageImages.fileNameOf("https://x.com/photo", 3))
    }

    @Test
    fun `常见格式的 mime 认得出，未知的按 jpeg 处理`() {
        assertEquals("image/png", PageImages.mimeOf("https://x.com/a.png"))
        assertEquals("image/webp", PageImages.mimeOf("https://x.com/a.webp?x=1"))
        assertEquals("image/gif", PageImages.mimeOf("https://x.com/A.GIF"))
        assertEquals("image/jpeg", PageImages.mimeOf("https://x.com/noext"))
    }
}
