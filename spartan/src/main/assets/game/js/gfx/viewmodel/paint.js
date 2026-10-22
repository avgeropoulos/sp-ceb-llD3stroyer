/**
 * Procedural canvas textures for the viewmodel (close-up detail the shared textures don't need):
 * the arm atlas (skin, leather wrist wrap, crimson sleeve, bronze ring), the weapon atlas (iron,
 * bronze, ash, leather cord) and the aspis bronze face and wooden interior.
 *
 * Each set is painted into float buffers (albedo, height, roughness, metalness) and baked to an
 * sRGB colour canvas, a tangent-space normal canvas and an ORM canvas (G = roughness,
 * B = metalness, as three.js reads them). Canvas rows run top-down; UV v runs bottom-up.
 * DOM access happens only inside functions.
 */
import { createNoise } from '../../core/noise.js';
import { clamp, createRng, lerp, smoothstep } from '../../core/math.js';

export function makeCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

/** Float paint buffers with region helpers and soft stamps. */
class Paint {
  constructor(w, h, seed) {
    this.w = w;
    this.h = h;
    this.alb = new Float32Array(w * h * 3);
    this.hgt = new Float32Array(w * h);
    this.rough = new Float32Array(w * h).fill(0.6);
    this.metal = new Float32Array(w * h);
    this.regions = [];
    this.noise = createNoise(seed);
    this.rng = createRng(seed * 7919 + 17);
  }

  /** UV rect (v up) → pixel rect. wrap: the region tiles in u (cylinders). nrm: normal strength. */
  region(u0, v0, u1, v1, wrap = false, nrm = 1) {
    const r = {
      x0: Math.round(u0 * this.w), x1: Math.round(u1 * this.w),
      y0: Math.round((1 - v1) * this.h), y1: Math.round((1 - v0) * this.h),
      wrap, nrm,
    };
    r.w = r.x1 - r.x0;
    r.h = r.y1 - r.y0;
    this.regions.push(r);
    return r;
  }

  /** Calls fn(i, u, v, x, y) for every pixel of r, u/v local in [0, 1) (v up). */
  each(r, fn) {
    for (let y = r.y0; y < r.y1; y++) {
      const v = 1 - (y - r.y0 + 0.5) / r.h;
      for (let x = r.x0; x < r.x1; x++) fn(y * this.w + x, (x - r.x0 + 0.5) / r.w, v, x, y);
    }
  }

  setAlb(i, r, g, b) {
    this.alb[i * 3] = r;
    this.alb[i * 3 + 1] = g;
    this.alb[i * 3 + 2] = b;
  }

  mixAlb(i, c, a) {
    const j = i * 3;
    this.alb[j] += (c[0] - this.alb[j]) * a;
    this.alb[j + 1] += (c[1] - this.alb[j + 1]) * a;
    this.alb[j + 2] += (c[2] - this.alb[j + 2]) * a;
  }

  /**
   * Soft capsule stamp from (x0, y0) to (x1, y1) in pixels, clipped to region r (wrapping in x
   * if r.wrap). fn(i, k) receives the coverage k ∈ (0, 1].
   */
  stroke(r, x0, y0, x1, y1, rad, fn) {
    const minX = Math.floor(Math.min(x0, x1) - rad - 1);
    const maxX = Math.ceil(Math.max(x0, x1) + rad + 1);
    const minY = Math.max(r.y0, Math.floor(Math.min(y0, y1) - rad - 1));
    const maxY = Math.min(r.y1 - 1, Math.ceil(Math.max(y0, y1) + rad + 1));
    const dx = x1 - x0;
    const dy = y1 - y0;
    const len2 = dx * dx + dy * dy || 1e-6;
    for (let y = minY; y <= maxY; y++) {
      for (let x = minX; x <= maxX; x++) {
        let px = x;
        if (px < r.x0 || px >= r.x1) {
          if (!r.wrap) continue;
          px = r.x0 + (((px - r.x0) % r.w) + r.w) % r.w;
        }
        const t = clamp(((x + 0.5 - x0) * dx + (y + 0.5 - y0) * dy) / len2, 0, 1);
        const ex = x + 0.5 - (x0 + dx * t);
        const ey = y + 0.5 - (y0 + dy * t);
        const d = Math.sqrt(ex * ex + ey * ey) / rad;
        if (d >= 1) continue;
        const k = (1 - d * d) * (1 - d * d);
        fn(y * this.w + px, k);
      }
    }
  }

  dot(r, x, y, rad, fn) {
    this.stroke(r, x, y, x, y, rad, fn);
  }

  /** Random point inside region r, in pixels. */
  rand(r) {
    return [r.x0 + this.rng.next() * r.w, r.y0 + this.rng.next() * r.h];
  }

  /** Bakes the buffers into { map, normal, orm } canvases. */
  bake(normalStrength = 2) {
    const { w, h } = this;
    const map = makeCanvas(w, h);
    const normal = makeCanvas(w, h);
    const orm = makeCanvas(w, h);
    const mImg = map.getContext('2d').createImageData(w, h);
    const nImg = normal.getContext('2d').createImageData(w, h);
    const oImg = orm.getContext('2d').createImageData(w, h);
    const md = mImg.data;
    const nd = nImg.data;
    const od = oImg.data;
    for (let i = 0, n = w * h; i < n; i++) {
      const j = i * 4;
      md[j] = clamp(this.alb[i * 3], 0, 1) * 255;
      md[j + 1] = clamp(this.alb[i * 3 + 1], 0, 1) * 255;
      md[j + 2] = clamp(this.alb[i * 3 + 2], 0, 1) * 255;
      md[j + 3] = 255;
      od[j] = 255;
      od[j + 1] = clamp(this.rough[i], 0.04, 1) * 255;
      od[j + 2] = clamp(this.metal[i], 0, 1) * 255;
      od[j + 3] = 255;
      nd[j] = 128;
      nd[j + 1] = 128;
      nd[j + 2] = 255;
      nd[j + 3] = 255;
    }
    // Sobel per region, so heights never bleed across region borders; wrapping regions wrap.
    const hg = this.hgt;
    for (const r of this.regions) {
      const s = normalStrength * r.nrm;
      for (let y = r.y0; y < r.y1; y++) {
        const ym = y > r.y0 ? y - 1 : y;
        const yp = y < r.y1 - 1 ? y + 1 : y;
        for (let x = r.x0; x < r.x1; x++) {
          let xm = x - 1;
          let xp = x + 1;
          if (xm < r.x0) xm = r.wrap ? r.x1 - 1 : x;
          if (xp >= r.x1) xp = r.wrap ? r.x0 : x;
          const gx = (hg[ym * w + xp] + 2 * hg[y * w + xp] + hg[yp * w + xp]) - (hg[ym * w + xm] + 2 * hg[y * w + xm] + hg[yp * w + xm]);
          const gy = (hg[yp * w + xm] + 2 * hg[yp * w + x] + hg[yp * w + xp]) - (hg[ym * w + xm] + 2 * hg[ym * w + x] + hg[ym * w + xp]);
          // n = (-dh/du, -dh/dv, 1); rows run down, so -dh/dv = +dh/drow.
          let nx = -gx * s * 0.125;
          let ny = gy * s * 0.125;
          const inv = 1 / Math.sqrt(nx * nx + ny * ny + 1);
          nx *= inv;
          ny *= inv;
          const j = (y * w + x) * 4;
          nd[j] = (nx * 0.5 + 0.5) * 255;
          nd[j + 1] = (ny * 0.5 + 0.5) * 255;
          nd[j + 2] = (inv * 0.5 + 0.5) * 255;
        }
      }
    }
    map.getContext('2d').putImageData(mImg, 0, 0);
    normal.getContext('2d').putImageData(nImg, 0, 0);
    orm.getContext('2d').putImageData(oImg, 0, 0);
    return { map, normal, orm };
  }
}

// sRGB palette (0..1)
const SKIN = [0.66, 0.46, 0.33];
const HAIR = [0.16, 0.11, 0.08];
const LEATHER = [0.37, 0.235, 0.14];
const CRIMSON = [0.50, 0.085, 0.07];
const BRONZE = [0.83, 0.60, 0.36];
const BRONZE_DARK = [0.42, 0.28, 0.15];
const PATINA = [0.30, 0.40, 0.30];
const IRON = [0.6, 0.6, 0.6];
const RUST = [0.45, 0.25, 0.12];
const ASH = [0.6, 0.45, 0.3]; // oiled ash

// ---------------------------------------------------------------------------------------------
// Arm atlas

/**
 * UV regions of the arm atlas (u0, v0, u1, v1). Arm tube: u around (dorsal at 0.75), v along
 * (wrist 0 → elbow 0.5 → shoulder 1). Hand: fingers v∈[0,0.25], palm v∈[0.25,0.5], dorsal at u 0.25.
 */
export const ARM_UV = {
  arm: [0, 0, 0.5, 1],
  fingers: [0.5, 0, 0.75, 0.25],
  palm: [0.5, 0.25, 0.75, 0.5],
  leather: [0.75, 0, 1, 0.5],
  cloth: [0.5, 0.5, 0.75, 0.75],
  bronze: [0.75, 0.5, 1, 0.75],
};

function skinBase(P, i, u, v, n1, n2, dors) {
  const tan = 1 - 0.09 * dors;
  const r = (SKIN[0] + 0.05 * n1 + 0.025 * Math.max(0, n2)) * tan + (1 - dors) * 0.025;
  const g = (SKIN[1] + 0.035 * n1 - 0.01 * Math.max(0, n2)) * tan + (1 - dors) * 0.012;
  const b = (SKIN[2] + 0.025 * n1 - 0.008 * Math.max(0, n2)) * tan + (1 - dors) * 0.012;
  P.setAlb(i, r, g, b);
}

export function paintArmAtlas(size, seed = 11) {
  const P = new Paint(size, size, seed);
  const N = P.noise;
  const px = size / 512; // pixel scale relative to the 512 design size
  const R = {};
  R.arm = P.region(...ARM_UV.arm, true, 0.9);
  R.fingers = P.region(...ARM_UV.fingers, true, 0.9);
  R.palm = P.region(...ARM_UV.palm, true, 0.9);
  R.leather = P.region(...ARM_UV.leather, true, 1.4);
  R.cloth = P.region(...ARM_UV.cloth, true, 1.0);
  R.bronze = P.region(...ARM_UV.bronze, true, 0.8);
  R.spare = P.region(0.5, 0.75, 1, 1, false, 0.5);

  // Arm skin: mottling, tan on the dorsal side, pores.
  P.each(R.arm, (i, u, v) => {
    const n1 = N.pfbm2(u * 4, v * 8, 4, 8, 3);
    const n2 = N.pnoise2(u * 24, v * 48, 24, 48);
    const dors = 0.5 + 0.5 * Math.cos(2 * Math.PI * (u - 0.75));
    skinBase(P, i, u, v, n1, n2, dors);
    P.hgt[i] = 0.25 * N.pnoise2(u * 90, v * 180, 90, 180) + 0.12 * (N.hash2(i, 7) - 0.5);
    P.rough[i] = 0.5 + 0.05 * n1 + 0.04 * dors;
    P.metal[i] = 0;
  });
  // Hair: thin strokes pointing toward the wrist, densest on the dorsal forearm.
  const hairs = Math.round(1300 * px * px);
  for (let k = 0; k < hairs; k++) {
    const [x, y] = P.rand(R.arm);
    const u = (x - R.arm.x0) / R.arm.w;
    const v = 1 - (y - R.arm.y0) / R.arm.h;
    const dors = 0.5 + 0.5 * Math.cos(2 * Math.PI * (u - 0.75));
    const fore = v < 0.5 ? smoothstep(0.02, 0.12, v) : 0.35;
    if (P.rng.next() > dors * dors * fore) continue;
    const len = (3 + P.rng.next() * 4) * px;
    const ang = Math.PI / 2 + (P.rng.next() - 0.5) * 0.7 + (u - 0.75) * 0.6;
    const a = 0.1 + 0.16 * P.rng.next();
    P.stroke(R.arm, x, y, x + Math.cos(ang) * len, y + Math.sin(ang) * len, 0.55 * px, (j, c) => {
      P.mixAlb(j, HAIR, a * c);
      P.hgt[j] += 0.15 * c;
    });
  }
  // Veins on the inner forearm and a pair of old scars on the outside.
  // Veins: a smooth wandering path, painted as a soft max-blended ridge so joints don't bead.
  const vein = new Float32Array(P.w * P.h);
  for (let k = 0; k < 3; k++) {
    let x = R.arm.x0 + R.arm.w * (0.16 + 0.2 * P.rng.next());
    let y = R.arm.y0 + R.arm.h * (0.53 + 0.1 * P.rng.next());
    let ang = Math.PI / 2 + (P.rng.next() - 0.5) * 0.4;
    for (let s = 0; s < 60; s++) {
      ang += (P.rng.next() - 0.5) * 0.35;
      ang = clamp(ang, Math.PI / 2 - 0.6, Math.PI / 2 + 0.6);
      const nx = x + Math.cos(ang) * 4 * px;
      const ny = y + Math.sin(ang) * 4 * px;
      if (ny > R.arm.y1 - 4) break;
      P.stroke(R.arm, x, y, nx, ny, 3.4 * px, (j, c) => {
        vein[j] = Math.max(vein[j], c);
      });
      x = nx;
      y = ny;
    }
  }
  P.each(R.arm, (i) => {
    const c = vein[i];
    if (c <= 0) return;
    P.alb[i * 3] -= 0.02 * c;
    P.alb[i * 3 + 2] += 0.014 * c;
    P.hgt[i] += 0.55 * c;
  });
  for (let k = 0; k < 3; k++) {
    const x = R.arm.x0 + R.arm.w * (0.62 + 0.25 * P.rng.next());
    const y = R.arm.y0 + R.arm.h * (0.55 + 0.35 * P.rng.next());
    const a = (P.rng.next() - 0.5) * 1.2;
    const l = (14 + P.rng.next() * 22) * px;
    P.stroke(R.arm, x, y, x + Math.cos(a) * l, y + Math.sin(a) * l, 2.2 * px, (j, c) => {
      P.mixAlb(j, [0.80, 0.60, 0.52], 0.55 * c);
      P.hgt[j] -= 0.8 * c;
      P.rough[j] -= 0.12 * c;
    });
  }
  // Dust.
  P.each(R.arm, (i, u, v) => {
    const d = smoothstep(0.35, 0.75, N.pfbm2(u * 3 + 7, v * 5, 3, 5, 3));
    if (d > 0) {
      P.mixAlb(i, [0.60, 0.50, 0.38], 0.22 * d);
      P.rough[i] += 0.15 * d;
    }
  });

  // Hand: fingers (knuckle creases, nails) and palm/back of the hand.
  P.each(R.fingers, (i, u, v) => {
    const n1 = N.pfbm2(u * 4, v * 6, 4, 6, 3);
    const n2 = N.pnoise2(u * 16, v * 24, 16, 24);
    const dors = 0.5 + 0.5 * Math.cos(2 * Math.PI * (u - 0.25));
    skinBase(P, i, u, v, n1, n2, dors);
    // Redder over the knuckles (joints at v ≈ 0, 0.45, 0.75 along the finger).
    const kn = Math.max(Math.exp(-(((v - 0.5) / 0.06) ** 2)), Math.exp(-(((v - 0.78) / 0.05) ** 2)), Math.exp(-((v / 0.08) ** 2)));
    P.alb[i * 3] += 0.05 * kn * dors;
    P.alb[i * 3 + 1] -= 0.02 * kn * dors;
    // Creases across the dorsal knuckles.
    const cr = Math.sin((v - 0.5) * 140) * Math.exp(-(((v - 0.5) / 0.05) ** 2)) + Math.sin((v - 0.78) * 160) * Math.exp(-(((v - 0.78) / 0.04) ** 2));
    P.hgt[i] = 0.22 * cr * dors + 0.2 * N.pnoise2(u * 70, v * 90, 70, 90);
    P.rough[i] = 0.5 + 0.05 * n1;
    // Nail on the dorsal side of the last phalanx.
    const nail = smoothstep(0.84, 0.88, v) * (1 - smoothstep(0.985, 1, v)) * smoothstep(0.62, 0.8, dors);
    if (nail > 0) {
      P.mixAlb(i, [0.80, 0.64, 0.57], 0.8 * nail);
      P.hgt[i] += 0.8 * nail;
      P.rough[i] = lerp(P.rough[i], 0.32, nail);
    }
  });
  P.each(R.palm, (i, u, v) => {
    const n1 = N.pfbm2(u * 4, v * 4, 4, 4, 3);
    const n2 = N.pnoise2(u * 20, v * 20, 20, 20);
    const dors = 0.5 + 0.5 * Math.cos(2 * Math.PI * (u - 0.25));
    skinBase(P, i, u, v, n1, n2, dors);
    // Tendon ridges on the back of the hand, knuckle redness near v = 0.
    const tend = Math.pow(Math.abs(Math.sin((u - 0.25) * Math.PI * 9)), 6) * dors * smoothstep(0.1, 0.5, v);
    P.hgt[i] = 0.4 * tend + 0.2 * N.pnoise2(u * 60, v * 60, 60, 60);
    P.alb[i * 3] += 0.04 * Math.exp(-((v / 0.12) ** 2)) * dors;
    P.rough[i] = 0.52 + 0.05 * n1;
  });
  for (let k = 0; k < Math.round(260 * px * px); k++) {
    const [x, y] = P.rand(R.palm);
    const u = (x - R.palm.x0) / R.palm.w;
    const dors = 0.5 + 0.5 * Math.cos(2 * Math.PI * (u - 0.25));
    if (P.rng.next() > dors * dors) continue;
    const l = (3 + P.rng.next() * 4) * px;
    const ang = Math.PI / 2 + (P.rng.next() - 0.5) * 0.8;
    P.stroke(R.palm, x, y, x + Math.cos(ang) * l, y + Math.sin(ang) * l, 0.55 * px, (j, c) => P.mixAlb(j, HAIR, 0.16 * c));
  }

  // Leather wrist wrap: a strap spiralling round the wrist, with raised edges and wear.
  P.each(R.leather, (i, u, v) => {
    const band = v * 6.5 + u;
    const f = band - Math.floor(band);
    const edge = Math.min(f, 1 - f);
    const n1 = N.pfbm2(u * 6, v * 12, 6, 12, 4);
    const grain = N.pnoise2(u * 64, v * 128, 64, 128);
    const wear = smoothstep(0.1, 0.6, n1) * smoothstep(0.08, 0.3, edge);
    const shade = 0.75 + 0.25 * smoothstep(0, 0.12, edge);
    P.setAlb(i,
      (LEATHER[0] + 0.05 * n1 + 0.07 * wear) * shade,
      (LEATHER[1] + 0.035 * n1 + 0.05 * wear) * shade,
      (LEATHER[2] + 0.02 * n1 + 0.03 * wear) * shade);
    // The strap is domed, and each turn overlaps the next (a step at f = 0).
    P.hgt[i] = 3.2 * Math.sqrt(Math.max(0, f * (1 - f))) + 1.2 * f + 0.25 * grain;
    P.rough[i] = 0.72 - 0.16 * wear + 0.05 * grain;
    P.metal[i] = 0;
  });

  // Crimson linen sleeve.
  P.each(R.cloth, (i, u, v) => {
    const n1 = N.pfbm2(u * 4, v * 4, 4, 4, 4);
    const wx = Math.sin(u * size * 0.25 * Math.PI * 2 * 0.25 / px);
    const wy = Math.sin(v * size * 0.25 * Math.PI * 2 * 0.25 / px);
    const weave = wx * wy;
    const fold = Math.sin(u * Math.PI * 2 * 3 + n1 * 2);
    const sh = 0.85 + 0.1 * fold + 0.05 * weave;
    P.setAlb(i, (CRIMSON[0] + 0.07 * n1) * sh, (CRIMSON[1] + 0.02 * n1) * sh, (CRIMSON[2] + 0.015 * n1) * sh);
    P.hgt[i] = 0.35 * weave + 1.5 * fold;
    P.rough[i] = 0.88;
    P.metal[i] = 0;
  });

  // Bronze arm ring (twisted).
  P.each(R.bronze, (i, u, v) => {
    const n1 = N.pfbm2(u * 4, v * 4, 4, 4, 4);
    const tw = Math.sin((u * 10 + v * 3) * Math.PI * 2);
    const tarn = smoothstep(-0.2, 0.5, n1) * (0.5 - 0.5 * tw);
    P.setAlb(i, lerp(BRONZE[0], BRONZE_DARK[0], tarn), lerp(BRONZE[1], BRONZE_DARK[1], tarn), lerp(BRONZE[2], BRONZE_DARK[2], tarn));
    P.hgt[i] = 1.4 * tw;
    P.rough[i] = 0.3 + 0.25 * tarn;
    P.metal[i] = 1;
  });
  P.each(R.spare, (i) => {
    P.setAlb(i, SKIN[0], SKIN[1], SKIN[2]);
    P.rough[i] = 0.55;
  });
  return P.bake(2.2);
}

// ---------------------------------------------------------------------------------------------
// Weapon atlas (w × 2w)

/** UV regions of the weapon atlas. Blade/spearhead: u across, v base→tip. Shaft: u around, v butt→head. */
export const WEAPON_UV = {
  blade: [0, 0, 0.5, 0.62],
  head: [0, 0.62, 0.5, 0.82],
  bronze: [0, 0.82, 0.5, 1],
  wood: [0.5, 0, 0.75, 1],
  cord: [0.75, 0, 1, 0.5],
  grip: [0.75, 0.5, 1, 1],
};

function ironPixel(P, i, u, v, n1, n2, fullerW, base) {
  const c = Math.abs(u - 0.5) * 2; // 0 at the spine, 1 at the edges
  const fuller = fullerW > 0 ? 1 - smoothstep(fullerW * 0.75, fullerW, c) : 0;
  const edge = smoothstep(0.78, 0.9, c);
  const shade = 1 + 0.06 * n1 - 0.22 * fuller + 0.14 * edge;
  P.setAlb(i, base[0] * shade * 1.01, base[1] * shade, base[2] * shade * 0.98);
  P.hgt[i] = 0.35 * n2;
  P.rough[i] = 0.3 + 0.06 * n1 + 0.1 * fuller - 0.06 * edge;
  P.metal[i] = 1;
}

export function paintWeaponAtlas(width, seed = 23) {
  const P = new Paint(width, width * 2, seed);
  const N = P.noise;
  const px = width / 512;
  const R = {};
  R.blade = P.region(...WEAPON_UV.blade, false, 0.6);
  R.head = P.region(...WEAPON_UV.head, false, 0.6);
  R.bronze = P.region(...WEAPON_UV.bronze, false, 0.7);
  R.wood = P.region(...WEAPON_UV.wood, true, 1.2);
  R.cord = P.region(...WEAPON_UV.cord, true, 1.6);
  R.grip = P.region(...WEAPON_UV.grip, true, 1.0);

  P.each(R.blade, (i, u, v) => {
    const n1 = N.pfbm2(u * 3, v * 10, 3, 10, 4);
    const n2 = N.pnoise2(u * 8, v * 60, 8, 60);
    ironPixel(P, i, u, v, n1, n2, v < 0.7 ? 0.3 * (1 - smoothstep(0.55, 0.7, v)) + 0.0001 : 0, IRON);
  });
  // Sharpening scratches across the edges, a few long scuffs, rust specks near the hilt.
  for (let k = 0; k < Math.round(900 * px * px); k++) {
    const [x, y] = P.rand(R.blade);
    const u = (x - R.blade.x0) / R.blade.w;
    const nearEdge = Math.abs(u - 0.5) > 0.3;
    const l = (nearEdge ? 4 + P.rng.next() * 8 : 8 + P.rng.next() * 30) * px;
    const ang = nearEdge ? (P.rng.next() - 0.5) * 0.5 : Math.PI / 2 + (P.rng.next() - 0.5) * 0.8;
    P.stroke(R.blade, x, y, x + Math.cos(ang) * l, y + Math.sin(ang) * l, 0.6 * px, (j, c) => {
      P.alb[j * 3] += 0.05 * c;
      P.alb[j * 3 + 1] += 0.05 * c;
      P.alb[j * 3 + 2] += 0.05 * c;
      P.rough[j] += 0.12 * c;
      P.hgt[j] -= 0.4 * c;
    });
  }
  for (let k = 0; k < Math.round(70 * px * px); k++) {
    const x = R.blade.x0 + R.blade.w * (0.2 + 0.6 * P.rng.next());
    const y = R.blade.y1 - R.blade.h * (P.rng.next() ** 2) * 0.35;
    P.dot(R.blade, x, y, (1 + P.rng.next() * 2.5) * px, (j, c) => {
      P.mixAlb(j, RUST, 0.7 * c);
      P.rough[j] = lerp(P.rough[j], 0.85, c);
      P.metal[j] = lerp(P.metal[j], 0.35, c);
      P.hgt[j] -= 0.5 * c;
    });
  }
  P.each(R.head, (i, u, v) => {
    const n1 = N.pfbm2(u * 3, v * 5, 3, 5, 4);
    const n2 = N.pnoise2(u * 8, v * 30, 8, 30);
    ironPixel(P, i, u, v, n1, n2, 0, [0.56, 0.565, 0.58]);
    const rib = 1 - smoothstep(0.02, 0.08, Math.abs(u - 0.5));
    P.alb[i * 3] += 0.05 * rib;
    P.alb[i * 3 + 1] += 0.05 * rib;
    P.alb[i * 3 + 2] += 0.05 * rib;
    const rust = smoothstep(0.45, 0.8, N.pfbm2(u * 5 + 3, v * 6, 5, 6, 3)) * (1 - v) * 0.8;
    P.mixAlb(i, RUST, rust * 0.6);
    P.rough[i] = lerp(P.rough[i], 0.8, rust);
  });
  P.each(R.bronze, (i, u, v) => {
    const n1 = N.pfbm2(u * 5, v * 5, 5, 5, 4);
    const tarn = smoothstep(0.0, 0.7, n1);
    const pat = smoothstep(0.5, 0.85, N.pfbm2(u * 9 + 5, v * 9, 9, 9, 3));
    P.setAlb(i, lerp(BRONZE[0], BRONZE_DARK[0], tarn * 0.7), lerp(BRONZE[1], BRONZE_DARK[1], tarn * 0.7), lerp(BRONZE[2], BRONZE_DARK[2], tarn * 0.7));
    P.mixAlb(i, PATINA, pat * 0.5);
    P.hgt[i] = 0.4 * N.pnoise2(u * 30, v * 30, 30, 30);
    P.rough[i] = 0.28 + 0.25 * tarn + 0.3 * pat;
    P.metal[i] = 1 - 0.6 * pat;
  });
  // Ash shaft: long straight grain, darker and smoother where the hand holds it (v ≈ 0.38).
  P.each(R.wood, (i, u, v) => {
    const wob = N.pfbm2(u * 2, v * 6, 2, 6, 3);
    const g = Math.sin((u * 24 + wob * 1.6) * Math.PI * 2);
    const fine = N.pnoise2(u * 40, v * 400, 40, 400);
    const line = Math.pow(0.5 + 0.5 * g, 6);
    const grime = Math.exp(-(((v - 0.52) / 0.07) ** 2));
    const sh = 1 - 0.1 * line - 0.05 * fine - 0.2 * grime;
    P.setAlb(i, ASH[0] * sh, ASH[1] * sh, ASH[2] * sh * 0.97);
    P.hgt[i] = -0.6 * line + 0.25 * fine;
    P.rough[i] = 0.62 - 0.18 * grime + 0.05 * fine;
    P.metal[i] = 0;
  });
  // Leather cord wrap (sword grip, spear grip band): helical cord with worn tops.
  P.each(R.cord, (i, u, v) => {
    const ph = v * 34 + u;
    const f = ph - Math.floor(ph);
    const dome = Math.sqrt(Math.max(0, f * (1 - f))) * 2;
    const n1 = N.pfbm2(u * 4, v * 12, 4, 12, 3);
    const sh = 0.55 + 0.45 * dome;
    P.setAlb(i, (0.27 + 0.04 * n1) * sh, (0.17 + 0.03 * n1) * sh, (0.10 + 0.02 * n1) * sh);
    P.hgt[i] = 2.2 * dome;
    P.rough[i] = 0.78 - 0.25 * dome * dome;
    P.metal[i] = 0;
  });
  // Dark polished horn/wood (spare: pommel cap insets).
  P.each(R.grip, (i, u, v) => {
    const n1 = N.pfbm2(u * 3, v * 9, 3, 9, 4);
    P.setAlb(i, 0.22 + 0.04 * n1, 0.15 + 0.03 * n1, 0.10 + 0.02 * n1);
    P.hgt[i] = 0.3 * n1;
    P.rough[i] = 0.42;
    P.metal[i] = 0;
  });
  return P.bake(2.0);
}

// ---------------------------------------------------------------------------------------------
// Aspis: bronze face (planar disc mapping) and wooden interior.

/** The aspis disc occupies a circle of this radius (in UV) around (0.5, 0.5). */
export const SHIELD_UV_R = 0.43;
/** Radial bands of the bronze texture, as a fraction of SHIELD_UV_R. */
export const SHIELD_BANDS = { face: 0.9, rim: 0.975 };

function bronzeFacePixel(P, N, i, u, v, r, th) {
  const n1 = N.fbm2(u * 6, v * 6, 4);
  const tarn = smoothstep(0.15, 0.85, n1) * 0.55;
  const burnish = Math.sin(r * 900 + N.noise2(u * 20, v * 20) * 2);
  P.setAlb(i, lerp(BRONZE[0], BRONZE_DARK[0], tarn), lerp(BRONZE[1], BRONZE_DARK[1], tarn), lerp(BRONZE[2], BRONZE_DARK[2], tarn));
  P.rough[i] = 0.2 + 0.18 * tarn + 0.03 * burnish;
  P.metal[i] = 1;
  P.hgt[i] = 0.12 * burnish;
}

/** Paints the aspis bronze: hammered face, guilloche rim, rolled edge, and a fallback fly blazon. */
export function paintShieldBronze(size, seed = 31) {
  const P = new Paint(size, size, seed);
  const N = P.noise;
  const px = size / 512;
  const all = P.region(0, 0, 1, 1, false, 1);
  const R0 = SHIELD_UV_R;
  P.each(all, (i, u, v) => {
    const dx = u - 0.5;
    const dy = v - 0.5;
    const r = Math.hypot(dx, dy) / R0;
    const th = Math.atan2(dy, dx);
    bronzeFacePixel(P, N, i, u, v, r, th);
    if (r > SHIELD_BANDS.face && r <= SHIELD_BANDS.rim) {
      // Guilloche: two interlaced cables around the rim, patina in the recesses.
      const w = (r - SHIELD_BANDS.face) / (SHIELD_BANDS.rim - SHIELD_BANDS.face);
      const a = th * 48;
      const c1 = Math.sin(a) * 0.32 + 0.5;
      const c2 = -Math.sin(a) * 0.32 + 0.5;
      const d = Math.min(Math.abs(w - c1), Math.abs(w - c2));
      const rope = Math.max(0, 1 - d / 0.17);
      const recess = 1 - rope;
      P.hgt[i] = 2.4 * Math.sqrt(rope) + 0.6 * smoothstep(0, 0.12, Math.min(w, 1 - w));
      P.mixAlb(i, BRONZE_DARK, 0.55 * recess);
      P.mixAlb(i, PATINA, 0.25 * recess * smoothstep(0.2, 0.7, N.noise2(u * 40, v * 40) * 0.5 + 0.5));
      P.rough[i] = 0.25 + 0.3 * recess;
    } else if (r > SHIELD_BANDS.rim) {
      // Rolled edge: worn bright, nicked.
      P.mixAlb(i, [0.9, 0.7, 0.45], 0.35);
      P.rough[i] = 0.24 + 0.1 * N.noise2(u * 80, v * 80);
      P.hgt[i] = 0.3 * N.noise2(u * 120, v * 120);
    }
  });
  // Planishing marks: shallow overlapping dimples over the whole face.
  const dimples = Math.round(5200 * px * px);
  for (let k = 0; k < dimples; k++) {
    const [x, y] = P.rand(all);
    const rad = (2.5 + P.rng.next() * 5) * px;
    P.dot(all, x, y, rad, (j, c) => {
      P.hgt[j] -= 0.5 * c;
    });
  }
  // Battle scars: dents and bright scratches.
  for (let k = 0; k < Math.round(260 * px); k++) {
    const [x, y] = P.rand(all);
    const a = P.rng.next() * Math.PI * 2;
    const l = (6 + P.rng.next() * 50) * px;
    P.stroke(all, x, y, x + Math.cos(a) * l, y + Math.sin(a) * l, 0.7 * px, (j, c) => {
      P.alb[j * 3] += 0.08 * c;
      P.alb[j * 3 + 1] += 0.06 * c;
      P.alb[j * 3 + 2] += 0.04 * c;
      P.rough[j] += 0.15 * c;
      P.hgt[j] -= 0.6 * c;
    });
  }
  for (let k = 0; k < 14; k++) {
    const [x, y] = P.rand(all);
    const rad = (8 + P.rng.next() * 16) * px;
    P.dot(all, x, y, rad, (j, c) => {
      P.hgt[j] -= 2.5 * c;
      P.mixAlb(j, BRONZE_DARK, 0.15 * c);
    });
  }
  paintFlyBlazon(P, all, size);
  return P.bake(1.6);
}

/** A fly painted in dark paint at the centre of the face, worn through in places. */
function paintFlyBlazon(P, r, size) {
  const N = P.noise;
  const cx = size / 2;
  const cy = size / 2;
  const s = size * SHIELD_UV_R * 0.62; // emblem half-height in px
  const paint = new Float32Array(size * size);
  const add = (j, c) => {
    paint[j] = Math.max(paint[j], c);
  };
  const ell = (x, y, rx, ry, rot) => {
    const steps = Math.max(2, Math.ceil(Math.max(rx, ry) / Math.max(1, Math.min(rx, ry)) * 3));
    const ca = Math.cos(rot);
    const sa = Math.sin(rot);
    const rr = Math.min(rx, ry);
    const L = Math.max(rx, ry) - rr;
    const ux = rx >= ry ? ca : -sa;
    const uy = rx >= ry ? sa : ca;
    P.stroke(r, x - ux * L, y - uy * L, x + ux * L, y + uy * L, rr * 1.15, (j, c) => add(j, Math.min(1, c * 2.2)));
    return steps;
  };
  // Head, thorax, abdomen (head toward the top of the shield = canvas -y).
  ell(cx, cy - s * 0.52, s * 0.15, s * 0.12, 0);
  ell(cx, cy - s * 0.22, s * 0.17, s * 0.2, 0);
  ell(cx, cy + s * 0.28, s * 0.2, s * 0.36, 0);
  // Wings: two long lobes swept back.
  for (const side of [-1, 1]) {
    const wx = cx + side * s * 0.36;
    const wy = cy + s * 0.12;
    ell(wx, wy, s * 0.16, s * 0.48, side * 0.42);
    // Legs: three per side, bent.
    for (let k = 0; k < 3; k++) {
      const by = cy - s * (0.32 - k * 0.12);
      const kx = cx + side * s * (0.38 + 0.06 * k);
      const ky = by + s * (k - 1) * 0.16;
      const fx = cx + side * s * (0.5 + 0.1 * k);
      const fy = ky + s * (k === 0 ? -0.25 : 0.32);
      P.stroke(r, cx + side * s * 0.12, by, kx, ky, s * 0.028, (j, c) => add(j, Math.min(1, c * 2)));
      P.stroke(r, kx, ky, fx, fy, s * 0.02, (j, c) => add(j, Math.min(1, c * 2)));
    }
  }
  // Eyes (lighter paint) are skipped; paint wear lets bronze through.
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const j = y * size + x;
      if (paint[j] <= 0) continue;
      const wear = smoothstep(0.35, 0.7, N.fbm2(x / size * 40, y / size * 40, 3) * 0.5 + 0.5);
      const a = paint[j] * (1 - 0.85 * wear);
      P.mixAlb(j, [0.07, 0.05, 0.04], a);
      P.rough[j] = lerp(P.rough[j], 0.62, a);
      P.metal[j] = lerp(P.metal[j], 0, a);
      P.hgt[j] += 0.6 * a;
    }
  }
}

/**
 * Composites an external blazon image (e.g. ctx.textures.get('blazonFly').image) over the face of
 * a baked bronze set: colour where it differs from bronze becomes paint (non-metal, rougher).
 */
export function compositeBlazon(set, image) {
  const map = set.map;
  const orm = set.orm;
  const size = map.width;
  const tmp = makeCanvas(size, size);
  const t = tmp.getContext('2d');
  const face = size * SHIELD_UV_R * SHIELD_BANDS.face * 2;
  t.save();
  t.beginPath();
  t.arc(size / 2, size / 2, face / 2, 0, Math.PI * 2);
  t.clip();
  t.drawImage(image, size / 2 - face / 2, size / 2 - face / 2, face, face);
  t.restore();
  const src = t.getImageData(0, 0, size, size).data;
  const mctx = map.getContext('2d');
  const octx = orm.getContext('2d');
  const mImg = mctx.getImageData(0, 0, size, size);
  const oImg = octx.getImageData(0, 0, size, size);
  const md = mImg.data;
  const od = oImg.data;
  for (let j = 0; j < md.length; j += 4) {
    const a = src[j + 3] / 255;
    if (a <= 0) continue;
    const r = src[j] / 255;
    const g = src[j + 1] / 255;
    const b = src[j + 2] / 255;
    // Bronze is a warm, saturated mid tone; anything far from it is paint.
    const mx = Math.max(r, g, b);
    const mn = Math.min(r, g, b);
    const sat = mx > 0 ? (mx - mn) / mx : 0;
    const warm = r >= g && g >= b ? 1 : 0;
    const bronzeLike = warm * smoothstep(0.25, 0.45, sat) * smoothstep(0.25, 0.45, mx);
    const paint = a * (1 - bronzeLike);
    md[j] = lerp(md[j], src[j], a);
    md[j + 1] = lerp(md[j + 1], src[j + 1], a);
    md[j + 2] = lerp(md[j + 2], src[j + 2], a);
    if (bronzeLike > 0.5) {
      // Their bronze: keep our metal, take their tone only.
      od[j + 2] = 255;
    } else {
      od[j + 1] = lerp(od[j + 1], 0.62 * 255, paint);
      od[j + 2] = lerp(od[j + 2], 0, paint);
    }
  }
  mctx.putImageData(mImg, 0, 0);
  octx.putImageData(oImg, 0, 0);
}

/** Paints the aspis interior: turned wooden core of vertical planks, worn rim back, cord corner. */
export function paintShieldInterior(size, seed = 37) {
  const P = new Paint(size, size, seed);
  const N = P.noise;
  const all = P.region(0, 0, 1, 1, false, 1);
  const R0 = SHIELD_UV_R;
  const planks = 9;
  P.each(all, (i, u, v) => {
    const dx = u - 0.5;
    const dy = v - 0.5;
    const r = Math.hypot(dx, dy) / R0;
    // Plank index across the shield (planks run vertically), each with its own tone.
    const pu = (dx / R0 + 1) * planks / 2;
    const pk = Math.floor(pu);
    const pf = pu - pk;
    const tone = (N.hash2(pk, 3) - 0.5) * 0.12;
    const wob = N.fbm2(u * 5 + pk * 3.1, v * 1.5, 3);
    const grain = Math.pow(0.5 + 0.5 * Math.sin((pf * 7 + wob * 2.2) * Math.PI * 2), 5);
    const fine = N.noise2(u * 300, v * 30);
    const seam = 1 - smoothstep(0, 0.035, Math.min(pf, 1 - pf));
    // Lathe-turned: faint concentric tool marks.
    const lathe = Math.sin(r * 420 + N.noise2(u * 9, v * 9) * 1.5);
    const grime = smoothstep(0.75, 0.2, r) * 0.25 + smoothstep(0.6, 1, N.fbm2(u * 4 + 9, v * 4, 3) * 0.5 + 0.5) * 0.3;
    let rC = 0.47 + tone - 0.08 * grain - 0.02 * fine;
    let gC = 0.31 + tone * 0.8 - 0.06 * grain - 0.015 * fine;
    let bC = 0.18 + tone * 0.5 - 0.035 * grain - 0.01 * fine;
    const dark = 1 - 0.3 * grime - 0.35 * seam;
    rC *= dark;
    gC *= dark;
    bC *= dark;
    let h = -0.7 * grain + 0.2 * fine + 0.15 * lathe - 1.5 * seam;
    let ro = 0.66 + 0.06 * grain - 0.1 * grime;
    if (r > SHIELD_BANDS.face) {
      // Rim back: flat, worn lighter where it rests on the shoulder.
      const wear = smoothstep(0.3, 0.8, N.fbm2(u * 12, v * 12, 3) * 0.5 + 0.5);
      rC = lerp(rC, 0.56, 0.35 + 0.3 * wear);
      gC = lerp(gC, 0.4, 0.35 + 0.3 * wear);
      bC = lerp(bC, 0.26, 0.35 + 0.3 * wear);
      h = 0.3 * fine;
      ro = 0.6 - 0.1 * wear;
    }
    P.setAlb(i, rC, gC, bC);
    P.hgt[i] = h;
    P.rough[i] = ro;
    P.metal[i] = 0;
  });
  // Corner square (u, v ∈ [0, 0.12]): twisted cord for the antilabe and the hanging cords.
  const cord = P.region(0, 0, 0.12, 0.12, true, 1.6);
  P.each(cord, (i, u, v) => {
    const ph = v * 9 + u * 2;
    const f = ph - Math.floor(ph);
    const dome = Math.sqrt(Math.max(0, f * (1 - f))) * 2;
    const n = N.noise2(u * 40, v * 40);
    const sh = 0.6 + 0.4 * dome;
    P.setAlb(i, (0.52 + 0.04 * n) * sh, (0.40 + 0.03 * n) * sh, (0.26 + 0.02 * n) * sh);
    P.hgt[i] = 2 * dome;
    P.rough[i] = 0.85;
  });
  return P.bake(1.8);
}

// ---------------------------------------------------------------------------------------------
// Small arrow atlas (shaft reed, fletching, bronze head), used with vertex UVs.

export const ARROW_UV = {
  shaft: [0, 0, 0.5, 1],
  feather: [0.5, 0, 1, 0.5],
  head: [0.5, 0.5, 1, 1],
};

export function paintArrowAtlas(size = 128, seed = 41) {
  const P = new Paint(size, size, seed);
  const N = P.noise;
  const R = {
    shaft: P.region(...ARROW_UV.shaft, true, 0.6),
    feather: P.region(...ARROW_UV.feather, false, 0.8),
    head: P.region(...ARROW_UV.head, false, 0.5),
  };
  P.each(R.shaft, (i, u, v) => {
    // Reed with nodes, plus binding near both ends.
    const node = Math.exp(-((((v * 4) % 1 - 0.5) / 0.04) ** 2));
    const bind = smoothstep(0.88, 0.9, v) + (1 - smoothstep(0.08, 0.1, v));
    const n = N.pnoise2(u * 4, v * 40, 4, 40);
    P.setAlb(i, 0.64 + 0.05 * n - 0.12 * node - 0.3 * bind, 0.55 + 0.04 * n - 0.1 * node - 0.3 * bind, 0.34 + 0.03 * n - 0.06 * node - 0.18 * bind);
    P.hgt[i] = node + 0.3 * n;
    P.rough[i] = 0.6;
  });
  P.each(R.feather, (i, u, v) => {
    // Barbs angled back from the quill; grey-brown with a dark tip band.
    const barb = Math.sin((u * 30 + v * 12) * Math.PI);
    const band = smoothstep(0.75, 0.8, u) * (1 - smoothstep(0.9, 0.95, u));
    const c = 0.6 + 0.06 * barb - 0.35 * band + 0.05 * N.noise2(u * 10, v * 10);
    P.setAlb(i, c * 0.95, c * 0.9, c * 0.82);
    P.hgt[i] = 0.4 * barb;
    P.rough[i] = 0.9;
  });
  P.each(R.head, (i, u, v) => {
    const n = N.noise2(u * 12, v * 12);
    P.setAlb(i, lerp(BRONZE[0], BRONZE_DARK[0], 0.5 + 0.3 * n), lerp(BRONZE[1], BRONZE_DARK[1], 0.5 + 0.3 * n), lerp(BRONZE[2], BRONZE_DARK[2], 0.5 + 0.3 * n));
    P.rough[i] = 0.45;
    P.metal[i] = 1;
  });
  return P.bake(1.2);
}
