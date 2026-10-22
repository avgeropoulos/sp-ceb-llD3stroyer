/**
 * PURE layout of the pass at Thermopylae (DESIGN.md §3.1): the cliff line, the shore line, the
 * ground height field, scree fans, hot springs and the Phocian Wall. No three.js, no DOM, so it
 * is unit-tested in Node and shared by every world builder.
 */
import { createNoise } from '../../core/noise.js';
import { clamp, lerp, smoothstep } from '../../core/math.js';

export const SEA_LEVEL = -3;
export const CLIFF_X = -9;
export const SHORE_X = 9.5;
export const WALL = { z: 12, halfDepth: 0.9, height: 2.5, gapMinX: -2, gapMaxX: 2 };
/** z range of the terrain, from behind the wall to the far end of the pass. */
export const Z_NEAR = 360;
export const Z_FAR = -1650;

// Near-field cache for fast groundHeight lookups (where actors and projectiles live).
const CACHE_X0 = -12;
const CACHE_X1 = 18;
const CACHE_Z0 = -90;
const CACHE_Z1 = 72;
const CACHE_STEP = 0.25;

/**
 * Builds the layout. Everything is deterministic for a seed.
 * @param {number} [seed]
 */
export function createLayout(seed = 480) {
  const N = createNoise(seed);

  /** x of the cliff foot (face plane x = −9 near the line; the pass widens far away). */
  function cliffX(z) {
    if (z >= -40) return CLIFF_X;
    const t = smoothstep(-40, -110, z);
    const far = CLIFF_X - 0.03 * (-40 - z) - 3.5 * (N.fbm2(z * 0.006, 3.1, 3) + 0.3) - 1.2 * N.noise2(z * 0.03, 8.2);
    return lerp(CLIFF_X, far, t);
  }

  /** x of the seaward lip where the ground drops to the shore. */
  function shoreX(z) {
    const wob = 0.3 * N.noise2(z * 0.09, 4.4) + 0.15 * N.noise2(z * 0.31, 1.9);
    if (z >= -40) return SHORE_X + wob * smoothstep(6, 18, Math.abs(z + 2));
    const t = smoothstep(-40, -120, z);
    const far = SHORE_X + 0.045 * (-40 - z) + 4 * N.fbm2(z * 0.005, 7.7, 3) + 1.5 * N.noise2(z * 0.025, 2.2);
    return lerp(SHORE_X + wob, far, t);
  }

  // Scree fans below gullies, kept away from the fighting line.
  const fans = [
    { z: -27, w: 5.5, h: 2.2 },
    { z: -57, w: 7, h: 3.2 },
    { z: -92, w: 9, h: 4.5 },
    { z: -140, w: 10, h: 5 },
    { z: -205, w: 12, h: 6 },
    { z: -290, w: 12, h: 6 },
    { z: 26, w: 6, h: 2.6 },
    { z: 53, w: 7.5, h: 3.2 },
  ];

  /** Hot sulphur springs at the cliff foot: vent position, pool radius, crust radius. */
  const springs = [
    { x: -7.7, z: -13.5, pool: 0.9, crust: 2.6, steam: 1 },
    { x: -7.9, z: 6.5, pool: 0.7, crust: 2.1, steam: 0.8 },
    { x: -6.8, z: -36, pool: 1.2, crust: 3.2, steam: 1.2 },
    { x: -7.2, z: 21, pool: 1.0, crust: 2.8, steam: 1 },
  ];

  function talus(x, z, cx) {
    const dc = x - cx;
    // A low talus apron along the whole foot, kept outside the player rectangle (x ≥ −7).
    let h = 0;
    const w = 1.8;
    if (dc < w) {
      const t = 1 - clamp(dc / w, 0, 1);
      h += 0.75 * t * t * (1.2 - 0.2 * t) + 0.12 * N.noise2(z * 0.4, 0.5) * t;
    }
    for (let i = 0; i < fans.length; i++) {
      const f = fans[i];
      const dz = z - f.z;
      if (dz > f.w * 1.6 || dz < -f.w * 1.6) continue;
      const d = Math.hypot(Math.max(0, dc) * 1.0, dz * 0.75) / f.w;
      if (d < 1) h += f.h * Math.pow(1 - d, 1.4);
    }
    return h;
  }

  /** Absolute height of the rocky shore and seabed at distance ds beyond the lip. */
  function seabed(ds) {
    const d = ds - 2;
    return -3.75 - (d > 0 ? 0.16 * d + 6 * (1 - Math.exp(-d / 25)) : 0);
  }

  // Calm zone around the fighting line: flatter ground.
  function calmWeight(x, z) {
    return smoothstep(18, 8, Math.abs(z + 3)) * smoothstep(10.5, 8.5, Math.abs(x - 0.5));
  }

  /** Raw terrain height (may be below sea level, under the water). */
  function terrainHeight(x, z) {
    const cx = cliffX(z);
    const sx = shoreX(z);
    const calm = calmWeight(x, z);
    let h = N.fbm2(x * 0.07 + 11.3, z * 0.07, 3) * 0.2 * (1 - 0.7 * calm);
    h += N.noise2(x * 0.22, z * 0.22) * 0.04 * (1 - 0.6 * calm);
    // Ruts and hollows worn along the road through the pass.
    h -= 0.06 * smoothstep(0.55, 0.9, N.noise2(x * 0.18 + 40, z * 0.05)) * (1 - calm);
    h -= 0.012 * (x - cx);
    if (z < -60) {
      // The road climbs gently westward, so the army further down the pass stands above the
      // nearer ground instead of hiding behind it.
      const far = smoothstep(-60, -200, z);
      h += N.fbm2(x * 0.012, z * 0.008, 3) * 0.8 * far;
      const run = -60 - z;
      h += run < 450 ? 0.022 * run * smoothstep(0, 60, run) : 9.9 + 0.01 * (run - 450);
    }
    if (z < -1000) h += smoothstep(-1000, -1600, z) * (40 + 25 * N.noise2(x * 0.006, z * 0.004));
    h += talus(x, z, cx);
    const ds = x - sx;
    if (ds > -0.8) {
      // Blend from the floor down to an absolute seabed profile, so the drop always reaches the
      // water a couple of metres past the lip however high the floor is.
      const rough = ds > -0.2 ? 0.35 * N.fbm2(x * 0.5, z * 0.5, 2) * smoothstep(-0.2, 1.2, ds) * (1 - smoothstep(4, 9, ds)) : 0;
      const t = smoothstep(-0.5, 2.3, ds);
      h = h + (seabed(ds) - h) * t - rough;
    }
    return h;
  }

  // Precompute the near-field cache (bilinear; error < 1 mm against terrainHeight).
  const cw = Math.round((CACHE_X1 - CACHE_X0) / CACHE_STEP) + 1;
  const ch = Math.round((CACHE_Z1 - CACHE_Z0) / CACHE_STEP) + 1;
  const cache = new Float32Array(cw * ch);
  for (let j = 0; j < ch; j++) {
    const z = CACHE_Z0 + j * CACHE_STEP;
    for (let i = 0; i < cw; i++) cache[j * cw + i] = terrainHeight(CACHE_X0 + i * CACHE_STEP, z);
  }

  /** Ground or water-surface height at (x, z): fast, defined everywhere, −3 over the sea. */
  function groundHeight(x, z) {
    let h;
    if (x >= CACHE_X0 && x < CACHE_X1 && z >= CACHE_Z0 && z < CACHE_Z1) {
      const fx = (x - CACHE_X0) / CACHE_STEP;
      const fz = (z - CACHE_Z0) / CACHE_STEP;
      const i = fx | 0;
      const j = fz | 0;
      const tx = fx - i;
      const tz = fz - j;
      const k = j * cw + i;
      const a = cache[k];
      const b = cache[k + 1];
      const c = cache[k + cw];
      const d = cache[k + cw + 1];
      h = a + (b - a) * tx + (c - a) * tz + (a - b - c + d) * tx * tz;
    } else {
      h = Number.isFinite(x) && Number.isFinite(z) ? terrainHeight(x, z) : 0;
    }
    return h < SEA_LEVEL ? SEA_LEVEL : h;
  }

  /** True where (x, z) is open water. */
  const isSea = (x, z) => terrainHeight(x, z) < SEA_LEVEL;

  return { cliffX, shoreX, terrainHeight, groundHeight, isSea, seabed, calmWeight, fans, springs, noise: N };
}
