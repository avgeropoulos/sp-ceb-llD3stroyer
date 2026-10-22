// (9b) Pause in motion mode: motion while paused must be ignored, and resuming must not replay stale
// sensor samples as gestures. Uses the real pause button and the pause menu's Resume button.
// Usage: node spartan/tests/e2e/review-motion-pause.mjs [source=android|web|inject]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'android';
const G = await openGame({ source });
const PI = Math.PI;
const cases = [
  { name: 'slash R 1.0 s before Resume', script: [{ kind: 'idle', duration: 1.0 }, { kind: 'swing', dir: 0, speed: 9 }, { kind: 'idle', duration: 1.0 }], before: 0 },
  { name: 'phone raised from the lap to guard (U, 7 rad/s) just before Resume', script: [{ kind: 'idle', duration: 1.5 }, { kind: 'swing', dir: PI / 2, speed: 7, windup: 0, recoil: 0, ret: 0 }, { kind: 'idle', duration: 0.3 }], before: 0 },
  { name: 'thrust 0.5 s before Resume', script: [{ kind: 'idle', duration: 1.5 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.5 }], before: 0 },
  { name: 'slash L 4 s before Resume (older than the ring)', script: [{ kind: 'idle', duration: 0.5 }, { kind: 'swing', dir: PI, speed: 9 }, { kind: 'idle', duration: 4.0 }], before: 0 },
];
try {
  for (const c of cases) {
    const r = await G.ev((R, g, ctx, a) => {
      R.arena();
      const foe = g.spawn('levy', 0, -1.7, {});
      foe.blockChance = foe.baseBlock = 0; foe.hp = foe.maxHp = 1e6; foe.nextAttackAt = 1e9;
      R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: a.source });
      // pause with the real corner button
      const pb = document.querySelector('#controls .ctl-pause').getBoundingClientRect();
      R.touch('pointerdown', pb.left + pb.width / 2, pb.top + pb.height / 2, 71);
      R.touch('pointerup', pb.left + pb.width / 2, pb.top + pb.height / 2, 71);
      R.frames(2);
      const pausedMode = ctx.mode;
      R.clearLog();
      // the phone moves while the pause menu is up
      R.play(a.script, { rot: 90 }, { source: a.source, tail: 0.05 });
      const duringPause = R.evs('gesture').length + R.evs('swing').length;
      // Resume (the pause menu button)
      const rb = document.querySelector('[data-act="resume"]');
      const visible = !!rb && rb.offsetParent !== null;
      rb && rb.click();
      const modeAfter = ctx.mode;
      R.frames(30);
      return {
        pausedMode, visible, modeAfter, duringPause,
        after: R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '') + '@' + (e.frame)).join(',') || '-',
        swings: R.evs('swing').map((e) => e.kind + (e.kind === 'slash' ? ':' + R.tok(e.dir) : '')).join(',') || '-',
        hits: R.evs('hit').length,
        yaw: +ctx.player.yaw.toFixed(3),
      };
    }, { source, script: c.script });
    const bad = r.after !== '-' || r.swings !== '-';
    console.log(`${bad ? 'FAIL' : 'ok  '} ${c.name}: mode ${r.pausedMode}→${r.modeAfter} (resume button visible ${r.visible}); during pause ${r.duringPause} events; after Resume gestures [${r.after}] swings [${r.swings}] hits ${r.hits} yaw ${r.yaw}`);
  }
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
