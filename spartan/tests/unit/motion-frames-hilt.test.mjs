// MotionFrames in the hilt grip (docs-hilt-grip.md B.2, H.1 #3): the web heuristics change (the sign
// latch direction, the provisional rule's guard, no rotFix), the rest does not.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames, FRAME_GRIPS, remapToScreen } from '../../src/main/assets/game/js/motion/frames.js';

const PI = Math.PI;
const idle = (duration) => ({ kind: 'idle', duration });
const swing = (dir, more = {}) => ({ kind: 'swing', dir, ...more });
const hilt = (so) => ({ ...so, hold: 'hilt' });
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b} ±${tol}`);

function feed(frames, raw, fn = () => {}) {
  for (const r of raw) fn(frames.process(r), r);
}

test('FRAME_GRIPS: the window grip as before, the hilt grip latch, guard and no rotFix', () => {
  assert.ok(Object.isFrozen(FRAME_GRIPS) && Object.isFrozen(FRAME_GRIPS.window) && Object.isFrozen(FRAME_GRIPS.hilt));
  assert.deepEqual(FRAME_GRIPS.window.latch, [0, 0, 1]);
  near(FRAME_GRIPS.window.restUp[1], 0.707, 0.001, 'window guard up y');
  near(FRAME_GRIPS.window.restUp[2], 0.707, 0.001, 'window guard up z');
  assert.equal(FRAME_GRIPS.window.rotFix, true);
  assert.deepEqual(FRAME_GRIPS.hilt.latch, [0, -0.6, 0.8]);
  assert.deepEqual(FRAME_GRIPS.hilt.restUp, [0, 0.89, 0.45]);
  assert.equal(FRAME_GRIPS.hilt.rotFix, false);
  assert.equal(new MotionFrames().grip, 'window');
  assert.equal(new MotionFrames({ grip: 'hilt' }).grip, 'hilt');
  assert.equal(new MotionFrames({ grip: 'upright' }).grip, 'window', 'anything else is the window grip');
});

test('hilt web −1 latches within 3 fast swings (cuts, chops, forearm sweeps, a cock-back and throw), and +1 stays +1', () => {
  // no idle lead-in: only the swings can tell
  const scripts = [
    ['R, D, L', [swing(0), swing(-PI / 2), swing(PI)]],
    ['D, D, D', [swing(-PI / 2), swing(-PI / 2), swing(-PI / 2)]],
    ['DR, DL, D', [swing(-PI / 4), swing((-3 * PI) / 4), swing(-PI / 2)]],
    ['forearm sweeps', [swing(0, { yawShare: 1, speed: 12 }), swing(PI, { yawShare: 1, speed: 12 }), swing(0, { yawShare: 1, speed: 12 })]],
    ['cock-back at 12, then throw', [{ kind: 'raise', windupSpeed: 12 }, swing(-PI / 2, { arc: 2.6, windup: 0, speed: 10 })]],
  ];
  for (const [name, script] of scripts) {
    for (const sign of [1, -1]) {
      for (const rate of [60, 200]) {
        for (const lean of [0.2, 0.45, 0.8]) {
          for (const includeLinear of [true, false]) {
            const raw = synthesize(script, hilt({ rate, lean, includeLinear, includeGravity: false, gravitySign: sign }));
            const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
            feed(f, raw);
            const label = `${name}, sign ${sign}, ${rate} Hz, lean ${lean}, linear ${includeLinear}`;
            assert.deepEqual([f.gravitySign, f.gravityLatched], [sign, true], label);
          }
        }
      }
    }
  }
  // Rolls of the fist alone (R, L) are weak evidence: the hand turns about the elbow at only
  // (1 − wrist) of the phone's rate. They may leave the sign to the provisional rule; never wrong.
  for (const sign of [1, -1]) {
    for (const rate of [60, 200]) {
      for (const lean of [0.2, 0.45, 0.8]) {
        const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
        feed(f, synthesize([swing(0), swing(PI), swing(0), swing(PI)], hilt({ rate, lean, includeGravity: false, gravitySign: sign })));
        assert.ok(!f.gravityLatched || f.gravitySign === sign, `rolls, sign ${sign}, ${rate} Hz, lean ${lean}: latched ${f.gravitySign}`);
      }
    }
  }
});

test('the hilt latch decides on whole fast runs: a fast chop at 200 Hz is not misread by its speeding-up half', () => {
  // In the fist a chop turns about x through the elbow, so its tangential acceleration lies along
  // the latch direction too: over its first 12 fast samples (60 ms) it outweighs the centripetal part.
  for (const speed of [12, 15]) {
    const raw = synthesize([swing(-PI / 4, { speed, wrist: 0 })], hilt({ rate: 200, lean: 0.8, includeGravity: false, gravitySign: -1 }));
    const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
    feed(f, raw);
    assert.deepEqual([f.gravitySign, f.gravityLatched], [-1, true], `DR at ${speed}`);
  }
});

test('the provisional rule uses the grip guard: a hilt guard held 1 s flips a wrong sign; a calm blade-down rest after the latch changes nothing', () => {
  for (const lean of [0.2, 0.45, 0.8]) {
    const raw = synthesize([idle(1.3)], hilt({ rate: 60, lean, includeGravity: false, gravitySign: -1 }));
    const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
    feed(f, raw);
    assert.equal(f.gravitySign, -1, `provisional at lean ${lean}`);
    assert.equal(f.gravityLatched, false);
    near(f.sample.up[1], Math.sin(PI / 2 - lean), 0.05, `up is the guard's again (lean ${lean})`);
  }
  // a right sign at the guard is left alone
  const ok = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
  feed(ok, synthesize([idle(1.5)], hilt({ rate: 60, includeGravity: false })));
  assert.equal(ok.gravitySign, 1);
  // latched by swings, then the blade lowered and held down for 2 s: no flip, no screen-angle fix
  for (const sign of [1, -1]) {
    const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
    const script = [swing(0), swing(PI), swing(0), { kind: 'lower', beta: -1.45, duration: 1.0 }, idle(2.5)];
    feed(f, synthesize(script, hilt({ rate: 100, includeGravity: false, gravitySign: sign })));
    assert.equal(f.gravitySign, sign, `sign ${sign}`);
    assert.equal(f.gravityLatched, true);
    assert.equal(f.rotFix, 0, 'a calm blade-down pose is a rest');
    assert.ok(f.sample.up[1] < -0.9, `the blade really points down: up ${f.sample.up}`);
  }
});

test('rotFix is never applied in the hilt grip (and is kept for the window grip)', () => {
  // motion-frames.test.mjs's stream: the screen angle reported 180° off, latched, then held still.
  // The window grip fixes it; the hilt grip never does.
  for (const sign of [1, -1]) {
    const off = synthesize([idle(0.3), swing(0), swing(PI), idle(2.2)], { rate: 100, rot: 90, includeGravity: false, gravitySign: sign }).map((r) => ({ ...r, rot: 270 }));
    const w = new MotionFrames({ platformKind: 'web' });
    feed(w, off);
    assert.equal(w.rotFix, 180, `window, sign ${sign}`);
    const h = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
    feed(h, off);
    assert.equal(h.rotFix, 0, `hilt, sign ${sign}`);
  }
  // a remembered window fix is stored but never applied in the hilt grip
  const raw = synthesize([swing(0), swing(PI), idle(1.0)], hilt({ rate: 100, includeGravity: false }));
  const f = new MotionFrames({ platformKind: 'web', grip: 'hilt' });
  assert.equal(f.importCalibration({ axisMap: 'xyz', gravitySign: 1, rotFix: 180 }), true);
  feed(f, raw.slice(0, 50), (s, r) => {
    const truth = remapToScreen(r.gyro, r.rot);
    for (let i = 0; i < 3; i++) near(s.w[i], truth[i], 1e-12, 'w uses the reported rotation');
  });
  assert.equal(f.rotFix, 180, 'kept');
  assert.equal(f.exportCalibration().rotFix, 180);
  f.setGrip('window');
  const s = f.process({ ...raw[60], t: raw[60].t });
  const fixed = remapToScreen(raw[60].gyro, raw[60].rot + 180);
  for (let i = 0; i < 3; i++) near(s.w[i], fixed[i], 1e-12, 'applied again in the window grip');
});

test('a rotation change is still a gap in the hilt grip', () => {
  const raw = synthesize([idle(0.5)], hilt({ rate: 100, rot: 0, includeGravity: false }));
  const turned = synthesize([idle(0.5)], hilt({ rate: 100, rot: 90, includeGravity: false, t0: 0.51 }));
  const f = new MotionFrames({ platformKind: 'android', grip: 'hilt' });
  feed(f, raw);
  const s = f.process(turned[0]);
  assert.equal(s.gap, true);
  near(s.up[1], Math.sin(PI / 2 - 0.45), 0.05, 'up re-initialised from accG in the new frame');
  assert.equal(f.process(turned[1]).gap, false);
});

test('setGrip keeps axisMap, sign and rotFix; a change resets the filters, the same grip changes nothing', () => {
  const f = new MotionFrames({ platformKind: 'web' });
  f.importCalibration({ axisMap: 'zxy', gravitySign: -1, rotFix: 180 });
  feed(f, synthesize([idle(0.3)], { rate: 100, includeGravity: false, gravitySign: -1 }).map((r) => ({ ...r, gyro: [r.gyro[2], r.gyro[0], r.gyro[1]] })));
  const before = f.exportCalibration();
  f.setGrip('hilt');
  assert.equal(f.grip, 'hilt');
  assert.deepEqual(f.exportCalibration(), before, 'the device calibration is kept');
  const raw = synthesize([idle(0.2)], hilt({ rate: 100, includeGravity: false, gravitySign: -1, t0: 10 }));
  const s0 = f.process(raw[0]);
  assert.equal(s0.dt, 0, 'the filters were reset');
  assert.equal(s0.gap, false, 'no gap across a reset');
  f.process(raw[1]);
  f.setGrip('hilt');
  near(f.process(raw[2]).dt, 0.01, 1e-9, 'setGrip with the same grip changes nothing');
  f.setGrip('window');
  assert.equal(f.process(raw[3]).dt, 0);
  assert.deepEqual(f.exportCalibration(), before);
});
