// Art review: spear-cam framing, shipped vs a side-offset trail camera, via an in-flight rewrite of
// gfx/fx.js computeTrail (page.route; no game file is edited). Shipped: the camera sits exactly
// behind the spear (+0.35 m up), so in flight the shaft is a vertical pole under the centre and at
// impact the target's own body hides the hit. Proposed: +0.55 m up and 0.45 m to the side of the
// flight line, so the shaft runs diagonally through the lower third and the impact is seen.
// Usage: node spartan/tests/e2e/review-art-spearcam.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const OLD = 'vA.copy(pr.pos).addScaledVector(velDir, -back).addScaledVector(UP, 0.35 * smoothstep(0, 0.25, tc.camT));';
const NEW =
  'vA.copy(pr.pos).addScaledVector(velDir, -back).addScaledVector(UP, 0.55 * smoothstep(0, 0.25, tc.camT));' +
  ' vB.crossVectors(velDir, UP); if (vB.lengthSq() > 1e-6) vA.addScaledVector(vB.normalize(), 0.45 * smoothstep(0, 0.25, tc.camT));';
try {
  for (const variant of ['before', 'after']) {
    const page = await l.newPage({ width, height });
    if (variant === 'after') {
      await page.route('**/js/gfx/fx.js', async (route) => {
        const resp = await route.fetch();
        const body = await resp.text();
        if (!body.includes(OLD)) throw new Error('rewrite target not found in fx.js');
        await route.fulfill({ response: resp, body: body.replace(OLD, NEW) });
      });
    }
    await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
    await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
    const ev = (fn, a) => page.evaluate(fn, a);
    await ev(() => {
      const g = window.__game;
      g.startBattle(1);
      g.ctx.waves.stop();
      g.ctx.ai.clear();
      g.ctx.combat.reset();
      g.ctx.world.setTimeOfDay('morning');
      g.ctx.hud.reset();
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(60);
      g.spawn('immortal', 0.4, -16);
      g.step(20);
      g.ctx.__camLog = [];
      g.ctx.events.on('spearCam', (e) => g.ctx.__camLog.push(e.phase));
      g.action({ type: 'throw', power: 0.85 });
      g.step(26);
    });
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(outDir, `spearcam-flight-${variant}.png`), timeout: 300000 });
    for (let i = 0; i < 40; i++) {
      const ph = await ev(() => window.__game.ctx.__camLog.slice(-1)[0]);
      if (ph === 'impact' || ph === 'end') break;
      await ev(() => window.__game.step(3));
    }
    await ev(() => window.__game.step(4));
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(outDir, `spearcam-impact-${variant}.png`), timeout: 300000 });
    console.log(variant, JSON.stringify(await ev(() => ({ log: window.__game.ctx.__camLog, errors: window.__game.errors.slice(0, 3) }))));
    await page.context().close();
  }
} finally {
  await l.close();
}
