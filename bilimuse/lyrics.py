"""歌词：LRC 解析、标题匹配、来源阶梯、缓存与偏好。

契约见 docs/LYRICS.md。两条原则贯穿全文件：

1. 不伪造歌词：每行歌词都说清来源，找不到就返回 ``status="none"``。
2. 歌词永远不能影响播放：所有远端错误都在这里降级，调用方只需兜底。

来源阶梯：本地 LRC → 用户锁定（pin）→ B 站人工字幕 → LRCLIB → B 站 AI 字幕 → none。
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import httpx

from . import config
from .api import BiliClient
from .lyrics_offset import estimate_offset

# ---------------------------------------------------------------- 常量

LRCLIB_API = "https://lrclib.net/api"
LRCLIB_UA = f"BiliMuse/{config.VERSION} (https://github.com/Jackiechen259/bilimuse)"

BVID_RE = re.compile(r"^[A-Za-z0-9]{1,24}$")
DEMO_BVIDS = ("DEMO0001", "DEMO0002", "DEMO0003")

MAX_LRC_BYTES = 256 * 1024
MAX_LRC_LINES = 2000
MAX_SEARCH_RESULTS = 10
MAX_CANDIDATES = 3
MAX_QUERIES = 4
MIN_AI_LINES = 4
# 远端响应体积上限（LRCLIB / B 站字幕）：4 MB，防止异常响应占满内存
MAX_REMOTE_BYTES = 4 * 1024 * 1024

CACHE_VERSION = 2
CACHE_TTL = 30 * 24 * 3600  # 命中缓存 30 天
MISS_TTL = 6 * 3600  # 未命中缓存 6 小时

MATCH_THRESHOLD = 0.62
TRACK_THRESHOLD = 0.7
# LRCLIB 查询：每次解析最多 4 条并行，整轮总预算 7 秒
LRCLIB_CONCURRENCY = 4
LRCLIB_BUDGET = 7.0
# 查询结果内存缓存：正结果 1 小时、空结果 10 分钟，最多 128 条
SEARCH_TTL = 3600.0
SEARCH_MISS_TTL = 600.0
SEARCH_CACHE_MAX = 128
# B 站轨道探测短缓存：换源面板紧跟着解析打开时不再重复请求
BILI_TRACKS_TTL = 60.0
# 换源面板复用最近一次解析结果，避免重复探测 B 站与 LRCLIB
PROBE_SNAPSHOT_TTL = 300.0
# 视频与曲目时长差超过这个值通常不是同一版本（前奏/口白仍允许 45s 以内）
MAX_DURATION_DIFF = 45.0
# 标题里出现这些词说明是伴奏/器乐版本，即使 LRCLIB 没标 instrumental 也不用
_INSTRUMENTAL_TITLE_RE = re.compile(
    r"伴奏|纯音乐|off\s*vocal|instrumental|karaoke|(?<![a-z])inst(?![a-z])"
)

OFFSET_MIN = -30.0
OFFSET_MAX = 30.0

PIN_SOURCES = ("local", "bili", "lrclib", "demo")

LRCLIB_SOURCE_LABELS = {
    "local": "本地歌词",
    "lrclib": "LRCLIB",
    "demo": "演示歌词",
}

# 时间标签：[mm:ss]、[mm:ss.x]、[mm:ss.xx]、[mm:ss.xxx]、[m:ss]
TIME_TAG_RE = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:\.(\d{1,3}))?\]")
OFFSET_RE = re.compile(r"\[offset:\s*([+-]?\d+)\s*\]", re.IGNORECASE)
META_LINE_RE = re.compile(r"^\[[A-Za-z#]+:[^\]]*\]$")
# 占位内容：「♪ 音乐 ♪」「音乐」「音」
PLACEHOLDER_RE = re.compile(r"^[♪♫\s]*音乐?[♪♫\s]*$")
# B 站 AI 字幕用这些符号标出一句完整唱词的边界
MUSIC_MARKS = ("♪", "♫", "♬", "♩")

NOISE_WORDS = (
    "4K",
    "1080P",
    "60帧",
    "高音质",
    "无损",
    "官方",
    "MV",
    "PV",
    "纯享",
    "完整版",
    "现场",
    "Live",
    "中文字幕",
    "动态歌词",
    "HD",
    "HQ",
    "首播",
    "臻享",
    "花絮",
    "翻唱",
    "伴奏",
    "纯音乐",
    "合集",
    "无损音质",
)
# 标题里只去符号、保留内容的引号：《》「」『』
_TITLE_QUOTE_RE = re.compile(r"《([^》]*)》|「([^」]*)」|『([^』]*)』")
# 版本/画质标签：整段（含内容）去掉
_BRACKET_RE = re.compile(r"【[^】]*】|\[[^\]]*\]|（[^）]*）|\([^)]*\)")
# feat. 子句不属于曲名（遇到分隔符/括号即止）；前后加词边界，避免误伤 "Soft Rain"
_FEAT_RE = re.compile(
    r"(?i)(?<![a-z])(?:feat\.?|ft\.?|featuring)(?![a-z])\s+"
    r"[^)\]）】,，/、;；|\-–—]+"
)
# 歌手串里的多歌手分隔符；feat./ft. 同样要有词边界，避免拆开 "Daft Punk"
_ARTIST_SPLIT_RE = re.compile(
    r"(?i)\s*(?:(?<![a-z])(?:feat\.?|ft\.?|featuring)(?![a-z])|,|&|/|、|;|；)\s*"
)
_SEPARATOR_RE = re.compile(r"\s*[-—–－]\s*")

DEMO_LINES: tuple[tuple[int, str], ...] = (
    (2_000, "这是 45 秒的合成器演示音轨"),
    (7_000, "由本地 BTR 引擎分块并发加载"),
    (12_000, "右下角可以看到节点与实时速度"),
    (17_000, "关闭多线程，缓冲会明显变慢"),
    (22_000, "歌词来自本机演示数据"),
    (27_000, "不访问网络，也不属于任何歌曲"),
    (32_000, "打开设置可以切换三套界面风格"),
    (37_000, "按 L 可以随时收起歌词"),
)

_UNSET = object()

# 偏好文件的读改写跨线程事务：解析在 await 处挂起时，用户可能同时 POST 手动偏移
_PREFS_LOCK = threading.RLock()


class LyricsError(RuntimeError):
    """歌词内容/来源不可用（内部用，最终都降级成 status=none）。"""


@dataclass
class LyricLine:
    t: float
    text: str

    def to_dict(self) -> dict[str, Any]:
        return {"t": round(float(self.t), 3), "text": self.text}


# ---------------------------------------------------------------- 基础工具


def is_valid_bvid(bvid: Any) -> bool:
    return isinstance(bvid, str) and bool(BVID_RE.match(bvid))


def is_demo_bvid(bvid: Any) -> bool:
    return isinstance(bvid, str) and bvid in DEMO_BVIDS


def demo_lines() -> list[LyricLine]:
    return [LyricLine(millis / 1000.0, text) for millis, text in DEMO_LINES]


def clamp_offset(value: Any) -> float:
    try:
        offset = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(OFFSET_MIN, min(OFFSET_MAX, offset))


def source_label(source: str, kind: str = "") -> str:
    if source == "bili":
        return "B 站字幕（人工）" if kind == "manual" else "B 站字幕（AI 转写）"
    return LRCLIB_SOURCE_LABELS.get(source, "")


def _as_float(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _empty_available() -> dict[str, Any]:
    return {"local": False, "bili": False, "lrclib": False}


def none_result(
    reason: str = "not_found",
    *,
    offset: float = 0.0,
    available: dict[str, Any] | None = None,
    cached: bool = False,
) -> dict[str, Any]:
    return {
        "status": "none",
        "source": "",
        "source_label": "",
        "synced": False,
        "offset": clamp_offset(offset),
        "lines": [],
        "meta": {
            "matched": None,
            "available": dict(available or _empty_available()),
            "cached": cached,
            "offset_source": "",
            "auto_offset": None,
            "auto_offset_confidence": None,
        },
        "reason": reason,
    }


def _ok_result(
    source: str,
    kind: str,
    lines: Iterable[LyricLine],
    synced: bool,
    *,
    offset: float = 0.0,
    matched: dict[str, Any] | None = None,
    available: dict[str, Any] | None = None,
    cached: bool = False,
) -> dict[str, Any]:
    return {
        "status": "ok",
        "source": source,
        "source_label": source_label(source, kind),
        "synced": bool(synced),
        "offset": clamp_offset(offset),
        "lines": [line.to_dict() for line in lines],
        "meta": {
            "matched": matched,
            "available": dict(available or _empty_available()),
            "cached": cached,
            "offset_source": "",
            "auto_offset": None,
            "auto_offset_confidence": None,
        },
        "reason": "",
    }


def _with_pin(result: dict[str, Any], prefs: dict[str, Any]) -> dict[str, Any]:
    """把当前的 pin 写进 meta；ok / none / 缓存命中都要带，前端据此展示锁定来源。"""
    pin = prefs.get("pin")
    meta = dict(result.get("meta") or {})
    meta["pin"] = dict(pin) if isinstance(pin, dict) else None
    result["meta"] = meta
    return result


def _apply_offset_source(
    result: dict[str, Any], prefs: dict[str, Any]
) -> dict[str, Any]:
    """按当前偏好写 meta.offset_source；auto_offset 等字段缺省补 null。"""
    meta = dict(result.get("meta") or {})
    mode = str(prefs.get("offset_mode") or "auto")
    if mode == "manual":
        meta["offset_source"] = "manual"
    else:
        meta["offset_source"] = "auto" if _as_float(prefs.get("offset")) else ""
    meta.setdefault("auto_offset", None)
    meta.setdefault("auto_offset_confidence", None)
    result["meta"] = meta
    return result


# ---------------------------------------------------------------- LRC 解析


def decode_lrc(raw: bytes) -> str:
    """编码回退：utf-8-sig → gbk → utf-8(replace)。"""
    for encoding in ("utf-8-sig", "gbk"):
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def _tag_seconds(match: re.Match[str]) -> float:
    minutes = int(match.group(1))
    seconds = int(match.group(2))
    fraction = match.group(3) or ""
    millis = int(fraction) / (10 ** len(fraction)) if fraction else 0.0
    return minutes * 60 + seconds + millis


def _blank_kept_indexes(texts: list[str]) -> list[int]:
    """保留文本索引：中间连续空行最多 2 个，首尾空行全部裁掉。"""
    kept: list[int] = []
    blank_run = 0
    for index, text in enumerate(texts):
        if text:
            blank_run = 0
            kept.append(index)
        else:
            blank_run += 1
            if blank_run <= 2:
                kept.append(index)
    while kept and not texts[kept[0]]:
        kept.pop(0)
    while kept and not texts[kept[-1]]:
        kept.pop()
    return kept


def _trim_blank_lines(lines: list[LyricLine]) -> list[LyricLine]:
    """裁掉首尾空行，中间连续空行最多保留 2 个（参与滚动）。"""
    return [
        lines[index] for index in _blank_kept_indexes([line.text for line in lines])
    ]


def parse_lrc_text(text: str) -> tuple[list[LyricLine], bool]:
    """解析 LRC，返回 (按时间升序去重的行, 是否带时间轴)。"""
    if not isinstance(text, str):
        return [], False
    text = text.lstrip("\ufeff")

    offset_seconds = 0.0
    offsets = OFFSET_RE.findall(text)
    if offsets:
        try:
            offset_seconds = int(offsets[-1]) / 1000.0
        except (TypeError, ValueError):
            offset_seconds = 0.0

    timed: list[tuple[float, str]] = []
    plain: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            # 保留空行（同步歌词里空行参与滚动；纯文本歌词同样按行展示）
            plain.append("")
            continue
        if line.startswith("#"):
            continue
        matches = list(TIME_TAG_RE.finditer(line))
        if matches:
            lyric = line[matches[-1].end():].strip()
            for match in matches:
                timed.append((_tag_seconds(match) + offset_seconds, lyric))
            continue
        if META_LINE_RE.match(line):
            continue
        plain.append(line)

    if not timed:
        # 没有任何时间标签：静态歌词，按行原样展示（t 只是序号占位）
        kept = [plain[index] for index in _blank_kept_indexes(plain)]
        return [LyricLine(float(index), lyric) for index, lyric in enumerate(kept)], False

    seen: set[tuple[float, str]] = set()
    lines: list[LyricLine] = []
    for raw_time, lyric in timed:
        t = max(0.0, round(raw_time, 3))
        key = (t, lyric)
        if key in seen:
            continue
        seen.add(key)
        lines.append(LyricLine(t, lyric))
    lines.sort(key=lambda item: item.t)
    return _trim_blank_lines(lines)[:MAX_LRC_LINES], True


def parse_lrc_bytes(raw: bytes) -> tuple[list[LyricLine], bool]:
    if len(raw) > MAX_LRC_BYTES:
        raise ValueError("歌词文件超过 256 KB")
    return parse_lrc_text(decode_lrc(raw))


def parse_lrc_file(path: Path) -> tuple[list[LyricLine], bool]:
    return parse_lrc_bytes(Path(path).read_bytes())


# ---------------------------------------------------------------- 标题清洗与匹配


def normalize_text(text: Any) -> str:
    """全角转半角 + 统一小写 + 压缩空白。"""
    if text is None:
        return ""
    chars: list[str] = []
    for char in str(text):
        code = ord(char)
        if code == 0x3000:
            chars.append(" ")
        elif 0xFF01 <= code <= 0xFF5E:
            chars.append(chr(code - 0xFEE0))
        else:
            chars.append(char)
    return re.sub(r"\s+", " ", "".join(chars)).strip().casefold()


def _strip_noise(text: str) -> str:
    for word in sorted(NOISE_WORDS, key=len, reverse=True):
        token = word.casefold()
        if re.fullmatch(r"[a-z0-9]+", token):
            text = re.sub(
                rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", " ", text
            )
        else:
            text = text.replace(token, " ")
    return text


def clean_title(title: Any) -> str:
    """把 B 站标题洗成适合搜曲库的文本；《》「」『』 里的歌名要保留。"""
    text = normalize_text(title)
    text = _FEAT_RE.sub(" ", text)
    # 《起风了》→ 起风了（保留内容）；「」『』 同理，其余括号里的通常是噪声标签，整段去掉
    text = _TITLE_QUOTE_RE.sub(lambda match: f" {match.group(1) or match.group(2) or match.group(3)} ", text)
    text = _BRACKET_RE.sub(" ", text)
    text = _strip_noise(text)
    text = re.sub(r"[ \t]+", " ", text)
    text = _SEPARATOR_RE.sub(" - ", text)
    return text.strip(" -")


def title_candidates(title: Any, upper: Any = "") -> list[tuple[str, str]]:
    """生成最多三个 (歌名, 歌手) 候选；歌手为空时由调用方用 UP 主名提示。"""
    raw = str(title or "").strip()
    if not raw:
        return []

    candidates: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()

    def add(track: str, artist: str) -> None:
        track = track.strip()
        artist = artist.strip()
        if not track:
            return
        key = (normalize_text(track), normalize_text(artist))
        if key in seen:
            return
        seen.add(key)
        candidates.append((track, artist))

    cleaned = clean_title(raw)
    parts = [part.strip() for part in _SEPARATOR_RE.split(cleaned) if part.strip()]
    if len(parts) >= 2:
        # 常见有「歌名 - 歌手」和「歌手 - 歌名」两种写法，两种顺序都作为候选；
        # 最终由 LRCLIB 打分决定哪条可信。
        add(parts[0], parts[1])
        if normalize_text(parts[0]) != normalize_text(parts[1]):
            add(parts[1], parts[0])

    normalized = normalize_text(raw)
    quote = _TITLE_QUOTE_RE.search(normalized)
    if quote and _quote_content(quote).strip():
        outside = clean_title(_TITLE_QUOTE_RE.sub(" ", normalized))
        add(_quote_content(quote), outside)

    if not candidates and cleaned:
        add(cleaned, "")

    return candidates[:MAX_CANDIDATES]


def query_plan(
    candidates: list[tuple[str, str]], cleaned_title: str
) -> list[dict[str, str]]:
    """LRCLIB 查询计划：前两个候选带歌手、完整标题、第一个候选单独曲名。"""
    plan: list[dict[str, str]] = []

    def add(params: dict[str, str]) -> None:
        if params and params not in plan:
            plan.append(params)

    for track, artist in candidates[:2]:
        params = {"track_name": track}
        if artist:
            params["artist_name"] = artist
        add(params)
    if cleaned_title:
        add({"q": cleaned_title})
    if candidates:
        add({"track_name": candidates[0][0]})
    return plan[:MAX_QUERIES]


def similarity(left: Any, right: Any) -> float:
    """0–1：完全相等 1.0，包含 0.8，否则字符二元组 Jaccard 重合率。"""
    left_text = normalize_text(left)
    right_text = normalize_text(right)
    if not left_text or not right_text:
        return 0.0
    if left_text == right_text:
        return 1.0
    if left_text in right_text or right_text in left_text:
        return 0.8
    left_grams = {left_text[i: i + 2] for i in range(len(left_text) - 1)} or {left_text}
    right_grams = {right_text[i: i + 2] for i in range(len(right_text) - 1)} or {right_text}
    return len(left_grams & right_grams) / len(left_grams | right_grams)


def _quote_content(match: re.Match[str]) -> str:
    return match.group(1) or match.group(2) or match.group(3) or ""


def artist_score(candidate: Any, actual: Any) -> float:
    """歌手：与候选或 UP 主归一化后相等 0.9、包含 0.5，否则 0。

    歌手串可能写成 `A feat. B` / `A & B` / `A、B`：按分隔符拆成多个名字后交叉比较。
    """
    candidate_text = normalize_text(candidate)
    actual_text = normalize_text(actual)
    if not candidate_text or not actual_text:
        return 0.0
    if candidate_text == actual_text:
        return 0.9

    def components(text: str) -> list[str]:
        return [part.strip() for part in _ARTIST_SPLIT_RE.split(text) if part.strip()]

    left = components(candidate_text)
    right = components(actual_text)
    if any(a == b for a in left for b in right):
        return 0.9
    if candidate_text in actual_text or actual_text in candidate_text:
        return 0.5
    if any(a in b or b in a for a in left for b in right):
        return 0.5
    return 0.0


def duration_score(candidate_duration: Any, video_duration: Any) -> float:
    """时长差 ≤ 2s 满分，之后每多 60s 扣 1，最多扣到 0。"""
    video = _as_float(video_duration)
    candidate = _as_float(candidate_duration)
    if video <= 0 or candidate <= 0:
        return 1.0
    diff = abs(candidate - video)
    if diff <= 2:
        return 1.0
    return max(0.0, 1.0 - (diff - 2) / 60.0)


def score_record(
    record: Any,
    track: Any,
    artist: Any = "",
    upper: Any = "",
    video_duration: Any = 0,
) -> float:
    """单条 LRCLIB 记录对某个候选的得分：track*0.6 + artist*0.2 + duration*0.2。"""
    if not isinstance(record, dict):
        return 0.0
    track_part = similarity(record.get("trackName"), track)
    artist_part = 0.0
    for name in (artist, upper):
        if str(name or "").strip():
            artist_part = max(artist_part, artist_score(name, record.get("artistName")))
    return (
        track_part * 0.6
        + artist_part * 0.2
        + duration_score(record.get("duration"), video_duration) * 0.2
    )


def _duration_diff(candidate_duration: Any, video_duration: Any) -> float:
    video = _as_float(video_duration)
    candidate = _as_float(candidate_duration)
    if video <= 0 or candidate <= 0:
        return float("inf")
    return abs(candidate - video)


def _merge_record(
    existing: dict[str, Any], incoming: dict[str, Any]
) -> dict[str, Any]:
    """同一 id 被多个查询返回时，优先保留真正带歌词的那一份。"""
    for field in ("syncedLyrics", "plainLyrics"):
        if not str(existing.get(field) or "").strip() and str(
            incoming.get(field) or ""
        ).strip():
            return incoming
    return existing


def _is_instrumental(record: dict[str, Any]) -> bool:
    if record.get("instrumental"):
        return True
    return bool(_INSTRUMENTAL_TITLE_RE.search(normalize_text(record.get("trackName"))))


def _artist_conflicts(
    artist: Any, record_artist: Any, upper: Any
) -> bool:
    """标题解析出的歌手与记录歌手明确冲突时拒绝；UP 主名能对上就不算冲突。"""
    candidate_artist = normalize_text(artist)
    actual_artist = normalize_text(record_artist)
    if not candidate_artist or not actual_artist:
        return False
    if artist_score(candidate_artist, actual_artist) > 0:
        return False
    return artist_score(upper, actual_artist) <= 0


def rank_matches(
    records: Iterable[Any],
    candidates: list[tuple[str, str]],
    upper: Any = "",
    video_duration: Any = 0,
) -> list[tuple[dict[str, Any], float]]:
    """过滤并排序 LRCLIB 记录；第一条就是 pick_match 以前返回的最佳记录。

    过滤：器乐/伴奏、没有可用歌词、标题歌手冲突、时长差 > 45s。
    排序：总分降序 → 有同步歌词优先 → 时长更近 → id 小。
    """
    video = _as_float(video_duration)
    ranked: list[tuple[dict[str, Any], float, bool, float, float]] = []
    for record in records or []:
        if not isinstance(record, dict) or _is_instrumental(record):
            continue
        lines, synced = _record_lines(record)
        if not lines:
            continue
        record_duration = _as_float(record.get("duration"))
        if video > 0 and record_duration > 0 and abs(record_duration - video) > MAX_DURATION_DIFF:
            continue
        best: float | None = None
        for track, artist in candidates:
            if similarity(record.get("trackName"), track) < TRACK_THRESHOLD:
                continue
            if _artist_conflicts(artist, record.get("artistName"), upper):
                continue
            score = score_record(record, track, artist, upper, video)
            if score < MATCH_THRESHOLD:
                continue
            if best is None or score > best:
                best = score
        if best is None:
            continue
        ranked.append(
            (
                record,
                best,
                bool(synced),
                _duration_diff(record.get("duration"), video),
                _as_float(record.get("id")),
            )
        )
    ranked.sort(key=lambda item: (-item[1], not item[2], item[3], item[4]))
    return [(record, score) for record, score, _synced, _diff, _id in ranked]


def pick_match(
    records: Iterable[Any],
    candidates: list[tuple[str, str]],
    upper: Any = "",
    video_duration: Any = 0,
) -> tuple[dict[str, Any], float] | None:
    """从一批 LRCLIB 结果里挑最可信的一条；保留原签名供旧调用方使用。"""
    ranked = rank_matches(records, candidates, upper, video_duration)
    return ranked[0] if ranked else None


# ---------------------------------------------------------------- B 站字幕转换


def _strip_music_marks(text: str) -> str:
    """去掉 AI 字幕里的 ♪ 标记，并把因此产生的多余空白压成一个空格。"""
    for mark in MUSIC_MARKS:
        text = text.replace(mark, " ")
    return re.sub(r"\s+", " ", text).strip()


def subtitle_lines(body: Iterable[Any], *, ai: bool = False) -> list[LyricLine]:
    """把字幕 JSON 的 body[{from,to,content}] 转成歌词行。

    AI 字幕是句子级碎片：相邻间隔 ≤ 0.3s、合并后 ≤ 24 字、总跨度 ≤ 8s 时
    合并成一行，时间取第一段的 from。合并后不足 4 行视为没有歌词。
    """
    segments: list[tuple[float, float, str, bool]] = []
    for item in body or []:
        if not isinstance(item, dict):
            continue
        raw = str(item.get("content") or "").strip()
        if not raw or PLACEHOLDER_RE.match(raw):
            continue
        if ai:
            # B 站 AI 字幕会把整句歌词包在 ♪ 里，这些标记只是分段提示，不该展示；
            # 带标记的段本身是一句完整的唱词，不再和相邻碎片合并。
            text = _strip_music_marks(raw)
            standalone = any(mark in raw for mark in MUSIC_MARKS)
            if not text:
                continue
        else:
            text = raw
            standalone = False
        try:
            start = max(0.0, float(item.get("from")))
        except (TypeError, ValueError):
            continue
        end = max(start, _as_float(item.get("to")))
        segments.append((round(start, 3), round(end, 3), text, standalone))
    if not segments:
        return []

    if not ai:
        seen: set[tuple[float, str]] = set()
        lines: list[LyricLine] = []
        for start, _end, text, _alone in segments:
            key = (start, text)
            if key in seen:
                continue
            seen.add(key)
            lines.append(LyricLine(start, text))
        lines.sort(key=lambda item: item.t)
        return lines[:MAX_LRC_LINES]

    merged: list[LyricLine] = []
    cur_start, cur_end, cur_text, cur_alone = segments[0]
    for start, end, text, alone in segments[1:]:
        if (
            not cur_alone
            and not alone
            and start - cur_end <= 0.3
            and len(cur_text) + len(text) <= 24
            and max(cur_end, end) - cur_start <= 8.0
        ):
            cur_text += text
            cur_end = max(cur_end, end)
            continue
        merged.append(LyricLine(cur_start, cur_text))
        cur_start, cur_end, cur_text = start, end, text
    merged.append(LyricLine(cur_start, cur_text))
    merged.sort(key=lambda item: item.t)
    if len(merged) < MIN_AI_LINES:
        return []
    return merged[:MAX_LRC_LINES]


def _bili_kind(tracks: Iterable[Any] | None) -> str:
    """人工字幕优先；没有人工时看有没有 AI 中文轨道。"""
    manual = False
    ai = False
    for track in tracks or []:
        if not isinstance(track, dict):
            continue
        lan = str(track.get("lan") or "")
        if lan.startswith("ai-"):
            ai = True
        else:
            manual = True
    if manual:
        return "manual"
    return "ai" if ai else ""


def _pick_bili_tracks(
    tracks: Iterable[Any] | None,
) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    """人工字幕优先，AI 里再优先 ai-zh（契约要求），其余 ai-* 兜底。"""
    manual = None
    ai_zh = None
    ai_other = None
    for track in tracks or []:
        if not isinstance(track, dict):
            continue
        lan = str(track.get("lan") or "")
        if not lan.startswith("ai-"):
            if manual is None:
                manual = track
            continue
        if lan == "ai-zh":
            if ai_zh is None:
                ai_zh = track
        elif ai_other is None:
            ai_other = track
    return manual, ai_zh or ai_other


def _record_lines(record: dict[str, Any]) -> tuple[list[LyricLine], bool]:
    synced_text = record.get("syncedLyrics")
    if isinstance(synced_text, str) and synced_text.strip():
        try:
            lines, synced = parse_lrc_text(synced_text)
        except ValueError:
            lines, synced = [], False
        if lines:
            return lines, synced
    plain = record.get("plainLyrics")
    if isinstance(plain, str) and plain.strip():
        lines = [
            LyricLine(float(index), part.strip())
            for index, part in enumerate(plain.splitlines())
            if part.strip()
        ]
        if lines:
            return lines[:MAX_LRC_LINES], False
    return [], False


def _matched(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": int(_as_float(record.get("id"))),
        "track": str(record.get("trackName") or ""),
        "artist": str(record.get("artistName") or ""),
        "duration": int(_as_float(record.get("duration"))),
    }


# ---------------------------------------------------------------- 服务


class LyricsService:
    """解析一首歌的歌词：来源阶梯 + 缓存 + 偏好。

    ``bili`` / ``http`` 由 server.py 在 lifespan 里注入：``bili`` 是带登录态的
    B 站客户端，``http`` 必须是独立、不带任何 B 站 Cookie 的 LRCLIB 客户端。
    """

    def __init__(
        self,
        bili: BiliClient | None = None,
        http: httpx.AsyncClient | None = None,
        *,
        lyrics_dir: str | Path | None = None,
        cache_dir: str | Path | None = None,
    ) -> None:
        self.bili = bili
        self.http = http
        self.lyrics_dir = Path(lyrics_dir) if lyrics_dir else config.LYRICS_DIR
        self.cache_dir = Path(cache_dir) if cache_dir else (config.CACHE_DIR / "lyrics")
        self._locks: dict[str, asyncio.Lock] = {}
        # 查询结果只驻内存：key 是已经发给 LRCLIB 的清洗后查询参数，不落盘
        self._search_cache: OrderedDict[
            tuple[tuple[str, str], ...], tuple[float, list[dict[str, Any]]]
        ] = OrderedDict()
        self._search_inflight: dict[
            tuple[tuple[str, str], ...], asyncio.Task[list[dict[str, Any]]]
        ] = {}
        self._lrclib_sem = asyncio.Semaphore(LRCLIB_CONCURRENCY)
        # B 站轨道探测短缓存：bvid -> (expires, tracks, page_duration)
        self._bili_cache: OrderedDict[
            str, tuple[float, list[dict[str, Any]], float]
        ] = OrderedDict()
        # 换源快照：bvid -> (expires, snapshot)
        self._snapshots: OrderedDict[str, tuple[float, dict[str, Any]]] = OrderedDict()
        self._now = time.monotonic

    # ------------------------------------------------------------ 路径

    def lrc_path(self, bvid: str) -> Path:
        return self.lyrics_dir / f"{bvid}.lrc"

    def prefs_path(self, bvid: str) -> Path:
        return self.lyrics_dir / f"{bvid}.json"

    def cache_path(self, bvid: str) -> Path:
        return self.cache_dir / f"{bvid}.json"

    def _local_exists(self, bvid: str) -> bool:
        try:
            return self.lrc_path(bvid).is_file()
        except OSError:
            return False

    def _lock_for(self, bvid: str) -> asyncio.Lock:
        """取（或创建）按 bvid 的解析锁；表太大时顺手清掉已释放的旧锁。"""
        if len(self._locks) > 256:
            for key, lock in list(self._locks.items()):
                if key != bvid and not lock.locked():
                    self._locks.pop(key, None)
        lock = self._locks.get(bvid)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[bvid] = lock
        return lock

    # ------------------------------------------------------------ 偏好

    @staticmethod
    def _default_prefs() -> dict[str, Any]:
        return {"offset": 0.0, "offset_mode": "auto", "pin": None}

    def _read_prefs(self, bvid: str) -> dict[str, Any]:
        with _PREFS_LOCK:
            return self._read_prefs_unlocked(bvid)

    def _read_prefs_unlocked(self, bvid: str) -> dict[str, Any]:
        prefs = self._default_prefs()
        try:
            raw = json.loads(self.prefs_path(bvid).read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            return prefs
        if not isinstance(raw, dict):
            return prefs
        prefs["offset"] = clamp_offset(raw.get("offset", 0.0))
        mode = raw.get("offset_mode")
        if mode in {"auto", "manual"}:
            prefs["offset_mode"] = mode
        else:
            # 旧文件没有模式：非零偏移尊重为手动，零偏移允许自动校准
            prefs["offset_mode"] = "manual" if prefs["offset"] else "auto"
        pin = raw.get("pin")
        if isinstance(pin, dict) and str(pin.get("source") or "") in PIN_SOURCES:
            pin_id: int | None = None
            if pin.get("id") is not None:
                try:
                    pin_id = int(pin["id"])
                except (TypeError, ValueError):
                    pin_id = None
            prefs["pin"] = {"source": str(pin["source"]), "id": pin_id}
        return prefs

    def _write_prefs(self, bvid: str, prefs: dict[str, Any]) -> None:
        with _PREFS_LOCK:
            self._write_prefs_unlocked(bvid, prefs)

    def _write_prefs_unlocked(self, bvid: str, prefs: dict[str, Any]) -> None:
        try:
            self.lyrics_dir.mkdir(parents=True, exist_ok=True)
            target = self.prefs_path(bvid)
            tmp = target.with_name(f".{target.name}.tmp")
            tmp.write_text(
                json.dumps(prefs, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            os.replace(tmp, target)
        except OSError:
            pass

    def preferences(self, bvid: str) -> dict[str, Any]:
        return self._read_prefs(bvid)

    def update_prefs(
        self,
        bvid: str,
        *,
        offset: Any = _UNSET,
        offset_mode: Any = _UNSET,
        pin_source: Any = _UNSET,
        pin_id: Any = _UNSET,
    ) -> dict[str, Any]:
        """更新并持久化偏好；传 None 表示清除，未传的字段保持不变。"""
        with _PREFS_LOCK:
            return self._update_prefs_unlocked(
                bvid,
                offset=offset,
                offset_mode=offset_mode,
                pin_source=pin_source,
                pin_id=pin_id,
            )

    def _update_prefs_unlocked(
        self,
        bvid: str,
        *,
        offset: Any = _UNSET,
        offset_mode: Any = _UNSET,
        pin_source: Any = _UNSET,
        pin_id: Any = _UNSET,
    ) -> dict[str, Any]:
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        prefs = self._read_prefs(bvid)
        if offset is not _UNSET:
            prefs["offset"] = 0.0 if offset is None else clamp_offset(offset)
            # 用户手动调偏即进入 manual；手动归零则允许下次重新自动校准
            prefs["offset_mode"] = "manual" if prefs["offset"] else "auto"
        if offset_mode is not _UNSET:
            mode = "auto" if offset_mode is None else str(offset_mode).strip().lower()
            if mode not in {"auto", "manual"}:
                raise ValueError("未知的偏移模式")
            prefs["offset_mode"] = mode
            if mode == "auto":
                prefs["offset"] = 0.0

        if pin_source is not _UNSET or pin_id is not _UNSET:
            if pin_source is not _UNSET:
                if pin_source is None:
                    prefs["pin"] = None
                else:
                    source = str(pin_source).strip().lower()
                    if source not in PIN_SOURCES:
                        raise ValueError("未知的歌词来源")
                    new_id: int | None = None
                    if pin_id is not _UNSET and pin_id is not None:
                        try:
                            new_id = int(pin_id)
                        except (TypeError, ValueError):
                            raise ValueError("pin_id 不合法")
                    elif (
                        prefs.get("pin")
                        and prefs["pin"].get("source") == source
                    ):
                        new_id = prefs["pin"].get("id")
                    # LRCLIB 必须指定具体候选；B 站字幕按来源锁定即可，
                    # 阶梯内部会优先挑人工字幕，再退 AI。
                    if source == "lrclib" and new_id is None:
                        raise ValueError("该来源需要提供 id")
                    prefs["pin"] = {"source": source, "id": new_id}
            else:  # 只传了 pin_id
                if not prefs.get("pin"):
                    raise ValueError("还没有锁定来源")
                if pin_id is None:
                    prefs["pin"]["id"] = None
                else:
                    try:
                        prefs["pin"]["id"] = int(pin_id)
                    except (TypeError, ValueError):
                        raise ValueError("pin_id 不合法")
        self._write_prefs(bvid, prefs)
        return prefs

    # ------------------------------------------------------------ 缓存

    def _read_cache(
        self, bvid: str, prefs: dict[str, Any]
    ) -> dict[str, Any] | None:
        try:
            raw = json.loads(self.cache_path(bvid).read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            return None
        if not isinstance(raw, dict) or raw.get("v") != CACHE_VERSION:
            return None
        try:
            expires = float(raw.get("expires", 0))
        except (TypeError, ValueError):
            return None
        if expires <= time.time():
            return None
        if raw.get("pin") != prefs.get("pin"):
            return None
        result = raw.get("result")
        if not isinstance(result, dict) or result.get("status") not in {"ok", "none"}:
            return None
        if result.get("status") == "none" and str(result.get("reason") or "") in {
            "disabled",
            "not_logged_in",
        }:
            # 旧版本可能留下这类缓存；开关 / 登录态已经变了，直接丢弃
            return None
        source = str(result.get("source") or "")
        if result["status"] == "ok":
            if source not in {"lrclib", "bili"}:
                return None
            if source == "lrclib" and not config.settings.lyrics_online:
                return None
            if source == "bili" and not config.settings.lyrics_bili:
                return None
        return result

    def _write_cache(
        self, bvid: str, result: dict[str, Any], prefs: dict[str, Any]
    ) -> None:
        source = str(result.get("source") or "")
        pin = prefs.get("pin")
        if isinstance(pin, dict) and source != str(pin.get("source") or ""):
            # 有 pin 时只缓存真正命中 pin 的结果；降级结果不能被缓存遮蔽
            return
        if result.get("status") == "none" and str(result.get("reason") or "") in {
            "disabled",
            "not_logged_in",
        }:
            # 来源开关 / 登录态是可变的：这类空结果缓存后用户改设置也看不到新结果
            return
        if result.get("status") == "ok" and source not in {"lrclib", "bili"}:
            return
        ttl = CACHE_TTL if result.get("status") == "ok" else MISS_TTL
        payload = {
            "v": CACHE_VERSION,
            "expires": time.time() + ttl,
            "pin": prefs.get("pin"),
            "result": {
                **result,
                "offset": 0.0,
                "meta": {**(result.get("meta") or {}), "cached": False},
            },
        }
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            target = self.cache_path(bvid)
            tmp = target.with_name(f".{target.name}.tmp")
            tmp.write_text(
                json.dumps(payload, ensure_ascii=False), encoding="utf-8"
            )
            os.replace(tmp, target)
        except OSError:
            pass

    def _delete_cache(self, bvid: str) -> None:
        try:
            self.cache_path(bvid).unlink()
        except OSError:
            pass

    # ------------------------------------------------------------ 远端请求

    async def _fetch_search(self, params: dict[str, str]) -> list[dict[str, Any]]:
        if self.http is None:
            raise LyricsError("LRCLIB 不可用")
        response = await self.http.get(f"{LRCLIB_API}/search", params=params)
        if response.status_code == 404:
            return []
        response.raise_for_status()
        if len(response.content) > MAX_REMOTE_BYTES:
            raise LyricsError("LRCLIB 返回内容过大")
        try:
            data = response.json()
        except ValueError as exc:
            raise LyricsError("LRCLIB 返回了无法解析的内容") from exc
        if not isinstance(data, list):
            return []
        return [item for item in data if isinstance(item, dict)]

    async def _fetch_record(self, lrclib_id: int) -> dict[str, Any] | None:
        if self.http is None:
            raise LyricsError("LRCLIB 不可用")
        response = await self.http.get(f"{LRCLIB_API}/get/{int(lrclib_id)}")
        if response.status_code == 404:
            return None
        response.raise_for_status()
        if len(response.content) > MAX_REMOTE_BYTES:
            raise LyricsError("LRCLIB 返回内容过大")
        try:
            data = response.json()
        except ValueError as exc:
            raise LyricsError("LRCLIB 返回了无法解析的内容") from exc
        return data if isinstance(data, dict) else None

    async def _fetch_bili_tracks(self, bvid: str) -> list[dict[str, Any]]:
        now = self._now()
        cached = self._bili_cache.get(bvid)
        if cached is not None and cached[0] > now:
            self._bili_cache.move_to_end(bvid)
            return [dict(track) for track in cached[1]]
        bili = self.bili
        if bili is None:
            return []
        page = await bili.page_info(bvid)
        cid = int(page.get("cid") or 0) if isinstance(page, dict) else 0
        duration = _as_float(page.get("duration")) if isinstance(page, dict) else 0.0
        tracks: list[dict[str, Any]] = []
        if cid:
            tracks = [
                item
                for item in await bili.subtitle_tracks(bvid, cid)
                if isinstance(item, dict)
            ]
        self._bili_cache[bvid] = (now + BILI_TRACKS_TTL, tracks, duration)
        self._bili_cache.move_to_end(bvid)
        while len(self._bili_cache) > 128:
            self._bili_cache.popitem(last=False)
        return [dict(track) for track in tracks]

    def _page_duration(self, bvid: str) -> float:
        cached = self._bili_cache.get(bvid)
        if cached is None or cached[0] <= self._now():
            return 0.0
        return cached[2]

    def _logged_in(self) -> bool:
        bili = self.bili
        return bool(
            bili is not None
            and getattr(getattr(bili, "session", None), "logged_in", False)
        )

    def _store_snapshot(
        self,
        bvid: str,
        bili_kind: str,
        bili_entry_id: int | None,
        matched: Any,
    ) -> None:
        self._snapshots[bvid] = (
            self._now() + PROBE_SNAPSHOT_TTL,
            {
                "online": bool(config.settings.lyrics_online),
                "bili_enabled": bool(config.settings.lyrics_bili),
                "logged_in": self._logged_in(),
                "bili_kind": bili_kind,
                "bili_entry_id": bili_entry_id,
                "lrclib_matched": matched,
            },
        )
        self._snapshots.move_to_end(bvid)
        while len(self._snapshots) > 128:
            self._snapshots.popitem(last=False)

    def _remember_snapshot(self, bvid: str, result: dict[str, Any]) -> None:
        # 本地 / 演示 / 带 pin 的来源没有完整探测阶梯，不能拿它当换源可用性快照
        if str(result.get("source") or "") in {"local", "demo"}:
            return
        if result.get("status") == "none" and str(result.get("reason") or "") in {
            "timeout",
            "error",
        }:
            # 瞬时失败不能在 5 分钟内把换源面板锁成「不可用」
            return
        meta = result.get("meta") or {}
        if meta.get("pin"):
            return
        available = meta.get("available") or {}
        entry_id: int | None = None
        cached = self._bili_cache.get(bvid)
        if cached is not None:
            manual_entry, ai_entry = _pick_bili_tracks(cached[1])
            entry = manual_entry or ai_entry
            if entry is not None:
                entry_id = int(_as_float(entry.get("id")))
        self._store_snapshot(
            bvid,
            str(available.get("bili") or ""),
            entry_id,
            meta.get("matched"),
        )

    def _snapshot(self, bvid: str) -> dict[str, Any] | None:
        entry = self._snapshots.get(bvid)
        if entry is None:
            return None
        expires, snapshot = entry
        if expires <= self._now():
            self._snapshots.pop(bvid, None)
            return None
        if snapshot["online"] != bool(config.settings.lyrics_online):
            return None
        if snapshot["bili_enabled"] != bool(config.settings.lyrics_bili):
            return None
        if snapshot["logged_in"] != self._logged_in():
            return None
        return snapshot

    async def _fetch_bili_subtitle(self, url: str) -> dict[str, Any]:
        bili = self.bili
        if bili is None:
            raise LyricsError("B 站字幕不可用")
        return await bili.subtitle_body(url)

    async def _download_subtitle(
        self, track: dict[str, Any]
    ) -> tuple[list[LyricLine], str] | None:
        kind = "ai" if str(track.get("lan") or "").startswith("ai-") else "manual"
        body = await self._fetch_bili_subtitle(str(track.get("url") or ""))
        raw = body.get("body") if isinstance(body, dict) else None
        lines = subtitle_lines(raw, ai=(kind == "ai"))
        if not lines:
            return None
        return lines, kind

    # ------------------------------------------------------------ 匹配

    async def _search_cached(
        self, params: dict[str, str], *, refresh: bool = False
    ) -> list[dict[str, Any]]:
        """带内存缓存与在途合并的单条 LRCLIB 搜索。"""
        key = tuple(sorted(params.items()))
        now = self._now()
        if not refresh:
            entry = self._search_cache.get(key)
            if entry is not None:
                expires, records = entry
                if expires > now:
                    self._search_cache.move_to_end(key)
                    return [dict(item) for item in records]
                self._search_cache.pop(key, None)

        task = self._search_inflight.get(key)
        if task is None:
            async def run() -> list[dict[str, Any]]:
                async with self._lrclib_sem:
                    return await self._fetch_search(params)

            task = asyncio.create_task(run())
            self._search_inflight[key] = task

            def _done(finished: asyncio.Task[list[dict[str, Any]]]) -> None:
                if self._search_inflight.get(key) is finished:
                    self._search_inflight.pop(key, None)

            task.add_done_callback(_done)

        # shield：单个调用方被取消（切歌 / 关视图）时，不连带取消其他视频共享的查询
        records = await asyncio.shield(task)
        ttl = SEARCH_TTL if records else SEARCH_MISS_TTL
        self._search_cache[key] = (self._now() + ttl, list(records or []))
        self._search_cache.move_to_end(key)
        while len(self._search_cache) > SEARCH_CACHE_MAX:
            self._search_cache.popitem(last=False)
        return [dict(item) for item in records or []]

    async def _search_lrclib(
        self,
        candidates: list[tuple[str, str]],
        cleaned_title: str,
        upper: str,
        video_duration: float,
        *,
        refresh: bool = False,
    ) -> list[tuple[dict[str, Any], float]]:
        """并行执行查询计划，合并去重后全局排序；返回可依次尝试的候选列表。"""
        plan = query_plan(candidates, cleaned_title)
        if not plan:
            return []
        tasks = [
            asyncio.create_task(self._search_cached(params, refresh=refresh))
            for params in plan
        ]
        done, pending = await asyncio.wait(tasks, timeout=LRCLIB_BUDGET)
        records: list[dict[str, Any]] = []
        failure: str | None = None
        for task in done:
            try:
                records.extend(task.result())
            except httpx.TimeoutException:
                failure = failure or "timeout"
            except asyncio.CancelledError:
                failure = failure or "timeout"
            except Exception:
                failure = failure or "error"
        for task in pending:
            if not task.done():
                task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
            if not records:
                failure = failure or "timeout"

        merged: dict[Any, dict[str, Any]] = {}
        for record in records:
            if not isinstance(record, dict):
                continue
            key = record.get("id")
            if key is None:
                key = id(record)
            existing = merged.get(key)
            merged[key] = _merge_record(existing, record) if existing else record

        ranked = rank_matches(merged.values(), candidates, upper, video_duration)
        if not ranked and not records:
            if failure == "timeout":
                raise httpx.ReadTimeout("LRCLIB 超时")
            if failure == "error":
                raise LyricsError("LRCLIB 不可用")
        return ranked

    def _lrclib_result(
        self, record: dict[str, Any], available: dict[str, Any]
    ) -> dict[str, Any] | None:
        lines, synced = _record_lines(record)
        if not lines:
            return None
        return _ok_result(
            "lrclib",
            "",
            lines,
            synced,
            matched=_matched(record),
            available={**available, "lrclib": True},
        )

    # ------------------------------------------------------------ 解析

    async def resolve(
        self,
        bvid: str,
        title: str = "",
        upper: str = "",
        duration: Any = 0,
        refresh: bool = False,
    ) -> dict[str, Any]:
        """按来源阶梯解析；永远返回统一结果体，不向调用方抛远端错误。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        prefs = self._read_prefs(bvid)
        if is_demo_bvid(bvid):
            # 演示曲目默认给演示歌词，但用户自己导入的本地 LRC 仍然是最高优先。
            local = self._load_local(bvid)
            if local is not None:
                lines, synced = local
                result = _with_pin(
                    _apply_offset_source(
                        _ok_result(
                            "local",
                            "",
                            lines,
                            synced,
                            offset=prefs["offset"],
                            available={"local": True, "bili": False, "lrclib": False},
                        ),
                        prefs,
                    ),
                    prefs,
                )
            else:
                result = _with_pin(
                    _apply_offset_source(
                        _ok_result(
                            "demo",
                            "",
                            demo_lines(),
                            True,
                            offset=prefs["offset"],
                            available=_empty_available(),
                        ),
                        prefs,
                    ),
                    prefs,
                )
            self._remember_snapshot(bvid, result)
            return result
        async with self._lock_for(bvid):
            result = await self._resolve_locked(
                bvid, str(title or ""), str(upper or ""), _as_float(duration), bool(refresh)
            )
        self._remember_snapshot(bvid, result)
        return result

    async def _resolve_locked(
        self,
        bvid: str,
        title: str,
        upper: str,
        duration: float,
        refresh: bool,
    ) -> dict[str, Any]:
        prefs = self._read_prefs(bvid)
        local = self._load_local(bvid)
        if local is not None:
            lines, synced = local
            return _with_pin(
                _apply_offset_source(
                    _ok_result(
                        "local",
                        "",
                        lines,
                        synced,
                        offset=prefs["offset"],
                        available={"local": True, "bili": False, "lrclib": False},
                    ),
                    prefs,
                ),
                prefs,
            )

        # 有 pin 时跳过缓存读取：pin 源可能只是瞬时失败，不能被降级缓存挡住重试
        if not refresh and not prefs.get("pin"):
            cached = self._read_cache(bvid, prefs)
            if cached is not None:
                offset = prefs["offset"]
                if prefs.get("offset_mode") == "auto" and not offset:
                    cached_auto = (cached.get("meta") or {}).get("auto_offset")
                    if isinstance(cached_auto, (int, float)):
                        offset = clamp_offset(cached_auto)
                        prefs["offset"] = offset
                return _with_pin(
                    _apply_offset_source(
                        {
                            **cached,
                            "offset": offset,
                            "lines": [dict(line) for line in cached.get("lines") or []],
                            "meta": {**(cached.get("meta") or {}), "cached": True},
                        },
                        prefs,
                    ),
                    prefs,
                )

        result = await self._ladder(bvid, title, upper, duration, prefs, refresh)
        result["offset"] = prefs["offset"]
        _apply_offset_source(result, prefs)
        _with_pin(result, prefs)
        self._write_cache(bvid, result, prefs)
        return result

    def _load_local(self, bvid: str) -> tuple[list[LyricLine], bool] | None:
        try:
            lines, synced = parse_lrc_file(self.lrc_path(bvid))
        except (OSError, ValueError):
            return None
        if not lines:
            return None
        return lines, synced

    async def _calibration_lines(self, bvid: str) -> list[LyricLine] | None:
        """取本视频自己的字幕行用于校准；人工优先、AI 兜底，失败只返回 None。"""
        bili = self.bili
        logged_in = bool(
            bili is not None
            and getattr(getattr(bili, "session", None), "logged_in", False)
        )
        if not (config.settings.lyrics_bili and logged_in):
            return None
        try:
            tracks = await self._fetch_bili_tracks(bvid)
        except Exception:
            return None
        manual_entry, ai_entry = _pick_bili_tracks(tracks)
        entry = manual_entry or ai_entry
        if entry is None:
            return None
        try:
            downloaded = await self._download_subtitle(entry)
        except Exception:
            return None
        return downloaded[0] if downloaded is not None else None

    def _calibrate(
        self,
        bvid: str,
        result: dict[str, Any],
        prefs: dict[str, Any],
        record: dict[str, Any],
        subtitle_lines: list[LyricLine] | None,
    ) -> dict[str, Any]:
        """用字幕行估计偏移；手动模式下只记录候选值，不覆盖用户设置。"""
        if not subtitle_lines or not result.get("synced"):
            return _apply_offset_source(result, prefs)
        lrclib_lines, _synced = _record_lines(record)
        if not lrclib_lines:
            return _apply_offset_source(result, prefs)
        estimate = estimate_offset(
            [(line.t, line.text) for line in lrclib_lines],
            [(line.t, line.text) for line in subtitle_lines],
        )
        if estimate is None:
            return _apply_offset_source(result, prefs)
        auto_offset, confidence = estimate
        meta = dict(result.get("meta") or {})
        meta["auto_offset"] = round(auto_offset, 3)
        meta["auto_offset_confidence"] = round(confidence, 3)
        result["meta"] = meta
        # 解析期间用户可能刚手动调过偏移：写盘前在锁内重读一次，manual 永不被覆盖
        with _PREFS_LOCK:
            fresh = self._read_prefs(bvid)
            prefs.update(fresh)
            if str(prefs.get("offset_mode") or "auto") == "manual":
                return _apply_offset_source(result, prefs)
            prefs["offset"] = clamp_offset(auto_offset)
            prefs["offset_mode"] = "auto"
            self._write_prefs(bvid, prefs)
        result["offset"] = prefs["offset"]
        return _apply_offset_source(result, prefs)

    async def _ladder(
        self,
        bvid: str,
        title: str,
        upper: str,
        duration: float,
        prefs: dict[str, Any],
        refresh: bool = False,
    ) -> dict[str, Any]:
        online = bool(config.settings.lyrics_online)
        bili_enabled = bool(config.settings.lyrics_bili)
        bili = self.bili
        logged_in = bool(
            bili is not None
            and getattr(getattr(bili, "session", None), "logged_in", False)
        )
        available = _empty_available()
        saw_timeout = False
        saw_error = False
        tracks: list[dict[str, Any]] | None = None
        tracks_loaded = False

        async def ensure_tracks() -> list[dict[str, Any]] | None:
            nonlocal tracks, tracks_loaded, saw_timeout, saw_error
            if tracks_loaded:
                return tracks
            tracks_loaded = True
            if not (bili_enabled and logged_in):
                return None
            try:
                tracks = await self._fetch_bili_tracks(bvid)
            except httpx.TimeoutException:
                saw_timeout = True
                tracks = None
            except Exception:
                saw_error = True
                tracks = None
            return tracks

        # 1) 用户锁定的来源优先于自动阶梯（本地文件在上面已处理）
        pin = prefs.get("pin")
        if isinstance(pin, dict):
            source = str(pin.get("source") or "")
            pin_id = pin.get("id")
            if source == "lrclib" and online and pin_id is not None:
                # 字幕探测与 pin 记录并行，避免为校准多等 2-3 个串行往返
                subtitle_task: asyncio.Task[Any] | None = None
                if bili_enabled and logged_in:
                    subtitle_task = asyncio.create_task(self._calibration_lines(bvid))
                try:
                    record = await self._fetch_record(int(pin_id))
                except httpx.TimeoutException:
                    saw_timeout = True
                    record = None
                except Exception:
                    saw_error = True
                    record = None
                pinned = None
                if record:
                    pinned = self._lrclib_result(record, available)
                    if pinned is not None and pinned.get("synced"):
                        subtitle = None
                        if subtitle_task is not None:
                            try:
                                subtitle = await subtitle_task
                            except Exception:
                                subtitle = None
                        pinned = self._calibrate(
                            bvid, pinned, prefs, record, subtitle
                        )
                if subtitle_task is not None and not subtitle_task.done():
                    subtitle_task.cancel()
                    await asyncio.gather(subtitle_task, return_exceptions=True)
                if pinned is not None:
                    return pinned
            elif source == "bili" and bili_enabled and logged_in:
                pinned_tracks = await ensure_tracks()
                entry = None
                if pin_id is not None:
                    entry = next(
                        (
                            item
                            for item in pinned_tracks or []
                            if int(_as_float(item.get("id"))) == int(pin_id)
                        ),
                        None,
                    )
                else:
                    # 只锁来源时沿用阶梯自己的挑法：人工字幕优先，AI 兜底。
                    manual_pin, ai_pin = _pick_bili_tracks(pinned_tracks)
                    entry = manual_pin or ai_pin
                if entry is not None:
                    try:
                        downloaded = await self._download_subtitle(entry)
                    except httpx.TimeoutException:
                        saw_timeout = True
                        downloaded = None
                    except Exception:
                        saw_error = True
                        downloaded = None
                    if downloaded is not None:
                        lines, kind = downloaded
                        return _ok_result(
                            "bili",
                            kind,
                            lines,
                            True,
                            available={**available, "bili": kind},
                        )

        # 2) B 站人工字幕
        all_tracks = await ensure_tracks()
        manual_entry, ai_entry = _pick_bili_tracks(all_tracks)
        available["bili"] = _bili_kind(all_tracks) or False
        # 没有人工字幕时，AI 字幕与 LRCLIB 搜索并行下载：命中 LRCLIB 就用于校准，
        # 没命中就作为兜底，避免为了校准多等一次 CDN。
        ai_task: asyncio.Task[Any] | None = None
        if online and manual_entry is None and ai_entry is not None:
            ai_task = asyncio.create_task(self._download_subtitle(ai_entry))
        if manual_entry is not None:
            try:
                downloaded = await self._download_subtitle(manual_entry)
            except httpx.TimeoutException:
                saw_timeout = True
                downloaded = None
            except Exception:
                saw_error = True
                downloaded = None
            if downloaded is not None:
                lines, kind = downloaded
                return _ok_result(
                    "bili", kind, lines, True, available=dict(available)
                )
            # 人工字幕下载失败：仍可用 AI 字幕做校准 / 兜底
            if online and ai_entry is not None and ai_task is None:
                ai_task = asyncio.create_task(self._download_subtitle(ai_entry))

        # 3) LRCLIB
        if online:
            candidates = title_candidates(title, upper)
            if candidates:
                effective_duration = duration
                if effective_duration <= 0:
                    effective_duration = self._page_duration(bvid)
                try:
                    ranked = await self._search_lrclib(
                        candidates,
                        clean_title(title),
                        upper,
                        effective_duration,
                        refresh=refresh,
                    )
                except httpx.TimeoutException:
                    saw_timeout = True
                    ranked = []
                except Exception:
                    saw_error = True
                    ranked = []
                # 最佳记录若解析不出歌词，继续尝试次选，而不是直接掉到 AI 字幕
                for record, _score in ranked[:3]:
                    result = self._lrclib_result(record, available)
                    if result is not None:
                        subtitle: list[LyricLine] | None = None
                        if ai_task is not None:
                            try:
                                downloaded = await ai_task
                            except httpx.TimeoutException:
                                saw_timeout = True
                                downloaded = None
                            except Exception:
                                saw_error = True
                                downloaded = None
                            if downloaded is not None:
                                subtitle = downloaded[0]
                        if result.get("synced"):
                            result = self._calibrate(
                                bvid, result, prefs, record, subtitle
                            )
                        return result

        # 4) B 站 AI 字幕兜底
        if ai_entry is not None:
            downloaded: tuple[list[LyricLine], str] | None = None
            if ai_task is not None:
                try:
                    downloaded = await ai_task
                except httpx.TimeoutException:
                    saw_timeout = True
                    downloaded = None
                except Exception:
                    saw_error = True
                    downloaded = None
            else:
                try:
                    downloaded = await self._download_subtitle(ai_entry)
                except httpx.TimeoutException:
                    saw_timeout = True
                    downloaded = None
                except Exception:
                    saw_error = True
                    downloaded = None
            if downloaded is not None:
                lines, kind = downloaded
                return _ok_result(
                    "bili", kind, lines, True, available={**available, "bili": "ai"}
                )

        # 5) 空状态：给前端一个可操作的 reason
        if not online and not bili_enabled:
            reason = "disabled"
        elif saw_timeout:
            reason = "timeout"
        elif saw_error:
            reason = "error"
        elif bili_enabled and not logged_in:
            # B 站字幕开着但没登录：与安卓一致，即使在线源开着但没匹配到也提示登录
            reason = "not_logged_in"
        else:
            reason = "not_found"
        return none_result(reason, available=available)

    # ------------------------------------------------------------ 换源菜单 / 搜索 / 采用

    async def sources(
        self,
        bvid: str,
        title: str = "",
        upper: str = "",
        duration: Any = 0,
    ) -> list[dict[str, Any]]:
        """换源菜单：各来源是否可用。演示曲目不联网。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        if is_demo_bvid(bvid):
            return [
                {
                    "source": "local",
                    "available": self._local_exists(bvid),
                    "label": "本地歌词",
                },
                {"source": "bili", "available": False, "label": "B 站字幕"},
                {"source": "lrclib", "available": False, "label": "LRCLIB"},
                {"source": "demo", "available": True, "label": "演示歌词"},
            ]

        snapshot = self._snapshot(bvid)
        if snapshot is not None:
            return self._source_items(
                self._local_exists(bvid),
                str(snapshot.get("bili_kind") or ""),
                snapshot.get("bili_entry_id"),
                snapshot.get("lrclib_matched"),
                demo=False,
            )

        online = bool(config.settings.lyrics_online)
        bili_enabled = bool(config.settings.lyrics_bili)
        bili = self.bili
        logged_in = bool(
            bili is not None
            and getattr(getattr(bili, "session", None), "logged_in", False)
        )
        kind = ""
        bili_entry_id: int | None = None
        if bili_enabled and logged_in:
            try:
                bili_tracks = await self._fetch_bili_tracks(bvid)
                kind = _bili_kind(bili_tracks)
                manual_entry, ai_entry = _pick_bili_tracks(bili_tracks)
                entry = manual_entry or ai_entry
                if entry is not None:
                    bili_entry_id = int(_as_float(entry.get("id")))
            except Exception:
                kind = ""

        lrclib_item: dict[str, Any] = {
            "source": "lrclib",
            "available": False,
            "label": "LRCLIB",
        }
        if online:
            candidates = title_candidates(title, upper)
            matched = None
            if candidates:
                effective_duration = _as_float(duration)
                if effective_duration <= 0:
                    effective_duration = self._page_duration(bvid)
                try:
                    ranked = await self._search_lrclib(
                        candidates, clean_title(title), upper, effective_duration
                    )
                except Exception:
                    ranked = []
                if ranked:
                    matched = ranked[0]
            if matched is not None:
                record = matched[0]
                lrclib_item = {
                    "source": "lrclib",
                    "available": True,
                    "label": "LRCLIB",
                    "matched": {
                        "id": int(_as_float(record.get("id"))),
                        "track": str(record.get("trackName") or ""),
                        "artist": str(record.get("artistName") or ""),
                        "duration": int(_as_float(record.get("duration"))),
                    },
                }

        self._store_snapshot(
            bvid, kind, bili_entry_id, lrclib_item.get("matched")
        )
        return self._source_items(
            self._local_exists(bvid),
            kind,
            bili_entry_id,
            lrclib_item.get("matched"),
            demo=False,
        )

    @staticmethod
    def _source_items(
        local_available: bool,
        bili_kind: str,
        bili_entry_id: int | None,
        lrclib_matched: Any,
        *,
        demo: bool,
    ) -> list[dict[str, Any]]:
        label = "B 站字幕"
        if bili_kind == "manual":
            label = "B 站字幕（人工）"
        elif bili_kind == "ai":
            label = "B 站字幕（AI 转写）"
        bili_item: dict[str, Any] = {
            "source": "bili",
            "available": bool(bili_kind),
            "label": label,
        }
        if bili_kind:
            bili_item["kind"] = bili_kind
        if bili_entry_id is not None:
            bili_item["id"] = int(bili_entry_id)
        lrclib_item: dict[str, Any] = {
            "source": "lrclib",
            "available": lrclib_matched is not None,
            "label": "LRCLIB",
        }
        if lrclib_matched is not None:
            lrclib_item["matched"] = lrclib_matched
        return [
            {"source": "local", "available": local_available, "label": "本地歌词"},
            bili_item,
            lrclib_item,
            {"source": "demo", "available": demo, "label": "演示歌词"},
        ]

    async def search(self, query: str) -> list[dict[str, Any]]:
        """LRCLIB 关键词搜索，最多 10 条；任何错误都退化成空列表。"""
        keyword = str(query or "").strip()
        if not keyword or not config.settings.lyrics_online:
            return []
        try:
            records = await self._search_cached({"q": keyword})
        except Exception:
            return []
        items: list[dict[str, Any]] = []
        for record in (records or [])[:MAX_SEARCH_RESULTS]:
            if not isinstance(record, dict):
                continue
            items.append(
                {
                    "id": int(_as_float(record.get("id"))),
                    "track": str(record.get("trackName") or ""),
                    "artist": str(record.get("artistName") or ""),
                    "album": str(record.get("albumName") or ""),
                    "duration": int(_as_float(record.get("duration"))),
                    "instrumental": bool(record.get("instrumental")),
                    "synced": bool(str(record.get("syncedLyrics") or "").strip()),
                }
            )
        return items[:MAX_SEARCH_RESULTS]

    async def use_lrclib(
        self,
        bvid: str,
        lrclib_id: Any,
        title: str = "",
        upper: str = "",
        duration: Any = 0,
    ) -> dict[str, Any]:
        """采用某条 LRCLIB 候选并锁定，后续解析直接用这条。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        if not config.settings.lyrics_online:
            return none_result("disabled")
        try:
            record = await self._fetch_record(int(lrclib_id))
        except httpx.TimeoutException:
            return none_result("timeout")
        except Exception:
            return none_result("error")
        if not record:
            return none_result("not_found")

        async with self._lock_for(bvid):
            prefs = self._read_prefs(bvid)
            result = self._lrclib_result(record, _empty_available())
            if result is None:
                return none_result("not_found")
            prefs["pin"] = {
                "source": "lrclib",
                "id": int(_as_float(record.get("id"))) or int(lrclib_id),
            }
            if result.get("synced"):
                subtitle = await self._calibration_lines(bvid)
                result = self._calibrate(bvid, result, prefs, record, subtitle)
            self._write_prefs(bvid, prefs)
            result["offset"] = prefs["offset"]
            _apply_offset_source(result, prefs)
            _with_pin(result, prefs)
            self._write_cache(bvid, result, prefs)
            return result

    # ------------------------------------------------------------ 本地文件

    async def import_lrc(self, bvid: str, text: Any) -> dict[str, Any]:
        """保存用户导入的 LRC（UTF-8）并返回本地歌词结果。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        raw = str(text or "")
        data = raw.encode("utf-8")
        if len(data) > MAX_LRC_BYTES:
            raise ValueError("歌词文件超过 256 KB")
        lines, synced = parse_lrc_text(raw.lstrip("\ufeff"))
        if not lines:
            raise ValueError("歌词内容为空")
        try:
            self.lyrics_dir.mkdir(parents=True, exist_ok=True)
            target = self.lrc_path(bvid)
            tmp = target.with_name(f".{target.name}.tmp")
            tmp.write_text(raw, encoding="utf-8")
            os.replace(tmp, target)
        except OSError as exc:
            raise LyricsError("歌词保存失败") from exc
        # 本地文件优先，旧的远程缓存不再有意义
        self._delete_cache(bvid)
        prefs = self._read_prefs(bvid)
        return _apply_offset_source(
            _ok_result(
                "local",
                "",
                lines[:MAX_LRC_LINES],
                synced,
                offset=prefs["offset"],
                available={"local": True, "bili": False, "lrclib": False},
            ),
            prefs,
        )

    async def delete(self, bvid: str) -> dict[str, Any]:
        """删除本地 LRC 与缓存，不动用户偏好。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        for path in (self.lrc_path(bvid), self.cache_path(bvid)):
            try:
                path.unlink()
            except OSError:
                pass
        return {"ok": True}
