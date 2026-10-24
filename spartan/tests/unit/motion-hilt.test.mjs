// GestureRecognizer in the hilt grip (docs-hilt-grip.md B.3, H.1 #1): the synthetic phone in the
// hilt hold (§B.4) → MotionFrames({ grip: 'hilt' }) → GestureRecognizer({ grip: 'hilt' }).
// It mirrors motion-gestures.test.mjs. The required matrix: lean {0.2, 0.45, 0.8} × roll {0, 0.3}
// × rate {50, 100, 200} × gravity {android +1 with TYPE_GRAVITY, web +1, web −1 (no gravity sensor
// on the web)}, plus runs without linear acceleration and/or without gravity at lean {0.2, 0.8}.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer, GESTURE_DEFAULTS, GRIPS } from '../../src/main/assets/game/js/motion/gestures.js';

const PI = Math.PI;
const TOKENS = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
/** §3.4: eight 45° sectors centred on their angles. */
const token = (dir) => TOKENS[((Math.round(dir / (PI / 4)) % 8) + 8) % 8];
// From the upright guard the tip goes right, down or left; up from it is the cock-back (B.3 rule 5).
const GUARD_DIRS = [['R', 0], ['DR', -PI / 4], ['D', -PI / 2], ['DL', (-3 * PI) / 4], ['L', PI]];
// Uppercuts start from a low guard: the blade lowered toward the foe first.
const LOW_DIRS = [['U', PI / 2], ['UR', PI / 4], ['UL', (3 * PI) / 4]];
const HILT = GRIPS.hilt;
const HP = HILT.params;
// The idle before each script. Web −1 needs longer: its provisional sign flip (§5.2 b) takes 1 s of
// calm, and only then is the hilt guard seen right and learned from a held guard (guardHold, B.3.3).
const LEAD = 1.2;
const LEAD_FLIP = 1.0 + HILT.guardHold + 0.2;

function variants() {
  const out = [];
  for (const lean of [0.2, 0.45, 0.8]) {
    for (const roll of [0, 0.3]) {
      for (const rate of [50, 100, 200]) {
        const tag = `lean${lean} roll${roll} ${rate}Hz`;
        out.push({ name: `android ${tag}`, kind: 'android', s: { lean, roll, rate } });
        out.push({ name: `web+1 ${tag}`, kind: 'web', s: { lean, roll, rate, includeGravity: false } });
        out.push({ name: `web-1 ${tag}`, kind: 'web', s: { lean, roll, rate, includeGravity: false, gravitySign: -1 } });
      }
    }
  }
  for (const rate of [50, 100, 200]) {
    for (const lean of [0.2, 0.8]) {
      const tag = `lean${lean} ${rate}Hz`;
      out.push({ name: `no-linear ${tag}`, kind: 'android', s: { rate, lean, includeLinear: false } });
      out.push({ name: `no-gravity ${tag}`, kind: 'android', s: { rate, lean, includeGravity: false } });
      out.push({ name: `no-linear-no-gravity ${tag}`, kind: 'android', s: { rate, lean, includeLinear: false, includeGravity: false } });
      out.push({ name: `web-1 no-linear ${tag}`, kind: 'web', s: { rate, lean, includeLinear: false, includeGravity: false, gravitySign: -1 } });
    }
  }
  return out;
}
const MATRIX = variants();
const LIGHT = MATRIX.filter((v, i) => i % 3 === 0 || v.name.startsWith('web-1'));
const leadOf = (s) => (s.gravitySign === -1 ? LEAD_FLIP : LEAD);

/**
 * Synthesizes `script` in the hilt hold (after an idle lead-in), runs frames + gestures in the hilt
 * grip, collects events and candidates. Look is off in the hilt grip: every consumeLook() must be 0.
 */
function run(script, synthOpts, { kind = 'android', weapon = 'sword', sensitivity = 1, filter = null, lead = leadOf(synthOpts), onSample = null } = {}) {
  const full = lead > 0 ? [{ kind: 'idle', duration: lead }, ...script] : script;
  let raw = synthesize(full, { ...synthOpts, hold: 'hilt' });
  if (filter) raw = filter(raw);
  const frames = new MotionFrames({ platformKind: kind, grip: 'hilt' });
  const g = new GestureRecognizer({ sensitivity, weapon, gyroRange: synthOpts.gyroRange || 34.9, grip: 'hilt' });
  const events = [];
  const candidates = [];
  for (const r of raw) {
    const s = frames.process(r);
    for (const e of g.push(s)) events.push({ ...e });
    for (const c of g.consumeCandidates()) candidates.push({ ...c });
    const y = g.consumeLook(1 / 60).yaw;
    if (!Object.is(y, 0)) assert.fail(`consumeLook().yaw is ${y} at t ${r.t.toFixed(3)}: look is off in the hilt grip`);
    if (onSample) onSample(s, g);
  }
  return { events, candidates, g, frames };
}

// (an overSpeed notice is a warning, not a move: the over-speed test looks for it)
const describe = (events) => events.filter((e) => e.type !== 'overSpeed').map((e) => (e.type === 'slash' ? `slash:${token(e.dir)}` : e.type)).join(' ') || '(none)';

/** Runs check(variant) over the matrix and fails with every failing variant listed. */
function overMatrix(check, matrix = MATRIX) {
  const fails = [];
  for (const v of matrix) {
    const r = check(v);
    if (r) fails.push(`${v.name}: ${r}`);
  }
  assert.equal(fails.length, 0, `${fails.length}/${matrix.length} variants failed:\n  ${fails.slice(0, 20).join('\n  ')}`);
}

const swing = (dir, more = {}) => ({ kind: 'swing', dir, ...more });
const idle = (duration) => ({ kind: 'idle', duration });
const lower = (beta = 0.1, more = {}) => ({ kind: 'lower', beta, ...more });

test('R, DR, D, DL and L from the guard, with wind-up, rebound and return, give exactly one slash with the right token', () => {
  overMatrix((v) => {
    for (const [tok, dir] of GUARD_DIRS) {
      const { events } = run([swing(dir), idle(0.4)], v.s, { kind: v.kind });
      if (events.length !== 1 || events[0].type !== 'slash' || token(events[0].dir) !== tok) return `${tok} → ${describe(events)}`;
    }
    return null;
  });
});

test('U, UR and UL each give exactly one slash after lowering the blade (lower β 0.1, idle 0.3)', () => {
  overMatrix((v) => {
    for (const [tok, dir] of LOW_DIRS) {
      const { events } = run([lower(0.1), idle(0.3), swing(dir), idle(0.4)], v.s, { kind: v.kind });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    return null;
  });
});

test('an up swing from the guard (speed 4–12, arc 0.6–1.6) never slashes; it is one windup iff it ends at β′ ≥ windupBeta', () => {
  // the guard reads β′ ≈ guardBeta (1.10), so the swing ends at β′ ≈ 1.10 + arc: arcs ≤ 1.2 never
  // reach 2.35, arcs ≥ 1.4 do
  overMatrix((v) => {
    for (const speed of [4, 8, 12]) {
      for (const arc of [0.6, 0.9, 1.2, 1.4, 1.6]) {
        const { events } = run([swing(PI / 2, { speed, arc, windup: 0, recoil: 0 }), idle(0.5)], v.s, { kind: v.kind });
        const want = arc >= 1.4 ? 'windup' : '(none)';
        if (describe(events) !== want) return `speed ${speed} arc ${arc} → ${describe(events)}`;
      }
    }
    return null;
  });
});

test('R, L, R, L at about 2 Hz (arc 1.0, speed 9, ret 0) gives 4 alternating slashes', () => {
  const sw = (dir) => swing(dir, { arc: 1.0, speed: 9, ret: 0 });
  overMatrix((v) => {
    const { events } = run([sw(0), sw(PI), sw(0), sw(PI), idle(0.3)], v.s, { kind: v.kind });
    const got = describe(events);
    if (got !== 'slash:R slash:L slash:R slash:L') return got;
    const span = events[3].t - events[0].t; // three intervals
    if (!(span > 0.9 && span < 2.1)) return `cadence ${(3 / span).toFixed(2)} Hz`;
    return null;
  });
});

test('a slow return after a strike is not a slash; a deliberate reverse slash is', () => {
  overMatrix((v) => {
    const a = run([swing(0, { speed: 10, ret: 0 }), idle(0.05), swing(PI, { speed: 4 }), idle(0.3)], v.s, { kind: v.kind });
    if (describe(a.events) !== 'slash:R') return `R10,L4 → ${describe(a.events)}`;
    const b = run([swing(0, { speed: 10, ret: 0 }), idle(0.05), swing(PI, { speed: 9 }), idle(0.3)], v.s, { kind: v.kind });
    if (describe(b.events) !== 'slash:R slash:L') return `R10,L9 → ${describe(b.events)}`;
    // a gentle strike's own unhurried return to guard (1–2 rad/s) is never a slash
    for (const ret of [1, 2]) {
      const c = run([swing(0, { speed: 4, arc: 0.9, ret }), idle(0.3), swing(PI, { speed: 4, arc: 0.9, ret }), idle(0.3)], v.s, { kind: v.kind });
      if (describe(c.events) !== 'slash:R slash:L') return `gentle R,L with returns at ${ret} → ${describe(c.events)}`;
    }
    return null;
  });
});

test('forearm sweeps (yawShare 1.0) R and L at 5 and 9 rad/s, at lean 0.2 and 0.45, give R and L', () => {
  // an upright blade swept on the forearm only yaws; the yaw floor counts it at ≥ 0.7 (B.1)
  overMatrix((v) => {
    for (const speed of [5, 9]) {
      for (const [tok, dir] of [['R', 0], ['L', PI]]) {
        const { events } = run([swing(dir, { speed, yawShare: 1 }), idle(0.4)], v.s, { kind: v.kind });
        if (describe(events) !== `slash:${tok}`) return `${tok}@${speed} → ${describe(events)}`;
      }
    }
    return null;
  }, MATRIX.filter((v) => v.s.lean !== 0.8));
});

test('throws at speed 5, 6, 7 give one windup and one throw, no slash, power > 0 and rising; full power from a firm flick', () => {
  // (The recognizer measures ≈ 95% of the synth's nominal speed: the throw resolves at
  // throwMaxSweep, before its peak, and the trunk twist turns the throw's axis away from the
  // body-right it carries. So a nominal 7.5 throw scores ≥ 0.85 and full power comes by 8.)
  overMatrix((v) => {
    const powers = [];
    for (const speed of [5, 6, 7, 7.5, 8, 10]) {
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `speed ${speed} → ${describe(events)}`;
      powers.push(events[1].power);
    }
    const [p5, p6, p7, p75, p8, p10] = powers;
    if (!(p5 > 0 && p5 < p6 && p6 < p7 && p7 < p75 && p75 >= 0.85 && p8 === 1 && p10 === 1)) return `powers ${powers.map((p) => p.toFixed(3))}`;
    return null;
  });
});

test('no false throws: an ordinary lift (cockArc 0.5–1.0) then a chop is that chop with the spear, never a windup or throw', () => {
  const lifts = [0.5, 0.75, 1.0];
  const chops = [['DR', -PI / 4], ['D', -PI / 2], ['DL', (-3 * PI) / 4]];
  const check = (s, kind, liftList) => {
    for (const cockArc of liftList) {
      for (const [tok, dir] of chops) {
        for (const speed of [6, 9, 12]) {
          const { events } = run([{ kind: 'raise', cockArc, hold: 0.15 }, swing(dir, { speed, windup: 0 }), idle(0.5)], s, { kind, weapon: 'spear' });
          if (describe(events) !== `slash:${tok}`) return `lift ${cockArc} then ${tok}@${speed} → ${describe(events)}`;
        }
      }
    }
    return null;
  };
  overMatrix((v) => check(v.s, v.kind, lifts), LIGHT);
  // guards at the shift clamps: G 0.6 (shift −0.35) and G 1.6 (shift +0.25). At G 1.6 the cock-back
  // is at βabs 2.60, exactly 1.0 above the guard (B.3.3), so the largest ordinary lift there is 0.9.
  for (const [G, liftList] of [[0.6, lifts], [1.6, [0.5, 0.75, 0.9]]]) {
    for (const [name, kind, src] of [['android', 'android', {}], ['web-1', 'web', { includeGravity: false, gravitySign: -1 }]]) {
      for (const rate of [50, 200]) {
        const r = check({ lean: PI / 2 - G, rate, ...src }, kind, liftList);
        assert.equal(r, null, `guard ${G} ${name} ${rate} Hz: ${r}`);
      }
    }
  }
});

test('with the spear a D swing without a cock-back is a D slash', () => {
  overMatrix((v) => {
    const { events } = run([swing(-PI / 2, { speed: 9 }), idle(0.4)], v.s, { kind: v.kind, weapon: 'spear' });
    return describe(events) === 'slash:D' ? null : describe(events);
  });
});

test('cock-back then chop: windup and a D slash with the sword, windup and a throw with the spear', () => {
  overMatrix((v) => {
    const script = [{ kind: 'raise' }, swing(-PI / 2, { arc: 2.6, windup: 0 }), idle(0.5)];
    const sword = run(script, v.s, { kind: v.kind });
    if (describe(sword.events) !== 'windup slash:D') return `sword → ${describe(sword.events)}`;
    const spear = run(script, v.s, { kind: v.kind, weapon: 'spear' });
    return describe(spear.events) === 'windup throw' ? null : `spear → ${describe(spear.events)}`;
  }, LIGHT);
});

test('over-speed: a slash or throw at 15 rad/s is flagged fast (on it, or by the overSpeed notice at its end); at 12 it is not', () => {
  // A slash fires early, before its peak: it carries `fast` when its measured peak is already over
  // overSpeed, else the swing's end emits { type: 'overSpeed', fast: true } once it got that fast.
  // (wrist 0: the synth's speed is then the true peak, as in the synth's own tests)
  const flagged = (events) => events.some((e) => e.fast === true);
  overMatrix((v) => {
    for (const [speed, fast] of [[15, true], [12, false]]) {
      for (const [tok, dir] of GUARD_DIRS) {
        const { events } = run([swing(dir, { speed, wrist: 0 }), idle(0.4)], v.s, { kind: v.kind });
        if (describe(events) !== `slash:${tok}` || flagged(events) !== fast) return `${tok}@${speed} → ${events.map((e) => e.type + (e.fast ? '(fast)' : '')).join(' ')}`;
        const notice = events.find((e) => e.type === 'overSpeed');
        if (notice && (events[0].fast || events.length !== 2 || !(notice.peak >= HILT.overSpeed))) return `${tok}@${speed}: notice ${JSON.stringify(notice)}`;
      }
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw' || flagged(events) !== fast) return `throw@${speed} → ${events.map((e) => e.type + (e.fast ? '(fast)' : '')).join(' ')}`;
    }
    return null;
  }, LIGHT);
  // a gyro that clips: a slash that reaches 0.95 of its range is fast whatever its speed
  const { events } = run([swing(0, { speed: 10 }), idle(0.4)], { rate: 200, gyroRange: 8.7 });
  assert.equal(describe(events), 'slash:R');
  assert.ok(flagged(events), 'a clipped gyro is over-speed');
  // the window grip never flags it
  const g = new GestureRecognizer();
  const f = new MotionFrames({ platformKind: 'android' });
  const ev = [];
  for (const r of synthesize([idle(LEAD), swing(0, { speed: 20 }), idle(0.4)], { rate: 200 })) for (const e of g.push(f.process(r))) ev.push(e);
  assert.ok(ev.length === 1 && !('fast' in ev[0]), 'window events are unchanged');
});

test('guard learning: no snap for guardGrace after reset(); a tapping pose then the fist guard heals within 3 s', () => {
  const shiftOf = (G) => Math.min(HILT.guardShift[1], Math.max(HILT.guardShift[0], G - HP.guardBeta));
  for (const [name, kind, src] of [['android', 'android', {}], ['web+1', 'web', { includeGravity: false }]]) {
    for (const rate of [50, 100, 200]) {
      const label = `${name} ${rate} Hz`;
      // tapping pose βabs 0.7 for 1.5 s, then the fist guard at 1.35
      const change = 1.5, rise = 0.5;
      const tapping = { lean: PI / 2 - 0.7, rate, ...src };
      const shifts = [];
      let earliest = Infinity;
      const ev1 = run([idle(change), lower(1.35, { duration: rise }), idle(4)], tapping, {
        kind, lead: 0,
        onSample: (s, g) => {
          if (s.t < 1.0 - 0.02) {
            assert.equal(g.debug.guardBeta, HP.guardBeta, `${label}: snapped at ${s.t.toFixed(2)} s, inside the grace`);
            assert.equal(g.guardLearned, false);
          } else if (earliest === Infinity && g.guardLearned) earliest = s.t;
          if (s.t >= change + 3) shifts.push(shiftOf(g.debug.guardBeta));
        },
      });
      assert.equal(describe(ev1.events), '(none)', label);
      assert.ok(earliest < 1.1, `${label}: first snap at ${earliest}`);
      // a clean start at the fist guard
      let clean = null;
      run([idle(change + rise + 4)], { lean: PI / 2 - 1.35, rate, ...src }, { kind, lead: 0, onSample: (s, g) => (clean = shiftOf(g.debug.guardBeta)) });
      assert.ok(shifts.length > 10 && shifts.every((x) => Math.abs(x - clean) <= 0.01), `${label}: shift ${shifts[0]?.toFixed(3)}…${shifts[shifts.length - 1]?.toFixed(3)}, clean ${clean.toFixed(3)}`);
      // …and the cock-back and the ordinary lift behave the same after the change as from a clean start
      for (const [cockArc, want] of [[1.5, 'windup'], [0.9, '(none)']]) {
        const healed = run([idle(change), lower(1.35, { duration: rise }), idle(3), { kind: 'raise', cockArc }, idle(0.4)], tapping, { kind, lead: 0 });
        const fresh = run([idle(change + rise + 3), { kind: 'raise', cockArc }, idle(0.4)], { lean: PI / 2 - 1.35, rate, ...src }, { kind, lead: 0 });
        assert.equal(describe(healed.events), want, `${label}: healed guard, lift ${cockArc}`);
        assert.equal(describe(fresh.events), want, `${label}: clean guard, lift ${cockArc}`);
      }
    }
  }
  // reset() keeps the learned guard but starts a new grace: a different pose right after it is not learned
  const frames = new MotionFrames({ platformKind: 'android', grip: 'hilt' });
  const g = new GestureRecognizer({ grip: 'hilt' });
  for (const r of synthesize([idle(2)], { hold: 'hilt', lean: PI / 2 - 1.35 })) g.push(frames.process(r));
  const learned = g.debug.guardBeta;
  assert.ok(g.guardLearned && Math.abs(learned - 1.35) < 0.02, `learned ${learned}`);
  g.reset();
  assert.equal(g.guardLearned, true, 'kept across reset()');
  for (const r of synthesize([idle(1.5)], { hold: 'hilt', lean: PI / 2 - 1.0, t0: 2.01 })) {
    g.push(frames.process(r));
    if (r.t < 2.01 + 0.98) assert.equal(g.debug.guardBeta, learned, `changed at ${(r.t - 2.01).toFixed(2)} s after reset()`);
  }
});

test('a thrust followed by a retraction of 0.25, 0.3 or 0.35 s gives exactly one thrust, power > 0.5', () => {
  overMatrix((v) => {
    for (const retract of [0.25, 0.3, 0.35]) {
      const { events } = run([{ kind: 'thrust', retract }, idle(0.6)], v.s, { kind: v.kind });
      if (describe(events) !== 'thrust') return `retract ${retract} → ${describe(events)}`;
      if (!(events[0].power > 0.5)) return `retract ${retract} → power ${events[0].power}`;
    }
    return null;
  });
});

test('10 s of walking, 10 s of idling, and a slow lower to β 0.1 and back give nothing', () => {
  overMatrix((v) => {
    const w = run([{ kind: 'walk', duration: 10 }], v.s, { kind: v.kind });
    if (w.events.length) return `walk → ${describe(w.events)}`;
    const i = run([idle(10)], v.s, { kind: v.kind });
    if (i.events.length) return `idle → ${describe(i.events)}`;
    const l = run([lower(0.1), idle(0.5), lower(PI / 2 - v.s.lean), idle(0.5)], v.s, { kind: v.kind });
    if (l.events.length) return `lower and back → ${describe(l.events)}`;
    return null;
  });
});

test('consumeLook().yaw is 0 on every sample: body turns, swings, cock-backs and throws', () => {
  // (run() checks every sample of every script in this file; this one turns the body on purpose)
  overMatrix((v) => {
    const r = run([{ kind: 'turn', yaw: 1.0, duration: 1.2 }, idle(0.5), { kind: 'turn', yaw: -0.6, duration: 0.6 }, swing(0), { kind: 'throw' }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
    // the internal look still runs (it drives the recoil rule's rest detection), it is just never shown
    r.g.holdLook();
    r.g.resumeLook();
    r.g.recenter();
    return Object.is(r.g.consumeLook(0.1).yaw, 0) ? null : 'look after hold/resume/recenter';
  }, LIGHT);
});

test('detection latency from motion onset is < 130 ms for a 9 rad/s R, L, D, DR or DL', () => {
  // Onset: the stroke reaching 10% of its peak speed (minimum-jerk: τ = 0.0866).
  const speed = 9, arc = 1.4, windup = 0.3;
  let worst = 0;
  overMatrix((v) => {
    const onset = leadOf(v.s) + (1.875 * windup) / (0.4 * speed) + 0.06 + 0.0866 * ((1.875 * arc) / speed);
    for (const [tok, dir] of GUARD_DIRS) {
      const { events } = run([swing(dir, { speed }), idle(0.3)], v.s, { kind: v.kind });
      const e = events.find((x) => x.type === 'slash');
      if (!e) return `${tok}: no slash`;
      const lat = e.t - onset;
      worst = Math.max(worst, lat);
      if (!(lat < 0.13)) return `${tok}: ${(lat * 1000).toFixed(0)} ms`;
    }
    return null;
  });
  assert.ok(worst > 0.03, `latency ${worst} is implausibly small`);
});

test('display rotation 0, 90 or 270 (a natural-landscape tablet held upright) classifies the same', () => {
  overMatrix((v) => {
    const scripts = [
      ...GUARD_DIRS.map(([, dir]) => [swing(dir), idle(0.4)]),
      [lower(0.1), idle(0.3), swing(PI / 2), idle(0.4)],
      [{ kind: 'raise' }, swing(-PI / 2, { arc: 2.6, windup: 0 }), idle(0.5)],
      [{ kind: 'thrust' }, idle(0.6)],
    ];
    for (const script of scripts) {
      const base = describe(run(script, { ...v.s, rot: 0 }, { kind: v.kind }).events);
      for (const rot of [90, 270]) {
        const got = describe(run(script, { ...v.s, rot }, { kind: v.kind }).events);
        if (got !== base) return `rot ${rot}: ${got}, rot 0: ${base}`;
      }
    }
    return null;
  }, LIGHT);
});

// ---- robustness beyond the required matrix (the lighter matrix keeps the run short) ----

for (const sens of [0.6, 1, 1.6]) {
  test(`sensitivity ${sens} keeps classification for proportionally scaled motions`, () => {
    overMatrix((v) => {
      for (const [tok, dir] of GUARD_DIRS) {
        const { events } = run([swing(dir, { speed: 9 / sens, ret: 2.5 / sens }), idle(0.4)], v.s, { kind: v.kind, sensitivity: sens });
        if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
      }
      for (const [tok, dir] of LOW_DIRS) {
        const { events } = run([lower(0.1, { duration: 1.4 * sens }), idle(0.3), swing(dir, { speed: 9 / sens, ret: 2.5 / sens }), idle(0.4)], v.s, { kind: v.kind, sensitivity: sens });
        if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
      }
      for (const speed of [5, 7]) {
        const { events } = run([{ kind: 'throw', speed: speed / sens, windupSpeed: 5 / sens, lookBack: 0.7 * sens }, idle(0.5 * sens)], v.s, { kind: v.kind, weapon: 'spear', sensitivity: sens });
        if (describe(events) !== 'windup throw') return `throw ${speed} → ${describe(events)}`;
      }
      const { events } = run([{ kind: 'thrust', duration: 0.18 * sens, retract: 0.3 * sens }, idle(0.6)], v.s, { kind: v.kind, sensitivity: sens });
      return describe(events) === 'thrust' ? null : `thrust → ${describe(events)}`;
    }, LIGHT);
  });
}

test('left hand: the guard swings, the uppercuts and throws classify the same', () => {
  overMatrix((v) => {
    const s = { ...v.s, hand: 'left' };
    for (const [tok, dir] of GUARD_DIRS) {
      const { events } = run([swing(dir), idle(0.4)], s, { kind: v.kind });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const [tok, dir] of LOW_DIRS) {
      const { events } = run([lower(0.1), idle(0.3), swing(dir), idle(0.4)], s, { kind: v.kind });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const speed of [5, 7]) {
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `throw ${speed} → ${describe(events)}`;
    }
    return null;
  }, LIGHT);
});

test('10% timestamp jitter and occasional dropped samples change nothing', () => {
  const drop = (raw) => raw.filter((r, i) => (i * 7919) % 23 !== 5); // ~4% dropped, scattered
  overMatrix((v) => {
    const s = { ...v.s, jitter: 0.1 };
    for (const [tok, dir] of GUARD_DIRS) {
      const { events } = run([swing(dir), idle(0.4)], s, { kind: v.kind, filter: drop });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const [tok, dir] of LOW_DIRS) {
      const { events } = run([lower(0.1), idle(0.3), swing(dir), idle(0.4)], s, { kind: v.kind, filter: drop });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const speed of [5, 7]) {
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], s, { kind: v.kind, weapon: 'spear', filter: drop });
      if (describe(events) !== 'windup throw') return `throw ${speed} → ${describe(events)}`;
    }
    for (const retract of [0.25, 0.3, 0.35]) {
      const { events } = run([{ kind: 'thrust', retract }, idle(0.6)], s, { kind: v.kind, filter: drop });
      if (describe(events) !== 'thrust') return `thrust ${retract} → ${describe(events)}`;
    }
    const w = run([{ kind: 'walk', duration: 6 }], s, { kind: v.kind, filter: drop });
    return w.events.length ? `walk → ${describe(w.events)}` : null;
  }, LIGHT);
});

test('cock-backs at 3.5–12 rad/s over 0.9–1.5 rad never slash; a deep one is a windup, then a throw', () => {
  overMatrix((v) => {
    for (const windupSpeed of [3.5, 6, 9, 12]) {
      for (const cockArc of [0.9, 1.1, 1.3, 1.5]) {
        const { events } = run([{ kind: 'raise', cockArc, windupSpeed }, idle(0.4)], v.s, { kind: v.kind, weapon: 'spear' });
        // β′ ≈ 1.10 + cockArc: 1.3 and 1.5 reach windupBeta (2.35), 0.9 and 1.1 do not
        const want = cockArc >= 1.3 ? 'windup' : '(none)';
        if (describe(events) !== want) return `cock-back ${cockArc} at ${windupSpeed} → ${describe(events)}`;
      }
      const { events } = run([{ kind: 'throw', windupSpeed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `throw with a cock-back at ${windupSpeed} → ${describe(events)}`;
    }
    return null;
  }, LIGHT);
});

test('settled: true at the guard within settleTime + 0.05 s; false cocked back (screen down) and at a low follow-through', () => {
  for (const lean of [0.2, 0.45, 0.8]) {
    for (const rate of [50, 100, 200]) {
      const label = `lean ${lean} ${rate} Hz`;
      // the guard: settled once still for settleTime
      let tSettled = null;
      run([idle(0.5)], { lean, rate, noise: 0 }, { lead: 0, onSample: (s, g) => { if (tSettled === null && g.settled) tSettled = s.t; } });
      assert.ok(tSettled !== null && tSettled <= GESTURE_DEFAULTS.settleTime + 0.05, `${label}: settled at ${tSettled}`);
      // held cock-back: never settled while it is held (screen facing down and back)
      let heldSettled = 0, heldN = 0, back = null;
      const cock = run([{ kind: 'raise', hold: 1.0 }, { kind: 'lower', beta: PI / 2 - lean, duration: 0.8 }, idle(0.6)], { lean, rate, noise: 0 }, {
        onSample: (s, g) => {
          const t = s.t - LEAD;
          if (t > 0.7 && t < 1.5) {
            heldN++;
            if (g.settled) heldSettled++;
          }
          if (t > 2.4 && back === null && g.settled) back = t;
        },
      });
      assert.ok(heldN > 10 && heldSettled === 0, `${label}: settled ${heldSettled}/${heldN} samples while cocked back`);
      assert.ok(back !== null, `${label}: settled again back at guard (${describe(cock.events)})`);
      // a chop that stays low (blade below horizontal): not settled until back at guard
      let lowSettled = 0, lowN = 0;
      run([swing(-PI / 2, { arc: 2.2, recoil: 0, ret: 0 }), idle(1.0)], { lean, rate, noise: 0 }, {
        onSample: (s, g) => {
          if (s.t - LEAD > 0.8) {
            lowN++;
            if (g.settled) lowSettled++;
          }
        },
      });
      assert.ok(lowN > 10 && lowSettled === 0, `${label}: settled ${lowSettled}/${lowN} samples at the follow-through`);
    }
  }
});

test('after a throw nothing fires until the phone is back at guard (throwSettle), and then strikes work at once', () => {
  overMatrix((v) => {
    // the firm-flick release ends low; the way back up to the guard is fast (lookBack 0.4: ≈ 7 rad/s)
    const back = run([{ kind: 'throw', speed: 6, lookBack: 0.4 }, idle(0.6)], v.s, { kind: v.kind, weapon: 'spear' });
    if (describe(back.events) !== 'windup throw') return `throw, quick return → ${describe(back.events)}`;
    // a release that stays low past postThrowLock: the swing back up from there is still the way back
    const release = swing(-PI / 2, { speed: 6, arc: 3.0, windup: 0, recoil: 0, ret: 0 });
    const late = run([{ kind: 'raise' }, release, idle(1.0), swing(PI / 2, { speed: 6, arc: 1.5, windup: 0 }), idle(0.6)], v.s, { kind: v.kind, weapon: 'spear' });
    if (describe(late.events) !== 'windup throw') return `throw, low for 1 s, swing up → ${describe(late.events)}`;
    // back at guard: the next strike counts
    const next = run([{ kind: 'throw', speed: 6 }, idle(0.4), swing(0), idle(0.4)], v.s, { kind: v.kind, weapon: 'spear' });
    return describe(next.events) === 'windup throw slash:R' ? null : `throw, guard, R → ${describe(next.events)}`;
  }, LIGHT);
});

test('a held-up swing from the guard marks its calibration candidate heldUp; cuts do not', () => {
  for (const rate of [50, 100, 200]) {
    const up = run([swing(PI / 2, { speed: 6, arc: 1.0, windup: 0 }), idle(0.5)], { rate });
    assert.equal(describe(up.events), '(none)', `${rate} Hz: up from the guard`);
    assert.ok(up.candidates.length >= 1 && up.candidates.some((c) => c.heldUp === true), `${rate} Hz: ${JSON.stringify(up.candidates)}`);
    const cut = run([lower(0.1), idle(0.3), swing(PI / 2, { speed: 6 }), idle(0.4), swing(0, { speed: 6 }), idle(0.4)], { rate });
    assert.equal(describe(cut.events), 'slash:U slash:R', `${rate} Hz`);
    assert.ok(cut.candidates.length >= 2 && cut.candidates.every((c) => !('heldUp' in c)), `${rate} Hz: ${JSON.stringify(cut.candidates)}`);
  }
});

test('setGrip("window") then setGrip("hilt") resets cleanly: no event, no look jump', () => {
  // in the hilt grip, between strikes (as main.js's applyControls on a mode change)
  const raw = synthesize([idle(LEAD), swing(0, { speed: 9 }), idle(0.6), idle(0.9), swing(PI, { speed: 9 }), idle(0.6)], { hold: 'hilt', rate: 100, lean: 0.2 });
  const frames = new MotionFrames({ platformKind: 'android', grip: 'hilt' });
  const g = new GestureRecognizer({ grip: 'hilt' });
  const ev = [];
  const tSwitch = LEAD + (1.875 * 0.3) / 3.6 + 0.06 + (1.875 * 1.4) / 9 + 0.6 + 0.4;
  let switched = false, before = null;
  for (const r of raw) {
    if (!switched && r.t > tSwitch) {
      before = describe(ev);
      assert.ok(Math.abs(g.debug.guardBeta - 1.37) < 0.03, `the guard was learned (${g.debug.guardBeta})`);
      g.setGrip('window');
      assert.equal(g.grip, 'window');
      assert.ok(Object.is(g.consumeLook(0.1).yaw, 0), 'no look jump into the window grip');
      g.setGrip('hilt');
      frames.setGrip('window');
      frames.setGrip('hilt');
      assert.equal(g.debug.guardBeta, HP.guardBeta, 'the guard is forgotten');
      switched = true;
    }
    const n = ev.length;
    for (const e of g.push(frames.process(r))) ev.push(e);
    if (switched && r.t < tSwitch + 0.9) assert.equal(ev.length, n, 'no event from the switch');
    assert.ok(Object.is(g.consumeLook(0.1).yaw, 0));
  }
  assert.equal(before, 'slash:R');
  assert.equal(describe(ev), 'slash:R slash:L', 'strikes work as before after the switch');
  // a window recognizer with a body turn still easing in: back from the hilt grip, nothing jumps
  const w = new GestureRecognizer();
  const wf = new MotionFrames({ platformKind: 'android' });
  let look = 0;
  for (const r of synthesize([idle(0.5), { kind: 'turn', yaw: 0.8, duration: 0.8 }], { rate: 100 })) {
    w.push(wf.process(r));
    look += w.consumeLook().yaw;
  }
  assert.ok(look > 0.1, `the turn is under way (${look})`);
  w.setGrip('hilt');
  assert.ok(Object.is(w.consumeLook(0.1).yaw, 0));
  w.setGrip('window');
  assert.ok(Object.is(w.consumeLook(0.1).yaw, 0), 'the unshown rest of the turn is dropped');
});

test('API: GRIPS, setGrip/grip, the hilt thresholds, debug.grip; GESTURE_DEFAULTS and the window grip unchanged', () => {
  assert.ok(Object.isFrozen(GRIPS) && Object.isFrozen(GRIPS.hilt) && Object.isFrozen(GRIPS.window) && Object.isFrozen(HP));
  assert.deepEqual(Object.keys(GRIPS), ['window', 'hilt']);
  assert.deepEqual(HP, { guardBeta: 1.1, upHoldStartBeta: 0.6, windupBeta: 2.35, dipSector: 0, throwPeak: 4.5, throwFull: 7.5, throwMin: 3.5, slashPowerSpan: 5.5, fastRatio: 1.6 });
  assert.deepEqual(GRIPS.window.params, {});
  assert.equal(GRIPS.hilt.yawFloor, 0.7);
  assert.equal(GRIPS.hilt.overSpeed, 14);
  assert.ok(Object.isFrozen(GESTURE_DEFAULTS));
  assert.equal(GESTURE_DEFAULTS.windupBeta, 1.6);
  assert.equal(GESTURE_DEFAULTS.guardBeta, -0.39);
  // the window recognizer is today's
  const w = new GestureRecognizer({ sensitivity: 1.3 });
  assert.equal(w.grip, 'window');
  assert.deepEqual(w.p, { ...GESTURE_DEFAULTS });
  assert.equal(w.debug.grip, 'window');
  // the hilt profile and constructor params on top of it
  const h = new GestureRecognizer({ grip: 'hilt', sensitivity: 1.3, params: { throwMin: 3 } });
  assert.equal(h.grip, 'hilt');
  assert.deepEqual(h.p, { ...GESTURE_DEFAULTS, ...HP, throwMin: 3 });
  assert.equal(h._throwFull, HP.throwFull / 1.3);
  assert.equal(h._slashSpan, HP.slashPowerSpan, 'full slash power at 8.3 rad/s at 1×');
  // setGrip: unknown → window; unchanged → no-op (no reset: a swing under way survives)
  h.setGrip('nonsense');
  assert.equal(h.grip, 'window');
  assert.deepEqual(h.p, { ...GESTURE_DEFAULTS, throwMin: 3 });
  assert.equal(h.sens, 1.3);
  assert.equal(h._throwFull, GESTURE_DEFAULTS.throwFull / 1.3, 'the sensitivity is re-applied');
  h.setGrip('hilt');
  assert.equal(h.debug.grip, 'hilt');
  assert.equal(h.debug.guardBeta, HP.guardBeta);
  assert.equal(h.guardLearned, false, 'the learned guard is forgotten');
  h._swActive = true;
  h.setGrip('hilt');
  assert.equal(h._swActive, true, 'setGrip with the same grip changes nothing');
  assert.equal(new GestureRecognizer({ grip: 'sideways' }).grip, 'window');
});
