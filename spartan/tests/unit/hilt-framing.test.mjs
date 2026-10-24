// Unit tests for the PURE world-camera framing (js/gfx/framing.js, docs-hilt-grip.md §E.1).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FOV, cameraFov, verticalFov, horizontalFov } from '../../src/main/assets/game/js/gfx/framing.js';

/** main.js applySize before the hilt grip: a fixed 100° across, clamped to 55–70° vertical. */
const oldFov = (aspect) => {
  const v = (2 * Math.atan(Math.tan((100 * Math.PI) / 360) / aspect) * 180) / Math.PI;
  return Math.min(70, Math.max(55, v));
};

test('FOV is frozen, with the design numbers', () => {
  assert.ok(Object.isFrozen(FOV) && Object.isFrozen(FOV.landscapeV) && Object.isFrozen(FOV.portraitV));
  assert.deepEqual({ ...FOV, landscapeV: [...FOV.landscapeV], portraitV: [...FOV.portraitV] },
    { landscapeH: 100, landscapeV: [55, 70], portraitH: 46, portraitV: [72, 84] });
});

test('landscape: the old formula, the same numbers', () => {
  for (const a of [16 / 9, 19.5 / 9, 2.22, 1, 4 / 3, 1.6, 2, 2.4, 3]) {
    assert.equal(cameraFov(a), oldFov(a), `aspect ${a}`);
  }
  // Every landscape phone stays inside 55–70.
  assert.ok(Math.abs(cameraFov(16 / 9) - 67.7) < 0.1, `16:9 ${cameraFov(16 / 9)}`);
  assert.ok(Math.abs(cameraFov(19.5 / 9) - 57.6) < 0.1, `19.5:9 ${cameraFov(19.5 / 9)}`);
});

test('portrait phones: 84° × 44°, and 80.7° × 46° at 9:18', () => {
  for (const [w, h] of [[412, 915], [360, 800]]) {
    const a = w / h;
    assert.equal(cameraFov(a), 84, `${w}×${h}`);
    const across = horizontalFov(cameraFov(a), a);
    assert.ok(Math.abs(across - 44) <= 1, `${w}×${h} horizontal ${across}`);
  }
  assert.equal(cameraFov(0.45), 84);
  const v = cameraFov(9 / 18);
  assert.ok(Math.abs(v - 80.7) < 0.05, `9:18 vertical ${v}`);
  assert.ok(Math.abs(horizontalFov(v, 0.5) - 46) < 1e-9, 'unclamped: exactly 46° across');
  const tablet = cameraFov(0.75);
  assert.equal(tablet, 72, 'a portrait tablet hits the lower clamp');
  assert.ok(Math.abs(horizontalFov(tablet, 0.75) - 57) < 1, `tablet horizontal ${horizontalFov(tablet, 0.75)}`);
});

test('continuous and monotone within each branch, and clamped', () => {
  const sweep = (from, to, lo, hi) => {
    let prev = cameraFov(from);
    for (let a = from + 0.001; a < to; a += 0.001) {
      const v = cameraFov(a);
      assert.ok(v >= lo && v <= hi, `aspect ${a}: ${v} outside [${lo}, ${hi}]`);
      assert.ok(v <= prev + 1e-12, `aspect ${a}: not monotone (${prev} → ${v})`);
      assert.ok(prev - v < 0.2, `aspect ${a}: jump ${prev - v}`);
      prev = v;
    }
  };
  sweep(0.2, 0.999, 72, 84);
  sweep(1, 4, 55, 70);
  assert.equal(cameraFov(0.1), 84);
  assert.equal(cameraFov(0.999), 72);
  assert.equal(cameraFov(1), 70);
  assert.equal(cameraFov(10), 55);
});

test('verticalFov and horizontalFov are inverses', () => {
  for (const a of [0.45, 0.5, 0.75, 1, 16 / 9, 2.22]) {
    for (const h of [46, 60, 100]) assert.ok(Math.abs(horizontalFov(verticalFov(h, a), a) - h) < 1e-9);
  }
});

test('a degenerate aspect gives a finite FOV', () => {
  for (const a of [0, -1, NaN, undefined, Infinity]) {
    const v = cameraFov(a);
    assert.ok(Number.isFinite(v) && v >= 55 && v <= 84, `aspect ${a}: ${v}`);
  }
});
