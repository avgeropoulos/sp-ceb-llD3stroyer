// Unit tests for the portrait slash path of the viewmodel's PURE motion math (gfx/viewmodel/motion.js,
// docs-hilt-grip.md §E.2, §H.1 #11): node --test spartan/tests/unit/viewmodel-hilt.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  RIG, SLASH, slashHalf, slashTimes, slashParam, slashGeometry, createSlashOut, slashEdgeSign, slashHandOffset,
  motionSlashTimes,
} from '../../src/main/assets/game/js/gfx/viewmodel/motion.js';
import { TIMING } from '../../src/main/assets/game/js/game/rules.js';

const close = (a, b, eps = 1e-9, msg = '') => assert.ok(Math.abs(a - b) <= eps, `${msg} ${a} ≠ ${b} (±${eps})`);
const DIRS = [];
for (let i = 0; i < 8; i++) DIRS.push(-Math.PI + (i * Math.PI) / 4);
for (const d of [0.1, 0.37, 1.0, 2.2, -0.6, -1.9, -2.9, 3.0]) DIRS.push(d);
const PORTRAIT = Object.assign({}, SLASH, { half: [0.22, 0.34] });

// slashGeometry as it was before the half-axes (a verbatim copy), the reference for "unchanged".
function shapeParamRef(s) {
  const a = Math.abs(s);
  if (a <= 1) return s;
  return Math.sign(s) * (1 + 0.55 * Math.tanh((a - 1) / 0.55));
}
const _off = [0, 0];
function slashGeometryRef(dir, s, out, reach = RIG.swordReach, P = SLASH) {
  const k = RIG.scale;
  const D = -RIG.slashPlaneZ / k;
  const E = RIG.slashHalf / k;
  const dx = Math.cos(dir);
  const dy = Math.sin(dir);
  const g = shapeParamRef(s);
  const over = Math.max(0, Math.abs(g) - 1);
  const tx = g * E * dx;
  const ty = g * E * dy - over * over * 0.3;
  const tz = -D + over * 0.6;
  const [ox, oy] = slashHandOffset(dir, P, _off);
  const q = P.handQ + P.handQEnd * Math.min(1, g * g);
  let hx = ox + q * tx * (tx < 0 ? P.leftFollow : 1);
  let hy = oy + q * ty * (ty > 0 ? P.upFollow : 1);
  let lx = tx - hx;
  let ly = ty - hy;
  let lat = Math.hypot(lx, ly);
  const maxLat = P.maxLat * reach;
  if (lat > maxLat) {
    lx *= maxLat / lat;
    ly *= maxLat / lat;
    lat = maxLat;
    hx = tx - lx;
    hy = ty - ly;
  }
  const hz = tz + Math.sqrt(reach * reach - lat * lat);
  out.tip[0] = tx; out.tip[1] = ty; out.tip[2] = tz;
  out.hand[0] = hx; out.hand[1] = hy; out.hand[2] = hz;
  out.blade[0] = lx / reach; out.blade[1] = ly / reach; out.blade[2] = (tz - hz) / reach;
  out.motion[0] = dx; out.motion[1] = dy; out.motion[2] = 0;
  return out;
}
const same = (a, b, msg) => {
  for (const key of ['tip', 'hand', 'blade', 'motion']) {
    for (let i = 0; i < 3; i++) assert.equal(a[key][i], b[key][i], `${msg} ${key}[${i}]`);
  }
};

test('slashHalf: [0.22, 0.34] on a 0.45 portrait phone, the landscape circle from a square screen up', () => {
  assert.deepEqual(slashHalf(0.45), [0.22, 0.34]);
  assert.deepEqual(RIG.slashHalfPortrait, [0.22, 0.34]);
  close(RIG.portraitAspect, 0.45);
  for (const a of [2.17, 16 / 9, 4 / 3, 1]) assert.deepEqual(slashHalf(a), [0.35, 0.35], `aspect ${a}`);
  assert.deepEqual(slashHalf(0.3), [0.22, 0.34], 'narrower than the reference phone: clamped');
  assert.deepEqual(slashHalf(NaN), [0.35, 0.35], 'a degenerate aspect reads as landscape');
  // continuous and monotone across portrait, meeting the circle at 1
  let prev = slashHalf(0.45);
  for (let a = 0.46; a < 1; a += 0.01) {
    const h = slashHalf(a);
    assert.ok(h[0] >= prev[0] && h[1] >= prev[1] && h[0] <= 0.35 && h[1] <= 0.35, `monotone at ${a}`);
    assert.ok(h[0] - prev[0] < 0.01, `continuous at ${a}`);
    prev = h;
  }
  close(slashHalf(1 - 1e-9)[0], 0.35, 1e-6, 'meets the circle');
  const out = [9, 9];
  assert.equal(slashHalf(0.6, out), out, 'writes into out');
});

test('slashGeometry without P.half is bit-for-bit what it was', () => {
  const a = createSlashOut();
  const b = createSlashOut();
  const tuned = Object.assign({}, SLASH, { handC: [0.12, -0.16], maxLat: 0.7 });
  for (const dir of DIRS) {
    for (let s = -2.2; s <= 2.8; s += 0.05) {
      same(slashGeometry(dir, s, a), slashGeometryRef(dir, s, b), `default dir=${dir} s=${s}`);
      same(slashGeometry(dir, s, a, 0.5, tuned), slashGeometryRef(dir, s, b, 0.5, tuned), `tuned dir=${dir} s=${s}`);
      // a circular half equal to RIG.slashHalf is the same path
      same(slashGeometry(dir, s, a, RIG.swordReach, Object.assign({}, SLASH, { half: [RIG.slashHalf, RIG.slashHalf] })), slashGeometryRef(dir, s, b), `circle dir=${dir} s=${s}`);
    }
  }
  for (const dir of DIRS) assert.equal(slashEdgeSign(dir), slashEdgeSign(dir, SLASH), 'edge sign with the default tuning');
});

test('portrait path: the tip runs on (0.22·cos dir, 0.34·sin dir)·s in the plane z = -0.55 (vm)', () => {
  const o = createSlashOut();
  const k = RIG.scale;
  for (const dir of DIRS) {
    const dx = Math.cos(dir);
    const dy = Math.sin(dir);
    for (const s of [-1, -0.5, 0, 0.5, 1]) {
      slashGeometry(dir, s, o, RIG.swordReach, PORTRAIT);
      close(o.tip[0] * k, 0.22 * s * dx, 1e-12, `tip x dir=${dir} s=${s}`);
      close(o.tip[1] * k, 0.34 * s * dy, 1e-12, `tip y dir=${dir} s=${s}`);
      close(o.tip[2] * k, -0.55, 1e-12, `tip z dir=${dir} s=${s}`);
      // the blade stays rigid, points away from the eye, and the fist stays well clear of it
      close(Math.hypot(o.tip[0] - o.hand[0], o.tip[1] - o.hand[1], o.tip[2] - o.hand[2]), RIG.swordReach, 1e-9, 'reach');
      close(Math.hypot(...o.blade), 1, 1e-9, 'unit blade');
      assert.ok(o.blade[2] < 0 && o.hand[2] < -0.25, `blade forward, hand depth ${o.hand[2]} dir=${dir} s=${s}`);
      // motion is the unit direction the tip travels in (along the ellipse's chord through the centre)
      const mx = 0.22 * dx;
      const my = 0.34 * dy;
      const m = Math.hypot(mx, my);
      close(o.motion[0], mx / m, 1e-12, 'motion x');
      close(o.motion[1], my / m, 1e-12, 'motion y');
      close(o.motion[2], 0, 0, 'motion z');
    }
  }
});

test('portrait path: crosses the centre at the strike with the same timing as landscape', () => {
  const o = createSlashOut();
  const L = createSlashOut();
  const k = RIG.scale;
  const tms = [slashTimes(TIMING.slash), motionSlashTimes(TIMING.slash.strike * 0.5, TIMING.slash, {}, 1 / 60)];
  for (const tm of tms) {
    for (const dir of DIRS) {
      slashGeometry(dir, slashParam(tm.strike, tm), o, RIG.swordReach, PORTRAIT);
      close(Math.hypot(o.tip[0], o.tip[1]), 0, 1e-12, `tip at the centre at the strike dir=${dir}`);
      close(o.tip[2] * k, -0.55, 1e-12, 'in the plane');
      // the same parameter at every instant: the portrait tip is the landscape tip scaled per axis
      for (let t = 0; t <= tm.total + 1e-9; t += 0.004) {
        const s = slashParam(t, tm);
        if (Math.abs(s) > 1) continue;
        slashGeometry(dir, s, o, RIG.swordReach, PORTRAIT);
        slashGeometry(dir, s, L);
        close(o.tip[0] / 0.22, L.tip[0] / 0.35, 1e-9, `x timing dir=${dir} t=${t}`);
        close(o.tip[1] / 0.34, L.tip[1] / 0.35, 1e-9, `y timing dir=${dir} t=${t}`);
      }
    }
  }
});

test('portrait path: the tip travels along its chord through the strike window, and the edge sign is ±1', () => {
  const tm = slashTimes(TIMING.slash);
  const o = createSlashOut();
  for (const dir of DIRS) {
    const ux = 0.22 * Math.cos(dir);
    const uy = 0.34 * Math.sin(dir);
    let prev = -Infinity;
    for (let t = tm.ta; t <= tm.tc; t += 0.004) {
      slashGeometry(dir, slashParam(t, tm), o, RIG.swordReach, PORTRAIT);
      const along = o.tip[0] * ux + o.tip[1] * uy;
      assert.ok(along >= prev - 1e-12, `dir=${dir} t=${t}`);
      prev = along;
    }
    const e = slashEdgeSign(dir, PORTRAIT);
    assert.ok(e === 1 || e === -1);
  }
});
