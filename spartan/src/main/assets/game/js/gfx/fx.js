/**
 * gfx/fx.js (DESIGN.md §11): particles, decals, camera shake, hit-stop, slow motion, the spear
 * cam and gameplay haptics. fx is the only writer of ctx.time.scale, hitStop and playerRealtime.
 *
 * Particles and decals advance with game time (gdt) and freeze during hit-stop and pause.
 * Shake, slow-mo timers and the spear cam advance with real time (dt), which is 0 while paused.
 */
import * as THREE from 'three';
import { clamp, lerp, smoothstep } from '../core/math.js';
import { TimeControl, SPEAR_CAM, HITSTOP_REARM } from './fx/timectl.js';
import { Shake } from './fx/shake.js';
import { createParticles, PARTICLE_CAP } from './fx/particles.js';
import { createDecals, DECAL_CAP } from './fx/decals.js';

export { TimeControl, SPEAR_CAM, HITSTOP_REARM, Shake, PARTICLE_CAP, DECAL_CAP };

/** Hit-stop durations in seconds (DESIGN.md §11). */
export const HITSTOP = Object.freeze({ hit: 0.05, kill: 0.085, crit: 0.085, block: 0.035, perfect: 0.11, hurt: 0.06 });

/** Shake [radians, seconds], halved in motion mode. block and perfect are fx's own additions. */
export const SHAKE = Object.freeze({
  hit: Object.freeze([0.006, 0.12]),
  kill: Object.freeze([0.01, 0.2]),
  bash: Object.freeze([0.012, 0.18]),
  hurt: Object.freeze([0.025, 0.25]),
  guardBreak: Object.freeze([0.035, 0.3]),
  block: Object.freeze([0.008, 0.14]),
  perfect: Object.freeze([0.012, 0.2]),
});

/** Gameplay haptics: [ms, strength] pulses or vibration patterns (DESIGN.md §11 table). */
export const HAPTICS = Object.freeze({
  fleshHit: Object.freeze([22, 0.7]),
  kill: Object.freeze([40, 1.0]),
  guarded: Object.freeze([0, 12, 30, 12]),
  block: Object.freeze([30, 0.8]),
  perfect: Object.freeze([0, 15, 25, 45]),
  hurt: Object.freeze([70, 1.0]),
  shieldUp: Object.freeze([6, 0.3]),
  arrowShield: Object.freeze([12, 0.5]),
  spearRelease: Object.freeze([0, 25, 60, 25]),
  spearImpact: Object.freeze([0, 30, 40, 60]),
  volleyWarning: Object.freeze([0, 20, 90, 20]),
});

const CAM_START = Object.freeze({ phase: 'start' });
const CAM_IMPACT = Object.freeze({ phase: 'impact' });
const CAM_END = Object.freeze({ phase: 'end' });

const ORBIT_SWEEP = 1.15; // radians the camera travels around the impact
const PASS_MIN_X = -8.3; // keep the spear cam out of the cliff
const JOBS = 8;
const EMPTY = Object.freeze([]);

const smoother = (t) => t * t * t * (t * (t * 6 - 15) + 10);

/**
 * Creates the fx module (DESIGN.md §11 gfx/fx.js).
 * @param {object} ctx the shared context (§4)
 */
export function createFx(ctx) {
  const { scene, events } = ctx;
  const time = ctx.time;
  const tc = new TimeControl(time);
  const shake = new Shake(1234);
  const decals = createDecals(ctx);
  let landSeed = 0;
  const particles = createParticles(ctx, {
    onBloodLand(x, y, z, width) {
      if (bloodOff()) return;
      landSeed = (landSeed + 0.618034) % 1;
      decals.addXYZ(x, y, z, 'spot', width * (4 + 4 * landSeed));
    },
  });

  // --- scratch (no per-frame allocation) ---
  const UP = new THREE.Vector3(0, 1, 0);
  const vA = new THREE.Vector3();
  const vB = new THREE.Vector3();
  const vC = new THREE.Vector3();
  const qA = new THREE.Quaternion();
  const mA = new THREE.Matrix4();
  const eA = new THREE.Euler(0, 0, 0, 'YXZ');
  const hitDir = { x: 0, y: 0, z: 0 };

  let gclock = 0; // game seconds since creation (particles, decals, splash dedupe)

  // --- helpers ---------------------------------------------------------------------------------

  function bloodOff() {
    return !!(ctx.settings && ctx.settings.blood === false);
  }

  function ground(x, z) {
    const w = ctx.world;
    return w && w.groundHeight ? w.groundHeight(x, z) : 0;
  }

  function motionMul() {
    return ctx.input && ctx.input.motion ? 0.5 : 1;
  }

  function haptic(entry) {
    try {
      const h = ctx.platform && ctx.platform.haptics;
      if (!h) return;
      if (entry.length === 2) h.pulse(entry[0], entry[1]);
      else h.pattern(entry.slice()); // a mutable copy of the frozen row (events are rare)
    } catch (err) {
      // Haptics are a nicety; never let them break an event handler.
    }
  }

  function addShake(entry) {
    shake.add(entry[0] * motionMul(), entry[1]);
  }

  /** Writes the event point (or the target's chest) into `out`. Returns false if none. */
  function pointOf(p, target, out) {
    if (p && Number.isFinite(p.x)) {
      out.set(p.x, p.y, p.z);
      return true;
    }
    if (target && typeof target === 'object') {
      if (typeof target.chestPoint === 'function') {
        target.chestPoint(out);
        return true;
      }
      if (target.pos) {
        out.set(target.pos.x, target.pos.y + 1.25, target.pos.z);
        return true;
      }
    }
    return false;
  }

  /** hit.dir {x,z} (attacker → target) into hitDir, with a fallback from the attacker position. */
  function readHitDir(e, target) {
    let x = 0;
    let z = 0;
    if (e.dir && Number.isFinite(e.dir.x)) {
      x = e.dir.x;
      z = e.dir.z;
    } else if (target && target.pos) {
      const src = e.attacker === 'player' ? ctx.camera.position : e.attacker && e.attacker.pos;
      if (src) {
        x = target.pos.x - src.x;
        z = target.pos.z - src.z;
      }
    }
    const l = Math.hypot(x, z);
    hitDir.x = l > 1e-6 ? x / l : 0;
    hitDir.y = 0;
    hitDir.z = l > 1e-6 ? z / l : 1;
    return hitDir;
  }

  /** Direction from `point` back toward whoever attacked (for sparks and splinters). */
  function towardAttacker(attacker, point, out) {
    const src = attacker === 'player' ? ctx.camera.position : attacker && attacker.pos;
    if (src) {
      out.set(src.x - point.x, 0, src.z - point.z);
      if (out.lengthSq() < 1e-6) out.set(0, 0, 1);
      out.normalize();
    } else {
      ctx.camera.getWorldDirection(out).negate();
      out.y = 0;
      if (out.lengthSq() < 1e-6) out.set(0, 0, 1);
      out.normalize();
    }
    out.y = 0.35;
    return out;
  }

  /** What an actor parries with: 'bronze' (aspis), 'wicker' (gerron, spara) or 'weapon'. */
  function guardOf(a) {
    if (!a || typeof a !== 'object') return 'weapon';
    const K = ctx.characters && ctx.characters.KINDS;
    const shield = K && K[a.kind] ? K[a.kind].shield : undefined;
    if (shield !== undefined) return shield === 'aspis' ? 'bronze' : shield ? 'wicker' : 'weapon';
    if (a.kind === 'spartan') return 'bronze';
    return a.kind === 'captain' || a.kind === 'archer' || a.kind === 'helot' ? 'weapon' : 'wicker';
  }
  const isBronze = (a) => guardOf(a) === 'bronze';
  const isWicker = (a) => guardOf(a) === 'wicker';

  // --- emitters (public) ---------------------------------------------------------------------

  let lastSplashT = -1e9;
  const lastSplash = new THREE.Vector3();
  function splash(point, amount = 1) {
    if (!point) return;
    const x = Array.isArray(point) ? point[0] : point.x;
    const y = Array.isArray(point) ? point[1] : point.y;
    const z = Array.isArray(point) ? point[2] : point.z;
    if (!Number.isFinite(x) || !Number.isFinite(z)) return;
    const dx = x - lastSplash.x;
    const dz = z - lastSplash.z;
    if (gclock - lastSplashT < 0.3 && dx * dx + dz * dz < 2.25) return; // the same splash, reported twice
    lastSplashT = gclock;
    lastSplash.set(x, Number.isFinite(y) ? y : -3, z);
    particles.splash(lastSplash, amount);
  }

  // --- delayed effects: the dust of a falling body ------------------------------------------

  const jobT = new Float32Array(JOBS);
  const jobX = new Float32Array(JOBS);
  const jobY = new Float32Array(JOBS);
  const jobZ = new Float32Array(JOBS);
  const jobA = new Float32Array(JOBS);
  let jobCursor = 0;
  function scheduleDust(x, y, z, amount, delay) {
    let slot = -1;
    for (let i = 0; i < JOBS; i++) {
      if (jobT[i] <= 0) {
        slot = i;
        break;
      }
    }
    if (slot < 0) {
      slot = jobCursor;
      jobCursor = (jobCursor + 1) % JOBS;
    }
    jobT[slot] = delay;
    jobX[slot] = x;
    jobY[slot] = y;
    jobZ[slot] = z;
    jobA[slot] = amount;
  }

  function updateDelayed(gdt) {
    if (!(gdt > 0)) return;
    for (let i = 0; i < JOBS; i++) {
      if (jobT[i] <= 0) continue;
      jobT[i] -= gdt;
      if (jobT[i] <= 0) {
        vA.set(jobX[i], jobY[i], jobZ[i]);
        particles.dust(vA, jobA[i]);
      }
    }
  }

  // --- lights for particle shading -------------------------------------------------------------

  let sunL = null;
  let hemiL = null;
  let ambL = null;
  let scanLeft = 0;
  const scanFn = (o) => {
    if (o.isDirectionalLight) {
      if (!sunL || (o.castShadow && !sunL.castShadow)) sunL = o;
    } else if (o.isHemisphereLight) {
      if (!hemiL) hemiL = o;
    } else if (o.isAmbientLight) {
      if (!ambL) ambL = o;
    }
  };
  /** The world's sun and sky light (world.sun/hemi), else whatever lights the scene has. */
  function findLights() {
    sunL = hemiL = ambL = null;
    const w = ctx.world;
    if (w && w.sun && w.sun.isDirectionalLight) {
      sunL = w.sun;
      if (w.hemi && w.hemi.isHemisphereLight) hemiL = w.hemi;
      return true;
    }
    scene.traverse(scanFn);
    return !!(sunL && (hemiL || ambL));
  }
  function refreshLights(dt) {
    scanLeft -= dt;
    if (scanLeft <= 0) scanLeft = findLights() ? 5 : 1;
    const U = particles.uniforms;
    if (sunL) {
      if (sunL.visible) U.uSun.value.copy(sunL.color).multiplyScalar(sunL.intensity);
      else U.uSun.value.setRGB(0, 0, 0);
    }
    if (hemiL || ambL) {
      const amb = U.uAmb.value;
      amb.setRGB(0, 0, 0);
      if (hemiL && hemiL.visible) {
        const k = hemiL.intensity;
        amb.r += (hemiL.color.r * 0.62 + hemiL.groundColor.r * 0.38) * k;
        amb.g += (hemiL.color.g * 0.62 + hemiL.groundColor.g * 0.38) * k;
        amb.b += (hemiL.color.b * 0.62 + hemiL.groundColor.b * 0.38) * k;
      }
      if (ambL && ambL.visible) {
        amb.r += ambL.color.r * ambL.intensity;
        amb.g += ambL.color.g * ambL.intensity;
        amb.b += ambL.color.b * ambL.intensity;
      }
    }
    const w = ctx.world;
    if (w && w.sunDirection) U.uSunDir.value.copy(w.sunDirection);
    else if (sunL) U.uSunDir.value.subVectors(sunL.position, sunL.target.position).normalize();
  }

  // --- spear cam -------------------------------------------------------------------------------

  let camProj = null;
  const camOrigin = new THREE.Vector3();
  const velDir = new THREE.Vector3(0, 0, -1);
  const trailPos = new THREE.Vector3();
  const trailQuat = new THREE.Quaternion();
  let trailValid = false;
  const lastPos = new THREE.Vector3(); // last pose the cam wrote (before shake)
  const lastQuat = new THREE.Quaternion();
  let lastValid = false;
  const easeFromPos = new THREE.Vector3();
  const easeFromQuat = new THREE.Quaternion();
  let easeFromValid = false;
  const orbitCenter = new THREE.Vector3();
  const orbitStartPos = new THREE.Vector3();
  const orbitStartQuat = new THREE.Quaternion();
  const orbitPos = new THREE.Vector3();
  const orbitQuat = new THREE.Quaternion();
  let orbitInit = false;
  let orbitA0 = 0;
  let orbitR0 = 2;
  let orbitR1 = 2;
  let orbitH0 = 0.4;
  let orbitSign = 1;

  /**
   * Predicted game seconds until the spear strikes something: ballistic steps of its tip (combat
   * collides the tip, `projectile.tip` metres ahead of pos) against the ground, the world's
   * colliders and living Persians.
   */
  function predictFlight(pr) {
    if (!pr || !pr.pos || !pr.vel) return 0;
    const tip = Number.isFinite(pr.tip) ? pr.tip : 0;
    let x = pr.pos.x;
    let y = pr.pos.y;
    let z = pr.pos.z;
    const vx = pr.vel.x;
    let vy = pr.vel.y;
    const vz = pr.vel.z;
    let sp = Math.hypot(vx, vy, vz) || 1;
    let tx = x + (vx / sp) * tip;
    let ty = y + (vy / sp) * tip;
    let tz = z + (vz / sp) * tip;
    const h = 1 / 60;
    const tMax = Math.max(0, SPEAR_CAM.maxAge - (pr.age || 0));
    const actors = ctx.actors || EMPTY;
    const cols = (ctx.world && ctx.world.colliders) || EMPTY;
    for (let t = 0; t < tMax; t += h) {
      const x0 = tx;
      const y0 = ty;
      const z0 = tz;
      vy -= 9.81 * h;
      x += vx * h;
      y += vy * h;
      z += vz * h;
      sp = Math.hypot(vx, vy, vz) || 1;
      tx = x + (vx / sp) * tip;
      ty = y + (vy / sp) * tip;
      tz = z + (vz / sp) * tip;
      if (y < SPEAR_CAM.minY || ty <= ground(tx, tz)) return t + h;
      if (insideCollider(cols, tx, ty, tz)) return t + h;
      const sx = tx - x0;
      const sz = tz - z0;
      const l2 = sx * sx + sz * sz;
      for (let i = 0; i < actors.length; i++) {
        const a = actors[i];
        if (!a || !a.alive || a.team === 'greek' || !a.pos) continue;
        let k = l2 > 1e-9 ? ((a.pos.x - x0) * sx + (a.pos.z - z0) * sz) / l2 : 0;
        k = k < 0 ? 0 : k > 1 ? 1 : k;
        const dx = x0 + sx * k - a.pos.x;
        const dz = z0 + sz * k - a.pos.z;
        const py = y0 + (ty - y0) * k;
        const rr = (a.radius || 0.32) + 0.06;
        if (dx * dx + dz * dz <= rr * rr && py >= a.pos.y && py <= a.pos.y + (a.height || 1.8)) return t + h * k;
      }
    }
    return tMax;
  }
  const comp = (v, i) => (Array.isArray(v) ? v[i] : i === 0 ? v.x : i === 1 ? v.y : v.z);
  /** True if the point is inside a box collider or behind a plane collider (§11 world). */
  function insideCollider(cols, x, y, z) {
    for (let i = 0; i < cols.length; i++) {
      const c = cols[i];
      if (!c) continue;
      if (c.type === 'box' && c.min && c.max) {
        if (x >= comp(c.min, 0) && x <= comp(c.max, 0) && y >= comp(c.min, 1) && y <= comp(c.max, 1) &&
          z >= comp(c.min, 2) && z <= comp(c.max, 2)) return true;
      } else if (c.type === 'plane' && c.point && c.normal) {
        const d = (x - comp(c.point, 0)) * comp(c.normal, 0) + (y - comp(c.point, 1)) * comp(c.normal, 1) +
          (z - comp(c.point, 2)) * comp(c.normal, 2);
        if (d < 0) return true;
      }
    }
    return false;
  }
  const remainingFlight = () => (camProj ? predictFlight(camProj) : 0);

  function startSpearCam(pr) {
    if (tc.camActive || !pr || !pr.pos || !pr.vel) return;
    camProj = pr;
    camOrigin.copy(pr.pos);
    const sp = pr.vel.length();
    if (sp > 0.1) velDir.copy(pr.vel).multiplyScalar(1 / sp);
    trailValid = lastValid = easeFromValid = orbitInit = false;
    tc.camStart(pr.src === 'motion', predictFlight(pr), remainingFlight);
    computeTrail(0);
    events.emit('spearCam', CAM_START);
  }

  function beginEase() {
    easeFromValid = lastValid;
    if (lastValid) {
      easeFromPos.copy(lastPos);
      easeFromQuat.copy(lastQuat);
    }
  }

  function finishCam() {
    camProj = null;
    trailValid = lastValid = easeFromValid = orbitInit = false;
    events.emit('spearCam', CAM_END);
  }

  function clampCam(p, minLift) {
    if (p.x < PASS_MIN_X) p.x = PASS_MIN_X;
    const gy = ground(p.x, p.z);
    if (p.y < gy + minLift) p.y = gy + minLift;
  }

  function lookQuat(from, to, out) {
    mA.lookAt(from, to, UP);
    return out.setFromRotationMatrix(mA);
  }

  /** Trail: behind and above the spear, looking a little ahead of it. */
  function computeTrail(dt) {
    const pr = camProj;
    if (!pr || !pr.pos) return;
    if (pr.vel) {
      const sp = pr.vel.length();
      if (sp > 0.5) velDir.copy(pr.vel).multiplyScalar(1 / sp);
    }
    const back = Math.min(1.6, pr.pos.distanceTo(camOrigin));
    vA.copy(pr.pos).addScaledVector(velDir, -back).addScaledVector(UP, 0.35 * smoothstep(0, 0.25, tc.camT));
    clampCam(vA, 0.3);
    vB.copy(pr.pos).addScaledVector(velDir, 5);
    lookQuat(vA, vB, qA);
    if (!trailValid || !(dt > 0)) trailQuat.copy(qA);
    else trailQuat.slerp(qA, 1 - Math.exp(-18 * dt));
    trailPos.copy(vA);
    trailValid = true;
  }

  function orbitPoint(a, r, h, out) {
    return out.set(orbitCenter.x + Math.sin(a) * r, orbitCenter.y + h, orbitCenter.z + Math.cos(a) * r);
  }

  function scoreOrbitEnd(p) {
    let s = -0.15 * Math.abs(p.x) + 0.1 * p.z;
    if (p.x < PASS_MIN_X + 0.2) s -= 10;
    if (ground(p.x, p.z) > p.y - 0.3) s -= 10;
    return s;
  }

  function initOrbit() {
    if (lastValid) {
      orbitStartPos.copy(lastPos);
      orbitStartQuat.copy(lastQuat);
    } else {
      orbitStartPos.copy(trailPos);
      orbitStartQuat.copy(trailQuat);
    }
    vA.subVectors(orbitStartPos, orbitCenter);
    orbitR0 = clamp(Math.hypot(vA.x, vA.z), 1.2, 2.6);
    orbitA0 = Math.atan2(vA.x, vA.z);
    orbitH0 = clamp(vA.y, 0.1, 0.9);
    orbitR1 = clamp(orbitR0 * 0.9 + 0.2, 1.6, 2.4);
    const sPlus = scoreOrbitEnd(orbitPoint(orbitA0 + ORBIT_SWEEP, orbitR1, 0.45, vB));
    const sMinus = scoreOrbitEnd(orbitPoint(orbitA0 - ORBIT_SWEEP, orbitR1, 0.45, vB));
    orbitSign = sPlus >= sMinus ? 1 : -1;
    orbitInit = true;
  }

  /** Impact: hold during the freeze, then circle the impact point while looking at it. */
  function computeOrbit() {
    if (!orbitInit) initOrbit();
    const pt = tc.phaseT;
    const F = SPEAR_CAM.freeze;
    if (pt < F) {
      orbitPos.lerpVectors(orbitStartPos, orbitCenter, 0.08 * smoothstep(0, F, pt));
      orbitQuat.copy(orbitStartQuat);
      return;
    }
    const u = clamp((pt - F) / SPEAR_CAM.orbit, 0, 1);
    const e = smoother(u);
    orbitPoint(orbitA0 + orbitSign * ORBIT_SWEEP * e, lerp(orbitR0, orbitR1, e), lerp(orbitH0, 0.45, e), vA);
    vB.lerpVectors(orbitStartPos, orbitCenter, 0.08);
    orbitPos.lerpVectors(vB, vA, smoothstep(0, 0.3, u));
    clampCam(orbitPos, 0.3);
    vC.copy(orbitCenter);
    vC.y += 0.05;
    lookQuat(orbitPos, vC, qA);
    orbitQuat.slerpQuaternions(orbitStartQuat, qA, smoothstep(0, 0.4, u));
  }

  // --- event handlers --------------------------------------------------------------------------

  const hitP = new THREE.Vector3();
  const nrm = new THREE.Vector3();
  const spray = new THREE.Vector3();
  let lastSwingKind = '';
  let lastSwingDir = 0;
  let lastSwingT = -1e9;
  let sideFlip = 1;

  /**
   * Which way the blood flies: along the blade for the player's slashes (the swing's screen
   * direction), back out of the wound for thrusts, out of the back for thrown spears, and
   * sideways for everyone else. `d` is the hit direction (attacker → target, unit XZ).
   */
  function sprayDir(e, d, out) {
    const kind = e.kind;
    if (kind === 'thrust' || kind === 'spearThrust') return out.set(-d.x, 0.45, -d.z).normalize();
    if (e.byPlayer && lastSwingKind === 'slash' && time.now - lastSwingT < 0.6) {
      const yaw = ctx.camera.rotation.y;
      const c = Math.cos(lastSwingDir);
      const sn = Math.sin(lastSwingDir);
      out.set(Math.cos(yaw) * c * 0.9 - d.x * 0.35, sn * 0.9 + 0.2, -Math.sin(yaw) * c * 0.9 - d.z * 0.35);
      return out.normalize();
    }
    sideFlip = -sideFlip;
    return out.set(-d.z * sideFlip * 0.9 - d.x * 0.35, 0.3, d.x * sideFlip * 0.9 - d.z * 0.35).normalize();
  }

  function onSwing(e) {
    if (!e) return;
    lastSwingKind = e.kind || '';
    lastSwingDir = Number.isFinite(e.dir) ? e.dir : 0;
    lastSwingT = time.now;
  }
  let lastHitTarget = null;
  let lastHitDx = 0;
  let lastHitDz = 1;
  let lastArrowDustT = -1e9;

  /** A hit by a thrown spear (combat may name the kind 'spear' or 'throw'). */
  function isThrownSpear(e, tgt) {
    const k = e.kind;
    if (k === 'spear' || k === 'throw' || k === 'thrown') return true;
    const pr = camProj;
    return !!pr && k !== 'spearThrust' && (pr.target === tgt || (Array.isArray(pr.hits) && pr.hits.indexOf(tgt) >= 0));
  }

  function onHit(e) {
    if (!e || !e.target || typeof e.target !== 'object') return;
    const tgt = e.target;
    const kind = e.kind;
    const bleed = kind === 'bleed';
    const spear = isThrownSpear(e, tgt);
    if (!pointOf(e.point, tgt, hitP)) return;
    const d = readHitDir(e, tgt);
    lastHitTarget = tgt;
    lastHitDx = d.x;
    lastHitDz = d.z;
    if (bleed) {
      if (!bloodOff()) particles.blood(hitP, d, 0.2);
    } else if (e.guarded) {
      nrm.set(-d.x, 0.35, -d.z);
      if (isBronze(tgt)) particles.sparks(hitP, nrm, 0.8);
      else if (isWicker(tgt)) {
        particles.splinters(hitP, nrm, 0.8);
      } else particles.sparks(hitP, nrm, 0.5);
    } else if (kind === 'bash') {
      particles.dust(hitP, 0.35);
      if (isWicker(tgt)) particles.splinters(hitP, d, 0.5);
    } else if (!bloodOff()) {
      let amount = clamp((+e.damage || 30) / 34, 0.35, 2);
      if (e.critical) amount += 0.4;
      if (e.killed) amount += 0.3;
      if (spear) amount = Math.max(amount, 1.8);
      if (spear) {
        // A thrown spear goes through: a spray out of the back and a spurt back along the shaft.
        hitP.x += d.x * 0.12;
        hitP.z += d.z * 0.12;
        particles.blood(hitP, spray.set(d.x, 0.2, d.z).normalize(), amount * 0.6);
        hitP.x -= d.x * 0.26;
        hitP.z -= d.z * 0.26;
        particles.blood(hitP, spray.set(-d.x, 0.7, -d.z).normalize(), amount * 0.5);
      } else {
        // Start on the body's surface facing the attacker.
        hitP.x -= d.x * 0.07;
        hitP.z -= d.z * 0.07;
        particles.blood(hitP, sprayDir(e, d, spray), amount);
      }
      if (e.killed || e.critical || spear) {
        // A heavy blow also splashes the ground just beyond the victim.
        const t = tgt.pos;
        if (t) decals.addXYZ(t.x + d.x * 0.6, t.y, t.z + d.z * 0.6, 'blood', 0.35 + 0.15 * amount, 0.25);
      }
    }
    if (!e.byPlayer || bleed) return;
    if (e.killed) {
      tc.hitStop(HITSTOP.kill); // kill does the shake and haptic
      return;
    }
    tc.hitStop(e.critical ? HITSTOP.crit : HITSTOP.hit);
    addShake(kind === 'bash' ? SHAKE.bash : SHAKE.hit);
    if (!spear) haptic(e.guarded ? HAPTICS.guarded : HAPTICS.fleshHit);
  }

  function onKill(e) {
    if (!e || !e.target || typeof e.target !== 'object') return;
    const tgt = e.target;
    const cause = e.cause || 'weapon';
    // 'sea': ai.js splashes when the body reaches the water (§7 Into the Gulf).
    if (cause !== 'sea' && tgt.pos) {
      let fx = lastHitDx;
      let fz = lastHitDz;
      if (lastHitTarget !== tgt) {
        const f = tgt.facing || 0;
        fx = -Math.sin(f);
        fz = -Math.cos(f);
      }
      const p = tgt.pos;
      if (!bloodOff()) {
        if (cause !== 'bleed' && pointOf(null, tgt, hitP)) {
          hitDir.x = fx;
          hitDir.y = 0;
          hitDir.z = fz;
          particles.blood(hitP, hitDir, 0.8);
        }
        decals.addXYZ(p.x + fx * 0.75, p.y, p.z + fz * 0.75, 'pool', 1.1 + 0.35 * ((gclock * 7.3) % 1), 0.9);
      }
      scheduleDust(p.x + fx * 0.9, p.y + 0.05, p.z + fz * 0.9, 0.9, 0.75);
    }
    if (tgt === lastHitTarget) lastHitTarget = null;
    if (!e.byPlayer) return;
    if (cause !== 'bleed') {
      tc.hitStop(HITSTOP.kill);
      addShake(SHAKE.kill);
    }
    if (!e.spear) haptic(HAPTICS.kill);
  }

  function onBlock(e) {
    if (!e) return;
    const byPlayer = e.by === 'player';
    const by = byPlayer ? null : e.by;
    if (!pointOf(e.point, by, hitP)) {
      if (!byPlayer) return;
      // In front of the raised aspis.
      ctx.camera.getWorldDirection(vA);
      hitP.copy(ctx.camera.position).addScaledVector(vA, 0.75);
      hitP.y -= 0.15;
    }
    towardAttacker(e.attacker, hitP, nrm);
    const guardBreak = e.kind === 'guardBreak';
    if (byPlayer || isBronze(by)) particles.sparks(hitP, nrm, e.perfect ? 1.6 : guardBreak ? 1.3 : 1);
    else if (isWicker(by)) particles.splinters(hitP, nrm, 0.8);
    else particles.sparks(hitP, nrm, 0.6);
    if (!byPlayer) return;
    if (e.perfect) {
      tc.hitStop(HITSTOP.perfect);
      addShake(SHAKE.perfect);
      haptic(HAPTICS.perfect);
    } else if (guardBreak) {
      tc.hitStop(HITSTOP.block); // player.guardBreak() pushes the view itself
      addShake(SHAKE.guardBreak);
      haptic(HAPTICS.block);
    } else {
      tc.hitStop(HITSTOP.block);
      addShake(SHAKE.block);
      haptic(HAPTICS.block);
    }
  }

  function onPlayerHurt() {
    // player.applyCamera adds the directional flinch; fx adds the hit-stop and the shake.
    tc.hitStop(HITSTOP.hurt);
    addShake(SHAKE.hurt);
    haptic(HAPTICS.hurt);
  }

  function onSpearImpact(e) {
    if (!e) return;
    const pr = e.projectile;
    const p = e.point || (pr && (pr.impactPoint || pr.pos));
    if (p) {
      if (e.water) {
        splash(p, 0.7);
      } else if (!e.target) {
        hitP.set(p.x, p.y, p.z);
        if (e.normal && Number.isFinite(e.normal.x)) nrm.set(e.normal.x, e.normal.y, e.normal.z);
        else nrm.set(0, 1, 0);
        if (hitP.y > ground(hitP.x, hitP.z) + 0.35) {
          particles.sparks(hitP, nrm, 0.6); // stone (wall, cliff, rocks)
          particles.dust(hitP, 0.45);
        } else {
          particles.dust(hitP, 0.9);
          if (!bloodOff()) decals.addXYZ(hitP.x, hitP.y, hitP.z, 'scuff', 0.55);
        }
      }
    }
    if (pr && pr.byPlayer && !e.water) haptic(HAPTICS.spearImpact);
    if (!tc.camActive || !pr || pr !== camProj) return;
    if (e.target && !e.water && tc.camPhase === 'trail') {
      if (p) orbitCenter.set(p.x, p.y, p.z);
      else orbitCenter.copy(pr.pos);
      orbitInit = false;
      if (tc.camImpact()) events.emit('spearCam', CAM_IMPACT);
    } else if (tc.camPhase === 'trail') {
      if (tc.camEase()) beginEase();
    }
  }

  function onArrowHit(e) {
    if (!e) return;
    const tg = e.target;
    if (tg === 'player') return; // playerHurt covers it
    if (!tg && e.blocked && e.by && typeof e.by === 'object') {
      // An ally caught it on his aspis (combat: { target: null, blocked: true, by: actor }).
      if (!pointOf(e.point, e.by, hitP) || hitP.distanceToSquared(ctx.camera.position) > 900) return;
      nrm.set(0, 0.8, -0.6); // the arrows come down from the Persian side
      if (isBronze(e.by)) particles.sparks(hitP, nrm, 0.3);
      else particles.splinters(hitP, nrm, 0.25);
      return;
    }
    if (tg === 'shield') {
      haptic(HAPTICS.arrowShield);
      if (!pointOf(e.point, null, hitP)) {
        ctx.camera.getWorldDirection(vA);
        hitP.copy(ctx.camera.position).addScaledVector(vA, 0.8);
      }
      ctx.camera.getWorldDirection(nrm).negate();
      nrm.y += 0.4;
      particles.sparks(hitP, nrm, 0.3);
      return;
    }
    if (!pointOf(e.point, typeof tg === 'object' ? tg : null, hitP)) return;
    if (tg && typeof tg === 'object') {
      nrm.set(0, 1, 0);
      if (isBronze(tg)) particles.sparks(hitP, nrm, 0.3);
      else particles.splinters(hitP, nrm, 0.25);
    } else if (gclock - lastArrowDustT > 0.05 && hitP.distanceToSquared(ctx.camera.position) < 900) {
      lastArrowDustT = gclock;
      particles.dust(hitP, 0.2);
    }
  }

  function onCombo(e) {
    if (e && Array.isArray(e.slowMo) && e.slowMo.length >= 2) tc.slowMo(e.slowMo[0], e.slowMo[1], true);
  }

  function onShield(e) {
    if (e && e.up) haptic(HAPTICS.shieldUp);
  }

  function onSpearThrown(e) {
    const pr = e && e.projectile;
    if (!pr || !pr.byPlayer) return;
    haptic(HAPTICS.spearRelease);
    startSpearCam(pr);
  }

  function onVolley(e) {
    if (e && e.phase === 'warning') haptic(HAPTICS.volleyWarning);
  }

  events.on('hit', onHit);
  events.on('kill', onKill);
  events.on('block', onBlock);
  events.on('playerHurt', onPlayerHurt);
  events.on('spearImpact', onSpearImpact);
  events.on('arrowHit', onArrowHit);
  events.on('combo', onCombo);
  events.on('shield', onShield);
  events.on('spearThrown', onSpearThrown);
  events.on('volley', onVolley);
  events.on('swing', onSwing); // only to aim blood along the player's blade

  // --- camera ----------------------------------------------------------------------------------

  let shakeWritten = false;
  let wroteX = 0;
  let wroteY = 0;
  let wroteZ = 0;
  let offX = 0;
  let offY = 0;
  let offZ = 0;

  function applyCamera(camera) {
    if (!camera) return;
    const phase = tc.camPhase;
    if (phase !== 'off') {
      // The camera holds the player's eye pose for this frame (player.applyCamera ran first).
      if (phase === 'trail') {
        if (trailValid) {
          const k = smoother(clamp(tc.camT / SPEAR_CAM.blendIn, 0, 1));
          vA.lerpVectors(camera.position, trailPos, k);
          qA.slerpQuaternions(camera.quaternion, trailQuat, k);
        } else {
          vA.copy(camera.position);
          qA.copy(camera.quaternion);
        }
      } else if (phase === 'impact') {
        if (!orbitInit) computeOrbit();
        vA.copy(orbitPos);
        qA.copy(orbitQuat);
      } else {
        const k = smoother(clamp(tc.phaseT / SPEAR_CAM.ease, 0, 1));
        if (easeFromValid) {
          vA.lerpVectors(easeFromPos, camera.position, k);
          qA.slerpQuaternions(easeFromQuat, camera.quaternion, k);
        } else {
          vA.copy(camera.position);
          qA.copy(camera.quaternion);
        }
      }
      camera.position.copy(vA);
      camera.quaternion.copy(qA);
      lastPos.copy(vA);
      lastQuat.copy(qA);
      lastValid = true;
      if (shake.x !== 0 || shake.y !== 0 || shake.z !== 0) {
        eA.set(shake.x, shake.y, shake.z, 'YXZ');
        camera.quaternion.multiply(qA.setFromEuler(eA));
      }
      shakeWritten = false;
      return;
    }
    // Rotational shake on top of the absolute camera the player set. If nobody re-set the camera
    // since our last write, take our previous offset off first so shake never accumulates.
    const r = camera.rotation;
    let bx = r.x;
    let by = r.y;
    let bz = r.z;
    if (shakeWritten && bx === wroteX && by === wroteY && bz === wroteZ) {
      bx -= offX;
      by -= offY;
      bz -= offZ;
    }
    offX = shake.x;
    offY = shake.y;
    offZ = shake.z;
    if (offX === 0 && offY === 0 && offZ === 0) {
      if (shakeWritten) r.set(bx, by, bz);
      shakeWritten = false;
      return;
    }
    r.set(bx + offX, by + offY, bz + offZ);
    wroteX = r.x;
    wroteY = r.y;
    wroteZ = r.z;
    shakeWritten = true;
  }

  // --- update / reset --------------------------------------------------------------------------

  function update(dt, gdt) {
    dt = dt > 0 ? dt : 0;
    gdt = gdt > 0 ? gdt : 0;
    gclock += gdt;
    if (tc.camActive) {
      const mode = ctx.mode;
      if (mode && mode !== 'battle' && mode !== 'training' && mode !== 'paused') {
        // Left the battle (quit, game over): no lingering spear cam.
        if (tc.camEnd()) finishCam();
      }
    }
    let gone = false;
    const pr = camProj;
    if (tc.camPhase === 'trail' && pr) {
      gone = pr.alive === false || pr.stuck === true || pr.age > SPEAR_CAM.maxAge || !pr.pos || pr.pos.y < SPEAR_CAM.minY;
    }
    const settled = !ctx.input || ctx.input.phoneSettled !== false;
    const ev = tc.update(dt, settled, gone);
    if (ev === 'ease') beginEase();
    else if (ev === 'end') finishCam();
    if (dt > 0 && tc.camActive) {
      if (tc.camPhase === 'trail') computeTrail(dt);
      else if (tc.camPhase === 'impact') computeOrbit();
    }
    shake.update(dt);
    updateDelayed(gdt);
    particles.update(gdt);
    decals.update(gdt, dt);
    refreshLights(dt);
  }

  function reset() {
    const wasCam = tc.camActive;
    tc.reset();
    shake.reset();
    particles.reset();
    decals.reset();
    camProj = null;
    trailValid = lastValid = easeFromValid = orbitInit = false;
    shakeWritten = false;
    offX = offY = offZ = 0;
    jobT.fill(0);
    lastHitTarget = null;
    lastSplashT = -1e9;
    if (wasCam) events.emit('spearCam', CAM_END);
  }

  return {
    blood(point, dir, amount = 1) {
      if (!bloodOff()) particles.blood(point, dir, amount);
    },
    sparks(point, normal, amount = 1) {
      particles.sparks(point, normal, amount);
    },
    dust(point, amount = 1) {
      particles.dust(point, amount);
    },
    splinters(point, dir, amount = 1) {
      particles.splinters(point, dir, amount);
    },
    splash,
    /** Extra (not in §11): pale rising steam wisps. */
    steam(point, amount = 1) {
      particles.steam(point, amount);
    },
    decal(point, kind = 'blood', size) {
      if (bloodOff()) return;
      decals.add(point, kind, size);
    },
    shake(intensity, duration = 0.2) {
      shake.add((+intensity || 0) * motionMul(), +duration || 0.2);
    },
    hitStop(seconds) {
      return tc.hitStop(+seconds || 0);
    },
    slowMo(scale, realSeconds, opts) {
      return tc.slowMo(+scale, +realSeconds, !!(opts && opts.playerRealtime));
    },
    get cameraOverridden() {
      return tc.camActive;
    },
    get particleCount() {
      return particles.count;
    },
    /** Diagnostics for tests and the debug overlay. */
    get decalCount() {
      return decals.count;
    },
    get spearCamPhase() {
      return tc.camPhase;
    },
    get shakeOffset() {
      return shake;
    },
    applyCamera,
    update,
    reset,
  };
}
