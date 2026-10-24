# Thermopylae music: the final score

The three cues the game asks for with `setMusic('title' | 'battle' | 'lastStand')`. They are written as
deterministic Node scripts that output a Standard MIDI File and a loop sidecar per cue. The pipeline in
`spartan/tools/music/` (see its README) renders, masters and checks them.

Nobody has listened to these files. Every judgement below comes from score analysis and audio
measurements. Before release, someone should listen on a phone speaker and on headphones.

| file | what it is |
|---|---|
| `title.mjs` | "Dawn at the Hot Gates": 72 BPM, 4/4, 27 bars = 90.000 s |
| `battle.mjs` | "Embaterion": 100 BPM, 20 x 4/4 + 8 x 5/4 + 20 x 4/4 = 48 bars = 200 quarter notes = 120.000 s |
| `lastStand.mjs` | "The Kolonos": 75 BPM, 4/4, 34 bars = 108.800 s |
| `motifs.mjs` | shared material: the leitmotif T, the Seikilos line (quoted) and its Mixolydian re-casting, the Kastoreion dance, the paean chant, the heterophony generator |
| `lib.mjs` | the score model and SMF writer. No dependencies. It handles humanisation with a seeded RNG, circular loop time, RPN pitch-bend range and fine tuning, and two checks that `write()` enforces: part ranges (`minKey`/`maxKey`) and monophony (`mono: true` parts: aulos, salpinx) |

Every loop is a whole number of 20 ms Opus frames, so the pipeline's packet-domain loop applies to all
three. The header of each generator gives its form, mode, tempo, instruments and quoted melodies.
`spartan/tests/unit/music-bank.test.mjs` checks the shipped files against `js/audio/musicbank.js`.

## Rebuilding

Run from the repository root. `C` is the pipeline cache: `spartan/tools/music/setup.sh` fills it once.
This build used the cache at `THERMOPYLAE_MUSIC_CACHE=<scratchpad>/music/cache`.

```sh
export THERMOPYLAE_MUSIC_CACHE=...; C=$THERMOPYLAE_MUSIC_CACHE
for cue in title battle lastStand; do
  node spartan/tools/music/cues/$cue.mjs                       # -> $C/score/$cue.mid + $cue.json (argv[2] overrides the dir)
  node spartan/tools/music/render.mjs --check $C/score/$cue.mid
  node spartan/tools/music/render.mjs $C/score/$cue.mid        # -> $C/build/$cue.loop.wav + $cue.render.json
  $C/venv/bin/python -I spartan/tools/music/master.py $C/build/$cue.loop.wav   # -> audio/music/$cue.ogg, js/audio/musicbank.js
done
$C/venv/bin/python -I spartan/tools/music/qa.py --browser
node --test spartan/tests/unit/music-bank.test.mjs
```

The generators are deterministic: two runs give byte-identical MIDI and JSON, and the renders are
bit-identical. Every score edit that adds or removes a note reshuffles the humanisation of all later
notes, because they share one RNG stream, and it moves the Opus encoder's choices. So after any edit,
re-run master and QA, not only render. (Run the three `master.py` calls one after another, or with
`--no-bank` and then `node spartan/tools/music/render.mjs --bank` once: each call rewrites the bank.)

## How the final score was chosen

The three judges each scored the three candidates per cue (Greek identity / epic impact / game fit):

| cue | historian | cinematic | gameplay |
|---|---|---|---|
| title | 8 / 6 / 5 = 19 | 5 / 8.5 / 8 = 21.5 | 7.5 / 7.5 / 7.5 = **22.5** |
| battle | 8 / 5.5 / 5 = 18.5 | 4.5 / 8 / 6.5 = 19 | 7 / 7.5 / 8 = **22.5** |
| lastStand | 8.5 / 6.5 / 4.5 = 19.5 | 5.5 / 8.5 / 8 = 22 | 8 / 8 / 6.5 = **22.5** |

**composer-gameplay** has the best total in every cue. For lastStand the margin over cinematic is
0.5. The rule was to break ties toward epic impact without losing Greek identity. Cinematic's lastStand
sets the Seikilos in A major and B minor triads, the clearest anachronism the Greek judge found, so
gameplay stays.

Taking one composer for all three cues also keeps the soundtrack's thread. The game-fit judge noted
that mixing composers breaks the leitmotif.

Two themes run through all three cues:

- **T**: the composed embaterion in the Dorian species, anapaests across the bar line.
  - title: sung by the dawn aulos, then in tutti;
  - battle: marched in S2 and S7;
  - lastStand: re-cast as the Mixolydian lament, then the Dorian defiance.
- **The Seikilos line**: the only quoted melody.
  - title: the solo aulos's "light" in bars 9-11, then sung in tutti at the climax in bars 21-23;
  - lastStand: sung as the last defiance in bars 25-27, then re-cast into the Mixolydian as the
    solo-aulos epitaph in bars 31-34.

## Round 1: what changed against the base, cue by cue

The grafts from the other two candidates and the fixes for the judges' weaknesses. The figures quoted
here are the judges' "before" values; the shipped values are in "Measurements of what ships" below.

### title (base: gameplay title)

- **Seikilos grafted in (historian, epic).** The climax is the Seikilos sung by the men in two octaves
  (A2-E4). Horns sit at pitch, trombones an octave below, the massed aulos plays in heterophony, and
  the drums follow the song's long notes. Ancient material: 6 of 27 bars (was 4 of 24).
- **No triads under the tune (Greek).** Under the Seikilos the drones and kithara stay on the melody's
  final E and the mese B of its Iastian tonos, so its C# never meets an A. P5's bass is a drone
  E | E | A | E instead of E | A | C | E | E | A. Gameplay's triad time was 9.9 %.
- **The empire (P4) is a real dip and build (cinematic, epic).** It drops to -23 LUFS and rises to
  -15; the chromatic genus is heard as lines (A F# F E on the low aulos, then on trombones and celli,
  answered by e' c#' c' b on the horns). The parallel-fifth tremolo is gone (tremolo on E/B), and so is
  the cymbal swell (see the Opus note below).
- **Break-off (historian).** The tutti breaks off on the mese A with one arrival blow. The solo aulos
  then completes the cadence alone (T bars 7-8, F to E+ to E), and the dawn returns.
- **Terraced (game fit).** P5 sits one level under P6. Gameplay's near-max was 29.9 %, its centroid CV
  0.01.
- **Realism.** Reeds at or below F#5 (gameplay reached E6), men at or below E4 (C5); contrabass E1 + E2
  and a bass-trombone E2 pedal under the climax; the second aulos (shanai) in heterophony with turns,
  tuned -6 cents by RPN 1, the low aulos +4 cents; F#5 graces on the Seikilos's long E.
- **Drums.** Low bongo 61 and open conga 63 stand in for the hand-struck tympanon skin on the anapaests
  (their energy lies at 300 Hz-3 kHz, so the procession reads on a phone). The concert bass drum, the
  kit's peakiest sound, is used sparingly. Gameplay's limiter: 2.5 dB on 14 % of samples.

### battle (base: gameplay battle)

- **S3 rewritten (Greek, game fit).** Gameplay's chordal "phalanx" (48 % triad time) is replaced by a
  Kastoreion dance on the massed aulos (bars 13-16: sixteenth-note anapaests around the mese A, shanai
  quick heterophony, cor anglais an octave below, kithara and pizzicato on E/B, frame drum and krotala,
  no great drum) and by T's head called by the horns and answered by the trombones over the men's E/B
  drone (bars 17-20). T is stated in full only in S2 and S7.
- **Paean kept (Greek's favourite)**: the 5/4 cretic paean with the "ie!" cry, on an A-only drone, so
  the chant's C never makes an A-minor triad; no tympanon jingles, krotala only in its second half.
- **SFX band cleared (game fit).** Jingles in off-beat 8ths only (120 hits; gameplay 276), krotala at
  phrase ends. Gameplay had 3.7 % of its energy above 3.5 kHz, 46 % under 120 Hz, raw peak +0.9 dBFS and
  3.6 dB of limiting on 15.8 %.
- **Drive above 300 Hz (epic).** Hand drums play the anapaest shorts and pizzicato doubles the cello
  ostinato's accents two octaves up.
- **Othismos (S7).** T at pitch on three aulos parts in heterophony (oboe, shanai at -7 cents, cor
  anglais an octave below), the men in unison an octave below the horns.
- **Kept: the muster opening and the breath, with no jingles.**
- **Build (S6).** The tremolo climbs the tetrachord E F G A as an octave line over an E-only drone.

### lastStand (base: gameplay lastStand), 34 bars = 108.8 s (was 32 = 102.4)

- **Shine (bars 25-28, cinematic's idea, Greek-safe).** The Seikilos sung in tutti over an E/B drone
  (the melody's final and the mese of its tonos), in unison and octaves; drums follow its long notes.
- **The blow.** Bar 28's close relaxes, then one tutti stroke on E/B lands at bar 29; bar 30 drops to
  -28 LUFS, a 10 LU release.
- **Epitaph (historian).** In bars 31-34 the Seikilos is re-cast degree for degree into the Mixolydian
  (e b b g a b a g f, then e d c b) on the solo low aulos over a failing heartbeat and a B-only drone,
  with quarter-tone approaches held 0.4 beat. Its last B rings across the seam into the threnos.
- **No sliding chords (Greek).** L3's parallel tremolo fifths (54.7 % triad time) are gone (round 2
  then also removed the climbing line that replaced them, see below); the turn's build is the chromatic
  genus E F F# A as an octave line, not fifths. Gameplay's whole-cue triad time was 13.2 %.
- **Dochmiac variety (game fit).** L1 heartbeat (round 2 adds a soft frame-drum dochmiac), L2 frame
  drum alone, L3 taiko and frame drum, L4 full with bass drum, krotala and hand drums, L6 full with the
  trombones pounding the dochmiac on E2/B2 (historian's graft), Shine a processional on the song; no
  jingles before the defiance.
- **Plateau broken (epic, game fit).** Grief (L4) and the defiance sit one level under Shine; the turn
  (bars 17-18) keeps its mid-loop speech room. Gameplay's near-max was 40.9 %.
- **Realism.** Reeds at or below A5 (gameplay reached E6), men at or below G4, the shanai detuned
  -6 cents in heterophony.

## Round 2: the adversarial reviews

Three reviews (loops and mastering; musical quality; authenticity and honesty) found 21 issues. Each
was checked against the MIDI, the code or the decoded files before it was fixed. All were confirmed;
one is left to the Integrate step (the project README), and the memory rules are written down here
and in the pipeline README for it.

**Pipeline (spartan/tools/music/).**

- **True peak.** The decoded battle and lastStand files measured -1.46 and -1.49 dBTP, over the
  -1.5 dBTP ceiling, because `master.py` accepted up to the ceiling + 0.05 dB. It now accepts only a
  decoded true peak at or under the ceiling (problem otherwise), and `qa.py` FAILs above it. The
  `TRUEPEAK_DBTP` tag held the limiter's value (-1.90 to -1.93), not the decoded file's; it is now
  rewritten after the final encode with the decoded value (the audio packets are untouched and checked).
- **Meter map.** battle is 4/4 + 5/4, but `render.json`, the Ogg tags and QA called it 48 bars of 4/4
  (115.2 s against 120 s), so QA's bar grid ran mid-bar from bar 21 and the battle breath was misreported
  (10.9 LU at "bars 33-36"; on the real bars it was 9.6 LU at 29-32). `render.mjs` now writes the
  time-signature map (`meter: '4/4,5/4@21,4/4@29'`), the quarter-note count and the bar starts;
  `master.py` tags `METER`, `BARS`, `BEATS` and measures the seam on the real bar lines; `qa.py`
  rebuilds the bars from the tags and FAILs when they do not add up to `LOOPLENGTH`
  (`musiclib.bar_lines`).
- **Bank.** `musicbank.js` entries carry `samples` (the exact 48 kHz decode length), so the game can
  assert `buf.length === samples`; `qa.py` FAILs a mismatch.
- **Regression test.** `tests/unit/music-bank.test.mjs` parses the Ogg pages (CRCs, OpusHead, tags,
  granules) and checks every bank entry against its file and the brief, the meter map against the
  loop length, `TRUEPEAK_DBTP` against the ceiling, and the download and decoded-memory budgets.
- **Decoded memory** (not changed in code; rules for the Integrate step below and in the README).
- **README.** The licence note claimed the cues quote or develop the Delphic hymns, Mesomedes and the
  Orestes papyrus and transcribe them from the notation; only Seikilos line 1 is quoted, from memory.
  Rewritten. The GeneralUser GS note now carries the licence's own caveat on sample provenance and
  names the npm package's repackager.

**Scores.**

- **lastStand, overlapping aulos.** At bar 16 beats 3-4 the L4 tutti played T bar 4's pickup (F5 F5)
  while the same solo oboe played its soft E4 E4 march pickup: two notes on one aulos, a minor 9th
  apart. Bar 12 had the same problem: L3's held B3 under the L4 pickup. L4 now closes T on the final
  (`T4_CLOSED`) and L3 ends two beats early (`T8_SHORT`). `lib.write()` now refuses overlaps of more
  than 0.1 beat on `mono: true` parts; all aulos and salpinx parts are flagged.
- **Builds crested over their arrivals** (battle 36 to 37, lastStand 20 to 21, title 16 to 17). The
  build swells now stop at expression 96-110 instead of 106-124, the cymbal swells at velocity 56
  (battle, lastStand; title has none), and the
  16ths, rolls and sustains stop at beat 3.5 of the last build bar, a breath in which only the aulos's
  anapaest pickup and two light drum shorts lead in. The arrival's downbeat gets a stronger great drum
  and bass drum, a tremolo low-E stroke (battle, lastStand) and an expression accent that settles over
  two beats. Per-beat K-weighted loudness of the shipped files, arrival's first two beats minus the
  build's last beat: title -1.0 -> +1.4 LU, battle -2.4 -> +1.3 LU, lastStand -2.4 -> +1.3 LU (the
  reviewer's acceptance: at least +1 LU).
- **lastStand L3 drone cluster.** The celli held B2/F3 under the men's entry on B2/E3 (E3 against F3
  for 8 beats), and the tremolo's climb B C D E ran inside the drone register (B2/C3, a held B-D-F,
  E3/F3). Now the celli move with the men (fourth B/E in bars 9-10, diminished fifth B/F in 11-12), the
  kithara's middle string follows (E3, then F3), and the tremolo holds the B octave. Low-register
  seconds in bars 9-12 (beat-pairs, the reviewer's measure): 9.4 / 10.9 / 4.5 / 10.9 -> 0 / 0 / 0 / 0.
- **lastStand L4 quarter tones.** The held enharmonic approach on the oboe sounded against unison and
  octave doublings in strings, shanai, horns and chorus. Dropped in L4; the quarter tones stay where
  the aulos is exposed (L1-L3, epitaph).
- **Low seconds at 73-82 Hz.** battle's alternating ostinato (OST_B: E E E E D D C D) put the celli's
  D2 under the basses' (bars 6-20) or the timpani's (bars 38-44) E2. The basses now take the D on beat
  2 in those bars, and the timpani's beat-2 stroke is B2. In lastStand bar 24 the trombones play B2
  only from beat 1.5, the timpani's beat-3 stroke is B2, and the basses' C2 is short. Those bars now
  measure 0 beat-pairs (were 0.76 per battle bar, 1.98 in lastStand bar 24).
- **battle S7 othismos.** Each melodic part's expression now follows the phrase (the arrival accented,
  a swell to T's top, a fall into the phrase end) instead of two flat levels. 41-44 sits one terrace up
  (+14 expression) and adds layers S2 lacks: the shanai breaks into quick heterophony and a salpinx
  call answers bar 42. The oboe now plays T's pickup into bar 37, so the anapaest crosses the bar line
  as in S2. title P5 got the same phrase shaping.
- **battle S5 breath.** The solo oboe lament (expression up to 108) filled the speech room: bar 30
  measured -22.3 dBFS in the 200 Hz-3.5 kHz band. Now at expression 46-60: bars 29-32 measure
  -35.5 / -30.9 / -33.8 / -36.4 dBFS (target: under -30).
- **lastStand trough.** Release, epitaph, threnos and kommos (bars 30 to 8, 41.6 s) had only a
  heartbeat. The frame drum now keeps a soft dochmiac (velocity 0.40-0.52 of full) under the epitaph's
  close (33-34) and through the threnos (1-4), into the kommos's (5-8). Drum notes per bar in 33-34 and
  1-4: 3, 1 and 4, 4, 5, 4 -> 11, 8 and 13, 10, 11, 10. The loudness of those bars is unchanged; the
  pulse no longer stops.
- **lastStand bar 3 sigh.** D -> C+ -> C bent C a quarter tone sharp, which belongs to no pyknon of the
  Mixolydian (B B+ C, E E+ F), and the glide outlasted the note, so the next C started 39 cents sharp.
  Bar 3 now steps D -> C diatonically, and `sigh()` ends its glide inside the lower note.
- **battle cor anglais at G5** (written D6, above the instrument): the paean heterophony's turn on F
  went to G. `hetero()` takes a `maxKey`: at the ceiling the turn dips instead. The part's range is now
  E3-F5.
- **Wording.** "E and B, both fixed notes" under the Seikilos was wrong ancient theory: in the
  two-sharp system of its Iastian tonos the fixed notes are B, C# and F#; E is the melody's final. The
  comments now say "the melody's final E and the mese B". T's comment said every bar is "- uu - uu",
  which is a dactyl; T is anapaestic across the bar line (see below). NOTES' "80 held-bend events" in
  lastStand was wrong (it had 19 quarter-tone inflections; 15 after round 2), and battle's reed ceiling
  was G5, not F5 (now F5).

**In the game.** `spartan/README.md` describes the music (pre-rendered Ogg Opus loops in `audio/music/`,
scored here, `loops.js` as the fallback) and carries the credits: GeneralUser GS by S. Christian Collins,
spessasynth_core, and line 1 of the Seikilos epitaph.

## Quoted and modelled ancient sources

- **Seikilos epitaph, line 1, "Hoson zes, phainou".**
  - Source: stele from near Tralles, 1st c. AD; Poehlmann & West, *Documents of Ancient Greek Music*
    (2001) no. 23; West, *Ancient Greek Music* (1992).
  - Signs C Z Z K I Z I K O in the Iastian tonos = a e' e' c#' d' e' d' c#' b, scored A4 E5 E5 C#5 D5
    E5 D5 C#5 B4. A reviewer checked the pitches against the vocal-notation signs.
  - Rhythm: 12 chronoi as 1 2 2 1 1 1 1 1 2.
  - **Caveat: transcribed from memory of the standard reading.** No printed edition is reachable from
    this sandbox (only npm and PyPI are). Another reading lengthens the e' of "phai" (1 2 2 1 1 2 1 1 1);
    both make 12 chronoi. Check pitches and rhythm against DAGM 23 before calling it note-for-note.
  - The closes (A G F# E; in the Mixolydian, E D C B) are composed.
  - Its system: the Iastian tonos has two sharps; its fixed notes (hestotes) are B, C# and F#, its mese
    B. The drones under it are on the melody's final E and that mese B.
- **The Mixolydian epitaph** is the same line re-cast degree for degree. That is a transformation, not
  a quotation.
- **Orestes papyrus** (P. Vienna G 2315, Euripides *Orestes* 338-344). Used as a model only: the
  dochmiac rhythm, the enharmonic pyknon and the interjected aulos sighs. No pitch of it is quoted,
  because it could not be transcribed reliably here.
- **No Delphic hymn or Mesomedes melody is used.** The Delphic paeans are cited only for the
  cretic-paeonic rhythm of the battle paean.
- **Composed in the documented idiom:**
  - T: anapaests (u u -) running across the bar line, the pickup giving the first pair of shorts
    (uu | - uu - uu | -); read inside the bar, each foot scans as a dactyl, a substitution anapaestic
    metre allows. The Spartan marching songs (embateria, also called enoplia, Athenaeus 14.630f) were
    anapaestic: the Doric marching songs PMG 856-857 (carmina popularia, ascribed to Tyrtaeus) are
    anapaestic dimeters. Thucydides 5.70 describes the advance to many aulos players, to keep step;
    Plutarch, *Lycurgus* 22, the Kastoreion played as they advanced. (The PMG numbers and the
    Athenaeus passage were checked from memory only.)
  - The Kastoreion dance: the real tune is lost; Plutarch names it only.
  - The 5/4 paean: cretic-paeonic feet like the Delphic paeans; the cry "ie".
- **Harmoniai.** These are the ancient octave species, not the church modes:
  - Dorian = E-E on the white keys (two disjunct tetrachords e d c b | a g f e, mese A).
  - Mixolydian = B-B (the lamenting harmonia, Aristotle, *Politics* 1340a-b).
  - Phrygian species E F# G A B C# D: the Seikilos's own, Iastian tonos.
  - Chromatic genus: E F F# A | B C C# E.
  - Enharmonic: quarter tones by pitch bend on the aulos (exactly +50 cents, bend range 2): 15
    inflections in lastStand (held 0.35-0.4 beat), 5 in battle (three in the breath, two on the final E
    of S2 and of the S8 tag), 1 in title (the close).

## Measurements of what ships

From the pipeline's own `qa.py --browser` on the decoded .ogg files in `audio/music/`:

| | title | battle | lastStand |
|---|---|---|---|
| loop | 90.000 s, 4 320 000 smp, 27 bars | 120.000 s, 5 760 000 smp, 48 bars (`4/4,5/4@21,4/4@29`, 200 beats) | 108.800 s, 5 222 400 smp, 34 bars |
| verdict | WARN (44.1 kHz decode only) | WARN (44.1 kHz decode only) | PASS |
| integrated / LRA | -16.0 LUFS / 11.2 LU | -16.0 / 9.7 | -16.0 / 10.9 |
| true peak (decoded, = tag) / PLR | -1.62 dBTP / 14.4 dB | -1.58 / 14.4 | -1.59 / 14.4 |
| limiter max, share of samples | 1.05 dB, 3.1 % | 1.76 dB, 2.3 % | 2.22 dB, 7.3 % |
| seam (jump / lpc) | PASS 0.52 / 0.43 | PASS 0.08 / 0.92 | PASS 0.49 / 1.08 |
| breath (quietest vs loudest 4 bars) | 10.4 LU (1-4 vs 21-24) | 13.6 LU (29-32 vs 41-44) | 11.6 LU (33-34 vs 25-28) |
| near max (within 3 LU of loudest) | 21.7 % | 30.2 % | 25.2 % |
| centroid / CV of 8-bar windows | 617 Hz / 0.11 | 568 Hz / 0.23 | 567 Hz / 0.20 |
| energy < 120 Hz / > 3.5 kHz | 26.4 % / 0.3 % | 32.7 % / 0.9 % | 23.7 % / 0.4 % |
| L/R correlation / mono-sum loss | 0.84 / 0.39 LU | 0.80 / 0.61 LU | 0.85 / 0.41 LU |
| size | 1 010 911 B (89.9 kb/s) | 1 311 360 B (87.4 kb/s) | 1 152 460 B (84.7 kb/s) |

Total 3 474 731 bytes. The loudness spread across the three cues is 0.03 LU. Every decoded wrap equals
a continuous decode across the pass end (difference 0.0); renders are periodic to -113 to -120 dBFS.

In Chromium every cue decodes at 48 kHz to exactly the loop length and its seam passes. A 48 kHz
buffer looped in a 44.1 kHz context wraps cleanly (lpc 0.44 / 0.84 / 1.06). The two WARNs are the
documented 44.1 kHz `decodeAudioData` path, which comes out 1 sample short for title and battle.

battle's near-max rose from 22.8 % (round 1) to 30.2 %: the othismos now rises in phrases instead of
jumping to a flat top, so its loudest 3 s is a little lower and more of S7 sits within 3 LU of it. It
is far from the 70 % "no breath" warning, and the breath itself is deeper (13.6 LU on the real bars,
against 9.6).

The game-fit judge's tools on the shipped files (mono sum through a 400 Hz high-pass for the phone;
voice audibility against the recorded Leonidas clips at their in-game gain and duck):

| | title | battle | lastStand |
|---|---|---|---|
| phone loudness (mono, 400 Hz HPF) | -17.81 | -18.67 | -18.23 (spread 0.86 LU) |
| openings, first 2.5 s | -23.9 LUFS | -21.1 | -25.1 |
| speech band (200 Hz-3.5 kHz) in the quiet bars | -29.6 / -28.1 / -26.3 / -27.6 dBFS (bars 1-4) | -32.8 / -34.0 / -34.1 (bars 1-3); -35.5 / -30.9 / -33.8 / -36.4 (breath, 29-32) | -34.2 (bar 1), -27.2 / -25.6 (the turn, 17-18) |
| voice audibility proxy, mean / min | 0.931 / 0.740 | 0.935 / 0.770 | 0.932 / 0.769 |
| bar self-similarity | 0.743 | 0.718 | 0.740 |

The Greek judge's MIDI tools on the final scores:

| | title | battle | lastStand |
|---|---|---|---|
| triad time | 3.5 % | 5.6 % | 7.6 % |
| unison/octave share of joint onsets | 69.0 % | 52.2 % | 68.2 % |
| aulos + kithara + choir + salpinx share | 36.6 % | 48.1 % | 38.6 % |
| reed ceiling | F#5 | F5 | A5 |

battle's triad time rose from 5.1 % and lastStand's from 7.4 %, in the bars where T's passing G sounds
over the E/B drones: the timpani's beat-2 stroke is now B2 in every battle bar of S7 (it was E2 in the
even bars), and the tremolo's low-E stroke at the arrivals holds an E for two beats (lastStand bar 21:
6 -> 13 %); the rest is humanisation moving note ends.

## Notes for the pipeline and the Integrate step

- **Decode the music at 48 kHz, on demand, and mind the memory.** Each cue decodes to a stereo float32
  buffer of `samples x 8` bytes: title 34.6 MB, battle 46.1 MB, lastStand 41.8 MB, against the game's
  23-25 MB budget for all synthesized audio and 16 MiB for decoded voice. A battle-to-lastStand
  crossfade holds 87.9 MB. The rules (pipeline README, "Notes for the game"): decode one cue at a time
  with `new OfflineAudioContext(2, 1, 48000).decodeAudioData`, never through the main context's
  `decodeAudioData` (the shared voice `decode()`); keep only the playing cue and the one fading out, and
  drop the outgoing buffer after the fade; assert `sampleRate === 48000 && length === samples` and fall
  back to `loops.js` otherwise; down-mix to mono on the low quality setting or `deviceMemory <= 3`
  (halves the memory; mono-sum loss 0.4-0.6 LU); give the music its own e2e memory budget (48 MB
  steady, 90 MB in a crossfade). `music-bank.test.mjs` already holds the files to those budgets.
- **Opus packet loop: convergence.** `master.py` keeps the encoder's pass 2 as the loop and checks the
  wrap against the pass 2 -> 3 transition. In round 1 two intermediate title mixes failed that check
  (libopus had not quite converged: pass 1 vs 2 differed in 191 packets, pass 2 vs 3 in 1, pass 3 vs 4
  in 0). Every shipped file passes; keeping pass 3 would make the check robust. Not changed here.
- **The codec's overshoot comes from transients** (0.2-0.7 dB on these mixes). Drum strokes that land
  together (battle's timpani roll on the cymbal swell's strokes, bar 36) cost the most limiting; the
  breath before the othismos stops the roll at beat 3.
- **Repeated cymbal strokes rendered non-periodically** once (the title's suspended-cymbal swell,
  -51 dBFS between passes); without it the render is periodic to -120 dBFS. The swells in battle bar 36
  and lastStand bar 20 render periodically.
- **GeneralUser's taiko (program 116) barely changes with the key** (1.8 dB less under 120 Hz from key
  40 to 64), so the bright accents come from the hand drums.

## Open points

- Listen on a phone and on headphones. In particular, check:
  - the GM choir's "ah" in the paean and the Seikilos (it cannot sing words);
  - the oboe/shanai heterophony, and the shanai's quick heterophony in battle 41-44;
  - the hand-drum stand-ins for the tympanon;
  - the breaths before the arrivals (an eighth of near-silence: does it read as a breath or a gap?);
  - whether lastStand's soft frame-drum dochmiac (velocity 0.40-0.52) is audible at all under the
    threnos.
- Check the Seikilos against DAGM 23 (see above).
- The bar self-similarity of title and lastStand (0.74) is higher than gameplay's (0.69 and 0.72). The
  drones on E and B are the cause, and they are the Greek identity the judges asked for.
- The Greek-instrument share of the title (37 %) is no higher than gameplay's, because the cello and
  contrabass drones carry the weight under the dawn and the climax.
