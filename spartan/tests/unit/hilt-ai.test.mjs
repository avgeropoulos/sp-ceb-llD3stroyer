// game/ai.js's hilt wind-up grace (docs-hilt-grip.md §C.6, H.1 #6b), run headless like
// balance-ai.test.mjs: a hilt player turns only through the lock-on, so a Persian's
// player-targeted wind-up from beyond LOCK.threatAngle of his view gets +0.35 s, judged at its
// start. Touch, the window grip, the trainer and runs without ctx.input.grip get none.
// node --test spartan/tests/unit/hilt-ai.test.mjs
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
const { createRng, angleDiff, yawTo } = await import('../../src/main/assets/game/js/core/math.js');
const { createAI } = await import('../../src/main/assets/game/js/game/ai.js');
const { ENEMIES } = await import('../../src/main/assets/game/js/game/rules.js');
const { LOCK } = await import('../../src/main/assets/game/js/game/lockon.js');

const DT = 1 / 30;
const GRACE = 0.35;
const IMMORTAL_D3 = ENEMIES.immortal.windup * 1.15 * 0.9; // 0.70 × 1.15 (motion) × 0.9 (Day 3)

/**
 * createAI with a stub player (never hurt) on `day`; input.grip as given (undefined: no key at
 * all, as in the headless balance runs). Records every player-targeted wind-up with the
 * attacker's bearing off the view at that moment.
 */
function world({ day = 3, control = 'motion', grip, yaw = 0, seed = 5 } = {}) {
  const events = new EventBus();
  const input = { shieldHeld: false, shieldPressedAt: -10 };
  if (grip !== undefined) input.grip = grip;
  const ctx = {
    events, rng: createRng(seed), scene: new THREE.Scene(), actors: [], mode: 'battle', quality: 'low',
    time: { gnow: 0 },
    settings: { control },
    waves: { day, dayWave: 0 },
    input,
    player: { pos: new THREE.Vector3(0, 0, 0), yaw, alive: true, hp: 100, maxHp: 100, shieldRaise: 0 },
    combat: { actorStrike: () => 'block' },
  };
  const ai = createAI(ctx);
  ctx.ai = ai;
  const windups = [];
  events.on('enemyWindup', (e) => {
    if (!e.targetIsPlayer) return;
    const p = ctx.player;
    const rel = angleDiff(p.yaw, yawTo(e.actor.pos.x - p.pos.x, e.actor.pos.z - p.pos.z));
    windups.push({ actor: e.actor, T: e.timeToStrike, actorT: e.actor.timeToStrike, rel });
  });
  const step = (s) => {
    for (let i = 0, n = Math.round(s / DT); i < n; i++) {
      ctx.time.gnow += DT;
      ai.update(DT);
    }
  };
  return { ctx, ai, step, windups };
}

/** The first player-targeted wind-up of an enemy spawned at (x, z), within 30 s. */
function firstWindup(opts, x, z, spawnOpts = {}, kind = 'immortal') {
  const w = world(opts);
  const a = w.ai.spawnEnemy(kind, x, z, spawnOpts);
  for (let i = 0; i < 900 && !w.windups.some((e) => e.actor === a); i++) w.step(DT);
  const e = w.windups.find((x2) => x2.actor === a);
  assert.ok(e, `${kind} at (${x}, ${z}) wound up at the player`);
  return e;
}

const close = (a, b, msg) => assert.ok(Math.abs(a - b) < 1e-9, `${msg}: ${a} vs ${b}`);

test('hilt, view 0: a Day 3 Immortal winding up from behind gets +0.35 s; from the front, none', () => {
  const rear = firstWindup({ grip: 'hilt' }, 0.2, 3.5);
  assert.ok(Math.abs(rear.rel) > LOCK.threatAngle, `from behind: ${rear.rel}`);
  close(rear.T, IMMORTAL_D3 + GRACE, 'rear');
  close(rear.actorT, IMMORTAL_D3 + GRACE, 'rear, actor.timeToStrike');
  const fwd = firstWindup({ grip: 'hilt' }, -Math.sin(0.5) * 4, -Math.cos(0.5) * 4);
  assert.ok(Math.abs(fwd.rel) <= LOCK.threatAngle, `from the front: ${fwd.rel}`);
  close(fwd.T, IMMORTAL_D3, 'front');
});

test('the grace follows the view at wind-up start: facing the rear attacker, none', () => {
  const e = firstWindup({ grip: 'hilt', yaw: Math.PI }, 0.2, 3.5);
  assert.ok(Math.abs(e.rel) <= LOCK.threatAngle, `in view: ${e.rel}`);
  close(e.T, IMMORTAL_D3, 'facing him');
  // and a front attacker is a rear one for a player facing the rear
  const f = firstWindup({ grip: 'hilt', yaw: Math.PI }, 0, -4);
  assert.ok(Math.abs(f.rel) > LOCK.threatAngle);
  close(f.T, IMMORTAL_D3 + GRACE, 'behind the view');
});

test('no grace for the window grip, touch, a ctx without input.grip, or the trainer', () => {
  close(firstWindup({ grip: 'window' }, 0.2, 3.5).T, IMMORTAL_D3, 'window');
  close(firstWindup({ grip: null, control: 'touch' }, 0.2, 3.5).T, ENEMIES.immortal.windup * 0.9, 'touch');
  close(firstWindup({}, 0.2, 3.5).T, IMMORTAL_D3, 'no input.grip (headless balance runs)');
  // the trainer: 1.2 s × 1.15 in motion, never the Day 3 factor or the grace, even behind the view
  const t = firstWindup({ grip: 'hilt', day: 1 }, 0, 1.7, { trainer: true, facing: Math.PI }, 'levy');
  assert.ok(Math.abs(t.rel) > LOCK.threatAngle);
  close(t.T, 1.2 * 1.15, 'trainer');
});

test('Days 1 and 2 get the grace too (any player-targeted wind-up from behind the view)', () => {
  const lv = ENEMIES.levy.windup * 1.15;
  close(firstWindup({ grip: 'hilt', day: 1, yaw: Math.PI }, 0, -4, {}, 'levy').T, lv + GRACE, 'day 1, levy behind the view');
  close(firstWindup({ grip: 'hilt', day: 1 }, 0, -4, {}, 'levy').T, lv, 'day 1, levy in front');
});
