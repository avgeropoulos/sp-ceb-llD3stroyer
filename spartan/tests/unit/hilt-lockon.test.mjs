// game/lockon.js (docs-hilt-grip.md §C.1, §C.3, §C.4, H.1 #5): the hilt grip's lock-on, PURE.
// Candidate scoring and acquisition, the thumb swipe's directional switch (with the worked
// ranking examples of §C.1), the live-list check, and the yaw spring's timing guarantees.
// node --test spartan/tests/unit/hilt-lockon.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  LOCK, YawSpring, isLockable, inActors, lockScore, acquire, nextInDirection,
} from '../../src/main/assets/game/js/game/lockon.js';

const PI = Math.PI;
const DEG = PI / 180;
const ORIGIN = Object.freeze({ x: 0, z: 0 });

/** A Persian at camera bearing `bearing` (0 ahead, + left) and `dist` metres from the origin. */
function foe(bearing, dist, o = {}) {
  return {
    pos: { x: -Math.sin(bearing) * dist, z: -Math.cos(bearing) * dist },
    alive: true, team: 'persian', role: 'melee', state: 'engage', targetIsPlayer: false, ...o,
  };
}
const at = (x, z, o = {}) => ({ pos: { x, z }, alive: true, team: 'persian', role: 'melee', state: 'engage', targetIsPlayer: false, ...o });

test('LOCK is frozen and holds the §C.1 numbers', () => {
  assert.ok(Object.isFrozen(LOCK));
  assert.equal(LOCK.acquireRange, 14);
  assert.equal(LOCK.switchRange, 30);
  assert.equal(LOCK.spearRange, 45);
  assert.equal(LOCK.keepRange, 22);
  assert.equal(LOCK.threatAngle, 1.31);
  assert.equal(LOCK.maxRate, 2.2);
  assert.equal(LOCK.fastRate, 3.0);
});

test('isLockable: a living Persian that is not the helot, retreating or dead; archers too', () => {
  assert.equal(isLockable(foe(0, 3)), true);
  assert.equal(isLockable(foe(0, 3, { role: 'archer' })), true);
  assert.equal(isLockable(foe(0, 3, { role: 'dummy' })), true, 'the Agoge dummies');
  assert.equal(isLockable(foe(0, 3, { alive: false })), false);
  assert.equal(isLockable(foe(0, 3, { state: 'dead' })), false);
  assert.equal(isLockable(foe(0, 3, { state: 'retreat' })), false);
  assert.equal(isLockable(foe(0, 3, { role: 'helot' })), false);
  assert.equal(isLockable(foe(0, 3, { team: 'greek', role: 'ally' })), false);
  assert.equal(isLockable(null), false);
});

test('lockScore: angle + 0.12 per metre, less the wind-up and engaged bonuses', () => {
  const a = foe(0.5, 4);
  assert.ok(Math.abs(lockScore(a, ORIGIN, 0) - (0.5 + 0.12 * 4)) < 1e-12);
  a.targetIsPlayer = true;
  assert.ok(Math.abs(lockScore(a, ORIGIN, 0) - (0.5 + 0.48 - 0.25)) < 1e-12);
  a.state = 'windup';
  assert.ok(Math.abs(lockScore(a, ORIGIN, 0) - (0.5 + 0.48 - 0.25 - 0.6)) < 1e-12);
  // the angle is measured from the view yaw
  assert.ok(Math.abs(lockScore(foe(0.5, 4), ORIGIN, 0.5) - 0.48) < 1e-12);
});

test('acquire picks the front-centre foe over a nearer one at 1.0 rad; the bonuses reorder', () => {
  const front = foe(0, 4);
  const side = foe(1.0, 2);
  assert.equal(acquire([side, front], ORIGIN, 0), front);
  // engaged alone does not beat the centre at 1.0 rad (1.24 − 0.25 > 0.48) ...
  side.targetIsPlayer = true;
  assert.equal(acquire([side, front], ORIGIN, 0), front);
  // ... winding up at the player does (1.24 − 0.85 < 0.48)
  side.state = 'windup';
  assert.equal(acquire([side, front], ORIGIN, 0), side);
  // the engaged bonus reorders a closer contest: 0.3 rad at 3 m (0.66) vs 0 rad at 4 m (0.48)
  const near = foe(0.3, 3);
  assert.equal(acquire([near, front], ORIGIN, 0), front);
  near.targetIsPlayer = true;
  assert.equal(acquire([near, front], ORIGIN, 0), near);
});

test('acquire: only around the view, within 14 m, never an archer, the helot or the dead', () => {
  assert.equal(acquire([foe(1.2, 3)], ORIGIN, 0), null, 'outside the 1.15 rad cone');
  assert.ok(acquire([foe(1.2, 3)], ORIGIN, 0.2), 'inside it once the view turns');
  assert.equal(acquire([foe(0, 14.5)], ORIGIN, 0), null, 'beyond acquireRange');
  assert.ok(acquire([foe(0, 13.9)], ORIGIN, 0));
  assert.equal(acquire([foe(0, 5, { role: 'archer' })], ORIGIN, 0), null, 'archers only through switching');
  assert.equal(acquire([foe(0, 5, { role: 'helot' }), foe(0, 3, { alive: false }), foe(0.1, 3, { team: 'greek' })], ORIGIN, 0), null);
  assert.equal(acquire([], ORIGIN, 0), null);
  assert.equal(acquire(null, ORIGIN, 0), null);
  // a kill on the left never swings the view to a foe far on the right
  assert.equal(acquire([foe(-1.6, 2)], ORIGIN, 0.9), null);
});

test('nextInDirection finds right (+1) and left (−1), and returns null when nothing lies that way', () => {
  const cur = foe(0, 3);
  const r = foe(-0.6, 3);
  const l = foe(0.7, 3);
  const list = [cur, r, l];
  assert.equal(nextInDirection(list, ORIGIN, 0, cur, 1, 30), r);
  assert.equal(nextInDirection(list, ORIGIN, 0, cur, -1, 30), l);
  assert.equal(nextInDirection([cur, l], ORIGIN, 0, cur, 1, 30), null, 'no wrap to the other side');
  assert.equal(nextInDirection([cur], ORIGIN, 0, cur, -1, 30), null);
  assert.equal(nextInDirection(list, ORIGIN, 0, cur, 0, 30), null, 'dir must be ±1');
  // the current one is never picked, and the dead are skipped
  assert.equal(nextInDirection([cur, foe(-0.6, 3, { alive: false })], ORIGIN, 0, cur, 1, 30), null);
});

test('nextInDirection honours sideMin, the sideGap rule, the range and reaches the rear', () => {
  // sideMin: without a current foe, 0.04 rad is not "to the right", 0.06 is
  assert.equal(nextInDirection([foe(-0.04, 5)], ORIGIN, 0, null, 1, 30), null);
  assert.ok(nextInDirection([foe(-0.06, 5)], ORIGIN, 0, null, 1, 30));
  // sideGap: a foe nearly behind the current one (0.6 m to the side at 6 m) is not beside him
  const cur = foe(0, 2);
  // (+x is to the right of a view along −Z)
  const behind = at(0.6, -6);
  assert.equal(nextInDirection([cur, behind], ORIGIN, 0, cur, 1, 30), null, `rel ${Math.atan2(0.6, 6).toFixed(3)} < min ${(0.05 + Math.atan2(0.4, 2)).toFixed(3)}`);
  assert.equal(nextInDirection([behind], ORIGIN, 0, null, 1, 30), behind, 'without a current foe, sideMin alone');
  const beside = at(2.0, -6);
  assert.equal(nextInDirection([cur, behind, beside], ORIGIN, 0, cur, 1, 30), beside);
  // range: the sword's 30 m, the spear's 45 m
  const far = foe(-0.5, 40);
  assert.equal(nextInDirection([cur, far], ORIGIN, 0, cur, 1, LOCK.switchRange), null);
  assert.equal(nextInDirection([cur, far], ORIGIN, 0, cur, 1, LOCK.spearRange), far);
  // the rear: |rel| up to π, so repeated swipes walk round the player
  const rearR = foe(-2.9, 2);
  assert.equal(nextInDirection([cur, rearR], ORIGIN, 0, cur, 1, 30), rearR);
  const backL = foe(PI - 0.01, 2);
  const backR = foe(-PI + 0.01, 2);
  assert.equal(nextInDirection([cur, backL], ORIGIN, 0, cur, -1, 30), backL, 'just left of straight behind: left');
  assert.equal(nextInDirection([cur, backR], ORIGIN, 0, cur, 1, 30), backR, 'just right of it: right');
  // repeated right swipes walk round through the rear: front → 35° right → 145° right → behind → front
  const ring = [cur, foe(-35 * DEG, 2), foe(-145 * DEG, 2), backL];
  let from = cur;
  const seen = [];
  for (let k = 0; k < 4; k++) {
    const n = nextInDirection(ring, ORIGIN, Math.atan2(-from.pos.x, -from.pos.z), from, 1, 30);
    if (!n) break;
    seen.push(ring.indexOf(n));
    from = n;
  }
  assert.deepEqual(seen, [1, 2, 3, 0], 'right swipes reach the rear');
});

test('ranking: the slot foe beats a far one a few degrees off; ties go to the nearer', () => {
  // Battle: from the engaged foe ahead at 1.6 m, the 35° slot foe at 1.6 m (0.61) beats a queued
  // foe at 8° and 20 m (2.18), and one at 20° and 20 m (2.39).
  const cur = foe(0, 1.6);
  const slot = foe(-35 * DEG, 1.6);
  const queued8 = foe(-8 * DEG, 20);
  const queued20 = foe(-20 * DEG, 20);
  assert.equal(nextInDirection([cur, queued8, slot], ORIGIN, 0, cur, 1, 30), slot);
  assert.equal(nextInDirection([cur, queued20, slot], ORIGIN, 0, cur, 1, 30), slot);
  assert.equal(nextInDirection([cur, queued20], ORIGIN, 0, cur, 1, 30), queued20, 'reached when nothing nearer lies that way');
  // Agoge lock step: from the centre dummy (0, −2.6) a right swipe picks the right dummy
  // (1.6, −2.2), rank 0.63, not the far dummy (1.5, −18), which lies within the sideGap angle.
  const centre = at(0, -2.6, { role: 'dummy' });
  const right = at(1.6, -2.2, { role: 'dummy' });
  const farD = at(1.5, -18, { role: 'dummy' });
  const left = at(-1.6, -2.2, { role: 'dummy' });
  const agoge = [centre, farD, right, left];
  assert.equal(nextInDirection(agoge, ORIGIN, 0, centre, 1, 30), right);
  assert.equal(nextInDirection(agoge, ORIGIN, 0, centre, -1, 30), left);
  assert.equal(nextInDirection([centre, farD], ORIGIN, 0, centre, 1, 30), null, 'the far dummy is not beside the centre one');
  // ties (both within swipeNear, the same bearing) go to the nearer
  const n1 = foe(-0.5, 1.5);
  const n2 = foe(-0.5, 2.5);
  assert.equal(nextInDirection([n2, n1], ORIGIN, 0, null, 1, 30), n1);
  assert.equal(nextInDirection([n1, n2], ORIGIN, 0, null, 1, 30), n1);
});

test('an archer at 30 m: picked when he is the only foe that way, after a nearer one otherwise', () => {
  const cur = foe(0, 2);
  const archer = foe(-0.5, 30, { role: 'archer' });
  assert.equal(nextInDirection([cur, archer], ORIGIN, 0, cur, 1, LOCK.switchRange), archer);
  const nearer = foe(-1.0, 2);
  assert.equal(nextInDirection([cur, archer, nearer], ORIGIN, 0, cur, 1, LOCK.switchRange), nearer);
});

test('inActors: true for a listed actor, false once the list was cleared (ai.clear)', () => {
  const a = foe(0, 3);
  const list = [foe(1, 2), a];
  assert.equal(inActors(a, list), true);
  list.length = 0;
  assert.equal(inActors(a, list), false);
  assert.equal(inActors(null, list), false);
  assert.equal(inActors(a, null), false);
});

/** Runs a spring at fixed dt from `from` toward `to` for `seconds`; per-step records. */
function runSpring(from, to, seconds, { dt = 1 / 60, fast = false, desiredAt = null } = {}) {
  const s = new YawSpring();
  s.reset(from);
  let yaw = from;
  const steps = [];
  for (let i = 1; i <= Math.round(seconds / dt); i++) {
    const desired = desiredAt ? desiredAt(i * dt) : to;
    const v0 = s.vel;
    const y = s.step(yaw, desired, dt, fast);
    steps.push({ t: i * dt, yaw: y, dy: y - yaw, dv: s.vel - v0, moving: s.moving });
    yaw = y;
  }
  return { s, steps, yaw };
}

test('YawSpring: 0 → 1.0 rad at 60 Hz is monotone, rate- and accel-limited, close by 0.8 s, at rest by 1.2 s', () => {
  const { steps } = runSpring(0, 1.0, 2);
  let overshoot = 0;
  for (const st of steps) {
    assert.ok(st.dy >= -1e-12, `monotone at ${st.t.toFixed(3)}: ${st.dy}`);
    assert.ok(Math.abs(st.dy) <= LOCK.maxRate / 60 + 1e-9, `step ${st.dy}`);
    assert.ok(Math.abs(st.dv) <= LOCK.maxAccel / 60 + 1e-9, `dv ${st.dv}`);
    overshoot = Math.max(overshoot, st.yaw - 1.0);
  }
  assert.ok(overshoot < 0.005, `overshoot ${overshoot}`);
  const by08 = steps.find((st) => Math.abs(st.t - 0.8) < 1e-6);
  assert.ok(Math.abs(1.0 - by08.yaw) <= 0.035, `at 0.8 s: ${by08.yaw}`);
  const by12 = steps.find((st) => Math.abs(st.t - 1.2) < 1e-6);
  assert.equal(by12.moving, false, 'at rest by 1.2 s');
  assert.ok(Math.abs(1.0 - by12.yaw) < LOCK.settleErr + 1e-9);
  // and it stays there
  assert.ok(steps.filter((st) => st.t > 1.2).every((st) => st.dy === 0 && !st.moving));
});

test('YawSpring: the §C.4 timings (35° within 2° in ≈ 0.6 s, 90° in ≈ 1.0 s), with no overshoot', () => {
  for (const [deg, within] of [[35, 0.62], [90, 1.05]]) {
    const to = deg * DEG;
    const { steps } = runSpring(0, to, 2.5);
    const t2 = steps.find((st) => Math.abs(to - st.yaw) < 2 * DEG).t;
    assert.ok(t2 <= within, `${deg}°: within 2° at ${t2.toFixed(3)} s`);
    assert.ok(steps.every((st) => st.yaw <= to + 0.005), `${deg}°: no overshoot`);
    assert.ok(steps.every((st) => st.dy >= -1e-12), `${deg}°: never reverses`);
  }
  // negative turns and turns through ±π take the short way
  const neg = runSpring(0, -0.8, 2);
  assert.ok(neg.steps.every((st) => st.dy <= 1e-12) && Math.abs(neg.yaw + 0.8) < 0.005);
  const wrap = runSpring(3.0, -3.0, 2); // 0.28 rad through π
  assert.ok(wrap.steps.every((st) => Math.abs(st.dy) < 0.05 || Math.abs(st.dy) > 6), 'no long way round');
  assert.ok(Math.abs(Math.abs(wrap.yaw) - 3.0) < 0.005 && wrap.yaw < 0, `ends at ${wrap.yaw}`);
});

test('YawSpring: a ±0.02 rad swaying target leaves a resting camera at rest', () => {
  const { steps } = runSpring(0, 0, 5, { desiredAt: (t) => 0.02 * Math.sin(t * 9) });
  assert.ok(steps.every((st) => st.dy === 0 && !st.moving));
  // beyond the dead zone it moves
  const big = runSpring(0, 0, 1, { desiredAt: () => 0.05 });
  assert.ok(big.steps.some((st) => st.moving));
});

test('YawSpring: fast mode honours fastRate and fastAccel; π into the block arc in ≈ 0.70 s', () => {
  const { steps } = runSpring(0, PI - 1e-6, 3, { fast: true });
  assert.ok(steps.every((st) => Math.abs(st.dy) <= LOCK.fastRate / 60 + 1e-9 && Math.abs(st.dv) <= LOCK.fastAccel / 60 + 1e-9));
  assert.ok(steps.some((st) => Math.abs(st.dy) > LOCK.maxRate / 60 + 1e-6), 'faster than the normal rate');
  const into = steps.find((st) => PI - st.yaw <= LOCK.threatAngle).t;
  assert.ok(into <= 0.72, `into the block arc at ${into.toFixed(3)} s`);
});

test('YawSpring: dt 0.05 is stable and larger steps are clamped to it', () => {
  const { steps, yaw, s } = runSpring(0, 1.0, 3, { dt: 0.05 });
  assert.ok(steps.every((st) => st.dy >= -1e-12 && Math.abs(st.dy) <= LOCK.maxRate * 0.05 + 1e-9));
  assert.ok(Math.abs(yaw - 1.0) < LOCK.settleErr && !s.moving);
  const s2 = new YawSpring();
  s2.reset(0);
  const y = s2.step(0, 1.0, 0.5);
  assert.ok(Math.abs(y) <= LOCK.maxAccel * 0.05 * 0.05 + 1e-9, `a 0.5 s step moves like 0.05 s: ${y}`);
  assert.equal(s2.step(y, 1.0, 0), y, 'dt 0 changes nothing');
  assert.equal(s2.step(y, 1.0, NaN), y);
});

test('YawSpring: reset stops it where it is', () => {
  const s = new YawSpring();
  s.reset(0);
  let yaw = 0;
  for (let i = 0; i < 20; i++) yaw = s.step(yaw, 1.0, 1 / 60);
  assert.ok(s.moving && s.vel > 0);
  s.reset(yaw);
  assert.equal(s.vel, 0);
  assert.equal(s.moving, false);
  assert.equal(s.yaw, yaw);
  // the next step starts from rest: Δv ≤ accel·dt
  const y2 = s.step(yaw, 1.0, 1 / 60);
  assert.ok(y2 - yaw <= (LOCK.maxAccel / 60) / 60 + 1e-12);
});
