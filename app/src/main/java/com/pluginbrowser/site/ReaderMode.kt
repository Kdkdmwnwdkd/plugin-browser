package com.pluginbrowser.site

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 阅读模式：把提取出来的正文渲染成一页干净的 HTML。
 *
 * 不用 loadData 而是 loadDataWithBaseURL(原始 url)，
 * 是因为正文里的图片、代码高亮样式大多是相对路径或依赖站点的 CSP 之外的资源，
 * 带上 base url 才能正常加载；代价是这页仍然算「同源」，所以只放静态 HTML，不放脚本。
 */
object ReaderMode {

    data class Article(
        val title: String,
        val site: String,
        val byline: String,
        val url: String,
        val html: String,
        val length: Int,
    )

    const val SCHEME = "pb://reader"

    /** evaluateJavascript 的返回值是「JSON 字符串字面量」，这里要解两层 */
    fun parse(raw: String?): Article? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val text = runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                else -> raw
            }
        }.getOrNull() ?: return null

        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok")) return null
        val html = obj.optString("html")
        if (html.length < 80) return null
        return Article(
            title = obj.optString("title").ifBlank { obj.optString("site") },
            site = obj.optString("site"),
            byline = obj.optString("byline"),
            url = obj.optString("url"),
            html = html,
            length = obj.optInt("length"),
        )
    }

    fun html(article: Article, scale: Float, dark: Boolean): String {
        val bg = if (dark) "#121212" else "#fbfaf7"
        val fg = if (dark) "#d6d6d6" else "#1f2328"
        val sub = if (dark) "#8e8e8e" else "#6b7280"
        val rule = if (dark) "#2a2a2a" else "#e6e3dd"
        val accent = if (dark) "#8ab4f8" else "#1a6fd4"
        val base = 17.0f * scale
        val titleSize = 25.0f * scale
        val minutes = (article.length / 350).coerceAtLeast(1)

        return """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${escape(article.title)}</title>
<style>
  :root { color-scheme: ${if (dark) "dark" else "light"}; }
  html, body { margin: 0; padding: 0; background: $bg; color: $fg; }
  body {
    font-family: system-ui, -apple-system, "PingFang SC", "Noto Sans SC", sans-serif;
    font-size: ${base}px; line-height: 1.78;
    padding: 62px 20px 72px; box-sizing: border-box;
    -webkit-text-size-adjust: 100%; word-wrap: break-word;
  }
  .bar {
    position: fixed; top: 0; left: 0; right: 0; z-index: 10;
    display: flex; align-items: center; gap: 14px;
    padding: 12px 16px; background: $bg; border-bottom: 1px solid $rule;
    font-size: 13.5px; color: $sub;
  }
  .bar a { color: $accent; text-decoration: none; padding: 4px 2px; }
  .bar .grow { flex: 1; }
  h1.title { font-size: ${titleSize}px; line-height: 1.34; margin: 0 0 10px; font-weight: 700; }
  .meta { color: $sub; font-size: ${base * 0.78f}px; margin-bottom: 26px; }
  .meta span + span::before { content: " · "; }
  article { max-width: 680px; margin: 0 auto; }
  article p { margin: 0 0 1.05em; }
  article img, article picture img { max-width: 100%; height: auto; display: block; margin: 1em auto; border-radius: 6px; }
  article h1, article h2, article h3 { line-height: 1.4; margin: 1.6em 0 .7em; }
  article h2 { font-size: ${base * 1.22f}px; }
  article h3 { font-size: ${base * 1.08f}px; }
  article a { color: $accent; }
  article blockquote {
    margin: 1.2em 0; padding: 2px 0 2px 14px; border-left: 3px solid $rule; color: $sub;
  }
  article pre, article code {
    font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
    font-size: ${base * 0.86f}px;
  }
  article pre {
    background: ${if (dark) "#1d1d1d" else "#f2f0ec"}; padding: 12px 14px; border-radius: 8px;
    overflow-x: auto; line-height: 1.55;
  }
  article figure { margin: 1.2em 0; }
  article figcaption { color: $sub; font-size: ${base * 0.76f}px; text-align: center; }
  article table { border-collapse: collapse; width: 100%; font-size: ${base * 0.86f}px; display: block; overflow-x: auto; }
  article td, article th { border: 1px solid $rule; padding: 6px 8px; }
  article hr { border: 0; border-top: 1px solid $rule; margin: 2em 0; }
  .foot { max-width: 680px; margin: 34px auto 0; padding-top: 16px; border-top: 1px solid $rule;
          color: $sub; font-size: ${base * 0.74f}px; }
  .foot a { color: $accent; text-decoration: none; word-break: break-all; }
</style>
</head>
<body>
<div class="bar">
  <a href="$SCHEME/exit">退出</a>
  <span class="grow"></span>
  <a href="$SCHEME/font?d=-1">A-</a>
  <a href="$SCHEME/font?d=1">A+</a>
</div>
<article>
  <h1 class="title">${escape(article.title)}</h1>
  <div class="meta">
    <span>${escape(article.site)}</span>
    ${if (article.byline.isNotBlank()) "<span>${escape(article.byline)}</span>" else ""}
    <span>约 $minutes 分钟</span>
  </div>
  ${article.html}
</article>
<div class="foot">
  正文由阅读模式提取，可能有缺失 · <a href="${escape(article.url)}">查看原文</a>
</div>
</body></html>
""".trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
