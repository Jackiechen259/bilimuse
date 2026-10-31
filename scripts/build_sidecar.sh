#!/usr/bin/env bash
# 把 Python 引擎打包成 Tauri 边车（sidecar）二进制。
#
#   scripts/build_sidecar.sh            强制重建
#   scripts/build_sidecar.sh --if-needed  源码没改动就跳过（tauri dev 用）
#
# 产物：desktop/src-tauri/binaries/bilimuse-server-<target-triple>[.exe]
# Tauri 的 externalBin 要求文件名带 target triple，所以这里要算一次。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if_needed=0
if [[ "${1:-}" == "--if-needed" ]]; then
  if_needed=1
fi

TRIPLE="${TAURI_ENV_TARGET_TRIPLE:-$(rustc -vV | sed -n 's/^host: //p')}"
if [[ -z "$TRIPLE" ]]; then
  echo "[sidecar] 无法确定 target triple，请安装 rustc 或设置 TAURI_ENV_TARGET_TRIPLE" >&2
  exit 1
fi

if [[ -n "${PYTHON:-}" ]]; then
  PY="$PYTHON"
elif [[ -x "$ROOT/.venv/bin/python" ]]; then
  PY="$ROOT/.venv/bin/python"
else
  echo "[sidecar] 没有 .venv，先建一个并装运行依赖"
  python3 -m venv "$ROOT/.venv"
  PY="$ROOT/.venv/bin/python"
  "$PY" -m pip install -q -r "$ROOT/requirements.txt"
fi
if [[ ! -x "$PY" ]]; then
  echo "[sidecar] 找不到可用的 Python，请安装 Python 3.10+ 或设置 PYTHON" >&2
  exit 1
fi

OUT_DIR="$ROOT/desktop/src-tauri/binaries"
BUILD_DIR="$ROOT/build/pyinstaller"
EXT=""
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) EXT=".exe" ;;
esac
TARGET="$OUT_DIR/bilimuse-server-$TRIPLE$EXT"

if [[ $if_needed -eq 1 && -f "$TARGET" ]]; then
  changed="$(find bilimuse pyproject.toml requirements.txt -newer "$TARGET" -print -quit 2>/dev/null || true)"
  if [[ -z "$changed" ]]; then
    echo "[sidecar] 已是最新，跳过：$TARGET"
    exit 0
  fi
fi

if ! "$PY" -c 'import PyInstaller' >/dev/null 2>&1; then
  echo "[sidecar] 安装 PyInstaller"
  "$PY" -m pip install -q "pyinstaller>=6.6"
fi

SEP=':'
DATA_SEP="$SEP"
if [[ "$EXT" == ".exe" ]]; then
  DATA_SEP=';'
fi

rm -rf "$BUILD_DIR"
mkdir -p "$OUT_DIR"

# PyInstaller 默认往 ~/.cache 写引导器缓存；放回 build/ 里，构建不污染用户目录，
# 也不会在只读 HOME / 沙箱里失败
export PYINSTALLER_CONFIG_DIR="${PYINSTALLER_CONFIG_DIR:-$ROOT/build/pyinstaller-cache}"

echo "[sidecar] 打包 Python 引擎（$TRIPLE）"
# uvloop 不打包：它让这个 .so 变成 16MB（压缩后 5MB），多花 ~100ms 启动，
# 换来的只是本地单条流上不到 1% 的 CPU —— 桌面版不划算。uvicorn 会自动退回
# asyncio 事件循环，httptools 解析器仍然保留。服务端/网页版装了 uvloop 就用。
"$PY" -m PyInstaller \
  --noconfirm \
  --clean \
  --onefile \
  --strip \
  --name bilimuse-server \
  --distpath "$BUILD_DIR/dist" \
  --workpath "$BUILD_DIR/work" \
  --specpath "$BUILD_DIR" \
  --paths "$ROOT" \
  --add-data "$ROOT/bilimuse/web${DATA_SEP}bilimuse/web" \
  --collect-submodules uvicorn \
  --hidden-import uvicorn.logging \
  --hidden-import uvicorn.loops.auto \
  --hidden-import uvicorn.protocols.http.auto \
  --hidden-import uvicorn.protocols.websockets.auto \
  --hidden-import uvicorn.lifespan.on \
  --hidden-import uvicorn.protocols.http.httptools_impl \
  --hidden-import httptools \
  --exclude-module uvloop \
  --log-level WARN \
  "$ROOT/scripts/sidecar_entry.py"

install -m 755 "$BUILD_DIR/dist/bilimuse-server$EXT" "$TARGET"
echo "[sidecar] 完成：$TARGET"
echo "[sidecar] 自检：$TARGET --demo --desktop --port 0"
