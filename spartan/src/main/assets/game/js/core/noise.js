/**
 * PURE seeded noise: simplex 2D/3D, fbm and ridged fbm, plus periodic (tileable) gradient
 * noise and Worley cells used by the procedural textures. No DOM, no three.js.
 * Every value function returns roughly [-1, 1] unless documented otherwise.
 */
import { createRng } from './math.js';

const F2 = 0.5 * (Math.sqrt(3) - 1);
const G2 = (3 - Math.sqrt(3)) / 6;
const F3 = 1 / 3;
const G3 = 1 / 6;

// 12 edge-midpoint gradients of a cube (Gustavson). 2D simplex uses their x, y.
const GRAD3 = new Float64Array([
  1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0,
  1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
  0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1,
]);

// 32 evenly spaced unit gradients for the periodic gradient noise.
const PG = 32;
const PGX = new Float64Array(PG);
const PGY = new Float64Array(PG);
for (let i = 0; i < PG; i++) {
  const a = ((i + 0.5) / PG) * Math.PI * 2;
  PGX[i] = Math.cos(a);
  PGY[i] = Math.sin(a);
}

const fade = (t) => t * t * t * (t * (t * 6 - 15) + 10);

/**
 * Creates a seeded noise generator. The same seed always gives the same values.
 * @param {number} seed
 * @returns {{
 *   noise2(x: number, y: number): number,
 *   noise3(x: number, y: number, z: number): number,
 *   fbm2(x: number, y: number, octaves?: number, lac?: number, gain?: number): number,
 *   ridge2(x: number, y: number, oct?: number): number,
 *   warp2(x: number, y: number, amount?: number, octaves?: number): number,
 *   pnoise2(x: number, y: number, px: number, py: number): number,
 *   pfbm2(x: number, y: number, px: number, py: number, octaves?: number, gain?: number): number,
 *   pridge2(x: number, y: number, px: number, py: number, octaves?: number, gain?: number): number,
 *   worley2(x: number, y: number, px: number, py: number, out: Float64Array|number[], jitter?: number): Float64Array|number[],
 *   hash2(i: number, j: number): number,
 * }}
 */
export function createNoise(seed = 1) {
  const rng = createRng((seed >>> 0) * 2654435761 + 1013904223);
  const p = new Uint8Array(256);
  for (let i = 0; i < 256; i++) p[i] = i;
  for (let i = 255; i > 0; i--) {
    const j = Math.floor(rng.next() * (i + 1));
    const t = p[i];
    p[i] = p[j];
    p[j] = t;
  }
  const perm = new Uint8Array(512);
  const permMod12 = new Uint8Array(512);
  for (let i = 0; i < 512; i++) {
    perm[i] = p[i & 255];
    permMod12[i] = perm[i] % 12;
  }
  const seedMix = Math.imul((seed >>> 0) + 0x9e3779b9, 0x85ebca6b) | 0;

  /** Integer lattice hash → uint32. */
  function ihash(i, j) {
    let h = (Math.imul(i, 0x27d4eb2d) ^ Math.imul(j, 0x165667b1) ^ seedMix) | 0;
    h = Math.imul(h ^ (h >>> 15), 0x85ebca6b);
    h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
    return (h ^ (h >>> 16)) >>> 0;
  }

  /** 2D simplex noise, about [-1, 1]. */
  function noise2(xin, yin) {
    const s = (xin + yin) * F2;
    const i = Math.floor(xin + s);
    const j = Math.floor(yin + s);
    const t = (i + j) * G2;
    const x0 = xin - (i - t);
    const y0 = yin - (j - t);
    let i1, j1;
    if (x0 > y0) {
      i1 = 1;
      j1 = 0;
    } else {
      i1 = 0;
      j1 = 1;
    }
    const x1 = x0 - i1 + G2;
    const y1 = y0 - j1 + G2;
    const x2 = x0 - 1 + 2 * G2;
    const y2 = y0 - 1 + 2 * G2;
    const ii = i & 255;
    const jj = j & 255;
    let n = 0;
    let t0 = 0.5 - x0 * x0 - y0 * y0;
    if (t0 > 0) {
      const g = permMod12[ii + perm[jj]] * 3;
      t0 *= t0;
      n += t0 * t0 * (GRAD3[g] * x0 + GRAD3[g + 1] * y0);
    }
    let t1 = 0.5 - x1 * x1 - y1 * y1;
    if (t1 > 0) {
      const g = permMod12[ii + i1 + perm[jj + j1]] * 3;
      t1 *= t1;
      n += t1 * t1 * (GRAD3[g] * x1 + GRAD3[g + 1] * y1);
    }
    let t2 = 0.5 - x2 * x2 - y2 * y2;
    if (t2 > 0) {
      const g = permMod12[ii + 1 + perm[jj + 1]] * 3;
      t2 *= t2;
      n += t2 * t2 * (GRAD3[g] * x2 + GRAD3[g + 1] * y2);
    }
    return 70 * n;
  }

  /** 3D simplex noise, about [-1, 1]. */
  function noise3(xin, yin, zin) {
    const s = (xin + yin + zin) * F3;
    const i = Math.floor(xin + s);
    const j = Math.floor(yin + s);
    const k = Math.floor(zin + s);
    const t = (i + j + k) * G3;
    const x0 = xin - (i - t);
    const y0 = yin - (j - t);
    const z0 = zin - (k - t);
    let i1, j1, k1, i2, j2, k2;
    if (x0 >= y0) {
      if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
      else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
      else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
    } else if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
    else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
    else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
    const x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
    const x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3;
    const x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;
    const ii = i & 255, jj = j & 255, kk = k & 255;
    let n = 0;
    let t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
    if (t0 > 0) {
      const g = permMod12[ii + perm[jj + perm[kk]]] * 3;
      t0 *= t0;
      n += t0 * t0 * (GRAD3[g] * x0 + GRAD3[g + 1] * y0 + GRAD3[g + 2] * z0);
    }
    let t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
    if (t1 > 0) {
      const g = permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]] * 3;
      t1 *= t1;
      n += t1 * t1 * (GRAD3[g] * x1 + GRAD3[g + 1] * y1 + GRAD3[g + 2] * z1);
    }
    let t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
    if (t2 > 0) {
      const g = permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]] * 3;
      t2 *= t2;
      n += t2 * t2 * (GRAD3[g] * x2 + GRAD3[g + 1] * y2 + GRAD3[g + 2] * z2);
    }
    let t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
    if (t3 > 0) {
      const g = permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]] * 3;
      t3 *= t3;
      n += t3 * t3 * (GRAD3[g] * x3 + GRAD3[g + 1] * y3 + GRAD3[g + 2] * z3);
    }
    return 32 * n;
  }

  /** Fractal sum of noise2, normalised to about [-1, 1]. */
  function fbm2(x, y, octaves = 5, lac = 2, gain = 0.5) {
    let sum = 0;
    let amp = 1;
    let norm = 0;
    let f = 1;
    for (let o = 0; o < octaves; o++) {
      // Offsetting each octave avoids the lattice origins lining up.
      sum += amp * noise2(x * f + o * 17.31, y * f - o * 9.73);
      norm += amp;
      amp *= gain;
      f *= lac;
    }
    return sum / norm;
  }

  /** Ridged multifractal (sharp crests), about [-1, 1]; crests near +1. */
  function ridge2(x, y, oct = 5) {
    let sum = 0;
    let amp = 1;
    let norm = 0;
    let f = 1;
    let w = 1;
    for (let o = 0; o < oct; o++) {
      let n = 1 - Math.abs(noise2(x * f + o * 31.7, y * f + o * 11.1));
      n *= n;
      n *= w;
      w = n < 0 ? 0 : n > 1 ? 1 : n;
      sum += n * amp;
      norm += amp;
      amp *= 0.5;
      f *= 2;
    }
    return (sum / norm) * 2 - 1;
  }

  /** Domain-warped fbm (Quilez): fbm(p + amount·fbm2(p)), about [-1, 1]. */
  function warp2(x, y, amount = 1.5, octaves = 4) {
    const qx = fbm2(x + 5.2, y + 1.3, octaves);
    const qy = fbm2(x - 3.7, y + 8.4, octaves);
    return fbm2(x + amount * qx, y + amount * qy, octaves);
  }

  /** Periodic gradient noise on an integer lattice with periods px, py (cells). */
  function pnoise2(x, y, px, py) {
    const fx0 = Math.floor(x);
    const fy0 = Math.floor(y);
    const fx = x - fx0;
    const fy = y - fy0;
    let i0 = fx0 % px;
    if (i0 < 0) i0 += px;
    let j0 = fy0 % py;
    if (j0 < 0) j0 += py;
    const i1 = i0 + 1 === px ? 0 : i0 + 1;
    const j1 = j0 + 1 === py ? 0 : j0 + 1;
    const h00 = ihash(i0, j0) & 31;
    const h10 = ihash(i1, j0) & 31;
    const h01 = ihash(i0, j1) & 31;
    const h11 = ihash(i1, j1) & 31;
    const n00 = PGX[h00] * fx + PGY[h00] * fy;
    const n10 = PGX[h10] * (fx - 1) + PGY[h10] * fy;
    const n01 = PGX[h01] * fx + PGY[h01] * (fy - 1);
    const n11 = PGX[h11] * (fx - 1) + PGY[h11] * (fy - 1);
    const u = fade(fx);
    const v = fade(fy);
    const a = n00 + u * (n10 - n00);
    const b = n01 + u * (n11 - n01);
    return 1.4142 * (a + v * (b - a));
  }

  /**
   * Tileable fbm: x, y in lattice units of the first octave; periods px, py (integers)
   * double with each octave, so the result tiles with period (px, py).
   */
  function pfbm2(x, y, px, py, octaves = 5, gain = 0.5) {
    let sum = 0;
    let amp = 1;
    let norm = 0;
    let f = 1;
    for (let o = 0; o < octaves; o++) {
      sum += amp * pnoise2(x * f + o * 0.371, y * f + o * 0.613, px * f, py * f);
      norm += amp;
      amp *= gain;
      f *= 2;
    }
    return sum / norm;
  }

  /** Tileable ridged fbm, about [-1, 1] with crests near +1. */
  function pridge2(x, y, px, py, octaves = 5, gain = 0.5) {
    let sum = 0;
    let amp = 1;
    let norm = 0;
    let f = 1;
    let w = 1;
    for (let o = 0; o < octaves; o++) {
      let n = 1 - Math.abs(pnoise2(x * f + o * 0.29, y * f + o * 0.77, px * f, py * f));
      n *= n;
      const nw = n * w;
      w = nw > 1 ? 1 : nw;
      sum += nw * amp;
      norm += amp;
      amp *= gain;
      f *= 2;
    }
    return (sum / norm) * 2 - 1;
  }

  /**
   * Tileable Worley (cellular) noise with one jittered feature point per cell.
   * Writes into `out`: [F1, F2, cellId 0..1, nearest point x, nearest point y] and returns it.
   * Distances are in cell units.
   */
  function worley2(x, y, px, py, out, jitter = 0.9) {
    const cx = Math.floor(x);
    const cy = Math.floor(y);
    let f1 = 1e9;
    let f2 = 1e9;
    let id = 0;
    let nx = 0;
    let ny = 0;
    for (let dj = -1; dj <= 1; dj++) {
      for (let di = -1; di <= 1; di++) {
        const gi = cx + di;
        const gj = cy + dj;
        let wi = gi % px;
        if (wi < 0) wi += px;
        let wj = gj % py;
        if (wj < 0) wj += py;
        const h = ihash(wi, wj);
        const ox = gi + 0.5 + ((h & 0xffff) / 65535 - 0.5) * jitter;
        const oy = gj + 0.5 + ((h >>> 16) / 65535 - 0.5) * jitter;
        const dx = ox - x;
        const dy = oy - y;
        const d = dx * dx + dy * dy;
        if (d < f1) {
          f2 = f1;
          f1 = d;
          id = ihash(wj, wi + 7919) / 4294967296;
          nx = ox;
          ny = oy;
        } else if (d < f2) {
          f2 = d;
        }
      }
    }
    out[0] = Math.sqrt(f1);
    out[1] = Math.sqrt(f2);
    out[2] = id;
    out[3] = nx;
    out[4] = ny;
    return out;
  }

  /** Deterministic hash of two integers → [0, 1). */
  const hash2 = (i, j) => ihash(i | 0, j | 0) / 4294967296;

  /**
   * Fast tileable fractal field for texture generation: a size×size Float32Array that wraps
   * seamlessly. `period` is the number of lattice cells across the first octave (doubling per
   * octave, stopping once cells get smaller than 2 px). Lattice gradients are hashed once per
   * cell, so this is ~10× faster than calling pfbm2 per pixel.
   * @param {number} size  pixels per side
   * @param {number} period  cells across (x); `periodY` defaults to the same
   * @param {{ octaves?: number, gain?: number, ridged?: boolean, turbulence?: boolean, periodY?: number, salt?: number }} [opts]
   * @returns {Float32Array} values in about [-1, 1] (ridged/turbulence: crests near +1)
   */
  function fbmField(size, period, opts = {}) {
    const { octaves = 5, gain = 0.5, ridged = false, turbulence = false, salt = 0 } = opts;
    const periodY = opts.periodY || period;
    const n = size * size;
    const out = new Float32Array(n);
    const weight = ridged ? new Float32Array(n).fill(1) : null;
    const cellX = new Int32Array(size);
    const fracX = new Float32Array(size);
    const fadeX = new Float32Array(size);
    let amp = 1;
    let norm = 0;
    let px = period;
    let py = periodY;
    for (let o = 0; o < octaves; o++) {
      if (o > 0 && (size / px < 2 || size / py < 2)) break;
      const sx = px / size;
      const sy = py / size;
      const offX = (o * 0.6180339 + salt * 0.1234) % 1;
      const offY = (o * 0.3819660 + salt * 0.4321) % 1;
      for (let x = 0; x < size; x++) {
        const c = (x + 0.5) * sx + offX;
        const fl = Math.floor(c);
        cellX[x] = fl % px;
        fracX[x] = c - fl;
        fadeX[x] = fade(c - fl);
      }
      const gx = new Float32Array(px * py);
      const gy = new Float32Array(px * py);
      for (let j = 0; j < py; j++) {
        for (let i = 0; i < px; i++) {
          const h = ihash(i + o * 1013 + salt * 7, j - o * 7919 + salt * 131) & 31;
          gx[j * px + i] = PGX[h];
          gy[j * px + i] = PGY[h];
        }
      }
      for (let y = 0; y < size; y++) {
        const c = (y + 0.5) * sy + offY;
        const fl = Math.floor(c);
        const j0 = fl % py;
        const j1 = j0 + 1 === py ? 0 : j0 + 1;
        const fy = c - fl;
        const fy1 = fy - 1;
        const v = fade(fy);
        const r0 = j0 * px;
        const r1 = j1 * px;
        const row = y * size;
        for (let x = 0; x < size; x++) {
          const i0 = cellX[x];
          const i1 = i0 + 1 === px ? 0 : i0 + 1;
          const fx = fracX[x];
          const fx1 = fx - 1;
          const u = fadeX[x];
          const n00 = gx[r0 + i0] * fx + gy[r0 + i0] * fy;
          const n10 = gx[r0 + i1] * fx1 + gy[r0 + i1] * fy;
          const n01 = gx[r1 + i0] * fx + gy[r1 + i0] * fy1;
          const n11 = gx[r1 + i1] * fx1 + gy[r1 + i1] * fy1;
          const a = n00 + u * (n10 - n00);
          const b = n01 + u * (n11 - n01);
          let val = 1.4142 * (a + v * (b - a));
          const k = row + x;
          if (ridged) {
            val = 1 - (val < 0 ? -val : val);
            val *= val * weight[k];
            weight[k] = val > 1 ? 1 : val;
          } else if (turbulence) {
            val = val < 0 ? -val : val;
          }
          out[k] += val * amp;
        }
      }
      norm += amp;
      amp *= gain;
      px *= 2;
      py *= 2;
    }
    const inv = 1 / norm;
    if (ridged || turbulence) for (let k = 0; k < n; k++) out[k] = out[k] * inv * 2 - 1;
    else for (let k = 0; k < n; k++) out[k] *= inv;
    return out;
  }

  /**
   * Fast tileable Worley field: one jittered point per cell, cellsX × cellsY cells across the
   * texture (unequal counts make elongated cells). Distances are in mean-cell units. With
   * `vectors`, dx/dy hold the offset from each pixel to its nearest feature point (same units).
   * @returns {{ f1: Float32Array, f2: Float32Array, id: Float32Array, dx?: Float32Array, dy?: Float32Array }}
   */
  function worleyField(size, cellsX, cellsY = cellsX, jitter = 0.9, salt = 0, vectors = false) {
    const n = size * size;
    const f1o = new Float32Array(n);
    const f2o = new Float32Array(n);
    const ido = new Float32Array(n);
    const dxo = vectors ? new Float32Array(n) : null;
    const dyo = vectors ? new Float32Array(n) : null;
    const nc = cellsX * cellsY;
    const ox = new Float32Array(nc);
    const oy = new Float32Array(nc);
    const cid = new Float32Array(nc);
    for (let j = 0; j < cellsY; j++) {
      for (let i = 0; i < cellsX; i++) {
        const h = ihash(i * 3 + 101 + salt, j * 5 - 17 - salt);
        ox[j * cellsX + i] = 0.5 + ((h & 0xffff) / 65535 - 0.5) * jitter;
        oy[j * cellsX + i] = 0.5 + ((h >>> 16) / 65535 - 0.5) * jitter;
        cid[j * cellsX + i] = ihash(j + salt, i + 7919) / 4294967296;
      }
    }
    const kx = Math.sqrt(cellsY / cellsX);
    const ky = Math.sqrt(cellsX / cellsY);
    // Per-column lookups: wrapped neighbour cell indices and offsets to their origins.
    const colW = new Int32Array(size * 3);
    const colD = new Float32Array(size * 3);
    for (let x = 0; x < size; x++) {
      const cx = ((x + 0.5) / size) * cellsX;
      const ci = Math.floor(cx);
      for (let d = 0; d < 3; d++) {
        const gi = ci + d - 1;
        colW[x * 3 + d] = ((gi % cellsX) + cellsX) % cellsX;
        colD[x * 3 + d] = gi - cx;
      }
    }
    for (let y = 0; y < size; y++) {
      const cy = ((y + 0.5) / size) * cellsY;
      const cj = Math.floor(cy);
      const r0 = ((((cj - 1) % cellsY) + cellsY) % cellsY) * cellsX;
      const r1 = (((cj % cellsY) + cellsY) % cellsY) * cellsX;
      const r2 = ((((cj + 1) % cellsY) + cellsY) % cellsY) * cellsX;
      const e0 = cj - 1 - cy;
      const e1 = cj - cy;
      const e2 = cj + 1 - cy;
      for (let x = 0; x < size; x++) {
        let d1 = 1e9;
        let d2 = 1e9;
        let best = 0;
        let bx = 0;
        let by = 0;
        for (let dj = 0; dj < 3; dj++) {
          const rb = dj === 0 ? r0 : dj === 1 ? r1 : r2;
          const ey = dj === 0 ? e0 : dj === 1 ? e1 : e2;
          for (let di = 0; di < 3; di++) {
            const q = x * 3 + di;
            const c = rb + colW[q];
            const dx = (colD[q] + ox[c]) * kx;
            const dy = (ey + oy[c]) * ky;
            const d = dx * dx + dy * dy;
            if (d < d1) {
              d2 = d1;
              d1 = d;
              best = c;
              bx = dx;
              by = dy;
            } else if (d < d2) {
              d2 = d;
            }
          }
        }
        const k = y * size + x;
        f1o[k] = Math.sqrt(d1);
        f2o[k] = Math.sqrt(d2);
        ido[k] = cid[best];
        if (vectors) {
          dxo[k] = bx;
          dyo[k] = by;
        }
      }
    }
    return vectors ? { f1: f1o, f2: f2o, id: ido, dx: dxo, dy: dyo } : { f1: f1o, f2: f2o, id: ido };
  }

  return { noise2, noise3, fbm2, ridge2, warp2, pnoise2, pfbm2, pridge2, worley2, hash2, fbmField, worleyField };
}

/** Bilinear sample of a square tileable field at pixel coordinates (wraps). */
export function sampleField(field, size, x, y) {
  const fx = Math.floor(x);
  const fy = Math.floor(y);
  const tx = x - fx;
  const ty = y - fy;
  let x0 = fx % size;
  if (x0 < 0) x0 += size;
  let y0 = fy % size;
  if (y0 < 0) y0 += size;
  const x1 = x0 + 1 === size ? 0 : x0 + 1;
  const y1 = y0 + 1 === size ? 0 : y0 + 1;
  const a = field[y0 * size + x0];
  const b = field[y0 * size + x1];
  const c = field[y1 * size + x0];
  const d = field[y1 * size + x1];
  const top = a + (b - a) * tx;
  return top + (c + (d - c) * tx - top) * ty;
}
