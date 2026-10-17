// 藏经阁：按道脉分层的书架。书脊越厚，功法越熟；点开看详情、换篇章、封存或散功。

import { computed, onMounted, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import { L, sub } from '../copy.js';
import { bumpData, confirmDialog, fail, refreshHome, startSession, store, toast } from '../store.js';
import { dayLabel, fmtDur, hhmm } from '../time.js';
import RealmBar from '../components/realm.js';

const SHORT = {
  未入门: '未', 初窥门径: '初窥', 略有小成: '小成', 登堂入室: '入室', 融会贯通: '贯通', 炉火纯青: '纯青', 登峰造极: '造极',
};

function emptyForm(pathId) {
  return { id: null, name: '', subject: '', chapter: '', pathId, description: '', sort: 0 };
}

export default {
  name: 'LibraryView',
  components: { RealmBar },
  setup() {
    const plain = computed(() => store.settings && store.settings.plainMode);
    const methods = ref([]);
    const selectedId = ref(null);
    const detail = ref(null);
    const form = ref(null);
    const chapterEdit = ref(null);

    async function load() {
      try {
        methods.value = await api.get('/methods?archived=all');
      } catch (e) {
        fail(e);
      }
    }

    async function loadDetail() {
      if (!selectedId.value) {
        detail.value = null;
        return;
      }
      try {
        detail.value = await api.get('/methods/' + selectedId.value);
      } catch (e) {
        fail(e);
        selectedId.value = null;
      }
    }

    const shelves = computed(() => {
      const byPath = new Map(store.paths.map((p) => [p.id, { path: p, books: [] }]));
      for (const m of methods.value) {
        if (m.archived) continue;
        if (!byPath.has(m.pathId)) byPath.set(m.pathId, { path: { id: m.pathId, name: m.pathName, color: m.color }, books: [] });
        byPath.get(m.pathId).books.push(m);
      }
      return [...byPath.values()].filter((s) => s.books.length);
    });
    const archived = computed(() => methods.value.filter((m) => m.archived));

    function spineStyle(m) {
      return { '--c': m.color, width: 42 + m.mastery.index * 5 + 'px' };
    }

    function open(m) {
      selectedId.value = selectedId.value === m.id ? null : m.id;
      chapterEdit.value = null;
    }

    function newMethod() {
      form.value = emptyForm(store.paths[0] && store.paths[0].id);
    }

    function editMethod() {
      const d = detail.value;
      form.value = { id: d.id, name: d.name, subject: d.subject || '', chapter: d.chapter || '', pathId: d.pathId, description: d.description || '', sort: d.sort };
    }

    async function saveForm() {
      const f = form.value;
      const body = {
        name: f.name.trim(), subject: f.subject.trim() || null, chapter: f.chapter.trim() || null,
        pathId: f.pathId, description: f.description.trim() || null, sort: f.sort,
      };
      try {
        const saved = f.id ? await api.put('/methods/' + f.id, body) : await api.post('/methods', body);
        form.value = null;
        toast(f.id ? '功法已修订。' : '新功法已录入藏经阁。', 'info');
        await load();
        selectedId.value = saved.id;
        afterChange();
      } catch (e) {
        fail(e);
      }
    }

    async function saveChapter() {
      const d = detail.value;
      try {
        await api.put('/methods/' + d.id, {
          name: d.name, subject: d.subject, chapter: (chapterEdit.value || '').trim() || null,
          pathId: d.pathId, description: d.description, sort: d.sort,
        });
        chapterEdit.value = null;
        toast('已换篇。', 'info');
        await load();
        afterChange();
      } catch (e) {
        fail(e);
      }
    }

    async function toggleArchive() {
      const d = detail.value;
      try {
        await api.post('/methods/' + d.id + (d.archived ? '/unarchive' : '/archive'));
        toast(d.archived ? '已从高阁取下。' : '已束之高阁。', 'info');
        await load();
        afterChange();
      } catch (e) {
        fail(e);
      }
    }

    async function scatter() {
      const d = detail.value;
      const ok = await confirmDialog({
        title: '散功',
        message: '《' + d.name + '》与它全部 ' + fmtDur(d.totalSeconds) + ' 的修炼记录将一并消散，无法恢复。若只是暂时不修，可以"束之高阁"。',
        okText: '散去',
        danger: true,
      });
      if (!ok) return;
      try {
        await api.del('/methods/' + d.id);
        selectedId.value = null;
        toast('功法已散。', 'info');
        await load();
        bumpData();
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    function afterChange() {
      loadDetail();
      refreshHome().catch(() => {});
    }

    async function practice() {
      await startSession(detail.value.id);
      location.hash = '#/chamber';
    }

    const dailyMax = computed(() => (detail.value ? Math.max(1, ...detail.value.daily.map((d) => d.seconds)) : 1));

    watch(selectedId, loadDetail);
    watch(() => store.dataVersion, () => { load(); loadDetail(); });
    onMounted(load);

    return {
      store, L, sub, plain, methods, shelves, archived, selectedId, detail, form, chapterEdit,
      spineStyle, open, newMethod, editMethod, saveForm, saveChapter, toggleArchive, scatter, practice, dailyMax,
      short: (name) => SHORT[name] || name, dayLabel, hhmm, dur: (s) => fmtDur(s, plain.value),
    };
  },
  template: `
  <div class="view library">
    <header class="view-head">
      <h1 class="view-title">{{ L('library') }}<small v-if="sub('library')">{{ sub('library') }}</small></h1>
      <button type="button" class="btn btn-seal" @click="newMethod">撰写新功法</button>
    </header>

    <div class="library-grid" :class="{ 'has-detail': !!detail }">
      <div class="shelves">
        <section v-for="s in shelves" :key="s.path.id" class="shelf">
          <h2 class="shelf-name"><i :style="{ background: s.path.color }"></i>{{ s.path.name }}</h2>
          <div class="shelf-books">
            <button v-for="m in s.books" :key="m.id" type="button" class="spine" :class="{ on: selectedId === m.id }"
                    :style="spineStyle(m)" @click="open(m)" :aria-label="m.name + '，' + m.mastery.name + '，累计 ' + dur(m.totalSeconds)">
              <span class="spine-label">{{ m.name }}</span>
              <span class="spine-seal">{{ short(m.mastery.name) }}</span>
            </button>
          </div>
          <div class="shelf-plank"></div>
        </section>
        <section v-if="archived.length" class="shelf high">
          <h2 class="shelf-name">高阁<small>束之高阁的功法</small></h2>
          <div class="shelf-books">
            <button v-for="m in archived" :key="m.id" type="button" class="spine archived" :class="{ on: selectedId === m.id }"
                    :style="spineStyle(m)" @click="open(m)" :aria-label="m.name + '（已封存）'">
              <span class="spine-label">{{ m.name }}</span>
              <span class="spine-seal">封</span>
            </button>
          </div>
          <div class="shelf-plank"></div>
        </section>
        <p v-if="!methods.length" class="empty-note">藏经阁空空如也。</p>
      </div>

      <aside v-if="detail" class="panel ornate method-detail">
        <button type="button" class="close-x" @click="selectedId = null" aria-label="合上">×</button>
        <h2 class="detail-name">《{{ detail.name }}》</h2>
        <p class="detail-sub">{{ detail.subject || detail.pathName }}<span v-if="detail.archived"> · 已束之高阁</span></p>
        <div class="detail-chapter">
          <template v-if="chapterEdit === null">
            <span>当前篇章：<b>{{ detail.chapter || '未分篇' }}</b></span>
            <button type="button" class="btn-text" @click="chapterEdit = detail.chapter || ''">换篇</button>
          </template>
          <form v-else class="row" @submit.prevent="saveChapter">
            <input class="input" v-model="chapterEdit" maxlength="30" placeholder="如：导数篇" aria-label="新篇章">
            <button type="submit" class="btn btn-jade small">定篇</button>
            <button type="button" class="btn-text" @click="chapterEdit = null">罢了</button>
          </form>
        </div>
        <p v-if="detail.description" class="detail-desc">{{ detail.description }}</p>

        <RealmBar :info="detail.mastery" caption="熟练度"/>
        <p class="detail-total">累计修炼 <b>{{ dur(detail.totalSeconds) }}</b></p>

        <div class="mini-bars" role="img" :aria-label="'近 30 日：' + detail.daily.filter(d => d.seconds).length + ' 日有修炼'">
          <i v-for="d in detail.daily" :key="d.day" :class="{ today: d.day === store.home?.day }"
             :style="{ height: Math.max(d.seconds ? 6 : 2, d.seconds / dailyMax * 100) + '%' }" :title="dayLabel(d.day) + ' ' + dur(d.seconds)"></i>
        </div>
        <p class="mini-caption">近三十日</p>

        <div v-if="detail.chapters.length" class="detail-block">
          <h3>各篇用功</h3>
          <ul class="kv-list">
            <li v-for="c in detail.chapters" :key="c.chapter"><span>{{ c.chapter }}</span><span>{{ dur(c.seconds) }} · {{ c.sessions }} 次</span></li>
          </ul>
        </div>
        <div v-if="detail.recent.length" class="detail-block">
          <h3>近来修炼</h3>
          <ul class="kv-list">
            <li v-for="r in detail.recent.slice(0, 8)" :key="r.id">
              <span>{{ dayLabel(r.day) }} {{ hhmm(r.startedAt) }}<small v-if="r.chapter"> · {{ r.chapter }}</small></span><span>{{ dur(r.seconds) }}</span>
            </li>
          </ul>
        </div>

        <div class="detail-actions">
          <button v-if="!detail.archived" type="button" class="btn btn-seal" @click="practice" :disabled="!!store.active">{{ L('start') }}</button>
          <button type="button" class="btn btn-ghost" @click="editMethod">修订</button>
          <button type="button" class="btn btn-ghost" @click="toggleArchive">{{ detail.archived ? L('unarchive') : L('archive') }}</button>
          <button type="button" class="btn-text danger" @click="scatter">{{ L('scatter') }}</button>
        </div>
      </aside>
    </div>

    <transition name="veil">
      <div v-if="form" class="veil" @click.self="form = null" @keydown.esc="form = null">
        <form class="dialog wide" role="dialog" aria-modal="true" aria-labelledby="method-form-title" @submit.prevent="saveForm">
          <h3 id="method-form-title" class="dialog-title">{{ form.id ? '修订功法' : '撰写新功法' }}</h3>
          <label class="field"><span>功法名</span><input class="input" v-model="form.name" maxlength="20" required placeholder="如：大衍算经"></label>
          <label class="field"><span>白话科目</span><input class="input" v-model="form.subject" maxlength="30" placeholder="如：高等数学"></label>
          <label class="field"><span>当前篇章</span><input class="input" v-model="form.chapter" maxlength="30" placeholder="如：极限篇，可留空"></label>
          <label class="field"><span>所属道脉</span>
            <select class="input" v-model="form.pathId" required>
              <option v-for="p in store.paths" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </label>
          <label class="field"><span>简述</span><textarea class="input" v-model="form.description" maxlength="200" rows="2" placeholder="资料、网课……可留空"></textarea></label>
          <div class="dialog-actions">
            <button type="button" class="btn btn-ghost" @click="form = null">罢了</button>
            <button type="submit" class="btn btn-seal">{{ form.id ? '修订' : '录入' }}</button>
          </div>
        </form>
      </div>
    </transition>
  </div>`,
};
