package com.pluginbrowser.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * GitHub 通道的纯逻辑测试。
 * 只覆盖「哪些域名该接管」「代理串怎么解析」这两件容易写错又没法靠肉眼验证的事。
 */
class GitHubChannelTest {

    @Test
    fun `只接管 GitHub 自己的域名`() {
        assertTrue(GitHubChannel.isGitHubHost("github.com"))
        assertTrue(GitHubChannel.isGitHubHost("www.github.com"))
        assertTrue(GitHubChannel.isGitHubHost("api.github.com"))
        assertTrue(GitHubChannel.isGitHubHost("gist.github.com"))
        assertTrue(GitHubChannel.isGitHubHost("raw.githubusercontent.com"))
        assertTrue(GitHubChannel.isGitHubHost("avatars.githubusercontent.com"))
        assertTrue(GitHubChannel.isGitHubHost("github.githubassets.com"))
    }

    @Test
    fun `相似域名不能误伤`() {
        assertFalse(GitHubChannel.isGitHubHost("notgithub.com"))
        assertFalse(GitHubChannel.isGitHubHost("github.com.evil.tld"))
        assertFalse(GitHubChannel.isGitHubHost("fakegithubusercontent.com"))
        assertFalse(GitHubChannel.isGitHubHost("github.io"))
        assertFalse(GitHubChannel.isGitHubHost(null))
        assertFalse(GitHubChannel.isGitHubHost(""))
    }

    @Test
    fun `默认只接管静态资源域名`() {
        // 网页、登录、接口一律不接：这些请求依赖 Cookie 轮换与重定向链
        assertFalse(GitHubChannel.shouldHandle("github.com", catchPages = false))
        assertFalse(GitHubChannel.shouldHandle("api.github.com", catchPages = false))
        assertFalse(GitHubChannel.shouldHandle("gist.github.com", catchPages = false))
        assertFalse(GitHubChannel.shouldHandle("codeload.github.com", catchPages = false))

        // 静态资源域才接管，缓存与自定义 DNS 在这里才有意义
        assertTrue(GitHubChannel.shouldHandle("raw.githubusercontent.com", catchPages = false))
        assertTrue(GitHubChannel.shouldHandle("avatars.githubusercontent.com", catchPages = false))
        assertTrue(GitHubChannel.shouldHandle("camo.githubusercontent.com", catchPages = false))
        assertTrue(GitHubChannel.shouldHandle("objects.githubusercontent.com", catchPages = false))
        assertTrue(GitHubChannel.shouldHandle("github.githubassets.com", catchPages = false))

        // 高级开关打开后回到旧行为，但仍不碰非 GitHub 域名
        assertTrue(GitHubChannel.shouldHandle("github.com", catchPages = true))
        assertTrue(GitHubChannel.shouldHandle("api.github.com", catchPages = true))
        assertFalse(GitHubChannel.shouldHandle("example.com", catchPages = true))
        assertFalse(GitHubChannel.shouldHandle(null, catchPages = true))
        assertFalse(GitHubChannel.shouldHandle("", catchPages = true))
    }

    @Test
    fun `静态资源域判定不能误伤`() {
        assertTrue(GitHubChannel.isAssetHost("raw.githubusercontent.com"))
        assertTrue(GitHubChannel.isAssetHost("github.githubassets.com"))
        assertFalse(GitHubChannel.isAssetHost("github.com"))
        assertFalse(GitHubChannel.isAssetHost("githubusercontent.com"))
        assertFalse(GitHubChannel.isAssetHost("evil-githubassets.com"))
        assertFalse(GitHubChannel.isAssetHost(null))
    }

    @Test
    fun `登录链路一律不接管`() {
        // 这些路径上全是 POST + 302 + 轮换的会话 Cookie，中转一次就容易登录失败
        assertTrue(GitHubChannel.isAuthPath("/session"))
        assertTrue(GitHubChannel.isAuthPath("/sessions"))
        assertTrue(GitHubChannel.isAuthPath("/session/verify"))
        assertTrue(GitHubChannel.isAuthPath("/login"))
        assertTrue(GitHubChannel.isAuthPath("/login/oauth/authorize"))
        assertTrue(GitHubChannel.isAuthPath("/signup"))
        assertTrue(GitHubChannel.isAuthPath("/password_reset"))
        assertTrue(GitHubChannel.isAuthPath("/account_verifications"))

        // 普通页面、仓库页、release 直链照常走通道
        assertFalse(GitHubChannel.isAuthPath("/"))
        assertFalse(GitHubChannel.isAuthPath("/Kdkdmwnwdkd/plugin-browser"))
        assertFalse(GitHubChannel.isAuthPath("/Kdkdmwnwdkd/plugin-browser/releases/download/v2.4.4/x.apk"))
        assertFalse(GitHubChannel.isAuthPath(null))
        assertFalse(GitHubChannel.isAuthPath(""))
    }

    @Test
    fun `代理串解析`() {
        assertNull(GitHubChannel.proxyOf(""))
        assertNull(GitHubChannel.proxyOf("   "))

        val plain = GitHubChannel.proxyOf("127.0.0.1:7890")!!
        assertEquals(Proxy.Type.HTTP, plain.type())
        assertEquals(7890, (plain.address() as java.net.InetSocketAddress).port)

        val http = GitHubChannel.proxyOf("http://192.168.1.5:8080")!!
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(8080, (http.address() as java.net.InetSocketAddress).port)

        val socks = GitHubChannel.proxyOf("socks5://127.0.0.1:1080")!!
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertEquals(1080, (socks.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `没写端口时用该协议的常用端口`() {
        val http = GitHubChannel.proxyOf("proxy.lan")!!
        assertEquals(7890, (http.address() as java.net.InetSocketAddress).port)

        val socks = GitHubChannel.proxyOf("socks5://proxy.lan")!!
        assertEquals(1080, (socks.address() as java.net.InetSocketAddress).port)
    }
}
