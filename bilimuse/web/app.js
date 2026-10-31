/* BiliMuse 前端逻辑：无框架，纯 DOM。 */
'use strict';

const $ = (id) => document.getElementById(id);

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
  settings: { btr_enabled: true, concurrency: 8 },
  playToken: 0,
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

/* ------------------------------------------------------------------ 账号 */

function renderAccount() {
  const u = state.user || {};
  $('account-name').textContent = u.logged_in ? (u.uname || `UID ${u.mid}`) : '未登录';
  $('account-hint').textContent = u.logged_in
    ? (u.vip ? '大会员 · 可听 Hi-Res' : '已登录')
    : (state.demo
      ? '演示模式，无需登录'
      : (state.desktop ? '扫码 / 密码 / 短信登录' : '扫码登录后读取你的收藏夹'));
  $('login-btn').textContent = u.logged_in ? '切换' : '登录';
  const avatar = $('avatar');
  if (u.face) {
    avatar.style.backgroundImage = `url(${u.face})`;
    avatar.textContent = '';
  } else {
    avatar.style.backgroundImage = '';
    avatar.textContent = (u.uname || 'B').slice(0, 1);
  }
}

/* ------------------------------------------------------------------ 收藏夹 */

async function loadFolders() {
  const box = $('folders');
  try {
    const data = await api('/api/folders');
    state.folders = data.folders || [];
    box.innerHTML = '';
    if (!state.folders.length) {
      box.innerHTML = '<div class="muted small" style="padding:8px 12px">没有收藏夹</div>';
      $('folder-count').textContent = '';
      return;
    }
    $('folder-count').textContent = state.folders.length;
    for (const folder of state.folders) {
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
    if (state.folderId === null) {
      const first = state.folders[0];
      selectFolder(first.id, first.title);
    } else {
      highlightFolder(state.folderId);
    }
  } catch (err) {
    box.innerHTML = '';
    const tip = document.createElement('div');
    tip.className = 'muted small';
    tip.style.padding = '8px 12px';
    tip.textContent = err.message;
    box.appendChild(tip);
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
  $('folder-title').textContent = title || '收藏夹';
  $('folder-meta').textContent = '正在加载…';
  try {
    const qs = new URLSearchParams({ pn: '1', ps: '50' });
    if (keyword) qs.set('keyword', keyword);
    const data = await api(`/api/folder/${state.folderId}?${qs}`);
    state.tracks = data.items || [];
    $('folder-title').textContent = data.title || title || '收藏夹';
    const count = data.total || state.tracks.length;
    $('folder-meta').textContent = state.demo
      ? `演示模式 · ${count} 首（本地生成的音频，不需要登录）`
      : `${count} 个视频 · 点击任意一行开始播放`;
    renderTracks();
  } catch (err) {
    state.tracks = [];
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
    $('empty-text').textContent = state.folderId === null ? '先在左侧选择一个收藏夹' : '这里还没有内容';
    return;
  }
  state.tracks.forEach((track, i) => {
    const row = document.createElement('div');
    row.className = 'track';
    row.dataset.index = String(i);
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

async function playIndex(i) {
  if (i < 0 || i >= state.tracks.length) return;
  state.index = i;
  const track = state.tracks[i];
  const token = ++state.playToken;

  $('now-title').textContent = track.title;
  $('now-sub').textContent = track.upper || '';
  $('quality').textContent = '解析中…';
  $('now-cover').style.backgroundImage = track.cover ? `url(${track.cover})` : '';
  $('now-cover').textContent = track.cover ? '' : '♪';
  $('stats').textContent = '正在探测 CDN 节点…';
  document.querySelectorAll('.track').forEach((el) => {
    el.classList.toggle('active', Number(el.dataset.index) === i);
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
      $('engine-hint').textContent =
        `${s.nodes.length} 节点 · ${ready ? '已就绪' : fmtSpeed(live)} · 对冲 ${s.hedges} 次`;
    } else {
      $('stats').innerHTML = `<span style="opacity:.7">单连接</span> · ${fmtSpeed(live)}`;
      $('engine-hint').textContent = 'BTR 已关闭，单连接直通';
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

/** 登录成功（含切换账号）：清掉上一个账号的收藏夹/播放列表。 */
function afterLogin(user) {
  state.user = user;
  state.folderId = null;
  state.tracks = [];
  state.index = -1;
  state.current = null;
  state.playToken += 1;      // 让上一个账号正在解析的音轨作废
  const audio = $('audio');
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  stopStats();
  $('now-title').textContent = '未在播放';
  $('now-sub').textContent = '选择一首开始';
  $('quality').textContent = '—';
  $('stats').textContent = 'BTR 待机';
  renderAccount();
  renderTracks();
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

async function saveSettings(patch) {
  try {
    const s = await api('/api/settings', { method: 'POST', body: JSON.stringify(patch) });
    state.settings = s;
    $('btr-toggle').checked = s.btr_enabled;
    $('concurrency').value = String(s.concurrency);
    $('engine-hint').textContent = s.btr_enabled
      ? `BTR 已开启 · ${s.concurrency} 并发 · ${Math.round(s.chunk_size / 1024)}KB/块`
      : 'BTR 已关闭，单连接直通';
  } catch (err) {
    $('engine-hint').textContent = err.message;
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
    $('login-tabs').classList.toggle('hidden', !state.desktop);
    renderAccount();
    $('btr-toggle').checked = data.settings.btr_enabled;
    $('concurrency').value = String(data.settings.concurrency);
    $('engine-hint').textContent = data.settings.btr_enabled
      ? `BTR 已开启 · ${data.settings.concurrency} 并发`
      : 'BTR 已关闭，单连接直通';
    if (!data.user.logged_in && !data.demo) {
      $('folder-meta').textContent = state.desktop
        ? '还没有登录，点右上角「登录」扫码，或使用账号密码 / 短信验证码'
        : '还没有登录，点右上角「登录」扫码';
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
$('btr-toggle').addEventListener('change', (evt) => saveSettings({ btr_enabled: evt.target.checked }));
$('concurrency').addEventListener('change', (evt) => saveSettings({ concurrency: Number(evt.target.value) }));
$('volume').addEventListener('input', (evt) => { $('audio').volume = Number(evt.target.value); });
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
$('audio').addEventListener('ended', () => playNext(1));
$('audio').addEventListener('error', () => {
  if (!state.current) return;
  $('stats').textContent = '音频中断，正在重试…';
  const src = $('audio').src;
  setTimeout(() => {
    if (state.current && $('audio').src === src) $('audio').load();
  }, 800);
});

document.addEventListener('keydown', (evt) => {
  const tag = (evt.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'select' || tag === 'textarea') return;
  if (evt.code === 'Space') { evt.preventDefault(); togglePlay(); }
  else if (evt.code === 'ArrowRight') $('audio').currentTime += 5;
  else if (evt.code === 'ArrowLeft') $('audio').currentTime -= 5;
  else if (evt.code === 'KeyN') playNext(1);
  else if (evt.code === 'KeyP') playNext(-1);
});

window.addEventListener('beforeunload', stopStats);

boot();
