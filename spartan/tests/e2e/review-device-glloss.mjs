// Review (device lens): WebGL context loss and restore, as Android WebView does after the app
// is backgrounded under memory pressure or the GPU process restarts. Real rAF loop (no manual
// mode), phone-like context. Screenshots before/after and pixel stats of the canvas.
// Usage: node spartan/tests/e2e/review-device-glloss.mjs [outDir]
import fs from 'node:fs';
import path from 'node:path';
import { startServer } from '../serve.mjs';
import { loadPlaywright, BROWSER_ARGS, phonePage } from './review-device-native.mjs';

const outDir = process.argv[2] || '/tmp/glloss';
fs.mkdirSync(outDir, { recursive: true });
const { chromium } = await loadPlaywright();
const server = await startServer(0);
const base = `http://127.0.0.1:${server.address().port}/src/main/assets/game/index.html`;
const browser = await chromium.launch({ args: BROWSER_ARGS });
const page = await phonePage(browser, { dpr: 1 });
const log = (...a) => console.log(...a);

// Mean luminance and fraction of near-black pixels of the WebGL canvas (read via a 2D copy).
const canvasStats = () => page.evaluate(async () => {
  const c = document.getElementById('scene');
  await new Promise((r) => requestAnimationFrame(() => r()));
  const w = 160, h = Math.round((160 * c.height) / c.width);
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  const g = cv.getContext('2d');
  g.drawImage(c, 0, 0, w, h);
  const d = g.getImageData(0, 0, w, h).data;
  let sum = 0, black = 0;
  for (let i = 0; i < d.length; i += 4) {
    const l = 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
    sum += l;
    if (l < 8) black++;
  }
  return { mean: +(sum / (w * h)).toFixed(1), black: +(black / (w * h)).toFixed(3) };
});

try {
  await page.goto(`${base}?seed=7&quality=medium`);
  await page.waitForFunction(() => window.__booted || (window.__game && window.__game.errors.length), null, { timeout: 300000 });
  await page.evaluate(() => window.__game.startBattle(1));
  await page.waitForTimeout(6000);
  const before = await canvasStats();
  log('before', JSON.stringify(before), JSON.stringify(await page.evaluate(() => window.__game.stats())));
  await page.screenshot({ path: path.join(outDir, 'gl-before.png'), timeout: 300000 });

  await page.evaluate(() => {
    const gl = window.__game.ctx.renderer.getContext();
    window.__ext = gl.getExtension('WEBGL_lose_context');
    window.__lost = 0;
    window.__restored = 0;
    const c = document.getElementById('scene');
    c.addEventListener('webglcontextlost', () => window.__lost++);
    c.addEventListener('webglcontextrestored', () => window.__restored++);
    window.__ext.loseContext();
  });
  await page.waitForFunction(() => window.__lost > 0, null, { timeout: 20000 });
  await page.waitForTimeout(1500);
  log('mode while lost', await page.evaluate(() => window.__game.ctx.mode));
  await page.evaluate(() => window.__ext.restoreContext());
  await page.waitForFunction(() => window.__restored > 0, null, { timeout: 20000 });
  await page.waitForTimeout(8000);
  const after = await canvasStats();
  log('after', JSON.stringify(after), JSON.stringify(await page.evaluate(() => window.__game.stats())));
  await page.screenshot({ path: path.join(outDir, 'gl-after.png'), timeout: 300000 });
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  log('errors', errs.length, errs.slice(0, 12).join('\n'));
  log('warnings', page.logs.slice(0, 20).join('\n'));
} catch (e) {
  console.error('FAILED', e);
} finally {
  await browser.close();
  server.close();
}
