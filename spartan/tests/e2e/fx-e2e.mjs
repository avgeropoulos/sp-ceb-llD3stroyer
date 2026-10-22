// Scripted checks for gfx/fx.js in tests/sandbox/fx-sandbox.html (manual stepping, main.js frame
// semantics): time scale, hit-stop, slow-mo, spear cam, pause, reset, haptics, caps,
// per-frame allocations and draw calls.
// Usage: node spartan/tests/e2e/fx-e2e.mjs [filter]
import assert from 'node:assert/strict';
import { launch } from './lib.mjs';

const filter = process.argv[2] || '';
const DT = 1 / 60;
const l = await launch();
const results = [];

async function withPage(query, fn) {
  const page = await l.newPage();
  await page.goto(`${l.base}/tests/sandbox/fx-sandbox.html?${query}&manual=1`);
  await page.waitForFunction(() => window.__fx, null, { timeout: 60000 });
  try {
    await fn(page);
  } finally {
    const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
    await page.close();
    if (errs.length) throw new Error(`page errors:\n${errs.join('\n')}`);
  }
}

async function check(name, query, fn) {
  if (filter && !name.includes(filter)) return;
  const t0 = Date.now();
  try {
    await withPage(query, fn);
    results.push({ name, ok: true });
    console.log(`PASS ${name} (${Date.now() - t0} ms)`);
  } catch (err) {
    results.push({ name, ok: false });
    console.log(`FAIL ${name}\n  ${String(err && err.stack || err).split('\n').slice(0, 8).join('\n  ')}`);
  }
}

const near = (a, b, eps, msg) => assert.ok(Math.abs(a - b) <= eps, `${msg ?? ''} ${a} vs ${b} (±${eps})`);

// Payload builders (evaluated in the page).
const HIT = `(target, o = {}) => Object.assign({ target, attacker: 'player', damage: 34, kind: 'slash', dir: { x: 0, z: -1 },
  point: window.__fx.P(target.pos.x, 1.3, target.pos.z), guarded: false, critical: false, killed: false, byPlayer: true, combo: null }, o)`;

// ---------------------------------------------------------------------------------------------

await check('slowMo holds its scale for its real duration (60 Hz and 30 Hz)', 'scene=empty', async (page) => {
  for (const dt of [DT, 1 / 30]) {
    const r = await page.evaluate((dt) => {
      const f = window.__fx;
      f.fx.reset();
      const t0 = f.ctx.time.now;
      f.emit('combo', { id: 'storm', name: 'Storm of Bronze', mult: 2.4, slowMo: [0.5, 0.4], bonus: 240 });
      const s0 = f.state();
      const run = f.run(Math.round(0.6 / dt), dt).map((s) => ({ t: s.now - t0, scale: s.scale, rt: s.playerRealtime, gdt: s.gdt }));
      return { s0, run };
    }, dt);
    assert.equal(r.s0.scale, 0.5);
    assert.equal(r.s0.playerRealtime, true);
    for (const s of r.run) {
      if (s.t <= 0.4 - 0.12 - 1e-6) {
        assert.equal(s.scale, 0.5, `t=${s.t.toFixed(3)} scale`);
        assert.equal(s.rt, true);
      } else if (s.t >= 0.4 - 1e-6) {
        assert.equal(s.scale, 1, `t=${s.t.toFixed(3)} after`);
        assert.equal(s.rt, false);
      } else {
        assert.ok(s.scale >= 0.5 && s.scale <= 1, 'easing out');
      }
    }
    // gdt follows the scale of the previous frame (main computes gdt before fx.update).
    near(r.run[1].gdt, dt * 0.5, 1e-9, 'gdt at half speed');
  }
});

await check('hit-stop max-merge, durations and the 0.08 s re-arm rule', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const a = f.makeActor(0, -2);
    const out = {};
    f.emit('hit', hit(a));
    out.hit = f.ctx.time.hitStop;
    f.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
    out.merged = f.ctx.time.hitStop;
    let frozen = 0;
    for (let i = 0; i < 20 && f.ctx.time.hitStop > 0; i++) if (f.run(1)[0].gdt === 0) frozen++;
    out.frozen = frozen;
    out.after = f.ctx.time.hitStop;
    f.emit('hit', hit(a)); // right after the end: refused
    out.rearmAtEnd = f.ctx.time.hitStop;
    f.run(3); // 0.05 s after the end: still refused
    f.emit('hit', hit(a));
    out.rearmBlocked = f.ctx.time.hitStop;
    f.run(2); // 0.083 s after the end
    f.emit('hit', hit(a));
    out.rearmOk = f.ctx.time.hitStop;
    const one = (fn) => {
      f.fx.reset();
      fn();
      return f.ctx.time.hitStop;
    };
    out.crit = one(() => f.emit('hit', hit(a, { critical: true })));
    out.block = one(() => f.emit('block', { by: 'player', attacker: a, point: f.P(0, 1.3, -0.6), perfect: false, kind: 'melee' }));
    out.perfect = one(() => f.emit('block', { by: 'player', attacker: a, point: f.P(0, 1.3, -0.6), perfect: true, kind: 'melee' }));
    out.hurt = one(() => f.emit('playerHurt', { damage: 10, hp: 90, from: a, kind: 'melee', relYaw: 0.4 }));
    out.direct = one(() => {
      f.fx.hitStop(0.03);
      f.fx.hitStop(0.02);
    });
    return out;
  }, HIT);
  near(r.hit, 0.05, 1e-9, 'hit');
  near(r.merged, 0.085, 1e-9, 'merged with kill');
  assert.ok(r.frozen >= 5 && r.frozen <= 6, `frozen frames ${r.frozen}`);
  assert.equal(r.after, 0);
  assert.equal(r.rearmAtEnd, 0, 're-arm blocked at the end');
  assert.equal(r.rearmBlocked, 0, 're-arm blocked within 0.08 s');
  near(r.rearmOk, 0.05, 1e-9, 're-armed after 0.08 s');
  near(r.crit, 0.085, 1e-9, 'crit');
  near(r.block, 0.035, 1e-9, 'block');
  near(r.perfect, 0.11, 1e-9, 'perfect');
  near(r.hurt, 0.06, 1e-9, 'hurt');
  near(r.direct, 0.03, 1e-9, 'max-merge keeps the longer one');
});

await check('AI-vs-AI events: particles only (no hit-stop, shake or haptics)', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const a = f.makeActor(0, -3);
    const b = f.makeActor(1, -3);
    b.kind = 'spartan';
    b.team = 'greek';
    f.hapticLog.length = 0;
    f.emit('hit', hit(a, { attacker: b, byPlayer: false, killed: true, damage: 60 }));
    f.emit('kill', { target: a, attacker: b, byPlayer: false, kind: 'slash', spear: false, cause: 'weapon' });
    f.emit('block', { by: b, attacker: a, point: f.P(1, 1.3, -2.6), perfect: true, kind: 'melee' });
    f.emit('hit', hit(b, { attacker: a, byPlayer: false, guarded: true }));
    const before = f.fx.particleCount;
    f.emit('arrowHit', { target: null, point: f.P(1, 1.4, -3.3), blocked: true, by: b }); // an ally's aspis
    const arrowSparks = f.fx.particleCount - before;
    const sh = f.fx.shakeOffset;
    const amps = Array.from(sh.amp);
    const run = f.run(10);
    return { hs: f.ctx.time.hitStop, frozen: run.filter((s) => s.gdt === 0).length, haptics: f.hapticLog.length, amps, particles: run[0].particles, decals: f.state().decals, arrowSparks };
  }, HIT);
  assert.ok(r.arrowSparks > 0, 'sparks off an ally\'s aspis');
  assert.equal(r.hs, 0);
  assert.equal(r.frozen, 0);
  assert.equal(r.haptics, 0);
  assert.ok(r.amps.every((x) => x === 0), 'no shake');
  assert.ok(r.particles > 20, `particles ${r.particles}`);
});

await check('shake amplitudes, ×0.5 in motion mode', 'scene=empty', async (page) => {
  const r = await page.evaluate(() => {
    const f = window.__fx;
    const maxAmp = () => Math.max(...f.fx.shakeOffset.amp);
    const out = {};
    for (const motion of [false, true]) {
      f.setMotion(motion);
      f.fx.reset();
      f.emit('playerHurt', { damage: 10, hp: 90, from: null, kind: 'arrow', relYaw: 0 });
      out[`hurt${motion ? 'M' : ''}`] = maxAmp();
      f.fx.reset();
      f.emit('block', { by: 'player', attacker: null, point: f.P(0, 1.4, -0.7), perfect: false, kind: 'guardBreak' });
      out[`gb${motion ? 'M' : ''}`] = maxAmp();
    }
    f.setMotion(false);
    f.fx.reset();
    // The offset reaches the camera as rotation, bounded by the amplitude.
    f.emit('playerHurt', { damage: 10, hp: 90, from: null, kind: 'arrow', relYaw: 0 });
    let peak = 0;
    for (let i = 0; i < 30; i++) {
      f.run(1);
      const c = f.ctx.camera.rotation;
      peak = Math.max(peak, Math.abs(c.x - f.view.pitch), Math.abs(c.y - f.view.yaw), Math.abs(c.z));
    }
    out.peak = peak;
    out.settled = Math.abs(f.ctx.camera.rotation.x - f.view.pitch) + Math.abs(f.ctx.camera.rotation.y - f.view.yaw);
    return out;
  });
  near(r.hurt, 0.025, 1e-6, 'hurt');
  near(r.hurtM, 0.0125, 1e-6, 'hurt motion');
  near(r.gb, 0.035, 1e-6, 'guardBreak');
  near(r.gbM, 0.0175, 1e-6, 'guardBreak motion');
  assert.ok(r.peak > 0.004 && r.peak < 0.05, `camera peak ${r.peak}`);
  assert.ok(r.settled < 1e-9, `shake gone ${r.settled}`);
});

async function spearRun(page, { motion, settle = null, target = 'hit', frames = 420 }) {
  return page.evaluate(({ motion, settle, target, frames }) => {
    const f = window.__fx;
    f.setMotion(motion);
    f.setSettled(settle === null ? '1' : '0');
    const a = f.makeActor(1.0, -16);
    const t0 = f.ctx.time.now;
    const p = target === 'hit' ? f.P(a.pos.x, a.pos.y + 1.3, a.pos.z) : target === 'sea' ? f.P(14, -3, -12) : f.P(-1.6, f.groundHeight(-1.6, -14), -14);
    f.playerThrow(p, { src: motion ? 'motion' : 'touch' });
    const thrown = f.state();
    const out = [];
    for (let i = 0; i < frames; i++) {
      if (settle !== null && Math.abs(f.ctx.time.now - t0 - settle) < 1e-6) f.setSettled('1');
      f.frame(1 / 60);
      const s = f.state();
      s.t = s.now - t0;
      out.push(s);
    }
    f.step(0);
    return { thrown, run: out, log: f.spearCamLog.map((e) => ({ phase: e.phase, t: e.t - t0 })) };
  }, { motion, settle, target, frames });
}

function phases(run) {
  const marks = {};
  let prev = 'trail';
  for (const s of run) {
    if (s.phase !== prev) {
      marks[s.phase] = marks[s.phase] ?? s.t;
      prev = s.phase;
    }
  }
  return marks;
}

await check('spear cam, touch throw: ~1.2 s flight, freeze 0.12, orbit 0.7 at 0.2, ease 0.35', 'scene=empty', async (page) => {
  const { thrown, run, log } = await spearRun(page, { motion: false });
  assert.equal(thrown.phase, 'trail');
  assert.equal(thrown.overridden, true);
  const m = phases(run);
  const impact = log.find((e) => e.phase === 'impact').t;
  const end = log.find((e) => e.phase === 'end').t;
  assert.deepEqual(log.map((e) => e.phase), ['start', 'impact', 'end']);
  near(impact, 1.2, 0.12, 'touch flight lasts about 1.2 s');
  const trail = run.filter((s) => s.phase === 'trail');
  assert.ok(trail.every((s) => Math.abs(s.scale - thrown.scale) < 1e-9), 'constant scale while trailing');
  const atImpact = run.find((s) => s.phase === 'impact');
  near(atImpact.hitStop, 0.12, 1e-6, 'freeze');
  const frozen = run.filter((s) => s.phase === 'impact' && s.gdt === 0).length;
  assert.ok(frozen >= 7 && frozen <= 8, `freeze frames ${frozen}`);
  assert.ok(run.filter((s) => s.phase === 'impact').every((s) => s.scale === 0.2), 'orbit at 0.2');
  near(m.ease - m.impact, 0.82, DT + 1e-6, 'freeze + orbit');
  near(end - m.ease, 0.35, DT + 1e-6, 'ease back');
  const last = run[run.length - 1];
  assert.equal(last.scale, 1);
  assert.equal(last.playerRealtime, false);
  assert.equal(last.overridden, false);
  const ease = run.filter((s) => s.phase === 'ease').map((s) => s.scale);
  assert.ok(ease.every((v, i) => i === 0 || v >= ease[i - 1]), 'scale ramps up while easing');
});

await check('spear cam, motion throw: hold at 0.1 until settled 0.1 s, ramp 0.15, ~1.6 s remaining', 'scene=empty&motion=1', async (page) => {
  const { thrown, run, log } = await spearRun(page, { motion: true, settle: 0.5 });
  assert.equal(thrown.scale, 0.1);
  const hold = run.filter((s) => s.phase === 'trail' && s.scale === 0.1);
  const holdEnd = run.find((s) => s.phase === 'trail' && s.scale > 0.1).t;
  near(holdEnd, 0.6 + DT, DT + 1e-6, 'hold ends 0.1 s after settling');
  assert.ok(hold.length >= 34, `held ${hold.length} frames`);
  const rampDone = run.find((s) => s.t >= holdEnd + 0.15 + DT);
  const flightScale = rampDone.scale;
  assert.ok(flightScale >= 0.3 && flightScale <= 1, `flight scale ${flightScale}`);
  const impact = log.find((e) => e.phase === 'impact').t;
  near(impact - (holdEnd - DT), 1.6, 0.15, 'remaining flight ≈ 1.6 s real');
  assert.deepEqual(log.map((e) => e.phase), ['start', 'impact', 'end']);
});

await check('spear cam, motion throw never settled: hold capped at 1.4 s', 'scene=empty&motion=1', async (page) => {
  const { run, log } = await spearRun(page, { motion: true, settle: 99, frames: 480 });
  const holdEnd = run.find((s) => s.phase === 'trail' && s.scale > 0.1).t;
  near(holdEnd, 1.4 + DT, DT + 1e-6, 'hold cap');
  assert.deepEqual(log.map((e) => e.phase), ['start', 'impact', 'end']);
});

await check('spear cam: miss and sea splash ease back at once; other slowMo ignored', 'scene=empty', async (page) => {
  for (const target of ['miss', 'sea']) {
    const r = await page.evaluate(() => {
      window.__fx.fx.reset();
      window.__fx.spearCamLog.length = 0;
    });
    const { run, log } = await spearRun(page, { motion: false, target, frames: 200 });
    const m = phases(run);
    assert.deepEqual(log.map((e) => e.phase), ['start', 'end'], `${target}: no impact phase`);
    assert.ok(m.ease !== undefined, `${target}: eased`);
    const endT = log.find((e) => e.phase === 'end').t;
    near(endT - m.ease, 0.35, DT + 1e-6, `${target} ease`);
    assert.equal(run[run.length - 1].scale, 1);
    void r;
  }
  const ignored = await page.evaluate(() => {
    const f = window.__fx;
    f.fx.reset();
    const a = f.makeActor(0.5, -20);
    f.playerThrow(f.P(a.pos.x, a.pos.y + 1.3, a.pos.z), { src: 'touch' });
    f.run(5);
    const before = f.ctx.time.scale;
    const applied = f.fx.slowMo(0.35, 0.5, { playerRealtime: true });
    f.emit('combo', { id: 'cleave', slowMo: [0.35, 0.5], bonus: 220 });
    return { before, after: f.ctx.time.scale, applied, rt: f.ctx.time.playerRealtime };
  });
  assert.equal(ignored.applied, false);
  assert.equal(ignored.after, ignored.before);
  assert.equal(ignored.rt, false);
});

await check('spear cam: timeout after 3 s of game age and sinking below -2.5 ease back', 'scene=empty', async (page) => {
  const r = await page.evaluate(() => {
    const f = window.__fx;
    const out = {};
    // A spear thrown straight up and far: a stub that never lands (ground far below).
    const pr = f.throwSpear({ origin: f.P(0, 1.7, 0), velocity: f.P(0, 40, -2), src: 'touch' });
    pr.vel.set(0, 0, 0);
    // Freeze the stub's integration so only age grows.
    const run = [];
    for (let i = 0; i < 400 && f.fx.spearCamPhase !== 'off'; i++) {
      pr.age += f.ctx.time.gdt;
      pr.pos.y = 50;
      pr.vel.set(0, 0, -1);
      f.frame(1 / 60);
      run.push({ phase: f.fx.spearCamPhase, age: pr.age });
    }
    out.easeAge = run.find((s) => s.phase === 'ease').age;
    f.fx.reset();
    const p2 = f.throwSpear({ origin: f.P(12, -2, -4), velocity: f.P(0, -1, -1), src: 'touch' });
    p2.pos.y = -2.6;
    f.frame(1 / 60);
    out.sunk = f.fx.spearCamPhase;
    return out;
  });
  assert.ok(r.easeAge > 3 && r.easeAge < 3.1, `age ${r.easeAge}`);
  assert.equal(r.sunk, 'ease');
});

await check('pause (dt = 0) freezes slow-mo, hit-stop, shake, particles and the spear cam', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const a = f.makeActor(0, -2.5);
    f.emit('combo', { id: 'storm', slowMo: [0.5, 0.4], bonus: 240 });
    f.emit('hit', hit(a));
    f.run(2);
    const snap = () => {
      const s = f.state();
      const c = f.ctx.camera;
      return { scale: s.scale, hitStop: s.hitStop, particles: s.particles, rt: s.playerRealtime, rot: [c.rotation.x, c.rotation.y, c.rotation.z], pos: c.position.toArray(), phase: s.phase };
    };
    const before = snap();
    f.setPaused(true);
    f.run(120);
    const during = snap();
    f.setPaused(false);
    const resumed = f.run(30).map((s) => s.scale);
    // Spear cam paused mid-flight.
    f.fx.reset();
    const b = f.makeActor(1, -16);
    f.playerThrow(f.P(b.pos.x, b.pos.y + 1.3, b.pos.z));
    f.run(20);
    const cam1 = snap();
    f.setPaused(true);
    f.run(90);
    const cam2 = snap();
    f.setPaused(false);
    f.run(200);
    return { before, during, resumed, cam1, cam2, log: f.spearCamLog.map((e) => e.phase) };
  }, HIT);
  assert.deepEqual(r.during, r.before, 'nothing moves while paused');
  assert.ok(r.before.hitStop > 0 && r.before.scale === 0.5);
  assert.ok(r.resumed.includes(1), 'slow-mo finishes after resuming');
  assert.deepEqual(r.cam2, r.cam1, 'spear cam frozen');
  assert.equal(r.cam1.phase, 'trail');
  assert.deepEqual(r.log.slice(-3), ['start', 'impact', 'end']);
});

await check('reset() restores scale 1 and clears everything', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const a = f.makeActor(0.5, -2.5);
    f.emit('hit', hit(a, { killed: true, damage: 80 }));
    f.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
    f.run(30);
    const b = f.makeActor(1, -16);
    f.setMotion(true);
    f.playerThrow(f.P(b.pos.x, b.pos.y + 1.3, b.pos.z), { src: 'motion' });
    f.fx.hitStop(0.1);
    f.run(3);
    const before = f.state();
    f.spearCamLog.length = 0;
    f.fx.reset();
    const after = f.state();
    return { before, after, log: f.spearCamLog.map((e) => e.phase), slowAfter: f.fx.slowMo(0.5, 0.2) };
  }, HIT);
  assert.equal(r.before.overridden, true);
  assert.ok(r.before.particles > 0 && r.before.decals > 0);
  assert.equal(r.after.scale, 1);
  assert.equal(r.after.hitStop, 0);
  assert.equal(r.after.playerRealtime, false);
  assert.equal(r.after.overridden, false);
  assert.equal(r.after.particles, 0);
  assert.equal(r.after.decals, 0);
  assert.deepEqual(r.log, ['end']);
  assert.equal(r.slowAfter, true, 'slowMo works again after reset');
});

await check('spear cam camera: starts at the eye, trails behind the spear, orbits the impact, returns to the eye', 'scene=empty', async (page) => {
  const r = await page.evaluate(() => {
    const f = window.__fx;
    const cam = f.ctx.camera;
    const a = f.makeActor(1.0, -16);
    f.run(1);
    const eye = cam.position.clone();
    const eyeQ = cam.quaternion.clone();
    const pr = f.playerThrow(f.P(a.pos.x, a.pos.y + 1.3, a.pos.z), { src: 'touch' });
    const fwd = new f.THREE.Vector3();
    const samples = [];
    for (let i = 0; i < 160 && f.fx.spearCamPhase !== 'off'; i++) {
      f.frame(1 / 60);
      cam.getWorldDirection(fwd);
      const vdir = pr.vel.clone().normalize();
      const toImpact = pr.impactPoint ? pr.impactPoint.clone().sub(cam.position) : null;
      samples.push({
        phase: f.fx.spearCamPhase,
        hitStop: f.ctx.time.hitStop,
        fromEye: cam.position.distanceTo(eye),
        toSpear: cam.position.distanceTo(pr.pos),
        alongVel: fwd.dot(vdir),
        toImpactDist: toImpact ? toImpact.length() : -1,
        lookAtImpact: toImpact ? fwd.dot(toImpact.normalize()) : -1,
        quatDot: Math.abs(cam.quaternion.dot(eyeQ)),
      });
    }
    f.run(1);
    return { samples, endFromEye: cam.position.distanceTo(eye), endQuat: Math.abs(cam.quaternion.dot(eyeQ)) };
  });
  const s = r.samples;
  assert.ok(s[0].phase === 'trail' && s[0].fromEye < 0.05, `first frame at the eye (${s[0].fromEye})`);
  const trail = s.filter((x) => x.phase === 'trail').slice(15); // after the 0.2 s blend-in
  assert.ok(trail.length > 10, 'trailed');
  for (const x of trail) {
    assert.ok(x.toSpear < 2.1, `close behind the spear (${x.toSpear})`);
    assert.ok(x.alongVel > 0.85, `looking along the flight (${x.alongVel})`);
  }
  const orbit = s.filter((x) => x.phase === 'impact' && x.hitStop === 0);
  assert.ok(orbit.length > 30, `orbit frames ${orbit.length}`);
  for (const x of orbit.slice(10)) {
    assert.ok(x.toImpactDist > 1.0 && x.toImpactDist < 2.8, `orbit radius ${x.toImpactDist}`);
    assert.ok(x.lookAtImpact > 0.97, `looking at the impact (${x.lookAtImpact})`);
  }
  assert.ok(r.endFromEye < 1e-9 && r.endQuat > 1 - 1e-9, 'back at the eye after the ease');
});

await check('pre-warm: compile() right after createFx builds every fx program; spawning adds none', 'scene=empty', async (page) => {
  const r = await page.evaluate(() => {
    const f = window.__fx;
    const P = f.P;
    const fxNames = (list) => list.filter((n) => /^fx-/.test(n)).sort();
    f.fx.blood(P(0, 1.3, -3), P(0, 0.2, -1), 1);
    f.fx.sparks(P(0, 1.3, -2), P(0, 0, 1), 1);
    f.fx.dust(P(1, 0, -4), 1);
    f.fx.splash(P(12, -3, -6));
    f.fx.decal(P(0, 0, -2.5), 'pool', 1);
    const before = f.programs().length;
    f.run(10);
    return { prewarm: fxNames(f.prewarmPrograms), after: fxNames(f.programs()), grew: f.programs().length - before };
  });
  assert.deepEqual(r.prewarm, ['fx-decals', 'fx-soft', 'fx-sparks', 'fx-streaks']);
  assert.deepEqual(r.after, r.prewarm);
  assert.equal(r.grew, 0, 'no program compiled when effects first appear');
});

await check('gameplay haptics table, recorded by a stub platform', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const a = f.makeActor(0, -2);
    const log = f.hapticLog;
    const take = (fn) => {
      log.length = 0;
      fn();
      f.fx.reset();
      return log.map((e) => (e.kind === 'pulse' ? ['pulse', e.ms, e.strength] : ['pattern', ...e.arr]));
    };
    const proj = () => ({ kind: 'spear', pos: f.P(0, 1.7, 0), prevPos: f.P(0, 1.7, 0), vel: f.P(0, 3, -25), alive: true, stuck: false, byPlayer: true, src: 'touch', age: 0, hits: [], impactPoint: null });
    return {
      flesh: take(() => f.emit('hit', hit(a))),
      guarded: take(() => f.emit('hit', hit(a, { guarded: true, damage: 5 }))),
      kill: take(() => {
        f.emit('hit', hit(a, { killed: true }));
        f.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
      }),
      block: take(() => f.emit('block', { by: 'player', attacker: a, point: null, perfect: false, kind: 'melee' })),
      perfect: take(() => f.emit('block', { by: 'player', attacker: a, point: null, perfect: true, kind: 'melee' })),
      hurt: take(() => f.emit('playerHurt', { damage: 12, hp: 70, from: a, kind: 'melee', relYaw: -0.5 })),
      shieldUp: take(() => f.emit('shield', { up: true })),
      shieldDown: take(() => f.emit('shield', { up: false })),
      release: take(() => f.emit('spearThrown', { projectile: proj() })),
      impact: take(() => f.emit('spearImpact', { projectile: proj(), target: a, point: f.P(0, 1.3, -2), normal: f.P(0, 0, 1), water: false })),
      warning: take(() => f.emit('volley', { phase: 'warning', duration: 1 })),
      loosed: take(() => f.emit('volley', { phase: 'loosed', duration: 1 })),
      aiHit: take(() => f.emit('hit', hit(a, { byPlayer: false, attacker: null }))),
      enemySpear: take(() => f.emit('spearThrown', { projectile: Object.assign(proj(), { byPlayer: false }) })),
    };
  }, HIT);
  assert.deepEqual(r.flesh, [['pulse', 22, 0.7]]);
  assert.deepEqual(r.guarded, [['pattern', 0, 12, 30, 12]]);
  assert.deepEqual(r.kill, [['pulse', 40, 1]]);
  assert.deepEqual(r.block, [['pulse', 30, 0.8]]);
  assert.deepEqual(r.perfect, [['pattern', 0, 15, 25, 45]]);
  assert.deepEqual(r.hurt, [['pulse', 70, 1]]);
  assert.deepEqual(r.shieldUp, [['pulse', 6, 0.3]]);
  assert.deepEqual(r.shieldDown, []);
  assert.deepEqual(r.release, [['pattern', 0, 25, 60, 25]]);
  assert.deepEqual(r.impact, [['pattern', 0, 30, 40, 60]]);
  assert.deepEqual(r.warning, [['pattern', 0, 20, 90, 20]]);
  assert.deepEqual(r.loosed, []);
  assert.deepEqual(r.aiHit, []);
  assert.deepEqual(r.enemySpear, []);
});

await check('pools never exceed 800 particles / 60 decals; blood off means no blood or decals', 'scene=empty', async (page) => {
  const r = await page.evaluate((HIT) => {
    const f = window.__fx;
    const hit = eval(HIT);
    const P = f.P;
    let maxP = 0;
    let maxD = 0;
    const a = f.makeActor(0, -3);
    for (let i = 0; i < 400; i++) {
      const x = (i % 9) - 4;
      f.fx.blood(P(x, 1.3, -3), P(0, 0.2, -1), 3);
      f.fx.sparks(P(x, 1.3, -2), P(0, 0, 1), 3);
      f.fx.dust(P(x, 0, -4), 3);
      f.fx.splinters(P(x, 1, -3), P(0, 0, 1), 3);
      f.fx.splash(P(12 + (i % 5) * 3, -3, -4 - i));
      f.fx.decal(P(x * 0.8, 0, -2 - (i % 7)), i % 2 ? 'pool' : 'blood', 0.8);
      f.frame(1 / 60);
      if (i % 25 === 0) f.step(0);
      maxP = Math.max(maxP, f.fx.particleCount);
      maxD = Math.max(maxD, f.fx.decalCount);
    }
    const out = { maxP, maxD };
    f.fx.reset();
    f.ctx.settings.blood = false;
    f.emit('hit', hit(a, { damage: 60, killed: true }));
    f.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
    f.fx.blood(P(0, 1.3, -2), P(0, 0, -1), 2);
    f.fx.decal(P(0, 0, -2), 'pool', 1);
    out.offParticles = f.fx.particleCount;
    f.run(120);
    out.offDecals = f.fx.decalCount;
    f.ctx.settings.blood = true;
    return out;
  }, HIT);
  assert.ok(r.maxP <= 800, `particles ${r.maxP}`);
  assert.ok(r.maxP >= 700, `the pool fills up under spam (${r.maxP})`);
  assert.ok(r.maxD <= 60, `decals ${r.maxD}`);
  assert.equal(r.maxD, 60);
  assert.equal(r.offParticles, 0, 'no blood particles with blood off (the body-fall dust comes later)');
  assert.equal(r.offDecals, 0);
  console.log(`  max particles ${r.maxP}, max decals ${r.maxD}`);
});

await check('no per-frame allocations in fx update/applyCamera; draw calls', 'scene=empty', async (page) => {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  const setup = await page.evaluate(() => {
    const f = window.__fx;
    const P = f.P;
    f.makeActor(1, -16);
    f.makeShield(-0.5, 1.3, -2);
    // Something of everything, alive for the whole measurement.
    const v = [P(0, 1.3, -3), P(-1, 0.2, -0.3), P(-0.4, 1.4, -1.9), P(0, 0.3, 1), P(1, 0, -3), P(0.3, 1, -3), P(0, 0.2, 1), P(12, -3, -4)];
    let k = 0;
    window.__spawn = () => {
      f.fx.blood(v[0], v[1], 1.2);
      f.fx.sparks(v[2], v[3], 1);
      f.fx.dust(v[4], 1);
      f.fx.splinters(v[5], v[6], 1);
      v[7].z = -4 - (k++ % 8) * 2; // a new splash each time (same-spot splashes are merged)
      f.fx.splash(v[7]);
      f.fx.shake(0.01, 0.3);
    };
    window.__spawn();
    f.fx.decal(P(0, 0, -2), 'pool', 1.2);
    f.fx.slowMo(0.5, 100);
    f.run(10);
    return f.drawCalls();
  });
  // Warm up so everything is optimized, then sample allocations while only fx code runs.
  // Effects are re-spawned from preallocated vectors so particles stay alive throughout.
  const loop = (n) => page.evaluate((n) => {
    const f = window.__fx;
    const fx = f.fx;
    const cam = f.ctx.camera;
    const t = f.ctx.time;
    let minAlive = Infinity;
    for (let i = 0; i < n; i++) {
      if (i % 90 === 0) window.__spawn();
      t.now += 1 / 60;
      fx.update(1 / 60, 1 / 120);
      cam.rotation.set(0.02, 0.01, 0);
      fx.applyCamera(cam);
      if (i % 90 === 89) minAlive = Math.min(minAlive, fx.particleCount);
    }
    return minAlive;
  }, n);
  for (let k = 0; k < 10; k++) {
    await page.evaluate(() => window.__spawn());
    await loop(400);
  }
  // Two sampling windows; a real per-frame allocation shows up in both, JIT re-optimization
  // (interpreted frames boxing doubles for a moment) usually in only one.
  const windows = [];
  let alive = Infinity;
  for (let w = 0; w < 2; w++) {
    await cdp.send('HeapProfiler.collectGarbage');
    await cdp.send('HeapProfiler.startSampling', { samplingInterval: 128 });
    alive = Math.min(alive, await loop(3000));
    const { profile } = await cdp.send('HeapProfiler.stopSampling');
    const byFn = new Map();
    const walk = (node, stack) => {
      const cf = node.callFrame;
      const here = stack.concat(cf);
      if (node.selfSize > 0) {
        const ours = here.find((c) => /gfx\/fx/.test(c.url));
        if (ours) {
          const key = `${cf.functionName || '(anon)'} @ ${cf.url.split('/').slice(-2).join('/')}:${cf.lineNumber + 1} via ${ours.functionName}`;
          byFn.set(key, (byFn.get(key) || 0) + node.selfSize);
        }
      }
      for (const c of node.children || []) walk(c, here);
    };
    walk(profile.head, []);
    windows.push({ total: [...byFn.values()].reduce((a, b) => a + b, 0), byFn });
  }
  const best = windows[0].total <= windows[1].total ? windows[0] : windows[1];
  const total = best.total;
  const byFn = best.byFn;
  console.log(`  fx draw calls: ${setup.fx} (main pass total ${setup.total}, fx meshes visible ${setup.fxVisible}); particles alive during sampling: ≥ ${alive}`);
  console.log(`  bytes allocated under fx code per 3000 updates: ${windows.map((w) => w.total).join(' / ')}${byFn.size ? '\n    ' + [...byFn].map(([k, v]) => `${v} B ${k}`).join('\n    ') : ''}`);
  assert.ok(alive > 50, 'particles were active');
  assert.ok(setup.fx <= 4, `fx draw calls ${setup.fx}`);
  assert.ok(total / 3000 < 8, `allocations per update ${total / 3000} B`);
});

await l.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exitCode = failed.length ? 1 : 0;
