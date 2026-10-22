// Review (performance lens): main.js adaptResolution() on a CPU-bound phone. Boots the real game
// (RAF loop, not manual), stubs out drawing, and drives the loop with a virtual vsync whose frame
// interval does NOT depend on resolution (a JS-bound frame: 25 ms → every 2nd vsync at 60 Hz).
// Samples stats().renderScale and the drawing-buffer size each virtual second: resolution falls
// to the 0.6 floor while the frame rate stays exactly where it was.
// Usage: node spartan/tests/e2e/review-perf-dynres2.mjs [cpuMs=25] [seconds=30]
import { launch } from './lib.mjs';

const CPU_MS = Number(process.argv[2] || 25);
const SECS = Number(process.argv[3] || 30);
const l = await launch();
try {
  const page = await l.newPage({ width: 914, height: 411 });
  await page.goto(`${l.game}?seed=7&quality=medium&mode=battle&day=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready && window.__booted, null, { timeout: 300000 });
  const out = await page.evaluate(async ([CPU_MS, SECS]) => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer;
    const noop = () => {};
    r.render = noop; r.clear = noop; r.clearDepth = noop;
    const q = [];
    window.requestAnimationFrame = (cb) => { q.push(cb); return q.length; };
    await new Promise((res) => setTimeout(res, 200));
    const vsync = 1000 / 60;
    const interval = Math.ceil(CPU_MS / vsync) * vsync; // a frame that misses vsync waits for the next
    let vt = performance.now();
    const rows = [];
    let frames = 0;
    const hu = ctx.hud.update;
    ctx.hud.update = function (...a) { frames++; return hu.apply(this, a); };
    for (let s = 0; s < SECS; s++) {
      frames = 0;
      const n = Math.round(1000 / interval);
      for (let i = 0; i < n; i++) { vt += interval; for (const cb of q.splice(0)) cb(vt); }
      const st = g.stats();
      const db = r.getDrawingBufferSize(new ctx.THREE.Vector2());
      rows.push(`${s + 1}s:${frames}fps@${st.renderScale.toFixed(2)}(${db.x}x${db.y})`);
    }
    return { interval, rows, dpr: window.devicePixelRatio };
  }, [CPU_MS, SECS]);
  console.log(`CPU-bound frames of ${CPU_MS} ms (vsync interval ${out.interval.toFixed(1)} ms, resolution-independent), DPR ${out.dpr}:`);
  console.log(out.rows.join(' '));
} finally {
  await l.close();
}
