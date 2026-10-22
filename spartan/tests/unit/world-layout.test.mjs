// Unit tests for gfx/world/layout.js (PURE): the shape of the pass that every world builder,
// player, AI and projectile relies on (DESIGN.md §3.1, §11 groundHeight).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createLayout, SEA_LEVEL, CLIFF_X, SHORE_X, WALL } from '../../src/main/assets/game/js/gfx/world/layout.js';

const L = createLayout(480);

test('is deterministic for a seed', () => {
  const b = createLayout(480);
  for (let i = 0; i < 200; i++) {
    const x = -12 + (i % 30), z = -80 + i * 0.7;
    assert.equal(L.groundHeight(x, z), b.groundHeight(x, z));
  }
});

test('the fighting line is a near-flat floor', () => {
  for (let x = -7; x <= 8.4; x += 0.5) {
    for (let z = -6; z <= 3; z += 0.5) {
      const h = L.groundHeight(x, z);
      assert.ok(Math.abs(h) < 0.4, `floor at (${x}, ${z}) is ${h}`);
      // Gentle: the slope across the player rectangle stays walkable.
      const s = Math.hypot(L.groundHeight(x + 0.25, z) - h, L.groundHeight(x, z + 0.25) - h) / 0.25;
      assert.ok(s < 0.25, `slope ${s} at (${x}, ${z})`);
    }
  }
});

test('the cliff foot and the shore lip sit where §3.1 puts them near the line', () => {
  for (let z = -40; z <= 30; z += 2) {
    assert.equal(L.cliffX(z), CLIFF_X);
    assert.ok(Math.abs(L.shoreX(z) - SHORE_X) < 0.6, `shore lip at z=${z}: ${L.shoreX(z)}`);
  }
});

test('the ground drops to the sea past the lip and is −3 over the water', () => {
  for (let z = -60; z <= 60; z += 3) {
    const sx = L.shoreX(z);
    assert.ok(L.groundHeight(sx - 1, z) > -1, 'floor before the lip');
    assert.equal(L.groundHeight(sx + 4, z), SEA_LEVEL, `water 4 m past the lip at z=${z}`);
    assert.equal(L.groundHeight(sx + 200, z), SEA_LEVEL);
    assert.ok(L.isSea(sx + 6, z));
    let prev = Infinity;
    for (let x = sx - 0.5; x < sx + 4; x += 0.25) {
      const h = L.groundHeight(x, z);
      assert.ok(h <= prev + 0.45, `drop is roughly monotonic at (${x}, ${z})`);
      prev = h;
    }
  }
  assert.equal(L.groundHeight(1000, -1000), SEA_LEVEL);
});

test('groundHeight is finite everywhere and the near cache matches the full function', () => {
  for (const [x, z] of [[NaN, 0], [0, NaN], [Infinity, 3], [-1e6, 1e6], [1e9, -1e9]]) assert.ok(Number.isFinite(L.groundHeight(x, z)));
  let worst = 0;
  let floor = 0;
  for (let i = 0; i < 6000; i++) {
    const x = -11.9 + ((i * 7.31) % 29.8);
    const z = -89.9 + ((i * 13.17) % 161.8);
    const e = Math.abs(L.groundHeight(x, z) - Math.max(SEA_LEVEL, L.terrainHeight(x, z)));
    worst = Math.max(worst, e);
    const inBasin = L.springs.some((sp) => Math.hypot(x - sp.x, z - sp.z) < sp.pool + 0.5);
    if (x > -7 && x < 8.4 && z > -45 && z < 12 && !inBasin) floor = Math.max(floor, e);
  }
  // Bilinear on a 25 cm grid: millimetres on the floor, a few cm at the kinks of the cliff foot
  // and in the spring basins.
  assert.ok(floor < 0.01, `cache error on the floor ${floor}`);
  assert.ok(worst < 0.08, `cache error ${worst}`);
});

test('the far pass climbs so the army shows over the rise; the wall and its gap are on flat ground', () => {
  assert.ok(L.groundHeight(0, -400) > L.groundHeight(0, -150) + 8);
  assert.ok(L.groundHeight(0, -150) > L.groundHeight(0, -60));
  for (let x = WALL.gapMinX; x <= WALL.gapMaxX; x += 0.5) assert.ok(Math.abs(L.groundHeight(x, WALL.z)) < 0.5);
});

test('springs sit in small basins on the floor beside the cliff', () => {
  assert.ok(L.springs.length >= 2);
  for (const s of L.springs) {
    assert.ok(s.x > CLIFF_X && s.x < CLIFF_X + 3, `spring x ${s.x}`);
    const centre = L.terrainHeight(s.x, s.z);
    assert.ok(Math.abs(centre) < 2);
    // The vent is the low point of its basin, so the pool shows.
    for (let a = 0; a < 8; a++) {
      const r = s.pool + 0.2;
      assert.ok(L.terrainHeight(s.x + Math.cos(a) * r, s.z + Math.sin(a) * r) > centre + 0.15);
    }
  }
});
