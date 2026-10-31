//! BiliMuse 桌面壳。
//!
//! 结构很简单：Tauri 只负责「一个窗口 + 管住 Python 进程」，
//! 真正的登录、收藏夹、BTR 取流仍然跑在打包好的 Python 边车（sidecar）里。
//!
//! 启动流程：
//!   1. 立刻显示本地启动页（`web/index.html`），避免白屏；
//!   2. 拉起 `bilimuse-server --desktop --port 0`，端口交给内核分配；
//!   3. 边车在 uvicorn 真正 accept 之后往 stdout 打 `BILIMUSE_PORT=<port>`；
//!   4. 拿到端口再把窗口导航到 `http://127.0.0.1:<port>/`；
//!   5. 窗口关闭 / 应用退出时 kill 边车；边车那边还有 stdin EOF 兜底，
//!      就算桌面壳崩了也不会留下孤儿进程。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;

use tauri::{RunEvent, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

const READY_PREFIX: &str = "BILIMUSE_PORT=";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(40);
const LOG_MAX_BYTES: u64 = 2 * 1024 * 1024;

fn main() {
    let app = tauri::Builder::default()
        .setup(|app| {
            // 窗口自己建，这样能先给用户一个启动页，而不是等 Python 起来才见窗口
            let window =
                WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                    .title("BiliMuse")
                    .inner_size(1180.0, 760.0)
                    .min_inner_size(880.0, 560.0)
                    .center()
                    .build()?;

            let demo = std::env::args().any(|arg| arg == "--demo")
                || std::env::var("BILIMUSE_DEMO").as_deref() == Ok("1");

            thread::spawn(move || start_backend(&window, demo));
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("BiliMuse 启动失败");

    app.run(|_handle, event| {
        if let RunEvent::Exit = event {
            shutdown_sidecar();
        }
    });
}

// ---------------------------------------------------------------- 边车进程

fn sidecar_slot() -> &'static Mutex<Option<Child>> {
    static SLOT: OnceLock<Mutex<Option<Child>>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

fn shutdown_sidecar() {
    if let Ok(mut slot) = sidecar_slot().lock() {
        if let Some(mut child) = slot.take() {
            append_log(&format!("应用退出，停止本地引擎（pid={}）", child.id()));
            terminate(&mut child);
        }
    }
}

/// 依次尝试几个可能的位置：打包目录 → 开发目录。
fn sidecar_candidates() -> Vec<PathBuf> {
    let triple = env!("BILIMUSE_TARGET");
    let exe_name = if cfg!(windows) {
        "bilimuse-server.exe"
    } else {
        "bilimuse-server"
    };
    let suffixed = format!(
        "bilimuse-server-{triple}{}",
        if cfg!(windows) { ".exe" } else { "" }
    );

    let mut out = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            out.push(dir.join(exe_name));
            out.push(dir.join(&suffixed));
            // macOS .app：Contents/MacOS 同级再往上一层是 Resources
            out.push(dir.join("..").join("Resources").join(exe_name));
        }
    }
    // cargo tauri dev / 手动 cargo run 时，二进制还在 binaries/ 下面
    out.push(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries")
            .join(suffixed),
    );
    out
}

fn log_file() -> PathBuf {
    let base = if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
    } else {
        std::env::var_os("XDG_CACHE_HOME").map(PathBuf::from)
    }
    .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".cache")))
    .unwrap_or_else(std::env::temp_dir);

    let dir = base.join("bilimuse");
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("desktop.log");
    if std::fs::metadata(&path)
        .map(|meta| meta.len() > LOG_MAX_BYTES)
        .unwrap_or(false)
    {
        let _ = std::fs::remove_file(&path);
    }
    path
}

fn append_log(message: &str) {
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_file())
    {
        let _ = writeln!(file, "[desktop] {message}");
    }
}

fn read_log_tail(max_lines: usize) -> String {
    let Ok(text) = std::fs::read_to_string(log_file()) else {
        return String::new();
    };
    let lines: Vec<&str> = text.lines().rev().take(max_lines).collect();
    lines.into_iter().rev().collect::<Vec<_>>().join("\n")
}

/// 拉起边车并等它报端口；返回端口，失败时返回给用户看的说明。
fn spawn_backend(demo: bool) -> Result<u16, String> {
    let path = sidecar_candidates()
        .into_iter()
        .find(|candidate| candidate.is_file())
        .ok_or_else(|| {
            "找不到 Python 引擎（bilimuse-server）。\n\n先运行：bash scripts/build_sidecar.sh"
                .to_string()
        })?;

    let mut command = Command::new(&path);
    command.arg("--desktop").arg("--port").arg("0");
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // 自成进程组：PyInstaller onefile 会再 fork 一层，退出时好整组收掉
        command.process_group(0);
    }
    if demo {
        command.arg("--demo");
    }
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = command
        .spawn()
        .map_err(|err| format!("无法启动引擎：{}\n{err}", path.display()))?;

    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "引擎没有 stdout".to_string())?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| "引擎没有 stderr".to_string())?;

    // stderr 落到日志文件，出问题时启动页会提示路径
    let log_path = log_file();
    thread::spawn(move || {
        let mut sink = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
            .ok();
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            if cfg!(debug_assertions) {
                eprintln!("[bilimuse] {line}");
            }
            if let Some(file) = sink.as_mut() {
                let _ = writeln!(file, "{line}");
            }
        }
    });

    let (tx, rx) = mpsc::channel::<u16>();
    thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let line = line.trim().to_string();
            if let Some(rest) = line.strip_prefix(READY_PREFIX) {
                if let Ok(port) = rest.trim().parse::<u16>() {
                    let _ = tx.send(port);
                    return;
                }
            }
        }
        // 进程提前退出时 sender 被丢弃，recv 会返回 Err
    });

    match rx.recv_timeout(STARTUP_TIMEOUT) {
        Ok(port) => {
            if let Ok(mut slot) = sidecar_slot().lock() {
                *slot = Some(child);
            }
            Ok(port)
        }
        Err(err) => {
            terminate(&mut child);
            // stderr 线程可能还在把最后几行刷进日志，稍等一下再取尾巴
            std::thread::sleep(Duration::from_millis(150));
            let tail = read_log_tail(12);
            let headline = match err {
                // 进程提前退出：通常是依赖缺失或端口无法绑定，日志里有真正原因
                RecvTimeoutError::Disconnected => "本地引擎启动失败（进程已退出）。",
                RecvTimeoutError::Timeout => "本地引擎启动超时。",
            };
            let mut message = format!("{headline}\n\n日志：{}", log_file().display());
            if !tail.is_empty() {
                message.push_str("\n\n");
                message.push_str(&tail);
            }
            Err(message)
        }
    }
}

/// 结束边车。Unix 上按进程组杀：onefile 的引导进程和真正的服务进程是两层，
/// 只 kill 父进程的话，子进程要等 stdin EOF 才退。
fn terminate(child: &mut Child) {
    #[cfg(unix)]
    {
        let pid = child.id() as i32;
        unsafe {
            libc::kill(-pid, libc::SIGKILL);
        }
    }
    #[cfg(windows)]
    {
        let pid = child.id().to_string();
        let _ = Command::new("taskkill")
            .args(["/F", "/T", "/PID", &pid])
            .output();
    }
    let _ = child.kill();
    let _ = child.wait();
}

// ---------------------------------------------------------------- 启动流程

fn start_backend(window: &WebviewWindow, demo: bool) {
    match spawn_backend(demo) {
        Ok(port) => {
            let url = format!("http://127.0.0.1:{port}/");
            match url.parse::<Url>() {
                Ok(url) => {
                    if let Err(err) = window.navigate(url) {
                        report_error(window, &format!("窗口加载失败：{err}"));
                    }
                }
                Err(err) => report_error(window, &format!("地址无效：{err}")),
            }
        }
        Err(message) => {
            append_log(&message);
            report_error(window, &message);
        }
    }
}

fn report_error(window: &WebviewWindow, message: &str) {
    // serde_json 负责转义换行/引号，避免 eval 里的字符串把脚本搞坏
    let literal = serde_json::to_string(message).unwrap_or_else(|_| "\"启动失败\"".to_string());
    let _ = window.eval(format!(
        "window.__bilimuseError && window.__bilimuseError({literal});"
    ));
    let _ = window.set_title("BiliMuse · 启动失败");
}
