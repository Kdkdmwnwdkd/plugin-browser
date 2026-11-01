package com.pluginbrowser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商选择。
 *
 * 这块看着没什么可测的，但「默认值」是个容易被顺手改坏的地方：
 * 一旦有人觉得「百度是主力，默认设百度吧」，新用户第一次点翻译就会撞上
 * 「请先去申请 key」——而这条路径没有任何测试会报警。
 */
class TranslateProviderTest {

    @Test
    fun `没选过时默认落到内置免费源`() {
        assertEquals(TranslateProvider.TENCENT, TranslateProvider.of(null))
        assertEquals(TranslateProvider.TENCENT, TranslateProvider.of(""))
        assertEquals(TranslateProvider.TENCENT, TranslateProvider.of("not-a-provider"))
    }

    @Test
    fun `存过的供应商能原样读回`() {
        TranslateProvider.entries.forEach { provider ->
            assertEquals(provider, TranslateProvider.of(provider.id))
        }
    }

    @Test
    fun `内置源没有任何必填项`() {
        // 空字段集合 = isConfigured 恒为 true，这就是「免配置」的实现方式
        assertTrue(TranslateProvider.TENCENT.fields.isEmpty())
    }

    @Test
    fun `其余供应商都至少有一个字段`() {
        TranslateProvider.entries
            .filter { it != TranslateProvider.TENCENT }
            .forEach { assertFalse("${it.id} 缺少配置字段", it.fields.isEmpty()) }
    }

    @Test
    fun `每个供应商的 id 唯一且非空`() {
        val ids = TranslateProvider.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `目标语言的代码与腾讯接口一致`() {
        // 腾讯用 zh / en / ja / ko，和这里的枚举是同一套；改任何一边都会让译文变原文
        assertEquals(listOf("zh", "en", "ja", "ko"), TranslateTarget.entries.map { it.code })
    }
}
