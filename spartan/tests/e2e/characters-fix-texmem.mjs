// Characters fixer: texture memory held for the character atlas sources after boot. The atlas
// (gfx/characters/atlas.js buildMaterials) draws its tiles from ctx.textures sources and then
// hands them back with ctx.textures.release(name); this prints which sources are still in memory
// and textures.memoryBytes({ all: true }) with the characters files from a git revision (A) and
// the working tree (B).
// Usage: node spartan/tests/e2e/characters-fix-texmem.mjs [--rev=7f85c80] [quality=medium]
import { execFileSync } from 'node:child_process';
import { launch } from './lib.mjs';

const args = process.argv.slice(2);
const rev = (args.find((a) => a.startsWith('--rev=')) || '--rev=7f85c80').slice(6);
const quality = (args.find((a) => a.startsWith('quality=')) || 'quality=medium').slice(8);
const SOURCES = ['bronze', 'crimson', 'felt', 'leather', 'linen', 'persianRobeA', 'persianRobeB', 'persianRobeC', 'scale', 'skinGreek', 'skinPersian', 'wicker', 'wood',
  'blazonFly', 'blazonGorgon', 'blazonLion', 'blazonBoar', 'blazonClub', 'blazonScorpion'];

const l = await launch();
async function measure(variant) {
  const page = await l.newPage({ width: 320, height: 180 });
  if (variant === 'A') {
    const OWN = /\/js\/(game\/ai\.js|gfx\/characters\.js|gfx\/characters\/[a-z]+\.js)$/;
    await page.route(OWN, async (route) => {
      const rel = new URL(route.request().url()).pathname.replace(/^\//, '');
      const body = execFileSync('git', ['show', `${rev}:spartan/${rel}`], { encoding: 'utf8', maxBuffer: 1 << 26 });
      await route.fulfill({ status: 200, contentType: 'text/javascript', body });
    });
  }
  await page.goto(`${l.game}?manual=1&seed=7&quality=${quality}`);
  await page.waitForFunction(() => window.__game && (window.__game.ready || window.__game.errors.length), null, { timeout: 300000 });
  const r = await page.evaluate((SOURCES) => {
    const g = window.__game, t = g.ctx.textures;
    g.startBattle(1);
    g.step(30);
    const held = SOURCES.filter((n) => t.has(n));
    return { held, allMB: +(t.memoryBytes({ all: true }) / 1048576).toFixed(1), gpuMB: +(t.memoryBytes() / 1048576).toFixed(1), errors: g.errors.slice(0, 3) };
  }, SOURCES);
  await page.context().close();
  return r;
}
try {
  for (const v of ['A', 'B']) {
    const r = await measure(v);
    console.log(`${v === 'A' ? `A (${rev})` : 'B (working tree)'}: textures in memory ${r.allMB} MB (sampled by materials ${r.gpuMB} MB); atlas sources still held ${r.held.length}: ${r.held.join(' ')}${r.errors.length ? '\n  errors: ' + r.errors.join(' | ') : ''}`);
  }
} finally {
  await l.close();
}
