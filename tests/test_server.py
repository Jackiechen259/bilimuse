"""API 层测试：Range 解析 + 演示模式的完整播放链路（真实 HTTP 服务）。"""

from __future__ import annotations

import httpx
import pytest

from bilimuse import server
from bilimuse.config import CACHE_DIR
from bilimuse.server import parse_range
from mock_cdn import serve


@pytest.fixture(scope="module")
def api():
    server.state.demo_mode = True
    srv, base = serve(server.app)  # 真实端口，演示音频要能被自己的 BTR 引擎拉到
    with httpx.Client(base_url=base, timeout=30.0) as client:
        yield client
    srv.should_exit = True


def test_parse_range_cases():
    assert parse_range("bytes=0-999", 10000) == (0, 1000)
    assert parse_range("bytes=5000-", 10000) == (5000, 10000)
    assert parse_range("bytes=-500", 10000) == (9500, 10000)
    assert parse_range("bytes=0-", None) is None
    assert parse_range(None, 10000) is None
    assert parse_range("bytes=20000-", 10000) is None
    assert parse_range("items=0-10", 10000) is None
    assert parse_range("bytes=100-200,300-400", 10000) == (100, 201)
    assert parse_range("bytes=0-99999", 10000) == (0, 10000)


def test_health_and_state(api):
    health = api.get("/api/health").json()
    assert health["ok"] is True
    assert health["demo"] is True

    state = api.get("/api/state").json()
    assert state["demo"] is True
    # 网页版没有桌面壳，账号密码 / 短信登录入口要隐藏
    assert state["desktop"] is False
    assert "settings" in state and "version" in state


def test_demo_folder(api):
    folders = api.get("/api/folders").json()["folders"]
    assert len(folders) == 1 and folders[0]["count"] == 3

    data = api.get("/api/folder/-1").json()
    assert data["total"] == 3
    assert data["items"][0]["bvid"].startswith("DEMO")


def test_full_playback_chain(api):
    info = api.post("/api/track", json={"bvid": "DEMO0001", "title": "夜航星"}).json()
    assert info["size"] > 1_000_000
    assert info["mode"] == "btr", info
    assert info["nodes"] >= 1

    path = CACHE_DIR / "demo" / "night_flight.wav"
    raw = path.read_bytes()

    resp = api.get(f"/api/stream/{info['id']}", headers={"Range": "bytes=0-65535"})
    assert resp.status_code == 206
    assert resp.headers["content-range"] == f"bytes 0-65535/{len(raw)}"
    assert resp.content == raw[:65536]

    resp = api.get(f"/api/stream/{info['id']}", headers={"Range": "bytes=300000-399999"})
    assert resp.status_code == 206
    assert resp.content == raw[300000:400000]

    head = api.head(f"/api/stream/{info['id']}")
    assert head.status_code == 200
    assert int(head.headers["content-length"]) == len(raw)
    assert head.content == b""

    stats = api.get(f"/api/stats/{info['id']}").json()
    assert stats["mode"] == "btr"
    assert stats["bytes_done"] > 0


def test_concurrent_streams_are_independent(api):
    """同时播两首（切歌不断流）时两个 stream id 各自可用。"""
    first = api.post("/api/track", json={"bvid": "DEMO0002"}).json()
    second = api.post("/api/track", json={"bvid": "DEMO0003"}).json()
    assert first["id"] != second["id"]
    for info in (first, second):
        resp = api.get(f"/api/stream/{info['id']}", headers={"Range": "bytes=0-1023"})
        assert resp.status_code == 206 and len(resp.content) == 1024


def test_unknown_track_404(api):
    assert api.get("/api/stream/nope").status_code == 404


def test_settings_roundtrip(api):
    resp = api.post("/api/settings", json={"concurrency": 16, "btr_enabled": True})
    assert resp.json()["concurrency"] == 16
    resp = api.post("/api/settings", json={"concurrency": 999})
    assert resp.json()["concurrency"] <= 64
    api.post("/api/settings", json={"concurrency": 8})
