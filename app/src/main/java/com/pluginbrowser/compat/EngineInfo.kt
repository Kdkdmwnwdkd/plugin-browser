package com.pluginbrowser.compat

/**
 * 内核体检的判定逻辑。
 *
 * 为什么要有这个：Kite 自己是一层壳，网页长什么样、会不会崩，取决于手机里的
 * WebView —— 而 WebView 不是 Kite 能升的，它跟着系统走。同一个 APK 装在
 * WebView 120 的机器上是浏览器，装在 WebView 76 的机器上就是个「很多网站打不开」的壳。
 *
 * 所以「适配所有品牌」这句话拆开，真正能做的只有两件：
 * 1. 如实告诉用户他这台机器的内核到了什么程度、哪些站会出问题；
 * 2. 按内核能力决定 Kite 自己要不要降级（比如低内核不做强制暗色注入）。
 *
 * 这里全是纯函数，不碰 android.*，方便单测。
 */
object EngineInfo {

    /** 内核档位。阈值不是拍脑袋，见各个取值上的注释。 */
    enum class Grade {
        /** 系统里没有可用的 WebView：被停用、被卸载、或 ROM 阉割。除了提示用户去装，做不了别的。 */
        MISSING,

        /** Chrome < 80。连可选链、空值合并都没有，现代前端打包产物普遍直接语法报错。 */
        ANCIENT,

        /** 80–89。语法能过，但 CSS 的 gap/aspect-ratio/:is() 这一批还缺，布局会散。 */
        OLD,

        /** 90–104。日常够用，个别吃新特性的站点（新式设计系统、Web Components 重度站）会有毛病。 */
        FAIR,

        /** 105 以上。现代网页该有的都有，Kite 这边不需要为它做任何妥协。 */
        GOOD,
    }

    /** 低于这个版本，绝大部分现代站点会直接抛 SyntaxError（GitHub 就是典型）。 */
    const val MIN_RUNNABLE = 80

    /** 到这个版本，Kite 的功能可以全开。 */
    const val MIN_COMFORTABLE = 105

    /**
     * 从 `83.0.4103.120` 这样的版本串里取主版本号。
     * 认不出来的返回 0，交给调用方当成 MISSING 处理，不要瞎猜成新版本。
     */
    fun parseMajor(version: String?): Int {
        val v = version?.trim().orEmpty()
        if (v.isEmpty()) return 0
        val head = v.substringBefore('.')
        return head.toIntOrNull() ?: 0
    }

    fun gradeOf(major: Int, packageName: String?): Grade = when {
        // 包名为空 = 系统里压根没有 WebView 提供者
        packageName.isNullOrBlank() -> Grade.MISSING
        major <= 0 -> Grade.MISSING
        major < MIN_RUNNABLE -> Grade.ANCIENT
        major < 90 -> Grade.OLD
        major < MIN_COMFORTABLE -> Grade.FAIR
        else -> Grade.GOOD
    }

    /** 一句话结论，直接显示给用户，不要夹带技术名词。 */
    fun headline(grade: Grade): String = when (grade) {
        Grade.MISSING -> "这台机器没有可用的网页内核"
        Grade.ANCIENT -> "内核太旧，很多网站会打不开"
        Grade.OLD -> "内核偏旧，部分网页会排版错乱"
        Grade.FAIR -> "内核可用，少数新网站可能有小毛病"
        Grade.GOOD -> "内核正常，网页该有的都能跑"
    }

    /** 对应的动作建议，MISSING 和 ANCIENT 才需要用户动手。 */
    fun action(grade: Grade): String = when (grade) {
        Grade.MISSING ->
            "去应用商店搜索「Android System WebView」或「WebView」装上并启用；" +
                "华为/荣耀机型搜「华为浏览器」更新。装完重开 Kite。"
        Grade.ANCIENT ->
            "去应用商店更新「Android System WebView」。更新不了的话，" +
                "在 Kite 设置里打开「桌面版网站」能绕过一部分问题，但不是全部。"
        Grade.OLD ->
            "能更新就更新一下「Android System WebView」，更新后排版问题会少很多。"
        Grade.FAIR, Grade.GOOD -> "不需要做什么。"
    }

    /** 厂商。只用于给针对性的提示，不做任何行为分支。 */
    enum class Vendor(val label: String) {
        MEIZU("魅族"),
        HUAWEI("华为"),
        HONOR("荣耀"),
        XIAOMI("小米"),
        OPPO("OPPO"),
        VIVO("vivo"),
        SAMSUNG("三星"),
        OTHER("其他"),
    }

    fun vendorOf(brand: String?, manufacturer: String?): Vendor {
        val s = "${brand.orEmpty()} ${manufacturer.orEmpty()}".lowercase()
        return when {
            s.contains("meizu") || s.contains("魅族") -> Vendor.MEIZU
            // 荣耀独立后 brand 仍是 HONOR，manufacturer 有时写 HUAWEI，必须先判荣耀
            s.contains("honor") || s.contains("荣耀") -> Vendor.HONOR
            s.contains("huawei") || s.contains("华为") -> Vendor.HUAWEI
            s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") ||
                s.contains("小米") || s.contains("红米") -> Vendor.XIAOMI
            s.contains("oppo") || s.contains("realme") || s.contains("oneplus") -> Vendor.OPPO
            s.contains("vivo") || s.contains("iqoo") -> Vendor.VIVO
            s.contains("samsung") || s.contains("三星") -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /**
     * 厂商相关的已知坑。这些不是猜的，是这些 ROM 上长期存在的差异：
     * 内核由谁维护、能不能单独更新、有没有改过 WebView 的默认行为。
     */
    fun notesFor(vendor: Vendor, grade: Grade): List<String> = buildList {
        when (vendor) {
            Vendor.MEIZU ->
                add("魅族的内核跟 Flyme 版本走，应用商店里通常搜不到「Android System WebView」。想升内核基本得升系统。")
            Vendor.HUAWEI ->
                add("华为机型的内核是 com.huawei.webview，跟着 HMS Core 更新，不走 Google 那套。")
            Vendor.HONOR ->
                add("荣耀独立后的机型内核仍是华为那一套（com.huawei.webview），更新入口在应用市场。")
            Vendor.XIAOMI ->
                add("小米/红米的内核可以在应用商店单独更新「Android System WebView」，这是国产机里最好升的一类。")
            Vendor.OPPO ->
                add("OPPO/一加/realme 的内核更新入口在「软件商店 → 系统应用更新」，不一定有独立入口。")
            Vendor.VIVO ->
                add("vivo/iQOO 的内核跟随系统更新，应用商店一般不单独提供。")
            Vendor.SAMSUNG ->
                add("三星机型可以直接在 Galaxy Store / Play 商店更新 Android System WebView。")
            Vendor.OTHER ->
                add("这个品牌没有专门的已知问题记录。内核版本以本页读到的为准。")
        }
        if (grade == Grade.ANCIENT || grade == Grade.OLD) {
            add("内核低于 105 时，Kite 会关掉几项依赖新特性的功能（见下方实测），这是有意的，不是坏了。")
        }
        // 鸿蒙这条跟厂商无关，是系统层面的分叉，凡是华为/荣耀都提示一次
        if (vendor == Vendor.HUAWEI || vendor == Vendor.HONOR) {
            add("如果系统是 HarmonyOS NEXT（纯血鸿蒙，设置里显示 HarmonyOS 5 及以上），它不认 APK，这个包装不上，需要在鸿蒙上另做一个版本。")
        }
    }

    /**
     * 把实测脚本返回的 `k=v;k=v` 串解析成有序键值对。
     * 返回 null 表示压根没拿到结果（脚本语法错误 / 页面还没准备好）。
     */
    fun parseProbe(raw: String?): List<Pair<String, String>>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return null
        // evaluateJavascript 会把结果再套一层 JSON 字符串引号
        val body = s.removeSurrounding("\"")
        if (body.isBlank()) return null
        val out = body.split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
        return out.ifEmpty { null }
    }

    /** 实测结果里有没有失败项。 */
    fun failedProbes(probes: List<Pair<String, String>>): List<String> =
        probes.filter { it.second == "false" || it.second == "0" }.map { it.first }
}
