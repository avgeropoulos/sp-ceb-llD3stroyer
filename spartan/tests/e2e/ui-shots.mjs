// Screenshots of every UI screen from the UI sandbox at phone and desktop sizes.
// Usage: node spartan/tests/e2e/ui-shots.mjs <outDir> [filter]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const outDir = process.argv[2] || 'ui-shots';
const filter = process.argv[3] || '';
fs.mkdirSync(outDir, { recursive: true });

const ALL_SIZES = {
  '844x390': { name: '844x390', width: 844, height: 390, mobile: true },
  '915x412': { name: '915x412', width: 915, height: 412, mobile: true },
  '1280x720': { name: '1280x720', width: 1280, height: 720, mobile: false },
};
const SIZES = (process.env.UI_SIZES || '844x390,1280x720').split(',').map((k) => ALL_SIZES[k]);
const SHOTS = [
  ['title', 'view=title&best={"score":15420,"kills":87,"day":2}'],
  ['settings-controls', 'view=settings&tab=controls'],
  ['settings-sound', 'view=settings&tab=sound'],
  ['settings-display', 'view=settings&tab=display'],
  ['pause', 'view=pause'],
  ['gameover', 'view=gameover&best={"score":9000,"kills":50,"day":3}'],
  ['howto-motion', 'view=howto&tab=motion'],
  ['howto-touch', 'view=howto&tab=touch'],
  ['howto-keys', 'view=howto&tab=keys'],
  ['safety', 'view=safety'],
  ['hud', 'view=hud', 12],
  ['hud-banner', 'view=hud-banner', 20],
  ['hud-wave', 'view=hud-wave', 20],
  ['hud-dayfade', 'view=hud-dayfade', 138],
  ['hud-kolonos', 'view=hud-kolonos', 40],
  ['hud-endcard', 'view=hud-endcard', 20],
  ['hud-volley', 'view=hud-volley', 30],
  ['hud-spearcam', 'view=hud-spearcam', 10],
  ['touch', 'view=touch&control=touch'],
  ['touch-spear', 'view=touch&control=touch&spear=1'],
  ['touch-left', 'view=touch&control=touch&left=1&spear=1'],
  ['motion', 'view=motion&control=motion&debug=1'],
  ['training-touch', 'view=training&control=touch&step=2&gesture=1'],
  ['training-motion', 'view=training&control=motion&step=1&gesture=1'],
  ['training-grip', 'view=training&control=motion&step=0'],
];

const l = await launch();
let failed = 0;
try {
  for (const size of SIZES) {
    const page = await l.newPage(size);
    for (const [name, query, frames = 6] of SHOTS) {
      if (filter && !name.includes(filter)) continue;
      await page.goto(`${l.base}/tests/sandbox/ui-sandbox.html?${query}&manual=1`);
      await page.waitForFunction(() => window.__ui, null, { timeout: 30000 });
      await page.evaluate((n) => window.__sandbox.step(n), frames);
      await page.waitForTimeout(950); // CSS transitions run in real time
      await page.evaluate(() => window.__sandbox.step(1));
      const file = path.join(outDir, `${name}-${size.name}.png`);
      await page.screenshot({ path: file });
      const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
      if (errs.length) {
        failed++;
        console.error(`${name}@${size.name}:\n  ${errs.join('\n  ')}`);
        page.errors.length = 0;
      }
      console.log(`saved ${file}`);
    }
    await page.context().close();
  }
} finally {
  await l.close();
}
if (failed) process.exitCode = 1;
