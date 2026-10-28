"""BTR 引擎测试：正确性、并发收益、断点续传。"""

from __future__ import annotations

import hashlib
import random
import time

import httpx
import pytest

from bilimuse.btr import BTRStream, SimpleStream, Source, build_stream, expand_mirrors
from mock_cdn import make_app, serve

rng = random.Random(20261028)
PAYLOAD = rng.randbytes(3 * 1024 * 1024)
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


@pytest.fixture(scope="module")
def cdn():
    server, base = serve(make_app(PAYLOAD, rate=500_000))
    yield base
    server.should_exit = True


@pytest.mark.asyncio
async def test_single_connection_is_slow(cdn):
    async with httpx.AsyncClient() as client:
        stream = SimpleStream(client, f"{cdn}/media/song.m4a", {"Accept-Encoding": "identity"})
        started = time.monotonic()
        body = b"".join([chunk async for chunk in stream.iter_range()])
        elapsed = time.monotonic() - started
    assert hashlib.sha256(body).hexdigest() == DIGEST
    # 3MB / 500KB/s ≈ 6s，单连接不可能更快
    assert elapsed > 4.0, elapsed


@pytest.mark.asyncio
async def test_btr_is_much_faster_and_byte_exact(cdn):
    async with httpx.AsyncClient() as client:
        single = SimpleStream(client, f"{cdn}/media/song.m4a", {"Accept-Encoding": "identity"})
        started = time.monotonic()
        b"".join([chunk async for chunk in single.iter_range()])
        single_time = time.monotonic() - started

        stream = await build_stream(
            client,
            f"{cdn}/media/song.m4a",
            (),
            concurrency=8,
            chunk_size=256 * 1024,
            use_mirrors=False,
        )
        assert isinstance(stream, BTRStream)
        started = time.monotonic()
        body = b"".join([chunk async for chunk in stream.iter_range()])
        btr_time = time.monotonic() - started

    assert hashlib.sha256(body).hexdigest() == DIGEST
    assert btr_time < single_time / 2.5, f"single={single_time:.2f}s btr={btr_time:.2f}s"
    assert stream.stats.chunks_done >= 12
    assert stream.snapshot()["mode"] == "btr"


@pytest.mark.asyncio
async def test_range_seek(cdn):
    async with httpx.AsyncClient() as client:
        stream = await build_stream(
            client, f"{cdn}/media/song.m4a", (), concurrency=4, use_mirrors=False
        )
        start, end = 1_000_000, 1_500_000
        body = b"".join([chunk async for chunk in stream.iter_range(start, end)])
    assert len(body) == end - start
    assert body == PAYLOAD[start:end]


@pytest.mark.asyncio
async def test_survives_broken_node(cdn):
    """一个节点每次只给 40KB 就断流，另一个正常 —— 数据仍必须完整。"""
    async with httpx.AsyncClient() as client:
        bad = Source(url=f"{cdn}/media/song.m4a?fail_after=40000")
        good = Source(url=f"{cdn}/media/song.m4a")
        stream = BTRStream(
            client,
            [bad, good],
            len(PAYLOAD),
            {"Accept-Encoding": "identity"},
            concurrency=4,
            chunk_size=128 * 1024,
            hedge_delay=0.4,
        )
        body = b"".join([chunk async for chunk in stream.iter_range()])
    assert hashlib.sha256(body).hexdigest() == DIGEST
    assert stream.stats.retries > 0


def test_expand_mirrors_keeps_path_and_query():
    url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/a/b/c.m4s?deadline=1&os=cos"
    mirrors = expand_mirrors(url, ["https://upos-sz-mirrorali.bilivideo.com/upgcxcode/a/b/c.m4s?deadline=1&os=cos"])
    hosts = [u.split("/")[2] for u in mirrors]
    assert len(mirrors) >= 5
    assert len(set(hosts)) == len(hosts)
    assert "upos-sz-mirrorcos.bilivideo.com" in hosts
    assert "upos-sz-mirrorali.bilivideo.com" in hosts
    assert all("/upgcxcode/a/b/c.m4s?deadline=1&os=cos" in u for u in mirrors)
