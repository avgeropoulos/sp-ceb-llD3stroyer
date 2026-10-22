// Review (performance lens): frame pacing on 60/90/120/144 Hz panels. Boots the real game (RAF
// loop, not manual), then replaces requestAnimationFrame with a virtual vsync that delivers
// callbacks at exact panel intervals, and counts how many game frames main.js steps per virtual
// second (frame() always calls hud.update once). Drawing is stubbed out so SwiftShader speed does
// not matter: only main.js's `interval < 9.5 ms` pacing rule is exercised.
// Usage: node spartan/tests/e2e/review-perf-pacing.mjs
import { launch } from './lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 160, height: 90 });
  await page.goto(`${l.game}?seed=7&quality=medium&nohud=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready && window.__booted, null, { timeout: 300000 });
  const res = await page.evaluate(async () => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer;
    const noop = () => {};
    r.render = noop; r.clear = noop; r.clearDepth = noop;
    let frames = 0;
    const hu = ctx.hud.update;
    ctx.hud.update = function (...a) { frames++; return hu.apply(this, a); };
    // Hand the loop over to a virtual vsync: the loop re-registers itself on every callback.
    const q = [];
    window.requestAnimationFrame = (cb) => { q.push(cb); return q.length; };
    await new Promise((res) => setTimeout(res, 200)); // let the real RAF hand over once
    let vt = performance.now();
    const out = {};
    for (const hz of [60, 90, 120, 144]) {
      const period = 1000 / hz;
      // Small jitter (±0.3 ms) like a real compositor.
      let seed = 1;
      const jit = () => { seed = (seed * 16807) % 2147483647; return (seed / 2147483647 - 0.5) * 0.6; };
      frames = 0;
      const N = hz * 10; // 10 virtual seconds
      for (let i = 0; i < N; i++) {
        vt += period;
        const cbs = q.splice(0);
        for (const cb of cbs) cb(vt + jit());
      }
      out[hz] = { callbacks: N, gameFrames: frames, fps: +(frames / 10).toFixed(1) };
    }
    return out;
  });
  for (const [hz, v] of Object.entries(res)) console.log(`${hz} Hz panel: ${v.gameFrames} game frames in 10 s of vsync → ${v.fps} fps (DESIGN §15 target 60)`);
  console.log('errors:', page.errors.length, page.errors.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
