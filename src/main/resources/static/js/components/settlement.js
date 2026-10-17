// 出关卷轴：展开宣纸，进度条从出关前长到出关后；跨过门槛时落一枚"突破/精进/蜕变"印。

import { computed, nextTick, ref, watch } from '/vendor/vue.esm-browser.prod.js';
import { api } from '../api.js';
import { pick } from '../copy.js';
import { bumpData, confirmDialog, fail, refreshHome, store, toast } from '../store.js';
import { fmtDur, incenseText } from '../time.js';
import SealBox from './seal.js';

function widthOf(before, after, shown) {
  if (!shown) return (after.index > before.index ? 0 : before.progress) * 100 + '%';
  return after.progress * 100 + '%';
}

function remainText(info, plain) {
  if (info.nextSeconds == null) return '已臻至境';
  return '距' + info.nextName + ' ' + fmtDur(info.nextSeconds - info.totalSeconds, plain);
}

export default {
  name: 'Settlement',
  components: { SealBox },
  setup() {
    const s = computed(() => store.settlement);
    const shown = ref(false);
    const sealed = ref(false);
    const suggestDone = ref(false);
    const copy = ref('');
    const closeBtn = ref(null);
    const plain = computed(() => store.settings && store.settings.plainMode);

    const masteryUp = computed(() => s.value && s.value.method.after.index > s.value.method.before.index);
    const realmUp = computed(() => s.value && s.value.realm.breakthrough);
    const sealText = computed(() => {
      if (!s.value) return '';
      if (realmUp.value) return s.value.realm.kind === 'BODY' ? '蜕变' : '突破';
      return masteryUp.value ? '精进' : '';
    });

    function chooseCopy(v) {
      const sec = v.session.seconds;
      if (v.realm.breakthrough) return pick('breakthrough');
      if (v.method.after.index > v.method.before.index) return pick('mastery');
      if (sec < 15 * 60) return pick('finishShort');
      if (sec > 150 * 60) return pick('finishLong');
      return pick('finish');
    }

    watch(s, async (v) => {
      shown.value = false;
      sealed.value = false;
      suggestDone.value = false;
      if (!v) return;
      copy.value = chooseCopy(v);
      await nextTick();
      if (closeBtn.value) closeBtn.value.focus();
      requestAnimationFrame(() => requestAnimationFrame(() => (shown.value = true)));
      if (sealText.value) setTimeout(() => { if (store.settlement === v) sealed.value = true; }, 1150);
    }, { immediate: true });

    const title = computed(() => (s.value && s.value.session.source === 'MANUAL' ? '补 记' : '出 关'));
    const realmLabel = computed(() => (s.value && s.value.realm.kind === 'BODY' ? '体修' : '修为'));
    const incense = computed(() => s.value ? incenseText(s.value.session.seconds, (store.settings && store.settings.incenseMinutes) || 45) : '');
    const masteryWidth = computed(() => s.value ? widthOf(s.value.method.before, s.value.method.after, shown.value) : '0%');
    const realmWidth = computed(() => s.value ? widthOf(s.value.realm.before, s.value.realm.after, shown.value) : '0%');
    const masteryRemain = computed(() => s.value ? remainText(s.value.method.after, plain.value) : '');
    const realmRemain = computed(() => s.value ? remainText(s.value.realm.after, plain.value) : '');
    const dur = (sec) => fmtDur(sec, plain.value);

    function close() {
      store.settlement = null;
    }

    async function onSuggest(done) {
      if (!done || !s.value.suggestItem) return;
      try {
        await api.put('/daily-items/' + s.value.suggestItem.id, { done: true });
        toast(pick('check'));
        bumpData();
        refreshHome().catch(() => {});
      } catch (e) {
        suggestDone.value = false;
        fail(e);
      }
    }

    async function discard() {
      const v = s.value;
      const ok = await confirmDialog({
        title: '此次不算',
        message: '这次修炼将从修炼录中抹去，不计入修为。',
        okText: '抹去',
        danger: true,
      });
      if (!ok) return;
      try {
        await api.del('/sessions/' + v.session.id);
        close();
        toast('此番修炼已抹去。', 'info');
        bumpData();
        refreshHome().catch(() => {});
      } catch (e) {
        fail(e);
      }
    }

    return { s, shown, sealed, sealText, suggestDone, copy, closeBtn, title, realmLabel, incense, masteryWidth, realmWidth, masteryRemain, realmRemain, dur, close, onSuggest, discard };
  },
  template: `
  <transition name="veil">
    <div v-if="s" class="veil" @click.self="close" @keydown.esc="close">
      <div class="scroll" :class="{ open: shown }" role="dialog" aria-modal="true" aria-labelledby="settle-title">
        <div class="roller"></div>
        <div class="paper">
          <div class="paper-inner">
            <span v-if="sealed" class="stamp">{{ sealText }}</span>
            <h2 id="settle-title" class="scroll-title">{{ title }}</h2>
            <p class="scroll-method">《{{ s.method.name }}》<span v-if="s.method.chapter"> · {{ s.method.chapter }}</span></p>
            <dl class="scroll-rows">
              <div>
                <dt>本次修炼</dt>
                <dd><b>{{ dur(s.session.seconds) }}</b><small>{{ incense }}</small></dd>
              </div>
              <div>
                <dt>功法累计</dt>
                <dd>
                  <span>{{ dur(s.method.after.totalSeconds) }} · <em class="ink-name">{{ s.method.after.name }}</em></span>
                  <span class="ink-bar"><i :style="{ width: masteryWidth }"></i></span>
                  <small>{{ masteryRemain }}</small>
                </dd>
              </div>
              <div>
                <dt>今日共修</dt>
                <dd><span>{{ dur(s.todaySeconds) }}</span></dd>
              </div>
              <div>
                <dt>{{ realmLabel }}</dt>
                <dd>
                  <span><em class="ink-name">{{ s.realm.after.name }}</em></span>
                  <span class="ink-bar cinnabar"><i :style="{ width: realmWidth }"></i></span>
                  <small>{{ realmRemain }}</small>
                </dd>
              </div>
            </dl>
            <div v-if="s.suggestItem" class="scroll-suggest">
              <SealBox v-model="suggestDone" :disabled="suggestDone" :label="'勾上今日' + s.suggestItem.name + '功课'" @toggle="onSuggest"/>
              <span>顺手勾上今日「{{ s.suggestItem.name }}」功课</span>
            </div>
            <p v-if="copy" class="scroll-copy">「{{ copy }}」</p>
            <div class="scroll-actions">
              <button ref="closeBtn" type="button" class="btn btn-ink" @click="close">收卷</button>
              <button type="button" class="btn-text ink" @click="discard">此次不算</button>
            </div>
          </div>
        </div>
        <div class="roller"></div>
      </div>
    </div>
  </transition>`,
};
