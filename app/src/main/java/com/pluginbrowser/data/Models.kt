package com.pluginbrowser.data

/** 书签 / 历史列表项 */
data class ArchiveItem(
    val id: Long,
    val title: String,
    val url: String,
    val time: Long,
)

/** 地址栏建议项（历史 + 书签 + 搜索引擎） */
data class SuggestionItem(
    val title: String,
    val url: String,
    val isBookmark: Boolean,
    val isSearch: Boolean = false,
)

/** 下载记录 */
data class DownloadItem(
    val id: Long,
    val name: String,
    val url: String,
    val path: String,
    val mime: String,
    val size: Long,
    val status: Int,
    val dmId: Long,
    val created: Long,
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RUNNING = 1
        const val STATUS_DONE = 2
        const val STATUS_FAILED = 3
    }
}
