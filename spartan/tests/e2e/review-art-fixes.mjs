// Art-direction review: before/after screenshots of proposed visual fixes, applied at RUNTIME only
// (module exports mutated from the page; no game source file is edited).
//   guard   levy/immortal overhand guard: elbow dropped (no horizontal "sausage" arm)
//   dawn    Day 3 dawn preset: brighter western sky, lower exposure (sky/land coherence)
//   phalanx the two neighbours 1.0 m to each side and 0.55 m forward, so the line shows at the frame edges
// Usage: node spartan/tests/e2e/review-art-fixes.mjs <outDir> [WxH=915x412] [guard|dawn|phalanx ...]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outDir = '.', size = '915x412', ...wanted] = process.argv;
const [width, height] = size.split('x').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const l = await launch();
const page = await l.newPage({ width, height });
const ev = (fn, arg) => page.evaluate(fn, arg);
const lum = (name) =>
  ev(async () => {
    // Mean luminance of regions read back from the preserved canvas.
    const c = document.getElementById('scene');
    const w = c.width;
    const h = c.height;
    const tmp = document.createElement('canvas');
    tmp.width = w;
    tmp.height = h;
    const g2 = tmp.getContext('2d');
    g2.drawImage(c, 0, 0);
    const reg = (x0, y0, x1, y1) => {
      const d = g2.getImageData(Math.round(x0 * w), Math.round(y0 * h), Math.round((x1 - x0) * w), Math.round((y1 - y0) * h)).data;
      let s = 0;
      for (let i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
      return +(s / (d.length / 4)).toFixed(1);
    };
    return { sky: reg(0.62, 0.15, 0.92, 0.42), cliff: reg(0.02, 0.2, 0.3, 0.48), ground: reg(0.16, 0.63, 0.46, 0.8), sea: reg(0.76, 0.52, 0.96, 0.62) };
  });
async function shot(name, extra = {}) {
  await page.waitForTimeout(700);
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, timeout: 300000 });
  console.log(`${file} ${JSON.stringify({ ...extra, lum: await lum() })}`);
}
const sandbox = (tod = 'morning') =>
  ev((tod) => {
    const g = window.__game;
    g.startBattle(1);
    g.ctx.waves.stop();
    g.ctx.ai.clear();
    g.ctx.combat.reset();
    g.ctx.world.setTimeOfDay(tod);
    g.ctx.hud.reset();
  }, tod);
const mod = (rel) => `new URL('js/${rel}', location.href).href`;

const runs = {
  async guard() {
    for (const variant of ['before', 'after']) {
      if (variant === 'after') {
        await ev(async (u) => {
          const { STYLES, CH } = await import(u);
          for (const st of [STYLES.levy, STYLES.sparabara]) {
            st.guard.set([-0.24, 0.24, 0.12], CH.handR); // fist by the ear, a little forward (was y 0.33, z 0.06)
            st.guard.set([-0.6, -0.65, -0.35], CH.elbR); // elbow pole down and out (was [-1, 0.1, -0.5]: straight out sideways)
          }
        }, await ev(new Function(`return ${mod('gfx/characters/poses.js')}`)));
      }
      await sandbox('morning');
      await ev(() => {
        const g = window.__game;
        g.spawn('levy', -1.0, -2.0, { variant: 'kissian' });
        g.spawn('levy', 0.9, -2.3, { variant: 'mede' });
        g.spawn('immortal', 0.0, -4.5);
        g.step(50);
      });
      await shot(`guard-${variant}`);
    }
  },
  async dawn() {
    for (const variant of ['before', 'after']) {
      if (variant === 'after') {
        await ev(async (u) => {
          const { PRESETS } = await import(u);
          Object.assign(PRESETS.dawn, { exposure: 1.7, sun: 2.1, hemi: 0.42, rayleigh: 3.0, turbidity: 7.0 });
        }, await ev(new Function(`return ${mod('gfx/world/sky.js')}`)));
      }
      await sandbox('dawn');
      await ev(() => {
        const g = window.__game;
        g.ctx.ai.ensureAllies();
        g.spawn('immortal', 0.5, -7);
        g.spawn('levy', -2, -10);
        g.step(40);
      });
      await shot(`dawn-${variant}`);
    }
  },
  async dawn2() {
    // Brighten only the sky (the tint multiplies sky radiance and the sky-matched fog), keep the land.
    await ev(async (u) => {
      const { PRESETS } = await import(u);
      PRESETS.dawn.tint = [1.3 * 1.9, 0.88 * 1.9, 1.02 * 1.9];
    }, await ev(new Function(`return ${mod('gfx/world/sky.js')}`)));
    await sandbox('dawn');
    await ev(() => {
      const g = window.__game;
      g.ctx.ai.ensureAllies();
      g.spawn('immortal', 0.5, -7);
      g.spawn('levy', -2, -10);
      g.step(40);
    });
    await shot('dawn-after-tint1.9');
  },
  async phalanx() {
    for (const variant of ['before', 'after']) {
      await sandbox('morning');
      await ev((variant) => {
        const g = window.__game;
        const ai = g.ctx.ai;
        if (variant === 'before') ai.ensureAllies();
        else {
          for (const x of [-3.0, -2.0, -1.0, 1.0, 2.0, 3.0]) ai.spawnAlly(x);
          for (const a of g.ctx.actors) if (a.role === 'ally') {
            a.homeZ = Math.abs(a.homeX) < 1.5 ? -0.55 : -0.35;
            a.pos.z = a.homeZ;
          }
        }
        g.spawn('immortal', 0.2, -3.2);
        g.spawn('levy', -1.2, -3.0, { variant: 'mede' });
        g.spawn('levy', 1.4, -3.4, { variant: 'kissian' });
        g.step(60);
      }, variant);
      await shot(`phalanx-${variant}`);
    }
  },
};

let bad = 0;
try {
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 180000 });
  for (const name of wanted.length ? wanted : Object.keys(runs)) {
    try {
      await runs[name]();
    } catch (err) {
      bad++;
      console.error(`${name}: ${err && (err.stack || err)}`);
    }
  }
  const errs = page.errors.concat(await ev(() => window.__game.errors));
  if (errs.length) {
    bad++;
    console.error(errs.slice(0, 8).join('\n'));
  }
} finally {
  await l.close();
}
process.exitCode = bad ? 1 : 0;
