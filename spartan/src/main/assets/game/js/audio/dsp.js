/**
 * PURE DSP building blocks for the synthesized audio: noise, filters, envelopes, modal
 * resonators, a formant voice, a plucked string, a reverb and loop helpers. Everything renders
 * into plain Float32Arrays, so it runs (and is tested) under Node as well as in the browser.
 */

export const TAU = Math.PI * 2;
const LN1000 = 6.907755278982137; // ln(1000): a T60 decay is exp(-LN1000 * t / t60)

/** Fast deterministic white noise in [-1, 1) (xorshift32). */
export function createNoise(seed = 1) {
  let s = (Math.imul(seed | 0, 2654435761) >>> 0) || 0x9e3779b9;
  return function noise() {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 2147483648 - 1;
  };
}

/** Pink-ish (-3 dB/oct) noise from a white source (Paul Kellet's economy filter). */
export function createPink(white) {
  let b0 = 0, b1 = 0, b2 = 0;
  return function pink() {
    const w = white();
    b0 = 0.99765 * b0 + w * 0.099046;
    b1 = 0.963 * b1 + w * 0.2965164;
    b2 = 0.57 * b2 + w * 1.0526913;
    return (b0 + b1 + b2 + w * 0.1848) * 0.2;
  };
}

/** Brown (-6 dB/oct) noise: a leaky integrator of white noise. */
export function createBrown(white) {
  let y = 0;
  return function brown() {
    y = y * 0.996 + white() * 0.06;
    return y;
  };
}

/** Biquad filter (RBJ cookbook, transposed direct form II). Setters return `this`. */
export class Biquad {
  constructor() {
    this.b0 = 1; this.b1 = 0; this.b2 = 0; this.a1 = 0; this.a2 = 0;
    this.z1 = 0; this.z2 = 0;
  }

  _set(b0, b1, b2, a0, a1, a2) {
    this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
    this.a1 = a1 / a0; this.a2 = a2 / a0;
    return this;
  }

  lowpass(sr, f, q = 0.7071) {
    const w = TAU * clampF(f, sr) / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
    return this._set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
  }

  highpass(sr, f, q = 0.7071) {
    const w = TAU * clampF(f, sr) / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
    return this._set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
  }

  /** Band-pass with 0 dB peak gain. */
  bandpass(sr, f, q = 1) {
    const w = TAU * clampF(f, sr) / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
    return this._set(al, 0, -al, 1 + al, -2 * c, 1 - al);
  }

  peak(sr, f, q, db) {
    const A = Math.pow(10, db / 40), w = TAU * clampF(f, sr) / sr, c = Math.cos(w), al = Math.sin(w) / (2 * q);
    return this._set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
  }

  lowshelf(sr, f, db) {
    const A = Math.pow(10, db / 40), w = TAU * clampF(f, sr) / sr, c = Math.cos(w);
    const al = Math.sin(w) / 2 * Math.SQRT2, sa = 2 * Math.sqrt(A) * al;
    return this._set(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
      (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
  }

  highshelf(sr, f, db) {
    const A = Math.pow(10, db / 40), w = TAU * clampF(f, sr) / sr, c = Math.cos(w);
    const al = Math.sin(w) / 2 * Math.SQRT2, sa = 2 * Math.sqrt(A) * al;
    return this._set(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
      (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
  }

  process(x) {
    const y = this.b0 * x + this.z1;
    this.z1 = this.b1 * x - this.a1 * y + this.z2;
    this.z2 = this.b2 * x - this.a2 * y;
    return y;
  }

  reset() {
    this.z1 = this.z2 = 0;
    return this;
  }
}

function clampF(f, sr) {
  return f < 10 ? 10 : f > sr * 0.47 ? sr * 0.47 : f;
}

/** One-pole low-pass (6 dB/oct). */
export class OnePole {
  constructor(sr, f) {
    this.y = 0;
    this.set(sr, f);
  }

  set(sr, f) {
    this.a = 1 - Math.exp(-TAU * clampF(f, sr) / sr);
    return this;
  }

  process(x) {
    this.y += this.a * (x - this.y);
    return this.y;
  }
}

/** Two-pole resonator with unity DC gain (Klatt formant resonator). */
export class Reson {
  constructor() {
    this.a = 1; this.b = 0; this.c = 0; this.y1 = 0; this.y2 = 0;
  }

  set(sr, f, bw) {
    const c = -Math.exp(-TAU * bw / sr);
    const b = 2 * Math.exp(-Math.PI * bw / sr) * Math.cos(TAU * Math.min(f, sr * 0.45) / sr);
    this.a = 1 - b - c; this.b = b; this.c = c;
    return this;
  }

  process(x) {
    const y = this.a * x + this.b * this.y1 + this.c * this.y2;
    this.y2 = this.y1;
    this.y1 = y;
    return y;
  }
}

// ---------------------------------------------------------------------------------------------
// Envelopes and shapes

/** Attack (raised cosine) then exponential decay with time constant `tau`. */
export function adEnv(t, attack, tau) {
  if (t < 0) return 0;
  if (t < attack) return 0.5 - 0.5 * Math.cos(Math.PI * t / attack);
  return Math.exp(-(t - attack) / tau);
}

/** Asymmetric bell: rises with width `w1` to 1 at `tp`, falls with width `w2`. */
export function bell(t, tp, w1, w2) {
  const d = (t - tp) / (t < tp ? w1 : w2);
  return Math.exp(-d * d);
}

/** Piecewise-linear envelope through flat [t0, v0, t1, v1, ...] points. */
export function envPts(t, pts) {
  if (t <= pts[0]) return pts[1];
  for (let i = 2; i < pts.length; i += 2) {
    if (t <= pts[i]) {
      const t0 = pts[i - 2], t1 = pts[i];
      return pts[i - 1] + (pts[i + 1] - pts[i - 1]) * (t1 > t0 ? (t - t0) / (t1 - t0) : 1);
    }
  }
  return pts[pts.length - 1];
}

export const smooth01 = (x) => (x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x));

/**
 * A smooth periodic random function of time with `harmonics` sinusoids that all complete an
 * integer number of cycles over `period`. Returns f(t) in about [-1, 1].
 */
export function periodicWobble(rng, period, harmonics = 4, kMin = 1, kMax = 6) {
  const ks = [], ph = [], am = [];
  let norm = 0;
  for (let i = 0; i < harmonics; i++) {
    const k = kMin + Math.floor(rng.next() * (kMax - kMin + 1));
    ks.push(TAU * k / period);
    ph.push(rng.next() * TAU);
    const a = 0.4 + rng.next() * 0.6;
    am.push(a);
    norm += a;
  }
  for (let i = 0; i < harmonics; i++) am[i] /= norm;
  return (t) => {
    let v = 0;
    for (let i = 0; i < ks.length; i++) v += am[i] * Math.sin(ks[i] * t + ph[i]);
    return v;
  };
}

/** Rounds a frequency so it completes an integer number of cycles over `period` seconds. */
export const loopFreq = (f, period) => Math.max(1, Math.round(f * period)) / period;

/** Equal-tempered frequency of a note name like 'D4', 'Bb3', 'F#5' (A4 = 440). */
export function noteFreq(name) {
  const m = /^([A-G])([#b]?)(-?\d)$/.exec(name);
  if (!m) throw new Error(`bad note ${name}`);
  const semis = { C: -9, D: -7, E: -5, F: -4, G: -2, A: 0, B: 2 }[m[1]] + (m[2] === '#' ? 1 : m[2] === 'b' ? -1 : 0);
  return 440 * Math.pow(2, (semis + (Number(m[3]) - 4) * 12) / 12);
}

// ---------------------------------------------------------------------------------------------
// Modal synthesis

/**
 * Strike response of a hardness-limited excitation: a soft low-pass in frequency.
 * `fc` is where a hard or soft mallet stops exciting modes.
 */
export const strikeGain = (f, fc) => 1 / Math.sqrt(1 + Math.pow(f / fc, 4));

/**
 * Adds a bank of exponentially decaying sinusoids (struck modes) into `out` from sample
 * `start`. modes: [{ f, amp, t60, phase? }]. Each uses a decaying rotating phasor, so there is
 * no per-sample trig.
 */
export function addModes(out, sr, start, modes, { attack = 0.0003, gain = 1, hardness = 0 } = {}) {
  const na = Math.max(1, Math.round(attack * sr));
  const end = out.length;
  for (let m = 0; m < modes.length; m++) {
    const md = modes[m];
    if (!(md.f > 0) || md.f >= sr * 0.46 || !md.amp) continue;
    const w = TAU * md.f / sr, c = Math.cos(w), s = Math.sin(w);
    const r = Math.exp(-LN1000 / (md.t60 * sr));
    const ph = md.phase || 0;
    let x = Math.cos(ph), y = Math.sin(ph);
    let a = md.amp * gain * (hardness > 0 ? strikeGain(md.f, hardness) : 1);
    const n = Math.min(end - start, Math.ceil(md.t60 * 1.6 * sr));
    for (let i = 0; i < n; i++) {
      const v = i < na ? a * y * (0.5 - 0.5 * Math.cos(Math.PI * i / na)) : a * y;
      out[start + i] += v;
      const nx = x * c - y * s;
      y = x * s + y * c;
      x = nx;
      a *= r;
    }
  }
}

/**
 * Builds a list of modes from frequency ratios. Each mode can be split into a beating doublet,
 * which is what gives real bronze its shimmer.
 */
export function makeModes(rng, base, ratios, { amps = null, t60 = 1, t60Ref = base, t60Exp = 0.6, ampExp = 0.5,
  jitter = 0.01, doublet = 0, doubletSpread = 0.003, ampRand = 0.5, t60Max = 1e9 } = {}) {
  const out = [];
  for (let i = 0; i < ratios.length; i++) {
    const f = base * ratios[i] * (1 + (rng.next() * 2 - 1) * jitter);
    const a = (amps ? amps[i] : Math.pow(ratios[i] / ratios[0], -ampExp)) * (1 - ampRand + ampRand * rng.next());
    const d = Math.min(t60Max, t60 * Math.pow(t60Ref / f, t60Exp));
    out.push({ f, amp: a, t60: d, phase: rng.next() * TAU });
    if (doublet > 0) {
      const f2 = f * (1 + doubletSpread * (0.4 + rng.next()));
      out.push({ f: f2, amp: a * doublet * (0.6 + 0.4 * rng.next()), t60: d * (0.8 + 0.3 * rng.next()), phase: rng.next() * TAU });
    }
  }
  return out;
}

/** A decaying sine whose pitch glides from f0·ratio down to f0 (drums, thumps, heartbeats). */
export function addThump(out, sr, start, { f0, ratio = 2, glide = 0.03, attack = 0.002, tau = 0.08, amp = 1, dur = 0 }) {
  const n = Math.min(out.length - start, Math.ceil((dur || tau * 8 + attack) * sr));
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const f = f0 * (1 + (ratio - 1) * Math.exp(-t / glide));
    ph += TAU * f / sr;
    out[start + i] += amp * adEnv(t, attack, tau) * Math.sin(ph);
  }
}

/** Filtered noise burst with an attack/decay envelope. */
export function addNoiseBurst(out, sr, start, noise, { attack = 0.001, tau = 0.02, amp = 1, filter = null, dur = 0 }) {
  const n = Math.min(out.length - start, Math.ceil((dur || attack + tau * 7) * sr));
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    let v = noise();
    if (filter) v = filter.process(v);
    out[start + i] += amp * adEnv(t, attack, tau) * v;
  }
}

/** A tiny band-passed click (crackle grain). */
export function addClick(out, sr, start, noise, f, q, amp, tau = 0.0015) {
  const bq = new Biquad().bandpass(sr, f, q);
  addNoiseBurst(out, sr, start, noise, { attack: 0.0002, tau, amp, filter: bq });
}

// ---------------------------------------------------------------------------------------------
// Voice (formant) synthesis

/** Male vowel formants F1..F4 (Hz). */
export const VOWELS = {
  a: [730, 1090, 2440, 3400],
  o: [570, 840, 2410, 3300],
  u: [320, 870, 2240, 3300],
  e: [530, 1840, 2480, 3500],
  i: [290, 2250, 3010, 3600],
  uh: [640, 1190, 2390, 3400],
  ae: [660, 1720, 2410, 3400],
  l: [380, 1250, 2700, 3400],
  m: [260, 1150, 2250, 3300],
};

/** Interpolates formant sets along a [t0, 'a', t1, 'l', ...] path into `out`. */
export function vowelAt(path, t, out) {
  if (t <= path[0]) return copy4(VOWELS[path[1]], out);
  for (let i = 2; i < path.length; i += 2) {
    if (t <= path[i]) {
      const A = VOWELS[path[i - 1]], B = VOWELS[path[i + 1]];
      const u = smooth01((t - path[i - 2]) / Math.max(1e-6, path[i] - path[i - 2]));
      for (let k = 0; k < 4; k++) out[k] = A[k] + (B[k] - A[k]) * u;
      return out;
    }
  }
  return copy4(VOWELS[path[path.length - 1]], out);
}

function copy4(a, out) {
  out[0] = a[0]; out[1] = a[1]; out[2] = a[2]; out[3] = a[3];
  return out;
}

/** Rosenberg glottal flow over one period, phase in [0, 1). */
export function glottal(ph, oq) {
  const tp = oq * 0.66, tn = oq * 0.34;
  if (ph < tp) return 0.5 - 0.5 * Math.cos(Math.PI * ph / tp);
  if (ph < tp + tn) return Math.cos(0.5 * Math.PI * (ph - tp) / tn);
  return 0;
}

/**
 * Adds a formant voice: Rosenberg glottal pulses (differentiated for lip radiation) with
 * jitter, shimmer and roughness, plus aspiration noise, through a cascade of four formant
 * resonators. Time functions are evaluated per sample (f0, amp) or per 32 samples (vowel).
 *
 * p = { dur, f0(t), amp(t), vowel(t, out4) → [F1..F4], bw: [4], breath, jitter, shimmer, rough, oq, tilt, presence (dB),
 *       air (parallel high branch), seed, gain }
 */
export function addVoice(out, sr, start, p) {
  const noise = createNoise(p.seed || 7);
  const n = Math.min(out.length - start, Math.ceil(p.dur * sr));
  const rs = [new Reson(), new Reson(), new Reson(), new Reson()];
  const bw = p.bw || [90, 110, 160, 220];
  const tilt = new OnePole(sr, p.tilt || 2400);
  // The cascade rolls off steeply above F2; a presence shelf restores the F3-F4 "edge".
  const pres = new Biquad().highshelf(sr, 1700, p.presence == null ? 10 : p.presence);
  // Parallel branch for the higher formants (F5+), which a 4-pole cascade cuts too steeply.
  const hiF = new Biquad().bandpass(sr, 3600, 0.7), air = p.air == null ? 0.8 : p.air;
  const asp = new Biquad().highpass(sr, 900, 0.6);
  const fm = [0, 0, 0, 0];
  const oq = p.oq || 0.6, breath = p.breath || 0, jit = p.jitter || 0, shim = p.shimmer || 0, rough = p.rough || 0;
  const gain = p.gain == null ? 1 : p.gain;
  let ph = 0, prev = 0, perJ = 0, perA = 1, odd = false, slowJ = 0;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    if ((i & 31) === 0) {
      const v = p.vowel(t, fm) || fm;
      for (let k = 0; k < 4; k++) rs[k].set(sr, v[k], bw[k]);
    }
    slowJ += (noise() * 0.5 - slowJ) * 0.002;
    const f = p.f0(t) * (1 + perJ + slowJ * jit);
    ph += f / sr;
    if (ph >= 1) {
      ph -= 1;
      perJ = noise() * jit * 0.5;
      odd = !odd;
      perA = 1 + noise() * shim - (odd ? rough * 0.5 : 0);
    }
    const g = glottal(ph, oq) * perA;
    const src = (g - prev) * sr / (f * 4 + 1);
    prev = g;
    const a = asp.process(noise()) * breath * (0.25 + g);
    let v = tilt.process(src) + a;
    const hv = hiF.process(v) * air;
    v = pres.process(rs[3].process(rs[2].process(rs[1].process(rs[0].process(v))))) + hv;
    out[start + i] += v * p.amp(t) * gain;
  }
}

// ---------------------------------------------------------------------------------------------
// Plucked string (Karplus-Strong with an allpass for fine tuning)

export function addPluck(out, sr, start, f, { amp = 1, t60 = 1.5, bright = 0.5, pos = 0.18, seed = 3 } = {}) {
  const noise = createNoise(seed);
  const period = sr / f;
  const N = Math.max(2, Math.floor(period - 0.5));
  const frac = period - 0.5 - N;
  const apC = (1 - frac) / (1 + frac);
  const buf = new Float32Array(N);
  // Excitation: low-passed noise with a pluck-position comb.
  const lp = new OnePole(sr, 800 + bright * 7000);
  const pd = Math.max(1, Math.round(N * pos));
  const tmp = new Float32Array(N);
  for (let i = 0; i < N; i++) tmp[i] = lp.process(noise());
  for (let i = 0; i < N; i++) buf[i] = tmp[i] - (i >= pd ? tmp[i - pd] : 0);
  // Loop loss per period so the note decays to -60 dB in t60.
  const loss = Math.pow(10, -3 / (t60 * f));
  const n = Math.min(out.length - start, Math.ceil(t60 * 1.3 * sr));
  let idx = 0, last = 0, apX = 0, apY = 0;
  const fade = Math.round(0.002 * sr);
  for (let i = 0; i < n; i++) {
    const x = buf[idx];
    const avg = 0.5 * (x + last) * loss;
    last = x;
    const y = apC * avg + apX - apC * apY;
    apX = avg;
    apY = y;
    buf[idx] = y;
    idx = idx + 1 === N ? 0 : idx + 1;
    out[start + i] += x * amp * (i < fade ? i / fade : 1);
  }
}

// ---------------------------------------------------------------------------------------------
// Reverb (Freeverb-style: 8 damped combs into 4 allpasses), mono

export class Reverb {
  constructor(sr, { room = 0.84, damp = 0.3, spread = 0 } = {}) {
    const k = sr / 44100;
    this.combs = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617].map((l) => ({
      buf: new Float32Array(Math.max(8, Math.round((l + spread) * k))), i: 0, store: 0,
    }));
    this.aps = [556, 441, 341, 225].map((l) => ({ buf: new Float32Array(Math.max(4, Math.round((l + spread) * k))), i: 0 }));
    this.fb = room;
    this.damp = damp;
  }

  process(x) {
    const inp = x * 0.03;
    let out = 0;
    const fb = this.fb, d = this.damp;
    for (let j = 0; j < 8; j++) {
      const c = this.combs[j];
      const y = c.buf[c.i];
      c.store = y * (1 - d) + c.store * d;
      c.buf[c.i] = inp + c.store * fb;
      if (++c.i === c.buf.length) c.i = 0;
      out += y;
    }
    for (let j = 0; j < 4; j++) {
      const a = this.aps[j];
      const b = a.buf[a.i];
      a.buf[a.i] = out + b * 0.5;
      if (++a.i === a.buf.length) a.i = 0;
      out = b - out;
    }
    return out;
  }
}

/** Runs `proc(x) → y` over `buf` in place (one-shot sounds). */
export function processLinear(buf, proc) {
  for (let i = 0; i < buf.length; i++) buf[i] = proc(buf[i]);
}

/**
 * Runs a stateful per-sample process `proc(x, i) → y` over a loop buffer circularly, in place:
 * the state is first warmed up on the last `warm` samples, so the output wraps without a seam.
 */
export function processCircular(buf, warm, proc) {
  const n = buf.length;
  for (let i = Math.max(0, n - warm); i < n; i++) proc(buf[i], i);
  for (let i = 0; i < n; i++) buf[i] = proc(buf[i], i);
}

/** Generator form of processCircular that yields every `chunk` samples (time-sliced rendering). */
export function* processCircularGen(buf, warm, proc, chunk = 32768) {
  const n = buf.length;
  for (let i = Math.max(0, n - warm); i < n; i++) {
    proc(buf[i], i);
    if (i % chunk === 0) yield;
  }
  for (let i = 0; i < n; i++) {
    buf[i] = proc(buf[i], i);
    if (i % chunk === chunk - 1) yield;
  }
}

/** Adds `src` into `out` at `offset`, wrapping around the end (loop buffers). */
export function wrapAdd(out, src, offset, gain = 1) {
  const n = out.length;
  let j = ((offset % n) + n) % n;
  for (let i = 0; i < src.length; i++) {
    out[j] += src[i] * gain;
    if (++j === n) j = 0;
  }
}

// ---------------------------------------------------------------------------------------------
// Analysis and finishing

/** Peak, RMS, max 50 ms RMS ("momentary"), and whether there is a NaN/Infinity. */
export function measure(buf, sr) {
  let peak = 0, sum = 0, bad = false;
  const win = Math.max(1, Math.round(0.05 * sr));
  let wsum = 0, wmax = 0;
  for (let i = 0; i < buf.length; i++) {
    const v = buf[i];
    if (!Number.isFinite(v)) {
      bad = true;
      continue;
    }
    const a = Math.abs(v);
    if (a > peak) peak = a;
    const s = v * v;
    sum += s;
    wsum += s;
    if (i >= win) {
      const o = buf[i - win];
      if (Number.isFinite(o)) wsum -= o * o;
    }
    if (i >= win - 1 && wsum > wmax) wmax = wsum;
  }
  if (buf.length < win) wmax = wsum;
  return {
    peak,
    rms: Math.sqrt(sum / Math.max(1, buf.length)),
    momentary: Math.sqrt(Math.max(0, wmax) / Math.min(win, Math.max(1, buf.length))),
    bad,
  };
}

/** Removes DC with a one-pole high-pass at ~15 Hz (circular for loops). */
export function removeDC(buf, sr, loop = false) {
  const a = Math.exp(-TAU * 15 / sr);
  let x1 = 0, y1 = 0;
  const proc = (x) => {
    const y = x - x1 + a * y1;
    x1 = x;
    y1 = y;
    return y;
  };
  if (loop) processCircular(buf, Math.min(buf.length, Math.round(sr * 0.5)), proc);
  else processLinear(buf, proc);
}

/** Scales `buf` in place; replaces any non-finite sample with 0. */
export function scale(buf, g) {
  for (let i = 0; i < buf.length; i++) {
    const v = buf[i] * g;
    buf[i] = Number.isFinite(v) ? v : 0;
  }
}

/**
 * Normalizes to a loudness target (max 50 ms RMS) without exceeding `peak`.
 * Returns the gain applied.
 */
export function normalize(buf, sr, { momentary = 0.25, peak = 0.89 } = {}) {
  const m = measure(buf, sr);
  if (m.peak < 1e-9) return 1;
  const g = Math.min(peak / m.peak, momentary / Math.max(1e-9, m.momentary));
  scale(buf, g);
  return g;
}

/** Short fades at the ends of a one-shot so truncation never clicks. */
export function fadeEnds(buf, sr, fadeIn = 0.0005, fadeOut = 0.02) {
  const ni = Math.min(buf.length, Math.round(fadeIn * sr));
  for (let i = 0; i < ni; i++) buf[i] *= i / ni;
  const no = Math.min(buf.length, Math.round(fadeOut * sr));
  for (let i = 0; i < no; i++) buf[buf.length - 1 - i] *= i / no;
}

/** Soft saturation: tanh(drive·x)/tanh(drive). */
export function saturate(buf, drive) {
  const k = 1 / Math.tanh(drive);
  for (let i = 0; i < buf.length; i++) buf[i] = Math.tanh(buf[i] * drive) * k;
}

// ---------------------------------------------------------------------------------------------
// Additive harmonic tones (reeds, brass, birds)

/**
 * Adds Σ w_k·sin(k·φ) for a time-varying f0, using the Chebyshev recurrence so each sample
 * costs one sin/cos pair. `weights(t, f, w)` fills w[1..K] every 32 samples; harmonics above
 * 0.45·sr are skipped. With `wrap`, writes wrap around the end of `out` (loop buffers).
 *
 * p = { dur, f0(t), amp(t), weights(t, f, w), maxK = 40, wrap = false, gain = 1 }
 */
export function addHarmonic(out, sr, start, p) {
  const len = out.length;
  const n = p.wrap ? Math.ceil(p.dur * sr) : Math.min(len - start, Math.ceil(p.dur * sr));
  const maxK = p.maxK || 40;
  const w = new Float64Array(maxK + 1);
  const gain = p.gain == null ? 1 : p.gain;
  let ph = 0, K = 1;
  let j = p.wrap ? ((start % len) + len) % len : start;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const f = p.f0(t);
    if ((i & 31) === 0) {
      K = Math.max(1, Math.min(maxK, Math.floor(sr * 0.45 / (f * 1.03))));
      p.weights(t, f, w);
    }
    ph += TAU * f / sr;
    if (ph > TAU) ph -= TAU;
    const s1 = Math.sin(ph), c2 = 2 * Math.cos(ph);
    let sPrev = 0, s = s1, acc = w[1] * s1;
    for (let k = 2; k <= K; k++) {
      const sk = c2 * s - sPrev;
      sPrev = s;
      s = sk;
      acc += w[k] * sk;
    }
    out[j] += acc * p.amp(t) * gain;
    if (++j === len) {
      if (!p.wrap) break;
      j = 0;
    }
  }
}
