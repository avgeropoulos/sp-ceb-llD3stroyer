// Unit tests for the PURE fx time control and shake (gfx/fx/timectl.js, gfx/fx/shake.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TimeControl, SPEAR_CAM, HITSTOP_REARM } from '../../src/main/assets/game/js/gfx/fx/timectl.js';
import { Shake } from '../../src/main/assets/game/js/gfx/fx/shake.js';

const DT = 1 / 60;

function rig() {
  const time = { now: 0, gnow: 0, dt: 0, gdt: 0, pdt: 0, scale: 1, hitStop: 0, playerRealtime: false };
  const tc = new TimeControl(time);
  let paused = false;
  /** One main.js frame (§10 step 0) followed by fx.update. */
  const step = (n = 1, settled = true, gone = false) => {
    const out = [];
    for (let i = 0; i < n; i++) {
      time.dt = DT;
      time.now += DT;
      if (!paused && time.hitStop > 0) time.hitStop = Math.max(0, time.hitStop - DT);
      const stopped = paused || time.hitStop > 0;
      time.gdt = stopped ? 0 : DT * time.scale;
      time.pdt = stopped ? 0 : time.playerRealtime ? DT : time.gdt;
      time.gnow += time.gdt;
      const ev = tc.update(paused ? 0 : DT, settled, gone);
      if (ev) out.push(ev);
    }
    return out;
  };
  return { time, tc, step, setPaused: (p) => (paused = p) };
}

const close = (a, b, eps = 1e-6) => assert.ok(Math.abs(a - b) <= eps, `${a} ≈ ${b}`);

test('hit-stop max-merges while active', () => {
  const { time, tc } = rig();
  assert.equal(tc.hitStop(0.05), true);
  close(time.hitStop, 0.05);
  tc.hitStop(0.085);
  close(time.hitStop, 0.085);
  tc.hitStop(0.03);
  close(time.hitStop, 0.085);
});

test('hit-stop re-arm: none within 0.08 s of the last one ending', () => {
  const { time, tc, step } = rig();
  tc.hitStop(0.05);
  step(3); // 0.05 s (3 × 1/60 leaves a float residue, so main stops one more frame)
  if (time.hitStop > 0) step(1);
  assert.equal(time.hitStop, 0);
  assert.equal(tc.hitStop(0.05), false, 'blocked right after the end');
  step(3); // 0.05 s after the end
  assert.equal(tc.hitStop(0.05), false, 'blocked at +0.05');
  step(2); // 0.083 s after the end
  assert.equal(tc.hitStop(0.05), true, 'allowed after 0.08');
  close(time.hitStop, 0.05);
  assert.equal(HITSTOP_REARM, 0.08);
});

test('endHitStop (a motion swing) ends a running hit-stop at once and leaves the re-arm open', () => {
  const { time, tc, step } = rig();
  assert.equal(tc.endHitStop(), false, 'nothing to end');
  tc.hitStop(0.11);
  step(1);
  assert.ok(time.hitStop > 0);
  assert.equal(tc.endHitStop(), true);
  assert.equal(time.hitStop, 0);
  step(1);
  assert.ok(time.pdt > 0 && time.gdt > 0, 'time runs on the next frame');
  step(2); // the new blow strikes ~0.05 s later: it gets its own freeze
  assert.equal(tc.hitStop(0.05), true, 'the next hit-stop is not blocked by the re-arm rule');
});

test('endHitStop never ends the spear-cam impact freeze', () => {
  const { time, tc } = rig();
  tc.camStart(false, 0.6, () => 0.6);
  tc.camImpact();
  assert.ok(tc.camActive && time.hitStop > 0);
  assert.equal(tc.endHitStop(), false);
  assert.ok(time.hitStop > 0);
});

test('hit-stop freezes gdt for its duration', () => {
  const { time, tc, step } = rig();
  tc.hitStop(0.1);
  let frozen = 0;
  for (let i = 0; i < 12; i++) {
    step();
    if (time.gdt === 0) frozen++;
  }
  assert.ok(frozen >= 5 && frozen <= 6, `frozen frames ${frozen}`);
});

test('slowMo holds its scale for its real duration, then eases to 1', () => {
  const { time, tc, step } = rig();
  tc.slowMo(0.5, 0.4, true);
  assert.equal(time.scale, 0.5);
  assert.equal(time.playerRealtime, true);
  step(16); // 0.267 s
  close(time.scale, 0.5);
  step(5); // 0.35 s: in the ease-out
  assert.ok(time.scale > 0.5 && time.scale < 1);
  assert.equal(time.playerRealtime, true);
  step(4); // 0.4167 s
  assert.equal(time.scale, 1);
  assert.equal(time.playerRealtime, false);
});

test('slowMo merges: deeper scale and longer remainder', () => {
  const { time, tc, step } = rig();
  tc.slowMo(0.5, 0.4, false);
  step(6);
  tc.slowMo(0.35, 0.5, true);
  assert.equal(time.scale, 0.35);
  assert.equal(time.playerRealtime, true);
  step(22); // 0.367 s
  close(time.scale, 0.35);
  step(8);
  assert.equal(time.scale, 1);
});

test('pause (dt = 0) freezes slowMo timers', () => {
  const { time, tc, step, setPaused } = rig();
  tc.slowMo(0.5, 0.3);
  setPaused(true);
  step(120);
  assert.equal(time.scale, 0.5);
  setPaused(false);
  step(19);
  assert.equal(time.scale, 1);
});

test('touch spear cam sets the flight to about 1.2 s', () => {
  const { time, tc } = rig();
  tc.camStart(false, 0.6, () => 0.6);
  close(time.scale, 0.5);
  tc.reset();
  tc.camStart(false, 0.05, () => 0.05);
  close(time.scale, SPEAR_CAM.touchMin);
  tc.reset();
  tc.camStart(false, 2.5, () => 2.5);
  close(time.scale, 1);
});

test('motion spear cam holds at 0.1 until settled for 0.1 s, then ramps over 0.15 s', () => {
  const { time, tc, step } = rig();
  tc.camStart(true, 1, () => 0.8);
  assert.equal(time.scale, 0.1);
  step(18, false); // 0.3 s unsettled
  assert.equal(time.scale, 0.1);
  step(5, true); // 0.083 s settled
  assert.equal(time.scale, 0.1);
  assert.equal(tc.holdDone, false);
  step(1, true); // 0.1 s settled → hold ends
  assert.equal(tc.holdDone, true);
  step(9, true); // 0.15 s ramp
  close(time.scale, 0.5); // 0.8 / 1.6
  assert.equal(tc.camPhase, 'trail');
});

test('motion spear cam hold is capped at 1.4 s', () => {
  const { time, tc, step } = rig();
  tc.camStart(true, 1, () => 0.2);
  step(83, false); // 1.383 s
  assert.equal(tc.holdDone, false);
  step(2, false);
  assert.equal(tc.holdDone, true);
  step(10, false);
  close(time.scale, SPEAR_CAM.motionMin); // 0.2/1.6 clamps to 0.3
});

test('spear-cam impact: freeze 0.12, orbit 0.7 at 0.2, ease 0.35, end at scale 1', () => {
  const { time, tc, step } = rig();
  tc.camStart(false, 0.6, () => 0.6);
  step(10);
  assert.equal(tc.camImpact(), true);
  assert.equal(time.scale, 0.2);
  assert.ok(time.hitStop >= 0.12 - 1e-9);
  assert.equal(tc.slowMo(0.5, 1), false, 'slowMo ignored while the cam runs');
  let evs = step(49); // 0.8167 s
  assert.deepEqual(evs, []);
  assert.equal(tc.camPhase, 'impact');
  evs = step(1); // 0.833 > 0.82
  assert.deepEqual(evs, ['ease']);
  evs = step(20); // 0.333 s
  assert.equal(tc.camPhase, 'ease');
  assert.ok(time.scale > 0.9 && time.scale < 1);
  evs = step(2);
  assert.deepEqual(evs, ['end']);
  assert.equal(time.scale, 1);
  assert.equal(time.playerRealtime, false);
});

test('spear-cam miss eases back at once', () => {
  const { time, tc, step } = rig();
  tc.camStart(true, 1, () => 1);
  step(5);
  const evs = step(1, true, true);
  assert.deepEqual(evs, ['ease']);
  step(21);
  assert.equal(tc.camPhase, 'off');
  assert.equal(time.scale, 1);
});

test('reset restores scale 1, no hit-stop, no realtime', () => {
  const { time, tc } = rig();
  tc.slowMo(0.3, 2, true);
  tc.hitStop(0.1);
  tc.reset();
  assert.deepEqual([time.scale, time.hitStop, time.playerRealtime], [1, 0, false]);
  tc.camStart(true, 1, null);
  tc.reset();
  assert.equal(tc.camActive, false);
  assert.equal(time.scale, 1);
});

test('shake is bounded, smooth, and dies out', () => {
  const s = new Shake(3);
  s.add(0.025, 0.25);
  let maxAbs = 0;
  let maxStep = 0;
  let prev = 0;
  for (let i = 0; i < 30; i++) {
    s.update(DT);
    maxAbs = Math.max(maxAbs, Math.abs(s.x), Math.abs(s.y), Math.abs(s.z));
    if (i > 0) maxStep = Math.max(maxStep, Math.abs(s.x - prev));
    prev = s.x;
  }
  assert.ok(maxAbs > 0.003 && maxAbs <= 0.025 + 1e-9, `max ${maxAbs}`);
  assert.ok(maxStep < 0.02, `step ${maxStep}`);
  assert.equal(s.x, 0);
  assert.equal(s.y, 0);
  assert.equal(s.active, false);
  s.update(0);
  assert.equal(s.x, 0);
});

test('shake kick peaks near tau and decays', () => {
  const s = new Shake(3);
  s.addKick(0.02, -0.01, 0, 0.06);
  let peak = 0;
  for (let i = 0; i < 60; i++) {
    s.update(DT);
    peak = Math.max(peak, s.x);
  }
  assert.ok(peak > 0.017 && peak <= 0.02 + 1e-9, `peak ${peak}`);
  assert.ok(Math.abs(s.x) < 1e-3);
});
