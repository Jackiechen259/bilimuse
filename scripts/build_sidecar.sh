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

# 把 PATH 里的命令名（如 `python`）或绝对路径统一解析成可执行路径。
# 关键是使用 `command -v`，不能对裸命令名做 `[[ -x python ]]`，否则 CI 会误判。
resolve_python() {
  local candidate="$1"
  if [[ -z "$candidate" ]]; then
    return 1
  fi
  if [[ "$candidate" == */* ]]; then
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    return 1
  fi
  local resolved
  resolved="$(command -v "$candidate" 2>/dev/null || true)"
  if [[ -n "$resolved" && -x "$resolved" ]]; then
    printf '%s\n' "$resolved"
    return 0
  fi
  return 1
}

venv_python() {
  local candidate
  # Unix / Windows（Git Bash）两种 venv 布局都支持。
  for candidate in "$ROOT/.venv/bin/python" "$ROOT/.venv/Scripts/python.exe"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

if [[ -n "${PYTHON:-}" ]]; then
  if ! PY="$(resolve_python "$PYTHON")"; then
    echo "[sidecar] PYTHON=$PYTHON 不存在或不可执行" >&2
    exit 1
  fi
elif PY="$(venv_python)"; then
  :
else
  echo "[sidecar] 没有 .venv，先建一个并装运行依赖"
  BOOTSTRAP_PY="$(resolve_python python3 || resolve_python python || true)"
  if [[ -z "$BOOTSTRAP_PY" ]]; then
    echo "[sidecar] 找不到可用的 Python，请安装 Python 3.10+ 或设置 PYTHON" >&2
    exit 1
  fi
  "$BOOTSTRAP_PY" -m venv "$ROOT/.venv"
  if ! PY="$(venv_python)"; then
    echo "[sidecar] 创建 .venv 成功但没有找到 Python 解释器" >&2
    exit 1
  fi
  "$PY" -m pip install -q -r "$ROOT/requirements.txt"
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

# PyInstaller 的 --strip 在 Windows 上依赖额外 strip 工具，且收益很小；
# Linux/macOS 用它把 onefile 的体积从 ~22 MB 降到 ~18 MB。
STRIP_ARGS=()
if [[ "$EXT" != ".exe" ]]; then
  STRIP_ARGS=(--strip)
fi

echo "[sidecar] 打包 Python 引擎（${TRIPLE}）"
# uvloop 不打包：它让这个 .so 变成 16MB（压缩后 5MB），多花 ~100ms 启动，
# 换来的只是本地单条流上不到 1% 的 CPU —— 桌面版不划算。uvicorn 会自动退回
# asyncio 事件循环，httptools 解析器仍然保留。服务端/网页版装了 uvloop 就用。
# add-data 用相对路径：Windows Git Bash 会把带分隔符的绝对 POSIX 路径转换坏。
"$PY" -m PyInstaller \
  --noconfirm \
  --clean \
  --onefile \
  "${STRIP_ARGS[@]}" \
  --name bilimuse-server \
  --distpath "$BUILD_DIR/dist" \
  --workpath "$BUILD_DIR/work" \
  --specpath "$BUILD_DIR" \
  --paths "$ROOT" \
  --add-data "bilimuse/web${DATA_SEP}bilimuse/web" \
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
