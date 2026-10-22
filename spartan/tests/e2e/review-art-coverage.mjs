// Art/readability review: how much of a phone screen the first-person viewmodel and the helmet
// overlay hide, per situation. Renders the same frame with the viewmodel shown and hidden (and with
// the helmet view on and off) and counts pixels whose colour changes by more than 24/255.
// Usage: node spartan/tests/e2e/review-art-coverage.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const page = await l.newPage({ width, height });
const ev = (fn, arg) => page.evaluate(fn, arg);
try {
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium&nohud=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
  const grab = () =>
    ev(() => {
      const c = document.getElementById('scene');
      const t = document.createElement('canvas');
      t.width = c.width;
      t.height = c.height;
      const g2 = t.getContext('2d');
      g2.drawImage(c, 0, 0);
      return Array.from(g2.getImageData(0, 0, t.width, t.height).data);
    });
  const diff = (a, b) => {
    let n = 0;
    let lower = 0;
    let right = 0;
    const W = width;
    for (let i = 0, p = 0; i < a.length; i += 4, p++) {
      const d = Math.max(Math.abs(a[i] - b[i]), Math.abs(a[i + 1] - b[i + 1]), Math.abs(a[i + 2] - b[i + 2]));
      if (d > 24) {
        n++;
        const x = p % W;
        const y = (p / W) | 0;
        if (y > height * 0.5) lower++;
        if (x > W * 0.5) right++;
      }
    }
    const tot = a.length / 4;
    return { pct: +((100 * n) / tot).toFixed(1), lowerHalfPct: +((100 * lower) / tot).toFixed(1), rightHalfPct: +((100 * right) / tot).toFixed(1) };
  };
  const cases = {
    swordIdle: () => {},
    shieldRaised: () => {
      const p = window.__game.ctx.player;
      window.__game.ctx.input.shieldHeld = true;
    },
    spearIdle: () => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }),
    spearThrust: 'thrust',
  };
  const out = {};
  for (const [name, fn] of Object.entries(cases)) {
    await ev(() => {
      const g = window.__game;
      g.startBattle(1);
      g.ctx.waves.stop();
      g.ctx.ai.clear();
      g.ctx.combat.reset();
      g.ctx.world.setTimeOfDay('morning');
      g.ctx.settings.helmetView = true;
    });
    if (name === 'spearThrust') {
      await ev(() => window.__game.action({ type: 'switchWeapon', weapon: 'spear' }));
      await ev(() => window.__game.step(60));
      await ev(() => window.__game.action({ type: 'thrust', power: 0.9 }));
      await ev(() => window.__game.step(8));
    } else if (name === 'shieldRaised') {
      await page.keyboard.down('KeyK');
      await ev(() => window.__game.step(30));
    } else {
      if (typeof fn === 'function') await ev(fn);
      await ev(() => window.__game.step(60));
    }
    const full = await grab();
    // Same frame, viewmodel hidden (the overlay quad lives in the vm scene, so hide only the rig).
    await ev(() => {
      const g = window.__game;
      const rig = g.ctx.vm.scene.getObjectByName('vmRig');
      rig.visible = false;
      g.render();
    });
    const noVm = await grab();
    await ev(() => {
      const g = window.__game;
      g.ctx.vm.scene.getObjectByName('vmOverlay').visible = false;
      g.render();
    });
    const bare = await grab();
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(outDir, `cover-${name}-bare.png`) });
    await ev(() => {
      const g = window.__game;
      g.ctx.vm.scene.getObjectByName('vmRig').visible = true;
      g.ctx.vm.scene.getObjectByName('vmOverlay').visible = true;
      g.render();
    });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(outDir, `cover-${name}-full.png`) });
    out[name] = { viewmodel: diff(noVm, full), helmetOverlay: diff(bare, noVm), both: diff(bare, full) };
    if (name === 'shieldRaised') await page.keyboard.up('KeyK');
    console.log(name, JSON.stringify(out[name]));
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
