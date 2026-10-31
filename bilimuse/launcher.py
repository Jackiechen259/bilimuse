"""统一的启动器：先自己 bind 端口，再把 socket 交给 uvicorn。

这样做的原因有两个：

* `--port 0` 时端口由内核分配，必须 bind 之后才知道真实端口，桌面壳需要它；
* 先 bind 再启动，端口被占用时会立刻失败，而不是等 uvicorn 起了一半才报错。
"""

from __future__ import annotations

import socket
import sys
import threading
import time
from typing import Any, Callable

import uvicorn

READY_PREFIX = "BILIMUSE_PORT="


def create_socket(host: str, port: int) -> socket.socket:
    """创建并绑定监听 socket（尚未 listen，uvicorn 会负责）。"""
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    # 重启后立刻复用端口：桌面版反复开关时很常见
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.set_inheritable(True)
    return sock


def socket_port(sock: socket.socket) -> int:
    return int(sock.getsockname()[1])


def make_server(app: Any, *, quiet: bool = False) -> uvicorn.Server:
    config = uvicorn.Config(
        app,
        log_level="warning" if quiet else "info",
        access_log=not quiet,
        lifespan="on",
    )
    return uvicorn.Server(config)


def announce_ready(
    server: uvicorn.Server,
    port: int,
    *,
    timeout: float = 30.0,
    emit: bool = True,
    on_ready: Callable[[int], None] | None = None,
    stream: Any = None,
) -> None:
    """等 uvicorn 真正开始 accept 之后，再打印端口。

    桌面壳靠这一行判断"可以打开页面了"，提前打印会让窗口先撞上连接失败。
    """
    out = stream or sys.stdout
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if getattr(server, "started", False):
            if emit:
                print(f"{READY_PREFIX}{port}", file=out, flush=True)
            if on_ready is not None:
                on_ready(port)
            return
        if getattr(server, "should_exit", False):
            return
        time.sleep(0.02)


def watch_stdin(server: uvicorn.Server) -> None:
    """桌面壳退出（含崩溃）时关闭管道的 stdin，这里收到 EOF 就优雅收尾。

    避免 Tauri 进程被强杀后，边车还在后台占着收藏夹/登录态。
    """
    try:
        stream = getattr(sys.stdin, "buffer", None)
        if stream is None:
            return
        while stream.read(1):
            pass
    except (OSError, ValueError):
        pass
    server.should_exit = True


def run(
    app: Any,
    host: str,
    port: int,
    *,
    quiet: bool = False,
    on_ready: Callable[[int], None] | None = None,
) -> None:
    """阻塞运行；端口先绑好，再交给 uvicorn。"""
    sock = create_socket(host, port)
    actual = socket_port(sock)
    server = make_server(app, quiet=quiet)

    threading.Thread(
        target=announce_ready,
        args=(server, actual),
        kwargs={"emit": quiet, "on_ready": on_ready},
        name="bilimuse-ready",
        daemon=True,
    ).start()
    if quiet:
        # 桌面模式：桌面壳退出（含崩溃）时 stdin 收到 EOF，进程跟着收尾
        threading.Thread(
            target=watch_stdin, args=(server,), name="bilimuse-stdin", daemon=True
        ).start()

    server.run(sockets=[sock])


__all__ = [
    "READY_PREFIX",
    "announce_ready",
    "create_socket",
    "make_server",
    "run",
    "socket_port",
    "watch_stdin",
]
