package com.bilimuse.app.data

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 自动校准结果：整首偏移（秒）与置信度（0–1）。 */
data class OffsetEstimate(
    val offset: Double,
    val confidence: Double,
)

/**
 * 自动偏移校准：用 B 站字幕行的文字给 LRCLIB 歌词估计整首固定偏移。
 *
 * 纯 JVM 模块，不依赖歌词模型或网络；常量与算法与 Python `bilimuse/lyrics_offset.py`
 * 逐项对齐。输入是两组 `(时间秒, 文本)`，输出 `OffsetEstimate` 或 `null`。
 */
object LyricsOffset {
    const val CAL_RANGE = 30.0
    const val CAL_STEP = 0.5
    const val CAL_WINDOW = 1.5
    const val CAL_MIN_LINES = 8
    const val CAL_MIN_LINE_LEN = 3
    const val CAL_MIN_LINE_SIM = 0.55
    const val CAL_MIN_MATCH_RATIO = 0.35
    const val CAL_MIN_AVG_SIM = 0.60
    const val CAL_MIN_PEAK_MARGIN = 0.08
    const val CAL_ZERO_MARGIN = 0.03
    const val CAL_MAX_REF = 120
    const val CAL_MAX_CAND = 400

    /** 归一化行文本：NFKC、小写，只保留字母/数字/CJK，截断 64 字。 */
    fun lineKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(64)

    fun estimate(
        refLines: List<Pair<Double, String>>,
        candLines: List<Pair<Double, String>>,
    ): OffsetEstimate? {
        val ref = refLines
            .map { (time, text) -> time to lineKey(text) }
            .filter { it.second.length >= CAL_MIN_LINE_LEN }
        val cand = candLines
            .map { (time, text) -> time to lineKey(text) }
            .filter { it.second.length >= CAL_MIN_LINE_LEN }
        if (ref.isEmpty() || cand.isEmpty()) return null

        val sampledRef = sample(ref, CAL_MAX_REF)
        val sampledCand = sample(cand, CAL_MAX_CAND).sortedBy { it.first }
        val times = sampledCand.map { it.first }
        if (sampledRef.size < CAL_MIN_LINES || sampledCand.size < CAL_MIN_LINES) return null

        val count = sampledRef.size
        val needMatches = maxOf(CAL_MIN_LINES, ceil(CAL_MIN_MATCH_RATIO * count).toInt())
        val steps = (CAL_RANGE / CAL_STEP).roundToInt()
        val scores = LinkedHashMap<Int, Score>()
        for (stepIndex in -steps..steps) {
            val offset = stepIndex * CAL_STEP
            var total = 0.0
            var matched = 0
            var distanceTotal = 0.0
            for ((timeValue, key) in sampledRef) {
                val target = timeValue + offset
                val low = lowerBound(times, target - CAL_WINDOW - 1e-9)
                val high = upperBound(times, target + CAL_WINDOW + 1e-9)
                var best = 0.0
                var bestDistance = CAL_WINDOW
                var index = low
                while (index < high) {
                    val similarity = similarity(key, sampledCand[index].second)
                    val distance = abs(times[index] - target)
                    if (similarity > best ||
                        (abs(similarity - best) <= 1e-9 && distance < bestDistance)
                    ) {
                        best = similarity
                        bestDistance = distance
                        if (best >= 1.0 && distance <= 1e-9) break
                    }
                    index++
                }
                if (best >= CAL_MIN_LINE_SIM) {
                    total += best
                    matched++
                    distanceTotal += bestDistance
                }
            }
            scores[stepIndex] = Score(
                normalized = total / count,
                matched = matched,
                average = if (matched > 0) total / matched else 0.0,
                distance = if (matched > 0) distanceTotal / matched else CAL_WINDOW,
            )
        }

        val eligible = scores.filter { (_step, score) ->
            score.matched >= needMatches && score.average >= CAL_MIN_AVG_SIM
        }.keys
        if (eligible.isEmpty()) return null

        var bestStep = eligible.maxWith(
            compareBy<Int>(
                { scores[it]!!.normalized },
                { -scores[it]!!.distance },
                { scores[it]!!.matched },
                { -abs(it) },
            ),
        )
        var best = scores[bestStep]!!

        // 副歌重复会让多个偏移几乎同分；没有明显峰值就放弃，避免自动校准误判
        var farPeak = 0.0
        for ((stepIndex, score) in scores) {
            if (abs(stepIndex - bestStep) * CAL_STEP > 3.0 && score.normalized > farPeak) {
                farPeak = score.normalized
            }
        }
        if (best.normalized - farPeak < CAL_MIN_PEAK_MARGIN) return null

        // 0 偏移不差时就保持 0，避免 0.5–1.5 秒的抖动式误判
        val zero = scores[0]
        if (bestStep != 0 && zero != null &&
            zero.normalized >= best.normalized - CAL_ZERO_MARGIN
        ) {
            bestStep = 0
            best = zero
        }

        val confidence = 0.5 * best.average + 0.5 * (best.matched.toDouble() / count)
        return OffsetEstimate(
            offset = round3(bestStep * CAL_STEP),
            confidence = round3(confidence),
        )
    }

    // ------------------------------------------------------------ 内部工具

    private data class Score(
        val normalized: Double,
        val matched: Int,
        val average: Double,
        val distance: Double,
    )

    private fun sample(
        lines: List<Pair<Double, String>>,
        limit: Int,
    ): List<Pair<Double, String>> {
        if (lines.size <= limit) return lines.toList()
        if (limit <= 1) return listOf(lines.first())
        val step = (lines.size - 1).toDouble() / (limit - 1)
        return (0 until limit).map { lines[(it * step).roundToInt()] }
    }

    private fun similarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) return 0.9
        val leftGrams = bigrams(left)
        val rightGrams = bigrams(right)
        val union = (leftGrams + rightGrams).size
        if (union == 0) return 0.0
        return leftGrams.count { it in rightGrams }.toDouble() / union
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val result = LinkedHashSet<String>(text.length)
        for (index in 0 until text.length - 1) {
            result += text.substring(index, index + 2)
        }
        return result
    }

    private fun lowerBound(values: List<Double>, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) / 2
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBound(values: List<Double>, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) / 2
            if (values[mid] <= target) low = mid + 1 else high = mid
        }
        return low
    }

    private fun round3(value: Double): Double = (value * 1000).roundToInt() / 1000.0
}
