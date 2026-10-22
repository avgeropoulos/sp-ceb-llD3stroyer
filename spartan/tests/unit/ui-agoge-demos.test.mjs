// The Agoge's Demo button injects synthesize(step.demo()). Each demo must make the real motion
// pipeline (MotionFrames → GestureRecognizer) produce exactly the move the step teaches.
import test from 'node:test';
import assert from 'node:assert/strict';
import { STEPS } from '../../src/main/assets/game/js/ui/training.js';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer } from '../../src/main/assets/game/js/motion/gestures.js';
import { dirToken } from '../../src/main/assets/game/js/game/rules.js';

const EXPECT = {
  calibrate: 'R,L,R',
  slashLR: 'R,R,L,L',
  overhead: 'windup,D,windup,D',
  uppercut: 'U',
  thrust: 'thrust,thrust,thrust',
  bash: 'thrust,thrust',
  spear: 'thrust,thrust,windup,throw',
  twin: 'R,L',
};

for (const rot of [90, 270]) {
  for (const hand of ['right', 'left']) {
    test(`Agoge demos give the taught gestures (rot ${rot}, ${hand} hand)`, () => {
      for (const s of STEPS) {
        if (!s.demo) continue;
        const frames = new MotionFrames({ platformKind: 'android' });
        const g = new GestureRecognizer({ sensitivity: 1, weapon: s.id === 'spear' ? 'spear' : 'sword', gyroRange: 34.9 });
        const seen = [];
        for (const raw of synthesize(s.demo(), { rot, hand, seed: 3 })) {
          for (const e of g.push(frames.process(raw))) seen.push(e.type === 'slash' ? dirToken(e.dir) : e.type);
        }
        assert.equal(seen.join(','), EXPECT[s.id], `${s.id}`);
      }
    });
  }
}
