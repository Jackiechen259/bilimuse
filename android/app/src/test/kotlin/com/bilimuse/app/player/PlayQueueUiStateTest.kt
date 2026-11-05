package com.bilimuse.app.player

import com.bilimuse.app.data.TrackItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueUiStateTest {
    private fun tracks(count: Int): List<TrackItem> = List(count) { index ->
        TrackItem(
            bvid = "BV${index + 1}",
            title = "曲目 ${index + 1}",
            duration = 60 + index,
        )
    }

    @Test
    fun `sequence queue shows loaded items over the source total`() {
        val state = PlayQueueUiState(
            items = tracks(3),
            currentIndex = 1,
            shuffle = false,
            total = 128,
            hasMore = true,
        )

        assertEquals("顺序播放", state.modeText)
        assertEquals("128", state.countText)
        assertEquals("02 / 128 · 还有更多未加载", state.positionText)
        assertTrue(state.canLoadMore)
        assertEquals("队列结束 · 共 3 首", state.endText)
        assertEquals("BV2", state.currentItem?.bvid)
    }

    @Test
    fun `sequence queue done hides load more and trailing hint`() {
        val state = PlayQueueUiState(
            items = tracks(3),
            currentIndex = 2,
            shuffle = false,
            total = 3,
            hasMore = false,
        )

        assertEquals("03 / 03", state.positionText)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `shuffle queue reports scope and preparing state`() {
        val preparing = PlayQueueUiState(
            items = tracks(5),
            currentIndex = 4,
            shuffle = true,
            scope = "全部搜索结果",
            total = 999,
            preparing = true,
        )
        assertEquals("随机播放 · 正在整理收藏夹…", preparing.modeText)
        assertEquals("5", preparing.countText)
        assertEquals("05 / 05", preparing.positionText)
        assertFalse(preparing.canLoadMore)

        val ready = preparing.copy(preparing = false)
        assertEquals("随机播放 · 全部搜索结果", ready.modeText)
    }

    @Test
    fun `shuffle ignores the source total`() {
        val state = PlayQueueUiState(
            items = tracks(4),
            currentIndex = 0,
            shuffle = true,
            total = 200,
        )

        assertEquals(4, state.displayTotal)
        assertEquals("4", state.countText)
    }

    @Test
    fun `total never hides items already in the queue`() {
        val state = PlayQueueUiState(
            items = tracks(6),
            currentIndex = 5,
            total = 2,
        )

        assertEquals(6, state.displayTotal)
        assertEquals("06 / 06", state.positionText)
    }

    @Test
    fun `empty or out-of-range queue has no position`() {
        val empty = PlayQueueUiState()
        assertEquals("", empty.positionText)
        assertNull(empty.currentItem)
        assertEquals("0", empty.countText)
        assertFalse(empty.canLoadMore)

        val outOfRange = PlayQueueUiState(items = tracks(2), currentIndex = 9)
        assertEquals("", outOfRange.positionText)
        assertNull(outOfRange.currentItem)
    }
}
