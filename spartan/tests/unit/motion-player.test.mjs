// game/player.js in motion mode, after real-phone feedback ("the swing animation must go when I
// swing the phone", "the view turned on its own"): every detected motion swing starts its attack
// within the frame, a motion slash strikes sooner and says when (strikeIn), and nothing but the
// player's body turns the camera. Runs the real player + combat on the headless gameplay harness.
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

const { EventBus } = await import('../../src/main/assets/game/js/core/events.js');
const { createRng } = await import('../../src/main/assets/game/js/core/math.js');
const { TIMING, WEAPONS } = await import('../../src/main/assets/game/js/game/rules.js');
const { createHarness, createHeadlessCtx } = await import('../sandbox/gameplay-harness.js');

const ctx = createHeadlessCtx({ EventBus, createRng });
const h = createHarness(ctx);
ctx.loop(() => h.tick());

const PI = Math.PI;
const DT = 1 / 60;
const front = (a, d) => [-Math.sin(a) * d, -Math.cos(a) * d];
const spawnAt = (kind, a, d, o = {}) => {
  const [x, z] = front(a, d);
  return h.spawn(kind, x, z, { blockChance: 0, hp: 1e6, ...o });
};
const P = () => h.player;
const motionReset = () => {
  h.reset();
  h.ctx.input.motion = true;
  h.ctx.settings.control = 'motion';
};
const motion = (a) => h.action({ src: 'motion', power: 0.5, ...a });

test('a motion slash strikes after half the usual delay and says when (strikeIn)', () => {
  motionReset();
  spawnAt('levy', 0, 1.6);
  motion({ type: 'slash', dir: 0 });
  h.step(1);
  const sw = h.events('swing');
  assert.equal(sw.length, 1);
  assert.ok(Math.abs(sw[0].payload.strikeIn - TIMING.slash.strike * 0.5) < 1e-9, `strikeIn ${sw[0].payload.strikeIn}`);
  const t0 = sw[0].t;
  h.stepUntil(() => h.events('hit').length > 0, 30);
  const dt = h.events('hit')[0].t - t0;
  assert.ok(dt <= TIMING.slash.strike * 0.5 + DT + 1e-9, `struck ${dt.toFixed(3)} s after the swing`);
  // touch and keys keep the full delay, and report it
  h.reset();
  h.action({ type: 'slash', dir: 0, src: 'touch' });
  h.step(1);
  assert.equal(h.events('swing')[0].payload.strikeIn, TIMING.slash.strike);
  h.reset();
  h.action({ type: 'thrust', src: 'touch' });
  h.step(1);
  assert.equal(h.events('swing')[0].payload.strikeIn, TIMING.thrust.strike);
});

test('every motion swing starts its attack within the frame, even mid-attack', () => {
  // a new slash right after the last one's strike (well before cancelAfter) starts at once
  motionReset();
  const lv = spawnAt('levy', 0, 1.6);
  motion({ type: 'slash', dir: 0 });
  h.step(4); // 67 ms: struck (50 ms), not yet cancellable (160 ms)
  assert.equal(h.events('hit').length, 1);
  const f = h.frame;
  motion({ type: 'slash', dir: PI });
  h.step(1);
  const sw = h.events('swing');
  assert.equal(sw.length, 2, 'started');
  assert.equal(sw[1].frame, f + 1, 'in the very next frame');
  assert.equal(sw[1].payload.dir, PI);
  // a third one before the second has struck: the second's strike lands first, then the third starts
  motion({ type: 'slash', dir: 0 });
  h.step(1);
  assert.equal(h.events('swing').length, 3);
  assert.equal(h.events('hit').length, 2, 'the cut-short slash still struck');
  h.stepTime(0.3);
  assert.equal(h.events('hit').length, 3);
  assert.ok(lv.hitsTaken.length === 3);
  // a motion thrust cuts a slash short too
  motion({ type: 'thrust' });
  h.step(1);
  assert.equal(h.events('swing').pop().payload.kind, 'thrust');
  // touch input still waits for cancelAfter (the buffer)
  h.reset();
  h.action({ type: 'slash', dir: 0, src: 'touch' });
  h.step(4);
  h.action({ type: 'slash', dir: PI, src: 'touch' });
  h.step(1);
  assert.equal(h.events('swing').length, 1, 'touch: buffered');
  h.stepUntil(() => h.events('swing').length === 2, 30);
  assert.ok(h.events('swing')[1].t - h.events('swing')[0].t >= TIMING.slash.cancelAfter - 1e-6);
});

test('a motion swing waits only while staggered, drawing or throwing', () => {
  motionReset();
  P().guardBreak(null);
  assert.equal(P().state, 'stagger');
  motion({ type: 'slash', dir: 0 });
  h.step(1);
  assert.equal(h.events('swing').length, 0, 'staggered: buffered');
  h.stepUntil(() => h.events('swing').length === 1, 60);
  assert.equal(P().state, 'attack', 'started once the stagger ended');
  // drawing the spear
  motionReset();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.step(1);
  assert.equal(P().state, 'draw');
  motion({ type: 'thrust' });
  h.step(1);
  assert.equal(h.events('swing').length, 0, 'drawing: buffered');
});

test('motion mode never turns the camera by itself: no auto-face, steady pitch, aim turns the attack', () => {
  motionReset();
  const lv = spawnAt('levy', PI * 0.8, 1.6, { state: 'windup' });
  h.ctx.events.emit('enemyWindup', { actor: lv, timeToStrike: 2.5, targetIsPlayer: true });
  h.stepTime(2);
  assert.equal(P().yaw, 0, 'no auto-face');
  const pitch = P().pitch;
  assert.ok(pitch < 0 && pitch > -0.12, `steady, slightly lowered pitch ${pitch}`);
  spawnAt('levy', 0, 1.2);
  h.stepTime(0.5);
  assert.equal(P().pitch, pitch, 'no easing toward a target');
  // the attack (not the camera) turns up to ±30° toward a foe off to the side
  motionReset();
  const side = spawnAt('levy', 0.5, 1.8);
  h.step(1);
  motion({ type: 'slash', dir: 0 });
  h.stepTime(0.1);
  assert.equal(P().yaw, 0);
  assert.ok(Math.abs(P().attackYaw - 0.5) < 1e-6, `attack yaw ${P().attackYaw}`);
  assert.equal(h.events('hit').pop().payload.target, side);
});

// ---- Review follow-ups -----------------------------------------------------------------------

test('a motion slash at a foe just out of reach lunges at the normal speed and still lands', () => {
  // 3.0 m: out of sword reach (2.1 m, foe radius 0.32), within the lunge. The motion slash strikes
  // at half the delay, before its lunge has finished: the blow reaches from where the lunge ends.
  motionReset();
  const foe = spawnAt('levy', 0, 3.0);
  h.stepTime(0.1);
  const x0 = P().pos.x, z0 = P().pos.z;
  let last = [x0, z0], maxStep = 0;
  motion({ type: 'slash', dir: 0 });
  for (let i = 0; i < 30; i++) {
    h.step(1);
    maxStep = Math.max(maxStep, Math.hypot(P().pos.x - last[0], P().pos.z - last[1]));
    last = [P().pos.x, P().pos.z];
  }
  assert.equal(foe.hitsTaken.length, 1, 'the slash landed');
  assert.ok(Math.hypot(P().pos.x - x0, P().pos.z - z0) > 0.3, 'the player lunged');
  assert.ok(maxStep <= (0.8 * DT) / TIMING.slash.strike + 1e-6, `lunge step ${maxStep.toFixed(3)} m per frame`);
  assert.ok(WEAPONS.sword.reach < 3.0 - 0.32, 'the foe starts out of reach');
});

test('two motion slashes in one frame both land on a foe in lunge range (the cut-short attack lunges first)', () => {
  for (const dist of [1.6, 2.6]) {
    motionReset();
    const foe = spawnAt('levy', 0, dist);
    h.stepTime(0.1);
    motion({ type: 'slash', dir: PI / 2 });
    motion({ type: 'slash', dir: -PI / 2 });
    h.stepTime(0.5);
    assert.equal(foe.hitsTaken.length, 2, `${dist} m: ${foe.hitsTaken.length} hits`);
    assert.deepEqual(h.events('combo').map((e) => e.payload.id), ['cleave'], `${dist} m`);
  }
});
