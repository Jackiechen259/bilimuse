"""起播延迟对比：9 个候选节点里 8 个"连得上但不回包"，等全部测速 vs 抢跑。

用法：PYTHONPATH=. .venv/bin/python scripts/bench_probe.py
"""

import asyncio
import time

import httpx

from bilimuse.btr import build_stream
from tests.mock_cdn import Blackhole, make_app, serve

PAYLOAD = bytes(2 * 1024 * 1024)


async def main() -> None:
    server, base = serve(make_app(PAYLOAD, rate=500_000, ttfb=0.05))
    holes = [Blackhole() for _ in range(8)]
    good = f"{base}/media/song.m4a"
    dead = [h.url for h in holes]
    try:
        async with httpx.AsyncClient() as client:
            for eager in (False, True):
                started = time.monotonic()
                stream = await build_stream(
                    client, good, dead, concurrency=8, use_mirrors=False, eager=eager
                )
                elapsed = time.monotonic() - started
                print(f"{'抢跑' if eager else '等全部测完'}: 起播等待 {elapsed * 1000:7.1f} ms"
                      f"（节点池 {len(stream.pool)}）")
    finally:
        for hole in holes:
            hole.close()
        server.should_exit = True


asyncio.run(main())
