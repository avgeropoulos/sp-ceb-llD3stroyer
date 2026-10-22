// Builds every character variant in Node (geometry needs no DOM) and checks the §11 contract:
// rigid skinning, at most two material groups, the triangle budgets per quality, valid bone
// indices, and the pose/clip tables (channel widths, finite values, CLIPS timing fields).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const gameDir = resolve(here, '../../src/main/assets/game');
const threeBase = pathToFileURL(join(gameDir, 'lib/three') + sep).href;
const hooks = `
const base = ${JSON.stringify(threeBase)};
export async function resolve(specifier, context, next) {
  if (specifier === 'three') return next(base + 'three.module.js', context);
  if (specifier.startsWith('three/addons/')) return next(base + 'addons/' + specifier.slice('three/addons/'.length), context);
  return next(specifier, context);
}
`;
register('data:text/javascript,' + encodeURIComponent(hooks));

const G = pathToFileURL(join(gameDir, 'js/gfx/characters') + sep).href;
const { buildModel } = await import(G + 'models.js');
const { variantFor, KINDS } = await import(G + 'kinds.js');
const { atlasRects } = await import(G + 'atlas.js');
const { BONES } = await import(G + 'rig.js');
const { CH, N, STYLES, CLIPS, clipKeys } = await import(G + 'poses.js');

const VARIANTS = [
  ['spartan', {}], ['spartan', { weapon: 'xiphos' }], ['spartan', { blazon: 'lion' }],
  ['levy', { variant: 'mede' }], ['levy', { variant: 'kissian' }], ['sparabara', {}],
  ['immortal', {}], ['immortal', { variant: 'gold' }], ['archer', {}], ['captain', {}], ['dummy', {}], ['helot', {}],
];
const BUDGET = { high: 2500, medium: 2500, low: 1200 };

for (const quality of ['high', 'low']) {
  test(`every variant meets the ${quality} budget with rigid skinning and ≤ 2 groups`, () => {
    const rects = atlasRects(quality);
    const rectMap = { ...rects.cloth, ...rects.metal };
    const seen = new Set();
    for (const [kind, opts] of VARIANTS) {
      for (let seed = 1; seed <= 16; seed++) {
        const { key, spec } = variantFor(kind, opts, seed);
        if (seen.has(key)) continue;
        seen.add(key);
        const { geometry: g, info } = buildModel(spec, rectMap, quality);
        const label = `${quality} ${key}`;
        assert.ok(info.triangles <= BUDGET[quality], `${label}: ${info.triangles} triangles`);
        assert.ok(g.groups.length >= 1 && g.groups.length <= 2, `${label}: ${g.groups.length} groups`);
        g.groups.forEach((gr) => assert.ok(gr.materialIndex === 0 || gr.materialIndex === 1, `${label}: material ${gr.materialIndex}`));
        for (const a of ['position', 'normal', 'uv', 'skinIndex', 'skinWeight']) assert.ok(g.attributes[a], `${label}: missing ${a}`);
        assert.ok(g.index && g.index.count === info.triangles * 3, `${label}: indexed`);
        const si = g.attributes.skinIndex, sw = g.attributes.skinWeight, pos = g.attributes.position, uv = g.attributes.uv;
        for (let i = 0; i < si.count; i++) {
          const b = si.getX(i);
          assert.ok(b >= 0 && b < BONES.length, `${label}: bone ${b}`);
          assert.equal(sw.getX(i), 1);
          assert.equal(sw.getY(i) + sw.getZ(i) + sw.getW(i), 0);
          assert.ok(Number.isFinite(pos.getX(i) + pos.getY(i) + pos.getZ(i)), `${label}: position`);
          assert.ok(uv.getX(i) >= 0 && uv.getX(i) <= 1 && uv.getY(i) >= 0 && uv.getY(i) <= 1, `${label}: uv in atlas`);
        }
        // the body (not the hand-held items, which the animator places) stands on y = 0
        const items = new Set(['itemL', 'weaponR', 'nock'].map((n) => BONES.findIndex((b) => b[0] === n)));
        let minY = Infinity, maxY = -Infinity;
        for (let i = 0; i < pos.count; i++) {
          if (items.has(si.getX(i))) continue;
          minY = Math.min(minY, pos.getY(i));
          maxY = Math.max(maxY, pos.getY(i));
        }
        assert.ok(minY > -0.01 && minY < 0.02, `${label}: soles at ${minY}`);
        assert.ok(maxY < 2.45, `${label}: top at ${maxY}`);
      }
    }
  });
}

test('KINDS carries the public facts for every kind', () => {
  for (const kind of ['spartan', 'levy', 'sparabara', 'immortal', 'archer', 'captain', 'dummy', 'helot']) {
    const k = KINDS[kind];
    assert.ok(k, kind);
    assert.ok(k.height >= 1.6 && k.height <= 2.0, `${kind} height`);
    assert.ok('weapon' in k && 'shield' in k && 'attackClip' in k, `${kind} fields`);
  }
  assert.equal(KINDS.captain.height, 1.95);
});

test('CLIPS: every clip has strikeAt, duration, layer and loop:false', () => {
  const names = ['thrust', 'overhead', 'slash', 'axe', 'bash', 'shoot', 'throw', 'hit', 'stagger', 'knockdown', 'getup',
    'block_hit', 'cheer', 'die_back', 'die_forward', 'die_crumple', 'die_spear', 'fall_sea'];
  for (const n of names) {
    const c = CLIPS[n];
    assert.ok(c, n);
    assert.ok(c.strikeAt >= 0 && c.strikeAt <= 1, `${n} strikeAt`);
    assert.ok(c.duration > 0, `${n} duration`);
    assert.ok(c.layer === 'full' || c.layer === 'upper', `${n} layer`);
    assert.equal(c.loop, false);
  }
});

test('poses: keyframes are finite, ordered, and keep item scales intact', () => {
  for (const style of Object.keys(STYLES)) {
    for (const clip of Object.keys(CLIPS)) {
      const keys = clipKeys(style, clip);
      assert.equal(keys[0].t, 0);
      assert.equal(keys[keys.length - 1].t, 1);
      for (let i = 0; i < keys.length; i++) {
        const p = keys[i].pose;
        assert.equal(p.length, N);
        if (i) assert.ok(keys[i].t >= keys[i - 1].t, `${style}:${clip} order`);
        for (let c = 0; c < N; c++) assert.ok(Number.isFinite(p[c]), `${style}:${clip} key ${i} channel ${c}`);
        // only the throw and the archer's draw hide the weapon; nothing hides the shield
        assert.equal(p[CH.lscale], 1, `${style}:${clip} key ${i} lscale`);
        if (clip !== 'throw' && clip !== 'shoot' && style !== 'archer') assert.equal(p[CH.wscale], 1, `${style}:${clip} key ${i} wscale`);
      }
    }
  }
});
