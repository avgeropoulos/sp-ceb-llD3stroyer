// Unit tests for core/noise.js (PURE): determinism, range, continuity and tiling.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createNoise } from '../../src/main/assets/game/js/core/noise.js';

const samples = (n, fn) => {
  const out = [];
  for (let i = 0; i < n; i++) out.push(fn(i));
  return out;
};
// Deterministic pseudo-random coordinates spread over a wide domain (negatives included).
const coord = (i, k) => Math.sin(i * 12.9898 + k * 78.233) * 437.5453;

test('same seed gives identical values; different seeds differ', () => {
  const a = createNoise(7);
  const b = createNoise(7);
  const c = createNoise(8);
  let diff = 0;
  for (let i = 0; i < 2000; i++) {
    const x = coord(i, 1), y = coord(i, 2), z = coord(i, 3);
    assert.equal(a.noise2(x, y), b.noise2(x, y));
    assert.equal(a.noise3(x, y, z), b.noise3(x, y, z));
    assert.equal(a.fbm2(x, y), b.fbm2(x, y));
    assert.equal(a.ridge2(x, y, 4), b.ridge2(x, y, 4));
    assert.equal(a.pfbm2(x, y, 8, 8, 4), b.pfbm2(x, y, 8, 8, 4));
    if (Math.abs(a.noise2(x, y) - c.noise2(x, y)) > 1e-6) diff++;
  }
  assert.ok(diff > 1500, `seeds 7 and 8 should differ (only ${diff} differed)`);
});

test('values stay in about [-1, 1] and use the range', () => {
  const n = createNoise(3);
  const fns = {
    noise2: (i) => n.noise2(coord(i, 1) * 0.1, coord(i, 2) * 0.1),
    noise3: (i) => n.noise3(coord(i, 1) * 0.1, coord(i, 2) * 0.1, coord(i, 3) * 0.1),
    fbm2: (i) => n.fbm2(coord(i, 1) * 0.05, coord(i, 2) * 0.05),
    ridge2: (i) => n.ridge2(coord(i, 1) * 0.05, coord(i, 2) * 0.05, 5),
    warp2: (i) => n.warp2(coord(i, 1) * 0.05, coord(i, 2) * 0.05),
    pnoise2: (i) => n.pnoise2(coord(i, 1) * 0.1, coord(i, 2) * 0.1, 16, 16),
    pfbm2: (i) => n.pfbm2(coord(i, 1) * 0.05, coord(i, 2) * 0.05, 8, 8, 5),
    pridge2: (i) => n.pridge2(coord(i, 1) * 0.05, coord(i, 2) * 0.05, 8, 8, 5),
  };
  for (const [name, fn] of Object.entries(fns)) {
    const v = samples(20000, fn);
    const lo = Math.min(...v);
    const hi = Math.max(...v);
    const mean = v.reduce((s, x) => s + x, 0) / v.length;
    assert.ok(lo >= -1.05 && hi <= 1.05, `${name} out of range: [${lo}, ${hi}]`);
    assert.ok(hi - lo > 0.8, `${name} has too little spread: [${lo}, ${hi}]`);
    assert.ok(v.every(Number.isFinite), `${name} produced a non-finite value`);
    if (name !== 'ridge2' && name !== 'pridge2') assert.ok(Math.abs(mean) < 0.15, `${name} mean ${mean} is biased`);
  }
});

test('noise is continuous', () => {
  const n = createNoise(11);
  for (let i = 0; i < 2000; i++) {
    const x = coord(i, 1) * 0.1, y = coord(i, 2) * 0.1;
    const e = 1e-4;
    assert.ok(Math.abs(n.noise2(x + e, y) - n.noise2(x, y)) < 0.01);
    assert.ok(Math.abs(n.noise3(x, y + e, 0.3) - n.noise3(x, y, 0.3)) < 0.01);
    assert.ok(Math.abs(n.pnoise2(x + e, y, 8, 8) - n.pnoise2(x, y, 8, 8)) < 0.01);
  }
});

test('periodic noise tiles exactly with its period', () => {
  const n = createNoise(5);
  for (let i = 0; i < 500; i++) {
    const x = (coord(i, 1) % 40) + 0.123;
    const y = (coord(i, 2) % 40) + 0.456;
    assert.ok(Math.abs(n.pnoise2(x, y, 6, 9) - n.pnoise2(x + 6, y - 9, 6, 9)) < 1e-9);
    assert.ok(Math.abs(n.pfbm2(x, y, 4, 4, 5) - n.pfbm2(x + 4, y + 8, 4, 4, 5)) < 1e-9);
    assert.ok(Math.abs(n.pridge2(x, y, 3, 5, 4) - n.pridge2(x - 3, y + 5, 3, 5, 4)) < 1e-9);
    const a = n.worley2(x, y, 7, 7, new Float64Array(5));
    const b = n.worley2(x + 7, y - 14, 7, 7, new Float64Array(5));
    assert.ok(Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && a[2] === b[2]);
  }
});

test('worley returns ordered distances and a stable cell id', () => {
  const n = createNoise(9);
  const out = [0, 0, 0, 0, 0];
  for (let i = 0; i < 2000; i++) {
    const x = Math.abs(coord(i, 1)) % 16, y = Math.abs(coord(i, 2)) % 16;
    n.worley2(x, y, 16, 16, out);
    assert.ok(out[0] >= 0 && out[0] <= out[1], 'F1 <= F2');
    assert.ok(out[0] < 1.5, 'F1 within a cell and a half');
    assert.ok(out[2] >= 0 && out[2] < 1, 'id in [0,1)');
  }
});

test('hash2 is deterministic and uniform enough', () => {
  const n = createNoise(1);
  const bins = new Array(10).fill(0);
  for (let i = 0; i < 100; i++) for (let j = 0; j < 100; j++) bins[Math.floor(n.hash2(i, j) * 10)]++;
  for (const b of bins) assert.ok(b > 850 && b < 1150, `bin count ${b}`);
  assert.equal(n.hash2(3, 4), createNoise(1).hash2(3, 4));
});

test('fbm2 honours octave and gain parameters', () => {
  const n = createNoise(2);
  const x = 1.234, y = -5.678;
  assert.equal(n.fbm2(x, y, 1), n.noise2(x, y));
  assert.notEqual(n.fbm2(x, y, 5, 2, 0.5), n.fbm2(x, y, 5, 2, 0.7));
});

test('fbmField and worleyField are deterministic and tile seamlessly', async () => {
  const { sampleField } = await import('../../src/main/assets/game/js/core/noise.js');
  const S = 64;
  for (const opts of [{ octaves: 5 }, { octaves: 4, ridged: true }, { octaves: 3, turbulence: true, periodY: 8 }]) {
    const a = createNoise(4).fbmField(S, 4, opts);
    const b = createNoise(4).fbmField(S, 4, opts);
    assert.deepEqual(a, b);
    let interior = 0;
    let wrap = 0;
    for (let y = 0; y < S; y++) {
      for (let x = 0; x < S - 1; x++) interior = Math.max(interior, Math.abs(a[y * S + x + 1] - a[y * S + x]));
      wrap = Math.max(wrap, Math.abs(a[y * S] - a[y * S + S - 1]));
    }
    for (let x = 0; x < S; x++) wrap = Math.max(wrap, Math.abs(a[x] - a[(S - 1) * S + x]));
    assert.ok(wrap <= interior * 1.05 + 1e-6, `wrap step ${wrap} vs interior ${interior}`);
    for (const v of a) assert.ok(v >= -1.05 && v <= 1.05 && Number.isFinite(v));
  }
  const w = createNoise(4).worleyField(S, 8, 4);
  const w2 = createNoise(4).worleyField(S, 8, 4);
  assert.deepEqual(w.f1, w2.f1);
  let jumps = 0;
  for (let y = 0; y < S; y++) if (Math.abs(w.f1[y * S] - w.f1[y * S + S - 1]) > 0.3) jumps++;
  assert.ok(jumps === 0, 'worley field wraps');
  for (let k = 0; k < S * S; k++) assert.ok(w.f1[k] <= w.f2[k]);
  // Bilinear sampling wraps and interpolates.
  assert.equal(sampleField(a64(), 4, 0, 0), 0);
  assert.equal(sampleField(a64(), 4, 4, 4), 0);
  assert.equal(sampleField(a64(), 4, 0.5, 0), 0.5);
  assert.equal(sampleField(a64(), 4, -0.5, 0), 1.5);
});

function a64() {
  // 4×4 ramp in x: 0,1,2,3 per row.
  const f = new Float32Array(16);
  for (let y = 0; y < 4; y++) for (let x = 0; x < 4; x++) f[y * 4 + x] = x;
  return f;
}

test('worleyField vectors point at the nearest feature point', () => {
  const S = 64;
  const w = createNoise(6).worleyField(S, 5, 9, 0.9, 3, true);
  const plain = createNoise(6).worleyField(S, 5, 9, 0.9, 3);
  assert.deepEqual(w.f1, plain.f1);
  assert.equal(plain.dx, undefined);
  for (let k = 0; k < S * S; k++) {
    assert.ok(Math.abs(Math.hypot(w.dx[k], w.dy[k]) - w.f1[k]) < 1e-4, `|d| = F1 at ${k}`);
  }
  // Within one cell the vector changes by exactly the pixel step (a plane through the point).
  let same = 0;
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S - 1; x++) {
      const k = y * S + x;
      if (w.id[k] !== w.id[k + 1]) continue;
      same++;
      assert.ok(Math.abs(w.dx[k] - w.dx[k + 1] - (5 / S) * Math.sqrt(9 / 5)) < 1e-4);
    }
  }
  assert.ok(same > S * S * 0.8);
});
