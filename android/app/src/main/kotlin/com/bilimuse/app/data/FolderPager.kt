package com.bilimuse.app.data

import kotlinx.coroutines.delay

/** 全量加载时每页请求的条数；和列表分页保持一致，避免接口对 ps 上限的处理不一致。 */
internal const val FULL_PAGE_SIZE = 30

/** 单次全量加载最多翻多少页，防止接口异常时无限循环。 */
internal const val FULL_MAX_PAGES = 200

/** 翻页之间的间隔，避免短时间内连续请求触发风控。 */
internal const val FULL_PAGE_GAP_MS = 100L

/** 连续多少个空页后停止；用于跳过整页都是失效稿件、被过滤成空页的情况。 */
internal const val FULL_MAX_EMPTY_PAGES = 3

/**
 * 把一个分页接口从头翻到尾，拿到「整个收藏夹」或「全部搜索结果」。
 *
 * 列表页只加载用户滚到的位置，而随机播放需要覆盖全部内容，所以开启随机时用这个
 * 函数把剩下的页补全。规则：
 * - 按 bvid 去重，并保留接口返回的顺序；
 * - 接口说没有更多、或整页都是重复数据时停止
 *   （后者用于防止接口忽略 pn 导致死循环）；
 * - 整页被过滤成空（全是失效稿件）时继续往后翻，连续空页超过上限才停止；
 * - [onPage] 每页回调一次，参数是去重后的累计条数和预计总数（未知时为 0）。
 */
internal suspend fun loadAllFolderTracks(
    pageSize: Int = FULL_PAGE_SIZE,
    totalHint: Int = 0,
    maxPages: Int = FULL_MAX_PAGES,
    delayBetweenPagesMs: Long = FULL_PAGE_GAP_MS,
    fetch: suspend (page: Int, size: Int) -> FolderPage,
    onPage: (loaded: Int, total: Int) -> Unit = { _, _ -> },
): List<TrackItem> {
    val collected = LinkedHashMap<String, TrackItem>()
    var page = 1
    var emptyPages = 0
    while (page <= maxPages) {
        val result = fetch(page, pageSize)
        var added = 0
        for (item in result.items) {
            if (item.bvid.isBlank() || collected.containsKey(item.bvid)) continue
            collected[item.bvid] = item
            added++
        }
        onPage(collected.size, if (result.total > 0) result.total else totalHint)
        if (!result.hasMore) break
        if (result.items.isEmpty()) {
            // 整页失效稿件会被上层过滤成空页；连续空页过多才认为已经翻到底。
            emptyPages++
            if (emptyPages >= FULL_MAX_EMPTY_PAGES) break
        } else {
            emptyPages = 0
            // 整页都是重复数据：接口没有按 pn 翻页，继续只会死循环。
            if (added == 0) break
        }
        if (page == maxPages) break
        if (delayBetweenPagesMs > 0) delay(delayBetweenPagesMs)
        page++
    }
    return collected.values.toList()
}

/**
 * 播放队列里还没有的音轨（按 bvid 去重）。
 * 用于把「整个收藏夹」补进当前队列时，避免和已经加载的页重复。
 */
internal fun missingQueueTracks(
    existingIds: Set<String>,
    items: List<TrackItem>,
): List<TrackItem> {
    val seen = existingIds.toMutableSet()
    val missing = ArrayList<TrackItem>()
    for (item in items) {
        if (item.bvid.isBlank()) continue
        if (seen.add(item.bvid)) missing += item
    }
    return missing
}
