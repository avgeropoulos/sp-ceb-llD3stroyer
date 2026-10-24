// lastStand.mjs -> lastStand.mid + lastStand.json: "The Kolonos", Day 3: Leonidas has fallen, the last men
// fight on the hill with swords and bare hands. A heroic lament: grief, then defiance, building, then the end.
//
//   node spartan/tools/music/cues/lastStand.mjs [outDir]   (default $THERMOPYLAE_MUSIC_CACHE/score; deterministic)
//
// TEMPO AND LENGTH  75 BPM, 4/4, 34 bars = 108.800 s loop (75 BPM gives whole 20 ms Opus frames for any
//   bar count: 5 222 400 samples at 48 kHz).
// MODE  Grief: the ancient Mixolydian harmonia, the lamenting one (Aristotle, Politics 1340a-b): the octave
//   species B-B on the white keys (B C D E | E F G A + a tone; the modern "Locrian"), final B, drones on B and
//   E, with the enharmonic pyknon (B B+ C, E E+ F: quarter tones by pitch bend) on the aulos. Turn: a change
//   of genus to the chromatic (E F F# A, as a rising line). Defiance: the Dorian species on E (E-E white keys,
//   mese A). Shine: the Phrygian species on E (E F# G A B C# D), the Seikilos melody's own. Epitaph: back to the
//   Mixolydian on B. Drones and unison/octave doubling throughout: no chords.
// RHYTHM  Dochmiacs (u - - u -, eighths 1+2+2+1+2 = one 4/4 bar), the meter of tragic lament (the Orestes
//   papyrus, P. Vienna G 2315, is dochmiac), on the drums and the trombones.
// FORM
//   L1  1-4   Threnos: drone on B, taiko heartbeat and a soft frame-drum dochmiac (the fight goes on), enharmonic
//             aulos sighs in bars 2-3 (room for the voice)
//   L2  5-8   Kommos: the lament (T re-cast degree for degree into the Mixolydian) on the low aulos, the frame
//             drum takes up the dochmiac
//   L3  9-12  Rising: the answer on the oboe with the shenai in heterophony, the men enter on the fourth B/E and
//             rise to the diminished fifth B/F with the celli, the tremolo swells on the B drone
//   L4 13-16  Grief: the lament in the men's chorus (two octaves), horns, strings and aulos; full dochmiac
//   L5 17-20  The turn: the drone moves to E, the oboe remembers the battle march pp (room for the voice), then
//             the charge: the tremolo climbs the chromatic tetrachord E F F# A, taiko 8ths -> 16ths, salpinx;
//             it crests under the defiance and breathes on its last eighth (the aulos's pickup leads in)
//   L6 21-24  Defiance: T in the Dorian in tutti (unison and octaves), trombones pounding the dochmiac
//      25-28  Shine: the Seikilos sung by the men in octaves with horns, strings and aulos (the climax)
//   L7 29-30  The blow: one tutti stroke on E/B, cut off; the drone falls back to B
//      31-34  Epitaph: the Seikilos re-cast into the Mixolydian on the solo low aulos over a failing heartbeat,
//             the frame drum's soft dochmiac returning under its close (33-34); its last B rings into bar 1
// QUOTED MELODY  Seikilos epitaph, line 1 (DAGM no. 23, see motifs.mjs): a e' e' c#' d' e' d' c#' b in the
//   Phrygian species (bars 25-27, with a composed close), and the same line re-cast degree for degree into the
//   Mixolydian on B, e b b g a b a g f + e d c b (bars 31-34). The Orestes papyrus is a model only (dochmiac
//   rhythm, the enharmonic pyknon, interjected aulos sighs): none of its pitches are quoted.
// INSTRUMENTS  aulos = English horn 69 (lament lead, +4 cents) + Oboe 68 + Shanai 111 (second player, -6 cents);
//   kithara = Harp 46; men's chorus = Choir Aahs 52 (E2-A4) + Voice Oohs 53 hum; Horns 60, Trombones 57,
//   salpinx = Trumpet 56 (B and E only); Slow Strings 49, Tremolo 44, Cello 42, Contrabass 43 (B1/E1);
//   Taiko 116, Timpani 47; GS Orchestra kit (concert bass drum, surdo as frame drum, low bongo 61 / open conga 63
//   as the hand-struck tympanon skin, tambourine as its jingles, castanets as krotala, cymbal).
import { Cue, P, hit, recast, outDir, DORIAN_E, MIXOLYDIAN_B } from './lib.mjs';
import { PICK_T, T, SEIKILOS_1, SEIKILOS_CLOSE, SEIKILOS_MIX, SEIKILOS_MIX_CLOSE, hetero, Tflat, PCS } from './motifs.mjs';

const OUT = outDir();
const c = new Cue({ name: 'lastStand', bpm: 75, meters: [[34, 4]], seed: 0x1E0C1D });

const OB = 0, SH = 1, EH = 2, HP = 3, CH = 4, VO = 5, HN = 6, TB = 7, TP = 8, DR = 9, ST = 10, VC = 11, CB = 12, TK = 13, TI = 14, TR = 15;
const REED = { minKey: P('E3'), maxKey: P('A5') };
c.channel(OB, { name: 'Aulos (oboe)', program: 68, vol: 112, pan: 56, rev: 54, jit: 5, mono: true, ...REED });
c.channel(SH, { name: 'Aulos 2 (shenai)', program: 111, vol: 68, pan: 80, rev: 50, lag: 8, jit: 6, fine: -6, mono: true, ...REED });
c.channel(EH, { name: 'Low aulos (cor anglais)', program: 69, vol: 124, pan: 48, rev: 58, lag: 4, jit: 5, fine: 4, mono: true, ...REED });
c.channel(HP, { name: 'Kithara (harp)', program: 46, vol: 104, pan: 34, rev: 60, jit: 4 });
c.channel(CH, { name: 'Men chorus', program: 52, vol: 106, pan: 64, rev: 72, lag: 16, jit: 7, minKey: P('E2'), maxKey: P('A4') });
c.channel(VO, { name: 'Hum (voice oohs)', program: 53, vol: 96, pan: 70, rev: 74, lag: 14, jit: 6, minKey: P('E2'), maxKey: P('A4') });
c.channel(HN, { name: 'Horns', program: 60, vol: 88, pan: 42, rev: 66, lag: 10, jit: 6 });
c.channel(TB, { name: 'Trombones', program: 57, vol: 94, pan: 86, rev: 60, lag: 8, jit: 5 });
c.channel(TP, { name: 'Salpinx (trumpet)', program: 56, vol: 78, pan: 100, rev: 90, lag: 4, jit: 4, mono: true });
c.channel(DR, { name: 'Orchestral perc (GS kit 48)', program: 48, vol: 112, pan: 64, rev: 50, jit: 1.5 });
c.channel(ST, { name: 'Strings (slow)', program: 49, vol: 90, pan: 64, rev: 64, lag: 10, jit: 6 });
c.channel(VC, { name: 'Celli', program: 42, vol: 94, pan: 46, rev: 46, lag: 5, jit: 4 });
c.channel(CB, { name: 'Basses', program: 43, vol: 100, pan: 76, rev: 42, lag: 5, jit: 4, minKey: P('E1') });
c.channel(TK, { name: 'Taiko', program: 116, vol: 100, pan: 56, rev: 52, jit: 1.5 });
c.channel(TI, { name: 'Timpani', program: 47, vol: 102, pan: 40, rev: 58, jit: 2 });
c.channel(TR, { name: 'Tremolo strings', program: 44, vol: 90, pan: 64, rev: 62, lag: 6, jit: 5 });
// HI/LO: hand drums for the tympanon's struck skin (low bongo 61, open conga 63): energy at 300 Hz-3 kHz, audible on phones
const K = { BD: 36, TAMB: 54, CYM: 59, SURDO_M: 86, SURDO_O: 87, CAST: 85, HI: 61, LO: 63 };
const DON = 40, KA = 43;

const sections = [
  [1, 'L1 Threnos: drone on B, heartbeat, soft dochmiac, enharmonic aulos sighs (speech room)'],
  [5, 'L2 Kommos: the lament (T in the Mixolydian) on the low aulos'],
  [9, 'L3 Rising: the answer on the aulos, the men enter, dochmiac drums'],
  [13, 'L4 Grief: the lament in full chorus and orchestra'],
  [17, 'L5 The turn: drone to E, the march remembered (speech room), the charge'],
  [21, 'L6 Defiance: T in the Dorian, tutti'],
  [25, 'L6 Shine: the Seikilos sung in tutti (climax)'],
  [29, 'L7 The blow, the fall back to B'],
  [31, 'L7 Epitaph: the Seikilos in the Mixolydian on the solo aulos'],
];
for (const [b, t] of sections) c.marker(b, t);

const swell = (ch, t0, t1, a, b) => c.ramp(ch, t0, t1, 11, a, b, c.q(0.25));
const hold = (ch, b0, b1, keys, vel, o = {}) => { for (const k of keys) c.note(ch, c.T(b0, o.at ?? 0), c.T(b1, o.end ?? 0) - c.T(b0, o.at ?? 0) - c.q(o.gap ?? 0.06), k, vel, { jit: o.jit }); };
// T re-cast into the Mixolydian on B, a fourth below (final E4 -> B3)
const mix = (p) => recast(p, DORIAN_E, MIXOLYDIAN_B) - 12;
const asIs = (p) => P(p);
// T bars 1-4 closing on the final (no pickup to T'): the lament's full statement in L4 ends on bar 16 beat 3,
// so the solo oboe's soft march pickup (bar 16 beat 4) sounds alone
const T4_CLOSED = [...Tflat(0, 3), ['F4', 1], ['E4', 2]];
// T' closing two beats early (bar 12 beats 1-2), clearing beat 4 for the L4 pickup on the same aulos
const T8_SHORT = [...Tflat(4, 7), ['F4', 1], ['E4', 2]];

/**
 * A flat line [[pitch, quarters, vel?]] from tick t through a pitch map. orn: grace from the next species note
 * above on notes of 2+ beats; enh: long notes on B or E are approached from a quarter tone above, held there
 * (hold beats) before gliding down: the enharmonic pyknon made audible.
 */
function play(ch, t, items, { map = asIs, oct = 0, vel = 90, legato = 0.95, shortLeg = 0.84, accent = 8, orn = false, enh = false, hold: hq = 0.35, pcs = PCS.mixolydianB, velOf = null } = {}) {
  const s = 12 * oct;
  items.forEach(([p, d, v0], i) => {
    const key = map(p) + s, long = d >= 1;
    const v = velOf ? velOf(i, d) : (v0 ?? vel + (long ? accent / 2 : -accent / 2));
    if (orn && d >= 2) { let u = key + 1; while (!pcs.includes(u % 12)) u++; c.note(ch, t - c.q(0.1), c.q(0.09), u, v - 20, { jit: 1 }); }
    if (enh && long && [11, 4].includes(key % 12)) { c.bend(ch, t - c.q(0.01), 0.5); c.glide(ch, t + c.q(hq), t + c.q(hq + 0.4), 0.5, 0); }
    c.note(ch, t, c.q(d) * (long ? legato : shortLeg), key, v);
    t += c.q(d);
  });
  return t;
}
/** enharmonic sigh on a monophonic aulos channel: C -> B+ (held) -> B, or F -> E+ -> E (the Mixolydian's two
 *  pykna, B B+ C and E E+ F). The glide back to the true pitch ends inside the lower note, so the next note
 *  starts in tune. */
function sigh(ch, b, beat, upper, lower, vel, dur = 2) {
  c.note(ch, c.T(b, beat), c.q(0.5) * 0.98, upper, vel);
  const t = c.T(b, beat + 0.5), len = dur - 0.5;
  c.bend(ch, t - c.q(0.02), 0.5);
  c.note(ch, t, c.q(len) * 0.97, lower, vel - 6);
  c.glide(ch, t + c.q(Math.min(0.4, len * 0.4)), t + c.q(Math.min(0.95, len * 0.9)), 0.5, 0);
}

// ---------------------------------------------------------------- drums
// dochmiac u - - u - in 8ths at 0, 1, 3, 5, 6 (sixteenths 0 2 6 10 12)
function dochmiac(b, lv, { taiko = true, surdo = true, bd = false, cast = false, tamb = false, fill = false, hand = false } = {}) {
  const t = (e) => c.T(b, e / 2), V = (v) => Math.max(1, Math.min(127, Math.round(v * lv)));
  if (taiko) { hit(c, TK, t(0), KA, V(88)); hit(c, TK, t(1), DON, V(112)); hit(c, TK, t(3), DON, V(102)); hit(c, TK, t(5), KA, V(84)); hit(c, TK, t(6), DON, V(110)); }
  if (surdo) for (const [e, k, v] of [[0, K.SURDO_O, 92], [1, K.SURDO_O, 100], [3, K.SURDO_O, 90], [4, K.SURDO_M, 50], [5, K.SURDO_M, 78], [6, K.SURDO_O, 98], [7, K.SURDO_M, 56]]) hit(c, DR, t(e), k, V(v));
  if (bd) { hit(c, DR, t(1), K.BD, V(90)); hit(c, DR, t(6), K.BD, V(80)); } // the concert bass drum is the kit's peakiest sound
  if (tamb) for (const e of [1, 6]) hit(c, DR, t(e), K.TAMB, V(84));
  if (cast) for (const e of [0, 5]) hit(c, DR, t(e), K.CAST, V(86));
  if (hand) for (const [e, k, v] of [[0, K.HI, 108], [2, K.LO, 84], [5, K.HI, 110], [7, K.LO, 90]]) hit(c, DR, t(e), k, V(v));
  if (fill) for (let s = 12; s < 16; s++) hit(c, TK, c.T(b, s / 4), s % 2 ? KA : DON, V(92 + (s - 12) * 6));
}
// L1: heartbeat (the dochmiac's head, short-long) on the deep taiko; the frame drum keeps the dochmiac going
// softly under it (the fight does not stop for the lament); no jingles
for (let b = 1; b <= 4; b++) {
  hit(c, TK, c.T(b, 0), DON, 58); hit(c, TK, c.T(b, 0.5), DON, 86); hit(c, TK, c.T(b, 2.5), DON, 50); hit(c, TK, c.T(b, 3), DON, 72);
  dochmiac(b, 0.46 + 0.02 * (b - 1), { taiko: false });
}
// L2: the heartbeat goes on; the frame drum alone takes up the dochmiac, softly
for (let b = 5; b <= 8; b++) { hit(c, TK, c.T(b, 0), DON, 56); hit(c, TK, c.T(b, 0.5), DON, 80); dochmiac(b, 0.56 + 0.03 * (b - 5), { taiko: false }); }
// L3: taiko and frame drum, growing
for (let b = 9; b <= 12; b++) dochmiac(b, 0.6 + 0.03 * (b - 9), { fill: b === 12 });
// L4: full dochmiac with the concert bass drum, krotala on the shorts
for (let b = 13; b <= 16; b++) dochmiac(b, 0.68 + 0.02 * (b - 13), { bd: true, cast: true, fill: b === 16, hand: true });
hit(c, DR, c.T(13), K.CYM, 96, { dur: c.q(4) });
// L5: the turn. 17-18 heartbeat only; 19-20 the charge (8ths -> 16ths), timpani roll, cymbal swell
for (const b of [17, 18]) { hit(c, TK, c.T(b, 0), DON, b === 17 ? 90 : 60); hit(c, TK, c.T(b, 0.5), DON, b === 17 ? 68 : 80); hit(c, TK, c.T(b, 3), DON, 62); }
for (let e = 0; e < 8; e++) { hit(c, TK, c.T(19, e / 2), e % 2 ? KA : DON, 70 + e * 3); hit(c, DR, c.T(19, e / 2), e % 2 ? K.SURDO_M : K.SURDO_O, 60 + e * 3); }
// (the charge crests under the defiance's downbeat: its 16ths, cymbal swell and swells stop short of bar 21's
// level, and on the last eighth it breathes: two light shorts, the anapaest into the downbeat)
for (let s = 0; s < 14; s++) { hit(c, TK, c.T(20, s / 4), s % 2 ? KA : DON, 80 + Math.min(s, 10) * 1.2); hit(c, DR, c.T(20, s / 4), s % 2 ? K.SURDO_M : K.SURDO_O, 62 + Math.min(s, 10) * 1.6); }
hit(c, TK, c.T(20, 3.5), KA, 62); hit(c, TK, c.T(20, 3.75), KA, 70);
for (let s = 0; s < 14; s++) hit(c, DR, c.T(20, 1.2 + s / 6), K.CYM, Math.round(22 + s * 2.6), { jit: 1 });
// L6 defiance (21-24): full dochmiac, tympanon on the two longs, krotala from 23
for (let b = 21; b <= 24; b++) dochmiac(b, 0.82, { bd: true, tamb: true, cast: b >= 23, fill: b === 24, hand: true });
// the defiance lands: a downbeat stroke of the great drum and the bass drum ahead of the dochmiac's first short
hit(c, DR, c.T(21), K.CYM, 104, { dur: c.q(4) }); hit(c, TK, c.T(21), DON, 106); hit(c, DR, c.T(21, 0.02), K.BD, 82);
// L6 shine (25-28): the drums follow the song's longs (as in the title's climax), a different gait from the dochmiac
const SKON = [0, 1, 3, 5, 6, 7, 8, 9, 10]; // Seikilos onsets in quarters from bar 25
SKON.forEach((q, i) => {
  const b = 25 + Math.floor(q / 4), beat = q % 4, long = SEIKILOS_1[i][1] >= 2;
  hit(c, TK, c.T(b, beat), long || i === 0 ? DON : KA, long ? 106 : 84);
  if (long) { hit(c, DR, c.T(b, beat), K.BD, 80); c.note(TI, c.T(b, beat), c.q(1.5), i === 8 ? 'B2' : 'E2', 102); }
  hit(c, DR, c.T(b, beat), long ? K.SURDO_O : K.SURDO_M, long ? 102 : 74);
});
for (let b = 25; b <= 28; b++) {
  for (const q of [1, 3]) hit(c, DR, c.T(b, q), K.TAMB, 80);
  hit(c, TK, c.T(b, 3.5), KA, 78); hit(c, TK, c.T(b, 1.5), KA, 70);
  for (const [q, k, v] of [[0.5, K.HI, 96], [1.5, K.LO, 110], [2.5, K.HI, 98], [3.5, K.LO, 114]]) hit(c, DR, c.T(b, q), k, v);
}
// the close (bar 28): A G F# E with the drums on 1 and 3
hit(c, TK, c.T(28, 0), DON, 108); hit(c, TK, c.T(28, 2), DON, 112); hit(c, DR, c.T(28, 2), K.BD, 86);
hit(c, DR, c.T(25), K.CYM, 78, { dur: c.q(4) });
// L7: the blow, then the heartbeat failing under the epitaph
hit(c, TK, c.T(29), DON, 120); hit(c, DR, c.T(29, 0.02), K.BD, 100); hit(c, DR, c.T(29), K.CYM, 98, { dur: c.q(8) });
for (let b = 31; b <= 34; b++) {
  const v = [0.9, 0.78, 0.66, 0.54][b - 31];
  hit(c, TK, c.T(b, 0), DON, Math.round(60 * v)); hit(c, TK, c.T(b, 0.5), DON, Math.round(88 * v));
  if (b < 34) hit(c, TK, c.T(b, 3), DON, Math.round(66 * v));
}
// under the epitaph's close the frame drum takes up the dochmiac again, softly, into the threnos
for (const b of [33, 34]) dochmiac(b, b === 33 ? 0.4 : 0.43, { taiko: false });

// timpani: B2/E2 on the dochmiac longs in L4; E2/B2 in the defiance; roll in L5; the blow
for (let b = 13; b <= 16; b++) { c.note(TI, c.T(b, 0.5), c.q(1), 'B2', 96); c.note(TI, c.T(b, 3), c.q(1), 'E2', 88); }
for (let i = 0; i < 48; i++) c.note(TI, c.T(19, i / 6), c.q(0.14), 'E2', Math.round(48 + i * 0.9), { jit: 1, vj: 3 });
for (let i = 0; i < 21; i++) c.note(TI, c.T(20, i / 6), c.q(0.14), 'E2', Math.round(88 + Math.min(i, 12) * 0.6), { jit: 1, vj: 3 });
for (let b = 21; b <= 24; b++) { c.note(TI, c.T(b, 0.5), c.q(1), 'E2', 110); c.note(TI, c.T(b, 3), c.q(1), b % 2 || b === 24 ? 'B2' : 'E2', 98); }
c.note(TI, c.T(29), c.q(3), 'E2', 114);

// ---------------------------------------------------------------- drones and low strings
// L1-L4 on B (the Mixolydian final): basses B1, celli B2/E3 (the fixed notes B and E)
hold(CB, 1, 9, ['B1'], 86); hold(VC, 1, 9, ['B2', 'E3'], 62);
swell(CB, c.T(1), c.T(2), 76, 96); swell(VC, c.T(1), c.T(3), 64, 96);
hold(CB, 9, 13, ['B1'], 96);
// L3: the celli move with the men: the fourth B/E (9-10), then the diminished fifth B/F (11-12), the lament
hold(VC, 9, 11, ['B2', 'E3'], 72); hold(VC, 11, 13, ['B2', 'F3'], 74);
// grief: celli in the dochmiac on B with the semitone C
const DOCH = [92, 116, 0, 108, 0, 88, 112, 0];
for (let b = 13; b <= 16; b++) for (let e = 0; e < 8; e++) if (DOCH[e]) c.note(VC, c.T(b, e / 2), c.q(e === 0 || e === 5 ? 0.45 : 0.9), b === 16 && e >= 5 ? 'C3' : 'B2', DOCH[e]);
hold(CB, 13, 17, ['B1'], 104);
c.cc(VC, c.T(13), 11, 104); c.cc(CB, c.T(13), 11, 106);
// the turn: drone on E
hold(CB, 17, 21, ['E1'], 94, { gap: 0.5 }); hold(VC, 17, 19, ['E2', 'B2'], 70); hold(VC, 19, 21, ['E2', 'B2'], 90, { gap: 0.5 });
c.cc(VC, c.T(17), 11, 86); c.cc(CB, c.T(17), 11, 96); swell(VC, c.T(19), c.T(20, 3.9), 80, 108); swell(CB, c.T(19), c.T(20, 3.9), 96, 110);
// defiance: 8th ostinato on E with dochmiac accents, basses on the longs
const OST = ['E2', 'E2', 'E2', 'E2', 'E2', 'E2', 'E2', 'F2'];
const OACC = [86, 118, 62, 110, 62, 88, 116, 70];
for (let b = 21; b <= 24; b++) {
  for (let e = 0; e < 8; e++) c.note(VC, c.T(b, e / 2), c.q(0.4), b % 4 === 0 && e >= 4 ? ['D2', 'D2', 'C2', 'D2'][e - 4] : OST[e], OACC[e]);
  // bar 24's C2 is short, so it never rubs against the celli's D2 that follows
  for (const e of [1, 3, 6]) c.note(CB, c.T(b, e / 2), c.q(b % 4 === 0 && e === 6 ? 0.45 : 0.9), b % 4 === 0 && e === 6 ? 'C2' : 'E1', 104);
}
c.cc(VC, c.T(21), 11, 110); c.cc(CB, c.T(21), 11, 110);
// shine: the drone on the melody's final E and the mese B of its Iastian tonos (B is a fixed note, E the
// final), so no A ever sounds under its C#
hold(CB, 25, 29, ['E1'], 104, { gap: 0.04 }); hold(VC, 25, 29, ['E2', 'B2'], 96, { gap: 0.04 });
c.cc(CB, c.T(25), 11, 122); c.cc(VC, c.T(25), 11, 120);
// the fall: the E drone pivots back to B for the epitaph
hold(CB, 29, 31, ['E1'], 96, { at: 0.5 }); hold(CB, 31, 35, ['B1'], 84);
hold(VC, 29, 31, ['E2', 'B2'], 80, { at: 0.5 }); hold(VC, 31, 35, ['B2', 'B3'], 62);
c.cc(CB, c.T(29, 0.5), 11, 104); c.cc(VC, c.T(29, 0.5), 11, 100);
swell(CB, c.T(29, 0.5), c.T(30, 3.5), 104, 76); swell(VC, c.T(29, 0.5), c.T(30, 3.5), 100, 66);
c.cc(CB, c.T(31), 11, 84); c.cc(VC, c.T(31), 11, 72); swell(CB, c.T(34), c.T(34, 3.9), 84, 76); swell(VC, c.T(34), c.T(34, 3.9), 72, 64);
c.cc(CB, c.T(1), 11, 76); c.cc(VC, c.T(1), 11, 64);

// ---------------------------------------------------------------- aulos
// L1: sighs with the enharmonic pyknon, bars 2-3 only (bars 1 and 4 are room for the voice)
c.cc(EH, c.T(1), 11, 80); swell(EH, c.T(1, 2), c.T(1, 3.9), 80, 108); // bar 1: the epitaph's last B is still ringing
sigh(EH, 2, 0, 'C4', 'B3', 98, 2); sigh(EH, 2, 2.5, 'F4', 'E4', 94, 1.5);
// bar 3: D -> C is a plain diatonic step (C+ belongs to no pyknon of the Mixolydian), then the sigh C -> B+ -> B
c.note(EH, c.T(3, 0), c.q(0.5) * 0.98, 'D4', 92); c.note(EH, c.T(3, 0.5), c.q(0.5) * 0.97, 'C4', 86);
sigh(EH, 3, 1, 'C4', 'B3', 96, 2.5);
// L2: the lament (T in the Mixolydian on B, B3-G4) on the low aulos, enharmonic approaches held on B and E
play(EH, c.T(4, 3), PICK_T.map(([p, d]) => [p, d, 86]), { map: mix, shortLeg: 0.86 });
play(EH, c.T(5), Tflat(0, 4), { map: mix, vel: 98, orn: true, enh: true, legato: 0.97 });
for (const b of [5, 7]) { swell(EH, c.T(b), c.T(b + 1), 90, 114); swell(EH, c.T(b + 1, 1), c.T(b + 1, 3.6), 114, 90); }
// L3: the answer (T' in the Mixolydian, B3-B4) on the oboe; the shenai joins in heterophony from bar 11
c.cc(OB, c.T(9), 11, 100);
play(OB, c.T(9), T8_SHORT, { map: mix, vel: 92, orn: true, enh: true, legato: 0.97 });
play(SH, c.T(11), hetero(Tflat(6, 8).map(([p, d]) => [mix(p), d]), PCS.mixolydianB), { vel: 62, legato: 0.86 });
c.cc(SH, c.T(11), 11, 88);
swell(OB, c.T(9), c.T(10, 2), 96, 114); swell(OB, c.T(11), c.T(12, 3.5), 114, 92);
// L4: aulos above the chorus (T in the Mixolydian an octave up, B4-G5), shenai heterophony. No held quarter
// tones here: the strings and the shenai double the oboe in unison, so a bent oboe would only sound out of tune
// (the enharmonic colour stays where the aulos is exposed: L1-L3 and the epitaph)
c.cc(OB, c.T(12, 3), 11, 102); c.cc(SH, c.T(12, 3), 11, 90);
play(OB, c.T(12, 3), PICK_T.map(([p, d]) => [p, d, 80]), { map: mix, oct: 1 });
play(OB, c.T(13), T4_CLOSED, { map: mix, oct: 1, vel: 92, orn: true });
play(SH, c.T(13), hetero(T4_CLOSED.map(([p, d]) => [mix(p) + 12, d]), PCS.mixolydianB), { vel: 68, legato: 0.86 });
// L5: the march remembered: T bars 1-2 (Dorian) on the solo oboe, soft, bars 17-18
c.cc(OB, c.T(17), 11, 92);
play(OB, c.T(16, 3), PICK_T.map(([p, d]) => [p, d, 70]), {});
play(OB, c.T(17), Tflat(0, 2), { vel: 80, orn: true, legato: 0.98, pcs: PCS.dorianE });
// the charge: aulos trill E5-F5 in bar 20
for (let s = 0; s < 12; s++) c.note(OB, c.T(20, s / 4), c.q(0.24), s % 2 ? 'F5' : 'E5', 70 + s * 2, { jit: 2 });
swell(OB, c.T(19, 2), c.T(20, 3), 80, 104);
// L6 defiance: T bars 1-4 at pitch on oboe, shenai heterophony, cor anglais an octave below (the pickup soft, in
// the charge's breath; the downbeat accented, settling by beat 3)
c.cc(OB, c.T(20, 3), 11, 92); c.cc(SH, c.T(20, 3), 11, 94); c.cc(EH, c.T(21), 11, 86);
play(OB, c.T(20, 3), PICK_T.map(([p, d]) => [p, d, 80]), {});
play(OB, c.T(21), Tflat(0, 4), { vel: 94, orn: true, pcs: PCS.dorianE });
play(SH, c.T(21), hetero(Tflat(0, 4), PCS.dorianE), { vel: 72, legato: 0.84 });
play(EH, c.T(21), Tflat(0, 4), { oct: -1, vel: 76, legato: 0.88 });
// L6 shine: the Seikilos on the massed aulos (oboe with graces, shenai heterophony) and the close
const SK = [...SEIKILOS_1, ...SEIKILOS_CLOSE];
const SKV = (base) => (i) => base + [0, 6, 4, -2, 0, 4, 0, -2, 2, 2, -4, -4, 4][i];
c.cc(OB, c.T(25), 11, 122); c.cc(SH, c.T(25), 11, 108);
play(OB, c.T(25), SK, { velOf: SKV(96), legato: 0.97, orn: true, pcs: PCS.phrygianSpeciesE });
play(SH, c.T(25), hetero(SK, PCS.phrygianSpeciesE), { vel: 74, legato: 0.92, shortLeg: 0.9 });
// L7 epitaph (31-34): the Seikilos re-cast into the Mixolydian on the solo low aulos, then its close to B,
// the long notes approached through their quarter tones; the final B rings into bar 1
c.cc(EH, c.T(31), 11, 88);
play(EH, c.T(31), [...SEIKILOS_MIX, ...SEIKILOS_MIX_CLOSE.slice(0, -1), ['B3', 3]], { vel: 94, enh: true, orn: true, legato: 0.98, hold: 0.4,
  velOf: (i) => 92 + [0, 6, 4, -2, 0, 4, 0, -4, 2, -2, -6, -8, -4][i] });
swell(EH, c.T(31), c.T(31, 3), 88, 96); swell(EH, c.T(33), c.T(34, 3.9), 96, 80);
c.cc(OB, c.T(1), 11, 100); c.cc(SH, c.T(1), 11, 88);

// ---------------------------------------------------------------- kithara (harp): single strings on the fixed notes
for (let b = 5; b <= 12; b++) for (const [e, k, v] of [[1, 'B2', 70], [3, b >= 11 ? 'F3' : 'E3', 58], [6, 'B3', 66]]) c.note(HP, c.T(b, e / 2), c.q(1.5), k, v);
for (let b = 17; b <= 18; b++) for (const [e, k, v] of [[1, 'E3', 66], [3, 'B3', 54], [6, 'E4', 60]]) c.note(HP, c.T(b, e / 2), c.q(1.5), k, v);
for (let b = 31; b <= 34; b++) for (const [e, k, v] of [[1, 'B2', 58], [3, 'B2', 42], [6, 'B3', 52]]) c.note(HP, c.T(b, e / 2), c.q(1.5), k, Math.round(v * (1 - 0.08 * (b - 31))));

// ---------------------------------------------------------------- voices
// hum (B3/E4) under the lament, rising into the chorus
hold(VO, 6, 13, ['B3', 'E4'], 60); c.cc(VO, c.T(6), 11, 40); swell(VO, c.T(6), c.T(8), 40, 80); swell(VO, c.T(9), c.T(12, 3.5), 80, 100);
// L3: the men enter on "ah": the fixed fourth B2/E3, then B2/F3 (the diminished fifth: the lament)
hold(CH, 9, 11, ['B2', 'E3'], 80); hold(CH, 11, 13, ['B2', 'F3'], 84);
c.cc(CH, c.T(9), 11, 52); swell(CH, c.T(9), c.T(12, 3.8), 52, 96);
// L4: the men sing the lament in two octaves (B2-G4)
c.cc(CH, c.T(12, 3), 11, 100);
play(CH, c.T(12, 3), PICK_T.map(([p, d]) => [p, d, 90]), { map: mix });
play(CH, c.T(13), T4_CLOSED, { map: mix, vel: 102, accent: 6, legato: 0.98 });
play(CH, c.T(13), T4_CLOSED, { map: mix, oct: -1, vel: 92, accent: 6, legato: 0.98 });
// L5: the men swell on the E octave into the defiance
hold(CH, 19, 21, ['E3', 'E4'], 90, { gap: 0.5 }); c.cc(CH, c.T(17), 11, 30); swell(CH, c.T(19), c.T(20, 3.4), 30, 100);
// L6 defiance: T in unison (E3-C4)
c.cc(CH, c.T(21), 11, 102);
play(CH, c.T(21), Tflat(0, 4), { oct: -1, vel: 110, accent: 6, legato: 0.97 });
// L6 shine: the Seikilos in two octaves (A2-E4)
c.cc(CH, c.T(25), 11, 124);
play(CH, c.T(25), SK, { oct: -1, velOf: SKV(110), legato: 0.98 });
play(CH, c.T(25), SK, { oct: -2, velOf: SKV(98), legato: 0.98 });
// L7: after the blow the men hold E/B a moment, dying; the hum returns on B/E under the epitaph
hold(CH, 29, 31, ['E3', 'B3'], 90, { at: 0.25 }); c.cc(CH, c.T(29), 11, 100); swell(CH, c.T(29, 0.5), c.T(30, 3.5), 100, 14);
hold(VO, 31, 35, ['B2', 'B3'], 54); c.cc(VO, c.T(31), 11, 30); swell(VO, c.T(31), c.T(32), 30, 70); swell(VO, c.T(34), c.T(34, 3.9), 70, 40);
c.cc(CH, c.T(1), 11, 52); c.cc(VO, c.T(1), 11, 40);

// ---------------------------------------------------------------- brass
// L4: horns double the lament (B3-G4), trombones hold the fixed notes
c.cc(HN, c.T(12, 3), 11, 96);
play(HN, c.T(13), T4_CLOSED, { map: mix, vel: 94, accent: 10, legato: 0.94 });
hold(TB, 13, 15, ['B2', 'E3'], 82); hold(TB, 15, 17, ['B2', 'F3'], 86);
c.cc(TB, c.T(13), 11, 80); swell(TB, c.T(15), c.T(16, 3.9), 80, 108);
// L5: horns and trombones swell the E octave into the defiance
hold(HN, 19, 21, ['E4', 'E3'], 90, { gap: 0.5 }); c.cc(HN, c.T(17), 11, 40); swell(HN, c.T(19), c.T(20, 3.4), 40, 102);
hold(TB, 19, 21, ['E3', 'E2'], 90, { gap: 0.5 }); c.cc(TB, c.T(17), 11, 40); swell(TB, c.T(19), c.T(20, 3.4), 40, 102);
// salpinx: the charge (bar 20) on the natural-trumpet notes
c.line(TP, c.T(20), [['B4', 0.5, 84], ['B4', 0.5, 88], ['E5', 1, 96], ['B4', 0.5, 86], ['B4', 0.5, 90], ['E5', 0.6, 98]], { legato: 0.82 });
c.cc(TP, c.T(20), 11, 100);
// L6 defiance: horns T at pitch; the trombones pound the dochmiac on E2/B2 underneath
c.cc(HN, c.T(21), 11, 104);
play(HN, c.T(21), Tflat(0, 4), { vel: 102, accent: 12, legato: 0.92, pcs: PCS.dorianE });
c.cc(TB, c.T(21), 11, 100);
// the trombones pound the dochmiac on E2/B2; under bar 24's cadence (the celli's D-C-D from beat 2) only on B2
for (let b = 21; b <= 24; b++) {
  for (const [e, d, v] of [[0, 0.45, 86], [1, 0.9, 110], [3, 0.9, 104], [5, 0.45, 88], [6, 0.9, 112]]) {
    for (const k of b === 24 && e >= 3 ? ['B2'] : ['E2', 'B2']) c.note(TB, c.T(b, e / 2), c.q(d), k, v);
  }
}
// L6 shine: horns sing the Seikilos at pitch (A4-E5, the heroic register), trombones an octave below,
// the salpinx doubles its long Es and calls on the final B
c.cc(HN, c.T(25), 11, 124); c.cc(TB, c.T(25), 11, 118);
play(HN, c.T(25), SK, { velOf: SKV(104), legato: 0.95 });
play(TB, c.T(25), SK, { oct: -1, velOf: SKV(98), legato: 0.9 });
c.line(TP, c.T(25, 1), [['E5', 2, 102], ['E5', 2, 106]], { legato: 0.92 });
c.line(TP, c.T(27, 2), [['B4', 2, 98]], { legato: 0.9 });
c.cc(TP, c.T(25), 11, 112);
// L7: the blow on E/B (no third), cut short
for (const [ch, ks, v] of [[HN, ['E4', 'B3'], 108], [TB, ['E3', 'B2'], 108], [ST, ['E3', 'B3', 'E4', 'B4'], 104], [CH, ['E3', 'B3'], 104], [CB, ['E1'], 104], [VC, ['E2'], 100]]) for (const k of ks) c.note(ch, c.T(29), c.q(1.1), k, v);
for (const ch of [HN, TB, ST]) c.cc(ch, c.T(29), 11, 116);
// the close of the song falls back a little (bar 28, beats 2-4) so that the blow lands as a blow
for (const [ch, v] of [[OB, 122], [SH, 108], [HN, 124], [TB, 118], [CH, 124], [ST, 120]]) swell(ch, c.T(28, 2.2), c.T(28, 3.9), v, Math.round(v * 0.78));
c.cc(HN, c.T(1), 11, 104); c.cc(TB, c.T(1), 11, 80); c.cc(TP, c.T(1), 11, 108);

// ---------------------------------------------------------------- strings
// L3: tremolo on the B drone (octave), swelling under the answer: no line moving inside the drone register
// (a climb through C3/D3 there rubbed against the drone B2 and the men's E3/F3); the rise is the men's
// fourth opening to the diminished fifth, the swell and the drums
hold(TR, 9, 13, ['B2', 'B3'], 86, { gap: 0.02 });
c.cc(TR, c.T(9), 11, 30); swell(TR, c.T(9), c.T(12, 3.9), 30, 82);
// L4: strings double the lament an octave above the men and at pitch
c.cc(ST, c.T(12, 3), 11, 90);
play(ST, c.T(13), T4_CLOSED, { map: mix, oct: 1, vel: 82, legato: 1.0, accent: 6 });
play(ST, c.T(13), T4_CLOSED, { map: mix, vel: 76, legato: 1.0, accent: 6 });
// L5: the chromatic turn: the tremolo climbs E F F# A in octaves (two per bar), crescendo
const TURN = [['E3', 'E4'], ['F3', 'F4'], ['F#3', 'F#4'], ['A3', 'A4']];
for (let i = 0; i < 4; i++) for (const k of TURN[i]) c.note(TR, c.T(19 + Math.floor(i / 2), (i % 2) * 2), c.q(i === 3 ? 1.5 : 1.98), k, 94);
c.cc(TR, c.T(17), 11, 30); swell(TR, c.T(19), c.T(20, 3.45), 40, 104);
// the defiance lands: the tremolo strikes the low E octave on the downbeat and dies away over two beats
for (const k of ['E2', 'E3']) c.note(TR, c.T(21), c.q(2.5), k, 112);
c.cc(TR, c.T(21), 11, 112); swell(TR, c.T(21, 0.5), c.T(21, 2.5), 112, 30); c.cc(TR, c.T(1), 11, 30);
// L6: strings T at pitch and the octave (soft), then the Seikilos at pitch and the octave
c.cc(ST, c.T(21), 11, 96);
play(ST, c.T(21), Tflat(0, 4), { vel: 96, legato: 0.96, accent: 6 });
play(ST, c.T(21), Tflat(0, 4), { oct: 1, vel: 78, legato: 0.96, accent: 6 });
// the defiance's downbeat lands accented (expression +16), settling over two and a half beats to the T level
for (const [ch, v] of [[OB, 104], [SH, 94], [HN, 104], [TB, 100], [CH, 102], [ST, 96]]) swell(ch, c.T(21), c.T(21, 2.5), v + 16, v);
c.cc(ST, c.T(25), 11, 120);
play(ST, c.T(25), SK, { velOf: SKV(96), legato: 1.0 });
play(ST, c.T(25), SK, { oct: 1, velOf: SKV(80), legato: 1.0 });
c.cc(ST, c.T(1), 11, 96);

const res = c.write(OUT, {
  title: 'The Kolonos',
  harmonia: 'Mixolydian species on B (B C D E F G A) with enharmonic pykna; chromatic turn E F F# A (bars 19-20); Dorian on E for the defiance (21-24); Phrygian species on E for the Seikilos (25-28); Mixolydian on B again for the epitaph (31-34)',
  quotes: 'Seikilos epitaph line 1 (DAGM 23) in tutti, bars 25-27 (+ composed close, bar 28); the same line re-cast into the Mixolydian on solo aulos, bars 31-33 (+ close, bar 34)',
  sections: sections.map(([b, n], i) => ({ name: n, fromBar: b, toBar: (sections[i + 1]?.[0] ?? c.bars + 1) - 1, startSeconds: +c.secondsOfBar(b).toFixed(3) })),
  species: [
    { name: 'Mixolydian species on B', fromBar: 1, toBar: 16, pcs: PCS.mixolydianB },
    { name: 'Dorian species on E (+ chromatic F# in the turn)', fromBar: 17, toBar: 24, pcs: [...PCS.dorianE, 6] },
    { name: 'Phrygian species on E (Seikilos)', fromBar: 25, toBar: 28, pcs: PCS.phrygianSpeciesE },
    { name: 'Dorian -> Mixolydian (the blow on E/B, the fall)', fromBar: 29, toBar: 30, pcs: PCS.dorianE },
    { name: 'Mixolydian species on B (epitaph)', fromBar: 31, toBar: 34, pcs: PCS.mixolydianB },
  ],
});
console.log(JSON.stringify({ cue: 'lastStand', notes: res.notes, loop: [res.json.loopStartSeconds, res.json.loopEndSeconds], seconds: res.json.loopSeconds, midi: res.midPath }));
