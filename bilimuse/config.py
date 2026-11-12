"""全局配置与路径。

所有可调项都能用环境变量覆盖，方便部署到别的机器：
    BILIMUSE_HOST / BILIMUSE_PORT / BILIMUSE_CONCURRENCY ...
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

APP_NAME = "bilimuse"
VERSION = "0.2.4"
THEMES = ("ink", "paper", "cream")


def _default_config_dir() -> Path:
    """各平台的配置目录；桌面版打包后也能落在系统习惯的位置。"""
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
        return base / APP_NAME
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / APP_NAME
    return Path.home() / ".config" / APP_NAME


def _default_cache_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
        return base / APP_NAME / "cache"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Caches" / APP_NAME
    return Path.home() / ".cache" / APP_NAME


def _web_dir() -> Path:
    """前端静态文件位置。

    PyInstaller 打包后 `__file__` 指向解包目录里的 pyc，静态文件被放在
    `sys._MEIPASS/bilimuse/web`（见 scripts/build_sidecar.sh 的 --add-data）。
    """
    bundled = getattr(sys, "_MEIPASS", None)
    if bundled:
        return Path(bundled) / "bilimuse" / "web"
    return Path(__file__).resolve().parent / "web"


CONFIG_DIR = Path(os.environ.get("BILIMUSE_CONFIG_DIR") or _default_config_dir())
SESSION_FILE = CONFIG_DIR / "session.json"
SETTINGS_FILE = CONFIG_DIR / "settings.json"
CACHE_DIR = Path(os.environ.get("BILIMUSE_CACHE_DIR") or _default_cache_dir())

WEB_DIR = _web_dir()

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
REFERER = "https://www.bilibili.com"

API = "https://api.bilibili.com"
PASSPORT = "https://passport.bilibili.com"


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class Settings:
    """可热更新的运行设置，并持久化到配置目录。

    环境变量仍然是初始默认值；用户在设置界面里改过的项会写到
    ``CONFIG_DIR/settings.json``，下次启动继续生效。桌面版端口是随机的，
    因此不能依赖浏览器 localStorage（localhost 的不同端口是不同 origin）。
    """

    host: str = field(default_factory=lambda: os.environ.get("BILIMUSE_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: _env_int("BILIMUSE_PORT", 8756))

    # BTR 引擎
    btr_enabled: bool = field(default_factory=lambda: _env_bool("BILIMUSE_BTR", True))
    concurrency: int = field(default_factory=lambda: _env_int("BILIMUSE_CONCURRENCY", 8))
    max_concurrency: int = field(default_factory=lambda: _env_int("BILIMUSE_MAX_CONCURRENCY", 32))
    chunk_size: int = field(default_factory=lambda: _env_int("BILIMUSE_CHUNK_SIZE", 512 * 1024))
    prefetch_chunks: int = field(default_factory=lambda: _env_int("BILIMUSE_PREFETCH_CHUNKS", 2))

    # 音质偏好：flac > dolby > 最高码率 m4a
    prefer_lossless: bool = True

    # 播放器偏好
    volume: float = 0.9
    autoplay_next: bool = True

    # 收藏夹显示：存被隐藏的收藏夹 ID，新收藏夹默认可见
    hidden_folders: list[int] = field(default_factory=list)

    # 界面风格：墨 / 纸 / 米。None = 还没选过，前端会沿用本机缓存并回写。
    theme: str | None = None

    def clamp(self) -> None:
        self.concurrency = max(1, min(self.concurrency, 64))
        self.chunk_size = max(64 * 1024, min(self.chunk_size, 8 * 1024 * 1024))
        self.volume = max(0.0, min(float(self.volume), 1.0))
        if self.theme not in THEMES:
            self.theme = None
        self.hidden_folders = sorted(
            {
                int(fid)
                for fid in self.hidden_folders
                if isinstance(fid, int) and not isinstance(fid, bool)
            }
        )

    def public(self) -> dict[str, object]:
        """返回给前端设置页的可调项。"""
        return {
            "btr_enabled": self.btr_enabled,
            "concurrency": self.concurrency,
            "chunk_size": self.chunk_size,
            "prefer_lossless": self.prefer_lossless,
            "volume": round(self.volume, 2),
            "autoplay_next": self.autoplay_next,
            "hidden_folders": list(self.hidden_folders),
            "theme": self.theme,
        }

    def load(self) -> None:
        """从配置目录恢复界面设置；损坏或旧文件不影响启动。"""
        try:
            raw = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            self.clamp()
            return
        if not isinstance(raw, dict):
            self.clamp()
            return
        if isinstance(raw.get("btr_enabled"), bool):
            self.btr_enabled = raw["btr_enabled"]
        for key, low, high in (
            ("concurrency", 1, 64),
            ("chunk_size", 64 * 1024, 8 * 1024 * 1024),
        ):
            value = raw.get(key)
            if isinstance(value, (int, float)):
                setattr(self, key, max(low, min(int(value), high)))
        if isinstance(raw.get("prefer_lossless"), bool):
            self.prefer_lossless = raw["prefer_lossless"]
        if isinstance(raw.get("volume"), (int, float)):
            self.volume = float(raw["volume"])
        if isinstance(raw.get("autoplay_next"), bool):
            self.autoplay_next = raw["autoplay_next"]
        if isinstance(raw.get("theme"), str) and raw["theme"] in THEMES:
            self.theme = raw["theme"]
        if isinstance(raw.get("hidden_folders"), list):
            self.hidden_folders = [
                fid
                for fid in raw["hidden_folders"]
                if isinstance(fid, int) and not isinstance(fid, bool)
            ]
        self.clamp()

    def save(self) -> bool:
        """原子写入设置文件（0600）。写入失败时仍保留本次运行时设置。"""
        target = SETTINGS_FILE
        tmp = target.with_name(f".{target.name}.tmp")
        try:
            CONFIG_DIR.mkdir(parents=True, exist_ok=True)
            tmp.write_text(
                json.dumps(self.public(), ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            os.chmod(tmp, 0o600)
            os.replace(tmp, target)
            return True
        except OSError:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
            return False


settings = Settings()
settings.load()
