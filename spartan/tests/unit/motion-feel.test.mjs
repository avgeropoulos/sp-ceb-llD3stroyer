// Motion feel after real-phone feedback ("some swings did nothing", "it reacted late", "wrong
// direction", "the view turned on its own / was jumpy"): gentle swings, latency, grip-independent
// directions, mirrored setups, calibration from swing candidates, and gyro look that only follows
// deliberate body turns. All through the synthetic phone (§5.4) → MotionFrames → GestureRecognizer.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer, GESTURE_DEFAULTS, snapCardinal, slashThreshold } from '../../src/main/assets/game/js/motion/gestures.js';
import { suggestSensitivity, pickStrokes, CALIBRATION } from '../../src/main/assets/game/js/ui/training.js';

const PI = Math.PI;
const DEG = PI / 180;
const TOKENS = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
const token = (dir) => TOKENS[((Math.round(dir / (PI / 4)) % 8) + 8) % 8];
const DIRS = TOKENS.map((k, i) => [k, i <= 4 ? (i * PI) / 4 : (i * PI) / 4 - 2 * PI]);
const LEAD = 1.2;
const idle = (duration) => ({ kind: 'idle', duration });
const swing = (dir, more = {}) => ({ kind: 'swing', dir, ...more });

/**
 * Grips (phone in the hand) and holds: tilted back 0–60°, rolled ±45°, more upright or flatter,
 * and the screen facing partly sideways (the forearm aimed off the body's forward).
 */
const GRIPS = [
  ['default', {}],
  ['upright', { tilt: 0.4 }],
  ['tilted back 30°', { grip: { pitch: 0.52 } }],
  ['tilted back 60° (flat)', { grip: { pitch: 1.05 } }],
  ['rolled +45°', { grip: { roll: 0.785 } }],
  ['rolled -45°', { grip: { roll: -0.785 } }],
  ['back 40° rolled 30°', { grip: { pitch: 0.7, roll: 0.52 } }],
  ['screen sideways +30°', { aim: 0.52 }],
  ['screen sideways -30°, back 30°', { aim: -0.52, grip: { pitch: 0.52 } }],
];
const SOURCES = [
  ['android', 'android', {}],
  ['web -1', 'web', { includeGravity: false, gravitySign: -1 }],
];

/**
 * Runs a script (after an idle lead-in); consumes look once per frame of sensor time (`fps`; with
 * `passDt` the frame time is passed as main.js does). `gapAfterSlash`: drop that many seconds of
 * samples right after the first slash (a sensor gap while its swing is still under way).
 */
function run(script, so, { kind = 'android', sensitivity = 1, weapon = 'sword', flip = null, onFrame = null, fps = 60, passDt = false, gapAfterSlash = 0 } = {}) {
  const raw = synthesize([idle(LEAD), ...script], so);
  const frames = new MotionFrames({ platformKind: kind });
  const g = new GestureRecognizer({ sensitivity, weapon });
  if (flip) g.setFlip(flip[0], flip[1]);
  const events = [];
  const candidates = [];
  let look = 0, maxStep = 0, maxLook = 0;
  let next = raw[0].t + 1 / fps;
  let skipUntil = -Infinity;
  const steps = [];
  for (const r of raw) {
    if (r.t < skipUntil) continue;
    const s = frames.process(r);
    for (const e of g.push(s)) {
      events.push({ ...e });
      if (gapAfterSlash && e.type === 'slash' && skipUntil === -Infinity) skipUntil = r.t + gapAfterSlash;
    }
    for (const c of g.consumeCandidates()) candidates.push({ ...c });
    if (r.t >= next) {
      next += 1 / fps;
      const y = g.consumeLook(passDt ? 1 / fps : undefined).yaw;
      look += y;
      steps.push(y);
      maxStep = Math.max(maxStep, Math.abs(y));
      maxLook = Math.max(maxLook, Math.abs(look));
      if (onFrame) onFrame(g, r.t, look);
    }
  }
  return { events, candidates, look, maxStep, maxLook, steps, g };
}
const slashes = (events) => events.filter((e) => e.type === 'slash');
const describe = (events) => events.map((e) => (e.type === 'slash' ? token(e.dir) : e.type)).join(' ') || '(none)';

function check(fails, label, msg) {
  if (msg) fails.push(`${label}: ${msg}`);
}
function assertNoFails(fails, total) {
  assert.equal(fails.length, 0, `${fails.length}${total ? '/' + total : ''} failed:\n  ${fails.slice(0, 25).join('\n  ')}`);
}

// ---- 1. detection sensitivity ------------------------------------------------------------------

test('cautious swings register at the default sensitivity (peak 3.5–4 rad/s, arc 0.8–1.0, any grip)', () => {
  // small cock-back, wrist roll, slow sloppy return: what a player afraid of dropping the phone does
  let n = 0, ok = 0;
  const fails = [];
  for (const [gname, go] of GRIPS) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        for (const [tok, dir] of DIRS) {
          const speed = [3.5, 4][(n >> 1) & 1];
          const arc = [0.8, 1.0][n & 1];
          const seg = swing(dir, { speed, arc, windup: 0.12, ret: 1.5, retYaw: ((n % 5) - 2) * 0.1, wristRoll: n % 3 === 0 ? 0.3 : 0 });
          const { events } = run([seg, idle(0.5)], { rate, ...src, ...go, seed: n }, { kind });
          n++;
          const s = slashes(events);
          if (s.length === 1 && token(s[0].dir) === tok) ok++;
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

test('no false slashes: walking, idling, slow body turns, looking around and returns to guard', () => {
  const fails = [];
  for (const [gname, go] of GRIPS) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        const so = { rate, ...src, ...go };
        const cases = [
          ['walk 8 s', [{ kind: 'walk', duration: 8 }]],
          ['idle 5 s', [idle(5)]],
          ['turn 90° over 2 s', [{ kind: 'turn', yaw: PI / 2, duration: 2 }, idle(0.5)]],
          ['turn -90° over 1.3 s', [{ kind: 'turn', yaw: -PI / 2, duration: 1.3 }, idle(0.5)]],
          ['look around', [{ kind: 'turn', yaw: 0.6, duration: 0.8 }, idle(0.3), { kind: 'turn', yaw: -0.6, duration: 0.8 }, idle(0.5)]],
        ];
        for (const [name, script] of cases) {
          const { events } = run(script, so, { kind });
          if (events.length) check(fails, `${gname} ${sname} ${rate}Hz ${name}`, describe(events));
        }
        // returns to guard after a strike: the synth's normal (2.5 rad/s), fast (4, the Agoge demo) and slow, off-target ones
        for (const [speed, ret, retYaw] of [[9, 2.5, 0], [9, 4, 0], [4, 1.5, 0.44], [6, 2, -0.44], [4, 1, 0.3]]) {
          const { events } = run([swing(0, { speed, arc: 1.0, ret, retYaw }), idle(0.6), swing(PI, { speed, arc: 1.0, ret, retYaw: -retYaw }), idle(0.6)], so, { kind });
          if (describe(events) !== 'R L') check(fails, `${gname} ${sname} ${rate}Hz R,L at ${speed} with returns at ${ret}`, describe(events));
        }
      }
    }
  }
  assertNoFails(fails);
});

// ---- 2. latency ----------------------------------------------------------------------------------

test('latency from motion onset: ≈ 90 ms for a 5 rad/s swing, ≤ 70 ms for 9 rad/s', () => {
  // Onset: the stroke reaching 10% of its peak speed (minimum-jerk τ = 0.0866), as in §5.3's test.
  const measure = (speed, arc, windup, dirs) => {
    const lats = [];
    for (const rate of [50, 100, 200]) {
      for (const tilt of [0, 0.5]) {
        for (const dir of dirs) {
          const onset = LEAD + (windup ? (1.875 * windup) / (0.4 * speed) + 0.06 : 0) + 0.0866 * ((1.875 * arc) / speed);
          const s = slashes(run([swing(dir, { speed, arc, windup }), idle(0.4)], { rate, tilt }).events);
          assert.equal(s.length, 1, `speed ${speed} arc ${arc} dir ${dir.toFixed(2)} ${rate} Hz`);
          lats.push(s[0].t - onset);
        }
      }
    }
    lats.sort((a, b) => a - b);
    return { median: lats[lats.length >> 1], worst: lats[lats.length - 1] };
  };
  const cardinals = [0, PI, -PI / 2, PI / 2];
  const five = measure(5, 1.0, 0.3, cardinals);
  const nine = measure(9, 1.6, 0.3, cardinals);
  const nineShort = measure(9, 1.0, 0.3, cardinals);
  assert.ok(five.median <= 0.09 + 1e-9 && five.worst <= 0.1 + 1e-9, `5 rad/s: median ${(five.median * 1000).toFixed(0)} ms, worst ${(five.worst * 1000).toFixed(0)} ms`);
  assert.ok(nine.median <= 0.07 + 1e-9 && nine.worst <= 0.08 + 1e-9, `9 rad/s: median ${(nine.median * 1000).toFixed(0)} ms, worst ${(nine.worst * 1000).toFixed(0)} ms`);
  assert.ok(nineShort.worst <= 0.06 + 1e-9, `9 rad/s arc 1: worst ${(nineShort.worst * 1000).toFixed(0)} ms`);
  // uppercuts from the guard are not held to the end: with the usual small dip they are as quick as
  // any cut, and even straight up from the guard they fire before the stroke ends
  const up = measure(9, 1.6, 0.3, [PI / 2]);
  assert.ok(up.worst <= 0.08 + 1e-9, `uppercut with a dip: worst ${(up.worst * 1000).toFixed(0)} ms`);
  for (const arc of [1.2, 1.6]) {
    for (const rate of [50, 100, 200]) {
      const stroke = (1.875 * arc) / 9;
      const s = slashes(run([swing(PI / 2, { speed: 9, arc, windup: 0 }), idle(0.6)], { rate, tilt: 0.5 }).events);
      assert.ok(s.length === 1 && token(s[0].dir) === 'U', `uppercut arc ${arc} ${rate} Hz`);
      assert.ok(s[0].t - LEAD < 0.7 * stroke, `uppercut arc ${arc} ${rate} Hz fired ${((s[0].t - LEAD) * 1000).toFixed(0)} ms into a ${(stroke * 1000).toFixed(0)} ms stroke`);
    }
  }
});

test('a dip to a low guard before an uppercut is no chop; a chop from the guard still is', () => {
  // The Agoge teaches the uppercut from a low guard: the dip (0.3–0.5 rad at 0.4 × the stroke's
  // speed, so up to 5 rad/s) is faster than a cautious chop, yet only the uppercut fires.
  const fails = [];
  let n = 0;
  for (const tilt of [0, 0.4, -0.3]) {
    for (const speed of [5, 9, 13]) {
      for (const windup of [0.3, 0.4, 0.5]) {
        for (const rate of [50, 100, 200]) {
          n++;
          const ev = run([swing(PI / 2, { speed, windup }), idle(0.8)], { rate, tilt }).events;
          if (describe(ev) !== 'U') fails.push(`tilt ${tilt} speed ${speed} dip ${windup} ${rate} Hz: ${describe(ev)}`);
        }
      }
    }
  }
  assertNoFails(fails, n);
  // a chop straight down from the guard (no raise first) fires while it still speeds up
  for (const [speed, arc, worst] of [[3.5, 0.8, 0.18], [5, 1.0, 0.14], [9, 1.6, 0.1]]) {
    for (const windup of [0, 0.12]) {
      for (const rate of [50, 100, 200]) {
        const onset = LEAD + (windup ? (1.875 * windup) / (0.4 * speed) + 0.06 : 0) + 0.0866 * ((1.875 * arc) / speed);
        const s = slashes(run([swing(-PI / 2, { speed, arc, windup }), idle(0.6)], { rate }).events);
        assert.ok(s.length === 1 && token(s[0].dir) === 'D', `chop ${speed}/${arc} from the guard ${rate} Hz: ${describe(s)}`);
        assert.ok(s[0].t - onset <= worst, `chop ${speed}/${arc} from the guard ${rate} Hz: ${((s[0].t - onset) * 1000).toFixed(0)} ms`);
      }
    }
  }
});

// ---- 3. direction ---------------------------------------------------------------------------------

test('directions do not depend on the grip: R/L/U/D and the diagonals at 5–9 rad/s', () => {
  const fails = [];
  let n = 0;
  for (const [gname, go] of GRIPS) {
    for (const [sname, kind, src] of SOURCES) {
      for (const rate of [50, 100, 200]) {
        for (const speed of [5, 9]) {
          for (const [tok, dir] of DIRS) {
            n++;
            const { events } = run([swing(dir, { speed }), idle(0.4)], { rate, ...src, ...go }, { kind });
            if (describe(events) !== tok) check(fails, `${gname} ${sname} ${rate}Hz ${tok}@${speed}`, describe(events));
          }
        }
      }
    }
  }
  assertNoFails(fails, n);
});

test('wrist roll about the forearm does not bend the direction', () => {
  // (Body-right is world-fixed during the swing, so a roll about a forearm that has already swept
  // θ leaks sin θ of the roll rate into the vertical; a roll this size stays within the snap.)
  const fails = [];
  for (const [gname, go] of GRIPS) {
    for (const rate of [50, 100]) {
      for (const [tok, dir] of DIRS) {
        for (const wristRoll of [-0.2, 0.2]) {
          // 11° of roll during the stroke (and half of it kept): a wrist that pronates as it cuts
          const { events } = run([swing(dir, { speed: 6, arc: 1.0, wristRoll, rollEnd: wristRoll / 2 }), idle(0.4)], { rate, ...go });
          if (describe(events) !== tok) check(fails, `${gname} ${rate}Hz ${tok} roll ${wristRoll}`, describe(events));
        }
      }
    }
  }
  assertNoFails(fails);
});

test('slashes snap to the nearest cardinal within ~20° (rawDir keeps the measured angle)', () => {
  assert.equal(snapCardinal(-15 * DEG, GESTURE_DEFAULTS.cardinalSnap), 0);
  assert.equal(snapCardinal(PI - 0.3, GESTURE_DEFAULTS.cardinalSnap), PI);
  assert.equal(snapCardinal(-PI + 0.3, GESTURE_DEFAULTS.cardinalSnap), PI);
  assert.equal(snapCardinal(-PI / 2 + 0.2, GESTURE_DEFAULTS.cardinalSnap), -PI / 2);
  assert.equal(snapCardinal(PI / 4, GESTURE_DEFAULTS.cardinalSnap), PI / 4);
  assert.ok(Object.is(snapCardinal(-0.01, GESTURE_DEFAULTS.cardinalSnap), 0), 'never −0');
  for (const [want, dir] of [[0, -15 * DEG], [0, 12 * DEG], [PI, PI + 15 * DEG], [-PI / 2, -PI / 2 + 14 * DEG]]) {
    const s = slashes(run([swing(dir, { speed: 7 }), idle(0.4)], { rate: 100, tilt: 0.25 }).events);
    assert.equal(s.length, 1);
    assert.equal(s[0].dir, want, `a slash at ${(dir / DEG).toFixed(0)}° is shown at ${(want / DEG).toFixed(0)}°`);
    const off = Math.abs(Math.atan2(Math.sin(s[0].rawDir - s[0].dir), Math.cos(s[0].rawDir - s[0].dir)));
    assert.ok(off > 0.1 && off < 25 * DEG, `rawDir ${(s[0].rawDir / DEG).toFixed(1)}°`);
  }
  const diag = slashes(run([swing(-PI / 4, { speed: 7 }), idle(0.4)], { rate: 100, tilt: 0.25 }).events);
  assert.equal(token(diag[0].dir), 'DR', 'diagonals stay diagonal');
});

test('a mirrored setup is corrected by setFlip: x → π − dir (and look), y → −dir', () => {
  // the setting's contract, on a normal sensor: every direction comes out mirrored
  for (const [fx, fy] of [[true, false], [false, true], [true, true]]) {
    for (const rate of [50, 100]) {
      for (const [, dir] of DIRS) {
        const s = slashes(run([swing(dir, { speed: 7 }), idle(0.4)], { rate, tilt: 0.25 }, { flip: [fx, fy] }).events);
        let want = dir;
        if (fx) want = PI - want;
        if (fy) want = -want;
        assert.ok(s.length === 1 && token(s[0].dir) === token(want), `flip ${fx}/${fy} ${token(dir)} → ${s.map((e) => token(e.dir))}, want ${token(want)}`);
      }
    }
  }
  // a sensor that reports one rotation axis the wrong way round (in landscape, rot 90: device x is
  // screen-up, the yaw of an upright phone; device y is the pitch): uncorrected the cardinals come
  // out mirrored, corrected they are right again
  const mirror = (raw, fx, fy) => raw.map((r) => ({ ...r, gyro: [fx ? -r.gyro[0] : r.gyro[0], fy ? -r.gyro[1] : r.gyro[1], r.gyro[2]] }));
  for (const [fx, fy, toks] of [[true, false, ['R', 'L']], [false, true, ['U', 'D']]]) {
    for (const tok of toks) {
      const dir = DIRS.find((d) => d[0] === tok)[1];
      for (const flip of [false, true]) {
        const raw = mirror(synthesize([idle(LEAD), swing(dir, { speed: 7, windup: 0.15 }), idle(0.4)], { rate: 100, rot: 90, tilt: 0.5 }), fx, fy);
        const frames = new MotionFrames({ platformKind: 'android' });
        const g = new GestureRecognizer();
        g.setFlip(flip && fx, flip && fy);
        const ev = [];
        for (const r of raw) for (const e of g.push(frames.process(r))) ev.push({ ...e });
        const want = flip ? tok : token(fx ? PI - dir : -dir);
        assert.equal(describe(ev), want, `${fx ? 'x' : 'y'}-mirrored sensor, ${tok}, flip ${flip}`);
      }
    }
  }
  // flipX turns gyro look the other way too
  const plain = run([{ kind: 'turn', yaw: 0.6, duration: 1.0 }, idle(0.8)], { rate: 100 });
  const flipped = run([{ kind: 'turn', yaw: 0.6, duration: 1.0 }, idle(0.8)], { rate: 100 }, { flip: [true, false] });
  assert.ok(Math.abs(plain.look - 0.6) < 0.05 && Math.abs(flipped.look + 0.6) < 0.05, `look ${plain.look} / ${flipped.look}`);
});

// ---- 4. look ---------------------------------------------------------------------------------------

test('slashes with sloppy returns (up to 25° off, 1–2 rad/s) change the view by < 3° net', () => {
  const fails = [];
  for (const [gname, go] of GRIPS) {
    for (const rate of [50, 100, 200]) {
      for (const speed of [4, 6, 9]) {
        const script = [];
        const offs = [0.44, -0.3, 0.2, -0.44, 0.35, -0.1];
        const rets = [1, 1.5, 2, 1.2, 1.8, 1];
        const dirs = [0, PI, PI / 4, -PI / 2, (3 * PI) / 4, -PI / 4];
        for (let i = 0; i < 6; i++) script.push(idle(0.3), swing(dirs[i], { speed, arc: 1.0, ret: rets[i], retYaw: offs[i], retPitch: offs[(i + 2) % 6] / 2, wristRoll: 0.2 }));
        script.push(idle(1.0));
        const r = run(script, { rate, ...go });
        if (Math.abs(r.look) >= 3 * DEG || r.maxLook >= 3 * DEG) check(fails, `${gname} ${rate}Hz speed ${speed}`, `net ${(r.look / DEG).toFixed(2)}°, max ${(r.maxLook / DEG).toFixed(2)}°`);
        if (r.maxStep > 1.5 * DEG) check(fails, `${gname} ${rate}Hz speed ${speed}`, `frame step ${(r.maxStep / DEG).toFixed(2)}°`);
      }
    }
  }
  assertNoFails(fails);
});

test('a deliberate 90° body turn over 2 s turns the view ≥ 80°, smoothly; no frame moves it > 1.5°', () => {
  for (const [gname, go] of GRIPS) {
    for (const rate of [50, 100, 200]) {
      for (const yaw of [PI / 2, -PI / 2]) {
        const r = run([{ kind: 'turn', yaw, duration: 2 }, idle(1.0)], { rate, ...go });
        assert.ok(Math.abs(r.look) >= 80 * DEG && Math.abs(r.look) <= 92 * DEG, `${gname} ${rate} Hz: turned ${(r.look / DEG).toFixed(1)}°`);
        assert.ok(r.maxStep <= 1.5 * DEG + 1e-9, `${gname} ${rate} Hz: frame step ${(r.maxStep / DEG).toFixed(2)}°`);
        // smooth: never backwards (beyond sensor noise), and no frame jumps more than twice its
        // neighbours plus 0.2°
        for (let i = Math.floor(LEAD * 60); i < r.steps.length - 1; i++) {
          assert.ok(r.steps[i] * Math.sign(yaw) >= -0.05 * DEG, `${gname} ${rate} Hz: the view turned back at frame ${i}`);
          const lim = 2 * Math.max(Math.abs(r.steps[i - 1]), Math.abs(r.steps[i + 1])) + 0.2 * DEG;
          assert.ok(Math.abs(r.steps[i]) <= lim, `${gname} ${rate} Hz: a jump at frame ${i}`);
        }
      }
    }
  }
  // the view never moves by more than 1.5° in a frame, whatever the phone does
  const busy = [];
  for (let i = 0; i < 12; i++) busy.push(swing(DIRS[i % 8][1], { speed: 5 + (i % 4) * 3, ret: 1 + (i % 3) }), { kind: 'turn', yaw: i % 2 ? 0.5 : -0.4, duration: 0.6 + (i % 3) * 0.3 });
  busy.push({ kind: 'walk', duration: 2 }, { kind: 'throw' }, idle(1));
  for (const rate of [50, 100, 200]) assert.ok(run(busy, { rate }, { weapon: 'spear' }).maxStep <= 1.5 * DEG + 1e-9);
});

test('the view never turns on its own: idle, walking and strikes leave it within 3°', () => {
  for (const rate of [50, 100, 200]) {
    assert.ok(Math.abs(run([idle(10)], { rate }).maxLook) < 0.5 * DEG, 'idle');
    assert.ok(run([{ kind: 'walk', duration: 8 }], { rate }).maxLook < 3 * DEG, 'walking');
    // a strike that stays out, a cock-back held before a strike, a thrust
    assert.ok(Math.abs(run([swing(0, { ret: 0 }), idle(1.5)], { rate }).look) < 1 * DEG, 'strike stays out');
    const cock = run([swing(PI, { speed: 1.5, arc: 0.3, ret: 0, windup: 0, recoil: 0 }), idle(0.12), swing(0, { speed: 6, windup: 0 }), idle(1.0)], { rate });
    assert.ok(Math.abs(cock.look) < 3 * DEG, `slow cock-back then strike: ${(cock.look / DEG).toFixed(1)}°`);
    assert.ok(run([{ kind: 'thrust' }, idle(0.6), { kind: 'thrust' }, idle(0.6)], { rate }).maxLook < 1 * DEG, 'thrusts');
  }
});

test('swings too gentle to slash do not turn the view either', () => {
  // 2.2–2.7 rad/s (no slash at 1×, e.g. before the Agoge calibration): the stroke is faster than a
  // turn, so it is a strike for the view: its cock-back is taken back and its return discarded.
  for (const rate of [50, 100, 200]) {
    for (const speed of [2.2, 2.5, 2.7]) {
      const script = [];
      for (let i = 0; i < 5; i++) script.push(idle(0.3), swing(i % 2 ? PI : 0, { speed }));
      script.push(idle(1.0));
      const r = run(script, { rate });
      assert.equal(slashes(r.events).length, 0, `speed ${speed} ${rate} Hz: ${describe(r.events)}`);
      assert.ok(Math.abs(r.look) < 1 * DEG, `speed ${speed} ${rate} Hz: net ${(r.look / DEG).toFixed(1)}°`);
      assert.ok(r.maxLook < 12 * DEG, `speed ${speed} ${rate} Hz: up to ${(r.maxLook / DEG).toFixed(1)}° on the way`);
      assert.ok(r.maxStep <= GESTURE_DEFAULTS.lookStep + 1e-9);
    }
  }
});

test('recenter and the spear cam hold/resume still work with the new look', () => {
  // recenter mid-turn: nothing jumps and the turn carries on
  let recentered = false;
  const r = run([idle(0.4), { kind: 'turn', yaw: 0.6, duration: 2.0 }, idle(0.6)], { rate: 100 }, {
    onFrame: (g, t) => {
      if (!recentered && t > LEAD + 1.4) {
        g.recenter();
        recentered = true;
      }
    },
  });
  assert.ok(r.maxStep < 0.02 && Math.abs(r.look - 0.6) < 0.06, `look ${r.look}, step ${r.maxStep}`);
  // hold freezes the view (a throw's spear cam), resume follows again without a jump
  let phase = 0;
  let heldMoves = 0;
  let resumeStep = null;
  let lookAtHold = 0;
  const h = run([idle(0.3), { kind: 'turn', yaw: 0.8, duration: 1.0 }, idle(0.6), { kind: 'turn', yaw: -0.5, duration: 1.0 }, idle(0.8)], { rate: 100 }, {
    onFrame: (g, t, look) => {
      if (phase === 0 && t > LEAD + 0.2) {
        g.holdLook();
        lookAtHold = look;
        phase = 1;
      } else if (phase === 1) {
        if (Math.abs(look - lookAtHold) > 1e-9) heldMoves++;
        if (t > LEAD + 2.0) {
          g.resumeLook();
          phase = 2;
          resumeStep = g.consumeLook().yaw;
        }
      }
    },
  });
  assert.equal(heldMoves, 0, 'held');
  assert.equal(resumeStep, 0, 'resume does not jump');
  assert.ok(Math.abs(h.look - lookAtHold + 0.5) < 0.06, `only the turn after resume is followed: ${h.look - lookAtHold}`);
  // a throw holds look by itself until resumeLook() (or the 2.5 s failsafe)
  const t = run([{ kind: 'throw' }, idle(0.5)], { rate: 100 }, { weapon: 'spear' });
  assert.equal(t.g.debug.lookState, 'hold');
});

// ---- 5. calibration ---------------------------------------------------------------------------------

test('swings too gentle to slash still give candidates; calibrating from them makes them slash', () => {
  for (const [gname, go] of [GRIPS[0], GRIPS[3], GRIPS[7]]) {
    const script = [];
    for (let i = 0; i < 5; i++) script.push(idle(0.4), swing(i % 2 ? PI : 0, { speed: 2.4, arc: 0.8, windup: 0.1, ret: 1.2 }), idle(0.4));
    const before = run(script, { rate: 100, ...go });
    assert.equal(slashes(before.events).length, 0, `${gname}: below the thresholds at 1×`);
    const peaks = pickStrokes(before.candidates);
    assert.equal(peaks.length, 5, `${gname}: 5 strokes among ${before.candidates.length} candidates`);
    const s = suggestSensitivity(peaks, CALIBRATION);
    assert.ok(s > 1.5 && s <= CALIBRATION.max, `${gname}: suggested ${s}`);
    const after = run(script, { rate: 100, ...go }, { sensitivity: s });
    assert.equal(describe(after.events), 'R L R L R', `${gname} at ${s}×`);
    // and still nothing from walking or a slow look around at that sensitivity
    assert.equal(run([{ kind: 'walk', duration: 6 }, { kind: 'turn', yaw: 0.6, duration: 1.2 }, idle(0.5)], { rate: 100, ...go }, { sensitivity: s }).events.length, 0);
  }
  // a hard slasher (9 rad/s with cock-back and return): the strokes are picked, not the cock-backs or returns
  const hard = [];
  for (let i = 0; i < 5; i++) hard.push(idle(0.3), swing(i % 2 ? PI : 0, { speed: 9 }));
  const h = run(hard, { rate: 100 });
  const peaks = pickStrokes(h.candidates);
  assert.equal(peaks.length, 5, `strokes among ${h.candidates.map((c) => c.peak.toFixed(1))}`);
  assert.ok(peaks.every((p) => p > 7), `${peaks}`);
  assert.equal(suggestSensitivity(peaks, CALIBRATION), 0.6);
  // the original contract of suggestSensitivity is unchanged
  assert.equal(suggestSensitivity([9, 9, 9, 9, 9]), 1);
});

test('sensitivity 2.2 (the calibration ceiling) keeps classification for proportionally scaled motions', () => {
  const sens = 2.2;
  const fails = [];
  for (const rate of [50, 100, 200]) {
    for (const tilt of [0, 0.5]) {
      const so = { rate, tilt };
      for (const [tok, dir] of DIRS) {
        const { events } = run([swing(dir, { speed: 9 / sens, ret: 2.5 / sens }), idle(0.6)], so, { sensitivity: sens });
        if (describe(events) !== tok) check(fails, `${rate}Hz tilt ${tilt} ${tok}`, describe(events));
      }
      const thr = run([{ kind: 'thrust', duration: 0.18 * sens, retract: 0.3 * sens }, idle(0.8)], so, { sensitivity: sens });
      if (describe(thr.events) !== 'thrust') check(fails, `${rate}Hz tilt ${tilt} thrust`, describe(thr.events));
      const w = run([{ kind: 'walk', duration: 6 }, idle(1)], so, { sensitivity: sens });
      if (w.events.length) check(fails, `${rate}Hz tilt ${tilt} walk`, describe(w.events));
    }
  }
  assertNoFails(fails);
});

test('a hard swing at a high sensitivity is one slash: its short, fast wind-up is no stroke', () => {
  // e.g. a gentle swinger, calibrated to 1.6–2.2×, who swings hard in battle (wind-up 0.3 rad at up to 7 rad/s)
  const fails = [];
  let n = 0;
  for (const sens of [1.6, 2.2]) {
    for (const rate of [50, 100, 200]) {
      for (const speed of [9, 13, 18]) {
        for (const [tok, dir] of DIRS) {
          n++;
          const { events } = run([swing(dir, { speed }), idle(0.6)], { rate }, { sensitivity: sens });
          const got = slashes(events).map((e) => token(e.dir)).join(' ');
          if (got !== tok) check(fails, `${sens}× ${rate}Hz ${tok}@${speed}`, got || '(none)');
        }
      }
    }
  }
  assertNoFails(fails, n);
});

// ---- Review follow-ups -----------------------------------------------------------------------

test('slashThreshold: swingPeak / s, never below lookMax (a body turn gyro look accepts)', () => {
  assert.equal(slashThreshold(1), GESTURE_DEFAULTS.swingPeak);
  assert.equal(slashThreshold(0.5), GESTURE_DEFAULTS.swingPeak / 0.5);
  assert.equal(slashThreshold(2.2), GESTURE_DEFAULTS.lookMax);
  assert.equal(slashThreshold(9), GESTURE_DEFAULTS.lookMax, 'clamped to SENSITIVITY_MAX');
});

test('at high sensitivity a deliberate body turn is still no slash, and the view follows it', () => {
  for (const sensitivity of [1.6, 1.8, 2.2]) {
    for (const rate of [50, 100, 200]) {
      for (const [yaw, duration] of [[PI / 4, 1], [-PI / 4, 1], [PI / 2, 2], [-PI / 2, 2]]) {
        const r = run([{ kind: 'turn', yaw, duration }, idle(1)], { rate }, { sensitivity });
        const label = `s ${sensitivity}, ${rate} Hz, ${Math.round(yaw / DEG)}° over ${duration} s`;
        assert.equal(slashes(r.events).length, 0, `${label}: ${describe(r.events)}`);
        assert.ok(Math.abs(r.look) >= 0.85 * Math.abs(yaw), `${label}: the view turned ${(r.look / DEG).toFixed(1)}°`);
      }
    }
  }
});

test('a sensor gap while a slash is under way does not turn later opposite swings into its "return"', () => {
  for (const rate of [100, 200]) {
    const script = [swing(0, { speed: 9 }), idle(2.7), swing(PI, { speed: 3.5, arc: 1.0, windup: 0 }), idle(1)];
    const base = run(script, { rate });
    const gap = run(script, { rate }, { gapAfterSlash: 0.15 });
    assert.equal(slashes(base.events).length, 2, `${rate} Hz without a gap: ${describe(base.events)}`);
    assert.equal(slashes(gap.events).length, 2, `${rate} Hz with a gap: ${describe(gap.events)}`);
    assert.ok(slashes(gap.events)[1].t - slashes(base.events)[1].t < 0.02, `${rate} Hz: the gentle swing came late after the gap`);
  }
});

test('at 30 fps the view keeps up with a body turn (main.js passes the frame time to consumeLook)', () => {
  const stopT = LEAD + 2;
  for (const fps of [30, 45, 60]) {
    for (const rate of [100, 200]) {
      let atStop = null;
      const r = run([{ kind: 'turn', yaw: PI / 2, duration: 2 }, idle(1.5)], { rate }, {
        fps, passDt: true, onFrame: (g, t, look) => { if (atStop === null && t >= stopT) atStop = look; },
      });
      assert.ok(atStop >= 0.9 * r.look, `${fps} fps, ${rate} Hz: ${(atStop / DEG).toFixed(1)}° of ${(r.look / DEG).toFixed(1)}° when the body stopped`);
      assert.ok(r.maxStep <= Math.max(GESTURE_DEFAULTS.lookStep, (1.1 * GESTURE_DEFAULTS.lookRate) / fps) + 1e-9, `${fps} fps: frame step ${(r.maxStep / DEG).toFixed(2)}°`);
    }
  }
});
