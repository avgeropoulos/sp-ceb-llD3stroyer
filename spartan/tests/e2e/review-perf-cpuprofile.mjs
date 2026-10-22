// Review (performance lens): V8 CPU profile of (1) simulation frames (no drawing) and (2) render
// calls, in the worst-case population (14 enemies, 7 allies + helot, 20 corpses, particles,
// volleys). Sampling attribution is robust to machine load (all functions slow down equally), so
// shares are reported along with ms/frame from the sampled totals.
// Usage: node spartan/tests/e2e/review-perf-cpuprofile.mjs [simFrames=3000] [renders=150]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const SIM = Number(process.argv[2] || 3000);
const RENDERS = Number(process.argv[3] ?? 150);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); });
  await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, rv = window.__rv;
    rv.noDraw = true;
    g.startBattle(1);
    g.step(120);
    ctx.waves.stop();
    const kinds = ['levy', 'levy', 'sparabara', 'immortal', 'levy'];
    const victims = [];
    for (let i = 0; i < 20; i++) victims.push(g.spawn(kinds[i % 5], -5 + (i % 10) * 1.3, -7 - Math.floor(i / 10) * 2.5));
    g.step(5);
    for (const v of victims) ctx.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 });
    g.step(300);
    const mix = ['levy', 'sparabara', 'immortal', 'levy', 'captain', 'immortal'];
    let k = 0;
    rv.topUp = () => {
      let n = 0, archers = 0;
      for (const a of ctx.actors) if (a.alive && a.team === 'persian') { n++; if (a.role === 'archer') archers++; }
      while (archers < 2) { g.spawn('archer', -3 + 6 * ((k++ * 0.37) % 1), -17); archers++; n++; }
      while (n < 14) { g.spawn(mix[k++ % mix.length], -5 + 11 * ((k * 0.37) % 1), -8 - 3 * ((k * 0.61) % 1)); n++; }
      if (ctx.ai.alliesAlive < 7) ctx.ai.ensureAllies();
    };
    rv.lastVolley = -10;
    const R = ctx.renderer;
    const noop = () => {};
    rv.drive = (frames) => {
      const saved = rv.noDraw ? [R.render, R.clear, R.clearDepth] : null;
      if (saved) { R.render = noop; R.clear = noop; R.clearDepth = noop; }
      try { rv.drive0(frames); } finally { if (saved) { R.render = saved[0]; R.clear = saved[1]; R.clearDepth = saved[2]; } }
    };
    rv.drive0 = (frames) => {
      for (let f = 0; f < frames; f += 10) {
        rv.topUp();
        if (ctx.time.gnow - rv.lastVolley > 7) { ctx.combat.volley({ count: 300, duration: 1.2, warn: 1.5 }); rv.lastVolley = ctx.time.gnow; }
        if ((f / 10) % 2 === 0) { const x = -4 + 8 * Math.random(); ctx.fx.blood({ x, y: 1.2, z: -4 }, { x: 0, y: 0.2, z: 1 }, 0.8); ctx.fx.dust({ x, y: 0.05, z: -4 }, 0.6); }
        rv.pilotStep();
        g.step(10);
      }
    };
    rv.pilot(true);
    rv.noDraw = true;
    rv.drive(2400); // warm-up
  });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Profiler.enable');
  await cdp.send('Profiler.setSamplingInterval', { interval: 100 });

  const analyze = (profile, label, frames, exclude = []) => {
    const nodes = new Map(profile.nodes.map((n) => [n.id, n]));
    const parent = new Map();
    for (const n of profile.nodes) for (const c of n.children || []) parent.set(c, n.id);
    const selfUs = new Map();
    for (let i = 0; i < profile.samples.length; i++) selfUs.set(profile.samples[i], (selfUs.get(profile.samples[i]) || 0) + (profile.timeDeltas[i] || 0));
    const name = (n) => `${n.callFrame.functionName || '(anon)'} ${(n.callFrame.url || '').replace(/^.*\/game\//, '')}:${n.callFrame.lineNumber + 1}`;
    let total = 0;
    const self = new Map();
    const incl = new Map();
    for (const [id, us] of selfUs) {
      const n = nodes.get(id);
      const nm = name(n);
      if (exclude.some((x) => nm.startsWith(x)) || nm.startsWith('(idle)') || nm.startsWith('(program)')) continue;
      total += us;
      self.set(nm, (self.get(nm) || 0) + us);
      // inclusive: walk to root, count each distinct function once per sample stack
      const seen = new Set();
      let cur = id;
      while (cur !== undefined) {
        const cn = nodes.get(cur);
        const k = name(cn);
        if (!seen.has(k)) { seen.add(k); incl.set(k, (incl.get(k) || 0) + us); }
        cur = parent.get(cur);
      }
    }
    const ms = (us) => (us / 1000 / frames).toFixed(3);
    console.log(`\n== ${label}: ${(total / 1000).toFixed(0)} ms sampled over ${frames} frames = ${ms(total)} ms/frame (wall on a loaded 2.1 GHz Xeon) ==`);
    console.log('top self time:');
    for (const [k, v] of [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 28)) console.log(`  ${(100 * v / total).toFixed(1).padStart(5)}%  ${ms(v)} ms/f  ${k}`);
    console.log('top inclusive (game/three functions):');
    for (const [k, v] of [...incl.entries()].filter(([k]) => !/^\(root\)|^\(anon\) :|^evaluate|^\(anonymous\)/.test(k)).sort((a, b) => b[1] - a[1]).slice(0, 40)) console.log(`  ${(100 * v / total).toFixed(1).padStart(5)}%  ${ms(v)} ms/f  ${k}`);
  };

  // (1) simulation only.
  await cdp.send('Profiler.start');
  const t0 = Date.now();
  await page.evaluate((n) => { window.__rv.noDraw = true; window.__rv.drive(n); }, SIM);
  const sim = (await cdp.send('Profiler.stop')).profile;
  console.log(`sim wall ${Date.now() - t0} ms`);
  analyze(sim, 'SIMULATION (frame() without drawing)', SIM, ['rv.drive', 'pilotStep', 'topUp', 'drive0']);

  if (RENDERS > 0) {
  // (2) render only (same frame re-rendered, GPU synced before each, sync excluded).
  const pop = await page.evaluate(() => { const c = window.__game.ctx; return { alive: c.actors.filter((a) => a.alive && a.team === 'persian').length, greeks: c.actors.filter((a) => a.alive && a.team !== 'persian').length, corpses: c.ai.corpses.length, particles: c.fx.particleCount, calls: (window.__game.render(), c.renderer.info.render.calls) }; });
  console.log('render population', JSON.stringify(pop));
  await cdp.send('Profiler.start');
  await page.evaluate((n) => {
    const g = window.__game, gl = g.ctx.renderer.getContext(), px = new Uint8Array(4);
    window.__rv.noDraw = false;
    for (let i = 0; i < n; i++) {
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      g.render();
    }
  }, RENDERS);
  const ren = (await cdp.send('Profiler.stop')).profile;
  analyze(ren, 'RENDER (main + vm pass, incl. shadow pass)', RENDERS, ['readPixels', '(anon) :']);
  }
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
