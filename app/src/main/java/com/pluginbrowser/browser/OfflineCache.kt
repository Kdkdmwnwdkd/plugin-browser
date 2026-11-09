package com.pluginbrowser.browser

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 离线快照：页面加载成功后顺手存一份正文 HTML，断网时至少有内容可看。
 *
 * 不追完整离线浏览——图片、CSS 在断网时本来也拿不回来；
 * 这里要的是「地铁里打开过的那篇文章还能读」，正文 + 内联样式就够了。
 *
 * 存储上限 24 页，超出按访问时间淘汰。无痕标签不存。
 */
object OfflineCache {

    private const val MAX_PAGES = 24

    private fun dir(context: Context): File =
        File(context.filesDir, "offline-pages").apply { mkdirs() }

    /** URL 去掉锚点后做 key，同一个页面刷新、换锚点都命中同一份快照 */
    internal fun key(url: String): String {
        val normalized = url.substringBefore('#')
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun exists(context: Context, url: String): Boolean =
        File(dir(context), key(url)).exists()

    fun save(context: Context, url: String, html: String): Boolean {
        if (!url.startsWith("http") || html.length < 512) return false
        return try {
            val f = File(dir(context), key(url))
            f.writeText(html)
            trim(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun load(context: Context, url: String): String? = try {
        val f = File(dir(context), key(url))
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            f.readText()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** 记访问时间（LRU 依据）：load() 里 setLastModified 已完成；这里只清最老的 */
    private fun trim(context: Context) {
        val files = dir(context).listFiles { file -> file.isFile } ?: return
        if (files.size <= MAX_PAGES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_PAGES)
            .forEach { it.delete() }
    }
}
