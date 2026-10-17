// 境界条：境界名 + 灵力进度 + 距下一境界还差多久。

import { computed } from '/vendor/vue.esm-browser.prod.js';
import { fmtDur } from '../time.js';
import { store } from '../store.js';

export default {
  name: 'RealmBar',
  props: {
    info: { type: Object, required: true },
    caption: { type: String, default: '' },
    compact: { type: Boolean, default: false },
    tone: { type: String, default: 'gold' }, // gold | jade | ink
  },
  setup(props) {
    const pct = computed(() => Math.round(props.info.progress * 1000) / 10 + '%');
    const remain = computed(() => {
      const i = props.info;
      if (i.nextSeconds == null) return '已臻至境';
      const plain = store.settings && store.settings.plainMode;
      return '距' + i.nextName + ' ' + fmtDur(i.nextSeconds - i.totalSeconds, plain);
    });
    return { pct, remain };
  },
  template: `
  <div class="realm-bar" :class="['tone-' + tone, { compact }]">
    <div class="realm-line">
      <span v-if="caption" class="realm-caption">{{ caption }}</span>
      <span class="realm-name">{{ info.name }}</span>
      <span class="realm-next">{{ remain }}</span>
    </div>
    <div class="bar" role="progressbar" :aria-valuenow="Math.round(info.progress * 100)" aria-valuemin="0" aria-valuemax="100"
         :aria-label="info.name + '，' + remain">
      <div class="bar-fill" :style="{ width: pct }"></div>
    </div>
  </div>`,
};
