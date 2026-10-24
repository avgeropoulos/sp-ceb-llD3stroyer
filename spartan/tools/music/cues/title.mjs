// title.mjs -> title.mid + title.json: "Dawn at the Hot Gates", the title screen and menus.
//
//   node spartan/tools/music/cues/title.mjs [outDir]   (default $THERMOPYLAE_MUSIC_CACHE/score; deterministic)
//
// TEMPO AND LENGTH  72 BPM, 4/4, 27 bars = 90.000 s loop (27 is a multiple of 3, so the loop is a whole
//   number of 20 ms Opus frames at 72 BPM: 4 320 000 samples at 48 kHz).
// MODE  The ancient Dorian harmonia: the octave species E-E on the white keys, two disjunct descending
//   tetrachords e' d' c' b | a g f e, mese A, final E; diatonic genus. Drones on the fixed notes E and B
//   (and the mese A for one bar). Two changes (metabolai): the Phrygian species E F# G A B C# D (the
//   Seikilos melody's own, Iastian tonos) for the Seikilos, and the chromatic genus (tetrachords
//   E F F# A | B C C# E, stated as descending lines) for the empire. Not the modern church modes.
//   Under the Seikilos the drones stay on E and B: the melody's final E and the mese B of its Iastian tonos
//   (in that two-sharp system the fixed notes are B, C# and F#; E is the final, not a fixed note).
// FORM
//   P1  1-4   Dawn: drone, kithara krousis on the fixed notes, taiko heartbeat, distant salpinx (room for the voice)
//   P2  5-8   The solo aulos sings T, the soundtrack's leitmotif (bars 1-4), over a drone pipe
//   P3  9-12  The light: the Seikilos incipit on the solo aulos, the second aulos joins in heterophony
//   P4 13-16  The empire: dip, then the chromatic genus as falling tetrachord lines (low aulos, trombones,
//             horns), the men's E/F cluster, drums quickening, timpani roll; it crests under P5's downbeat
//             and breathes on its last eighth (the aulos's pickup leads in)
//   P5 17-20  The Three Hundred: T in tutti (horns at pitch, trombones and men an octave below, massed aulos
//             heterophony), processional drums, one level under the climax; the empire's build crests under
//             its downbeat, and each half of the phrase swells and falls
//   P6 21-24  "While you live, shine": the Seikilos sung by the men in octaves with horns, strings and aulos
//             (the climax), then the tutti breaks off on the mese A with one arrival blow
//   P7 25-27  The solo aulos alone completes the cadence to E (T bars 7-8, enharmonic close); the dawn returns
// QUOTED MELODY  Seikilos epitaph, line 1, "Hoson zes, phainou" (stele from Tralles, 1st c. AD; Poehlmann &
//   West, Documents of Ancient Greek Music 2001, no. 23; West, Ancient Greek Music 1992): signs C Z Z K I Z
//   I K O in the Iastian tonos = a e' e' c#' d' e' d' c#' b, 12 chronoi (see motifs.mjs for the reading and
//   its caveat). Everything else (T, the empire lines, the closes) is composed.
// INSTRUMENTS  aulos = Oboe 68 (solo) + Shanai 111 (second player, -6 cents) + English horn 69 (low aulos and
//   drone pipe, +4 cents); kithara = Harp 46; men's chorus = Choir Aahs 52 (E2-A4) + Voice Oohs 53 hum;
//   Horns 60, Trombones 57, salpinx = Trumpet 56 (natural-trumpet notes B and E only); Slow Strings 49,
//   Tremolo Strings 44, Cello 42, Contrabass 43; Taiko 116, Timpani 47; GS Orchestra kit (bank 128 prog 48:
//   concert bass drum, surdo as frame drum, low bongo 61 / open conga 63 as the hand-struck tympanon skin,
//   tambourine as its jingles, castanets as krotala, cymbal).
import { Cue, P, hit, outDir } from './lib.mjs';
import { PICK_T, T, SEIKILOS_1, SEIKILOS_CLOSE, hetero, Tflat, PCS } from './motifs.mjs';

const OUT = outDir();
const c = new Cue({ name: 'title', bpm: 72, meters: [[27, 4]], seed: 0x7171E });

const OB = 0, SH = 1, EH = 2, HP = 3, CH = 4, VO = 5, HN = 6, TB = 7, TP = 8, DR = 9, ST = 10, VC = 11, CB = 12, TK = 13, TI = 14, TR = 15;
const REED = { minKey: P('E3'), maxKey: P('A5') };
c.channel(OB, { name: 'Aulos (oboe)', program: 68, vol: 114, pan: 58, rev: 56, jit: 5, mono: true, ...REED });
c.channel(SH, { name: 'Aulos 2 (shenai)', program: 111, vol: 70, pan: 80, rev: 54, lag: 9, jit: 7, fine: -6, mono: true, ...REED });
c.channel(EH, { name: 'Low aulos / drone pipe (cor anglais)', program: 69, vol: 118, pan: 48, rev: 58, lag: 4, jit: 5, fine: 4, mono: true, ...REED });
c.channel(HP, { name: 'Kithara (harp)', program: 46, vol: 98, pan: 36, rev: 62, jit: 4 });
c.channel(CH, { name: 'Men chorus', program: 52, vol: 106, pan: 64, rev: 74, lag: 16, jit: 7, minKey: P('E2'), maxKey: P('A4') });
c.channel(VO, { name: 'Hum (voice oohs)', program: 53, vol: 96, pan: 70, rev: 76, lag: 14, jit: 6, minKey: P('E2'), maxKey: P('A4') });
c.channel(HN, { name: 'Horns', program: 60, vol: 92, pan: 44, rev: 70, lag: 10, jit: 6 });
c.channel(TB, { name: 'Trombones', program: 57, vol: 94, pan: 86, rev: 62, lag: 8, jit: 5 });
c.channel(TP, { name: 'Salpinx (trumpet)', program: 56, vol: 76, pan: 100, rev: 100, lag: 4, jit: 4, mono: true });
c.channel(DR, { name: 'Orchestral perc (GS kit 48)', program: 48, vol: 106, pan: 64, rev: 56, jit: 2 });
c.channel(ST, { name: 'Strings (slow)', program: 49, vol: 92, pan: 64, rev: 66, lag: 10, jit: 6 });
c.channel(VC, { name: 'Celli', program: 42, vol: 92, pan: 46, rev: 52, lag: 6, jit: 4 });
c.channel(CB, { name: 'Basses', program: 43, vol: 100, pan: 76, rev: 46, lag: 6, jit: 4, minKey: P('E1') });
c.channel(TK, { name: 'Taiko', program: 116, vol: 94, pan: 56, rev: 60, jit: 2 });
c.channel(TI, { name: 'Timpani', program: 47, vol: 100, pan: 40, rev: 60, jit: 2 });
c.channel(TR, { name: 'Tremolo strings', program: 44, vol: 90, pan: 64, rev: 64, lag: 6, jit: 5 });
// HI/LO: hand drums for the tympanon's struck skin (low bongo 61, open conga 63): energy at 300 Hz-3 kHz, audible on phones
const K = { BD: 36, TAMB: 54, CYM: 59, SURDO_M: 86, SURDO_O: 87, CAST: 85, HI: 61, LO: 63 };
// taiko keys: 40 "don" on the strong beats, 43 "ka" on the shorts (GeneralUser's taiko changes little with the key)
const DON = 40, KA = 43;

const sections = [
  [1, 'P1 Dawn: drone, kithara, distant salpinx (speech room)'],
  [5, 'P2 The aulos sings the theme T'],
  [9, 'P3 The light: Seikilos incipit (Phrygian species) on the aulos'],
  [13, 'P4 The empire: chromatic genus, falling tetrachords, drums quicken'],
  [17, 'P5 The Three Hundred: T in tutti'],
  [21, 'P6 While you live, shine: the Seikilos sung in tutti (climax), break-off on the mese A'],
  [25, 'P7 The aulos alone closes on E; the dawn returns'],
];
for (const [b, t] of sections) c.marker(b, t);

const swell = (ch, t0, t1, a, b) => c.ramp(ch, t0, t1, 11, a, b, c.q(0.25));
const hold = (ch, b0, b1, keys, vel, o = {}) => { for (const k of keys) c.note(ch, c.T(b0, o.at ?? 0), c.T(b1, o.end ?? 0) - c.T(b0, o.at ?? 0) - c.q(o.gap ?? 0.06), k, vel, { jit: o.jit }); };

/** a flat melodic line [[pitch, quarters, vel?]] from body tick t; opts: oct, vel, legato, accent (longs louder),
 *  orn (grace from the next species note above on notes >= 2 beats), scoop (aulos slide into long notes), pcs */
function play(ch, t, items, { oct = 0, vel = 90, legato = 0.95, shortLeg = 0.86, accent = 8, orn = false, scoop = false, pcs = PCS.dorianE, velOf = null } = {}) {
  const s = 12 * oct;
  items.forEach(([p, d, v0], i) => {
    const k = P(p) + s, long = d >= 1;
    const v = velOf ? velOf(i, d) : (v0 ?? vel + (long ? accent / 2 : -accent / 2));
    if (orn && d >= 2) { let u = k + 1; while (!pcs.includes(u % 12)) u++; c.note(ch, t - c.q(0.1), c.q(0.09), u, v - 20, { jit: 1 }); }
    else if (scoop && d >= 1.5) c.scoop(ch, t, -0.3, 0.16);
    c.note(ch, t, c.q(d) * (long ? legato : shortLeg), k, v);
    t += c.q(d);
  });
  return t;
}
const tRows = Tflat;

// ---------------------------------------------------------------- P1 Dawn (1-4)
hold(CB, 1, 5, ['E1'], 94); hold(VC, 1, 5, ['E2', 'B2'], 78);
swell(CB, c.T(1), c.T(2), 86, 100); swell(VC, c.T(1), c.T(3), 80, 104); swell(VC, c.T(4), c.T(4, 3.5), 96, 80);
for (const b of [1, 3]) { hit(c, TK, c.T(b), DON, b === 1 ? 70 : 90); hit(c, TK, c.T(b, 0.5), DON, 54); }
for (const b of [2, 4]) hit(c, TK, c.T(b, 2), DON, 60);
for (let b = 1; b <= 4; b++) for (const [q, k, v] of [[0, 'E3', 78], [1, 'B3', 66], [2, 'E4', 72], [3, 'B3', 64]]) c.note(HP, c.T(b, q), c.q(1.8), k, b === 1 ? v - 8 : v);
// distant salpinx at dawn: the fixed notes B and E (the natural trumpet's notes)
c.line(TP, c.T(2, 2), [['B4', 1, 78], ['B4', 1, 82], ['E5', 6, 92]], { legato: 0.9 });
swell(TP, c.T(2, 2), c.T(3, 2), 80, 112); swell(TP, c.T(3, 2), c.T(4, 3.5), 112, 50);
// high string shimmer (air above the voice)
hold(ST, 3, 5, ['E5', 'B5'], 52); swell(ST, c.T(3), c.T(4, 2), 30, 64); swell(ST, c.T(4, 2), c.T(5), 64, 50);

// ---------------------------------------------------------------- P2 (5-8): solo aulos, T bars 1-4
c.cc(OB, c.T(1), 11, 100);
play(OB, c.T(4, 3), PICK_T.map(([p, d]) => [p, d, 80]), { shortLeg: 0.86 });
play(OB, c.T(5), tRows(0, 4), { vel: 92, orn: true, scoop: true, legato: 0.98 });
for (const b of [5, 7]) { swell(OB, c.T(b), c.T(b + 1, 0), 92, 118); swell(OB, c.T(b + 1, 1), c.T(b + 1, 3.6), 118, 96); }
hold(EH, 5, 9, ['E4'], 34); swell(EH, c.T(5), c.T(6), 40, 76);
// drones E | E | A | E under T bars 1-4 (the mese A under T bar 3, as in the tutti)
for (const [b, lo, hi] of [[5, 'E1', ['E2', 'B2']], [6, 'E1', ['E2', 'B2']], [7, 'A1', ['A2', 'E3']], [8, 'E1', ['E2', 'B2']]]) { hold(CB, b, b + 1, [lo], 82, { gap: 0.08 }); hold(VC, b, b + 1, hi, 62, { gap: 0.08 }); }
const KR = ['E3', 'B3', 'E4', 'B3', 'A3', 'B3', 'E4', 'B3'];
const KR_FIX = ['E3', 'B3', 'E4', 'B3', 'E3', 'B3', 'E4', 'B3']; // under the Seikilos: its final E and mese B only (no A under its C#)
for (let b = 5; b <= 12; b++) for (let e = 0; e < 8; e++) c.note(HP, c.T(b, e / 2), c.q(1.2), (b >= 9 ? KR_FIX : KR)[e], e % 4 === 0 ? 66 : 48);
for (const b of [5, 7, 9, 11]) { hit(c, TK, c.T(b), DON, 80); hit(c, TK, c.T(b, 0.5), DON, 50); hit(c, TK, c.T(b, 2), DON, 56); }
// frame drum (soft surdo) on the anapaests from bar 7: a gentle pulse
for (let b = 7; b <= 12; b++) for (const [q, v] of [[0, 62], [1, 38], [1.5, 44], [2, 56], [3, 38], [3.5, 44]]) hit(c, DR, c.T(b, q), q % 2 === 0 ? K.SURDO_O : K.SURDO_M, v);
hold(VO, 7, 8, ['E3', 'A3'], 54); hold(VO, 8, 9, ['E3', 'B3'], 54); swell(VO, c.T(7), c.T(8), 30, 84);

// ---------------------------------------------------------------- P3 (9-12): the Seikilos on the aulos (the light)
// bars 9-11: the incipit (12 chronoi = 12 quarters); bar 12: composed close down through F# to E
c.cc(OB, c.T(9), 11, 92);
play(OB, c.T(9), [...SEIKILOS_1.map(([p, d], i) => [p, d, [84, 98, 94, 86, 88, 96, 88, 84, 90][i]]), ...SEIKILOS_CLOSE.map(([p, d], i) => [p, d, [86, 78, 76, 82][i]])],
  { legato: 0.97, scoop: true, pcs: PCS.phrygianSpeciesE });
for (const [b, q] of [[9, 1], [9, 3]]) c.note(OB, c.T(b, q) - c.q(0.1), c.q(0.09), 'F#5', 70, { jit: 1 }); // aulos graces on the long Es
swell(OB, c.T(9), c.T(10, 0), 92, 108); swell(OB, c.T(10, 3), c.T(12, 3.5), 108, 86);
// the second aulos player answers the incipit in heterophony from bar 11 (soft, a few cents flat)
c.cc(SH, c.T(9), 11, 70);
play(SH, c.T(11), hetero([['D5', 1], ['C#5', 1], ['B4', 2]], PCS.phrygianSpeciesE).map(([p, d]) => [p, d, 62]), { legato: 0.9, shortLeg: 0.9 });
play(SH, c.T(12), hetero(SEIKILOS_CLOSE, PCS.phrygianSpeciesE).map(([p, d]) => [p, d, 60]), { legato: 0.9, shortLeg: 0.9 });
swell(SH, c.T(11), c.T(12, 3.5), 70, 50);
hold(EH, 9, 13, ['B3'], 34);
hold(VO, 9, 13, ['E3', 'B3'], 60); c.cc(VO, c.T(9), 11, 84); swell(VO, c.T(11), c.T(12, 3.8), 84, 60);
hold(ST, 9, 13, ['E3', 'B3', 'E4'], 50); c.cc(ST, c.T(9), 11, 52); swell(ST, c.T(10), c.T(11), 52, 66); swell(ST, c.T(12), c.T(12, 3.8), 66, 40);
hold(CB, 9, 13, ['E1'], 82); hold(VC, 9, 13, ['E2', 'B2'], 60);
swell(CB, c.T(12), c.T(12, 3.9), 100, 70); swell(VC, c.T(12), c.T(12, 3.9), 100, 66);

// ---------------------------------------------------------------- P4 The empire (13-16): chromatic genus
// It starts low and quiet (a dip after the light) and grows to the downbeat of 17.
// Low pedal on the fixed notes, the tremolo on E/B (no sliding chords), all swelling to the tutti.
// (every swell crests below P5's downbeat level, and the empire breathes on its last eighth, where only the
// aulos's pickup and two light drum shorts lead in: the Three Hundred arrive instead of deflating)
hold(TB, 13, 17, ['E2', 'B2'], 84, { gap: 0.5 }); c.cc(TB, c.T(13), 11, 36); swell(TB, c.T(13), c.T(16, 3.4), 36, 100);
hold(CB, 13, 17, ['E1'], 92, { gap: 0.5 }); c.cc(CB, c.T(13), 11, 70); swell(CB, c.T(13), c.T(16, 3.4), 70, 100);
hold(VC, 13, 15, ['E2'], 76); hold(VC, 16, 17, ['E2'], 92, { gap: 0.5 }); c.cc(VC, c.T(13), 11, 60); swell(VC, c.T(13), c.T(16, 3.4), 60, 98);
hold(TR, 13, 17, ['E3', 'B3'], 90, { gap: 0.5 }); c.cc(TR, c.T(13), 11, 24); swell(TR, c.T(13), c.T(16, 3.45), 24, 98);
// the low aulos states the lower chromatic tetrachord, falling A F# F E, and turns back (bars 13-14)
c.cc(EH, c.T(13), 11, 104);
play(EH, c.T(13), [['A4', 1.5, 92], ['F#4', 0.5, 82], ['F4', 1, 90], ['E4', 1, 88], ['E4', 0.5, 78], ['F4', 0.5, 82], ['F#4', 1, 88], ['F4', 1, 86], ['E4', 1, 92]], { legato: 0.96, shortLeg: 0.9 });
swell(EH, c.T(14), c.T(14, 3.9), 104, 80);
// trombones (with the celli an octave below) take the falling tetrachord in bar 15; the horns answer with the
// upper tetrachord e' c#' c' b in bar 16, so the two chromatic tetrachords are heard as lines, not as chords
play(TB, c.T(15), [['A3', 1.5, 96], ['F#3', 0.5, 86], ['F3', 1, 94], ['E3', 1, 98]], { legato: 0.96, shortLeg: 0.92 });
play(VC, c.T(15), [['A2', 1.5, 90], ['F#2', 0.5, 80], ['F2', 1, 88], ['E2', 1, 92]], { legato: 0.96, shortLeg: 0.92 });
c.cc(HN, c.T(15), 11, 84);
play(HN, c.T(16), [['E4', 1.5, 98], ['C#4', 0.5, 86], ['C4', 1, 96], ['B3', 0.5, 100]], { legato: 0.95, shortLeg: 0.92 });
swell(HN, c.T(16), c.T(16, 3.4), 92, 100);
// the men breathe a semitone cluster E3/F3 (the horde), swelling
hold(CH, 15, 17, ['E3', 'F3'], 80, { gap: 0.5 }); c.cc(CH, c.T(15), 11, 30); swell(CH, c.T(15), c.T(16, 3.4), 30, 96);
// drums: the pulse quickens into the downbeat of 17
for (let q = 0; q < 4; q++) hit(c, TK, c.T(13, q), q % 2 ? KA : DON, 50 + q * 2);
for (let e = 0; e < 8; e++) hit(c, TK, c.T(14, e / 2), e % 2 ? KA : DON, 56 + e * 2);
for (let e = 0; e < 8; e++) hit(c, TK, c.T(15, e / 2), e % 2 ? KA : DON, 76 + e * 2);
for (let s = 0; s < 14; s++) hit(c, TK, c.T(16, s / 4), s % 2 ? KA : DON, 80 + s * 1.0);
hit(c, TK, c.T(16, 3.5), KA, 60); hit(c, TK, c.T(16, 3.75), KA, 68);
for (let e = 0; e < 7; e++) hit(c, DR, c.T(16, e / 2), e % 2 ? K.SURDO_M : K.SURDO_O, 62 + e * 3);
for (let q = 0; q < 8; q++) c.note(TI, c.T(13, q / 2), c.q(0.4), 'E2', 46 + q * 2);
for (let i = 0; i < 48; i++) c.note(TI, c.T(15, i / 6), c.q(0.14), 'E2', Math.round(52 + i * 1.3), { jit: 1, vj: 3 });
// (no cymbal swell here: under the sixteenth-note drums it made the Opus decode overshoot the true-peak ceiling by 1.2 dB)

// ---------------------------------------------------------------- P5 The Three Hundred (17-20): T bars 1-4 in tutti
// One level under the climax (expression 88-100 against 114-118 in P6). T in unison and octaves only; drones beneath.
const TT = tRows(0, 4);
const PCK = PICK_T.map(([p, d]) => [p, d, 76]);
c.cc(OB, c.T(16, 3), 11, 98); c.cc(SH, c.T(16, 3), 11, 88);
play(OB, c.T(16, 3), PCK); play(OB, c.T(17), TT, { vel: 94, orn: true, scoop: true });
play(SH, c.T(17), hetero(TT, PCS.dorianE), { vel: 72, legato: 0.9 });
play(HN, c.T(17), TT, { vel: 104, accent: 12, legato: 0.94 });
play(TB, c.T(17), TT, { oct: -1, vel: 96, accent: 12, legato: 0.9 });
play(CH, c.T(17), TT, { oct: -1, vel: 112, accent: 6, legato: 0.98 });
play(ST, c.T(17), TT, { vel: 94, legato: 1.0, accent: 6 });
play(ST, c.T(17), TT, { oct: 1, vel: 80, legato: 1.0, accent: 6 });
// each half of the phrase is shaped like a sung line around its old flat level: the downbeat of 17 lands
// accented, the line swells to T's top (the C5 of bar 18), falls, swells less to bar 19's G and falls into
// bar 20 (one level under P6)
const p5Arcs = (ch, base) => {
  const pts = [[17, 0, 8], [17, 2, 2], [18, 0, 6], [18, 2.5, 2], [19, 0, 0], [19, 2, 2], [20, 1, -4], [20, 3.8, -8]];
  for (let i = 0; i + 1 < pts.length; i++) swell(ch, c.T(pts[i][0], pts[i][1]), c.T(pts[i + 1][0], pts[i + 1][1]), base + pts[i][2], base + pts[i + 1][2]);
};
for (const [ch, v] of [[HN, 98], [TB, 92], [CH, 94], [ST, 88], [OB, 98], [SH, 88]]) p5Arcs(ch, v);
hold(EH, 17, 21, ['E4'], 60); c.cc(EH, c.T(17), 11, 84); // the drone pipe of the massed aulos
// drones: E | E | A | E (the mese A under T bar 3; no third is ever sustained against them)
const DRONE5 = [['E1', 'E2', 'B2'], ['E1', 'E2', 'B2'], ['A1', 'A2', 'E3'], ['E1', 'E2', 'B2']];
for (let i = 0; i < 4; i++) { hold(CB, 17 + i, 18 + i, DRONE5[i].slice(0, 2), 104, { gap: 0.08 }); hold(VC, 17 + i, 18 + i, DRONE5[i].slice(1), 92, { gap: 0.08 }); }
c.cc(CB, c.T(17), 11, 100); c.cc(VC, c.T(17), 11, 96);
// processional drums: DON on 1 and 3 with anapaest pickups, concert BD, frame drum, tympanon on 2 and 4,
// krotala on the shorts of the phrase ends
function procession(b, lv, land = 1) {
  const V = (v) => Math.round(Math.min(127, v * lv));
  // land: the downbeat's great drum of an arrival (bar 17), louder than the procession's
  hit(c, TK, c.T(b, 0), DON, Math.min(127, V(110 * land))); hit(c, TK, c.T(b, 2), DON, V(98));
  hit(c, TK, c.T(b, 1), KA, V(70)); hit(c, TK, c.T(b, 1.5), KA, V(80)); hit(c, TK, c.T(b, 3), KA, V(72)); hit(c, TK, c.T(b, 3.5), KA, V(84));
  if (b % 2) hit(c, DR, c.T(b, 0), K.BD, V(82)); // the concert bass drum is the peakiest sound in the kit: used sparingly
  for (const [q, v] of [[0, 94], [1, 54], [1.5, 64], [2, 86], [3, 54], [3.5, 68]]) hit(c, DR, c.T(b, q), q % 2 === 0 ? K.SURDO_O : K.SURDO_M, V(v));
  for (const q of [1, 3]) hit(c, DR, c.T(b, q), K.TAMB, V(82));
  for (const [q, k, v] of [[1, K.HI, 100], [1.5, K.LO, 112], [3, K.HI, 102], [3.5, K.LO, 116]]) hit(c, DR, c.T(b, q), k, V(v));
}
for (let b = 17; b <= 20; b++) {
  procession(b, 0.82, b === 17 ? 1.3 : 1);
  const r = DRONE5[b - 17][0][0];
  c.note(TI, c.T(b, 0), c.q(1.5), r === 'A' ? 'A2' : 'E2', 90);
  c.note(TI, c.T(b, 2), c.q(1.5), r === 'A' ? 'E2' : 'B2', 78);
}
for (const b of [18, 20]) for (const q of [3, 3.5]) hit(c, DR, c.T(b, q), K.CAST, 92);
hit(c, DR, c.T(17), K.CYM, 84, { dur: c.q(4) });
// salpinx on the fixed notes, answering the phrase (bars 19-20)
c.line(TP, c.T(19, 2), [['B4', 0.5, 84], ['B4', 0.5, 88], ['E5', 1, 96]], { legato: 0.85 });
c.line(TP, c.T(20, 2), [['B4', 0.5, 86], ['B4', 0.5, 90], ['E5', 1.5, 100]], { legato: 0.85 });
c.cc(TP, c.T(19), 11, 104);

// ---------------------------------------------------------------- P6 While you live, shine (21-24): the climax
// The Seikilos in tutti: men in two octaves (A2-E4), horns at pitch (A4-E5), trombones an octave below,
// strings at pitch and the octave, the massed aulos in heterophony; drones on E and B only (the melody's final
// E and the mese B of its Iastian tonos; B is a fixed note there, E is the final), so the melody's C# never
// meets an A: no triads, only the line and its octaves.
const SK = SEIKILOS_1;
const SKV = (base) => (i, d) => base + [0, 6, 4, -2, 0, 4, 0, -2, 2][i];
for (const [ch, v] of [[OB, 116], [SH, 102], [HN, 118], [TB, 114], [CH, 118], [ST, 114]]) c.cc(ch, c.T(21), 11, v);
play(OB, c.T(21), SK, { velOf: SKV(96), legato: 0.97, orn: true, pcs: PCS.phrygianSpeciesE });
play(SH, c.T(21), hetero(SK, PCS.phrygianSpeciesE), { vel: 74, legato: 0.92, shortLeg: 0.9 });
play(HN, c.T(21), SK, { velOf: SKV(106), legato: 0.95 });
play(TB, c.T(21), SK, { oct: -1, velOf: SKV(100), legato: 0.92 });
play(CH, c.T(21), SK, { oct: -1, velOf: SKV(112), legato: 0.98 });
play(CH, c.T(21), SK, { oct: -2, velOf: SKV(100), legato: 0.98 });
play(ST, c.T(21), SK, { velOf: SKV(98), legato: 1.0 });
play(ST, c.T(21), SK, { oct: 1, velOf: SKV(84), legato: 1.0 });
// salpinx doubles the long Es of the melody (its natural note) and calls on the final B
c.line(TP, c.T(21, 1), [['E5', 2, 104], ['E5', 2, 108]], { legato: 0.92 });
c.line(TP, c.T(23, 2), [['B4', 2, 100]], { legato: 0.9 });
c.cc(TP, c.T(21), 11, 112);
hold(EH, 21, 24, ['E4'], 66); // drone pipe
hold(CB, 21, 24, ['E1', 'E2'], 112, { gap: 0.04 }); hold(VC, 21, 24, ['E2', 'B2'], 100, { gap: 0.04 });
// weight under the men (two octaves below the tune): the bass trombone holds the final E2 under the melody
hold(TB, 21, 24, ['E2'], 96, { gap: 0.04 });
c.cc(CB, c.T(21), 11, 116); c.cc(VC, c.T(21), 11, 112);
// drums follow the song's longs: the great drum and bass drum on "son" and "zes" (the two long Es) and the close
const SKON = [0, 1, 3, 5, 6, 7, 8, 9, 10]; // onsets in quarters from bar 21
SKON.forEach((q, i) => {
  const b = 21 + Math.floor(q / 4), beat = q % 4;
  const long = SK[i][1] >= 2;
  hit(c, TK, c.T(b, beat), long || i === 0 ? DON : KA, long ? 100 : 78);
  if (long) { hit(c, DR, c.T(b, beat), K.BD, 76); c.note(TI, c.T(b, beat), c.q(1.5), i === 8 ? 'B2' : 'E2', 100); }
  hit(c, DR, c.T(b, beat), long ? K.SURDO_O : K.SURDO_M, long ? 100 : 70);
});
for (let b = 21; b <= 23; b++) {
  for (const q of [1, 3]) hit(c, DR, c.T(b, q), K.TAMB, 86);
  hit(c, TK, c.T(b, 3.5), KA, 76);
  // the hand drum keeps the procession's anapaests going under the song
  for (const [q, k, v] of [[0.5, K.HI, 70], [1.5, K.LO, 80], [2.5, K.HI, 72], [3.5, K.LO, 82]]) hit(c, DR, c.T(b, q), k, v);
}
// bar 24: the tutti breaks off on the mese A: one arrival blow, then everything dies away over the bar
const ARR = [[OB, ['A4']], [HN, ['A4', 'E4']], [TB, ['A3', 'E3']], [CH, ['A3', 'E3', 'A2']], [ST, ['A4', 'E4', 'A3', 'A5']], [SH, ['A4']], [CB, ['A1']], [VC, ['A2', 'E3']], [EH, ['E4']]];
for (const [ch, ks] of ARR) for (const k of ks) c.note(ch, c.T(24), c.q(3.7), k, ch === OB ? 94 : 96, { jit: 5 });
for (const [ch, v] of [[OB, 112], [SH, 98], [HN, 116], [TB, 110], [CH, 114], [ST, 110], [CB, 112], [VC, 108], [EH, 78]]) { c.cc(ch, c.T(24), 11, v); swell(ch, c.T(24, 0.5), c.T(24, 3.7), v, 18); }
hit(c, TK, c.T(24), DON, 102); hit(c, DR, c.T(24, 0.02), K.BD, 82); c.note(TI, c.T(24), c.q(2), 'A2', 96);
hit(c, DR, c.T(24), K.CYM, 74, { dur: c.q(8) });

// ---------------------------------------------------------------- P7 (25-27): the aulos alone completes the cadence
// T bars 7-8 over the returning E drone; the final E is reached through the enharmonic E+ (F -> E+ -> E)
c.cc(OB, c.T(25), 11, 110);
const CAD = tRows(6, 8);
play(OB, c.T(25), CAD.slice(0, -1), { vel: 86, scoop: true, legato: 0.99 });
const tFin = c.T(26, 1);
c.bend(OB, tFin - c.q(0.01), 0.5); c.glide(OB, tFin + c.q(0.06), tFin + c.q(0.7), 0.5, 0);
c.note(OB, tFin, c.q(3) * 0.98, 'E4', 84);
swell(OB, c.T(26, 1.5), c.T(26, 3.9), 110, 70);
hold(CB, 25, 28, ['E1'], 80); hold(VC, 25, 28, ['E2', 'B2'], 64);
c.cc(CB, c.T(25), 11, 76); c.cc(VC, c.T(25), 11, 70); swell(CB, c.T(25), c.T(26), 76, 96); swell(VC, c.T(25), c.T(26), 70, 96);
swell(CB, c.T(27), c.T(27, 3.8), 96, 86); swell(VC, c.T(27), c.T(27, 3.8), 96, 80);
hold(VO, 25, 27, ['E3', 'B3'], 56); c.cc(VO, c.T(25), 11, 40); swell(VO, c.T(25), c.T(25, 3), 40, 74); swell(VO, c.T(26, 2), c.T(26, 3.9), 74, 30);
// the dawn returns in bar 27: kithara on the fixed notes and a soft taiko pickup into the bar-1 heartbeat
for (const [q, k, v] of [[0, 'E3', 64], [1, 'B3', 54], [2, 'E4', 58], [3, 'B3', 52]]) c.note(HP, c.T(27, q), c.q(1.8), k, v);
for (const [q, k, v] of [[1, 'E3', 52], [2, 'B3', 46], [3, 'E4', 50]]) c.note(HP, c.T(26, q), c.q(2.5), k, v);
hit(c, TK, c.T(25, 0), DON, 70); hit(c, TK, c.T(25, 0.5), DON, 48);
hit(c, TK, c.T(27, 2), DON, 58); hit(c, TK, c.T(27, 2.5), DON, 42);

// restate the bar-1 expressions (every controller that moves in the body returns to bar 1's value at tick 0)
for (const [ch, v] of [[CB, 86], [VC, 80], [ST, 30], [TP, 80], [TB, 50], [HN, 84], [CH, 30], [VO, 30], [EH, 40], [TR, 24], [SH, 70]]) c.cc(ch, c.T(1), 11, v);

const res = c.write(OUT, {
  title: 'Dawn at the Hot Gates',
  harmonia: 'Ancient Dorian species on E (E-E white keys: e d c b | a g f e, mese A), diatonic; metabole to the Phrygian species E F# G A B C# D for the Seikilos (bars 9-12, 21-23) and to the chromatic genus E F F# A | B C C# E (bars 13-16)',
  quotes: 'Seikilos epitaph line 1 (DAGM 23): a e\' e\' c#\' d\' e\' d\' c#\' b, 12 chronoi (1 2 2 1 1 1 1 1 2); solo aulos bars 9-11, tutti bars 21-23',
  sections: sections.map(([b, n], i) => ({ name: n, fromBar: b, toBar: (sections[i + 1]?.[0] ?? c.bars + 1) - 1, startSeconds: +c.secondsOfBar(b).toFixed(3) })),
  species: [
    { name: 'Dorian species on E', fromBar: 1, toBar: 8, pcs: PCS.dorianE },
    { name: 'Phrygian species on E (Seikilos)', fromBar: 9, toBar: 12, pcs: PCS.phrygianSpeciesE },
    { name: 'chromatic genus on E', fromBar: 13, toBar: 16, pcs: PCS.chromaticE },
    { name: 'Dorian species on E', fromBar: 17, toBar: 20, pcs: PCS.dorianE },
    { name: 'Phrygian species on E (Seikilos)', fromBar: 21, toBar: 23, pcs: PCS.phrygianSpeciesE },
    { name: 'Dorian species on E', fromBar: 24, toBar: 27, pcs: PCS.dorianE },
  ],
});
console.log(JSON.stringify({ cue: 'title', notes: res.notes, loop: [res.json.loopStartSeconds, res.json.loopEndSeconds], seconds: res.json.loopSeconds, midi: res.midPath }));
