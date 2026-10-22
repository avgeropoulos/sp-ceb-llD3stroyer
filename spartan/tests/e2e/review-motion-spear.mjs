// (4) Spear by motion in the real game: swap button (real touch) → spear; synthesized overhand throw →
// windup + throw → projectile → spear cam hold until the phone settles → impact → camera returns.
// Then: stray gestures, camera drift, and whether gyro look resumes (a slow 0.6 rad body turn).
// Usage: node spartan/tests/e2e/review-motion-spear.mjs [source] [rot] [lookBack] [targetZ] [extra synth opts JSON]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rot = Number(process.argv[3] || 90);
const lookBack = Number(process.argv[4] || 0.7);
const targetZ = Number(process.argv[5] || -12);
const extra = JSON.parse(process.argv[6] || '{}');
const G = await openGame({ source });
try {
  const r = await G.ev((R, g, ctx, a) => {
    if (a.source === 'android') window.__nativeRot = a.rot;
    if (a.source === 'web') window.__webRot = a.rot;
    const opts = Object.assign({ rot: a.rot }, a.extra);
    R.arena();
    // a training dummy holds still (a levy would walk up to the line)
    const foe = ctx.ai.spawnDummy(0.3, a.targetZ, {});
    R.play([{ kind: 'idle', duration: 1.2 }], opts, { source: a.source });
    // the weapon-swap corner button, by a real touch
    const btn = document.querySelector('#controls .ctl-swap');
    const rc = btn.getBoundingClientRect();
    const bx = rc.left + rc.width / 2, by = rc.top + rc.height / 2;
    const hitEl = R.touch('pointerdown', bx, by, 31);
    R.touch('pointerup', bx, by, 31);
    R.frames(60);
    const weapon = ctx.player.weapon + '/' + ctx.player.hasSpearInHand;
    R.clearLog();
    const yaw0 = ctx.player.yaw;
    const trace = [];
    const samples = g.synth([{ kind: 'idle', duration: 0.3 }, { kind: 'throw', lookBack: a.lookBack }, { kind: 'idle', duration: 2.5 }], opts);
    const fed = R.feed(samples, a.source);
    let lastPhase = 'off', lastSettled = null, lastArm = '';
    const N = Math.ceil((fed.dur + 0.5) * 60);
    for (let i = 0; i < N; i++) {
      R.frames(1);
      const ph = ctx.fx.spearCamPhase;
      const st = ctx.input.phoneSettled;
      const arm = (ctx.viewmodel.debug.arm && ctx.viewmodel.debug.arm.kind) || '';
      if (ph !== lastPhase || st !== lastSettled || arm !== lastArm || (ph === 'trail' && i % 6 === 0)) {
        trace.push(`${(R.vt - fed.start).toFixed(2)}s motion-t | cam=${ph} scale=${ctx.time.scale.toFixed(2)} settled=${st} arm=${arm} yaw=${ctx.player.yaw.toFixed(3)}`);
        lastPhase = ph; lastSettled = st; lastArm = arm;
      }
    }
    const yaw1 = ctx.player.yaw;
    trace.push('target distance ' + Math.hypot(foe.pos.x - ctx.player.pos.x, foe.pos.z - ctx.player.pos.z).toFixed(2));
    const evs = R.log.filter((e) => ['gesture', 'swing', 'spearCam', 'spearImpact', 'hit'].includes(e.ev)).map((e) => `${(e.t - fed.start).toFixed(2)}s ${e.ev}:${e.type || e.kind || e.phase || ''}${e.target ? '→' + e.target : ''}${e.power != null ? ' p=' + e.power.toFixed(2) : ''}`);
    // gyro look afterwards: a slow 0.6 rad turn to the left
    R.clearLog();
    R.play([{ kind: 'idle', duration: 0.5 }, { kind: 'turn', yaw: 0.6, duration: 1.2 }, { kind: 'idle', duration: 0.6 }], opts, { source: a.source });
    const yaw2 = ctx.player.yaw;
    const after = R.evs('gesture').map((e) => e.type);
    return { hitEl, weapon, evs, trace, yaw0, yaw1, yaw2, turned: yaw2 - yaw1, after, hold: ctx.gestures.debug.lookState };
  }, { source, rot, lookBack, targetZ, extra });
  console.log('swap button touch →', r.hitEl, '| weapon', r.weapon);
  console.log('events (seconds after the motion starts):\n  ' + r.evs.join('\n  '));
  console.log('trace:\n  ' + r.trace.join('\n  '));
  console.log(`yaw before ${r.yaw0.toFixed(3)} after cam ${r.yaw1.toFixed(3)} (drift ${(r.yaw1 - r.yaw0).toFixed(3)}); slow 0.6 rad turn afterwards moved the view ${r.turned.toFixed(3)} rad; gestures during turn [${r.after}] lookState ${r.hold}`);
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
