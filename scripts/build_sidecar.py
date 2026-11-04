#!/usr/bin/env python3
r"""把 Python 引擎打包成 Tauri 边车（sidecar）二进制。

    python scripts/build_sidecar.py              强制重建
    python scripts/build_sidecar.py --if-needed  源码没改动就跳过（tauri dev 用）

产物：desktop/src-tauri/binaries/bilimuse-server-<target-triple>[.exe]
Tauri 的 externalBin 要求文件名带 target triple，所以这里要算一次。

为什么用 Python 而不是 bash：Windows 的 PATH 里 `bash` 经常是
`C:\Windows\System32\bash.exe`（WSL 的启动器），它看不到 Windows 的 rustc，
也打不出 .exe，`beforeDevCommand` 会直接失败。这个脚本只用标准库，
Windows / Linux / macOS 行为一致；`scripts/build_sidecar.sh` 只是转调它的薄壳。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "desktop" / "src-tauri" / "binaries"
BUILD_DIR = ROOT / "build" / "pyinstaller"
IS_WINDOWS = os.name == "nt"
EXT = ".exe" if IS_WINDOWS else ""
# PyInstaller 的 --add-data 用 ';' 分隔源/目标（Windows）或 ':'（其余平台）。
DATA_SEP = ";" if IS_WINDOWS else ":"


def fail(message: str) -> NoReturn:
    print(f"[sidecar] {message}", file=sys.stderr)
    raise SystemExit(1)


def run(cmd: list[str], **kwargs) -> None:
    subprocess.run(cmd, check=True, **kwargs)


def resolve_python(candidate: str) -> str | None:
    """把 PATH 里的命令名（如 `python`）或绝对路径统一解析成可执行路径。

    关键是走 PATH 查找，不能对裸命令名做存在性判断，否则 CI 上会误判。
    """
    if not candidate:
        return None
    if "/" in candidate or "\\" in candidate:
        return candidate if Path(candidate).is_file() else None
    return shutil.which(candidate)


def venv_python() -> str | None:
    # Unix / Windows（Git Bash）两种 venv 布局都支持。
    for rel in ("bin/python", "Scripts/python.exe"):
        candidate = ROOT / ".venv" / rel
        if candidate.is_file():
            return str(candidate)
    return None


def pick_python() -> str:
    if os.environ.get("PYTHON"):
        resolved = resolve_python(os.environ["PYTHON"])
        if not resolved:
            fail(f"PYTHON={os.environ['PYTHON']} 不存在或不可执行")
        return resolved

    found = venv_python()
    if found:
        return found

    print("[sidecar] 没有 .venv，先建一个并装运行依赖")
    bootstrap = resolve_python("python3") or resolve_python("python")
    if not bootstrap:
        fail("找不到可用的 Python，请安装 Python 3.10+ 或设置 PYTHON")
    run([bootstrap, "-m", "venv", str(ROOT / ".venv")])
    found = venv_python()
    if not found:
        fail("创建 .venv 成功但没有找到 Python 解释器")
    run([found, "-m", "pip", "install", "-q", "-r", str(ROOT / "requirements.txt")])
    return found


def host_triple() -> str:
    triple = os.environ.get("TAURI_ENV_TARGET_TRIPLE", "").strip()
    if triple:
        return triple
    try:
        output = subprocess.run(
            ["rustc", "-vV"], capture_output=True, text=True, check=True
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return ""
    for line in output.splitlines():
        if line.startswith("host: "):
            return line[len("host: ") :].strip()
    return ""


def ensure_pyinstaller(python: str) -> None:
    probe = subprocess.run([python, "-c", "import PyInstaller"], capture_output=True)
    if probe.returncode == 0:
        return
    print("[sidecar] 安装 PyInstaller")
    run([python, "-m", "pip", "install", "-q", "pyinstaller>=6.6"])


def sidecar_sources() -> list[Path]:
    """参与 --if-needed 判定的文件：Python 包、依赖清单、打包入口。

    `__pycache__` 里的 .pyc 每次运行都会变，算进来会让 --if-needed 永远失效。
    """
    sources = [ROOT / "pyproject.toml", ROOT / "requirements.txt"]
    sources.append(ROOT / "scripts" / "sidecar_entry.py")
    for path in (ROOT / "bilimuse").rglob("*"):
        if path.is_file() and "__pycache__" not in path.parts:
            sources.append(path)
    return [p for p in sources if p.is_file()]


def is_up_to_date(target: Path) -> bool:
    if not target.is_file():
        return False
    target_mtime = target.stat().st_mtime
    return all(p.stat().st_mtime <= target_mtime for p in sidecar_sources())


def build(python: str, triple: str, target: Path) -> None:
    shutil.rmtree(BUILD_DIR, ignore_errors=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # PyInstaller 默认往 ~/.cache 写引导器缓存；放回 build/ 里，构建不污染用户目录，
    # 也不会在只读 HOME / 沙箱里失败。
    env = dict(os.environ)
    env.setdefault("PYINSTALLER_CONFIG_DIR", str(ROOT / "build" / "pyinstaller-cache"))

    cmd = [
        python,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onefile",
    ]
    # PyInstaller 的 --strip 在 Windows 上依赖额外 strip 工具，且收益很小；
    # Linux/macOS 用它把 onefile 的体积从 ~22 MB 降到 ~18 MB。
    if not IS_WINDOWS:
        cmd.append("--strip")
    cmd += [
        "--name",
        "bilimuse-server",
        "--distpath",
        str(BUILD_DIR / "dist"),
        "--workpath",
        str(BUILD_DIR / "work"),
        "--specpath",
        str(BUILD_DIR),
        "--paths",
        str(ROOT),
        # PyInstaller 会相对 specpath 解析相对路径，所以源目录必须给绝对路径。
        "--add-data",
        f"{(ROOT / 'bilimuse' / 'web').as_posix()}{DATA_SEP}bilimuse/web",
        "--collect-submodules",
        "uvicorn",
        "--hidden-import",
        "uvicorn.logging",
        "--hidden-import",
        "uvicorn.loops.auto",
        "--hidden-import",
        "uvicorn.protocols.http.auto",
        "--hidden-import",
        "uvicorn.protocols.websockets.auto",
        "--hidden-import",
        "uvicorn.lifespan.on",
        "--hidden-import",
        "uvicorn.protocols.http.httptools_impl",
        "--hidden-import",
        "httptools",
        # uvloop 不打包：它让这个 .so 变成 16MB（压缩后 5MB），多花 ~100ms 启动，
        # 换来的只是本地单条流上不到 1% 的 CPU —— 桌面版不划算。uvicorn 会自动
        # 退回 asyncio 事件循环，httptools 解析器仍然保留。
        "--exclude-module",
        "uvloop",
        "--log-level",
        "WARN",
        str(ROOT / "scripts" / "sidecar_entry.py"),
    ]

    print(f"[sidecar] 打包 Python 引擎（{triple}）")
    run(cmd, env=env)

    built = BUILD_DIR / "dist" / f"bilimuse-server{EXT}"
    if not built.is_file():
        fail(f"PyInstaller 没有产出 {built}")
    shutil.copy2(built, target)
    if not IS_WINDOWS:
        target.chmod(0o755)


def main(argv: list[str]) -> int:
    triple = host_triple()
    if not triple:
        fail("无法确定 target triple，请安装 rustc 或设置 TAURI_ENV_TARGET_TRIPLE")

    target = OUT_DIR / f"bilimuse-server-{triple}{EXT}"
    if "--if-needed" in argv[1:] and is_up_to_date(target):
        print(f"[sidecar] 已是最新，跳过：{target}")
        return 0

    python = pick_python()
    ensure_pyinstaller(python)
    build(python, triple, target)
    print(f"[sidecar] 完成：{target}")
    print(f"[sidecar] 自检：{target} --demo --desktop --port 0")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except subprocess.CalledProcessError as exc:
        print(f"[sidecar] 命令失败（exit {exc.returncode}）：{' '.join(map(str, exc.cmd))}",
              file=sys.stderr)
        raise SystemExit(exc.returncode or 1)
