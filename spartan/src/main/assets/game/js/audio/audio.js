/**
 * WebAudio engine (DESIGN.md §11 audio/audio.js): synthesized SFX, ambience beds, music and
 * voice dispatch. sfx.js and loops.js render every sound once into AudioBuffers, in a module
 * worker when possible, else time-sliced on the main thread. The only files are the Greeks'
 * recorded voice lines (audio/voice/*.ogg, listed in voicebank.js), decoded when first needed.
 *
 * Graph: voices → sfx bus ─┐                      ┌→ convolver (the pass) → sfx bus
 *        loops → music bus → duck ─┼→ master → limiter → out
 *        beds  → ambience bus → duck → muffle ─┘
 *        voice clips → pan → voice bus ─┘ (+ reverb send)
 *        distant host events → low-pass → ambience bus (+ reverb)
 *
 * audio subscribes itself to the bus events (§12) and is the only caller of platform.voice.say.
 */
import { createRng, clamp, lerp, smoothstep } from '../core/math.js';
import { LEONIDAS_X } from '../game/campaign.js';
import { SFX, SFX_NAMES, sfxLength, sfxRate, renderSfxGen } from './sfx.js';
import { LOOPS, MUSIC_NAMES, AMBIENCE_LAYERS, loopLength, renderLoopGen, renderImpulse } from './loops.js';

export { SFX, SFX_NAMES, LOOPS, MUSIC_NAMES, AMBIENCE_LAYERS };

/** Text-to-speech pitch and rate per speaker. */
export const SPEAKERS = {
  Leonidas: { pitch: 0.78, rate: 0.9 },
  Dienekes: { pitch: 0.86, rate: 0.93 },
  Trachinian: { pitch: 1.06, rate: 1.06 },
  Herald: { pitch: 0.97, rate: 0.86 },
  Lookout: { pitch: 1.12, rate: 1.12 },
  Narrator: { pitch: 0.92, rate: 0.95 },
};
const DEFAULT_SPEAKER = { pitch: 0.9, rate: 0.95 };

/**
 * Where each speaker of recorded lines stands, relative to the player's place in the line (x
 * right, −z toward the enemy): Leonidas on his left (§13), Dienekes a little to his right, the
 * herald and the lookout ahead and farther off. `pan` is how far the voice may pan; `gain` its
 * level; `wet` its send into the pass's reverb.
 */
export const VOICE_PLACES = {
  Leonidas: { x: LEONIDAS_X, z: 0, pan: 0.35, gain: 1, wet: 0.1 },
  Dienekes: { x: 2.8, z: 0, pan: 0.2, gain: 1, wet: 0.1 },
  Trachinian: { x: 4.2, z: 0.4, pan: 0.3, gain: 0.95, wet: 0.14 },
  Herald: { x: 0.3, z: -15, pan: 0.25, gain: 0.8, wet: 0.32 },
  Lookout: { x: -1, z: -9, pan: 0.25, gain: 0.82, wet: 0.28 },
};
const DEFAULT_PLACE = { x: 0, z: -2, pan: 0, gain: 0.95, wet: 0.14 };
const VOICE_LATE = 0.6; // s: a line whose clip is still loading after this long is dropped
const CLIP_CACHE = 8 * 1048576; // bytes of decoded clips kept (least recently used go first)

/** Ambience beds: layer → level. */
export const AMBIENCE = {
  battle: { wind: 0.4, sea: 0.42, din: 0.85 },
  calm: { wind: 0.55, sea: 0.62, cicadas: 0.4 },
  none: {},
};

/** Combo stings are transposed by musical intervals so each combo has its own colour. */
const COMBO_RATE = { twin: 1, fury: 1.125, storm: 1.335, cleave: 0.75, xcut: 1.189, answer: 0.89, drive: 0.84, wall: 0.75 };

const MAX_VOICES = 24;
const AI_VOL = 0.5; // AI-vs-AI events
const LOW_HP = 0.3; // heartbeat below this fraction of max HP
const MUSIC_FADE = 2.5, AMB_FADE = 3;
const BUS_TRIM = { music: 0.8, ambience: 0.5 };
const STRIDE = 0.75; // metres between footsteps
const ALLY_X = [-4.2, -2.8, -1.4, 1.4, 2.8, 4.2]; // line slots that catch arrows on their shields
const PROJECTILE_KINDS = new Set(['spear', 'throw', 'spearThrow', 'projectile', 'arrow']);
// Render order behind the title screen: UI first, then combat, then the rest.
const SFX_ORDER = ['uiTap', 'uiConfirm', 'whoosh', 'whooshHeavy', 'flesh', 'fleshHeavy', 'clang', 'wicker', 'wood', 'parry', 'shieldUp',
  'bash', 'guardBreak', 'hurt', 'grunt', 'death', 'step', 'arrowThud', 'arrowShield', 'spearThrow', 'spearWhistle', 'spearHit',
  'spearGround', 'splash', 'scream', 'comboSting', 'heartbeat', 'draw', 'drum', 'gull', 'horn', 'volley', 'warcry'];
const PRELOAD_LOOPS = ['wind', 'sea', 'cicadas', 'title', 'din', 'battle']; // lastStand is rendered on demand

const isGen = (fn) => fn && fn.constructor && fn.constructor.name === 'GeneratorFunction';
const num = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? v : d);

export function createAudio(ctx) {
  const rng = createRng(0x5a17);
  const hasWindow = typeof window !== 'undefined';
  let ac = null;
  try {
    const AC = hasWindow ? window.AudioContext || window.webkitAudioContext : null;
    if (AC) ac = new AC({ latencyHint: 'interactive' });
  } catch (err) {
    console.warn('audio: no AudioContext', err);
    ac = null;
  }

  const stats = { renderMs: 0, jobsDone: 0, bytes: 0, workers: 0, startedAt: now(), doneAt: 0, readyAt: {}, played: 0, stolen: 0, dropped: 0 };
  const log = [];
  let wantRunning = true; // false between suspend() and resume()
  let resumeAt = -1e9; // when unlock()/resume() last asked the context to run

  // ------------------------------------------------------------------------------------------
  // Graph

  let master, limiter, sfxBus, voiceBus, musicBus, musicDuck, ambBus, ambDuck, ambMuffle, verbIn, distIn, analyser = null;
  const OPEN = ac ? Math.min(20000, ac.sampleRate * 0.45) : 20000; // an open low-pass
  if (ac) {
    try {
      buildGraph();
    } catch (err) {
      console.warn('audio: graph failed', err);
      ac = null;
    }
  }

  function gain(v, to) {
    const g = ac.createGain();
    g.gain.value = v;
    if (to) g.connect(to);
    return g;
  }

  function buildGraph() {
    limiter = ac.createDynamicsCompressor();
    limiter.threshold.value = -5;
    limiter.knee.value = 3;
    limiter.ratio.value = 14;
    limiter.attack.value = 0.002;
    limiter.release.value = 0.18;
    limiter.connect(ac.destination);
    master = gain(0.9, limiter);
    sfxBus = gain(1, master);
    voiceBus = gain(1, master);
    musicDuck = gain(1, master);
    musicBus = gain(0.5, musicDuck);
    ambMuffle = ac.createBiquadFilter();
    ambMuffle.type = 'lowpass';
    ambMuffle.frequency.value = OPEN;
    ambMuffle.Q.value = 0.5;
    ambMuffle.connect(master);
    ambDuck = gain(1, ambMuffle);
    ambBus = gain(0.6, ambDuck);
    // The pass between cliff and sea: a short stereo convolution reverb shared by all SFX.
    const conv = ac.createConvolver();
    conv.normalize = false;
    const [l, r] = renderImpulse(ac.sampleRate, ctx.quality === 'low' ? 0.8 : 1.3);
    const ir = ac.createBuffer(2, l.length, ac.sampleRate);
    ir.copyToChannel(l, 0);
    ir.copyToChannel(r, 1);
    conv.buffer = ir;
    conv.connect(gain(0.55, sfxBus));
    verbIn = gain(1, conv);
    // The Persian host far down the pass: dull and reverberant.
    const far = ac.createBiquadFilter();
    far.type = 'lowpass';
    far.frequency.value = 1500;
    far.Q.value = 0.6;
    far.connect(ambBus);
    distIn = gain(1, far);
    distIn.connect(gain(0.8, verbIn));
    stats.bytes += l.length * 8;
  }

  // ------------------------------------------------------------------------------------------
  // Rendering: a priority queue of jobs, served by one or two module workers, or by time
  // slices in update() when workers are unavailable.

  const bufs = new Map(); // 'sfx:name' → AudioBuffer[]; 'loop:name' → AudioBuffer
  const jobs = [];
  const waiters = new Map();
  const pool = []; // { w, job, sentAt, turns, ok }
  let seq = 0, reqId = 0, idleResolvers = [];

  const busy = (key) => pool.some((s) => s.job && s.job.key === key);

  function queue(kind, name, prio) {
    if (!ac) return;
    const key = kind + ':' + name;
    if (bufs.has(key) || busy(key)) return;
    const j = jobs.find((x) => x.key === key);
    if (j) {
      if (prio < j.prio) {
        j.prio = prio;
        sortJobs();
      }
      return;
    }
    jobs.push({ key, kind, name, prio, seq: seq++, gen: null });
    sortJobs();
    dispatch();
  }

  function sortJobs() {
    jobs.sort((a, b) => a.prio - b.prio || a.seq - b.seq);
  }

  function whenReady(key, fn) {
    if (bufs.has(key)) {
      fn();
      return;
    }
    let list = waiters.get(key);
    if (!list) waiters.set(key, (list = []));
    list.push(fn);
  }

  function install(job, list) {
    if (!bufs.has(job.key)) {
      for (const b of list) stats.bytes += b.length * b.numberOfChannels * 4;
      bufs.set(job.key, job.kind === 'sfx' ? list : list[0]);
      stats.readyAt[job.key] = Math.round(now() - stats.startedAt);
    }
    stats.jobsDone++;
    const ws = waiters.get(job.key);
    if (ws) {
      waiters.delete(job.key);
      for (const fn of ws) {
        try {
          fn();
        } catch (err) {
          console.error('audio: ready callback failed', err);
        }
      }
    }
    checkIdle();
  }

  function checkIdle() {
    if (jobs.length || pool.some((s) => s.job)) return;
    if (!stats.doneAt) stats.doneAt = now();
    const rs = idleResolvers;
    idleResolvers = [];
    for (const r of rs) r();
  }

  function startWorkers() {
    if (!ac || typeof Worker === 'undefined') return;
    const cores = hasWindow && window.navigator ? window.navigator.hardwareConcurrency || 4 : 4;
    for (let i = 0, n = cores >= 6 ? 2 : 1; i < n; i++) {
      try {
        const slot = { w: new Worker(new URL('./worker.js', import.meta.url), { type: 'module' }), job: null, sentAt: 0, turns: 0, ok: false };
        slot.w.onmessage = (e) => onWorkerMessage(slot, e.data);
        slot.w.onerror = (e) => {
          if (e && e.preventDefault) e.preventDefault();
          workerDown(slot, 'error');
        };
        slot.w.onmessageerror = () => workerDown(slot, 'messageerror');
        pool.push(slot);
      } catch {
        break;
      }
    }
    stats.workers = pool.length;
  }

  function workerDown(slot, why) {
    const i = pool.indexOf(slot);
    if (i < 0) return;
    pool.splice(i, 1);
    stats.workers = pool.length;
    try {
      slot.w.terminate();
    } catch { /* already gone */ }
    if (slot.job) {
      slot.job.gen = null;
      jobs.push(slot.job);
      slot.job = null;
      sortJobs();
    }
    if (!pool.length) console.warn(`audio: render worker unavailable (${why}); rendering on the main thread`);
    dispatch();
  }

  function dispatch() {
    for (const slot of pool) {
      if (slot.job || !jobs.length) continue;
      const job = (slot.job = jobs.shift());
      job.id = ++reqId;
      slot.sentAt = now();
      slot.turns = 0;
      slot.w.postMessage({ id: job.id, kind: job.kind, name: job.name, sr: ac.sampleRate });
    }
  }

  /**
   * A worker that never answers (e.g. a WebView that can't load it) is replaced by time slices.
   * It needs both elapsed time and many event-loop turns, so a busy main thread that simply
   * hasn't read the reply yet doesn't count.
   */
  function watchdog() {
    for (let i = pool.length - 1; i >= 0; i--) {
      const slot = pool[i];
      if (!slot.job) continue;
      slot.turns++;
      if (slot.turns > 60 && now() - slot.sentAt > (slot.ok ? 30000 : 3000)) workerDown(slot, 'no response');
    }
  }

  function onWorkerMessage(slot, res) {
    const job = slot.job;
    if (!job || res.id !== job.id) return;
    slot.job = null;
    slot.ok = true;
    if (res.error) {
      console.error(`audio: rendering ${job.key} failed`, res.error);
      stats.jobsDone++;
      checkIdle();
    } else {
      stats.renderMs += res.ms;
      const list = res.chans.map((data) => {
        const b = ac.createBuffer(1, data.length, res.sr);
        b.copyToChannel(data, 0);
        return b;
      });
      install(job, list);
    }
    dispatch();
  }

  /** Main-thread render of one job, writing straight into AudioBuffer memory. */
  function* mainJob(job) {
    const list = [];
    if (job.kind === 'sfx') {
      const sr = sfxRate(job.name, ac.sampleRate);
      for (let v = 0; v < SFX[job.name].variants; v++) {
        const b = ac.createBuffer(1, sfxLength(job.name, sr), sr);
        yield* renderSfxGen(job.name, b.getChannelData(0), sr, v);
        list.push(b);
        yield;
      }
    } else {
      const b = ac.createBuffer(1, loopLength(job.name), LOOPS[job.name].sr);
      yield* renderLoopGen(job.name, b.getChannelData(0));
      list.push(b);
    }
    return list;
  }

  function pumpMain(budgetMs) {
    if (!jobs.length) return;
    const t0 = now();
    while (jobs.length && now() - t0 < budgetMs) {
      const job = jobs[0];
      if (!job.gen) job.gen = mainJob(job);
      let r;
      try {
        r = job.gen.next();
      } catch (err) {
        console.error(`audio: rendering ${job.key} failed`, err);
        jobs.shift();
        stats.jobsDone++;
        continue;
      }
      if (r.done) {
        jobs.shift();
        install(job, r.value);
      }
    }
    stats.renderMs += now() - t0;
    checkIdle();
  }

  /** Renders an SFX now (cheap ones only), used if a sound is needed before its turn. */
  function renderNow(name) {
    const key = 'sfx:' + name;
    const i = jobs.findIndex((j) => j.key === key);
    const job = i >= 0 ? jobs.splice(i, 1)[0] : { key, kind: 'sfx', name, gen: null };
    const t0 = now();
    const gen = job.gen || mainJob(job);
    let r;
    while (!(r = gen.next()).done);
    stats.renderMs += now() - t0;
    install(job, r.value);
  }

  function getSfx(name) {
    const key = 'sfx:' + name;
    const list = bufs.get(key);
    if (list) return list;
    if (!isGen(SFX[name].render)) {
      renderNow(name);
      return bufs.get(key);
    }
    queue('sfx', name, -1);
    return null;
  }

  if (ac) {
    startWorkers();
    SFX_ORDER.forEach((name, i) => queue('sfx', name, i < 2 ? 0 : i < 16 ? 2 : 3));
    for (const name of Object.keys(SFX)) queue('sfx', name, 3);
    for (const name of PRELOAD_LOOPS) queue('loop', name, 4);
  }

  // ------------------------------------------------------------------------------------------
  // Voices

  const voices = [];
  const lastStart = new Map();
  const lastVariant = new Map();
  const S = { pan: 0, gain: 1, lp: 0, wet: 0 };

  /** Pan, gain, air absorption and reverb send for a world position, relative to the camera. */
  function spatial(at) {
    const cam = ctx.camera;
    const ax = num(at.x, num(at[0], 0)), ay = num(at.y, num(at[1], 0)), az = num(at.z, num(at[2], 0));
    if (!cam || !cam.matrixWorld) {
      S.pan = 0; S.gain = 1; S.lp = 0; S.wet = 0;
      return;
    }
    const e = cam.matrixWorld.elements;
    const dx = ax - e[12], dy = ay - e[13], dz = az - e[14];
    const d = Math.max(1e-3, Math.hypot(dx, dy, dz));
    const side = (dx * e[0] + dy * e[1] + dz * e[2]) / d;
    const front = -(dx * e[8] + dy * e[9] + dz * e[10]) / d;
    S.pan = clamp(side * Math.min(1, d / 2.5), -1, 1) * 0.85;
    const REF = 3;
    S.gain = d <= REF ? 1 : REF / (REF + (d - REF));
    let lp = 0;
    if (front < -0.3) {
      S.gain *= 0.82;
      lp = 7000;
    }
    if (d > 15) lp = Math.min(lp || 16000, clamp(16000 - (d - 15) * 260, 1800, 16000));
    S.lp = lp;
    S.wet = clamp((d - 2) / 35, 0, 0.5);
  }

  function kill(v) {
    if (v.dying) return;
    v.dying = true;
    const t = ac.currentTime;
    try {
      v.gain.gain.cancelScheduledValues(t);
      v.gain.gain.setTargetAtTime(0, t, 0.01);
      v.src.stop(t + 0.06);
    } catch { /* already stopped */ }
  }

  /** Enforces the per-sound cap and the global voice cap. Returns false to drop the new sound. */
  function makeRoom(name, cap, prio) {
    const t = ac.currentTime + 0.05;
    let same = 0, oldest = null, live = 0;
    for (let i = 0; i < voices.length; i++) {
      const v = voices[i];
      if (v.dying) continue;
      live++;
      if (v.name === name && v.start <= t) {
        same++;
        if (!oldest || v.start < oldest.start) oldest = v;
      }
    }
    if (same >= cap && oldest) {
      kill(oldest);
      live--;
      stats.stolen++;
    }
    while (live >= MAX_VOICES) {
      let victim = null;
      for (let i = 0; i < voices.length; i++) {
        const v = voices[i];
        if (v.dying) continue;
        if (!victim || v.prio < victim.prio || (v.prio === victim.prio && v.start < victim.start)) victim = v;
      }
      if (!victim || victim.prio > prio) return false;
      kill(victim);
      live--;
      stats.stolen++;
    }
    return true;
  }

  /** True while sounds may be scheduled: running, or resuming right after a gesture/resume(). */
  function live() {
    if (!ac) return false;
    if (ac.state === 'running') return true;
    return ac.state === 'suspended' && wantRunning && now() - resumeAt < 400;
  }

  /**
   * Plays a one-shot. opts: { volume = 1, rate = 1, at: Vector3|null } plus internal extras:
   * pan, delay (s), prio, bus ('sfx'|'ambience'|'distant'), send (reverb), exact (no jitter),
   * variant. Returns a handle { stop() } or null if nothing was played.
   */
  function playSfx(name, opts) {
    if (!live()) return null;
    const spec = SFX[name];
    if (!spec) {
      warnOnce('unknown sound ' + name);
      return null;
    }
    const o = opts || {};
    const list = getSfx(name);
    if (!list) {
      stats.dropped++;
      return null;
    }
    const delay = Math.max(0, num(o.delay, 0));
    const when = ac.currentTime + delay;
    const last = lastStart.get(name);
    if (last !== undefined && Math.abs(when - last) < 0.012) return null;
    let volume = Math.max(0, num(o.volume, 1));
    let rate = clamp(num(o.rate, 1), 0.25, 4);
    if (!o.exact) {
      rate *= 1 + (rng.next() - 0.5) * 0.07;
      volume *= 1 - rng.next() * 0.12;
    }
    let pan = clamp(num(o.pan, 0), -1, 1), lp = 0, send = num(o.send, 0.14);
    if (o.at) {
      spatial(o.at);
      volume *= S.gain;
      pan = S.pan;
      lp = S.lp;
      send += S.wet;
    }
    if (volume < 0.004) return null;
    const prio = num(o.prio, spec.prio);
    if (!makeRoom(name, spec.cap, prio)) {
      stats.dropped++;
      return null;
    }
    let vi = o.variant == null ? Math.floor(rng.next() * list.length) : o.variant % list.length;
    if (list.length > 1 && o.variant == null && vi === lastVariant.get(name)) vi = (vi + 1) % list.length;
    lastVariant.set(name, vi);

    const src = ac.createBufferSource();
    src.buffer = list[vi];
    src.playbackRate.value = rate;
    const g = gain(volume);
    let head = src;
    if (lp > 0) {
      const f = ac.createBiquadFilter();
      f.type = 'lowpass';
      f.frequency.value = lp;
      f.Q.value = 0.5;
      src.connect(f);
      head = f;
    }
    head.connect(g);
    let out = g;
    if (pan && ac.createStereoPanner) {
      const p = ac.createStereoPanner();
      p.pan.value = pan;
      g.connect(p);
      out = p;
    }
    out.connect(o.bus === 'distant' ? distIn : o.bus === 'ambience' ? ambBus : sfxBus);
    let sg = null;
    if (send > 0.01 && o.bus !== 'distant') {
      sg = gain(send, verbIn);
      out.connect(sg);
    }
    const v = { name, src, gain: g, out, sg, head, prio, start: when, dying: false };
    voices.push(v);
    src.onended = () => {
      const i = voices.indexOf(v);
      if (i >= 0) voices.splice(i, 1);
      try {
        src.disconnect();
        if (head !== src) head.disconnect();
        g.disconnect();
        if (out !== g) out.disconnect();
        if (sg) sg.disconnect();
      } catch { /* already disconnected */ }
    };
    src.start(when);
    lastStart.set(name, when);
    stats.played++;
    if (log.length >= 200) log.shift();
    log.push({ name, volume, rate, pan, delay, at: !!o.at, bus: o.bus || 'sfx', t: when });
    return { stop: () => kill(v) };
  }

  const warned = new Set();
  function warnOnce(msg) {
    if (warned.has(msg)) return;
    warned.add(msg);
    console.warn('audio: ' + msg);
  }

  // ------------------------------------------------------------------------------------------
  // Music and ambience beds

  const music = { want: 'none', cur: null };

  function fadeOut(node, src, sec) {
    const t = ac.currentTime;
    const p = node.gain;
    p.cancelScheduledValues(t);
    p.setValueAtTime(p.value, t);
    p.linearRampToValueAtTime(0, t + sec);
    try {
      src.stop(t + sec + 0.05);
    } catch { /* not started */ }
  }

  function setMusic(kind) {
    const k = MUSIC_NAMES.includes(kind) ? kind : 'none';
    music.want = k;
    if (!ac) return;
    if (music.cur && music.cur.name === k) return;
    if (music.cur) {
      fadeOut(music.cur.gain, music.cur.src, MUSIC_FADE);
      music.cur = null;
    }
    if (k !== 'none') {
      queue('loop', k, 1);
      whenReady('loop:' + k, startMusic);
    }
  }

  function startMusic() {
    const k = music.want;
    if (music.cur || k === 'none') return;
    const b = bufs.get('loop:' + k);
    if (!b) return;
    const src = ac.createBufferSource();
    src.buffer = b;
    src.loop = true;
    const g = gain(0, musicBus);
    src.connect(g);
    const t = ac.currentTime;
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(1, t + MUSIC_FADE);
    src.start(t);
    music.cur = { name: k, src, gain: g };
  }

  const amb = { kind: 'none', drumT: 6, hornT: 18, gullT: 4 };
  const layers = AMBIENCE_LAYERS.map((name) => ({ name, want: 0, nodes: null, panC: 0 }));

  function setAmbience(kind) {
    amb.kind = AMBIENCE[kind] ? kind : 'none';
    const mix = AMBIENCE[amb.kind];
    for (const L of layers) {
      L.want = mix[L.name] || 0;
      if (!ac) continue;
      if (L.want > 0) {
        queue('loop', L.name, 1);
        whenReady('loop:' + L.name, () => applyLayer(L));
      } else {
        applyLayer(L);
      }
    }
  }

  function applyLayer(L) {
    if (L.want > 0 && !L.nodes) {
      const b = bufs.get('loop:' + L.name);
      if (!b) return;
      const g = gain(0, ambBus);
      const t = ac.currentTime;
      const srcs = [], pans = [];
      // Two decorrelated copies of the mono bed, half a loop apart, make it wide.
      for (let k = 0; k < 2; k++) {
        const s = ac.createBufferSource();
        s.buffer = b;
        s.loop = true;
        let node = s;
        if (ac.createStereoPanner) {
          const p = ac.createStereoPanner();
          s.connect(p);
          node = p;
          pans.push(p);
        }
        node.connect(g);
        s.start(t, k * b.duration * 0.5);
        srcs.push(s);
      }
      L.nodes = { g, srcs, pans, lastPan: 9 };
      L.level = -1;
      updateLayerPan(L, true);
    }
    if (L.nodes && L.level !== L.want) {
      const t = ac.currentTime;
      const p = L.nodes.g.gain;
      p.cancelScheduledValues(t);
      p.setValueAtTime(p.value, t);
      p.linearRampToValueAtTime(L.want, t + AMB_FADE);
      L.level = L.want;
      if (L.want === 0) {
        for (const s of L.nodes.srcs) {
          try {
            s.stop(t + AMB_FADE + 0.05);
          } catch { /* not started */ }
        }
        L.nodes = null;
      }
    }
  }

  /** Horizontal camera right vector → how far a world direction sits to the right (−1..1). */
  function sideOf(dx, dz) {
    const cam = ctx.camera;
    if (!cam || !cam.matrixWorld) return dx;
    const e = cam.matrixWorld.elements;
    const rl = Math.hypot(e[0], e[2]) || 1;
    return (dx * e[0] + dz * e[2]) / rl;
  }

  function updateLayerPan(L, force) {
    if (!L.nodes || L.nodes.pans.length < 2) return;
    let c = 0, w = 0.6;
    if (L.name === 'sea') {
      c = sideOf(1, 0) * 0.7; // the Gulf lies to the east (+X)
      w = 0.25;
    } else if (L.name === 'din') {
      c = sideOf(0, -1) * 0.6; // the host comes from −Z
      w = 0.35;
    } else if (L.name === 'cicadas') {
      c = sideOf(-1, 0) * 0.35; // the scrub on the cliff (−X)
      w = 0.5;
    }
    if (!force && Math.abs(c - L.nodes.lastPan) < 0.02) return;
    L.nodes.lastPan = c;
    const t = ac.currentTime;
    L.nodes.pans[0].pan.setTargetAtTime(clamp(c - w, -1, 1), t, 0.08);
    L.nodes.pans[1].pan.setTargetAtTime(clamp(c + w, -1, 1), t, 0.08);
  }

  /** A war-drum figure from the far end of the pass. */
  function drumPattern(hits, beat, start, vol, prio = 0) {
    const pan = sideOf(0, -1) * 0.5;
    let t = start;
    for (let i = 0; i < hits; i++) {
      const accent = i % 4 === 0;
      playSfx('drum', { bus: 'distant', delay: t, volume: vol * (accent ? 1 : 0.6), pan: pan + (rng.next() - 0.5) * 0.2, prio, rate: accent ? 0.96 : 1.02 });
      t += beat * (i % 4 === 3 ? 1.5 : 1) * (1 + (rng.next() - 0.5) * 0.04);
    }
  }

  function updateAmbientEvents(dt) {
    if (amb.kind === 'battle') {
      amb.drumT -= dt;
      if (amb.drumT <= 0) {
        drumPattern(6 + Math.floor(rng.next() * 9), 0.46 + rng.next() * 0.14, 0, 0.5 + rng.next() * 0.35);
        amb.drumT = 14 + rng.next() * 22;
      }
      amb.hornT -= dt;
      if (amb.hornT <= 0) {
        playSfx('horn', { bus: 'distant', volume: 0.3 + rng.next() * 0.25, rate: 0.9 + rng.next() * 0.14, pan: sideOf(0, -1) * 0.5, prio: 0 });
        amb.hornT = 30 + rng.next() * 40;
      }
    } else if (amb.kind === 'calm') {
      amb.gullT -= dt;
      if (amb.gullT <= 0) {
        playSfx('gull', { bus: 'ambience', volume: 0.35 + rng.next() * 0.4, rate: 0.9 + rng.next() * 0.2, pan: clamp(sideOf(1, 0) * 0.7 + (rng.next() - 0.5) * 0.5, -1, 1), send: 0.2, prio: 0 });
        amb.gullT = 6 + rng.next() * 16;
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // Mix state: volumes, ducking, muffling, heartbeat

  const mix = { master: -1, sfx: -1, music: -1, duckUntil: 0, musicDuck: 1, ambDuck: 1, muffle: OPEN };
  const hb = { on: false, next: 0, sev: 0 };

  function setParam(p, v, tc = 0.04) {
    const t = ac.currentTime;
    p.cancelScheduledValues(t);
    p.setTargetAtTime(v, t, tc);
  }

  function applyVolumes() {
    if (!ac) return;
    const s = ctx.settings || {};
    mix.master = clamp(num(s.master, 0.9), 0, 1);
    mix.sfx = clamp(num(s.sfx, 1), 0, 1);
    mix.music = clamp(num(s.music, 0.55), 0, 1);
    setParam(master.gain, mix.master);
    setParam(sfxBus.gain, mix.sfx);
    setParam(musicBus.gain, mix.music * BUS_TRIM.music);
    setParam(ambBus.gain, mix.sfx * BUS_TRIM.ambience);
  }

  function updateMix() {
    const s = ctx.settings || {};
    if (clamp(num(s.master, 0.9), 0, 1) !== mix.master || clamp(num(s.sfx, 1), 0, 1) !== mix.sfx || clamp(num(s.music, 0.55), 0, 1) !== mix.music) applyVolumes();
    const t = ac.currentTime;
    const mode = ctx.mode;
    const speaking = t < mix.duckUntil;
    const md = speaking ? 0.55 : 1;
    const ad = (mode === 'paused' ? 0.45 : mode === 'gameover' ? 0.6 : 1) * (speaking ? 0.75 : 1);
    if (md !== mix.musicDuck) {
      mix.musicDuck = md;
      setParam(musicDuck.gain, md, speaking ? 0.08 : 0.4);
    }
    if (ad !== mix.ambDuck) {
      mix.ambDuck = ad;
      setParam(ambDuck.gain, ad, 0.3);
    }
    // Slow motion and failing health both dull the world around you.
    const scale = num(ctx.time && ctx.time.scale, 1);
    let cut = OPEN;
    if (scale < 0.95) cut = Math.min(cut, lerp(700, OPEN, smoothstep(0.08, 1, scale)));
    if (hb.on) cut = Math.min(cut, lerp(6000, 1800, hb.sev));
    if (Math.abs(cut - mix.muffle) > 50) {
      mix.muffle = cut;
      setParam(ambMuffle.frequency, cut, 0.12);
    }
  }

  // Footsteps from the player's own travel (stick, lunges, drift back to the line). If another
  // module plays 'step' itself, these stop so steps are never doubled.
  const steps = { x: 0, z: 0, acc: 0, has: false, foot: 1, external: false };
  function updateSteps() {
    const p = ctx.player;
    const pos = p && p.pos;
    const mode = ctx.mode;
    if (steps.external || !pos || (mode !== 'battle' && mode !== 'training') || p.alive === false) {
      steps.has = false;
      return;
    }
    const x = num(pos.x, 0), z = num(pos.z, 0);
    if (!steps.has) {
      steps.x = x;
      steps.z = z;
      steps.acc = STRIDE * 0.6;
      steps.has = true;
      return;
    }
    const d = Math.hypot(x - steps.x, z - steps.z);
    steps.x = x;
    steps.z = z;
    if (d > 1.5) return; // a reset or a teleport, not a step
    steps.acc += d;
    if (steps.acc >= STRIDE) {
      steps.acc -= STRIDE;
      steps.foot = -steps.foot;
      playSfx('step', { volume: 0.4, pan: 0.12 * steps.foot, prio: 0, send: 0.05 });
    }
  }

  function updateHeartbeat() {
    const p = ctx.player;
    const mode = ctx.mode;
    let sev = 0;
    if (p && (mode === 'battle' || mode === 'training') && p.alive !== false && num(p.hp, 100) > 0) {
      const frac = num(p.hp, 100) / Math.max(1, num(p.maxHp, 100));
      if (frac <= LOW_HP) sev = 0.25 + 0.75 * clamp(1 - frac / LOW_HP, 0, 1);
    }
    hb.sev = sev;
    if (sev <= 0) {
      hb.on = false;
      return;
    }
    const t = ac.currentTime;
    if (!hb.on) {
      hb.on = true;
      hb.next = t + 0.05;
    }
    if (hb.next < t + 0.12) {
      playSfx('heartbeat', { volume: 0.55 + 0.45 * sev, rate: 1 + 0.08 * sev, delay: Math.max(0, hb.next - t), exact: true, prio: 4 });
      hb.next = Math.max(hb.next, t) + lerp(0.95, 0.58, sev);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Voice lines. The Greeks' lines are recorded clips (§13), decoded on first need or preloaded
  // for a battle, played one at a time like the text-to-speech they replace (a new line cuts
  // off the one being said). Anything else, or a clip that can't be had, goes to platform TTS.

  const clips = new Map(); // file → { file, buf, bytes, used, plays, failed, promise }
  const vstats = { started: 0, tts: 0, late: 0, failed: 0 };
  let clipBytes = 0;
  let decodeFails = 0;
  let speech = null; // the clip being said: { v, entry, src, g, out, sg, t0 }
  let held = null; // the line the pause menu cut: { v, entry, offset }
  let waiting = null; // a line whose clip is still loading: { v, sp, at }
  let ttsUntil = 0; // now() until which a TTS line may still be talking

  const voiceOff = () => !!ctx.settings && ctx.settings.voice === false;
  /** Clip files are relative to the game root (index.html's folder). */
  const clipUrl = (file) => new URL('../../' + String(file).replace(/^\/+/, ''), import.meta.url).href;

  function decode(data) {
    return new Promise((resolve, reject) => {
      // Callbacks for old WebKit; the promise newer engines also return is caught so it can't go unhandled.
      const p = ac.decodeAudioData(data, resolve, (err) => reject(err || new Error('decode failed')));
      if (p && typeof p.catch === 'function') p.catch(() => {});
    });
  }

  /** The decoded clip for `file` (loading it once): resolves to its cache entry, or rejects. */
  function loadClip(file) {
    let e = clips.get(file);
    if (e) {
      e.used = now();
      return e.promise;
    }
    e = { file, buf: null, bytes: 0, used: now(), plays: 0, failed: false, promise: null };
    clips.set(file, e);
    const entry = e;
    e.promise = Promise.resolve()
      .then(() => fetch(clipUrl(file)))
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.arrayBuffer();
      })
      .then((data) =>
        decode(data).catch((err) => {
          decodeFails++;
          throw err;
        }),
      )
      .then((buf) => {
        entry.buf = buf;
        entry.bytes = buf.length * buf.numberOfChannels * 4;
        clipBytes += entry.bytes;
        trimClips();
        return entry;
      })
      .catch((err) => {
        entry.failed = true;
        vstats.failed++;
        warnOnce(`voice clip ${file} unavailable (${(err && err.message) || err}); speaking it with text-to-speech`);
        throw err;
      });
    e.promise.catch(() => {}); // a preload nobody waits on
    return e.promise;
  }

  /**
   * Keeps decoded clips under CLIP_CACHE: lines already spoken go first (least recently used
   * first), then preloaded ones still waiting for their turn. The line being said stays.
   */
  function trimClips() {
    while (clipBytes > CLIP_CACHE) {
      let victim = null;
      for (const e of clips.values()) {
        if (!e.buf || (speech && speech.entry === e) || (held && held.entry === e)) continue;
        if (!victim || (e.plays > 0) > (victim.plays > 0) || ((e.plays > 0) === (victim.plays > 0) && e.used < victim.used)) victim = e;
      }
      if (!victim) return;
      clipBytes -= victim.bytes;
      clips.delete(victim.file);
    }
  }

  /** Two clips that won't decode: this engine can't play Ogg Opus, so every line goes to TTS. */
  const clipsBroken = () => decodeFails >= 2;

  /** Pan for a speaker's place, from where the player stands and looks (−1 left … 1 right). */
  function placePan(place) {
    const p = ctx.player && ctx.player.pos;
    const dx = place.x - num(p && p.x, 0), dz = place.z - num(p && p.z, 0);
    const d = Math.hypot(dx, dz);
    return d < 0.3 ? 0 : clamp(sideOf(dx, dz) / d, -1, 1) * place.pan;
  }

  function duckFor(seconds) {
    mix.duckUntil = Math.max(mix.duckUntil, ac.currentTime + Math.max(0, seconds) + 0.25);
  }

  /** Fades out the clip being said (if any). */
  function cutSpeech() {
    const s = speech;
    if (!s) return;
    speech = null;
    const t = ac.currentTime;
    try {
      const p = s.g.gain;
      p.cancelScheduledValues(t);
      p.setValueAtTime(p.value, t);
      p.linearRampToValueAtTime(0, t + 0.08);
      s.src.stop(t + 0.1);
    } catch { /* already stopped */ }
  }

  /** Starts a decoded line `offset` s in, through the voice bus with a send into the pass. */
  function startClip(v, entry, offset) {
    const buf = entry.buf;
    if (!buf || !live() || voiceOff() || offset >= buf.duration - 0.05) return false;
    cutSpeech();
    if (ttsUntil > now()) stopTts();
    const place = VOICE_PLACES[v.speaker] || DEFAULT_PLACE;
    const pan = placePan(place);
    const t = ac.currentTime;
    const src = ac.createBufferSource();
    src.buffer = buf;
    const g = gain(0);
    src.connect(g);
    let out = g;
    if (pan && ac.createStereoPanner) {
      out = ac.createStereoPanner();
      out.pan.value = pan;
      g.connect(out);
    }
    out.connect(voiceBus);
    const sg = gain(place.wet, verbIn);
    out.connect(sg);
    // A line resumed after the pause menu fades back in rather than clicking in mid-word.
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(place.gain, t + (offset > 0 ? 0.06 : 0.008));
    const s = { v, entry, src, g, out, sg, t0: t - offset };
    src.onended = () => {
      if (speech === s) speech = null;
      try {
        src.disconnect();
        g.disconnect();
        if (out !== g) out.disconnect();
        sg.disconnect();
      } catch { /* already disconnected */ }
    };
    src.start(t, offset);
    speech = s;
    entry.used = now();
    entry.plays++;
    vstats.started++;
    duckFor(buf.duration - offset);
    if (log.length >= 200) log.shift();
    log.push({ name: 'voice', key: v.key || null, speaker: v.speaker, file: entry.file, volume: place.gain, pan, offset, t });
    return true;
  }

  /** A recorded line: now if decoded, else when it arrives (if that's soon), else TTS if it can't be had. */
  function sayClip(v, sp) {
    const file = v.clip.file;
    const e = clips.get(file);
    if (e && e.failed) {
      sayTts(v, sp);
      return;
    }
    cutSpeech();
    if (e && e.buf) {
      startClip(v, e, 0);
      return;
    }
    const w = (waiting = { v, sp, at: now() });
    duckFor(num(v.clip.dur, 2));
    loadClip(file).then(
      (entry) => {
        if (waiting !== w) return; // another line, the pause menu or voices off came first
        waiting = null;
        if (now() - w.at > VOICE_LATE * 1000) vstats.late++;
        else startClip(w.v, entry, 0);
      },
      () => {
        if (waiting !== w) return;
        waiting = null;
        sayTts(w.v, w.sp);
      },
    );
  }

  /** The English line through platform TTS (no-op there unless settings.voice). */
  function sayTts(v, sp) {
    const text = v.text == null ? '' : String(v.text);
    if (!text) return;
    if (ac) cutSpeech();
    const pv = ctx.platform && ctx.platform.voice;
    if (pv && typeof pv.say === 'function') {
      try {
        pv.say(text, { pitch: sp.pitch, rate: sp.rate });
        vstats.tts++;
      } catch (err) {
        console.warn('audio: voice failed', err);
      }
    }
    const secs = 0.5 + text.length / (14 * sp.rate);
    ttsUntil = now() + secs * 1000;
    // Duck the music and the world while someone speaks (TTS lives outside WebAudio).
    if (ac && !voiceOff()) duckFor(secs - 0.25);
  }

  function stopTts() {
    ttsUntil = 0;
    const pv = ctx.platform && ctx.platform.voice;
    try {
      if (pv && typeof pv.stop === 'function') pv.stop();
    } catch { /* ignore */ }
  }

  /** §12 `voice`: { key, text, speaker, greek?, clip?: { file, dur } }. Never throws. */
  function sayLine(v) {
    try {
      const sp = SPEAKERS[v.speaker] || DEFAULT_SPEAKER;
      const clip = v.clip && typeof v.clip.file === 'string' && v.clip.file ? v.clip : null;
      if (!v.text && !clip) return;
      held = null; // a new line replaces one the pause menu cut
      waiting = null;
      if (clip && ac && !clipsBroken()) {
        if (voiceOff()) cutSpeech(); // silent; the HUD still shows the subtitles
        else sayClip(v, sp);
        return;
      }
      sayTts(v, sp);
    } catch (err) {
      warnOnce('voice line failed: ' + ((err && err.message) || err));
    }
  }

  /** The pause menu stops the line being said; resume() takes it up again a little before the cut. */
  function holdSpeech() {
    waiting = null;
    const s = speech;
    if (!s) return;
    const offset = ac.currentTime - s.t0;
    cutSpeech();
    if (offset < s.entry.buf.duration - 0.3) held = { v: s.v, entry: s.entry, offset: Math.max(0, offset - 0.25) };
  }

  function onMode(m) {
    if (!ac) return;
    try {
      if (m.mode === 'paused') {
        holdSpeech();
      } else if ((m.mode === 'battle' || m.mode === 'training') && m.prev === 'paused') {
        const h = held;
        held = null;
        if (h) startClip(h.v, h.entry, h.offset);
      } else {
        held = null;
        waiting = null;
        cutSpeech();
      }
    } catch (err) {
      warnOnce('voice pause failed: ' + ((err && err.message) || err));
    }
  }

  /** Decodes clips ahead of need (waves asks for a battle's barks and lines at each dayStart). */
  function preloadVoice(files) {
    if (!ac || voiceOff() || clipsBroken() || !Array.isArray(files) || typeof fetch !== 'function') return;
    for (const f of files) if (typeof f === 'string' && f) loadClip(f);
  }

  // ------------------------------------------------------------------------------------------
  // Events (§12)

  const isPlayer = (e) => !!e.byPlayer || e.attacker === 'player';
  const posOf = (actor) => (actor && actor.pos ? actor.pos : null);
  const tmpAt = { x: 0, y: 0, z: 0 };
  let whistle = null;

  function guardSound(actor) {
    if (!actor || actor.team === 'greek') return 'clang';
    return actor.kind === 'captain' ? 'wood' : 'wicker';
  }

  const handlers = {
    swing(p) {
      const power = clamp(num(p.power, 0.6), 0, 1);
      if (p.kind === 'throw') playSfx('whoosh', { volume: 0.25, rate: 0.7 });
      else if (p.kind === 'bash') playSfx('whooshHeavy', { volume: 0.4 + 0.3 * power, rate: 0.82 });
      else if (p.kind === 'spearThrust') playSfx('whooshHeavy', { volume: 0.55 + 0.35 * power, rate: 1.05 + 0.15 * power });
      else if (p.kind === 'thrust') playSfx('whoosh', { volume: 0.5 + 0.35 * power, rate: 1.15 + 0.15 * power });
      else {
        const heavy = p.weapon === 'spear' || power > 0.75;
        playSfx(heavy ? 'whooshHeavy' : 'whoosh', { volume: 0.55 + 0.45 * power, rate: (heavy ? 0.95 : 0.9) + 0.22 * power });
      }
    },
    hit(h) {
      if (!h.target) return;
      const kind = h.kind || 'melee';
      if (kind === 'bleed' || PROJECTILE_KINDS.has(kind)) return; // spearImpact / arrowHit sound those
      const mine = isPlayer(h);
      const at = h.point || posOf(h.target);
      const o = { volume: mine ? 1 : AI_VOL, at, prio: mine ? 3 : 1 };
      if (h.guarded) {
        playSfx(guardSound(h.target), o);
        return;
      }
      if (kind === 'bash') {
        playSfx('bash', o);
        return;
      }
      const heavy = h.killed || h.critical || kind === 'spearThrust' || kind === 'axe' || num(h.damage, 0) >= 45 || !!h.combo;
      if (h.critical) o.rate = 0.9;
      playSfx(heavy ? 'fleshHeavy' : 'flesh', o);
    },
    kill(k) {
      if (!k.target) return;
      const mine = isPlayer(k);
      const at = posOf(k.target);
      // Named allies (Leonidas on Day 3) fall at full volume, not as background AI-vs-AI.
      const vol = mine || (k.target.team === 'greek' && k.target.name) ? 1 : AI_VOL;
      // Into the Gulf: ai.js plays the scream at the lip and the splash at the water, in game time.
      if (k.cause === 'sea') return;
      playSfx('death', { volume: vol * 0.9, at, prio: vol === 1 ? 3 : 2, rate: k.target.team === 'greek' ? 0.92 : 1 });
    },
    block(b) {
      if (b.by === 'player') {
        const name = b.kind === 'guardBreak' ? 'guardBreak' : b.perfect ? 'parry' : 'clang';
        playSfx(name, { volume: 1, prio: 4, pan: 0 });
        return;
      }
      playSfx(guardSound(b.by), { volume: AI_VOL, at: b.point || posOf(b.by), prio: 1 });
    },
    shield(s) {
      if (s.up) playSfx('shieldUp', { volume: 0.8, prio: 2 });
    },
    playerHurt(p) {
      const pan = clamp(-Math.sin(num(p.relYaw, 0)) * 0.6, -0.6, 0.6);
      playSfx('hurt', { volume: 1, prio: 5 });
      if (p.kind === 'arrow') playSfx('arrowThud', { volume: 0.9, pan, prio: 4 });
      else playSfx(p.kind === 'axe' ? 'fleshHeavy' : 'flesh', { volume: 0.75, pan, prio: 4 });
    },
    playerDeath() {
      playSfx('death', { volume: 1, rate: 0.88, prio: 5 });
      hb.on = false;
    },
    enemyWindup(w) {
      if (!w.targetIsPlayer || !w.actor) return;
      const cap = w.actor.kind === 'captain';
      const p = posOf(w.actor);
      if (p) {
        tmpAt.x = num(p.x, 0);
        tmpAt.y = num(p.y, 0) + 1.6;
        tmpAt.z = num(p.z, 0);
      }
      playSfx('grunt', { at: p ? tmpAt : null, volume: cap ? 1.45 : 1, rate: cap ? 0.78 : 1, prio: 3 });
    },
    spearThrown(e) {
      const pr = e.projectile || {};
      const mine = pr.byPlayer !== false;
      playSfx('spearThrow', { volume: 1, prio: 4, at: mine ? null : pr.pos });
      whistle = playSfx('spearWhistle', { volume: 0.75, delay: 0.05, prio: 3, at: mine ? null : pr.pos });
    },
    spearImpact(e) {
      if (whistle) {
        whistle.stop();
        whistle = null;
      }
      const at = e.point || (e.projectile && e.projectile.pos) || null;
      const name = e.water ? 'splash' : e.target ? 'spearHit' : 'spearGround';
      playSfx(name, { volume: 1.5, at, prio: 4 });
    },
    arrowHit(a) {
      if (a.target === 'shield') playSfx('arrowShield', { volume: 1, prio: 3 });
      else if (a.target === 'player') playSfx('arrowThud', { volume: 0.9, prio: 3 });
      else playSfx('arrowThud', { volume: a.target ? 0.7 : 0.45, at: a.point || posOf(a.target), prio: a.target ? 1 : 0 });
    },
    volley(v) {
      if (v.phase === 'warning') playSfx('horn', { bus: 'distant', volume: 1.2, pan: sideOf(0, -1) * 0.4, prio: 4 });
      else if (v.phase === 'loosed') playSfx('volley', { volume: 1, prio: 5, exact: true });
      else if (v.phase === 'impact') arrowRain(clamp(num(v.duration, 1), 0.3, 3));
    },
    combo(c) {
      playSfx('comboSting', { volume: 0.9, rate: COMBO_RATE[c.id] || 1, prio: 5, exact: true });
    },
    waveStart() {
      playSfx('warcry', { volume: 1, prio: 5 });
      playSfx('horn', { bus: 'distant', delay: 1.3, volume: 0.8, rate: 0.95, pan: sideOf(0, -1) * 0.4, prio: 3 });
      drumPattern(10, 0.42, 1.8, 0.9, 2);
      amb.drumT = Math.max(amb.drumT, 16);
    },
    weaponSwitch() {
      playSfx('draw', { volume: 0.6, prio: 2 });
    },
    dayStart(d) {
      if (num(d.day, 1) >= 3) queue('loop', 'lastStand', 2); // ready before the Kolonos
      // A new day, or a restart from the pause menu: whatever was being said is over.
      if (ac) {
        held = null;
        waiting = null;
        cutSpeech();
      }
    },
    voice: sayLine,
    modeChanged: onMode,
  };

  /** The arrows that miss the player still rain down all along the line. */
  function arrowRain(dur) {
    const px = num(ctx.player && ctx.player.pos && ctx.player.pos.x, 0);
    for (let i = 0; i < 16; i++) {
      tmpAt.x = px + (rng.next() - 0.5) * 14;
      tmpAt.y = 0;
      tmpAt.z = (rng.next() - 0.6) * 9;
      playSfx('arrowThud', { at: tmpAt, delay: rng.next() * dur, volume: 0.45 + rng.next() * 0.4, prio: 0 });
    }
    for (let i = 0; i < 4; i++) {
      tmpAt.x = rng.pick(ALLY_X);
      tmpAt.y = 1.4;
      tmpAt.z = 0;
      playSfx('arrowShield', { at: tmpAt, delay: rng.next() * dur, volume: 0.55, prio: 0 });
    }
  }

  const offs = [];
  if (ctx.events && typeof ctx.events.on === 'function') {
    for (const name of Object.keys(handlers)) {
      const fn = handlers[name];
      offs.push(ctx.events.on(name, (payload) => fn(payload || {})));
    }
  }

  // ------------------------------------------------------------------------------------------
  // Lifecycle

  function unlock() {
    if (!ac) return;
    wantRunning = true;
    if (ac.state !== 'running' && ac.state !== 'closed') {
      resumeAt = now();
      ac.resume().catch(() => {});
    }
    try {
      // A silent sample started inside the gesture also unlocks WebKit.
      const s = ac.createBufferSource();
      s.buffer = ac.createBuffer(1, 1, ac.sampleRate);
      s.connect(ac.destination);
      s.start(0);
    } catch { /* ignore */ }
  }

  // Safety net: any gesture restarts a context the browser has suspended (autoplay policy,
  // an interruption), unless we suspended it ourselves. Costs one comparison per event.
  const onGesture = () => {
    if (wantRunning && ac.state !== 'running') unlock();
  };
  if (ac && hasWindow && window.addEventListener) {
    for (const n of ['pointerdown', 'touchend', 'keydown']) window.addEventListener(n, onGesture, { capture: true, passive: true });
  }

  function suspend() {
    if (!ac) return;
    wantRunning = false;
    if (ac.state === 'running') ac.suspend().catch(() => {});
  }

  function resume() {
    if (!ac) return;
    wantRunning = true;
    if (ac.state !== 'running' && ac.state !== 'closed') {
      resumeAt = now();
      ac.resume().catch(() => {});
    }
  }

  function update(dt) {
    if (!ac) return;
    if (pool.length) {
      watchdog();
      dispatch();
    }
    if (!pool.length) pumpMain(ctx.mode === 'battle' || ctx.mode === 'training' ? 3 : 8);
    if ((speech || held) && voiceOff()) {
      held = null;
      cutSpeech(); // voices turned off in the settings
    }
    if (ac.state !== 'running') return;
    const d = clamp(num(dt, 0), 0, 0.1);
    for (const L of layers) if (L.nodes) updateLayerPan(L, false);
    updateSteps();
    updateHeartbeat();
    updateAmbientEvents(d);
    updateMix();
  }

  /** Resolves when everything queued so far is rendered (drives time slices without a worker). */
  function prerender() {
    if (!ac) return Promise.resolve();
    return new Promise((resolve) => {
      idleResolvers.push(resolve);
      checkIdle();
      const step = () => {
        if (pool.length) watchdog();
        if (!pool.length) pumpMain(12);
        if (jobs.length || pool.some((sl) => sl.job)) setTimeout(step, pool.length ? 100 : 0);
      };
      step();
    });
  }

  if (ac) applyVolumes();

  /** Public play() for other modules. One that plays 'step' itself takes over footsteps. */
  function play(name, opts) {
    if (name === 'step') steps.external = true;
    return playSfx(name, opts);
  }

  return {
    unlock,
    play,
    setAmbience,
    setMusic,
    applyVolumes,
    suspend,
    resume,
    update,
    prerender,
    preloadVoice,
    /** Queues a loop for rendering ahead of need (e.g. 'lastStand'). */
    preload(name) {
      if (LOOPS[name]) queue('loop', name, 2);
      else if (SFX[name]) queue('sfx', name, 2);
    },
    get context() {
      return ac;
    },
    get state() {
      return ac ? ac.state : 'unavailable';
    },
    /** Diagnostics for tests and the debug overlay. */
    stats() {
      return {
        ...stats,
        state: ac ? ac.state : 'unavailable',
        sampleRate: ac ? ac.sampleRate : 0,
        voices: voices.reduce((n, v) => n + (v.dying ? 0 : 1), 0),
        pending: jobs.length + pool.reduce((n, s) => n + (s.job ? 1 : 0), 0),
        ready: bufs.size,
        mb: stats.bytes / 1048576,
        music: music.cur ? music.cur.name : 'none',
        ambience: amb.kind,
        heartbeat: hb.on,
        voice: {
          ...vstats,
          speaking: speech ? speech.entry.file : null,
          held: held ? held.entry.file : null,
          loading: waiting ? waiting.v.clip.file : null,
          cached: [...clips.values()].filter((e) => e.buf).length,
          mb: clipBytes / 1048576,
          broken: clipsBroken(),
        },
      };
    },
    log,
    /** Test hook: a rendered buffer ('loop:<name>' or an SFX name + variant), or null. */
    getBuffer(name, variant = 0) {
      const b = bufs.get(LOOPS[name] ? 'loop:' + name : 'sfx:' + name);
      return Array.isArray(b) ? b[variant] || null : b || null;
    },
    /** Test hook: an AnalyserNode on the final output. */
    analyser() {
      if (!ac) return null;
      if (!analyser) {
        analyser = ac.createAnalyser();
        analyser.fftSize = 2048;
        limiter.connect(analyser);
      }
      return analyser;
    },
    dispose() {
      for (const off of offs) off();
      if (ac) cutSpeech();
      if (hasWindow && window.removeEventListener) for (const n of ['pointerdown', 'touchend', 'keydown']) window.removeEventListener(n, onGesture, { capture: true });
      for (const slot of pool) slot.w.terminate();
      pool.length = 0;
      if (ac) ac.close().catch(() => {});
    },
  };
}

function now() {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}
