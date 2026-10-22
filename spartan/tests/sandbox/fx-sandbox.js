// fx sandbox: a stand-in pass (sky, dusty floor, sea, cliff), stub actors, a stub combat that
// flies a ballistic spear, and a main.js-like frame step (pause-aware) driving gfx/fx.js.
import * as THREE from 'three';
import { Sky } from 'three/addons/Sky.js';
import { createSandboxCtx } from './sandbox-ctx.js';
import { createFx } from '../../src/main/assets/game/js/gfx/fx.js';
import { lerp, smoothstep, clamp, forward3, right3, yawTo } from '../../src/main/assets/game/js/core/math.js';

const params = new URLSearchParams(location.search);
const SCENE = params.get('scene') || 'all';
const MANUAL = params.get('manual') === '1';
const useWorld = params.get('world') === '1';

const ctx = await createSandboxCtx({ withWorld: useWorld });
const { scene, camera, renderer } = ctx;
if (params.get('fov')) {
  camera.fov = Number(params.get('fov'));
  camera.updateProjectionMatrix();
}
if (params.get('blood') === '0') ctx.settings.blood = false;
ctx.input.motion = params.get('motion') === '1';

// ---- Environment ------------------------------------------------------------------------------

function floorHeight(x, z) {
  let y = 0.12 * Math.sin(x * 0.7 + 0.3) * Math.cos(z * 0.45) + 0.05 * Math.sin(x * 2.1 + z * 1.3) + 0.025 * Math.sin(x * 4.3 - z * 3.1);
  if (x > 9) y = lerp(y, -3.6, smoothstep(9, 10.6, x));
  if (x < -8.6) y += (-8.6 - x) * 3;
  return y;
}
const groundHeight = (x, z) => Math.max(floorHeight(x, z), x > 9.4 ? -3 : -10);

const sunDir = new THREE.Vector3(-30, 40, 25).normalize();
if (!useWorld || !ctx.world || !ctx.world.rebuildEnvironment || ctx.world.groundHeight.length === 0) {
  // Replace sandbox-ctx's flat plane with a small Hot Gates stand-in.
  for (const o of scene.children.slice()) if (o.isMesh && o.geometry && o.geometry.type === 'PlaneGeometry') scene.remove(o);
  const sky = new Sky();
  sky.scale.setScalar(4000);
  const u = sky.material.uniforms;
  u.turbidity.value = 3.5;
  u.rayleigh.value = 1.4;
  u.mieCoefficient.value = 0.004;
  u.mieDirectionalG.value = 0.8;
  u.sunPosition.value.copy(sunDir);
  u.cloudCoverage.value = 0.25;
  scene.add(sky);
  scene.background = null;
  const pmrem = new THREE.PMREMGenerator(renderer);
  const skyScene = new THREE.Scene();
  const sky2 = new Sky();
  sky2.scale.setScalar(900);
  sky2.material.uniforms.sunPosition.value.copy(sunDir);
  sky2.material.uniforms.showSunDisc.value = 0;
  skyScene.add(sky2);
  scene.environment = pmrem.fromScene(skyScene, 0, 0.1, 1000).texture;
  scene.environmentIntensity = 0.35;
  sky.material.uniforms.showSunDisc.value = 1;
  renderer.toneMappingExposure = 0.55;
  scene.fog = new THREE.FogExp2(0xc9c3b4, 0.0065);

  const cv = document.createElement('canvas');
  cv.width = cv.height = 512;
  const g = cv.getContext('2d');
  g.fillStyle = '#8d7a60';
  g.fillRect(0, 0, 512, 512);
  let s = 7;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  for (let i = 0; i < 9000; i++) {
    const v = rnd();
    const c = v < 0.5 ? `rgba(70,58,44,${0.08 + rnd() * 0.15})` : `rgba(190,170,140,${0.06 + rnd() * 0.12})`;
    g.fillStyle = c;
    g.beginPath();
    g.ellipse(rnd() * 512, rnd() * 512, 0.5 + rnd() * 4, 0.5 + rnd() * 3, rnd() * 3, 0, Math.PI * 2);
    g.fill();
  }
  for (let i = 0; i < 260; i++) {
    g.fillStyle = `rgba(${120 + rnd() * 60},${105 + rnd() * 50},${90 + rnd() * 40},0.9)`;
    g.beginPath();
    g.ellipse(rnd() * 512, rnd() * 512, 1.5 + rnd() * 4, 1 + rnd() * 3, rnd() * 3, 0, Math.PI * 2);
    g.fill();
  }
  const dirt = new THREE.CanvasTexture(cv);
  dirt.colorSpace = THREE.SRGBColorSpace;
  dirt.wrapS = dirt.wrapT = THREE.RepeatWrapping;
  dirt.repeat.set(40, 40);
  dirt.anisotropy = 8;
  const fg = new THREE.PlaneGeometry(120, 120, 240, 240);
  fg.rotateX(-Math.PI / 2);
  const pos = fg.attributes.position;
  for (let i = 0; i < pos.count; i++) pos.setY(i, floorHeight(pos.getX(i), pos.getZ(i)));
  fg.computeVertexNormals();
  const floor = new THREE.Mesh(fg, new THREE.MeshStandardMaterial({ map: dirt, roughness: 0.95, metalness: 0 }));
  floor.receiveShadow = true;
  scene.add(floor);
  const sea = new THREE.Mesh(new THREE.PlaneGeometry(400, 400), new THREE.MeshStandardMaterial({ color: 0x1d4656, roughness: 0.08, metalness: 0 }));
  sea.rotation.x = -Math.PI / 2;
  sea.position.set(209.5, -3, -60);
  scene.add(sea);
  const cliff = new THREE.Mesh(new THREE.BoxGeometry(6, 40, 240), new THREE.MeshStandardMaterial({ color: 0x7d7264, roughness: 1 }));
  cliff.position.set(-13, 16, -40);
  cliff.receiveShadow = true;
  scene.add(cliff);
  ctx.world.groundHeight = groundHeight;
  ctx.world.sunDirection = sunDir;
}
if (ctx.sandboxLights) {
  ctx.sandboxLights.sun.shadow.camera.updateProjectionMatrix();
  ctx.sandboxLights.sun.shadow.bias = -0.0005;
}

// ---- Stub actors --------------------------------------------------------------------------------

const robe = new THREE.MeshStandardMaterial({ color: 0x6a3b52, roughness: 0.85 });
const trousers = new THREE.MeshStandardMaterial({ color: 0x4b4636, roughness: 0.9 });
const skin = new THREE.MeshStandardMaterial({ color: 0xa8775a, roughness: 0.7 });
const felt = new THREE.MeshStandardMaterial({ color: 0xc9b98f, roughness: 0.95 });
const wickerMat = new THREE.MeshStandardMaterial({ color: 0x9c7d4c, roughness: 0.9 });
const bronze = new THREE.MeshStandardMaterial({ color: 0xc69a5a, metalness: 1, roughness: 0.32 });
const ash = new THREE.MeshStandardMaterial({ color: 0x8a6a45, roughness: 0.7 });
const actors = [];
ctx.actors = actors;

function makeActor(x, z, { kind = 'levy', shield = false, facing = 0 } = {}) {
  const root = new THREE.Group();
  const add = (geo, mat, px, py, pz, rx = 0) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(px, py, pz);
    m.rotation.x = rx;
    m.castShadow = true;
    root.add(m);
    return m;
  };
  add(new THREE.CylinderGeometry(0.09, 0.07, 0.85, 10), trousers, -0.11, 0.43, 0);
  add(new THREE.CylinderGeometry(0.09, 0.07, 0.85, 10), trousers, 0.11, 0.43, 0);
  add(new THREE.CylinderGeometry(0.2, 0.27, 0.72, 14), robe, 0, 1.18, 0);
  add(new THREE.CylinderGeometry(0.07, 0.06, 0.6, 8), robe, -0.3, 1.2, 0.05, 0.3);
  add(new THREE.CylinderGeometry(0.07, 0.06, 0.6, 8), robe, 0.3, 1.2, 0.05, 0.3);
  add(new THREE.SphereGeometry(0.115, 16, 12), skin, 0, 1.64, 0);
  add(new THREE.ConeGeometry(0.13, 0.22, 14), felt, 0, 1.8, 0);
  if (shield) add(new THREE.BoxGeometry(0.62, 0.95, 0.05), wickerMat, -0.1, 1.05, 0.38);
  root.rotation.y = facing;
  const gy = groundHeight(x, z);
  root.position.set(x, gy, z);
  scene.add(root);
  const actor = {
    id: actors.length + 1, kind, team: 'persian', role: 'melee', pos: new THREE.Vector3(x, gy, z), facing,
    radius: 0.32, height: 1.75, alive: true, state: 'engage', visual: { root },
    fall: null,
    chestPoint(out) {
      return out.set(this.pos.x, this.pos.y + 1.3, this.pos.z);
    },
  };
  actors.push(actor);
  return actor;
}

function makeShield(x, y, z) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.45, 0.05, 48), bronze);
  m.rotation.x = Math.PI / 2;
  m.rotation.z = 0.15;
  m.position.set(x, y, z);
  m.castShadow = true;
  scene.add(m);
  return m;
}

const kQ = new THREE.Quaternion();
const kAxis = new THREE.Vector3();
function updateActors(gdt) {
  for (const a of actors) {
    if (!a.fall || gdt <= 0) continue;
    a.fall.t = Math.min(1, a.fall.t + gdt / 0.75);
    const e = a.fall.t * a.fall.t;
    kAxis.set(0, 1, 0).cross(a.fall.dir).normalize();
    kQ.setFromAxisAngle(kAxis, (Math.PI / 2) * e);
    a.visual.root.quaternion.copy(kQ).multiply(a.fall.base);
  }
}

// ---- Stub combat: a ballistic spear ---------------------------------------------------------

const spearMesh = new THREE.Group();
{
  const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.014, 0.017, 2.3, 8), ash);
  shaft.rotation.x = Math.PI / 2;
  spearMesh.add(shaft);
  const tip = new THREE.Mesh(new THREE.ConeGeometry(0.028, 0.24, 10), bronze);
  tip.rotation.x = Math.PI / 2;
  tip.position.z = 1.25;
  spearMesh.add(tip);
  spearMesh.traverse((o) => (o.castShadow = true));
  spearMesh.visible = false;
  scene.add(spearMesh);
}
const projectiles = [];
const Zp = new THREE.Vector3(0, 0, 1);
const vd = new THREE.Vector3();

/** Mimics combat.throwSpear: emits spearThrown with a live projectile object. */
function throwSpear({ origin, velocity, src = 'touch', byPlayer = true }) {
  const pr = {
    kind: 'spear', mesh: spearMesh, pos: origin.clone(), prevPos: origin.clone(), vel: velocity.clone(),
    alive: true, stuck: false, byPlayer, power: 0.8, src, target: null, hits: [], age: 0, impactPoint: null,
  };
  if (spearMesh.parent !== scene) scene.attach(spearMesh);
  spearMesh.visible = true;
  orient(pr);
  projectiles.length = 0;
  projectiles.push(pr);
  ctx.events.emit('spearThrown', { projectile: pr });
  return pr;
}

function orient(pr) {
  vd.copy(pr.vel).normalize();
  spearMesh.quaternion.setFromUnitVectors(Zp, vd);
  spearMesh.position.copy(pr.pos).addScaledVector(vd, -1.2);
}

const segA = new THREE.Vector3();
function updateProjectiles(gdt) {
  for (const pr of projectiles) {
    if (!pr.alive || gdt <= 0) continue;
    pr.prevPos.copy(pr.pos);
    pr.vel.y -= 9.81 * gdt;
    pr.pos.addScaledVector(pr.vel, gdt);
    pr.age += gdt;
    orient(pr);
    // Actors (segment vs vertical capsule axis, in XZ).
    for (const a of actors) {
      if (!a.alive) continue;
      const sx = pr.pos.x - pr.prevPos.x;
      const sz = pr.pos.z - pr.prevPos.z;
      const l2 = sx * sx + sz * sz;
      const s = clamp(l2 > 1e-9 ? ((a.pos.x - pr.prevPos.x) * sx + (a.pos.z - pr.prevPos.z) * sz) / l2 : 0, 0, 1);
      segA.lerpVectors(pr.prevPos, pr.pos, s);
      const dx = segA.x - a.pos.x;
      const dz = segA.z - a.pos.z;
      if (dx * dx + dz * dz <= 0.38 * 0.38 && segA.y >= a.pos.y && segA.y <= a.pos.y + a.height) {
        hitActor(pr, a, segA);
        break;
      }
    }
    if (!pr.alive) continue;
    const gy = groundHeight(pr.pos.x, pr.pos.z);
    if (pr.pos.y <= gy) {
      pr.alive = false;
      pr.stuck = true;
      const water = gy <= -2.95;
      pr.pos.y = gy;
      pr.impactPoint = pr.pos.clone();
      orient(pr);
      ctx.events.emit('spearImpact', { projectile: pr, target: null, point: pr.impactPoint, normal: new THREE.Vector3(0, 1, 0), water });
      if (water) {
        fx.splash(pr.impactPoint);
        spearMesh.visible = false;
      }
    }
  }
}

function hitActor(pr, a, point) {
  pr.alive = false;
  pr.stuck = true;
  pr.target = a;
  pr.impactPoint = point.clone();
  pr.pos.copy(point);
  orient(pr);
  vd.copy(pr.vel).setY(0).normalize();
  a.alive = false;
  ctx.events.emit('hit', {
    target: a, attacker: 'player', damage: 200, kind: 'spear', dir: { x: vd.x, z: vd.z }, point: pr.impactPoint,
    guarded: false, critical: false, killed: true, byPlayer: true, combo: null,
  });
  ctx.events.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'spear', spear: true, cause: 'weapon' });
  ctx.events.emit('spearImpact', { projectile: pr, target: a, point: pr.impactPoint, normal: vd.clone().negate(), water: false });
  a.visual.root.attach(spearMesh);
  a.fall = { t: 0, dir: vd.clone(), base: a.visual.root.quaternion.clone() };
}

function ballisticPitch(speed, dxz, dy, g = 9.81) {
  const v2 = speed * speed;
  const disc = v2 * v2 - g * (g * dxz * dxz + 2 * dy * v2);
  if (disc < 0) return null;
  return Math.atan((v2 - Math.sqrt(disc)) / (g * dxz));
}

/** Player throw per DESIGN.md §7 Throw, at a world target point. */
function playerThrow(target, { src = 'touch', speed = 27 } = {}) {
  const eye = playerEye();
  const yaw = view.yaw;
  const r = right3(yaw);
  const f = forward3(yaw, 0);
  const origin = new THREE.Vector3(eye.x + r[0] * 0.22 + f[0] * 0.35, eye.y + 0.1, eye.z + r[2] * 0.22 + f[2] * 0.35);
  const dx = target.x - origin.x;
  const dz = target.z - origin.z;
  const yawL = yawTo(dx, dz);
  const pitchL = ballisticPitch(speed, Math.hypot(dx, dz), target.y - origin.y) ?? view.pitch + 0.06;
  const v = forward3(yawL, pitchL);
  return throwSpear({ origin, velocity: new THREE.Vector3(v[0] * speed, v[1] * speed, v[2] * speed), src });
}

// ---- Player camera stand-in -------------------------------------------------------------------

const view = { x: 0, z: 0, yaw: 0, pitch: 0 };
const eyeV = new THREE.Vector3();
function playerEye() {
  return eyeV.set(view.x, groundHeight(view.x, view.z) + 1.66, view.z);
}
function playerApplyCamera() {
  camera.position.copy(playerEye());
  camera.rotation.set(view.pitch, view.yaw, 0, 'YXZ');
}

// ---- fx -----------------------------------------------------------------------------------------

const hapticLog = [];
ctx.platform.haptics = {
  pulse: (ms, strength = 1) => hapticLog.push({ t: ctx.time.now, kind: 'pulse', ms, strength }),
  pattern: (arr) => hapticLog.push({ t: ctx.time.now, kind: 'pattern', arr: Array.from(arr) }),
};
const spearCamLog = [];
ctx.events.on('spearCam', (e) => spearCamLog.push({ t: ctx.time.now, phase: e.phase }));

const fx = createFx(ctx);
ctx.fx = fx;

// ---- Scenes -------------------------------------------------------------------------------------

const P = (x, y, z) => new THREE.Vector3(x, y, z);
let settledMode = params.get('settled') || 'auto';
let frameNo = 0;
let throwFrame = -1;
const script = [];
const at = (frame, fn) => script.push({ frame, fn });

function setupScene(name) {
  switch (name) {
    case 'empty':
      break;
    case 'blood': {
      const a = makeActor(0.15, -2.3);
      view.pitch = 0.16;
      at(1, () => {
        // A leftward, slightly rising slash (§3.4 dir = screen angle of the blade's motion).
        ctx.events.emit('swing', { kind: 'slash', dir: Math.PI - 0.35, power: 0.9, weapon: 'sword', src: 'test' });
        ctx.events.emit('hit', {
          target: a, attacker: 'player', damage: 45, kind: 'slash', dir: { x: -0.1, z: -1 }, point: P(0.12, 1.42, -2.15),
          guarded: false, critical: true, killed: false, byPlayer: true, combo: null,
        });
      });
      break;
    }
    case 'sparks': {
      makeShield(-0.25, 1.35, -1.9);
      view.pitch = 0.0;
      view.yaw = 0.08;
      at(1, () => fx.sparks(P(-0.12, 1.5, -1.86), P(0.4, 0.3, 1), 1.6));
      break;
    }
    case 'dust': {
      view.pitch = -0.2;
      at(1, () => fx.dust(P(0.2, groundHeight(0.2, -3), -3), 1.6));
      at(4, () => fx.dust(P(-0.8, groundHeight(-0.8, -3.6), -3.6), 0.8));
      break;
    }
    case 'splinters': {
      const a = makeActor(0.2, -3.4, { shield: true });
      view.pitch = Number(params.get('pitch') || -0.05);
      at(1, () => {
        ctx.events.emit('hit', {
          target: a, attacker: 'player', damage: 6, kind: 'slash', dir: { x: -0.4, z: -0.92 }, point: P(0.1, 1.15, -3.02),
          guarded: true, critical: false, killed: false, byPlayer: true, combo: null,
        });
        fx.splinters(P(0.1, 1.15, -3.0), P(0.6, 0.2, 0.4), 1.2);
      });
      break;
    }
    case 'splash': {
      view.yaw = -1.05;
      view.pitch = -0.22;
      view.x = 6;
      at(1, () => fx.splash(P(13, -3, -4), Number(params.get('amount') || 1.6)));
      break;
    }
    case 'decals': {
      view.pitch = -0.72;
      const a = makeActor(0.6, -3.2);
      const b = makeActor(-1.3, -2.4);
      at(1, () => {
        ctx.events.emit('hit', { target: a, attacker: 'player', damage: 60, kind: 'slash', dir: { x: 0.2, z: -1 }, point: P(0.6, 1.3, -3.2), guarded: false, critical: false, killed: true, byPlayer: true, combo: null });
        ctx.events.emit('kill', { target: a, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon' });
        a.alive = false;
        a.fall = { t: 0, dir: P(0.2, 0, -1).normalize(), base: a.visual.root.quaternion.clone() };
      });
      at(20, () => {
        ctx.events.emit('hit', { target: b, attacker: a, damage: 40, kind: 'slash', dir: { x: -1, z: 0.2 }, point: P(-1.3, 1.25, -2.4), guarded: false, critical: false, killed: false, byPlayer: false, combo: null });
        fx.blood(P(0.4, 1.0, -1.6), P(-0.2, -1, -0.3), 1.6);
        fx.decal(P(-0.6, 0, -1.5), 'blood', 0.5);
        fx.decal(P(1.4, 0, -1.9), 'scuff', 0.8);
      });
      break;
    }
    case 'spear': {
      const target = params.get('target') || 'hit';
      const src = params.get('motion') === '1' ? 'motion' : 'touch';
      const a = makeActor(1.0, -16);
      makeActor(-2.2, -21);
      makeActor(3.4, -24);
      view.pitch = 0.04;
      at(2, () => {
        throwFrame = frameNo;
        if (target === 'hit') playerThrow(P(a.pos.x, a.pos.y + 1.3, a.pos.z), { src });
        else if (target === 'sea') {
          view.yaw = -0.7;
          playerThrow(P(14, -3, -12), { src, speed: 26 });
        } else playerThrow(P(-1.6, groundHeight(-1.6, -14), -14), { src });
      });
      break;
    }
    default: {
      const a = makeActor(0.5, -2.6, { shield: true });
      makeShield(-0.9, 1.3, -2.2);
      view.pitch = 0.05;
      at(1, () => {
        fx.blood(P(0.5, 1.4, -2.4), P(-1, 0.1, -0.4), 1.4);
        fx.sparks(P(-0.8, 1.45, -2.15), P(0.3, 0.3, 1), 1.2);
        fx.dust(P(1.8, groundHeight(1.8, -3.5), -3.5), 1.2);
      });
      break;
    }
  }
}
setupScene(SCENE);

// ---- Frame (DESIGN.md §10, as main.js does it) -------------------------------------------------

let paused = false;
let recording = null;
function frame(dt) {
  const t = ctx.time;
  ctx.mode = paused ? 'paused' : 'battle';
  t.dt = dt;
  t.now += dt;
  if (!paused && t.hitStop > 0) t.hitStop = Math.max(0, t.hitStop - dt);
  const stopped = paused || t.hitStop > 0;
  t.gdt = stopped ? 0 : dt * t.scale;
  t.pdt = stopped ? 0 : t.playerRealtime ? dt : t.gdt;
  t.gnow += t.gdt;
  if (!paused) {
    frameNo++;
    for (const s of script) if (s.frame === frameNo) s.fn();
    if (settledMode === 'auto') ctx.input.phoneSettled = throwFrame < 0 || frameNo - throwFrame > 30;
    else ctx.input.phoneSettled = settledMode !== '0';
  }
  updateProjectiles(t.gdt);
  updateActors(t.gdt);
  fx.update(paused ? 0 : dt, t.gdt);
  playerApplyCamera();
  fx.applyCamera(camera);
  camera.updateMatrixWorld();
  if (recording) recording.push(state());
}

function state() {
  const t = ctx.time;
  return {
    frame: frameNo, now: t.now, gnow: t.gnow, gdt: t.gdt, scale: t.scale, hitStop: t.hitStop, playerRealtime: t.playerRealtime,
    phase: fx.spearCamPhase, overridden: fx.cameraOverridden, particles: fx.particleCount, decals: fx.decalCount,
  };
}

const hud = document.getElementById('fx-hud');
function render() {
  ctx.render();
  const s = state();
  hud.textContent = `${SCENE}  cam:${s.phase}  scale:${s.scale.toFixed(2)}  hitStop:${s.hitStop.toFixed(3)}  particles:${s.particles}  decals:${s.decals}  calls:${renderer.info.render.calls}`;
}

function step(n = 1, dt = 1 / 60) {
  for (let i = 0; i < n; i++) frame(dt);
  render();
}

/** Draw calls with and without the fx meshes (main pass only). */
function drawCalls() {
  const fxMeshes = [];
  scene.traverse((o) => {
    if (o.name === 'fx-particles' || o.name === 'fx-decals') fxMeshes.push(o);
  });
  render();
  const total = renderer.info.render.calls;
  const vis = fxMeshes.map((m) => m.visible);
  fxMeshes.forEach((m) => (m.visible = false));
  ctx.render();
  const without = renderer.info.render.calls;
  fxMeshes.forEach((m, i) => (m.visible = vis[i]));
  render();
  return { total, fx: total - without, fxVisible: vis.filter(Boolean).length };
}

window.__sandbox.step = step;
window.__fx = {
  ctx, fx, THREE, P, actors, hapticLog, spearCamLog, view, frame,
  step, state, drawCalls, makeActor, makeShield, playerThrow, throwSpear, groundHeight,
  setPaused: (p) => (paused = !!p),
  setSettled: (m) => (settledMode = String(m)),
  setMotion: (m) => (ctx.input.motion = !!m),
  emit: (name, payload) => ctx.events.emit(name, payload),
  record: (on) => {
    const out = recording;
    recording = on ? [] : null;
    return out;
  },
  /** Steps n frames recording state, returns the samples. */
  run(n, dt = 1 / 60) {
    const out = [];
    for (let i = 0; i < n; i++) {
      frame(dt);
      out.push(state());
    }
    render();
    return out;
  },
  get projectile() {
    return projectiles[0] || null;
  },
};

step(0);
if (!MANUAL) {
  let last = performance.now();
  const loop = (now) => {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    step(1, dt);
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
}
