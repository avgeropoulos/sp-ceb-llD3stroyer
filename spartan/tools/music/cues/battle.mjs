// battle.mjs -> battle.mid + battle.json: "Embaterion", Days 1-2 (fighting and respites).
//
//   node spartan/tools/music/cues/battle.mjs [outDir]   (default $THERMOPYLAE_MUSIC_CACHE/score; deterministic)
//
// TEMPO AND LENGTH  100 BPM; 48 bars = 20 x 4/4 + 8 x 5/4 + 20 x 4/4 = 200 quarter notes = 120.000 s loop
//   (5 760 000 samples at 48 kHz, whole 20 ms Opus frames).
// MODE  The ancient Dorian harmonia: the octave species E-E on the white keys (e' d' c' b | a g f e, two
//   disjunct descending tetrachords), final E, mese A; diatonic genus, with enharmonic colour (E E+ F,
//   B B+ C: quarter tones by pitch bend) on the solo aulos in the breath. Drones on the fixed notes E, A, B.
//   Not the modern church Dorian. White keys only.
// RHYTHM  Anapaests (short-short-long), the marching foot of the Spartan embateria (Thucydides 5.70: they
//   advanced to many aulos players, to keep step), in 4/4; cretic feet (long-short-long) in the 5/4 paean,
//   after the cretic-paeonic rhythm of the Delphic paeans.
// FORM (A/B/C contrast for 20+ minutes; the breath and the build give the loop a shape)
//   S1  1-4   Muster: drums and the low string ostinato only (room for the day-start speech)
//   S2  5-12  Embaterion: the aulos plays T (the soundtrack's theme) in heterophony, drone pipe, kithara
//   S3 13-16  The Kastoreion dance: massed aulos in heterophony in sixteenth-note anapaests around the mese,
//             kithara and pizzicato "massed kitharas", frame drum and krotala; light low end
//      17-20  The phalanx: T's head called and answered by horns and trombones over the men's drone, the
//             drums return, salpinx calls
//   S4 21-28  Paean (5/4, cretic): the men chant on the mese A after the cry "ie!", drone on A only
//   S5 29-32  Breath: taiko heartbeat, drone, a soft solo aulos lament with the enharmonic pyknon (room for the voice)
//   S6 33-36  Build: taiko quarters -> 8ths -> 16ths, the tremolo climbs the tetrachord E F G A, timpani roll;
//             it crests under the othismos and breathes on its last eighth (only the aulos's pickup and two
//             light drum shorts lead in: an anapaest into the downbeat)
//   S7 37-44  Othismos ("the push"): T in tutti, unison and octaves only, each 4-bar phrase shaped; 41-44 one
//             level up by added layers: the second aulos breaks into quick heterophony, a salpinx call answers bar 42
//   S8 45-48  Recoil: the groove one level down, an aulos tag, a taiko fill back to bar 1
// QUOTES  None note for note: no Spartan melody survives. T, the Kastoreion dance and the paean are composed
//   in the documented idiom (anapaestic embateria; Plutarch, Lycurgus 22; the paean vowels "ie paian").
// INSTRUMENTS  aulos = Oboe 68 (lead) + Shanai 111 (second player, -7 cents) + English horn 69 (low aulos and
//   drone pipe, +5 cents); kithara = Harp 46 + Pizzicato strings 45 (massed kitharas); men's chorus = Choir
//   Aahs 52 (E2-A4); Horns 60, Trombones 57, salpinx = Trumpet 56 (B and E only); Strings 48, Tremolo 44,
//   Cello 42, Contrabass 43; Taiko 116 (key 40 deep "don", 43 "ka"), Timpani 47; GS Orchestra kit (concert bass
//   drum 36, surdo 86/87 as frame drum, low bongo 61 / open conga 63 as the hand-struck tympanon skin,
//   tambourine 54 as its jingles, castanets 85 as krotala, cymbal 59).
import { Cue, P, hit, outDir } from './lib.mjs';
import { PICK_T, T, PAEAN, KASTOREION, hetero, Tflat, PCS } from './motifs.mjs';

const OUT = outDir();
const c = new Cue({ name: 'battle', bpm: 100, meters: [[20, 4], [8, 5], [20, 4]], seed: 0x5A47A });

// ---------------------------------------------------------------- orchestra
const OB = 0, SH = 1, EH = 2, HP = 3, CH = 4, PZ = 5, HN = 6, TB = 7, TP = 8, DR = 9, ST = 10, VC = 11, CB = 12, TK = 13, TI = 14, TR = 15;
const REED = { minKey: P('E3'), maxKey: P('A5') };
c.channel(OB, { name: 'Aulos (oboe)', program: 68, vol: 124, pan: 52, rev: 46, lag: 0, jit: 4, mono: true, ...REED });
c.channel(SH, { name: 'Aulos 2 (shenai)', program: 111, vol: 78, pan: 80, rev: 44, lag: 7, jit: 6, fine: -7, mono: true, ...REED });
// the cor anglais stops at F5 (written C6, the top of its range)
c.channel(EH, { name: 'Low aulos / drone pipe (cor anglais)', program: 69, vol: 88, pan: 62, rev: 52, lag: 4, jit: 4, fine: 5, mono: true, minKey: P('E3'), maxKey: P('F5') });
c.channel(HP, { name: 'Kithara (harp)', program: 46, vol: 106, pan: 36, rev: 48, jit: 3 });
c.channel(CH, { name: 'Men chorus', program: 52, vol: 112, pan: 64, rev: 68, lag: 14, jit: 6, minKey: P('E2'), maxKey: P('A4') });
c.channel(PZ, { name: 'Massed kitharas (pizzicato)', program: 45, vol: 100, pan: 84, rev: 44, jit: 4 });
c.channel(HN, { name: 'Horns', program: 60, vol: 96, pan: 42, rev: 62, lag: 10, jit: 5 });
c.channel(TB, { name: 'Trombones', program: 57, vol: 94, pan: 86, rev: 56, lag: 8, jit: 4 });
c.channel(TP, { name: 'Salpinx (trumpet)', program: 56, vol: 78, pan: 98, rev: 84, lag: 4, jit: 3, mono: true });
c.channel(DR, { name: 'Orchestral perc (GS kit 48)', program: 48, vol: 114, pan: 64, rev: 42, jit: 1.5 });
c.channel(ST, { name: 'Strings', program: 48, vol: 92, pan: 64, rev: 56, lag: 8, jit: 5 });
c.channel(VC, { name: 'Celli', program: 42, vol: 88, pan: 46, rev: 38, lag: 4, jit: 3 });
c.channel(CB, { name: 'Basses', program: 43, vol: 94, pan: 78, rev: 34, lag: 4, jit: 3, minKey: P('E1') });
c.channel(TK, { name: 'Taiko', program: 116, vol: 92, pan: 56, rev: 44, jit: 1.5 });
c.channel(TI, { name: 'Timpani', program: 47, vol: 102, pan: 40, rev: 50, jit: 2 });
c.channel(TR, { name: 'Tremolo strings', program: 44, vol: 94, pan: 64, rev: 60, lag: 6, jit: 5 });

// HI/LO: the hand drums standing in for the tympanon's struck skin (HI = low bongo 61, LO = open conga 63): measured on
// GeneralUser, most of their energy lies between 300 Hz and 3 kHz, so the march stays audible on a phone
// speaker without adding rumble or competing with the swords above 3.5 kHz
const K = { BD: 36, CAST: 85, TAMB: 54, CYM: 59, SURDO_M: 86, SURDO_O: 87, HI: 61, LO: 63 };
// taiko keys: 40 "don" on the longs, 43 "ka" on the shorts (GeneralUser's taiko changes little with the key)
const DON = 40, KA = 43;

const sections = [
  [1, 'S1 Muster: drums + ostinato (speech room)'],
  [5, 'S2 Embaterion: T on the aulos in heterophony'],
  [13, 'S3 Kastoreion dance (massed aulos), then the phalanx calls T\'s head'],
  [21, 'S4 Paean (5/4 cretic): the men chant on the mese A'],
  [29, 'S5 Breath: drone + heartbeat + enharmonic aulos lament (speech room)'],
  [33, 'S6 Build: drums double, tremolo climbs E F G A'],
  [37, 'S7 Othismos: T in tutti (37-40 one level down, 41-44 full: quick heterophony, salpinx)'],
  [45, 'S8 Recoil: aulos tag, drums lead back'],
];
for (const [b, t] of sections) c.marker(b, t);

// intensity per bar (0..1) drives drum velocities
const lvl = (b) => {
  if (b <= 4) return 0.7 + 0.03 * (b - 1);
  if (b <= 12) return 0.66 + 0.01 * (b - 5);
  if (b <= 16) return 0.5;
  if (b <= 20) return 0.72 + 0.04 * (b - 17);
  if (b <= 28) return 0.74 + 0.015 * (b - 21);
  if (b <= 32) return 0.2;
  if (b <= 36) return 0.45 + 0.17 * (b - 33);
  if (b <= 40) return 0.88;
  if (b <= 44) return 0.97;
  return 0.78 - 0.05 * (b - 45);
};
const V = (b, v) => Math.max(1, Math.min(127, Math.round(v * (0.55 + 0.45 * lvl(b)))));

// ---------------------------------------------------------------- helpers
const swell = (ch, t0, t1, a, b) => c.ramp(ch, t0, t1, 11, a, b, c.q(0.25));
const sustain = (ch, b0, b1, keys, vel, o = {}) => { for (const k of keys) c.note(ch, c.T(b0, o.at ?? 0), c.T(b1, o.end ?? 0) - c.T(b0, o.at ?? 0) - c.q(o.gap ?? 0.05), k, vel, { jit: o.jit }); };
/** a flat line [[pitch, quarters, vel?]] from tick t; longs accented; orn = grace from the species note above on
 *  notes of 2+ beats; enhClose = the last note reached through its quarter-tone-sharp neighbour (aulos bend) */
function play(ch, t, items, { oct = 0, vel = 92, legato = 0.92, shortLeg = 0.78, accent = 10, orn = false, enhClose = false, pcs = PCS.dorianE } = {}) {
  const s = 12 * oct;
  items.forEach(([p, d, v0], i) => {
    const k = P(p) + s, long = d >= 1;
    const v = v0 ?? vel + (long ? accent / 2 : -accent / 2);
    if (orn && d >= 2) { let u = k + 1; while (!pcs.includes(u % 12)) u++; c.note(ch, t - c.q(0.08), c.q(0.07), u, v - 18, { jit: 1 }); }
    if (enhClose && i === items.length - 1) { c.bend(ch, t - c.q(0.01), 0.5); c.glide(ch, t + c.q(0.05), t + c.q(0.6), 0.5, 0); }
    c.note(ch, t, c.q(d) * (long ? legato : shortLeg), k, v);
    t += c.q(d);
  });
  return t;
}
const pickup = (ch, b, oct, vel) => play(ch, c.T(b - 1, 3), PICK_T.map(([p, d]) => [p, d, vel]), { oct, shortLeg: 0.8 });
/**
 * S7's expression (CC11) for a melodic channel: each 4-bar phrase is shaped like a sung line around `base`
 * (the phrase's mean level). The arrival (37) lands accented, the line swells to its top (T's C5 in bar 38;
 * T' starts on its peak E5 in 41 and swells again to C5 in 42), then falls into the phrase end (40, 44).
 * 41-44 sit `up` above 37-40 (the terrace), with the added layers on top.
 */
function othismosArcs(ch, base, up = 14) {
  const pts = [[37, 0, 14], [37, 2, 4], [38, 0, 6], [38, 2.5, 4], [39, 2, -2], [40, 3.5, -8],
    [41, 0, 8 + up], [41, 2, 2 + up], [42, 0, 6 + up], [42, 2.5, 4 + up], [43, 2, -2 + up], [44, 3.5, -8 + up]];
  for (let i = 0; i + 1 < pts.length; i++) {
    const [b0, q0, v0] = pts[i], [b1, q1, v1] = pts[i + 1];
    if (b1 === 41 && q1 === 0) { swell(ch, c.T(b0, q0), c.T(40, 3.9), base + v0, base + v0 - 2); continue; }
    swell(ch, c.T(b0, q0), c.T(b1, q1), base + v0, base + v1);
  }
}

// ---------------------------------------------------------------- drums
// 4/4 groove: anapaests across the bar, DON . ka ka DON . ka ka (8th positions 0, 2, 3, 4, 6, 7).
// tamb: tympanon jingles on the off-beat 8ths at velocity tv (never sixteenths: the sword and shield SFX own
// the band above 3.5 kHz); castEnd: krotala only on the anapaest of the bar's last beat (phrase ends).
function groove44(b, { bd = false, tamb = 0, tv = 92, castEnd = false, surdo = true, dense = false, don = 1, hand = 0, land = 1 } = {}) {
  const t = (e) => c.T(b, e / 2); // e in 8ths
  // land: the downbeat's great drum of an arrival (the othismos), louder than the groove's
  hit(c, TK, t(0), DON, Math.min(127, V(b, 100 * don * land))); hit(c, TK, t(4), DON, V(b, 90 * don));
  hit(c, TK, t(2), KA, V(b, 72)); hit(c, TK, t(3), KA, V(b, 84));
  hit(c, TK, t(6), KA, V(b, 74)); hit(c, TK, t(7), KA, V(b, 90));
  if (dense) { hit(c, TK, t(5), KA, V(b, 56)); hit(c, TK, t(1), KA, V(b, 52)); }
  if (surdo) {
    const acc = [88, 46, 72, 60, 80, 46, 72, 64];
    for (let e = 0; e < 8; e++) hit(c, DR, t(e), e % 4 === 0 ? K.SURDO_O : K.SURDO_M, V(b, acc[e]));
  }
  if (bd) hit(c, DR, t(0), K.BD, Math.min(127, V(b, 80 * (land > 1 ? 1.08 : 1)))); // the concert bass drum is the peakiest sound in the kit: kept light
  if (hand) for (const [e, k, v] of [[2, K.HI, 100], [3, K.LO, 112], [6, K.HI, 102], [7, K.LO, 118]]) hit(c, DR, t(e), k, V(b, v * hand));
  if (tamb) for (const e of [1, 3, 5, 7]) hit(c, DR, t(e), K.TAMB, V(b, tv));
  if (castEnd) for (const [e, v] of [[6, 84], [6.5, 72], [7, 96]]) hit(c, DR, c.T(b, e / 2), K.CAST, V(b, v));
}
// 5/4 cretic (long-short-long = half, quarter, half): DON(0) ka(2) DON(3) + pickup ka(4.5)
function groove54(b, { cast = false } = {}) {
  const t = (q) => c.T(b, q);
  hit(c, TK, t(0), DON, V(b, 104)); hit(c, TK, t(2), KA, V(b, 86)); hit(c, TK, t(3), DON, V(b, 94));
  hit(c, TK, t(4.5), KA, V(b, 78));
  hit(c, DR, t(0), K.BD, V(b, 80));
  for (const [q, k, v] of [[0, K.SURDO_O, 92], [1, K.SURDO_M, 56], [2, K.SURDO_M, 84], [3, K.SURDO_O, 96], [4, K.SURDO_M, 70], [4.5, K.SURDO_M, 58]]) hit(c, DR, t(q), k, V(b, v));
  if (cast) for (let e = 0; e < 10; e++) hit(c, DR, t(e / 2), K.CAST, V(b, [0, 4, 6].includes(e) ? 96 : 64));
  for (const [q, k, v] of [[2, K.HI, 104], [4, K.LO, 100], [4.5, K.LO, 112]]) hit(c, DR, t(q), k, V(b, v));
}
function taikoFill(b, from = 2, to = 4, v0 = 80, v1 = 116, step = 0.25) {
  const n = Math.round((to - from) / step);
  for (let i = 0; i < n; i++) hit(c, TK, c.T(b, from + i * step), i % 2 ? KA : DON, Math.round(v0 + (v1 - v0) * i / n));
}
// the dance (13-16): no great drum or bass drum; frame drum on the beats, a light taiko "ka" on the anapaests
function danceDrums(b) {
  for (const [q, k, v] of [[0, K.SURDO_O, 90], [1, K.SURDO_M, 62], [2, K.SURDO_O, 80], [3, K.SURDO_M, 66]]) hit(c, DR, c.T(b, q), k, v);
  for (const q of [0.5, 0.75, 2.5, 2.75]) hit(c, TK, c.T(b, q), KA, q % 1 === 0.5 ? 52 : 60);
  for (const q of [1, 3]) hit(c, DR, c.T(b, q), K.TAMB, 70);
  if (b % 2 === 0) for (const [q, v] of [[3, 70], [3.25, 62], [3.5, 82]]) hit(c, DR, c.T(b, q), K.CAST, v);
}

// S1 Muster 1-4 (krotala only at the phrase end), S2 5-12
for (let b = 1; b <= 12; b++) {
  groove44(b, { bd: b === 1 || b === 5 || b === 9, tamb: 1, tv: b <= 4 ? 88 : 84, castEnd: b === 4 || b === 8 || b === 12, dense: b >= 9 && b <= 11, hand: b >= 5 ? 0.8 : 0 });
}
taikoFill(4, 3, 4, 70, 100);
hit(c, TK, c.T(8, 3.5), KA, 80); hit(c, TK, c.T(8, 3.75), KA, 92);
taikoFill(12, 3, 4, 70, 100);
// S3 the dance 13-16, then the phalanx 17-20 (the groove returns and grows)
for (let b = 13; b <= 16; b++) danceDrums(b);
hit(c, DR, c.T(13, 0), K.CYM, 70, { dur: c.q(4) });
for (let b = 17; b <= 20; b++) groove44(b, { bd: b === 17 || b === 19, tamb: 1, tv: 84, castEnd: b === 18, dense: b === 19, hand: 0.9 });
hit(c, TK, c.T(16, 3.5), KA, 76); hit(c, TK, c.T(16, 3.75), KA, 88);
// bar 20: triplet fill into the 5/4 paean
for (let i = 0; i < 6; i++) hit(c, TK, c.T(20, 2 + i / 3), i % 3 ? KA : DON, 88 + i * 4);
// S4 Paean 21-28 (5/4): no jingles; krotala only in the second half
for (let b = 21; b <= 28; b++) groove54(b, { cast: b >= 25 });
hit(c, DR, c.T(21, 0), K.CYM, 98, { dur: c.q(4) });
// S5 Breath 29-32: heartbeat on deep taiko only (82 Hz: below the voice), a soft frame drum under it
for (let b = 29; b <= 32; b++) {
  hit(c, TK, c.T(b, 0), DON, b === 29 ? 96 : 66); hit(c, TK, c.T(b, 0.5), DON, b === 29 ? 62 : 48);
  if (b < 32) { hit(c, TK, c.T(b, 2), DON, 58); hit(c, TK, c.T(b, 2.5), DON, 42); }
  hit(c, DR, c.T(b, 0.5), K.SURDO_M, 40);
}
// S6 Build 33-36: quarters -> 8ths -> 16ths, crescendo
for (let q = 0; q < 4; q++) hit(c, TK, c.T(33, q), DON, 70 + q * 4);
for (let e = 0; e < 8; e++) hit(c, TK, c.T(34, e / 2), e % 2 ? KA : DON, 70 + e * 2);
for (let s = 0; s < 16; s++) hit(c, TK, c.T(35, s / 4), s % 4 ? KA : DON, 74 + s * 1.4);
// bar 36: 16ths to beat 3.5, then the breath: two light shorts (ka ka) are the anapaest into the downbeat
for (let s = 0; s < 14; s++) hit(c, TK, c.T(36, s / 4), s % 2 ? KA : DON, 84 + Math.min(s, 10) * 1.2);
hit(c, TK, c.T(36, 3.5), KA, 64); hit(c, TK, c.T(36, 3.75), KA, 72);
for (let e = 0; e < 8; e++) hit(c, DR, c.T(34, e / 2), K.SURDO_M, 50 + e * 3);
for (let s = 0; s < 16; s++) hit(c, DR, c.T(35, s / 4), s % 4 ? K.SURDO_M : K.SURDO_O, 60 + s * 2);
for (let s = 0; s < 14; s++) hit(c, DR, c.T(36, s / 4), s % 4 ? K.SURDO_M : K.SURDO_O, 72 + s * 1.6);
for (let e = 0; e < 7; e++) hit(c, DR, c.T(36, e / 2), K.TAMB, 60 + e * 2);
// suspended-cymbal swell (soft repeated strokes) into bar 37, capped at 60: it crests under the othismos's
// downbeat (and hot rolls overshoot the codec's true peak)
for (let s = 0; s < 14; s++) hit(c, DR, c.T(36, 1.4 + s / 8), K.CYM, Math.round(22 + s * 2.6), { jit: 1 });
// S7 Othismos 37-44: tympanon in off-beat 8ths, krotala at the phrase ends only
for (let b = 37; b <= 44; b++) groove44(b, { bd: true, tamb: 1, tv: 86, castEnd: b === 40 || b === 44, dense: b % 2 === 0, don: b <= 40 ? 0.9 : 1, hand: b <= 40 ? 0.95 : 0.98, land: b === 37 ? 1.25 : 1 });
hit(c, DR, c.T(37, 0), K.CYM, 100, { dur: c.q(4) });
hit(c, DR, c.T(41, 0), K.CYM, 106, { dur: c.q(4) });
taikoFill(40, 3, 4, 92, 112);
taikoFill(44, 2, 4, 86, 116);
// S8 Recoil 45-48
for (let b = 45; b <= 48; b++) groove44(b, { bd: b === 45, tamb: b <= 46 ? 1 : 0, tv: 78, castEnd: b === 46, hand: 0.85 });
taikoFill(48, 2, 4, 64, 104);

// timpani (E2 = 40, B2 = 47, A2 = 45)
for (let b = 17; b <= 20; b++) { c.note(TI, c.T(b, 0), c.q(1), 'E2', V(b, 100)); if (b % 2 === 0) c.note(TI, c.T(b, 2), c.q(1), 'B2', V(b, 86)); }
for (let b = 21; b <= 28; b++) { c.note(TI, c.T(b, 0), c.q(1.5), 'A2', V(b, 104)); c.note(TI, c.T(b, 3), c.q(1.5), 'A2', V(b, 92)); }
// timpani roll through bars 35-36, stopping at bar 36 beat 3 (the drums' 16ths take the last beat to the breath;
// the roll's strokes landing on the cymbal swell's made the loop's peakiest moment)
for (let i = 0; i < 56; i++) c.note(TI, c.T(35, i / 8), c.q(0.12), 'E2', Math.round(44 + i * 0.9), { jit: 1, vj: 4 });
// beat 2 on B2 in every bar: in the even bars the celli's ostinato is on D2 there, and E2 against it is mud
for (let b = 37; b <= 44; b++) { c.note(TI, c.T(b, 0), c.q(1), 'E2', b <= 40 ? 96 : 104); c.note(TI, c.T(b, 2), c.q(1), 'B2', 88); }
c.note(TI, c.T(45, 0), c.q(2), 'E2', 100);

// ---------------------------------------------------------------- low strings: ostinato on the E pedal, anapaest accents
const OST_A = ['E2', 'E2', 'E2', 'E2', 'E2', 'E2', 'F2', 'E2'];
const OST_B = ['E2', 'E2', 'E2', 'E2', 'D2', 'D2', 'C2', 'D2'];
const ACC = [104, 62, 80, 70, 98, 62, 82, 74];
/** cello 8ths, basses on the two longs (lowE: the E1 octave, only where the drums need the weight), and the
 *  massed kitharas (pizzicato) on the accents, which give the sustained cello patch a real attack */
function ostinato(b, v = 1, alt = false, lowE = true) {
  const pat = alt && b % 2 === 0 ? OST_B : OST_A;
  for (let e = 0; e < 8; e++) c.note(VC, c.T(b, e / 2), c.q(0.38), pat[e], Math.round(ACC[e] * v * 0.84));
  for (const e of [0, 3, 4, 7]) c.note(PZ, c.T(b, e / 2), c.q(0.4), P(pat[e]) + 24, Math.round(ACC[e] * v * 0.9));
  const bass = lowE ? 'E1' : 'E2';
  // beat 2: in the OST_B bars the celli are on D2 there, so the basses take the D too (E against D at 73/82 Hz is mud)
  c.note(CB, c.T(b, 0), c.q(0.9), bass, Math.round(88 * v)); c.note(CB, c.T(b, 2), c.q(0.9), pat === OST_B ? 'D2' : bass, Math.round(76 * v));
}
for (let b = 1; b <= 12; b++) ostinato(b, b <= 4 ? 0.88 : 0.95, b >= 5, b <= 4);
for (let b = 17; b <= 20; b++) ostinato(b, 0.9 + 0.02 * (b - 17), true, false);
for (let b = 37; b <= 48; b++) ostinato(b, b <= 40 ? 0.98 : b <= 44 ? 1.04 : 0.9, true, true);
// the dance (13-16): pizzicato and the basses on the fixed notes E and B only, no cello ostinato
for (let b = 13; b <= 16; b++) {
  for (const [q, k, v] of [[0, 'E2', 92], [1, 'B2', 70], [2, 'E3', 82], [3, 'B2', 72]]) c.note(PZ, c.T(b, q), c.q(0.5), k, v);
  c.note(CB, c.T(b, 0), c.q(1.8), 'E2', 70); c.note(CB, c.T(b, 2), c.q(1.8), 'E2', 62);
}
// paean: drone on the mese A alone (no fifth: the chant's C never meets an A-E dyad), cretic re-articulation
for (let b = 21; b <= 28; b++) {
  c.note(VC, c.T(b, 0), c.q(1.9), 'A2', 76); c.note(VC, c.T(b, 0), c.q(1.9), 'A3', 50);
  c.note(VC, c.T(b, 3), c.q(1.9), 'A2', 70); c.note(VC, c.T(b, 3), c.q(1.9), 'A3', 46);
  c.note(VC, c.T(b, 2), c.q(0.8), 'A2', 60);
  c.note(CB, c.T(b, 0), c.q(1.9), 'A1', 86); c.note(CB, c.T(b, 3), c.q(1.9), 'A1', 78);
  for (const [q, v] of [[0, 84], [2, 62], [3, 76]]) c.note(PZ, c.T(b, q), c.q(0.5), 'A2', v);
}
// breath + build: bass drone E1, celli E2 (+ B2 in the breath; E octaves only in the build, under the climbing line)
sustain(CB, 29, 33, ['E1'], 88);
sustain(VC, 29, 33, ['E2', 'B2'], 58); c.cc(VC, c.T(29), 11, 90);
sustain(CB, 33, 37, ['E1'], 94, { gap: 0.5 });
sustain(VC, 33, 35, ['E2'], 70); sustain(VC, 35, 37, ['E2', 'E3'], 92, { gap: 0.5 });
swell(VC, c.T(33), c.T(36, 3.9), 50, 108); c.cc(VC, c.T(37), 11, 124);
swell(CB, c.T(29), c.T(31), 70, 96); swell(CB, c.T(33), c.T(36, 3.9), 80, 110);
c.cc(CB, c.T(37), 11, 120); c.cc(VC, c.T(1), 11, 124); c.cc(CB, c.T(1), 11, 120);
c.cc(VC, c.T(17), 11, 116); c.cc(CB, c.T(13), 11, 100); c.cc(CB, c.T(17), 11, 116);

// ---------------------------------------------------------------- kithara (harp): krousis on the fixed notes
const KR = ['E3', 'B3', 'E4', 'B3', 'A3', 'B3', 'E4', 'B3'];
const KR_EB = ['E3', 'B3', 'E4', 'B3', 'E3', 'B3', 'E4', 'B3'];
for (let b = 5; b <= 12; b++) for (let e = 0; e < 8; e++) c.note(HP, c.T(b, e / 2), c.q(0.9), KR[e], e % 4 === 0 ? 80 : 56);
for (let b = 13; b <= 16; b++) for (let e = 0; e < 8; e++) c.note(HP, c.T(b, e / 2), c.q(0.8), KR_EB[e], e % 4 === 0 ? 84 : 62);
for (let b = 21; b <= 28; b++) for (const [q, k, v] of [[0, 'A2', 86], [1, 'B2', 60], [2, 'A3', 74], [3, 'A2', 82], [4, 'A3', 64], [4.5, 'B3', 58]]) c.note(HP, c.T(b, q), c.q(1), k, v);
for (let b = 37; b <= 46; b++) for (let e = 0; e < 8; e++) c.note(HP, c.T(b, e / 2), c.q(0.8), KR[e], e % 4 === 0 ? 92 : 70);

// ---------------------------------------------------------------- aulos
// S2: T on the oboe, the shenai in heterophony (its own turns on the longs, a few cents flat, a little late),
// cor anglais drone pipe on the fixed notes
const TT = Tflat(0, 8);
pickup(OB, 5, 0, 88); play(OB, c.T(5), TT, { vel: 102, orn: true, enhClose: true });
play(SH, c.T(5), hetero(TT, PCS.dorianE), { vel: 74, legato: 0.86, shortLeg: 0.8 });
sustain(EH, 5, 9, ['E4'], 56); sustain(EH, 9, 13, ['B3'], 54);
swell(EH, c.T(5), c.T(6), 40, 80); c.cc(EH, c.T(12, 3), 11, 70);
for (const b of [5, 9]) { swell(OB, c.T(b), c.T(b + 1, 2), 88, 110); swell(OB, c.T(b + 2), c.T(b + 3, 3), 110, 92); }
// S3: the Kastoreion dance on the massed aulos (oboe, shenai heterophony, cor anglais an octave below from bar 15)
const KD = KASTOREION.flat();
c.cc(OB, c.T(13), 11, 100); c.cc(SH, c.T(13), 11, 92); c.cc(EH, c.T(13), 11, 80);
play(OB, c.T(13), KD, { vel: 96, accent: 12, legato: 0.9, shortLeg: 0.8 });
play(SH, c.T(13), hetero(KD, PCS.dorianE, { quick: true }), { vel: 72, accent: 10, legato: 0.86, shortLeg: 0.78 });
play(EH, c.T(15), KASTOREION.slice(2).flat(), { oct: -1, vel: 70, accent: 10, legato: 0.88, shortLeg: 0.8 });
swell(OB, c.T(15), c.T(16, 2), 100, 112); swell(OB, c.T(16, 2), c.T(16, 3.9), 112, 96);
// S4: the cor anglais plays the chant an octave up in heterophony (bars 25-28); at its ceiling F5 the turn dips
c.cc(EH, c.T(25), 11, 76);
play(EH, c.T(25), hetero(PAEAN.slice(4).flat().map(([p, d]) => [P(p) + 12, d]), PCS.dorianE, { maxKey: P('F5') }), { vel: 66, legato: 0.9, shortLeg: 0.85, accent: 6 });
// S5 Breath: solo aulos lament with the enharmonic pyknon (E E+ F, B B+ C) in bars 30-31 only, soft (expression
// <= 60): the breath is the loop's speech room, so the 200 Hz-3.5 kHz band stays under -30 dBFS in bars 29-32
c.cc(OB, c.T(29), 11, 52);
c.line(OB, c.T(30), [
  ['E5', 1.5, 78], ['E5', 0.5, 70, { qt: 1 }], ['F5', 1, 84], ['E5', 1, 76, { qt: 1 }],
  ['E5', 0.5, 72], ['D5', 0.5, 70], ['C5', 0.5, 68], ['B4', 0.5, 70], ['A4', 1, 74], ['B4', 1, 66, { qt: 1 }],
], { legato: 0.98 });
c.line(OB, c.T(32), [['B4', 2, 62]], { legato: 0.95 });
swell(OB, c.T(30), c.T(30, 3), 52, 60); swell(OB, c.T(31), c.T(32, 2), 60, 46);
// S6 build: aulos trill E5-F5 crescendo (bar 36)
for (let s = 0; s < 12; s++) c.note(OB, c.T(36, s / 4), c.q(0.24), s % 2 ? 'F5' : 'E5', 70 + s * 2, { jit: 2 });
swell(OB, c.T(36), c.T(36, 3.9), 70, 104);
// S7: T at pitch on three aulos parts: oboe, shenai heterophony, cor anglais an octave below. In 41-44 the
// shenai breaks into quick heterophony (a turn on every one-beat long): the climax adds a layer S2 lacks.
pickup(OB, 37, 0, 84); play(OB, c.T(37), TT, { vel: 96, orn: true });
play(SH, c.T(37), hetero(Tflat(0, 4), PCS.dorianE), { vel: 74, legato: 0.82, shortLeg: 0.78 });
play(SH, c.T(41), hetero(Tflat(4, 8), PCS.dorianE, { quick: true }), { vel: 78, legato: 0.82, shortLeg: 0.78 });
play(EH, c.T(37), TT, { oct: -1, vel: 72, legato: 0.88 });
for (const [ch, base] of [[OB, 100], [SH, 94], [EH, 80]]) othismosArcs(ch, base);
// S8: aulos tag (T bars 1-2 at pitch, a falling close), then drums only
c.cc(OB, c.T(45), 11, 100);
pickup(OB, 45, 0, 80);
play(OB, c.T(45), [...Tflat(0, 2), ['A4', 1, 82], ['G4', 0.5, 74], ['F4', 0.5, 72], ['E4', 2, 78]], { vel: 86, orn: true, legato: 0.9, enhClose: true });
swell(OB, c.T(47), c.T(48, 3.5), 100, 50);
c.cc(OB, c.T(1), 11, 110); c.cc(SH, c.T(1), 11, 110); c.cc(EH, c.T(1), 11, 70);

// ---------------------------------------------------------------- brass
// S3 phalanx (17-20): T's head called by the horns and answered an octave lower by the trombones, then both
// in octaves (T bars 2-3), over the men's drone. Antiphony, not chords.
c.cc(HN, c.T(16, 3), 11, 104); c.cc(TB, c.T(17, 3), 11, 100);
pickup(HN, 17, 0, 90); play(HN, c.T(17), Tflat(0, 1), { vel: 100, accent: 14, legato: 0.9 });
pickup(TB, 18, -1, 90); play(TB, c.T(18), Tflat(0, 1), { oct: -1, vel: 98, accent: 14, legato: 0.88 });
play(HN, c.T(19), Tflat(1, 3), { vel: 104, accent: 14, legato: 0.9 });
play(TB, c.T(19), Tflat(1, 3), { oct: -1, vel: 100, accent: 14, legato: 0.86 });
swell(HN, c.T(19), c.T(20, 2), 104, 122); swell(TB, c.T(19), c.T(20, 2), 100, 118);
// paean: horns double the chant softly; trombones swell the mese octave
c.cc(HN, c.T(21), 11, 78); c.cc(TB, c.T(21), 11, 70);
for (const b of [25, 27]) sustain(TB, b, b + 2, ['A2', 'A3'], 82);
swell(TB, c.T(25), c.T(28, 4), 64, 108);
// build: horns and trombones swell the E octaves
sustain(HN, 35, 37, ['E4', 'E3'], 90, { gap: 0.5 }); swell(HN, c.T(35), c.T(36, 3.4), 50, 102);
sustain(TB, 34, 37, ['E2', 'E3'], 88, { gap: 0.5 }); c.cc(TB, c.T(33), 11, 40); swell(TB, c.T(34), c.T(36, 3.4), 40, 102);
// S7 tutti: horns T at pitch, trombones an octave lower; the phrases shaped, 41-44 one level up
play(HN, c.T(37), TT, { vel: 104, legato: 0.9, accent: 14 });
play(TB, c.T(37), TT, { oct: -1, vel: 100, legato: 0.85, accent: 16 });
othismosArcs(HN, 102); othismosArcs(TB, 98);
// S8: one held horn E octave dying away
sustain(HN, 45, 47, ['E4', 'E3'], 84); c.cc(HN, c.T(45), 11, 110); swell(HN, c.T(45), c.T(46, 3.8), 110, 30);
c.cc(HN, c.T(1), 11, 100); c.cc(TB, c.T(1), 11, 96);
// salpinx calls (natural-trumpet notes B and E, anapaests)
function salpinx(b, v = 96, lastLen = 1) {
  c.line(TP, c.T(b), [['B4', 0.5, v - 16], ['B4', 0.5, v - 10], ['E5', 1, v], ['B4', 0.5, v - 16], ['B4', 0.5, v - 10], ['E5', lastLen, v]], { legato: 0.82 });
}
salpinx(18, 90);
salpinx(36, 88, 0.6); salpinx(42, 98); salpinx(44, 104);
c.line(TP, c.T(28, 3), [['E5', 0.5, 86], ['E5', 0.5, 92], ['B5', 1, 98]], { legato: 0.85 });
c.cc(TP, c.T(1), 11, 110);

// ---------------------------------------------------------------- men's chorus
// S3 phalanx: a drone on the fixed notes E3/B3 ("ah"), swelling under the brass antiphony
sustain(CH, 17, 21, ['E3', 'B3'], 86, { gap: 0.1 });
c.cc(CH, c.T(17), 11, 60); swell(CH, c.T(17), c.T(20, 3.5), 60, 112);
// S4 Paean: the cry "ie!" on the downbeat of 21, then the cretic chant on the mese, lower octave from bar 25
c.note(CH, c.T(21, 0), c.q(0.5), 'A3', 116); c.note(CH, c.T(21, 0), c.q(0.5), 'A2', 108);
for (let i = 0; i < 8; i++) {
  const b = 21 + i;
  let beat = i === 0 ? 0.75 : 0;
  const items = i === 0 ? [['A3', 1.25], ['B3', 1], ['C4', 2]] : PAEAN[i];
  for (const [p, d] of items) {
    const v = 100 + (d >= 2 ? 8 : 0) + (i >= 4 ? 6 : 0);
    c.note(CH, c.T(b, beat), c.q(d) * 0.94, p, v);
    if (i >= 4) c.note(CH, c.T(b, beat), c.q(d) * 0.94, P(p) - 12, v - 12);
    c.note(HN, c.T(b, beat), c.q(d) * 0.9, p, 72 + (i >= 4 ? 10 : 0));
    beat += d;
  }
  swell(CH, c.T(b), c.T(b, 2), 88, 112); swell(CH, c.T(b, 3), c.T(b, 4.9), 106, 92);
}
// build: the men swell on the E octave
sustain(CH, 34, 37, ['E3', 'E4'], 88, { gap: 0.5 }); swell(CH, c.T(34), c.T(36, 3.4), 30, 100);
// S7: the men sing T in unison an octave below the horns (E3-E4)
play(CH, c.T(37), TT, { oct: -1, vel: 112, legato: 0.95, accent: 6, shortLeg: 0.82 });
othismosArcs(CH, 100);
c.cc(CH, c.T(1), 11, 100);

// ---------------------------------------------------------------- strings
// S7: strings double T at pitch and the octave above (soft)
play(ST, c.T(37), TT, { vel: 98, legato: 0.92 });
play(ST, c.T(37), TT, { oct: 1, vel: 80, legato: 0.92 });
othismosArcs(ST, 96); c.cc(ST, c.T(1), 11, 100);
// S6 tremolo: the lower tetrachord climbs E F G A in octaves (a line, not a chain of chords), crescendo
const CLIMB = [['E3', 'E4'], ['F3', 'F4'], ['G3', 'G4'], ['A3', 'A4']];
for (let i = 0; i < 4; i++) sustain(TR, 33 + i, 34 + i, CLIMB[i], 96, { gap: i === 3 ? 0.5 : 0.02 });
// the othismos lands: the tremolo strikes the low E octave on the downbeat and dies away over two beats
sustain(TR, 37, 37, ['E2', 'E3'], 104, { end: 2.5, gap: 0 }); c.cc(TR, c.T(37), 11, 112); swell(TR, c.T(37, 0.5), c.T(37, 2.5), 112, 40);
swell(TR, c.T(33), c.T(36, 3.95), 34, 104);
c.cc(TR, c.T(1), 11, 34);
// paean: tremolo high pedal on the mese (A4/A5) very soft, for air (bars 25-28)
sustain(TR, 25, 29, ['A4', 'A5'], 44); c.cc(TR, c.T(25), 11, 44); swell(TR, c.T(27), c.T(28, 4.5), 44, 72); c.cc(TR, c.T(29), 11, 34);
c.cc(PZ, c.T(1), 11, 110);

const res = c.write(OUT, {
  title: 'Embaterion',
  harmonia: 'Ancient Dorian species on E (E-E white keys: e d c b | a g f e), mese A; diatonic, enharmonic colour on the aulos in the breath',
  quotes: 'none note for note (no Spartan melody survives); T, the Kastoreion dance and the paean are composed',
  sections: sections.map(([b, n], i) => ({ name: n, fromBar: b, toBar: (sections[i + 1]?.[0] ?? c.bars + 1) - 1, startSeconds: +c.secondsOfBar(b).toFixed(3) })),
  timeSignatures: '4/4 bars 1-20, 5/4 bars 21-28, 4/4 bars 29-48',
  species: [{ name: 'Dorian species on E: E F G A B C D', fromBar: 1, toBar: 48, pcs: PCS.dorianE }],
});
console.log(JSON.stringify({ cue: 'battle', notes: res.notes, loop: [res.json.loopStartSeconds, res.json.loopEndSeconds], seconds: res.json.loopSeconds, midi: res.midPath }));
