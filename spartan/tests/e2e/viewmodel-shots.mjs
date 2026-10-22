// Viewmodel screenshots: node spartan/tests/e2e/viewmodel-shots.mjs [outDir] [filter] [sizes]
//   outDir defaults to ./viewmodel-shots; filter is a substring of the shot name; sizes like "844x390,960x540".
// Each shot loads the sandbox in manual mode, settles the rig, triggers events, then advances exactly
// `at` seconds of pdt before capturing.
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , outArg = 'viewmodel-shots', filter = '', sizesArg = '844x390,960x540'] = process.argv;
const outDir = path.resolve(outArg);
fs.mkdirSync(outDir, { recursive: true });
const sizes = sizesArg.split(',').map((s) => s.split('x').map(Number));

const S = 0.1; // TIMING.slash.strike

const dirs = { R: 0, UR: 45, U: 90, UL: 135, L: 180, DL: -135, D: -90, DR: -45 };
const shots = [
  { name: 'idle-sword', setup: { weapon: 'sword' } },
  { name: 'idle-spear', setup: { weapon: 'spear' } },
  { name: 'shield-raised', setup: { weapon: 'sword', raise: 1 } },
  { name: 'shield-raised-spear', setup: { weapon: 'spear', raise: 1 } },
  ...Object.entries(dirs).map(([k, deg]) => ({
    name: `slash-${k}`, setup: { weapon: 'sword' }, events: [['swing', { kind: 'slash', dir: deg * Math.PI / 180, power: 0.8, weapon: 'sword', src: 'test' }]], at: S,
  })),
  { name: 'slash-R-early', setup: { weapon: 'sword' }, events: [['swing', { kind: 'slash', dir: 0, power: 0.8, weapon: 'sword', src: 'test' }]], at: 0.075 },
  { name: 'slash-R-late', setup: { weapon: 'sword' }, events: [['swing', { kind: 'slash', dir: 0, power: 0.8, weapon: 'sword', src: 'test' }]], at: 0.135 },
  { name: 'thrust', setup: { weapon: 'sword' }, events: [['swing', { kind: 'thrust', power: 0.8, weapon: 'sword', src: 'test' }]], at: 0.11 },
  { name: 'spearThrust', setup: { weapon: 'spear' }, events: [['swing', { kind: 'spearThrust', power: 0.8, weapon: 'spear', src: 'test' }]], at: 0.13 },
  { name: 'spearSlash-L', setup: { weapon: 'spear' }, events: [['swing', { kind: 'slash', dir: Math.PI, power: 0.8, weapon: 'spear', src: 'test' }]], at: S },
  { name: 'bash', setup: { weapon: 'sword', raise: 1 }, events: [['swing', { kind: 'bash', power: 0.8, weapon: 'sword', src: 'test' }]], at: 0.09 },
  { name: 'throw-windup', setup: { weapon: 'spear' }, play: ['throwWindup'], at: 0.3 },
  { name: 'throw-release', setup: { weapon: 'spear' }, play: ['throwWindup'], settle2: 0.3, events: [['swing', { kind: 'throw', power: 0.8, weapon: 'spear', src: 'motion' }]], at: 0.035 },
  { name: 'throw-follow', setup: { weapon: 'spear' }, play: ['throwWindup'], settle2: 0.3, events: [['swing', { kind: 'throw', power: 0.8, weapon: 'spear', src: 'motion' }]], at: 0.16 },
  { name: 'block-impact', setup: { weapon: 'sword', raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'melee' }]], at: 0.02 },
  { name: 'guard-break', setup: { weapon: 'sword', raise: 1 }, events: [['block', { by: 'player', perfect: false, kind: 'guardBreak' }]], at: 0.12 },
  { name: 'hurt', setup: { weapon: 'sword' }, events: [['playerHurt', { damage: 16, hp: 60, relYaw: 0.6, kind: 'melee' }]], at: 0.03 },
  { name: 'arrows5-raised', setup: { weapon: 'sword', raise: 1, arrows: 5 } },
  { name: 'arrows5-low', setup: { weapon: 'sword', raise: 0, arrows: 5 } },
  { name: 'helmet', setup: { weapon: 'sword', overlay: { helmet: true } } },
  { name: 'vignette', setup: { weapon: 'sword', overlay: { helmet: false, vignette: 0.85 } } },
  { name: 'hurtflash', setup: { weapon: 'sword', overlay: { helmet: true, hurt: 0.7 } } },
  { name: 'draw-spear', setup: { weapon: 'sword' }, after: { weapon: 'spear' }, events: [['weaponSwitch', { weapon: 'spear' }]], at: 0.4 },
];

const l = await launch();
let failed = 0;
try {
  for (const [w, h] of sizes) {
    const page = await l.newPage({ width: w, height: h });
    for (const shot of shots) {
      if (filter && !shot.name.includes(filter)) continue;
      await page.goto(`${l.base}/tests/sandbox/viewmodel-sandbox.html?manual=1`);
      await page.waitForFunction(() => window.__vm, null, { timeout: 60000 });
      await page.evaluate((s) => {
        const V = window.__vm;
        V.setup(Object.assign({ raise: 0, walk: false }, s.setup || {}));
        V.ctx.step(45, 1 / 60); // settle raise, guard and sway
        if (s.after) V.setup(s.after);
        for (const k of s.play || []) V.vm.play(k);
        if (s.settle2) V.run(s.settle2);
        for (const [name, payload] of s.events || []) V.emit(name, payload);
        V.run(s.at || 0);
      }, shot);
      const file = path.join(outDir, `${shot.name}-${w}x${h}.png`);
      await page.screenshot({ path: file });
      const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
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
