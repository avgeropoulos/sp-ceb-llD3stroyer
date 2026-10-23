// Regression checks for the characters fixes from review C, run headless (no canvas needed):
//   shadow casters capped to the nearest 12 (6 on low); settled corpses frozen and thawed before
//   they sink; a dying body nudged out of the pile; the per-body blood seed texel; the guard
//   poses (ally dory low across the view, no 'scarecrow' arm, the thrust still chambers).
// node --test spartan/tests/unit/characters-fix.test.mjs
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
const GAME = '../../src/main/assets/game/js/';
const { EventBus } = await import(GAME + 'core/events.js');
const { createRng } = await import(GAME + 'core/math.js');
const { createAI } = await import(GAME + 'game/ai.js');
const { CLIPS } = await import(GAME + 'gfx/characters/poses.js');
const { BLOOD_SEED_TEXEL } = await import(GAME + 'gfx/characters/atlas.js');
const { BONES, B, createBones } = await import(GAME + 'gfx/characters/rig.js');
const { Animator } = await import(GAME + 'gfx/characters/anim.js');
const { buildModel } = await import(GAME + 'gfx/characters/models.js');
const { variantFor, KINDS } = await import(GAME + 'gfx/characters/kinds.js');
const { atlasRects } = await import(GAME + 'gfx/characters/atlas.js');

const DT = 1 / 30;

/** A visual with the §11 anim API: clips end (onEnd) after their duration; freezes are logged. */
function stubVisual(kind, log) {
  const root = new THREE.Group();
  const mesh = new THREE.Object3D();
  mesh.castShadow = false;
  root.add(mesh);
  let clip = null, t = 0, dur = 1, onEnd = null, frozen = false;
  const v = {
    root, mesh, kind, frozenLog: [],
    sockets: { head: root, chest: root, rightHand: root, leftHand: root, back: root },
    anim: {
      setLocomotion() {}, setGuard() {}, flinch() {},
      play(name, o = {}) { clip = name; t = 0; dur = o.duration || (CLIPS[name] && CLIPS[name].duration) || 1; onEnd = o.onEnd || null; },
      update(dt) {
        if (frozen) log.push(`update while frozen: ${kind}`);
        if (!clip) return;
        t += dt;
        if (t >= dur && onEnd) { const f = onEnd; onEnd = null; f(); }
      },
      get clip() { return clip; }, get playing() { return t < dur; }, get guard() { return false; },
    },
    attach(o) { root.attach(o); },
    setBloodied() {},
    setVisible(on) { root.visible = on; },
    setFrozen(on) { frozen = !!on; v.frozenLog.push([frozen, root.position.y]); },
    get frozen() { return frozen; },
    dispose() { root.removeFromParent(); },
  };
  return v;
}

function world({ quality = 'medium' } = {}) {
  const log = [];
  const ctx = {
    events: new EventBus(), rng: createRng(5), scene: new THREE.Scene(), actors: [], mode: 'battle', quality,
    time: { gnow: 0 }, settings: { control: 'touch' }, waves: { day: 1, dayWave: 0 },
    input: { shieldHeld: false, shieldPressedAt: -10 },
    // a dead player: nobody engages, so the scene stays where it was put
    player: { pos: new THREE.Vector3(0, 0, 0), yaw: 0, alive: false, hp: 0, maxHp: 100, shieldRaise: 0 },
    camera: new THREE.PerspectiveCamera(),
    combat: { actorStrike: () => 'block' },
    characters: { KINDS, create: (kind) => stubVisual(kind, log) },
  };
  ctx.camera.position.set(0, 1.6, 0);
  const ai = createAI(ctx);
  ctx.ai = ai;
  const step = (s) => {
    for (let i = 0, n = Math.round(s / DT); i < n; i++) {
      ctx.time.gnow += DT;
      ai.update(DT);
    }
  };
  const kill = (a, dir = { x: 0, z: -1 }) => a.takeHit({ damage: 9999, dir, kind: 'melee' });
  return { ctx, ai, step, kill, log };
}

test('only the nearest 12 actors within 20 m cast shadows (6 on low); the far and the dead never do', () => {
  for (const [quality, cap] of [['medium', 12], ['high', 12], ['low', 6]]) {
    const w = world({ quality });
    const near = [];
    for (let i = 0; i < 16; i++) near.push(w.ai.spawnEnemy('levy', -6 + (i % 8) * 1.6, -3 - Math.floor(i / 8) * 4));
    const far = w.ai.spawnEnemy('levy', 0, -26);
    w.step(DT);
    const casters = w.ctx.actors.filter((a) => a.visual.mesh.castShadow);
    assert.equal(casters.length, cap, `${quality}: ${casters.length} casters`);
    assert.ok(!far.visual.mesh.castShadow, `${quality}: an actor 26 m away casts`);
    const d2 = (a) => a.pos.x ** 2 + a.pos.z ** 2;
    const worst = Math.max(...casters.map(d2));
    for (const a of near) if (!a.visual.mesh.castShadow) assert.ok(d2(a) >= worst - 1e-9, `${quality}: a nearer actor left out`);
    w.kill(casters[0]);
    w.step(DT);
    assert.ok(!casters[0].visual.mesh.castShadow, `${quality}: a corpse casts`);
  }
});

test('a settled corpse is frozen (no animator updates) and thawed before it starts to sink', () => {
  const w = world();
  const a = w.ai.spawnEnemy('levy', 0.5, -3);
  w.step(0.2);
  w.kill(a);
  w.step(0.2);
  assert.ok(w.ai.corpses.includes(a) && !a.frozen, 'frozen while still falling');
  w.step(3);
  assert.ok(a.animDone && a.frozen && a.visual.frozen, 'not frozen once the death clip ended');
  const y0 = a.visual.root.position.y;
  w.step(1);
  assert.equal(a.visual.root.position.y, y0);
  // the corpse cap makes the oldest body sink: it must be thawed first, then go down
  for (let i = 0; i < 21; i++) w.kill(w.ai.spawnEnemy('levy', -8 + i * 0.75, -9));
  w.step(1.5);
  assert.ok(a.sinkT >= 0, 'the oldest corpse should sink');
  assert.ok(!a.frozen && !a.visual.frozen, 'sinking while frozen');
  const thaw = a.visual.frozenLog.findIndex(([f]) => !f);
  assert.ok(thaw > 0 && a.visual.frozenLog[thaw][1] === y0, 'the root moved before the corpse was thawed');
  assert.ok(a.visual.root.position.y < y0, 'did not sink');
  assert.deepEqual(w.log, []);
});

test('a body falling where another lies is nudged out of it (≤ 0.4 m, during its fall)', () => {
  const w = world();
  const first = w.ai.spawnEnemy('levy', 0, -3);
  w.step(0.1);
  w.kill(first);
  w.step(3);
  const x0 = first.pos.x, z0 = first.pos.z;
  const second = w.ai.spawnEnemy('levy', x0 + 0.1, z0 + 0.05);
  w.step(DT);
  const sx = second.pos.x, sz = second.pos.z;
  w.kill(second);
  assert.equal(second.deathClip.startsWith('die_'), true);
  const n = Math.hypot(second.nudgeX, second.nudgeZ);
  assert.ok(n > 0.1 && n <= 0.4 + 1e-9, `nudge ${n.toFixed(3)} m`);
  w.step(0.05);
  assert.ok(Math.hypot(second.pos.x - sx, second.pos.z - sz) < 0.05, 'the nudge must not jump at the blow');
  w.step(3);
  const moved = Math.hypot(second.pos.x - sx, second.pos.z - sz);
  assert.ok(moved > 0.1 && moved <= 0.42, `moved ${moved.toFixed(3)} m`);
  assert.ok(Math.hypot(second.pos.x - x0, second.pos.z - z0) > Math.hypot(sx - x0, sz - z0), 'not away from the other body');
  assert.equal(first.pos.x, x0, 'the body already down must not move');
  // a body falling alone is not nudged
  const lone = w.ai.spawnEnemy('levy', 6, -12);
  w.step(DT);
  w.kill(lone);
  assert.equal(lone.nudgeX, 0);
  assert.equal(lone.nudgeZ, 0);
});

test('the blood-seed texel lies past the bone matrices and bone updates never write it', () => {
  const bones = [];
  for (let i = 0; i < BONES.length; i++) {
    const b = new THREE.Bone();
    if (i > 0) bones[BONES[i][1]].add(b);
    bones.push(b);
  }
  const sk = new THREE.Skeleton(bones);
  sk.computeBoneTexture();
  assert.equal(BLOOD_SEED_TEXEL, BONES.length * 4);
  const size = sk.boneTexture.image.width;
  assert.ok(BLOOD_SEED_TEXEL < size * size, `no spare texel in a ${size}² bone texture`);
  sk.boneMatrices[BLOOD_SEED_TEXEL * 4] = 0.625;
  bones[3].position.set(1, 2, 3);
  bones[0].updateMatrixWorld(true);
  sk.update();
  assert.equal(sk.boneMatrices[BLOOD_SEED_TEXEL * 4], 0.625);
});

/** Bone world positions of a kind held in its guard for a moment (unit tests, no mesh). */
function guardPose(kind, clip, t) {
  const rects = atlasRects('low');
  const { spec } = variantFor(kind, {}, 3);
  const model = buildModel(spec, { ...rects.cloth, ...rects.metal }, 'low');
  const { bones } = createBones(model.rest);
  const mesh = new THREE.Object3D();
  mesh.add(bones[0]);
  const root = new THREE.Group();
  root.add(mesh);
  const an = new Animator({ bones, rest: model.rest, style: KINDS[kind].style, mesh, root, seed: 3, right: 'spear', left: kind === 'spartan' ? 'aspis' : 'grip' });
  an.setGuard(true);
  for (let i = 0; i < 60; i++) an.update(1 / 60);
  if (clip) {
    an.play(clip, { duration: CLIPS[clip].duration });
    for (let i = 0, n = Math.round(t * CLIPS[clip].duration * 60); i < n; i++) an.update(1 / 60);
  }
  root.updateMatrixWorld(true);
  const w = (i) => bones[i].getWorldPosition(new THREE.Vector3());
  const spear = new THREE.Vector3(0, 0, 1).applyQuaternion(bones[B.weaponR].getWorldQuaternion(new THREE.Quaternion()));
  return { shoulder: w(B.upperArmR), elbow: w(B.forearmR), hand: w(B.handR), spear };
}

test('guard poses: the allies’ dory points down across the view; no level "scarecrow" arm; the thrust still chambers', () => {
  const hop = guardPose('spartan');
  const down = Math.asin(-hop.spear.y) * 180 / Math.PI;
  assert.ok(down > 15 && down < 35, `hoplite dory ${down.toFixed(1)}° below level`);
  for (const kind of ['levy', 'sparabara', 'immortal']) {
    const g = guardPose(kind);
    assert.ok(g.shoulder.y - g.elbow.y > 0.12, `${kind}: elbow ${(g.shoulder.y - g.elbow.y).toFixed(3)} m under the shoulder`);
    assert.ok(g.hand.y > g.elbow.y, `${kind}: the fist must stay above the elbow (overhand)`);
    // the thrust's 'back' key (t ≈ 0.36–0.5): the fist drawn back behind the shoulder, up high
    const b = guardPose(kind, 'thrust', 0.47);
    assert.ok(b.hand.z < b.shoulder.z - 0.12, `${kind}: thrust not chambered (fist ${(b.hand.z - b.shoulder.z).toFixed(3)} m ahead)`);
    assert.ok(b.hand.y > b.shoulder.y, `${kind}: chambered fist below the shoulder`);
  }
});
