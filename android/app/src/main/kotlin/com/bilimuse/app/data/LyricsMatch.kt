package com.bilimuse.app.data

import kotlin.math.abs

/**
 * 标题清洗、候选生成与 LRCLIB 打分：纯 JVM，可在 JVM 单测里直接跑。
 *
 * B 站标题是给人看的（`【纯享版】孤勇者 陈奕迅` 直接搜索会返回空数组），所以先按
 * `docs/LYRICS.md` 的规则清洗，再生成最多两个 `(track, artist)` 候选，最后按
 * `track*0.6 + artist*0.2 + duration*0.2` 打分。
 */
object LyricsMatch {
    /** 接受一条 LRCLIB 结果所需的最低总分。 */
    const val THRESHOLD = 0.62

    /** 接受一条 LRCLIB 结果所需的最低 track 分。 */
    const val MIN_TRACK = 0.7

    /** 一个 `(歌名, 歌手)` 候选。 */
    data class Query(
        val track: String,
        val artist: String = "",
        /**
         * [artist] 是否来自标题解析（`歌名 - 歌手` / `《歌名》歌手`）。
         * UP 主兜底的歌手只用于打分与搜索提示，不能当 `artist_name` 查询参数。
         */
        val artistFromTitle: Boolean = true,
    )

    /** 三个分项与加权总分。 */
    data class MatchScore(
        val track: Double,
        val artist: Double,
        val duration: Double,
    ) {
        val total: Double get() = track * 0.6 + artist * 0.2 + duration * 0.2

        val accepted: Boolean get() = total >= THRESHOLD && track >= MIN_TRACK
    }

    /** 包裹型标签：`【】 [] （） () 「」 『』`，整段（含内容）去掉。 */
    private val BRACKET_SEGMENT = Regex("""[【\[（(「『][^】\]）)」』]*[】\]）)」』]""")

    /** `《歌名》`，只去符号、保留内容（歌名要从里面取）。 */
    private val BOOK_TITLE = Regex("""《([^》]*)》""")

    /** 破折号分隔：` - ` / ` — ` / ` – ` / `－`。 */
    private val DASH_SEPARATOR = Regex("""\s+[-—–]\s+|－""")

    /** 噪声词：先长后短，避免 `无损` 抢先吃掉 `无损音质`。 */
    private const val NOISE_WORD_PATTERN =
        "无损音质|中文字幕|动态歌词|完整版|高音质|纯享|现场|首播|臻享|花絮|翻唱|伴奏|纯音乐|合集|1080p|60帧|4k|官方|mv|pv|live|hd|hq|无损"

    private val NOISE_WORD = Regex("""(?i)(?<![a-z0-9])($NOISE_WORD_PATTERN)(?![a-z0-9])""")

    /** 全角转半角、英文小写、空白压缩。 */
    fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (char in text) {
            builder.append(
                when {
                    char.code in 0xFF01..0xFF5E -> (char.code - 0xFEE0).toChar()
                    char == '\u3000' -> ' '
                    else -> char
                },
            )
        }
        return builder.toString().lowercase().replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 清洗标题：去掉包裹标签、噪声词与《》符号，得到适合搜索的曲名文本。
     *
     * 注意 `《...》` 只去符号、保留内容——候选生成时还要从书名号里取歌名。
     */
    fun cleanTitle(raw: String): String {
        var text = normalize(raw)
        text = BRACKET_SEGMENT.replace(text, " ")
        text = text.replace("《", " ").replace("》", " ")
        text = NOISE_WORD.replace(text, " ")
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 生成最多两个 `(track, artist)` 候选：
     * 1. 破折号分隔：左歌名、右歌手；
     * 2. `《》` 里的内容当歌名，括号外文字当歌手；
     * 3. 都没有时整串当歌名，UP 主名当歌手提示。
     */
    fun candidates(title: String, upper: String): List<Query> {
        val normalizedTitle = normalize(title)
        val fallbackArtist = cleanTitle(upper)
        val result = LinkedHashSet<Query>()

        splitByDash(cleanTitle(title))?.let { (track, artist) ->
            val cleanedTrack = track.trim()
            val parsedArtist = artist.trim()
            if (cleanedTrack.isNotEmpty()) {
                result += Query(
                    track = cleanedTrack,
                    artist = parsedArtist.ifEmpty { fallbackArtist },
                    artistFromTitle = parsedArtist.isNotEmpty(),
                )
            }
        }

        BOOK_TITLE.find(normalizedTitle)?.let { match ->
            val track = cleanTitle(match.groupValues[1])
            if (track.isNotEmpty()) {
                val outside = cleanTitle(normalizedTitle.replace(match.value, " "))
                result += Query(
                    track = track,
                    artist = outside.ifEmpty { fallbackArtist },
                    artistFromTitle = outside.isNotEmpty(),
                )
            }
        }

        if (result.isEmpty()) {
            val track = cleanTitle(title)
            if (track.isNotEmpty()) {
                result += Query(track, fallbackArtist, artistFromTitle = false)
            }
        }
        return result.take(2).toList()
    }

    /** 搜索面板的预填关键词：清洗后的「歌名 歌手」。 */
    fun searchQuery(title: String, upper: String): String {
        val first = candidates(title, upper).firstOrNull()
        return if (first == null) {
            cleanTitle(title)
        } else {
            listOf(first.track, first.artist).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** track 分：完全相等 1.0；包含 0.8；否则字符二元组 Jaccard 重合率（与 Python 一致）。 */
    fun trackScore(candidate: String, query: String): Double {
        val left = normalize(candidate)
        val right = normalize(query)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) return 0.8
        val leftBigrams = bigrams(left)
        val rightBigrams = bigrams(right)
        if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) return 0.0
        val overlap = leftBigrams.count { it in rightBigrams }
        if (overlap == 0) return 0.0
        val union = (leftBigrams + rightBigrams).size
        return overlap.toDouble() / union
    }

    /** artist 分：与候选歌手或 UP 主相等 0.9；包含 0.5；否则 0。 */
    fun artistScore(candidate: String, query: String, upper: String): Double {
        val normalizedCandidate = normalize(candidate)
        if (normalizedCandidate.isEmpty()) return 0.0
        val targets = listOf(query, upper).map(::normalize).filter { it.isNotEmpty() }
        if (targets.any { it == normalizedCandidate }) return 0.9
        if (targets.any { it.contains(normalizedCandidate) || normalizedCandidate.contains(it) }) {
            return 0.5
        }
        return 0.0
    }

    /**
     * 时长分：差 ≤2 秒 1.0，之后每秒线性衰减，60 秒以上 0。
     *
     * 候选或视频时长未知（≤0）时返回 1.0，不惩罚——与 Python `duration_score` 一致。
     */
    fun durationScore(candidateDurationSec: Int, videoDurationSec: Int): Double {
        if (candidateDurationSec <= 0 || videoDurationSec <= 0) return 1.0
        val diff = abs(candidateDurationSec - videoDurationSec)
        if (diff <= 2) return 1.0
        return (1.0 - (diff - 2) / 60.0).coerceAtLeast(0.0)
    }

    fun score(
        candidate: LyricsCandidate,
        query: Query,
        upper: String,
        videoDurationSec: Int,
    ): MatchScore = MatchScore(
        track = trackScore(candidate.track, query.track),
        artist = artistScore(candidate.artist, query.artist, upper),
        duration = durationScore(candidate.duration, videoDurationSec),
    )

    /**
     * 在候选里挑一条可接受的结果：排除 instrumental，要求通过阈值，同分取时长更接近的。
     */
    fun best(
        candidates: List<LyricsCandidate>,
        queries: List<Query>,
        upper: String,
        videoDurationSec: Int,
    ): Pair<LyricsCandidate, MatchScore>? {
        var bestCandidate: LyricsCandidate? = null
        var bestScore: MatchScore? = null
        var bestDurationDiff = Long.MAX_VALUE
        for (candidate in candidates) {
            if (candidate.instrumental) continue
            for (query in queries) {
                val score = score(candidate, query, upper, videoDurationSec)
                if (!score.accepted) continue
                val durationDiff: Long = if (candidate.duration > 0 && videoDurationSec > 0) {
                    abs(candidate.duration - videoDurationSec).toLong()
                } else {
                    Long.MAX_VALUE
                }
                val current = bestScore
                val better = current == null ||
                    score.total > current.total + 1e-9 ||
                    (abs(score.total - current.total) <= 1e-9 && durationDiff < bestDurationDiff)
                if (better) {
                    bestCandidate = candidate
                    bestScore = score
                    bestDurationDiff = durationDiff
                }
            }
        }
        val matched = bestCandidate ?: return null
        return matched to (bestScore ?: return null)
    }

    // ------------------------------------------------------------ 内部工具

    private fun splitByDash(text: String): Pair<String, String>? {
        val match = DASH_SEPARATOR.find(text) ?: return null
        return text.substring(0, match.range.first) to text.substring(match.range.last + 1)
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val result = LinkedHashSet<String>(text.length)
        for (index in 0 until text.length - 1) {
            result += text.substring(index, index + 2)
        }
        return result
    }
}
