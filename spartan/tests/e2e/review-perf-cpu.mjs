// Review (performance lens): per-frame JS cost by subsystem in a worst-case battle:
// 14 enemies (kept topped up), 7 allies + the helot, 20 corpses, particles and volleys.
// Sim updates are timed on every frame; renderer.render CPU time is timed on drawn frames only,
// after a 1-pixel readPixels so SwiftShader's backlog never lands inside the timed call.
// Also samples JS heap (CDP Runtime.getHeapUsage) and runs the V8 sampling heap profiler.
// Usage: node spartan/tests/e2e/review-perf-cpu.mjs [measureFrames=3600] [WxH=480x270] [quality=medium]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const FR = Number(process.argv[2] || 3600);
const [W, H] = (process.argv[3] || '480x270').split('x').map(Number);
const quality = process.argv[4] || 'medium';
const l = await launch();
try {
  const page = await l.newPage({ width: W, height: H });
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const cdp = await page.context().newCDPSession(page);
  // Let the audio worker finish its boot queue while the event loop runs (manual mode has no
  // RAF loop; a long synchronous step() loop would otherwise trip the audio worker watchdog).
  const au0 = await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); return window.__game.ctx.audio.stats(); });
  console.log('audio after prerender', JSON.stringify({ workers: au0.workers, pending: au0.pending, ready: au0.ready, mb: au0.mb.toFixed(1), renderMs: Math.round(au0.renderMs), doneAt: Math.round(au0.doneAt - au0.startedAt) }));

  // ---- setup: the worst-case population ----
  const setup = await page.evaluate(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const rv = window.__rv;
    rv.noDraw = true;
    g.startBattle(1);
    g.step(120);
    ctx.waves.stop(); // we drive the population ourselves
    // 20 corpses: spawn and strike down with a fake attacker.
    const kinds = ['levy', 'levy', 'sparabara', 'immortal', 'levy'];
    const victims = [];
    for (let i = 0; i < 20; i++) victims.push(g.spawn(kinds[i % kinds.length], -5 + (i % 10) * 1.3, -7 - Math.floor(i / 10) * 2.5));
    g.step(5);
    let killed = 0;
    for (const v of victims) {
      const fake = { alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI, team: 'greek', kind: 'spartan' };
      const r = ctx.combat.actorStrike(fake, { target: v, damage: 9999, kind: 'thrust', reach: 2 });
      if (!v.alive) killed++;
    }
    rv.run(240, 10);
    return { killed, corpses: ctx.ai.corpses.length, actors: ctx.actors.length };
  });
  console.log('setup', JSON.stringify(setup));

  await page.evaluate(() => {
    const g = window.__game;
    const ctx = g.ctx;
    const rv = window.__rv;
    const mix = ['levy', 'levy', 'sparabara', 'immortal', 'immortal', 'levy', 'captain', 'levy', 'sparabara', 'immortal', 'levy', 'immortal'];
    let k = 0;
    rv.topUp = () => {
      let n = 0;
      let archers = 0;
      for (const a of ctx.actors) if (a.alive && a.team === 'persian') { n++; if (a.role === 'archer') archers++; }
      while (archers < 2) { g.spawn('archer', -4 + 8 * Math.random(), -16 - 3 * Math.random()); archers++; n++; }
      while (n < 14) { g.spawn(mix[k++ % mix.length], -5 + 11 * ((k * 0.37) % 1), -9 - 4 * ((k * 0.61) % 1)); n++; }
      if (ctx.ai.alliesAlive < 7) ctx.ai.ensureAllies();
    };
    rv.lastVolley = 0;
    rv.extraFx = () => {
      // Keep fx busy like a melee line: blood, sparks and dust around the front every few frames.
      const fx = ctx.fx;
      const x = -4 + 8 * Math.random();
      const z = -3 - 3 * Math.random();
      fx.blood({ x, y: 1.2, z }, { x: 0, y: 0.2, z: 1 }, 0.8);
      fx.sparks({ x: x + 0.5, y: 1.3, z }, { x: 0, y: 0, z: 1 }, 0.6);
      fx.dust({ x: x - 0.5, y: 0.05, z }, 0.6);
    };
    rv.pilot(true);
    rv.hpScale = 1;
  });

  // Warm-up (JIT tiers), then measure.
  const chunk = async (frames, measureRender) => page.evaluate(([frames, measureRender]) => {
    const g = window.__game;
    const ctx = g.ctx;
    const rv = window.__rv;
    const gl = ctx.renderer.getContext();
    const px = new Uint8Array(4);
    const frameTimes = [];
    const renderTimes = [];
    for (let f = 0; f < frames; f += 10) {
      rv.topUp();
      if (ctx.time.gnow - rv.lastVolley > 7) { ctx.combat.volley({ count: 300, duration: 1.2, warn: 1.5 }); rv.lastVolley = ctx.time.gnow; }
      if ((f / 10) % 2 === 0) rv.extraFx();
      rv.pilotStep();
      // 9 frames without drawing, then (optionally) one drawn frame timed after a GPU sync.
      rv.noDraw = true;
      for (let i = 0; i < 9; i++) {
        const t0 = performance.now();
        g.step(1);
        frameTimes.push(performance.now() - t0);
      }
      if (measureRender) {
        rv.noDraw = false;
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        const t0 = performance.now();
        g.step(1);
        renderTimes.push(performance.now() - t0);
      } else {
        rv.noDraw = true;
        const t0 = performance.now();
        g.step(1);
        frameTimes.push(performance.now() - t0);
      }
    }
    return { frameTimes, renderTimes, pop: rv.sampleRender() };
  }, [frames, measureRender]);

  // rv.run swaps render out when noDraw; for plain g.step we need the same switch here.
  await page.evaluate(() => {
    const rv = window.__rv;
    const r = window.__game.ctx.renderer;
    const real = { render: r.render, clear: r.clear, clearDepth: r.clearDepth };
    const noop = () => {};
    for (const k of ['render', 'clear', 'clearDepth']) {
      Object.defineProperty(r, k, { configurable: true, get: () => (rv.noDraw ? noop : real[k]), set: (v) => { real[k] = v; } });
    }
  });

  let t = Date.now();
  await chunk(2400, false);
  console.log(`warm-up 2400 frames in ${Date.now() - t} ms`);
  await page.evaluate(() => { window.__rv.noDraw = false; window.__rv.wrapTimers(); window.__rv.resetTimers(); });

  const all = { frameTimes: [], renderTimes: [] };
  let pop = null;
  await cdp.send('Performance.enable');
  const metric = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
  const m0 = await metric();
  t = Date.now();
  for (let done = 0; done < FR; done += 600) {
    const r = await chunk(600, true);
    all.frameTimes.push(...r.frameTimes);
    all.renderTimes.push(...r.renderTimes);
    pop = r.pop;
  }
  const wall = Date.now() - t;
  const m1 = await metric();
  const thread = (m1.ThreadTime - m0.ThreadTime) * 1000;
  const script = (m1.ScriptDuration - m0.ScriptDuration) * 1000;
  const task = (m1.TaskDuration - m0.TaskDuration) * 1000;
  console.log(`main-thread CPU time ${thread.toFixed(0)} ms vs task (wall) ${task.toFixed(0)} ms, script ${script.toFixed(0)} ms over ${FR} frames -> contention factor wall/cpu = ${(task / thread).toFixed(2)}; CPU ms/frame = ${(thread / FR).toFixed(3)}`);
  const timers = await page.evaluate(() => window.__rv.timers);
  const au1 = await page.evaluate(() => window.__game.ctx.audio.stats());
  console.log('audio after measurement', JSON.stringify({ workers: au1.workers, pending: au1.pending, ready: au1.ready, voices: au1.voices, played: au1.played, stolen: au1.stolen, dropped: au1.dropped }));
  // Allocation sampling over a second window (kept out of the timed window).
  await cdp.send('HeapProfiler.enable');
  await cdp.send('HeapProfiler.collectGarbage');
  const heap0 = await cdp.send('Runtime.getHeapUsage');
  await cdp.send('HeapProfiler.startSampling', { samplingInterval: 4096, includeObjectsCollectedByMajorGC: true, includeObjectsCollectedByMinorGC: true });
  for (let done = 0; done < FR; done += 600) await chunk(600, true);
  const { profile } = await cdp.send('HeapProfiler.stopSampling');
  await cdp.send('HeapProfiler.collectGarbage');
  const heap1 = await cdp.send('Runtime.getHeapUsage');

  const stats = (arr) => {
    const s = arr.slice().sort((a, b) => a - b);
    const mean = s.reduce((a, b) => a + b, 0) / s.length;
    return { n: s.length, mean: +mean.toFixed(3), p50: +s[Math.floor(s.length * 0.5)].toFixed(3), p95: +s[Math.floor(s.length * 0.95)].toFixed(3), p99: +s[Math.floor(s.length * 0.99)].toFixed(3), max: +s[s.length - 1].toFixed(3) };
  };
  console.log(`measured ${FR} frames in ${wall} ms wall; population at end: enemies=${pop.alive} allies=${pop.allies} corpses=${pop.corpses} particles=${pop.particles} calls=${pop.total.calls} tris=${pop.total.tris}`);
  console.log('frame (no draw) ms', JSON.stringify(stats(all.frameTimes)));
  console.log('frame (with draw, after GPU sync) ms', JSON.stringify(stats(all.renderTimes)));
  const frames = FR;
  const rows = Object.entries(timers).map(([k, v]) => [k, v.ms / Math.max(1, v.n), v.max, v.n, v.ms]);
  rows.sort((a, b) => b[4] - a[4]);
  console.log('subsystem              mean ms/call   max ms    calls');
  for (const [k, mean, max, n] of rows) console.log(`${k.padEnd(22)} ${mean.toFixed(3).padStart(10)} ${max.toFixed(2).padStart(9)} ${String(n).padStart(8)}`);

  // Allocation by function (self size), top 30, plus by script.
  const byFn = new Map();
  const byUrl = new Map();
  let total = 0;
  const walk = (node) => {
    const cf = node.callFrame;
    if (node.selfSize > 0) {
      const url = (cf.url || '').replace(/^.*\/game\//, '');
      const key = `${cf.functionName || '(anon)'} ${url}:${cf.lineNumber + 1}`;
      byFn.set(key, (byFn.get(key) || 0) + node.selfSize);
      byUrl.set(url || '(native)', (byUrl.get(url || '(native)') || 0) + node.selfSize);
      total += node.selfSize;
    }
    for (const c of node.children || []) walk(c);
  };
  walk(profile.head);
  const perFrame = (b) => (b / FR).toFixed(0);
  console.log(`sampled allocations over ${FR} frames: ${(total / 1e6).toFixed(1)} MB (${perFrame(total)} B/frame)`);
  console.log('by script:');
  for (const [k, v] of [...byUrl.entries()].sort((a, b) => b[1] - a[1]).slice(0, 20)) console.log(`  ${(v / 1e6).toFixed(2).padStart(7)} MB  ${perFrame(v).padStart(7)} B/frame  ${k}`);
  console.log('by function:');
  for (const [k, v] of [...byFn.entries()].sort((a, b) => b[1] - a[1]).slice(0, 30)) console.log(`  ${(v / 1e6).toFixed(2).padStart(7)} MB  ${perFrame(v).padStart(7)} B/frame  ${k}`);
  console.log(`heap used: before ${(heap0.usedSize / 1e6).toFixed(1)} MB, after ${(heap1.usedSize / 1e6).toFixed(1)} MB (total ${(heap1.totalSize / 1e6).toFixed(1)} MB)`);
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 5).join('\n'));
} finally {
  await l.close();
}
