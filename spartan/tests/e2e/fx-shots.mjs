// Screenshots of every fx effect in tests/sandbox/fx-sandbox.html, plus the spear cam at launch,
// mid-flight, impact orbit and ease-back (touch and motion throws).
// Usage: node spartan/tests/e2e/fx-shots.mjs <outDir> [name ...]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', ...wanted] = process.argv;
fs.mkdirSync(outDir, { recursive: true });

// [file, query, frames] for the fixed scenes.
const FIXED = [
  ['blood', 'scene=blood', 9],
  ['blood-late', 'scene=blood', 40],
  ['sparks', 'scene=sparks', 4],
  ['dust', 'scene=dust', 25],
  ['splinters', 'scene=splinters', 14],
  ['splinters-ground', 'scene=splinters&pitch=-0.42&fov=40', 120],
  ['splash', 'scene=splash', 30],
  ['decals', 'scene=decals', 300],
  ['all', 'scene=all', 6],
];
// [file prefix, query, [[label, phase, frames into that phase]]]
const SPEAR = [
  ['spear-touch', 'scene=spear', [['launch', 'trail', 3], ['mid', 'trail', 30], ['orbit', 'impact', 30], ['ease', 'ease', 10]]],
  ['spear-motion', 'scene=spear&motion=1', [['hold', 'trail', 20], ['mid', 'trail', 60], ['orbit', 'impact', 45], ['ease', 'ease', 12]]],
];

const l = await launch();
let bad = 0;
async function open(query) {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.base}/tests/sandbox/fx-sandbox.html?${query}&manual=1`);
  await page.waitForFunction(() => window.__fx, null, { timeout: 120000 });
  return page;
}
async function done(page) {
  const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
  if (errs.length) {
    bad++;
    console.error(errs.join('\n'));
  }
  await page.close();
}
const want = (name) => !wanted.length || wanted.some((w) => name.startsWith(w));
try {
  for (const [name, query, frames] of FIXED) {
    if (!want(name)) continue;
    const page = await open(query);
    const info = await page.evaluate((n) => {
      window.__fx.step(n);
      const s = window.__fx.state();
      return `particles ${s.particles}, decals ${s.decals}, calls ${window.__fx.ctx.renderer.info.render.calls}`;
    }, frames);
    const file = path.join(outDir, `${name}-${frames}.png`);
    await page.screenshot({ path: file, timeout: 180000 });
    console.log(`${file}: ${info}`);
    await done(page);
  }
  for (const [prefix, query, marks] of SPEAR) {
    if (!want(prefix)) continue;
    const page = await open(query);
    for (const [label, phase, n] of marks) {
      const info = await page.evaluate(({ phase, n }) => {
        const f = window.__fx;
        let seen = 0;
        for (let i = 0; i < 900; i++) {
          f.frame(1 / 60);
          if (f.fx.spearCamPhase === phase && ++seen >= n) break;
        }
        f.step(0);
        const s = f.state();
        return `phase ${s.phase}, scale ${s.scale.toFixed(3)}, hitStop ${s.hitStop.toFixed(3)}, particles ${s.particles}`;
      }, { phase, n });
      const file = path.join(outDir, `${prefix}-${label}.png`);
      await page.screenshot({ path: file, timeout: 180000 });
      console.log(`${file}: ${info}`);
    }
    await done(page);
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
