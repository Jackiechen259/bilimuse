/* BiliMuse 前端逻辑：无框架，纯 DOM。 */
'use strict';

const $ = (id) => document.getElementById(id);

// B 站收藏夹列表接口的 ps 上限：超过 40 会返回 -400「请求错误」
const FOLDER_PAGE_SIZE = 40;
const PREFS_KEY = 'bilimuse.preferences';
const DEFAULT_PREFERENCES = { volume: 0.9, autoplayNext: true };

const SETTINGS_TABS = {
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
    subtitle: '版本、运行信息与键盘快捷键',
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
  index: -1,
  current: null,      // 当前 track 元数据（含 stream id）
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
  // 随机播放：列表只加载前 40 条，随机池需要整夹（或全部搜索结果）
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
  state.folderId = null;
  state.tracks = [];
  state.index = -1;
  state.keyword = '';
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

async function loadTracks(title, keyword = '') {
  const box = $('tracks');
  state.keyword = keyword;
  $('folder-title').textContent = title || '收藏夹';
  $('folder-meta').textContent = '正在加载…';
  try {
    const qs = new URLSearchParams({ pn: '1', ps: String(FOLDER_PAGE_SIZE) });
    if (keyword) qs.set('keyword', keyword);
    const data = await api(`/api/folder/${state.folderId}?${qs}`);
    state.tracks = data.items || [];
    state.index = state.current
      ? state.tracks.findIndex((t) => t.bvid === state.current.bvid)
      : -1;
    $('folder-title').textContent = data.title || title || '收藏夹';
    const count = data.total || state.tracks.length;
    $('folder-meta').textContent = state.demo
      ? `演示模式 · ${count} 首（本地生成的音频，不需要登录）`
      : `${count} 个视频 · 点击任意一行开始播放`;
    renderTracks();
  } catch (err) {
    state.tracks = [];
    state.index = -1;
    $('folder-meta').textContent = err.message;
    if (err.status === 401) openLogin();
    renderTracks();
  }
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
  state.tracks.forEach((track, i) => {
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
    if (track.cover) cover.style.backgroundImage = `url(${track.cover})`;
    else cover.textContent = '♪';
    const text = document.createElement('div');
    text.className = 'title-text';
    const line = document.createElement('div');
    line.textContent = track.title;
    const sub = document.createElement('div');
    sub.className = 'sub';
    sub.textContent = track.duration ? fmtTime(track.duration) : '';
    text.append(line, sub);
    cell.append(cover, text);

    const upper = document.createElement('div');
    upper.className = 'upper';
    upper.textContent = track.upper || '';

    const dur = document.createElement('div');
    dur.className = 'dur';
    dur.textContent = fmtTime(track.duration);

    row.append(idx, cell, upper, dur);
    row.addEventListener('click', () => playIndex(i));
    box.appendChild(row);
  });
}

/* ------------------------------------------------------------------ 播放 */

function playIndex(i) {
  if (i < 0 || i >= state.tracks.length) return;
  startTrack(state.tracks[i], i, currentView());
}

async function playTrackNow(track) {
  state.requestedBvid = track.bvid;
  const token = ++state.playToken;

  $('now-title').textContent = track.title;
  $('now-sub').textContent = track.upper || '';
  $('quality').textContent = '解析中…';
  $('now-cover').style.backgroundImage = track.cover ? `url(${track.cover})` : '';
  $('now-cover').textContent = track.cover ? '' : '♪';
  $('stats').textContent = '正在探测 CDN 节点…';
  document.querySelectorAll('.track').forEach((el) => {
    el.classList.toggle('active', el.dataset.bvid === track.bvid);
  });

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
  } catch (err) {
    $('stats').textContent = err.message;
    $('quality').textContent = '—';
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
  if (next >= state.tracks.length) return;
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
  try {
    const s = await api(`/api/stats/${state.current.id}`);
    const nodeLine = (s.nodes || []).slice(0, 2)
      .map((n) => n.host.replace('.bilivideo.com', '').replace('.akamaized.net', ''))
      .join(' · ');
    const live = s.recent_speed || 0;
    const ready = (s.progress || 0) >= 0.995;
    if (s.mode === 'btr') {
      const head = ready ? `已缓冲 ${Math.round((s.progress || 0) * 100)}%` : fmtSpeed(live);
      $('stats').innerHTML =
        `<span style="color:#fb7299">BTR</span> ${s.concurrency} 并发 · ${head} · ` +
        `${s.chunks_done}/${s.chunks_total} 块<br>` +
        `<span style="opacity:.7">${nodeLine || '单节点'}</span>`;
      const hint = $('engine-hint');
      if (hint) hint.textContent =
        `${s.nodes.length} 节点 · ${ready ? '已就绪' : fmtSpeed(live)} · 对冲 ${s.hedges} 次`;
      const summary = $('engine-summary');
      if (summary) summary.textContent =
        `BTR · ${s.concurrency} 并发 · ${ready ? '已就绪' : fmtSpeed(live)}`;
    } else {
      $('stats').innerHTML = `<span style="opacity:.7">单连接</span> · ${fmtSpeed(live)}`;
      const hint = $('engine-hint');
      if (hint) hint.textContent = 'BTR 已关闭，单连接直通';
      const summary = $('engine-summary');
      if (summary) summary.textContent = '单连接 · 直通';
    }
  } catch (_) { /* 忽略瞬时错误 */ }
}

function updateMediaSession() {
  if (!('mediaSession' in navigator) || !state.current) return;
  const t = state.current;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: t.title,
    artist: t.upper || 'B站收藏夹',
    album: 'BiliMuse',
    artwork: t.cover ? [{ src: t.cover, sizes: '512x512', type: 'image/jpeg' }] : [],
  });
  navigator.mediaSession.setActionHandler('previoustrack', () => playNext(-1));
  navigator.mediaSession.setActionHandler('nexttrack', () => playNext(1));
}

/* ------------------------------------------------------------------ 进度条 */

function setupSeek() {
  const seek = $('seek');
  let dragging = false;

  const ratioFromEvent = (evt) => {
    const rect = seek.getBoundingClientRect();
    return Math.min(1, Math.max(0, (evt.clientX - rect.left) / rect.width));
  };

  const apply = (ratio) => {
    const audio = $('audio');
    const dur = audio.duration || (state.current && state.current.duration) || 0;
    if (!dur) return;
    audio.currentTime = ratio * dur;
    $('seek-fill').style.width = `${ratio * 100}%`;
    $('seek-knob').style.left = `${ratio * 100}%`;
    $('t-cur').textContent = fmtTime(audio.currentTime);
  };

  seek.addEventListener('pointerdown', (evt) => {
    dragging = true;
    seek.setPointerCapture(evt.pointerId);
    apply(ratioFromEvent(evt));
  });
  seek.addEventListener('pointermove', (evt) => {
    if (dragging) apply(ratioFromEvent(evt));
  });
  seek.addEventListener('pointerup', (evt) => {
    dragging = false;
    try { seek.releasePointerCapture(evt.pointerId); } catch (_) { /* ignore */ }
  });
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
  state.folderId = null;
  state.tracks = [];
  state.index = -1;
  state.current = null;
  state.playToken += 1;      // 让上一个账号正在解析的音轨作废
  state.keyword = '';
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

function applyPreferences() {
  const audio = $('audio');
  if (audio) audio.volume = state.preferences.volume;
  const playerVolume = $('volume');
  if (playerVolume) playerVolume.value = String(state.preferences.volume);
  const volume = $('settings-volume');
  if (volume) volume.value = String(state.preferences.volume);
  const percent = Math.round(state.preferences.volume * 100);
  const value = $('settings-volume-value');
  if (value) value.textContent = `${percent}%`;
  if (volume) {
    volume.style.background =
      `linear-gradient(90deg, var(--pink) 0%, #c86dd7 ${percent}%, #343b4c ${percent}%, #343b4c 100%)`;
  }
  const autoplay = $('autoplay-next');
  if (autoplay) autoplay.checked = state.preferences.autoplayNext;
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
  const showAll = $('show-all-folders');
  const hidden = hiddenFolderIds();
  const visible = visibleFolders();

  if (summary) {
    if (state.folders.length) {
      summary.textContent = `已显示 ${visible.length} / ${state.folders.length}`;
    } else {
      summary.textContent = state.user && state.user.logged_in ? '没有收藏夹' : '登录后可管理';
    }
  }
  if (showAll) {
    showAll.classList.toggle('hidden', !state.folders.length || hidden.size === 0);
  }

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
}

function updateEngineSummary() {
  const s = state.settings || {};
  const enabled = s.btr_enabled !== false;
  const summary = $('engine-summary');
  if (summary) {
    summary.textContent = enabled
      ? `BTR 多线程 · ${s.concurrency || 8} 并发`
      : '单连接 · 直通模式';
  }
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
  const engineMode = $('settings-engine-mode-inline');
  if (engineMode) {
    engineMode.textContent = enabled ? 'BTR 多线程' : '单连接直通';
  }
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

  const version = state.version || '0.2.2';
  ['settings-version', 'settings-version-inline'].forEach((id) => {
    const el = $(id);
    if (el) el.textContent = version;
  });
  const mode = state.desktop ? '桌面版' : '网页版';
  ['settings-runtime-mode', 'settings-runtime-mode-inline'].forEach((id) => {
    const el = $(id);
    if (el) el.textContent = mode;
  });

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
  setTimeout(() => $('close-settings').focus(), 30);
}

function closeSettings() {
  flushPreferenceSave();
  flushFolderSave();
  $('settings-modal').classList.add('hidden');
  document.body.classList.remove('settings-open');
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

async function boot() {
  setupSeek();
  try {
    const data = await api('/api/state');
    state.user = data.user;
    state.demo = data.demo;
    state.desktop = Boolean(data.desktop);
    state.settings = data.settings;
    state.version = data.version || state.version;
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

// 设置窗口
$('open-settings').addEventListener('click', () => openSettings());
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

$('audio').addEventListener('timeupdate', () => {
  const audio = $('audio');
  const dur = audio.duration || (state.current && state.current.duration) || 0;
  if (!dur) return;
  const ratio = audio.currentTime / dur;
  $('seek-fill').style.width = `${Math.min(100, ratio * 100)}%`;
  $('seek-knob').style.left = `${Math.min(100, ratio * 100)}%`;
  $('t-cur').textContent = fmtTime(audio.currentTime);
  $('t-dur').textContent = fmtTime(dur);
});

$('audio').addEventListener('progress', () => {
  const audio = $('audio');
  if (!audio.buffered.length || !audio.duration) return;
  const end = audio.buffered.end(audio.buffered.length - 1);
  $('seek-buf').style.width = `${Math.min(100, (end / audio.duration) * 100)}%`;
});

$('audio').addEventListener('play', () => { $('play').textContent = '❚❚'; startStats(); });
$('audio').addEventListener('pause', () => { $('play').textContent = '▶'; stopStats(); });
$('audio').addEventListener('ended', () => {
  if (state.preferences.autoplayNext) playNext(1);
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

  const tag = (evt.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'select' || tag === 'textarea') return;
  if (evt.code === 'Space') { evt.preventDefault(); togglePlay(); }
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

boot();
