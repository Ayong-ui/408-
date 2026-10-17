// 修炼趋势：每日总时长的柱状图。单一序列不需要图例；今天用金色突出，其余用宣纸色。
// 柱宽不超过 24px、顶端 4px 圆角、底端贴基线；悬停/聚焦整列都能触发提示。

import { computed, onBeforeUnmount, onMounted, ref } from '/vendor/vue.esm-browser.prod.js';
import { dayLabel, fmtDur, fmtHours, parseDay, shortDay } from '../time.js';
import { store } from '../store.js';

const H = 196;
const TOP = 24;
const BOTTOM = 28;
const LEFT = 40;
const RIGHT = 8;
const STEPS = [0.5, 1, 2, 3, 4, 6, 8, 12];
const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

function roundedTop(x, y, w, h) {
  const r = Math.min(4, w / 2, h);
  return 'M' + x + ' ' + (y + h) + ' V' + (y + r) + ' Q' + x + ' ' + y + ' ' + (x + r) + ' ' + y
    + ' H' + (x + w - r) + ' Q' + (x + w) + ' ' + y + ' ' + (x + w) + ' ' + (y + r) + ' V' + (y + h) + ' Z';
}

export default {
  name: 'Bars',
  props: {
    days: { type: Array, default: () => [] },
    today: { type: String, default: '' },
    paths: { type: Array, default: () => [] },
  },
  setup(props) {
    const el = ref(null);
    const width = ref(560);
    const hover = ref(-1);
    const tipPos = ref({ x: 0, y: 0 });
    const plain = computed(() => store.settings && store.settings.plainMode);
    let ro = null;

    onMounted(() => {
      ro = new ResizeObserver((entries) => {
        const w = Math.round(entries[0].contentRect.width);
        if (w > 0) width.value = w;
      });
      ro.observe(el.value);
    });
    onBeforeUnmount(() => ro && ro.disconnect());

    const plotH = H - TOP - BOTTOM;
    const plotW = computed(() => Math.max(60, width.value - LEFT - RIGHT));
    const slot = computed(() => plotW.value / Math.max(1, props.days.length));

    const scale = computed(() => {
      const maxH = Math.max(0, ...props.days.map((d) => d.seconds)) / 3600;
      if (maxH === 0) return { max: 2, step: 1 };
      const step = STEPS.find((s) => Math.ceil(maxH / s) <= 4) || 24;
      return { max: Math.max(step, Math.ceil(maxH / step) * step), step };
    });

    const yOf = (hours) => TOP + plotH - (hours / scale.value.max) * plotH;

    const ticks = computed(() => {
      const out = [];
      for (let v = 0; v <= scale.value.max + 1e-9; v += scale.value.step) out.push(v);
      return out;
    });

    const bars = computed(() => {
      const n = props.days.length;
      const bw = Math.min(24, Math.max(4, slot.value * 0.58));
      return props.days.map((d, i) => {
        const h = ((d.seconds / 3600) / scale.value.max) * plotH;
        const x = LEFT + i * slot.value + (slot.value - bw) / 2;
        const y = TOP + plotH - h;
        const date = parseDay(d.day);
        let label = '';
        if (n <= 7) label = d.day === props.today ? '今' : WEEK[date.getDay()];
        else if (i % 5 === (n - 1) % 5) label = d.day === props.today ? '今' : shortDay(d.day);
        return {
          day: d.day, seconds: d.seconds, byPath: d.byPath || {},
          x, y, w: bw, h, cx: x + bw / 2,
          path: h >= 0.5 ? roundedTop(x, y, bw, h) : '',
          today: d.day === props.today, label,
        };
      });
    });

    const todayBar = computed(() => bars.value.find((b) => b.today && b.seconds > 0));

    const pathById = computed(() => {
      const m = {};
      for (const p of props.paths) m[p.id] = p;
      return m;
    });

    const tip = computed(() => {
      const b = bars.value[hover.value];
      if (!b) return null;
      const rows = Object.entries(b.byPath)
        .map(([id, sec]) => ({ id, sec, path: pathById.value[id] }))
        .filter((r) => r.sec > 0)
        .sort((a, c) => c.sec - a.sec)
        .slice(0, 4)
        .map((r) => ({ id: r.id, name: r.path ? r.path.name : '其他', color: r.path ? r.path.color : '#737882', value: fmtDur(r.sec, plain.value) }));
      return { title: dayLabel(b.day), value: fmtDur(b.seconds, plain.value), rows };
    });

    function onMove(i, ev) {
      hover.value = i;
      const box = el.value.getBoundingClientRect();
      tipPos.value = { x: ev.clientX - box.left, y: ev.clientY - box.top };
    }

    function onFocus(i) {
      hover.value = i;
      const b = bars.value[i];
      tipPos.value = { x: b.cx, y: Math.min(b.y, TOP + plotH - 20) };
    }

    const summary = computed(() => '每日修炼时长：' + props.days.map((d) => dayLabel(d.day) + ' ' + fmtDur(d.seconds, plain.value)).join('，'));

    return { el, width, H, TOP, LEFT, RIGHT, plotH, slot, ticks, yOf, bars, todayBar, hover, tipPos, tip, onMove, onFocus, summary, fmtHours, fmtDur, plain };
  },
  template: `
  <div class="bars-wrap" ref="el" @pointerleave="hover = -1">
    <svg class="bars" :viewBox="'0 0 ' + width + ' ' + H" role="img" :aria-label="summary">
      <g class="grid">
        <g v-for="t in ticks" :key="t">
          <line :x1="LEFT" :x2="width - RIGHT" :y1="yOf(t)" :y2="yOf(t)" :class="{ base: t === 0 }"/>
          <text :x="LEFT - 8" :y="yOf(t)" text-anchor="end" dominant-baseline="central">{{ t === 0 ? '0' : fmtHours(t * 3600) }}</text>
        </g>
      </g>
      <path v-for="(b, i) in bars" :key="b.day" v-show="b.path" :d="b.path" :class="['bar', { today: b.today, on: hover === i }]"/>
      <text v-if="todayBar" class="bar-value" :x="todayBar.cx" :y="todayBar.y - 7" text-anchor="middle">{{ fmtDur(todayBar.seconds, plain) }}</text>
      <text v-for="b in bars" :key="'l' + b.day" v-show="b.label" class="bar-label" :class="{ today: b.today }"
            :x="b.cx" :y="H - 8" text-anchor="middle">{{ b.label }}</text>
      <rect v-for="(b, i) in bars" :key="'h' + b.day" class="hit" :x="LEFT + i * slot" :y="TOP" :width="slot" :height="plotH"
            tabindex="0" :aria-label="b.day + ' ' + fmtDur(b.seconds, plain)"
            @pointerenter="onMove(i, $event)" @pointermove="onMove(i, $event)" @focus="onFocus(i)" @blur="hover = -1"/>
    </svg>
    <div v-if="tip" class="chart-tip" :style="{ left: tipPos.x + 'px', top: tipPos.y + 'px' }">
      <strong>{{ tip.value }}</strong>
      <span class="tip-sub">{{ tip.title }}</span>
      <span v-for="r in tip.rows" :key="r.id" class="tip-row"><i class="key" :style="{ background: r.color }"></i>{{ r.name }} {{ r.value }}</span>
    </div>
  </div>`,
};
