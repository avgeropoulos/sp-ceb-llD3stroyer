// Balance fixes in game/waves.js, game/campaign.js and ui/menus.js (review B, campaign lens):
// pacing (vanguard, respite pre-spawn), volleys fed by archers, the Day 3 spear beats, respite
// spear top-ups, the 'Shields up!' throttle, restart at the current part of the day, pause labels.
// node --test spartan/tests/unit/balance-waves.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { DAYS, VOICE, RESPITE, SPAWN, firstEntryOfDay } from '../../src/main/assets/game/js/game/campaign.js';
import { createWaves, lineSeconds } from '../../src/main/assets/game/js/game/waves.js';
import { pickVoiceLine } from '../../src/main/assets/game/js/audio/voicelines.js';
import { VOICEBANK } from '../../src/main/assets/game/js/audio/voicebank.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';
import { createRng } from '../../src/main/assets/game/js/core/math.js';
import { pauseKicker } from '../../src/main/assets/game/js/ui/menus.js';

const DT = 0.05;

/**
 * A fake world around createWaves. Melee enemies die `killAfter` s after they set out (idle
 * vanguard men don't count down until released). Archers live until `killArchers()`.
 */
function harness({ motion = false, killAfter = 3, spears = 4, archersApi = true } = {}) {
  const events = new EventBus();
  const log = [];
  const t = { gnow: 0 };
  const actors = [];
  const calls = { volley: [], refill: [], drop: [], spawns: [] };
  const player = {
    maxHp: 100, hp: 100, kills: 0, score: 0, spares: spears - 1, hasSpearInHand: true,
    get spearsLeft() { return this.spares + (this.hasSpearInHand ? 1 : 0); },
    heal() {},
    refillSpears(n) {
      calls.refill.push({ t: t.gnow, n, before: this.spearsLeft });
      const total = n > 0 ? Math.min(4, n) : 4;
      this.hasSpearInHand = true;
      this.spares = Math.max(this.spares, total - 1);
    },
    dropSpears(o) {
      calls.drop.push({ t: t.gnow, keepInHand: o && o.keepInHand });
      this.spares = 0;
      if (!(o && o.keepInHand)) this.hasSpearInHand = false;
    },
  };
  const ai = {
    spawnEnemy(kind, x, z, opts) {
      const a = { kind, role: kind === 'archer' ? 'archer' : 'melee', alive: true, born: t.gnow, pos: { x, z }, state: opts && opts.delay > 0 ? 'idle' : 'advance', delay: opts && opts.delay > 0 ? opts.delay : 0, fromBehind: !!(opts && opts.fromBehind) };
      actors.push(a);
      calls.spawns.push({ t: t.gnow, kind, x, z, delay: a.delay, a });
      return a;
    },
    get enemiesAlive() { return actors.filter((a) => a.alive && a.role === 'melee').length; },
    playerAttackers: [],
    ensureAllies() {}, spawnHelot() {}, killAlly() {}, clearCorpses() {},
    retreatArchers() { for (const a of actors) if (a.role === 'archer' && a.alive) a.state = 'retreat'; },
  };
  if (archersApi) {
    Object.defineProperty(ai, 'archersShooting', { get: () => actors.filter((a) => a.alive && a.role === 'archer' && a.state !== 'retreat').length });
    Object.defineProperty(ai, 'archersAlive', { get: () => actors.filter((a) => a.alive && a.role === 'archer').length });
  }
  const ctx = {
    events, rng: createRng(5), time: t, actors, player, ai,
    settings: { control: motion ? 'motion' : 'touch' },
    combat: { volley: (o) => calls.volley.push({ t: t.gnow, ...o }) },
    world: { setTimeOfDay() {} }, audio: { setMusic() {} }, hud: { banner() {} },
  };
  for (const n of ['dayStart', 'waveStart', 'waveCleared', 'respite', 'phase', 'voice', 'gameOver']) events.on(n, (e) => log.push({ n, t: +t.gnow.toFixed(3), ...e }));
  const waves = createWaves(ctx);
  ctx.waves = waves;
  const step = (dt = DT) => {
    t.gnow += dt;
    for (const a of actors) {
      if (!a.alive) continue;
      if (a.state === 'idle') { a.delay -= dt; if (a.delay <= 0) { a.state = 'advance'; a.setOut = t.gnow; } continue; }
      if (a.setOut === undefined) a.setOut = a.born;
      if (a.role === 'melee' && t.gnow - a.setOut > killAfter) a.alive = false;
    }
    waves.update(dt);
  };
  const run = (s) => { for (let i = 0, n = Math.round(s / DT); i < n; i++) step(); };
  const until = (f, max = 2000) => { for (let i = 0; i < max / DT && !f(); i++) step(); assert.ok(f(), 'condition reached'); };
  const killArchers = (n = Infinity) => { for (const a of actors) if (n > 0 && a.alive && a.role === 'archer') { a.alive = false; n--; } };
  return { ctx, waves, log, calls, step, run, until, t, events, actors, player, killArchers };
}

test('Day 1: the vanguard stands at z ≈ −16 through the intro and sets out at waveStart', () => {
  const h = harness({ killAfter: 1e9 });
  h.waves.start(1);
  const van = h.calls.spawns.filter((s) => s.t === 0);
  assert.equal(van.length, DAYS[0].vanguard.count);
  assert.ok(van.every((s) => s.kind === 'levy' && s.a.state === 'idle' && Math.abs(s.z - -16) <= 0.85 && Math.abs(s.x) < 3.5));
  // The herald and Leonidas: each line holds the intro for its recorded clip (or its TTS estimate).
  const clipOf = (k) => pickVoiceLine(VOICEBANK, k, VOICE[k].speaker, 0);
  const intro = Math.max(5 + 1, 1.2 + DAYS[0].intro.reduce((s, k) => s + lineSeconds({ text: VOICE[k].text, clip: clipOf(k) }), 0) + 1.2);
  h.until(() => h.log.some((e) => e.n === 'waveStart'));
  const ws = h.log.find((e) => e.n === 'waveStart').t;
  assert.ok(Math.abs(ws - intro) < 0.06, `waveStart at ${ws}, intro ${intro}`);
  // Nobody moved during the intro; they all set out within ~1 s of the wave starting.
  h.run(1.2);
  for (const s of van) assert.ok(s.a.setOut >= ws - 0.06 && s.a.setOut <= ws + 1.0, `set out at ${s.a.setOut}`);
  // They are the first four of the wave's 8: only 4 more spawn, at z −26…−20.
  h.until(() => {
    for (const a of h.actors) a.alive = false;
    return h.log.some((e) => e.n === 'waveCleared');
  });
  const rest = h.calls.spawns.filter((s) => s.t > 0 && s.t <= h.log.find((e) => e.n === 'waveCleared').t);
  assert.equal(rest.length, 8 - van.length);
  assert.ok(rest.every((s) => s.z >= SPAWN.front.z[0] && s.z <= SPAWN.front.z[1]));
});

test('restart at the start of the vanguard-free dusk entry does not place a vanguard', () => {
  const h = harness();
  h.waves.start(1, 1);
  h.run(0.2);
  assert.equal(h.calls.spawns.length, 0);
});

test('the next wave sets out 6 s before a respite ends and keeps its plan (touch 10 s, motion 18 s)', () => {
  for (const motion of [false, true]) {
    const h = harness({ motion, killAfter: 1 });
    h.waves.start(1);
    h.until(() => h.log.some((e) => e.n === 'respite'));
    const r = h.log.find((e) => e.n === 'respite');
    assert.equal(r.seconds, motion ? RESPITE.motion : RESPITE.touch);
    h.until(() => h.log.filter((e) => e.n === 'waveStart').length === 2);
    const ws2 = h.log.filter((e) => e.n === 'waveStart')[1].t;
    assert.ok(Math.abs(ws2 - r.t - r.seconds) < 0.06);
    const early = h.calls.spawns.filter((s) => s.t > r.t && s.t < ws2);
    assert.ok(early.length >= 2, `pre-spawned ${early.length}`);
    assert.ok(Math.abs(early[0].t - (ws2 - SPAWN.respiteLead)) < 0.06, `first set out ${ws2 - early[0].t} s before the wave`);
    assert.ok(early.every((s) => s.kind === 'levy' && s.z >= -26 && s.z <= -20));
    // Wave 2 (12 levies) spawns exactly its 12 men in all.
    h.until(() => h.log.filter((e) => e.n === 'waveCleared').length === 2);
    const c2 = h.log.filter((e) => e.n === 'waveCleared')[1].t;
    assert.equal(h.calls.spawns.filter((s) => s.t > r.t && s.t <= c2).length, 12);
  }
});

test('READY works after 3 s, and an early READY skips the pre-spawn without losing men', () => {
  const h = harness({ killAfter: 1 });
  h.waves.start(1);
  h.until(() => h.log.some((e) => e.n === 'respite'));
  const r = h.log.find((e) => e.n === 'respite').t;
  h.run(1);
  assert.equal(h.waves.canReady, false);
  assert.equal(h.waves.ready(), true);
  h.until(() => h.log.filter((e) => e.n === 'waveStart').length === 2);
  const ws2 = h.log.filter((e) => e.n === 'waveStart')[1].t;
  assert.ok(Math.abs(ws2 - r - RESPITE.readyAfter) < 0.06, `READY ended it after ${ws2 - r}`);
  h.until(() => h.log.filter((e) => e.n === 'waveCleared').length === 2);
  const c2 = h.log.filter((e) => e.n === 'waveCleared')[1].t;
  assert.equal(h.calls.spawns.filter((s) => s.t > r && s.t <= c2).length, 12);
});

test('volleys need archers: each archer down thins them, all down stops them', () => {
  // Day 2 wave 1: 3 archers, volleyEvery 26.
  const h = harness({ killAfter: 1e9 });
  h.waves.start(2);
  h.until(() => h.calls.volley.length === 1);
  assert.equal(h.calls.volley[0].count, 300);
  h.killArchers(1);
  h.until(() => h.calls.volley.length === 2);
  assert.equal(h.calls.volley[1].count, 200);
  h.killArchers(1);
  h.until(() => h.calls.volley.length === 3);
  assert.equal(h.calls.volley[2].count, 100);
  h.killArchers();
  const n = h.calls.volley.length;
  const shields = h.log.filter((e) => e.n === 'voice' && e.key === 'shieldsUp').length;
  h.run(120);
  assert.equal(h.calls.volley.length, n, 'no volley once every archer is dead');
  assert.equal(h.log.filter((e) => e.n === 'voice' && e.key === 'shieldsUp').length, shields);
});

test('volleys from archers not yet on the field still count; waves without archers keep the clock', () => {
  // An ai without the archer getters: volleys run on the clock (as before).
  const h = harness({ killAfter: 1e9, archersApi: false });
  h.waves.start(2);
  h.run(100);
  assert.ok(h.calls.volley.length >= 3 && h.calls.volley.every((v) => v.count === 300));
  // Day 3 phase A has no archers: full volleys on the clock.
  const h3 = harness({ killAfter: 2 });
  h3.waves.start(3);
  h3.until(() => h3.waves.phase === 'A' && h3.calls.volley.length >= 2);
  assert.ok(h3.calls.volley.every((v) => v.count === 360));
});

test('Day 3: phase B drops the bundle (one spear stays in hand) with the line; phase C is sword only', () => {
  const h = harness({ killAfter: 1 });
  h.waves.start(3);
  assert.equal(h.player.spearsLeft, 4);
  h.until(() => h.waves.phase === 'B');
  const bAt = h.log.find((e) => e.n === 'phase' && e.phase === 'B').t;
  h.run(3);
  const swords = h.log.find((e) => e.n === 'voice' && e.text === 'Our spears are broken. Swords!');
  assert.ok(swords && swords.t >= bAt);
  assert.deepEqual(h.calls.drop.map((d) => d.keepInHand), [true]);
  assert.ok(Math.abs(h.calls.drop[0].t - (bAt + 2.4)) < 0.06);
  assert.equal(h.player.spearsLeft, 1);
  h.until(() => h.waves.phase === 'C');
  const cAt = h.log.find((e) => e.n === 'phase' && e.phase === 'C').t;
  assert.deepEqual(h.calls.drop.map((d) => d.keepInHand), [true, false]);
  assert.ok(Math.abs(h.calls.drop[1].t - cAt) < 1e-6);
  assert.equal(h.player.spearsLeft, 0);
  // No refill after phase B began, even on a respite-like lull.
  assert.ok(h.calls.refill.every((r) => r.t < bAt));
  // Phase C volleys: never closer than 7 s; rear volleys always 100 (one arrow).
  h.run(120);
  const cv = h.calls.volley.filter((v) => v.t >= cAt);
  assert.ok(cv.length >= 12);
  for (let i = 1; i < cv.length; i++) assert.ok(cv[i].t - cv[i - 1].t >= 7 - 1e-6);
  assert.ok(cv.filter((v) => v.fromBehind).every((v) => v.count === 100));
  // 'Shields up!' at most once per 12 s.
  const sh = h.log.filter((e) => e.n === 'voice' && e.key === 'shieldsUp' && e.t >= cAt).map((e) => e.t);
  assert.ok(sh.length >= 5 && sh.length < cv.length);
  for (let i = 1; i < sh.length; i++) assert.ok(sh[i] - sh[i - 1] >= 12 - 1e-6);
});

test('respites top the bundle up to 2 spears (never take any away); dayStart refills it fully', () => {
  const h = harness({ killAfter: 1, spears: 0 });
  h.player.hasSpearInHand = false;
  h.player.spares = 0;
  h.waves.start(1);
  assert.deepEqual(h.calls.refill.map((r) => r.n), [undefined], 'dayStart: full refill');
  assert.equal(h.player.spearsLeft, 4);
  // Throw them all, then reach a respite: back to 2.
  h.player.spares = 0;
  h.player.hasSpearInHand = false;
  h.until(() => h.log.some((e) => e.n === 'respite'));
  assert.equal(h.calls.refill.length, 2);
  assert.equal(h.calls.refill[1].n, RESPITE.spears);
  assert.equal(h.player.spearsLeft, 2);
  // With 3 spears left, the next respite leaves them alone.
  h.player.spares = 2;
  h.player.hasSpearInHand = true;
  h.until(() => h.log.filter((e) => e.n === 'respite').length === 2);
  assert.equal(h.calls.refill.length, 2);
  assert.equal(h.player.spearsLeft, 3);
});

test('start(day, entry) restarts at the current part of the day; entryIndex/timeOfDay/waveCount report it', () => {
  const h = harness({ killAfter: 1 });
  h.waves.start(1, 1);
  assert.equal(h.log[0].n, 'dayStart');
  assert.equal(h.log[0].title, 'The Immortals');
  assert.equal(h.waves.entryIndex, 1);
  assert.equal(h.waves.timeOfDay, 'dusk');
  h.until(() => h.waves.state === 'fighting');
  assert.equal(h.waves.waveCount, 2);
  // Out-of-range entries fall back to the day's first.
  h.waves.start(2, 3);
  assert.equal(h.waves.entryIndex, 0);
  assert.equal(h.waves.timeOfDay, 'noon');
  h.waves.start(3, 1);
  assert.equal(h.waves.entryIndex, 0);
  assert.equal(h.waves.day, 3);
  h.waves.start(1);
  assert.equal(h.waves.entryIndex, 0);
  assert.equal(h.waves.timeOfDay, 'morning');
  // Playing through the morning: entryIndex becomes 1 at dusk.
  h.until(() => h.waves.timeOfDay === 'dusk');
  assert.equal(h.waves.entryIndex, 1);
  assert.equal(firstEntryOfDay(1) + h.waves.entryIndex, 1);
  // Phase B: four surges.
  h.waves.start(3);
  h.until(() => h.waves.phase === 'B' && h.waves.state === 'fighting');
  assert.equal(h.waves.waveCount, 4);
  h.until(() => h.waves.phase === 'C' && h.waves.state === 'fighting');
  assert.equal(h.waves.waveCount, 0);
});

test('pause kicker names the part of the day and the phase', () => {
  assert.equal(pauseKicker({ day: 1, wave: 2, timeOfDay: 'morning' }), 'Day I · Wave II');
  assert.equal(pauseKicker({ day: 1, wave: 1, timeOfDay: 'dusk' }), 'Day I · Dusk · Wave I');
  assert.equal(pauseKicker({ day: 1, wave: 0, timeOfDay: 'dusk' }), 'Day I · Dusk');
  assert.equal(pauseKicker({ day: 2, wave: 3, timeOfDay: 'noon' }), 'Day II · Wave III');
  assert.equal(pauseKicker({ day: 3, phase: 'A', wave: 2, timeOfDay: 'dawn' }), 'Day III · Wave II');
  assert.equal(pauseKicker({ day: 3, phase: 'B', wave: 2, waveCount: 4, timeOfDay: 'dawn' }), 'Day III · Surge II of IV');
  assert.equal(pauseKicker({ day: 3, phase: 'C', wave: 1, timeOfDay: 'dawn' }), 'Day III · The Kolonos');
  assert.equal(pauseKicker(null), '');
  // With the real waves object.
  const h = harness({ killAfter: 1 });
  h.waves.start(1, 1);
  h.until(() => h.waves.state === 'fighting');
  assert.equal(pauseKicker(h.waves), 'Day I · Dusk · Wave I');
  h.waves.start(3);
  h.until(() => h.waves.phase === 'B' && h.waves.state === 'fighting');
  assert.equal(pauseKicker(h.waves), 'Day III · Surge I of IV');
});
