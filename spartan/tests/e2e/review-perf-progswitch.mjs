// Review (performance lens): program re-resolution per frame caused by materials shared between
// SkinnedMesh actors and plain-Mesh props (thrown/stuck spears, stuck arrows, dropped weapons).
// three.js r186 setProgram() sees `!object.isSkinnedMesh && materialProperties.skinning === true`
// (or the reverse) and runs getProgram() — getParameters + getProgramCacheKey + uniform list
// rebuild — every time the draw order alternates between the two. Counts those switches in one
// frame and times renderer CPU before/after giving props their own material clones (test-only).
// Usage: node spartan/tests/e2e/review-perf-progswitch.mjs
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const res = await page.evaluate(async () => {
    const g = window.__game;
    const ctx = g.ctx;
    const r = ctx.renderer;
    const rv = window.__rv;
    rv.noDraw = true;
    rv.pilot(true);
    rv.hpScale = 0.6;
    g.startBattle(2);
    rv.run(5400, 10); // 90 s: spears thrown, arrows stuck, bodies down
    rv.noDraw = false;
    g.step(1);
    // Count program re-resolutions in one frame.
    const orig = r.renderBufferDirect;
    let switches = 0;
    let draws = 0;
    const who = {};
    const count = function (camera, scene, geometry, material, object, group) {
      if (scene !== null) {
        draws++;
        const mp = r.properties.get(material);
        if (mp.currentProgram !== undefined) {
          const skinFlip = (object.isSkinnedMesh === true) !== (mp.skinning === true);
          const instFlip = (object.isInstancedMesh === true) !== (mp.instancing === true);
          if (skinFlip || instFlip) {
            switches++;
            const k = `${material.name || material.type} on ${object.name}${skinFlip ? ' (skinning flip)' : ' (instancing flip)'}`;
            who[k] = (who[k] || 0) + 1;
          }
        }
      }
      return orig.apply(this, arguments);
    };
    r.renderBufferDirect = count;
    g.render();
    r.renderBufferDirect = orig;
    // Shared-material props in the scene.
    const props = [];
    ctx.scene.traverse((o) => {
      if (o.isMesh && !o.isSkinnedMesh) {
        const mats = Array.isArray(o.material) ? o.material : [o.material];
        if (mats.some((m) => /^chr-/.test(m.name))) props.push(o.name);
      }
    });
    const propCounts = {};
    for (const n of props) propCounts[n] = (propCounts[n] || 0) + 1;

    // Time render CPU (sync GPU before each timed frame), A/B: shared vs cloned prop materials.
    const gl = r.getContext();
    const px = new Uint8Array(4);
    const timeRender = (n) => {
      const ts = [];
      for (let i = 0; i < n; i++) {
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        const t0 = performance.now();
        g.render();
        ts.push(performance.now() - t0);
      }
      ts.sort((a, b) => a - b);
      return { median: ts[Math.floor(n / 2)], min: ts[0] };
    };
    timeRender(10);
    const before = timeRender(40);
    // Test-only fix: give every non-skinned prop its own (shared) clone of the chr materials.
    const clones = new Map();
    const cl = (m) => { if (!/^chr-/.test(m.name)) return m; let c = clones.get(m); if (!c) { c = m.clone(); c.name = m.name + '-prop'; clones.set(m, c); } return c; };
    ctx.scene.traverse((o) => {
      if (o.isMesh && !o.isSkinnedMesh) o.material = Array.isArray(o.material) ? o.material.map(cl) : cl(o.material);
    });
    timeRender(10);
    let switches2 = 0;
    r.renderBufferDirect = function (camera, scene, geometry, material, object) {
      if (scene !== null) {
        const mp = r.properties.get(material);
        if (mp.currentProgram !== undefined && (((object.isSkinnedMesh === true) !== (mp.skinning === true)) || ((object.isInstancedMesh === true) !== (mp.instancing === true)))) switches2++;
      }
      return orig.apply(this, arguments);
    };
    g.render();
    r.renderBufferDirect = orig;
    const after = timeRender(40);
    return { draws, switches, who, propCounts, stuckSpears: ctx.combat.stuckSpears.length, corpses: ctx.ai.corpses.length, actors: ctx.actors.length, before, after, switches2, programs: r.info.programs.length };
  });
  console.log(JSON.stringify(res, null, 1));
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log('errors:', errs.length, errs.slice(0, 3).join('\n'));
} finally {
  await l.close();
}
