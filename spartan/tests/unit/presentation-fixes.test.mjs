// Regression tests for the presentation fixer's review fixes (ui/hud.js, gfx/viewmodel/*): the hurt
// arc that kept reading as mauve over the sky as it faded, and the elbow seam where the upper-arm
// and forearm tubes cut through each other. The death fall, the dory pose, the helmet mask and the
// title contrast are checked in the real game by spartan/tests/e2e/presentation-checks.mjs.
// Run: node --test spartan/tests/unit/presentation-fixes.test.mjs
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

const { arcOpacity, arcBacking, arcWidth } = await import('../../src/main/assets/game/js/ui/hud.js');
const { upperArmParts, forearmParts } = await import('../../src/main/assets/game/js/gfx/viewmodel/meshes.js');

const LIFE = 1.4;
const ages = (step = 0.02) => {
  const out = [];
  for (let t = 0; t < LIFE + 0.1; t += step) out.push(+t.toFixed(4));
  return out;
};

/** Hue in degrees of an sRGB triple (0..255). */
function hue([r, g, b]) {
  const mx = Math.max(r, g, b);
  const mn = Math.min(r, g, b);
  if (mx === mn) return 0;
  let h;
  if (mx === r) h = ((g - b) / (mx - mn)) % 6;
  else if (mx === g) h = (b - r) / (mx - mn) + 2;
  else h = (r - g) / (mx - mn) + 4;
  return (h * 60 + 360) % 360;
}

test('hurt arc: the red fades from its peak, holds a floor until 1.2 s, then reaches 0 by the end of its life', () => {
  for (const peak of [0.6, 0.8, 1]) {
    let prev = Infinity;
    for (const t of ages()) {
      const op = arcOpacity(t, LIFE, peak);
      assert.ok(op <= prev + 1e-9, `non-increasing at ${t}s (peak ${peak})`);
      prev = op;
      if (t < 1.2) assert.ok(op >= 0.4 - 1e-9, `floor holds at ${t}s: ${op}`);
      if (t >= LIFE) assert.equal(op, 0);
    }
    assert.ok(Math.abs(arcOpacity(0, LIFE, peak) - peak) < 1e-9, 'starts at the peak');
  }
  assert.equal(arcOpacity(-1), 0);
  assert.equal(arcOpacity(NaN), 0);
});

test('hurt arc: the dark backing outlasts the red and the stroke thins from 9 to 3 units', () => {
  for (const t of ages()) {
    const r = arcOpacity(t, LIFE, 0.8);
    const b = arcBacking(t, LIFE, 0.8);
    assert.ok(b >= r - 1e-9 && b <= 1, `backing ${b} vs red ${r} at ${t}s`);
  }
  assert.equal(arcWidth(0), 9);
  assert.ok(Math.abs(arcWidth(LIFE) - 3) < 1e-9);
  assert.ok(arcWidth(0.7) < 9 && arcWidth(0.7) > 3);
});

test('hurt arc over a blue sky stays red (hue within 25° of red) until 1.2 s, for every hit strength', () => {
  // The composite the HUD draws: the red stroke (opacity R) over the dark backing (opacity B times
  // its gradient's 0.75 core), over the sky. Colours from hud.js (gradient core #d8331f, backing
  // #140000); the reviewer measured the sky at about rgb(40, 90, 160) behind the arc.
  const sky = [40, 90, 160];
  const red = [0xd8, 0x33, 0x1f];
  const dark = [0x14, 0, 0];
  for (const peak of [0.6, 0.8, 1]) {
    for (const t of ages(0.05)) {
      if (t >= 1.2) break;
      const R = arcOpacity(t, LIFE, peak);
      const B = arcBacking(t, LIFE, peak) * 0.75;
      const c = [0, 1, 2].map((k) => red[k] * R + (1 - R) * (dark[k] * B + sky[k] * (1 - B)));
      const h = hue(c);
      const off = Math.min(h, 360 - h);
      assert.ok(off <= 25, `t=${t}s peak=${peak}: rgb(${c.map(Math.round)}) hue ${h.toFixed(0)}°`);
    }
  }
});

/** Max |x| and |z| of the vertices of `geo` whose y is within `eps` of `y`. */
function ringExtent(geo, y, eps = 1e-5) {
  const p = geo.getAttribute('position');
  let ax = 0;
  let az = 0;
  let n = 0;
  for (let i = 0; i < p.count; i++) {
    if (Math.abs(p.getY(i) - y) > eps) continue;
    n++;
    ax = Math.max(ax, Math.abs(p.getX(i)));
    az = Math.max(az, Math.abs(p.getZ(i)));
  }
  return { ax, az, n };
}

test('elbow: the upper arm tapers inside the forearm’s start ring (no two coincident tubes to cut a sawtooth)', () => {
  const L1 = 0.3;
  const [upper] = upperArmParts(L1);
  const [fore] = forearmParts(0.27, { ring: true });
  const end = ringExtent(upper, L1);
  const start = ringExtent(fore, 0);
  assert.ok(end.n > 0 && start.n > 0, 'rings found');
  // At least 2.5 mm inside on each axis, so skinning at a bent elbow keeps the upper arm hidden.
  assert.ok(start.ax - end.ax >= 0.0025, `width: forearm ${start.ax} vs upper arm ${end.ax}`);
  assert.ok(start.az - end.az >= 0.0025, `depth: forearm ${start.az} vs upper arm ${end.az}`);
});
