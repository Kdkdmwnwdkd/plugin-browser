package com.pluginbrowser.browser

import android.webkit.WebViewClient

/** 加载错误时展示的本地错误页（自动适配深浅色） */
object ErrorPages {

    /**
     * `pb://retry` 是给错误页里那个按钮用的：
     * 页面本身是 data: 加载的，没法直接调用 WebView，只能走一次自定义 scheme 让原生接手。
     */
    const val RETRY_SCHEME = "pb://retry"

    fun html(errorCode: Int, failedUrl: String, extraHint: String? = null): String {
        val reason = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "找不到服务器，请检查网址是否正确"
            WebViewClient.ERROR_CONNECT -> "连接服务器失败"
            WebViewClient.ERROR_TIMEOUT -> "等了很久都没有响应，连接卡住了"
            WebViewClient.ERROR_FILE_NOT_FOUND -> "文件不存在"
            // WebView 把「连接被中途掐断」「TLS 被重置」都归到这一类，
            // 国内访问 GitHub 时最常见的正是代理抖动，所以文案要说人话
            WebViewClient.ERROR_UNKNOWN -> "连接被中断，可能是网络或代理不稳定"
            else -> "页面加载失败"
        }
        val host = runCatching { android.net.Uri.parse(failedUrl).host }.getOrNull()
        val hint = when {
            !extraHint.isNullOrBlank() -> extraHint
            host.isNullOrBlank() -> "请检查网络连接后重试"
            else -> "请检查网络或代理设置，也可以稍后再试"
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { --bg:#ffffff; --text:#1b1b1f; --sub:#5f6368; --accent:#0b57d0; --btn:#0b57d0; --btnText:#ffffff; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#111318; --text:#e3e3e9; --sub:#9aa0a6; --accent:#a8c7fa;
            --btn:#a8c7fa; --btnText:#0a1a2f; }
  }
  body { margin:0; background:var(--bg); color:var(--text); display:flex;
         flex-direction:column; align-items:center; justify-content:center;
         min-height:100vh; font-family:-apple-system,"PingFang SC",sans-serif; padding:24px;
         box-sizing:border-box; }
  .code { font-size:44px; margin-bottom:8px; opacity:.85; }
  h2 { margin:0 0 10px; font-size:19px; font-weight:600; }
  p { color:var(--sub); font-size:14px; margin:4px 0; text-align:center; line-height:1.5; }
  .url { color:var(--accent); word-break:break-all; font-size:12px; margin-top:14px;
         text-align:center; max-width:92vw; }
  a.retry { display:inline-block; margin-top:22px; padding:10px 26px; border-radius:22px;
            background:var(--btn); color:var(--btnText); font-size:14px; font-weight:600;
            text-decoration:none; }
</style>
</head>
<body>
  <div class="code">☁</div>
  <h2>无法访问此网页</h2>
  <p>$reason</p>
  <p>$hint</p>
  <div class="url">${escape(failedUrl)}</div>
  <a class="retry" href="$RETRY_SCHEME">重新加载</a>
</body>
</html>
""".trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
