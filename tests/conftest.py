"""pytest 公共配置：把项目根目录加入 sys.path，隔离配置/缓存目录。"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

_tmp = Path(tempfile.mkdtemp(prefix="bilimuse-test-"))
os.environ["BILIMUSE_CONFIG_DIR"] = str(_tmp / "config")
os.environ["BILIMUSE_CACHE_DIR"] = str(_tmp / "cache")
