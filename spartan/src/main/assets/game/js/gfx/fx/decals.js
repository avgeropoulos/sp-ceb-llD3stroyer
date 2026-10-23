/**
 * Ground decals for gfx/fx.js (DESIGN.md §11): at most 60, recycled, in one instanced draw.
 * Each decal is a 4×4 vertex grid whose heights are sampled from world.groundHeight when it is
 * placed, so it follows the terrain. It is a MeshStandardMaterial (sun, shadows, fog and
 * environment reflections) whose shape, growth, drying and fade are computed in the shader from
 * its birth time, so nothing is uploaded per frame.
 */
import * as THREE from 'three';
import { clamp } from '../../core/math.js';

/** Decal cap (DESIGN.md §11: ≤ 60). */
export const DECAL_CAP = 60;

const GRID = 4;
const KIND_SPLAT = 0;
const KIND_POOL = 1;
const KIND_SPOT = 2;
const KIND_SCUFF = 3;
const DEFAULT_SIZE = [0.5, 1.3, 0.1, 0.7];
const LIFE = [70, 120, 45, 35];
const GROW = [0.08, 4.5, 0.05, 0.1];
/** A new pool this close (m) to a live one feeds it instead of stacking a second pool. */
export const POOL_MERGE_DIST = 0.35;
/** Largest a merged pool grows (m). */
const POOL_MAX = 2.4;
/** At most this many live decals overlap one spot; a new one fades out the oldest of them. */
export const MAX_OVERLAP = 4;
const FADE_OUT = 4; // s: the shader's end-of-life fade

/** Maps a decal kind name to its shader kind. */
function kindIndex(kind) {
  switch (kind) {
    case 'pool':
      return KIND_POOL;
    case 'spot':
    case 'drop':
      return KIND_SPOT;
    case 'scuff':
    case 'dust':
      return KIND_SCUFF;
    default:
      return KIND_SPLAT;
  }
}

const VERT_HEAD = /* glsl */ `
attribute vec4 aH0;
attribute vec4 aH1;
attribute vec4 aH2;
attribute vec4 aH3;
attribute float aIdx;
attribute vec4 aShape;
attribute vec4 aLife;
attribute vec3 aNrm;
uniform float uDecalTime;
varying vec2 vDUv;
varying vec4 vDShape;
varying vec4 vDLife;
`;

const VERT_BEGIN = /* glsl */ `
int di = int(aIdx + 0.5);
vec4 dRow = di < 4 ? aH0 : (di < 8 ? aH1 : (di < 12 ? aH2 : aH3));
int dc = di - (di / 4) * 4;
float dh = dc == 0 ? dRow.x : (dc == 1 ? dRow.y : (dc == 2 ? dRow.z : dRow.w));
vec3 transformed = vec3(position.x * aShape.x, dh + 0.03, position.z * aShape.x);
float dcr = cos(aShape.y);
float dsr = sin(aShape.y);
vDUv = mat2(dcr, dsr, -dsr, dcr) * (position.xz * 2.0);
vDShape = aShape;
vDLife = vec4(uDecalTime - aLife.x, aLife.y, aLife.z, aLife.w);
`;

const FRAG_HEAD = /* glsl */ `
varying vec2 vDUv;
varying vec4 vDShape;
varying vec4 vDLife;
float dHash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float dNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(dHash(i), dHash(i + vec2(1.0, 0.0)), f.x), mix(dHash(i + vec2(0.0, 1.0)), dHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
`;

const FRAG_SHAPE = /* glsl */ `
float dAge = vDLife.x;
if (dAge < 0.0 || dAge > vDLife.z) discard;
float dKind = vDShape.w;
float dSeed = vDShape.z;
vec2 dp = vDUv;
float dr = length(dp);
float dth = atan(dp.y, dp.x);
float dn = dNoise(dp * 3.0 + dSeed * 17.0);
float dA = 0.0;
float dThick = 0.0;
float dRough = 1.0;
vec3 dCol = vec3(0.0);
float dDry = smoothstep(0.0, 45.0, dAge);
if (dKind < 0.5) {
  // Splat: an irregular blob with drops flung outward.
  float g = clamp(dAge / max(vDLife.y, 0.01), 0.0, 1.0);
  float e = (0.6 + 0.4 * g) * (0.48 + 0.07 * sin(3.0 * dth + dSeed * 6.0) + 0.05 * sin(7.0 * dth + dSeed * 11.0) + 0.12 * (dn - 0.5));
  dA = 1.0 - smoothstep(e - 0.05, e, dr);
  for (int k = 0; k < 7; k++) {
    float fk = float(k);
    float ang = dSeed * 40.0 + fk * 2.39996;
    vec2 dirc = vec2(cos(ang), sin(ang));
    vec2 q = dp - dirc * (0.55 + 0.28 * fract(dSeed * 13.7 + fk * 0.618));
    float rr = 0.035 + 0.055 * fract(dSeed * 7.3 + fk * 0.37);
    float dd = length(vec2(dot(q, dirc) * 0.55, dot(q, vec2(-dirc.y, dirc.x)))) / rr;
    dA = max(dA, (1.0 - smoothstep(0.7, 1.0, dd)) * g);
  }
  dThick = 1.0 - smoothstep(0.0, 0.5, dr);
} else if (dKind < 1.5) {
  // Pool: spreads with an ease-out, thick and dark in the middle.
  float g = clamp(dAge / max(vDLife.y, 0.01), 0.0, 1.0);
  float grow = 0.15 + 0.85 * (1.0 - (1.0 - g) * (1.0 - g) * (1.0 - g));
  float e = grow * (0.8 + 0.08 * sin(3.0 * dth + dSeed * 5.0) + 0.06 * sin(5.0 * dth + dSeed * 9.0)
    + 0.035 * sin(11.0 * dth + dSeed * 3.0) + 0.1 * (dn - 0.5));
  dA = 1.0 - smoothstep(e - 0.03, e, dr);
  dThick = clamp(1.0 - dr / max(e, 0.01), 0.0, 1.0);
} else if (dKind < 2.5) {
  // Spot: one landed drop.
  float e = 0.72 + 0.16 * (dn - 0.5) + 0.06 * sin(5.0 * dth + dSeed * 7.0);
  dA = 1.0 - smoothstep(e - 0.14, e, dr);
  dThick = 1.0 - dr;
} else {
  // Scuff: churned, darker earth.
  float n2 = dNoise(dp * 7.0 + dSeed * 5.0);
  dA = (1.0 - smoothstep(0.25, 0.95, dr + (dn - 0.5) * 0.45)) * (0.45 + 0.55 * n2) * 0.5;
}
if (dKind < 2.5) {
  // Fresh blood is a deep crimson that browns and dulls as it soaks in and dries; the rim and
  // thin films dry first, the thick middle stays dark and wet longest.
  float dEdge = clamp(dDry + (1.0 - dThick) * 0.35, 0.0, 1.0);
  float dVar = 0.85 + 0.3 * dNoise(dp * 9.0 + dSeed * 31.0);
  dCol = mix(vec3(0.21, 0.012, 0.008), vec3(0.08, 0.021, 0.014), dEdge) * mix(1.0, 0.6, dThick) * dVar;
  dRough = mix(0.1, 0.62, dEdge);
  dA *= 0.95;
} else {
  dCol = vec3(0.16, 0.125, 0.095);
  dRough = 1.0;
}
dA *= 1.0 - smoothstep(vDLife.z - 4.0, vDLife.z, dAge);
if (dA < 0.01) discard;
diffuseColor = vec4(dCol, dA);
`;

/**
 * Creates the decal system and adds its mesh to `ctx.scene`.
 * @returns {{ add, addXYZ, update, reset, mesh, readonly count }}
 */
export function createDecals(ctx) {
  const { scene } = ctx;
  let clock = 0;
  const timeUniform = { value: 0 };

  // A 4×4 grid on [-0.5, 0.5]² in XZ; aIdx tells the shader which sampled height to use.
  const pos = [];
  const nrm = [];
  const idx = [];
  const index = [];
  for (let j = 0; j < GRID; j++) {
    for (let i = 0; i < GRID; i++) {
      pos.push(-0.5 + i / (GRID - 1), 0, -0.5 + j / (GRID - 1));
      nrm.push(0, 1, 0);
      idx.push(j * GRID + i);
    }
  }
  for (let j = 0; j < GRID - 1; j++) {
    for (let i = 0; i < GRID - 1; i++) {
      const a = j * GRID + i;
      index.push(a, a + GRID, a + 1, a + 1, a + GRID, a + GRID + 1);
    }
  }
  const geom = new THREE.BufferGeometry();
  geom.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geom.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  geom.setAttribute('aIdx', new THREE.Float32BufferAttribute(idx, 1));
  geom.setIndex(index);
  const inst = (size) => {
    const a = new THREE.InstancedBufferAttribute(new Float32Array(DECAL_CAP * size), size);
    a.setUsage(THREE.DynamicDrawUsage);
    return a;
  };
  const aH = [inst(4), inst(4), inst(4), inst(4)];
  const aShape = inst(4);
  const aLife = inst(4);
  const aNrm = inst(3);
  geom.setAttribute('aH0', aH[0]);
  geom.setAttribute('aH1', aH[1]);
  geom.setAttribute('aH2', aH[2]);
  geom.setAttribute('aH3', aH[3]);
  geom.setAttribute('aShape', aShape);
  geom.setAttribute('aLife', aLife);
  geom.setAttribute('aNrm', aNrm);

  const mat = new THREE.MeshStandardMaterial({
    color: 0xffffff,
    roughness: 0.5,
    metalness: 0,
    transparent: true,
    depthWrite: false,
    polygonOffset: true,
    polygonOffsetFactor: -1,
    polygonOffsetUnits: -4,
  });
  mat.name = 'fx-decals';
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uDecalTime = timeUniform;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_HEAD)
      .replace('#include <beginnormal_vertex>', 'vec3 objectNormal = aNrm;')
      .replace('#include <begin_vertex>', VERT_BEGIN);
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', '#include <common>\n' + FRAG_HEAD)
      .replace('#include <color_fragment>', FRAG_SHAPE)
      .replace('#include <roughnessmap_fragment>', 'float roughnessFactor = dRough;');
  };
  mat.customProgramCacheKey = () => 'fx-decals-v1';

  const mesh = new THREE.InstancedMesh(geom, mat, DECAL_CAP);
  mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  mesh.count = 0;
  mesh.frustumCulled = false;
  mesh.castShadow = false;
  mesh.receiveShadow = true;
  mesh.renderOrder = 10;
  mesh.matrixAutoUpdate = false;
  mesh.name = 'fx-decals';
  mesh.visible = true; // empty until the first update; lets the pre-warm compile its program
  scene.add(mesh);

  const birth = new Float32Array(DECAL_CAP);
  const life = new Float32Array(DECAL_CAP);
  const centreX = new Float32Array(DECAL_CAP);
  const centreZ = new Float32Array(DECAL_CAP);
  const kinds = new Int8Array(DECAL_CAP).fill(-1);
  let used = 0; // slots [0, used) have been written at least once
  let expiryCheck = 0;
  let seedN = 0;
  const heights = new Float32Array(GRID * GRID);
  let fitX = 0; // ground slope of the last sampleGround (dh/dx, dh/dz) and its normal length
  let fitZ = 0;
  let fitL = 1;
  const m4 = new THREE.Matrix4();

  function ground(x, z) {
    const w = ctx.world;
    return w && w.groundHeight ? w.groundHeight(x, z) : 0;
  }

  function isLive(i) {
    return kinds[i] >= 0 && clock - birth[i] < life[i];
  }

  /**
   * Keeps stains in one spot from stacking deeper than MAX_OVERLAP: when that many live decals
   * already overlap a new one at (x, z) of `size`, the oldest of them fades out over FADE_OUT s
   * (the shader's end-of-life fade) instead of popping.
   */
  function thinCrowd(x, z, size) {
    const sh = aShape.array;
    let n = 0;
    let oldest = -1;
    let oldestBirth = Infinity;
    for (let i = 0; i < used; i++) {
      if (!isLive(i) || birth[i] + life[i] - clock <= FADE_OUT) continue; // gone or already going
      const dx = centreX[i] - x;
      const dz = centreZ[i] - z;
      const r = 0.4 * (sh[i * 4] + size);
      if (dx * dx + dz * dz >= r * r) continue;
      n++;
      if (birth[i] < oldestBirth) {
        oldestBirth = birth[i];
        oldest = i;
      }
    }
    if (n < MAX_OVERLAP || oldest < 0) return;
    life[oldest] = Math.max(0, clock - birth[oldest]) + FADE_OUT;
    aLife.array[oldest * 4 + 2] = life[oldest];
    touch(aLife);
  }

  /** The nearest live pool within POOL_MERGE_DIST of (x, z), or -1. */
  function poolNear(x, z) {
    let best = -1;
    let bestD = POOL_MERGE_DIST * POOL_MERGE_DIST;
    for (let i = 0; i < used; i++) {
      if (kinds[i] !== KIND_POOL || !isLive(i) || clock < birth[i]) continue;
      const dx = centreX[i] - x;
      const dz = centreZ[i] - z;
      const d = dx * dx + dz * dz;
      if (d <= bestD) {
        bestD = d;
        best = i;
      }
    }
    return best;
  }

  function pickSlot() {
    if (used < DECAL_CAP) return used++;
    let oldest = -1;
    let oldestBirth = Infinity;
    let oldestAny = 0;
    let oldestAnyBirth = Infinity;
    for (let i = 0; i < DECAL_CAP; i++) {
      if (!isLive(i)) return i;
      if (birth[i] < oldestAnyBirth) {
        oldestAnyBirth = birth[i];
        oldestAny = i;
      }
      // Recycle stains before pools: pools mark where someone died.
      if (kinds[i] !== KIND_POOL && birth[i] < oldestBirth) {
        oldestBirth = birth[i];
        oldest = i;
      }
    }
    return oldest >= 0 ? oldest : oldestAny;
  }

  // Whole-buffer uploads (≤ 4 KB each, only when a decal is placed): update ranges would
  // allocate a range object per call, and decals land from inside the particle update.
  function touch(attr) {
    attr.needsUpdate = true;
  }

  /**
   * Places a decal centred at (x, y, z) on the ground. y is only used to reject points far
   * above the ground. Returns the slot, or -1 if the spot is unsuitable (sea, steep, mid-air).
   */
  function addXYZ(x, y, z, kind = 'blood', size, delay = 0) {
    const k = kindIndex(kind);
    if (!(size > 0)) size = DEFAULT_SIZE[k];
    size = clamp(size, 0.03, 3);
    const g0 = ground(x, z);
    if (!(g0 > -2.9)) return -1; // sea or invalid
    if (y - g0 > 1.5) return -1;
    if (k === KIND_POOL) {
      const m = poolNear(x, z);
      if (m >= 0 && mergePool(m, x, z, size)) return m;
    }
    if (!sampleGround(x, z, size)) return -1;
    thinCrowd(x, z, size);
    const s = pickSlot();
    write(s, x, z, k, size, clock + Math.max(0, delay));
    return s;
  }

  /**
   * A new pool feeds the live pool `m`: it spreads (area-weighted, up to POOL_MAX) toward the new
   * blood, from the size it shows now, and turns fresh and wet again. False if the bigger pool
   * wouldn't fit the ground there (the caller then places a separate one).
   */
  function mergePool(m, x, z, size) {
    const sh = aShape.array;
    const old = sh[m * 4];
    const add = 0.5 * size * size;
    const total = old * old + add;
    const grown = Math.min(POOL_MAX, Math.sqrt(total));
    if (!(grown > old + 1e-3)) {
      // Already as big as it gets: just keep it fresh.
      birth[m] = clock - GROW[KIND_POOL];
      life[m] = LIFE[KIND_POOL];
      aLife.array[m * 4] = birth[m];
      aLife.array[m * 4 + 2] = life[m];
      touch(aLife);
      return true;
    }
    const w = add / total;
    const nx = centreX[m] + (x - centreX[m]) * w;
    const nz = centreZ[m] + (z - centreZ[m]) * w;
    if (!sampleGround(nx, nz, grown)) return false;
    // Pool growth in the shader: 0.15 + 0.85·(1 − (1 − g)³) of its size at g = age / GROW.
    const g = clamp((clock - birth[m]) / GROW[KIND_POOL], 0, 1);
    const shown = (0.15 + 0.85 * (1 - (1 - g) * (1 - g) * (1 - g))) * old;
    const r = shown / grown;
    const g0 = r <= 0.15 ? 0 : 1 - Math.cbrt(Math.max(0, 1 - (r - 0.15) / 0.85));
    write(m, nx, nz, KIND_POOL, grown, clock - g0 * GROW[KIND_POOL], sh[m * 4 + 2]);
    return true;
  }

  /** Samples the ground under a decal into `heights`; false if the spot is unsuitable. */
  function sampleGround(x, z, size) {
    let lo = Infinity;
    let hi = -Infinity;
    for (let j = 0; j < GRID; j++) {
      for (let i = 0; i < GRID; i++) {
        const h = ground(x + (-0.5 + i / (GRID - 1)) * size, z + (-0.5 + j / (GRID - 1)) * size);
        heights[j * GRID + i] = h;
        if (h < lo) lo = h;
        if (h > hi) hi = h;
      }
    }
    if (lo < -2.9 || hi - lo > 0.35 * size + 0.12) return false;
    // Plane fit from the outer columns and rows.
    let sx = 0;
    let sz = 0;
    for (let t = 0; t < GRID; t++) {
      sx += heights[t * GRID + GRID - 1] - heights[t * GRID];
      sz += heights[(GRID - 1) * GRID + t] - heights[t];
    }
    sx /= GRID * size;
    sz /= GRID * size;
    const nl = Math.sqrt(sx * sx + 1 + sz * sz);
    if (1 / nl < 0.55) return false;
    fitX = sx;
    fitZ = sz;
    fitL = nl;
    return true;
  }

  /** Writes slot s from the last sampleGround (seed: keep a merged pool's shape, else a new one). */
  function write(s, x, z, k, size, born, seed) {
    const sx = fitX;
    const sz = fitZ;
    const nl = fitL;
    kinds[s] = k;
    birth[s] = born;
    life[s] = LIFE[k];
    centreX[s] = x;
    centreZ[s] = z;
    if (seed === undefined) {
      seedN = (seedN + 1) % 997;
      seed = ((seedN * 0.6180339887) % 1) * 0.999;
    }
    for (let r = 0; r < 4; r++) {
      const arr = aH[r].array;
      for (let c = 0; c < 4; c++) arr[s * 4 + c] = heights[r * 4 + c];
      touch(aH[r]);
    }
    const sh = aShape.array;
    sh[s * 4] = size;
    sh[s * 4 + 1] = seed * 6.283 * 7;
    sh[s * 4 + 2] = seed;
    sh[s * 4 + 3] = k;
    touch(aShape);
    const lf = aLife.array;
    lf[s * 4] = birth[s];
    lf[s * 4 + 1] = GROW[k];
    lf[s * 4 + 2] = LIFE[k];
    lf[s * 4 + 3] = 0;
    touch(aLife);
    const nr = aNrm.array;
    nr[s * 3] = -sx / nl;
    nr[s * 3 + 1] = 1 / nl;
    nr[s * 3 + 2] = -sz / nl;
    touch(aNrm);
    m4.makeTranslation(x, 0, z);
    mesh.setMatrixAt(s, m4);
    mesh.instanceMatrix.needsUpdate = true;
    if (s + 1 > mesh.count) mesh.count = s + 1;
  }

  /** Frees a slot. The shader already discards it (age > life); no upload needed. */
  function hide(i) {
    kinds[i] = -1;
  }

  return {
    mesh,
    add(point, kind = 'blood', size, delay = 0) {
      if (!point) return -1;
      const x = Array.isArray(point) ? point[0] : point.x;
      const y = Array.isArray(point) ? point[1] : point.y;
      const z = Array.isArray(point) ? point[2] : point.z;
      if (!Number.isFinite(x) || !Number.isFinite(z)) return -1;
      return addXYZ(x, Number.isFinite(y) ? y : ground(x, z), z, kind, size, delay);
    },
    addXYZ,
    /** Live decal count (≤ DECAL_CAP). */
    get count() {
      let n = 0;
      for (let i = 0; i < used; i++) if (isLive(i)) n++;
      return n;
    },
    update(gdt, dt) {
      if (gdt > 0) clock += gdt;
      timeUniform.value = clock;
      expiryCheck -= dt > 0 ? dt : 0;
      if (expiryCheck <= 0) {
        expiryCheck = 1;
        for (let i = 0; i < used; i++) if (kinds[i] >= 0 && clock - birth[i] >= life[i]) hide(i);
      }
      mesh.visible = mesh.count > 0 && !(ctx.settings && ctx.settings.blood === false);
    },
    reset() {
      for (let i = 0; i < used; i++) if (kinds[i] >= 0) hide(i);
      mesh.count = 0;
      used = 0;
      mesh.visible = false;
    },
  };
}
