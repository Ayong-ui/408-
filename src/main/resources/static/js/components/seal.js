// 朱印复选框：勾选时一枚"毕"字印落下。键盘、读屏按复选框处理。

import { ref } from '/vendor/vue.esm-browser.prod.js';

export default {
  name: 'SealBox',
  props: {
    modelValue: { type: Boolean, default: false },
    char: { type: String, default: '毕' },
    label: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['update:modelValue', 'toggle'],
  setup(props, { emit }) {
    const stamping = ref(false);
    function toggle(ev) {
      if (props.disabled) return;
      const next = !props.modelValue;
      if (next) {
        stamping.value = true;
        setTimeout(() => (stamping.value = false), 700);
      }
      emit('update:modelValue', next);
      emit('toggle', next, ev.currentTarget);
    }
    return { stamping, toggle };
  },
  template: `
  <button type="button" class="seal-box" :class="{ on: modelValue, stamping }" role="checkbox"
          :aria-checked="modelValue ? 'true' : 'false'" :aria-label="label" :disabled="disabled" @click="toggle">
    <span class="seal-mark" aria-hidden="true">{{ char }}</span>
  </button>`,
};
