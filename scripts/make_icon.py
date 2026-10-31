#!/usr/bin/env python3
"""生成桌面版图标（不依赖任何外部素材）。

输出到 desktop/src-tauri/icons/：Tauri 打包需要的各档 PNG + Windows ico。
只用到 Pillow，系统 Python 一般都有；没有就先 `pip install pillow`。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 1024
SS = 4  # 超采样倍数，先画大图再缩放，边缘才不毛糙
OUT = Path(__file__).resolve().parent.parent / "desktop" / "src-tauri" / "icons"

BG_TOP = (22, 27, 45)
BG_BOTTOM = (9, 11, 19)
DISC = (17, 20, 32)
RING = (38, 44, 66)
ACCENT = (126, 156, 255)
ACCENT_2 = (167, 139, 250)


def _rounded_gradient(size: int, radius: int) -> Image.Image:
    """竖向渐变的圆角矩形。"""
    img = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        color = tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM))
        draw.line([(0, y), (size, y)], fill=color + (255,))

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([(0, 0), (size - 1, size - 1)], radius=radius, fill=255)
    img.putalpha(mask)
    return img


def _circle(draw: ImageDraw.ImageDraw, cx: int, cy: int, r: int, **kwargs) -> None:
    draw.ellipse([(cx - r, cy - r), (cx + r, cy + r)], **kwargs)


def render() -> Image.Image:
    size = SIZE * SS
    img = _rounded_gradient(size, 224 * SS)
    draw = ImageDraw.Draw(img, "RGBA")
    center = size // 2
    disc_cy = int(size * 0.56)
    disc_r = int(size * 0.30)

    # 背后的一点光晕：用径向渐变一次合成，逐圈叠加会糊成一圈亮边
    glow_px = int(disc_r * 2.8)
    gradient = Image.radial_gradient("L").resize((glow_px, glow_px), Image.BICUBIC)
    gradient = Image.eval(gradient, lambda v: int(max(0.0, 1.0 - v / 255.0) ** 2 * 90))
    glow = Image.new("RGBA", (glow_px, glow_px), ACCENT + (0,))
    glow.putalpha(gradient)
    img.alpha_composite(glow, (center - glow_px // 2, disc_cy - glow_px // 2))

    # 唱片
    _circle(draw, center, disc_cy, disc_r, fill=DISC + (255,))
    draw.ellipse(
        [(center - disc_r, disc_cy - disc_r), (center + disc_r, disc_cy + disc_r)],
        outline=RING + (255,),
        width=3 * SS,
    )
    for k in range(1, 5):
        rr = int(disc_r * (0.72 + k * 0.055))
        _circle(draw, center, disc_cy, rr, outline=RING + (120 - k * 18,), width=2 * SS)
    _circle(draw, center, disc_cy, int(disc_r * 0.085), fill=(10, 12, 20, 255))
    _circle(draw, center, disc_cy, int(disc_r * 0.03), fill=ACCENT + (255,))

    # 音符：两个符头 + 两根符干 + 梁
    h1 = (int(center - size * 0.088), int(size * 0.66))
    h2 = (int(center + size * 0.100), int(size * 0.60))
    head_r = int(size * 0.055)
    stem_w = int(size * 0.022)
    stem_top = int(size * 0.30)

    draw.rounded_rectangle(
        [(h1[0] + head_r - stem_w // 2, stem_top), (h1[0] + head_r + stem_w // 2, h1[1])],
        radius=stem_w // 2,
        fill=ACCENT + (255,),
    )
    draw.rounded_rectangle(
        [(h2[0] + head_r - stem_w // 2, stem_top - size // 60), (h2[0] + head_r + stem_w // 2, h2[1])],
        radius=stem_w // 2,
        fill=ACCENT_2 + (255,),
    )
    draw.polygon(
        [
            (h1[0] + head_r - stem_w // 2, stem_top),
            (h1[0] + head_r + stem_w // 2, stem_top),
            (h2[0] + head_r + stem_w // 2, stem_top - size // 60),
            (h2[0] + head_r - stem_w // 2, stem_top - size // 60),
        ],
        fill=ACCENT_2 + (255,),
    )
    # 符头稍微转一点角度，看着像手写谱
    for (cx, cy), color in ((h1, ACCENT), (h2, ACCENT_2)):
        head = Image.new("RGBA", (head_r * 3, head_r * 3), (0, 0, 0, 0))
        hd = ImageDraw.Draw(head)
        hd.ellipse(
            [
                (head.width // 2 - head_r, head.height // 2 - int(head_r * 0.78)),
                (head.width // 2 + head_r, head.height // 2 + int(head_r * 0.78)),
            ],
            fill=color + (255,),
        )
        head = head.rotate(-18, resample=Image.BICUBIC, expand=False)
        img.alpha_composite(head, (cx - head.width // 2, cy - head.height // 2))

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    master = render()
    master.save(OUT / "icon.png")

    sizes = {"32x32.png": 32, "128x128.png": 128, "128x128@2x.png": 256, "256x256.png": 256, "512x512.png": 512}
    for name, px in sizes.items():
        master.resize((px, px), Image.LANCZOS).save(OUT / name)

    master.resize((256, 256), Image.LANCZOS).save(
        OUT / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    )
    print(f"图标已生成：{OUT}")


if __name__ == "__main__":
    main()
