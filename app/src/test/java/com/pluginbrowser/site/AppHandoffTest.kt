package com.pluginbrowser.site

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「在 App 打开 / 留在网页」的选择解析：存的是字符串，读错了就会一直弹窗 */
class AppHandoffTest {

    @Test
    fun `记住的选择能正确读回`() {
        assertEquals(AppHandoff.Choice.APP, AppHandoff.Choice.of("app"))
        assertEquals(AppHandoff.Choice.WEB, AppHandoff.Choice.of("web"))
    }

    @Test
    fun `没存过或者存了脏数据都当成每次询问`() {
        assertEquals(AppHandoff.Choice.ASK, AppHandoff.Choice.of(null))
        assertEquals(AppHandoff.Choice.ASK, AppHandoff.Choice.of(""))
        assertEquals(AppHandoff.Choice.ASK, AppHandoff.Choice.of("whatever"))
    }
}
