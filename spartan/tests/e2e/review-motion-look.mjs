// (5) Gyro look in the real game: slow turns turn the view, swings don't drift it, recenter works.
// Usage: node spartan/tests/e2e/review-motion-look.mjs [source] [rot] [extra synth opts JSON]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rot = Number(process.argv[3] || 90);
const extra = JSON.parse(process.argv[4] || '{}');
const G = await openGame({ source });
const PI = Math.PI;
try {
  const r = await G.ev((R, g, ctx, a) => {
    if (a.source === 'android') window.__nativeRot = a.rot;
    if (a.source === 'web') window.__webRot = a.rot;
    const PI = Math.PI;
    const opts = Object.assign({ rot: a.rot }, a.extra);
    R.arena();
    const out = {};
    R.play([{ kind: 'idle', duration: 1.2 }], opts, { source: a.source });
    // a) slow turns
    let y0 = ctx.player.yaw;
    R.play([{ kind: 'turn', yaw: 1.0, duration: 1.2 }, { kind: 'idle', duration: 0.8 }], opts, { source: a.source });
    out.turnLeft1 = +(ctx.player.yaw - y0).toFixed(3);
    y0 = ctx.player.yaw;
    R.play([{ kind: 'turn', yaw: -0.5, duration: 1.5 }, { kind: 'idle', duration: 0.8 }], opts, { source: a.source });
    out.turnRight05 = +(ctx.player.yaw - y0).toFixed(3);
    // a fast body turn (0.8 rad in 0.35 s: e.g. to face an attacker on the flank)
    y0 = ctx.player.yaw;
    R.play([{ kind: 'turn', yaw: 0.8, duration: 0.35 }, { kind: 'idle', duration: 1.0 }], opts, { source: a.source });
    out.fastTurn08 = +(ctx.player.yaw - y0).toFixed(3);
    out.fastTurnGestures = R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(',');
    R.clearLog();
    // b) 24 strikes in all directions (with return to guard), plus thrusts: view drift
    y0 = ctx.player.yaw;
    const script = [];
    for (let i = 0; i < 24; i++) {
      script.push({ kind: 'idle', duration: 0.25 });
      script.push({ kind: 'swing', dir: ((i * 3) % 8) * (PI / 4) - (((i * 3) % 8) > 4 ? 2 * PI : 0), speed: 8 + (i % 4) * 2 });
      if (i % 6 === 5) script.push({ kind: 'thrust' });
    }
    script.push({ kind: 'idle', duration: 1.0 });
    const swYaw = [];
    R.play(script, opts, { source: a.source, each: () => swYaw.push(ctx.player.yaw) });
    out.strikeDrift = +(ctx.player.yaw - y0).toFixed(3);
    out.strikeMaxExcursion = +Math.max(...swYaw.map((y) => Math.abs(y - y0))).toFixed(3);
    out.strikeGestures = R.evs('gesture').length + ' gestures, ' + R.evs('gesture', (e) => e.type === 'slash').length + ' slashes';
    // c) recenter via the real button
    R.play([{ kind: 'turn', yaw: 0.7, duration: 1.0 }, { kind: 'idle', duration: 0.6 }], opts, { source: a.source });
    out.beforeRecenter = +ctx.player.yaw.toFixed(3);
    const btn = document.querySelector('#controls .ctl-recenter');
    const rc = btn.getBoundingClientRect();
    R.touch('pointerdown', rc.left + rc.width / 2, rc.top + rc.height / 2, 41);
    R.touch('pointerup', rc.left + rc.width / 2, rc.top + rc.height / 2, 41);
    R.frames(30);
    out.afterRecenter = +ctx.player.yaw.toFixed(3);
    R.play([{ kind: 'idle', duration: 0.5 }, { kind: 'swing', dir: 0 }, { kind: 'idle', duration: 0.8 }], opts, { source: a.source });
    out.afterRecenterAndSwing = +ctx.player.yaw.toFixed(3);
    R.play([{ kind: 'turn', yaw: -0.4, duration: 1.0 }, { kind: 'idle', duration: 0.6 }], opts, { source: a.source });
    out.turnAfterRecenter = +ctx.player.yaw.toFixed(3);
    return out;
  }, { source, rot, extra });
  console.log(`${source} rot=${rot} ${JSON.stringify(extra)}`, JSON.stringify(r, null, 1));
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
