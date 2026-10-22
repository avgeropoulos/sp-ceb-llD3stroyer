// Regression tests for the combat fixer's review fixes (game/combat.js, game/player.js,
// game/combos.js): the Hoplite's Answer only finishes weakened foes, heavy enemies shrug off a
// bash, volleys are blocked only when facing them, the chain caps at 3 and lapses, Twin/Fury/Storm
// need flowing swings (with a wider motion window), dropSpears/refillSpears(total), and the
// noThrowLock flag. Runs the real player + combat on the headless gameplay harness.
// Run: node --test spartan/tests/unit/combat-fixes.test.mjs
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
const { ENEMIES } = await import('../../src/main/assets/game/js/game/rules.js');
const { COMBOS, COMBO_WINDOWS, ComboTracker } = await import('../../src/main/assets/game/js/game/combos.js');
const { createHarness, createHeadlessCtx } = await import('../sandbox/gameplay-harness.js');

const ctx = createHeadlessCtx({ EventBus, createRng });
const h = createHarness(ctx);
ctx.loop(() => h.tick());

const PI = Math.PI;
const front = (a, d) => [-Math.sin(a) * d, -Math.cos(a) * d];
const spawnAt = (kind, a, d, o = {}) => {
  const [x, z] = front(a, d);
  return h.spawn(kind, x, z, { blockChance: 0, hp: 1000, ...o });
};
const P = () => h.player;

// Bash, then thrust 0.3 s later (inside even a heavy foe's 0.5 s stagger).
function answer(power = 0.5) {
  h.action({ type: 'bash', power });
  h.stepTime(0.3);
  h.action({ type: 'thrust', power });
  h.stepTime(0.4);
  return h.events('combo').map((e) => e.payload.id);
}

// ---- 1. Hoplite's Answer and heavy bash stagger ------------------------------------------------

test("combat: the Hoplite's Answer only finishes a weakened (≤ 35% hp) foe; otherwise ×2", () => {
  h.reset();
  const full = spawnAt('levy', 0, 1.3, { hp: 400 });
  assert.deepEqual(answer(), ['answer']);
  const hits = full.hitsTaken;
  assert.equal(hits.length, 2);
  const bash = hits[0].damage;
  const thrust = 40 * (0.65 + 0.7 * 0.5) * 1.3; // computeDamage(thrust 40, power 0.5, ×1.3)
  assert.ok(Math.abs(hits[1].damage - 2 * thrust) < 1e-9, `×2 on a full-hp foe (${hits[1].damage})`);
  assert.equal(hits[1].ignoreGuard, true, 'the answer still ignores the guard');
  assert.ok(full.alive && Math.abs(full.hp - (400 - bash - 2 * thrust)) < 1e-9, `not executed at full hp (${full.hp})`);

  // At exactly 35% after the bash: executed.
  h.reset();
  const weak = spawnAt('levy', 0, 1.3, { hp: 400 });
  weak.hp = 140 + 8; // the bash (power 0.5) deals 8
  answer();
  assert.ok(!weak.alive, 'a foe at 35% hp is finished');
  assert.ok(weak.hitsTaken[1].damage >= 140 - 1e-9);

  // Not staggered: no execute, no ×2.
  h.reset();
  const calm = spawnAt('levy', 0, 1.3, { hp: 400 });
  calm.hp = 100;
  h.action({ type: 'bash', power: 0.5 });
  h.stepTime(0.3);
  calm.state = 'engage';
  h.action({ type: 'thrust', power: 0.5 });
  h.stepTime(0.4);
  assert.ok(calm.alive && Math.abs(calm.hitsTaken[1].damage - thrust) < 1e-9, 'unstaggered: plain combo damage');
});

test('combat: a heavy foe (the captain) takes a 0.5 s bash stagger and is never knocked down by it', () => {
  h.reset();
  const cap = spawnAt('captain', 0, 1.3, { hp: 180 });
  cap.stats = ENEMIES.captain;
  h.action({ type: 'bash', power: 0.95 });
  h.stepTime(0.3);
  const b = cap.hitsTaken[0];
  assert.equal(b.stagger, 0.5);
  assert.equal(b.knockdown, false, 'a hard bash does not floor the captain');
  assert.equal(cap.state, 'stagger');
  h.stepTime(0.35);
  assert.notEqual(cap.state, 'stagger', 'recovered after 0.5 s');

  // An ordinary foe keeps the 1.1 s stagger and the hard-bash knockdown.
  h.reset();
  const lv = spawnAt('levy', 0, 1.3);
  h.action({ type: 'bash', power: 0.95 });
  h.stepTime(0.3);
  assert.equal(lv.hitsTaken[0].stagger, 1.1);
  assert.equal(lv.hitsTaken[0].knockdown, true);

  // stats.bashStagger overrides; a heavy flag on the actor works too; Shield Wall is capped as well.
  h.reset();
  const odd = spawnAt('levy', 0, 1.3);
  odd.stats = { bashStagger: 0.7 };
  const heavy = spawnAt('levy', 0.6, 1.4);
  heavy.heavy = true;
  h.action({ type: 'bash', power: 0.5 });
  h.stepTime(0.95);
  h.action({ type: 'bash', power: 0.5 });
  h.stepTime(0.3);
  assert.ok(h.events('combo').some((e) => e.payload.id === 'wall'), 'wall');
  assert.equal(odd.hitsTaken[0].stagger, 0.7);
  assert.equal(odd.hitsTaken.at(-1).stagger, 0.7, 'wall on stats.bashStagger');
  assert.equal(heavy.hitsTaken.at(-1).stagger, 0.5, 'wall on a heavy foe');
});

test("combat: a full-hp captain needs two Answers (the second finishes him)", () => {
  h.reset();
  const cap = spawnAt('captain', 0, 1.3, { hp: 180 });
  cap.stats = ENEMIES.captain;
  answer(0.6);
  assert.ok(cap.alive && cap.hp > 0.3 * 180, `alive after one (${cap.hp.toFixed(1)})`);
  h.stepTime(2.6); // past rebash immunity
  answer(0.6);
  assert.ok(!cap.alive, 'second answer at ≤ 35% executes');
});

// ---- 3. Volleys: the shield only takes arrows from the way the player faces ---------------------

function volleyOn({ yaw, shield, fromBehind }) {
  h.reset();
  P().yaw = yaw;
  h.setShield(shield);
  h.stepTime(0.3);
  h.combat.volley({ count: 300, duration: 1.0, warn: 0.5, fromBehind });
  h.stepTime(4.5);
  return {
    shield: h.events('arrowHit').filter((e) => e.payload.target === 'shield').length,
    hurt: h.events('playerHurt').map((e) => e.payload.relYaw),
  };
}

test('combat: a frontal volley is blocked only facing the front, a rear volley only facing the rear', () => {
  let r = volleyOn({ yaw: 0, shield: true, fromBehind: false });
  assert.deepEqual([r.shield, r.hurt.length], [3, 0], 'front, facing front');
  r = volleyOn({ yaw: 1.2, shield: true, fromBehind: false });
  assert.deepEqual([r.shield, r.hurt.length], [3, 0], 'front, turned 1.2 rad (inside 1.31)');
  r = volleyOn({ yaw: PI, shield: true, fromBehind: false });
  assert.deepEqual([r.shield, r.hurt.length], [0, 3], 'front, facing the rear: hurt');
  assert.ok(r.hurt.every((y) => Math.abs(y) > 3), 'relYaw: from behind the view');
  r = volleyOn({ yaw: 1.45, shield: true, fromBehind: false });
  assert.deepEqual([r.shield, r.hurt.length], [0, 3], 'front, turned 1.45 rad: hurt');
  r = volleyOn({ yaw: PI, shield: true, fromBehind: true });
  assert.deepEqual([r.shield, r.hurt.length], [3, 0], 'rear, facing the rear: blocked');
  r = volleyOn({ yaw: -PI + 0.5, shield: true, fromBehind: true });
  assert.deepEqual([r.shield, r.hurt.length], [3, 0], 'rear, nearly facing the rear');
  r = volleyOn({ yaw: 0, shield: true, fromBehind: true });
  assert.deepEqual([r.shield, r.hurt.length], [0, 3], 'rear, facing the front: hurt');
  assert.ok(r.hurt.every((y) => Math.abs(y) > 3));
  r = volleyOn({ yaw: PI, shield: false, fromBehind: true });
  assert.deepEqual([r.shield, r.hurt.length], [0, 3], 'rear, facing it without the shield: hurt');
  assert.ok(r.hurt.every((y) => Math.abs(y) < 1e-9), 'relYaw 0: in front of the view');
  r = volleyOn({ yaw: 0.6, shield: false, fromBehind: false });
  assert.ok(r.hurt.length === 3 && r.hurt.every((y) => Math.abs(y + 0.6) < 1e-9), 'relYaw is relative to the view');
});

// ---- 4. Chain: cap 3, lapses after 4 s without a hit, resets on waveCleared ---------------------

test('player: the chain caps at 3.0, lapses after 4 s without a hit and resets on waveCleared', () => {
  h.reset();
  const hit = () => h.ctx.events.emit('hit', { byPlayer: true, kind: 'slash' });
  for (let i = 0; i < 40; i++) hit();
  assert.equal(P().chain, 3);
  h.stepTime(3.9);
  assert.equal(P().chain, 3, 'still up just before 4 s');
  hit(); // a hit restarts the clock
  h.stepTime(3.9);
  assert.equal(P().chain, 3);
  const n = h.events('scoreChanged').length;
  h.stepTime(0.25);
  assert.equal(P().chain, 1, 'lapsed after 4 s without a hit');
  assert.equal(h.events('scoreChanged').length, n + 1);
  assert.equal(h.events('scoreChanged').at(-1).payload.chain, 1);
  h.stepTime(1);
  assert.equal(h.events('scoreChanged').length, n + 1, 'reported once');

  for (let i = 0; i < 5; i++) hit();
  assert.equal(P().chain, 1.5);
  h.ctx.events.emit('waveCleared', { wave: 1 });
  assert.equal(P().chain, 1, 'waveCleared resets');
  // A kill scores with the chain at that moment.
  for (let i = 0; i < 4; i++) hit();
  h.ctx.events.emit('kill', { byPlayer: true, spear: false, cause: 'weapon' });
  assert.equal(P().score, 140);
});

// ---- 4/5. Twin/Fury/Storm need flowing swings; motion has a 1.7 s window -------------------------

test('combos: COMBO_WINDOWS and the flow rows', () => {
  assert.deepEqual({ ...COMBO_WINDOWS.touch }, { chain: 1.25, flow: 0.6 });
  assert.deepEqual({ ...COMBO_WINDOWS.motion }, { chain: 1.7, flow: 1.7 });
  const flow = new Set(COMBOS.filter((r) => r.flow).map((r) => r.id));
  assert.deepEqual([...flow].sort(), ['fury', 'storm', 'twin']);
});

test('combos: with the touch windows, slow alternation is no combo; other rows keep the 1.25 s window', () => {
  const ids = (c, toks, gaps, t0 = 10) => {
    let t = t0;
    return toks.map((tok, i) => {
      if (i) t += gaps[i - 1];
      const r = c.push(tok, t);
      return r ? r.id : null;
    });
  };
  const touch = () => {
    const c = new ComboTracker();
    c.setWindows(COMBO_WINDOWS.touch);
    return c;
  };
  assert.deepEqual(ids(touch(), ['R', 'L', 'R', 'L'], [0.5, 0.5, 0.5]), [null, 'twin', 'fury', 'storm']);
  assert.deepEqual(ids(touch(), ['R', 'L'], [0.6]), [null, 'twin'], 'exactly 0.6 s');
  assert.deepEqual(ids(touch(), ['R', 'L', 'R', 'L'], [0.9, 0.9, 0.9]), [null, null, null, null], '0.9 s apart: no bonus');
  // A slow gap restarts the flow, not the chain: R .9 L .3 R .3 L → twin on the fast pair, then fury.
  assert.deepEqual(ids(touch(), ['R', 'L', 'R', 'L'], [0.9, 0.3, 0.3]), [null, null, 'twin', 'fury']);
  // Non-flow rows still use the chain window.
  assert.deepEqual(ids(touch(), ['U', 'D'], [1.2]), [null, 'cleave']);
  assert.deepEqual(ids(touch(), ['DR', 'DL'], [1.0]), [null, 'xcut']);
  assert.deepEqual(ids(touch(), ['B', 'T'], [1.0]), [null, 'answer']);
  assert.deepEqual(ids(touch(), ['U', 'D'], [1.3]), [null, null], 'beyond 1.25 s');
  // Motion: a strike with its return (~1.5 s) chains and flows.
  const m = new ComboTracker();
  m.setWindows(COMBO_WINDOWS.motion);
  assert.deepEqual(ids(m, ['R', 'L', 'R', 'L'], [1.5, 1.48, 1.6]), [null, 'twin', 'fury', 'storm']);
  const m2 = new ComboTracker();
  m2.setWindows(COMBO_WINDOWS.motion);
  assert.deepEqual(ids(m2, ['U', 'D'], [1.5]), [null, 'cleave']);
  assert.deepEqual(ids(m2, ['R', 'L'], [1.8]), [null, null], 'beyond 1.7 s');
  // Defaults are unchanged (no flow limit) and setWindows keeps omitted fields.
  const d = new ComboTracker();
  assert.equal(d.window, 1.25);
  assert.equal(d.flow, Infinity);
  d.setWindows({ flow: 0.6 });
  assert.equal(d.window, 1.25);
  assert.equal(d.flow, 0.6);
});

function slashes(dirs, gap) {
  for (const dir of dirs) {
    h.action({ type: 'slash', dir, power: 0.5 });
    h.stepTime(gap);
  }
  return h.events('combo').map((e) => e.payload.id);
}

test('player: touch Twin Fangs needs ≤ 0.6 s between swings; the bonus follows', () => {
  h.reset();
  spawnAt('levy', 0, 1.6);
  assert.deepEqual(slashes([0, PI, 0], 0.5), ['twin', 'fury']);
  assert.equal(P().score, 150 + 180);
  h.reset();
  spawnAt('levy', 0, 1.6);
  assert.deepEqual(slashes([0, PI, 0, PI], 0.9), [], 'slow alternation: no combo, no bonus');
  assert.equal(P().score, 0);
});

test('player: in motion mode R, L, R with a return to guard (1.5 s apart) is Twin then Fury', () => {
  h.reset();
  h.ctx.input.motion = true;
  spawnAt('levy', 0, 1.6);
  assert.deepEqual(slashes([0, PI, 0], 1.5), ['twin', 'fury']);
  h.reset();
  h.ctx.input.motion = true;
  spawnAt('levy', 0, 1.6);
  assert.deepEqual(slashes([PI / 2, -PI / 2], 1.5), ['cleave'], 'U then D 1.5 s apart');
  h.reset();
  h.ctx.input.motion = false;
  spawnAt('levy', 0, 1.6);
  assert.deepEqual(slashes([0, PI, 0], 1.5), [], 'touch: 1.5 s is too slow');
});

// ---- 2. dropSpears / refillSpears(total) ---------------------------------------------------------

test('player: dropSpears({ keepInHand: true }) leaves only the spear in hand', () => {
  h.reset();
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.7);
  assert.equal(P().spearsLeft, 4);
  P().dropSpears({ keepInHand: true });
  assert.deepEqual([P().spares, P().hasSpearInHand, P().weapon, P().spearsLeft], [0, true, 'spear', 1]);
  h.action({ type: 'throw', power: 0.5, src: 'touch' });
  h.stepTime(1.2);
  assert.equal(P().weapon, 'sword', 'back to the sword after the last throw');
  assert.equal(P().spearsLeft, 0);
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.step(2);
  assert.equal(P().weapon, 'sword', 'a spearless player cannot take the spear');
});

test('player: dropSpears({ keepInHand: false }) goes to the sword with a weaponSwitch', () => {
  h.reset();
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.7);
  const n = h.events('weaponSwitch').length;
  P().dropSpears({ keepInHand: false });
  assert.deepEqual([P().spares, P().hasSpearInHand, P().weapon, P().spearsLeft], [0, false, 'sword', 0]);
  assert.equal(h.events('weaponSwitch').length, n + 1);
  assert.equal(h.events('weaponSwitch').at(-1).payload.weapon, 'sword');
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.action({ type: 'switchWeapon' });
  h.step(2);
  assert.equal(P().weapon, 'sword', 'switching to the spear is refused');
  // Already on the sword: no extra weaponSwitch.
  P().dropSpears({ keepInHand: false });
  assert.equal(h.events('weaponSwitch').length, n + 1);
  // A spear only on the back (sword drawn) is dropped too.
  h.reset();
  P().refillSpears();
  P().dropSpears({ keepInHand: false });
  assert.deepEqual([P().spearsLeft, P().weapon], [0, 'sword']);
});

test('player: dropSpears mid-throw cancels the throw; after a loosed throw the player is not stuck', () => {
  h.reset();
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.7);
  h.action({ type: 'throw', power: 0.5, src: 'touch' }); // touch release after 0.14 s
  h.step(2);
  assert.equal(P().state, 'throw');
  P().dropSpears({ keepInHand: false });
  h.stepTime(1);
  assert.equal(h.events('spearThrown').length, 0, 'the unreleased throw never flew');
  assert.deepEqual([P().state, P().weapon], ['idle', 'sword']);

  h.reset();
  P().refillSpears();
  h.action({ type: 'switchWeapon', weapon: 'spear' });
  h.stepTime(0.7);
  h.action({ type: 'throw', power: 0.5, src: 'touch' });
  h.stepTime(0.2); // loosed, attack still running
  assert.equal(h.events('spearThrown').length, 1);
  P().dropSpears({ keepInHand: false });
  h.stepTime(1.2);
  assert.deepEqual([P().state, P().weapon, P().spearsLeft], ['idle', 'sword', 0]);
  spawnAt('levy', 0, 1.6);
  h.action({ type: 'slash', dir: 0, power: 0.5 });
  h.stepTime(0.4);
  assert.equal(h.events('swing').at(-1).payload.kind, 'slash', 'can still attack');
});

test('player: refillSpears(total) tops up to total (one in hand) and never takes spears away', () => {
  h.reset();
  P().dropSpears({ keepInHand: false });
  P().refillSpears(2);
  assert.deepEqual([P().spares, P().hasSpearInHand, P().spearsLeft], [1, true, 2]);
  P().refillSpears(2);
  assert.equal(P().spearsLeft, 2, 'idempotent');
  P().refillSpears();
  assert.deepEqual([P().spares, P().spearsLeft], [3, 4], 'default is the full bundle');
  P().refillSpears(2);
  assert.equal(P().spearsLeft, 4, 'a smaller refill keeps what is there');
  P().refillSpears(9);
  assert.equal(P().spearsLeft, 4, 'capped at the bundle');
  h.reset(); // start: 2 spares, none in hand
  P().refillSpears(2);
  assert.deepEqual([P().spares, P().hasSpearInHand, P().spearsLeft], [1, true, 2], 'one moves into the hand');
  P().dropSpears({ keepInHand: false });
  P().refillSpears(0);
  assert.equal(P().spearsLeft, 0, 'refill to 0 is a no-op');
});

// ---- 6. noThrowLock ------------------------------------------------------------------------------

test('player: the throw never locks onto an actor flagged noThrowLock', () => {
  const throwAt = (flag) => {
    h.reset();
    const near = h.spawn('dummy', 0, -2.6, { hp: Infinity });
    const far = h.spawn('dummy', 1.5, -18, { hp: Infinity });
    near.noThrowLock = flag;
    P().refillSpears();
    h.action({ type: 'switchWeapon', weapon: 'spear' });
    h.stepTime(0.7);
    h.action({ type: 'throw', power: 0.6, src: 'touch' });
    h.stepUntil(() => h.events('spearImpact').length > 0, 300);
    const t = h.events('spearImpact')[0].payload.target;
    return t === near ? 'near' : t === far ? 'far' : 'none';
  };
  assert.equal(throwAt(false), 'near', 'without the flag the near dummy steals the lock');
  assert.equal(throwAt(true), 'far', 'with it the spear goes to the far target');
});
