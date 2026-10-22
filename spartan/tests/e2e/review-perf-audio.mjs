// Review (performance lens): audio render jobs at boot in the real RAF loop (not manual mode):
// per-job worker time (gaps between readyAt stamps), total, AudioBuffer MB, and the watchdog
// margins in audio.js (a job slower than 30 s after the first reply, or 3 s before it, gets the
// worker terminated and the job re-rendered on the main thread in update()).
// Usage: node spartan/tests/e2e/review-perf-audio.mjs
import { launch } from './lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  const cdp = await page.context().newCDPSession(page);
  await page.goto(`${l.game}?seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && window.__game.ready, null, { timeout: 300000 });
  await page.waitForFunction(() => { const s = window.__game.ctx.audio.stats(); return s.pending === 0 && s.ready >= 30; }, null, { timeout: 600000, polling: 500 });
  const s = await page.evaluate(() => window.__game.ctx.audio.stats());
  const entries = Object.entries(s.readyAt).sort((a, b) => a[1] - b[1]);
  let prev = 0;
  const rows = entries.map(([k, t]) => { const d = t - prev; prev = t; return [k, t, d]; });
  console.log(`workers=${s.workers} jobs=${s.jobsDone} ready=${s.ready} AudioBuffers=${s.mb.toFixed(1)} MB worker renderMs=${Math.round(s.renderMs)} all done ${Math.round(s.doneAt - s.startedAt)} ms after audio init`);
  console.log('slowest jobs (gap between consecutive readyAt; single worker → ≈ job time):');
  for (const [k, t, d] of rows.slice().sort((a, b) => b[2] - a[2]).slice(0, 10)) console.log(`  ${k.padEnd(18)} ready at ${String(t).padStart(6)} ms, took ≈ ${d} ms`);
  const m = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((x) => [x.name, x.value]));
  console.log(`hardwareConcurrency=${await page.evaluate(() => navigator.hardwareConcurrency)} (audio.js starts 2 workers only when ≥ 6 cores)`);
} finally {
  await l.close();
}
