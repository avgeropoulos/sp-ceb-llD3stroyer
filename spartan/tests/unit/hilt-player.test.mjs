// game/player.js in the hilt grip (docs-hilt-grip.md §C, H.1 #6): the lock-on camera on the real
// player + combat, run on the headless gameplay harness with ctx.input.grip = 'hilt'. Acquisition,
// swipes, loss, the front, the threat switch, the spring as the only yaw writer, aiming and
// throwing at the lock, the hilt volley rule, and the window grip and touch left as they were.
// node --test spartan/tests/unit/hilt-player.test.mjs
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

const { EventBus } = await import('../../src/main/assets/game/js/core/events.js');
const { createRng, angleDiff } = await import('../../src/main/assets/game/js/core/math.js');
const { LOCK } = await import('../../src/main/assets/game/js/game/lockon.js');
const { createHarness, createHeadlessCtx } = await import('../sandbox/gameplay-harness.js');

const ctx = createHeadlessCtx({ EventBus, createRng });
const h = createHarness(ctx);
ctx.loop(() => h.tick());

const PI = Math.PI;
const DT = 1 / 60;
const P = () => h.player;
const front = (a, d) => [-Math.sin(a) * d, -Math.cos(a) * d];
/** A foe at camera bearing `a` (0 ahead, + left) and `d` m: never guards, never dies by accident. */
const spawnAt = (kind, a, d, o = {}) => {
  const [x, z] = front(a, d);
  return h.spawn(kind, x, z, { blockChance: 0, hp: 1e6, ...o });
};
const bearing = (a) => Math.atan2(-(a.pos.x - P().pos.x), -(a.pos.z - P().pos.z));

const locks = [];
ctx.events.on('lockOn', (e) => locks.push({ ...e, frame: h.frame }));
const reasons = () => locks.map((e) => e.reason);

/** A fresh battle with the given grip ('hilt' | 'window' in motion, null for touch). */
function gripReset(grip = 'hilt') {
  h.reset();
  ctx.input.motion = grip !== null;
  ctx.settings.control = grip === null ? 'touch' : 'motion';
  ctx.settings.lockAuto = true;
  ctx.input.grip = grip;
  locks.length = 0;
}

/** Steps n frames; returns each frame's yaw change. */
function stepYaws(n) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const y0 = P().yaw;
    h.step(1);
    out.push(P().yaw - y0);
  }
  return out;
}

const windup = (a) => {
  a.state = 'windup';
  a.targetIsPlayer = true;
  ctx.events.emit('enemyWindup', { actor: a, timeToStrike: 1, targetIsPlayer: true });
};

test('locks the front foe on spawn; a swipe moves it (switch) and the view turns smoothly; a miss emits none', () => {
  gripReset();
  const f = spawnAt('levy', 0, 3);
  const r = spawnAt('levy', -0.6, 3);
  spawnAt('levy', 0.7, 3);
  h.step(1);
  assert.equal(P().lockTarget, f);
  assert.equal(P().lockMode, 'target');
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['acquire', f, null]]);
  locks.length = 0;
  // A right swipe (the action input.js sends), never buffered: it moves the lock in the same frame
  // even mid-attack.
  h.action({ type: 'slash', dir: 0, src: 'motion', power: 0.5 });
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  const f0 = h.frame;
  const dys = stepYaws(60);
  assert.equal(P().lockTarget, r);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['switch', r, f]]);
  assert.equal(locks[0].frame, f0 + 1, 'in the frame it arrived');
  // the turn: every step ≤ maxRate/60, never reversing, within 2° of the bearing in ≤ 0.8 s
  assert.ok(dys.every((d) => Math.abs(d) <= LOCK.maxRate / 60 + 1e-9), `max step ${Math.max(...dys.map(Math.abs))}`);
  assert.ok(dys.every((d) => d <= 1e-12), 'never reverses');
  // the 2° timing, measured cleanly from rest facing the centre foe
  gripReset();
  const f2 = spawnAt('levy', 0, 3);
  const r2 = spawnAt('levy', -0.6, 3);
  spawnAt('levy', 0.7, 3);
  h.stepTime(0.5);
  assert.equal(P().lockTarget, f2);
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  let t2 = null;
  for (let i = 1; i <= 60; i++) {
    h.step(1);
    if (t2 === null && Math.abs(angleDiff(P().yaw, bearing(r2))) < 2 * PI / 180) t2 = i * DT;
  }
  assert.ok(t2 !== null && t2 <= 0.8, `within 2° after ${t2} s`);
  // at rest: a right swipe with nothing further right gives 'none' and the view stays put
  h.stepTime(1);
  assert.equal(P().lockSpring.moving, false);
  locks.length = 0;
  const y0 = P().yaw;
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  h.stepTime(0.5);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['none', r2, r2]]);
  assert.equal(P().yaw, y0);
  assert.equal(P().lockTarget, r2);
  // player.switchTarget(dir) is the same, and says whether the lock moved
  assert.equal(P().switchTarget(-1), true);
  assert.equal(P().lockTarget, f2, 'left of r: the centre foe, nearer in angle than the left one');
  assert.equal(P().switchTarget(-1), true);
  assert.equal(P().switchTarget(-1), false);
});

test('killing the lock keeps facing its bearing for deathHold, then acquires the next within deathHold + 2 frames', () => {
  gripReset();
  const a = spawnAt('levy', 0, 2);
  const b = spawnAt('levy', 0.4, 3);
  h.step(1);
  assert.equal(P().lockTarget, a);
  locks.length = 0;
  a.takeHit({ damage: 1e9, kind: 'slash', dir: { x: 0, z: -1 }, power: 1 });
  assert.equal(a.alive, false);
  const y0 = P().yaw;
  let held = true;
  const n = h.stepUntil(() => {
    if (P().lockTarget !== b && P().yaw !== y0) held = false;
    return P().lockTarget === b;
  }, 60);
  assert.ok(n > 0 && n <= Math.round(LOCK.deathHold / DT) + 2, `acquired after ${n} frames`);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['death', null, a], ['acquire', b, null]]);
  // the hold counts from the frame that saw the death, which follows the kill
  assert.ok(locks[1].frame - locks[0].frame >= Math.round(LOCK.deathHold / DT) - 1, 'held for deathHold');
  assert.ok(n >= Math.round(LOCK.deathHold / DT), `not before deathHold (${n} frames)`);
  assert.ok(held, 'the view held the dead foe\'s bearing until the next lock');
  // during the hold the mode is 'hold'
  gripReset();
  const c = spawnAt('levy', 0, 2);
  h.step(1);
  c.takeHit({ damage: 1e9, kind: 'slash', dir: { x: 0, z: -1 }, power: 1 });
  h.step(2);
  assert.equal(P().lockMode, 'hold');
  assert.equal(P().lockTarget, null);
  h.stepTime(0.5);
  assert.equal(P().lockMode, 'front', 'nothing left to acquire: the front');
});

test('an auto-acquired lock beyond keepRange for loseTime is lost; a swiped archer at 30 m is kept and a throw flies at him', () => {
  gripReset();
  const a = spawnAt('levy', 0, 10);
  h.step(1);
  assert.equal(P().lockTarget, a);
  locks.length = 0;
  a.pos.z = -(LOCK.keepRange + 3);
  const n = h.stepUntil(() => locks.length > 0, 120);
  assert.deepEqual(reasons(), ['lost']);
  assert.ok(Math.abs(n * DT - LOCK.loseTime) <= DT + 1e-9, `lost after ${n * DT} s`);
  assert.equal(P().lockMode, 'front');

  // A swipe to an archer at 30 m: kept for 3 s (its keep range is 33 m), and a throw 1.5 s after
  // the swipe flies at him.
  gripReset();
  const f = spawnAt('levy', 0, 3);
  const archer = spawnAt('archer', -0.5, 30);
  h.step(1);
  assert.equal(P().lockTarget, f);
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.step(1);
  assert.equal(P().lockTarget, archer, 'swiped to the archer');
  h.stepTime(1.5);
  assert.equal(P().weapon, 'spear');
  h.action({ type: 'throw', src: 'motion', power: 1 });
  h.step(4);
  const thrown = h.events('spearThrown');
  assert.equal(thrown.length, 1);
  const v = thrown[0].payload.projectile.vel;
  const vy = Math.atan2(-v.x, -v.z);
  assert.ok(Math.abs(angleDiff(vy, bearing(archer))) <= 0.03, `throw yaw ${vy} vs bearing ${bearing(archer)}`);
  h.stepTime(1.5);
  assert.equal(P().lockTarget, archer, 'kept for 3 s');
  assert.ok(!reasons().includes('lost'));
});

test('clearing the actor list (as ai.clear() does) with a lock gives lost within 1 frame; no foes: the view eases to 0', () => {
  gripReset();
  const a = spawnAt('dummy', 0.9, 3);
  h.stepTime(2);
  assert.equal(P().lockTarget, a);
  assert.ok(Math.abs(P().yaw - bearing(a)) < 0.01, `facing the dummy: ${P().yaw}`);
  locks.length = 0;
  ctx.ai.clear(); // disposes the actors without killing them
  assert.equal(a.alive, true);
  h.step(1);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['lost', null, a]]);
  assert.equal(P().lockTarget, null);
  assert.equal(P().lockMode, 'front');
  const dys = stepYaws(90);
  assert.ok(Math.abs(P().yaw) <= 0.01, `yaw ${P().yaw} after 1.5 s`);
  assert.ok(dys.every((d) => d <= 1e-12 && Math.abs(d) <= LOCK.maxRate / 60 + 1e-9));
});

function rearVolley(grip) {
  gripReset(grip);
  h.setShield(true);
  h.stepTime(0.3);
  const yaws = [];
  h.combat.volley({ count: 300, duration: 1.0, warn: 0.5, fromBehind: true });
  for (let i = 0; i < 270; i++) {
    h.step(1);
    yaws.push(P().yaw);
  }
  return {
    shield: h.events('arrowHit').filter((e) => e.payload.target === 'shield').length,
    hurt: h.events('playerHurt').length,
    yaws,
  };
}

test('hilt: a raised shield takes a rear volley and the view never turns; window and touch keep the facing rule', () => {
  const hilt = rearVolley('hilt');
  assert.deepEqual([hilt.shield, hilt.hurt], [3, 0]);
  assert.ok(hilt.yaws.every((y) => y === 0), 'the volley never turns the view');
  const win = rearVolley('window');
  assert.deepEqual([win.shield, win.hurt], [0, 3], 'window: facing the front, a rear volley hurts');
  const touch = rearVolley(null);
  assert.deepEqual([touch.shield, touch.hurt], [0, 3], 'touch: unchanged');
  // an aimed arrow from an archer behind still needs the shield to face him, hilt or not
  gripReset('hilt');
  const archer = spawnAt('archer', PI, 15);
  h.setShield(true);
  h.stepTime(0.3);
  assert.equal(P().lockTarget, null, 'archers are never auto-acquired');
  h.combat.shootArrow(archer, 'player');
  h.stepTime(2);
  assert.equal(h.events('playerHurt').length, 1, 'the aimed shot from behind hurts');
  assert.equal(h.events('arrowHit').filter((e) => e.payload.target === 'shield').length, 0);
});

test('a threat wind-up from behind takes the lock at the fast rate, at most once per threatGap, never straight back', () => {
  gripReset();
  const F = spawnAt('levy', 0, 2);
  const R = spawnAt('immortal', PI - 0.05, 2);
  const S = spawnAt('levy', 0.6, 2.5);
  h.step(1);
  assert.equal(P().lockTarget, F);
  locks.length = 0;
  // a wind-up the shield covers never turns the view
  windup(S);
  assert.equal(P().lockTarget, F);
  S.state = 'engage';
  windup(R);
  assert.equal(P().lockTarget, R);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['threat', R, F]]);
  R.state = 'engage';
  const dys = stepYaws(60);
  assert.ok(dys.every((d) => Math.abs(d) <= LOCK.fastRate / 60 + 1e-9));
  assert.ok(dys.some((d) => Math.abs(d) > LOCK.maxRate / 60 + 1e-6), 'faster than a swipe turn');
  // 1 s later S (now far off the view) winds up: within threatGap, no switch
  assert.ok(Math.abs(angleDiff(P().yaw, bearing(S))) > LOCK.threatAngle);
  windup(S);
  S.state = 'engage';
  assert.equal(P().lockTarget, R, 'threatGap');
  h.stepTime(2.2); // 3.2 s after the switch
  // F, now behind, lost the lock to a threat 3.2 s ago: it can't take it straight back
  assert.ok(Math.abs(angleDiff(P().yaw, bearing(F))) > LOCK.threatAngle);
  windup(F);
  F.state = 'engage';
  assert.equal(P().lockTarget, R, 'threatReturn');
  // the lock itself winding up at the player blocks a threat switch
  R.state = 'windup';
  R.targetIsPlayer = true;
  windup(S);
  assert.equal(P().lockTarget, R, 'the lock is winding up');
  R.state = 'engage';
  S.state = 'engage';
  h.step(6); // 3.3 s
  windup(S);
  S.state = 'engage';
  assert.equal(P().lockTarget, S, 'past threatGap, S takes the lock');
  assert.deepEqual(reasons(), ['threat', 'threat']);
  assert.equal(locks[1].prev, R);
  // R lost it 0 s ago: blocked by threatReturn after threatGap has passed too
  h.stepTime(3.1);
  assert.ok(Math.abs(angleDiff(P().yaw, bearing(R))) > LOCK.threatAngle);
  windup(R);
  R.state = 'engage';
  assert.equal(P().lockTarget, S, 'R lost the lock to a threat 3.1 s ago');
  h.stepTime(2);
  windup(R);
  R.state = 'engage';
  assert.equal(P().lockTarget, R, 'after threatReturn it may');
  // lockAuto off: never
  gripReset();
  ctx.settings.lockAuto = false;
  const F2 = spawnAt('levy', 0, 2);
  const R2 = spawnAt('immortal', PI, 2);
  h.step(1);
  windup(R2);
  assert.equal(P().lockTarget, F2);
});

test('in the hilt grip a recenter drops the lock and faces the front through the spring, never the recenter ease', () => {
  gripReset();
  const a = spawnAt('levy', 0.6, 3);
  h.stepTime(1.5);
  assert.ok(Math.abs(P().yaw - 0.6) < 0.01);
  locks.length = 0;
  const rec0 = h.gestures.recenters;
  h.action({ type: 'recenter', src: 'key' });
  const dys = stepYaws(18);
  assert.equal(h.gestures.recenters, rec0 + 1, 'the gyro heading is re-anchored');
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['clear', null, a]]);
  assert.equal(P().lockMode, 'front');
  assert.ok(P().yaw > 0.1, `the 0.25 s recenter ease never ran: yaw ${P().yaw} after 0.3 s`);
  assert.ok(dys.every((d) => d <= 1e-12 && Math.abs(d) <= LOCK.maxRate / 60 + 1e-9));
  // after recenterHold it acquires again
  const n = h.stepUntil(() => P().lockTarget === a, 120);
  const t = (18 + n) * DT;
  assert.ok(Math.abs(t - LOCK.recenterHold) <= 2 * DT, `re-acquired ${t} s after the recenter`);
  // the window grip keeps the 0.25 s ease
  gripReset('window');
  P().yaw = 0.6;
  h.action({ type: 'recenter', src: 'key' });
  h.stepTime(0.3);
  assert.equal(P().yaw, 0);
});

test('fx.cameraOverridden freezes the yaw (and ignores swipes); the spring restarts from rest', () => {
  gripReset();
  spawnAt('levy', 0.8, 3);
  spawnAt('levy', -0.5, 10);
  h.stepTime(0.25);
  const y0 = P().yaw;
  assert.ok(y0 > 0.05 && y0 < 0.75, `mid-turn ${y0}`);
  h.fx.cameraOverridden = true;
  h.stepTime(1);
  assert.equal(P().yaw, y0);
  locks.length = 0;
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  h.step(1);
  assert.equal(P().switchTarget(1), false);
  assert.deepEqual(locks, [], 'swipes are ignored under the spear cam');
  h.fx.cameraOverridden = false;
  h.step(1);
  const dy = P().yaw - y0;
  assert.ok(dy > 0 && dy <= LOCK.maxAccel * DT * DT + 1e-12, `restarted from rest: ${dy}`);
  h.stepTime(1.5);
  assert.ok(Math.abs(P().yaw - 0.8) < 0.01);
});

test('strikes aim at the lock (attackYaw within 0.02 rad); a closer foe in front is struck while the lock is far', () => {
  gripReset();
  const a = spawnAt('levy', 0.4, 1.8);
  h.stepTime(1.2);
  assert.equal(P().lockTarget, a);
  assert.equal(P().focusTarget, a);
  h.action({ type: 'slash', dir: 0, src: 'motion', power: 0.5 });
  h.stepTime(0.2);
  assert.ok(Math.abs(angleDiff(P().attackYaw, bearing(a))) <= 0.02, `attackYaw ${P().attackYaw} vs ${bearing(a)}`);
  assert.equal(a.hitsTaken.length, 1);
  // the lock on a far foe; a near one in front is the focus and takes the blow
  gripReset();
  const far = spawnAt('levy', 0, 8);
  const near = spawnAt('levy', 0.3, 1.6);
  h.step(1);
  assert.equal(P().setLock(far), true);
  assert.equal(P().lockTarget, far);
  h.step(1);
  assert.equal(P().focusTarget, near);
  h.action({ type: 'slash', dir: 0, src: 'motion', power: 0.5 });
  h.stepTime(0.2);
  assert.equal(near.hitsTaken.length, 1);
  assert.equal(far.hitsTaken.length, 0);
  assert.equal(P().lockTarget, far, 'the lock stays');
});

/** Spear in hand facing yaw 0, then a foe at `bear` and 12 m, then a motion throw: its velocity yaw. */
function throwAt(grip, bear, { noThrowLock = false } = {}) {
  gripReset(grip);
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.4);
  const foe = spawnAt('levy', bear, 12);
  if (noThrowLock) foe.noThrowLock = true;
  h.step(1);
  h.action({ type: 'throw', src: 'motion', power: 0.8 });
  h.step(4);
  const thrown = h.events('spearThrown');
  assert.equal(thrown.length, 1, `${grip}: thrown`);
  const v = thrown[0].payload.projectile.vel;
  return { yaw: Math.atan2(-v.x, -v.z), foe };
}

test('a throw flies at the locked foe at 12 m (within 0.03 rad), even before the view has turned to him', () => {
  const r = throwAt('hilt', 0.4);
  assert.equal(P().lockTarget, r.foe);
  assert.ok(Math.abs(P().yaw) < 0.05, `the view has barely turned: ${P().yaw}`);
  assert.ok(Math.abs(angleDiff(r.yaw, bearing(r.foe))) <= 0.03, `throw yaw ${r.yaw} vs ${bearing(r.foe)}`);
  // the window grip throws along the view (0.4 rad is outside THROW_ANGLE), as today
  const w = throwAt('window', 0.4);
  assert.ok(Math.abs(w.yaw) < 0.03, `window throw yaw ${w.yaw}`);
  // a noThrowLock lock (the Agoge's near dummy) is never thrown at
  const n = throwAt('hilt', 0.4, { noThrowLock: true });
  assert.equal(P().lockTarget, n.foe);
  assert.ok(Math.abs(n.yaw) < 0.06, `noThrowLock: along the view ${n.yaw}`);
});

test('swipes reach 45 m with the spear in hand and 30 m with the sword; lockSides shows what a swipe would pick', () => {
  gripReset();
  const f = spawnAt('levy', 0, 3);
  const far = spawnAt('archer', -0.5, 40);
  const l = spawnAt('levy', 0.7, 3);
  const sides = P().lockSides;
  h.step(1);
  assert.equal(P().lockTarget, f);
  assert.equal(sides.left, l);
  assert.equal(sides.right, null, 'the archer at 40 m is out of sword range');
  locks.length = 0;
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  h.step(1);
  assert.deepEqual(reasons(), ['none']);
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.15);
  assert.equal(P().lockSides, sides, 'one object, mutated in place');
  assert.equal(sides.right, far, 'recomputed within 0.1 s with the spear in hand');
  h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
  h.step(1);
  assert.equal(P().lockTarget, far);
  // a dead side foe drops out of lockSides within 0.1 s
  h.stepTime(1.5);
  assert.equal(sides.left, f);
  f.takeHit({ damage: 1e9, kind: 'slash', dir: { x: 0, z: -1 }, power: 1 });
  h.stepTime(0.12);
  assert.equal(sides.left, l);
});

test('C.9: leaving the hilt grip clears the lock and keeps the yaw; coming back starts the spring at rest and acquires', () => {
  gripReset();
  const a = spawnAt('levy', 0.5, 3);
  h.stepTime(1.5);
  const y = P().yaw;
  locks.length = 0;
  ctx.input.grip = 'window';
  h.step(1);
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['clear', null, a]]);
  assert.equal(P().lockMode, 'off');
  assert.equal(P().lockTarget, null);
  assert.equal(P().yaw, y);
  assert.deepEqual([P().lockSides.left, P().lockSides.right], [null, null]);
  ctx.input.lookYaw = -0.3;
  h.step(1);
  assert.ok(Math.abs(P().yaw - (y - 0.3)) < 1e-12, 'the window grip follows look input');
  locks.length = 0;
  ctx.input.grip = 'hilt';
  const y1 = P().yaw;
  h.step(1);
  assert.deepEqual(locks.map((e) => [e.reason, e.target]), [['acquire', a]]);
  assert.ok(Math.abs(P().yaw - y1) <= LOCK.maxAccel * DT * DT + 1e-12, 'no jump');
  h.stepTime(1.5);
  assert.ok(Math.abs(P().yaw - bearing(a)) < 0.01);
});

test('phase and dayStart clear the lock to the front; player death clears it to off', () => {
  gripReset();
  const a = spawnAt('levy', 0, 2);
  h.step(1);
  locks.length = 0;
  ctx.events.emit('phase', { day: 3, phase: 2 });
  assert.deepEqual(locks.map((e) => [e.reason, e.target, e.prev]), [['clear', null, a]]);
  assert.equal(P().lockMode, 'front');
  h.step(1);
  assert.equal(P().lockTarget, a, 're-acquired in front');
  ctx.events.emit('dayStart', { day: 3 });
  assert.equal(P().lockTarget, null);
  h.step(1);
  locks.length = 0;
  P().damage(1000, { kind: 'melee' });
  assert.equal(P().alive, false);
  assert.deepEqual(locks.map((e) => [e.reason, e.prev]), [['clear', a]]);
  assert.equal(P().lockMode, 'off');
  h.stepTime(0.5);
  assert.equal(P().lockMode, 'off');
  assert.equal(P().lockTarget, null);
  assert.equal(P().switchTarget(1), false);
  // a new battle starts it again
  gripReset();
  spawnAt('levy', 0, 2);
  h.step(1);
  assert.equal(P().lockMode, 'target');
});

test('touch and the window grip: no lock, the yaw follows input.lookYaw as today, swipes and setLock do nothing', () => {
  for (const grip of [null, 'window']) {
    gripReset(grip);
    const f = spawnAt('levy', 0, 3);
    spawnAt('levy', -0.6, 3);
    h.step(1);
    assert.equal(P().lockTarget, null, `${grip}`);
    assert.equal(P().lockMode, 'off');
    ctx.input.lookYaw = 0.1;
    h.step(1);
    assert.ok(Math.abs(P().yaw - 0.1) < 1e-12, `${grip}: look input`);
    h.action({ type: 'switchTarget', dir: 1, src: 'touch' });
    h.step(1);
    assert.equal(P().switchTarget(1), false);
    assert.equal(P().setLock(f), false);
    ctx.events.emit('enemyWindup', { actor: f, timeToStrike: 1, targetIsPlayer: true });
    ctx.events.emit('phase', { day: 3, phase: 2 });
    h.action({ type: 'recenter', src: 'key' });
    h.stepTime(0.3);
    assert.equal(P().lockTarget, null);
    assert.deepEqual(locks, [], `${grip}: no lockOn`);
    assert.deepEqual([P().lockSides.left, P().lockSides.right], [null, null]);
    assert.equal(P().yaw, 0, `${grip}: the recenter ease as today`);
  }
});
