/**
 * Static props, all merged into a handful of meshes: limestone boulders and floor stones, dry
 * grass and evergreen scrub cards, the Phocian Wall, and the hot springs (pools, mineral crusts
 * and GPU-animated steam). Props near the line are split out so only they cast shadows.
 */
import * as THREE from 'three';
import { mergeGeometries, toCreasedNormals } from 'three/addons/BufferGeometryUtils.js';
import { clamp, lerp, smoothstep, createRng } from '../../core/math.js';
import { WALL, SEA_LEVEL } from './layout.js';

const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _e = new THREE.Euler();
const _s = new THREE.Vector3();
const _p = new THREE.Vector3();

/** Adds constant-valued attributes so every merged piece has the same layout. */
function withAttributes(g, { color = [1, 1, 1], ao = null, sway = null }) {
  const n = g.attributes.position.count;
  const col = new Float32Array(n * 3);
  const aoA = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    col[i * 3] = color[0];
    col[i * 3 + 1] = color[1];
    col[i * 3 + 2] = color[2];
    aoA[i] = ao ? ao(i) : 1;
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  g.setAttribute('ao', new THREE.BufferAttribute(aoA, 1));
  if (sway) {
    const sw = new Float32Array(n);
    for (let i = 0; i < n; i++) sw[i] = sway(i);
    g.setAttribute('sway', new THREE.BufferAttribute(sw, 1));
  }
  return g;
}

/**
 * A fractured limestone boulder: a displaced sphere cut by a few planes (flat joint faces),
 * flattened underneath. Returns a unit-ish geometry before placement.
 */
function rockGeometry(N, R, pebble = false) {
  const g = pebble ? new THREE.IcosahedronGeometry(1, 0) : new THREE.SphereGeometry(1, 10, 7);
  const p = g.attributes.position;
  const seed = R.next() * 50;
  const cuts = [];
  const nc = pebble ? 1 : 3 + Math.floor(R.next() * 3);
  for (let i = 0; i < nc; i++) {
    const a = R.next() * Math.PI * 2;
    const b = (R.next() - 0.3) * 1.3;
    cuts.push([Math.cos(a) * Math.cos(b), Math.sin(b), Math.sin(a) * Math.cos(b), 0.42 + R.next() * 0.3]);
  }
  for (let i = 0; i < p.count; i++) {
    let x = p.getX(i);
    let y = p.getY(i);
    let z = p.getZ(i);
    const d = 1 + 0.2 * N.noise3(x * 1.1 + seed, y * 1.1, z * 1.1) + 0.07 * N.noise3(x * 2.9, y * 2.9 + seed, z * 2.9);
    x *= d;
    y *= d;
    z *= d;
    for (const c of cuts) {
      const dot = x * c[0] + y * c[1] + z * c[2];
      if (dot > c[3]) {
        const k = dot - c[3];
        x -= c[0] * k;
        y -= c[1] * k;
        z -= c[2] * k;
      }
    }
    if (y < -0.35) y = -0.35 + (y + 0.35) * 0.35;
    p.setXYZ(i, x, y, z);
  }
  // Creased normals keep the fracture faces flat and the weathered surfaces rounded.
  const out = toCreasedNormals(g, pebble ? 0.5 : 0.62);
  if (out !== g) g.dispose();
  return out;
}

/** Places a rock: scale, random orientation, sunk into the ground. */
function placeRock(N, R, layout, x, z, size, opts = {}) {
  const g = rockGeometry(N, R, opts.pebble);
  const sx = size * (0.75 + R.next() * 0.5);
  const sy = size * (opts.flat ?? 0.55 + R.next() * 0.35);
  const sz = size * (0.75 + R.next() * 0.5);
  _e.set((R.next() - 0.5) * 0.4, R.next() * Math.PI * 2, (R.next() - 0.5) * 0.4);
  _q.setFromEuler(_e);
  _s.set(sx, sy, sz);
  const ground = opts.y ?? layout.terrainHeight(x, z);
  _p.set(x, ground + sy * (opts.sink ?? 0.18), z);
  _m.compose(_p, _q, _s);
  g.applyMatrix4(_m);
  // UVs scaled to keep texel density about constant (≈ 1.6 m per texture repeat).
  const uv = g.attributes.uv;
  const ru = Math.max(1, Math.round((Math.PI * (sx + sz)) / 1.6));
  const rv = Math.max(1, Math.round((Math.PI * sy) / 1.6));
  const uo = R.next();
  for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * ru + uo, uv.getY(i) * rv);
  const tint = 0.8 + R.next() * 0.25;
  const warm = R.next() * 0.1;
  const bottom = ground - sy * 0.3;
  const pa = g.attributes.position;
  withAttributes(g, {
    color: [tint * (1 + warm), tint, tint * (1 - warm)],
    ao: (i) => 0.5 + 0.5 * smoothstep(bottom, bottom + sy * 1.1, pa.getY(i)),
  });
  return { g, box: new THREE.Box3().setFromBufferAttribute(pa) };
}

/** Three crossed vertical quads, rooted at the origin, v = 0 at the root. */
function cardGeometry(w, h, lean, quads, normalUp) {
  const pos = [];
  const nrm = [];
  const uv = [];
  const sway = [];
  const index = [];
  for (let k = 0; k < quads; k++) {
    const a = (k / quads) * Math.PI;
    const dx = Math.cos(a) * w * 0.5;
    const dz = Math.sin(a) * w * 0.5;
    const base = pos.length / 3;
    pos.push(-dx, 0, -dz, dx, 0, dz, dx + lean[0], h, dz + lean[1], -dx + lean[0], h, -dz + lean[1]);
    uv.push(0, 0, 1, 0, 1, 1, 0, 1);
    sway.push(0, 0, 1, 1);
    for (let i = 0; i < 4; i++) {
      // Normals mostly up, so cards light like the ground they grow from.
      const side = i === 0 || i === 3 ? -1 : 1;
      const nx = normalUp ? 0 : side * Math.cos(a) * 0.6;
      const nz = normalUp ? 0 : side * Math.sin(a) * 0.6;
      const ny = normalUp ? 1 : 0.8;
      const l = Math.hypot(nx, ny, nz);
      nrm.push(nx / l, ny / l, nz / l);
    }
    index.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute('sway', new THREE.Float32BufferAttribute(sway, 1));
  g.setIndex(index);
  return g;
}

function placeCard(R, x, y, z, w, h, quads, tint, normalUp, swayAmt) {
  const lean = [(R.next() - 0.5) * w * 0.3, (R.next() - 0.5) * w * 0.3];
  const g = cardGeometry(w, h, lean, quads, normalUp);
  _e.set(0, R.next() * Math.PI, 0);
  _q.setFromEuler(_e);
  _m.compose(_p.set(x, y, z), _q, _s.set(1, 1, 1));
  g.applyMatrix4(_m);
  const n = g.attributes.position.count;
  const col = new Float32Array(n * 3);
  const ao = new Float32Array(n);
  const sw = g.attributes.sway;
  for (let i = 0; i < n; i++) {
    col[i * 3] = tint[0];
    col[i * 3 + 1] = tint[1];
    col[i * 3 + 2] = tint[2];
    ao[i] = 0.55 + 0.45 * sw.getX(i);
    sw.setX(i, sw.getX(i) * swayAmt);
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  g.setAttribute('ao', new THREE.BufferAttribute(ao, 1));
  return g;
}

function staticMesh(geoms, material, name, cast) {
  if (!geoms.length) return null;
  const g = mergeGeometries(geoms, false);
  for (const s of geoms) s.dispose();
  g.computeBoundingSphere();
  const mesh = new THREE.Mesh(g, material);
  mesh.name = name;
  mesh.castShadow = cast;
  mesh.receiveShadow = true;
  mesh.matrixAutoUpdate = false;
  mesh.updateMatrix();
  return mesh;
}

/** Distance band in which props cast shadows (the shadow box follows the player, ±18 m). */
const isNear = (x, z) => z > -24 && z < 22 && x > -13 && x < 13;

/**
 * Rocks of every size. Returns near (shadow-casting) and far meshes plus box colliders for the
 * boulders a spear could hit.
 */
export function buildRocks(layout, mat, quality, extraStones = []) {
  const N = layout.noise;
  const R = createRng(4801);
  const near = [];
  const far = [];
  const colliders = [];
  const scale = quality === 'low' ? 0.6 : 1;
  const add = (res, x, z, size) => {
    (isNear(x, z) ? near : far).push(res.g);
    if (size >= 0.85 && z > -75 && z < 45 && x > -10 && x < 11) {
      const b = res.box;
      const shrink = 0.18;
      const dx = (b.max.x - b.min.x) * shrink * 0.5;
      const dy = (b.max.y - b.min.y) * shrink * 0.5;
      const dz = (b.max.z - b.min.z) * shrink * 0.5;
      colliders.push({ type: 'box', min: [b.min.x + dx, b.min.y, b.min.z + dz], max: [b.max.x - dx, b.max.y - dy, b.max.z - dz] });
    }
  };
  const core = (z) => z > -11 && z < 8;
  // Talus boulders along the cliff foot, thicker on the scree fans.
  for (let z = 330; z > -420; ) {
    const step = Math.abs(z) < 70 ? 1.1 + R.next() * 1.8 : 2.5 + R.next() * 5;
    z -= step / scale;
    const cx = layout.cliffX(z);
    let fan = 0;
    for (const f of layout.fans) fan = Math.max(fan, 1 - Math.abs(z - f.z) / (f.w * 1.2));
    const n = 1 + (fan > 0 ? Math.round(fan * 3) : 0);
    for (let i = 0; i < n; i++) {
      const reach = core(z) ? 1.4 : 2.6 + fan * 3;
      const x = cx - 0.4 + R.next() * reach;
      const size = core(z) ? 0.3 + R.next() * 0.6 : 0.35 + Math.pow(R.next(), 2) * (1.8 + fan);
      add(placeRock(N, R, layout, x, z, size), x, z, size);
    }
  }
  // Shore rocks: on the lip, down the drop and out in the water.
  for (let z = 320; z > -420; ) {
    z -= (Math.abs(z) < 70 ? 1.3 + R.next() * 1.6 : 3 + R.next() * 5) / scale;
    const sx = layout.shoreX(z);
    const n = 1 + (R.next() < 0.5 ? 1 : 0);
    for (let i = 0; i < n; i++) {
      const off = core(z) ? 0 + R.next() * 3.5 : -0.4 + R.next() * 4.5;
      const x = sx + off;
      const size = 0.35 + Math.pow(R.next(), 1.5) * (off > 1.5 ? 2.4 : 1.2);
      const ground = layout.terrainHeight(x, z);
      add(placeRock(N, R, layout, x, z, size, { y: Math.max(ground, SEA_LEVEL - 1.2) }), x, z, size);
    }
  }
  // Lone boulders out on the floor, beyond the fighting area and down the pass.
  for (let i = 0; i < 70 * scale; i++) {
    const z = R.next() < 0.3 ? 20 + R.next() * 120 : -50 - Math.pow(R.next(), 0.7) * 380;
    const cx = layout.cliffX(z);
    const sx = layout.shoreX(z);
    const f = R.next();
    // Keep the view down the pass to the army open.
    if (z < -50 && z > -260 && f > 0.25 && f < 0.75) continue;
    const x = lerp(cx + 3, sx - 2, f);
    if (Math.abs(z - WALL.z) < 4) continue;
    const size = 0.5 + Math.pow(R.next(), 2) * 1.8;
    add(placeRock(N, R, layout, x, z, size), x, z, size);
  }
  // Floor stones: small, flat-sitting limestone pieces; sparse on the trampled line.
  const stones = [];
  const stonesFar = [];
  const count = Math.round((quality === 'high' ? 700 : quality === 'low' ? 260 : 480));
  for (let i = 0; i < count; i++) {
    const z = 45 - Math.pow(R.next(), 1.2) * 130;
    const cx = layout.cliffX(z);
    const sx = layout.shoreX(z);
    const x = lerp(cx + 0.8, sx - 0.2, R.next());
    const calm = layout.calmWeight(x, z);
    if (R.next() < calm * 0.65) continue;
    const size = 0.05 + Math.pow(R.next(), 2.2) * 0.3;
    const res = placeRock(N, R, layout, x, z, size, { pebble: true, flat: 0.35 + R.next() * 0.3, sink: 0.25 });
    (isNear(x, z) ? stones : stonesFar).push(res.g);
  }
  // Rubble at the foot of the wall.
  for (let i = 0; i < 60 * scale; i++) {
    const side = R.next() < 0.5 ? -1 : 1;
    const x = side < 0 ? lerp(-8.5, -2.3, R.next()) : lerp(2.3, 9, R.next());
    const z = WALL.z + (R.next() < 0.5 ? -1 : 1) * (WALL.halfDepth + 0.1 + R.next() * 1.3);
    const size = 0.15 + R.next() * 0.35;
    stones.push(placeRock(N, R, layout, x, z, size, { pebble: R.next() < 0.6, sink: 0.2 }).g);
  }
  // Capstones and end blocks of the wall.
  for (const st of extraStones) {
    stones.push(placeRock(N, R, layout, st.x, st.z, st.size, { y: st.y, flat: st.flat, sink: st.sink }).g);
  }
  return {
    near: staticMesh(near.concat(stones), mat, 'rocksNear', true),
    far: staticMesh(far.concat(stonesFar), mat, 'rocksFar', false),
    colliders,
  };
}

/** Dry grass tufts and evergreen scrub. */
export function buildVegetation(layout, grassMat, scrubMat, cliffSpots, quality) {
  const R = createRng(9157);
  const grass = [];
  const scrub = [];
  const scale = quality === 'low' ? 0.5 : quality === 'high' ? 1.3 : 1;
  const tuft = (x, z, s) => {
    const y = layout.terrainHeight(x, z);
    if (y < SEA_LEVEL + 0.2) return;
    const dry = R.next();
    const tint = [lerp(0.95, 0.72, dry * 0.5), lerp(0.92, 0.82, dry), lerp(0.75, 0.6, dry)];
    grass.push(placeCard(R, x, y - 0.03, z, 0.45 * s + R.next() * 0.3 * s, 0.35 * s + R.next() * 0.35 * s, 3, tint, true, 1));
  };
  // Along the cliff foot and the shore lip, and sparse on the floor.
  for (let i = 0; i < 1100 * scale; i++) {
    const z = 60 - Math.pow(R.next(), 1.15) * 230;
    const cx = layout.cliffX(z);
    const sx = layout.shoreX(z);
    const r = R.next();
    let x;
    if (r < 0.35) x = cx + 0.3 + Math.pow(R.next(), 1.5) * 3.2;
    else if (r < 0.65) x = sx - Math.pow(R.next(), 1.5) * 2.2;
    else x = lerp(cx + 3, sx - 1.5, R.next());
    const calm = layout.calmWeight(x, z);
    const core = smoothstep(9, 4, Math.abs(z + 2)) * smoothstep(8, 5, Math.abs(x - 0.8));
    if (R.next() < Math.max(core * 0.92, calm * 0.5)) continue;
    // Clumps: a few tufts together.
    const n = 1 + Math.floor(R.next() * 3);
    for (let k = 0; k < n; k++) tuft(x + (R.next() - 0.5) * 0.8, z + (R.next() - 0.5) * 0.8, 0.7 + R.next() * 0.6);
  }
  // Scrub on the talus, around the springs' margins and the far floor.
  const bush = (x, y, z, s, quads = 3) => {
    const t = 0.85 + R.next() * 0.3;
    scrub.push(placeCard(R, x, y - 0.08 * s, z, s * (0.9 + R.next() * 0.4), s * (0.7 + R.next() * 0.35), quads, [t, t * (0.95 + R.next() * 0.1), t * 0.9], false, 0.35));
  };
  for (let i = 0; i < 260 * scale; i++) {
    const z = 300 - R.next() * 700;
    if (z > -12 && z < 9) continue;
    const cx = layout.cliffX(z);
    const x = cx + 0.2 + Math.pow(R.next(), 1.6) * 3;
    bush(x, layout.terrainHeight(x, z), z, 0.7 + R.next() * 0.9);
  }
  for (let i = 0; i < 90 * scale; i++) {
    const z = -40 - R.next() * 380;
    const x = lerp(layout.cliffX(z) + 3, layout.shoreX(z) - 1, R.next());
    bush(x, layout.terrainHeight(x, z), z, 0.8 + R.next() * 1.2);
  }
  // On the cliff: ledges, the upper slope, and a ragged line of trees along the crest.
  for (const s of cliffSpots.ledgeSpots) {
    if (R.next() < 0.09 * scale) bush(s[0], s[1], s[2], 0.9 + Math.min(s[3], 1.5) * 0.8, 2);
  }
  for (const s of cliffSpots.slopeSpots) {
    if (R.next() < 0.12 * scale) bush(s[0] + 0.5, s[1], s[2], 2 + R.next() * 3, 2);
  }
  for (const s of cliffSpots.crestSpots) {
    if (R.next() < 0.55) bush(s[0] + 1.5, s[1], s[2], 5 + R.next() * 6, 2);
  }
  return {
    grass: staticMesh(grass, grassMat, 'grass', false),
    scrub: staticMesh(scrub, scrubMat, 'scrub', false),
  };
}

/**
 * The Phocian Wall: rough fieldstone, 2.5 m high and 1.8 m thick across the pass at z ≈ 12,
 * with a 4 m gap at x ∈ [−2, 2]. Returns the mesh and its two box colliders.
 */
export function buildWall(layout, mat) {
  const N = layout.noise;
  const segs = [
    [layout.cliffX(WALL.z) - 0.8, WALL.gapMinX],
    [WALL.gapMaxX, layout.shoreX(WALL.z) + 0.6],
  ];
  const geoms = [];
  const colliders = [];
  const z0 = WALL.z - WALL.halfDepth;
  const z1 = WALL.z + WALL.halfDepth;
  const batter = 0.16;
  const tile = 3.1;
  const stones = [];
  const topAt = (x) => {
    let t = WALL.height - 0.25 + 0.14 * N.noise2(x * 0.6, 3.3) + 0.06 * N.noise2(x * 2.5, 1.1);
    // Broken, lower courses beside the gap and here and there along the wall.
    const dg = Math.min(Math.abs(x - WALL.gapMinX), Math.abs(x - WALL.gapMaxX));
    t -= 0.4 * (1 - smoothstep(0.2, 1.6, dg)) * (0.6 + 0.4 * N.noise2(x * 2, 7));
    t -= 0.5 * smoothstep(0.45, 0.8, N.noise2(x * 0.28, 9.2));
    return t;
  };
  // The faces bulge and lean a little where the courses have settled.
  const bulge = (x, y) => 0.1 * N.noise2(x * 0.45, 2.2) * smoothstep(0, 1.2, y) + 0.05 * N.noise2(x * 1.3, y * 0.8);
  for (const [xa, xb] of segs) {
    const nx = Math.max(2, Math.ceil((xb - xa) / 0.32));
    const ny = 9;
    const nz = 6;
    const bump = (x, y, z) => 0.05 * N.noise3(x * 2.3, y * 2.3, z * 2.3) + 0.025 * N.noise3(x * 6.1, y * 6.1, z * 6.1);
    // Front (−z, facing the line) and back (+z) faces, top, and the gap-side end.
    const face = (side) => {
      const pos = [];
      const uv = [];
      for (let j = 0; j <= ny; j++) {
        for (let i = 0; i <= nx; i++) {
          const x = lerp(xa, xb, i / nx);
          const ground = layout.terrainHeight(x, side < 0 ? z0 : z1);
          const top = topAt(x);
          const t = j / ny;
          const y = lerp(ground - 0.5, top, t);
          const lean = batter * clamp(y / WALL.height, 0, 1);
          const z = (side < 0 ? z0 + lean : z1 - lean) + side * (bump(x, y, side) * (j > 0 && j < ny ? 1 : 0.3) + bulge(x, y) * side);
          pos.push(x, y, z);
          uv.push(x / tile, y / tile);
        }
      }
      const index = [];
      for (let j = 0; j < ny; j++) {
        for (let i = 0; i < nx; i++) {
          const a = j * (nx + 1) + i;
          const b = a + 1;
          const d = a + nx + 1;
          const e = d + 1;
          if (side < 0) index.push(a, d, b, b, d, e);
          else index.push(a, b, d, b, e, d);
        }
      }
      return gridToGeom(pos, uv, index);
    };
    const top = () => {
      const pos = [];
      const uv = [];
      for (let k = 0; k <= nz; k++) {
        for (let i = 0; i <= nx; i++) {
          const x = lerp(xa, xb, i / nx);
          const tz = k / nz;
          const z = lerp(z0 + batter, z1 - batter, tz);
          const y = topAt(x) + 0.08 * Math.sin(Math.PI * tz) + bump(x, 3, z) * 1.5;
          pos.push(x, y, z);
          uv.push(x / tile, z / tile);
        }
      }
      const index = [];
      for (let k = 0; k < nz; k++) {
        for (let i = 0; i < nx; i++) {
          const a = k * (nx + 1) + i;
          const b = a + 1;
          const d = a + nx + 1;
          const e = d + 1;
          index.push(a, d, b, b, d, e);
        }
      }
      return gridToGeom(pos, uv, index);
    };
    const end = (x, facing) => {
      const pos = [];
      const uv = [];
      for (let j = 0; j <= ny; j++) {
        for (let k = 0; k <= nz; k++) {
          const t = j / ny;
          const zz = lerp(z0, z1, k / nz);
          const ground = layout.terrainHeight(x, zz);
          const y = lerp(ground - 0.5, topAt(x), t);
          const lean = batter * clamp(y / WALL.height, 0, 1);
          const z = lerp(z0 + lean, z1 - lean, k / nz);
          pos.push(x + facing * bump(x, y, z) * 1.6, y, z);
          uv.push(z / tile, y / tile);
        }
      }
      const index = [];
      for (let j = 0; j < ny; j++) {
        for (let k = 0; k < nz; k++) {
          const a = j * (nz + 1) + k;
          const b = a + 1;
          const d = a + nz + 1;
          const e = d + 1;
          if (facing > 0) index.push(a, d, b, b, d, e);
          else index.push(a, b, d, b, e, d);
        }
      }
      return gridToGeom(pos, uv, index);
    };
    geoms.push(face(-1), face(1), top());
    if (xb === WALL.gapMinX) geoms.push(end(xb, 1));
    if (xa === WALL.gapMaxX) geoms.push(end(xa, -1));
    if (xb > 5) geoms.push(end(xb, 1));
    colliders.push({ type: 'box', min: [xa, -1, z0 - 0.12], max: [xb, WALL.height + 0.3, z1 + 0.12] });
    // Capstones along the top, two irregular rows, and big blocks at the gap ends.
    const R = createRng(Math.round(xa * 100) + 5000);
    for (let x = xa + 0.3; x < xb - 0.2; x += 0.42 + R.next() * 0.3) {
      if (R.next() < 0.12) continue;
      for (const zz of [WALL.z - 0.42, WALL.z + 0.42]) {
        if (R.next() < 0.25) continue;
        const size = 0.34 + R.next() * 0.3;
        stones.push({ x: x + (R.next() - 0.5) * 0.2, z: zz + (R.next() - 0.5) * 0.25, y: topAt(x) - 0.04, size, flat: 0.5 + R.next() * 0.2, sink: 0.35 });
      }
    }
    for (const ex of [xa, xb]) {
      if (Math.abs(ex) > 2.5) continue;
      const inward = ex < 0 ? -1 : 1;
      for (let i = 0; i < 4; i++) {
        const y = 0.2 + i * 0.6;
        stones.push({ x: ex - inward * (0.18 + R.next() * 0.15), z: WALL.z + (R.next() - 0.5) * 1.1, y, size: 0.45 + R.next() * 0.2, flat: 0.55, sink: 0.4 });
      }
    }
  }
  for (const g of geoms) {
    const p = g.attributes.position;
    withAttributes(g, {
      color: [1, 1, 1],
      ao: (i) => 0.55 + 0.45 * smoothstep(-0.3, 1.4, p.getY(i)),
    });
    const col = g.attributes.color;
    for (let i = 0; i < p.count; i++) {
      const y = p.getY(i);
      const dust = 1 - smoothstep(0, 0.9, y);
      const v = 0.92 + 0.12 * N.noise2(p.getX(i) * 0.4, y * 0.6);
      col.setXYZ(i, v * (1 + dust * 0.08), v * (1 + dust * 0.04), v * (1 - dust * 0.04));
    }
  }
  const mesh = staticMesh(geoms, mat, 'phocianWall', true);
  return { mesh, colliders, stones };
}

function gridToGeom(pos, uv, index) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(index);
  g.computeVertexNormals();
  return g;
}

/** Pools, mineral crusts and steam for the hot springs. */
export function buildSprings(layout, poolMat, crustMat, steamMat, quality) {
  const pools = [];
  const crusts = [];
  for (const s of layout.springs) {
    // Water level: the lowest rim height.
    let rim = Infinity;
    for (let a = 0; a < 12; a++) {
      const ang = (a / 12) * Math.PI * 2;
      rim = Math.min(rim, layout.terrainHeight(s.x + Math.cos(ang) * s.pool, s.z + Math.sin(ang) * s.pool));
    }
    s.level = rim - 0.03;
    const pg = new THREE.CircleGeometry(s.pool * 1.08, 28).rotateX(-Math.PI / 2);
    pg.translate(s.x, s.level, s.z);
    withAttributes(pg, { color: [1, 1, 1] });
    pools.push(pg);
    // Crust decal draped on the terrain.
    const n = 12;
    const R = s.crust;
    const pos = [];
    const uv = [];
    const index = [];
    for (let j = 0; j <= n; j++) {
      for (let i = 0; i <= n; i++) {
        const u = i / n;
        const v = j / n;
        const x = s.x + (u - 0.5) * 2 * R;
        const z = s.z + (v - 0.5) * 2 * R * 1.25;
        pos.push(x, Math.max(layout.terrainHeight(x, z), s.level) + 0.025, z);
        uv.push(u, v);
      }
    }
    for (let j = 0; j < n; j++) {
      for (let i = 0; i < n; i++) {
        const a = j * (n + 1) + i;
        index.push(a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2);
      }
    }
    const cg = new THREE.BufferGeometry();
    cg.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    cg.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    cg.setIndex(index);
    cg.computeVertexNormals();
    withAttributes(cg, { color: [1, 1, 1] });
    crusts.push(cg);
  }
  const poolMesh = staticMesh(pools, poolMat, 'springPools', false);
  const crustMesh = staticMesh(crusts, crustMat, 'springCrust', false);
  crustMesh.receiveShadow = true;
  crustMesh.renderOrder = -5;
  // Steam: GPU-animated camera-facing quads, one instanced draw.
  const per = quality === 'low' ? 10 : quality === 'high' ? 22 : 16;
  const count = per * layout.springs.length;
  const quad = new THREE.PlaneGeometry(1, 1);
  const ig = new THREE.InstancedBufferGeometry();
  ig.index = quad.index;
  ig.setAttribute('position', quad.attributes.position);
  ig.setAttribute('uv', quad.attributes.uv);
  const base = new Float32Array(count * 4);
  const seed = new Float32Array(count * 4);
  const R = createRng(777);
  let k = 0;
  for (const s of layout.springs) {
    for (let i = 0; i < per; i++) {
      base[k * 4] = s.x + (R.next() - 0.5) * s.pool;
      base[k * 4 + 1] = s.level;
      base[k * 4 + 2] = s.z + (R.next() - 0.5) * s.pool;
      base[k * 4 + 3] = s.steam;
      seed[k * 4] = i / per + R.next() * 0.04;
      seed[k * 4 + 1] = R.next();
      seed[k * 4 + 2] = R.next();
      seed[k * 4 + 3] = R.next();
      k++;
    }
  }
  ig.setAttribute('iBase', new THREE.InstancedBufferAttribute(base, 4));
  ig.setAttribute('iSeed', new THREE.InstancedBufferAttribute(seed, 4));
  ig.instanceCount = count;
  ig.boundingSphere = new THREE.Sphere(new THREE.Vector3(-7, 3, -5), 45);
  const steam = new THREE.Mesh(ig, steamMat);
  steam.name = 'steam';
  steam.frustumCulled = true;
  steam.matrixAutoUpdate = false;
  steam.renderOrder = 5;
  return { pools: poolMesh, crust: crustMesh, steam };
}

/** Steam shader: rising, expanding, fading puffs lit by the sun and sky colours. */
export function makeSteamMaterial(map, uniforms) {
  return new THREE.ShaderMaterial({
    name: 'steam',
    uniforms: {
      map: { value: map },
      uTime: uniforms.time,
      uLight: uniforms.light,
      uShade: uniforms.shade,
      uWind: { value: new THREE.Vector2(-0.35, 0.12) },
      uSunDir: uniforms.sunDir,
    },
    vertexShader: /* glsl */ `
      attribute vec4 iBase;
      attribute vec4 iSeed;
      uniform float uTime;
      uniform vec2 uWind;
      uniform vec3 uSunDir;
      varying vec2 vUv;
      varying float vAlpha;
      varying float vLit;
      void main() {
        float life = 4.0 + iSeed.y * 3.0;
        float age = fract( uTime / life + iSeed.x );
        vec3 p = iBase.xyz;
        float rise = age * ( 1.8 + iSeed.z * 1.4 );
        p.y += 0.15 + rise;
        p.xz += uWind * rise * rise * 0.6;
        p.x += sin( uTime * 0.6 + iSeed.w * 6.283 ) * 0.3 * age;
        p.z += cos( uTime * 0.45 + iSeed.y * 6.283 ) * 0.3 * age;
        float size = mix( 0.4, 2.6, sqrt( age ) ) * ( 0.7 + iSeed.z * 0.6 ) * iBase.w;
        vec4 mv = viewMatrix * vec4( p, 1.0 );
        float rot = iSeed.w * 6.283 + uTime * 0.15 * ( iSeed.x - 0.5 );
        vec2 c = ( uv - 0.5 ) * size;
        mv.xy += mat2( cos( rot ), sin( rot ), -sin( rot ), cos( rot ) ) * c;
        gl_Position = projectionMatrix * mv;
        vUv = uv;
        vAlpha = smoothstep( 0.0, 0.15, age ) * ( 1.0 - smoothstep( 0.35, 1.0, age ) ) * iBase.w;
        // Puffs between the eye and the sun glow (forward scattering).
        vec3 toCam = normalize( cameraPosition - p );
        vLit = 0.55 + 0.45 * max( 0.0, -dot( toCam, uSunDir ) );
      }
    `,
    fragmentShader: /* glsl */ `
      uniform sampler2D map;
      uniform vec3 uLight;
      uniform vec3 uShade;
      varying vec2 vUv;
      varying float vAlpha;
      varying float vLit;
      void main() {
        vec4 t = texture2D( map, vUv );
        vec3 col = mix( uShade, uLight, vLit );
        gl_FragColor = vec4( col, t.a * vAlpha * 0.5 );
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }
    `,
    transparent: true,
    depthWrite: false,
    fog: false,
  });
}
