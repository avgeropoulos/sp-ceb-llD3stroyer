// A 6 rad/s swinger (default synth swing: 0.3 rad wind-up at 0.4×speed, 60 ms pause) slashes R four times at a levy.
import { openGame } from './review-motion-lib.mjs';
const G = await openGame({ source: 'android' });
for (const sens of [1, 1.55]) {
  const r = await G.ev((R, g, ctx, sens) => {
    R.arena();
    ctx.settings.sensitivity = sens;
    const foe = g.spawn('levy', 0, -1.8, {});
    foe.blockChance = foe.baseBlock = 0; foe.hp = foe.maxHp = 1e6; foe.nextAttackAt = 1e9;
    R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: 'android' });
    const out = [];
    for (let i = 0; i < 4; i++) {
      R.clearLog();
      R.play([{ kind: 'idle', duration: 0.5 }, { kind: 'swing', dir: 0, speed: 6 }, { kind: 'idle', duration: 0.8 }], { rot: 90 }, { source: 'android' });
      const ang = Math.atan2(-(foe.pos.x - ctx.player.pos.x), -(foe.pos.z - ctx.player.pos.z)) - ctx.player.yaw;
      out.push(`slash ${i + 1}: gestures [${R.evs('gesture').map((e) => e.type).join(',')}] hits ${R.evs('hit').length}; view yaw ${ctx.player.yaw.toFixed(2)} rad (${(ctx.player.yaw * 57.3).toFixed(0)}°), levy now ${(ang * 57.3).toFixed(0)}° off-centre`);
    }
    return out;
  }, sens);
  console.log(`sensitivity ${sens}:\n  ` + r.join('\n  '));
}
await G.close();
