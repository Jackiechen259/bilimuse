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
        // 「」是歌名引号，不是噪声标签
        assertEquals(
            "残酷な天使のテーゼ 高橋洋子",
            LyricsMatch.cleanTitle("「残酷な天使のテーゼ」高橋洋子"),
        )
        assertEquals("孤勇者", LyricsMatch.cleanTitle("孤勇者 feat. 陈奕迅"))
    }

    @Test
    fun buildsCandidateFromDashSeparator() {
        val candidates = LyricsMatch.candidates("【纯享版】夜航星 - 不才", "某UP")
        assertEquals("夜航星", candidates[0].track)
        assertEquals("不才", candidates[0].artist)
        assertTrue("标题解析出的歌手才算 artist", candidates[0].artistFromTitle)
        // 反向候选也要有：常见「歌手 - 歌名」写法
        assertEquals("不才", candidates[1].track)
        assertEquals("夜航星", candidates[1].artist)
    }

    @Test
    fun candidatesAreDedupedAndCapped() {
        val candidates = LyricsMatch.candidates("《夜航星》不才 - 夜航星", "某UP")
        assertTrue(candidates.size <= LyricsMatch.MAX_CANDIDATES)
        val keys = candidates.map { it.track.lowercase() to it.artist.lowercase() }
        assertEquals(keys.size, keys.toSet().size)
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
        assertFalse("UP 主兜底的歌手不能当成标题解析结果", candidates[0].artistFromTitle)
        assertEquals("孤勇者 陈奕迅", LyricsMatch.searchQuery("孤勇者（Live版）", "陈奕迅"))
    }

    @Test
    fun trackScoreUsesJaccardLikePython() {
        assertEquals(1.0, LyricsMatch.trackScore("夜航星", "夜航星"), 1e-6)
        assertEquals(0.8, LyricsMatch.trackScore("夜航星", "夜航"), 1e-6)
        assertEquals(1.0, LyricsMatch.trackScore("NIGHT FLIGHT", "night flight"), 1e-6)
        // 二元组：{夜航, 航星} 与 {星夜, 夜航}，交集 1、并集 3 → Jaccard = 1/3（与 Python 一致）
        assertEquals(1.0 / 3.0, LyricsMatch.trackScore("夜航星", "星夜航"), 1e-6)
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
    fun artistScoreSplitsFeatComponents() {
        assertEquals(0.9, LyricsMatch.artistScore("陈奕迅", "陈奕迅 feat. G.E.M.邓紫棋", ""), 1e-6)
        assertEquals(0.9, LyricsMatch.artistScore("陈奕迅", "G.E.M.邓紫棋 & 陈奕迅", ""), 1e-6)
    }

    @Test
    fun featRegexDoesNotMatchInsideEnglishWords() {
        assertEquals("soft rain", LyricsMatch.cleanTitle("Soft Rain"))
        assertEquals("gift of the magi", LyricsMatch.cleanTitle("Gift of the Magi"))
        assertEquals("daft punk - get lucky", LyricsMatch.cleanTitle("Daft Punk - Get Lucky"))
        assertTrue(LyricsMatch.artistScore("Da", "Daft Punk", "") < 0.9)
        assertTrue(LyricsMatch.artistScore("So", "Soft Cell", "") < 0.9)
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
    fun unknownDurationIsNotPenalizedLikePython() {
        // Python duration_score：任一方时长未知（≤0）直接返回 1.0，不惩罚。
        assertEquals(1.0, LyricsMatch.durationScore(0, 304), 1e-6)
        assertEquals(1.0, LyricsMatch.durationScore(304, 0), 1e-6)
        assertEquals(1.0, LyricsMatch.durationScore(0, 0), 1e-6)

        val candidate = LyricsCandidate(
            id = 1,
            track = "夜航星",
            artist = "不才",
            album = "",
            duration = 0,
            instrumental = false,
            synced = true,
        )
        val score = LyricsMatch.score(candidate, LyricsMatch.Query("夜航星", "不才"), "不才", 304)
        assertEquals(1.0, score.duration, 1e-6)
        assertEquals(0.98, score.total, 1e-6)
        assertTrue(score.accepted)
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

        // 近似标题（包含匹配 0.8）时与 Python 结果一致：0.8*0.6 + 0.9*0.2 + 1.0*0.2 = 0.86。
        val approximate = LyricsMatch.score(
            candidate.copy(track = "夜航星 - Live"),
            LyricsMatch.Query("夜航星", "不才"),
            "不才",
            304,
        )
        assertEquals(0.8, approximate.track, 1e-6)
        assertEquals(0.86, approximate.total, 1e-6)
        assertTrue(approximate.accepted)

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

    @Test
    fun rankSkipsRecordsWithoutLyrics() {
        val withoutLyrics = LyricsCandidate(
            id = 1, track = "夜航星", artist = "不才", album = "",
            duration = 304, instrumental = false, synced = false, hasLyrics = false,
        )
        val withLyrics = withoutLyrics.copy(id = 2, hasLyrics = true)
        val ranked = LyricsMatch.rank(
            listOf(withoutLyrics, withLyrics),
            listOf(LyricsMatch.Query("夜航星", "不才")),
            "",
            304,
        )
        assertEquals(listOf(2L), ranked.map { it.first.id })
    }

    @Test
    fun rankRejectsArtistConflictAndDurationFarApart() {
        val conflict = LyricsCandidate(
            id = 1, track = "夜航星", artist = "周杰伦", album = "",
            duration = 304, instrumental = false, synced = true, hasLyrics = true,
        )
        assertTrue(
            LyricsMatch.rank(
                listOf(conflict),
                listOf(LyricsMatch.Query("夜航星", "不才")),
                "",
                304,
            ).isEmpty(),
        )
        val farApart = conflict.copy(id = 2, artist = "不才", duration = 304 + 46)
        assertTrue(
            LyricsMatch.rank(
                listOf(farApart),
                listOf(LyricsMatch.Query("夜航星", "不才")),
                "",
                304,
            ).isEmpty(),
        )
        val boundary = farApart.copy(id = 3, duration = 304 + 45)
        assertEquals(
            listOf(3L),
            LyricsMatch.rank(
                listOf(boundary),
                listOf(LyricsMatch.Query("夜航星", "不才")),
                "",
                304,
            ).map { it.first.id },
        )
    }

    @Test
    fun rankRejectsInstrumentalTitleAndPrefersSyncedOnTie() {
        val karaoke = LyricsCandidate(
            id = 1, track = "夜航星 (伴奏)", artist = "不才", album = "",
            duration = 304, instrumental = false, synced = true, hasLyrics = true,
        )
        val plain = karaoke.copy(id = 2, track = "夜航星", synced = false)
        val synced = plain.copy(id = 3, synced = true)
        val ranked = LyricsMatch.rank(
            listOf(karaoke, plain, synced),
            listOf(LyricsMatch.Query("夜航星", "不才")),
            "",
            304,
        )
        assertEquals(listOf(3L, 2L), ranked.map { it.first.id })
    }

    @Test
    fun queryPlanOrdersDedupesAndCaps() {
        val plan = LyricsMatch.queryPlan(
            listOf(
                LyricsMatch.Query("夜航星", "不才"),
                LyricsMatch.Query("不才", "夜航星"),
            ),
            "夜航星 - 不才",
        )
        assertEquals(
            listOf(
                mapOf("track_name" to "夜航星", "artist_name" to "不才"),
                mapOf("track_name" to "不才", "artist_name" to "夜航星"),
                mapOf("q" to "夜航星 - 不才"),
                mapOf("track_name" to "夜航星"),
            ),
            plan,
        )
        assertEquals(
            listOf(mapOf("track_name" to "夜航星")),
            LyricsMatch.queryPlan(
                listOf(LyricsMatch.Query("夜航星", "夜航星", artistFromTitle = false)),
                "",
            ),
        )
    }
}
