// Screenshot any page under spartan/: node spartan/tests/e2e/shot.mjs <path?query> <out.png> [frames=30] [WxH=960x540]
// Pages that use sandbox-ctx.js or main.js honour manual=1, so the frames are stepped deterministically.
import { launch } from './lib.mjs';

const [, , target, out, framesArg = '30', size = '960x540'] = process.argv;
if (!target || !out) {
  console.error('usage: shot.mjs <path?query> <out.png> [frames] [WxH]');
  process.exit(2);
}
const [width, height] = size.split('x').map(Number);
const l = await launch();
try {
  const page = await l.newPage({ width, height });
  const sep = target.includes('?') ? '&' : '?';
  await page.goto(`${l.base}/${target.replace(/^\//, '')}${sep}manual=1`);
  await page.waitForFunction(() => window.__sandbox || (window.__game && window.__game.ready), null, { timeout: 60000 });
  await page.evaluate((n) => (window.__sandbox || window.__game).step(n), Number(framesArg));
  await page.screenshot({ path: out });
  const errs = page.errors.concat(await page.evaluate(() => (window.__sandbox || window.__game).errors || []));
  if (errs.length) {
    console.error(errs.join('\n'));
    process.exitCode = 1;
  }
  console.log(`saved ${out}`);
} finally {
  await l.close();
}
