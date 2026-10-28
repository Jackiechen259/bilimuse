"""登录态持久化。

只存 cookie 与公开展示信息，文件权限 0600，不记录密码。
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from .config import CONFIG_DIR, SESSION_FILE


@dataclass
class Session:
    cookies: dict[str, str] = field(default_factory=dict)
    mid: int = 0
    uname: str = ""
    face: str = ""
    vip: bool = False
    updated_at: float = 0.0

    @property
    def logged_in(self) -> bool:
        return bool(self.cookies.get("SESSDATA")) and self.mid > 0

    def cookie_header(self) -> str:
        return "; ".join(f"{k}={v}" for k, v in self.cookies.items())

    def public(self) -> dict[str, Any]:
        return {
            "logged_in": self.logged_in,
            "mid": self.mid,
            "uname": self.uname,
            "face": self.face,
            "vip": self.vip,
            "updated_at": self.updated_at,
        }


def load() -> Session:
    if not SESSION_FILE.exists():
        return Session()
    try:
        raw = json.loads(SESSION_FILE.read_text("utf-8"))
        return Session(
            cookies={str(k): str(v) for k, v in raw.get("cookies", {}).items()},
            mid=int(raw.get("mid", 0)),
            uname=str(raw.get("uname", "")),
            face=str(raw.get("face", "")),
            vip=bool(raw.get("vip", False)),
            updated_at=float(raw.get("updated_at", 0)),
        )
    except (OSError, ValueError):
        return Session()


def save(session: Session) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    session.updated_at = time.time()
    payload = {
        "cookies": session.cookies,
        "mid": session.mid,
        "uname": session.uname,
        "face": session.face,
        "vip": session.vip,
        "updated_at": session.updated_at,
    }
    tmp = SESSION_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), "utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(SESSION_FILE)


def clear() -> None:
    try:
        SESSION_FILE.unlink()
    except FileNotFoundError:
        pass
