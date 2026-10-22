// Review (performance lens): A/B of render-CPU fixes, measured as renderer main-thread CPU time
// (/proc per-thread utime+stime, immune to machine load). Same worst-case population as
// review-perf-cputime.mjs. Variants: base, freeze (settled corpses skip skeleton/matrix updates),
// propMats (props get their own material clones), shadow12 (12 nearest actors cast shadows),
// all (the three together). Original header follows:
// renderer-process CPU time (CDP SystemInfo.getProcessInfo, immune to
// machine load) per simulated frame and per rendered frame, in the worst-case population
// (14 enemies, 7 allies + helot, 20 corpses, particles, volleys every 7 s).
// Usage: node spartan/tests/e2e/review-perf-render-ab-cpu.mjs [rendersPerBlock=40] [rounds=3]
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const N = Number(process.argv[2] || 40);
const ROUNDS = Number(process.argv[3] || 3);
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
  const procCpu = async () => { const t = threadTimes(); return { renderer: (t.CrRendererMain || 0) / 1000, all: t }; };
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

  await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, rv = window.__rv;
    rv.pilot(false);
    rv.topUp();
    ctx.combat.volley({ count: 300, duration: 1.2, warn: 0.3 });
    rv.drive(120);
    const corpses = ctx.ai.corpses;
    const frozen = [];
    const freeze = (on) => {
      if (on) {
        for (const c of corpses) {
          if (!c.animDone || c.sinkT >= 0) continue;
          const m = c.visual.mesh;
          m.skeleton.update();
          frozen.push({ m, upd: m.skeleton.update, root: c.visual.root });
          m.skeleton.update = () => {};
          c.visual.root.updateMatrixWorld(true);
          c.visual.root.matrixWorldAutoUpdate = false;
        }
      } else { for (const s of frozen) { s.m.skeleton.update = s.upd; s.root.matrixWorldAutoUpdate = true; } frozen.length = 0; }
    };
    const propSaved = [];
    const clones = new Map();
    const cl = (m) => { if (!/^chr-/.test(m.name)) return m; let c = clones.get(m); if (!c) { c = m.clone(); c.onBeforeCompile = m.onBeforeCompile; c.customProgramCacheKey = m.customProgramCacheKey.bind(m); c.userData = m.userData; c.name = m.name + '-prop'; clones.set(m, c); } return c; };
    const propMats = (on) => {
      if (on) ctx.scene.traverse((o) => { if (o.isMesh && !o.isSkinnedMesh && o.material) { const mm = Array.isArray(o.material) ? o.material : [o.material]; if (mm.some((m) => /^chr-/.test(m.name))) { propSaved.push([o, o.material]); o.material = Array.isArray(o.material) ? o.material.map(cl) : cl(o.material); } } });
      else { for (const [o, m] of propSaved) o.material = m; propSaved.length = 0; }
    };
    const shSaved = [];
    const shadow12 = (on) => {
      if (on) {
        const cam = ctx.camera.position;
        const living = ctx.actors.filter((a) => a.alive && a.visual.mesh.castShadow);
        living.sort((a, b) => (a.pos.x - cam.x) ** 2 + (a.pos.z - cam.z) ** 2 - ((b.pos.x - cam.x) ** 2 + (b.pos.z - cam.z) ** 2));
        for (let i = 12; i < living.length; i++) { shSaved.push(living[i].visual.mesh); living[i].visual.mesh.castShadow = false; }
      } else { for (const m of shSaved) m.castShadow = true; shSaved.length = 0; }
    };
    const all = (on) => { freeze(on); propMats(on); shadow12(on); };
    rv.variants = { base: () => {}, freeze, propMats, shadow12, all };
    const gl = ctx.renderer.getContext();
    const px = new Uint8Array(4);
    rv.renderN = (k, n, on) => { rv.variants[k](true); for (let i = 0; i < n; i++) { gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); g.render(); } const calls = ctx.renderer.info.render.calls; rv.variants[k](false); return calls; };
    for (const k in rv.variants) rv.renderN(k, 3); // warm (compiles clone programs once)
  });
  const names = ['base', 'freeze', 'propMats', 'shadow12', 'all'];
  const acc = Object.fromEntries(names.map((k) => [k, { ms: 0, n: 0, calls: 0 }]));
  const syncBase = async () => { const a = threadTimes(); await page.evaluate((n) => { const gl = window.__game.ctx.renderer.getContext(), px = new Uint8Array(4); for (let i = 0; i < n; i++) gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); }, N); const b = threadTimes(); return (b['headless_shell.'] || 0) - (a['headless_shell.'] || 0); };
  const sb = await syncBase();
  for (let r = 0; r < ROUNDS; r++) {
    for (const k of names) {
      const a = threadTimes();
      const calls = await page.evaluate(([k, n]) => window.__rv.renderN(k, n), [k, N]);
      const b = threadTimes();
      acc[k].ms += (b['headless_shell.'] || 0) - (a['headless_shell.'] || 0) - sb;
      acc[k].n += N;
      acc[k].calls = calls;
    }
  }
  const base = acc.base.ms / acc.base.n;
  console.log(`sync-only baseline ${(sb / N).toFixed(2)} ms/readPixels (subtracted)`);
  for (const k of names) {
    const per = acc[k].ms / acc[k].n;
    console.log(`${k.padEnd(9)} main-thread CPU ${per.toFixed(2)} ms/render (${(100 * (per - base) / base).toFixed(0)}% vs base), draw calls ${acc[k].calls}; phone ×4–6: ${(per * 4).toFixed(1)}–${(per * 6).toFixed(1)} ms`);
  }
} finally {
  await l.close();
}
