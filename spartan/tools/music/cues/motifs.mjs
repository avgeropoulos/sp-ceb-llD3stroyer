// motifs.mjs: the material shared by the three cues. Two themes run through the soundtrack:
//   T         the composed leitmotif, an anapaestic embaterion in the Dorian species (the Spartans' march);
//   SEIKILOS  the one quoted ancient melody, line 1 of the Seikilos epitaph ("While you live, shine").
// Title: T on the dawn aulos, Seikilos as the light, T and Seikilos in the tutti. Battle: T marched.
// Last stand: T re-cast as a lament, Seikilos sung as the last defiance, then re-cast as the epitaph.
// Pitch names: C4 = middle C. Durations in quarter notes.
import { P, stepUp, stepDown } from './lib.mjs';

/**
 * T, "the embaterion": the soundtrack's theme (composed, not ancient). Ancient Dorian species on E
 * (E D C B | A G F E, mese A), diatonic. Rhythm: anapaests (u u -) running across the bar line, the
 * pickup giving the first pair of shorts: uu | - uu - uu | - ... (8th, 8th | quarter, 8th, 8th, ...),
 * with the longs on the strong beats, so drums and melody march together. Read inside the bar, each
 * foot scans as a dactyl (- u u), a substitution anapaestic metre allows. The Spartan marching songs
 * (embateria, also called enoplia: Athenaeus 14.630f) were anapaestic: the Doric marching songs PMG
 * 856-857 (carmina popularia, ascribed to Tyrtaeus) are anapaestic dimeters. Thucydides 5.70 describes
 * the advance to many aulos players, to keep step; Plutarch, Lycurgus 22, the Kastoreion played as they
 * advanced. Bars 1-4 rise from the final E through the mese A to the trite C and fall back with the
 * Dorian semitone F-E; bars 5-8 (T') answer from the nete E5. Pickup: two shorts on beat 4 of the bar before.
 */
export const PICK_T = [['E4', 0.5], ['E4', 0.5]];
export const T = [
  [['A4', 1], ['G4', 0.5], ['A4', 0.5], ['B4', 1], ['A4', 0.5], ['B4', 0.5]],
  [['C5', 2], ['B4', 1], ['A4', 0.5], ['G4', 0.5]],
  [['A4', 1], ['G4', 0.5], ['F4', 0.5], ['G4', 1], ['F4', 0.5], ['E4', 0.5]],
  [['F4', 1], ['E4', 2], ['B4', 0.5], ['B4', 0.5]], // last two notes = pickup to T'
  [['E5', 1], ['D5', 0.5], ['C5', 0.5], ['D5', 1], ['C5', 0.5], ['B4', 0.5]],
  [['C5', 2], ['B4', 1], ['A4', 0.5], ['G4', 0.5]],
  [['A4', 1], ['B4', 0.5], ['C5', 0.5], ['B4', 1], ['A4', 0.5], ['G4', 0.5]],
  [['F4', 1], ['E4', 3]],
];

/**
 * The paean chant (composed): cretic feet, long-short-long (half, quarter, half) in 5/4, centred on
 * the mese A, men's voices A3-F4. The Delphic paeans are in cretic-paeonic rhythm; the tune is mine.
 */
export const PAEAN = [
  [['A3', 2], ['B3', 1], ['C4', 2]],
  [['B3', 2], ['A3', 1], ['G3', 2]],
  [['A3', 2], ['C4', 1], ['E4', 2]],
  [['D4', 2], ['C4', 1], ['B3', 2]],
  [['C4', 2], ['D4', 1], ['E4', 2]],
  [['F4', 2], ['E4', 1], ['D4', 2]],
  [['E4', 2], ['D4', 1], ['C4', 2]],
  [['B3', 1], ['C4', 0.5], ['B3', 0.5], ['A3', 3]],
];

/**
 * The "Kastoreion" dance (composed): the tune the Spartans advanced to is lost (Plutarch, Lycurgus 22,
 * says only that the king had the aulos play it as they advanced), so this is written in its manner: every
 * beat an anapaest in sixteenths (short-short-long), circling the mese A in the Dorian species, each phrase
 * closing on a one-beat long. Range E4-F5.
 */
export const KASTOREION = [
  [['A4', 0.25], ['A4', 0.25], ['B4', 0.5], ['C5', 0.25], ['B4', 0.25], ['A4', 0.5], ['A4', 0.25], ['A4', 0.25], ['E5', 0.5], ['D5', 0.25], ['C5', 0.25], ['B4', 0.5]],
  [['C5', 0.25], ['B4', 0.25], ['A4', 0.5], ['B4', 0.25], ['A4', 0.25], ['G4', 0.5], ['A4', 0.25], ['G4', 0.25], ['F4', 0.5], ['E4', 1]],
  [['A4', 0.25], ['A4', 0.25], ['B4', 0.5], ['C5', 0.25], ['B4', 0.25], ['C5', 0.5], ['D5', 0.25], ['C5', 0.25], ['D5', 0.5], ['E5', 1]],
  [['F5', 0.25], ['E5', 0.25], ['D5', 0.5], ['E5', 0.25], ['D5', 0.25], ['C5', 0.5], ['B4', 0.25], ['C5', 0.25], ['B4', 0.5], ['A4', 1]],
];

/**
 * QUOTED (ancient): Epitaph of Seikilos, line 1, "Hoson zes, phainou" ("While you live, shine").
 * Stele from near Tralles, 1st c. AD (Copenhagen, National Museum); Poehlmann & West, Documents of
 * Ancient Greek Music (2001) no. 23; West, Ancient Greek Music (1992). Iastian tonos, diatonic; the
 * octave species is the Phrygian (E F# G A B C# D E in the usual transposition).
 * Notation as read (from memory: no printed edition is reachable from the build sandbox):
 *   Ho  son  zes  phai-      nou
 *   C   Z    Z    K  I  Z    I  K  O    ->   a  e' e' | c#' d' e' | d' c#' b
 * Rhythm: 12 chronoi for the line, here 1 2 2 1 1 1 1 1 2 with one chronos = one quarter note: "son",
 * "zes" and the line's last note long (two 6-chronos halves: a e' e' c#' | d' e' d' c#' b). Another
 * reading lengthens the e' of "phai" instead (1 2 2 1 1 2 1 1 1); both make 12. The pitches follow the
 * standard modern reading. Check pitches and rhythm against DAGM 23 before calling it note-for-note.
 */
export const SEIKILOS_1 = [['A4', 1], ['E5', 2], ['E5', 2], ['C#5', 1], ['D5', 1], ['E5', 1], ['D5', 1], ['C#5', 1], ['B4', 2]];

/**
 * The Seikilos line re-cast degree for degree from the Phrygian species on E (E F# G A B C# D) into the
 * Mixolydian on B (B C D E F G A), the lamenting harmonia: a e' e' c#' d' e' d' c#' b -> e b b g a b a g f.
 * Used as the epitaph at the end of the last stand; then the title's composed close (A G F# E) re-cast
 * as E D C B, falling to the final B.
 */
export const SEIKILOS_MIX = [['E4', 1], ['B4', 2], ['B4', 2], ['G4', 1], ['A4', 1], ['B4', 1], ['A4', 1], ['G4', 1], ['F4', 2]];
export const SEIKILOS_CLOSE = [['A4', 1], ['G4', 0.5], ['F#4', 0.5], ['E4', 2]];       // composed, Phrygian species
export const SEIKILOS_MIX_CLOSE = [['E4', 1], ['D4', 0.5], ['C4', 0.5], ['B3', 2]];  // the same, Mixolydian

/**
 * Heterophony (the second aulos player's version of a line): same skeleton and onsets, but long notes
 * end with a turn through the next note of the species above, the longest also dip below, and a skip of
 * a third between two shorts is filled with a passing note. Ancient accounts of aulos accompaniment
 * describe exactly this: the instrument plays the singer's line with its own small departures.
 * items: [[pitch, quarters, ...rest]]; pcs: the species' pitch classes; maxKey: the part's ceiling (a turn
 * that would cross it goes through the note below instead). Returns a new item list.
 */
export function hetero(items, pcs, { turns = true, fill = true, quick = false, maxKey = 127 } = {}) {
  const out = [];
  let shorts = 0;
  // the next species note above, or (at the part's ceiling, maxKey) the one below: the turn then dips instead
  const up = (k) => { const u = stepUp(k, pcs); return u > maxKey ? stepDown(k, pcs) : u; };
  for (let i = 0; i < items.length; i++) {
    const [p, d, ...rest] = items[i];
    if (p === null || p === undefined) { out.push(items[i]); continue; }
    const k = P(p);
    // quick (dance tunes in sixteenths): a turn on the one-beat longs, and every other anapaest's long
    // (the eighth after two sixteenths) breaks into a lower mordent
    const afterPair = i >= 2 && items[i - 1][1] === 0.25 && items[i - 2][1] === 0.25;
    if (quick && d >= 1 && d < 2) { out.push([k, d - 0.5, ...rest], [up(k), 0.25], [k, 0.25]); continue; }
    if (quick && d === 0.5 && afterPair && (shorts++ % 2 === 1)) { out.push([k, 0.25, ...rest], [stepDown(k, pcs), 0.25]); continue; }
    if (turns && d >= 3) {
      out.push([k, d - 1, ...rest], [up(k), 0.25], [k, 0.25], [stepDown(k, pcs), 0.25], [k, 0.25]);
    } else if (turns && d >= 2) {
      out.push([k, d - 0.5, ...rest], [up(k), 0.25], [k, 0.25]);
    } else if (fill && d === 0.5 && items[i + 1] && items[i + 1][0] && items[i + 1][1] === 0.5) {
      const n = P(items[i + 1][0]);
      const mid = n > k ? stepUp(k, pcs) : stepDown(k, pcs); // a passing note between k and n: never above both
      if (Math.abs(n - k) >= 3 && Math.abs(n - k) <= 4 && mid !== n) out.push([k, 0.25, ...rest], [mid, 0.25]);
      else out.push([k, d, ...rest]);
    } else out.push([k, d, ...rest]);
  }
  return out;
}
/** T bars [from, to) as one flat item list (for hetero() and line()) */
export const Tflat = (from = 0, to = 8) => T.slice(from, to).flat();

// Species as pitch-class sets (for the analysis sidecars)
export const PCS = {
  dorianE: [4, 5, 7, 9, 11, 0, 2],          // E F G A B C D
  phrygianSpeciesE: [4, 6, 7, 9, 11, 1, 2], // E F# G A B C# D (Seikilos)
  mixolydianB: [11, 0, 2, 4, 5, 7, 9],      // B C D E F G A
  chromaticE: [4, 5, 6, 9, 11, 0, 1],       // E F F# A | B C C# (chromatic genus, both tetrachords)
};
