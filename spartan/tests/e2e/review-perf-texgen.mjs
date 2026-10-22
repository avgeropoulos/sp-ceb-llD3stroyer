// Review (performance lens): CPU time of every procedural texture generator (the pure pixel part
// of the boot's "Weaving cloaks…" step), per quality, via textures.js generatePixels in Node.
// Canvas-drawn textures (grass, scrub, blazons) need a DOM and are not covered here.
// Usage: node spartan/tests/e2e/review-perf-texgen.mjs [qualities=low,medium,high]
import { register } from 'node:module';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const gameDir = resolve(here, '../../src/main/assets/game');
const threeBase = pathToFileURL(join(gameDir, 'lib/three') + sep).href;
register('data:text/javascript,' + encodeURIComponent(`
const base = ${JSON.stringify(threeBase)};
export async function resolve(s, c, n) {
  if (s === 'three') return n(base + 'three.module.js', c);
  if (s.startsWith('three/addons/')) return n(base + 'addons/' + s.slice(13), c);
  return n(s, c);
}`));
const { TEXTURE_NAMES, generatePixels } = await import(pathToFileURL(join(gameDir, 'js/gfx/textures.js')).href);
const cpu = () => { const u = process.cpuUsage(); return (u.user + u.system) / 1000; };
for (const q of (process.argv[2] || 'low,medium,high').split(',')) {
  const rows = [];
  let total = 0;
  for (const name of TEXTURE_NAMES) {
    const t0 = cpu();
    const r = generatePixels(name, q);
    const ms = cpu() - t0;
    if (!r) continue;
    total += ms;
    rows.push([name, r.size, ms]);
  }
  rows.sort((a, b) => b[2] - a[2]);
  console.log(`[${q}] pixel generators: ${total.toFixed(0)} ms CPU (phone ×4–6: ${(total * 4 / 1000).toFixed(1)}–${(total * 6 / 1000).toFixed(1)} s); slowest: ${rows.slice(0, 8).map(([n, s, ms]) => `${n} ${s}² ${ms.toFixed(0)}ms`).join(', ')}`);
}
