// 声音全部用 Web Audio 现场合成，不需要音频文件：钟磬（入定/出关/香尽）和几种灵音（白噪音）。

let ctx = null;

function audio() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
  }
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

/** 一声磬：几个不谐和泛音指数衰减，加一个微失谐的分量产生拍音。 */
export function chime(volume = 1) {
  const ac = audio();
  if (!ac) return;
  const t = ac.currentTime;
  const master = ac.createGain();
  master.gain.value = 0.16 * volume;
  master.connect(ac.destination);
  const base = 392;
  const partials = [
    [1, 1, 4.6],
    [1.004, 0.6, 4.6],
    [2.76, 0.45, 3.0],
    [5.4, 0.22, 2.0],
    [8.93, 0.1, 1.2],
  ];
  for (const [ratio, amp, decay] of partials) {
    const o = ac.createOscillator();
    o.type = 'sine';
    o.frequency.value = base * ratio;
    const g = ac.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(amp, t + 0.008);
    g.gain.exponentialRampToValueAtTime(0.0001, t + decay);
    o.connect(g).connect(master);
    o.start(t);
    o.stop(t + decay + 0.05);
  }
}

function noiseBuffer(ac, color) {
  const len = ac.sampleRate * 4;
  const buf = ac.createBuffer(1, len, ac.sampleRate);
  const d = buf.getChannelData(0);
  if (color === 'brown') {
    let last = 0;
    for (let i = 0; i < len; i++) {
      last = (last + 0.02 * (Math.random() * 2 - 1)) / 1.02;
      d[i] = last * 3.5;
    }
  } else {
    // Paul Kellet 粉红噪声
    let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    for (let i = 0; i < len; i++) {
      const w = Math.random() * 2 - 1;
      b0 = 0.99886 * b0 + w * 0.0555179;
      b1 = 0.99332 * b1 + w * 0.0750759;
      b2 = 0.969 * b2 + w * 0.153852;
      b3 = 0.8665 * b3 + w * 0.3104856;
      b4 = 0.55 * b4 + w * 0.5329522;
      b5 = -0.7616 * b5 - w * 0.016898;
      d[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
      b6 = w * 0.115926;
    }
  }
  return buf;
}

function lfo(ac, rate, depth, target) {
  const o = ac.createOscillator();
  o.frequency.value = rate;
  const g = ac.createGain();
  g.gain.value = depth;
  o.connect(g).connect(target);
  o.start();
  return o;
}

export const AMBIENTS = [
  { key: 'rain', name: '夜雨' },
  { key: 'stream', name: '山泉' },
  { key: 'wind', name: '松风' },
];

let current = null;

export function ambientPlaying() {
  return current ? current.key : null;
}

export function playAmbient(key, volume = 0.5) {
  stopAmbient();
  const ac = audio();
  if (!ac) return;
  const src = ac.createBufferSource();
  src.loop = true;
  const out = ac.createGain();
  out.gain.setValueAtTime(0, ac.currentTime);
  out.gain.linearRampToValueAtTime(volume * 0.5, ac.currentTime + 1.5);
  out.connect(ac.destination);
  const extra = [];

  if (key === 'stream') {
    src.buffer = noiseBuffer(ac, 'brown');
    const lp = ac.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 900;
    extra.push(lfo(ac, 0.13, 350, lp.frequency));
    src.connect(lp).connect(out);
  } else if (key === 'wind') {
    src.buffer = noiseBuffer(ac, 'pink');
    const bp = ac.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = 650;
    bp.Q.value = 0.9;
    extra.push(lfo(ac, 0.07, 380, bp.frequency));
    const swell = ac.createGain();
    swell.gain.value = 0.8;
    extra.push(lfo(ac, 0.05, 0.35, swell.gain));
    src.connect(bp).connect(swell).connect(out);
  } else {
    src.buffer = noiseBuffer(ac, 'pink');
    const hp = ac.createBiquadFilter();
    hp.type = 'highpass';
    hp.frequency.value = 500;
    const lp = ac.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 7000;
    src.connect(hp).connect(lp).connect(out);
  }
  src.start();
  current = { key, src, out, extra };
}

export function stopAmbient() {
  if (!current) return;
  const { src, out, extra } = current;
  current = null;
  const ac = audio();
  if (!ac) return;
  out.gain.cancelScheduledValues(ac.currentTime);
  out.gain.setValueAtTime(out.gain.value, ac.currentTime);
  out.gain.linearRampToValueAtTime(0, ac.currentTime + 0.6);
  setTimeout(() => {
    try {
      src.stop();
      extra.forEach((o) => o.stop());
    } catch {
      // 已经停了
    }
  }, 700);
}
