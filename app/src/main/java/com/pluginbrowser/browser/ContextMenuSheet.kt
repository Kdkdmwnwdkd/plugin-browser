package com.pluginbrowser.browser

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.pluginbrowser.R

/**
 * 长按链接 / 图片后弹出的小菜单。
 *
 * 和 BrowserMenuSheet 分开放：那个是「这个页面能做什么」，这个针对的是
 * 「手指底下这一块东西能做什么」，条目少、要贴着内容出现，用不着网格。
 *
 * 选项通过 [onPick] 回传 id，具体动作留在 Fragment 里做——
 * 这里不碰 WebView，免得弹层生命周期和页面生命周期纠缠在一起。
 */
class ContextMenuSheet : BottomSheetDialogFragment() {

    data class Item(val id: String, val icon: Int, val label: String)

    /** 顶部那行小字：链接域名或图片尺寸，帮用户确认「点的是哪个」 */
    var heading: String? = null
    var items: List<Item> = emptyList()
    var onPick: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val surface = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorSurface,
            0xFFFEFBFF.toInt(),
        )
        val onSurface = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            0xFF1B1B1F.toInt(),
        )
        val onVariant = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFF44474E.toInt(),
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(0, dp(4), 0, dp(10))
        }

        heading?.takeIf { it.isNotBlank() }?.let { text ->
            root.addView(
                TextView(ctx).apply {
                    this.text = text
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(onVariant)
                    setPadding(dp(20), dp(10), dp(20), dp(8))
                },
            )
        }

        items.forEach { item ->
            root.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52),
                    )
                    setPadding(dp(20), 0, dp(20), 0)
                    isClickable = true
                    isFocusable = true
                    foreground = ripple()
                    addView(
                        ImageView(ctx).apply {
                            setImageResource(item.icon)
                            imageTintList = android.content.res.ColorStateList.valueOf(onVariant)
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        },
                    )
                    addView(
                        TextView(ctx).apply {
                            text = item.label
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            setTextColor(onSurface)
                            setPadding(dp(16), 0, 0, 0)
                        },
                    )
                    setOnClickListener {
                        dismissAllowingStateLoss()
                        onPick?.invoke(item.id)
                    }
                },
            )
        }
        return root
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** selectableItemBackground 取的是主题里的水波纹，比自己拼一张遮罩稳 */
    private fun ripple(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getDrawable(requireContext(), tv.resourceId) else null
    }

    companion object {
        const val TAG = "context_menu"
    }
}
