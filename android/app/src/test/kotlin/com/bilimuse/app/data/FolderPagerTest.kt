package com.bilimuse.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPagerTest {

    private fun track(bvid: String) = TrackItem(bvid = bvid, title = bvid)

    private fun page(
        items: List<TrackItem>,
        hasMore: Boolean,
        total: Int = 0,
    ) = FolderPage(items = items, hasMore = hasMore, total = total)

    @Test
    fun loadsAllPagesDeduplicatesAndReportsProgress() = runTest {
        val requested = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val pages = mapOf(
            1 to page(listOf(track("a"), track("b")), hasMore = true, total = 5),
            2 to page(listOf(track("b"), track("c")), hasMore = true, total = 5),
            3 to page(listOf(track("d"), track("e")), hasMore = false, total = 5),
        )

        val result = loadAllFolderTracks(
            totalHint = 5,
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                requested += pageNumber
                pages[pageNumber] ?: page(emptyList(), hasMore = false)
            },
            onPage = { loaded, total -> progress += loaded to total },
        )

        assertEquals(listOf("a", "b", "c", "d", "e"), result.map { it.bvid })
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(listOf(2 to 5, 3 to 5, 5 to 5), progress)
    }

    @Test
    fun stopsWhenPageOnlyContainsDuplicates() = runTest {
        val requested = mutableListOf<Int>()
        val result = loadAllFolderTracks(
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                requested += pageNumber
                page(listOf(track("a"), track("b")), hasMore = true)
            },
        )

        assertEquals(listOf("a", "b"), result.map { it.bvid })
        assertEquals(listOf(1, 2), requested)
    }

    @Test
    fun skipsFilteredEmptyPageAndKeepsPaging() = runTest {
        val requested = mutableListOf<Int>()
        val result = loadAllFolderTracks(
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                requested += pageNumber
                when (pageNumber) {
                    1 -> page(listOf(track("a")), hasMore = true)
                    2 -> page(emptyList(), hasMore = true)
                    else -> page(listOf(track("b")), hasMore = false)
                }
            },
        )

        assertEquals(listOf("a", "b"), result.map { it.bvid })
        assertEquals(listOf(1, 2, 3), requested)
    }

    @Test
    fun stopsAfterConsecutiveEmptyPages() = runTest {
        val requested = mutableListOf<Int>()
        val result = loadAllFolderTracks(
            maxPages = 10,
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                requested += pageNumber
                page(emptyList(), hasMore = true)
            },
        )

        assertTrue(result.isEmpty())
        assertEquals(listOf(1, 2, 3), requested)
    }

    @Test
    fun stopsAtMaxPages() = runTest {
        val requested = mutableListOf<Int>()
        val result = loadAllFolderTracks(
            maxPages = 3,
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                requested += pageNumber
                page(listOf(track("t$pageNumber")), hasMore = true)
            },
        )

        assertEquals(listOf("t1", "t2", "t3"), result.map { it.bvid })
        assertEquals(listOf(1, 2, 3), requested)
    }

    @Test
    fun stopsWhenHasMoreIsFalseEvenIfTotalHintIsLarger() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = loadAllFolderTracks(
            totalHint = 10,
            delayBetweenPagesMs = 0,
            fetch = { _, _ -> page(listOf(track("a")), hasMore = false) },
            onPage = { loaded, total -> progress += loaded to total },
        )

        assertEquals(listOf("a"), result.map { it.bvid })
        assertEquals(listOf(1 to 10), progress)
    }

    @Test
    fun prefersPageTotalOverHint() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        loadAllFolderTracks(
            totalHint = 10,
            delayBetweenPagesMs = 0,
            fetch = { pageNumber, _ ->
                if (pageNumber == 1) {
                    page(listOf(track("a")), hasMore = true, total = 3)
                } else {
                    page(listOf(track("b")), hasMore = false, total = 3)
                }
            },
            onPage = { loaded, total -> progress += loaded to total },
        )

        assertEquals(listOf(1 to 3, 2 to 3), progress)
    }

    @Test
    fun skipsBlankBvidsAndPassesPageSize() = runTest {
        val sizes = mutableListOf<Int>()
        val result = loadAllFolderTracks(
            pageSize = 50,
            delayBetweenPagesMs = 0,
            fetch = { _, size ->
                sizes += size
                page(
                    listOf(track(""), track("a"), TrackItem(bvid = "  ", title = "blank")),
                    hasMore = false,
                )
            },
        )

        assertEquals(listOf("a"), result.map { it.bvid })
        assertEquals(listOf(50), sizes)
    }

    @Test
    fun missingQueueTracksSkipsExistingAndRepeatedItems() {
        val missing = missingQueueTracks(
            existingIds = setOf("a"),
            items = listOf(track("a"), track("b"), track("b"), track("c")),
        )

        assertEquals(listOf("b", "c"), missing.map { it.bvid })
    }

    @Test
    fun missingQueueTracksReturnsEmptyWhenEverythingKnown() {
        assertTrue(missingQueueTracks(setOf("a", "b"), listOf(track("a"), track("b"))).isEmpty())
        assertTrue(missingQueueTracks(emptySet(), emptyList()).isEmpty())
    }

    @Test
    fun missingQueueTracksSkipsBlankIds() {
        val missing = missingQueueTracks(emptySet(), listOf(track(""), track("a")))
        assertEquals(listOf("a"), missing.map { it.bvid })
    }
}
