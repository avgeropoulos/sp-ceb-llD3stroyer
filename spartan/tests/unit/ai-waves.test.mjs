// Campaign data (§13) and the waves state machine (§11) with a fake ai/player/combat.
// node --test spartan/tests/unit/ai-waves.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { DAYS, VOICE, RESPITE, SPAWN, ALLY_SLOTS, LEONIDAS_X, firstEntryOfDay, meleeCount } from '../../src/main/assets/game/js/game/campaign.js';
import { createWaves } from '../../src/main/assets/game/js/game/waves.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';
import { createRng } from '../../src/main/assets/game/js/core/math.js';

test('campaign data matches §13', () => {
  assert.deepEqual(DAYS.map((d) => [d.day, d.time, d.title]), [
    [1, 'morning', 'The First Day'], [1, 'dusk', 'The Immortals'], [2, 'noon', 'The Second Day'], [3, 'dawn', 'The Third Day'],
  ]);
  const pick = (w) => Object.fromEntries(Object.entries(w).filter(([k]) => k !== 'subtitle'));
  assert.deepEqual(DAYS[0].waves.map(pick), [{ levy: 8, alive: 4 }, { levy: 12, alive: 5 }, { levy: 12, archer: 2, alive: 6, volleyEvery: 0 }, { levy: 6, sparabara: 6, alive: 6 }]);
  assert.deepEqual(DAYS[1].waves.map(pick), [{ immortal: 8, archer: 3, volleyEvery: 30, alive: 6 }, { immortal: 10, captain: 1, archer: 3, volleyEvery: 24, alive: 7 }]);
  assert.deepEqual(DAYS[2].waves.map(pick), [
    { levy: 8, sparabara: 6, archer: 3, volleyEvery: 26, alive: 7 },
    { immortal: 8, sparabara: 4, captain: 1, archer: 3, volleyEvery: 22, alive: 8 },
    { levy: 6, immortal: 8, captain: 1, archer: 4, volleyEvery: 20, alive: 9 },
  ]);
  assert.equal(DAYS[1].endCard, 'Three times, it is said, the King leapt from his throne in fear for his army.');
  const [A, B, C] = DAYS[3].phases;
  assert.deepEqual(A.waves.map(meleeCount), [14, 16, 18]);
  assert.deepEqual(A.waves.map((w) => w.alive), [10, 10, 10]);
  assert.deepEqual(A.waves.map((w) => w.volleyEvery), [18, 16, 14]);
  assert.ok(A.waves.every((w) => w.levy > 0 && w.immortal > 0 && !w.archer));
  assert.deepEqual(A.respite, { seconds: 12, heal: 0.2, refill: true, helot: true, allies: false });
  assert.equal(B.waves.length, 4);
  assert.ok(B.waves.every((w) => w.immortal === 8 && w.captain === 1));
  assert.equal(B.lull, 6);
  assert.equal(B.killAlly, 'Leonidas');
  assert.equal(B.title, "Over Leonidas' body");
  assert.equal(C.title, 'The Kolonos');
  assert.equal(C.subtitle, 'They defended themselves with swords, those who still had them, and with hands and teeth.');
  assert.deepEqual(C.volley, { first: 10, start: 10, step: 0.5, min: 7 });
  assert.equal(C.swordOnly, true);
  assert.equal(C.fade, 2);
  assert.deepEqual(RESPITE, { motion: 18, touch: 10, readyAfter: 3, heal: 0.35, spears: 2 });
  assert.deepEqual(SPAWN.interval, [1.2, 2.0]);
  assert.deepEqual(SPAWN.front, { x: [-6, 7], z: [-26, -20] });
  assert.deepEqual(SPAWN.archer, { x: [-6, 7], z: [-45, -35] });
  assert.equal(SPAWN.respiteLead, 6);
  assert.deepEqual(DAYS[0].vanguard, { count: 4, z: -16 });
  assert.deepEqual(SPAWN.behind.z, [30, 40]);
  assert.deepEqual([...ALLY_SLOTS], [-4.2, -2.8, -1.4, 1.4, 2.8, 4.2, 5.6]);
  assert.equal(LEONIDAS_X, -1.4);
  assert.deepEqual([firstEntryOfDay(1), firstEntryOfDay(2), firstEntryOfDay(3)], [0, 2, 3]);
});

test('voice lines and speakers are exactly §13', () => {
  const lines = Object.values(VOICE).map((v) => `${v.speaker}: ${v.text}`);
  for (const l of [
    'Herald: Thus says King Xerxes to Leonidas: hand over your arms.', 'Leonidas: Come and take them.',
    'Trachinian: Their arrows are so many they hide the sun!', 'Dienekes: Good news. Then we shall fight in the shade.',
    'Leonidas: Hold the line!', 'Leonidas: Shields up!', 'Leonidas: Alalai!', 'Lookout: The Immortals! Hydarnes leads them!',
    'Lookout: The Phocians have abandoned the Anopaia path! Ephialtes is leading the Immortals over the mountain!',
    'Leonidas: Eat your breakfast as men who will dine in Hades.',
  ]) assert.ok(lines.includes(l), l);
  assert.ok(lines.some((l) => l.endsWith('The Tegeans take the line.')));
  assert.ok(lines.some((l) => l.endsWith('Our spears are broken. Swords!')));
  assert.equal(VOICE.molonLabe.greek, 'ΜΟΛΩΝ ΛΑΒΕ');
  const speakers = new Set(['Leonidas', 'Dienekes', 'Trachinian', 'Herald', 'Lookout', 'Narrator']);
  for (const v of Object.values(VOICE)) assert.ok(speakers.has(v.speaker), v.speaker);
});

/** A fake world around createWaves: the "player" kills every melee enemy `killAfter` s after it spawns. */
function harness({ motion = false, killAfter = 3, readyAfter = null } = {}) {
  const events = new EventBus();
  const log = [];
  const t = { gnow: 0 };
  const actors = [];
  const calls = { volley: [], heal: [], refill: 0, ensure: [], helot: 0, kill: [], retreat: 0, time: [], music: [], banner: [], clearCorpses: 0, spawns: [] };
  const ctx = {
    events, rng: createRng(7), time: t, actors,
    settings: { control: motion ? 'motion' : 'touch' },
    player: { maxHp: 100, hp: 100, kills: 3, score: 1234, heal: (n) => calls.heal.push([t.gnow, n]), refillSpears: () => calls.refill++ },
    combat: { volley: (o) => calls.volley.push({ t: t.gnow, ...o }) },
    world: { setTimeOfDay: (x) => calls.time.push(x) },
    audio: { setMusic: (m) => calls.music.push(m) },
    hud: { banner: (text) => calls.banner.push(text) },
    ai: {
      spawnEnemy(kind, x, z, opts) {
        const a = { kind, role: kind === 'archer' ? 'archer' : 'melee', alive: true, born: t.gnow, pos: { x, z }, fromBehind: !!(opts && opts.fromBehind) };
        actors.push(a);
        calls.spawns.push({ t: t.gnow, kind, x, z, fromBehind: a.fromBehind });
        return a;
      },
      get enemiesAlive() { return actors.filter((a) => a.alive && a.role === 'melee').length; },
      playerAttackers: [],
      ensureAllies: (xs, o) => calls.ensure.push([t.gnow, xs && xs.length, !!(o && o.walkIn)]),
      spawnHelot: () => calls.helot++,
      killAlly: (n) => calls.kill.push([t.gnow, n]),
      retreatArchers: () => { calls.retreat++; for (const a of actors) if (a.role === 'archer') a.alive = false; },
      clearCorpses: () => calls.clearCorpses++,
    },
  };
  for (const n of ['dayStart', 'waveStart', 'waveCleared', 'respite', 'phase', 'voice', 'gameOver']) events.on(n, (e) => log.push({ n, t: +t.gnow.toFixed(3), ...e }));
  const waves = createWaves(ctx);
  ctx.waves = waves;
  const step = (dt) => {
    t.gnow += dt;
    for (const a of actors) if (a.alive && a.role === 'melee' && t.gnow - a.born > killAfter) a.alive = false;
    ctx.ai.playerAttackers.length = actors.some((a) => a.alive && a.role === 'melee') ? 1 : 0;
    if (readyAfter !== null && waves.inRespite) {
      const r = log.filter((e) => e.n === 'respite').pop();
      if (r && t.gnow - r.t >= readyAfter) waves.ready();
    }
    waves.update(dt);
  };
  return { ctx, waves, log, calls, step, t, events };
}

test('the campaign runs from Day 1 to the end of phase C and gameOver 3.2 s after death', () => {
  const h = harness({ readyAfter: 6 });
  h.waves.start(1);
  assert.equal(h.waves.state, 'intro');
  let cAt = null;
  for (let i = 0; i < 200000 && h.waves.state !== 'over'; i++) {
    h.step(0.05);
    if (cAt === null && h.waves.phase === 'C' && h.waves.state === 'fighting') cAt = h.t.gnow;
    if (cAt !== null && h.t.gnow - cAt > 90 && !h.log.some((e) => e.n === 'gameOver') && !h.dead) {
      h.dead = h.t.gnow;
      h.events.emit('playerDeath', {});
    }
  }
  const seq = h.log.filter((e) => e.n !== 'voice').map((e) => `${e.n}:${e.day}${e.phase || ''}${e.wave ? '#' + e.wave : ''}`);
  assert.deepEqual(seq.filter((s) => s.startsWith('dayStart')), ['dayStart:1', 'dayStart:1', 'dayStart:2', 'dayStart:3']);
  assert.deepEqual(seq.filter((s) => s.startsWith('phase')), ['phase:3A', 'phase:3B', 'phase:3C']);
  assert.equal(seq.filter((s) => s.startsWith('waveStart')).length, 6 + 3 + 3 + 4 + 1);
  assert.equal(seq.filter((s) => s.startsWith('respite')).length, 3 + 1 + 2 + 2);
  const over = h.log.find((e) => e.n === 'gameOver');
  assert.ok(over, 'gameOver');
  assert.ok(Math.abs(over.t - h.dead - 3.2) < 0.051, `gameOver at +${over.t - h.dead}`);
  assert.deepEqual({ day: over.day, wave: over.wave, kills: over.kills, score: over.score }, { day: 3, wave: 1, kills: 3, score: 1234 });
  assert.equal(h.log[h.log.length - 1].n, 'gameOver');
  // Time of day and Leonidas.
  assert.deepEqual(h.calls.time, ['morning', 'dusk', 'noon', 'dawn', 'morning']);
  assert.equal(h.calls.kill.length, 1);
  assert.equal(h.calls.kill[0][1], 'Leonidas');
  const bAt = h.log.find((e) => e.n === 'phase' && e.phase === 'B').t;
  assert.ok(h.calls.kill[0][0] - bAt >= 0.95 && h.calls.kill[0][0] - bAt < 1.1);
  // Spears are refilled at every dayStart and respite before phase B, never after.
  const lastRespite = h.log.filter((e) => e.n === 'respite').pop().t;
  assert.ok(lastRespite < bAt);
  // Phase C: Immortals from behind at z 30..40.
  const behind = h.calls.spawns.filter((s) => s.fromBehind);
  assert.ok(behind.length > 3 && behind.every((s) => s.kind === 'immortal' && s.z >= 30 && s.z <= 40 && s.t > cAt));
  // The Day 1 vanguard (4 levies at z ≈ −16, placed at Fight); every other front spawn at z −26…−20.
  const front = h.calls.spawns.filter((s) => !s.fromBehind && s.kind !== 'archer');
  assert.deepEqual(front.slice(0, 4).map((s) => [s.t, s.kind]), [[0, 'levy'], [0, 'levy'], [0, 'levy'], [0, 'levy']]);
  assert.ok(front.slice(0, 4).every((s) => s.z >= -16.5 && s.z <= -15));
  assert.ok(front.slice(4).every((s) => s.z >= -26 && s.z <= -20 && s.x >= -6 && s.x <= 7));
  assert.ok(h.calls.spawns.filter((s) => s.kind === 'archer').every((s) => s.z >= -45 && s.z <= -35));
  // Phase C volleys: 10, 10, 9.5, 9, ... ≥ 7 s apart (never overlapping), alternating from behind.
  const cv = h.calls.volley.filter((v) => v.t >= cAt);
  const gaps = cv.map((v, i) => +(v.t - (i ? cv[i - 1].t : cAt)).toFixed(2));
  assert.deepEqual(gaps.slice(0, 9), [10, 10, 9.5, 9, 8.5, 8, 7.5, 7, 7]);
  assert.deepEqual(cv.slice(0, 6).map((v) => v.fromBehind), [false, true, false, true, false, true]);
  assert.ok(cv.every((v) => v.count === (v.fromBehind ? 100 : 360)));
  // The end card banner and the voice lines.
  assert.ok(h.calls.banner[0].startsWith('Three times'));
  const voices = h.log.filter((e) => e.n === 'voice');
  // 'Shields up!' at a volley warning, at most once per 12 s.
  const shields = voices.filter((v) => v.key === 'shieldsUp').map((v) => v.t);
  assert.ok(shields.length > 10 && shields.length < h.calls.volley.length);
  assert.ok(shields.every((t, i) => i === 0 || t - shields[i - 1] >= 12 - 1e-6));
  assert.ok(shields.every((t) => h.calls.volley.some((v) => Math.abs(v.t - t) < 1e-6)));
  assert.equal(voices.filter((v) => v.key === 'arrowsHideSun').length, 1);
  assert.equal(voices.filter((v) => v.text === 'The Tegeans take the line.').length, 1);
  assert.ok(voices.filter((v) => v.t > bAt + 1.1).every((v) => v.speaker !== 'Leonidas'));
  assert.deepEqual(h.log.filter((e) => e.n === 'waveCleared' && e.phase === 'B').map((e) => e.title), ['Driven back (1/4)', 'Driven back (2/4)', 'Driven back (3/4)', 'Driven back (4/4)']);
});

test('respites last 10 s (touch) / 18 s (motion); READY ends them at ≥ 3 s', () => {
  for (const [motion, ready, want] of [[false, null, 10], [true, null, 18], [true, 2, 3], [true, 9, 9]]) {
    const h = harness({ motion, killAfter: 1 });
    h.waves.start(1);
    let rAt = null, readyRes = null;
    for (let i = 0; i < 40000; i++) {
      h.step(0.05);
      const r = h.log.find((e) => e.n === 'respite');
      if (r && rAt === null) rAt = r.t;
      if (rAt !== null && ready !== null && readyRes === null && h.t.gnow - rAt >= ready - 1e-9) readyRes = h.waves.ready();
      if (rAt !== null && h.log.some((e) => e.n === 'waveStart' && e.t > rAt)) break;
    }
    const next = h.log.find((e) => e.n === 'waveStart' && e.t > rAt).t;
    assert.ok(Math.abs(next - rAt - want) < 0.11, `motion=${motion} ready=${ready}: ${next - rAt}`);
    const r = h.log.find((e) => e.n === 'respite');
    assert.equal(r.seconds, motion ? 18 : 10);
    if (ready !== null) assert.equal(readyRes, true);
    // 35% heal, spears, helot and walk-in allies.
    assert.ok(h.calls.heal.some(([, n]) => Math.abs(n - 35) < 1e-9));
    assert.ok(h.calls.helot >= 1 && h.calls.ensure.some((e) => e[2] === true));
  }
});

test('READY is ignored outside a respite and in phase B lulls', () => {
  const h = harness({ killAfter: 1 });
  assert.equal(h.waves.ready(), false);
  h.waves.start(3);
  assert.equal(h.waves.ready(), false);
  let lullSeen = false;
  for (let i = 0; i < 40000 && !lullSeen; i++) {
    h.step(0.05);
    if (h.waves.phase === 'B' && h.waves.state === 'respite') {
      lullSeen = true;
      assert.equal(h.waves.inRespite, false);
      assert.equal(h.waves.canReady, false);
      assert.equal(h.waves.ready(), false);
      assert.ok(h.waves.respiteLeft > 0 && h.waves.respiteLeft <= 6);
    }
  }
  assert.ok(lullSeen);
});

test('stop() cancels everything silently', () => {
  const h = harness({ killAfter: 1e9 });
  h.waves.start(2);
  for (let i = 0; i < 25 * 20; i++) h.step(0.05);
  assert.equal(h.waves.state, 'fighting');
  const n = h.log.length, v = h.calls.volley.length, s = h.calls.spawns.length;
  h.waves.stop();
  h.events.emit('playerDeath', {});
  for (let i = 0; i < 200 * 20; i++) h.step(0.05);
  assert.equal(h.log.length, n);
  assert.equal(h.calls.volley.length, v);
  assert.equal(h.calls.spawns.length, s);
  assert.equal(h.waves.state, 'over');
});

test('first volley ≥ 12 s after waveStart, then every volleyEvery', () => {
  const h = harness({ killAfter: 1e9 });
  h.waves.start(2);
  for (let i = 0; i < 120 * 20; i++) h.step(0.05);
  const ws = h.log.find((e) => e.n === 'waveStart').t;
  const vs = h.calls.volley.map((v) => v.t - ws);
  assert.ok(vs[0] >= 12 && vs[0] <= 15.01, String(vs[0]));
  for (let i = 1; i < vs.length; i++) assert.ok(Math.abs(vs[i] - vs[i - 1] - 26) < 0.06);
  assert.ok(vs.length >= 4);
  // Spawns respect the alive cap of 7.
  assert.ok(h.ctx.ai.enemiesAlive <= 7);
});

test('starting on Day 3 begins at dawn with the Anopaia lines, then phase A in the morning', () => {
  const h = harness();
  h.waves.start(3);
  for (let i = 0; i < 30 * 20; i++) h.step(0.05);
  // The English of a recorded line comes from the voice bank (voice-waves.test.mjs checks it).
  const voices = h.log.filter((e) => e.n === 'voice').map((v) => v.speaker + ': ' + v.key);
  assert.equal(voices[0], 'Lookout: anopaia');
  assert.equal(voices[1], 'Leonidas: breakfast');
  assert.deepEqual(h.calls.time, ['dawn', 'morning']);
  assert.equal(h.waves.phase, 'A');
  assert.equal(h.calls.ensure.length >= 1, true);
  assert.equal(h.calls.music[0], 'lastStand');
});
