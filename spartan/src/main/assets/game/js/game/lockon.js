/**
 * The hilt grip's lock-on (docs-hilt-grip.md §C). PURE: no DOM, no three.js; positions are
 * duck-typed { x, z } and every `yaw` uses the camera convention (0 faces -Z, positive turns left).
 *
 * The hilt player can't turn his body to look around, so the view faces one foe, the lock:
 * candidate scoring and acquisition around the current view, directional switching for the thumb
 * swipe, and a rate- and acceleration-limited, critically damped yaw spring that turns the view.
 * player.js owns the lock state and runs it only while ctx.input.grip === 'hilt'.
 * Nothing here allocates per call.
 */
import { angleDiff, clamp, wrapAngle, yawTo } from '../core/math.js';

export const LOCK = Object.freeze({
  acquireRange: 14,     // m: auto-acquire
  switchRange: 30,      // m: a swipe may pick this far with the sword
  spearRange: 45,       // m: … and this far with the spear in hand (= THROW_RANGE)
  keepRange: 22,        // m: an auto-acquired lock beyond this for loseTime is dropped
  keepSlack: 3,         // m: a swiped or threat lock is kept to min(spearRange, max(keepRange, its distance at lock + keepSlack))
  loseTime: 1.0,        // s (pdt)
  acquireCone: 1.15,    // rad: auto-acquire only within this of the view yaw
  distWeight: 0.12,     // score per metre (acquire), and rank per metre beyond swipeNear (swipe)
  swipeNear: 3,         // m: swipe ranking ignores distance up to this
  sideGap: 0.4,         // m: a swipe skips a foe within this lateral offset of the current one (behind or in front of him)
  windupBonus: 0.6,     // rad of score: winding up at the player
  engagedBonus: 0.25,   // rad of score: engaged with the player (actor.targetIsPlayer)
  deathHold: 0.35,      // s: keep facing a foe that just died before acquiring the next
  recenterHold: 0.8,    // s: after a recenter, face the line before acquiring again
  threatAngle: 1.31,    // rad: a player-targeted wind-up from beyond this may take the lock (lockAuto)
  threatGap: 3.0,       // s: at most one threat switch per this
  threatReturn: 5.0,    // s: a threat switch never goes back to a foe that lost the lock to a threat this recently
  sideMin: 0.05,        // rad: a swipe needs a foe at least this far to that side
  deadZone: 0.035,      // rad: a resting camera starts moving only beyond this error
  settleErr: 0.004, settleVel: 0.02,   // it comes to rest inside these
  omega: 9,             // rad/s: spring natural frequency (critically damped)
  maxRate: 2.2,         // rad/s (≈ 2.1° per 60 Hz frame)
  maxAccel: 12,         // rad/s²
  fastRate: 3.0, fastAccel: 18,        // threat switches
  bearingTau: 0.12,     // s: low-pass of the target's bearing (animation sway)
});

const MAX_DT = 0.05;

/**
 * Can this actor hold the lock? Alive, a Persian (the Agoge's dummies and trainer included), not
 * the helot, not retreating. Archers are lockable, but only switching reaches them (acquire skips them).
 */
export function isLockable(actor) {
  return !!actor && actor.alive === true && actor.team === 'persian' && actor.role !== 'helot'
    && actor.state !== 'retreat' && actor.state !== 'dead' && !!actor.pos;
}

/**
 * Is the actor still in the live list? ai.clear() disposes actors without marking them dead, so a
 * stored lock is checked against ctx.actors every frame (an index scan, no allocation).
 */
export function inActors(actor, actors) {
  if (!actor || !actors) return false;
  for (let i = 0; i < actors.length; i++) if (actors[i] === actor) return true;
  return false;
}

/**
 * Acquisition score (lower is better): the angle off the view plus distWeight per metre, less the
 * bonuses for a foe winding up at the player and for one engaged with him.
 */
export function lockScore(actor, pos, viewYaw, P = LOCK) {
  const dx = actor.pos.x - pos.x;
  const dz = actor.pos.z - pos.z;
  const dist = Math.hypot(dx, dz);
  const rel = dist > 1e-9 ? Math.abs(angleDiff(viewYaw, yawTo(dx, dz))) : 0;
  let s = rel + P.distWeight * dist;
  if (actor.targetIsPlayer) {
    s -= P.engagedBonus;
    if (actor.state === 'windup') s -= P.windupBonus;
  }
  return s;
}

/**
 * The foe to lock without a swipe: lockable, not an archer, within acquireRange and within
 * acquireCone of the view, with the lowest lockScore. Only around the current view, so a kill on
 * the left never swings the view to a foe far on the right. null if there is none.
 */
export function acquire(actors, pos, viewYaw, P = LOCK) {
  if (!actors) return null;
  let best = null;
  let bestScore = Infinity;
  for (let i = 0; i < actors.length; i++) {
    const a = actors[i];
    if (!isLockable(a) || a.role === 'archer') continue;
    const dx = a.pos.x - pos.x;
    const dz = a.pos.z - pos.z;
    const dist = Math.hypot(dx, dz);
    if (dist > P.acquireRange) continue;
    const rel = dist > 1e-9 ? Math.abs(angleDiff(viewYaw, yawTo(dx, dz))) : 0;
    if (rel > P.acquireCone) continue;
    const s = lockScore(a, pos, viewYaw, P);
    if (s < bestScore) {
      bestScore = s;
      best = a;
    }
  }
  return best;
}

/**
 * The foe a thumb swipe picks: the next lockable actor (archers included) to the right (dir +1)
 * or left (dir −1) of `fromYaw`, within `range`, other than `current`. To count as beside the
 * current foe it must lie at least sideMin + atan2(sideGap, distance to the current foe) that way,
 * so one straight behind or in front of him is skipped. Rank = |rel| + distWeight·max(0, dist −
 * swipeNear): the nearer side foe beats a far one a few degrees off; ties go to the nearer. Up to
 * |rel| = π, so repeated swipes walk round through the rear. No wrap: null if nothing lies that way.
 */
export function nextInDirection(actors, pos, fromYaw, current, dir, range, P = LOCK) {
  if (!actors || !(dir === 1 || dir === -1)) return null;
  let min = P.sideMin;
  if (current && current.pos) {
    const cd = Math.hypot(current.pos.x - pos.x, current.pos.z - pos.z);
    min += Math.atan2(P.sideGap, cd);
  }
  let best = null;
  let bestRank = Infinity;
  let bestDist = Infinity;
  for (let i = 0; i < actors.length; i++) {
    const a = actors[i];
    if (a === current || !isLockable(a)) continue;
    const dx = a.pos.x - pos.x;
    const dz = a.pos.z - pos.z;
    const dist = Math.hypot(dx, dz);
    if (dist > range || dist < 1e-6) continue;
    const rel = angleDiff(fromYaw, yawTo(dx, dz));
    // right is a negative rel (positive yaw turns left)
    if (dir === 1 ? rel >= -min : rel <= min) continue;
    const rank = Math.abs(rel) + P.distWeight * Math.max(0, dist - P.swipeNear);
    if (rank < bestRank - 1e-12 || (rank <= bestRank + 1e-12 && dist < bestDist)) {
      bestRank = rank;
      bestDist = dist;
      best = a;
    }
  }
  return best;
}

/**
 * The view's yaw spring: critically damped (ω = omega), with its rate and acceleration clamped, so
 * the view never jumps (a step is ≤ rate·dt), its velocity is continuous (it changes by ≤ accel·dt
 * per step), a transition never reverses, and a resting camera ignores errors inside deadZone
 * (no creep or chatter for a target swaying inside ±2°). dt is clamped to 0.05 s.
 */
export class YawSpring {
  constructor(P = LOCK) {
    this.P = P;
    this._vel = 0;
    this._moving = false;
    /** The last yaw it was reset to or stepped to. */
    this.yaw = 0;
  }

  /** At rest at `yaw`, velocity 0 (also how the spear cam freezes it). */
  reset(yaw = 0) {
    this._vel = 0;
    this._moving = false;
    this.yaw = Number.isFinite(yaw) ? yaw : 0;
  }

  /**
   * One step toward `desired`; returns the new yaw. fast: the threat-switch limits
   * (fastRate/fastAccel) instead of maxRate/maxAccel.
   */
  step(yaw, desired, dt, fast = false) {
    const P = this.P;
    if (!(dt > 0)) return yaw;
    if (dt > MAX_DT) dt = MAX_DT;
    const e = angleDiff(yaw, desired);
    if (!this._moving) {
      if (Math.abs(e) <= P.deadZone) {
        this.yaw = yaw;
        return yaw;
      }
      this._moving = true;
    }
    const rate = fast ? P.fastRate : P.maxRate;
    const accel = fast ? P.fastAccel : P.maxAccel;
    const w = P.omega;
    const a = clamp(w * w * e - 2 * w * this._vel, -accel, accel);
    this._vel = clamp(this._vel + a * dt, -rate, rate);
    yaw = wrapAngle(yaw + this._vel * dt);
    if (Math.abs(angleDiff(yaw, desired)) < P.settleErr && Math.abs(this._vel) < P.settleVel) {
      this._moving = false;
      this._vel = 0;
    }
    this.yaw = yaw;
    return yaw;
  }

  get vel() {
    return this._vel;
  }

  get moving() {
    return this._moving;
  }
}

/** Tap to lock (the buttons control): padding and sizes in CSS px, the range in m. */
export const TAP = Object.freeze({
  pad: 24,        // px added above the head and below the feet
  minHalfW: 34,   // px: a far foe is still this wide either side of his centre
  widthRatio: 0.3, // half-width as a share of his drawn height
  range: 45,      // m: no farther than a spear flies (LOCK.spearRange)
});

const tapHead = { x: 0, y: 0, front: false };
const tapFeet = { x: 0, y: 0, front: false };

// World point → CSS px through a duck-typed camera (three.js's column-major matrixWorldInverse and
// projectionMatrix elements). out.front is false behind (or nearly at) the eye.
function projectTo(cam, x, y, z, vw, vh, out) {
  const m = cam.matrixWorldInverse.elements;
  const vx = m[0] * x + m[4] * y + m[8] * z + m[12];
  const vy = m[1] * x + m[5] * y + m[9] * z + m[13];
  const vz = m[2] * x + m[6] * y + m[10] * z + m[14];
  out.front = vz < -0.15;
  if (!out.front) return out;
  const p = cam.projectionMatrix.elements;
  const cx = p[0] * vx + p[4] * vy + p[8] * vz + p[12];
  const cy = p[1] * vx + p[5] * vy + p[9] * vz + p[13];
  const cw = p[3] * vx + p[7] * vy + p[11] * vz + p[15];
  out.x = (cx / cw + 1) * 0.5 * vw;
  out.y = (1 - cy / cw) * 0.5 * vh;
  return out;
}

/**
 * The lockable foe drawn under a tap at (x, y) CSS px on a vw×vh view, or null: within his
 * projected head-to-feet span padded by TAP.pad, and widthRatio of his drawn height either side of
 * his centre (at least minHalfW); the nearest centre wins. Archers count (a tap can reach them).
 * `from` is the player's position: foes beyond TAP.range are skipped. No allocation.
 */
export function pickOnScreen(actors, camera, x, y, vw, vh, from, P = TAP) {
  if (!actors || !camera || !camera.matrixWorldInverse || !camera.projectionMatrix) return null;
  let best = null;
  let bestD = Infinity;
  for (let i = 0; i < actors.length; i++) {
    const a = actors[i];
    if (!isLockable(a)) continue;
    if (from && Math.hypot(a.pos.x - from.x, a.pos.z - from.z) > P.range) continue;
    const h = a.height || 1.75;
    const y0 = a.pos.y || 0;
    projectTo(camera, a.pos.x, y0 + h, a.pos.z, vw, vh, tapHead);
    projectTo(camera, a.pos.x, y0, a.pos.z, vw, vh, tapFeet);
    if (!tapHead.front || !tapFeet.front) continue;
    if (y < tapHead.y - P.pad || y > tapFeet.y + P.pad) continue;
    const cx = (tapHead.x + tapFeet.x) / 2;
    const half = Math.max(P.minHalfW, (tapFeet.y - tapHead.y) * P.widthRatio);
    if (Math.abs(x - cx) > half) continue;
    const d = Math.hypot(x - cx, y - (tapHead.y + tapFeet.y) / 2);
    if (d < bestD) {
      bestD = d;
      best = a;
    }
  }
  return best;
}
