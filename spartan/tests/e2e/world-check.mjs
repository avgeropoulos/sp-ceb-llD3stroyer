// World slice checks in a real browser (headless Chromium + SwiftShader):
// renderer.info for the main and shadow passes, program count stability across setTimeOfDay and
// the volley shade, groundHeight across the pass and over the sea, colliders vs geometry, and
// texture memory. Usage: node spartan/tests/e2e/world-check.mjs [quality=medium]
import process from 'node:process';
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const l = await launch();
let failed = 0;
const check = (ok, msg) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}`);
  if (!ok) failed++;
};
try {
  const page = await l.newPage({ width: 960, height: 540 });
  await page.goto(`${l.base}/tests/sandbox/world-view.html?manual=1&quality=${quality}`);
  await page.waitForFunction(() => window.__sandbox && window.__worldStats, null, { timeout: 120000 });
  await page.evaluate(() => window.__sandbox.step(2));
  const r = await page.evaluate(() => {
    const ctx = window.__sandbox.ctx;
    const { world, renderer, scene } = ctx;
    const out = {};
    out.stats = window.__worldStats();
    // World meshes and their draw calls (everything under the world root plus the sky).
    let worldMeshes = 0;
    let hordeMeshes = 0;
    const casters = [];
    world.root.traverse((o) => {
      if (!o.isMesh || !o.visible) return;
      if (o.parent && o.parent.name === 'horde') hordeMeshes++;
      else worldMeshes++;
      if (o.castShadow) casters.push(o.name);
    });
    out.worldMeshes = worldMeshes;
    out.hordeMeshes = hordeMeshes;
    out.casters = casters;
    // Program stability.
    const before = renderer.info.programs.length;
    const presets = ['dawn', 'morning', 'noon', 'afternoon', 'dusk'];
    for (const p of presets) {
      world.setTimeOfDay(p);
      ctx.render();
    }
    out.programsBefore = before;
    out.programsAfterTod = renderer.info.programs.length;
    world.setTimeOfDay('morning');
    const sun0 = world.sun.intensity;
    ctx.events.emit('volley', { phase: 'warning', duration: 1 });
    ctx.events.emit('volley', { phase: 'loosed', duration: 1 });
    window.__sandbox.step(90);
    out.shadeSun = world.sun.intensity / sun0;
    out.shadeShadow = world.sun.shadow.intensity;
    out.programsAfterShade = renderer.info.programs.length;
    ctx.events.emit('volley', { phase: 'end', duration: 1 });
    window.__sandbox.step(240);
    out.restoredSun = world.sun.intensity / sun0;
    // Safety: a volley that never ends (mode change) clears on its own.
    ctx.events.emit('volley', { phase: 'loosed', duration: 1 });
    window.__sandbox.step(60 * 6);
    out.autoClear = world.sun.intensity / sun0;
    // Overlapping volleys: the shade holds until the last one ends.
    ctx.events.emit('volley', { phase: 'loosed', duration: 1 });
    window.__sandbox.step(60);
    ctx.events.emit('volley', { phase: 'loosed', duration: 1 });
    window.__sandbox.step(30);
    ctx.events.emit('volley', { phase: 'end', duration: 1 });
    window.__sandbox.step(60);
    out.overlapHeld = world.sun.intensity / sun0;
    ctx.events.emit('volley', { phase: 'end', duration: 1 });
    window.__sandbox.step(240);
    out.overlapEnd = world.sun.intensity / sun0;
    // Army density: training hides it, a day of battle brings it back.
    world.setArmyDensity(0);
    const hidden = world.horde.group.visible;
    ctx.events.emit('dayStart', { day: 1 });
    out.army = { hidden, restored: world.horde.group.visible && world.horde.density === 1, half: (world.setArmyDensity(0.5), world.horde.meshes[0].count) };
    world.setArmyDensity(1);
    // Context-restore path: a fresh PMREM env replaces (and frees) the old one without new programs.
    const envBefore = world.envMap;
    const progs = renderer.info.programs.length;
    world.rebuildEnvironment();
    ctx.render();
    out.rebuild = { changed: world.envMap !== envBefore, isEnv: scene.environment === world.envMap, programs: renderer.info.programs.length - progs };
    // groundHeight.
    const g = world.groundHeight;
    out.ground = {
      line: [-7, -3, 0, 3, 8.4].map((x) => +g(x, 0).toFixed(3)),
      across: [-9, -8, -6, 0, 6, 9, 9.5, 10, 11, 12, 20, 100].map((x) => +g(x, -20).toFixed(2)),
      sea: [g(15, 0), g(40, -100), g(500, 300), g(1000, -1000)],
      wall: g(0, 12),
      far: [g(0, -400), g(0, -1200), g(0, 200)],
      nan: g(NaN, 0),
    };
    // Colliders vs geometry.
    const wallMesh = world.root.getObjectByName('phocianWall');
    wallMesh.geometry.computeBoundingBox();
    const wb = wallMesh.geometry.boundingBox;
    const boxes = world.colliders.filter((c) => c.type === 'box');
    const wallBoxes = boxes.filter((c) => c.min[2] < 12 && c.max[2] > 12 && c.max[1] < 3.5);
    // Sample wall vertices: every vertex above ground must lie inside a wall collider (±4 cm).
    const pos = wallMesh.geometry.attributes.position;
    let outside = 0;
    for (let i = 0; i < pos.count; i += 3) {
      const x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i);
      if (y < g(x, z) + 0.05) continue;
      const inside = wallBoxes.some((b) => x >= b.min[0] - 0.04 && x <= b.max[0] + 0.04 && y <= b.max[1] + 0.04 && z >= b.min[2] - 0.04 && z <= b.max[2] + 0.04);
      if (!inside) outside++;
    }
    out.wall = { bbox: [wb.min.toArray().map((v) => +v.toFixed(2)), wb.max.toArray().map((v) => +v.toFixed(2))], boxes: wallBoxes.map((b) => [b.min, b.max]), outside, gapClear: !wallBoxes.some((b) => b.max[0] > -1.99 && b.min[0] < 1.99) };
    // Cliff plane vs the cliff mesh near the line: mean x of face vertices between 0 and 3 m.
    const cliffMesh = world.root.getObjectByName('cliffNear');
    const cp = cliffMesh.geometry.attributes.position;
    let sum = 0, n = 0, maxX = -1e9;
    for (let i = 0; i < cp.count; i++) {
      const y = cp.getY(i), z = cp.getZ(i);
      if (y > 0 && y < 3 && z > -30 && z < 30) {
        sum += cp.getX(i);
        n++;
        maxX = Math.max(maxX, cp.getX(i));
      }
    }
    out.cliff = { plane: world.colliders[0], meanX: +(sum / n).toFixed(2), maxX: +maxX.toFixed(2) };
    out.rockColliders = boxes.length - wallBoxes.length;
    out.bounds = world.bounds;
    out.envMap = !!world.envMap && world.envMap === scene.environment;
    out.texMB = +(ctx.textures.memoryBytes() / 1048576).toFixed(1);
    out.errors = window.__sandbox.errors.slice();
    return out;
  });
  console.log(JSON.stringify(r.stats));
  console.log('world meshes', r.worldMeshes, 'horde meshes', r.hordeMeshes, 'shadow casters', r.casters.join(','));
  check(r.stats.main.calls <= 35 + 4, `main pass draw calls ${r.stats.main.calls} (world ≤ 35 + horde 4)`);
  check(r.worldMeshes + 1 <= 35, `world meshes + sky = ${r.worldMeshes + 1} ≤ 35`);
  check(r.hordeMeshes <= 4, `horde draws ${r.hordeMeshes} ≤ 4`);
  check(r.stats.shadow.calls <= 45, `shadow pass draw calls ${r.stats.shadow.calls}`);
  check(r.stats.main.triangles <= 350000, `main triangles ${r.stats.main.triangles}`);
  check(r.stats.shadow.triangles <= 100000, `shadow triangles ${r.stats.shadow.triangles}`);
  check(r.programsAfterTod === r.programsBefore, `programs stable across setTimeOfDay: ${r.programsBefore} → ${r.programsAfterTod}`);
  check(r.programsAfterShade === r.programsBefore, `programs stable during volley shade: ${r.programsAfterShade}`);
  check(r.shadeSun < 0.35 && r.shadeShadow < 0.45, `volley shade dims sun ×${r.shadeSun.toFixed(2)}, shadow ${r.shadeShadow.toFixed(2)}`);
  check(Math.abs(r.restoredSun - 1) < 0.02, `sun restored after end: ×${r.restoredSun.toFixed(3)}`);
  check(Math.abs(r.autoClear - 1) < 0.02, `stuck shade clears itself: ×${r.autoClear.toFixed(3)}`);
  check(r.overlapHeld < 0.4 && Math.abs(r.overlapEnd - 1) < 0.02, `overlapping volleys hold the shade (×${r.overlapHeld.toFixed(2)}) until the last ends (×${r.overlapEnd.toFixed(3)})`);
  check(!r.army.hidden && r.army.restored && r.army.half > 0, `army density 0 hides, dayStart restores, 0.5 → ${r.army.half} soldiers`);
  check(r.rebuild.changed && r.rebuild.isEnv && r.rebuild.programs === 0, `rebuildEnvironment swaps the env map with no new programs (${JSON.stringify(r.rebuild)})`);
  console.log('ground', JSON.stringify(r.ground));
  check(r.ground.line.every((h) => Math.abs(h) < 0.3), 'line floor is near 0');
  check(r.ground.sea.every((h) => h === -3), 'groundHeight is −3 over the sea');
  check(Number.isFinite(r.ground.nan), 'groundHeight never returns NaN');
  console.log('wall', JSON.stringify(r.wall));
  check(r.wall.outside === 0, `all wall vertices inside its colliders (${r.wall.outside} outside)`);
  check(r.wall.gapClear, 'wall gap x ∈ [−2, 2] is free of colliders');
  console.log('cliff', JSON.stringify(r.cliff), 'rock colliders', r.rockColliders);
  check(Math.abs(r.cliff.meanX + 9) < 0.6 && r.cliff.maxX < -8.3, 'cliff face base sits on the x = −9 plane');
  check(r.envMap, 'envMap is scene.environment');
  // Every texture, as main.js generates them at boot.
  const all = await page.evaluate(async () => {
    const t = window.__sandbox.ctx.textures;
    const t0 = performance.now();
    await t.generateAll();
    return { mb: +(t.memoryBytes() / 1048576).toFixed(1), ms: Math.round(performance.now() - t0), n: t.names.length };
  });
  console.log('texture memory MB (world only)', r.texMB, '| all', all.n, 'textures:', all.mb, 'MB');
  check(all.mb <= 64, `texture memory for all textures ≤ 64 MB (${all.mb})`);
  const errs = page.errors.concat(r.errors);
  check(errs.length === 0, `no page errors${errs.length ? ': ' + errs.join(' | ') : ''}`);
} finally {
  await l.close();
}
process.exitCode = failed ? 1 : 0;
