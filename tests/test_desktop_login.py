"""桌面版账号密码 / 短信验证码登录：请求编排 + cookie 换登录态。"""

from __future__ import annotations

import time

import httpx
import pytest
from fastapi.testclient import TestClient

from bilimuse import auth, server
from bilimuse.api import BiliClient, BiliError, NotLoggedIn
from bilimuse.auth import Session


@pytest.fixture()
def desktop_client(monkeypatch):
    """带 lifespan 的 TestClient，并把真正的 nav 校验换成假实现。"""
    server.state.desktop_shell = True
    server.state.desktop_login = None
    with TestClient(server.app) as client:
        assert server.state.bili is not None

        async def fake_login(cookies: dict[str, str]) -> dict:
            if cookies.get("SESSDATA") == "expired":
                raise NotLoggedIn()
            session = server.state.bili.session
            session.cookies = dict(cookies)
            session.mid = 42
            session.uname = "测试用户"
            session.face = ""
            session.vip = False
            return session.public()

        monkeypatch.setattr(server.state.bili, "login_from_cookies", fake_login)
        yield client
    server.state.desktop_shell = False
    server.state.desktop_login = None


def test_end_to_end_password_login(desktop_client):
    client = desktop_client

    started = client.post("/api/desktop/login/request", json={"mode": "password"})
    assert started.status_code == 200
    payload = started.json()
    request_id = payload["request_id"]
    assert payload["status"] == "pending" and payload["mode"] == "password"

    # 桌面壳领取请求（只会领到一次）
    claim = client.get("/api/desktop/login/pending")
    assert claim.status_code == 200
    assert claim.json() == {"request_id": request_id, "mode": "password"}
    assert client.get("/api/desktop/login/pending").status_code == 204

    status = client.get(
        "/api/desktop/login/status", params={"request_id": request_id}
    ).json()
    assert status["status"] == "claimed"

    done = client.post(
        "/api/desktop/login/cookies",
        json={
            "request_id": request_id,
            "cookies": {
                "SESSDATA": "fresh",
                "bili_jct": "jct",
                "DedeUserID": "42",
            },
        },
    )
    assert done.status_code == 200
    body = done.json()
    assert body["status"] == "success"
    assert body["user"]["mid"] == 42

    status = client.get(
        "/api/desktop/login/status", params={"request_id": request_id}
    ).json()
    assert status["status"] == "success"

    saved = auth.load()
    assert saved.logged_in and saved.mid == 42
    assert saved.cookies["SESSDATA"] == "fresh"


def test_failed_cookie_validation_is_reported(desktop_client):
    client = desktop_client
    request_id = client.post(
        "/api/desktop/login/request", json={"mode": "sms"}
    ).json()["request_id"]
    client.get("/api/desktop/login/pending")

    result = client.post(
        "/api/desktop/login/cookies",
        json={"request_id": request_id, "cookies": {"SESSDATA": "expired"}},
    ).json()
    assert result["status"] == "failed"
    assert "登录态校验失败" in result["message"]


def test_unparsable_validation_response_is_reported(desktop_client, monkeypatch):
    """风控页返回 HTML 时 json() 会抛 ValueError，也不能把前端卡在 claimed。"""
    client = desktop_client
    assert server.state.bili is not None

    async def boom(cookies: dict[str, str]) -> dict:
        raise ValueError("Expecting value: line 1 column 1 (char 0)")

    monkeypatch.setattr(server.state.bili, "login_from_cookies", boom)
    request_id = client.post(
        "/api/desktop/login/request", json={"mode": "password"}
    ).json()["request_id"]
    client.get("/api/desktop/login/pending")

    result = client.post(
        "/api/desktop/login/cookies",
        json={"request_id": request_id, "cookies": {"SESSDATA": "x"}},
    ).json()
    assert result["status"] == "failed"
    assert "无法解析" in result["message"]


def test_cancel_before_claim(desktop_client):
    client = desktop_client
    request_id = client.post(
        "/api/desktop/login/request", json={"mode": "password"}
    ).json()["request_id"]

    cancelled = client.post(
        "/api/desktop/login/cancel",
        json={"request_id": request_id, "message": "用户关闭了弹窗"},
    ).json()
    assert cancelled["status"] == "cancelled"
    assert client.get("/api/desktop/login/pending").status_code == 204


def test_stale_pending_request_expires(desktop_client):
    client = desktop_client
    request_id = client.post(
        "/api/desktop/login/request", json={"mode": "password"}
    ).json()["request_id"]

    # 模拟桌面壳一直没来领取：领取端直接返回 204，状态查询显示 expired
    assert server.state.desktop_login is not None
    server.state.desktop_login.created = time.time() - 120
    assert client.get("/api/desktop/login/pending").status_code == 204
    status = client.get(
        "/api/desktop/login/status", params={"request_id": request_id}
    ).json()
    assert status["status"] == "expired"


def test_endpoints_are_desktop_only(monkeypatch):
    server.state.desktop_shell = False
    server.state.desktop_login = None
    with TestClient(server.app) as client:
        resp = client.post("/api/desktop/login/request", json={"mode": "password"})
        assert resp.status_code == 404
        assert client.get("/api/desktop/login/pending").status_code == 404


@pytest.mark.asyncio
async def test_cookie_login_rolls_back_on_bad_session():
    session = Session(
        cookies={"SESSDATA": "old", "bili_jct": "old-jct"},
        mid=7,
        uname="老用户",
        vip=True,
    )
    client = BiliClient(session)

    async def handler(request: httpx.Request) -> httpx.Response:
        cookie = request.headers.get("cookie", "")
        is_login = "SESSDATA=old" in cookie
        return httpx.Response(
            200,
            json={
                "code": 0,
                "data": {
                    "isLogin": is_login,
                    "mid": 7,
                    "uname": "老用户",
                    "face": "",
                    "vipStatus": 1,
                },
            },
        )

    await client.http.aclose()
    client.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    with pytest.raises(NotLoggedIn):
        await client.login_from_cookies(
            {"SESSDATA": "expired", "bili_jct": "bad", "DedeUserID": "99"}
        )
    assert client.session.cookies["SESSDATA"] == "old"
    assert client.session.mid == 7
    assert client.session.uname == "老用户"

    user = await client.login_from_cookies(
        {"SESSDATA": "old", "bili_jct": "fresh", "DedeUserID": "7"}
    )
    assert user["mid"] == 7 and user["logged_in"] is True
    assert client.session.cookies["bili_jct"] == "fresh"
    await client.close()


@pytest.mark.asyncio
async def test_cookie_login_requires_sessdata():
    client = BiliClient(Session())
    with pytest.raises(BiliError):
        await client.login_from_cookies({"bili_jct": "no-session"})
    await client.close()
