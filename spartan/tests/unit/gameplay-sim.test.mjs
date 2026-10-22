// Headless run of the gameplay sandbox checks (tests/sandbox/gameplay-checks.js) under Node, so CI
// covers game/player.js and game/combat.js too. A resolve hook maps 'three' and 'three/addons/*'
// to the vendored files (as modulegraph.test.mjs does). The same checks run in Chromium via
// tests/e2e/gameplay-checks.mjs.
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
const { createHarness, createHeadlessCtx } = await import('../sandbox/gameplay-harness.js');
const { CHECKS, validateEvent } = await import('../sandbox/gameplay-checks.js');

const ctx = createHeadlessCtx({ EventBus, createRng });
const h = createHarness(ctx);
ctx.loop(() => h.tick());

for (const name of Object.keys(CHECKS)) {
  test(`gameplay: ${name}`, async () => {
    await CHECKS[name](h);
  });
}

test('gameplay: every emitted event matches its §12 payload', () => {
  const seen = new Set();
  for (const e of h.allLog) {
    seen.add(e.name);
    const synthetic = (e.name === 'hit' || e.name === 'kill') && !e.payload.target;
    assert.equal(validateEvent(e.name, e.payload, synthetic), null, `${e.name} payload`);
  }
  for (const name of ['swing', 'combo', 'playerHurt', 'playerDeath', 'scoreChanged', 'weaponSwitch', 'shield', 'hit', 'kill', 'block', 'spearThrown', 'spearImpact', 'arrowHit', 'volley']) {
    assert.ok(seen.has(name), `${name} was emitted`);
  }
});
