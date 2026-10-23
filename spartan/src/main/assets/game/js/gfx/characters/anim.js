/**
 * Procedural animator for one Visual. Each update builds a pose (Float32Array of channels)
 * from layers, then solves it onto the bones:
 *   base   combat stance (breathing, weight shifts) blended with walk/run cycles whose feet
 *          are planted (stride matched to speed), and the guard layer on the upper body
 *   clip   keyframed one-shot (full body or upper body), Hermite-interpolated, with the strike
 *          window kept at its authored speed when the caller stretches the wind-up
 *   snap   short inertial crossfade from the previous output when clips start or end
 *   flinch additive damped springs on the torso and head, by hit direction
 * Arms and legs are two-bone IK (hands in chest space, feet on the ground in mesh space);
 * the weapon and shield are aimed by direction. Lying poses are ground-corrected so bodies
 * rest on the ground. Nothing here allocates per frame: in the hot paths, scalars cross helper
 * calls through the Float64Array scratch _f (or as a typed array and an index) rather than as
 * arguments or return values, which V8 would box when the call is not inlined.
 */
import * as THREE from 'three';
import { B, BONES } from './rig.js';
import { CH, N, UPPER, STYLES, CLIPS, clipKeys } from './poses.js';

const PI = Math.PI;
const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smooth = (t) => t * t * (3 - 2 * t);

// module scratch (updates run one at a time)
const _e = new THREE.Euler();
const _m = new THREE.Matrix4();
const _q = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map(() => new THREE.Quaternion());
const _v = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13].map(() => new THREE.Vector3());
const _sx = new THREE.Vector3(), _sy = new THREE.Vector3(), _sz = new THREE.Vector3();
const _ua = new THREE.Vector3(), _ub = new THREE.Vector3(), _uc = new THREE.Vector3(), _ud = new THREE.Vector3();
const _qa = new THREE.Quaternion(), _qb = new THREE.Quaternion(), _qc = new THREE.Quaternion(), _qd = new THREE.Quaternion();
// scalar scratch: Euler angles for eulerQa(q, _f, F_E), solve2's lengths in and angle out, dt
const _f = new Float64Array(8);
const F_E = 0, F_L1 = 3, F_L2 = 4, F_PHI = 5, F_DT = 6, F_W = 7;

/** v.normalize(), written out (bit for bit: three.js scales by 1 / length). */
function normalizeV(v) {
  const inv = 1 / (Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z) || 1);
  v.x *= inv;
  v.y *= inv;
  v.z *= inv;
  return v;
}

function eulerQ(q, x, y, z, order = 'YXZ') {
  _e.set(x, y, z, order);
  return q.setFromEuler(_e);
}

/**
 * q from the YXZ Euler angles a[i], a[i + 1], a[i + 2] (x pitch, y yaw, z roll): the same as
 * eulerQ(q, a[i], a[i + 1], a[i + 2]) (three.js setFromEuler, bit for bit), without passing
 * the angles as arguments.
 */
function eulerQa(q, a, i) {
  const x = a[i], y = a[i + 1], z = a[i + 2];
  const c1 = Math.cos(x / 2), c2 = Math.cos(y / 2), c3 = Math.cos(z / 2);
  const s1 = Math.sin(x / 2), s2 = Math.sin(y / 2), s3 = Math.sin(z / 2);
  q._x = s1 * c2 * c3 + c1 * s2 * s3;
  q._y = c1 * s2 * c3 - s1 * c2 * s3;
  q._z = c1 * c2 * s3 - s1 * s2 * c3;
  q._w = c1 * c2 * c3 + s1 * s2 * s3;
  q._onChangeCallback();
  return q;
}

/** v = (a[i], a[i + 1], a[i + 2]) without passing the values as arguments. */
function setA(v, a, i) {
  v.x = a[i];
  v.y = a[i + 1];
  v.z = a[i + 2];
  return v;
}

/** q = rotation by a[i] about +X (as setFromAxisAngle((1, 0, 0), a[i])). */
function axisXQ(q, a, i) {
  const h = a[i] / 2;
  q._x = Math.sin(h);
  q._y = 0;
  q._z = 0;
  q._w = Math.cos(h);
  q._onChangeCallback();
  return q;
}

function basisQ(q, x, y, z) {
  _m.makeBasis(x, y, z);
  return q.setFromRotationMatrix(_m);
}

/** Orthonormal frame with Z along `z` and Y as close as possible to `yHint`. */
function frameQ(q, z, yHint, fallback) {
  normalizeV(_sz.copy(z));
  _sy.copy(yHint);
  // _sy.addScaledVector(_sz, -_sy.dot(_sz)), without passing the double to a call
  const k = -(_sy.x * _sz.x + _sy.y * _sz.y + _sy.z * _sz.z);
  _sy.x += _sz.x * k;
  _sy.y += _sz.y * k;
  _sy.z += _sz.z * k;
  if (_sy.x * _sy.x + _sy.y * _sy.y + _sy.z * _sy.z < 1e-6) {
    _sy.copy(fallback);
    _sy.addScaledVector(_sz, -_sy.dot(_sz));
    if (_sy.lengthSq() < 1e-6) _sy.set(0, 1, 0).addScaledVector(_sz, -_sz.y);
    if (_sy.lengthSq() < 1e-6) _sy.set(1, 0, 0);
  }
  normalizeV(_sy);
  _sx.crossVectors(_sy, _sz);
  return basisQ(q, _sx, _sy, _sz);
}

/**
 * Two-bone IK in the frame of S/T/pole, bone lengths _f[F_L1] and _f[F_L2]. Writes the upper
 * bone's rotation (rest direction −Y) into qOut, the elbow/knee into elbow, the lower direction
 * into lowerDir and the lower bone's flex angle about its local X into _f[F_PHI].
 * sign: +1 knees (bend forward), −1 elbows.
 */
function solve2(S, T, pole, sign, qOut, elbow, lowerDir) {
  const l1 = _f[F_L1], l2 = _f[F_L2];
  const d = _ua.subVectors(T, S);
  let dist = Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);
  if (dist < 1e-6) {
    d.set(0, -1, 0);
    dist = 1e-3;
  } else {
    const inv = 1 / dist;
    d.x *= inv;
    d.y *= inv;
    d.z *= inv;
  }
  dist = clamp(dist, Math.abs(l1 - l2) + 1e-4, (l1 + l2) * 0.9995);
  const cosB = clamp((l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist), -1, 1);
  const sinB = Math.sqrt(1 - cosB * cosB);
  // (the Vector3 calls that take a scalar are written out: a double argument is boxed when the
  // call is not inlined)
  const p = _ub.copy(pole);
  const pd = -(pole.x * d.x + pole.y * d.y + pole.z * d.z);
  p.x += d.x * pd;
  p.y += d.y * pd;
  p.z += d.z * pd;
  if (p.x * p.x + p.y * p.y + p.z * p.z < 1e-8) {
    p.set(0, 0, sign).addScaledVector(d, -d.z * sign);
    if (p.lengthSq() < 1e-8) p.set(1, 0, 0);
  }
  normalizeV(p);
  const u = _uc;
  u.x = d.x * cosB + p.x * sinB;
  u.y = d.y * cosB + p.y * sinB;
  u.z = d.z * cosB + p.z * sinB;
  elbow.x = S.x + u.x * l1;
  elbow.y = S.y + u.y * l1;
  elbow.z = S.z + u.z * l1;
  lowerDir.x = S.x + d.x * dist - elbow.x;
  lowerDir.y = S.y + d.y * dist - elbow.y;
  lowerDir.z = S.z + d.z * dist - elbow.z;
  normalizeV(lowerDir);
  const X = _ud;
  if (sign > 0) X.crossVectors(p, d);
  else X.crossVectors(d, p);
  normalizeV(X);
  _sy.copy(u).negate();
  _sz.crossVectors(X, _sy);
  basisQ(qOut, X, _sy, _sz);
  const lz = lowerDir.x * _sz.x + lowerDir.y * _sz.y + lowerDir.z * _sz.z;
  const lu = lowerDir.x * u.x + lowerDir.y * u.y + lowerDir.z * u.z;
  _f[F_PHI] = Math.atan2(-lz, lu);
}

/** Hermite evaluation of keyframes at normalised time ta[ti] into out. */
function sampleKeys(keys, tans, ta, ti, out) {
  const t = ta[ti];
  const n = keys.length;
  if (t <= keys[0].t) {
    out.set(keys[0].pose);
    return;
  }
  if (t >= keys[n - 1].t) {
    out.set(keys[n - 1].pose);
    return;
  }
  let i = 0;
  while (i < n - 2 && t > keys[i + 1].t) i++;
  const k0 = keys[i], k1 = keys[i + 1];
  const h = k1.t - k0.t;
  const u = (t - k0.t) / h;
  const u2 = u * u, u3 = u2 * u;
  const h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
  const p0 = k0.pose, p1 = k1.pose, m0 = tans[i], m1 = tans[i + 1];
  for (let c = 0; c < N; c++) out[c] = h00 * p0[c] + h10 * h * m0[c] + h01 * p1[c] + h11 * h * m1[c];
}

/** Per-key tangents (finite differences in time; zero at stops and the ends). */
function keyTangents(keys) {
  return keys.map((k, i) => {
    const m = new Float32Array(N);
    if (k.stop || i === 0 || i === keys.length - 1) return m;
    const a = keys[i - 1], b = keys[i + 1];
    const dt = b.t - a.t;
    for (let c = 0; c < N; c++) m[c] = (b.pose[c] - a.pose[c]) / dt;
    // keep the monotone feel: limit tangents where the key is a local extremum
    for (let c = 0; c < N; c++) {
      const d0 = k.pose[c] - a.pose[c], d1 = b.pose[c] - k.pose[c];
      if (d0 * d1 <= 0) m[c] = 0;
    }
    return m;
  });
}
const tanCache = new Map();
function clipData(style, clip) {
  const key = style + ':' + clip;
  let d = tanCache.get(key);
  if (!d) {
    const keys = clipKeys(style, clip);
    d = { keys, tans: keyTangents(keys) };
    tanCache.set(key, d);
  }
  return d;
}

/** Bones of loose cloth (cloak, skirt panels). */
export const CLOTH_BONES = [B.cloak, B.skirtFL, B.skirtFR, B.skirtBL, B.skirtBR];

/** Foot roll pivots relative to the ankle (unit body metres): ball of the foot and heel. */
const PIV_Y = -0.085, PIV_BALL_Z = 0.13, PIV_HEEL_Z = -0.06;

/** Contact points for ground correction: [bone, x, y, z, radius] in unit-body bone space. */
const CONTACTS = [
  [B.head, 0, 0.07, -0.02, 0.11], [B.head, 0, -0.04, 0.07, 0.07], [B.head, 0, 0.12, 0.05, 0.06],
  [B.chest, 0, 0.14, -0.1, 0.06], [B.chest, 0, 0.1, 0.12, 0.05], [B.chest, 0.14, 0.18, 0, 0.05], [B.chest, -0.14, 0.18, 0, 0.05],
  [B.spine, 0, 0, -0.11, 0.05], [B.spine, 0, 0, 0.11, 0.05],
  [B.pelvis, 0, -0.04, -0.11, 0.06], [B.pelvis, 0, -0.04, 0.1, 0.05], [B.pelvis, 0.15, -0.02, 0, 0.04], [B.pelvis, -0.15, -0.02, 0, 0.04],
  [B.forearmL, 0, 0, 0, 0.045], [B.forearmR, 0, 0, 0, 0.045], [B.handL, 0, -0.06, 0, 0.035], [B.handR, 0, -0.06, 0, 0.035],
  [B.upperArmL, 0, -0.02, 0, 0.05], [B.upperArmR, 0, -0.02, 0, 0.05],
  [B.shinL, 0, 0, 0.03, 0.05], [B.shinR, 0, 0, 0.03, 0.05], [B.shinL, 0, -0.2, -0.03, 0.05], [B.shinR, 0, -0.2, -0.03, 0.05],
  [B.footL, 0, -0.075, -0.06, 0.012], [B.footL, 0, -0.07, 0.17, 0.012], [B.footR, 0, -0.075, -0.06, 0.012], [B.footR, 0, -0.07, 0.17, 0.012],
  [B.thighL, 0, -0.2, 0, 0.07], [B.thighR, 0, -0.2, 0, 0.07],
];

/**
 * Animator for one instance.
 * cfg: { bones, rest, style, mesh, seed, right: 'spear'|'axe'|'sword'|'bundle'|'arrow'|null,
 *        left: 'aspis'|'grip'|'bow'|null, itemPoints: [[x,y,z]...] in itemL space, bowTipY }
 */
export class Animator {
  constructor(cfg) {
    this.bones = cfg.bones;
    this.rest = cfg.rest;
    this.style = cfg.style;
    this.st = STYLES[cfg.style] || STYLES.levy;
    this.mesh = cfg.mesh;
    this.root = cfg.root;
    this.right = cfg.right;
    this.left = cfg.left;
    this.itemPoints = cfg.itemPoints || [];
    this.bowTipY = cfg.bowTipY || 0;
    this.isDummy = cfg.style === 'dummy';
    // world metres per mesh metre (the per-instance height variety scales the mesh)
    this.meshScale = cfg.scale || 1;
    // the carried weapon's reach either side of the fist (mesh metres), to lay it down
    this.wFront = (cfg.weaponLen && cfg.weaponLen[0]) || 0;
    this.wBack = (cfg.weaponLen && cfg.weaponLen[1]) || 0;
    this.dirty = true;
    // ground contacts: [bone, x, y, z] in bone space (mesh metres), measured from the geometry;
    // cloth: hem points of the cloak and skirt panels, which squash rather than lift the body
    this.contacts = cfg.contacts || null;
    this.cloth = cfg.cloth || null;
    this.clothSquashed = false;
    const s = this.rest.s;
    this.s = s;
    // per-instance variety (deterministic)
    const r = (k) => {
      let h = Math.imul((cfg.seed | 0) ^ (k * 0x9e3779b9), 0x85ebca6b);
      h ^= h >>> 13;
      h = Math.imul(h, 0xc2b2ae35);
      return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
    };
    this.phase0 = r(1) * 10;
    this.breathRate = 0.24 + r(2) * 0.08;
    this.swayRate = 0.11 + r(3) * 0.06;
    this.stanceWidth = 0.94 + r(4) * 0.12;
    this.guardJitter = (r(5) - 0.5) * 0.04;
    this.lookRate = 0.07 + r(6) * 0.05;
    // state
    this.t = 0;
    this.speedCmd = 0;
    this.speed = 0;
    this.gait = 0;
    this.moveW = 0;
    this.guardOn = false;
    this.guardW = 0;
    this.clipName = null;
    this.clipDef = null;
    this.clipT = 0;
    this.clipD = 1;
    this.clipPlaying = false;
    this.clipStruck = false;
    this.onStrike = null;
    this.onEnd = null;
    this.serial = 0;
    this.snapW = 0;
    this.snapRate = 8;
    // flinch springs: pitch, roll, yaw (torso) and a head spring following
    this.fl = new Float32Array(6); // p, r, y, vp, vr, vy
    this.fh = new Float32Array(4); // head p, r, vp, vr
    this.flPush = new Float32Array(2);
    this.cloakA = 0;
    this.cloakV = 0;
    this.prevYaw = 0;
    // buffers
    this.base = new Float32Array(N);
    this.cpose = new Float32Array(N);
    this.out = new Float32Array(N);
    this.last = new Float32Array(N);
    this.snap = new Float32Array(N);
    this.last.set(this.st.stance);
    // rest data (mesh space, scaled)
    const P = this.rest.pos;
    const v = (a) => new THREE.Vector3(a[0], a[1], a[2]);
    this.r = {
      pelvis: v(P.pelvis),
      hipL: v(P.thighL).sub(v(P.pelvis)),
      hipR: v(P.thighR).sub(v(P.pelvis)),
      spine: v(P.spine).sub(v(P.pelvis)),
      chest: v(P.chest).sub(v(P.spine)),
      clavL: v(P.clavL).sub(v(P.chest)),
      clavR: v(P.clavR).sub(v(P.chest)),
      shL: v(P.upperArmL).sub(v(P.clavL)),
      shR: v(P.upperArmR).sub(v(P.clavR)),
      shL0: v(P.upperArmL).sub(v(P.chest)),
      shR0: v(P.upperArmR).sub(v(P.chest)),
      grip: v(this.rest.grip),
    };
    this.len = this.rest.len;
    this.gripLen = this.r.grip.length();
    this.mats = BONES.map(() => new THREE.Matrix4());
    this.groundY = 0;
    this.qChestInv = new THREE.Quaternion();
    this._gripL = new THREE.Vector3();
    this._qItemL = new THREE.Quaternion();
    this._aim = new THREE.Vector3();
  }

  setLocomotion(speed) {
    this.speedCmd = Number.isFinite(speed) ? speed : 0;
    this.dirty = true;
  }

  setGuard(on) {
    this.guardOn = !!on;
    this.dirty = true;
  }

  /** One-shot clip. Replacing a clip drops its pending callbacks. */
  play(clip, opts = {}) {
    const def = CLIPS[clip];
    if (!def) return;
    this.serial++;
    this.snap.set(this.last);
    this.snapW = 1;
    this.snapRate = 1 / Math.max(0.06, Math.min(0.14, (opts.duration || def.duration) * 0.12));
    this.clipName = clip;
    this.clipDef = def;
    this.clipData = clipData(this.style, clip);
    this.clipT = 0;
    this.clipD = Math.max(0.05, opts.duration > 0 ? opts.duration : def.duration);
    this.clipPlaying = true;
    this.clipStruck = false;
    this.onStrike = opts.onStrike || null;
    this.onEnd = opts.onEnd || null;
    this.dirty = true;
  }

  /** Additive hit reaction; dirX/dirZ is the hit direction in world space (attacker → target). */
  flinch(dirX, dirZ, strength = 1) {
    const f = this.root ? this.root.rotation.y : 0;
    const c = Math.cos(f), s = Math.sin(f);
    const lx = dirX * c - dirZ * s, lz = dirX * s + dirZ * c;
    const k = clamp(strength, 0, 2) * (this.isDummy ? 2.2 : 4.2);
    this.fl[3] += lz * k; // pitch: pushed back when hit from the front
    this.fl[4] += -lx * k;
    this.fl[5] += (lx >= 0 ? 1 : -1) * 0.25 * k * (0.5 + 0.5 * Math.abs(lx));
    this.flPush[0] += lx * 0.06 * strength;
    this.flPush[1] += lz * 0.06 * strength;
    this.dirty = true;
  }

  get clip() {
    return this.clipName;
  }

  get playing() {
    return this.clipPlaying;
  }

  get guard() {
    return this.guardOn;
  }

  /** Maps real clip time to the authored normalised time (strike window keeps its speed). */
  clipTime() {
    this.clipTimeF();
    return _f[F_W];
  }

  /** clipTime() into _f[F_W]. */
  clipTimeF() {
    const def = this.clipDef;
    const D = this.clipD, t = this.clipT;
    const rig = def.rigid;
    let u = clamp(t / D, 0, 1);
    if (rig && def.strikeAt > 0) {
      const D0 = def.duration, a = rig[0], b = rig[1], sa = def.strikeAt;
      const k = Math.min(1, D / D0);
      const S = sa * D;
      const pre = (sa - a) * D0 * k, post = (b - sa) * D0 * k;
      const E1 = S - pre, E2 = D - S - post;
      if (E1 > 1e-4 && E2 > 1e-4) {
        if (t < E1) u = (a * t) / E1;
        else if (t < S + post) u = a + (t - E1) / (D0 * k);
        else u = clamp(b + ((1 - b) * (t - S - post)) / E2, 0, 1);
      }
    }
    _f[F_W] = u;
  }

  update(gdt) {
    // clip time advances by the whole step (callbacks stay exact); dynamics use a clamped step
    const step = gdt > 0 ? gdt : 0;
    const dt = Math.min(step, 0.1);
    if (step === 0 && !this.dirty) return;
    this.dirty = false;
    this.t += dt;
    if (this.isDummy) {
      _f[F_DT] = dt;
      this.springs();
      this.applyDummy();
      this.advanceClip(step);
      return;
    }
    // locomotion state
    this.speed += (this.speedCmd - this.speed) * (1 - Math.exp(-10 * dt));
    const aspd = Math.abs(this.speed);
    const target = clamp((aspd - 0.05) / 0.55, 0, 1);
    this.moveW += (smooth(target) - this.moveW) * (1 - Math.exp(-8 * dt));
    this.guardW += ((this.guardOn ? 1 : 0) - this.guardW) * (1 - Math.exp(-9 * dt));
    _f[F_DT] = dt;
    this.buildBase();
    const out = this.out;
    out.set(this.base);
    // clip layer (evaluate at the time reached this frame, then fire callbacks)
    const prevClip = this.clipName;
    if (this.clipName) {
      this.clipT += step;
      this.clipTimeF();
      sampleKeys(this.clipData.keys, this.clipData.tans, _f, F_W, this.cpose);
      const cp = this.cpose;
      const def = this.clipDef;
      let upper = def.layer === 'upper';
      // attacks while moving: legs keep walking
      if (!upper && !def.hold && this.moveW > 0.3 && def.strikeAt > 0 && this.clipName !== 'getup') upper = true;
      if (upper) {
        for (let c = 0; c < N; c++) if (UPPER[c]) out[c] = cp[c];
      } else out.set(cp);
    }
    // crossfade from the previous output
    if (this.snapW > 0) {
      const w = smooth(this.snapW);
      for (let c = 0; c < N; c++) out[c] += (this.snap[c] - out[c]) * w;
      this.snapW = Math.max(0, this.snapW - dt * this.snapRate);
    }
    _f[F_DT] = dt;
    this.springs();
    this.applyFlinch(out);
    this.solve(out);
    this.last.set(out);
    if (prevClip) this.fireCallbacks();
  }

  /** Clip bookkeeping for the dummy (no pose, callbacks only). */
  advanceClip(dt) {
    if (!this.clipName) return;
    this.clipT += dt;
    this.fireCallbacks();
  }

  fireCallbacks() {
    if (!this.clipPlaying) return;
    const serial = this.serial;
    const def = this.clipDef;
    if (!this.clipStruck && this.clipT >= def.strikeAt * this.clipD) {
      this.clipStruck = true;
      const cb = this.onStrike;
      this.onStrike = null;
      if (cb) cb();
      if (serial !== this.serial) return;
    }
    if (this.clipT >= this.clipD) {
      this.clipPlaying = false;
      const cb = this.onEnd;
      this.onEnd = null;
      if (!def.hold) {
        this.snap.set(this.last);
        this.snapW = 1;
        this.snapRate = 6;
        this.clipName = null;
        this.clipDef = null;
      }
      if (cb) cb();
    }
  }

  /** Advances the flinch springs by _f[F_DT]. */
  springs() {
    const dt = _f[F_DT];
    if (dt <= 0) return;
    const w = this.isDummy ? 9 : 15, z = this.isDummy ? 0.16 : 0.62;
    const fl = this.fl;
    for (let i = 0; i < 3; i++) {
      const a = -w * w * fl[i] - 2 * z * w * fl[i + 3];
      fl[i + 3] += a * dt;
      fl[i] += fl[i + 3] * dt;
    }
    // head lags the torso
    const fh = this.fh, wh = 11, zh = 0.45;
    for (let i = 0; i < 2; i++) {
      const a = -wh * wh * (fh[i] - fl[i] * 1.4) - 2 * zh * wh * fh[i + 2];
      fh[i + 2] += a * dt;
      fh[i] += fh[i + 2] * dt;
    }
    const decay = Math.exp(-8 * dt);
    this.flPush[0] *= decay;
    this.flPush[1] *= decay;
  }

  applyFlinch(out) {
    const fl = this.fl, fh = this.fh;
    out[CH.spine] += fl[0] * 0.35;
    out[CH.spine + 2] += fl[1] * 0.3;
    out[CH.chest] += fl[0] * 0.45;
    out[CH.chest + 1] += fl[2] * 0.5;
    out[CH.chest + 2] += fl[1] * 0.35;
    out[CH.neck] += fh[0] * 0.4;
    out[CH.head] += fh[0] * 0.5;
    out[CH.head + 2] += fh[1] * 0.4;
    out[CH.pelP] += this.flPush[0];
    out[CH.pelP + 2] += this.flPush[1];
    const m = Math.min(0.06, Math.abs(fl[0]) * 0.05 + Math.abs(fl[1]) * 0.04);
    out[CH.pelP + 1] -= m;
    out[CH.handL + 1] -= m * 0.6;
    out[CH.handR + 1] -= m * 0.6;
  }

  // -------------------------------------------------------------------------------------
  // Base layer: stance + guard + idle life + gait

  /** Base pose for this frame (time step _f[F_DT]). */
  buildBase() {
    const b = this.base;
    const st = this.st;
    b.set(st.stance);
    const gw = this.guardW;
    if (gw > 0.001) {
      const g = st.guard;
      for (let c = 0; c < N; c++) if (UPPER[c]) b[c] += (g[c] - b[c]) * gw;
    }
    // stance width variety
    b[CH.footL] *= this.stanceWidth;
    b[CH.footR] *= this.stanceWidth;
    b[CH.handL + 1] += this.guardJitter * gw;
    // idle life: breathing, slow weight shift, head look
    const T = this.t + this.phase0;
    const idle = 1 - this.moveW;
    const br = Math.sin(T * PI * 2 * this.breathRate);
    const sw = Math.sin(T * PI * 2 * this.swayRate);
    const sw2 = Math.sin(T * PI * 2 * this.swayRate * 0.53 + 1.3);
    b[CH.chest] += br * 0.022 * (0.6 + 0.4 * idle);
    b[CH.spine] -= br * 0.01;
    b[CH.handL + 1] += br * 0.006;
    b[CH.handR + 1] += br * 0.006;
    b[CH.pelP] += sw * 0.022 * idle;
    b[CH.pelR + 2] += -sw * 0.03 * idle;
    b[CH.spine + 2] += sw * 0.025 * idle;
    b[CH.pelP + 1] += (sw2 * 0.008 - 0.004) * idle;
    b[CH.head + 1] += Math.sin(T * PI * 2 * this.lookRate) * 0.12 * idle * (1 - gw * 0.6);
    b[CH.head] += Math.sin(T * PI * 2 * this.lookRate * 0.7 + 2) * 0.04 * idle;
    if (this.moveW < 0.001 && Math.abs(this.speed) < 0.01) return;
    this.gaitLayer(b);
  }

  gaitLayer(b) {
    const dt = _f[F_DT];
    const v = this.speed;
    const av = Math.abs(v);
    const dir = v < 0 ? -1 : 1;
    const w = this.moveW;
    const run = smooth(clamp((av - 1.9) / 0.8, 0, 1));
    const s = this.s;
    // cadence (steps/s) and stride; one cycle = two steps
    const cadence = 1.5 + 0.42 * Math.max(av, 0.6) + 0.25 * run;
    const stepLen = av / cadence / s / this.meshScale; // unit body metres
    this.gait = (this.gait + (cadence / 2) * dt * dir + 10) % 1;
    const beta = 0.62 - 0.26 * run;
    const D = 2 * beta * stepLen; // stance travel
    const lift = (0.075 + 0.08 * run) * Math.min(1, av / 1.2 + 0.3);
    const width = 0.1 - 0.035 * run;
    for (let leg = 0; leg < 2; leg++) {
      const ch = leg === 0 ? CH.footL : CH.footR;
      const side = leg === 0 ? 1 : -1;
      const p = (this.gait + leg * 0.5) % 1;
      let z, y = 0, pitch = 0;
      if (p < beta) {
        const q = p / beta;
        z = D / 2 - q * D;
        // heel strike → flat → heel off
        pitch = -0.18 * (1 - smooth(clamp(q / 0.15, 0, 1))) * (1 - run) + 0.45 * smooth(clamp((q - 0.65) / 0.35, 0, 1));
      } else {
        const q = (p - beta) / (1 - beta);
        const e = smooth(q);
        z = -D / 2 + D * (e * 1.06 - 0.03 * Math.sin(q * PI));
        y = lift * Math.pow(Math.sin(q * PI), 0.85) + 0.04 * (1 - q) * (1 - q);
        pitch = 0.45 * (1 - smooth(clamp(q / 0.5, 0, 1))) - 0.2 * smooth(clamp((q - 0.6) / 0.4, 0, 1)) * (1 - run);
      }
      if (dir < 0) pitch *= -0.5;
      const fx = side * width;
      b[ch] += (fx - b[ch]) * w;
      b[ch + 1] += (y - b[ch + 1]) * w;
      b[ch + 2] += (z - b[ch + 2]) * w;
      b[ch + 3] += (side * 0.06 - b[ch + 3]) * w;
      b[ch + 4] += (pitch - b[ch + 4]) * w;
    }
    // pelvis: bob (two per cycle), sway toward the stance leg, twist, lean
    const ph = this.gait * PI * 2;
    const bobA = 0.018 + 0.02 * run;
    const bob = run > 0.5 ? Math.cos(ph * 2) : -Math.cos(ph * 2);
    const lean = (0.05 + 0.14 * run) * Math.min(1, av / 1.4) * dir;
    b[CH.pelP] += (Math.sin(ph) * 0.02 * (1 - run) - b[CH.pelP]) * w;
    b[CH.pelP + 1] += (-0.025 - 0.04 * run + bob * bobA - b[CH.pelP + 1]) * w;
    b[CH.pelP + 2] += (0.02 * run - b[CH.pelP + 2]) * w;
    b[CH.pelR] += (lean * 0.5 - b[CH.pelR]) * w;
    b[CH.pelR + 1] += (Math.sin(ph) * (0.09 + 0.05 * run) - b[CH.pelR + 1] * 0.6) * w;
    b[CH.pelR + 2] += (Math.sin(ph) * 0.04 - b[CH.pelR + 2]) * w;
    b[CH.spine] += lean * 0.5 * w;
    b[CH.chest + 1] += -Math.sin(ph) * (0.11 + 0.05 * run) * w;
    b[CH.head] += -lean * 0.6 * w;
    b[CH.head + 1] -= b[CH.head + 1] * 0.7 * w;
    // arm swing (weapon arm more than the shield arm)
    const swing = Math.sin(ph) * (0.05 + 0.08 * run) * Math.min(1, av / 1.4);
    const gwk = 1 - this.guardW * 0.7;
    b[CH.handR + 2] += -swing * gwk * w;
    b[CH.handR + 1] += Math.abs(swing) * 0.2 * w;
    b[CH.handL + 2] += swing * (this.left === 'aspis' || this.left === 'grip' ? 0.3 : 1) * gwk * w;
    b[CH.handL + 1] += Math.abs(Math.cos(ph)) * 0.012 * w + run * 0.03 * w;
    b[CH.handR + 1] += run * 0.04 * w;
    b[CH.cloak] += (0.06 + 0.14 * run) * Math.min(1, av / 1.4) * w * dir;
  }

  // -------------------------------------------------------------------------------------
  // Solve the pose onto the bones

  /** Solves pose p onto the bones (time step _f[F_DT], for the cloak). */
  solve(p) {
    const dt = _f[F_DT];
    const bones = this.bones, s = this.s, r = this.r;
    const q = _q, v = _v;
    // pelvis (mesh space)
    const pel = bones[B.pelvis];
    const pp = pel.position;
    pp.x = r.pelvis.x + p[CH.pelP] * s;
    pp.y = r.pelvis.y + p[CH.pelP + 1] * s;
    pp.z = r.pelvis.z + p[CH.pelP + 2] * s;
    const qP = eulerQa(q[0], p, CH.pelR);
    pel.quaternion.copy(qP);
    eulerQa(bones[B.spine].quaternion, p, CH.spine);
    eulerQa(bones[B.chest].quaternion, p, CH.chest);
    // chest orientation in mesh space, to bring actor-space aims into chest space
    this.qChestInv.copy(qP).multiply(bones[B.spine].quaternion).multiply(bones[B.chest].quaternion).invert();
    eulerQa(bones[B.neck].quaternion, p, CH.neck);
    eulerQa(bones[B.head].quaternion, p, CH.head);
    // ---- legs (pelvis frame)
    const legFK = clamp(p[CH.legFK], 0, 1);
    const qPinv = q[1].copy(qP).invert();
    for (let leg = 0; leg < 2; leg++) {
      const L = leg === 0;
      const fch = L ? CH.footL : CH.footR;
      const thigh = bones[L ? B.thighL : B.thighR], shin = bones[L ? B.shinL : B.shinR], foot = bones[L ? B.footL : B.footR];
      const hip = L ? r.hipL : r.hipR;
      let qThigh = q[2], phi = 0;
      if (legFK < 0.999) {
        const yaw = p[fch + 3];
        // the foot rolls about the ball when the heel lifts and about the heel when the toes
        // lift, so the ankle moves to keep that point where it is (no skating on heel-off)
        const fp = p[fch + 4];
        const pz = fp >= 0 ? PIV_BALL_Z : PIV_HEEL_Z;
        const cp = Math.cos(fp), sp = Math.sin(fp);
        const dy = PIV_Y * (1 - cp) + pz * sp;
        const dz = pz - PIV_Y * sp - pz * cp;
        const T = v[0];
        T.x = (p[fch] + Math.sin(yaw) * dz) * s;
        T.y = this.rest.ankle + (Math.max(0, p[fch + 1]) + dy) * s;
        T.z = (p[fch + 2] + Math.cos(yaw) * dz) * s;
        T.sub(pel.position).applyQuaternion(qPinv);
        const pole = v[1];
        pole.x = Math.sin(yaw);
        pole.y = 0;
        pole.z = Math.cos(yaw);
        pole.applyQuaternion(qPinv);
        _f[F_L1] = this.len.thigh;
        _f[F_L2] = this.len.shin;
        solve2(hip, T, pole, 1, qThigh, v[2], v[3]);
        phi = _f[F_PHI];
        // foot: flat on the ground with yaw and heel pitch, relative to the shin
        _f[F_E] = p[fch + 4];
        _f[F_E + 1] = yaw;
        _f[F_E + 2] = 0;
        const qFootW = eulerQa(q[3], _f, F_E);
        const qShinW = q[4].copy(qP).multiply(qThigh).multiply(axisXQ(_qa, _f, F_PHI));
        foot.quaternion.copy(qShinW.invert().multiply(qFootW));
      } else {
        foot.quaternion.identity();
      }
      if (legFK > 0.001) {
        const tch = L ? CH.thighL : CH.thighR;
        const qFK = eulerQa(q[5], p, tch);
        const shinFK = p[L ? CH.shinL : CH.shinR];
        _f[F_E] = p[L ? CH.footFKL : CH.footFKR];
        _f[F_E + 1] = 0;
        _f[F_E + 2] = 0;
        const footFK = eulerQa(q[6], _f, F_E);
        if (legFK >= 0.999) {
          qThigh.copy(qFK);
          phi = shinFK;
          foot.quaternion.copy(footFK);
        } else {
          qThigh.slerp(qFK, legFK);
          phi += (shinFK - phi) * legFK;
          foot.quaternion.slerp(footFK, legFK);
        }
      }
      thigh.quaternion.copy(qThigh);
      _f[F_PHI] = phi;
      axisXQ(shin.quaternion, _f, F_PHI);
      // skirt panels follow the thigh: front panels when it swings forward, back when back
      const u = v[4].set(0, -1, 0).applyQuaternion(qThigh);
      const pitch = Math.atan2(u.z, -u.y), roll = Math.atan2(u.x, -u.y);
      const sf = bones[L ? B.skirtFL : B.skirtFR], sb = bones[L ? B.skirtBL : B.skirtBR];
      // lying down, the cloth drapes instead of standing out on a raised thigh
      const follow = 1 - 0.65 * clamp(p[CH.ground], 0, 1);
      _f[F_E] = -Math.max(0, pitch) * 0.85 * follow;
      _f[F_E + 1] = 0;
      _f[F_E + 2] = roll * 0.5;
      eulerQa(sf.quaternion, _f, F_E);
      _f[F_E] = -Math.min(0, pitch) * 0.8 * follow;
      eulerQa(sb.quaternion, _f, F_E);
    }
    // ---- arms (chest space)
    this.solveArms(p);
    // ---- cloak: hangs back with speed, sways with turning
    const yaw = this.root ? this.root.rotation.y : 0;
    let dyaw = yaw - this.prevYaw;
    this.prevYaw = yaw;
    if (dyaw > PI) dyaw -= 2 * PI;
    if (dyaw < -PI) dyaw += 2 * PI;
    if (dt > 0) {
      const chestPitch = p[CH.pelR] + p[CH.spine] + p[CH.chest];
      // gravity swings it away from a leaning back; on the ground it lies along the body
      const free = 1 - clamp(p[CH.ground], 0, 1);
      const target = clamp(p[CH.cloak] + (Math.max(0, -chestPitch) * 0.8 - Math.max(0, chestPitch) * 0.3) * free, -0.2, 1.4);
      const a = -60 * (this.cloakA - target) - 9 * this.cloakV;
      this.cloakV += a * dt + Math.abs(dyaw) * 3;
      this.cloakA += this.cloakV * dt;
    }
    const flutter = Math.sin(this.t * 9.3) * 0.03 * this.moveW * Math.min(1, Math.abs(this.speed) / 2);
    _f[F_E] = Math.max(-0.05, this.cloakA + flutter);
    _f[F_E + 1] = 0;
    _f[F_E + 2] = clamp(-dyaw * 4, -0.2, 0.2);
    eulerQa(bones[B.cloak].quaternion, _f, F_E);
    // item visibility
    const ws = clamp(p[CH.wscale], 0, 1), ls = clamp(p[CH.lscale], 0, 1);
    bones[B.weaponR].scale.setScalar(ws < 0.5 ? 1e-4 : 1);
    bones[B.itemL].scale.setScalar(ls < 0.5 ? 1e-4 : 1);
    // ground correction for lying poses
    if (this.clothSquashed) {
      for (let i = 0; i < CLOTH_BONES.length; i++) bones[CLOTH_BONES[i]].scale.set(1, 1, 1);
      this.clothSquashed = false;
    }
    if (p[CH.ground] > 0.01) {
      _f[F_W] = p[CH.ground];
      this.groundCorrect();
    }
  }

  /** Brings an aim/up vector from actor space into chest space by the aimSpace weight _f[F_W]. */
  toChest(vec) {
    const w = _f[F_W];
    if (w <= 0.001 || vec.x * vec.x + vec.y * vec.y + vec.z * vec.z < 1e-8) return vec;
    const a = this._aim.copy(vec).applyQuaternion(this.qChestInv);
    if (w >= 0.999) return vec.copy(a);
    const l = vec.length();
    // vec.lerp(a, w).setLength(l), written out (no double arguments)
    vec.x += (a.x - vec.x) * w;
    vec.y += (a.y - vec.y) * w;
    vec.z += (a.z - vec.z) * w;
    const inv = 1 / (vec.length() || 1);
    vec.x *= inv;
    vec.y *= inv;
    vec.z *= inv;
    vec.x *= l;
    vec.y *= l;
    vec.z *= l;
    return vec;
  }

  solveArms(p) {
    const bones = this.bones, r = this.r, s = this.s, q = _q, v = _v;
    const len = this.len;
    const draw = clamp(p[CH.draw], 0, 1);
    _f[F_W] = clamp(p[CH.aimSpace], 0, 1);
    for (let arm = 0; arm < 2; arm++) {
      const L = arm === 0;
      const side = L ? 1 : -1;
      const hch = L ? CH.handL : CH.handR, ech = L ? CH.elbL : CH.elbR;
      const T = v[5], pole = v[6];
      T.x = p[hch] * s;
      T.y = p[hch + 1] * s;
      T.z = p[hch + 2] * s;
      setA(pole, p, ech);
      // clavicle: shrug when the hand is high, protract when reaching forward
      const sh0 = L ? r.shL0 : r.shR0;
      const elev = clamp((T.y - sh0.y) / (0.45 * s), -0.3, 1);
      const reach = clamp(T.z / (0.5 * s), -0.6, 1);
      _f[F_E] = 0;
      _f[F_E + 1] = -side * 0.22 * reach;
      _f[F_E + 2] = side * 0.2 * Math.max(0, elev);
      const qClav = eulerQa(bones[L ? B.clavL : B.clavR].quaternion, _f, F_E);
      const S = v[7].copy(L ? r.shL : r.shR).applyQuaternion(qClav).add(L ? r.clavL : r.clavR);
      const qU = q[7], elbow = v[8], low = v[9];
      // pass 1 to the grip with a straight wrist, to get the forearm direction
      _f[F_L1] = len.upperArm;
      _f[F_L2] = len.forearm + this.gripLen;
      solve2(S, T, pole, -1, qU, elbow, low);
      // hand / item orientation in chest space
      const qH = q[8];
      const mode = L ? this.left : this.right;
      const toElbow = v[10].copy(low).negate();
      if (L) {
        const aim = this.toChest(setA(v[11], p, CH.aimL));
        const up = this.toChest(setA(v[12], p, CH.upL));
        if (mode === 'aspis') {
          // shield plane contains the forearm: X → elbow, Z ≈ aim
          normalizeV(_ua.copy(toElbow));
          // _uc = aim − (aim·_ua) _ua
          const k = -(aim.x * _ua.x + aim.y * _ua.y + aim.z * _ua.z);
          _uc.x = aim.x + _ua.x * k;
          _uc.y = aim.y + _ua.y * k;
          _uc.z = aim.z + _ua.z * k;
          if (_uc.x * _uc.x + _uc.y * _uc.y + _uc.z * _uc.z < 1e-6) _uc.set(0, 0, 1);
          normalizeV(_uc);
          _ub.crossVectors(_uc, _ua);
          basisQ(q[9], _ua, _ub, _uc); // item
          // hand grips the antilabe: grip axis along the shield's Y, wrist toward the elbow
          basisQ(qH, _uc, _ua, _ub);
        } else if (mode === 'grip' || mode === 'bow') {
          frameQ(q[9], aim, up, toElbow);
          // item axes
          _sx.set(1, 0, 0).applyQuaternion(q[9]);
          _sy.set(0, 1, 0).applyQuaternion(q[9]);
          _sz.set(0, 0, 1).applyQuaternion(q[9]);
          if (mode === 'grip') {
            _ua.copy(_sy).negate();
            _ub.copy(_sz).negate();
            basisQ(qH, _ua, _ub, _sx);
          } else {
            _ua.copy(_sx).negate();
            _ub.copy(_sy).negate();
            basisQ(qH, _ua, _ub, _sz);
          }
        } else {
          frameQ(qH, v[11].set(0, 0, 1).applyQuaternion(qU), toElbow, _uc.set(0, 1, 0));
          q[9].copy(qH);
        }
      } else {
        const aim = this.toChest(setA(v[11], p, CH.aimR));
        if (mode === 'arrow' && draw > 0.01) {
          // arrow lies from the draw hand through the bow grip
          aim.set(p[CH.handL] - p[CH.handR], p[CH.handL + 1] - p[CH.handR + 1], p[CH.handL + 2] - p[CH.handR + 2]);
        }
        const up = this.toChest(setA(v[12], p, CH.upR));
        if (up.x * up.x + up.y * up.y + up.z * up.z < 1e-6) up.copy(toElbow);
        frameQ(qH, aim, up, toElbow);
        q[9].copy(qH);
      }
      // pass 2: wrist target so that the fist centre lands on the grip target
      const wrist = v[13].copy(r.grip).applyQuaternion(qH);
      wrist.subVectors(T, wrist);
      _f[F_L1] = len.upperArm;
      _f[F_L2] = len.forearm;
      solve2(S, wrist, pole, -1, qU, elbow, low);
      // locals
      const qClavInv = _qb.copy(qClav).invert();
      const upper = bones[L ? B.upperArmL : B.upperArmR];
      upper.quaternion.copy(qClavInv).multiply(qU);
      const fore = bones[L ? B.forearmL : B.forearmR];
      axisXQ(fore.quaternion, _f, F_PHI);
      const qFore = _qc.copy(qU).multiply(fore.quaternion);
      const hand = bones[L ? B.handL : B.handR];
      hand.quaternion.copy(qFore).invert().multiply(qH);
      const item = bones[L ? B.itemL : B.weaponR];
      item.quaternion.copy(qH).invert().multiply(q[9]);
      if (L) {
        // remember the bow grip (chest space) for the nock
        const lf = len.forearm;
        wrist.x = low.x * lf + elbow.x;
        wrist.y = low.y * lf + elbow.y;
        wrist.z = low.z * lf + elbow.z;
        this._gripL.copy(r.grip).applyQuaternion(qH).add(wrist);
        this._qItemL.copy(q[9]);
      } else if (this.left === 'bow') {
        // nock follows the drawing fist
        const nock = bones[B.nock];
        if (draw > 0.01) {
          const gripR = v[11].copy(low).multiplyScalar(len.forearm).add(elbow).add(_ua.copy(r.grip).applyQuaternion(qH));
          gripR.sub(this._gripL).applyQuaternion(_qa.copy(this._qItemL).invert());
          gripR.y -= this.bowTipY * s;
          gripR.z += 0.0;
          nock.position.set(0, 0, 0).lerp(gripR, draw);
        } else nock.position.set(0, 0, 0);
      }
    }
  }

  /** Settles a lying pose (ground weight _f[F_W]) onto the ground. */
  groundCorrect() {
    const weight = _f[F_W];
    const bones = this.bones, mats = this.mats, s = this.s;
    for (let i = 0; i < bones.length; i++) {
      const bn = bones[i];
      bn.updateMatrix();
      const parent = BONES[i][1];
      if (parent < 0) mats[i].copy(bn.matrix);
      else mats[i].multiplyMatrices(mats[parent], bn.matrix);
    }
    // how much the body is lying (not kneeling): the chest's up axis toward horizontal
    const up = mats[B.chest].elements[5];
    const lying = weight * clamp((0.75 - up) / 0.45, 0, 1);
    if (lying > 0.001 && this.left && bones[B.itemL].scale.x > 0.5) this.layShield(lying);
    let minY = Infinity;
    const pt = _v[0];
    const cs = this.contacts;
    if (cs) {
      for (let i = 0; i < cs.length; i += 4) {
        pt.set(cs[i + 1], cs[i + 2], cs[i + 3]).applyMatrix4(mats[cs[i]]);
        if (pt.y < minY) minY = pt.y;
      }
    } else {
      for (let i = 0; i < CONTACTS.length; i++) {
        const c = CONTACTS[i];
        pt.set(c[1] * s, c[2] * s, c[3] * s).applyMatrix4(mats[c[0]]);
        const yy = pt.y - c[4] * s;
        if (yy < minY) minY = yy;
      }
      if (this.left && bones[B.itemL].scale.x > 0.5) {
        const ips = this.itemPoints;
        for (let i = 0; i < ips.length; i++) {
          pt.set(ips[i][0] * s, ips[i][1] * s, ips[i][2] * s).applyMatrix4(mats[B.itemL]);
          if (pt.y < minY) minY = pt.y;
        }
      }
    }
    // never sink; settle onto the ground as the pose asks
    const dy = minY < 0 ? -minY : -minY * weight;
    bones[B.pelvis].position.y += dy;
    this.groundY = minY + dy;
    if (this.cloth) this.squashCloth(dy);
    if (lying > 0.001 && this.wFront > 0 && bones[B.weaponR].scale.x > 0.5) this.layWeapon(lying, dy);
  }

  /**
   * Cloak and skirt panels are rigid; where a hem would go through the ground, the panel is
   * flattened along its most vertical axis (cloth lying crumpled under or beside the body).
   */
  squashCloth(dy) {
    const cl = this.cloth, mats = this.mats, bones = this.bones, pt = _v[0];
    for (let k = 0; k < CLOTH_BONES.length; k++) {
      const b = CLOTH_BONES[k];
      let low = Infinity;
      for (let i = 0; i < cl.length; i += 4) {
        if (cl[i] !== b) continue;
        pt.set(cl[i + 1], cl[i + 2], cl[i + 3]).applyMatrix4(mats[b]);
        if (pt.y < low) low = pt.y;
      }
      low += dy;
      const m = mats[b].elements;
      const yo = m[13] + dy;
      const margin = 0.012;
      if (low >= margin || yo <= margin) continue;
      const f = clamp((yo - margin) / (yo - low), 0.08, 1);
      // the bone axis closest to vertical (columns of its mesh-space matrix)
      const ax = Math.abs(m[1]), ay = Math.abs(m[5]), az = Math.abs(m[9]);
      const sc = bones[b].scale;
      if (ax >= ay && ax >= az) sc.x = f;
      else if (ay >= az) sc.y = f;
      else sc.z = f;
      this.clothSquashed = true;
    }
  }

  /** On the ground the shield (or bow) settles flat, face up or down, still on the arm. */
  layShield(weight) {
    const bones = this.bones, mats = this.mats;
    const qHand = _qa.setFromRotationMatrix(_m.extractRotation(mats[B.handL]));
    const qI = _qb.copy(qHand).multiply(bones[B.itemL].quaternion);
    const n = this.left === 'bow' ? _ua.set(1, 0, 0) : _ua.set(0, 0, 1);
    n.applyQuaternion(qI);
    const want = _ub.set(0, n.y >= 0 ? 1 : -1, 0);
    _qc.setFromUnitVectors(n, want).multiply(qI);
    qI.slerp(_qc, clamp(weight, 0, 1));
    const item = bones[B.itemL];
    item.quaternion.copy(qHand.invert()).multiply(qI);
    item.updateMatrix();
    mats[B.itemL].multiplyMatrices(mats[B.handL], item.matrix);
    mats[B.nock].multiplyMatrices(mats[B.itemL], bones[B.nock].matrix);
  }

  /**
   * Lays the carried weapon down beside a body on the ground: its long axis goes level, then
   * tips so that its longer end rests on the ground (a spear never goes through the floor).
   */
  layWeapon(weight, dy) {
    const bones = this.bones, s = this.s;
    const qHand = _qa.setFromRotationMatrix(_m.extractRotation(this.mats[B.handR]));
    const qW = _qb.copy(qHand).multiply(bones[B.weaponR].quaternion);
    const ax = _ua.set(0, 0, 1).applyQuaternion(qW);
    const flat = _ub.set(ax.x, 0, ax.z);
    if (flat.lengthSq() < 1e-6) flat.set(1, 0, 0).applyQuaternion(qW).setY(0);
    if (flat.lengthSq() < 1e-6) flat.set(1, 0, 0);
    flat.normalize();
    const h = this.mats[B.weaponR].elements[13] + dy - 0.016 * s;
    const front = this.wFront * s, back = this.wBack * s;
    const tipDown = front >= back;
    const sinA = clamp(h / (tipDown ? front : back), -0.9, 0.9);
    const cosA = Math.sqrt(1 - sinA * sinA);
    const want = _uc.copy(flat).multiplyScalar(cosA);
    want.y = tipDown ? -sinA : sinA;
    _qc.setFromUnitVectors(ax, want).multiply(qW);
    // roll about the shaft so the flat of the blade lies on the ground (an axe's blade sits on +Y)
    const flatAxis = this.right === 'axe' ? _ub.set(1, 0, 0) : _ub.set(0, 1, 0);
    flatAxis.applyQuaternion(_qc);
    const tgt = _ud.set(0, 1, 0).addScaledVector(want, -want.y);
    if (tgt.lengthSq() > 1e-6) {
      tgt.normalize();
      if (tgt.dot(flatAxis) < 0) tgt.negate();
      _qd.setFromUnitVectors(flatAxis, tgt);
      _qc.premultiply(_qd);
    }
    qW.slerp(_qc, clamp(weight, 0, 1));
    bones[B.weaponR].quaternion.copy(qHand.invert()).multiply(qW);
  }

  applyDummy() {
    const fl = this.fl;
    _f[F_E] = fl[0] * 0.35;
    _f[F_E + 1] = fl[2] * 0.2;
    _f[F_E + 2] = fl[1] * 0.35;
    eulerQa(this.bones[B.spine].quaternion, _f, F_E);
  }
}
