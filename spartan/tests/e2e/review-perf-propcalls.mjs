// Review (performance lens): draw calls spent on small props (stuck arrows, stuck/thrown spears)
// per pass, after a long Day 2 fight with volleys (god-mode autopilot), counted by object name.
// Usage: node spartan/tests/e2e/review-perf-propcalls.mjs [frames=5400]
import { launch } from './lib.mjs';
import { installProbe } from './review-perf-lib.mjs';

const FR = Number(process.argv[2] || 5400);
const l = await launch();
try {
  const page = await l.newPage({ width: 320, height: 180 });
  await page.goto(`${l.game}?manual=1&seed=7&quality=medium`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  await installProbe(page);
  const res = await page.evaluate((FR) => {
    const g = window.__game, ctx = g.ctx, r = ctx.renderer, rv = window.__rv;
    rv.noDraw = true;
    rv.pilot(true);
    rv.hpScale = 0.6;
    g.startBattle(2);
    const out = [];
    for (let done = 0; done < FR; done += 600) {
      rv.run(600, 10);
      if (done % 1800 === 0) ctx.combat.volley({ count: 300, duration: 1.2, warn: 0.5 });
      rv.noDraw = false;
      const orig = r.renderBufferDirect;
      const by = {};
      r.renderBufferDirect = function (camera, scene, geometry, material, object, group) {
        const c0 = r.info.render.calls;
        const res = orig.apply(this, arguments);
        const pass = scene === null ? 'shadow' : scene === ctx.scene ? 'main' : 'vm';
        const name = /^prop/.test(object.name) ? object.name : object.isSkinnedMesh ? 'skinned' : 'other';
        const k = pass + ' ' + name;
        by[k] = (by[k] || 0) + (r.info.render.calls - c0);
        return res;
      };
      g.step(1);
      r.renderBufferDirect = orig;
      rv.noDraw = true;
      out.push({ t: +ctx.time.gnow.toFixed(0), total: r.info.render.calls, by, stuckArrows: ctx.combat.stuckArrows ? ctx.combat.stuckArrows.length : null, stuckSpears: ctx.combat.stuckSpears.length });
    }
    return out;
  }, FR);
  for (const s of res) {
    const props = Object.entries(s.by).filter(([k]) => /prop/.test(k)).map(([k, v]) => `${k}=${v}`).join(' ');
    const shadowAll = Object.entries(s.by).filter(([k]) => k.startsWith('shadow')).reduce((a, [, v]) => a + v, 0);
    const shadowProps = Object.entries(s.by).filter(([k]) => k.startsWith('shadow prop')).reduce((a, [, v]) => a + v, 0);
    console.log(`t=${s.t}s calls=${s.total} shadow=${shadowAll} (props ${shadowProps}) | ${props} | stuckArrows=${s.stuckArrows} stuckSpears=${s.stuckSpears}`);
  }
} finally {
  await l.close();
}
