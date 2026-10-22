/**
 * Per-kind data (KINDS) and kit assembly. A kind variant is everything that changes the
 * merged geometry (headgear, colours, blazon, weapon); it is chosen deterministically from
 * the instance seed and opts, and each variant's geometry is built once and shared.
 */
import * as THREE from 'three';
import { sweep, slab, box, tube, thetas, mat } from './geo.js';
import { B } from './rig.js';
import { BLAZONS } from './atlas.js';
import {
  buildHead, skirt, band, spearParts, sagarisParts, bowParts, gerronParts, aspisParts, sparaParts,
  gorytosParts, bundleParts,
} from './models.js';

const PI = Math.PI;
const smooth = (a, b, v) => {
  const t = Math.min(1, Math.max(0, (v - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

/**
 * Public per-kind facts. height: metres (before the ±4% per-instance variation);
 * weapon/shield: what the model carries; style: which pose family animates it;
 * chest/head: fractions of height for aiming and hit points.
 */
export const KINDS = {
  spartan: { height: 1.80, weapon: 'dory', shield: 'aspis', attackClip: 'thrust', style: 'hoplite', team: 'greek', blazons: BLAZONS, chest: 0.74, head: 0.93 },
  levy: { height: 1.74, weapon: 'persianSpear', shield: 'gerron', attackClip: 'thrust', style: 'levy', team: 'persian', variants: ['mede', 'kissian'], chest: 0.74, head: 0.93 },
  sparabara: { height: 1.75, weapon: 'persianSpear', shield: 'spara', attackClip: 'thrust', style: 'sparabara', team: 'persian', chest: 0.74, head: 0.93 },
  immortal: { height: 1.80, weapon: 'persianSpear', shield: 'gerron', attackClip: 'overhead', style: 'levy', team: 'persian', chest: 0.74, head: 0.93 },
  archer: { height: 1.72, weapon: 'bow', shield: null, attackClip: 'shoot', style: 'archer', team: 'persian', chest: 0.74, head: 0.93 },
  captain: { height: 1.95, weapon: 'sagaris', shield: null, attackClip: 'axe', style: 'captain', team: 'persian', heavy: true, chest: 0.74, head: 0.93 },
  dummy: { height: 1.75, weapon: null, shield: 'wicker', attackClip: null, style: 'dummy', team: 'persian', chest: 0.72, head: 0.97 },
  helot: { height: 1.70, weapon: 'bundle', shield: null, attackClip: null, style: 'helot', team: 'greek', chest: 0.74, head: 0.93 },
};

// ---------------------------------------------------------------------------------------
// Headgear (head-local, placed with mat(U.head))

function corinthian(c, crestTile) {
  const { add, U, S } = c;
  const M = mat(U.head);
  const th = c.lo
    ? [-PI, -1.9, -1.2, -0.62, -0.17, -0.085, 0, 0.085, 0.17, 0.62, 1.2, 1.9, PI]
    : [-PI, -2.45, -1.8, -1.2, -0.62, -0.17, -0.085, 0, 0.085, 0.17, 0.62, 1.2, 1.8, 2.45, PI];
  const R = [
    { y: 0.2, rx: 0, rz: 0, z: -0.016 },
    { y: 0.188, rx: 0.05, rz: 0.064, z: -0.016 },
    { y: 0.162, rx: 0.08, rz: 0.103, z: -0.014 },
    { y: 0.12, rx: 0.09, rz: 0.115, z: -0.01 },
    { y: 0.083, rx: 0.091, rz: 0.117, z: -0.005, shape: (t) => 1 + 0.035 * Math.max(0, Math.cos(t)) ** 3 },
    { y: 0.061, rx: 0.088, rz: 0.118, z: 0.002, fn: (t, p) => { if (Math.abs(t) < 0.7) p[1] -= 0.015 * smooth(0.28, 0.62, Math.abs(t)); } },
    { y: 0.033, rx: 0.087, rz: 0.121, z: 0.004, fn: (t, p) => { if (Math.abs(t) < 0.7) p[1] += 0.008 * smooth(0.36, 0.62, Math.abs(t)); } },
    { y: 0.002, rx: 0.086, rz: 0.121, z: 0.006, shape: (t) => 1 + 0.03 * Math.max(0, Math.cos(t)) ** 4 },
    { y: -0.04, rx: 0.083, rz: 0.116, z: 0.01, shape: (t) => 1 + 0.02 * Math.max(0, Math.cos(t)) },
    {
      y: -0.084, rx: 0.074, rz: 0.104, z: 0.013,
      fn: (t, p) => {
        const a = Math.abs(t);
        p[1] -= 0.028 * smooth(1.3, 2.6, a);
        const f = 1 + 0.12 * smooth(1.6, 2.9, a);
        p[0] *= f;
        p[2] = (p[2] - 0.013) * f + 0.013 - 0.012 * smooth(1.6, 2.9, a);
      },
    },
  ];
  const skip = (i, j) => {
    const mid = Math.abs((th[j] + th[j + 1]) / 2);
    if (i === 5) return mid > 0.085 && mid < 0.62;
    if (i === 6) return mid > 0.085 && mid < 0.17;
    if (i >= 7) return mid < 0.17;
    return false;
  };
  add(sweep(R, { thetas: th, skip, uvFn: (u, v, t, i, p) => [0.5 + t / (2 * PI), (p[1] + 0.12) / 0.33] }), 'bronze', B.head, M);
  // horsehair crest on a low holder, front to back over the crown
  const crest = slab(
    (u, v, out) => {
      const a = 0.55 - u * 2.55; // forehead → nape
      const R0 = 0.19;
      const bx = 0, by = 0.012 + Math.cos(a) * R0, bz = -0.012 + Math.sin(a) * R0;
      const h = 0.025 + 0.085 * Math.sin(Math.min(1, u * 1.12) * PI) ** 0.75;
      out[0] = 0.013 + 0.007 * v;
      out[1] = by + Math.cos(a) * h * v;
      out[2] = bz + Math.sin(a) * h * v;
      void bx;
    },
    S(8, 4), 1, 0.026 + 0.007, { flip: true, edgeUv: [0.1, 0.1, 0.9, 0.9] },
  );
  add(crest, crestTile, B.head, M.clone().multiply(mat([-0.0, 0, 0])), { sub: [0, 0.2, 1, 0.8] });
}

/** Soft felt tiara (Mede/Persian hood): covers skull, neck and cheeks, the face open, peak folded forward. */
function tiara(c, tile, o = {}) {
  const { add, U, S } = c;
  const M = mat(U.head);
  const th = c.lo
    ? [-PI, -1.9, -1.2, -0.8, 0, 0.8, 1.2, 1.9, PI]
    : [-PI, -2.4, -1.75, -1.2, -0.8, -0.4, 0, 0.4, 0.8, 1.2, 1.75, 2.4, PI];
  const tall = o.tall || 0;
  const R = [
    { y: 0.235 + tall, rx: 0, rz: 0, z: 0.03 + tall * 0.25 },
    { y: 0.22 + tall * 0.8, rx: 0.035, rz: 0.04, z: 0.02 + tall * 0.2 },
    { y: 0.19 + tall * 0.4, rx: 0.07, rz: 0.085, z: 0.0 },
    { y: 0.14, rx: 0.088, rz: 0.11, z: -0.012 },
    { y: 0.085, rx: 0.088, rz: 0.112, z: -0.01 },
    { y: 0.03, rx: 0.087, rz: 0.11, z: -0.012 },
    { y: -0.03, rx: 0.084, rz: 0.106, z: -0.014 },
    {
      y: -0.085, rx: 0.08, rz: 0.104, z: -0.016,
      fn: (t, p) => {
        const a = Math.abs(t);
        p[1] -= 0.06 * smooth(1.4, 2.6, a);
        const f = 1 + 0.12 * smooth(1.6, 3.0, a);
        p[0] *= f;
        p[2] = (p[2] + 0.016) * f - 0.016;
      },
    },
  ];
  if (c.lo) R.splice(5, 1);
  const faceRing = c.lo ? 4 : 4;
  const skip = (i, j) => {
    const mid = Math.abs((th[j] + th[j + 1]) / 2);
    return i >= faceRing && mid < 0.8;
  };
  add(sweep(R, { thetas: th, skip, uvFn: (u, v, t, i, p) => [0.5 + t / (2 * PI), (p[1] + 0.15) / 0.42] }), tile, B.head, M);
  // chin strap / lappet tie under the beard line
  if (!c.lo) {
    add(tube([[0.083, -0.03, 0.03], [0.06, -0.075, 0.07], [0, -0.09, 0.085], [-0.06, -0.075, 0.07], [-0.083, -0.03, 0.03]], [0.008], { segs: 4 }), tile, B.head, M);
  }
}

/** Kissian mitra: wrapped turban. */
function mitra(c) {
  const { add, U, S } = c;
  const M = mat(U.head);
  const R = [
    { y: 0.022, rx: 0.08, rz: 0.104, z: -0.006 },
    { y: 0.045, rx: 0.095, rz: 0.12, z: -0.006 },
    { y: 0.08, rx: 0.098, rz: 0.122, z: -0.008 },
    { y: 0.11, rx: 0.1, rz: 0.124, z: -0.01 },
    { y: 0.14, rx: 0.094, rz: 0.116, z: -0.012 },
    { y: 0.168, rx: 0.08, rz: 0.098, z: -0.014 },
    { y: 0.19, rx: 0.05, rz: 0.06, z: -0.016 },
    { y: 0.198, rx: 0, rz: 0, z: -0.016 },
  ].map((r, i) => {
    // wraps cross diagonally: lift the front of some rings
    if (i > 0 && i < 6) r.fn = (t, p) => { p[1] += 0.012 * Math.cos(t + i * 1.3) * (i % 2 ? 1 : -1); };
    return r;
  });
  add(sweep(R, { segs: S(14, 9), uvFn: (u, v, t, i, p) => [0.5 + t / (2 * PI), (p[1] - 0.02) / 0.18] }), 'turban', B.head, M);
}

/** Saka kyrbasia: tall, stiff, pointed felt cap with neck and ear flaps. */
function sakaCap(c) {
  tiara(c, 'feltDark', { tall: 0.0 });
  const { add, U, S } = c;
  const M = mat(U.head);
  const cone = sweep(
    [
      { y: 0.13, rx: 0.08, rz: 0.1, z: -0.01 },
      { y: 0.21, rx: 0.058, rz: 0.07, z: -0.018 },
      { y: 0.28, rx: 0.033, rz: 0.04, z: -0.03 },
      { y: 0.335, rx: 0.01, rz: 0.012, z: -0.042 },
      { y: 0.35, rx: 0, rz: 0, z: -0.046 },
    ],
    { segs: S(10, 6), uvFn: (u, v, t, i, p) => [0.5 + t / (2 * PI), (p[1] - 0.1) / 0.35] },
  );
  add(cone, 'feltDark', B.head, M);
}

/** Helot's dogskin cap (kyne). */
function kyne(c) {
  const { add, U, S } = c;
  const M = mat(U.head);
  const R = [
    { y: 0.03, rx: 0.084, rz: 0.108, z: -0.012 },
    { y: 0.05, rx: 0.088, rz: 0.112, z: -0.012 },
    { y: 0.11, rx: 0.084, rz: 0.108, z: -0.012 },
    { y: 0.16, rx: 0.06, rz: 0.077, z: -0.013 },
    { y: 0.188, rx: 0.025, rz: 0.03, z: -0.014 },
    { y: 0.192, rx: 0, rz: 0, z: -0.014 },
  ];
  add(sweep(R, { segs: S(12, 8), uvFn: (u, v, t, i, p) => [0.5 + t / (2 * PI), p[1] * 4] }), 'dogskin', B.head, M);
}

/** Twisted cloth headband of the Immortals (Persepolis). */
function headband(c) {
  const { U } = c;
  band(c, 'turban', 0.07, 0.1, 0.081, 0.104, -0.006, B.head, { m: mat(U.head) });
}

// ---------------------------------------------------------------------------------------
// Kit pieces

function cloak(c) {
  const { add, S, s, bulk } = c;
  const yTop = 1.455, yBot = 0.44;
  const pt = slab(
    (u, v, out) => {
      const phi = (u - 0.5) * 2 * 1.2;
      const a = PI + phi;
      const w = 1 - v; // 0 at the top
      const y = yTop - (yTop - yBot) * w + 0.03 * Math.cos(phi * 1.3) * (1 - w) ** 3;
      const fold = Math.sin(phi * 6.5 + 0.4) * smooth(0, 0.5, w);
      const r = 0.168 + 0.05 * w + 0.01 * fold;
      const rz = 0.118 + 0.045 * w + 0.02 * fold;
      out[0] = Math.sin(a) * r * bulk;
      out[1] = y;
      out[2] = Math.cos(a) * rz * bulk - 0.022 - 0.012 * w;
    },
    S(6, 4), S(4, 2), 0.01, { flip: true, backUv: (u, v) => [u, v] },
  );
  add(pt, 'crimson', B.cloak, null, { boneFn: (p) => (p.y > 1.43 * s ? B.chest : B.cloak) });
}

function pteruges(c) {
  skirt(c, 'linen', 1.035, 0.76, 0.158, 0.19, { thick: 0.012, flare: 0.08, depth: 0.74 });
}

function scabbard(c, side = 1) {
  const { add } = c;
  const M = mat([0.17 * side, 0.9, 0.03], [0.55, 0, side * 0.2]);
  add(box(0.045, 0.46, 0.022), 'leatherDark', B.pelvis, M.clone().multiply(mat([0, -0.2, 0])));
  add(box(0.024, 0.1, 0.024), 'wood', B.pelvis, M.clone().multiply(mat([0, 0.075, 0])));
  add(box(0.09, 0.018, 0.03), 'bronzeDark', B.pelvis, M.clone().multiply(mat([0, 0.025, 0])));
  add(box(0.04, 0.025, 0.03), 'bronzeDark', B.pelvis, M.clone().multiply(mat([0, 0.13, 0])));
}

function xiphos(c) {
  const { add, U } = c;
  const M = mat(U.weaponR);
  const blade = slab(
    (u, v, out) => {
      const z = 0.06 + v * 0.5;
      const w = 0.022 + 0.012 * Math.sin(Math.min(1, v * 1.4) * PI * 0.8) - 0.022 * smooth(0.82, 1, v);
      out[0] = (u - 0.5) * 2 * Math.max(0.001, w);
      out[1] = 0.004;
      out[2] = z;
    },
    2, c.S(5, 3), 0.008, { flip: false },
  );
  add(blade, 'iron', B.weaponR, M.clone().multiply(mat([0, 0, 0])));
  add(box(0.10, 0.022, 0.028), 'bronzeDark', B.weaponR, M.clone().multiply(mat([0, 0, 0.05])));
  add(box(0.028, 0.028, 0.11), 'wood', B.weaponR, M.clone().multiply(mat([0, 0, -0.005])));
  add(box(0.05, 0.03, 0.03), 'bronzeDark', B.weaponR, M.clone().multiply(mat([0, 0, -0.07])));
}

function arrowParts(add, bone, M, lo) {
  const toZ = new THREE.Matrix4().makeRotationX(PI / 2);
  add(sweep([{ y: -0.02, rx: 0.004, rz: 0.004 }, { y: 0.70, rx: 0.004, rz: 0.004 }], { segs: 3, uvFn: (u, v) => [u * 0.2, v] }), 'wood', bone, M.clone().multiply(toZ));
  add(sweep([{ y: 0.69, rx: 0.006, rz: 0.006 }, { y: 0.72, rx: 0.008, rz: 0.008 }, { y: 0.76, rx: 0, rz: 0 }], { segs: 3, uvFn: (u, v) => [u * 0.2, v * 0.2] }), 'bronzeDark', bone, M.clone().multiply(toZ));
  if (!lo) {
    for (const a of [0, (2 * PI) / 3, (4 * PI) / 3]) {
      const f = slab((u, v, out) => { out[0] = 0; out[1] = 0.004 + v * 0.012; out[2] = 0.0 + u * 0.1 + v * 0.01; }, 1, 1, 0.001, { noEdges: true });
      add(f, 'wool', bone, M.clone().multiply(mat([0, 0, 0], [0, 0, a])));
    }
  }
}

function quiverAtHip(c, arrows) {
  gorytosParts(c, B.pelvis, [0.20, 0.86, -0.06], [0.28, 0.0, -0.18], { arrows });
}

function persianBody(c, o) {
  band(c, o.belt || 'leatherDark', 0.995, 1.035, 0.157, 0.106, -0.004, B.pelvis, { extra: { boneFn: c.torsoBone } });
}

// ---------------------------------------------------------------------------------------
// Specs

const pick = (arr, r) => arr[Math.floor(r * arr.length) % arr.length];

function hash01(seed, salt) {
  let h = (seed * 2654435761 + salt * 40503) >>> 0;
  h = Math.imul(h ^ (h >>> 15), 2246822507);
  h = Math.imul(h ^ (h >>> 13), 3266489909);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function normBlazon(b) {
  if (!b) return null;
  if (BLAZONS.includes(b)) return b;
  const k = 'blazon' + String(b).charAt(0).toUpperCase() + String(b).slice(1);
  return BLAZONS.includes(k) ? k : null;
}

/**
 * Resolves the variant for (kind, opts, seed). Returns { key, spec } where spec holds build
 * proportions, tiles, flags and head/kit builders.
 */
export function variantFor(kind, opts, seed) {
  const r = (salt) => hash01(seed, salt);
  const H = KINDS[kind].height;
  if (kind === 'spartan') {
    const blazon = normBlazon(opts.blazon) || pick(BLAZONS.slice(1), r(1));
    const crest = 'horsehair';
    const weapon = opts.weapon === 'xiphos' || opts.weapon === 'sword' ? 'xiphos' : 'dory';
    const skin = r(2) < 0.5 ? 'skinG' : 'skinG2';
    return {
      key: `spartan:${blazon}:${weapon}:${skin}`,
      spec: {
        kind, H, shoulders: 1.06, bulk: 1.05, weapon,
        tiles: { hips: 'crimson', trunk: 'linen', skin, sleeve: 'crimson', thigh: skin, shin: skin, foot: skin, neck: skin },
        sleeve: 'short', greaves: true,
        head: (c) => {
          buildHead(c, { face: 'faceG', beard: 'greek', hair: 'long', ears: false });
          corinthian(c, crest);
        },
        kit: (c) => {
          pteruges(c);
          cloak(c);
          scabbard(c, 1);
          aspisParts(c.add, B.itemL, { face: blazon, M: mat(c.U.itemL) }, c.lo);
          if (weapon === 'xiphos') xiphos(c);
          else spearParts(c.add, B.weaponR, { length: 2.5, grip: 1.05, butt: 'spike', headTile: 'iron', buttTile: 'bronze', headLen: 0.27 }, mat(c.U.weaponR), c.lo);
        },
      },
    };
  }
  if (kind === 'levy' || kind === 'sparabara' || kind === 'archer') {
    const kissian = kind === 'levy' && (opts.variant ? opts.variant === 'kissian' : r(1) < 0.5);
    const scheme = Math.floor(r(2) * 3);
    const tunic = ['tunicRed', 'tunicBlue', 'tunicOchre'][scheme];
    const trousers = scheme === 1 ? 'trouserDiamond' : 'trouserStripe';
    const coat = kind === 'levy' && r(3) < 0.45 ? 'scaleIron' : 'quilted';
    const skin = r(4) < 0.5 ? 'skinP' : 'skinP2';
    const variant = kissian ? 'kissian' : 'mede';
    return {
      key: `${kind}:${variant}:${tunic}:${coat}:${skin}`,
      spec: {
        kind, H, shoulders: 1.0, bulk: 1.0, variant,
        tiles: { hips: tunic, trunk: kind === 'archer' ? tunic : coat, skin, sleeve: tunic, thigh: trousers, shin: trousers, foot: 'leather', neck: skin },
        sleeve: 'long', trousers: true, boots: true,
        head: (c) => {
          buildHead(c, { face: 'faceP', beard: 'persian', hair: 'bun', ears: kissian });
          if (kissian) mitra(c);
          else tiara(c, scheme === 1 ? 'feltDark' : 'felt');
        },
        kit: (c) => {
          skirt(c, tunic, 1.035, kind === 'archer' ? 0.66 : 0.6, 0.162, 0.215, { thick: 0.008, flare: 0.08 });
          persianBody(c, {});
          if (kind === 'archer') {
            bowParts(c.add, B.itemL, B.nock, { M: mat(c.U.itemL) }, c.lo);
            arrowParts(c.add, B.weaponR, mat(c.U.weaponR), c.lo);
            quiverAtHip(c, true);
          } else {
            spearParts(c.add, B.weaponR, { length: 2.0, grip: 0.8, butt: 'knob', headTile: 'iron', buttTile: 'bronzeDark', headLen: 0.22, shaftR: 0.0135 }, mat(c.U.weaponR), c.lo);
            if (kind === 'sparabara') sparaParts(c.add, B.itemL, { M: mat(c.U.itemL) }, c.lo);
            else gerronParts(c.add, B.itemL, { M: mat(c.U.itemL) }, c.lo);
            quiverAtHip(c, false);
          }
        },
      },
    };
  }
  if (kind === 'immortal') {
    const robe = pick(['robeA', 'robeB', 'robeC'], r(1));
    const gold = opts.variant === 'gold' || (opts.variant !== 'silver' && r(2) < 0.2);
    const tiaraOn = r(3) < 0.4;
    const skin = r(4) < 0.5 ? 'skinP' : 'skinP2';
    return {
      key: `immortal:${robe}:${gold ? 'gold' : 'silver'}:${tiaraOn ? 't' : 'b'}:${skin}`,
      spec: {
        kind, H, shoulders: 1.02, bulk: 1.02,
        tiles: { hips: robe, trunk: robe, skin, sleeve: robe, thigh: 'trouserStripe', shin: 'trouserStripe', foot: 'leatherDark', neck: skin },
        sleeve: 'long', trousers: true, boots: true,
        head: (c) => {
          buildHead(c, { face: 'faceP', beard: 'persian', hair: 'bun', ears: !tiaraOn });
          if (tiaraOn) tiara(c, 'felt');
          else headband(c);
        },
        kit: (c) => {
          skirt(c, robe, 1.035, 0.2, 0.162, 0.27, { thick: 0.008, flare: 0.12, depth: 0.76 });
          persianBody(c, { belt: 'leather' });
          spearParts(c.add, B.weaponR, { length: 2.05, grip: 0.82, butt: 'pomegranate', headTile: 'iron', buttTile: gold ? 'gold' : 'silver', headLen: 0.23, shaftR: 0.0135 }, mat(c.U.weaponR), c.lo);
          gerronParts(c.add, B.itemL, { M: mat(c.U.itemL), shape: 'violin', width: 0.5, height: 0.86 }, c.lo);
          // bow case on the back
          gorytosParts(c, B.chest, [0.06, 1.22, -0.16], [0, PI / 2, 0.5], { arrows: true });
        },
      },
    };
  }
  if (kind === 'captain') {
    return {
      key: 'captain',
      spec: {
        kind, H, shoulders: 1.1, hips: 1.04, bulk: 1.12,
        tiles: { hips: 'tunicRed', trunk: 'scaleBronze', skin: 'skinP2', sleeve: 'tunicRed', thigh: 'trouserDiamond', shin: 'trouserDiamond', foot: 'leatherDark', neck: 'skinP2' },
        sleeve: 'long', trousers: true, boots: true,
        head: (c) => {
          buildHead(c, { face: 'faceS', beard: 'saka', hair: null, ears: false });
          sakaCap(c);
        },
        kit: (c) => {
          skirt(c, 'scaleBronze', 1.035, 0.64, 0.17, 0.22, { thick: 0.012, flare: 0.06 });
          persianBody(c, { belt: 'leather' });
          sagarisParts(c.add, B.weaponR, { length: 0.8, grip: 0.16 }, mat(c.U.weaponR), c.lo);
          quiverAtHip(c, true);
        },
      },
    };
  }
  if (kind === 'helot') {
    return {
      key: 'helot',
      spec: {
        kind, H, shoulders: 0.98, bulk: 0.96,
        tiles: { hips: 'wool', trunk: 'wool', skin: 'skinG2', sleeve: 'wool', thigh: 'skinG2', shin: 'skinG2', foot: 'skinG2', neck: 'skinG2' },
        sleeve: 'none',
        head: (c) => {
          buildHead(c, { face: 'faceG', beard: 'short', hair: null, ears: true });
          kyne(c);
        },
        kit: (c) => {
          skirt(c, 'wool', 1.035, 0.7, 0.158, 0.2, { thick: 0.008 });
          band(c, 'leather', 0.995, 1.025, 0.155, 0.104, -0.004, B.pelvis, { extra: { boneFn: c.torsoBone } });
          bundleParts(c.add, B.weaponR, mat(c.U.weaponR), c.lo);
        },
      },
    };
  }
  // dummy
  return { key: 'dummy', spec: { kind: 'dummy', H } };
}
