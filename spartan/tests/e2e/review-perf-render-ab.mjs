// Review (performance lens): renderer CPU cost breakdown and A/B of three candidate fixes, in the
// worst-case population (14 enemies, 7 allies + helot, 20 corpses, particles, a volley in flight).
// Each timed render follows a 1-px readPixels so SwiftShader's backlog is not timed. Variants are
// interleaved sample by sample so machine load affects them equally; medians are reported.
//   base      as shipped
//   freeze    settled corpses: skeleton.update skipped, bones/root matrices frozen
//   propMats  props (spears/arrows) get their own material clones (no skinning flip-flop)
//   shadow8   only the 8 nearest living actors cast shadows
// Also times Skeleton.update and scene.updateMatrixWorld inside the base render.
// Usage: node spartan/tests/e2e/review-perf-render-ab.mjs [samples=60]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const SAMPLES = Number(process.argv[2] || 60);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  await page.evaluate(async () => { await window.__game.ctx.audio.prerender(); });
  const res = await page.evaluate((SAMPLES) => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer, rv = window.__rv, THREE = ctx.THREE;
    rv.noDraw = true;
    g.startBattle(1);
    g.step(120);
    ctx.waves.stop();
    const kinds = ['levy', 'levy', 'sparabara', 'immortal', 'levy'];
    const victims = [];
    for (let i = 0; i < 20; i++) victims.push(g.spawn(kinds[i % 5], -5 + (i % 10) * 1.3, -7 - Math.floor(i / 10) * 2.5));
    g.step(5);
    for (const v of victims) ctx.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 });
    g.step(240);
    const mix = ['levy', 'sparabara', 'immortal', 'levy', 'captain', 'immortal'];
    for (let i = 0; i < 12; i++) g.spawn(mix[i % mix.length], -5 + i * 0.9, -3.5 - (i % 3) * 1.2);
    g.spawn('archer', -2, -17); g.spawn('archer', 3, -17);
    rv.pilot(true);
    rv.run(600, 10);
    // A few thrown spears stuck in the ground and arrows on shields.
    for (let i = 0; i < 4; i++) ctx.combat.throwSpear({ origin: { x: -3 + i * 2, y: 1.6, z: 0 }, velocity: { x: 0, y: 2, z: -9 }, power: 0.6, byPlayer: false });
    rv.run(240, 10);
    ctx.combat.volley({ count: 300, duration: 1.2, warn: 0.3 });
    rv.pilot(false);
    // Top the living up to 14 Persians and 7 allies + the helot right before measuring.
    let n = ctx.actors.filter((a) => a.alive && a.team === 'persian').length;
    for (let i = 0; n < 14; i++, n++) g.spawn(mix[i % mix.length], -5 + (i % 8) * 1.3, -2.8 - Math.floor(i / 8) * 1.4);
    ctx.ai.ensureAllies();
    if (!ctx.actors.some((a) => a.role === 'helot' && a.alive)) ctx.ai.spawnHelot();
    rv.run(150, 10); // arrows in flight now
    const pop = { enemies: ctx.actors.filter((a) => a.alive && a.team === 'persian').length, greeks: ctx.actors.filter((a) => a.alive && a.team !== 'persian').length, corpses: ctx.ai.corpses.length, settled: ctx.ai.corpses.filter((c) => c.animDone).length, stuckSpears: ctx.combat.stuckSpears.length, particles: ctx.fx.particleCount };
    // Freeze time so every variant renders the identical frame.
    const gl = r.getContext();
    const px = new Uint8Array(4);
    // ---- instrumentation of Skeleton.update / updateMatrixWorld ----
    let skelMs = 0, skelN = 0, umwMs = 0;
    const SU = THREE.Skeleton.prototype.update;
    THREE.Skeleton.prototype.update = function () { const t0 = performance.now(); const x = SU.call(this); skelMs += performance.now() - t0; skelN++; return x; };
    const UMW = ctx.scene.updateMatrixWorld;
    ctx.scene.updateMatrixWorld = function (f) { const t0 = performance.now(); const x = UMW.call(this, f); umwMs += performance.now() - t0; return x; };
    // ---- variants ----
    const corpses = ctx.ai.corpses;
    const frozen = [];
    const freeze = (on) => {
      if (on) {
        for (const c of corpses) {
          if (!c.animDone || c.sinkT >= 0) continue;
          const m = c.visual.mesh;
          m.skeleton.update();
          const saved = { m, upd: m.skeleton.update, root: c.visual.root };
          m.skeleton.update = () => {};
          c.visual.root.updateMatrixWorld(true);
          c.visual.root.matrixWorldAutoUpdate = false;
          frozen.push(saved);
        }
      } else {
        for (const s of frozen) { s.m.skeleton.update = s.upd; s.root.matrixWorldAutoUpdate = true; }
        frozen.length = 0;
      }
    };
    const propSaved = [];
    const clones = new Map();
    const cl = (m) => { if (!/^chr-/.test(m.name)) return m; let c = clones.get(m); if (!c) { c = m.clone(); c.onBeforeCompile = m.onBeforeCompile; c.customProgramCacheKey = m.customProgramCacheKey.bind(m); c.userData = m.userData; c.name = m.name + '-prop'; clones.set(m, c); } return c; };
    const propMats = (on) => {
      if (on) ctx.scene.traverse((o) => { if (o.isMesh && !o.isSkinnedMesh && o.material) { const mm = Array.isArray(o.material) ? o.material : [o.material]; if (mm.some((m) => /^chr-/.test(m.name))) { propSaved.push([o, o.material]); o.material = Array.isArray(o.material) ? o.material.map(cl) : cl(o.material); } } });
      else { for (const [o, m] of propSaved) o.material = m; propSaved.length = 0; }
    };
    const shSaved = [];
    const shadow8 = (on) => {
      if (on) {
        const cam = ctx.camera.position;
        const living = ctx.actors.filter((a) => a.alive && a.visual.mesh.castShadow);
        living.sort((a, b) => (a.pos.x - cam.x) ** 2 + (a.pos.z - cam.z) ** 2 - ((b.pos.x - cam.x) ** 2 + (b.pos.z - cam.z) ** 2));
        for (let i = 8; i < living.length; i++) { shSaved.push(living[i].visual.mesh); living[i].visual.mesh.castShadow = false; }
      } else { for (const m of shSaved) m.castShadow = true; shSaved.length = 0; }
    };
    const variants = { base: () => {}, freeze, propMats, shadow8 };
    const times = {}, calls = {};
    for (const k in variants) { times[k] = []; }
    const one = () => { gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); const t0 = performance.now(); g.render(); return performance.now() - t0; };
    // Warm every variant (compiles the clone programs once).
    for (const k in variants) { variants[k](true); one(); one(); calls[k] = r.info.render.calls; variants[k](false); }
    let baseSkel = 0, baseUmw = 0, baseN = 0;
    for (let s = 0; s < SAMPLES; s++) {
      for (const k in variants) {
        variants[k](true);
        skelMs = 0; umwMs = 0; skelN = 0;
        times[k].push(one());
        if (k === 'base') { baseSkel += skelMs; baseUmw += umwMs; baseN++; }
        variants[k](false);
      }
    }
    THREE.Skeleton.prototype.update = SU;
    ctx.scene.updateMatrixWorld = UMW;
    const med = (a) => { const s = a.slice().sort((x, y) => x - y); return +s[Math.floor(s.length / 2)].toFixed(2); };
    const p25 = (a) => { const s = a.slice().sort((x, y) => x - y); return +s[Math.floor(s.length / 4)].toFixed(2); };
    const out = {};
    for (const k in times) out[k] = { median: med(times[k]), p25: p25(times[k]), calls: calls[k] };
    return { pop, out, skeletonUpdateMsPerFrame: +(baseSkel / baseN).toFixed(3), skeletonUpdatesPerFrame: skelN, sceneUpdateMatrixWorldMsPerFrame: +(baseUmw / baseN).toFixed(3) };
  }, SAMPLES);
  console.log(JSON.stringify(res, null, 1));
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
