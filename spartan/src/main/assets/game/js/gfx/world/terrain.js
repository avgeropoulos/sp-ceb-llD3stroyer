/**
 * The pass floor: one curvilinear grid whose columns follow the cliff foot and the shore lip, so
 * the talus and the seaward drop get dense vertices everywhere while the open floor stays light.
 * Rows are dense near the line and grow geometrically toward the far end of the pass.
 */
import * as THREE from 'three';
import { clamp, lerp, smoothstep } from '../../core/math.js';
import { Z_NEAR, Z_FAR, SEA_LEVEL } from './layout.js';

/** Column definitions: anchored to the cliff foot, spread across the floor, or anchored to the lip. */
function makeColumns() {
  const cols = [];
  for (const d of [-5, -2.5, -1.2, -0.5, 0, 0.35, 0.7, 1.05, 1.45, 1.9, 2.4, 3]) cols.push({ a: 'c', d });
  const mid = 15;
  for (let i = 0; i < mid; i++) cols.push({ a: 'm', f: i / (mid - 1) });
  for (const d of [-1.3, -0.8, -0.45, -0.15, 0.15, 0.45, 0.8, 1.2, 1.65, 2.15, 2.75, 3.5, 4.5, 6, 8.5, 12, 18, 28]) cols.push({ a: 's', d });
  return cols;
}

function makeRows() {
  const rows = [];
  // Behind the wall the pass continues east; sparse rows there.
  let z = Z_NEAR;
  while (z > 72) {
    rows.push(z);
    z -= Math.max(1, (z - 60) * 0.12);
  }
  for (z = 72; z > -48; z -= 0.75) rows.push(z);
  let dz = 0.75;
  while (z > Z_FAR) {
    rows.push(z);
    dz = Math.min(45, dz * 1.04);
    z -= dz;
  }
  rows.push(Z_FAR);
  return rows;
}

export function columnX(col, cx, sx) {
  if (col.a === 'c') return cx + col.d;
  if (col.a === 's') return sx + col.d;
  return lerp(cx + 3.5, sx - 1.8, col.f);
}

/**
 * @returns {{ mesh: THREE.Mesh, geometry: THREE.BufferGeometry }}
 */
export function buildTerrain(layout, material, opts = {}) {
  const cols = makeColumns();
  const rows = makeRows();
  const nc = cols.length;
  const nr = rows.length;
  const N = layout.noise;
  const pos = new Float32Array(nc * nr * 3);
  const uv = new Float32Array(nc * nr * 2);
  const col = new Float32Array(nc * nr * 3);
  const ao = new Float32Array(nc * nr);
  const splat = new Float32Array(nc * nr);
  const tile = opts.tile || 2.6;
  const springs = layout.springs;
  const c = [0, 0, 0];
  for (let j = 0; j < nr; j++) {
    const z = rows[j];
    const cx = layout.cliffX(z);
    const sx = layout.shoreX(z);
    for (let i = 0; i < nc; i++) {
      const k = j * nc + i;
      const x = columnX(cols[i], cx, sx);
      const y = layout.terrainHeight(x, z);
      pos[k * 3] = x;
      pos[k * 3 + 1] = y;
      pos[k * 3 + 2] = z;
      uv[k * 2] = x / tile;
      uv[k * 2 + 1] = -z / tile;
      groundColour(N, x, y, z, cx, sx, springs, c);
      col[k * 3] = c[0];
      col[k * 3 + 1] = c[1];
      col[k * 3 + 2] = c[2];
      // Sky blocked by the cliff, the wall's base, and the water's depth.
      let o = 0.62 + 0.38 * smoothstep(-0.5, 13, x - cx);
      if (Math.abs(z - 12) < 2.2 && (x < -1.6 || x > 1.6)) o *= 0.75 + 0.25 * smoothstep(0.9, 2.2, Math.abs(z - 12));
      if (y < SEA_LEVEL) o *= 0.7;
      ao[k] = o;
      // Gravel and sand: the talus at the cliff foot and the shore below the lip.
      const talus = (1 - smoothstep(0.6, 2.6, x - cx)) * 0.6;
      const shore = smoothstep(-0.6, 0.6, x - sx);
      const patches = smoothstep(0.35, 0.65, N.noise2(x * 0.11 + 7, z * 0.11)) * 0.45;
      splat[k] = clamp(Math.max(talus * 0.85, shore, patches * (1 - layout.calmWeight(x, z) * 0.7)), 0, 1);
    }
  }
  const index = [];
  for (let j = 0; j < nr - 1; j++) {
    for (let i = 0; i < nc - 1; i++) {
      const a = j * nc + i;
      const b = a + 1;
      const d = a + nc;
      const e = d + 1;
      // Rows run toward −z and columns toward +x: (a, b, d) faces up.
      index.push(a, b, d, b, e, d);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  g.setAttribute('ao', new THREE.BufferAttribute(ao, 1));
  g.setAttribute('splat', new THREE.BufferAttribute(splat, 1));
  g.setIndex(index);
  g.computeVertexNormals();
  g.computeBoundingSphere();
  const mesh = new THREE.Mesh(g, material);
  mesh.name = 'terrain';
  mesh.receiveShadow = true;
  mesh.castShadow = false;
  mesh.matrixAutoUpdate = false;
  mesh.updateMatrix();
  return { mesh, geometry: g };
}

/** Linear tint multipliers for the dirt texture at a ground point. */
function groundColour(N, x, y, z, cx, sx, springs, out) {
  let r = 1;
  let g = 1;
  let b = 1;
  // Broad variation: warmer, redder earth and greyer dusty patches.
  const v1 = N.fbm2(x * 0.045 + 3.3, z * 0.045, 3);
  const v2 = N.fbm2(x * 0.15 - 9.1, z * 0.15, 2);
  const bright = 1 + v1 * 0.16 + v2 * 0.06;
  r *= bright * (1 + v1 * 0.05);
  g *= bright;
  b *= bright * (1 - v1 * 0.06);
  // The trampled line: lighter and dustier.
  const trample = smoothstep(16, 4, Math.abs(z + 3)) * smoothstep(9.5, 6.5, Math.abs(x - 0.6));
  r *= 1 + trample * 0.07;
  g *= 1 + trample * 0.07;
  b *= 1 + trample * 0.09;
  // Dry vegetation stain toward the margins and far down the pass.
  const margin = Math.max(1 - smoothstep(0.5, 4, x - cx), smoothstep(-4, -0.8, x - sx));
  const far = smoothstep(-60, -260, z) + smoothstep(25, 60, z) * 0.5;
  const veg = clamp(smoothstep(0.0, 0.5, N.fbm2(x * 0.05, z * 0.05 + 40, 3)) * (0.35 + margin * 0.5 + far * 0.6), 0, 1);
  r *= 1 - veg * 0.14;
  g *= 1 - veg * 0.11;
  b *= 1 - veg * 0.3;
  // Scree at the cliff foot is greyer.
  const scree = 1 - smoothstep(0, 2.8, x - cx);
  r *= 1 - scree * 0.05;
  b *= 1 + scree * 0.04;
  // Wet band at the waterline, and the seabed seen through water.
  if (y < SEA_LEVEL + 0.5) {
    const wet = smoothstep(SEA_LEVEL + 0.5, SEA_LEVEL - 0.1, y);
    r *= 1 - wet * 0.45;
    g *= 1 - wet * 0.38;
    b *= 1 - wet * 0.3;
    if (y < SEA_LEVEL) {
      const deep = smoothstep(SEA_LEVEL, SEA_LEVEL - 3, y);
      r *= 0.75 - deep * 0.3;
      g *= 0.92 - deep * 0.2;
      b *= 0.95 - deep * 0.15;
    }
  }
  // Mineral bleaching downslope of the springs.
  for (const s of springs) {
    const d = Math.hypot(x - s.x, (z - s.z) * 0.8);
    const m = 1 - smoothstep(s.crust * 0.5, s.crust * 2.2, d);
    if (m > 0) {
      r *= 1 + m * 0.18;
      g *= 1 + m * 0.16;
      b *= 1 + m * 0.1;
    }
  }
  out[0] = r;
  out[1] = g;
  out[2] = b;
  return out;
}
