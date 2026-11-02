#!/usr/bin/env python3
"""从 assets/branding 的 SVG 母版生成桌面版和网页图标。

SVG 是唯一母版；Android 的 VectorDrawable 使用同一套 108×108 坐标，
所以两端的唱片、音符比例完全一致（详见 assets/branding/README.md）。

依赖 cairosvg：

    pip install cairosvg
    python scripts/make_icon.py

生成：
    desktop/src-tauri/icons/*.png, icon.ico
    bilimuse/web/favicon.svg, favicon.ico
"""

from __future__ import annotations

import struct
from pathlib import Path

try:
    import cairosvg
except ImportError as exc:  # pragma: no cover - 只在缺少可选依赖时触发
    raise SystemExit("缺少 cairosvg，先运行：pip install cairosvg") from exc

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / "assets" / "branding"
DESKTOP = ROOT / "desktop" / "src-tauri" / "icons"
WEB = ROOT / "bilimuse" / "web"

ICON_SVG = BRAND / "bilimuse-icon.svg"
FAVICON_SVG = BRAND / "bilimuse-favicon.svg"


def render(svg: Path, size: int) -> bytes:
    """把 SVG 渲染成指定边长的 8-bit PNG。"""
    return cairosvg.svg2png(url=str(svg), output_width=size, output_height=size)


def write_ico(path: Path, entries: list[tuple[int, bytes]]) -> None:
    """写 PNG 压缩的 ICO（Vista+ 格式），避免引入 Pillow。"""
    header = struct.pack("<HHH", 0, 1, len(entries))
    offset = 6 + 16 * len(entries)
    directory = bytearray()
    for size, png in entries:
        directory += struct.pack(
            "<BBBBHHII",
            size & 0xFF,
            size & 0xFF,
            0,  # 调色板颜色数
            0,  # 保留
            1,  # 颜色平面
            32,  # 每像素位数
            len(png),
            offset,
        )
        offset += len(png)
    path.write_bytes(header + bytes(directory) + b"".join(png for _, png in entries))


def main() -> None:
    for svg in (ICON_SVG, FAVICON_SVG):
        if not svg.exists():
            raise SystemExit(f"缺少 SVG 母版：{svg}")

    DESKTOP.mkdir(parents=True, exist_ok=True)

    # 桌面版：1024 母版 + Tauri 打包需要的各档 PNG
    (DESKTOP / "icon.png").write_bytes(render(ICON_SVG, 1024))
    for name, px in {
        "32x32.png": 32,
        "128x128.png": 128,
        "128x128@2x.png": 256,
        "256x256.png": 256,
        "512x512.png": 512,
    }.items():
        (DESKTOP / name).write_bytes(render(ICON_SVG, px))

    # Windows 图标：16/24 用去掉唱片的简化版，32 以上用完整版
    ico_entries: list[tuple[int, bytes]] = []
    for px in (16, 24):
        ico_entries.append((px, render(FAVICON_SVG, px)))
    for px in (32, 48, 64, 128, 256):
        ico_entries.append((px, render(ICON_SVG, px)))
    write_ico(DESKTOP / "icon.ico", ico_entries)

    # 网页 favicon：小尺寸同样用简化版
    WEB.mkdir(parents=True, exist_ok=True)
    (WEB / "favicon.svg").write_bytes(FAVICON_SVG.read_bytes())
    write_ico(
        WEB / "favicon.ico",
        [(px, render(FAVICON_SVG, px)) for px in (16, 32, 48)],
    )

    print(f"桌面图标：{DESKTOP}")
    print(f"网页 favicon：{WEB / 'favicon.ico'}")


if __name__ == "__main__":
    main()
