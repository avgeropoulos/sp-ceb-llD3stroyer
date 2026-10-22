// Unit tests for the viewmodel's PURE motion math (gfx/viewmodel/motion.js):
// node --test spartan/tests/unit/viewmodel-motion.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  RIG, SLASH, slashTimes, slashParam, slashGeometry, createSlashOut, slashEdgeSign, slashHandOffset,
  thrustCurve, bashCurve, jolt, bump, ramp, smootherstep,
} from '../../src/main/assets/game/js/gfx/viewmodel/motion.js';
import { TIMING } from '../../src/main/assets/game/js/game/rules.js';

const EPS = 1e-9;
const close = (a, b, eps = 1e-6, msg = '') => assert.ok(Math.abs(a - b) <= eps, `${msg} ${a} ≠ ${b} (±${eps})`);
const DIRS = [];
for (let i = 0; i < 8; i++) DIRS.push(-Math.PI + (i * Math.PI) / 4);
for (const d of [0.1, 0.37, 1.0, 2.2, -0.6, -1.9, -2.9, 3.0]) DIRS.push(d); // arbitrary angles too
const TAN = Math.tan(Math.PI / 6); // vm camera fov 60

test('rig constants match the contract', () => {
  close(RIG.slashPlaneZ, -0.55, EPS);
  close(RIG.slashHalf, 0.35, EPS);
  assert.ok(RIG.scale > 0 && RIG.scale <= 1);
});

test('slash timeline: the path parameter is monotonic and crosses 0 exactly at TIMING.slash.strike', () => {
  const tm = slashTimes(TIMING.slash);
  close(tm.strike, TIMING.slash.strike, EPS);
  close(slashParam(TIMING.slash.strike, tm), 0, 1e-12, 's(strike)');
  assert.ok(tm.ta > 0 && tm.ta < tm.strike, 'anticipation before the strike');
  assert.ok(tm.tc > tm.strike && tm.tc < tm.total, 'follow-through ends before total');
  assert.ok(tm.recover < tm.back && tm.back <= tm.total, 'recovery finishes within total');
  assert.ok(slashParam(0, tm) < -1, 'starts cocked beyond the path start');
  assert.ok(slashParam(tm.total, tm) > 1, 'follows through beyond the path end');
  let prev = -Infinity;
  for (let t = 0; t <= tm.total + 1e-9; t += 0.002) {
    const s = slashParam(t, tm);
    assert.ok(s >= prev - 1e-12, `monotonic at t=${t}`);
    prev = s;
  }
  // Fastest at the strike: a centred difference there beats one 20 ms either side.
  const v = (t) => (slashParam(t + 1e-4, tm) - slashParam(t - 1e-4, tm)) / 2e-4;
  assert.ok(v(tm.strike) > v(tm.strike - 0.02) && v(tm.strike) > v(tm.strike + 0.02));
});

test('slash tip path: in the plane z = -0.55 (vm), from -0.35·d to +0.35·d along dir', () => {
  const o = createSlashOut();
  const k = RIG.scale;
  for (const dir of DIRS) {
    const dx = Math.cos(dir);
    const dy = Math.sin(dir);
    for (const s of [-1, -0.5, 0, 0.5, 1]) {
      slashGeometry(dir, s, o);
      close(o.tip[2] * k, -0.55, 1e-9, `tip z dir=${dir} s=${s}`);
      close(o.tip[0] * k, 0.35 * s * dx, 1e-9, `tip x dir=${dir} s=${s}`);
      close(o.tip[1] * k, 0.35 * s * dy, 1e-9, `tip y dir=${dir} s=${s}`);
    }
  }
});

test('slash: the blade is rigid (hand to tip = reach) and points from the grip to the tip', () => {
  const o = createSlashOut();
  for (const dir of DIRS) {
    for (let s = -1.6; s <= 2.8; s += 0.1) {
      slashGeometry(dir, s, o);
      const L = Math.hypot(o.tip[0] - o.hand[0], o.tip[1] - o.hand[1], o.tip[2] - o.hand[2]);
      close(L, RIG.swordReach, 1e-9, `reach dir=${dir} s=${s}`);
      for (let i = 0; i < 3; i++) close(o.hand[i] + o.blade[i] * RIG.swordReach, o.tip[i], 1e-9, 'blade dir');
      close(Math.hypot(...o.blade), 1, 1e-9, 'unit blade');
      assert.ok(o.blade[2] < 0, 'the blade points away from the eye');
    }
  }
});

test('slash: the tip travels along dir through the strike window', () => {
  const tm = slashTimes(TIMING.slash);
  const o = createSlashOut();
  for (const dir of DIRS) {
    const dx = Math.cos(dir);
    const dy = Math.sin(dir);
    let prev = -Infinity;
    for (let t = tm.ta; t <= tm.tc; t += 0.004) {
      slashGeometry(dir, slashParam(t, tm), o);
      const along = o.tip[0] * dx + o.tip[1] * dy;
      assert.ok(along >= prev - 1e-9, `dir=${dir} t=${t}`);
      prev = along;
    }
  }
});

test('slash: at the strike the fist is on screen at every phone aspect and well clear of the eye', () => {
  const o = createSlashOut();
  for (const dir of DIRS) {
    slashGeometry(dir, 0, o);
    const [x, y, z] = o.hand;
    assert.ok(z < -0.25, `hand depth ${z} dir=${dir}`);
    const nx = x / -z / TAN;
    const ny = y / -z / TAN;
    assert.ok(Math.abs(ny) < 0.98 && Math.abs(nx) < 4 / 3, `hand at (${nx.toFixed(2)}, ${ny.toFixed(2)}) dir=${dir}`);
    // The blade crosses the view at a readable angle to the cut, not along it.
    const off = slashHandOffset(dir);
    const ang = Math.abs(Math.sin(Math.atan2(off[1], off[0]) - dir));
    assert.ok(ang >= Math.sin(SLASH.minAngle) - 1e-9, `hand offset angle dir=${dir}`);
  }
});

test('slashEdgeSign is ±1 and deterministic', () => {
  for (const dir of DIRS) {
    const a = slashEdgeSign(dir);
    assert.ok(a === 1 || a === -1);
    assert.equal(slashEdgeSign(dir), a);
  }
});

test('thrust and bash curves peak at the strike and settle by the end', () => {
  for (const kind of ['thrust', 'spearThrust']) {
    const { strike, total } = TIMING[kind];
    close(thrustCurve(strike, strike, total), 1, 1e-9, kind);
    assert.ok(thrustCurve(strike * 0.3, strike, total) <= 0, `${kind} draws back first`);
    close(thrustCurve(total, strike, total), 0, 1e-9, `${kind} end`);
    for (let t = 0; t <= total; t += 0.005) assert.ok(thrustCurve(t, strike, total) <= 1 + 1e-9);
  }
  const { strike, total } = TIMING.bash;
  close(bashCurve(strike, strike, total), 1, 1e-9, 'bash');
  close(bashCurve(total, strike, total), 0, 1e-9, 'bash end');
  close(bashCurve(0, strike, total), 0, 1e-9, 'bash start');
});

test('easing helpers', () => {
  close(smootherstep(0), 0, EPS);
  close(smootherstep(1), 1, EPS);
  close(smootherstep(0.5), 0.5, EPS);
  close(ramp(1, 2, 0.5), 0, EPS);
  close(ramp(1, 2, 3), 1, EPS);
  close(bump(0.5, 0, 0.2, 0.8, 1), 1, EPS);
  close(bump(1.2, 0, 0.2, 0.8, 1), 0, EPS);
  close(jolt(0, 5, 3), 1, EPS);
  assert.ok(Math.abs(jolt(2, 5, 3)) < 0.01);
  close(jolt(-1, 5, 3), 0, EPS);
});
