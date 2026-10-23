// Characters fixer: regression checks in the real game (review C, render CPU and memory), for what
// the headless unit tests cannot see (they have no canvas, so no atlas and no real visuals):
//   - props (thrown dory, arrow) never share a material with a SkinnedMesh;
//   - the character atlas hands its source textures back (ctx.textures.release) once drawn;
//   - settled corpses are frozen: no matrix recomposes, no skeleton (bone texture) updates;
//   - at most 12 actors cast shadows on medium, and no corpse does;
//   - every body has its own blood-stain seed (the spare bone-texture texel);
//   - no shader program is created per frame once everything has been drawn once.
// --rev=<git rev> serves ai.js, characters.js and characters/*.js from that revision (to see the
// checks fail on the code before the fixes).
// Usage: node spartan/tests/e2e/characters-fix-checks.mjs [quality=medium] [--rev=<rev>]
import { execFileSync } from 'node:child_process';
import { launch } from './lib.mjs';

const args = process.argv.slice(2);
const quality = (args.find((a) => a.startsWith('quality=')) || 'quality=medium').slice(8);
const rev = (args.find((a) => a.startsWith('--rev=')) || '').slice(6);
const l = await launch();
let failed = 0;
const check = (ok, what, detail) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail !== undefined ? ` ${JSON.stringify(detail)}` : ''}`);
  if (!ok) failed++;
};
try {
  const page = await l.newPage({ width: 640, height: 288 });
  if (rev) {
    const OWN = /\/js\/(game\/ai\.js|gfx\/characters\.js|gfx\/characters\/[a-z]+\.js)$/;
    await page.route(OWN, async (route) => {
      const rel = new URL(route.request().url()).pathname.replace(/^\//, '');
      const body = execFileSync('git', ['show', `${rev}:spartan/${rel}`], { encoding: 'utf8', maxBuffer: 1 << 26 });
      await route.fulfill({ status: 200, contentType: 'text/javascript', body });
    });
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const r = await page.evaluate(async () => {
    const g = window.__game, c = g.ctx;
    const BLOOD_SEED_TEXEL = (await import('./js/gfx/characters/atlas.js')).BLOOD_SEED_TEXEL || 28 * 4;
    g.startBattle(1);
    g.step(60);
    c.waves.stop();
    const victims = [];
    const kinds = ['levy', 'sparabara', 'immortal', 'levy', 'captain', 'levy'];
    for (let i = 0; i < 12; i++) victims.push(g.spawn(kinds[i % kinds.length], -4 + (i % 6) * 1.6, -5 - Math.floor(i / 6) * 2.2));
    for (let i = 0; i < 14; i++) g.spawn(kinds[i % kinds.length], -5 + (i % 7) * 1.5, -9 - Math.floor(i / 7) * 2);
    g.step(10);
    for (const v of victims) c.combat.actorStrike({ alive: true, pos: { x: v.pos.x, y: 0, z: v.pos.z + 1 }, facing: Math.PI }, { target: v, damage: 9999, reach: 2 });
    // props, as the combat code makes them (a thrown dory, an arrow)
    const props = ['dory', 'arrow', 'persianSpear'].map((n, i) => {
      const p = c.characters.createProp(n);
      p.position.set(-1 + i, 0.4, -3);
      c.scene.add(p);
      return p;
    });
    for (let i = 0; i < 300; i++) { g.step(1); if (i % 30 === 0) g.render(); }
    g.render();
    const programs0 = c.renderer.info.programs.length;
    for (let i = 0; i < 20; i++) { g.step(1); g.render(); }
    const programs1 = c.renderer.info.programs.length;

    // materials shared between skinned and plain meshes
    const skinnedMats = new Set(), plainMats = new Set();
    c.scene.traverse((o) => {
      if (!o.isMesh || o.isInstancedMesh) return;
      const ms = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of ms) (o.isSkinnedMesh ? skinnedMats : plainMats).add(m);
    });
    const shared = [...plainMats].filter((m) => skinnedMats.has(m)).map((m) => m.name || m.type);
    const propShared = props.flatMap((p) => (Array.isArray(p.material) ? p.material : [p.material])).filter((m) => skinnedMats.has(m)).map((m) => m.name);

    // atlas sources still in memory
    const SOURCES = ['bronze', 'crimson', 'felt', 'leather', 'linen', 'persianRobeA', 'persianRobeB', 'persianRobeC', 'scale', 'skinGreek', 'skinPersian', 'wicker', 'wood',
      'blazonGorgon', 'blazonLion', 'blazonBoar', 'blazonClub', 'blazonScorpion'];
    const held = c.textures.release ? SOURCES.filter((n) => c.textures.has(n)) : null;

    // corpses
    const corpses = c.ai.corpses;
    const settled = corpses.filter((k) => k.animDone && k.sinkT < 0 && k.fall === 0 && k.kbx === 0 && k.kbz === 0 && k.nudgeT >= 1);
    const SkelUpdate = Object.getPrototypeOf(settled[0] ? settled[0].visual.mesh.skeleton : {}).update;
    const badFrozen = settled.filter((k) => {
      const v = k.visual, m = v.mesh;
      return !k.frozen || v.root.matrixAutoUpdate || m.matrixAutoUpdate || m.skeleton.bones.some((b) => b.matrixAutoUpdate) || m.skeleton.update === SkelUpdate;
    }).length;
    // a frozen body's bones keep their world matrices (the pose it settled in)
    const k0 = settled[0];
    let drift = 0;
    if (k0) {
      const before = k0.visual.mesh.skeleton.bones.map((b) => b.matrixWorld.clone());
      for (let i = 0; i < 10; i++) { g.step(1); g.render(); }
      k0.visual.mesh.skeleton.bones.forEach((b, i) => { for (let j = 0; j < 16; j++) drift = Math.max(drift, Math.abs(b.matrixWorld.elements[j] - before[i].elements[j])); });
    }

    // shadow casters
    let casters = 0, corpseCasters = 0;
    for (const a of c.actors) if (a.visual.mesh && a.visual.mesh.castShadow) casters++;
    for (const k of corpses) if (k.visual.mesh && k.visual.mesh.castShadow) corpseCasters++;

    // blood seeds
    const seeds = c.actors.concat(corpses).filter((a) => a.visual.mesh && a.visual.mesh.skeleton).map((a) => a.visual.mesh.skeleton.boneMatrices[BLOOD_SEED_TEXEL * 4]);
    const unique = new Set(seeds.map((s) => s.toFixed(4))).size;

    return {
      programs0, programs1, shared, propShared, held, corpses: corpses.length, settled: settled.length, badFrozen, drift,
      casters, corpseCasters, alive: c.actors.filter((a) => a.alive).length, seeds: seeds.length, unique, errors: g.errors.slice(0, 5),
    };
  });
  check(r.propShared.length === 0 && r.shared.length === 0, 'no material is drawn by both a SkinnedMesh and a plain mesh', { props: r.propShared, all: r.shared });
  check(r.held === null || r.held.length === 0, 'the atlas released its source textures', r.held);
  check(r.settled >= 8 && r.badFrozen === 0, 'settled corpses are frozen (no matrix or skeleton updates)', { corpses: r.corpses, settled: r.settled, notFrozen: r.badFrozen });
  check(r.drift === 0, 'a frozen corpse keeps its pose', { drift: r.drift });
  check(r.casters <= (quality === 'low' ? 6 : 12) && r.corpseCasters === 0, 'shadow casters capped, no corpse casts', { casters: r.casters, alive: r.alive, corpseCasters: r.corpseCasters });
  check(r.unique >= r.seeds - 1, 'every body has its own blood seed', { bodies: r.seeds, distinct: r.unique });
  check(r.programs1 === r.programs0, 'no shader program created per frame', { before: r.programs0, after: r.programs1 });
  check(r.errors.length === 0 && page.errors.length === 0, 'no runtime errors', r.errors.concat(page.errors).slice(0, 5));
} finally {
  await l.close();
}
console.log(failed ? `${failed} FAILED` : 'all characters checks passed');
process.exit(failed ? 1 : 0);
