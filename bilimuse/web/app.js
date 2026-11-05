/* BiliMuse 前端逻辑：无框架，纯 DOM。 */
'use strict';

const $ = (id) => document.getElementById(id);

// B 站收藏夹列表接口的 ps 上限：超过 40 会返回 -400「请求错误」
const FOLDER_PAGE_SIZE = 40;
// B 站图片 CDN 支持 @宽_高_裁剪 后缀；列表 38px 没必要下载整张 16:9 原图。
const COVER_THUMB_SIZE = 96;
// 当前曲目的封面由底部播放器和详情页共用，同尺寸 = 同一个请求、同一时刻出现。
const COVER_CURRENT_SIZE = 560;
// 收藏夹 ≤ 480 首时一次预取全部剩余封面；更大的收藏夹只提前 3 页，随滚动续取
const COVER_PREFETCH_ALL_TRACKS = 480;
const COVER_PREFETCH_AHEAD = 3;
const PREFS_KEY = 'bilimuse.preferences';
const DEFAULT_PREFERENCES = { volume: 0.9, autoplayNext: true };

/* 界面风格：墨（黑白深色）/ 纸（黑白浅色）/ 米（暖色）。
   真源是服务端 settings.json（桌面版端口随机，localStorage 会丢），
   这里同时缓存一份到 localStorage，让 <head> 的内联脚本首帧就能上色。 */
const THEME_KEY = 'bilimuse.theme';
const THEMES = ['ink', 'paper', 'cream'];
const THEME_COLORS = { ink: '#0b0b0c', paper: '#f4f1ea', cream: '#ebe0cd' };
let themePersistTimer = null;

function currentTheme() {
  const value = document.documentElement.getAttribute('data-theme');
  return THEMES.includes(value) ? value : 'ink';
}

function applyTheme(theme, { persist = true } = {}) {
  const next = THEMES.includes(theme) ? theme : 'ink';
  document.documentElement.setAttribute('data-theme', next);
  document.querySelectorAll('[data-theme-choice]').forEach((btn) => {
    btn.setAttribute('aria-pressed', String(btn.dataset.themeChoice === next));
  });
  const meta = document.getElementById('theme-color');
  if (meta) meta.setAttribute('content', THEME_COLORS[next]);
  if (persist) {
    try { localStorage.setItem(THEME_KEY, next); } catch (_) { /* 受限 webview 忽略 */ }
  }
}

/** 主题也要跨端口 / 跨会话保留，写入服务端设置（防抖合并连点）。 */
function persistTheme(theme) {
  if (!THEMES.includes(theme)) return;
  state.settings.theme = theme;
  clearTimeout(themePersistTimer);
  themePersistTimer = setTimeout(() => {
    themePersistTimer = null;
    saveSettings({ theme: state.settings.theme }, { quiet: true });
  }, 220);
}

function initTheme() {
  let theme = 'ink';
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (THEMES.includes(saved)) theme = saved;
  } catch (_) { /* 用默认主题 */ }
  const preset = document.documentElement.getAttribute('data-theme');
  applyTheme(THEMES.includes(preset) ? preset : theme, { persist: false });
}

const SETTINGS_TABS = {
  appearance: {
    title: '外观',
    subtitle: '选择黑白或米色的界面风格',
  },
  playback: {
    title: '播放与音质',
    subtitle: '调整音轨选择与播放器的默认行为',
  },
  engine: {
    title: '播放引擎',
    subtitle: 'BTR 多线程、并发与分块策略',
  },
  folders: {
    title: '收藏夹',
    subtitle: '选择在列表里显示哪些收藏夹',
  },
  account: {
    title: '账号',
    subtitle: '管理本机的 B 站登录态',
  },
  about: {
    title: '关于 BiliMuse',
    subtitle: '版本、快捷键与免责声明',
  },
};

let settingsToastTimer = null;
let preferenceSaveTimer = null;
let folderSaveTimer = null;
let settingsReturnFocus = null;

function loadPreferences() {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    const saved = raw ? JSON.parse(raw) : {};
    const volume = Number(saved.volume);
    return {
      volume: Number.isFinite(volume) ? Math.min(1, Math.max(0, volume)) : DEFAULT_PREFERENCES.volume,
      autoplayNext: typeof saved.autoplayNext === 'boolean'
        ? saved.autoplayNext
        : DEFAULT_PREFERENCES.autoplayNext,
    };
  } catch (_) {
    return { ...DEFAULT_PREFERENCES };
  }
}

function persistPreferences() {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(state.preferences));
  } catch (_) { /* 无痕或受限 webview 下忽略即可 */ }
}

const state = {
  user: null,
  demo: false,
  desktop: false,     // 桌面壳模式：可使用账号密码 / 短信验证码登录
  folders: [],
  folderId: null,
  tracks: [],
  tracksTotal: 0,       // 收藏夹总条目（夹内搜索时接口不返回可信总数）
  tracksHasMore: false, // 是否还有下一页
  tracksPage: 0,        // 已加载到第几页
  tracksLoading: false, // 正在加载下一页
  tracksError: '',
  tracksToken: 0,       // 切换收藏夹 / 搜索词时作废旧的分页请求
  coverPrefetchGen: 0,       // 切换收藏夹 / 搜索词时作废封面预取
  coverPrefetchTimer: null,
  coverPrefetchRunning: false,
  coverPrefetchPending: false,
  coverPrefetchPages: new Set(),
  index: -1,
  current: null,      // 当前 track 元数据（含 stream id）
  nowTrack: null,     // 正在播放曲目的列表元数据：音轨解析完成前详情页也能渲染
  nowSource: '',      // 播放来源描述，例如「收藏夹「演示 · 合成器浪潮」」
  npError: '',        // 详情页音轨解析错误
  detailStats: null,  // 最近一次 /api/stats 快照（BTR 看板）
  detailSpeed: [],    // 详情页实时速度曲线采样
  statsTimer: null,
  pollTimer: null,    // 扫码轮询
  desktopTimer: null, // 桌面登录窗口状态轮询
  desktopLogin: null, // { id, mode }
  desktopMode: 'sms',
  loginMode: 'qr',    // 当前登录弹窗的 tab：qr / password / sms
  settings: {
    btr_enabled: true,
    concurrency: 8,
    chunk_size: 524288,
    prefer_lossless: true,
    volume: 0.9,
    autoplay_next: true,
    hidden_folders: [],
  },
  preferences: loadPreferences(),
  version: '',
  playToken: 0,
  // 随机播放：列表按页滚动加载，随机池需要整夹（或全部搜索结果）
  keyword: '',          // 当前列表对应的搜索词，不是输入框的实时值
  shuffle: false,
  shuffleLoading: false,
  shuffleError: '',
  shuffleScope: '整个收藏夹',
  shuffleGen: 0,
  shuffleKey: '',
  shufflePromise: null,
  shuffleAbort: null,
  shuffleCache: new Map(),  // "folderId:keyword" -> { items, total }
  queue: [],            // 当前播放顺序；随机模式下是整个收藏夹的随机排列
  queuePos: -1,
  queueShuffled: false,
  queueKey: '',
  queueSource: null,    // 当前播放队列对应的 { folderId, keyword }
  playSourceKey: '',
  playRequest: 0,
  requestedBvid: '',
};

/* ------------------------------------------------------------------ 工具 */

async function api(path, options = {}) {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!resp.ok) {
    let detail = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      detail = body.detail || detail;
    } catch (_) { /* ignore */ }
    const err = new Error(detail);
    err.status = resp.status;
    throw err;
  }
  return resp.json();
}

function fmtTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) sec = 0;
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

function fmtSize(bytes) {
  if (!bytes) return '—';
  const mb = bytes / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
}

function fmtSpeed(bps) {
  if (!bps) return '0 KB/s';
  const mb = bps / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB/s` : `${Math.round(bps / 1024)} KB/s`;
}

function debounce(fn, wait) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}

/* ------------------------------------------------------------------ 封面 */

/* 封面是 B 站 CDN 上的大图（常见 200–500KB），38px 的列表缩略图没必要整张下载。
   这里统一走 @宽_高_裁剪 的 WebP 变体：96px 约 3KB，560px 约 30KB。
   同一 URL + 尺寸共享一个 Promise，因此底部播放器和详情页会在同一帧出现。 */
const coverPromiseCache = new Map();

function coverSizeUrl(url, size) {
  if (!url || /^(data|blob):/i.test(url)) return url;
  let parsed;
  try {
    parsed = new URL(url, window.location.href);
  } catch (_) {
    return url;
  }
  if (!/(^|\.)(hdslb\.com|biliimg\.com)$/i.test(parsed.hostname)) return url;
  const clean = url.split('@')[0];
  const [rawPath, query] = clean.split('?');
  // 收藏夹接口有时返回 http:// 的封面；统一升级到 https，既复用 preconnect 也避免混合内容
  const path = rawPath.startsWith('//')
    ? `https:${rawPath}`
    : rawPath.replace(/^http:\/\//i, 'https://');
  return `${path}@${size}w_${size}h_1c.webp${query ? `?${query}` : ''}`;
}

function preloadCoverImage(url) {
  return new Promise((resolve) => {
    const image = new Image();
    // 页面已经声明 no-referrer；这里再显式指定一次，避免 Image 请求走默认策略
    image.referrerPolicy = 'no-referrer';
    image.onload = () => resolve(url);
    image.onerror = () => resolve('');
    image.src = url;
  });
}

function resolveCover(url, size) {
  if (!url) return Promise.resolve('');
  const key = `${size}|${url}`;
  const cached = coverPromiseCache.get(key);
  if (cached) return cached;

  const sized = coverSizeUrl(url, size);
  const promise = preloadCoverImage(sized)
    .then((ok) => {
      if (ok) return ok;
      if (sized === url) return '';
      return preloadCoverImage(url);   // 个别图片不支持变体时退回原图
    })
    .then((src) => {
      if (!src) coverPromiseCache.delete(key);
      return src;
    });
  coverPromiseCache.set(key, promise);
  if (coverPromiseCache.size > 400) {
    const oldest = coverPromiseCache.keys().next().value;
    if (oldest !== key) coverPromiseCache.delete(oldest);
  }
  return promise;
}

/** 列表 / 队列封面：先显示 ♪ 占位，加载完成后换成背景并移除占位。 */
function applyCoverElement(el, cover, size, placeholder = '♪') {
  if (!el) return;
  const key = `${size}|${cover || ''}`;
  if (cover && el._coverKey === key && el.style.backgroundImage) return;

  el._coverToken = (el._coverToken || 0) + 1;
  const token = el._coverToken;
  el._coverKey = '';
  el.style.backgroundImage = '';
  el.textContent = '';   // 清掉初始的 ♪ / 上一次的占位，只保留下面这一个 span

  let ph = el._coverPh;
  if (!ph || ph.parentNode !== el) {
    ph = document.createElement('span');
    ph.className = 'cover-ph';
    ph.setAttribute('aria-hidden', 'true');
    el.prepend(ph);
    el._coverPh = ph;
  }
  ph.textContent = placeholder;

  if (!cover) return;
  resolveCover(cover, size).then((src) => {
    if (el._coverToken !== token || !src) return;
    el.style.backgroundImage = `url(${src})`;
    el._coverKey = key;
    ph.remove();
  });
}

/* 列表按页加载，但封面可以提前取：小收藏夹一次预取完，大收藏夹提前 3 页。
   这样滚动到下一页时，96px 缩略图已经在浏览器缓存里，不会一行行慢慢出图。 */

function resetCoverPrefetch() {
  state.coverPrefetchGen += 1;
  state.coverPrefetchPages.clear();
  state.coverPrefetchPending = false;
  if (state.coverPrefetchTimer) {
    clearTimeout(state.coverPrefetchTimer);
    state.coverPrefetchTimer = null;
  }
}

function scheduleCoverPrefetch() {
  if (state.folderId === null) return;
  if (state.coverPrefetchRunning) {
    state.coverPrefetchPending = true;
    return;
  }
  const gen = state.coverPrefetchGen;
  if (state.coverPrefetchTimer) clearTimeout(state.coverPrefetchTimer);
  state.coverPrefetchTimer = setTimeout(() => {
    state.coverPrefetchTimer = null;
    prefetchCoverPages(gen);
  }, 700);
}

async function prefetchCoverPages(gen) {
  if (gen !== state.coverPrefetchGen || state.coverPrefetchRunning) return;
  if (state.folderId === null || !state.tracksHasMore || state.tracksLoading) return;
  const fid = state.folderId;
  const keyword = state.keyword || '';
  const loaded = Math.max(1, state.tracksPage);
  const totalPages = state.tracksTotal > 0
    ? Math.ceil(state.tracksTotal / FOLDER_PAGE_SIZE)
    : 0;
  // total 可信且收藏夹不大时全部预取；搜索结果 / 超大收藏夹只提前几页，随滚动续取
  const prefetchAll = totalPages > 0 && state.tracksTotal <= COVER_PREFETCH_ALL_TRACKS;
  const limit = prefetchAll ? totalPages : loaded + COVER_PREFETCH_AHEAD;
  if (limit <= loaded) return;

  const view = viewKey({ folderId: fid, keyword });
  state.coverPrefetchRunning = true;
  try {
    for (let page = loaded + 1; page <= limit; page += 1) {
      if (gen !== state.coverPrefetchGen) return;
      if (state.folderId !== fid || (state.keyword || '') !== keyword) return;
      const key = `${view}:${page}`;
      if (state.coverPrefetchPages.has(key)) continue;
      state.coverPrefetchPages.add(key);

      let data = null;
      try {
        data = await fetchTracksPage(fid, page, keyword);
      } catch (_) {
        state.coverPrefetchPages.delete(key);   // 瞬时报错，下次滚动时再试
        break;
      }
      if (gen !== state.coverPrefetchGen) return;
      if (!data) break;
      const items = data.items || [];
      if (items.length) {
        items.forEach((track) => {
          if (track && track.bvid && track.cover) resolveCover(track.cover, COVER_THUMB_SIZE);
        });
      }
      if (!data.has_more) break;
      // 页与页之间让出一点时间，别和正在播放的音频抢带宽
      await new Promise((resolve) => setTimeout(resolve, 120));
    }
  } finally {
    state.coverPrefetchRunning = false;
    if (state.coverPrefetchPending) {
      state.coverPrefetchPending = false;
      scheduleCoverPrefetch();
    }
  }
}

/* ------------------------------------------------------------------ 随机池 */

function currentView() {
  return { folderId: state.folderId, keyword: state.keyword || '' };
}

function viewKey(source) {
  return `${source.folderId}:${(source.keyword || '').toLowerCase()}`;
}

function shuffleArray(list) {
  const out = list.slice();
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** 当前曲目放队首，其余随机；没有当前曲目时整队随机。 */
function buildShuffleQueue(items, currentBvid) {
  const rest = items.filter((t) => t.bvid !== currentBvid);
  const shuffled = shuffleArray(rest);
  if (!currentBvid) return shuffled;
  const current = items.find((t) => t.bvid === currentBvid);
  return current ? [current, ...shuffled] : shuffled;
}

function updateShuffleUi() {
  const btn = $('shuffle');
  if (!btn) return;
  btn.classList.toggle('active', state.shuffle);
  btn.setAttribute('aria-pressed', String(state.shuffle));
  const npShuffle = $('np-shuffle');
  if (npShuffle) {
    npShuffle.classList.toggle('active', state.shuffle);
    npShuffle.setAttribute('aria-pressed', String(state.shuffle));
  }
  if (npOpen()) {
    npRenderQueue();
    npRenderPlayState();
  }
  const hint = $('shuffle-hint');
  if (!hint) return;
  if (!state.shuffle) {
    hint.classList.add('hidden');
    hint.classList.remove('error');
    return;
  }
  hint.classList.remove('hidden');
  hint.classList.toggle('error', Boolean(state.shuffleError));
  if (state.shuffleLoading) {
    hint.textContent = `正在加载${state.shuffleScope}，请稍候…`;
  } else if (state.shuffleError) {
    hint.textContent = '加载失败，随机仅限已加载内容';
  } else if (state.queueShuffled && state.queue.length) {
    const scope = state.queueSource && state.queueSource.keyword ? '全部搜索结果' : '整个收藏夹';
    hint.textContent = `随机范围：${scope} · ${state.queue.length} 首`;
  } else {
    hint.textContent = '随机：点任意一首后补全整个收藏夹';
  }
}

async function loadShuffleQueue(source, key) {
  const gen = ++state.shuffleGen;
  if (state.shuffleAbort) state.shuffleAbort.abort();
  const controller = new AbortController();
  state.shuffleAbort = controller;
  state.shuffleLoading = true;
  state.shuffleError = '';
  state.shuffleScope = source.keyword ? '全部搜索结果' : '整个收藏夹';
  updateShuffleUi();

  try {
    const qs = new URLSearchParams();
    if (source.keyword) qs.set('keyword', source.keyword);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    const data = await api(`/api/folder/${source.folderId}/all${suffix}`, {
      signal: controller.signal,
    });
    if (gen !== state.shuffleGen) return null;
    const items = (data.items || []).filter((t) => t && t.bvid);
    if (!items.length) throw new Error('收藏夹里没有可播放的内容');
    const entry = { items, total: Number(data.total) || items.length };
    state.shuffleCache.set(key, entry);
    state.shuffleLoading = false;
    state.shuffleError = '';
    updateShuffleUi();
    return entry;
  } catch (err) {
    if (gen !== state.shuffleGen) return null;
    state.shuffleLoading = false;
    if (err.name === 'AbortError') {
      updateShuffleUi();
      return null;
    }
    state.shuffleError = err.message || '加载失败';
    console.warn('[shuffle] 加载整个收藏夹失败', err);
    updateShuffleUi();
    return null;
  } finally {
    if (state.shuffleAbort === controller) state.shuffleAbort = null;
  }
}

/**
 * 确保某个收藏夹视图的整夹列表已加载。
 * 同一视图的并发调用共享同一个 Promise，避免连点「下一首」时反复重拉。
 */
function ensureShuffleQueue(source) {
  const key = viewKey(source);
  const cached = state.shuffleCache.get(key);
  if (cached) {
    if (state.shuffleLoading) {
      state.shuffleGen += 1;
      if (state.shuffleAbort) state.shuffleAbort.abort();
      state.shuffleAbort = null;
      state.shuffleLoading = false;
      state.shuffleKey = '';
      state.shufflePromise = null;
      updateShuffleUi();
    }
    return Promise.resolve(cached);
  }
  if (state.shuffleLoading && state.shuffleKey === key && state.shufflePromise) {
    return state.shufflePromise;
  }

  state.shuffleKey = key;
  const promise = loadShuffleQueue(source, key).finally(() => {
    if (state.shufflePromise === promise) {
      state.shufflePromise = null;
      state.shuffleKey = '';
    }
  });
  state.shufflePromise = promise;
  return promise;
}

function applyShuffleQueue(source, entry, currentBvid) {
  state.queue = buildShuffleQueue(entry.items, currentBvid);
  state.queuePos = currentBvid ? 0 : -1;
  state.queueShuffled = true;
  state.queueKey = viewKey(source);
  state.queueSource = { ...source };
  state.shuffleError = '';
  updateShuffleUi();
}

/** 队列已经属于同一视图时保留现有随机顺序，只移动当前位置。 */
function applyShuffleQueueForTrack(source, entry, track) {
  const key = viewKey(source);
  if (state.queueShuffled && state.queueKey === key) {
    const pos = state.queue.findIndex((t) => t.bvid === track.bvid);
    if (pos >= 0) {
      state.queuePos = pos;
      state.shuffleError = '';
      updateShuffleUi();
      return;
    }
  }
  applyShuffleQueue(source, entry, track.bvid);
}

function startTrack(track, visibleIndex, source) {
  state.index = visibleIndex;
  state.playSourceKey = viewKey(source);
  state.queueSource = { ...source };
  state.nowSource = npSourceLabel(source);
  const requestId = ++state.playRequest;
  playTrackNow(track);

  if (!state.shuffle) {
    state.queueShuffled = false;
    state.queue = state.tracks.slice();
    state.queuePos = visibleIndex;
    state.queueKey = state.playSourceKey;
    updateShuffleUi();
    return;
  }

  // 整夹还没加载好时，先退回当前列表顺序；加载完成后再替换成整夹随机队列。
  state.queueShuffled = false;
  state.queue = state.tracks.slice();
  state.queuePos = visibleIndex;
  state.queueKey = state.playSourceKey;

  const key = state.playSourceKey;
  ensureShuffleQueue(source).then((entry) => {
    if (!entry || !state.shuffle) return;
    if (requestId !== state.playRequest) return;   // 用户已经点了别的歌
    if (state.playSourceKey !== key) return;
    applyShuffleQueueForTrack(source, entry, track);
  });
  updateShuffleUi();
}

async function toggleShuffle() {
  state.shuffle = !state.shuffle;
  state.shuffleError = '';
  if (!state.shuffle) {
    state.shuffleGen += 1;
    if (state.shuffleAbort) state.shuffleAbort.abort();
    state.shuffleAbort = null;
    state.shuffleKey = '';
    state.shufflePromise = null;
    state.shuffleLoading = false;
    state.queueShuffled = false;
    const idx = state.current
      ? state.tracks.findIndex((t) => t.bvid === state.current.bvid)
      : state.index;
    state.index = idx;
    state.queue = state.tracks.slice();
    state.queuePos = idx;
    updateShuffleUi();
    return;
  }

  updateShuffleUi();
  // 还没开始播放时不急着拉整夹；用户点任意一首后 startTrack 会自动补全。
  if (!state.current) return;

  const source = state.queueSource || currentView();
  if (!source || source.folderId === null) return;
  const requestId = state.playRequest;
  const entry = await ensureShuffleQueue(source);
  if (!entry || !state.shuffle || requestId !== state.playRequest) return;
  applyShuffleQueueForTrack(source, entry, state.current);
}

/* ------------------------------------------------------------------ 账号 */

function renderAccount() {
  const u = state.user || {};
  const loggedIn = Boolean(u.logged_in);
  const name = loggedIn ? (u.uname || `UID ${u.mid}`) : '未登录';
  const hint = loggedIn
    ? (u.vip ? '大会员 · 可听 Hi-Res' : '已登录')
    : (state.demo
      ? '演示模式，无需登录'
      : (state.desktop ? '扫码 / 密码 / 短信登录' : '扫码登录后读取你的收藏夹'));
  $('account-name').textContent = name;
  $('account-hint').textContent = hint;
  $('login-btn').textContent = loggedIn ? '切换' : '登录';

  const avatar = $('avatar');
  if (u.face) {
    avatar.style.backgroundImage = `url(${u.face})`;
    avatar.textContent = '';
  } else {
    avatar.style.backgroundImage = '';
    avatar.textContent = (u.uname || 'B').slice(0, 1);
  }

  // 设置 → 账号
  const settingsName = $('settings-account-name');
  if (!settingsName) return;
  settingsName.textContent = state.demo && !loggedIn ? '演示模式' : name;
  $('settings-account-hint').textContent = state.demo && !loggedIn
    ? '当前使用本地合成音频，可随时登录 B 站账号'
    : hint;
  const settingsAvatar = $('settings-avatar');
  if (u.face) {
    settingsAvatar.style.backgroundImage = `url(${u.face})`;
    settingsAvatar.textContent = '';
  } else {
    settingsAvatar.style.backgroundImage = '';
    settingsAvatar.textContent = state.demo && !loggedIn ? '♪' : (u.uname || 'B').slice(0, 1);
  }
  const badge = $('settings-account-badge');
  badge.textContent = state.demo && !loggedIn ? '演示' : (loggedIn ? (u.vip ? '大会员' : '已登录') : '未登录');
  badge.classList.toggle('online', loggedIn);
  $('settings-account-login').textContent = loggedIn ? '切换账号' : '登录 B 站账号';
  $('settings-account-logout').classList.toggle('hidden', !loggedIn || state.demo);
  $('settings-desktop-login-tag').textContent = state.desktop ? '桌面版可用' : '仅桌面版';
  $('settings-desktop-login-tag').title = state.desktop
    ? '通过独立官方窗口完成登录'
    : '网页版不支持内嵌官方登录窗口，请使用扫码登录';
}

/* ------------------------------------------------------------------ 收藏夹 */

function hiddenFolderIds() {
  const saved = state.settings && Array.isArray(state.settings.hidden_folders)
    ? state.settings.hidden_folders
    : [];
  return new Set(saved.map(Number).filter(Number.isFinite));
}

function visibleFolders() {
  const hidden = hiddenFolderIds();
  return state.folders.filter((folder) => !hidden.has(Number(folder.id)));
}

function updateFolderCount() {
  const el = $('folder-count');
  if (!el) return;
  const all = state.folders.length;
  const visible = visibleFolders().length;
  el.textContent = all ? (visible === all ? String(all) : `${visible}/${all}`) : '';
}

function clearFolderSelection(message) {
  resetCoverPrefetch();
  state.folderId = null;
  state.tracks = [];
  state.index = -1;
  state.keyword = '';
  state.tracksToken += 1;
  state.tracksTotal = 0;
  state.tracksHasMore = false;
  state.tracksPage = 0;
  state.tracksLoading = false;
  state.tracksError = '';
  $('search').value = '';
  $('folder-title').textContent = '选择一个收藏夹';
  $('folder-meta').textContent = message;
  renderTracks();
}

function renderFolders() {
  const box = $('folders');
  if (!box) return;
  box.innerHTML = '';
  updateFolderCount();
  const visible = visibleFolders();
  if (!state.folders.length) {
    const empty = document.createElement('div');
    empty.className = 'muted small';
    empty.style.padding = '8px 12px';
    empty.textContent = '没有收藏夹';
    box.appendChild(empty);
    return;
  }

  if (!visible.length) {
    const empty = document.createElement('div');
    empty.className = 'folders-empty';
    const copy = document.createElement('span');
    copy.textContent = '所有收藏夹都已隐藏。';
    const manage = document.createElement('button');
    manage.className = 'ghost';
    manage.textContent = '管理收藏夹';
    manage.addEventListener('click', () => openSettings('folders'));
    empty.append(copy, manage);
    box.appendChild(empty);
    clearFolderSelection('所有收藏夹已隐藏，可在设置中恢复显示');
    return;
  }

  for (const folder of visible) {
    const btn = document.createElement('button');
    btn.className = 'folder';
    btn.dataset.id = String(folder.id);
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = folder.title;
    const count = document.createElement('span');
    count.className = 'count';
    count.textContent = folder.count ? String(folder.count) : '';
    btn.append(name, count);
    btn.addEventListener('click', () => selectFolder(folder.id, folder.title));
    box.appendChild(btn);
  }

  const currentVisible = visible.some((folder) => folder.id === state.folderId);
  if (currentVisible) {
    highlightFolder(state.folderId);
  } else {
    const first = visible[0];
    selectFolder(first.id, first.title);
  }
}

async function loadFolders() {
  const box = $('folders');
  try {
    const data = await api('/api/folders');
    state.folders = data.folders || [];
    renderFolders();
    renderFolderSettings();
  } catch (err) {
    state.folders = [];
    box.innerHTML = '';
    const tip = document.createElement('div');
    tip.className = 'muted small';
    tip.style.padding = '8px 12px';
    tip.textContent = err.message;
    box.appendChild(tip);
    $('folder-count').textContent = '';
    renderFolderSettings();
  }
}

function highlightFolder(id) {
  document.querySelectorAll('.folder').forEach((el) => {
    el.classList.toggle('active', el.dataset.id === String(id));
  });
}

async function selectFolder(id, title) {
  state.folderId = id;
  highlightFolder(id);
  $('search').value = '';
  await loadTracks(title);
}

async function fetchTracksPage(fid, pn, keyword) {
  const qs = new URLSearchParams({ pn: String(pn), ps: String(FOLDER_PAGE_SIZE) });
  if (keyword) qs.set('keyword', keyword);
  return api(`/api/folder/${fid}?${qs}`);
}

function folderMetaText() {
  const loaded = state.tracks.length;
  if (state.demo) {
    return `演示模式 · ${state.tracksTotal || loaded} 首（本地生成的音频，不需要登录）`;
  }
  if (state.keyword) {
    return state.tracksHasMore
      ? `搜索“${state.keyword}” · 已加载 ${loaded} 条`
      : `搜索“${state.keyword}” · 共 ${loaded} 条结果`;
  }
  return `${state.tracksTotal || loaded} 个视频 · 点击任意一行开始播放`;
}

function updateFolderMeta() {
  $('folder-meta').textContent = folderMetaText();
}

async function loadTracks(title, keyword = '') {
  const token = ++state.tracksToken;
  resetCoverPrefetch();
  const fid = state.folderId;
  state.keyword = keyword;
  state.tracksTotal = 0;
  state.tracksHasMore = false;
  state.tracksPage = 0;
  state.tracksLoading = false;
  state.tracksError = '';
  $('folder-title').textContent = title || '收藏夹';
  $('folder-meta').textContent = '正在加载…';
  try {
    const data = await fetchTracksPage(fid, 1, keyword);
    if (token !== state.tracksToken) return;   // 用户已经切到别的收藏夹 / 搜索词
    state.tracks = (data.items || []).filter((t) => t && t.bvid);
    state.tracksPage = 1;
    state.tracksHasMore = Boolean(data.has_more);
    state.tracksTotal = keyword ? 0 : (Number(data.total) || state.tracks.length);
    state.index = state.current
      ? state.tracks.findIndex((t) => t.bvid === state.current.bvid)
      : -1;
    $('folder-title').textContent = data.title || title || '收藏夹';
    updateFolderMeta();
    renderTracks();
    $('tracks').scrollTop = 0;
    fillTrackViewport();
    if (npOpen()) {
      npRenderQueue();
      npRenderPlayState();
    }
    scheduleCoverPrefetch();
  } catch (err) {
    if (token !== state.tracksToken) return;
    state.tracks = [];
    state.tracksHasMore = false;
    state.index = -1;
    $('folder-meta').textContent = err.message;
    if (err.status === 401) openLogin();
    renderTracks();
  }
}

/** 窗口很高、一页填不满时继续往后加载，直到出现滚动条或没有下一页。 */
function fillTrackViewport() {
  const box = $('tracks');
  if (!box || !state.tracksHasMore || state.tracksLoading) return;
  if (box.scrollHeight <= box.clientHeight + 8) loadMoreTracks();
}

/**
 * 拉下一页并追加到列表末尾。
 * 接口会过滤失效稿件，所以一整页可能一条不剩；连续空页达到上限就停下，
 * 避免 B 站分页异常时死循环。返回实际追加的条数。
 */
async function loadMoreTracks() {
  if (state.folderId === null || !state.tracksHasMore || state.tracksLoading) return 0;
  const token = state.tracksToken;
  const fid = state.folderId;
  const keyword = state.keyword;
  state.tracksLoading = true;
  state.tracksError = '';
  renderTracksFooter();
  let added = 0;
  let emptyPages = 0;
  try {
    while (state.tracksHasMore && emptyPages < 3) {
      const data = await fetchTracksPage(fid, state.tracksPage + 1, keyword);
      if (token !== state.tracksToken) return 0;
      state.tracksPage += 1;
      state.tracksHasMore = Boolean(data.has_more);
      if (!keyword) state.tracksTotal = Number(data.total) || state.tracksTotal;

      const seen = new Set(state.tracks.map((t) => t.bvid));
      const fresh = [];
      for (const item of data.items || []) {
        if (!item || !item.bvid || seen.has(item.bvid)) continue;
        seen.add(item.bvid);
        fresh.push(item);
      }
      if (fresh.length) {
        appendTracks(fresh);
        added += fresh.length;
        updateFolderMeta();
        break;
      }
      emptyPages += 1;
      if (!state.tracksHasMore) break;
    }
    return added;
  } catch (err) {
    if (token !== state.tracksToken) return 0;
    state.tracksError = err.message;
    if (err.status === 401) openLogin();
    return 0;
  } finally {
    if (token === state.tracksToken) {
      state.tracksLoading = false;
      renderTracksFooter();
      if (added > 0) fillTrackViewport();
    }
  }
}

function createTrackRow(track, i) {
  const row = document.createElement('div');
  row.className = 'track';
  row.dataset.index = String(i);
  row.dataset.bvid = track.bvid;
  if (state.current && state.current.bvid === track.bvid) row.classList.add('active');

  const idx = document.createElement('div');
  idx.className = 'idx';
  idx.textContent = String(i + 1).padStart(2, '0');

  const cell = document.createElement('div');
  cell.className = 'title-cell';
  const cover = document.createElement('div');
  cover.className = 'cover';
  applyCoverElement(cover, track.cover, COVER_THUMB_SIZE);
  const text = document.createElement('div');
  text.className = 'title-text';
  const line = document.createElement('div');
  line.textContent = track.title;
  const sub = document.createElement('div');
  sub.className = 'sub';
  sub.textContent = track.upper || 'B 站收藏夹';
  text.append(line, sub);
  cell.append(cover, text);

  const dur = document.createElement('div');
  dur.className = 'dur';
  dur.textContent = fmtTime(track.duration);

  row.append(idx, cell, dur);
  row.addEventListener('click', () => playIndex(i));
  return row;
}

function renderTracks() {
  const box = $('tracks');
  box.innerHTML = '';
  $('empty').classList.toggle('hidden', state.tracks.length > 0);
  if (!state.tracks.length) {
    const allHidden = state.folders.length > 0 && visibleFolders().length === 0;
    $('empty-text').textContent = allHidden
      ? '所有收藏夹已隐藏'
      : (state.folderId === null ? '先在左侧选择一个收藏夹' : '这里还没有内容');
    $('empty-manage').classList.toggle('hidden', !allHidden);
    return;
  }
  const frag = document.createDocumentFragment();
  state.tracks.forEach((track, i) => frag.appendChild(createTrackRow(track, i)));
  box.appendChild(frag);
  renderTracksFooter();
}

/** 只追加新行，不重建整张列表：否则每次翻页都会跳回顶部。 */
function appendTracks(items) {
  if (!items.length) return;
  const box = $('tracks');
  const footer = $('tracks-footer');
  const start = state.tracks.length;
  const frag = document.createDocumentFragment();
  items.forEach((track, k) => {
    state.tracks.push(track);
    frag.appendChild(createTrackRow(track, start + k));
  });
  if (footer && footer.parentNode === box) box.insertBefore(frag, footer);
  else box.appendChild(frag);
  // 第一页全是被过滤的失效稿件时列表可能先显示空状态，补到内容后要收起来
  $('empty').classList.add('hidden');
  if (npOpen()) npRenderQueue();
  scheduleCoverPrefetch();
}

function renderTracksFooter() {
  const box = $('tracks');
  if (!box) return;
  let footer = $('tracks-footer');
  if (!state.tracks.length) {
    if (footer) footer.remove();
    return;
  }
  if (!footer) {
    footer = document.createElement('div');
    footer.id = 'tracks-footer';
    footer.className = 'tracks-footer';
  }
  if (footer.parentNode !== box) box.appendChild(footer);
  footer.innerHTML = '';
  const noun = state.keyword ? '条结果' : '个视频';

  if (state.tracksLoading) {
    const spinner = document.createElement('span');
    spinner.className = 'tracks-spinner';
    const text = document.createElement('span');
    text.textContent = '正在加载更多…';
    footer.append(spinner, text);
    return;
  }
  if (state.tracksError) {
    const text = document.createElement('span');
    text.textContent = `加载更多失败：${state.tracksError}`;
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'ghost';
    retry.textContent = '重试';
    retry.addEventListener('click', () => loadMoreTracks());
    footer.append(text, retry);
    return;
  }
  if (state.tracksHasMore) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'ghost';
    btn.textContent = state.keyword
      ? `加载更多搜索结果（已加载 ${state.tracks.length} 条）`
      : `加载更多（已加载 ${state.tracks.length} / ${state.tracksTotal || '?'}）`;
    btn.addEventListener('click', () => loadMoreTracks());
    footer.append(btn);
    return;
  }
  if (state.tracks.length > FOLDER_PAGE_SIZE) {
    footer.textContent = `已加载全部 ${state.tracks.length} ${noun}`;
    return;
  }
  footer.remove();
}


/* ------------------------------------------------------------------ 播放 */

function playIndex(i) {
  if (i < 0 || i >= state.tracks.length) return;
  startTrack(state.tracks[i], i, currentView());
}

async function playTrackNow(track) {
  state.requestedBvid = track.bvid;
  const token = ++state.playToken;

  state.nowTrack = track;
  state.npError = '';
  state.detailStats = null;
  state.detailSpeed = new Array(NP_SPEED_POINTS).fill(0);
  npSyncEntry();

  $('now-title').textContent = track.title;
  $('now-sub').textContent = track.upper || '';
  $('quality').textContent = '解析中…';
  // 与详情页共用 560px 封面：同一个 Promise，两处同帧出现
  applyCoverElement($('now-cover'), track.cover, COVER_CURRENT_SIZE);
  // 详情页关着时先把大封面准备好，打开时飞行动画才有落点
  if (!npOpen()) npApplyCover(track.cover || '');
  $('stats').textContent = '正在探测 CDN 节点…';
  document.querySelectorAll('.track').forEach((el) => {
    el.classList.toggle('active', el.dataset.bvid === track.bvid);
  });
  if (npOpen()) {
    npRenderInfo();
    npRenderQueue();
    npRenderEngine();
    npRenderTime();
    npRenderPlayState();
  }

  try {
    const info = await api('/api/track', {
      method: 'POST',
      body: JSON.stringify({ bvid: track.bvid, title: track.title }),
    });
    if (token !== state.playToken) return;   // 用户已经点了别的歌
    state.current = { ...track, ...info };
    $('quality').textContent = info.quality || '—';
    $('quality').title = `${info.mode === 'btr' ? `BTR · ${info.nodes} 节点` : '单连接'} · ${fmtSize(info.size)} · ${info.codecs}`;
    const audio = $('audio');
    audio.src = `/api/stream/${info.id}`;
    audio.currentTime = 0;
    await audio.play().catch((err) => {
      $('stats').textContent = '浏览器阻止了自动播放，请再点一次 ▶';
      console.warn(err);
    });
    startStats();
    updateMediaSession();
    if (npOpen()) {
      npRenderInfo();
      npRenderEngine();
    }
  } catch (err) {
    if (token !== state.playToken) return;
    state.npError = err.message || '音轨解析失败';
    $('stats').textContent = err.message;
    $('quality').textContent = '—';
    if (npOpen()) npRenderInfo();
    if (err.status === 401) openLogin();
  }
}

function togglePlay() {
  const audio = $('audio');
  if (!state.current) {
    playIndex(state.index >= 0 ? state.index : 0);
    return;
  }
  if (audio.paused) audio.play().catch(() => {});
  else audio.pause();
}

/** 底部播放条的大播放键：SVG 图标随播放状态切换。 */
function syncPlayButton() {
  const btn = $('play');
  if (!btn) return;
  const playing = !$('audio').paused;
  btn.classList.toggle('is-playing', playing);
  btn.setAttribute('aria-label', playing ? '暂停' : '播放');
  btn.title = playing ? '暂停（Space）' : '播放（Space）';
}

function playNext(step = 1) {
  if (state.shuffle && state.queueShuffled && state.queue.length) {
    let pos = state.queuePos;
    if (pos < 0) {
      pos = Math.floor(Math.random() * state.queue.length);
    } else {
      pos += step;
      if (pos >= state.queue.length) {
        // 播到队尾后重新洗牌，随机播放不中断
        const last = state.current ? state.current.bvid : '';
        state.queue = shuffleArray(state.queue);
        pos = 0;
        if (state.queue.length > 1 && state.queue[0].bvid === last) pos = 1;
      } else if (pos < 0) {
        pos = state.queue.length - 1;
      }
    }
    state.queuePos = pos;
    const track = state.queue[pos];
    state.index = state.tracks.findIndex((t) => t.bvid === track.bvid);
    playTrackNow(track);
    updateShuffleUi();
    return;
  }

  if (!state.tracks.length) return;
  const next = state.index + step;
  if (next < 0) return playIndex(state.tracks.length - 1);
  if (next >= state.tracks.length) {
    // 顺序播放到已加载的末尾：先补下一页再接着播，不用手动滚动列表
    if (step > 0 && state.tracksHasMore && !state.tracksLoading) {
      const start = state.tracks.length;
      loadMoreTracks().then((added) => {
        if (added > 0 && state.tracks.length > start) playIndex(start);
      });
    }
    return;
  }
  playIndex(next);
}

function startStats() {
  stopStats();
  state.statsTimer = setInterval(refreshStats, 1000);
  refreshStats();
}

function stopStats() {
  if (state.statsTimer) {
    clearInterval(state.statsTimer);
    state.statsTimer = null;
  }
}

async function refreshStats() {
  if (!state.current) return;
  const sid = state.current.id;
  try {
    const s = await api(`/api/stats/${sid}`);
    // 切歌后旧请求可能才返回：只把属于当前音轨的快照交给详情页
    if (!state.current || state.current.id !== sid) return;
    state.detailStats = s;
    npSampleSpeed(s);
    const nodeLine = (s.nodes || []).slice(0, 2)
      .map((n) => n.host.replace('.bilivideo.com', '').replace('.akamaized.net', ''))
      .join(' · ');
    const live = s.recent_speed || 0;
    const ready = (s.progress || 0) >= 0.995;
    if (s.mode === 'btr') {
      const head = ready ? `已缓冲 ${Math.round((s.progress || 0) * 100)}%` : fmtSpeed(live);
      $('stats').innerHTML =
        `<span class="stat-tag">BTR</span> ${s.concurrency} 并发 · ${head} · ` +
        `${s.chunks_done}/${s.chunks_total} 块<br>` +
        `<span class="stat-faint">${nodeLine || '单节点'}</span>`;
      const hint = $('engine-hint');
      if (hint) hint.textContent =
        `${s.nodes.length} 节点 · ${ready ? '已就绪' : fmtSpeed(live)} · 对冲 ${s.hedges} 次`;
    } else {
      $('stats').innerHTML = `<span class="stat-faint">单连接</span> · ${fmtSpeed(live)}`;
      const hint = $('engine-hint');
      if (hint) hint.textContent = 'BTR 已关闭，单连接直通';
    }
    if (npOpen()) npRenderEngine();
  } catch (_) { /* 忽略瞬时错误 */ }
}

function updateMediaSession() {
  if (!('mediaSession' in navigator) || !state.current) return;
  const t = state.current;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: t.title,
    artist: t.upper || 'B站收藏夹',
    album: 'BiliMuse',
    artwork: t.cover ? [{ src: coverSizeUrl(t.cover, 512), sizes: '512x512' }] : [],
  });
  navigator.mediaSession.setActionHandler('previoustrack', () => playNext(-1));
  navigator.mediaSession.setActionHandler('nexttrack', () => playNext(1));
}

/* ------------------------------------------------------------------ 播放详情 */

/* 桌面版沉浸式 Now Playing。页面只是播放状态的另一个视图：
   标题 / 封面 / 进度 / 队列 / BTR 遥测全部从 state 与 /api/stats 派生，
   不新增任何服务端接口，也不会打断底部播放器。 */

const NP_SPEED_POINTS = 48;
const NP_MAX_NODES = 6;
// 队列可能是整个收藏夹（上千首）：只渲染当前附近的一段，避免一次插入上万节点
const NP_QUEUE_WINDOW = 240;
let npReturnFocus = null;
let npToastTimer = null;

const NP_ICON_PLAY =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.3v13.4a1 1 0 0 0 1.53.85l10.4-6.7a1 1 0 0 0 0-1.7L9.53 4.45A1 1 0 0 0 8 5.3Z"/></svg>';

function npOpen() {
  return !$('now-playing').classList.contains('hidden');
}

function npTrack() {
  return state.nowTrack || state.current;
}

function npPlaying() {
  return Boolean(state.nowTrack) && !$('audio').paused;
}

function npDuration() {
  const audio = $('audio');
  return audio.duration
    || (state.current && state.current.duration)
    || (state.nowTrack && state.nowTrack.duration)
    || 0;
}

function npEl(tag, className = '', text = '') {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function npFolderTitle() {
  const source = state.queueSource;
  if (source && source.folderId !== null && source.folderId !== undefined) {
    const folder = state.folders.find((f) => f.id === source.folderId);
    if (folder && folder.title) return folder.title;
  }
  const title = $('folder-title') ? $('folder-title').textContent : '';
  return title && title !== '选择一个收藏夹' ? title : '';
}

function npSourceLabel(source) {
  const keyword = source && source.keyword ? source.keyword : state.keyword;
  if (keyword) return `搜索「${keyword}」`;
  const folder = source && source.folderId !== null && source.folderId !== undefined
    ? state.folders.find((f) => f.id === source.folderId)
    : null;
  const title = folder ? folder.title : npFolderTitle();
  return title ? `收藏夹「${title}」` : '来自收藏夹';
}

function npSpeedParts(bps) {
  const value = Number(bps) || 0;
  if (value >= 1024 * 1024) return { value: (value / 1024 / 1024).toFixed(1), unit: 'MB/s' };
  return { value: String(Math.max(0, Math.round(value / 1024))), unit: 'KB/s' };
}

/** 本次播放期间有实际吞吐的采样均值，用于缓冲完成后的稳定读数。 */
function npAverageSpeed() {
  let sum = 0;
  let count = 0;
  state.detailSpeed.forEach((value) => {
    if (value > 0) {
      sum += value;
      count += 1;
    }
  });
  return count ? sum / count : 0;
}

function npCodecLabel(codecs) {
  const raw = String(codecs || '');
  const codec = raw.toLowerCase();
  if (!codec) return '';
  if (codec.includes('flac')) return 'FLAC';
  if (codec.includes('ec-3') || codec.includes('eac3')) return 'E-AC-3';
  if (codec.includes('ac-3')) return 'AC-3';
  if (codec.includes('opus')) return 'Opus';
  if (codec.includes('mp4a') || codec.includes('aac')) return 'AAC';
  if (codec.includes('mp3')) return 'MP3';
  if (codec.includes('wav')) return 'WAV';
  return raw.toUpperCase();
}

function npQueueView() {
  if (state.shuffle && state.queueShuffled && state.queue.length) {
    return {
      items: state.queue,
      pos: state.queuePos,
      shuffled: true,
      scope: state.queueSource && state.queueSource.keyword ? '全部搜索结果' : '整个收藏夹',
    };
  }
  return { items: state.tracks, pos: state.index, shuffled: false, scope: '' };
}

/* ---------------------------------------------------------------- 渲染 */

let npCoverToken = 0;
let npCoverUrl = '';

/** 详情页封面与底部播放器共用 resolveCover(560)：同一请求、同一时刻换图。 */
function npApplyCover(cover) {
  const art = $('np-art-cover');
  const ambient = $('np-ambient');
  if (cover === npCoverUrl && !art.classList.contains('is-empty')) return;

  const token = ++npCoverToken;
  npCoverUrl = cover || '';

  // 新封面加载前先收起上一首的封面，避免切歌瞬间显示错图
  art.classList.add('is-empty');
  art.style.backgroundImage = '';
  ambient.classList.add('is-empty');
  ambient.style.backgroundImage = '';

  if (!cover) return;
  resolveCover(cover, COVER_CURRENT_SIZE).then((src) => {
    if (token !== npCoverToken || !src) return;
    art.classList.remove('is-empty');
    art.style.backgroundImage = `url(${src})`;
    ambient.classList.remove('is-empty');
    ambient.style.backgroundImage = `url(${src})`;
  });
}

function npRenderInfo() {
  if (!npOpen()) return;
  const track = npTrack();
  if (!track) {
    $('np-title').textContent = '未在播放';
    $('np-artist').textContent = '—';
    $('np-intro').textContent = '';
    $('np-chips').replaceChildren();
    return;
  }

  const resolved = state.current && state.current.bvid === track.bvid ? state.current : null;
  $('np-title').textContent = track.title || '未知曲目';
  $('np-artist').textContent = track.upper || 'B 站收藏夹';
  $('np-intro').textContent = track.intro || '';
  $('np-intro').classList.toggle('hidden', !track.intro);
  $('np-header-sub').textContent = npFolderTitle() || 'B 站收藏夹';
  $('np-source').textContent = state.nowSource || npSourceLabel(state.queueSource);

  const cover = track.cover || '';
  npApplyCover(cover);

  $('np-copy').classList.toggle('hidden', state.demo || !track.bvid);

  const chips = $('np-chips');
  chips.replaceChildren();
  if (state.npError) {
    const retry = npEl('button', 'np-chip is-error', `解析失败 · 点击重试`);
    retry.type = 'button';
    retry.title = state.npError;
    retry.addEventListener('click', () => playTrackNow(track));
    chips.append(retry);
    return;
  }
  if (!resolved) {
    chips.append(npEl('span', 'np-chip is-loading', '正在解析音轨…'));
    return;
  }

  const quality = npEl('span', 'np-chip is-quality', resolved.quality || '音轨');
  chips.append(quality);

  const codec = npCodecLabel(resolved.codecs);
  const kbps = resolved.bandwidth ? `${Math.round(resolved.bandwidth / 1000)} kbps` : '';
  const meta = [codec, kbps].filter(Boolean).join(' · ');
  if (meta) chips.append(npEl('span', 'np-chip', meta));

  const engine = npEl('span', 'np-chip is-engine');
  engine.insertAdjacentHTML(
    'beforeend',
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13 2.5 4.8 13.2h5.7l-1.2 8.3 8.2-10.7h-5.7l1.2-8.3Z"/></svg>'
  );
  engine.append(document.createTextNode(
    resolved.mode === 'btr' ? `BTR · ${resolved.nodes || 0} 节点` : '单连接'
  ));
  chips.append(engine);
}

function npRenderPlayState() {
  const playing = npPlaying();
  const hasTrack = Boolean(state.nowTrack);
  $('np-play').classList.toggle('is-playing', playing);
  $('np-play').setAttribute('aria-label', playing ? '暂停' : '播放');
  $('np-art').classList.toggle('is-playing', playing);
  $('np-state-dot').classList.toggle('is-paused', !playing);
  $('np-state-text').textContent = hasTrack ? (playing ? '正在播放' : '已暂停') : '待机';
  document.querySelectorAll('.np-q-eq').forEach((eq) => {
    eq.classList.toggle('is-paused', !playing);
  });

  const q = npQueueView();
  const indexText = q.items.length && q.pos >= 0
    ? `${String(q.pos + 1).padStart(2, '0')} / ${String(q.shuffled ? q.items.length : (state.tracksTotal || q.items.length)).padStart(2, '0')}`
    : '—';
  $('np-state-index').textContent = indexText;
  npSyncEntry();
}

function npSyncEntry() {
  const hasTrack = Boolean(state.nowTrack);
  $('now').classList.toggle('is-idle', !hasTrack);
  $('now').classList.toggle('is-live', hasTrack && npPlaying());
  const expand = $('open-now-playing');
  if (expand) expand.disabled = !hasTrack;
  const queue = $('queue-btn');
  if (queue) queue.disabled = !hasTrack;
}

function npRenderQueue() {
  if (!npOpen()) return;
  const view = npQueueView();
  const items = view.items;
  const shuffled = view.shuffled;
  const total = shuffled ? items.length : (state.tracksTotal || items.length);

  $('np-queue-count').textContent = String(total || items.length);
  const mode = $('np-queue-mode');
  if (shuffled) mode.textContent = `随机播放 · ${view.scope}`;
  else if (state.shuffle) mode.textContent = state.shuffleLoading ? '随机播放 · 正在整理收藏夹…' : '随机播放 · 准备中…';
  else mode.textContent = '顺序播放';
  mode.classList.toggle('is-shuffle', Boolean(state.shuffle));

  const pos = view.pos >= 0 ? view.pos + 1 : 0;
  let hint = items.length
    ? `${String(pos).padStart(2, '0')} / ${String(total || items.length).padStart(2, '0')}`
    : '';
  if (!shuffled && state.tracksHasMore) hint = `${hint} · 还有更多未加载`;
  $('np-queue-hint').textContent = hint;

  const box = $('np-queue');
  box.replaceChildren();
  if (!items.length) {
    box.append(npEl('div', 'np-queue-empty', '队列还是空的，从左侧选一首开始播放吧。'));
    return;
  }

  const frag = document.createDocumentFragment();
  let start = 0;
  if (items.length > NP_QUEUE_WINDOW) {
    const anchor = view.pos >= 0 ? view.pos : 0;
    start = Math.max(0, Math.min(items.length - NP_QUEUE_WINDOW, anchor - 20));
  }
  const visible = items.slice(start, start + NP_QUEUE_WINDOW);
  if (start > 0) {
    box.append(npEl('div', 'np-queue-note', `前面还有 ${start} 首`));
  }
  visible.forEach((track, i) => frag.append(npQueueRow(track, start + i, start + i === view.pos)));
  box.append(frag);

  const remaining = items.length - start - visible.length;
  if (remaining > 0) {
    box.append(npEl('div', 'np-queue-foot', `后面还有 ${remaining} 首`));
  } else if (!shuffled && state.tracksHasMore) {
    const more = npEl('button', 'np-queue-more', state.tracksLoading ? '正在加载下一页…' : '加载下一页');
    more.type = 'button';
    more.disabled = Boolean(state.tracksLoading);
    more.addEventListener('click', () => loadMoreTracks());
    box.append(more);
  } else {
    box.append(npEl('div', 'np-queue-foot', `队列结束 · 共 ${items.length} 首`));
  }
  npScrollCurrent();
}

function npQueueRow(track, index, current) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = `np-q-row${current ? ' is-current' : ''}`;
  row.title = current ? `${track.title || ''}（正在播放）` : (track.title || '');
  if (current) row.setAttribute('aria-current', 'true');

  const idx = npEl('span', 'np-q-idx');
  if (current) {
    const eq = npEl('span', `np-q-eq${npPlaying() ? '' : ' is-paused'}`);
    eq.append(npEl('i'), npEl('i'), npEl('i'));
    idx.append(eq);
  } else {
    idx.textContent = String(index + 1).padStart(2, '0');
  }

  const cover = npEl('span', 'np-q-cover');
  applyCoverElement(cover, track.cover, COVER_THUMB_SIZE);
  cover.insertAdjacentHTML('beforeend', NP_ICON_PLAY.replace('<svg', '<svg class="np-q-play"'));

  const text = npEl('span', 'np-q-text');
  text.append(npEl('span', 'np-q-title', track.title || '未知曲目'));
  text.append(npEl('span', 'np-q-sub', track.upper || 'B 站收藏夹'));

  const dur = npEl('span', 'np-q-dur', track.duration ? fmtTime(track.duration) : '');
  row.append(idx, cover, text, dur);
  row.addEventListener('click', () => npPlayQueueItem(index));
  return row;
}

function npScrollCurrent() {
  const box = $('np-queue');
  const current = box.querySelector('.np-q-row.is-current');
  if (!current) return;
  const boxRect = box.getBoundingClientRect();
  const rowRect = current.getBoundingClientRect();
  if (rowRect.top < boxRect.top || rowRect.bottom > boxRect.bottom) {
    box.scrollTop += rowRect.top - boxRect.top - (boxRect.height - rowRect.height) / 2;
  }
}

function npPlayQueueItem(index) {
  const view = npQueueView();
  const track = view.items[index];
  if (!track) return;
  if (view.shuffled) {
    state.queuePos = index;
    state.index = state.tracks.findIndex((t) => t.bvid === track.bvid);
    playTrackNow(track);
    updateShuffleUi();
    return;
  }
  playIndex(index);
}

/* ---------------------------------------------------------------- BTR 看板 */

function npSampleSpeed(snapshot) {
  if (!snapshot) return;
  const value = $('audio').paused ? 0 : (Number(snapshot.recent_speed) || 0);
  state.detailSpeed.push(value);
  if (state.detailSpeed.length > NP_SPEED_POINTS) state.detailSpeed.shift();
  if (npOpen()) npDrawSpark();
}

function npDrawSpark() {
  if (!npOpen()) return;
  const line = $('np-spark-line');
  const area = $('np-spark-area');
  if (!line || !area) return;
  const points = state.detailSpeed;
  if (!points.length) {
    line.setAttribute('d', '');
    area.setAttribute('d', '');
    return;
  }
  const width = 240;
  const height = 64;
  const pad = 4;
  const values = points.length === 1 ? [points[0], points[0]] : points;
  const max = Math.max(...values, 1);
  const step = width / (values.length - 1);
  let d = '';
  values.forEach((value, i) => {
    const x = i * step;
    const y = height - pad - (value / max) * (height - pad * 2);
    d += `${i ? ' L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`;
  });
  line.setAttribute('d', d);
  area.setAttribute('d', `${d} L${width} ${height} L0 ${height} Z`);
}

function npRenderEngine() {
  if (!npOpen()) return;
  const snapshot = state.detailStats;
  const paused = !npPlaying();
  const mode = snapshot ? snapshot.mode : (state.settings.btr_enabled ? 'btr' : 'single');

  $('np-dock-tag').textContent = mode === 'btr' ? 'BTR' : '单连接';
  $('np-dock-tag').classList.toggle('is-single', mode !== 'btr');
  $('np-tab-live').classList.toggle('is-live', npPlaying() && Boolean(snapshot));

  if (!snapshot) {
    $('np-speed-value').textContent = '—';
    $('np-speed-unit').textContent = '';
    $('np-engine-live').textContent = '正在探测 CDN 节点…';
    $('np-buf-text').textContent = '—';
    $('np-buf-fill').style.width = '0%';
    $('np-stat-conc').textContent = '—';
    $('np-stat-chunk').textContent = '—';
    $('np-stat-hedge').textContent = '—';
    $('np-stat-served').textContent = '—';
    $('np-node-count').textContent = '探测中';
    $('np-node-list').replaceChildren(npEl('div', 'np-node-empty', '正在测速…'));
    $('np-dock-engine').textContent = '正在探测节点…';
    $('np-engine-note').classList.add('hidden');
    npDrawSpark();
    return;
  }

  const total = Number(snapshot.size) || (state.current && state.current.size) || 0;
  // 同一音轨被重新 Range 请求时，服务端统计会累加；展示层按音轨大小封顶
  const done = Math.min(Number(snapshot.bytes_done) || 0, total || Infinity);
  const ratio = total ? Math.min(1, done / total) : (Number(snapshot.progress) || 0);
  const percent = Math.round(ratio * 100);
  const ready = ratio >= 0.995;

  // 缓冲完成后实时速度会回到 0：改用本次播放的平均速度，避免大数字变成死值
  const average = npAverageSpeed();
  const useAverage = average > 0 && (paused || ready);
  const shown = npSpeedParts(useAverage ? average : (paused ? 0 : snapshot.recent_speed));
  $('np-speed-value').textContent = shown.value;
  $('np-speed-unit').textContent = shown.unit;
  $('np-speed-label').textContent = useAverage ? '平均速度' : '实时缓冲';

  const nodes = Array.isArray(snapshot.nodes) ? snapshot.nodes : [];
  const modeText = snapshot.mode === 'btr'
    ? `BTR · ${snapshot.concurrency} 并发 · ${nodes.length} 节点`
    : '单连接直通';
  $('np-engine-live').textContent = paused
    ? `${modeText} · 已暂停`
    : (ready ? `${modeText} · 已就绪` : modeText);

  $('np-buf-text').textContent = total
    ? `${percent}% · ${fmtSize(done)} / ${fmtSize(total)}`
    : `${percent}%`;
  $('np-buf-fill').style.width = `${ratio * 100}%`;
  $('np-seek-engine').style.width = `${ratio * 100}%`;

  $('np-stat-conc').textContent = snapshot.mode === 'btr' ? `${snapshot.concurrency} 路` : '1 路';
  $('np-stat-chunk').textContent = snapshot.mode === 'btr'
    ? `${Math.min(snapshot.chunks_done || 0, snapshot.chunks_total || 0)} / ${snapshot.chunks_total || 0}`
    : '直通';
  $('np-stat-hedge').textContent = `${snapshot.hedges || 0} / ${snapshot.retries || 0}`;
  $('np-stat-served').textContent = fmtSize(snapshot.bytes_served);

  const maxSpeed = nodes.reduce((max, node) => Math.max(max, Number(node.speed) || 0), 1);
  const list = $('np-node-list');
  list.replaceChildren();
  nodes.slice(0, NP_MAX_NODES).forEach((node) => {
    const row = npEl('div', `np-node${node.errors ? ' has-error' : ''}`);
    row.title = `${node.host} · 首字节 ${Math.round((Number(node.ttfb) || 0) * 1000)} ms`
      + (node.errors ? ` · ${node.errors} 次失败` : '');
    row.append(npEl('span', 'np-node-dot'));
    row.append(npEl('span', 'np-node-host', node.host || '未知节点'));
    const bar = npEl('span', 'np-node-bar');
    const fill = npEl('i');
    fill.style.width = `${Math.round(((Number(node.speed) || 0) / maxSpeed) * 100)}%`;
    bar.append(fill);
    row.append(bar);
    row.append(npEl('span', 'np-node-speed', node.speed ? fmtSpeed(node.speed) : '—'));
    list.append(row);
  });
  if (!nodes.length) list.append(npEl('div', 'np-node-empty', '正在测速…'));
  $('np-node-count').textContent = snapshot.mode === 'btr'
    ? `${nodes.length} 个 · 按实测速度`
    : '单连接';

  $('np-dock-engine').textContent = snapshot.mode === 'btr'
    ? (ready ? `已缓冲 ${percent}% · ${snapshot.concurrency} 并发` : `${snapshot.concurrency} 并发 · ${fmtSpeed(paused ? 0 : snapshot.recent_speed)}`)
    : `${paused ? '已暂停' : fmtSpeed(snapshot.recent_speed)} · 单连接`;

  const btrOff = snapshot.mode === 'single' && state.settings.btr_enabled === false;
  $('np-engine-note').classList.toggle('hidden', snapshot.mode !== 'single');
  $('np-engine-note-btn').classList.toggle('hidden', !btrOff);
  $('np-engine-note-text').textContent = btrOff
    ? 'BTR 已关闭，当前为单连接直通。开启后可多 CDN 并发分段拉流。'
    : '这条音轨当前走单连接直通，BTR 多线程未生效。';
  npDrawSpark();
}

/* ---------------------------------------------------------------- 进度 */

function npRenderTime() {
  if (!npOpen()) return;
  const audio = $('audio');
  const duration = npDuration();
  const current = audio.currentTime || 0;
  const ratio = duration ? Math.min(1, current / duration) : 0;
  $('np-seek-fill').style.width = `${ratio * 100}%`;
  $('np-seek-knob').style.left = `${ratio * 100}%`;
  $('np-cur').textContent = fmtTime(current);
  $('np-dur').textContent = fmtTime(duration);
  $('np-seek').setAttribute('aria-valuenow', String(Math.round(ratio * 100)));
}

function npRenderBuffered() {
  if (!npOpen()) return;
  const audio = $('audio');
  const duration = npDuration();
  if (!duration || !audio.buffered.length) return;
  const end = audio.buffered.end(audio.buffered.length - 1);
  $('np-seek-buf').style.width = `${Math.min(100, (end / duration) * 100)}%`;
}

/* ---------------------------------------------------------------- 打开 / 关闭 */

function npFocusables() {
  return Array.from($('now-playing').querySelectorAll('button, input, [tabindex]:not([tabindex="-1"])'))
    .filter((el) => {
      if (el.disabled || el.tabIndex < 0 || el.offsetParent === null) return false;
      return getComputedStyle(el).visibility !== 'hidden';
    });
}

function trapNpFocus(evt) {
  const items = npFocusables();
  if (!items.length) return;
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  const inside = $('now-playing').contains(active);
  if (evt.shiftKey && (active === first || !inside)) {
    evt.preventDefault();
    last.focus();
  } else if (!evt.shiftKey && (active === last || !inside)) {
    evt.preventDefault();
    first.focus();
  }
}

/* 播放队列：宽屏是右侧栏，窄屏是右侧滑出的抽屉。 */
function npQueueIsOverlay() {
  return window.matchMedia('(max-width: 1120px)').matches;
}

function npSetQueue(open, { focus = false } = {}) {
  $('now-playing').classList.toggle('np-queue-open', Boolean(open));
  const toggles = document.querySelectorAll('[data-queue-toggle]');
  toggles.forEach((btn) => btn.setAttribute('aria-expanded', String(Boolean(open))));
  if (!open) {
    if (focus && toggles.length) toggles[0].focus({ preventScroll: true });
    return;
  }
  npRenderQueue();
  requestAnimationFrame(() => npScrollCurrent());
  if (focus) $('np-queue-close').focus({ preventScroll: true });
}

/* BTR 遥测收进底部抽屉：默认不出现，点状态胶囊才展开。 */
function npSetEngine(open, { focus = false } = {}) {
  const layer = $('np-engine-layer');
  if (!layer) return;
  const isOpen = Boolean(open);
  layer.classList.toggle('hidden', !isOpen);
  $('np-engine-pill').setAttribute('aria-expanded', String(isOpen));
  $('np-engine-sheet').setAttribute('aria-hidden', String(!isOpen));
  if (isOpen) {
    npRenderEngine();
    npDrawSpark();
    requestAnimationFrame(() => $('np-engine-close').focus({ preventScroll: true }));
  } else if (focus) {
    $('np-engine-pill').focus({ preventScroll: true });
  }
}

function npEngineOpen() {
  const layer = $('np-engine-layer');
  return Boolean(layer) && !layer.classList.contains('hidden');
}

function npRenderAll() {
  npRenderInfo();
  npRenderQueue();
  npRenderEngine();
  npRenderTime();
  npRenderPlayState();
  npDrawSpark();
}

/* ------------------------------------------------------------------ 详情过渡
   打开：底部播放条的小封面先被复制一份，飞到详情页大封面的位置；外框淡入，
   顶栏 / 走带 / 队列错峰浮入。关闭时原路飞回。
   动效全部走 Web Animations API，桌面壳（WebView2）和浏览器表现一致；
   系统启用「减少动态效果」时跳过，直接显示 / 隐藏。 */

const NP_ENTER_MS = 420;
const NP_LEAVE_MS = 260;
const NP_MOTION_EASE = 'cubic-bezier(0.22, 0.9, 0.26, 1)';

let npMotionToken = 0;
let npMotionAnims = [];
let npClosing = false;
let npCloseTimer = null;
let npCoverTimer = null;
let npAfterClose = null;

function npMotionAvailable() {
  return typeof Element.prototype.animate === 'function'
    && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 停掉上一轮过渡，清掉飞行封面；返回本轮令牌，过期的异步回调靠它作废。 */
function npStopMotion() {
  npMotionToken += 1;
  clearTimeout(npCloseTimer);
  npCloseTimer = null;
  clearTimeout(npCoverTimer);
  npCoverTimer = null;
  npMotionAnims.forEach((anim) => {
    try { anim.cancel(); } catch (_) { /* 已经播完 */ }
  });
  npMotionAnims = [];
  document.querySelectorAll('.np-cover-fly').forEach((node) => node.remove());
  $('now-playing').classList.remove('is-closing');
  $('np-art').style.opacity = '';
  return npMotionToken;
}

function npTrackMotion(anim) {
  npMotionAnims.push(anim);
  return anim;
}

/** 关闭前抓一份当前动效状态：入场没播完就按 Esc 时，从眼前这一帧接着退场。 */
function npMotionSnapshot() {
  const overlay = $('now-playing');
  const read = (el) => {
    if (!el) return null;
    const style = getComputedStyle(el);
    return { opacity: style.opacity, transform: style.transform };
  };
  const fly = document.querySelector('.np-cover-fly');
  const flyStyle = fly ? getComputedStyle(fly) : null;
  return {
    overlay: read(overlay),
    header: read(overlay.querySelector('.np-header')),
    deck: read(overlay.querySelector('.np-console')),
    queue: read(overlay.querySelector('.np-queue-rail')),
    fly: fly ? {
      rect: fly.getBoundingClientRect(),
      image: flyStyle.backgroundImage,
      radius: flyStyle.borderRadius,
      shadow: flyStyle.boxShadow,
    } : null,
  };
}

/**
 * 把小封面的截图从 fromEl 的矩形送到 toEl 的矩形。
 * 位置走 transform，宽高与圆角单独插值，避免整体 scale 时圆角被一起放大。
 * 封面图可能挂在子元素上（详情页的 .np-art-cover），所以允许单独指定 imageEl。
 */
function npCoverFlight(fromEl, toEl, duration, imageEl = fromEl) {
  const fromStyle = getComputedStyle(fromEl);
  const image = getComputedStyle(imageEl).backgroundImage;
  if (!image || image === 'none') return null;
  return npCoverFlightFromRect(
    fromEl.getBoundingClientRect(),
    image,
    fromStyle.borderRadius,
    fromStyle.boxShadow,
    toEl,
    duration,
  );
}

/** 从任意矩形起飞：中途打断入场时，接着飞行副本当前的位置往回飞。 */
function npCoverFlightFromRect(from, image, radius, shadow, toEl, duration) {
  const to = toEl.getBoundingClientRect();
  if (from.width < 8 || to.width < 8) return null;

  const fly = document.createElement('div');
  fly.className = 'np-cover-fly';
  fly.style.left = `${from.left}px`;
  fly.style.top = `${from.top}px`;
  fly.style.width = `${from.width}px`;
  fly.style.height = `${from.height}px`;
  fly.style.borderRadius = radius;
  fly.style.backgroundImage = image;
  fly.style.boxShadow = shadow;
  document.body.append(fly);

  const toStyle = getComputedStyle(toEl);
  const anim = fly.animate([
    {
      transform: 'translate(0, 0)',
      width: `${from.width}px`,
      height: `${from.height}px`,
      borderRadius: radius,
      boxShadow: shadow,
    },
    {
      transform: `translate(${to.left - from.left}px, ${to.top - from.top}px)`,
      width: `${to.width}px`,
      height: `${to.height}px`,
      borderRadius: toStyle.borderRadius,
      boxShadow: toStyle.boxShadow,
    },
  ], { duration, easing: NP_MOTION_EASE, fill: 'both' });
  return { fly, anim, target: toEl };
}

/** 飞行落点后把真封面亮出来，再让副本淡出，边缘不会闪。 */
function npLandCover(flight, token) {
  const land = () => {
    if (token !== npMotionToken) return;
    flight.target.style.opacity = '1';
    const fade = flight.fly.animate([{ opacity: 1 }, { opacity: 0 }], {
      duration: 100,
      easing: 'linear',
      fill: 'forwards',
    });
    fade.finished.catch(() => {}).then(() => {
      if (token === npMotionToken) flight.fly.remove();
    });
  };
  flight.anim.finished.then(land, land);
}

function npEnterMotion(token) {
  const overlay = $('now-playing');
  const header = overlay.querySelector('.np-header');
  const deck = overlay.querySelector('.np-console');
  const queue = overlay.querySelector('.np-queue-rail');
  const art = $('np-art');

  npTrackMotion(overlay.animate(
    [{ opacity: 0 }, { opacity: 1 }],
    { duration: 180, easing: 'ease-out', fill: 'both' },
  ));

  const flight = npCoverFlight($('now-cover'), art, NP_ENTER_MS);
  if (flight) {
    art.style.opacity = '0';
    npLandCover(flight, token);
    // 页面被切到后台时动画可能停住，兜底把飞行副本收掉，避免一直悬着
    clearTimeout(npCoverTimer);
    npCoverTimer = setTimeout(() => {
      if (token !== npMotionToken) return;
      npCoverTimer = null;
      const node = document.querySelector('.np-cover-fly');
      if (node) node.remove();
      art.style.opacity = '1';
    }, NP_ENTER_MS + 380);
  } else {
    // 没有封面可飞（纯演示 / 封面缺失）时，大封面自己轻轻浮上来
    npTrackMotion(art.animate(
      [
        { opacity: 0, transform: 'translateY(10px) scale(0.97)' },
        { opacity: 1, transform: 'none' },
      ],
      { duration: 360, delay: 40, easing: NP_MOTION_EASE, fill: 'both' },
    ));
  }

  npTrackMotion(header.animate(
    [
      { opacity: 0, transform: 'translateY(-10px)' },
      { opacity: 1, transform: 'none' },
    ],
    { duration: 300, delay: 120, easing: NP_MOTION_EASE, fill: 'both' },
  ));

  npTrackMotion(deck.animate(
    [
      { opacity: 0, transform: 'translateY(16px)' },
      { opacity: 1, transform: 'none' },
    ],
    { duration: 330, delay: 150, easing: NP_MOTION_EASE, fill: 'both' },
  ));

  if (queue && queue.getBoundingClientRect().width > 0
    && getComputedStyle(queue).visibility !== 'hidden') {
    npTrackMotion(queue.animate(
      [
        { opacity: 0, transform: 'translateX(18px)' },
        { opacity: 1, transform: 'none' },
      ],
      { duration: 310, delay: 180, easing: NP_MOTION_EASE, fill: 'both' },
    ));
  }
}

function npLeaveMotion(token, snapshot = null) {
  const overlay = $('now-playing');
  const header = overlay.querySelector('.np-header');
  const deck = overlay.querySelector('.np-console');
  const queue = overlay.querySelector('.np-queue-rail');
  const art = $('np-art');
  const overlayFrom = snapshot && snapshot.overlay ? snapshot.overlay.opacity : '1';
  const headerFrom = (snapshot && snapshot.header) || { opacity: '1', transform: 'none' };
  const deckFrom = (snapshot && snapshot.deck) || { opacity: '1', transform: 'none' };
  const queueFrom = (snapshot && snapshot.queue) || { opacity: '1', transform: 'none' };

  overlay.classList.add('is-closing');

  const flight = snapshot && snapshot.fly
    ? npCoverFlightFromRect(
      snapshot.fly.rect,
      snapshot.fly.image,
      snapshot.fly.radius,
      snapshot.fly.shadow,
      $('now-cover'),
      NP_LEAVE_MS,
    )
    : npCoverFlight(art, $('now-cover'), NP_LEAVE_MS, $('np-art-cover'));
  if (flight) art.style.opacity = '0';

  npTrackMotion(overlay.animate(
    [{ opacity: overlayFrom }, { opacity: 0 }],
    { duration: 160, delay: 80, easing: 'ease-in', fill: 'both' },
  ));

  npTrackMotion(header.animate(
    [
      { opacity: headerFrom.opacity, transform: headerFrom.transform },
      { opacity: 0, transform: 'translateY(-8px)' },
    ],
    { duration: 90, easing: 'ease-in', fill: 'both' },
  ));

  npTrackMotion(deck.animate(
    [
      { opacity: deckFrom.opacity, transform: deckFrom.transform },
      { opacity: 0, transform: 'translateY(12px)' },
    ],
    { duration: 90, easing: 'ease-in', fill: 'both' },
  ));

  if (queue && queue.getBoundingClientRect().width > 0
    && getComputedStyle(queue).visibility !== 'hidden') {
    npTrackMotion(queue.animate(
      [
        { opacity: queueFrom.opacity, transform: queueFrom.transform },
        { opacity: 0, transform: 'translateX(14px)' },
      ],
      { duration: 90, easing: 'ease-in', fill: 'both' },
    ));
  }

  npCloseTimer = setTimeout(() => npFinishClose(token), NP_LEAVE_MS + 40);
}

function npFinishClose(token) {
  if (token !== npMotionToken) return;
  const overlay = $('now-playing');
  npClosing = false;
  npCloseTimer = null;
  clearTimeout(npCoverTimer);
  npCoverTimer = null;
  overlay.classList.add('hidden');
  overlay.classList.remove('np-queue-open', 'is-closing', 'np-motion');
  $('app').removeAttribute('inert');
  $('player').removeAttribute('inert');
  $('np-art').style.opacity = '';
  document.querySelectorAll('.np-cover-fly').forEach((node) => node.remove());
  npMotionAnims.forEach((anim) => {
    try { anim.cancel(); } catch (_) { /* 已经播完 */ }
  });
  npMotionAnims = [];
  const target = npReturnFocus;
  npReturnFocus = null;
  if (target && typeof target.focus === 'function' && document.contains(target)) {
    target.focus({ preventScroll: true });
  }
  const after = npAfterClose;
  npAfterClose = null;
  if (after) after();
}

function openNowPlaying() {
  if (npOpen() || npClosing || !state.nowTrack) return;
  const active = document.activeElement;
  npReturnFocus = active && active !== document.body ? active : $('open-now-playing');
  npAfterClose = null;
  const token = npStopMotion();
  const animate = npMotionAvailable();
  // 先挂上 np-motion，确保 display 变化时兜底的 np-in 不会和 JS 动效叠加
  if (animate) $('now-playing').classList.add('np-motion');
  $('now-playing').classList.remove('hidden');
  $('app').setAttribute('inert', '');
  $('player').setAttribute('inert', '');
  npSetEngine(false);
  npSetQueue(!npQueueIsOverlay());
  npRenderAll();
  if (animate) npEnterMotion(token);
  setTimeout(() => {
    if (npOpen()) $('np-play').focus({ preventScroll: true });
  }, 30);
}

function closeNowPlaying({ after = null } = {}) {
  if (!npOpen() || npClosing) return;
  if (npEngineOpen()) npSetEngine(false);
  const animate = npMotionAvailable();
  const snapshot = animate ? npMotionSnapshot() : null;
  const token = npStopMotion();
  npClosing = true;
  npAfterClose = typeof after === 'function' ? after : null;
  if (!animate) {
    npFinishClose(token);
    return;
  }
  npLeaveMotion(token, snapshot);
}

/* ---------------------------------------------------------------- 行为 */

function showNpToast(message, isError = false) {
  const toast = $('np-toast');
  if (!toast) return;
  clearTimeout(npToastTimer);
  toast.textContent = message;
  toast.classList.toggle('is-error', isError);
  toast.classList.add('is-visible');
  npToastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2100);
}

async function copyTrackLink() {
  const track = npTrack();
  if (!track || !track.bvid || state.demo) return;
  const url = `https://www.bilibili.com/video/${encodeURIComponent(track.bvid)}`;
  try {
    await navigator.clipboard.writeText(url);
  } catch (_) {
    const area = document.createElement('textarea');
    area.value = url;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    let copied = true;
    try {
      copied = document.execCommand('copy');
    } catch (err) {
      copied = false;
    }
    area.remove();
    if (!copied) {
      showNpToast('复制失败，请手动复制', true);
      return;
    }
  }
  showNpToast(`已复制 ${track.bvid} 链接`);
}

/* ------------------------------------------------------------------ 进度条 */

/* 底部播放器与播放详情各有一条进度条，共用同一套指针拖动逻辑。
   详情页额外带一个悬停时间气泡，并在键盘左右键时把事件拦下来，
   避免和全局「快退 / 快进 5 秒」重复执行。 */
function setupSeekBar(seek, apply, tip = null) {
  let dragging = false;

  const ratioFromEvent = (evt) => {
    const rect = seek.getBoundingClientRect();
    return Math.min(1, Math.max(0, (evt.clientX - rect.left) / rect.width));
  };

  const showTip = (ratio) => {
    if (!tip) return;
    const duration = npDuration();
    tip.textContent = fmtTime(ratio * duration);
    tip.style.left = `${ratio * 100}%`;
  };

  seek.addEventListener('pointerdown', (evt) => {
    dragging = true;
    seek.classList.add('is-dragging');
    seek.setPointerCapture(evt.pointerId);
    const ratio = ratioFromEvent(evt);
    apply(ratio);
    showTip(ratio);
  });
  seek.addEventListener('pointermove', (evt) => {
    const ratio = ratioFromEvent(evt);
    showTip(ratio);
    if (dragging) apply(ratio);
  });
  seek.addEventListener('pointerup', (evt) => {
    dragging = false;
    seek.classList.remove('is-dragging');
    try { seek.releasePointerCapture(evt.pointerId); } catch (_) { /* ignore */ }
  });
  seek.addEventListener('pointercancel', () => {
    dragging = false;
    seek.classList.remove('is-dragging');
  });
  seek.addEventListener('keydown', (evt) => {
    if (evt.code !== 'ArrowLeft' && evt.code !== 'ArrowRight') return;
    evt.preventDefault();
    evt.stopPropagation();
    const audio = $('audio');
    const duration = npDuration();
    if (!duration) return;
    const step = evt.code === 'ArrowRight' ? 5 : -5;
    audio.currentTime = Math.min(duration, Math.max(0, audio.currentTime + step));
    apply(audio.currentTime / duration);
  });
}

function setupSeek() {
  setupSeekBar($('seek'), (ratio) => {
    const audio = $('audio');
    const dur = audio.duration || (state.current && state.current.duration) || 0;
    if (!dur) return;
    audio.currentTime = ratio * dur;
    $('seek-fill').style.width = `${ratio * 100}%`;
    $('seek-knob').style.left = `${ratio * 100}%`;
    $('t-cur').textContent = fmtTime(audio.currentTime);
  });

  setupSeekBar($('np-seek'), (ratio) => {
    const duration = npDuration();
    if (!duration) return;
    $('audio').currentTime = ratio * duration;
    npRenderTime();
  }, $('np-seek-tip'));
}

/* ------------------------------------------------------------------ 登录 */

function setLoginTab(mode) {
  document.querySelectorAll('.login-tab').forEach((el) => {
    el.classList.toggle('active', el.dataset.mode === mode);
  });
}

function setLoginPanel(mode) {
  const isQr = mode === 'qr';
  state.loginMode = mode;
  $('login-qr-panel').classList.toggle('hidden', !isQr);
  $('login-window-panel').classList.toggle('hidden', isQr);
  if (isQr) {
    $('login-title').textContent = '扫码登录';
    $('login-sub').innerHTML = '用 B 站手机客户端扫码，登录态只保存在本机 <code>~/.config/bilimuse/</code>';
  } else {
    $('login-title').textContent = mode === 'password' ? '账号密码登录' : '短信验证码登录';
    $('login-sub').textContent = '在独立窗口里完成 B 站官方登录，登录态只保存在本机';
    state.desktopMode = mode;
  }
}

async function openLogin() {
  $('login-modal').classList.remove('hidden');
  $('login-tabs').classList.toggle('hidden', !state.desktop);
  await beginQrLogin();
}

async function beginQrLogin() {
  await cancelDesktopLogin();
  setLoginTab('qr');
  setLoginPanel('qr');
  $('qr-mask').classList.add('hidden');
  $('qr-status').textContent = '正在生成二维码…';
  $('qr-img').removeAttribute('src');
  clearInterval(state.pollTimer);
  try {
    const data = await api('/api/login/qr', { method: 'POST' });
    // 生成二维码期间用户可能已经关掉弹窗 / 切到别的登录方式
    if (state.loginMode !== 'qr' || $('login-modal').classList.contains('hidden')) return;
    $('qr-img').src = data.qr;
    $('qr-status').textContent = '请用 B 站 App 扫码';
    pollLogin(data.key);
  } catch (err) {
    $('qr-status').textContent = `二维码生成失败：${err.message}`;
  }
}

/** 清掉上一个账号的收藏夹、随机池与播放状态。 */
function resetLibraryAndPlayer() {
  closeNowPlaying();
  resetCoverPrefetch();
  state.folderId = null;
  state.tracks = [];
  state.index = -1;
  state.current = null;
  state.nowTrack = null;
  state.nowSource = '';
  state.npError = '';
  state.detailStats = null;
  state.detailSpeed = new Array(NP_SPEED_POINTS).fill(0);
  state.playToken += 1;      // 让上一个账号正在解析的音轨作废
  state.keyword = '';
  state.tracksToken += 1;
  state.tracksTotal = 0;
  state.tracksHasMore = false;
  state.tracksPage = 0;
  state.tracksLoading = false;
  state.tracksError = '';
  state.shuffle = false;
  state.shuffleGen += 1;
  if (state.shuffleAbort) state.shuffleAbort.abort();
  state.shuffleAbort = null;
  state.shuffleKey = '';
  state.shufflePromise = null;
  state.shuffleLoading = false;
  state.shuffleError = '';
  state.shuffleCache.clear();
  state.queue = [];
  state.queuePos = -1;
  state.queueShuffled = false;
  state.queueKey = '';
  state.queueSource = null;
  state.playSourceKey = '';
  state.playRequest += 1;
  state.requestedBvid = '';
  const audio = $('audio');
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  stopStats();
  $('now-title').textContent = '未在播放';
  $('now-sub').textContent = '选择一首开始';
  $('quality').textContent = '—';
  $('stats').textContent = 'BTR 待机';
  applyCoverElement($('now-cover'), '', COVER_CURRENT_SIZE);
  npSyncEntry();
}

/** 登录成功（含切换账号）：清掉上一个账号的收藏夹/播放列表。 */
function afterLogin(user) {
  state.user = user;
  resetLibraryAndPlayer();
  renderAccount();
  renderTracks();
  updateShuffleUi();
  syncSettingsUi();
}

function pollLogin(key) {
  clearInterval(state.pollTimer);
  state.pollTimer = setInterval(async () => {
    try {
      const res = await api(`/api/login/poll?key=${encodeURIComponent(key)}`);
      $('qr-status').textContent = res.message || '等待扫码';
      if (res.status === 'success') {
        clearInterval(state.pollTimer);
        afterLogin(res.user);
        closeLogin();
        await loadFolders();
      } else if (res.status === 'expired') {
        clearInterval(state.pollTimer);
        $('qr-mask').classList.remove('hidden');
      }
    } catch (err) {
      clearInterval(state.pollTimer);
      $('qr-status').textContent = `登录失败：${err.message}`;
    }
  }, 2000);
}

async function beginDesktopLogin(mode) {
  clearInterval(state.pollTimer);
  await cancelDesktopLogin();
  setLoginTab(mode);
  setLoginPanel(mode);
  $('login-window-retry').classList.add('hidden');
  $('login-window-status').textContent = '正在请求打开 B 站官方登录窗口…';
  try {
    const data = await api('/api/desktop/login/request', {
      method: 'POST',
      body: JSON.stringify({ mode }),
    });
    if (state.loginMode !== mode || $('login-modal').classList.contains('hidden')) {
      // 请求期间用户已经关掉弹窗 / 换了登录方式：别留下一个没人管的登录窗口
      try {
        await api('/api/desktop/login/cancel', {
          method: 'POST',
          body: JSON.stringify({ request_id: data.request_id, message: '已关闭登录弹窗' }),
        });
      } catch (_) { /* ignore */ }
      return;
    }
    state.desktopLogin = { id: data.request_id, mode: data.mode };
    $('login-window-status').textContent = data.message || '正在打开登录窗口…';
    pollDesktopLogin();
  } catch (err) {
    $('login-window-status').textContent = `无法打开登录窗口：${err.message}`;
    $('login-window-retry').classList.remove('hidden');
  }
}

function pollDesktopLogin() {
  clearInterval(state.desktopTimer);
  state.desktopTimer = setInterval(async () => {
    const req = state.desktopLogin;
    if (!req) return;
    try {
      const res = await api(
        `/api/desktop/login/status?request_id=${encodeURIComponent(req.id)}`,
      );
      $('login-window-status').textContent = res.message || '等待在官方窗口完成登录…';
      if (res.status === 'success') {
        stopDesktopLogin();
        state.desktopLogin = null;
        afterLogin(res.user);
        closeLogin(false);
        await loadFolders();
      } else if (['failed', 'cancelled', 'expired'].includes(res.status)) {
        stopDesktopLogin();
        state.desktopLogin = null;
        $('login-window-retry').classList.remove('hidden');
      }
    } catch (err) {
      stopDesktopLogin();
      $('login-window-status').textContent = `登录状态查询失败：${err.message}`;
      $('login-window-retry').classList.remove('hidden');
    }
  }, 1000);
}

function stopDesktopLogin() {
  clearInterval(state.desktopTimer);
  state.desktopTimer = null;
}

async function cancelDesktopLogin() {
  const req = state.desktopLogin;
  stopDesktopLogin();
  state.desktopLogin = null;
  if (!req) return;
  try {
    await api('/api/desktop/login/cancel', {
      method: 'POST',
      body: JSON.stringify({ request_id: req.id, message: '已取消登录' }),
    });
  } catch (_) { /* 取消失败不打扰用户 */ }
}

function closeLogin(cancelDesktop = true) {
  clearInterval(state.pollTimer);
  if (cancelDesktop) cancelDesktopLogin();
  else {
    stopDesktopLogin();
    state.desktopLogin = null;
  }
  $('login-modal').classList.add('hidden');
}

/* ------------------------------------------------------------------ 设置 */

/** 滑杆的自绘轨道：把「已选比例」写进 --range。 */
function paintRange(input) {
  if (!input) return;
  const min = Number(input.min);
  const max = Number(input.max);
  const value = Number(input.value);
  const lo = Number.isFinite(min) ? min : 0;
  const hi = Number.isFinite(max) && max > lo ? max : 1;
  const ratio = Math.min(1, Math.max(0, ((Number.isFinite(value) ? value : lo) - lo) / (hi - lo)));
  input.style.setProperty('--range', `${ratio * 100}%`);
}

function paintAllRanges() {
  document.querySelectorAll('input[type="range"]').forEach(paintRange);
}

function applyPreferences() {
  const audio = $('audio');
  if (audio) audio.volume = state.preferences.volume;
  const playerVolume = $('volume');
  if (playerVolume) playerVolume.value = String(state.preferences.volume);
  const npVolume = $('np-volume');
  if (npVolume) npVolume.value = String(state.preferences.volume);
  const volume = $('settings-volume');
  if (volume) volume.value = String(state.preferences.volume);
  const percent = Math.round(state.preferences.volume * 100);
  const value = $('settings-volume-value');
  if (value) value.textContent = `${percent}%`;
  const autoplay = $('autoplay-next');
  if (autoplay) autoplay.checked = state.preferences.autoplayNext;
  paintAllRanges();
}

function syncPreferencesFromSettings(s) {
  if (!s) return;
  if (typeof s.volume === 'number' && Number.isFinite(s.volume)) {
    state.preferences.volume = Math.min(1, Math.max(0, s.volume));
  }
  if (typeof s.autoplay_next === 'boolean') {
    state.preferences.autoplayNext = s.autoplay_next;
  }
  persistPreferences();
}

function flushPreferenceSave() {
  if (!preferenceSaveTimer) return;
  clearTimeout(preferenceSaveTimer);
  preferenceSaveTimer = null;
  saveSettings({
    volume: state.preferences.volume,
    autoplay_next: state.preferences.autoplayNext,
  }, { quiet: true });
}

function setPreference(patch) {
  state.preferences = { ...state.preferences, ...patch };
  persistPreferences();
  applyPreferences();
  // 滑动音量时只发最后一次请求；服务端持久化后，换端口重开也能恢复。
  clearTimeout(preferenceSaveTimer);
  preferenceSaveTimer = setTimeout(() => {
    preferenceSaveTimer = null;
    saveSettings({
      volume: state.preferences.volume,
      autoplay_next: state.preferences.autoplayNext,
    }, { quiet: true });
  }, 260);
}

function renderFolderSettings() {
  const box = $('folder-visibility-list');
  if (!box) return;
  const summary = $('folder-visibility-summary');
  const selectAll = $('show-all-folders');
  const selectNone = $('hide-all-folders');
  const hidden = hiddenFolderIds();
  const visible = visibleFolders();
  const total = state.folders.length;
  // 只统计当前账号真实存在的收藏夹：settings 里可能残留已删除收藏夹的 id。
  const hiddenCount = state.folders.filter((folder) => hidden.has(Number(folder.id))).length;

  if (summary) {
    if (total) {
      summary.textContent = `已显示 ${visible.length} / ${total}`;
    } else {
      summary.textContent = state.user && state.user.logged_in ? '没有收藏夹' : '登录后可管理';
    }
  }
  for (const btn of [selectAll, selectNone]) {
    if (btn) btn.classList.toggle('hidden', total === 0);
  }
  if (selectAll) selectAll.disabled = total === 0 || hiddenCount === 0;
  if (selectNone) selectNone.disabled = total === 0 || hiddenCount === total;

  box.innerHTML = '';
  if (!state.folders.length) {
    const empty = document.createElement('div');
    empty.className = 'settings-empty';
    empty.textContent = state.user && state.user.logged_in
      ? '还没有收藏夹。'
      : '登录后可以在这里选择显示哪些收藏夹。';
    box.appendChild(empty);
    return;
  }

  for (const folder of state.folders) {
    const isHidden = hidden.has(Number(folder.id));
    const row = document.createElement('label');
    row.className = 'setting-row folder-visibility-row';

    const copy = document.createElement('span');
    copy.className = 'setting-copy';
    const title = document.createElement('span');
    title.className = 'setting-title';
    title.textContent = folder.title || '未命名收藏夹';
    const desc = document.createElement('span');
    desc.className = 'setting-desc';
    desc.textContent = [
      folder.count ? `${folder.count} 个视频` : '',
      isHidden ? '已隐藏' : '显示在列表',
    ].filter(Boolean).join(' · ');
    copy.append(title, desc);

    const sw = document.createElement('span');
    sw.className = 'switch';
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = !isHidden;
    input.setAttribute('aria-label', `显示收藏夹 ${folder.title || ''}`);
    const knob = document.createElement('span');
    sw.append(input, knob);
    input.addEventListener('change', () => setFolderHidden(folder.id, !input.checked));

    row.append(copy, sw);
    box.appendChild(row);
  }
}

function persistHiddenFolders() {
  const value = state.settings.hidden_folders.slice();
  folderSaveChain = folderSaveChain
    .catch(() => { /* 上一次失败不阻塞下一次 */ })
    .then(async () => {
      const saved = await api('/api/settings', {
        method: 'POST',
        body: JSON.stringify({ hidden_folders: value }),
      });
      // 请求期间用户可能又改了：只在本地没有更新时采用服务端结果。
      if (!folderSaveTimer && JSON.stringify(state.settings.hidden_folders) === JSON.stringify(value)) {
        folderSaveDirty = false;
        state.settings.hidden_folders = saved.hidden_folders || [];
        renderFolders();
        renderFolderSettings();
      }
    })
    .catch((err) => {
      showSettingsToast(`收藏夹设置保存失败：${err.message}`, true);
    });
}

let folderSaveChain = Promise.resolve();
let folderSaveDirty = false;

function scheduleHiddenFoldersSave() {
  clearTimeout(folderSaveTimer);
  folderSaveTimer = setTimeout(() => {
    folderSaveTimer = null;
    persistHiddenFolders();
  }, 260);
}

function flushFolderSave() {
  if (!folderSaveTimer) return;
  clearTimeout(folderSaveTimer);
  folderSaveTimer = null;
  persistHiddenFolders();
}

function setFolderHidden(id, hidden) {
  const ids = hiddenFolderIds();
  const key = Number(id);
  if (!Number.isFinite(key)) return;
  if (hidden) ids.add(key);
  else ids.delete(key);
  state.settings.hidden_folders = [...ids].sort((a, b) => a - b);
  folderSaveDirty = true;
  renderFolders();
  renderFolderSettings();
  scheduleHiddenFoldersSave();
}

function showAllFolders() {
  if (!hiddenFolderIds().size) return;
  state.settings.hidden_folders = [];
  folderSaveDirty = true;
  renderFolders();
  renderFolderSettings();
  clearTimeout(folderSaveTimer);
  folderSaveTimer = null;
  persistHiddenFolders();
  showSettingsToast('已全选：所有收藏夹都会显示');
}

function hideAllFolders() {
  const ids = [...new Set(state.folders
    .map((folder) => Number(folder.id))
    .filter((id) => Number.isFinite(id)))].sort((a, b) => a - b);
  if (!ids.length) return;
  const hidden = hiddenFolderIds();
  if (ids.length === hidden.size && ids.every((id) => hidden.has(id))) return;
  state.settings.hidden_folders = ids;
  folderSaveDirty = true;
  renderFolders();
  renderFolderSettings();
  clearTimeout(folderSaveTimer);
  folderSaveTimer = null;
  persistHiddenFolders();
  showSettingsToast('已全不选：收藏夹已全部隐藏，点「全选」恢复');
}

function updateEngineSummary() {
  const s = state.settings || {};
  const enabled = s.btr_enabled !== false;
  const hint = $('engine-hint');
  if (hint) {
    hint.textContent = enabled
      ? `BTR 已开启 · ${s.concurrency || 8} 并发 · ${Math.round((s.chunk_size || 524288) / 1024)} KB/块`
      : 'BTR 已关闭，当前使用单连接直通';
  }
  const card = $('engine-status-card');
  if (card) card.classList.toggle('off', !enabled);
  const rail = $('settings-rail-status');
  if (rail) rail.textContent = enabled ? '本地引擎已连接' : '单连接模式';
}

function syncSettingsUi() {
  const s = state.settings || {};
  const enabled = s.btr_enabled !== false;
  const lossless = $('prefer-lossless');
  if (lossless) lossless.checked = s.prefer_lossless !== false;
  if ($('btr-toggle')) $('btr-toggle').checked = enabled;

  document.querySelectorAll('#settings-concurrency [data-value]').forEach((btn) => {
    const selected = Number(btn.dataset.value) === Number(s.concurrency);
    btn.classList.toggle('selected', selected);
    btn.setAttribute('aria-pressed', String(selected));
    btn.disabled = !enabled;
  });
  document.querySelectorAll('#settings-chunk-size [data-value]').forEach((btn) => {
    const selected = Number(btn.dataset.value) === Number(s.chunk_size);
    btn.classList.toggle('selected', selected);
    btn.setAttribute('aria-pressed', String(selected));
    btn.disabled = !enabled;
  });
  if ($('btr-options')) $('btr-options').classList.toggle('is-disabled', !enabled);
  if ($('btr-chunk-options')) $('btr-chunk-options').classList.toggle('is-disabled', !enabled);

  const version = state.version || '0.2.3';
  ['settings-version', 'settings-version-inline'].forEach((id) => {
    const el = $(id);
    if (el) el.textContent = version;
  });
  const mode = state.desktop ? '桌面版' : '网页版';
  const runtimeMode = $('settings-runtime-mode');
  if (runtimeMode) runtimeMode.textContent = mode;

  applyPreferences();
  updateEngineSummary();
  renderFolderSettings();
}

function setSettingsTab(tab) {
  const next = SETTINGS_TABS[tab] ? tab : 'playback';
  document.querySelectorAll('[data-settings-tab]').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.settingsTab === next);
  });
  document.querySelectorAll('[data-settings-pane]').forEach((pane) => {
    pane.classList.toggle('active', pane.dataset.settingsPane === next);
  });
  $('settings-title').textContent = SETTINGS_TABS[next].title;
  $('settings-subtitle').textContent = SETTINGS_TABS[next].subtitle;
  const scroll = document.querySelector('.settings-scroll');
  if (scroll) scroll.scrollTop = 0;
}

function openSettings(tab = 'playback') {
  settingsReturnFocus = document.activeElement;
  syncSettingsUi();
  setSettingsTab(tab);
  $('settings-modal').classList.remove('hidden');
  document.body.classList.add('settings-open');
  // 设置层永远在最上面：把播放详情也设为 inert，避免焦点跑到背后的页面
  if (npOpen()) $('now-playing').setAttribute('inert', '');
  setTimeout(() => $('close-settings').focus(), 30);
}

function closeSettings() {
  flushPreferenceSave();
  flushFolderSave();
  $('settings-modal').classList.add('hidden');
  document.body.classList.remove('settings-open');
  $('now-playing').removeAttribute('inert');
  if (settingsReturnFocus && typeof settingsReturnFocus.focus === 'function') {
    settingsReturnFocus.focus();
  }
  settingsReturnFocus = null;
}

function showSettingsToast(message, isError = false) {
  const toast = $('settings-toast');
  if (!toast) return;
  clearTimeout(settingsToastTimer);
  toast.textContent = message;
  toast.classList.toggle('error', isError);
  toast.classList.add('visible');
  settingsToastTimer = setTimeout(() => toast.classList.remove('visible'), 1900);
}

async function saveSettings(patch, { quiet = false } = {}) {
  const hiddenPending = folderSaveDirty || Boolean(folderSaveTimer);
  const localHidden = state.settings.hidden_folders;
  try {
    const s = await api('/api/settings', { method: 'POST', body: JSON.stringify(patch) });
    state.settings = s;
    if (!themePersistTimer && THEMES.includes(s.theme) && s.theme !== currentTheme()) {
      applyTheme(s.theme);
    }
    // 收藏夹显示使用独立的串行队列；别让旧响应覆盖用户刚切好的本地状态。
    if (hiddenPending) state.settings.hidden_folders = localHidden;
    syncPreferencesFromSettings(s);
    syncSettingsUi();
    if (!quiet) showSettingsToast('设置已保存');
  } catch (err) {
    syncSettingsUi();
    showSettingsToast(`保存失败：${err.message}`, true);
  }
}

let logoutConfirmTimer = null;

async function handleLogoutClick() {
  const btn = $('settings-account-logout');
  if (!btn) return;
  if (btn.dataset.confirm !== '1') {
    btn.dataset.confirm = '1';
    btn.textContent = '再次点击确认退出';
    clearTimeout(logoutConfirmTimer);
    logoutConfirmTimer = setTimeout(() => {
      btn.dataset.confirm = '';
      btn.textContent = '退出登录';
    }, 3200);
    return;
  }

  clearTimeout(logoutConfirmTimer);
  btn.dataset.confirm = '';
  btn.disabled = true;
  btn.textContent = '正在退出…';
  try {
    await api('/api/logout', { method: 'POST' });
    state.user = {
      logged_in: false,
      mid: 0,
      uname: '',
      face: '',
      vip: false,
    };
    resetLibraryAndPlayer();
    state.folders = [];
    $('folders').innerHTML = '';
    $('folder-count').textContent = '';
    $('search').value = '';
    $('folder-title').textContent = '选择一个收藏夹';
    $('folder-meta').textContent = state.desktop
      ? '还没有登录，点「登录」扫码，或使用账号密码 / 短信验证码'
      : '还没有登录，点「登录」扫码';
    renderAccount();
    renderTracks();
    updateShuffleUi();
    syncSettingsUi();
    closeSettings();
  } catch (err) {
    showSettingsToast(`退出失败：${err.message}`, true);
  } finally {
    btn.disabled = false;
    btn.textContent = '退出登录';
  }
}

/* ------------------------------------------------------------------ 启动 */

/* 桌面壳是无边框窗口，顶栏右端的三枚按钮就是系统标题栏。
   详情页顶部也有一组同样的按钮（覆盖整窗后仍然能最小化 / 关闭），
   所以这里按 data-win 统一绑定，而不是写死单个 id。 */
function setupWindowChrome() {
  const controls = Array.from(document.querySelectorAll('.window-controls'));
  if (!controls.length) return;
  const tauri = window.__TAURI__;
  const current = tauri && tauri.window && tauri.window.getCurrentWindow
    ? tauri.window.getCurrentWindow()
    : null;
  if (!current || !document.documentElement.classList.contains('is-windows')) {
    // 普通浏览器（或 macOS/Linux 的原生标题栏窗口）里，别留一排点不动的假按钮
    controls.forEach((el) => el.classList.add('hidden'));
    return;
  }

  const maxButtons = Array.from(document.querySelectorAll('[data-win="max"]'));
  const warn = (action) => (err) => console.warn(`窗口${action}失败`, err);
  const syncMaximized = async () => {
    try {
      const maximized = await current.isMaximized();
      const label = maximized ? '还原' : '最大化';
      maxButtons.forEach((btn) => {
        btn.classList.toggle('is-maximized', maximized);
        btn.title = label;
        btn.setAttribute('aria-label', label);
      });
    } catch (err) {
      warn('状态读取')(err);
    }
  };

  document.querySelectorAll('[data-win]').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (btn.dataset.win === 'min') current.minimize().catch(warn('最小化'));
      else if (btn.dataset.win === 'max') current.toggleMaximize().catch(warn('缩放'));
      else if (btn.dataset.win === 'close') current.close().catch(warn('关闭'));
    });
  });
  // 拖到屏幕边缘吸附、双击标题栏、系统菜单都会走 resize，图标跟着同步
  if (current.onResized) current.onResized(syncMaximized).catch(warn('监听'));
  syncMaximized();
}

/* 列表滚动到底部附近时自动加载下一页；页脚的「加载更多」是手动兜底。 */
function setupTracksScroll() {
  const box = $('tracks');
  if (!box) return;
  box.addEventListener('scroll', () => {
    if (!state.tracksHasMore || state.tracksLoading) return;
    if (box.scrollTop + box.clientHeight >= box.scrollHeight - 260) loadMoreTracks();
  }, { passive: true });
}

async function boot() {
  setupSeek();
  setupTracksScroll();
  npSyncEntry();
  try {
    const data = await api('/api/state');
    state.user = data.user;
    state.demo = data.demo;
    state.desktop = Boolean(data.desktop);
    // 桌面壳专属样式（滚动条、顶栏工具条）；网页版保持系统原生与轻量排版
    document.documentElement.classList.toggle('is-desktop', state.desktop);
    state.settings = data.settings;
    state.version = data.version || state.version;
    // 主题：服务端存过就以服务端为准；老配置没有 theme 时，把本机缓存回写一次
    const serverTheme = data.settings && data.settings.theme;
    if (THEMES.includes(serverTheme)) applyTheme(serverTheme);
    else persistTheme(currentTheme());
    syncPreferencesFromSettings(data.settings);
    $('login-tabs').classList.toggle('hidden', !state.desktop);
    renderAccount();
    updateShuffleUi();
    applyPreferences();
    syncSettingsUi();
    if (!data.user.logged_in && !data.demo) {
      $('folder-meta').textContent = state.desktop
        ? '还没有登录，点「登录」扫码，或使用账号密码 / 短信验证码'
        : '还没有登录，点「登录」扫码';
      state.folderId = null;
      renderTracks();
      return;
    }
    await loadFolders();
  } catch (err) {
    $('folder-meta').textContent = `服务未就绪：${err.message}`;
  }
}

$('play').addEventListener('click', togglePlay);
$('prev').addEventListener('click', () => playNext(-1));
$('next').addEventListener('click', () => playNext(1));
$('shuffle').addEventListener('click', toggleShuffle);

// 播放详情
$('now').addEventListener('click', openNowPlaying);
$('open-now-playing').addEventListener('click', (evt) => {
  evt.stopPropagation();
  openNowPlaying();
});
// 底部播放条右侧的队列键：打开详情页，并直接把播放队列展开
$('queue-btn').addEventListener('click', () => {
  openNowPlaying();
  if (npOpen()) npSetQueue(true);
});
$('np-close').addEventListener('click', closeNowPlaying);
$('np-play').addEventListener('click', togglePlay);
$('np-art').addEventListener('click', togglePlay);
$('np-prev').addEventListener('click', () => playNext(-1));
$('np-next').addEventListener('click', () => playNext(1));
$('np-shuffle').addEventListener('click', toggleShuffle);
$('np-copy').addEventListener('click', copyTrackLink);
$('np-settings').addEventListener('click', () => {
  npSetEngine(false);
  openSettings('engine');
});
$('np-engine-settings').addEventListener('click', () => {
  npSetEngine(false);
  openSettings('engine');
});
$('np-engine-note-btn').addEventListener('click', async () => {
  await saveSettings({ btr_enabled: true });
  if (state.settings.btr_enabled) {
    showNpToast('已开启 BTR 多线程 · 下一首生效');
    npRenderEngine();
  } else {
    showNpToast('保存失败，请到设置里重试', true);
  }
});
$('np-volume').addEventListener('input', (evt) => {
  setPreference({ volume: Number(evt.target.value) });
});
$('np-queue-btn').addEventListener('click', () => {
  const open = !$('now-playing').classList.contains('np-queue-open');
  npSetQueue(open, { focus: open && npQueueIsOverlay() });
});
$('np-queue-close').addEventListener('click', () => npSetQueue(false, { focus: true }));
$('np-queue-scrim').addEventListener('click', () => npSetQueue(false, { focus: true }));
$('np-engine-pill').addEventListener('click', () => npSetEngine(!npEngineOpen()));
$('np-engine-close').addEventListener('click', () => npSetEngine(false, { focus: true }));
$('np-engine-backdrop').addEventListener('click', () => npSetEngine(false, { focus: true }));
document.querySelectorAll('[data-theme-choice]').forEach((btn) => {
  btn.addEventListener('click', () => {
    const theme = btn.dataset.themeChoice;
    if (theme === currentTheme()) return;
    applyTheme(theme);
    persistTheme(theme);
  });
});

$('login-btn').addEventListener('click', openLogin);
$('close-modal').addEventListener('click', () => closeLogin());
$('login-modal').addEventListener('click', (evt) => {
  if (evt.target === $('login-modal')) closeLogin();
});
$('qr-mask').addEventListener('click', beginQrLogin);
document.querySelectorAll('.login-tab').forEach((btn) => {
  btn.addEventListener('click', () => {
    if (btn.dataset.mode === 'qr') beginQrLogin();
    else beginDesktopLogin(btn.dataset.mode);
  });
});
$('login-window-cancel').addEventListener('click', async () => {
  $('login-window-status').textContent = '正在取消…';
  await cancelDesktopLogin();
  closeLogin(false);
});
$('login-window-retry').addEventListener('click', () => beginDesktopLogin(state.desktopMode));

// 收藏夹显示管理
$('manage-folders').addEventListener('click', () => openSettings('folders'));
$('empty-manage').addEventListener('click', () => openSettings('folders'));
$('show-all-folders').addEventListener('click', showAllFolders);
$('hide-all-folders').addEventListener('click', hideAllFolders);

// 设置窗口
$('settings-btn').addEventListener('click', () => openSettings());
$('close-settings').addEventListener('click', closeSettings);
$('settings-modal').addEventListener('click', (evt) => {
  if (evt.target === $('settings-modal')) closeSettings();
});
document.querySelectorAll('[data-settings-tab]').forEach((btn) => {
  btn.addEventListener('click', () => setSettingsTab(btn.dataset.settingsTab));
});
$('settings-account-login').addEventListener('click', () => {
  closeSettings();
  openLogin();
});
$('settings-account-logout').addEventListener('click', handleLogoutClick);

$('btr-toggle').addEventListener('change', (evt) => saveSettings({ btr_enabled: evt.target.checked }));
document.querySelectorAll('#settings-concurrency [data-value]').forEach((btn) => {
  btn.addEventListener('click', () => saveSettings({ concurrency: Number(btn.dataset.value) }));
});
document.querySelectorAll('#settings-chunk-size [data-value]').forEach((btn) => {
  btn.addEventListener('click', () => saveSettings({ chunk_size: Number(btn.dataset.value) }));
});
$('prefer-lossless').addEventListener('change', (evt) => {
  saveSettings({ prefer_lossless: evt.target.checked });
});
$('settings-volume').addEventListener('input', (evt) => {
  setPreference({ volume: Number(evt.target.value) });
});
$('autoplay-next').addEventListener('change', (evt) => {
  setPreference({ autoplayNext: evt.target.checked });
});

$('volume').addEventListener('input', (evt) => {
  setPreference({ volume: Number(evt.target.value) });
});
$('search').addEventListener('input', debounce((evt) => {
  if (state.folderId === null) return;
  const title = $('folder-title').textContent;
  loadTracks(title, evt.target.value.trim());
}, 350));

// 桌面壳：搜索框里的快捷键提示跟随系统习惯（macOS 用 ⌘）
if (/Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent)) {
  $('search-kbd').textContent = '⌘ K';
}

$('audio').addEventListener('timeupdate', () => {
  const audio = $('audio');
  const dur = audio.duration || (state.current && state.current.duration) || 0;
  if (!dur) return;
  const ratio = audio.currentTime / dur;
  $('seek-fill').style.width = `${Math.min(100, ratio * 100)}%`;
  $('seek-knob').style.left = `${Math.min(100, ratio * 100)}%`;
  $('t-cur').textContent = fmtTime(audio.currentTime);
  $('t-dur').textContent = fmtTime(dur);
  if (npOpen()) npRenderTime();
});

$('audio').addEventListener('progress', () => {
  const audio = $('audio');
  if (!audio.buffered.length || !audio.duration) return;
  const end = audio.buffered.end(audio.buffered.length - 1);
  $('seek-buf').style.width = `${Math.min(100, (end / audio.duration) * 100)}%`;
  if (npOpen()) npRenderBuffered();
});

$('audio').addEventListener('play', () => {
  syncPlayButton();
  startStats();
  if (npOpen()) npRenderPlayState();
  npSyncEntry();
});
$('audio').addEventListener('pause', () => {
  syncPlayButton();
  stopStats();
  if (npOpen()) {
    npRenderPlayState();
    npRenderEngine();
  }
  npSyncEntry();
});
$('audio').addEventListener('ended', () => {
  if (state.preferences.autoplayNext) {
    playNext(1);
    return;
  }
  syncPlayButton();
  if (npOpen()) npRenderPlayState();
  npSyncEntry();
});
$('audio').addEventListener('error', () => {
  if (!state.current) return;
  $('stats').textContent = '音频中断，正在重试…';
  const src = $('audio').src;
  setTimeout(() => {
    if (state.current && $('audio').src === src) $('audio').load();
  }, 800);
});

document.addEventListener('keydown', (evt) => {
  const settingsOpen = !$('settings-modal').classList.contains('hidden');
  const loginOpen = !$('login-modal').classList.contains('hidden');
  if (settingsOpen) {
    if (evt.code === 'Escape') { evt.preventDefault(); closeSettings(); }
    return;
  }
  if (loginOpen) {
    if (evt.code === 'Escape') { evt.preventDefault(); closeLogin(); }
    return;
  }

  const detailOpen = npOpen();
  if (detailOpen) {
    if (evt.code === 'Escape') {
      evt.preventDefault();
      // 先关最上面的一层：传输详情 → 覆盖式队列 → 页面本身
      if (npEngineOpen()) npSetEngine(false, { focus: true });
      else if (npQueueIsOverlay() && $('now-playing').classList.contains('np-queue-open')) npSetQueue(false, { focus: true });
      else closeNowPlaying();
      return;
    }
    if (evt.code === 'KeyF' && !evt.ctrlKey && !evt.metaKey && !evt.altKey) {
      evt.preventDefault();
      closeNowPlaying();
      return;
    }
    if (evt.code === 'KeyQ' && !evt.ctrlKey && !evt.metaKey && !evt.altKey) {
      evt.preventDefault();
      const open = !$('now-playing').classList.contains('np-queue-open');
      npSetQueue(open, { focus: open && npQueueIsOverlay() });
      return;
    }
    if (evt.code === 'Tab') { trapNpFocus(evt); return; }
    if ((evt.ctrlKey || evt.metaKey) && evt.code === 'KeyK') {
      evt.preventDefault();
      closeNowPlaying({
        after: () => {
          $('search').focus();
          $('search').select();
        },
      });
      return;
    }
  }

  // 桌面壳：⌘/Ctrl + K 聚焦收藏夹搜索
  if (state.desktop && (evt.ctrlKey || evt.metaKey) && evt.code === 'KeyK') {
    evt.preventDefault();
    $('search').focus();
    $('search').select();
    return;
  }

  const tag = (evt.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'select' || tag === 'textarea') return;
  if (!detailOpen && evt.code === 'KeyF' && !evt.ctrlKey && !evt.metaKey && !evt.altKey) {
    evt.preventDefault();
    openNowPlaying();
    return;
  }
  if (evt.code === 'Space') {
    // 焦点在按钮 / 链接上时交给控件自己的空格行为，避免一次按键触发两次
    const target = evt.target;
    if (target && target.closest && target.closest('button, a, [role="button"]')) return;
    evt.preventDefault();
    togglePlay();
  }
  else if (evt.code === 'ArrowRight') $('audio').currentTime += 5;
  else if (evt.code === 'ArrowLeft') $('audio').currentTime -= 5;
  else if (evt.code === 'KeyN') playNext(1);
  else if (evt.code === 'KeyP') playNext(-1);
  else if (evt.code === 'KeyS') toggleShuffle();
});

window.addEventListener('beforeunload', () => {
  stopStats();
  flushFolderSave();
  flushPreferenceSave();
});

initTheme();
syncPlayButton();
setupWindowChrome();
boot();
