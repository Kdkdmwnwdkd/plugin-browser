package com.pluginbrowser.browser

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.pluginbrowser.R

/**
 * 浏览器功能面板。
 *
 * 之前是 PopupMenu 拉出一长条文字列表，功能一多就显得又长又杂。
 * 这里改成底部升起的四列图标网格，按「页面 / 标签 / 工具」分组，
 * 低频项（桌面版、设置）放在最下面的列表里。
 */
class BrowserMenuSheet : BottomSheetDialogFragment() {

    data class Action(val id: String, val icon: Int, val label: String)

    data class State(
        val translated: Boolean = false,
        val desktopUa: Boolean = false,
        val plugins: List<Action> = emptyList(),
    )

    var state = State()
    var onAction: ((String) -> Unit)? = null

    private var onSurface = 0
    private var onSurfaceVariant = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_browser_menu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        onSurfaceVariant =
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val pageRow = view.findViewById<LinearLayout>(R.id.row_page)
        val tabRow = view.findViewById<LinearLayout>(R.id.row_tabs)
        val toolRow = view.findViewById<LinearLayout>(R.id.row_tools)
        val pluginRow = view.findViewById<LinearLayout>(R.id.row_plugins)
        val pluginTitle = view.findViewById<TextView>(R.id.title_plugins)
        val listRow = view.findViewById<LinearLayout>(R.id.row_list)

        cell(pageRow, Action("refresh", R.drawable.ic_refresh, getString(R.string.menu_refresh)))
        cell(
            pageRow,
            Action(
                "translate",
                R.drawable.ic_translate,
                getString(
                    if (state.translated) R.string.menu_restore_translate else R.string.menu_translate_short,
                ),
            ),
        )
        cell(pageRow, Action("find", R.drawable.ic_search, getString(R.string.menu_find_short)))
        cell(pageRow, Action("share", R.drawable.ic_share, getString(R.string.menu_share_short)))

        cell(tabRow, Action("new_tab", R.drawable.ic_add, getString(R.string.menu_new_tab_short)))
        cell(
            tabRow,
            Action("new_incognito", R.drawable.ic_incognito, getString(R.string.menu_new_incognito_short)),
        )
        cell(tabRow, Action("bookmarks", R.drawable.ic_bookmark, getString(R.string.menu_bookmark_short)))
        cell(tabRow, Action("history", R.drawable.ic_history, getString(R.string.menu_history_short)))

        cell(toolRow, Action("downloads", R.drawable.ic_download, getString(R.string.menu_downloads_short)))
        cell(toolRow, Action("sniffer", R.drawable.ic_radar, getString(R.string.menu_sniffer_short)))
        cell(toolRow, Action("screenshot", R.drawable.ic_image, getString(R.string.menu_screenshot_short)))
        cell(toolRow, Action("plugins", R.drawable.ic_extension, getString(R.string.menu_plugins_short)))

        if (state.plugins.isNotEmpty()) {
            pluginTitle.visibility = View.VISIBLE
            pluginRow.visibility = View.VISIBLE
            state.plugins.forEach { cell(pluginRow, it) }
        }

        listRow(listRow, "desktop", R.drawable.ic_desktop, getString(R.string.menu_desktop_ua), state.desktopUa)
        listRow(
            listRow,
            "diag",
            R.drawable.ic_pulse,
            getString(R.string.diag_title),
            null,
        )
        listRow(
            listRow,
            "add_bookmark",
            R.drawable.ic_bookmark_border,
            getString(R.string.menu_add_bookmark),
            null,
        )
        listRow(listRow, "settings", R.drawable.ic_settings, getString(R.string.menu_settings), null)
    }

    private fun cell(row: LinearLayout, action: Action) {
        val ctx = row.context
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f)
            isClickable = true
            isFocusable = true
            background = rippleBackground(ctx)
        }
        val icon = ImageView(ctx).apply {
            setImageResource(action.icon)
            imageTintList = ColorStateList.valueOf(onSurface)
            layoutParams = LinearLayout.LayoutParams(dp(23), dp(23))
        }
        val label = TextView(ctx).apply {
            text = action.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7) }
        }
        cell.addView(icon)
        cell.addView(label)
        cell.setOnClickListener {
            dismissAllowingStateLoss()
            onAction?.invoke(action.id)
        }
        row.addView(cell)
    }

    /** 列表行：左图标 + 文字 + 右侧可选状态 */
    private fun listRow(
        parent: LinearLayout,
        id: String,
        iconRes: Int,
        title: String,
        checked: Boolean?,
    ) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            isClickable = true
            isFocusable = true
            setPadding(dp(20), 0, dp(20), 0)
            background = rippleBackground(ctx)
        }
        row.addView(
            ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            },
        )
        row.addView(
            TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(14) }
            },
        )
        if (checked != null) {
            row.addView(
                TextView(ctx).apply {
                    text = getString(
                        if (checked) R.string.menu_desktop_on else R.string.menu_desktop_off,
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(onSurfaceVariant)
                },
            )
        }
        row.setOnClickListener {
            dismissAllowingStateLoss()
            onAction?.invoke(id)
        }
        parent.addView(row)
    }

    private fun rippleBackground(ctx: android.content.Context) = run {
        val value = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        androidx.core.content.ContextCompat.getDrawable(ctx, value.resourceId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet
}
