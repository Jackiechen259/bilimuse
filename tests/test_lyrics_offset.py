"""自动偏移校准纯函数测试（完全离线）。"""

from __future__ import annotations

from bilimuse.lyrics_offset import estimate_offset


def make_lines(count: int, *, spacing: float = 5.0, shift: float = 0.0):
    """生成一组时间规律、文本互不相同的歌词行。"""
    lines = []
    for index in range(count):
        text = f"第{index}句歌词内容各不相同"
        lines.append((40.0 + index * spacing + shift, text))
    return lines


def ref_lines(count: int = 24):
    return [(40.0 + index * 5.0, f"第{index}句歌词内容各不相同") for index in range(count)]


def test_estimate_offset_finds_positive_shift():
    assert estimate_offset(ref_lines(), make_lines(24, shift=3.5))[0] == 3.5


def test_estimate_offset_finds_negative_shift():
    assert estimate_offset(ref_lines(), make_lines(24, shift=-7.0))[0] == -7.0


def test_estimate_offset_finds_large_shift():
    assert estimate_offset(ref_lines(), make_lines(24, shift=29.5))[0] == 29.5


def test_estimate_offset_keeps_zero_shift():
    result = estimate_offset(ref_lines(), make_lines(24, shift=0.0))
    assert result is not None
    assert result[0] == 0.0


def test_estimate_offset_tolerates_noisy_lines():
    lines = make_lines(24, shift=4.0)
    noisy = []
    for index, (t, text) in enumerate(lines):
        noisy.append((t, text if index % 3 else f"乱码{index}x{index}"))
    result = estimate_offset(ref_lines(), noisy)
    assert result is not None
    assert result[0] == 4.0


def test_estimate_offset_requires_enough_lines():
    assert estimate_offset(ref_lines(6), make_lines(6, shift=3.0)) is None


def test_estimate_offset_rejects_unrelated_text():
    unrelated = [
        (40.0 + index * 5.0, f"完全无关的另一首歌第{index}句")
        for index in range(24)
    ]
    assert estimate_offset(ref_lines(), unrelated) is None


def test_estimate_offset_rejects_ambiguous_repeated_lines():
    # 所有行文本相同：每隔 5 秒都能满分对齐，无法确定唯一偏移
    ref = [(40.0 + index * 5.0, "重复副歌") for index in range(20)]
    cand = [(42.0 + index * 5.0, "重复副歌") for index in range(20)]
    assert estimate_offset(ref, cand) is None
