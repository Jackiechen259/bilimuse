package com.bilimuse.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** LRC 解析与编码回退：纯 JVM，不依赖 Android。 */
class LyricsLrcTest {
    @Test
    fun parsesBomCrlfAndIgnoresMetadata() {
        val text = "\uFEFF[ti:夜航星]\r\n[ar:不才]\r\n[al:夜航星]\r\n[by:demo]\r\n" +
            "[00:12.34]执念的鱼\r\n[00:15.5]我\r\n"
        val result = LyricsLrc.parseBytes(text.toByteArray(Charsets.UTF_8))
        assertTrue(result.synced)
        assertEquals(2, result.lines.size)
        assertEquals(12_340L, result.lines[0].timeMs)
        assertEquals("执念的鱼", result.lines[0].text)
        assertEquals(15_500L, result.lines[1].timeMs)
        assertEquals("我", result.lines[1].text)
    }

    @Test
    fun supportsMillisecondPrecisionAndMinuteSeconds() {
        val result = LyricsLrc.parse(
            """
            [00:01.5]A
            [00:02.05]B
            [00:03.005]C
            [1:02.25]D
            """.trimIndent(),
        )
        assertEquals(listOf(1_500L, 2_050L, 3_005L, 62_250L), result.lines.map { it.timeMs })
    }

    @Test
    fun expandsMultipleTimestampsOnOneLine() {
        val result = LyricsLrc.parse("[00:12.00][01:30.00]执念的鱼\n[00:20.00]副歌")
        assertTrue(result.synced)
        assertEquals(
            listOf(12_000L, 20_000L, 90_000L),
            result.lines.map { it.timeMs },
        )
        assertEquals("执念的鱼", result.lines[0].text)
        assertEquals("执念的鱼", result.lines[2].text)
    }

    @Test
    fun appliesOffsetTagToAllLines() {
        val earlier = LyricsLrc.parse("[offset:-500]\n[00:12.00]A")
        assertEquals(11_500L, earlier.lines.single().timeMs)

        val later = LyricsLrc.parse("[offset:800]\n[00:12.00]A\n[00:13.00]B")
        assertEquals(listOf(12_800L, 13_800L), later.lines.map { it.timeMs })
    }

    @Test
    fun negativeTimestampsAfterOffsetClampToZero() {
        val result = LyricsLrc.parse("[offset:-10000]\n[00:05.00]A")
        assertEquals(0L, result.lines.single().timeMs)
    }

    @Test
    fun sortsByTimeAndDropsDuplicateText() {
        val result = LyricsLrc.parse(
            """
            [00:30.00]B
            [00:10.00]A
            [00:10.000]A
            [00:20.00]C
            """.trimIndent(),
        )
        assertEquals(listOf(10_000L, 20_000L, 30_000L), result.lines.map { it.timeMs })
        assertEquals(listOf("A", "C", "B"), result.lines.map { it.text })
    }

    @Test
    fun keepsBlankLinesButTrimsEdgesAndCollapsesRuns() {
        val result = LyricsLrc.parse(
            """
            [00:01.00]A
            [00:02.00]
            [00:03.00]
            [00:04.00]
            [00:05.00]B
            [00:06.00]

            """.trimIndent(),
        )
        assertEquals(listOf("A", "", "", "B"), result.lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 5_000L), result.lines.map { it.timeMs })
    }

    @Test
    fun textWithoutTimeTagsBecomesStaticLyrics() {
        val result = LyricsLrc.parse("第一行\n\n第二行\n[ti:测试]\n")
        assertFalse(result.synced)
        assertEquals(listOf("第一行", "", "第二行"), result.lines.map { it.text })
    }

    @Test
    fun staticLyricsTrimEdgesAndCollapseBlankRuns() {
        val result = LyricsLrc.parse("\n\n第一行\n\n\n\n第二行\n\n")
        assertFalse(result.synced)
        // 首尾空行裁掉，中间连续空行最多保留 2 个（与文档 / Python 修复后行为一致）。
        assertEquals(listOf("第一行", "", "", "第二行"), result.lines.map { it.text })
    }

    @Test
    fun truncatesAtTwoThousandLines() {
        val text = (1..2_100).joinToString("\n") { index ->
            String.format("[%02d:%02d.00]L%d", index / 60, index % 60, index)
        }
        val result = LyricsLrc.parse(text)
        assertEquals(LyricsLrc.MAX_LINES, result.lines.size)
        assertEquals(2_000, LyricsLrc.MAX_LINES)
        assertEquals(256 * 1024, LyricsLrc.MAX_BYTES)
    }

    @Test
    fun fallsBackToGbkWhenUtf8Fails() {
        val bytes = "[00:02.00]夜航星".toByteArray(Charset.forName("GBK"))
        val result = LyricsLrc.parseBytes(bytes)
        assertEquals("夜航星", result.lines.single().text)
    }

    @Test
    fun emptyInputProducesNoLines() {
        val result = LyricsLrc.parse("")
        assertFalse(result.synced)
        assertTrue(result.lines.isEmpty())
    }
}
