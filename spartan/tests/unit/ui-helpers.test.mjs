// Unit tests for the pure helpers exported by ui/*.js (they must also import cleanly in Node).
import test from 'node:test';
import assert from 'node:assert/strict';
import { Trail, findSwipe, stickVector, swipePower, INPUT, MOTION_TIP } from '../../src/main/assets/game/js/ui/input.js';
import { roman, groupDigits, clock, subtitleSeconds, phaseFade, curtainOpacity } from '../../src/main/assets/game/js/ui/hud.js';
import { FIELDS, TEXT } from '../../src/main/assets/game/js/ui/menus.js';
import { STEPS, suggestSensitivity } from '../../src/main/assets/game/js/ui/training.js';
import { synthesize, scriptDuration } from '../../src/main/assets/game/js/motion/synth.js';
import { SENSITIVITY_MIN, SENSITIVITY_MAX } from '../../src/main/assets/game/js/motion/gestures.js';

const wrap = (a) => Math.atan2(Math.sin(a), Math.cos(a));

function stroke(dx, dy, ms, steps = 6, t0 = 1000, x0 = 500, y0 = 200) {
  const tr = new Trail();
  for (let i = 0; i <= steps; i++) tr.push(t0 + (ms * i) / steps, x0 + (dx * i) / steps, y0 + (dy * i) / steps);
  return tr;
}

test('findSwipe: 8 directions with screen-y inverted', () => {
  for (let k = 0; k < 8; k++) {
    const a = (k * Math.PI) / 4;
    const out = findSwipe(stroke(Math.cos(a) * 100, -Math.sin(a) * 100, 60), {});
    assert.ok(out, `dir ${k}`);
    assert.ok(Math.abs(wrap(out.dir - a)) < 1e-9, `dir ${k}: ${out.dir} vs ${a}`);
    assert.ok(out.speed > 1.6 && out.speed < 1.7);
  }
});

test('findSwipe: thresholds (> 40 px, ≤ 180 ms, ≥ 0.5 px/ms)', () => {
  assert.equal(findSwipe(stroke(40, 0, 20), {}), null, 'exactly 40 px is not enough');
  assert.ok(findSwipe(stroke(41, 0, 20), {}));
  assert.equal(findSwipe(stroke(80, 0, 200), {}), null, '0.4 px/ms is too slow');
  assert.ok(findSwipe(stroke(90, 0, 170), {}), '0.53 px/ms inside the window');
  // A fast flick at the end of a slow drag counts; the old slow part falls outside the window.
  const tr = stroke(30, 0, 400, 10);
  for (let i = 1; i <= 4; i++) tr.push(1400 + i * 15, 530 + i * 15, 200);
  const out = findSwipe(tr, {});
  assert.ok(out && Math.abs(out.dir) < 1e-9);
});

test('Trail keeps the newest samples and restarts at the newest', () => {
  const tr = new Trail(4);
  for (let i = 0; i < 6; i++) tr.push(i, i * 10, 0);
  assert.equal(tr.n, 4);
  assert.equal(tr.t[0], 2);
  assert.equal(tr.x[3], 50);
  tr.restart();
  assert.equal(tr.n, 1);
  assert.equal(tr.t[0], 5);
});

test('stickVector: dead zone, rescale, clamp and forward = up', () => {
  assert.deepEqual(stickVector(3, 3), [0, 0]);
  const [x, z] = stickVector(0, -INPUT.stickRadius);
  assert.ok(Math.abs(x) < 1e-12 && Math.abs(z - 1) < 1e-12, 'full up is forward 1');
  const [x2, z2] = stickVector(INPUT.stickRadius * 2, 0);
  assert.ok(Math.abs(x2 - 1) < 1e-12 && Math.abs(z2) < 1e-12, 'clamped to 1');
  const half = stickVector(INPUT.stickRadius * 0.56, 0)[0];
  assert.ok(half > 0.45 && half < 0.55, `rescaled half ${half}`);
});

test('swipePower is 0.45..1 and rises with speed', () => {
  assert.equal(swipePower(0.5), 0.45);
  assert.equal(swipePower(10), 1);
  assert.ok(swipePower(1.5) > swipePower(1.0));
});

test('§6 thresholds and the tip text', () => {
  assert.equal(INPUT.swipeMinPx, 40);
  assert.equal(INPUT.swipeWindowMs, 180);
  assert.equal(INPUT.swipeMinSpeed, 0.5);
  assert.equal(INPUT.tapMaxPx, 12);
  assert.equal(INPUT.tapMaxMs, 200);
  assert.equal(INPUT.lookPerPx, 0.006);
  assert.equal(INPUT.mousePerPx, 0.0025);
  assert.equal(INPUT.throwFullS, 0.7);
  assert.equal(INPUT.edgeStrip, 12);
  assert.equal(INPUT.maxContactRadius, 40);
  assert.equal(INPUT.tipHoldS, 5);
  assert.equal(INPUT.tipSlashes, 3);
  assert.equal(MOTION_TIP, 'Your shield is up while you swing. Rest your thumb on the frame, and press the glass to raise the shield.');
});

test('hud formatting helpers', () => {
  assert.equal(roman(3), 'III');
  assert.equal(roman(12), 'XII');
  assert.equal(roman(40), '40');
  assert.equal(groupDigits(15420), '15,420');
  assert.equal(groupDigits(999), '999');
  assert.equal(groupDigits(1234567), '1,234,567');
  assert.equal(clock(25), '0:25');
  assert.equal(clock(14.2), '0:15');
  assert.equal(clock(-3), '0:00');
  assert.ok(subtitleSeconds('Come and take them.', 'ΜΟΛΩΝ ΛΑΒΕ') > subtitleSeconds('Come and take them.'));
  assert.ok(subtitleSeconds('x'.repeat(400)) <= 7);
});

test('phase fades: Day 3 phase C fades 2 s (§13), others none; payload wins', () => {
  assert.equal(phaseFade({ day: 3, phase: 'C' }), 2);
  assert.equal(phaseFade({ day: 3, phase: 'A' }), 0);
  assert.equal(phaseFade({ day: 3, phase: 'B' }), 0);
  assert.equal(phaseFade({ day: 1, phase: null }), 0);
  assert.equal(phaseFade({ day: 3, phase: 'B', fade: 1.5 }), 1.5);
  assert.equal(phaseFade(null), 0);
});

test('curtainOpacity: in, hold, out, then clear', () => {
  const [a, h, o] = [0.5, 1.2, 1.1];
  assert.equal(curtainOpacity(-0.1, a, h, o), 0);
  assert.equal(curtainOpacity(0, a, h, o), 0);
  assert.ok(curtainOpacity(0.25, a, h, o) > 0.4 && curtainOpacity(0.25, a, h, o) < 0.6);
  assert.equal(curtainOpacity(0.5, a, h, o), 1);
  assert.equal(curtainOpacity(1.0, a, h, o), 1, 'covers the view when waves changes the world (BEHIND_CARD = 1 s)');
  assert.ok(curtainOpacity(2.25, a, h, o) > 0.3 && curtainOpacity(2.25, a, h, o) < 0.7);
  assert.equal(curtainOpacity(a + h + o, a, h, o), 0);
  let prev = 0;
  for (let t = 0; t <= a; t += 0.01) {
    const v = curtainOpacity(t, a, h, o);
    assert.ok(v >= prev - 1e-12);
    prev = v;
  }
});

test('settings UI covers every §9 field', () => {
  const keys = FIELDS.map((f) => f.key).sort();
  assert.deepEqual(keys, ['aimAssist', 'blood', 'control', 'gyroLook', 'haptics', 'helmetView', 'leftHanded', 'master', 'motionFlipX', 'motionFlipY', 'music', 'quality', 'sensitivity', 'sensorDebug', 'sfx', 'voice'].sort());
  assert.equal(new Set(FIELDS.map((f) => f.key)).size, FIELDS.length, 'no duplicate keys');
  const q = FIELDS.find((f) => f.key === 'quality');
  assert.deepEqual(q.options.map((o) => o[0]), ['auto', 'low', 'medium', 'high']);
  assert.match(q.hint, /after restart/i);
  // The slider spans the recognizer's range, so it can show (and set) what the Agoge suggests.
  const s = FIELDS.find((f) => f.key === 'sensitivity');
  assert.equal(s.min, SENSITIVITY_MIN);
  assert.equal(s.max, SENSITIVITY_MAX);
  assert.equal(SENSITIVITY_MIN, 0.5);
  assert.equal(SENSITIVITY_MAX, 2.2);
  const steps = (s.max - s.min) / s.step;
  assert.ok(Math.abs(steps - Math.round(steps)) < 1e-9, 'the max is on a slider step');
  // Motion-only rows (greyed out in touch mode), the swap toggles among them, under Controls.
  const motionOnly = FIELDS.filter((f) => f.motionOnly).map((f) => f.key);
  assert.deepEqual(motionOnly, ['sensitivity', 'gyroLook', 'motionFlipX', 'motionFlipY']);
  for (const k of ['motionFlipX', 'motionFlipY']) {
    const f = FIELDS.find((x) => x.key === k);
    assert.equal(f.tab, 'controls');
    assert.equal(f.type, 'toggle');
    assert.match(f.label, /^Swap swing/);
  }
  assert.match(TEXT.epitaph1 + ' ' + TEXT.epitaph2, /^Go tell the Spartans, stranger passing by, that here, obedient to their laws, we lie\.$/);
  assert.equal(TEXT.epitaphCite, 'Simonides (Herodotus 7.228)');
  assert.equal(TEXT.epigraph, 'With it, or on it.');
});

test('suggestSensitivity = clamp(9 / median(peak), 0.6, 1.8), to 0.05', () => {
  assert.equal(suggestSensitivity([9, 9, 9, 9, 9]), 1);
  assert.equal(suggestSensitivity([6, 7, 7.5, 8, 30]), 1.2);
  assert.equal(suggestSensitivity([2, 3, 4, 3, 2]), 1.8);
  assert.equal(suggestSensitivity([20, 25, 30, 22, 19]), 0.6);
  assert.equal(suggestSensitivity([8, 10]), 1);
  assert.equal(suggestSensitivity([]), 1);
});

test('Agoge steps follow §11 and their demos synthesize', () => {
  assert.deepEqual(STEPS.map((s) => s.id), ['grip', 'calibrate', 'slashLR', 'overhead', 'uppercut', 'thrust', 'block', 'perfect', 'bash', 'volley', 'spear', 'twin']);
  assert.deepEqual(STEPS.map((s) => s.n), [1, 5, 4, 2, 1, 3, 3, 2, 2, 1, 4, 1]);
  assert.ok(STEPS[1].motionOnly);
  for (const s of STEPS) {
    assert.ok(s.title && s.voice && s.motion, s.id);
    if (!s.motionOnly) assert.ok(s.touch, s.id);
    if (!s.demo) continue;
    const script = s.demo();
    const dur = scriptDuration(script);
    assert.ok(dur > 0.5 && dur < 7.5, `${s.id} demo lasts ${dur}`);
    const samples = synthesize(script, { rot: 90, hand: 'right' });
    assert.ok(samples.length > 50, `${s.id} demo has samples`);
  }
});
