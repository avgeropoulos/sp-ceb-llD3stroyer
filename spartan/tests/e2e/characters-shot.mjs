// Batch screenshots of the characters sandbox views with one browser.
// usage: node spartan/tests/e2e/characters-shot.mjs <outDir> <name>=<page?query> ... [--size=WxH] [--frames=N]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const args = process.argv.slice(2);
const outDir = args.shift();
let size = '960x540', frames = 4;
const jobs = [];
for (const a of args) {
  if (a.startsWith('--size=')) size = a.slice(7);
  else if (a.startsWith('--frames=')) frames = Number(a.slice(9));
  else {
    const i = a.indexOf('=');
    jobs.push([a.slice(0, i), a.slice(i + 1)]);
  }
}
fs.mkdirSync(outDir, { recursive: true });
const [width, height] = size.split('x').map(Number);
const l = await launch();
let failed = 0;
try {
  for (const [name, target] of jobs) {
    const page = await l.newPage({ width, height });
    const sep = target.includes('?') ? '&' : '?';
    await page.goto(`${l.base}/tests/sandbox/${target}${sep}manual=1`);
    try {
      await page.waitForFunction(() => (window.__chars && window.__sandbox) || (window.__sandbox && window.__sandbox.errors.length), null, { timeout: 60000 });
    } catch (err) {
      console.log(`${name}: TIMEOUT ${page.errors.join(' | ')}`);
      failed++;
      await page.close();
      continue;
    }
    const early = await page.evaluate(() => window.__sandbox.errors);
    if (!(await page.evaluate(() => !!window.__chars))) {
      console.log(`${name}: FAILED ${early.join(' | ')} ${page.errors.join(' | ')}`);
      failed++;
      await page.close();
      continue;
    }
    const info = await page.evaluate((n) => {
      window.__sandbox.step(n);
      const r = window.__sandbox.ctx.renderer.info.render;
      return { calls: r.calls, triangles: r.triangles, visuals: window.__chars.info.visuals.length };
    }, frames);
    const file = path.join(outDir, `${name}.png`);
    await page.screenshot({ path: file });
    const errs = page.errors.concat(await page.evaluate(() => window.__sandbox.errors || []));
    console.log(`${name}: ${file} calls=${info.calls} tris=${info.triangles} visuals=${info.visuals}${errs.length ? ' ERR ' + errs.join(' | ') : ''}`);
    if (errs.length) failed++;
    await page.close();
  }
} finally {
  await l.close();
}
process.exitCode = failed ? 1 : 0;
