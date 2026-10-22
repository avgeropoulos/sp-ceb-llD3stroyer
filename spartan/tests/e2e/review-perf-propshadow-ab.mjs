// Review (performance lens): A/B of render main-thread CPU (/proc utime+stime of the renderer's
// main thread, tid == pid; immune to machine load) with the small props' shadows on vs off, in a
// Day 2 fight after volleys (16 stuck arrows at the cap, several stuck spears).
// Variants: base; noArrowShadow (prop-arrow castShadow=false); noPropShadow (arrows and spears).
// Usage: node spartan/tests/e2e/review-perf-propshadow-ab.mjs [rendersPerBlock=30] [rounds=3]
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
    // Undo the probe's renderBufferDirect wrapper so it adds no cost to the measurement.
    if (r.renderBufferDirect !== Object.getPrototypeOf(r).renderBufferDirect && r.hasOwnProperty('renderBufferDirect')) { /* own property set by the probe */ }
    rv.noDraw = true;
    rv.pilot(true);
    rv.hpScale = 0.6;
    g.startBattle(2);
    for (let k = 0; k < 4; k++) { rv.run(900, 10); ctx.combat.volley({ count: 300, duration: 1.2, warn: 0.5 }); }
    rv.run(600, 10);
    rv.pilot(false);
    rv.noDraw = false;
    g.step(1);
    const props = [];
    ctx.scene.traverse((o) => { if (/^prop-(arrow|dory|persianSpear|sagaris)/.test(o.name)) props.push(o); });
    rv.props = props;
    const set = (re, on) => { for (const o of props) if (re.test(o.name)) o.castShadow = on; };
    rv.variant = { base: () => {}, noArrowShadow: () => set(/^prop-arrow/, false), noPropShadow: () => set(/^prop-/, false) };
    rv.reset = () => set(/^prop-/, true);
    const gl = r.getContext();
    const px = new Uint8Array(4);
    rv.renderN = (k, n) => { rv.reset(); rv.variant[k](); for (let i = 0; i < n; i++) { gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); g.render(); } const calls = r.info.render.calls; rv.reset(); return calls; };
    for (const k in rv.variant) rv.renderN(k, 3);
    const count = {};
    for (const o of props) count[o.name] = (count[o.name] || 0) + 1;
    return { props: count, actors: ctx.actors.length, corpses: ctx.ai.corpses.length };
  });
  console.log('scene:', JSON.stringify(setup));
  const syncA = mainMs();
  await page.evaluate((n) => { const gl = window.__game.ctx.renderer.getContext(), px = new Uint8Array(4); for (let i = 0; i < n; i++) gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); }, N);
  const sync = mainMs() - syncA;
  const names = ['base', 'noArrowShadow', 'noPropShadow'];
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
