// 灵力分布：阵盘样式的环形图。外圈八卦刻度慢慢运转；扇区之间留 2px 缝；悬停或键盘聚焦显示数值。
// 旁边的图例同时列出时长和占比，不依赖悬停也能读到全部数值。

import { computed, ref } from '/vendor/vue.esm-browser.prod.js';
import { fmtDur } from '../time.js';
import { store } from '../store.js';

const C = 120;
const R = 70;
const TRIGRAMS = ['☰', '☴', '☵', '☶', '☷', '☳', '☲', '☱'];

function polar(r, deg) {
  const rad = (deg * Math.PI) / 180;
  return [C + r * Math.cos(rad), C + r * Math.sin(rad)];
}

function arcPath(start, end) {
  if (end - start >= 359.9) {
    const [x0, y0] = polar(R, -90);
    const [x1, y1] = polar(R, 90);
    return 'M' + x0 + ' ' + y0 + ' A' + R + ' ' + R + ' 0 1 1 ' + x1 + ' ' + y1 + ' A' + R + ' ' + R + ' 0 1 1 ' + x0 + ' ' + y0;
  }
  const [x0, y0] = polar(R, start);
  const [x1, y1] = polar(R, end);
  const large = end - start > 180 ? 1 : 0;
  return 'M' + x0.toFixed(2) + ' ' + y0.toFixed(2) + ' A' + R + ' ' + R + ' 0 ' + large + ' 1 ' + x1.toFixed(2) + ' ' + y1.toFixed(2);
}

export default {
  name: 'Donut',
  props: {
    data: { type: Object, default: null },
    periodName: { type: String, default: '本周' },
    prevName: { type: String, default: '上周同期' },
  },
  setup(props) {
    const hover = ref(-1);
    const tipPos = ref({ x: 0, y: 0 });
    const fig = ref(null);
    const plain = computed(() => store.settings && store.settings.plainMode);

    const items = computed(() => ((props.data && props.data.items) || []).filter((i) => i.seconds > 0));
    const total = computed(() => (props.data ? props.data.totalSeconds : 0));

    const arcs = computed(() => {
      const list = items.value;
      const gap = list.length > 1 ? ((2 / R) * 180) / Math.PI : 0;
      let a = -90;
      return list.map((it) => {
        const sweep = it.ratio * 360;
        const start = a + gap / 2;
        const end = a + Math.max(sweep - gap / 2, gap / 2 + 0.01);
        a += sweep;
        return { d: arcPath(start, end), color: it.color, item: it };
      });
    });

    const ticks = computed(() => {
      const out = [];
      for (let i = 0; i < 48; i++) {
        const deg = i * 7.5 - 90;
        const long = i % 6 === 0;
        const [x0, y0] = polar(long ? 101 : 104, deg);
        const [x1, y1] = polar(110, deg);
        out.push({ x0, y0, x1, y1, long });
      }
      return out;
    });

    const trigrams = TRIGRAMS.map((g, i) => {
      const [x, y] = polar(92, i * 45 - 90);
      return { g, x, y };
    });

    function deltaText(cur, prev) {
      const diff = cur - prev;
      if (!prev && !cur) return '';
      if (Math.abs(diff) < 60) return '与' + props.prevName + '持平';
      return (diff > 0 ? '较' + props.prevName + '多 ' : '较' + props.prevName + '少 ') + fmtDur(Math.abs(diff), plain.value);
    }

    const totalDelta = computed(() => (props.data ? deltaText(props.data.totalSeconds, props.data.prevTotalSeconds) : ''));
    const totalUp = computed(() => props.data && props.data.totalSeconds >= props.data.prevTotalSeconds);

    const tip = computed(() => {
      const a = arcs.value[hover.value];
      if (!a) return null;
      const it = a.item;
      return {
        value: fmtDur(it.seconds, plain.value),
        name: it.name,
        ratio: Math.round(it.ratio * 1000) / 10 + '%',
        delta: deltaText(it.seconds, it.prevSeconds),
        color: it.color,
      };
    });

    function onMove(i, ev) {
      hover.value = i;
      const box = fig.value.getBoundingClientRect();
      tipPos.value = { x: ev.clientX - box.left, y: ev.clientY - box.top };
    }

    function onFocus(i) {
      hover.value = i;
      tipPos.value = { x: fig.value.clientWidth / 2, y: fig.value.clientHeight / 2 };
    }

    const summary = computed(() => {
      const parts = items.value.map((i) => i.name + ' ' + fmtDur(i.seconds, plain.value));
      return props.periodName + '共修 ' + fmtDur(total.value, plain.value) + (parts.length ? '：' + parts.join('，') : '');
    });

    const fmt = (s) => fmtDur(s, plain.value);
    const pct = (r) => Math.round(r * 1000) / 10 + '%';

    return { C, R, hover, tipPos, fig, items, total, arcs, ticks, trigrams, tip, totalDelta, totalUp, onMove, onFocus, summary, fmt, pct, deltaText };
  },
  template: `
  <div class="donut-wrap">
    <div class="donut-figure" ref="fig" @pointerleave="hover = -1">
      <svg viewBox="0 0 240 240" class="donut" role="img" :aria-label="summary">
        <g class="formation" aria-hidden="true">
          <circle :cx="C" :cy="C" r="112" class="ring"/>
          <circle :cx="C" :cy="C" r="99" class="ring faint"/>
          <line v-for="(t, i) in ticks" :key="i" :x1="t.x0" :y1="t.y0" :x2="t.x1" :y2="t.y1" :class="['tick', { long: t.long }]"/>
          <text v-for="t in trigrams" :key="t.g" :x="t.x" :y="t.y" class="trigram" text-anchor="middle" dominant-baseline="central">{{ t.g }}</text>
        </g>
        <circle :cx="C" :cy="C" :r="R" class="donut-track"/>
        <path v-for="(a, i) in arcs" :key="a.item.pathId" :d="a.d" :stroke="a.color"
              :class="['donut-arc', { on: hover === i, dim: hover >= 0 && hover !== i }]"
              tabindex="0" :aria-label="a.item.name + ' ' + fmt(a.item.seconds) + '，占 ' + pct(a.item.ratio)"
              @pointerenter="onMove(i, $event)" @pointermove="onMove(i, $event)" @focus="onFocus(i)" @blur="hover = -1"/>
        <text :x="C" :y="C - 6" class="donut-total" text-anchor="middle">{{ total > 0 ? fmt(total) : '尚未修炼' }}</text>
        <text :x="C" :y="C + 16" class="donut-label" text-anchor="middle">{{ periodName }}灵力</text>
      </svg>
      <div v-if="tip" class="chart-tip" :style="{ left: tipPos.x + 'px', top: tipPos.y + 'px' }">
        <strong>{{ tip.value }}</strong>
        <span class="tip-row"><i class="key" :style="{ background: tip.color }"></i>{{ tip.name }} · 占 {{ tip.ratio }}</span>
        <span v-if="tip.delta" class="tip-sub">{{ tip.delta }}</span>
      </div>
    </div>
    <div class="donut-side">
      <p v-if="totalDelta" class="donut-delta" :class="{ up: totalUp }">{{ totalDelta }}</p>
      <ul v-if="items.length" class="donut-legend">
        <li v-for="(it, i) in items" :key="it.pathId" :class="{ on: hover === i }" @pointerenter="hover = i" @pointerleave="hover = -1">
          <i class="swatch" :style="{ background: it.color }"></i>
          <span class="lg-name">{{ it.name }}</span>
          <span class="lg-val">{{ fmt(it.seconds) }}</span>
          <span class="lg-pct">{{ pct(it.ratio) }}</span>
        </li>
      </ul>
      <p v-else class="empty-note">此间灵气未聚。入定一次，阵盘便会亮起。</p>
    </div>
  </div>`,
};
