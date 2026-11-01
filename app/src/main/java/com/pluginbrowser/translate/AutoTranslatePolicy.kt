package com.pluginbrowser.translate

/**
 * 「这一页现在要不要自动翻」的判定。
 *
 * 抽成纯函数不是因为逻辑复杂，而是因为判错的代价两条都很贵：
 * 多判一次要烧掉十几秒和整页的请求，少判一次用户只会觉得「自动翻译坏了」。
 * 条件有六七个，散在 Fragment 里既没法测，也迟早会漏掉一个。
 */
object AutoTranslatePolicy {

    /** 跟随全局：只有用户点「翻译」时才翻 */
    const val MANUAL = 0

    /** 进入这个站点就自动翻 */
    const val ALWAYS = 1

    /** 这个站永远不翻（用户明确说过） */
    const val NEVER = 2

    fun normalize(mode: Int): Int = when (mode) {
        ALWAYS, NEVER -> mode
        else -> MANUAL
    }

    /**
     * 页面加载完之后，要不要直接开始翻。
     *
     * [page] 为 null 表示还没探测语言——此时其余条件都可以先判，语言相关的留给二次判定。
     */
    fun shouldStart(
        mode: Int,
        translated: Boolean,
        suppressed: Boolean,
        url: String?,
        page: PageLanguage?,
        target: TranslateTarget,
    ): Boolean {
        if (normalize(mode) != ALWAYS) return false

        // 已经翻过了。onPageFinished 会因站内锚点、pushState、iframe 之类再触发，
        // 不挡这一层，一次滚动就能把同一页翻两遍
        if (translated) return false

        // 用户刚点了「还原原文」。不挡的话按钮等于失灵：翻开、还原、立刻又翻开
        if (suppressed) return false

        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        // 判不准就翻：自动翻译最大的失败是「该翻的没翻」，白翻一次只是慢一点
        if (page != null && page.isAlready(target)) return false

        return true
    }

    /**
     * 手动翻译成功之后，要不要问一句「以后这个站自动翻」。
     *
     * 只在站点仍是「手动」且没问过时问。问过一次就记下来——
     * 用户说「先不用」也是回答，反复弹就是骚扰。
     */
    fun shouldAskAfterSuccess(mode: Int, askedBefore: Boolean): Boolean =
        normalize(mode) == MANUAL && !askedBefore

    /** 站点面板上显示的三态文案 */
    fun label(mode: Int): String = when (normalize(mode)) {
        ALWAYS -> "自动"
        NEVER -> "从不"
        else -> "手动"
    }
}
