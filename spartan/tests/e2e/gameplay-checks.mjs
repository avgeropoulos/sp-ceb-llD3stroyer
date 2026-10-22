// Playwright checks for game/player.js + game/combat.js in the gameplay sandbox.
// Usage: node spartan/tests/e2e/gameplay-checks.mjs [checkName ...]
// Runs every check in tests/sandbox/gameplay-checks.js inside headless Chromium (manual stepping),
// prints a line per check and exits non-zero on any failure or page error.
import { launch } from './lib.mjs';

const names = process.argv.slice(2);
const l = await launch();
let failed = 0;
try {
  const page = await l.newPage({ width: 640, height: 360 });
  await page.goto(`${l.base}/tests/sandbox/gameplay-sandbox.html?manual=1&checks=1`);
  await page.waitForFunction(() => window.__gpReady === true, null, { timeout: 60000 });
  const propSource = await page.evaluate(() => window.__gp.propSource);
  console.log(`props: ${propSource}`);
  const results = await page.evaluate((n) => window.__gp.runChecks(n), names);
  for (const r of results) {
    console.log(`${r.ok ? 'ok  ' : 'FAIL'} ${r.name}${r.ms !== undefined ? ` (${r.ms} ms)` : ''}`);
    if (!r.ok) {
      failed++;
      console.log(`     ${String(r.error).split('\n').slice(0, 4).join('\n     ')}`);
    }
    if (r.counts) console.log(`     events seen: ${JSON.stringify(r.counts)}`);
  }
  const info = await page.evaluate(() => {
    const r = window.__sandbox.ctx.renderer.info;
    return { calls: r.render.calls, triangles: r.render.triangles, geometries: r.memory.geometries };
  });
  console.log(`renderer.info after the run: ${JSON.stringify(info)}`);
  // The sandbox probes gfx/characters.js for real props; while that module is missing the
  // probe's 404 is expected.
  const expected404 = (e) => propSource === 'fallback' && /status of 404/.test(e);
  const errs = page.errors.filter((e) => !expected404(e)).concat(await page.evaluate(() => window.__sandbox.errors || []));
  if (errs.length) {
    failed++;
    console.log(`page errors:\n${errs.join('\n')}`);
  }
  console.log(failed ? `${failed} failing` : `all ${results.length} checks passed`);
} finally {
  await l.close();
}
process.exitCode = failed ? 1 : 0;
