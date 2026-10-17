// 静室：入定中显示香炉和计时；未入定时显示功法签，点一下就开始。

import { computed, ref } from '/vendor/vue.esm-browser.prod.js';
import { L, sub } from '../copy.js';
import {
  discardActive, finishSession, liveElapsed, pauseSession, resumeSession, startSession, store,
} from '../store.js';
import { cnNum, fmtClock } from '../time.js';
import Incense from './incense.js';
import MethodPicker from './picker.js';

export default {
  name: 'TimerPanel',
  components: { Incense, MethodPicker },
  props: {
    methods: { type: Array, default: () => [] },
    limit: { type: Number, default: 6 },
    big: { type: Boolean, default: false },
  },
  setup() {
    const busy = ref(false);
    const active = computed(() => store.active);
    const elapsed = computed(() => liveElapsed());
    const per = computed(() => ((store.settings && store.settings.incenseMinutes) || 45) * 60);
    const sticks = computed(() => Math.floor(elapsed.value / per.value));
    const progress = computed(() => (elapsed.value % per.value) / per.value);
    const running = computed(() => active.value && active.value.status === 'RUNNING');
    const state = computed(() => (!active.value ? 'idle' : running.value ? 'running' : 'paused'));
    const clock = computed(() => fmtClock(elapsed.value));
    const incenseLine = computed(() => {
      if (!active.value) return '';
      if (!running.value) return '调息中，香火暂熄';
      const n = sticks.value;
      return '第' + cnNum(n + 1) + '炷香' + (n ? ' · 已燃' + (n === 2 ? '两' : cnNum(n)) + '炷' : '');
    });

    async function run(fn, ...args) {
      if (busy.value) return;
      busy.value = true;
      try {
        await fn(...args);
      } finally {
        busy.value = false;
      }
    }

    return {
      store, L, sub, busy, active, elapsed, sticks, progress, running, state, clock, incenseLine,
      start: (m) => run(startSession, m.id),
      pause: () => run(pauseSession),
      resume: () => run(resumeSession),
      finish: () => run(finishSession),
      discard: () => run(discardActive),
      manual: () => (store.manual = { methodId: store.home && store.home.lastMethodId }),
    };
  },
  template: `
  <section class="panel ornate chamber" :class="{ big, active: !!active }">
    <header class="panel-head">
      <h2 class="panel-title">{{ L('chamber') }}<small v-if="sub('chamber')">{{ sub('chamber') }}</small></h2>
      <button type="button" class="btn-text" @click="manual">{{ L('manual') }}</button>
    </header>

    <div v-if="active" class="chamber-active">
      <Incense class="chamber-incense" :progress="progress" :state="state" :sticks="sticks"/>
      <div class="chamber-info">
        <div class="clock" :class="{ paused: !running }" role="timer" aria-live="off">{{ clock }}</div>
        <div class="chamber-method">《{{ active.methodName }}》<span v-if="active.chapter"> · {{ active.chapter }}</span></div>
        <div class="chamber-line">{{ incenseLine }}</div>
        <div class="chamber-actions">
          <button v-if="running" type="button" class="btn btn-ghost" :disabled="busy" @click="pause">
            {{ L('pause') }}<small v-if="sub('pause')">{{ sub('pause') }}</small>
          </button>
          <button v-else type="button" class="btn btn-jade" :disabled="busy" @click="resume">
            {{ L('resume') }}<small v-if="sub('resume')">{{ sub('resume') }}</small>
          </button>
          <button type="button" class="btn btn-seal" :disabled="busy" @click="finish">
            {{ L('finish') }}<small v-if="sub('finish')">{{ sub('finish') }}</small>
          </button>
        </div>
        <button type="button" class="btn-text faint" :disabled="busy" @click="discard">{{ L('discard') }}</button>
      </div>
    </div>

    <div v-else class="chamber-idle">
      <Incense class="chamber-incense" state="idle" :progress="0" :sticks="0"/>
      <div class="chamber-pick">
        <p class="chamber-hint">择一功法，点一下即{{ L('start') }}<template v-if="sub('start')">（{{ sub('start') }}）</template>。</p>
        <MethodPicker :methods="methods" :limit="limit" :disabled="busy" @pick="start"/>
        <p v-if="limit && methods.length > limit" class="chamber-more"><a href="#/chamber">全部功法 →</a></p>
      </div>
    </div>
  </section>`,
};
