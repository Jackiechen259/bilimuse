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
  │                              │ 过滤慢节点 + 自适应并发       │
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

只保留 `throughput >= max(best * 0.6, 150 KB/s)` 的节点。

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
5. `test_server.py` —— 起真实端口，跑通 登录态 → 收藏夹 → 解析 → Range 取流 的完整链路

不 mock 引擎内部逻辑：测试打的是真 HTTP，测的是端到端行为。

## 已知取舍

- **按序输出 vs 全速下载**：当前窗口只预取播放位置之后的数据。要"整首缓存"就得放开窗口，但内存与磁盘策略会复杂很多，等有缓存需求再说。
- **探测成本**：每条新音轨要额外下载 N×64 KB 做测速。对 3 MB 的音轨是可接受的 2%，对 300 KB 的短音频就偏重 —— 后续可以按文件大小决定是否全省略。
- **没有 WBI 缓存**：每次 `playurl` 都会重新拉一次 `nav` 取 img/sub key。请求频率低，先不做缓存。
