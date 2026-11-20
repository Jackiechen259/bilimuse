# BiliMuse 设计语言 · Flat Press

三端（Web / 桌面 / Android）共用的现代扁平视觉语言。目标不是「加一层皮肤」，而是用
纯色表面、1px 细线与 B 站粉播放头，让界面安静、可扫读，同时保留产品自己的唱片编辑气质。

## 五条原则

1. **纯色 + 细线**：面板全部实色，层级靠 `line` / `line-soft` 与背景明度差，不用阴影。
2. **粉色是信号**：`#FB7299` 只出现在播放状态、进度、选中项、主按钮、当前歌词与焦点环。
3. **硬边**：封面、卡片、面板、弹窗、抽屉圆角为 0；按钮/输入/chip 2px；只有播放键与进度
   knob 允许全圆。
4. **排版承担个性**：系统 sans + mono；标题更粗更紧，微标签大写宽字距，数字一律
   `tabular-nums`。不引入网络字体或字体文件。
5. **动效服务层级**：120–380ms 的 opacity / transform；保留页面进入、首屏列表错峰与
   内容切换；不做呼吸光、旋转、涟漪、视差、扫光与主题圆形揭示。系统「减少动态效果」时
   全部跳过。

## 签名：Playhead（播放头）

一条 **2px 粉色轨**，三种角色，只代表「播放位置」：

| 形态 | 用在 |
| --- | --- |
| 水平线 | 底部播放条进度、详情进度、启动页加载线 |
| 竖直线 | 当前曲目行、队列当前行、当前歌词行、侧栏/设置选中项 |
| 短底线 | 横向选中标签（Android 收藏夹 chip、移动端设置导航） |

不要用在 Logo、卡片边框、hover、引擎图表、统计数字或普通图标上。

## 颜色令牌

Web 令牌在 `bilimuse/web/style.css` 顶部，Android 在 `ui/Theme.kt` 的 `BiliPalette`，
值保持一致。

### ink · 深色（默认）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `bg` | `#111114` | 页面底色 |
| `panel` | `#17171A` | 播放条 / 面板 |
| `panel2` | `#1D1D21` | 次级面板 / 选中底 |
| `sidebar` | `#141417` | 侧栏 |
| `text` | `#F4F3F1` | 主文字 |
| `text2` | `#B9B8C0` | 次级文字 |
| `muted` | `#86858F` | 辅助文字 |
| `faint` | `#6B6B76` | 装饰 / 禁用 |
| `line` | `#2A2A2F` | 结构分隔 |
| `line-soft` | `#242429` | 行分隔 |
| `line-strong` | `#6B6B76` | 输入框 / 滑轨边界 |
| `track` | `#3A3A40` | 未填充进度轨 |
| `accent` | `#FB7299` | 粉底填充 |
| `accent-strong` | `#FB7299` | 2px 播放头 |
| `accent-text` | `#FB7299` | 深色底上的粉色文字 |
| `on-accent` | `#1A0A10` | 粉底上的文字 / 图标 |

### paper · 浅色

| 令牌 | 值 |
| --- | --- |
| `bg` | `#FFFFFF` |
| `panel` / `panel2` | `#FFFFFF` / `#F6F6F3` |
| `sidebar` | `#F6F6F3` |
| `text` / `text2` | `#17171A` / `#44444C` |
| `muted` / `faint` | `#6B6B76` / `#8A8A92` |
| `line` / `line-soft` / `line-strong` | `#E8E8EC` / `#F0F0F3` / `#8A8A92` |
| `track` | `#E8E8EC` |
| `accent` / `accent-strong` / `accent-text` | `#FB7299` / `#D63669` / `#B4235A` |
| `on-accent` | `#1A0A10` |

### cream · 暖纸

| 令牌 | 值 |
| --- | --- |
| `bg` | `#F2EBDF` |
| `panel` / `panel2` | `#FAF5EC` / `#FFFBF4` |
| `sidebar` | `#ECE3D4` |
| `text` / `text2` | `#1B1A17` / `#514A3F` |
| `muted` / `faint` | `#6A6152` / `#8A7F6D` |
| `line` / `line-soft` / `line-strong` | `#DFD4C1` / `#E8DFCE` / `#8A7F6D` |
| `track` | `#DFD4C1` |
| `accent` / `accent-strong` / `accent-text` | `#FB7299` / `#D63669` / `#B4235A` |
| `on-accent` | `#1A0A10` |

### 对比度

- `#FB7299` 在白色 / 纸色 / 米色上只有 2.2–2.6:1，**不能**做浅色主题的文字或小图形。
- 浅色主题文字用 `accent-text`（`#B4235A`，5.3–5.8:1）；2px 轨用 `accent-strong`
  （`#D63669`，3.1–3.6:1）。
- 粉底上的文字一律 `on-accent`（7.28:1）。
- `faint` 只做装饰与禁用态；9–10px 微标签用 `muted`。
- 输入框、滑轨等控件边界用 `line-strong`；`line-soft` 只做装饰分隔。

## 圆角 / 字体 / 动效

- 圆角：封面 / 卡片 / 面板 / 弹窗 / 抽屉 `0`；按钮 / 输入 / chip / 开关 `2px`；
  播放键、进度 knob、加载指示器可用全圆。
- 字体：系统 UI 字体；数字用等宽 + `tabular-nums`。
- 层级：页面标题 28px/700/-0.03em；详情标题 `clamp(26px, 3vw, 38px)/700/-0.035em`；
  正文 14px；微标签 10px/600/0.16em 大写。
- 动效：`120–380ms`，只动 transform / opacity；保留页面进入、首屏错峰、内容切换、播放/暂停交叉淡入、进度线性
  补间、按压 `scale(0.96–0.99)`。

## 三端映射

| 概念 | Web / 桌面 | Android |
| --- | --- | --- |
| 主题切换 | `<html data-theme="ink|paper|cream">`，侧栏 / 设置 / 详情顶栏 | `AppTheme` + `BiliPalette`，设置 → 界面 → 界面风格 |
| 当前曲目 | 行左 2px 粉轨 + `selected` 底 | 行左 2dp 粉轨 + `BiliSelected` 底 |
| 当前歌词 | 粉色文字 + 2px 粉色下划线 | `BiliPinkText` + 2dp 粉色下划线 |
| 进度 | 2px 方头，`track` + `accent-strong` | 2dp 方头，`BiliTrack` + `BiliPinkStrong` |
| 播放键 | 42px 粉圆，深色图标 | 64dp 粉圆，`BiliOnPink` 图标 |
| 封面 | 0 圆角 + 1px 内线，播放时左缘 2px 粉脊 | 0 圆角 + 1px 线，播放时左缘 2dp 粉脊 |

## 实现位置

- `bilimuse/web/style.css`：三套令牌与全部组件样式
- `bilimuse/web/app.js`：主题切换（160ms 淡入）、详情页上浮进出（240ms / 160ms）
- `desktop/web/index.html`：启动页的 ink 令牌副本（需与 Web 保持同步）
- `android/.../ui/Theme.kt`：`AppTheme`、三套 `BiliPalette`、`BiliMuseTheme`
- `android/.../ui/Motion.kt`：动效令牌与 reduced-motion 降级
- `docs/PLAYBACK.md`、`docs/LYRICS.md`、`docs/ANDROID.md`：各端界面细节

## 不在范围内

- 信息架构与页面路由；只改视觉、装饰与主题切换。
- 业务逻辑、API、歌词数据层、播放引擎。
- 品牌图标母版与第三方依赖。
