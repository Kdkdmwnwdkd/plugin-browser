package com.pluginbrowser.util

/**
 * 地址栏「提交」判定。
 *
 * 这里踩过一次坑：原先只认 IME_ACTION_GO / IME_NULL，结果三星、搜狗这类
 * 带皮肤的输入法按回车时发的是 IME_ACTION_DONE，或者干脆只发一个原始
 * KEYCODE_ENTER。事件被漏掉 → 键盘收起 → 页面一动不动，看起来就像
 * 「浏览器坏了」。
 *
 * 所以判定放宽到「任何一种表示确认的 IME 动作，或者一个回车键」，
 * 单独拎出来是为了能写单元测试锁住。
 */
object AddressSubmit {

    const val IME_NONE = 1
    const val IME_GO = 2
    const val IME_SEARCH = 3
    const val IME_SEND = 4
    const val IME_DONE = 6

    const val KEY_ENTER = 66
    const val KEY_NUMPAD_ENTER = 160

    /** @param actionId 输入法动作 id（0 表示没有动作） */
    fun isSubmit(actionId: Int, keyCode: Int = 0, keyDown: Boolean = false): Boolean {
        if (keyDown && (keyCode == KEY_ENTER || keyCode == KEY_NUMPAD_ENTER)) return true
        return when (actionId) {
            0, IME_NONE, IME_GO, IME_SEARCH, IME_SEND, IME_DONE -> true
            else -> false
        }
    }
}
