// Art/readability review: what the player sees when they fall. Kills the player 1 s into Day 1,
// then at 1, 3, 6 and 10 s of game time after death screenshots the screen and dumps which menu
// screen is on (opacity/visibility) and whether the day-intro banner is still shown.
// Usage: node spartan/tests/e2e/review-art-gameover.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  await ev(() => {
    const g = window.__game;
    g.startBattle(1);
    g.step(60);
    g.ctx.player.damage(1000, { from: null, kind: 'melee' });
  });
  const dump = () =>
    ev(() => {
      const on = [...document.querySelectorAll('#menus .mn-screen')].map((s) => {
        const cs = getComputedStyle(s);
        return { cls: s.className, on: s.classList.contains('is-on'), opacity: cs.opacity, vis: cs.visibility };
      }).filter((s) => s.on || s.opacity !== '0');
      const banner = document.querySelector('.hud-banner');
      const bcs = banner && getComputedStyle(banner);
      return {
        mode: window.__game.ctx.mode,
        gnow: +window.__game.ctx.time.gnow.toFixed(2),
        menusOpen: document.querySelector('#menus').classList.contains('is-open'),
        screensOn: on,
        banner: banner ? { cls: banner.className, opacity: bcs.opacity, text: banner.textContent.trim().slice(0, 60) } : null,
        errors: window.__game.errors.slice(0, 3),
      };
    });
  if (process.env.BURST === '1') {
    // Same as review-art-shots.mjs 'gameover': 20 back-to-back batches of 15 frames at 1/30 s.
    for (let i = 0; i < 20; i++) await ev(() => window.__game.step(15, 1 / 30));
    await page.waitForTimeout(900);
    await page.screenshot({ path: path.join(outDir, 'gameover-burst.png'), timeout: 300000 });
    console.log(path.join(outDir, 'gameover-burst.png'), JSON.stringify(await dump()));
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(outDir, 'gameover-burst-wait3s.png'), timeout: 300000 });
    console.log(path.join(outDir, 'gameover-burst-wait3s.png'), JSON.stringify(await dump()));
  }
  let t = 0;
  for (const target of process.env.BURST === '1' ? [] : [1, 3, 6, 10]) {
    await ev((n) => window.__game.step(n, 1 / 30), Math.round((target - t) * 30));
    t = target;
    await page.waitForTimeout(900);
    const file = path.join(outDir, `gameover-${target}s.png`);
    await page.screenshot({ path: file, timeout: 300000 });
    console.log(file, JSON.stringify(await dump()));
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
