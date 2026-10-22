/**
 * PURE motion math for the first-person viewmodel (DESIGN.md §11): rig constants, easing,
 * one-shot timelines and the slash path solver. Plain [x, y, z] arrays, no three.js, no DOM.
 *
 * Rig space is real-world metres with the eye at the origin, +X right, +Y up, -Z forward.
 * The rig is drawn in vm space under a uniform scale RIG.scale about the eye, which is
 * perspective-neutral: the arms and kit look life-size while the slash tip path (§11) is
 * specified in vm units.
 */
import { clamp } from '../../core/math.js';

export const RIG = {
  scale: 0.6,                       // rig metres → vm units (the strike tip sits 0.92 m out)
  shoulderR: [0.185, -0.215, 0.04],
  shoulderL: [-0.185, -0.215, 0.04],
  upperArm: 0.29,                   // shoulder → elbow
  foreArm: 0.255,                   // elbow → wrist
  wristInGrip: [0.027, 0, 0.075],   // wrist centre in the right hand's grip frame
  swordReach: 0.594,                // grip centre → sword tip (meshes.js SWORD.tipY)
  slashPlaneZ: -0.55,               // vm units: the tip path plane
  slashHalf: 0.35,                  // vm units: the tip path runs from -half·d to +half·d
};

// ---------- easing ----------
export const easeInQuad = (x) => x * x;
export const easeOutQuad = (x) => 1 - (1 - x) * (1 - x);
export const easeOutCubic = (x) => 1 - (1 - x) ** 3;
export const easeInOutSine = (x) => 0.5 - 0.5 * Math.cos(Math.PI * clamp(x, 0, 1));
export const smootherstep = (x) => {
  x = clamp(x, 0, 1);
  return x * x * x * (x * (x * 6 - 15) + 10);
};
/** 0→1 over [a, b] with a smooth start and end. */
export const ramp = (a, b, t) => (b <= a ? (t >= b ? 1 : 0) : smootherstep((t - a) / (b - a)));
/** A bump that rises over [a, b], holds to c, and falls by d. */
export function bump(t, a, b, c, d) {
  if (t <= a || t >= d) return 0;
  if (t < b) return easeInOutSine((t - a) / (b - a));
  if (t <= c) return 1;
  return 1 - easeInOutSine((t - c) / (d - c));
}
/** Damped oscillation that starts at 1 (an immediate displacement) and settles to 0. */
export function jolt(t, freq, decay) {
  if (t < 0) return 0;
  return Math.exp(-decay * t) * Math.cos(2 * Math.PI * freq * t);
}

// ---------- slash timeline ----------
const S0 = -1.3; // path parameter at the cocked pose (the path itself is s ∈ [-1, 1])

/**
 * Derived times for a slash from TIMING.slash ({ strike, total }). The tip velocity is a C1
 * bump: sin² rising over [ta, strike], cos² falling over [strike, tc], peaking at the strike.
 */
export function slashTimes(timing) {
  const strike = timing.strike;
  const total = Math.max(timing.total, strike + 0.12);
  const h1 = Math.min(0.065, strike * 0.65);
  const h2 = Math.min(0.12, Math.max(0.05, total - strike - 0.1));
  const ta = strike - h1;
  const tc = strike + h2;
  // Same peak speed on both sides, so the follow-through travels further: s1 solves s(strike) = 0.
  const s1 = S0 + -S0 * (h1 + h2) / h1;
  // Recovery to guard starts as the follow-through settles and is back on guard before `total`.
  const recover = tc - 0.04;
  return { strike, total, ta, tc, h1, h2, s0: S0, s1, recover, back: recover + 0.75 * (total - recover) };
}

/** Raw path parameter s(t). s = -1 and +1 are the ends of the §11 tip path; s(strike) = 0. */
export function slashParam(t, tm) {
  const { ta, tc, h1, h2, strike, s0, s1 } = tm;
  if (t <= ta) return s0;
  if (t >= tc) return s1;
  const A = (h1 + h2) / 2;
  let area;
  if (t < strike) {
    const x = (t - ta) / h1;
    area = h1 * (x / 2 - Math.sin(Math.PI * x) / (2 * Math.PI));
  } else {
    const x = (t - strike) / h2;
    area = h1 / 2 + h2 * (x / 2 + Math.sin(Math.PI * x) / (2 * Math.PI));
  }
  return s0 + (s1 - s0) * (area / A);
}

/** Beyond the path ends the tip keeps going but saturates and curls back toward the body. */
function shapeParam(s) {
  const a = Math.abs(s);
  if (a <= 1) return s;
  return Math.sign(s) * (1 + 0.55 * Math.tanh((a - 1) / 0.55));
}

/**
 * Hand path tuning (rig metres). The hand follows a scaled copy of the tip path around a centre at
 * the lower right of the view, offset a little perpendicular to the cut toward the arm's side, and
 * its depth comes from the sword's length. That keeps the fist and forearm in view through the
 * strike at every phone aspect, with the blade angled into the scene so its flat stays readable.
 */
export const SLASH = {
  handC: [0.19, -0.12],     // screen-plane offset of the hand from the tip at the strike (lower right)
  minAngle: 0.8,            // the hand sits at least this far (rad) off the cut line, so the blade sweeps flat
  handQ: 0.45,              // fraction of the tip's lateral travel the hand follows at the centre
  handQEnd: 0.22,           // … plus this much more at the path ends (keeps the hand within reach)
  upFollow: 0.6,            // the hand follows less when the tip is high (keeps the arm out of the face)
  leftFollow: 0.8,          // … and when it is to the left (the arm across the body)
  maxLat: 0.9,              // max screen-plane share of the blade (keeps it off edge-on)
  forearm: [0.35, -0.6, 0.7], // where the forearm leaves the fist at guard (picks which edge leads)
};

/** Hand offset from the tip at the strike for a cut along dir, as [x, y] (rig metres). */
export function slashHandOffset(dir, P = SLASH, out = [0, 0]) {
  const r = Math.hypot(P.handC[0], P.handC[1]);
  let a = Math.atan2(P.handC[1], P.handC[0]);
  // Angle between the offset and the cut line (mod π), in (-π/2, π/2].
  let rel = a - dir;
  rel -= Math.PI * Math.round(rel / Math.PI);
  if (Math.abs(rel) < P.minAngle) a += (rel >= 0 ? 1 : -1) * (P.minAngle - Math.abs(rel));
  out[0] = r * Math.cos(a);
  out[1] = r * Math.sin(a);
  return out;
}

const _off = [0, 0];

/**
 * Sword pose for a slash at path parameter s (see slashParam), in rig space.
 * out = { tip:[3], hand:[3], blade:[3] (unit, grip→tip), motion:[3] (unit tip direction of travel) }.
 */
export function slashGeometry(dir, s, out, reach = RIG.swordReach, P = SLASH) {
  const k = RIG.scale;
  const D = -RIG.slashPlaneZ / k;
  const E = RIG.slashHalf / k;
  const dx = Math.cos(dir);
  const dy = Math.sin(dir);
  const g = shapeParam(s);
  const over = Math.max(0, Math.abs(g) - 1);
  // Tip: on the path for |s| ≤ 1; beyond it, curl toward the body and drop a little.
  const tx = g * E * dx;
  const ty = g * E * dy - over * over * 0.3;
  const tz = -D + over * 0.6;
  // Hand: offset from the tip's path, following the tip partly.
  const [ox, oy] = slashHandOffset(dir, P, _off);
  const q = P.handQ + P.handQEnd * Math.min(1, g * g);
  let hx = ox + q * tx * (tx < 0 ? P.leftFollow : 1);
  let hy = oy + q * ty * (ty > 0 ? P.upFollow : 1);
  let lx = tx - hx;
  let ly = ty - hy;
  let lat = Math.hypot(lx, ly);
  const maxLat = P.maxLat * reach;
  if (lat > maxLat) {
    lx *= maxLat / lat;
    ly *= maxLat / lat;
    lat = maxLat;
    hx = tx - lx;
    hy = ty - ly;
  }
  const hz = tz + Math.sqrt(reach * reach - lat * lat);
  out.tip[0] = tx; out.tip[1] = ty; out.tip[2] = tz;
  out.hand[0] = hx; out.hand[1] = hy; out.hand[2] = hz;
  out.blade[0] = lx / reach; out.blade[1] = ly / reach; out.blade[2] = (tz - hz) / reach;
  out.motion[0] = dx; out.motion[1] = dy; out.motion[2] = 0;
  return out;
}

/**
 * Which edge leads (+1/-1) for a slash along dir: the side that keeps the forearm leaving the fist
 * toward the lower right, as at guard. Both xiphos edges are sharp, so either can lead.
 */
export function slashEdgeSign(dir, P = SLASH) {
  const out = createSlashOut();
  slashGeometry(dir, 0, out, RIG.swordReach, P);
  const b = out.blade;
  const m = out.motion;
  const mb = m[0] * b[0] + m[1] * b[1] + m[2] * b[2];
  const f = P.forearm;
  let d = 0;
  for (let i = 0; i < 3; i++) d += (m[i] - mb * b[i]) * f[i];
  return d >= 0 ? 1 : -1;
}

export function createSlashOut() {
  return { tip: [0, 0, 0], hand: [0, 0, 0], blade: [0, 0, 1], motion: [1, 0, 0] };
}

/** Converts a rig-space point to vm units. */
export function rigToVm(p, out = [0, 0, 0]) {
  out[0] = p[0] * RIG.scale;
  out[1] = p[1] * RIG.scale;
  out[2] = p[2] * RIG.scale;
  return out;
}

// ---------- other one-shot curves (seconds of pdt) ----------

/** Forward extension 0..1 of a thrust: a small draw-back, a fast lunge peaking at strike, a hold, a retract. */
export function thrustCurve(t, strike, total) {
  const back = 0.18 * bump(t, 0, strike * 0.35, strike * 0.42, strike * 0.75);
  const out = t < strike
    ? easeInQuad(clamp((t - strike * 0.42) / (strike * 0.58), 0, 1))
    : 1 - easeInOutSine(clamp((t - strike - 0.06) / Math.max(0.05, total - strike - 0.06), 0, 1));
  return t < strike * 0.42 ? -back : out - back;
}

/** Bash punch 0..1 (shield forward), peaking at strike with a jolt after contact. */
export function bashCurve(t, strike, total) {
  if (t <= 0 || t >= total) return 0;
  if (t < strike) {
    const x = t / strike;
    return -0.15 * Math.sin(Math.PI * clamp(x / 0.4, 0, 1)) * (x < 0.4 ? 1 : 0) + (x >= 0.4 ? easeInQuad((x - 0.4) / 0.6) : 0);
  }
  return 1 - easeInOutSine((t - strike) / (total - strike));
}
