// Spartan rage (game/rage.js) and the buttons layout's tap to lock (game/lockon.js pickOnScreen).
import test from 'node:test';
import assert from 'node:assert/strict';
// three comes straight from the vendored build (no import map in Node).
const THREE = await import(new URL('../../src/main/assets/game/lib/three/three.module.js', import.meta.url).href);
import { RAGE, gainRage, canUnleash } from '../../src/main/assets/game/js/game/rage.js';
import { pickOnScreen, TAP } from '../../src/main/assets/game/js/game/lockon.js';

test('the rage meter fills from the fight, clamped, and unleashes only when full', () => {
  assert.equal(gainRage(0, 'hit'), RAGE.gain.hit);
  assert.equal(gainRage(0.95, 'kill'), 1);
  assert.equal(gainRage(0.5, 'nothing'), 0.5);
  assert.equal(gainRage(NaN, 'perfect'), RAGE.gain.perfect);
  assert.equal(canUnleash(1, false), true);
  assert.equal(canUnleash(0.99, false), false);
  assert.equal(canUnleash(1, true), false);
  // About a wave's work: ten kills and ten hits fill it.
  let v = 0;
  for (let i = 0; i < 10; i++) v = gainRage(gainRage(v, 'kill'), 'hit');
  assert.equal(v, 1);
  assert.ok(RAGE.damageMul > 1 && RAGE.takenMul < 1 && RAGE.duration > 0 && Object.isFrozen(RAGE.gain));
});

test('pickOnScreen: a tap on a foe picks him, the nearest of two, never one behind or out of range', () => {
  const W = 800, H = 360;
  const cam = new THREE.PerspectiveCamera(60, W / H, 0.1, 500);
  cam.position.set(0, 1.6, 0);
  cam.updateMatrixWorld();
  cam.matrixWorldInverse.copy(cam.matrixWorld).invert();
  const foe = (x, z, extra = {}) => ({ alive: true, team: 'persian', state: 'idle', pos: new THREE.Vector3(x, 0, z), height: 1.75, ...extra });
  const ahead = foe(0, -6);
  const right = foe(2.5, -6);
  const behind = foe(0, 6);
  const far = foe(0, -60);
  const ally = foe(-2.5, -6, { team: 'greek' });
  const actors = [ahead, right, behind, far, ally];
  const screen = (a, dy = 0.55) => {
    const v = new THREE.Vector3(a.pos.x, dy * a.height, a.pos.z).project(cam);
    return [(v.x + 1) / 2 * W, (1 - v.y) / 2 * H];
  };
  const from = { x: 0, z: 0 };
  assert.equal(pickOnScreen(actors, cam, ...screen(ahead), W, H, from), ahead);
  assert.equal(pickOnScreen(actors, cam, ...screen(right), W, H, from), right);
  assert.equal(pickOnScreen(actors, cam, ...screen(right, 0.95), W, H, from), right, 'his head counts');
  assert.equal(pickOnScreen(actors, cam, ...screen(ally), W, H, from), null, 'an ally is never picked');
  assert.equal(pickOnScreen(actors, cam, 20, 20, W, H, from), null, 'empty sky');
  // The far foe sits on the same screen line as `ahead`: out of range, he is skipped.
  assert.ok(Math.hypot(far.pos.x, far.pos.z) > TAP.range);
  const [fx, fy] = screen(far);
  assert.notEqual(pickOnScreen([far], cam, fx, fy, W, H, from), far);
  assert.equal(pickOnScreen([behind], cam, W / 2, H / 2, W, H, from), null, 'behind the eye');
  assert.equal(pickOnScreen(null, cam, 0, 0, W, H, from), null);
});

test('the rage grows with the campaign: one tier a day, the last stand strongest', async () => {
  const { RAGE_TIERS, rageTier } = await import('../../src/main/assets/game/js/game/rage.js');
  assert.deepEqual([rageTier(1, null), rageTier(2, null), rageTier(3, 'A'), rageTier(3, 'B'), rageTier(3, 'C'), rageTier(NaN, null)].map((t) => t.tier), [1, 2, 3, 3, 4, 1]);
  for (let i = 1; i < RAGE_TIERS.length; i++) {
    const a = RAGE_TIERS[i - 1], b = RAGE_TIERS[i];
    assert.ok(b.duration >= a.duration && b.damageMul >= a.damageMul && b.takenMul <= a.takenMul && b.healPerKill >= a.healPerKill
      && b.gainMul >= a.gainMul && b.killExtend >= a.killExtend, `tier ${b.tier} is no weaker than ${a.tier}`);
    assert.ok(b.perk.length > 0, `tier ${b.tier} announces a perk`);
  }
  const last = RAGE_TIERS[3];
  assert.ok(last.arrowProof && last.shockwave && !RAGE_TIERS[2].arrowProof && !RAGE_TIERS[2].shockwave);
  assert.equal(gainRage(0, 'kill', 1.5), RAGE.gain.kill * 1.5);
});
