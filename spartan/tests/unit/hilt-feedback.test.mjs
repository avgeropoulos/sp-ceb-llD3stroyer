// Feedback while swinging blind in the hilt grip (docs-hilt-grip.md §F, H.1 #12): gfx/fx.js's
// HAPTICS_HILT table and its dispatch (coalescing, the lock, armed-spear and attack-from-behind
// cues), the hilt slow motion (HILT_SLOWMO: the firm-swing rule, the gap, volleys, the spear cam),
// and audio.js's hilt lock tick and armed cue. The window grip and touch stay as they were.
// fx runs for real in Node (three through a resolve hook), on a fake time stepped like main.js;
// audio runs against the fake WebAudio of fake-webaudio.mjs.
// node --test spartan/tests/unit/hilt-feedback.test.mjs
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
const { LOCK } = await import('../../src/main/assets/game/js/game/lockon.js');
const { createFx, HAPTICS, HAPTICS_HILT, HILT_HAPTIC_GAP, HILT_SLOWMO, HILT_SLOWMO_POWER, SPEAR_CAM } =
  await import('../../src/main/assets/game/js/gfx/fx.js');
const { installFakes, settle } = await import('./fake-webaudio.mjs');
const { createAudio } = await import('../../src/main/assets/game/js/audio/audio.js');

const DT = 1 / 60;
const STRIKES = ['fleshHit', 'guarded', 'kill', 'hurt'];
const near = (a, b, eps, msg) => assert.ok(Math.abs(a - b) <= eps, `${msg}: ${a} vs ${b} (±${eps})`);
/** How long a slow-mo of `d` real seconds holds its scale: TimeControl eases out over min(0.12, 0.3·d). */
const holdOf = (d) => d - Math.min(0.12, 0.3 * d);

/** The "on" segments of a row: a pulse's ms, or a pattern's odd entries ([wait, on, off, on, …]). */
const onSegments = (row) => (row.length === 2 ? [row[0]] : row.filter((_, i) => i % 2 === 1));

// ---- the tables ------------------------------------------------------------------------------

test('HAPTICS_HILT is frozen, valid for the platform, and covers every HAPTICS key', () => {
  assert.ok(Object.isFrozen(HAPTICS_HILT));
  for (const key of Object.keys(HAPTICS)) assert.ok(key in HAPTICS_HILT, `HAPTICS.${key} has a hilt row`);
  for (const [key, row] of Object.entries(HAPTICS_HILT)) {
    assert.ok(Object.isFrozen(row), `${key} frozen`);
    assert.ok(Array.isArray(row) && row.length >= 2 && row.length <= 16, `${key}: 2 to 16 values`);
    for (const v of row) assert.ok(Number.isFinite(v) && v >= 0, `${key}: ${v} is a finite non-negative number`);
    if (row.length === 2) {
      // fx dispatches every 2-element row as a pulse [ms, strength]; platform.js drops ms ≤ 0.
      assert.ok(row[0] > 0 && row[0] <= 400, `${key}: pulse ms ${row[0]} in (0, 400]`);
      assert.ok(row[1] > 0 && row[1] <= 1, `${key}: pulse strength ${row[1]} in (0, 1]`);
    } else {
      for (const v of row) assert.ok(v <= 400, `${key}: pattern value ${v} ≤ 400 ms`);
      assert.ok(onSegments(row).every((ms) => ms > 0), `${key}: every "on" segment is felt`);
    }
  }
  // The window table keeps its own rule: every pulse it dispatches has ms > 0.
  for (const [key, row] of Object.entries(HAPTICS)) if (row.length === 2) assert.ok(row[0] > 0, `HAPTICS.${key}`);
});

test('the four strike results are strong (every "on" segment ≥ 30 ms) and have four different shapes', () => {
  for (const key of STRIKES) {
    for (const ms of onSegments(HAPTICS_HILT[key])) assert.ok(ms >= 30, `${key}: an "on" segment of ${ms} ms`);
  }
  const shape = (row) => onSegments(row).join('/');
  const shapes = STRIKES.map((k) => shape(HAPTICS_HILT[k]));
  assert.equal(new Set(shapes).size, 4, `shapes ${shapes.join(' | ')}`);
  // One knock, three short, knock + long, one long buzz (§F.1).
  assert.deepEqual(STRIKES.map((k) => onSegments(HAPTICS_HILT[k]).length), [1, 3, 2, 1]);
  assert.ok(HAPTICS_HILT.hurt[0] >= 3 * HAPTICS_HILT.fleshHit[0], 'the hurt buzz is long next to the hit knock');
});

test('HAPTICS_HILT holds the §F.1 rows and HILT_SLOWMO the §F.3 numbers', () => {
  const rows = Object.fromEntries(Object.entries(HAPTICS_HILT).map(([k, v]) => [k, [...v]]));
  assert.deepEqual(rows, {
    fleshHit: [45, 1.0], guarded: [0, 30, 35, 30, 35, 30], kill: [0, 45, 45, 110], hurt: [160, 1.0],
    block: [40, 0.8], perfect: [0, 30, 40, 30, 40, 90], shieldUp: [15, 0.4], arrowShield: [20, 0.6],
    spearArmed: [0, 35, 45, 35], spearRelease: [0, 30, 60, 30], spearImpact: [0, 40, 40, 90], spearGround: [20, 0.5],
    volleyWarning: [0, 40, 90, 40], lockTick: [30, 0.6], lockNone: [0, 30, 70, 30], rearThreat: [0, 80, 60, 80],
    rage: [0, 60, 50, 60, 50, 160], rageReady: [0, 35, 50, 35],
  });
  assert.equal(HILT_HAPTIC_GAP, 0.04);
  assert.ok(Object.isFrozen(HILT_SLOWMO));
  assert.deepEqual({ kill: [...HILT_SLOWMO.kill], heavy: [...HILT_SLOWMO.heavy], perfect: [...HILT_SLOWMO.perfect], minGap: HILT_SLOWMO.minGap },
    { kill: [0.4, 0.22], heavy: [0.3, 0.3], perfect: [0.5, 0.18], minGap: 2.5 });
  assert.equal(HILT_SLOWMO_POWER, 0.55);
});

// ---- fx on a fake time -----------------------------------------------------------------------

/** fx on a stub ctx; `grip` goes to ctx.input.grip (undefined: a context that never heard of grips). */
function rig({ grip, motion = grip === 'hilt' } = {}) {
  const hapt = [];
  const ctx = {
    THREE,
    scene: new THREE.Scene(),
    camera: new THREE.PerspectiveCamera(56, 2.2, 0.1, 2000),
    events: new EventBus(),
    time: { now: 0, gnow: 0, dt: 0, gdt: 0, pdt: 0, scale: 1, hitStop: 0, playerRealtime: false },
    settings: { blood: true },
    input: { motion, phoneSettled: true },
    mode: 'battle',
    actors: [],
    world: { groundHeight: () => 0, colliders: [] },
    player: { pos: new THREE.Vector3(0, 0, 0), yaw: 0, weapon: 'sword', hasSpearInHand: false },
    platform: {
      haptics: {
        pulse: (ms, s = 1) => hapt.push(['pulse', ms, s]),
        pattern: (a) => hapt.push(['pattern', ...a]),
      },
    },
  };
  if (grip !== undefined) ctx.input.grip = grip;
  ctx.camera.rotation.order = 'YXZ';
  const fx = createFx(ctx);
  const t = ctx.time;
  const ev = ctx.events;
  /** One main.js frame (§10 step 0), then fx.update. */
  const step = (n = 1) => {
    for (let i = 0; i < n; i++) {
      t.dt = DT;
      t.now += DT;
      if (t.hitStop > 0) t.hitStop = Math.max(0, t.hitStop - DT);
      t.gdt = t.hitStop > 0 ? 0 : DT * t.scale;
      t.pdt = t.hitStop > 0 ? 0 : t.playerRealtime ? DT : t.gdt;
      t.gnow += t.gdt;
      fx.update(DT, t.gdt);
    }
  };
  /** Real seconds of frames. */
  const wait = (s) => step(Math.ceil(s / DT));
  /** A foe at camera bearing `a` (0 ahead, + left) and `d` m. */
  const foe = (a = 0, d = 2, o = {}) => {
    const actor = { kind: 'levy', team: 'persian', alive: true, state: 'idle', facing: 0, pos: new THREE.Vector3(-Math.sin(a) * d, 0, -Math.cos(a) * d), ...o };
    ctx.actors.push(actor);
    return actor;
  };
  const hit = (target, o = {}) => ({
    target, attacker: 'player', damage: 34, kind: 'slash', dir: { x: 0, z: -1 }, point: new THREE.Vector3(target.pos.x, 1.3, target.pos.z),
    guarded: false, critical: false, killed: false, byPlayer: true, combo: null, ...o,
  });
  const swing = (power) => ev.emit('swing', { kind: 'slash', dir: 0, power, weapon: 'sword', src: 'motion', strikeIn: 0.1 });
  /** combat's order for a killing blow: hit (killed), then kill. */
  const kill = (target, o = {}, k = {}) => {
    ev.emit('hit', hit(target, { killed: true, ...o }));
    ev.emit('kill', { target, attacker: 'player', byPlayer: true, kind: 'slash', spear: false, cause: 'weapon', ...k });
  };
  const proj = (o = {}) => ({ kind: 'spear', pos: new THREE.Vector3(0, 1.7, 0), prevPos: new THREE.Vector3(0, 1.7, 0), vel: new THREE.Vector3(0, 3, -25),
    alive: true, stuck: false, byPlayer: true, src: 'motion', age: 0, hits: [], impactPoint: null, ...o });
  /** The haptics an action plays, with the log and fx cleared before it. */
  const take = (fn) => {
    hapt.length = 0;
    fn();
    return hapt.slice();
  };
  return { ctx, fx, t, ev, step, wait, foe, hit, swing, kill, proj, take, hapt };
}

const asPulse = (row) => ['pulse', row[0], row[1]];
const asPattern = (row) => ['pattern', ...row];
const played = (row) => (row.length === 2 ? asPulse(row) : asPattern(row));

test('window grip and contexts without a grip: the HAPTICS table, unchanged, and no hilt-only cue', () => {
  for (const grip of [undefined, 'window', null]) {
    const r = rig({ grip });
    const a = r.foe(0, 2);
    const behind = r.foe(Math.PI, 2);
    const each = (fn) => {
      const out = r.take(fn);
      r.fx.reset();
      r.wait(0.2);
      return out;
    };
    assert.deepEqual(each(() => r.ev.emit('hit', r.hit(a))), [asPulse(HAPTICS.fleshHit)], `${grip}: flesh`);
    assert.deepEqual(each(() => r.ev.emit('hit', r.hit(a, { guarded: true }))), [asPattern(HAPTICS.guarded)], `${grip}: guarded`);
    assert.deepEqual(each(() => r.kill(a)), [asPulse(HAPTICS.kill)], `${grip}: kill`);
    assert.deepEqual(each(() => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: true, kind: 'melee' })), [asPattern(HAPTICS.perfect)]);
    assert.deepEqual(each(() => r.ev.emit('playerHurt', { damage: 12, hp: 70, from: a, kind: 'melee', relYaw: 0 })), [asPulse(HAPTICS.hurt)]);
    assert.deepEqual(each(() => r.ev.emit('shield', { up: true })), [asPulse(HAPTICS.shieldUp)]);
    // A spear in the ground or the sea is felt only in the hilt grip.
    assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj(), target: null, point: new THREE.Vector3(0, 0, -20), water: false })),
      [asPattern(HAPTICS.spearImpact)]);
    assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj(), target: null, point: new THREE.Vector3(9, -3, -20), water: true })), []);
    // The window grip fires every event, however close together (no coalescing).
    assert.equal(each(() => {
      r.ev.emit('shield', { up: true });
      r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: false, kind: 'melee' });
    }).length, 2);
    r.ctx.player.weapon = 'spear';
    r.ctx.player.hasSpearInHand = true;
    assert.deepEqual(each(() => {
      r.ev.emit('lockOn', { target: a, prev: null, reason: 'acquire' });
      r.ev.emit('lockOn', { target: a, prev: a, reason: 'none' });
      r.ev.emit('enemyWindup', { actor: behind, timeToStrike: 0.8, targetIsPlayer: true });
      r.ev.emit('gesture', { type: 'windup', t: 0 });
    }), [], `${grip}: no hilt-only cue`);
  }
});

test('hilt grip: every event plays its HAPTICS_HILT row; a whiff plays nothing', () => {
  const r = rig({ grip: 'hilt' });
  const a = r.foe(0, 2);
  const each = (fn) => {
    const out = r.take(fn);
    r.fx.reset();
    r.wait(0.2);
    return out;
  };
  const H = HAPTICS_HILT;
  assert.deepEqual(each(() => r.ev.emit('hit', r.hit(a))), [played(H.fleshHit)]);
  assert.deepEqual(each(() => r.ev.emit('hit', r.hit(a, { guarded: true, damage: 5 }))), [played(H.guarded)]);
  assert.deepEqual(each(() => r.kill(a)), [played(H.kill)]);
  assert.deepEqual(each(() => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: false, kind: 'melee' })), [played(H.block)]);
  assert.deepEqual(each(() => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: false, kind: 'guardBreak' })), [played(H.block)]);
  assert.deepEqual(each(() => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: true, kind: 'melee' })), [played(H.perfect)]);
  assert.deepEqual(each(() => r.ev.emit('playerHurt', { damage: 12, hp: 70, from: a, kind: 'melee', relYaw: 0 })), [played(H.hurt)]);
  assert.deepEqual(each(() => r.ev.emit('shield', { up: true })), [played(H.shieldUp)]);
  assert.deepEqual(each(() => r.ev.emit('shield', { up: false })), []);
  assert.deepEqual(each(() => r.ev.emit('arrowHit', { target: 'shield', point: null })), [played(H.arrowShield)]);
  assert.deepEqual(each(() => r.ev.emit('spearThrown', { projectile: r.proj() })), [played(H.spearRelease)]);
  assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj(), target: a, point: new THREE.Vector3(0, 1.3, -2), water: false })), [played(H.spearImpact)]);
  assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj(), target: null, point: new THREE.Vector3(0, 0, -20), water: false })), [played(H.spearGround)]);
  assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj(), target: null, point: new THREE.Vector3(9, -3, -20), water: true })), [played(H.spearGround)]);
  assert.deepEqual(each(() => r.ev.emit('spearImpact', { projectile: r.proj({ byPlayer: false }), target: null, point: new THREE.Vector3(0, 0, -20), water: false })), []);
  assert.deepEqual(each(() => r.ev.emit('volley', { phase: 'warning', duration: 1 })), [played(H.volleyWarning)]);
  assert.deepEqual(each(() => r.ev.emit('volley', { phase: 'loosed', duration: 1 })), []);
  // A thrown spear's hit and kill are felt through spearImpact only; AI-vs-AI blows not at all.
  assert.deepEqual(each(() => r.kill(a, { kind: 'spear' }, { kind: 'spear', spear: true })), []);
  assert.deepEqual(each(() => r.ev.emit('hit', r.hit(a, { byPlayer: false, attacker: null }))), []);
  // A whiff emits only the swing: no haptic (the chip says MISS at guard).
  assert.deepEqual(each(() => r.swing(0.9)), []);
});

test('hilt grip: the lock tick, the "nothing there" double, and nothing for threat, death, lost or clear', () => {
  const r = rig({ grip: 'hilt' });
  const a = r.foe(0, 2);
  const b = r.foe(0.6, 3);
  const each = (fn) => {
    const out = r.take(fn);
    r.wait(0.1);
    return out;
  };
  const H = HAPTICS_HILT;
  assert.deepEqual(each(() => r.ev.emit('lockOn', { target: a, prev: null, reason: 'acquire' })), [played(H.lockTick)]);
  assert.deepEqual(each(() => r.ev.emit('lockOn', { target: b, prev: a, reason: 'switch' })), [played(H.lockTick)]);
  assert.deepEqual(each(() => r.ev.emit('lockOn', { target: b, prev: b, reason: 'none' })), [played(H.lockNone)]);
  assert.deepEqual(each(() => r.ev.emit('lockOn', { target: null, prev: null, reason: 'none' })), [played(H.lockNone)]);
  for (const reason of ['threat', 'death', 'lost', 'clear']) {
    const target = reason === 'threat' ? a : null;
    assert.deepEqual(each(() => r.ev.emit('lockOn', { target, prev: b, reason })), [], reason);
  }
});

test('hilt grip: a wind-up at the player from beyond threatAngle of the view plays the urgent double', () => {
  const r = rig({ grip: 'hilt' });
  const each = (fn) => {
    const out = r.take(fn);
    r.wait(0.1);
    return out;
  };
  const urgent = [played(HAPTICS_HILT.rearThreat)];
  const windup = (actor, targetIsPlayer = true) => r.ev.emit('enemyWindup', { actor, timeToStrike: 0.8, targetIsPlayer });
  for (const yaw of [0, 1.2, -2.5]) {
    r.ctx.player.yaw = yaw;
    assert.deepEqual(each(() => windup(r.foe(yaw, 2))), [], `ahead (view ${yaw})`);
    assert.deepEqual(each(() => windup(r.foe(yaw + LOCK.threatAngle - 0.05, 2))), [], 'inside the shield cover');
    assert.deepEqual(each(() => windup(r.foe(yaw + LOCK.threatAngle + 0.05, 2))), urgent, 'just beyond it, on the left');
    assert.deepEqual(each(() => windup(r.foe(yaw - LOCK.threatAngle - 0.05, 2))), urgent, 'just beyond it, on the right');
    assert.deepEqual(each(() => windup(r.foe(yaw + Math.PI, 2))), urgent, 'straight behind');
    assert.deepEqual(each(() => windup(r.foe(yaw + Math.PI, 2), false)), [], 'not at the player');
  }
  // It plays whether or not the lock turns: the lockOn 'threat' that may follow adds nothing.
  r.ctx.player.yaw = 0;
  const x = r.foe(Math.PI, 2);
  assert.deepEqual(each(() => {
    windup(x);
    r.ev.emit('lockOn', { target: x, prev: null, reason: 'threat' });
  }), urgent);
  assert.deepEqual(each(() => {
    r.ev.emit('lockOn', { target: x, prev: null, reason: 'threat' });
    windup(x);
  }), urgent);
});

test('hilt grip: the armed haptic on a wind-up with the spear in hand, and only then', () => {
  const r = rig({ grip: 'hilt' });
  const each = (fn) => {
    const out = r.take(fn);
    r.wait(0.1);
    return out;
  };
  const p = r.ctx.player;
  const windup = () => r.ev.emit('gesture', { type: 'windup', t: 0 });
  assert.deepEqual(each(windup), [], 'the sword');
  p.weapon = 'spear';
  p.hasSpearInHand = false;
  assert.deepEqual(each(windup), [], 'the spear arm empty (re-arming)');
  p.hasSpearInHand = true;
  assert.deepEqual(each(windup), [played(HAPTICS_HILT.spearArmed)]);
  assert.deepEqual(each(() => r.ev.emit('gesture', { type: 'slash', dir: 0, power: 0.8 })), [], 'other gestures');
  assert.deepEqual(each(() => r.ev.emit('gesture', { type: 'throw', power: 0.8 })), [], 'the throw itself (spearThrown plays the release)');
});

test('hilt coalescing: within 40 ms a new haptic is dropped, except kill, hurt, perfect and attack from behind', () => {
  const r = rig({ grip: 'hilt' });
  const a = r.foe(0, 2);
  const behind = r.foe(Math.PI, 2);
  const H = HAPTICS_HILT;
  const block = (perfect = false) => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect, kind: 'melee' });
  const settle = () => {
    r.fx.reset();
    r.wait(0.2);
  };
  // The same frame: the shield goes up and the blow lands on it.
  assert.deepEqual(r.take(() => {
    r.ev.emit('shield', { up: true });
    block();
  }), [played(H.shieldUp)], 'the block is dropped');
  settle();
  // A sweep strikes three men in one frame: one knock.
  const b = r.foe(0.4, 2);
  const c = r.foe(-0.4, 2);
  assert.deepEqual(r.take(() => {
    for (const t of [a, b, c]) r.ev.emit('hit', r.hit(t));
  }), [played(H.fleshHit)]);
  settle();
  // Two frames apart (33 ms) is still inside the gap; three (50 ms) is past it.
  assert.deepEqual(r.take(() => {
    r.ev.emit('hit', r.hit(a));
    r.step(2);
    r.ev.emit('hit', r.hit(b, { guarded: true }));
    r.step(1);
    r.ev.emit('hit', r.hit(c));
  }), [played(H.fleshHit), played(H.fleshHit)]);
  settle();
  // The urgent rows replace whatever plays.
  for (const [name, fire, row] of [
    ['kill', () => r.kill(b), H.kill],
    ['hurt', () => r.ev.emit('playerHurt', { damage: 12, hp: 70, from: a, kind: 'melee', relYaw: 0 }), H.hurt],
    ['perfect', () => block(true), H.perfect],
    ['attack from behind', () => r.ev.emit('enemyWindup', { actor: behind, timeToStrike: 0.8, targetIsPlayer: true }), H.rearThreat],
  ]) {
    assert.deepEqual(r.take(() => {
      r.ev.emit('shield', { up: true });
      fire();
    }), [played(H.shieldUp), played(row)], name);
    settle();
  }
  // ... and then hold the gap themselves; the same urgent row twice in it plays once.
  assert.deepEqual(r.take(() => {
    r.ev.emit('playerHurt', { damage: 12, hp: 70, from: a, kind: 'melee', relYaw: 0 });
    r.ev.emit('arrowHit', { target: 'shield', point: null });
    r.ev.emit('enemyWindup', { actor: behind, timeToStrike: 0.8, targetIsPlayer: true });
    r.ev.emit('enemyWindup', { actor: r.foe(Math.PI - 0.3, 2), timeToStrike: 0.8, targetIsPlayer: true });
  }), [played(H.hurt), played(H.rearThreat)]);
});

// ---- slow motion ------------------------------------------------------------------------------

test('hilt kill slow-mo: after a swing of power 0.5 none, after 0.6 it runs (lastSwingPower)', () => {
  const r = rig({ grip: 'hilt' });
  r.swing(0.5);
  assert.equal(r.fx.lastSwingPower, 0.5);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, 1, 'no slow-mo after a 0.5 swing');
  assert.equal(r.t.playerRealtime, false);
  r.wait(3);
  r.swing(0.6);
  assert.equal(r.fx.lastSwingPower, 0.6);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, HILT_SLOWMO.kill[0], 'kill slow-mo');
  assert.equal(r.t.playerRealtime, true, 'the hero keeps full speed');
  // It lasts its real duration (easing out over the last 0.12 s), through the kill's hit-stop.
  const t0 = r.t.now;
  let held = 0;
  while (r.t.now - t0 < 0.4) {
    r.step(1);
    if (r.t.scale === HILT_SLOWMO.kill[0]) held = r.t.now - t0;
  }
  near(held, holdOf(HILT_SLOWMO.kill[1]), DT + 1e-6, 'held at its scale');
  assert.equal(r.t.scale, 1);
  assert.equal(r.t.playerRealtime, false);
  // The power threshold is HILT_SLOWMO_POWER.
  r.wait(3);
  r.swing(HILT_SLOWMO_POWER);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, HILT_SLOWMO.kill[0], 'at the threshold');
});

test('hilt slow-mo gap: at most one per 2.5 real seconds, kills and perfect blocks alike', () => {
  const r = rig({ grip: 'hilt' });
  const a = r.foe(0, 2);
  const block = () => r.ev.emit('block', { by: 'player', attacker: a, point: null, perfect: true, kind: 'melee' });
  r.swing(0.9);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, HILT_SLOWMO.kill[0]);
  r.wait(1);
  assert.equal(r.t.scale, 1);
  block();
  assert.equal(r.t.scale, 1, 'a perfect block 1 s later: inside the gap');
  r.swing(0.9);
  r.kill(r.foe(0, 2), { critical: true });
  assert.equal(r.t.scale, 1, 'a crit kill inside the gap');
  r.wait(HILT_SLOWMO.minGap - 1 - 0.2);
  block();
  assert.equal(r.t.scale, 1, '0.2 s before the gap ends');
  r.wait(0.25);
  block();
  assert.equal(r.t.scale, HILT_SLOWMO.perfect[0], 'perfect block slow-mo once the gap is over');
  assert.equal(r.t.playerRealtime, true);
  const t0 = r.t.now;
  let held = 0;
  while (r.t.now - t0 < 0.4) {
    r.step(1);
    if (r.t.scale === HILT_SLOWMO.perfect[0]) held = r.t.now - t0;
  }
  near(held, holdOf(HILT_SLOWMO.perfect[1]), DT + 1e-6, 'perfect held at its scale');
});

test('hilt heavy slow-mo for a crit, a captain or Into the Gulf, whatever the swing power', () => {
  for (const [name, o, k, kind] of [
    ['crit', { critical: true }, {}, 'levy'],
    ['captain', {}, {}, 'captain'],
    ['Into the Gulf', { kind: 'bash' }, { kind: 'bash', cause: 'sea' }, 'levy'],
  ]) {
    const r = rig({ grip: 'hilt' });
    r.swing(0.3);
    r.kill(r.foe(0, 2, { kind }), o, k);
    assert.equal(r.t.scale, HILT_SLOWMO.heavy[0], name);
    assert.equal(r.t.playerRealtime, true, name);
  }
});

test('no hilt slow-mo for a spear kill, a bleed kill, an AI kill, during a volley impact, or outside the hilt grip', () => {
  const r = rig({ grip: 'hilt' });
  r.swing(0.9);
  r.kill(r.foe(0, 2, { kind: 'captain' }), { kind: 'spear', critical: true }, { kind: 'spear', spear: true });
  assert.equal(r.t.scale, 1, 'the spear (the spear cam has its own)');
  r.ev.emit('kill', { target: r.foe(0, 2), attacker: 'player', byPlayer: true, kind: 'bleed', spear: false, cause: 'bleed' });
  assert.equal(r.t.scale, 1, 'a bleed kill');
  r.ev.emit('hit', r.hit(r.foe(0, 2), { killed: true, byPlayer: false, attacker: null }));
  assert.equal(r.t.scale, 1, 'an AI kill');
  // A volley's impact phase: none from 'impact' to 'end' (two volleys may overlap).
  r.ev.emit('volley', { phase: 'impact', duration: 1.2 });
  r.wait(0.6);
  r.ev.emit('volley', { phase: 'impact', duration: 1.2 });
  r.swing(0.9);
  r.kill(r.foe(0, 2));
  r.ev.emit('block', { by: 'player', attacker: r.foe(0, 2), point: null, perfect: true, kind: 'melee' });
  assert.equal(r.t.scale, 1, 'during the impact phase');
  r.wait(0.7);
  r.ev.emit('volley', { phase: 'end', duration: 1.2 });
  r.swing(0.9);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, 1, 'the first volley ended, the second still lands');
  r.wait(0.6);
  r.ev.emit('volley', { phase: 'end', duration: 1.2 });
  r.swing(0.9);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, HILT_SLOWMO.kill[0], 'after the impact phase');
  // A battle reset before the 'end' (combat drops its volleys): the rule lapses after the
  // impact's game-time duration and half a second.
  const v = rig({ grip: 'hilt' });
  v.ev.emit('volley', { phase: 'impact', duration: 1 });
  v.wait(1.4);
  v.swing(0.9);
  v.kill(v.foe(0, 2));
  assert.equal(v.t.scale, 1, 'still inside duration + 0.5 s (the kill\'s hit-stop froze game time too)');
  v.wait(0.3);
  v.swing(0.9);
  v.kill(v.foe(0, 2));
  assert.equal(v.t.scale, HILT_SLOWMO.kill[0], 'lapsed');
  // The window grip and touch: no hilt slow-mo at all.
  for (const grip of ['window', undefined]) {
    const w = rig({ grip });
    w.swing(1);
    w.kill(w.foe(0, 2, { kind: 'captain' }), { critical: true });
    w.ev.emit('block', { by: 'player', attacker: w.foe(0, 2), point: null, perfect: true, kind: 'melee' });
    assert.equal(w.t.scale, 1, `${grip}`);
  }
});

test('hilt slow-mo merges with a combo slow-mo and never starts under the spear cam', () => {
  const r = rig({ grip: 'hilt' });
  r.ev.emit('combo', { id: 'storm', name: 'Storm of Bronze', mult: 2.4, slowMo: [0.5, 0.4], bonus: 240 });
  assert.equal(r.t.scale, 0.5);
  r.swing(0.9);
  r.kill(r.foe(0, 2));
  assert.equal(r.t.scale, HILT_SLOWMO.kill[0], 'the deeper scale wins');
  r.wait(0.3 - 0.01);
  assert.ok(r.t.scale < 1, 'the longer remainder (the combo\'s 0.4 s) wins');
  r.wait(0.2);
  assert.equal(r.t.scale, 1);
  // Under the spear cam the time scale is the cam's, and a kill doesn't use up the gap.
  const s = rig({ grip: 'hilt' });
  s.ev.emit('spearThrown', { projectile: s.proj() });
  assert.equal(s.fx.spearCamPhase, 'trail');
  assert.equal(s.t.scale, SPEAR_CAM.holdScale);
  s.swing(0.9);
  s.kill(s.foe(0, 2));
  assert.equal(s.t.scale, SPEAR_CAM.holdScale, 'the spear cam keeps its scale');
  s.fx.reset();
  s.step(1);
  s.swing(0.9);
  s.kill(s.foe(0, 2));
  assert.equal(s.t.scale, HILT_SLOWMO.kill[0], 'the refused slow-mo left the gap open');
});

// ---- audio ------------------------------------------------------------------------------------

let fakes = null;
/** audio.js on the fake WebAudio, every SFX rendered (the fake worker answers at once). */
async function audioRig(grip) {
  fakes = fakes || installFakes();
  const events = new EventBus();
  const ctx = {
    settings: { master: 0.9, music: 0.55, sfx: 1, voice: true }, events, mode: 'battle', quality: 'medium', time: { scale: 1 },
    camera: null, platform: { voice: { say() {}, stop() {} } },
    input: grip === undefined ? {} : { grip },
    player: { weapon: 'sword', hasSpearInHand: false, pos: { x: 0, y: 0, z: 0 } },
    fx: { cameraOverridden: false },
  };
  const audio = createAudio(ctx);
  for (let i = 0; i < 400 && audio.stats().pending > 0; i++) await settle(1);
  await settle();
  assert.ok(audio.getBuffer('uiTap') && audio.getBuffer('uiConfirm'), 'the cues are rendered');
  const ac = audio.context;
  /** The log entries an action adds (the fake clock moves on, so the 12 ms same-name rule never bites). */
  const take = (fn) => {
    const n = audio.log.length;
    fn();
    ac.advance(0.05);
    return audio.log.slice(n).map((e) => ({ name: e.name, volume: e.volume, pan: e.pan, at: e.at }));
  };
  return { audio, ctx, events, take };
}

test('audio, hilt grip: a centred uiTap when the lock moves (0.6, 0.8 for a threat), uiConfirm when the spear is armed', async () => {
  const r = await audioRig('hilt');
  const a = { pos: { x: 0, y: 0, z: -2 } };
  const lock = (reason, target = a) => r.take(() => r.events.emit('lockOn', { target, prev: null, reason }));
  const check = (got, name, vol, msg) => {
    assert.equal(got.length, 1, `${msg}: one cue`);
    assert.equal(got[0].name, name, msg);
    assert.equal(got[0].pan, 0, `${msg}: centred`);
    assert.equal(got[0].at, false, `${msg}: not placed in the world`);
    // playSfx's natural variation takes up to 12% off the volume.
    assert.ok(got[0].volume <= vol + 1e-9 && got[0].volume >= vol * 0.88 - 1e-9, `${msg}: volume ${got[0].volume} for ${vol}`);
  };
  check(lock('acquire'), 'uiTap', 0.6, 'acquire');
  check(lock('switch'), 'uiTap', 0.6, 'switch');
  check(lock('threat'), 'uiTap', 0.8, 'threat');
  for (const reason of ['death', 'lost', 'clear']) assert.deepEqual(lock(reason, null), [], reason);
  assert.deepEqual(lock('none'), [], 'a swipe that found nobody: the haptic says so');
  const windup = () => r.take(() => r.events.emit('gesture', { type: 'windup', t: 0 }));
  assert.deepEqual(windup(), [], 'a wind-up with the sword');
  r.ctx.player.weapon = 'spear';
  r.ctx.player.hasSpearInHand = true;
  check(windup(), 'uiConfirm', 0.5, 'spear armed');
  r.ctx.fx.cameraOverridden = true;
  assert.deepEqual(windup(), [], 'under the spear cam');
  r.ctx.fx.cameraOverridden = false;
  assert.deepEqual(r.take(() => r.events.emit('gesture', { type: 'slash', dir: 0, power: 0.8 })), [], 'other gestures');
  r.audio.dispose();
});

test('audio, window grip and touch: no lock tick and no armed cue', async () => {
  for (const grip of ['window', null, undefined]) {
    const r = await audioRig(grip);
    r.ctx.player.weapon = 'spear';
    r.ctx.player.hasSpearInHand = true;
    const got = r.take(() => {
      r.events.emit('lockOn', { target: { pos: { x: 0, y: 0, z: -2 } }, prev: null, reason: 'switch' });
      r.events.emit('lockOn', { target: { pos: { x: 0, y: 0, z: 2 } }, prev: null, reason: 'threat' });
      r.events.emit('gesture', { type: 'windup', t: 0 });
    });
    assert.deepEqual(got, [], `${grip}`);
    r.audio.dispose();
  }
});
