//! 桌面壳登录桥：账号密码 / 短信验证码登录。
//!
//! B 站这两种登录都必须先过极验人机验证。自己实现验证码 SDK 既脆弱又容易踩
//! 风控，所以这里内嵌 B 站官方 H5 登录页（和安卓版同一套思路）：
//!
//!   页面点击「账号密码 / 短信验证码」
//!     -> POST /api/desktop/login/request
//!     -> 本模块轮询 /api/desktop/login/pending，领到请求后开一个独立的
//!        incognito webview 加载官方登录页
//!     -> 每隔 700ms 读一次该 webview 的 cookie，出现 SESSDATA 就回传
//!        POST /api/desktop/login/cookies，由 Python 边车校验并落盘
//!     -> 登录窗口自动关闭，前端轮询 status 看到 success 后刷新收藏夹
//!
//! 密码永远不会进入 BiliMuse 的代码：它在 B 站官方页面里提交，桌面壳只读
//! `.bilibili.com` 的 cookie。登录 webview 也没有任何 Tauri IPC 权限。
//!
//! 为什么用本地 HTTP 轮询而不是页面直接调 Tauri IPC：主窗口加载的是
//! `http://127.0.0.1:<随机端口>` 的远程页面，给远程页面开 IPC 需要配置
//! URLPattern 权限，而端口是内核随机分配的。走本地回环 HTTP 只多一个线程，
//! 却不需要放宽任何远程页面权限，也方便单独用 Python 测试整条登录编排。

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tauri::webview::PageLoadEvent;
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

const LOGIN_LABEL: &str = "desktop-login";
const LOGIN_URL: &str = concat!(
    "https://passport.bilibili.com/h5-app/passport/login",
    "?gourl=https%3A%2F%2Fwww.bilibili.com&lang=zh-CN"
);
// 官方 H5 页按手机浏览器设计；默认 UA 带 "wv" 容易被风控拦，换成普通 Chrome
const LOGIN_UA: &str = concat!(
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 ",
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
);

const REQUEST_POLL: Duration = Duration::from_millis(800);
const COOKIE_POLL: Duration = Duration::from_millis(700);
const WINDOW_TIMEOUT: Duration = Duration::from_secs(10 * 60);

/// 官方页默认落在短信表单；选「账号密码」时帮用户点一下切换按钮。
/// 选择器失效也没关系，用户仍可手动点击。
const PASSWORD_TAB_JS: &str = r#"
(function () {
  var tries = 0;
  var timer = setInterval(function () {
    var el = document.querySelector('.login-type-toggle');
    if (el) {
      // 页面默认是短信表单，切换按钮文案是「账号密码登录」；
      // 如果某些版本默认就是密码表单，文案会变成「手机短信登录」，不能再点回去。
      var text = (el.textContent || '').trim();
      if (text.indexOf('账号密码') >= 0 || text.indexOf('密码登录') >= 0) {
        el.click();
      }
      clearInterval(timer);
      return;
    }
    if (++tries > 12) { clearInterval(timer); }
  }, 500);
})();
"#;

fn active_slot() -> &'static Mutex<Option<String>> {
    static SLOT: OnceLock<Mutex<Option<String>>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

fn is_active() -> bool {
    active_slot()
        .lock()
        .map(|slot| slot.is_some())
        .unwrap_or(false)
}

fn set_active(value: Option<String>) {
    if let Ok(mut slot) = active_slot().lock() {
        *slot = value;
    }
}

/// 边车就绪后启动登录桥。只在桌面版调用一次。
pub fn run(app: AppHandle, port: u16) {
    thread::spawn(move || bridge_loop(app, port));
}

fn bridge_loop(app: AppHandle, port: u16) {
    let mut last_error: Option<Instant> = None;
    loop {
        // 已经有一个登录窗口时不要领新请求
        if is_active() {
            thread::sleep(Duration::from_millis(300));
            continue;
        }

        match http_request(
            port,
            "GET",
            "/api/desktop/login/pending",
            None,
            Duration::from_secs(4),
        ) {
            Ok((200, body)) => {
                if let Some((request_id, mode)) = parse_pending(&body) {
                    open_login_window(app.clone(), port, request_id, mode);
                }
            }
            Ok(_) => {}
            Err(err) => {
                let now = Instant::now();
                let should_log = last_error
                    .map(|last| now.duration_since(last) > Duration::from_secs(60))
                    .unwrap_or(true);
                if should_log {
                    crate::append_log(&format!("登录桥暂时连不上本地引擎：{err}"));
                    last_error = Some(now);
                }
            }
        }
        thread::sleep(REQUEST_POLL);
    }
}

fn open_login_window(app: AppHandle, port: u16, request_id: String, mode: String) {
    set_active(Some(request_id.clone()));

    let app_in_main = app.clone();
    let request_in_main = request_id.clone();
    let request_for_error = request_id.clone();
    let mode_in_main = mode.clone();

    let scheduled = app.run_on_main_thread(move || {
        if let Some(existing) = app_in_main.get_webview_window(LOGIN_LABEL) {
            // 理论上 active 槽位能防住；这里只是兜底，避免两个窗口抢 cookie
            let _ = existing.set_focus();
            cancel_request(port, &request_in_main, "登录窗口已经打开");
            set_active(None);
            return;
        }

        let url = match login_url().parse::<Url>() {
            Ok(url) => url,
            Err(err) => {
                crate::append_log(&format!("登录页地址无效：{err}"));
                cancel_request(port, &request_in_main, "登录页地址无效");
                set_active(None);
                return;
            }
        };

        let builder =
            WebviewWindowBuilder::new(&app_in_main, LOGIN_LABEL, WebviewUrl::External(url))
                .title("BiliMuse · 登录 B 站")
                // 1366×768 的笔记本也放得下（加上标题栏也不会超出屏幕）
                .inner_size(460.0, 700.0)
                .min_inner_size(360.0, 520.0)
                .resizable(true)
                .center()
                // 独立且不落地 cookie：不和主窗口共享登录态，也不会记住上一个账号
                .incognito(true)
                .user_agent(LOGIN_UA)
                .on_page_load(move |window, payload| {
                    let on_passport = payload
                        .url()
                        .host_str()
                        .map(|host| host.ends_with("passport.bilibili.com"))
                        .unwrap_or(false);
                    if payload.event() == PageLoadEvent::Finished
                        && mode_in_main == "password"
                        && on_passport
                    {
                        let _ = window.eval(PASSWORD_TAB_JS);
                    }
                });

        match builder.build() {
            Ok(window) => {
                crate::append_log(&format!(
                    "已打开 B 站官方登录窗口（request={request_in_main}，mode={mode}）"
                ));
                let _ = window.set_focus();
                spawn_monitor(app_in_main.clone(), port, request_in_main);
            }
            Err(err) => {
                crate::append_log(&format!("打开登录窗口失败：{err}"));
                cancel_request(port, &request_in_main, &format!("打开登录窗口失败：{err}"));
                set_active(None);
            }
        }
    });

    if let Err(err) = scheduled {
        crate::append_log(&format!("调度登录窗口失败：{err}"));
        cancel_request(port, &request_for_error, "调度登录窗口失败");
        set_active(None);
    }
}

/// 关掉登录窗口，并等它真正从窗口表里消失，避免下一个请求撞上同名 label。
fn close_login_window(app: &AppHandle, window: &WebviewWindow) {
    let _ = window.close();
    for _ in 0..20 {
        if app.get_webview_window(LOGIN_LABEL).is_none() {
            return;
        }
        thread::sleep(Duration::from_millis(50));
    }
}

fn spawn_monitor(app: AppHandle, port: u16, request_id: String) {
    thread::spawn(move || {
        let deadline = Instant::now() + WINDOW_TIMEOUT;
        let mut logged_cookies = false;

        loop {
            thread::sleep(COOKIE_POLL);

            let Some(window) = app.get_webview_window(LOGIN_LABEL) else {
                cancel_request(port, &request_id, "已关闭登录窗口");
                crate::append_log("登录窗口被关闭，取消本次登录");
                set_active(None);
                return;
            };

            // 前端取消 / 请求超时 / 边车已经给出结果：关窗收尾
            match fetch_status(port, &request_id) {
                Some(status)
                    if matches!(
                        status.as_str(),
                        "cancelled" | "expired" | "failed" | "success"
                    ) =>
                {
                    close_login_window(&app, &window);
                    set_active(None);
                    return;
                }
                _ => {}
            }

            if Instant::now() > deadline {
                cancel_request(port, &request_id, "登录窗口超时");
                crate::append_log("登录窗口超时，已自动关闭");
                close_login_window(&app, &window);
                set_active(None);
                return;
            }

            match read_bili_cookies(&window) {
                Ok(cookies) => {
                    if !cookies.is_empty() && !logged_cookies {
                        let names: Vec<&str> = cookies.keys().map(String::as_str).collect();
                        crate::append_log(&format!("登录窗口已产生 cookie：{}", names.join(", ")));
                        logged_cookies = true;
                    }
                    if !cookies.contains_key("SESSDATA") {
                        continue;
                    }
                    match post_cookies(port, &request_id, &cookies) {
                        Ok((200, body)) => {
                            let status = serde_json::from_str::<Value>(&body)
                                .ok()
                                .and_then(|value| {
                                    value
                                        .get("status")
                                        .and_then(Value::as_str)
                                        .map(str::to_string)
                                })
                                .unwrap_or_else(|| "unknown".to_string());
                            crate::append_log(&format!(
                                "官方登录窗口回传 cookie，校验结果：{status}"
                            ));
                            close_login_window(&app, &window);
                            set_active(None);
                            return;
                        }
                        Ok((code, _)) => {
                            crate::append_log(&format!("回传登录 cookie 失败：HTTP {code}"));
                            close_login_window(&app, &window);
                            set_active(None);
                            return;
                        }
                        Err(err) => {
                            // 本地边车偶发超时：窗口别关，下一轮再试
                            crate::append_log(&format!(
                                "回传登录 cookie 网络错误，稍后重试：{err}"
                            ));
                        }
                    }
                }
                Err(err) => {
                    crate::append_log(&format!("读取登录窗口 cookie 失败：{err}"));
                }
            }
        }
    });
}

// ---------------------------------------------------------------- webview cookie

fn read_bili_cookies(window: &WebviewWindow) -> Result<BTreeMap<String, String>, String> {
    // 用 cookies() 而不是 cookies_for_url()：macOS 上后者只保留 domain 完全相等的
    // cookie，会把 .bilibili.com 的 SESSDATA 过滤掉。登录窗口是 incognito 的，
    // 里面本来就只有 B 站/极验的 cookie，读全量再按域名筛选更稳。
    let cookies = window
        .cookies()
        .map_err(|err| format!("读取 webview cookie 失败：{err}"))?;

    let mut out = BTreeMap::new();
    for cookie in cookies {
        let domain = cookie.domain().unwrap_or("");
        if !domain.is_empty() && !is_bili_domain(domain) {
            continue;
        }
        let name = cookie.name();
        let value = cookie.value();
        if name.is_empty() || value.is_empty() || name.len() > 64 || value.len() > 8192 {
            continue;
        }
        out.insert(name.to_string(), value.to_string());
    }
    Ok(out)
}

fn is_bili_domain(domain: &str) -> bool {
    let domain = domain.trim_start_matches('.').to_ascii_lowercase();
    domain == "bilibili.com" || domain.ends_with(".bilibili.com")
}

fn login_url() -> String {
    // 留一个环境变量出口：官方页面改版时不用重新发版就能换入口
    std::env::var("BILIMUSE_LOGIN_URL").unwrap_or_else(|_| LOGIN_URL.to_string())
}

// ---------------------------------------------------------------- 本地 HTTP

fn http_request(
    port: u16,
    method: &str,
    path: &str,
    body: Option<&str>,
    timeout: Duration,
) -> Result<(u16, String), String> {
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(2))
        .map_err(|err| format!("连接本地引擎失败：{err}"))?;
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));

    let body = body.unwrap_or("");
    let request = format!(
        "{method} {path} HTTP/1.1\r\n\
         Host: 127.0.0.1:{port}\r\n\
         Connection: close\r\n\
         Accept: application/json\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         \r\n\
         {body}",
        body.as_bytes().len()
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|err| format!("发送本地请求失败：{err}"))?;
    stream
        .flush()
        .map_err(|err| format!("发送本地请求失败：{err}"))?;

    let mut raw = Vec::new();
    stream
        .read_to_end(&mut raw)
        .map_err(|err| format!("读取本地响应失败：{err}"))?;
    parse_http_response(&raw)
}

fn parse_http_response(raw: &[u8]) -> Result<(u16, String), String> {
    let split = find_bytes(raw, b"\r\n\r\n").ok_or_else(|| "本地响应缺少头部".to_string())?;
    let head = std::str::from_utf8(&raw[..split]).map_err(|err| err.to_string())?;
    let mut lines = head.split("\r\n");
    let status_line = lines.next().ok_or_else(|| "本地响应为空".to_string())?;
    let code = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| format!("无法解析状态行：{status_line}"))?;

    let mut chunked = false;
    let mut content_length: Option<usize> = None;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim().to_ascii_lowercase();
        let value = value.trim();
        match name.as_str() {
            "transfer-encoding" => {
                chunked = value.to_ascii_lowercase().contains("chunked");
            }
            "content-length" => content_length = value.parse::<usize>().ok(),
            _ => {}
        }
    }

    let mut body = raw[split + 4..].to_vec();
    if chunked {
        body = decode_chunked(&body)?;
    } else if let Some(length) = content_length {
        if body.len() > length {
            body.truncate(length);
        }
    }
    Ok((code, String::from_utf8_lossy(&body).into_owned()))
}

fn decode_chunked(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut rest = data;
    let mut out = Vec::new();
    loop {
        let end = find_bytes(rest, b"\r\n").ok_or_else(|| "chunked 响应不完整".to_string())?;
        let header = std::str::from_utf8(&rest[..end]).map_err(|err| err.to_string())?;
        let size_text = header.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_text, 16)
            .map_err(|_| format!("chunked 长度无效：{size_text}"))?;
        rest = &rest[end + 2..];
        if size == 0 {
            break;
        }
        if rest.len() < size + 2 {
            return Err("chunked 响应被截断".to_string());
        }
        out.extend_from_slice(&rest[..size]);
        rest = &rest[size + 2..];
    }
    Ok(out)
}

fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(0);
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn parse_pending(body: &str) -> Option<(String, String)> {
    let value: Value = serde_json::from_str(body).ok()?;
    let request_id = value.get("request_id")?.as_str()?.to_string();
    let mode = value
        .get("mode")
        .and_then(Value::as_str)
        .unwrap_or("sms")
        .to_string();
    Some((request_id, mode))
}

fn fetch_status(port: u16, request_id: &str) -> Option<String> {
    let path = format!("/api/desktop/login/status?request_id={request_id}");
    let (code, body) = http_request(port, "GET", &path, None, Duration::from_secs(4)).ok()?;
    if code != 200 {
        return None;
    }
    serde_json::from_str::<Value>(&body)
        .ok()?
        .get("status")
        .and_then(Value::as_str)
        .map(str::to_string)
}

fn post_cookies(
    port: u16,
    request_id: &str,
    cookies: &BTreeMap<String, String>,
) -> Result<(u16, String), String> {
    let payload = json!({
        "request_id": request_id,
        "cookies": cookies,
    })
    .to_string();
    http_request(
        port,
        "POST",
        "/api/desktop/login/cookies",
        Some(&payload),
        Duration::from_secs(30),
    )
}

fn cancel_request(port: u16, request_id: &str, message: &str) {
    let payload = json!({
        "request_id": request_id,
        "message": message,
    })
    .to_string();
    if let Err(err) = http_request(
        port,
        "POST",
        "/api/desktop/login/cancel",
        Some(&payload),
        Duration::from_secs(4),
    ) {
        crate::append_log(&format!("取消登录请求失败：{err}"));
    }
}

#[cfg(test)]
mod tests {
    use super::{decode_chunked, find_bytes, is_bili_domain, parse_http_response};
    use std::collections::BTreeMap;

    #[test]
    fn parses_content_length_response() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}";
        let (code, body) = parse_http_response(raw).unwrap();
        assert_eq!(code, 200);
        assert_eq!(body, "{\"ok\":true}");
    }

    #[test]
    fn parses_204_without_body() {
        let raw = b"HTTP/1.1 204 No Content\r\n\r\n";
        let (code, body) = parse_http_response(raw).unwrap();
        assert_eq!(code, 204);
        assert_eq!(body, "");
    }

    #[test]
    fn decodes_chunked_body() {
        let raw = b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"ok\r\n4\r\n\":1}\r\n0\r\n\r\n";
        let (code, body) = parse_http_response(raw).unwrap();
        assert_eq!(code, 200);
        assert_eq!(body, "{\"ok\":1}");
    }

    #[test]
    fn decodes_chunk_extensions() {
        let data = b"4;foo=bar\r\nabcd\r\n0\r\n\r\n";
        assert_eq!(decode_chunked(data).unwrap(), b"abcd");
    }

    #[test]
    fn matches_bilibili_domains_only() {
        assert!(is_bili_domain(".bilibili.com"));
        assert!(is_bili_domain("passport.bilibili.com"));
        assert!(!is_bili_domain("evilbilibili.com"));
        assert!(!is_bili_domain("bilibili.com.evil.dev"));
    }

    #[test]
    fn serde_serializes_cookie_map() {
        let mut cookies = BTreeMap::new();
        cookies.insert("SESSDATA".to_string(), "abc".to_string());
        let payload = serde_json::json!({
            "request_id": "r1",
            "cookies": cookies,
        })
        .to_string();
        assert!(payload.contains("\"SESSDATA\":\"abc\""));
    }

    #[test]
    fn find_bytes_works() {
        assert_eq!(find_bytes(b"abc\r\n\r\n", b"\r\n\r\n"), Some(3));
        assert_eq!(find_bytes(b"abc", b"\r\n"), None);
    }
}
