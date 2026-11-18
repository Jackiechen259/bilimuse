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
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import httpx

from . import config
from .api import BiliClient

# ---------------------------------------------------------------- 常量

LRCLIB_API = "https://lrclib.net/api"
LRCLIB_UA = f"BiliMuse/{config.VERSION} (https://github.com/Jackiechen259/bilimuse)"

BVID_RE = re.compile(r"^[A-Za-z0-9]{1,24}$")
DEMO_BVIDS = ("DEMO0001", "DEMO0002", "DEMO0003")

MAX_LRC_BYTES = 256 * 1024
MAX_LRC_LINES = 2000
MAX_SEARCH_RESULTS = 10
MIN_AI_LINES = 4
# 远端响应体积上限（LRCLIB / B 站字幕）：4 MB，防止异常响应占满内存
MAX_REMOTE_BYTES = 4 * 1024 * 1024

CACHE_VERSION = 1
CACHE_TTL = 30 * 24 * 3600  # 命中缓存 30 天
MISS_TTL = 6 * 3600  # 未命中缓存 6 小时

MATCH_THRESHOLD = 0.62
TRACK_THRESHOLD = 0.7

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
_BOOK_RE = re.compile(r"《([^》]*)》")
_BRACKET_RE = re.compile(r"【[^】]*】|\[[^\]]*\]|（[^）]*）|\([^)]*\)|「[^」]*」|『[^』]*』")
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
    """把 B 站标题洗成适合搜曲库的文本；《》 里的歌名要保留。"""
    text = normalize_text(title)
    # 《起风了》→ 起风了（保留内容），其余括号里的通常是噪声标签，整段去掉
    text = _BOOK_RE.sub(lambda match: f" {match.group(1)} ", text)
    text = _BRACKET_RE.sub(" ", text)
    text = _strip_noise(text)
    text = re.sub(r"[ \t]+", " ", text)
    text = _SEPARATOR_RE.sub(" - ", text)
    return text.strip(" -")


def title_candidates(title: Any, upper: Any = "") -> list[tuple[str, str]]:
    """生成最多两个 (歌名, 歌手) 候选；歌手为空时由调用方用 UP 主名提示。"""
    raw = str(title or "").strip()
    if not raw:
        return []

    candidates: list[tuple[str, str]] = []

    def add(track: str, artist: str) -> None:
        track = track.strip()
        artist = artist.strip()
        if not track:
            return
        item = (track, artist)
        if item not in candidates:
            candidates.append(item)

    cleaned = clean_title(raw)
    parts = [part.strip() for part in _SEPARATOR_RE.split(cleaned) if part.strip()]
    if len(parts) >= 2:
        add(parts[0], parts[1])

    normalized = normalize_text(raw)
    book = _BOOK_RE.search(normalized)
    if book and book.group(1).strip():
        outside = clean_title(_BOOK_RE.sub(" ", normalized))
        add(book.group(1), outside)

    if not candidates and cleaned:
        add(cleaned, "")

    return candidates[:2]


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


def artist_score(candidate: Any, actual: Any) -> float:
    """歌手：与候选或 UP 主归一化后相等 0.9、包含 0.5，否则 0。"""
    candidate_text = normalize_text(candidate)
    actual_text = normalize_text(actual)
    if not candidate_text or not actual_text:
        return 0.0
    if candidate_text == actual_text:
        return 0.9
    if candidate_text in actual_text or actual_text in candidate_text:
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


def pick_match(
    records: Iterable[Any],
    candidates: list[tuple[str, str]],
    upper: Any = "",
    video_duration: Any = 0,
) -> tuple[dict[str, Any], float] | None:
    """从一批 LRCLIB 结果里挑最可信的一条；同一分数取时长更接近的。"""
    best: tuple[dict[str, Any], float] | None = None
    best_score = 0.0
    best_diff = float("inf")
    for record in records or []:
        if not isinstance(record, dict) or record.get("instrumental"):
            continue
        for track, artist in candidates:
            track_part = similarity(record.get("trackName"), track)
            if track_part < TRACK_THRESHOLD:
                continue
            # 打分只保留一份实现：pick_match 直接复用 score_record
            score = score_record(record, track, artist, upper, video_duration)
            if score < MATCH_THRESHOLD:
                continue
            diff = _duration_diff(record.get("duration"), video_duration)
            if score > best_score + 1e-9 or (
                abs(score - best_score) <= 1e-9 and diff < best_diff
            ):
                best = (record, score)
                best_score = score
                best_diff = diff
    return best


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
        return {"offset": 0.0, "pin": None}

    def _read_prefs(self, bvid: str) -> dict[str, Any]:
        prefs = self._default_prefs()
        try:
            raw = json.loads(self.prefs_path(bvid).read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            return prefs
        if not isinstance(raw, dict):
            return prefs
        prefs["offset"] = clamp_offset(raw.get("offset", 0.0))
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
        pin_source: Any = _UNSET,
        pin_id: Any = _UNSET,
    ) -> dict[str, Any]:
        """更新并持久化偏好；传 None 表示清除，未传的字段保持不变。"""
        if not is_valid_bvid(bvid):
            raise ValueError("bvid 不合法")
        prefs = self._read_prefs(bvid)
        if offset is not _UNSET:
            prefs["offset"] = 0.0 if offset is None else clamp_offset(offset)

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
        bili = self.bili
        if bili is None:
            return []
        page = await bili.page_info(bvid)
        cid = int(page.get("cid") or 0) if isinstance(page, dict) else 0
        if not cid:
            return []
        return await bili.subtitle_tracks(bvid, cid)

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

    async def _search_lrclib(
        self,
        candidates: list[tuple[str, str]],
        cleaned_title: str,
        upper: str,
        video_duration: float,
    ) -> tuple[dict[str, Any], float] | None:
        plan: list[dict[str, str]] = []
        for track, artist in candidates:
            params: dict[str, str] = {"track_name": track}
            if artist:
                params["artist_name"] = artist
            plan.append(params)
        if cleaned_title:
            plan.append({"q": cleaned_title})
        for track, _artist in candidates:
            plan.append({"track_name": track})

        seen: set[tuple[tuple[str, str], ...]] = set()
        for params in plan:
            key = tuple(sorted(params.items()))
            if key in seen:
                continue
            seen.add(key)
            records = await self._fetch_search(params)
            matched = pick_match(records, candidates, upper, video_duration)
            if matched is not None:
                return matched
        return None

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
                return _with_pin(
                    _ok_result(
                        "local",
                        "",
                        lines,
                        synced,
                        offset=prefs["offset"],
                        available={"local": True, "bili": False, "lrclib": False},
                    ),
                    prefs,
                )
            return _with_pin(
                _ok_result(
                    "demo",
                    "",
                    demo_lines(),
                    True,
                    offset=prefs["offset"],
                    available=_empty_available(),
                ),
                prefs,
            )
        async with self._lock_for(bvid):
            return await self._resolve_locked(
                bvid, str(title or ""), str(upper or ""), _as_float(duration), bool(refresh)
            )

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
                _ok_result(
                    "local",
                    "",
                    lines,
                    synced,
                    offset=prefs["offset"],
                    available={"local": True, "bili": False, "lrclib": False},
                ),
                prefs,
            )

        # 有 pin 时跳过缓存读取：pin 源可能只是瞬时失败，不能被降级缓存挡住重试
        if not refresh and not prefs.get("pin"):
            cached = self._read_cache(bvid, prefs)
            if cached is not None:
                return _with_pin(
                    {
                        **cached,
                        "offset": prefs["offset"],
                        "lines": [dict(line) for line in cached.get("lines") or []],
                        "meta": {**(cached.get("meta") or {}), "cached": True},
                    },
                    prefs,
                )

        result = await self._ladder(bvid, title, upper, duration, prefs)
        result["offset"] = prefs["offset"]
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

    async def _ladder(
        self,
        bvid: str,
        title: str,
        upper: str,
        duration: float,
        prefs: dict[str, Any],
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
                try:
                    record = await self._fetch_record(int(pin_id))
                except httpx.TimeoutException:
                    saw_timeout = True
                    record = None
                except Exception:
                    saw_error = True
                    record = None
                if record:
                    pinned = self._lrclib_result(record, available)
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

        # 3) LRCLIB
        if online:
            candidates = title_candidates(title, upper)
            if candidates:
                try:
                    matched = await self._search_lrclib(
                        candidates, clean_title(title), upper, duration
                    )
                except httpx.TimeoutException:
                    saw_timeout = True
                    matched = None
                except Exception:
                    saw_error = True
                    matched = None
                if matched is not None:
                    result = self._lrclib_result(matched[0], available)
                    if result is not None:
                        return result

        # 4) B 站 AI 字幕兜底
        if ai_entry is not None:
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
        local_item = {
            "source": "local",
            "available": self._local_exists(bvid),
            "label": "本地歌词",
        }
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

        online = bool(config.settings.lyrics_online)
        bili_enabled = bool(config.settings.lyrics_bili)
        bili = self.bili
        logged_in = bool(
            bili is not None
            and getattr(getattr(bili, "session", None), "logged_in", False)
        )
        kind = ""
        bili_entry: dict[str, Any] | None = None
        if bili_enabled and logged_in:
            try:
                bili_tracks = await self._fetch_bili_tracks(bvid)
                kind = _bili_kind(bili_tracks)
                manual_entry, ai_entry = _pick_bili_tracks(bili_tracks)
                bili_entry = manual_entry or ai_entry
            except Exception:
                kind = ""
        label = "B 站字幕"
        if kind == "manual":
            label = "B 站字幕（人工）"
        elif kind == "ai":
            label = "B 站字幕（AI 转写）"
        bili_item: dict[str, Any] = {
            "source": "bili",
            "available": bool(kind),
            "label": label,
        }
        if kind:
            bili_item["kind"] = kind
        if bili_entry is not None:
            # 给客户端一个可锁定的具体轨道；不传也能只按 source 锁来源。
            bili_item["id"] = int(_as_float(bili_entry.get("id")))

        lrclib_item: dict[str, Any] = {
            "source": "lrclib",
            "available": False,
            "label": "LRCLIB",
        }
        if online:
            candidates = title_candidates(title, upper)
            matched = None
            if candidates:
                try:
                    matched = await self._search_lrclib(
                        candidates, clean_title(title), upper, _as_float(duration)
                    )
                except Exception:
                    matched = None
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
                    },
                }

        return [
            local_item,
            bili_item,
            lrclib_item,
            {"source": "demo", "available": False, "label": "演示歌词"},
        ]

    async def search(self, query: str) -> list[dict[str, Any]]:
        """LRCLIB 关键词搜索，最多 10 条；任何错误都退化成空列表。"""
        keyword = str(query or "").strip()
        if not keyword or not config.settings.lyrics_online:
            return []
        try:
            records = await self._fetch_search({"q": keyword})
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
            self._write_prefs(bvid, prefs)
            result["offset"] = prefs["offset"]
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
        return _ok_result(
            "local",
            "",
            lines[:MAX_LRC_LINES],
            synced,
            offset=prefs["offset"],
            available={"local": True, "bili": False, "lrclib": False},
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
