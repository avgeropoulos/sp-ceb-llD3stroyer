// Characters fixer: A/B of render main-thread CPU in the big-fight population of
// review-perf-cputime3.mjs (13–14 enemies, 7 allies, ~20 corpses, particles, volleys), with the
// characters files (ai.js, characters.js, characters/*.js) served from a git revision (A) or the
// working tree (B). One page at a time, alternating A/B rounds; CPU is the renderer main thread's
// utime+stime from /proc (immune to machine load). Also reports draw calls, shadow casters and
// frozen corpses.
// Usage: node spartan/tests/e2e/characters-fix-perf.mjs [--rev=7f85c80] [renders=60] [rounds=2] [quality=medium]
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const args = process.argv.slice(2);
const rev = (args.find((a) => a.startsWith('--rev=')) || '--rev=7f85c80').slice(6);
const num = (k, d) => Number((args.find((a) => a.startsWith(k + '=')) || `${k}=${d}`).split('=')[1]);
const RENDERS = num('renders', 60);
const ROUNDS = num('rounds', 2);
const quality = (args.find((a) => a.startsWith('quality=')) || 'quality=medium').slice(8);

const l = await launch();
const bcdp = await l.browser.newBrowserCDPSession();
const rendererPids = async () => (await bcdp.send('SystemInfo.getProcessInfo')).processInfo.filter((p) => p.type === 'renderer').map((p) => p.id);
const mainMs = (pids) => {
  let ms = 0;
  for (const pid of pids) {
    try {
      const st = fs.readFileSync(`/proc/${pid}/task/${pid}/stat`, 'utf8');
      const f = st.slice(st.lastIndexOf(')') + 2).split(' ');
      ms += (+f[11] + +f[12]) * 10;
    } catch { /* gone */ }
  }
  return ms;
};

async function measure(variant) {
  const page = await l.newPage({ width: 320, height: 180 });
  if (variant === 'A') {
    const OWN = /\/js\/(game\/ai\.js|gfx\/characters\.js|gfx\/characters\/[a-z]+\.js)$/;
    await page.route(OWN, async (route) => {
      const rel = new URL(route.request().url()).pathname.replace(/^\//, '');
      const body = execFileSync('git', ['show', `${rev}:spartan/${rel}`], { encoding: 'utf8', maxBuffer: 1 << 26 });
      await route.fulfill({ status: 200, contentType: 'text/javascript', body });
    });
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); });
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
    rv.pilot(false);
    rv.topUp();
    rv.drive(60);
  });
  const pids = await rendererPids();
  // simulation (rendering stubbed), then rendering of the same frame
  let a = mainMs(pids);
  await page.evaluate(() => window.__rv.drive(1200));
  const simMs = (mainMs(pids) - a) / 1200;
  const pop = await page.evaluate(() => {
    const g = window.__game, c = g.ctx;
    g.render();
    let casters = 0;
    for (const x of c.actors) if (x.visual.mesh && x.visual.mesh.castShadow) casters++;
    return {
      alive: c.actors.filter((x) => x.alive && x.team === 'persian').length, greeks: c.actors.filter((x) => x.alive && x.team !== 'persian').length,
      corpses: c.ai.corpses.length, frozen: c.ai.corpses.filter((x) => x.frozen).length, casters,
      particles: c.fx.particleCount, calls: c.renderer.info.render.calls, tris: c.renderer.info.render.triangles, programs: c.renderer.info.programs.length,
    };
  });
  a = mainMs(pids);
  await page.evaluate((n) => { const g = window.__game; for (let i = 0; i < n; i++) g.render(); const gl = g.ctx.renderer.getContext(), px = new Uint8Array(4); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); }, RENDERS);
  const renMs = (mainMs(pids) - a) / RENDERS;
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  await page.context().close();
  return { simMs, renMs, pop, errs };
}

try {
  const acc = { A: [], B: [] };
  for (let r = 0; r < ROUNDS; r++) {
    for (const v of r % 2 ? ['B', 'A'] : ['A', 'B']) {
      const res = await measure(v);
      acc[v].push(res);
      console.log(`${v} round ${r + 1}: sim ${res.simMs.toFixed(3)} ms/frame, render ${res.renMs.toFixed(2)} ms/render, ${JSON.stringify(res.pop)}`);
      if (res.errs.length) console.log(res.errs.slice(0, 4).join('\n'));
    }
  }
  const mean = (xs, k) => xs.reduce((s, x) => s + x[k], 0) / xs.length;
  const rA = mean(acc.A, 'renMs'), rB = mean(acc.B, 'renMs');
  const sA = mean(acc.A, 'simMs'), sB = mean(acc.B, 'simMs');
  console.log(`A (${rev}): render ${rA.toFixed(2)} ms, sim ${sA.toFixed(3)} ms`);
  console.log(`B (working tree): render ${rB.toFixed(2)} ms (${((100 * (rB - rA)) / rA).toFixed(0)}%), sim ${sB.toFixed(3)} ms (${((100 * (sB - sA)) / sA).toFixed(0)}%)`);
} finally {
  await l.close();
}
