// Regression tests for the scene fixer's review fixes (game/combat.js, gfx/world.js, gfx/world/*,
// gfx/fx.js, gfx/fx/*): volleys that read as a dark cloud (distance LOD, motion-blur streaks, a
// tight loose and a towering arc) and a deep shade; the spear-cam trail offset up and away from
// the cliff; dawn and dusk skies that neither go to night nor blow out; stuck arrows out of the
// shadow pass and stuck spears casting only near the camera; blood pools that merge instead of
// stacking; and half the dust right at the camera.
// Run: node --test spartan/tests/unit/scene-fixes.test.mjs
import { register } from 'node:module';
import test from 'node:test';
import assert from 'node:assert/strict';

const LIB = new URL('../../src/main/assets/game/lib/three/', import.meta.url).href;
const hook = `
const LIB = ${JSON.stringify(LIB)};
export async function resolve(spec, context, next) {
  if (spec === 'three') return { url: LIB + 'three.module.js', shortCircuit: true };
  if (spec.startsWith('three/addons/')) return { url: LIB + 'addons/' + spec.slice(13), shortCircuit: true };
  return next(spec, context);
}`;
register(`data:text/javascript,${encodeURIComponent(hook)}`, import.meta.url);

const THREE = await import('three');
const { EventBus } = await import('../../src/main/assets/game/js/core/events.js');
const { createRng } = await import('../../src/main/assets/game/js/core/math.js');
const { createHarness, createHeadlessCtx } = await import('../sandbox/gameplay-harness.js');
const { PACK } = await import('../../src/main/assets/game/js/gfx/fx/streaks.js');
const { SHADE } = await import('../../src/main/assets/game/js/gfx/world.js');
const { PRESETS, sunDirectionFor, skyCoefficients, skyRadiance, acesFilmic } = await import('../../src/main/assets/game/js/gfx/world/sky.js');
const { createDecals, MAX_OVERLAP } = await import('../../src/main/assets/game/js/gfx/fx/decals.js');
const { createParticles, DUST_NEAR } = await import('../../src/main/assets/game/js/gfx/fx/particles.js');
const { createFx } = await import('../../src/main/assets/game/js/gfx/fx.js');

const ctx = createHeadlessCtx({ EventBus, createRng });
const h = createHarness(ctx);
ctx.loop(() => h.tick());

const volleyMesh = () => h.combat.volleyMesh;
const streakMesh = () => volleyMesh().getObjectByName('volleyStreaks');

/** Flight arrows now: [{ x, y, z, scale }] from the instance matrices past the 200 ground slots. */
function flightArrows() {
  const m = new THREE.Matrix4();
  const p = new THREE.Vector3();
  const q = new THREE.Quaternion();
  const s = new THREE.Vector3();
  const out = [];
  const mesh = volleyMesh();
  for (let i = 200; i < mesh.count; i++) {
    mesh.getMatrixAt(i, m);
    m.decompose(p, q, s);
    if (s.x === 0) continue;
    out.push({ x: p.x, y: p.y, z: p.z, scale: s.x });
  }
  return out;
}

/** Streak instances with a non-zero opacity: [{ hx, hy, hz, tx, ty, tz, a }]. */
function liveStreaks() {
  const g = streakMesh().geometry;
  const head = g.getAttribute('iHead');
  const tail = g.getAttribute('iTail');
  const par = g.getAttribute('iPar');
  const out = [];
  for (let i = 0; i < g.instanceCount; i++) {
    const a = par.getX(i);
    if (a > 0) out.push({ hx: head.getX(i), hy: head.getY(i), hz: head.getZ(i), tx: tail.getX(i), ty: tail.getY(i), tz: tail.getZ(i), a });
  }
  return out;
}

function untilLoosed() {
  const n = h.stepUntil(() => h.events('volley').some((e) => e.payload.phase === 'loosed'), 600);
  assert.ok(n > 0, 'the volley looses');
}

test('a volley is a cloud: every arrow aloft at once, streaked, LOD-scaled, arcing high', () => {
  h.reset();
  ctx.player.pos.set(0, 0, 0);
  h.combat.volley({ count: 300, duration: 1.0, warn: 0.5 });
  untilLoosed();
  assert.ok(streakMesh(), 'streak mesh under volleyArrows');
  assert.equal(streakMesh().geometry.index.count, 6 * (1 + PACK), 'one quad per streak of the packet');
  h.stepTime(0.17); // the archers loose within 0.15 s of each other
  assert.equal(flightArrows().length, 300, 'all 300 arrows in the air');
  const s = liveStreaks();
  assert.equal(s.length, 300, 'a streak per flying arrow');
  assert.ok(streakMesh().visible);
  // The block they leave from is narrow (≤ 8 m across).
  const xs = flightArrows().map((a) => a.x);
  assert.ok(Math.max(...xs) - Math.min(...xs) < 10, `launch block ${(Math.max(...xs) - Math.min(...xs)).toFixed(1)} m`);
  h.stepTime(0.5);
  const far = flightArrows();
  assert.ok(far.every((a) => a.scale >= 1 - 1e-6 && a.scale <= 4 + 1e-6), 'LOD scale within [1, 4]');
  assert.ok(far.filter((a) => a.scale > 2).length > 150, 'arrows 30+ m out are drawn at least twice as thick');
  // Streaks run from the tip back past the tail: longer than the arrow itself.
  for (const k of liveStreaks().slice(0, 50)) {
    const len = Math.hypot(k.hx - k.tx, k.hy - k.ty, k.hz - k.tz);
    assert.ok(len > 0.9, `streak ${len.toFixed(2)} m`);
  }
  // A towering arc: the cloud peaks well above head height.
  let top = 0;
  for (let i = 0; i < 40; i++) {
    h.step(3);
    for (const a of flightArrows()) top = Math.max(top, a.y);
  }
  assert.ok(top > 12, `apex ${top.toFixed(1)} m`);
  // Once they have all landed, no streaks remain.
  h.stepTime(2.5);
  assert.equal(flightArrows().length, 0);
  assert.equal(streakMesh().visible, false);
  h.combat.reset();
  assert.equal(streakMesh().geometry.instanceCount, 0);
});

test('the volley shade hides the sun and darkens the whole view', () => {
  assert.equal(SHADE.sun, 0.85);
  assert.equal(SHADE.exposure, 0.2);
  assert.ok(SHADE.shadow >= 0.7);
});

test('stuck arrows never cast shadows', () => {
  h.reset();
  ctx.player.pos.set(0, 0, 0);
  const archer = h.spawn('archer', 0, -20);
  // One at the ground in front, one at an ally.
  const ally = h.spawn('spartan', 2, 0, { role: 'ally' });
  ally.team = 'greek';
  ally.role = 'ally';
  h.combat.shootArrow(archer, ally);
  h.stepTime(1.5);
  const arrows = [];
  ctx.scene.traverse((o) => {
    if (/arrow/.test(o.name) && o.isMesh && !o.isInstancedMesh) arrows.push(o);
  });
  assert.ok(arrows.length >= 1, 'an arrow stuck somewhere');
  for (const a of arrows) assert.equal(a.castShadow, false, `${a.name} casts no shadow`);
});

test('stuck spears cast shadows only within 8 m of the camera', () => {
  h.reset();
  ctx.player.pos.set(0, 0, 0);
  h.step(2);
  const near = h.combat.throwSpear({ origin: [0.5, 1.6, -1], velocity: [0, -2, -3], power: 0.3, byPlayer: false });
  const far = h.combat.throwSpear({ origin: [0.5, 1.6, -1], velocity: [0, 2, -10], power: 0.3, byPlayer: false });
  h.stepTime(2);
  assert.ok(near.stuck && far.stuck, 'both stuck in the ground');
  const cam = ctx.camera.position;
  assert.ok(near.mesh.position.distanceTo(cam) < 8 && far.mesh.position.distanceTo(cam) > 8);
  assert.equal(near.mesh.castShadow, true);
  assert.equal(far.mesh.castShadow, false);
  // Walk up to the far one: it casts again.
  ctx.player.pos.set(0.5, 0, -4);
  h.step(2);
  assert.ok(far.mesh.position.distanceTo(ctx.camera.position) < 8);
  assert.equal(far.mesh.castShadow, true);
  h.combat.reset();
});

/** Tone-mapped sRGB luma (0–255) of the sky toward `dir` for a preset (the CPU twin of the Sky). */
function skyLuma(p, dir) {
  const sun = sunDirectionFor(p);
  const coef = skyCoefficients(sun, p.turbidity, p.rayleigh, p.mie);
  const tm = acesFilmic(skyRadiance(dir, sun, coef, p.mieG, [0, 0, 0], p.tint || [1, 1, 1]), p.exposure);
  const srgb = (v) => (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
  return { luma: 255 * (0.2126 * srgb(tm[0]) + 0.7152 * srgb(tm[1]) + 0.0722 * srgb(tm[2])), sat: 1 - Math.min(...tm) / Math.max(...tm) };
}
const UPPER = [Math.cos(0.44) * Math.sin(0.21), Math.sin(0.44), -Math.cos(0.44) * Math.cos(0.21)]; // 25° up, down the pass

test('dawn is a pale morning sky, not night; dusk glows instead of blowing out to white', () => {
  const dawn = skyLuma(PRESETS.dawn, UPPER);
  assert.ok(dawn.luma > 60, `dawn upper sky luma ${dawn.luma.toFixed(0)} (navy below 50)`);
  const dusk = skyLuma(PRESETS.dusk, UPPER);
  assert.ok(dusk.luma < 150, `dusk upper sky luma ${dusk.luma.toFixed(0)} (white above 180)`);
  assert.ok(dusk.sat > 0.35, `dusk upper sky saturation ${dusk.sat.toFixed(2)}`);
  // The low dawn sun no longer floods the cliff like noon.
  assert.ok(PRESETS.dawn.sun < PRESETS.morning.sun);
  for (const p of Object.values(PRESETS)) assert.ok(p.exposure > 0.5 && p.exposure < 3);
});

function fxRig() {
  const c = {
    THREE,
    scene: new THREE.Scene(),
    camera: new THREE.PerspectiveCamera(56, 2.2, 0.1, 2000),
    events: new EventBus(),
    time: { now: 0, gnow: 0, dt: 0, gdt: 0, pdt: 0, scale: 1, hitStop: 0, playerRealtime: false },
    settings: { blood: true },
    input: { motion: false, phoneSettled: true },
    mode: 'battle',
    actors: [],
    world: { groundHeight: () => 0, colliders: [{ type: 'plane', point: [-9.5, 0, 0], normal: [1, 0, 0] }] },
  };
  c.camera.rotation.order = 'YXZ';
  return c;
}

test('spear cam trail sits above and beside the flight, on the side away from the cliff', () => {
  for (const startX of [0, -7.5]) {
    const c = fxRig();
    const fx = createFx(c);
    c.camera.position.set(startX, 1.6, 0);
    const pr = { pos: new THREE.Vector3(startX + 0.3, 1.7, -0.5), vel: new THREE.Vector3(0, 4, -20), byPlayer: true, src: 'motion', age: 0, alive: true, stuck: false, tip: 1.25, hits: [] };
    c.events.emit('spearThrown', { projectile: pr });
    assert.equal(fx.spearCamPhase, 'trail');
    const side = new THREE.Vector3();
    for (let i = 0; i < 40; i++) {
      const dt = 1 / 60;
      c.time.now += dt;
      const gdt = dt * c.time.scale;
      pr.age += gdt;
      pr.vel.y -= 9.81 * gdt;
      pr.pos.addScaledVector(pr.vel, gdt);
      fx.update(dt, gdt);
      c.camera.position.set(startX, 1.6, 0);
      c.camera.quaternion.identity();
      fx.applyCamera(c.camera);
    }
    assert.equal(fx.spearCamPhase, 'trail');
    const rel = c.camera.position.clone().sub(pr.pos);
    const dir = pr.vel.clone().normalize();
    side.crossVectors(dir, new THREE.Vector3(0, 1, 0)).setY(0).normalize();
    assert.ok(rel.dot(side) > 0.35, `side offset ${rel.dot(side).toFixed(2)}`);
    assert.ok(side.x > 0, 'the offset points away from the cliff (+x)');
    assert.ok(rel.y > 0.45, `up offset ${rel.y.toFixed(2)}`);
    assert.ok(c.camera.position.x >= -8.3 - 1e-6, 'never inside the cliff');
  }
});

test('a new blood pool feeds a live pool nearby instead of stacking', () => {
  const c = fxRig();
  const d = createDecals(c);
  const a = d.addXYZ(0, 0, 0, 'pool', 1.2);
  d.update(10, 10);
  const size0 = d.mesh.geometry.getAttribute('aShape').getX(a);
  const b = d.addXYZ(0.2, 0, 0.15, 'pool', 1.2);
  assert.equal(b, a, 'merged into the same slot');
  assert.equal(d.count, 1);
  assert.ok(d.mesh.geometry.getAttribute('aShape').getX(a) > size0 * 1.1, 'the pool spread');
  // Far enough away it is a pool of its own.
  assert.notEqual(d.addXYZ(1.5, 0, 0, 'pool', 1.2), a);
  assert.equal(d.count, 2);
});

test('stains in one spot stack at most MAX_OVERLAP deep (the oldest fade out)', () => {
  const c = fxRig();
  const d = createDecals(c);
  for (let i = 0; i < 9; i++) {
    d.addXYZ(0.05 * i, 0, 0, 'blood', 0.6);
    d.update(0.2, 0.2);
  }
  for (let t = 0; t < 30; t++) d.update(0.2, 0.2); // past the 4 s fade
  assert.equal(d.count, MAX_OVERLAP);
});

test('dust right at the camera emits half the puffs', () => {
  const c = fxRig();
  c.camera.position.set(0, 1.6, 0);
  const p = createParticles(c);
  const counts = [0, 0, 0];
  p.dust({ x: 0.5, y: 0, z: -0.5 }, 1);
  const nearSoft = p.counts(counts)[2];
  p.reset();
  p.dust({ x: 0, y: 0, z: -4 * DUST_NEAR }, 1);
  const farSoft = p.counts(counts)[2];
  assert.ok(nearSoft <= Math.ceil(farSoft / 2), `near ${nearSoft} vs far ${farSoft}`);
  assert.ok(nearSoft >= 1);
});
