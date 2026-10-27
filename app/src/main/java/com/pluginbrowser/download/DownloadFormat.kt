package com.pluginbrowser.download

import java.util.Locale

/** 下载相关的大小/类型文案，列表和确认框共用 */

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 优先用扩展名（APK / ZIP / PDF），没有扩展名才退回 MIME 的尾段 */
internal fun typeLabel(fileName: String, mime: String?): String {
    val ext = fileName.substringAfterLast('.', "").uppercase(Locale.US)
    if (ext.length in 1..5) return ext
    return mime?.substringAfterLast('/')?.uppercase(Locale.US).orEmpty()
}
