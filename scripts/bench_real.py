"""在真实 B 站 CDN 上对比：单连接 vs BTR 8 并发（同一首歌、同一区间）。"""

import asyncio
import hashlib
import time

import httpx

from bilimuse.api import BiliClient, pick_audio
from bilimuse.auth import Session
from bilimuse.btr import SimpleStream, build_stream

BVID = "BV1xx411c7mD"
SPAN = 4 * 1024 * 1024
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com",
    "Origin": "https://www.bilibili.com",
    "Accept-Encoding": "identity",
}


async def main() -> None:
    api = BiliClient(Session())
    page = await api.page_info(BVID)
    data = await api.playurl(BVID, page["cid"])
    audio = pick_audio(data.get("dash") or {})
    print(f"音轨: {audio['quality']} {audio['codecs']} 备份节点={len(audio['backup_urls'])}")

    async with httpx.AsyncClient(headers=HEADERS, follow_redirects=True,
                                 limits=httpx.Limits(max_connections=96)) as client:
        t0 = time.monotonic()
        single = SimpleStream(client, audio["url"], HEADERS)
        body1 = b"".join([c async for c in single.iter_range(0, SPAN)])
        t1 = time.monotonic() - t0

        stream = await build_stream(client, audio["url"], audio["backup_urls"],
                                    headers=HEADERS, concurrency=8,
                                    chunk_size=512 * 1024)
        t0 = time.monotonic()
        body2 = b"".join([c async for c in stream.iter_range(0, SPAN)])
        t2 = time.monotonic() - t0

    h1, h2 = hashlib.sha256(body1).hexdigest(), hashlib.sha256(body2).hexdigest()
    print(f"单连接: {t1:5.2f}s  {len(body1)/t1/1024/1024:5.2f} MB/s")
    print(f"BTR   : {t2:5.2f}s  {len(body2)/t2/1024/1024:5.2f} MB/s  "
          f"({len(stream.pool)} 节点, {stream.stats.hedges} 次对冲)")
    print(f"加速比: {t1/t2:.2f}x  数据一致: {h1 == h2}")
    print("节点:", [(n['host'], round(n['speed']/1024), n['errors'])
                    for n in stream.snapshot()["nodes"]])
    await api.close()


asyncio.run(main())
