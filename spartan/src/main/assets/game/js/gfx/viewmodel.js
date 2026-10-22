/**
 * First-person viewmodel (DESIGN.md §11): the right forearm and hand with the xiphos or the dory,
 * the left forearm carrying the aspis, stuck arrows, a slash motion-trail and the full-screen
 * overlay quad (blood vignette, hurt flash, Corinthian helmet slit).
 *
 * Everything lives in ctx.vm.scene, in vm.camera space (camera at the origin, -Z forward). The rig
 * is authored in real metres and drawn under a uniform scale about the eye (RIG.scale), which is
 * perspective-neutral. One-shots are event-driven and advance with ctx.time.pdt (frozen by
 * hit-stop); sway, bob and breathing use dt.
 *
 * Draw calls in the vm pass: arms (1, skinned), sword or spear (1), aspis bronze + wood (2),
 * stuck arrows (1 when any), trail (1 while visible), overlay (1 when active) — at most 7.
 */
import * as THREE from 'three';
import { TIMING } from '../game/rules.js';
import { clamp, wrapAngle, createRng } from '../core/math.js';
import {
  RIG, slashTimes, slashParam, slashGeometry, createSlashOut, slashEdgeSign,
  thrustCurve, bashCurve, jolt, bump, ramp, easeOutCubic, easeOutQuad, easeInOutSine, smootherstep,
} from './viewmodel/motion.js';
import {
  paintArmAtlas, paintWeaponAtlas, paintShieldBronze, paintShieldInterior, paintArrowAtlas, compositeBlazon,
} from './viewmodel/paint.js';
import {
  forearmParts, upperArmParts, fistParts, swordGeometry, spearGeometry, aspisGeometry, arrowGeometry,
  skinTo, mirrorX, aspisInnerZ, aspisOuterZ, ASPIS, PORPAX, ANTILABE, SWORD, SPEAR,
} from './viewmodel/meshes.js';
import { createOverlay } from './viewmodel/overlay.js';
import { createTrail } from './viewmodel/trail.js';
import { mergeGeometries } from 'three/addons/BufferGeometryUtils.js';

export { RIG } from './viewmodel/motion.js';

const MAX_ARROWS = 8;
const SWORD_REACH = SWORD.tipY; // grip centre → tip
const BLADE_BASE = SWORD.guardHi + 0.25; // trail inner edge along the blade

// ---------------------------------------------------------------------------------------------
// Poses (rig space). Sword: y = blade direction, z = wrist direction. Spear: y = toward the butt.

const v3 = (x, y, z) => new THREE.Vector3(x, y, z);
const _m = new THREE.Matrix4();
const _a = new THREE.Vector3();
const _b = new THREE.Vector3();
const _c = new THREE.Vector3();
const _d = new THREE.Vector3();
const _e = new THREE.Vector3();
const _q = new THREE.Quaternion();
const _q2 = new THREE.Quaternion();

/** Orthonormal basis quaternion with +Y = yDir and +Z as close as possible to zHint. */
function basisQuat(yDir, zHint, out) {
  _a.copy(yDir).normalize();
  _b.copy(zHint).addScaledVector(_a, -zHint.dot(_a));
  if (_b.lengthSq() < 1e-8) _b.set(0, 0, 1).addScaledVector(_a, -_a.z);
  _b.normalize();
  _c.crossVectors(_a, _b);
  _m.makeBasis(_c, _a, _b);
  return out.setFromRotationMatrix(_m);
}

function pose(p, y, z) {
  return { p: v3(...p), q: basisQuat(v3(...y), v3(...z), new THREE.Quaternion()) };
}
function spearPose(p, fwd, wrist) {
  return pose(p, fwd.map((x) => -x), wrist);
}
/** Overhand spear pose with the head aimed at a point (rig space). */
function spearAim(p, at, wrist) {
  return spearPose(p, [at[0] - p[0], at[1] - p[1], at[2] - p[2]], wrist);
}

const POSES = {
  swordLow: pose([0.27, -0.235, -0.42], [-0.11, 0.7, -0.7], [0.35, -0.6, 0.7]),
  swordHigh: pose([0.285, -0.165, -0.41], [-0.12, 0.68, -0.72], [0.35, -0.6, 0.7]),
  // Overhand dory: the fist above the shoulder, just out of view; the shaft comes in from the upper
  // right and the head points at an enemy's chest.
  spearLow: spearAim([0.28, 0.06, -0.12], [0.0, -0.42, -2.4], [0.3, -1, 0.3]),
  spearHigh: spearAim([0.28, 0.075, -0.15], [0.0, -0.36, -2.4], [0.3, -1, 0.3]),
  empty: pose([0.27, -0.66, -0.2], [-0.1, 0.4, -0.9], [0.3, -0.6, 0.7]),
  // Sword thrust: chambered back, then driven out with the point at the centre.
  thrustBack: pose([0.24, -0.17, -0.32], [-0.12, 0.2, -0.97], [0.4, -0.8, 0.4]),
  thrustOut: pose([0.13, -0.165, -0.55], [-0.21, 0.23, -0.95], [0.4, -0.8, 0.35]),
  // Spear: thrust extended, throw drawn back over the shoulder, release, follow-through.
  spearOut: spearAim([0.15, -0.06, -0.56], [-0.02, -0.18, -2.8], [0.3, -1, 0.3]),
  windup: spearAim([0.3, 0.13, 0.13], [0.12, 0.5, -2.6], [0.25, -1, -0.1]),
  release: spearAim([0.3, 0.12, -0.2], [0.05, 0.25, -3], [0.3, -1, 0.3]),
  follow: pose([0.06, -0.34, -0.42], [-0.5, -0.3, -0.8], [0.55, -0.55, 0.45]),
  // Draw: the hand dips out of view and comes back with the weapon.
  drawLow: pose([0.3, -0.72, -0.18], [0.1, -0.3, -0.95], [0.4, -0.6, 0.6]),
  drawLowSpear: spearAim([0.3, -0.7, -0.12], [0.3, -1.7, -2.2], [0.3, -1, 0.3]),
  drawSwordMid: pose([0.24, -0.42, -0.36], [-0.05, -0.15, -0.99], [0.35, -0.8, 0.4]),
  drawSpearMid: spearAim([0.28, -0.22, -0.2], [0.05, 0.05, -2.4], [0.3, -1, 0.3]),
};

const SHIELD_POSES = {
  low: { p: v3(-0.36, -0.53, -0.4), e: new THREE.Euler(0.42, 0.62, 0.42, 'YXZ') },
  high: { p: v3(-0.13, -0.478, -0.37), e: new THREE.Euler(0.12, 0.07, 0.02, 'YXZ') },
};
SHIELD_POSES.low.q = new THREE.Quaternion().setFromEuler(SHIELD_POSES.low.e);
SHIELD_POSES.high.q = new THREE.Quaternion().setFromEuler(SHIELD_POSES.high.e);

// Left hand on the antilabe (aspis space): grip frame X = +Z_s, Y = +Y_s, Z = -X_s.
const LEFT_GRIP = v3(ANTILABE.x, 0, aspisInnerZ(ANTILABE.x) + 0.046);
const LEFT_GRIP_Q = new THREE.Quaternion().setFromRotationMatrix(new THREE.Matrix4().makeBasis(v3(0, 0, 1), v3(0, 1, 0), v3(-1, 0, 0)));

// ---------------------------------------------------------------------------------------------

/**
 * Creates the viewmodel. See DESIGN.md §11 for the API.
 * @param {object} ctx
 */
export function createViewmodel(ctx) {
  const vm = ctx.vm;
  const scene = vm.scene;
  const cam = vm.camera;
  cam.fov = 60;
  cam.near = 0.01;
  cam.far = 10;
  cam.position.set(0, 0, 0);
  cam.quaternion.identity();
  cam.updateProjectionMatrix();
  scene.fog = null;
  scene.background = null;

  // Canvas sizes per quality. Painting is CPU work at construction (behind the boot screen):
  // about 0.65 s on medium on a desktop CPU, so the sizes go where they show — the hands and the
  // shield interior; the bronze face only shows to the enemy.
  const quality = ctx.quality || 'medium';
  const high = quality === 'high';
  const low = quality === 'low';
  const armSize = high ? 1024 : low ? 256 : 512;
  const weaponSize = high ? 512 : 256;
  const bronzeSize = high ? 512 : 256;
  const woodSize = low ? 256 : 512;
  const aniso = high ? 8 : 4;
  const rng = createRng(7);

  // ---- Textures and materials ----
  const texOf = (canvas, srgb) => {
    const t = new THREE.CanvasTexture(canvas);
    t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
    t.anisotropy = aniso;
    t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping;
    return t;
  };
  const matOf = (set, extra = {}) => {
    const orm = texOf(set.orm, false);
    return new THREE.MeshStandardMaterial({
      map: texOf(set.map, true),
      normalMap: texOf(set.normal, false),
      roughnessMap: orm,
      metalnessMap: orm,
      roughness: 1,
      metalness: 1,
      ...extra,
    });
  };
  const armSet = paintArmAtlas(armSize);
  const weaponSet = paintWeaponAtlas(weaponSize);
  const bronzeSet = paintShieldBronze(bronzeSize);
  const woodSet = paintShieldInterior(woodSize);
  const arrowSet = paintArrowAtlas(low ? 64 : 128);
  const armMat = matOf(armSet);
  const weaponMat = matOf(weaponSet);
  const bronzeMat = matOf(bronzeSet, { envMapIntensity: 1.1 });
  const woodMat = new THREE.MeshStandardMaterial({
    map: texOf(woodSet.map, true), normalMap: texOf(woodSet.normal, false), roughness: 0.7, metalness: 0,
  });
  const arrowMat = matOf(arrowSet, { side: THREE.DoubleSide });
  const bronzeMap = bronzeMat.map;

  // ---- Scene graph ----
  const rigRoot = new THREE.Group();
  rigRoot.name = 'vmRig';
  rigRoot.scale.setScalar(RIG.scale);
  const sway = new THREE.Group();
  rigRoot.add(sway);
  scene.add(rigRoot);

  // Arms: one skinned mesh, rigid parts. Bones: 0 R upper, 1 R fore, 2 R hand, 3 L upper, 4 L fore, 5 L hand.
  const L1 = RIG.upperArm;
  const L2 = RIG.foreArm;
  const partsR = [
    ...upperArmParts(L1).map((g) => skinTo(g, 0)),
    ...forearmParts(L2, { ring: true }).map((g) => skinTo(g, 1)),
    ...fistParts().map((g) => skinTo(g, 2)),
  ];
  const partsL = [
    ...upperArmParts(L1).map((g) => skinTo(mirrorX(g), 3)),
    ...forearmParts(L2).map((g) => skinTo(mirrorX(g), 4)),
    ...fistParts(0.012).map((g) => skinTo(mirrorX(g), 5)),
  ];
  const armGeo = mergeGeometries([...partsR, ...partsL], false);
  const bones = [];
  for (let i = 0; i < 6; i++) bones.push(new THREE.Bone());
  const arms = new THREE.SkinnedMesh(armGeo, armMat);
  arms.name = 'vmArms';
  for (const b of bones) arms.add(b);
  arms.bind(new THREE.Skeleton(bones, bones.map(() => new THREE.Matrix4())), new THREE.Matrix4());
  arms.frustumCulled = false;
  sway.add(arms);

  const sword = new THREE.Mesh(swordGeometry(), weaponMat);
  sword.name = 'vmSword';
  sword.frustumCulled = false;
  const spear = new THREE.Mesh(spearGeometry(), weaponMat);
  spear.name = 'vmSpear';
  spear.frustumCulled = false;
  sway.add(sword, spear);

  const aspis = aspisGeometry(low ? 48 : 72);
  const shield = new THREE.Group();
  shield.name = 'vmAspis';
  const shieldBronze = new THREE.Mesh(aspis.bronze, bronzeMat);
  const shieldWood = new THREE.Mesh(aspis.interior, woodMat);
  shieldBronze.frustumCulled = shieldWood.frustumCulled = false;
  shield.add(shieldBronze, shieldWood);
  const arrows = new THREE.InstancedMesh(arrowGeometry(), arrowMat, MAX_ARROWS);
  arrows.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  arrows.frustumCulled = false;
  arrows.count = MAX_ARROWS; // all present for the pre-warm compile; trimmed on the first update
  shield.add(arrows);
  sway.add(shield);

  const trail = createTrail(28);
  sway.add(trail.mesh);
  const overlay = createOverlay();
  scene.add(overlay);

  // Lights: own sun (no shadows) and sky fill; directions follow the main camera every update.
  const sun = new THREE.DirectionalLight(0xfff1dc, 2.4);
  sun.castShadow = false;
  const hemi = new THREE.HemisphereLight(0xcfdcf0, 0x6e5a44, 0.8);
  scene.add(sun, sun.target, hemi);
  scene.environment = (ctx.world && ctx.world.envMap) || null;

  // ---- State ----
  const st = {
    weapon: 'sword',
    hasSpear: true,
    raise: 0,
    visible: true,
    shown: 'sword',            // what the hand displays: 'sword' | 'spear' | null
    released: false,           // the spear left the hand during this throw
    arm: null,                 // right-arm one-shot
    shieldFx: [],              // additive shield one-shots
    rigFx: [],                 // whole-rig one-shots (hurt)
    forceUp: 0,                // perfect-block snap
    forceDown: 0,              // guard break
    prevYaw: null,
    prevPitch: 0,
    prevX: 0,
    prevZ: 0,
    swayYaw: 0, swayYawV: 0, swayPitch: 0, swayPitchV: 0,
    speed: 0, bobPhase: 0, breath: 0, time: 0,
    overlay: { vignette: 0, hurt: 0, helmet: false },
    first: true,
    snap: 0.2,                 // seconds after reset during which weapon changes snap (no draw)
    lightScan: 0,
  };
  const worldLights = { sun: null, hemi: null };
  // Stuck arrows: a fixed pool; the oldest is replaced once all eight are in use.
  const stuck = [];
  for (let i = 0; i < MAX_ARROWS; i++) stuck.push({ p: new THREE.Vector3(), q: new THREE.Quaternion(), axis: new THREE.Vector3(), t: 0 });
  let stuckCount = 0;
  let stuckHead = 0;

  // Working poses.
  const hand = { p: new THREE.Vector3(), q: new THREE.Quaternion() };
  const guard = { p: new THREE.Vector3(), q: new THREE.Quaternion() };
  const tmpPose = { p: new THREE.Vector3(), q: new THREE.Quaternion() };
  const shieldPose = { p: new THREE.Vector3(), q: new THREE.Quaternion() };
  const slashOut = createSlashOut();
  const slashTm = slashTimes(TIMING.slash);

  // ---------------------------------------------------------------------------------------------
  // One-shots

  // Right-arm one-shot state: two pooled records, alternated so a wind-up can hand over to its
  // release within one frame without allocating.
  const NO_OPTS = Object.freeze({});
  const TOUCH_RELEASE = Object.freeze({ motion: false });
  const newArm = () => ({
    kind: '', t: 0, dur: 0, opts: NO_OPTS, dir: 0, power: 0, sigma: 1, swapped: false, hideAt: 0,
    fromP: new THREE.Vector3(), fromQ: new THREE.Quaternion(),
  });
  const armPool = [newArm(), newArm()];
  let armNext = 0;

  function startArm(kind, opts = NO_OPTS) {
    const a = armPool[armNext];
    armNext ^= 1;
    a.kind = kind;
    a.t = 0;
    a.opts = opts || NO_OPTS;
    a.fromP.copy(hand.p);
    a.fromQ.copy(hand.q);
    a.swapped = false;
    if (kind === 'slash' || kind === 'spearSlash') {
      a.dir = typeof opts.dir === 'number' ? opts.dir : 0;
      a.power = clamp(opts.power ?? 0.6, 0, 1);
      a.dur = slashTm.total;
      // Which edge leads is fixed for the whole swing (the side that keeps the wrist natural).
      if (kind === 'slash') a.sigma = slashEdgeSign(a.dir);
    } else if (kind === 'thrust') a.dur = TIMING.thrust.total;
    else if (kind === 'spearThrust') a.dur = TIMING.spearThrust.total;
    else if (kind === 'bash') a.dur = TIMING.bash.total;
    else if (kind === 'throwWindup') a.dur = opts.auto ? TIMING.throw.windup : 1.3;
    else if (kind === 'throwRelease') {
      a.dur = Math.max(0.5, TIMING.throw.total - (opts.motion ? 0 : TIMING.throw.windup));
      // The world spear spawns 0.03 s into a motion throw and at once for a touch throw (§7).
      a.hideAt = opts.motion ? 0.03 : 0.012;
      st.released = false;
    } else if (kind === 'draw') a.dur = 0.55;
    st.arm = a;
    return a;
  }

  function addShieldFx(kind, opts = {}) {
    if (st.shieldFx.length >= 6) st.shieldFx.shift();
    const f = { kind, t: 0, opts, side: opts.side ?? (rng.next() - 0.5) };
    f.dur = kind === 'bash' ? TIMING.bash.total : kind === 'guardBreak' ? 0.9 : kind === 'arrow' ? 0.5 : 0.6;
    st.shieldFx.push(f);
  }

  function play(kind, opts = NO_OPTS) {
    if (!opts) opts = NO_OPTS;
    switch (kind) {
      case 'slash':
        if (st.shown === 'spear') startArm('spearSlash', opts);
        else startArm('slash', opts);
        break;
      case 'spearSlash':
      case 'thrust':
      case 'spearThrust':
      case 'draw':
        if (kind === 'draw' && st.arm && st.arm.kind === 'draw' && st.arm.t < 0.12) break;
        startArm(kind, opts);
        break;
      case 'bash':
        startArm('bash', opts);
        addShieldFx('bash', opts);
        break;
      case 'throwWindup':
        if (st.shown !== 'spear') break;
        if (st.arm && st.arm.kind === 'throwWindup' && !opts.auto) break; // already drawn back
        startArm('throwWindup', opts);
        break;
      case 'throwRelease':
        if (st.shown !== 'spear' && !(st.arm && st.arm.kind === 'throwWindup')) break;
        startArm('throwRelease', opts);
        break;
      case 'blockImpact': {
        const side = opts.side ?? 0;
        if (opts.guardBreak) {
          addShieldFx('guardBreak', { side });
          st.forceDown = 1;
        } else {
          addShieldFx('block', { perfect: !!opts.perfect, side });
          if (opts.perfect) st.forceUp = 1;
        }
        break;
      }
      case 'hurt':
        if (st.rigFx.length >= 4) st.rigFx.shift();
        st.rigFx.push({ kind: 'hurt', t: 0, dur: 0.6, rel: opts.relYaw ?? 0, amp: clamp(0.55 + (opts.damage ?? 10) / 40, 0.6, 1.3) });
        break;
      default:
        break;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Events (DESIGN.md §12)

  const ev = ctx.events;
  ev.on('swing', (p) => {
    if (!p) return;
    switch (p.kind) {
      case 'slash':
        play(p.weapon === 'spear' && st.shown === 'spear' ? 'spearSlash' : 'slash', p);
        break;
      case 'thrust':
        play('thrust', p);
        break;
      case 'spearThrust':
        play('spearThrust', p);
        break;
      case 'bash':
        play('bash', p);
        break;
      case 'throw':
        if (p.src === 'motion') play('throwRelease', { motion: true, power: p.power });
        else play('throwWindup', { auto: true, power: p.power });
        break;
      default:
        break;
    }
  });
  ev.on('block', (p) => {
    if (!p || p.by !== 'player') return;
    play('blockImpact', { perfect: !!p.perfect, guardBreak: p.kind === 'guardBreak', side: attackerSide(p.attacker) });
  });
  ev.on('playerHurt', (p) => play('hurt', p || {}));
  ev.on('weaponSwitch', (p) => play('draw', { weapon: p && p.weapon }));
  ev.on('arrowHit', (p) => {
    if (p && p.target === 'shield') addStuckArrow();
  });
  ev.on('respite', () => clearStuckArrows());

  /** -1..1: which side of the view an attacker is on (positive = right). */
  function attackerSide(att) {
    const pl = ctx.player;
    if (!att || !att.pos || !pl || !pl.pos || typeof pl.yaw !== 'number') return 0;
    const dx = att.pos.x - pl.pos.x;
    const dz = att.pos.z - pl.pos.z;
    const rel = wrapAngle(Math.atan2(-dx, -dz) - pl.yaw);
    return clamp(-rel, -1, 1);
  }

  // ---------------------------------------------------------------------------------------------
  // Stuck arrows

  function writeArrow(i) {
    const s = stuck[i];
    _q.copy(s.q);
    if (s.t < 0.6) {
      const w = 0.07 * jolt(s.t, 13, 8);
      _q2.setFromAxisAngle(s.axis, w);
      _q.premultiply(_q2);
    }
    _m.compose(s.p, _q, _e.set(1, 1, 1));
    arrows.setMatrixAt(i, _m);
  }

  function addStuckArrow() {
    let i;
    if (stuckCount < MAX_ARROWS) {
      i = stuckCount++;
    } else {
      i = stuckHead;
      stuckHead = (stuckHead + 1) % MAX_ARROWS;
    }
    const s = stuck[i];
    // Volleys fall steeply: hits spread over the face, shafts point up and out toward the archers.
    const r = 0.07 + 0.29 * Math.sqrt(rng.next());
    const th = rng.next() * Math.PI * 2;
    const x = Math.cos(th) * r;
    const y = Math.sin(th) * r * 0.9 + 0.03;
    const out = _a.set((rng.next() - 0.5) * 0.7, 0.55 + rng.next() * 0.9, -1).normalize();
    const deep = rng.next() < 0.5;
    const pen = deep ? ASPIS.wall + 0.03 + rng.next() * 0.02 : 0.025 + rng.next() * 0.015;
    const z = aspisOuterZ(Math.hypot(x, y));
    s.p.set(x, y, z).addScaledVector(out, -pen);
    s.q.setFromUnitVectors(_b.set(0, 0, 1), out);
    s.axis.set(rng.next() - 0.5, rng.next() - 0.5, 0).cross(out).normalize();
    s.t = 0;
    writeArrow(i);
    arrows.count = stuckCount;
    arrows.instanceMatrix.needsUpdate = true;
    addShieldFx('arrow', { side: x > 0 ? 0.4 : -0.4 });
  }

  function clearStuckArrows() {
    stuckCount = 0;
    stuckHead = 0;
    arrows.count = 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Pose evaluation

  function slerpPose(out, a, b, w) {
    out.p.lerpVectors(a.p, b.p, w);
    out.q.slerpQuaternions(a.q, b.q, w);
  }

  function guardFor(kind, raise, out) {
    if (kind === 'spear') slerpPose(out, POSES.spearLow, POSES.spearHigh, raise);
    else if (kind === 'sword') slerpPose(out, POSES.swordLow, POSES.swordHigh, raise);
    else {
      out.p.copy(POSES.empty.p);
      out.q.copy(POSES.empty.q);
    }
    // Composition across phone aspects: spread the hand a little wider on wide screens.
    out.p.x += clamp((cam.aspect - 1.78) * 0.05, -0.03, 0.025);
    return out;
  }

  /** Key-framed pose: keys = [{ t, pose }] (pose may be the live guard); smooth between keys. */
  function keyed(keys, t, out) {
    let i = 0;
    while (i < keys.length - 2 && t > keys[i + 1].t) i++;
    const a = keys[i];
    const b = keys[i + 1];
    const w = smootherstep((t - a.t) / Math.max(1e-4, b.t - a.t));
    slerpPose(out, a.pose, b.pose, w);
    return out;
  }

  const fromPose = { p: null, q: null };
  const keysRelease = [
    { t: 0, pose: fromPose },
    { t: 0.045, pose: POSES.release },
    { t: 0.2, pose: POSES.follow },
    { t: 0.42, pose: POSES.empty },
    { t: 10, pose: POSES.empty },
  ];
  const keysDraw = [
    { t: 0, pose: fromPose },
    { t: 0.2, pose: POSES.drawLow },
    { t: 0.36, pose: null },
    { t: 0.55, pose: guard },
    { t: 10, pose: guard },
  ];

  /** Evaluates the right-arm one-shot into `hand` (already holding the guard). */
  function evalArm(a) {
    const t = a.t;
    fromPose.p = a.fromP;
    fromPose.q = a.fromQ;
    switch (a.kind) {
      case 'slash': {
        const s = slashParam(t, slashTm);
        slashGeometry(a.dir, s, slashOut, SWORD_REACH);
        _a.fromArray(slashOut.blade);
        _b.fromArray(slashOut.motion).addScaledVector(_a, -_b.dot(_a));
        if (_b.lengthSq() < 1e-6) _b.set(0, 0, 1);
        _b.normalize().multiplyScalar(a.sigma);
        tmpPose.p.fromArray(slashOut.hand);
        basisQuat(_a, _b, tmpPose.q);
        const wIn = easeOutQuad(clamp(t / slashTm.ta, 0, 1));
        slerpPose(hand, fromPose, tmpPose, wIn);
        const wOut = ramp(slashTm.recover, slashTm.back, t);
        if (wOut > 0) slerpPose(hand, hand, guard, wOut);
        break;
      }
      case 'spearSlash': {
        // The head sweeps along dir: the spear pivots about the hand while the hand drifts with it.
        const s = slashParam(t, slashTm);
        const g = clamp(s, -1.6, 1.6);
        const dx = Math.cos(a.dir);
        const dy = Math.sin(a.dir);
        tmpPose.p.copy(guard.p).add(_a.set(dx * 0.1 * g, dy * 0.08 * g, -0.05 * (1 - Math.abs(g) * 0.4)));
        _b.set(dy, -dx, 0); // axis in the view plane, perpendicular to the cut (the head moves along dir)
        _q.setFromAxisAngle(_b.normalize(), 0.42 * g);
        tmpPose.q.copy(guard.q).premultiply(_q);
        slerpPose(hand, fromPose, tmpPose, easeOutQuad(clamp(t / slashTm.ta, 0, 1)));
        const wOut = ramp(slashTm.recover, slashTm.back, t);
        if (wOut > 0) slerpPose(hand, hand, guard, wOut);
        break;
      }
      case 'thrust': {
        const tm = TIMING.thrust;
        const c = thrustCurve(t, tm.strike, tm.total);
        tmpPose.p.lerpVectors(POSES.thrustBack.p, POSES.thrustOut.p, c);
        tmpPose.q.slerpQuaternions(POSES.thrustBack.q, POSES.thrustOut.q, clamp(c, 0, 1));
        slerpPose(hand, fromPose, tmpPose, easeOutQuad(clamp(t / 0.045, 0, 1)));
        const wOut = ramp(tm.total * 0.62, tm.total, t);
        if (wOut > 0) slerpPose(hand, hand, guard, wOut);
        break;
      }
      case 'spearThrust': {
        const tm = TIMING.spearThrust;
        const c = thrustCurve(t, tm.strike, tm.total);
        tmpPose.p.lerpVectors(guard.p, POSES.spearOut.p, c);
        tmpPose.q.slerpQuaternions(guard.q, POSES.spearOut.q, clamp(c, 0, 1));
        slerpPose(hand, fromPose, tmpPose, easeOutQuad(clamp(t / 0.05, 0, 1)));
        break;
      }
      case 'bash': {
        // The sword arm draws back and tucks in as the body drives the shield forward.
        const c = bashCurve(t, TIMING.bash.strike, TIMING.bash.total);
        tmpPose.p.copy(guard.p).add(_a.set(0.03 * c, -0.03 * c, 0.07 * c));
        tmpPose.q.copy(guard.q);
        slerpPose(hand, fromPose, tmpPose, easeOutQuad(clamp(t / 0.06, 0, 1)));
        break;
      }
      case 'throwWindup': {
        const w = easeOutCubic(clamp(t / (a.opts.auto ? TIMING.throw.windup * 0.95 : 0.16), 0, 1));
        slerpPose(hand, fromPose, POSES.windup, w);
        if (!a.opts.auto && t > a.dur - 0.3) slerpPose(hand, hand, guard, ramp(a.dur - 0.3, a.dur, t));
        break;
      }
      case 'throwRelease':
        keyed(keysRelease, t, hand);
        if (!st.released && t >= a.hideAt) st.released = true;
        if (st.shown === 'spear' && st.released) st.shown = null;
        break;
      case 'draw': {
        // Down and out of view with the old weapon, swap there, and come back up with the new one.
        if (!a.swapped && t >= 0.2) {
          a.swapped = true;
          st.shown = targetShown();
          st.released = false;
        }
        const next = targetShown();
        keysDraw[1].pose = st.shown === 'spear' ? POSES.drawLowSpear : POSES.drawLow;
        keysDraw[2].pose = next === 'spear' ? POSES.drawSpearMid : POSES.drawSwordMid;
        keyed(keysDraw, t, hand);
        break;
      }
      default:
        break;
    }
  }

  function targetShown() {
    if (st.weapon === 'spear') return st.hasSpear ? 'spear' : null;
    return 'sword';
  }

  // ---------------------------------------------------------------------------------------------
  // IK and bones

  const SR = v3(...RIG.shoulderR);
  const SL = v3(...RIG.shoulderL);
  const WRIST_R = v3(...RIG.wristInGrip);
  const WRIST_L = v3(-RIG.wristInGrip[0], RIG.wristInGrip[1], RIG.wristInGrip[2]);
  const POLE_R = v3(0.45, -1, 0.15).normalize();
  const ik = {
    S: new THREE.Vector3(), E: new THREE.Vector3(), W: new THREE.Vector3(),
    X: new THREE.Vector3(), Y: new THREE.Vector3(), Z: new THREE.Vector3(),
    hinge: new THREE.Vector3(1, 0, 0), hingeL: new THREE.Vector3(-1, 0, 0),
  };
  const gx = new THREE.Vector3();
  const gy = new THREE.Vector3();
  const gz = new THREE.Vector3();

  function setBone(bone, p, x, y, z) {
    bone.position.copy(p);
    _m.makeBasis(x, y, z);
    bone.quaternion.setFromRotationMatrix(_m);
  }

  /** Two-bone IK: places the elbow on its circle as close as possible to `pref`. */
  function solveElbow(S, W, pref, outS, outE) {
    outS.copy(S);
    _a.subVectors(W, outS);
    let d = _a.length();
    const maxD = L1 + L2 - 0.003;
    const minD = Math.abs(L1 - L2) + 0.06;
    if (d > maxD) {
      // The shoulder protracts (torso twist and lean), mostly forward and sideways rather than up,
      // so a reach overhead doesn't lift the upper arm into view.
      _b.copy(_a).divideScalar(d);
      if (_b.y > 0) _b.y *= 0.25;
      _b.normalize();
      const vu = _a.dot(_b);
      const disc = vu * vu - d * d + maxD * maxD;
      if (disc >= 0) outS.addScaledVector(_b, vu - Math.sqrt(disc));
      else outS.addScaledVector(_a, (d - maxD) / d);
      d = maxD;
    } else if (d < minD) {
      outS.addScaledVector(_a, -(minD - d) / Math.max(d, 1e-4));
      d = minD;
    }
    _a.subVectors(W, outS).normalize();
    const ad = (L1 * L1 - L2 * L2 + d * d) / (2 * d);
    const rc = Math.sqrt(Math.max(0, L1 * L1 - ad * ad));
    _b.copy(outS).addScaledVector(_a, ad); // circle centre
    _c.subVectors(pref, _b);
    _c.addScaledVector(_a, -_c.dot(_a));
    if (_c.lengthSq() < 1e-8) _c.copy(POLE_R).addScaledVector(_a, -POLE_R.dot(_a));
    _c.normalize();
    outE.copy(_b).addScaledVector(_c, rc);
  }

  function updateBones() {
    // Right hand frame.
    gx.set(1, 0, 0).applyQuaternion(hand.q);
    gy.set(0, 1, 0).applyQuaternion(hand.q);
    gz.set(0, 0, 1).applyQuaternion(hand.q);
    ik.W.copy(WRIST_R).applyQuaternion(hand.q).add(hand.p);
    // Preferred elbow: along the hand's wrist axis, biased down and out.
    _d.copy(gz).addScaledVector(gx, 0.25).normalize().multiplyScalar(L2).add(ik.W).addScaledVector(POLE_R, 0.12);
    solveElbow(SR, ik.W, _d, ik.S, ik.E);
    setBone(bones[2], hand.p, gx, gy, gz);
    // Forearm: +Y elbow → wrist, X = radial side (the thumb's direction), Z = palmar.
    ik.Y.subVectors(ik.W, ik.E).normalize();
    ik.X.copy(gy).addScaledVector(ik.Y, -gy.dot(ik.Y)).normalize();
    ik.Z.crossVectors(ik.X, ik.Y);
    setBone(bones[1], ik.E, ik.X, ik.Y, ik.Z);
    _e.copy(ik.Y);
    // Upper arm: +Y shoulder → elbow, X = elbow hinge.
    ik.Y.subVectors(ik.E, ik.S).normalize();
    _a.crossVectors(ik.Y, _e);
    if (_a.lengthSq() > 1e-5) ik.hinge.copy(_a).normalize();
    ik.X.copy(ik.hinge).addScaledVector(ik.Y, -ik.hinge.dot(ik.Y)).normalize();
    ik.Z.crossVectors(ik.X, ik.Y);
    setBone(bones[0], ik.S, ik.X, ik.Y, ik.Z);

    // Left arm: hand and forearm ride the aspis; the upper arm reaches back to the shoulder.
    _q.copy(shieldPose.q).multiply(LEFT_GRIP_Q);
    gx.set(1, 0, 0).applyQuaternion(_q);
    gy.set(0, 1, 0).applyQuaternion(_q);
    gz.set(0, 0, 1).applyQuaternion(_q);
    _d.copy(LEFT_GRIP).applyQuaternion(shieldPose.q).add(shieldPose.p);
    setBone(bones[5], _d, gx, gy, gz);
    ik.W.copy(WRIST_L).applyQuaternion(_q).add(_d);
    // Elbow: forearm length back from the wrist toward the porpax at the aspis centre.
    _c.set(-0.06, 0, aspisInnerZ(0) + PORPAX.axisLift).applyQuaternion(shieldPose.q).add(shieldPose.p);
    _c.sub(ik.W).normalize();
    ik.E.copy(ik.W).addScaledVector(_c, L2);
    ik.Y.subVectors(ik.W, ik.E).normalize();
    ik.X.copy(gy).multiplyScalar(-1).addScaledVector(ik.Y, gy.dot(ik.Y)).normalize();
    ik.Z.crossVectors(ik.X, ik.Y);
    setBone(bones[4], ik.E, ik.X, ik.Y, ik.Z);
    _e.copy(ik.Y);
    _a.subVectors(SL, ik.E).normalize();
    ik.S.copy(ik.E).addScaledVector(_a, L1);
    ik.Y.subVectors(ik.E, ik.S).normalize();
    _a.crossVectors(_e, ik.Y);
    if (_a.lengthSq() > 1e-5) ik.hingeL.copy(_a).normalize();
    ik.X.copy(ik.hingeL).addScaledVector(ik.Y, -ik.hingeL.dot(ik.Y)).normalize();
    ik.Z.crossVectors(ik.X, ik.Y);
    setBone(bones[3], ik.S, ik.X, ik.Y, ik.Z);
  }

  // ---------------------------------------------------------------------------------------------
  // Shield

  const shieldOff = { p: new THREE.Vector3(), r: new THREE.Euler(0, 0, 0, 'YXZ') };

  function updateShield(pdt) {
    // Snap-up after a perfect block, forced down after a guard break.
    const fu = st.forceUp;
    st.forceUp = Math.max(0, st.forceUp - pdt / 0.5);
    st.forceDown = Math.max(0, st.forceDown - pdt / 0.9);
    let raise = st.raise;
    if (fu > 0) raise = Math.max(raise, smootherstep(Math.min(1, fu * 2.5)));
    if (st.forceDown > 0) raise = Math.min(raise, 1 - smootherstep(Math.min(1, st.forceDown * 2)));
    shieldOff.p.set(0, 0, 0);
    shieldOff.r.set(0, 0, 0);
    for (let i = st.shieldFx.length - 1; i >= 0; i--) {
      const f = st.shieldFx[i];
      f.t += pdt;
      if (f.t >= f.dur) {
        st.shieldFx.splice(i, 1);
        continue;
      }
      const t = f.t;
      if (f.kind === 'bash') {
        const tm = TIMING.bash;
        const c = bashCurve(t, tm.strike, tm.total);
        raise = Math.max(raise, 0.92 * bump(t, 0, 0.06, tm.total - 0.15, tm.total));
        const hit = t >= tm.strike ? jolt(t - tm.strike, 9, 16) : 0;
        shieldOff.p.x += 0.05 * c;
        shieldOff.p.y += 0.035 * c;
        shieldOff.p.z += -0.26 * c + 0.03 * hit;
        shieldOff.r.x += -0.14 * c + 0.05 * hit;
        shieldOff.r.y += -0.12 * c;
      } else if (f.kind === 'block') {
        const k = f.opts.perfect ? 0.75 : 1;
        const j = jolt(t, f.opts.perfect ? 9 : 6.5, f.opts.perfect ? 14 : 9);
        shieldOff.p.z += 0.055 * k * j;
        shieldOff.p.y -= 0.02 * k * j;
        shieldOff.p.x += 0.02 * f.side * j;
        shieldOff.r.x += 0.13 * k * j;
        shieldOff.r.z += -0.06 * f.side * j;
      } else if (f.kind === 'guardBreak') {
        const j = jolt(t, 4, 6);
        const knock = bump(t, 0, 0.05, 0.3, 0.9);
        shieldOff.p.z += 0.12 * j;
        shieldOff.p.x += -0.1 * knock;
        shieldOff.p.y += -0.14 * knock;
        shieldOff.r.x += 0.3 * j;
        shieldOff.r.z += 0.45 * knock;
        shieldOff.r.y += 0.2 * knock;
      } else if (f.kind === 'arrow') {
        const j = jolt(t, 10, 12);
        shieldOff.p.z += 0.014 * j;
        shieldOff.r.x += 0.03 * j;
      }
    }
    slerpPose(shieldPose, SHIELD_POSES.low, SHIELD_POSES.high, easeInOutSine(raise));
    shieldPose.p.x -= clamp((cam.aspect - 1.78) * 0.04, -0.02, 0.02);
    shieldPose.p.add(shieldOff.p);
    _q.setFromEuler(shieldOff.r);
    shieldPose.q.premultiply(_q);
    return raise;
  }

  // ---------------------------------------------------------------------------------------------
  // Trail

  const tb = new THREE.Vector3();
  const tt = new THREE.Vector3();

  function updateTrail(a) {
    const n = trail.samples;
    if (!a || (a.kind !== 'slash' && a.kind !== 'spearSlash')) {
      trail.hide();
      return false;
    }
    const win = 0.07;
    const t1 = Math.min(a.t, slashTm.tc - 0.025);
    const t0 = Math.max(slashTm.ta, a.t - win);
    if (t1 - t0 < 0.004) {
      trail.hide();
      return false;
    }
    const fadeOut = 1 - ramp(slashTm.tc - 0.07, slashTm.tc, a.t);
    for (let i = 0; i < n; i++) {
      const tau = t0 + (t1 - t0) * (i / (n - 1));
      const s = slashParam(tau, slashTm);
      const age = (a.t - tau) / win + (1 - fadeOut);
      if (a.kind === 'slash') {
        slashGeometry(a.dir, s, slashOut, SWORD_REACH);
        tt.fromArray(slashOut.tip);
        tb.fromArray(slashOut.hand).addScaledVector(_a.fromArray(slashOut.blade), BLADE_BASE);
      } else {
        const g = clamp(s, -1.6, 1.6);
        const dx = Math.cos(a.dir);
        const dy = Math.sin(a.dir);
        _c.copy(guard.p).add(_a.set(dx * 0.1 * g, dy * 0.08 * g, -0.05 * (1 - Math.abs(g) * 0.4)));
        _q.setFromAxisAngle(_b.set(dy, -dx, 0).normalize(), 0.42 * g);
        _q2.copy(guard.q).premultiply(_q);
        _d.set(0, -1, 0).applyQuaternion(_q2);
        tt.copy(_c).addScaledVector(_d, -SPEAR.headTip);
        tb.copy(_c).addScaledVector(_d, -SPEAR.headTip - 0.4);
      }
      trail.set(i, tb, tt, age);
    }
    trail.commit(n);
    trail.mesh.material.uniforms.uOpacity.value = 0.22 + 0.2 * (a.power ?? 0.6);
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Lights, environment, first-update work

  const invCam = new THREE.Quaternion();

  function findWorldLights() {
    worldLights.sun = null;
    worldLights.hemi = null;
    if (!ctx.scene) return;
    ctx.scene.traverse((o) => {
      if (o.isDirectionalLight && !worldLights.sun && o.castShadow) worldLights.sun = o;
      else if (o.isHemisphereLight && !worldLights.hemi) worldLights.hemi = o;
    });
  }

  function updateLights(dt) {
    if (ctx.camera) invCam.copy(ctx.camera.quaternion).invert();
    else invCam.identity();
    const world = ctx.world;
    if (world && world.sunDirection) sun.position.copy(world.sunDirection).applyQuaternion(invCam).multiplyScalar(5);
    else sun.position.set(-3, 4, 2.5);
    hemi.position.set(0, 1, 0).applyQuaternion(invCam);
    // Keep the last env map if the world briefly has none (a context restore), so the vm programs
    // never switch between env and no-env at runtime.
    if (world && world.envMap) scene.environment = world.envMap;
    scene.environmentRotation.setFromQuaternion(invCam);
    st.lightScan -= dt;
    if ((!worldLights.sun || !worldLights.sun.parent) && st.lightScan <= 0) {
      st.lightScan = 1;
      findWorldLights();
    }
    if (worldLights.sun && worldLights.sun.parent) {
      sun.color.copy(worldLights.sun.color);
      sun.intensity = worldLights.sun.intensity * 0.92;
    }
    if (worldLights.hemi && worldLights.hemi.parent) {
      hemi.color.copy(worldLights.hemi.color);
      hemi.groundColor.copy(worldLights.hemi.groundColor);
      hemi.intensity = worldLights.hemi.intensity;
    }
  }

  /** Paints the player's blazon (the shared blazonFly texture) over the bronze face. */
  function applyBlazon(src) {
    try {
      compositeBlazon(bronzeSet, src);
      bronzeMap.needsUpdate = true;
      bronzeMat.metalnessMap.needsUpdate = true;
    } catch (err) {
      console.warn('viewmodel: blazon composite failed', err);
    }
  }

  function firstUpdate() {
    st.first = false;
    // The constructor may not call other modules, so the blazon is fetched on the first update.
    // Its canvas may live on the GPU: copy it with createImageBitmap, which reads it back off the
    // main thread, instead of stalling a frame on a synchronous read. Until then the fallback
    // fly painted with the bronze shows (and the face only faces the enemy anyway).
    let img = null;
    try {
      const t = ctx.textures && ctx.textures.get && ctx.textures.get('blazonFly');
      img = t && t.image;
    } catch (err) {
      console.warn('viewmodel: blazonFly unavailable', err);
    }
    // Any drawable image (canvas, bitmap, <img>); a DataTexture's { data } image is not drawable.
    if (img && img.width > 0 && !img.data) {
      if (typeof createImageBitmap === 'function') {
        createImageBitmap(img).then((bmp) => {
          applyBlazon(bmp);
          if (bmp.close) bmp.close();
        }, () => applyBlazon(img));
      } else applyBlazon(img);
    }
    arrows.count = stuckCount;
    st.shown = targetShown();
  }

  // ---------------------------------------------------------------------------------------------
  // Per-frame

  const rigOff = { p: new THREE.Vector3(), r: new THREE.Euler(0, 0, 0, 'YXZ') };
  const PIVOT = v3(0, -0.3, 0.05);

  function updateRig(dt, pdt) {
    const pl = ctx.player;
    // Sway: the rig lags the view's rotation and springs back (dt).
    if (pl && typeof pl.yaw === 'number' && typeof pl.pitch === 'number') {
      if (st.prevYaw === null) {
        st.prevYaw = pl.yaw;
        st.prevPitch = pl.pitch;
      }
      const dy = clamp(wrapAngle(pl.yaw - st.prevYaw), -0.25, 0.25);
      const dp = clamp(pl.pitch - st.prevPitch, -0.25, 0.25);
      st.prevYaw = pl.yaw;
      st.prevPitch = pl.pitch;
      st.swayYaw -= dy * 0.45;
      st.swayPitch -= dp * 0.45;
      const px = pl.pos ? pl.pos.x : 0;
      const pz = pl.pos ? pl.pos.z : 0;
      const step = Math.hypot(px - st.prevX, pz - st.prevZ);
      st.prevX = px;
      st.prevZ = pz;
      if (dt > 0 && step < 1) {
        st.speed += (step / dt - st.speed) * (1 - Math.exp(-8 * dt));
        st.bobPhase += step * (Math.PI * 2 / 1.5);
      }
    }
    if (dt > 0) {
      // Semi-implicit spring, sub-stepped so long frames stay stable.
      const k = 150;
      const c = 18;
      const n = Math.ceil(dt / (1 / 120));
      const h = dt / n;
      for (let i = 0; i < n; i++) {
        st.swayYawV += (-k * st.swayYaw - c * st.swayYawV) * h;
        st.swayPitchV += (-k * st.swayPitch - c * st.swayPitchV) * h;
        st.swayYaw = clamp(st.swayYaw + st.swayYawV * h, -0.14, 0.14);
        st.swayPitch = clamp(st.swayPitch + st.swayPitchV * h, -0.12, 0.12);
      }
      st.breath += dt;
      st.time += dt;
      if (dt > 0 && (!pl || !pl.pos)) st.speed *= Math.exp(-6 * dt);
    }
    const bob = clamp(st.speed / 2.2, 0, 1);
    const ph = st.bobPhase;
    const br = Math.sin(st.breath * Math.PI * 2 * 0.23);
    rigOff.p.set(
      Math.sin(ph * 0.5) * 0.011 * bob,
      -Math.abs(Math.cos(ph * 0.5)) * 0.016 * bob + 0.008 * bob + br * 0.003,
      0,
    );
    rigOff.r.set(st.swayPitch + br * 0.004 + 0.008 * bob * Math.cos(ph), st.swayYaw, Math.sin(ph * 0.5) * 0.012 * bob + st.swayYaw * 0.35);
    // Idle drift so the hands are never perfectly still.
    rigOff.r.x += Math.sin(st.time * 0.9) * 0.002 + Math.sin(st.time * 2.3) * 0.001;
    rigOff.r.y += Math.sin(st.time * 0.7 + 1) * 0.002;
    // Hurt flinches (pdt).
    for (let i = st.rigFx.length - 1; i >= 0; i--) {
      const f = st.rigFx[i];
      f.t += pdt;
      if (f.t >= f.dur) {
        st.rigFx.splice(i, 1);
        continue;
      }
      const j = jolt(f.t, 3.2, 7) * f.amp;
      const side = clamp(f.rel, -1, 1);
      rigOff.p.y -= 0.035 * j;
      rigOff.p.z += 0.045 * j;
      rigOff.p.x -= 0.03 * side * j;
      rigOff.r.x -= 0.07 * j;
      rigOff.r.z += 0.06 * side * j;
      rigOff.r.y += 0.04 * side * j;
    }
    // Lunge into thrusts.
    const a = st.arm;
    if (a && (a.kind === 'thrust' || a.kind === 'spearThrust')) {
      const tm = TIMING[a.kind];
      rigOff.p.z -= 0.035 * clamp(thrustCurve(a.t, tm.strike, tm.total), 0, 1);
    }
    _q.setFromEuler(rigOff.r);
    sway.quaternion.copy(_q);
    sway.position.copy(PIVOT).applyQuaternion(_q).negate().add(PIVOT).add(rigOff.p);
  }

  function update(dt, gdt) {
    const pdt = Math.max(0, (ctx.time && ctx.time.pdt) || 0);
    if (st.first) firstUpdate();

    // Weapon displayed / redraw detection.
    const target = targetShown();
    if (st.snap > 0) {
      st.snap -= dt;
      if (!st.arm) st.shown = target;
    }
    const a0 = st.arm;
    const busy = a0 && (a0.kind === 'draw' || a0.kind === 'throwRelease' || (a0.kind === 'throwWindup'));
    if (target !== st.shown && !busy) {
      if (target === null) st.shown = null;
      else play('draw');
    }

    const raise = updateShield(pdt);
    guardFor(st.shown, raise, guard);
    hand.p.copy(guard.p);
    hand.q.copy(guard.q);

    // Advance the right-arm one-shot with pdt.
    const a = st.arm;
    if (a) {
      a.t += pdt;
      if (a.kind === 'throwWindup' && a.opts.auto && a.t >= TIMING.throw.windup) {
        const over = a.t - TIMING.throw.windup;
        a.t = TIMING.throw.windup;
        evalArm(a); // the release starts from the fully drawn-back pose
        const r = startArm('throwRelease', TOUCH_RELEASE);
        r.t = over;
        evalArm(r);
      } else if (a.t >= a.dur) {
        if (a.kind === 'draw' && !a.swapped) st.shown = targetShown();
        if (a.kind === 'throwRelease') st.released = false;
        st.arm = null;
      } else {
        evalArm(a);
      }
    }
    if (!st.arm) {
      // Blend out of anything that ended off-guard (e.g. an empty hand) is handled by the guard itself.
      hand.p.copy(guard.p);
      hand.q.copy(guard.q);
    }

    updateRig(dt, pdt);
    updateBones();

    // Weapons ride the right hand.
    const shown = st.shown;
    sword.visible = st.visible && shown === 'sword';
    spear.visible = st.visible && shown === 'spear';
    sword.position.copy(hand.p);
    sword.quaternion.copy(hand.q);
    spear.position.copy(hand.p);
    spear.quaternion.copy(hand.q);

    shield.position.copy(shieldPose.p);
    shield.quaternion.copy(shieldPose.q);

    // Arrow quiver.
    let dirty = false;
    for (let i = 0; i < stuckCount; i++) {
      if (stuck[i].t < 0.6) {
        stuck[i].t += pdt;
        writeArrow(i);
        dirty = true;
      }
    }
    if (dirty) arrows.instanceMatrix.needsUpdate = true;
    arrows.visible = stuckCount > 0;

    trail.mesh.visible = st.visible && updateTrail(st.arm);
    rigRoot.visible = st.visible;

    updateLights(dt);

    // Overlay.
    const u = overlay.material.uniforms;
    u.uVignette.value = st.overlay.vignette;
    u.uHurt.value = st.overlay.hurt;
    u.uHelmet.value = st.overlay.helmet ? 1 : 0;
    u.uAspect.value = cam.aspect || 16 / 9;
    u.uTime.value = st.time;
    overlay.visible = st.overlay.helmet || st.overlay.vignette > 0.003 || st.overlay.hurt > 0.003;
  }

  // ---------------------------------------------------------------------------------------------
  // API

  return {
    setWeapon(w) {
      st.weapon = w === 'spear' ? 'spear' : 'sword';
    },
    setHasSpear(b) {
      st.hasSpear = !!b;
    },
    setShield(r) {
      st.raise = clamp(Number(r) || 0, 0, 1);
    },
    play,
    addStuckArrow,
    clearStuckArrows,
    setVisible(b) {
      st.visible = !!b;
    },
    reset() {
      st.arm = null;
      st.shieldFx.length = 0;
      st.rigFx.length = 0;
      st.forceUp = 0;
      st.forceDown = 0;
      st.released = false;
      st.prevYaw = null;
      st.swayYaw = st.swayYawV = st.swayPitch = st.swayPitchV = 0;
      st.speed = 0;
      st.shown = targetShown();
      st.snap = 0.2;
      st.overlay.vignette = 0;
      st.overlay.hurt = 0;
      const pl = ctx.player;
      if (pl && pl.pos) {
        st.prevX = pl.pos.x;
        st.prevZ = pl.pos.z;
      }
      clearStuckArrows();
      trail.hide();
    },
    setOverlay(o = {}) {
      if (typeof o.vignette === 'number') st.overlay.vignette = clamp(o.vignette, 0, 1);
      if (typeof o.hurt === 'number') st.overlay.hurt = clamp(o.hurt, 0, 1);
      if (typeof o.helmet === 'boolean') st.overlay.helmet = o.helmet;
    },
    update,
    /** Debug/test view of the internal state (not part of the contract). */
    get debug() {
      return { shown: st.shown, arm: st.arm && { kind: st.arm.kind, t: st.arm.t }, arrows: stuckCount, raise: st.raise, hand, shieldPose };
    },
  };
}
