"""FastAPI 服务：登录、收藏夹、音频流代理（BTR）。

浏览器里的 <audio> 只能整段拉流，不能自己发多 Range 请求，所以真正的
多线程逻辑放在服务端：前端只连本地 /api/stream，服务端把远程音频按块
并发拉下来、按序吐给浏览器，并且支持 Range 以便拖动进度条。
"""

from __future__ import annotations

import asyncio
import mimetypes
import time
import uuid
from collections import OrderedDict
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel

from . import auth, demo
from .api import BiliClient, BiliError, NotLoggedIn, pick_audio
from .btr import BTRStream, SimpleStream, StreamError, build_stream
from .config import CACHE_DIR, REFERER, UA, VERSION, WEB_DIR, settings

MEDIA_HEADERS = {
    "User-Agent": UA,
    "Referer": REFERER,
    "Origin": "https://www.bilibili.com",
    "Accept": "*/*",
    "Accept-Encoding": "identity",
}

MAX_TRACKS = 48


@dataclass
class Track:
    id: str
    bvid: str
    title: str
    size: int
    quality: str
    bandwidth: int
    codecs: str
    mime: str
    duration: int
    stream: Any
    created: float = field(default_factory=time.time)

    def public(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "bvid": self.bvid,
            "title": self.title,
            "size": self.size,
            "quality": self.quality,
            "bandwidth": self.bandwidth,
            "codecs": self.codecs,
            "mime": self.mime,
            "duration": self.duration,
            "mode": self.stream.snapshot()["mode"],
            "nodes": len(self.stream.snapshot()["nodes"]),
        }


class State:
    bili: BiliClient | None = None
    media: httpx.AsyncClient | None = None
    tracks: "OrderedDict[str, Track]" = OrderedDict()
    by_bvid: dict[str, tuple[str, float]] = {}
    resolve_locks: dict[str, asyncio.Lock] = {}
    demo_mode: bool = False
    demo_files: dict[str, Path] = {}
    demo_bvid_to_idx: dict[str, int] = {}


state = State()


@asynccontextmanager
async def lifespan(app: FastAPI):
    session = auth.load()
    state.bili = BiliClient(session)
    state.media = httpx.AsyncClient(
        headers=MEDIA_HEADERS,
        timeout=httpx.Timeout(30.0, connect=10.0),
        follow_redirects=True,
        limits=httpx.Limits(max_connections=96, max_keepalive_connections=48),
    )
    if state.demo_mode:
        state.demo_files = demo.ensure_audio(CACHE_DIR)
        state.demo_bvid_to_idx = {t["bvid"]: i for i, t in enumerate(demo.DEMO_TRACKS)}
    yield
    await state.bili.close()
    await state.media.aclose()


app = FastAPI(title="BiliMuse", version=VERSION, lifespan=lifespan)


# ------------------------------------------------------------------ 工具


def parse_range(header: str | None, total: int) -> tuple[int, int] | None:
    """解析 Range 头，返回 [start, end) 或 None（不支持/无 Range）。"""
    if not header or not total:
        return None
    header = header.strip()
    if not header.lower().startswith("bytes="):
        return None
    spec = header[6:].split(",")[0].strip()
    if "-" not in spec:
        return None
    raw_start, raw_end = spec.split("-", 1)
    try:
        if raw_start == "":
            length = int(raw_end)
            if length <= 0:
                return None
            start = max(0, total - length)
            return start, total
        start = int(raw_start)
        end = int(raw_end) + 1 if raw_end else total
    except ValueError:
        return None
    if start >= total:
        return None
    end = min(end, total)
    if end <= start:
        return None
    return start, end


def _http_error(exc: Exception) -> HTTPException:
    if isinstance(exc, NotLoggedIn):
        return HTTPException(401, "未登录或登录已失效")
    if isinstance(exc, BiliError):
        return HTTPException(502, f"B 站接口返回错误 {exc.code}：{exc.message}")
    if isinstance(exc, StreamError):
        return HTTPException(502, f"音频流不可用：{exc}")
    return HTTPException(500, f"内部错误：{exc}")


def _media_type(track: Track) -> str:
    if track.mime:
        return track.mime.split(";")[0]
    return mimetypes.guess_type(f"x.{track.codecs or ''}")[0] or "audio/mp4"


# ------------------------------------------------------------------ 生命周期接口


@app.get("/api/state")
async def api_state() -> dict[str, Any]:
    bili = state.bili
    assert bili is not None
    user = bili.session.public()
    if user["logged_in"] and not user["uname"]:
        try:
            await bili.refresh_profile()
            user = bili.session.public()
        except (BiliError, httpx.HTTPError):
            pass
    return {
        "version": VERSION,
        "demo": state.demo_mode,
        "user": user,
        "settings": {
            "btr_enabled": settings.btr_enabled,
            "concurrency": settings.concurrency,
            "chunk_size": settings.chunk_size,
            "prefer_lossless": settings.prefer_lossless,
        },
    }


class LoginQR(BaseModel):
    pass


@app.post("/api/login/qr")
async def api_login_qr() -> dict[str, Any]:
    bili = state.bili
    assert bili is not None
    try:
        data = await bili.qr_generate()
        import segno

        qr = segno.make(data["url"], error="m")
        svg = qr.svg_data_uri(scale=6, dark="#0b0d12", light="#ffffff")
        return {"key": data["key"], "url": data["url"], "qr": svg}
    except (BiliError, httpx.HTTPError) as exc:
        raise _http_error(exc)


@app.get("/api/login/poll")
async def api_login_poll(key: str = Query(..., min_length=8)) -> dict[str, Any]:
    bili = state.bili
    assert bili is not None
    try:
        result = await bili.qr_poll(key)
    except (BiliError, httpx.HTTPError) as exc:
        raise _http_error(exc)
    if result["status"] == "success":
        auth.save(bili.session)
        result["user"] = bili.session.public()
        result.pop("cookies", None)
    return result


@app.post("/api/logout")
async def api_logout() -> dict[str, Any]:
    bili = state.bili
    assert bili is not None
    auth.clear()
    bili.session.cookies = {}
    bili.session.mid = 0
    bili.session.uname = ""
    bili.session.face = ""
    bili.http.cookies.clear()
    state.tracks.clear()
    state.by_bvid.clear()
    return {"ok": True}


# ------------------------------------------------------------------ 收藏夹


@app.get("/api/folders")
async def api_folders() -> dict[str, Any]:
    if state.demo_mode:
        return {"folders": demo.DEMO_FOLDERS}
    bili = state.bili
    assert bili is not None
    try:
        return {"folders": await bili.folders()}
    except (BiliError, httpx.HTTPError) as exc:
        raise _http_error(exc)


@app.get("/api/folder/{fid}")
async def api_folder(
    fid: int,
    pn: int = Query(1, ge=1),
    ps: int = Query(30, ge=1, le=50),
    keyword: str = "",
) -> dict[str, Any]:
    if state.demo_mode:
        items = [dict(t) for t in demo.DEMO_TRACKS]
        if keyword:
            items = [t for t in items if keyword.lower() in t["title"].lower()]
        return {
            "title": demo.DEMO_FOLDERS[0]["title"],
            "items": items,
            "has_more": False,
            "total": len(items),
        }
    bili = state.bili
    assert bili is not None
    try:
        data = await bili.folder_items(fid, pn, ps, keyword)
    except (BiliError, httpx.HTTPError) as exc:
        raise _http_error(exc)
    folders = {f["id"]: f["title"] for f in (await bili.folders())}
    data["title"] = folders.get(fid, "收藏夹")
    # 过滤掉"已失效"的稿件
    data["items"] = [i for i in data["items"] if i["bvid"] and not (i["attr"] & 9)]
    return data


# ------------------------------------------------------------------ 取流


async def _demo_track(bvid: str, base_url: str) -> Track:
    idx = state.demo_bvid_to_idx[bvid]
    meta = demo.DEMO_TRACKS[idx]
    path = state.demo_files[bvid]
    url = f"{base_url.rstrip('/')}/demo/audio/{path.name}"
    stream = await build_stream(
        state.media,
        url,
        (),
        headers={"Referer": "http://127.0.0.1"},
        concurrency=settings.concurrency,
        chunk_size=settings.chunk_size,
        btr=settings.btr_enabled,
        use_mirrors=False,  # 演示音频是本机文件，没必要去探测 B 站 CDN
    )
    return Track(
        id=uuid.uuid4().hex[:12],
        bvid=bvid,
        title=meta["title"],
        size=path.stat().st_size,
        quality="演示音轨",
        bandwidth=0,
        codecs="wav",
        mime="audio/wav",
        duration=meta["duration"],
        stream=stream,
    )


async def _real_track(bvid: str, title: str = "") -> Track:
    bili = state.bili
    assert bili is not None
    page = await bili.page_info(bvid)
    data = await bili.playurl(bvid, page["cid"])
    dash = data.get("dash") or {}
    audio = pick_audio(dash, settings.prefer_lossless)
    stream = await build_stream(
        state.media,
        audio["url"],
        audio["backup_urls"],
        headers=MEDIA_HEADERS,
        concurrency=settings.concurrency,
        chunk_size=settings.chunk_size,
        btr=settings.btr_enabled,
    )
    size = getattr(stream, "total_size", 0) or 0
    return Track(
        id=uuid.uuid4().hex[:12],
        bvid=bvid,
        title=title,
        size=int(size),
        quality=audio["quality"],
        bandwidth=audio["bandwidth"],
        codecs=audio["codecs"],
        mime=audio["mime"] or "audio/mp4",
        duration=page["duration"],
        stream=stream,
    )


class TrackRequest(BaseModel):
    bvid: str
    title: str = ""


@app.post("/api/track")
async def api_track(req: TrackRequest, request: Request) -> dict[str, Any]:
    bvid = req.bvid.strip()
    if not bvid or len(bvid) > 24:
        raise HTTPException(400, "bvid 不合法")

    cached = state.by_bvid.get(bvid)
    if cached and cached[1] > time.time() and cached[0] in state.tracks:
        track = state.tracks[cached[0]]
        state.tracks.move_to_end(cached[0])
        if req.title and not track.title:
            track.title = req.title
        return track.public()

    lock = state.resolve_locks.setdefault(bvid, asyncio.Lock())
    async with lock:
        cached = state.by_bvid.get(bvid)
        if cached and cached[1] > time.time() and cached[0] in state.tracks:
            return state.tracks[cached[0]].public()
        try:
            if state.demo_mode and bvid in state.demo_bvid_to_idx:
                track = await _demo_track(bvid, str(request.base_url))
            else:
                track = await _real_track(bvid, req.title)
        except (BiliError, httpx.HTTPError, StreamError) as exc:
            raise _http_error(exc)

        state.tracks[track.id] = track
        state.by_bvid[bvid] = (track.id, time.time() + 1800)
        while len(state.tracks) > MAX_TRACKS:
            old_id, _ = state.tracks.popitem(last=False)
            state.by_bvid = {k: v for k, v in state.by_bvid.items() if v[0] != old_id}
        return track.public()


async def _stream_body(track: Track, start: int, end: int | None):
    async for piece in track.stream.iter_range(start, end):
        yield piece


@app.api_route("/api/stream/{sid}", methods=["GET", "HEAD"])
async def api_stream(sid: str, request: Request):
    track = state.tracks.get(sid)
    if track is None:
        raise HTTPException(404, "音频已过期，请重新点击播放")
    total = int(getattr(track.stream, "total_size", 0) or track.size or 0)
    rng = parse_range(request.headers.get("range"), total)
    media_type = _media_type(track)
    base_headers = {
        "Accept-Ranges": "bytes" if total else "none",
        "Cache-Control": "no-store",
    }

    if rng:
        start, end = rng
        headers = {
            **base_headers,
            "Content-Range": f"bytes {start}-{end - 1}/{total}",
            "Content-Length": str(end - start),
        }
        if request.method == "HEAD":
            return Response(status_code=206, headers=headers, media_type=media_type)
        return StreamingResponse(
            _stream_body(track, start, end),
            status_code=206,
            headers=headers,
            media_type=media_type,
        )

    headers = dict(base_headers)
    if total:
        headers["Content-Length"] = str(total)
    if request.method == "HEAD":
        return Response(status_code=200, headers=headers, media_type=media_type)
    return StreamingResponse(
        _stream_body(track, 0, total or None),
        status_code=200,
        headers=headers,
        media_type=media_type,
    )


@app.get("/api/stats/{sid}")
async def api_stats(sid: str) -> dict[str, Any]:
    track = state.tracks.get(sid)
    if track is None:
        raise HTTPException(404, "音频不存在")
    snap = track.stream.snapshot()
    snap.update({"bvid": track.bvid, "title": track.title, "size": track.size})
    return snap


# ------------------------------------------------------------------ 设置


class SettingsPatch(BaseModel):
    btr_enabled: bool | None = None
    concurrency: int | None = None
    chunk_size: int | None = None
    prefer_lossless: bool | None = None


@app.get("/api/settings")
async def api_get_settings() -> dict[str, Any]:
    return {
        "btr_enabled": settings.btr_enabled,
        "concurrency": settings.concurrency,
        "chunk_size": settings.chunk_size,
        "prefer_lossless": settings.prefer_lossless,
    }


@app.post("/api/settings")
async def api_set_settings(patch: SettingsPatch) -> dict[str, Any]:
    if patch.btr_enabled is not None:
        settings.btr_enabled = patch.btr_enabled
    if patch.concurrency is not None:
        settings.concurrency = patch.concurrency
    if patch.chunk_size is not None:
        settings.chunk_size = patch.chunk_size
    if patch.prefer_lossless is not None:
        settings.prefer_lossless = patch.prefer_lossless
    settings.clamp()
    # 已解析的音轨也立刻套用新并发
    for track in state.tracks.values():
        if isinstance(track.stream, BTRStream):
            track.stream.concurrency = settings.concurrency
            track.stream.max_concurrency = settings.concurrency
            track.stream.chunk_size = settings.chunk_size
    return await api_get_settings()


# ------------------------------------------------------------------ 演示音频（限速）


@app.api_route("/demo/audio/{name}", methods=["GET", "HEAD"])
async def demo_audio(name: str, request: Request):
    if not state.demo_mode or "/" in name or ".." in name:
        raise HTTPException(404, "not found")
    path = CACHE_DIR / "demo" / name
    if not path.exists():
        raise HTTPException(404, "not found")
    total = path.stat().st_size
    rng = parse_range(request.headers.get("range"), total)
    start, end = rng if rng else (0, total)

    async def body():
        await asyncio.sleep(0.12)  # 模拟节点首字节延迟
        remaining = end - start
        with path.open("rb") as fh:
            fh.seek(start)
            while remaining > 0:
                block = fh.read(min(16 * 1024, remaining))
                if not block:
                    break
                remaining -= len(block)
                await asyncio.sleep(len(block) / demo.DEMO_RATE)
                yield block

    headers = {"Accept-Ranges": "bytes", "Content-Length": str(end - start)}
    status = 200
    if rng:
        status = 206
        headers["Content-Range"] = f"bytes {start}-{end - 1}/{total}"
    if request.method == "HEAD":
        return Response(status_code=status, headers=headers, media_type="audio/wav")
    return StreamingResponse(body(), status_code=status, headers=headers, media_type="audio/wav")


# ------------------------------------------------------------------ 静态页面


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(WEB_DIR / "index.html")


@app.get("/app.js")
async def app_js() -> FileResponse:
    return FileResponse(WEB_DIR / "app.js", media_type="application/javascript")


@app.get("/style.css")
async def style_css() -> FileResponse:
    return FileResponse(WEB_DIR / "style.css", media_type="text/css")


@app.get("/api/health")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "version": VERSION,
        "btr": settings.btr_enabled,
        "concurrency": settings.concurrency,
        "tracks": len(state.tracks),
        "demo": state.demo_mode,
    }
