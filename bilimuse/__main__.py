"""命令行入口：python -m bilimuse --demo"""

from __future__ import annotations

import argparse
import sys
import threading
import webbrowser

from .config import VERSION, settings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="bilimuse",
        description="BiliMuse —— 把 B 站收藏夹变成私人音乐库（内置 BTR 多线程加速）",
    )
    parser.add_argument("--host", default=settings.host, help="监听地址，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=settings.port, help="监听端口，默认 8756")
    parser.add_argument("--demo", action="store_true", help="演示模式：不登录，播放本地生成的音频")
    parser.add_argument("--open", action="store_true", help="启动后自动打开浏览器")
    parser.add_argument("--concurrency", type=int, default=settings.concurrency, help="BTR 并发数")
    parser.add_argument("--no-btr", action="store_true", help="关闭多线程，走单连接（用于对比）")
    parser.add_argument("--version", action="version", version=f"BiliMuse {VERSION}")
    args = parser.parse_args(argv)

    settings.host = args.host
    settings.port = args.port
    settings.concurrency = args.concurrency
    settings.btr_enabled = not args.no_btr
    settings.clamp()

    from . import server

    server.state.demo_mode = args.demo

    url = f"http://{args.host}:{args.port}/"
    print(f"BiliMuse {VERSION}")
    print(f"  → {url}")
    print(f"  BTR: {'on' if settings.btr_enabled else 'off'}  concurrency={settings.concurrency}"
          f"  chunk={settings.chunk_size // 1024}KB" + ("  [演示模式]" if args.demo else ""))

    if args.open:
        threading.Timer(1.2, lambda: webbrowser.open(url)).start()

    import uvicorn

    uvicorn.run(server.app, host=args.host, port=args.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
