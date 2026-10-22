/**
 * PURE sound-effect recipes. Each one synthesizes a mono one-shot into a zeroed Float32Array.
 * Bronze uses inharmonic modal partials with beating doublets, flesh is a low thump plus
 * filtered noise, whooshes are band-passed noise swept with the swing speed, and voices are a
 * formant synthesizer. renderSfx() finishes every sound the same way: DC removal, end fades,
 * loudness normalization under a peak ceiling.
 */
import { createRng } from '../core/math.js';
import {
  TAU, Biquad, OnePole, createNoise, adEnv, bell, envPts, vowelAt, addModes, makeModes, addThump,
  addNoiseBurst, addClick, addVoice, addPluck, addHarmonic, Reverb, processLinear, removeDC, normalize, fadeEnds,
  measure, noteFreq,
} from './dsp.js';

/**
 * name → { dur (s), variants, loud (target max 50 ms RMS), prio (voice stealing), cap (max
 * simultaneous instances), maxRate (render sample-rate cap, Hz), render(out, sr, rng, noise,
 * variant) }. render may be a generator (it yields between expensive layers so rendering can
 * be time-sliced). Dark sounds with little above 10 kHz render at 32 kHz to save memory.
 */
export const SFX = {};
const def = (name, spec) => {
  SFX[name] = { variants: 1, loud: 0.26, prio: 1, cap: 4, maxRate: 48000, ...spec };
};
const DARK = 32000;

// ---------------------------------------------------------------------------------------------
// Shared layers

/** Band-passed noise swept with a bell-shaped speed profile (sword and spear whooshes). */
function whooshLayer(out, sr, noise, { start = 0, tp, w1, w2, fLo, fHi, q = 1.3, body = 0.5, bodyF = 260, edge = 0.25, amp = 1, flutter = 0 }) {
  const bp = new Biquad(), bp2 = new Biquad(), lp = new Biquad().lowpass(sr, bodyF, 0.8), top = new OnePole(sr, 4500);
  const n0 = Math.round(start * sr);
  const n = out.length - n0;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const e = bell(t, tp, w1, w2);
    if ((i & 15) === 0) {
      const fc = fLo + (fHi - fLo) * e;
      bp.bandpass(sr, fc, q);
      bp2.bandpass(sr, fc * 2.1, 6);
    }
    const x = top.process(noise()) * 1.6;
    const fl = flutter ? 1 - flutter * (0.5 + 0.5 * Math.sin(TAU * 31 * t)) : 1;
    out[n0 + i] += amp * Math.pow(e, 1.6) * fl * (bp.process(x) * 3 + edge * 3 * bp2.process(x) + body * lp.process(x));
  }
}

/** Wet squelch: a few blips of a resonant band sweeping down. */
function squelch(out, sr, rng, noise, t0, len, amp) {
  const bq = new Biquad();
  const n0 = Math.round(t0 * sr), n = Math.min(out.length - n0, Math.round(len * sr));
  const blips = [];
  for (let b = 0, nb = 3 + Math.floor(rng.next() * 3); b < nb; b++) blips.push(rng.next() * len * 0.7, 0.4 + rng.next() * 0.6);
  const f0 = 900 + rng.next() * 400;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    if ((i & 15) === 0) bq.bandpass(sr, f0 * (1 - 0.45 * t / len), 5);
    let e = 0;
    for (let b = 0; b < blips.length; b += 2) e += adEnv(t - blips[b], 0.003, 0.014) * blips[b + 1];
    out[n0 + i] += amp * e * bq.process(noise()) * 4;
  }
}

/** Dense crackle grains (wicker, splintering wood, gravel, bone). */
function crackle(out, sr, rng, noise, { t0 = 0, len = 0.1, count = 20, fLo = 1500, fHi = 4000, q = 2, amp = 0.3, decay = 3, tau = 0.0015 }) {
  for (let c = 0; c < count; c++) {
    const u = Math.pow(rng.next(), decay > 1 ? 1.6 : 1);
    const t = t0 + u * len;
    const a = amp * (0.35 + 0.65 * rng.next()) * Math.exp(-u * decay);
    addClick(out, sr, Math.round(t * sr), noise, fLo + (fHi - fLo) * rng.next(), q, a * 4, tau * (0.6 + 0.8 * rng.next()));
  }
}

/** PolyBLEP correction for a band-limited sawtooth (removes the aliasing of the naive ramp). */
function polyBlep(ph, dt) {
  if (ph < dt) {
    const x = ph / dt;
    return x + x - x * x - 1;
  }
  if (ph > 1 - dt) {
    const x = (ph - 1) / dt;
    return x * x + x + x + 1;
  }
  return 0;
}

/** Buzzing thrum of a vibrating wooden shaft (spears and arrows), with a slow quiver. */
function shaftBuzz(out, sr, start, { f = 90, tau = 0.25, amp = 0.3, quiver = 11, bright = 900 }) {
  const lp = new OnePole(sr, bright), lp2 = new OnePole(sr, bright * 1.5);
  const n0 = Math.round(start * sr), n = out.length - n0;
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const dt = f * (1 + 0.012 * Math.sin(TAU * quiver * t)) / sr;
    ph += dt;
    ph -= Math.floor(ph);
    const saw = 2 * ph - 1 - polyBlep(ph, dt);
    const e = adEnv(t, 0.004, tau) * (0.8 + 0.2 * Math.sin(TAU * quiver * t));
    out[n0 + i] += amp * e * lp2.process(lp.process(saw));
  }
}

// Mode ratios: a domed bronze shield facing (dense, inharmonic) and a free-free bar (blade).
const ASPIS = [1, 1.47, 1.73, 2.15, 2.33, 2.65, 2.98, 3.31, 3.91, 4.15, 4.62, 5.12, 5.73, 6.1, 6.57, 7.27, 7.86, 8.6, 9.42, 10.3, 11.2, 12.4];
const BAR = [1, 2.756, 5.404, 8.933, 13.34];
// Ideal circular membrane (drums).
const MEMBRANE = [1, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.501, 3.6, 4.06];

/** Bronze shield ring: the aspis modes, the striking blade's modes and the contact transient. */
function bronze(out, sr, rng, noise, { start = 0, base = 280, t60 = 1.5, hardness = 5000, gain = 1, blade = 0.6, bladeBase = 720, bladeT60 = 2, tick = 0.8, core = 0.6, ampExp = 0.3 }) {
  const n0 = Math.round(start * sr);
  const shield = makeModes(rng, base, ASPIS, { t60, t60Exp: 0.55, ampExp, doublet: 0.85, doubletSpread: 0.004, jitter: 0.015, ampRand: 0.65, t60Max: t60 * 1.2 });
  addModes(out, sr, n0, shield, { hardness, gain });
  if (blade > 0) {
    const bm = makeModes(rng, bladeBase, BAR, { amps: [0.55, 0.45, 0.3, 0.18, 0.08], t60: bladeT60, t60Exp: 0.35, doublet: 0.7, doubletSpread: 0.0015, ampRand: 0.3 });
    addModes(out, sr, n0, bm, { hardness: hardness * 1.4, gain: gain * blade });
  }
  if (tick > 0) addNoiseBurst(out, sr, n0, noise, { attack: 0.0002, tau: 0.0025, amp: tick * 1.5, filter: new Biquad().highpass(sr, 2500, 0.7) });
  if (core > 0) {
    // The aspis is a wooden bowl faced with bronze: a short woody body under the ring.
    addThump(out, sr, n0, { f0: 120, ratio: 1.5, glide: 0.01, attack: 0.001, tau: 0.035, amp: core * 0.6 });
    addNoiseBurst(out, sr, n0, noise, { attack: 0.0005, tau: 0.02, amp: core * 2.2, filter: new Biquad().bandpass(sr, 300, 1.2) });
  }
}

/** Low body thump with a mid "smack", the core of every flesh impact. */
function body(out, sr, rng, noise, { start = 0, f0 = 55, ratio = 2.6, tau = 0.06, amp = 1, knock = 380, knockAmp = 2.2, cut = 0.6, cutF = 2400, slap = 0.35 }) {
  const n0 = Math.round(start * sr);
  addThump(out, sr, n0, { f0, ratio, glide: 0.022, attack: 0.0015, tau, amp: amp * 0.7 });
  // A second, higher knock so the hit reads on small phone speakers too.
  addNoiseBurst(out, sr, n0, noise, { attack: 0.0008, tau: 0.022, amp: knockAmp * amp * 0.9, filter: new Biquad().bandpass(sr, knock * 2.3, 1.4) });
  addNoiseBurst(out, sr, n0, noise, { attack: 0.001, tau: 0.032, amp: knockAmp * amp, filter: new Biquad().bandpass(sr, knock, 1.1) });
  addNoiseBurst(out, sr, n0, noise, { attack: 0.0005, tau: 0.018, amp: cut * amp, filter: new Biquad().lowpass(sr, cutF, 0.7) });
  if (slap > 0) addNoiseBurst(out, sr, n0, noise, { attack: 0.0002, tau: 0.004, amp: slap * amp, filter: new Biquad().highpass(sr, 2500, 0.7) });
}

/** Voice helper: a formant voice with a vowel path, f0 and amplitude breakpoints. */
function voice(out, sr, start, { dur, f0, fPts, aPts, path, breath = 0.25, jitter = 0.04, shimmer = 0.1, rough = 0.2, oq = 0.55, tilt = 2600, presence = 10, air = 0.8, bw, gain = 1, seed = 1, vib = 0, vibRate = 5.5 }) {
  const fm = [0, 0, 0, 0];
  addVoice(out, sr, Math.round(start * sr), {
    dur, seed, breath, jitter, shimmer, rough, oq, tilt, presence, air, bw, gain,
    f0: (t) => f0 * envPts(t, fPts) * (vib ? 1 + vib * Math.sin(TAU * vibRate * t) * Math.min(1, t * 3) : 1),
    amp: (t) => envPts(t, aPts),
    vowel: (t) => vowelAt(path, t, fm),
  });
}

/** Lyre (kithara) string. */
const lyre = (out, sr, t, note, amp, t60 = 1.6, seed = 1, bright = 0.55) =>
  addPluck(out, sr, Math.round(t * sr), typeof note === 'number' ? note : noteFreq(note), { amp, t60, bright, seed });

// ---------------------------------------------------------------------------------------------
// Weapons

def('whoosh', {
  dur: 0.42, variants: 3, loud: 0.2, prio: 2, cap: 3,
  render(out, sr, rng, noise) {
    const k = 0.88 + rng.next() * 0.24;
    whooshLayer(out, sr, noise, { tp: 0.15, w1: 0.065, w2: 0.085, fLo: 340 * k, fHi: 1450 * k, q: 1.4, body: 0.7, bodyF: 280, edge: 0.22 });
  },
});

def('whooshHeavy', {
  dur: 0.62, variants: 2, loud: 0.22, prio: 2, cap: 3,
  render(out, sr, rng, noise) {
    const k = 0.9 + rng.next() * 0.2;
    whooshLayer(out, sr, noise, { tp: 0.23, w1: 0.1, w2: 0.13, fLo: 180 * k, fHi: 850 * k, q: 1.1, body: 1.4, bodyF: 200, edge: 0.15 });
    whooshLayer(out, sr, noise, { tp: 0.26, w1: 0.05, w2: 0.08, fLo: 500 * k, fHi: 1500 * k, q: 2, body: 0, edge: 0.1, amp: 0.3 });
  },
});

def('clang', {
  dur: 1.9, variants: 2, loud: 0.3, prio: 3, cap: 3,
  render(out, sr, rng, noise) {
    const k = 0.92 + rng.next() * 0.16;
    bronze(out, sr, rng, noise, { base: 300 * k, t60: 1.6, hardness: 8000, blade: 1.2, bladeBase: (720 + rng.next() * 90) * k, bladeT60: 2.0, core: 0.4, ampExp: 0.12 });
  },
});

def('parry', {
  dur: 2.1, variants: 2, loud: 0.3, prio: 3, cap: 2,
  render(out, sr, rng, noise) {
    const k = 0.95 + rng.next() * 0.1;
    // The edge meets the rim: a bright ringing blade over a lighter shield ring, and a scrape.
    bronze(out, sr, rng, noise, { base: 330 * k, t60: 1.3, hardness: 7000, gain: 0.55, blade: 2.0, bladeBase: 960 * k, bladeT60: 2.6, tick: 1, core: 0.25 });
    const bp = new Biquad();
    const n = Math.round(0.09 * sr);
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      if ((i & 15) === 0) bp.bandpass(sr, 2600 + 3000 * t / 0.09, 3);
      out[i] += 1.4 * adEnv(t, 0.004, 0.03) * bp.process(noise());
    }
  },
});

def('guardBreak', {
  maxRate: DARK, dur: 1.6, loud: 0.32, prio: 4, cap: 1,
  render(out, sr, rng, noise) {
    // An axe blow drives the shield in: a heavy low hit, splintering wood, dented bronze.
    addThump(out, sr, 0, { f0: 42, ratio: 2.2, glide: 0.03, attack: 0.002, tau: 0.16, amp: 1.1 });
    addThump(out, sr, 0, { f0: 95, ratio: 1.6, glide: 0.02, attack: 0.001, tau: 0.05, amp: 0.6 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0008, tau: 0.05, amp: 2.4, filter: new Biquad().bandpass(sr, 240, 1) });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0002, tau: 0.004, amp: 1.2, filter: new Biquad().highpass(sr, 1200, 0.7) });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0005, tau: 0.03, amp: 2.4, filter: new Biquad().bandpass(sr, 850, 1.2) });
    crackle(out, sr, rng, noise, { t0: 0.004, len: 0.22, count: 70, fLo: 700, fHi: 3600, q: 1.6, amp: 0.9, decay: 2.5, tau: 0.002 });
    const dented = makeModes(rng, 175, ASPIS.slice(0, 14), { t60: 0.7, t60Exp: 0.5, ampExp: 0.25, doublet: 1, doubletSpread: 0.02, jitter: 0.04, ampRand: 0.7 });
    addModes(out, sr, 0, dented, { hardness: 2400, gain: 1.1 });
    addModes(out, sr, 0, [{ f: 1130, amp: 0.12, t60: 0.7 }, { f: 2890, amp: 0.08, t60: 0.45 }, { f: 4410, amp: 0.05, t60: 0.3 }], {});
  },
});

def('flesh', {
  maxRate: DARK, dur: 0.45, variants: 3, loud: 0.3, prio: 3, cap: 4,
  render(out, sr, rng, noise) {
    const k = 0.9 + rng.next() * 0.2;
    body(out, sr, rng, noise, { f0: 56 * k, tau: 0.06, knock: 360 * k, knockAmp: 4, cut: 1.4 });
    squelch(out, sr, rng, noise, 0.008, 0.16, 0.8);
  },
});

def('fleshHeavy', {
  maxRate: DARK, dur: 0.65, variants: 2, loud: 0.32, prio: 3, cap: 4,
  render(out, sr, rng, noise) {
    const k = 0.9 + rng.next() * 0.2;
    body(out, sr, rng, noise, { f0: 46 * k, ratio: 2.8, tau: 0.09, knock: 300 * k, knockAmp: 4.5, cut: 1.6, cutF: 2000 });
    crackle(out, sr, rng, noise, { t0: 0.003, len: 0.06, count: 12, fLo: 1800, fHi: 4200, q: 2.5, amp: 0.6, decay: 2 });
    squelch(out, sr, rng, noise, 0.01, 0.26, 1.0);
    addNoiseBurst(out, sr, Math.round(0.03 * sr), noise, { attack: 0.01, tau: 0.08, amp: 0.8, filter: new Biquad().lowpass(sr, 500, 0.8) });
  },
});

def('wicker', {
  dur: 0.45, variants: 2, loud: 0.24, prio: 2, cap: 4,
  render(out, sr, rng, noise) {
    // A blade bites a woven willow shield: a hollow knock and a dry crackle.
    const k = 0.9 + rng.next() * 0.2;
    addModes(out, sr, 0, makeModes(rng, 290 * k, [1, 2.3, 3.9, 5.6], { t60: 0.16, t60Exp: 0.4, ampExp: 0.4, jitter: 0.05 }), { hardness: 3000, gain: 0.7 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0006, tau: 0.03, amp: 1.6, filter: new Biquad().bandpass(sr, 850 * k, 1.3) });
    crackle(out, sr, rng, noise, { t0: 0.001, len: 0.14, count: 42, fLo: 1300, fHi: 4200, q: 1.8, amp: 0.45, decay: 3 });
    addModes(out, sr, 0, [{ f: 3150 * k, amp: 0.06, t60: 0.12 }, { f: 4720 * k, amp: 0.04, t60: 0.08 }], {});
  },
});

def('wood', {
  maxRate: DARK, dur: 0.45, variants: 2, loud: 0.24, prio: 2, cap: 3,
  render(out, sr, rng, noise) {
    // Edge on a hardwood haft: a short, hard, hollow knock.
    const k = 0.9 + rng.next() * 0.2;
    addModes(out, sr, 0, makeModes(rng, 185 * k, [1, 2.45, 4.6, 7.2, 10.3], { amps: [1, 0.7, 0.45, 0.3, 0.18], t60: 0.26, t60Exp: 0.6, jitter: 0.03 }), { hardness: 3200 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0003, tau: 0.008, amp: 0.9, filter: new Biquad().bandpass(sr, 1600, 0.9) });
    addModes(out, sr, 0, [{ f: 2950 * k, amp: 0.07, t60: 0.15 }, { f: 4480 * k, amp: 0.04, t60: 0.09 }], {});
  },
});

def('bash', {
  maxRate: DARK, dur: 0.65, variants: 2, loud: 0.32, prio: 3, cap: 2,
  render(out, sr, rng, noise) {
    // The bronze-faced aspis slams into a body: a deep boom, a short dull bronze knock, the body.
    addThump(out, sr, 0, { f0: 50, ratio: 2, glide: 0.025, attack: 0.002, tau: 0.1, amp: 1.1 });
    body(out, sr, rng, noise, { f0: 70, tau: 0.05, knock: 420, knockAmp: 4, cut: 1, amp: 0.8, slap: 0.6 });
    const knock = makeModes(rng, 230, ASPIS.slice(0, 10), { t60: 0.32, t60Exp: 0.4, ampExp: 0.4, doublet: 0.6, doubletSpread: 0.006 });
    addModes(out, sr, 0, knock, { hardness: 2200, gain: 0.9 });
  },
});

def('shieldUp', {
  dur: 0.36, variants: 2, loud: 0.13, prio: 2, cap: 2,
  render(out, sr, rng, noise) {
    // The aspis swings up on the forearm: air and cloth, a creak of the leather grip, the
    // rim settling against the shoulder.
    whooshLayer(out, sr, noise, { tp: 0.07, w1: 0.04, w2: 0.05, fLo: 500, fHi: 1300, q: 0.9, body: 0.8, bodyF: 220, edge: 0, amp: 0.8 });
    const bp = new Biquad().bandpass(sr, 900 + rng.next() * 300, 3);
    const n = Math.round(0.05 * sr), n0 = Math.round(0.035 * sr);
    let ph = 0;
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      ph += (40 + 25 * t / 0.05) / sr;
      const pulse = Math.exp(-((ph % 1) * 9));
      out[n0 + i] += 0.9 * bp.process(noise() * pulse) * Math.sin(Math.PI * i / n);
    }
    const t1 = 0.105 + rng.next() * 0.02;
    addThump(out, sr, Math.round(t1 * sr), { f0: 140, ratio: 1.4, glide: 0.01, attack: 0.001, tau: 0.025, amp: 0.35 });
    addModes(out, sr, Math.round(t1 * sr), makeModes(rng, 520, ASPIS.slice(0, 8), { t60: 0.35, t60Exp: 0.4, doublet: 0.6 }), { hardness: 2500, gain: 0.05 });
  },
});

def('spearThrow', {
  dur: 0.75, loud: 0.26, prio: 4, cap: 1,
  render(out, sr, rng, noise) {
    whooshLayer(out, sr, noise, { tp: 0.2, w1: 0.12, w2: 0.12, fLo: 160, fHi: 850, q: 1, body: 1.5, bodyF: 220, edge: 0.12 });
    whooshLayer(out, sr, noise, { tp: 0.22, w1: 0.05, w2: 0.1, fLo: 600, fHi: 1800, q: 2.2, body: 0, edge: 0.15, amp: 0.3 });
    // The thrower's grunt of effort.
    voice(out, sr, 0.1, { dur: 0.4, f0: 118, fPts: [0, 1.1, 0.08, 1.15, 0.35, 0.85], aPts: [0, 0, 0.03, 1, 0.12, 0.7, 0.36, 0], path: [0, 'uh', 0.1, 'a', 0.3, 'uh'], breath: 0.5, rough: 0.3, gain: 0.05, seed: 11 });
  },
});

def('spearWhistle', {
  dur: 1.5, loud: 0.14, prio: 3, cap: 1,
  render(out, sr, rng, noise) {
    // A spear in flight, receding: an aeolian whistle falling in pitch, with a shaft flutter.
    const bp = new Biquad(), bp2 = new Biquad();
    const n = out.length;
    const fl = 15 + rng.next() * 4;
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      if ((i & 15) === 0) {
        const fc = 1550 * (1 - 0.3 * t / 1.5);
        bp.bandpass(sr, fc, 10);
        bp2.bandpass(sr, fc * 0.7, 1.6);
      }
      const e = adEnv(t, 0.07, 0.42) * (1 - 0.4 * (0.5 + 0.5 * Math.sin(TAU * fl * t)));
      const x = noise();
      out[i] += e * (bp.process(x) * 5 + bp2.process(x) * 1.2);
    }
  },
});

def('spearHit', {
  maxRate: DARK, dur: 0.9, variants: 2, loud: 0.32, prio: 4, cap: 2,
  render(out, sr, rng, noise) {
    body(out, sr, rng, noise, { f0: 48, ratio: 3, tau: 0.08, knock: 330, knockAmp: 4.5, cut: 1.8, cutF: 3000, slap: 0.8 });
    crackle(out, sr, rng, noise, { t0: 0.002, len: 0.05, count: 14, fLo: 1500, fHi: 4000, q: 2.4, amp: 0.7, decay: 2 });
    squelch(out, sr, rng, noise, 0.01, 0.2, 0.9);
    shaftBuzz(out, sr, 0.01, { f: 88 + rng.next() * 12, tau: 0.2, amp: 0.3, quiver: 10 + rng.next() * 3, bright: 900 });
  },
});

def('spearGround', {
  maxRate: DARK, dur: 0.95, variants: 2, loud: 0.27, prio: 3, cap: 2,
  render(out, sr, rng, noise) {
    addThump(out, sr, 0, { f0: 70, ratio: 1.8, glide: 0.015, attack: 0.001, tau: 0.045, amp: 0.6 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0005, tau: 0.03, amp: 4, filter: new Biquad().bandpass(sr, 380, 1) });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0003, tau: 0.008, amp: 1.4, filter: new Biquad().bandpass(sr, 2000, 0.8) });
    // Dirt and pebbles settling.
    crackle(out, sr, rng, noise, { t0: 0.02, len: 0.35, count: 30, fLo: 500, fHi: 2200, q: 1.2, amp: 0.45, decay: 3, tau: 0.003 });
    shaftBuzz(out, sr, 0.004, { f: 76 + rng.next() * 12, tau: 0.3, amp: 0.45, quiver: 12 + rng.next() * 3, bright: 800 });
  },
});

def('splash', {
  maxRate: DARK, dur: 1.6, variants: 2, loud: 0.28, prio: 3, cap: 2,
  render(out, sr, rng, noise) {
    addThump(out, sr, 0, { f0: 55, ratio: 1.8, glide: 0.03, attack: 0.004, tau: 0.08, amp: 0.7 });
    // The sheet of water: a broad band sweeping up then settling.
    const bp = new Biquad(), hp = new Biquad().bandpass(sr, 3200, 0.9);
    const n = out.length;
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      if ((i & 15) === 0) bp.bandpass(sr, 400 + 1500 * Math.min(1, t / 0.05) * Math.exp(-t / 0.35) + 300, 0.8);
      const x = noise();
      out[i] += adEnv(t, 0.012, 0.16) * bp.process(x) * 2.6 + adEnv(t - 0.03, 0.03, 0.3) * hp.process(x) * 0.35;
    }
    // Droplets falling back: short rising sine chirps.
    for (let d = 0; d < 70; d++) {
      const t0 = 0.05 + Math.pow(rng.next(), 1.5) * 1.2;
      const f0 = 400 + rng.next() * 1600;
      const a = 0.06 * (0.3 + rng.next()) * Math.exp(-t0 * 1.6);
      const len = Math.round((0.008 + rng.next() * 0.02) * sr);
      let ph = 0;
      const s0 = Math.round(t0 * sr);
      for (let i = 0; i < len && s0 + i < n; i++) {
        const u = i / len;
        ph += TAU * f0 * (1 + 0.8 * u) / sr;
        out[s0 + i] += a * Math.sin(ph) * Math.sin(Math.PI * u) * Math.exp(-u * 2);
      }
    }
    // A gulp of air.
    let ph = 0;
    const s0 = Math.round(0.14 * sr), len = Math.round(0.07 * sr);
    for (let i = 0; i < len; i++) {
      const u = i / len;
      ph += TAU * (170 + 200 * u) / sr;
      out[s0 + i] += 0.2 * Math.sin(ph) * Math.sin(Math.PI * u);
    }
  },
});

// ---------------------------------------------------------------------------------------------
// Arrows

def('volley', {
  dur: 2.7, loud: 0.26, prio: 4, cap: 1,
  *render(out, sr, rng, noise) {
    const n = out.length;
    // A thousand bowstrings released far away: a soft, low wash of thumps.
    const lp = new OnePole(sr, 700);
    const wash = new Float32Array(Math.round(0.6 * sr));
    for (let g = 0; g < 90; g++) {
      const s = Math.round(rng.next() * 0.35 * sr);
      addThump(wash, sr, s, { f0: 95 + rng.next() * 80, ratio: 1.3, glide: 0.01, attack: 0.002, tau: 0.03, amp: 0.05 });
      addNoiseBurst(wash, sr, s, noise, { attack: 0.002, tau: 0.02, amp: 0.15, filter: new Biquad().bandpass(sr, 400 + rng.next() * 500, 1) });
    }
    for (let i = 0; i < wash.length; i++) out[i] += lp.process(wash[i]) * 1.2;
    yield;
    // The swarm: a rising hiss and many fletching whistles that peak as the arrows arrive.
    const tImp = 2.05;
    const env = (t) => (t < tImp ? 0.04 + 0.96 * Math.pow(Math.max(0, (t - 0.25) / (tImp - 0.25)), 2.2) : Math.exp(-(t - tImp) / 0.12));
    const hiss = new Biquad();
    const W = 16, wb = [], wf = [], wa = [], wr = [], wp = [];
    for (let k = 0; k < W; k++) {
      wb.push(new Biquad());
      wf.push(1100 + rng.next() * 2500);
      wa.push(0.3 + rng.next() * 0.7);
      wr.push(18 + rng.next() * 25);
      wp.push(rng.next() * TAU);
    }
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      const e = env(t);
      if ((i & 31) === 0) {
        const u = Math.min(1, t / tImp);
        hiss.bandpass(sr, 700 + 1900 * u, 0.7);
        for (let k = 0; k < W; k++) wb[k].bandpass(sr, wf[k] * (0.85 + 0.25 * u), 14);
      }
      const x = noise();
      let v = hiss.process(x) * 1.4;
      for (let k = 0; k < W; k++) v += wb[k].process(x) * wa[k] * (0.6 + 0.4 * Math.sin(wr[k] * t * TAU + wp[k])) * 1.6;
      out[i] += e * v;
      if ((i & 32767) === 32767) yield;
    }
  },
});

def('arrowThud', {
  dur: 0.32, variants: 3, loud: 0.22, prio: 1, cap: 6,
  render(out, sr, rng, noise) {
    const k = 0.85 + rng.next() * 0.3;
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0002, tau: 0.0018, amp: 1, filter: new Biquad().highpass(sr, 1500, 0.7) });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0004, tau: 0.012, amp: 2.2, filter: new Biquad().bandpass(sr, 700 * k, 1.3) });
    addThump(out, sr, 0, { f0: 120 * k, ratio: 1.6, glide: 0.008, attack: 0.001, tau: 0.02, amp: 0.4 });
    shaftBuzz(out, sr, 0.002, { f: 190 * k, tau: 0.055, amp: 0.35, quiver: 38, bright: 1400 });
  },
});

def('arrowShield', {
  dur: 0.8, variants: 2, loud: 0.26, prio: 3, cap: 4,
  render(out, sr, rng, noise) {
    // An arrowhead ticks off the bronze facing and sticks in the wood beneath.
    const k = 0.92 + rng.next() * 0.16;
    bronze(out, sr, rng, noise, { base: 310 * k, t60: 0.75, hardness: 8000, gain: 0.5, blade: 0, tick: 1.2, core: 0.5 });
    addModes(out, sr, 0, [{ f: 2550 * k, amp: 0.25, t60: 0.08 }, { f: 4100 * k, amp: 0.18, t60: 0.06 }, { f: 6300 * k, amp: 0.1, t60: 0.04 }], {});
    shaftBuzz(out, sr, 0.003, { f: 200 * k, tau: 0.06, amp: 0.3, quiver: 36, bright: 1500 });
  },
});

// ---------------------------------------------------------------------------------------------
// Voices

def('hurt', {
  maxRate: DARK, dur: 0.6, variants: 3, loud: 0.28, prio: 4, cap: 1,
  render(out, sr, rng, noise, v) {
    const f = 150 + rng.next() * 25;
    body(out, sr, rng, noise, { f0: 60, tau: 0.05, knock: 400, knockAmp: 1.6, cut: 0.4, amp: 0.55, slap: 0.3 });
    const paths = [[0, 'uh', 0.08, 'a', 0.3, 'uh'], [0, 'a', 0.15, 'a', 0.35, 'o'], [0, 'ae', 0.1, 'uh', 0.3, 'u']];
    voice(out, sr, 0.012, {
      dur: 0.52, f0: f, fPts: [0, 1.08, 0.04, 1.2, 0.2, 1.0, 0.45, 0.72], aPts: [0, 0, 0.015, 1, 0.16, 0.8, 0.42, 0.15, 0.5, 0],
      path: paths[v % 3], breath: 0.35, jitter: 0.05, shimmer: 0.14, rough: 0.35, oq: 0.5, tilt: 2400, gain: 0.07, seed: 31 + v,
    });
  },
});

def('death', {
  maxRate: DARK, dur: 1.4, variants: 2, loud: 0.26, prio: 2, cap: 3,
  render(out, sr, rng, noise, v) {
    const f = 125 + rng.next() * 45;
    const paths = [[0, 'a', 0.35, 'a', 0.75, 'o', 1.1, 'u'], [0, 'uh', 0.2, 'a', 0.6, 'uh', 1.0, 'm'], [0, 'ae', 0.3, 'a', 0.8, 'o', 1.1, 'u']];
    voice(out, sr, 0, {
      dur: 1.25, f0: f, fPts: [0, 1.4, 0.07, 1.55, 0.4, 1.2, 0.85, 0.85, 1.2, 0.68],
      aPts: [0, 0, 0.03, 1, 0.3, 0.85, 0.75, 0.5, 1.05, 0.22, 1.24, 0],
      path: paths[v % 3], breath: 0.3, jitter: 0.06, shimmer: 0.18, rough: 0.45, oq: 0.55, tilt: 2300, gain: 0.06, seed: 41 + v, vib: 0.02, vibRate: 6,
    });
    // The body hits the ground, kit rattling.
    const tf = 0.5 + rng.next() * 0.15;
    body(out, sr, rng, noise, { start: tf, f0: 62, tau: 0.07, knock: 280, knockAmp: 1.8, cut: 0.5, amp: 0.7, slap: 0.4 });
    for (let c = 0; c < 4; c++) {
      addModes(out, sr, Math.round((tf + 0.01 + rng.next() * 0.12) * sr),
        makeModes(rng, 1500 + rng.next() * 1500, [1, 1.7, 2.6], { t60: 0.12, ampRand: 0.5 }), { hardness: 5000, gain: 0.05 });
    }
  },
});

def('grunt', {
  maxRate: DARK, dur: 0.5, variants: 3, loud: 0.26, prio: 3, cap: 2,
  render(out, sr, rng, noise, v) {
    // Effort before a blow: an aspirated onset, then a short voiced "hah".
    const f = 128 + rng.next() * 30;
    addNoiseBurst(out, sr, 0, noise, { attack: 0.02, tau: 0.025, amp: 0.25, filter: new Biquad().bandpass(sr, 1300, 0.7) });
    const paths = [[0, 'uh', 0.08, 'a', 0.3, 'uh'], [0, 'uh', 0.1, 'ae', 0.3, 'uh'], [0, 'm', 0.05, 'a', 0.28, 'o']];
    voice(out, sr, 0.04, {
      dur: 0.44, f0: f, fPts: [0, 1.0, 0.06, 1.18, 0.2, 1.05, 0.42, 0.8], aPts: [0, 0, 0.025, 1, 0.12, 0.85, 0.32, 0.2, 0.42, 0],
      path: paths[v % 3], breath: 0.3, jitter: 0.06, shimmer: 0.12, rough: 0.4, oq: 0.48, tilt: 2900, presence: 12, air: 1.4, gain: 0.07, seed: 51 + v,
    });
  },
});

def('scream', {
  maxRate: DARK, dur: 2.0, loud: 0.24, prio: 3, cap: 2,
  render(out, sr, rng) {
    // A man going over the edge: a raw scream sliding down and away.
    const tmp = new Float32Array(out.length);
    voice(tmp, sr, 0, {
      dur: 1.95, f0: 360 + rng.next() * 40, fPts: [0, 1.0, 0.12, 1.12, 0.5, 1.0, 1.3, 0.78, 1.9, 0.62],
      aPts: [0, 0, 0.04, 1, 0.6, 0.9, 1.4, 0.45, 1.94, 0], path: [0, 'a', 0.6, 'ae', 1.2, 'a', 1.8, 'o'],
      bw: [130, 150, 200, 260], breath: 0.35, jitter: 0.07, shimmer: 0.2, rough: 0.55, oq: 0.42, tilt: 3800, presence: 14, air: 1.8, gain: 0.07, seed: 61, vib: 0.03, vibRate: 6.5,
    });
    const lp = new Biquad();
    for (let i = 0; i < tmp.length; i++) {
      if ((i & 31) === 0) lp.lowpass(sr, 6500 * Math.exp(-i / sr / 0.9) + 1200, 0.7);
      out[i] += lp.process(tmp[i]);
    }
  },
});

def('warcry', {
  maxRate: DARK, dur: 2.9, loud: 0.3, prio: 4, cap: 1,
  *render(out, sr, rng) {
    // "Alalai!": a dozen hoplites shouting together, each with his own pitch and timing.
    const N = 12;
    for (let k = 0; k < N; k++) {
      const f = 150 + rng.next() * 75;
      const t0 = rng.next() * 0.12;
      const s = 0.88 + rng.next() * 0.24;
      const T = (x) => x * s;
      voice(out, sr, t0, {
        dur: T(2.3), f0: f,
        fPts: [0, 0.92, T(0.18), 1.0, T(0.32), 1.02, T(0.5), 1.08, T(0.66), 1.14, T(1.1), 1.22, T(1.7), 1.16, T(2.2), 0.95],
        aPts: [0, 0, T(0.04), 0.9, T(0.22), 0.85, T(0.27), 0.45, T(0.34), 0.95, T(0.5), 0.85, T(0.56), 0.5, T(0.64), 1, T(1.5), 0.9, T(2.0), 0.35, T(2.28), 0],
        path: [T(0.02), 'a', T(0.22), 'a', T(0.28), 'l', T(0.34), 'a', T(0.52), 'a', T(0.58), 'l', T(0.66), 'a', T(0.95), 'ae', T(1.35), 'i', T(2.2), 'e'],
        bw: [120, 140, 190, 250], breath: 0.3, jitter: 0.05, shimmer: 0.15, rough: 0.35 + rng.next() * 0.2, oq: 0.45,
        tilt: 3600, presence: 14, air: 1.8, gain: 0.04, seed: 71 + k, vib: 0.012, vibRate: 5 + rng.next() * 1.5,
      });
      yield;
    }
    // The pass between cliff and sea answers with a short slap of reverb.
    const rv = new Reverb(sr, { room: 0.78, damp: 0.4 });
    const pre = Math.round(0.03 * sr);
    const dly = new Float32Array(pre);
    let di = 0;
    processLinear(out, (x) => {
      const d = dly[di];
      dly[di] = x;
      di = (di + 1) % pre;
      return x + rv.process(d) * 0.28;
    });
  },
});

// ---------------------------------------------------------------------------------------------
// The Persian host

def('horn', {
  maxRate: DARK, dur: 4.4, loud: 0.22, prio: 3, cap: 2,
  *render(out, sr, rng) {
    // A long karnay-like horn: a buzzing brass tone that brightens as it swells, two notes a
    // fifth apart, falling off at the end as the breath runs out. Heard across the field.
    const dry = new Float32Array(out.length);
    const note = (t0, dur, f, fall) => {
      addHarmonic(dry, sr, Math.round(t0 * sr), {
        dur, maxK: 36,
        f0: (t) => f * (1 - 0.06 * Math.exp(-t / 0.06)) * (1 + 0.004 * Math.sin(TAU * 4.6 * t) * Math.min(1, t)) * (fall && t > dur - 0.35 ? 1 - 0.1 * Math.pow((t - dur + 0.35) / 0.35, 2) : 1),
        amp: (t) => Math.min(1, t / 0.28) * (t > dur - 0.3 ? Math.max(0, (dur - t) / 0.3) : 1) * (0.9 + 0.1 * Math.sin(TAU * 0.7 * t)),
        weights: (t, ff, w) => {
          const a = Math.min(1, t / 0.28);
          const slope = 1.9 - 0.9 * a;
          for (let k = 1; k < w.length; k++) {
            const fk = k * ff;
            const bellRes = 1 + 1.4 * Math.exp(-Math.pow(Math.log2(fk / 950), 2) / 0.6);
            w[k] = Math.pow(k, -slope) * bellRes * (fk > 5000 ? 0 : 1);
          }
        },
        gain: 0.25,
      });
    };
    const f1 = 108 + rng.next() * 8;
    note(0.02, 1.55, f1, false);
    yield;
    note(1.5, 2.1, f1 * 1.5, true);
    yield;
    // Distance: soften the top and add the valley's reverb.
    const lp = new Biquad().lowpass(sr, 3200, 0.7);
    const rv = new Reverb(sr, { room: 0.88, damp: 0.45 });
    for (let i = 0; i < out.length; i++) {
      const x = lp.process(dry[i]);
      out[i] += x * 0.8 + rv.process(x) * 0.5;
      if ((i & 65535) === 65535) yield;
    }
  },
});

def('drum', {
  maxRate: DARK, dur: 1.3, variants: 2, loud: 0.26, prio: 1, cap: 16,
  render(out, sr, rng, noise) {
    // A large Persian war drum: membrane modes with a tension glide and a skin slap.
    const base = 74 * (0.94 + rng.next() * 0.12);
    const modes = makeModes(rng, base, MEMBRANE, { amps: [1, 0.7, 0.5, 0.45, 0.35, 0.3, 0.25, 0.2, 0.18, 0.14], t60: 0.95, t60Exp: 0.7, jitter: 0.01, ampRand: 0.3 });
    const n = out.length;
    for (const m of modes) {
      let ph = m.phase, a = m.amp * (1 / Math.sqrt(1 + Math.pow(m.f / 900, 4)));
      const r = Math.exp(-6.9078 / (m.t60 * sr));
      for (let i = 0; i < n; i++) {
        const t = i / sr;
        ph += TAU * m.f * (1 + 0.13 * Math.exp(-t / 0.045)) / sr;
        out[i] += a * Math.sin(ph) * Math.min(1, i / (0.0015 * sr));
        a *= r;
      }
    }
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0005, tau: 0.016, amp: 4, filter: new Biquad().bandpass(sr, 1000, 0.8) });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.001, tau: 0.06, amp: 3.5, filter: new Biquad().bandpass(sr, 260, 1.5) });
    addModes(out, sr, 0, [{ f: 340, amp: 0.25, t60: 0.18 }, { f: 520, amp: 0.15, t60: 0.12 }], { attack: 0.001 });
  },
});

// ---------------------------------------------------------------------------------------------
// Player feedback and UI

def('comboSting', {
  dur: 2.0, loud: 0.24, prio: 4, cap: 2,
  render(out, sr, rng, noise) {
    // A kithara strummed upward through the Dorian chord, over a soft bronze shimmer.
    const notes = ['D3', 'A3', 'D4', 'F4', 'A4', 'D5'];
    for (let i = 0; i < notes.length; i++) lyre(out, sr, 0.005 + i * 0.026, notes[i], 0.5 * (1 - i * 0.07), 1.8 - i * 0.12, 81 + i, 0.45 + i * 0.03);
    addModes(out, sr, 0, makeModes(rng, 587.3, [1, 2.0, 2.76, 3.0, 4.07, 5.4], { amps: [0.5, 0.35, 0.2, 0.25, 0.12, 0.08], t60: 1.6, t60Exp: 0.4, doublet: 0.7, doubletSpread: 0.002 }), { hardness: 4000, gain: 0.14, attack: 0.004 });
    addThump(out, sr, 0, { f0: 73.4, ratio: 1.3, glide: 0.02, attack: 0.004, tau: 0.18, amp: 0.25 });
  },
});

def('heartbeat', {
  maxRate: DARK, dur: 0.85, loud: 0.26, prio: 4, cap: 2,
  render(out, sr, rng, noise) {
    // Lub-dub, heard from inside the helmet: deep, muffled, with enough low-mid body for a
    // phone speaker.
    const beat = (t0, a) => {
      addThump(out, sr, Math.round(t0 * sr), { f0: 46, ratio: 1.7, glide: 0.02, attack: 0.006, tau: 0.07, amp: a * 0.6 });
      addNoiseBurst(out, sr, Math.round(t0 * sr), noise, { attack: 0.004, tau: 0.035, amp: a * 9, filter: new Biquad().bandpass(sr, 200, 1.3) });
      addThump(out, sr, Math.round(t0 * sr), { f0: 150, ratio: 1.3, glide: 0.01, attack: 0.005, tau: 0.03, amp: a * 0.45 });
    };
    beat(0, 1);
    beat(0.27, 0.7);
    const lp = new Biquad().lowpass(sr, 900, 0.7);
    for (let i = 0; i < out.length; i++) out[i] = Math.tanh(lp.process(out[i]) * 1.8);
  },
});

def('step', {
  maxRate: DARK, dur: 0.28, variants: 4, loud: 0.12, prio: 0, cap: 2,
  render(out, sr, rng, noise) {
    addThump(out, sr, 0, { f0: 80, ratio: 1.5, glide: 0.01, attack: 0.002, tau: 0.022, amp: 0.5 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.003, tau: 0.03, amp: 0.7, filter: new Biquad().lowpass(sr, 1200, 0.7) });
    crackle(out, sr, rng, noise, { t0: 0.004, len: 0.11, count: 22, fLo: 900, fHi: 3500, q: 1.5, amp: 0.2, decay: 2.5, tau: 0.002 });
  },
});

def('uiTap', {
  dur: 0.16, loud: 0.12, prio: 5, cap: 2,
  render(out, sr, rng, noise) {
    // A soft knock on a wax tablet's wooden frame.
    addModes(out, sr, 0, [{ f: 820, amp: 0.6, t60: 0.06 }, { f: 1960, amp: 0.3, t60: 0.04 }, { f: 3150, amp: 0.12, t60: 0.03 }], { hardness: 4000, attack: 0.0006 });
    addNoiseBurst(out, sr, 0, noise, { attack: 0.0003, tau: 0.003, amp: 0.3, filter: new Biquad().bandpass(sr, 2200, 1) });
  },
});

def('uiConfirm', {
  dur: 1.0, loud: 0.14, prio: 5, cap: 2,
  render(out, sr, rng) {
    lyre(out, sr, 0.004, 'A3', 0.55, 1.0, 91, 0.5);
    lyre(out, sr, 0.09, 'D4', 0.6, 1.1, 92, 0.55);
    addModes(out, sr, Math.round(0.09 * sr), [{ f: 1174.7, amp: 0.06, t60: 0.7 }, { f: 3231, amp: 0.025, t60: 0.4 }], { attack: 0.003 });
  },
});

// ---------------------------------------------------------------------------------------------
// Extras used internally (not part of the DESIGN.md name list)

def('draw', {
  dur: 0.55, loud: 0.13, prio: 2, cap: 1,
  render(out, sr, rng, noise) {
    // Bronze sliding out of a wooden scabbard, then the grip settling in the hand.
    const bp = new Biquad(), lpN = new OnePole(sr, 60);
    const n = Math.round(0.34 * sr);
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      if ((i & 15) === 0) bp.bandpass(sr, 2600 + 2400 * t / 0.34, 2.5);
      const fr = 0.5 + 0.5 * Math.abs(lpN.process(noise()) * 30);
      out[i] += 1.6 * Math.sin(Math.PI * t / 0.34) * fr * bp.process(noise());
    }
    addModes(out, sr, Math.round(0.33 * sr), [{ f: 1240, amp: 0.15, t60: 0.4 }, { f: 3380, amp: 0.08, t60: 0.25 }], { hardness: 6000 });
  },
});

def('gull', {
  maxRate: DARK, dur: 1.4, variants: 2, loud: 0.16, prio: 0, cap: 2,
  render(out, sr, rng, noise, v) {
    // Two or three "kyow" calls over the water.
    const calls = 2 + (v % 2);
    const tmp = new Float32Array(out.length);
    for (let c = 0; c < calls; c++) {
      const t0 = c * (0.32 + rng.next() * 0.08);
      const f = 820 + rng.next() * 160;
      const dur = 0.24 + rng.next() * 0.06;
      addHarmonic(tmp, sr, Math.round(t0 * sr), {
        dur, maxK: 8,
        f0: (t) => f * (t < 0.06 ? 1 + 0.55 * t / 0.06 : 1.55 - 0.65 * Math.min(1, (t - 0.06) / (dur - 0.06))),
        amp: (t) => Math.sin(Math.PI * Math.min(1, t / dur)) * (0.8 + 0.2 * noise()),
        weights: (t, ff, w) => {
          for (let k = 1; k < w.length; k++) w[k] = (k === 1 ? 0.5 : 1) * Math.exp(-Math.pow(Math.log2(k * ff / 2400), 2) / 0.8) / k;
        },
        gain: 0.3,
      });
    }
    const lp = new Biquad().lowpass(sr, 5000, 0.7);
    const rv = new Reverb(sr, { room: 0.7, damp: 0.5 });
    for (let i = 0; i < out.length; i++) {
      const x = lp.process(tmp[i]);
      out[i] += x + rv.process(x) * 0.25;
    }
  },
});

/** Names in the DESIGN.md §11 list (the extras above are internal). */
export const SFX_NAMES = ['whoosh', 'whooshHeavy', 'clang', 'parry', 'guardBreak', 'flesh', 'fleshHeavy', 'wicker', 'bash',
  'shieldUp', 'spearThrow', 'spearWhistle', 'spearHit', 'spearGround', 'volley', 'arrowThud', 'arrowShield', 'hurt',
  'death', 'grunt', 'scream', 'splash', 'warcry', 'horn', 'drum', 'comboSting', 'heartbeat', 'step', 'uiTap', 'uiConfirm'];

/** Sample rate `name` renders at for an output rate of `sr`. */
export const sfxRate = (name, sr) => Math.min(sr, SFX[name].maxRate);

/** Number of samples a variant of `name` needs at render rate `sr` (see sfxRate). */
export const sfxLength = (name, sr) => Math.ceil(SFX[name].dur * sr);

/**
 * Renders variant `variant` of `name` into `out` (zeroed, length sfxLength) and finishes it.
 * A generator: it yields between expensive layers. Returns { gain, peak, momentary } at the end.
 */
export function* renderSfxGen(name, out, sr, variant = 0) {
  const spec = SFX[name];
  const seed = hashName(name) + variant * 7919;
  const rng = createRng(seed);
  const noise = createNoise(seed * 31 + 7);
  const r = spec.render(out, sr, rng, noise, variant);
  if (r && typeof r.next === 'function') yield* r;
  removeDC(out, sr, false);
  fadeEnds(out, sr, 0.0003, 0.03);
  const gain = normalize(out, sr, { momentary: spec.loud, peak: 0.89 });
  const m = measure(out, sr);
  return { gain, peak: m.peak, momentary: m.momentary };
}

/** Synchronous convenience (tests): returns a new Float32Array. */
export function renderSfx(name, sr, variant = 0) {
  const out = new Float32Array(sfxLength(name, sr));
  const it = renderSfxGen(name, out, sr, variant);
  while (!it.next().done);
  return out;
}

function hashName(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return (h >>> 0) % 1000003 + 1;
}

