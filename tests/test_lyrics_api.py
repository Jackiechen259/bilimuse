"""歌词 HTTP 接口测试（全部不联网）。

用 TestClient 跑真实 lifespan；B 站客户端换成假实现，LRCLIB 与
B 站字幕下载入口全部 monkeypatch 掉，接口层只验证编排与错误路径。
"""

from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient

from bilimuse import config, lyrics, server
from bilimuse.api import (
    SUBTITLE_MAX_BYTES,
    BiliClient,
    BiliError,
    subtitle_url,
)
from bilimuse.auth import Session

BV = "BV1xx411c7mD"

MANUAL_TRACK = {
    "id": 11,
    "lan": "zh-CN",
    "label": "中文（人工）",
    "url": "https://aisubtitle.hdslb.com/manual.json",
}
AI_TRACK = {
    "id": 22,
    "lan": "ai-zh",
    "label": "中文（AI）",
    "url": "https://aisubtitle.hdslb.com/ai.json",
}


def seg(frm: float, to: float, text: str) -> dict:
    return {"from": frm, "to": to, "content": text}


def record(**over) -> dict:
    data = {
        "id": 31664182,
        "trackName": "夜航星",
        "artistName": "不才",
        "albumName": "",
        "duration": 304,
        "instrumental": False,
        "syncedLyrics": "[00:01.00]夜航星\n[00:02.00]第二行",
        "plainLyrics": "夜航星",
    }
    data.update(over)
    return data


class FakeBili:
    """假 B 站客户端：只记录调用，字幕通过 monkeypatch 的 _fetch_* 提供。"""

    def __init__(self, *, logged_in: bool = True):
        self.session = Session(
            cookies={"SESSDATA": "fake"} if logged_in else {},
            mid=1 if logged_in else 0,
        )
        self.calls: list[tuple] = []

    async def page_info(self, bvid):
        self.calls.append(("page_info", bvid))
        return {"cid": 42, "duration": 304}

    async def subtitle_tracks(self, bvid, cid):
        self.calls.append(("subtitle_tracks", bvid, cid))
        return []

    async def subtitle_body(self, url):
        self.calls.append(("subtitle_body", url))
        return {"body": []}


@pytest.fixture()
def api(monkeypatch, tmp_path):
    previous_demo = server.state.demo_mode
    server.state.demo_mode = False
    monkeypatch.setattr(config.settings, "lyrics_online", True)
    monkeypatch.setattr(config.settings, "lyrics_bili", True)
    with TestClient(server.app) as client:
        service = server.state.lyrics
        assert service is not None
        service.lyrics_dir = tmp_path / "lyrics"
        service.cache_dir = tmp_path / "cache"
        fake = FakeBili()
        service.bili = fake
        calls = {"search": 0, "record": 0}

        async def search(params):
            calls["search"] += 1
            return []

        async def fetch_record(lrclib_id):
            calls["record"] += 1
            return None

        monkeypatch.setattr(service, "_fetch_search", search)
        monkeypatch.setattr(service, "_fetch_record", fetch_record)
        yield SimpleNamespace(client=client, svc=service, fake=fake, calls=calls)
    server.state.demo_mode = previous_demo


# ---------------------------------------------------------------- 读取歌词


def test_demo_lyrics_are_offline(api):
    resp = api.client.get("/api/lyrics/DEMO0001")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["source"] == "demo"
    assert data["source_label"] == "演示歌词"
    assert data["synced"] is True
    assert len(data["lines"]) == 8
    assert data["lines"][0] == {"t": 2.0, "text": "这是 45 秒的合成器演示音轨"}
    # 演示歌词不碰 B 站，也不碰 LRCLIB
    assert api.fake.calls == []
    assert api.calls == {"search": 0, "record": 0}


def test_demo_sources_are_offline(api):
    resp = api.client.get("/api/lyrics/DEMO0002/sources")
    assert resp.status_code == 200
    items = resp.json()["items"]
    by_source = {item["source"]: item for item in items}
    assert by_source["demo"]["available"] is True
    assert by_source["demo"]["label"] == "演示歌词"
    assert by_source["local"]["available"] is False
    assert by_source["bili"]["available"] is False
    assert by_source["lrclib"]["available"] is False
    assert api.fake.calls == []
    assert api.calls == {"search": 0, "record": 0}


def test_get_lyrics_uses_lrclib(api, monkeypatch):
    async def fake_search(params):
        api.calls["search"] += 1
        return [record()]

    monkeypatch.setattr(api.svc, "_fetch_search", fake_search)
    resp = api.client.get(
        f"/api/lyrics/{BV}",
        params={"title": "夜航星 - 不才", "upper": "UP主", "duration": 304},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["source"] == "lrclib"
    assert data["source_label"] == "LRCLIB"
    assert data["synced"] is True
    assert data["offset"] == 0.0
    assert data["lines"][0] == {"t": 1.0, "text": "夜航星"}
    assert data["meta"]["matched"] == {
        "id": 31664182,
        "track": "夜航星",
        "artist": "不才",
        "duration": 304,
    }
    assert data["meta"]["available"]["lrclib"] is True
    assert data["reason"] == ""


def test_get_lyrics_returns_none_on_timeout(api, monkeypatch):
    async def timeout(params):
        raise httpx.ReadTimeout("超时")

    monkeypatch.setattr(api.svc, "_fetch_search", timeout)
    resp = api.client.get(f"/api/lyrics/{BV}", params={"title": "夜航星", "duration": 304})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "none"
    assert data["reason"] == "timeout"
    assert data["lines"] == []


def test_get_lyrics_returns_none_on_error(api, monkeypatch):
    async def boom(params):
        raise ValueError("坏响应")

    monkeypatch.setattr(api.svc, "_fetch_search", boom)
    resp = api.client.get(f"/api/lyrics/{BV}", params={"title": "夜航星", "duration": 304})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "none"
    assert data["reason"] == "error"


def test_lyrics_error_never_breaks_playback(api, monkeypatch):
    async def boom(*args, **kwargs):
        raise RuntimeError("一切都坏了")

    monkeypatch.setattr(api.svc, "resolve", boom)
    resp = api.client.get(f"/api/lyrics/{BV}")
    assert resp.status_code == 200
    assert resp.json()["status"] == "none"
    assert resp.json()["reason"] == "error"


def test_invalid_bvid_is_rejected(api):
    bad = [
        "a" * 25,
        "bad bvid!",
        "歌词",
    ]
    for bvid in bad:
        assert api.client.get(f"/api/lyrics/{bvid}").status_code == 400
        assert api.client.delete(f"/api/lyrics/{bvid}").status_code == 400
        assert (
            api.client.post(f"/api/lyrics/{bvid}", json={"lrc": "[00:01.00]x"}).status_code
            == 400
        )
        assert (
            api.client.post(f"/api/lyrics/{bvid}/use", json={"id": 1}).status_code == 400
        )


# ---------------------------------------------------------------- 导入 / 删除


def test_import_local_lyrics(api):
    payload = {"lrc": "[00:01.00]导入第一行\n[00:02.00]导入第二行"}
    resp = api.client.post(f"/api/lyrics/{BV}", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["source"] == "local"
    assert data["source_label"] == "本地歌词"
    assert [line["text"] for line in data["lines"]] == ["导入第一行", "导入第二行"]

    path = api.svc.lyrics_dir / f"{BV}.lrc"
    assert path.is_file()
    # 本地文件永远优先，不请求 LRCLIB
    assert api.client.get(f"/api/lyrics/{BV}").json()["source"] == "local"
    assert api.calls["search"] == 0


def test_import_too_large_returns_400(api):
    resp = api.client.post(
        f"/api/lyrics/{BV}",
        json={"lrc": "x" * (lyrics.MAX_LRC_BYTES + 1)},
    )
    assert resp.status_code == 400


def test_import_empty_returns_400(api):
    assert api.client.post(f"/api/lyrics/{BV}", json={"lrc": "   "}).status_code == 400


def test_delete_removes_local_and_cache(api):
    assert (
        api.client.post(f"/api/lyrics/{BV}", json={"lrc": "[00:01.00]一句"}).status_code
        == 200
    )
    resp = api.client.delete(f"/api/lyrics/{BV}")
    assert resp.status_code == 200
    assert resp.json()["ok"] is True
    assert not (api.svc.lyrics_dir / f"{BV}.lrc").exists()
    assert api.client.get(f"/api/lyrics/{BV}").json()["status"] == "none"


# ---------------------------------------------------------------- 偏好


def test_offset_clamp_roundtrip(api):
    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"offset": 99})
    assert resp.status_code == 200
    assert resp.json()["offset"] == 30.0

    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"offset": -99})
    assert resp.json()["offset"] == -30.0

    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"offset": None})
    assert resp.json()["offset"] == 0.0


def test_demo_import_local_lrc_wins(api):
    assert api.client.post(
        "/api/lyrics/DEMO0003", json={"lrc": "[00:01.00]自己导入的"}
    ).status_code == 200
    data = api.client.get("/api/lyrics/DEMO0003").json()
    assert data["status"] == "ok"
    assert data["source"] == "local"
    assert data["lines"] == [{"t": 1.0, "text": "自己导入的"}]
    sources = {
        item["source"]: item
        for item in api.client.get("/api/lyrics/DEMO0003/sources").json()["items"]
    }
    assert sources["local"]["available"] is True


def test_offset_applied_to_demo_lyrics(api):
    api.client.post("/api/lyrics/DEMO0001/prefs", json={"offset": 2.5})
    data = api.client.get("/api/lyrics/DEMO0001").json()
    assert data["offset"] == 2.5
    assert data["meta"]["offset_source"] == "manual"
    assert data["meta"]["auto_offset"] is None
    assert len(data["lines"]) == 8


def test_offset_mode_roundtrip(api):
    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"offset": 2.5})
    assert resp.status_code == 200
    assert resp.json()["offset_mode"] == "manual"

    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"offset_mode": "auto"})
    assert resp.status_code == 200
    assert resp.json()["offset"] == 0.0
    assert resp.json()["offset_mode"] == "auto"

    assert (
        api.client.post(
            f"/api/lyrics/{BV}/prefs", json={"offset_mode": "bogus"}
        ).status_code
        == 400
    )


def test_prefs_pin_and_clear(api):
    resp = api.client.post(
        f"/api/lyrics/{BV}/prefs",
        json={"pin_source": "lrclib", "pin_id": 31664182},
    )
    assert resp.status_code == 200
    assert resp.json()["pin"] == {"source": "lrclib", "id": 31664182}

    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"pin_source": None})
    assert resp.status_code == 200
    assert resp.json()["pin"] is None


def test_prefs_reject_bad_pin(api):
    assert (
        api.client.post(f"/api/lyrics/{BV}/prefs", json={"pin_source": "bogus"}).status_code
        == 400
    )
    # LRCLIB 必须给出具体候选 id；B 站字幕只锁来源是合法的
    assert (
        api.client.post(f"/api/lyrics/{BV}/prefs", json={"pin_source": "lrclib"}).status_code
        == 400
    )


def test_prefs_accept_bili_pin_without_id(api):
    resp = api.client.post(f"/api/lyrics/{BV}/prefs", json={"pin_source": "bili"})
    assert resp.status_code == 200
    assert resp.json()["pin"] == {"source": "bili", "id": None}


def test_pinned_bili_source_is_used(api, monkeypatch):
    api.client.post(f"/api/lyrics/{BV}/prefs", json={"pin_source": "bili"})

    async def fake_tracks(bvid):
        return [dict(AI_TRACK), dict(MANUAL_TRACK)]

    async def fake_download(track):
        assert track["lan"] == "zh-CN"
        return [lyrics.LyricLine(1.0, "人工歌词")], "manual"

    monkeypatch.setattr(api.svc, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(api.svc, "_download_subtitle", fake_download)
    data = api.client.get(f"/api/lyrics/{BV}").json()
    assert data["status"] == "ok"
    assert data["source"] == "bili"
    assert data["meta"]["matched"] is None
    # 锁了字幕源之后不再走 LRCLIB 搜索
    assert api.calls["search"] == 0


def test_pinned_lrclib_is_used(api, monkeypatch):
    api.client.post(
        f"/api/lyrics/{BV}/prefs",
        json={"pin_source": "lrclib", "pin_id": 31664182},
    )

    async def fake_record(lrclib_id):
        api.calls["record"] += 1
        return record()

    monkeypatch.setattr(api.svc, "_fetch_record", fake_record)
    data = api.client.get(f"/api/lyrics/{BV}").json()
    assert data["status"] == "ok"
    assert data["source"] == "lrclib"
    assert api.calls["record"] == 1
    assert api.calls["search"] == 0


# ---------------------------------------------------------------- 换源 / 搜索 / 采用


def test_sources_endpoint(api, monkeypatch):
    async def fake_tracks(bvid):
        return [dict(AI_TRACK)]

    async def fake_search(params):
        api.calls["search"] += 1
        return []

    monkeypatch.setattr(api.svc, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(api.svc, "_fetch_search", fake_search)

    items = api.client.get(f"/api/lyrics/{BV}/sources").json()["items"]
    by_source = {item["source"]: item for item in items}
    assert by_source["local"]["available"] is False
    assert by_source["bili"] == {
        "source": "bili",
        "available": True,
        "label": "B 站字幕（AI 转写）",
        "kind": "ai",
        "id": 22,
    }
    assert by_source["lrclib"]["available"] is False
    assert by_source["demo"]["available"] is False

    api.client.post(f"/api/lyrics/{BV}", json={"lrc": "[00:01.00]本地"})
    items = api.client.get(f"/api/lyrics/{BV}/sources").json()["items"]
    by_source = {item["source"]: item for item in items}
    assert by_source["local"]["available"] is True


def test_sources_show_manual_subtitles(api, monkeypatch):
    async def fake_tracks(bvid):
        return [dict(MANUAL_TRACK), dict(AI_TRACK)]

    async def fake_search(params):
        return [record()]

    monkeypatch.setattr(api.svc, "_fetch_bili_tracks", fake_tracks)
    monkeypatch.setattr(api.svc, "_fetch_search", fake_search)

    items = api.client.get(
        f"/api/lyrics/{BV}/sources",
        params={"title": "夜航星 - 不才", "upper": "UP主", "duration": 304},
    ).json()["items"]
    by_source = {item["source"]: item for item in items}
    assert by_source["bili"]["kind"] == "manual"
    assert by_source["bili"]["label"] == "B 站字幕（人工）"
    assert by_source["lrclib"]["available"] is True
    assert by_source["lrclib"]["matched"] == {
        "id": 31664182,
        "track": "夜航星",
        "artist": "不才",
        "duration": 304,
    }


def test_sources_survive_errors(api, monkeypatch):
    async def boom(*args, **kwargs):
        raise RuntimeError("源不可用")

    monkeypatch.setattr(api.svc, "_fetch_bili_tracks", boom)
    monkeypatch.setattr(api.svc, "_fetch_search", boom)
    resp = api.client.get(f"/api/lyrics/{BV}/sources")
    assert resp.status_code == 200
    items = resp.json()["items"]
    by_source = {item["source"]: item for item in items}
    assert by_source["bili"]["available"] is False
    assert by_source["lrclib"]["available"] is False


def test_search_endpoint(api, monkeypatch):
    captured: dict[str, object] = {}

    async def fake_search(params):
        captured.update(params)
        return [record(id=i) for i in range(12)]

    monkeypatch.setattr(api.svc, "_fetch_search", fake_search)
    resp = api.client.get("/api/lyrics/search", params={"q": "夜航星"})
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert len(items) == 10
    assert set(items[0]) >= {
        "id",
        "track",
        "artist",
        "album",
        "duration",
        "instrumental",
        "synced",
    }

    captured.clear()
    items = api.client.get("/api/lyrics/search", params={"q": "  "}).json()["items"]
    assert items == []
    assert captured == {}


def test_use_lrclib_candidate(api, monkeypatch):
    async def fake_record(lrclib_id):
        assert lrclib_id == 777
        return record(id=777)

    monkeypatch.setattr(api.svc, "_fetch_record", fake_record)
    resp = api.client.post(f"/api/lyrics/{BV}/use", json={"id": 777})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["source"] == "lrclib"
    assert data["meta"]["matched"]["id"] == 777

    # 采用后应被 pin 住，后续解析不再搜索
    data = api.client.get(f"/api/lyrics/{BV}").json()
    assert data["source"] == "lrclib"
    assert api.calls["search"] == 0


def test_use_unknown_candidate_returns_none(api):
    resp = api.client.post(f"/api/lyrics/{BV}/use", json={"id": 1})
    assert resp.status_code == 200
    assert resp.json()["status"] == "none"


# ---------------------------------------------------------------- B 站字幕客户端


async def test_subtitle_tracks_filters_urls():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "code": 0,
                "data": {
                    "subtitle": {
                        "subtitles": [
                            {
                                "id": 1,
                                "lan": "zh-CN",
                                "lan_doc": "人工",
                                "subtitle_url": "https://aisubtitle.hdslb.com/a.json",
                            },
                            {
                                "id": 2,
                                "lan": "ai-zh",
                                "lan_doc": "AI",
                                "subtitle_url": "//aisubtitle.hdslb.com/b.json",
                            },
                            {
                                "id": 3,
                                "lan": "ai-zh",
                                "subtitle_url": "https://evil.example.com/c.json",
                            },
                            {
                                "id": 4,
                                "lan": "ai-zh",
                                "subtitle_url": "http://aisubtitle.hdslb.com/d.json",
                            },
                            {"id": 5, "lan": "ai-zh", "subtitle_url": ""},
                            {
                                "id": 6,
                                "lan": "zh-CN",
                                "subtitle_url": "/x/subtitle.json",
                            },
                        ]
                    }
                },
            },
        )

    client = BiliClient(Session())
    await client.http.aclose()
    client.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        tracks = await client.subtitle_tracks(BV, 42)
    finally:
        await client.close()

    assert [track["id"] for track in tracks] == [1, 2, 6]
    assert tracks[1]["url"] == "https://aisubtitle.hdslb.com/b.json"
    assert tracks[2]["url"] == "https://www.bilibili.com/x/subtitle.json"
    assert tracks[0]["lan"] == "zh-CN"


async def test_subtitle_body_whitelist():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "aisubtitle.hdslb.com"
        return httpx.Response(200, json={"body": [seg(0, 1, "词")]})

    client = BiliClient(Session())
    await client.http.aclose()
    client.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        for bad in (
            "https://evil.example.com/subtitle.json",
            "http://aisubtitle.hdslb.com/subtitle.json",
            "ftp://aisubtitle.hdslb.com/subtitle.json",
            "https://aisubtitle.hdslb.com.evil.com/x.json",
            "",
        ):
            with pytest.raises(BiliError):
                await client.subtitle_body(bad)
        body = await client.subtitle_body("//aisubtitle.hdslb.com/ok.json")
        assert body["body"][0]["content"] == "词"
    finally:
        await client.close()


# ---------------------------------------------------------------- 服务生命周期 / 设置


def test_lrclib_client_has_no_cookies():
    with TestClient(server.app) as client:
        assert client.get("/api/health").json()["ok"] is True
        assert server.state.lrclib is not None
        assert server.state.lyrics is not None
        # LRCLIB 必须使用独立、不带 B 站 Cookie 的客户端
        assert list(server.state.lrclib.cookies.jar) == []
        header_names = {name.lower() for name in server.state.lrclib.headers}
        assert "cookie" not in header_names
        assert "BiliMuse/" in server.state.lrclib.headers["user-agent"]
        assert server.state.lrclib is not server.state.bili.http


def test_settings_lyrics_flags(monkeypatch):
    monkeypatch.setattr(config.settings, "lyrics_online", True)
    monkeypatch.setattr(config.settings, "lyrics_bili", True)
    with TestClient(server.app) as client:
        resp = client.post(
            "/api/settings",
            json={"lyrics_online": False, "lyrics_bili": False},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["lyrics_online"] is False
        assert data["lyrics_bili"] is False
        state = client.get("/api/state").json()["settings"]
        assert state["lyrics_online"] is False
        assert state["lyrics_bili"] is False


# ---------------------------------------------------------------- 字幕地址 / 响应上限


def test_subtitle_url_rebuilds_from_parsed_parts():
    # 返回的是用解析结果重建的 URL：host 归一化、丢弃 userinfo / 端口 / fragment
    assert (
        subtitle_url("https://AISUBTITLE.HDSLB.COM:443/a.json?x=1#frag")
        == "https://aisubtitle.hdslb.com/a.json?x=1"
    )
    assert (
        subtitle_url("//aisubtitle.hdslb.com/b.json")
        == "https://aisubtitle.hdslb.com/b.json"
    )
    assert (
        subtitle_url("/x/subtitle.json")
        == "https://www.bilibili.com/x/subtitle.json"
    )


@pytest.mark.parametrize(
    "bad",
    [
        "https://user:pass@aisubtitle.hdslb.com/x.json",
        "https://aisubtitle.hdslb.com@evil.example.com/x.json",
        "https://aisubtitle.hdslb.com\\evil.example.com/x.json",
        "https://ａｉｓｕｂｔｉｔｌｅ.hdslb.com/x.json",
    ],
)
def test_subtitle_url_rejects_userinfo_and_weird_hosts(bad):
    with pytest.raises(BiliError):
        subtitle_url(bad)


async def test_subtitle_body_rejects_redirect_to_other_host():
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(
            302, headers={"Location": "https://evil.example.com/subtitle.json"}
        )

    client = BiliClient(Session())
    await client.http.aclose()
    # 共享 client 跟随重定向；字幕下载必须单独关掉
    client.http = httpx.AsyncClient(
        transport=httpx.MockTransport(handler), follow_redirects=True
    )
    try:
        with pytest.raises(BiliError):
            await client.subtitle_body("https://aisubtitle.hdslb.com/a.json")
    finally:
        await client.close()
    assert len(seen) == 1
    assert "evil.example.com" not in seen[0]


async def test_subtitle_body_rejects_oversized_body():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"x" * (SUBTITLE_MAX_BYTES + 1))

    client = BiliClient(Session())
    await client.http.aclose()
    client.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(BiliError):
            await client.subtitle_body("https://aisubtitle.hdslb.com/a.json")
    finally:
        await client.close()


def test_resolve_meta_reports_pin(api, monkeypatch):
    api.client.post(
        f"/api/lyrics/{BV}/prefs", json={"pin_source": "lrclib", "pin_id": 777}
    )

    async def fake_record(lrclib_id):
        return record(id=777)

    monkeypatch.setattr(api.svc, "_fetch_record", fake_record)
    data = api.client.get(f"/api/lyrics/{BV}").json()
    assert data["status"] == "ok"
    assert data["meta"]["pin"] == {"source": "lrclib", "id": 777}
