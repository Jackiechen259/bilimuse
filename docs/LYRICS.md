# 歌词

播放详情页的整页同步歌词。三端（网页 / 桌面 / 安卓）共用同一套来源阶梯、清洗规则与
数据结构，只是各自的实现语言不同。

原则只有两条：

1. **不伪造歌词。** 每一行歌词都必须能说清来源（本地文件 / B 站字幕 / LRCLIB / 演示），
   界面上始终标注。宁可显示「没有找到歌词」，也不猜测、不编造。
2. **歌词永远不能影响播放。** 没打开歌词视图就不发歌词请求；断网、超时、接口变动都只
   退化成空状态，播放链路（BTR、音轨选择、队列）一行不改。

## 来源阶梯

按顺序取第一个可用的来源：

```
本地 LRC（文件存在即最高优先）
  → 用户锁定的来源（pin）
  → B 站人工字幕（lan 不以 ai- 开头）
  → LRCLIB（得分 ≥ 0.62）
  → B 站 AI 字幕（过滤占位内容后至少 4 行有效文本）
  → none
```

演示曲目（`DEMO0001..3`）默认给演示歌词，并且不发起任何网络请求；但用户自己导入的本地
LRC 仍然排在演示歌词前面。

为什么是这个顺序：

- 本地文件是用户自己的选择，必须最优先。
- 人工字幕是 UP 主/观众逐句录入的，时间轴与本视频音频完全一致，词也准确。
- LRCLIB 词最准，但 B 站视频常带前奏、口白、Live 垫场，整首会整体偏移几秒——所以
  LRCLIB 命中后必须提供偏移调节。它排在人工字幕之后、AI 字幕之前。
- AI 字幕是本视频音频的语音识别，时间轴准，但歌词经常转错，只能兜底。

## LRC 解析

输入是文本，输出按时间升序、去重后的 `[{t: 秒(float), text: 字符串}]`。

- 支持 `[mm:ss]`、`[mm:ss.x]`、`[mm:ss.xx]`、`[mm:ss.xxx]`，也支持 `[m:ss]`。
- 一行可带多个时间标签（`[00:12.00][01:30.00]歌词`），展开成多行。
- `[offset:±毫秒]` 在解析时叠加到所有时间上。
- 忽略 `[ti:]` `[ar:]` `[al:]` `[by:]` `[length:]` 等元信息标签和注释行。
- 空歌词行保留为空白行参与滚动，但首尾各裁掉，最多保留连续 2 个空行。
- 时间必须非负；同一时间戳的重复文本只保留一条。
- 编码：先 `utf-8-sig`，失败退 `gbk`，再失败 `utf-8` 且 `errors="replace"`。
- 文件大小上限 256 KB，解析行数上限 2000（超出部分截断）。
- 没有任何时间标签的文本视为**静态歌词**：`synced=false`，按行原样展示，不跟随、不显示偏移控件。
## 标题清洗与 LRCLIB 匹配

B 站标题是给人看的，不是给曲库搜的（实测 `【纯享版】孤勇者 陈奕迅` 直接搜索返回空数组），
所以先清洗再匹配：

1. 全角转半角，统一大小写（英文），压缩空白。
2. 去掉 `【】 [] （） () 《》 「」 『』` 包裹的片段，以及其中的噪声词：
   `4K 1080P 60帧 高音质 无损 官方 MV PV 纯享 完整版 现场 Live 中文字幕 动态歌词
   HD HQ 首播 臻享 花絮 翻唱 伴奏 纯音乐 合集 完整版 无损音质`。
   注意：「翻唱」去掉的是标签，不是歌曲本身，歌名/歌手照常保留。
3. 生成候选 `(track, artist)`，最多两个：
   - 先用 ` - ` / ` — ` / ` – ` / `－` 分隔：左歌名、右歌手；
   - 再取 `《》` 里的内容当歌名，括号外其余文字当歌手；
   - 都没有时整串当歌名，UP 主名当歌手提示。
4. 依次请求 LRCLIB，任一步有结果即停止：
   - `track_name=<track>&artist_name=<artist>`（artist 为空则省略该参数）
   - `q=<清洗后的完整标题>`
   - `track_name=<track>` 单独
5. 打分（0–1）：`track*0.6 + artist*0.2 + duration*0.2`
   - track：归一化后完全相等 1.0；一方包含另一方 0.8；字符二元组重合率；否则 0。
   - artist：与候选歌手或 UP 主归一化后相等 0.9、包含 0.5，否则 0。
   - duration：`diff = |候选时长 - 视频时长|` 秒；`diff ≤ 2` 得 1.0，否则
     `max(0, 1 - (diff - 2) / 60)`。候选比视频还长太多通常不是同一首。
6. 直接丢弃 `instrumental=true` 的条目；优先 `syncedLyrics`，只有 `plainLyrics` 时按静态歌词返回。
7. 接受条件：总分 ≥ **0.62** 且 track 分 ≥ **0.7**。同一分数取时长更接近的。
8. 结果里必须带上 `matched`（歌名、歌手、时长、LRCLIB id），界面要显示——选错歌时用户能看出来并自己改。

## B 站字幕

- `GET https://api.bilibili.com/x/player/v2?bvid=<bvid>&cid=<cid>`，从
  `data.subtitle.subtitles[]` 里挑：先挑 `lan` 不以 `ai-` 开头的（人工），再挑 `ai-zh`。
- `cid` 来自已有的 `GET /x/player/pagelist?bvid=`。
- 下载字幕前必须校验 URL：协议 `https`，host 的后缀属于
  `.hdslb.com` / `.bilibili.com` / `.bilivideo.com`；相对地址按 `https://www.bilibili.com` 补全。
- 字幕 JSON 的 `body[{from,to,content}]` 转成歌词行：
  - 丢掉空文本；
  - 丢掉占位内容：匹配 `^[♪♫\s]*音乐?[♪♫\s]*$`（例如 `♪ 音乐 ♪`）；
  - AI 字幕用 `♪ ♫ ♬ ♩` 标出「一整句唱词」的边界：显示前去掉这些标记；
    带标记的整句独立成行，不再和相邻碎片合并；只有不带标记的碎片才参与下面的合并；
  - AI 字幕是句子级碎片，按顺序合并：相邻两段间隔 ≤ 0.3s、合并后长度 ≤ 24 字、
    总跨度 ≤ 8s 时合并成一行，时间取第一段的 `from`；
  - 合并后有效行少于 4 行，视为没有歌词，继续降级。
- 未登录或接口报错都只是「这个源不可用」，不抛给用户。

## 存储

| 内容 | 路径 | 说明 |
| --- | --- | --- |
| 用户导入的 LRC | `CONFIG_DIR/lyrics/<bvid>.lrc` | 永久；统一按 UTF-8 写入 |
| 用户偏好 | `CONFIG_DIR/lyrics/<bvid>.json` | `{offset, pin: {source, id}}`，`source ∈ local/bili/lrclib/demo` |
| 抓取缓存 | `CACHE_DIR/lyrics/<bvid>.json` | 命中缓存 30 天、未命中缓存 6 小时 |
| 演示歌词 | 代码常量 | 不落盘、不联网 |

- `bvid` 必须匹配 `^[A-Za-z0-9]{1,24}$`，否则接口返回 400，任何情况下都不拼路径。
- 缓存键是 `bvid`；缓存内容带 `v`（结构版本），版本不符直接丢弃。
- `disabled` / `not_logged_in` 的空结果不写缓存（开关 / 登录态会变，缓存会遮蔽新结果）。
- 有 `pin` 时跳过缓存读取，每次都真正尝试 pin；非 pin 来源的降级结果也不写缓存。
- 同一 `bvid` 的解析用锁合并并发请求，避免切歌/重试时重复打 LRCLIB；锁表过大时清理已释放的锁。
- `refresh=1` 绕过缓存；只清缓存，不动用户本地文件与偏好。

## 偏移

- 单位秒，范围 `[-30, +30]`，步进 `0.5`。
- 显示与跳转都用 `t + offset`；服务端返回原始 `lines` 与 `offset` 两个字段，由客户端相加。
- 按 `bvid` 持久化在用户偏好里，切歌回来仍然生效。
- 只有 `synced=true` 时界面才显示偏移控件。

## 服务端接口

所有接口在 `bilimuse/server.py` 里注册，业务逻辑在 `bilimuse/lyrics.py`。

统一结果体：

```json
{
  "status": "ok",
  "source": "lrclib",
  "source_label": "LRCLIB",
  "synced": true,
  "offset": 0.0,
  "lines": [{"t": 12.34, "text": "执念的鱼"}],
  "meta": {
    "matched": {"id": 31664182, "track": "夜航星", "artist": "不才", "duration": 304},
    "available": {"local": false, "bili": "ai", "lrclib": true},
    "cached": false,
    "pin": null
  },
  "reason": ""
}
```

`status` 只有 `ok` / `none`。`none` 时 `lines` 为空、`reason` 取
`not_found` / `disabled` / `not_logged_in` / `timeout` / `error` 之一。
`not_logged_in` 表示 B 站字幕开着但未登录且最终没有歌词（即使在线源开着但没匹配到）。
`meta.pin` 是当前锁定的来源（`{source, id}` 或 `null`），`ok` / `none` 与缓存命中都会带，
前端据此显示已锁定的来源。

`source` 与 `source_label`：

| source | source_label |
| --- | --- |
| `local` | 本地歌词 |
| `bili` | B 站字幕（人工） / B 站字幕（AI 转写） |
| `lrclib` | LRCLIB |
| `demo` | 演示歌词 |

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/lyrics/{bvid}` | `?title=&upper=&duration=&refresh=0` | 解析并返回；永远 200（bvid 非法才 400） |
| POST | `/api/lyrics/{bvid}` | `{"lrc": "..."}` | 导入本地 LRC，返回 local 结果；超过 256 KB 返回 400 |
| DELETE | `/api/lyrics/{bvid}` | — | 删除本地 LRC 与缓存 |
| POST | `/api/lyrics/{bvid}/prefs` | `{"offset": 0.5, "pin_source": "bili", "pin_id": null}` | 三字段都可省略；传 `null` 表示清除。`lrclib` 必须带 `pin_id`；`bili` 可以只锁来源（内部人工优先、AI 兜底） |
| GET | `/api/lyrics/{bvid}/sources` | `?title=&upper=&duration=` | 换源菜单：各来源可用性与标签 |
| GET | `/api/lyrics/search` | `?q=关键词` | LRCLIB 搜索，最多 10 条 |
| POST | `/api/lyrics/{bvid}/use` | `{"id": 31664182}` | 采用某条 LRCLIB 候选并锁定 |

`/sources` 返回：

```json
{"items": [
  {"source": "local",  "available": true,  "label": "本地歌词"},
  {"source": "bili",   "available": true,  "label": "B 站字幕（AI 转写）", "kind": "ai"},
  {"source": "lrclib", "available": true,  "label": "LRCLIB",
   "matched": {"id": 31664182, "track": "夜航星", "artist": "不才"}},
  {"source": "demo",   "available": false, "label": "演示歌词"}
]}
```

`matched.id` 就是锁定 LRCLIB 时可以用的候选 id；pin `lrclib` 时传的
`pin_id` / `/use` 的 `id` 来自 `sources.matched.id` 或 `/search` 返回的 `id`。

`/search` 返回：

```json
{"items": [
  {"id": 31664182, "track": "夜航星", "artist": "不才", "album": "", "duration": 304,
   "instrumental": false, "synced": true}
]}
```

### 设置

`settings.json` 新增两项，默认都为 `true`：

| 键 | 含义 |
| --- | --- |
| `lyrics_online` | 允许查询 LRCLIB |
| `lyrics_bili` | 允许读取 B 站字幕 |

关闭后对应来源直接跳过；本地 LRC 与演示歌词不受影响。

## 演示模式

`DEMO0001` / `DEMO0002` / `DEMO0003` 返回固定的「演示歌词」：约 8 行、带时间戳、
内容描述演示模式与 BTR 引擎，来源标为 `demo`，**不发起任何网络请求**。三端内容一致。

## 网页 / 桌面界面

入口：

- 走带里的 `#np-lyrics-btn`（「词」字图标，`aria-pressed`）。
- 键盘 `L` 切换；`[` / `]` 偏移 ±0.5s。
- 点击封面**不改变**原有行为（播放 / 暂停）；歌词只从按钮或 `L` 进入。

布局：

- 歌词视图占据封面与曲目信息的位置（`#np-lyrics` 插在 `.np-art` 之后），开启时隐藏
  `.np-art` 与 `.np-trackline`；进度条、走带、底部状态、右侧播放队列都保持原样。
- 宽屏歌词区最大宽度 720px；≤820px 单列；窗口高度 ≤620px 且宽度 ≥821px 时切回单列，
  歌词区最小高度 150px，保证 880×560 仍可操作。
- 详情页打开时始终以封面视图进入；关闭详情页回到封面视图。

渲染：

- 每首歌只构建一次歌词行 DOM；`timeupdate` 经 `requestAnimationFrame` 合并后只切换
  `.is-active` 类，不重建列表。
- 自动滚动把当前行居中；`prefers-reduced-motion` 时用 `behavior: 'auto'`。
- 用户手动滚动时暂停跟随并显示「回到当前」；点它或切歌后恢复。
- 当前行字号 `clamp(20px, 2.4vw, 26px)`、700 字重、`--accent-text` 颜色，并带 2px 粉色
  下划线；非当前行用 `--muted` 全不透明。上下用 1px 细线收边，不再用渐隐遮罩。
- 点击某行把播放位置跳到 `t + offset`。
- 空状态按 `reason` 给不同文案与按钮：导入 LRC / 在线搜索 / 重试 / 去登录。
- 工具栏：来源标签、匹配信息（歌名 · 歌手）、偏移控件、换源、搜索、导入。
- 搜索面板：预填清洗后的「歌名 歌手」，列出 LRCLIB 候选，点「用这条」后锁定。
- 切换封面/歌词只做 180ms 淡入淡出；系统要求减少动态效果时直接切换。
- `Esc` 顺序：传输详情 → 覆盖式队列 → 歌词搜索面板 → 歌词视图 → 详情页。

新增状态（`app.js` 的 `state`）：`lyrics`、`lyricsOpen`、`lyricsLoading`、`lyricsError`、
`lyricsToken`、`lyricsUserScrolled`。所有异步回包按 `bvid + token` 比对，迟到的丢弃。

## 安卓界面

- `NowPlayingScreen` 顶部工具区加「词」按钮；开启后用 `LyricsPanel` 替换
  `CoverHero` 与 `TrackTitles`，`SeekSection` / `Controls` 保持可见。
- `LyricsPanel`：`LazyColumn`，当前行居中放大并带 2dp 粉色下划线，其余行 `BiliMuted`
  全不透明；`animateScrollToItem(index, -视口高/2)`；
  用户拖动时暂停跟随并显示「回到当前」；点行 seek；加载 / 空 / 错误三态；
  工具行含来源标签、匹配信息、偏移、导入、换源、搜索、重试、收起（换源用
  `ModalBottomSheet` 列 `sources`，搜索用对话框调 `search` 再 `useCandidate`）。
- 数据层：
  - `data/LyricsModels.kt`：`LyricsLine(timeMs, text)`、`LyricsResult`、`LyricsCandidate`、`LyricsSourceOption`；
  - `data/LyricsLrc.kt`：解析与编码回退（纯 JVM，可单测）；
  - `data/LyricsMatch.kt`：清洗、候选、打分（纯 JVM，可单测）；
  - `data/LyricsRepository.kt`：本地文件、缓存、LRCLIB、B 站字幕；
  - `data/BiliClient.kt`：`subtitleTracks(bvid, cid)`、`subtitleBody(url)`，带 URL 白名单。
- `MainViewModel`：`LyricsUiState`（定义在 ViewModel 层）与 `lyrics: StateFlow<LyricsUiState>`、
  `toggleLyrics / openLyrics / closeLyrics / reloadLyrics / setLyricsOffset / importLyrics`；
  只在歌词视图打开时发起解析，切歌时若打开则重载。
- 导入：`ActivityResultContracts.OpenDocument` 取 `text/plain`，读文本后交给 ViewModel。
- 设置：`EngineSettings.lyricsOnline` / `lyricsBili`，`SettingsStore` 持久化，
  `SettingsSheet` 加两行开关。
- 演示模式（`DEMO0001..3`）显示与网页一致的演示歌词。

## 隐私与安全

- LRCLIB 只收到清洗后的歌名、歌手文本；**不发 Cookie、不发账号信息、不发 Referer**。
  LRCLIB 请求使用独立 HTTP 客户端（网页版服务端与安卓各一个），绝不与 B 站客户端共用。
- LRCLIB User-Agent：`BiliMuse/<VERSION> (https://github.com/Jackiechen259/bilimuse)`。
- LRCLIB 超时 6 秒；429/5xx 直接降级并缓存「未命中」，不做重试轰炸。
- B 站字幕 URL 必须过白名单，防止接口被篡改后把服务端当代理；校验后按解析出的
  scheme/host/path 重建 URL，拒绝 userinfo 与非 ASCII / 带反斜杠的 host。
- 字幕下载不跟随重定向（任何 3xx 直接拒绝），响应体上限 4 MB；LRCLIB 响应同样限 4 MB。
- 日志里不打印 Cookie、不打印歌词全文。

## 非目标

逐字卡拉 OK、翻译/罗马音、歌词上传与分享、云端同步、歌词导出、富文本歌词编辑、
TV 布局、改动 BTR 与音轨选择。

## 测试矩阵

服务端与安卓各实现一份，用例对齐：

- LRC：BOM + CRLF、多时间戳、`[offset:]`、毫秒精度、乱序、空行、无时间标签、GBK。
- 清洗：`【4K】夜航星 - 不才`、`《起风了》买辣椒也用券`、`孤勇者（Live版）`。
- 打分：完全匹配、包含匹配、instrumental 排除、时长差惩罚、阈值边界。
- 阶梯：本地优先、pin 生效、关闭 `lyrics_online` 后不请求 LRCLIB、关闭 `lyrics_bili` 后不请求字幕；未登录且没有歌词时 `reason=not_logged_in`。
- 缓存：命中不请求、`refresh` 强制刷新、未命中缓存 6 小时、`disabled` / `not_logged_in` 不缓存、pin 存在时不被缓存短路。
- 演示模式：零网络。
- 安全：LRCLIB 请求头里没有 `Cookie`；非法 bvid 400；字幕 URL 白名单拒绝外域 / userinfo / 反斜杠与非 ASCII host、拒绝 3xx，响应体超 4 MB 拒绝。
- 接口：7 个端点的正常与错误路径、导入超限、删除、偏移 clamp、设置往返。

## 已实现文件索引

服务端：

- `bilimuse/lyrics.py`：`LyricsService`（`resolve` / `sources` / `search` / `use_lrclib` /
  `import_lrc` / `delete` / `update_prefs`）、`parse_lrc_text` / `parse_lrc_bytes` /
  `parse_lrc_file`、`clean_title`、`title_candidates`、`score_record` / `pick_match`、
  `subtitle_lines`、`demo_lines`、`is_valid_bvid` / `clamp_offset`；
- `bilimuse/api.py`：`BiliClient.subtitle_tracks` / `subtitle_body`，以及模块级
  `subtitle_url`（字幕 URL 白名单）；
- `bilimuse/server.py`：7 个 `/api/lyrics` 路由（`GET` / `POST` / `DELETE /api/lyrics/{bvid}`、
  `GET /sources`、`POST /prefs`、`GET /search`、`POST /use`）与 `SettingsPatch` 的
  `lyrics_online` / `lyrics_bili` 两项。

网页 / 桌面：

- `bilimuse/web/index.html`：`#np-lyrics` 整页歌词、走带入口 `#np-lyrics-btn`、设置里的
  `lyrics-online` / `lyrics-bili` 开关；
- `bilimuse/web/app.js`：`lyrics*` 函数（`lyricsToggle` / `lyricsLoadCurrent` /
  `lyricsApplyResult` / `lyricsBuildLines` / `lyricsUpdateActive` / `lyricsCenterActive` /
  `lyricsSeekTo` / `lyricsNudgeOffset` / `lyricsReadFile` / `lyricsOpenSearch` /
  `lyricsToggleSources` 等）与 `state.lyrics*` 字段；
- `bilimuse/web/style.css`：歌词区布局、活动行（粉色下划线）、上下细线、空态与响应式规则（`np-lyrics*`）。

安卓：

- `data/LyricsModels.kt`：`LyricsLine` / `LyricsResult` / `LyricsMeta` / `LyricsMatched` /
  `LyricsAvailable` / `LyricsCandidate` / `LyricsSourceOption` / `LyricsPrefs`；
- `data/LyricsLrc.kt`：LRC 解析与编码回退；
- `data/LyricsMatch.kt`：标题清洗、候选与打分；
- `data/LyricsRepository.kt`：来源阶梯、本地文件、缓存、LRCLIB、B 站字幕与偏好；
- `ui/LyricsPanel.kt`：`LyricsPanel` 及工具行 / 列表 / 空态 / 回到当前；
- `MainViewModel.kt`：`LyricsUiState`、`lyrics: StateFlow<LyricsUiState>`，以及
  `toggleLyrics` / `openLyrics` / `closeLyrics` / `reloadLyrics` / `setLyricsOffset` /
  `importLyrics` / `setLyricsOnline` / `setLyricsBili`。

测试：

- `tests/test_lyrics.py`、`tests/test_lyrics_api.py`；
- `android/app/src/test/kotlin/com/bilimuse/app/data/LyricsLrcTest.kt`、
  `LyricsMatchTest.kt`、`LyricsRepositoryTest.kt`。
