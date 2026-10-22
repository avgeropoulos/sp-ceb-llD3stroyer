// Gameplay sandbox harness: real game/player.js + game/combat.js driven against stub actors that
// implement the §11 Actor API (capsule visuals with sockets and attach(), takeHit with the §7
// guard rule, knockback, the sea fall and a falling death), plus stub viewmodel/fx/ai/world.
// Used by gameplay-sandbox.html (screenshots) and tests/e2e/gameplay-checks.mjs (assertions).
import * as THREE from 'three';
import { createPlayer } from '../../src/main/assets/game/js/game/player.js';
import { createCombat } from '../../src/main/assets/game/js/game/combat.js';
import { ENEMIES } from '../../src/main/assets/game/js/game/rules.js';
import { facingTo } from '../../src/main/assets/game/js/core/math.js';

const COLORS = {
  levy: 0x8a6a45, sparabara: 0x9a8a4a, immortal: 0x5b3f7a, captain: 0x6e2a22, archer: 0x6a7a45,
  dummy: 0xb79b6b, spartan: 0xa8322a, helot: 0x9a9a8a,
};
const SEA_EDGE = 8.6;

export const EVENT_NAMES = [
  'swing', 'combo', 'playerHurt', 'playerDeath', 'scoreChanged', 'weaponSwitch', 'shield',
  'hit', 'kill', 'block', 'spearThrown', 'spearImpact', 'arrowHit', 'volley', 'enemyWindup',
];

const geoCache = {};
function capsuleGeo(height) {
  const key = height.toFixed(2);
  if (!geoCache[key]) geoCache[key] = new THREE.CapsuleGeometry(0.32, height - 0.64, 6, 14);
  return geoCache[key];
}

/** A stub Actor (§11) with a coloured capsule visual. */
export function makeStubActor(ctx, kind, x, z, opts = {}) {
  const stats = ENEMIES[kind] || ENEMIES.levy;
  const height = opts.height || (kind === 'captain' ? 1.95 : 1.78);
  const team = opts.team || (kind === 'spartan' ? 'greek' : 'persian');
  const role = opts.role || (kind === 'dummy' ? 'dummy' : kind === 'archer' ? 'archer' : team === 'greek' ? 'ally' : 'melee');

  const root = new THREE.Group();
  root.rotation.order = 'YXZ';
  const mat = new THREE.MeshStandardMaterial({ color: opts.color || COLORS[kind] || 0x888888, roughness: 0.8 });
  const body = new THREE.Mesh(capsuleGeo(height), mat);
  body.position.y = height / 2;
  body.castShadow = true;
  root.add(body);
  const nose = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.08, 0.14), new THREE.MeshStandardMaterial({ color: 0x222222 }));
  nose.position.set(0, height - 0.22, 0.31);
  root.add(nose);
  const sockets = {};
  const sock = (name, sx, sy, sz) => {
    const o = new THREE.Object3D();
    o.name = name;
    o.position.set(sx, sy, sz);
    root.add(o);
    sockets[name] = o;
  };
  sock('head', 0, height - 0.16, 0);
  sock('chest', 0, height * 0.72, 0);
  sock('rightHand', -0.3, 1.0, 0.2);
  sock('leftHand', 0.3, 1.0, 0.2);
  sock('back', 0, 1.3, -0.2);

  const clips = [];
  const visual = {
    root, mesh: body, kind, sockets,
    anim: {
      setLocomotion() {}, setGuard() {}, flinch() {}, update() {},
      play(clip, o) { clips.push(clip); return o; },
      get clip() { return clips[clips.length - 1] || null; },
      get playing() { return false; },
      get guard() { return 0; },
    },
    attach(obj, socketName) {
      const s = sockets[socketName] || root;
      root.updateMatrixWorld(true);
      s.attach(obj);
    },
    setBloodied() {}, setVisible(v) { root.visible = v; }, dispose() { root.removeFromParent(); },
  };
  if (ctx.scene) ctx.scene.add(root);

  const facing = Number.isFinite(opts.facing) ? opts.facing : facingTo(-x, -z);
  const a = {
    id: ++makeStubActor.nextId, kind, team, role, name: opts.name || `${kind}${makeStubActor.nextId}`,
    visual, pos: new THREE.Vector3(x, ctx.world.groundHeight(x, z), z), facing,
    hp: Number.isFinite(opts.hp) ? opts.hp : stats.hp, maxHp: Number.isFinite(opts.hp) ? opts.hp : stats.hp,
    radius: 0.32, height, alive: true, state: opts.state || 'engage', guard: 0,
    blockChance: Number.isFinite(opts.blockChance) ? opts.blockChance : stats.guard, staggerT: 0, lastBashedAt: -1e9,
    vel: opts.vel ? new THREE.Vector3(opts.vel[0], 0, opts.vel[1]) : null,
    hitsTaken: [], clips, deathT: -1, cause: null, fallFrom: null,
    takeHit(hit) {
      const res = { damage: 0, guarded: false, killed: false, cause: null };
      if (!a.alive) return res;
      a.hitsTaken.push({ ...hit, dir: { ...hit.dir }, at: ctx.time.gnow });
      const dx = hit.dir ? hit.dir.x : 0;
      const dz = hit.dir ? hit.dir.z : 0;
      const disabled = a.state === 'stagger' || a.state === 'knockdown';
      const ignore = !!hit.ignoreGuard || hit.kind === 'bash' || hit.kind === 'bleed' || disabled
        || ((hit.kind === 'thrust' || hit.kind === 'spearThrust') && hit.power > 0.8);
      const front = Math.sin(a.facing) * -dx + Math.cos(a.facing) * -dz > 0.5;
      const guarded = !ignore && front && a.blockChance > 0 && ctx.rng.chance(a.blockChance);
      const dmg = (hit.damage || 0) * (guarded ? 0.15 : 1);
      res.damage = dmg;
      res.guarded = guarded;
      const kb = hit.knockback || 0;
      if (a.role !== 'dummy' && a.team === 'persian' && kb > 0 && a.pos.x + dx * kb > SEA_EDGE) {
        a.fallFrom = a.pos.clone();
        a.pos.x += dx * kb;
        a.pos.z += dz * kb;
        res.damage = Math.max(dmg, a.hp);
        res.killed = true;
        res.cause = 'sea';
        die('sea');
        return res;
      }
      if (a.role !== 'dummy') a.hp -= dmg;
      if (a.hp <= 0) {
        a.hp = 0;
        res.killed = true;
        res.cause = 'weapon';
        die(hit.kind === 'spear' ? 'spear' : 'weapon');
        return res;
      }
      if (a.role !== 'dummy') {
        a.pos.x += dx * kb;
        a.pos.z += dz * kb;
        if (hit.stagger > 0 || hit.knockdown) {
          a.state = hit.knockdown ? 'knockdown' : 'stagger';
          a.staggerT = Math.max(a.staggerT, hit.knockdown ? 2.2 : hit.stagger);
        }
      }
      return res;
    },
    chestPoint(out) {
      return out.set(a.pos.x, a.pos.y + a.height * 0.72, a.pos.z);
    },
    update(gdt) {
      if (a.alive && a.vel) {
        a.pos.x += a.vel.x * gdt;
        a.pos.z += a.vel.z * gdt;
        a.pos.y = ctx.world.groundHeight(a.pos.x, a.pos.z);
      }
      if (a.alive && (a.state === 'stagger' || a.state === 'knockdown')) {
        a.staggerT -= gdt;
        if (a.staggerT <= 0) a.state = 'engage';
      }
      root.position.set(a.pos.x, a.alive ? a.pos.y : root.position.y, a.pos.z);
      root.rotation.y = a.facing;
      if (!a.alive) {
        a.deathT += gdt;
        const k = Math.min(1, a.deathT / 0.8);
        root.rotation.x = -(Math.PI / 2) * k * k; // topple backwards
        if (a.cause === 'sea') root.position.y = Math.max(-3, -3 * Math.min(1, a.deathT / 1.0) ** 2);
      }
    },
  };
  function die(cause) {
    a.alive = false;
    a.state = 'dead';
    a.cause = cause;
    a.deathT = 0;
    clips.push(cause === 'sea' ? 'fall_sea' : cause === 'spear' ? 'die_spear' : 'die_back');
    const i = ctx.actors.indexOf(a);
    if (i >= 0) ctx.actors.splice(i, 1);
    harnessCorpses(ctx).push(a);
  }
  ctx.actors.push(a);
  return a;
}
makeStubActor.nextId = 0;

function harnessCorpses(ctx) {
  if (!ctx.__corpses) ctx.__corpses = [];
  return ctx.__corpses;
}

/** World stub: flat pass, the sea beyond x = 9.5 (groundHeight −3), cliff plane and Phocian wall boxes. */
export function makeWorldStub() {
  return {
    groundHeight: (x) => (x > 9.5 ? -3 : 0),
    bounds: { minX: -7, maxX: 8.4, minZ: -6, maxZ: 3 },
    colliders: [
      { type: 'plane', point: [-9, 0, 0], normal: [1, 0, 0] },
      { type: 'box', min: [-9, 0, 11.6], max: [-2, 2.5, 12.4] },
      { type: 'box', min: [2, 0, 11.6], max: [9.5, 2.5, 12.4] },
    ],
    sunDirection: new THREE.Vector3(-30, 40, 25).normalize(),
    envMap: null,
    setTimeOfDay() {}, setArmyDensity() {}, rebuildEnvironment() {}, update() {},
  };
}

/**
 * Builds the gameplay modules on `ctx` (from createSandboxCtx or a headless stand-in) and returns
 * test helpers. opts.characters: an optional { createProp } to use real props.
 */
export function createHarness(ctx, opts = {}) {
  ctx.world = makeWorldStub();
  ctx.actors.length = 0;
  ctx.actions.clear();

  const vm = {
    calls: { setWeapon: 0, setHasSpear: 0, setShield: 0 },
    last: { weapon: null, hasSpear: null, shield: null },
    playCalls: 0,
    setWeapon(w) { vm.calls.setWeapon++; vm.last.weapon = w; },
    setHasSpear(b) { vm.calls.setHasSpear++; vm.last.hasSpear = b; },
    setShield(r) { vm.calls.setShield++; vm.last.shield = r; },
    play() { vm.playCalls++; },
    addStuckArrow() {}, clearStuckArrows() {}, setVisible() {}, reset() {}, setOverlay() {}, update() {},
  };
  const fx = {
    cameraOverridden: false,
    splashes: [],
    splash(p) { fx.splashes.push(p.clone ? p.clone() : p); },
    blood() {}, sparks() {}, dust() {}, splinters() {}, decal() {}, shake() {}, hitStop() {}, slowMo() {},
    particleCount: 0, applyCamera() {}, update() {}, reset() {},
  };
  const gestures = { recenters: 0, recenter() { gestures.recenters++; } };
  ctx.viewmodel = vm;
  ctx.fx = fx;
  ctx.gestures = gestures;
  if (opts.characters) ctx.characters = opts.characters;
  ctx.ai = {
    update(gdt) {
      for (const a of ctx.actors.slice()) a.update(gdt);
      for (const a of harnessCorpses(ctx)) a.update(gdt);
    },
    clear() {
      for (const a of ctx.actors) a.visual.dispose();
      for (const a of harnessCorpses(ctx)) a.visual.dispose();
      ctx.actors.length = 0;
      harnessCorpses(ctx).length = 0;
    },
  };

  let frame = 0;
  const log = [];
  const allLog = []; // never cleared: for payload validation across every check
  for (const name of EVENT_NAMES) {
    ctx.events.on(name, (payload) => {
      const e = { name, payload, t: ctx.time.gnow, frame };
      log.push(e);
      allLog.push(e);
    });
  }

  ctx.combat = createCombat(ctx);
  ctx.player = createPlayer(ctx);
  ctx.player.reset({ mode: 'battle' });

  function tick() {
    frame++;
    ctx.player.update(ctx.time.pdt);
    ctx.ai.update(ctx.time.gdt);
    ctx.combat.update(ctx.time.gdt);
    ctx.player.applyCamera(ctx.camera);
    ctx.camera.updateMatrixWorld();
  }

  const h = {
    ctx, log, allLog, vm, fx, gestures,
    get player() { return ctx.player; },
    get combat() { return ctx.combat; },
    get frame() { return frame; },
    tick,
    /** Steps n frames of dt through ctx.step (which calls tick via the registered loop). */
    step(n = 1, dt = 1 / 60) { ctx.step(n, dt); },
    stepTime(seconds, dt = 1 / 60) { ctx.step(Math.max(1, Math.round(seconds / dt)), dt); },
    /** Steps until pred() is true (checked after every frame). Returns frames stepped, or -1. */
    stepUntil(pred, maxFrames = 600, dt = 1 / 60) {
      for (let i = 1; i <= maxFrames; i++) {
        ctx.step(1, dt);
        if (pred()) return i;
      }
      return -1;
    },
    action(a) { ctx.actions.push({ src: 'test', power: 0.6, ...a }); },
    spawn(kind, x, z, o) { return makeStubActor(ctx, kind, x, z, o); },
    events(name) { return log.filter((e) => e.name === name); },
    clearLog() { log.length = 0; },
    setShield(held) {
      if (held && !ctx.input.shieldHeld) ctx.input.shieldPressedAt = ctx.time.gnow;
      ctx.input.shieldHeld = !!held;
    },
    /** Fresh battle: actors cleared, combat/player reset, input neutral, settings default. */
    reset(mode = 'battle') {
      ctx.ai.clear();
      ctx.combat.reset();
      ctx.actions.clear();
      Object.assign(ctx.input, { moveX: 0, moveZ: 0, lookYaw: 0, lookPitch: 0, shieldHeld: false, shieldPressedAt: -10, phoneSettled: true, motion: false });
      ctx.settings.aimAssist = true;
      ctx.settings.control = 'touch';
      fx.cameraOverridden = false;
      fx.splashes.length = 0;
      ctx.time.scale = 1;
      ctx.time.hitStop = 0;
      ctx.time.playerRealtime = false;
      ctx.mode = mode;
      ctx.player.reset({ mode });
      log.length = 0;
    },
  };
  ctx.harness = h;
  return h;
}

/** A headless ctx (no renderer) for running the harness under Node during development. */
export function createHeadlessCtx({ EventBus, createRng }) {
  const actionsQ = [];
  const updaters = [];
  const ctx = {
    THREE,
    scene: new THREE.Scene(),
    camera: new THREE.PerspectiveCamera(70, 16 / 9, 0.1, 2000),
    vm: { scene: new THREE.Scene(), camera: new THREE.PerspectiveCamera(60, 16 / 9, 0.01, 10) },
    events: new EventBus(),
    settings: { control: 'touch', sensitivity: 1, gyroLook: true, aimAssist: true, leftHanded: false, haptics: true },
    time: { now: 0, gnow: 0, dt: 0, gdt: 0, pdt: 0, scale: 1, hitStop: 0, playerRealtime: false },
    rng: createRng(1),
    input: { moveX: 0, moveZ: 0, lookYaw: 0, lookPitch: 0, shieldHeld: false, shieldPressedAt: -10, phoneSettled: true, motion: false },
    actions: { push: (a) => actionsQ.push(a), drain: () => actionsQ.splice(0, actionsQ.length), clear: () => { actionsQ.length = 0; } },
    mode: 'battle',
    setMode(m) { ctx.mode = m; },
    actors: [],
    loop(fn) { updaters.push(fn); },
    step(n = 1, dt = 1 / 60) {
      for (let i = 0; i < n; i++) {
        const t = ctx.time;
        t.dt = dt;
        t.now += dt;
        if (t.hitStop > 0) {
          t.hitStop = Math.max(0, t.hitStop - dt);
          t.gdt = 0;
          t.pdt = 0;
        } else {
          t.gdt = dt * t.scale;
          t.pdt = t.playerRealtime ? dt : t.gdt;
        }
        t.gnow += t.gdt;
        for (const fn of updaters) fn(t.dt, t.gdt);
      }
    },
  };
  ctx.camera.rotation.order = 'YXZ';
  return ctx;
}
