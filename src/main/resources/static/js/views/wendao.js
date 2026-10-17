// 闻道阁：提交 B 站视频，字幕经 Claude 炼化成本地 Markdown 笔记；在这里看进度、读笔记。

import { computed, onBeforeUnmount, onMounted, ref } from '/vendor/vue.esm-browser.prod.js';
import { Marked } from '/vendor/marked.esm.js';
import { api } from '../api.js';
import { L, sub } from '../copy.js';
import { confirmDialog, fail, store, toast } from '../store.js';

const STATUS = {
  WAITING: { text: '等待字幕', cls: 'wait' },
  SUBTITLE_READY: { text: '字幕已获取', cls: 'busy' },
  REFINING: { text: '正在炼化玉简', cls: 'busy' },
  DONE: { text: '笔记已完成', cls: 'done' },
  FAILED: { text: '炼化失败', cls: 'failed' },
};
const BUSY = new Set(['WAITING', 'SUBTITLE_READY', 'REFINING']);
const TYPES = { theory: '理论讲解', demo: '实操演示', unknown: '' };

const ENV_HINTS = {
  python: '找不到 Python 虚拟环境（项目里的 .venv）。',
  script: '找不到炼化脚本 wendao/refine.py。',
  prompt: '找不到提示词 prompts/bilibili_note.md。',
  claude: '找不到 claude 命令：请先安装 Claude Code 并登录。',
  cookies: '没有放 B 站 cookies（config/bilibili-cookies.txt）。多数视频的字幕要登录才能拿到，做法见 README。',
};

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// 笔记由第三方字幕生成，按不可信内容渲染：原始 HTML 一律转义，只放行 http(s) 链接，不加载图片。
const md = new Marked({
  gfm: true,
  renderer: {
    html({ text }) {
      return escapeHtml(text);
    },
    link({ href, title, tokens }) {
      const text = this.parser.parseInline(tokens);
      if (!/^https?:\/\//i.test(href || '')) return text;
      return '<a href="' + escapeHtml(href) + '" target="_blank" rel="noopener noreferrer"'
        + (title ? ' title="' + escapeHtml(title) + '"' : '') + '>' + text + '</a>';
    },
    image({ text }) {
      return escapeHtml(text ? '〔图：' + text + '〕' : '〔图〕');
    },
  },
});

/** 去掉 YAML front matter，单独展示。 */
function splitFrontMatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!m) return { meta: [], body: text };
  const meta = m[1].split(/\r?\n/).map((line) => {
    const i = line.indexOf(':');
    return i > 0 ? [line.slice(0, i).trim(), line.slice(i + 1).trim().replace(/^"(.*)"$/, '$1')] : null;
  }).filter(Boolean);
  return { meta, body: text.slice(m[0].length) };
}

export default {
  name: 'WendaoView',
  setup() {
    const jobs = ref([]);
    const env = ref(null);
    const url = ref('');
    const methodId = ref(null);
    const busy = ref(false);
    const reader = ref(null);
    let timer = null;

    const methods = computed(() => (store.home ? store.home.methods : []));
    const envProblems = computed(() => {
      if (!env.value) return [];
      return Object.entries(ENV_HINTS).filter(([k]) => env.value[k] === false).map(([k, v]) => ({ key: k, text: v, soft: k === 'cookies' }));
    });

    async function loadJobs() {
      try {
        jobs.value = await api.get('/wendao/jobs');
      } catch (e) {
        fail(e);
      }
      schedule();
    }

    function schedule() {
      clearTimeout(timer);
      const anyBusy = jobs.value.some((j) => BUSY.has(j.status));
      timer = setTimeout(loadJobs, anyBusy ? 3000 : 30000);
    }

    async function submit() {
      if (!url.value.trim()) return;
      busy.value = true;
      try {
        await api.post('/wendao/jobs', { url: url.value.trim(), methodId: methodId.value || null });
        url.value = '';
        toast('玉简已入闻道阁，静候炼化。', 'info');
        await loadJobs();
      } catch (e) {
        fail(e);
      } finally {
        busy.value = false;
      }
    }

    async function retry(job) {
      try {
        await api.post('/wendao/jobs/' + job.id + '/retry');
        toast('重新炼化，旧笔记将被覆盖。', 'info');
        await loadJobs();
      } catch (e) {
        fail(e);
      }
    }

    async function remove(job) {
      const ok = await confirmDialog({
        title: '移出闻道阁',
        message: '只删除这条任务记录，本地的字幕和笔记文件会保留。',
        okText: '移出',
      });
      if (!ok) return;
      try {
        await api.del('/wendao/jobs/' + job.id);
        await loadJobs();
      } catch (e) {
        fail(e);
      }
    }

    async function read(job) {
      try {
        const note = await api.get('/wendao/jobs/' + job.id + '/note');
        const { meta, body } = splitFrontMatter(note.markdown);
        reader.value = { title: job.title || job.jobKey, url: job.url, path: note.path, meta, html: md.parse(body) };
      } catch (e) {
        fail(e);
      }
    }

    onMounted(async () => {
      loadJobs();
      try {
        env.value = await api.get('/wendao/status');
      } catch {
        env.value = null;
      }
    });
    onBeforeUnmount(() => clearTimeout(timer));

    return {
      L, sub, jobs, env, envProblems, url, methodId, methods, busy, reader,
      submit, retry, remove, read, STATUS, BUSY, TYPES,
      timeOf: (ms) => new Date(ms).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }),
    };
  },
  template: `
  <div class="view wendao">
    <header class="view-head">
      <h1 class="view-title">{{ L('wendao') }}<small v-if="sub('wendao')">{{ sub('wendao') }}</small></h1>
      <p class="view-note">B 站视频的字幕炼化成学习笔记，存在本机 data/notes。字幕会经 Claude 处理（发往 Anthropic 的服务）。</p>
    </header>

    <ul v-if="envProblems.length" class="env-list">
      <li v-for="p in envProblems" :key="p.key" :class="{ soft: p.soft }">{{ p.text }}</li>
    </ul>

    <form class="panel ornate wendao-form" @submit.prevent="submit">
      <label class="field grow">
        <span>玉简来处</span>
        <input class="input" v-model="url" placeholder="粘贴 B 站视频链接、b23.tv 短链或 BV 号" required>
      </label>
      <label class="field">
        <span>归入功法</span>
        <select class="input" v-model="methodId">
          <option :value="null">不归入</option>
          <option v-for="m in methods" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
      </label>
      <button type="submit" class="btn btn-seal" :disabled="busy">炼化</button>
    </form>

    <ul class="jobs">
      <li v-for="j in jobs" :key="j.id" class="job" :class="'st-' + STATUS[j.status].cls">
        <span class="job-furnace" :class="{ burning: BUSY.has(j.status) }" aria-hidden="true">丹</span>
        <div class="job-main">
          <a class="job-title" :href="j.url" target="_blank" rel="noopener noreferrer">{{ j.title || j.jobKey }}</a>
          <p class="job-meta">
            <span class="job-status">{{ STATUS[j.status].text }}</span>
            <span v-if="j.methodName">· {{ j.methodName }}</span>
            <span v-if="TYPES[j.videoType]">· {{ TYPES[j.videoType] }}</span>
            <span>· {{ timeOf(j.createdAt) }}</span>
          </p>
          <p v-if="j.message" class="job-msg">{{ j.message }}</p>
        </div>
        <div class="job-actions">
          <button v-if="j.hasNote" type="button" class="btn btn-jade small" @click="read(j)">阅读</button>
          <button v-if="!BUSY.has(j.status)" type="button" class="btn-text" @click="retry(j)">重新炼化</button>
          <button v-if="!BUSY.has(j.status)" type="button" class="btn-text faint" @click="remove(j)">移出</button>
        </div>
      </li>
    </ul>
    <p v-if="!jobs.length" class="empty-note">闻道阁尚无玉简。贴一个网课链接试试。</p>

    <transition name="veil">
      <div v-if="reader" class="veil" @click.self="reader = null" @keydown.esc="reader = null">
        <article class="reader" role="dialog" aria-modal="true" :aria-label="reader.title">
          <button type="button" class="close-x ink" @click="reader = null" aria-label="合上">×</button>
          <h2 class="reader-title">{{ reader.title }}</h2>
          <dl v-if="reader.meta.length" class="reader-meta">
            <template v-for="m in reader.meta" :key="m[0]"><dt>{{ m[0] }}</dt><dd>{{ m[1] }}</dd></template>
          </dl>
          <div class="markdown" v-html="reader.html"></div>
          <p class="reader-path">本地文件：{{ reader.path }}</p>
        </article>
      </div>
    </transition>
  </div>`,
};
