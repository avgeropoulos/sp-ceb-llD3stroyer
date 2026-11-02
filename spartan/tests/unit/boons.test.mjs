// Gifts of the gods (game/boons.js): the offer, how gifts fold into the rage's perks, and the
// respite waiting for the pick (game/waves.js).
// node --test spartan/tests/unit/boons.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { BOONS, applyBoons, offerBoons, boonById, GAZE } from '../../src/main/assets/game/js/game/boons.js';
import { RAGE_TIERS } from '../../src/main/assets/game/js/game/rage.js';
import { createWaves } from '../../src/main/assets/game/js/game/waves.js';
import { RESPITE, SPAWN } from '../../src/main/assets/game/js/game/campaign.js';
import { EventBus } from '../../src/main/assets/game/js/core/events.js';
import { createRng } from '../../src/main/assets/game/js/core/math.js';

test('the gifts: at least a dozen, unique ids, every one named, described and frozen', () => {
  assert.ok(BOONS.length >= 12);
  assert.equal(new Set(BOONS.map((b) => b.id)).size, BOONS.length);
  for (const b of BOONS) {
    assert.ok(b.name && b.desc && typeof b.apply === 'function' && Object.isFrozen(b), b.id);
    assert.equal(boonById(b.id), b);
  }
  assert.ok(GAZE.range > 0 && GAZE.stagger > 0);
});

test('applyBoons folds the gifts taken into the tier row; the row itself is untouched', () => {
  const base = applyBoons(RAGE_TIERS[0], []);
  assert.equal(base.duration, RAGE_TIERS[0].duration);
  assert.deepEqual([base.reachBonus, base.critMul, base.moveMul, base.unleashHeal, base.comboExtend, base.spearOnKill, base.hurtGainMul, base.gaze], [0, 1, 1, 0, 0, false, 1, false]);
  const p = applyBoons(RAGE_TIERS[1], ['zeus', 'achilles', 'bronze', 'apollo', 'dory', 'gorgon', 'nope']);
  assert.equal(p.duration, RAGE_TIERS[1].duration + 3);
  assert.equal(p.critMul, 1.4);
  assert.ok(Math.abs(p.takenMul - RAGE_TIERS[1].takenMul * 0.67) < 1e-12);
  assert.ok(p.arrowProof && p.spearOnKill && p.gaze && Object.isFrozen(p));
  assert.equal(RAGE_TIERS[1].duration, 10, 'the tier row is not changed');
  // Each gift changes something.
  for (const b of BOONS) assert.notDeepEqual(applyBoons(RAGE_TIERS[0], [b.id]), base, b.id);
});

test('offerBoons: three, never one taken, never an effect the perks already grant, deterministic per rng', () => {
  const rng = createRng(7);
  const o = offerBoons([], applyBoons(RAGE_TIERS[0], []), rng.next);
  assert.equal(o.length, 3);
  assert.equal(new Set(o).size, 3);
  const taken = BOONS.slice(0, BOONS.length - 2).map((b) => b.id);
  const left = offerBoons(taken, applyBoons(RAGE_TIERS[0], taken), createRng(1).next);
  assert.deepEqual(left.sort(), BOONS.slice(-2).map((b) => b.id).sort(), 'only what is left');
  assert.deepEqual(offerBoons(BOONS.map((b) => b.id), null), []);
  // The last stand already hurls foes back and is arrow-proof: those gifts are not offered.
  for (let s = 1; s < 40; s++) {
    const ids = offerBoons([], applyBoons(RAGE_TIERS[3], []), createRng(s).next);
    assert.ok(!ids.includes('apollo') && !ids.includes('alalai'), ids.join());
  }
  assert.deepEqual(offerBoons([], null, createRng(3).next), offerBoons([], null, createRng(3).next));
});

test('a respite waits for the pick, short of the next wave setting out; after 45 s the gods choose', () => {
  const events = new EventBus();
  const t = { gnow: 0 };
  const actors = [];
  const log = [];
  let offer = null;
  const picks = [];
  const player = {
    maxHp: 100, hp: 100, kills: 0, score: 0, spares: 3, hasSpearInHand: true,
    get spearsLeft() { return this.spares + 1; },
    heal() {}, refillSpears() {}, dropSpears() {},
    get boonOffer() { return offer; },
    pickBoon(i) { picks.push(i); offer = null; },
  };
  let id = 0;
  const ai = {
    spawnEnemy(kind, x, z) { const a = { id: id++, kind, team: 'persian', role: 'melee', alive: true, state: 'advance', pos: { x, y: 0, z }, born: t.gnow }; actors.push(a); return a; },
    spawnHelot() {}, ensureAllies() {}, killAlly() {}, retreatArchers() {}, clear() {}, clearCorpses() {},
    get enemiesAlive() { return actors.filter((a) => a.alive && a.role === 'melee').length; },
    get archersAlive() { return 0; },
  };
  const ctx = { events, rng: createRng(5), time: t, actors, player, ai, settings: { control: 'touch' }, combat: { volley() {} },
    world: { setTimeOfDay() {} }, audio: { setMusic() {} }, hud: { banner() {} } };
  for (const n of ['respite', 'waveStart']) events.on(n, (e) => log.push({ n, t: t.gnow, ...e }));
  events.on('respite', () => { offer = ['zeus', 'ares', 'fire']; });
  const waves = createWaves(ctx);
  const step = (dt = 0.05) => {
    t.gnow += dt;
    for (const a of actors) if (a.alive && t.gnow - a.born > 1) a.alive = false;
    waves.update(dt);
  };
  waves.start(1);
  for (let i = 0; i < 40000 && !log.some((e) => e.n === 'respite'); i++) step();
  const r = log.find((e) => e.n === 'respite');
  assert.ok(r, 'a respite');
  // No pick: the respite outlasts its seconds and the next wave has not set out.
  for (let i = 0; i < (RESPITE.touch + 10) / 0.05; i++) step();
  assert.equal(log.filter((e) => e.n === 'waveStart').length, 1, 'the second wave waits');
  assert.ok(waves.respiteLeft > SPAWN.respiteLead, `held above the set-out lead (${waves.respiteLeft})`);
  // The player picks: the wave comes respiteLead later.
  offer = null;
  const at = t.gnow;
  for (let i = 0; i < 40000 && log.filter((e) => e.n === 'waveStart').length < 2; i++) step();
  const ws2 = log.filter((e) => e.n === 'waveStart')[1].t;
  assert.ok(ws2 - at <= SPAWN.respiteLead + 1, `wave 2 ${(ws2 - at).toFixed(2)} s after the pick`);
  // Next respite, no pick at all: after 45 s the gods choose the first.
  for (let i = 0; i < 40000 && log.filter((e) => e.n === 'respite').length < 2; i++) step();
  const r2 = log.filter((e) => e.n === 'respite')[1].t;
  for (let i = 0; i < 40000 && picks.length === 0; i++) step();
  assert.deepEqual(picks, [0]);
  assert.ok(Math.abs(t.gnow - r2 - 45) < 0.2, `chosen at ${(t.gnow - r2).toFixed(2)} s`);
});
