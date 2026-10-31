"""启动器测试：端口绑定、就绪信号、打包后的静态文件路径。"""

from __future__ import annotations

import io
import socket
import sys
from types import SimpleNamespace

import pytest

from bilimuse import config, launcher


def test_create_socket_binds_ephemeral_port() -> None:
    sock = launcher.create_socket("127.0.0.1", 0)
    try:
        port = launcher.socket_port(sock)
        assert 0 < port < 65536
        assert sock.getsockname()[1] == port
    finally:
        sock.close()


def test_port_conflict_raises() -> None:
    sock = launcher.create_socket("127.0.0.1", 0)
    sock.listen(1)  # 真正占用端口；只 bind 的话 SO_REUSEADDR 会放行
    port = launcher.socket_port(sock)
    try:
        with pytest.raises(OSError):
            launcher.create_socket("127.0.0.1", port).close()
    finally:
        sock.close()


def test_announce_ready_waits_for_start() -> None:
    out = io.StringIO()
    server = SimpleNamespace(started=True, should_exit=False)

    launcher.announce_ready(server, 43210, stream=out)

    assert out.getvalue() == f"{launcher.READY_PREFIX}43210\n"


def test_announce_ready_skips_when_exiting() -> None:
    out = io.StringIO()
    server = SimpleNamespace(started=False, should_exit=True)

    launcher.announce_ready(server, 43210, timeout=1.0, stream=out)

    assert out.getvalue() == ""


def test_announce_ready_reports_callback() -> None:
    seen: list[int] = []
    server = SimpleNamespace(started=True, should_exit=False)

    launcher.announce_ready(
        server, 5001, emit=False, stream=io.StringIO(), on_ready=seen.append
    )

    assert seen == [5001]


def test_web_dir_uses_bundle_when_frozen(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)
    assert config._web_dir() == tmp_path / "bilimuse" / "web"


def test_web_dir_defaults_to_package() -> None:
    assert config.WEB_DIR.name == "web"
    assert (config.WEB_DIR / "index.html").is_file()


def test_desktop_announce_line_is_parseable() -> None:
    """桌面壳就是靠这一行拿端口的，格式别改。"""
    out = io.StringIO()
    launcher.announce_ready(
        SimpleNamespace(started=True, should_exit=False), 12345, stream=out
    )
    line = out.getvalue().strip()
    port = int(line.split("=", 1)[1])
    assert port == 12345
