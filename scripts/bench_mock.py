"""本地可复现的对比：每连接限速 500KB/s 的假 CDN 上，单连接 vs BTR。

用法：PYTHONPATH=. .venv/bin/python scripts/bench_mock.py
"""

import asyncio
import hashlib
import random
import time

import httpx

from bilimuse.btr import SimpleStream, build_stream
from tests.mock_cdn import make_app, serve

SIZE = 4 * 1024 * 1024
RATE = 500_000
PAYLOAD = random.Random(7).randbytes(SIZE)
HEADERS = {"Accept-Encoding": "identity"}


async def main() -> None:
    server, base = serve(make_app(PAYLOAD, rate=RATE))
    url = f"{base}/media/song.m4a"
    try:
        async with httpx.AsyncClient() as client:
            single = SimpleStream(client, url, HEADERS)
            t0 = time.monotonic()
            a = b"".join([c async for c in single.iter_range()])
            t1 = time.monotonic() - t0

            stream = await build_stream(client, url, (), headers=HEADERS,
                                        concurrency=8, chunk_size=512 * 1024,
                                        use_mirrors=False)
            t0 = time.monotonic()
            b = b"".join([c async for c in stream.iter_range()])
            t2 = time.monotonic() - t0
    finally:
        server.should_exit = True

    print(f"文件 {SIZE/1024/1024:.0f} MB，单连接限速 {RATE/1024:.0f} KB/s")
    print(f"单连接: {t1:.2f}s ({len(a)/t1/1024/1024:.2f} MB/s)")
    print(f"BTR   : {t2:.2f}s ({len(b)/t2/1024/1024:.2f} MB/s)，并发 {stream.concurrency}，"
          f"块 {stream.chunk_size//1024} KB，{len(stream.pool)} 节点")
    print(f"加速 {t1/t2:.1f}x，字节一致: {hashlib.sha256(a).digest() == hashlib.sha256(b).digest()}")


asyncio.run(main())
