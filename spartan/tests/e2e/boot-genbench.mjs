// First-launch texture generation with and without the generator workers (fixer "boot"): times
// generateAll() on a fresh textures module (pixel cache off) in the booted game page, alternating
// main-thread-only and worker runs, and reports wall time, the main thread's long-task time and
// the longest gap between animation frames (how badly the boot screen would stall).
// Usage: node spartan/tests/e2e/boot-genbench.mjs [quality=medium] [rounds=2] [workers=3]
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const rounds = Number(process.argv[3] || 2);
const workers = Number(process.argv[4] || 3);
const l = await launch();
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const rows = [];
  for (let r = 0; r < rounds; r++) {
    for (const n of [0, workers]) {
      const res = await page.evaluate(async ([q, count]) => {
        const mod = await import('/src/main/assets/game/js/gfx/textures.js');
        const t = mod.createTextures({ quality: q, renderer: window.__game.ctx.renderer }, { cache: false, workers: count });
        let longMs = 0;
        const obs = new PerformanceObserver((list) => {
          for (const e of list.getEntries()) longMs += e.duration;
        });
        obs.observe({ type: 'longtask', buffered: false });
        let maxGap = 0;
        let last = performance.now();
        let on = true;
        const tick = () => {
          const t1 = performance.now();
          maxGap = Math.max(maxGap, t1 - last);
          last = t1;
          if (on) requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
        const t0 = performance.now();
        await t.generateAll();
        const wall = performance.now() - t0;
        on = false;
        await new Promise((res) => setTimeout(res, 100));
        obs.disconnect();
        for (const name of t.names) t.release(name);
        return { wall: Math.round(wall), longMs: Math.round(longMs), maxGap: Math.round(maxGap), stats: { ...t.stats } };
      }, [quality, n]);
      rows.push({ workers: n, ...res });
      console.log(`quality=${quality} workers=${n}: generateAll ${res.wall} ms wall; main-thread long tasks ${res.longMs} ms; longest frame gap ${res.maxGap} ms; ${JSON.stringify(res.stats)}`);
    }
  }
  const avg = (n, k) => Math.round(rows.filter((x) => x.workers === n).reduce((s, x) => s + x[k], 0) / rounds);
  console.log(`\nmean over ${rounds}: main thread only ${avg(0, 'wall')} ms wall, ${avg(0, 'longMs')} ms long tasks, ${avg(0, 'maxGap')} ms worst gap | ${workers} workers ${avg(workers, 'wall')} ms wall, ${avg(workers, 'longMs')} ms long tasks, ${avg(workers, 'maxGap')} ms worst gap`);
} finally {
  await l.close();
}
