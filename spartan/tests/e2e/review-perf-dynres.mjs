// Review (performance lens): main.js adaptResolution() reacts to the RAF interval only, so a
// CPU-bound frame (JS, not fill) still drives renderScale down to 0.6 without any fps gain.
// Runs the real RAF loop (not manual), adds a fixed busy-wait to every frame (stand-in for a
// phone's slower JS), and samples fps and renderScale. Compares with no busy-wait.
// Usage: node spartan/tests/e2e/review-perf-dynres.mjs [busyMs=24] [seconds=20] [WxH=96x54]
import { launch } from './lib.mjs';

const BUSY = Number(process.argv[2] || 24);
const SECS = Number(process.argv[3] || 20);
const [W, H] = (process.argv[4] || '96x54').split('x').map(Number);
const l = await launch();
try {
  for (const busy of [0, BUSY]) {
    const page = await l.newPage({ width: W, height: H });
    await page.goto(`${l.game}?seed=7&quality=medium&nohud=1`);
    await page.waitForFunction(() => window.__game && window.__game.ready && window.__booted, null, { timeout: 300000 });
    await page.waitForTimeout(3000);
    await page.evaluate((busy) => {
      const ctx = window.__game.ctx;
      const u = ctx.world.update;
      ctx.world.update = function (...a) { const t = performance.now(); while (performance.now() - t < busy); return u.apply(this, a); };
      window.__samples = [];
      let n = 0;
      const tick = () => { n++; requestAnimationFrame(tick); };
      requestAnimationFrame(tick);
      setInterval(() => { const s = window.__game.stats(); window.__samples.push({ fps: Math.round(s.fps), scale: +s.renderScale.toFixed(2), pr: +ctx.renderer.getPixelRatio().toFixed(2), raf: n }); n = 0; }, 1000);
    }, busy);
    await page.waitForTimeout(SECS * 1000);
    const s = await page.evaluate(() => window.__samples);
    console.log(`busy=${busy} ms/frame: ` + s.map((x) => `${x.fps}fps@${x.scale}`).join(' '));
    await page.close();
  }
} finally {
  await l.close();
}
