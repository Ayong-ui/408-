// 观星台：传送阵（常逛的网站）和情报簿（手动收藏的链接）。自由探索区，不计入任何功课。

import { computed, onMounted, ref } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import { L, sub } from '../copy.js';
import { confirmDialog, fail, toast } from '../store.js';

const STATUSES = ['想研究', '已试用', '纯粹觉得很酷'];
const TONES = ['gold', 'jade', 'cinnabar', 'indigo'];

function hostOf(url) {
  try {
    return new URL(url).host.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** 传送阵的阵眼字：中文取首字，英文取首字母。 */
function glyphOf(name) {
  const ch = [...(name || '?').trim()][0] || '?';
  return /[a-z]/i.test(ch) ? ch.toUpperCase() : ch;
}

function toneOf(name) {
  let h = 0;
  for (const c of name || '') h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return TONES[h % TONES.length];
}

export default {
  name: 'ObservatoryView',
  setup() {
    const portals = ref([]);
    const editing = ref(null);
    const intel = ref([]);
    const filter = ref('');
    const draft = ref({ title: '', url: '', note: '', status: STATUSES[0] });

    async function load() {
      try {
        const [p, i] = await Promise.all([api.get('/portals'), api.get('/intel')]);
        portals.value = p;
        intel.value = i;
      } catch (e) {
        fail(e);
      }
    }

    const shown = computed(() => portals.value.filter((p) => p.enabled));
    const intelShown = computed(() => (filter.value ? intel.value.filter((i) => i.status === filter.value) : intel.value));

    function startEdit() {
      editing.value = portals.value.map((p) => ({ ...p }));
    }

    function addPortal() {
      editing.value.push({ id: null, name: '', url: 'https://', note: '', enabled: true });
    }

    function move(i, d) {
      const list = editing.value;
      const j = i + d;
      if (j < 0 || j >= list.length) return;
      [list[i], list[j]] = [list[j], list[i]];
    }

    async function savePortals() {
      try {
        portals.value = await api.put('/portals', editing.value.map((p) => ({
          id: p.id, name: p.name.trim(), url: p.url.trim(), note: (p.note || '').trim() || null, enabled: p.enabled,
        })));
        editing.value = null;
        toast('传送阵已重新布置。', 'info');
      } catch (e) {
        fail(e);
      }
    }

    async function addIntel() {
      const d = draft.value;
      if (!d.title.trim()) return;
      try {
        await api.post('/intel', { title: d.title.trim(), url: d.url.trim() || null, note: d.note.trim() || null, status: d.status });
        draft.value = { title: '', url: '', note: '', status: d.status };
        intel.value = await api.get('/intel');
        toast('已记入情报簿。', 'info');
      } catch (e) {
        fail(e);
      }
    }

    async function cycleStatus(item) {
      const next = STATUSES[(STATUSES.indexOf(item.status) + 1) % STATUSES.length];
      try {
        Object.assign(item, await api.put('/intel/' + item.id, { title: item.title, url: item.url, note: item.note, status: next }));
      } catch (e) {
        fail(e);
      }
    }

    async function removeIntel(item) {
      const ok = await confirmDialog({ title: '删去情报', message: '「' + item.title + '」将从情报簿中删去。', okText: '删去', danger: true });
      if (!ok) return;
      try {
        await api.del('/intel/' + item.id);
        intel.value = intel.value.filter((i) => i.id !== item.id);
      } catch (e) {
        fail(e);
      }
    }

    onMounted(load);

    return {
      L, sub, portals, shown, editing, intel, intelShown, filter, draft, STATUSES,
      startEdit, addPortal, move, savePortals, addIntel, cycleStatus, removeIntel,
      hostOf, glyphOf, toneOf, dateOf: (ms) => new Date(ms).toLocaleDateString('zh-CN'),
    };
  },
  template: `
  <div class="view observatory">
    <header class="view-head">
      <h1 class="view-title">{{ L('observatory') }}<small v-if="sub('observatory')">{{ sub('observatory') }}</small></h1>
      <p class="view-note">自由探索之地，不计功课，不催不扰。</p>
    </header>

    <section class="panel">
      <header class="panel-head">
        <h2 class="panel-title">{{ L('portals') }}<small v-if="sub('portals')">{{ sub('portals') }}</small></h2>
        <button v-if="!editing" type="button" class="btn-text" @click="startEdit">布阵</button>
      </header>

      <div v-if="!editing" class="portals">
        <a v-for="p in shown" :key="p.id" class="portal" :href="p.url" target="_blank" rel="noopener noreferrer">
          <span class="portal-eye" :class="'tone-' + toneOf(p.name)">{{ glyphOf(p.name) }}</span>
          <span class="portal-name">{{ p.name }}</span>
          <span class="portal-host">{{ p.note || hostOf(p.url) }}</span>
        </a>
        <p v-if="!shown.length" class="empty-note">尚无传送阵。点"布阵"添加常逛的网站。</p>
      </div>

      <form v-else class="list-editor" @submit.prevent="savePortals">
        <div v-for="(p, i) in editing" :key="i" class="editor-row">
          <input class="input" v-model="p.name" maxlength="20" placeholder="名称" required aria-label="名称">
          <input class="input grow" v-model="p.url" placeholder="https://" required aria-label="网址">
          <input class="input" v-model="p.note" maxlength="40" placeholder="备注" aria-label="备注">
          <label class="toggle"><input type="checkbox" v-model="p.enabled">显示</label>
          <span class="row-tools">
            <button type="button" class="btn-text" @click="move(i, -1)" aria-label="上移">↑</button>
            <button type="button" class="btn-text" @click="move(i, 1)" aria-label="下移">↓</button>
            <button type="button" class="btn-text danger" @click="editing.splice(i, 1)">删去</button>
          </span>
        </div>
        <div class="editor-actions">
          <button type="button" class="btn-text" @click="addPortal">＋ 添一座传送阵</button>
          <span class="grow"></span>
          <button type="button" class="btn btn-ghost" @click="editing = null">罢了</button>
          <button type="submit" class="btn btn-jade">定阵</button>
        </div>
      </form>
    </section>

    <section class="panel">
      <header class="panel-head">
        <h2 class="panel-title">{{ L('intel') }}<small v-if="sub('intel')">{{ sub('intel') }}</small></h2>
      </header>
      <form class="intel-form" @submit.prevent="addIntel">
        <input class="input" v-model="draft.title" maxlength="100" placeholder="看到什么有意思的？" required aria-label="标题">
        <input class="input" v-model="draft.url" placeholder="链接（可留空）" aria-label="链接">
        <input class="input" v-model="draft.note" maxlength="200" placeholder="一句备注（可留空）" aria-label="备注">
        <select class="input" v-model="draft.status" aria-label="状态">
          <option v-for="s in STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
        <button type="submit" class="btn btn-jade">记下</button>
      </form>
      <div class="filter-row">
        <button type="button" class="chip" :class="{ on: !filter }" @click="filter = ''">全部</button>
        <button v-for="s in STATUSES" :key="s" type="button" class="chip" :class="{ on: filter === s }" @click="filter = s">{{ s }}</button>
      </div>
      <ul class="intel-list">
        <li v-for="i in intelShown" :key="i.id" class="intel-item">
          <div class="intel-main">
            <a v-if="i.url" :href="i.url" target="_blank" rel="noopener noreferrer" class="intel-title">{{ i.title }}</a>
            <span v-else class="intel-title">{{ i.title }}</span>
            <p v-if="i.note" class="intel-note">{{ i.note }}</p>
          </div>
          <button type="button" class="chip small" :title="'点击切换状态'" @click="cycleStatus(i)">{{ i.status }}</button>
          <span class="intel-date">{{ dateOf(i.createdAt) }}</span>
          <button type="button" class="btn-text faint" @click="removeIntel(i)">删去</button>
        </li>
      </ul>
      <p v-if="!intelShown.length" class="empty-note">情报簿还是空的。</p>
    </section>
  </div>`,
};
