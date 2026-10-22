// Review (performance lens): relative GPU cost of each pass/feature, using SwiftShader (a software
// GPU) as a proxy: CPU time of ALL threads of Chromium's GPU process per rendered frame, from
// /proc (immune to machine load), for the same battle frame at 960x540 with medium settings,
// toggling one feature at a time. Absolute numbers mean nothing for a phone; the shares do
// (fragment work × shader cost, vertex work).
// Usage: node spartan/tests/e2e/review-perf-gpucost.mjs [frames=12] [rounds=2]
import fs from 'node:fs';
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const N = Number(process.argv[2] || 12);
const ROUNDS = Number(process.argv[3] || 2);
const l = await launch();
try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const bcdp = await l.browser.newBrowserCDPSession();
  const { processInfo } = await bcdp.send('SystemInfo.getProcessInfo');
  const gpuPids = processInfo.filter((p) => p.type === 'GPU' || p.type === 'gpu-process' || p.type === 'gpu').map((p) => p.id);
  const procMs = () => {
    let ms = 0;
    for (const pid of gpuPids) {
      let tids = [];
      try { tids = fs.readdirSync(`/proc/${pid}/task`); } catch { continue; }
      for (const tid of tids) { try { const st = fs.readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8'); const f = st.slice(st.lastIndexOf(')') + 2).split(' '); ms += (+f[11] + +f[12]) * 10; } catch { /* gone */ } }
    }
    return ms;
  };
  console.log('gpu pids', gpuPids, processInfo.map((p) => p.type).join(','));
  await page.evaluate(() => {
    const g = window.__game, ctx = g.ctx, rv = window.__rv, r = ctx.renderer;
    rv.noDraw = true;
    rv.pilot(true);
    rv.hpScale = 0.6;
    g.startBattle(1);
    rv.run(1800, 10);
    ctx.combat.volley({ count: 300, duration: 1.2, warn: 0.3 });
    rv.run(100, 10);
    rv.pilot(false);
    rv.noDraw = false;
    g.step(1);
    let overlay = null;
    ctx.vm.scene.traverse((o) => { if (o.name === 'vmOverlay') overlay = o; });
    const transp = [];
    ctx.scene.traverse((o) => { if (o.material && !Array.isArray(o.material) && o.material.transparent) transp.push(o); });
    const sky = ctx.scene.getObjectByName('sky');
    const vmMeshes = [];
    ctx.vm.scene.traverse((o) => { if ((o.isMesh) && o.name !== 'vmOverlay') vmMeshes.push(o); });
    const skinned = [];
    ctx.scene.traverse((o) => { if (o.isSkinnedMesh) skinned.push(o); });
    const vis = (list, on) => { for (const o of list) o.visible = on; };
    rv.gv = {
      base: [() => {}, () => {}],
      noOverlay: [() => { overlay.visible = false; }, () => { overlay.visible = true; }],
      noShadowPass: [() => { r.shadowMap.autoUpdate = false; r.shadowMap.needsUpdate = false; }, () => { r.shadowMap.autoUpdate = true; }],
      noTransparent: [() => vis(transp, false), () => vis(transp, true)],
      noSky: [() => { if (sky) sky.visible = false; }, () => { if (sky) sky.visible = true; }],
      noVmPass: [() => vis(vmMeshes, false), () => vis(vmMeshes, true)],
      noCharacters: [() => vis(skinned, false), () => vis(skinned, true)],
    };
    const gl = r.getContext();
    const px = new Uint8Array(4);
    rv.gpuN = (k, n) => { rv.gv[k][0](); for (let i = 0; i < n; i++) { g.render(); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); } rv.gv[k][1](); };
    for (const k in rv.gv) rv.gpuN(k, 2);
    return true;
  });
  const names = await page.evaluate(() => Object.keys(window.__rv.gv));
  const acc = Object.fromEntries(names.map((k) => [k, { ms: 0, n: 0 }]));
  for (let r = 0; r < ROUNDS; r++) {
    for (const k of names) {
      const a = procMs();
      await page.evaluate(([k, n]) => window.__rv.gpuN(k, n), [k, N]);
      acc[k].ms += procMs() - a;
      acc[k].n += N;
    }
  }
  const base = acc.base.ms / acc.base.n;
  console.log(`SwiftShader GPU-process CPU per frame at 960x540, MSAA on (medium): base ${base.toFixed(0)} ms`);
  for (const k of names) {
    const per = acc[k].ms / acc[k].n;
    console.log(`  ${k.padEnd(14)} ${per.toFixed(0).padStart(5)} ms/frame  → feature share ≈ ${(100 * (base - per) / base).toFixed(0)}%`);
  }
} finally {
  await l.close();
}
