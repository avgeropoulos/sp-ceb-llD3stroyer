// The hilt grip's Agoge (docs-hilt-grip.md §G, §H.1 #10). Its Demo button injects
// synthesize(step.demoHilt(), { hold: 'hilt' }): each demo must make the hilt pipeline
// (MotionFrames → GestureRecognizer, both with grip 'hilt') produce exactly the moves the step
// teaches, for either hand. The runtime step list adds the lock step in the hilt grip only.
import test from 'node:test';
import assert from 'node:assert/strict';
import { STEPS, HILT_LOCK_STEP, agogeSteps } from '../../src/main/assets/game/js/ui/training.js';
import { synthesize, scriptDuration } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer } from '../../src/main/assets/game/js/motion/gestures.js';
import { dirToken } from '../../src/main/assets/game/js/game/rules.js';

const EXPECT = {
  calibrate: 'R,L,R',
  slashLR: 'R,R,L,L',
  overhead: 'D,D', // a chop straight from the guard: no cock-back, so no wind-up
  uppercut: 'U',
  thrust: 'thrust,thrust,thrust',
  bash: 'thrust,thrust',
  spear: 'thrust,thrust,windup,throw',
  twin: 'R,L',
};

/** The moves a hilt demo gives (the over-speed notice is not a move). */
function movesOf(s, { rot, hand, platformKind = 'android', seed = 3 }) {
  const frames = new MotionFrames({ platformKind, grip: 'hilt' });
  const g = new GestureRecognizer({ sensitivity: 1, weapon: s.id === 'spear' ? 'spear' : 'sword', gyroRange: 34.9, grip: 'hilt' });
  const seen = [];
  for (const raw of synthesize(s.demoHilt(), { hold: 'hilt', rot, hand, seed })) {
    for (const e of g.push(frames.process(raw))) {
      if (e.type !== 'overSpeed') seen.push(e.type === 'slash' ? dirToken(e.dir) : e.type);
    }
  }
  return seen.join(',');
}

for (const rot of [0, 90]) {
  for (const hand of ['right', 'left']) {
    test(`hilt Agoge demos give the taught gestures (rot ${rot}, ${hand} hand)`, () => {
      for (const s of agogeSteps('hilt')) {
        if (!s.demoHilt) continue;
        assert.equal(movesOf(s, { rot, hand }), EXPECT[s.id], s.id);
      }
    });
  }
}

test('hilt Agoge demos also hold on a web phone (gravity sign learned) and with other seeds', () => {
  for (const s of agogeSteps('hilt')) {
    if (!s.demoHilt) continue;
    assert.equal(movesOf(s, { rot: 0, hand: 'right', platformKind: 'web', seed: 7 }), EXPECT[s.id], `${s.id} web`);
    assert.equal(movesOf(s, { rot: 0, hand: 'left', seed: 17 }), EXPECT[s.id], `${s.id} seed 17`);
  }
});

test('every step that moves the phone has a hilt demo, and every step a hilt instruction', () => {
  const withDemo = STEPS.filter((s) => s.demoHilt).map((s) => s.id);
  assert.deepEqual(withDemo, Object.keys(EXPECT));
  assert.deepEqual(withDemo, STEPS.filter((s) => s.demo).map((s) => s.id));
  for (const s of agogeSteps('hilt')) {
    assert.ok(typeof s.hilt === 'string' && s.hilt.length > 20, `${s.id} hilt text`);
    if (!s.demoHilt) continue;
    const dur = scriptDuration(s.demoHilt(), { hold: 'hilt' });
    assert.ok(dur > 0.5 && dur < 7.5, `${s.id} hilt demo lasts ${dur}`);
  }
  // Safety wording (§I.3 risk 1) and the lead's decisions: a firm flick, never a hard throw; the
  // spear comes from the top button only; the strap is strongly recommended.
  for (const s of agogeSteps('hilt')) assert.doesNotMatch(s.hilt, /\bhard\b/i, s.id);
  const spear = STEPS.find((s) => s.id === 'spear');
  assert.match(spear.hilt, /weapon button at the top/);
  assert.doesNotMatch(spear.hilt, /flick your thumb/i);
  assert.match(STEPS[0].hilt, /strongly recommended/);
});

test('the runtime step list: 13 steps in the hilt grip (lock at index 6), STEPS itself otherwise', () => {
  const hilt = agogeSteps('hilt');
  assert.equal(hilt.length, 13);
  assert.equal(hilt[6], HILT_LOCK_STEP);
  assert.deepEqual(hilt.map((s) => s.id), ['grip', 'calibrate', 'slashLR', 'overhead', 'uppercut', 'thrust', 'lock', 'block', 'perfect', 'bash', 'volley', 'spear', 'twin']);
  // the other steps are the very same objects, in STEPS' order
  assert.deepEqual(hilt.filter((s) => s !== HILT_LOCK_STEP), STEPS);
  assert.ok(Object.isFrozen(hilt));
  for (const g of ['window', null, undefined, 'touch']) assert.equal(agogeSteps(g), STEPS, String(g));
  assert.equal(STEPS.length, 12);
  assert.ok(!STEPS.includes(HILT_LOCK_STEP));
});

test('HILT_LOCK_STEP: id lock, n 2, hilt only, no demo (the big slot animates a thumb instead)', () => {
  assert.equal(HILT_LOCK_STEP.id, 'lock');
  assert.equal(HILT_LOCK_STEP.n, 2);
  assert.equal(HILT_LOCK_STEP.hiltOnly, true);
  assert.equal(HILT_LOCK_STEP.demo, null);
  assert.equal(HILT_LOCK_STEP.demoHilt, null);
  assert.ok(HILT_LOCK_STEP.title && HILT_LOCK_STEP.voice);
  assert.match(HILT_LOCK_STEP.hilt, /right/);
  assert.match(HILT_LOCK_STEP.hilt, /left/);
  assert.ok(Object.isFrozen(HILT_LOCK_STEP));
});
