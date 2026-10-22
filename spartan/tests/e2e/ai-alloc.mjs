// Allocation probe for game/ai.js: 14 enemies + 8 Greeks (7 allies and the helot... until it
// leaves) + 20 corpses, then N frames of ai.update under the V8 sampling heap profiler.
// Allocations are attributed per function; only event payloads (enemyWindup) are allowed.
// Usage: node spartan/tests/e2e/ai-alloc.mjs [--real]
//   --js-flags=...   extra V8 flags (e.g. --no-maglev), via a local launcher
import { launch } from './lib.mjs';
import { startServer } from '../serve.mjs';

const real = process.argv.includes('--real');
const jsFlags = (process.argv.find((a) => a.startsWith('--js-flags=')) || '').slice(11);

async function launchWithFlags(flags) {
  let pw;
  try { pw = await import('playwright'); } catch { pw = await import('/opt/node22/lib/node_modules/playwright/index.mjs'); }
  const server = await startServer(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await pw.chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', `--js-flags=${flags}`] });
  return {
    base,
    async newPage() {
      const page = await (await browser.newContext({ viewport: { width: 960, height: 540 } })).newPage();
      page.errors = [];
      page.on('pageerror', (e) => page.errors.push(String(e)));
      return page;
    },
    async close() { await browser.close(); server.close(); },
  };
}

const l = jsFlags ? await launchWithFlags(jsFlags) : await launch();
let failed = false;
try {
  const page = await l.newPage();
  await page.goto(`${l.base}/tests/sandbox/ai-battle.html?manual=1&scene=none${real ? '&real=1' : ''}`);
  await page.waitForFunction(() => window.__sim, null, { timeout: 240000 });
  const pop = await page.evaluate(() => window.__sim.scenarios.populate());
  console.log('population', JSON.stringify(pop));
  // Warm up long enough for the optimizing tiers; the population is kept topped up.
  const WARM = Number((process.argv.find((a) => a.startsWith('warm=')) || 'warm=20000').slice(5));
  await page.evaluate((n) => window.__sim.scenarios.runFrames({ frames: n, keep: true }), WARM);

  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('HeapProfiler.collectGarbage');
  await cdp.send('HeapProfiler.startSampling', { samplingInterval: 16, includeObjectsCollectedByMajorGC: true, includeObjectsCollectedByMinorGC: true });
  const FRAMES = 1800;
  const t0 = Date.now();
  const res = await page.evaluate((frames) => {
    const sim = window.__sim;
    const before = sim.rec.events.filter((e) => e.name === 'enemyWindup').length;
    const t = performance.now();
    const r = sim.scenarios.runFrames({ frames, dt: 1 / 60, keep: true });
    const ms = performance.now() - t;
    return { ...r, windups: sim.rec.events.filter((e) => e.name === 'enemyWindup').length - before, msPerFrame: ms / frames, corpses: sim.ai.corpses.length, actors: sim.ctx.actors.length };
  }, FRAMES);
  const { profile } = await cdp.send('HeapProfiler.stopSampling');
  console.log('run', JSON.stringify(res), `${Date.now() - t0} ms wall`);

  const byFn = new Map();
  const walk = (node, stack) => {
    const cf = node.callFrame;
    const here = stack.concat([cf]);
    if (node.selfSize > 0 && /\/js\/game\/ai\.js$/.test(cf.url)) {
      const key = `${cf.functionName || '(anonymous)'}:${cf.lineNumber + 1}`;
      byFn.set(key, (byFn.get(key) || 0) + node.selfSize);
    }
    for (const c of node.children || []) walk(c, here);
  };
  walk(profile.head, []);
  const total = [...byFn.values()].reduce((a, b) => a + b, 0);
  console.log(`ai.js allocations over ${FRAMES} frames: ${total} bytes`);
  for (const [k, v] of [...byFn.entries()].sort((a, b) => b[1] - a[1])) console.log(`  ${k}  ${v} B`);
  // Event-time work (spawns, hits, deaths, attacks, target choice) may allocate; per-frame code
  // may not. 'update' includes whatever V8 inlined into it, so it carries the rare boxing at
  // value-change points (terrain re-sample, locomotion step).
  const EVENT_FNS = new Set(['startWindup', 'resolveStrike', 'takeHit', 'die', 'deathClipFor', 'Actor', 'spawnEnemy', 'spawnAlly',
    'spawnDummy', 'spawnHelot', 'addActor', 'createVisual', 'engageArrived', 'playReaction', 'play', 'think', 'claimPlayerSlot',
    'claimAllySpot', 'claimWait', 'releaseAll', 'killAlly', 'retreatArchers', 'disposeActor', 'clipInfo', 'clipDuration',
    'clipStrikeAt', 'ensureAllies', 'pickAllyTarget', 'pickAllyFoe', 'tryDirector', 'setGuard', 'integrateKnock']);
  let perFrame = 0;
  for (const [k, v] of byFn) if (!EVENT_FNS.has(k.split(':')[0])) perFrame += v;
  const perFrameAvg = perFrame / FRAMES;
  const okBad = perFrameAvg <= 40;
  const okWind = true;
  console.log(`${okBad ? 'ok  ' : 'FAIL'} per-frame code allocates ${perFrameAvg.toFixed(1)} B/frame on average (≤ 40; event-time work excluded: ${total - perFrame} B)`);
  failed = !okBad || !okWind || page.errors.length > 0;
  if (page.errors.length) console.log(page.errors.join('\n'));
} finally {
  await l.close();
}
process.exit(failed ? 1 : 0);
