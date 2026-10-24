// spartan/tools/music/cues/lib.mjs: the score model and Standard MIDI File writer shared by the three
// cue generators (title.mjs, battle.mjs, lastStand.mjs). No dependencies beyond Node's standard library.
//
// Loop convention (matches spartan/tools/music/README.md):
//   bar 1 of the file  = setup bar (GS reset, programs, controllers), silent
//   bars 2..N+1        = loop body, pass 1 (warm-up: gives pass 2 its reverb/release ring-in)
//   bars N+2..2N+1     = loop body, pass 2 = THE LOOP  [loopStart, loopEnd)
// Both passes are identical (humanisation is generated once and copied). Body time is circular:
// an event humanised to before tick 0 is wrapped to the end of the body (it is the same instant
// in a loop), and a note that rings over the seam is clamped so that it never overlaps the next
// note-on of the same key on the same channel.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Where a generator writes <cue>.mid + <cue>.json: argv[2], else $THERMOPYLAE_MUSIC_CACHE/score
 *  (default ~/.cache/thermopylae-music/score). Generated scores are build products, not committed. */
export function outDir(argv = process.argv) {
  const cache = process.env.THERMOPYLAE_MUSIC_CACHE
    || path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'thermopylae-music');
  const dir = argv[2] || path.join(cache, 'score');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export const PPQ = 960;

// ------------------------------------------------------------------ deterministic randomness
export function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  next.gauss = () => { // approx N(0,1), Irwin-Hall(4)
    return (next() + next() + next() + next() - 2) * Math.sqrt(3);
  };
  return next;
}

// ------------------------------------------------------------------ pitch names
const PC = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
/** 'E4' -> 64, 'F#3', 'Bb2'; a trailing '+' means a quarter tone up (returned as {midi, qt:+1}). */
export function P(name) {
  if (typeof name === 'number') return name;
  const m = /^([A-G])(#|b)?(-?\d)$/.exec(name);
  if (!m) throw new Error(`bad pitch ${name}`);
  return 12 * (Number(m[3]) + 1) + PC[m[1]] + (m[2] === '#' ? 1 : m[2] === 'b' ? -1 : 0);
}
export const NAMES = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'G#', 'A', 'Bb', 'B'];
export const pname = (n) => NAMES[n % 12] + (Math.floor(n / 12) - 1);

// ------------------------------------------------------------------ the cue
export class Cue {
  /**
   * @param {object} o
   * @param {string} o.name cue name
   * @param {number} o.bpm one tempo for the whole file
   * @param {Array<[number, number]>} o.meters list of [bars, beatsPerBar] (quarter-note beats) for the body
   * @param {number} o.seed humanisation seed
   */
  constructor({ name, bpm, meters, seed = 1 }) {
    this.name = name; this.bpm = bpm; this.meters = meters; this.r = rng(seed);
    this.barStarts = []; // body ticks of each body bar (index 0 = bar 1)
    this.barBeats = [];
    let t = 0;
    for (const [n, beats] of meters) for (let i = 0; i < n; i++) { this.barStarts.push(t); this.barBeats.push(beats); t += beats * PPQ; }
    this.bodyTicks = t; this.bars = this.barStarts.length;
    this.setupBeats = meters[0][1];
    this.notes = []; this.ctl = []; this.markers = []; this.chan = {};
  }
  /** body tick of bar b (1-based) + beat offset in quarter notes */
  T(b, beat = 0) {
    if (b < 1 || b > this.bars + 1) throw new Error(`bar ${b} out of range`);
    const base = b === this.bars + 1 ? this.bodyTicks : this.barStarts[b - 1];
    return Math.round(base + beat * PPQ);
  }
  q(n) { return Math.round(n * PPQ); }
  msToTicks(ms) { return ms * PPQ * this.bpm / 60000; }
  secondsOfBar(b) { return this.T(b) / PPQ * 60 / this.bpm; }

  /**
   * channel setup, emitted in the setup bar and restated at body tick 0.
   * fine: RPN 1 fine tuning in cents (the doubled aulos players are a few cents apart);
   * minKey/maxKey: the part's range (write() refuses a note outside it, e.g. reeds above A5, men's voices above A4).
   * mono: a monophonic part (one aulos player, one salpinx): write() refuses two of its notes that overlap
   *   by more than 0.1 beat (grace notes and legato joins stay under that), across the seam too.
   */
  channel(ch, o) {
    this.chan[ch] = { program: 0, bank: 0, vol: 100, pan: 64, expr: 127, rev: 40, cho: 0, lag: 0, jit: 3, name: '', bendRange: 2, fine: 0, minKey: 0, maxKey: 127, mono: false, ...o };
  }

  /** a note. pos in body ticks, dur in ticks. opts: {jit (ms sd), lag (ms), vj (velocity jitter), qt (quarter tones -1/0/+1 via bend), legato} */
  note(ch, pos, dur, pitch, vel, opts = {}) {
    const c = this.chan[ch]; if (!c) throw new Error(`channel ${ch} not set up`);
    const jit = opts.jit ?? c.jit, lag = opts.lag ?? c.lag, vj = opts.vj ?? 4;
    const off = this.msToTicks(lag + jit * this.r.gauss());
    const v = Math.max(1, Math.min(127, Math.round(vel + vj * this.r.gauss())));
    const start = Math.round(pos + off);
    const len = Math.max(30, Math.round(dur + this.msToTicks(jit * 0.5 * this.r.gauss())));
    this.notes.push({ ch, start, end: start + len, key: P(pitch), vel: v });
  }
  /** a melodic line: items [pitch|null, quarters, vel?, {qt, gl}] played legato-ish from pos */
  line(ch, pos, items, { vel = 90, legato = 0.95, tieMin = 0 } = {}) {
    let t = pos;
    for (const it of items) {
      const [p, qn, v = vel, o = {}] = it;
      const d = this.q(qn);
      if (p !== null && p !== undefined) {
        if (o.qt) this.bend(ch, t - this.q(0.03), o.qt * 0.5);
        this.note(ch, t, Math.max(tieMin, d * (o.legato ?? legato)), p, v, o);
        if (o.qt) this.bend(ch, t + d - this.q(0.02), 0);
        if (o.gl) this.glide(ch, t + d * o.gl[0], t + d * o.gl[1], o.gl[2], o.gl[3] ?? 0);
      }
      t += d;
    }
    return t;
  }
  cc(ch, pos, num, val) { this.ctl.push({ ch, t: Math.round(pos), kind: 'cc', num, val: Math.max(0, Math.min(127, Math.round(val))) }); }
  /** linear CC ramp from t0 to t1 (inclusive), one event per `step` ticks */
  ramp(ch, t0, t1, num, v0, v1, step = PPQ / 8) {
    const n = Math.max(1, Math.round((t1 - t0) / step));
    let last = -1;
    for (let i = 0; i <= n; i++) {
      const v = Math.round(v0 + (v1 - v0) * i / n);
      if (v !== last) this.cc(ch, t0 + (t1 - t0) * i / n, num, v);
      last = v;
    }
  }
  /** pitch bend in semitones (bend range 2) */
  bend(ch, pos, semis) {
    const range = this.chan[ch].bendRange;
    const v = Math.max(0, Math.min(16383, Math.round(8192 + semis / range * 8192)));
    this.ctl.push({ ch, t: Math.round(pos), kind: 'bend', val: v });
  }
  glide(ch, t0, t1, s0, s1, step = PPQ / 32) {
    const n = Math.max(1, Math.round((t1 - t0) / step));
    for (let i = 0; i <= n; i++) this.bend(ch, t0 + (t1 - t0) * i / n, s0 + (s1 - s0) * i / n);
  }
  /** aulos scoop: the note starts `semis` flat and slides up to pitch over `beats` (bend back to 0 at the end) */
  scoop(ch, pos, semis = -0.3, beats = 0.14) {
    this.bend(ch, pos - this.q(0.01), semis);
    this.glide(ch, pos + this.q(0.02), pos + this.q(beats), semis, 0, PPQ / 48);
  }
  marker(b, text) { this.markers.push({ t: this.T(b), text }); }

  /** bar (1-based) and beat of a body tick, as "12:3.00" */
  barOf(t) {
    let b = 0;
    while (b + 1 < this.bars && this.barStarts[b + 1] <= t) b++;
    return `${b + 1}:${((t - this.barStarts[b]) / PPQ).toFixed(2)}`;
  }
  /** overlaps of more than `maxBeats` between notes of a mono:true channel (circular body) */
  monoOverlaps(maxBeats = 0.1) {
    const out = [], L = this.bodyTicks;
    for (const [chs, c] of Object.entries(this.chan)) {
      if (!c.mono) continue;
      const ch = Number(chs);
      const ns = this.notes.filter((n) => n.ch === ch).sort((a, b) => a.start - b.start);
      for (let i = 0; i < ns.length; i++) {
        for (let j = i + 1; j <= ns.length; j++) {
          const b = j < ns.length ? ns[j] : { ...ns[0], start: ns[0].start + L, end: ns[0].end + L };
          if (j === ns.length && ns.length < 2) break;
          if (b.start >= ns[i].end) break;
          const ov = Math.min(ns[i].end, b.end) - b.start;
          if (ov > maxBeats * PPQ) out.push({ ch, a: ns[i], b, ticks: ov });
        }
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- finalisation
  _wrap(t) { const L = this.bodyTicks; return ((t % L) + L) % L; }
  /** circular body: wrap starts, clamp overlaps of the same key (also across the seam) */
  _resolve() {
    const L = this.bodyTicks;
    for (const n of this.notes) { const d = n.end - n.start; n.start = this._wrap(n.start); n.end = n.start + d; }
    const byKey = new Map();
    for (const n of this.notes) { const k = n.ch * 128 + n.key; if (!byKey.has(k)) byKey.set(k, []); byKey.get(k).push(n); }
    for (const arr of byKey.values()) {
      arr.sort((a, b) => a.start - b.start || b.vel - a.vel);
      // drop exact duplicates (same start): keep the loudest
      for (let i = arr.length - 1; i > 0; i--) if (arr[i].start === arr[i - 1].start) { arr[i - 1].end = Math.max(arr[i - 1].end, arr[i].end); arr[i].dead = true; }
      const live = arr.filter((n) => !n.dead);
      for (let i = 0; i < live.length; i++) {
        const nxt = i + 1 < live.length ? live[i + 1].start : live[0].start + L;
        if (live[i].end > nxt - 8) live[i].end = Math.max(live[i].start + 20, nxt - 8);
      }
    }
    this.notes = this.notes.filter((n) => !n.dead);
    for (const c of this.ctl) c.t = Math.min(L - 1, Math.max(0, c.t));
  }

  /** write <name>.mid and <name>.json next to `dir` */
  write(dir, extra = {}) {
    this._resolve();
    const bad = this.notes.filter((n) => n.key < this.chan[n.ch].minKey || n.key > this.chan[n.ch].maxKey);
    if (bad.length) {
      const n = bad[0], c = this.chan[n.ch];
      throw new Error(`${bad.length} note(s) outside their part's range, e.g. ${pname(n.key)} on '${c.name}' (range ${pname(c.minKey)}-${pname(c.maxKey)}) at body tick ${n.start}`);
    }
    const poly = this.monoOverlaps();
    if (poly.length) {
      const o = poly[0];
      throw new Error(`${poly.length} overlap(s) on monophonic parts, e.g. ${pname(o.a.key)} + ${pname(o.b.key)} on '${this.chan[o.ch].name}' `
        + `for ${(o.ticks / PPQ).toFixed(2)} beat at bar ${this.barOf(o.b.start)}`);
    }
    // STEM="label:0,1,2" keeps only those channels' notes (balance analysis); output <name>.stem-<label>.*
    let base = this.name;
    if (process.env.STEM) {
      const [label, list] = process.env.STEM.split(':');
      const keep = new Set(list.split(',').map(Number));
      this.notes = this.notes.filter((n) => keep.has(n.ch));
      base = `${this.name}.stem-${label}`;
    }
    const L = this.bodyTicks, setup = this.setupBeats * PPQ;
    const passStart = [setup, setup + L]; // body pass 1, pass 2 (loop)
    const tracks = [];
    // conductor track
    const cond = [];
    const usPerQ = Math.round(60000000 / this.bpm);
    cond.push({ t: 0, o: 0, b: [0xFF, 0x51, 3, (usPerQ >> 16) & 255, (usPerQ >> 8) & 255, usPerQ & 255] });
    const ts = (t, beats) => cond.push({ t, o: 1, b: [0xFF, 0x58, 4, beats, 2, 24, 8] });
    ts(0, this.setupBeats);
    let lastBeats = this.setupBeats;
    for (const p of passStart) {
      for (let i = 0; i < this.bars; i++) {
        if (this.barBeats[i] !== lastBeats || i === 0) { ts(p + this.barStarts[i], this.barBeats[i]); lastBeats = this.barBeats[i]; }
      }
    }
    const txt = (t, type, s) => { const by = [...Buffer.from(s, 'latin1')]; cond.push({ t, o: 2, b: [0xFF, type, ...vlq(by.length), ...by] }); };
    txt(0, 0x03, `Thermopylae - ${this.name}`);
    for (const [pi, p] of passStart.entries()) for (const m of this.markers) txt(p + m.t, 0x06, `${pi === 1 ? '' : 'warmup '}${m.text}`);
    // No 'loopStart'/'loopEnd' markers: spessasynth's sequencer treats them as a hard loop and stops a
    // linear preview render early; the sidecar JSON is the loop marking.
    txt(passStart[1], 0x06, 'LOOP BODY (pass 2) begins: see sidecar');
    tracks.push(cond);
    // GS reset in the setup bar (track 1 head)
    const chans = Object.keys(this.chan).map(Number).sort((a, b) => a - b);
    for (const ch of chans) {
      const c = this.chan[ch];
      const ev = [];
      if (ch === chans[0]) ev.push({ t: 0, o: 0, b: [0xF0, ...vlq(10), 0x41, 0x10, 0x42, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41, 0xF7] });
      // Timeline anchor: spessasynth's sequencer skips to the first note-on (skipToFirstNoteOn), which would
      // shift a linear preview render by the setup bar. A note-on at tick 0 on a kit key with no sample
      // (key 1: GeneralUser's kits start at key 27) makes no sound and keeps the render's time = MIDI time.
      if (ch === chans[0]) ev.push({ t: 0, o: 8, b: [0x99, 1, 1] }, { t: PPQ / 8, o: 6, b: [0x89, 1, 0] });
      ev.push({ t: 0, o: 0, b: [0xFF, 0x03, ...vlq(c.name.length), ...Buffer.from(c.name, 'latin1')] });
      const setupAt = (t) => {
        ev.push({ t, o: 1, b: [0xB0 | ch, 0, c.bank] });
        ev.push({ t, o: 1, b: [0xB0 | ch, 32, 0] });
        ev.push({ t, o: 2, b: [0xC0 | ch, c.program] });
        // pitch-bend range RPN
        ev.push({ t, o: 3, b: [0xB0 | ch, 101, 0] }, { t, o: 3, b: [0xB0 | ch, 100, 0] }, { t, o: 3, b: [0xB0 | ch, 6, c.bendRange] }, { t, o: 3, b: [0xB0 | ch, 38, 0] });
        if (c.fine) {
          // RPN 1 fine tuning: 8192 = 0 cents, 8192 / 100 per cent (spessasynth honours it per channel)
          const v = Math.max(0, Math.min(16383, Math.round(8192 + c.fine * 81.92)));
          ev.push({ t, o: 3, b: [0xB0 | ch, 101, 0] }, { t, o: 3, b: [0xB0 | ch, 100, 1] }, { t, o: 3, b: [0xB0 | ch, 6, v >> 7] }, { t, o: 3, b: [0xB0 | ch, 38, v & 127] });
        }
        ev.push({ t, o: 3, b: [0xB0 | ch, 101, 127] }, { t, o: 3, b: [0xB0 | ch, 100, 127] });
      };
      setupAt(PPQ / 4);
      const state = (t) => {
        ev.push({ t, o: 4, b: [0xB0 | ch, 7, c.vol] }, { t, o: 4, b: [0xB0 | ch, 10, c.pan] }, { t, o: 4, b: [0xB0 | ch, 91, c.rev] }, { t, o: 4, b: [0xB0 | ch, 93, c.cho] });
      };
      state(PPQ / 2);
      // body passes
      const ctl = this.ctl.filter((e) => e.ch === ch);
      const notes = this.notes.filter((n) => n.ch === ch);
      // value of CC11 and bend that bar 1 needs: the latest event at or before tick 0 is the one at 0
      for (const p of passStart) {
        state(p);
        const has11 = ctl.some((e) => e.kind === 'cc' && e.num === 11 && e.t === 0);
        if (!has11) ev.push({ t: p, o: 4, b: [0xB0 | ch, 11, c.expr] });
        if (!ctl.some((e) => e.kind === 'bend' && e.t === 0)) ev.push({ t: p, o: 4, b: [0xE0 | ch, 0, 64] });
        for (const e of ctl) {
          if (e.kind === 'cc') ev.push({ t: p + e.t, o: 5, b: [0xB0 | ch, e.num, e.val] });
          else ev.push({ t: p + e.t, o: 5, b: [0xE0 | ch, e.val & 127, (e.val >> 7) & 127] });
        }
        for (const n of notes) {
          ev.push({ t: p + n.start, o: 7, b: [0x90 | ch, n.key, n.vel] });
          ev.push({ t: p + n.end, o: 6, b: [0x80 | ch, n.key, 0] });
        }
      }
      tracks.push(ev);
    }
    const bytes = smf(tracks);
    const midPath = `${dir}/${base}.mid`;
    fs.writeFileSync(midPath, bytes);
    const spb = 60 / this.bpm;
    const bodySec = L / PPQ * spb;
    const loopStartSeconds = +(setup / PPQ * spb + bodySec).toFixed(6);
    const loopEndSeconds = +(loopStartSeconds + bodySec).toFixed(6);
    const meterStr = [...new Set(this.barBeats)].map((b) => `${b}/4`).join('+');
    const json = {
      cue: this.name,
      midi: `${this.name}.mid`,
      bpm: this.bpm,
      meter: meterStr,
      bars: this.bars,
      loopStartBar: 2 + this.bars,
      loopEndBar: 2 + 2 * this.bars,
      loopStartSeconds,
      loopEndSeconds,
      loopSeconds: +bodySec.toFixed(6),
      convention: 'Bar 1 is a silent setup bar; the body is written twice (bars 2..N+1 warm-up, N+2..2N+1 the loop). Cut [loopStartSeconds, loopEndSeconds) of a linear render, or let spartan/tools/music/render.mjs loop the marked body.',
      ...extra,
      barStartsSeconds: this.barStarts.map((t) => +(t / PPQ * spb).toFixed(6)),
      barBeats: this.barBeats,
    };
    fs.writeFileSync(`${dir}/${base}.json`, JSON.stringify(json, null, 2) + '\n');
    return { midPath, json, notes: this.notes.length };
  }
}

// ------------------------------------------------------------------ SMF bytes
function vlq(n) {
  const out = [n & 0x7F];
  while ((n >>= 7)) out.unshift((n & 0x7F) | 0x80);
  return out;
}
function smf(tracks) {
  const chunks = [];
  const hdr = Buffer.alloc(14);
  hdr.write('MThd', 0, 'latin1'); hdr.writeUInt32BE(6, 4); hdr.writeUInt16BE(1, 8); hdr.writeUInt16BE(tracks.length, 10); hdr.writeUInt16BE(PPQ, 12);
  chunks.push(hdr);
  for (const ev of tracks) {
    ev.sort((a, b) => a.t - b.t || a.o - b.o);
    const data = [];
    let last = 0, lastT = 0;
    for (const e of ev) {
      data.push(...vlq(e.t - last)); last = e.t; lastT = e.t;
      data.push(...e.b);
    }
    data.push(0, 0xFF, 0x2F, 0);
    const h = Buffer.alloc(8); h.write('MTrk', 0, 'latin1'); h.writeUInt32BE(data.length, 4);
    chunks.push(h, Buffer.from(data));
  }
  return Buffer.concat(chunks);
}

// ------------------------------------------------------------------ musical helpers
/** Drum hit (channel 9 or a melodic drum channel) with timing jitter in ms */
export function hit(cue, ch, pos, key, vel, o = {}) { cue.note(ch, pos, o.dur ?? cue.q(0.25), key, vel, { jit: o.jit ?? 1.5, vj: o.vj ?? 5, lag: o.lag }); }

/** Map a melody from one species to another by scale degree. from/to: arrays of 7 pitch classes in order. */
export function recast(pitchName, from, to) {
  const n = P(pitchName); const pc = n % 12; const oct = Math.floor(n / 12);
  const i = from.indexOf(pc); if (i < 0) throw new Error(`pitch ${pitchName} not in source species`);
  // keep the register: degree distance from the source final
  const f0 = from[0], t0 = to[0];
  const srcAbove = (pc - f0 + 12) % 12, dstAbove = (to[i] - t0 + 12) % 12;
  const base = n - srcAbove; // final at or below the note
  return base + (t0 - f0) + dstAbove;
}
/** the next note of a species (array of pitch classes) above / below a MIDI key */
export function stepUp(key, pcs) { let k = P(key) + 1; while (!pcs.includes(k % 12)) k++; return k; }
export function stepDown(key, pcs) { let k = P(key) - 1; while (!pcs.includes(k % 12)) k--; return k; }
export const DORIAN_E = [4, 5, 7, 9, 11, 0, 2];      // E F G A B C D  (ancient Dorian species E-E)
export const MIXOLYDIAN_B = [11, 0, 2, 4, 5, 7, 9];  // B C D E F G A  (ancient Mixolydian species B-B)
export const PHRYGIAN_SP_E = [4, 6, 7, 9, 11, 1, 2]; // E F# G A B C# D (Phrygian species / Iastian tonos, Seikilos)
