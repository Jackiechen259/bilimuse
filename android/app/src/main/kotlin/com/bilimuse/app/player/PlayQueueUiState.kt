package com.bilimuse.app.player

import com.bilimuse.app.data.TrackItem

/**
 * 播放队列面板的展示状态。
 *
 * 队列内容本身来自 [PlayerController] 持有的 MediaController（通知栏、耳机线控、
 * Android Auto 改了队列也会同步进来）；这里再拼上当前播放来源的分页信息，
 * 让面板能和桌面版一样显示「顺序播放 / 随机播放」「03 / 128」以及未加载的尾页。
 */
data class PlayQueueUiState(
    val items: List<TrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    /** 随机池范围：整个收藏夹 / 全部搜索结果。 */
    val scope: String = "整个收藏夹",
    /** 当前来源的总曲目数；队列只加载了一部分时用它显示分母。 */
    val total: Int = 0,
    /** 当前来源还有未加载的下一页（仅顺序播放且仍停留在同一收藏夹时成立）。 */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** 随机播放正在后台补全整个收藏夹。 */
    val preparing: Boolean = false,
) {
    /** 随机队列本身就是全部内容；顺序播放用来源总数，至少不小于已入队数量。 */
    val displayTotal: Int
        get() = if (shuffle) items.size else maxOf(total, items.size)

    val countText: String
        get() = displayTotal.toString()

    val modeText: String
        get() = when {
            shuffle && preparing -> "随机播放 · 正在整理收藏夹…"
            shuffle -> "随机播放 · $scope"
            else -> "顺序播放"
        }

    /** 「03 / 128」；顺序播放还有未加载页时附带提示。 */
    val positionText: String
        get() {
            if (currentIndex !in items.indices) return ""
            val current = (currentIndex + 1).toString().padStart(2, '0')
            val totalText = displayTotal.coerceAtLeast(currentIndex + 1)
                .toString()
                .padStart(2, '0')
            val base = "$current / $totalText"
            return if (!shuffle && hasMore) "$base · 还有更多未加载" else base
        }

    val canLoadMore: Boolean
        get() = !shuffle && hasMore

    val endText: String
        get() = "队列结束 · 共 ${items.size} 首"

    val currentItem: TrackItem?
        get() = items.getOrNull(currentIndex)
}
