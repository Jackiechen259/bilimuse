"""演示模式的封面。

演示模式的音频是本地合成的，没有真实视频，也就没有可引用的 B 站封面。
这里用内联 SVG 画三张 16:9 的「视频缩略图」：不联网、不额外打包图片文件，
同时让列表、底部播放器和播放详情页在演示模式下也能看到完整的封面效果。

B 站图片 CDN 会拒绝来自 127.0.0.1 的 Referer；页面已用
`<meta name="referrer" content="no-referrer">` 统一去掉，真实封面同理。
"""

from __future__ import annotations

import base64


def _data_uri(svg: str) -> str:
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode("utf-8")).decode("ascii")


NIGHT_FLIGHT = _data_uri("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 450">
  <defs>
    <linearGradient id="night-sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#120a24"/>
      <stop offset=".56" stop-color="#251243"/>
      <stop offset="1" stop-color="#070b17"/>
    </linearGradient>
    <radialGradient id="night-moon" cx=".5" cy=".4" r=".6">
      <stop offset="0" stop-color="#ffe6f0"/>
      <stop offset=".52" stop-color="#fb7299"/>
      <stop offset="1" stop-color="#9b5de5"/>
    </radialGradient>
  </defs>
  <rect width="800" height="450" fill="url(#night-sky)"/>
  <g fill="#fff">
    <circle cx="96" cy="74" r="1.8" opacity=".65"/>
    <circle cx="168" cy="132" r="1.2" opacity=".5"/>
    <circle cx="620" cy="96" r="1.5" opacity=".6"/>
    <circle cx="704" cy="180" r="1.1" opacity=".45"/>
    <circle cx="258" cy="62" r="1" opacity=".5"/>
    <circle cx="552" cy="56" r="1.3" opacity=".55"/>
    <circle cx="740" cy="52" r="1" opacity=".4"/>
    <circle cx="58" cy="210" r="1.1" opacity=".35"/>
  </g>
  <circle cx="400" cy="182" r="150" fill="none" stroke="#00aeec" stroke-opacity=".12"/>
  <circle cx="400" cy="182" r="120" fill="none" stroke="#fb7299" stroke-opacity=".2"/>
  <circle cx="400" cy="182" r="92" fill="url(#night-moon)"/>
  <path d="M0 334C150 306 250 342 400 326S650 306 800 334V450H0Z" fill="#070b16"/>
  <path d="M0 352C170 328 290 362 450 344S690 336 800 356" fill="none" stroke="#00aeec" stroke-opacity=".22"/>
  <g transform="translate(512 248)">
    <path d="M0 0 54-16 22 6 30 24 14 8 0 12Z" fill="#f7f8ff" opacity=".9"/>
    <path d="M-78 34C-44 18-20 12 4 14" fill="none" stroke="#fb7299" stroke-opacity=".5" stroke-dasharray="3 8"/>
  </g>
  <text x="400" y="410" text-anchor="middle" font-family="Segoe UI,Helvetica,Arial,sans-serif"
    font-size="23" letter-spacing="12" fill="#f2e9ff" opacity=".8">NIGHT FLIGHT</text>
</svg>""")


NEON_CITY = _data_uri("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 450">
  <defs>
    <linearGradient id="neon-sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#080a1e"/>
      <stop offset=".42" stop-color="#2c1348"/>
      <stop offset=".7" stop-color="#7a2a63"/>
      <stop offset="1" stop-color="#161027"/>
    </linearGradient>
    <linearGradient id="neon-sun" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffd6b0"/>
      <stop offset=".45" stop-color="#fb7299"/>
      <stop offset="1" stop-color="#b45ee6"/>
    </linearGradient>
    <linearGradient id="neon-fade" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#0b0a1a" stop-opacity="0"/>
      <stop offset="1" stop-color="#080a16" stop-opacity=".9"/>
    </linearGradient>
  </defs>
  <rect width="800" height="450" fill="url(#neon-sky)"/>
  <circle cx="400" cy="206" r="118" fill="url(#neon-sun)"/>
  <g fill="#1b0b2a">
    <rect x="282" y="212" width="236" height="6"/>
    <rect x="282" y="228" width="236" height="8"/>
    <rect x="282" y="248" width="236" height="10"/>
    <rect x="282" y="270" width="236" height="12"/>
    <rect x="282" y="294" width="236" height="14"/>
  </g>
  <path d="M0 324H800V450H0Z" fill="#0a0918"/>
  <g stroke="#00aeec" stroke-opacity=".38" stroke-width="1">
    <path d="M0 324H800"/>
    <path d="M400 324 60 450M400 324 160 450M400 324 260 450M400 324 340 450M400 324 400 450M400 324 460 450M400 324 540 450M400 324 640 450M400 324 740 450"/>
    <path d="M0 344H800M0 370H800M0 402H800"/>
  </g>
  <rect width="800" height="450" fill="url(#neon-fade)"/>
  <text x="400" y="410" text-anchor="middle" font-family="Segoe UI,Helvetica,Arial,sans-serif"
    font-size="23" letter-spacing="12" fill="#ffe9f4" opacity=".85">NEON CITY</text>
</svg>""")


SEA_SIGNAL = _data_uri("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 450">
  <defs>
    <linearGradient id="sea-sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#04121c"/>
      <stop offset=".55" stop-color="#0b2b3b"/>
      <stop offset="1" stop-color="#061019"/>
    </linearGradient>
    <linearGradient id="sea-water" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#0b3a4c"/>
      <stop offset="1" stop-color="#04111b"/>
    </linearGradient>
    <radialGradient id="sea-moon" cx=".5" cy=".5" r=".5">
      <stop offset="0" stop-color="#e6fbff"/>
      <stop offset="1" stop-color="#7cd7f2"/>
    </radialGradient>
  </defs>
  <rect width="800" height="450" fill="url(#sea-sky)"/>
  <circle cx="400" cy="140" r="62" fill="url(#sea-moon)" opacity=".95"/>
  <circle cx="400" cy="140" r="98" fill="none" stroke="#7cd7f2" stroke-opacity=".16"/>
  <path d="M0 300H800V450H0Z" fill="url(#sea-water)"/>
  <g fill="none" stroke="#7cd7f2" stroke-opacity=".22">
    <path d="M0 318C120 306 210 330 320 318S560 306 800 322"/>
    <path d="M0 346C140 334 250 358 380 344S620 334 800 352"/>
    <path d="M0 382C160 368 280 392 420 378S660 372 800 388"/>
  </g>
  <g transform="translate(548 210)">
    <path d="M-10 96H10L6 0H-6Z" fill="#0a1a24" stroke="#7cd7f2" stroke-opacity=".4"/>
    <circle cx="0" cy="0" r="5" fill="#fb7299"/>
    <g fill="none" stroke="#fb7299" stroke-opacity=".4">
      <path d="M-20-14A28 28 0 0 1 20-14"/>
      <path d="M-38-28A54 54 0 0 1 38-28"/>
      <path d="M-56-42A80 80 0 0 1 56-42"/>
    </g>
  </g>
  <path d="M0 300C140 288 260 312 400 300S660 288 800 302" fill="none" stroke="#7cd7f2" stroke-opacity=".3"/>
  <text x="400" y="414" text-anchor="middle" font-family="Segoe UI,Helvetica,Arial,sans-serif"
    font-size="23" letter-spacing="12" fill="#e4fbff" opacity=".82">SEA SIGNAL</text>
</svg>""")


__all__ = ["NIGHT_FLIGHT", "NEON_CITY", "SEA_SIGNAL"]
