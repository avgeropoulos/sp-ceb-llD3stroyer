// Viewmodel screenshots: node spartan/tests/e2e/viewmodel-shots.mjs [outDir] [filter] [sizes] [query]
//   outDir defaults to ./viewmodel-shots; filter is a comma-separated list of substrings of shot names;
//   sizes like "844x390,960x540"; query is extra sandbox URL params (e.g. "&quality=low").
// One page load per size: each shot resets the viewmodel, sets the scene up, settles the rig, triggers
// events, then advances exactly `at` seconds of pdt (in 1/60 steps) before capturing.
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outArg = 'viewmodel-shots', filter = '', sizesArg = '844x390,960x540', query = ''] = process.argv;
const outDir = path.resolve(outArg);
fs.mkdirSync(outDir, { recursive: true });
const sizes = sizesArg.split(',').map((s) => s.split('x').map(Number));
const filters = filter ? filter.split(',') : [];

const S = 0.1; // TIMING.slash.strike
const swing = (kind, extra = {}) => ['swing', Object.assign({ kind, dir: 0, power: 0.8, weapon: 'sword', src: 'test' }, extra)];

const dirs = { R: 0, UR: 45, U: 90, UL: 135, L: 180, DL: -135, D: -90, DR: -45 };
export const SHOTS = [
  { name: 'idle-sword', setup: { weapon: 'sword' } },
  { name: 'idle-spear', setup: { weapon: 'spear' } },
  { name: 'shield-raised', setup: { weapon: 'sword', raise: 1 } },
  { name: 'shield-raised-spear', setup: { weapon: 'spear', raise: 1 } },
  ...Object.entries(dirs).map(([k, deg]) => ({ name: `slash-${k}`, events: [swing('slash', { dir: deg * Math.PI / 180 })], at: S })),
  { name: 'slash-R-a', events: [swing('slash', { dir: 0 })], at: 0.06 },
  { name: 'slash-R-c', events: [swing('slash', { dir: 0 })], at: 0.14 },
  { name: 'slash-D-a', events: [swing('slash', { dir: -Math.PI / 2 })], at: 0.06 },
  { name: 'slash-D-c', events: [swing('slash', { dir: -Math.PI / 2 })], at: 0.14 },
  { name: 'slash-30deg', events: [swing('slash', { dir: 30 * Math.PI / 180 })], at: S },
  { name: 'slash-raised-L', setup: { raise: 1 }, events: [swing('slash', { dir: Math.PI })], at: S },
  { name: 'thrust', events: [swing('thrust')], at: 0.11 },
  { name: 'thrust-back', events: [swing('thrust')], at: 0.04 },
  { name: 'spearThrust', setup: { weapon: 'spear' }, events: [swing('spearThrust', { weapon: 'spear' })], at: 0.13 },
  { name: 'spearSlash-L', setup: { weapon: 'spear' }, events: [swing('slash', { dir: Math.PI, weapon: 'spear' })], at: S },
  { name: 'spearSlash-D', setup: { weapon: 'spear' }, events: [swing('slash', { dir: -Math.PI / 2, weapon: 'spear' })], at: S },
  { name: 'bash', setup: { weapon: 'sword', raise: 1 }, events: [swing('bash')], at: 0.09 },
  { name: 'throw-windup', setup: { weapon: 'spear' }, play: ['throwWindup'], at: 0.3 },
  { name: 'throw-release', setup: { weapon: 'spear' }, play: ['throwWindup'], settle2: 0.3, events: [swing('throw', { weapon: 'spear', src: 'motion' })], at: 0.035 },
  { name: 'throw-follow', setup: { weapon: 'spear' }, play: ['throwWindup'], settle2: 0.3, events: [swing('throw', { weapon: 'spear', src: 'motion' })], at: 0.16 },
  { name: 'throw-touch-windup', setup: { weapon: 'spear' }, events: [swing('throw', { weapon: 'spear', src: 'touch' })], at: 0.13 },
  { name: 'block-impact', setup: { raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'melee' }]], at: 0.02 },
  { name: 'block-perfect', setup: { raise: 0 }, events: [['block', { by: 'player', perfect: true, kind: 'melee' }]], at: 0.05 },
  { name: 'guard-break', setup: { raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'guardBreak' }]], at: 0.12 },
  { name: 'hurt', events: [['playerHurt', { damage: 16, hp: 60, relYaw: 0.6, kind: 'melee' }]], at: 0.03 },
  { name: 'arrows5-raised', setup: { raise: 1, arrows: 5 } },
  { name: 'arrows5-low', setup: { raise: 0, arrows: 5 } },
  { name: 'helmet', setup: { overlay: { helmet: true } } },
  { name: 'helmet-raised', setup: { raise: 1, overlay: { helmet: true } } },
  { name: 'vignette', setup: { overlay: { helmet: false, vignette: 0.85 } } },
  { name: 'vignette-helmet', setup: { overlay: { helmet: true, vignette: 0.6 } } },
  { name: 'hurtflash', setup: { overlay: { helmet: true, hurt: 0.7 } } },
  { name: 'draw-spear', setup: { weapon: 'sword' }, after: { weapon: 'spear' }, events: [['weaponSwitch', { weapon: 'spear' }]], at: 0.3 },
  { name: 'walk', setup: { walk: true }, settle: 50 },
];

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname);
if (isMain) {
  const l = await launch();
  let failed = 0;
  try {
    for (const [w, h] of sizes) {
      const page = await l.newPage({ width: w, height: h });
      await page.goto(`${l.base}/tests/sandbox/viewmodel-sandbox.html?manual=1${query}`);
      await page.waitForFunction(() => window.__vm, null, { timeout: 120000 });
      for (const shot of SHOTS) {
        if (filters.length && !filters.some((f) => shot.name.includes(f))) continue;
        await page.evaluate((s) => window.__vm.shot(s), shot);
        const file = path.join(outDir, `${shot.name}-${w}x${h}.png`);
        await page.screenshot({ path: file });
        const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors.splice(0)));
        if (errs.length) {
          failed++;
          console.error(shot.name, errs.join('\n'));
          page.errors.length = 0;
        }
        console.log('saved', file);
      }
      await page.close();
    }
  } finally {
    await l.close();
  }
  if (failed) process.exitCode = 1;
}
