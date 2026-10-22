// Review (performance lens): what scene.updateMatrixWorld() walks every frame. Counts Object3Ds
// with matrixAutoUpdate (recomposed every frame) by owner in the worst-case battle population,
// how many belong to corpses whose death animation has finished (static, yet recomposed and their
// skeletons re-uploaded every frame), and the skinned meshes whose bone texture is uploaded
// every drawn frame.
// Usage: node spartan/tests/e2e/review-perf-scenegraph.mjs
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const res = await page.evaluate(() => {
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
    for (let i = 0; i < 14; i++) g.spawn(['levy', 'immortal', 'sparabara'][i % 3], -5 + i * 0.8, -9 - (i % 3));
    if (ctx.ai.ensureAllies) ctx.ai.ensureAllies();
    rv.run(600, 10);
    rv.noDraw = false;
    g.step(1);
    rv.refreshActors();
    const by = {};
    let total = 0, auto = 0;
    const settled = new Set();
    for (const c of ctx.ai.corpses) if (c.animDone && !(c.sinkT >= 0)) settled.add(c.visual.root);
    let settledObjs = 0;
    const inSettled = (o) => { let p = o; while (p) { if (settled.has(p)) return true; p = p.parent; } return false; };
    ctx.scene.traverse((o) => {
      total++;
      if (!o.matrixAutoUpdate) return;
      auto++;
      let owner = rv.ownerOf(o);
      if (o.isBone) owner = 'bones of ' + owner.replace(/^(actor|corpse|dead):(greek:|persian:)?/, '$1:');
      else owner = owner.replace(/^(actor|corpse|dead):(greek:|persian:)?([a-z]+).*$/, '$1:$3');
      owner = owner.replace(/:[a-z]+$/, '');
      by[owner] = (by[owner] || 0) + 1;
      if (inSettled(o)) settledObjs++;
    });
    let skinnedVisible = 0, skinnedSettled = 0, boneTexBytes = 0;
    ctx.scene.traverseVisible((o) => {
      if (!o.isSkinnedMesh) return;
      skinnedVisible++;
      if (inSettled(o)) skinnedSettled++;
      const t = o.skeleton.boneTexture;
      if (t) boneTexBytes += t.image.width * t.image.height * 16;
    });
    return {
      population: { alive: ctx.actors.filter((a) => a.alive && a.team === 'persian').length, greeks: ctx.actors.filter((a) => a.alive && a.team !== 'persian').length, corpses: ctx.ai.corpses.length, settledCorpses: settled.size },
      sceneObjects: total, matrixAutoUpdate: auto, settledCorpseObjects: settledObjs,
      byOwner: Object.entries(by).sort((a, b) => b[1] - a[1]),
      skinnedVisible, skinnedSettled, boneTexKBPerFrame: +(boneTexBytes / 1024).toFixed(1),
      calls: r.info.render.calls,
    };
  });
  console.log(JSON.stringify(res, null, 1));
} finally {
  await l.close();
}
