// 时间相关的格式化：时长、计时、炷香、时辰、农历、日期。

const CN = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九'];
const WEEK = ['一', '二', '三', '四', '五', '六', '日'];
const ZHI = '子丑寅卯辰巳午未申酉戌亥';

/** 0–99 的中文数字。 */
export function cnNum(n) {
  n = Math.max(0, Math.min(99, Math.floor(n)));
  if (n < 10) return CN[n];
  if (n < 20) return '十' + (n % 10 ? CN[n % 10] : '');
  return CN[Math.floor(n / 10)] + '十' + (n % 10 ? CN[n % 10] : '');
}

export function pad2(n) {
  return String(n).padStart(2, '0');
}

/** 计时器读数 01:02:03。 */
export function fmtClock(sec) {
  sec = Math.max(0, Math.floor(sec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return pad2(h) + ':' + pad2(m) + ':' + pad2(sec % 60);
}

/** 时长：3 时 05 分 / 52 分；白话模式用小时、分钟。 */
export function fmtDur(sec, plain = false) {
  sec = Math.max(0, Math.round(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h === 0 && m === 0) return sec > 0 ? '不足 1 分' : (plain ? '0 分钟' : '0 分');
  if (plain) return h ? h + ' 小时' + (m ? ' ' + m + ' 分' : '') : m + ' 分钟';
  return h ? h + ' 时 ' + pad2(m) + ' 分' : m + ' 分';
}

/** 坐标轴、提示里的小时数：2.5 时。 */
export function fmtHours(sec) {
  const h = sec / 3600;
  return (h >= 10 || Number.isInteger(h) ? Math.round(h) : h.toFixed(1)) + ' 时';
}

/** 用炷香描述一段时长：约一炷香又七分。 */
export function incenseText(sec, incenseMinutes) {
  const per = incenseMinutes * 60;
  const n = Math.floor(sec / per);
  const rest = Math.round((sec - n * per) / 60);
  if (n === 0) return rest >= incenseMinutes / 2 ? '过半炷香' : '不足半炷香';
  return '约' + (n === 2 ? '两' : cnNum(n)) + '炷香' + (rest ? '又' + cnNum(rest) + '分' : '');
}

/** 时辰：23 点是子初，0 点是子正；每个小时四刻。如 15:35 → 申初二刻。 */
export function shichen(d = new Date()) {
  const h = d.getHours();
  const idx = Math.floor(((h + 1) % 24) / 2);
  const ke = ['初', '一', '二', '三'][Math.floor(d.getMinutes() / 15)];
  return ZHI[idx] + (h % 2 === 1 ? '初' : '正') + ke + '刻';
}

export function shichenName(d = new Date()) {
  return ZHI[Math.floor(((d.getHours() + 1) % 24) / 2)] + '时';
}

const lunarFmt = (() => {
  try {
    return new Intl.DateTimeFormat('zh-CN-u-ca-chinese', { year: 'numeric', month: 'long', day: 'numeric' });
  } catch {
    return null;
  }
})();

function lunarDay(n) {
  if (n <= 10) return '初' + (n === 10 ? '十' : CN[n]);
  if (n < 20) return '十' + CN[n - 10];
  if (n === 20) return '二十';
  if (n < 30) return '廿' + CN[n - 20];
  return '三十';
}

/** 农历：丙午年 九月初六。浏览器不支持农历时返回空串。 */
export function lunarText(d = new Date()) {
  if (!lunarFmt) return '';
  let year = '';
  let month = '';
  let day = '';
  for (const p of lunarFmt.formatToParts(d)) {
    if (p.type === 'yearName') year = p.value;
    else if (p.type === 'month') month = p.value;
    else if (p.type === 'day') day = p.value;
  }
  const n = parseInt(day, 10);
  return (year ? year + '年 ' : '') + month + (Number.isFinite(n) ? lunarDay(n) : day);
}

export function weekdayCn(isoWeekday) {
  return '周' + WEEK[(isoWeekday - 1 + 7) % 7];
}

/** 'yyyy-MM-dd' → Date（本地零点）。 */
export function parseDay(day) {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function dayKey(date) {
  return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
}

/** 10月15日 周四 */
export function dayLabel(day) {
  const d = parseDay(day);
  return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + weekdayCn(d.getDay() || 7);
}

export function shortDay(day) {
  const d = parseDay(day);
  return (d.getMonth() + 1) + '/' + d.getDate();
}

export function hhmm(ms) {
  const d = new Date(ms);
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

/** 给 <input type="datetime-local"> 用的本地时间串。 */
export function toLocalInput(ms) {
  const d = new Date(ms);
  return dayKey(d) + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

export function fromLocalInput(value) {
  const t = new Date(value).getTime();
  return Number.isFinite(t) ? t : null;
}
