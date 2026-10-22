// MotionFrames and remapToScreen (DESIGN.md §5.2), driven by the synthetic phone (§5.4).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames, remapToScreen } from '../../src/main/assets/game/js/motion/frames.js';

const len = (v) => Math.hypot(v[0], v[1], v[2]);
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const angle = (a, b) => Math.acos(Math.max(-1, Math.min(1, dot(a, b) / (len(a) * len(b)))));
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b} ±${tol}`);
const idle = (duration) => ({ kind: 'idle', duration });
const swing = (dir, more = {}) => ({ kind: 'swing', dir, ...more });

function feed(frames, raw, fn) {
  for (const r of raw) fn(frames.process(r), r);
}

test('remapToScreen: §3.3 for every display rotation', () => {
  const v = [1, 2, 3];
  assert.deepEqual(remapToScreen(v, 0), [1, 2, 3]);
  assert.deepEqual(remapToScreen(v, 90), [-2, 1, 3]); // device +x → screen +y
  assert.deepEqual(remapToScreen(v, 180), [-1, -2, 3]);
  assert.deepEqual(remapToScreen(v, 270), [2, -1, 3]);
  assert.deepEqual(remapToScreen(v, -90), [2, -1, 3]);
  assert.deepEqual(remapToScreen(v, 450), [-2, 1, 3]);
  const out = [0, 0, 0];
  assert.equal(remapToScreen(v, 90, out), out);
  const inPlace = [1, 2, 3];
  remapToScreen(inPlace, 90, inPlace);
  assert.deepEqual(inPlace, [-2, 1, 3]);
  // the inverse used by synth: d = (cos r·sx + sin r·sy, −sin r·sx + cos r·sy, sz)
  for (const r of [0, 90, 180, 270]) {
    const c = Math.round(Math.cos((r * Math.PI) / 180)), s = Math.round(Math.sin((r * Math.PI) / 180));
    const scr = [0.3, -1.2, 0.7];
    const dev = [c * scr[0] + s * scr[1], -s * scr[0] + c * scr[1], scr[2]];
    assert.deepEqual(remapToScreen(dev, r).map((x) => +x.toFixed(12)), scr);
  }
});

test('android: up = normalize(grav), a = acc, dt clamp, gap, calm; gravitySign fixed at +1', () => {
  const raw = synthesize([idle(0.5), swing(0), idle(0.5)], { rate: 100, gravitySign: -1 });
  const f = new MotionFrames({ platformKind: 'android' });
  let first = true;
  let calmSeen = false, motionSeen = false;
  feed(f, raw, (s, r) => {
    if (first) {
      assert.equal(s.dt, 0);
      assert.equal(s.gap, false);
      first = false;
    } else {
      near(s.dt, 0.01, 1e-9, 'dt');
    }
    const g = remapToScreen(r.grav, r.rot);
    const l = len(g);
    for (let i = 0; i < 3; i++) near(s.up[i], g[i] / l, 1e-9, 'up straight from the gravity sensor, never sign-flipped');
    calmSeen ||= s.calm;
    motionSeen ||= !s.calm;
  });
  assert.equal(f.gravitySign, 1, 'android never flips');
  assert.ok(calmSeen && motionSeen);

  const f2 = new MotionFrames({ platformKind: 'android' });
  const r0 = synthesize([idle(0.1)], { rate: 100 });
  f2.process(r0[0]);
  const s1 = f2.process({ ...r0[1], t: r0[0].t + 0.08 });
  assert.equal(s1.dt, 0.05, 'dt clamped');
  assert.equal(s1.gap, false);
  const s2 = f2.process({ ...r0[2], t: r0[0].t + 0.2 });
  assert.equal(s2.gap, true, 'gap > 0.1 s');
  assert.equal(f2.process({ ...r0[3], t: r0[0].t + 0.21 }), s2, 'the Sample object is reused');
  const back = f2.process({ ...r0[4], t: r0[0].t + 0.05 });
  assert.equal(back.gap, true, 'a jump back in time is a gap');
  assert.equal(back.dt, 0);
  assert.equal(f2.process({ ...r0[5], t: r0[0].t + 0.04 }).gap, false, 'small reorderings are not');
});

test('without a gravity sensor, up follows the true gravity within 3° through swings, thrusts and throws', () => {
  const script = [idle(0.5), swing(0), swing(Math.PI / 2), { kind: 'thrust' }, { kind: 'throw' }, { kind: 'walk', duration: 2 }, idle(0.5)];
  for (const rate of [50, 200]) {
    for (const kind of ['android', 'web']) {
      const raw = synthesize(script, { rate, tilt: 0.3, roll: 0.2 });
      const f = new MotionFrames({ platformKind: kind });
      let worst = 0;
      feed(f, raw, (s, r) => {
        const truth = remapToScreen(r.grav, r.rot);
        const saved = r.grav;
        worst = Math.max(worst, angle(s.up, truth));
        r.grav = saved;
      });
      // feed again with the gravity sensor removed
      const f2 = new MotionFrames({ platformKind: kind });
      let worst2 = 0;
      for (const r of raw) {
        const truth = remapToScreen(r.grav, r.rot);
        const s = f2.process({ ...r, grav: null });
        worst2 = Math.max(worst2, angle(s.up, truth));
      }
      assert.ok(worst < 0.01, `with gravity ${worst}`);
      assert.ok(worst2 < (3 * Math.PI) / 180, `${kind} ${rate} Hz: up error ${((worst2 * 180) / Math.PI).toFixed(2)}°`);
    }
  }
});

test('without linear acceleration, a = accG − 9.81·up tracks the true linear acceleration', () => {
  const raw = synthesize([idle(0.3), swing(0), { kind: 'thrust' }, idle(0.3)], { rate: 200, noise: 0 });
  for (const grav of [true, false]) {
    const f = new MotionFrames({ platformKind: 'android' });
    let err = 0, n = 0;
    for (const r of raw) {
      const truth = remapToScreen(r.acc, r.rot);
      const s = f.process({ ...r, acc: null, grav: grav ? r.grav : null });
      err += (s.a[0] - truth[0]) ** 2 + (s.a[1] - truth[1]) ** 2 + (s.a[2] - truth[2]) ** 2;
      n++;
    }
    const rms = Math.sqrt(err / n);
    assert.ok(rms < (grav ? 0.01 : 0.5), `rms ${rms} (gravity sensor ${grav})`);
  }
});

test('web gravity sign: provisional −1 after 1 s calm, then latched by fast swings', () => {
  for (const tilt of [0, 0.5]) {
    const raw = synthesize([idle(1.3)], { rate: 60, tilt, includeGravity: false, gravitySign: -1 });
    const f = new MotionFrames({ platformKind: 'web' });
    feed(f, raw, () => {});
    assert.equal(f.gravitySign, -1, `provisional at tilt ${tilt}`);
    assert.equal(f.gravityLatched, false);
    assert.ok(f.sample.up[1] > 0.8, 'up is upright again');
  }
  for (const sign of [1, -1]) {
    for (const rate of [60, 200]) {
      // no idle lead-in: only the swings can tell
      const raw = synthesize([swing(0), swing(Math.PI), swing(0)], { rate, includeGravity: false, gravitySign: sign });
      const f = new MotionFrames({ platformKind: 'web' });
      feed(f, raw, () => {});
      assert.equal(f.gravitySign, sign, `latched ${sign} at ${rate} Hz`);
      assert.equal(f.gravityLatched, true);
    }
  }
  // once latched, a calm phone held screen-down does not flip it back
  const f = new MotionFrames({ platformKind: 'web' });
  feed(f, synthesize([swing(0), swing(Math.PI)], { rate: 200, includeGravity: false, gravitySign: -1 }), () => {});
  assert.equal(f.gravitySign, -1);
  const still = synthesize([idle(2)], { rate: 100, includeGravity: false, gravitySign: 1, t0: 10 });
  feed(f, still, () => {});
  assert.equal(f.gravitySign, -1, 'latched signs need swings to change');
});

test('web rotFix: a screen angle reported 180° off is corrected once the sign is latched', () => {
  for (const tilt of [0, 0.5]) {
    for (const sign of [1, -1]) {
      const raw = synthesize([idle(0.3), swing(0), swing(Math.PI), idle(2.2)], { rate: 100, tilt, rot: 90, includeGravity: false, gravitySign: sign });
      const f = new MotionFrames({ platformKind: 'web' });
      let lastW;
      feed(f, raw.map((r) => ({ ...r, rot: 270 })), (s) => (lastW = s));
      assert.equal(f.rotFix, 180, `tilt ${tilt} sign ${sign}`);
      assert.equal(f.gravitySign, sign);
      assert.ok(lastW.up[1] > 0.6, `upright after the fix: ${lastW.up}`);
    }
  }
  // a correct angle is left alone
  const ok = new MotionFrames({ platformKind: 'web' });
  feed(ok, synthesize([idle(0.3), swing(0), swing(Math.PI), idle(2.2)], { rate: 100, includeGravity: false }), () => {});
  assert.equal(ok.rotFix, 0);
});

test('web axisMap: a W3C-ordered rotationRate (alpha=z, beta=x, gamma=y) is detected from slow motion', () => {
  const slow = [idle(0.3)];
  for (const dir of [Math.PI / 2, -Math.PI / 2, Math.PI / 4, (-3 * Math.PI) / 4, (3 * Math.PI) / 4, -Math.PI / 4]) {
    slow.push(swing(dir, { speed: 1.6, arc: 0.9, windup: 0, recoil: 0, ret: 0 }));
  }
  slow.push(idle(0.3));
  for (const rate of [60, 100]) {
    const raw = synthesize(slow, { rate, includeGravity: false });
    // what a spec-compliant browser would deliver through platform.js (alpha→x, beta→y, gamma→z)
    const w3c = raw.map((r) => ({ ...r, gyro: [r.gyro[2], r.gyro[0], r.gyro[1]] }));
    const f = new MotionFrames({ platformKind: 'web' });
    feed(f, w3c, () => {});
    assert.equal(f.axisMap, 'zxy', `switched at ${rate} Hz`);
    // and the screen-frame rates are right afterwards
    const r = raw[raw.length - 1];
    const s = f.process({ ...w3c[w3c.length - 1], t: r.t + 0.01 });
    const truth = remapToScreen(r.gyro, r.rot);
    for (let i = 0; i < 3; i++) near(s.w[i], truth[i], 1e-9, 'w');

    const g = new MotionFrames({ platformKind: 'web' });
    feed(g, raw, () => {});
    assert.equal(g.axisMap, 'xyz', `the default order stays at ${rate} Hz`);
  }
  const a = new MotionFrames({ platformKind: 'android' });
  const raw = synthesize(slow, { rate: 100 });
  feed(a, raw.map((r) => ({ ...r, gyro: [r.gyro[2], r.gyro[0], r.gyro[1]] })), () => {});
  assert.equal(a.axisMap, 'xyz', 'android never remaps');
});

test('web calibration export/import: a remembered W3C axis order gets the first strikes right, and is re-checked', async () => {
  const { GestureRecognizer } = await import('../../src/main/assets/game/js/motion/gestures.js');
  const TOK = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
  const tok = (d) => TOK[((Math.round(d / (Math.PI / 4)) % 8) + 8) % 8];
  const w3c = (raw) => raw.map((r) => ({ ...r, gyro: [r.gyro[2], r.gyro[0], r.gyro[1]] }));
  const dirs = [0, Math.PI, -Math.PI / 2, Math.PI / 2, Math.PI / 4, (-3 * Math.PI) / 4];
  const session = (f, so, map) => {
    const script = [idle(1.5)];
    for (const d of dirs) script.push(idle(0.4), swing(d, { speed: 9 }), idle(0.6));
    const g = new GestureRecognizer();
    const out = [];
    for (const r of map(synthesize(script, so))) for (const e of g.push(f.process(r))) if (e.type === 'slash') out.push(tok(e.dir));
    return out.join(' ');
  };
  const want = 'R L D U UR DL';
  for (const gravitySign of [1, -1]) {
    const so = { rate: 60, includeGravity: false, gravitySign };
    const fresh = new MotionFrames({ platformKind: 'web' });
    assert.deepEqual(fresh.exportCalibration(), { axisMap: 'xyz', gravitySign: 1, rotFix: 0, confirmed: false });
    assert.notEqual(session(fresh, so, w3c), want, 'a fresh page learns the axis order on its first strikes');
    const saved = JSON.parse(JSON.stringify(fresh.exportCalibration())); // as stored
    assert.deepEqual(saved, { axisMap: 'zxy', gravitySign, rotFix: 0, confirmed: true });

    const next = new MotionFrames({ platformKind: 'web' });
    assert.equal(next.importCalibration(saved), true);
    assert.equal(next.axisMap, 'zxy');
    assert.equal(next.gravitySign, gravitySign);
    assert.equal(next.exportCalibration().confirmed, false, 'an import is a seed, not a verdict');
    assert.equal(session(next, so, w3c), want, `seeded (sign ${gravitySign}): every strike right from the first`);
    assert.equal(next.exportCalibration().confirmed, true);

    // the same stored calibration on another browser/device (spec order is gone, sign flipped): overridden
    const stale = new MotionFrames({ platformKind: 'web' });
    stale.importCalibration(saved);
    const other = { ...so, gravitySign: -gravitySign };
    assert.ok(session(stale, other, (x) => x).endsWith('D U UR DL'), 'later strikes right once re-checked');
    assert.deepEqual(stale.exportCalibration(), { axisMap: 'xyz', gravitySign: -gravitySign, rotFix: 0, confirmed: true });
  }
  // a remembered 180° screen-angle fix is re-checked too
  const rf = new MotionFrames({ platformKind: 'web' });
  rf.importCalibration({ axisMap: 'xyz', gravitySign: 1, rotFix: 180, confirmed: true });
  assert.equal(rf.rotFix, 180);
  feed(rf, synthesize([idle(0.3), swing(0), swing(Math.PI), idle(2.2)], { rate: 100, includeGravity: false }), () => {});
  assert.equal(rf.rotFix, 0, 'a wrong remembered screen-angle fix is undone');

  // garbage and Android
  const g = new MotionFrames({ platformKind: 'web' });
  for (const bad of [null, undefined, 'zxy', 42, {}, { axisMap: 'yzx', gravitySign: 2, rotFix: 90 }]) assert.equal(g.importCalibration(bad), false);
  assert.deepEqual(g.exportCalibration(), { axisMap: 'xyz', gravitySign: 1, rotFix: 0, confirmed: false });
  const a = new MotionFrames({ platformKind: 'android' });
  assert.equal(a.importCalibration({ axisMap: 'zxy', gravitySign: -1, rotFix: 180, confirmed: true }), false, 'Android has nothing to calibrate');
  assert.deepEqual(a.exportCalibration(), { axisMap: 'xyz', gravitySign: 1, rotFix: 0, confirmed: false });
});

test('the web axis check does not switch on the swing acceleration a missing linear sensor leaves in accG', () => {
  // gentle strikes never latch the sign; their slow returns used to let the wrong order win by 2.5×
  const script = [idle(1.2)];
  for (let i = 0; i < 4; i++) script.push(idle(0.5), swing(0, { speed: 5.5 }), idle(0.8));
  for (const rate of [50, 100]) {
    const f = new MotionFrames({ platformKind: 'web' });
    feed(f, synthesize(script, { rate, tilt: 0.5, includeLinear: false, includeGravity: false, gravitySign: -1 }), () => {});
    assert.equal(f.axisMap, 'xyz', `${rate} Hz`);
  }
});

test('a rotation change re-initialises up and is flagged like a gap', () => {
  const raw = synthesize([idle(0.5)], { rate: 100, rot: 90, includeGravity: false });
  const flipped = synthesize([idle(0.5)], { rate: 100, rot: 270, includeGravity: false, t0: 0.51 });
  const f = new MotionFrames({ platformKind: 'android' });
  feed(f, raw, () => {});
  const s = f.process(flipped[0]);
  assert.equal(s.gap, true);
  assert.ok(s.up[1] > 0.9, 'up re-initialised from accG in the new frame');
  assert.equal(f.process(flipped[1]).gap, false);
});

test('reset() clears timing and filters but keeps the device calibration', () => {
  const f = new MotionFrames({ platformKind: 'web' });
  feed(f, synthesize([swing(0), swing(Math.PI)], { rate: 200, includeGravity: false, gravitySign: -1 }), () => {});
  assert.equal(f.gravitySign, -1);
  f.reset();
  assert.equal(f.gravitySign, -1);
  assert.equal(f.gravityLatched, true);
  const raw = synthesize([idle(0.2)], { rate: 100, includeGravity: false, gravitySign: -1, t0: 50 });
  const s = f.process(raw[0]);
  assert.equal(s.dt, 0);
  assert.equal(s.gap, false, 'no gap across a reset');
  assert.ok(s.up[1] > 0.8);
});

test('missing sensors degrade gracefully', () => {
  const f = new MotionFrames({ platformKind: 'android' });
  const s = f.process({ t: 1, gyro: [0, 0, 0], acc: null, accG: null, grav: null, rot: 90 });
  assert.deepEqual(s.up, [0, 1, 0]);
  assert.deepEqual(s.a, [0, 0, 0]);
  const s2 = f.process({ t: 1.01, gyro: [0, 0, 1], acc: null, accG: null, grav: null, rot: 90 });
  assert.ok(Number.isFinite(s2.up[0]) && Math.abs(len(s2.up) - 1) < 1e-9);
});
