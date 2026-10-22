/**
 * PURE music and ambience loops. Every loop is periodic by construction: oscillators complete
 * a whole number of cycles per loop, modulators are periodic, events wrap around the end, and
 * every filter or reverb runs circularly (warmed up on the loop's tail). So the loops repeat
 * without a seam or a crossfade.
 *
 * Music: 'title' and 'battle' are a Dorian aulos theme over a low sung paean drone (no drum);
 * 'lastStand' is a darker drone with a sparse lament. Ambience layers: 'wind', 'sea', 'din'
 * (the distant army), 'cicadas'.
 */
import { createRng } from '../core/math.js';
import {
  TAU, Biquad, OnePole, Reson, createNoise, createPink, glottal, VOWELS, periodicWobble, loopFreq, noteFreq,
  addHarmonic, addModes, addVoice, vowelAt, Reverb, processCircular, processCircularGen, wrapAdd, removeDC, normalize,
  smooth01, adEnv,
} from './dsp.js';

/** name → { kind: 'music'|'ambience', sr, dur (s), loud (target max 50 ms RMS), gen } */
export const LOOPS = {};

/** Music stem levels (exported so tests can solo a stem). */
export const MIX = { choir: 0.2, aulos: 2 };

/** Samples in loop `name`. */
export const loopLength = (name) => Math.round(LOOPS[name].dur * LOOPS[name].sr);

/** Renders loop `name` into `out` (zeroed, loopLength samples). A generator that yields often. */
export function* renderLoopGen(name, out) {
  const L = LOOPS[name];
  const rng = createRng(hash(name));
  yield* L.gen(out, L.sr, L.dur, rng, createNoise(hash(name) * 13 + 5));
  removeDC(out, L.sr, true);
  normalize(out, L.sr, { momentary: L.loud, peak: 0.89 });
}

/** Synchronous convenience (tests). */
export function renderLoop(name) {
  const out = new Float32Array(loopLength(name));
  const it = renderLoopGen(name, out);
  while (!it.next().done);
  return out;
}

function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return (h >>> 0) % 1000003 + 1;
}

// ---------------------------------------------------------------------------------------------
// Instruments

const CENT = Math.LN2 / 1200; // linearized cents → frequency ratio (keeps loop phase exact)
const CR = 32; // control-rate block (loop lengths are multiples of it)

/** Differentiated Rosenberg pulse table (one period), for the choir. */
function glottalTable(oq, size = 2048) {
  const t = new Float32Array(size + 1);
  let prev = glottal(0, oq), mx = 0;
  for (let i = 1; i <= size; i++) {
    const g = glottal(i / size, oq);
    t[i] = g - prev;
    prev = g;
    mx = Math.max(mx, Math.abs(t[i]));
  }
  t[0] = t[size];
  for (let i = 0; i <= size; i++) t[i] /= mx;
  return t;
}

/**
 * A low male choir sustaining a drone (the paean). Voices are summed glottal sources with
 * their own detune, vibrato, drift and breathing swells; one shared formant filter morphs
 * through `vowels` `vowelCycles` times per loop.
 * voices: [{ note, amp, cents }]
 */
function* choir(out, sr, Lfull, rng, noise, { voices, vowels, vowelCycles = 2, bw = [110, 130, 180, 250], swell = 0.35, oq = 0.6, tilt = 1800, presence = 6, breath = 0.04, pulse = null, gain = 1, tile = 1 }) {
  // With tile > 1 the drone repeats `tile` times per loop (it is rendered once, shorter).
  const n = out.length / tile, L = Lfull / tile;
  const src = new Float32Array(n);
  const table = glottalTable(oq);
  const TS = table.length - 1;
  for (const v of voices) {
    const f = loopFreq(noteFreq(v.note) * Math.pow(2, (v.cents || 0) / 1200), L);
    const vr = loopFreq(4.4 + rng.next() * 1.2, L), vp = rng.next() * TAU, vd = 7 + rng.next() * 7;
    const drift = periodicWobble(rng, L, 3, 1, 5);
    const sw = periodicWobble(rng, L, 3, 1, 4);
    // Modulators run at a 32-sample control rate. Sampled periodic sinusoids still sum to zero
    // over the loop, so the phase returns exactly to its start.
    const ampAt = (t) => v.amp * (1 - swell + swell * (0.5 + 0.5 * sw(t))) * (pulse ? pulse(t) : 1);
    let ph = rng.next();
    let aPrev = ampAt((n - CR) / sr);
    for (let b = 0; b < n; b += CR) {
      const t = b / sr;
      const cents = vd * Math.sin(TAU * vr * t + vp) * (0.7 + 0.3 * sw(t)) + 5 * drift(t);
      const inc = f * (1 + CENT * cents) / sr;
      const a = ampAt(t);
      const end = Math.min(n, b + CR);
      for (let i = b; i < end; i++) {
        ph += inc;
        ph -= Math.floor(ph);
        const x = ph * TS, xi = x | 0;
        src[i] += (table[xi] + (table[xi + 1] - table[xi]) * (x - xi)) * (aPrev + (a - aPrev) * (i - b) / CR);
      }
      aPrev = a;
    }
    yield;
  }
  // Breath noise rides on the same formants.
  const pink = createPink(noise);
  for (let i = 0; i < n; i++) src[i] += pink() * breath;
  // Shared vocal tract: tilt, then a formant cascade morphing through the vowel cycle.
  const rs = [new Reson(), new Reson(), new Reson(), new Reson()];
  const tl = new OnePole(sr, tilt);
  const pres = new Biquad().highshelf(sr, 1600, presence);
  const fm = [0, 0, 0, 0];
  const nv = vowels.length;
  const per = L / vowelCycles;
  yield* processCircularGen(src, Math.round(sr * 0.5), (x, i) => {
    if ((i & 63) === 0) {
      const u = ((i / sr) % per) / per * nv;
      const k = Math.floor(u), fr = smooth01(u - k);
      const A = VOWELS[vowels[k % nv]], B = VOWELS[vowels[(k + 1) % nv]];
      for (let j = 0; j < 4; j++) {
        fm[j] = A[j] + (B[j] - A[j]) * fr;
        rs[j].set(sr, fm[j], bw[j]);
      }
    }
    return pres.process(rs[3].process(rs[2].process(rs[1].process(rs[0].process(tl.process(x))))));
  });
  for (let k = 0; k < tile; k++) for (let i = 0; i < n; i++) out[k * n + i] += src[i] * gain;
}

/**
 * One aulos note (double reed): odd harmonics strong, evens weak, brighter when louder, a
 * scoop up into the pitch, delayed vibrato, and breath noise with a chiff on tongued attacks.
 * Wraps around the loop end.
 */
function aulosNote(out, sr, t0, dur, f, noise, { amp = 1, legato = false, scoop = 0.7, vib = 16, bright = 1, breath = 0.05, seed = 1 } = {}) {
  const rel = 0.11;
  const total = dur + rel;
  const att = legato ? 0.03 : 0.055;
  const scoopR = 1 - Math.pow(2, -(legato ? scoop * 0.5 : scoop) / 12);
  const vr = 5.1 + ((seed * 0.37) % 1) * 0.6, vp = seed;
  // The spectral shape is cached per note: odd/even balance and the reed's resonance.
  const KMAX = Math.min(24, Math.floor(6500 / f));
  const shape = new Float64Array(KMAX + 1), lnk = new Float64Array(KMAX + 1);
  for (let k = 1; k <= KMAX; k++) {
    shape[k] = (k & 1 ? 1 : 0.3) * (1 + 0.9 * Math.exp(-Math.pow(Math.log2(k * f / 1500), 2) / 0.5));
    lnk[k] = Math.log(k);
  }
  const env = (t) => {
    const a = t < att ? smooth01(t / att) : 1;
    const sus = 1 + 0.08 * Math.sin(Math.PI * Math.min(1, t / Math.max(0.2, dur))) - 0.06 * Math.min(1, t / 2);
    const r = t > dur ? Math.exp(-(t - dur) / 0.035) : 1;
    return a * sus * r;
  };
  addHarmonic(out, sr, Math.round(t0 * sr), {
    dur: total, maxK: KMAX, wrap: true, gain: amp * 0.22,
    f0: (t) => f * (1 - scoopR * Math.exp(-t / 0.05)) * (1 + CENT * vib * Math.sin(TAU * vr * t + vp) * smooth01((t - 0.18) / 0.4)),
    amp: env,
    weights: (t, ff, w) => {
      const b = (0.45 + 0.55 * Math.min(1, env(t))) * bright;
      const slope = 1.75 - 0.75 * b;
      for (let k = 1; k <= KMAX; k++) w[k] = Math.exp(-slope * lnk[k]) * shape[k];
    },
  });
  // Breath through the reed, and the tongued "chiff".
  const len = out.length;
  const bp = new Biquad().bandpass(sr, 2300, 0.9);
  const ns = Math.ceil(total * sr);
  let j = ((Math.round(t0 * sr) % len) + len) % len;
  for (let i = 0; i < ns; i++) {
    const t = i / sr;
    const ch = legato ? 0 : 0.5 * Math.exp(-t / 0.02) * Math.min(1, t / 0.004);
    out[j] += bp.process(noise()) * amp * (breath * env(t) + ch * breath * 3);
    if (++j === len) j = 0;
  }
}

/**
 * Plays a melody of [note | 'r', beats] (optionally [note, beats, 'g'] for a grace note from
 * the step above) on the aulos, plus a sustained drone pipe under each phrase.
 */
function* aulosPart(out, sr, bpm, melody, noise, { amp = 1, drone = null, droneAmp = 0.25, vib = 16, bright = 1, transpose = 0 } = {}) {
  const beat = 60 / bpm;
  let t = 0, phraseStart = -1, seed = 1;
  const spans = [];
  for (let i = 0; i < melody.length; i++) {
    const [note, beats, orn] = melody[i];
    const d = beats * beat;
    if (note === 'r') {
      if (phraseStart >= 0) spans.push([phraseStart, t]);
      phraseStart = -1;
    } else {
      const prev = melody[i - 1];
      const legato = !!prev && prev[0] !== 'r';
      if (phraseStart < 0) phraseStart = t;
      const f = noteFreq(note) * Math.pow(2, transpose / 12);
      if (orn === 'g') aulosNote(out, sr, t - 0.075, 0.06, f * Math.pow(2, 2 / 12), noise, { amp: amp * 0.8, legato: true, scoop: 0, vib: 0, bright, seed: seed++ });
      aulosNote(out, sr, t, d - (legato ? 0 : 0.02), f, noise, { amp, legato: legato || orn === 'g', vib, bright, seed: seed++ });
      yield;
    }
    t += d;
  }
  if (phraseStart >= 0) spans.push([phraseStart, t]);
  if (drone) {
    for (const [a, b] of spans) {
      aulosNote(out, sr, a - 0.05, b - a + 0.1, noteFreq(drone) * Math.pow(2, transpose / 12), noise, { amp: amp * droneAmp, scoop: 0.3, vib: 6, bright: 0.6, breath: 0.03, seed: seed++ });
      yield;
    }
  }
}

/** Circular reverb send and a gentle top roll-off: out = dry + wet. */
function* space(out, sr, { room = 0.86, damp = 0.35, wet = 0.3, lp = 7000, warm = 4 } = {}) {
  const rv = new Reverb(sr, { room, damp });
  const lpf = new Biquad().lowpass(sr, lp, 0.6);
  yield* processCircularGen(out, Math.round(sr * warm), (x) => lpf.process(x + rv.process(x) * wet));
}

const M = (s) => s.trim().split(/\s+/).map((tok) => {
  const [n, b, o] = tok.split(':');
  return [n, Number(b), o];
});

// ---------------------------------------------------------------------------------------------
// Music

// Title: a slow, lyrical Dorian theme (72 BPM, 40 beats).
const TITLE_BPM = 72;
const TITLE = M(`
  r:2 A4:1.5:g G4:0.5 A4:1 C5:1 B4:0.5 A4:0.5 G4:1 A4:3
  r:1 D5:1.5:g E5:0.5 D5:1 C5:1 B4:1 A4:2
  G4:1 F4:0.5 E4:0.5 F4:1 G4:1 A4:3
  r:1 C5:1 A4:1 G4:1 F4:0.5 E4:0.5 D4:4:g r:5`);

// Battle: the marching aulos of the embateria in anapaests (short-short-long), 96 BPM, 48 beats.
const BATTLE_BPM = 96;
const BATTLE = M(`
  D4:0.5 D4:0.5 A4:1 D4:0.5 D4:0.5 A4:1 C5:0.5 B4:0.5 A4:1 G4:1 A4:1 r:1
  A4:0.5 A4:0.5 D5:1 D5:0.5 C5:0.5 B4:1 A4:0.5 G4:0.5 F4:1 E4:1 D4:1 r:1
  A4:0.5 C5:0.5 D5:2:g E5:0.5 D5:0.5 C5:1 B4:1 A4:3 r:2
  D5:0.5 C5:0.5 A4:1 C5:0.5 B4:0.5 G4:1 A4:0.5 G4:0.5 F4:1 G4:0.5 F4:0.5 E4:1 D4:3 r:8`);

// Last stand: a lament falling through the Phrygian tetrachord, 54 BPM, 24 beats.
const LAST_BPM = 54;
const LAST = M(`r:6 A3:2:g G3:1 F3:1 Eb3:2 D3:4 r:8`);

const beatsOf = (mel) => mel.reduce((s, m) => s + m[1], 0);

LOOPS.title = {
  kind: 'music', sr: 24000, dur: beatsOf(TITLE) * 60 / TITLE_BPM, loud: 0.17,
  *gen(out, sr, L, rng, noise) {
    yield* choir(out, sr, L, rng, noise, {
      voices: [{ note: 'D3', amp: 1, cents: -6 }, { note: 'D3', amp: 0.85, cents: 7 }, { note: 'D3', amp: 0.7, cents: 1 },
        { note: 'A2', amp: 0.8, cents: 4 }, { note: 'A2', amp: 0.6, cents: -9 }, { note: 'D2', amp: 0.65, cents: 0 }],
      vowels: ['o', 'a'], vowelCycles: 1, gain: 0.9, tile: 2,
    });
    const mel = new Float32Array(out.length);
    yield* aulosPart(mel, sr, TITLE_BPM, TITLE, noise, { amp: 1, drone: 'D4', droneAmp: 0.22 });
    for (let i = 0; i < out.length; i++) out[i] = out[i] * MIX.choir + mel[i] * MIX.aulos;
    yield* space(out, sr, { wet: 0.34 });
  },
};

LOOPS.battle = {
  kind: 'music', sr: 24000, dur: beatsOf(BATTLE) * 60 / BATTLE_BPM, loud: 0.17,
  *gen(out, sr, L, rng, noise) {
    const bar = 4 * 60 / BATTLE_BPM;
    yield* choir(out, sr, L, rng, noise, {
      voices: [{ note: 'D3', amp: 1, cents: -3 }, { note: 'D3', amp: 0.9, cents: 6 }, { note: 'A2', amp: 0.9, cents: 2 },
        { note: 'A2', amp: 0.8, cents: -6 }, { note: 'D2', amp: 0.7, cents: 0 }, { note: 'A3', amp: 0.35, cents: 3 }],
      vowels: ['a', 'o'], vowelCycles: 1, gain: 0.95, tile: 2,
      // The singers lean into each bar of the march.
      pulse: (t) => 0.78 + 0.22 * Math.pow(0.5 + 0.5 * Math.cos(TAU * (t % bar) / bar), 3),
    });
    const mel = new Float32Array(out.length);
    yield* aulosPart(mel, sr, BATTLE_BPM, BATTLE, noise, { amp: 1, drone: 'A3', droneAmp: 0.24, vib: 14, bright: 1.1 });
    for (let i = 0; i < out.length; i++) out[i] = out[i] * MIX.choir + mel[i] * MIX.aulos;
    yield* space(out, sr, { wet: 0.28 });
  },
};

LOOPS.lastStand = {
  kind: 'music', sr: 24000, dur: beatsOf(LAST) * 60 / LAST_BPM, loud: 0.15,
  *gen(out, sr, L, rng, noise) {
    yield* choir(out, sr, L, rng, noise, {
      voices: [{ note: 'D2', amp: 1, cents: -5 }, { note: 'D2', amp: 0.6, cents: 10 }, { note: 'A1', amp: 0.8, cents: 0 },
        { note: 'D3', amp: 0.45, cents: 2 }, { note: 'A2', amp: 0.6, cents: -4 }],
      vowels: ['u', 'o', 'u', 'm'], vowelCycles: 1, swell: 0.5, tilt: 1200, presence: 3, gain: 1,
    });
    // A minor second that swells in and out of the drone.
    yield* choir(out, sr, L, rng, noise, {
      voices: [{ note: 'Eb3', amp: 0.5, cents: 0 }], vowels: ['u', 'o'], vowelCycles: 1, swell: 0.9, tilt: 1200, presence: 2, gain: 0.35,
      pulse: (t) => Math.pow(0.5 - 0.5 * Math.cos(TAU * t / L), 2),
    });
    // A deep, slow rumble under everything.
    const lp = new Biquad().lowpass(sr, 90, 0.7);
    const sw = periodicWobble(rng, L, 2, 1, 2);
    const rum = new Float32Array(out.length);
    for (let i = 0; i < rum.length; i++) rum[i] = noise();
    processCircular(rum, sr * 2, (x, i) => lp.process(x) * (0.6 + 0.4 * sw(i / sr)));
    const mel = new Float32Array(out.length);
    yield* aulosPart(mel, sr, LAST_BPM, LAST, noise, { amp: 0.75, vib: 22, bright: 0.75 });
    for (let i = 0; i < out.length; i++) out[i] = out[i] * MIX.choir * 2.5 + mel[i] * MIX.aulos * 1.2 + rum[i] * 1.6;
    yield* space(out, sr, { wet: 0.5, room: 0.9, damp: 0.45, lp: 5000, warm: 5 });
  },
};

// ---------------------------------------------------------------------------------------------
// Ambience

LOOPS.wind = {
  kind: 'ambience', sr: 16000, dur: 16, loud: 0.2,
  *gen(out, sr, L, rng, noise) {
    // Gusting wind through the pass: pink noise in a wandering band, a faint whistle off the
    // rocks in the strongest gusts, and a low rumble.
    for (let i = 0; i < out.length; i++) out[i] = noise();
    const gust = periodicWobble(rng, L, 4, 1, 5);
    const g2 = periodicWobble(rng, L, 3, 2, 7);
    let b0 = 0, b1 = 0, b2 = 0, br = 0;
    const bp = new Biquad(), wh = new Biquad(), lp = new Biquad().lowpass(sr, 140, 0.7);
    yield* processCircularGen(out, sr * 2, (w, i) => {
      const t = i / sr;
      const g = 0.5 + 0.5 * gust(t);
      if ((i & 31) === 0) {
        bp.bandpass(sr, 280 + 520 * g, 0.55);
        wh.bandpass(sr, 640 + 380 * (0.5 + 0.5 * g2(t)), 16);
      }
      b0 = 0.99765 * b0 + w * 0.099046;
      b1 = 0.963 * b1 + w * 0.2965164;
      b2 = 0.57 * b2 + w * 1.0526913;
      const pink = (b0 + b1 + b2 + w * 0.1848) * 0.2;
      br = br * 0.996 + w * 0.06;
      return bp.process(pink) * (0.2 + 0.8 * Math.pow(g, 1.5)) * 2.2 + wh.process(w) * 0.16 * g * g * g + lp.process(br) * (0.5 + 0.5 * g) * 0.55;
    });
  },
};

LOOPS.sea = {
  kind: 'ambience', sr: 22050, dur: 24, loud: 0.2,
  *gen(out, sr, L, rng, noise) {
    const n = out.length;
    // The open water: a low, slowly heaving surge.
    const heave = periodicWobble(rng, L, 3, 3, 5);
    const lp = new Biquad().lowpass(sr, 380, 0.7);
    let br = 0;
    for (let i = 0; i < n; i++) out[i] = noise();
    processCircular(out, sr * 2, (w, i) => {
      br = br * 0.995 + w * 0.08;
      return lp.process(br) * (0.65 + 0.35 * heave(i / sr)) * 0.55;
    });
    yield;
    // Waves breaking on the rocks below the pass, then dragging the shingle back.
    const waves = 4;
    for (let k = 0; k < waves; k++) {
      const t0 = (k + (rng.next() - 0.5) * 0.35) * L / waves;
      const big = 0.7 + rng.next() * 0.5;
      const len = Math.round(6 * sr);
      const ev = new Float32Array(len);
      const lpf = new Biquad(), crash = new Biquad().bandpass(sr, 1100, 0.6), hp = new Biquad().highpass(sr, 1600, 0.7);
      const tc = 0.9 + rng.next() * 0.3;
      for (let i = 0; i < len; i++) {
        const t = i / sr;
        if ((i & 31) === 0) lpf.lowpass(sr, 280 + 2400 * smooth01(t / tc) * Math.exp(-Math.max(0, t - tc) / 1.6), 0.7);
        const x = noise();
        const roar = lpf.process(x) * (t < tc ? Math.pow(t / tc, 2) : Math.exp(-(t - tc) / 1.3));
        const cr = crash.process(x) * adEnv(t - tc, 0.04, 0.28);
        const foam = hp.process(x) * adEnv(t - tc - 0.1, 0.25, 1.5);
        ev[i] = big * (roar * 1.1 + cr * 0.9 + foam * 0.45);
      }
      // Shingle: stones rattling in the backwash.
      for (let c = 0; c < 260; c++) {
        const tt = tc + 0.6 + Math.pow(rng.next(), 0.8) * 3.6;
        const s = Math.round(tt * sr);
        const f = 1800 + rng.next() * 4200;
        const bq = new Biquad().bandpass(sr, f, 3);
        const a = big * 0.18 * (0.3 + rng.next()) * Math.exp(-Math.abs(tt - tc - 1.8) / 1.1);
        for (let i = 0; i < 0.006 * sr && s + i < len; i++) ev[s + i] += a * bq.process(noise()) * Math.exp(-i / (0.0012 * sr)) * 4;
      }
      wrapAdd(out, ev, Math.round(t0 * sr));
      yield;
    }
  },
};

LOOPS.din = {
  kind: 'ambience', sr: 16000, dur: 16, loud: 0.2,
  *gen(out, sr, L, rng, noise) {
    const n = out.length;
    // Tens of thousands of men a few hundred metres away: babble, feet, shouts, metal.
    for (let i = 0; i < n; i++) out[i] = noise();
    const f1 = periodicWobble(rng, L, 3, 3, 9), f2 = periodicWobble(rng, L, 3, 3, 9), syl = periodicWobble(rng, L, 4, 30, 70);
    const pink = { b0: 0, b1: 0, b2: 0 };
    const r1 = new Biquad(), r2 = new Biquad(), r3 = new Biquad().bandpass(sr, 2400, 2), lp = new Biquad().lowpass(sr, 160, 0.7);
    let br = 0;
    yield* processCircularGen(out, sr * 2, (w, i) => {
      const t = i / sr;
      if ((i & 31) === 0) {
        r1.bandpass(sr, 600 + 160 * f1(t), 2.2);
        r2.bandpass(sr, 1350 + 350 * f2(t), 2.6);
      }
      pink.b0 = 0.99765 * pink.b0 + w * 0.099046;
      pink.b1 = 0.963 * pink.b1 + w * 0.2965164;
      pink.b2 = 0.57 * pink.b2 + w * 1.0526913;
      const p = (pink.b0 + pink.b1 + pink.b2 + w * 0.1848) * 0.2;
      br = br * 0.995 + w * 0.07;
      const babble = (r1.process(p) * 1.4 + r2.process(p) * 0.8 + r3.process(p) * 0.25) * (0.85 + 0.15 * syl(t));
      return babble * 2.2 + lp.process(br) * 0.7;
    });
    // Shouts carried on the wind.
    const tmp = new Float32Array(n);
    const fm = [0, 0, 0, 0];
    const vs = ['a', 'o', 'e', 'ae', 'uh'];
    for (let s = 0; s < 14; s++) {
      const dur = 0.35 + rng.next() * 0.6;
      const f = 140 + rng.next() * 130;
      const path = [0, rng.pick(vs), dur * 0.6, rng.pick(vs)];
      const ev = new Float32Array(Math.ceil(dur * sr) + 8);
      addVoice(ev, sr, 0, {
        dur, seed: 900 + s, breath: 0.3, jitter: 0.05, shimmer: 0.15, rough: 0.4, oq: 0.45, tilt: 2500, presence: 4, air: 0.3,
        f0: (t) => f * (1 + 0.12 * Math.sin(Math.PI * t / dur)),
        amp: (t) => Math.sin(Math.PI * Math.min(1, t / dur)),
        vowel: (t) => vowelAt(path, t, fm),
      });
      wrapAdd(tmp, ev, Math.round(rng.next() * n), (0.4 + rng.next() * 0.6) * 0.035);
    }
    yield;
    // Bronze and iron knocking together somewhere in the host.
    for (let c = 0; c < 90; c++) {
      const ev = new Float32Array(Math.round(0.25 * sr));
      const f = 900 + rng.next() * 2600;
      addModes(ev, sr, 0, [{ f, amp: 1, t60: 0.06 + rng.next() * 0.12, phase: 0 }, { f: f * (1.5 + rng.next()), amp: 0.5, t60: 0.05, phase: 0 }], { attack: 0.0005 });
      wrapAdd(tmp, ev, Math.round(rng.next() * n), 0.04 * (0.2 + rng.next()));
    }
    yield;
    const lpS = new Biquad().lowpass(sr, 1800, 0.7);
    processCircular(tmp, sr, (x) => lpS.process(x));
    for (let i = 0; i < n; i++) out[i] += tmp[i];
    // Distance: a soft top and a wash of reverb from the valley walls.
    yield* space(out, sr, { room: 0.85, damp: 0.5, wet: 0.45, lp: 2400, warm: 3 });
  },
};

LOOPS.cicadas = {
  kind: 'ambience', sr: 22050, dur: 12, loud: 0.16,
  *gen(out, sr, L, rng, noise) {
    const n = out.length;
    // Cicadas in the scrub of the cliff: each sings phrases of tymbal pulses on a narrow band.
    for (let c = 0; c < 8; c++) {
      const fc = 4200 + rng.next() * 2600;
      const rate = 90 + rng.next() * 90;
      const near = 0.25 + rng.next() * 0.75;
      const echeme = rng.chance(0.5) ? 5 + rng.next() * 4 : 0;
      const phrase = 2 + rng.next() * 4;
      const start = rng.next() * L;
      const len = Math.round((phrase + 1.2) * sr);
      const ev = new Float32Array(len);
      const bp = new Biquad().bandpass(sr, fc, 3 + rng.next() * 3);
      const bp2 = new Biquad().bandpass(sr, fc * 1.9, 4);
      let ph = 0;
      for (let i = 0; i < len; i++) {
        const t = i / sr;
        ph += rate / sr;
        ph -= Math.floor(ph);
        const pulse = Math.exp(-ph * 7);
        const gate = echeme ? 0.5 + 0.5 * Math.tanh(4 * Math.sin(TAU * echeme * t)) : 1;
        const env = smooth01(t / 0.7) * smooth01((phrase + 1.2 - t) / 0.5);
        const x = noise() * pulse;
        ev[i] = (bp.process(x) + 0.3 * bp2.process(x)) * gate * env * near * 3;
      }
      wrapAdd(out, ev, Math.round(start * sr));
      yield;
    }
    // The chorus of the whole hillside behind them.
    const bp = new Biquad().bandpass(sr, 5200, 1.4);
    const sw = periodicWobble(rng, L, 2, 1, 3);
    const am = loopFreq(118, L);
    const bed = new Float32Array(n);
    for (let i = 0; i < n; i++) bed[i] = noise();
    processCircular(bed, sr, (x, i) => {
      const t = i / sr;
      return bp.process(x) * (0.55 + 0.45 * Math.sin(TAU * am * t)) * (0.6 + 0.4 * sw(t)) * 0.5;
    });
    for (let i = 0; i < n; i++) out[i] += bed[i];
  },
};

/** Loop names by kind. */
export const MUSIC_NAMES = ['title', 'battle', 'lastStand'];
export const AMBIENCE_LAYERS = ['wind', 'sea', 'din', 'cicadas'];


/**
 * A stereo impulse response for the convolution reverb of the open pass: a pre-delay, a few
 * early reflections off the cliff (stronger on the left), then a decaying diffuse tail whose
 * highs die away faster than its lows. Energy-normalized per channel.
 */
export function renderImpulse(sr, seconds = 1.3, seed = 5) {
  const n = Math.round(seconds * sr);
  const chans = [new Float32Array(n), new Float32Array(n)];
  const rng = createRng(seed);
  const refl = [[0.011, 0.5, 0.25], [0.019, 0.42, 0.2], [0.031, 0.3, 0.12], [0.047, 0.22, 0.18], [0.063, 0.15, 0.1]];
  for (let c = 0; c < 2; c++) {
    const out = chans[c];
    const noise = createNoise(seed * 17 + c * 101);
    const lp = new OnePole(sr, 9000);
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      if ((i & 63) === 0) lp.set(sr, 9000 * Math.exp(-t / 0.35) + 900);
      const env = smooth01((t - 0.008) / 0.025) * Math.exp(-6.9 * t / seconds);
      out[i] = lp.process(noise()) * env;
    }
    for (const [t, l, r] of refl) {
      const j = Math.round((t + rng.next() * 0.002) * sr);
      if (j < n) out[j] += (c === 0 ? l : r) * (rng.next() < 0.5 ? -1 : 1);
    }
    let e = 0;
    for (let i = 0; i < n; i++) e += out[i] * out[i];
    const g = 1 / Math.sqrt(e || 1);
    for (let i = 0; i < n; i++) out[i] *= g;
  }
  return chans;
}
