/**
 * Builds one merged, rigidly skinned geometry per kind variant: anatomy (torso, head with a
 * face, tapered limbs, fists, feet) plus clothing, armour, headgear, shield and weapon.
 * Everything is authored in "unit body" coordinates for a 1.76 m man (feet at y = 0,
 * facing +Z, the actor's left = +X) and scaled to the kind's height. Items held in the
 * hands are authored in their bone's local frame (origin at the fist centre, weapon along
 * +Z) and placed at the bone's rest position.
 */
import * as THREE from 'three';
import { sweep, slab, box, tube, polySmooth, thetas, mat, PartBuilder } from './geo.js';
import { B, restPose } from './rig.js';
import { FACE, faceV, METAL_TILES } from './atlas.js';

const METAL = new Set(METAL_TILES);
const PI = Math.PI;
const smooth = (a, b, v) => {
  const t = Math.min(1, Math.max(0, (v - a) / (b - a)));
  return t * t * (3 - 2 * t);
};

/** Rings from a compact table [y, rx, rz, dz?, dx?] with radius scale. */
function rings(table, cx = 0, cz = 0, k = 1, extra) {
  return table.map(([y, rx, rz, dz = 0, dx = 0]) => Object.assign({ y, rx: rx * k, rz: rz * k, x: cx + dx, z: cz + dz }, extra));
}

/** Cylindrical UVs with the front (θ = 0) at u = 0.5 and v by y between y0 and y1. */
const cylUv = (y0, y1, u0 = 0, u1 = 1) => (u, v, t, i, pt) => [u0 + (u1 - u0) * (0.5 + t / (2 * PI)), (pt[1] - y0) / (y1 - y0)];

/**
 * Builds a kind variant. spec: { kind, H, shoulders, hips, bulk, ... kit } (see KIT in kinds).
 * Returns { geometry, rest, info }.
 */
export function buildModel(spec, rects, quality) {
  const lo = quality === 'low';
  const S = (hi, low) => (lo ? low : hi);
  const build = { H: spec.H, shoulders: spec.shoulders || 1, hips: spec.hips || 1 };
  const rest = restPose(build);
  const unit = restPose({ H: 1.76, shoulders: build.shoulders, hips: build.hips });
  const U = unit.pos;
  const s = rest.s;
  const bulk = spec.bulk || 1;
  const Sm = new THREE.Matrix4().makeScale(s, s, s);
  const pb = new PartBuilder(rects);
  const add = (pt, tile, bone, m, extra) => {
    const M = m ? Sm.clone().multiply(m) : Sm.clone();
    pb.add(pt, Object.assign({ tile, bone, m: M, group: METAL.has(tile) ? 1 : 0 }, extra));
  };
  const torsoBone = (p) => (p.y < 1.005 * s ? B.pelvis : p.y < 1.15 * s ? B.spine : B.chest);
  const ctx = { add, U, S, lo, s, bulk, spec, torsoBone };

  if (spec.kind === 'dummy') {
    buildDummy(ctx);
  } else {
    buildBody(ctx);
    if (spec.head) spec.head(ctx);
    if (spec.kit) spec.kit(ctx);
  }
  const geometry = pb.build();
  return { geometry, rest, info: { triangles: geometry.userData.triangles, byGroup: geometry.userData.trianglesByGroup } };
}

// ---------------------------------------------------------------------------------------
// Anatomy

/** Rows of a profile table by index: `hi` on high/medium, `lo` on low (null keeps every row). */
export function sel(c, table, hi, lo) {
  const idx = c.lo ? lo : hi;
  return idx ? idx.map((i) => table[i]) : table;
}

/**
 * Torso, neck, arms, fists and legs. Ring counts are chosen per quality so the whole body
 * stays near 900 triangles on high and 480 on low, leaving the rest of the budget for kit.
 */
function buildBody(c) {
  const { add, U, S, bulk, spec, torsoBone } = c;
  const k = bulk;
  const t = spec.tiles;
  const segT = S(10, 7);
  // torso: hips (lower tile) then the trunk (upper tile)
  const chestShape = (amp, back) => (th) => 1 + amp * Math.max(0, Math.cos(th)) ** 2 - back * Math.max(0, -Math.cos(th)) ** 2;
  const hipsT = [
    [0.835, 0, 0, -0.005],
    [0.86, 0.105, 0.085, -0.005],
    [0.905, 0.148, 0.100, -0.008],
    [0.96, 0.160, 0.106, -0.01],
    [1.03, 0.151, 0.101, -0.004],
  ];
  add(sweep(rings(sel(c, hipsT, null, [0, 2, 3, 4]), 0, 0, k), { segs: segT, uvFn: cylUv(0.83, 1.03) }), t.hips, B.pelvis, null, { boneFn: torsoBone });
  const trunkT = [
    [1.0, 0.152, 0.101, -0.004],
    [1.10, 0.142, 0.095, 0.0],
    [1.19, 0.150, 0.104, 0.006],
    [1.275, 0.162, 0.112, 0.01],
    [1.345, 0.172, 0.112, 0.004],
    [1.405, 0.176, 0.100, -0.008],
    [1.445, 0.125, 0.080, -0.016],
    [1.468, 0.06, 0.055, -0.022],
    [1.472, 0, 0, -0.022],
  ];
  const trunk = rings(sel(c, trunkT, [0, 1, 2, 3, 4, 5, 6, 8], [0, 1, 3, 5, 6, 8]), 0, 0, k).map((r) => {
    if (r.y > 1.15 && r.y < 1.42) r.shape = chestShape(0.07, 0.05);
    if (r.y >= 1.40 && r.y < 1.45) r.shape = (th) => 1 - 0.08 * Math.max(0, Math.cos(th)) ** 2;
    return r;
  });
  add(sweep(trunk, { segs: segT, uvFn: cylUv(1.0, 1.47) }), t.trunk, B.chest, null, { boneFn: torsoBone });
  // neck
  const neckT = [[1.40, 0.058, 0.062, -0.026], [1.47, 0.052, 0.058, -0.024], [1.545, 0.048, 0.054, -0.018], [1.61, 0.046, 0.05, -0.01]];
  add(sweep(rings(sel(c, neckT, [0, 2, 3], [0, 3]), 0, 0, Math.min(k, 1.05)), { segs: S(7, 5), uvFn: cylUv(1.4, 1.61, 0, 0.25) }), t.neck || t.skin, B.neck, null, { boneFn: (p) => (p.y > 1.585 * c.s ? B.head : B.neck) });
  const limb = S(7, 5);
  // arms
  for (const side of [1, -1]) {
    const L = side > 0 ? 'L' : 'R';
    const x0 = U['upperArm' + L][0], z0 = U['upperArm' + L][2];
    const sl = spec.sleeve || 'none';
    const upperTile = sl === 'none' ? t.skin : t.sleeve;
    const upperT = [
      [1.474, 0, 0],
      [1.462, 0.036, 0.040],
      [1.432, 0.053, 0.057, 0, 0.006 * side],
      [1.37, 0.051, 0.053, 0.003, 0.005 * side],
      [1.29, 0.046, 0.048, 0.006],
      [1.205, 0.041, 0.043, 0.002],
      [1.142, 0.037, 0.039],
      [1.112, 0.022, 0.024],
      [1.104, 0, 0],
    ];
    const ka = k * 1.06;
    add(sweep(rings(sel(c, upperT, [0, 1, 2, 4, 6, 8], [0, 2, 4, 6, 8]), x0, z0, ka), { segs: limb, uvFn: cylUv(1.1, 1.48, 0, sl === 'short' ? 0.5 : 1) }), upperTile, B['upperArm' + L]);
    if (sl === 'short') {
      // the chiton's short sleeve, loose round the upper arm below the shoulder
      const sleeveT = [[1.445, 0.05, 0.054, 0, 0.004 * side], [1.39, 0.061, 0.064, 0.003, 0.006 * side], [1.325, 0.06, 0.062, 0.004, 0.004 * side], [1.318, 0.046, 0.049, 0.004]];
      add(sweep(rings(sel(c, sleeveT, null, [0, 2, 3]), x0, z0, k), { segs: limb, uvFn: cylUv(1.31, 1.45) }), t.sleeve, B['upperArm' + L]);
    }
    const foreTile = sl === 'long' ? t.sleeve : t.skin;
    const fx = U['forearm' + L][0];
    const foreT = sl === 'long'
      ? [[1.162, 0, 0], [1.148, 0.032, 0.034], [1.12, 0.043, 0.045], [1.05, 0.044, 0.043, 0.004], [0.96, 0.040, 0.040, 0.004], [0.905, 0.038, 0.040, 0.006], [0.895, 0.03, 0.032, 0.006], [0.89, 0, 0, 0.006]]
      : [[1.162, 0, 0], [1.148, 0.030, 0.032], [1.12, 0.040, 0.042], [1.055, 0.043, 0.040, 0.004, 0.003 * side], [0.98, 0.034, 0.031, 0.006], [0.905, 0.021, 0.028, 0.008], [0.88, 0.019, 0.026, 0.008], [0.872, 0, 0, 0.008]];
    add(sweep(rings(sel(c, foreT, [0, 2, 3, 4, 5, 7], [0, 2, 4, 7]), fx, z0, k * 1.05), { segs: limb, uvFn: cylUv(0.87, 1.165, 0, sl === 'long' ? 1 : 0.5) }), foreTile, B['forearm' + L]);
    // fist
    const hx = U['hand' + L][0];
    const fistT = [[0.888, 0, 0, 0.008], [0.882, 0.019, 0.026, 0.008], [0.862, 0.024, 0.040, 0.014], [0.835, 0.027, 0.046, 0.018], [0.806, 0.026, 0.044, 0.016], [0.787, 0.019, 0.034, 0.012], [0.781, 0, 0, 0.012]];
    add(sweep(rings(sel(c, fistT, [0, 2, 3, 4, 6], [0, 2, 4, 6]), hx - 0.002 * side, z0, Math.min(k, 1.06)), { segs: S(6, 5), uvFn: cylUv(0.78, 0.89, 0, 0.3) }), t.hand || t.skin, B['hand' + L]);
    // thumb across the front of the fist
    if (!c.lo) {
      const th = tube([[hx + 0.012 * side, 0.856, z0 + 0.05], [hx - 0.010 * side, 0.826, z0 + 0.06]], [0.0115, 0.0095], { segs: 4, capEnd: true });
      add(th, t.hand || t.skin, B['hand' + L]);
    }
  }
  // legs
  for (const side of [1, -1]) {
    const L = side > 0 ? 'L' : 'R';
    const x0 = U['thigh' + L][0];
    const tk = spec.trousers ? k * 1.16 : k;
    const thighT = [
      [0.99, 0, 0],
      [0.965, 0.072, 0.075],
      [0.90, 0.085, 0.086, 0.004, 0.004 * side],
      [0.80, 0.078, 0.079, 0.012, 0.006 * side],
      [0.68, 0.066, 0.068, 0.01, 0.004 * side],
      [0.565, 0.06, 0.061, 0.006],
      [0.495, 0.054, 0.056, 0.004],
      [0.462, 0.044, 0.045],
      [0.452, 0, 0],
    ];
    add(sweep(rings(sel(c, thighT, [0, 1, 2, 4, 6, 8], [0, 2, 4, 6, 8]), x0, 0, tk), { segs: S(8, 6), uvFn: cylUv(0.45, 0.99) }), t.thigh, B['thigh' + L]);
    const shinT = [
      [0.525, 0, 0, 0.004],
      [0.508, 0.049, 0.051, 0.006],
      [0.47, 0.053, 0.057, 0.008],
      [0.405, 0.055, 0.062, -0.010],
      [0.32, 0.049, 0.053, -0.013],
      [0.22, 0.037, 0.039, -0.006],
      [0.125, 0.029, 0.032, -0.002],
      [0.092, 0.027, 0.030, 0.0],
      [0.078, 0, 0, 0.0],
    ];
    if (spec.greaves) {
      // knee, the bronze greave (open at the top, sprung round the calf), the ankle
      add(sweep(rings([[0.525, 0, 0, 0.004], [0.505, 0.05, 0.053, 0.006], [0.468, 0, 0, 0.008]], x0, 0, k), { segs: S(7, 5), uvFn: cylUv(0.46, 0.53, 0, 0.4) }), t.skin, B['shin' + L]);
      const grT = [[0.505, 0.052, 0.056, 0.012], [0.47, 0.058, 0.064, 0.010], [0.405, 0.06, 0.068, -0.010], [0.32, 0.054, 0.059, -0.013], [0.22, 0.042, 0.044, -0.006], [0.13, 0.035, 0.038, -0.002], [0.112, 0, 0, 0]];
      add(sweep(rings(sel(c, grT, null, [0, 2, 4, 6]), x0, 0, k), { segs: S(8, 6), uvFn: cylUv(0.11, 0.52) }), 'greave', B['shin' + L]);
      add(sweep(rings(sel(c, [[0.125, 0.028, 0.031, -0.002], [0.092, 0.027, 0.030, 0.0], [0.078, 0, 0, 0.0]], null, [0, 2]), x0, 0, k), { segs: S(7, 5), uvFn: cylUv(0.07, 0.13, 0, 0.4) }), t.skin, B['shin' + L]);
    } else {
      const sk = spec.trousers ? k * 1.14 : k;
      add(sweep(rings(sel(c, shinT, [0, 2, 3, 4, 6, 8], [0, 2, 3, 5, 8]), x0, 0, sk), { segs: S(8, 6), uvFn: cylUv(0.07, 0.53) }), t.shin, B['shin' + L]);
    }
    // foot: swept along +Z (sweep y → +Z, sweep z → −Y)
    const fz = U['foot' + L][2];
    const boot = !!spec.boots;
    const fk = boot ? 1.04 : 0.92;
    const footT = [
      [-0.068, 0, 0, 0.040],
      [-0.06, 0.028, 0.030, 0.042],
      [-0.03, 0.033, 0.045, 0.045],
      [0.02, 0.036, 0.048, 0.042],
      [0.08, 0.04, 0.032, 0.03],
      [0.14, 0.042, 0.022, 0.019],
      [0.178, 0.033, 0.016, 0.014],
      [0.192, 0, 0, 0.012],
    ];
    const foot = sel(c, footT, [0, 1, 2, 4, 5, 6, 7], [0, 2, 4, 5, 7]).map(([z, rx, ry, yc]) => ({ y: z, rx: rx * fk, rz: ry * fk, x: 0, z: -yc, fn: rx > 0 ? (th, pt) => { if (pt[2] > 0) pt[2] = Math.min(pt[2], 0.0005); } : null }));
    // flat sole: sweep z positive maps to −Y; clamp so the sole lies on y = 0
    const m = mat([x0, 0, fz], [PI / 2, 0, 0]);
    add(sweep(foot, { segs: S(7, 5), uvFn: (u, v) => [u, v] }), t.foot, B['foot' + L], m);
    if (boot) {
      // ankle cuff of the shoe
      add(sweep(rings([[0.075, 0.032, 0.035], [0.16, 0.034, 0.037, -0.002], [0.17, 0.0, 0.0, -0.002]], x0, 0, 1.05), { segs: S(7, 5), uvFn: cylUv(0.07, 0.17, 0, 0.5) }), t.foot, B['shin' + L]);
    }
  }
}

/** Head with face; o: { face, beard: 'persian'|'greek'|'saka'|'short', hair: 'bun'|'long'|null, ears } */
export function buildHead(c, o) {
  const { add, U, S } = c;
  const hp = U.head;
  const M = mat(hp);
  // denser columns across the face (eye sockets at ±0.36 rad)
  const th = c.lo
    ? [-PI, -2.1, -1.2, -0.5, 0, 0.5, 1.2, 2.1, PI]
    : [-PI, -2.4, -1.75, -1.2, -0.75, -0.36, 0, 0.36, 0.75, 1.2, 1.75, 2.4, PI];
  const headT = [
    [-0.080, 0.0, 0.0, 0.045],
    [-0.074, 0.030, 0.038, 0.048],
    [-0.058, 0.052, 0.070, 0.030],
    [-0.030, 0.064, 0.086, 0.015],
    [0.002, 0.070, 0.096, 0.007],
    [0.040, 0.074, 0.099, 0.001],
    [0.074, 0.076, 0.100, -0.004],
    [0.110, 0.072, 0.095, -0.008],
    [0.140, 0.060, 0.080, -0.010],
    [0.160, 0.038, 0.050, -0.012],
    [0.168, 0, 0, -0.012],
  ];
  const headR = sel(c, headT, [0, 2, 3, 4, 5, 6, 7, 8, 10], [0, 3, 4, 6, 8, 10]).map(([y, rx, rz, z]) => {
    const r = { y, rx, rz, z };
    // flatter face, deeper skull at the back, eye sockets, cheekbones, brow
    r.shape = (t) => {
      const f = Math.cos(t);
      let m = 1 - 0.05 * Math.max(0, f) ** 6;
      if (y > 0.03 && y < 0.05) m -= 0.045 * Math.exp(-(((Math.abs(t) - 0.36) / 0.16) ** 2));
      if (y > 0.06 && y < 0.08) m += 0.025 * Math.exp(-(((Math.abs(t) - 0.3) / 0.35) ** 2));
      if (y > -0.01 && y < 0.01) m += 0.03 * Math.exp(-(((Math.abs(t) - 0.75) / 0.25) ** 2));
      if (y < -0.04) m -= 0.06 * Math.max(0, -f);
      return m;
    };
    return r;
  });
  add(sweep(headR, { thetas: th, uvFn: (u, v, t, i, pt) => [0.5 + t / (2 * PI), faceV(pt[1])] }), o.face, B.head, M);
  // nose
  const nose = polySmooth(
    [
      [0, 0.054, 0.093], [0.011, 0.045, 0.091], [-0.011, 0.045, 0.091],
      [0, 0.004, 0.124], [0.019, -0.005, 0.101], [-0.019, -0.005, 0.101], [0, -0.010, 0.108],
    ],
    [[0, 2, 3], [0, 3, 1], [1, 3, 4], [2, 5, 3], [3, 5, 6], [3, 6, 4]],
    (p) => [0.5 + Math.atan2(p[0], p[2]) / (2 * PI), faceV(p[1])],
  );
  add(nose, o.face, B.head, M);
  if (o.ears !== false && !c.lo) {
    for (const sd of [1, -1]) {
      const ear = sweep(rings([[-0.02, 0, 0], [-0.012, 0.007, 0.016], [0.014, 0.008, 0.02], [0.036, 0, 0]], 0, 0), { segs: 5, uvFn: () => [0.5 + (sd * 1.5) / (2 * PI), faceV(0.0)] });
      add(ear, o.face, B.head, mat([hp[0] + sd * 0.074, hp[1] + 0.012, hp[2] - 0.006], [0, 0, sd * 0.18]));
    }
  }
  if (o.beard) {
    const b = o.beard;
    const tile = o.beardTile || 'hair';
    let table;
    if (b === 'persian') {
      // long, square, curled beard of the Persepolis reliefs
      table = [[-0.158, 0.0, 0.0, 0.05], [-0.152, 0.036, 0.028, 0.05], [-0.12, 0.054, 0.048, 0.044], [-0.08, 0.066, 0.074, 0.03], [-0.04, 0.071, 0.091, 0.014], [-0.005, 0.073, 0.097, 0.006]];
    } else if (b === 'saka') {
      table = [[-0.195, 0.0, 0.0, 0.05], [-0.185, 0.03, 0.026, 0.05], [-0.13, 0.058, 0.048, 0.042], [-0.08, 0.072, 0.080, 0.028], [-0.04, 0.075, 0.095, 0.014], [-0.005, 0.076, 0.1, 0.006]];
    } else if (b === 'greek') {
      table = [[-0.135, 0.0, 0.0, 0.072], [-0.128, 0.014, 0.013, 0.07], [-0.1, 0.04, 0.038, 0.056], [-0.07, 0.06, 0.068, 0.036], [-0.04, 0.068, 0.088, 0.016], [-0.01, 0.07, 0.095, 0.008]];
    } else {
      table = [[-0.105, 0.0, 0.0, 0.05], [-0.098, 0.030, 0.026, 0.05], [-0.075, 0.058, 0.072, 0.03], [-0.04, 0.069, 0.090, 0.016], [-0.01, 0.071, 0.097, 0.008]];
    }
    const rows = table.length === 6 ? sel(c, table, [0, 2, 3, 4, 5], [0, 2, 4, 5]) : sel(c, table, [0, 2, 3, 4], [0, 2, 4]);
    const br = rows.map(([y, rx, rz, z]) => ({
      y, rx, rz, z,
      // full around the jaw, thin at the back so it tucks into the neck
      shape: (t) => (Math.abs(t) < 1.5 ? 1.02 : 1.02 - 0.75 * smooth(1.5, 2.3, Math.abs(t))),
      // the beard's upper edge drops below the lips at the front (moustache is painted)
      fn: (t, p) => {
        const k = Math.min(1, Math.max(0, (y + 0.1) / 0.095));
        p[1] -= 0.05 * k * (1 - smooth(0.3, 1.0, Math.abs(t)));
      },
    }));
    const bs = b === 'greek' || b === 'short' ? S(8, 6) : S(10, 7);
    add(sweep(br, { segs: bs, uvFn: (u, v, t, i, pt) => [0.5 + t / (2 * PI), 0.5 + pt[1] * 1.6] }), tile, B.head, M);
  }
  if (o.hair === 'bun' && !c.lo) {
    // knot of curls at the nape
    const bun = sweep(rings([[-0.055, 0, 0], [-0.04, 0.05, 0.03], [0.0, 0.06, 0.035], [0.035, 0, 0]], 0, 0), { segs: 6 });
    add(bun, 'hair', B.head, mat([hp[0], hp[1], hp[2] - 0.088]));
  } else if (o.hair === 'long') {
    // Spartan locks falling from under the helmet onto the back
    const locks = slab(
      (u, v, out) => {
        const a = PI + (u - 0.5) * 1.6;
        const y = 0.02 - (1 - v) * 0.2;
        const r = 0.072 + (1 - v) * 0.04;
        out[0] = Math.sin(a) * r * 1.05;
        out[1] = y;
        out[2] = Math.cos(a) * r * 1.0 - 0.025 - (1 - v) * 0.02;
      },
      S(4, 2), S(2, 1), 0.015, { flip: true, noEdges: c.lo },
    );
    add(locks, 'hair', B.neck, M, { boneFn: (p) => (p.y > hp[1] * c.s - 0.005 ? B.head : B.neck) });
  }
}

// ---------------------------------------------------------------------------------------
// Clothing helpers

/** Four skirt panels (FL, FR, BL, BR) hanging from the waist, each following its thigh. */
export function skirt(c, tile, yTop, yBot, rTop, rBot, o = {}) {
  const { add, S, bulk, s } = c;
  const nu = S(3, 2);
  const zc = o.zc ?? -0.006;
  const quads = [
    ['FL', 0, PI / 2], ['FR', -PI / 2, 0], ['BL', PI / 2, PI], ['BR', -PI, -PI / 2],
  ];
  for (const [name, a0, a1] of quads) {
    const pt = slab(
      (u, v, out) => {
        const a = a0 + (a1 - a0) * u;
        const y = yBot + (yTop - yBot) * v;
        const w = 1 - v;
        const r = (rTop + (rBot - rTop) * w) * bulk;
        const front = Math.cos(a);
        const rz = r * (o.depth ?? 0.72) * (1 + 0.12 * Math.max(0, front));
        out[0] = Math.sin(a) * r * (1 + (o.flare ?? 0.05) * w);
        out[1] = y;
        out[2] = Math.cos(a) * rz + zc + (o.lean ?? 0) * w;
      },
      nu, S(2, 1), o.thick ?? 0.008,
      { noEdges: true },
    );
    const boneName = 'skirt' + name;
    add(pt, tile, B[boneName], null, {
      uvScale: 1,
      boneFn: (p) => (p.y > (yTop - 0.01) * s ? B.pelvis : B[boneName]),
    });
  }
}

/** A plain ring band (belt, headband) around y with radii. */
export function band(c, tile, y0, y1, rx, rz, zc, bone, o = {}) {
  const r = [
    { y: y0, rx: rx * 0.96, rz: rz * 0.96, z: zc, x: o.x || 0 },
    { y: y0 + 0.002, rx, rz, z: zc, x: o.x || 0 },
    { y: y1 - 0.002, rx, rz, z: zc, x: o.x || 0 },
    { y: y1, rx: rx * 0.96, rz: rz * 0.96, z: zc, x: o.x || 0 },
  ];
  c.add(sweep(r, { segs: o.segs || c.S(9, 6), closed: true, uvFn: o.uvFn }), tile, bone, o.m || null, o.extra);
}

// ---------------------------------------------------------------------------------------
// Dummy: a post with a crossbar, a sack head and a wicker shield. Wobbles about its base.

function buildDummy(c) {
  const { add, S } = c;
  const post = rings([[0.0, 0.06, 0.06], [0.6, 0.055, 0.057], [1.2, 0.05, 0.052], [1.62, 0.045, 0.047], [1.63, 0, 0]], 0, 0);
  add(sweep(post, { segs: S(7, 5), uvFn: (u, v) => [u, v] }), 'wood', B.spine, null, { boneFn: (p) => (p.y < 0.05 ? B.pelvis : p.y > 1.2 ? B.chest : B.spine) });
  // crossbar
  const bar = sweep(rings([[-0.52, 0, 0], [-0.50, 0.035, 0.03], [0.50, 0.035, 0.03], [0.52, 0, 0]]), { segs: S(6, 4), uvFn: (u, v) => [u, v] });
  add(bar, 'wood', B.chest, mat([0, 1.36, 0], [0, 0, -PI / 2]));
  // straw-stuffed sack head
  const head = sweep(rings([[-0.11, 0, 0], [-0.1, 0.06, 0.055], [-0.04, 0.095, 0.085], [0.04, 0.09, 0.082], [0.1, 0.05, 0.05], [0.115, 0, 0]]), { segs: S(9, 6), uvFn: (u, v) => [u, v] });
  add(head, 'straw', B.head, mat([0, 1.70, 0]));
  // straw torso bundle tied to the post
  const body = sweep(rings([[1.0, 0, 0], [1.02, 0.12, 0.1], [1.15, 0.16, 0.12], [1.32, 0.17, 0.12], [1.42, 0.12, 0.09], [1.45, 0, 0]]), { segs: S(10, 6), uvFn: (u, v) => [u, v] });
  add(body, 'straw', B.chest);
  band(c, 'leatherDark', 1.10, 1.13, 0.155, 0.115, 0, B.chest);
  // wicker shield hung on the left of the crossbar
  const sh = slab((u, v, out) => {
    const x = (u - 0.5) * 0.56, y = (v - 0.5) * 0.82;
    out[0] = x;
    out[1] = y;
    out[2] = -0.05 * (x / 0.28) ** 2;
  }, S(4, 2), S(3, 2), 0.025, {});
  add(sh, 'wicker', B.chest, mat([0.30, 1.18, 0.15], [0.12, 0.25, 0]));
  // crossed feet planks on the ground
  add(box(0.9, 0.06, 0.1), 'woodDark', B.pelvis, mat([0, 0.03, 0], [0, 0.6, 0]));
  add(box(0.9, 0.06, 0.1), 'woodDark', B.pelvis, mat([0, 0.03, 0], [0, -0.97, 0]));
}

// ---------------------------------------------------------------------------------------
// Items (bone-local frames: origin at the fist centre, +Z the weapon's long axis)

/**
 * Spear: { length, grip (distance from butt to the hand), head: 'leaf', butt: 'spike'|'pomegranate'|'knob',
 * headTile, buttTile, shaftR }. Returns parts added to the given bone. Also used for props
 * (with bone 0 and a non-skinned builder).
 */
export function spearParts(add, bone, o, M = new THREE.Matrix4(), lo = false) {
  const L = o.length, g = o.grip, r = o.shaftR || 0.014;
  const z0 = -g, z1 = L - g;
  const headLen = o.headLen || 0.24;
  const toZ = new THREE.Matrix4().makeRotationX(PI / 2);
  const T = (m) => M.clone().multiply(m || toZ);
  // shaft: sweep along y then rotate onto +Z (sweep y → +Z)
  const buttLen = o.butt === 'spike' ? 0.26 : o.butt === 'pomegranate' ? 0.07 : 0.04;
  const shaft = sweep(
    [
      { y: z0 + buttLen - 0.02, rx: r * 0.92, rz: r * 0.92 },
      { y: z1 - headLen + 0.03, rx: r * 0.85, rz: r * 0.85 },
    ],
    { segs: lo ? 4 : 6, uvFn: (u, v) => [u * 0.25, v] },
  );
  add(shaft, o.shaftTile || 'wood', bone, T());
  // head: leaf blade with a midrib (diamond section)
  const hb = z1 - headLen;
  const head = sweep(
    [
      { y: hb - 0.005, rx: r * 1.15, rz: r * 1.15 },
      { y: hb + 0.06, rx: r * 0.95, rz: r * 0.95 },
      { y: hb + 0.065, rx: r * 1.0, rz: r * 0.75 },
      { y: hb + headLen * 0.42, rx: (o.headW || 0.026), rz: 0.0055 },
      { y: hb + headLen * 0.8, rx: (o.headW || 0.026) * 0.55, rz: 0.004 },
      { y: z1, rx: 0, rz: 0 },
    ],
    { segs: 4, a0: -PI, a1: PI, uvFn: (u, v) => [u * 0.3, v] },
  );
  add(head, o.headTile || 'iron', bone, T());
  if (o.butt === 'spike') {
    const sp = sweep(
      [
        { y: z0, rx: 0, rz: 0 },
        { y: z0 + 0.04, rx: 0.008, rz: 0.008 },
        { y: z0 + buttLen - 0.06, rx: r * 0.95, rz: r * 0.95 },
        { y: z0 + buttLen, rx: r * 1.15, rz: r * 1.15 },
        { y: z0 + buttLen + 0.005, rx: 0, rz: 0 },
      ],
      { segs: 4, uvFn: (u, v) => [u * 0.3, v] },
    );
    add(sp, o.buttTile || 'bronze', bone, T());
  } else if (o.butt === 'pomegranate') {
    const pg = sweep(
      [
        { y: z0 - 0.01, rx: 0, rz: 0 },
        { y: z0 - 0.002, rx: 0.012, rz: 0.012 },
        { y: z0 + 0.02, rx: 0.032, rz: 0.032 },
        { y: z0 + 0.048, rx: 0.03, rz: 0.03 },
        { y: z0 + 0.062, rx: 0.012, rz: 0.012 },
        { y: z0 + 0.068, rx: 0.016, rz: 0.016, crease: true },
        { y: z0 + 0.078, rx: 0, rz: 0 },
      ],
      { segs: lo ? 5 : 7, uvFn: (u, v) => [u * 0.4, v * 0.4] },
    );
    add(pg, o.buttTile || 'gold', bone, T());
  } else {
    const kn = sweep(
      [{ y: z0, rx: 0, rz: 0 }, { y: z0 + 0.004, rx: r * 1.3, rz: r * 1.3 }, { y: z0 + 0.04, rx: r * 1.2, rz: r * 1.2 }, { y: z0 + 0.045, rx: 0, rz: 0 }],
      { segs: 5, uvFn: (u, v) => [u * 0.3, v * 0.3] },
    );
    add(kn, o.buttTile || 'bronzeDark', bone, T());
  }
}

/** Sagaris: haft along +Z, narrow blade on +Y, hammer/point on −Y. */
export function sagarisParts(add, bone, o, M = new THREE.Matrix4(), lo = false) {
  const L = o.length || 0.78, g = o.grip || 0.18;
  const z0 = -g, z1 = L - g;
  const toZ = new THREE.Matrix4().makeRotationX(PI / 2);
  const T = (m) => M.clone().multiply(m || toZ);
  const haft = sweep(
    [{ y: z0, rx: 0, rz: 0 }, { y: z0 + 0.005, rx: 0.016, rz: 0.016 }, { y: z0 + 0.04, rx: 0.017, rz: 0.017 }, { y: z1 - 0.02, rx: 0.014, rz: 0.014 }, { y: z1 + 0.012, rx: 0.0, rz: 0.0 }],
    { segs: lo ? 5 : 6, uvFn: (u, v) => [u * 0.25, v] },
  );
  add(haft, 'woodDark', bone, T());
  // socket collar
  const hz = z1 - 0.05;
  add(box(0.04, 0.05, 0.075), 'ironDark', bone, M.clone().multiply(mat([0, 0, hz], [0, 0, 0])));
  // blade: tapering toward the edge, flared edge
  const blade = slab(
    (u, v, out) => {
      const w = 0.028 + 0.035 * v;
      out[0] = 0;
      out[1] = 0.02 + v * 0.13;
      out[2] = hz + (u - 0.5) * w * 2 + v * 0.01;
    },
    1, lo ? 1 : 2, 0.008, { flip: false },
  );
  add(blade, 'iron', bone, M.clone().multiply(mat([-0.004, 0, 0])));
  // back hammer / point
  const back = sweep(
    [{ y: 0.02, rx: 0.016, rz: 0.016 }, { y: 0.07, rx: 0.013, rz: 0.013 }, { y: 0.095, rx: 0, rz: 0 }],
    { segs: 4, uvFn: (u, v) => [u * 0.3, v * 0.3] },
  );
  add(back, 'ironDark', bone, M.clone().multiply(mat([0, 0, hz], [PI, 0, 0])));
}

/** Composite (recurved) bow in the left-hand item frame: limbs along ±Z, string on −Y. */
export function bowParts(add, bone, nockBone, o = {}, lo = false) {
  const pts = [];
  const n = lo ? 6 : 10;
  for (let i = 0; i <= n; i++) {
    const t = (i / n) * 2 - 1; // −1 bottom tip .. 1 top tip
    const a = Math.abs(t);
    // limbs sweep back toward the archer, tips recurve forward
    const y = -0.11 * Math.sin(Math.min(1, a / 0.82) * PI * 0.5) ** 1.3 + 0.07 * smooth(0.78, 1, a);
    pts.push([0, y, t * 0.52]);
  }
  const radii = pts.map((p, i) => {
    const a = Math.abs((i / n) * 2 - 1);
    const r = 0.013 - 0.007 * a;
    return [r * 0.8, r * 1.15];
  });
  add(tube(pts, radii, { segs: lo ? 4 : 5, up: [0, 1, 0], capStart: true, capEnd: true }), 'horsehair', bone, o.M, { sub: [0.3, 0.2, 0.4, 0.8] });
  // grip wrap
  add(tube([[0, 0.002, -0.06], [0, 0.002, 0.06]], [0.017], { segs: 5 }), 'leatherDark', bone, o.M);
  // string: two halves; tip vertices on the bow, nock vertices on the nock bone
  const tipTop = pts[n], tipBot = pts[0];
  for (const tip of [tipTop, tipBot]) {
    const sx = 0.0012;
    const strand = polySmooth(
      [[sx, tip[1], tip[2]], [-sx, tip[1], tip[2]], [sx, tip[1] - 0.0005, 0], [-sx, tip[1] - 0.0005, 0],
        [0, tip[1] + sx, tip[2]], [0, tip[1] - sx, tip[2]], [0, tip[1] + sx - 0.0005, 0], [0, tip[1] - sx - 0.0005, 0]],
      [[0, 1, 3], [0, 3, 2], [4, 5, 7], [4, 7, 6], [1, 0, 2], [1, 2, 3], [5, 4, 6], [5, 6, 7]],
      () => [0.5, 0.5],
    );
    const restZ = 0.0;
    add(strand, 'wool', bone, o.M, {
      boneFn: (p, k) => (Math.abs(strand.p[k * 3 + 2] - restZ) < 0.01 ? nockBone : bone),
    });
  }
  return { tipY: tipTop[1] };
}

/** Rectangular wicker gerron or violin-shaped Persian shield in the grip frame (+Z = facing out). */
export function gerronParts(add, bone, o, lo = false) {
  const W = o.width || 0.5, H = o.height || 0.8;
  const violin = o.shape === 'violin';
  const nu = lo ? 2 : 4, nv = lo ? 2 : 5;
  const sh = slab(
    (u, v, out) => {
      const y = (v - 0.5) * H;
      let w = W / 2;
      if (violin) {
        const yy = y / (H / 2);
        w *= 0.78 + 0.22 * Math.abs(yy) ** 0.7 - 0.28 * Math.exp(-((yy / 0.22) ** 2));
        w *= 1 - 0.35 * smooth(0.8, 1, Math.abs(yy));
      } else {
        w *= 1 - 0.12 * smooth(0.85, 1, Math.abs(y / (H / 2)));
      }
      const x = (u - 0.5) * 2 * w;
      out[0] = x;
      out[1] = y + (o.dy || 0);
      out[2] = (o.dz ?? 0.05) - (o.curve ?? 0.07) * (x / (W / 2)) ** 2;
    },
    nu, nv, o.thick || 0.022,
    { backUv: (u, v) => [u, v], edgeUv: [0, 0, 0.1, 1] },
  );
  add(sh, o.tile || 'wicker', bone, o.M);
  // leather binding along the top and bottom edges, following the curve
  if (!lo && !violin) {
    for (const yy of [-1, 1]) {
      const pts = [];
      for (let i = 0; i <= 4; i++) {
        const x = (i / 4 - 0.5) * W * 0.88;
        pts.push([x, yy * (H / 2 - 0.012) + (o.dy || 0), (o.dz ?? 0.05) - (o.curve ?? 0.07) * (x / (W / 2)) ** 2 - (o.thick || 0.022) * 0.5]);
      }
      add(tube(pts, [[0.012, 0.016]], { segs: 3, capStart: true, capEnd: true }), 'leatherDark', bone, o.M);
    }
  }
  // grip bar behind the centre
  add(box(0.12, 0.022, 0.022), 'leatherDark', bone, (o.M || new THREE.Matrix4()).clone().multiply(mat([0, 0, 0.0])));
}

/**
 * Hoplite aspis on the left forearm. Frame: origin at the hand (antilabe), +X toward the
 * elbow, +Z facing out. Centre at (0.30, 0, −0.05), diameter 0.91, deep bowl, offset rim.
 */
export function aspisParts(add, bone, o, lo = false) {
  const R = 0.455, Rr = 0.4;
  const segs = lo ? 12 : 20;
  const zf = (r) => 0.135 * Math.max(0, 1 - (r / Rr) ** 2) ** 0.55 + 0.022;
  const center = [0.30, 0, -0.05];
  // sweep along +y = shield normal, rotated so y → +Z
  const M = (o.M || new THREE.Matrix4()).clone().multiply(mat(center, [PI / 2, 0, 0]));
  const planar = (u, v, t, i, pt) => [0.5 + pt[0] / (2 * R), 0.5 - pt[2] / (2 * R)];
  // back of the bowl (wood, painted dark), from the inside centre out to the rim lip
  const back = (lo ? [0] : [0, 0.24]).map((r) => ({ y: zf(r) - 0.014, rx: r, rz: r }));
  back.push({ y: -0.012, rx: R - 0.006, rz: R - 0.006 });
  add(sweep(back.reverse(), { segs, uvFn: planar, flip: true }), 'woodDark', bone, M);
  // face: rim lip, the flat offset rim (porpax side ring), then the bronze-faced bowl
  const front = [
    { y: -0.012, rx: R - 0.006, rz: R - 0.006 },
    { y: 0.0, rx: R, rz: R, crease: true },
    { y: 0.014, rx: Rr, rz: Rr, crease: true },
  ];
  for (const r of lo ? [0.22, 0] : [0.31, 0.17, 0]) front.push({ y: zf(r), rx: r, rz: r });
  add(sweep(front, { segs, uvFn: planar, flip: false }), o.face, bone, M);
  // porpax (arm band) and antilabe (hand grip)
  add(box(0.05, 0.10, 0.028), 'bronzeDark', bone, (o.M || new THREE.Matrix4()).clone().multiply(mat([0.30, 0, 0.055])));
  add(box(0.03, 0.12, 0.02), 'leatherDark', bone, (o.M || new THREE.Matrix4()).clone().multiply(mat([0.0, 0, 0.045])));
}

/** Tall rectangular wicker spara of the sparabara. */
export function sparaParts(add, bone, o, lo = false) {
  gerronParts(add, bone, { width: 0.66, height: 1.58, dy: -0.36, dz: 0.06, curve: 0.05, thick: 0.03, tile: 'wicker', M: o.M }, lo);
}

/** Gorytos (bow case with the bow tip showing), on the given bone, in mesh space. */
export function gorytosParts(c, bone, pos, rot, o = {}) {
  const { add, S } = c;
  const M = mat(pos, rot);
  const g = slab(
    (u, v, out) => {
      const y = (v - 0.5) * 0.52;
      const w = 0.11 * (1 - 0.45 * smooth(-0.26, 0.26, -y)) * (1 - 0.3 * (y > 0.2 ? (y - 0.2) / 0.06 : 0));
      const x = (u - 0.5) * 2 * w;
      out[0] = 0.03 * Math.cos((u - 0.5) * PI);
      out[1] = y;
      out[2] = x;
    },
    S(3, 2), S(3, 2), 0.045, { flip: true },
  );
  add(g, o.tile || 'leather', bone, M);
  // bow tip and arrow fletchings poking out of the top
  add(tube([[0.0, 0.24, 0.03], [-0.01, 0.34, 0.05], [0.0, 0.40, 0.08]], [0.012, 0.01, 0.007], { segs: 4, capEnd: true }), 'horsehair', bone, M, { sub: [0.3, 0.2, 0.4, 0.8] });
  if (o.arrows) {
    add(box(0.04, 0.09, 0.08), 'wool', bone, M.clone().multiply(mat([0, 0.29, -0.045])));
  }
}

/** Spear bundle carried by the helot (in the weaponR frame, along +Z). */
export function bundleParts(add, bone, M, lo = false) {
  const n = lo ? 3 : 5;
  for (let i = 0; i < n; i++) {
    const a = (i / n) * PI * 2;
    const off = [Math.cos(a) * 0.022, Math.sin(a) * 0.022, (i % 2) * 0.12 - 0.06];
    spearParts(add, bone, { length: 2.4, grip: 0.9, butt: i % 2 ? 'spike' : 'knob', headTile: 'iron', shaftR: 0.012, headLen: 0.2 }, M.clone().multiply(mat(off)), true);
  }
  add(tube([[0, 0, -0.05], [0, 0, 0.05]], [0.05], { segs: 6 }), 'leatherDark', bone, M);
}
