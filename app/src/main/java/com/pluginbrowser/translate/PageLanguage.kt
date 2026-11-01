package com.pluginbrowser.translate

/**
 * 页面语言检测的结果。
 *
 * 判不准（[isUnknown]）时一律照常翻译——宁可白翻一次，也不能因为一次误判
 * 让用户点了翻译却什么都没发生。
 */
data class PageLanguage(
    val declared: String,
    val guess: String,
    val variant: String,
    val confidence: Float,
    val chars: Int,
) {

    val isUnknown: Boolean get() = guess == UNKNOWN

    /** 给用户看的语言名。判不出来就返回空串，界面上不显示这一段 */
    fun displayName(): String = when (guess) {
        ZH -> if (variant == HANT) "繁体中文" else "简体中文"
        EN -> "英文"
        JA -> "日文"
        KO -> "韩文"
        LATIN -> "其他拉丁字母语言"
        OTHER -> "其他语言"
        else -> ""
    }

    /**
     * 是否已经是目标语言、不必再翻。
     *
     * 繁体中文 + 目标简体中文**不算**「已经是」：那个操作确实有产出（繁体转简体），
     * 而且这恰好是很多人点翻译的真实原因。
     */
    fun isAlready(target: TranslateTarget): Boolean {
        if (isUnknown) return false
        return when (target) {
            TranslateTarget.ZH -> guess == ZH && variant != HANT
            TranslateTarget.EN -> guess == EN
            TranslateTarget.JA -> guess == JA
            TranslateTarget.KO -> guess == KO
        }
    }

    companion object {
        const val UNKNOWN = "unknown"
        const val ZH = "zh"
        const val EN = "en"
        const val JA = "ja"
        const val KO = "ko"
        const val LATIN = "latin"
        const val OTHER = "other"
        const val HANT = "hant"

        /**
         * 解析 translate.js 里 detect() 的返回；任何异常都退回「不知道」。
         *
         * 字段归一化放在 [of] 里而不是这里——org.json 在单元测试里只有 stump 实现，
         * 留在这里的话归一逻辑就没法测了。
         */
        fun parse(json: org.json.JSONObject?): PageLanguage? {
            if (json == null) return null
            return of(
                declared = json.optString("declared", ""),
                guess = json.optString("guess", UNKNOWN),
                variant = json.optString("variant", UNKNOWN),
                confidence = json.optDouble("conf", 0.0).toFloat(),
                chars = json.optInt("chars", 0),
            )
        }

        fun of(
            declared: String,
            guess: String,
            variant: String,
            confidence: Float,
            chars: Int,
        ) = PageLanguage(
            declared = declared,
            guess = guess.ifBlank { UNKNOWN },
            variant = variant.ifBlank { UNKNOWN },
            confidence = confidence,
            chars = chars,
        )
    }
}
