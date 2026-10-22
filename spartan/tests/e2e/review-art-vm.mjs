// Art review: first-person viewmodel fixes, before/after, applied at RUNTIME (module source rewritten
// in flight with page.route; no game file is edited).
//   spear  idle dory: the shipped overhand pose puts the fist off-screen (a shaft floats in from the
//          right edge); the proposal brings fist + forearm into the upper-right of the frame.
//          Prints the projected NDC of the right hand in the vm camera for each variant.
//   skin   forearm "orange-peel": arm normal map at shipped strength vs normalScale 0.35, at the
//          spear thrust (forearm fills the lower right).
// Usage: node spartan/tests/e2e/review-art-vm.mjs <outDir> [WxH=915x412] [spear|skin ...]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...wanted] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();

const SPEAR_REWRITE = {
  'gfx/viewmodel.js': [
    [
      'spearLow: spearAim([0.28, 0.06, -0.12], [0.0, -0.42, -2.4], [0.3, -1, 0.3]),',
      'spearLow: spearAim([0.26, -0.02, -0.3], [0.0, -0.42, -2.4], [0.3, -1, 0.3]),',
    ],
    [
      'spearHigh: spearAim([0.28, 0.075, -0.15], [0.0, -0.36, -2.4], [0.3, -1, 0.3]),',
      'spearHigh: spearAim([0.26, -0.005, -0.32], [0.0, -0.36, -2.4], [0.3, -1, 0.3]),',
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
const sandbox = (page) =>
  page.evaluate(() => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay('morning');
    g.ctx.hud.reset();
  });
async function shot(page, name, extra = {}) {
  await page.waitForTimeout(700);
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(`${file} ${JSON.stringify(extra)}`);
}
// Right hand (a bone in the vm rig) projected with the vm camera.
const handNdc = (page) =>
  page.evaluate(() => {
    const c = window.__game.ctx;
    const T = c.THREE;
    let best = null;
    c.vm.scene.traverse((o) => {
      if (o.isBone && /hand.?r|r.?hand|handR|wristR/i.test(o.name)) best = best || o;
    });
    const names = [];
    c.vm.scene.traverse((o) => {
      if (o.isBone) names.push(o.name);
    });
    // Bones are unnamed: the weapon mesh sits at the grip (hand) pose, so use it.
    if (!best) best = c.vm.scene.getObjectByName('vmSpear');
    const v = new T.Vector3();
    best.getWorldPosition(v);
    v.project(c.vm.camera);
    return { at: best.name || 'bone', ndcX: +v.x.toFixed(2), ndcY: +v.y.toFixed(2), onScreen: Math.abs(v.x) <= 1 && Math.abs(v.y) <= 1 };
  });

const runs = {
  async spear() {
    for (const variant of ['before', 'after']) {
      const page = await newGame(variant === 'after' ? SPEAR_REWRITE : null);
      await sandbox(page);
      await page.evaluate(() => {
        const g = window.__game;
        g.action({ type: 'switchWeapon', weapon: 'spear' });
        g.step(60);
        g.spawn('levy', 0.3, -3.0, { variant: 'mede' });
        g.step(30);
      });
      await shot(page, `spear-idle-${variant}`, await handNdc(page));
      await page.evaluate(() => {
        const g = window.__game;
        g.action({ type: 'thrust', power: 0.9 });
        g.step(8);
      });
      await shot(page, `spear-thrust-${variant}`, await handNdc(page));
      await page.context().close();
    }
  },
  async skin() {
    const page = await newGame();
    for (const variant of ['before', 'after']) {
      await sandbox(page);
      await page.evaluate((variant) => {
        const g = window.__game;
        g.ctx.vm.scene.traverse((o) => {
          if (o.isSkinnedMesh && o.material && o.material.normalMap) {
            o.material.normalScale.set(variant === 'after' ? 0.35 : 1, variant === 'after' ? 0.35 : 1);
          }
        });
        g.action({ type: 'switchWeapon', weapon: 'spear' });
        g.step(60);
        g.action({ type: 'thrust', power: 0.9 });
        g.step(8);
      }, variant);
      await shot(page, `skin-${variant}`);
    }
    await page.context().close();
  },
};

let bad = 0;
try {
  for (const name of wanted.length ? wanted : Object.keys(runs)) {
    try {
      await runs[name]();
    } catch (err) {
      bad++;
      console.error(`${name}: ${err && (err.stack || err)}`);
    }
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
