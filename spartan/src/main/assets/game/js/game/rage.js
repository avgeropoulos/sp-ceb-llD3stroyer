/**
 * Spartan rage. PURE: no DOM, no three.js.
 *
 * The meter (0..1) fills as the player fights: hits, kills, blocks, combos, and a little from the
 * blows he takes. When it is full the RAGE button (or V) unleashes it for RAGE.duration s, while
 * the meter drains: the player's blows strike for damageMul, every cut catches each foe in its arc,
 * every hit staggers, a kill heals healPerKill, and blows on him (and the arrows) hurt takenMul as
 * much. Nothing fills the meter while it burns. player.js owns the state; combat.js reads it.
 */
export const RAGE = Object.freeze({
  duration: 8,      // s (player time)
  damageMul: 2,
  takenMul: 0.5,
  stagger: 0.6,     // s: the least stagger a blow deals while raging
  healPerKill: 6,
  gain: Object.freeze({
    hit: 0.04,
    kill: 0.1,
    block: 0.05,
    perfect: 0.12,
    combo: 0.06,
    hurt: 0.03,
  }),
});

/**
 * The meter after a gain of `kind` (a RAGE.gain key; unknown kinds add nothing), clamped to 0..1.
 * @param {number} level
 * @param {string} kind
 * @returns {number}
 */
export function gainRage(level, kind) {
  const g = RAGE.gain[kind] || 0;
  const v = (Number.isFinite(level) ? level : 0) + g;
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** Can the rage be unleashed now? A full meter, not already raging. */
export function canUnleash(level, raging) {
  return !raging && level >= 1 - 1e-9;
}
