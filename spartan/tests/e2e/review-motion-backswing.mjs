// (5c) "Cock back, pause, strike": a slow backswing (below swingStart) followed by a short pause and a
// normal 9 rad/s strike with return to guard. Measures the permanent view rotation it leaves behind.
// Usage: node spartan/tests/e2e/review-motion-backswing.mjs [source] [sensitivity]
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
    for (const back of [{ arc: 0.3, speed: 2.5 }, { arc: 0.5, speed: 3.0 }, { arc: 0.6, speed: 2.0 }]) {
      for (const pause of [0.0, 0.06, 0.12, 0.2, 0.3]) {
        for (const dir of [0, Math.PI]) {
          ctx.ai.clear();
          const foe = g.spawn('levy', 0, -1.8, {});
          foe.blockChance = foe.baseBlock = 0; foe.hp = foe.maxHp = 1e6; foe.nextAttackAt = 1e9;
          ctx.player.yaw = 0; ctx.player.pos.set(0, 0, 0);
          R.frames(2);
          const y0 = ctx.player.yaw;
          R.clearLog();
          const tr = [];
          let atStrike = null;
          const off = ctx.events.on('swing', () => { atStrike = ctx.player.yaw - y0; });
          // backswing opposite to the strike, a hold, then the strike (no extra wind-up) and the return
          R.play([
            { kind: 'idle', duration: 0.4 },
            { kind: 'swing', dir: dir + Math.PI, speed: back.speed, arc: back.arc, windup: 0, recoil: 0, ret: 0, pronate: 0 },
            { kind: 'idle', duration: pause },
            { kind: 'swing', dir, speed: 9, windup: 0 },
            { kind: 'idle', duration: 1.0 },
          ], opts, { source: a.source, each: () => tr.push(ctx.player.yaw - y0) });
          off();
          out.push({ max: +Math.max(...tr.map(Math.abs)).toFixed(3), atStrike: atStrike == null ? null : +atStrike.toFixed(3), hits: R.evs('hit').length, back: `${back.arc}rad@${back.speed}`, pause, dir: R.tok(dir), d: +(ctx.player.yaw - y0).toFixed(3), g: R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '')).join(',') });
        }
      }
    }
    return out;
  }, { source, sens });
  console.log(`${source} sensitivity ${sens}: backswing, pause, strike → permanent view yaw change (rad)`);
  for (const r of rows) console.log(`  backswing ${r.back.padEnd(10)} pause ${r.pause.toFixed(2)}s strike ${r.dir}  view moved ${String(r.d).padStart(6)} (max ${r.max}, at the swing ${r.atStrike})  hits ${r.hits}  gestures [${r.g}]`);
} finally {
  await G.close();
}
