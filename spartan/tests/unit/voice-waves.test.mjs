// Voice lines in the campaign (§13): waves picks each Greek line's recorded variant, times the
// queue by the clip, hands Leonidas' battle lines to Dienekes once he falls, praises every other
// respite and calls the Kolonos; audio.js speaks a clip's English through TTS when it can't play it.
// node --test spartan/tests/unit/voice-waves.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DAYS, VOICE, LEONIDAS_BATTLE_LINES } from '../../src/main/assets/game/js/game/campaign.js';
import { createWaves, lineSeconds, voiceSeconds } from '../../src/main/assets/game/js/game/waves.js';
import { VOICEBANK } from '../../src/main/assets/game/js/audio/voicebank.js';
import { createAudio, SPEAKERS } from '../../src/main/assets/game/js/audio/audio.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';
import { createRng } from '../../src/main/assets/game/js/core/math.js';

const GAME = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../src/main/assets/game');
const GAP = 0.35; // waves' breath after a recorded line
const DT = 0.05;

/** A fake world around createWaves (as in ai-waves): melee enemies die `killAfter` s after they spawn. */
function harness({ killAfter = 3, readyAfter = null } = {}) {
  const events = new EventBus();
  const log = [];
  const t = { gnow: 0 };
  const actors = [];
  const calls = { preload: [] };
  const ctx = {
    events, rng: createRng(7), time: t, actors,
    settings: { control: 'touch' },
    player: { maxHp: 100, hp: 100, kills: 0, score: 0, heal() {}, refillSpears() {} },
    combat: { volley() {} },
    world: { setTimeOfDay() {} },
    audio: { setMusic() {}, preloadVoice: (files) => calls.preload.push({ t: t.gnow, files }) },
    hud: { banner() {} },
    ai: {
      spawnEnemy(kind, x, z) {
        const a = { kind, role: kind === 'archer' ? 'archer' : 'melee', alive: true, born: t.gnow, pos: { x, z } };
        actors.push(a);
        return a;
      },
      get enemiesAlive() { return actors.filter((a) => a.alive && a.role === 'melee').length; },
      playerAttackers: [],
      ensureAllies() {}, spawnHelot() {}, killAlly() {}, clearCorpses() {},
      retreatArchers() { for (const a of actors) if (a.role === 'archer') a.alive = false; },
    },
  };
  for (const n of ['dayStart', 'waveStart', 'waveCleared', 'respite', 'phase', 'voice']) events.on(n, (e) => log.push({ n, t: +t.gnow.toFixed(3), ...e }));
  const waves = createWaves(ctx);
  ctx.waves = waves;
  const step = () => {
    t.gnow += DT;
    for (const a of actors) if (a.alive && a.role === 'melee' && t.gnow - a.born > killAfter) a.alive = false;
    ctx.ai.playerAttackers.length = actors.some((a) => a.alive && a.role === 'melee') ? 1 : 0;
    if (readyAfter !== null && waves.inRespite) {
      const r = log.filter((e) => e.n === 'respite').pop();
      if (r && t.gnow - r.t >= readyAfter) waves.ready();
    }
    waves.update(DT);
  };
  const until = (f, max = 3000) => {
    for (let i = 0; i < max / DT && !f(); i++) step();
    assert.ok(f(), 'condition reached');
  };
  const voices = () => log.filter((e) => e.n === 'voice');
  return { ctx, waves, log, calls, step, until, t, voices };
}

/** Index of the bank variant a payload came from (by its clip file), or -1. */
function variantOf(v) {
  const list = VOICEBANK[v.key] || [];
  return list.findIndex((x) => x.clips[v.speaker] && x.clips[v.speaker].file === (v.clip && v.clip.file));
}

/** The payload is exactly the bank's variant for its speaker. */
function assertFromBank(v) {
  const i = variantOf(v);
  assert.ok(i >= 0, `${v.key} by ${v.speaker}: clip ${v.clip && v.clip.file} is not his`);
  const b = VOICEBANK[v.key][i];
  assert.equal(v.text, b.en);
  assert.equal(v.greek, b.greek);
  assert.deepEqual(v.clip, { file: b.clips[v.speaker].file, dur: b.clips[v.speaker].dur });
  return i;
}

test('lineSeconds: a clip holds the queue for its length and a breath; without one, the TTS estimate', () => {
  assert.equal(lineSeconds({ text: 'Hold the line!', clip: { file: 'a.ogg', dur: 1.25 } }), 1.25 + GAP);
  assert.equal(lineSeconds({ text: 'Hold the line!', clip: null }), voiceSeconds('Hold the line!'));
  assert.equal(lineSeconds({ text: 'Hold the line!', clip: { file: 'a.ogg', dur: NaN } }), voiceSeconds('Hold the line!'));
});

test('Day 1 intro: the herald, then Leonidas, each from his clip; the next line waits for the clip', () => {
  const h = harness();
  h.waves.start(1);
  h.until(() => h.log.some((e) => e.n === 'waveStart'));
  const [herald, molon] = h.voices();
  assert.equal(herald.key, 'herald');
  assert.equal(herald.speaker, 'Herald');
  assertFromBank(herald);
  assert.equal(molon.key, 'molonLabe');
  assert.equal(molon.speaker, 'Leonidas');
  assert.equal(molon.greek, 'ΜΟΛΩΝ ΛΑΒΕ');
  assertFromBank(molon);
  assert.ok(Math.abs(herald.t - 1.2) < DT + 1e-6, `herald at ${herald.t}`);
  const gap = molon.t - herald.t;
  assert.ok(Math.abs(gap - (herald.clip.dur + GAP)) < DT + 1e-6, `Leonidas answers ${gap} s after the herald (clip ${herald.clip.dur} s)`);
  const ws = h.log.find((e) => e.n === 'waveStart').t;
  const want = Math.max(6, 1.2 + herald.clip.dur + GAP + molon.clip.dur + GAP + 1.2);
  assert.ok(Math.abs(ws - want) < DT + 1e-6, `waveStart at ${ws}, want ${want}`);
});

test('barks cycle through their variants, and Dienekes takes them over with his own clips', () => {
  const h = harness({ readyAfter: 6 });
  h.waves.start(1);
  h.until(() => h.waves.phase === 'C' && h.waves.state === 'fighting', 20000);
  for (let i = 0; i < 60 / DT; i++) h.step();
  const fallAt = h.log.find((e) => e.n === 'phase' && e.phase === 'B').t + 1.0;
  const vs = h.voices();
  for (const key of LEONIDAS_BATTLE_LINES) {
    const said = vs.filter((v) => v.key === key);
    assert.ok(said.length >= 3, `${key} said ${said.length} times`);
    const idx = said.map(assertFromBank);
    const n = VOICEBANK[key].length;
    assert.deepEqual(idx, idx.map((_, k) => k % n), `${key} variants in order`);
    for (const v of said) assert.equal(v.speaker, v.t > fallAt + 1e-6 ? 'Dienekes' : 'Leonidas', `${key} at ${v.t}`);
    if (key !== 'holdTheLine') assert.ok(said.some((v) => v.speaker === 'Dienekes'), `Dienekes says ${key}`);
  }
  // Every Greek line came with its clip, and nobody talked over a recorded line.
  for (const v of vs) {
    if (VOICE[v.key].speaker !== 'Narrator') assertFromBank(v);
  }
  for (let i = 1; i < vs.length; i++) {
    const prev = vs[i - 1];
    if (vs[i].key === 'shieldsUp') continue; // spoken at once at the volley warning, cutting in
    assert.ok(vs[i].t - prev.t >= lineSeconds(prev) - DT - 1e-6, `${vs[i].key} at ${vs[i].t} cuts ${prev.key} at ${prev.t}`);
  }
});

test('every other respite gets a word of praise, from Dienekes once Leonidas has fallen', () => {
  // Day 1: four respites (three in the morning, one at dusk): praise after the 1st and the 3rd.
  const h = harness({ killAfter: 1 });
  h.waves.start(1);
  h.until(() => h.log.filter((e) => e.n === 'respite').length === 4 && h.waves.state === 'fighting', 5000);
  const rs = h.log.filter((e) => e.n === 'respite');
  const praise = h.voices().filter((v) => v.key === 'respite');
  assert.equal(praise.length, 2);
  for (const [p, r] of [[praise[0], rs[0]], [praise[1], rs[2]]]) {
    assert.ok(p.t >= r.t + 1.0 - 1e-6 && p.t < r.t + r.seconds, `praise at ${p.t} for the respite at ${r.t}`);
    assert.equal(p.speaker, 'Leonidas');
  }
  assert.deepEqual(praise.map(assertFromBank), [0, 1]);
  // Day 3: phase A respites, then the lulls between the surges over Leonidas' body.
  const d3 = harness({ killAfter: 1 });
  d3.waves.start(3);
  d3.until(() => d3.waves.phase === 'C', 5000);
  const cleared = d3.log.filter((e) => e.n === 'waveCleared');
  const said = d3.voices().filter((v) => v.key === 'respite');
  // Cleared: A1 (respite #1), A2 (#2), A3 (to phase B), B1 (lull #3), B2 (#4), B3 (#5), B4 (to phase C).
  const after = (c) => said.find((v) => v.t >= c.t && v.t < c.t + 6);
  assert.deepEqual(cleared.map(after).map((v) => v && v.speaker), ['Leonidas', undefined, undefined, 'Dienekes', undefined, 'Dienekes', undefined]);
  for (const v of said) assertFromBank(v);
});

test('phase C: Dienekes calls them to the Kolonos as the fade ends, before the last war cry', () => {
  const h = harness({ killAfter: 1 });
  h.waves.start(3);
  h.until(() => h.waves.phase === 'C' && h.waves.state === 'fighting', 5000);
  for (let i = 0; i < 1 / DT; i++) h.step(); // the war cry is queued at waveStart
  const cAt = h.log.find((e) => e.n === 'phase' && e.phase === 'C').t;
  const vs = h.voices().filter((v) => v.t >= cAt);
  const kol = vs.find((v) => v.key === 'kolonos');
  assert.ok(kol, 'kolonos said');
  assert.equal(kol.speaker, 'Dienekes');
  assertFromBank(kol);
  assert.ok(Math.abs(kol.t - (cAt + DAYS[3].phases[2].fade + 0.6)) < DT + 1e-6, `kolonos at +${kol.t - cAt}`);
  const cry = vs.find((v) => v.key === 'warcry' && v.t > kol.t);
  assert.ok(cry && cry.speaker === 'Dienekes', 'the last-stand war cry follows');
  assert.ok(cry.t >= kol.t + kol.clip.dur + GAP - DT - 1e-6);
  assert.ok(h.voices().filter((v) => v.key === 'kolonos').length === 1);
});

test('the Narrator keeps the English line for text-to-speech, with no clip and no Greek', () => {
  const h = harness({ killAfter: 1 });
  h.waves.start(2);
  h.until(() => h.voices().some((v) => v.key === 'tegeans'));
  const v = h.voices().find((x) => x.key === 'tegeans');
  assert.deepEqual(v.speaker, 'Narrator');
  assert.equal(v.text, VOICE.tegeans.text);
  assert.equal(v.clip, undefined);
  assert.equal(v.greek, undefined);
});

test('each part of the campaign asks audio to preload the clips it will need', () => {
  const clipsOf = (key, sp) => VOICEBANK[key].map((x) => x.clips[sp] && x.clips[sp].file).filter(Boolean);
  const h = harness({ killAfter: 1e9 });
  h.waves.start(1);
  assert.equal(h.calls.preload.length, 1);
  const d1 = h.calls.preload[0].files;
  for (const f of [...clipsOf('herald', 'Herald'), ...clipsOf('molonLabe', 'Leonidas'), ...LEONIDAS_BATTLE_LINES.flatMap((k) => clipsOf(k, 'Leonidas'))]) {
    assert.ok(d1.includes(f), `Day 1 preloads ${f}`);
  }
  assert.ok(!d1.some((f) => f.endsWith('-dienekes.ogg')), 'Dienekes waits for Day 3 (or the first volley)');
  assert.equal(new Set(d1).size, d1.length);
  const h3 = harness({ killAfter: 1e9 });
  h3.waves.start(3);
  const d3 = h3.calls.preload[0].files;
  for (const f of [...clipsOf('anopaia', 'Lookout'), ...clipsOf('breakfast', 'Leonidas'), ...clipsOf('swords', 'Dienekes'), ...clipsOf('kolonos', 'Dienekes'),
    ...LEONIDAS_BATTLE_LINES.flatMap((k) => [...clipsOf(k, 'Leonidas'), ...clipsOf(k, 'Dienekes')])]) {
    assert.ok(d3.includes(f), `Day 3 preloads ${f}`);
  }
  for (const f of [...d1, ...d3]) assert.ok(fs.existsSync(path.join(GAME, f)), f);
});

test('audio.js without WebAudio speaks a recorded line in English through TTS and never throws', () => {
  const events = new EventBus();
  const said = [];
  const ctx = { settings: { voice: true }, events, platform: { voice: { say: (text, o) => said.push({ text, ...o }), stop() {} } } };
  const audio = createAudio(ctx);
  assert.equal(typeof audio.preloadVoice, 'function');
  audio.preloadVoice(['audio/voice/molonLabe-1-leonidas.ogg']);
  const b = VOICEBANK.molonLabe[0];
  events.emit('voice', { key: 'molonLabe', speaker: 'Leonidas', text: b.en, greek: b.greek, clip: b.clips.Leonidas });
  events.emit('voice', { speaker: 'Narrator', text: 'Raise your shield.' });
  for (const odd of [{ clip: { file: 7 }, text: 'Odd.' }, { clip: 'x', speaker: 'Leonidas' }, { clip: null }, {}, { text: 5, speaker: {} }]) {
    events.emit('voice', odd);
  }
  assert.deepEqual(said.slice(0, 2), [
    { text: b.en, ...SPEAKERS.Leonidas },
    { text: 'Raise your shield.', ...SPEAKERS.Narrator },
  ]);
  assert.equal(audio.stats().voice.started, 0);
  audio.dispose();
});

// ---- Review follow-ups -----------------------------------------------------------------------

test('a recorded line that has to be spoken in English instead holds the queue for the English', () => {
  const line = { text: 'The Phocians have abandoned the Anopaia path! Ephialtes is leading the Immortals over the mountain!', clip: { file: 'a.ogg', dur: 4.78 } };
  assert.equal(lineSeconds(line, true), 4.78 + GAP);
  assert.equal(lineSeconds(line, false), Math.max(4.78 + GAP, voiceSeconds(line.text)));
  assert.ok(lineSeconds(line, false) > 4.78 + GAP, 'the English is longer than the Greek here');
  assert.equal(lineSeconds({ text: 'Alalai!', clip: { file: 'a.ogg', dur: 1.9 } }, false), 1.9 + GAP, 'never shorter than the clip');
});

test('waves times lines by the English when audio says recorded lines cannot play', () => {
  const h = harness();
  h.ctx.audio.canPlayClips = () => false;
  h.waves.start(1);
  h.until(() => h.voices().length >= 2);
  const [herald, molon] = h.voices();
  const want = Math.max(herald.clip.dur + GAP, voiceSeconds(herald.text));
  assert.ok(Math.abs(molon.t - herald.t - want) < DT + 1e-6, `Leonidas answers ${(molon.t - herald.t).toFixed(2)} s after the herald, want ${want.toFixed(2)}`);
});

test('audio reports whether recorded lines can play (no WebAudio: no)', () => {
  const audio = createAudio({ settings: { voice: true }, events: new EventBus() });
  assert.equal(audio.canPlayClips(), false);
  audio.dispose();
});
