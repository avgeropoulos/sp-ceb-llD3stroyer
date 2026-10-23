// Unit tests for the boot-time texture pipeline (fixer "boot"): the generator checksum that keys
// the cross-launch pixel cache, the catalogue flags that decide what is generated and uploaded
// at boot, and the browser-only pieces degrading to "unavailable" outside a browser.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  TEXTURE_DEFS, TEXTURE_NAMES, TEXTURE_VERSION, generatePixels, generateOwner, textureSize,
} from '../../src/main/assets/game/js/gfx/textures/generators.js';
import { openPixelCache, textureCacheKey } from '../../src/main/assets/game/js/gfx/textures/cache.js';
import { textureWorkerCount, runOnWorkers } from '../../src/main/assets/game/js/gfx/textures/pool.js';

const jsDir = resolve(dirname(fileURLToPath(import.meta.url)), '../../src/main/assets/game/js');

/**
 * SHA-1 (first 12 hex digits) of every generator's output at low quality, per TEXTURE_VERSION.
 * Phones cache generated pixels under TEXTURE_VERSION, so a generator whose output changes while
 * the version stays the same would leave every returning player with the old texture. If this
 * test fails because you changed a generator (or noise.js / the math.js helpers they use) on
 * purpose: bump TEXTURE_VERSION in gfx/textures/generators.js and add its checksums here.
 */
const PINNED = {
  1: {
    dirt: '487fd17ff76d', rock: '9537241df443', cliffStrata: 'baeae0e8a59f', sand: '2cf85a4236d1', bronze: '800bc314f1b9',
    linen: 'fe3966674c22', crimson: 'def936bf8b94', leather: '6e096543acf0', wood: '4f87a2047830', wicker: '26782340f590',
    persianRobeA: '5f1378bdd2e3', persianRobeB: 'b4f4dc3bc729', persianRobeC: '9fef56810971', scale: 'ec425c9dfe25',
    felt: '885a1b7da95e', skinGreek: '1d4e410f1f21', skinPersian: 'cfac3a489e2d', stone: '31cc96305ca2', water: '849629052a40',
    waterNormal: 'f6d66b9fa985', cloud: 'daabbc5adef0', blood: 'e71e27e79f9a', smoke: '5db8dec0263d', spark: 'e9f20511bb09',
    sulphur: '88e812e0a534', foam: '576d504415b0',
  },
};

test('generator output matches the checksums pinned for TEXTURE_VERSION (the pixel cache key)', { timeout: 120000 }, () => {
  const pinned = PINNED[TEXTURE_VERSION];
  assert.ok(pinned, `no checksums pinned for TEXTURE_VERSION ${TEXTURE_VERSION}: add them to PINNED`);
  const changed = [];
  for (const name of TEXTURE_NAMES) {
    const r = generatePixels(name, 'low');
    if (!r) continue;
    const h = createHash('sha1').update(r.color);
    if (r.normal) h.update(r.normal);
    const sum = h.digest('hex').slice(0, 12);
    if (pinned[name] !== sum) changed.push(`${name}: ${sum} (pinned ${pinned[name]})`);
  }
  assert.deepEqual(changed, [], `generator output changed without a TEXTURE_VERSION bump:\n  ${changed.join('\n  ')}`);
});

test('boot skips textures nothing uses; atlas sources and blazons are never uploaded', () => {
  for (const n of ['water', 'blood', 'spark', 'bronzeNormal', 'wickerNormal']) assert.equal(TEXTURE_DEFS[n].boot, false, `${n} should be skipped at boot`);
  for (const n of ['bronze', 'linen', 'crimson', 'leather', 'wood', 'wicker', 'persianRobeA', 'persianRobeB', 'persianRobeC', 'scale',
    'felt', 'skinGreek', 'skinPersian', 'blazonFly', 'blazonGorgon', 'blazonLion', 'blazonBoar', 'blazonClub', 'blazonScorpion']) {
    assert.ok(!TEXTURE_DEFS[n].gpu, `${n} is only drawn into canvases and must not be uploaded`);
    assert.notEqual(TEXTURE_DEFS[n].boot, false, `${n} is needed at boot`);
  }
  // Every contract name (DESIGN.md §11) is still accepted by get().
  for (const n of ['dirt', 'dirtNormal', 'rock', 'rockNormal', 'cliffStrata', 'sand', 'bronze', 'bronzeNormal', 'linen', 'crimson',
    'leather', 'wood', 'wicker', 'wickerNormal', 'persianRobeA', 'persianRobeB', 'persianRobeC', 'scale', 'felt', 'skinGreek',
    'skinPersian', 'stone', 'water', 'waterNormal', 'cloud', 'blood', 'smoke', 'spark']) assert.ok(TEXTURE_DEFS[n], n);
});

test('every texture a world material samples is uploaded at boot, and nothing else is', () => {
  const src = readFileSync(join(jsDir, 'gfx/world.js'), 'utf8');
  const sampled = new Set([...src.matchAll(/\btex\.get\(\s*'([A-Za-z]+)'\s*\)/g)].map((m) => m[1]));
  assert.ok(sampled.size >= 10, `found only ${sampled.size} world textures`);
  for (const n of sampled) assert.ok(TEXTURE_DEFS[n] && TEXTURE_DEFS[n].gpu, `world.js samples ${n}: flag it gpu so generateAll uploads it`);
  const gpu = TEXTURE_NAMES.filter((n) => TEXTURE_DEFS[n].gpu);
  for (const n of gpu) assert.ok(sampled.has(n), `${n} is flagged gpu but no world material samples it (wasted GPU memory)`);
});

test('every texture the character atlas and viewmodel draw from is generated at boot', () => {
  const atlas = readFileSync(join(jsDir, 'gfx/characters/atlas.js'), 'utf8');
  const names = new Set([...atlas.matchAll(/\btex:\s*'([A-Za-z]+)'/g)].map((m) => m[1]));
  names.add('bronze');
  for (const n of TEXTURE_NAMES) if (n.startsWith('blazon')) names.add(n);
  for (const n of names) assert.ok(TEXTURE_DEFS[n] && TEXTURE_DEFS[n].boot !== false, `${n} is drawn by the atlas but not generated at boot`);
});

test('generateOwner returns the owner and its pair ready to install', () => {
  const r = generateOwner('dirt', 'low');
  const p = generatePixels('dirt', 'low');
  assert.equal(r.size, 256);
  assert.deepEqual(r.color, p.color);
  assert.equal(r.pair.name, 'dirtNormal');
  assert.equal(r.pair.size, textureSize('dirtNormal', 'low'));
  assert.deepEqual(r.pair.color, p.normal);
  assert.equal(r.pair.meanLum, 0.5);
  assert.ok(r.meanLum > 0.02 && r.meanLum < 0.8, `dirt mean luminance ${r.meanLum}`);
  assert.equal(generateOwner('waterNormal', 'low').meanLum, 0.5, 'linear maps report 0.5');
  assert.equal(generateOwner('blazonFly', 'low'), null, 'canvas-drawn textures have no generator');
});

test('outside a browser the cache and the workers report themselves unavailable', async () => {
  assert.equal(await openPixelCache({ timeoutMs: 200 }), null);
  assert.equal(textureCacheKey(TEXTURE_VERSION, 'medium', 'dirt'), `${TEXTURE_VERSION}:medium:dirt`);
  assert.equal(textureWorkerCount(), 0);
  const res = await runOnWorkers(['dirt', 'rock'], { count: 0, quality: 'low', onResult() {} });
  assert.deepEqual(res.left, ['dirt', 'rock'], 'with no workers every job is left for the main thread');
});
