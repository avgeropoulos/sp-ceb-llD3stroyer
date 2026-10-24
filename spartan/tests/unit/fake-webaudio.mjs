// A small fake of WebAudio, fetch and the module Worker, enough to run js/audio/audio.js under Node:
// AudioParam automation (set / linear ramp / target) is evaluated at any time, sources record how they
// were started and stopped and fire onended as the fake clock advances, buffers are lazily allocated
// (or synthesized by a fill function, so 46 MB cues cost nothing until read), and decodeAudioData
// returns what the fake "file" bytes describe. Used by tests/unit/music-cues.test.mjs.
import { SFX } from '../../src/main/assets/game/js/audio/sfx.js';

export class FakeParam {
  constructor(ctx, v) {
    this.ctx = ctx;
    this.base = v;
    this.events = [];
  }
  get value() {
    return this.at(this.ctx.currentTime);
  }
  set value(v) {
    this.base = v;
  }
  add(e) {
    this.events.push(e);
    this.events.sort((a, b) => a.t - b.t); // stable: same-time events keep their order
    return this;
  }
  setValueAtTime(v, t) {
    return this.add({ type: 'set', v, t });
  }
  linearRampToValueAtTime(v, t) {
    return this.add({ type: 'ramp', v, t });
  }
  setTargetAtTime(v, t, tc) {
    return this.add({ type: 'target', v, t, tc });
  }
  cancelScheduledValues(t) {
    this.events = this.events.filter((e) => e.t < t);
    return this;
  }
  /** The automated value at time t (Web Audio's rules for these three event kinds). */
  at(t) {
    let v = this.base, vt = 0, target = null;
    const cur = (x) => (target ? target.v + (target.v0 - target.v) * Math.exp(-(x - target.t) / target.tc) : v);
    for (const e of this.events) {
      if (e.type === 'ramp') {
        if (t < e.t) {
          const v0 = cur(vt);
          return e.t > vt ? v0 + (e.v - v0) * (t - vt) / (e.t - vt) : e.v;
        }
        v = e.v;
        target = null;
        vt = e.t;
        continue;
      }
      if (e.t > t) break;
      if (e.type === 'set') {
        v = e.v;
        target = null;
      } else {
        target = { v: e.v, t: e.t, tc: e.tc, v0: cur(e.t) };
      }
      vt = e.t;
    }
    return cur(t);
  }
}

export class FakeNode {
  constructor(ctx, kind) {
    this.context = ctx;
    this.kind = kind;
    this.outs = new Set();
  }
  connect(n) {
    this.outs.add(n);
    return n;
  }
  disconnect() {
    this.outs.clear();
  }
}

export class FakeBuffer {
  /** fill(channel, frame) → sample synthesizes the content without allocating it. */
  constructor({ numberOfChannels, length, sampleRate, fill = null, tag = null }) {
    Object.assign(this, { numberOfChannels, length, sampleRate, fill, tag });
    this.data = [];
  }
  get duration() {
    return this.length / this.sampleRate;
  }
  getChannelData(c) {
    if (!this.data[c]) {
      const a = new Float32Array(this.length);
      if (this.fill) for (let i = 0; i < a.length; i++) a[i] = this.fill(c, i);
      this.data[c] = a;
    }
    return this.data[c];
  }
  copyFromChannel(dest, c, off = 0) {
    if (this.data[c]) {
      dest.set(this.data[c].subarray(off, off + dest.length));
      return;
    }
    const n = Math.min(dest.length, this.length - off);
    for (let i = 0; i < n; i++) dest[i] = this.fill ? this.fill(c, off + i) : 0;
  }
  copyToChannel(src, c, off = 0) {
    this.getChannelData(c).set(src, off);
  }
}

export class FakeSource extends FakeNode {
  constructor(ctx) {
    super(ctx, 'source');
    this._buffer = null;
    this.bufferSet = false;
    this.loop = false;
    this.loopStart = 0;
    this.loopEnd = 0;
    this.playbackRate = new FakeParam(ctx, 1);
    this.when = null;
    this.offset = 0;
    this.stopAt = null;
    this.ended = false;
    this.onended = null;
    this.firstBuffer = null;
    ctx.sources.push(this);
  }
  get buffer() {
    return this._buffer;
  }
  set buffer(b) {
    if (b && this.bufferSet) throw new Error('InvalidStateError: the buffer can only be set once');
    if (b) {
      this.bufferSet = true;
      this.firstBuffer = b;
    }
    this._buffer = b;
  }
  start(when = 0, offset = 0) {
    if (this.when !== null) throw new Error('InvalidStateError: start() called twice');
    this.when = when;
    this.startAt = Math.max(when, this.context.currentTime);
    this.offset = offset;
  }
  stop(when) {
    if (this.when === null) throw new Error('InvalidStateError: stop() before start()');
    this.stopAt = when === undefined ? this.context.currentTime : when;
  }
  endTime() {
    if (this.when === null) return Infinity;
    let end = this.stopAt === null ? Infinity : Math.max(this.stopAt, this.startAt);
    const b = this.firstBuffer;
    if (!this.loop && b) end = Math.min(end, this.startAt + b.duration - this.offset);
    return end;
  }
  /** The product of the first `hops` gains after this source at time t (a music cue: hand-off × fade). */
  gainAt(t, hops = 2) {
    let g = 1;
    let n = [...this.outs][0];
    for (let k = 0; k < hops; k++) {
      if (!n || n.kind !== 'gain') return 0;
      g *= n.gain.at(t);
      n = [...n.outs][0];
    }
    return g;
  }
}

/** Bytes a fake file decodes from: { cue, channels, length, rate, fail }. */
export const fakeFile = (desc) => new TextEncoder().encode(JSON.stringify(desc)).buffer;

function fakeDecode(data, decoderRate) {
  let d;
  try {
    d = JSON.parse(new TextDecoder().decode(new Uint8Array(data)));
  } catch {
    return Promise.reject(new Error('EncodingError: unable to decode audio data'));
  }
  if (d.fail) return Promise.reject(new Error('EncodingError: unable to decode audio data'));
  // A real decoder resamples the 48 kHz file to its context's rate (and a sample may go missing).
  const rate = decoderRate;
  const length = rate === 48000 ? d.length : Math.floor(d.length * rate / 48000);
  const seed = d.seed || 0;
  return Promise.resolve(new FakeBuffer({
    numberOfChannels: d.channels || 1, length, sampleRate: rate, tag: d.cue || d.clip || null,
    fill: (c, i) => (((i * 7 + c * 13 + seed) % 1000) - 500) / 1000,
  }));
}

function decodeWith(rate, list, data, ok, err) {
  list.push(data);
  const p = fakeDecode(data, rate);
  p.then((b) => ok && ok(b), (e) => err && err(e));
  return p;
}

export class FakeAudioContext {
  constructor() {
    this.sampleRate = FakeAudioContext.rate;
    this.currentTime = 0;
    this.state = FakeAudioContext.initialState;
    this.sources = [];
    this.decodes = [];
    this.gains = [];
    this.destination = new FakeNode(this, 'destination');
    this.baseLatency = 0.01;
    FakeAudioContext.last = this;
  }
  createGain() {
    const g = new FakeNode(this, 'gain');
    g.gain = new FakeParam(this, 1);
    this.gains.push(g);
    return g;
  }
  createBufferSource() {
    return new FakeSource(this);
  }
  createBiquadFilter() {
    const n = new FakeNode(this, 'filter');
    n.type = 'lowpass';
    n.frequency = new FakeParam(this, 350);
    n.Q = new FakeParam(this, 1);
    n.gain = new FakeParam(this, 0);
    return n;
  }
  createDynamicsCompressor() {
    const n = new FakeNode(this, 'compressor');
    for (const k of ['threshold', 'knee', 'ratio', 'attack', 'release']) n[k] = new FakeParam(this, 0);
    return n;
  }
  createConvolver() {
    const n = new FakeNode(this, 'convolver');
    n.normalize = true;
    n.buffer = null;
    return n;
  }
  createStereoPanner() {
    const n = new FakeNode(this, 'panner');
    n.pan = new FakeParam(this, 0);
    return n;
  }
  createAnalyser() {
    const n = new FakeNode(this, 'analyser');
    n.fftSize = 2048;
    n.getFloatTimeDomainData = (a) => a.fill(0);
    return n;
  }
  createBuffer(ch, len, sr) {
    return new FakeBuffer({ numberOfChannels: ch, length: len, sampleRate: sr });
  }
  decodeAudioData(data, ok, err) {
    return decodeWith(this.sampleRate, this.decodes, data, ok, err);
  }
  resume() {
    this.state = 'running';
    return Promise.resolve();
  }
  suspend() {
    this.state = 'suspended';
    return Promise.resolve();
  }
  close() {
    this.state = 'closed';
    return Promise.resolve();
  }
  /** Moves the clock on in small steps, ending sources (onended) as their time comes. */
  advance(seconds, step = 0.01) {
    const end = this.currentTime + seconds;
    while (this.currentTime < end - 1e-9) {
      if (this.state === 'running') this.currentTime = Math.min(end, this.currentTime + step);
      else break;
      for (const s of this.sources) {
        if (!s.ended && s.endTime() <= this.currentTime + 1e-9) {
          s.ended = true;
          if (s.onended) s.onended();
        }
      }
    }
  }
}
FakeAudioContext.rate = 48000;
FakeAudioContext.initialState = 'running';

export class FakeOfflineAudioContext {
  constructor(channels, length, sampleRate) {
    this.sampleRate = sampleRate;
    FakeOfflineAudioContext.made.push(this);
  }
  decodeAudioData(data, ok, err) {
    return decodeWith(this.sampleRate, FakeOfflineAudioContext.decodes, data, ok, err);
  }
}
FakeOfflineAudioContext.made = [];
FakeOfflineAudioContext.decodes = [];

/** The render worker: answers every job at once with tiny silent buffers (or holds loop jobs). */
export class FakeWorker {
  constructor() {
    this.onmessage = null;
    FakeWorker.all.push(this);
  }
  postMessage(req) {
    FakeWorker.requests.push(req);
    const reply = () => {
      const n = req.kind === 'sfx' ? SFX[req.name].variants : 1;
      const chans = Array.from({ length: n }, () => new Float32Array(16));
      if (this.onmessage) this.onmessage({ data: { id: req.id, kind: req.kind, name: req.name, sr: req.kind === 'sfx' ? req.sr : 24000, chans, ms: 0 } });
    };
    if (req.kind === 'loop' && FakeWorker.holdLoops) FakeWorker.held.push(reply);
    else setImmediate(reply);
  }
  terminate() {}
  static releaseLoops() {
    const h = FakeWorker.held.splice(0);
    for (const r of h) r();
  }
}
FakeWorker.all = [];
FakeWorker.requests = [];
FakeWorker.held = [];
FakeWorker.holdLoops = false;

/**
 * Installs the fakes as globals (window, Worker, fetch, performance.now) and returns controls:
 * { files, gate(url), clock, tick(ms) }. `files` maps a URL suffix to fake file bytes or an HTTP status.
 */
export function installFakes() {
  const state = { clock: 0, files: new Map(), gates: new Map(), fetched: [] };
  globalThis.window = {
    AudioContext: FakeAudioContext,
    OfflineAudioContext: FakeOfflineAudioContext,
    navigator: { hardwareConcurrency: 4, deviceMemory: 8 },
    addEventListener() {},
    removeEventListener() {},
  };
  globalThis.Worker = FakeWorker;
  Object.defineProperty(globalThis.performance, 'now', { value: () => state.clock, configurable: true, writable: true });
  globalThis.fetch = async (url) => {
    state.fetched.push(String(url));
    const key = [...state.files.keys()].find((k) => String(url).endsWith(k));
    const gate = [...state.gates.keys()].find((k) => String(url).endsWith(k));
    if (gate) await state.gates.get(gate).promise;
    const v = key ? state.files.get(key) : 404;
    if (typeof v === 'number') return { ok: false, status: v, arrayBuffer: async () => new ArrayBuffer(0) };
    return { ok: true, status: 200, arrayBuffer: async () => v.slice(0) };
  };
  return {
    state,
    /** Holds fetches of `suffix` until the returned function is called. */
    gate(suffix) {
      let open;
      const promise = new Promise((r) => (open = r));
      state.gates.set(suffix, { promise });
      return () => {
        state.gates.delete(suffix);
        open();
      };
    },
  };
}

/** Lets pending promises, setImmediate callbacks and fake worker replies run. */
export async function settle(rounds = 8) {
  for (let i = 0; i < rounds; i++) await new Promise((r) => setImmediate(r));
}
