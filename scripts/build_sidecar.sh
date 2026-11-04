#!/usr/bin/env bash
# 薄壳：真正的打包逻辑在 scripts/build_sidecar.py（Windows 上没有可用的 bash）。
#
#   scripts/build_sidecar.sh              强制重建
#   scripts/build_sidecar.sh --if-needed  源码没改动就跳过（tauri dev 用）
#
# 保留这个入口是为了 CI 和文档里的 `bash scripts/build_sidecar.sh` 继续可用。
# Windows 上 tauri.windows.conf.json 直接调用 `python scripts/build_sidecar.py`：
# PATH 里的 `bash` 常常是 C:\Windows\System32\bash.exe（WSL 启动器），
# 它看不到 Windows 的 rustc，也打不出 .exe。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null 2>&1 || PY=python

exec "$PY" "$DIR/build_sidecar.py" "$@"
