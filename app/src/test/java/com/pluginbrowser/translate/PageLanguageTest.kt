package com.pluginbrowser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言检测的判定表。
 *
 * 这张表的意义在于：凡是「不确定」的情况，都必须落到「继续翻译」那一侧。
 * 拦住一次真正该翻的页面，比多翻一次已经是对的页面糟糕得多。
 */
class PageLanguageTest {

    private fun lang(guess: String, variant: String = "unknown", declared: String = "") =
        PageLanguage(
            declared = declared,
            guess = guess,
            variant = variant,
            confidence = 0.9f,
            chars = 200,
        )

    @Test
    fun `中文页面配中文目标时跳过`() {
        assertTrue(lang(PageLanguage.ZH, "hans").isAlready(TranslateTarget.ZH))
        // 判不出繁简时也当作中文：满屏汉字的页面，翻成中文没有产出
        assertTrue(lang(PageLanguage.ZH, PageLanguage.UNKNOWN).isAlready(TranslateTarget.ZH))
    }

    @Test
    fun `繁体页面配简体目标时不跳过`() {
        // 繁体转简体是有产出的操作，而且这恰好是很多人点翻译的真实原因
        assertFalse(lang(PageLanguage.ZH, PageLanguage.HANT).isAlready(TranslateTarget.ZH))
    }

    @Test
    fun `英文页面配中文目标时翻译`() {
        assertFalse(lang(PageLanguage.EN).isAlready(TranslateTarget.ZH))
    }

    @Test
    fun `日文页面配日文目标时跳过`() {
        assertTrue(lang(PageLanguage.JA).isAlready(TranslateTarget.JA))
        assertFalse(lang(PageLanguage.JA).isAlready(TranslateTarget.ZH))
    }

    @Test
    fun `未知语言一律翻译`() {
        val unknown = lang(PageLanguage.UNKNOWN)
        TranslateTarget.entries.forEach { target ->
            assertFalse("unknown 不该在 ${target.code} 上拦住翻译", unknown.isAlready(target))
        }
    }

    @Test
    fun `其他拉丁语系不算已经是英文`() {
        // 法语页面目标设成英文，是该翻的；只说「这是拉丁字母」不足以判成英文
        assertFalse(lang(PageLanguage.LATIN).isAlready(TranslateTarget.EN))
    }

    @Test
    fun `语言名给得出人话`() {
        assertEquals("简体中文", lang(PageLanguage.ZH, "hans").displayName())
        assertEquals("繁体中文", lang(PageLanguage.ZH, PageLanguage.HANT).displayName())
        assertEquals("英文", lang(PageLanguage.EN).displayName())
        assertEquals("日文", lang(PageLanguage.JA).displayName())
        assertEquals("韩文", lang(PageLanguage.KO).displayName())
        // 判不出来时返回空串，界面上不显示「→ null」
        assertEquals("", lang(PageLanguage.UNKNOWN).displayName())
    }

    @Test
    fun `脚本返回的字段原样落地`() {
        val parsed = PageLanguage.of(
            declared = "zh-TW",
            guess = "zh",
            variant = "hant",
            confidence = 0.93f,
            chars = 412,
        )
        assertEquals("zh-TW", parsed.declared)
        assertEquals(PageLanguage.ZH, parsed.guess)
        assertEquals(PageLanguage.HANT, parsed.variant)
        assertEquals(0.93f, parsed.confidence, 0.001f)
        assertEquals(412, parsed.chars)
    }

    @Test
    fun `空字段退回未知而不是崩掉`() {
        // 脚本可能因为页面结构古怪给出空串，这里必须归一成 unknown，
        // 否则 guess == null 会让 isAlready 的 when 落到不该落的分支
        val parsed = PageLanguage.of(declared = "", guess = "", variant = "", confidence = 0f, chars = 0)
        assertEquals(PageLanguage.UNKNOWN, parsed.guess)
        assertEquals(PageLanguage.UNKNOWN, parsed.variant)
        assertTrue(parsed.isUnknown)
        assertFalse(parsed.isAlready(TranslateTarget.ZH))
    }

    @Test
    fun `解析 null 返回 null`() {
        assertNull(PageLanguage.parse(null))
    }
}
