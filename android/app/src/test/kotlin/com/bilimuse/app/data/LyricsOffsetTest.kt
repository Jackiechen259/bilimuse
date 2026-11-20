package com.bilimuse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 自动偏移校准纯函数测试：与 Python `tests/test_lyrics_offset.py` 保持同一组数据。 */
class LyricsOffsetTest {
    private fun refLines(count: Int = 24): List<Pair<Double, String>> =
        (0 until count).map { index ->
            (40.0 + index * 5.0) to "第${index}句歌词内容各不相同"
        }

    private fun shiftedLines(
        count: Int = 24,
        shift: Double = 0.0,
    ): List<Pair<Double, String>> =
        (0 until count).map { index ->
            (40.0 + index * 5.0 + shift) to "第${index}句歌词内容各不相同"
        }

    @Test
    fun findsPositiveShift() {
        assertEquals(3.5, LyricsOffset.estimate(refLines(), shiftedLines(shift = 3.5))!!.offset, 1e-6)
    }

    @Test
    fun findsNegativeShift() {
        assertEquals(-7.0, LyricsOffset.estimate(refLines(), shiftedLines(shift = -7.0))!!.offset, 1e-6)
    }

    @Test
    fun findsLargeShift() {
        assertEquals(29.5, LyricsOffset.estimate(refLines(), shiftedLines(shift = 29.5))!!.offset, 1e-6)
    }

    @Test
    fun keepsZeroShift() {
        val result = LyricsOffset.estimate(refLines(), shiftedLines())
        assertNotNull(result)
        assertEquals(0.0, result!!.offset, 1e-6)
    }

    @Test
    fun toleratesNoisyLines() {
        val noisy = shiftedLines(shift = 4.0).mapIndexed { index, (time, text) ->
            time to if (index % 3 == 0) "乱码${index}x${index}" else text
        }
        assertEquals(4.0, LyricsOffset.estimate(refLines(), noisy)!!.offset, 1e-6)
    }

    @Test
    fun requiresEnoughLines() {
        assertNull(LyricsOffset.estimate(refLines(6), shiftedLines(6, 3.0)))
    }

    @Test
    fun rejectsUnrelatedText() {
        val unrelated = (0 until 24).map { index ->
            (40.0 + index * 5.0) to "完全无关的另一首歌第${index}句"
        }
        assertNull(LyricsOffset.estimate(refLines(), unrelated))
    }

    @Test
    fun rejectsAmbiguousRepeatedLines() {
        val ref = (0 until 20).map { (40.0 + it * 5.0) to "重复副歌" }
        val cand = (0 until 20).map { (42.0 + it * 5.0) to "重复副歌" }
        assertNull(LyricsOffset.estimate(ref, cand))
    }
}
