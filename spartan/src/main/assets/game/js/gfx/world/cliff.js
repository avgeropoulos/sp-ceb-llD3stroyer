/**
 * Mount Kallidromos: a layered limestone wall rising from the cliff foot (x ≈ −9, facing +X),
 * with dipping strata ledges, buttresses and gullies, a steep upper slope and an irregular crest.
 * Built as a (z, height) grid in three chunks so only the near chunk casts shadows.
 */
import * as THREE from 'three';
import { clamp, smoothstep } from '../../core/math.js';
import { toCreasedNormals } from 'three/addons/BufferGeometryUtils.js';
import { Z_NEAR, Z_FAR } from './layout.js';

/** Faces meeting at more than this angle keep separate normals (crisp ledges and arrises). */
const CREASE = 0.7;

const LEDGES = [2.7, 5.9, 8.9, 12.7, 16.8, 21.5, 26.7, 32.4, 38.5, 45.1, 51.6];
const WALL_ROWS_TOP = 58;
const UV_TILE = 10;

function makeColumns() {
  const zs = [];
  let z = Z_NEAR + 40;
  while (z > 70) {
    zs.push(z);
    z -= Math.max(1, (z - 60) * 0.1);
  }
  for (z = 70; z > -55; z -= 0.75) zs.push(z);
  let dz = 0.75;
  while (z > Z_FAR - 60) {
    zs.push(z);
    dz = Math.min(32, dz * 1.045);
    z -= dz;
  }
  zs.push(Z_FAR - 60);
  return zs;
}

function makeBaseRows() {
  const ys = [-2.5, -0.8];
  // Denser rows low down, where the player looks at the face from a few metres.
  for (let y = 0.3; y < WALL_ROWS_TOP; y += y < 14 ? 0.62 : 1.15) ys.push(y);
  for (const l of LEDGES) ys.push(l - 0.2, l + 0.2);
  ys.sort((a, b) => a - b);
  const out = [];
  for (const y of ys) if (!out.length || y - out[out.length - 1] > 0.12) out.push(y);
  return out;
}

const UPPER = [0.1, 0.22, 0.36, 0.52, 0.68, 0.82, 0.93, 1];

/** Smooth maximum (polynomial, blend width k). */
function smax(a, b, k) {
  const h = Math.max(k - Math.abs(a - b), 0) / k;
  return Math.max(a, b) + h * h * k * 0.25;
}

/**
 * @returns {{ meshes: THREE.Mesh[], near: THREE.Mesh, ledgeSpots: number[][], slopeSpots: number[][], crestSpots: number[][] }}
 */
export function buildCliff(layout, material) {
  const N = layout.noise;
  const zs = makeColumns();
  const base = makeBaseRows();
  const nRows = base.length + UPPER.length + 2;
  const nCols = zs.length;
  const P = new Float32Array(nCols * nRows * 3);
  const UV = new Float32Array(nCols * nRows * 2);
  const COL = new Float32Array(nCols * nRows * 3);
  const AO = new Float32Array(nCols * nRows);
  const ledgeSpots = [];
  const slopeSpots = [];
  const crestSpots = [];
  const ys = new Float32Array(nRows);
  const band = new Float32Array(LEDGES.length + 1);

  for (let c = 0; c < nCols; c++) {
    const z = zs[c];
    const cx = layout.cliffX(z);
    const hWall = 34 + 13 * N.fbm2(z * 0.011, 2.5, 3) + 6 * N.noise2(z * 0.05, 9.1);
    const hTop = hWall + 70 + 45 * N.fbm2(z * 0.006, 6.6, 3);
    const dip = 1.4 * Math.sin(z * 0.012) + 0.7 * N.noise2(z * 0.02, 5.5);
    // Buttresses (ridged, sharp crests) and gullies between them.
    const gully = 4.2 * (N.ridge2(z * 0.028, 1.7, 3) * 0.5 + 0.5 - 0.55) + 1.1 * N.noise2(z * 0.11, 3.3);
    const setbacks = LEDGES.map((l, k) => clamp(0.2 + 1.0 * (N.noise2(z * 0.05 + k * 7.1, k) * 0.5 + 0.5) * (k < 2 ? 0.6 : 1), 0.1, 1.5));
    // Row heights for this column.
    let r = 0;
    for (; r < base.length; r++) ys[r] = base[r] + (base[r] > 1 ? dip : 0);
    const upperFrom = WALL_ROWS_TOP + dip;
    for (let u = 0; u < UPPER.length; u++) ys[r++] = upperFrom + (hTop - upperFrom) * UPPER[u];
    ys[r++] = hTop + 7;
    ys[r++] = hTop + 15;
    // Each bed band (between two ledges) is a near-vertical slab with its own outline along z:
    // broad buttresses and squared-off blocks. Bands step back at every ledge.
    for (let k = 0; k < band.length; k++) {
      const a = N.fbm2(z * 0.045 + k * 13.7, k * 3.1, 3);
      const b = N.fbm2(z * 0.15 + k * 5.3, k * 7.7 + 40, 2) * 2.4 + 0.5;
      const bq = Math.floor(b);
      band[k] = (1.2 * a + 0.42 * (bq + smoothstep(0.6, 1, b - bq))) * (k === 0 ? 0.3 : 1);
    }
    let arc = 0;
    let px = 0;
    let py = 0;
    for (r = 0; r < nRows; r++) {
      const y = ys[r];
      let s = Math.max(0, y) * 0.03;
      let off = band[0];
      for (let k = 0; k < LEDGES.length; k++) {
        const ly = LEDGES[k] + dip;
        if (ly > hWall) break;
        const w = smoothstep(ly - 0.22, ly + 0.22, y);
        s += setbacks[k] * w;
        off += (band[k + 1] - band[k]) * w;
        // A hard bed's lip throws a crisp shadow line just above each ledge.
        const t = (y - ly) / 1.4;
        if (t > 0 && t < 1) s -= 0.22 * smoothstep(0, 0.15, t) * (1 - smoothstep(0.4, 1, t));
      }
      s += off;
      s += gully * smoothstep(1.5, 9, y);
      // The slab faces are not quite planar.
      s += 0.32 * N.fbm2(z * 0.28, y * 0.16, 2) * smoothstep(0, 2.5, y);
      // The foot never juts out over the pass (the x = −9 plane collider stands for it).
      if (y < 7) s = smax(s, -0.35 - smoothstep(2, 7, y), 0.35);
      if (y > hWall) s += (y - hWall) / 1.2 + 6 * N.fbm2(z * 0.03, y * 0.03 + 4, 2) * smoothstep(hWall, hWall + 20, y);
      if (y > hTop) s += (y - hTop) * 4.5;
      const x = cx - s;
      const k = r * nCols + c;
      P[k * 3] = x;
      P[k * 3 + 1] = y;
      P[k * 3 + 2] = z;
      if (r > 0) arc += Math.hypot(x - px, y - py);
      px = x;
      py = y;
      UV[k * 2] = -z / UV_TILE;
      UV[k * 2 + 1] = arc / UV_TILE;
      // Tint: rain streaks down the face, ochre staining, scrubby upper slopes.
      const streak = smoothstep(0.15, 0.6, N.fbm2(z * 0.16, y * 0.012 + 3, 3));
      const ochre = smoothstep(0.0, 0.45, N.fbm2(z * 0.025 + 5, y * 0.03, 3));
      const grey = smoothstep(0.1, 0.5, N.fbm2(z * 0.03 - 7, y * 0.04 + 2, 3));
      const veg = smoothstep(-0.05, 0.35, N.fbm2(z * 0.045, y * 0.045 + 9, 3)) * smoothstep(hWall - 3, hWall + 10, y);
      let cr = (1 - streak * 0.45) * (1 + ochre * 0.16) * (1 - grey * 0.22);
      let cg = (1 - streak * 0.44) * (1 - ochre * 0.02) * (1 - grey * 0.2);
      let cb = (1 - streak * 0.4) * (1 - ochre * 0.2) * (1 - grey * 0.15);
      cr *= 1 - veg * 0.42;
      cg *= 1 - veg * 0.3;
      cb *= 1 - veg * 0.52;
      const dust = 1 - smoothstep(0, 4, y);
      cr *= 1 + dust * 0.06;
      cg *= 1 + dust * 0.04;
      COL[k * 3] = cr;
      COL[k * 3 + 1] = cg;
      COL[k * 3 + 2] = cb;
      AO[k] = 0.68 + 0.32 * smoothstep(-0.5, 7, y);
      // Spots for vegetation (collected sparsely).
      if (z > -500 && z < 250) {
        for (let kk = 2; kk < LEDGES.length; kk++) {
          const ly = LEDGES[kk] + dip;
          if (ly < hWall && Math.abs(y - (ly + 0.2)) < 0.01) ledgeSpots.push([x + setbacks[kk] * 0.5, ly + 0.15, z, setbacks[kk]]);
        }
      }
      if (y > hWall + 2 && y < hTop - 2 && r % 2 === 0) slopeSpots.push([x, y, z]);
      if (r === nRows - 3) crestSpots.push([x, y, z]);
    }
  }

  // Rows kept away from the line: every other plain row low on the face, every ledge row, and
  // the whole upper slope (the near chunk keeps them all).
  const allRows = [];
  const sparseRows = [];
  for (let r = 0; r < nRows; r++) {
    allRows.push(r);
    const b = r < base.length ? base[r] : null;
    const ledge = b !== null && LEDGES.some((l) => Math.abs(b - l) < 0.25);
    if (b === null || b > 14 || ledge || r % 2 === 0 || r === base.length - 1) sparseRows.push(r);
  }
  // Shared vertex data → three chunks (behind, near, far) with one shared column at each seam.
  const chunks = [
    { name: 'cliffBehind', from: 34, to: Infinity, cast: false },
    { name: 'cliffNear', from: -42, to: 34, cast: true },
    { name: 'cliffFar', from: -Infinity, to: -42, cast: false },
  ];
  const meshes = [];
  let near = null;
  for (const ch of chunks) {
    const cIdx = [];
    for (let c = 0; c < nCols; c++) if (zs[c] <= ch.to + 1.0 && zs[c] >= ch.from - 1.0) cIdx.push(c);
    let g = gridGeometry(P, UV, COL, AO, ch.cast ? allRows : sparseRows, nCols, cIdx);
    tintLedges(g);
    if (CREASE > 0) {
      const cg = toCreasedNormals(g, CREASE);
      g.dispose();
      g = cg;
    }
    const mesh = new THREE.Mesh(g, material);
    mesh.name = ch.name;
    mesh.castShadow = ch.cast;
    mesh.receiveShadow = true;
    mesh.matrixAutoUpdate = false;
    mesh.updateMatrix();
    meshes.push(mesh);
    if (ch.cast) near = mesh;
  }
  return { meshes, near, ledgeSpots, slopeSpots, crestSpots };
}

function gridGeometry(P, UV, COL, AO, rIdx, nCols, cIdx) {
  const n = cIdx.length;
  const nRows = rIdx.length;
  const pos = new Float32Array(nRows * n * 3);
  const uv = new Float32Array(nRows * n * 2);
  const col = new Float32Array(nRows * n * 3);
  const ao = new Float32Array(nRows * n);
  for (let r = 0; r < nRows; r++) {
    for (let i = 0; i < n; i++) {
      const src = rIdx[r] * nCols + cIdx[i];
      const dst = r * n + i;
      pos.set(P.subarray(src * 3, src * 3 + 3), dst * 3);
      uv.set(UV.subarray(src * 2, src * 2 + 2), dst * 2);
      col.set(COL.subarray(src * 3, src * 3 + 3), dst * 3);
      ao[dst] = AO[src];
    }
  }
  const index = [];
  for (let r = 0; r < nRows - 1; r++) {
    for (let i = 0; i < n - 1; i++) {
      const a = r * n + i;
      const b = a + 1;
      const d = a + n;
      const e = d + 1;
      // Columns run toward −z and rows upward: (a, b, d) faces +X.
      index.push(a, b, d, b, e, d);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  g.setAttribute('ao', new THREE.BufferAttribute(ao, 1));
  g.setIndex(index);
  g.computeVertexNormals();
  g.computeBoundingSphere();
  return g;
}

/** Ledge tops collect dust and scrub; overhang undersides are darker. */
function tintLedges(g) {
  const nrm = g.attributes.normal;
  const col = g.attributes.color;
  const ao = g.attributes.ao;
  for (let i = 0; i < nrm.count; i++) {
    const ny = nrm.getY(i);
    if (ny > 0.35) {
      const t = smoothstep(0.35, 0.8, ny);
      col.setXYZ(i, col.getX(i) * (1 - 0.18 * t), col.getY(i) * (1 - 0.1 * t), col.getZ(i) * (1 - 0.32 * t));
    } else if (ny < -0.15) {
      ao.setX(i, ao.getX(i) * (1 - 0.35 * smoothstep(-0.15, -0.6, ny)));
    }
  }
}
