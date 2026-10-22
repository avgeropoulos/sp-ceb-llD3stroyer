// Review (performance lens): A/B of render main-thread CPU (/proc utime+stime of the renderer's
// main thread, tid == pid; immune to machine load) for freezing settled corpses, in the
// worst-case population (14 enemies, 7 allies, 20 corpses whose death animation has finished).
// Variants: base; freezeSkel (settled corpses' skeleton.update() skipped → no bone-texture
// re-upload); freezeAll (also matrixAutoUpdate=false on the corpse root, mesh and 28 bones after
// one final updateMatrixWorld, so scene.updateMatrixWorld neither recomposes nor re-multiplies).
// Usage: node spartan/tests/e2e/review-perf-corpsefreeze-ab.mjs [rendersPerBlock=30] [rounds=3]
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const N = Number(process.argv[2] || 30);
const ROUNDS = Number(process.argv[3] || 3);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); });
  const bcdp = await l.browser.newBrowserCDPSession();
  const { processInfo } = await bcdp.send('SystemInfo.getProcessInfo');
  const pids = processInfo.filter((p) => p.type === 'renderer').map((p) => p.id);
  const mainMs = () => { let ms = 0; for (const pid of pids) { try { const st = fs.readFileSync(`/proc/${pid}/task/${pid}/stat`, 'utf8'); const f = st.slice(st.lastIndexOf(')') + 2).split(' '); ms += (+f[11] + +f[12]) * 10; } catch { /* gone */ } } return ms; };
  const setup = await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, rv = window.__rv, r = ctx.renderer;
    rv.noDraw = true;
    g.startBattle(1);
    g.step(120);
    ctx.waves.stop();
    const kinds = ['levy', 'levy', 'sparabara', 'immortal', 'levy'];
    const victims = [];
    for (let i = 0; i < 20; i++) victims.push(g.spawn(kinds[i % 5], -5 + (i % 10) * 1.3, -7 - Math.floor(i / 10) * 2.5));
    g.step(5);
    for (const v of victims) ctx.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 });
    for (let i = 0; i < 14; i++) g.spawn(['levy', 'immortal', 'sparabara', 'archer'][i % 4], -5 + i * 0.8, -9 - (i % 3) * 2);
    if (ctx.ai.ensureAllies) ctx.ai.ensureAllies();
    rv.run(600, 10);
    rv.noDraw = false;
    g.step(1);
    const settled = ctx.ai.corpses.filter((c) => c.animDone && !(c.sinkT >= 0));
    const saved = [];
    const freezeSkel = () => { for (const c of settled) { const m = c.visual.mesh; m.skeleton.update(); saved.push([m.skeleton, m.skeleton.update]); m.skeleton.update = () => {}; } };
    const objs = [];
    const freezeAll = () => {
      freezeSkel();
      for (const c of settled) { c.visual.root.updateMatrixWorld(true); c.visual.root.traverse((o) => { if (o.matrixAutoUpdate) { o.matrixAutoUpdate = false; objs.push(o); } }); }
    };
    rv.variant = { base: () => {}, freezeSkel, freezeAll };
    rv.reset = () => { for (const [s, u] of saved) s.update = u; saved.length = 0; for (const o of objs) o.matrixAutoUpdate = true; objs.length = 0; };
    const gl = r.getContext();
    const px = new Uint8Array(4);
    rv.renderN = (k, n) => { rv.reset(); rv.variant[k](); for (let i = 0; i < n; i++) { gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); g.render(); } const calls = r.info.render.calls; rv.reset(); return calls; };
    for (const k in rv.variant) rv.renderN(k, 3);
    let n = 0; ctx.scene.traverse(() => n++);
    return { enemies: ctx.actors.filter((a) => a.alive && a.team === 'persian').length, greeks: ctx.actors.filter((a) => a.alive && a.team !== 'persian').length, corpses: ctx.ai.corpses.length, settled: settled.length, sceneObjects: n };
  });
  console.log('scene:', JSON.stringify(setup));
  const syncA = mainMs();
  await page.evaluate((n) => { const gl = window.__game.ctx.renderer.getContext(), px = new Uint8Array(4); for (let i = 0; i < n; i++) gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); }, N);
  const sync = mainMs() - syncA;
  const names = ['base', 'freezeSkel', 'freezeAll'];
  const acc = Object.fromEntries(names.map((k) => [k, { ms: 0, n: 0, calls: 0 }]));
  for (let r = 0; r < ROUNDS; r++) {
    for (const k of names) {
      const a = mainMs();
      const calls = await page.evaluate(([k, n]) => window.__rv.renderN(k, n), [k, N]);
      acc[k].ms += mainMs() - a - sync;
      acc[k].n += N;
      acc[k].calls = calls;
    }
  }
  const base = acc.base.ms / acc.base.n;
  for (const k of names) {
    const per = acc[k].ms / acc[k].n;
    console.log(`${k.padEnd(14)} main-thread CPU ${per.toFixed(2)} ms/render (${(100 * (per - base) / base).toFixed(0)}% vs base), draw calls ${acc[k].calls}; phone ×4–6: ${(per * 4).toFixed(1)}–${(per * 6).toFixed(1)} ms`);
  }
} finally {
  await l.close();
}
