package com.pluginbrowser.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.pluginbrowser.R
import kotlin.math.min

/**
 * 下拉刷新。
 *
 * 为什么不用 androidx.swiperefreshlayout：那个库判断「列表是否到顶」靠
 * `canScrollVertically(-1)`，而 WebView 的内容滚动由 Chromium 内部完成，
 * View 这一层始终认为自己没滚过——拿它判断永远是「已在顶部」，
 * 结果是网页滚到一半往下拉也会触发刷新，比没有这个功能更烦人。
 *
 * 所以这里的「到顶」由宿主回答（[atTopProvider]），数据来自注入脚本上报的
 * 真实 `window.scrollY`。容器自己只负责手势和动画。
 *
 * 手势上刻意保守：只有纵向位移明确压过横向、并且当前确实在顶部时才接管。
 * 网页内部那些横向滑动的轮播图不能被抢。
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 真的该刷新了才回调；宿主负责加载，加载完调 [finishRefresh] */
    var onRefresh: (() -> Unit)? = null

    /** 当前页面是否已经滚到最顶。宿主用网页上报的真实位置回答 */
    var atTopProvider: () -> Boolean = { true }

    private val indicator = RefreshIndicator(context)
    private val indicatorSize = dp(42f).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val triggerDistance = dp(72f)
    private val maxPull = dp(160f)
    private val holdOffset = dp(54f)

    private var startY = 0f
    private var startX = 0f
    private var pulling = false
    private var refreshing = false
    private var watchdog: Runnable? = null

    init {
        // 必须给明确的尺寸：默认的 MATCH_PARENT 会让 indicator.height 等于整个容器，
        // 于是「藏到屏幕上方」就变成藏了一整屏
        addView(indicator, LayoutParams(indicatorSize, indicatorSize))
        indicator.visibility = INVISIBLE
        indicator.translationY = -indicatorSize.toFloat()
    }

    fun isRefreshing(): Boolean = refreshing

    /** 宿主在加载结束时调用。没调也没事，有 8 秒兜底 */
    fun finishRefresh() {
        if (!refreshing) return
        refreshing = false
        watchdog?.let { removeCallbacks(it) }
        watchdog = null
        indicator.refreshing = false
        contentView()?.animate()?.translationY(0f)?.setDuration(220)?.start()
        indicator.animate()
            ?.translationY(-indicatorSize.toFloat())
            ?.alpha(0f)
            ?.setDuration(220)
            ?.withEndAction { indicator.visibility = INVISIBLE }
            ?.start()
    }

    /** 切标签、进阅读模式这类会换内容的时刻，必须复位，否则新页面顶部凭空矮一截 */
    fun reset() {
        watchdog?.let { removeCallbacks(it) }
        watchdog = null
        refreshing = false
        pulling = false
        indicator.refreshing = false
        indicator.visibility = INVISIBLE
        indicator.alpha = 0f
        indicator.translationY = -indicatorSize.toFloat()
        contentView()?.translationY = 0f
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val left = (width - indicatorSize) / 2
        val top = dp(10f).toInt()
        indicator.layout(left, top, left + indicatorSize, top + indicatorSize)
        if (!refreshing && !pulling) {
            indicator.translationY = -indicatorSize.toFloat()
        }
    }

    /**
     * 切标签时 TabManager 会 `container.removeAllViews()` 再挂新 WebView。
     * 不拦一下的话指示器会被一起清掉——第一次下拉能看到圆片，切个标签就再也看不到了，
     * 而且是那种「只在特定顺序下消失」的问题，最难查。
     */
    override fun removeAllViews() {
        for (i in childCount - 1 downTo 0) {
            if (getChildAt(i) !== indicator) removeViewAt(i)
        }
    }

    override fun removeAllViewsInLayout() {
        for (i in childCount - 1 downTo 0) {
            if (getChildAt(i) !== indicator) removeViewAt(i)
        }
    }

    /** 拖拽期间被平移的那一层：除指示器之外唯一的内容子 View */
    private fun contentView(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== indicator) return child
        }
        return null
    }

    /**
     * 自己接管整条事件链，而不是用 onInterceptTouchEvent + onTouchEvent。
     *
     * 原因是个很实际的问题：WebView 在手指按下的第一批 MOVE 里就会调
     * requestDisallowInterceptTouchEvent(true)（它认为自己要滚动），
     * 父容器的 onInterceptTouchEvent 从那以后就不再被调用。
     * 等它把控制权交还回来，手指往往已经滑出去几百像素——拦截确实发生了，
     * 但位移是在「拦截那一帧」才开始算的，之后没剩几个 MOVE 就抬手，
     * 结果就是「能看到页面被拉动一下，却不触发刷新」。
     *
     * dispatchTouchEvent 在链路最外层，不受 disallow 影响，能从头到尾盯着这一次手势。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                pulling = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (refreshing) return true
                val dy = ev.y - startY
                val dx = ev.x - startX
                if (!pulling) {
                    // 横向意图、没到阈值、或者页面根本不在顶部：一概不碰
                    if (dy <= touchSlop || dy < kotlin.math.abs(dx) * 1.2f) {
                        return super.dispatchTouchEvent(ev)
                    }
                    if (!atTopProvider()) return super.dispatchTouchEvent(ev)

                    pulling = true
                    cancelContent(ev)
                    indicator.visibility = VISIBLE
                    indicator.alpha = 1f
                }
                // 0.55 的阻尼：手指滑 100，页面下移 55。1:1 会太轻，一碰就到底
                val offset = (dy * 0.55f).coerceIn(0f, maxPull)
                contentView()?.translationY = offset
                indicator.translationY = (offset * 0.65f) - indicatorSize + dp(6f)
                indicator.progress = (offset / triggerDistance).coerceIn(0f, 1f)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (refreshing) return true
                if (!pulling) return super.dispatchTouchEvent(ev)
                pulling = false
                val offset = contentView()?.translationY ?: 0f
                val reached = offset >= triggerDistance && ev.actionMasked == MotionEvent.ACTION_UP
                if (reached) startRefresh() else settleBack()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 网页已经收到 DOWN 和若干 MOVE，被我们中途接走时必须补一个 CANCEL。
     * 不补的话它会停在「按下未抬起」的状态：链接一直高亮，
     * 下一次触摸还可能被并进同一段手势里。
     */
    private fun cancelContent(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        contentView()?.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun startRefresh() {
        refreshing = true
        indicator.progress = 1f
        indicator.refreshing = true
        indicator.visibility = VISIBLE
        contentView()?.animate()?.translationY(holdOffset)?.setDuration(180)?.start()
        indicator.animate()
            ?.translationY(0f)
            ?.setDuration(180)
            ?.start()
        onRefresh?.invoke()
        // 网页加载失败、被别的标签顶掉、或者干脆没回调 finishRefresh 时，
        // 别让转圈永远停在那里——那看起来就是「卡死了」
        val stop = Runnable { finishRefresh() }
        watchdog = stop
        postDelayed(stop, 8000)
    }

    private fun settleBack() {
        contentView()?.animate()?.translationY(0f)?.setDuration(200)?.start()
        indicator.animate()
            ?.translationY(-indicatorSize.toFloat())
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction { indicator.visibility = INVISIBLE }
            ?.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 刷新指示器：一个白色圆片加一段圆弧。
     *
     * 没用 ProgressBar 是因为它的 indeterminate 动画在系统主题下经常被压成
     * 一根细线，盖在网页上根本看不见。自绘能保证尺寸和对比度。
     */
    private class RefreshIndicator(context: Context) : View(context) {

        var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

        var refreshing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimation() else stopAnimation()
        }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpOf(2.6f)
            strokeCap = Paint.Cap.ROUND
            color = themeColor(com.google.android.material.R.attr.colorPrimary)
        }
        private val rect = RectF()
        private var rotation = 0f
        private val spinner = object : Runnable {
            override fun run() {
                rotation = (rotation + 11f) % 360f
                invalidate()
                postOnAnimation(this)
            }
        }

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(com.google.android.material.R.attr.colorSurface))
            }
            elevation = dpOf(4f)
        }

        private fun startAnimation() {
            removeCallbacks(spinner)
            post(spinner)
        }

        private fun stopAnimation() {
            removeCallbacks(spinner)
            rotation = 0f
            invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // 忘记停会让这个 View 被 Runnable 拽着不放，切页面后白转
            removeCallbacks(spinner)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dpOf(7f)
            rect.set(pad, pad, width - pad, height - pad)
            val sweep = if (refreshing) 260f else 40f + 260f * progress
            canvas.save()
            canvas.rotate(rotation, width / 2f, height / 2f)
            canvas.drawArc(rect, -90f, sweep, false, arcPaint)
            canvas.restore()
        }

        private fun dpOf(v: Float): Float = v * resources.displayMetrics.density

        private fun themeColor(attr: Int): Int {
            val tv = TypedValue()
            if (!context.theme.resolveAttribute(attr, tv, true)) return 0xFF1A73E8.toInt()
            return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        }
    }
}
