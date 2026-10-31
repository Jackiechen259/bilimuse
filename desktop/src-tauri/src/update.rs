//! 桌面版自动更新。
//!
//! 更新源是 GitHub Release 的 `latest.json`（`tauri-action` 在发布时生成），
//! 安装包必须同时通过 Tauri 更新签名校验。这里用独立线程串行执行：
//!
//!   启动后等待几秒 → 检查 → 用户确认 → 下载并校验 → 安装 → 提示重启
//!
//! 没有把检查逻辑放到前端，是因为主窗口加载的是本地 Python 边车的 HTTP 页面，
//! 刻意不开放远程 IPC；更新检查和安装权限只留在 Rust 壳里。

use std::time::Duration;

use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::{Update, UpdaterExt};

/// 避开启动和播放首帧，等窗口与边车稳定后再访问网络。
const INITIAL_DELAY: Duration = Duration::from_secs(10);
/// 一次启动期间最多每 6 小时检查一次；版本跳过只作用于当前进程。
const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);
const WINDOW_TITLE: &str = "BiliMuse";

pub fn start(app: AppHandle) {
    if updater_disabled() {
        crate::append_log("自动更新已通过 BILIMUSE_DISABLE_UPDATES 禁用");
        return;
    }

    let spawn = std::thread::Builder::new()
        .name("bilimuse-updater".to_string())
        .spawn(move || run(app));
    if let Err(err) = spawn {
        crate::append_log(&format!("无法启动自动更新线程：{err}"));
    }
}

fn updater_disabled() -> bool {
    matches!(
        std::env::var("BILIMUSE_DISABLE_UPDATES").as_deref(),
        Ok("1") | Ok("true") | Ok("TRUE") | Ok("yes") | Ok("YES")
    )
}

fn run(app: AppHandle) {
    std::thread::sleep(INITIAL_DELAY);

    // 用户点「稍后」后，本次进程内不再为同一个版本弹窗。
    let mut skipped_version: Option<String> = None;
    loop {
        check_once(&app, &mut skipped_version);
        std::thread::sleep(CHECK_INTERVAL);
    }
}

fn check_once(app: &AppHandle, skipped_version: &mut Option<String>) {
    let updater = match app.updater() {
        Ok(updater) => updater,
        Err(err) => {
            crate::append_log(&format!("自动更新尚未配置：{err}"));
            return;
        }
    };

    let update = match tauri::async_runtime::block_on(updater.check()) {
        Ok(Some(update)) => update,
        Ok(None) => {
            crate::append_log("自动更新检查完成：当前已是最新版本");
            return;
        }
        Err(err) => {
            // 没发过 Release、断网、GitHub 偶发 5xx 都会走到这里；不打扰用户。
            crate::append_log(&format!("自动更新检查失败：{err}"));
            return;
        }
    };

    if skipped_version.as_deref() == Some(update.version.as_str()) {
        return;
    }

    crate::append_log(&format!(
        "发现新版本 {}（当前 {}，下载地址 {}）",
        update.version, update.current_version, update.download_url
    ));

    let notes = update
        .body
        .as_deref()
        .map(str::trim)
        .filter(|notes| !notes.is_empty())
        .map(|notes| {
            if notes.chars().count() > 800 {
                format!("{}\n…", notes.chars().take(800).collect::<String>())
            } else {
                notes.to_string()
            }
        })
        .unwrap_or_else(|| "本次更新暂无详细说明。".to_string());

    let accepted = app
        .dialog()
        .message(format!(
            "发现新版本 v{}（当前 v{}）。\n\n{}\n\n是否现在下载并安装？",
            update.version, update.current_version, notes
        ))
        .title("BiliMuse 更新")
        .kind(MessageDialogKind::Info)
        .buttons(MessageDialogButtons::OkCancelCustom(
            "下载并安装".to_string(),
            "稍后".to_string(),
        ))
        .blocking_show();

    if !accepted {
        crate::append_log(&format!("用户暂缓更新 {}", update.version));
        *skipped_version = Some(update.version.clone());
        return;
    }

    let version = update.version.clone();
    match install(app, &update) {
        Ok(()) => {
            crate::append_log(&format!("更新 {version} 已下载、校验并安装"));
            let restart = app
                .dialog()
                .message(format!(
                    "BiliMuse v{version} 已安装完成。\n\n现在重启应用以使用新版本吗？"
                ))
                .title("BiliMuse 更新完成")
                .kind(MessageDialogKind::Info)
                .buttons(MessageDialogButtons::YesNo)
                .blocking_show();
            if restart {
                crate::append_log("用户确认重启以完成更新");
                app.restart();
            } else {
                // 已经安装完成，只是尚未重启；本次进程内不要再触发一次下载。
                *skipped_version = Some(version.clone());
                set_window_title(
                    app,
                    &format!("{WINDOW_TITLE} · v{version} 已就绪，重启后生效"),
                );
            }
        }
        Err(err) => {
            crate::append_log(&format!("更新 {version} 下载/安装失败：{err}"));
            set_window_title(app, WINDOW_TITLE);
            app.dialog()
                .message(format!(
                    "更新 v{version} 下载或安装失败。\n\n{err}\n\n可以稍后重试，或从 GitHub Releases 手动下载安装包。"
                ))
                .title("BiliMuse 更新失败")
                .kind(MessageDialogKind::Error)
                .buttons(MessageDialogButtons::Ok)
                .blocking_show();
        }
    }
}

fn install(app: &AppHandle, update: &Update) -> Result<(), String> {
    let version = update.version.clone();
    let mut downloaded_bytes = 0_u64;
    let mut last_percent = u64::MAX;
    let mut told_unknown_size = false;

    tauri::async_runtime::block_on(update.download_and_install(
        |chunk_len, total| {
            downloaded_bytes = downloaded_bytes.saturating_add(chunk_len as u64);
            if let Some(total) = total.filter(|total| *total > 0) {
                let percent = (downloaded_bytes.saturating_mul(100) / total).min(100);
                if percent != last_percent {
                    last_percent = percent;
                    set_window_title(
                        app,
                        &format!("{WINDOW_TITLE} · 正在下载更新 v{version}（{percent}%）"),
                    );
                }
            } else if !told_unknown_size {
                told_unknown_size = true;
                set_window_title(app, &format!("{WINDOW_TITLE} · 正在下载更新 v{version}"));
            }
        },
        || {
            set_window_title(
                app,
                &format!("{WINDOW_TITLE} · 正在校验并安装更新 v{version}…"),
            );
        },
    ))
    .map_err(|err| err.to_string())
}

fn set_window_title(app: &AppHandle, title: &str) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_title(title);
    }
}
