package com.bilimuse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标题清洗、候选生成与 LRCLIB 打分：纯 JVM，不依赖 Android。 */
class LyricsMatchTest {
    @Test
    fun cleansNoiseTagsAndNormalizesFullWidth() {
        assertEquals("夜航星 - 不才", LyricsMatch.cleanTitle("【4K】夜航星 - 不才"))
        assertEquals("孤勇者", LyricsMatch.cleanTitle("孤勇者（Live版）"))
        assertEquals("night flight", LyricsMatch.cleanTitle("ＮＩＧＨＴ　ＦＬＩＧＨＴ 【1080P】"))
        assertEquals("起风了", LyricsMatch.cleanTitle("《起风了》 高音质 完整版"))
    }

    @Test
    fun buildsCandidateFromDashSeparator() {
        val candidates = LyricsMatch.candidates("【纯享版】夜航星 - 不才", "某UP")
        assertEquals("夜航星", candidates[0].track)
        assertEquals("不才", candidates[0].artist)
    }

    @Test
    fun buildsCandidateFromBookTitle() {
        val candidates = LyricsMatch.candidates("《起风了》买辣椒也用券", "某UP")
        assertEquals("起风了", candidates[0].track)
        assertEquals("买辣椒也用券", candidates[0].artist)
    }

    @Test
    fun fallsBackToWholeTitleAndUpper() {
        val candidates = LyricsMatch.candidates("孤勇者（Live版）", "陈奕迅")
        assertEquals("孤勇者", candidates[0].track)
        assertEquals("陈奕迅", candidates[0].artist)
        assertEquals("孤勇者 陈奕迅", LyricsMatch.searchQuery("孤勇者（Live版）", "陈奕迅"))
    }

    @Test
    fun trackScoreUsesExactContainsAndBigramOverlap() {
        assertEquals(1.0, LyricsMatch.trackScore("夜航星", "夜航星"), 1e-6)
        assertEquals(0.8, LyricsMatch.trackScore("夜航星", "夜航"), 1e-6)
        assertEquals(1.0, LyricsMatch.trackScore("NIGHT FLIGHT", "night flight"), 1e-6)
        // 二元组：{夜航, 航星} 与 {星夜, 夜航}，重合 1 组 → Dice = 2*1/(2+2) = 0.5
        assertEquals(0.5, LyricsMatch.trackScore("夜航星", "星夜航"), 1e-6)
        assertEquals(0.0, LyricsMatch.trackScore("夜航星", "完全无关"), 1e-6)
    }

    @Test
    fun artistScoreMatchesArtistAndUpper() {
        assertEquals(0.9, LyricsMatch.artistScore("不才", "不才", ""), 1e-6)
        assertEquals(0.9, LyricsMatch.artistScore("陈奕迅", "", "陈奕迅"), 1e-6)
        assertEquals(0.5, LyricsMatch.artistScore("不才呀", "不才", ""), 1e-6)
        assertEquals(0.0, LyricsMatch.artistScore("不才", "陈奕迅", "陈奕迅"), 1e-6)
    }

    @Test
    fun durationScorePenalizesLongGaps() {
        assertEquals(1.0, LyricsMatch.durationScore(304, 304), 1e-6)
        assertEquals(1.0, LyricsMatch.durationScore(306, 304), 1e-6)
        assertEquals(0.95, LyricsMatch.durationScore(309, 304), 1e-6)
        assertEquals(0.0, LyricsMatch.durationScore(366, 304), 1e-6)
        assertEquals(0.0, LyricsMatch.durationScore(404, 304), 1e-6)
    }

    @Test
    fun weightedScoreAndAcceptanceBoundary() {
        val candidate = LyricsCandidate(
            id = 1,
            track = "夜航星",
            artist = "不才",
            album = "",
            duration = 304,
            instrumental = false,
            synced = true,
        )
        val score = LyricsMatch.score(candidate, LyricsMatch.Query("夜航星", "不才"), "不才", 304)
        assertEquals(0.98, score.total, 1e-6)
        assertTrue(score.accepted)

        // 总分 0.62 恰好通过（≥0.62），track 分也达标。
        val boundary = LyricsMatch.MatchScore(track = 0.8, artist = 0.5, duration = 0.2)
        assertEquals(0.62, boundary.total, 1e-6)
        assertTrue(boundary.accepted)

        // 总分 0.61 不通过。
        val below = LyricsMatch.MatchScore(track = 0.8, artist = 0.5, duration = 0.15)
        assertFalse(below.accepted)

        // track 分低于 0.7，总分再高也不接受。
        val highTotalLowTrack = LyricsMatch.MatchScore(track = 0.6, artist = 1.0, duration = 1.0)
        assertFalse(highTotalLowTrack.accepted)
    }

    @Test
    fun bestExcludesInstrumentalAndPrefersClosestDuration() {
        val candidates = listOf(
            LyricsCandidate(
                id = 1, track = "夜航星", artist = "不才", album = "",
                duration = 304, instrumental = true, synced = true,
            ),
            LyricsCandidate(
                id = 2, track = "夜航星", artist = "不才", album = "",
                duration = 304, instrumental = false, synced = true,
            ),
            LyricsCandidate(
                id = 3, track = "夜航星", artist = "不才", album = "",
                duration = 305, instrumental = false, synced = true,
            ),
        )
        val (best, _) = LyricsMatch.best(
            candidates = candidates,
            queries = listOf(LyricsMatch.Query("夜航星", "不才")),
            upper = "",
            videoDurationSec = 305,
        )!!
        assertEquals(3, best.id)

        assertNull(
            LyricsMatch.best(
                candidates = candidates.filter { it.instrumental },
                queries = listOf(LyricsMatch.Query("夜航星", "不才")),
                upper = "",
                videoDurationSec = 304,
            ),
        )
    }
}
