// Review (performance lens): DOM HUD cost per frame in the real RAF loop (battle, Day 1):
// style recalcs, layouts and their durations (CDP Performance metrics), per rendered frame.
// Usage: node spartan/tests/e2e/review-perf-dom.mjs [seconds=20]
import { launch } from './lib.mjs';

const SECS = Number(process.argv[2] || 20);
const l = await launch();
try {
  const page = await l.newPage({ width: 480, height: 270 });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  await page.goto(`${l.game}?seed=7&quality=medium&mode=battle&day=1`);
  await page.waitForFunction(() => window.__game && window.__game.ready && window.__booted, null, { timeout: 300000 });
  await page.waitForTimeout(8000);
  await page.evaluate(() => { window.__frames = 0; const ctx = window.__game.ctx; const u = ctx.hud.update; ctx.hud.update = function (...a) { window.__frames++; return u.apply(this, a); }; });
  const m = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((x) => [x.name, x.value]));
  const a = await m();
  await page.waitForTimeout(SECS * 1000);
  const b = await m();
  const frames = await page.evaluate(() => window.__frames);
  const d = (k) => b[k] - a[k];
  console.log(`frames=${frames} in ${SECS}s; per frame: RecalcStyle ${(d('RecalcStyleCount') / frames).toFixed(2)}× ${(1000 * d('RecalcStyleDuration') / frames).toFixed(3)} ms, Layout ${(d('LayoutCount') / frames).toFixed(2)}× ${(1000 * d('LayoutDuration') / frames).toFixed(3)} ms, Script ${(1000 * d('ScriptDuration') / frames).toFixed(2)} ms, Task ${(1000 * d('TaskDuration') / frames).toFixed(2)} ms; DOM nodes ${b.Nodes}, JS listeners ${b.JSEventListeners}`);
  console.log('mode/state:', await page.evaluate(() => `${window.__game.ctx.mode} ${window.__game.ctx.waves.state} actors=${window.__game.ctx.actors.length}`));
} finally {
  await l.close();
}
