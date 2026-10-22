// (9c) Swings while the spear cam is active must be ignored, and nothing stray may fire when it ends.
// Usage: node spartan/tests/e2e/review-motion-spearcam-swing.mjs [source]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const G = await openGame({ source });
try {
  for (const delay of [0.4, 1.2, 1.8]) {
    const r = await G.ev((R, g, ctx, a) => {
      R.arena();
      ctx.ai.spawnDummy(0.3, -20, {});
      const near = g.spawn('levy', -0.3, -1.8, {});
      near.blockChance = near.baseBlock = 0; near.hp = near.maxHp = 1e6; near.nextAttackAt = 1e9;
      R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      R.frames(50);
      // aim past the near levy at the far dummy
      ctx.player.yaw = -0.03;
      R.clearLog();
      // throw, look back at the phone, then slash R `delay` s after the phone is back at guard
      const s = R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'throw', lookBack: 0.5 }, { kind: 'idle', duration: a.delay }, { kind: 'swing', dir: 0, speed: 10 }, { kind: 'idle', duration: 0.6 }], { rot: 90 }, { source: a.source, tail: 0 });
      const camEvents = R.evs('spearCam').map((e) => e.phase + '@' + (e.t - s.start).toFixed(2)).join(',');
      R.until(() => !ctx.fx.cameraOverridden, 600);
      R.frames(60);
      return {
        cam: camEvents,
        gest: R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '') + '@' + (e.t - s.start).toFixed(2)).join(','),
        swings: R.evs('swing').map((e) => e.kind + (e.kind === 'slash' ? ':' + R.tok(e.dir) : '') + '@' + (e.t - s.start).toFixed(2)).join(','),
        camEnd: (R.evs('spearCam', (e) => e.phase === 'end')[0] || {}).t - s.start,
      };
    }, { source, delay });
    console.log(`slash ${delay}s after looking back: spearCam [${r.cam}] end@${r.camEnd && r.camEnd.toFixed(2)}\n   gestures [${r.gest}]\n   swings   [${r.swings}]`);
  }
} finally {
  await G.close();
}
