"""API 层测试：Range 解析 + 演示模式的完整播放链路（真实 HTTP 服务）。"""

from __future__ import annotations

import httpx
import pytest

from bilimuse import server
from bilimuse.api import BiliClient, FAV_MAX_PAGE_SIZE
from bilimuse.auth import Session
from bilimuse.config import CACHE_DIR
from bilimuse.server import load_all_folder_items, parse_range
from mock_cdn import serve


class FakeBili:
    """只实现 folder_items，用来测整夹翻页，不碰真实网络。"""

    def __init__(self, pages: dict[int, dict]):
        self.pages = pages
        self.calls: list[tuple[int, int, int, str]] = []

    async def folder_items(self, media_id, page=1, size=20, keyword=""):
        self.calls.append((media_id, page, size, keyword))
        return self.pages.get(page, {"items": [], "has_more": False, "total": 0})


def page(items: list[dict], has_more: bool = False, total: int = 0) -> dict:
    return {"items": items, "has_more": has_more, "total": total}


def item(bvid: str, attr: int = 0) -> dict:
    return {"bvid": bvid, "title": bvid, "attr": attr}


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

    # 旧前端缓存可能还在传 ps=50：端点要兼容（API 层会压到 40），不能直接 422
    legacy = api.get("/api/folder/-1", params={"ps": 50})
    assert legacy.status_code == 200
    assert legacy.json()["total"] == 3


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
    resp = api.post(
        "/api/settings",
        json={
            "concurrency": 16,
            "btr_enabled": True,
            "volume": 0.55,
            "autoplay_next": False,
            "hidden_folders": [7, 3, 7],
            "theme": "cream",
        },
    )
    data = resp.json()
    assert data["concurrency"] == 16
    assert data["volume"] == 0.55
    assert data["autoplay_next"] is False
    assert data["hidden_folders"] == [3, 7]
    assert data["theme"] == "cream"
    # /api/state 也给前端带同一份显示设置
    assert api.get("/api/state").json()["settings"]["hidden_folders"] == [3, 7]
    assert api.get("/api/state").json()["settings"]["theme"] == "cream"
    # 未知主题不会写进配置
    assert api.post("/api/settings", json={"theme": "neon"}).status_code == 422
    resp = api.post("/api/settings", json={"concurrency": 999, "volume": 2})
    data = resp.json()
    assert data["concurrency"] <= 64
    assert data["volume"] == 1.0
    api.post(
        "/api/settings",
        json={
            "concurrency": 8,
            "volume": 0.9,
            "autoplay_next": True,
            "hidden_folders": [],
            "theme": "ink",
        },
    )


def test_demo_folder_all(api):
    data = api.get("/api/folder/-1/all").json()
    assert data["total"] == 3
    assert len(data["items"]) == 3
    assert all(t["bvid"].startswith("DEMO") for t in data["items"])

    filtered = api.get("/api/folder/-1/all", params={"keyword": "夜"}).json()
    assert filtered["total"] == 1
    assert filtered["items"][0]["bvid"] == "DEMO0001"


async def test_load_all_folder_items_deduplicates_and_stops():
    fake = FakeBili(
        {
            1: page([item("a"), item("b")], has_more=True, total=4),
            2: page([item("b"), item("c")], has_more=False, total=4),
        }
    )
    data = await load_all_folder_items(fake, 42, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["a", "b", "c"]
    assert data["total"] == 4
    assert fake.calls == [(42, 1, 40, ""), (42, 2, 40, "")]


async def test_load_all_folder_items_skips_filtered_empty_pages():
    fake = FakeBili(
        {
            1: page([item("a")], has_more=True),
            2: page([], has_more=True),
            3: page([item("b")], has_more=False),
        }
    )
    data = await load_all_folder_items(fake, 1, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["a", "b"]
    assert [call[1] for call in fake.calls] == [1, 2, 3]


async def test_load_all_folder_items_stops_when_api_repeats_page():
    fake = FakeBili(
        {
            1: page([item("a"), item("b")], has_more=True),
            2: page([item("a"), item("b")], has_more=True),
            3: page([item("c")], has_more=False),
        }
    )
    data = await load_all_folder_items(fake, 1, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["a", "b"]
    assert [call[1] for call in fake.calls] == [1, 2]


async def test_load_all_folder_items_filters_invalid_items():
    fake = FakeBili(
        {
            1: page(
                [item("ok"), item("bit0", 1), item("bit3", 8), item("both", 9)],
                has_more=False,
            ),
        }
    )
    data = await load_all_folder_items(fake, 1, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["ok"]


async def test_load_all_folder_items_skips_all_invalid_page():
    fake = FakeBili(
        {
            1: page([item("gone", 1), item("deleted", 8)], has_more=True),
            2: page([item("b")], has_more=False),
        }
    )
    data = await load_all_folder_items(fake, 1, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["b"]
    assert [call[1] for call in fake.calls] == [1, 2]


async def test_load_all_folder_items_respects_max_pages():
    fake = FakeBili(
        {p: page([item(f"t{p}")], has_more=True) for p in range(1, 6)}
    )
    data = await load_all_folder_items(fake, 1, max_pages=3, page_gap=0)

    assert [i["bvid"] for i in data["items"]] == ["t1", "t2", "t3"]
    assert [call[1] for call in fake.calls] == [1, 2, 3]


async def test_load_all_folder_items_ignores_folder_total_for_search():
    fake = FakeBili({1: page([item("a")], has_more=False, total=100)})
    data = await load_all_folder_items(fake, 1, keyword="live", page_gap=0)

    assert data["total"] == 1
    assert fake.calls == [(1, 1, 40, "live")]


async def test_folder_items_caps_ps_at_bilibili_limit():
    """B 站对 ps > 40 返回 -400「请求错误」，API 层必须自己把分页压回上限。"""
    seen: dict[str, str] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["ps"] = request.url.params["ps"]
        return httpx.Response(
            200,
            json={"code": 0, "data": {"medias": [], "has_more": False}},
        )

    client = BiliClient(Session(cookies={"SESSDATA": "x"}, mid=42))
    await client.http.aclose()
    client.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        await client.folder_items(1, page=1, size=50)
    finally:
        await client.close()

    assert FAV_MAX_PAGE_SIZE == 40
    assert server.FULL_PAGE_SIZE <= FAV_MAX_PAGE_SIZE
    assert seen["ps"] == str(FAV_MAX_PAGE_SIZE)


def test_index_injects_saved_theme(api):
    """桌面版端口随机，主题必须首帧由服务端注入，页面脚本不能把它覆盖掉。"""
    assert api.post("/api/settings", json={"theme": "cream"}).status_code == 200
    html = api.get("/").text
    assert 'data-theme="cream"' in html
    assert "preset === 'ink'" in html
    api.post("/api/settings", json={"theme": "ink"})


def test_settings_theme_persist(tmp_path, monkeypatch):
    """界面主题也要跨进程保留：桌面端每次启动端口不同，不能只靠 localStorage。"""
    from bilimuse import config as config_module

    monkeypatch.setattr(config_module, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config_module, "SETTINGS_FILE", tmp_path / "settings.json")

    settings = config_module.Settings()
    settings.theme = "cream"
    assert settings.save() is True

    restored = config_module.Settings()
    restored.load()
    assert restored.theme == "cream"

    restored.theme = "neon"
    restored.clamp()
    assert restored.theme is None


def test_settings_hidden_folders_persist(tmp_path, monkeypatch):
    """settings.json 里保存的隐藏收藏夹要能跨进程恢复，并去重排序。"""
    from bilimuse import config as config_module

    monkeypatch.setattr(config_module, "CONFIG_DIR", tmp_path)
    monkeypatch.setattr(config_module, "SETTINGS_FILE", tmp_path / "settings.json")

    settings = config_module.Settings()
    settings.hidden_folders = [5, 2, 5]
    assert settings.save() is True

    restored = config_module.Settings()
    restored.load()
    assert restored.hidden_folders == [2, 5]
