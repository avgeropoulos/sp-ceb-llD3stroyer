/**
 * Procedural geometry for the viewmodel: arms (rigid-skinned parts), fists, the xiphos, the dory,
 * the aspis and a stuck arrow. Every part is authored in its own bone/grip frame in real metres.
 *
 * Right-hand grip frame: +Y along the handle toward the thumb (the sword blade), +Z toward the
 * wrist, +X out of the back of the hand. Limb bones run along +Y from the proximal joint.
 * Aspis frame: the bronze face looks down -Z, the bearer is on +Z, the rim's back is the z = 0 plane.
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/BufferGeometryUtils.js';
import { ARM_UV, WEAPON_UV, SHIELD_UV_R, SHIELD_BANDS, ARROW_UV } from './paint.js';

const _v = new THREE.Vector3();

// ---------------------------------------------------------------------------------------------
// Generic builders

/**
 * Lofts elliptical rings through stations into an indexed geometry with smooth normals.
 * station: { c:[3], ax:[3], ay:[3] (unit cross-section axes), a, b (semi-axes), v (0..1), prof?(θ) }.
 * Point(θ) = c + (cos θ·a·ax + sin θ·b·ay)·prof(θ); u runs 0→1 with θ 0→2π.
 * uv = [u0, v0, u1, v1] maps (u, v) into an atlas rect. capStart/capEnd: rounded cap ring counts.
 */
export function loft(stations, seg, uv, { capStart = 0, capEnd = 0, capLen = 1 } = {}) {
  const st = stations.slice();
  const tangentAt = (i) => {
    const a = st[Math.max(0, i - 1)].c;
    const b = st[Math.min(st.length - 1, i + 1)].c;
    return _v.set(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize().toArray();
  };
  const makeCap = (base, dir, n, sign) => {
    const out = [];
    const r = Math.min(base.a, base.b) * capLen;
    for (let k = 1; k <= n; k++) {
      const phi = (k / n) * Math.PI / 2;
      const s = k === n ? 0 : Math.cos(phi);
      const off = Math.sin(phi) * r * sign;
      out.push({
        c: [base.c[0] + dir[0] * off, base.c[1] + dir[1] * off, base.c[2] + dir[2] * off],
        ax: base.ax, ay: base.ay, a: base.a * s, b: base.b * s, v: base.v, prof: base.prof, pole: k === n ? dir.map((x) => x * sign) : null,
      });
    }
    return out;
  };
  if (capStart > 0) st.unshift(...makeCap(stations[0], tangentAt(0), capStart, -1).reverse());
  if (capEnd > 0) {
    const t = (() => {
      const n = stations.length;
      const a = stations[n - 2].c;
      const b = stations[n - 1].c;
      return _v.set(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize().toArray();
    })();
    st.push(...makeCap(stations[stations.length - 1], t, capEnd, 1));
  }
  const ring = seg + 1;
  const pos = new Float32Array(st.length * ring * 3);
  const uvs = new Float32Array(st.length * ring * 2);
  const [u0, v0, u1, v1] = uv;
  for (let i = 0; i < st.length; i++) {
    const s = st[i];
    for (let j = 0; j <= seg; j++) {
      const th = (j / seg) * Math.PI * 2;
      const m = s.prof ? s.prof(th) : 1;
      const cx = Math.cos(th) * s.a * m;
      const sy = Math.sin(th) * s.b * m;
      const k = (i * ring + j) * 3;
      pos[k] = s.c[0] + s.ax[0] * cx + s.ay[0] * sy;
      pos[k + 1] = s.c[1] + s.ax[1] * cx + s.ay[1] * sy;
      pos[k + 2] = s.c[2] + s.ax[2] * cx + s.ay[2] * sy;
      const q = (i * ring + j) * 2;
      uvs[q] = u0 + (u1 - u0) * (j / seg);
      uvs[q + 1] = v0 + (v1 - v0) * s.v;
    }
  }
  const idx = [];
  for (let i = 0; i < st.length - 1; i++) {
    for (let j = 0; j < seg; j++) {
      const a = i * ring + j;
      const b = a + ring;
      idx.push(a, b, a + 1, a + 1, b, b + 1);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  const nrm = g.attributes.normal.array;
  for (let i = 0; i < st.length; i++) {
    const a = i * ring * 3;
    const b = (i * ring + seg) * 3;
    for (let c = 0; c < 3; c++) nrm[a + c] = nrm[b + c] = (nrm[a + c] + nrm[b + c]) / 2;
    if (st[i].pole) {
      for (let j = 0; j <= seg; j++) {
        const k = (i * ring + j) * 3;
        nrm[k] = st[i].pole[0];
        nrm[k + 1] = st[i].pole[1];
        nrm[k + 2] = st[i].pole[2];
      }
    }
  }
  for (let i = 0; i < nrm.length; i += 3) {
    _v.fromArray(nrm, i).normalize().toArray(nrm, i);
  }
  return g;
}

/**
 * Lathe around the local Z axis: profile = [[r, z], …] (in order), uvFn(r, θ, k) → [u, v].
 * flip reverses the facing (normals toward -Z side vs +Z side). Normals are smooth along the
 * profile; split profiles where a crease is wanted.
 */
export function lathe(profile, seg, uvFn, flip = false) {
  const ring = seg + 1;
  const pos = new Float32Array(profile.length * ring * 3);
  const uvs = new Float32Array(profile.length * ring * 2);
  for (let i = 0; i < profile.length; i++) {
    const [r, z] = profile[i];
    for (let j = 0; j <= seg; j++) {
      const th = (j / seg) * Math.PI * 2;
      const k = (i * ring + j) * 3;
      pos[k] = Math.cos(th) * r;
      pos[k + 1] = Math.sin(th) * r;
      pos[k + 2] = z;
      const [u, v] = uvFn(r, th, i);
      uvs[(i * ring + j) * 2] = u;
      uvs[(i * ring + j) * 2 + 1] = v;
    }
  }
  const idx = [];
  for (let i = 0; i < profile.length - 1; i++) {
    for (let j = 0; j < seg; j++) {
      const a = i * ring + j;
      const b = a + ring;
      if (flip) idx.push(a, a + 1, b, a + 1, b + 1, b);
      else idx.push(a, b, a + 1, a + 1, b, b + 1);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  const nrm = g.attributes.normal.array;
  for (let i = 0; i < profile.length; i++) {
    const a = i * ring * 3;
    const b = (i * ring + seg) * 3;
    for (let c = 0; c < 3; c++) nrm[a + c] = nrm[b + c] = (nrm[a + c] + nrm[b + c]) / 2;
    if (profile[i][0] < 1e-6) {
      // Pole: average the ring so the centre is smooth.
      let x = 0;
      let y = 0;
      let z = 0;
      for (let j = 0; j < seg; j++) {
        const k = (i * ring + j) * 3;
        x += nrm[k];
        y += nrm[k + 1];
        z += nrm[k + 2];
      }
      _v.set(x, y, z).normalize();
      for (let j = 0; j <= seg; j++) _v.toArray(nrm, (i * ring + j) * 3);
    }
  }
  return g;
}

/** Grid surface from a function f(i, j) → [x, y, z], u/v in atlas rect. Normals computed. */
function gridSurface(nu, nv, f, uv, flip = false) {
  const pos = new Float32Array((nu + 1) * (nv + 1) * 3);
  const uvs = new Float32Array((nu + 1) * (nv + 1) * 2);
  const [u0, v0, u1, v1] = uv;
  for (let j = 0; j <= nv; j++) {
    for (let i = 0; i <= nu; i++) {
      const k = j * (nu + 1) + i;
      const p = f(i / nu, j / nv);
      pos[k * 3] = p[0];
      pos[k * 3 + 1] = p[1];
      pos[k * 3 + 2] = p[2];
      uvs[k * 2] = u0 + (u1 - u0) * (p[3] ?? i / nu);
      uvs[k * 2 + 1] = v0 + (v1 - v0) * (p[4] ?? j / nv);
    }
  }
  const idx = [];
  for (let j = 0; j < nv; j++) {
    for (let i = 0; i < nu; i++) {
      const a = j * (nu + 1) + i;
      const b = a + nu + 1;
      if (flip) idx.push(a, a + 1, b, a + 1, b + 1, b);
      else idx.push(a, b, a + 1, a + 1, b, b + 1);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.BufferAttribute(uvs, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

/** Adds rigid skinning attributes (one bone, weight 1). */
export function skinTo(g, bone) {
  const n = g.attributes.position.count;
  const si = new Uint16Array(n * 4);
  const sw = new Float32Array(n * 4);
  for (let i = 0; i < n; i++) {
    si[i * 4] = bone;
    sw[i * 4] = 1;
  }
  g.setAttribute('skinIndex', new THREE.BufferAttribute(si, 4));
  g.setAttribute('skinWeight', new THREE.BufferAttribute(sw, 4));
  return g;
}

/** Mirrors a geometry across x = 0 (positions and normals), fixing the winding. */
export function mirrorX(g) {
  const m = g.clone();
  const p = m.attributes.position.array;
  const n = m.attributes.normal.array;
  for (let i = 0; i < p.length; i += 3) {
    p[i] = -p[i];
    n[i] = -n[i];
  }
  const idx = m.index.array;
  for (let i = 0; i < idx.length; i += 3) {
    const t = idx[i + 1];
    idx[i + 1] = idx[i + 2];
    idx[i + 2] = t;
  }
  return m;
}

const sub = (rect, a, b, c, d) => [rect[0] + (rect[2] - rect[0]) * a, rect[1] + (rect[3] - rect[1]) * b, rect[0] + (rect[2] - rect[0]) * c, rect[1] + (rect[3] - rect[1]) * d];
const X = [1, 0, 0];
const Y = [0, 1, 0];
const Z = [0, 0, 1];

// ---------------------------------------------------------------------------------------------
// Arms (authored for the right side; the left is mirrored)

/**
 * Forearm along +Y from the elbow (0) to the wrist (len). X = radial (thumb) side, Z = palmar.
 * Includes the leather wrist wrap and an optional bronze ring.
 */
export function forearmParts(len, { ring = false } = {}) {
  // [t, a (radial-ulnar half width), b (dorso-palmar half depth), zOffset]
  const prof = [
    [0, 0.041, 0.039, 0],
    [0.12, 0.045, 0.041, -0.002],
    [0.3, 0.046, 0.040, -0.003],
    [0.5, 0.041, 0.034, -0.002],
    [0.7, 0.035, 0.027, 0],
    [0.86, 0.031, 0.022, 0.001],
    [1, 0.0295, 0.0205, 0.001],
  ];
  const st = prof.map(([t, a, b, dz]) => ({ c: [0, t * len, dz], ax: X, ay: Z, a, b, v: 0.5 * (1 - t) }));
  const skin = loft(st, 20, ARM_UV.arm, { capStart: 4, capLen: 0.7 });
  // Leather wrap from 0.74·len to just past the wrist; slightly flared at the ends.
  const wrap = [];
  const n = 8;
  for (let i = 0; i <= n; i++) {
    const t = 0.74 + (0.31 * i) / n;
    const tt = Math.min(1, t);
    const a = (tt < 0.86 ? 0.035 + (0.031 - 0.035) * (tt - 0.7) / 0.16 : 0.031 + (0.0295 - 0.031) * (tt - 0.86) / 0.14) + 0.0042;
    const b = (tt < 0.86 ? 0.027 + (0.022 - 0.027) * (tt - 0.7) / 0.16 : 0.022 + (0.0205 - 0.022) * (tt - 0.86) / 0.14) + 0.0042;
    const flare = i === 0 || i === n ? 0.0016 : 0;
    wrap.push({ c: [0, t * len, 0.001], ax: X, ay: Z, a: a + flare, b: b + flare, v: i / n });
  }
  const leather = loft(wrap, 20, ARM_UV.leather);
  const parts = [skin, leather];
  if (ring) {
    // A bronze bracelet hugging the arm just above the wrap.
    const rs = [];
    const at = (t) => {
      for (let i = 1; i < prof.length; i++) {
        if (t <= prof[i][0]) {
          const k = (t - prof[i - 1][0]) / (prof[i][0] - prof[i - 1][0]);
          return [prof[i - 1][1] + (prof[i][1] - prof[i - 1][1]) * k, prof[i - 1][2] + (prof[i][2] - prof[i - 1][2]) * k, prof[i - 1][3] + (prof[i][3] - prof[i - 1][3]) * k];
        }
      }
      return prof[prof.length - 1].slice(1);
    };
    for (let i = 0; i <= 6; i++) {
      const t = 0.675 + 0.05 * i / 6;
      const [a, b, dz] = at(t);
      const bulge = 0.0006 + Math.sin((i / 6) * Math.PI) * 0.0028;
      rs.push({ c: [0, t * len, dz], ax: X, ay: Z, a: a + bulge, b: b + bulge, v: i / 6 });
    }
    parts.push(loft(rs, 20, ARM_UV.bronze));
  }
  return parts;
}

/** Upper arm along +Y from the shoulder (0) to the elbow (len), with the crimson chiton sleeve. */
export function upperArmParts(len) {
  const prof = [
    [0, 0.054, 0.05],
    [0.25, 0.051, 0.048],
    [0.5, 0.048, 0.046],
    [0.8, 0.044, 0.041],
    [1, 0.041, 0.038],
  ];
  const st = prof.map(([t, a, b]) => ({ c: [0, t * len, 0], ax: X, ay: Z, a, b, v: 0.5 + 0.5 * (1 - t) }));
  const skin = loft(st, 18, ARM_UV.arm, { capStart: 3, capEnd: 4, capLen: 0.8 });
  const sleeve = [];
  for (let i = 0; i <= 6; i++) {
    const t = -0.05 + 0.42 * i / 6;
    const hem = i === 6 ? -0.002 : 0;
    const fold = (th) => 1 + 0.06 * Math.sin(th * 5 + i * 0.7) * (i / 6);
    sleeve.push({ c: [0, t * len, 0], ax: X, ay: Z, a: 0.062 + 0.006 * (i / 6) + hem, b: 0.058 + 0.006 * (i / 6) + hem, v: i / 6, prof: fold });
  }
  const cloth = loft(sleeve, 18, ARM_UV.cloth);
  return [skin, cloth];
}

// ---------------------------------------------------------------------------------------------
// Fist (right hand, grip frame), gripping a handle of radius ~0.016 along Y.

function catmull(pts, n) {
  const out = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[Math.min(pts.length - 1, i + 2)];
    for (let k = 0; k < n; k++) {
      const t = k / n;
      const t2 = t * t;
      const t3 = t2 * t;
      const q = [];
      for (let c = 0; c < p1.length; c++) {
        q.push(0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3));
      }
      out.push(q);
    }
  }
  out.push(pts[pts.length - 1].slice());
  return out;
}

/** A tube along a polyline of [x, y, z, radius] (radius may be [a, b]), cross-section axis `side`. */
function fingerTube(pts, side, uv, outwardFn, seg = 12) {
  const path = catmull(pts, 4);
  let len = 0;
  const acc = [0];
  for (let i = 1; i < path.length; i++) {
    len += Math.hypot(path[i][0] - path[i - 1][0], path[i][1] - path[i - 1][1], path[i][2] - path[i - 1][2]);
    acc.push(len);
  }
  const st = path.map((p, i) => {
    const a = path[Math.max(0, i - 1)];
    const b = path[Math.min(path.length - 1, i + 1)];
    const t = new THREE.Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize();
    const s = new THREE.Vector3(...side);
    s.addScaledVector(t, -s.dot(t)).normalize();
    const o = new THREE.Vector3(...outwardFn(p));
    o.addScaledVector(t, -o.dot(t)).addScaledVector(s, -o.dot(s)).normalize();
    return { c: [p[0], p[1], p[2]], ax: s.toArray(), ay: o.toArray(), a: p[3] * 1.06, b: p[3] * 0.92, v: acc[i] / len };
  });
  return loft(st, seg, uv, { capStart: 2, capEnd: 4, capLen: 0.9 });
}

/** Ellipsoid blob centred at c with radii r (in grip axes), mapped to an atlas rect. */
function blob(c, r, uv, seg = 12, rings = 8) {
  const st = [];
  for (let i = 0; i <= rings; i++) {
    const phi = -Math.PI / 2 + (i / rings) * Math.PI;
    const s = Math.cos(phi);
    st.push({ c: [c[0], c[1], c[2] + Math.sin(phi) * r[2]], ax: Y, ay: X, a: r[1] * Math.max(s, 0.0001), b: r[0] * Math.max(s, 0.0001), v: i / rings });
  }
  return loft(st, seg, uv);
}

/** Right fist in the grip frame. Returns an array of geometries (skin atlas). */
export function fistParts(handleR = 0.016) {
  const parts = [];
  const sq = (n) => (th) => 1 / Math.pow(Math.pow(Math.abs(Math.cos(th)), n) + Math.pow(Math.abs(Math.sin(th)), n), 1 / n);
  // Palm and back of the hand, lofted from the knuckle row (z -0.004) to inside the wrist wrap.
  const palm = [
    // [z, xc, halfWidth (Y), halfThickness (X), yc]
    [-0.008, 0.026, 0.04, 0.0135, 0.0],
    [0.004, 0.027, 0.0425, 0.015, 0.0],
    [0.025, 0.027, 0.0415, 0.016, 0.001],
    [0.05, 0.027, 0.0375, 0.017, 0.001],
    [0.072, 0.027, 0.032, 0.018, 0.0],
    [0.088, 0.027, 0.030, 0.018, 0.0],
  ];
  const knuckles = (th) => {
    // Bumps along the dorsal side of the knuckle row (θ near π/2 is the back of the hand).
    const s = Math.sin(th);
    if (s < 0.2) return sq(3.2)(th);
    const y = Math.cos(th);
    const b = 0.05 * s * (0.5 + 0.5 * Math.cos(y * Math.PI * 4.2));
    return sq(3.2)(th) * (1 + b);
  };
  const ps = palm.map(([z, xc, hw, ht, yc], i) => ({ c: [xc, yc, z], ax: Y, ay: X, a: hw, b: ht, v: i / (palm.length - 1), prof: i <= 1 ? knuckles : sq(3) }));
  parts.push(loft(ps, 24, ARM_UV.palm, { capStart: 3, capLen: 0.6 }));
  // Fingers: index → pinky. Key points in polar form around the handle (in the z-x plane).
  const fingers = [
    { y: 0.0285, r: 0.0094, s: 1.0, dz: -0.002 },
    { y: 0.0095, r: 0.0098, s: 1.04, dz: -0.003 },
    { y: -0.0095, r: 0.0093, s: 0.98, dz: -0.002 },
    { y: -0.0275, r: 0.0081, s: 0.84, dz: 0.003 },
  ];
  const keys = [
    // [rho, phi (deg, atan2(x, z)), radius scale]
    [0.034, 97, 1.0],
    [0.0345, 150, 0.97],
    [0.034, 190, 1.0],
    [0.0325, 232, 0.92],
    [0.0295, 262, 0.88],
    [0.027, 284, 0.78],
  ];
  const fu = ARM_UV.fingers;
  for (const f of fingers) {
    const minRho = handleR + f.r * 0.98;
    const pts = keys.map(([rho, phi, rs], k) => {
      const ph = (97 + (phi - 97) * f.s) * Math.PI / 180;
      const rr = Math.max(minRho, rho * (k === 0 ? 1 : 0.98 + 0.02 * f.s));
      return [Math.sin(ph) * rr, f.y - (k > 0 ? 0.0012 * k * Math.sign(f.y) * 0.3 : 0), Math.cos(ph) * rr + (k === 0 ? f.dz : 0), f.r * rs];
    });
    parts.push(fingerTube(pts, Y, fu, (p) => [p[0], 0, p[2]]));
  }
  // Thumb: from the thenar along the side of the palm, over the handle, tip resting on the index.
  const thumb = [
    [0.014, 0.028, 0.06, 0.0112],
    [0.01, 0.04, 0.032, 0.0102],
    [0.005, 0.0455, 0.008, 0.0095],
    [-0.005, 0.0465, -0.012, 0.009],
    [-0.017, 0.0435, -0.025, 0.0084],
    [-0.025, 0.0395, -0.029, 0.0076],
  ];
  parts.push(fingerTube(thumb, X, fu, (p) => [0, 1, 0.2]));
  // Thenar and hypothenar pads at the heel of the hand.
  parts.push(blob([0.008, 0.024, 0.046], [0.014, 0.017, 0.026], ARM_UV.palm));
  parts.push(blob([0.01, -0.026, 0.05], [0.012, 0.013, 0.022], ARM_UV.palm));
  return parts;
}

// ---------------------------------------------------------------------------------------------
// Xiphos (grip frame: grip centre at the origin, blade along +Y, edges along ±Z, flats ±X)

export const SWORD = { gripLo: -0.05, gripHi: 0.058, guardHi: 0.074, bladeLen: 0.52 };
SWORD.tipY = SWORD.guardHi + SWORD.bladeLen;

function bladeWidth(t) {
  // Leaf: waisted near the hilt, widest at ~60%, then a long point.
  const k = [[0, 0.046], [0.1, 0.040], [0.3, 0.045], [0.5, 0.053], [0.63, 0.056], [0.76, 0.050], [0.88, 0.034], [0.96, 0.014], [1, 0]];
  for (let i = 1; i < k.length; i++) {
    if (t <= k[i][0]) {
      const a = k[i - 1];
      const b = k[i];
      const s = (t - a[0]) / (b[0] - a[0]);
      const e = s * s * (3 - 2 * s);
      return a[1] + (b[1] - a[1]) * e;
    }
  }
  return 0;
}

export function swordGeometry() {
  const parts = [];
  const bu = WEAPON_UV.blade;
  const y0 = SWORD.guardHi - 0.004;
  const L = SWORD.bladeLen + 0.004;
  const nv = 40;
  // Cross-section stops across the half width (q = z / halfWidth) with relative thickness.
  const thick = (t) => 0.0038 - 0.0016 * t;
  const fuller = (t) => (t < 0.64 ? 1 - Math.max(0, (t - 0.5) / 0.14) : 0) * (t > 0.015 ? 1 : t / 0.015);
  const section = (q, t) => {
    const T = thick(t);
    const aq = Math.abs(q);
    if (aq >= 0.86) return T * 0.32 * (1 - aq) / 0.14; // edge bevel
    // Body: gently convex, with the fuller groove in the middle third.
    let x = T * (0.32 + 0.68 * Math.cos((aq / 0.86) * Math.PI / 2) ** 0.6);
    const f = fuller(t);
    if (f > 0 && aq < 0.34) x -= f * T * 0.52 * Math.cos((aq / 0.34) * Math.PI / 2) ** 2;
    return x;
  };
  const face = (qa, qb, side, nu) => gridSurface(nu, nv, (i, j) => {
    const t = j;
    const q = qa + (qb - qa) * i;
    const hw = bladeWidth(t) / 2;
    return [side * section(q, t), y0 + t * L, q * hw, 0.5 + 0.5 * q, t];
  }, bu, side < 0);
  for (const side of [1, -1]) {
    parts.push(face(-1, -0.86, side, 2));
    parts.push(face(-0.86, 0.86, side, 14));
    parts.push(face(0.86, 1, side, 2));
  }
  // Guard: a bronze bar with rounded, slightly upturned ends.
  const gu = sub(WEAPON_UV.bronze, 0, 0, 1, 0.5);
  const guard = [];
  const gn = 10;
  for (let i = 0; i <= gn; i++) {
    const s = -1 + (2 * i) / gn;
    const lift = 0.004 * s * s;
    guard.push({ c: [0, (SWORD.gripHi + SWORD.guardHi) / 2 + lift, s * 0.043], ax: X, ay: Y, a: 0.0125, b: 0.0085 + 0.0012 * (1 - s * s), v: i / gn });
  }
  parts.push(loft(guard, 14, gu, { capStart: 3, capEnd: 3, capLen: 0.9 }));
  // Grip: oval, swelling in the middle, cord-wrapped.
  const grip = [];
  for (let i = 0; i <= 8; i++) {
    const t = i / 8;
    const swell = 1 + 0.12 * Math.sin(t * Math.PI);
    grip.push({ c: [0, SWORD.gripLo + (SWORD.gripHi - SWORD.gripLo) * t, 0], ax: X, ay: Z, a: 0.0118 * swell, b: 0.0145 * swell, v: t });
  }
  parts.push(loft(grip, 14, sub(WEAPON_UV.cord, 0, 0, 1, 0.25)));
  // Pommel: a bronze mushroom.
  const pom = [[0.0, -0.077], [0.01, -0.0765], [0.0165, -0.0725], [0.019, -0.0665], [0.0175, -0.06], [0.0125, -0.0545], [0.0105, -0.049]];
  const pg = lathe(pom, 18, (r, th, k) => [gu[0] + (gu[2] - gu[0]) * (0.5 + 0.45 * Math.cos(th) * r / 0.019), gu[1] + (gu[3] - gu[1]) * (k / 6)]);
  pg.rotateX(-Math.PI / 2); // lathe axis Z → Y
  parts.push(pg);
  return mergeGeometries(parts, false);
}

// ---------------------------------------------------------------------------------------------
// Dory (grip frame: overhand, head toward -Y, butt toward +Y)

export const SPEAR = { shaftHead: -1.0, shaftButt: 1.1, headTip: -1.3, buttTip: 1.34 };

export function spearGeometry() {
  const parts = [];
  const len = SPEAR.shaftButt - SPEAR.shaftHead;
  const shaft = [];
  const n = 24;
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const y = SPEAR.shaftButt - t * len;
    const r = 0.0145 - 0.0018 * Math.max(0, (t - 0.45) / 0.55) - 0.001 * Math.max(0, (0.25 - t) / 0.25);
    shaft.push({ c: [0, y, 0], ax: X, ay: Z, a: r, b: r, v: t });
  }
  parts.push(loft(shaft, 14, WEAPON_UV.wood));
  // Leather grip band at the hand.
  const band = [];
  for (let i = 0; i <= 6; i++) {
    const y = -0.07 + 0.14 * i / 6;
    band.push({ c: [0, y, 0], ax: X, ay: Z, a: 0.0162, b: 0.0162, v: i / 6 });
  }
  parts.push(loft(band, 14, sub(WEAPON_UV.cord, 0, 0.3, 1, 0.6), { capStart: 1, capEnd: 1, capLen: 0.15 }));
  // Spearhead: socket, then a leaf blade with a midrib (lofted cross-sections down -Y).
  const hu = WEAPON_UV.head;
  const socket = [];
  for (let i = 0; i <= 5; i++) {
    const t = i / 5;
    const r = 0.0165 - 0.0035 * t + (i === 1 ? 0.0012 : 0);
    socket.push({ c: [0, SPEAR.shaftHead + 0.02 - 0.1 * t, 0], ax: X, ay: Z, a: r, b: r, v: t * 0.3 });
  }
  parts.push(loft(socket, 14, sub(hu, 0.3, 0, 0.7, 1)));
  const headLen = SPEAR.shaftHead - 0.08 - SPEAR.headTip;
  const hw = (t) => {
    const k = [[0, 0.012], [0.12, 0.032], [0.35, 0.056], [0.55, 0.052], [0.8, 0.03], [1, 0]];
    for (let i = 1; i < k.length; i++) {
      if (t <= k[i][0]) {
        const s = (t - k[i - 1][0]) / (k[i][0] - k[i - 1][0]);
        return k[i - 1][1] + (k[i][1] - k[i - 1][1]) * (s * s * (3 - 2 * s));
      }
    }
    return 0;
  };
  const hy0 = SPEAR.shaftHead - 0.075;
  const hsec = (q, t) => {
    const aq = Math.abs(q);
    const T = 0.0072 * (1 - 0.6 * t);
    if (aq < 0.18) return T * (1 - 0.25 * (aq / 0.18)); // midrib
    return T * 0.75 * Math.max(0, 1 - (aq - 0.18) / 0.82) ** 0.8 + 0.0002;
  };
  for (const side of [1, -1]) {
    parts.push(gridSurface(12, 24, (i, j) => {
      const q = -1 + 2 * i;
      return [q * hw(j) / 2, hy0 - j * headLen, side * hsec(q, j), 0.5 + 0.5 * q, j];
    }, hu, side < 0));
  }
  // Sauroter: a four-sided bronze spike with a collar.
  const bu = sub(WEAPON_UV.bronze, 0, 0.5, 1, 1);
  const sq = (th) => 1 / Math.pow(Math.pow(Math.abs(Math.cos(th)), 4) + Math.pow(Math.abs(Math.sin(th)), 4), 0.25);
  const butt = [];
  const bl = SPEAR.buttTip - SPEAR.shaftButt + 0.03;
  for (let i = 0; i <= 10; i++) {
    const t = i / 10;
    let r = t < 0.25 ? 0.0172 : 0.0172 * (1 - (t - 0.25) / 0.75) ** 0.9 + 0.0004;
    if (i === 2) r += 0.003;
    butt.push({ c: [0, SPEAR.shaftButt - 0.03 + t * bl, 0], ax: X, ay: Z, a: r, b: r, v: t, prof: t > 0.25 ? sq : null });
  }
  parts.push(loft(butt, 16, bu));
  return mergeGeometries(parts, false);
}

// ---------------------------------------------------------------------------------------------
// Aspis

export const ASPIS = {
  R: 0.45,          // outer radius (rim edge)
  a: 0.405,         // bowl edge (rim front starts)
  aIn: 0.39,        // interior bowl edge on the rim's back
  front: -0.018,    // rim front plane
  depth: 0.11,      // bowl depth beyond the rim front
  wall: 0.012,      // wood thickness at the centre
};

/** Outer (bronze) bowl surface z at radius r. */
export function aspisOuterZ(r) {
  const s = Math.min(1, r / ASPIS.a);
  return ASPIS.front - ASPIS.depth * Math.pow(Math.max(0, 1 - Math.pow(s, 2.4)), 0.6);
}

/** Inner (wooden) bowl surface z at radius r. */
export function aspisInnerZ(r) {
  const s = Math.min(1, r / ASPIS.aIn);
  const h = -ASPIS.front + ASPIS.depth - ASPIS.wall;
  return -h * Math.pow(Math.max(0, 1 - Math.pow(s, 2.4)), 0.6);
}

function discUV(rTex, th, flipU = false) {
  const R0 = SHIELD_UV_R;
  return [0.5 + (flipU ? -1 : 1) * Math.cos(th) * rTex * R0, 0.5 + Math.sin(th) * rTex * R0];
}

/**
 * Returns { bronze, interior } geometries for the aspis, in aspis space.
 * The bronze set covers the face, rim front, rolled lip, back band and porpax.
 */
export function aspisGeometry(seg = 72) {
  const A = ASPIS;
  const bronze = [];
  const interior = [];
  // Face: bowl exterior, denser near the steep edge.
  const face = [];
  const rings = 22;
  for (let i = 0; i <= rings; i++) {
    const s = 1 - (1 - i / rings) ** 1.6;
    const r = A.a * s;
    face.push([r, aspisOuterZ(r)]);
  }
  // Outward-facing: the face looks down -Z.
  bronze.push(lathe(face, seg, (r, th) => discUV(SHIELD_BANDS.face * r / A.a, th), true));
  // Rim front: flat ring, slightly dished toward the edge.
  const rimF = [];
  for (let i = 0; i <= 3; i++) {
    const t = i / 3;
    rimF.push([A.a + (A.R - A.a) * t, A.front + 0.004 * t * t]);
  }
  bronze.push(lathe(rimF, seg, (r, th) => discUV(SHIELD_BANDS.face + (SHIELD_BANDS.rim - SHIELD_BANDS.face) * (r - A.a) / (A.R - A.a), th), true));
  // Rolled lip: half-round from the rim front, round the outside, onto the back.
  const lip = [];
  const zf = A.front + 0.004;
  const lr = -zf / 2;
  for (let i = 0; i <= 8; i++) {
    const a = -Math.PI / 2 + (i / 8) * Math.PI;
    lip.push([A.R + Math.cos(a) * lr * 0.55, zf / 2 + Math.sin(a) * lr]);
  }
  bronze.push(lathe(lip, seg, (r, th, k) => discUV(SHIELD_BANDS.rim + (1 - SHIELD_BANDS.rim) * (k / 8), th), true));
  // Back band: the bronze sheet folded over the rim's back edge.
  const bandIn = A.R - 0.017;
  const back = [[A.R, 0.0003], [bandIn + 0.004, 0.0006], [bandIn, 0.0002]];
  bronze.push(lathe(back, seg, (r, th) => discUV(SHIELD_BANDS.rim + (1 - SHIELD_BANDS.rim) * (A.R - r) / (A.R - bandIn), th, true), true));
  // Interior: the rim back (wood) and the turned bowl.
  const rimB = [[bandIn, 0], [A.aIn + 0.004, 0], [A.aIn, -0.002]];
  interior.push(lathe(rimB, seg, (r, th) => discUV(SHIELD_BANDS.face + 0.07 * (r - A.aIn) / (bandIn - A.aIn), th, true), true));
  const bowl = [];
  for (let i = 0; i <= rings; i++) {
    const r = A.aIn * (1 - i / rings) ** 0.7;
    bowl.push([r, aspisInnerZ(r)]);
  }
  bowl[0] = [A.aIn, -0.002];
  interior.push(lathe(bowl, seg, (r, th) => discUV(SHIELD_BANDS.face * r / A.aIn, th, true), true));
  // Porpax: a bronze band arching over the forearm at the centre (forearm runs along +X).
  const fz = aspisInnerZ(0) + PORPAX.axisLift;
  const pr = PORPAX.radius;
  const pw = 0.03;
  const pu = [0.5 - 0.3 * SHIELD_UV_R, 0.5 - 0.6 * SHIELD_UV_R, 0.5 - 0.05 * SHIELD_UV_R, 0.5 + 0.6 * SHIELD_UV_R];
  const arch = (outer) => gridSurface(4, 18, (i, j) => {
    const a = -1.25 + 2.5 * j; // angle round the forearm, 0 = straight toward the bearer
    const rr = pr + (outer ? 0.003 : 0);
    const x = -pw + 2 * pw * i;
    const y = Math.sin(a) * rr * 1.08;
    const z = fz + Math.cos(a) * rr;
    return [x, y, Math.max(z, aspisInnerZ(Math.hypot(x, y)) + 0.001), i, j];
  }, pu, !outer);
  bronze.push(arch(true), arch(false));
  // Antilabe: a cord handle near the rim on the +X side.
  const ax = ANTILABE.x;
  const az = aspisInnerZ(ax);
  const cu = [0.0, 0.0, 0.12, 0.12];
  const cord = [];
  for (let i = 0; i <= 10; i++) {
    const t = i / 10;
    const y = -0.075 + 0.15 * t;
    const lift = Math.sin(t * Math.PI) ** 0.6 * ANTILABE.lift + 0.004;
    cord.push({ c: [ax, y, aspisInnerZ(Math.hypot(ax, y)) + lift], ax: X, ay: Z, a: 0.0085, b: 0.0075, v: t });
  }
  interior.push(loft(cord, 10, cu, { capStart: 2, capEnd: 2 }));
  // Decorative cord sagging between pegs round the upper interior, with tassels.
  const pegs = 5;
  const cr = A.aIn - 0.05;
  const pts = [];
  const pegPts = [];
  for (let k = 0; k <= pegs; k++) {
    const th = Math.PI * (0.18 + 0.64 * (k / pegs));
    for (let s = 0; s < 8; s++) {
      if (k === pegs && s > 0) break;
      const thS = th + (Math.PI * 0.64 / pegs) * (s / 8);
      const sag = Math.sin((s / 8) * Math.PI) ** 1.3 * 0.05;
      const x = Math.cos(thS) * cr;
      const y = Math.sin(thS) * cr - sag;
      const r = Math.hypot(x, y);
      pts.push([x, y, aspisInnerZ(r) + 0.007 + sag * 0.15]);
      if (s === 0) pegPts.push([x, y, aspisInnerZ(r) + 0.007]);
    }
  }
  const cst = pts.map((p, i) => ({ c: p, ax: Z, ay: [0, 0, 0], a: 0.0048, b: 0.0048, v: i / pts.length }));
  for (let i = 0; i < cst.length; i++) {
    const a = pts[Math.max(0, i - 1)];
    const b = pts[Math.min(pts.length - 1, i + 1)];
    const t = new THREE.Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize();
    const n = new THREE.Vector3(0, 0, 1).cross(t).normalize();
    cst[i].ax = [0, 0, 1];
    cst[i].ay = n.toArray();
  }
  interior.push(loft(cst, 6, cu));
  // Tassels hanging from each peg.
  for (const p of pegPts) {
    const tas = [];
    for (let i = 0; i <= 5; i++) {
      const t = i / 5;
      const z = aspisInnerZ(Math.hypot(p[0], p[1] - 0.06 * t)) + 0.009;
      const r = t < 0.25 ? 0.006 : 0.0045 + 0.004 * Math.sin(((t - 0.25) / 0.75) * Math.PI) ** 0.5;
      tas.push({ c: [p[0], p[1] - 0.065 * t, z], ax: X, ay: Z, a: r, b: r, v: t });
    }
    interior.push(loft(tas, 6, cu, { capStart: 1, capEnd: 2 }));
  }
  return { bronze: mergeGeometries(bronze, false), interior: mergeGeometries(interior, false) };
}

/** Porpax: the forearm axis runs along +X at this lift above the interior centre. */
export const PORPAX = { radius: 0.05, axisLift: 0.052 };
/** Antilabe: the cord handle at x (aspis space), standing off the wood by lift. */
export const ANTILABE = { x: 0.335, lift: 0.042 };

// ---------------------------------------------------------------------------------------------
// Arrow: tip at the origin pointing -Z... built along +Z from the tip (z = 0) to the nock.

export const ARROW_LEN = 0.74;

export function arrowGeometry() {
  const parts = [];
  const L = ARROW_LEN;
  const shaft = [];
  for (let i = 0; i <= 6; i++) {
    const t = i / 6;
    shaft.push({ c: [0, 0, 0.03 + t * (L - 0.03)], ax: X, ay: Y, a: 0.0042, b: 0.0042, v: t });
  }
  parts.push(loft(shaft, 6, ARROW_UV.shaft, { capEnd: 1, capLen: 0.5 }));
  // Trilobate bronze head.
  const hu = ARROW_UV.head;
  for (let k = 0; k < 3; k++) {
    const a = (k / 3) * Math.PI * 2;
    const ca = Math.cos(a);
    const sa = Math.sin(a);
    parts.push(gridSurface(1, 3, (i, j) => {
      const w = 0.0075 * Math.sin(j * Math.PI * 0.85) * i;
      const z = j * 0.04;
      return [ca * w, sa * w, z, i, j];
    }, hu, false));
    parts.push(gridSurface(1, 3, (i, j) => {
      const w = 0.0075 * Math.sin(j * Math.PI * 0.85) * i;
      const z = j * 0.04;
      return [ca * w, sa * w, z, i, j];
    }, hu, true));
  }
  // Three vanes near the nock.
  const fu = ARROW_UV.feather;
  for (let k = 0; k < 3; k++) {
    const a = (k / 3) * Math.PI * 2 + 0.5;
    const ca = Math.cos(a);
    const sa = Math.sin(a);
    for (const flip of [false, true]) {
      parts.push(gridSurface(2, 4, (i, j) => {
        const z = L - 0.03 - (1 - j) * 0.13;
        const h = 0.004 + i * 0.013 * Math.sin(Math.min(1, j * 1.15) * Math.PI * 0.55 + 0.15);
        return [ca * h, sa * h, z, i, j];
      }, fu, flip));
    }
  }
  return mergeGeometries(parts, false);
}
