// Agoge step XI ('The dory') in the real game: after the two spear thrusts the near dummy steps
// aside (it hid the far target and stole the throw lock), a throw straight ahead hits the far
// target and completes the step, and the dummy is back in place for step XII. An early throw
// (before the thrusts) also goes to the far target: the near dummy carries noThrowLock.
// Usage: node spartan/tests/e2e/combat-agoge-dory.mjs
import { launch } from './lib.mjs';

const l = await launch();
let failed = 0;
const check = (name, cond, info = '') => {
  if (!cond) failed++;
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${name}${info ? `  ${info}` : ''}`);
};
try {
  const page = await l.newPage();
  await page.goto(`${l.game}?manual=1&seed=3&quality=low`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  for (const early of [false, true]) {
    const r = await page.evaluate((early) => {
      const g = window.__game;
      const ctx = g.ctx;
      ctx.renderer.render = () => {};
      ctx.settings.control = 'touch';
      g.startTraining();
      g.step(10);
      const tr = ctx.training;
      for (let i = 0; i < 20 && tr.stepIndex < 10; i++) {
        document.querySelector('#training [data-act="skip"]').click();
        g.step(2);
      }
      const dummies = () => ctx.actors.filter((a) => a.role === 'dummy');
      const near = () => dummies().find((a) => Math.abs(a.pos.z + 2.6) < 0.01);
      const far = () => dummies().find((a) => Math.abs(a.pos.z + 18) < 0.01);
      const at = (a) => (a ? [+a.pos.x.toFixed(2), +a.pos.z.toFixed(2), !!a.noThrowLock] : null);
      const out = { step: tr.stepIndex, entry: at(near()) };
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(45);
      if (!early) {
        for (let k = 0; k < 2; k++) {
          g.action({ type: 'thrust', power: 0.6 });
          g.step(30);
        }
        out.afterThrusts = at(near());
        g.step(30);
        out.aside = at(near());
        out.hint = document.querySelector('#training .tr-hint').textContent;
      }
      const impacts = [];
      const off = ctx.events.on('spearImpact', (e) => impacts.push(e.target === far() ? 'far' : e.target === near() ? 'near' : 'ground'));
      ctx.player.yaw = 0;
      g.action({ type: 'throw', power: 0.6 });
      for (let i = 0; i < 180 && !impacts.length; i++) g.step(1);
      off();
      out.impacts = impacts;
      out.done = document.querySelector('#training').classList.contains('is-done');
      for (let i = 0; i < 180 && tr.stepIndex === 10; i++) g.step(1);
      g.step(30);
      out.next = tr.stepIndex;
      out.back = at(near());
      out.farAt = at(far());
      out.errors = g.errors.slice();
      return out;
    }, early);
    const tag = early ? 'early throw' : 'thrust, thrust, throw';
    check(`${tag}: reached step XI with the near dummy in place and flagged`, r.step === 10 && JSON.stringify(r.entry) === '[0,-2.6,true]', JSON.stringify(r.entry));
    if (!early) {
      check(`${tag}: after the thrusts the near dummy steps aside to x = -1.4`, r.aside && r.aside[0] === -1.4 && r.afterThrusts[0] < 0, JSON.stringify([r.afterThrusts, r.aside, r.hint]));
    }
    check(`${tag}: a throw straight ahead hits the far target`, r.impacts.join() === 'far', JSON.stringify(r.impacts));
    check(`${tag}: the step completes`, r.done && r.next === 11, `done=${r.done} next=${r.next}`);
    check(`${tag}: the near dummy is back at (0, -2.6), unflagged; far target unmoved`, JSON.stringify(r.back) === '[0,-2.6,false]' && JSON.stringify(r.farAt) === '[1.5,-18,false]', JSON.stringify([r.back, r.farAt]));
    check(`${tag}: no page errors`, r.errors.length === 0 && page.errors.length === 0, [...r.errors, ...page.errors].join(' | '));
  }
} finally {
  await l.close();
}
console.log(failed ? `${failed} check(s) failed` : 'all checks passed');
process.exit(failed ? 1 : 0);
