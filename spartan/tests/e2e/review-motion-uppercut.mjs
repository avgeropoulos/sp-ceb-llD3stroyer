// Uppercut variants from the normal guard (no dip first): which come out as a U slash, and when?
import { openGame } from './review-motion-lib.mjs';
const G = await openGame({ source: 'android' });
const rows = await G.ev((R, g, ctx) => {
  R.arena();
  const out = [];
  R.play([{ kind: 'idle', duration: 1.0 }], { rot: 90 }, { source: 'android' });
  for (const weapon of ['sword', 'spear']) {
    if (weapon === 'spear') { g.action({ type: 'switchWeapon', weapon: 'spear' }); R.frames(50); }
    for (const arc of [1.2, 1.6, 2.0, 2.4]) {
      for (const speed of [7, 9, 12]) {
        for (const windup of [0, 0.3]) {
          R.clearLog();
          if (!ctx.player.hasSpearInHand && weapon === 'spear') { ctx.player.refillSpears(); R.frames(5); }
          const p = R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'swing', dir: Math.PI / 2, arc, speed, windup }, { kind: 'idle', duration: 0.8 }], { rot: 90 }, { source: 'android' });
          const onset = p.start + 0.3 + (windup > 0 ? (1.875 * windup) / (0.4 * speed) + 0.06 : 0);
          const gs = R.evs('gesture').map((e) => e.type + (e.dir != null ? ':' + R.tok(e.dir) : '') + '+' + Math.round((e.t - onset) * 1000) + 'ms').join(',');
          out.push(`${weapon} uppercut arc ${arc} speed ${speed} windup ${windup}: [${gs}] swings [${R.evs('swing').map((e) => e.kind).join(',')}]`);
          R.until(() => !ctx.fx.cameraOverridden, 600);
        }
      }
    }
  }
  return out;
});
console.log(rows.join('\n'));
await G.close();
