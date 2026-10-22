/**
 * Procedural textures (DESIGN.md §11). Everything is generated at runtime from seeded noise
 * (textures/generators.js) or painted on canvases (blazons and vegetation, below), then cached.
 * Colour maps are sRGB, data maps (normals) linear; all use RepeatWrapping.
 *
 * Boot (generateAll): pixels come from the cross-launch cache (textures/cache.js) when they
 * can, otherwise from generators on module workers (textures/pool.js) or, failing that, the
 * main thread. Only the textures a material samples (`gpu` in the catalogue) are uploaded;
 * character atlas sources and blazons stay canvases that the atlas draws from, and callers
 * hand them back with release(name) once they are done.
 */
import * as THREE from 'three';
import { clamp, lerp, createRng } from '../core/math.js';
import {
  TEXTURE_DEFS as DEFS, TEXTURE_NAMES, TEXTURE_SEEDS as SEEDS, TEXTURE_VERSION, textureSize, generatePixels, generateOwner,
  meanLuminance, flipRows, coverageMips, bleedColour, CUTOUT_ALPHA,
} from './textures/generators.js';
import { openPixelCache, textureCacheKey } from './textures/cache.js';
import { runOnWorkers, textureWorkerCount } from './textures/pool.js';

export { TEXTURE_NAMES, TEXTURE_VERSION, textureSize, generatePixels, CUTOUT_ALPHA };

/** Painters of the canvas-drawn textures (catalogue entries with `draw`). */
const DRAW = {
  blazonFly: drawFly,
  blazonGorgon: drawGorgon,
  blazonLion: drawLion,
  blazonBoar: drawBoar,
  blazonClub: drawClub,
  blazonScorpion: drawScorpion,
  grass: drawGrass,
  scrub: drawScrub,
};

/** What generateAll builds, in catalogue order (water, blood and spark have no consumer). */
export const BOOT_TEXTURES = TEXTURE_NAMES.filter((n) => DEFS[n].boot !== false);

const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

// ---------------------------------------------------------------------------------------
// Module
// ---------------------------------------------------------------------------------------

/**
 * @param {object} ctx §4 ctx (uses ctx.quality and ctx.renderer for anisotropy/upload)
 * @param {{ cache?: boolean, workers?: number }} [opts] turn off the pixel cache, or set the
 *   number of generator workers (0: main thread only); defaults suit the device
 * @returns {{ get(name: string): THREE.Texture, generateAll(onProgress?: (fraction: number, name: string) => void): Promise<void>,
 *   release(name: string): boolean, has(name: string): boolean, names: string[], memoryBytes(opts?: { all?: boolean }): number,
 *   stats: object }}
 */
export function createTextures(ctx, opts = {}) {
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
  const useCache = opts.cache !== false;
  /** What the last generateAll did (boot measurements and tests). */
  const stats = { ms: 0, fromCache: 0, generated: 0, workers: 0, workerJobs: 0, mainJobs: 0, cache: 'off' };
  // Open the pixel cache now, so it is usually ready by the time generateAll runs.
  let cacheP = null;
  const pixelCache = () => (cacheP ||= openPixelCache());
  if (useCache) pixelCache();
  const cacheKey = (name) => textureCacheKey(TEXTURE_VERSION, quality, name);

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

  /**
   * Makes `name`'s texture from RGBA rows (row 0 = v 0). Textures a material samples, and
   * data maps, keep the array as a DataTexture (what the GPU gets, and what a context restore
   * re-uploads); the others become canvases, which the character atlas draws from.
   */
  function install(name, S, px, meanLum, mips = null) {
    const def = DEFS[name];
    const linear = !!def.of || !!def.linear;
    let tex;
    if (def.data || def.gpu) {
      tex = new THREE.DataTexture(px, S, S, THREE.RGBAFormat, THREE.UnsignedByteType);
      tex.flipY = false;
    } else {
      const canvas = makeCanvas(S);
      // Canvases are top-down and flipped on upload.
      canvas.getContext('2d').putImageData(new ImageData(flipRows(px, S), S, S), 0, 0);
      tex = new THREE.CanvasTexture(canvas);
    }
    tex.userData.meanLum = meanLum;
    finish(tex, name, linear);
    if (def.cutout) {
      // Alpha-tested foliage thins out in the mips; keep its coverage instead.
      tex.mipmaps = mips || coverageMips(px, S, CUTOUT_ALPHA);
      tex.generateMipmaps = false;
    }
    return tex;
  }

  /** Paints a canvas texture's owner. Data ones come back as pixels, like a generator's. */
  function paint(name) {
    const def = DEFS[name];
    const S = textureSize(name, quality);
    const R = createRng(SEEDS[name] * 7 + 3);
    const canvas = makeCanvas(S);
    const g = canvas.getContext('2d', def.data ? { willReadFrequently: true } : undefined);
    g.save();
    g.scale(S, S);
    DRAW[name](g, R, S);
    g.restore();
    if (!def.data) return { name, size: S, canvas };
    const px = flipRows(new Uint8ClampedArray(g.getImageData(0, 0, S, S).data), S);
    if (def.cutout) bleedColour(px, S, 6);
    return { name, size: S, color: px, meanLum: meanLuminance(px), pair: null };
  }

  /**
   * Installs a generator's or painter's result: the owner and its pair, each only if `want`s
   * it and it isn't in memory already. Returns the names installed.
   */
  function accept(res, want) {
    const out = [];
    if (res.canvas) {
      if (!cache.has(res.name) && want(res.name)) {
        finish(new THREE.CanvasTexture(res.canvas), res.name, false);
        out.push(res.name);
      }
      return out;
    }
    for (const r of [res, res.pair]) {
      if (!r || cache.has(r.name) || !want(r.name)) continue;
      install(r.name, r.size, r.color, r.meanLum);
      out.push(r.name);
    }
    return out;
  }

  function build(name) {
    const owner = DEFS[name].of || name;
    const res = DEFS[owner].draw ? paint(owner) : generateOwner(owner, quality);
    // The sibling comes along for free unless nothing uses it.
    accept(res, (n) => n === name || DEFS[n].boot !== false);
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

  function upload(name) {
    if (!DEFS[name].gpu) return;
    try {
      if (ctx.renderer && ctx.renderer.initTexture) ctx.renderer.initTexture(cache.get(name));
    } catch (err) {
      console.warn('textures: upload failed', name, err);
    }
  }

  /** A cache record as stored, or null if it doesn't fit `name` at this quality. */
  function validRecord(name, rec) {
    const S = textureSize(name, quality);
    if (!rec || rec.size !== S || !rec.data || rec.data.length !== S * S * 4) return null;
    const px = rec.data instanceof Uint8ClampedArray ? rec.data : new Uint8ClampedArray(rec.data.buffer, rec.data.byteOffset, rec.data.length);
    let mips = null;
    if (DEFS[name].cutout && Array.isArray(rec.mips)) {
      mips = [{ data: px, width: S, height: S }];
      for (let s = S >> 1, i = 0; s >= 1; s >>= 1, i++) {
        const m = rec.mips[i];
        if (!m || m.length !== s * s * 4) {
          mips = null;
          break;
        }
        mips.push({ data: m, width: s, height: s });
      }
    }
    return { size: S, px, meanLum: typeof rec.meanLum === 'number' ? rec.meanLum : undefined, mips };
  }

  /** Caches generated pixels (`r`: a generator's or painter's image) for later launches. */
  function store(db, name, r) {
    const rec = { size: r.size, data: r.color, meanLum: r.meanLum };
    const tex = cache.get(name);
    if (DEFS[name].cutout && tex && tex.mipmaps) rec.mips = tex.mipmaps.slice(1).map((m) => m.data);
    db.put(cacheKey(name), rec);
  }

  async function runAll(onProgress) {
    const t0 = now();
    const total = BOOT_TEXTURES.length;
    let done = 0;
    let lastYield = now();
    const report = (name) => {
      done++;
      if (onProgress) onProgress(done / total, name);
    };
    // Keep the boot screen animating without spending a frame per texture.
    const breathe = async () => {
      if (now() - lastYield > 24) {
        await nextFrame();
        lastYield = now();
      }
    };
    Object.assign(stats, { ms: 0, fromCache: 0, generated: 0, workers: 0, workerJobs: 0, mainJobs: 0, cache: useCache ? 'none' : 'off' });

    // 1. Already in memory (get() or an earlier run).
    for (const name of BOOT_TEXTURES) {
      if (!cache.has(name)) continue;
      upload(name);
      report(name);
    }

    // 2. Pixels cached by an earlier launch.
    const wanted = new Set(BOOT_TEXTURES.filter((n) => !cache.has(n)));
    const db = useCache && wanted.size ? await pixelCache() : null;
    if (db) {
      stats.cache = 'idb';
      const names = [...wanted];
      const recs = await db.getMany(names.map(cacheKey));
      for (let i = 0; i < names.length; i++) {
        const name = names[i];
        const rec = validRecord(name, recs[i]);
        recs[i] = null;
        if (!rec || cache.has(name)) continue;
        try {
          const meanLum = rec.meanLum ?? (DEFS[name].of || DEFS[name].linear ? 0.5 : meanLuminance(rec.px));
          install(name, rec.size, rec.px, meanLum, rec.mips);
        } catch (err) {
          console.warn('textures: cached pixels unusable', name, err);
          cache.delete(name);
          continue;
        }
        wanted.delete(name);
        stats.fromCache++;
        upload(name);
        report(name);
        await breathe();
      }
    }

    // 3. Generate the rest: generators on workers, painters on the main thread meanwhile.
    for (const n of [...wanted]) if (cache.has(n)) wanted.delete(n);
    const owners = [...new Set([...wanted].map((n) => DEFS[n].of || n))];
    const costOf = (o) => textureSize(o, quality) ** 2 * (DEFS[o].cost || 1);
    const gens = owners.filter((o) => DEFS[o].gen).sort((a, b) => costOf(b) - costOf(a));
    const paints = owners.filter((o) => DEFS[o].draw);
    const want = (n) => wanted.has(n);
    const take = (res) => {
      for (const name of accept(res, want)) {
        wanted.delete(name);
        stats.generated++;
        const r = res.name === name ? res : res.pair;
        if (db && r && r.color) store(db, name, r);
        upload(name);
        report(name);
      }
    };
    const count = gens.length ? (opts.workers ?? textureWorkerCount()) : 0;
    const onWorkers = count > 0
      ? runOnWorkers(gens, {
        count,
        quality,
        onResult: (res) => {
          stats.workerJobs++;
          take(res);
        },
        // Workers can be busy for seconds at a time on a slow phone: keep the boot watchdog fed.
        onTick: () => onProgress && onProgress(done / total, 'generating'),
      })
      : Promise.resolve({ left: gens, workers: 0 });
    for (const o of paints) {
      take(paint(o));
      stats.mainJobs++;
      await breathe();
    }
    const { left, workers } = await onWorkers;
    stats.workers = workers;
    for (const o of left) {
      take(generateOwner(o, quality));
      stats.mainJobs++;
      await nextFrame();
      lastYield = now();
    }
    // Anything still missing (an owner that failed to produce it) is built on demand.
    for (const name of [...wanted]) {
      get(name);
      upload(name);
      report(name);
    }
    if (db && stats.generated) {
      // Generated something: drop pixels cached by other generator versions.
      db.prune(`${TEXTURE_VERSION}:`);
    }
    if (cacheP) {
      // Pending writes still finish after close().
      cacheP.then((c) => c && c.close());
      cacheP = null;
    }
    stats.ms = Math.round(now() - t0);
  }

  let running = null;
  /** Builds every boot texture behind the boot screen and uploads the ones materials sample. */
  async function generateAll(onProgress) {
    while (running) await running.catch(() => {});
    running = runAll(onProgress);
    try {
      await running;
    } finally {
      running = null;
    }
  }

  /**
   * Hands a texture back once its user is done with it (the character atlas, after drawing
   * its sources): frees its GPU copy and drops this module's pixels. A later get() builds it
   * again. Returns whether it was in memory.
   */
  function release(name) {
    const tex = cache.get(name);
    if (!tex) return false;
    cache.delete(name);
    try {
      tex.dispose();
    } catch (err) {
      console.warn('textures: dispose failed', name, err);
    }
    return true;
  }

  /** Whether `name` is in memory (get() would not have to build it). */
  function has(name) {
    return cache.has(name);
  }

  /**
   * Estimated GPU bytes (RGBA8 + full mip chain) of the textures in memory that materials
   * sample, which generateAll uploads. `{ all: true }` counts every texture in memory.
   */
  function memoryBytes({ all = false } = {}) {
    let sum = 0;
    for (const [name, tex] of cache) {
      if (!all && !DEFS[name].gpu) continue;
      const img = tex.image;
      sum += (img.width * img.height * 4 * 4) / 3;
    }
    return Math.round(sum);
  }

  return { get, generateAll, release, has, names: TEXTURE_NAMES.slice(), memoryBytes, stats };
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

function makeCanvas(S) {
  const c = document.createElement('canvas');
  c.width = S;
  c.height = S;
  return c;
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
  // A summer-dry tuft: a fan of thin, drooping blades, darker and denser at the base, a few
  // still faintly green, some carrying oat-like seed heads.
  const cols = [
    [196, 182, 140], [210, 198, 160], [178, 166, 128], [164, 150, 116], [150, 140, 112],
    [186, 170, 124], [140, 140, 108], [128, 132, 100], [200, 190, 156],
  ];
  g.lineCap = 'round';
  const blades = 150;
  for (let i = 0; i < blades; i++) {
    const back = i < blades * 0.35;
    const x0 = 0.5 + (R.next() - 0.5) * (back ? 0.22 : 0.34);
    // Fan: lean grows with distance from the centre; outer blades droop.
    const ang = (R.next() - 0.5) * 1.6 + (x0 - 0.5) * 2.2;
    const len = (0.4 + R.next() * 0.55) * (1 - Math.abs(ang) * 0.25);
    const droop = 0.15 + R.next() * 0.35;
    const sx = Math.sin(ang);
    const x1 = x0 + sx * len * 0.85;
    const y1 = 1 - len * Math.cos(ang) * (1 - droop * Math.abs(sx));
    const cx = x0 + sx * len * 0.3;
    const cy = 1 - len * 0.75;
    const c = cols[Math.floor(R.next() * cols.length)];
    const shade = back ? 0.72 + R.next() * 0.15 : 0.85 + R.next() * 0.2;
    const grad = g.createLinearGradient(x0, 1, x1, y1);
    grad.addColorStop(0, `rgb(${(c[0] * shade * 0.62) | 0},${(c[1] * shade * 0.6) | 0},${(c[2] * shade * 0.55) | 0})`);
    grad.addColorStop(0.45, `rgb(${(c[0] * shade) | 0},${(c[1] * shade) | 0},${(c[2] * shade) | 0})`);
    grad.addColorStop(1, `rgb(${Math.min(255, c[0] * shade * 1.08) | 0},${Math.min(255, c[1] * shade * 1.08) | 0},${Math.min(255, c[2] * shade * 1.05) | 0})`);
    g.strokeStyle = grad;
    const w0 = 0.009 + R.next() * 0.009;
    const steps = 7;
    let px = x0;
    let py = 1;
    for (let k = 1; k <= steps; k++) {
      const t = k / steps;
      const qx = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cx + t * t * x1;
      const qy = (1 - t) * (1 - t) * 1 + 2 * (1 - t) * t * cy + t * t * y1;
      g.lineWidth = w0 * (1 - t * 0.8);
      g.beginPath();
      g.moveTo(px, py);
      g.lineTo(qx, qy);
      g.stroke();
      px = qx;
      py = qy;
    }
    if (!back && R.next() < 0.2) {
      // Seed head: a few pale spikelets hanging off the last part of the stem.
      g.fillStyle = `rgb(${(214 * shade) | 0},${(202 * shade) | 0},${(164 * shade) | 0})`;
      for (let k = 0; k < 5; k++) {
        const t = 0.8 + k * 0.045;
        const qx = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * cx + t * t * x1;
        const qy = (1 - t) * (1 - t) * 1 + 2 * (1 - t) * t * cy + t * t * y1;
        ellipse(g, qx + (k & 1 ? 0.008 : -0.008), qy + 0.012, 0.005, 0.016, (k & 1 ? 0.5 : -0.5) + ang * 0.5);
        g.fill();
      }
    }
  }
}

function drawScrub(g, R) {
  // Maquis (kermes oak, lentisk): a low, wide, irregular mound of small dark leaves built from
  // overlapping clumps, sunlit grey-green on top, nearly black inside, a few dead twigs.
  const clumps = [];
  const nc = 6 + Math.floor(R.next() * 3);
  for (let i = 0; i < nc; i++) {
    const a = (i / nc) * Math.PI + (R.next() - 0.5) * 0.5;
    clumps.push({
      x: 0.5 - Math.cos(a) * (0.2 + R.next() * 0.12),
      y: 0.8 - Math.sin(a) * (0.1 + R.next() * 0.2),
      r: 0.13 + R.next() * 0.1,
    });
  }
  clumps.push({ x: 0.5, y: 0.66, r: 0.24 }, { x: 0.33, y: 0.84, r: 0.14 }, { x: 0.67, y: 0.84, r: 0.14 });
  g.lineCap = 'round';
  g.strokeStyle = '#3b3024';
  for (let i = 0; i < 9; i++) {
    const a = -Math.PI / 2 + (R.next() - 0.5) * 2.2;
    const len = 0.12 + R.next() * 0.12;
    g.lineWidth = 0.008;
    g.beginPath();
    g.moveTo(0.5 + (R.next() - 0.5) * 0.2, 0.99);
    g.lineTo(0.5 + Math.cos(a) * len, 0.99 + Math.sin(a) * len * 0.9);
    g.stroke();
  }
  // Dusty grey-greens: maquis on limestone, not a lawn.
  const greens = ['#2a3220', '#343d26', '#3f482c', '#4b5434', '#58603d', '#656c48', '#737856', '#82856a'];
  for (let i = 0; i < 1800; i++) {
    const cl = clumps[Math.floor(R.next() * clumps.length)];
    const a = R.next() * Math.PI * 2;
    const r = Math.sqrt(R.next()) * cl.r;
    const x = cl.x + Math.cos(a) * r;
    const y = cl.y + Math.sin(a) * r * 0.85;
    if (y > 0.985 || y < 0.03 || x < 0.03 || x > 0.97) continue;
    // Light from above and outside; dark in the core of each clump.
    const outer = r / cl.r;
    const top = clamp((cl.y - y) / cl.r * 0.5 + 0.5, 0, 1);
    const lum = clamp(top * 0.55 + outer * 0.35 + (R.next() - 0.5) * 0.45, 0, 0.999);
    g.fillStyle = greens[Math.floor(lum * greens.length)];
    ellipse(g, x, y, 0.009 + R.next() * 0.009, 0.005 + R.next() * 0.005, R.next() * Math.PI);
    g.fill();
  }
  // Dead twigs poking out of the mound.
  g.strokeStyle = '#6e6250';
  for (let i = 0; i < 6; i++) {
    const cl = clumps[Math.floor(R.next() * clumps.length)];
    const a = -Math.PI / 2 + (R.next() - 0.5) * 2.4;
    const x = cl.x + Math.cos(a) * cl.r * 0.8;
    const y = cl.y + Math.sin(a) * cl.r * 0.7;
    g.lineWidth = 0.005;
    g.beginPath();
    g.moveTo(x, y);
    g.lineTo(x + Math.cos(a) * 0.07, y + Math.sin(a) * 0.07);
    g.stroke();
  }
}
