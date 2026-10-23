// Characters fixer: per-function JS allocations of the actor update (ai.js) and the animator
// (gfx/characters/anim.js) in the AI sandbox's big fight (14 enemies, 7 allies, 20 corpses), under
// the V8 sampling heap profiler, like ai-alloc.mjs. With --real the real characters (skinned
// visuals, animator) run instead of the stub visuals.
//   --rev=<git rev>  serves ai.js, characters.js and characters/*.js from that revision (A/B).
//   --file=<regex>   which script URLs to report (default: ai.js and anim.js).
// Usage: node spartan/tests/e2e/characters-fix-alloc.mjs [--real] [--rev=7f85c80] [frames=1800]
import { execFileSync } from 'node:child_process';
import { launch } from './lib.mjs';

const args = process.argv.slice(2);
const real = args.includes('--real');
const rev = (args.find((a) => a.startsWith('--rev=')) || '').slice(6);
const fileRe = new RegExp((args.find((a) => a.startsWith('--file=')) || '--file=/js/(game/ai|gfx/characters/anim)\\.js$').slice(7));
const FRAMES = Number((args.find((a) => /^frames=/.test(a)) || 'frames=1800').slice(7));
const WARM = Number((args.find((a) => /^warm=/.test(a)) || 'warm=6000').slice(5));

const l = await launch();
try {
  const page = await l.newPage();
  if (rev) {
    const OWN = /\/js\/(game\/ai\.js|gfx\/characters\.js|gfx\/characters\/[a-z]+\.js)$/;
    await page.route(OWN, async (route) => {
      const rel = new URL(route.request().url()).pathname.replace(/^\//, '');
      const body = execFileSync('git', ['show', `${rev}:spartan/${rel}`], { encoding: 'utf8', maxBuffer: 1 << 26 });
      await route.fulfill({ status: 200, contentType: 'text/javascript', body });
    });
  }
  await page.goto(`${l.base}/tests/sandbox/ai-battle.html?manual=1&scene=none${real ? '&real=1' : ''}`);
  await page.waitForFunction(() => window.__sim, null, { timeout: 240000 });
  const pop = await page.evaluate(() => window.__sim.scenarios.populate());
  console.log('population', JSON.stringify(pop), rev ? `(characters files from ${rev})` : '(working tree)');
  await page.evaluate((n) => window.__sim.scenarios.runFrames({ frames: n, keep: true }), WARM);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('HeapProfiler.collectGarbage');
  await cdp.send('HeapProfiler.startSampling', { samplingInterval: 16, includeObjectsCollectedByMajorGC: true, includeObjectsCollectedByMinorGC: true });
  const t = Date.now();
  const res = await page.evaluate((frames) => {
    const sim = window.__sim;
    const t0 = performance.now();
    const r = sim.scenarios.runFrames({ frames, dt: 1 / 60, keep: true });
    return { ...r, msPerFrame: +((performance.now() - t0) / frames).toFixed(3), corpses: sim.ai.corpses.length, frozen: sim.ai.corpses.filter((c) => c.frozen).length };
  }, FRAMES);
  const { profile } = await cdp.send('HeapProfiler.stopSampling');
  console.log('run', JSON.stringify(res), `${Date.now() - t} ms wall`);
  const byFn = new Map();
  const byFile = new Map();
  const walk = (node) => {
    const cf = node.callFrame;
    if (node.selfSize > 0 && fileRe.test(cf.url)) {
      const file = cf.url.replace(/^.*\/js\//, '');
      const key = `${file} ${cf.functionName || '(anonymous)'}:${cf.lineNumber + 1}`;
      byFn.set(key, (byFn.get(key) || 0) + node.selfSize);
      byFile.set(file, (byFile.get(file) || 0) + node.selfSize);
    }
    for (const c of node.children || []) walk(c);
  };
  walk(profile.head);
  for (const [f, v] of byFile) console.log(`${f}: ${v} B over ${FRAMES} frames = ${(v / FRAMES).toFixed(1)} B/frame`);
  for (const [k, v] of [...byFn.entries()].sort((a, b) => b[1] - a[1]).slice(0, 24)) console.log(`  ${k}  ${v} B (${(v / FRAMES).toFixed(1)} B/frame)`);
  if (page.errors.length) {
    console.log(page.errors.slice(0, 5).join('\n'));
    process.exitCode = 1;
  }
} finally {
  await l.close();
}
