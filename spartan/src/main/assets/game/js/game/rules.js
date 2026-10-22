/**
 * Gameplay rules (DESIGN.md §7). PURE: timings, weapon and enemy stats, damage, hit geometry,
 * targeting and ballistics. No DOM, no three.js; points are duck-typed { x, y, z }.
 * Every `yaw` here uses the camera convention (0 faces -Z, positive turns left).
 */
import { angleDiff, clamp, wrapAngle, yawTo } from '../core/math.js';

/** Seconds of pdt. strike = when damage resolves; total = attack length; cancelAfter = earliest buffered follow-up. */
export const TIMING = {
  slash: { strike: 0.10, total: 0.34, cancelAfter: 0.16 },
  thrust: { strike: 0.11, total: 0.38, cancelAfter: 0.20 },
  spearThrust: { strike: 0.13, total: 0.44, cancelAfter: 0.24 },
  bash: { strike: 0.09, total: 0.45, cancelAfter: 0.30, cooldown: 0.9 },
  throw: { windup: 0.14, total: 0.60, redraw: 0.6 }, // motion throws skip windup (spawn after 0.03)
  shieldRaise: 0.14, shieldLower: 0.18, shieldDip: 0.45,
  perfectBlockWindow: 0.22, perfectNeedsDown: 0.25, critWindow: 1.0,
};

export const WEAPONS = {
  sword: { reach: 2.1, slashDamage: 34, thrustDamage: 40, thrustHalfAngle: 0.35 },
  spear: { reach: 3.0, slashDamage: 22, thrustDamage: 55, thrustHalfAngle: 0.24, throwDamage: 200, throwSpeed: [20, 34] },
  shield: { reach: 1.6, halfAngle: 0.44, bashDamage: 8, bashKnockback: 0.9, bashStagger: 1.1, rebashImmunity: 2.5 },
};

/** Seconds, metres, m/s. */
export const ENEMIES = {
  levy: { hp: 55, guard: 0.25, damage: 10, windup: 0.85, interval: [3.0, 4.5], walk: 1.4, run: 2.4, reach: 1.9, attackClip: 'thrust' },
  sparabara: { hp: 70, guard: 0.70, damage: 12, windup: 0.90, interval: [3.2, 4.8], walk: 1.2, run: 2.0, reach: 2.2, attackClip: 'thrust' },
  immortal: { hp: 90, guard: 0.45, damage: 16, windup: 0.70, interval: [2.4, 3.4], walk: 1.5, run: 2.8, reach: 2.2, attackClip: 'overhead' },
  captain: { hp: 180, guard: 0.30, damage: 28, windup: 1.00, interval: [3.5, 5.0], walk: 1.3, run: 2.2, reach: 1.8, attackClip: 'axe', heavy: true },
  archer: { hp: 40, guard: 0, damage: 8, windup: 1.20, interval: [4.0, 6.0], walk: 1.4, run: 2.4, reach: 45, attackClip: 'shoot' },
  spartan: { hp: 150, guard: 0.60, damage: 30, windup: 0.60, interval: [1.6, 2.6], walk: 1.4, run: 3.0, reach: 2.6, attackClip: 'thrust' },
  dummy: { hp: Infinity, guard: 0, damage: 0 },
  helot: { hp: 1, guard: 0, damage: 0, walk: 3.5 },
};

/** Limits on attacks whose target is the player, by day. */
export const DIRECTOR = {
  1: { engaged: 2, windups: 1, rate: 0.18, gap: 0.6 },
  2: { engaged: 3, windups: 2, rate: 0.28, gap: 0.4 },
  3: { engaged: 4, windups: 2, rate: 0.40, ratePerWave: 0.04, rateMax: 0.9, gap: 0.3 },
};

const TOKENS = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
const SECTOR = Math.PI / 4;

/**
 * Screen-space slash direction (0 = rightward, PI/2 = up, ±PI = left, -PI/2 = down) to one of
 * eight 45° tokens centred on their angles. A non-finite dir gives 'R'.
 */
export function dirToken(dir) {
  if (!Number.isFinite(dir)) return 'R';
  const i = Math.round(wrapAngle(dir) / SECTOR);
  return TOKENS[((i % 8) + 8) % 8];
}

/** Token sets (§3.4). Combo steps are Sets of tokens. */
export const TOKEN_SETS = {
  Rish: new Set(['R', 'UR', 'DR']),
  Lish: new Set(['L', 'UL', 'DL']),
  Uish: new Set(['U', 'UL', 'UR']),
  Dish: new Set(['D', 'DL', 'DR']),
};

const PROFILES = {};
for (const token of TOKENS) {
  const vertical = token === 'U' || token === 'D';
  const horizontal = token === 'R' || token === 'L';
  PROFILES[token] = Object.freeze({
    token,
    category: horizontal ? 'horizontal' : vertical ? 'vertical' : 'diagonal',
    halfAngle: horizontal ? 0.70 : vertical ? 0.35 : 0.55,
    damageMul: horizontal ? 1.0 : vertical ? 1.35 : 1.15,
    overShield: token === 'D',
  });
}

/**
 * Slash shape for a direction: horizontal cuts are wide, verticals narrow and heavy, and the
 * overhead chop (D) goes over the shield. Returns a shared frozen object.
 * @returns {{ token: string, category: 'horizontal'|'diagonal'|'vertical', halfAngle: number, damageMul: number, overShield: boolean }}
 */
export function slashProfile(dir) {
  return PROFILES[dirToken(dir)];
}

/**
 * Horizontal sector test. dist = |target - origin| in XZ; angle = signed angleDiff(yaw, yawTo(dx, dz))
 * (positive = target on the left). Hit iff the target's circle reaches into the sector.
 * @returns {{ hit: boolean, dist: number, angle: number }} `out` if given.
 */
export function sectorHit(origin, yaw, reach, halfAngle, target, radius = 0, out) {
  const dx = target.x - origin.x;
  const dz = target.z - origin.z;
  const dist = Math.hypot(dx, dz);
  const angle = dist > 1e-9 ? angleDiff(yaw, yawTo(dx, dz)) : 0;
  const hit = dist - radius <= reach && Math.abs(angle) - Math.atan2(radius, Math.max(dist, 0.01)) <= halfAngle;
  const o = out || {};
  o.hit = hit;
  o.dist = dist;
  o.angle = angle;
  return o;
}

/**
 * Best target in a cone: the living actor minimizing |angle| + 0.15·dist with |angle| ≤ maxAngle
 * and dist ≤ maxDist (centre to centre, XZ). `filter(actor)` may reject candidates.
 */
export function pickTarget(actors, origin, yaw, maxAngle, maxDist, filter) {
  let best = null;
  let bestScore = Infinity;
  if (!actors) return null;
  for (let i = 0; i < actors.length; i++) {
    const a = actors[i];
    if (!a || !a.alive || !a.pos) continue;
    if (filter && !filter(a)) continue;
    const dx = a.pos.x - origin.x;
    const dz = a.pos.z - origin.z;
    const dist = Math.hypot(dx, dz);
    if (dist > maxDist) continue;
    const angle = dist > 1e-9 ? Math.abs(angleDiff(yaw, yawTo(dx, dz))) : 0;
    if (angle > maxAngle) continue;
    const score = angle + 0.15 * dist;
    if (score < bestScore) {
      bestScore = score;
      best = a;
    }
  }
  return best;
}

// Smaller root of rr·t² + 2·b·t + c = 0 (entry into a sphere/cylinder), or NaN.
function entryRoot(rr, b, c) {
  const h = b * b - rr * c;
  if (h < 0 || rr <= 1e-14) return NaN;
  return (-b - Math.sqrt(h)) / rr;
}

function sphereEntry(p0x, p0y, p0z, dx, dy, dz, rr, cx, cy, cz, r) {
  const ox = p0x - cx;
  const oy = p0y - cy;
  const oz = p0z - cz;
  return entryRoot(rr, dx * ox + dy * oy + dz * oz, ox * ox + oy * oy + oz * oz - r * r);
}

/**
 * First parameter t ∈ [0, 1] where the segment p0→p1 enters the capsule with axis a→b and
 * radius r, 0 if p0 starts inside, or -1 if it never touches it.
 */
export function segmentCapsule(p0, p1, a, b, r) {
  const bax = b.x - a.x;
  const bay = b.y - a.y;
  const baz = b.z - a.z;
  const oax = p0.x - a.x;
  const oay = p0.y - a.y;
  const oaz = p0.z - a.z;
  const baba = bax * bax + bay * bay + baz * baz;
  const baoa = bax * oax + bay * oay + baz * oaz;
  const r2 = r * r;

  // Inside already?
  const h0 = baba > 1e-14 ? clamp(baoa / baba, 0, 1) : 0;
  const qx = oax - bax * h0;
  const qy = oay - bay * h0;
  const qz = oaz - baz * h0;
  if (qx * qx + qy * qy + qz * qz <= r2) return 0;

  const dx = p1.x - p0.x;
  const dy = p1.y - p0.y;
  const dz = p1.z - p0.z;
  const rr = dx * dx + dy * dy + dz * dz;
  if (rr <= 1e-14) return -1;

  let best = Infinity;
  // Cylinder body (flat ends are covered by the end spheres).
  if (baba > 1e-14) {
    const bard = bax * dx + bay * dy + baz * dz;
    const rdoa = dx * oax + dy * oay + dz * oaz;
    const oaoa = oax * oax + oay * oay + oaz * oaz;
    const qa = baba * rr - bard * bard;
    if (qa > 1e-12 * baba * rr) {
      const t = entryRoot(qa, baba * rdoa - baoa * bard, baba * oaoa - baoa * baoa - r2 * baba);
      if (t >= 0) {
        const y = baoa + t * bard;
        if (y >= 0 && y <= baba) best = t;
      }
    }
  }
  const ta = sphereEntry(p0.x, p0.y, p0.z, dx, dy, dz, rr, a.x, a.y, a.z, r);
  if (ta >= 0 && ta < best) best = ta;
  const tb = sphereEntry(p0.x, p0.y, p0.z, dx, dy, dz, rr, b.x, b.y, b.z, r);
  if (tb >= 0 && tb < best) best = tb;
  return best <= 1 ? best : -1;
}

/**
 * Launch pitch (low arc) that carries a projectile at `speed` over a horizontal distance `dxz`
 * and a height change `dy` under gravity `g`, or null when out of range.
 */
export function ballisticPitch(speed, dxz, dy, g = 9.81) {
  if (!(speed > 0)) return null;
  const x = Math.abs(dxz);
  const v2 = speed * speed;
  const disc = v2 * v2 - g * (g * x * x + 2 * dy * v2);
  if (disc < 0) return null;
  return Math.atan2(v2 - Math.sqrt(disc), g * x);
}

/** base·(0.65 + 0.7·power)·comboMul·(crit ? 1.6 : 1), with power clamped to [0, 1]. */
export function computeDamage({ base, power = 0, comboMul = 1, crit = false }) {
  const p = Number.isFinite(power) ? clamp(power, 0, 1) : 0;
  return base * (0.65 + 0.7 * p) * comboMul * (crit ? 1.6 : 1);
}
