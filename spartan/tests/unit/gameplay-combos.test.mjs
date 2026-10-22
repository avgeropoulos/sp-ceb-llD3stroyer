// Unit tests for game/combos.js (DESIGN.md §8). Run: node --test spartan/tests/unit/gameplay-combos.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { COMBOS, ComboTracker } from '../../src/main/assets/game/js/game/combos.js';
import { dirToken } from '../../src/main/assets/game/js/game/rules.js';

const ids = (tracker, tokens, dt = 0.3, t0 = 10) => tokens.map((tok, i) => {
  const row = tracker.push(tok, t0 + i * dt);
  return row ? row.id : null;
});

test('§8 required: R,L,R,L 0.3 s apart returns twin, fury, storm', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['R', 'L', 'R', 'L']), [null, 'twin', 'fury', 'storm']);
  assert.equal(c.chain.length, 0, 'storm clears the chain');
});

test('§8 required: DR,DL returns xcut (more specific than twin)', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['DR', 'DL']), [null, 'xcut']);
  const c2 = new ComboTracker();
  assert.deepEqual(ids(c2, ['DL', 'DR']), [null, 'xcut']);
});

test('§8 required: a gap > 1.25 s resets', () => {
  const c = new ComboTracker();
  assert.equal(c.push('R', 0), null);
  assert.equal(c.push('L', 1.26), null);
  assert.equal(c.push('R', 1.5).id, 'twin', 'L,R after the reset');
  // Exactly the window still continues.
  const c2 = new ComboTracker();
  c2.push('R', 0);
  assert.equal(c2.push('L', 1.25).id, 'twin');
});

test('rows: either-order pairs, bonuses, descriptions and effects', () => {
  assert.equal(COMBOS.length, 12);
  for (const row of COMBOS) {
    assert.equal(row.bonus, Math.round(100 * row.mult));
    assert.equal(typeof row.desc, 'string');
    assert.ok(row.desc.length > 5);
    assert.ok(row.seq.every((s) => s instanceof Set));
  }
  const by = (id) => COMBOS.filter((r) => r.id === id);
  assert.equal(by('twin').length, 2);
  assert.equal(by('fury').length, 2);
  assert.equal(by('storm').length, 2);
  assert.equal(by('xcut').length, 2);
  assert.equal(by('cleave').length, 1);
  assert.deepEqual(by('storm')[0].slowMo, [0.5, 0.4]);
  assert.deepEqual(by('cleave')[0].slowMo, [0.35, 0.5]);
  assert.equal(by('cleave')[0].breakGuard, true);
  assert.deepEqual(by('xcut')[0].bleed, { dps: 6, dur: 3 });
  assert.equal(by('fury')[0].effect, 'sweep');
  assert.equal(by('fury')[0].arc, 1.75);
  assert.equal(by('answer')[0].effect, 'execute');
  assert.equal(by('drive')[0].knockback, 2.5);
  assert.equal(by('wall')[0].effect, 'stagger_all');
  assert.equal(by('wall')[0].radius, 2.5);
  assert.equal(by('storm')[0].bonus, 240);
});

test('L-first alternation and diagonal members of the sets', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['L', 'R', 'L', 'R']), [null, 'twin', 'fury', 'storm']);
  const c2 = new ComboTracker();
  assert.deepEqual(ids(c2, ['UR', 'UL', 'DR', 'L']), [null, 'twin', 'fury', 'storm']);
});

test('xcut keeps the chain alive: DR,DL,DR,DL climbs to storm', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['DR', 'DL', 'DR', 'DL']), [null, 'xcut', 'fury', 'storm']);
});

test('cleave clears; cleave vs twin on an exact specificity tie goes to the earlier row', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['U', 'D', 'D']), [null, 'cleave', null]);
  assert.equal(c.chain.length, 1);
  const c2 = new ComboTracker();
  // UL ∈ Uish ∩ Lish and DR ∈ Dish ∩ Rish: both rows have size 6 → table order (twin).
  assert.deepEqual(ids(c2, ['UL', 'DR']), [null, 'twin']);
  const c3 = new ComboTracker();
  assert.deepEqual(ids(c3, ['UR', 'D']), [null, 'cleave']);
});

test('thrust / bash rows: answer, drive, wall', () => {
  let c = new ComboTracker();
  assert.deepEqual(ids(c, ['B', 'T']), [null, 'answer']);
  assert.equal(c.chain.length, 0);
  c = new ComboTracker();
  assert.deepEqual(ids(c, ['T', 'T', 'T', 'T']), [null, null, 'drive', null]);
  c = new ComboTracker();
  // The bash cooldown (0.9 s) is under the window, so B,B is reachable.
  assert.deepEqual(ids(c, ['B', 'B'], 0.9), [null, 'wall']);
  c = new ComboTracker();
  assert.deepEqual(ids(c, ['B', 'B'], 1.3), [null, null], 'too slow');
});

test('a throw (J) or an off-set token breaks the chain', () => {
  const c = new ComboTracker();
  assert.deepEqual(ids(c, ['R', 'J', 'L']), [null, null, null]);
  const c2 = new ComboTracker();
  assert.deepEqual(ids(c2, ['R', 'L', 'U', 'D']), [null, 'twin', null, 'cleave']);
});

test('chains of six: the chain keeps at most 6 tokens and matching still works', () => {
  const c = new ComboTracker();
  for (let i = 0; i < 9; i++) assert.equal(c.push('U', i * 0.2), null);
  assert.equal(c.chain.length, 6);
  assert.deepEqual([...c.chain], ['U', 'U', 'U', 'U', 'U', 'U']);
  assert.equal(c.push('D', 2).id, 'cleave');
  assert.equal(c.chain.length, 0);
  // Six alternating slashes: twin, fury, storm, then a fresh start.
  const c2 = new ComboTracker();
  assert.deepEqual(ids(c2, ['R', 'L', 'R', 'L', 'R', 'L']), [null, 'twin', 'fury', 'storm', null, 'twin']);
  // Six thrusts: drive twice.
  const c3 = new ComboTracker();
  assert.deepEqual(ids(c3, ['T', 'T', 'T', 'T', 'T', 'T']), [null, null, 'drive', null, null, 'drive']);
});

test('the window measures between consecutive pushes, not from the chain start', () => {
  const c = new ComboTracker();
  // 1.0 s apart each: within the window every time.
  assert.deepEqual(ids(c, ['R', 'L', 'R', 'L'], 1.0), [null, 'twin', 'fury', 'storm']);
  // A pause mid-chain restarts from the token after it.
  const c2 = new ComboTracker();
  c2.push('R', 0);
  c2.push('L', 0.3);
  assert.equal(c2.push('R', 2.0), null, 'fury lost to the pause');
  assert.equal(c2.push('L', 2.3).id, 'twin');
});

test('reset, custom window and table, time going backwards', () => {
  const c = new ComboTracker(COMBOS, 0.5);
  c.push('R', 0);
  assert.equal(c.push('L', 0.6), null);
  c.reset();
  assert.equal(c.chain.length, 0);
  c.push('R', 5);
  assert.equal(c.push('L', 5.4).id, 'twin');
  const only = [{ id: 'x', name: 'X', seq: [new Set(['U']), new Set(['U'])], mult: 1, bonus: 100 }];
  const c2 = new ComboTracker(only);
  assert.equal(c2.push('U', 0), null);
  assert.equal(c2.push('U', 0.1).id, 'x');
  // gnow restarts after a mode reset: an earlier timestamp starts a new chain.
  const c3 = new ComboTracker();
  c3.push('R', 100);
  assert.equal(c3.push('L', 1), null);
});

test('screen directions map to the intended combos via dirToken', () => {
  const c = new ComboTracker();
  const R = dirToken(0.1);
  const L = dirToken(Math.PI - 0.2);
  assert.equal(c.push(R, 0), null);
  assert.equal(c.push(L, 0.3).id, 'twin');
  const c2 = new ComboTracker();
  c2.push(dirToken(Math.PI / 2 + 0.2), 0);
  assert.equal(c2.push(dirToken(-Math.PI / 2 - 0.1), 0.4).id, 'cleave');
  const c3 = new ComboTracker();
  c3.push(dirToken(-Math.PI / 4), 0);
  assert.equal(c3.push(dirToken(-3 * Math.PI / 4), 0.4).id, 'xcut');
});
