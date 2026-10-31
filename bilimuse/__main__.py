"""命令行入口：python -m bilimuse [--demo] [--desktop]"""

from __future__ import annotations

import argparse
import os
import sys

from .config import VERSION, settings


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="bilimuse",
        description="BiliMuse —— 把 B 站收藏夹变成私人音乐库（内置 BTR 多线程加速）",
    )
    parser.add_argument("--host", default=settings.host, help="监听地址，默认 127.0.0.1")
    parser.add_argument(
        "--port", type=int, default=settings.port, help="监听端口；0 表示由系统分配，默认 8756"
    )
    parser.add_argument("--demo", action="store_true", help="演示模式：不登录，播放本地生成的音频")
    parser.add_argument("--open", action="store_true", help="启动后自动打开浏览器")
    parser.add_argument("--concurrency", type=int, default=settings.concurrency, help="BTR 并发数")
    parser.add_argument("--no-btr", action="store_true", help="关闭多线程，走单连接（用于对比）")
    parser.add_argument(
        "--desktop",
        action="store_true",
        help="桌面壳模式：只监听回环地址、日志静默、就绪后输出 BILIMUSE_PORT=<port>",
    )
    parser.add_argument("--version", action="version", version=f"BiliMuse {VERSION}")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)

    # 桌面模式只服务本机窗口，强制回环：避免一边用桌面版一边把带登录态的
    # 代理暴露到局域网。
    desktop = args.desktop or os.environ.get("BILIMUSE_DESKTOP") == "1"
    if desktop:
        args.host = "127.0.0.1"

    settings.host = args.host
    settings.port = args.port
    settings.concurrency = args.concurrency
    settings.btr_enabled = not args.no_btr
    settings.clamp()

    from . import server

    server.state.demo_mode = args.demo

    announce = None
    if not desktop:
        display_host = "127.0.0.1" if args.host in {"0.0.0.0", "::"} else args.host

        def announce(actual_port: int) -> None:
            url = f"http://{display_host}:{actual_port}/"
            print(f"BiliMuse {VERSION}")
            print(f"  → {url}")
            print(
                f"  BTR: {'on' if settings.btr_enabled else 'off'}"
                f"  concurrency={settings.concurrency}"
                f"  chunk={settings.chunk_size // 1024}KB"
                + ("  [演示模式]" if args.demo else "")
            )
            if args.open:
                import threading
                import webbrowser

                threading.Timer(1.2, lambda: webbrowser.open(url)).start()

    try:
        from . import launcher

        launcher.run(server.app, args.host, args.port, quiet=desktop, on_ready=announce)
    except OSError as exc:
        # 端口被占用是最常见的启动失败，给出可操作的提示
        print(f"启动失败：{exc}", file=sys.stderr)
        print("端口可能已被占用，试试 --port 0 让系统分配。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
