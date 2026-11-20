"""自动偏移校准：用 B 站字幕行的文字给 LRCLIB 歌词估计整首固定偏移。

纯函数模块，不依赖歌词模型或网络；Python 与 Android 保持同一套常量与算法。
输入是两组 ``(时间秒, 文本)``，输出 ``(offset, confidence)`` 或 ``None``。
"""

from __future__ import annotations

import bisect
import math
import unicodedata
from typing import Any, Iterable

CAL_RANGE = 30.0
CAL_STEP = 0.5
CAL_WINDOW = 1.5
CAL_MIN_LINES = 8
CAL_MIN_LINE_LEN = 3
CAL_MIN_LINE_SIM = 0.55
CAL_MIN_MATCH_RATIO = 0.35
CAL_MIN_AVG_SIM = 0.60
CAL_MIN_PEAK_MARGIN = 0.08
CAL_ZERO_MARGIN = 0.03
CAL_MAX_REF = 120
CAL_MAX_CAND = 400


def line_key(text: Any) -> str:
    """归一化行文本：NFKC、小写，只保留字母/数字/CJK，截断 64 字。"""
    normalized = unicodedata.normalize("NFKC", str(text or "")).casefold()
    kept = "".join(char for char in normalized if char.isalnum())
    return kept[:64]


def _sample(items: list[tuple[float, str]], limit: int) -> list[tuple[float, str]]:
    """等距抽样，保持整首的时间覆盖；不足上限时原样返回。"""
    if len(items) <= limit:
        return list(items)
    if limit <= 1:
        return [items[0]]
    step = (len(items) - 1) / (limit - 1)
    return [items[round(index * step)] for index in range(limit)]


def _similarity(left: str, right: str) -> float:
    if not left or not right:
        return 0.0
    if left == right:
        return 1.0
    if left in right or right in left:
        return 0.9
    left_grams = {left[index: index + 2] for index in range(len(left) - 1)} or {left}
    right_grams = {right[index: index + 2] for index in range(len(right) - 1)} or {right}
    return len(left_grams & right_grams) / len(left_grams | right_grams)


def estimate_offset(
    ref_lines: Iterable[tuple[float, Any]],
    cand_lines: Iterable[tuple[float, Any]],
) -> tuple[float, float] | None:
    """估计把 LRCLIB 行时间加上多少秒后与字幕行对齐。

    ``ref_lines`` 是 LRCLIB 行（歌曲时间轴），``cand_lines`` 是 B 站字幕行（视频
    时间轴）。返回 ``(offset, confidence)``；置信度不足时返回 ``None``，调用方保持
    原偏移，行为不比手动校准差。
    """
    ref = [
        (float(time_value), line_key(text))
        for time_value, text in ref_lines or []
    ]
    ref = [(time_value, key) for time_value, key in ref if len(key) >= CAL_MIN_LINE_LEN]
    cand = [
        (float(time_value), line_key(text))
        for time_value, text in cand_lines or []
    ]
    cand = [(time_value, key) for time_value, key in cand if len(key) >= CAL_MIN_LINE_LEN]
    if not ref or not cand:
        return None

    ref = _sample(ref, CAL_MAX_REF)
    cand = _sample(cand, CAL_MAX_CAND)
    cand.sort(key=lambda item: item[0])
    times = [item[0] for item in cand]
    if len(ref) < CAL_MIN_LINES or len(cand) < CAL_MIN_LINES:
        return None

    count = len(ref)
    need_matches = max(CAL_MIN_LINES, math.ceil(CAL_MIN_MATCH_RATIO * count))
    steps = int(round(CAL_RANGE / CAL_STEP))
    # 每个偏移: (归一化总分, 匹配行数, 平均相似度, 平均时间误差)
    scores: dict[int, tuple[float, int, float, float]] = {}
    for step_index in range(-steps, steps + 1):
        offset = step_index * CAL_STEP
        total = 0.0
        matched = 0
        distance_total = 0.0
        for time_value, key in ref:
            target = time_value + offset
            low = bisect.bisect_left(times, target - CAL_WINDOW - 1e-9)
            high = bisect.bisect_right(times, target + CAL_WINDOW + 1e-9)
            best = 0.0
            best_distance = CAL_WINDOW
            for index in range(low, high):
                similarity = _similarity(key, cand[index][1])
                distance = abs(times[index] - target)
                if similarity > best or (
                    abs(similarity - best) <= 1e-9 and distance < best_distance
                ):
                    best = similarity
                    best_distance = distance
                    if best >= 1.0 and distance <= 1e-9:
                        break
            if best >= CAL_MIN_LINE_SIM:
                total += best
                matched += 1
                distance_total += best_distance
        scores[step_index] = (
            total / count,
            matched,
            (total / matched) if matched else 0.0,
            (distance_total / matched) if matched else CAL_WINDOW,
        )

    eligible = [
        step_index
        for step_index, (_normalized, matched, average, _distance) in scores.items()
        if matched >= need_matches and average >= CAL_MIN_AVG_SIM
    ]
    if not eligible:
        return None

    # 窗口内的偏移会形成平台（±1.5s 都是满分）：用平均时间误差挑出真正居中的那个
    best = max(
        eligible,
        key=lambda step_index: (
            scores[step_index][0],
            -scores[step_index][3],
            scores[step_index][1],
            -abs(step_index),
        ),
    )
    best_score, best_matched, best_average, _best_distance = scores[best]

    # 副歌重复会让多个偏移几乎同分；没有明显峰值就放弃，避免自动校准误判
    far_peak = 0.0
    for step_index, (normalized, _matched, _average, _distance) in scores.items():
        if abs(step_index - best) * CAL_STEP > 3.0 and normalized > far_peak:
            far_peak = normalized
    if best_score - far_peak < CAL_MIN_PEAK_MARGIN:
        return None

    # 0 偏移不差时就保持 0，避免 0.5–1.5 秒的抖动式误判
    zero = scores.get(0)
    if best != 0 and zero is not None and zero[0] >= best_score - CAL_ZERO_MARGIN:
        best = 0
        best_score, best_matched, best_average, _best_distance = zero

    confidence = 0.5 * best_average + 0.5 * (best_matched / count)
    return (round(best * CAL_STEP, 3), round(confidence, 3))
