package com.pluginbrowser.compat

import com.pluginbrowser.compat.EngineInfo.Grade
import com.pluginbrowser.compat.EngineInfo.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核判定的边界。
 *
 * 这些阈值会被直接显示给用户当结论用（「内核太旧」「不需要做什么」），
 * 判错一个档位等于给错建议，所以边界值逐个钉住。
 */
class EngineInfoTest {

    @Test
    fun `版本号取主版本`() {
        assertEquals(83, EngineInfo.parseMajor("83.0.4103.120"))
        assertEquals(138, EngineInfo.parseMajor("138.0.7204.157"))
        assertEquals(105, EngineInfo.parseMajor("105"))
        assertEquals(105, EngineInfo.parseMajor(" 105.1 "))
    }

    @Test
    fun `读不出版本号就返回 0 而不是瞎猜`() {
        assertEquals(0, EngineInfo.parseMajor(null))
        assertEquals(0, EngineInfo.parseMajor(""))
        assertEquals(0, EngineInfo.parseMajor("unknown"))
        assertEquals(0, EngineInfo.parseMajor(".4103"))
    }

    @Test
    fun `没有内核提供者一律算缺失`() {
        assertEquals(Grade.MISSING, EngineInfo.gradeOf(138, null))
        assertEquals(Grade.MISSING, EngineInfo.gradeOf(138, ""))
        assertEquals(Grade.MISSING, EngineInfo.gradeOf(0, "com.google.android.webview"))
    }

    @Test
    fun `档位边界`() {
        val pkg = "com.google.android.webview"
        assertEquals(Grade.ANCIENT, EngineInfo.gradeOf(79, pkg))
        assertEquals(Grade.OLD, EngineInfo.gradeOf(80, pkg))
        assertEquals(Grade.OLD, EngineInfo.gradeOf(89, pkg))
        assertEquals(Grade.FAIR, EngineInfo.gradeOf(90, pkg))
        assertEquals(Grade.FAIR, EngineInfo.gradeOf(104, pkg))
        assertEquals(Grade.GOOD, EngineInfo.gradeOf(105, pkg))
        assertEquals(Grade.GOOD, EngineInfo.gradeOf(140, pkg))
    }

    @Test
    fun `荣耀不能被认成华为`() {
        // 荣耀机器的 manufacturer 常写成 HUAWEI，只看 manufacturer 会判错
        assertEquals(Vendor.HONOR, EngineInfo.vendorOf("HONOR", "HUAWEI"))
        assertEquals(Vendor.HUAWEI, EngineInfo.vendorOf("HUAWEI", "HUAWEI"))
    }

    @Test
    fun `常见厂商识别`() {
        assertEquals(Vendor.MEIZU, EngineInfo.vendorOf("meizu", "Meizu"))
        assertEquals(Vendor.XIAOMI, EngineInfo.vendorOf("Redmi", "Xiaomi"))
        assertEquals(Vendor.XIAOMI, EngineInfo.vendorOf("POCO", "Xiaomi"))
        assertEquals(Vendor.OPPO, EngineInfo.vendorOf("realme", "realme"))
        assertEquals(Vendor.OPPO, EngineInfo.vendorOf("OnePlus", "OnePlus"))
        assertEquals(Vendor.VIVO, EngineInfo.vendorOf("iQOO", "vivo"))
        assertEquals(Vendor.SAMSUNG, EngineInfo.vendorOf("samsung", "samsung"))
        assertEquals(Vendor.OTHER, EngineInfo.vendorOf("nubia", "nubia"))
    }

    @Test
    fun `每个档位都有结论和建议，不能有空串`() {
        Grade.entries.forEach { g ->
            assertTrue(EngineInfo.headline(g).isNotBlank())
            assertTrue(EngineInfo.action(g).isNotBlank())
        }
    }

    @Test
    fun `华为荣耀会额外提示鸿蒙不认 APK`() {
        val notes = EngineInfo.notesFor(Vendor.HUAWEI, Grade.GOOD)
        assertTrue(notes.any { it.contains("HarmonyOS NEXT") })
        val meizu = EngineInfo.notesFor(Vendor.MEIZU, Grade.GOOD)
        assertTrue(meizu.none { it.contains("HarmonyOS NEXT") })
    }

    @Test
    fun `低内核会说明功能被降级`() {
        assertTrue(EngineInfo.notesFor(Vendor.MEIZU, Grade.OLD).any { it.contains("降") || it.contains("关掉") })
        assertTrue(EngineInfo.notesFor(Vendor.MEIZU, Grade.GOOD).none { it.contains("关掉") })
    }

    @Test
    fun `解析实测结果`() {
        // evaluateJavascript 返回的是带引号的 JSON 字符串
        val r = EngineInfo.parseProbe("\"a=true;b=false;c=1\"")
        assertEquals(listOf("a" to "true", "b" to "false", "c" to "1"), r)
    }

    @Test
    fun `拿不到结果时返回 null 而不是空列表`() {
        // 空列表会被上层当成「全部通过」，比没测更误导
        assertNull(EngineInfo.parseProbe(null))
        assertNull(EngineInfo.parseProbe(""))
        assertNull(EngineInfo.parseProbe("null"))
        assertNull(EngineInfo.parseProbe("\"\""))
        assertNull(EngineInfo.parseProbe("\"garbage\""))
    }

    @Test
    fun `挑出失败项`() {
        val probes = listOf("a" to "true", "b" to "false", "c" to "1", "d" to "0")
        assertEquals(listOf("b", "d"), EngineInfo.failedProbes(probes))
    }
}
