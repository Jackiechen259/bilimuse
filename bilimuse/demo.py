"""演示模式：本地生成几段音频，用同一条 BTR 管线播放。

用途：没登录也能跑通 UI 和引擎；也让"单连接 vs 多连接"的效果肉眼可见。
"""

from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 44100
DURATION = 45  # 秒
DEMO_RATE = 320_000  # 每连接限速，字节/秒（制造出"单线程很慢"的真实感）

DEMO_FOLDERS = [
    {"id": -1, "title": "演示 · 合成器浪潮", "count": 3, "fav_state": 1},
]

DEMO_TRACKS = [
    {
        "bvid": "DEMO0001",
        "title": "夜航星 (Demo)",
        "upper": "BiliMuse",
        "duration": DURATION,
        "cover": "",
        "intro": "多线程加载演示：45 秒合成的琶音音轨",
        "ctime": 0,
        "attr": 0,
        "file": "night_flight.wav",
        "root": 220.0,
    },
    {
        "bvid": "DEMO0002",
        "title": "城市霓虹 (Demo)",
        "upper": "BiliMuse",
        "duration": DURATION,
        "cover": "",
        "intro": "低频铺底 + 和弦，用于测试进度条拖动（Range 请求）",
        "ctime": 0,
        "attr": 0,
        "file": "neon_city.wav",
        "root": 164.81,
    },
    {
        "bvid": "DEMO0003",
        "title": "海边的信号 (Demo)",
        "upper": "BiliMuse",
        "duration": DURATION,
        "cover": "",
        "intro": "缓慢的环境音，测试连续播放",
        "ctime": 0,
        "attr": 0,
        "file": "sea_signal.wav",
        "root": 196.0,
    },
]


def _write_tone(path: Path, root: float) -> None:
    """合成一段和弦琶音，够用来验证播放链路。

    先算 4 秒的循环，再按字节重复拼到全长 —— 纯 Python 逐样本算 45 秒
    要几十秒，没人愿意等这个。
    """
    loop_seconds = 4
    loop_frames = SAMPLE_RATE * loop_seconds
    chords = [[1.0, 1.25, 1.5], [1.0, 1.2, 1.5], [1.0, 1.33, 1.66], [1.0, 1.25, 1.6]]
    frames = bytearray()
    for i in range(loop_frames):
        t = i / SAMPLE_RATE
        bar = int(t / 2) % len(chords)
        note = int(t * 4) % 3
        freq = root * chords[bar][note]
        env = min(1.0, (t % 0.25) * 8) * math.exp(-(t % 0.25) * 3)
        pad = math.sin(2 * math.pi * root * 0.5 * t) * 0.12
        sample = (math.sin(2 * math.pi * freq * t) * 0.35 +
                  math.sin(2 * math.pi * freq * 2 * t) * 0.08) * env + pad
        sample = max(-1.0, min(1.0, sample))
        frames += struct.pack("<h", int(sample * 26000))

    repeats = max(1, DURATION // loop_seconds)
    body = bytes(frames) * repeats
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(body)


def ensure_audio(cache_dir: Path) -> dict[str, Path]:
    """生成（或复用）演示音频，返回 bvid -> 文件路径。"""
    out_dir = cache_dir / "demo"
    out_dir.mkdir(parents=True, exist_ok=True)
    mapping: dict[str, Path] = {}
    for track in DEMO_TRACKS:
        path = out_dir / track["file"]
        if not path.exists() or path.stat().st_size < 1000:
            _write_tone(path, float(track["root"]))
        mapping[track["bvid"]] = path
    return mapping
