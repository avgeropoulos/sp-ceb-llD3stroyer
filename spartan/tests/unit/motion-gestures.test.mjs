// GestureRecognizer (DESIGN.md §5.3) against the synthetic phone (§5.4), through MotionFrames (§5.2).
// The required matrix: rot {90, 270} × tilt {0, 0.5} × roll {0, 0.3} × rate {50, 100, 200}
// × gravity {android +1 with TYPE_GRAVITY, web +1, web −1 (no gravity sensor on the web)},
// plus runs without linear acceleration and/or without gravity.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { synthesize } from '../../src/main/assets/game/js/motion/synth.js';
import { MotionFrames } from '../../src/main/assets/game/js/motion/frames.js';
import { GestureRecognizer, GESTURE_DEFAULTS } from '../../src/main/assets/game/js/motion/gestures.js';

const TOKENS = ['R', 'UR', 'U', 'UL', 'L', 'DL', 'D', 'DR'];
/** §3.4: eight 45° sectors centred on their angles. */
const token = (dir) => TOKENS[((Math.round(dir / (Math.PI / 4)) % 8) + 8) % 8];
const DIRS = TOKENS.map((k, i) => [k, i <= 4 ? (i * Math.PI) / 4 : (i * Math.PI) / 4 - 2 * Math.PI]);
const LEAD = 1.2; // idle before each script: lets the web gravity sign settle (§5.2 b)

function variants() {
  const out = [];
  for (const rot of [90, 270]) {
    for (const tilt of [0, 0.5]) {
      for (const roll of [0, 0.3]) {
        for (const rate of [50, 100, 200]) {
          const tag = `rot${rot} tilt${tilt} roll${roll} ${rate}Hz`;
          out.push({ name: `android ${tag}`, kind: 'android', s: { rot, tilt, roll, rate } });
          out.push({ name: `web+1 ${tag}`, kind: 'web', s: { rot, tilt, roll, rate, includeGravity: false } });
          out.push({ name: `web-1 ${tag}`, kind: 'web', s: { rot, tilt, roll, rate, includeGravity: false, gravitySign: -1 } });
        }
      }
    }
  }
  for (const rate of [50, 100, 200]) {
    for (const tilt of [0, 0.5]) {
      const tag = `tilt${tilt} ${rate}Hz`;
      out.push({ name: `no-linear ${tag}`, kind: 'android', s: { rate, tilt, includeLinear: false } });
      out.push({ name: `no-gravity ${tag}`, kind: 'android', s: { rate, tilt, includeGravity: false } });
      out.push({ name: `no-linear-no-gravity ${tag}`, kind: 'android', s: { rate, tilt, includeLinear: false, includeGravity: false } });
      out.push({ name: `web-1 no-linear ${tag}`, kind: 'web', s: { rate, tilt, includeLinear: false, includeGravity: false, gravitySign: -1 } });
    }
  }
  return out;
}
const MATRIX = variants();

/** Synthesizes `script` (after an idle lead-in), runs frames + gestures, collects events and look. */
function run(script, synthOpts, { kind = 'android', weapon = 'sword', sensitivity = 1, filter = null, lead = LEAD, onSample = null } = {}) {
  const full = lead > 0 ? [{ kind: 'idle', duration: lead }, ...script] : script;
  let raw = synthesize(full, synthOpts);
  if (filter) raw = filter(raw);
  const frames = new MotionFrames({ platformKind: kind });
  const g = new GestureRecognizer({ sensitivity, weapon, gyroRange: synthOpts.gyroRange || 34.9 });
  const events = [];
  let look = 0;
  let maxLook = 0;
  for (const r of raw) {
    const s = frames.process(r);
    for (const e of g.push(s)) events.push({ ...e });
    look += g.consumeLook().yaw;
    maxLook = Math.max(maxLook, Math.abs(look));
    if (onSample) onSample(s, g, look);
  }
  return { events, look, maxLook, g, frames };
}

const describe = (events) => events.map((e) => (e.type === 'slash' ? `slash:${token(e.dir)}` : e.type)).join(' ') || '(none)';

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

test('each 8-way swing with wind-up, rebound and return gives exactly one slash with the right token', () => {
  overMatrix((v) => {
    for (const [tok, dir] of DIRS) {
      const { events } = run([swing(dir), idle(0.4)], v.s, { kind: v.kind });
      if (events.length !== 1 || events[0].type !== 'slash' || token(events[0].dir) !== tok) return `${tok} → ${describe(events)}`;
    }
    return null;
  });
});

test('R,L,R,L at about 2 Hz (arc 1.0, speed 9) gives 4 alternating slashes', () => {
  const sw = (dir) => swing(dir, { arc: 1.0, speed: 9, ret: 0 });
  overMatrix((v) => {
    const { events } = run([sw(0), sw(Math.PI), sw(0), sw(Math.PI), idle(0.3)], v.s, { kind: v.kind });
    const got = describe(events);
    if (got !== 'slash:R slash:L slash:R slash:L') return got;
    const span = events[3].t - events[0].t; // three intervals
    if (!(span > 0.9 && span < 2.1)) return `cadence ${(3 / span).toFixed(2)} Hz`;
    return null;
  });
});

test('a slow return after a strike is not a slash; a deliberate reverse slash is', () => {
  overMatrix((v) => {
    const a = run([swing(0, { speed: 10 }), idle(0.05), swing(Math.PI, { speed: 4 }), idle(0.3)], v.s, { kind: v.kind });
    if (describe(a.events) !== 'slash:R') return `R10,L4 → ${describe(a.events)}`;
    const b = run([swing(0, { speed: 10 }), idle(0.05), swing(Math.PI, { speed: 9 }), idle(0.3)], v.s, { kind: v.kind });
    if (describe(b.events) !== 'slash:R slash:L') return `R10,L9 → ${describe(b.events)}`;
    return null;
  });
});

test('throws at speed 9, 13, 18 give one windup and one throw, no slash, with power rising with speed', () => {
  overMatrix((v) => {
    const powers = [];
    for (const speed of [9, 13, 18]) {
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `speed ${speed} → ${describe(events)}`;
      powers.push(events[1].power);
    }
    if (!(powers[0] < powers[1] && powers[1] <= powers[2] && powers[0] > 0)) return `powers ${powers.map((p) => p.toFixed(2))}`;
    return null;
  });
});

test('a downward spear swing without a wind-up is a slash, never a throw', () => {
  overMatrix((v) => {
    const { events } = run([swing(-Math.PI / 2, { speed: 9 }), idle(0.4)], v.s, { kind: v.kind, weapon: 'spear' });
    return describe(events) === 'slash:D' ? null : describe(events);
  });
});

test('a thrust followed by a retraction of 0.25, 0.3 or 0.35 s gives exactly one thrust', () => {
  overMatrix((v) => {
    for (const retract of [0.25, 0.3, 0.35]) {
      const { events } = run([{ kind: 'thrust', retract }, idle(0.6)], v.s, { kind: v.kind });
      if (describe(events) !== 'thrust') return `retract ${retract} → ${describe(events)}`;
      if (!(events[0].power > 0.5)) return `retract ${retract} → power ${events[0].power}`;
    }
    return null;
  });
});

test('10 s of walking and 10 s of idling give nothing', () => {
  overMatrix((v) => {
    const w = run([{ kind: 'walk', duration: 10 }], v.s, { kind: v.kind });
    if (w.events.length) return `walk → ${describe(w.events)}`;
    const i = run([idle(10)], v.s, { kind: v.kind });
    if (i.events.length) return `idle → ${describe(i.events)}`;
    if (Math.abs(i.look) > 0.02) return `idle look ${i.look.toFixed(3)}`;
    return null;
  });
});

test('a 1.0 rad turn over 1.2 s is consumed as 1.0 ± 0.1 of look and no gesture', () => {
  overMatrix((v) => {
    const { events, look } = run([{ kind: 'turn', yaw: 1.0, duration: 1.2 }, idle(0.8)], v.s, { kind: v.kind });
    if (events.length) return describe(events);
    return Math.abs(look - 1) <= 0.1 ? null : `look ${look.toFixed(3)}`;
  });
});

test('a swing plus its return leaves the view where it was (|net| and |peak| look < 0.05)', () => {
  overMatrix((v) => {
    for (const [tok, dir] of DIRS) {
      const { look, maxLook } = run([swing(dir), idle(0.8)], v.s, { kind: v.kind });
      if (Math.abs(look) >= 0.05 || maxLook >= 0.05) return `${tok}: net ${look.toFixed(3)} peak ${maxLook.toFixed(3)}`;
    }
    return null;
  });
});

test('detection latency from motion onset is < 130 ms for a 9 rad/s swing', () => {
  // Onset: the stroke reaching 10% of its peak speed (minimum-jerk: τ = 0.0866).
  const speed = 9, arc = 1.6, windup = 0.3;
  const onset = LEAD + (1.875 * windup) / (0.4 * speed) + 0.06 + 0.0866 * ((1.875 * arc) / speed);
  let worst = 0;
  overMatrix((v) => {
    for (const [tok, dir] of DIRS) {
      // Uppercuts from a level guard are deliberately held to the swing's end (§5.3), so they're excluded.
      if (v.s.tilt > 0 && tok.startsWith('U')) continue;
      const { events } = run([swing(dir, { speed }), idle(0.3)], v.s, { kind: v.kind });
      const e = events.find((x) => x.type === 'slash');
      if (!e) return `${tok}: no slash`;
      const lat = e.t - onset;
      worst = Math.max(worst, lat);
      if (!(lat < 0.13)) return `${tok}: ${(lat * 1000).toFixed(0)} ms`;
    }
    return null;
  });
  assert.ok(worst > 0.05, `latency ${worst} is implausibly small`);
});

// ---- robustness beyond the required matrix (a lighter matrix keeps the run short) ----

const LIGHT = MATRIX.filter((v, i) => i % 3 === 0 || v.name.startsWith('web-1'));

for (const sens of [0.6, 1, 1.6]) {
  test(`sensitivity ${sens} keeps classification for proportionally scaled motions`, () => {
    overMatrix((v) => {
      for (const [tok, dir] of DIRS) {
        const { events } = run([swing(dir, { speed: 9 / sens, ret: 2.5 / sens }), idle(0.4)], v.s, { kind: v.kind, sensitivity: sens });
        if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
      }
      for (const speed of [9, 13, 18]) {
        const { events } = run([{ kind: 'throw', speed: speed / sens, windupSpeed: 6 / sens }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear', sensitivity: sens });
        if (describe(events) !== 'windup throw') return `throw ${speed} → ${describe(events)}`;
      }
      const { events } = run([{ kind: 'thrust', duration: 0.18 * sens, retract: 0.3 * sens }, idle(0.6)], v.s, { kind: v.kind, sensitivity: sens });
      return describe(events) === 'thrust' ? null : `thrust → ${describe(events)}`;
    }, LIGHT);
  });
}

test('left hand: 8-way swings and throws classify the same', () => {
  overMatrix((v) => {
    const s = { ...v.s, hand: 'left' };
    for (const [tok, dir] of DIRS) {
      const { events } = run([swing(dir), idle(0.4)], s, { kind: v.kind });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const speed of [9, 13, 18]) {
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
    for (const [tok, dir] of DIRS) {
      const { events } = run([swing(dir), idle(0.4)], s, { kind: v.kind, filter: drop });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    for (const speed of [9, 13, 18]) {
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

test('throw wind-ups from 3.5 to 12 rad/s and 2.0 to 3.0 rad never slash', () => {
  overMatrix((v) => {
    for (const windupSpeed of [3.5, 4.5, 6, 8, 10, 12]) {
      const { events } = run([{ kind: 'throw', speed: 13, windupSpeed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `windupSpeed ${windupSpeed} → ${describe(events)}`;
    }
    for (const windupArc of [2.0, 2.5, 3.0]) {
      const { events } = run([{ kind: 'throw', speed: 13, windupArc }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== 'windup throw') return `windupArc ${windupArc} → ${describe(events)}`;
    }
    return null;
  }, LIGHT);
});

test('raise then chop: an overhead slash with the sword, a throw with the spear', () => {
  overMatrix((v) => {
    const script = [{ kind: 'raise' }, swing(-Math.PI / 2, { arc: 2.6, windup: 0 }), idle(0.5)];
    const sword = run(script, v.s, { kind: v.kind });
    if (describe(sword.events) !== 'windup slash:D') return `sword → ${describe(sword.events)}`;
    const spear = run(script, v.s, { kind: v.kind, weapon: 'spear' });
    return describe(spear.events) === 'windup throw' ? null : `spear → ${describe(spear.events)}`;
  }, LIGHT);
});

test('an uppercut that ends overhead is a U slash (a cut rebounds), a raise held up is a wind-up', () => {
  overMatrix((v) => {
    for (const weapon of ['sword', 'spear']) {
      for (const arc of [2.0, 2.4]) {
        for (const speed of [7, 12]) {
          const { events } = run([swing(Math.PI / 2, { arc, speed, windup: 0 }), idle(0.8)], v.s, { kind: v.kind, weapon });
          if (describe(events) !== 'slash:U') return `${weapon} arc ${arc} speed ${speed} → ${describe(events)}`;
        }
      }
      // the same stroke stopped and held overhead is a raise
      const held = run([swing(Math.PI / 2, { arc: 2.4, speed: 9, windup: 0, recoil: 0, ret: 0 }), idle(0.6)], v.s, { kind: v.kind, weapon });
      if (describe(held.events) !== 'windup') return `${weapon} held overhead → ${describe(held.events)}`;
    }
    // Leonidas' Cleave: an uppercut that flows over the top into a chop
    const cleave = run([swing(Math.PI / 2, { arc: 2.2, windup: 0, ret: 0 }), swing(-Math.PI / 2, { arc: 2.2, windup: 0 }), idle(0.5)], v.s, { kind: v.kind });
    return describe(cleave.events) === 'slash:U slash:D' ? null : `cleave → ${describe(cleave.events)}`;
  }, LIGHT);
});

test('an uppercut from the normal guard fires while braking, before the stroke ends', () => {
  const lead = LEAD + 0.3;
  overMatrix((v) => {
    if (v.s.tilt > 0) return null; // from a level guard these end near windupBeta: held to the end (§5.3)
    for (const arc of [1.2, 1.6]) {
      for (const speed of [7, 9, 12]) {
        const { events } = run([idle(0.3), swing(Math.PI / 2, { arc, speed, windup: 0 }), idle(0.6)], v.s, { kind: v.kind });
        if (describe(events) !== 'slash:U') return `arc ${arc} speed ${speed} → ${describe(events)}`;
        const stroke = (1.875 * arc) / speed;
        const t = events[0].t - lead;
        if (!(t < 0.8 * stroke)) return `arc ${arc} speed ${speed}: fired ${(t * 1000).toFixed(0)} ms into a ${(stroke * 1000).toFixed(0)} ms stroke`;
      }
    }
    return null;
  }, LIGHT);
});

test('arm motions that come back to the guard never move the view (raise, chop over the top, return)', () => {
  // ∫(w·up)dt is path-dependent once the blade passes vertical; the heading must not be
  overMatrix((v) => {
    const { look, maxLook } = run([{ kind: 'raise' }, swing(-Math.PI / 2, { arc: 2.6, windup: 0 }), idle(1.0)], v.s, { kind: v.kind });
    if (Math.abs(look) >= 0.05 || maxLook >= 0.05) return `net ${look.toFixed(3)} peak ${maxLook.toFixed(3)}`;
    const up = run([swing(Math.PI / 2, { arc: 2.2, speed: 8 }), swing(Math.PI / 4, { arc: 2.0 }), idle(1.0)], v.s, { kind: v.kind });
    return Math.abs(up.look) < 0.05 && up.maxLook < 0.05 ? null : `high swings: net ${up.look.toFixed(3)} peak ${up.maxLook.toFixed(3)}`;
  }, LIGHT);
});

test('spear in hand: ordinary 8-way swings stay slashes', () => {
  overMatrix((v) => {
    for (const [tok, dir] of DIRS) {
      const { events } = run([swing(dir), idle(0.4)], v.s, { kind: v.kind, weapon: 'spear' });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
    }
    return null;
  }, LIGHT);
});

test('thrusts of other depths, speeds and dips count once (a deep, slow retraction does not re-fire)', () => {
  overMatrix((v) => {
    for (const dip of [0, 0.25]) {
      for (const distance of [0.25, 0.45]) {
        const { events } = run([{ kind: 'thrust', dip, distance }, idle(0.6)], v.s, { kind: v.kind });
        if (describe(events) !== 'thrust') return `dip ${dip} distance ${distance} → ${describe(events)}`;
      }
    }
    for (const [distance, duration, retract] of [[0.5, 0.12, 0.45], [0.5, 0.18, 0.45], [0.2, 0.3, 0.2], [0.35, 0.12, 0.3]]) {
      const { events } = run([{ kind: 'thrust', distance, duration, retract }, idle(0.6)], v.s, { kind: v.kind });
      if (describe(events) !== 'thrust') return `D ${distance} T ${duration} retract ${retract} → ${describe(events)}`;
    }
    return null;
  }, LIGHT);
});

test('a gap > 0.1 s resets cleanly: no event from the torn swing, the next one is normal', () => {
  overMatrix((v) => {
    const script = [swing(0), idle(0.6), swing(Math.PI), idle(0.4)];
    // cut out 0.25 s in the middle of the first stroke
    const strokeStart = LEAD + (1.875 * 0.3) / 3.6 + 0.06;
    const tear = (raw) => raw.filter((r) => r.t < strokeStart + 0.04 || r.t > strokeStart + 0.29);
    let gaps = 0;
    const { events, maxLook } = run(script, v.s, { kind: v.kind, filter: tear, onSample: (s) => (gaps += s.gap ? 1 : 0) });
    if (gaps !== 1) return `${gaps} gaps flagged`;
    if (describe(events) !== 'slash:L') return describe(events);
    return maxLook < 0.1 ? null : `look jumped ${maxLook.toFixed(3)}`;
  }, LIGHT);
});

test('a slow cock-back before a strike is no body turn: backswing, short hold, strike leaves the view where it was', () => {
  // the backswing (2.5 rad/s) is too slow to be a swing; its heading change must not be released as a turn
  overMatrix((v) => {
    for (const [tok, dir] of DIRS) {
      const back = swing(dir + Math.PI, { speed: 2.5, arc: 0.3, ret: 0, windup: 0, recoil: 0 });
      const { events, look } = run([back, idle(0.12), swing(dir, { speed: 9, windup: 0 }), idle(1.0)], v.s, { kind: v.kind });
      if (describe(events) !== `slash:${tok}`) return `${tok} → ${describe(events)}`;
      if (Math.abs(look) >= 0.05) return `${tok}: net look ${look.toFixed(3)}`;
    }
    // a return to guard that lands a little off (either side) is still absorbed
    for (const off of [-0.12, 0.12]) {
      const back = swing(Math.PI, { speed: 2.5, arc: 0.3, ret: 0, windup: 0, recoil: 0 });
      const strike = [back, idle(0.12), swing(0, { speed: 9, windup: 0 }), { kind: 'turn', yaw: off, duration: 0.25 }, idle(1.0)];
      const { look } = run(strike, v.s, { kind: v.kind });
      if (Math.abs(look) >= 0.05) return `return ${off} off: net look ${look.toFixed(3)}`;
    }
    return null;
  }, LIGHT);
});

test('repeated gentle slashes (speed 5–6, default wind-up) do not turn the view', () => {
  overMatrix((v) => {
    for (const speed of [5, 5.5, 6]) {
      for (const dir of [0, Math.PI, Math.PI / 4, (-3 * Math.PI) / 4]) {
        const script = [];
        for (let i = 0; i < 4; i++) script.push(idle(0.5), swing(dir, { speed }), idle(0.8));
        const { events, look } = run(script, v.s, { kind: v.kind });
        // 5 rad/s is right at swingPeak: it may or may not slash, but the view must stay put either way
        if (speed > 5 && describe(events) !== Array(4).fill(`slash:${token(dir)}`).join(' ')) return `speed ${speed} ${token(dir)}: ${describe(events)}`;
        if (Math.abs(look) >= 0.05) return `speed ${speed} ${token(dir)}: 4 slashes turned the view ${look.toFixed(3)}`;
      }
    }
    // a slower cock-back (1.5 rad/s, released as a turn at first) and a held one (0.5 s) are taken back too
    for (const [speed, hold] of [[1.5, 0.12], [2.5, 0.5]]) {
      const back = swing(Math.PI, { speed, arc: 0.3, ret: 0, windup: 0, recoil: 0 });
      const { look } = run([back, idle(hold), swing(0, { speed: 9, windup: 0 }), idle(1.0)], v.s, { kind: v.kind });
      if (Math.abs(look) >= 0.05) return `cock-back at ${speed} rad/s, hold ${hold} s: net look ${look.toFixed(3)}`;
    }
    return null;
  }, LIGHT);
});

test('a body turn just before a strike is kept, whichever way the strike goes', () => {
  overMatrix((v) => {
    for (const [yaw, dur] of [[0.4, 0.35], [-0.3, 1.0], [0.8, 0.6]]) {
      for (const pause of [0.1, 0.3]) {
        for (const dir of [0, Math.PI, Math.PI / 2]) {
          const { look } = run([{ kind: 'turn', yaw, duration: dur }, idle(pause), swing(dir), idle(1.2)], v.s, { kind: v.kind });
          if (Math.abs(look - yaw) > 0.06) return `turn ${yaw} in ${dur} s, ${pause} s, ${token(dir)}: look ${look.toFixed(3)}`;
        }
      }
    }
    // and a brisk short turn on its own is still released once the phone holds still
    const { look } = run([{ kind: 'turn', yaw: 0.4, duration: 0.35 }, idle(1.0)], v.s, { kind: v.kind });
    return Math.abs(look - 0.4) <= 0.05 ? null : `brisk 0.4 rad turn: look ${look.toFixed(3)}`;
  }, LIGHT);
});

test('a body turn after a strike is followed once the phone stays there', () => {
  // strike right and stay (no return): after swingSettleTime the view follows the new heading
  const { look } = run([swing(0, { ret: 0 }), idle(1.5)], { rate: 100, tilt: 0.5 });
  assert.ok(look < -0.8, `look ${look}`);
});

test('reset() clears recoil memory, the post-throw lock and look hold, without moving the view', () => {
  const s = { rate: 100, tilt: 0.25 };
  /** Feeds `first`, calls between(g, frames) once `when(g)` holds, then feeds `second` (if any). */
  const play = (first, second, { weapon = 'sword', when, between }) => {
    const frames = new MotionFrames({ platformKind: 'android' });
    const g = new GestureRecognizer({ weapon });
    const ev = [];
    let tCut = null;
    for (const r of first) {
      for (const e of g.push(frames.process(r))) ev.push({ ...e });
      if (tCut === null && ev.length && when(g)) {
        tCut = r.t;
        between(g);
        break;
      }
    }
    assert.notEqual(tCut, null, 'cut point reached');
    if (second) for (const r of second(tCut)) for (const e of g.push(frames.process(r))) ev.push({ ...e });
    return { ev: describe(ev), g };
  };
  const strikeIdle = (g) => g.debug.phase === 'idle';

  // recoil memory: L at 52% of the R's speed right after it is a return, unless reset() came between
  const strike = synthesize([idle(LEAD), swing(0, { speed: 12, ret: 0, recoil: 0 }), idle(0.2)], s);
  const back = (t0) => synthesize([swing(Math.PI, { speed: 6.2, windup: 0, ret: 0 }), idle(0.3)], { ...s, t0: t0 + 0.01 });
  assert.equal(play(strike, back, { when: strikeIdle, between: () => {} }).ev, 'slash:R', 'a 52% reverse is a return');
  assert.equal(play(strike, back, { when: strikeIdle, between: (g) => g.reset() }).ev, 'slash:R slash:L');

  // post-throw lock and look hold
  const thr = synthesize([idle(LEAD), { kind: 'throw', speed: 13 }, idle(0.2)], s);
  const next = (t0) => synthesize([swing(0, { windup: 0 }), idle(0.3)], { ...s, t0: t0 + 0.01 });
  const afterThrow = (g) => g.debug.phase === 'locked' && !g._swActive;
  const kept = play(thr, next, { weapon: 'spear', when: afterThrow, between: () => {} });
  assert.equal(kept.ev, 'windup throw', 'swings right after a throw are ignored');
  assert.equal(kept.g.debug.lookState, 'hold', 'look stays held after a throw until resumeLook()');
  let jump = null;
  const freed = play(thr, next, {
    weapon: 'spear',
    when: afterThrow,
    between: (g) => {
      g.consumeLook();
      g.reset();
      jump = g.consumeLook().yaw;
    },
  });
  assert.equal(jump, 0, 'reset() does not move the view');
  assert.equal(freed.ev, 'windup throw slash:R');
  assert.notEqual(freed.g.debug.lookState, 'hold');
});

test('after a throw, settled follows the phone (still and upright), not the post-throw lock', () => {
  for (const lookBack of [0.2, 0.4]) {
    for (const rate of [50, 100, 200]) {
      let tThrow = null, tSettled = null, tMoving = null;
      run([{ kind: 'throw', lookBack }, idle(1.5)], { rate, noise: 0 }, {
        weapon: 'spear',
        onSample: (s, g, look) => {
          if (tThrow === null && g.debug.lastEvent && g.debug.lastEvent.type === 'throw') tThrow = s.t;
          if (tThrow === null) return;
          if (Math.hypot(s.w[0], s.w[1], s.w[2]) > 0.3) tMoving = s.t; // the phone still moving back to guard
          if (tSettled === null && g.settled) tSettled = s.t;
        },
      });
      assert.ok(tThrow !== null && tSettled !== null, `lookBack ${lookBack} ${rate} Hz: throw and settle seen`);
      assert.ok(tSettled - tMoving <= GESTURE_DEFAULTS.settleTime + 0.05, `lookBack ${lookBack} ${rate} Hz: settled ${(tSettled - tMoving).toFixed(3)} s after the phone stopped`);
      if (lookBack === 0.2) assert.ok(tSettled < tThrow + GESTURE_DEFAULTS.postThrowLock, `settled ${(tSettled - tThrow).toFixed(2)} s after the throw, inside the lock`);
    }
  }
});

test('low sensitivity never makes throwing harder; a remembered wind-up plus a downward swing is a throw', () => {
  const g = new GestureRecognizer({ sensitivity: 0.6 });
  assert.equal(g._throwPeak, GESTURE_DEFAULTS.throwPeak);
  assert.equal(g._throwFull, GESTURE_DEFAULTS.throwFull);
  g.setSensitivity(1.6);
  assert.equal(g._throwPeak, GESTURE_DEFAULTS.throwPeak / 1.6);
  assert.equal(g._throwFull, GESTURE_DEFAULTS.throwFull / 1.6);
  overMatrix((v) => {
    // a hard slasher (calibrated to 0.6) throwing normally
    for (const speed of [8, 10, 13]) {
      const { events } = run([{ kind: 'throw', speed }, idle(0.5)], v.s, { kind: v.kind, weapon: 'spear', sensitivity: 0.6 });
      if (describe(events) !== 'windup throw') return `sens 0.6 throw ${speed} → ${describe(events)}`;
    }
    // a raise and a chop between swingPeak and throwPeak: a weak throw, not a chop
    const weak = run([{ kind: 'raise' }, swing(-Math.PI / 2, { arc: 2.6, windup: 0, speed: 6 }), idle(0.5)], v.s, { kind: v.kind, weapon: 'spear' });
    if (describe(weak.events) !== 'windup throw' || weak.events[1].power !== 0) return `raise + chop at 6 → ${describe(weak.events)}`;
    return null;
  }, LIGHT);
});

test('holdLook / resumeLook / recenter', () => {
  const raw = synthesize([idle(0.5), { kind: 'turn', yaw: 0.8, duration: 1.0 }, idle(0.6), { kind: 'turn', yaw: -0.5, duration: 0.8 }, idle(0.6)], { rate: 100 });
  const frames = new MotionFrames({ platformKind: 'android' });
  const g = new GestureRecognizer();
  let look = 0;
  let phase = 0;
  for (const r of raw) {
    g.push(frames.process(r));
    if (phase === 0 && r.t >= 0.4) {
      look += g.consumeLook().yaw;
      g.holdLook();
      phase = 1;
    } else if (phase === 1 && r.t >= 2.2) {
      g.resumeLook();
      phase = 2;
      assert.equal(g.consumeLook().yaw, 0, 'resume does not jump');
    }
    const y = g.consumeLook().yaw;
    if (phase === 1) assert.equal(y, 0, 'held');
    look += y;
  }
  assert.ok(Math.abs(look + 0.5) < 0.06, `only the second turn is looked: ${look}`);

  const g2 = new GestureRecognizer();
  const f2 = new MotionFrames({ platformKind: 'android' });
  let look2 = 0;
  let maxStep = 0;
  for (const r of synthesize([idle(0.4), { kind: 'turn', yaw: 0.6, duration: 2.0 }, idle(0.5)], { rate: 100 })) {
    g2.push(f2.process(r));
    if (r.t > 1.4 && r.t < 1.405) g2.recenter();
    const y = g2.consumeLook().yaw;
    maxStep = Math.max(maxStep, Math.abs(y));
    look2 += y;
  }
  assert.ok(maxStep < 0.02, `recenter never jumps: ${maxStep}`);
  assert.ok(look2 > 0.5 && look2 < 0.7, `slow turns are followed 1:1: ${look2}`);
});

test('settled requires calm, no swing and an upright screen', () => {
  const frames = new MotionFrames({ platformKind: 'android' });
  const g = new GestureRecognizer();
  const seen = [];
  for (const r of synthesize([idle(0.5), swing(0), idle(0.8)], { rate: 100 })) {
    g.push(frames.process(r));
    seen.push([r.t, g.settled]);
  }
  assert.equal(seen.find(([t]) => t > 0.4)[1], true);
  assert.ok(seen.some(([t, s]) => t > 0.8 && t < 1.2 && !s), 'not settled mid-swing');
  assert.equal(seen[seen.length - 1][1], true);

  const upside = new GestureRecognizer();
  const sample = { t: 0, dt: 0.01, w: [0, 0, 0], a: [0, 0, 0], up: [0, -1, 0], calm: true, gap: false };
  for (let i = 0; i < 50; i++) {
    sample.t = i * 0.01;
    upside.push(sample);
  }
  assert.equal(upside.settled, false, 'screen upside down is not settled');
});

test('API: sensitivity clamps and scales, push returns a reused array, debug is populated', () => {
  const g = new GestureRecognizer({ sensitivity: 9 });
  assert.equal(g.sens, 2);
  g.setSensitivity(0.1);
  assert.equal(g.sens, 0.5);
  assert.equal(g._start, GESTURE_DEFAULTS.swingStart / 0.5);
  assert.equal(g._sweep, GESTURE_DEFAULTS.swingSweep / Math.sqrt(0.5));
  g.setWeapon('spear');
  assert.equal(g.weapon, 'spear');
  g.setWeapon('nonsense');
  assert.equal(g.weapon, 'sword');
  const sample = { t: 0, dt: 0.01, w: [0, 0, 0], a: [0, 0, 0], up: [0, 1, 0], calm: true, gap: false };
  const a1 = g.push(sample);
  sample.t = 0.01;
  const a2 = g.push(sample);
  assert.equal(a1, a2);
  const d = g.debug;
  for (const k of ['m', 'yawRate', 'pitchRate', 'beta', 'phase', 'peak', 'fwdAcc', 'fwdVel', 'lookState', 'lastEvent']) assert.ok(k in d, k);
  assert.ok(Object.isFrozen(GESTURE_DEFAULTS));
});

test('slash power respects the gyro range ceiling', () => {
  // a narrow-range gyro clips at 8.7 rad/s; a strike there must still reach full power
  const { events } = run([swing(0, { speed: 14 }), idle(0.4)], { rate: 200, tilt: 0.5, gyroRange: 8.7 }, {});
  assert.equal(describe(events), 'slash:R');
  assert.ok(events[0].power > 0.9, `power ${events[0].power}`);
});
