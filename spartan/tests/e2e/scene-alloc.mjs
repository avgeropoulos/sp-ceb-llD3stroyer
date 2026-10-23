// Scene fixer check: garbage per frame from the volley and particle hot paths (combat volleys,
// fx particles and decals), under the V8 sampling heap profiler. A sandbox Day 1 with allies:
// a 300-arrow volley every 4 s, and every 10 frames blood, dust and sparks 2–6 m in front of the
// player. Warms up first so the hot functions are optimized, then samples `frames` frames and
// prints bytes per frame by function for the scene-owned modules (and the total).
// SCENE_OVERRIDE='js/game/combat.js=/path/a.js,js/gfx/fx/particles.js=/path/b.js' serves other
// sources for those files (a before/after comparison). Drawing is stubbed out (SwiftShader takes
// about a second a frame, and none of these hot paths depend on it); SCENE_DRAW=1 keeps it.
// Usage: node spartan/tests/e2e/scene-alloc.mjs [frames=1200]
import fs from 'node:fs';
import { launch } from './lib.mjs';

const FRAMES = Number(process.argv[2] || 1200);
const OWN = /(combat|particles|decals|streaks|fx|world|props|sky)\.js/;
const l = await launch();
try {
  const page = await l.newPage({ width: 480, height: 270 });
  for (const pair of (process.env.SCENE_OVERRIDE || '').split(',').filter(Boolean)) {
    const [rel, file] = pair.split('=');
    const body = fs.readFileSync(file, 'utf8');
    await page.route(`**/${rel}`, (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body }));
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await page.evaluate((draw) => {
    window.__sceneDraw = draw;
    const g = window.__game;
    const c = g.ctx;
    g.startBattle(1);
    c.waves.stop();
    c.ai.clear();
    c.combat.reset();
    c.ai.ensureAllies();
    c.hud.reset();
    let f = 0;
    let seed = 1;
    const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    // One driven frame: effects as a busy melee makes them, and a volley every 4 s.
    const r = c.renderer;
    if (!window.__sceneDraw) {
      const noop = () => {};
      r.render = noop;
      r.clear = noop;
      r.clearDepth = noop;
    }
    window.__sceneFrame = () => {
      const p = c.player.pos;
      if (f % 240 === 0) c.combat.volley({ count: 300, duration: 1.0, warn: 0.5 });
      if (f % 10 === 0) {
        const x = p.x - 2 + 4 * rnd();
        const z = p.z - 2 - 4 * rnd();
        c.fx.blood({ x, y: 1.2, z }, { x: rnd() - 0.5, y: 0.2, z: 1 }, 1);
        c.fx.dust({ x, y: 0.05, z }, 0.8);
        c.fx.sparks({ x, y: 1.3, z: z + 0.3 }, { x: 0, y: 0.3, z: 1 }, 0.8);
      }
      f++;
      g.step(1);
    };
  }, process.env.SCENE_DRAW === '1');
  const run = (n) => page.evaluate((n) => { for (let i = 0; i < n; i++) window.__sceneFrame(); }, n);
  for (let i = 0; i < 6; i++) await run(100); // warm-up: get the hot paths optimized
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('HeapProfiler.enable');
  await cdp.send('HeapProfiler.startSampling', { samplingInterval: 128 });
  for (let done = 0; done < FRAMES; done += 100) await run(Math.min(100, FRAMES - done));
  const { profile } = await cdp.send('HeapProfiler.stopSampling');
  const byFn = new Map();
  let total = 0;
  const walk = (node) => {
    const cf = node.callFrame;
    const file = (cf.url || '').split('/').pop() || '(native)';
    const key = `${cf.functionName || '(anon)'} ${file}:${cf.lineNumber + 1}`;
    if (node.selfSize) {
      byFn.set(key, (byFn.get(key) || 0) + node.selfSize);
      total += node.selfSize;
    }
    for (const ch of node.children || []) walk(ch);
  };
  walk(profile.head);
  const rows = [...byFn.entries()].filter(([k]) => OWN.test(k)).sort((a, b) => b[1] - a[1]);
  const own = rows.reduce((s, [, v]) => s + v, 0);
  console.log(`frames ${FRAMES}: total ${(total / FRAMES).toFixed(0)} B/frame, scene-owned ${(own / FRAMES).toFixed(0)} B/frame`);
  for (const [k, v] of rows.slice(0, 15)) console.log(`  ${(v / FRAMES).toFixed(0).padStart(6)} B/frame  ${k}`);
  const natives = [...byFn.entries()].filter(([k]) => /\(native\)|^hypot|^sqrt/.test(k)).sort((a, b) => b[1] - a[1]).slice(0, 5);
  for (const [k, v] of natives) console.log(`  ${(v / FRAMES).toFixed(0).padStart(6)} B/frame  ${k}`);
  const stats = await page.evaluate(() => ({ particles: window.__game.ctx.fx.particleCount, decals: window.__game.ctx.fx.decalCount, errors: window.__game.errors.slice(0, 3) }));
  console.log(JSON.stringify(stats));
} finally {
  await l.close();
}
