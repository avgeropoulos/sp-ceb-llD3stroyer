// Review (performance lens): cost of the viewmodel's compositeBlazon() (gfx/viewmodel/paint.js),
// which runs on the main thread right after the first frame (title screen). Calls the real
// exported function on canvases the size the game uses (the vm bronze set, 1024² on medium) with
// the real blazonFly image, and reports wall time and renderer main-thread CPU time (CDP
// ThreadTime), and how much of it is the synchronous getImageData readback.
// Usage: node spartan/tests/e2e/review-perf-blazon.mjs [size=1024] [reps=3]
import { launch } from './lib.mjs';

const SIZE = Number(process.argv[2] || 1024);
const REPS = Number(process.argv[3] || 3);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable', { timeDomain: 'threadTicks' });
  const cpu = async () => (await cdp.send('Performance.getMetrics')).metrics.find((m) => m.name === 'ThreadTime').value * 1000;
  // The size the game actually uses for the vm bronze set.
  const actual = await page.evaluate(() => {
    let s = null;
    window.__game.ctx.vm.scene.traverse((o) => { const m = o.material; if (m && m.metalnessMap && m.map && m.map.image && m.map.image.width) s = Math.max(s || 0, m.map.image.width); });
    return s;
  });
  console.log(`largest vm metal map in the game: ${actual}²`);
  for (let r = 0; r < REPS; r++) {
    await page.evaluate(async (S) => {
      const mod = await import(new URL('js/gfx/viewmodel/paint.js', location.href).href);
      const mk = () => { const c = document.createElement('canvas'); c.width = S; c.height = S; const g = c.getContext('2d'); g.fillStyle = '#b08040'; g.fillRect(0, 0, S, S); return c; };
      const img = window.__game.ctx.textures.get('blazonFly').image;
      const bmp = await createImageBitmap(img);
      window.__bz = { mod, set: { map: mk(), orm: mk() }, bmp };
    }, SIZE);
    const c0 = await cpu();
    const wall = await page.evaluate(() => { const t0 = performance.now(); window.__bz.mod.compositeBlazon(window.__bz.set, window.__bz.bmp); return performance.now() - t0; });
    const c1 = await cpu();
    // The readbacks alone: three getImageData calls of the same size.
    const c2 = await cpu();
    const rb = await page.evaluate((S) => {
      const s = window.__bz.set;
      const t0 = performance.now();
      const tmp = document.createElement('canvas'); tmp.width = S; tmp.height = S;
      const t = tmp.getContext('2d'); t.drawImage(window.__bz.bmp, 0, 0, S / 2, S / 2);
      t.getImageData(0, 0, S, S); s.map.getContext('2d').getImageData(0, 0, S, S); s.orm.getContext('2d').getImageData(0, 0, S, S);
      return performance.now() - t0;
    }, SIZE);
    const c3 = await cpu();
    console.log(`run ${r + 1}: compositeBlazon(${SIZE}²) wall ${wall.toFixed(0)} ms, main-thread CPU ${(c1 - c0).toFixed(0)} ms; 3× getImageData alone wall ${rb.toFixed(0)} ms, CPU ${(c3 - c2).toFixed(0)} ms`);
  }
} finally {
  await l.close();
}
