"""测试用的假 CDN：支持 Range、按连接限速、可注入断流。"""

from __future__ import annotations

import asyncio
import socket
import threading
import time

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import Response, StreamingResponse

CHUNK = 16 * 1024


class Blackhole:
    """只 accept、永远不回包的节点：模拟"连得上但卡死"的镜像。"""

    def __init__(self) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(64)
        self.sock.settimeout(0.2)
        self.port = int(self.sock.getsockname()[1])
        self._stop = threading.Event()
        self._held: list[socket.socket] = []
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self.sock.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            self._held.append(conn)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/media/song.m4a"

    def close(self) -> None:
        self._stop.set()
        try:
            self.sock.close()
        except OSError:
            pass
        for conn in self._held:
            try:
                conn.close()
            except OSError:
                pass


def parse_range(header: str | None, total: int):
    if not header or not header.lower().startswith("bytes="):
        return None
    spec = header[6:].split(",")[0].strip()
    if "-" not in spec:
        return None
    raw_start, raw_end = spec.split("-", 1)
    try:
        if raw_start == "":
            length = int(raw_end)
            return max(0, total - length), total
        start = int(raw_start)
        end = int(raw_end) + 1 if raw_end else total
    except ValueError:
        return None
    if start >= total:
        return None
    return start, min(end, total)


def make_app(payload: bytes, rate: int = 500_000, ttfb: float = 0.03) -> FastAPI:
    """rate: 每连接多少字节/秒（用来模拟"单连接很慢"）。"""
    app = FastAPI()

    @app.api_route("/media/song.m4a", methods=["GET", "HEAD"])
    async def media(request: Request):
        total = len(payload)
        rng = parse_range(request.headers.get("range"), total)
        start, end = rng if rng else (0, total)
        if start >= total:
            return Response(status_code=416, headers={"Content-Range": f"bytes */{total}"})

        try:
            fail_after = int(request.query_params.get("fail_after", "0"))
        except ValueError:
            fail_after = 0

        async def body():
            await asyncio.sleep(ttfb)
            sent = 0
            pos = start
            while pos < end:
                size = min(CHUNK, end - pos)
                if fail_after and sent + size > fail_after:
                    # 先吐一部分，然后直接断流（模拟节点中途掉线）
                    tail = fail_after - sent
                    if tail > 0:
                        yield payload[pos:pos + tail]
                    raise RuntimeError("模拟节点断流")
                await asyncio.sleep(size / rate)
                yield payload[pos:pos + size]
                pos += size
                sent += size

        headers = {"Accept-Ranges": "bytes", "Content-Length": str(end - start)}
        status = 200
        if rng:
            status = 206
            headers["Content-Range"] = f"bytes {start}-{end - 1}/{total}"
        if request.method == "HEAD":
            return Response(status_code=status, headers=headers, media_type="audio/mp4")
        return StreamingResponse(body(), status_code=status, headers=headers, media_type="audio/mp4")

    return app


def serve(app: FastAPI, lifespan: str = "auto") -> tuple[uvicorn.Server, str]:
    """在后台线程里起服务，返回 (server, base_url)。"""
    config = uvicorn.Config(app, host="127.0.0.1", port=0, log_level="error", lifespan=lifespan)
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.time() + 10
    while not server.started and time.time() < deadline:
        time.sleep(0.02)
    if not server.started:
        raise RuntimeError("测试服务器启动失败")
    port = server.servers[0].sockets[0].getsockname()[1]
    return server, f"http://127.0.0.1:{port}"
