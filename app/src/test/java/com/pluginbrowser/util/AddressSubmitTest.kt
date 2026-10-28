package com.pluginbrowser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏回车判定。
 *
 * 这一条曾经出过事故：用户粘贴 GitHub 地址按回车，键盘收起来了，页面一动不动。
 * 原因是当时的判定只认 IME_ACTION_GO，输入法发 DONE / SEARCH 或者只发一个
 * 原始回车键就全漏掉了。这里把各种输入法的实际行为都钉住。
 */
class AddressSubmitTest {

    @Test
    fun `标准前往键`() {
        assertTrue(AddressSubmit.isSubmit(AddressSubmit.IME_GO))
    }

    @Test
    fun `三方输入法常发完成或搜索`() {
        assertTrue(AddressSubmit.isSubmit(AddressSubmit.IME_DONE))
        assertTrue(AddressSubmit.isSubmit(AddressSubmit.IME_SEARCH))
        assertTrue(AddressSubmit.isSubmit(AddressSubmit.IME_SEND))
        assertTrue(AddressSubmit.isSubmit(AddressSubmit.IME_NONE))
    }

    @Test
    fun `没有动作id时也算提交`() {
        assertTrue(AddressSubmit.isSubmit(0))
    }

    @Test
    fun `只发原始回车键事件也要接住`() {
        assertTrue(AddressSubmit.isSubmit(0, AddressSubmit.KEY_ENTER, keyDown = true))
        assertTrue(AddressSubmit.isSubmit(0, AddressSubmit.KEY_NUMPAD_ENTER, keyDown = true))
        // 抬起的那一下不重复触发（这里用 IME_ACTION_NEXT 排除「无动作 id 一律提交」的干扰）
        assertFalse(AddressSubmit.isSubmit(5, AddressSubmit.KEY_ENTER, keyDown = false))
    }

    @Test
    fun `换行或下一项不该被当成前往`() {
        assertFalse(AddressSubmit.isSubmit(5))
        assertFalse(AddressSubmit.isSubmit(7))
    }
}
