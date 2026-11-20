package com.bilimuse.app.data

import kotlin.math.abs

/**
 * 标题清洗、候选生成与 LRCLIB 打分：纯 JVM，可在 JVM 单测里直接跑。
 *
 * B 站标题是给人看的（`【纯享版】孤勇者 陈奕迅` 直接搜索会返回空数组），所以先按
 * `docs/LYRICS.md` 的规则清洗，再生成最多三个 `(track, artist)` 候选，最后按
 * `track*0.6 + artist*0.2 + duration*0.2` 打分。规则与 Python `lyrics.py` 保持一致。
 */
object LyricsMatch {
    /** 接受一条 LRCLIB 结果所需的最低总分。 */
    const val THRESHOLD = 0.62

    /** 接受一条 LRCLIB 结果所需的最低 track 分。 */
    const val MIN_TRACK = 0.7

    /** 最多生成几个 `(歌名, 歌手)` 候选。 */
    const val MAX_CANDIDATES = 3

    /** 一次解析最多并发几个 LRCLIB 查询。 */
    const val MAX_QUERIES = 4

    /** 视频与曲目时长差超过这个值通常不是同一版本。 */
    const val MAX_DURATION_DIFF = 45

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

    /** 版本/画质标签：`【】 [] （） ()`，整段（含内容）去掉。 */
    private val BRACKET_SEGMENT = Regex("""[【\[（(][^】\]）)]*[】\]）)]""")

    /** 歌名引号：`《》 「」 『』`，只去符号、保留内容（歌名要从里面取）。 */
    private val TITLE_QUOTE = Regex("""《([^》]*)》|「([^」]*)」|『([^』]*)』""")

    /** 破折号分隔：` - ` / ` — ` / ` – ` / `－`，无空格也认。 */
    private val DASH_SEPARATOR = Regex("""\s*[-—–－]\s*""")

    /** `feat.` 子句不属于曲名（遇到分隔符/括号即止）；加词边界，避免误伤 "Soft Rain"。 */
    private val FEAT_CLAUSE = Regex(
        """(?i)(?<![a-z])(?:feat\.?|ft\.?|featuring)(?![a-z])\s+[^)\]）】,，/、;；|\-–—]+""",
    )

    /** 歌手串里的多歌手分隔符；feat./ft. 同样要有词边界，避免拆开 "Daft Punk"。 */
    private val ARTIST_SPLIT = Regex(
        """(?i)\s*(?:(?<![a-z])(?:feat\.?|ft\.?|featuring)(?![a-z])|,|&|/|、|;|；)\s*""",
    )

    /** 标题里出现这些词说明是伴奏/器乐版本。 */
    private val INSTRUMENTAL_TITLE =
        Regex("""伴奏|纯音乐|off\s*vocal|instrumental|karaoke|(?<![a-z])inst(?![a-z])""")

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
                    char == '　' -> ' '
                    else -> char
                },
            )
        }
        return builder.toString().lowercase().replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 清洗标题：去掉包裹标签、feat. 子句与噪声词；`《》「」『』` 只去符号、保留内容。
     */
    fun cleanTitle(raw: String): String {
        var text = normalize(raw)
        text = FEAT_CLAUSE.replace(text, " ")
        text = TITLE_QUOTE.replace(text) { match -> " ${quoteContent(match)} " }
        text = BRACKET_SEGMENT.replace(text, " ")
        text = NOISE_WORD.replace(text, " ")
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 生成最多三个候选：
     * 1. 破折号分隔：左歌名、右歌手；
     * 2. 同一分隔的反向：歌手、歌名；
     * 3. `《》/「」/『』` 里的内容当歌名，括号外文字当歌手；
     * 4. 都没有时整串当歌名，UP 主名当歌手提示。
     */
    fun candidates(title: String, upper: String): List<Query> {
        val normalizedTitle = normalize(title)
        val fallbackArtist = cleanTitle(upper)
        val result = LinkedHashSet<Query>()
        val seen = HashSet<Pair<String, String>>()

        fun add(trackRaw: String, artistRaw: String, artistFromTitle: Boolean) {
            val track = trackRaw.trim()
            val artist = artistRaw.trim()
            if (track.isEmpty()) return
            val key = normalize(track) to normalize(artist)
            if (!seen.add(key)) return
            result += Query(
                track = track,
                artist = artist,
                artistFromTitle = artistFromTitle && artist.isNotEmpty(),
            )
        }

        splitByDash(cleanTitle(title))?.let { (left, right) ->
            val track = left.trim()
            val artist = right.trim()
            if (track.isNotEmpty()) {
                add(track, artist.ifEmpty { fallbackArtist }, artist.isNotEmpty())
            }
            if (artist.isNotEmpty() && normalize(track) != normalize(artist)) {
                add(artist, track, true)
            }
        }

        TITLE_QUOTE.find(normalizedTitle)?.let { match ->
            val track = cleanTitle(quoteContent(match))
            if (track.isNotEmpty()) {
                val outside = cleanTitle(normalizedTitle.replaceRange(match.range, " "))
                add(track, outside.ifEmpty { fallbackArtist }, outside.isNotEmpty())
            }
        }

        if (result.isEmpty()) {
            val track = cleanTitle(title)
            if (track.isNotEmpty()) add(track, fallbackArtist, false)
        }
        return result.take(MAX_CANDIDATES)
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

    /** LRCLIB 查询计划：前两个候选带歌手、完整标题、第一个候选单独曲名。 */
    fun queryPlan(queries: List<Query>, cleanedTitle: String): List<Map<String, String>> {
        val plan = LinkedHashSet<Map<String, String>>()
        queries.take(2).forEach { query ->
            val params = linkedMapOf("track_name" to query.track)
            if (query.artist.isNotBlank() && query.artistFromTitle) {
                params["artist_name"] = query.artist
            }
            plan += params
        }
        if (cleanedTitle.isNotBlank()) plan += mapOf("q" to cleanedTitle)
        queries.firstOrNull()?.let { plan += mapOf("track_name" to it.track) }
        return plan.take(MAX_QUERIES)
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

    /**
     * artist 分：与候选或 UP 主相等 0.9；包含 0.5；否则 0。
     *
     * 歌手串可能写成 `A feat. B` / `A & B` / `A、B`：按分隔符拆成多个名字后交叉比较。
     */
    fun artistScore(candidate: String, query: String, upper: String): Double {
        val normalizedCandidate = normalize(candidate)
        if (normalizedCandidate.isEmpty()) return 0.0
        val targets = listOf(query, upper).map(::normalize).filter { it.isNotEmpty() }
        if (targets.isEmpty()) return 0.0
        if (targets.any { it == normalizedCandidate }) return 0.9
        val candidateParts = splitArtists(normalizedCandidate)
        var best = 0.0
        for (target in targets) {
            val targetParts = splitArtists(target)
            if (candidateParts.any { left -> targetParts.any { it == left } }) {
                best = maxOf(best, 0.9)
                continue
            }
            if (target.contains(normalizedCandidate) || normalizedCandidate.contains(target)) {
                best = maxOf(best, 0.5)
                continue
            }
            if (candidateParts.any { left ->
                    targetParts.any { right -> left.contains(right) || right.contains(left) }
                }
            ) {
                best = maxOf(best, 0.5)
            }
        }
        return best
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
     * 过滤并排序 LRCLIB 候选：排除器乐/伴奏、无歌词、歌手冲突、时长差 > 45s；
     * 排序：总分降序 → 有同步歌词优先 → 时长更近 → id 小。
     */
    fun rank(
        candidates: List<LyricsCandidate>,
        queries: List<Query>,
        upper: String,
        videoDurationSec: Int,
    ): List<Pair<LyricsCandidate, MatchScore>> {
        val ranked = ArrayList<Triple<LyricsCandidate, MatchScore, Long>>()
        for (candidate in candidates) {
            if (candidate.instrumental) continue
            if (!candidate.hasLyrics) continue
            if (INSTRUMENTAL_TITLE.containsMatchIn(normalize(candidate.track))) continue
            if (candidate.duration > 0 && videoDurationSec > 0 &&
                abs(candidate.duration - videoDurationSec) > MAX_DURATION_DIFF
            ) {
                continue
            }
            var bestScore: MatchScore? = null
            for (query in queries) {
                if (trackScore(candidate.track, query.track) < MIN_TRACK) continue
                if (query.artistFromTitle && query.artist.isNotBlank() &&
                    candidate.artist.isNotBlank() &&
                    artistScore(candidate.artist, query.artist, upper) <= 0
                ) {
                    continue
                }
                val score = score(candidate, query, upper, videoDurationSec)
                if (!score.accepted) continue
                if (bestScore == null || score.total > bestScore.total) bestScore = score
            }
            val score = bestScore ?: continue
            val durationDiff = if (candidate.duration > 0 && videoDurationSec > 0) {
                abs(candidate.duration - videoDurationSec).toLong()
            } else {
                Long.MAX_VALUE
            }
            ranked += Triple(candidate, score, durationDiff)
        }
        ranked.sortWith(
            compareByDescending<Triple<LyricsCandidate, MatchScore, Long>> { it.second.total }
                .thenBy { if (it.first.synced) 0 else 1 }
                .thenBy { it.third }
                .thenBy { it.first.id },
        )
        return ranked.map { it.first to it.second }
    }

    /** 保留旧入口：返回排名第一的候选。 */
    fun best(
        candidates: List<LyricsCandidate>,
        queries: List<Query>,
        upper: String,
        videoDurationSec: Int,
    ): Pair<LyricsCandidate, MatchScore>? =
        rank(candidates, queries, upper, videoDurationSec).firstOrNull()

    // ------------------------------------------------------------ 内部工具

    private fun splitByDash(text: String): Pair<String, String>? {
        val match = DASH_SEPARATOR.find(text) ?: return null
        return text.substring(0, match.range.first) to text.substring(match.range.last + 1)
    }

    private fun quoteContent(match: MatchResult): String =
        match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun splitArtists(text: String): List<String> =
        ARTIST_SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val result = LinkedHashSet<String>(text.length)
        for (index in 0 until text.length - 1) {
            result += text.substring(index, index + 2)
        }
        return result
    }
}
