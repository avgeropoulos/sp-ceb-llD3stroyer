// Review (performance lens): renderer-process CPU time (CDP SystemInfo.getProcessInfo, immune to
// machine load) per simulated frame and per rendered frame, in the worst-case population
// (14 enemies, 7 allies + helot, 20 corpses, particles, volleys every 7 s).
// (cputime2: the renderer main thread is the process main thread, tid == pid; under
// --headless=old it is named "headless_shell." rather than CrRendererMain.)
// Usage: node spartan/tests/e2e/review-perf-cputime2.mjs [simFrames=3600] [renders=120]
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const SIM = Number(process.argv[2] || 3600);
const RENDERS = Number(process.argv[3] || 120);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); });
  const bcdp = await l.browser.newBrowserCDPSession();
  // Per-thread CPU time from /proc: the renderer's main thread (CrRendererMain) only.
  const { processInfo } = await bcdp.send('SystemInfo.getProcessInfo');
  const pids = processInfo.filter((p) => p.type === 'renderer').map((p) => p.id);
  const TICK = 100; // USER_HZ
  const threadTimes = () => {
    const out = {};
    for (const pid of pids) {
      let tids = [];
      try { tids = fs.readdirSync(`/proc/${pid}/task`); } catch { continue; }
      for (const tid of tids) {
        try {
          const st = fs.readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8');
          const name = st.slice(st.indexOf('(') + 1, st.lastIndexOf(')'));
          const f = st.slice(st.lastIndexOf(')') + 2).split(' ');
          const ms = ((+f[11]) + (+f[12])) * (1000 / TICK);
          out[name] = (out[name] || 0) + ms;
        } catch { /* thread gone */ }
      }
    }
    return out;
  };
  const mainTid = () => { let ms = 0; for (const pid of pids) { try { const st = fs.readFileSync(`/proc/${pid}/task/${pid}/stat`, "utf8"); const f = st.slice(st.lastIndexOf(")") + 2).split(" "); ms += ((+f[11]) + (+f[12])) * (1000 / TICK); } catch {} } return ms; };
  const procCpu = async () => { const t = threadTimes(); return { renderer: mainTid() / 1000, all: t }; };
  const diffThreads = (a, b) => Object.entries(b.all).map(([k, v]) => [k, v - (a.all[k] || 0)]).filter(([, v]) => v > 0).sort((x, y) => y[1] - x[1]).slice(0, 8).map(([k, v]) => `${k}=${v.toFixed(0)}ms`).join(' ');
  await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, rv = window.__rv;
    const R = ctx.renderer;
    const noop = () => {};
    rv.noDraw = true;
    g.startBattle(1);
    g.step(120);
    ctx.waves.stop();
    const kinds = ['levy', 'levy', 'sparabara', 'immortal', 'levy'];
    const victims = [];
    for (let i = 0; i < 20; i++) victims.push(g.spawn(kinds[i % 5], -5 + (i % 10) * 1.3, -7 - Math.floor(i / 10) * 2.5));
    g.step(5);
    for (const v of victims) ctx.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 });
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
    rv.drive = (frames) => {
      const saved = [R.render, R.clear, R.clearDepth];
      R.render = noop; R.clear = noop; R.clearDepth = noop;
      try {
        for (let f = 0; f < frames; f += 10) {
          rv.topUp();
          if (ctx.time.gnow - rv.lastVolley > 7) { ctx.combat.volley({ count: 300, duration: 1.2, warn: 1.5 }); rv.lastVolley = ctx.time.gnow; }
          rv.pilotStep();
          g.step(10);
        }
      } finally { R.render = saved[0]; R.clear = saved[1]; R.clearDepth = saved[2]; }
    };
    rv.pilot(true);
    rv.drive(3000);
  });
  // (a) simulation
  let c0 = await procCpu();
  let t0 = Date.now();
  await page.evaluate((n) => window.__rv.drive(n), SIM);
  let c1 = await procCpu();
  const simMs = (c1.renderer - c0.renderer) * 1000;
  console.log('  threads:', diffThreads(c0, c1));
  console.log(`simulation: renderer main-thread CPU ${simMs.toFixed(0)} ms over ${SIM} frames = ${(simMs / SIM).toFixed(3)} ms/frame (wall ${Date.now() - t0} ms)`);
  // (b) rendering (same frame, GPU synced before each render; the sync wait is not CPU time)
  const pop = await page.evaluate(() => { const c = window.__game.ctx; window.__game.render(); return { alive: c.actors.filter((a) => a.alive && a.team === 'persian').length, greeks: c.actors.filter((a) => a.alive && a.team !== 'persian').length, corpses: c.ai.corpses.length, particles: c.fx.particleCount, calls: c.renderer.info.render.calls, tris: c.renderer.info.render.triangles }; });
  // Baseline: the sync alone.
  c0 = await procCpu();
  await page.evaluate((n) => { const gl = window.__game.ctx.renderer.getContext(), px = new Uint8Array(4); for (let i = 0; i < n; i++) gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); }, RENDERS);
  c1 = await procCpu();
  const syncMs = (c1.renderer - c0.renderer) * 1000;
  c0 = await procCpu();
  t0 = Date.now();
  await page.evaluate((n) => { const g = window.__game, gl = g.ctx.renderer.getContext(), px = new Uint8Array(4); for (let i = 0; i < n; i++) { gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); g.render(); } }, RENDERS);
  c1 = await procCpu();
  const renMs = (c1.renderer - c0.renderer) * 1000 - syncMs;
  console.log('  threads:', diffThreads(c0, c1));
  console.log(`render: population ${JSON.stringify(pop)}; renderer main-thread CPU ${renMs.toFixed(0)} ms over ${RENDERS} renders = ${(renMs / RENDERS).toFixed(2)} ms/render (sync-only baseline ${(syncMs / RENDERS).toFixed(2)} ms subtracted; wall ${Date.now() - t0} ms)`);
  const per = simMs / SIM + renMs / RENDERS;
  console.log(`desktop CPU per 60-fps frame ≈ ${per.toFixed(2)} ms → phone ×4: ${(per * 4).toFixed(1)} ms, ×6: ${(per * 6).toFixed(1)} ms (budget 16.7 ms)`);
} finally {
  await l.close();
}
