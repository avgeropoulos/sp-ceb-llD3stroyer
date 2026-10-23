/**
 * Combat (DESIGN.md §7, §11 game/combat.js): resolves player and actor strikes, blocking,
 * thrown spears and aimed arrows (segment-vs-capsule flight), volleys and bleeds.
 * Emits hit, kill, block, spearThrown, spearImpact, arrowHit and volley.
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/BufferGeometryUtils.js';
import { WEAPONS, slashProfile, sectorHit, segmentCapsule, computeDamage, ballisticPitch } from './rules.js';
import { clamp, facingToYaw, angleDiff, yawTo } from '../core/math.js';
import { createArrowStreaks, STREAK_STRIDE } from '../gfx/fx/streaks.js';

const G = 9.81;
const SEA_X = 8.6;
const WATER_Y = -2.95;
const PLAYER_RADIUS = 0.32;
const PLAYER_HEIGHT = 1.8;
const BLOCK_HALF_ANGLE = 1.31;
const ARROW_DAMAGE = 8;
const ARROW_SPEED = 38;
const MAX_STUCK_SPEARS = 12;
const MAX_STUCK_ARROWS = 16;
const MAX_FLYING_ARROWS = 24;
const VOLLEY_FLIGHT = 2.0; // loosed → first impact
const VOLLEY_SLOTS = 600; // visual arrows in flight at once
const GROUND_ARROWS = 200;
// Volley look (visual only): flight arrows grow with distance (×1 up to LOD_NEAR m, up to ×LOD_MAX)
// so a 9 mm shaft 40 m away still covers pixels, and each trails a motion-blur streak of its
// last VOLLEY_BLUR seconds of flight.
// The 2 s flight is a gameplay timing; the arc is drawn as if shot from much farther off (a
// stronger visual gravity), so the cloud towers 8–20 m over the pass and the arrows come down
// steeply on the line, as a long-range volley does.
const VOLLEY_G = 1.8 * G;
const LOD_NEAR = 12;
const LOD_MAX = 4;
const VOLLEY_BLUR = 0.06;
const ARROW_HALF = 0.37; // volley arrow: tip 0.37 m ahead of the instance origin, tail 0.36 behind
const SPEAR_SHADOW_DIST = 8; // stuck spears farther than this from the camera cast no shadow
const SKEWER_POWER = 0.7;
const SKEWER_REACH = 2.2;
const BLEED_TICK = 0.5;
const MAX_BLEEDS = 24;
const SPEAR_EMBED = 0.32;
const ARROW_EMBED = 0.12;
const PLAYER_ARROW_LIMIT = 3;
const EXECUTE_HP = 0.35; // Hoplite's Answer finishes a staggered foe at ≤ 35% hp…
const EXECUTE_MUL = 2.0; // …and otherwise strikes for double damage
const HEAVY_BASH_STAGGER = 0.5; // a heavy enemy (the captain) shrugs off a bash this fast

const Z_AXIS = new THREE.Vector3(0, 0, 1);

// ---- small geometry helpers ---------------------------------------------------------------

const vx = (p) => (Array.isArray(p) ? p[0] : p.x);
const vy = (p) => (Array.isArray(p) ? p[1] : p.y);
const vz = (p) => (Array.isArray(p) ? p[2] : p.z);

// Entry of the segment p0→p1 into an axis-aligned box: { t, axis, sign } (axis -1 if p0 starts
// inside), or null when it misses. min/max are [x,y,z] or {x,y,z}.
const boxHit = { t: 0, axis: 0, sign: 0 };
const comp = (p, i) => (Array.isArray(p) ? p[i] : i === 0 ? p.x : i === 1 ? p.y : p.z);
function segmentBox(p0, p1, min, max) {
  let tmin = 0;
  let tmax = 1;
  let axis = -1;
  let sign = 0;
  for (let i = 0; i < 3; i++) {
    const o = comp(p0, i);
    const d = comp(p1, i) - o;
    const lo = comp(min, i);
    const hi = comp(max, i);
    if (Math.abs(d) < 1e-12) {
      if (o < lo || o > hi) return null;
      continue;
    }
    let t1 = (lo - o) / d;
    let t2 = (hi - o) / d;
    let s = -1; // entering through the min face: outward normal points to -axis
    if (t1 > t2) {
      const tt = t1;
      t1 = t2;
      t2 = tt;
      s = 1;
    }
    if (t1 > tmin) {
      tmin = t1;
      axis = i;
      sign = s;
    }
    if (t2 < tmax) tmax = t2;
    if (tmin > tmax) return null;
  }
  boxHit.t = tmin;
  boxHit.axis = axis;
  boxHit.sign = sign;
  return boxHit;
}

function colorize(geo, r, g, b) {
  const n = geo.attributes.position.count;
  const c = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    c[i * 3] = r;
    c[i * 3 + 1] = g;
    c[i * 3 + 2] = b;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(c, 3));
  return geo;
}

// Volley arrow: 0.76 m, tip on +Z, origin near the centre of mass. ~30 triangles.
function buildArrowGeometry() {
  const shaft = new THREE.CylinderGeometry(0.0045, 0.0045, 0.66, 4, 1, true);
  shaft.rotateX(Math.PI / 2);
  shaft.translate(0, 0, -0.03);
  const head = new THREE.ConeGeometry(0.011, 0.075, 4);
  head.rotateX(Math.PI / 2);
  head.translate(0, 0, 0.335);
  const f1 = new THREE.PlaneGeometry(0.03, 0.12);
  f1.rotateX(Math.PI / 2);
  f1.translate(0, 0, -0.30);
  const f2 = f1.clone();
  f2.rotateZ(Math.PI / 2);
  colorize(shaft, 0.52, 0.40, 0.26);
  colorize(head, 0.20, 0.20, 0.22);
  colorize(f1, 0.80, 0.77, 0.70);
  colorize(f2, 0.80, 0.77, 0.70);
  const geo = mergeGeometries([shaft, head, f1, f2], false);
  for (const g of [shaft, head, f1, f2]) g.dispose();
  return geo;
}

// Fallback dory if gfx/characters.js is missing: a 2.7 m ash shaft with a leather grip, a bronze
// leaf-shaped head and a sauroter (butt spike). Tip on +Z at 1.40, centre of mass near the origin.
function buildDoryGeometry() {
  const along = (g, z0) => {
    g.rotateX(Math.PI / 2);
    g.translate(0, 0, z0);
    return g;
  };
  const shaft = colorize(along(new THREE.CylinderGeometry(0.016, 0.019, 2.0, 8, 1, true), 0), 0.55, 0.42, 0.28);
  const grip = colorize(along(new THREE.CylinderGeometry(0.021, 0.021, 0.22, 8, 1, true), -0.02), 0.26, 0.17, 0.10);
  const socket = colorize(along(new THREE.CylinderGeometry(0.019, 0.017, 0.12, 8, 1, false), 1.02), 0.72, 0.52, 0.26);
  const head = new THREE.ConeGeometry(0.038, 0.34, 8);
  head.scale(1, 1, 0.35); // a flat leaf blade
  colorize(along(head, 1.23), 0.80, 0.60, 0.32);
  const collar = colorize(along(new THREE.CylinderGeometry(0.02, 0.02, 0.05, 8, 1, false), -1.0), 0.70, 0.50, 0.26);
  const butt = new THREE.ConeGeometry(0.017, 0.28, 6);
  butt.rotateX(-Math.PI / 2);
  butt.translate(0, 0, -1.16);
  colorize(butt, 0.70, 0.50, 0.26);
  const parts = [shaft, grip, socket, head, collar, butt];
  const geo = mergeGeometries(parts.map((g) => (g.index ? g.toNonIndexed() : g)), false);
  for (const g of parts) g.dispose();
  geo.computeVertexNormals();
  return geo;
}

/** @param {object} ctx shared context (§4) */
export function createCombat(ctx) {
  const ev = ctx.events;
  const scene = ctx.scene;

  // Scratch (no per-frame allocation in the hot paths).
  const sec = { hit: false, dist: 0, angle: 0 };
  const sectorList = [];
  const sectorDist = [];
  const single = [];
  const hitsOut = [];
  const hitPool = [];
  const capA = new THREE.Vector3();
  const capB = new THREE.Vector3();
  const tipNow = new THREE.Vector3();
  const tmp = new THREE.Vector3();
  const tmp2 = new THREE.Vector3();
  const dirTmp = new THREE.Vector3();
  const mat4 = new THREE.Matrix4();
  const quat = new THREE.Quaternion();
  const scl = new THREE.Vector3();
  const ZERO_SCALE = new THREE.Matrix4().makeScale(0, 0, 0);

  const flying = []; // projectiles in flight
  const sinking = [];
  const stuckSpears = [];
  const stuckArrows = [];
  const bleeds = [];
  const volleys = [];
  const propInfo = {}; // name → { tip }

  // ---- volley visuals: one InstancedMesh, ground ring [0, 200) then flight slots ----------
  const arrowGeo = buildArrowGeometry();
  const arrowMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.75, metalness: 0.05, side: THREE.DoubleSide });
  const volleyMesh = new THREE.InstancedMesh(arrowGeo, arrowMat, GROUND_ARROWS + VOLLEY_SLOTS);
  volleyMesh.name = 'volleyArrows';
  volleyMesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  volleyMesh.frustumCulled = false;
  volleyMesh.castShadow = false;
  volleyMesh.receiveShadow = false;
  volleyMesh.count = 0;
  for (let i = 0; i < GROUND_ARROWS + VOLLEY_SLOTS; i++) volleyMesh.setMatrixAt(i, ZERO_SCALE);
  // Motion-blur streaks of the flight slots, under volleyMesh so hiding it hides the whole volley.
  const streaks = createArrowStreaks(VOLLEY_SLOTS, { renderer: ctx.renderer });
  volleyMesh.add(streaks.mesh);
  if (scene) scene.add(volleyMesh);
  // Flight slot data (struct of arrays).
  const fl = {
    on: new Uint8Array(VOLLEY_SLOTS),
    vol: new Array(VOLLEY_SLOTS).fill(null),
    t0: new Float32Array(VOLLEY_SLOTS),
    tf: new Float32Array(VOLLEY_SLOTS),
    px: new Float32Array(VOLLEY_SLOTS),
    py: new Float32Array(VOLLEY_SLOTS),
    pz: new Float32Array(VOLLEY_SLOTS),
    vx: new Float32Array(VOLLEY_SLOTS),
    vy: new Float32Array(VOLLEY_SLOTS),
    vz: new Float32Array(VOLLEY_SLOTS),
  };
  let flyingHigh = 0;
  let flyingActive = 0;
  let groundNext = 0;
  let groundCount = 0;

  let doryGeo = null;
  let doryMat = null;

  const groundY = (x, z) => (ctx.world && ctx.world.groundHeight ? ctx.world.groundHeight(x, z) : 0);
  const gnow = () => ctx.time.gnow;
  const rng = () => ctx.rng;

  // ---- props --------------------------------------------------------------------------------

  function makeProp(name) {
    let obj = null;
    const ch = ctx.characters;
    if (ch && ch.createProp) {
      try {
        obj = ch.createProp(name);
      } catch (err) {
        console.warn(`combat: createProp(${name}) failed`, err);
        obj = null;
      }
    }
    if (!obj) {
      if (name === 'arrow') {
        obj = new THREE.Mesh(arrowGeo, arrowMat);
      } else {
        if (!doryGeo) {
          doryGeo = buildDoryGeometry();
          doryMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.55, metalness: 0.35 });
        }
        obj = new THREE.Mesh(doryGeo, doryMat);
        obj.castShadow = true;
      }
      obj.name = `prop:${name}`;
    }
    // An 8 mm shaft leaves nothing in a 3.5 cm shadow texel, and stuck arrows sit mostly beside
    // or behind the camera, culled from the view but not from the shadow box.
    if (name === 'arrow') obj.castShadow = false;
    if (!propInfo[name]) {
      // Tip offset along +Z from the centre of mass, measured once per prop kind.
      obj.updateMatrixWorld(true);
      const box = new THREE.Box3().setFromObject(obj);
      const tip = Number.isFinite(box.max.z) && box.max.z > 0.05 ? box.max.z : name === 'arrow' ? 0.38 : 1.25;
      propInfo[name] = { tip };
    }
    return obj;
  }

  function orient(mesh, dx, dy, dz) {
    dirTmp.set(dx, dy, dz);
    const l = dirTmp.length();
    if (l < 1e-9) return;
    dirTmp.multiplyScalar(1 / l);
    mesh.quaternion.setFromUnitVectors(Z_AXIS, dirTmp);
  }

  function attachTo(actor, mesh, socket) {
    const vis = actor && actor.visual;
    if (!vis) return false;
    try {
      if (vis.attach) {
        vis.attach(mesh, socket);
        return true;
      }
      const s = (vis.sockets && vis.sockets[socket]) || vis.root;
      if (s) {
        s.attach(mesh);
        return true;
      }
    } catch (err) {
      console.warn('combat: attach failed', err);
    }
    return false;
  }

  function retire(list, max) {
    while (list.length > max) {
      const old = list.shift();
      if (old.mesh) old.mesh.removeFromParent();
      old.stuck = false;
    }
  }

  // ---- targeting ----------------------------------------------------------------------------

  /** Living actors of `team` inside the sector, nearest first. Returns a reused array. */
  function hostilesInSector(origin, yaw, reach, halfAngle, team = 'persian') {
    sectorList.length = 0;
    sectorDist.length = 0;
    const actors = ctx.actors || [];
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a || !a.alive || a.team !== team || !a.pos) continue;
      sectorHit(origin, yaw, reach, halfAngle, a.pos, a.radius || 0.32, sec);
      if (!sec.hit) continue;
      // insertion by distance
      let j = sectorList.length;
      sectorList.push(a);
      sectorDist.push(sec.dist);
      while (j > 0 && sectorDist[j - 1] > sec.dist) {
        sectorList[j] = sectorList[j - 1];
        sectorDist[j] = sectorDist[j - 1];
        j--;
      }
      sectorList[j] = a;
      sectorDist[j] = sec.dist;
    }
    return sectorList;
  }

  function chestOf(actor, out) {
    if (actor.chestPoint) return actor.chestPoint(out);
    return out.set(actor.pos.x, actor.pos.y + (actor.height || 1.75) * 0.74, actor.pos.z);
  }

  // ---- hits ---------------------------------------------------------------------------------

  function emitHit(target, attacker, res, hit, comboId) {
    const dmg = Number.isFinite(res.damage) ? res.damage : hit.damage;
    ev.emit('hit', {
      target,
      attacker,
      damage: dmg,
      kind: hit.kind,
      dir: { x: hit.dir.x, z: hit.dir.z },
      point: hit.point,
      guarded: !!res.guarded,
      critical: !!hit.critical,
      killed: !!res.killed,
      byPlayer: !!hit.byPlayer,
      combo: comboId || null,
    });
  }

  function emitKill(target, attacker, byPlayer, kind, spear, cause) {
    ev.emit('kill', { target, attacker, byPlayer: !!byPlayer, kind, spear: !!spear, cause });
  }

  // Calls takeHit and emits hit (+ kill). Returns the takeHit result or null.
  function deliver(target, hit, comboId, spear) {
    if (!target || !target.alive || !target.takeHit) return null;
    const kb = hit.knockback || 0;
    const seaBound = kb > 0 && target.pos.x + hit.dir.x * kb > SEA_X;
    hit.combo = comboId || null;
    const res = target.takeHit(hit) || { damage: hit.damage, guarded: false, killed: !target.alive };
    emitHit(target, hit.attacker, res, hit, comboId);
    if (res.killed) {
      let cause = 'weapon';
      if (res.cause === 'sea' || (kb > 0 && (seaBound || target.pos.x > SEA_X))) cause = 'sea';
      else if (hit.kind === 'bleed') cause = 'bleed';
      emitKill(target, hit.attacker, hit.byPlayer, hit.kind, spear, cause);
    }
    return res;
  }

  // Bash stagger for a heavy enemy (stats.bashStagger, else HEAVY_BASH_STAGGER), or null.
  function bashStaggerOf(target) {
    const st = target.stats;
    if (st && Number.isFinite(st.bashStagger)) return st.bashStagger;
    return target.heavy || (st && st.heavy) ? HEAVY_BASH_STAGGER : null;
  }

  function makeHit(kind, damage, dx, dz, power, byPlayer, attacker, point) {
    return {
      damage, kind, dir: { x: dx, z: dz }, power, ignoreGuard: false, knockback: 0, stagger: 0, knockdown: false,
      byPlayer, critical: false, point, attacker, combo: null, wall: false,
    };
  }

  function addBleed(target, dps, dur, byPlayer) {
    for (const b of bleeds) {
      if (b.target === target) {
        b.dps = dps;
        b.left = dur;
        b.byPlayer = byPlayer;
        return;
      }
    }
    if (bleeds.length >= MAX_BLEEDS) bleeds.shift();
    bleeds.push({ target, dps, left: dur, acc: 0, tick: 0, byPlayer });
  }

  /**
   * Resolves a player attack at its strike moment. One target (the focus if it is in the sector,
   * else the nearest), except sweep and stagger_all combos. Returns a reused array of
   * { target, damage, guarded, killed }.
   */
  function playerStrike(attack) {
    hitsOut.length = 0;
    if (!attack) return hitsOut;
    const pl = ctx.player;
    const kind = attack.kind;
    const combo = attack.combo || null;
    const origin = attack.origin || (pl && pl.pos);
    if (!origin) return hitsOut;
    const yaw = Number.isFinite(attack.yaw) ? attack.yaw : pl ? pl.yaw : 0;
    const power = Number.isFinite(attack.power) ? clamp(attack.power, 0, 1) : 0.6;
    const crit = !!attack.crit;
    let weapon = attack.weapon;
    if (!weapon) weapon = kind === 'spearThrust' ? 'spear' : kind === 'bash' ? 'shield' : pl && pl.weapon === 'spear' && pl.hasSpearInHand ? 'spear' : 'sword';

    let reach;
    let halfAngle;
    let base;
    let ignoreGuard = false;
    let knockback = 0;
    let stagger = 0;
    let knockdown = false;
    const S = WEAPONS.shield;
    if (kind === 'slash') {
      const W = weapon === 'spear' ? WEAPONS.spear : WEAPONS.sword;
      const prof = slashProfile(attack.dir);
      reach = W.reach;
      halfAngle = prof.halfAngle;
      base = W.slashDamage * prof.damageMul;
      ignoreGuard = prof.overShield;
    } else if (kind === 'thrust' || kind === 'spearThrust') {
      const W = kind === 'spearThrust' ? WEAPONS.spear : WEAPONS.sword;
      reach = W.reach;
      halfAngle = W.thrustHalfAngle;
      base = W.thrustDamage;
      ignoreGuard = power > 0.8;
    } else if (kind === 'bash') {
      reach = S.reach;
      halfAngle = S.halfAngle;
      base = S.bashDamage;
      ignoreGuard = true;
      knockback = S.bashKnockback;
      stagger = S.bashStagger;
      knockdown = power > 0.75;
    } else {
      return hitsOut;
    }
    if (combo && combo.breakGuard) ignoreGuard = true;
    if (combo && combo.knockback) {
      knockback = combo.knockback;
      stagger = Math.max(stagger, 0.9);
    }
    const effect = combo ? combo.effect : null;
    const wall = effect === 'stagger_all';

    let targets;
    if (effect === 'sweep') {
      targets = hostilesInSector(origin, yaw, reach, (combo.arc || 1.75) / 2);
    } else if (wall) {
      targets = hostilesInSector(origin, yaw, combo.radius || 2.5, Math.PI);
      ignoreGuard = true;
      knockback = Math.max(knockback, S.bashKnockback);
      stagger = Math.max(stagger, S.bashStagger);
    } else {
      const cands = hostilesInSector(origin, yaw, reach, halfAngle);
      const focus = attack.focus !== undefined ? attack.focus : pl ? pl.focusTarget : null;
      single.length = 0;
      const pick = focus && cands.indexOf(focus) >= 0 ? focus : cands[0];
      if (pick) single.push(pick);
      targets = single;
    }
    if (targets.length === 0) return hitsOut;
    // Copy: takeHit handlers may call back into hostilesInSector.
    const list = targets.slice();
    const comboMul = combo ? combo.mult || 1 : 1;
    const now = gnow();
    for (let i = 0; i < list.length; i++) {
      const target = list[i];
      if (!target.alive) continue;
      let dx = target.pos.x - origin.x;
      let dz = target.pos.z - origin.z;
      const d = Math.sqrt(dx * dx + dz * dz);
      if (d > 1e-6) {
        dx /= d;
        dz /= d;
      } else {
        dx = -Math.sin(yaw);
        dz = -Math.cos(yaw);
      }
      const point = chestOf(target, new THREE.Vector3());
      const r = target.radius || 0.32;
      point.x -= dx * r * 0.9;
      point.z -= dz * r * 0.9;
      let damage = computeDamage({ base, power, comboMul, crit });
      let hitIgnore = ignoreGuard;
      let hitKb = knockback;
      let hitStagger = stagger;
      let hitKd = knockdown;
      const staggered = target.state === 'stagger' || target.state === 'knockdown';
      if (effect === 'execute' && staggered) {
        const hp = target.hp;
        const maxHp = target.maxHp;
        const weakened = Number.isFinite(hp) && Number.isFinite(maxHp) && maxHp > 0 && hp <= EXECUTE_HP * maxHp + 1e-9;
        damage = weakened ? Math.max(damage, hp) : damage * EXECUTE_MUL;
        hitIgnore = true;
      }
      if (kind === 'bash') {
        const last = Number.isFinite(target.lastBashedAt) ? target.lastBashedAt : -Infinity;
        if (!wall && now - last < S.rebashImmunity) {
          hitKb = 0;
          hitStagger = 0;
          hitKd = false;
        } else {
          target.lastBashedAt = now;
          const heavyStagger = bashStaggerOf(target);
          if (heavyStagger !== null) {
            hitStagger = Math.min(hitStagger, heavyStagger);
            hitKd = false;
          }
        }
      }
      const hit = makeHit(kind, damage, dx, dz, power, true, 'player', point);
      hit.wall = wall;
      hit.ignoreGuard = hitIgnore;
      hit.knockback = hitKb;
      hit.stagger = hitStagger;
      hit.knockdown = hitKd;
      hit.critical = crit;
      const res = deliver(target, hit, combo ? combo.id : null, false);
      if (!res) continue;
      if (combo && combo.bleed && !res.killed && target.alive) addBleed(target, combo.bleed.dps, combo.bleed.dur, true);
      let h = hitPool[hitsOut.length];
      if (!h) h = hitPool[hitsOut.length] = { target: null, damage: 0, guarded: false, killed: false };
      h.target = target;
      h.damage = Number.isFinite(res.damage) ? res.damage : damage;
      h.guarded = !!res.guarded;
      h.killed = !!res.killed;
      hitsOut.push(h);
    }
    return hitsOut;
  }

  /**
   * Resolves an actor's strike at its strike moment against the player or another actor.
   * @returns {'hit'|'block'|'perfect'|'guardBreak'|'miss'}
   */
  function actorStrike(attacker, attack) {
    if (!attacker || attacker.alive === false || !attack) return 'miss';
    const pl = ctx.player;
    const toPlayer = attack.target === 'player' || (pl && attack.target === pl);
    const target = toPlayer ? pl : attack.target;
    if (!target || !target.pos) return 'miss';
    if (toPlayer ? !pl.alive : !target.alive) return 'miss';
    const reach = (Number.isFinite(attack.reach) ? attack.reach : 2) + 0.3;
    const halfAngle = Number.isFinite(attack.halfAngle) ? attack.halfAngle : 0.6;
    const radius = toPlayer ? PLAYER_RADIUS : target.radius || 0.32;
    sectorHit(attacker.pos, facingToYaw(attacker.facing || 0), reach, halfAngle, target.pos, radius, sec);
    if (!sec.hit) return 'miss';

    let dx = target.pos.x - attacker.pos.x;
    let dz = target.pos.z - attacker.pos.z;
    const d = Math.sqrt(dx * dx + dz * dz) || 1;
    dx /= d;
    dz /= d;
    const kind = attack.kind || 'melee';
    const damage = Number.isFinite(attack.damage) ? attack.damage : 10;

    if (toPlayer) {
      const gy = groundY(pl.pos.x, pl.pos.z);
      const point = new THREE.Vector3(pl.pos.x - dx * 0.5, gy + 1.3, pl.pos.z - dz * 0.5);
      const r = pl.tryBlock ? pl.tryBlock(attacker, kind) : 'none';
      if (r === 'perfect') {
        ev.emit('block', { by: 'player', attacker, point, perfect: true, kind });
        if (attacker.takeHit) {
          const h = makeHit('parry', 0, -dx, -dz, 0, true, 'player', point);
          h.stagger = attack.heavy ? 1.4 : 1.2;
          h.ignoreGuard = true;
          attacker.takeHit(h);
        }
        return 'perfect';
      }
      if (r === 'block') {
        if (attack.heavy) {
          ev.emit('block', { by: 'player', attacker, point, perfect: false, kind: 'guardBreak' });
          if (pl.guardBreak) pl.guardBreak(attacker);
          if (pl.damage) pl.damage(8, { from: attacker, kind });
          return 'guardBreak';
        }
        ev.emit('block', { by: 'player', attacker, point, perfect: false, kind });
        return 'block';
      }
      if (pl.damage) pl.damage(damage, { from: attacker, kind });
      return 'hit';
    }

    const point = chestOf(target, new THREE.Vector3());
    point.x -= dx * (target.radius || 0.32);
    point.z -= dz * (target.radius || 0.32);
    const hit = makeHit(kind, damage, dx, dz, 0.6, false, attacker, point);
    const res = deliver(target, hit, null, false);
    if (!res) return 'miss';
    return res.guarded ? 'block' : 'hit';
  }

  // ---- arrows vs the player and allies ------------------------------------------------------

  // Resolves one arrow on the player. The raised shield (≥ 0.4) takes it only if the player
  // faces where it comes from: the shooter's bearing, or for a volley the Persian front (yaw 0)
  // or, fromBehind, the rear (yaw π). Turning toward the threat is the skill.
  function arrowOnPlayer(fromBehind, shooter) {
    const pl = ctx.player;
    if (!pl || !pl.alive) return 'miss';
    const from = shooter && shooter.pos ? yawTo(shooter.pos.x - pl.pos.x, shooter.pos.z - pl.pos.z) : fromBehind ? Math.PI : 0;
    const relYaw = angleDiff(pl.yaw, from);
    const frontal = Math.abs(relYaw) <= BLOCK_HALF_ANGLE;
    const gy = groundY(pl.pos.x, pl.pos.z);
    const sy = Math.sin(pl.yaw);
    const cy = Math.cos(pl.yaw);
    if (frontal && pl.shieldRaise >= 0.4) {
      const point = new THREE.Vector3(pl.pos.x - sy * 0.45, gy + 1.35, pl.pos.z - cy * 0.45);
      ev.emit('arrowHit', { target: 'shield', point });
      return 'shield';
    }
    const point = new THREE.Vector3(pl.pos.x, gy + 1.3, pl.pos.z);
    ev.emit('arrowHit', { target: 'player', point });
    if (pl.damage) pl.damage(ARROW_DAMAGE, { from: shooter || null, kind: 'arrow', relYaw });
    return 'player';
  }

  // Sticks a visual arrow prop into an actor (shield arm if blocked, else chest).
  function stickArrowProp(actor, point, dx, dy, dz, blocked) {
    const mesh = makeProp('arrow');
    const tip = propInfo.arrow.tip;
    const l = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
    dx /= l;
    dy /= l;
    dz /= l;
    mesh.position.set(point.x + dx * (ARROW_EMBED - tip), point.y + dy * (ARROW_EMBED - tip), point.z + dz * (ARROW_EMBED - tip));
    orient(mesh, dx, dy, dz);
    if (scene) scene.add(mesh);
    mesh.updateMatrixWorld(true);
    if (!attachTo(actor, mesh, blocked ? 'leftHand' : 'chest')) mesh.removeFromParent();
    const rec = { kind: 'arrow', mesh, stuck: true };
    stuckArrows.push(rec);
    retire(stuckArrows, MAX_STUCK_ARROWS);
  }

  // One arrow on a Greek actor: allies block with p 0.8, otherwise it hits (8 damage).
  function arrowOnActor(actor, point, dx, dy, dz, shooter) {
    if (!actor || !actor.alive) return;
    const r = rng();
    const isAlly = actor.role === 'ally';
    if (isAlly && r && r.chance(0.8)) {
      ev.emit('arrowHit', { target: null, point, blocked: true, by: actor });
      stickArrowProp(actor, point, dx, dy, dz, true);
      return;
    }
    const hl = Math.sqrt(dx * dx + dz * dz) || 1;
    const hit = makeHit('arrow', ARROW_DAMAGE, dx / hl, dz / hl, 0.5, false, shooter || null, point);
    hit.ignoreGuard = isAlly;
    ev.emit('arrowHit', { target: actor, point });
    const res = deliver(actor, hit, null, false);
    if (res && !res.guarded) stickArrowProp(actor, point, dx, dy, dz, false);
  }

  // ---- projectiles --------------------------------------------------------------------------

  function spawnProjectile(kind, origin, velocity, opts) {
    const mesh = makeProp(kind === 'spear' ? 'dory' : 'arrow');
    const p = {
      kind,
      mesh,
      pos: new THREE.Vector3(vx(origin), vy(origin), vz(origin)),
      prevPos: new THREE.Vector3(vx(origin), vy(origin), vz(origin)),
      vel: new THREE.Vector3(vx(velocity), vy(velocity), vz(velocity)),
      alive: true,
      stuck: false,
      byPlayer: !!opts.byPlayer,
      power: clamp(Number.isFinite(opts.power) ? opts.power : 0.5, 0, 1),
      src: opts.src || 'test',
      target: opts.target || null,
      hits: [],
      age: 0,
      impactPoint: null,
      shooter: opts.shooter || null,
      tip: propInfo[kind === 'spear' ? 'dory' : 'arrow'].tip,
      tipPrev: new THREE.Vector3(vx(origin), vy(origin), vz(origin)), // the first sweep starts at the hand
      water: false,
      sinkT: 0,
    };
    mesh.position.copy(p.pos);
    orient(mesh, p.vel.x, p.vel.y, p.vel.z);
    if (scene) scene.add(mesh);
    flying.push(p);
    return p;
  }

  /** Launches a spear from `origin` with `velocity` (Vector3 or [x,y,z]). Emits spearThrown. */
  function throwSpear({ origin, velocity, power = 0.5, byPlayer = true, src = 'test' } = {}) {
    if (!origin || !velocity) return null;
    const p = spawnProjectile('spear', origin, velocity, { power, byPlayer, src });
    ev.emit('spearThrown', { projectile: p });
    return p;
  }

  /** A single aimed arrow from an archer at the player or an actor; resolves like a volley arrow. */
  function shootArrow(fromActor, target) {
    if (!fromActor || !fromActor.pos || !target) return null;
    const pl = ctx.player;
    const toPlayer = target === 'player' || target === pl;
    let arrows = 0;
    for (const p of flying) if (p.kind === 'arrow') arrows++;
    if (arrows >= MAX_FLYING_ARROWS) return null;
    const f = fromActor.facing || 0;
    const origin = chestOf(fromActor, new THREE.Vector3());
    origin.y += 0.18;
    origin.x += Math.sin(f) * 0.4;
    origin.z += Math.cos(f) * 0.4;
    const aim = new THREE.Vector3();
    if (toPlayer) {
      if (!pl) return null;
      aim.set(pl.pos.x, groundY(pl.pos.x, pl.pos.z) + 1.35, pl.pos.z);
    } else {
      if (!target.alive) return null;
      chestOf(target, aim);
    }
    const dx = aim.x - origin.x;
    const dz = aim.z - origin.z;
    const dxz = Math.sqrt(dx * dx + dz * dz);
    const p = ballisticPitch(ARROW_SPEED, dxz, aim.y - origin.y);
    const r = rng();
    const spread = r ? r.gauss() * 0.006 : 0;
    const pitch = (p === null ? Math.PI / 4 : p) + spread;
    const yaw = yawTo(dx, dz) + (r ? r.gauss() * 0.006 : 0);
    const c = Math.cos(pitch);
    const vel = new THREE.Vector3(-Math.sin(yaw) * c * ARROW_SPEED, Math.sin(pitch) * ARROW_SPEED, -Math.cos(yaw) * c * ARROW_SPEED);
    return spawnProjectile('arrow', origin, vel, { byPlayer: false, target: toPlayer ? 'player' : target, shooter: fromActor, power: 0.5 });
  }

  // Earliest contact of the tip sweep p.tipPrev → tipNow: { t, kind, actor, normal }.
  const contact = { t: 2, kind: '', actor: null, nx: 0, ny: 1, nz: 0 };
  function findContact(p) {
    contact.t = 2;
    contact.kind = '';
    contact.actor = null;
    const a0 = p.tipPrev;
    const a1 = tipNow;
    const actors = ctx.actors || [];
    for (let i = 0; i < actors.length; i++) {
      const a = actors[i];
      if (!a || !a.alive || !a.pos || a === p.shooter || a.role === 'helot') continue;
      if (p.kind === 'spear' ? a.team !== 'persian' : a.team !== 'greek') continue;
      if (p.hits.indexOf(a) >= 0) continue;
      const r = a.radius || 0.32;
      const h = a.height || 1.75;
      capA.set(a.pos.x, a.pos.y + r, a.pos.z);
      capB.set(a.pos.x, a.pos.y + Math.max(h - r, r + 0.01), a.pos.z);
      const t = segmentCapsule(a0, a1, capA, capB, r);
      if (t >= 0 && t < contact.t) {
        contact.t = t;
        contact.kind = 'actor';
        contact.actor = a;
      }
    }
    const pl = ctx.player;
    if (p.kind === 'arrow' && pl && pl.alive) {
      const gy = groundY(pl.pos.x, pl.pos.z);
      capA.set(pl.pos.x, gy + PLAYER_RADIUS, pl.pos.z);
      capB.set(pl.pos.x, gy + PLAYER_HEIGHT - PLAYER_RADIUS, pl.pos.z);
      const t = segmentCapsule(a0, a1, capA, capB, PLAYER_RADIUS);
      if (t >= 0 && t < contact.t) {
        contact.t = t;
        contact.kind = 'player';
        contact.actor = null;
      }
    }
    // Ground (or the sea surface).
    const h0 = a0.y - groundY(a0.x, a0.z);
    const h1 = a1.y - groundY(a1.x, a1.z);
    if (h1 <= 0) {
      const t = h0 <= 0 ? 0 : h0 / (h0 - h1);
      if (t < contact.t) {
        contact.t = t;
        contact.kind = 'ground';
        contact.nx = 0;
        contact.ny = 1;
        contact.nz = 0;
      }
    }
    const cols = ctx.world && ctx.world.colliders;
    if (cols) {
      for (let i = 0; i < cols.length; i++) {
        const c = cols[i];
        if (c.type === 'plane') {
          const nx = vx(c.normal);
          const ny = vy(c.normal);
          const nz = vz(c.normal);
          const px = vx(c.point);
          const py = vy(c.point);
          const pz = vz(c.point);
          const d0 = (a0.x - px) * nx + (a0.y - py) * ny + (a0.z - pz) * nz;
          const d1 = (a1.x - px) * nx + (a1.y - py) * ny + (a1.z - pz) * nz;
          if (d0 > 0 && d1 <= 0) {
            const t = d0 / (d0 - d1);
            if (t < contact.t) {
              contact.t = t;
              contact.kind = 'collider';
              contact.nx = nx;
              contact.ny = ny;
              contact.nz = nz;
            }
          }
        } else if (c.type === 'box') {
          const b = segmentBox(a0, a1, c.min, c.max);
          if (b && b.axis >= 0 && b.t < contact.t) {
            contact.t = b.t;
            contact.kind = 'collider';
            contact.nx = b.axis === 0 ? b.sign : 0;
            contact.ny = b.axis === 1 ? b.sign : 0;
            contact.nz = b.axis === 2 ? b.sign : 0;
          }
        }
      }
    }
    return contact.t <= 1 ? contact : null;
  }

  // Places the projectile so its tip sits `embed` beyond `point` along its flight direction.
  function placeEmbedded(p, point, embed) {
    const v = p.vel;
    const l = v.length() || 1;
    const dx = v.x / l;
    const dy = v.y / l;
    const dz = v.z / l;
    p.pos.set(point.x + dx * (embed - p.tip), point.y + dy * (embed - p.tip), point.z + dz * (embed - p.tip));
    p.mesh.position.copy(p.pos);
    orient(p.mesh, dx, dy, dz);
    p.mesh.updateMatrixWorld(true);
  }

  function stickInGround(p, point, nx, ny, nz) {
    p.alive = false;
    p.impactPoint = point.clone();
    const water = point.y <= WATER_Y + 0.05 && groundY(point.x, point.z) <= WATER_Y;
    if (water) {
      p.water = true;
      p.sinkT = 0;
      sinking.push(p);
      if (ctx.fx && ctx.fx.splash) ctx.fx.splash(p.impactPoint);
      if (p.kind === 'spear') ev.emit('spearImpact', { projectile: p, target: null, point: p.impactPoint, normal: new THREE.Vector3(0, 1, 0), water: true });
      else ev.emit('arrowHit', { target: null, point: p.impactPoint });
      return;
    }
    p.stuck = true;
    placeEmbedded(p, point, p.kind === 'spear' ? 0.35 : 0.14);
    if (p.kind === 'spear') {
      stuckSpears.push(p);
      retire(stuckSpears, MAX_STUCK_SPEARS);
      ev.emit('spearImpact', { projectile: p, target: null, point: p.impactPoint, normal: new THREE.Vector3(nx, ny, nz), water: false });
    } else {
      stuckArrows.push(p);
      retire(stuckArrows, MAX_STUCK_ARROWS);
      ev.emit('arrowHit', { target: null, point: p.impactPoint });
    }
  }

  function socketFor(actor, point) {
    const top = actor.pos.y + (actor.height || 1.75);
    return point.y > top - 0.3 ? 'head' : 'chest';
  }

  function spearHitsActor(p, actor, point) {
    const l = Math.sqrt(p.vel.x * p.vel.x + p.vel.z * p.vel.z) || 1;
    const dx = p.vel.x / l;
    const dz = p.vel.z / l;
    const hit = makeHit('spear', computeDamage({ base: WEAPONS.spear.throwDamage, power: p.power }), dx, dz, p.power, p.byPlayer, p.byPlayer ? 'player' : null, point);
    hit.ignoreGuard = true;
    hit.stagger = 0.9;
    p.hits.push(actor);
    deliver(actor, hit, null, true);
  }

  function resolveSpearOnActor(p, actor, point) {
    spearHitsActor(p, actor, point);
    let final = actor;
    let finalPoint = point;
    // A hard throw can skewer a second actor just behind the first.
    if (p.power > SKEWER_POWER) {
      const sp = p.vel.length() || 1;
      tmp2.copy(p.vel).multiplyScalar(SKEWER_REACH / sp).add(point);
      let best = null;
      let bestT = 2;
      const actors = ctx.actors || [];
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (!a || !a.alive || a.team !== 'persian' || p.hits.indexOf(a) >= 0) continue;
        const r = a.radius || 0.32;
        capA.set(a.pos.x, a.pos.y + r, a.pos.z);
        capB.set(a.pos.x, a.pos.y + Math.max((a.height || 1.75) - r, r + 0.01), a.pos.z);
        const t = segmentCapsule(point, tmp2, capA, capB, r);
        if (t >= 0 && t < bestT) {
          bestT = t;
          best = a;
        }
      }
      if (best) {
        const pt2 = new THREE.Vector3().lerpVectors(point, tmp2, bestT);
        spearHitsActor(p, best, pt2);
        final = best;
        finalPoint = pt2;
      }
    }
    p.alive = false;
    p.stuck = true;
    p.target = final;
    p.impactPoint = finalPoint;
    placeEmbedded(p, finalPoint, SPEAR_EMBED);
    if (!attachTo(final, p.mesh, socketFor(final, finalPoint))) {
      // No visual to carry it: leave it where it struck.
    }
    stuckSpears.push(p);
    retire(stuckSpears, MAX_STUCK_SPEARS);
    const n = new THREE.Vector3(-p.vel.x, -p.vel.y, -p.vel.z).normalize();
    ev.emit('spearImpact', { projectile: p, target: final, point: finalPoint, normal: n, water: false });
  }

  function resolveArrow(p, c, point) {
    p.alive = false;
    p.impactPoint = point;
    if (c.kind === 'player') {
      p.mesh.removeFromParent();
      arrowOnPlayer(false, p.shooter);
      return;
    }
    const actor = c.actor;
    p.mesh.removeFromParent();
    arrowOnActor(actor, point, p.vel.x, p.vel.y, p.vel.z, p.shooter);
  }

  function stepProjectiles(dt) {
    for (let i = flying.length - 1; i >= 0; i--) {
      const p = flying[i];
      p.age += dt;
      p.prevPos.copy(p.pos);
      // Exact constant-gravity step, so flights match ballisticPitch.
      p.pos.x += p.vel.x * dt;
      p.pos.y += p.vel.y * dt - 0.5 * G * dt * dt;
      p.pos.z += p.vel.z * dt;
      p.vel.y -= G * dt;
      const sp = p.vel.length() || 1;
      tipNow.set(p.pos.x + (p.vel.x / sp) * p.tip, p.pos.y + (p.vel.y / sp) * p.tip, p.pos.z + (p.vel.z / sp) * p.tip);
      const c = dt > 0 ? findContact(p) : null;
      if (c) {
        const point = new THREE.Vector3().lerpVectors(p.tipPrev, tipNow, c.t);
        flying.splice(i, 1);
        if (p.kind === 'spear') {
          if (c.kind === 'actor') resolveSpearOnActor(p, c.actor, point);
          else stickInGround(p, point, c.nx, c.ny, c.nz);
        } else if (c.kind === 'actor' || c.kind === 'player') {
          resolveArrow(p, c, point);
        } else {
          stickInGround(p, point, c.nx, c.ny, c.nz);
        }
        continue;
      }
      p.tipPrev.copy(tipNow);
      p.mesh.position.copy(p.pos);
      orient(p.mesh, p.vel.x, p.vel.y, p.vel.z);
      if (p.age > 10 || p.pos.y < -20) {
        p.alive = false;
        p.mesh.removeFromParent();
        flying.splice(i, 1);
      }
    }
    for (let i = sinking.length - 1; i >= 0; i--) {
      const p = sinking[i];
      p.sinkT += dt;
      const k = Math.exp(-3.5 * dt);
      p.vel.multiplyScalar(k);
      p.vel.y -= 1.2 * dt;
      p.pos.addScaledVector(p.vel, dt);
      p.mesh.position.copy(p.pos);
      if (p.sinkT > 2.5) {
        p.mesh.removeFromParent();
        sinking.splice(i, 1);
      }
    }
  }

  // ---- volleys ------------------------------------------------------------------------------

  /**
   * A massed arrow volley on the Greek line: warning at once, loosed at `warn`, impact 2 s later,
   * end after `duration`. Up to min(3, ceil(count/100)) arrows resolve against the player.
   */
  function volley({ count = 300, duration = 1.0, warn = 2.5, fromBehind = false } = {}) {
    const r = rng();
    const rnd = () => (r ? r.next() : Math.random());
    const v = { t: 0, count: Math.max(0, count | 0), duration, warn, fromBehind, phase: 'warning', playerTimes: [], allies: [], spawned: false };
    const impactAt = warn + VOLLEY_FLIGHT;
    const n = Math.min(PLAYER_ARROW_LIMIT, Math.ceil(v.count / 100));
    for (let k = 0; k < n; k++) v.playerTimes.push(impactAt + (duration * (k + 0.15 + 0.7 * rnd())) / n);
    volleys.push(v);
    ev.emit('volley', { phase: 'warning', duration });
    return v;
  }

  function launchVolleyArrows(v) {
    const r = rng();
    const rnd = () => (r ? r.next() : Math.random());
    const pl = ctx.player;
    const px = pl ? pl.pos.x : 0;
    const pz = pl ? pl.pos.z : 0;
    const impactAt = v.warn + VOLLEY_FLIGHT;
    // The archers loose together from one block facing the player, so the arrows rise as a
    // cloud and fan out over the line (the draws per arrow are unchanged: gameplay rng).
    const lx0 = clamp(px, -3, 4) - 4;
    let slot = 0;
    for (let k = 0; k < v.count; k++) {
      while (slot < VOLLEY_SLOTS && fl.on[slot]) slot++;
      if (slot >= VOLLEY_SLOTS) break;
      const lx = lx0 + 8 * rnd();
      const lz = v.fromBehind ? 30 + 20 * rnd() : -42 - 22 * rnd();
      const ly = groundY(lx, lz) + 1.5;
      let tx = -8.3 + 18.5 * rnd();
      let tz = -4.5 + 7.5 * rnd();
      // Keep visual arrows out of the player's own body.
      const ddx = tx - px;
      const ddz = tz - pz;
      if (ddx * ddx + ddz * ddz < 1.5) {
        tx += ddx >= 0 ? 1.3 : -1.3;
        tz += ddz >= 0 ? 0.6 : -0.6;
      }
      const ty = Math.max(groundY(tx, tz), -3);
      const t0 = v.t + 0.15 * rnd();
      const tLand = impactAt + v.duration * rnd();
      const F = Math.max(0.8, tLand - t0);
      fl.on[slot] = 1;
      fl.vol[slot] = v;
      fl.t0[slot] = t0;
      fl.tf[slot] = F;
      fl.px[slot] = lx;
      fl.py[slot] = ly;
      fl.pz[slot] = lz;
      fl.vx[slot] = (tx - lx) / F;
      fl.vy[slot] = (ty - ly) / F + 0.5 * VOLLEY_G * F;
      fl.vz[slot] = (tz - lz) / F;
      flyingActive++;
      if (slot + 1 > flyingHigh) flyingHigh = slot + 1;
    }
  }

  function landGroundArrow(x, y, z, dx, dy, dz) {
    const l = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
    tmp.set(dx / l, dy / l, dz / l);
    quat.setFromUnitVectors(Z_AXIS, tmp);
    const r = rng();
    const embed = 0.18 + 0.12 * (r ? r.next() : 0.5);
    tmp2.set(x + tmp.x * (embed - 0.38), y + tmp.y * (embed - 0.38), z + tmp.z * (embed - 0.38));
    scl.set(1, 1, 1);
    mat4.compose(tmp2, quat, scl);
    volleyMesh.setMatrixAt(groundNext, mat4);
    groundNext = (groundNext + 1) % GROUND_ARROWS;
    groundCount = Math.min(GROUND_ARROWS, groundCount + 1);
  }

  // Flight arrows are written straight into the instance arrays (rotation taking +Z to the flight
  // direction, then the LOD scale): no quaternion, compose or setter calls in a loop over 600
  // slots, so nothing is boxed or allocated per arrow.
  function stepVolleyArrows() {
    if (flyingActive === 0) return false;
    const cam = ctx.camera;
    const cx = cam ? cam.position.x : 0;
    const cy = cam ? cam.position.y : 0;
    const cz = cam ? cam.position.z : 0;
    const M = volleyMesh.instanceMatrix.array;
    const S = streaks.data;
    let high = 0;
    for (let s = 0; s < flyingHigh; s++) {
      const so = s * STREAK_STRIDE;
      if (!fl.on[s]) {
        S[so + 6] = 0;
        continue;
      }
      const v = fl.vol[s];
      const tau = v.t - fl.t0[s];
      const o = (GROUND_ARROWS + s) * 16;
      if (tau < 0) {
        for (let e = 0; e < 16; e++) M[o + e] = 0;
        S[so + 6] = 0;
        high = s + 1;
        continue;
      }
      const T = fl.tf[s];
      if (tau >= T) {
        fl.on[s] = 0;
        fl.vol[s] = null;
        flyingActive--;
        for (let e = 0; e < 16; e++) M[o + e] = 0;
        S[so + 6] = 0;
        const x = fl.px[s] + fl.vx[s] * T;
        const y = fl.py[s] + fl.vy[s] * T - 0.5 * VOLLEY_G * T * T;
        const z = fl.pz[s] + fl.vz[s] * T;
        if (y > WATER_Y + 0.1) landGroundArrow(x, y, z, fl.vx[s], fl.vy[s] - VOLLEY_G * T, fl.vz[s]);
        continue;
      }
      high = s + 1;
      const x = fl.px[s] + fl.vx[s] * tau;
      const y = fl.py[s] + fl.vy[s] * tau - 0.5 * VOLLEY_G * tau * tau;
      const z = fl.pz[s] + fl.vz[s] * tau;
      const fvx = fl.vx[s];
      const dvy = fl.vy[s] - VOLLEY_G * tau;
      const fvz = fl.vz[s];
      const l = Math.sqrt(fvx * fvx + dvy * dvy + fvz * fvz) || 1;
      const ux = fvx / l;
      const uy = dvy / l;
      const uz = fvz / l;
      // Distance LOD: thicker and a little longer far away (×1 within LOD_NEAR metres).
      const ex = x - cx;
      const ey = y - cy;
      const ez = z - cz;
      let k = Math.sqrt(ex * ex + ey * ey + ez * ez) / LOD_NEAR;
      k = k < 1 ? 1 : k > LOD_MAX ? LOD_MAX : k;
      const kz = 1 + 0.5 * (k - 1);
      // Shortest rotation of +Z onto u (Rodrigues about z × u); u = -Z turns about Y.
      let r00 = -1;
      let r01 = 0;
      let r10 = 0;
      let r11 = 1;
      let r22 = -1;
      if (uz > -0.99999) {
        const q = 1 / (1 + uz);
        r00 = 1 - q * ux * ux;
        r01 = -q * ux * uy;
        r10 = r01;
        r11 = 1 - q * uy * uy;
        r22 = uz;
      }
      // Column-major: columns are R·(k,0,0), R·(0,k,0), R·(0,0,kz) and the position.
      M[o] = r00 * k;
      M[o + 1] = r10 * k;
      M[o + 2] = -ux * k;
      M[o + 3] = 0;
      M[o + 4] = r01 * k;
      M[o + 5] = r11 * k;
      M[o + 6] = -uy * k;
      M[o + 7] = 0;
      M[o + 8] = ux * kz;
      M[o + 9] = uy * kz;
      M[o + 10] = r22 * kz;
      M[o + 11] = 0;
      M[o + 12] = x;
      M[o + 13] = y;
      M[o + 14] = z;
      M[o + 15] = 1;
      // Streak: from the tip back over the arrow and its last VOLLEY_BLUR seconds of flight.
      const hx = x + ux * ARROW_HALF * kz;
      const hy = y + uy * ARROW_HALF * kz;
      const hz = z + uz * ARROW_HALF * kz;
      const back = 2 * ARROW_HALF * kz + l * VOLLEY_BLUR;
      S[so] = hx;
      S[so + 1] = hy;
      S[so + 2] = hz;
      S[so + 3] = hx - ux * back;
      S[so + 4] = hy - uy * back;
      S[so + 5] = hz - uz * back;
      S[so + 6] = tau < 0.1 ? tau * 10 : 1;
      S[so + 7] = (s * 0.618034) % 1;
    }
    flyingHigh = high;
    streaks.commit(high);
    return true;
  }

  function stepVolleys(dt) {
    const r = rng();
    for (let i = volleys.length - 1; i >= 0; i--) {
      const v = volleys[i];
      const before = v.t;
      v.t += dt;
      const impactAt = v.warn + VOLLEY_FLIGHT;
      const endAt = impactAt + v.duration;
      if (v.phase === 'warning' && v.t >= v.warn) {
        v.phase = 'loosed';
        ev.emit('volley', { phase: 'loosed', duration: v.duration });
        launchVolleyArrows(v);
        // Each ally may take one arrow during the impact window.
        const actors = ctx.actors || [];
        for (let k = 0; k < actors.length; k++) {
          const a = actors[k];
          if (a.alive && a.team === 'greek' && a.role === 'ally' && (!r || r.chance(0.7))) {
            v.allies.push({ actor: a, at: impactAt + v.duration * (r ? r.next() : 0.5) });
          }
        }
      }
      if (v.phase === 'loosed' && v.t >= impactAt) {
        v.phase = 'impact';
        ev.emit('volley', { phase: 'impact', duration: v.duration });
      }
      if (v.phase === 'impact' || v.phase === 'loosed') {
        for (let k = 0; k < v.playerTimes.length; k++) {
          const at = v.playerTimes[k];
          if (at > before && at <= v.t) arrowOnPlayer(v.fromBehind, null);
        }
        for (let k = 0; k < v.allies.length; k++) {
          const e = v.allies[k];
          if (e.at > before && e.at <= v.t && e.actor.alive) {
            const pt = chestOf(e.actor, new THREE.Vector3());
            arrowOnActor(e.actor, pt, 0.15, -1, v.fromBehind ? -0.5 : 0.5, null);
          }
        }
      }
      if (v.phase === 'impact' && v.t >= endAt) {
        v.phase = 'end';
        ev.emit('volley', { phase: 'end', duration: v.duration });
        volleys.splice(i, 1);
      }
    }
  }

  // ---- bleeds -------------------------------------------------------------------------------

  function stepBleeds(dt) {
    for (let i = bleeds.length - 1; i >= 0; i--) {
      const b = bleeds[i];
      if (!b.target.alive) {
        bleeds.splice(i, 1);
        continue;
      }
      const step = Math.min(dt, b.left);
      b.left -= step;
      b.acc += b.dps * step;
      b.tick += step;
      if (b.tick >= BLEED_TICK - 1e-9 || b.left <= 1e-9) {
        b.tick = 0;
        const dmg = b.acc;
        b.acc = 0;
        if (dmg > 0) {
          const point = chestOf(b.target, new THREE.Vector3());
          const hit = makeHit('bleed', dmg, 0, 0, 0, b.byPlayer, b.byPlayer ? 'player' : null, point);
          hit.ignoreGuard = true;
          deliver(b.target, hit, null, false);
        }
      }
      if (b.left <= 1e-9) bleeds.splice(i, 1);
    }
  }

  // Stuck spears cast a shadow only near the camera: farther out the 2-call shadow draw of a
  // 2.5 m shaft buys nothing a player notices (DESIGN.md §11 shadow budget).
  function updateSpearShadows() {
    const cam = ctx.camera;
    if (!cam) return;
    const cp = cam.position;
    const r2 = SPEAR_SHADOW_DIST * SPEAR_SHADOW_DIST;
    for (let i = 0; i < stuckSpears.length; i++) {
      const m = stuckSpears[i].mesh;
      if (!m) continue;
      const e = m.matrixWorld.elements;
      const dx = e[12] - cp.x;
      const dy = e[13] - cp.y;
      const dz = e[14] - cp.z;
      m.castShadow = dx * dx + dy * dy + dz * dz <= r2;
    }
  }

  // ---- lifecycle ----------------------------------------------------------------------------

  function update(gdt) {
    const dt = Number.isFinite(gdt) && gdt > 0 ? gdt : 0;
    if (dt > 0) {
      stepProjectiles(dt);
      stepVolleys(dt);
      stepBleeds(dt);
    }
    // Visual volley arrows (skipped while the game is frozen).
    const moved = dt > 0 && stepVolleyArrows();
    const count = flyingActive > 0 || flyingHigh > 0 ? GROUND_ARROWS + flyingHigh : groundCount;
    if (count !== volleyMesh.count || moved) {
      volleyMesh.count = count;
      volleyMesh.instanceMatrix.needsUpdate = true;
    }
    updateSpearShadows();
  }

  /** Removes every projectile, stuck spear and arrow, volley and bleed. */
  function reset() {
    for (const list of [flying, sinking, stuckSpears, stuckArrows]) {
      for (const p of list) if (p.mesh) p.mesh.removeFromParent();
      list.length = 0;
    }
    bleeds.length = 0;
    volleys.length = 0;
    fl.on.fill(0);
    fl.vol.fill(null);
    flyingActive = 0;
    flyingHigh = 0;
    groundNext = 0;
    groundCount = 0;
    for (let i = 0; i < GROUND_ARROWS + VOLLEY_SLOTS; i++) volleyMesh.setMatrixAt(i, ZERO_SCALE);
    volleyMesh.count = 0;
    volleyMesh.instanceMatrix.needsUpdate = true;
    streaks.commit(0);
  }

  return {
    playerStrike,
    actorStrike,
    throwSpear,
    shootArrow,
    volley,
    hostilesInSector,
    reset,
    update,
    /** Debug/test views (read-only). */
    get projectiles() {
      return flying;
    },
    get stuckSpears() {
      return stuckSpears;
    },
    get activeVolleys() {
      return volleys.length;
    },
    get volleyMesh() {
      return volleyMesh;
    },
  };
}
