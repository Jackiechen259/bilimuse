"""Flat Press 设计令牌的回归测试。

不依赖浏览器：解析 style.css 的三套主题令牌，检查关键对比度与禁用样式；
同时确认桌面启动页的 ink 副本没有漂移。
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STYLE = ROOT / "bilimuse" / "web" / "style.css"
DESKTOP = ROOT / "desktop" / "web" / "index.html"

REQUIRED = {
    "bg", "bg-elev", "bg-elev-2", "sidebar-bg", "text", "text-2", "muted",
    "faint", "line", "line-soft", "line-strong", "track", "accent",
    "accent-strong", "accent-text", "on-accent", "danger", "selected",
}
FORBIDDEN = (
    "backdrop-filter",
    "filter: blur",
    "drop-shadow",
    "linear-gradient",
    "radial-gradient",
    "box-shadow",
)


def _hex(value: str) -> tuple[float, float, float]:
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def _luminance(color: str) -> float:
    def channel(c: float) -> float:
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(c) for c in _hex(color))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _contrast(a: str, b: str) -> float:
    la, lb = _luminance(a), _luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def _block(css: str, selector: str) -> str:
    start = css.index(selector)
    end = css.index("}", start)
    return css[start:end]


def _tokens(block: str) -> dict[str, str]:
    return dict(re.findall(r"--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;", block))


def test_three_theme_tokens_and_contrast() -> None:
    css = STYLE.read_text(encoding="utf-8")
    blocks = {
        "ink": _tokens(_block(css, ":root {")),
        "paper": _tokens(_block(css, 'html[data-theme="paper"] {')),
        "cream": _tokens(_block(css, 'html[data-theme="cream"] {')),
    }

    for name, tokens in blocks.items():
        missing = REQUIRED - tokens.keys()
        assert not missing, f"{name} 缺少令牌: {sorted(missing)}"
        assert tokens["accent"] == "#fb7299"
        assert _contrast(tokens["accent-text"], tokens["bg"]) >= 4.5, name
        assert _contrast(tokens["on-accent"], tokens["accent"]) >= 4.5, name
        assert _contrast(tokens["accent-strong"], tokens["track"]) >= 3.0, name
        assert _contrast(tokens["text"], tokens["bg"]) >= 7.0, name
        assert _contrast(tokens["muted"], tokens["bg"]) >= 4.5, name


def test_no_decorative_styles() -> None:
    css = STYLE.read_text(encoding="utf-8")
    for pattern in FORBIDDEN:
        assert pattern not in css, f"style.css 仍包含 {pattern}"


def test_desktop_splash_matches_ink() -> None:
    css = STYLE.read_text(encoding="utf-8")
    ink = _tokens(_block(css, ":root {"))
    splash = DESKTOP.read_text(encoding="utf-8")
    splash_tokens = dict(re.findall(r"--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;", splash))

    assert splash_tokens["bg"] == ink["bg"]
    assert splash_tokens["surface"] == ink["bg-elev"]
    assert splash_tokens["line"] == ink["line"]
    assert splash_tokens["text"] == ink["text"]
    assert splash_tokens["muted"] == ink["muted"]
    assert splash_tokens["accent"] == ink["accent"]

    for pattern in FORBIDDEN:
        assert pattern not in splash, f"启动页仍包含 {pattern}"
