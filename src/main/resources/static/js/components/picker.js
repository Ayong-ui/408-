// 功法签：点一下即入定。左侧色条是所属道脉。

import { computed } from '/vendor/vue.esm-browser.prod.js';

export default {
  name: 'MethodPicker',
  props: {
    methods: { type: Array, default: () => [] },
    limit: { type: Number, default: 0 },
    disabled: { type: Boolean, default: false },
  },
  emits: ['pick'],
  setup(props) {
    const list = computed(() => (props.limit ? props.methods.slice(0, props.limit) : props.methods));
    return { list };
  },
  template: `
  <div class="picker">
    <button v-for="m in list" :key="m.id" type="button" class="lot" :disabled="disabled" @click="$emit('pick', m)"
            :aria-label="'入定修炼 ' + m.name + (m.chapter ? ' ' + m.chapter : '')">
      <i class="lot-vein" :style="{ background: m.color }"></i>
      <span class="lot-name">{{ m.name }}</span>
      <span class="lot-sub">{{ m.chapter || m.subject || m.pathName }}</span>
    </button>
    <p v-if="!list.length" class="empty-note">藏经阁空空如也，先去撰写一部功法。</p>
  </div>`,
};
