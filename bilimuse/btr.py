"""BTR 多线程流式引擎（Bilibili-Thread-Ripper 风格）。

核心思路
--------
B 站播放器对一段音频只开一条连接、连一个节点。海外或冷门视频上这条连接
常常只有几百 KB/s，边下边播就会卡。BTR 做三件事：

1. 把整段音频切成固定大小的块，多块并发下载（可跨多个 CDN 节点）；
2. 乱序到达的块在内存里排队，按偏移量顺序吐出，交给浏览器/播放器；
3. 对迟迟不回、或明显慢于中位数的块做"对冲"：另找一个节点重发同一段，
   谁先到用谁，剩下的取消；某块中途断流则从断点续传，不整块重来。

与普通多线程下载器的区别：这里的目标不是"尽快下完整个文件"，而是让
播放位置之后的数据尽快就绪 —— 所以只预取一个窗口，块越小、并发越高，
起播越快，但请求开销也越大。默认 512KB / 8 并发。
"""

from __future__ import annotations

import asyncio
import math
import random
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import AsyncIterator, Iterable

import httpx

# 已知可互换的 B 站媒体节点（path/query 相同，只换 host）
MIRROR_HOSTS = [
    "upos-sz-mirrorcos.bilivideo.com",
    "upos-sz-mirrorali.bilivideo.com",
    "upos-sz-mirrorhw.bilivideo.com",
    "upos-sz-mirrorakam.bilivideo.com",
    "upos-sz-mirrorcoso1.bilivideo.com",
    "upos-sz-mirroraliov.bilivideo.com",
    "upos-sz-mirror08c.bilivideo.com",
    "upos-hz-mirrorakam.akamaized.net",
]


class StreamError(RuntimeError):
    pass


def expand_mirrors(url: str, backups: Iterable[str] = ()) -> list[str]:
    """从一条 playurl 拼出候选节点列表（备份地址 + 已知镜像 host 互换）。"""
    parsed = urllib.parse.urlsplit(url)
    hosts: list[str] = []
    for candidate in list(backups) + [url]:
        host = urllib.parse.urlsplit(candidate).netloc
        if host and host not in hosts:
            hosts.append(host)
    for host in MIRROR_HOSTS:
        if host not in hosts:
            hosts.append(host)

    out: list[str] = []
    for host in hosts:
        out.append(urllib.parse.urlunsplit((parsed.scheme, host, parsed.path, parsed.query, "")))
    return out


@dataclass
class Source:
    url: str
    label: str = ""
    speed: float = 0.0          # EWMA 字节/秒
    ttfb: float = 0.5           # 首字节延迟（秒）
    ok_bytes: int = 0
    errors: int = 0

    def __post_init__(self) -> None:
        if not self.label:
            self.label = urllib.parse.urlsplit(self.url).netloc

    @property
    def score(self) -> float:
        # 速度为主，延迟做轻微惩罚；出错的节点降权
        return self.speed / (1.0 + self.ttfb) / (1.0 + self.errors)


class CdnPool:
    """按实时测速排序的节点池。"""

    def __init__(self, sources: list[Source]):
        self.sources = sources
        self._rr = 0

    def __len__(self) -> int:
        return len(self.sources)

    def pick(self, exclude: Iterable[str] = ()) -> Source | None:
        if not self.sources:
            return None
        excluded = set(exclude)
        alive = [s for s in self.sources if s.url not in excluded and s.errors < 4]
        if not alive:
            alive = [s for s in self.sources if s.url not in excluded]
        if not alive:
            alive = list(self.sources)
            for s in alive:
                s.errors = 0
        # 慢节点会拖住整条流水线：低于最快节点 60% 的直接排除
        if len(alive) > 1:
            best = max(s.speed for s in alive)
            fast = [s for s in alive if s.speed >= best * 0.6]
            if fast:
                alive = fast
        if len(alive) == 1:
            return alive[0]
        # 按速度平方加权：谁快谁多拿块，但保留少量探索，防止"burst 后限速"的节点被永久选中
        weights = [max(s.speed, 1.0) ** 2 for s in alive]
        total = sum(weights)
        roll = random.random() * total
        upto = 0.0
        for src, weight in zip(alive, weights):
            upto += weight
            if roll <= upto:
                return src
        return alive[-1]

    def best_speed(self) -> float:
        return max((s.speed for s in self.sources), default=0.0)

    def report_ok(self, src: Source, nbytes: int, elapsed: float) -> None:
        if nbytes <= 0 or elapsed <= 0:
            return
        inst = nbytes / elapsed
        src.speed = inst if src.speed <= 0 else src.speed * 0.7 + inst * 0.3
        src.ttfb = src.ttfb * 0.8 + min(elapsed, 5.0) * 0.2
        src.ok_bytes += nbytes

    def report_error(self, src: Source) -> None:
        # 出过错就一直在降权/隔离，别让它再进主循环
        src.errors += 1
        src.speed *= 0.5


@dataclass
class Stats:
    chunks_done: int = 0
    bytes_done: int = 0
    retries: int = 0
    hedges: int = 0
    started: float = field(default_factory=time.monotonic)
    bytes_served: int = 0
    _prev_bytes: int = 0
    _prev_time: float = field(default_factory=time.monotonic)

    @property
    def elapsed(self) -> float:
        return max(time.monotonic() - self.started, 1e-6)

    @property
    def speed(self) -> float:
        return self.bytes_done / self.elapsed

    def sample_speed(self) -> float:
        """两次采样之间的瞬时速度（下载完成后会自然降到 0）。"""
        now = time.monotonic()
        delta_t = now - self._prev_time
        if delta_t <= 0:
            return 0.0
        rate = (self.bytes_done - self._prev_bytes) / delta_t
        self._prev_bytes, self._prev_time = self.bytes_done, now
        return max(0.0, rate)


class BTRStream:
    """一段可 Random-Access 的远程音频，内部多连接并发拉取。"""

    def __init__(
        self,
        client: httpx.AsyncClient,
        sources: list[Source],
        total_size: int,
        headers: dict[str, str],
        *,
        concurrency: int = 8,
        chunk_size: int = 512 * 1024,
        prefetch_chunks: int = 2,
        hedge_delay: float = 1.2,
        max_rounds: int = 6,
    ):
        if total_size <= 0:
            raise StreamError("总长度未知，不能用 BTR")
        self.client = client
        self.pool = CdnPool(sources)
        self.total_size = total_size
        self.headers = headers
        self.concurrency = max(1, concurrency)
        self.max_concurrency = max(1, concurrency)
        self.chunk_size = max(32 * 1024, chunk_size)
        self.prefetch_chunks = max(1, prefetch_chunks)
        self.hedge_delay = hedge_delay
        self.max_rounds = max_rounds
        self.stats = Stats()

    # ------------------------------------------------------------ 单请求

    async def _download(self, src: Source, offset: int, length: int) -> bytes:
        """请求 [offset, offset+length) 区间的字节；断流时返回已收到的部分。"""
        assert length > 0
        headers = dict(self.headers)
        headers["Range"] = f"bytes={offset}-{offset + length - 1}"
        buf = bytearray()
        started = time.monotonic()
        try:
            async with self.client.stream(
                "GET",
                src.url,
                headers=headers,
                timeout=httpx.Timeout(20.0, connect=8.0, read=20.0),
            ) as resp:
                if resp.status_code not in (200, 206):
                    raise StreamError(f"HTTP {resp.status_code} @ {src.label}")
                skip = offset if resp.status_code == 200 else 0
                async for piece in resp.aiter_bytes(64 * 1024):
                    if skip:
                        if len(piece) <= skip:
                            skip -= len(piece)
                            continue
                        piece = piece[skip:]
                        skip = 0
                    room = length - len(buf)
                    if len(piece) >= room:
                        buf += piece[:room]
                        break
                    buf += piece
        except (httpx.HTTPError, StreamError):
            if not buf:
                self.pool.report_error(src)
                raise
            self.stats.retries += 1
        if len(buf) == length:
            self.pool.report_ok(src, len(buf), time.monotonic() - started)
        else:
            # 少了字节说明连接被截断，不能算这个节点"快"
            self.pool.report_error(src)
        self.stats.bytes_done += len(buf)
        return bytes(buf)

    async def _hedged(self, offset: int, length: int) -> bytes:
        """先在一个节点上请求；超时未回则另开一个节点重发同一段，谁先到用谁。"""
        tried: set[str] = set()
        tasks: dict[asyncio.Task[bytes], Source] = {}

        def launch() -> bool:
            src = self.pool.pick(exclude=tried)
            if src is None:
                return False
            tried.add(src.url)
            task = asyncio.create_task(self._download(src, offset, length))
            task.add_done_callback(lambda t: t.cancelled() or t.exception())
            tasks[task] = src
            return True

        launch()
        deadline = time.monotonic() + self.hedge_delay
        try:
            while tasks:
                timeout = max(0.0, deadline - time.monotonic())
                done, _ = await asyncio.wait(
                    set(tasks), timeout=timeout, return_when=asyncio.FIRST_COMPLETED
                )
                for task in done:
                    tasks.pop(task, None)
                    try:
                        data = task.result()
                    except (httpx.HTTPError, StreamError, asyncio.CancelledError):
                        continue
                    if data:
                        return data
                if done:
                    deadline = time.monotonic() + self.hedge_delay
                    continue
                # 还没回包 → 对冲
                if len(tasks) < 2 and launch():
                    self.stats.hedges += 1
                    self._ramp_up()
                    deadline = time.monotonic() + self.hedge_delay
                else:
                    deadline = time.monotonic() + 1.0
        finally:
            for task in tasks:
                task.cancel()
        raise StreamError(f"区间 {offset}+{length} 所有节点都失败")

    async def _chunk(self, offset: int, length: int) -> bytes:
        data = bytearray()
        rounds = 0
        while len(data) < length:
            rounds += 1
            if rounds > self.max_rounds:
                raise StreamError(f"块 {offset} 重试 {self.max_rounds} 次仍失败")
            try:
                part = await self._hedged(offset + len(data), length - len(data))
            except StreamError:
                self.stats.retries += 1
                self._ramp_up()
                await asyncio.sleep(min(0.2 * rounds, 1.0))
                continue
            if not part:
                self.stats.retries += 1
                continue
            data += part
        self.stats.chunks_done += 1
        return bytes(data)

    # ------------------------------------------------------------ 顺序输出

    async def iter_range(self, start: int = 0, end: int | None = None) -> AsyncIterator[bytes]:
        """按顺序产出 [start, end) 的字节，内部并发预取。"""
        end = self.total_size if end is None else min(end, self.total_size)
        start = max(0, min(start, end))
        if start >= end:
            return

        offsets = list(range(start, end, self.chunk_size))
        inflight: dict[int, asyncio.Task[bytes]] = {}
        idx = 0
        try:
            while idx < len(offsets):
                # 每轮重算窗口：上游可以按实时速度调整并发
                window = self.concurrency + self.prefetch_chunks
                while len(inflight) < window and idx + len(inflight) < len(offsets):
                    off = offsets[idx + len(inflight)]
                    inflight[off] = asyncio.create_task(
                        self._chunk(off, min(self.chunk_size, end - off))
                    )
                task = inflight.pop(offsets[idx])
                data = await task
                self.stats.bytes_served += len(data)
                yield data
                idx += 1
        finally:
            for task in inflight.values():
                task.cancel()

    def _ramp_up(self) -> None:
        """遇到慢块/断流时提高并发，最多到用户设定值。"""
        if self.concurrency < self.max_concurrency:
            self.concurrency = min(self.max_concurrency, self.concurrency + 2)

    async def read_all(self) -> bytes:
        out = bytearray()
        async for piece in self.iter_range():
            out += piece
        return bytes(out)

    def snapshot(self) -> dict:
        return {
            "mode": "btr",
            "nodes": [
                {"host": s.label, "speed": round(s.speed), "ttfb": round(s.ttfb, 3), "errors": s.errors}
                for s in sorted(self.pool.sources, key=lambda s: s.score, reverse=True)
            ],
            "concurrency": self.concurrency,
            "chunk_size": self.chunk_size,
            "chunks_done": self.stats.chunks_done,
            "chunks_total": -(-self.total_size // self.chunk_size),
            "bytes_done": self.stats.bytes_done,
            "bytes_served": self.stats.bytes_served,
            "progress": round(min(1.0, self.stats.bytes_done / self.total_size), 4),
            "speed": round(self.stats.speed),
            "recent_speed": round(self.stats.sample_speed()),
            "hedges": self.stats.hedges,
            "retries": self.stats.retries,
        }


class SimpleStream:
    """单连接直通，BTR 关闭时的降级路径，也用于长度未知的资源。"""

    def __init__(
        self,
        client: httpx.AsyncClient,
        url: str,
        headers: dict[str, str],
        total_size: int | None = None,
    ):
        self.client = client
        self.url = url
        self.headers = headers
        self.total_size = total_size
        self.stats = Stats()

    async def iter_range(self, start: int = 0, end: int | None = None) -> AsyncIterator[bytes]:
        headers = dict(self.headers)
        if start or end is not None:
            suffix = "" if end is None else str(end - 1)
            headers["Range"] = f"bytes={start}-{suffix}"
        remaining = None if end is None else max(0, end - start)
        async with self.client.stream("GET", self.url, headers=headers) as resp:
            if resp.status_code not in (200, 206):
                raise StreamError(f"HTTP {resp.status_code}")
            skip = start if resp.status_code == 200 else 0
            async for piece in resp.aiter_bytes(64 * 1024):
                if skip:
                    if len(piece) <= skip:
                        skip -= len(piece)
                        continue
                    piece = piece[skip:]
                    skip = 0
                if remaining is not None:
                    if remaining <= 0:
                        break
                    piece = piece[:remaining]
                    remaining -= len(piece)
                self.stats.bytes_done += len(piece)
                self.stats.bytes_served += len(piece)
                yield piece
                if remaining is not None and remaining <= 0:
                    break

    def snapshot(self) -> dict:
        return {
            "mode": "single",
            "nodes": [{"host": urllib.parse.urlsplit(self.url).netloc, "speed": 0, "ttfb": 0, "errors": 0}],
            "concurrency": 1,
            "chunk_size": 0,
            "chunks_done": 0,
            "chunks_total": 0,
            "bytes_done": self.stats.bytes_done,
            "bytes_served": self.stats.bytes_served,
            "progress": round(min(1.0, self.stats.bytes_done / self.total_size), 4) if self.total_size else 0,
            "speed": round(self.stats.speed),
            "recent_speed": round(self.stats.sample_speed()),
            "hedges": 0,
            "retries": 0,
        }


PROBE_BYTES = 64 * 1024


async def probe(client: httpx.AsyncClient, url: str, headers: dict[str, str], timeout: float = 3.0):
    """探测一个节点：返回 (总长度, 是否支持 Range, 首字节延迟, 实测吞吐)。

    吞吐是真实测出来的，不是猜的 —— 只换 host 的镜像节点里有大量
    "TTFB 很快但被限速到 5KB/s"的坑，不实测就会把块分给它们。
    """
    started = time.monotonic()
    probe_headers = dict(headers)
    probe_headers["Range"] = f"bytes=0-{PROBE_BYTES - 1}"
    async with client.stream("GET", url, headers=probe_headers, timeout=timeout) as resp:
        if resp.status_code not in (200, 206):
            raise StreamError(f"HTTP {resp.status_code}")
        ttfb = time.monotonic() - started
        total = 0
        content_range = resp.headers.get("content-range", "")
        if "/" in content_range:
            try:
                total = int(content_range.rsplit("/", 1)[1])
            except ValueError:
                total = 0
        if not total:
            total = int(resp.headers.get("content-length") or 0)
        received = 0
        async for piece in resp.aiter_bytes(8 * 1024):
            received += len(piece)
        elapsed = max(time.monotonic() - started, 1e-3)
        return total, resp.status_code == 206, ttfb, received / elapsed


async def build_stream(
    client: httpx.AsyncClient,
    url: str,
    backups: Iterable[str] = (),
    *,
    headers: dict[str, str] | None = None,
    concurrency: int = 8,
    chunk_size: int = 512 * 1024,
    btr: bool = True,
    use_mirrors: bool = True,
) -> BTRStream | SimpleStream:
    """探测候选节点 → 组装节点池 → 返回可流式读取的对象。"""
    headers = dict(headers or {})
    headers.setdefault("Accept-Encoding", "identity")
    if not btr:
        return SimpleStream(client, url, headers)

    candidates = expand_mirrors(url, backups) if use_mirrors else list(dict.fromkeys([url, *backups]))
    results = await asyncio.gather(
        *(probe(client, u, headers) for u in candidates), return_exceptions=True
    )
    sources: list[Source] = []
    total = 0
    for cand, res in zip(candidates, results):
        if isinstance(res, Exception):
            continue
        size, ranged, ttfb, throughput = res
        if not ranged:
            continue
        total = total or size
        sources.append(Source(url=cand, ttfb=ttfb, speed=throughput))
    if not sources or total <= 0:
        return SimpleStream(client, url, headers, total_size=total or None)

    # 只留真正跑得动的节点：慢节点会拖住整条流水线
    best = max(s.speed for s in sources)
    kept = [s for s in sources if s.speed >= max(best * 0.3, 150 * 1024)]
    if not kept:
        kept = [s for s in sources if s.speed >= best * 0.5] or sources[:1]
    sources = kept
    sources.sort(key=lambda s: s.score, reverse=True)

    # 块越小、并发越高，起播越快；但请求本身也有开销，所以按文件长度折中
    chunk_size = int(min(chunk_size, max(256 * 1024, total / 40)))
    chunk_size = max(128 * 1024, chunk_size)

    # 自适应并发：目标是 4MB/s 左右，够音乐用了。节点已经很快时没必要
    # 再开 8 条连接 —— 握手本身就要几百毫秒，反而更慢。
    target = 4 * 1024 * 1024
    auto_concurrency = max(2, min(concurrency, math.ceil(target / max(best, 1.0))))
    # 对冲延迟按"块在该节点上的理论耗时"来定，避免网络快时过早重复请求
    expected = chunk_size / max(best, 1.0)
    hedge_delay = min(2.5, max(0.8, expected * 2.0))
    stream = BTRStream(
        client,
        sources,
        total,
        headers,
        concurrency=auto_concurrency,
        chunk_size=chunk_size,
        hedge_delay=hedge_delay,
    )
    stream.max_concurrency = concurrency
    return stream
