// The hilt grip's cock-back spear throw in the real game (DESIGN.md §5.3, §7, §11 fx).
// Motion comes from the android fake source of review-motion-lib.mjs (rotation 0, portrait-primary),
// synthesized with synth's hilt model (hold: 'hilt'), at 412×915:
//  - a cock-back behind the shoulder and a forward snap: the windup and throw gestures, the
//    viewmodel's throwWindup, the "armed" haptic between them, the release and impact haptics;
//  - the spear flies at the locked foe 12 m away, off the view's start (the view turned to him);
//  - the spear cam trails at scale 0.1 until the synthetic return to guard settles the phone, then
//    ramps; the view's yaw doesn't move under it;
//  - with the spear in hand, an ordinary 0.8 rad lift and a chop is a chop: no windup, no throw, no spear cam;
//  - in the Agoge's spear step, with the far dummy locked, the thrown spear lands within 2 m of it.
// Usage: node spartan/tests/e2e/hilt-throw.mjs [outDir]   (outDir: optional screenshots of the
//        armed pose and the spear cam)
import fs from 'node:fs';
import path from 'node:path';
import { openGame } from './review-motion-lib.mjs';

const outDir = process.argv[2] || null;
if (outDir) fs.mkdirSync(outDir, { recursive: true });
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const W = 412;
const H = 915;
const HAPTIC = { armed: '0,35,45,35', release: '0,30,60,30', impact: '0,40,40,90' }; // fx HAPTICS_HILT rows (P7)

const G = await openGame({ source: 'android', width: W, height: H, grip: 'hilt' });
const shot = async (name) => {
  if (!outDir) return;
  // a few real animation frames, so the HUD's CSS transitions catch up (manual mode requests none)
  await G.page.evaluate(() => new Promise((res) => {
    let n = 0;
    const f = () => (++n > 30 ? res() : requestAnimationFrame(f));
    requestAnimationFrame(f);
  }));
  await G.ev((R) => R.draw());
  await G.page.screenshot({ path: path.join(outDir, `${name}.png`) });
};
try {
  // ---------------------------------------------------------------- 1. cock-back + throw at the lock
  const setup = await G.ev((R, g, ctx) => {
    R.arena({ grip: 'hilt' });
    // One foe 12 m away, 0.35 rad to the right of the line: auto-acquired, and the view turns to him.
    const b = -0.35;
    const foe = ctx.ai.spawnDummy(-12 * Math.sin(b), -12 * Math.cos(b), {});
    window.__foe = foe;
    // the guard is learned from a held hilt guard (B.3.3): idle at guard first
    R.play([{ kind: 'idle', duration: 1.6 }], { hold: 'hilt', rot: 0 }, { source: 'android' });
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    R.frames(60);
    // main.js calls viewmodel.play('throwWindup') on a spear windup: record those calls
    window.__vmPlays = [];
    const play = ctx.viewmodel.play;
    ctx.viewmodel.play = function (kind, opts) {
      window.__vmPlays.push({ kind, t: R.vt });
      return play.call(this, kind, opts);
    };
    window.__thrown = [];
    ctx.events.on('spearThrown', (e) => {
      const p = e.projectile;
      const pl = ctx.player;
      window.__thrown.push({ vx: p.vel.x, vy: p.vel.y, vz: p.vel.z, byPlayer: p.byPlayer, px: pl.pos.x, pz: pl.pos.z, yaw: pl.yaw, t: R.vt });
    });
    return {
      grip: ctx.input.grip,
      lock: ctx.player.lockTarget === foe,
      lockMode: ctx.player.lockMode,
      yaw: ctx.player.yaw,
      bearing: b,
      weapon: `${ctx.player.weapon}/${ctx.player.hasSpearInHand}`,
      guard: !!ctx.gestures.guardLearned,
      cls: document.getElementById('controls').className,
    };
  });
  check('the hilt grip is on (ctx.input.grip, #controls.ctl-hilt), the spear in hand, the guard learned',
    setup.grip === 'hilt' && /\bctl-hilt\b/.test(setup.cls) && setup.weapon === 'spear/true' && setup.guard, JSON.stringify(setup));
  check('the foe 12 m away, 0.35 rad right, is locked and the view faces him', setup.lock && Math.abs(setup.yaw - setup.bearing) < 0.02,
    `lock ${setup.lock} ${setup.lockMode}, yaw ${setup.yaw.toFixed(3)} vs bearing ${setup.bearing}`);

  const thr = await G.ev((R, g, ctx) => {
    R.clearLog();
    window.__haptics.length = 0;
    // lookBack 0.5: the phone is back at guard well inside the hold's 1.4 s cap (SPEAR_CAM.holdMax),
    // so the ramp is the settle's doing (the default 0.7 s return settles just at the cap)
    const samples = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'throw', lookBack: 0.5 }, { kind: 'idle', duration: 3.0 }], { hold: 'hilt', rot: 0 });
    const fed = R.feed(samples, 'android');
    const trace = [];
    const n = Math.ceil((fed.dur + 0.3) * 60);
    for (let i = 0; i < n; i++) {
      R.frames(1);
      trace.push({ t: R.vt, cam: ctx.fx.spearCamPhase, over: ctx.fx.cameraOverridden, scale: ctx.time.scale, settled: ctx.input.phoneSettled, yaw: ctx.player.yaw });
    }
    const gest = R.evs('gesture').map((e) => ({ type: e.type, t: e.t, power: e.power, fast: e.fast }));
    return {
      start: fed.start,
      gest,
      vm: window.__vmPlays.slice(),
      haptics: window.__haptics.slice(),
      thrown: window.__thrown.slice(),
      cams: R.evs('spearCam').map((e) => ({ phase: e.phase, t: e.t })),
      impacts: R.evs('spearImpact').map((e) => ({ target: e.target, t: e.t })),
      trace,
      foe: { x: window.__foe.pos.x, z: window.__foe.pos.z },
      lockStill: ctx.player.lockTarget === window.__foe,
    };
  });
  const types = thr.gest.map((e) => e.type).filter((t) => t !== 'overSpeed');
  const wu = thr.gest.find((e) => e.type === 'windup');
  const th = thr.gest.find((e) => e.type === 'throw');
  check('cock-back + snap: exactly one windup, then one throw, and no slash or thrust',
    types.filter((t) => t === 'windup').length === 1 && types.filter((t) => t === 'throw').length === 1 && wu && th && wu.t < th.t
      && !types.includes('slash') && !types.includes('thrust'),
    thr.gest.map((e) => `${e.type}@${(e.t - thr.start).toFixed(2)}${e.power != null ? ` p=${e.power.toFixed(2)}` : ''}`).join(' '));
  check('a firm flick throws at power > 0.5', th && th.power > 0.5, th ? `power ${th.power.toFixed(2)}` : 'no throw');
  const vmW = thr.vm.filter((p) => p.kind === 'throwWindup');
  check('the windup plays the viewmodel\'s throwWindup on its own frame', wu && vmW.length === 1 && Math.abs(vmW[0].t - wu.t) < 0.02,
    `${vmW.map((p) => (p.t - thr.start).toFixed(3))} vs windup ${wu ? (wu.t - thr.start).toFixed(3) : '-'}`);
  const hp = (v) => thr.haptics.filter((h) => h.kind === 'pattern' && h.v === v);
  const armed = hp(HAPTIC.armed);
  check('the "armed" haptic (pattern 0,35,45,35) plays once, at the windup and before the throw',
    wu && th && armed.length === 1 && armed[0].t >= wu.t - 1e-6 && armed[0].t <= wu.t + 0.02 && armed[0].t < th.t,
    thr.haptics.map((h) => `${h.kind}:${h.v}@${(h.t - thr.start).toFixed(2)}`).join(' '));
  check('then the release and impact haptics (0,30,60,30 and 0,40,40,90)', hp(HAPTIC.release).length === 1 && hp(HAPTIC.impact).length === 1
    && hp(HAPTIC.release)[0].t > armed[0]?.t && hp(HAPTIC.impact)[0].t > hp(HAPTIC.release)[0].t);
  const sp = thr.thrown[0];
  const bearing = sp ? Math.atan2(-(thr.foe.x - sp.px), -(thr.foe.z - sp.pz)) : NaN;
  const vyaw = sp ? Math.atan2(-sp.vx, -sp.vz) : NaN;
  const dyaw = Math.abs(Math.atan2(Math.sin(vyaw - bearing), Math.cos(vyaw - bearing)));
  check('spearThrown flies at the locked foe 12 m away: velocity yaw within 0.03 rad of his bearing',
    thr.thrown.length === 1 && sp.byPlayer && dyaw <= 0.03, sp ? `velocity yaw ${vyaw.toFixed(4)}, bearing ${bearing.toFixed(4)}, Δ ${dyaw.toFixed(4)}` : 'no spear');
  check('the spear hits him (spearImpact on the dummy)', thr.impacts.some((e) => e.target === 'dummy'), JSON.stringify(thr.impacts));

  // The hold: scale 0.1 from the cam's start until the phone has been settled 0.1 s, then a ramp.
  const tr = thr.trace;
  const i0 = tr.findIndex((f) => f.cam === 'trail');
  const iUp = tr.findIndex((f, i) => i > i0 && f.cam === 'trail' && f.scale > 0.1 + 1e-6);
  const iSet = tr.findIndex((f, i) => i > i0 && f.settled);
  const hold = i0 >= 0 && iUp > i0 ? tr.slice(i0, iUp) : [];
  const holdS = hold.length / 60;
  check('spearCam starts on the throw (spearCam start)', i0 >= 0 && thr.cams.some((c) => c.phase === 'start'), thr.cams.map((c) => c.phase).join(','));
  check('the trail holds time at 0.1 while the phone is still out (not settled)',
    hold.length > 10 && hold.every((f) => Math.abs(f.scale - 0.1) < 1e-9) && !tr[i0].settled,
    `hold ${holdS.toFixed(2)} s`);
  check('the synthetic return to guard settles the phone, and the ramp starts 0.1 s after it (before the 1.4 s cap)',
    iSet > i0 && iUp > iSet && iUp - iSet >= 5 && iUp - iSet <= 8 && (iUp - i0) / 60 < 1.4 - 1 / 60,
    `settled at +${((iSet - i0) / 60).toFixed(2)} s, ramp at +${((iUp - i0) / 60).toFixed(2)} s of the cam`);
  const over = tr.filter((f) => f.over);
  const yawSpan = over.length ? Math.max(...over.map((f) => f.yaw)) - Math.min(...over.map((f) => f.yaw)) : NaN;
  check('the view\'s yaw doesn\'t move while the spear cam owns the camera, and the lock stays',
    over.length > 0 && yawSpan < 1e-6 && thr.lockStill, `yaw span ${yawSpan}`);

  if (outDir) {
    // the armed pose (the cock-back, held) and the spear cam that follows its snap, for a look
    await G.ev((R, g, ctx) => {
      ctx.player.refillSpears();
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      R.frames(60);
      const n = window.__vmPlays.length;
      R.feed(g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'raise', hold: 0.6 }, { kind: 'swing', dir: -Math.PI / 2, arc: 2.6, windup: 0 }, { kind: 'idle', duration: 2.5 }], { hold: 'hilt', rot: 0 }), 'android');
      R.until(() => window.__vmPlays.length > n, 240);
      R.frames(10);
    });
    await shot('hilt-throw-armed');
    await G.ev((R, g, ctx) => {
      R.until(() => ctx.fx.spearCamPhase === 'trail', 240);
      R.frames(15);
    });
    await shot('hilt-throw-spearcam');
    await G.ev((R) => R.frames(400));
  }

  // ---------------------------------------------------------------- 2. lift + chop with the spear
  const chop = await G.ev((R, g, ctx) => {
    ctx.ai.clear();
    const foe = ctx.ai.spawnDummy(0, -1.8, {});
    R.frames(90);
    ctx.player.refillSpears();
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    R.frames(60);
    R.play([{ kind: 'idle', duration: 1.2 }], { hold: 'hilt', rot: 0 }, { source: 'android' });
    R.clearLog();
    window.__haptics.length = 0;
    const weapon = `${ctx.player.weapon}/${ctx.player.hasSpearInHand}`;
    R.play([{ kind: 'raise', cockArc: 0.8, hold: 0.15 }, { kind: 'swing', dir: -Math.PI / 2, speed: 9, windup: 0 }, { kind: 'idle', duration: 1.5 }], { hold: 'hilt', rot: 0 }, { source: 'android' });
    return {
      weapon,
      lock: ctx.player.lockTarget === foe,
      gest: R.evs('gesture').map((e) => `${e.type}${e.dir != null ? ':' + R.tok(e.dir) : ''}`),
      swings: R.evs('swing').map((e) => e.kind),
      cams: R.evs('spearCam').length,
      thrown: R.evs('spearThrown').length,
      armed: window.__haptics.filter((h) => h.v === '0,35,45,35').length,
      still: `${ctx.player.weapon}/${ctx.player.hasSpearInHand}`,
    };
  });
  const cg = chop.gest.filter((x) => x !== 'overSpeed');
  check('spear in hand: a 0.8 rad lift and a chop gives one D slash, no windup and no throw',
    chop.weapon === 'spear/true' && cg.length === 1 && cg[0] === 'slash:D', `${chop.gest.join(' ') || '(none)'}`);
  check('…a spear attack (no throw), no spear cam, no armed haptic, the spear still in hand',
    chop.swings.length === 1 && chop.swings[0] !== 'throw' && chop.cams === 0 && chop.thrown === 0 && chop.armed === 0 && chop.still === 'spear/true',
    `swings [${chop.swings}], spearCam ${chop.cams}, thrown ${chop.thrown}, armed ${chop.armed}`);

  // ---------------------------------------------------------------- 3. the Agoge's spear step
  const ag = await G.page.evaluate(async () => {
    const T = await import('./js/ui/training.js');
    // The runtime step list of the hilt Agoge (§G): the lock step after the thrust once it exists.
    const steps = T.HILT_LOCK_STEP ? [...T.STEPS.slice(0, 6), T.HILT_LOCK_STEP, ...T.STEPS.slice(6)] : T.STEPS;
    return { idx: steps.findIndex((s) => s.id === 'spear'), lockStep: !!T.HILT_LOCK_STEP };
  });
  const agoge = await G.ev((R, g, ctx, a) => {
    g.startTraining();
    R.frames(10);
    const tr = ctx.training;
    let guard = 0;
    while (tr.stepIndex < a.idx && guard++ < 30) {
      document.querySelector('#training [data-act="skip"]').click();
      R.frames(2);
    }
    const at = tr.stepIndex;
    R.play([{ kind: 'idle', duration: 1.6 }], { hold: 'hilt', rot: 0 }, { source: 'android' });
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    R.frames(50);
    for (let k = 0; k < 2; k++) {
      g.action({ type: 'thrust', power: 0.8 });
      R.frames(40);
    }
    R.frames(90); // the near dummy steps aside
    const far = ctx.actors.find((x) => x.role === 'dummy' && Math.abs(x.pos.z + 18) < 0.5);
    const lockedByAgoge = ctx.player.lockTarget === far;
    if (!lockedByAgoge) ctx.player.setLock(far);
    R.until(() => !ctx.player.lockSpring.moving, 180);
    R.frames(20);
    const yaw = ctx.player.yaw;
    const lockAtThrow = ctx.player.lockTarget === far; // the step re-locks the near dummy once it is done
    R.clearLog();
    const imp = [];
    const off = ctx.events.on('spearImpact', (e) => imp.push({ far: e.target === far, x: e.point ? e.point.x : NaN, z: e.point ? e.point.z : NaN }));
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'throw' }, { kind: 'idle', duration: 3.0 }], { hold: 'hilt', rot: 0 }, { source: 'android' });
    R.frames(120);
    off();
    return {
      at,
      after: tr.stepIndex,
      lockedByAgoge,
      lock: lockAtThrow,
      yaw,
      far: far ? { x: far.pos.x, z: far.pos.z } : null,
      imp,
      gest: R.evs('gesture').map((e) => e.type),
      hint: document.querySelector('#training .tr-hint')?.textContent || '',
    };
  }, ag);
  check(`the Agoge reaches the spear step (index ${ag.idx}${ag.lockStep ? ', after the lock step' : ''})`, agoge.at === ag.idx, `at ${agoge.at}`);
  check('the far dummy is locked once the near one steps aside', agoge.lock && agoge.far,
    `${agoge.lockedByAgoge ? 'locked by the Agoge' : 'locked by player.setLock here (the Agoge does not lock it yet)'}; yaw ${agoge.yaw.toFixed(3)}`);
  const hitFar = agoge.imp.find((p) => p.far || (agoge.far && Math.hypot(p.x - agoge.far.x, p.z - agoge.far.z) <= 2));
  check('the hilt throw lands within 2 m of the far dummy, and the step completes', !!hitFar && agoge.after > agoge.at,
    `impacts ${JSON.stringify(agoge.imp.map((p) => ({ far: p.far, x: +p.x.toFixed(2), z: +p.z.toFixed(2) })))}; step ${agoge.at} → ${agoge.after}; gestures [${agoge.gest}]; hint "${agoge.hint}"`);

  const errors = await G.errors();
  check('no page errors', errors.length === 0, errors.join(' | ').slice(0, 1500));
} catch (err) {
  check(`script error: ${err && (err.stack || err)}`, false);
} finally {
  await G.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
