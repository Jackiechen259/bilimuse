#!/usr/bin/env bash
# 一键启动：首次运行自动建 venv 并装依赖
set -euo pipefail

cd "$(dirname "$0")"
PY="${PYTHON:-python3.12}"
command -v "$PY" >/dev/null 2>&1 || PY=python3

if [ ! -d .venv ]; then
  echo "[bilimuse] 创建虚拟环境 .venv"
  "$PY" -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
if [ ! -f .venv/.deps_ok ]; then
  echo "[bilimuse] 安装依赖"
  pip install -q --upgrade pip
  pip install -q -r requirements.txt
  touch .venv/.deps_ok
fi

exec python -m bilimuse "$@"
