package com.pluginbrowser.site

/**
 * 站点适配能对页面做的事。
 *
 * 分两类：
 * - 原生类（WebView 配置、系统行为）：UA、自动播放、全屏横屏、后台播放。这些脚本改不了，
 *   必须在加载前改 WebView 设置，所以由 SiteRuleEngine.applyTo 在导航时统一套用；
 * - 注入类（改页面 DOM）：拦遮罩、拦 App 跳转。靠 SiteScripts 里的脚本完成。
 */
enum class SiteAction {
    /** 吞掉 intent://、market:// 这类唤起 App 的跳转，留在网页里 */
    BLOCK_APP_JUMP,

    /** 隐藏「打开 App」「下载 App」横幅与浮层，并拦掉它们的点击 */
    HIDE_APP_BANNER,

    /** 全屏视频自动转横屏 */
    FULLSCREEN_LANDSCAPE,

    /** 允许无手势自动播放（视频站点的默认行为，别的站点不该开） */
    MEDIA_AUTOPLAY,

    /** 切到后台不暂停，继续出声 */
    BACKGROUND_PLAY,

    /** 页面适合阅读模式（只影响菜单提示，阅读模式本身任何页面都能手动用） */
    READER_READY,
}

enum class SiteGroup(val title: String) {
    APP_JUMP("打开 App 拦截"),
    VIDEO("视频"),
    READER("阅读"),
}

/**
 * 一条站点适配规则。
 *
 * 之所以做成「声明式 + 域名表」，而不是在浏览器各处写 if：
 * 一条规则的改动只碰这张表，不需要再回来读 WebViewClient / Fragment 里散落的判断，
 * 用户也能在设置里逐条开关。
 */
data class SiteRule(
    val id: String,
    val name: String,
    /** 域名匹配：精确域名或后缀；写 "example.com" 时 www.example.com、m.example.com 都命中 */
    val hosts: List<String>,
    val summary: String,
    val actions: Set<SiteAction>,
    val defaultEnabled: Boolean = true,
) {
    val group: SiteGroup
        get() = when {
            SiteAction.BLOCK_APP_JUMP in actions -> SiteGroup.APP_JUMP
            SiteAction.FULLSCREEN_LANDSCAPE in actions -> SiteGroup.VIDEO
            else -> SiteGroup.READER
        }

    fun matches(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return hosts.any { pattern ->
            val p = pattern.removePrefix("*.").lowercase()
            host == p || host.endsWith(".$p")
        }
    }

    /** 设置页里显示的域名摘要 */
    val hostLabel: String get() = hosts.joinToString(" · ")
}

/** 内置规则表。新增一个站点就在这里加一条，不用改浏览器代码。 */
object SiteRules {

    private val APP_JUMP_ACTIONS = setOf(SiteAction.BLOCK_APP_JUMP, SiteAction.HIDE_APP_BANNER)

    private fun video(autoplay: Boolean = true, background: Boolean = true): Set<SiteAction> =
        buildSet {
            add(SiteAction.FULLSCREEN_LANDSCAPE)
            if (autoplay) add(SiteAction.MEDIA_AUTOPLAY)
            if (background) add(SiteAction.BACKGROUND_PLAY)
        }

    val ALL: List<SiteRule> = listOf(
        SiteRule(
            id = "jump_zhihu",
            name = "知乎",
            hosts = listOf("zhihu.com"),
            summary = "不再弹「打开知乎 App」，点答案不会被拽去应用商店",
            actions = APP_JUMP_ACTIONS,
        ),
        SiteRule(
            id = "jump_baidu",
            name = "百度",
            hosts = listOf("baidu.com"),
            summary = "搜索结果页的下载条和唤起 App 拦截去掉，直接看网页",
            actions = APP_JUMP_ACTIONS,
        ),
        SiteRule(
            id = "jump_weibo",
            name = "微博",
            hosts = listOf("weibo.com", "weibo.cn"),
            summary = "m 站经常一打开就跳 App，这里拦在网页里继续看",
            actions = APP_JUMP_ACTIONS,
        ),
        SiteRule(
            id = "jump_taobao",
            name = "淘宝 / 天猫",
            hosts = listOf("taobao.com", "tmall.com"),
            summary = "商品页底部「打开淘宝」浮层隐藏，跳转不再打断浏览",
            actions = APP_JUMP_ACTIONS,
        ),
        SiteRule(
            id = "jump_jd",
            name = "京东",
            hosts = listOf("jd.com"),
            summary = "去掉唤起 App 的遮罩与跳转",
            actions = APP_JUMP_ACTIONS,
        ),
        SiteRule(
            id = "video_bilibili",
            name = "哔哩哔哩",
            hosts = listOf("bilibili.com", "b23.tv"),
            summary = "全屏自动横屏，切后台继续放声音",
            actions = video(),
        ),
        SiteRule(
            id = "video_youtube",
            name = "YouTube",
            hosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
            summary = "全屏自动横屏，切后台继续放声音",
            actions = video(),
        ),
        SiteRule(
            id = "video_cn",
            name = "腾讯视频 / 爱奇艺 / 优酷 / 芒果",
            hosts = listOf("v.qq.com", "iqiyi.com", "youku.com", "mgtv.com"),
            summary = "全屏视频自动横屏，不用再手动转一次",
            actions = video(background = false),
        ),
        SiteRule(
            id = "reader_wechat",
            name = "微信公众号文章",
            hosts = listOf("mp.weixin.qq.com"),
            summary = "菜单里的阅读模式对这些页面提取得最干净",
            actions = setOf(SiteAction.READER_READY),
        ),
        SiteRule(
            id = "reader_zhihu",
            name = "知乎专栏",
            hosts = listOf("zhuanlan.zhihu.com"),
            summary = "长文去掉侧栏与推荐位，只留正文",
            actions = setOf(SiteAction.READER_READY),
        ),
    )

    fun byId(id: String): SiteRule? = ALL.firstOrNull { it.id == id }
}
