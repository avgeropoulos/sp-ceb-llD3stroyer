// (2) Motion thrust → stab; real touch on the glass (#controls, motion layout) → shield; thrust → bash.
// Usage: node spartan/tests/e2e/review-motion-thrust.mjs [source] [rot]
import { openGame } from './review-motion-lib.mjs';
const source = process.argv[2] || 'inject';
const rot = Number(process.argv[3] || 90);
const G = await openGame({ source });
try {
  const r = await G.ev((R, g, ctx, a) => {
    if (a.source === 'android') window.__nativeRot = a.rot;
    if (a.source === 'web') window.__webRot = a.rot;
    R.arena();
    const out = {};
    const foe = () => {
      ctx.ai.clear();
      const d = g.spawn('levy', 0, -1.5, {});
      d.blockChance = d.baseBlock = 0;
      d.hp = d.maxHp = 1e6;
      d.nextAttackAt = 1e9;
      R.frames(2);
      return d;
    };
    R.play([{ kind: 'idle', duration: 1.2 }], { rot: a.rot }, { source: a.source });
    const summary = () => ({
      gest: R.evs('gesture').map((e) => e.type).join(','),
      swing: R.evs('swing').map((e) => e.kind + '/' + e.src).join(','),
      hits: R.evs('hit').map((e) => e.target + ':' + (e.kind || '')).join(','),
    });
    // a) plain thrust
    foe();
    R.clearLog();
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }], { rot: a.rot }, { source: a.source });
    out.thrust = summary();
    // b) real touch on the glass → shield → thrust → bash
    foe();
    R.frames(60);
    R.clearLog();
    const el = R.touch('pointerdown', 480, 300, 11);
    R.frames(20);
    out.touchEl = el;
    out.shieldHeld = ctx.input.shieldHeld;
    out.raise = +ctx.player.shieldRaise.toFixed(2);
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }], { rot: a.rot }, { source: a.source });
    out.bash = summary();
    R.touch('pointerup', 480, 300, 11);
    R.frames(30);
    out.shieldAfterUp = ctx.input.shieldHeld;
    // c) spear thrust
    foe();
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    R.frames(60);
    R.clearLog();
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.6 }], { rot: a.rot }, { source: a.source });
    out.spearThrust = summary();
    out.weapon = ctx.player.weapon;
    // d) three thrusts in a row (training step pattern)
    g.action({ type: 'switchWeapon', weapon: 'sword' });
    R.frames(60);
    foe();
    R.clearLog();
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.4 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.4 }, { kind: 'thrust' }, { kind: 'idle', duration: 0.5 }], { rot: a.rot }, { source: a.source });
    out.three = summary();
    return out;
  }, { source, rot });
  console.log(JSON.stringify(r, null, 1));
  const errs = await G.errors();
  if (errs.length) console.log('ERRORS', errs.slice(0, 5));
} finally {
  await G.close();
}
