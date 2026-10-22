// Review (performance lens): main-thread cost of spawning one enemy mid-battle (Visual creation:
// SkinnedMesh, 28 bones, Skeleton, Animator) and of its first drawn frame (geometry/bone-texture
// upload), per kind, after the boot pre-warm. Times are wall ms on this machine (low load).
// Usage: node spartan/tests/e2e/review-perf-spawn.mjs
import { launch } from './lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const res = await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer;
    const noop = () => {};
    const saved = [r.render, r.clear, r.clearDepth];
    g.startBattle(1);
    r.render = noop; r.clear = noop; r.clearDepth = noop;
    g.step(60);
    ctx.waves.stop();
    const out = {};
    for (const k of ['levy', 'sparabara', 'immortal', 'archer', 'captain']) {
      const ts = [];
      for (let i = 0; i < 12; i++) {
        const t0 = performance.now();
        const a = g.spawn(k, -6 + i, -12);
        ts.push(performance.now() - t0);
      }
      ts.sort((a, b) => a - b);
      out[k] = { median: +ts[6].toFixed(2), max: +ts[11].toFixed(2) };
      for (const a of ctx.actors.slice()) if (a.team === 'persian') { a.removeMe = true; a.alive = false; }
      g.step(2);
    }
    r.render = saved[0]; r.clear = saved[1]; r.clearDepth = saved[2];
    return out;
  });
  for (const [k, v] of Object.entries(res)) console.log(`spawn ${k.padEnd(10)} median ${v.median} ms, max ${v.max} ms (phone ×4–6: ${(v.median * 4).toFixed(1)}–${(v.median * 6).toFixed(1)} ms each)`);
} finally {
  await l.close();
}
