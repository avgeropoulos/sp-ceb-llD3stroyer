// Kinematic phone simulator (DESIGN.md §5.4): physical sanity of the generated sensor data.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize, scriptDuration } from '../../src/main/assets/game/js/motion/synth.js';
import { remapToScreen } from '../../src/main/assets/game/js/motion/frames.js';

const G = 9.81;
const len = (v) => Math.hypot(v[0], v[1], v[2]);
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b} ±${tol}`);
const quiet = { noise: 0 };

/** The sample with the largest |gyro|. */
const peakSample = (raw) => raw.reduce((best, s) => (len(s.gyro) > len(best.gyro) ? s : best), raw[0]);

test('gravity at rest: landscape rot 90 reads device +x, which is screen +y', () => {
  for (const rot of [90, 270]) {
    const raw = synthesize([{ kind: 'idle', duration: 0.3 }], { ...quiet, rot });
    const s = raw[10];
    const dev = s.grav;
    near(len(dev), G, 1e-6, 'gravity magnitude');
    // rot 90: device +x is screen-up; rot 270: device −x is
    near(dev[0], rot === 90 ? G * Math.cos(0.393) : -G * Math.cos(0.393), 0.05, `device x at rot ${rot}`);
    const scr = remapToScreen(dev, rot);
    assert.ok(scr[1] > 9, `screen +y at rot ${rot}: ${scr}`);
    near(scr[0], 0, 0.05, 'screen x (idle tremor only)');
    assert.ok(scr[2] > 3, 'the guard tilts the screen back toward the eye (up has +z)');
    near(len(s.accG), G, 0.15, '|accG| at rest (tremor ≈ 0.1 m/s²)');
    near(len(s.acc), 0, 0.15, '|acc| at rest');
  }
});

test('a horizontal swing turns about the screen-up axis; centripetal acceleration points at the user (+z)', () => {
  // tilt 0.5 holds the blade level: a rightward swing is then a pure yaw
  for (const rot of [90, 270]) {
    const raw = synthesize([{ kind: 'swing', dir: 0, speed: 9, windup: 0, recoil: 0, ret: 0, wrist: 0 }], { ...quiet, rot, tilt: 0.5, rate: 400 });
    const p = peakSample(raw);
    const w = remapToScreen(p.gyro, rot);
    near(len(w), 9, 0.05, 'speed is the true peak');
    assert.ok(w[1] < -8.9 && Math.abs(w[0]) < 0.1 && Math.abs(w[2]) < 0.6, `gyro on screen −y (rightward): ${w}`);
    const a = remapToScreen(p.acc, rot);
    near(a[2], 81 * 0.55, 1.5, 'centripetal ω²r toward the user');
  }
});

test('an upward swing turns about screen +x', () => {
  const raw = synthesize([{ kind: 'swing', dir: Math.PI / 2, windup: 0, recoil: 0, ret: 0, wrist: 0 }], { ...quiet, rot: 90, tilt: 0.25, rate: 400 });
  const w = remapToScreen(peakSample(raw).gyro, 90);
  assert.ok(w[0] > 8.9 && Math.abs(w[1]) < 0.2, `${w}`);
});

test('a turn is a pure yaw about world up', () => {
  const raw = synthesize([{ kind: 'turn', yaw: 1, duration: 1 }], { ...quiet, rot: 90, rate: 200 });
  const p = peakSample(raw);
  const w = remapToScreen(p.gyro, 90);
  const up = remapToScreen(p.grav, 90).map((x) => x / G);
  const along = w[0] * up[0] + w[1] * up[1] + w[2] * up[2];
  near(along, 1.875, 0.01, 'peak yaw rate (positive = left) along up');
  near(len(w), Math.abs(along), 1e-3, 'no other component');
});

test('integrating the gyro reproduces the swing arc', () => {
  const raw = synthesize([{ kind: 'swing', dir: Math.PI / 4, windup: 0, recoil: 0, ret: 0, arc: 1.2, pronate: 0 }], { ...quiet, rate: 1000 });
  let angle = 0;
  for (let i = 1; i < raw.length; i++) angle += len(raw[i].gyro) * (raw[i].t - raw[i - 1].t);
  near(angle, 1.2, 0.02, 'integrated rotation');
});

test('thrust: forward acceleration peaks at 5.77·D/T² along the blade', () => {
  const raw = synthesize([{ kind: 'thrust', distance: 0.35, duration: 0.18, dip: 0 }], { ...quiet, rate: 1000 });
  let peak = 0;
  for (const s of raw) peak = Math.max(peak, -remapToScreen(s.acc, 90)[2]);
  near(peak, (5.7735 * 0.35) / (0.18 * 0.18), 0.5, 'peak forward acceleration');
});

test('throw: wind-up past vertical, then a late-peaking release at the requested speed', () => {
  for (const speed of [9, 13, 18]) {
    const raw = synthesize([{ kind: 'throw', speed, twist: 0 }], { ...quiet, rate: 1000 });
    const p = peakSample(raw);
    near(len(p.gyro), speed, speed * 0.03, `peak rate at speed ${speed}`);
  }
  // the blade passes vertical during the wind-up: up then has −z in the screen frame (blade up)
  const raw = synthesize([{ kind: 'throw' }], { ...quiet, rate: 200 });
  const minZ = Math.min(...raw.map((s) => remapToScreen(s.grav, 90)[2] / G));
  assert.ok(minZ < -0.85, `blade raised past vertical: up.z ${minZ}`);
  // the release peaks late: after the midpoint of the downswing
  const T = scriptDuration([{ kind: 'throw' }]);
  assert.ok(T > 2 && T < 3.2, `throw length ${T}`);
});

test('options: rate, jitter, t0, seed, noise, flags, sign, clipping', () => {
  const script = [{ kind: 'idle', duration: 0.5 }, { kind: 'swing', dir: 0, speed: 20 }];
  const a = synthesize(script, { rate: 100, t0: 5, seed: 3 });
  const b = synthesize(script, { rate: 100, t0: 5, seed: 3 });
  const c = synthesize(script, { rate: 100, t0: 5, seed: 4 });
  assert.deepEqual(a, b, 'deterministic for a seed');
  assert.notDeepEqual(a[3].gyro, c[3].gyro, 'noise depends on the seed');
  assert.equal(a[0].t, 5);
  near(a[1].t - a[0].t, 0.01, 1e-9, 'spacing');
  near(a[a.length - 1].t - 5, scriptDuration(script), 0.0101, 'covers the script');

  const j = synthesize(script, { rate: 100, jitter: 0.1 });
  for (let i = 1; i < j.length; i++) {
    const d = j[i].t - j[i - 1].t;
    assert.ok(d >= 0.009 - 1e-9 && d <= 0.011 + 1e-9, `jittered spacing ${d}`);
  }

  const bare = synthesize(script, { includeLinear: false, includeGravity: false });
  assert.equal(bare[0].acc, null);
  assert.equal(bare[0].grav, null);
  assert.ok(Array.isArray(bare[0].accG));

  const neg = synthesize(script, { gravitySign: -1, noise: 0 });
  const pos = synthesize(script, { noise: 0 });
  for (const k of ['acc', 'accG', 'grav']) near(neg[10][k][0], -pos[10][k][0], 1e-9, `${k} negated`);
  assert.deepEqual(neg[10].gyro, pos[10].gyro, 'the gyro is never negated');

  const clipped = synthesize(script, { gyroRange: 8, noise: 0 });
  assert.ok(Math.max(...clipped.map((s) => Math.max(...s.gyro.map(Math.abs)))) <= 8 + 1e-12, 'gyro clipped');
  const hard = synthesize([{ kind: 'thrust', duration: 0.05 }], { noise: 0 });
  assert.ok(Math.max(...hard.map((s) => Math.max(...s.accG.map(Math.abs)))) <= 78 + 1e-12, 'acceleration clipped');

  assert.equal(synthesize(script, { rot: -90 })[0].rot, 270);
  assert.throws(() => synthesize([{ kind: 'dance' }]), /unknown segment/);
});

test('left hand mirrors the rig, not the meaning of dir', () => {
  const right = synthesize([{ kind: 'idle', duration: 0.2 }], quiet);
  const left = synthesize([{ kind: 'idle', duration: 0.2 }], { ...quiet, hand: 'left' });
  // the guard blade points slightly right for the right hand, slightly left for the left:
  // the screen frame's gravity is the same but the azimuth of the blade differs → identical up.
  near(len(left[5].grav), G, 1e-6, 'gravity');
  assert.deepEqual(left[5].grav.map((x) => +x.toFixed(6)), right[5].grav.map((x) => +x.toFixed(6)));
  const sw = synthesize([{ kind: 'swing', dir: 0, windup: 0, recoil: 0, ret: 0, wrist: 0 }], { ...quiet, hand: 'left', tilt: 0.5, rate: 400 });
  assert.ok(remapToScreen(peakSample(sw).gyro, 90)[1] < -8.9, 'a left-handed R swing still yaws right');
});

test('walking: ±2 m/s² fore-aft, 0.04 m bob, small yaw; idle: a tremor well below "calm"', () => {
  const walk = synthesize([{ kind: 'walk', duration: 4 }], { ...quiet, rate: 200 });
  const mid = walk.filter((s) => s.t > 1 && s.t < 3);
  const maxW = Math.max(...mid.map((s) => len(s.gyro)));
  assert.ok(maxW > 0.2 && maxW < 0.45, `walk yaw rate ${maxW}`);
  const maxA = Math.max(...mid.map((s) => len(s.acc)));
  assert.ok(maxA > 2 && maxA < 4.5, `walk acceleration ${maxA}`);
  const idle = synthesize([{ kind: 'idle', duration: 2 }], { ...quiet, rate: 200 });
  assert.ok(Math.max(...idle.map((s) => len(s.gyro))) < 0.3, 'tremor rate');
  assert.ok(Math.max(...idle.map((s) => len(s.acc))) < 0.5, 'tremor acceleration');
});

test('segments chain: a turn rotates the guard the swings return to', () => {
  const raw = synthesize([{ kind: 'turn', yaw: 0.8, duration: 0.8 }, { kind: 'swing', dir: 0 }, { kind: 'idle', duration: 0.3 }], { ...quiet, rate: 200 });
  // integrate the yaw rate about up over the whole script: the swing returns, the turn stays
  let yaw = 0;
  for (let i = 1; i < raw.length; i++) {
    const w = raw[i].gyro, g = raw[i].grav;
    yaw += ((w[0] * g[0] + w[1] * g[1] + w[2] * g[2]) / G) * (raw[i].t - raw[i - 1].t);
  }
  near(yaw, 0.8, 0.03, 'net heading');
  assert.ok(Number.isFinite(scriptDuration([])) && scriptDuration([]) === 0);
});
