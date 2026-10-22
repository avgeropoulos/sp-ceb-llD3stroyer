/**
 * Character atlases and shared materials.
 *
 * Every character uses at most two materials: group 0 (non-metal: skin, cloth, leather,
 * wood, wicker) maps a canvas atlas of tiles, group 1 (metal: bronze, iron, gold, silver,
 * scale, shield faces) maps a metal atlas plus a roughness/metalness atlas. Tiles are drawn
 * from ctx.textures.get(name) when it returns a texture, otherwise painted here. Materials
 * are shared by every kind; setBloodied picks one of four pre-built variants that share a
 * single program (only a uniform differs), so bleeding never compiles a shader.
 */
import * as THREE from 'three';
import { BONES } from './rig.js';

/** Non-metal tiles, 4 columns × 8 rows. */
export const CLOTH_TILES = [
  'skinG', 'skinG2', 'skinP', 'skinP2',
  'faceG', 'faceP', 'faceS', 'hair',
  'linen', 'crimson', 'leather', 'leatherDark',
  'wood', 'woodDark', 'wicker', 'felt',
  'robeA', 'robeB', 'robeC', 'quilted',
  'tunicRed', 'tunicBlue', 'tunicOchre', 'faceH',
  'trouserDiamond', 'trouserStripe', 'turban', 'feltDark',
  'straw', 'dogskin', 'wool', 'horsehair',
];
/** Metal tiles, 4 × 4. */
export const METAL_TILES = [
  'bronze', 'bronzeDark', 'iron', 'gold',
  'silver', 'scaleBronze', 'scaleIron', 'ironDark',
  'blazonFly', 'blazonGorgon', 'blazonLion', 'blazonBoar',
  'blazonClub', 'blazonScorpion', 'greave', 'helmet',
];
export const BLAZONS = ['blazonFly', 'blazonGorgon', 'blazonLion', 'blazonBoar', 'blazonClub', 'blazonScorpion'];

/** Head UV metrics shared by the head geometry and the face painter (head-local metres). */
export const FACE = {
  yBot: -0.082, yTop: 0.168,
  eyeY: 0.040, eyeT: 0.34, browY: 0.064, mouthY: -0.036, noseY: 0.0, earT: 1.5,
};
export const faceV = (y) => (y - FACE.yBot) / (FACE.yTop - FACE.yBot);
/** Around the head: u = 0.5 at the front, widened there so the face gets most of the tile. */
export const faceU = (t) => 0.5 + 0.5 * Math.sign(t) * Math.pow(Math.min(1, Math.abs(t) / Math.PI), 0.6);

const COLS_C = 4, ROWS_C = 8, COLS_M = 4, ROWS_M = 4;

/** UV rects [u0, v0, u1, v1] for a grid atlas with gutters. */
function rectsFor(names, cols, rows, T, gutter) {
  const W = cols * T, H = rows * T;
  const out = {};
  names.forEach((name, i) => {
    const c = i % cols, r = Math.floor(i / cols);
    const x0 = c * T, y0 = r * T;
    out[name] = [(x0 + gutter) / W, 1 - (y0 + T - gutter) / H, (x0 + T - gutter) / W, 1 - (y0 + gutter) / H];
  });
  return out;
}

/** Tile rects without needing any canvas (geometry can be built before textures exist). */
export function atlasRects(quality) {
  const T = quality === 'low' ? 128 : 256;
  const g = T >= 256 ? 4 : 2;
  return { cloth: rectsFor(CLOTH_TILES, COLS_C, ROWS_C, T, g), metal: rectsFor(METAL_TILES, COLS_M, ROWS_M, T, g), T, g };
}

// ---------------------------------------------------------------------------------------
// Small tileable value noise for the fallback painters.

function hash2(x, y, seed) {
  let h = (x * 374761393 + y * 668265263 + seed * 2246822519) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/** Tileable fbm in [0,1]: P×P samples, base period `cells`, `oct` octaves. */
function fbmTile(P, cells, oct, seed, gain = 0.5) {
  const out = new Float32Array(P * P);
  let amp = 1, norm = 0;
  for (let o = 0; o < oct; o++) {
    const C = cells << o;
    for (let y = 0; y < P; y++) {
      const fy = (y / P) * C;
      const iy = Math.floor(fy), ty = fy - iy;
      const sy = ty * ty * (3 - 2 * ty);
      const y0 = iy % C, y1 = (iy + 1) % C;
      for (let x = 0; x < P; x++) {
        const fx = (x / P) * C;
        const ix = Math.floor(fx), tx = fx - ix;
        const sx = tx * tx * (3 - 2 * tx);
        const x0 = ix % C, x1 = (ix + 1) % C;
        const a = hash2(x0, y0, seed + o), b = hash2(x1, y0, seed + o);
        const c = hash2(x0, y1, seed + o), d = hash2(x1, y1, seed + o);
        out[y * P + x] += amp * (a + (b - a) * sx + (c - a + (d - c - b + a) * sx) * sy);
      }
    }
    norm += amp;
    amp *= gain;
  }
  for (let i = 0; i < out.length; i++) out[i] /= norm;
  return out;
}

function makeCanvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

const hex = (c) => [(c >> 16) & 255, (c >> 8) & 255, c & 255];

/** Fills a P×P canvas with base colour modulated by noise (amp per channel). */
function noiseFill(cv, base, amp, cells, oct, seed, extra) {
  const P = cv.width;
  const g = cv.getContext('2d');
  const img = g.createImageData(P, P);
  const n = fbmTile(P, cells, oct, seed);
  const f = extra ? fbmTile(P, cells * 8, 2, seed + 77) : null;
  const [r0, g0, b0] = hex(base);
  for (let i = 0; i < P * P; i++) {
    let k = (n[i] - 0.5) * 2;
    if (f) k = k * 0.7 + (f[i] - 0.5) * 0.6;
    img.data[i * 4] = Math.max(0, Math.min(255, r0 + k * amp[0]));
    img.data[i * 4 + 1] = Math.max(0, Math.min(255, g0 + k * amp[1]));
    img.data[i * 4 + 2] = Math.max(0, Math.min(255, b0 + k * amp[2]));
    img.data[i * 4 + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  return g;
}

function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Draws an external texture image into a P×P canvas; returns false if it can't. */
function drawExternal(g, tex, P, reps = 1) {
  const img = tex && tex.image;
  if (!img) return false;
  try {
    if (!(img.width >= 8 && img.height >= 8)) return false;
    if (img.data && !(img instanceof HTMLCanvasElement)) {
      const src = makeCanvas(img.width, img.height);
      const d = src.getContext('2d').createImageData(img.width, img.height);
      if (img.data.length !== d.data.length) return false;
      d.data.set(img.data);
      src.getContext('2d').putImageData(d, 0, 0);
      return drawExternal(g, { image: src }, P, reps);
    }
    if (!(img.width >= 8 && img.height >= 8)) return false;
    const s = P / reps;
    for (let y = 0; y < reps; y++) for (let x = 0; x < reps; x++) g.drawImage(img, x * s, y * s, s, s);
    return true;
  } catch (err) {
    return false;
  }
}

function tint(g, P, color, op = 'multiply', alpha = 1) {
  g.save();
  g.globalCompositeOperation = op;
  g.globalAlpha = alpha;
  g.fillStyle = color;
  g.fillRect(0, 0, P, P);
  g.restore();
}

// ---------------------------------------------------------------------------------------
// Fallback painters (and decorators applied over shared textures). Each paints P×P, tileable.

const SKIN = { skinG: 0xb07a55, skinG2: 0x9c6744, skinP: 0xa47452, skinP2: 0x8d5f3f };

function paintSkin(g, P, base, seed) {
  noiseFill(g.canvas, base, [16, 12, 10], 4, 4, seed, true);
}

function paintCloth(g, P, base, seed, weave = 1) {
  noiseFill(g.canvas, base, [18, 16, 14], 3, 3, seed);
  // weave: fine crossing threads
  g.save();
  g.globalAlpha = 0.09 * weave;
  g.fillStyle = '#000';
  const step = Math.max(2, Math.round(P / 96));
  for (let y = 0; y < P; y += step * 2) g.fillRect(0, y, P, step * 0.6);
  g.globalAlpha = 0.06 * weave;
  for (let x = 0; x < P; x += step * 2) g.fillRect(x, 0, step * 0.6, P);
  g.restore();
  // soft vertical folds
  const r = rng(seed + 3);
  g.save();
  for (let i = 0; i < 7; i++) {
    const x = r() * P, w = (0.04 + r() * 0.08) * P;
    const grad = g.createLinearGradient(x - w, 0, x + w, 0);
    grad.addColorStop(0, 'rgba(0,0,0,0)');
    grad.addColorStop(0.5, `rgba(0,0,0,${0.08 + r() * 0.08})`);
    grad.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = grad;
    for (const dx of [-P, 0, P]) {
      g.save();
      g.translate(dx, 0);
      g.fillRect(x - w, 0, w * 2, P);
      g.restore();
    }
  }
  g.restore();
}

function paintLeather(g, P, base, seed) {
  noiseFill(g.canvas, base, [26, 18, 12], 6, 4, seed, true);
  const r = rng(seed);
  g.save();
  g.strokeStyle = 'rgba(30,15,5,0.25)';
  g.lineWidth = Math.max(1, P / 200);
  for (let i = 0; i < 14; i++) {
    const x = r() * P, y = r() * P;
    g.beginPath();
    g.moveTo(x, y);
    g.lineTo(x + (r() - 0.5) * P * 0.2, y + (r() - 0.5) * P * 0.1);
    g.stroke();
  }
  g.restore();
}

function paintWood(g, P, base, seed) {
  const img = g.createImageData(P, P);
  const n = fbmTile(P, 2, 3, seed);
  const [r0, g0, b0] = hex(base);
  for (let y = 0; y < P; y++) {
    for (let x = 0; x < P; x++) {
      const i = y * P + x;
      // grain runs along v (vertical), so shafts mapped with v along their length show it
      const k = Math.sin((x / P) * Math.PI * 2 * 9 + n[i] * 9) * 0.5 + (n[i] - 0.5);
      img.data[i * 4] = r0 + k * 22;
      img.data[i * 4 + 1] = g0 + k * 16;
      img.data[i * 4 + 2] = b0 + k * 10;
      img.data[i * 4 + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
}

function paintWicker(g, P, base, seed) {
  noiseFill(g.canvas, base, [14, 12, 8], 4, 2, seed);
  const rows = 16, cols = 8;
  const rh = P / rows, cw = P / cols;
  g.save();
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const x = c * cw + ((r % 2) * cw) / 2, y = r * rh;
      for (const dx of [0, -P]) {
        const grad = g.createLinearGradient(0, y, 0, y + rh);
        grad.addColorStop(0, 'rgba(255,240,200,0.20)');
        grad.addColorStop(0.5, 'rgba(255,240,200,0.05)');
        grad.addColorStop(1, 'rgba(30,18,6,0.5)');
        g.fillStyle = grad;
        g.beginPath();
        g.ellipse(x + dx + cw / 2, y + rh / 2, cw * 0.48, rh * 0.42, 0, 0, Math.PI * 2);
        g.fill();
      }
    }
  }
  // vertical stakes between the weave
  g.fillStyle = 'rgba(30,20,8,0.35)';
  for (let c = 0; c < cols; c++) g.fillRect(c * cw - 1, 0, 2, P);
  g.restore();
}

/**
 * Wear on a wicker shield face (over the shared texture or the painted fallback): weathered,
 * dirty patches, then a dark rawhide binding lashed round the rim. The tile maps the whole
 * face (gerron, spara), so its border is the shield's edge.
 */
function paintWickerWear(g, P, seed) {
  const r = rng(seed);
  g.save();
  for (let i = 0; i < 9; i++) {
    const x = r() * P, y = r() * P, rad = P * (0.08 + r() * 0.16);
    const grad = g.createRadialGradient(x, y, 0, x, y, rad);
    grad.addColorStop(0, `rgba(28,17,6,${(0.18 + r() * 0.16).toFixed(2)})`);
    grad.addColorStop(1, 'rgba(28,17,6,0)');
    g.fillStyle = grad;
    for (const dx of [-P, 0, P]) for (const dy of [-P, 0, P]) g.fillRect(x + dx - rad, y + dy - rad, rad * 2, rad * 2);
  }
  // rawhide binding: a dark band round the rim, a shadow inside it and the lashing across it
  const b = Math.max(2, Math.round(P * 0.04));
  g.fillStyle = 'rgba(20,12,5,0.35)';
  g.fillRect(b, b, P - 2 * b, Math.ceil(b * 0.5));
  g.fillRect(b, P - b - Math.ceil(b * 0.5), P - 2 * b, Math.ceil(b * 0.5));
  g.fillRect(b, b, Math.ceil(b * 0.5), P - 2 * b);
  g.fillRect(P - b - Math.ceil(b * 0.5), b, Math.ceil(b * 0.5), P - 2 * b);
  g.fillStyle = 'rgb(66,44,26)';
  g.fillRect(0, 0, P, b);
  g.fillRect(0, P - b, P, b);
  g.fillRect(0, 0, b, P);
  g.fillRect(P - b, 0, b, P);
  g.strokeStyle = 'rgba(120,92,60,0.55)';
  g.lineWidth = Math.max(1, b * 0.22);
  const n = 22;
  g.beginPath();
  for (let i = 0; i < n; i++) {
    const t = ((i + 0.5) / n) * P;
    g.moveTo(t - b * 0.35, 0);
    g.lineTo(t + b * 0.35, b);
    g.moveTo(t - b * 0.35, P - b);
    g.lineTo(t + b * 0.35, P);
    g.moveTo(0, t - b * 0.35);
    g.lineTo(b, t + b * 0.35);
    g.moveTo(P - b, t - b * 0.35);
    g.lineTo(P, t + b * 0.35);
  }
  g.stroke();
  g.restore();
}

function paintPattern(g, P, kind, seed) {
  if (kind === 'robeA') {
    // saffron robe with rosettes
    paintCloth(g, P, 0x9c6a2a, seed);
    g.save();
    const n = 4;
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const cx = ((x + (y % 2) * 0.5) / n) * P, cy = ((y + 0.5) / n) * P;
        for (const dx of [0, -P, P]) {
          g.fillStyle = 'rgba(235,225,190,0.85)';
          for (let k = 0; k < 8; k++) {
            const a = (k / 8) * Math.PI * 2;
            g.beginPath();
            g.ellipse(cx + dx + Math.cos(a) * P * 0.035, cy + Math.sin(a) * P * 0.035, P * 0.022, P * 0.012, a, 0, Math.PI * 2);
            g.fill();
          }
          g.fillStyle = 'rgba(120,30,20,0.9)';
          g.beginPath();
          g.arc(cx + dx, cy, P * 0.016, 0, Math.PI * 2);
          g.fill();
        }
      }
    }
    g.restore();
  } else if (kind === 'robeB') {
    // dark purple robe with stepped merlon bands
    paintCloth(g, P, 0x4a2c46, seed);
    g.save();
    for (const by of [0.12, 0.62]) {
      g.fillStyle = 'rgba(205,170,90,0.9)';
      g.fillRect(0, by * P, P, P * 0.05);
      const m = 8;
      for (let i = 0; i < m; i++) {
        const x = (i / m) * P, w = P / m;
        g.fillRect(x + w * 0.2, by * P - P * 0.04, w * 0.6, P * 0.04);
        g.fillRect(x + w * 0.35, by * P - P * 0.07, w * 0.3, P * 0.03);
      }
    }
    g.restore();
  } else if (kind === 'robeC') {
    // striped robe
    paintCloth(g, P, 0x6b7a8c, seed);
    g.save();
    const n = 8;
    for (let i = 0; i < n; i++) {
      g.fillStyle = i % 2 ? 'rgba(160,40,30,0.55)' : 'rgba(225,210,170,0.45)';
      g.fillRect((i / n) * P, 0, P / n / 3, P);
    }
    g.restore();
  } else if (kind === 'trouserDiamond') {
    paintCloth(g, P, 0x6a3b2a, seed);
    g.save();
    g.strokeStyle = 'rgba(220,190,120,0.55)';
    g.lineWidth = Math.max(1, P / 90);
    const n = 6;
    for (let i = -n; i <= n * 2; i++) {
      g.beginPath();
      g.moveTo((i / n) * P, 0);
      g.lineTo((i / n) * P + P, P);
      g.stroke();
      g.beginPath();
      g.moveTo((i / n) * P, 0);
      g.lineTo((i / n) * P - P, P);
      g.stroke();
    }
    g.restore();
  } else if (kind === 'trouserStripe') {
    paintCloth(g, P, 0x2f3c55, seed);
    g.save();
    const n = 6;
    for (let i = 0; i < n; i++) {
      g.fillStyle = 'rgba(200,170,90,0.45)';
      g.fillRect((i / n) * P, 0, P / n / 4, P);
    }
    g.restore();
  } else if (kind === 'quilted') {
    // quilted linen: puffed panels between stitched seams (vertical and horizontal)
    paintCloth(g, P, 0xb09c74, seed, 0.6);
    g.save();
    const nx = 6, ny = 8, cw = P / nx, ch = P / ny;
    for (let y = 0; y < ny; y++) {
      for (let x = 0; x < nx; x++) {
        const cx = (x + 0.5) * cw, cy = (y + 0.5) * ch;
        const grad = g.createRadialGradient(cx - cw * 0.12, cy - ch * 0.15, 0, cx, cy, Math.max(cw, ch) * 0.62);
        grad.addColorStop(0, 'rgba(255,250,230,0.22)');
        grad.addColorStop(0.6, 'rgba(255,250,230,0.0)');
        grad.addColorStop(1, 'rgba(70,50,30,0.32)');
        g.fillStyle = grad;
        g.fillRect(x * cw, y * ch, cw, ch);
      }
    }
    g.strokeStyle = 'rgba(70,50,30,0.55)';
    g.lineWidth = Math.max(1, P / 200);
    g.setLineDash([Math.max(2, P / 90), Math.max(1, P / 160)]);
    for (let i = 0; i <= nx; i++) {
      g.beginPath();
      g.moveTo(i * cw, 0);
      g.lineTo(i * cw, P);
      g.stroke();
    }
    for (let i = 0; i <= ny; i++) {
      g.beginPath();
      g.moveTo(0, i * ch);
      g.lineTo(P, i * ch);
      g.stroke();
    }
    g.restore();
  } else if (kind === 'turban') {
    paintCloth(g, P, 0xd8ccb0, seed, 0.6);
    g.save();
    g.strokeStyle = 'rgba(90,60,40,0.35)';
    g.lineWidth = Math.max(1, P / 64);
    for (let i = -8; i < 16; i++) {
      g.beginPath();
      g.moveTo((i / 8) * P, 0);
      g.lineTo((i / 8) * P + P * 0.5, P);
      g.stroke();
    }
    g.restore();
  }
}

function paintHair(g, P, base, seed) {
  noiseFill(g.canvas, base, [12, 9, 7], 8, 3, seed, true);
  const r = rng(seed);
  g.save();
  // dense small curls: dark troughs and faint lit crests
  const n = Math.round((P * P) / 90);
  for (let i = 0; i < n; i++) {
    const x = r() * P, y = r() * P, rad = P * (0.004 + r() * 0.006);
    const dark = r() < 0.6;
    g.strokeStyle = dark ? `rgba(0,0,0,${0.25 + r() * 0.25})` : `rgba(255,225,190,${0.05 + r() * 0.08})`;
    g.lineWidth = Math.max(1, P / 300) * (dark ? 1.4 : 1);
    const a = r() * 6.28;
    g.beginPath();
    g.arc(x, y, rad, a, a + 2.2 + r() * 1.5);
    g.stroke();
  }
  g.restore();
}

/**
 * Painted face over a skin tile, in the head's mapping (u = faceU(θ), v = faceV(y)): form
 * shading, almond eyes with lids, bushy brows, nose and mouth, hairline, beard and moustache.
 */
function paintFace(g, P, kind) {
  const TAU = Math.PI * 2;
  const X = (t) => faceU(t) * P;
  const Y = (y) => (1 - faceV(y)) * P;
  const PY = P / (FACE.yTop - FACE.yBot);
  const helmet = kind === 'faceH';
  const persian = kind === 'faceP' || kind === 'faceS';
  const hair = kind === 'faceS' ? [58, 42, 28] : persian ? [24, 17, 12] : [44, 30, 20];
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
  const r = rng(kind.length * 977 + 3);
  const path = (pts, close) => {
    g.beginPath();
    pts.forEach(([t, y], i) => (i ? g.lineTo(X(t), Y(y)) : g.moveTo(X(t), Y(y))));
    if (close) g.closePath();
  };
  const blob = (t, y, rt, ry, col, a) => {
    const cx = X(t), cy = Y(y);
    const rx = Math.max(1, (Math.abs(X(t + rt) - cx) + Math.abs(X(t - rt) - cx)) / 2), rr = Math.max(1, ry * PY);
    g.save();
    g.translate(cx, cy);
    g.scale(rx / rr, 1);
    const gr = g.createRadialGradient(0, 0, 0, 0, 0, rr);
    gr.addColorStop(0, rgba(col, a));
    gr.addColorStop(1, rgba(col, 0));
    g.fillStyle = gr;
    g.fillRect(-rr, -rr, rr * 2, rr * 2);
    g.restore();
  };
  if (helmet) tint(g, P, 'rgb(84,68,60)');
  g.save();
  // form: lit forehead, cheekbones and nose bridge; shaded sockets, temples and cheek hollows
  blob(0, 0.09, 0.55, 0.035, [255, 226, 196], 0.13);
  blob(0, 0.022, 0.09, 0.022, [255, 226, 196], 0.12);
  for (const s of [-1, 1]) {
    blob(s * 0.36, 0.045, 0.24, 0.022, [72, 34, 24], 0.5);
    blob(s * 0.5, 0.008, 0.22, 0.016, [255, 218, 188], 0.12);
    blob(s * 0.85, 0.02, 0.4, 0.06, [92, 46, 32], 0.28);
    blob(s * 0.6, -0.03, 0.22, 0.028, [86, 42, 28], 0.2);
  }
  // eyes: almond whites, brown irises under the upper lid, dark lid line, crease, lower lid
  for (const s of [-1, 1]) {
    const y = FACE.eyeY;
    const inner = s * 0.2, outer = s * 0.5;
    const top = [], bot = [];
    for (let i = 0; i <= 10; i++) {
      const f = i / 10, t = inner + (outer - inner) * f, sh = Math.sin(f * Math.PI);
      top.push([t, y + 0.0058 * Math.pow(sh, 0.7) - 0.0012 * f]);
      bot.push([t, y - 0.0038 * Math.pow(sh, 0.9) - 0.0008 * f]);
    }
    const outline = top.concat(bot.slice().reverse());
    path(outline, true);
    g.fillStyle = helmet ? '#6e655b' : '#cbbca6';
    g.fill();
    g.save();
    path(outline, true);
    g.clip();
    const ic = s * 0.335;
    const irx = Math.abs(X(ic + 0.07) - X(ic)), iry = 0.0062 * PY;
    g.fillStyle = helmet ? '#21140c' : '#3b2415';
    g.beginPath();
    g.ellipse(X(ic), Y(y + 0.0006), irx, iry, 0, 0, TAU);
    g.fill();
    g.fillStyle = '#0b0705';
    g.beginPath();
    g.ellipse(X(ic), Y(y + 0.0006), irx * 0.45, iry * 0.45, 0, 0, TAU);
    g.fill();
    // the upper lid's shadow across the eyeball
    path(top);
    g.strokeStyle = 'rgba(30,14,8,0.4)';
    g.lineWidth = 0.006 * PY;
    g.stroke();
    g.restore();
    if (!helmet) {
      g.fillStyle = 'rgba(255,250,240,0.7)';
      g.beginPath();
      g.arc(X(ic) - s * irx * 0.3, Y(y + 0.002), Math.max(0.8, iry * 0.25), 0, TAU);
      g.fill();
    }
    path(top);
    g.strokeStyle = persian ? 'rgba(10,6,4,0.95)' : 'rgba(34,18,10,0.85)';
    g.lineWidth = Math.max(1, (persian ? 0.0024 : 0.0017) * PY);
    g.stroke();
    if (persian) {
      // kohl line out past the outer corner
      g.beginPath();
      g.moveTo(X(outer), Y(y - 0.0012));
      g.lineTo(X(outer + s * 0.08), Y(y + 0.0015));
      g.stroke();
    }
    path(bot);
    g.strokeStyle = 'rgba(70,34,22,0.45)';
    g.lineWidth = Math.max(1, 0.0012 * PY);
    g.stroke();
    path(top.map(([t, yy]) => [t, yy + 0.006]));
    g.strokeStyle = 'rgba(70,34,22,0.35)';
    g.lineWidth = Math.max(1, 0.0016 * PY);
    g.stroke();
    // brow: short hairs along an arch, thick at the inner end
    for (let i = 0; i < 46; i++) {
      const f = i / 45;
      const t = s * (0.12 + 0.52 * f + (r() - 0.5) * 0.02);
      const yb = 0.06 + 0.0095 * Math.pow(Math.sin(Math.min(1, f * 1.15) * Math.PI * 0.5 + f * 0.6), 0.8) - 0.006 * f;
      const w = (1 - 0.55 * f) * (persian ? 1.25 : 1);
      g.strokeStyle = rgba(hair, 0.55 + r() * 0.35);
      g.lineWidth = Math.max(1, 0.0011 * PY * (0.8 + r() * 0.6));
      g.beginPath();
      g.moveTo(X(t), Y(yb - 0.0035 * w + (r() - 0.5) * 0.002));
      g.lineTo(X(t + s * (0.035 + r() * 0.02)), Y(yb + 0.0035 * w + (r() - 0.5) * 0.002));
      g.stroke();
    }
  }
  // nose: nostril shadows, wings, a lit tip
  for (const s of [-1, 1]) {
    blob(s * 0.16, -0.005, 0.06, 0.006, [60, 26, 18], 0.55);
    blob(s * 0.22, 0.004, 0.07, 0.012, [90, 44, 30], 0.25);
    path([[s * 0.21, -0.002], [s * 0.27, -0.022], [s * 0.28, -0.038]]);
    g.strokeStyle = 'rgba(84,40,26,0.28)';
    g.lineWidth = 0.004 * PY;
    g.stroke();
  }
  blob(0, 0.006, 0.05, 0.008, [255, 228, 200], 0.16);
  // mouth: upper lip in shade, fuller lower lip with a highlight
  const my = FACE.mouthY, mw = 0.24;
  const lip = (y0, h, col) => {
    const pts = [];
    for (let i = 0; i <= 10; i++) {
      const f = i / 10, t = (f * 2 - 1) * mw;
      pts.push([t, y0 + h * Math.pow(1 - Math.abs(f * 2 - 1), 0.6)]);
    }
    path(pts.concat([[mw, y0], [-mw, y0]]), true);
    g.fillStyle = col;
    g.fill();
  };
  lip(my, 0.0058, 'rgba(112,52,40,0.85)');
  lip(my, -0.0075, 'rgba(146,72,58,0.8)');
  blob(0, my - 0.004, 0.08, 0.0025, [255, 220, 200], 0.22);
  path([[-mw, my], [-mw * 0.5, my + 0.0008], [0, my - 0.0005], [mw * 0.5, my + 0.0008], [mw, my]]);
  g.strokeStyle = 'rgba(40,16,10,0.85)';
  g.lineWidth = Math.max(1, 0.0014 * PY);
  g.stroke();
  // hair: scalp above a soft hairline, temples, sideburns
  const hl = [[-Math.PI, 0.03], [-1.6, 0.03], [-1.42, 0.07], [-1.05, 0.085], [-0.62, 0.104], [-0.3, 0.112], [0, 0.114],
    [0.3, 0.112], [0.62, 0.104], [1.05, 0.085], [1.42, 0.07], [1.6, 0.03], [Math.PI, 0.03], [Math.PI, 0.2], [-Math.PI, 0.2]];
  path(hl, true);
  g.fillStyle = rgba(hair, 1);
  g.fill();
  path(hl.slice(0, 13));
  g.strokeStyle = rgba(hair, 0.35);
  g.lineWidth = 0.008 * PY;
  g.stroke();
  // beard: cheeks, jaw and chin, with a soft stubble edge; moustache over the upper lip
  const beard = [[-1.6, 0.03], [-1.25, 0.012], [-0.85, 0.0], [-0.55, -0.012], [-0.38, -0.024], [-0.3, -0.042],
    [-0.12, -0.05], [0, -0.052], [0.12, -0.05], [0.3, -0.042], [0.38, -0.024], [0.55, -0.012], [0.85, 0.0],
    [1.25, 0.012], [1.6, 0.03], [1.6, -0.2], [-1.6, -0.2]];
  path(beard.slice(0, 15));
  g.strokeStyle = rgba(hair, 0.3);
  g.lineWidth = 0.014 * PY;
  g.stroke();
  path(beard, true);
  g.fillStyle = rgba(hair, persian ? 1 : 0.95);
  g.fill();
  for (const s of [-1, 1]) {
    for (let i = 0; i < 26; i++) {
      const f = i / 25;
      const t0 = s * (0.03 + 0.26 * f);
      g.strokeStyle = rgba(hair, 0.7 + r() * 0.3);
      g.lineWidth = Math.max(1, 0.0016 * PY);
      g.beginPath();
      g.moveTo(X(t0), Y(my + 0.012 - 0.002 * f));
      g.quadraticCurveTo(X(t0 + s * 0.05), Y(my + 0.006), X(t0 + s * (0.08 + 0.04 * f)), Y(my - 0.004 - 0.012 * f));
      g.stroke();
    }
  }
  // the lower lip shows through the beard; the chin tuft below it
  lip(my - 0.0005, -0.007, 'rgba(140,68,54,0.9)');
  for (let i = 0; i < 260; i++) {
    const t = (r() - 0.5) * 3.0, y = -0.02 - r() * 0.07;
    if (Math.abs(t) < mw * 1.1 && y > my - 0.012) continue;
    g.strokeStyle = r() < 0.6 ? 'rgba(0,0,0,0.35)' : 'rgba(255,225,190,0.08)';
    g.lineWidth = Math.max(1, 0.0012 * PY);
    const a = r() * 6.28;
    g.beginPath();
    g.arc(X(t), Y(y), 0.0035 * PY, a, a + 2.4);
    g.stroke();
  }
  g.restore();
}

/** Straight strands along v (horsehair crests, bows). */
function paintStrands(g, P, base, seed) {
  noiseFill(g.canvas, base, [20, 10, 8], 3, 3, seed);
  const r = rng(seed);
  g.save();
  for (let i = 0; i < 420; i++) {
    const x = r() * P, w = Math.max(1, P / 256) * (0.6 + r());
    g.fillStyle = r() < 0.55 ? `rgba(15,4,2,${0.25 + r() * 0.3})` : `rgba(255,190,150,${0.06 + r() * 0.1})`;
    g.fillRect(x, 0, w, P);
  }
  g.restore();
}

function paintLinen(g, P) {
  // linothorax: white linen with a painted meander band near the top and a border at the waist
  g.save();
  const band = (y, h, col) => {
    g.fillStyle = col;
    g.fillRect(0, y, P, h);
  };
  band(P * 0.05, P * 0.045, 'rgba(120,30,25,0.9)');
  band(P * 0.115, P * 0.012, 'rgba(40,40,70,0.8)');
  band(P * 0.86, P * 0.05, 'rgba(120,30,25,0.9)');
  g.strokeStyle = 'rgba(235,225,200,0.95)';
  g.lineWidth = Math.max(1, P / 220);
  const m = 12;
  for (let i = 0; i < m; i++) {
    const x = (i / m) * P, w = P / m, y = P * 0.058, h = P * 0.03;
    g.beginPath();
    g.moveTo(x, y + h);
    g.lineTo(x, y);
    g.lineTo(x + w * 0.75, y);
    g.lineTo(x + w * 0.75, y + h * 0.66);
    g.lineTo(x + w * 0.33, y + h * 0.66);
    g.lineTo(x + w * 0.33, y + h * 0.33);
    g.stroke();
  }
  g.restore();
}

function paintStraw(g, P, seed) {
  noiseFill(g.canvas, 0xb59a5e, [30, 26, 16], 8, 3, seed, true);
  const r = rng(seed);
  g.save();
  g.lineWidth = Math.max(1, P / 200);
  for (let i = 0; i < 300; i++) {
    const x = r() * P, y = r() * P;
    g.strokeStyle = r() < 0.5 ? 'rgba(60,40,10,0.35)' : 'rgba(255,240,180,0.25)';
    g.beginPath();
    g.moveTo(x, y);
    g.lineTo(x + (r() - 0.5) * P * 0.05, y + P * 0.06);
    g.stroke();
  }
  g.restore();
}

// --- metal painters ---

function paintMetal(g, P, base, seed, dents = true) {
  noiseFill(g.canvas, base, [24, 20, 14], 3, 4, seed, true);
  if (!dents) return;
  const r = rng(seed + 9);
  g.save();
  for (let i = 0; i < 90; i++) {
    const x = r() * P, y = r() * P, s = P * (0.008 + r() * 0.02);
    const grad = g.createRadialGradient(x - s * 0.3, y - s * 0.3, 0, x, y, s);
    grad.addColorStop(0, 'rgba(255,240,200,0.10)');
    grad.addColorStop(1, 'rgba(0,0,0,0.08)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(x, y, s, 0, Math.PI * 2);
    g.fill();
  }
  g.restore();
}

function paintScale(g, P, base, seed) {
  noiseFill(g.canvas, 0x2a2018, [10, 8, 6], 4, 2, seed);
  const [r0, g0, b0] = hex(base);
  const rows = 12, cols = 10;
  const w = P / cols, h = P / rows;
  const r = rng(seed);
  g.save();
  for (let row = rows; row >= -1; row--) {
    for (let c = -1; c <= cols; c++) {
      const x = c * w + ((row % 2) * w) / 2, y = row * h;
      const k = (r() - 0.5) * 30;
      const grad = g.createLinearGradient(0, y - h * 0.4, 0, y + h * 1.2);
      grad.addColorStop(0, `rgb(${r0 + 30 + k},${g0 + 25 + k},${b0 + 15 + k})`);
      grad.addColorStop(1, `rgb(${r0 - 40 + k},${g0 - 35 + k},${b0 - 25 + k})`);
      g.fillStyle = grad;
      g.beginPath();
      g.moveTo(x + w * 0.04, y - h * 0.3);
      g.lineTo(x + w * 0.96, y - h * 0.3);
      g.lineTo(x + w * 0.96, y + h * 0.75);
      g.quadraticCurveTo(x + w * 0.5, y + h * 1.45, x + w * 0.04, y + h * 0.75);
      g.closePath();
      g.fill();
    }
  }
  g.restore();
}

/** Simple painted emblems on a bronze disc, used when textures.js has no blazon. */
function paintBlazon(g, P, name, seed) {
  paintMetal(g, P, 0xb88a42, seed);
  blazonShapes(g, P, name);
}

/** The painted emblem alone (dark paint on whatever is below). */
function blazonShapes(g, P, name) {
  const c = P / 2;
  g.save();
  g.translate(c, c);
  g.fillStyle = '#1b1210';
  g.strokeStyle = '#1b1210';
  const s = P / 256;
  g.scale(s, s);
  g.lineCap = 'round';
  g.lineJoin = 'round';
  if (name === 'blazonFly') {
    g.beginPath();
    g.ellipse(0, 18, 12, 30, 0, 0, Math.PI * 2);
    g.fill();
    g.beginPath();
    g.arc(0, -20, 12, 0, Math.PI * 2);
    g.fill();
    g.fillStyle = 'rgba(27,18,16,0.85)';
    for (const sx of [-1, 1]) {
      g.beginPath();
      g.ellipse(sx * 30, 0, 30, 11, sx * -0.5, 0, Math.PI * 2);
      g.fill();
      g.lineWidth = 3;
      for (const k of [-1, 0, 1]) {
        g.beginPath();
        g.moveTo(sx * 8, 8 + k * 12);
        g.lineTo(sx * 30, 14 + k * 22);
        g.stroke();
      }
    }
  } else if (name === 'blazonGorgon') {
    g.fillStyle = '#8a2a1c';
    g.beginPath();
    g.arc(0, 0, 44, 0, Math.PI * 2);
    g.fill();
    g.fillStyle = '#1b1210';
    g.lineWidth = 6;
    for (let k = 0; k < 12; k++) {
      const a = (k / 12) * Math.PI * 2;
      g.beginPath();
      g.moveTo(Math.cos(a) * 44, Math.sin(a) * 44);
      g.quadraticCurveTo(Math.cos(a + 0.3) * 62, Math.sin(a + 0.3) * 62, Math.cos(a) * 72, Math.sin(a) * 72);
      g.stroke();
    }
    for (const sx of [-1, 1]) {
      g.beginPath();
      g.arc(sx * 16, -10, 7, 0, Math.PI * 2);
      g.fill();
    }
    g.fillRect(-18, 16, 36, 8);
    g.fillStyle = '#e8dcc0';
    for (let k = -2; k <= 2; k++) g.fillRect(k * 7 - 2, 16, 4, 8);
  } else if (name === 'blazonLion') {
    g.beginPath();
    g.moveTo(-70, 30);
    g.quadraticCurveTo(-60, -10, -20, -12);
    g.lineTo(30, -14);
    g.quadraticCurveTo(48, -40, 66, -24);
    g.quadraticCurveTo(76, -6, 60, 4);
    g.lineTo(54, 34);
    g.lineTo(42, 34);
    g.lineTo(40, 10);
    g.lineTo(-30, 12);
    g.lineTo(-40, 36);
    g.lineTo(-52, 36);
    g.lineTo(-50, 14);
    g.quadraticCurveTo(-74, 0, -86, -30);
    g.lineTo(-80, -32);
    g.quadraticCurveTo(-72, -8, -60, 4);
    g.closePath();
    g.fill();
  } else if (name === 'blazonBoar') {
    g.beginPath();
    g.ellipse(-6, 0, 58, 28, 0, 0, Math.PI * 2);
    g.fill();
    g.beginPath();
    g.moveTo(40, -14);
    g.lineTo(76, 4);
    g.lineTo(42, 18);
    g.fill();
    g.fillRect(-46, 18, 10, 26);
    g.fillRect(24, 18, 10, 26);
    g.lineWidth = 4;
    for (let k = -4; k <= 3; k++) {
      g.beginPath();
      g.moveTo(k * 12, -26);
      g.lineTo(k * 12 + 4, -40);
      g.stroke();
    }
    g.strokeStyle = '#e8dcc0';
    g.beginPath();
    g.moveTo(58, 8);
    g.quadraticCurveTo(66, -4, 60, -12);
    g.stroke();
  } else if (name === 'blazonClub') {
    g.rotate(-0.6);
    g.beginPath();
    g.moveTo(-8, 80);
    g.lineTo(8, 80);
    g.lineTo(20, -60);
    g.quadraticCurveTo(0, -84, -20, -60);
    g.closePath();
    g.fill();
    for (let k = 0; k < 6; k++) {
      g.beginPath();
      g.arc((k % 2 ? 1 : -1) * 16, -50 + k * 18, 6, 0, Math.PI * 2);
      g.fill();
    }
  } else {
    // scorpion
    g.beginPath();
    g.ellipse(0, 10, 18, 34, 0, 0, Math.PI * 2);
    g.fill();
    g.lineWidth = 8;
    g.beginPath();
    g.moveTo(0, 40);
    g.quadraticCurveTo(10, 80, -16, 86);
    g.quadraticCurveTo(-40, 70, -30, 40);
    g.stroke();
    g.lineWidth = 5;
    for (const sx of [-1, 1]) {
      for (let k = 0; k < 4; k++) {
        g.beginPath();
        g.moveTo(sx * 14, k * 12);
        g.lineTo(sx * 40, k * 14 + 8);
        g.lineTo(sx * 46, k * 16 + 20);
        g.stroke();
      }
      g.beginPath();
      g.moveTo(sx * 10, -22);
      g.quadraticCurveTo(sx * 30, -52, sx * 34, -60);
      g.stroke();
      g.beginPath();
      g.ellipse(sx * 34, -66, 9, 14, sx * 0.4, 0, Math.PI * 2);
      g.fill();
    }
  }
  g.restore();
}

/** Embossed line: a dark groove with a light lip below it (path built by draw()). */
function emboss(g, P, draw, w = 1) {
  const lw = Math.max(1, P / 210) * w;
  g.save();
  g.lineCap = 'round';
  g.lineJoin = 'round';
  g.translate(0, lw * 1.1);
  g.strokeStyle = 'rgba(255,236,190,0.32)';
  g.lineWidth = lw * 1.5;
  draw();
  g.stroke();
  g.restore();
  g.save();
  g.lineCap = 'round';
  g.lineJoin = 'round';
  g.strokeStyle = 'rgba(42,24,10,0.62)';
  g.lineWidth = lw;
  draw();
  g.stroke();
  g.restore();
}

/**
 * Corinthian helmet details in the helmet's mapping (u = 0.5 + θ/2π with the face at the
 * centre, v = (y + 0.13)/0.34 in head metres): arched brows meeting over the nasal, the
 * carination round the skull, and borders along the lower edge and the face opening.
 */
function paintHelmet(g, P) {
  const X = (t) => (0.5 + t / (Math.PI * 2)) * P;
  const Y = (y) => (1 - (y + 0.13) / 0.34) * P;
  const sm = (a, b, v) => {
    const t = Math.min(1, Math.max(0, (v - a) / (b - a)));
    return t * t * (3 - 2 * t);
  };
  // darker patina low on the cheek pieces and neck guard
  const grad = g.createLinearGradient(0, Y(0.0), 0, Y(-0.11));
  grad.addColorStop(0, 'rgba(60,40,20,0)');
  grad.addColorStop(1, 'rgba(60,40,20,0.28)');
  g.fillStyle = grad;
  g.fillRect(0, Y(0.0), P, Y(-0.11) - Y(0.0));
  for (const sd of [-1, 1]) {
    emboss(g, P, () => {
      g.beginPath();
      g.moveTo(X(0), Y(0.058));
      g.quadraticCurveTo(X(sd * 0.36), Y(0.09), X(sd * 0.98), Y(0.07));
      g.quadraticCurveTo(X(sd * 1.7), Y(0.096), X(sd * Math.PI), Y(0.094));
    }, 1.2);
  }
  // borders: along the lower edge (following its notch and flare) and down the cheek slit
  emboss(g, P, () => {
    g.beginPath();
    for (let i = 0; i <= 64; i++) {
      const t = -Math.PI + (i / 64) * Math.PI * 2, a = Math.abs(t);
      const y = -0.086 + 0.022 * Math.exp(-(((a - 1.45) / 0.3) ** 2)) - 0.03 * sm(1.8, 2.7, a);
      if (i === 0) g.moveTo(X(t), Y(y));
      else g.lineTo(X(t), Y(y));
    }
  });
  for (const sd of [-1, 1]) {
    emboss(g, P, () => {
      g.beginPath();
      g.moveTo(X(sd * 0.26), Y(-0.095));
      g.lineTo(X(sd * 0.26), Y(-0.02));
      g.quadraticCurveTo(X(sd * 0.3), Y(0.012), X(sd * 0.68), Y(0.03));
    }, 0.8);
  }
}

/** Anatomical greave in the shin mapping (front at u = 0.5, v = (y − 0.11)/0.41). */
function paintGreave(g, P) {
  const X = (t) => (0.5 + t / (Math.PI * 2)) * P;
  const Y = (y) => (1 - (y - 0.11) / 0.41) * P;
  // knee boss
  for (const r of [0.028, 0.016]) {
    emboss(g, P, () => {
      g.beginPath();
      g.ellipse(X(0), Y(0.465), (r / 0.06) * P * 0.16, r * P * 2.2, 0, 0, Math.PI * 2);
    }, 0.9);
  }
  // calf muscles and the shin ridge
  for (const sd of [-1, 1]) {
    emboss(g, P, () => {
      g.beginPath();
      g.moveTo(X(sd * 0.5), Y(0.43));
      g.bezierCurveTo(X(sd * 1.6), Y(0.4), X(sd * 1.9), Y(0.3), X(sd * 1.2), Y(0.2));
    });
  }
  emboss(g, P, () => {
    g.beginPath();
    g.moveTo(X(0), Y(0.42));
    g.lineTo(X(0), Y(0.16));
  }, 0.7);
  // rolled rims at the top and the ankle
  for (const y of [0.5, 0.122]) {
    emboss(g, P, () => {
      g.beginPath();
      g.moveTo(0, Y(y));
      g.lineTo(P, Y(y));
    }, 1.1);
  }
}

/**
 * The aspis rim in the planar shield mapping (tile radius 0.5 = the shield's edge): a groove
 * where the offset rim meets the bowl and a running guilloche on the rim, as on many aspides.
 */
function paintAspisRim(g, P) {
  const c = P / 2, rIn = (0.4 / 0.455) * c, rOut = c * 0.985;
  const TAU = Math.PI * 2;
  g.save();
  g.strokeStyle = 'rgba(36,22,10,0.6)';
  g.lineWidth = Math.max(1, P / 150);
  g.beginPath();
  g.arc(c, c, rIn, 0, TAU);
  g.stroke();
  g.strokeStyle = 'rgba(255,236,190,0.25)';
  g.lineWidth = Math.max(1, P / 260);
  g.beginPath();
  g.arc(c, c, rIn + P / 160, 0, TAU);
  g.stroke();
  const rm = (rIn + rOut) / 2, w = (rOut - rIn) * 0.3;
  const n = 30, steps = n * 10;
  g.strokeStyle = 'rgba(30,18,8,0.7)';
  g.lineWidth = Math.max(1, P / 230);
  for (const ph of [0, Math.PI]) {
    g.beginPath();
    for (let i = 0; i <= steps; i++) {
      const a = (i / steps) * TAU;
      const r = rm + w * Math.sin(a * n + ph);
      if (i === 0) g.moveTo(c + Math.cos(a) * r, c + Math.sin(a) * r);
      else g.lineTo(c + Math.cos(a) * r, c + Math.sin(a) * r);
    }
    g.stroke();
  }
  g.fillStyle = 'rgba(30,18,8,0.6)';
  for (let i = 0; i < n; i++) {
    const a = ((i + 0.5) / n) * TAU;
    g.beginPath();
    g.arc(c + Math.cos(a) * rm, c + Math.sin(a) * rm, Math.max(0.8, P / 300), 0, TAU);
    g.fill();
  }
  g.restore();
}

// ---------------------------------------------------------------------------------------

const CLOTH_SPEC = {
  skinG: { tex: 'skinGreek', paint: (g, P) => paintSkin(g, P, SKIN.skinG, 11) },
  skinG2: { tex: 'skinGreek', tint: 'rgb(214,196,186)', paint: (g, P) => paintSkin(g, P, SKIN.skinG2, 12) },
  skinP: { tex: 'skinPersian', paint: (g, P) => paintSkin(g, P, SKIN.skinP, 13) },
  skinP2: { tex: 'skinPersian', tint: 'rgb(214,198,190)', paint: (g, P) => paintSkin(g, P, SKIN.skinP2, 14) },
  faceG: { tex: 'skinGreek', paint: (g, P) => paintSkin(g, P, SKIN.skinG, 15), face: true },
  faceP: { tex: 'skinPersian', paint: (g, P) => paintSkin(g, P, SKIN.skinP, 16), face: true },
  faceS: { tex: 'skinPersian', tint: 'rgb(236,226,216)', paint: (g, P) => paintSkin(g, P, 0xa77a58, 17), face: true },
  hair: { paint: (g, P) => paintHair(g, P, 0x2c1f15, 18) },
  linen: { tex: 'linen', paint: (g, P) => paintCloth(g, P, 0xd8ceb4, 19, 0.7), deco: paintLinen },
  crimson: { tex: 'crimson', paint: (g, P) => paintCloth(g, P, 0x8a1c16, 20) },
  leather: { tex: 'leather', paint: (g, P) => paintLeather(g, P, 0x6e4a2c, 21) },
  leatherDark: { tex: 'leather', tint: 'rgb(130,110,100)', paint: (g, P) => paintLeather(g, P, 0x3e2a1c, 22) },
  wood: { tex: 'wood', paint: (g, P) => paintWood(g, P, 0x8a6a45, 23) },
  woodDark: { tex: 'wood', tint: 'rgb(150,125,110)', paint: (g, P) => paintWood(g, P, 0x5a4029, 24) },
  // darker and more saturated than raw straw: a weathered shield, not the brightest thing in view
  wicker: { tex: 'wicker', tint: 'rgb(199,186,180)', paint: (g, P) => paintWicker(g, P, 0x83653a, 25), deco: (g, P) => paintWickerWear(g, P, 43) },
  felt: { tex: 'felt', tint: 'rgb(220,196,150)', paint: (g, P) => noiseFill(g.canvas, 0xa8956e, [18, 16, 12], 6, 4, 26, true) },
  robeA: { tex: 'persianRobeA', paint: (g, P) => paintPattern(g, P, 'robeA', 27) },
  robeB: { tex: 'persianRobeB', paint: (g, P) => paintPattern(g, P, 'robeB', 28) },
  robeC: { tex: 'persianRobeC', paint: (g, P) => paintPattern(g, P, 'robeC', 29) },
  quilted: { paint: (g, P) => paintPattern(g, P, 'quilted', 30) },
  tunicRed: { tex: 'linen', tint: '#9c3a26', paint: (g, P) => paintCloth(g, P, 0x8e3622, 31) },
  tunicBlue: { tex: 'linen', tint: '#4a5a86', paint: (g, P) => paintCloth(g, P, 0x3c4a72, 32) },
  tunicOchre: { tex: 'linen', tint: '#b88a3a', paint: (g, P) => paintCloth(g, P, 0xa67a34, 33) },
  // the face inside a Corinthian helmet: in deep shade, only the eyes catch light
  faceH: { tex: 'skinGreek', paint: (g, P) => paintSkin(g, P, SKIN.skinG, 34), face: true },
  trouserDiamond: { paint: (g, P) => paintPattern(g, P, 'trouserDiamond', 35) },
  trouserStripe: { paint: (g, P) => paintPattern(g, P, 'trouserStripe', 36) },
  turban: { paint: (g, P) => paintPattern(g, P, 'turban', 37) },
  feltDark: { tex: 'felt', tint: 'rgb(150,110,80)', paint: (g, P) => noiseFill(g.canvas, 0x7a5a3a, [16, 12, 10], 6, 4, 38, true) },
  straw: { paint: (g, P) => paintStraw(g, P, 39) },
  dogskin: { paint: (g, P) => paintHair(g, P, 0x6a4a30, 40) },
  wool: { paint: (g, P) => paintCloth(g, P, 0x9a8a6c, 41) },
  horsehair: { paint: (g, P) => paintStrands(g, P, 0x6a1a12, 42) },
};

const METAL_SPEC = {
  bronze: { tex: 'bronze', paint: (g, P) => paintMetal(g, P, 0xb8873e, 51), metal: 1, rough: 0.34 },
  bronzeDark: { tex: 'bronze', tint: 'rgb(190,170,140)', paint: (g, P) => paintMetal(g, P, 0x8e6a36, 52), metal: 1, rough: 0.46 },
  iron: { paint: (g, P) => paintMetal(g, P, 0x7a7a7c, 53), metal: 1, rough: 0.42 },
  gold: { paint: (g, P) => paintMetal(g, P, 0xd9ab45, 54, false), metal: 1, rough: 0.28 },
  silver: { paint: (g, P) => paintMetal(g, P, 0xc8c8cc, 55, false), metal: 1, rough: 0.3 },
  scaleBronze: { tex: 'scale', paint: (g, P) => paintScale(g, P, 0xb08040, 56), metal: 1, rough: 0.48 },
  scaleIron: { tex: 'scale', tint: 'rgb(150,150,155)', paint: (g, P) => paintScale(g, P, 0x808084, 57), metal: 1, rough: 0.52 },
  ironDark: { paint: (g, P) => paintMetal(g, P, 0x4c4a48, 58), metal: 1, rough: 0.55 },
  greave: { tex: 'bronze', paint: (g, P) => paintMetal(g, P, 0xb8873e, 59), metal: 1, rough: 0.33, deco: paintGreave },
  helmet: { tex: 'bronze', paint: (g, P) => paintMetal(g, P, 0xb8873e, 60), metal: 1, rough: 0.3, deco: paintHelmet },
};
for (const b of BLAZONS) METAL_SPEC[b] = { tex: b, paint: (g, P) => paintBlazon(g, P, b, 61), metal: 1, rough: 0.36, blazon: b, deco: paintAspisRim };

function placeTile(atlasCtx, tileCanvas, x0, y0, T, gutter) {
  const P = tileCanvas.width;
  atlasCtx.save();
  atlasCtx.beginPath();
  atlasCtx.rect(x0, y0, T, T);
  atlasCtx.clip();
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) atlasCtx.drawImage(tileCanvas, x0 + gutter + dx * P, y0 + gutter + dy * P);
  }
  atlasCtx.restore();
}

function getTex(ctx, name, used) {
  try {
    const t = ctx.textures && ctx.textures.get ? ctx.textures.get(name) : null;
    if (t && used) used.add(name);
    return t || null;
  } catch (err) {
    return null;
  }
}

/** Shared textures the atlas reads that something else reads later (viewmodel.js: its aspis). */
const KEEP_SOURCES = new Set(['blazonFly']);

/**
 * Builds one tile canvas (P×P) for a spec. Shield faces also get a paint mask (the emblem and
 * the rim decoration on a transparent ground), so their paint can be made non-metallic in the
 * roughness/metalness atlas without reading pixels back from a canvas.
 */
function buildTile(ctx, name, spec, P, used) {
  const cv = makeCanvas(P, P);
  const g = cv.getContext('2d');
  const ext = spec.tex ? getTex(ctx, spec.tex, used) : null;
  let mask = null;
  if (spec.blazon) {
    // emblems are painted on a transparent ground: lay the bronze first
    const br = getTex(ctx, 'bronze', used);
    if (!(br && drawExternal(g, br, P))) paintMetal(g, P, 0xb88a42, 61);
    mask = makeCanvas(P, P);
    const mg = mask.getContext('2d');
    if (!(ext && drawExternal(mg, ext, P))) blazonShapes(mg, P, spec.blazon);
    g.drawImage(mask, 0, 0);
    if (spec.deco) spec.deco(mg, P);
  } else {
    const used = ext ? drawExternal(g, ext, P, spec.reps || 1) : false;
    if (used) {
      if (spec.tint) tint(g, P, spec.tint);
    } else {
      spec.paint(g, P);
    }
  }
  if (spec.face) paintFace(g, P, name);
  if (spec.deco) spec.deco(g, P);
  return { cv, mask };
}

/**
 * Roughness (G) / metalness (B) tile for a metal tile: the spec's roughness with a little
 * variation, and paint (from the mask) as a rough dielectric. Draw calls only, no readback.
 */
function ormTile(spec, P2, mask, seed) {
  const out = makeCanvas(P2, P2);
  const g = out.getContext('2d');
  const r0 = Math.round(spec.rough * 255);
  g.fillStyle = `rgb(255,${r0},255)`;
  g.fillRect(0, 0, P2, P2);
  const r = rng(seed);
  for (let i = 0; i < 40; i++) {
    const v = Math.max(0, Math.min(255, r0 + Math.round((r() - 0.5) * 70)));
    g.fillStyle = `rgba(255,${v},255,0.35)`;
    const x = r() * P2, y = r() * P2, s = P2 * (0.05 + r() * 0.2);
    g.beginPath();
    g.arc(x, y, s, 0, Math.PI * 2);
    g.fill();
  }
  if (mask) {
    const m = makeCanvas(P2, P2);
    const mg = m.getContext('2d');
    mg.drawImage(mask, 0, 0, P2, P2);
    mg.globalCompositeOperation = 'source-in';
    mg.fillStyle = 'rgb(255,184,0)';
    mg.fillRect(0, 0, P2, P2);
    g.drawImage(m, 0, 0);
  }
  return out;
}

const BLOOD_LEVELS = [0, 0.35, 0.65, 1];

/**
 * Texel of a skeleton's bone texture that holds the body's blood-stain seed: the first one past
 * the bone matrices (characters.js writes it once; bone updates never reach it). Per instance at
 * no per-draw cost, while the materials stay shared.
 */
export const BLOOD_SEED_TEXEL = BONES.length * 4;

/**
 * Shared onBeforeCompile: blood splatter in bind space (follows the body as it moves), offset
 * per body by its seed so two corpses of a kind never carry the same stains.
 */
function bloodCompile(shader) {
  shader.uniforms.uBlood = this.userData.uBlood;
  shader.vertexShader = shader.vertexShader
    .replace('#include <common>', '#include <common>\nvarying vec3 vBindPos;\nvarying float vBloodSeed;')
    .replace(
      '#include <begin_vertex>',
      `#include <begin_vertex>
vBindPos = position;
vBloodSeed = 0.0;
#ifdef USE_SKINNING
{
  int bSize = textureSize( boneTexture, 0 ).x;
  int bj = ${BLOOD_SEED_TEXEL};
  if ( bj < bSize * bSize ) vBloodSeed = texelFetch( boneTexture, ivec2( bj % bSize, bj / bSize ), 0 ).x;
}
#endif`,
    );
  shader.fragmentShader = shader.fragmentShader
    .replace(
      '#include <common>',
      `#include <common>
varying vec3 vBindPos;
varying float vBloodSeed;
uniform float uBlood;
float bHash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float bNoise(vec3 x) {
  vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(mix(bHash(i), bHash(i + vec3(1,0,0)), f.x), mix(bHash(i + vec3(0,1,0)), bHash(i + vec3(1,1,0)), f.x), f.y),
             mix(mix(bHash(i + vec3(0,0,1)), bHash(i + vec3(1,0,1)), f.x), mix(bHash(i + vec3(0,1,1)), bHash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float bloodMask() {
  if (uBlood <= 0.0) return 0.0;
  vec3 p = vBindPos;
  vec3 q = p + vec3(vBloodSeed * 7.1, vBloodSeed * 1.9, vBloodSeed * 3.3);
  float n = bNoise(q * vec3(11.0, 5.0, 11.0)) * 0.65 + bNoise(q * 31.0) * 0.35;
  // Wounds stain the torso; the head and legs stay mostly clean (a dead man is stained, not dyed).
  float torso = smoothstep(0.6, 1.0, p.y) * (1.0 - 0.85 * smoothstep(1.32, 1.48, p.y));
  float region = torso * (0.75 + 0.25 * sign(p.z)) + 0.2;
  float thr = 1.02 - uBlood * 0.32;
  return smoothstep(thr, thr + 0.06, n * region + 0.12 * uBlood);
}`,
    )
    .replace(
      '#include <map_fragment>',
      `#include <map_fragment>
float bMask = bloodMask();
diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.17, 0.012, 0.008), bMask * 0.82);`,
    )
    .replace(
      '#include <metalnessmap_fragment>',
      `#include <metalnessmap_fragment>
metalnessFactor = mix(metalnessFactor, 0.0, bMask);`,
    )
    .replace(
      '#include <roughnessmap_fragment>',
      `#include <roughnessmap_fragment>
roughnessFactor = mix(roughnessFactor, 0.3, bMask);`,
    );
}

/**
 * Lazily builds the atlases and the shared materials. Returns
 * { cloth: [mat×4 blood levels], metal: [mat×4], props: [cloth0, metal0], rects, T }.
 */
export function buildMaterials(ctx) {
  const quality = ctx.quality || 'medium';
  const { cloth: clothRects, metal: metalRects, T, g: gutter } = atlasRects(quality);
  const P = T - gutter * 2;
  const clothCv = makeCanvas(COLS_C * T, ROWS_C * T);
  const cg = clothCv.getContext('2d');
  const used = new Set();
  CLOTH_TILES.forEach((name, i) => {
    const { cv } = buildTile(ctx, name, CLOTH_SPEC[name], P, used);
    placeTile(cg, cv, (i % COLS_C) * T, Math.floor(i / COLS_C) * T, T, gutter);
  });
  const metalCv = makeCanvas(COLS_M * T, ROWS_M * T);
  const mg = metalCv.getContext('2d');
  const half = Math.max(32, T / 2), gh = Math.max(1, gutter / 2), P2 = half - gh * 2;
  const ormCv = makeCanvas(COLS_M * half, ROWS_M * half);
  const og = ormCv.getContext('2d');
  METAL_TILES.forEach((name, i) => {
    const spec = METAL_SPEC[name];
    const { cv, mask } = buildTile(ctx, name, spec, P, used);
    const x = i % COLS_M, y = Math.floor(i / COLS_M);
    placeTile(mg, cv, x * T, y * T, T, gutter);
    placeTile(og, ormTile(spec, P2, mask, 70 + i), x * half, y * half, half, gh);
  });
  const aniso = quality === 'high' ? 8 : 4;
  const mk = (cv, srgb) => {
    const t = new THREE.CanvasTexture(cv);
    t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
    t.anisotropy = aniso;
    t.generateMipmaps = true;
    t.minFilter = THREE.LinearMipmapLinearFilter;
    t.magFilter = THREE.LinearFilter;
    t.needsUpdate = true;
    return t;
  };
  const clothTex = mk(clothCv, true), metalTex = mk(metalCv, true), ormTex = mk(ormCv, false);
  // The atlases hold their own copies now: the source textures (atlas-only) can be freed.
  for (const name of used) {
    if (KEEP_SOURCES.has(name)) continue;
    try {
      ctx.textures.release?.(name);
    } catch (err) {
      console.warn('characters: texture release failed', name, err);
    }
  }
  const cloth = [], metal = [];
  for (const level of BLOOD_LEVELS) {
    const c = new THREE.MeshStandardMaterial({ map: clothTex, roughness: 0.82, metalness: 0 });
    c.name = `chr-cloth-${level}`;
    c.userData.uBlood = { value: level };
    c.onBeforeCompile = bloodCompile;
    cloth.push(c);
    const m = new THREE.MeshStandardMaterial({ map: metalTex, roughnessMap: ormTex, metalnessMap: ormTex, roughness: 1, metalness: 1 });
    m.name = `chr-metal-${level}`;
    m.userData.uBlood = { value: level };
    m.onBeforeCompile = bloodCompile;
    metal.push(m);
  }
  return { cloth, metal, rects: { ...clothRects, ...metalRects }, T, textures: [clothTex, metalTex, ormTex], levels: BLOOD_LEVELS };
}
