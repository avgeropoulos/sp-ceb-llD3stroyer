// Art review: how much of a 915x412 frame the first-person dory hides, at idle and mid-thrust,
// shipped vs a proposed overhand pose (fist raised, shaft angled down at the target so the rear half
// rises out of the top of the frame instead of running past the cheek as a plank).
// Renders each frame with the vmSpear mesh shown and hidden and counts changed pixels; also reports
// the apparent width in px of the shaft at the right screen edge (column x = W-40).
// Source rewritten in flight with page.route (no game file edited).
// Usage: node spartan/tests/e2e/review-art-spearcover.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const AFTER = {
  'gfx/viewmodel.js': [
    [
      'spearLow: spearAim([0.28, 0.06, -0.12], [0.0, -0.42, -2.4], [0.3, -1, 0.3]),',
      'spearLow: spearAim([0.24, 0.1, -0.3], [0.0, -0.55, -2.4], [0.3, -1, 0.3]),',
    ],
    [
      'spearHigh: spearAim([0.28, 0.075, -0.15], [0.0, -0.36, -2.4], [0.3, -1, 0.3]),',
      'spearHigh: spearAim([0.24, 0.115, -0.32], [0.0, -0.5, -2.4], [0.3, -1, 0.3]),',
    ],
    [
      'spearOut: spearAim([0.15, -0.06, -0.56], [-0.02, -0.18, -2.8], [0.3, -1, 0.3]),',
      'spearOut: spearAim([0.14, 0.06, -0.6], [-0.02, -0.42, -2.8], [0.3, -1, 0.3]),',
    ],
  ],
};
async function newGame(rewrites) {
  const page = await l.newPage({ width, height });
  if (rewrites) {
    for (const [suffix, pairs] of Object.entries(rewrites)) {
      await page.route(`**/js/${suffix}`, async (route) => {
        const resp = await route.fetch();
        let body = await resp.text();
        for (const [a, b] of pairs) {
          if (!body.includes(a)) throw new Error(`rewrite target not found in ${suffix}: ${a}`);
          body = body.replace(a, b);
        }
        await route.fulfill({ response: resp, body });
      });
    }
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 240000 });
  return page;
}
const grab = (page) =>
  page.evaluate(() => {
    const c = document.getElementById('scene');
    const t = document.createElement('canvas');
    t.width = c.width;
    t.height = c.height;
    const g2 = t.getContext('2d');
    g2.drawImage(c, 0, 0);
    return Array.from(g2.getImageData(0, 0, t.width, t.height).data);
  });
async function cover(page) {
  const full = await grab(page);
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.vm.scene.getObjectByName('vmSpear').visible = false;
    g.render();
  });
  const bare = await grab(page);
  await page.evaluate(() => {
    const g = window.__game;
    g.ctx.vm.scene.getObjectByName('vmSpear').visible = true;
    g.render();
  });
  let n = 0;
  let colRun = 0;
  const X = width - 40;
  for (let i = 0, p = 0; i < full.length; i += 4, p++) {
    const d = Math.max(Math.abs(full[i] - bare[i]), Math.abs(full[i + 1] - bare[i + 1]), Math.abs(full[i + 2] - bare[i + 2]));
    if (d > 24) {
      n++;
      if (p % width === X) colRun++;
    }
  }
  return { spearCoverPct: +((100 * n) / (full.length / 4)).toFixed(2), shaftPxAtRightEdge: colRun };
}
try {
  for (const variant of ['before', 'after']) {
    const page = await newGame(variant === 'after' ? AFTER : null);
    await page.evaluate(() => {
      const g = window.__game;
      g.startBattle(1);
      g.ctx.waves.stop();
      g.ctx.ai.clear();
      g.ctx.combat.reset();
      g.ctx.world.setTimeOfDay('morning');
      g.ctx.hud.reset();
      g.action({ type: 'switchWeapon', weapon: 'spear' });
      g.step(60);
      g.spawn('levy', 0.0, -2.4, { variant: 'kissian' });
      g.step(30);
    });
    const idle = await cover(page);
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(outDir, `spearcover-idle-${variant}.png`) });
    console.log(path.join(outDir, `spearcover-idle-${variant}.png`), JSON.stringify(idle));
    await page.evaluate(() => {
      const g = window.__game;
      g.action({ type: 'thrust', power: 0.9 });
      g.step(8);
    });
    const thrust = await cover(page);
    await page.waitForTimeout(600);
    await page.screenshot({ path: path.join(outDir, `spearcover-thrust-${variant}.png`) });
    console.log(path.join(outDir, `spearcover-thrust-${variant}.png`), JSON.stringify(thrust));
    const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
    if (errs.length) console.error(errs.slice(0, 5).join('\n'));
    await page.context().close();
  }
} finally {
  await l.close();
}
