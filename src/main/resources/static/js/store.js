// 全局状态与修炼动作。各页面共用一份首页数据和进行中的修炼。

import { reactive } from '/vendor/vue.esm-browser.prod.js';
import { api } from './api.js';
import { pick } from './copy.js';
import { chime } from './sound.js';

export const store = reactive({
  home: null,          // GET /api/home 的结果
  settings: null,
  paths: [],           // 道脉
  active: null,        // 进行中的修炼（ActiveSession）
  activeNow: 0,        // 收到 active 时的服务器时间
  offset: 0,           // 服务器时间 − 本机时间
  now: Date.now(),     // 每秒更新，驱动计时显示
  settlement: null,    // 出关卷轴
  tribulation: null,   // 渡劫动画
  toasts: [],
  confirm: null,       // 确认框
  idleAsk: null,       // "道友还在否"
  manual: null,        // 补记弹窗
  homeError: '',
  range: 'week',       // 统计范围 today | week | month
  dataVersion: 0,      // 修炼数据有变化时 +1，统计和列表据此重新拉取
});

export function serverNow() {
  return store.now + store.offset;
}

function syncClock(serverTime, sentAt) {
  const receivedAt = Date.now();
  store.offset = serverTime - (sentAt + receivedAt) / 2;
}

export function setActive(active, now) {
  store.active = active;
  store.activeNow = now;
}

/** 进行中修炼的实时有效时长（秒）。 */
export function liveElapsed() {
  const a = store.active;
  if (!a) return 0;
  if (a.status !== 'RUNNING') return a.elapsedSeconds;
  return a.elapsedSeconds + Math.max(0, (serverNow() - store.activeNow) / 1000);
}

export function bumpData() {
  store.dataVersion++;
}

export async function refreshHome() {
  const sentAt = Date.now();
  try {
    const home = await api.get('/home');
    syncClock(home.now, sentAt);
    store.home = home;
    store.settings = home.settings;
    setActive(home.active, home.now);
    store.homeError = '';
    applyBodyClasses();
    return home;
  } catch (e) {
    store.homeError = e.message;
    throw e;
  }
}

export async function refreshActive() {
  const sentAt = Date.now();
  const r = await api.get('/sessions/active');
  syncClock(r.now, sentAt);
  setActive(r.active, r.now);
}

export async function refreshPaths() {
  store.paths = await api.get('/paths');
}

export function applyBodyClasses() {
  const s = store.settings;
  if (!s) return;
  const cls = document.body.classList;
  for (const lv of ['full', 'light', 'none']) cls.toggle('fx-' + lv, s.effectLevel === lv);
  cls.toggle('plain', !!s.plainMode);
}

export function effectLevel() {
  return (store.settings && store.settings.effectLevel) || 'full';
}

// —— 传音（轻提示） ——

let toastSeq = 0;

export function toast(text, kind = 'copy', ms = 2600) {
  if (!text) return;
  const id = ++toastSeq;
  store.toasts.push({ id, text, kind });
  if (store.toasts.length > 3) store.toasts.shift();
  setTimeout(() => dismissToast(id), ms);
}

export function dismissToast(id) {
  const i = store.toasts.findIndex((t) => t.id === id);
  if (i >= 0) store.toasts.splice(i, 1);
}

export function fail(e) {
  toast(e && e.message ? e.message : String(e), 'error', 4200);
}

/** 确认框，返回 Promise<boolean>。 */
export function confirmDialog({ title, message, okText = '确定', danger = false }) {
  return new Promise((resolve) => {
    store.confirm = { title, message, okText, danger, resolve };
  });
}

export function closeConfirm(result) {
  const c = store.confirm;
  store.confirm = null;
  if (c) c.resolve(result);
}

// —— 修炼动作 ——

async function sessionCall(path, body) {
  const sentAt = Date.now();
  const r = await api.post(path, body);
  syncClock(r.now, sentAt);
  setActive(r.active, r.now);
  return r;
}

export async function startSession(methodId) {
  try {
    await sessionCall('/sessions/start', { methodId });
    if (store.settings && store.settings.soundEnabled) chime();
    toast(pick('start'));
    refreshHome().catch(() => {});
  } catch (e) {
    fail(e);
    refreshActive().catch(() => {});
  }
}

export async function pauseSession() {
  if (!store.active) return;
  try {
    await sessionCall('/sessions/' + store.active.id + '/pause');
  } catch (e) {
    fail(e);
    refreshActive().catch(() => {});
  }
}

export async function resumeSession() {
  if (!store.active) return;
  try {
    await sessionCall('/sessions/' + store.active.id + '/resume');
  } catch (e) {
    fail(e);
    refreshActive().catch(() => {});
  }
}

export async function touchSession() {
  if (!store.active) return;
  try {
    await sessionCall('/sessions/' + store.active.id + '/touch');
  } catch (e) {
    fail(e);
  }
}

/** 出关。endAt 为修正后的结束时间（忘关时用），不传则按现在。 */
export async function finishSession(endAt) {
  const a = store.active;
  if (!a) return;
  let s;
  try {
    s = await api.post('/sessions/' + a.id + '/finish', endAt ? { endAt } : {});
  } catch (e) {
    fail(e);
    refreshActive().catch(() => {});
    return;
  }
  store.active = null;
  store.idleAsk = null;
  bumpData();
  refreshHome().catch(() => {});
  if (store.settings && store.settings.soundEnabled) chime(0.8);
  showSettlement(s);
}

/** 出关或补记之后：大境界变化先渡劫，再展开结算卷轴。 */
export function showSettlement(s) {
  if (s.discarded) {
    toast('不足一分，此次不计。', 'info');
    return;
  }
  if (s.realm && s.realm.major && effectLevel() !== 'none') {
    store.tribulation = { settlement: s };
  } else {
    store.settlement = s;
  }
}

export async function discardActive() {
  const a = store.active;
  if (!a) return;
  const ok = await confirmDialog({
    title: '此次不算',
    message: '这次入定的时间将不计入修为，确定吗？',
    okText: '不算了',
    danger: true,
  });
  if (!ok) return;
  try {
    await api.del('/sessions/' + a.id);
    store.active = null;
    toast('此番入定已作罢。', 'info');
    refreshHome().catch(() => {});
  } catch (e) {
    fail(e);
  }
}

// —— 今日功课 ——

export async function setItemDone(item, done) {
  const before = item.done;
  item.done = done;
  try {
    const updated = await api.put('/daily-items/' + item.id, { done });
    Object.assign(item, updated);
    if (done) {
      const items = (store.home && store.home.items) || [];
      if (items.length && items.every((i) => i.done)) toast(pick('allDone'));
    }
    bumpData();
    refreshHome().catch(() => {});
    return true;
  } catch (e) {
    item.done = before;
    fail(e);
    return false;
  }
}
