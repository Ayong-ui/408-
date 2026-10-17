// 静室：入定计时、全部功法签、补记和修炼录。

import { computed, onMounted, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import { L, sub } from '../copy.js';
import { bumpData, confirmDialog, fail, refreshHome, store, toast } from '../store.js';
import { dayKey, dayLabel, fmtDur, hhmm, parseDay } from '../time.js';
import TimerPanel from '../components/timer.js';

export default {
  name: 'ChamberView',
  components: { TimerPanel },
  setup() {
    const plain = computed(() => store.settings && store.settings.plainMode);
    const methods = computed(() => (store.home ? store.home.methods : []));
    const records = ref([]);
    const spanDays = ref(30);
    const loading = ref(false);

    async function load() {
      if (!store.home) return;
      loading.value = true;
      try {
        const to = store.home.day;
        const from = parseDay(to);
        from.setDate(from.getDate() - spanDays.value + 1);
        records.value = await api.get('/sessions?from=' + dayKey(from) + '&to=' + to + '&limit=1000');
      } catch (e) {
        fail(e);
      } finally {
        loading.value = false;
      }
    }

    const groups = computed(() => {
      const map = new Map();
      for (const r of records.value) {
        if (!map.has(r.day)) map.set(r.day, { day: r.day, total: 0, rows: [] });
        const g = map.get(r.day);
        g.total += r.seconds;
        g.rows.push(r);
      }
      return [...map.values()];
    });

    async function remove(r) {
      const ok = await confirmDialog({
        title: '抹去这条修炼',
        message: '《' + r.methodName + '》' + fmtDur(r.seconds) + '，抹去后不计入修为。',
        okText: '抹去',
        danger: true,
      });
      if (!ok) return;
      try {
        await api.del('/sessions/' + r.id);
        toast('已抹去。', 'info');
        bumpData();
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    function more() {
      spanDays.value += 30;
      load();
    }

    watch(() => store.dataVersion, load);
    watch(() => store.home && store.home.day, (d, old) => { if (d && !old) load(); });
    onMounted(load);

    return {
      store, L, sub, methods, groups, loading, remove, more, spanDays,
      dayLabel, hhmm, dur: (s) => fmtDur(s, plain.value),
      manual: () => (store.manual = { methodId: store.home && store.home.lastMethodId }),
    };
  },
  template: `
  <div class="view chamber-view">
    <TimerPanel :methods="methods" :limit="0" big/>

    <section class="panel history">
      <header class="panel-head">
        <h2 class="panel-title">{{ L('history') }}<small v-if="sub('history')">{{ sub('history') }}</small></h2>
        <span class="panel-note">近 {{ spanDays }} 日</span>
        <button type="button" class="btn btn-ghost small" @click="manual">{{ L('manual') }}</button>
      </header>
      <div v-for="g in groups" :key="g.day" class="history-day">
        <h3 class="history-date">{{ dayLabel(g.day) }}<span>共 {{ dur(g.total) }}</span></h3>
        <ul>
          <li v-for="r in g.rows" :key="r.id" class="history-row">
            <i class="vein" :style="{ background: r.color }"></i>
            <span class="history-time">{{ hhmm(r.startedAt) }}–{{ hhmm(r.endedAt) }}</span>
            <span class="history-method">《{{ r.methodName }}》<small v-if="r.chapter">{{ r.chapter }}</small></span>
            <span v-if="r.source === 'MANUAL'" class="badge">补记</span>
            <span class="history-dur">{{ dur(r.seconds) }}</span>
            <button type="button" class="btn-text faint" @click="remove(r)" :aria-label="'抹去这条修炼'">抹去</button>
          </li>
        </ul>
      </div>
      <p v-if="!groups.length && !loading" class="empty-note">近来尚无修炼。择一功法入定，修炼录便有了第一笔。</p>
      <div class="more"><button type="button" class="btn-text" @click="more" :disabled="loading">再往前翻 30 日</button></div>
    </section>
  </div>`,
};
