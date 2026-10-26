package com.pluginbrowser.browser

import android.webkit.WebViewClient

/** 加载错误时展示的本地错误页（自动适配深浅色） */
object ErrorPages {

    fun html(errorCode: Int, failedUrl: String): String {
        val reason = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "找不到服务器，请检查网址是否正确"
            WebViewClient.ERROR_CONNECT -> "连接服务器失败"
            WebViewClient.ERROR_TIMEOUT -> "连接超时"
            WebViewClient.ERROR_FILE_NOT_FOUND -> "文件不存在"
            WebViewClient.ERROR_UNKNOWN -> "发生未知错误"
            else -> "页面加载失败"
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { --bg:#ffffff; --text:#1b1b1f; --sub:#5f6368; --accent:#0b57d0; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#111318; --text:#e3e3e9; --sub:#9aa0a6; --accent:#a8c7fa; }
  }
  body { margin:0; background:var(--bg); color:var(--text); display:flex;
         flex-direction:column; align-items:center; justify-content:center;
         min-height:100vh; font-family:-apple-system,"PingFang SC",sans-serif; padding:24px; }
  .code { font-size:44px; margin-bottom:8px; }
  h2 { margin:0 0 10px; font-size:19px; }
  p { color:var(--sub); font-size:14px; margin:4px 0; text-align:center; }
  .url { color:var(--accent); word-break:break-all; font-size:12px; margin-top:14px; }
</style>
</head>
<body>
  <div class="code">☁</div>
  <h2>无法访问此网页</h2>
  <p>$reason</p>
  <p>请检查网络连接，或点击地址栏右侧按钮重试</p>
  <div class="url">${escape(failedUrl)}</div>
</body>
</html>
""".trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
