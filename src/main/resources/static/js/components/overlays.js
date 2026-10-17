// 全局浮层：传音（轻提示）、确认框、"道友还在否"、补记。

import { computed, nextTick, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import {
  bumpData, closeConfirm, dismissToast, fail, finishSession, refreshHome, serverNow, showSettlement, store, touchSession,
} from '../store.js';
import { fmtDur, fromLocalInput, toLocalInput } from '../time.js';

export const Toasts = {
  name: 'Toasts',
  setup() {
    return { store, dismissToast };
  },
  template: `
  <div class="toasts" aria-live="polite">
    <transition-group name="toast">
      <button v-for="t in store.toasts" :key="t.id" type="button" class="toast" :class="'toast-' + t.kind" @click="dismissToast(t.id)">
        {{ t.text }}
      </button>
    </transition-group>
  </div>`,
};

export const ConfirmDialog = {
  name: 'ConfirmDialog',
  setup() {
    const okBtn = ref(null);
    watch(() => store.confirm, async (c) => {
      if (!c) return;
      await nextTick();
      if (okBtn.value) okBtn.value.focus();
    });
    return { store, closeConfirm, okBtn };
  },
  template: `
  <transition name="veil">
    <div v-if="store.confirm" class="veil" @click.self="closeConfirm(false)" @keydown.esc="closeConfirm(false)">
      <div class="dialog" role="alertdialog" aria-modal="true" :aria-label="store.confirm.title">
        <h3 class="dialog-title">{{ store.confirm.title }}</h3>
        <p class="dialog-text">{{ store.confirm.message }}</p>
        <div class="dialog-actions">
          <button type="button" class="btn btn-ghost" @click="closeConfirm(false)">罢了</button>
          <button ref="okBtn" type="button" class="btn" :class="store.confirm.danger ? 'btn-seal' : 'btn-jade'" @click="closeConfirm(true)">
            {{ store.confirm.okText }}
          </button>
        </div>
      </div>
    </div>
  </transition>`,
};

/** 入定太久没有动静时问一句。可以回"还在"，也可以按实际离开的时间出关。 */
export const IdleAsk = {
  name: 'IdleAsk',
  setup() {
    const endInput = ref('');
    const ask = computed(() => store.idleAsk && store.active && store.active.id === store.idleAsk.sessionId ? store.idleAsk : null);
    const since = computed(() => (ask.value ? fmtDur((serverNow() - ask.value.lastActiveAt) / 1000) : ''));

    watch(ask, (a) => {
      if (!a) return;
      const incense = ((store.settings && store.settings.incenseMinutes) || 45) * 60000;
      endInput.value = toLocalInput(Math.min(serverNow(), a.lastActiveAt + incense));
    });

    async function stay() {
      store.idleAsk = null;
      await touchSession();
    }

    async function leave() {
      const endAt = fromLocalInput(endInput.value);
      if (!endAt) return;
      store.idleAsk = null;
      await finishSession(Math.min(endAt, serverNow()));
    }

    return { ask, since, endInput, stay, leave, minInput: computed(() => store.active ? toLocalInput(store.active.startedAt) : ''), maxInput: computed(() => toLocalInput(serverNow())) };
  },
  template: `
  <transition name="veil">
    <div v-if="ask" class="veil">
      <div class="dialog" role="alertdialog" aria-modal="true" aria-labelledby="idle-title">
        <h3 id="idle-title" class="dialog-title">道友还在否？</h3>
        <p class="dialog-text">入定已逾 {{ since }}，其间洞府没有任何动静。</p>
        <div class="dialog-actions">
          <button type="button" class="btn btn-seal" @click="stay">还在修炼</button>
        </div>
        <div class="dialog-split">
          <p class="dialog-text small">早已离开？按实际离开的时间出关：</p>
          <div class="row">
            <input class="input" type="datetime-local" v-model="endInput" :min="minInput" :max="maxInput" aria-label="实际离开的时间">
            <button type="button" class="btn btn-ghost" @click="leave">按此时出关</button>
          </div>
        </div>
      </div>
    </div>
  </transition>`,
};

/** 补记：健身房、图书馆没带电脑、忘了开计时，事后补上。默认"刚修完"：开始时间 = 现在 − 时长，手动改过开始时间后不再联动。 */
export const ManualEntry = {
  name: 'ManualEntry',
  setup() {
    const form = ref(null);
    const busy = ref(false);
    const startTouched = ref(false);
    const methods = computed(() => (store.home && store.home.methods) || []);
    const quick = [30, 45, 60, 90, 120];

    watch(() => store.manual, (m) => {
      if (!m) return;
      const method = methods.value.find((x) => x.id === m.methodId) || methods.value[0];
      const minutes = 60;
      startTouched.value = false;
      form.value = {
        methodId: method ? method.id : null,
        minutes,
        startAt: toLocalInput(serverNow() - minutes * 60000),
        chapter: method ? method.chapter || '' : '',
        note: '',
      };
    });

    watch(() => form.value && form.value.minutes, (m) => {
      if (!form.value || startTouched.value || !(m > 0)) return;
      form.value.startAt = toLocalInput(serverNow() - m * 60000);
    });

    function onMethod() {
      const method = methods.value.find((x) => x.id === form.value.methodId);
      form.value.chapter = method ? method.chapter || '' : '';
    }

    function setMinutes(m) {
      form.value.minutes = m;
    }

    function close() {
      store.manual = null;
    }

    async function submit() {
      const f = form.value;
      const startAt = fromLocalInput(f.startAt);
      if (!f.methodId || !startAt || !(f.minutes > 0)) return;
      busy.value = true;
      try {
        const s = await api.post('/sessions/manual', {
          methodId: f.methodId,
          startAt,
          minutes: Math.round(f.minutes),
          chapter: f.chapter || null,
          note: f.note || null,
        });
        close();
        bumpData();
        refreshHome().catch(() => {});
        showSettlement(s);
      } catch (e) {
        fail(e);
      } finally {
        busy.value = false;
      }
    }

    return { store, form, busy, methods, quick, onMethod, setMinutes, close, submit, startTouched };
  },
  template: `
  <transition name="veil">
    <div v-if="store.manual && form" class="veil" @click.self="close" @keydown.esc="close">
      <form class="dialog wide" role="dialog" aria-modal="true" aria-labelledby="manual-title" @submit.prevent="submit">
        <h3 id="manual-title" class="dialog-title">补记修炼</h3>
        <p class="dialog-text small">健身房、图书馆没带电脑，或者忘了入定，事后补上，修为照样算。</p>
        <label class="field">
          <span>功法</span>
          <select class="input" v-model="form.methodId" @change="onMethod" required>
            <option v-for="m in methods" :key="m.id" :value="m.id">{{ m.name }}（{{ m.subject || m.pathName }}）</option>
          </select>
        </label>
        <div class="field">
          <span>时长</span>
          <div class="row wrap">
            <button v-for="q in quick" :key="q" type="button" class="chip" :class="{ on: form.minutes === q }" @click="setMinutes(q)">{{ q }} 分</button>
            <input class="input narrow" type="number" min="1" max="720" v-model.number="form.minutes" aria-label="分钟数"> 分钟
          </div>
        </div>
        <label class="field">
          <span>开始于</span>
          <input class="input" type="datetime-local" v-model="form.startAt" @input="startTouched = true" required>
        </label>
        <label class="field">
          <span>篇章</span>
          <input class="input" v-model="form.chapter" maxlength="30" placeholder="可留空">
        </label>
        <label class="field">
          <span>备注</span>
          <input class="input" v-model="form.note" maxlength="100" placeholder="可留空">
        </label>
        <div class="dialog-actions">
          <button type="button" class="btn btn-ghost" @click="close">罢了</button>
          <button type="submit" class="btn btn-seal" :disabled="busy">补记</button>
        </div>
      </form>
    </div>
  </transition>`,
};
