/**
 * Procedural texture pixels (DESIGN.md §11): the catalogue and every pixel generator. No DOM
 * and no three.js, so it also runs in the texture workers (textures/worker.js) and in Node.
 * Images are RGBA8 rows from v = 0 up (row 0 = v 0); textures.js turns them into textures.
 */
import { createNoise, sampleField } from '../../core/noise.js';
import { clamp, smoothstep, createRng } from '../../core/math.js';

/**
 * Bump whenever any generator's output changes (here, in core/noise.js or in the core/math.js
 * helpers they use): it keys the pixel cache that lets later launches skip generation.
 * tests/unit/boot-textures.test.mjs pins a checksum of every generator per version.
 */
export const TEXTURE_VERSION = 1;

// ---------------------------------------------------------------------------------------
// Catalogue
// ---------------------------------------------------------------------------------------

/**
 * Contract names (§11) plus world extras, in generation order. The order also fixes each
 * texture's seed: append new names, never insert or reorder. `pair` names are produced by the
 * same generator as their owner (`of`). `draw` textures are painted on a 2D canvas by
 * textures.js.
 *   gpu   a material samples it, so generateAll uploads it. The others (character atlas
 *         sources, blazons) are only drawn into canvases and never need a GPU copy.
 *   boot  false: nothing uses it at boot, so generateAll skips it (get() still builds it).
 *   cost  relative generation cost per pixel; the workers start the slowest first.
 */
export const TEXTURE_DEFS = {
  dirt: { gen: genDirt, size: 'big', pair: 'dirtNormal', gpu: true, cost: 2 },
  dirtNormal: { of: 'dirt', size: 'bigNormal', gpu: true },
  rock: { gen: genRock, size: 'big', pair: 'rockNormal', gpu: true, cost: 2.2 },
  rockNormal: { of: 'rock', gpu: true },
  cliffStrata: { gen: genCliff, size: 'big', pair: 'cliffNormal', gpu: true, cost: 3.5 },
  cliffNormal: { of: 'cliffStrata', gpu: true },
  sand: { gen: genSand, pair: 'sandNormal', gpu: true, cost: 1.3 },
  sandNormal: { of: 'sand', gpu: true },
  bronze: { gen: genBronze, pair: 'bronzeNormal' },
  bronzeNormal: { of: 'bronze', boot: false },
  linen: { gen: genLinen },
  crimson: { gen: genCrimson },
  leather: { gen: genLeather },
  wood: { gen: genWood },
  wicker: { gen: genWicker, pair: 'wickerNormal' },
  wickerNormal: { of: 'wicker', boot: false },
  persianRobeA: { gen: genRobeA, cost: 1.4 },
  persianRobeB: { gen: genRobeB },
  persianRobeC: { gen: genRobeC },
  scale: { gen: genScale },
  felt: { gen: genFelt },
  skinGreek: { gen: (S, N, R) => genSkin(S, N, R, [184, 128, 94], [196, 122, 98], [148, 98, 72]), cost: 1.2 },
  skinPersian: { gen: (S, N, R) => genSkin(S, N, R, [166, 116, 84], [176, 108, 84], [128, 86, 62]), cost: 1.2 },
  stone: { gen: genStone, pair: 'stoneNormal', gpu: true, cost: 1.4 },
  stoneNormal: { of: 'stone', gpu: true },
  water: { gen: genWater, size: 'small', boot: false },
  waterNormal: { gen: genWaterNormal, linear: true, gpu: true },
  cloud: { gen: genCloud, size: 'sprite', data: true, gpu: true },
  blood: { gen: genBlood, size: 'sprite', boot: false },
  smoke: { gen: genSmoke, size: 'sprite', data: true, gpu: true },
  spark: { gen: genSpark, size: 'sprite', data: true, boot: false },
  blazonFly: { draw: true, size: 'blazon' },
  blazonGorgon: { draw: true, size: 'blazon' },
  blazonLion: { draw: true, size: 'blazon' },
  blazonBoar: { draw: true, size: 'blazon' },
  blazonClub: { draw: true, size: 'blazon' },
  blazonScorpion: { draw: true, size: 'blazon' },
  // World extras.
  grass: { draw: true, size: 'sprite', data: true, cutout: true, gpu: true },
  scrub: { draw: true, size: 'sprite', data: true, cutout: true, gpu: true },
  sulphur: { gen: genSulphur, size: 'sprite', data: true, gpu: true },
  foam: { gen: genFoam, size: 'sprite', data: true, gpu: true },
};

/** Every name get() accepts, in generation order. */
export const TEXTURE_NAMES = Object.keys(TEXTURE_DEFS);

/** Seed per name (owners seed their generator's noise and rng). */
export const TEXTURE_SEEDS = {};
TEXTURE_NAMES.forEach((n, i) => {
  let h = 2166136261;
  for (let k = 0; k < n.length; k++) h = Math.imul(h ^ n.charCodeAt(k), 16777619);
  TEXTURE_SEEDS[n] = ((h >>> 0) % 100000) + i;
});
const SEEDS = TEXTURE_SEEDS;

/** Pixel size of a texture for a quality level. */
export function textureSize(name, quality) {
  const def = TEXTURE_DEFS[name];
  if (!def) return 0;
  const kind = def.size || (def.of && TEXTURE_DEFS[def.of].size === 'big' ? 'bigNormal' : 'normal');
  if (quality === 'low') return 256;
  if (kind === 'big') return quality === 'high' ? 1024 : 512;
  if (kind === 'bigNormal') return name === 'dirtNormal' && quality === 'high' ? 1024 : 512;
  if (kind === 'sprite' || kind === 'small') return 256;
  return 512;
}

/**
 * Runs a pixel generator without touching the DOM (tests and benchmarks).
 * @returns {{ size: number, color: Uint8ClampedArray, normal: Uint8ClampedArray|null }|null}
 */
export function generatePixels(name, quality = 'medium') {
  const def = TEXTURE_DEFS[name];
  if (!def || !def.gen) return null;
  const S = textureSize(name, quality);
  const out = def.gen(S, createNoise(SEEDS[name]), createRng(SEEDS[name] * 7 + 3));
  return out.color ? { size: S, color: out.color, normal: out.normal || null } : { size: S, color: out, normal: null };
}

/**
 * Generates an owner's images ready to install: its own and, for a `pair`, the normal map
 * resampled to the pair's size. `meanLum` is the mean linear luminance (0.5 for data maps),
 * which world.js uses to normalise anti-tiling.
 * @returns {{ name: string, size: number, color: Uint8ClampedArray, meanLum: number,
 *   pair: { name: string, size: number, color: Uint8ClampedArray, meanLum: number }|null }|null}
 */
export function generateOwner(name, quality = 'medium') {
  const def = TEXTURE_DEFS[name];
  const res = generatePixels(name, quality);
  if (!res) return null;
  const S = res.size;
  const out = { name, size: S, color: res.color, meanLum: def.linear ? 0.5 : meanLuminance(res.color), pair: null };
  if (def.pair && res.normal) {
    const NS = textureSize(def.pair, quality);
    out.pair = { name: def.pair, size: NS, color: NS === S ? res.normal : resamplePixels(res.normal, S, NS), meanLum: 0.5 };
  }
  return out;
}

export { meanLuminance, flipRows, coverageMips, bleedColour };

// ---------------------------------------------------------------------------------------
// Image helpers
// ---------------------------------------------------------------------------------------

/** Mean linear luminance of an sRGB RGBA image (sampled), used to normalise anti-tiling. */
function meanLuminance(px) {
  let sum = 0;
  let n = 0;
  const lin = (v) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  for (let k = 0; k < px.length; k += 4 * 7) {
    sum += 0.2126 * lin(px[k]) + 0.7152 * lin(px[k + 1]) + 0.0722 * lin(px[k + 2]);
    n++;
  }
  return n ? sum / n : 0.3;
}

function flipRows(px, S) {
  const out = new Uint8ClampedArray(px.length);
  const row = S * 4;
  for (let y = 0; y < S; y++) out.set(px.subarray(y * row, (y + 1) * row), (S - 1 - y) * row);
  return out;
}

/** Nearest/box resample of an RGBA square image (used to shrink a normal map). */
function resamplePixels(px, S, T) {
  const out = new Uint8ClampedArray(T * T * 4);
  const f = S / T;
  for (let y = 0; y < T; y++) {
    for (let x = 0; x < T; x++) {
      let r = 0, g = 0, b = 0;
      for (let j = 0; j < f; j++) {
        for (let i = 0; i < f; i++) {
          const k = ((Math.floor(y * f) + j) * S + Math.floor(x * f) + i) * 4;
          r += px[k] - 128;
          g += px[k + 1] - 128;
          b += px[k + 2] - 128;
        }
      }
      const l = Math.hypot(r, g, b) || 1;
      const o = (y * T + x) * 4;
      out[o] = 128 + (r / l) * 127;
      out[o + 1] = 128 + (g / l) * 127;
      out[o + 2] = 128 + (b / l) * 127;
      out[o + 3] = 255;
    }
  }
  return out;
}

/** alphaTest that world foliage materials use with the cut-out textures. */
export const CUTOUT_ALPHA = 0.42;

/**
 * Full mip chain for an alpha cut-out (rows of RGBA bytes, square, power of two). Colour is
 * alpha-weighted, and each level's alpha is scaled so the fraction of texels passing the alpha
 * test stays what it is at full size (otherwise grass blades vanish a few metres away).
 */
function coverageMips(px, S, ref) {
  const cover = (a, n, k) => {
    let c = 0;
    for (let i = 0; i < n; i++) if (a[i * 4 + 3] * k > ref * 255) c++;
    return c / n;
  };
  const target = cover(px, S * S, 1);
  const levels = [{ data: px, width: S, height: S }];
  let src = px;
  let s = S;
  while (s > 1) {
    const t = s >> 1;
    const raw = new Uint8ClampedArray(t * t * 4);
    for (let y = 0; y < t; y++) {
      for (let x = 0; x < t; x++) {
        let r = 0, g = 0, b = 0, a = 0, rr = 0, gg = 0, bb = 0;
        for (let j = 0; j < 2; j++) {
          for (let i = 0; i < 2; i++) {
            const o = ((y * 2 + j) * s + x * 2 + i) * 4;
            const w = src[o + 3];
            r += src[o] * w;
            g += src[o + 1] * w;
            b += src[o + 2] * w;
            a += w;
            rr += src[o];
            gg += src[o + 1];
            bb += src[o + 2];
          }
        }
        const o = (y * t + x) * 4;
        if (a > 0) {
          raw[o] = r / a;
          raw[o + 1] = g / a;
          raw[o + 2] = b / a;
        } else {
          raw[o] = rr / 4;
          raw[o + 1] = gg / 4;
          raw[o + 2] = bb / 4;
        }
        raw[o + 3] = a / 4;
      }
    }
    // Binary search for the alpha scale that keeps the coverage.
    let lo = 0.5;
    let hi = 8;
    for (let it = 0; it < 14; it++) {
      const mid = (lo + hi) * 0.5;
      if (cover(raw, t * t, mid) < target) lo = mid;
      else hi = mid;
    }
    const k = (lo + hi) * 0.5;
    const out = new Uint8ClampedArray(raw);
    for (let i = 3; i < out.length; i += 4) out[i] = raw[i] * k;
    levels.push({ data: out, width: t, height: t });
    src = raw;
    s = t;
  }
  return levels;
}

/** Spreads colour into fully transparent texels so filtering/mips don't pull in black. */
function bleedColour(px, S, passes) {
  const n = S * S;
  const known = new Uint8Array(n);
  for (let k = 0; k < n; k++) known[k] = px[k * 4 + 3] > 8 ? 1 : 0;
  let any = false;
  for (let k = 0; k < n; k++) if (known[k]) any = true;
  if (!any) return;
  const next = new Uint8Array(n);
  for (let p = 0; p < passes; p++) {
    next.set(known);
    for (let y = 0; y < S; y++) {
      for (let x = 0; x < S; x++) {
        const k = y * S + x;
        if (known[k]) continue;
        let r = 0, g = 0, b = 0, c = 0;
        for (let d = 0; d < 4; d++) {
          const nx = (x + (d === 0 ? 1 : d === 1 ? -1 : 0) + S) % S;
          const ny = (y + (d === 2 ? 1 : d === 3 ? -1 : 0) + S) % S;
          const q = ny * S + nx;
          if (known[q]) {
            r += px[q * 4];
            g += px[q * 4 + 1];
            b += px[q * 4 + 2];
            c++;
          }
        }
        if (c) {
          px[k * 4] = r / c;
          px[k * 4 + 1] = g / c;
          px[k * 4 + 2] = b / c;
          next[k] = 1;
        }
      }
    }
    known.set(next);
  }
  // Whatever is still unknown gets the mean visible colour.
  let r = 0, g = 0, b = 0, c = 0;
  for (let k = 0; k < n; k++) {
    if (px[k * 4 + 3] > 8) {
      r += px[k * 4];
      g += px[k * 4 + 1];
      b += px[k * 4 + 2];
      c++;
    }
  }
  for (let k = 0; k < n; k++) {
    if (!known[k]) {
      px[k * 4] = r / c;
      px[k * 4 + 1] = g / c;
      px[k * 4 + 2] = b / c;
    }
  }
}

// ---------------------------------------------------------------------------------------
// Field toolkit (pure helpers on Float32Array square fields; row 0 = v 0)
// ---------------------------------------------------------------------------------------

const fract = (v) => v - Math.floor(v);

function norm01(f) {
  let lo = Infinity, hi = -Infinity;
  for (let k = 0; k < f.length; k++) {
    const v = f[k];
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  const s = hi > lo ? 1 / (hi - lo) : 0;
  for (let k = 0; k < f.length; k++) f[k] = (f[k] - lo) * s;
  return f;
}

/** Separable wrapped box blur, applied twice (≈ gaussian). */
function blur(f, S, r) {
  if (r < 1) return f.slice();
  let src = f;
  const tmp = new Float32Array(S * S);
  const out = new Float32Array(S * S);
  const w = 1 / (2 * r + 1);
  for (let pass = 0; pass < 2; pass++) {
    for (let y = 0; y < S; y++) {
      const row = y * S;
      let acc = 0;
      for (let k = -r; k <= r; k++) acc += src[row + ((k + S) % S)];
      for (let x = 0; x < S; x++) {
        tmp[row + x] = acc * w;
        acc += src[row + ((x + r + 1) % S)] - src[row + ((x - r + S) % S)];
      }
    }
    for (let x = 0; x < S; x++) {
      let acc = 0;
      for (let k = -r; k <= r; k++) acc += tmp[((k + S) % S) * S + x];
      for (let y = 0; y < S; y++) {
        out[y * S + x] = acc * w;
        acc += tmp[((y + r + 1) % S) * S + x] - tmp[((y - r + S) % S) * S + x];
      }
    }
    src = out.slice();
  }
  return src;
}

/**
 * Tangent-space normal map (OpenGL convention, +Y = +v) from a height field. Optional slope
 * fields (sx, sy, as tangents of the tilt) add facets that need no height (chipped planes).
 */
function normalMap(h, S, strength, sx = null, sy = null) {
  const px = new Uint8ClampedArray(S * S * 4);
  const k = strength * (S / 512);
  for (let y = 0; y < S; y++) {
    const yu = ((y + 1) % S) * S;
    const yd = ((y - 1 + S) % S) * S;
    const row = y * S;
    for (let x = 0; x < S; x++) {
      const xr = (x + 1) % S;
      const xl = (x - 1 + S) % S;
      const dx = (h[row + xr] - h[row + xl]) * 0.5;
      const dy = (h[yu + x] - h[yd + x]) * 0.5;
      let nx = -dx * k;
      let ny = -dy * k;
      if (sx) {
        nx -= sx[row + x];
        ny -= sy[row + x];
      }
      const l = 1 / Math.sqrt(nx * nx + ny * ny + 1);
      nx *= l;
      ny *= l;
      const o = (row + x) * 4;
      px[o] = (nx * 0.5 + 0.5) * 255;
      px[o + 1] = (ny * 0.5 + 0.5) * 255;
      px[o + 2] = (l * 0.5 + 0.5) * 255;
      px[o + 3] = 255;
    }
  }
  return px;
}

/** Writes an sRGB colour (0..255 floats) into px at texel k. */
function put(px, k, r, g, b, a = 255) {
  const o = k * 4;
  px[o] = r;
  px[o + 1] = g;
  px[o + 2] = b;
  px[o + 3] = a;
}

/** Linear blend of two [r,g,b] arrays into `o`. */
function mixc(a, b, t, o) {
  o[0] = a[0] + (b[0] - a[0]) * t;
  o[1] = a[1] + (b[1] - a[1]) * t;
  o[2] = a[2] + (b[2] - a[2]) * t;
  return o;
}

/** Piecewise-linear colour ramp over stops [[t, [r,g,b]], …]. */
function ramp(stops) {
  return (t, o) => {
    if (t <= stops[0][0]) return mixc(stops[0][1], stops[0][1], 0, o);
    for (let i = 1; i < stops.length; i++) {
      if (t <= stops[i][0]) {
        const [t0, c0] = stops[i - 1];
        const [t1, c1] = stops[i];
        return mixc(c0, c1, (t - t0) / (t1 - t0), o);
      }
    }
    const last = stops[stops.length - 1][1];
    return mixc(last, last, 0, o);
  };
}

/** Fine plain-weave modulation (0..1 around 0.5) for fabrics. */
function weave(x, y, S, threads) {
  const u = (x / S) * threads;
  const v = (y / S) * threads;
  const iu = Math.floor(u);
  const iv = Math.floor(v);
  const fu = u - iu;
  const fv = v - iv;
  const over = (iu + iv) & 1;
  const across = Math.sin(Math.PI * (over ? fv : fu));
  const along = 0.75 + 0.25 * Math.sin(Math.PI * (over ? fu : fv));
  return across * along;
}

// ---------------------------------------------------------------------------------------
// Ground, rock and stone
// ---------------------------------------------------------------------------------------

/** Presses sandal prints into a height field (trampled ground). */
function stampPrints(h, S, R, count, lenPx, widPx, depth) {
  for (let i = 0; i < count; i++) {
    const cx = Math.floor(R.next() * S);
    const cy = Math.floor(R.next() * S);
    const ang = R.next() * Math.PI * 2;
    const ca = Math.cos(ang);
    const sa = Math.sin(ang);
    const d0 = depth * (0.5 + R.next() * 0.7);
    const rad = Math.ceil(lenPx * 0.65);
    for (let dy = -rad; dy <= rad; dy++) {
      const y = (((cy + dy) % S) + S) % S;
      for (let dx = -rad; dx <= rad; dx++) {
        const a = (dx * ca + dy * sa) / (lenPx * 0.5);
        const b = (-dx * sa + dy * ca) / (widPx * 0.5);
        const w = a > 0.1 ? 1 : 0.8;
        const d = a * a + (b / w) * (b / w);
        if (d >= 1.45) continue;
        const x = (((cx + dx) % S) + S) % S;
        const inner = 1 - smoothstep(0.5, 1.0, d);
        const rim = smoothstep(0.85, 1.05, d) * (1 - smoothstep(1.05, 1.45, d));
        h[y * S + x] += -inner * d0 + rim * d0 * 0.12;
      }
    }
  }
}

function genDirt(S, N, R) {
  const n = S * S;
  const base = N.fbmField(S, 6, { octaves: 6, gain: 0.5 });
  const fine = N.fbmField(S, 48, { octaves: 2, gain: 0.55, salt: 2 });
  const tone = N.fbmField(S, 4, { octaves: 3, salt: 3 });
  const cracks = N.fbmField(S, 6, { octaves: 3, ridged: true, salt: 4 });
  const peb = N.worleyField(S, 18, 18, 0.95, 5);
  const grit = N.worleyField(S, 56, 56, 0.95, 6);
  const h = new Float32Array(n);
  const pebT = new Float32Array(n);
  const pebC = new Float32Array(n);
  const crk = new Float32Array(n);
  for (let k = 0; k < n; k++) {
    let v = base[k] * 0.32 + fine[k] * 0.07;
    const cm = smoothstep(0.7, 0.95, cracks[k]) * smoothstep(0.0, -0.3, base[k]);
    crk[k] = cm;
    v -= cm * 0.12;
    // Angular, half-buried stones: an irregular outline (noise on the cell distance) and a
    // flat top, rather than round domes.
    const id = peb.id[k];
    if (id < 0.24) {
      const r = 0.16 + 0.3 * fract(id * 7.31);
      const d = (peb.f1[k] + fine[k] * 0.09 + base[k] * 0.05) / r;
      if (d < 1) {
        v += smoothstep(1, 0.55, d) * (0.07 + 0.08 * fract(id * 13.7)) + (1 - d * d) * 0.03;
        pebT[k] = smoothstep(1, 0.8, d);
        pebC[k] = id / 0.24;
      }
    }
    const gid = grit.id[k];
    if (gid < 0.2) {
      const r = 0.2 + 0.3 * fract(gid * 5.7);
      const d = (grit.f1[k] + fine[k] * 0.12) / r;
      if (d < 1) {
        v += smoothstep(1, 0.5, d) * 0.035;
        if (pebT[k] === 0) {
          pebT[k] = smoothstep(1, 0.6, d) * 0.6;
          pebC[k] = fract(gid * 9.1);
        }
      }
    }
    h[k] = v;
  }
  stampPrints(h, S, R, 9, S * 0.1, S * 0.036, 0.05);
  const cav = blur(h, S, Math.max(1, S >> 8));
  const px = new Uint8ClampedArray(n * 4);
  const dust = ramp([
    [0, [108, 94, 76]],
    [0.35, [142, 126, 102]],
    [0.6, [166, 150, 124]],
    [0.85, [186, 172, 146]],
    [1, [200, 188, 164]],
  ]);
  const warm = [164, 126, 92];
  const pal = ramp([[0, [176, 170, 158]], [0.3, [132, 126, 116]], [0.55, [166, 150, 124]], [0.8, [104, 98, 90]], [1, [188, 182, 168]]]);
  const c = [0, 0, 0];
  const pc = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    const t = clamp(0.58 + base[k] * 0.55 + fine[k] * 0.22 + (h[k] - cav[k]) * 1.4, 0, 1);
    dust(t, c);
    mixc(c, warm, smoothstep(0.0, 0.5, tone[k]) * 0.3, c);
    if (pebT[k] > 0) {
      pal(pebC[k], pc);
      // Dust settles on the stones, so they never read as clean white.
      mixc(pc, c, 0.38, pc);
      mixc(c, pc, pebT[k], c);
    }
    const shade = clamp(1 + (h[k] - cav[k]) * 0.8 - crk[k] * 0.2, 0.7, 1.15);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 8) };
}

function genRock(S, N) {
  const n = S * S;
  const wx = N.fbmField(S, 3, { octaves: 4, salt: 2 });
  const wy = N.fbmField(S, 3, { octaves: 4, salt: 3 });
  const ridgeF = N.fbmField(S, 3, { octaves: 6, ridged: true, gain: 0.5 });
  const body = N.fbmField(S, 5, { octaves: 6, salt: 1 });
  const cells = N.worleyField(S, 5, 5, 0.9, 4);
  const edgeF = new Float32Array(n);
  for (let k = 0; k < n; k++) edgeF[k] = cells.f2[k] - cells.f1[k];
  const crackOn = N.fbmField(S, 2, { octaves: 3, salt: 9 });
  const pits = N.worleyField(S, 36, 36, 1, 5);
  const pitOn = N.fbmField(S, 3, { octaves: 3, salt: 10 });
  const lichenF = N.fbmField(S, 6, { octaves: 5, salt: 6 });
  const spots = N.worleyField(S, 30, 30, 0.9, 7);
  const h = new Float32Array(n);
  const crackA = new Float32Array(n);
  const warpAmt = S * 0.07;
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const ox = x + wx[k] * warpAmt;
      const oy = y + wy[k] * warpAmt;
      const rg = sampleField(ridgeF, S, ox, oy);
      let v = rg * 0.3 + body[k] * 0.45;
      // Cracks follow warped cell borders, and only where the mask allows (never a full network).
      const e = sampleField(edgeF, S, x + wy[k] * warpAmt * 0.6, y - wx[k] * warpAmt * 0.6);
      const crack = (1 - smoothstep(0.006, 0.03, e)) * smoothstep(0.05, 0.35, crackOn[k]);
      crackA[k] = crack;
      v -= crack * 0.28;
      if (pits.id[k] < 0.25) v -= (1 - smoothstep(0.05, 0.3, pits.f1[k] + body[k] * 0.1)) * 0.07 * smoothstep(-0.1, 0.3, pitOn[k]);
      h[k] = v;
    }
  }
  const cav = blur(h, S, Math.max(1, S >> 7));
  const px = new Uint8ClampedArray(n * 4);
  const tone = ramp([[0, [88, 84, 78]], [0.35, [122, 117, 108]], [0.65, [150, 144, 132]], [1, [178, 172, 158]]]);
  const warm = [166, 146, 114];
  const lichenGrey = [178, 182, 166];
  const lichenOrange = [190, 136, 62];
  const dark = [74, 70, 64];
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    tone(clamp(0.55 + body[k] * 0.8 + (h[k] - cav[k]) * 1.6, 0, 1), c);
    mixc(c, warm, smoothstep(-0.1, 0.4, wx[k]) * 0.35, c);
    const lm = smoothstep(0.28, 0.48, lichenF[k]);
    if (lm > 0) mixc(c, lichenGrey, lm * 0.45, c);
    const sr = spots.f1[k] + body[k] * 0.12;
    if (spots.id[k] < 0.035 && sr < 0.16 + (0.2 * spots.id[k]) / 0.035) mixc(c, lichenOrange, 0.45 * lm + 0.15, c);
    else if (spots.id[k] > 0.95 && sr < 0.12) mixc(c, dark, 0.45, c);
    mixc(c, dark, crackA[k] * 0.35, c);
    const shade = clamp(1 + (h[k] - cav[k]) * 1.3, 0.6, 1.2);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 9) };
}

/**
 * Weathered limestone for the cliff; v runs up the face (row 0 = bottom of a tile ≈ 10 m).
 * Beds of uneven thickness with recessed seams that come and go, angular fracture blocks,
 * pitted and runnelled surfaces, rain streaks hanging from the seams, ochre staining, lichen.
 */
function genCliff(S, N, R) {
  const n = S * S;
  const px2 = S / 1024;
  // Beds that fill the tile exactly, so it tiles vertically.
  const beds = [];
  let total = 0;
  const count = 6;
  for (let i = 0; i < count; i++) {
    const w = 0.35 + Math.pow(R.next(), 1.3) * 1.9;
    beds.push({ w });
    total += w;
  }
  let acc = 0;
  for (const b of beds) {
    b.y0 = (acc / total) * S;
    acc += b.w;
    b.y1 = (acc / total) * S;
    b.hard = R.next();
    b.tone = R.next();
    b.seam = 0.35 + R.next() * 0.65;
    b.salt = R.next() * 10;
    const joints = [];
    const jn = R.next() < 0.4 ? 0 : 1 + Math.floor(R.next() * 2.4);
    for (let j = 0; j < jn; j++) joints.push({ x: R.next() * S, w: 0.6 + R.next(), slant: (R.next() - 0.5) * 0.3, vis: 0.4 + R.next() * 0.6 });
    b.joints = joints;
  }
  const bedAt = new Int16Array(S + 1);
  for (let y = 0; y <= S; y++) {
    let bi = 0;
    while (bi < beds.length - 1 && y >= beds[bi].y1) bi++;
    bedAt[y] = bi;
  }
  // Fields. Row 0 of a periodY:1 field is a 1D profile along u.
  const waveLo = N.fbmField(S, 2, { octaves: 3, salt: 7, periodY: 1 });
  const waveHi = N.fbmField(S, 16, { octaves: 2, salt: 8, periodY: 1 });
  const seamVis = N.fbmField(S, 6, { octaves: 3, salt: 12, periodY: 1 });
  const rough = N.fbmField(S, 8, { octaves: 6, gain: 0.52, ridged: true, salt: 1 });
  const body = N.fbmField(S, 4, { octaves: 5, salt: 2 });
  const fine = N.fbmField(S, 64, { octaves: 2, salt: 3 });
  const wob = N.fbmField(S, 10, { octaves: 3, salt: 4 });
  const rill = N.fbmField(S, 28, { octaves: 3, salt: 5, periodY: 3 });
  const streak = N.fbmField(S, 18, { octaves: 4, salt: 6, periodY: 2 });
  const stain = N.fbmField(S, 3, { octaves: 5, salt: 9 });
  const lichM = N.fbmField(S, 5, { octaves: 4, salt: 10 });
  const blocks = N.worleyField(S, 6, 9, 0.85, 11);
  // Chipped facets about a metre across: each cell a plane tilted its own way (normals only).
  const facets = N.worleyField(S, 13, 22, 0.9, 15, true);
  const fsx = new Float32Array(n);
  const fsy = new Float32Array(n);
  const pits = N.worleyField(S, 46, 46, 1, 13);
  const spots = N.worleyField(S, 30, 30, 0.95, 14);
  const h = new Float32Array(n);
  const seamA = new Float32Array(n);
  const bedT = new Float32Array(n);
  const bedI = new Uint8Array(n);
  const fresh = new Float32Array(n);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const yy = (((y + waveLo[x] * S * 0.03 + waveHi[x] * S * 0.004 + wob[k] * S * 0.004) % S) + S) % S;
      const bi = bedAt[Math.floor(yy)];
      const b = beds[bi];
      const th = b.y1 - b.y0;
      const t = clamp((yy - b.y0) / th, 0, 1);
      // Hard beds: flat faces with rounded arrises. Soft beds: hollowed back.
      const edge = Math.min(t, 1 - t) * th;
      const round = smoothstep(0, 14 * px2 + th * 0.12, edge);
      let v = b.hard > 0.45 ? 0.32 * round + 0.05 : 0.08 + 0.18 * round - 0.12 * Math.sin(Math.PI * t);
      // Seams between beds: recesses whose depth comes and goes along the bed. Each boundary is
      // shaped from both sides so the face never steps abruptly.
      const below = yy - b.y0;
      const above = b.y1 - yy;
      const vis = smoothstep(-0.25, 0.45, seamVis[x] + body[k] * 0.5 + wob[k] * 0.3);
      const svLo = b.seam * vis;
      const svHi = beds[(bi + 1) % beds.length].seam * vis;
      const sLo = (1 - smoothstep(0, (2 + 7 * svLo) * px2 + 1, below + fine[k] * 2 * px2)) * svLo;
      const sHi = (1 - smoothstep(0, (1 + 3 * svHi) * px2 + 1, above - fine[k] * 2 * px2)) * svHi;
      const seam = Math.max(sLo, sHi);
      v -= seam * 0.24;
      // Joints: narrow vertical fractures, wobbling and fading along their length.
      let joint = 0;
      for (const j of b.joints) {
        let d = Math.abs(x - (j.x + wob[k] * S * 0.02 + below * j.slant));
        if (d > S / 2) d = S - d;
        const jw = (1.5 + 2.5 * j.w) * px2 + 0.5;
        joint = Math.max(joint, (1 - smoothstep(0, jw, d)) * j.vis * smoothstep(-0.35, 0.2, rough[k] * 0.6 + body[k]));
      }
      v -= joint * 0.26;
      // Angular fracture blocks: each Voronoi cell a slightly different plane, only in patches,
      // blended over a few pixels so the borders read as arrises rather than drawn lines.
      const bid = fract(blocks.id[k] * 7.13 + b.salt);
      const bedge = blocks.f2[k] - blocks.f1[k];
      const bm = smoothstep(0.0, 0.5, stain[k] + rough[k] * 0.3);
      v += (bid - 0.5) * 0.09 * (0.5 + b.hard) * bm * smoothstep(0.0, 0.05, bedge);
      {
        // Facets break at the seams (the bed index re-seeds them), and their borders are
        // warped so they never read as straight-edged polygons.
        const wx = (x + Math.round(wob[k] * S * 0.03 + rough[k] * S * 0.006) + S) % S;
        const wy = (y + Math.round(body[k] * S * 0.02) + S) % S;
        const fk = wy * S + wx;
        const fid = fract(facets.id[fk] * 3.71 + bi * 0.377);
        const ga = fid * 23.25;
        const gm = (0.02 + 0.06 * fract(fid * 13.1)) * smoothstep(-0.15, 0.35, lichM[k] * 0.7 + body[k] * 0.6 + 0.1) * (0.4 + 0.8 * b.hard);
        // Facets round over in the last few pixels before a border, like a worn arris.
        const soft = smoothstep(0.0, 0.04, facets.f2[fk] - facets.f1[fk]);
        fsx[k] = Math.cos(ga) * gm * soft;
        fsy[k] = Math.sin(ga) * gm * soft;
      }
      // Surface: ridged roughness, runnels down the soft beds, solution pits.
      v += rough[k] * 0.11 + fine[k] * 0.025;
      v += rill[k] * (0.03 + 0.05 * (1 - b.hard));
      if (pits.id[k] < 0.16) v -= (1 - smoothstep(0.05, 0.3, pits.f1[k] + fine[k] * 0.1)) * 0.04 * smoothstep(0.0, 0.4, body[k] + 0.1);
      h[k] = v;
      seamA[k] = Math.max(seam * 0.8, joint);
      bedT[k] = t;
      bedI[k] = bi;
      fresh[k] = bid < 0.22 ? bm : 0;
    }
  }
  const cav = blur(h, S, Math.max(1, Math.round(5 * px2)));
  const px = new Uint8ClampedArray(n * 4);
  const c = [0, 0, 0];
  const grey = [156, 153, 146];
  const buff = [170, 159, 138];
  const cream = [184, 174, 152];
  const grime = [86, 84, 80];
  const ochre = [170, 124, 80];
  const lichenW = [196, 196, 186];
  const lichenO = [184, 132, 66];
  const lichenK = [62, 60, 56];
  for (let k = 0; k < n; k++) {
    const b = beds[bedI[k]];
    mixc(grey, buff, clamp(b.tone * 0.5 + body[k] * 0.6 + 0.25, 0, 1), c);
    if (fresh[k] > 0) mixc(c, cream, 0.45 * fresh[k], c);
    const tone = 0.93 + body[k] * 0.12 + fine[k] * 0.04;
    c[0] *= tone;
    c[1] *= tone;
    c[2] *= tone;
    // Rain streaks hang from each bed's top (t = 1) and fade downward.
    const st = smoothstep(0.05, 0.55, streak[k] + body[k] * 0.25) * (0.25 + 0.75 * Math.pow(bedT[k], 0.6));
    mixc(c, grime, st * 0.42, c);
    mixc(c, ochre, smoothstep(0.15, 0.6, stain[k]) * 0.38 + smoothstep(0.35, 0.8, -streak[k]) * smoothstep(0.6, 1, bedT[k]) * 0.2, c);
    // Lichen: ragged rosettes in clusters.
    const lm = smoothstep(0.15, 0.5, lichM[k]);
    if (lm > 0) {
      const sid = spots.id[k];
      const r = spots.f1[k] + fine[k] * 0.25 + rough[k] * 0.08;
      if (sid < 0.3 && r < 0.4) {
        const a = (1 - smoothstep(0.18, 0.36, r)) * lm * (0.6 + 0.4 * fract(sid * 31.7));
        mixc(c, sid < 0.05 ? lichenO : sid < 0.1 ? lichenK : lichenW, a * 0.55, c);
      }
    }
    mixc(c, grime, seamA[k] * 0.32, c);
    // Up-facing facets catch dust and skylight.
    const shade = clamp(1.04 + (h[k] - cav[k]) * 1.1 + fsy[k] * 0.25, 0.66, 1.18);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 7, fsx, fsy) };
}

function genSand(S, N) {
  const n = S * S;
  const grain = N.fbmField(S, 128, { octaves: 2, salt: 1 });
  const ripple = N.fbmField(S, 4, { octaves: 4, salt: 2 });
  const peb = N.worleyField(S, 34, 34, 0.95, 3);
  const tone = N.fbmField(S, 3, { octaves: 4, salt: 4 });
  const h = new Float32Array(n);
  const pebT = new Float32Array(n);
  const pebC = new Float32Array(n);
  for (let k = 0; k < n; k++) {
    let v = ripple[k] * 0.3 + grain[k] * 0.08;
    if (peb.id[k] < 0.5) {
      const r = 0.25 + 0.35 * fract(peb.id[k] * 11.3);
      const d = peb.f1[k] / r;
      if (d < 1) {
        v += Math.sqrt(1 - d * d) * 0.35;
        pebT[k] = smoothstep(1, 0.8, d);
        pebC[k] = peb.id[k] / 0.5;
      }
    }
    h[k] = v;
  }
  const cav = blur(h, S, 1);
  const px = new Uint8ClampedArray(n * 4);
  const base = ramp([[0, [128, 114, 92]], [0.5, [156, 140, 112]], [1, [178, 164, 136]]]);
  const pe = ramp([[0, [112, 106, 98]], [0.3, [158, 150, 136]], [0.6, [140, 122, 98]], [0.8, [178, 170, 154]], [1, [94, 88, 82]]]);
  const c = [0, 0, 0];
  const pc = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    base(clamp(0.5 + tone[k] * 0.6 + grain[k] * 0.4, 0, 1), c);
    if (pebT[k] > 0) mixc(c, pe(pebC[k], pc), pebT[k] * 0.85, c);
    const shade = clamp(1 + (h[k] - cav[k]) * 1.5, 0.75, 1.15);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 5) };
}

/** Dry-stone fieldstone masonry (the Phocian Wall): flattish stones in rough courses. */
function genStone(S, N) {
  const n = S * S;
  const cells = N.worleyField(S, 5, 9, 0.8, 1);
  const small = N.worleyField(S, 16, 24, 0.9, 2);
  const rough = N.fbmField(S, 8, { octaves: 6, salt: 3 });
  const tone = N.fbmField(S, 3, { octaves: 4, salt: 4 });
  const lichen = N.fbmField(S, 6, { octaves: 4, salt: 5 });
  const h = new Float32Array(n);
  const gapA = new Float32Array(n);
  const ids = new Float32Array(n);
  for (let k = 0; k < n; k++) {
    const e = cells.f2[k] - cells.f1[k];
    const stoneMask = smoothstep(0.05, 0.12, e + rough[k] * 0.04);
    const dome = smoothstep(0.05, 0.4, e);
    let v = stoneMask * (0.35 + dome * 0.35) + rough[k] * 0.14 * stoneMask;
    const e2 = small.f2[k] - small.f1[k];
    const chink = (1 - stoneMask) * smoothstep(0.04, 0.12, e2);
    v += chink * (0.18 + smoothstep(0.04, 0.3, e2) * 0.12);
    h[k] = v;
    gapA[k] = (1 - stoneMask) * (1 - chink);
    ids[k] = stoneMask > 0.5 ? cells.id[k] : small.id[k];
  }
  const cav = blur(h, S, Math.max(1, S >> 7));
  const px = new Uint8ClampedArray(n * 4);
  const pal = ramp([
    [0, [150, 144, 132]],
    [0.25, [180, 172, 156]],
    [0.45, [164, 148, 122]],
    [0.6, [192, 184, 166]],
    [0.75, [138, 128, 114]],
    [0.9, [170, 138, 106]],
    [1, [158, 154, 146]],
  ]);
  const gap = [78, 70, 60];
  const lichenC = [156, 160, 136];
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    pal(ids[k], c);
    const tn = 0.9 + rough[k] * 0.2 + tone[k] * 0.1;
    c[0] *= tn;
    c[1] *= tn;
    c[2] *= tn;
    mixc(c, lichenC, smoothstep(0.32, 0.55, lichen[k]) * 0.35, c);
    mixc(c, gap, gapA[k] * 0.8, c);
    const shade = clamp(1.06 + (h[k] - cav[k]) * 1.4, 0.6, 1.25);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 10) };
}

// ---------------------------------------------------------------------------------------
// Metals and fabrics
// ---------------------------------------------------------------------------------------

function genBronze(S, N) {
  const n = S * S;
  const hammer = N.worleyField(S, 22, 22, 0.85, 1);
  const tone = N.fbmField(S, 3, { octaves: 5, salt: 2 });
  const tarnish = N.fbmField(S, 2, { octaves: 5, salt: 3 });
  // Burnishing: fine streaks elongated along u.
  const burnish = N.fbmField(S, 3, { octaves: 3, salt: 4, periodY: 96 });
  const pitsF = N.worleyField(S, 64, 64, 1, 5);
  const h = new Float32Array(n);
  for (let k = 0; k < n; k++) {
    const dimple = 1 - smoothstep(0, 0.7, hammer.f1[k]);
    h[k] = -dimple * dimple * 0.3 + burnish[k] * 0.015 - (pitsF.id[k] < 0.05 ? (1 - smoothstep(0, 0.25, pitsF.f1[k])) * 0.08 : 0);
  }
  const px = new Uint8ClampedArray(n * 4);
  const bright = [212, 162, 96];
  const mid = [186, 136, 76];
  const dark = [128, 88, 52];
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    mixc(bright, mid, clamp(0.5 + tone[k] * 1.1, 0, 1), c);
    mixc(c, dark, smoothstep(0.15, 0.55, tarnish[k]) * 0.45, c);
    const b = 1 + burnish[k] * 0.07;
    const facet = 1 - (1 - smoothstep(0, 0.7, hammer.f1[k])) * 0.05;
    put(px, k, c[0] * b * facet, c[1] * b * facet, c[2] * b * facet);
  }
  return { color: px, normal: normalMap(h, S, 3) };
}

/** Overlapping scale armour; rows hang down (v up), each row lying over the one below. */
function genScale(S, N) {
  const n = S * S;
  const cols = 9;
  const rows = 12;
  const rh = 1 / rows;
  const tone = N.fbmField(S, 4, { octaves: 4, salt: 1 });
  const rough = N.fbmField(S, 32, { octaves: 3, salt: 2 });
  const R = createRng(17);
  const tint = new Float32Array(cols * rows);
  for (let i = 0; i < tint.length; i++) tint[i] = R.next();
  const px = new Uint8ClampedArray(n * 4);
  const bronze = [178, 136, 82];
  const iron = [150, 146, 140];
  const gap = [34, 26, 20];
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    const v = (y + 0.5) / S;
    const r0 = Math.floor(v / rh);
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const u = (x + 0.5) / S;
      let hit = false;
      // Rows above lie on top, so test the highest candidate row first.
      for (let r = r0 + 1; r >= r0 - 1 && !hit; r--) {
        const off = (r & 1) * 0.5;
        const fx = u * cols - off;
        const ci = Math.floor(fx);
        const lx = fx - ci - 0.5;
        const ly = (v - r * rh) / rh;
        if (ly < 0 || ly > 1.6) continue;
        const w = 0.47;
        const rr = ly > 0.5 ? Math.abs(lx) / w : Math.hypot(lx / w, (ly - 0.5) / 0.5);
        if (rr >= 1) continue;
        hit = true;
        const edge = 1 - smoothstep(0.82, 1, rr);
        const rib = 1 - smoothstep(0, 0.07, Math.abs(lx));
        // Shadow just below the edge of the row above (local y = 1).
        const occl = 1 - 0.45 * smoothstep(0.55, 1.0, ly);
        const cid = (((r % rows) + rows) % rows) * cols + (((ci % cols) + cols) % cols);
        mixc(bronze, iron, tint[cid] < 0.18 ? 0.85 : 0, c);
        const lum = (0.78 + edge * 0.18 + rib * 0.1 - (1 - ly) * 0.08) * occl * (0.93 + tone[k] * 0.1 + rough[k] * 0.04) * (0.9 + tint[cid] * 0.2);
        put(px, k, c[0] * lum, c[1] * lum, c[2] * lum);
      }
      if (!hit) put(px, k, gap[0], gap[1], gap[2]);
    }
  }
  return px;
}

function fabric(S, N, colourAt, threads = 110, fuzz = 0.08) {
  const n = S * S;
  const fib = N.fbmField(S, 96, { octaves: 2, salt: 11 });
  const soil = N.fbmField(S, 3, { octaves: 5, salt: 12 });
  const px = new Uint8ClampedArray(n * 4);
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      colourAt(x, y, k, c);
      const w = weave(x, y, S, threads);
      const t = (0.86 + w * 0.2 + fib[k] * fuzz) * (1 - smoothstep(0.25, 0.7, soil[k]) * 0.12);
      put(px, k, c[0] * t, c[1] * t, c[2] * t);
    }
  }
  return px;
}

function genLinen(S, N) {
  const tone = N.fbmField(S, 4, { octaves: 5, salt: 1 });
  const slub = N.fbmField(S, 3, { octaves: 2, salt: 2, periodY: 220 });
  const base = [226, 218, 196];
  const warm = [212, 196, 162];
  return fabric(S, N, (x, y, k, c) => {
    mixc(base, warm, clamp(0.4 + tone[k] * 0.9 + slub[k] * 0.4, 0, 1), c);
  }, 120, 0.06);
}

function genCrimson(S, N) {
  const tone = N.fbmField(S, 6, { octaves: 5, salt: 1 });
  const fade = N.fbmField(S, 2, { octaves: 3, salt: 2 });
  const ramped = ramp([[0, [104, 18, 22]], [0.5, [136, 26, 30]], [1, [160, 44, 38]]]);
  const n = S * S;
  const twillThreads = 140;
  const fib = N.fbmField(S, 128, { octaves: 2, salt: 3 });
  const px = new Uint8ClampedArray(n * 4);
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      ramped(clamp(0.5 + tone[k] * 0.5 + fade[k] * 0.3, 0, 1), c);
      // 2/2 twill: fine diagonal ribs.
      const d = ((x + y) / S) * twillThreads;
      const tw = 0.9 + 0.12 * Math.sin(d * Math.PI) + fib[k] * 0.08;
      put(px, k, c[0] * tw, c[1] * tw, c[2] * tw);
    }
  }
  return px;
}

function genLeather(S, N) {
  const n = S * S;
  const grain = N.worleyField(S, 72, 72, 1, 1);
  const crease = N.fbmField(S, 6, { octaves: 5, ridged: true, salt: 2 });
  const tone = N.fbmField(S, 4, { octaves: 5, salt: 3 });
  const scuff = N.fbmField(S, 8, { octaves: 4, salt: 4 });
  const px = new Uint8ClampedArray(n * 4);
  const base = ramp([[0, [86, 54, 32]], [0.5, [114, 74, 44]], [1, [138, 94, 58]]]);
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    base(clamp(0.5 + tone[k] * 0.6, 0, 1), c);
    const g = 0.88 + smoothstep(0, 0.45, grain.f1[k]) * 0.14;
    const cr = smoothstep(0.75, 0.97, crease[k]) * 0.25;
    const sc = smoothstep(0.4, 0.65, scuff[k]) * 0.12;
    const t = g * (1 - cr) + sc;
    put(px, k, c[0] * t, c[1] * t, c[2] * t);
  }
  return px;
}

/** Ash wood; the grain runs along v. */
function genWood(S, N) {
  const n = S * S;
  const warp = N.fbmField(S, 2, { octaves: 4, salt: 1, periodY: 1 });
  const fibre = N.fbmField(S, 64, { octaves: 2, salt: 2, periodY: 3 });
  const tone = N.fbmField(S, 2, { octaves: 3, salt: 3 });
  const knots = N.worleyField(S, 3, 6, 0.8, 4);
  const px = new Uint8ClampedArray(n * 4);
  const light = [196, 160, 112];
  const darkC = [138, 98, 62];
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const ring = 0.5 + 0.5 * Math.sin(((x / S) * 18 + warp[k] * 2.2) * Math.PI * 2);
      let t = Math.pow(ring, 3) * 0.6 + fibre[k] * 0.25;
      if (knots.id[k] < 0.12) t += (1 - smoothstep(0.05, 0.3, knots.f1[k])) * 0.6;
      mixc(light, darkC, clamp(t + tone[k] * 0.3, 0, 1), c);
      put(px, k, c[0], c[1], c[2]);
    }
  }
  return px;
}

/** Woven osier: horizontal weavers over/under vertical stakes. */
function genWicker(S, N) {
  const n = S * S;
  const stakes = 10;
  const rowsN = 24;
  const tone = N.fbmField(S, 4, { octaves: 4, salt: 1 });
  const fib = N.fbmField(S, 8, { octaves: 3, salt: 2, periodY: 96 });
  const R = createRng(5);
  const rodTint = new Float32Array(rowsN);
  for (let i = 0; i < rowsN; i++) rodTint[i] = R.next();
  const h = new Float32Array(n);
  const col = new Float32Array(n);
  const gapA = new Float32Array(n);
  for (let y = 0; y < S; y++) {
    const fy = (y / S) * rowsN;
    const r = Math.floor(fy);
    const ty = fy - r;
    const across = Math.sin(Math.PI * ty);
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const fx = (x / S) * stakes;
      const ci = Math.floor(fx + 0.5);
      const dx = fx - ci;
      const over = ((r + ci) & 1) === 0;
      // The weaver bends around the stake: up where it passes over, down where under.
      const bend = Math.cos(Math.PI * clamp(dx * 2, -1, 1)) * 0.5 + 0.5;
      const weaverH = across * 0.7 + (over ? bend : -bend) * 0.35;
      const stakeVisible = !over && Math.abs(dx) < 0.18;
      let v = weaverH;
      if (stakeVisible) v = Math.max(v, 0.55 + Math.cos((Math.abs(dx) / 0.18) * Math.PI * 0.5) * 0.35);
      h[k] = v + fib[k] * 0.05;
      col[k] = stakeVisible ? 0.2 : rodTint[r];
      gapA[k] = 1 - smoothstep(0.0, 0.35, across);
    }
  }
  const px = new Uint8ClampedArray(n * 4);
  const rod = ramp([[0, [168, 132, 82]], [0.5, [196, 160, 104]], [1, [180, 150, 96]]]);
  const gap = [52, 38, 24];
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    rod(col[k], c);
    const t = 0.82 + h[k] * 0.25 + tone[k] * 0.1 + fib[k] * 0.08;
    mixc(c, gap, gapA[k] * 0.8, c);
    put(px, k, c[0] * t, c[1] * t, c[2] * t);
  }
  return { color: px, normal: normalMap(h, S, 6) };
}

/** Rosettes on indigo (Achaemenid court robes). */
function genRobeA(S, N) {
  const cells = 6;
  const ground = [44, 50, 104];
  const petal = [232, 214, 170];
  const centre = [164, 42, 36];
  const gold = [208, 162, 72];
  const tone = N.fbmField(S, 3, { octaves: 4, salt: 1 });
  return fabric(S, N, (x, y, k, c) => {
    const u = (x / S) * cells;
    const v = (y / S) * cells;
    const lx = u - Math.floor(u) - 0.5;
    const ly = v - Math.floor(v) - 0.5;
    const r = Math.hypot(lx, ly);
    const a = Math.atan2(ly, lx);
    mixc(ground, ground, 0, c);
    const tn = 1 + tone[k] * 0.12;
    c[0] *= tn;
    c[1] *= tn;
    c[2] *= tn;
    const ring = 1 - smoothstep(0.012, 0.03, Math.abs(r - 0.4));
    const petalR = 0.31 * (0.55 + 0.45 * Math.abs(Math.cos(a * 4)));
    const pm = 1 - smoothstep(petalR - 0.02, petalR + 0.005, r);
    const cm = 1 - smoothstep(0.07, 0.09, r);
    // Small gold lozenges where four cells meet.
    const corner = Math.abs(Math.abs(lx) - 0.5) + Math.abs(Math.abs(ly) - 0.5);
    const lz = 1 - smoothstep(0.07, 0.09, corner);
    mixc(c, gold, Math.max(ring, lz), c);
    mixc(c, petal, pm, c);
    mixc(c, centre, cm, c);
  }, 120, 0.07);
}

/** Bands of stepped merlons on saffron. */
function genRobeB(S, N) {
  const bands = 4;
  const across = 8;
  const ground = [212, 166, 76];
  const merlon = [78, 46, 30];
  const white = [236, 226, 200];
  const red = [150, 40, 34];
  const tone = N.fbmField(S, 3, { octaves: 4, salt: 1 });
  return fabric(S, N, (x, y, k, c) => {
    const v = (y / S) * bands;
    const tv = v - Math.floor(v);
    const u = (x / S) * across;
    const tu = u - Math.floor(u);
    mixc(ground, ground, 0, c);
    const tn = 1 + tone[k] * 0.1;
    c[0] *= tn;
    c[1] *= tn;
    c[2] *= tn;
    if (tv < 0.06 || (tv > 0.5 && tv < 0.56)) {
      mixc(c, red, 1, c);
    } else if (tv < 0.5) {
      // Stepped merlon: three steps rising to the centre of each repeat.
      const local = (tv - 0.06) / 0.44;
      const dist = Math.abs(tu - 0.5);
      const step = Math.floor((0.5 - dist) * 6) / 6;
      if (local < 0.25 + step * 1.2 && dist < 0.42) mixc(c, merlon, 1, c);
    } else {
      // Row of small white squares.
      const sq = Math.abs(tu - 0.5) < 0.12 && Math.abs((tv - 0.56) / 0.44 - 0.5) < 0.22;
      if (sq) mixc(c, white, 1, c);
    }
  }, 120, 0.07);
}

/** Vertical stripes with a dotted cream band. */
function genRobeC(S, N) {
  const cols = [[150, 38, 34], [226, 210, 172], [52, 58, 108], [226, 210, 172], [176, 128, 52], [226, 210, 172]];
  const repeats = 3;
  const tone = N.fbmField(S, 3, { octaves: 4, salt: 1 });
  const line = [40, 28, 24];
  return fabric(S, N, (x, y, k, c) => {
    const u = ((x / S) * repeats * cols.length) % cols.length;
    const i = Math.floor(u);
    const t = u - i;
    const src = cols[i];
    c[0] = src[0];
    c[1] = src[1];
    c[2] = src[2];
    const tn = 1 + tone[k] * 0.1;
    c[0] *= tn;
    c[1] *= tn;
    c[2] *= tn;
    if (t < 0.06 || t > 0.94) mixc(c, line, 0.8, c);
    if (i === 1) {
      const v = ((y / S) * 24) % 1;
      if (Math.hypot(t - 0.5, v - 0.5) < 0.16) mixc(c, cols[0], 0.9, c);
    }
  }, 120, 0.07);
}

function genFelt(S, N) {
  const n = S * S;
  const a = N.fbmField(S, 8, { octaves: 6, salt: 1 });
  const b = N.fbmField(S, 64, { octaves: 3, salt: 2, turbulence: true });
  const px = new Uint8ClampedArray(n * 4);
  const base = ramp([[0, [162, 142, 114]], [0.5, [188, 170, 142]], [1, [206, 192, 166]]]);
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    base(clamp(0.5 + a[k] * 0.5 + b[k] * 0.25, 0, 1), c);
    put(px, k, c[0], c[1], c[2]);
  }
  return px;
}

function genSkin(S, N, R, base, flush, shadow) {
  const n = S * S;
  const blotch = N.fbmField(S, 6, { octaves: 5, salt: 1 });
  const fine = N.fbmField(S, 32, { octaves: 3, salt: 2 });
  const pores = N.worleyField(S, 96, 96, 1, 3);
  const freck = N.worleyField(S, 40, 40, 1, 4);
  const px = new Uint8ClampedArray(n * 4);
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    mixc(base, flush, smoothstep(0, 0.5, blotch[k]) * 0.35, c);
    mixc(c, shadow, smoothstep(0, -0.5, blotch[k]) * 0.22, c);
    let t = 1 + fine[k] * 0.03 - (1 - smoothstep(0.05, 0.2, pores.f1[k])) * 0.04;
    if (freck.id[k] < 0.06 && freck.f1[k] < 0.22) t *= 0.93;
    put(px, k, c[0] * t, c[1] * t, c[2] * t);
  }
  return px;
}

// ---------------------------------------------------------------------------------------
// Water
// ---------------------------------------------------------------------------------------

function genWater(S, N) {
  const n = S * S;
  const a = N.fbmField(S, 4, { octaves: 5, salt: 1 });
  const caust = N.worleyField(S, 12, 12, 1, 2);
  const px = new Uint8ClampedArray(n * 4);
  const deep = [26, 70, 92];
  const shallow = [58, 120, 128];
  const c = [0, 0, 0];
  for (let k = 0; k < n; k++) {
    mixc(deep, shallow, clamp(0.5 + a[k] * 0.9, 0, 1), c);
    const ca = (1 - smoothstep(0, 0.08, caust.f2[k] - caust.f1[k])) * 0.15;
    put(px, k, c[0] + ca * 120, c[1] + ca * 140, c[2] + ca * 130);
  }
  return px;
}

/** Tileable wind waves: integer wave vectors keep the sum periodic. */
function genWaterNormal(S, N, R) {
  const n = S * S;
  const waves = [];
  const dirs = 24;
  for (let i = 0; i < dirs; i++) {
    const kmag = 2 + Math.floor(Math.pow(R.next(), 1.6) * 22);
    // Wind from the north-west with a wide spread, so no single direction stripes the sea.
    const ang = -0.5 + (R.next() - 0.5) * Math.PI * 1.6;
    let kx = Math.round(Math.cos(ang) * kmag);
    const ky = Math.round(Math.sin(ang) * kmag);
    if (kx === 0 && ky === 0) kx = 1;
    waves.push({ kx, ky, a: 1 / Math.pow(Math.hypot(kx, ky), 1.25), ph: R.next() * Math.PI * 2 });
  }
  const rip = N.fbmField(S, 24, { octaves: 3, salt: 3 });
  const h = new Float32Array(n);
  // sin(a + b) = sin a·cos b + cos a·sin b, with a along u and b along v tabulated per wave.
  const nw = waves.length;
  const su = new Float32Array(nw * S);
  const cu = new Float32Array(nw * S);
  const sv = new Float32Array(nw * S);
  const cv = new Float32Array(nw * S);
  const amp = new Float32Array(nw);
  for (let i = 0; i < nw; i++) {
    const w = waves[i];
    amp[i] = w.a;
    for (let t = 0; t < S; t++) {
      const a = (w.kx * t / S) * Math.PI * 2 + w.ph;
      const b = (w.ky * t / S) * Math.PI * 2;
      su[i * S + t] = Math.sin(a);
      cu[i * S + t] = Math.cos(a);
      sv[i * S + t] = Math.sin(b);
      cv[i * S + t] = Math.cos(b);
    }
  }
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      let s = 0;
      for (let i = 0; i < nw; i++) {
        // Sharpened crests (peaked sine).
        const p = su[i * S + x] * cv[i * S + y] + cu[i * S + x] * sv[i * S + y];
        s += amp[i] * (p - 0.25 * p * p);
      }
      h[y * S + x] = s + rip[y * S + x] * 0.04;
    }
  }
  norm01(h);
  return normalMap(h, S, 26);
}

// ---------------------------------------------------------------------------------------
// Sprites and decals (RGBA, alpha carries the shape)
// ---------------------------------------------------------------------------------------

function genCloud(S, N) {
  const n = S * S;
  const f = N.fbmField(S, 4, { octaves: 6, salt: 1 });
  const g = N.fbmField(S, 8, { octaves: 4, salt: 2, turbulence: true });
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const dx = (x + 0.5) / S - 0.5;
      const dy = (y + 0.5) / S - 0.5;
      const r = Math.hypot(dx, dy * 1.1) * 2;
      const fall = 1 - smoothstep(0.35, 1, r + f[k] * 0.35);
      const dens = clamp(fall * (0.75 + f[k] * 0.6 + g[k] * 0.2), 0, 1);
      // Lit from above: brighter at the top (high v).
      const lit = 0.78 + 0.22 * clamp(0.5 + dy * 1.6 + f[k] * 0.3, 0, 1);
      put(px, k, 255 * lit, 252 * lit, 248 * lit, dens * 255);
    }
  }
  return px;
}

function genSmoke(S, N) {
  const n = S * S;
  const f = N.fbmField(S, 3, { octaves: 6, salt: 1 });
  const w = N.fbmField(S, 6, { octaves: 5, salt: 2, ridged: true });
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const dx = (x + 0.5) / S - 0.5;
      const dy = (y + 0.5) / S - 0.5;
      const r = Math.hypot(dx, dy) * 2;
      const fall = Math.pow(1 - smoothstep(0.1, 1, r + f[k] * 0.25), 1.5);
      const wisp = clamp(0.45 + f[k] * 0.8 + w[k] * 0.35, 0, 1);
      const a = clamp(fall * wisp, 0, 1);
      put(px, k, 236, 234, 230, a * 220);
    }
  }
  return px;
}

function genSpark(S) {
  const n = S * S;
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const dx = (x + 0.5) / S - 0.5;
      const dy = (y + 0.5) / S - 0.5;
      const r = Math.hypot(dx, dy) * 2;
      const core = Math.exp(-r * r * 60);
      const glow = Math.exp(-r * r * 7) * (1 - smoothstep(0.7, 1, r));
      const streak = Math.exp(-dy * dy * 900) * (1 - smoothstep(0.2, 1, Math.abs(dx) * 2)) * 0.35;
      const a = clamp(core + glow * 0.6 + streak, 0, 1);
      const hot = clamp(core * 1.5 + streak, 0, 1);
      put(px, k, 255, 200 + 55 * hot, 120 + 135 * hot, a * 255);
    }
  }
  return px;
}

function genBlood(S, N, R) {
  const n = S * S;
  const f = N.fbmField(S, 4, { octaves: 5, salt: 1 });
  const g = N.fbmField(S, 12, { octaves: 3, salt: 2 });
  const drops = [];
  for (let i = 0; i < 26; i++) {
    const ang = R.next() * Math.PI * 2;
    const dist = 0.22 + Math.pow(R.next(), 0.7) * 0.24;
    drops.push({ x: 0.5 + Math.cos(ang) * dist, y: 0.5 + Math.sin(ang) * dist, r: 0.006 + R.next() * 0.022, ang });
  }
  // Coverage: the lobed central splash, then each drop stamped over its own bounding box.
  const cover = new Float32Array(n);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const dx = (x + 0.5) / S - 0.5;
      const dy = (y + 0.5) / S - 0.5;
      const r = Math.hypot(dx, dy);
      const a0 = Math.atan2(dy, dx);
      const lobes = 0.2 + 0.05 * Math.sin(a0 * 5 + 1.3) + 0.03 * Math.sin(a0 * 11) + f[k] * 0.08;
      cover[k] = 1 - smoothstep(lobes - 0.01, lobes + 0.004, r);
    }
  }
  for (const d of drops) {
    // Drops are stretched along their flight direction.
    const ca = Math.cos(d.ang);
    const sa = Math.sin(d.ang);
    const ext = d.r * 1.8;
    const x0 = Math.max(0, Math.floor((d.x - ext) * S));
    const x1 = Math.min(S - 1, Math.ceil((d.x + ext) * S));
    const y0 = Math.max(0, Math.floor((d.y - ext) * S));
    const y1 = Math.min(S - 1, Math.ceil((d.y + ext) * S));
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const ex = (x + 0.5) / S - d.x;
        const ey = (y + 0.5) / S - d.y;
        const along = ex * ca + ey * sa;
        const side = -ex * sa + ey * ca;
        const dd = Math.hypot(along / 1.8, side) / d.r;
        const k = y * S + x;
        cover[k] = Math.max(cover[k], 1 - smoothstep(0.85, 1, dd));
      }
    }
  }
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const r = Math.hypot((x + 0.5) / S - 0.5, (y + 0.5) / S - 0.5);
      const a = r > 0.49 ? 0 : cover[k];
      const thick = clamp(0.75 + g[k] * 0.3 + (0.2 - r) * 0.8, 0.4, 1);
      put(px, k, 92 * thick + 14, 6, 8, a * 255 * (0.82 + 0.18 * thick));
    }
  }
  return px;
}

/**
 * Mineral crust round a hot-spring vent, as a ground decal: pale sulphur at the vent, white
 * travertine with rimstone terraces, rust-orange runoff channels toward +u (downhill, to the
 * sea), and a damp brown fringe. The vent sits at u ≈ 0.33, v = 0.5.
 */
function genSulphur(S, N) {
  const n = S * S;
  const f = N.fbmField(S, 3, { octaves: 6, salt: 1 });
  const g = N.fbmField(S, 12, { octaves: 4, salt: 2 });
  const zone = N.fbmField(S, 4, { octaves: 4, salt: 3 });
  const runs = N.fbmField(S, 3, { octaves: 4, salt: 5, periodY: 14 });
  const grain = N.fbmField(S, 64, { octaves: 2, salt: 6 });
  const px = new Uint8ClampedArray(n * 4);
  const vent = [226, 214, 132];
  const white = [226, 222, 206];
  const cream = [212, 202, 176];
  const rim = [176, 164, 140];
  const rust = [184, 106, 50];
  const ochre = [196, 146, 74];
  const damp = [118, 96, 72];
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const u = (x + 0.5) / S;
      const v = (y + 0.5) / S;
      const du = u - 0.33;
      const dv = (v - 0.5) * 1.15;
      // Elongated downhill (+u), lobed edge.
      const r = Math.hypot(du * (du > 0 ? 0.68 : 1.35), dv) * 2.2 + f[k] * 0.24;
      mixc(white, cream, smoothstep(0.15, 0.7, r) + g[k] * 0.25, c);
      // Rimstone terraces: thin raised rims at irregular radii.
      const ring = Math.abs(fract(r * 7 + f[k] * 1.5) - 0.5);
      mixc(c, rim, (1 - smoothstep(0.03, 0.09, ring)) * smoothstep(0.2, 0.35, r) * 0.55, c);
      // Sulphur round the vent.
      mixc(c, vent, (1 - smoothstep(0.08, 0.3, r + zone[k] * 0.1)) * 0.85, c);
      // Runoff channels: rust and ochre streaks drawn downhill.
      const ch = smoothstep(0.15, 0.5, runs[k] + zone[k] * 0.3) * smoothstep(0.0, 0.15, du) * smoothstep(0.15, 0.4, r);
      mixc(c, zone[k] > 0 ? rust : ochre, ch * 0.8, c);
      mixc(c, damp, smoothstep(0.62, 0.95, r) * 0.75, c);
      const t = 0.92 + grain[k] * 0.1;
      const border = smoothstep(0, 0.08, Math.min(u, 1 - u, v, 1 - v));
      const a = (1 - smoothstep(0.72, 0.98, r)) * (0.9 + g[k] * 0.2) * border;
      put(px, k, c[0] * t, c[1] * t, c[2] * t, clamp(a, 0, 1) * 235);
    }
  }
  return px;
}

/** Shore foam strip: tiles along u, dense at v = 0 (the waterline) and thinning out to v = 1. */
function genFoam(S, N) {
  const n = S * S;
  const f = N.fbmField(S, 6, { octaves: 5, salt: 1, periodY: 3 });
  const streak = N.fbmField(S, 3, { octaves: 4, salt: 2, periodY: 12 });
  const bub = N.worleyField(S, 48, 24, 1, 3);
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    const v = (y + 0.5) / S;
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const band = 1 - smoothstep(0.05, 0.9, v + f[k] * 0.3);
      const edge = smoothstep(0.0, 0.05, v);
      const lace = smoothstep(-0.1, 0.4, streak[k] + f[k] * 0.5) * (0.7 + 0.3 * smoothstep(0.02, 0.15, bub.f1[k]));
      const a = clamp(band * edge * (0.25 + lace * 0.85), 0, 1);
      put(px, k, 240, 244, 242, a * 255);
    }
  }
  return px;
}
