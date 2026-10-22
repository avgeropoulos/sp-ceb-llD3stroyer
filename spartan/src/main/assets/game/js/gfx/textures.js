/**
 * Procedural textures (DESIGN.md §11). Everything is generated at runtime from seeded noise
 * into canvases (or data arrays for alpha cut-outs, so colour survives under alpha), then
 * cached. Colour maps are sRGB, data maps (normals) linear; all use RepeatWrapping.
 */
import * as THREE from 'three';
import { createNoise, sampleField } from '../core/noise.js';
import { clamp, lerp, smoothstep, createRng } from '../core/math.js';

// ---------------------------------------------------------------------------------------
// Catalogue
// ---------------------------------------------------------------------------------------

/** Contract names (§11) plus world extras. `pair` names are produced by the same generator. */
const DEFS = {
  dirt: { gen: genDirt, size: 'big', pair: 'dirtNormal' },
  dirtNormal: { of: 'dirt', size: 'bigNormal' },
  rock: { gen: genRock, size: 'big', pair: 'rockNormal' },
  rockNormal: { of: 'rock' },
  cliffStrata: { gen: genCliff, size: 'big', pair: 'cliffNormal' },
  cliffNormal: { of: 'cliffStrata' },
  sand: { gen: genSand, pair: 'sandNormal' },
  sandNormal: { of: 'sand' },
  bronze: { gen: genBronze, pair: 'bronzeNormal' },
  bronzeNormal: { of: 'bronze' },
  linen: { gen: genLinen },
  crimson: { gen: genCrimson },
  leather: { gen: genLeather },
  wood: { gen: genWood },
  wicker: { gen: genWicker, pair: 'wickerNormal' },
  wickerNormal: { of: 'wicker' },
  persianRobeA: { gen: genRobeA },
  persianRobeB: { gen: genRobeB },
  persianRobeC: { gen: genRobeC },
  scale: { gen: genScale },
  felt: { gen: genFelt },
  skinGreek: { gen: (S, N, R) => genSkin(S, N, R, [184, 128, 94], [196, 122, 98], [148, 98, 72]) },
  skinPersian: { gen: (S, N, R) => genSkin(S, N, R, [166, 116, 84], [176, 108, 84], [128, 86, 62]) },
  stone: { gen: genStone, pair: 'stoneNormal' },
  stoneNormal: { of: 'stone' },
  water: { gen: genWater, size: 'small' },
  waterNormal: { gen: genWaterNormal, linear: true },
  cloud: { gen: genCloud, size: 'sprite', data: true },
  blood: { gen: genBlood, size: 'sprite' },
  smoke: { gen: genSmoke, size: 'sprite', data: true },
  spark: { gen: genSpark, size: 'sprite', data: true },
  blazonFly: { draw: drawFly, size: 'blazon' },
  blazonGorgon: { draw: drawGorgon, size: 'blazon' },
  blazonLion: { draw: drawLion, size: 'blazon' },
  blazonBoar: { draw: drawBoar, size: 'blazon' },
  blazonClub: { draw: drawClub, size: 'blazon' },
  blazonScorpion: { draw: drawScorpion, size: 'blazon' },
  // World extras.
  grass: { draw: drawGrass, size: 'sprite', data: true, cutout: true },
  scrub: { draw: drawScrub, size: 'sprite', data: true, cutout: true },
  sulphur: { gen: genSulphur, size: 'sprite', data: true },
  foam: { gen: genFoam, size: 'sprite', data: true },
};

/** Every name get() accepts, in generation order. */
export const TEXTURE_NAMES = Object.keys(DEFS);

const SEEDS = {};
TEXTURE_NAMES.forEach((n, i) => {
  let h = 2166136261;
  for (let k = 0; k < n.length; k++) h = Math.imul(h ^ n.charCodeAt(k), 16777619);
  SEEDS[n] = ((h >>> 0) % 100000) + i;
});

/** Pixel size of a texture for a quality level. */
export function textureSize(name, quality) {
  const def = DEFS[name];
  if (!def) return 0;
  const kind = def.size || (def.of && DEFS[def.of].size === 'big' ? 'bigNormal' : 'normal');
  if (quality === 'low') return kind === 'sprite' || kind === 'small' ? 128 : 256;
  if (kind === 'big') return quality === 'high' ? 1024 : 512;
  if (kind === 'bigNormal') return name === 'dirtNormal' && quality === 'high' ? 1024 : 512;
  if (kind === 'sprite' || kind === 'small') return 256;
  return 512;
}

// ---------------------------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------------------------

/**
 * @param {object} ctx §4 ctx (uses ctx.quality and ctx.renderer for anisotropy/upload)
 * @returns {{ get(name: string): THREE.Texture, generateAll(onProgress?: (fraction: number, name: string) => void): Promise<void>,
 *   names: string[], memoryBytes(): number }}
 */
export function createTextures(ctx) {
  const quality = ctx.quality || 'medium';
  const cache = new Map();
  let maxAniso = 4;
  try {
    maxAniso = ctx.renderer?.capabilities?.getMaxAnisotropy?.() || 4;
  } catch {
    maxAniso = 4;
  }
  const aniso = Math.min(quality === 'high' ? 8 : 4, maxAniso);
  const warned = new Set();
  let fallback = null;

  function finish(tex, name, linear) {
    tex.name = name;
    tex.colorSpace = linear ? THREE.NoColorSpace : THREE.SRGBColorSpace;
    tex.wrapS = THREE.RepeatWrapping;
    tex.wrapT = THREE.RepeatWrapping;
    tex.anisotropy = aniso;
    tex.magFilter = THREE.LinearFilter;
    tex.minFilter = THREE.LinearMipmapLinearFilter;
    tex.generateMipmaps = true;
    tex.needsUpdate = true;
    cache.set(name, tex);
    return tex;
  }

  function fromPixels(px, S, name, { data = false, linear = false } = {}) {
    const meanLum = linear ? 0.5 : meanLuminance(px);
    if (data) {
      const tex = new THREE.DataTexture(px, S, S, THREE.RGBAFormat, THREE.UnsignedByteType);
      tex.flipY = false;
      tex.userData.meanLum = meanLum;
      return finish(tex, name, linear);
    }
    const canvas = makeCanvas(S);
    // Rows are generated bottom-up (row 0 = v 0); canvases are top-down and flipped on upload.
    canvas.getContext('2d').putImageData(new ImageData(flipRows(px, S), S, S), 0, 0);
    const tex = new THREE.CanvasTexture(canvas);
    tex.userData.meanLum = meanLum;
    return finish(tex, name, linear);
  }

  function build(name) {
    const def = DEFS[name];
    const owner = def.of ? def.of : name;
    const odef = DEFS[owner];
    const S = textureSize(owner, quality);
    const N = createNoise(SEEDS[owner]);
    const R = createRng(SEEDS[owner] * 7 + 3);
    if (odef.draw) {
      const canvas = makeCanvas(S);
      const g = canvas.getContext('2d', odef.data ? { willReadFrequently: true } : undefined);
      g.save();
      g.scale(S, S);
      odef.draw(g, R, S);
      g.restore();
      if (odef.data) {
        const img = g.getImageData(0, 0, S, S).data;
        const px = flipRows(new Uint8ClampedArray(img), S);
        if (odef.cutout) bleedColour(px, S, 6);
        return fromPixels(px, S, owner, { data: true });
      }
      return finish(new THREE.CanvasTexture(canvas), owner, false);
    }
    const out = odef.gen(S, N, R);
    const res = out.color ? out : { color: out };
    fromPixels(res.color, S, owner, { data: !!odef.data, linear: !!odef.linear });
    if (odef.pair && res.normal) {
      const NS = textureSize(odef.pair, quality);
      const npx = NS === S ? res.normal : resamplePixels(res.normal, S, NS);
      fromPixels(npx, NS, odef.pair, { linear: true });
    }
    return cache.get(name);
  }

  function get(name) {
    const hit = cache.get(name);
    if (hit) return hit;
    if (!DEFS[name]) {
      if (!warned.has(name)) {
        warned.add(name);
        console.warn(`textures: unknown texture "${name}"`);
      }
      if (!fallback) {
        const px = new Uint8ClampedArray([128, 128, 128, 255]);
        fallback = new THREE.DataTexture(px, 1, 1, THREE.RGBAFormat);
        fallback.needsUpdate = true;
      }
      return fallback;
    }
    return build(name);
  }

  async function generateAll(onProgress) {
    const total = TEXTURE_NAMES.length;
    for (let i = 0; i < total; i++) {
      const name = TEXTURE_NAMES[i];
      const tex = get(name);
      try {
        if (ctx.renderer && ctx.renderer.initTexture) ctx.renderer.initTexture(tex);
      } catch (err) {
        console.warn('textures: upload failed', name, err);
      }
      if (onProgress) onProgress((i + 1) / total, name);
      await nextFrame();
    }
  }

  /** Estimated GPU bytes of the textures generated so far (RGBA8 + full mip chain). */
  function memoryBytes() {
    let sum = 0;
    for (const tex of cache.values()) {
      const img = tex.image;
      sum += (img.width * img.height * 4 * 4) / 3;
    }
    return Math.round(sum);
  }

  return { get, generateAll, names: TEXTURE_NAMES.slice(), memoryBytes };
}

/**
 * Runs a pixel generator without touching the DOM (tests and benchmarks).
 * @returns {{ size: number, color: Uint8ClampedArray, normal: Uint8ClampedArray|null }|null}
 */
export function generatePixels(name, quality = 'medium') {
  const def = DEFS[name];
  if (!def || !def.gen) return null;
  const S = textureSize(name, quality);
  const out = def.gen(S, createNoise(SEEDS[name]), createRng(SEEDS[name] * 7 + 3));
  return out.color ? { size: S, color: out.color, normal: out.normal || null } : { size: S, color: out, normal: null };
}

function nextFrame() {
  return new Promise((resolve) => {
    let done = false;
    const fin = () => {
      if (!done) {
        done = true;
        resolve();
      }
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(fin);
    setTimeout(fin, 60);
  });
}

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

function makeCanvas(S) {
  const c = document.createElement('canvas');
  c.width = S;
  c.height = S;
  return c;
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
      let r = 0, g = 0, b = 0, n = 0;
      for (let j = 0; j < f; j++) {
        for (let i = 0; i < f; i++) {
          const k = ((Math.floor(y * f) + j) * S + Math.floor(x * f) + i) * 4;
          r += px[k] - 128;
          g += px[k + 1] - 128;
          b += px[k + 2] - 128;
          n++;
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

/** Tangent-space normal map (OpenGL convention, +Y = +v) from a height field. */
function normalMap(h, S, strength) {
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
      if (pits.id[k] < 0.35) v -= (1 - smoothstep(0.05, 0.3, pits.f1[k])) * 0.12 * smoothstep(-0.1, 0.3, pitOn[k]);
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
    if (spots.id[k] < 0.06 && spots.f1[k] < 0.18 + (0.25 * spots.id[k]) / 0.06) mixc(c, lichenOrange, 0.6, c);
    else if (spots.id[k] > 0.94 && spots.f1[k] < 0.12) mixc(c, dark, 0.7, c);
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
  const grey = [174, 170, 162];
  const buff = [186, 175, 152];
  const cream = [198, 188, 166];
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
    const shade = clamp(1.04 + (h[k] - cav[k]) * 1.1, 0.66, 1.16);
    put(px, k, c[0] * shade, c[1] * shade, c[2] * shade);
  }
  return { color: px, normal: normalMap(h, S, 7) };
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
    let ky = Math.round(Math.sin(ang) * kmag);
    if (kx === 0 && ky === 0) kx = 1;
    waves.push({ kx, ky, a: 1 / Math.pow(Math.hypot(kx, ky), 1.25), ph: R.next() * Math.PI * 2 });
  }
  const rip = N.fbmField(S, 24, { octaves: 3, salt: 3 });
  const h = new Float32Array(n);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const u = x / S;
      const v = y / S;
      let s = 0;
      for (const w of waves) {
        // Sharpened crests (peaked sine).
        const p = Math.sin((w.kx * u + w.ky * v) * Math.PI * 2 + w.ph);
        s += w.a * (p - 0.25 * p * p);
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
  const px = new Uint8ClampedArray(n * 4);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const u = (x + 0.5) / S;
      const v = (y + 0.5) / S;
      const dx = u - 0.5;
      const dy = v - 0.5;
      const r = Math.hypot(dx, dy);
      const a0 = Math.atan2(dy, dx);
      const lobes = 0.2 + 0.05 * Math.sin(a0 * 5 + 1.3) + 0.03 * Math.sin(a0 * 11) + f[k] * 0.08;
      let a = 1 - smoothstep(lobes - 0.01, lobes + 0.004, r);
      for (const d of drops) {
        // Drops are stretched along their flight direction.
        const ex = u - d.x;
        const ey = v - d.y;
        const along = ex * Math.cos(d.ang) + ey * Math.sin(d.ang);
        const side = -ex * Math.sin(d.ang) + ey * Math.cos(d.ang);
        const dd = Math.hypot(along / 1.8, side) / d.r;
        a = Math.max(a, 1 - smoothstep(0.85, 1, dd));
      }
      if (r > 0.49) a = 0;
      const thick = clamp(0.75 + g[k] * 0.3 + (0.2 - r) * 0.8, 0.4, 1);
      put(px, k, 92 * thick + 14, 6, 8, a * 255 * (0.82 + 0.18 * thick));
    }
  }
  return px;
}

/** Mineral crust round a hot-spring vent: white travertine, pale sulphur, rusty runoff. */
function genSulphur(S, N) {
  const n = S * S;
  const f = N.fbmField(S, 3, { octaves: 6, salt: 1 });
  const g = N.fbmField(S, 12, { octaves: 4, salt: 2 });
  const zone = N.fbmField(S, 4, { octaves: 4, salt: 3 });
  const crust = N.worleyField(S, 20, 20, 1, 4);
  const px = new Uint8ClampedArray(n * 4);
  const white = [208, 202, 180];
  const grey = [172, 164, 140];
  const yellow = [204, 182, 96];
  const rust = [168, 104, 58];
  const brown = [128, 96, 66];
  const c = [0, 0, 0];
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const k = y * S + x;
      const dx = (x + 0.5) / S - 0.5;
      const dy = (y + 0.5) / S - 0.5;
      const r = Math.hypot(dx, dy) * 2 + f[k] * 0.32;
      mixc(white, grey, smoothstep(0.1, 0.6, r) * 0.6 + g[k] * 0.2, c);
      // Patches rather than rings: sulphur near the vent, rust in the runoff.
      mixc(c, yellow, smoothstep(0.15, 0.45, zone[k]) * (1 - smoothstep(0.2, 0.55, r)) * 0.8, c);
      mixc(c, rust, smoothstep(0.0, 0.35, -zone[k]) * smoothstep(0.3, 0.7, r) * 0.7, c);
      mixc(c, brown, smoothstep(0.7, 0.95, r) * 0.6, c);
      const cr = 0.86 + smoothstep(0, 0.1, crust.f2[k] - crust.f1[k]) * 0.14;
      const a = (1 - smoothstep(0.55, 0.95, r)) * (0.85 + g[k] * 0.3);
      put(px, k, c[0] * cr, c[1] * cr, c[2] * cr, clamp(a, 0, 1) * 215);
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

// ---------------------------------------------------------------------------------------
// Painted shield blazons: dark paint on a transparent ground, details incised (cut out)
// as in black-figure painting. Drawn in unit coordinates, y down, centred on (0.5, 0.5).
// ---------------------------------------------------------------------------------------

const INK = 'rgba(22,15,11,0.97)';

function incise(g, width, fn) {
  g.save();
  g.globalCompositeOperation = 'destination-out';
  g.strokeStyle = '#000';
  g.fillStyle = '#000';
  g.lineWidth = width;
  g.lineCap = 'round';
  g.lineJoin = 'round';
  fn();
  g.restore();
}

function ellipse(g, x, y, rx, ry, rot = 0) {
  g.beginPath();
  g.ellipse(x, y, rx, ry, rot, 0, Math.PI * 2);
}

function polyPath(g, pts, close = true) {
  g.beginPath();
  g.moveTo(pts[0][0], pts[0][1]);
  for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]);
  if (close) g.closePath();
}

/** Smooth closed path through points (Catmull-Rom → Bézier). */
function smoothPath(g, pts, close = true) {
  const n = pts.length;
  g.beginPath();
  g.moveTo(pts[0][0], pts[0][1]);
  const last = close ? n : n - 1;
  for (let i = 0; i < last; i++) {
    const p0 = pts[(i - 1 + n) % n];
    const p1 = pts[i];
    const p2 = pts[(i + 1) % n];
    const p3 = pts[(i + 2) % n];
    const a = !close && i === 0 ? p1 : p0;
    const d = !close && i === n - 2 ? p2 : p3;
    g.bezierCurveTo(p1[0] + (p2[0] - a[0]) / 6, p1[1] + (p2[1] - a[1]) / 6, p2[0] - (d[0] - p1[0]) / 6, p2[1] - (d[1] - p1[1]) / 6, p2[0], p2[1]);
  }
  if (close) g.closePath();
}

function strokeLimb(g, pts, w0, w1) {
  // Tapered limb as a sequence of segments with decreasing width.
  for (let i = 0; i < pts.length - 1; i++) {
    g.lineWidth = lerp(w0, w1, i / Math.max(1, pts.length - 2));
    g.beginPath();
    g.moveTo(pts[i][0], pts[i][1]);
    g.lineTo(pts[i + 1][0], pts[i + 1][1]);
    g.stroke();
  }
}

function inkStyle(g) {
  g.fillStyle = INK;
  g.strokeStyle = INK;
  g.lineCap = 'round';
  g.lineJoin = 'round';
}

/** The player's fly (Plutarch, Moralia 234C), seen from above, head up. */
function drawFly(g) {
  inkStyle(g);
  // Wings, translucent paint with incised veins.
  for (const s of [-1, 1]) {
    g.save();
    g.globalAlpha = 0.62;
    ellipse(g, 0.5 + s * 0.135, 0.6, 0.075, 0.215, -s * 0.42);
    g.fill();
    g.restore();
    incise(g, 0.004, () => {
      for (let i = 0; i < 4; i++) {
        g.beginPath();
        g.moveTo(0.5 + s * 0.06, 0.45);
        g.quadraticCurveTo(0.5 + s * (0.12 + i * 0.02), 0.58, 0.5 + s * (0.1 + i * 0.045), 0.72 + i * 0.03);
        g.stroke();
      }
    });
  }
  // Legs: three pairs, two segments each.
  g.lineWidth = 0.012;
  const legs = [
    [[0.055, 0.40], [0.13, 0.31], [0.15, 0.22], [0.17, 0.19]],
    [[0.065, 0.44], [0.17, 0.44], [0.24, 0.52], [0.27, 0.53]],
    [[0.055, 0.49], [0.13, 0.58], [0.16, 0.72], [0.18, 0.76]],
  ];
  for (const s of [-1, 1]) {
    for (const leg of legs) strokeLimb(g, leg.map(([x, y]) => [0.5 + s * x, y]), 0.016, 0.007);
  }
  // Abdomen.
  ellipse(g, 0.5, 0.585, 0.07, 0.115);
  g.fill();
  // Thorax.
  ellipse(g, 0.5, 0.43, 0.082, 0.075);
  g.fill();
  // Head and compound eyes.
  ellipse(g, 0.5, 0.315, 0.07, 0.05);
  g.fill();
  for (const s of [-1, 1]) {
    ellipse(g, 0.5 + s * 0.05, 0.305, 0.045, 0.05, s * 0.3);
    g.fill();
  }
  incise(g, 0.005, () => {
    for (const s of [-1, 1]) {
      ellipse(g, 0.5 + s * 0.05, 0.305, 0.034, 0.039, s * 0.3);
      g.stroke();
      ellipse(g, 0.5 + s * 0.06, 0.29, 0.008, 0.008);
      g.fill();
    }
    // Thorax stripes and abdomen segments.
    for (const dx of [-0.03, 0, 0.03]) {
      g.beginPath();
      g.moveTo(0.5 + dx, 0.38);
      g.lineTo(0.5 + dx * 1.1, 0.475);
      g.stroke();
    }
    for (let i = 0; i < 4; i++) {
      g.beginPath();
      const y = 0.53 + i * 0.035;
      g.ellipse(0.5, y - 0.02, 0.06 - i * 0.008, 0.03, 0, Math.PI * 0.15, Math.PI * 0.85);
      g.stroke();
    }
  });
  // Bristles.
  g.lineWidth = 0.004;
  for (let i = 0; i < 14; i++) {
    const a = -Math.PI * 0.9 + (i / 13) * Math.PI * 0.8;
    g.beginPath();
    g.moveTo(0.5 + Math.cos(a) * 0.08, 0.43 + Math.sin(a) * 0.075);
    g.lineTo(0.5 + Math.cos(a) * 0.1, 0.43 + Math.sin(a) * 0.092);
    g.stroke();
  }
}

/** Gorgoneion: frontal Gorgon head with snakes, tusks and lolling tongue. */
function drawGorgon(g) {
  inkStyle(g);
  // Snakes radiating round the head.
  const snakes = 12;
  for (let i = 0; i < snakes; i++) {
    const a = (i / snakes) * Math.PI * 2 + 0.13;
    const pts = [];
    for (let t = 0; t <= 1.001; t += 0.1) {
      const r = 0.2 + t * 0.17;
      const wig = Math.sin(t * Math.PI * 2.2 + i) * 0.11 * t;
      pts.push([0.5 + Math.cos(a + wig) * r, 0.5 + Math.sin(a + wig) * r]);
    }
    g.lineWidth = 0.02;
    g.beginPath();
    g.moveTo(pts[0][0], pts[0][1]);
    for (let k = 1; k < pts.length; k++) g.lineTo(pts[k][0], pts[k][1]);
    g.stroke();
    const hp = pts[pts.length - 1];
    const pp = pts[pts.length - 2];
    const ha = Math.atan2(hp[1] - pp[1], hp[0] - pp[0]);
    ellipse(g, hp[0], hp[1], 0.024, 0.015, ha);
    g.fill();
    g.lineWidth = 0.003;
    g.beginPath();
    g.moveTo(hp[0] + Math.cos(ha) * 0.02, hp[1] + Math.sin(ha) * 0.02);
    g.lineTo(hp[0] + Math.cos(ha) * 0.04, hp[1] + Math.sin(ha) * 0.04);
    g.stroke();
  }
  // Face.
  ellipse(g, 0.5, 0.5, 0.215, 0.205);
  g.fill();
  incise(g, 0.006, () => {
    // Brows, eyes, nose.
    for (const s of [-1, 1]) {
      g.beginPath();
      g.arc(0.5 + s * 0.08, 0.43, 0.06, Math.PI * 1.1, Math.PI * 1.9);
      g.stroke();
      ellipse(g, 0.5 + s * 0.08, 0.45, 0.045, 0.04);
      g.fill();
    }
    g.beginPath();
    g.moveTo(0.48, 0.44);
    g.quadraticCurveTo(0.47, 0.53, 0.45, 0.545);
    g.quadraticCurveTo(0.5, 0.56, 0.55, 0.545);
    g.quadraticCurveTo(0.53, 0.53, 0.52, 0.44);
    g.stroke();
    // Grinning mouth.
    g.beginPath();
    g.moveTo(0.37, 0.585);
    g.quadraticCurveTo(0.5, 0.56, 0.63, 0.585);
    g.quadraticCurveTo(0.5, 0.67, 0.37, 0.585);
    g.fill();
    // Curls along the brow.
    for (let i = 0; i < 9; i++) {
      g.beginPath();
      g.arc(0.355 + i * 0.036, 0.345 + Math.abs(i - 4) * 0.006, 0.013, 0, Math.PI * 1.6);
      g.stroke();
    }
    // Ear spirals.
    for (const s of [-1, 1]) {
      g.beginPath();
      g.arc(0.5 + s * 0.2, 0.5, 0.02, 0, Math.PI * 1.5);
      g.stroke();
    }
  });
  // Pupils.
  for (const s of [-1, 1]) {
    ellipse(g, 0.5 + s * 0.08, 0.45, 0.018, 0.018);
    g.fill();
  }
  // Tusks and tongue.
  for (const s of [-1, 1]) {
    polyPath(g, [[0.5 + s * 0.07, 0.585], [0.5 + s * 0.095, 0.585], [0.5 + s * 0.085, 0.64]]);
    g.fill();
    polyPath(g, [[0.5 + s * 0.035, 0.635], [0.5 + s * 0.06, 0.632], [0.5 + s * 0.05, 0.585]]);
    g.fill();
  }
  smoothPath(g, [[0.46, 0.6], [0.54, 0.6], [0.555, 0.68], [0.5, 0.735], [0.445, 0.68]]);
  g.fill();
  incise(g, 0.005, () => {
    g.beginPath();
    g.moveTo(0.5, 0.62);
    g.lineTo(0.5, 0.71);
    g.stroke();
  });
}

/** Striding lion, facing left, tail raised. */
function drawLion(g) {
  inkStyle(g);
  // Tail.
  g.lineWidth = 0.022;
  g.beginPath();
  g.moveTo(0.74, 0.44);
  g.bezierCurveTo(0.84, 0.44, 0.84, 0.3, 0.78, 0.27);
  g.bezierCurveTo(0.74, 0.25, 0.76, 0.2, 0.8, 0.2);
  g.stroke();
  ellipse(g, 0.81, 0.195, 0.03, 0.022, -0.4);
  g.fill();
  // Legs (far pair first).
  g.lineWidth = 0.05;
  strokeLimb(g, [[0.33, 0.54], [0.36, 0.64], [0.37, 0.73]], 0.055, 0.035);
  strokeLimb(g, [[0.66, 0.52], [0.7, 0.6], [0.66, 0.66], [0.68, 0.73]], 0.07, 0.035);
  strokeLimb(g, [[0.28, 0.54], [0.25, 0.64], [0.22, 0.73]], 0.06, 0.038);
  strokeLimb(g, [[0.62, 0.52], [0.6, 0.6], [0.58, 0.66], [0.55, 0.73]], 0.075, 0.038);
  for (const p of [[0.37, 0.74], [0.69, 0.74], [0.21, 0.74], [0.54, 0.74]]) {
    ellipse(g, p[0] - 0.012, p[1], 0.035, 0.018);
    g.fill();
  }
  // Body.
  smoothPath(g, [
    [0.3, 0.4], [0.42, 0.42], [0.55, 0.41], [0.66, 0.39], [0.74, 0.42], [0.75, 0.48],
    [0.7, 0.55], [0.6, 0.55], [0.48, 0.52], [0.38, 0.56], [0.27, 0.57], [0.24, 0.5],
  ]);
  g.fill();
  // Mane: flame-like tufts round the head and neck.
  const mane = [];
  for (let i = 0; i <= 16; i++) {
    const a = Math.PI * 0.55 + (i / 16) * Math.PI * 1.55;
    const r = i % 2 ? 0.115 : 0.155;
    mane.push([0.27 + Math.cos(a) * r, 0.4 + Math.sin(a) * r * 0.95]);
  }
  polyPath(g, mane);
  g.fill();
  // Head and open jaws.
  smoothPath(g, [[0.22, 0.33], [0.29, 0.34], [0.3, 0.43], [0.22, 0.47], [0.13, 0.45], [0.11, 0.4], [0.15, 0.35]]);
  g.fill();
  polyPath(g, [[0.15, 0.44], [0.09, 0.45], [0.1, 0.475], [0.19, 0.48]]);
  g.fill();
  incise(g, 0.006, () => {
    ellipse(g, 0.19, 0.375, 0.016, 0.011, -0.2);
    g.fill();
    g.beginPath();
    g.moveTo(0.11, 0.435);
    g.lineTo(0.18, 0.44);
    g.stroke();
    // Mane strands.
    for (let i = 0; i < 9; i++) {
      const a = Math.PI * 0.75 + (i / 8) * Math.PI * 1.2;
      g.beginPath();
      g.moveTo(0.27 + Math.cos(a) * 0.07, 0.4 + Math.sin(a) * 0.065);
      g.lineTo(0.27 + Math.cos(a) * 0.125, 0.4 + Math.sin(a) * 0.12);
      g.stroke();
    }
    // Ribs, shoulder and haunch.
    for (let i = 0; i < 4; i++) {
      g.beginPath();
      g.arc(0.46 + i * 0.035, 0.44, 0.07, Math.PI * 0.35, Math.PI * 0.7);
      g.stroke();
    }
    g.beginPath();
    g.arc(0.66, 0.47, 0.06, Math.PI * 0.9, Math.PI * 1.7);
    g.stroke();
    g.beginPath();
    g.arc(0.31, 0.49, 0.05, Math.PI * 1.2, Math.PI * 2.1);
    g.stroke();
  });
}

/** Charging boar with a bristling crest, facing left. */
function drawBoar(g) {
  inkStyle(g);
  // Legs.
  strokeLimb(g, [[0.33, 0.55], [0.31, 0.63], [0.29, 0.7]], 0.05, 0.03);
  strokeLimb(g, [[0.4, 0.56], [0.42, 0.63], [0.44, 0.7]], 0.05, 0.03);
  strokeLimb(g, [[0.66, 0.55], [0.7, 0.62], [0.73, 0.7]], 0.06, 0.03);
  strokeLimb(g, [[0.72, 0.54], [0.67, 0.62], [0.63, 0.7]], 0.06, 0.03);
  for (const p of [[0.29, 0.71], [0.44, 0.71], [0.73, 0.71], [0.63, 0.71]]) {
    polyPath(g, [[p[0] - 0.022, p[1] + 0.012], [p[0] + 0.02, p[1] + 0.012], [p[0], p[1] - 0.02]]);
    g.fill();
  }
  // Body: humped back, deep chest, long snout.
  smoothPath(g, [
    [0.15, 0.5], [0.2, 0.44], [0.28, 0.38], [0.4, 0.33], [0.55, 0.33], [0.68, 0.36],
    [0.79, 0.42], [0.8, 0.5], [0.74, 0.57], [0.6, 0.58], [0.45, 0.58], [0.32, 0.58], [0.22, 0.55],
  ]);
  g.fill();
  // Snout disc.
  polyPath(g, [[0.16, 0.47], [0.1, 0.475], [0.1, 0.52], [0.17, 0.53]]);
  g.fill();
  // Bristle crest.
  const crest = [];
  for (let i = 0; i <= 18; i++) {
    const t = i / 18;
    const x = 0.27 + t * 0.45;
    const y = 0.4 - Math.sin(t * Math.PI) * 0.075 + 0.01;
    crest.push([x, y + 0.02]);
    if (i < 18) crest.push([x + 0.0125, y - 0.035 - (i % 3 === 1 ? 0.012 : 0)]);
  }
  crest.push([0.72, 0.44], [0.27, 0.44]);
  polyPath(g, crest);
  g.fill();
  // Ear and tail.
  polyPath(g, [[0.3, 0.39], [0.35, 0.3], [0.37, 0.4]]);
  g.fill();
  g.lineWidth = 0.012;
  g.beginPath();
  g.moveTo(0.79, 0.44);
  g.bezierCurveTo(0.85, 0.42, 0.84, 0.36, 0.81, 0.38);
  g.stroke();
  incise(g, 0.006, () => {
    // Tusk, eye, mouth, nostril.
    g.lineWidth = 0.012;
    g.beginPath();
    g.moveTo(0.19, 0.52);
    g.quadraticCurveTo(0.15, 0.5, 0.165, 0.455);
    g.stroke();
    g.lineWidth = 0.006;
    ellipse(g, 0.26, 0.43, 0.012, 0.009, -0.3);
    g.fill();
    g.beginPath();
    g.moveTo(0.11, 0.515);
    g.lineTo(0.22, 0.535);
    g.stroke();
    ellipse(g, 0.11, 0.49, 0.006, 0.01);
    g.fill();
    // Shoulder and ham lines, bristle strokes.
    g.beginPath();
    g.arc(0.37, 0.48, 0.07, Math.PI * 1.1, Math.PI * 1.9);
    g.stroke();
    g.beginPath();
    g.arc(0.7, 0.48, 0.07, Math.PI * 1.0, Math.PI * 1.7);
    g.stroke();
    for (let i = 0; i < 10; i++) {
      const x = 0.4 + i * 0.03;
      g.beginPath();
      g.moveTo(x, 0.44);
      g.lineTo(x + 0.012, 0.47);
      g.stroke();
    }
  });
}

/** Herakles' knotted club, diagonal. */
function drawClub(g) {
  inkStyle(g);
  g.save();
  g.translate(0.5, 0.5);
  g.rotate(-0.78);
  // Shaft from handle (left) to head (right), along +x.
  smoothPath(g, [[-0.36, -0.03], [-0.1, -0.05], [0.12, -0.08], [0.3, -0.105], [0.37, -0.07], [0.38, 0], [0.37, 0.07],
    [0.3, 0.105], [0.12, 0.08], [-0.1, 0.05], [-0.36, 0.03], [-0.38, 0]]);
  g.fill();
  // Knots.
  const R = createRng(31);
  for (let i = 0; i < 11; i++) {
    const t = -0.2 + (i / 10) * 0.52;
    const w = 0.04 + ((t + 0.2) / 0.52) * 0.06;
    const side = i % 2 ? 1 : -1;
    ellipse(g, t, side * w, 0.025 + R.next() * 0.012, 0.02 + R.next() * 0.01, R.next());
    g.fill();
  }
  // Pommel.
  ellipse(g, -0.375, 0, 0.022, 0.04);
  g.fill();
  incise(g, 0.005, () => {
    for (const dy of [-0.02, 0.012, 0.035]) {
      g.beginPath();
      g.moveTo(-0.3, dy * 0.5);
      g.bezierCurveTo(-0.05, dy * 0.9, 0.15, dy * 1.4, 0.33, dy * 1.6);
      g.stroke();
    }
    for (let i = 0; i < 6; i++) {
      const t = -0.15 + i * 0.09;
      g.beginPath();
      g.arc(t, (i % 2 ? 1 : -1) * (0.045 + i * 0.008), 0.012, 0, Math.PI * 2);
      g.stroke();
    }
    g.beginPath();
    g.moveTo(-0.33, -0.035);
    g.lineTo(-0.33, 0.035);
    g.moveTo(-0.29, -0.037);
    g.lineTo(-0.29, 0.037);
    g.stroke();
  });
  g.restore();
}

/** Scorpion from above, pincers up, tail curled to the right. */
function drawScorpion(g) {
  inkStyle(g);
  // Legs: four pairs.
  for (const s of [-1, 1]) {
    for (let i = 0; i < 4; i++) {
      const y0 = 0.43 + i * 0.035;
      const pts = [[0.5 + s * 0.05, y0], [0.5 + s * (0.13 + i * 0.01), y0 - 0.03 + i * 0.01], [0.5 + s * (0.18 + i * 0.015), y0 + 0.04 + i * 0.03]];
      strokeLimb(g, pts, 0.016, 0.008);
    }
    // Pincer arm.
    strokeLimb(g, [[0.5 + s * 0.05, 0.4], [0.5 + s * 0.13, 0.36], [0.5 + s * 0.16, 0.27]], 0.026, 0.02);
    // Claw.
    ellipse(g, 0.5 + s * 0.165, 0.235, 0.038, 0.055, s * 0.25);
    g.fill();
    g.lineWidth = 0.016;
    g.beginPath();
    g.moveTo(0.5 + s * 0.15, 0.2);
    g.quadraticCurveTo(0.5 + s * 0.13, 0.15, 0.5 + s * 0.105, 0.155);
    g.stroke();
    g.beginPath();
    g.moveTo(0.5 + s * 0.185, 0.19);
    g.quadraticCurveTo(0.5 + s * 0.17, 0.13, 0.5 + s * 0.135, 0.13);
    g.stroke();
  }
  // Carapace and body segments.
  ellipse(g, 0.5, 0.42, 0.065, 0.055);
  g.fill();
  for (let i = 0; i < 6; i++) {
    ellipse(g, 0.5, 0.48 + i * 0.03, 0.072 - i * 0.004, 0.022);
    g.fill();
  }
  // Tail: tapering segments on a curl to the right, ending in the sting.
  const tail = [[0.5, 0.65], [0.5, 0.7], [0.515, 0.75], [0.55, 0.785], [0.6, 0.79], [0.645, 0.765], [0.665, 0.72]];
  for (let i = 0; i < tail.length; i++) {
    ellipse(g, tail[i][0], tail[i][1], 0.03 - i * 0.0015, 0.03 - i * 0.0015);
    g.fill();
  }
  ellipse(g, 0.665, 0.67, 0.032, 0.04, 0.2);
  g.fill();
  g.lineWidth = 0.014;
  g.beginPath();
  g.moveTo(0.665, 0.64);
  g.quadraticCurveTo(0.66, 0.6, 0.625, 0.6);
  g.stroke();
  incise(g, 0.005, () => {
    for (let i = 0; i < 6; i++) {
      g.beginPath();
      g.moveTo(0.44 + i * 0.002, 0.49 + i * 0.03);
      g.lineTo(0.56 - i * 0.002, 0.49 + i * 0.03);
      g.stroke();
    }
    ellipse(g, 0.485, 0.405, 0.008, 0.008);
    g.fill();
    ellipse(g, 0.515, 0.405, 0.008, 0.008);
    g.fill();
    g.beginPath();
    g.moveTo(0.5, 0.39);
    g.lineTo(0.5, 0.45);
    g.stroke();
  });
}

// ---------------------------------------------------------------------------------------
// Vegetation cards (alpha cut-outs). The base of the plant sits at the bottom of the card.
// ---------------------------------------------------------------------------------------

function drawGrass(g, R) {
  const cols = ['#c8b47c', '#b9a46a', '#d6c28c', '#a89a64', '#9a9a6a', '#bfa870', '#8f8a5a'];
  g.lineCap = 'round';
  for (let i = 0; i < 70; i++) {
    const x0 = 0.5 + (R.next() - 0.5) * 0.28;
    const lean = (R.next() - 0.5) * 0.9 + (x0 - 0.5) * 1.6;
    const len = 0.45 + R.next() * 0.5;
    const x1 = x0 + lean * len * 0.6;
    const y1 = 1 - len * 0.95;
    const cx = x0 + lean * len * 0.15;
    const cy = 1 - len * 0.55;
    g.strokeStyle = cols[Math.floor(R.next() * cols.length)];
    const w0 = 0.012 + R.next() * 0.01;
    const steps = 6;
    let px = x0;
    let py = 1;
    for (let s = 1; s <= steps; s++) {
      const t = s / steps;
      const qx = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cx + t * t * x1;
      const qy = (1 - t) * (1 - t) * 1 + 2 * (1 - t) * t * cy + t * t * y1;
      g.lineWidth = w0 * (1 - t * 0.85);
      g.beginPath();
      g.moveTo(px, py);
      g.lineTo(qx, qy);
      g.stroke();
      px = qx;
      py = qy;
    }
    // Some blades carry a seed head.
    if (R.next() < 0.25) {
      g.fillStyle = '#d8c690';
      ellipse(g, x1, y1 + 0.03, 0.012, 0.04, lean * 0.6);
      g.fill();
    }
  }
}

function drawScrub(g, R) {
  // Twigs fanning up from the base.
  g.strokeStyle = '#4a3a28';
  g.lineCap = 'round';
  for (let i = 0; i < 12; i++) {
    const a = -Math.PI / 2 + (R.next() - 0.5) * 1.5;
    const len = 0.2 + R.next() * 0.25;
    g.lineWidth = 0.012;
    g.beginPath();
    g.moveTo(0.5, 0.99);
    g.lineTo(0.5 + Math.cos(a) * len, 0.99 + Math.sin(a) * len);
    g.stroke();
  }
  // Leaf clusters: dark evergreen (kermes oak, lentisk), lighter towards the sunlit top.
  const greens = ['#2c3720', '#364426', '#41502b', '#4d5b33', '#5a673b', '#6a7444'];
  for (let i = 0; i < 1100; i++) {
    const a = R.next() * Math.PI * 2;
    const r = Math.sqrt(R.next()) * 0.42;
    const x = 0.5 + Math.cos(a) * r * (1 + 0.15 * Math.sin(a * 3));
    const y = 0.55 + Math.sin(a) * r * 0.82;
    if (y > 0.96) continue;
    const top = clamp((0.95 - y) / 0.8, 0, 1);
    const idx = clamp(Math.floor((top * 0.75 + R.next() * 0.45) * greens.length), 0, greens.length - 1);
    g.fillStyle = greens[idx];
    ellipse(g, x, y, 0.011 + R.next() * 0.01, 0.006 + R.next() * 0.006, R.next() * Math.PI);
    g.fill();
  }
}
