// Stand-in for game/rules.js with the §7 numbers, used by the ai sandbox only while the real
// module is missing.
export const ENEMIES = {
  levy:      { hp: 55,  guard: 0.25, damage: 10, windup: 0.85, interval: [3.0, 4.5], walk: 1.4, run: 2.4, reach: 1.9, attackClip: 'thrust' },
  sparabara: { hp: 70,  guard: 0.70, damage: 12, windup: 0.90, interval: [3.2, 4.8], walk: 1.2, run: 2.0, reach: 2.2, attackClip: 'thrust' },
  immortal:  { hp: 90,  guard: 0.45, damage: 16, windup: 0.70, interval: [2.4, 3.4], walk: 1.5, run: 2.8, reach: 2.2, attackClip: 'overhead' },
  captain:   { hp: 180, guard: 0.30, damage: 28, windup: 1.00, interval: [3.5, 5.0], walk: 1.3, run: 2.2, reach: 1.8, attackClip: 'axe', heavy: true },
  archer:    { hp: 40,  guard: 0,    damage: 8,  windup: 1.20, interval: [4.0, 6.0], walk: 1.4, run: 2.4, reach: 45, attackClip: 'shoot' },
  spartan:   { hp: 150, guard: 0.60, damage: 30, windup: 0.60, interval: [1.6, 2.6], walk: 1.4, run: 3.0, reach: 2.6, attackClip: 'thrust' },
  dummy:     { hp: Infinity, guard: 0, damage: 0 },
  helot:     { hp: 1, guard: 0, damage: 0, walk: 3.5 },
};
export const DIRECTOR = {
  1: { engaged: 2, windups: 1, rate: 0.18, gap: 0.6 },
  2: { engaged: 3, windups: 2, rate: 0.28, gap: 0.4 },
  3: { engaged: 4, windups: 2, rate: 0.40, ratePerWave: 0.04, rateMax: 0.9, gap: 0.3 },
};
