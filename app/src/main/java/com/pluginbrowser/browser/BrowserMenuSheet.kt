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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.pluginbrowser.R

/**
 * 浏览器功能面板。
 *
 * 版式只有一种元素：一行一个动作——左图标、中间文字、右边状态。
 *
 * 上一版是「四列图标格 + 底部长条行」两段式：上半屏 12 个格子挤成一片、标签 11sp 顶到边，
 * 下半屏突然变成松散长条，中间还横一条分割线。两套密度拼在一张面板上，眼睛找不到落点。
 * 现在统一压成列表，分组靠小标题和留白，不画线。
 *
 * 图标这次是真统一了。之前图库里一半是 Material 实心字形、一半是后来补的 1.7dp 线框，
 * 染成同一个颜色之后块状和线条混着排，越看越乱——所以 2.10.1 干脆全撤了，结果又变成
 * 「全靠读字认功能」。现在动作图标全部换成 Material Icons 同一套字形；底栏那五个另算，
 * 它们内部一致，形状也是手绘的「多层页面」语义，换掉反而更差。
 *
 * 关闭态不写「已关闭」——那是默认值，信息量为零，只标开着的。
 */
class BrowserMenuSheet : BottomSheetDialogFragment() {

    data class Action(val id: String, val icon: Int, val label: String)

    data class State(
        val translated: Boolean = false,
        val desktopUa: Boolean = false,
        val readerMode: Boolean = false,
        val nightMode: Boolean = false,
        /** 无图模式的当前档位文案（关闭 / 仅 Wi-Fi / 不加载） */
        val imageLabel: String = "关闭",
        val plugins: List<Action> = emptyList(),
    )

    var state = State()
    var onAction: ((String) -> Unit)? = null

    private var onSurface = 0
    private var onSurfaceVariant = 0
    private var accent = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_browser_menu, container, false)

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.isFitToContents = true
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        // 高度要钉在根视图上，不能钉 sheet。
        //
        // sheet 的高度由 BottomSheetBehavior 的展开逻辑接管，直接改 sheet.layoutParams.height
        // 会被覆盖（改完还是原样）。fitToContents=true 时 behavior 读的是根视图的高度当成
        // 展开高度，所以把根限死在 92% 屏高，超出的内容交给里面的 ScrollView 滚。
        //
        // 不能用 wrap_content：那会让 ScrollView 跟着内容一起变高，结果既不裁也不滚，
        // 底部几行凭空消失——360~410dp 高的国产机上「设置」就是这么丢的。
        val content = view ?: return
        content.post {
            // 用窗口可用高度，不是物理屏高。
            // 物理屏高含状态栏和导航栏（这块屏 1280 对 1048），按它算会把底部内容推出可视区。
            val avail = (sheet.parent as? View)?.height?.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val target = (avail * 0.94f).toInt()
            content.layoutParams = content.layoutParams.apply { height = target }
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        onSurfaceVariant =
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)

        // 三键导航的机型上，最后一行会被导航栏压掉半截，文字只剩上半截，看着像坏了。
        //
        // 只挂 insets listener 不够：BottomSheet 的 window 是 layout-no-limits 的，
        // 导航栏根本不作为 inset 派发下来（拿到的永远是 0）。所以先用系统资源算一个
        // 保底高度，再让 listener 在有真实 insets 时覆盖它。
        //
        // 内边距要加在滚动内容上，不能加在 ScrollView 自己身上——加在外层等于把可视区截短，
        // 底部那几行反而更够不着了。
        val inner = (view as? ViewGroup)?.getChildAt(0) ?: view
        val basePad = inner.paddingBottom
        val navFromRes = resources
            .getIdentifier("navigation_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: 0
        inner.setPadding(inner.paddingLeft, inner.paddingTop, inner.paddingRight, basePad + navFromRes)
        ViewCompat.setOnApplyWindowInsetsListener(inner) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (nav > 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + nav)
            }
            insets
        }

        val current = view.findViewById<LinearLayout>(R.id.group_current)
        val open = view.findViewById<LinearLayout>(R.id.group_open)
        val tools = view.findViewById<LinearLayout>(R.id.group_tools)
        val display = view.findViewById<LinearLayout>(R.id.group_display)
        val plugins = view.findViewById<LinearLayout>(R.id.group_plugins)
        val more = view.findViewById<LinearLayout>(R.id.group_more)

        // —— 当前页：对这一页做点什么。刷新不在这里，地址栏右边就有。
        listRow(
            current,
            "translate",
            R.drawable.ic_translate,
            if (state.translated) getString(R.string.menu_restore_translate)
            else getString(R.string.menu_translate_short),
        )
        listRow(current, "find", R.drawable.ic_search, getString(R.string.menu_find_short))
        listRow(current, "share", R.drawable.ic_share, getString(R.string.menu_share_short))
        listRow(
            current,
            "add_bookmark",
            R.drawable.ic_bookmark_border,
            getString(R.string.menu_bookmark_current),
        )
        listRow(current, "qrcode", R.drawable.ic_qrcode, getString(R.string.menu_qrcode))

        // —— 打开：去哪
        listRow(open, "new_tab", R.drawable.ic_add, getString(R.string.menu_new_tab))
        listRow(
            open,
            "new_incognito",
            R.drawable.ic_incognito,
            getString(R.string.menu_new_incognito),
        )
        listRow(open, "bookmarks", R.drawable.ic_bookmark, getString(R.string.menu_bookmark_short))
        listRow(open, "history", R.drawable.ic_history, getString(R.string.menu_history_short))
        listRow(open, "downloads", R.drawable.ic_download, getString(R.string.menu_downloads_short))

        // —— 工具：页面内容本身的处理
        listRow(tools, "sniffer", R.drawable.ic_radar, getString(R.string.menu_sniffer_short))
        listRow(
            tools,
            "screenshot",
            R.drawable.ic_camera,
            getString(R.string.menu_screenshot_short),
        )
        listRow(tools, "images", R.drawable.ic_image, getString(R.string.menu_save_images))
        listRow(tools, "block_element", R.drawable.ic_block, getString(R.string.menu_block_element))
        listRow(tools, "print", R.drawable.ic_print, getString(R.string.menu_print))
        listRow(tools, "clean_url", R.drawable.ic_clean_url, getString(R.string.menu_clean_url))

        // —— 显示：读起来怎么样。开着才标注，关闭是默认态。
        listRow(
            display,
            "reader",
            R.drawable.ic_reader,
            if (state.readerMode) getString(R.string.menu_exit_reader)
            else getString(R.string.menu_reader),
            if (state.readerMode) "开" else null,
            state.readerMode,
        )
        listRow(
            display,
            "night",
            R.drawable.ic_night,
            getString(R.string.menu_night),
            if (state.nightMode) "开" else null,
            state.nightMode,
        )
        listRow(
            display,
            "image_mode",
            R.drawable.ic_image_off,
            getString(R.string.menu_image_mode),
            state.imageLabel,
            state.imageLabel != "关闭",
        )
        listRow(
            display,
            "desktop",
            R.drawable.ic_desktop,
            getString(R.string.menu_desktop_ua),
            if (state.desktopUa) "开" else null,
            state.desktopUa,
        )

        if (state.plugins.isNotEmpty()) {
            view.findViewById<View>(R.id.title_plugins).visibility = View.VISIBLE
            plugins.visibility = View.VISIBLE
            state.plugins.forEach { listRow(plugins, it.id, it.icon, it.label) }
        }

        // —— 更多
        listRow(more, "desktop_shortcut", R.drawable.ic_shortcut, getString(R.string.menu_shortcut))
        listRow(more, "plugins", R.drawable.ic_extension, getString(R.string.menu_plugins))
        listRow(more, "diag", R.drawable.ic_pulse, getString(R.string.diag_title))
        listRow(more, "settings", R.drawable.ic_settings, getString(R.string.menu_settings))
    }

    /**
     * 列表行：左图标、中间标题、右边状态。
     *
     * 左右内边距取 24dp，跟组标题的缩进对齐——2.10.0 那版是 16dp，标题在 24、行在 16，
     * 整片文字像被人推歪了一格。
     */
    private fun listRow(
        parent: LinearLayout,
        id: String,
        iconRes: Int,
        title: String,
        trailing: String? = null,
        trailingOn: Boolean = false,
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
            setPadding(dp(24), 0, dp(24), 0)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { marginStart = dp(14) }
            },
        )
        if (trailing != null) {
            row.addView(
                TextView(ctx).apply {
                    text = trailing
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (trailingOn) accent else onSurfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
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
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        androidx.core.content.ContextCompat.getDrawable(ctx, value.resourceId)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet
}
