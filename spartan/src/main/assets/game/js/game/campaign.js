/**
 * Campaign data for game/waves.js (DESIGN.md §13). PURE: plain data, no imports, no DOM.
 * Sources: Herodotus 7.210–7.228, Plutarch (Moralia 225C–D), Diodorus 11.9.
 */

/** World x of the ally line slots (z ≈ 0, §11). The player holds x = 0. */
export const ALLY_SLOTS = Object.freeze([-4.2, -2.8, -1.4, 1.4, 2.8, 4.2, 5.6]);
/** Leonidas holds the slot on the player's left. */
export const LEONIDAS_X = -1.4;

/** Every spoken line. `speaker` is one of the §12 voice speakers. */
export const VOICE = Object.freeze({
  herald: { speaker: 'Herald', text: 'The King commands: hand over your arms.' },
  molonLabe: { speaker: 'Leonidas', text: 'Come and take them.', greek: 'ΜΟΛΩΝ ΛΑΒΕ' },
  arrowsHideSun: { speaker: 'Trachinian', text: 'Their arrows will hide the sun!' },
  fightInShade: { speaker: 'Dienekes', text: 'Good. Then we shall fight in the shade.' },
  holdTheLine: { speaker: 'Leonidas', text: 'Hold the line!' },
  shieldsUp: { speaker: 'Leonidas', text: 'Shields up!' },
  warcry: { speaker: 'Leonidas', text: 'Alalai!' },
  immortals: { speaker: 'Lookout', text: 'The Immortals! Hydarnes leads them.' },
  anopaia: {
    speaker: 'Lookout',
    text: 'The Phocians have fled the Anopaia path! Ephialtes has led the Immortals over the mountain!',
  },
  breakfast: { speaker: 'Leonidas', text: 'Eat your breakfast as men who will dine in Hades.' },
  tegeans: { speaker: 'Narrator', text: 'The Tegeans take the line.' },
  swords: { speaker: 'Dienekes', text: 'Our spears are broken. Swords!' },
});

/** Lines Leonidas speaks in battle; Dienekes takes them over once Leonidas has fallen. */
export const LEONIDAS_BATTLE_LINES = Object.freeze(['holdTheLine', 'shieldsUp', 'warcry']);

/** Melee kinds a wave can contain (archers are listed separately and never count toward `alive`). */
export const MELEE_KINDS = Object.freeze(['levy', 'sparabara', 'immortal', 'captain']);

/**
 * The campaign. Days 1–2 are lists of waves; Day 3 is three phases.
 * Wave fields: kind counts, `alive` (max melee alive at once), `volleyEvery` (s, 0/absent = none).
 */
export const DAYS = [
  {
    day: 1, time: 'morning', title: 'The First Day', subtitle: 'The Medes and Kissians', allies: true,
    intro: ['herald', 'molonLabe'],
    waves: [
      { levy: 8, alive: 4, subtitle: 'Medes and Kissians' },
      { levy: 12, alive: 5, subtitle: 'Many men, but few warriors' },
      { levy: 12, archer: 2, alive: 6, volleyEvery: 0, subtitle: 'Bowmen behind them' },
      { levy: 6, sparabara: 6, alive: 6, subtitle: 'A wall of wicker shields' },
    ],
  },
  {
    day: 1, time: 'dusk', title: 'The Immortals', subtitle: "Hydarnes leads the King's Ten Thousand",
    intro: ['immortals'],
    waves: [
      { immortal: 8, archer: 3, volleyEvery: 30, alive: 6, subtitle: 'The Ten Thousand' },
      { immortal: 10, captain: 1, archer: 3, volleyEvery: 24, alive: 7, subtitle: 'A Saka axeman leads them' },
    ],
    // Herodotus 7.212
    endCard: 'Three times, it is said, the King leapt from his throne in fear for his army.',
    endSource: 'Herodotus 7.212',
  },
  {
    day: 2, time: 'noon', title: 'The Second Day', subtitle: 'The Greeks hold the pass city by city', allies: true,
    intro: [],
    waves: [
      { levy: 8, sparabara: 6, archer: 3, volleyEvery: 26, alive: 7, subtitle: 'Fresh levies and shield-bearers' },
      { immortal: 8, sparabara: 4, captain: 1, archer: 3, volleyEvery: 22, alive: 8, subtitle: 'The Immortals again' },
      { levy: 6, immortal: 8, captain: 1, archer: 4, volleyEvery: 20, alive: 9, subtitle: 'Picked men of every nation' },
    ],
  },
  {
    day: 3, time: 'dawn', battleTime: 'morning', title: 'The Third Day', subtitle: 'The Anopaia path',
    intro: ['anopaia', 'breakfast'],
    phases: [
      {
        id: 'A', title: 'Into the wider pass', subtitle: 'They go out beyond the wall to meet them',
        respite: { seconds: 12, heal: 0.2, refill: true, helot: true, allies: false },
        waves: [
          { levy: 8, immortal: 6, alive: 10, volleyEvery: 18, subtitle: 'Driven on with whips' },
          { levy: 8, immortal: 8, alive: 10, volleyEvery: 16, subtitle: 'Driven on with whips' },
          { levy: 8, immortal: 10, alive: 10, volleyEvery: 14, subtitle: 'Driven on with whips' },
        ],
      },
      {
        id: 'B', title: "Over Leonidas' body", subtitle: 'Leonidas has fallen', killAlly: 'Leonidas',
        noRefill: true, lull: 6, // 7.224–225: four times they drove the Persians back
        waves: [
          { immortal: 8, captain: 1, alive: 9, surge: true },
          { immortal: 8, captain: 1, alive: 9, surge: true },
          { immortal: 8, captain: 1, alive: 9, surge: true },
          { immortal: 8, captain: 1, alive: 9, surge: true },
        ],
      },
      {
        id: 'C', title: 'The Kolonos',
        // Herodotus 7.225
        subtitle: 'They defended themselves with swords, those who still had them, and with hands and teeth.',
        fade: 2, endless: true,
        endlessWave: { levy: 0.3, immortal: 0.7, alive: 12, behindShare: 0.4 },
        volley: { first: 10, start: 10, step: 1, min: 4 },
      },
    ],
  },
];

/** Respite rules for Days 1–2 (Day 3 phase A carries its own). */
export const RESPITE = Object.freeze({ motion: 25, touch: 15, readyAfter: 6, heal: 0.35 });

/** Spawning rules (§13). */
export const SPAWN = Object.freeze({
  interval: [1.2, 2.0],
  front: { x: [-6, 7], z: [-45, -35] },
  behind: { x: [-3, 3], z: [30, 40] },
  firstVolley: 12,
});

/** Seconds a title card stays up; the HUD fades it. */
export const CARD_SECONDS = 5;

/** Seconds between a fatal blow and `gameOver`. */
export const GAME_OVER_DELAY = 3.2;

/** Index of the first DAYS entry of a day (1–3). */
export function firstEntryOfDay(day) {
  for (let i = 0; i < DAYS.length; i++) if (DAYS[i].day === day) return i;
  return 0;
}

/** Sum of melee enemies in a wave definition. */
export function meleeCount(wave) {
  let n = 0;
  for (const k of MELEE_KINDS) n += wave[k] || 0;
  return n;
}
