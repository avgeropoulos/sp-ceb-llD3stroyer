/**
 * Spartan rage. PURE: no DOM, no three.js.
 *
 * The meter (0..1) fills as the player fights: hits, kills, blocks, combos, and a little from the
 * blows he takes. When it is full the RAGE button (or V) unleashes it for the tier's duration,
 * while the meter drains: the player's blows strike for damageMul, every cut catches each foe in
 * its arc, every hit staggers, a kill heals healPerKill, and blows on him hurt takenMul as much.
 * Nothing fills the meter while it burns. player.js owns the state; combat.js reads its perks.
 *
 * The rage grows as the campaign nears its end (RAGE_TIERS, rageTier): longer, kills that feed
 * it, a faster meter, and at the Kolonos arrows that cannot hurt and a war cry that hurls back
 * every foe around the player.
 */
export const RAGE = Object.freeze({
  duration: 8,      // s (player time), the first tier's
  damageMul: 2,
  takenMul: 0.5,
  stagger: 0.6,     // s: the least stagger a blow deals while raging
  healPerKill: 6,
  shockRadius: 4,   // m: the last stand's war cry
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
 * The rage's tiers, one per stage of the campaign. Each lists every perk, so the player and
 * combat read one row. `perk` is what the HUD announces when the tier is reached.
 */
export const RAGE_TIERS = Object.freeze([
  Object.freeze({ tier: 1, name: 'Spartan rage', duration: RAGE.duration, damageMul: RAGE.damageMul, takenMul: RAGE.takenMul,
    healPerKill: RAGE.healPerKill, gainMul: 1, killExtend: 0, arrowProof: false, shockwave: false, perk: '' }),
  Object.freeze({ tier: 2, name: 'Fury of Lakedaimon', duration: 10, damageMul: 2, takenMul: 0.5,
    healPerKill: 6, gainMul: 1, killExtend: 1, arrowProof: false, shockwave: false,
    perk: 'longer, and kills feed it' }),
  Object.freeze({ tier: 3, name: 'Wrath of the Three Hundred', duration: 10, damageMul: 2, takenMul: 0.5,
    healPerKill: 10, gainMul: 1.5, killExtend: 1, arrowProof: false, shockwave: false,
    perk: 'fills faster, kills heal more' }),
  Object.freeze({ tier: 4, name: 'The last stand', duration: 12, damageMul: 2.5, takenMul: 0.35,
    healPerKill: 12, gainMul: 1.5, killExtend: 1.5, arrowProof: true, shockwave: true,
    perk: 'arrow-proof, and the war cry hurls foes back' }),
]);

/**
 * The tier for a point in the campaign: Day 1 → 1, Day 2 → 2, Day 3 → 3, the Kolonos (Day 3's
 * phase C) → 4. Anything unknown (the Agoge) is the first tier.
 * @param {number} day
 * @param {string|null} phase
 */
export function rageTier(day, phase) {
  const t = day >= 3 ? (phase === 'C' ? 4 : 3) : day === 2 ? 2 : 1;
  return RAGE_TIERS[t - 1];
}

/**
 * The meter after a gain of `kind` (a RAGE.gain key; unknown kinds add nothing) scaled by `mul`,
 * clamped to 0..1.
 * @param {number} level
 * @param {string} kind
 * @param {number} [mul]
 * @returns {number}
 */
export function gainRage(level, kind, mul = 1) {
  const g = (RAGE.gain[kind] || 0) * (Number.isFinite(mul) ? mul : 1);
  const v = (Number.isFinite(level) ? level : 0) + g;
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** Can the rage be unleashed now? A full meter, not already raging. */
export function canUnleash(level, raging) {
  return !raging && level >= 1 - 1e-9;
}
