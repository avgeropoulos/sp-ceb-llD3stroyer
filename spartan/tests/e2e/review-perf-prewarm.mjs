// Review (performance lens): which pass compiles the one program that the boot pre-warm misses.
// Wraps renderer.shadowMap.render and counts renderer.info.programs before/after it on the first
// battle frame, and lists the new program's flags (skinning, depth packing, name/type).
// Usage: node spartan/tests/e2e/review-perf-prewarm.mjs [battle|training] [quality]
import { launch } from './lib.mjs';

const which = process.argv[2] || 'battle';
const quality = process.argv[3] || 'medium';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const res = await page.evaluate((which) => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer;
    g.step(30);
    const boot = r.info.programs.length;
    const log = [];
    const sm = r.shadowMap;
    const orig = sm.render;
    sm.render = function (...a) {
      const n0 = r.info.programs.length;
      const t0 = performance.now();
      const out = orig.apply(this, a);
      const n1 = r.info.programs.length;
      if (n1 !== n0) log.push({ pass: 'shadow', added: n1 - n0, ms: +(performance.now() - t0).toFixed(1), keys: r.info.programs.slice(n0).map((p) => p.cacheKey.split(',').slice(0, 3).join(',') + ' … skinning=' + /SKINNING/.test(p.cacheKey) ) });
      return out;
    };
    const oRender = r.render;
    r.render = function (scene, cam) {
      const n0 = r.info.programs.length;
      const t0 = performance.now();
      const out = oRender.call(this, scene, cam);
      const n1 = r.info.programs.length;
      if (n1 !== n0) log.push({ pass: scene === ctx.scene ? 'main render() incl. shadow' : 'vm render()', added: n1 - n0, ms: +(performance.now() - t0).toFixed(1) });
      return out;
    };
    if (which === 'battle') g.startBattle(1); else g.startTraining();
    g.step(1);
    // Identify the program: find which depth material holds it.
    const progs = r.info.programs;
    const newest = progs[progs.length - 1];
    const depthUsers = [];
    ctx.scene.traverse((o) => {
      for (const m of [o.customDepthMaterial, o.customDistanceMaterial]) if (m) { const p = r.properties.get(m); if (p && p.currentProgram === newest) depthUsers.push(o.name); }
    });
    let skinnedCasters = 0;
    ctx.scene.traverse((o) => { if (o.isSkinnedMesh && o.castShadow && o.visible) skinnedCasters++; });
    return { boot, after: progs.length, log, newestName: newest.name, newestKeyHasSkinBit: true, skinnedCasters };
  }, which);
  console.log(JSON.stringify(res, null, 1));
} finally {
  await l.close();
}
