// Review (performance lens): main-thread cost of world.setTimeOfDay (sky compute + PMREM capture)
// and when the campaign calls it relative to the title cards.
// Usage: node spartan/tests/e2e/review-perf-timeofday.mjs
import { launch } from './lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 300000 });
  const r = await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, gl = ctx.renderer.getContext(), px = new Uint8Array(4);
    g.step(5);
    const times = [];
    for (const t of ['dawn', 'morning', 'noon', 'afternoon', 'dusk', 'morning', 'dawn', 'noon']) {
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      const t0 = performance.now();
      ctx.world.setTimeOfDay(t);
      const t1 = performance.now();
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); // includes the GPU work of the capture
      times.push(`${t}: ${(t1 - t0).toFixed(1)} ms JS, ${(performance.now() - t0).toFixed(0)} ms incl. GPU`);
    }
    // When does the campaign call it? Log calls with the HUD banner state.
    const calls = [];
    const s = ctx.world.setTimeOfDay;
    ctx.world.setTimeOfDay = function (t) { calls.push(`${t} at gnow=${ctx.time.gnow.toFixed(1)} state=${ctx.waves.state} banner=${document.querySelector('.hud-banner') && document.querySelector('.hud-banner').className}`); return s.call(this, t); };
    g.startBattle(3);
    for (let i = 0; i < 1500; i++) g.step(1);
    return { times, calls };
  });
  console.log(r.times.join('\n'));
  console.log('campaign calls:', r.calls.join(' | '));
} finally {
  await l.close();
}
