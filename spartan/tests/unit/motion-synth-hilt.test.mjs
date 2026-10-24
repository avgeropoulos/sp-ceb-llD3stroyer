// The synthetic phone in the hilt hold (docs-hilt-grip.md B.4, H.1 #4): physical sanity of the
// generated sensor data, and the window hold left exactly as it was.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize, scriptDuration } from '../../src/main/assets/game/js/motion/synth.js';
import { remapToScreen } from '../../src/main/assets/game/js/motion/frames.js';

const G = 9.81;
const PI = Math.PI;
const len = (v) => Math.hypot(v[0], v[1], v[2]);
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b} ±${tol}`);
const quiet = { noise: 0, hold: 'hilt' };
const idle = (duration) => ({ kind: 'idle', duration });
/** The sample with the largest |gyro|. */
const peakSample = (raw) => raw.reduce((best, s) => (len(s.gyro) > len(best.gyro) ? s : best), raw[0]);
/** βabs of the blade (+y) from a sample's gravity, for a phone that has not turned (forward −Z, right +X). */
const betaOf = (s, rot = 0) => {
  const u = remapToScreen(s.grav, rot).map((x) => x / G);
  return Math.atan2(u[1], u[2]); // F = up × R with R = screen +x: F.y = u.z
};

test('at the guard, gravity reads device +y·sin βg and +z·cos βg (portrait, rot 0 by default)', () => {
  for (const lean of [0.2, 0.45, 0.8]) {
    for (const hand of ['right', 'left']) {
      const raw = synthesize([idle(0.3)], { ...quiet, lean, hand });
      const s = raw[10];
      const bg = PI / 2 - lean;
      assert.equal(s.rot, 0, 'the hilt is held in portrait');
      // (the idle tremor only: 0.002 rad)
      near(s.grav[0], 0, 0.05, `x, lean ${lean} ${hand}`);
      near(s.grav[1], G * Math.sin(bg), 0.05, `y, lean ${lean} ${hand}`);
      near(s.grav[2], G * Math.cos(bg), 0.05, `z, lean ${lean} ${hand}`);
      near(len(s.accG), G, 0.15, '|accG| at rest (tremor)');
    }
  }
  // tilt is ignored, rot can be given (a natural-landscape tablet held upright reports 90 or 270)
  assert.deepEqual(synthesize([idle(0.2)], { ...quiet, tilt: 0.5 }), synthesize([idle(0.2)], quiet));
  const r90 = synthesize([idle(0.2)], { ...quiet, rot: 90 })[5];
  assert.equal(r90.rot, 90);
  near(remapToScreen(r90.grav, 90)[1], G * Math.sin(PI / 2 - 0.45), 0.05, 'the same screen-frame gravity at rot 90');
});

test('a hilt R swing with lean 0 is a pure roll about the body forward axis (gyro on screen −z), speed the true peak', () => {
  for (const hand of ['right', 'left']) {
    const raw = synthesize([{ kind: 'swing', dir: 0, speed: 9, windup: 0, recoil: 0, ret: 0, wrist: 0 }], { ...quiet, lean: 0, hand, rate: 400 });
    const w = peakSample(raw).gyro;
    near(len(w), 9, 0.05, `${hand}: speed is the true peak`);
    assert.ok(w[2] < -8.95 && Math.abs(w[0]) < 0.05 && Math.abs(w[1]) < 0.05, `${hand}: gyro on screen −z (tip to the right): ${w}`);
  }
  // a chop (D) turns about screen +x, tip forward: negative
  const d = peakSample(synthesize([{ kind: 'swing', dir: -PI / 2, speed: 9, windup: 0, recoil: 0, ret: 0, wrist: 0 }], { ...quiet, rate: 400 })).gyro;
  assert.ok(d[0] < -8.95 && Math.abs(d[1]) < 0.05 && Math.abs(d[2]) < 0.05, `chop: ${d}`);
  // a forearm sweep (yawShare 1) is a pure yaw about world up
  const f = synthesize([{ kind: 'swing', dir: 0, speed: 6, windup: 0, recoil: 0, ret: 0, wrist: 0, yawShare: 1 }], { ...quiet, rate: 400 });
  const p = peakSample(f);
  const up = p.grav.map((x) => x / G);
  const along = p.gyro[0] * up[0] + p.gyro[1] * up[1] + p.gyro[2] * up[2];
  near(along, -6, 0.05, 'turning right about up');
  near(len(p.gyro), 6, 0.05, 'no other component');
});

test('the cock-back takes βabs past π/2 + 1.0 and holds it; the throw releases at the requested speed', () => {
  const raw = synthesize([{ kind: 'raise' }], { ...quiet, rate: 200 });
  const top = Math.max(...raw.map((s) => betaOf(s)));
  assert.ok(top > PI / 2 + 1.0, `raise: βabs ${top.toFixed(3)}`);
  near(betaOf(raw[raw.length - 1]), PI / 2 - 0.45 + 1.5, 1e-3, 'held at βg + cockArc');
  const thr = synthesize([{ kind: 'throw' }], { ...quiet, rate: 200 });
  assert.ok(Math.max(...thr.map((s) => betaOf(s))) > PI / 2 + 1.0, 'the throw cocks back as far');
  near(betaOf(thr[thr.length - 1]), PI / 2 - 0.45, 1e-3, 'and comes back to the guard');
  for (const speed of [5, 7, 10]) {
    const p = peakSample(synthesize([{ kind: 'throw', speed, twist: 0 }], { ...quiet, rate: 1000 }));
    near(len(p.gyro), speed, speed * 0.02, `throw peak at speed ${speed}`);
  }
  // an ordinary lift is small: by default the hand swings about the shoulder in proportion to
  // cockArc (1.4 rad for the full 1.5), not the full cock-back's 1.4
  const peakAcc = (seg) => Math.max(...synthesize([{ kind: 'raise', ...seg }], { ...quiet, rate: 500 }).map((s) => len(s.acc)));
  const lift = peakAcc({ cockArc: 0.5 }), flung = peakAcc({ cockArc: 0.5, shoulderArc: 1.4 });
  near(lift / flung, 0.5 / 1.5, 0.02, `the hand's peak acceleration: ${lift.toFixed(1)} vs ${flung.toFixed(1)} m/s²`);
  near(peakAcc({}), peakAcc({ cockArc: 1.5, shoulderArc: 1.4 }), 1e-9, 'the documented defaults');
  assert.ok(scriptDuration([{ kind: 'throw' }], { hold: 'hilt' }) > scriptDuration([{ kind: 'raise' }], { hold: 'hilt' }));
});

test('a thrust punches along the body forward; its peak acceleration is 5.77·D/T²', () => {
  for (const lean of [0.2, 0.45, 0.8]) {
    const raw = synthesize([{ kind: 'thrust', dip: 0 }], { ...quiet, lean, rate: 1000 });
    const p = raw.reduce((best, s) => (len(s.acc) > len(best.acc) ? s : best), raw[0]);
    const bg = PI / 2 - lean;
    // world forward (0, 0, −1) in the device frame: (0, cos βg, −sin βg)
    const a = p.acc, m = len(a);
    near(m, (5.7735 * 0.3) / (0.18 * 0.18), 0.6, `lean ${lean}: peak`);
    near(a[1] / m, Math.cos(bg), 0.01, `lean ${lean}: along forward (y)`);
    near(a[2] / m, -Math.sin(bg), 0.01, `lean ${lean}: along forward (z)`);
    near(a[0] / m, 0, 0.01, `lean ${lean}: no sideways part`);
  }
});

test('lower brings βabs to `beta` slowly (both holds), and back up', () => {
  for (const lean of [0.2, 0.45, 0.8]) {
    const raw = synthesize([{ kind: 'lower', beta: 0.1 }, idle(0.2), { kind: 'lower', beta: PI / 2 - lean }], { ...quiet, lean, rate: 200 });
    const mid = raw.find((s) => s.t > 1.5);
    near(betaOf(mid), 0.1, 0.005, `lean ${lean}: lowered (idle tremor aside)`);
    near(betaOf(raw[raw.length - 1]), PI / 2 - lean, 1e-3, `lean ${lean}: back at the guard`);
    if (lean === 0.45) assert.ok(Math.max(...raw.map((s) => len(s.gyro))) < 1.5, 'slow at the default lean');
  }
  // the window hold: the blade (−z) elevation, as gestures.js measures it
  const w = synthesize([{ kind: 'lower', beta: -0.8 }], { noise: 0, rate: 200 });
  const u = remapToScreen(w[w.length - 1].grav, 90).map((x) => x / G);
  near(Math.atan2(-u[2], Math.hypot(u[0], u[1])), -0.8, 1e-6, 'window lower');
});

test('segments chain in the hilt hold: turns carry the guard, every strike comes back to it', () => {
  // the heading as ∫(w·up)dt, over strikes that stay below vertical (past it the integral is
  // path-dependent, not a heading)
  const flat = [{ kind: 'turn', yaw: 0.8, duration: 0.8 }, { kind: 'swing', dir: 0 }, { kind: 'swing', dir: -PI / 2 }, { kind: 'swing', dir: PI, yawShare: 1 }, { kind: 'thrust' }, idle(0.3)];
  const raw = synthesize(flat, { ...quiet, rate: 200 });
  let yaw = 0;
  for (let i = 1; i < raw.length; i++) {
    const w = raw[i].gyro, g = raw[i].grav;
    yaw += ((w[0] * g[0] + w[1] * g[1] + w[2] * g[2]) / G) * (raw[i].t - raw[i - 1].t);
  }
  near(yaw, 0.8, 0.03, 'net heading');
  // cock-backs, throws and chops from over the shoulder come back to the guard too
  const script = [{ kind: 'turn', yaw: 0.8, duration: 0.8 }, { kind: 'throw' }, { kind: 'raise' }, { kind: 'swing', dir: -PI / 2, arc: 2.6, windup: 0 }, idle(0.3)];
  const back = synthesize(script, { ...quiet, rate: 200 });
  near(betaOf(back[back.length - 1]), PI / 2 - 0.45, 1e-3, 'back at the guard');
  assert.throws(() => synthesize([{ kind: 'dance' }], { hold: 'hilt' }), /unknown segment/);
});

test('hold "window" output is byte-identical to before (and is the default)', () => {
  const script = [idle(0.4), { kind: 'swing', dir: PI / 4, wristRoll: 0.2, rollEnd: 0.1, retYaw: 0.2, retPitch: -0.1 }, { kind: 'walk', duration: 1.2 }, { kind: 'turn', yaw: 0.6, duration: 0.8 },
    { kind: 'thrust', dip: 0.2 }, { kind: 'raise' }, { kind: 'swing', dir: -PI / 2, arc: 2.6, windup: 0 }, { kind: 'throw', speed: 11, twist: 0.3 }, idle(0.3)];
  const opts = [
    { rate: 100, seed: 5 },
    { rate: 60, rot: 270, tilt: 0.4, roll: 0.3, aim: -0.3, grip: { pitch: 0.4, roll: 0.2, yaw: 0.1 }, hand: 'left', noise: 2, seed: 9, gravitySign: -1, includeLinear: false, includeGravity: false, gyroRange: 20, jitter: 0.1, t0: 3 },
  ];
  // fingerprints of these runs, taken with synth.js as it was before the hilt hold existed
  const BEFORE = [
    { n: 976, t: 4757.999999999938, gyro: -48.378876093453606, accG: 17289.82856013099, acc: 7347.554017984122, grav: 9917.386326812551, wGyro: -11439.131132576906, wAccG: 858608.2591516504 },
    { n: 630, t: 5190.031258573198, gyro: 310.54878837891084, accG: -3971.7394661943513, acc: 0, grav: 0, wGyro: -3275.4725012883127, wAccG: -106436.4907952179 },
  ];
  opts.forEach((o, j) => {
    const raw = synthesize(script, o);
    assert.deepEqual(synthesize(script, { ...o, hold: 'window' }), raw, 'window is the default hold');
    const f = { n: raw.length, t: 0, gyro: 0, accG: 0, acc: 0, grav: 0, wGyro: 0, wAccG: 0 };
    raw.forEach((r, i) => {
      f.t += r.t;
      for (let k = 0; k < 3; k++) {
        f.gyro += r.gyro[k] * (k + 1);
        f.accG += r.accG[k] * (k + 1);
        if (r.acc) f.acc += r.acc[k] * (k + 1);
        if (r.grav) f.grav += r.grav[k] * (k + 1);
        f.wGyro += r.gyro[k] * ((i % 97) + 1) * (k + 1);
        f.wAccG += r.accG[k] * ((i % 89) + 1) * (k + 1);
      }
    });
    for (const k of Object.keys(BEFORE[j])) near(f[k], BEFORE[j][k], 1e-7 * (1 + Math.abs(BEFORE[j][k])), `options ${j}: ${k}`);
  });
  assert.equal(synthesize([idle(0.1)])[0].rot, 90, 'the window hold stays landscape by default');
});
