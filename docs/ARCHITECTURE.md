# 架构与设计决策

## 为什么是"本地服务端 + 浏览器 UI"

浏览器的 `<audio>` 只接受一条连接：它自己不会把一段音频拆成多段并发下载，也不会带 `Referer` 之外的鉴权头。想做多线程，就必须有一个能自己发 HTTP 请求的进程。

三种选择：

| 方案 | 成本 | 结果 |
| --- | --- | --- |
| 纯前端 | 低 | 受限于浏览器单连接，BTR 无从谈起 |
| 本地服务端 + 浏览器 UI | 中 | 引擎任意发挥，跨平台，无构建步骤 ✅ |
| Tauri / Electron 桌面壳 | 高 | 多一层打包与签名，对音乐播放没有额外收益 |

选第二种。前端是三个静态文件（`web/index.html`、`app.js`、`style.css`），不需要 npm，改一行刷新就生效；将来要套桌面壳，把 UI 指向同一个本地端口即可。

## 数据流

```
浏览器                      BiliMuse 服务端                     B 站
  │                              │                              │
  │ POST /api/track {bvid}       │                              │
  ├─────────────────────────────>│ GET /x/player/pagelist ─────>│
  │                              │ GET /x/player/wbi/playurl ──>│  → dash.audio[]
  │                              │                              │
  │                              │ 探测候选节点（并发 64KB Range）│
  │                              ├─────────────────────────────>│  × N 个 CDN host
  │                              │ 首个可用节点起播，其余后台补  │
  │<─────────────────────────────┤ {stream_id, size, quality}   │
  │                              │                              │
  │ GET /api/stream/{id}         │                              │
  │  Range: bytes=0-             │  按块并发：Range 0-262143    │
  ├─────────────────────────────>├─────────────────────────────>│
  │                              │  乱序到达 → 按偏移排队       │
  │<═════════════════════════════┤  按序 yield                  │
  │  206 Partial Content         │                              │
  │                              │  GET /api/stats/{id}         │
  │  每秒轮询引擎指标             │                              │
  ├─────────────────────────────>│                              │
```

## 模块职责

- **`api.py`**：只负责"跟 B 站说话"。二维码登录（`passport-login/web/qrcode/*`）、收藏夹（`v3/fav/*`）、分页列表、`playurl`。`playurl` 优先走 wbi 签名版本，失败回退到未签名版本。
- **`auth.py`**：把 cookie 写进 `~/.config/bilimuse/session.json`，权限 0600，原子替换（先写 `.tmp` 再 rename）。不存密码、不存 refresh token 之外的任何凭据。
- **`btr.py`**：引擎。不依赖 FastAPI，可单独 import 做基准测试（`scripts/bench_*.py` 就是这么用的）。
- **`server.py`**：编排层。解析 Range、维护"流 ID → 引擎实例"的 LRU（48 条）、把异常翻译成中文 HTTP 错误。
- **`demo.py`**：合成演示音频。先用 4 秒循环再按字节复制到 45 秒，纯 Python 也能在 1 秒内生成三首。

## BTR 引擎细节

### 节点池

`expand_mirrors()` 把 `baseUrl` + `backupUrl` + 已知镜像 host 组合成候选列表。B 站音频 URL 的 path/query 在各镜像间通用，只换 host 就能命中不同 CDN，这正是这类加速器的基础。

但"能连通"不等于"能用"：同一区域内不同镜像的速度可以差 100 倍。所以每个候选都要实测：

```python
total, supports_range, ttfb, throughput = await probe(url)   # 64 KB
```

**不等所有节点测完**。九个候选里常有一两个连得上却不回包，等它们就会白等满 3 秒超时。所以 `build_stream()` 是"抢跑"的：第一个返回且支持 Range 的节点先把流交出去开播，剩下的探针在后台继续跑（`_graft()`），谁测完谁进池。新节点要过 `throughput >= max(best * 0.3, 150 KB/s)` 这关，进来的同时会按新速度收紧对冲延迟。

```python
stream = await build_stream(...)   # 第一个可用节点就返回
# 之后 pool 自己会长；/api/stats 里的 nodes 数量会跟着涨
```

唯一的例外：首个节点慢于 150 KB/s 时再多等最多 0.75 秒（`EAGER_GRACE`），免得一个被限速到 5 KB/s 的坑节点抢到起播权。`eager=False` 保留旧语义（等全部探针），测试和对比脚本用得上。

### 调度

`iter_range()` 维护一个"窗口"（并发数 + 预取块），只调度播放位置之后的若干块：

```
offsets:   [0][1][2][3][4][5][6][7] ...
inflight:   ▲▲▲▲▲▲▲▲                       窗口 = concurrency + prefetch
yield:      ▲                              按 offsets 顺序 await
```

窗口内的块乱序完成、按序输出。这样内存占用有上界（窗口 × 块大小），不会因为"提前把整个文件拉下来"而爆内存。

选节点用速度平方加权（`random.choices` 的手写版），而不是轮转：轮转会把 1/3 的块交给慢节点，而按序输出时**一个慢块就决定整条流水线的尾延迟**。

### 对冲（hedging）

真实 BTR 的核心不是"多开线程"，而是"判断这一块会不会赶不上播放"。这里用一个简化版：

```
t=0      在节点 A 上请求 [off, off+len)
t=D      还没回来 → 在节点 B 上重发同一段（D = 2 × 理论耗时，0.8s ≤ D ≤ 2.5s）
t=?      谁先完成用谁，另一个 cancel
```

代价是浪费一份带宽，收益是把 p99 尾延迟压到接近 p50。只在慢块上触发，正常路径零开销。

### 断点续传

`_download()` 不把"连接中途断开"当失败，而是返回**已收到的字节**。外层 `_chunk()` 从 `offset + len(received)` 继续请求剩余部分，最多 6 轮。

### 自适应并发

```
auto_concurrency = clamp(ceil(4MB/s ÷ 探测吞吐), 2, 用户设定值)
```

如果探测到节点已经能跑 10 MB/s，开 8 条连接只会多花 8 次 TLS 握手（几百毫秒），反而更慢 —— 实测里那些 0.5x 的样本就是这么来的。运行过程中一旦发生对冲或重试，并发会逐步 +2 升到上限，应对"先快后限速"的节点。

## Range 与缓存

- 浏览器拖动进度条会发 `Range: bytes=N-`，服务端换算成引擎的起始偏移，返回 `206` + `Content-Range`。
- `HEAD` 只回头，不建流。
- 同一个 `bvid` 30 分钟内复用已解析的流（`by_bvid`），避免每次点歌都重新探测节点。
- 流实例按 LRU 淘汰（48 条），淘汰时只丢元数据，不涉及已下载的音频。

## 错误处理

| 场景 | 行为 |
| --- | --- |
| 未登录 / 登录失效 | `401`，前端自动弹二维码 |
| 接口返回非 0 code | `502` + B 站原始 code 与 message |
| 所有节点探测失败 | 降级为单连接 `SimpleStream` |
| 所有节点都断流 | 该块重试 6 轮后抛 `StreamError`，前端显示"音频中断，正在重试"，800 ms 后重新 `load()` |
| 视频没有 dash 音频（番剧/付费） | `502`，提示"该视频没有可用的音频流" |

## 测试策略

`tests/mock_cdn.py` 是一个真正的 HTTP 服务（uvicorn 随机端口），支持：

- 按连接限速（模拟被限速的 CDN）
- `?fail_after=N`：吐 N 字节后主动断流（模拟节点掉线）
- 标准 `Range` / `206` / `Content-Range`

在此之上：

1. `test_single_connection_is_slow` —— 确认基线确实慢（否则后面的"快"没有意义）
2. `test_btr_is_much_faster_and_byte_exact` —— 提速 > 2.5x 且 sha256 一致
3. `test_range_seek` —— 中段 Range 的字节精确
4. `test_survives_broken_node` —— 一个节点每次都断流，数据仍完整
5. `test_dead_node_does_not_delay_startup` —— 有个只 accept 不回包的"黑洞"节点时，起播不被拖慢
6. `test_late_node_joins_pool_in_background` —— 慢一拍的节点事后自己进池
7. `test_eager_off_still_waits_for_all_nodes` —— `eager=False` 保留旧语义
8. `test_server.py` —— 起真实端口，跑通 登录态 → 收藏夹 → 解析 → Range 取流 的完整链路

不 mock 引擎内部逻辑：测试打的是真 HTTP，测的是端到端行为。

## 安卓版

安卓版没有照搬"本地服务端 + 浏览器 UI"，而是把同一套思路原生化：

- 界面是 Jetpack Compose，不加载网页；
- ExoPlayer 通过自定义 `BtrDataSource` 直接消费 BTR 引擎的输出，省掉本地 HTTP 转发；
- `seek` 不再换算 Range，而是关掉旧 reader、按新 position 新建一条顺序 reader；
- 后台播放由 `MediaSessionService` 负责，通知栏、锁屏、耳机按键走系统媒体会话；
- 登录：扫码是原生的；账号密码 / 手机验证码因为 B 站强制极验，嵌入官方 H5 登录页完成验证，
  成功后把 cookie 导入原生会话。

BTR 的节点探测、抢跑、加权调度、对冲、续传、自适应并发都有 Kotlin 移植，并用
MockWebServer 按连接限速做了字节一致性和提速测试。细节见 [ANDROID.md](ANDROID.md)。

## 桌面版的账号密码 / 短信登录

网页版只有扫码，因为浏览器里的页面没法读取 `.bilibili.com` 的 HttpOnly cookie；
桌面壳补上了这一层：

1. 页面点「账号密码 / 短信验证码」→ `POST /api/desktop/login/request`；
2. Rust 侧登录桥轮询 `/api/desktop/login/pending`，领到后创建一个**独立 incognito
   webview** 加载 B 站官方 H5 登录页（账号密码、短信、极验都在官方页面里完成）；
3. 登录桥每 700ms 用 Tauri 的 `cookies()` 读一次 cookie（能读 HttpOnly），按
   `.bilibili.com` 过滤，看到 `SESSDATA` 就回传 `/api/desktop/login/cookies`；
4. Python 侧先临时替换 cookie 调 `nav` 校验，成功才写 `session.json`，失败回滚；
5. 登录窗口自动关闭，前端轮询 `/api/desktop/login/status` 看到 success 后刷新收藏夹。

这条链路只存在于桌面版：边车带 `--desktop` 时 `state.desktop_shell=True`，否则
`/api/desktop/login/*` 返回 404。桌面壳与边车之间走最小化的回环 HTTP 客户端，
没有给加载远程页面的主窗口开放 Tauri IPC。细节见 [DESKTOP.md](DESKTOP.md)。

## 已知取舍

- **按序输出 vs 全速下载**：当前窗口只预取播放位置之后的数据。要"整首缓存"就得放开窗口，但内存与磁盘策略会复杂很多，等有缓存需求再说。
- **抢跑 vs 最优节点**：第一个能用的节点就开播，意味着它的速度可能不是最高的；靠后台补节点 + 慢块对冲把差距补回来。如果首批节点全都低于 150 KB/s，会多等最多 0.75 秒再决定（`EAGER_GRACE`）。
- **探测成本**：每条新音轨要额外下载 N×64 KB 做测速。对 3 MB 的音轨是可接受的 2%，对 300 KB 的短音频就偏重 —— 后续可以按文件大小决定是否全省略。
- **没有 WBI 缓存**：每次 `playurl` 都会重新拉一次 `nav` 取 img/sub key。请求频率低，先不做缓存。
