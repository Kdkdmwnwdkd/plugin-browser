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
