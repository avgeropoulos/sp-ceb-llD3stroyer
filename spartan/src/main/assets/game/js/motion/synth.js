/**
 * Kinematic phone simulator (DESIGN.md §5.4). PURE: no DOM, no three.js.
 *
 * A rigid phone is moved through a script of segments in the world frame of §3.1 (metres,
 * Y up, the player facing −Z). Position p(t) and orientation q(t) (screen/body frame → world)
 * are analytic; the sensors are derived from them exactly as a phone would report them:
 * body rates from q, specific force from p̈ and gravity, then the inverse §3.3 remap into the
 * device frame, noise and clipping. Used by the unit tests and by the training demo.
 *
 * Two holds: 'window' (landscape, the blade out of the back of the phone, DESIGN §3.3) and 'hilt'
 * (portrait, the phone upright in the fist, the blade out of its top edge: docs-hilt-grip.md B.4).
 */
import { createRng } from '../core/math.js';

const G = 9.81;
const H = 1e-3; // central-difference step (s)
const WRIST_LAG = 0.03;
const SWING_PAUSE = 0.06;
const THROW_FOLLOW = 0.3;
const LATE_POW = 1.4;
const ACC_CLIP = 78;

const EYE = [0, 1.66, 0];
const PHONE = [0.12, 1.48, -0.42];
const SHOULDER = [0.19, 1.45, 0.03];
const TRUNK_Z = 0.1; // turns and the throw twist rotate about the vertical through (0, ·, 0.1)
const UP = [0, 1, 0];
// Hilt rig (right hand): the phone centre at the guard, and the elbow the forearm pivots on.
const HILT_HAND = [0.15, 1.24, -0.36];
const HILT_ELBOW = [0.21, 1.02, -0.1];
const WRIST_DROP = 0.05; // the wrist pivot: this far from the phone centre along its −y
const THROW_DROP = 0.25; // hilt release: the hand ends this far (rad) below its guard angle about the shoulder
const COCK_ARC = 1.5; // the hilt cock-back: the top of the phone tips back this far (rad)…
const COCK_SHOULDER = 1.4; // …while the hand swings up and back this far about the shoulder

// ---- tiny vector / quaternion helpers ([x,y,z] and [x,y,z,w]) ----

const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const len = (a) => Math.hypot(a[0], a[1], a[2]);
const norm = (a) => {
  const l = len(a);
  return l > 1e-12 ? [a[0] / l, a[1] / l, a[2] / l] : [0, 0, 0];
};

function qAxisAngle(axis, angle) {
  const h = angle * 0.5;
  const s = Math.sin(h);
  return [axis[0] * s, axis[1] * s, axis[2] * s, Math.cos(h)];
}

function qMul(a, b) {
  return [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
}

const qConj = (q) => [-q[0], -q[1], -q[2], q[3]];

/** Rotates vector v by unit quaternion q. */
function qRot(q, v) {
  const [x, y, z, w] = q;
  const tx = 2 * (y * v[2] - z * v[1]);
  const ty = 2 * (z * v[0] - x * v[2]);
  const tz = 2 * (x * v[1] - y * v[0]);
  return [v[0] + w * tx + y * tz - z * ty, v[1] + w * ty + z * tx - x * tz, v[2] + w * tz + x * ty - y * tx];
}

function qNorm(q) {
  const l = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
  return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
}

const qDot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];

function qSlerp(a, b, t) {
  let d = qDot(a, b);
  let bb = b;
  if (d < 0) {
    d = -d;
    bb = [-b[0], -b[1], -b[2], -b[3]];
  }
  if (d > 0.9995) return qNorm([a[0] + (bb[0] - a[0]) * t, a[1] + (bb[1] - a[1]) * t, a[2] + (bb[2] - a[2]) * t, a[3] + (bb[3] - a[3]) * t]);
  const th = Math.acos(d);
  const s = Math.sin(th);
  const wa = Math.sin((1 - t) * th) / s;
  const wb = Math.sin(t * th) / s;
  return [a[0] * wa + bb[0] * wb, a[1] * wa + bb[1] * wb, a[2] * wa + bb[2] * wb, a[3] * wa + bb[3] * wb];
}

const qAngle = (a, b) => 2 * Math.acos(Math.min(1, Math.abs(qDot(a, b))));

/** Quaternion of the rotation whose matrix has columns x, y, z (body axes in world). */
function qFromBasis(x, y, z) {
  const m00 = x[0], m01 = y[0], m02 = z[0];
  const m10 = x[1], m11 = y[1], m12 = z[1];
  const m20 = x[2], m21 = y[2], m22 = z[2];
  const tr = m00 + m11 + m22;
  let q;
  if (tr > 0) {
    const s = 0.5 / Math.sqrt(tr + 1);
    q = [(m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25 / s];
  } else if (m00 > m11 && m00 > m22) {
    const s = 2 * Math.sqrt(1 + m00 - m11 - m22);
    q = [0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s];
  } else if (m11 > m22) {
    const s = 2 * Math.sqrt(1 + m11 - m00 - m22);
    q = [(m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s];
  } else {
    const s = 2 * Math.sqrt(1 + m22 - m00 - m11);
    q = [(m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s];
  }
  return qNorm(q);
}

/** Rotates point p about the axis through `center` by quaternion r. */
const rotAbout = (p, r, center) => add(center, qRot(r, sub(p, center)));

/** Rotates point p about the vertical trunk axis by yaw. */
const yawAbout = (p, yaw) => rotAbout(p, qAxisAngle(UP, yaw), [0, p[1], TRUNK_Z]);

// ---- motion profiles ----

const clamp01 = (t) => (t < 0 ? 0 : t > 1 ? 1 : t);
/** Minimum-jerk profile s(τ) = 10τ³ − 15τ⁴ + 6τ⁵ (peak slope 1.875). */
const s5 = (t) => {
  t = clamp01(t);
  return t * t * t * (10 + t * (-15 + 6 * t));
};
/** Late-peaking throw profile s(τ^1.4). */
const late = (t) => s5(Math.pow(clamp01(t), LATE_POW));
// Peak slope of `late`, so a throw of `arc` lasts arc·LATE_PEAK/speed (computed once; pure math).
const LATE_PEAK = (() => {
  let best = 0;
  for (let i = 1; i < 4000; i++) {
    const t = i / 4000;
    best = Math.max(best, (late(t + 1e-5) - late(t - 1e-5)) / 2e-5);
  }
  return best;
})();

// ---- rig ----

function makeRig(o) {
  const hs = o.hand === 'left' ? -1 : 1;
  const P0 = [PHONE[0] * hs, PHONE[1], PHONE[2]];
  const S0 = [SHOULDER[0] * hs, SHOULDER[1], SHOULDER[2]];
  // Guard: the blade looks through the phone from the eye, screen-x horizontal.
  let b = norm(sub(P0, EYE));
  let R = norm(cross(b, UP));
  let U = cross(R, b);
  // tilt > 0 pitches the phone's top edge back toward the player: the blade tip rises
  // (tilt ≈ 0.5 holds the blade level).
  const ct = Math.cos(o.tilt), st = Math.sin(o.tilt);
  const b1 = add(scale(b, ct), scale(U, st));
  U = sub(scale(U, ct), scale(b, st));
  b = b1;
  // roll about the blade axis (mirrored for the left hand)
  const rq = qAxisAngle(b, o.roll * hs);
  R = qRot(rq, R);
  U = qRot(rq, U);
  let Q0 = qFromBasis(R, U, scale(b, -1));
  // aim: the forearm (and the phone with it) points off the body's forward by this yaw, so the
  // screen faces partly sideways; swings follow the forearm (mirrored for the left hand)
  if (o.aim) Q0 = qNorm(qMul(qAxisAngle(UP, o.aim * hs), Q0));
  return { hs, P0, Q0, S0, G: gripQuat(o.grip, hs) };
}

/**
 * The hilt rig: the phone upright in the fist at H0, screen toward the player, its top edge (the
 * blade, +y) leaning forward by `lean` from vertical (βg = π/2 − lean). Then roll about the blade
 * (mirrored for the left hand) and aim (a yaw about the vertical: the forearm and the fist with it).
 * The elbow (st.el, the strikes' pivot) starts at EL0, behind and below the fist along the aimed
 * forearm. It stays put for wrist motions and during a strike, turns about the shoulder with a
 * cock-back and with the trunk, and comes back to the guard with the hand.
 */
function makeHiltRig(o) {
  const hs = o.hand === 'left' ? -1 : 1;
  const P0 = [HILT_HAND[0] * hs, HILT_HAND[1], HILT_HAND[2]];
  const S0 = [SHOULDER[0] * hs, SHOULDER[1], SHOULDER[2]];
  const aim = qAxisAngle(UP, (o.aim || 0) * hs);
  const EL0 = add(P0, qRot(aim, sub([HILT_ELBOW[0] * hs, HILT_ELBOW[1], HILT_ELBOW[2]], P0)));
  const bg = Math.PI / 2 - o.lean;
  const F = [0, 0, -1];
  const y = add(scale(F, Math.cos(bg)), scale(UP, Math.sin(bg)));
  const z = add(scale(F, -Math.sin(bg)), scale(UP, Math.cos(bg)));
  let Q0 = qFromBasis(cross(y, z), y, z);
  if (o.roll) Q0 = qNorm(qMul(Q0, qAxisAngle([0, 1, 0], o.roll * hs)));
  if (o.aim) Q0 = qNorm(qMul(aim, Q0));
  return { hs, hilt: true, P0, Q0, S0, EL0, G: gripQuat(o.grip, hs) };
}

/** The hilt elbow at the guard after the trunk turned by `yaw`. */
const guardElbow = (rig, yaw) => yawAbout(rig.EL0, yaw);
const FOREARM = len(sub(HILT_ELBOW, HILT_HAND));

/**
 * The recognizer's body-right capture (gestures.js _captureRight) for a phone at attitude q, in the
 * world: forward is the horizontal part of the phone's −z (negated past vertical), blended with its
 * +y when it lies nearly flat; R = forward × up. Strikes are built on it, so the recognizer sees
 * them along the intended axes.
 */
function bodyRight(q) {
  const u = qRot(qConj(q), UP);
  const ux = u[0], uy = u[1], uz = u[2];
  const sg = uy >= 0 ? 1 : -1;
  let fx = sg * uz * ux, fy = sg * uz * uy, fz = sg * (uz * uz - 1);
  const wy = clamp01((0.35 - Math.sqrt(Math.max(0, 1 - uz * uz))) / 0.25) * (uz >= 0 ? 1 : -1);
  if (wy !== 0) {
    fx += wy * -uy * ux;
    fy += wy * (1 - uy * uy);
    fz += wy * -uy * uz;
  }
  let r = [fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux];
  if (len(r) < 1e-6) r = [1 - ux * ux, -ux * uy, -ux * uz];
  const w = qRot(q, norm(r));
  return norm([w[0], 0, w[2]]);
}

/** The blade elevation βabs as the recognizer measures it for this hold (R: its body-right). */
function gripBeta(rig, q, R) {
  if (rig.hilt) {
    const b = qRot(q, [0, 1, 0]);
    const F = cross(UP, R);
    return Math.atan2(b[1], b[0] * F[0] + b[1] * F[1] + b[2] * F[2]);
  }
  const u = qRot(qConj(q), UP);
  return Math.atan2(-u[2], (u[1] >= 0 ? 1 : -1) * Math.hypot(u[0], u[1]));
}

/**
 * The phone's attitude in the hand (body frame of the hand → phone), from opts.grip =
 * { pitch, roll, yaw } in radians: pitch > 0 tilts the screen back (top edge away from the
 * player, screen facing up toward the eyes, the back of the phone pointing down), roll turns the
 * phone about its own back axis, yaw turns the screen to face partly sideways (positive: the
 * screen faces right). Roll and yaw are mirrored for the left hand. The arm's motion is unchanged:
 * only what the sensors see differs (a gripped phone reports the hand's rotation in its own frame).
 */
function gripQuat(grip, hs) {
  if (!grip) return null;
  const pitch = grip.pitch || 0, roll = (grip.roll || 0) * hs, yaw = (grip.yaw || 0) * hs;
  if (!pitch && !roll && !yaw) return null;
  return qNorm(qMul(qMul(qAxisAngle([0, 1, 0], yaw), qAxisAngle([1, 0, 0], -pitch)), qAxisAngle([0, 0, 1], roll)));
}

const guardPose = (rig, yaw) => ({ p: yawAbout(rig.P0, yaw), q: qNorm(qMul(qAxisAngle(UP, yaw), rig.Q0)) });
const rightOf = (yaw) => [Math.cos(yaw), 0, -Math.sin(yaw)];
const forwardOf = (yaw) => [-Math.sin(yaw), 0, -Math.cos(yaw)];
const blade = (q) => qRot(q, [0, 0, -1]);

// ---- segment builders: each appends pieces { t0, dur, f(tl) → {p, q} } and advances st ----

function push(st, pieces, dur, f) {
  if (!(dur > 0)) return;
  pieces.push({ t0: st.t, dur, f });
  const end = f(dur);
  st.p = end.p;
  st.q = end.q;
  st.t += dur;
}

/**
 * The guard a return goes back to: the true guard, or (sloppy returns) one off by `offYaw` about
 * the trunk and `offPitch` about the body's right axis. The body has not turned: st.yaw stays.
 */
function returnPose(rig, yaw, offYaw = 0, offPitch = 0) {
  if (!offYaw && !offPitch) return guardPose(rig, yaw);
  const g = guardPose(rig, yaw + offYaw);
  if (!offPitch) return g;
  const r = qAxisAngle(rightOf(yaw + offYaw), offPitch);
  return { p: g.p, q: qNorm(qMul(r, g.q)) };
}

function pushReturn(st, rig, pieces, dur, g = guardPose(rig, st.yaw)) {
  const p0 = st.p, q0 = st.q;
  let qg = g.q;
  if (qDot(q0, qg) < 0) qg = qg.map((c) => -c);
  push(st, pieces, dur, (tl) => {
    const k = s5(tl / dur);
    return { p: add(p0, scale(sub(g.p, p0), k)), q: qSlerp(q0, qg, k) };
  });
}

function hold(st, pieces, dur) {
  const p = st.p, q = st.q;
  push(st, pieces, dur, () => ({ p, q }));
}

function buildIdle(seg, st, rig, pieces, rng) {
  const dur = seg.duration ?? 1;
  const p0 = st.p, q0 = st.q;
  const f = [0, 1, 2].map(() => rng.range(8, 12));
  const ph = [0, 1, 2, 3].map(() => rng.range(0, Math.PI * 2));
  const ramp = Math.min(0.1, dur / 2);
  push(st, pieces, dur, (tl) => {
    if (tl <= 0 || tl >= dur) return { p: p0, q: q0 };
    const env = s5(tl / ramp) * s5((dur - tl) / ramp) * 0.002;
    const w = 2 * Math.PI * tl;
    const r = [env * Math.sin(w * f[0] + ph[0]), env * Math.sin(w * f[1] + ph[1]), env * Math.sin(w * f[2] + ph[2])];
    const q = qNorm(qMul(q0, [r[0] / 2, r[1] / 2, r[2] / 2, 1]));
    const d = env * 0.015 * Math.sin(w * f[0] + ph[3]); // ~30 µm: ≈ 0.1 m/s²
    return { p: [p0[0] + d, p0[1] + d, p0[2]], q };
  });
}

function buildWalk(seg, st, rig, pieces) {
  const dur = seg.duration ?? 2;
  const p0 = st.p, q0 = st.q;
  const fwd = forwardOf(st.yaw);
  const wStep = 2 * Math.PI * 1.8;
  const aFwd = 2 / (wStep * wStep); // ±2 m/s² fore-aft
  const ramp = Math.min(0.3, dur / 2);
  push(st, pieces, dur, (tl) => {
    if (tl <= 0 || tl >= dur) return { p: p0, q: q0 };
    const env = s5(tl / ramp) * s5((dur - tl) / ramp);
    const bob = 0.02 * Math.sin(wStep * tl) * env; // 0.04 m peak to peak
    const fa = aFwd * Math.sin(wStep * tl + Math.PI / 2) * env;
    const yaw = 0.05 * Math.sin(2 * Math.PI * 0.9 * tl) * env;
    const p = yawAbout(add(add(p0, scale(fwd, fa)), [0, bob, 0]), yaw);
    return { p, q: qMul(qAxisAngle(UP, yaw), q0) };
  });
}

function buildTurn(seg, st, rig, pieces) {
  const yaw = seg.yaw ?? 0;
  const dur = seg.duration ?? Math.max(0.2, (1.875 * Math.abs(yaw)) / 2);
  const p0 = st.p, q0 = st.q;
  push(st, pieces, dur, (tl) => {
    const a = yaw * s5(tl / dur);
    return { p: yawAbout(p0, a), q: qMul(qAxisAngle(UP, a), q0) };
  });
  st.yaw += yaw;
  if (st.el) st.el = yawAbout(st.el, yaw); // (hilt: the elbow turns with the trunk)
}

/**
 * A swing's stroke angle θ(t): a wind-up of `windup` rad the other way at 0.4·speed, a pause, the
 * min-jerk stroke (peak `speed`), and a rebound of recoil·arc at 0.3·speed.
 * t2/t3: the stroke's start and end, ts its duration, t4 the rebound's end.
 */
function strokeProfile(speed, arc, windup, recoil) {
  const tw = windup > 0 ? (1.875 * windup) / (0.4 * speed) : 0;
  const t1 = tw;
  const t2 = t1 + (windup > 0 ? SWING_PAUSE : 0);
  const ts = (1.875 * arc) / speed;
  const t3 = t2 + ts;
  const tr = recoil > 0 ? (1.875 * recoil * arc) / (0.3 * speed) : 0;
  const t4 = t3 + tr;
  const theta = (t) => {
    if (t <= 0) return 0;
    if (t < t1) return -windup * s5(t / tw);
    if (t < t2) return -windup;
    if (t < t3) return -windup + arc * s5((t - t2) / ts);
    if (t < t4) return -windup + arc - recoil * arc * s5((t - t3) / tr);
    return -windup + arc - recoil * arc;
  };
  return { theta, t2, t3, t4, ts };
}

function buildSwing(seg, st, rig, pieces) {
  const dir = seg.dir ?? 0;
  const speed = Math.max(0.1, seg.speed ?? 9);
  const arc = seg.arc ?? 1.6;
  const radius = seg.radius ?? 0.55;
  const windup = seg.windup ?? 0.3;
  const recoil = seg.recoil ?? 0.15;
  const ret = seg.ret ?? 2.5;
  const wrist = seg.wrist ?? 0.3;
  const pronate = seg.pronate ?? 0.3;
  // Wrist roll about the forearm (horizontal and forward at the swing's start, then carried by the
  // arm): wristRoll·sin(π·progress) during the stroke, plus rollEnd·progress that stays until the
  // return (mirrored for the left hand). A hand-local rotation: it does not tilt the arm's sweep.
  const wristRoll = (seg.wristRoll ?? 0) * rig.hs;
  const rollEnd = (seg.rollEnd ?? 0) * rig.hs;
  const p0 = st.p, q0 = st.q;
  const b = blade(q0);
  let fore = [b[0], 0, b[2]];
  fore = len(fore) > 0.2 ? norm(fore) : forwardOf(st.yaw);
  const foreLocal = qRot(qConj(q0), fore);
  // View frame at the blade, gravity-aligned and unwrapped past vertical like §5.3:
  // R = s·normalize(b × up), U = R × b is the direction in which the blade elevation rises.
  const sgn = qRot(q0, UP)[1] >= 0 ? 1 : -1;
  let R = cross(b, UP);
  R = len(R) < 1e-3 ? rightOf(st.yaw) : scale(norm(R), sgn);
  const U = cross(R, b);
  const d = add(scale(R, Math.cos(dir)), scale(U, Math.sin(dir)));
  const n = norm(cross(b, d));
  const pivot = sub(p0, scale(b, radius)); // the blade is radial from the pivot
  const { theta, t2, t3, t4, ts } = strokeProfile(speed, arc, windup, recoil);
  const diag = Math.sin(2 * dir) * rig.hs; // diagonals only; mirrored for the left hand
  const zBody = [0, 0, 1];
  push(st, pieces, t4 + WRIST_LAG, (tl) => {
    const th = theta(tl);
    const ph = (1 - wrist) * th + wrist * theta(tl - WRIST_LAG);
    const p = rotAbout(p0, qAxisAngle(n, (1 - wrist) * th), pivot);
    let q = qMul(qAxisAngle(n, ph), q0);
    if (diag !== 0 && tl > t2 && tl < t3) {
      // roll about the blade (body −z)
      q = qMul(q, qAxisAngle(zBody, -pronate * diag * Math.sin(Math.PI * s5((tl - t2) / ts))));
    }
    if ((wristRoll || rollEnd) && tl > t2) {
      const k = tl < t3 ? s5((tl - t2) / ts) : 1;
      const r = wristRoll * Math.sin(Math.PI * k) + rollEnd * k;
      if (r) q = qMul(q, qAxisAngle(foreLocal, r));
    }
    return { p, q: qNorm(q) };
  });
  if (ret > 0) {
    const g = returnPose(rig, st.yaw, seg.retYaw || 0, seg.retPitch || 0);
    const ang = qAngle(st.q, g.q);
    pushReturn(st, rig, pieces, Math.max(0.15, (1.875 * ang) / ret), g);
  }
}

/**
 * Hilt swing: the tip of the upright blade moves across the view in direction `dir`. With R and b0
 * the body-right capture and the blade (+y) at the start, the axis is
 * n = normalize(sin dir·R − cos dir·(R × b0)), so the recognizer sees (cos dir, sin dir); `yawShare`
 * blends its horizontal part toward a yaw about world up (a forearm sweep). The arm turns
 * (1 − wrist)·θ about n through the elbow; the wrist adds wrist·θ 30 ms later (orientation only).
 * Wind-up, profile, recoil, return, pronation, wrist roll and sloppy returns as for the window hold;
 * the fist rolls (pronation and wrist roll) about the long axis of the phone it grips (its +y, with
 * opts.grip), as the window hold rolls about the blade.
 */
function buildSwingHilt(seg, st, rig, pieces) {
  const dir = seg.dir ?? 0;
  const speed = Math.max(0.1, seg.speed ?? 9);
  const arc = seg.arc ?? 1.4;
  const windup = seg.windup ?? 0.3;
  const recoil = seg.recoil ?? 0.15;
  const ret = seg.ret ?? 2.5;
  const wrist = seg.wrist ?? 0.45;
  const pronate = seg.pronate ?? 0.2;
  const yawShare = clamp01(seg.yawShare ?? 0);
  const wristRoll = (seg.wristRoll ?? 0) * rig.hs;
  const rollEnd = (seg.rollEnd ?? 0) * rig.hs;
  const p0 = st.p, q0 = st.q;
  const R = bodyRight(q0);
  const b0 = qRot(q0, [0, 1, 0]);
  let n = norm(sub(scale(R, Math.sin(dir)), scale(cross(R, b0), Math.cos(dir))));
  if (yawShare > 0) {
    const ny = add(scale(n, 1 - yawShare), scale(UP, -yawShare * Math.cos(dir)));
    if (len(ny) > 1e-6) n = norm(ny);
  }
  const elbow = st.el;
  const { theta, t2, t3, t4, ts } = strokeProfile(speed, arc, windup, recoil);
  const diag = Math.sin(2 * dir) * rig.hs; // diagonals only; mirrored for the left hand
  const rollAxis = rig.G ? qRot(rig.G, [0, 1, 0]) : [0, 1, 0]; // the phone's long axis, in the hand
  push(st, pieces, t4 + WRIST_LAG, (tl) => {
    const th = theta(tl);
    const ph = (1 - wrist) * th + wrist * theta(tl - WRIST_LAG);
    const p = rotAbout(p0, qAxisAngle(n, (1 - wrist) * th), elbow);
    let q = qMul(qAxisAngle(n, ph), q0);
    if (diag !== 0 && tl > t2 && tl < t3) {
      q = qMul(q, qAxisAngle(rollAxis, pronate * diag * Math.sin(Math.PI * s5((tl - t2) / ts))));
    }
    if ((wristRoll || rollEnd) && tl > t2) {
      const k = tl < t3 ? s5((tl - t2) / ts) : 1;
      const r = wristRoll * Math.sin(Math.PI * k) + rollEnd * k;
      if (r) q = qMul(q, qAxisAngle(rollAxis, r));
    }
    return { p, q: qNorm(q) };
  });
  if (ret > 0) {
    // the arm pivots at the elbow, so the hand travels too: the return covers the blade's turn and
    // the hand's way back (over the forearm) at `ret` rad/s, whichever takes longer
    const g = returnPose(rig, st.yaw, seg.retYaw || 0, seg.retPitch || 0);
    const ang = Math.max(qAngle(st.q, g.q), len(sub(st.p, g.p)) / FOREARM);
    pushReturn(st, rig, pieces, Math.max(0.15, (1.875 * ang) / ret), g);
    st.el = guardElbow(rig, st.yaw + (seg.retYaw || 0));
  }
}

/** Hilt thrust: the fist punches along the body forward (up × R), the tip dipping forward by `dip`. */
function buildThrustHilt(seg, st, rig, pieces) {
  const D = seg.distance ?? 0.3;
  const T = Math.max(0.03, seg.duration ?? 0.18);
  const holdT = seg.hold ?? 0.08;
  const retract = Math.max(0.03, seg.retract ?? 0.3);
  const dip = seg.dip ?? 0.15;
  const p0 = st.p, q0 = st.q;
  const R = bodyRight(q0);
  const F = cross(UP, R);
  push(st, pieces, T + holdT + retract, (tl) => {
    let k;
    if (tl < T) k = s5(tl / T);
    else if (tl < T + holdT) k = 1;
    else k = 1 - s5((tl - T - holdT) / retract);
    return { p: add(p0, scale(F, D * k)), q: qMul(qAxisAngle(R, -dip * k), q0) };
  });
}

/**
 * Hilt cock-back: the top of the phone tips back by cockArc about R (peak rate `speed`) while the
 * hand swings up and back by shoulderArc about R through the shoulder (position only).
 */
function cockPieces(st, rig, pieces, R, cockArc, shoulderArc, speed) {
  const p0 = st.p, q0 = st.q;
  const S = yawAbout(rig.S0, st.yaw);
  const T = (1.875 * Math.abs(cockArc)) / Math.max(0.1, speed);
  push(st, pieces, T, (tl) => {
    const k = s5(tl / T);
    return { p: rotAbout(p0, qAxisAngle(R, shoulderArc * k), S), q: qMul(qAxisAngle(R, cockArc * k), q0) };
  });
  st.el = rotAbout(st.el, qAxisAngle(R, shoulderArc), S); // the whole arm turned about the shoulder
}

/**
 * The hand's swing about the shoulder for a cock-back of `cockArc`: 1.4 rad for the full 1.5 rad
 * cock-back, in proportion for a smaller lift (which barely moves the hand).
 */
const shoulderArcOf = (seg, cockArc) => seg.shoulderArc ?? (COCK_SHOULDER * cockArc) / COCK_ARC;

/** Hilt raise: the cock-back alone, held (a small cockArc is the ordinary lift before a chop). */
function buildRaiseHilt(seg, st, rig, pieces) {
  const cockArc = seg.cockArc ?? COCK_ARC;
  cockPieces(st, rig, pieces, bodyRight(st.q), cockArc, shoulderArcOf(seg, cockArc), seg.windupSpeed ?? 5);
  hold(st, pieces, seg.hold ?? 0.2);
}

/**
 * Hilt throw: the cock-back behind the shoulder, a hold, then the release: the phone turns −arc
 * about R (late-peaking, peak `speed`) while the hand swings down to THROW_DROP below its guard
 * angle about the advancing shoulder and the trunk twists. Then the follow-through and the return.
 */
function buildThrowHilt(seg, st, rig, pieces) {
  const speed = Math.max(0.1, seg.speed ?? 7);
  const cockArc = seg.cockArc ?? COCK_ARC;
  const shoulderArc = shoulderArcOf(seg, cockArc);
  const arc = seg.arc ?? 3.0;
  const twist = (seg.twist ?? 0.45) * rig.hs;
  const lookBack = Math.max(0.1, seg.lookBack ?? 0.7);
  const q00 = st.q, p00 = st.p;
  const R = bodyRight(q00);
  cockPieces(st, rig, pieces, R, cockArc, shoulderArc, seg.windupSpeed ?? 5);
  hold(st, pieces, seg.hold ?? 0.12);
  const S = yawAbout(rig.S0, st.yaw);
  const fwd = forwardOf(st.yaw);
  const T = (arc * LATE_PEAK) / speed;
  push(st, pieces, T, (tl) => {
    const g = late(tl / T);
    const r = qAxisAngle(R, cockArc - arc * g); // relative to the pose before the cock-back
    const rp = qAxisAngle(R, shoulderArc - (shoulderArc + THROW_DROP) * g);
    const zeta = twist * g;
    const pArm = add(add(S, scale(fwd, 0.25 * g)), qRot(rp, sub(p00, S)));
    return { p: yawAbout(pArm, zeta), q: qNorm(qMul(qAxisAngle(UP, zeta), qMul(r, q00))) };
  });
  hold(st, pieces, THROW_FOLLOW);
  pushReturn(st, rig, pieces, lookBack);
  st.el = guardElbow(rig, st.yaw);
}

/**
 * Both holds: the blade turns about R through the wrist until its elevation (the hold's βabs, as
 * the recognizer measures it) is `beta`, min-jerk over `duration`: a slow lower to a low guard, or
 * back up. With the defaults it peaks below 1.5 rad/s.
 */
function buildLower(seg, st, rig, pieces) {
  const dur = Math.max(0.05, seg.duration ?? 1.4);
  const p0 = st.p, q0 = st.q;
  const R = bodyRight(q0);
  const d = (seg.beta ?? 0.1) - gripBeta(rig, q0, R);
  const W = add(p0, qRot(q0, [0, -WRIST_DROP, 0]));
  push(st, pieces, dur, (tl) => {
    const r = qAxisAngle(R, d * s5(tl / dur));
    return { p: rotAbout(p0, r, W), q: qMul(r, q0) };
  });
}

function buildThrust(seg, st, rig, pieces) {
  const D = seg.distance ?? 0.35;
  const T = Math.max(0.03, seg.duration ?? 0.18);
  const holdT = seg.hold ?? 0.08;
  const retract = Math.max(0.03, seg.retract ?? 0.3);
  const dip = seg.dip ?? 0.12;
  const p0 = st.p, q0 = st.q;
  const b = blade(q0);
  const sgn = qRot(q0, UP)[1] >= 0 ? 1 : -1;
  let R = cross(b, UP);
  R = len(R) < 1e-3 ? rightOf(st.yaw) : scale(norm(R), sgn);
  const total = T + holdT + retract;
  push(st, pieces, total, (tl) => {
    let k;
    if (tl < T) k = s5(tl / T);
    else if (tl < T + holdT) k = 1;
    else k = 1 - s5((tl - T - holdT) / retract);
    return { p: add(p0, scale(b, D * k)), q: qMul(qAxisAngle(R, -dip * k), q0) };
  });
}

function windupPieces(st, rig, pieces, arc, speed) {
  const p0 = st.p, q0 = st.q;
  const R = rightOf(st.yaw);
  const S = yawAbout(rig.S0, st.yaw);
  const T = (1.875 * arc) / Math.max(0.1, speed);
  push(st, pieces, T, (tl) => {
    const r = qAxisAngle(R, arc * s5(tl / T));
    return { p: rotAbout(p0, r, S), q: qMul(r, q0) };
  });
}

function buildRaise(seg, st, rig, pieces) {
  windupPieces(st, rig, pieces, seg.windupArc ?? 2.5, seg.windupSpeed ?? 6);
  hold(st, pieces, seg.hold ?? 0.2);
}

function buildThrow(seg, st, rig, pieces) {
  const speed = Math.max(0.1, seg.speed ?? 13);
  const windupArc = seg.windupArc ?? 2.5;
  const arc = seg.arc ?? 3.4;
  const twist = (seg.twist ?? 0.45) * rig.hs;
  const lookBack = Math.max(0.1, seg.lookBack ?? 0.7);
  const q00 = st.q, p00 = st.p;
  windupPieces(st, rig, pieces, windupArc, seg.windupSpeed ?? 6);
  hold(st, pieces, seg.hold ?? 0.12);
  // The release: about R̂ through the (advancing) shoulder, late-peaking, with a trunk twist.
  const R = rightOf(st.yaw);
  const S = yawAbout(rig.S0, st.yaw);
  const fwd = forwardOf(st.yaw);
  const T = (arc * LATE_PEAK) / speed;
  push(st, pieces, T, (tl) => {
    const g = late(tl / T);
    const r = qAxisAngle(R, windupArc - arc * g); // relative to the pose before the wind-up
    const zeta = twist * g;
    const pArm = add(add(S, scale(fwd, 0.25 * g)), qRot(r, sub(p00, S)));
    return { p: yawAbout(pArm, zeta), q: qNorm(qMul(qAxisAngle(UP, zeta), qMul(r, q00))) };
  });
  hold(st, pieces, THROW_FOLLOW);
  pushReturn(st, rig, pieces, lookBack);
}

const BUILDERS = {
  idle: buildIdle,
  walk: buildWalk,
  turn: buildTurn,
  swing: buildSwing,
  thrust: buildThrust,
  throw: buildThrow,
  raise: buildRaise,
  lower: buildLower,
};
// what 'hilt' changes (idle, walk, turn and lower are shared)
const HILT_BUILDERS = {
  swing: buildSwingHilt,
  thrust: buildThrustHilt,
  throw: buildThrowHilt,
  raise: buildRaiseHilt,
};

function normOpts(opts = {}) {
  const o = {
    rate: 100, rot: 90, tilt: 0, roll: 0, aim: 0, grip: null, hand: 'right', noise: 1, seed: 1, gravitySign: 1,
    includeLinear: true, includeGravity: true, gyroRange: 34.9, jitter: 0, t0: 0, hold: 'window', lean: 0.45,
    ...opts,
  };
  // the hilt is held in portrait: rot 0 unless given (a natural-landscape tablet reports 90 or 270)
  if (o.hold === 'hilt' && (opts == null || opts.rot == null)) o.rot = 0;
  return o;
}

function compile(script, o) {
  const rig = o.hold === 'hilt' ? makeHiltRig(o) : makeRig(o);
  const rng = createRng((o.seed >>> 0) * 2654435761 + 7);
  const g0 = guardPose(rig, 0);
  const st = { p: g0.p, q: g0.q, yaw: 0, t: 0, el: rig.hilt ? rig.EL0 : null };
  const pieces = [];
  for (const seg of script || []) {
    const kind = seg && seg.kind;
    const build = (rig.hilt && HILT_BUILDERS[kind]) || BUILDERS[kind];
    if (!build) throw new Error(`synth: unknown segment kind "${seg && seg.kind}"`);
    build(seg, st, rig, pieces, rng);
  }
  const first = g0;
  const last = { p: st.p, q: st.q };
  const pose = (t) => {
    if (pieces.length === 0 || t <= 0) return first;
    let lo = 0, hi = pieces.length - 1;
    if (t >= pieces[hi].t0 + pieces[hi].dur) return last;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (pieces[mid].t0 <= t) lo = mid;
      else hi = mid - 1;
    }
    return pieces[lo].f(t - pieces[lo].t0);
  };
  // the phone as gripped in the hand (opts.grip): what the sensors see
  const G = rig.G;
  const phonePose = G ? (t) => {
    const h = pose(t);
    return { p: h.p, q: qMul(h.q, G) };
  } : pose;
  return { pose: phonePose, duration: st.t, rng, rig };
}

/** Total length of a script in seconds (with default synth options unless given). */
export function scriptDuration(script, opts) {
  return compile(script, normOpts(opts)).duration;
}

/**
 * Simulates the phone sensors for a script (DESIGN.md §5.4).
 * @param {object[]} script segments, back-to-back
 * @param {object} [opts] { rate, rot, tilt, roll, aim, grip: { pitch, roll, yaw }, hand, noise, seed,
 *   gravitySign, includeLinear, includeGravity, gyroRange, jitter, t0, hold, lean }
 *   hold: 'window' (default) or 'hilt'. In the hilt hold `lean` (0.45) is the blade's forward lean
 *   from vertical at the guard, rot defaults to 0 and tilt is ignored; 'window' output is unchanged.
 * @returns {{t:number, gyro:number[], acc:number[]|null, accG:number[]|null, grav:number[]|null, rot:number}[]}
 *   RawSamples in the device frame (§5.1)
 */
export function synthesize(script, opts) {
  const o = normOpts(opts);
  const { pose, duration, rng } = compile(script, o);
  const rot = ((Math.round(o.rot) % 360) + 360) % 360;
  const cr = Math.round(Math.cos((rot * Math.PI) / 180));
  const sr = Math.round(Math.sin((rot * Math.PI) / 180));
  // inverse remap (§3.3): screen → device
  const toDevice = (v) => [cr * v[0] + sr * v[1], -sr * v[0] + cr * v[1], v[2]];
  const sign = o.gravitySign < 0 ? -1 : 1;
  const gN = 0.02 * o.noise, aN = 0.15 * o.noise;
  const clip = (v, lim) => (v > lim ? lim : v < -lim ? -lim : v);
  const noisy = (v, sd, lim) => [clip(v[0] + rng.gauss() * sd, lim), clip(v[1] + rng.gauss() * sd, lim), clip(v[2] + rng.gauss() * sd, lim)];
  const step = 1 / Math.max(1, o.rate);
  const gWorld = [0, G, 0];
  const out = [];
  for (let t = 0; t <= duration + 1e-9; ) {
    const pm = pose(t - H), pc = pose(t), pp = pose(t + H);
    const pdd = scale(add(sub(pp.p, scale(pc.p, 2)), pm.p), 1 / (H * H));
    let qp = pp.q, qm = pm.q;
    if (qDot(qp, pc.q) < 0) qp = qp.map((c) => -c);
    if (qDot(qm, pc.q) < 0) qm = qm.map((c) => -c);
    const qd = [(qp[0] - qm[0]) / (2 * H), (qp[1] - qm[1]) / (2 * H), (qp[2] - qm[2]) / (2 * H), (qp[3] - qm[3]) / (2 * H)];
    const wq = qMul(qConj(pc.q), qd);
    const omega = [2 * wq[0], 2 * wq[1], 2 * wq[2]];
    const qi = qConj(pc.q);
    const accLin = scale(qRot(qi, pdd), sign);
    const accG = scale(qRot(qi, add(pdd, gWorld)), sign);
    const grav = scale(qRot(qi, gWorld), sign);
    out.push({
      t: o.t0 + t,
      gyro: noisy(toDevice(omega), gN, o.gyroRange),
      acc: o.includeLinear ? noisy(toDevice(accLin), aN, ACC_CLIP) : null,
      accG: noisy(toDevice(accG), aN, ACC_CLIP),
      grav: o.includeGravity ? noisy(toDevice(grav), gN, ACC_CLIP) : null,
      rot,
    });
    t += step * (1 + (o.jitter ? o.jitter * (2 * rng.next() - 1) : 0));
  }
  return out;
}
