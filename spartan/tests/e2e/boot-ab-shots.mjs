// Before/after screenshots for the boot-time texture changes (fixer "boot"): renders the same
// deterministic scenes (manual stepping, seed 7) with a reference textures.js served in place of
// the current one, then with the current one on a first launch (generated on workers) and on a
// second launch (pixels from the IndexedDB cache), saves PNGs and counts differing pixels.
// Usage: node spartan/tests/e2e/boot-ab-shots.mjs <reference textures.js> [outDir] [quality=medium]
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './lib.mjs';

const [, , refFile, outDir = '/tmp/boot-ab', quality = 'medium'] = process.argv;
if (!refFile) {
  console.error('usage: boot-ab-shots.mjs <reference textures.js> [outDir] [quality]');
  process.exit(2);
}
fs.mkdirSync(outDir, { recursive: true });
const refSrc = fs.readFileSync(refFile, 'utf8');

const SCENES = {
  title: 'g.step(30)',
  battle: "g.startBattle(1); g.step(150); g.spawn('immortal', 0.6, -2.4); g.spawn('captain', -1.2, -3.2); g.spawn('levy', 1.8, -3.6); g.step(40)",
  training: 'g.startTraining(); g.step(90)',
};

/** Loads the game fresh, runs one scene and returns the frame as a PNG data URL (also saved). */
async function shoot(page, scene, label) {
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const url = await page.evaluate((code) => {
    const g = window.__game;
    new Function('g', code)(g);
    g.render();
    return g.ctx.renderer.domElement.toDataURL('image/png');
  }, SCENES[scene]);
  fs.writeFileSync(path.join(outDir, `${scene}-${label}.png`), Buffer.from(url.split(',')[1], 'base64'));
  const errs = page.errors.concat(await page.evaluate(() => window.__game.errors));
  if (errs.length) console.log(`${scene} ${label}: errors`, errs.slice(0, 3).join(' | '));
  const stats = await page.evaluate(() => window.__game.ctx.textures.stats || null);
  return { url, stats };
}

/** Number of pixels whose RGB differs by more than `tol` in any channel, decoded in the page. */
async function diff(page, a, b, tol = 0) {
  return page.evaluate(async ([a, b, tol]) => {
    const load = (src) => new Promise((res) => {
      const im = new Image();
      im.onload = () => res(im);
      im.src = src;
    });
    const [ia, ib] = await Promise.all([load(a), load(b)]);
    const px = (im) => {
      const c = document.createElement('canvas');
      c.width = im.width;
      c.height = im.height;
      const g = c.getContext('2d');
      g.drawImage(im, 0, 0);
      return g.getImageData(0, 0, im.width, im.height).data;
    };
    const da = px(ia);
    const db = px(ib);
    let n = 0;
    let max = 0;
    for (let i = 0; i < da.length; i += 4) {
      const d = Math.max(Math.abs(da[i] - db[i]), Math.abs(da[i + 1] - db[i + 1]), Math.abs(da[i + 2] - db[i + 2]));
      if (d > tol) n++;
      if (d > max) max = d;
    }
    return { differing: n, total: da.length / 4, maxDelta: max };
  }, [a, b, tol]);
}

const l = await launch();
let bad = 0;
try {
  const refPage = await l.newPage({ width: 915, height: 412 });
  await refPage.route('**/js/gfx/textures.js', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: refSrc }));
  // One context for the current code: its first scene is a first launch (generated on workers),
  // the later ones load the pixels cached in IndexedDB. Before/after run back to back per scene,
  // so other edits to the tree in the meantime can't creep into a comparison.
  const page = await l.newPage({ width: 915, height: 412 });
  for (const scene of Object.keys(SCENES)) {
    const before = await shoot(refPage, scene, 'before');
    const after = await shoot(page, scene, 'after');
    const how = after.stats ? (after.stats.fromCache ? `cached launch, ${after.stats.fromCache} from cache` : `first launch, ${after.stats.workers} workers`) : '';
    const d = await diff(page, before.url, after.url);
    if (d.differing) bad++;
    console.log(`${scene} before vs after (${how}): ${d.differing} of ${d.total} pixels differ (max Δ ${d.maxDelta})`);
  }
} finally {
  await l.close();
}
console.log(bad ? `${bad} comparison(s) differ; PNGs in ${outDir}` : `identical; PNGs in ${outDir}`);
process.exitCode = bad ? 1 : 0;
