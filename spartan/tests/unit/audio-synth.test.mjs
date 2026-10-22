// Unit tests for the pure audio synthesis (js/audio/dsp.js, sfx.js, loops.js) and for the
// parts of audio.js that work without WebAudio (event wiring for voice lines).
// Run: node --test spartan/tests/unit/audio-synth.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { stats, spectrum, seam } from './audio-analysis.mjs';
import { BANDS } from './audio-expect.mjs';
import { SFX, SFX_NAMES, renderSfx, sfxLength, sfxRate } from '../../src/main/assets/game/js/audio/sfx.js';
import { LOOPS, renderLoop, loopLength, renderImpulse } from '../../src/main/assets/game/js/audio/loops.js';
import {
  Biquad, Reson, noteFreq, loopFreq, processCircular, addModes, normalize, measure, createNoise,
} from '../../src/main/assets/game/js/audio/dsp.js';
import { createAudio, SPEAKERS } from '../../src/main/assets/game/js/audio/audio.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';

const SR = 48000;

test('dsp: filters, tuning and loop helpers', () => {
  const lp = new Biquad().lowpass(SR, 1000);
  let y = 0;
  for (let i = 0; i < 4000; i++) y = lp.process(1);
  assert.ok(Math.abs(y - 1) < 1e-6, 'low-pass DC gain is 1');
  const r = new Reson().set(SR, 700, 90);
  for (let i = 0; i < 20000; i++) y = r.process(1);
  assert.ok(Math.abs(y - 1) < 1e-6, 'formant resonator DC gain is 1');
  assert.equal(noteFreq('A4'), 440);
  assert.ok(Math.abs(noteFreq('D4') - 293.665) < 0.01);
  assert.ok(Math.abs(noteFreq('Eb3') - 155.563) < 0.01);
  const f = loopFreq(146.83, 33.3333);
  assert.ok(Math.abs(f * 33.3333 - Math.round(f * 33.3333)) < 1e-9, 'loopFreq gives whole cycles per loop');
  // A circular one-pole over a periodic input equals the filter's periodic steady state.
  const n = 4800, buf = new Float32Array(n), src = new Float32Array(n);
  for (let i = 0; i < n; i++) src[i] = buf[i] = Math.sign(Math.sin(2 * Math.PI * 10 * i / n));
  let s = 0;
  processCircular(buf, 2400, (x) => (s += 0.01 * (x - s)));
  let z = 0, worst = 0;
  for (let k = 0; k < 6; k++) {
    for (let i = 0; i < n; i++) {
      z += 0.01 * (src[i] - z);
      if (k === 5) worst = Math.max(worst, Math.abs(z - buf[i]));
    }
  }
  assert.ok(worst < 1e-5, `circular processing matches the steady state (${worst})`);
});

test('dsp: modal decay and normalization', () => {
  const out = new Float32Array(SR);
  addModes(out, SR, 0, [{ f: 500, amp: 1, t60: 0.5, phase: 0 }]);
  const early = measure(out.subarray(0, 2400), SR).peak, late = measure(out.subarray(24000, 26400), SR).peak;
  assert.ok(late < early * 0.002, `mode decays 60 dB in t60 (early ${early}, late ${late})`);
  const noise = createNoise(3);
  const b = new Float32Array(SR);
  for (let i = 0; i < b.length; i++) b[i] = noise() * 3;
  normalize(b, SR, { momentary: 10, peak: 0.89 });
  assert.ok(Math.abs(measure(b, SR).peak - 0.89) < 1e-6, 'normalize respects the peak ceiling');
});

test('sfx: every DESIGN.md name exists', () => {
  for (const name of SFX_NAMES) assert.ok(SFX[name], `missing ${name}`);
  assert.equal(SFX_NAMES.length, 30);
});

test('sfx: every variant renders clean, balanced and in its spectral band', () => {
  let bytes = 0;
  for (const name of Object.keys(SFX)) {
    const spec = SFX[name];
    const sr = sfxRate(name, SR);
    for (let v = 0; v < spec.variants; v++) {
      const buf = renderSfx(name, sr, v);
      bytes += buf.length * 4;
      const s = stats(buf, sr);
      assert.equal(buf.length, sfxLength(name, sr));
      assert.equal(s.nan, 0, `${name}:${v} NaN`);
      assert.ok(s.peak <= 0.98 && s.peak > 0.05, `${name}:${v} peak ${s.peak}`);
      assert.ok(s.momentary >= spec.loud * 0.5 && s.momentary <= spec.loud * 1.05, `${name}:${v} loudness ${s.momentary} vs ${spec.loud}`);
      assert.ok(Math.abs(buf[0]) < 0.05 && Math.abs(buf[buf.length - 1]) < 1e-3, `${name}:${v} ends`);
      const c = spectrum(buf, sr).centroid;
      const band = BANDS[name];
      assert.ok(c >= band[0] && c <= band[1], `${name}:${v} centroid ${c.toFixed(0)} not in ${band}`);
    }
  }
  // Loudness balance: impacts, whooshes, voices and UI stay within 10 dB of each other.
  const louds = Object.values(SFX).map((s) => s.loud);
  assert.ok(20 * Math.log10(Math.max(...louds) / Math.min(...louds)) < 10.5);
  // Buffer memory at 48 kHz: SFX + every loop + the reverb IR stays under ~25 MB.
  for (const name of Object.keys(LOOPS)) bytes += loopLength(name) * 4;
  bytes += Math.round(1.3 * SR) * 8;
  assert.ok(bytes / 1048576 <= 23, `buffer memory ${(bytes / 1048576).toFixed(1)} MB`);
});

test('loops: periodic, seamless, unclipped and in band', () => {
  for (const name of Object.keys(LOOPS)) {
    const L = LOOPS[name];
    const buf = renderLoop(name);
    assert.equal(buf.length, loopLength(name));
    if (L.kind === 'music') assert.equal(buf.length % 64, 0, `${name}: control-rate blocks tile the loop`);
    const s = stats(buf, L.sr);
    assert.equal(s.nan, 0, `${name} NaN`);
    assert.ok(s.peak <= 0.98, `${name} peak ${s.peak}`);
    assert.ok(s.rms > 0.02, `${name} too quiet`);
    const se = seam(buf);
    assert.ok(se.jump <= se.p999jump, `${name} seam jump ${se.jump} > ${se.p999jump}`);
    assert.ok(se.curv <= se.p999curv, `${name} seam curvature ${se.curv} > ${se.p999curv}`);
    const c = spectrum(buf, L.sr).centroid;
    assert.ok(c >= BANDS[name][0] && c <= BANDS[name][1], `${name} centroid ${c.toFixed(0)} not in ${BANDS[name]}`);
  }
});

test('impulse response: stereo, decaying, energy-normalized', () => {
  const [l, r] = renderImpulse(SR, 1.3);
  assert.equal(l.length, Math.round(1.3 * SR));
  let e = 0, eTail = 0, corr = 0;
  for (let i = 0; i < l.length; i++) {
    e += l[i] * l[i];
    if (i > l.length * 0.8) eTail += l[i] * l[i];
    corr += l[i] * r[i];
  }
  assert.ok(Math.abs(e - 1) < 1e-3);
  assert.ok(eTail < e * 0.01, 'tail decays');
  assert.ok(Math.abs(corr) < 0.2, 'channels are decorrelated');
});

test('audio.js without WebAudio: safe no-ops, and voice lines still reach the platform', () => {
  const events = new EventBus();
  const said = [];
  const ctx = { settings: { voice: true }, events, platform: { voice: { say: (text, o) => said.push({ text, ...o }), stop() {} } } };
  const audio = createAudio(ctx);
  for (const k of ['unlock', 'play', 'setAmbience', 'setMusic', 'applyVolumes', 'suspend', 'resume', 'update']) assert.equal(typeof audio[k], 'function', k);
  assert.equal(audio.state, 'unavailable');
  assert.equal(audio.play('clang'), null);
  audio.setMusic('battle');
  audio.setAmbience('battle');
  audio.update(0.016);
  audio.unlock();
  audio.suspend();
  audio.resume();
  events.emit('hit', { target: { pos: { x: 0, y: 0, z: -2 } }, attacker: 'player', byPlayer: true, kind: 'slash' });
  events.emit('voice', { text: 'Come and take them.', speaker: 'Leonidas', greek: 'ΜΟΛΩΝ ΛΑΒΕ' });
  events.emit('voice', { text: 'Their arrows will hide the sun!', speaker: 'Trachinian' });
  events.emit('voice', { speaker: 'Leonidas' });
  assert.deepEqual(said, [
    { text: 'Come and take them.', ...SPEAKERS.Leonidas },
    { text: 'Their arrows will hide the sun!', ...SPEAKERS.Trachinian },
  ]);
});
