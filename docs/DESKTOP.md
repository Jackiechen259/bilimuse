# 桌面版（Tauri v2）

桌面版不是把 Python 逻辑重写一遍，而是给它套一个原生外壳：

![桌面版界面](../assets/desktop.png)

```
┌─────────────────────────────── BiliMuse.app / .deb ───────────────────────────────┐
│  Tauri (Rust)                                                                      │
│    · 一个原生窗口，加载本地启动页                                                   │
│    · 拉起并守护 Python 边车（bilimuse-server）                                      │
│    · 拿到端口后把窗口导航到 http://127.0.0.1:<随机端口>/                            │
│                                                                                    │
│  Python 边车（PyInstaller 打包，内含 FastAPI + BTR 引擎 + 前端静态文件）             │
│    · 扫码登录 / 官方窗口 cookie 校验 / 收藏夹 / playurl / 多 CDN 分块拉流            │
│                                                                                    │
│  登录窗口（按需创建，incognito，无 IPC 权限）                                       │
│    · B 站官方 H5 登录页：账号密码 / 短信验证码 / 极验                               │
└────────────────────────────────────────────────────────────────────────────────────┘
```

## 为什么不全部用 Rust 重写

BTR 引擎（节点探测、分块调度、对冲、断点续传、自适应并发）和 B 站 WBI 签名、
扫码登录的边界条件，都在 `bilimuse/` 里并且有测试覆盖。用 Rust 重写一遍等于把
最容易出错的部分重做一次，收益只是"少带一个 Python 运行时"。所以这里选择：

- **前端复用**：桌面窗口直接加载网页版同一套页面，登录弹窗按 `/api/state` 的
  `desktop` 字段决定是否显示账号密码 / 短信入口；
- **引擎零改动**：`btr.py` / `api.py` / `auth.py` 原样运行在边车里；
- **打包成单文件**：PyInstaller onefile，用户机器不需要装 Python。

代价是安装包会多出 ~20 MB（Python 运行时 + 依赖）。

## 目录

```
desktop/
├── package.json              # @tauri-apps/cli，npm/pnpm 脚本
├── web/index.html            # 启动页（等边车就绪时显示）
└── src-tauri/
    ├── Cargo.toml
    ├── build.rs              # 把 target triple 固化给运行时用
    ├── tauri.conf.json       # 窗口/打包/externalBin/updater 配置
    ├── capabilities/         # Tauri v2 权限（页面不用 IPC，只留 core:default）
    ├── icons/                # scripts/make_icon.py 生成
    ├── binaries/             # 边车产物（不入库）
    └── src/
        ├── main.rs           # 窗口 + 边车生命周期
        ├── login.rs          # 官方登录窗口 + cookie 桥（回环 HTTP 客户端）
        └── update.rs         # GitHub Release 自动更新（检查/下载/安装/重启）
.github/workflows/release.yml # 打 tag 后构建各平台安装包并发布 Release
scripts/
├── build_sidecar.sh          # PyInstaller 打包边车
├── sidecar_entry.py          # 打包入口（保留包内相对导入）
└── make_icon.py              # 生成图标
```

## 启动时序

1. Tauri 建窗口，加载 `desktop/web/index.html`（深色启动页，不会白屏）。
2. 后台线程执行 `bilimuse-server --desktop --port 0`。
3. Python 先 `bind()` 端口（端口 0 = 内核分配），再启动 uvicorn；
   uvicorn **真正开始 accept 之后**才往 stdout 打 `BILIMUSE_PORT=<port>`。
4. Rust 读到这一行，把窗口导航到 `http://127.0.0.1:<port>/`。
5. 关闭窗口 → 应用退出 → `kill` 边车；如果 Tauri 进程被强杀，
   边车会从 stdin 收到 EOF 自行退出（`launcher.watch_stdin`）。

端口用 0 而不是固定 8756：桌面版可能和网页版同时开着，也可能同时开两份，
固定端口会直接冲突。

## 随机播放整个收藏夹

桌面窗口加载的是网页版同一套前端，播放器左侧的 🔀 按钮用于随机播放（快捷键 `S`）：

1. 前端请求 `/api/folder/<id>/all`（夹内搜索时带上 `keyword`）；
2. 边车把该收藏夹的分页从第 1 页翻到最后，按 bvid 去重、过滤失效稿件，一次返回完整列表
   （`bilimuse/server.py` 的 `load_all_folder_items`）；
3. 前端把完整列表洗牌后作为播放队列，当前曲目放队首；页面列表本身仍然只渲染前 50 条；
4. 「下一首」/ 自动切歌从整个收藏夹里随机取，播到队尾会重新洗牌继续；
5. 即使列表只加载了前 50 条，随机范围仍是整个收藏夹；开启随机后播放器会显示
   「正在加载整个收藏夹…」和最终的「随机范围：整个收藏夹 · N 首」。

随机开关只保存在当前页面，不写配置；切换账号会清空整夹缓存。

## 账号密码 / 短信验证码登录

B 站的这两种登录都必须先过极验人机验证，自己实现验证码 SDK 既脆弱又容易踩风控。
桌面版的做法和安卓版一致：**把 B 站官方 H5 登录页放进一个独立 webview**，密码只在
官方页面里提交，桌面壳登录成功后只读 `.bilibili.com` 的 cookie，交给 Python 边车校验。

```
前端（127.0.0.1:<port>）        边车（FastAPI）              Tauri 桌面壳
   │ POST /api/desktop/login/request │                              │
   ├────────────────────────────────>│ 记下 pending 请求            │
   │                                 │<── GET /pending（轮询 800ms）─┤
   │                                 │─── 200 {request_id, mode} ───>│
   │                                 │                              │ 开 incognito
   │                                 │                              │ 官方登录窗口
   │                                 │                              │ 用户完成极验/密码/短信
   │                                 │                              │ 每 700ms 读 cookie
   │                                 │<── POST /cookies ────────────┤ 出现 SESSDATA
   │                                 │── nav 校验 → 写 session.json │
   │ GET /status?request_id=…        │                              │
   ├────────────────────────────────>│                              │
   │<── success + user ──────────────┤                              │ 关登录窗口
   │ 关弹窗、刷新收藏夹              │                              │
```

实现要点（`desktop/src-tauri/src/login.rs`）：

- 登录窗口用 `WebviewUrl::External` + `incognito(true)`。incognito 让每次登录都从干净
  的 cookie 开始，也不会和主窗口共享登录态；WebKitGTK / WebView2 / WKWebView 都支持。
- 窗口用手机 Chrome UA 加载 H5 登录页（`passport.bilibili.com/h5-app/passport/login`），
  选「账号密码」时会自动点一下页面里的切换按钮；点不到也能手动切。
- cookie 通过 Tauri 的 `Webview::cookies()` 读取（**能读到 HttpOnly 的 SESSDATA**，
  不依赖页面的 `document.cookie`），再按 `.bilibili.com` 域名筛选。没有用
  `cookies_for_url()`：macOS 上它只保留 domain 完全相等的 cookie，会漏掉父域 cookie。
- 登录 webview 不在 capability 列表里，没有任何 Tauri IPC 权限；主窗口也不开远程 IPC。
  桌面壳用最小的回环 HTTP 客户端（`std::net::TcpStream`，无额外依赖）和边车通信。
- Python 侧先临时替换 cookie 调 `nav` 校验，失败会恢复原登录态；成功才 `auth.save()`
  写 `~/.config/bilimuse/session.json`（0600）。密码从不进入这两个进程。
- 关掉登录窗口 / 关掉登录弹窗 -> 边车把请求标记为 cancelled，Rust 侧同步关窗；
  登录成功后状态不会被迟到的「关闭窗口」覆盖。

网页版（直接 `python -m bilimuse`，没有桌面壳）没有承载官方登录页的 webview，
所以 `/api/desktop/login/*` 直接返回 404，前端只显示扫码入口。

如果官方登录页改版导致选择器或入口失效，可以用 `BILIMUSE_LOGIN_URL=...` 覆盖登录页地址；
selector 失效不影响手动点击页面上的「账号密码登录」。

## 环境要求

Linux（Ubuntu/Debian）：

```bash
sudo apt install libwebkit2gtk-4.1-dev build-essential curl wget file \
  libxdo-dev libssl-dev libayatana-appindicator3-dev librsvg2-dev
```

另外需要 Rust（1.77+）、Node.js 18+ 和 Python 3.10+（打包边车用；
没有 `.venv` 时 `build_sidecar.sh` 会自动建一个并装依赖）。
macOS 需要 Xcode Command Line Tools，Windows 需要 WebView2（Win10/11 自带）和 MSVC 工具链。

## 开发

```bash
cd desktop
npm install          # 或 pnpm install

# 首次会先打包边车（约 30 秒），之后 --if-needed 会跳过
npx tauri dev

# 演示模式（不登录，播放本地合成音频）
npx tauri dev -- -- --demo
```

`tauri dev -- -- --demo` 里的第一段 `--` 是 tauri 的分隔符，第二段传给应用本身；
也可以直接 `BILIMUSE_DEMO=1 npx tauri dev`。

## 构建安装包

```bash
cd desktop
npx tauri build
```

产物：

- `desktop/src-tauri/target/release/bundle/deb/BiliMuse_0.1.0_amd64.deb`
- `desktop/src-tauri/target/release/bundle/appimage/BiliMuse_0.1.0_amd64.AppImage`

只想要可执行文件（不打包）：`npx tauri build --no-bundle`，产物在
`desktop/src-tauri/target/release/bilimuse-desktop`。

边车会由 `beforeBuildCommand` 自动重新打包，产物名必须带 target triple
（`bilimuse-server-x86_64-unknown-linux-gnu`），这是 Tauri `externalBin` 的约定。

打包时的两个取舍（都在 `scripts/build_sidecar.sh` 里）：

- `--strip`：uvloop 的 `.so` 未 strip 时有 16 MB（压缩后 5 MB），strip 后整个边车
  从 22.5 MB 回到 17.6 MB，启动也快 ~100 ms；
- `--exclude-module uvloop`：桌面版跑 asyncio 事件循环 + httptools 解析器。
  uvloop 只对"每秒几十个本地小请求"有帮助，折算到 4 MB/s 的播放不到 1% 的 CPU，
  不值得为它多背 5 MB 和启动时间。网页版装了就用（`/api/health` 会显示）。

另：`PYINSTALLER_CONFIG_DIR` 默认指到 `build/pyinstaller-cache`，构建不会往
`~/.cache` 里写东西。

## 日志

边车的 stderr 会写到：

```
~/.cache/bilimuse/desktop.log
```

Windows 是 `%LOCALAPPDATA%\bilimuse\desktop.log`。超过 2 MB 会在下次启动时清空。
启动失败时启动页会直接显示错误摘要和日志路径。

## 平台差异

| 平台 | 说明 |
| --- | --- |
| Linux | 已验证 `.deb` 构建与实跑（Ubuntu 24.04）；需要 webkit2gtk-4.1 |
| Windows | 用 `npx tauri build --bundles nsis`；边车产物带 `.exe` 后缀（脚本已处理），子进程用 `CREATE_NO_WINDOW` 防止弹黑框，退出时用 `taskkill /T` |
| macOS | 用 `npx tauri build --bundles app,dmg`；需要 `icon.icns`（`npx tauri icon icons/icon.png` 生成），边车要在 macOS 上重新打包 |

`tauri.conf.json` 里的 `bundle.targets` 是 `["deb", "appimage"]`（Linux 默认）。
在其他平台构建时用 `--bundles` 覆盖即可，不用改配置。

安装 deb：

```bash
sudo apt install ./BiliMuse_0.1.0_amd64.deb
```

安装后启动器里会出现 BiliMuse，可执行文件是 `/usr/bin/bilimuse-desktop`，
边车在 `/usr/bin/bilimuse-server`。

## 自动更新（GitHub Releases）

桌面壳使用 Tauri v2 的 updater 插件，更新端点固定为：

```
https://github.com/Jackiechen259/bilimuse/releases/latest/download/latest.json
```

应用启动 10 秒后检查一次，此后每 6 小时检查一次；发现新版本会弹原生确认框，
用户同意后下载安装包，**校验 Tauri 更新签名**再安装，最后提示重启。更新逻辑在
`desktop/src-tauri/src/update.rs`，不经过 Python 页面，也不开放远程 IPC 权限。
本次进程内点过「稍后」的版本不会再次打扰；可用 `BILIMUSE_DISABLE_UPDATES=1`
临时禁用检查。

### 签名密钥

`tauri.conf.json` 里的 `plugins.updater.pubkey` 是更新公钥；GitHub Actions 必须持有
对应私钥，否则构建不会产出可被客户端接受的签名包。开发机上已生成了一对密钥：

```
desktop/.tauri/bilimuse.key      # 私钥：不要提交，已加入 .gitignore
desktop/.tauri/bilimuse.key.pub  # 公钥：其内容已填入 tauri.conf.json
```

如果要重新生成（会要求同步更新 `pubkey`，并让已发布版本失效）：

```bash
cd desktop
npx tauri signer generate --ci --write-keys .tauri/bilimuse.key
cat .tauri/bilimuse.key.pub   # 填到 tauri.conf.json 的 plugins.updater.pubkey
```

把私钥内容加入仓库的 GitHub Actions Secrets：

| Secret | 值 |
| --- | --- |
| `TAURI_SIGNING_PRIVATE_KEY` | `desktop/.tauri/bilimuse.key` 文件内容（或私钥文件路径） |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | 私钥密码；生成时未设置密码则留空 |

私钥丢失后无法再给已发布客户端推送更新，只能让用户手动下载新版；请不要把
`desktop/.tauri/` 提交进 Git。

### 发布一个可自动更新的版本

1. 同步修改版本号：`desktop/src-tauri/tauri.conf.json`（必需）、
   `desktop/package.json`、`desktop/src-tauri/Cargo.toml`、`pyproject.toml`
   和 `bilimuse/config.py` 中的 `VERSION`。
2. 提交后打 tag 并推送：

   ```bash
   git tag v0.2.1
   git push origin v0.2.1
   ```

3. `.github/workflows/release.yml` 会为 Linux / Windows / macOS 构建安装包，并由
   `tauri-action` 生成带签名的 updater 产物和 `latest.json`，上传到一个**草稿**
   Release。
4. 在 GitHub 上检查草稿 Release，确认 `.sig` / `latest.json` 等资产齐全后点击
   Publish。发布后 `latest.json` 才会出现在 `releases/latest/download/` 下。

Linux 自动更新只保证 AppImage 安装方式可用；通过 `.deb` 安装的用户请从 Releases
手动下载新包。Windows 使用 NSIS 安装包，macOS 使用 `.app` 更新包。

## 已验证

在本仓库的开发机上实际跑通过：

- `cargo build` / `cargo fmt --check` 通过
- `npm run build`（`tauri build --bundles deb`）产出 17.9 MiB 安装包
- 启动后窗口加载真实界面，边车随机端口可用（`/api/health` 返回 0.1.0）
- 演示模式可解析音轨并返回 `206 Partial Content`（BTR 链路可用）
- 关窗后边车进程组在几十毫秒内退出（实测 17–30ms）
- 删掉边车后启动页显示「找不到 Python 引擎」错误提示；引擎崩溃时错误页会带出 stderr 最后几行
- 桌面登录桥的 HTTP 解析 / chunked / cookie 域过滤有 Rust 单元测试（7 个）；
  Python 侧登录编排有 `tests/test_desktop_login.py` 覆盖（发起 → 领取 → 回传 → 校验 → 落盘、失败回滚、取消、网页版 404）
- 在 Xvfb 下实跑桌面版：登录弹窗显示「扫码 / 账号密码 / 短信验证码」，点账号密码能拉起
  官方 H5 登录窗口并自动切到密码表单；点短信验证码显示手机号表单；点取消后两个窗口都关闭；
  日志里能看到从 incognito webview 读到的 B 站 cookie（`_uuid`）
- 无头 Chromium 实测随机播放：演示列表只加载 3 首、注入 6 首整夹列表后，开启随机得到
  6 首的播放队列，连点「下一首」能取到列表外的曲目；关闭随机后队列回到列表顺序
- Python 测试 `39 passed`，覆盖整夹翻页（逐页请求、去重、空页/重复页/页数上限）

## 已知限制

- 还没做单实例：重复启动会开两个窗口、两个边车（端口不同，不冲突）
- 还没做托盘/关闭到后台：关窗即退出，音乐随之停止
- 还没做系统媒体键：网页版已经接了 Media Session，桌面壳后续可以补一层原生快捷键
- 安装包比网页版大 ~20 MB，首次启动边车需要解包，约 0.5–1.5 秒
- Linux 的自动更新面向 AppImage；`.deb` 安装方式需要手动升级
- 账号密码 / 短信登录的**成功路径**需要真实账号和极验交互，开发机没有账号可用于自动化测试；
  已验证官方页加载、自动切换、cookie 读取和取消/超时，cookie 换登录态部分由 Python 单测覆盖

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| 启动页报"找不到 Python 引擎" | 没打包边车：`bash scripts/build_sidecar.sh` |
| 启动页报"本地引擎启动超时" | 看 `~/.cache/bilimuse/desktop.log`；常见是缺 OpenSSL 或杀毒软件拦截 |
| `cargo build` 报 `webkit2gtk-4.1` not found | 装 `libwebkit2gtk-4.1-dev` |
| 窗口全黑 | 边车起来了但前端没加载：确认 `bilimuse/web/` 被打进边车（`--add-data`） |
| 端口冲突 | 桌面版用随机端口，理论上不会；网页版手动 `--port` 时注意 |
| 点「账号密码 / 短信」没有窗口 | 看 `~/.cache/bilimuse/desktop.log` 里的「登录桥 / 登录窗口」日志；网页版没有桌面壳，本来就不支持 |
| 官方登录页白屏或提示浏览器不受支持 | 先用 `BILIMUSE_LOGIN_URL` 换官方入口；Linux WebKitGTK 对极验的兼容性随 B 站前端改版变化 |
| 窗口中登录成功但界面没刷新 | 确认边车收到 cookie：日志里应有「回传 cookie，校验结果：success」；失败时界面会显示 nav 校验原因 |
