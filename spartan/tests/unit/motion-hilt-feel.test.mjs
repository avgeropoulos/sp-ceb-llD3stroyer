// Hilt grip feel (docs-hilt-grip.md B, H.1 #2), as motion-feel.test.mjs does for the window grip:
// cautious swings, forearm sweeps, latency, no false slashes, mirrored setups and calibration from
// swing candidates. All through the synthetic phone in the hilt hold (§B.4) → MotionFrames →
// GestureRecognizer, both in the hilt grip.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer, GRIPS } from '../../src/main/assets/game/js/motion/gestures.js';
import { suggestSensitivity, pickStrokes, CALIBRATION } from '../../src/main/assets/game/js/ui/training.js';

const PI = Math.PI;
const TOKENS = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
const token = (dir) => TOKENS[((Math.round(dir / (PI / 4)) % 8) + 8) % 8];
const GUARD_DIRS = [['R', 0], ['DR', -PI / 4], ['D', -PI / 2], ['DL', (-3 * PI) / 4], ['L', PI]];
const LOW_DIRS = [['U', PI / 2], ['UR', PI / 4], ['UL', (3 * PI) / 4]];
// web −1: the provisional sign flip takes 1 s of calm, then the guard is learned from a held guard
const LEAD = 1.2;
const LEAD_FLIP = 1.0 + GRIPS.hilt.guardHold + 0.2;
const idle = (duration) => ({ kind: 'idle', duration });
const swing = (dir, more = {}) => ({ kind: 'swing', dir, ...more });
const lower = (beta = 0.1, more = {}) => ({ kind: 'lower', beta, ...more });

/**
 * Grips (the phone in the fist) and holds: the blade leaning forward 0.2–0.8 rad from vertical,
 * rolled ±0.5 about itself (the screen facing partly sideways), the forearm aimed ±30° off the
 * body's forward, and the phone tipped in the hand (grip pitch ±0.35, also with a forward lean).
 */
const GRIPS_HELD = [
  ['lean 0.45', {}],
  ['lean 0.2', { lean: 0.2 }],
  ['lean 0.8', { lean: 0.8 }],
  ['rolled +0.5', { roll: 0.5 }],
  ['rolled -0.5', { roll: -0.5 }],
  ['aimed +30°', { aim: 0.52 }],
  ['aimed -30°', { aim: -0.52 }],
  ['tipped forward in the hand 0.35', { grip: { pitch: 0.35 } }],
  ['tipped back in the hand 0.35', { grip: { pitch: -0.35 } }],
  ['lean 0.8, tipped back 0.35', { lean: 0.8, grip: { pitch: -0.35 } }],
];
const SOURCES = [
  ['android', 'android', {}],
  ['web -1', 'web', { includeGravity: false, gravitySign: -1 }],
];

/** Runs a script (after an idle lead-in) in the hilt grip; look must stay 0. */
function run(script, so, { kind = 'android', sensitivity = 1, weapon = 'sword', flip = null } = {}) {
  const lead = so.gravitySign === -1 ? LEAD_FLIP : LEAD;
  const raw = synthesize([idle(lead), ...script], { ...so, hold: 'hilt' });
  const frames = new MotionFrames({ platformKind: kind, grip: 'hilt' });
  const g = new GestureRecognizer({ sensitivity, weapon, grip: 'hilt' });
  if (flip) g.setFlip(flip[0], flip[1]);
  const events = [];
  const candidates = [];
  for (const r of raw) {
    const s = frames.process(r);
    for (const e of g.push(s)) events.push({ ...e });
    for (const c of g.consumeCandidates()) candidates.push({ ...c });
    if (!Object.is(g.consumeLook(1 / 60).yaw, 0)) assert.fail('look is off in the hilt grip');
  }
  return { events, candidates, g, lead };
}
const slashes = (events) => events.filter((e) => e.type === 'slash');
const describe = (events) => events.filter((e) => e.type !== 'overSpeed').map((e) => (e.type === 'slash' ? token(e.dir) : e.type)).join(' ') || '(none)';

function check(fails, label, msg) {
  if (msg) fails.push(`${label}: ${msg}`);
}
function assertNoFails(fails, total) {
  assert.equal(fails.length, 0, `${fails.length}${total ? '/' + total : ''} failed:\n  ${fails.slice(0, 25).join('\n  ')}`);
}

// ---- 1. detection sensitivity ------------------------------------------------------------------

test('cautious swings register at the default sensitivity (peak 3.5–4 rad/s, arc 0.8–1.0, any grip)', () => {
  // small cock-back, wrist roll, slow sloppy return: what a player afraid of dropping the phone does.
  // From the guard the tip goes right, down or left; uppercuts start from a lowered blade.
  // The fist rolls about the phone's long axis. With the blade upright that roll is a yaw, which the
  // yaw floor (B.1) counts like a forearm sweep, so the roll is the 0.2 rad (11°) of the window
  // feel test's wrist-roll check rather than the 0.3 of its cautious swings.
  let n = 0, ok = 0;
  const fails = [];
  for (const [gname, go] of GRIPS_HELD) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        for (const [tok, dir] of [...GUARD_DIRS, ...LOW_DIRS]) {
          const speed = [3.5, 4][(n >> 1) & 1];
          const arc = [0.8, 1.0][n & 1];
          const seg = swing(dir, { speed, arc, windup: 0.12, ret: 1.5, retYaw: ((n % 5) - 2) * 0.1, wristRoll: n % 3 === 0 ? 0.2 : 0 });
          const script = tok.startsWith('U') ? [lower(0.1), idle(0.3), seg, idle(0.5)] : [seg, idle(0.5)];
          const { events } = run(script, { rate, ...src, ...go, seed: n }, { kind });
          n++;
          const s = slashes(events);
          if (s.length === 1 && token(s[0].dir) === tok && describe(events) === tok) ok++;
          else check(fails, `${gname} ${sname} ${rate}Hz ${tok} ${speed}/${arc}`, describe(events));
        }
      }
    }
  }
  // every one that registers goes the right way; at most 1 in 50 of these timid swings is missed
  const wrong = fails.filter((f) => !f.endsWith('(none)'));
  assert.equal(wrong.length, 0, `wrong direction or extra events:\n  ${wrong.slice(0, 20).join('\n  ')}`);
  assert.ok(ok / n >= 0.98, `${ok}/${n} detected:\n  ${fails.slice(0, 20).join('\n  ')}`);
});

test('forearm-sweep horizontals (yawShare 0.6 and 1.0) at 5 and 9 rad/s give R and L, in any grip', () => {
  const fails = [];
  let n = 0;
  for (const [gname, go] of GRIPS_HELD) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        for (const yawShare of [0.6, 1.0]) {
          for (const speed of [5, 9]) {
            for (const [tok, dir] of [['R', 0], ['L', PI]]) {
              n++;
              const { events } = run([swing(dir, { speed, yawShare }), idle(0.4)], { rate, ...src, ...go }, { kind });
              if (describe(events) !== tok) check(fails, `${gname} ${sname} ${rate}Hz ${tok}@${speed} yawShare ${yawShare}`, describe(events));
            }
          }
        }
      }
    }
  }
  assertNoFails(fails, n);
});

test('no false slashes: walking, idling, a held cock-back, a slow lower, returns to guard and body turns up to 2 rad/s', () => {
  const fails = [];
  for (const [gname, go] of GRIPS_HELD) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        const so = { rate, ...src, ...go };
        const lean = go.lean ?? 0.45;
        const cases = [
          ['walk 8 s', [{ kind: 'walk', duration: 8 }]],
          ['idle 5 s', [idle(5)]],
          ['cock-back held 2 s', [{ kind: 'raise', hold: 2 }, lower(PI / 2 - lean, { duration: 1.2 }), idle(0.5)]],
          ['slow lower and back', [lower(0.1), idle(0.6), lower(PI / 2 - lean), idle(0.5)]],
          // the yaw floor counts a body turn about up at ≥ 0.7 of its rate: still below the lookMax floor
          ['turn 90° over 1.5 s (peak 1.96 rad/s)', [{ kind: 'turn', yaw: PI / 2, duration: 1.5 }, idle(0.5)]],
          ['turn -90° over 1.5 s', [{ kind: 'turn', yaw: -PI / 2, duration: 1.5 }, idle(0.5)]],
          ['look around', [{ kind: 'turn', yaw: 0.6, duration: 0.8 }, idle(0.3), { kind: 'turn', yaw: -0.6, duration: 0.8 }, idle(0.5)]],
        ];
        for (const [name, script] of cases) {
          const { events } = run(script, so, { kind });
          // (the held cock-back is a wind-up, and only that)
          const want = name.startsWith('cock-back') ? 'windup' : '(none)';
          if (describe(events) !== want) check(fails, `${gname} ${sname} ${rate}Hz ${name}`, describe(events));
        }
        // returns to guard after a strike: the synth's normal (2.5 rad/s), fast (4) and slow, off-target ones
        for (const [speed, ret, retYaw] of [[9, 2.5, 0], [9, 4, 0], [4, 1.5, 0.44], [6, 2, -0.44], [4, 1, 0.3]]) {
          const { events } = run([swing(0, { speed, arc: 1.0, ret, retYaw }), idle(0.6), swing(PI, { speed, arc: 1.0, ret, retYaw: -retYaw }), idle(0.6)], so, { kind });
          if (describe(events) !== 'R L') check(fails, `${gname} ${sname} ${rate}Hz R,L at ${speed} with returns at ${ret}`, describe(events));
          const d = run([swing(-PI / 2, { speed, arc: 1.0, ret, retYaw }), idle(0.6)], so, { kind });
          if (describe(d.events) !== 'D') check(fails, `${gname} ${sname} ${rate}Hz D at ${speed} with a return at ${ret}`, describe(d.events));
        }
      }
    }
  }
  assertNoFails(fails);
});

// ---- 2. latency ----------------------------------------------------------------------------------

test('latency from motion onset: 5 rad/s arc 1.0 median ≤ 90 ms, worst ≤ 110; 9 rad/s arc 1.4 median ≤ 70, worst ≤ 85', () => {
  // The cardinals, as motion-feel.test.mjs measures them: R, D and L from the guard, U from a low
  // guard. Onset: the phone's rotation reaching 10% of its peak. (The hilt hold's wrist turns
  // `wrist` = 0.45 of the stroke 30 ms after the arm, so the phone starts ≈ 10 ms after the arm's
  // own 10% point, τ = 0.0866, which the window tests use for their wrist share of 0.3.)
  const phoneOnset = (speed, arc) => {
    const T = (1.875 * arc) / speed;
    const v = (t) => (t <= 0 || t >= T ? 0 : 30 * (t / T) ** 2 * (1 - t / T) ** 2);
    const rate = (t) => 0.55 * v(t) + 0.45 * v(t - 0.03);
    let peak = 0;
    for (let t = 0; t < T + 0.03; t += 1e-4) peak = Math.max(peak, rate(t));
    let t = 0;
    while (rate(t) < 0.1 * peak) t += 1e-4;
    return t;
  };
  const measure = (speed, arc, windup) => {
    const lats = [];
    const strokeStart = windup ? (1.875 * windup) / (0.4 * speed) + 0.06 : 0;
    for (const rate of [50, 100, 200]) {
      for (const lean of [0.2, 0.45, 0.8]) {
        for (const [tok, dir] of [['R', 0], ['D', -PI / 2], ['L', PI], ['U', PI / 2]]) {
          const low = tok === 'U' ? [lower(0.1), idle(0.3)] : [];
          const onset = LEAD + (low.length ? 1.7 : 0) + strokeStart + phoneOnset(speed, arc);
          const s = slashes(run([...low, swing(dir, { speed, arc, windup }), idle(0.4)], { rate, lean }).events);
          assert.ok(s.length === 1 && token(s[0].dir) === tok, `speed ${speed} arc ${arc} ${tok} lean ${lean} ${rate} Hz: ${describe(s)}`);
          lats.push(s[0].t - onset);
        }
      }
    }
    lats.sort((a, b) => a - b);
    return { median: lats[lats.length >> 1], worst: lats[lats.length - 1] };
  };
  const five = measure(5, 1.0, 0.3);
  const nine = measure(9, 1.4, 0.3);
  assert.ok(five.median <= 0.09 + 1e-9 && five.worst <= 0.11 + 1e-9, `5 rad/s: median ${(five.median * 1000).toFixed(0)} ms, worst ${(five.worst * 1000).toFixed(0)} ms`);
  assert.ok(nine.median <= 0.07 + 1e-9 && nine.worst <= 0.085 + 1e-9, `9 rad/s: median ${(nine.median * 1000).toFixed(0)} ms, worst ${(nine.worst * 1000).toFixed(0)} ms`);
});

test('a chop straight down from the guard gives D within 180, 140 and 100 ms at 3.5, 5 and 9 rad/s', () => {
  // the dip rule is off in the hilt grip (dipSector 0): a chop fires while it still speeds up
  for (const [speed, arc, worst] of [[3.5, 0.8, 0.18], [5, 1.0, 0.14], [9, 1.4, 0.1]]) {
    for (const windup of [0, 0.12]) {
      for (const rate of [50, 100, 200]) {
        for (const lean of [0.2, 0.45, 0.8]) {
          const onset = LEAD + (windup ? (1.875 * windup) / (0.4 * speed) + 0.06 : 0) + 0.0866 * ((1.875 * arc) / speed);
          const s = slashes(run([swing(-PI / 2, { speed, arc, windup }), idle(0.6)], { rate, lean }).events);
          const label = `chop ${speed}/${arc} windup ${windup} lean ${lean} ${rate} Hz`;
          assert.ok(s.length === 1 && token(s[0].dir) === 'D', `${label}: ${describe(s)}`);
          assert.ok(s[0].t - onset <= worst, `${label}: ${((s[0].t - onset) * 1000).toFixed(0)} ms`);
        }
      }
    }
  }
});

// ---- 3. direction ---------------------------------------------------------------------------------

test('a mirrored setup is corrected by setFlip: x → π − dir, y → −dir', () => {
  for (const [fx, fy] of [[true, false], [false, true], [true, true]]) {
    for (const rate of [50, 100]) {
      for (const [, dir] of [...GUARD_DIRS, ...LOW_DIRS]) {
        // a flipped U is a D, and a flipped D a U: lower the blade first whenever the result rises
        let want = dir;
        if (fx) want = PI - want;
        if (fy) want = -want;
        const up = Math.sin(dir) > 0.1 || Math.sin(want) > 0.1;
        const script = up ? [lower(0.1), idle(0.3), swing(dir, { speed: 7 }), idle(0.4)] : [swing(dir, { speed: 7 }), idle(0.4)];
        const s = slashes(run(script, { rate }, { flip: [fx, fy] }).events);
        assert.ok(s.length === 1 && token(s[0].dir) === token(want), `flip ${fx}/${fy} ${token(dir)} → ${s.map((e) => token(e.dir))}, want ${token(want)}`);
      }
    }
  }
});

// ---- 4. calibration ---------------------------------------------------------------------------------

test('swings too gentle to slash still give candidates; calibrating from them makes them slash', () => {
  for (const [gname, go] of [GRIPS_HELD[0], GRIPS_HELD[1], GRIPS_HELD[2], GRIPS_HELD[5]]) {
    const script = [];
    for (let i = 0; i < 5; i++) script.push(idle(0.4), swing(i % 2 ? PI : 0, { speed: 2.4, arc: 0.8, windup: 0.1, ret: 1.2 }), idle(0.4));
    const before = run(script, { rate: 100, ...go });
    assert.equal(slashes(before.events).length, 0, `${gname}: below the thresholds at 1×`);
    const peaks = pickStrokes(before.candidates);
    assert.equal(peaks.length, 5, `${gname}: 5 strokes among ${before.candidates.length} candidates`);
    const s = suggestSensitivity(peaks, CALIBRATION);
    assert.ok(s > 1.3 && s <= CALIBRATION.max, `${gname}: suggested ${s}`);
    const after = run(script, { rate: 100, ...go }, { sensitivity: s });
    assert.equal(describe(after.events), 'R L R L R', `${gname} at ${s}×`);
    // and still nothing from walking or a slow look around at that sensitivity
    assert.equal(run([{ kind: 'walk', duration: 6 }, { kind: 'turn', yaw: 0.6, duration: 1.2 }, idle(0.5)], { rate: 100, ...go }, { sensitivity: s }).events.length, 0);
  }
});
