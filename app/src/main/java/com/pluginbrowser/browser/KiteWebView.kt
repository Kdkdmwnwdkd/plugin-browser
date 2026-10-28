package com.pluginbrowser.browser

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import org.json.JSONTokener

/**
 * 带文字选择工具条的 WebView。
 *
 * 系统给的选择菜单只有「复制 / 全选 / 网页搜索」，而且「网页搜索」走的是系统浏览器。
 * 这里在它后面补三项：搜索、翻译、分享——都落在当前这个应用里，
 * 用户选中一句话不用先复制再切 App。
 *
 * 实现方式：拦 startActionMode，把 WebView 自己的回调包一层。
 * 不能直接替换回调（那会丢掉「复制/全选」），必须委托。
 */
class KiteWebView(context: Context) : WebView(context) {

    enum class SelectionAction(val label: String) {
        SEARCH("搜索"),
        TRANSLATE("翻译"),
        SHARE("分享"),
    }

    /** 选中文字后点了扩展项；text 是已经还原过转义的正文字符串 */
    var onSelectionAction: ((SelectionAction, String) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null) return super.startActionMode(null, type)
        return super.startActionMode(Wrapper(callback), type)
    }

    @Suppress("DEPRECATION")
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        if (callback == null) return super.startActionMode(null)
        return super.startActionMode(Wrapper(callback))
    }

    private fun currentSelection(onText: (String) -> Unit) {
        evaluateJavascript(
            "(function(){try{var s=window.getSelection();return s?s.toString():'';}catch(e){return '';}})()",
        ) { raw ->
            onText(decodeJsString(raw))
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String ?: "" }.getOrDefault("")
    }

    private inner class Wrapper(private val delegate: ActionMode.Callback) : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            delegate.onPrepareActionMode(mode, menu)
            // 系统在旋转、选区变化时会重跑 onPrepare，必须去重
            if (menu.findItem(ID_SEARCH) == null) {
                menu.add(Menu.NONE, ID_SEARCH, 200, SelectionAction.SEARCH.label)
                menu.add(Menu.NONE, ID_TRANSLATE, 201, SelectionAction.TRANSLATE.label)
                menu.add(Menu.NONE, ID_SHARE, 202, SelectionAction.SHARE.label)
            }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = when (item.itemId) {
                ID_SEARCH -> SelectionAction.SEARCH
                ID_TRANSLATE -> SelectionAction.TRANSLATE
                ID_SHARE -> SelectionAction.SHARE
                else -> null
            }
            if (action == null) return delegate.onActionItemClicked(mode, item)
            currentSelection { text ->
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) onSelectionAction?.invoke(action, trimmed)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = delegate.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val d = delegate
            if (d is ActionMode.Callback2) {
                d.onGetContentRect(mode, view, outRect)
            } else {
                super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    private companion object {
        // 自定义 id 必须避开系统菜单项，取一个足够高的区间
        const val ID_SEARCH = 0x0F10
        const val ID_TRANSLATE = 0x0F11
        const val ID_SHARE = 0x0F12
    }
}
