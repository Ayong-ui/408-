// 香炉一炷香：计时器的意境部分。香随时间燃短，香头明灭，烟缕上升；调息时香火转暗、烟停。

import { computed } from '/vendor/vue.esm-browser.prod.js';
import { nextId } from '../fx.js';

const STUB_X = [-14, 14, -25, 25, -35, 35];

export default {
  name: 'Incense',
  props: {
    progress: { type: Number, default: 0 },   // 当前这炷香燃了多少 0..1
    state: { type: String, default: 'idle' }, // idle | running | paused
    sticks: { type: Number, default: 0 },     // 已燃尽的炷数
  },
  setup(props) {
    const ids = {
      glow: nextId('glow'),
      fade: nextId('fade'),
      mask: nextId('mask'),
      blur: nextId('blur'),
    };
    const url = (id) => 'url(#' + id + ')';
    const topY = computed(() => 164 - 110 * (1 - Math.max(0, Math.min(1, props.progress))));
    const stubs = computed(() => STUB_X.slice(0, Math.min(props.sticks, STUB_X.length)));
    const label = computed(() => {
      if (props.state === 'idle') return '香炉：香已备好';
      return '香炉：已燃 ' + props.sticks + ' 炷，当前一炷燃了 ' + Math.round(props.progress * 100) + '%';
    });
    return { ids, url, topY, stubs, label };
  },
  template: `
  <svg class="incense" :class="state" viewBox="0 -65 200 355" role="img" :aria-label="label">
    <defs>
      <radialGradient :id="ids.glow">
        <stop offset="0" stop-color="#ffd8a0" stop-opacity=".95"/>
        <stop offset=".35" stop-color="#ff8c4c" stop-opacity=".5"/>
        <stop offset="1" stop-color="#ff5a28" stop-opacity="0"/>
      </radialGradient>
      <linearGradient :id="ids.fade" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="-140">
        <stop offset="0" stop-color="#000"/>
        <stop offset=".1" stop-color="#fff"/>
        <stop offset=".55" stop-color="#999"/>
        <stop offset="1" stop-color="#000"/>
      </linearGradient>
      <mask :id="ids.mask" maskUnits="userSpaceOnUse" x="-50" y="-150" width="100" height="156">
        <rect x="-50" y="-150" width="100" height="156" :fill="url(ids.fade)"/>
      </mask>
      <filter :id="ids.blur" x="-60%" y="-20%" width="220%" height="140%">
        <feGaussianBlur stdDeviation="1.3"/>
      </filter>
    </defs>

    <image href="/art/incense/incense-shadow.webp" x="-28" y="40" width="256" height="256"/>
    <image href="/art/incense/incense-body.webp" x="-28" y="40" width="256" height="256"/>

    <g class="stubs">
      <g v-for="dx in stubs" :key="dx">
        <line :x1="100 + dx" y1="170" :x2="100 + dx" y2="160" stroke="#8e2a20" stroke-width="2.4"/>
        <line :x1="100 + dx" y1="160" :x2="100 + dx" y2="157" stroke="#6b665c" stroke-width="2.4"/>
      </g>
    </g>

    <line x1="100" y1="170" x2="100" y2="157" stroke="#605047" stroke-width="2"/>
    <line class="stick" x1="100" y1="164" x2="100" :y2="topY" stroke="#655348" stroke-width="1.8"/>

    <g :transform="'translate(100 ' + topY + ')'">
      <circle class="glow" r="9" :fill="url(ids.glow)"/>
      <circle class="ember" r="2.2"/>
      <g class="smoke" :mask="url(ids.mask)">
        <g :filter="url(ids.blur)">
          <path class="wisp w1" d="M0 -2 C -4 -16, 6 -28, 1 -42 S -8 -68, -2 -86 S 8 -114, 0 -132"/>
          <path class="wisp w2" d="M0 -2 C 5 -14, -5 -30, 2 -46 S 10 -72, 3 -92 S -6 -120, 4 -138"/>
          <path class="wisp w3" d="M0 -2 C -3 -12, 4 -24, -1 -38 S -10 -60, -4 -80 S 4 -106, -3 -126"/>
        </g>
      </g>
    </g>
  </svg>`,
};
