package com.pluginbrowser.translate

import android.content.Context
import android.content.SharedPreferences

/**
 * 翻译服务配置：支持多家供应商，字段由供应商自己声明，
 * 设置页据此动态生成表单，换供应商不需要改代码。
 */
enum class TranslateProvider(
    val id: String,
    val label: String,
    val fields: List<Field>,
    val help: String,
) {
    BAIDU("baidu", "百度大模型翻译", listOf(
        Field("appid", "APPID", "控制台 → 开发者信息，17 位数字"),
        Field("secret", "密钥", "同页的「密钥」，不是 API Key。建议长按粘贴而不是手打", secret = true),
    ), "用 APPID + 密钥签名调用。大模型翻译每月 100 万字符免费，QPS 较低，整页翻译会限速。"),

    CAIYUN("caiyun", "彩云小译", listOf(
        Field("token", "Token", "彩云小译开放平台的 token", secret = true),
    ), "接口简单、支持多段批量，返回顺序稳定，适合整页翻译。"),

    OPENAI("openai", "OpenAI 兼容接口", listOf(
        Field("base", "Base URL", "如 https://api.deepseek.com/v1"),
        Field("key", "API Key", "以 sk- 开头", secret = true),
        Field("model", "模型", "如 deepseek-chat / gpt-4o-mini"),
    ), "任何 OpenAI 兼容服务都能接：DeepSeek、月之暗面、硅基流动、本地 Ollama。"),

    LIBRE("libre", "LibreTranslate", listOf(
        Field("url", "服务地址", "如 https://libretranslate.com"),
        Field("key", "API Key", "自建可留空", secret = true),
    ), "开源可自建，完全免费；公共实例可能限流。");

    data class Field(
        val key: String,
        val label: String,
        val hint: String,
        val secret: Boolean = false,
    )

    companion object {
        fun of(id: String?): TranslateProvider =
            entries.firstOrNull { it.id == id } ?: BAIDU
    }
}

/** 目标语言（百度/彩云/Libre 用语言码，OpenAI 用自然语言描述） */
enum class TranslateTarget(val code: String, val label: String) {
    ZH("zh", "简体中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어");

    companion object {
        fun of(code: String?): TranslateTarget = entries.firstOrNull { it.code == code } ?: ZH
    }
}

/** 翻译配置的读写（全部存在本地，不上传） */
class TranslateSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pb_translate", Context.MODE_PRIVATE)

    var provider: TranslateProvider
        get() = TranslateProvider.of(prefs.getString(KEY_PROVIDER, null))
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.id).apply()

    var target: TranslateTarget
        get() = TranslateTarget.of(prefs.getString(KEY_TARGET, null))
        set(value) = prefs.edit().putString(KEY_TARGET, value.code).apply()

    fun value(provider: TranslateProvider, field: TranslateProvider.Field): String =
        prefs.getString("${provider.id}_${field.key}", "").orEmpty()

    fun putValue(provider: TranslateProvider, field: TranslateProvider.Field, value: String) {
        prefs.edit().putString("${provider.id}_${field.key}", sanitize(value)).apply()
    }

    /**
     * 规范化用户输入的密钥/ID：
     * 1) 全角转半角 —— 中文输入法下 "Ｄ" 与 "D" 字形几乎一样，但字节完全不同，
     *    服务端会直接判签名不匹配（54001 invalid sign）；
     * 2) 去掉不可见字符 —— 空白、NBSP、零宽、BOM，肉眼看不出来但同样让签名对不上。
     */
    private fun sanitize(raw: String): String = buildString {
        for (raw2 in raw) {
            val ch = when {
                raw2.code in 0xFF01..0xFF5E -> (raw2.code - 0xFEE0).toChar()   // 全角 ASCII
                raw2 == '\u3000' -> ' '                                        // 全角空格
                else -> raw2
            }
            if (ch.isWhitespace() ||
                ch == '\u00A0' || ch == '\u2007' || ch == '\u202F' ||
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' ||
                ch == '\uFEFF' || ch == '\u2060'
            ) {
                continue
            }
            append(ch)
        }
    }

    /** 当前供应商是否已配置齐全 */
    fun isConfigured(): Boolean {
        val current = provider
        return current.fields.filter { !it.key.endsWith("_optional") }.all { field ->
            // LibreTranslate 的 key 可留空
            if (current == TranslateProvider.LIBRE && field.key == "key") true
            else value(current, field).isNotBlank()
        }
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_TARGET = "target"
    }
}
