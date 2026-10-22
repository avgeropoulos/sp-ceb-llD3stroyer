// Art review: Day 1 dusk (the Immortals' entry) and Day 3 dawn sky/land balance, shipped vs tuned
// presets, mutated at runtime through the exported PRESETS (gfx/world/sky.js); no source is edited.
// For each shot it prints mean luma and HSV saturation of the upper sky, the horizon band, the lit
// cliff and the ground, read back from the canvas.
// Usage: node spartan/tests/e2e/review-art-sky.mjs <outDir> [WxH=915x412]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const VARIANTS = {
  dusk: [
    ['shipped', {}],
    // Lower exposure so the sky stops clipping to white; keep the sun strong for the gold rim light.
    ['exp1.3', { exposure: 1.3 }],
    ['exp1.3-rayleigh3.4', { exposure: 1.3, rayleigh: 3.4, turbidity: 6 }],
  ],
  dawn: [
    ['shipped', {}],
    // Brighter sky (tint multiplies sky radiance and the sky-matched fog) at the shipped exposure.
    ['tint1.9', { tint: [1.3 * 1.9, 0.88 * 1.9, 1.02 * 1.9] }],
    ['tint1.9-exp2.0', { tint: [1.3 * 1.9, 0.88 * 1.9, 1.02 * 1.9], exposure: 2.0 }],
  ],
};
try {
  const page = await l.newPage({ width, height });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  const skyUrl = await ev(() => new URL('js/gfx/world/sky.js', location.href).href);
  const orig = await ev(async (u) => {
    const { PRESETS } = await import(u);
    return JSON.parse(JSON.stringify({ dusk: PRESETS.dusk, dawn: PRESETS.dawn }));
  }, skyUrl);
  for (const [tod, list] of Object.entries(VARIANTS)) {
    for (const [label, patch] of list) {
      await ev(
        async ([u, tod, base, patch]) => {
          const { PRESETS } = await import(u);
          Object.assign(PRESETS[tod], base, patch);
          const g = window.__game;
          g.startBattle(tod === 'dawn' ? 3 : 1);
          g.ctx.waves.stop();
          g.ctx.ai.clear();
          g.ctx.combat.reset();
          g.ctx.world.setTimeOfDay(tod);
          g.ctx.ai.ensureAllies();
          g.ctx.hud.reset();
          g.spawn('immortal', 0.5, -7);
          g.spawn('levy', -2, -10);
          g.spawn('sparabara', 2, -12);
          g.step(40);
        },
        [skyUrl, tod, orig[tod], patch],
      );
      const m = await ev(() => {
        const c = document.getElementById('scene');
        const t = document.createElement('canvas');
        t.width = c.width;
        t.height = c.height;
        const g2 = t.getContext('2d');
        g2.drawImage(c, 0, 0);
        const reg = (x0, y0, x1, y1) => {
          const d = g2.getImageData(Math.round(x0 * t.width), Math.round(y0 * t.height), Math.round((x1 - x0) * t.width), Math.round((y1 - y0) * t.height)).data;
          let L = 0;
          let S = 0;
          let r = 0;
          let gg = 0;
          let b = 0;
          const n = d.length / 4;
          for (let i = 0; i < d.length; i += 4) {
            const mx = Math.max(d[i], d[i + 1], d[i + 2]);
            const mn = Math.min(d[i], d[i + 1], d[i + 2]);
            L += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
            S += mx ? (mx - mn) / mx : 0;
            r += d[i];
            gg += d[i + 1];
            b += d[i + 2];
          }
          return { luma: Math.round(L / n), sat: +(S / n).toFixed(2), rgb: [Math.round(r / n), Math.round(gg / n), Math.round(b / n)] };
        };
        return {
          skyUpper: reg(0.5, 0.1, 0.78, 0.3),
          skyHorizon: reg(0.55, 0.38, 0.9, 0.46),
          cliffLit: reg(0.07, 0.15, 0.33, 0.48),
          ground: reg(0.16, 0.63, 0.46, 0.8),
        };
      });
      await page.waitForTimeout(600);
      const file = path.join(outDir, `sky-${tod}-${label}.png`);
      await page.screenshot({ path: file, timeout: 300000 });
      console.log(file, JSON.stringify({ patch, ...m }));
    }
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
