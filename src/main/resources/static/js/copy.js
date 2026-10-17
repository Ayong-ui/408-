// 文案：界面用语（修仙 / 白话两套）和随机修仙短句。

import { store } from './store.js';

/** [修仙, 白话]。白话模式下显示后者；修仙模式下后者作为小字副标。 */
const LABELS = {
  home: ['洞府', '首页'],
  chamber: ['静室', '修炼'],
  library: ['藏经阁', '功法'],
  observatory: ['观星台', '收藏'],
  wendao: ['闻道阁', '视频笔记'],
  settings: ['布阵', '设置'],
  start: ['入定', '开始计时'],
  pause: ['调息', '暂停'],
  resume: ['续修', '继续'],
  finish: ['出关', '结束'],
  discard: ['此次不算', '作废'],
  manual: ['补记', '补录'],
  archive: ['束之高阁', '归档'],
  unarchive: ['取下', '取消归档'],
  scatter: ['散功', '删除'],
  daily: ['今日功课', '今日待办'],
  log: ['修行日志', '日志'],
  history: ['修炼录', '历史记录'],
  spirit: ['灵力分布', '时间分布'],
  trend: ['修炼趋势', '每日时长'],
  tools: ['法器栏', '工具'],
  portals: ['传送阵', '常用网站'],
  intel: ['情报簿', '收藏链接'],
  countdown: ['劫数', '考试倒计时'],
  cultivation: ['修为', '总学习时长'],
  body: ['体修', '健身时长'],
  meridians: ['三脉', '数学/408/英语'],
};

export function L(key) {
  const pair = LABELS[key];
  if (!pair) return key;
  return store.settings && store.settings.plainMode ? pair[1] : pair[0];
}

/** 修仙模式下的白话副标；白话模式下不需要。 */
export function sub(key) {
  const pair = LABELS[key];
  if (!pair || (store.settings && store.settings.plainMode)) return '';
  return pair[1];
}

const POOLS = {
  check: [
    '今日功课已毕，道心尚稳。',
    '灵力已入体。',
    '一笔勾销，心魔退散一分。',
    '又了一桩功课，灵台清明。',
    '功课既毕，可小憩片刻。',
    '道心又稳了一寸。',
  ],
  checkBody: [
    '锻体完成，肉身暂未荒废。',
    '气血翻涌，筋骨又硬了一分。',
    '肉身渐固，离金身又近一步。',
  ],
  allDone: [
    '今日功课尽数圆满，可以心安理得地摸鱼了。',
    '功课圆满，今日无愧于洞府。',
  ],
  start: [
    '闭目凝神，灵气入体。',
    '香已点燃，道友请。',
    '心无旁骛，入定。',
    '洞府已闭，外物勿扰。',
  ],
  finish: [
    '出关！灵力充盈。',
    '又精进了一分。',
    '一番苦修，道基更稳。',
    '收功吐纳，神清气爽。',
    '此番入定，颇有所得。',
  ],
  finishShort: [
    '半炷香也是修行。',
    '虽只片刻，亦是精进。',
  ],
  finishLong: [
    '久坐成仙，记得起身活动筋骨。',
    '长修归来，先喝口水再说。',
  ],
  incenseDone: [
    '一炷香燃尽，续上一炷。',
    '香尽一炷，道友还撑得住。',
  ],
  mastery: [
    '瓶颈松动，功法精进。',
    '豁然开朗，功法更上层楼。',
  ],
  breakthrough: [
    '修为精进，又破一层。',
    '灵力翻涌，境界松动。',
  ],
  log: [
    '已录入玉简。',
    '心得已铭刻。',
  ],
  welcome: [
    '道友云游归来，洞府一切如旧。',
  ],
};

const lastPicked = {};

/** 随机取一句，尽量不和上一句重复。关闭随机文案时返回空串。 */
export function pick(kind, force = false) {
  if (!force && store.settings && !store.settings.copyEnabled) return '';
  const pool = POOLS[kind];
  if (!pool || !pool.length) return '';
  let i = Math.floor(Math.random() * pool.length);
  if (pool.length > 1 && i === lastPicked[kind]) i = (i + 1) % pool.length;
  lastPicked[kind] = i;
  return pool[i];
}

const JI = [
  '在观星台流连过久',
  '只看答案不动笔',
  '熬夜炼丹',
  '收藏不看',
  '只抄笔记不做题',
  '今天的事明天修',
  '一道极限题硬算到天亮',
  '边刷题边刷手机',
  '临阵磨枪',
  '为选功法纠结半日',
];

/** 天时栏的"忌"：按日期取，一天之内不变。 */
export function jiOfDay(day) {
  let h = 0;
  for (const c of day) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return JI[h % JI.length];
}

/** 天时栏的问候：深夜劝睡，清晨鼓劲。 */
export function greeting(date = new Date()) {
  const h = date.getHours();
  if (h >= 0 && h < 4) return '子时已过，道友当心走火入魔。';
  if (h < 6) return '寅卯之交，紫气东来。';
  if (h < 9) return '晨光正好，宜主修。';
  if (h >= 23) return '夜已深，今日之功可收矣。';
  return '';
}
