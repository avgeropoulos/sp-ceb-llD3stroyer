/**
 * Actors: enemies, allies, dummies and the helot, their brains, the attack director and the
 * corpses (DESIGN.md §11 game/ai.js, numbers from §7 ENEMIES/DIRECTOR).
 *
 * Coordinates follow §3.1: the Greek line is z = 0 facing −Z, the cliff is at −X, the sea at +X,
 * the Phocian Wall at z ≈ 12 with a gap at x ∈ [−2, 2]. Actor facing: root.rotation.y = facing,
 * models look along +Z locally.
 *
 * update() allocates nothing per frame. In the hot paths, doubles never go into closure
 * variables or through small helper calls (V8 would box them); per-frame scalars live in
 * object fields, and the math is written inline.
 */
import * as THREE from 'three';
import { clamp, TAU, angleDiff, yawTo } from '../core/math.js';
import { ENEMIES, DIRECTOR } from './rules.js';
import { LOCK } from './lockon.js';
import { CLIPS } from '../gfx/characters.js';
import { ALLY_SLOTS, LEONIDAS_X } from './campaign.js';

export { ALLY_SLOTS, LEONIDAS_X };

const LEONIDAS = 'Leonidas';
const BLAZONS = ['blazonGorgon', 'blazonBoar', 'blazonScorpion', 'blazonClub', 'blazonLion', 'blazonGorgon', 'blazonBoar'];
const HEIGHTS = { spartan: 1.8, levy: 1.74, sparabara: 1.76, immortal: 1.82, archer: 1.72, captain: 1.95, dummy: 1.75, helot: 1.68 };
const DEG = Math.PI / 180;
const PI = Math.PI;

const RADIUS = 0.32;
const HARD_SEP = 2 * RADIUS + 0.03; // living capsules never come closer than this
const SOFT_SEP = 0.95;              // steering personal space
const X_MIN = -8.3;                 // foot of the cliff
const X_MAX = 8.45;                 // the sea edge, for anything not falling in
const SEA_EDGE = 8.6;               // §7 Into the Gulf
const SEA_LIP = 9.3;                // where the ground falls away
const WATER_Y = -3;
const WALL_Z = 12;
const WALL_HALF = 0.8;
const GAP_X = 1.55;                 // usable half-width of the 4 m gap for a 0.32 m capsule
const REBASH = 2.5;                 // WEAPONS.shield.rebashImmunity
const BASH_STAGGER = 1.1;           // WEAPONS.shield.bashStagger
const GUARDED_MUL = 0.15;
const ALLY_DAMAGE_MUL = 0.45;       // allies hold and wear the enemy down; the player wins the wave
const ALLY_LOOK = 0.6;              // an idle ally keeps its face within this of the front (rad)
const ATTACK_HALF = { thrust: 0.45, overhead: 0.55, axe: 0.6, slash: 0.6, bash: 0.5, shoot: 0.3 };
const ENGAGE_DELAY_MIN = 0.8;       // fallback when DIRECTOR[day].engageDelay is absent
const ENGAGE_DELAY_SPAN = 0.6;
const TURTLE_HOLD = 3.0;            // a shield held this long (s) takes player-targeted blows as heavy
const VOLLEY_COVER = 6.0;           // s after a volley warning (+ its duration) a long hold is not turtling
const ALLY_RELIEF = 15;             // allies take a player's attacker the player hasn't hit for this long (s)
const SPEARCAM_GRACE = 0.5;
const HILT_REAR_GRACE = 0.35;       // s: a hilt player's wind-up grace for an attacker the shield can't cover
const STRIKE_FALLBACK = 0.3;        // force the blow if a visual never calls onStrike
const CORPSE_CAP = 20;
const CORPSE_CAP_LOW = 10;
const SINK_DEPTH = 1;
const SINK_TIME = 3;
const MAX_SINKING = 4;
const SHADOW_DIST2 = 20 * 20;
const SHADOW_NEAREST = 12;          // shadow casters on medium/high: the nearest within 20 m
const SHADOW_NEAREST_LOW = 6;
// A dying body slides up to this far (m) during its fall to keep clear of the bodies already
// down; lying bodies are head–feet segments, kept BODY_GAP apart (two body half-widths).
const NUDGE_MAX = 0.4;
const BODY_GAP = 0.5;
// Where each death leaves the body, in actor space (x the actor's left, z its front) per unit of
// body height: the head and the feet midpoint of the settled pose (measured from the animator).
const DEATH_SEGS = {
  die_back: [-0.08, -0.59, 0.06, 0.23],
  die_spear: [-0.08, -0.59, 0.05, 0.21],
  die_forward: [-0.03, 0.62, 0.1, -0.21],
  die_crumple: [0.55, 0.09, -0.18, 0.15],
};
const WAIT_ROWS_FRONT = [-4.4, -5.9];
const WAIT_ROWS_REAR = [4.2, 5.7];
const GROUND_REFRESH = 0.35;        // re-sample the terrain after moving this far (linear in between)
const GROUND_PROBE = 0.25;

const FRONT_BEARINGS = [0, 35, -35];
const WIDE_BEARINGS = [65, -65];        // Day 3 only
const REAR_BEARINGS = [180, 145, -145]; // Day 3: the Immortals who came over the mountain
const SLOT_DIST = 1.6;                  // engage slots sit at 1.5–1.7 m

const STATS_FALLBACK = { hp: 55, guard: 0.25, damage: 10, windup: 0.85, interval: [3, 4.5], walk: 1.4, run: 2.4, reach: 1.9, attackClip: 'thrust' };

function clipInfo(name) {
  return (CLIPS && CLIPS[name]) || null;
}
function clipDuration(name, fallback) {
  const c = clipInfo(name);
  return c && c.duration > 0 ? c.duration : fallback;
}
function clipStrikeAt(name) {
  const c = clipInfo(name);
  return c && c.strikeAt > 0 && c.strikeAt <= 1 ? c.strikeAt : 0.6;
}

// Lying-body segments (die-time only; scalars go through SEG so that nothing is boxed): SEG holds
// two bodies [hx, hz, fx, fz] at 0 and 4; segGap leaves the closest points in SEG[8..11] (on the
// first body, then the second) and their distance in SEG[12].
const SEG = new Float64Array(13);

/**
 * Writes into SEG[o..o+3] where body b will lie: after its remaining shove and pile nudge. False
 * if its death clip leaves no known lying pose.
 */
function bodySeg(o, b) {
  const d = DEATH_SEGS[b.deathClip];
  if (!d) return false;
  const k = 1 - b.nudgeT;
  const x = b.pos.x + b.kbx / 9 + b.nudgeX * k, z = b.pos.z + b.kbz / 9 + b.nudgeZ * k;
  const f = b.facing, h = b.height;
  const c = Math.cos(f), sn = Math.sin(f);
  // actor space (x left, z front) → world: x·(cos f, −sin f) + z·(sin f, cos f)
  SEG[o] = x + (d[0] * c + d[1] * sn) * h;
  SEG[o + 1] = z + (-d[0] * sn + d[1] * c) * h;
  SEG[o + 2] = x + (d[2] * c + d[3] * sn) * h;
  SEG[o + 3] = z + (-d[2] * sn + d[3] * c) * h;
  return true;
}

/** Closest distance between the segments in SEG[0..3] and SEG[4..7] (2D), into SEG[12]. */
function segGap() {
  const ax = SEG[0], az = SEG[1], bx = SEG[4], bz = SEG[5];
  const ux = SEG[2] - ax, uz = SEG[3] - az, vx = SEG[6] - bx, vz = SEG[7] - bz;
  const rx = ax - bx, rz = az - bz;
  const a = ux * ux + uz * uz, e = vx * vx + vz * vz, f = vx * rx + vz * rz;
  let s = 0, t = 0;
  if (a < 1e-9 && e < 1e-9) { s = 0; t = 0; } else if (a < 1e-9) { t = clamp(f / e, 0, 1); } else {
    const c = ux * rx + uz * rz;
    if (e < 1e-9) s = clamp(-c / a, 0, 1);
    else {
      const b = ux * vx + uz * vz, den = a * e - b * b;
      s = den > 1e-9 ? clamp((b * f - c * e) / den, 0, 1) : 0;
      t = (b * s + f) / e;
      if (t < 0) { t = 0; s = clamp(-c / a, 0, 1); } else if (t > 1) { t = 1; s = clamp((b - c) / a, 0, 1); }
    }
  }
  SEG[8] = ax + ux * s; SEG[9] = az + uz * s;
  SEG[10] = bx + vx * t; SEG[11] = bz + vz * t;
  const dx = SEG[8] - SEG[10], dz = SEG[9] - SEG[11];
  SEG[12] = Math.sqrt(dx * dx + dz * dz);
}

/** A visual that does nothing, used if gfx/characters is missing or throws. */
function nullVisual(kind) {
  const root = new THREE.Group();
  const noop = () => {};
  return {
    root, mesh: null, kind,
    sockets: { head: root, chest: root, rightHand: root, leftHand: root, back: root },
    anim: { setLocomotion: noop, setGuard: noop, play: noop, flinch: noop, update: noop, clip: null, playing: false, guard: false },
    attach(o) { root.attach(o); },
    setBloodied: noop,
    setVisible(v) { root.visible = v; },
    dispose() { if (root.parent) root.parent.remove(root); },
  };
}

/**
 * Creates the actor system. update(gdt) runs after player.update and before combat.update (§10).
 * @param {object} ctx shared context (§4)
 */
export function createAI(ctx) {
  const events = ctx.events;
  if (!ctx.actors) ctx.actors = [];
  const actors = ctx.actors;       // ONLY living actors (§4)
  const corpses = [];
  const attackersOut = [];         // playerAttackers (reused)
  const tmpV = new THREE.Vector3();
  const GETUP_T = clipDuration('getup', 1.0);
  const KNOCKDOWN_T = clipDuration('knockdown', 0.9);
  let nextId = 1;
  let leonidasFallen = false;
  let helot = null;
  let playErrors = 0;

  // Per-frame snapshot. Doubles live in object fields (no per-frame boxing).
  const F = { px: 0, pz: 0, gnow: 0 };
  let playerAlive = false;
  let hasPlayer = false;
  let day = 1;
  let candidate = null;

  // Director (§11): token bucket, concurrent wind-up cap, gap between strike moments.
  const dir = {
    tokens: 1, cap: 1, rate: 0.18, gap: 0.6, windups: 1, engaged: 2, lastStrikeAt: -1e9, lastWindupAt: -1e9,
    engageMin: ENGAGE_DELAY_MIN, engageSpan: ENGAGE_DELAY_SPAN, volleyUntil: -1e9,
  };

  // Engage slots around the player: bearings relative to the line (−Z), not the camera.
  const slots = [];
  const addSlots = (list, kind) => {
    for (const b of list) slots.push({ bearing: b * DEG, sin: Math.sin(b * DEG), cos: Math.cos(b * DEG), kind, occupant: null, x: 0.5, z: 0.5, blocked: false });
  };
  addSlots(FRONT_BEARINGS, 'front');
  addSlots(WIDE_BEARINGS, 'wide');
  addSlots(REAR_BEARINGS, 'rear');

  // Loose ranks for enemies with no one to fight yet.
  const waitSpots = [];
  for (let r = 0; r < WAIT_ROWS_FRONT.length; r++) {
    for (let x = -6.3 + r * 0.7; x <= 7.7; x += 1.4) waitSpots.push({ x: x + 0.001, z: WAIT_ROWS_FRONT[r], rear: false, occupant: null });
  }
  for (let r = 0; r < WAIT_ROWS_REAR.length; r++) {
    for (let x = -4.2 + r * 0.7; x <= 4.9; x += 1.4) waitSpots.push({ x: x + 0.001, z: WAIT_ROWS_REAR[r], rear: true, occupant: null });
  }

  // ---------------------------------------------------------------------------------------
  // Actor

  class Actor {
    constructor(kind, team, role) {
      this.id = nextId++;
      this.kind = kind;
      this.team = team;
      this.role = role;
      this.name = '';
      this.visual = null;
      this.pos = new THREE.Vector3();
      this.facing = 0.5;
      this.hp = 1;
      this.maxHp = 1;
      this.radius = RADIUS;
      this.height = HEIGHTS[kind] || 1.75;
      this.alive = true;
      this.state = 'idle';
      this.guard = 0.5;
      this.blockChance = 0.5;
      this.staggerT = 0.5;
      this.lastBashedAt = -1e9;
      // Public extras: who it fights and when its blow lands (HUD chevrons, player auto-face).
      this.targetIsPlayer = false;
      this.foe = null;
      this.strikeAt = -0.5;
      this.timeToStrike = 0.5;
      this.trainer = false;
      this.fromBehind = false;
      this.deathCause = null;
      // Internal. Numeric fields start as doubles so V8 keeps them unboxed in place.
      this.stats = STATS_FALLBACK;
      this.baseBlock = 0.5;
      this.attackClip = 'thrust';
      this.manualStrike = false;
      this.vx = 0.5; this.vz = 0.5; this.speed = 0.5; this.prevX = 0.5; this.prevZ = 0.5; this.speedCap = 0.5;
      this.kbx = 0.5; this.kbz = 0.5;
      this.gx = 0.5; this.gz = 0.5; this.hasGoal = false; this.goalR = 0.5; this.goalRun = false;
      this.faceTo = 0.5; this.hasFace = false;
      this.groundY = 0.5; this.gyY = 0.5; this.gyX = 0.5; this.gyZ = 0.5; this.gyDX = 0.5; this.gyDZ = 0.5; this.gyValid = false;
      this.rotSent = 0.5; this.locoSent = -1.5;
      this.slot = null; this.allySpot = null; this.wait = null; this.spots = null;
      this.homeX = 0.5; this.homeZ = 0.5;
      this.thinkT = 0.5; this.stateT = 0.5; this.delay = 0.5;
      this.engagedAt = -1.5; this.readyAt = 0.5; this.nextAttackAt = 0.5;
      this.playerHitAt = -1.5;  // last time the player's blow landed on it (ally relief)
      this.gate = 2;            // from behind: 0 to the gap, 1 through it, 2 free
      this.lane = 0.5;
      this.speedMul = 1.5;
      this.guardOn = false;
      this.getup = false;
      this.cheered = false;
      this.protected = false;   // Leonidas, until his scripted fall
      this.keepCorpse = false;
      this.removeMe = false;
      this.animDone = false;
      this.frozen = false;      // a settled corpse whose visual no longer updates (setFrozen)
      this.deathClip = '';
      this.deathT = 0.5;
      this.sinkT = -1.5;
      this.nudgeX = 0.5; this.nudgeZ = 0.5; this.nudgeT = 1.5;
      this.fall = 0;            // Into the Gulf: 1 slide, 2 drop, 3 in the water
      this.fallY = 0.5; this.fallVY = 0.5; this.fallDX = 0.5; this.fallDZ = 0.5;
      this.helotPhase = 0;
      this.attack = { kind: 'melee', damage: 0.5, reach: 0.5, halfAngle: 0.45, heavy: false, target: null };
      this.result = { damage: 0.5, guarded: false, killed: false, cause: null };
      this.playAttack = { duration: 1.5, onStrike: () => resolveStrike(this), onEnd: null };
      this.playReact = { duration: 1.5 };
      this.playDeath = { duration: 1.5, onEnd: () => { this.animDone = true; } };
      // Reset the placeholders.
      this.facing = 0; this.guard = 0; this.blockChance = 0; this.staggerT = 0; this.strikeAt = -1; this.timeToStrike = 0;
      this.baseBlock = 0; this.vx = 0; this.vz = 0; this.speed = 0; this.speedCap = 0; this.kbx = 0; this.kbz = 0;
      this.gx = 0; this.gz = 0; this.goalR = 0.12; this.faceTo = 0; this.groundY = 0; this.gyY = 0; this.gyDX = 0; this.gyDZ = 0;
      this.homeX = 0; this.homeZ = 0; this.thinkT = 0; this.stateT = 0; this.delay = 0; this.engagedAt = -1; this.playerHitAt = -1;
      this.readyAt = 0; this.nextAttackAt = 0; this.lane = 0; this.speedMul = 1; this.deathT = 0; this.sinkT = -1;
      this.nudgeX = 0; this.nudgeZ = 0; this.nudgeT = 1;
      this.fallY = 0; this.fallVY = 0; this.fallDX = 0; this.fallDZ = 0;
      this.attack.damage = 0; this.attack.reach = 0; this.result.damage = 0;
    }

    /**
     * Applies a hit (§7 guard rule): guard, damage, flinch, stagger, knockdown, sea fall.
     * Returns { damage, guarded, killed, cause: 'weapon'|'sea'|'bleed'|null }; the object is
     * reused per actor, so read it before the next hit on the same actor.
     */
    takeHit(hit) {
      return takeHit(this, hit);
    }

    /** World position of the chest (or of the body on the ground). */
    chestPoint(out) {
      const o = out || new THREE.Vector3();
      const lying = !this.alive || this.state === 'knockdown';
      o.x = this.pos.x;
      o.y = this.groundY + (lying ? 0.28 : this.height * 0.72);
      o.z = this.pos.z;
      return o;
    }
  }

  // ---------------------------------------------------------------------------------------
  // Spawning

  function createVisual(kind, opts) {
    let v = null;
    try {
      if (ctx.characters && ctx.characters.create) v = ctx.characters.create(kind, opts);
    } catch (err) {
      console.error('ai: characters.create failed', kind, err);
    }
    return v || nullVisual(kind);
  }

  function groundAt(x, z) {
    const w = ctx.world;
    return w && w.groundHeight ? w.groundHeight(x, z) : 0;
  }

  /**
   * Feet height: the terrain is sampled (with its slope) only after moving GROUND_REFRESH m,
   * and extrapolated linearly in between.
   */
  function updateGround(a) {
    if (a.fall >= 2) { a.groundY = a.fallY; return; }
    const dx = a.pos.x - a.gyX, dz = a.pos.z - a.gyZ;
    if (a.gyValid && a.fall === 0 && dx * dx + dz * dz < GROUND_REFRESH * GROUND_REFRESH) {
      a.groundY = a.gyDX * dx + a.gyDZ * dz + a.gyY;
      return;
    }
    const x = a.pos.x, z = a.pos.z;
    const y = groundAt(x, z);
    a.gyY = y;
    a.gyDX = (groundAt(x + GROUND_PROBE, z) - y) / GROUND_PROBE;
    a.gyDZ = (groundAt(x, z + GROUND_PROBE) - y) / GROUND_PROBE;
    a.gyX = x;
    a.gyZ = z;
    a.gyValid = true;
    a.groundY = y;
  }

  /** Root transform from pos/facing and the ground (§11). */
  function place(a) {
    updateGround(a);
    a.pos.y = a.groundY;
    const p = a.visual.root.position;
    p.x = a.pos.x;
    p.y = a.sinkT >= 0 ? a.groundY - SINK_DEPTH * Math.min(1, a.sinkT / SINK_TIME) : a.groundY;
    p.z = a.pos.z;
    if (a.facing !== a.rotSent) {
      // Same as `rotation.y = facing` (Object3D's onRotationChange), without passing a double
      // through the setter call.
      const root = a.visual.root;
      a.rotSent = a.facing;
      root.rotation._y = a.facing;
      root.quaternion.setFromEuler(root.rotation, false);
    }
  }

  function addActor(a, opts) {
    a.visual = createVisual(a.kind, opts);
    const K = ctx.characters && ctx.characters.KINDS;
    if (K && K[a.kind] && K[a.kind].height > 0) a.height = K[a.kind].height;
    if (a.visual.mesh) a.visual.mesh.castShadow = true;
    ctx.scene.add(a.visual.root);
    a.prevX = a.pos.x;
    a.prevZ = a.pos.z;
    a.rotSent = NaN;
    place(a);
    actors.push(a);
    return a;
  }

  /** Spawns a Persian. opts: { facing, delay, role, variant, trainer, fromBehind } */
  function spawnEnemy(kind, x, z, opts = {}) {
    const stats = ENEMIES[kind] || STATS_FALLBACK;
    const role = opts.role || (kind === 'archer' ? 'archer' : 'melee');
    const a = new Actor(kind, 'persian', role);
    a.stats = stats;
    a.hp = a.maxHp = stats.hp > 0 ? stats.hp : STATS_FALLBACK.hp;
    a.baseBlock = stats.guard || 0;
    a.blockChance = a.baseBlock;
    a.attackClip = role === 'archer' ? 'shoot' : stats.attackClip || 'thrust';
    a.manualStrike = !clipInfo(a.attackClip);
    a.trainer = !!opts.trainer;
    a.fromBehind = !!opts.fromBehind;
    a.delay = opts.delay > 0 ? opts.delay : 0;
    a.speedMul = 0.92 + ctx.rng.next() * 0.16;
    a.lane = clamp(x, -1.1, 1.1) * 0.8;
    a.pos.set(x, 0, z);
    a.facing = opts.facing !== undefined ? opts.facing : a.fromBehind ? PI : 0;
    a.homeX = x;
    a.homeZ = z;
    if (role === 'archer') {
      // Archers keep well back (z < −25) and shoot at the allies.
      a.homeX = clamp(x, -5.5, 6.5);
      a.homeZ = -26 - ctx.rng.next() * 5;
    }
    a.gate = a.fromBehind && z > WALL_Z ? 0 : 2;
    a.thinkT = ctx.rng.next() * 0.3;
    a.state = a.delay > 0 ? 'idle' : 'advance';
    if (a.trainer) {
      a.state = 'engage';
      a.targetIsPlayer = true;
      a.engagedAt = ctx.time.gnow;
      a.readyAt = ctx.time.gnow + 1.5;
      a.nextAttackAt = a.readyAt;
    }
    const variant = opts.variant || (kind === 'levy' ? (ctx.rng.chance(0.5) ? 'mede' : 'kissian') : undefined);
    return addActor(a, { variant, seed: a.id });
  }

  /** Spawns a Spartan into the line slot at world x. opts: { name, blazon, walkIn } */
  function spawnAlly(x, opts = {}) {
    const stats = ENEMIES.spartan || STATS_FALLBACK;
    const a = new Actor('spartan', 'greek', 'ally');
    a.stats = stats;
    a.name = opts.name || '';
    a.hp = a.maxHp = stats.hp || 150;
    a.baseBlock = stats.guard || 0.6;
    a.blockChance = a.baseBlock;
    a.attackClip = stats.attackClip || 'thrust';
    a.manualStrike = !clipInfo(a.attackClip);
    a.homeX = x;
    a.homeZ = -0.05 + (ctx.rng.next() - 0.5) * 0.12;
    a.protected = a.name === LEONIDAS;
    a.thinkT = ctx.rng.next() * 0.3;
    if (opts.walkIn) {
      a.pos.set(x + (ctx.rng.next() - 0.5) * 0.8, 0, 7.5 + ctx.rng.next() * 2);
      a.state = 'advance';
    } else {
      a.pos.set(x, 0, a.homeZ);
      a.state = 'hold';
    }
    a.facing = PI;
    // The spots enemies stand on to fight this ally: front; a second front and the rear on Day 3.
    a.spots = [
      { ally: a, kind: 0, occupant: null, x: 0.5, z: 0.5 },
      { ally: a, kind: 1, occupant: null, x: 0.5, z: 0.5 },
      { ally: a, kind: 2, occupant: null, x: 0.5, z: 0.5 },
    ];
    let blazon = opts.blazon;
    if (!blazon) {
      const i = ALLY_SLOTS.indexOf(x);
      blazon = a.protected ? 'blazonLion' : BLAZONS[(i >= 0 ? i : a.id) % BLAZONS.length];
    }
    return addActor(a, { blazon, name: a.name || undefined, seed: a.id });
  }

  /** Fills the empty slots in xs (default ALLY_SLOTS) and restores the living. opts: { walkIn } */
  function ensureAllies(xs, opts) {
    const list = xs || ALLY_SLOTS;
    for (let s = 0; s < list.length; s++) {
      const x = list[s];
      let found = null;
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (a.alive && a.role === 'ally' && Math.abs(a.homeX - x) < 0.05) { found = a; break; }
      }
      if (found) {
        found.hp = found.maxHp;
        if (found.visual.setBloodied) found.visual.setBloodied(0);
        continue;
      }
      const name = Math.abs(x - LEONIDAS_X) < 0.05 && !leonidasFallen ? LEONIDAS : undefined;
      spawnAlly(x, { name, walkIn: !!(opts && opts.walkIn) });
    }
  }

  /** A training dummy: team 'persian', role 'dummy', never dies. opts: { far, facing } */
  function spawnDummy(x, z, opts = {}) {
    const a = new Actor('dummy', 'persian', 'dummy');
    a.stats = ENEMIES.dummy || { hp: Infinity, guard: 0, damage: 0 };
    a.hp = a.maxHp = Infinity;
    a.pos.set(x, 0, z);
    a.homeX = x;
    a.homeZ = z;
    a.facing = opts.facing !== undefined ? opts.facing : 0;
    a.state = 'idle';
    return addActor(a, { far: !!opts.far, seed: a.id });
  }

  /** The helot runs a bundle of spears from behind the wall to the player and back. */
  function spawnHelot() {
    if (helot && helot.alive) return helot;
    const a = new Actor('helot', 'greek', 'helot');
    a.stats = ENEMIES.helot || { hp: 1, guard: 0, damage: 0, walk: 3.5 };
    a.hp = a.maxHp = a.stats.hp || 1;
    a.pos.set(0.9, 0, 10);
    a.facing = PI;
    a.state = 'advance';
    helot = a;
    return addActor(a, { seed: a.id });
  }

  // ---------------------------------------------------------------------------------------
  // Animation helpers

  function play(a, clip, opts) {
    if (!clipInfo(clip)) return false;
    try {
      a.visual.anim.play(clip, opts);
      return true;
    } catch (err) {
      if (playErrors++ < 5) console.error('ai: anim.play failed', clip, err);
      return false;
    }
  }

  function playReaction(a, clip, duration) {
    a.playReact.duration = duration > 0 ? duration : clipDuration(clip, 0.5);
    play(a, clip, a.playReact);
  }

  function setGuard(a, on) {
    if (a.guardOn === on) return;
    a.guardOn = on;
    a.visual.anim.setGuard(on);
  }

  // ---------------------------------------------------------------------------------------
  // Hits, deaths, corpses

  function takeHit(a, hit) {
    const res = a.result;
    res.damage = 0;
    res.guarded = false;
    res.killed = false;
    res.cause = null;
    if (!a.alive || !hit || a.role === 'helot') return res;

    const now = ctx.time.gnow;
    if (hit.byPlayer) a.playerHitAt = now;
    let dx = hit.dir ? hit.dir.x || 0 : 0;
    let dz = hit.dir ? hit.dir.z || 0 : 0;
    const dl = Math.sqrt(dx * dx + dz * dz);
    if (dl > 1e-6) { dx /= dl; dz /= dl; } else { dx = -Math.sin(a.facing); dz = -Math.cos(a.facing); }
    const kind = hit.kind || 'melee';
    const power = hit.power || 0;
    let stagger = hit.stagger > 0 ? hit.stagger : 0;
    let knock = hit.knockback > 0 ? hit.knockback : 0;
    let knockdown = !!hit.knockdown;
    const isBash = kind === 'bash';

    // §7 Bash: an actor bashed within the last 2.5 s only takes damage and a flinch, unless
    // the bash completes Shield Wall. (combat may already have stamped lastBashedAt = now.)
    if (isBash) {
      const since = now - a.lastBashedAt;
      const wall = hit.combo === 'wall' || hit.wall === true;
      if (since > 1e-4 && since < REBASH && !wall) {
        stagger = 0;
        knock = 0;
        knockdown = false;
      } else {
        a.lastBashedAt = now;
        if (stagger <= 0) stagger = BASH_STAGGER;
      }
    }

    // §7 Enemy guard.
    const disabled = a.state === 'stagger' || a.state === 'knockdown';
    const ignore = !!hit.ignoreGuard || isBash || kind === 'bleed' || disabled ||
      ((kind === 'thrust' || kind === 'spearThrust') && power > 0.8);
    const front = Math.sin(a.facing) * -dx + Math.cos(a.facing) * -dz > 0.5;
    const guarded = !ignore && front && a.blockChance > 0 && ctx.rng.chance(a.blockChance);
    const dmg = Math.max(0, (hit.damage || 0) * (guarded ? GUARDED_MUL : 1));
    res.damage = dmg;
    res.guarded = guarded;
    const flinchK = clamp(dmg / 30, guarded ? 0.15 : 0.3, 1) * (hit.critical ? 1.3 : 1);

    if (a.role === 'dummy') {
      a.visual.anim.flinch(dx, dz, Math.max(flinchK, 0.4));
      return res;
    }

    // §7 Into the Gulf: knockback that carries a Persian past the edge.
    if (a.team === 'persian' && knock > 0 && a.pos.x + dx * knock > SEA_EDGE) {
      res.damage = Math.max(dmg, a.hp);
      res.guarded = false;
      res.killed = true;
      res.cause = 'sea';
      a.hp = 0;
      die(a, 'sea', dx, dz, kind, null);
      return res;
    }

    a.hp -= dmg;
    if (a.protected && a.hp < 1) a.hp = 1;
    if (a.hp <= 0) {
      a.hp = 0;
      res.killed = true;
      res.cause = kind === 'bleed' ? 'bleed' : 'weapon';
      die(a, res.cause, dx, dz, kind, null);
      return res;
    }

    if (dmg > 0 && !guarded && a.visual.setBloodied) a.visual.setBloodied(clamp(1 - a.hp / a.maxHp, 0, 1));
    if ((dmg > 0 && kind !== 'bleed') || stagger > 0) a.visual.anim.flinch(dx, dz, flinchK);

    if (knock > 0) {
      // Exponential slide that covers `knock` metres (λ = 9).
      a.kbx += dx * knock * 9;
      a.kbz += dz * knock * 9;
    }

    if (knockdown) {
      a.strikeAt = -1;
      a.state = 'knockdown';
      a.stateT = KNOCKDOWN_T + 0.7 + GETUP_T;
      a.staggerT = a.stateT;
      a.getup = false;
      playReaction(a, 'knockdown', KNOCKDOWN_T);
      setGuard(a, false);
      a.guard = 0;
      a.nextAttackAt = Math.max(a.nextAttackAt, now + a.stateT + 0.6);
    } else if (stagger > 0) {
      // Stagger interrupts a wind-up (the new clip cancels its callbacks) and drops the guard.
      a.strikeAt = -1;
      a.state = 'stagger';
      a.staggerT = stagger;
      a.stateT = stagger;
      playReaction(a, 'stagger', stagger);
      setGuard(a, false);
      a.guard = 0;
      a.nextAttackAt = Math.max(a.nextAttackAt, now + stagger + 0.5);
    } else if (a.state !== 'windup' && a.state !== 'recover' && kind !== 'bleed') {
      if (guarded) playReaction(a, 'block_hit', 0);
      else if (dmg > 0) {
        playReaction(a, 'hit', 0);
        a.nextAttackAt = Math.max(a.nextAttackAt, now + 0.45);
      }
    }
    return res;
  }

  function deathClipFor(a, cause, dx, dz, kind) {
    if (cause === 'sea') return clipInfo('fall_sea') ? 'fall_sea' : 'die_back';
    if (kind === 'spear' || kind === 'throw') return 'die_spear';
    const fromFront = Math.sin(a.facing) * -dx + Math.cos(a.facing) * -dz;
    if (fromFront > 0.35) return ctx.rng.chance(0.7) ? 'die_back' : 'die_crumple';
    if (fromFront < -0.35) return 'die_forward';
    return 'die_crumple';
  }

  function die(a, cause, dx, dz, kind, clipOverride) {
    a.alive = false;
    a.state = 'dead';
    a.deathCause = cause;
    a.strikeAt = -1;
    a.staggerT = 0;
    a.guard = 0;
    a.blockChance = 0;
    releaseAll(a);
    a.targetIsPlayer = false;
    const clip = clipOverride || deathClipFor(a, cause, dx, dz, kind);
    a.deathClip = clip;
    a.nudgeX = 0;
    a.nudgeZ = 0;
    a.nudgeT = 1;
    if (cause === 'sea') {
      a.fall = 1;
      a.fallDX = dx;
      a.fallDZ = dz;
      a.kbx = 0;
      a.kbz = 0;
    } else {
      a.kbx *= 0.5; // bodies keep a little of the blow's momentum
      a.kbz *= 0.5;
      clearOfBodies(a);
    }
    a.playDeath.duration = clipDuration(clip, 1.2);
    a.animDone = false;
    a.deathT = 0;
    const an = a.visual.anim;
    an.setLocomotion(0);
    an.setGuard(false);
    a.guardOn = false;
    if (!play(a, clip, a.playDeath)) a.animDone = true;
    if (a.visual.setBloodied && cause !== 'sea') a.visual.setBloodied(1);
  }

  /**
   * Accumulates into PUSH[0..1] the way out of the bodies in `pile` for the lying segment in
   * SEG[0..3] (each overlap BODY_GAP − gap, away from the other body), and their sum into PUSH[2].
   */
  const PUSH = new Float64Array(3);
  const pile = [];   // the bodies near a dying one (die-time scratch)
  let pileN = 0;
  function pileOverlap() {
    const mx = (SEG[0] + SEG[2]) * 0.5, mz = (SEG[1] + SEG[3]) * 0.5;
    let cost = 0;
    for (let i = 0; i < pileN; i++) {
      bodySeg(4, pile[i]);
      segGap();
      const d = SEG[12];
      if (d >= BODY_GAP) continue;
      cost += BODY_GAP - d;
      let ux = SEG[8] - SEG[10], uz = SEG[9] - SEG[11];
      if (d < 1e-3) {
        // crossing: away from the other body's axis, on the side of this body's middle
        ux = -(SEG[7] - SEG[5]);
        uz = SEG[6] - SEG[4];
        if (ux * (mx - SEG[4]) + uz * (mz - SEG[5]) < 0) { ux = -ux; uz = -uz; }
      }
      const l = Math.sqrt(ux * ux + uz * uz);
      if (l < 1e-6) continue;
      PUSH[0] += (ux * (BODY_GAP - d)) / l;
      PUSH[1] += (uz * (BODY_GAP - d)) / l;
    }
    PUSH[2] = cost;
  }

  /**
   * Heaps of dead must not pass through each other: the dying body's final pose slides (up to
   * NUDGE_MAX, during its fall in updateCorpses) out of the bodies already down or falling that
   * lie within BODY_GAP of where it will lie. Die-time only.
   */
  function clearOfBodies(a) {
    if (!DEATH_SEGS[a.deathClip]) return;
    // the bodies down or falling within reach of this one (a lying body spans < 1.3 m)
    pileN = 0;
    const n = corpses.length + actors.length;
    for (let i = 0; i < n; i++) {
      const b = i < corpses.length ? corpses[i] : actors[i - corpses.length];
      if (b === a || b.alive || b.fall !== 0 || b.sinkT >= 0 || !DEATH_SEGS[b.deathClip]) continue;
      const dx = b.pos.x - a.pos.x, dz = b.pos.z - a.pos.z;
      if (dx * dx + dz * dz > 9) continue;
      if (pileN < pile.length) pile[pileN] = b;
      else pile.push(b);
      pileN++;
    }
    if (pileN === 0) return;
    bodySeg(0, a);
    PUSH[0] = 0;
    PUSH[1] = 0;
    pileOverlap();
    let px = PUSH[0], pz = PUSH[1];
    const pl = Math.sqrt(px * px + pz * pz);
    if (pl > NUDGE_MAX) {
      px *= NUDGE_MAX / pl;
      pz *= NUDGE_MAX / pl;
    }
    for (let i = 0; i < pileN; i++) pile[i] = null;
    const moved = px * px + pz * pz >= 0.0004;
    a.nudgeX = moved ? px : 0;
    a.nudgeZ = moved ? pz : 0;
    a.nudgeT = moved ? 0 : 1;
  }

  /** Scripted death (Leonidas on Day 3, phase B). Returns the actor or null. */
  function killAlly(name) {
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive || a.role !== 'ally' || a.name !== name) continue;
      a.protected = false;
      a.keepCorpse = name === LEONIDAS; // they fight over his body: it never sinks
      if (name === LEONIDAS) leonidasFallen = true;
      die(a, 'scripted', -Math.sin(a.facing), -Math.cos(a.facing), 'spear', clipInfo('die_spear') ? 'die_spear' : 'die_back');
      if (ctx.audio && ctx.audio.play) ctx.audio.play('death', { volume: 0.9, at: a.pos });
      return a;
    }
    if (name === LEONIDAS) leonidasFallen = true;
    return null;
  }

  /** Archers walk off to z = −70 and are removed without a corpse. */
  function retreatArchers() {
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive || a.role !== 'archer' || a.state === 'retreat') continue;
      releaseAll(a);
      a.strikeAt = -1;   // a drawn shot is never loosed: onStrike only acts in 'windup'
      a.staggerT = 0;
      a.state = 'retreat';
    }
  }

  function corpseCap() {
    return ctx.quality === 'low' ? CORPSE_CAP_LOW : CORPSE_CAP;
  }

  function disposeActor(a) {
    try {
      a.visual.dispose();
    } catch (err) {
      console.error('ai: dispose failed', err);
    }
    if (a.visual.root.parent) a.visual.root.parent.remove(a.visual.root);
  }

  /** Moves dead and departed actors out of ctx.actors (start of every update). */
  function sweep() {
    let w = 0;
    let moved = false;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (a.alive) { actors[w++] = a; continue; }
      moved = true;
      releaseAll(a);
      if (a === helot) helot = null;
      if (a.removeMe) { disposeActor(a); continue; }
      if (a.visual.mesh) a.visual.mesh.castShadow = false;
      a.gyValid = false;
      corpses.push(a);
    }
    actors.length = w;
    if (!moved) return;

    // Corpse cap: the oldest resting bodies sink.
    const cap = corpseCap();
    let resting = 0;
    let sinking = 0;
    for (let i = 0; i < corpses.length; i++) {
      const c = corpses[i];
      if (c.sinkT >= 0) sinking++;
      else if (!c.keepCorpse) resting++;
    }
    for (let i = 0; i < corpses.length && resting > cap; i++) {
      const c = corpses[i];
      if (c.sinkT >= 0 || c.keepCorpse) continue;
      c.sinkT = 0;
      resting--;
      sinking++;
    }
    // Never more than a few on their way down at once.
    while (sinking > MAX_SINKING) {
      let idx = -1;
      for (let i = 0; i < corpses.length; i++) if (corpses[i].sinkT >= 0) { idx = i; break; }
      if (idx < 0) break;
      disposeActor(corpses[idx]);
      for (let i = idx; i < corpses.length - 1; i++) corpses[i] = corpses[i + 1];
      corpses.length--;
      sinking--;
    }
  }

  function updateCorpses(gdt) {
    let w = 0;
    for (let i = 0; i < corpses.length; i++) {
      const c = corpses[i];
      if (c.frozen) {
        // A settled body costs nothing until it moves again (it starts to sink).
        if (c.sinkT < 0 && c.fall === 0 && c.kbx === 0 && c.kbz === 0) { corpses[w++] = c; continue; }
        c.frozen = false;
        if (c.visual.setFrozen) c.visual.setFrozen(false);
      }
      if (c.fall > 0) updateSeaFall(c, gdt);
      else if (c.kbx !== 0 || c.kbz !== 0) integrateKnock(c, gdt);
      if (c.nudgeT < 1 && (c.animDone || c.deathT > 0.15 * c.playDeath.duration)) {
        // the pile nudge, spread over the fall
        let k = gdt / (0.55 * c.playDeath.duration);
        if (k > 1 - c.nudgeT) k = 1 - c.nudgeT;
        c.nudgeT += k;
        c.pos.x += c.nudgeX * k;
        c.pos.z += c.nudgeZ * k;
        if (c.fall === 0) c.pos.x = c.pos.x < X_MIN ? X_MIN : c.pos.x > X_MAX ? X_MAX : c.pos.x;
      }
      if (c.sinkT >= 0) {
        c.sinkT += gdt;
        if (c.sinkT >= SINK_TIME) { disposeActor(c); continue; }
      }
      place(c);
      if (!c.animDone) {
        c.visual.anim.update(gdt);
        c.deathT += gdt;
        if (c.deathT > c.playDeath.duration + 0.5) c.animDone = true; // in case onEnd never comes
      } else if (c.sinkT < 0 && c.fall === 0 && c.kbx === 0 && c.kbz === 0 && c.nudgeT >= 1 && c.visual.setFrozen) {
        // Settled: freeze its matrices and skeleton (render CPU in big fights).
        c.frozen = true;
        c.visual.setFrozen(true);
      }
      corpses[w++] = c;
    }
    corpses.length = w;
  }

  function integrateKnock(a, gdt) {
    a.pos.x += a.kbx * gdt;
    a.pos.z += a.kbz * gdt;
    const k = Math.exp(-9 * gdt);
    a.kbx *= k;
    a.kbz *= k;
    if (Math.abs(a.kbx) < 0.02 && Math.abs(a.kbz) < 0.02) { a.kbx = 0; a.kbz = 0; }
    if (a.fall === 0) a.pos.x = a.pos.x < X_MIN ? X_MIN : a.pos.x > X_MAX ? X_MAX : a.pos.x;
  }

  /** Into the Gulf: slide over the edge, drop to the water, splash, sink. */
  function updateSeaFall(a, gdt) {
    if (a.fall === 1) {
      a.pos.x += (a.fallDX > 0.3 ? a.fallDX : 0.6) * 3.2 * gdt;
      a.pos.z += a.fallDZ * 1.2 * gdt;
      if (a.pos.x >= SEA_LIP) {
        a.fall = 2;
        a.fallY = groundAt(a.pos.x, a.pos.z);
        if (a.fallY < WATER_Y + 0.5) a.fallY = Math.max(a.groundY, WATER_Y + 0.5);
        a.fallVY = 1.2;
        if (ctx.audio && ctx.audio.play) ctx.audio.play('scream', { at: a.pos, volume: 2, prio: 3 });
      }
    } else if (a.fall === 2) {
      a.pos.x += 1.4 * gdt;
      a.fallVY -= 9.81 * gdt;
      a.fallY += a.fallVY * gdt;
      if (a.fallY <= WATER_Y) {
        a.fallY = WATER_Y;
        a.fall = 3;
        tmpV.set(a.pos.x, WATER_Y, a.pos.z);
        if (ctx.fx && ctx.fx.splash) ctx.fx.splash(tmpV);
        if (ctx.audio && ctx.audio.play) ctx.audio.play('splash', { at: tmpV, volume: 2, prio: 3 });
        if (a.sinkT < 0) a.sinkT = 0;
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Targets: player slots, ally spots, loose ranks

  function releaseAll(a) {
    if (a.slot) { if (a.slot.occupant === a) a.slot.occupant = null; a.slot = null; }
    if (a.allySpot) { if (a.allySpot.occupant === a) a.allySpot.occupant = null; a.allySpot = null; }
    if (a.wait) { if (a.wait.occupant === a) a.wait.occupant = null; a.wait = null; }
    if (!a.trainer) a.targetIsPlayer = false;
    a.foe = null;
  }

  function engagedCount() {
    let n = 0;
    for (let i = 0; i < slots.length; i++) if (slots[i].occupant) n++;
    return n;
  }

  function updateSlots() {
    for (let i = 0; i < slots.length; i++) {
      const s = slots[i];
      const x = F.px - s.sin * SLOT_DIST;
      s.x = x < X_MIN + 0.2 ? X_MIN + 0.2 : x > X_MAX - 0.2 ? X_MAX - 0.2 : x;
      s.z = F.pz - s.cos * SLOT_DIST;
      // Wide and rear slots exist only on Day 3; any slot is blocked while a Greek stands on it.
      s.blocked = s.kind !== 'front' && day !== 3;
      if (!s.blocked) {
        for (let j = 0; j < actors.length; j++) {
          const o = actors[j];
          if (!o.alive || o.team !== 'greek' || o.role === 'helot') continue;
          const ex = o.pos.x - s.x, ez = o.pos.z - s.z;
          if (ex * ex + ez * ez < 0.72 * 0.72) { s.blocked = true; break; }
        }
      }
      const o = s.occupant;
      if (o && (!o.alive || !playerAlive || (s.blocked && s.kind !== 'front'))) {
        s.occupant = null;
        if (o.slot === s) o.slot = null;
        if (o.alive && playerAlive) { o.targetIsPlayer = false; o.thinkT = 0; }
      }
    }
  }

  function claimPlayerSlot(a) {
    if (!playerAlive || a.role !== 'melee' || a.trainer) return false;
    if (engagedCount() >= dir.engaged) return false;
    const behind = a.pos.z > F.pz + 0.8;
    let best = null;
    let bestD = Infinity;
    for (let i = 0; i < slots.length; i++) {
      const s = slots[i];
      if (s.occupant || s.blocked || (s.kind === 'rear') !== behind) continue;
      const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
      // Prefer the centre a little, so a lone attacker comes straight on.
      const d = ex * ex + ez * ez - (s.bearing === 0 ? 0.5 : 0);
      if (d < bestD) { bestD = d; best = s; }
    }
    if (!best) return false;
    releaseAll(a);
    best.occupant = a;
    a.slot = best;
    a.targetIsPlayer = true;
    a.engagedAt = -1;
    return true;
  }

  function allySpotPos(spot) {
    const al = spot.ally;
    let bias = 0;
    const rel = al.pos.x - F.px;
    // Keep clear of the player's flank slots.
    if (Math.abs(rel) < 2.3 && Math.abs(al.pos.z - F.pz) < 2.5) bias = rel > 0 ? 0.4 : -0.4;
    let x;
    if (spot.kind === 0) { x = al.homeX + bias; spot.z = al.homeZ - 1.6; }
    else if (spot.kind === 1) { x = al.homeX + bias + (rel >= 0 ? 0.55 : -0.55); spot.z = al.homeZ - 2.15; }
    else { x = al.homeX + bias; spot.z = al.homeZ + 1.6; }
    spot.x = x < X_MIN + 0.2 ? X_MIN + 0.2 : x > X_MAX - 0.2 ? X_MAX - 0.2 : x;
  }

  function claimAllySpot(a) {
    if (a.role !== 'melee' || a.trainer) return false;
    const behind = a.pos.z > 1.2;
    let best = null;
    let bestD = Infinity;
    for (let i = 0; i < actors.length; i++) {
      const al = actors[i];
      if (!al.alive || al.role !== 'ally' || al.state === 'advance' || !al.spots) continue;
      for (let k = 0; k < al.spots.length; k++) {
        const s = al.spots[k];
        if (s.occupant || (s.kind !== 0 && day !== 3) || (s.kind === 2) !== behind) continue;
        allySpotPos(s);
        const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
        const d = ex * ex + ez * ez + k * 4;
        if (d < bestD) { bestD = d; best = s; }
      }
    }
    if (!best) return false;
    releaseAll(a);
    best.occupant = a;
    a.allySpot = best;
    a.foe = best.ally;
    a.engagedAt = -1;
    return true;
  }

  function claimWait(a) {
    const rear = a.pos.z > 1.2;
    let best = null;
    let bestD = Infinity;
    for (let i = 0; i < waitSpots.length; i++) {
      const s = waitSpots[i];
      if (s.occupant || s.rear !== rear) continue;
      const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
      const d = ex * ex + ez * ez;
      if (d < bestD) { bestD = d; best = s; }
    }
    if (!best) return false;
    best.occupant = a;
    a.wait = best;
    return true;
  }

  /** Engage the player if a slot is free, else an ally, else wait in the loose ranks. */
  function think(a) {
    if (a.slot) return;
    const duel = a.allySpot && a.foe && a.foe.alive;
    if (duel && a.state === 'engage') return;
    if (claimPlayerSlot(a)) { a.state = 'advance'; return; }
    if (duel) return;
    if (claimAllySpot(a)) { a.state = 'advance'; return; }
    if (!a.wait) {
      releaseAll(a);
      claimWait(a);
      a.state = 'advance';
    }
  }

  // ---------------------------------------------------------------------------------------
  // Director

  function configureDirector(gdt) {
    const d = DIRECTOR[day] || DIRECTOR[1];
    let rate = d.rate;
    if (d.ratePerWave) {
      const w = ctx.waves && ctx.waves.dayWave > 0 ? ctx.waves.dayWave : 0;
      rate = Math.min(d.rateMax || 1, rate + d.ratePerWave * w);
    }
    const p = ctx.player;
    if (day < 3 && p && p.hp <= 25) rate *= 0.7; // mercy
    dir.rate = rate;
    dir.gap = d.gap;
    dir.windups = d.windups;
    dir.engaged = d.engaged;
    const ed = d.engageDelay;
    dir.engageMin = ed ? ed[0] : ENGAGE_DELAY_MIN;
    dir.engageSpan = ed ? ed[1] : ENGAGE_DELAY_SPAN;
    dir.cap = d.windups > 1 ? d.windups : 1;
    dir.tokens = Math.min(dir.cap, dir.tokens + rate * gdt);
  }

  function motionMode() {
    return !!(ctx.settings && ctx.settings.control === 'motion');
  }

  function cameraOverridden() {
    return !!(ctx.fx && ctx.fx.cameraOverridden);
  }

  /** §11: timeToStrike = windup × 1.15 in motion mode × 0.9 on Day 3 (+ hiltRearGrace). */
  function playerTimeToStrike(a) {
    const base = a.trainer ? 1.2 : a.stats.windup || 0.85;
    return base * (motionMode() ? 1.15 : 1) * (day === 3 && !a.trainer ? 0.9 : 1) + hiltRearGrace(a);
  }

  /**
   * docs-hilt-grip.md §C.6: a hilt player turns only through the lock-on, and its threat turn from
   * straight behind into the block arc takes ~0.7 s. So a Persian's player-targeted wind-up from
   * beyond LOCK.threatAngle of the view gets HILT_REAR_GRACE more, judged once at its start. Never
   * for the trainer, touch, the window grip or headless runs without ctx.input.grip.
   */
  function hiltRearGrace(a) {
    const inp = ctx.input;
    const p = ctx.player;
    if (!inp || (inp.grip !== 'hilt' && inp.lockOn !== true) || a.trainer || a.team !== 'persian' || !p || !p.pos) return 0;
    const rel = angleDiff(Number.isFinite(p.yaw) ? p.yaw : 0, yawTo(a.pos.x - p.pos.x, a.pos.z - p.pos.z));
    return Math.abs(rel) > LOCK.threatAngle ? HILT_REAR_GRACE : 0;
  }

  /** Grants the candidate a player-targeted wind-up if the director allows it now. */
  function tryDirector(c) {
    if (cameraOverridden() || !playerAlive || dir.tokens < 1) return;
    const base = c.stats.windup || 0.85;
    const T = base * (motionMode() ? 1.15 : 1) * (day === 3 ? 0.9 : 1) + hiltRearGrace(c);
    const strike = F.gnow + T;
    if (Math.abs(strike - dir.lastStrikeAt) < dir.gap) return;
    let active = 0;
    for (let i = 0; i < actors.length; i++) {
      const o = actors[i];
      if (!o.alive || o.state !== 'windup' || !o.targetIsPlayer || o.strikeAt < 0) continue;
      active++;
      if (Math.abs(strike - o.strikeAt) < dir.gap) return;
    }
    if (active < dir.windups) startWindup(c, T, true);
  }

  function startWindup(a, T, toPlayer) {
    const clip = a.attackClip;
    const now = ctx.time.gnow;
    a.state = 'windup';
    a.timeToStrike = T;
    a.strikeAt = now + T;
    a.stateT = 0;
    // The clip's strike frame lands exactly at timeToStrike.
    a.playAttack.duration = T / clipStrikeAt(clip);
    play(a, clip, a.playAttack);
    setGuard(a, false);
    const iv = a.stats.interval || STATS_FALLBACK.interval;
    const interval = a.trainer ? 3 : iv[0] + ctx.rng.next() * (iv[1] - iv[0]);
    a.nextAttackAt = now + Math.max(T + 0.7, interval);
    if (toPlayer && !a.trainer) {
      dir.tokens -= 1;
      dir.lastWindupAt = now;
    }
    if (a.team === 'persian') events.emit('enemyWindup', { actor: a, timeToStrike: T, targetIsPlayer: toPlayer });
  }

  /**
   * Anti-turtle: a shield held without a break for TURTLE_HOLD s turns blows aimed at the
   * player into heavy ones (guard break). Time under a volley doesn't count: the hold clock
   * restarts when the volley's cover ends.
   */
  function playerTurtling() {
    const inp = ctx.input;
    const p = ctx.player;
    if (!inp || !inp.shieldHeld) return false;
    if (p && Number.isFinite(p.shieldRaise) && p.shieldRaise < 0.7) return false;
    const now = ctx.time.gnow;
    if (now < dir.volleyUntil) return false;
    const pressed = Number.isFinite(inp.shieldPressedAt) ? inp.shieldPressedAt : now;
    const since = pressed > dir.volleyUntil ? pressed : dir.volleyUntil;
    return now - since >= TURTLE_HOLD - 1e-6;
  }

  events.on('volley', (e) => {
    if (!e || e.phase !== 'warning') return;
    const until = ctx.time.gnow + VOLLEY_COVER + (e.duration > 0 ? e.duration : 1);
    if (until > dir.volleyUntil) dir.volleyUntil = until;
  });

  /** onStrike of the attack clip: the blow resolves through combat. */
  function resolveStrike(a) {
    if (!a.alive || a.state !== 'windup') return;
    const combat = ctx.combat;
    a.strikeAt = -1;
    if (a.role === 'archer') {
      if (a.foe && a.foe.alive && combat && combat.shootArrow) combat.shootArrow(a, a.foe);
    } else {
      const atk = a.attack;
      atk.kind = a.attackClip === 'axe' ? 'axe' : 'melee';
      atk.damage = a.trainer ? 0 : (a.stats.damage || 0) * (a.role === 'ally' ? ALLY_DAMAGE_MUL : 1);
      atk.reach = a.stats.reach || 1.9;
      atk.halfAngle = ATTACK_HALF[a.attackClip] || 0.45;
      atk.heavy = !!a.stats.heavy || (a.targetIsPlayer && !a.trainer && playerTurtling());
      if (a.targetIsPlayer) {
        dir.lastStrikeAt = ctx.time.gnow;
        atk.target = 'player';
        if (playerAlive && combat && combat.actorStrike) combat.actorStrike(a, atk);
      } else if (a.foe && a.foe.alive) {
        atk.target = a.foe;
        if (combat && combat.actorStrike) combat.actorStrike(a, atk);
      }
      atk.target = null;
    }
    // A perfect block staggers the attacker inside actorStrike; otherwise recover.
    if (a.alive && a.state === 'windup') {
      a.state = 'recover';
      a.stateT = Math.max(0.25, a.playAttack.duration * (1 - clipStrikeAt(a.attackClip)) * 0.8);
    }
  }

  // After the spear cam, blows already on their way wait an extra 0.5 s (re-telegraphed).
  events.on('spearCam', (e) => {
    if (!e || e.phase !== 'end') return;
    const now = ctx.time.gnow;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive || a.state !== 'windup' || !a.targetIsPlayer || a.strikeAt < 0) continue;
      const remaining = Math.max(0, a.strikeAt - now) + SPEARCAM_GRACE;
      a.timeToStrike = remaining;
      a.strikeAt = now + remaining;
      a.playAttack.duration = remaining / clipStrikeAt(a.attackClip);
      play(a, a.attackClip, a.playAttack);
      a.nextAttackAt = Math.max(a.nextAttackAt, a.strikeAt + 0.7);
      events.emit('enemyWindup', { actor: a, timeToStrike: remaining, targetIsPlayer: true });
    }
  });

  // ---------------------------------------------------------------------------------------
  // Brains. Goals are written straight into a.gx/a.gz; goal() sets the rest.

  function goal(a, run, r) {
    a.hasGoal = true;
    a.goalRun = run;
    a.goalR = r;
  }

  function engageArrived(a) {
    a.state = 'engage';
    if (a.engagedAt < 0) {
      const now = ctx.time.gnow;
      a.engagedAt = now;
      a.readyAt = now + dir.engageMin + ctx.rng.next() * dir.engageSpan;
      if (a.nextAttackAt < a.readyAt) a.nextAttackAt = a.readyAt;
    }
  }

  /** Stagger and knockdown countdown, shared by every fighting role. Returns true while disabled. */
  function disabledTick(a, gdt) {
    if (a.state !== 'stagger' && a.state !== 'knockdown') return false;
    a.hasGoal = false;
    a.staggerT -= gdt;
    a.stateT -= gdt;
    if (a.state === 'knockdown' && !a.getup && a.stateT <= GETUP_T) {
      a.getup = true;
      playReaction(a, 'getup', 0);
    }
    if (a.stateT <= 0) {
      a.staggerT = 0;
      a.thinkT = 0;
      if (a.role === 'melee') a.state = a.slot || a.allySpot ? 'engage' : 'advance';
      else a.state = 'hold';
    }
    return true;
  }

  /** Wind-up and recovery, shared by every fighting role. Returns true while attacking. */
  function attackTick(a, gdt) {
    if (a.state === 'windup') {
      if (a.strikeAt > 0 && F.gnow >= a.strikeAt + (a.manualStrike ? 0 : STRIKE_FALLBACK)) resolveStrike(a);
      if (a.state === 'windup' && !a.targetIsPlayer && !(a.foe && a.foe.alive)) {
        // The target fell to someone else: lower the weapon.
        a.strikeAt = -1;
        a.state = 'recover';
        a.stateT = 0.3;
        playReaction(a, 'block_hit', 0.3);
      }
      faceTarget(a);
      closeIn(a);
      return true;
    }
    if (a.state === 'recover') {
      a.stateT -= gdt;
      a.hasGoal = false;
      faceTarget(a);
      if (a.stateT <= 0) {
        if (a.role === 'melee') a.state = a.targetIsPlayer || a.foe ? 'engage' : 'advance';
        else a.state = 'hold';
      }
      return true;
    }
    return false;
  }

  function faceTarget(a) {
    if (a.targetIsPlayer) { a.faceTo = Math.atan2(F.px - a.pos.x, F.pz - a.pos.z); a.hasFace = true; }
    else if (a.foe && a.foe.alive) { a.faceTo = Math.atan2(a.foe.pos.x - a.pos.x, a.foe.pos.z - a.pos.z); a.hasFace = true; }
  }

  /** During a wind-up, shuffle in if the target drifted out of reach. */
  function closeIn(a) {
    a.hasGoal = false;
    if (a.role === 'archer') return;
    let ex, ez;
    if (a.targetIsPlayer) { ex = F.px - a.pos.x; ez = F.pz - a.pos.z; }
    else if (a.foe && a.foe.alive) { ex = a.foe.pos.x - a.pos.x; ez = a.foe.pos.z - a.pos.z; }
    else return;
    const reach = a.stats.reach || 1.9;
    const d = Math.sqrt(ex * ex + ez * ez);
    if (d > reach - 0.15 && d > 1.2) {
      const k = (d - (reach - 0.3)) / d;
      a.gx = a.pos.x + ex * k;
      a.gz = a.pos.z + ez * k;
      goal(a, false, 0.05);
      a.speedCap = 0.7;
    }
  }

  function brainEnemy(a, gdt) {
    if (a.state === 'idle') {
      a.hasGoal = false;
      a.delay -= gdt;
      if (a.delay <= 0) a.state = 'advance';
      return;
    }
    if (disabledTick(a, gdt) || attackTick(a, gdt)) return;

    // The player has fallen: those who fought him raise their weapons.
    if (!playerAlive && (a.targetIsPlayer || a.cheered)) {
      releaseAll(a);
      a.state = 'hold';
      a.hasGoal = false;
      if (!a.cheered) { a.cheered = true; playReaction(a, 'cheer', 0); }
      return;
    }

    if (a.trainer) {
      a.gx = a.homeX;
      a.gz = a.homeZ;
      goal(a, false, 0.15);
      a.faceTo = Math.atan2(F.px - a.pos.x, F.pz - a.pos.z);
      a.hasFace = true;
      a.state = 'engage';
      setGuard(a, true);
      if (playerAlive && F.gnow >= a.nextAttackAt && !cameraOverridden()) startWindup(a, playerTimeToStrike(a), true);
      return;
    }

    if (a.gate < 2) { routeThroughGap(a); return; }

    a.thinkT -= gdt;
    if (a.thinkT <= 0 || (a.foe && !a.foe.alive) || (!a.slot && !a.allySpot && !a.wait)) {
      a.thinkT = 0.25 + ctx.rng.next() * 0.15;
      if (a.foe && !a.foe.alive) releaseAll(a);
      think(a);
    }

    if (a.slot) {
      const s = a.slot;
      const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
      const d = Math.sqrt(ex * ex + ez * ez);
      a.gx = s.x;
      a.gz = s.z;
      goal(a, d > 6, 0.08);
      if (a.state === 'advance' && d < 0.35) engageArrived(a);
      else if (a.state === 'engage' && d > 1.2) a.state = 'advance';
      a.hasFace = a.state === 'engage' || d < 2.5;
      if (a.hasFace) a.faceTo = Math.atan2(F.px - a.pos.x, F.pz - a.pos.z);
      setGuard(a, d < 4);
      if (a.state === 'engage') {
        const px = F.px - a.pos.x, pz = F.pz - a.pos.z;
        const reach = (a.stats.reach || 1.9) + 0.25;
        const now = F.gnow;
        if (now >= a.readyAt && now >= a.nextAttackAt && px * px + pz * pz <= reach * reach) {
          // The director picks the one who has waited longest.
          if (!candidate || a.nextAttackAt < candidate.nextAttackAt) candidate = a;
        }
      }
      return;
    }

    if (a.allySpot) {
      const s = a.allySpot;
      allySpotPos(s);
      const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
      const d = Math.sqrt(ex * ex + ez * ez);
      a.gx = s.x;
      a.gz = s.z;
      goal(a, d > 6, 0.08);
      const f = a.foe;
      if (a.state === 'advance' && d < 0.4) engageArrived(a);
      else if (a.state === 'engage' && d > 1.3) a.state = 'advance';
      a.hasFace = !!f && (a.state === 'engage' || d < 2.5);
      if (a.hasFace) a.faceTo = Math.atan2(f.pos.x - a.pos.x, f.pos.z - a.pos.z);
      setGuard(a, d < 4);
      if (a.state === 'engage' && f) {
        const fx = f.pos.x - a.pos.x, fz = f.pos.z - a.pos.z;
        const reach = (a.stats.reach || 1.9) + 0.25;
        if (F.gnow >= a.readyAt && F.gnow >= a.nextAttackAt && fx * fx + fz * fz <= reach * reach) {
          startWindup(a, a.stats.windup || 0.85, false);
        }
      }
      return;
    }

    if (a.wait) {
      const s = a.wait;
      const ex = s.x - a.pos.x, ez = s.z - a.pos.z;
      const d2 = ex * ex + ez * ez;
      a.gx = s.x;
      a.gz = s.z;
      goal(a, d2 > 36, 0.12);
      if (d2 < 0.45 * 0.45) {
        a.state = 'hold';
        a.faceTo = Math.atan2(F.px - a.pos.x, F.pz - a.pos.z);
        a.hasFace = true;
      } else {
        a.state = 'advance';
        a.hasFace = false;
      }
      setGuard(a, d2 < 9);
      return;
    }

    // Every rank is full: close up behind the front.
    a.gx = a.pos.x < -6 ? -6 : a.pos.x > 7 ? 7 : a.pos.x;
    a.gz = a.pos.z > 1.2 ? 7.2 : -7.4;
    goal(a, true, 0.6);
    a.state = 'advance';
    a.hasFace = false;
  }

  function routeThroughGap(a) {
    a.state = 'advance';
    a.hasFace = false;
    setGuard(a, false);
    a.gx = a.lane;
    if (a.gate === 0) {
      a.gz = WALL_Z + WALL_HALF + 0.9;
      goal(a, true, 0.35);
      if (a.pos.z < WALL_Z + WALL_HALF + 1.4 && Math.abs(a.pos.x - a.lane) < 0.6) a.gate = 1;
    } else {
      a.gz = WALL_Z - WALL_HALF - 1.4;
      goal(a, true, 0.35);
      if (a.pos.z < WALL_Z - WALL_HALF - 0.9) { a.gate = 2; a.thinkT = 0; }
    }
  }

  function brainArcher(a, gdt) {
    if (a.state === 'retreat') {
      a.gx = a.pos.x < -6 ? -6 : a.pos.x > 7 ? 7 : a.pos.x;
      a.gz = -72;
      goal(a, true, 0.5);
      a.hasFace = false;
      setGuard(a, false);
      if (a.pos.z < -69) { a.removeMe = true; a.alive = false; }
      return;
    }
    if (a.state === 'idle') {
      a.hasGoal = false;
      a.delay -= gdt;
      if (a.delay <= 0) a.state = 'advance';
      return;
    }
    if (disabledTick(a, gdt) || attackTick(a, gdt)) return;
    a.gx = a.homeX;
    a.gz = a.homeZ;
    if (a.state === 'advance') {
      goal(a, true, 0.2);
      a.hasFace = false;
      const ex = a.homeX - a.pos.x, ez = a.homeZ - a.pos.z;
      if (ex * ex + ez * ez < 0.35 * 0.35) a.state = 'hold';
      return;
    }
    // Hold the firing position and shoot at the Greek line.
    goal(a, false, 0.3);
    a.faceTo = 0;
    a.hasFace = true;
    if (F.gnow >= a.nextAttackAt) {
      const target = pickAllyTarget();
      if (target) {
        a.foe = target;
        a.faceTo = Math.atan2(target.pos.x - a.pos.x, target.pos.z - a.pos.z);
        startWindup(a, a.stats.windup || 1.2, false);
      } else {
        a.nextAttackAt = F.gnow + 1;
      }
    }
  }

  function pickAllyTarget() {
    let n = 0;
    for (let i = 0; i < actors.length; i++) if (actors[i].alive && actors[i].role === 'ally') n++;
    if (n === 0) return null;
    let k = Math.floor(ctx.rng.next() * n);
    for (let i = 0; i < actors.length; i++) {
      const o = actors[i];
      if (o.alive && o.role === 'ally' && k-- === 0) return o;
    }
    return null;
  }

  function brainAlly(a, gdt) {
    if (disabledTick(a, gdt) || attackTick(a, gdt)) return;
    a.gx = a.homeX;
    a.gz = a.homeZ;
    if (a.state === 'advance') {
      goal(a, false, 0.12);
      a.hasFace = false;
      setGuard(a, false);
      const ex = a.homeX - a.pos.x, ez = a.homeZ - a.pos.z;
      if (ex * ex + ez * ez < 0.3 * 0.3) { a.state = 'hold'; a.faceTo = PI; a.hasFace = true; }
      return;
    }
    // Hold the line.
    goal(a, false, 0.12);
    a.thinkT -= gdt;
    if (a.thinkT <= 0 || (a.foe && !a.foe.alive)) {
      a.thinkT = 0.25 + ctx.rng.next() * 0.1;
      a.foe = pickAllyFoe(a);
    }
    const f = a.foe;
    a.hasFace = true;
    if (f) {
      a.state = 'engage';
      const fx = f.pos.x - a.pos.x, fz = f.pos.z - a.pos.z;
      a.faceTo = Math.atan2(fx, fz);
      setGuard(a, true);
      if (a.engagedAt < 0) {
        a.engagedAt = F.gnow;
        const at = F.gnow + 0.5 + ctx.rng.next() * 0.8;
        if (a.nextAttackAt < at) a.nextAttackAt = at;
      }
      const reach = (a.stats.reach || 2.6) + 0.2;
      if (F.gnow >= a.nextAttackAt && fx * fx + fz * fz <= reach * reach) startWindup(a, a.stats.windup || 0.6, false);
    } else {
      a.state = 'hold';
      a.engagedAt = -1;
      const threat = nearestPersian(a, 5);
      setGuard(a, !!threat);
      // Keep the file facing front; glance toward a threat only within ±ALLY_LOOK.
      let f = PI;
      if (threat) {
        const t = Math.atan2(threat.pos.x - a.pos.x, threat.pos.z - a.pos.z);
        let rel = t - PI;
        if (rel < -PI) rel += TAU;
        if (rel > ALLY_LOOK) rel = ALLY_LOOK;
        else if (rel < -ALLY_LOOK) rel = -ALLY_LOOK;
        f = PI + rel;
        if (day === 3 && threat.pos.z > a.pos.z + 0.5) f = t; // Day 3: turn to face the rear
      }
      a.faceTo = f > PI ? f - TAU : f;
    }
  }

  /**
   * The enemy attacking this ally, else one close in front that isn't on the player, or one on
   * the player that the player hasn't struck for ALLY_RELIEF s (so a stalled duel resolves).
   */
  function pickAllyFoe(a) {
    let best = null;
    let bestD = Infinity;
    for (let i = 0; i < actors.length; i++) {
      const o = actors[i];
      if (!o.alive || o.team !== 'persian' || o.role !== 'melee' || o.trainer) continue;
      const ex = o.pos.x - a.pos.x, ez = o.pos.z - a.pos.z;
      const d = ex * ex + ez * ez;
      if (o.foe === a) {
        if (d - 100 < bestD) { bestD = d - 100; best = o; }
        continue;
      }
      if (d > 2.7 * 2.7 || (ez > 0.4 && day !== 3)) continue;
      if (o.targetIsPlayer) {
        if (o.engagedAt < 0) continue;
        const since = o.playerHitAt > o.engagedAt ? o.playerHitAt : o.engagedAt;
        if (F.gnow - since < ALLY_RELIEF) continue;
      }
      if (d < bestD) { bestD = d; best = o; }
    }
    return best;
  }

  function nearestPersian(a, maxD) {
    let best = null;
    let bestD = maxD * maxD;
    for (let i = 0; i < actors.length; i++) {
      const o = actors[i];
      if (!o.alive || o.team !== 'persian' || o.role !== 'melee') continue;
      const ex = o.pos.x - a.pos.x, ez = o.pos.z - a.pos.z;
      const d = ex * ex + ez * ez;
      if (d < bestD) { bestD = d; best = o; }
    }
    return best;
  }

  function brainHelot(a, gdt) {
    const ph = a.helotPhase;
    a.hasFace = false;
    if (ph === 0) {
      // Run up on the player's right with the spears.
      const tx = F.px + 0.6;
      a.gx = tx < X_MIN + 0.3 ? X_MIN + 0.3 : tx > X_MAX - 0.3 ? X_MAX - 0.3 : tx;
      a.gz = F.pz + 0.75;
      goal(a, true, 0.2);
      a.state = 'advance';
      const ex = a.gx - a.pos.x, ez = a.gz - a.pos.z;
      if (ex * ex + ez * ez < 0.35 * 0.35 || !playerAlive) { a.helotPhase = 1; a.stateT = playerAlive ? 1.2 : 0; }
    } else if (ph === 1) {
      a.hasGoal = false;
      a.state = 'hold';
      a.faceTo = Math.atan2(F.px - a.pos.x, F.pz - a.pos.z);
      a.hasFace = true;
      a.stateT -= gdt;
      if (a.stateT <= 0) { a.helotPhase = 2; a.state = 'retreat'; }
    } else if (ph === 2) {
      a.gx = 0.6;
      a.gz = 10.3;
      goal(a, true, 0.4);
      if (a.pos.z > 9.9) a.helotPhase = 3;
    } else {
      // Out through the gap and out of sight behind the wall.
      a.gx = 0.6;
      a.gz = 16;
      goal(a, true, 0.4);
      if (a.pos.z > 15.2) { a.removeMe = true; a.alive = false; }
    }
  }

  function brainDummy(a) {
    a.hasGoal = false;
    a.hasFace = false;
  }

  // ---------------------------------------------------------------------------------------
  // Movement

  function invMass(a) {
    if (a.role === 'dummy') return 0;
    if (a.role === 'ally') return a.state === 'advance' ? 1 : 0.3;
    if (a.state === 'windup') return 0.45;
    return 1;
  }

  function steer(a, gdt) {
    a.prevX = a.pos.x;
    a.prevZ = a.pos.z;
    if (a.role === 'dummy') return;
    let dvx = 0, dvz = 0;
    const disabled = a.state === 'stagger' || a.state === 'knockdown';
    if (a.hasGoal && !disabled) {
      const ex = a.gx - a.pos.x, ez = a.gz - a.pos.z;
      const d = Math.sqrt(ex * ex + ez * ez);
      if (d > a.goalR) {
        const st = a.stats;
        let spd = a.role === 'helot' ? st.walk || 3.5 : (a.goalRun ? st.run || 2.4 : st.walk || 1.4) * a.speedMul;
        if (a.state === 'windup' && spd > (a.speedCap || 0.6)) spd = a.speedCap || 0.6;
        if (d < 0.9) spd *= d / 0.9; // arrive
        dvx = (ex / d) * spd;
        dvz = (ez / d) * spd;
      }
    }
    // Personal space: steer away from close neighbours.
    let sx = 0, sz = 0;
    for (let j = 0; j < actors.length; j++) {
      const o = actors[j];
      if (o === a || !o.alive) continue;
      const ex = a.pos.x - o.pos.x, ez = a.pos.z - o.pos.z;
      const d2 = ex * ex + ez * ez;
      if (d2 > SOFT_SEP * SOFT_SEP || d2 < 1e-8) continue;
      const d = Math.sqrt(d2);
      const k = (SOFT_SEP - d) / (SOFT_SEP * d);
      sx += ex * k;
      sz += ez * k;
    }
    if (hasPlayer) {
      const ex = a.pos.x - F.px, ez = a.pos.z - F.pz;
      const d2 = ex * ex + ez * ez;
      if (d2 < SOFT_SEP * SOFT_SEP && d2 > 1e-8) {
        const d = Math.sqrt(d2);
        const k = (1.5 * (SOFT_SEP - d)) / (SOFT_SEP * d);
        sx += ex * k;
        sz += ez * k;
      }
    }
    const st = a.state;
    const gain = st === 'engage' || st === 'hold' || st === 'windup' || st === 'recover' ? 0.8 : 1.6;
    dvx += sx * gain;
    dvz += sz * gain;
    if (disabled) { dvx = 0; dvz = 0; }
    const k = 1 - Math.exp(-10 * gdt);
    a.vx += (dvx - a.vx) * k;
    a.vz += (dvz - a.vz) * k;
    a.pos.x += a.vx * gdt;
    a.pos.z += a.vz * gdt;
    if (a.kbx !== 0 || a.kbz !== 0) integrateKnock(a, gdt);
  }

  /** Hard constraint: living capsules never overlap; the player is an immovable obstacle. */
  function separate() {
    const n = actors.length;
    for (let iter = 0; iter < 3; iter++) {
      for (let i = 0; i < n; i++) {
        const a = actors[i];
        if (!a.alive) continue;
        const wa = invMass(a);
        for (let j = i + 1; j < n; j++) {
          const b = actors[j];
          if (!b.alive) continue;
          const wb = invMass(b);
          if (wa + wb === 0) continue;
          let ex = b.pos.x - a.pos.x, ez = b.pos.z - a.pos.z;
          const d2 = ex * ex + ez * ez;
          if (d2 >= HARD_SEP * HARD_SEP) continue;
          let d = Math.sqrt(d2);
          if (d < 1e-6) { ex = a.id < b.id ? 0.01 : -0.01; ez = 0.005; d = Math.sqrt(ex * ex + ez * ez); }
          const push = (HARD_SEP - d) / (d * (wa + wb));
          a.pos.x -= ex * push * wa;
          a.pos.z -= ez * push * wa;
          b.pos.x += ex * push * wb;
          b.pos.z += ez * push * wb;
        }
        if (hasPlayer && wa > 0) {
          let ex = a.pos.x - F.px, ez = a.pos.z - F.pz;
          const d2 = ex * ex + ez * ez;
          if (d2 < HARD_SEP * HARD_SEP) {
            let d = Math.sqrt(d2);
            if (d < 1e-6) { ex = 0; ez = -1; d = 1; }
            const push = (HARD_SEP - d) / d;
            a.pos.x += ex * push;
            a.pos.z += ez * push;
          }
        }
      }
    }
  }

  /** Cliff, sea edge and the Phocian Wall outside its gap. */
  function worldConstraints(a) {
    const p = a.pos;
    if (p.x < X_MIN) p.x = X_MIN;
    if (p.x > X_MAX) p.x = X_MAX;
    const wz = p.z - WALL_Z;
    if (Math.abs(wz) < WALL_HALF + RADIUS && Math.abs(p.x) > GAP_X) {
      if (Math.abs(p.x) < GAP_X + 0.6) p.x = p.x > 0 ? GAP_X : -GAP_X;
      else p.z = wz >= 0 ? WALL_Z + WALL_HALF + RADIUS : WALL_Z - WALL_HALF - RADIUS;
    }
  }

  function turn(a, gdt) {
    if (a.state === 'stagger' || a.state === 'knockdown' || a.role === 'dummy') return;
    let target;
    if (a.hasFace) target = a.faceTo;
    else if (a.speed >= 0.35) target = Math.atan2(a.vx, a.vz); // face where we walk
    else return;
    let d = (target - a.facing + PI) % TAU;
    if (d < 0) d += TAU;
    d -= PI;
    const m = (a.state === 'windup' ? 3.2 : 7) * gdt;
    let f = a.facing + (d > m ? m : d < -m ? -m : d);
    if (f > PI) f -= TAU;
    else if (f <= -PI) f += TAU;
    a.facing = f;
  }

  /** guard (0..1) eases toward the stance; blockChance follows it, so a wind-up leaves the body open. */
  function updateGuard(a, gdt) {
    const st = a.state;
    let g = 0;
    if (st === 'engage' || st === 'hold' || st === 'advance') g = a.guardOn ? 1 : 0.4;
    else if (st === 'windup') g = 0.35;
    else if (st === 'recover') g = 0.5;
    const k = gdt * 6;
    a.guard += (g - a.guard) * (k < 1 ? k : 1);
    if (st === 'stagger' || st === 'knockdown') a.blockChance = 0;
    else {
      const gg = a.guard * 1.15;
      a.blockChance = a.baseBlock * (gg < 1 ? gg : 1);
    }
  }

  // Shadows: only the nearest 12 actors within 20 m of the camera cast them (6 on low), §15:
  // each caster is two shadow draws, the biggest share of the shadow pass in a full fight.
  const nearest = new Array(SHADOW_NEAREST).fill(null);
  const nearestD = new Float64Array(SHADOW_NEAREST);
  function updateShadows() {
    const cam = ctx.camera;
    if (!cam) return;
    const cx = cam.position.x, cz = cam.position.z;
    const K = ctx.quality === 'low' ? SHADOW_NEAREST_LOW : SHADOW_NEAREST;
    for (let k = 0; k < K; k++) { nearest[k] = null; nearestD[k] = Infinity; }
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      const m = a.visual.mesh;
      if (!m) continue;
      m.castShadow = false;
      const ex = a.pos.x - cx, ez = a.pos.z - cz;
      const d2 = ex * ex + ez * ez;
      if (!a.alive || d2 >= SHADOW_DIST2 || d2 >= nearestD[K - 1]) continue;
      let k = K - 1;
      while (k > 0 && nearestD[k - 1] > d2) { nearestD[k] = nearestD[k - 1]; nearest[k] = nearest[k - 1]; k--; }
      nearestD[k] = d2;
      nearest[k] = a;
    }
    for (let k = 0; k < K; k++) if (nearest[k]) nearest[k].visual.mesh.castShadow = true;
  }

  // ---------------------------------------------------------------------------------------
  // Update

  function readWorldState() {
    F.gnow = ctx.time.gnow;
    const p = ctx.player;
    hasPlayer = !!(p && p.pos);
    if (hasPlayer) {
      F.px = p.pos.x;
      F.pz = p.pos.z;
      playerAlive = p.alive !== false && ctx.mode !== 'title';
    } else {
      F.px = 0;
      F.pz = 0;
      playerAlive = false;
    }
    const w = ctx.waves;
    const d = w && w.day ? w.day : 1;
    day = d >= 3 ? 3 : d <= 1 ? 1 : 2;
  }

  /** One frame of AI (§10 step 3, after player.update): brains, director, movement, visuals. */
  function update(gdt) {
    sweep();
    if (!(gdt > 0)) return;
    readWorldState();
    configureDirector(gdt);
    updateSlots();

    candidate = null;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive) continue;
      a.speedCap = 0;
      if (a.role === 'melee') brainEnemy(a, gdt);
      else if (a.role === 'archer') brainArcher(a, gdt);
      else if (a.role === 'ally') brainAlly(a, gdt);
      else if (a.role === 'helot') brainHelot(a, gdt);
      else brainDummy(a);
    }
    // At most one new player-targeted wind-up per frame.
    if (candidate && candidate.alive && candidate.state === 'engage') tryDirector(candidate);
    candidate = null;

    for (let i = 0; i < actors.length; i++) if (actors[i].alive) steer(actors[i], gdt);
    separate();

    // playerAttackers is rewritten in place: truncating to 0 would drop its backing store.
    let nAtt = 0;
    const ka = gdt * 10 < 1 ? gdt * 10 : 1;
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a.alive) continue;
      worldConstraints(a);
      const mx = a.pos.x - a.prevX, mz = a.pos.z - a.prevZ;
      let moved = Math.sqrt(mx * mx + mz * mz) / gdt;
      if (moved > 6) moved = 6;
      a.speed += (moved - a.speed) * ka;
      turn(a, gdt);
      if (a.role !== 'dummy' && a.role !== 'helot') updateGuard(a, gdt);
      if (a.targetIsPlayer && a.team === 'persian') {
        if (nAtt < attackersOut.length) attackersOut[nAtt] = a;
        else attackersOut.push(a);
        nAtt++;
      }
      place(a);
      const an = a.visual.anim;
      if (a.role !== 'dummy') {
        // Locomotion in 0.05 m/s steps, sent only when the step changes (no per-frame call).
        const loco = a.state === 'stagger' || a.state === 'knockdown' || a.speed < 0.08 ? 0 : Math.round(a.speed * 20) * 0.05;
        if (loco !== a.locoSent) {
          a.locoSent = loco;
          an.setLocomotion(loco);
        }
      }
      an.update(gdt); // may fire onStrike → combat
    }
    if (attackersOut.length !== nAtt) attackersOut.length = nAtt;
    updateCorpses(gdt);
    updateShadows();
  }

  /** Removes every actor, corpse and the helot. */
  function clear() {
    for (let i = 0; i < actors.length; i++) disposeActor(actors[i]);
    for (let i = 0; i < corpses.length; i++) disposeActor(corpses[i]);
    actors.length = 0;
    corpses.length = 0;
    attackersOut.length = 0;
    for (let i = 0; i < slots.length; i++) slots[i].occupant = null;
    for (let i = 0; i < waitSpots.length; i++) waitSpots[i].occupant = null;
    helot = null;
    leonidasFallen = false;
    candidate = null;
    dir.tokens = 1;
    dir.lastStrikeAt = -1e9;
    dir.lastWindupAt = -1e9;
    dir.volleyUntil = -1e9;
  }

  /** Removes the bodies only (a new day: the field was cleared in the night). Leonidas stays. */
  function clearCorpses() {
    let w = 0;
    for (let i = 0; i < corpses.length; i++) {
      const c = corpses[i];
      if (c.keepCorpse) corpses[w++] = c;
      else disposeActor(c);
    }
    corpses.length = w;
  }

  return {
    spawnEnemy,
    spawnAlly,
    ensureAllies,
    spawnDummy,
    spawnHelot,
    killAlly,
    retreatArchers,
    clear,
    clearCorpses,
    update,
    /** Living melee Persians (§11). */
    get enemiesAlive() {
      let n = 0;
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (a.alive && a.team === 'persian' && a.role === 'melee') n++;
      }
      return n;
    },
    get archersAlive() {
      let n = 0;
      for (let i = 0; i < actors.length; i++) if (actors[i].alive && actors[i].role === 'archer') n++;
      return n;
    },
    /** Living archers still on the firing line (not retreating): what feeds a wave's volleys. */
    get archersShooting() {
      let n = 0;
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (a.alive && a.role === 'archer' && a.state !== 'retreat') n++;
      }
      return n;
    },
    get alliesAlive() {
      let n = 0;
      for (let i = 0; i < actors.length; i++) if (actors[i].alive && actors[i].role === 'ally') n++;
      return n;
    },
    /** Persians engaged with the player, as of the last update (a reused array: copy to keep). */
    get playerAttackers() {
      return attackersOut;
    },
    /** Bodies on the field (read-only; tests and debug). */
    get corpses() {
      return corpses;
    },
    /** Director state (read-only; tests and debug). */
    get director() {
      return dir;
    },
    get leonidasFallen() {
      return leonidasFallen;
    },
  };
}
