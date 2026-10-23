// Scene fixer check: sky/land balance for every time of day with the shipped presets (or with
// a JSON patch per time of day, applied through the exported PRESETS of gfx/world/sky.js). For
// each shot it prints mean luma and HSV saturation of the upper sky, the horizon band, the lit
// cliff and the ground, read back from the canvas, plus the upper-sky/cliff luma ratio
// (target about 0.5–0.8: a sky that neither blows out nor reads as night over a lit cliff).
// Usage: node spartan/tests/e2e/scene-sky.mjs <outDir> [WxH=915x412] [todList=dawn,morning,noon,afternoon,dusk]
//   [patchJSON, e.g. '{"dusk":[{"exposure":1.3},{"exposure":1.1}]}']
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', todArg = 'dawn,morning,noon,afternoon,dusk', patchArg = '{}'] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const PATCH = JSON.parse(patchArg);
const DAY = { dawn: 3, morning: 1, noon: 2, afternoon: 2, dusk: 1 };
// A patch per time of day is an object, or an array of objects to compare several variants.
const VARIANTS = Object.fromEntries(
  todArg.split(',').map((t) => {
    const p = PATCH[t];
    if (Array.isArray(p)) return [t, p.map((v, i) => [`v${i}`, v])];
    return [t, [[p ? 'patched' : 'shipped', p || {}]]];
  }),
);
try {
  const page = await l.newPage({ width, height });
  const ev = (fn, arg) => page.evaluate(fn, arg);
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 240000 });
  const skyUrl = await ev(() => new URL('js/gfx/world/sky.js', location.href).href);
  const orig = await ev(async (u) => {
    const { PRESETS } = await import(u);
    return JSON.parse(JSON.stringify(PRESETS));
  }, skyUrl);
  for (const [tod, list] of Object.entries(VARIANTS)) {
    for (const [label, patch] of list) {
      await ev(
        async ([u, tod, base, patch, day]) => {
          const { PRESETS } = await import(u);
          Object.assign(PRESETS[tod], base, patch);
          const g = window.__game;
          g.startBattle(day);
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
        [skyUrl, tod, orig[tod], patch, DAY[tod] || 1],
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
      console.log(file, JSON.stringify({ patch, ratioSkyCliff: +(m.skyUpper.luma / m.cliffLit.luma).toFixed(2), ...m }));
    }
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) console.error(errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
