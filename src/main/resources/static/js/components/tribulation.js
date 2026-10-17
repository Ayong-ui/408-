// 渡劫：大境界变化时的过场。第一次出关是温和的"引气入体"；其后每个大境界都要挨一道雷劫。
// 点击任意处跳过；特效强度为"轻"时只有文字，没有闪电和震屏。结束后展开出关卷轴。

import { computed, onBeforeUnmount, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { effectLevel, store } from '../store.js';
import { shake } from '../fx.js';

export default {
  name: 'Tribulation',
  setup() {
    const t = computed(() => store.tribulation);
    const phase = ref(0);
    const level = ref('full');
    let timers = [];

    const before = computed(() => t.value && t.value.settlement.realm.before);
    const after = computed(() => t.value && t.value.settlement.realm.after);
    const first = computed(() => !!before.value && before.value.major === '凡人');
    const title = computed(() => (!after.value ? '' : first.value ? '引气入体' : after.value.major + '已成'));
    const subtitle = computed(() => (!after.value ? '' : first.value ? '自此踏上仙途 · ' + after.value.name : '雷劫已过 · ' + after.value.name));

    function clear() {
      timers.forEach(clearTimeout);
      timers = [];
    }

    function done() {
      clear();
      const v = store.tribulation;
      store.tribulation = null;
      phase.value = 0;
      if (v) store.settlement = v.settlement;
    }

    watch(t, (v) => {
      clear();
      if (!v) return;
      level.value = effectLevel();
      phase.value = 1;
      const storm = !first.value && level.value === 'full';
      if (storm) {
        timers.push(setTimeout(shake, 420));
        timers.push(setTimeout(shake, 1080));
      }
      timers.push(setTimeout(() => (phase.value = 2), storm ? 1250 : 500));
      timers.push(setTimeout(done, storm ? 3900 : 3200));
    }, { immediate: true });

    onBeforeUnmount(clear);

    const motes = Array.from({ length: 18 }, (_, i) => {
      const angle = (i / 18) * Math.PI * 2;
      const dist = 38 + (i % 3) * 9;
      return {
        '--dx': Math.cos(angle) * dist + 'vmin',
        '--dy': Math.sin(angle) * dist + 'vmin',
        '--delay': (i % 6) * 0.08 + 's',
      };
    });

    return { t, phase, level, first, title, subtitle, done, motes };
  },
  template: `
  <div v-if="t" class="tribulation" :class="['phase-' + phase, first ? 'gentle' : 'storm', 'lv-' + level]"
       role="dialog" aria-modal="true" :aria-label="title" @click="done" @keydown.esc="done" tabindex="-1">
    <div class="trib-clouds"></div>
    <template v-if="!first && level === 'full'">
      <svg class="bolt b1" viewBox="0 0 120 600" preserveAspectRatio="xMidYMin meet" aria-hidden="true">
        <polyline points="70,0 52,150 76,160 40,320 70,330 30,520"/>
      </svg>
      <svg class="bolt b2" viewBox="0 0 120 600" preserveAspectRatio="xMidYMin meet" aria-hidden="true">
        <polyline points="50,0 66,120 44,132 78,280 50,292 86,460"/>
      </svg>
      <div class="trib-flash"></div>
    </template>
    <div v-if="first" class="trib-gather" aria-hidden="true">
      <i v-for="(m, i) in motes" :key="i" :style="m"></i>
    </div>
    <div class="trib-text">
      <h2>{{ title }}</h2>
      <p>{{ subtitle }}</p>
      <small>点击继续</small>
    </div>
  </div>`,
};
