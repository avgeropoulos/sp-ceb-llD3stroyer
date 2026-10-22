// Review (performance lens): which program is compiled after boot when the first battle or
// training frame draws, and how its cache key differs from the boot-time sibling.
// Usage: node spartan/tests/e2e/review-perf-programs-diff.mjs [battle|training]
import { launch } from './lib.mjs';

const which = process.argv[2] || 'battle';
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const res = await page.evaluate((which) => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer;
    const keys = () => new Map(r.info.programs.map((p) => [p.cacheKey, p]));
    g.step(30);
    const before = keys();
    // Which vm materials exist, and the vm env at boot?
    const vmEnvAtTitle = !!ctx.vm.scene.environment;
    if (which === 'battle') g.startBattle(1); else g.startTraining();
    g.step(2);
    const after = keys();
    const added = [...after.keys()].filter((k) => !before.has(k));
    const out = [];
    for (const k of added) {
      const p = after.get(k);
      // Closest boot program with the same name: show the differing comma-separated parts.
      let best = null, bestDiff = null;
      const parts = k.split(',');
      for (const [bk, bp] of before) {
        if (bp.name !== p.name) continue;
        const bparts = bk.split(',');
        const diff = [];
        for (let i = 0; i < Math.max(parts.length, bparts.length); i++) if (parts[i] !== bparts[i]) diff.push(`#${i}: ${bparts[i]} -> ${parts[i]}`);
        if (!bestDiff || diff.length < bestDiff.length) { best = bk; bestDiff = diff; }
      }
      // Which material uses it now?
      const users = [];
      const scan = (o) => { const ms = o.material ? (Array.isArray(o.material) ? o.material : [o.material]) : []; for (const m of ms) { const mp = r.properties.get(m); if (mp && mp.currentProgram === p) users.push(`${o.name || o.type}/${m.name || m.type}`); } };
      ctx.scene.traverse(scan);
      ctx.vm.scene.traverse(scan);
      out.push({ name: p.name, users: [...new Set(users)].slice(0, 8), diffVsClosestBootProgram: bestDiff ? bestDiff.slice(0, 12) : 'no same-name program at boot' });
    }
    return { vmEnvAtTitle, vmEnvNow: !!ctx.vm.scene.environment, before: before.size, after: after.size, out };
  }, which);
  console.log(JSON.stringify(res, null, 1));
} finally {
  await l.close();
}
