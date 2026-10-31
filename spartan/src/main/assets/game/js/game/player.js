/**
 * PlayerController (DESIGN.md §7, §11 game/player.js). Drains actions, runs the attack timeline on
 * pdt, raises the shield, aims (motion: rotated attacks; touch: a short camera pull), lunges,
 * throws spears, keeps score, and places the first-person camera.
 *
 * Motion mode: in the window grip the camera only ever turns with the player's own body (gyro
 * look) or a recenter: no auto-face toward attackers and no pitch easing toward targets (a steady,
 * slightly lowered pitch). Aim help rotates the attack instead (up to MOTION_YAW_CLAMP). A detected
 * swing starts its attack at once: it may cut the current one short as soon as that one's strike
 * has resolved (resolving it first if need be), and a motion slash strikes after
 * MOTION_STRIKE_SCALE of the usual delay, since the physical swing is already under way.
 *
 * The hilt grip (docs-hilt-grip.md §C, ctx.input.grip === 'hilt'): the player can't turn his body
 * to look, so a lock-on camera faces one foe (lockTarget) through lockon.js's rate-limited,
 * critically damped yaw spring, the only yaw writer there besides the guard-break nudge. Gyro look,
 * the touch aim pull and the recenter ease never turn the view in that grip. A thumb swipe
 * (switchTarget) moves the lock, an attacker the shield can't cover may take it (lockAuto), and
 * strikes and throws aim at it. The buttons control (ctx.input.lockOn) runs the same lock-on, its
 * target buttons sending switchTarget. Touch and the window grip keep lockMode 'off'.
 */
import * as THREE from 'three';
import { TIMING, WEAPONS, pickTarget, ballisticPitch, dirToken } from './rules.js';
import { ComboTracker, COMBO_WINDOWS } from './combos.js';
import { clamp, lerp, smoothstep, angleDiff, wrapAngle, yawTo, TAU } from '../core/math.js';
import { LOCK, YawSpring, isLockable, inActors, acquire, nextInDirection } from './lockon.js';

const EYE = 1.66;
const BUFFER_LIFE = 0.4;
const FOCUS_ANGLE = 0.70;
const FOCUS_EXTRA = 1.5;
const MOTION_YAW_CLAMP = 0.52; // ±30°: the attack turns toward a foe the player is not quite facing
const TOUCH_PULL = 0.21;
const TOUCH_PULL_TIME = 0.1;
const LUNGE_MAX = 0.8;
const LUNGE_EXTRA = 1.0;
const LUNGE_MIN_GAP = 0.95; // never step closer than this (centre to centre)
const DRIFT_DELAY = 1.5;
const DRIFT_SPEED = 1.0;
const BLOCK_HALF_ANGLE = 1.31;
const GUARD_BREAK_LOCK = 0.8;
const GUARD_BREAK_YAW = 0.1;
const GUARD_BREAK_STAGGER = 0.35;
const THROW_ANGLE = 0.262;
const THROW_RANGE = 45;
const MOTION_PITCH_RATE = 4;
const MOTION_PITCH = -0.07; // steady, slightly lowered: chest height of a foe in reach
const MOTION_STRIKE_SCALE = 0.5;
const LOOK_PITCH_LIMIT = 1.15;
const PLAYER_RADIUS = 0.32;
const SWITCH_TIME = 0.3;
const MAX_SPARES = 3;
const START_SPARES = 2;
const CHAIN_MAX = 3.0;
const CHAIN_STEP = 0.1;
const CHAIN_IDLE = 4.0; // s of game time without a player hit before the chain drops back to 1
const LOCK_SIDES_EVERY = 0.1; // s (pdt): lockSides is recomputed at most this often
const THREAT_MEMORY = 4; // foes remembered as having lost the lock to a threat switch (≥ threatReturn / threatGap)

const SPEED_FWD = 2.3;
const SPEED_BACK = 1.5;
const SPEED_STRAFE = 1.9;

const ATTACK_TIMING = { slash: TIMING.slash, thrust: TIMING.thrust, spearThrust: TIMING.spearThrust, bash: TIMING.bash };
const DIPPING_KINDS = { slash: true, thrust: true, spearThrust: true };
const ATTACK_ACTIONS = { slash: true, thrust: true, bash: true, throw: true };

const isHostile = (a) => a.team === 'persian';

/** @param {object} ctx shared context (§4) */
export function createPlayer(ctx) {
  const ev = ctx.events;
  const v1 = new THREE.Vector3();

  // Internal timeline (sum of pdt) and state.
  let mode = 'battle';
  let ptime = 0;
  let stateT = 0;
  let redrawAt = Infinity;
  let lastBashStart = -Infinity;
  let lastAttackAt = -Infinity;
  let dipUntil = -Infinity;
  let shieldLockUntil = -Infinity;
  let critUntil = -Infinity;
  let shieldUpFlag = false;
  let deathT = 0;
  let lastHitAt = -Infinity; // gnow of the player's last hit (chain decay)

  // Shield hold tracking for perfect blocks (gnow based, like input.shieldPressedAt).
  let prevHeld = false;
  let prevPressedAt = NaN;
  let holdStart = -Infinity;
  let holdQualifies = false;
  let downSince = -Infinity;

  // Movement and camera.
  let velX = 0;
  let velZ = 0;
  let speedSmooth = 0;
  let bobPhase = 0;
  let bobAmp = 0;
  let pushX = 0;
  let pushZ = 0;
  let pushLeft = 0;
  let yawImpulse = 0;
  let recenterLeft = 0;
  let kickAt = -Infinity;
  let kickPitch = 0;
  let kickYaw = 0;
  let kickRoll = 0;
  let idleT = 0;
  let idleFrame = false;

  // Lock-on (the hilt grip, §C). lockMode: 'off' (touch, window, dead) | 'target' | 'front' | 'hold'.
  const spring = new YawSpring(LOCK);
  const lockSides = { left: null, right: null }; // what a swipe would pick now (mutated in place)
  const threatLost = new Array(THREAT_MEMORY).fill(null); // foes that lost the lock to a threat switch …
  const threatLostAt = new Array(THREAT_MEMORY).fill(-Infinity); // … and when (ptime)
  let threatSlot = 0;
  let lockTarget = null;
  let lockMode = 'off';
  let lockDesired = 0; // the spring's goal
  let lockBearing = 0; // the target's low-passed bearing
  let lockKeep = LOCK.keepRange; // the current lock is kept within this
  let lockFar = 0; // s (pdt) the current lock has been beyond lockKeep
  let lockHold = 0; // s left facing the last bearing ('hold', after a death) or the front (after a recenter)
  let lockFast = false; // a threat switch: the spring's fast limits until it comes to rest
  let lastThreatAt = -Infinity;
  let sidesLeft = 0;

  // The current attack (reused) and the one-slot buffer.
  const atk = {
    active: false, kind: 'slash', weapon: 'sword', dir: 0, power: 0, src: 'test', motion: false,
    t: 0, strike: 0, total: 0, cancelAfter: 0, cancellable: true, struck: false, released: false, releaseAt: 0,
    combo: null, target: null, reach: 0,
    lungeX: 0, lungeZ: 0, lungeLen: 0, lungeLeft: 0, lungeTime: 0,
    pull: 0, pullLeft: 0, pullTime: 0,
  };
  const buffer = { has: false, action: null, age: 0 };
  const strikeAttack = { kind: 'slash', dir: 0, power: 0, yaw: 0, origin: null, combo: null, crit: false, weapon: 'sword', focus: null, src: 'test' };
  const strikeOrigin = new THREE.Vector3(); // where the strike lands from: the lunge's end point
  const throwTrack = { target: null, x: 0, z: 0, t: 0 };

  const throwOrigin = new THREE.Vector3();
  const throwVel = new THREE.Vector3();

  const player = {
    pos: new THREE.Vector3(),
    yaw: 0,
    pitch: 0,
    hp: 100,
    maxHp: 100,
    weapon: 'sword',
    spares: START_SPARES,
    hasSpearInHand: false,
    get spearsLeft() {
      return (player.hasSpearInHand ? 1 : 0) + player.spares;
    },
    shieldRaise: 0,
    /** 'idle'|'attack'|'throw'|'postThrow'|'draw'|'stagger'|'dead' */
    state: 'idle',
    alive: true,
    kills: 0,
    score: 0,
    chain: 1,
    combos: new ComboTracker(),
    focusTarget: null,
    attackYaw: 0,
    /** The hilt grip's lock (§C.2), read-only: the foe the view faces and the strikes aim at. */
    get lockTarget() {
      return lockTarget;
    },
    /** 'off' (touch, the window grip, dead) | 'target' | 'front' (easing to yaw 0) | 'hold' (a dead lock's last bearing) */
    get lockMode() {
      return lockMode;
    },
    /** { left, right }: the foe a swipe that way would pick now (HUD arrows). Recomputed at most every 0.1 s. */
    lockSides,
    /** The view's YawSpring (read-only; tests and the e2e read `moving`). */
    get lockSpring() {
      return spring;
    },

    reset,
    update,
    applyCamera,
    idleCamera,
    tryBlock,
    damage,
    guardBreak,
    push,
    heal,
    refillSpears,
    dropSpears,
    recenter,
    switchTarget,
    setLock,
  };
  strikeAttack.origin = player.pos;

  // ---- helpers ---------------------------------------------------------------------------

  const groundY = (x, z) => (ctx.world && ctx.world.groundHeight ? ctx.world.groundHeight(x, z) : 0);
  const gnow = () => ctx.time.gnow;
  const motionMode = () => !!(ctx.input && ctx.input.motion);
  const cameraOverridden = () => !!(ctx.fx && ctx.fx.cameraOverridden);
  const shieldLocked = () => ptime < shieldLockUntil;
  // The lock-on runs in the hilt grip and the buttons control (ctx.input.lockOn; contexts that
  // only set the grip, like the unit tests, get it from the hilt grip).
  const lockControl = () => !!(ctx.input && (ctx.input.lockOn === true || ctx.input.grip === 'hilt'));
  const lockOn = () => lockMode !== 'off';

  function emitScore() {
    ev.emit('scoreChanged', { score: player.score, chain: player.chain, kills: player.kills });
  }

  function addScore(n) {
    if (!n) return;
    player.score += n;
    emitScore();
  }

  function resetChain() {
    if (player.chain === 1) return;
    player.chain = 1;
    emitScore();
  }

  // The chain lapses after CHAIN_IDLE s of game time without a player hit.
  function decayChain() {
    if (player.chain !== 1 && gnow() - lastHitAt > CHAIN_IDLE + 1e-9) resetChain();
  }

  function weaponReach() {
    return player.weapon === 'spear' && player.hasSpearInHand ? WEAPONS.spear.reach : WEAPONS.sword.reach;
  }

  // Signed yaw angle from the view to an actor (positive = on the left), XZ.
  function angleTo(actor, fromYaw = player.yaw) {
    return angleDiff(fromYaw, yawTo(actor.pos.x - player.pos.x, actor.pos.z - player.pos.z));
  }

  function chestPointOf(actor, out) {
    if (actor.chestPoint) return actor.chestPoint(out);
    return out.set(actor.pos.x, actor.pos.y + (actor.height || 1.75) * 0.74, actor.pos.z);
  }

  function syncViewmodel() {
    const vm = ctx.viewmodel;
    if (!vm) return;
    vm.setWeapon(player.weapon);
    vm.setHasSpear(player.hasSpearInHand);
    vm.setShield(player.shieldRaise);
  }

  // ---- shield hold tracking --------------------------------------------------------------

  // Detects the start of a new hold and decides whether it can perfect-block: the shield must
  // have been down (raise < 0.2) for at least perfectNeedsDown before the press.
  function trackShieldHold() {
    const inp = ctx.input;
    const held = !!(inp && inp.shieldHeld);
    const pressedAt = inp ? inp.shieldPressedAt : NaN;
    if (held && (!prevHeld || pressedAt !== prevPressedAt)) {
      const at = Number.isFinite(pressedAt) ? pressedAt : gnow();
      holdStart = at;
      holdQualifies = player.shieldRaise < 0.2 && at - downSince >= TIMING.perfectNeedsDown - 1e-6;
    }
    prevHeld = held;
    prevPressedAt = pressedAt;
  }

  // ---- focus ------------------------------------------------------------------------------

  // The lock is the focus while it is alive, in reach (+ FOCUS_EXTRA) and in front; otherwise a
  // closer foe in front still gets hit while the lock is on a far one (§C.5).
  function updateFocus() {
    const t = lockTarget;
    if (t && t.alive && distTo(t) <= weaponReach() + FOCUS_EXTRA && Math.abs(angleTo(t)) <= FOCUS_ANGLE) {
      player.focusTarget = t;
      return;
    }
    player.focusTarget = pickTarget(ctx.actors, player.pos, player.yaw, FOCUS_ANGLE, weaponReach() + FOCUS_EXTRA, isHostile);
  }

  // ---- lock-on (the hilt grip, docs-hilt-grip.md §C) -------------------------------------

  function distTo(actor) {
    return Math.hypot(actor.pos.x - player.pos.x, actor.pos.z - player.pos.z);
  }

  function bearingOf(actor) {
    return yawTo(actor.pos.x - player.pos.x, actor.pos.z - player.pos.z);
  }

  function swipeRange() {
    return player.weapon === 'spear' && player.hasSpearInHand ? LOCK.spearRange : LOCK.switchRange;
  }

  // The grip became the hilt: the spring starts at rest where the view is (no jump), facing the
  // front until stepLock acquires. The recenter ease never runs in this grip.
  function enterLock() {
    spring.reset(player.yaw);
    recenterLeft = 0;
    lockMode = 'front';
    lockDesired = 0;
    lockHold = 0;
    lockFar = 0;
    lockFast = false;
    sidesLeft = 0;
  }

  // Locks `t` (every new lockTarget comes through here). picked: a swipe, a threat or setLock,
  // which keep the lock out to max(keepRange, its distance + keepSlack) (≤ spearRange); an
  // auto-acquired one is kept to keepRange.
  function lockTo(t, reason, picked) {
    const prev = lockTarget;
    lockTarget = t;
    lockMode = 'target';
    lockKeep = picked ? Math.min(LOCK.spearRange, Math.max(LOCK.keepRange, distTo(t) + LOCK.keepSlack)) : LOCK.keepRange;
    lockFar = 0;
    lockHold = 0;
    lockBearing = bearingOf(t);
    lockDesired = lockBearing;
    sidesLeft = 0;
    if (t !== prev) ev.emit('lockOn', { target: t, prev, reason });
  }

  // Drops the lock: mode 'hold' (keep facing the last bearing), 'front' or 'off'; `hold` s before
  // the next acquisition. A transition under way keeps its spring limits until it rests.
  function dropLock(reason, mode, hold) {
    const prev = lockTarget;
    lockTarget = null;
    lockMode = mode;
    lockHold = hold;
    lockFar = 0;
    sidesLeft = 0;
    if (mode === 'off') {
      lockSides.left = null;
      lockSides.right = null;
      lockFast = false;
    }
    if (prev) ev.emit('lockOn', { target: null, prev, reason });
  }

  // Starts and stops with the grip, checks the lock (death, no longer lockable or listed, beyond
  // its keep range for loseTime) and acquires when there is none and no hold is running. Runs
  // before the focus, so strikes this frame aim at the lock it leaves.
  function stepLock(dt) {
    if (!lockControl()) {
      if (lockOn()) dropLock('clear', 'off', 0);
      return;
    }
    if (!lockOn()) enterLock();
    const t = lockTarget;
    if (t) {
      if (!t.alive || t.state === 'dead') dropLock('death', 'hold', LOCK.deathHold);
      else if (!isLockable(t) || !inActors(t, ctx.actors)) dropLock('lost', 'front', 0);
      else if (distTo(t) > lockKeep) {
        lockFar += dt;
        if (lockFar >= LOCK.loseTime - 1e-9) dropLock('lost', 'front', 0);
      } else lockFar = 0;
    }
    if (lockTarget) return;
    if (lockHold > 0) lockHold -= dt;
    if (lockHold > 1e-9) return;
    lockHold = 0;
    const a = acquire(ctx.actors, player.pos, player.yaw, LOCK);
    if (a) lockTo(a, 'acquire', false);
    else lockMode = 'front';
  }

  // What a swipe would pick now, for the HUD's side arrows (at most every LOCK_SIDES_EVERY s).
  function updateLockSides(dt) {
    if (!lockOn()) return;
    sidesLeft -= dt;
    if (sidesLeft > 0) return;
    sidesLeft = LOCK_SIDES_EVERY;
    const cur = lockTarget;
    const from = cur ? bearingOf(cur) : player.yaw;
    const range = swipeRange();
    lockSides.left = nextInDirection(ctx.actors, player.pos, from, cur, -1, range, LOCK);
    lockSides.right = nextInDirection(ctx.actors, player.pos, from, cur, 1, range, LOCK);
  }

  // The view's yaw in the hilt grip: the spring toward the lock's low-passed bearing (feet, not
  // the animated chest), the front, or the held bearing. Frozen (and yaw not written) while the
  // spear cam owns the camera, so the eye pose it eases back to holds still.
  function stepLockYaw(dt, overridden) {
    const t = lockTarget;
    if (t) {
      const k = dt > 0 ? 1 - Math.exp(-dt / LOCK.bearingTau) : 0;
      lockBearing = wrapAngle(lockBearing + angleDiff(lockBearing, bearingOf(t)) * k);
      lockDesired = lockBearing;
    } else if (lockMode === 'front') lockDesired = 0;
    if (overridden) {
      spring.reset(player.yaw);
      return;
    }
    player.yaw = spring.step(player.yaw, lockDesired, dt, lockFast);
    if (lockFast && !spring.moving) lockFast = false;
  }

  /**
   * Moves the lock to the next foe to the left (dir −1) or right (+1) of the current one (§C.3):
   * with the spear in hand out to spearRange, else switchRange. Emits lockOn 'switch', or 'none'
   * (target unchanged, the view doesn't move) when nothing lies that way. Only in the hilt grip,
   * alive and with the camera not overridden.
   */
  function switchTarget(dir) {
    if (!lockControl() || !player.alive || cameraOverridden()) return false;
    const d = dir > 0 ? 1 : dir < 0 ? -1 : 0;
    if (d === 0) return false;
    if (!lockOn()) enterLock();
    const cur = lockTarget;
    const next = nextInDirection(ctx.actors, player.pos, cur ? bearingOf(cur) : player.yaw, cur, d, swipeRange(), LOCK);
    if (!next) {
      ev.emit('lockOn', { target: cur, prev: cur, reason: 'none' });
      return false;
    }
    lockTo(next, 'switch', true);
    return true;
  }

  /**
   * The Agoge and tests: forces the lock onto `actor` (lockOn 'acquire', kept like a swiped lock),
   * or with null (or an actor that can't be locked) drops it to 'front' (lockOn 'clear').
   * Only in the hilt grip and alive. Returns whether `actor` is now the lock.
   */
  function setLock(actor) {
    if (!lockControl() || !player.alive) return false;
    if (!lockOn()) enterLock();
    if (actor && isLockable(actor)) lockTo(actor, 'acquire', true);
    else dropLock('clear', 'front', 0);
    return !!actor && lockTarget === actor;
  }

  // ---- attacks ---------------------------------------------------------------------------

  function canStartNow() {
    return player.state === 'idle';
  }

  // A motion slash or thrust (the phone already swung) may cut the current attack short as soon as
  // its strike has resolved; anything else waits for cancelAfter.
  const isMotionStrike = (a) => !!a && a.src === 'motion' && (a.type === 'slash' || a.type === 'thrust');

  function canCancelInto(a) {
    if (!atk.active || !atk.cancellable) return false;
    if (isMotionStrike(a) && atk.struck) return true;
    return atk.t >= atk.cancelAfter - 1e-9;
  }

  // The attack an action would start now (null if it would be dropped).
  function attackKind(a) {
    const inp = ctx.input || {};
    let kind;
    if (a.type === 'slash') kind = 'slash';
    else if (a.type === 'bash') kind = 'bash';
    else if (a.type === 'thrust') {
      if (inp.shieldHeld && !shieldLocked()) kind = 'bash';
      else kind = player.weapon === 'spear' && player.hasSpearInHand ? 'spearThrust' : 'thrust';
    } else if (a.type === 'throw') {
      if (player.weapon !== 'spear' || !player.hasSpearInHand) return null;
      kind = 'throw';
    } else return null;
    if (kind === 'bash' && ptime - lastBashStart < TIMING.bash.cooldown - 1e-9) return null;
    return kind;
  }

  // A motion swing mid-attack starts now: the current attack's strike lands first if still pending.
  function interruptForMotion(a) {
    if (!isMotionStrike(a) || player.state !== 'attack' || !atk.active || !atk.cancellable || !attackKind(a)) return false;
    if (!atk.struck) {
      // land the rest of its lunge first, or a foe just out of reach is missed (and the combo lost)
      if (atk.lungeLeft > 0) {
        player.pos.x += atk.lungeX * atk.lungeLeft;
        player.pos.z += atk.lungeZ * atk.lungeLeft;
        atk.lungeLeft = 0;
      }
      clampToBounds();
      resolveStrike();
    }
    endAttack();
    return startAttack(a);
  }

  // Starts an attack from an action. Returns false if the action is dropped.
  function startAttack(a) {
    const inp = ctx.input || {};
    const kind = attackKind(a);
    if (!kind) return false;

    const power = Number.isFinite(a.power) ? clamp(a.power, 0, 1) : 0.6;
    const dir = kind === 'slash' && Number.isFinite(a.dir) ? a.dir : 0;
    const src = a.src || 'test';
    let weapon;
    if (kind === 'bash') weapon = 'shield';
    else if (kind === 'spearThrust' || kind === 'throw') weapon = 'spear';
    else if (kind === 'slash') weapon = player.weapon === 'spear' && player.hasSpearInHand ? 'spear' : 'sword';
    else weapon = 'sword';

    atk.active = true;
    atk.kind = kind;
    atk.weapon = weapon;
    atk.dir = dir;
    atk.power = power;
    atk.src = src;
    atk.motion = motionMode();
    atk.t = 0;
    atk.struck = false;
    atk.released = false;
    atk.target = player.focusTarget;
    atk.lungeLeft = 0;
    atk.pullLeft = 0;
    atk.pull = 0;

    if (kind === 'throw') {
      atk.total = TIMING.throw.total;
      atk.strike = Infinity;
      atk.cancelAfter = Infinity;
      atk.cancellable = false;
      atk.releaseAt = src === 'motion' ? 0.03 : TIMING.throw.windup;
      atk.reach = 0;
      player.state = 'throw';
      const t = findThrowTarget();
      throwTrack.target = t;
      if (t) {
        throwTrack.x = t.pos.x;
        throwTrack.z = t.pos.z;
        throwTrack.t = gnow();
      }
    } else {
      const tm = ATTACK_TIMING[kind];
      atk.total = tm.total;
      // the physical swing is already under way: a motion slash strikes sooner
      atk.strike = kind === 'slash' && src === 'motion' ? tm.strike * MOTION_STRIKE_SCALE : tm.strike;
      atk.cancelAfter = tm.cancelAfter;
      atk.cancellable = true;
      atk.reach = kind === 'bash' ? WEAPONS.shield.reach : kind === 'spearThrust' ? WEAPONS.spear.reach : WEAPONS[weapon].reach;
      player.state = 'attack';
      planLunge();
      planPull();
    }

    // Striking from behind the shield dips it for the whole attack; a bash uses the shield.
    if (DIPPING_KINDS[kind] && inp.shieldHeld && !shieldLocked()) dipUntil = ptime + atk.total;
    else if (kind === 'bash') dipUntil = -Infinity;
    if (kind === 'bash') lastBashStart = ptime;
    lastAttackAt = ptime;

    const token = kind === 'slash' ? dirToken(dir) : kind === 'bash' ? 'B' : kind === 'throw' ? 'J' : 'T';
    // A motion strike returns to guard before the next one (~1.5 s apart), and the Cleave's chop
    // comes ~2.5 s after its uppercut: wider windows (COMBO_WINDOWS.motion).
    player.combos.setWindows(atk.motion ? COMBO_WINDOWS.motion : COMBO_WINDOWS.touch);
    atk.combo = player.combos.push(token, gnow());
    player.attackYaw = player.yaw;
    if (atk.motion && atk.target) player.attackYaw = wrapAngle(player.yaw + clamp(angleTo(atk.target), -MOTION_YAW_CLAMP, MOTION_YAW_CLAMP));
    // strikeIn: seconds until the strike resolves (a throw: until the spear leaves the hand)
    ev.emit('swing', { kind, dir, power, weapon, src, strikeIn: kind === 'throw' ? atk.releaseAt : atk.strike });
    return true;
  }

  // Lunge toward a focus target that is just out of reach (centre distance in (reach, reach + 1]).
  function planLunge() {
    const t = atk.target;
    if (!t) return;
    const dx = t.pos.x - player.pos.x;
    const dz = t.pos.z - player.pos.z;
    const d = Math.hypot(dx, dz);
    if (d <= atk.reach || d > atk.reach + LUNGE_EXTRA) return;
    const len = Math.min(LUNGE_MAX, d - atk.reach + 0.3, d - LUNGE_MIN_GAP);
    if (len <= 0.01) return;
    atk.lungeX = dx / d;
    atk.lungeZ = dz / d;
    atk.lungeLen = len;
    atk.lungeLeft = len;
    // over the unscaled strike time: a motion slash strikes sooner, but its step is no faster
    const tm = ATTACK_TIMING[atk.kind];
    atk.lungeTime = Math.max(tm ? tm.strike : atk.strike, 0.05);
  }

  // Touch/keyboard aim assist: pull the view up to 0.21 rad toward the focus over the first 0.1 s.
  // Never with the lock-on, whose spring is the hilt grip's only yaw writer.
  function planPull() {
    if (atk.motion || lockOn() || !atk.target || (ctx.settings && ctx.settings.aimAssist === false)) return;
    atk.pull = clamp(angleTo(atk.target), -TOUCH_PULL, TOUCH_PULL);
    atk.pullLeft = atk.pull;
    atk.pullTime = Math.min(TOUCH_PULL_TIME, atk.strike);
  }

  function computeAttackYaw() {
    if (!atk.motion) return player.yaw;
    const t = atk.target && atk.target.alive ? atk.target : player.focusTarget;
    if (!t) return player.yaw;
    return wrapAngle(player.yaw + clamp(angleTo(t), -MOTION_YAW_CLAMP, MOTION_YAW_CLAMP));
  }

  function resolveStrike() {
    atk.struck = true;
    player.attackYaw = computeAttackYaw();
    const crit = ptime < critUntil;
    const s = strikeAttack;
    s.kind = atk.kind;
    s.dir = atk.dir;
    s.power = atk.power;
    s.yaw = player.attackYaw;
    // A motion slash strikes before its lunge has finished (the step runs over the unscaled strike
    // time, so the camera doesn't jump): the blow reaches from where the lunge is carrying the player.
    strikeOrigin.copy(player.pos);
    if (atk.lungeLeft > 0) {
      strikeOrigin.x += atk.lungeX * atk.lungeLeft;
      strikeOrigin.z += atk.lungeZ * atk.lungeLeft;
    }
    s.origin = strikeOrigin;
    s.combo = atk.combo;
    s.crit = crit;
    s.weapon = atk.weapon;
    s.focus = atk.target && atk.target.alive ? atk.target : player.focusTarget;
    s.src = atk.src;
    const hits = ctx.combat ? ctx.combat.playerStrike(s) : null;
    const n = hits ? hits.length : 0;
    if (n > 0) {
      if (crit) critUntil = -Infinity;
      if (atk.combo) {
        ev.emit('combo', Object.assign({}, atk.combo, { bonus: atk.combo.bonus }));
        addScore(atk.combo.bonus);
      }
    } else {
      player.combos.reset();
    }
  }

  // The living hostile with the smallest view angle within 0.262 rad and 45 m. Actors flagged
  // noThrowLock (the Agoge's near dummy while the far target is the aim) are never locked.
  // The hilt grip throws at the lock instead while it is lockable, listed, not noThrowLock and
  // within 45 m (§C.5).
  function findThrowTarget() {
    const lt = lockTarget;
    if (lt && isLockable(lt) && !lt.noThrowLock && inActors(lt, ctx.actors)) {
      const d = distTo(lt);
      if (d <= THROW_RANGE && d >= 0.3) return lt;
    }
    const actors = ctx.actors || [];
    let best = null;
    let bestA = THROW_ANGLE;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a || !a.alive || a.team !== 'persian' || a.noThrowLock) continue;
      const d = Math.hypot(a.pos.x - player.pos.x, a.pos.z - player.pos.z);
      if (d > THROW_RANGE || d < 0.3) continue;
      const ang = Math.abs(angleTo(a));
      if (ang <= bestA) {
        bestA = ang;
        best = a;
      }
    }
    return best;
  }

  function releaseThrow() {
    atk.released = true;
    const yaw = player.yaw;
    const sy = Math.sin(yaw);
    const cy = Math.cos(yaw);
    const eyeY = groundY(player.pos.x, player.pos.z) + EYE;
    // origin = eye + right·0.22 + up·0.10 + forward·0.35
    throwOrigin.set(player.pos.x + cy * 0.22 - sy * 0.35, eyeY + 0.10, player.pos.z - sy * 0.22 - cy * 0.35);
    const ws = WEAPONS.spear.throwSpeed;
    const speed = lerp(ws[0], ws[1], atk.power);
    let yawL = yaw;
    let pitchL = player.pitch + 0.06;
    const target = findThrowTarget();
    if (target) {
      chestPointOf(target, v1);
      // Lead a moving target with its velocity measured since the throw started.
      let vx = 0;
      let vz = 0;
      const dtT = gnow() - throwTrack.t;
      if (throwTrack.target === target && dtT > 0.02) {
        vx = (target.pos.x - throwTrack.x) / dtT;
        vz = (target.pos.z - throwTrack.z) / dtT;
        const vl = Math.hypot(vx, vz);
        if (vl > 5) {
          vx *= 5 / vl;
          vz *= 5 / vl;
        }
      }
      let tf = 0;
      for (let k = 0; k < 3; k++) {
        const ax = v1.x + vx * tf - throwOrigin.x;
        const az = v1.z + vz * tf - throwOrigin.z;
        const dxz = Math.hypot(ax, az);
        const p = ballisticPitch(speed, dxz, v1.y - throwOrigin.y);
        pitchL = p === null ? Math.PI / 4 : p;
        yawL = yawTo(ax, az);
        tf = dxz / Math.max(1e-3, speed * Math.cos(pitchL));
        if (vx === 0 && vz === 0) break;
      }
    }
    const cp = Math.cos(pitchL);
    throwVel.set(-Math.sin(yawL) * cp * speed, Math.sin(pitchL) * speed, -Math.cos(yawL) * cp * speed);
    player.hasSpearInHand = false;
    redrawAt = ptime - atk.t + atk.releaseAt + TIMING.throw.redraw;
    throwTrack.target = null;
    if (ctx.combat) ctx.combat.throwSpear({ origin: throwOrigin.clone(), velocity: throwVel.clone(), power: atk.power, byPlayer: true, src: atk.src });
  }

  function endAttack() {
    atk.active = false;
    atk.target = null;
    if (player.state === 'attack' || player.state === 'throw') {
      player.state = atk.kind === 'throw' ? 'postThrow' : 'idle';
    }
  }

  function stepAttack(dt) {
    if (!atk.active) return;
    atk.t += dt;
    // Lunge over the strike time.
    if (atk.lungeLeft > 0) {
      const step = Math.min(atk.lungeLeft, (atk.lungeLen * dt) / atk.lungeTime);
      atk.lungeLeft -= step;
      player.pos.x += atk.lungeX * step;
      player.pos.z += atk.lungeZ * step;
    }
    // Touch aim pull.
    if (atk.pullLeft !== 0) {
      const rate = (Math.abs(atk.pull) * dt) / atk.pullTime;
      const step = Math.sign(atk.pullLeft) * Math.min(Math.abs(atk.pullLeft), rate);
      atk.pullLeft -= step;
      if (Math.abs(atk.pullLeft) < 1e-9) atk.pullLeft = 0;
      player.yaw = wrapAngle(player.yaw + step);
    }
    if (atk.kind === 'throw') {
      if (!atk.released && atk.t >= atk.releaseAt - 1e-9) releaseThrow();
    } else if (!atk.struck && atk.t >= atk.strike - 1e-9) {
      clampToBounds();
      resolveStrike();
    }
    if (atk.active && atk.t >= atk.total - 1e-9) endAttack();
  }

  function stepStates(dt) {
    if (player.state === 'draw' || player.state === 'stagger') {
      stateT -= dt;
      if (stateT <= 0) player.state = 'idle';
    }
    if (player.state === 'postThrow' && ptime >= redrawAt - 1e-9) redraw();
  }

  function redraw() {
    redrawAt = Infinity;
    if (mode === 'training' || player.spares > 0) {
      if (mode !== 'training') player.spares--;
      player.hasSpearInHand = true;
      player.state = 'idle';
    } else {
      player.weapon = 'sword';
      player.state = 'draw';
      stateT = SWITCH_TIME;
      ev.emit('weaponSwitch', { weapon: 'sword' });
    }
  }

  function switchWeapon(target) {
    const want = target === 'sword' || target === 'spear' ? target : player.weapon === 'sword' ? 'spear' : 'sword';
    if (want === player.weapon) return false;
    const st = player.state;
    if (st === 'throw' || st === 'stagger' || st === 'dead') return false;
    if (st === 'attack') {
      if (!atk.struck) return false;
      endAttack();
    }
    let drawTime = SWITCH_TIME;
    if (want === 'spear') {
      if (player.spearsLeft <= 0) return false;
      if (!player.hasSpearInHand) {
        if (mode !== 'training') player.spares--;
        player.hasSpearInHand = true;
        drawTime = TIMING.throw.redraw;
      }
    }
    redrawAt = Infinity;
    player.weapon = want;
    player.state = 'draw';
    stateT = drawTime;
    ev.emit('weaponSwitch', { weapon: want });
    return true;
  }

  function drainActions() {
    const list = ctx.actions ? ctx.actions.drain() : null;
    if (!list || list.length === 0) return;
    const overridden = cameraOverridden();
    for (let i = 0; i < list.length; i++) {
      const a = list[i];
      if (!a || !a.type) continue;
      if (a.type === 'pause') {
        if (ctx.mode === 'battle' || ctx.mode === 'training') {
          ctx.setMode('paused');
          return;
        }
        continue;
      }
      if (a.type === 'recenter') {
        recenter();
        continue;
      }
      if (a.type === 'switchWeapon') {
        if (player.alive) switchWeapon(a.weapon);
        continue;
      }
      if (a.type === 'switchTarget') {
        switchTarget(a.dir);
        continue;
      }
      if (!ATTACK_ACTIONS[a.type] || !player.alive || overridden) continue;
      if (canStartNow() && startAttack(a)) continue;
      if (canStartNow()) continue; // dropped (no spear, bash cooldown)
      if (interruptForMotion(a)) continue;
      buffer.has = true;
      buffer.action = a;
      buffer.age = 0;
    }
  }

  function stepBuffer(dt) {
    if (!buffer.has) return;
    buffer.age += dt;
    if (buffer.age > BUFFER_LIFE + 1e-9 || !player.alive || cameraOverridden()) {
      buffer.has = false;
      buffer.action = null;
      return;
    }
    if (canStartNow() || canCancelInto(buffer.action)) {
      const a = buffer.action;
      buffer.has = false;
      buffer.action = null;
      if (atk.active) endAttack(); // cancels the rest of the current attack
      startAttack(a);
    }
  }

  // ---- shield ----------------------------------------------------------------------------

  function updateShield(dt) {
    const inp = ctx.input || {};
    const held = !!inp.shieldHeld && player.alive && !shieldLocked();
    const dipping = ptime < dipUntil;
    const target = held ? (dipping ? TIMING.shieldDip : 1) : 0;
    let r = player.shieldRaise;
    if (r < target) r = Math.min(target, r + dt / TIMING.shieldRaise);
    else if (r > target) r = Math.max(target, r - dt / TIMING.shieldLower);
    player.shieldRaise = r;

    if (r < 0.2) {
      if (downSince === Infinity) downSince = gnow();
    } else downSince = Infinity;

    if (!shieldUpFlag && r >= 0.7) {
      shieldUpFlag = true;
      ev.emit('shield', { up: true });
    } else if (shieldUpFlag && r < 0.5 && !(dipping && held)) {
      shieldUpFlag = false;
      ev.emit('shield', { up: false });
    }
  }

  // ---- look and movement -----------------------------------------------------------------

  function updateLook(dt) {
    const inp = ctx.input || {};
    const lock = lockOn();
    const motion = motionMode() || lock;
    const overridden = cameraOverridden();
    const dy = Number.isFinite(inp.lookYaw) ? inp.lookYaw : 0;
    const dp = Number.isFinite(inp.lookPitch) ? inp.lookPitch : 0;
    inp.lookYaw = 0;
    inp.lookPitch = 0;
    if (!player.alive) return;
    if (lock) stepLockYaw(dt, overridden); // the hilt grip ignores look input
    else if (!overridden) {
      player.yaw = wrapAngle(player.yaw + dy);
      if (!motion) player.pitch = clamp(player.pitch + dp, -LOOK_PITCH_LIMIT, LOOK_PITCH_LIMIT);
    }

    if (recenterLeft > 0) {
      const k = Math.min(1, dt / recenterLeft);
      recenterLeft -= dt;
      player.yaw = wrapAngle(player.yaw - player.yaw * k);
      player.pitch -= player.pitch * k;
      if (recenterLeft <= 0) {
        recenterLeft = 0;
        player.yaw = 0;
        player.pitch = 0;
      }
    }

    if (yawImpulse !== 0) {
      const step = Math.sign(yawImpulse) * Math.min(Math.abs(yawImpulse), dt * 1.2);
      yawImpulse -= step;
      player.yaw = wrapAngle(player.yaw + step);
    }

    if (motion) {
      // A steady, slightly lowered pitch (eased back after a recenter or a mode change). Gyro never
      // looks in pitch, and no foe pulls it: the attack turns instead (the hilt lock turns only yaw).
      const step = MOTION_PITCH_RATE * dt;
      player.pitch += clamp(MOTION_PITCH - player.pitch, -step, step);
    }
  }

  function clampToBounds() {
    const b = ctx.world && ctx.world.bounds;
    if (!b) return;
    player.pos.x = clamp(player.pos.x, b.minX, b.maxX);
    player.pos.z = clamp(player.pos.z, b.minZ, b.maxZ);
  }

  function updateMove(dt) {
    if (dt <= 0) return;
    const inp = ctx.input || {};
    const x0 = player.pos.x;
    const z0 = player.pos.z;
    let mx = 0;
    let mz = 0;
    if (player.alive && !cameraOverridden()) {
      mx = clamp(Number(inp.moveX) || 0, -1, 1);
      mz = clamp(Number(inp.moveZ) || 0, -1, 1);
      const l = Math.hypot(mx, mz);
      if (l > 1) {
        mx /= l;
        mz /= l;
      }
    }
    let mul = 1;
    if (player.state === 'attack' || player.state === 'throw') mul = 0.45;
    else if (player.state === 'stagger') mul = 0.3;
    if (player.shieldRaise >= 0.7) mul *= 0.7;
    const sy = Math.sin(player.yaw);
    const cy = Math.cos(player.yaw);
    const fwd = mz * (mz > 0 ? SPEED_FWD : SPEED_BACK) * mul;
    const str = mx * SPEED_STRAFE * mul;
    // forward = (-sin, -cos), right = (cos, -sin)
    const tvx = -sy * fwd + cy * str;
    const tvz = -cy * fwd - sy * str;
    const k = 1 - Math.exp(-14 * dt);
    velX += (tvx - velX) * k;
    velZ += (tvz - velZ) * k;
    player.pos.x += velX * dt;
    player.pos.z += velZ * dt;

    if (pushLeft > 0) {
      const f = Math.min(1, dt / pushLeft);
      player.pos.x += pushX * f;
      player.pos.z += pushZ * f;
      pushX -= pushX * f;
      pushZ -= pushZ * f;
      pushLeft -= dt;
    }

    // Motion mode: drift back to the line slot after a pause in the fighting.
    if (motionMode() && player.alive && mx === 0 && mz === 0 && player.state === 'idle' && ptime - lastAttackAt > DRIFT_DELAY) {
      const d = Math.hypot(player.pos.x, player.pos.z);
      if (d > 1e-4) {
        const s = Math.min(d, DRIFT_SPEED * dt) / d;
        player.pos.x -= player.pos.x * s;
        player.pos.z -= player.pos.z * s;
      }
    }

    separateFromActors();
    clampToBounds();

    const moved = Math.hypot(player.pos.x - x0, player.pos.z - z0);
    speedSmooth += (moved / dt - speedSmooth) * (1 - Math.exp(-10 * dt));
    bobPhase = (bobPhase + moved * (TAU / 1.5)) % (TAU * 64);
    bobAmp += (clamp(speedSmooth / SPEED_FWD, 0, 1) - bobAmp) * (1 - Math.exp(-8 * dt));
  }

  function separateFromActors() {
    const actors = ctx.actors;
    if (!actors) return;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive || a.role === 'helot') continue;
      const min = PLAYER_RADIUS + (a.radius || 0.32);
      const dx = player.pos.x - a.pos.x;
      const dz = player.pos.z - a.pos.z;
      const d2 = dx * dx + dz * dz;
      if (d2 >= min * min) continue;
      const d = Math.sqrt(d2);
      if (d < 1e-4) {
        player.pos.z += min;
        continue;
      }
      const s = (min - d) / d;
      player.pos.x += dx * s;
      player.pos.z += dz * s;
    }
  }

  // ---- public API ------------------------------------------------------------------------

  /** Resets everything for a new battle, training session or the title screen. */
  function reset({ mode: m = 'battle' } = {}) {
    mode = m;
    ptime = 0;
    player.pos.set(0, 0, 0);
    player.yaw = 0;
    player.pitch = 0;
    player.hp = player.maxHp = 100;
    player.alive = true;
    player.state = 'idle';
    player.weapon = 'sword';
    player.spares = mode === 'training' ? MAX_SPARES : START_SPARES;
    player.hasSpearInHand = false;
    player.shieldRaise = 0;
    player.kills = 0;
    player.score = 0;
    player.chain = 1;
    lastHitAt = -Infinity;
    player.combos.reset();
    player.focusTarget = null;
    player.attackYaw = 0;
    atk.active = false;
    atk.target = null;
    buffer.has = false;
    buffer.action = null;
    stateT = 0;
    redrawAt = Infinity;
    lastBashStart = -Infinity;
    lastAttackAt = 0;
    dipUntil = -Infinity;
    shieldLockUntil = -Infinity;
    critUntil = -Infinity;
    shieldUpFlag = false;
    deathT = 0;
    prevHeld = false;
    prevPressedAt = NaN;
    holdStart = -Infinity;
    holdQualifies = false;
    downSince = -Infinity;
    velX = velZ = 0;
    speedSmooth = 0;
    bobPhase = 0;
    bobAmp = 0;
    pushX = pushZ = pushLeft = 0;
    yawImpulse = 0;
    recenterLeft = 0;
    kickAt = -Infinity;
    throwTrack.target = null;
    // the lock restarts with the next hilt update (enterLock, then acquire)
    dropLock('clear', 'off', 0);
    spring.reset(0);
    lastThreatAt = -Infinity;
    for (let i = 0; i < THREAT_MEMORY; i++) {
      threatLost[i] = null;
      threatLostAt[i] = -Infinity;
    }
    emitScore();
    syncViewmodel();
  }

  /** One player frame on the player's own timeline (pdt). */
  function update(pdt) {
    const dt = Number.isFinite(pdt) && pdt > 0 ? pdt : 0;
    ptime += dt;
    if (!player.alive) {
      deathT += dt;
      drainActions();
      updateShield(dt);
      updateLook(dt);
      syncViewmodel();
      return;
    }
    decayChain();
    trackShieldHold();
    stepLock(dt);
    updateFocus();
    stepStates(dt);
    stepAttack(dt);
    drainActions();
    stepBuffer(dt);
    updateLockSides(dt);
    updateShield(dt);
    updateLook(dt);
    updateMove(dt);
    syncViewmodel();
  }

  /** Absolute first-person camera: eye over the feet with breathing, walk bob and hurt flinch. */
  function applyCamera(camera) {
    const now = ctx.time.now;
    if (idleFrame) {
      idleFrame = false;
      const y = groundY(0, 1.5) + EYE;
      camera.position.set(0, y + 0.006 * Math.sin(now * TAU * 0.21), 1.5);
      camera.rotation.set(0.03 + 0.012 * Math.sin(idleT * 0.11), 0.25 * Math.sin(idleT * (TAU / 46)), 0, 'YXZ');
      return;
    }
    const gy = groundY(player.pos.x, player.pos.z);
    const breath = Math.sin(now * TAU * 0.24);
    const walkY = bobAmp * 0.045 * (Math.cos(2 * bobPhase) - 1) * 0.5;
    const walkX = bobAmp * 0.022 * Math.sin(bobPhase);
    const crouch = 0.05 * player.shieldRaise;
    let eyeY = gy + EYE + 0.006 * breath + walkY - crouch;
    let pitch = player.pitch + 0.0025 * breath;
    let roll = bobAmp * 0.006 * Math.sin(bobPhase);

    // Hurt flinch: a quick kick that peaks after ~60 ms and settles.
    const kt = now - kickAt;
    if (kt >= 0 && kt < 0.6) {
      const tau = 0.06;
      const k = (kt / tau) * Math.exp(1 - kt / tau);
      pitch += kickPitch * k;
      roll += kickRoll * k;
    }
    let yaw = player.yaw;
    if (kt >= 0 && kt < 0.6) yaw += kickYaw * (kt / 0.06) * Math.exp(1 - kt / 0.06);

    if (!player.alive) {
      const s = smoothstep(0, 1.1, deathT);
      eyeY = lerp(eyeY, gy + 0.32, s);
      roll = lerp(roll, 0.5, s);
      pitch = lerp(pitch, 0.25, s);
    }
    const sy = Math.sin(player.yaw);
    const cy = Math.cos(player.yaw);
    camera.position.set(player.pos.x + cy * walkX, eyeY, player.pos.z - sy * walkX);
    camera.rotation.set(pitch, yaw, roll, 'YXZ');
  }

  /** Title and game over: a slow pan from behind the line. applyCamera places it. */
  function idleCamera(dt) {
    idleT += Number.isFinite(dt) ? dt : 0;
    idleFrame = true;
  }

  /**
   * Query only: would the shield stop a melee strike from `attacker` right now?
   * Perfect needs a hold that began ≤ perfectBlockWindow ago after the shield had been down
   * (raise < 0.2) for ≥ perfectNeedsDown; it works even before the shield is fully up.
   */
  function tryBlock(attacker /* , kind */) {
    if (!player.alive || mode === 'title') return 'none';
    trackShieldHold();
    if (shieldLocked() || ptime < dipUntil) return 'none';
    if (attacker && attacker.pos && Math.abs(angleTo(attacker)) > BLOCK_HALF_ANGLE) return 'none';
    const inp = ctx.input || {};
    if (inp.shieldHeld && holdQualifies && gnow() - holdStart <= TIMING.perfectBlockWindow + 1e-6) return 'perfect';
    if (player.shieldRaise >= 0.7) return 'block';
    return 'none';
  }

  /** Applies damage (no-op in training, on the title screen or when dead). */
  function damage(amount, info = {}) {
    if (!player.alive || mode === 'training' || mode === 'title' || !(amount > 0)) return;
    player.hp = Math.max(0, player.hp - amount);
    const from = info.from || null;
    let relYaw = info.relYaw;
    if (!Number.isFinite(relYaw)) relYaw = from && from.pos ? angleTo(from) : 0;
    resetChain();
    const s = clamp(amount / 16, 0.4, 1.5);
    kickAt = ctx.time.now;
    kickPitch = 0.05 * s;
    kickYaw = -Math.sign(relYaw || 0) * 0.04 * s;
    kickRoll = (relYaw >= 0 ? -1 : 1) * 0.035 * s; // the head rolls away from the blow
    ev.emit('playerHurt', { damage: amount, hp: player.hp, from, kind: info.kind || 'melee', relYaw });
    if (player.hp <= 0) die();
  }

  function die() {
    player.alive = false;
    player.state = 'dead';
    atk.active = false;
    atk.target = null;
    buffer.has = false;
    buffer.action = null;
    deathT = 0;
    dropLock('clear', 'off', 0);
    ev.emit('playerDeath', {});
  }

  /** A heavy blow on the shield: forced down and locked for 0.8 s, view pushed, brief stagger. */
  function guardBreak(attacker) {
    if (!player.alive) return;
    shieldLockUntil = ptime + GUARD_BREAK_LOCK;
    dipUntil = -Infinity;
    player.shieldRaise = 0;
    downSince = gnow();
    if (shieldUpFlag) {
      shieldUpFlag = false;
      ev.emit('shield', { up: false });
    }
    let side = ctx.rng && ctx.rng.chance ? (ctx.rng.chance(0.5) ? 1 : -1) : 1;
    if (attacker && attacker.pos) {
      const a = angleTo(attacker);
      side = a >= 0 ? -1 : 1; // pushed away from the blow
      const dx = player.pos.x - attacker.pos.x;
      const dz = player.pos.z - attacker.pos.z;
      const d = Math.hypot(dx, dz);
      if (d > 1e-3) push(dx / d, dz / d, 0.25);
    }
    yawImpulse += side * GUARD_BREAK_YAW;
    if (player.state !== 'throw' && player.state !== 'postThrow') {
      if (atk.active) {
        atk.active = false;
        atk.target = null;
      }
      player.state = 'stagger';
      stateT = GUARD_BREAK_STAGGER;
    }
  }

  /** Shoves the player `metres` along (dirX, dirZ) over ~0.18 s, clamped to the bounds. */
  function push(dirX, dirZ, metres) {
    const l = Math.hypot(dirX, dirZ);
    if (!(l > 1e-6) || !(metres > 0)) return;
    pushX += (dirX / l) * metres;
    pushZ += (dirZ / l) * metres;
    pushLeft = 0.18;
  }

  function heal(amount) {
    if (!player.alive || !(amount > 0)) return;
    player.hp = Math.min(player.maxHp, player.hp + amount);
  }

  /**
   * Tops the bundle up to `total` spears, one of them in hand (default: the full 1 + MAX_SPARES).
   * A refill never takes spears away: with more than `total` left it only puts one in hand.
   */
  function refillSpears(total = MAX_SPARES + 1) {
    const want = Number.isFinite(total) ? clamp(Math.round(total), 0, MAX_SPARES + 1) : MAX_SPARES + 1;
    const n = Math.max(want, player.spearsLeft);
    if (n <= 0) return;
    player.spares = n - 1;
    player.hasSpearInHand = true;
    if (player.state === 'postThrow') {
      redrawAt = Infinity;
      player.state = 'idle';
    }
  }

  /**
   * The spears are broken (Day 3): spares = 0. keepInHand false also empties the hand and goes
   * back to the sword (weaponSwitch), cancelling a spear attack or throw in progress.
   */
  function dropSpears({ keepInHand = false } = {}) {
    player.spares = 0;
    if (keepInHand) return;
    player.hasSpearInHand = false;
    redrawAt = Infinity;
    // A throw (even one already loosed) ends now: no redraw follows, so 'postThrow' would stick.
    if (atk.active && (atk.kind === 'throw' || atk.weapon === 'spear')) endAttack();
    if (player.state === 'postThrow' || player.state === 'throw') player.state = 'idle';
    if (player.weapon !== 'sword') {
      player.weapon = 'sword';
      ev.emit('weaponSwitch', { weapon: 'sword' });
    }
  }

  /**
   * Eases the view back to the line (yaw 0, level) and re-anchors the gyro heading. In the hilt
   * grip it drops the lock instead (lockOn 'clear') and faces the front for recenterHold before
   * acquiring again; the spring stays the only yaw writer there.
   */
  function recenter() {
    if (!lockControl()) recenterLeft = 0.25;
    else if (player.alive) {
      if (!lockOn()) enterLock();
      dropLock('clear', 'front', LOCK.recenterHold);
    }
    try {
      if (ctx.gestures && ctx.gestures.recenter) ctx.gestures.recenter();
    } catch (err) {
      console.warn('recenter failed', err);
    }
  }

  // ---- events ----------------------------------------------------------------------------

  ev.on('hit', (e) => {
    if (!e || !e.byPlayer || e.kind === 'bleed') return;
    lastHitAt = gnow();
    player.chain = Math.min(CHAIN_MAX, Math.round((player.chain + CHAIN_STEP) * 10) / 10);
    emitScore();
  });

  ev.on('waveCleared', resetChain);

  ev.on('kill', (e) => {
    if (!e || !e.byPlayer) return;
    player.kills++;
    player.score += Math.round(100 * player.chain) + (e.spear ? 250 : 0) + (e.cause === 'sea' ? 150 : 0);
    emitScore();
  });

  // Phase changes and a new day clear the lock behind the fade (§C.7).
  const clearLockForPhase = () => {
    if (lockOn()) dropLock('clear', 'front', 0);
  };
  ev.on('phase', clearLockForPhase);
  ev.on('dayStart', clearLockForPhase);

  // Threat switch (§C.6, settings.lockAuto): a player-targeted wind-up the shield can't cover
  // (beyond threatAngle of the view) takes the lock at the fast rate, unless the lock itself is
  // winding up at the player, a threat switch happened within threatGap, or that foe lost the lock
  // to a threat within threatReturn (no ping-pong between front and rear).
  ev.on('enemyWindup', (e) => {
    if (!e || !e.targetIsPlayer || !lockOn() || !lockControl() || !player.alive) return;
    if (ctx.settings && ctx.settings.lockAuto === false) return;
    const x = e.actor;
    if (!x || x === lockTarget || !isLockable(x)) return;
    if (Math.abs(angleTo(x)) <= LOCK.threatAngle) return;
    const cur = lockTarget;
    if (cur && cur.state === 'windup' && cur.targetIsPlayer) return;
    if (ptime - lastThreatAt < LOCK.threatGap - 1e-9) return;
    for (let i = 0; i < THREAT_MEMORY; i++) {
      if (threatLost[i] === x && ptime - threatLostAt[i] < LOCK.threatReturn - 1e-9) return;
    }
    if (cur) {
      threatLost[threatSlot] = cur;
      threatLostAt[threatSlot] = ptime;
      threatSlot = (threatSlot + 1) % THREAT_MEMORY;
    }
    lastThreatAt = ptime;
    lockFast = true;
    lockTo(x, 'threat', true);
  });

  ev.on('block', (e) => {
    if (!e || e.by !== 'player' || !e.perfect) return;
    critUntil = ptime + TIMING.critWindow;
    if (player.shieldRaise < 0.7) player.shieldRaise = 0.7;
    addScore(50);
  });

  return player;
}
