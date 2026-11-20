package com.bilimuse.app.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * LRC 解析：纯 JVM，可在 JVM 单测里直接跑。
 *
 * 规则严格对齐 `docs/LYRICS.md`：
 * - 支持 `[mm:ss]` / `[mm:ss.x]` / `[mm:ss.xx]` / `[mm:ss.xxx]` / `[m:ss]`；
 * - 一行多个时间标签展开成多行；
 * - `[offset:±毫秒]` 叠加到所有时间；
 * - 忽略 `[ti:]` 等元信息与注释行；
 * - 空歌词行保留，首尾裁掉，连续空行最多 2 个；
 * - 时间非负、按时间升序、同一时间戳的重复文本只留一条；
 * - 编码回退 utf-8-sig → gbk → utf-8(replace)；
 * - 行数上限 2000，文件大小上限 256 KB（由调用方在导入前检查）。
 * - 没有任何时间标签时视为静态歌词（`synced=false`）。
 */
object LyricsLrc {
    /** 本地 LRC / 导入文件大小上限（256 KB）。 */
    const val MAX_BYTES = 256 * 1024

    /** 解析后的歌词行数上限。 */
    const val MAX_LINES = 2000

    /** 连续空行最多保留的条数。 */
    private const val MAX_CONSECUTIVE_BLANKS = 2

    /** 一行内的 `[mm:ss.xxx]` 时间标签。 */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[offset:+500]` / `[offset:-500]`。 */
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** `[ti:...]` / `[ar:...]` 之类的元信息行。 */
    private val META_TAG = Regex("""^\s*\[[A-Za-z]+:.*]\s*$""")

    /** `// 注释` / `# 注释`。 */
    private val COMMENT_LINE = Regex("""^\s*(//|#).*$""")

    /** 解析结果。[synced] 为 false 时 [lines] 是按行原样展示的静态歌词。 */
    data class ParseResult(
        val synced: Boolean,
        val lines: List<LyricsLine>,
    )

    /** 按 utf-8-sig → gbk → utf-8(replace) 的顺序解码。 */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        val payload = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        strictDecode(payload, Charsets.UTF_8)?.let { return it }
        strictDecode(payload, Charset.forName("GBK"))?.let { return it }
        return String(payload, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun parseBytes(bytes: ByteArray): ParseResult = parse(decode(bytes))

    fun parse(text: String): ParseResult {
        if (text.isEmpty()) return ParseResult(synced = false, lines = emptyList())

        // offset 是全局的；多个标签以最后一个为准（与 Python 一致）。
        val offsetMs = OFFSET_TAG.findAll(text).lastOrNull()?.groupValues?.get(1)
            ?.toLongOrNull() ?: 0L

        val timed = ArrayList<TimedLine>()
        val staticLines = ArrayList<String>()

        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val times = TIME_TAG.findAll(line).mapNotNull { match ->
                toMillis(
                    minutes = match.groupValues[1],
                    seconds = match.groupValues[2],
                    fraction = match.groupValues[3],
                )
            }.toList()
            if (times.isEmpty()) {
                if (META_TAG.matches(line) || OFFSET_TAG.matches(line) || COMMENT_LINE.matches(line)) {
                    continue
                }
                staticLines += line.trim()
            } else {
                val content = TIME_TAG.replace(line, "").trim()
                for (time in times) {
                    timed += TimedLine((time + offsetMs).coerceAtLeast(0L), content)
                }
            }
        }

        // 有任何时间标签就按同步歌词处理；否则整段视为静态歌词。
        if (timed.isEmpty()) {
            val lines = trimBlankEdges(
                staticLines.map { LyricsLine(0L, it) },
                collapseRuns = true,
            )
            return ParseResult(
                synced = false,
                lines = lines.take(MAX_LINES),
            )
        }

        val sorted = timed
            .sortedBy { it.timeMs }
            .map { LyricsLine(it.timeMs, it.text) }
        val seen = LinkedHashSet<Pair<Long, String>>()
        val deduped = ArrayList<LyricsLine>(sorted.size)
        for (line in sorted) {
            if (seen.add(line.timeMs to line.text)) deduped += line
        }
        val normalized = trimBlankEdges(deduped, collapseRuns = true)
        return ParseResult(
            synced = true,
            lines = normalized.take(MAX_LINES),
        )
    }

    private fun toMillis(minutes: String, seconds: String, fraction: String): Long? {
        val minuteValue = minutes.toLongOrNull() ?: return null
        val secondValue = seconds.toLongOrNull() ?: return null
        if (secondValue >= 60) return null
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minuteValue * 60_000L + secondValue * 1_000L + millis
    }

    /** 裁掉首尾空行；[collapseRuns] 时把连续空行压缩到 [MAX_CONSECUTIVE_BLANKS] 条。 */
    private fun trimBlankEdges(
        lines: List<LyricsLine>,
        collapseRuns: Boolean = false,
    ): List<LyricsLine> {
        val start = lines.indexOfFirst { it.text.isNotBlank() }
        if (start < 0) return emptyList()
        val end = lines.indexOfLast { it.text.isNotBlank() }
        val result = ArrayList<LyricsLine>(end - start + 1)
        var blankRun = 0
        for (index in start..end) {
            val line = lines[index]
            if (line.text.isBlank()) {
                blankRun++
                if (!collapseRuns || blankRun <= MAX_CONSECUTIVE_BLANKS) result += line
            } else {
                blankRun = 0
                result += line
            }
        }
        return result
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private data class TimedLine(val timeMs: Long, val text: String)
}
