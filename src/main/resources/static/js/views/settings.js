// 布阵：洞府设置、日课模板、作息、劫数、道脉。

import { computed, nextTick, onMounted, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import { L, sub } from '../copy.js';
import { chime } from '../sound.js';
import { applyBodyClasses, bumpData, confirmDialog, fail, refreshHome, refreshPaths, store, toast } from '../store.js';

const WEEK = ['一', '二', '三', '四', '五', '六', '日'];
const EFFECTS = [
  { key: 'full', name: '全', desc: '雷劫、震屏、灵光' },
  { key: 'light', name: '轻', desc: '只留文字与印' },
  { key: 'none', name: '无', desc: '安安静静' },
];

export default {
  name: 'SettingsView',
  props: { section: { type: String, default: '' } },
  setup(props) {
    const form = ref(null);
    const routines = ref([]);
    const slots = ref([]);
    const countdowns = ref([]);
    const paths = ref([]);
    const newPath = ref({ name: '', color: '#8a8f96', core: false, body: false });

    async function load() {
      try {
        const [s, r, sl, c] = await Promise.all([
          api.get('/settings'), api.get('/routines'), api.get('/slots'), api.get('/countdowns'),
        ]);
        form.value = { ...s };
        routines.value = r.map((x) => ({ ...x }));
        slots.value = sl.map((x) => ({ ...x }));
        countdowns.value = c.map((x) => ({ ...x }));
        await refreshPaths();
        paths.value = store.paths.map((p) => ({ ...p }));
      } catch (e) {
        fail(e);
      }
    }

    async function saveSettings() {
      try {
        const s = await api.put('/settings', form.value);
        store.settings = s;
        form.value = { ...s };
        applyBodyClasses();
        toast('洞府阵法已调整。', 'info');
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    function hasDay(r, d) {
      return r.weekdays.includes(String(d));
    }

    function toggleDay(r, d) {
      const set = new Set(r.weekdays.split(''));
      if (set.has(String(d))) set.delete(String(d));
      else set.add(String(d));
      r.weekdays = [...set].sort().join('');
    }

    function move(list, i, d) {
      const j = i + d;
      if (j < 0 || j >= list.length) return;
      [list[i], list[j]] = [list[j], list[i]];
    }

    async function saveRoutines() {
      try {
        const saved = await api.put('/routines', routines.value.map((r) => ({
          id: r.id, name: r.name.trim(), pathId: r.pathId || null, weekdays: r.weekdays, note: (r.note || '').trim() || null, enabled: r.enabled,
        })));
        routines.value = saved.map((x) => ({ ...x }));
        toast('日课模板已定，明日起生效；今天新增的也已补上。', 'info');
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    async function saveSlots() {
      try {
        const saved = await api.put('/slots', slots.value.map((s) => ({
          id: s.id, name: s.name.trim(), startTime: s.startTime, endTime: s.endTime, hint: (s.hint || '').trim() || null,
        })));
        slots.value = saved.map((x) => ({ ...x }));
        toast('作息已定。', 'info');
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    async function saveCountdowns() {
      try {
        const saved = await api.put('/countdowns', countdowns.value.map((c) => ({
          id: c.id, name: c.name.trim(), day: c.day, enabled: c.enabled,
        })));
        countdowns.value = saved.map((x) => ({ ...x }));
        toast('劫数已记下。', 'info');
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    async function savePath(p) {
      try {
        const saved = await api.put('/paths/' + p.id, { name: p.name.trim(), color: p.color, core: p.core, body: p.body, sort: p.sort });
        Object.assign(p, saved);
        await refreshPaths();
        toast('道脉「' + saved.name + '」已改。', 'info');
        bumpData();
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    async function addPath() {
      const p = newPath.value;
      if (!p.name.trim()) return;
      try {
        await api.post('/paths', { name: p.name.trim(), color: p.color, core: p.core, body: p.body });
        newPath.value = { name: '', color: '#8a8f96', core: false, body: false };
        await refreshPaths();
        paths.value = store.paths.map((x) => ({ ...x }));
        toast('新道脉已开。', 'info');
      } catch (e) {
        fail(e);
      }
    }

    async function removePath(p) {
      const ok = await confirmDialog({ title: '封闭道脉', message: '道脉「' + p.name + '」将被移除（下面还有功法时不能移除）。', okText: '移除', danger: true });
      if (!ok) return;
      try {
        await api.del('/paths/' + p.id);
        await refreshPaths();
        paths.value = store.paths.map((x) => ({ ...x }));
      } catch (e) {
        fail(e);
      }
    }

    async function scrollToSection() {
      if (!props.section) return;
      await nextTick();
      const el = document.getElementById('sec-' + props.section);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    watch(() => props.section, scrollToSection);
    onMounted(async () => {
      await load();
      scrollToSection();
    });

    return {
      store, L, sub, form, routines, slots, countdowns, paths, newPath, WEEK, EFFECTS,
      saveSettings, hasDay, toggleDay, move, saveRoutines, saveSlots, saveCountdowns, savePath, addPath, removePath,
      hours: Array.from({ length: 13 }, (_, i) => i),
      testChime: () => chime(),
      pathName: (id) => (store.paths.find((p) => p.id === id) || {}).name || '不归道脉',
    };
  },
  template: `
  <div class="view settings" v-if="form">
    <header class="view-head">
      <h1 class="view-title">{{ L('settings') }}<small v-if="sub('settings')">{{ sub('settings') }}</small></h1>
    </header>

    <form id="sec-basic" class="panel" @submit.prevent="saveSettings">
      <header class="panel-head"><h2 class="panel-title">洞府</h2></header>
      <div class="form-grid">
        <label class="field"><span>道号</span><input class="input" v-model="form.daoName" maxlength="12" required></label>
        <label class="field"><span>日界线</span>
          <select class="input" v-model.number="form.dayStartHour">
            <option v-for="h in hours" :key="h" :value="h">凌晨 {{ h }} 点换日</option>
          </select>
          <small>这个时间之前的修炼算前一天</small>
        </label>
        <label class="field"><span>一炷香</span><span class="row"><input class="input narrow" type="number" min="5" max="180" v-model.number="form.incenseMinutes"> 分钟</span></label>
        <label class="field"><span>忘关提醒</span><span class="row"><input class="input narrow" type="number" min="30" max="720" v-model.number="form.idleAskMinutes"> 分钟无动静后问一句</span></label>
      </div>
      <div class="field">
        <span>特效强度</span>
        <div class="row wrap">
          <button v-for="e in EFFECTS" :key="e.key" type="button" class="chip" :class="{ on: form.effectLevel === e.key }" @click="form.effectLevel = e.key">
            {{ e.name }}<small>{{ e.desc }}</small>
          </button>
        </div>
      </div>
      <div class="row wrap toggles">
        <label class="toggle"><input type="checkbox" v-model="form.copyEnabled">随机修仙文案</label>
        <label class="toggle"><input type="checkbox" v-model="form.plainMode">白话模式（界面用语改成大白话）</label>
        <label class="toggle"><input type="checkbox" v-model="form.soundEnabled">钟磬声</label>
        <button type="button" class="btn-text" @click="testChime">试听</button>
      </div>
      <div class="form-actions"><button type="submit" class="btn btn-jade">定阵</button></div>
    </form>

    <section id="sec-routines" class="panel">
      <header class="panel-head"><h2 class="panel-title">日课模板<small>每天据此生成今日功课</small></h2></header>
      <div class="list-editor">
        <div v-for="(r, i) in routines" :key="r.id || 'n' + i" class="editor-row">
          <input class="input" v-model="r.name" maxlength="20" placeholder="功课名" aria-label="功课名">
          <select class="input" v-model="r.pathId" aria-label="道脉">
            <option :value="null">不归道脉</option>
            <option v-for="p in store.paths" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <span class="weekdays" role="group" aria-label="哪几天出现">
            <button v-for="(w, d) in WEEK" :key="d" type="button" class="day" :class="{ on: hasDay(r, d + 1) }" @click="toggleDay(r, d + 1)"
                    :aria-pressed="hasDay(r, d + 1) ? 'true' : 'false'">{{ w }}</button>
          </span>
          <input class="input" v-model="r.note" maxlength="40" placeholder="备注" aria-label="备注">
          <label class="toggle"><input type="checkbox" v-model="r.enabled">启用</label>
          <span class="row-tools">
            <button type="button" class="btn-text" @click="move(routines, i, -1)" aria-label="上移">↑</button>
            <button type="button" class="btn-text" @click="move(routines, i, 1)" aria-label="下移">↓</button>
            <button type="button" class="btn-text danger" @click="routines.splice(i, 1)">删去</button>
          </span>
        </div>
        <div class="editor-actions">
          <button type="button" class="btn-text" @click="routines.push({ id: null, name: '', pathId: null, weekdays: '1234567', note: '', enabled: true })">＋ 添一项日课</button>
          <span class="grow"></span>
          <button type="button" class="btn btn-jade" @click="saveRoutines">定下日课</button>
        </div>
      </div>
    </section>

    <section id="sec-slots" class="panel">
      <header class="panel-head"><h2 class="panel-title">作息<small>只是一天的落脚点，不是命令</small></h2></header>
      <div class="list-editor">
        <div v-for="(s, i) in slots" :key="s.id || 'n' + i" class="editor-row">
          <input class="input narrow" v-model="s.name" maxlength="10" placeholder="时段" aria-label="时段名">
          <input class="input" type="time" v-model="s.startTime" aria-label="开始">
          <span>至</span>
          <input class="input" type="time" v-model="s.endTime" aria-label="结束">
          <input class="input grow" v-model="s.hint" maxlength="40" placeholder="宜修什么" aria-label="宜修什么">
          <button type="button" class="btn-text danger" @click="slots.splice(i, 1)">删去</button>
        </div>
        <div class="editor-actions">
          <button type="button" class="btn-text" @click="slots.push({ id: null, name: '', startTime: '08:00', endTime: '12:00', hint: '' })">＋ 添一个时段</button>
          <span class="grow"></span>
          <button type="button" class="btn btn-jade" @click="saveSlots">定下作息</button>
        </div>
      </div>
    </section>

    <section id="sec-countdowns" class="panel">
      <header class="panel-head"><h2 class="panel-title">{{ L('countdown') }}<small v-if="sub('countdown')">{{ sub('countdown') }}</small></h2></header>
      <div class="list-editor">
        <div v-for="(c, i) in countdowns" :key="c.id || 'n' + i" class="editor-row">
          <input class="input" v-model="c.name" maxlength="10" placeholder="如：六级" aria-label="名称">
          <input class="input" type="date" v-model="c.day" aria-label="日期">
          <label class="toggle"><input type="checkbox" v-model="c.enabled">显示</label>
          <button type="button" class="btn-text danger" @click="countdowns.splice(i, 1)">删去</button>
        </div>
        <p v-if="!countdowns.length" class="empty-note">尚无劫数。六级、考研初试的日子可以记在这里。</p>
        <div class="editor-actions">
          <button type="button" class="btn-text" @click="countdowns.push({ id: null, name: '', day: '', enabled: true })">＋ 添一道劫数</button>
          <span class="grow"></span>
          <button type="button" class="btn btn-jade" @click="saveCountdowns">记下劫数</button>
        </div>
      </div>
    </section>

    <section id="sec-paths" class="panel">
      <header class="panel-head"><h2 class="panel-title">道脉<small>统计分类；三脉每日尽量接触，体修计入体修境界</small></h2></header>
      <div class="list-editor">
        <form v-for="p in paths" :key="p.id" class="editor-row" @submit.prevent="savePath(p)">
          <input class="input color" type="color" v-model="p.color" :aria-label="p.name + ' 的颜色'">
          <input class="input" v-model="p.name" maxlength="10" aria-label="道脉名">
          <label class="toggle"><input type="checkbox" v-model="p.core">三脉</label>
          <label class="toggle"><input type="checkbox" v-model="p.body">体修</label>
          <span class="row-tools">
            <button type="submit" class="btn-text">存</button>
            <button type="button" class="btn-text danger" @click="removePath(p)">移除</button>
          </span>
        </form>
        <form class="editor-row add" @submit.prevent="addPath">
          <input class="input color" type="color" v-model="newPath.color" aria-label="新道脉颜色">
          <input class="input" v-model="newPath.name" maxlength="10" placeholder="新道脉" aria-label="新道脉名">
          <label class="toggle"><input type="checkbox" v-model="newPath.core">三脉</label>
          <label class="toggle"><input type="checkbox" v-model="newPath.body">体修</label>
          <button type="submit" class="btn-text">＋ 开辟</button>
        </form>
        <p class="hint">默认配色已为深色背景和色弱用户校验过；自己改色时，相邻道脉尽量选色相差得远的颜色。</p>
      </div>
    </section>

    <section id="sec-about" class="panel about">
      <header class="panel-head"><h2 class="panel-title">洞府须知</h2></header>
      <ul>
        <li>全部数据在项目目录 data/ 下：xiuxian.db 是数据库，notes 是闻道阁笔记。备份就是复制 data 文件夹。</li>
        <li>修仙台只监听本机。手机经 Tailscale 访问，做法见 README。</li>
        <li>闻道阁的字幕会经 Claude 处理，会发送到 Anthropic 的服务。</li>
      </ul>
    </section>
  </div>
  <div v-else class="view loading"><p class="empty-note">灵气汇聚中…</p></div>`,
};
