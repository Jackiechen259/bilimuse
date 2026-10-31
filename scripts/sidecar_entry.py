"""PyInstaller 入口：让打包后的二进制仍然是一个正常的包内调用。

不能直接把 `bilimuse/__main__.py` 丢给 PyInstaller —— 那个文件用的是相对导入
（`from .config import ...`），作为脚本执行会直接 ImportError。
"""

from __future__ import annotations

import sys

from bilimuse.__main__ import main

if __name__ == "__main__":
    sys.exit(main())
