// Agoge step XI: can a motion player hit the far target? Try aiming the view at it with a gyro turn first.
import { openGame } from './review-motion-lib.mjs';
const G = await openGame({ source: 'inject' });
for (const turn of [0, -0.05, -0.083, -0.12, -0.2]) {
  const r = await G.ev((R, g, ctx, turn) => {
    ctx.settings.control = 'motion';
    g.startTraining();
    R.frames(10);
    const tr = ctx.training;
    // jump to the spear step via Skip
    let guard = 0;
    while (tr.stepIndex < 10 && guard++ < 20) { document.querySelector('#training [data-act="skip"]').click(); R.frames(2); }
    R.play([{ kind: 'idle', duration: 0.8 }], { rot: 90 });
    g.action({ type: 'switchWeapon', weapon: 'spear' });
    R.frames(50);
    if (turn) R.play([{ kind: 'turn', yaw: turn, duration: 1.0 }, { kind: 'idle', duration: 0.8 }], { rot: 90 });
    const yaw = ctx.player.yaw;
    R.clearLog();
    const which = [];
    const off = ctx.events.on('spearImpact', (e) => which.push(e.target && e.target.pos ? `${e.target.role}@z=${e.target.pos.z.toFixed(1)}` : 'none'));
    R.play([{ kind: 'idle', duration: 0.3 }, { kind: 'throw' }, { kind: 'idle', duration: 3.5 }], { rot: 90 });
    off();
    const imp = which.join(',');
    return { step: tr.stepIndex, yaw: +yaw.toFixed(3), imp, hint: document.querySelector('#training .tr-hint').textContent, done: document.querySelector('#training').classList.contains('is-done') };
  }, turn);
  console.log(`step after ${r.step}; view turned ${turn} rad (yaw ${r.yaw}): impacts [${r.imp}] done=${r.done} hint="${r.hint}"`);
}
await G.close();
