/**
 * Gifts of the gods: rage perks the player picks. PURE: no DOM, no three.js.
 *
 * At every respite the player is offered three gifts not yet taken (offerBoons) and keeps the one
 * he picks for the rest of the battle. Each gift changes the rage (game/rage.js) while it burns,
 * or how it fills; applyBoons folds the taken gifts into the campaign tier's row, so the player,
 * combat and the HUD read one object. player.js owns the offer and the taken list.
 */
import { RAGE_TIERS } from './rage.js';

/** Fields a gift may change, with their neutral values (added to each tier row by applyBoons). */
const EXTRA = Object.freeze({
  reachBonus: 0,    // m added to a raging blow's reach
  critMul: 1,       // × a raging blow's damage, on top of damageMul
  moveMul: 1,       // × walking speed while raging
  unleashHeal: 0,   // health on unleashing
  comboExtend: 0,   // s a combo adds to a burning rage
  spearOnKill: false, // a raging kill puts a spear back in the bundle
  hurtGainMul: 1,   // × the meter a blow taken fills
  gaze: false,      // unleashing staggers every foe in front within gazeRange
});

export const GAZE = Object.freeze({ range: 12, halfAngle: 0.9, stagger: 1.5 });

/**
 * The gifts. `apply(p)` changes a copy of the row; `granted(p)` is true when the row already has
 * the effect (that gift is then not offered).
 */
export const BOONS = Object.freeze([
  { id: 'herakles', name: 'Blood of Herakles', desc: 'Raging kills heal 8 more.', apply: (p) => { p.healPerKill += 8; } },
  { id: 'zeus', name: 'Bolt of Zeus', desc: 'Your rage burns 3 s longer.', apply: (p) => { p.duration += 3; } },
  { id: 'ares', name: "Ares' Reach", desc: 'Raging blows reach further.', apply: (p) => { p.reachBonus += 0.7; } },
  { id: 'bronze', name: 'Bronze Skin', desc: 'Raging, blows hurt you a third less.', apply: (p) => { p.takenMul *= 0.67; } },
  { id: 'achilles', name: 'Wrath of Achilles', desc: 'Raging blows strike 40% harder.', apply: (p) => { p.critMul *= 1.4; } },
  { id: 'apollo', name: 'Shield of Apollo', desc: 'Raging, arrows cannot touch you.', apply: (p) => { p.arrowProof = true; }, granted: (p) => p.arrowProof },
  { id: 'alalai', name: 'Alalai!', desc: 'Your war cry hurls back every foe around you.', apply: (p) => { p.shockwave = true; }, granted: (p) => p.shockwave },
  { id: 'hermes', name: 'Sandals of Hermes', desc: 'Raging, you move 40% faster.', apply: (p) => { p.moveMul *= 1.4; } },
  { id: 'fire', name: 'Fire of Sparta', desc: 'Your rage fills a third faster.', apply: (p) => { p.gainMul *= 1.35; } },
  { id: 'second', name: 'Second Wind', desc: 'Unleashing the rage heals 25.', apply: (p) => { p.unleashHeal += 25; } },
  { id: 'laconic', name: 'Laconic Fury', desc: 'Raging combos add 2 s of rage.', apply: (p) => { p.comboExtend += 2; } },
  { id: 'dory', name: 'Dory of Leonidas', desc: 'Raging kills give you back a spear.', apply: (p) => { p.spearOnKill = true; } },
  { id: 'vengeance', name: 'Vengeance', desc: 'Blows you take fill the rage three times as fast.', apply: (p) => { p.hurtGainMul *= 3; } },
  { id: 'gorgon', name: "Gorgon's Gaze", desc: 'Unleashing stuns every foe before you.', apply: (p) => { p.gaze = true; } },
].map((b) => Object.freeze(b)));

const BY_ID = new Map(BOONS.map((b) => [b.id, b]));

/** The gift with this id, or undefined. */
export function boonById(id) {
  return BY_ID.get(id);
}

/**
 * The perks in effect: the tier row (RAGE_TIERS) with EXTRA's neutral fields, then every taken
 * gift applied in the order taken. Unknown ids are skipped. Returns a new frozen object.
 * @param {object} tierRow
 * @param {ReadonlyArray<string>} taken
 */
export function applyBoons(tierRow, taken) {
  const p = Object.assign({}, EXTRA, tierRow || RAGE_TIERS[0]);
  if (taken) for (const id of taken) {
    const b = BY_ID.get(id);
    if (b) b.apply(p);
  }
  return Object.freeze(p);
}

/**
 * Up to `n` gifts to offer: not taken, and not already granted by the perks in effect (`perks`),
 * drawn without replacement with `rand` (a () => [0, 1) function).
 * @returns {string[]} ids
 */
export function offerBoons(taken, perks, rand = Math.random, n = 3) {
  const pool = [];
  for (const b of BOONS) {
    if (taken && taken.includes(b.id)) continue;
    if (b.granted && perks && b.granted(perks)) continue;
    pool.push(b.id);
  }
  const out = [];
  while (out.length < n && pool.length > 0) {
    const r = rand();
    const i = Math.min(pool.length - 1, Math.floor((Number.isFinite(r) ? r : 0) * pool.length));
    out.push(pool.splice(i, 1)[0]);
  }
  return out;
}
