#!/usr/bin/env node
// Thermopylae music pipeline, stage 1: renders a cue MIDI into a seamless loop (48 kHz stereo,
// 32-bit float WAV) with the GeneralUser GS soundfont through spessasynth_core, and writes the
// game's musicbank.js from the encoded cues. See README.md in this directory.
//
//   node render.mjs <cue>.mid [--json <cue>.json] [--cue <name>] [--out-dir DIR] [--passes 3] [--full]
//   node render.mjs --check <cue>.mid [--json <cue>.json]      validate the loop marking, print the plan
//   node render.mjs --reference [--out-dir DIR]                render the procedural loops.js cues (loudness reference)
//   node render.mjs --bank [--music-dir DIR] [--bank-out FILE] regenerate js/audio/musicbank.js from audio/music/*.ogg
//
// LOOP CONVENTION (the contract with the composers; README.md has the long form)
//  * A sidecar JSON next to the MIDI (same basename) marks the loop body [loopStart, loopEnd):
//      { "cue": "battle", "bpm": 100, "bars": 48, "loopStartSeconds": 115.2, "loopEndSeconds": 230.4 }
//    or with 1-based bar numbers, end exclusive: { "loopStartBar": 49, "loopEndBar": 97 }.
//    Without a sidecar, MIDI marker meta events named "loopStart" and "loopEnd" are used.
//  * Both ends must sit on bar lines of the MIDI's own tempo map and time signatures; the body is a
//    whole number of bars.
//  * Everything before loopStart is a pre-roll played once (GS reset, programs, controllers, and
//    if the composer likes, the whole body written out once more as a warm-up).
//  * The body is every event in [loopStart, loopEnd). A note that starts in the body keeps its own
//    note-off even past loopEnd: it rings across the seam into bar 1, as the composer wrote it.
//    Other events at or after loopEnd are ignored (reported).
//  * The renderer plays the pre-roll, then the body `passes` times back to back (sample-exact: the
//    body's event offsets are rounded once and reused for every pass), and keeps pass 2. Its start
//    therefore already holds pass 1's ring-out (reverb, releases, notes crossing the seam), so the cut
//    loops without a seam. Pass 3 is the check: the samples after the cut's end in the continuous
//    render must equal the cut's own start (`seam.maxDiffVsPeakDb`).
//  * The loop length is round((loopEnd - loopStart) * 48000) samples, or with one tempo in the body
//    the nominal quarter notes x 60 / bpm when that is within 5 ms (MIDI tempos are whole us).
//    It should be a multiple of 960 samples (20 ms: whole Opus frames, see master.py); warned if not.
//
// Determinism: events are applied at their exact sample (the render is split into blocks at every
// event and pass boundary), and the two free-running modulators of the synth's master effects are
// phase-locked to the loop: the chorus LFO and the Dattorro reverb's tank excursion are given a
// whole number of cycles per loop and a phase that is a function of the position in the loop. Every
// other modulator in spessasynth runs per voice from its note-on, so the render is periodic.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SPARTAN = path.resolve(HERE, '../..');
const GAME = path.join(SPARTAN, 'src/main/assets/game');
const CACHE = process.env.THERMOPYLAE_MUSIC_CACHE
  || path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'), 'thermopylae-music');
const SR = 48000;
const BLOCK = 128;
const TAU = 2 * Math.PI;
const SPESSA_VERSION = '4.3.22';
const SF2_SHA256 = 'f45b6b4a68b6bf3d792fcbb6d7de24dc701a0f89c5900a21ef3aaece993b839a'; // GeneralUser GS v1.471 (npm generaluser@1.47.1)
const CUES = JSON.parse(fs.readFileSync(path.join(HERE, 'cues.json'), 'utf8'));
const CUE_NAMES = Object.keys(CUES).filter((k) => !k.startsWith('_'));

// ------------------------------------------------------------------------------------------ CLI

function parseArgs(argv) {
  const a = { files: [], passes: 3, tail: 0 };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) die(`${v} needs a value`);
      return argv[++i];
    };
    switch (v) {
      case '--json': a.json = next(); break;
      case '--cue': a.cue = next(); break;
      case '--out-dir': a.outDir = next(); break;
      case '--passes': a.passes = Number(next()); break;
      case '--full': a.full = true; break;
      case '--check': a.check = true; break;
      case '--reference': a.reference = true; break;
      case '--bank': a.bank = true; break;
      case '--music-dir': a.musicDir = next(); break;
      case '--bank-out': a.bankOut = next(); break;
      case '--allow-unpinned-sf2': a.anySf2 = true; break;
      case '--allow-offbar': a.offbar = true; break;
      case '-h': case '--help': usage(0); break;
      default:
        if (v.startsWith('-')) die(`unknown option ${v}`);
        a.files.push(v);
    }
  }
  return a;
}

function usage(code) {
  const src = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n');
  console.log(src.slice(1, 10).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  process.exit(code);
}

function die(msg) {
  console.error(`render.mjs: ${msg}`);
  process.exit(1);
}

// ------------------------------------------------------------------------------------ libraries

async function loadSpessa() {
  const dir = process.env.SPESSASYNTH || path.join(CACHE, 'node_modules', 'spessasynth_core');
  const pkgPath = path.join(dir, 'package.json');
  if (!fs.existsSync(pkgPath)) die(`spessasynth_core not found in ${dir}: run tools/music/setup.sh (or set SPESSASYNTH)`);
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
  if (pkg.version !== SPESSA_VERSION) {
    die(`spessasynth_core ${pkg.version} in ${dir}; this renderer is pinned to ${SPESSA_VERSION} (its effect patches depend on it)`);
  }
  const lib = await import(pathToFileURL(path.join(dir, pkg.module || pkg.main)).href);
  if (lib.SpessaLog) { lib.SpessaLog.infoEnabled = false; lib.SpessaLog.groupEnabled = false; }
  return lib;
}

function loadSoundfont(anySf2) {
  const sf2 = process.env.SF2 || path.join(CACHE, 'GeneralUser.sf2');
  if (!fs.existsSync(sf2)) die(`soundfont not found at ${sf2}: run tools/music/setup.sh (or set SF2)`);
  const buf = fs.readFileSync(sf2);
  const sha = crypto.createHash('sha256').update(buf).digest('hex');
  if (sha !== SF2_SHA256 && !anySf2) die(`${sf2} sha256 ${sha} is not the pinned GeneralUser GS v1.471 (${SF2_SHA256}); --allow-unpinned-sf2 to override`);
  return { path: sf2, sha256: sha, buffer: buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) };
}

// ------------------------------------------------------------------------------------ MIDI plan

const META_MARKER = 0x06, META_TIMESIG = 0x58, META_TEXT = 0x01, META_CUE = 0x07;

/** Bar lines of the MIDI (ticks), from its time-signature events (4/4 until the first one). */
function barLines(midi, upToTick) {
  const sigs = [];
  for (const tr of midi.tracks) {
    for (const e of tr.events) if (e.statusByte === META_TIMESIG) sigs.push({ tick: e.ticks, num: e.data[0], den: 2 ** e.data[1] });
  }
  sigs.sort((x, y) => x.tick - y.tick);
  if (!sigs.length || sigs[0].tick > 0) sigs.unshift({ tick: 0, num: 4, den: 4 });
  const bars = [];
  let sigIdx = 0, tick = 0;
  while (tick <= upToTick) {
    while (sigIdx + 1 < sigs.length && sigs[sigIdx + 1].tick <= tick) sigIdx++;
    bars.push({ tick, num: sigs[sigIdx].num, den: sigs[sigIdx].den });
    let next = tick + midi.timeDivision * 4 * sigs[sigIdx].num / sigs[sigIdx].den;
    // A time signature that does not fall on a bar line still starts a new bar (as notation would).
    if (sigIdx + 1 < sigs.length && sigs[sigIdx + 1].tick < next) next = sigs[sigIdx + 1].tick;
    tick = next;
  }
  return bars;
}

function textOf(e) {
  return Buffer.from(e.data).toString('latin1').trim();
}

/**
 * Reads the MIDI and the loop marking and splits the events into the pre-roll and the body.
 * Returns everything the renderer needs plus a human-readable summary.
 */
function plan(lib, midiPath, jsonPath, opts = {}) {
  const buf = fs.readFileSync(midiPath);
  const midi = lib.BasicMIDI.fromArrayBuffer(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), path.basename(midiPath));
  if (midi.isMultiPort) die(`${midiPath}: multi-port MIDI files are not supported (use channels 1-16 on one port)`);
  let side = {};
  const sidePath = jsonPath || midiPath.replace(/\.midi?$/i, '') + '.json';
  if (fs.existsSync(sidePath)) side = JSON.parse(fs.readFileSync(sidePath, 'utf8'));
  else if (jsonPath) die(`sidecar ${jsonPath} not found`);
  const warnings = [];
  const cue = opts.cue || side.cue || path.basename(midiPath).replace(/\.midi?$/i, '');
  if (!CUE_NAMES.includes(cue)) warnings.push(`cue '${cue}' is not one of ${CUE_NAMES.join(', ')}`);

  // Events in time order, with absolute seconds.
  const events = [];
  for (const { tr, ev } of midi.timeline) {
    const e = midi.tracks[tr].events[ev];
    events.push({ e, ticks: e.ticks, t: midi.midiTicksToSeconds(e.ticks), status: e.statusByte, order: events.length });
  }
  const lastTick = events.length ? events[events.length - 1].ticks : 0;

  // Loop points: sidecar seconds, sidecar bars, or markers.
  const bars = barLines(midi, lastTick + midi.timeDivision * 64);
  const barAt = (n) => {
    if (!Number.isInteger(n) || n < 1 || n > bars.length) die(`bar ${n} is outside the MIDI (1..${bars.length})`);
    return bars[n - 1].tick;
  };
  let startTick, endTick, source;
  if (side.loopStartSeconds != null && side.loopEndSeconds != null) {
    startTick = midi.secondsToMIDITicks(side.loopStartSeconds);
    endTick = midi.secondsToMIDITicks(side.loopEndSeconds);
    source = 'sidecar seconds';
    if (side.loopStartBar != null && side.loopEndBar != null) {
      const bs = barAt(side.loopStartBar), be = barAt(side.loopEndBar);
      if (Math.abs(bs - startTick) > 1 || Math.abs(be - endTick) > 1) {
        die(`sidecar loopStartBar/loopEndBar (${side.loopStartBar}/${side.loopEndBar}) disagree with loopStartSeconds/loopEndSeconds`);
      }
    }
  } else if (side.loopStartBar != null && side.loopEndBar != null) {
    startTick = barAt(side.loopStartBar);
    endTick = barAt(side.loopEndBar);
    source = 'sidecar bars';
  } else {
    for (const x of events) {
      if (x.status !== META_MARKER && x.status !== META_CUE && x.status !== META_TEXT) continue;
      const s = textOf(x.e).toLowerCase();
      if (s === 'loopstart' && startTick == null) startTick = x.ticks;
      if (s === 'loopend' && endTick == null) endTick = x.ticks;
    }
    if (startTick == null || endTick == null) die(`${midiPath}: no loop marking (sidecar ${sidePath} with loopStartSeconds/loopEndSeconds, or markers "loopStart"/"loopEnd")`);
    source = 'MIDI markers';
  }
  if (!(endTick > startTick)) die('loop end must come after loop start');

  // Snap to bar lines (and check that they are bar lines).
  const snap = (tick, what) => {
    let best = bars[0];
    for (const b of bars) if (Math.abs(b.tick - tick) < Math.abs(best.tick - tick)) best = b;
    const errS = Math.abs(midi.midiTicksToSeconds(best.tick) - midi.midiTicksToSeconds(tick));
    if (errS > 0.002 || Math.abs(best.tick - tick) > 2) {
      const msg = `${what} (tick ${tick}) is not on a bar line (nearest is bar ${bars.indexOf(best) + 1} at tick ${best.tick})`;
      if (!opts.offbar) die(msg + '; --allow-offbar to render anyway');
      warnings.push(msg);
      return tick;
    }
    return best.tick;
  };
  startTick = snap(startTick, 'loop start');
  endTick = snap(endTick, 'loop end');
  const startBar = bars.findIndex((b) => b.tick === startTick) + 1;
  const endBar = bars.findIndex((b) => b.tick === endTick) + 1;
  const nBars = startBar && endBar ? endBar - startBar : null;
  if (side.bars != null && nBars != null && side.bars !== nBars) die(`sidecar says ${side.bars} bars, the loop marking spans ${nBars}`);
  const t0 = midi.midiTicksToSeconds(startTick);
  const t1 = midi.midiTicksToSeconds(endTick);
  const S0 = Math.round(t0 * SR);

  // Tempo at the loop start (the musicbank's nominal bpm unless the sidecar says otherwise).
  let bpmAtStart = 120;
  for (const tc of midi.tempoChanges) if (tc.ticks <= startTick) { bpmAtStart = tc.tempo; break; } // ordered last-to-first
  // Tempo events inside the body that actually change the tempo (a repeated tempo event does not).
  const tempos = new Set(midi.tempoChanges.filter((tc) => tc.ticks > startTick && tc.ticks < endTick).map((tc) => +tc.tempo.toFixed(3)));
  if (tempos.size === 1 && tempos.has(+bpmAtStart.toFixed(3))) tempos.clear();
  const bpm = side.bpm ?? +bpmAtStart.toFixed(3);
  const sigAtStart = bars[startBar - 1] || bars[0];

  // Loop length. A MIDI tempo is whole microseconds per quarter note, so 72 bpm is stored as
  // 833333 us and 12 bars last 39.99998 s, not 40. With one tempo in the body, the nominal length
  // (quarter notes x 60 / bpm) is used when it is within 5 ms of the MIDI's: the loop then lasts
  // exactly the musical length (events keep their MIDI times; the last few samples simply ring on).
  const quarters = (endTick - startTick) / midi.timeDivision;
  const nominal = quarters * 60 / bpm;
  let L = Math.round((t1 - t0) * SR);
  let lengthSource = 'MIDI tempo map';
  if (!tempos.size && Math.abs(nominal - (t1 - t0)) < 0.005) {
    L = Math.round(nominal * SR);
    lengthSource = `nominal ${quarters} quarter notes at ${bpm} bpm`;
  }
  // master.py loops the Opus stream on a codec frame boundary only if the loop is a whole number of
  // 20 ms frames (960 samples); otherwise the codec noise splices at the wrap. A multiple of 960 is
  // also a whole number of samples at 44.1 kHz (WebAudio resamples decoded buffers to the context rate).
  if (L % 960) warnings.push(`loop length ${L} samples is not a multiple of 960 (20 ms): the Opus loop falls back to circular priming, and at 44.1 kHz the loop is ${(L * 44100 / SR).toFixed(2)} samples. Pick bpm and bars so that bars x beats x 60 / bpm is a multiple of 0.02 s (README, loop convention, item 4)`);

  // Pair notes (FIFO per channel and key), then split into pre-roll and body.
  const isOn = (x) => (x.status & 0xf0) === 0x90 && x.e.data[1] > 0;
  const isOff = (x) => (x.status & 0xf0) === 0x80 || ((x.status & 0xf0) === 0x90 && x.e.data[1] === 0);
  const open = new Map();
  for (const x of events) {
    if (x.status < 0x80 || x.status >= 0xf0) continue;
    const key = ((x.status & 0x0f) << 7) | x.e.data[0];
    if (isOn(x)) {
      if (!open.has(key)) open.set(key, []);
      open.get(key).push(x);
    } else if (isOff(x)) {
      const q = open.get(key);
      const on = q && q.shift();
      if (on) { on.off = x; x.on = on; }
    }
  }
  let hanging = 0;
  for (const q of open.values()) hanging += q.length;
  if (hanging) warnings.push(`${hanging} note-on(s) without a note-off (they would sustain for ever)`);

  const pre = [], body = [];
  let ignoredAfter = 0, sysexInBody = 0, allOffInBody = 0, metaSkipped = 0;
  for (const x of events) {
    if (x.status < 0x80) { metaSkipped++; continue; } // meta events: tempo and signatures are in the clock already
    const inPre = x.ticks < startTick;
    const inBody = x.ticks >= startTick && x.ticks < endTick;
    if (isOff(x)) {
      if (!x.on) continue; // stray note-off
      if (x.on.ticks < startTick) pre.push(x);
      else if (x.on.ticks < endTick) body.push(x);
      continue;
    }
    if (inPre) pre.push(x);
    else if (inBody) {
      if (x.status === 0xf0 || x.status === 0xf7) {
        // A SysEx (GS reset, effect setup) at the very start of the body is setup, not music: replaying
        // it every pass would cut the notes that ring across the seam.
        if (x.ticks === startTick) { pre.push(x); continue; }
        sysexInBody++;
      }
      if ((x.status & 0xf0) === 0xb0 && [120, 121, 123].includes(x.e.data[0])) allOffInBody++;
      body.push(x);
    } else if (!isOff(x)) ignoredAfter++;
  }
  if (ignoredAfter) warnings.push(`${ignoredAfter} event(s) at or after loopEnd ignored (a written-out extra pass?)`);
  if (sysexInBody) warnings.push(`${sysexInBody} SysEx message(s) inside the body are replayed every pass`);
  if (allOffInBody) warnings.push(`${allOffInBody} all-notes-off/reset-controllers message(s) inside the body: they will cut notes ringing across the seam`);
  if (tempos.size) warnings.push(`tempo changes inside the body (${[...tempos].join(', ')} bpm): fine, but the musicbank bpm is nominal`);

  // Controllers that change across the seam: the state after the whole body (the cut's end) vs.
  // that state updated by the body's own events on the loop start tick (the cut's start). Usually
  // intended (an accent on bar 1); a pitch or volume jump under a note that rings across the seam
  // is not.
  const apply = (st, x) => {
    const s = x.status & 0xf0, ch = x.status & 0x0f;
    if (s === 0xb0) st.set(`ch${ch + 1} CC${x.e.data[0]}`, x.e.data[1]);
    else if (s === 0xe0) st.set(`ch${ch + 1} pitch`, (x.e.data[1] << 7) | x.e.data[0]);
    else if (s === 0xc0) st.set(`ch${ch + 1} program`, x.e.data[0]);
  };
  const atEnd = new Map();
  for (const x of pre) if (x.ticks < startTick) apply(atEnd, x);
  for (const x of body) if (!isOff(x)) apply(atEnd, x);
  const atNext = new Map(atEnd);
  for (const x of body) if (x.ticks === startTick && !isOff(x)) apply(atNext, x);
  const seamChanges = [];
  for (const [k, v] of atNext) {
    if (atEnd.get(k) !== v && !/CC(0|32|6|38|98|99|100|101)$/.test(k)) seamChanges.push(`${k} ${atEnd.get(k) ?? 'unset'} -> ${v}`);
  }

  // Sample positions. Body offsets are rounded once, relative to the body start, so every pass is
  // scheduled identically. Note-offs at the same sample as other events go first (a note that ends
  // as the next one starts on the same key), except the note-off of a note that started on that
  // very sample.
  const preEv = pre.map((x) => ({ x, sample: Math.round(x.t * SR) }));
  const bodyEv = body.map((x) => ({ x, off: Math.round((x.t - t0) * SR) }));
  for (const b of bodyEv) {
    if (b.x.on) {
      const onOff = Math.round((b.x.on.t - t0) * SR);
      if (b.off <= onOff) b.off = onOff + 1;
    }
  }
  const notesInBody = body.filter(isOn).length;
  const crossing = body.filter((x) => isOn(x) && x.off && x.off.ticks > endTick).length;
  const sum = {
    midi: path.resolve(midiPath), sidecar: fs.existsSync(sidePath) ? path.resolve(sidePath) : null, cue, source,
    loopStartSeconds: +t0.toFixed(6), loopEndSeconds: +t1.toFixed(6), loopStartBar: startBar || null, loopEndBar: endBar || null,
    bars: nBars, meter: `${sigAtStart.num}/${sigAtStart.den}`, bpm, loopSamples: L, loopSeconds: L / SR, lengthSource,
    midiLoopSeconds: +(t1 - t0).toFixed(6),
    preRollSamples: S0, notesInBody, notesCrossingSeam: crossing, preRollEvents: pre.length, bodyEvents: body.length,
    metaEventsSkipped: metaSkipped, timeDivision: midi.timeDivision, seamControllerChanges: seamChanges,
  };
  const brief = CUES[cue];
  if (brief?.loopSeconds && (sum.loopSeconds < brief.loopSeconds[0] || sum.loopSeconds > brief.loopSeconds[1])) {
    warnings.push(`loop is ${sum.loopSeconds.toFixed(2)} s; the brief for '${cue}' asks for ${brief.loopSeconds[0]}-${brief.loopSeconds[1]} s`);
  }
  if (brief?.bpm && (bpm < brief.bpm[0] || bpm > brief.bpm[1])) warnings.push(`bpm ${bpm}; the brief for '${cue}' asks for ${brief.bpm[0]}-${brief.bpm[1]}`);
  if (!notesInBody) warnings.push('the loop body has no notes');
  return { midi, sum, warnings, preEv, bodyEv, L, S0 };
}

// --------------------------------------------------------------------------- effect phase locks

/**
 * Makes the master effects periodic in the loop. spessasynth's chorus LFO and the Dattorro reverb's
 * tank excursion (two slightly detuned sinusoids) run freely, so their phase at the cut's end would
 * not match its start. Both are given a whole number of cycles per loop (rates move by under half a
 * cycle per loop, i.e. well under 1 %) and a phase computed from the position in the loop before
 * every block. Checked against the pinned spessasynth_core so that a changed implementation fails
 * loudly instead of rendering seams.
 */
function lockEffects(synth, L) {
  const chorus = synth.chorusProcessor;
  const rev = synth.reverbProcessor;
  const dat = rev && rev.dattorro;
  const protoSrc = dat && String(Object.getPrototypeOf(dat).process);
  if (!chorus || typeof chorus.phase !== 'number' || typeof chorus.rateInc !== 'number') {
    die('spessasynth chorus has no phase/rateInc: update lockEffects() for this version');
  }
  if (!dat || !protoSrc.includes('excPhase * 6.28)') || !protoSrc.includes('excPhase * 6.2847)') || !Array.isArray(dat.delays)) {
    die('spessasynth reverb is not the expected Dattorro implementation: update lockEffects() for this version');
  }
  const st = { cSet: NaN, mC: 0, rate: NaN, incA: 0, incB: 0, mA: 0, mB: 0, posInLoop: 0 };

  // The pinned DattorroReverb.process (spessasynth_core 4.3.22, after khoin's public-domain
  // DattorroReverbNode), with its excursion phases taken from the loop position.
  dat.process = function periodicProcess(input, outputLeft, outputRight, startIndex, sampleCount) {
    const pd = this.preDelay | 0;
    const fi = this.inputDiffusion1;
    const si = this.inputDiffusion2;
    const dc = this.decay;
    const ft = this.decayDiffusion1;
    const sd = this.decayDiffusion2;
    const dp = 1 - this.damping;
    const ed = this.excursionDepth * this.sampleRate / 1e3;
    let phA = st.phA, phB = st.phB;
    const incA = st.incA, incB = st.incB;
    const blockStart = this.pDWrite;
    for (let j = 0; j < sampleCount; j++) this.pDelay[(blockStart + j) % this.pDLength] = input[j];
    for (let i = 0; i < sampleCount; i++) {
      this.lp1 += this.preLPF * (this.pDelay[(this.pDLength + this.pDWrite - pd + i) % this.pDLength] - this.lp1);
      let pre = this.writeDelay(0, this.lp1 - fi * this.readDelay(0));
      pre = this.writeDelay(1, fi * (pre - this.readDelay(1)) + this.readDelay(0));
      pre = this.writeDelay(2, fi * pre + this.readDelay(1) - si * this.readDelay(2));
      pre = this.writeDelay(3, si * (pre - this.readDelay(3)) + this.readDelay(2));
      const split = si * pre + this.readDelay(3);
      const exc = ed * (1 + Math.cos(phA * TAU));
      const exc2 = ed * (1 + Math.sin(phB * TAU));
      let temp = this.writeDelay(4, split + dc * this.readDelay(11) + ft * this.readDelayCAt(4, exc));
      this.writeDelay(5, this.readDelayCAt(4, exc) - ft * temp);
      this.lp2 += dp * (this.readDelay(5) - this.lp2);
      temp = this.writeDelay(6, dc * this.lp2 - sd * this.readDelay(6));
      this.writeDelay(7, this.readDelay(6) + sd * temp);
      temp = this.writeDelay(8, split + dc * this.readDelay(7) + ft * this.readDelayCAt(8, exc2));
      this.writeDelay(9, this.readDelayCAt(8, exc2) - ft * temp);
      this.lp3 += dp * (this.readDelay(9) - this.lp3);
      temp = this.writeDelay(10, dc * this.lp3 - sd * this.readDelay(10));
      this.writeDelay(11, this.readDelay(10) + sd * temp);
      const t = this.taps;
      const leftSample = this.readDelayAt(9, t[0]) + this.readDelayAt(9, t[1]) - this.readDelayAt(10, t[2]) + this.readDelayAt(11, t[3])
        - this.readDelayAt(5, t[4]) - this.readDelayAt(6, t[5]) - this.readDelayAt(7, t[6]);
      const idx = i + startIndex;
      outputLeft[idx] += leftSample * this.gain;
      const rightSample = this.readDelayAt(5, t[7]) + this.readDelayAt(5, t[8]) - this.readDelayAt(6, t[9]) + this.readDelayAt(7, t[10])
        - this.readDelayAt(9, t[11]) - this.readDelayAt(10, t[12]) - this.readDelayAt(11, t[13]);
      outputRight[idx] += rightSample * this.gain;
      if ((phA += incA) >= 1) phA -= 1;
      if ((phB += incB) >= 1) phB -= 1;
      for (let j = 0, d = this.delays[0]; j < this.delays.length; d = this.delays[++j]) {
        d[1] = d[1] + 1 & d[3];
        d[2] = d[2] + 1 & d[3];
      }
    }
    this.pDWrite = (blockStart + sampleCount) % this.pDLength;
  };

  const report = { chorusCyclesPerLoop: 0, reverbCyclesPerLoop: [0, 0] };
  /** Called before every block with the block's position in the loop (0..L-1). */
  return {
    report,
    before(posInLoop) {
      // Chorus: a triangle LFO, phase in [0, 1), advanced by rateInc per sample.
      if (chorus.rateInc !== st.cSet) {
        const inc = chorus.rateInc;
        st.mC = inc > 0 ? Math.max(1, Math.round(inc * L)) : 0;
        st.cSet = st.mC / L;
        report.chorusCyclesPerLoop = st.mC;
      }
      chorus.rateInc = st.cSet;
      chorus.phase = st.mC ? ((st.mC * posInLoop) % L) / L : chorus.phase;
      // Reverb: the original runs cos(excPhase * 6.28) and sin(excPhase * 6.2847) with excPhase
      // growing by excursionRate / sampleRate per sample; keep both rates, rounded to whole cycles
      // per loop (and never equal, so the two halves of the tank stay decorrelated).
      if (dat.excursionRate !== st.rate) {
        st.rate = dat.excursionRate;
        const perLoop = st.rate * L / SR;
        st.mA = Math.max(st.rate > 0 ? 1 : 0, Math.round(perLoop * 6.28 / TAU));
        st.mB = Math.max(st.rate > 0 ? 1 : 0, Math.round(perLoop * 6.2847 / TAU));
        if (st.mA && st.mB === st.mA) st.mB = st.mA + 1;
        st.incA = st.mA / L;
        st.incB = st.mB / L;
        report.reverbCyclesPerLoop = [st.mA, st.mB];
      }
      st.phA = ((st.mA * posInLoop) % L) / L;
      st.phB = ((st.mB * posInLoop) % L) / L;
    },
  };
}

// ---------------------------------------------------------------------------------------- render

async function renderCue(lib, file, args) {
  const P = plan(lib, file, args.json, { cue: args.cue, offbar: args.offbar });
  const { sum, warnings, preEv, bodyEv, L, S0 } = P;
  const passes = Math.max(2, Math.floor(args.passes || 3));
  const sf = loadSoundfont(args.anySf2);

  const synth = new lib.SpessaSynthProcessor(SR, { effectsEnabled: true, eventsEnabled: false, maxBufferSize: BLOCK });
  const warn = lib.SpessaLog ? lib.SpessaLog.warnEnabled : false;
  if (lib.SpessaLog) lib.SpessaLog.warnEnabled = false; // "Allocating N new voices!"
  synth.setSystemParameter('voiceCap', 1024); // offline: no need to steal voices
  if (lib.SpessaLog) lib.SpessaLog.warnEnabled = warn;
  synth.soundBankManager.addSoundBank(lib.SoundBankLoader.fromArrayBuffer(sf.buffer), 'gu');
  await synth.processorInitialized;
  const lock = lockEffects(synth, L);

  // Schedule: pre-roll at absolute samples, then the body `passes` times.
  const sched = [];
  for (const p of preEv) sched.push({ sample: p.sample, x: p.x });
  for (let k = 0; k < passes; k++) for (const b of bodyEv) sched.push({ sample: S0 + k * L + b.off, x: b.x });
  const prio = (s) => {
    const x = s.x;
    const off = (x.status & 0xf0) === 0x80 || ((x.status & 0xf0) === 0x90 && x.e.data[1] === 0);
    return off ? 0 : 1;
  };
  sched.sort((a, b) => a.sample - b.sample || prio(a) - prio(b) || a.x.order - b.x.order);
  const end = S0 + passes * L;
  const lastEvent = sched.length ? sched[sched.length - 1].sample : 0;
  const total = Math.max(end, lastEvent + 1);
  const left = new Float32Array(total), right = new Float32Array(total);

  const t0 = Date.now();
  let pos = 0, ei = 0, maxVoices = 0, blocks = 0;
  while (pos < total) {
    while (ei < sched.length && sched[ei].sample <= pos) {
      const { x } = sched[ei++];
      synth.processMessage(x.e);
    }
    let n = Math.min(BLOCK, total - pos);
    if (ei < sched.length) n = Math.min(n, sched[ei].sample - pos);
    // Split at pass boundaries so the block grid restarts identically in every pass.
    const rel = pos - S0;
    if (rel < 0) n = Math.min(n, -rel);
    else if (pos < end) n = Math.min(n, L - (rel % L));
    const posInLoop = ((rel % L) + L) % L;
    lock.before(posInLoop);
    synth.process(left, right, pos, n);
    if (synth.voiceCount > maxVoices) maxVoices = synth.voiceCount;
    pos += n;
    blocks++;
  }
  const renderMs = Date.now() - t0;

  // Keep pass 2 (index 1); compare with pass 3 for the periodicity and seam checks.
  const a = S0 + L;
  const loopL = left.subarray(a, a + L), loopR = right.subarray(a, a + L);
  const stats = signalStats(loopL, loopR);
  let periodic = null, continuity = null;
  if (passes >= 3) {
    const b = S0 + 2 * L;
    let num = 0, den = 0, mx = 0;
    for (let i = 0; i < L; i++) {
      const dl = left[b + i] - loopL[i], dr = right[b + i] - loopR[i];
      num += dl * dl + dr * dr;
      den += loopL[i] * loopL[i] + loopR[i] * loopR[i];
      mx = Math.max(mx, Math.abs(dl), Math.abs(dr));
    }
    periodic = { diffRmsDb: +db(Math.sqrt(num / Math.max(den, 1e-30))).toFixed(1), diffPeakDbfs: +db(mx).toFixed(1) };
    // The continuous render goes from the cut's last sample into pass 3; the loop wraps into the
    // cut's first sample. Compare the 100 ms after the seam, and the step across it.
    const W = Math.min(L, Math.round(0.1 * SR));
    let m2 = 0;
    for (let i = 0; i < W; i++) m2 = Math.max(m2, Math.abs(left[b + i] - loopL[i]), Math.abs(right[b + i] - loopR[i]));
    const stepWrap = Math.max(Math.abs(loopL[0] - loopL[L - 1]), Math.abs(loopR[0] - loopR[L - 1]));
    const stepCont = Math.max(Math.abs(left[b] - loopL[L - 1]), Math.abs(right[b] - loopR[L - 1]));
    continuity = {
      maxDiff100msDbfs: +db(m2).toFixed(1),
      maxDiffVsPeakDb: +(db(m2) - db(stats.peak)).toFixed(1),
      stepAtWrap: +stepWrap.toExponential(3), stepInContinuousRender: +stepCont.toExponential(3),
    };
    if (continuity.maxDiffVsPeakDb > -60) warnings.push(`the wrap differs from the continuous render by ${continuity.maxDiffVsPeakDb} dB re peak: not periodic`);
  } else warnings.push('--passes 2: no periodicity check');
  if (maxVoices > 900) warnings.push(`${maxVoices} voices at once (cap 1024)`);
  if (stats.peak > 4) warnings.push(`peak ${stats.peak.toFixed(2)}: the mix is extremely hot (master.py will normalize, but check the velocities)`);

  const outDir = args.outDir || path.join(CACHE, 'build');
  fs.mkdirSync(outDir, { recursive: true });
  const wavPath = path.join(outDir, `${sum.cue}.loop.wav`);
  writeWavFloat(wavPath, [loopL, loopR], SR);
  if (args.full) writeWavFloat(path.join(outDir, `${sum.cue}.full.wav`), [left, right], SR);
  const report = {
    ...sum, sampleRate: SR, passes, keptPass: 2, renderSeconds: +(renderMs / 1000).toFixed(1),
    realtimeFactor: +((total / SR) / (renderMs / 1000)).toFixed(1), blocks, maxVoices,
    effects: lock.report, soundfont: { path: sf.path, sha256: sf.sha256 }, spessasynth: SPESSA_VERSION,
    peak: +stats.peak.toFixed(4), peakDbfs: +db(stats.peak).toFixed(2), rmsDbfs: +db(stats.rms).toFixed(2), dc: +stats.dc.toExponential(2),
    periodicity: periodic, seam: continuity, wav: wavPath, warnings,
  };
  fs.writeFileSync(path.join(outDir, `${sum.cue}.render.json`), JSON.stringify(report, null, 2) + '\n');
  return report;
}

function signalStats(l, r) {
  let peak = 0, s2 = 0, s1 = 0;
  for (let i = 0; i < l.length; i++) {
    const a = l[i], b = r[i];
    peak = Math.max(peak, Math.abs(a), Math.abs(b));
    s2 += a * a + b * b;
    s1 += a + b;
  }
  return { peak, rms: Math.sqrt(s2 / (2 * l.length || 1)), dc: s1 / (2 * l.length || 1) };
}

const db = (x) => 20 * Math.log10(Math.max(x, 1e-12));

/** 32-bit float WAV (WAVE_FORMAT_IEEE_FLOAT), interleaved. */
function writeWavFloat(file, chans, sr) {
  const n = chans[0].length, c = chans.length;
  const data = Buffer.alloc(n * c * 4);
  for (let i = 0, o = 0; i < n; i++) for (let k = 0; k < c; k++, o += 4) data.writeFloatLE(chans[k][i], o);
  const h = Buffer.alloc(58);
  h.write('RIFF', 0); h.writeUInt32LE(50 + data.length, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(18, 16); h.writeUInt16LE(3, 20); h.writeUInt16LE(c, 22);
  h.writeUInt32LE(sr, 24); h.writeUInt32LE(sr * c * 4, 28); h.writeUInt16LE(c * 4, 32); h.writeUInt16LE(32, 34); h.writeUInt16LE(0, 36);
  h.write('fact', 38); h.writeUInt32LE(4, 42); h.writeUInt32LE(n, 46);
  h.write('data', 50); h.writeUInt32LE(data.length, 54);
  fs.writeFileSync(file, Buffer.concat([h, data]));
}

// ------------------------------------------------------------------------- procedural reference

/** Renders the current procedural music loops (js/audio/loops.js, imported read-only). */
async function renderReference(args) {
  const loops = await import(pathToFileURL(path.join(GAME, 'js/audio/loops.js')).href);
  const outDir = args.outDir || path.join(CACHE, 'reference');
  fs.mkdirSync(outDir, { recursive: true });
  const out = {};
  for (const name of loops.MUSIC_NAMES) {
    const t = Date.now();
    const buf = loops.renderLoop(name);
    const sr = loops.LOOPS[name].sr;
    const file = path.join(outDir, `${name}.procedural.wav`);
    writeWavFloat(file, [buf], sr);
    out[name] = { file, sr, seconds: +(buf.length / sr).toFixed(3), renderMs: Date.now() - t };
  }
  console.log(JSON.stringify(out, null, 2));
}

// ------------------------------------------------------------------------------------- musicbank

/** Pre-skip, decoded length and tags of an Ogg Opus file (single logical stream). */
function oggOpusInfo(file) {
  const d = fs.readFileSync(file);
  let pos = 0, cur = [], lastGranule = -1n;
  const packets = [];
  while (pos < d.length) {
    if (d.toString('latin1', pos, pos + 4) !== 'OggS') throw new Error(`${file}: no Ogg page at byte ${pos}`);
    const granule = d.readBigInt64LE(pos + 6);
    const nseg = d[pos + 26];
    let body = pos + 27 + nseg;
    for (let s = 0; s < nseg; s++) {
      const len = d[pos + 27 + s];
      if (packets.length < 2) cur.push(d.subarray(body, body + len));
      body += len;
      if (len < 255 && packets.length < 2) { packets.push(Buffer.concat(cur)); cur = []; }
    }
    if (granule >= 0n) lastGranule = granule;
    pos = body;
  }
  const head = packets[0];
  if (!head || head.toString('latin1', 0, 8) !== 'OpusHead') throw new Error(`${file}: not Ogg Opus`);
  const preSkip = head.readUInt16LE(10);
  const tags = {};
  const t = packets[1];
  if (t && t.toString('latin1', 0, 8) === 'OpusTags') {
    let p = 8;
    const vl = t.readUInt32LE(p); p += 4 + vl;
    const count = t.readUInt32LE(p); p += 4;
    for (let i = 0; i < count; i++) {
      const cl = t.readUInt32LE(p);
      const s = t.toString('utf8', p + 4, p + 4 + cl);
      const eq = s.indexOf('=');
      tags[s.slice(0, eq).toUpperCase()] = s.slice(eq + 1);
      p += 4 + cl;
    }
  }
  return { channels: head[9], preSkip, samples: Number(lastGranule) - preSkip, tags };
}

function writeBank(args) {
  const musicDir = args.musicDir || path.join(GAME, 'audio/music');
  const bankOut = args.bankOut || path.join(GAME, 'js/audio/musicbank.js');
  const rows = [];
  for (const cue of CUE_NAMES) {
    const file = path.join(musicDir, `${cue}.ogg`);
    if (!fs.existsSync(file)) continue;
    const info = oggOpusInfo(file);
    const dur = info.samples / SR;
    const bpm = Number(info.tags.BPM);
    const lufs = Number(info.tags.LUFS);
    if (!(dur > 0) || !Number.isFinite(bpm) || !Number.isFinite(lufs)) die(`${file}: missing loop length or BPM/LUFS tags (encode it with master.py)`);
    // The game's URL for the cue, whichever directory the metadata was read from.
    const rel = `audio/music/${cue}.ogg`;
    // dur: the exact loop length (samples / 48000, to the microsecond); bpm: an integer when it is one.
    const bpmLit = Number.isInteger(bpm) ? String(bpm) : num(bpm, 3);
    rows.push(`  ${cue}: Object.freeze({ file: '${rel}', dur: ${num(dur, 6)}, bpm: ${bpmLit}, lufs: ${num(lufs, 1)} }),`);
  }
  const src = [
    '// GENERATED by spartan/tools/music/render.mjs. Do not edit by hand.',
    '/** Pre-rendered music cues: seamless Ogg Opus loops. */',
    'export const MUSICBANK = Object.freeze({',
    ...rows,
    '});',
    '',
  ].join('\n');
  fs.mkdirSync(path.dirname(bankOut), { recursive: true });
  fs.writeFileSync(bankOut, src);
  console.log(`wrote ${bankOut} (${rows.length} cue${rows.length === 1 ? '' : 's'})`);
}

/** A JS number literal with at most `digits` decimals and at least one (84 -> 84.0). */
function num(x, digits) {
  let s = (+x.toFixed(digits)).toString();
  if (!s.includes('.') && !s.includes('e')) s += '.0';
  return s;
}

// ------------------------------------------------------------------------------------------ main

const args = parseArgs(process.argv.slice(2));
if (args.bank) {
  writeBank(args);
} else if (args.reference) {
  await renderReference(args);
} else {
  if (!args.files.length) usage(2);
  const lib = await loadSpessa();
  for (const f of args.files) {
    if (args.check) {
      const P = plan(lib, f, args.json, { cue: args.cue, offbar: args.offbar });
      console.log(JSON.stringify({ ...P.sum, warnings: P.warnings }, null, 2));
      continue;
    }
    const r = await renderCue(lib, f, args);
    const { cue, loopSamples, loopSeconds, bars, bpm, periodicity, seam, renderSeconds, realtimeFactor, peakDbfs, maxVoices, warnings, wav } = r;
    console.log(JSON.stringify({ cue, wav, loopSamples, loopSeconds, bars, bpm, peakDbfs, maxVoices, periodicity, seam, renderSeconds, realtimeFactor, warnings }, null, 2));
  }
}
