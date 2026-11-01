package com.pluginbrowser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动翻译的触发条件表。
 *
 * 这里每一条「不该翻」都对应一种真实会把人劝退的故障：
 * 同一页被翻两遍、点了还原又立刻翻回来、进中文站先转十秒。
 */
class AutoTranslatePolicyTest {

    private val enPage = PageLanguage.of("en", PageLanguage.EN, PageLanguage.UNKNOWN, 0.9f, 200)
    private val zhPage = PageLanguage.of("zh-CN", PageLanguage.ZH, "hans", 0.9f, 400)

    private fun start(
        mode: Int = AutoTranslatePolicy.ALWAYS,
        translated: Boolean = false,
        suppressed: Boolean = false,
        url: String? = "https://example.com/a",
        page: PageLanguage? = enPage,
        target: TranslateTarget = TranslateTarget.ZH,
    ) = AutoTranslatePolicy.shouldStart(mode, translated, suppressed, url, page, target)

    @Test
    fun `只有自动档才会自己动手`() {
        assertTrue(start(mode = AutoTranslatePolicy.ALWAYS))
        assertFalse(start(mode = AutoTranslatePolicy.MANUAL))
        assertFalse(start(mode = AutoTranslatePolicy.NEVER))
        // 旧版本/脏数据里可能存着别的数，一律当手动
        assertFalse(start(mode = 99))
    }

    @Test
    fun `已经翻过的页面不再翻第二遍`() {
        assertFalse(start(translated = true))
    }

    @Test
    fun `点了还原就停住`() {
        // 不挡这一条的话，还原按钮会立刻被下一次 onPageFinished 覆盖掉
        assertFalse(start(suppressed = true))
    }

    @Test
    fun `只对网页生效`() {
        assertFalse(start(url = "about:blank"))
        assertFalse(start(url = "file:///sdcard/a.html"))
        assertFalse(start(url = "data:text/html,hi"))
        assertFalse(start(url = ""))
        assertFalse(start(url = null))
        assertTrue(start(url = "http://example.com/a"))
    }

    @Test
    fun `已经是目标语言的页面跳过`() {
        assertFalse(start(page = zhPage))
    }

    @Test
    fun `繁体中文仍然要翻`() {
        // 繁转简有产出，而且这恰恰是很多人点翻译的原因
        val hant = PageLanguage.of("zh-TW", PageLanguage.ZH, PageLanguage.HANT, 0.9f, 400)
        assertTrue(start(page = hant))
    }

    @Test
    fun `判不出语言就照翻`() {
        // 自动翻译最糟的失败是「该翻的没翻」，白翻一次只是慢一点
        assertTrue(start(page = null))
        assertTrue(start(page = PageLanguage.of("", PageLanguage.UNKNOWN, PageLanguage.UNKNOWN, 0f, 0)))
    }

    @Test
    fun `目标语言不是中文时按目标判`() {
        // 英文页 + 目标是英文 → 已经是目标语言，跳过
        assertFalse(start(page = enPage, target = TranslateTarget.EN))
        // 中文页 + 目标是英文 → 要翻
        assertTrue(start(page = zhPage, target = TranslateTarget.EN))
    }

    @Test
    fun `翻译成功后只在手动档问一次`() {
        assertTrue(AutoTranslatePolicy.shouldAskAfterSuccess(AutoTranslatePolicy.MANUAL, false))
        // 问过就不再问：用户说「先不用」也是回答
        assertFalse(AutoTranslatePolicy.shouldAskAfterSuccess(AutoTranslatePolicy.MANUAL, true))
        // 已经是自动档了没必要问
        assertFalse(AutoTranslatePolicy.shouldAskAfterSuccess(AutoTranslatePolicy.ALWAYS, false))
        assertFalse(AutoTranslatePolicy.shouldAskAfterSuccess(AutoTranslatePolicy.NEVER, false))
    }

    @Test
    fun `三态文案`() {
        assertEquals("手动", AutoTranslatePolicy.label(AutoTranslatePolicy.MANUAL))
        assertEquals("自动", AutoTranslatePolicy.label(AutoTranslatePolicy.ALWAYS))
        assertEquals("从不", AutoTranslatePolicy.label(AutoTranslatePolicy.NEVER))
        assertEquals("手动", AutoTranslatePolicy.label(-1))
    }
}
