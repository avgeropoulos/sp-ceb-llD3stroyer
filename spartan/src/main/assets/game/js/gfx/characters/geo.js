/**
 * Geometry toolkit for character parts: ring sweeps (lathe-like surfaces with elliptical,
 * shaped cross-sections), thick sheets, boxes, tubes along paths and hand-built polys.
 * Parts are plain arrays in their own frame; PartBuilder places them in mesh space, maps
 * their 0..1 UVs into an atlas tile, assigns rigid skin weights and merges everything into
 * one indexed BufferGeometry with at most two groups (0 non-metal, 1 metal).
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/BufferGeometryUtils.js';

const TAU = Math.PI * 2;

/** Empty raw part. */
export function part() {
  return { p: [], n: [], uv: [], idx: [] };
}

/** Uniform angles from a0 to a1 inclusive (segs + 1 values). */
export function thetas(segs, a0 = -Math.PI, a1 = Math.PI) {
  const out = [];
  for (let j = 0; j <= segs; j++) out.push(a0 + ((a1 - a0) * j) / segs);
  return out;
}

/**
 * Sweeps cross-section rings along local +Y (rings need not be monotonic).
 * ring: { y, rx, rz, x = 0, z = 0, crease, shape(θ) → radius multiplier, fn(θ, pt, i) }
 * θ = 0 is the front (+Z), θ = +π/2 is +X. The seam sits at θ = ±π (the back).
 * o: { segs | thetas, closed = true, flip, skip(i, j) → bool, uvFn(u, v, θ, i, pt) → [u, v] }
 * A ring with rx = rz = 0 is a pole; its degenerate triangles are dropped. Rings may be listed
 * bottom-up or top-down (the orientation follows); pass flip to override for closed profiles.
 */
export function sweep(rings, o = {}) {
  const th = o.thetas || thetas(o.segs || 8, o.a0 ?? -Math.PI, o.a1 ?? Math.PI);
  const closed = o.closed ?? true;
  const R = [];
  for (const r of rings) {
    R.push(r);
    if (r.crease) R.push(Object.assign({}, r, { dup: true }));
  }
  const nr = R.length;
  const nc = th.length;
  const P = new Float64Array(nr * nc * 3);
  const pt = [0, 0, 0];
  for (let i = 0; i < nr; i++) {
    const r = R[i];
    for (let j = 0; j < nc; j++) {
      const t = th[j];
      const m = r.shape ? r.shape(t) : 1;
      pt[0] = (r.x || 0) + r.rx * Math.sin(t) * m;
      pt[1] = r.y;
      pt[2] = (r.z || 0) + r.rz * Math.cos(t) * m;
      if (r.fn) r.fn(t, pt, i);
      const k = (i * nc + j) * 3;
      P[k] = pt[0];
      P[k + 1] = pt[1];
      P[k + 2] = pt[2];
    }
  }
  const isPole = (i) => R[i].rx === 0 && R[i].rz === 0 && !R[i].fn;
  // cumulative profile length for v
  const vs = new Float64Array(nr);
  let acc = 0;
  for (let i = 1; i < nr; i++) {
    const a = R[i - 1], b = R[i];
    acc += Math.hypot(b.y - a.y, (b.rx + b.rz) / 2 - (a.rx + a.rz) / 2, (b.z || 0) - (a.z || 0));
    vs[i] = acc;
  }
  const out = part();
  const ga = (i, j, c) => P[(i * nc + j) * 3 + c];
  // rings listed top-down are oriented so the surface still faces outward
  const flip = o.flip ?? rings[rings.length - 1].y < rings[0].y;
  const sgn = flip ? -1 : 1;
  for (let i = 0; i < nr; i++) {
    for (let j = 0; j < nc; j++) {
      let jm = j - 1, jp = j + 1;
      if (closed) {
        if (jm < 0) jm = nc - 2;
        if (jp > nc - 1) jp = 1;
      } else {
        if (jm < 0) jm = 0;
        if (jp > nc - 1) jp = nc - 1;
      }
      let im = i - 1, ip = i + 1;
      if (im < 0 || R[i].dup) im = i;
      if (ip >= nr || R[ip].dup) ip = i;
      const tx = ga(i, jp, 0) - ga(i, jm, 0), ty = ga(i, jp, 1) - ga(i, jm, 1), tz = ga(i, jp, 2) - ga(i, jm, 2);
      const sx = ga(ip, j, 0) - ga(im, j, 0), sy = ga(ip, j, 1) - ga(im, j, 1), sz = ga(ip, j, 2) - ga(im, j, 2);
      let nx = ty * sz - tz * sy, ny = tz * sx - tx * sz, nz = tx * sy - ty * sx;
      let l = Math.hypot(nx, ny, nz);
      if (l < 1e-9) {
        // pole: point away from the neighbouring ring's centre
        const ni = i === 0 || R[i].dup ? i + 1 : i - 1;
        let cx = 0, cy = 0, cz = 0;
        for (let q = 0; q < nc - (closed ? 1 : 0); q++) {
          cx += ga(ni, q, 0);
          cy += ga(ni, q, 1);
          cz += ga(ni, q, 2);
        }
        const cnt = nc - (closed ? 1 : 0);
        nx = ga(i, j, 0) - cx / cnt;
        ny = ga(i, j, 1) - cy / cnt;
        nz = ga(i, j, 2) - cz / cnt;
        l = Math.hypot(nx, ny, nz) || 1;
        out.n.push(nx / l, ny / l, nz / l);
      } else {
        out.n.push((sgn * nx) / l, (sgn * ny) / l, (sgn * nz) / l);
      }
      out.p.push(ga(i, j, 0), ga(i, j, 1), ga(i, j, 2));
      let u = (th[j] - th[0]) / (th[nc - 1] - th[0]);
      let v = acc > 0 ? vs[i] / acc : 0;
      if (o.uvFn) {
        pt[0] = ga(i, j, 0);
        pt[1] = ga(i, j, 1);
        pt[2] = ga(i, j, 2);
        const uv = o.uvFn(u, v, th[j], i, pt);
        u = uv[0];
        v = uv[1];
      }
      out.uv.push(u, v);
    }
  }
  for (let i = 0; i < nr - 1; i++) {
    if (R[i + 1].dup) continue;
    const pa = isPole(i), pb = isPole(i + 1);
    for (let j = 0; j < nc - 1; j++) {
      if (o.skip && o.skip(i, j)) continue;
      const a = i * nc + j, b = i * nc + j + 1, c = (i + 1) * nc + j + 1, d = (i + 1) * nc + j;
      if (!pa) tri(out, a, b, c, flip);
      if (!pb) tri(out, a, c, d, flip);
    }
  }
  return out;
}

function tri(out, a, b, c, flip) {
  if (flip) out.idx.push(a, c, b);
  else out.idx.push(a, b, c);
}

/**
 * A thick sheet: fn(u, v, out) fills out = [x, y, z] for u, v in 0..1 (the outer surface).
 * The inner surface is offset by `thick` against the outer normal. Edges are closed with
 * flat strips. The outer normal is d/du × d/dv (flip to reverse). Both faces map the full tile.
 */
export function slab(fn, nu, nv, thick, o = {}) {
  const out = part();
  const S = [];
  const tmp = [0, 0, 0];
  for (let j = 0; j <= nv; j++) {
    for (let i = 0; i <= nu; i++) {
      fn(i / nu, j / nv, tmp);
      S.push([tmp[0], tmp[1], tmp[2]]);
    }
  }
  const at = (i, j) => S[j * (nu + 1) + i];
  const N = [];
  const sg = o.flip ? -1 : 1;
  for (let j = 0; j <= nv; j++) {
    for (let i = 0; i <= nu; i++) {
      const a = at(Math.min(nu, i + 1), j), b = at(Math.max(0, i - 1), j);
      const c = at(i, Math.min(nv, j + 1)), d = at(i, Math.max(0, j - 1));
      const ux = a[0] - b[0], uy = a[1] - b[1], uz = a[2] - b[2];
      const vx = c[0] - d[0], vy = c[1] - d[1], vz = c[2] - d[2];
      let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      const l = Math.hypot(nx, ny, nz) || 1;
      N.push([(sg * nx) / l, (sg * ny) / l, (sg * nz) / l]);
    }
  }
  const back = o.backUv || ((u, v) => [u, v]);
  // outer then inner
  for (let side = 0; side < 2; side++) {
    const base = out.p.length / 3;
    for (let j = 0; j <= nv; j++) {
      for (let i = 0; i <= nu; i++) {
        const k = j * (nu + 1) + i;
        const s = S[k], n = N[k];
        if (side === 0) {
          out.p.push(s[0], s[1], s[2]);
          out.n.push(n[0], n[1], n[2]);
          out.uv.push(i / nu, j / nv);
        } else {
          out.p.push(s[0] - n[0] * thick, s[1] - n[1] * thick, s[2] - n[2] * thick);
          out.n.push(-n[0], -n[1], -n[2]);
          const uv = back(i / nu, j / nv);
          out.uv.push(uv[0], uv[1]);
        }
      }
    }
    for (let j = 0; j < nv; j++) {
      for (let i = 0; i < nu; i++) {
        const a = base + j * (nu + 1) + i, b = a + 1, c = a + nu + 2, d = a + nu + 1;
        const f = side === 1 ? !o.flip : !!o.flip;
        tri(out, a, b, c, f);
        tri(out, a, c, d, f);
      }
    }
  }
  if (thick > 0 && !o.noEdges) {
    // border loop (outer-surface indices)
    const loop = [];
    for (let i = 0; i < nu; i++) loop.push([i, 0]);
    for (let j = 0; j < nv; j++) loop.push([nu, j]);
    for (let i = nu; i > 0; i--) loop.push([i, nv]);
    for (let j = nv; j > 0; j--) loop.push([0, j]);
    for (let q = 0; q < loop.length; q++) {
      const [i0, j0] = loop[q];
      const [i1, j1] = loop[(q + 1) % loop.length];
      const k0 = j0 * (nu + 1) + i0, k1 = j1 * (nu + 1) + i1;
      const a = S[k0], b = S[k1], na = N[k0], nb = N[k1];
      const a2 = [a[0] - na[0] * thick, a[1] - na[1] * thick, a[2] - na[2] * thick];
      const b2 = [b[0] - nb[0] * thick, b[1] - nb[1] * thick, b[2] - nb[2] * thick];
      // edge normal: along-edge × surface normal
      const ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2];
      const mx = (na[0] + nb[0]) / 2, my = (na[1] + nb[1]) / 2, mz = (na[2] + nb[2]) / 2;
      let nx = ey * mz - ez * my, ny = ez * mx - ex * mz, nz = ex * my - ey * mx;
      const l = Math.hypot(nx, ny, nz) || 1;
      nx /= l;
      ny /= l;
      nz /= l;
      if (o.flip) {
        nx = -nx;
        ny = -ny;
        nz = -nz;
      }
      const base = out.p.length / 3;
      out.p.push(...a, ...b, ...b2, ...a2);
      for (let r = 0; r < 4; r++) out.n.push(nx, ny, nz);
      const eu = o.edgeUv || [0.0, 0.0, 0.08, 1];
      out.uv.push(eu[0], eu[1], eu[2], eu[1], eu[2], eu[3], eu[0], eu[3]);
      // winding consistent with the outward edge normal
      if (o.flip) {
        out.idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
      } else {
        out.idx.push(base, base + 2, base + 1, base, base + 3, base + 2);
      }
    }
  }
  return out;
}

/** Axis-aligned box centred at the origin, each face mapped 0..1 (or by size if o.uvScale). */
export function box(w, h, d, o = {}) {
  const out = part();
  const x = w / 2, y = h / 2, z = d / 2;
  const faces = [
    // normal, 4 corners (CCW seen from outside)
    [[1, 0, 0], [[x, -y, z], [x, -y, -z], [x, y, -z], [x, y, z]], [d, h]],
    [[-1, 0, 0], [[-x, -y, -z], [-x, -y, z], [-x, y, z], [-x, y, -z]], [d, h]],
    [[0, 1, 0], [[-x, y, z], [x, y, z], [x, y, -z], [-x, y, -z]], [w, d]],
    [[0, -1, 0], [[-x, -y, -z], [x, -y, -z], [x, -y, z], [-x, -y, z]], [w, d]],
    [[0, 0, 1], [[-x, -y, z], [x, -y, z], [x, y, z], [-x, y, z]], [w, h]],
    [[0, 0, -1], [[x, -y, -z], [-x, -y, -z], [-x, y, -z], [x, y, -z]], [w, h]],
  ];
  for (const [n, cs, sz] of faces) {
    if (o.skip && o.skip(n)) continue;
    const base = out.p.length / 3;
    const su = o.uvScale ? sz[0] * o.uvScale : 1, sv = o.uvScale ? sz[1] * o.uvScale : 1;
    const uvs = [[0, 0], [su, 0], [su, sv], [0, sv]];
    for (let k = 0; k < 4; k++) {
      out.p.push(...cs[k]);
      out.n.push(...n);
      out.uv.push(Math.min(1, uvs[k][0]), Math.min(1, uvs[k][1]));
    }
    out.idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  return out;
}

/**
 * Tube along a polyline path (array of [x,y,z]) with per-point radii (number or [rx, rz]).
 * Uses parallel-transport frames. o: { segs, capStart, capEnd, up: [x,y,z] initial normal hint }
 * u runs around, v along the path.
 */
export function tube(path, radii, o = {}) {
  const segs = o.segs || 6;
  const n = path.length;
  const T = [], Nn = [], B = [];
  const v3 = (a) => new THREE.Vector3(a[0], a[1], a[2]);
  for (let i = 0; i < n; i++) {
    const a = v3(path[Math.max(0, i - 1)]), b = v3(path[Math.min(n - 1, i + 1)]);
    T.push(b.sub(a).normalize());
  }
  let up = o.up ? v3(o.up) : new THREE.Vector3(0, 1, 0);
  if (Math.abs(up.dot(T[0])) > 0.95) up = new THREE.Vector3(1, 0, 0);
  let nrm = up.clone().sub(T[0].clone().multiplyScalar(up.dot(T[0]))).normalize();
  for (let i = 0; i < n; i++) {
    if (i > 0) {
      const t = T[i];
      nrm = nrm.sub(t.clone().multiplyScalar(nrm.dot(t))).normalize();
    }
    Nn.push(nrm.clone());
    B.push(T[i].clone().cross(nrm).normalize());
  }
  const out = part();
  let total = 0;
  const lens = [0];
  for (let i = 1; i < n; i++) {
    total += v3(path[i]).distanceTo(v3(path[i - 1]));
    lens.push(total);
  }
  for (let i = 0; i < n; i++) {
    const r = radii[Math.min(i, radii.length - 1)];
    const rx = Array.isArray(r) ? r[0] : r, rz = Array.isArray(r) ? r[1] : r;
    for (let j = 0; j <= segs; j++) {
      const t = (j / segs) * TAU;
      const c = Math.cos(t), s = Math.sin(t);
      const dx = Nn[i].x * c * rx + B[i].x * s * rz;
      const dy = Nn[i].y * c * rx + B[i].y * s * rz;
      const dz = Nn[i].z * c * rx + B[i].z * s * rz;
      out.p.push(path[i][0] + dx, path[i][1] + dy, path[i][2] + dz);
      const nx = Nn[i].x * c * rz + B[i].x * s * rx;
      const ny = Nn[i].y * c * rz + B[i].y * s * rx;
      const nz = Nn[i].z * c * rz + B[i].z * s * rx;
      const l = Math.hypot(nx, ny, nz) || 1;
      out.n.push(nx / l, ny / l, nz / l);
      out.uv.push(j / segs, total > 0 ? lens[i] / total : 0);
    }
  }
  const w = segs + 1;
  for (let i = 0; i < n - 1; i++) {
    for (let j = 0; j < segs; j++) {
      const a = i * w + j, b = a + 1, c = a + w + 1, d = a + w;
      // around (N→B) × along (T) = N: outward
      out.idx.push(a, b, c, a, c, d);
    }
  }
  const cap = (i, dirSign) => {
    const base = out.p.length / 3;
    const t = T[i];
    out.p.push(...path[i]);
    out.n.push(t.x * dirSign, t.y * dirSign, t.z * dirSign);
    out.uv.push(0.5, i === 0 ? 0 : 1);
    for (let j = 0; j < segs; j++) {
      const a = i * w + j, b = a + 1;
      if (dirSign > 0) out.idx.push(base, a, b);
      else out.idx.push(base, b, a);
    }
  };
  if (o.capStart) cap(0, -1);
  if (o.capEnd) cap(n - 1, 1);
  return out;
}

/** Hand-built faceted poly: verts [[x,y,z]], tris [[a,b,c]] (CCW outside). Flat normals. */
export function poly(verts, tris, uvFn) {
  const out = part();
  for (const [a, b, c] of tris) {
    const A = verts[a], Bv = verts[b], C = verts[c];
    const ux = Bv[0] - A[0], uy = Bv[1] - A[1], uz = Bv[2] - A[2];
    const vx = C[0] - A[0], vy = C[1] - A[1], vz = C[2] - A[2];
    let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    const l = Math.hypot(nx, ny, nz) || 1;
    nx /= l;
    ny /= l;
    nz /= l;
    const base = out.p.length / 3;
    for (const V of [A, Bv, C]) {
      out.p.push(V[0], V[1], V[2]);
      out.n.push(nx, ny, nz);
      const uv = uvFn ? uvFn(V) : [0.5, 0.5];
      out.uv.push(uv[0], uv[1]);
    }
    out.idx.push(base, base + 1, base + 2);
  }
  return out;
}

/** Smooth-shaded variant of poly: shared vertices, averaged normals. */
export function polySmooth(verts, tris, uvFn) {
  const out = part();
  const N = verts.map(() => [0, 0, 0]);
  for (const [a, b, c] of tris) {
    const A = verts[a], Bv = verts[b], C = verts[c];
    const ux = Bv[0] - A[0], uy = Bv[1] - A[1], uz = Bv[2] - A[2];
    const vx = C[0] - A[0], vy = C[1] - A[1], vz = C[2] - A[2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    for (const k of [a, b, c]) {
      N[k][0] += nx;
      N[k][1] += ny;
      N[k][2] += nz;
    }
  }
  verts.forEach((V, k) => {
    const l = Math.hypot(N[k][0], N[k][1], N[k][2]) || 1;
    out.p.push(V[0], V[1], V[2]);
    out.n.push(N[k][0] / l, N[k][1] / l, N[k][2] / l);
    const uv = uvFn ? uvFn(V) : [0.5, 0.5];
    out.uv.push(uv[0], uv[1]);
  });
  for (const t of tris) out.idx.push(t[0], t[1], t[2]);
  return out;
}

/** Matrix from position, Euler rotation (radians, order XYZ unless given) and scale. */
export function mat(pos = [0, 0, 0], rot = [0, 0, 0], scale = [1, 1, 1], order = 'XYZ') {
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(rot[0], rot[1], rot[2], order));
  const s = typeof scale === 'number' ? [scale, scale, scale] : scale;
  m.compose(new THREE.Vector3(pos[0], pos[1], pos[2]), q, new THREE.Vector3(s[0], s[1], s[2]));
  return m;
}

/** Matrix whose columns are the given basis vectors, translated to pos. */
export function basis(pos, x, y, z) {
  const m = new THREE.Matrix4().makeBasis(
    new THREE.Vector3(...x).normalize(),
    new THREE.Vector3(...y).normalize(),
    new THREE.Vector3(...z).normalize(),
  );
  m.setPosition(pos[0], pos[1], pos[2]);
  return m;
}

const _p = new THREE.Vector3();
const _nv = new THREE.Vector3();
const _nmat = new THREE.Matrix3();

/**
 * Collects parts into the two material groups. rects maps tile names to [u0, v0, u1, v1].
 * add(part, { m, bone, tile, sub: [su0, sv0, su1, sv1], group, boneFn(p) → bone })
 */
export class PartBuilder {
  constructor(rects, { skinned = true } = {}) {
    this.rects = rects;
    this.skinned = skinned;
    this.lists = [[], []];
    this.tris = [0, 0];
  }

  add(pt, o = {}) {
    const group = o.group || 0;
    const r = this.rects[o.tile];
    if (!r) throw new Error(`characters: unknown tile ${o.tile}`);
    const sub = o.sub || [0, 0, 1, 1];
    const m = o.m;
    if (m) _nmat.getNormalMatrix(m);
    // a mirrored transform flips winding
    const flip = m ? m.determinant() < 0 : false;
    const nv = pt.p.length / 3;
    const pos = new Float32Array(nv * 3), nrm = new Float32Array(nv * 3), uv = new Float32Array(nv * 2);
    const si = new Uint16Array(nv * 4), sw = new Float32Array(nv * 4);
    for (let k = 0; k < nv; k++) {
      _p.set(pt.p[k * 3], pt.p[k * 3 + 1], pt.p[k * 3 + 2]);
      _nv.set(pt.n[k * 3], pt.n[k * 3 + 1], pt.n[k * 3 + 2]);
      if (m) {
        _p.applyMatrix4(m);
        _nv.applyMatrix3(_nmat).normalize();
      }
      pos[k * 3] = _p.x;
      pos[k * 3 + 1] = _p.y;
      pos[k * 3 + 2] = _p.z;
      nrm[k * 3] = _nv.x;
      nrm[k * 3 + 1] = _nv.y;
      nrm[k * 3 + 2] = _nv.z;
      const u = sub[0] + (sub[2] - sub[0]) * pt.uv[k * 2];
      const v = sub[1] + (sub[3] - sub[1]) * pt.uv[k * 2 + 1];
      uv[k * 2] = r[0] + (r[2] - r[0]) * u;
      uv[k * 2 + 1] = r[1] + (r[3] - r[1]) * v;
      si[k * 4] = o.boneFn ? o.boneFn(_p, k) : o.bone || 0;
      sw[k * 4] = 1;
    }
    const idx = flip ? pt.idx.slice() : pt.idx;
    if (flip) {
      for (let k = 0; k < idx.length; k += 3) {
        const t = idx[k + 1];
        idx[k + 1] = idx[k + 2];
        idx[k + 2] = t;
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('normal', new THREE.BufferAttribute(nrm, 3));
    g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    if (this.skinned) {
      g.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(si, 4));
      g.setAttribute('skinWeight', new THREE.BufferAttribute(sw, 4));
    }
    g.setIndex(Array.from(idx));
    this.lists[group].push(g);
    this.tris[group] += idx.length / 3;
    return this;
  }

  /** Merges into one geometry: per group first, then both groups with useGroups. */
  build() {
    const groups = [];
    for (let gi = 0; gi < 2; gi++) {
      const list = this.lists[gi];
      if (list.length === 0) continue;
      const merged = list.length === 1 ? list[0] : mergeGeometries(list, false);
      groups.push({ geo: merged, material: gi });
      for (const g of list) if (g !== merged) g.dispose();
    }
    let geo;
    if (groups.length === 1) {
      geo = groups[0].geo;
      geo.clearGroups();
      geo.addGroup(0, geo.index.count, groups[0].material);
    } else {
      geo = mergeGeometries(groups.map((g) => g.geo), true);
      for (const g of groups) g.geo.dispose();
    }
    geo.userData.triangles = this.tris[0] + this.tris[1];
    geo.userData.trianglesByGroup = this.tris.slice();
    return geo;
  }
}
