package com.pluginbrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkImporterTest {

    private val sample = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1700000000">技术</H3>
            <DL><p>
                <DT><A HREF="https://github.com/Kdkdmwnwdkd/plugin-browser" ADD_DATE="1700000001">plugin-browser</A>
                <DT><A HREF="https://developer.android.com/guide/webapps" ADD_DATE="1700000002">Web Apps &amp; WebView</A>
                <DT><A HREF="https://github.com/Kdkdmwnwdkd/plugin-browser">重复的链接</A>
            </DL><p>
            <DT><A HREF="javascript:alert(1)">书签小工具</A>
            <DT><A HREF="chrome://settings">内部页</A>
            <DT><A HREF="https://www.zhihu.com/question/1"><b>带标签</b> 的标题</A>
        </DL>
    """.trimIndent()

    @Test
    fun `解析出所有 http 链接并去掉内部协议`() {
        val list = BookmarkImporter.parse(sample)
        assertEquals(3, list.size)
        assertEquals(
            listOf(
                "https://github.com/Kdkdmwnwdkd/plugin-browser",
                "https://developer.android.com/guide/webapps",
                "https://www.zhihu.com/question/1",
            ),
            list.map { it.url },
        )
    }

    @Test
    fun `同一地址只保留第一次出现的标题`() {
        val list = BookmarkImporter.parse(sample)
        val repo = list.first { it.url.contains("plugin-browser") }
        assertEquals("plugin-browser", repo.title)
    }

    @Test
    fun `实体解码与标签清理`() {
        val list = BookmarkImporter.parse(sample)
        val android = list.first { it.url.contains("developer.android.com") }
        assertEquals("Web Apps & WebView", android.title)
        val zhihu = list.first { it.url.contains("zhihu.com") }
        assertEquals("带标签 的标题", zhihu.title)
    }

    @Test
    fun `没有标题时用地址兜底`() {
        val html = """<a href="https://example.com/a"></a>"""
        assertEquals("https://example.com/a", BookmarkImporter.parse(html).single().title)
    }

    @Test
    fun `空文件不会抛异常`() {
        assertTrue(BookmarkImporter.parse("").isEmpty())
        assertTrue(BookmarkImporter.parse("<html><body>no links</body></html>").isEmpty())
    }
}
