"""全局配置与路径。

所有可调项都能用环境变量覆盖，方便部署到别的机器：
    BILIMUSE_HOST / BILIMUSE_PORT / BILIMUSE_CONCURRENCY ...
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

APP_NAME = "bilimuse"
VERSION = "0.1.0"

CONFIG_DIR = Path(os.environ.get("BILIMUSE_CONFIG_DIR", Path.home() / ".config" / APP_NAME))
SESSION_FILE = CONFIG_DIR / "session.json"
CACHE_DIR = Path(os.environ.get("BILIMUSE_CACHE_DIR", Path.home() / ".cache" / APP_NAME))

WEB_DIR = Path(__file__).resolve().parent / "web"

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
    """运行时设置，可通过 /api/settings 热更新（仅内存）。"""

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

    def clamp(self) -> None:
        self.concurrency = max(1, min(self.concurrency, 64))
        self.chunk_size = max(64 * 1024, min(self.chunk_size, 8 * 1024 * 1024))


settings = Settings()
