// WebGL context loss with the boot-time texture changes (fixer "boot"): textures that are only
// kept as data (DataTextures uploaded at boot) and canvases must all re-upload after a restore.
// Manual stepping: render a battle frame, lose and restore the context, render the same frame
// again and count differing pixels; also checks the textures module's GPU textures came back.
// Usage: node spartan/tests/e2e/boot-glloss.mjs [quality=medium] [out.png prefix] [reference textures.js to serve instead]
import fs from 'node:fs';
import { launch } from './lib.mjs';

const quality = process.argv[2] || 'medium';
const prefix = process.argv[3] || null;
const refSrc = process.argv[4] ? fs.readFileSync(process.argv[4], 'utf8') : null;
const l = await launch();
let failed = false;
try {
  const page = await l.newPage({ width: 640, height: 360 });
  if (refSrc) await page.route('**/js/gfx/textures.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: refSrc }));
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const before = await page.evaluate(() => {
    const g = window.__game;
    g.startBattle(1);
    g.step(120);
    // The viewmodel's metal goes dark after a restore whatever textures.js does (its env map is
    // not rebuilt; seen with the pre-change textures.js too): compare the world scene only.
    g.ctx.vm.scene.visible = false;
    g.render();
    return g.ctx.renderer.domElement.toDataURL('image/png');
  });
  await page.evaluate(() => new Promise((resolve) => {
    const c = window.__game.ctx.renderer.domElement;
    const ext = window.__game.ctx.renderer.getContext().getExtension('WEBGL_lose_context');
    c.addEventListener('webglcontextlost', () => setTimeout(() => ext.restoreContext(), 200), { once: true });
    c.addEventListener('webglcontextrestored', () => setTimeout(resolve, 200), { once: true });
    ext.loseContext();
  }));
  const after = await page.evaluate(() => {
    const g = window.__game;
    g.render();
    const r = g.ctx.renderer;
    const t = g.ctx.textures;
    // Textures re-upload when next drawn: check the ones this view samples.
    const missing = ['dirt', 'dirtNormal', 'rock', 'cliffStrata', 'cliffNormal', 'sand', 'grass', 'waterNormal'].filter((n) => !r.properties.get(t.get(n)).__webglTexture);
    return { url: r.domElement.toDataURL('image/png'), lost: r.getContext().isContextLost(), missing };
  });
  const d = await page.evaluate(async ([a, b]) => {
    const load = (src) => new Promise((res) => {
      const im = new Image();
      im.onload = () => res(im);
      im.src = src;
    });
    const px = (im) => {
      const c = document.createElement('canvas');
      c.width = im.width;
      c.height = im.height;
      const g = c.getContext('2d');
      g.drawImage(im, 0, 0);
      return g.getImageData(0, 0, im.width, im.height).data;
    };
    const [da, db] = (await Promise.all([load(a), load(b)])).map(px);
    let n = 0, max = 0, lum = 0;
    for (let i = 0; i < da.length; i += 4) {
      const m = Math.max(Math.abs(da[i] - db[i]), Math.abs(da[i + 1] - db[i + 1]), Math.abs(da[i + 2] - db[i + 2]));
      if (m > 2) n++;
      if (m > max) max = m;
      lum += db[i] + db[i + 1] + db[i + 2];
    }
    return { differing: n, total: da.length / 4, maxDelta: max, meanAfter: +(lum / (da.length / 4) / 3).toFixed(1) };
  }, [before, after.url]);
  if (prefix) {
    fs.writeFileSync(`${prefix}-before.png`, Buffer.from(before.split(',')[1], 'base64'));
    fs.writeFileSync(`${prefix}-after.png`, Buffer.from(after.url.split(',')[1], 'base64'));
  }
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  console.log(`context restored: ${!after.lost}; textures not re-uploaded: ${after.missing.join(', ') || 'none'}`);
  console.log(`frame before loss vs after restore: ${d.differing} of ${d.total} pixels differ by > 2 (max Δ ${d.maxDelta}); mean level after ${d.meanAfter}`);
  failed = after.lost || after.missing.length > 0 || d.differing > d.total * 0.001 || errs.length > 0;
  if (errs.length) console.log('errors:', errs.slice(0, 5).join(' | '));
} finally {
  await l.close();
}
console.log(failed ? 'FAIL' : 'ok: everything re-uploaded after a context restore');
process.exitCode = failed ? 1 : 0;
