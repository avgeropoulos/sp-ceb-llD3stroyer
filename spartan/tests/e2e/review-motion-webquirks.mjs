// (6b) Web DeviceMotion quirks from a fresh page: inverted accelerometer sign (gravitySign −1) and the
// W3C rotationRate axis order. How many of the first strikes come out right, and when does the
// self-calibration (frames.js gravitySign / axisMap) settle?
// Usage: node spartan/tests/e2e/review-motion-webquirks.mjs [sign=1|-1] [w3c=0|1] [rot]
import { openGame } from './review-motion-lib.mjs';
const sign = Number(process.argv[2] || -1);
const w3c = process.argv[3] === '1';
const rot = Number(process.argv[4] || 90);
const G = await openGame({ source: 'web' });
try {
  const r = await G.ev((R, g, ctx, a) => {
    window.__webRot = a.rot;
    R.w3cOrder = a.w3c;
    R.arena();
    const opts = { rot: a.rot, gravitySign: a.sign };
    const out = [];
    R.play([{ kind: 'idle', duration: 1.5 }], opts, { source: 'web' });
    out.push(`after 1.5 s idle: sign ${ctx.motionFrames.gravitySign} latched ${ctx.motionFrames.gravityLatched} axis ${ctx.motionFrames.axisMap} rotFix ${ctx.motionFrames.rotFix}`);
    const dirs = [0, Math.PI, -Math.PI / 2, Math.PI / 2, Math.PI / 4, -3 * Math.PI / 4, 0, Math.PI];
    for (let i = 0; i < dirs.length; i++) {
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir: dirs[i], speed: 9 }, { kind: 'idle', duration: 0.6 }], opts, { source: 'web' });
      const gs = R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(',');
      out.push(`strike ${i + 1} want ${R.tok(dirs[i])}: [${gs}] ${gs === 'slash:' + R.tok(dirs[i]) ? 'ok' : 'WRONG'} | sign ${ctx.motionFrames.gravitySign} latched ${ctx.motionFrames.gravityLatched} axis ${ctx.motionFrames.axisMap} yaw ${ctx.player.yaw.toFixed(2)}`);
    }
    R.clearLog();
    R.play([{ kind: 'turn', yaw: 0.8, duration: 1.2 }, { kind: 'idle', duration: 0.8 }], opts, { source: 'web' });
    out.push(`slow 0.8 rad turn → view yaw ${ctx.player.yaw.toFixed(2)}, gestures [${R.evs('gesture').map((e) => e.type).join(',')}] axis ${ctx.motionFrames.axisMap}`);
    return out;
  }, { sign, w3c, rot });
  console.log(`web gravitySign ${sign} w3cOrder ${w3c} rot ${rot}\n  ` + r.join('\n  '));
} finally {
  await G.close();
}
