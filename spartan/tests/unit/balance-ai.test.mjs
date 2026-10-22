// Balance fixes in game/ai.js and game/rules.js (review B, campaign lens), run headless:
// the per-day engage delay, the anti-turtle rule (a shield held ≥ 3 s takes heavy blows) and
// allies relieving a stalled duel. A resolve hook maps 'three' to the vendored file.
// node --test spartan/tests/unit/balance-ai.test.mjs
import { register } from 'node:module';
import test from 'node:test';
import assert from 'node:assert/strict';

const LIB = new URL('../../src/main/assets/game/lib/three/', import.meta.url).href;
const hook = `
const LIB = ${JSON.stringify(LIB)};
export async function resolve(spec, context, next) {
  if (spec === 'three') return { url: LIB + 'three.module.js', shortCircuit: true };
  if (spec.startsWith('three/addons/')) return { url: LIB + 'addons/' + spec.slice(13), shortCircuit: true };
  return next(spec, context);
}`;
register(`data:text/javascript,${encodeURIComponent(hook)}`, import.meta.url);

const THREE = await import('three');
const { EventBus } = await import('../../src/main/assets/game/js/core/events.js');
const { createRng } = await import('../../src/main/assets/game/js/core/math.js');
const { createAI } = await import('../../src/main/assets/game/js/game/ai.js');
const { DIRECTOR } = await import('../../src/main/assets/game/js/game/rules.js');

const DT = 1 / 30;

/** createAI with a stub player (never hurt), input and combat that records every strike. */
function world(day = 1) {
  const events = new EventBus();
  const strikes = [];
  const ctx = {
    events, rng: createRng(11), scene: new THREE.Scene(), actors: [], mode: 'battle', quality: 'low',
    time: { gnow: 0 },
    settings: { control: 'touch' },
    waves: { day, dayWave: 0 },
    input: { shieldHeld: false, shieldPressedAt: -10 },
    player: { pos: new THREE.Vector3(0, 0, 0), yaw: 0, alive: true, hp: 100, maxHp: 100, shieldRaise: 0 },
    combat: { actorStrike: (a, atk) => { strikes.push({ t: ctx.time.gnow, a, heavy: atk.heavy, target: atk.target }); return 'block'; } },
  };
  const ai = createAI(ctx);
  ctx.ai = ai;
  const step = (s) => {
    for (let i = 0, n = Math.round(s / DT); i < n; i++) {
      ctx.time.gnow += DT;
      ai.update(DT);
    }
  };
  return { ctx, ai, strikes, step, events };
}

test('DIRECTOR: Days 1–2 press harder (rate 0.30 / 0.40) with a 0.3–0.7 s engage delay; Day 3 keeps 0.8–1.4 s', () => {
  assert.equal(DIRECTOR[1].rate, 0.30);
  assert.equal(DIRECTOR[2].rate, 0.40);
  assert.equal(DIRECTOR[3].rate, 0.40);
  assert.deepEqual(DIRECTOR[1].engageDelay, [0.3, 0.4]);
  assert.deepEqual(DIRECTOR[2].engageDelay, [0.3, 0.4]);
  assert.deepEqual(DIRECTOR[3].engageDelay, [0.8, 0.6]);
});

test('an enemy arriving at a player slot waits DIRECTOR[day].engageDelay before its first wind-up', () => {
  for (const [day, lo, hi] of [[1, 0.3, 0.7], [2, 0.3, 0.7], [3, 0.8, 1.4]]) {
    const delays = [];
    for (let k = 0; k < 6; k++) {
      const w = world(day);
      w.ctx.rng = createRng(100 + k);
      const a = w.ai.spawnEnemy('levy', 0.3, -4);
      for (let i = 0; i < 400 && a.engagedAt < 0; i++) w.step(DT);
      assert.ok(a.engagedAt > 0, `day ${day}: engaged`);
      delays.push(a.readyAt - a.engagedAt);
    }
    for (const d of delays) assert.ok(d >= lo - 1e-9 && d <= hi + 1e-9, `day ${day}: delay ${d}`);
  }
});

test('anti-turtle: blows on a shield held ≥ 3 s are heavy (guard break); a fresh hold or a volley is not', () => {
  const w = world(1);
  const { ctx } = w;
  const a = w.ai.spawnEnemy('levy', 0, -3);
  const heavyAt = (i) => w.strikes.filter((s) => s.target === 'player').map((s) => [+(s.t - i).toFixed(2), s.heavy]);
  // No shield: ordinary blows.
  w.step(8);
  assert.ok(w.strikes.length >= 1, 'the levy struck');
  assert.ok(w.strikes.every((s) => s.heavy === false));
  // Raise and hold the shield: blows inside the first 3 s are ordinary, later ones heavy.
  const held = ctx.time.gnow;
  ctx.input.shieldHeld = true;
  ctx.input.shieldPressedAt = held;
  ctx.player.shieldRaise = 1;
  const n0 = w.strikes.length;
  w.step(14);
  const during = w.strikes.slice(n0);
  assert.ok(during.length >= 3, `strikes while holding: ${during.length}`);
  for (const s of during) assert.equal(s.heavy, s.t - held >= 3 - 1e-6, JSON.stringify(heavyAt(held)));
  assert.ok(during.some((s) => s.heavy) && during.some((s) => !s.heavy) === during.some((s) => s.t - held < 3));
  // Re-pressing restarts the clock.
  ctx.input.shieldPressedAt = ctx.time.gnow;
  const repress = ctx.time.gnow;
  const n1 = w.strikes.length;
  w.step(2.5);
  assert.ok(w.strikes.slice(n1).every((s) => s.heavy === false && s.t - repress < 3));
  // A volley warning covers a long hold: no heavy blows for 6 s + its duration, then the clock
  // restarts from the end of that cover.
  w.events.emit('volley', { phase: 'warning', duration: 1 });
  const vAt = ctx.time.gnow;
  const n2 = w.strikes.length;
  w.step(7 + 2.9);
  assert.ok(w.strikes.slice(n2).every((s) => s.heavy === false), JSON.stringify(heavyAt(vAt)));
  const n3 = w.strikes.length;
  w.step(8);
  assert.ok(w.strikes.slice(n3).some((s) => s.heavy), 'heavy again 3 s after the volley cover');
  // The shield down (raise < 0.7) is never turtling.
  ctx.input.shieldHeld = false;
  ctx.player.shieldRaise = 0;
  const n4 = w.strikes.length;
  w.step(8);
  assert.ok(w.strikes.slice(n4).every((s) => s.heavy === false));
  assert.ok(a.alive);
});

test('anti-turtle: trainers and blows on allies are never made heavy by the player shield', () => {
  const w = world(1);
  const { ctx } = w;
  ctx.input.shieldHeld = true;
  ctx.input.shieldPressedAt = -100;
  ctx.player.shieldRaise = 1;
  w.ai.spawnEnemy('levy', 0, -1.7, { trainer: true, facing: 0 });
  w.step(10);
  assert.ok(w.strikes.length >= 2);
  assert.ok(w.strikes.every((s) => s.heavy === false));
});

test('allies take a player attacker the player has not struck for 15 s (a stalled duel resolves)', () => {
  const w = world(1);
  const { ctx } = w;
  const ally = w.ai.spawnAlly(1.4);
  const e = w.ai.spawnEnemy('levy', 0.6, -3.5);
  for (let i = 0; i < 600 && e.engagedAt < 0; i++) w.step(DT);
  assert.ok(e.targetIsPlayer && e.engagedAt > 0, 'engaged with the player');
  const engagedAt = e.engagedAt;
  // The player keeps hitting it every 5 s for 20 s: the ally leaves it alone.
  for (let k = 0; k < 4; k++) {
    w.step(5);
    assert.notEqual(ally.foe, e, `ally foe at +${(ctx.time.gnow - engagedAt).toFixed(1)} s`);
    e.takeHit({ damage: 0, kind: 'slash', byPlayer: true, dir: { x: 0, z: -1 } });
  }
  const lastHit = ctx.time.gnow;
  w.step(14);
  assert.notEqual(ally.foe, e, 'not before 15 s');
  let tookAt = null;
  for (let i = 0; i < 90 && tookAt === null; i++) {
    w.step(DT);
    if (ally.foe === e) tookAt = ctx.time.gnow;
  }
  assert.ok(tookAt !== null && tookAt - lastHit >= 15 - 1e-6 && tookAt - lastHit < 15.6, `took over at +${tookAt && (tookAt - lastHit)}`);
  // ...and wears it down: the ally's blows land on that enemy.
  w.step(12);
  assert.ok(w.strikes.some((s) => s.a === ally && s.target === e), 'the ally strikes the stalled attacker');
});
