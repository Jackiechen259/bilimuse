"""B 站 Web API 封装：扫码登录、收藏夹、音频流地址。"""

from __future__ import annotations

import hashlib
import time
import urllib.parse
from typing import Any

import httpx

from .auth import Session
from .config import API, PASSPORT, REFERER, UA, settings

# WBI 签名用的重排表（B 站前端常量）
_MIXIN_KEY_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
    61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
    36, 20, 34, 44,
]

# 收藏夹列表接口的 ps 上限：B 站对 ps > 40 直接回 -400「请求错误」，
# 和是否登录无关，所以这里统一兜底，避免调用方传入过大的分页。
FAV_MAX_PAGE_SIZE = 40

# dash 中音频流的 id -> 中文说明
AUDIO_QUALITY = {
    30216: "64K",
    30232: "132K",
    30280: "192K",
    30250: "杜比全景声",
    30251: "Hi-Res 无损",
}


class BiliError(RuntimeError):
    def __init__(self, code: int, message: str, data: Any = None):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message
        self.data = data


class NotLoggedIn(BiliError):
    def __init__(self, message: str = "账号未登录"):
        super().__init__(-101, message)


# 字幕地址白名单：接口返回的 subtitle_url 一旦被篡改，服务端不能当开放代理
SUBTITLE_HOSTS = ("hdslb.com", "bilibili.com", "bilivideo.com")
SUBTITLE_BASE = "https://www.bilibili.com"
# 字幕 JSON 上限：正常字幕远小于这个值，超过说明响应异常，直接拒绝
SUBTITLE_MAX_BYTES = 4 * 1024 * 1024


def subtitle_url(url: str) -> str:
    """校验并补全 B 站字幕地址，不合法直接抛 BiliError。

    只允许 https，host 必须等于或后缀属于白名单域名；``//host/x`` 与相对
    地址分别补全成 https 和 ``https://www.bilibili.com`` 下的绝对地址。
    返回值是用解析结果重建的 URL：userinfo、端口、fragment 以及带反斜杠 /
    非 ASCII 的 host 一律拒绝，避免校验对象与真正请求对象不一致。
    """
    raw = str(url or "").strip()
    if not raw:
        raise BiliError(-1, "字幕地址为空")
    if raw.startswith("//"):
        raw = "https:" + raw
    parsed = urllib.parse.urlsplit(raw)
    if not parsed.scheme:
        raw = urllib.parse.urljoin(f"{SUBTITLE_BASE}/", raw.lstrip("/"))
        parsed = urllib.parse.urlsplit(raw)
    if parsed.scheme.lower() != "https":
        raise BiliError(-1, "字幕地址必须使用 https")
    if parsed.username is not None or parsed.password is not None:
        raise BiliError(-1, "字幕地址不允许包含账号信息")
    host = (parsed.hostname or "").lower()
    if not host or "\\" in host or not host.isascii():
        raise BiliError(-1, "字幕地址不在允许的域名内")
    if not any(
        host == domain or host.endswith("." + domain) for domain in SUBTITLE_HOSTS
    ):
        raise BiliError(-1, "字幕地址不在允许的域名内")
    # 用解析出的部件重建，不再返回可能含歧义的原始字符串
    return urllib.parse.urlunsplit(("https", host, parsed.path, parsed.query, ""))


def _mixin_key(orig: str) -> str:
    return "".join(orig[i] for i in _MIXIN_KEY_TAB)[:32]


class BiliClient:
    """一个实例对应一份登录态；http 连接池在进程内复用。"""

    def __init__(self, session: Session):
        self.session = session
        self.http = httpx.AsyncClient(
            headers={
                "User-Agent": UA,
                "Referer": REFERER,
                "Origin": "https://www.bilibili.com",
                "Accept-Language": "zh-CN,zh;q=0.9",
            },
            timeout=httpx.Timeout(15.0, connect=8.0),
            follow_redirects=True,
            limits=httpx.Limits(max_connections=64, max_keepalive_connections=32),
        )
        if session.cookies:
            self.http.cookies.update(session.cookies)

    async def close(self) -> None:
        await self.http.aclose()

    # ---------------------------------------------------------------- 基础请求

    async def _get(
        self,
        url: str,
        params: dict[str, Any] | None = None,
        *,
        need_login: bool = False,
    ) -> dict[str, Any]:
        if need_login and not self.session.logged_in:
            raise NotLoggedIn()
        resp = await self.http.get(url, params=params)
        resp.raise_for_status()
        body = resp.json()
        code = int(body.get("code", 0))
        if code != 0:
            raise BiliError(code, str(body.get("message", "")), body.get("data"))
        return body.get("data") or {}

    async def ensure_buvid(self) -> None:
        """部分接口要求 buvid3/buvid4，缺了就补一个。"""
        if "buvid3" in self.http.cookies:
            return
        try:
            data = await self._get(f"{API}/x/frontend/finger/spi")
            for key, val in (("buvid3", data.get("b_3")), ("buvid4", data.get("b_4"))):
                if val:
                    self.http.cookies.set(key, val, domain=".bilibili.com")
        except (httpx.HTTPError, BiliError, ValueError):
            pass

    async def _wbi_keys(self) -> tuple[str, str]:
        data = await self._get(f"{API}/x/web-interface/nav")
        wbi = data.get("wbi_img") or {}
        img = str(wbi.get("img_url", "")).rsplit("/", 1)[-1].split(".")[0]
        sub = str(wbi.get("sub_url", "")).rsplit("/", 1)[-1].split(".")[0]
        return img, sub

    @staticmethod
    def _sign_wbi(params: dict[str, Any], img_key: str, sub_key: str) -> dict[str, Any]:
        mixin = _mixin_key(img_key + sub_key)
        signed = dict(params)
        signed["wts"] = int(time.time())
        pairs = sorted(signed.items())
        # B 站要求过滤 !'()* 字符
        query = urllib.parse.urlencode(
            [(k, "".join(ch for ch in str(v) if ch not in "!'()*")) for k, v in pairs]
        )
        signed["w_rid"] = hashlib.md5((query + mixin).encode()).hexdigest()
        return signed

    # ---------------------------------------------------------------- 登录

    async def qr_generate(self) -> dict[str, str]:
        data = await self._get(f"{PASSPORT}/x/passport-login/web/qrcode/generate")
        return {"key": str(data.get("qrcode_key", "")), "url": str(data.get("url", ""))}

    async def qr_poll(self, key: str) -> dict[str, Any]:
        """返回 {status, message, cookies?}

        status: pending / scanned / success / expired
        B 站返回的 code: 86101 未扫码, 86090 已扫码未确认, 0 成功, 86038 过期
        """
        resp = await self.http.get(
            f"{PASSPORT}/x/passport-login/web/qrcode/poll",
            params={"qrcode_key": key, "source": "main-mini"},
        )
        body = resp.json()
        data = body.get("data") or {}
        code = int(data.get("code", body.get("code", -1)))
        if code == 86101:
            return {"status": "pending", "message": "等待扫码"}
        if code == 86090:
            return {"status": "scanned", "message": "已扫码，请在手机上确认"}
        if code == 86038:
            return {"status": "expired", "message": "二维码已过期"}
        if code != 0:
            return {"status": "pending", "message": str(data.get("message") or "等待扫码")}

        cookies: dict[str, str] = {}
        # 1) 响应头 Set-Cookie（httpx 已解析，但有效期等细节以 key 为准）
        for c in self.http.cookies.jar:
            if "bilibili" in c.domain:
                cookies[c.name] = c.value
        # 2) 回调 URL 上的 query 参数（最可靠）
        callback = str(data.get("url", ""))
        if "?" in callback:
            for k, v in urllib.parse.parse_qsl(callback.split("?", 1)[1]):
                if k in {"SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"}:
                    cookies[k] = v
        if "SESSDATA" not in cookies:
            raise BiliError(-1, "登录成功但没拿到 SESSDATA")
        self.session.cookies = cookies
        self.session.mid = int(cookies.get("DedeUserID", 0) or 0)
        self.http.cookies.update(cookies)
        await self.refresh_profile()
        return {"status": "success", "message": "登录成功", "cookies": cookies}

    async def login_from_cookies(self, cookies: dict[str, str]) -> dict[str, Any]:
        """用桌面壳官方登录窗口拿到的 cookie 完成登录。

        B 站账号密码 / 短信验证码登录都必须先过极验，自己实现验证码 SDK 既脆弱
        又容易踩风控。所以桌面壳内嵌 B 站官方登录页，成功后只把 .bilibili.com
        的 cookie 交到这里：先临时替换登录态，用 nav 校验；失败则恢复原状态，
        密码始终不进入本程序。
        """
        cleaned: dict[str, str] = {}
        for raw_name, raw_value in cookies.items():
            name = str(raw_name).strip()
            value = str(raw_value).strip()
            if not name or not value or len(name) > 64 or len(value) > 8192:
                continue
            cleaned[name] = value
        if not cleaned.get("SESSDATA"):
            raise BiliError(-1, "登录窗口没有返回 SESSDATA")

        previous = (
            dict(self.session.cookies),
            self.session.mid,
            self.session.uname,
            self.session.face,
            self.session.vip,
        )
        try:
            mid = int(cleaned.get("DedeUserID", 0) or 0)
        except ValueError:
            mid = 0
        self.session.cookies = cleaned
        self.session.mid = mid
        self.http.cookies.clear()
        self.http.cookies.update(cleaned)

        try:
            user = await self.refresh_profile()
        except Exception:
            (
                self.session.cookies,
                self.session.mid,
                self.session.uname,
                self.session.face,
                self.session.vip,
            ) = previous
            self.http.cookies.clear()
            self.http.cookies.update(previous[0])
            raise

        # nav 可能补了 buvid3 之类的 cookie，一并收进登录态
        merged = dict(cleaned)
        for cookie in self.http.cookies.jar:
            name = cookie.name or ""
            value = cookie.value or ""
            if not name or not value or len(value) > 8192:
                continue
            if "bilibili" in (cookie.domain or ""):
                merged[name] = value
        self.session.cookies = merged
        return user

    async def refresh_profile(self) -> dict[str, Any]:
        data = await self._get(f"{API}/x/web-interface/nav")
        if not data.get("isLogin"):
            raise NotLoggedIn()
        self.session.mid = int(data.get("mid", self.session.mid) or self.session.mid)
        self.session.uname = str(data.get("uname", ""))
        self.session.face = str(data.get("face", ""))
        vip = data.get("vipStatus") == 1 or bool((data.get("vip") or {}).get("status"))
        self.session.vip = vip
        return self.session.public()

    # ---------------------------------------------------------------- 收藏夹

    async def folders(self) -> list[dict[str, Any]]:
        if not self.session.mid:
            await self.refresh_profile()
        data = await self._get(
            f"{API}/x/v3/fav/folder/created/list-all",
            {"up_mid": self.session.mid, "web_location": "333.1387"},
            need_login=True,
        )
        raw = data.get("list") or []
        out = []
        for f in raw:
            out.append(
                {
                    "id": int(f.get("id") or 0),
                    "title": str(f.get("title") or "未命名收藏夹"),
                    "count": int(f.get("media_count") or 0),
                    "fav_state": int(f.get("fav_state") or 0),
                }
            )
        return out

    async def folder_items(
        self, media_id: int, page: int = 1, size: int = 20, keyword: str = ""
    ) -> dict[str, Any]:
        # B 站对 ps > 40 返回 -400，这里再兜一层，防止调用方绕过校验
        size = max(1, min(int(size), FAV_MAX_PAGE_SIZE))
        params: dict[str, Any] = {
            "media_id": media_id,
            "pn": page,
            "ps": size,
            "order": "mtime",
            "type": 0,
            "tid": 0,
            "platform": "web",
            "web_location": "333.1387",
        }
        if keyword:
            params["keyword"] = keyword
        data = await self._get(f"{API}/x/v3/fav/resource/list", params, need_login=True)
        medias = data.get("medias") or []
        items = []
        for m in medias:
            items.append(
                {
                    "bvid": str(m.get("bvid") or ""),
                    "title": str(m.get("title") or ""),
                    "cover": str(m.get("cover") or ""),
                    "upper": str((m.get("upper") or {}).get("name") or ""),
                    "duration": int(m.get("duration") or 0),
                    "ctime": int(m.get("ctime") or 0),
                    "intro": str(m.get("intro") or "")[:200],
                    "attr": int(m.get("attr") or 0),
                }
            )
        return {
            "items": items,
            "has_more": bool(data.get("has_more")),
            "total": int(data.get("info", {}).get("media_count") or 0)
            if isinstance(data.get("info"), dict)
            else 0,
        }

    # ---------------------------------------------------------------- 取流

    async def page_info(self, bvid: str) -> dict[str, Any]:
        data = await self._get(f"{API}/x/player/pagelist", {"bvid": bvid})
        pages = data if isinstance(data, list) else data.get("page") or []
        if not pages:
            raise BiliError(-404, "找不到该视频的分P")
        first = pages[0]
        return {
            "cid": int(first.get("cid") or 0),
            "duration": int(first.get("duration") or 0),
            "part": str(first.get("part") or ""),
            "page_count": len(pages),
        }

    # ---------------------------------------------------------------- 字幕

    async def subtitle_tracks(self, bvid: str, cid: int) -> list[dict[str, Any]]:
        """取一个分P的字幕轨道列表；地址不合法或没登录时返回空列表。

        人工字幕的 ``lan`` 不以 ``ai-`` 开头，AI 转写的是 ``ai-zh``。
        没有 url 的轨道（未登录时 B 站会这样返回）直接丢掉。
        """
        data = await self._get(f"{API}/x/player/v2", {"bvid": bvid, "cid": cid})
        subtitle = data.get("subtitle") if isinstance(data, dict) else None
        raw = (subtitle or {}).get("subtitles") if isinstance(subtitle, dict) else None
        tracks: list[dict[str, Any]] = []
        for item in raw or []:
            if not isinstance(item, dict):
                continue
            try:
                url = subtitle_url(str(item.get("subtitle_url") or ""))
            except BiliError:
                continue
            tracks.append(
                {
                    "id": int(item.get("id") or 0),
                    "lan": str(item.get("lan") or ""),
                    "label": str(item.get("lan_doc") or ""),
                    "url": url,
                }
            )
        return tracks

    async def subtitle_body(self, url: str) -> dict[str, Any]:
        """下载字幕 JSON（只走白名单地址，不跟随重定向，4 MB 上限）。

        共享 client 为了正常 API 是 ``follow_redirects=True``；字幕下载必须
        单独关掉，否则白名单域 302 到外域就能绕过校验。任何 3xx 直接拒绝。
        """
        target = subtitle_url(url)
        resp = await self.http.get(target, follow_redirects=False)
        if 300 <= resp.status_code < 400:
            raise BiliError(-1, "字幕地址不允许重定向")
        resp.raise_for_status()
        if len(resp.content) > SUBTITLE_MAX_BYTES:
            raise BiliError(-1, "字幕内容超过 4 MB")
        try:
            body = resp.json()
        except ValueError as exc:
            raise BiliError(-1, "字幕内容格式不正确") from exc
        if not isinstance(body, dict):
            raise BiliError(-1, "字幕内容格式不正确")
        return body

    async def playurl(self, bvid: str, cid: int) -> dict[str, Any]:
        await self.ensure_buvid()
        params: dict[str, Any] = {
            "bvid": bvid,
            "cid": cid,
            "fnval": 4048,
            "fnver": 0,
            "fourk": 1,
            "try_look": 1,
        }
        try:
            img, sub = await self._wbi_keys()
            data = await self._get(
                f"{API}/x/player/wbi/playurl", self._sign_wbi(params, img, sub)
            )
        except (BiliError, httpx.HTTPError):
            data = await self._get(f"{API}/x/player/playurl", params)
        return data


def pick_audio(dash: dict[str, Any], prefer_lossless: bool = True) -> dict[str, Any]:
    """从 dash 里挑一条最适合当音乐听的音轨。

    优先级：Hi-Res 无损 > 杜比全景声 > 最高码率普通音轨。
    """
    candidates: list[dict[str, Any]] = []

    def add(entry: dict[str, Any] | None, quality: str) -> None:
        if not entry:
            return
        url = entry.get("baseUrl") or entry.get("base_url")
        if not url:
            return
        backups = entry.get("backupUrl") or entry.get("backup_url") or []
        candidates.append(
            {
                "url": str(url),
                "backup_urls": [str(u) for u in backups if u],
                "bandwidth": int(entry.get("bandwidth") or 0),
                "codecs": str(entry.get("codecs") or ""),
                "mime": str(entry.get("mimeType") or entry.get("mime_type") or ""),
                "quality": quality,
            }
        )

    flac = dash.get("flac") or {}
    dolby = dash.get("dolby") or {}
    if prefer_lossless:
        add(flac.get("audio") if isinstance(flac, dict) else None, "Hi-Res 无损")
        add(dolby.get("audio") if isinstance(dolby, dict) else None, "杜比全景声")

    for item in dash.get("audio") or []:
        qid = int(item.get("id") or 0)
        add(item, AUDIO_QUALITY.get(qid, f"{qid}"))

    if not candidates:
        raise BiliError(-404, "该视频没有可用的音频流（可能是番剧或需大会员）")

    # 无损优先，其次码率
    def rank(c: dict[str, Any]) -> tuple[int, int]:
        return (1 if c["quality"] in {"Hi-Res 无损", "杜比全景声"} else 0, c["bandwidth"])

    candidates.sort(key=rank, reverse=True)
    return candidates[0]
