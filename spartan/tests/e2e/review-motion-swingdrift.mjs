// (5b) View drift caused by single strikes (gyro look must absorb strikes + return to guard).
// Usage: node spartan/tests/e2e/review-motion-swingdrift.mjs [source] [sensitivity]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const sens = Number(process.argv[3] || 1);
const G = await openGame({ source });
try {
  const rows = await G.ev((R, g, ctx, a) => {
    R.arena();
    ctx.settings.sensitivity = a.sens;
    const opts = { rot: 90 };
    R.play([{ kind: 'idle', duration: 1.0 }], opts, { source: a.source });
    const out = [];
    for (const speed of [5, 6, 7, 9, 12]) {
      for (const dir of [0, Math.PI, Math.PI / 2, -Math.PI / 2]) {
        const y0 = ctx.player.yaw;
        R.clearLog();
        const trace = [];
        R.play([{ kind: 'idle', duration: 0.4 }, { kind: 'swing', dir, speed }, { kind: 'idle', duration: 1.0 }], opts, { source: a.source, each: () => trace.push(ctx.player.yaw - y0) });
        const g2 = R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(',');
        out.push({ speed, dir: R.tok(dir), d: +(ctx.player.yaw - y0).toFixed(3), max: +Math.max(...trace.map(Math.abs)).toFixed(3), g: g2 });
      }
    }
    return out;
  }, { source, sens });
  console.log(`${source} sensitivity ${sens}: net view yaw change per strike (rad), max excursion, gestures`);
  for (const r of rows) console.log(`  speed ${String(r.speed).padStart(2)} ${r.dir.padEnd(2)} net ${String(r.d).padStart(6)} max ${String(r.max).padStart(6)} [${r.g}]`);
} finally {
  await G.close();
}
