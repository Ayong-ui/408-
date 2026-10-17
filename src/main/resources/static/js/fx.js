// 轻量视觉反馈：飘字、震屏。都遵守特效强度设置。

import { effectLevel } from './store.js';

/** 从某个元素上方飘起一行字；靠近屏幕边缘时往里收，不被裁掉。 */
export function floatText(el, text, cls = '') {
  if (!el || !text || effectLevel() === 'none') return;
  const r = el.getBoundingClientRect();
  const span = document.createElement('span');
  span.className = 'float-text ' + cls;
  span.textContent = text;
  span.style.visibility = 'hidden';
  document.body.appendChild(span);
  const half = span.offsetWidth / 2;
  const center = Math.max(half + 8, Math.min(window.innerWidth - half - 8, r.left + r.width / 2));
  span.style.left = center + 'px';
  span.style.top = r.top + 'px';
  span.style.visibility = '';
  setTimeout(() => span.remove(), 1500);
}

/** 震屏只震主内容区：若震整个 #app，transform 会让里面 position: fixed 的弹窗跟着错位。 */
export function shake() {
  if (effectLevel() !== 'full') return;
  const el = document.querySelector('.main');
  if (!el) return;
  el.classList.remove('shake');
  void el.offsetWidth;
  el.classList.add('shake');
  setTimeout(() => el.classList.remove('shake'), 650);
}

let uid = 0;

/** SVG defs 需要页面内唯一的 id。 */
export function nextId(prefix) {
  uid += 1;
  return prefix + '-' + uid;
}
