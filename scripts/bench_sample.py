"""在若干真实视频上抽样：单连接 vs BTR（同一区间、同一音轨）。"""

import asyncio
import hashlib
import time

import httpx

from bilimuse.api import BiliClient, pick_audio
from bilimuse.auth import Session
from bilimuse.btr import SimpleStream, build_stream

SPAN = 2 * 1024 * 1024
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com",
    "Origin": "https://www.bilibili.com",
    "Accept-Encoding": "identity",
}


async def measure(client, audio, bvid):
    single = SimpleStream(client, audio["url"], HEADERS)
    t0 = time.monotonic()
    a = b"".join([c async for c in single.iter_range(0, SPAN)])
    t1 = time.monotonic() - t0

    stream = await build_stream(client, audio["url"], audio["backup_urls"],
                                headers=HEADERS, concurrency=8, chunk_size=512 * 1024)
    t0 = time.monotonic()
    b = b"".join([c async for c in stream.iter_range(0, SPAN)])
    t2 = time.monotonic() - t0
    same = hashlib.sha256(a).digest() == hashlib.sha256(b).digest()
    print(f"{bvid}  {audio['quality']:>4}  single {t1:5.2f}s ({len(a)/t1/1024:6.0f} KB/s)  "
          f"btr {t2:5.2f}s ({len(b)/t2/1024:6.0f} KB/s)  x{t1/t2:4.2f}  "
          f"nodes={len(stream.pool)} ok={same}")


async def main() -> None:
    api = BiliClient(Session())
    pop = await api._get("https://api.bilibili.com/x/web-interface/popular", {"ps": 6, "pn": 1})
    for item in pop.get("list", [])[:6]:
        bvid = item.get("bvid")
        try:
            page = await api.page_info(bvid)
            data = await api.playurl(bvid, page["cid"])
            audio = pick_audio(data.get("dash") or {})
            async with httpx.AsyncClient(headers=HEADERS, follow_redirects=True,
                                         limits=httpx.Limits(max_connections=96)) as client:
                await measure(client, audio, bvid)
        except Exception as exc:  # noqa: BLE001
            print(f"{bvid}  跳过: {exc}")
    await api.close()


asyncio.run(main())
