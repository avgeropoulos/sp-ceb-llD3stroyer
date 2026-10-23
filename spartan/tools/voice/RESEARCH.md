# Ancient Greek voices: how they are made and why

Every in-world Greek (Leonidas, Dienekes, the Trachinian, the lookout) and the Persian herald
speaks 5th-century Attic/Laconic Greek from pre-rendered clips. The Narrator and the Agoge
instructor keep the phone's English text-to-speech. This folder is the offline pipeline that turns
the script into those clips:

| file | what it is |
|---|---|
| `lines.json` | the script (written by the philologists): key, variant, speakers, Greek (polytonic and epigraphic capitals), English, IPA, delivery |
| `voices.json` | one recipe per speaker (voice blend, pitch, tempo, tone) plus one per delivery (speech, command, shout, cry) |
| `render.py` | lines.json + voices.json -> `audio/voice/<key>-<variant>-<speaker>.ogg` + `js/audio/voicebank.js` |
| `qa.py` | measures the rendered clips (loudness, peaks, silence, pitch, artifacts, speech quality, optional Whisper transcript, speaker distinctness) |
| `setup.sh` | fetches the model into a cache outside the repo and builds a pinned Python venv |
| `asr.mjs` | the Whisper batch transcriber that `qa.py --asr` drives |
| `render_google.py` | the same lines read by **native Greek** Google Cloud voices in Modern Greek pronunciation (see below) |
| `voices_google.json` | one Google voice per speaker, plus the same post-processing per delivery |

## Native Greek voices (Google Cloud)

The player, a Greek speaker, heard the Kokoro voices as foreign: the engine has no Greek voice,
so English, Spanish and Italian voices read the reconstructed IPA, and the reconstructed
pronunciation itself (b for β, u for ου, aspirated stops) sounds foreign to a Greek ear. Greeks
read ancient texts in Modern Greek pronunciation, so `render_google.py` does the same: it converts
each line's polytonic Greek to monotonic spelling and has a native Greek Chirp 3 HD voice read it,
then applies render.py's chain (EQ, delivery effort, loudness, Ogg Opus) and writes the same clips
and voicebank.js. Subtitles are unchanged.

```sh
export GOOGLE_TTS_API_KEY=...                       # a key with the Cloud Text-to-Speech API enabled
$PY -I spartan/tools/voice/render_google.py --show-text          # the monotonic text, no API calls
$PY -I spartan/tools/voice/render_google.py --list-voices        # the el-GR voices the key can use
$PY -I spartan/tools/voice/render_google.py --audition Leonidas --voices-try el-GR-Chirp3-HD-Charon,el-GR-Chirp3-HD-Fenrir --out /tmp/a
$PY -I spartan/tools/voice/render_google.py                      # render every line into the game
```

Responses are cached by request under `$THERMOPYLAE_TTS_CACHE` (default the voice cache's
`google-tts/`), so re-rendering after a post-processing change costs nothing. The key is sent in a
request header only.

## Using it

```sh
spartan/tools/voice/setup.sh            # once: Kokoro model + venv in ~/.cache/thermopylae-voice
spartan/tools/voice/setup.sh --asr      # optional: Whisper-small + DNSMOS for qa.py (about 1 GB)
PY=~/.cache/thermopylae-voice/venv/bin/python
$PY -I spartan/tools/voice/render.py                  # render every line, rewrite voicebank.js, delete stale clips
$PY -I spartan/tools/voice/render.py --only warcry    # one key (the bank is rebuilt from the clips on disk)
$PY -I spartan/tools/voice/render.py --out /tmp/try --qa   # render elsewhere, with a metrics JSON
$PY -I spartan/tools/voice/qa.py --asr --strict       # QA the clips in the repo; exit 1 on a hard failure
```

`THERMOPYLAE_VOICE_CACHE` moves the cache; `--model-dir` points at any folder holding
`kokoro-quantized.onnx`, `voices/` and `tokenizer.json`. ffmpeg with libopus must be on PATH. A
full render of 29 clips takes about 1 min 45 s on 4 CPU threads.

`lines.json` fields read by the renderer: `key`, `variant` (1..n), `speakers`, `caps` (the
subtitle), `en`, `ipa`, `delivery` (a free word; see below), and optionally `ipa_by_speaker`
(`{"Dienekes": "..."}`, a per-speaker pronunciation) and `asr_ref` (a Greek spelling Whisper should
produce, for an exact CER in QA). Keys appear in `voicebank.js` in the order of `lines.json`.

`delivery` is mapped to one of four efforts: **speech** (calm, grave, dry, wry, praise, encourage,
quiet), **command** (command, order, bark, call, defiant, alarm, proclaim, urgent), **shout**
(shout, yell, roar, bellow), **cry** (warcry, battlecry, ululate). Unknown words fall back to speech.

## Pipeline

1. **IPA -> Kokoro phonemes.** Kokoro's vocabulary has 115 symbols. Every IPA character is either
   in it, or in an explicit substitution table in `render.py` (`g` -> `ɡ`, macron -> `ː`, tie bars,
   non-syllabic and voiceless diacritics dropped, `ɫ` -> `l` ...), and each substitution is
   reported after the render. Anything else stops the render with the character's code point and
   Unicode name, so nothing is silently lost. Syllable dots inside words are ignored; `|`, `‖`
   become a comma and a period.
2. **Pitch accent.** Combining tone marks on a vowel (acute, circumflex, caron, grave) mark the
   Greek accent; in a word without them the vowel after `ˈ` is taken as acute (so tone marks and
   stress marks can be mixed in one line). Kokoro has no pitch input, so
   `render.py` patches the ONNX graph **in memory** (nothing is saved) with three per-phoneme inputs:
   a duration scale, an F0 multiplier and an energy offset, applied to the model's own predicted
   duration, F0 and energy curves (Hann-smoothed over ~110 ms so the edits are seamless). With
   neutral values the patched graph is bit-identical to the original (checked: max |diff| = 0.0).
   An acute raises its vowel by `accent_st` semitones (onset consonants by half, the next syllable
   falls by a quarter); a circumflex is high then falling inside the long vowel or diphthong; a
   caron rises; a grave gets no peak. Commands also lengthen and strengthen the accented and the
   phrase-final vowel.
3. **Speaker pitch and vocal-tract size.** `f0_st` and the delivery's `f0_delivery_st` move the
   model's own F0 curve (formants untouched). `formant` (< 1 = bigger man) is done by synthesizing
   at `speed / formant` and `F0 / formant` and resampling by `formant`: formants scale, pitch and
   tempo come back to where they were, with no phase-vocoder artifacts.
4. **Stuck phonemes.** Kokoro sometimes holds a phoneme far too long (typically a phrase-final `s`
   for 450-650 ms). Durations are capped (vowel 700 ms, cry vowel 950, consonant 200, pause 600)
   and the line is synthesized again.
5. **Post.** Voice EQ + effort EQ (biquads), level set to -20 LUFS so drive and threshold mean the
   same for every clip, parallel tanh saturation at 2x oversampling (commands, shouts, cries),
   soft-knee compressor, silence trim (-40 dB below peak, 40 ms pad, 5 ms fade-in, 20 ms fade-out),
   EBU R128 normalization to -16 LUFS with a look-ahead true-peak limiter. No reverb: the game adds
   the room.
6. **Encode and verify.** Mono Ogg Opus, 48 kHz, 48 kb/s VBR, bit-exact flags, no metadata. The
   clip is decoded again and measured; the pre-encode target is corrected until the decoded file is
   within 0.15 LU of -16 LUFS and at or under -1.5 dBTP (Opus sheds up to 0.7 LU of treble and can
   overshoot peaks). `dur` in `voicebank.js` is the decoded length.
7. **Determinism.** Fixed ONNX Runtime threading (4 intra-op, sequential), numpy/scipy DSP, ffmpeg
   bit-exact flags. Two full renders, and a render from a fresh `setup.sh` cache and venv, gave
   byte-identical `.ogg` files and `voicebank.js`. A different CPU family or ffmpeg/libopus build
   may change bytes (not sound); tuned with ffmpeg 6.1.1 + libopus 1.4, CPython 3.13,
   onnxruntime 1.31.0.

## How the voices were chosen (measurements, not listening)

I cannot listen. Every choice below comes from measurements, and a human listening pass is still
worth doing (`render.py --out <dir>` produces a pack in a minute). Measures:

* **Intelligibility**: Whisper-small (int8, transformers.js, forced Greek, greedy). *Control CER*:
  character error rate on Modern Greek sentences Whisper knows how to spell (the main one:
  "Καλημέρα σας. Είμαι ο Λεωνίδας, βασιλιάς της Σπάρτης."). *Ancient loose CER*: Whisper's Greek
  spelling and the line's IPA both reduced to coarse sound classes (Modern Greek spelling cannot
  show vowel length, aspiration or `b`/`v`), then compared. Both are noisy on one clip (one misheard
  word boundary moves a short phrase by 0.1-0.3), so decisions use means over several phrases.
* **Pitch**: pYIN F0 median, 10th and 90th percentile. Target for an adult man: 85-130 Hz in
  speech, higher when commanding or shouting.
* **Artifacts**: spectral flatness of voiced frames (buzz/noise), per-phoneme durations (stuck or
  garbled stretches), clipping, inner pauses, and DNSMOS P.835 SIG/OVRL (a non-intrusive speech
  quality predictor; trained on real noisy speech, used here only to catch distortion and to
  compare processing chains).
* **Distinctness**: MFCC 1-12 means of voiced frames, Euclidean distance between speakers on the
  same phrase or delivery, against the distance between two lines of one speaker (~14-18).

Test phrases (IPA): `molˈɔːn labˈe!` (defiant), `alalˈai!` (cry), `mˈenete! mˈenete en tˈaksi!`
(command), `aristopoiˈeːstʰe hɔːs en hˈaːidoː deipnɛːsˈontes.` (calm), and three Modern Greek
control sentences. Candidate renders are kept outside the repo (scratchpad `voicelab/candidates/`).

### Round A: the 23 male voices, unprocessed (speed 1.0)

| voice | control CER | ancient loose CER | F0 speech (Hz) | F0 command | note |
|---|---|---|---|---|---|
| am_onyx | **0.06** | 0.20 | **83** | 81 | deepest by far; narrow melody (5.3 st p10-p90 on the breakfast line) |
| am_michael | 0.08 | **0.06** | 120 | 117 | clearest consonants |
| am_adam | 0.06 | 0.13 | 120 | 125 | |
| am_echo | 0.16 | 0.13 | 104 | 107 | |
| am_fenrir | 0.08 | 0.25 | 136 | 146 | |
| am_eric | 0.20 | 0.17 | 154 | 161 | |
| am_liam | 0.22 | 0.28 | 115 | 128 | |
| am_puck | 0.18 | 0.18 | 112 | 122 | |
| am_santa | 0.36 | 0.11 | 206 | 188 | too high |
| bm_george | 0.12 | 0.08 | 146 | 145 | |
| bm_fable | 0.08 | 0.15 | 102 | 89 | |
| bm_daniel | 0.10 | 0.15 | 124 | 124 | |
| bm_lewis | 0.12 | 13.4 | 102 | 85 | Whisper looped: unusable on the ancient lines |
| em_alex | 0.16 | 0.07 | 133 | 142 | Spanish: pure vowels, no duration flags |
| em_santa | 0.20 | 0.13 | 141 | 147 | |
| hm_omega | 0.18 | 0.12 | 124 | 131 | |
| hm_psi | 0.12 | **0.05** | 133 | 147 | Hindi: very clear on Greek |
| im_nicola | 0.26 | 0.09 | 100 | 105 | Italian: Mediterranean rhythm, low |
| jm_kumo | 0.18 | 0.14 | 96 | 91 | |
| pm_alex | 0.06 | 0.15 | 136 | 143 | Portuguese |
| pm_santa | 0.12 | 0.11 | 147 | 133 | |
| zm_yunjian | 0.14 | **0.03** | 116 | 112 | |
| zm_yunxi | 0.12 | 0.13 | 164 | 167 | |

Kokoro's style vector is two halves: dims 0-127 drive the decoder (timbre), 128-255 the prosody
predictor (rhythm and melody). Blending them separately lets an English timbre speak with an
Italian or Spanish rhythm, which helped Greek more than any single voice.

### Leonidas (rounds L1-L3, with the base command/cry processing)

| candidate | control CER | ancient loose CER | F0 speech | F0 command | F0 cry | flatness |
|---|---|---|---|---|---|---|
| michael timbre + psi prosody, -3 st, formant 0.95 | 0.28 | 0.09 | 130 | 148 | 161 | 0.008 |
| michael + alex prosody, -3 st, 0.95 | 0.08 | 0.09 | 118 | 143 | 163 | 0.010 |
| michael + nicola prosody, -3 st, 0.95 | 0.08 | 0.08 | 89 | 118 | 134 | 0.007 |
| onyx + psi prosody | 0.18 | 0.15 | 140 | 151 | 183 | 0.005 |
| onyx + alex prosody | - | - | 137 | 168 | 181 | 0.005 |
| fenrir + nicola, -3 st, 0.95 | - | - | 87 | 111 | 142 | 0.012 |
| george + psi, -4 st, 0.94 | 0.14 | 0.10 | 118 | 112 | 146 | 0.010 |
| onyx + nicola prosody | 0.08 | 0.05 | 100 | 132 | 154 | 0.005 |
| onyx 60 / fenrir 40 + nicola | 0.08 | 0.14 | 102 | 131 | 163 | 0.007 |
| onyx 50 / michael 50 + nicola | 0.20 | 0.07 | 100 | 132 | 154 | 0.006 |
| **onyx 70 / michael 30 + nicola** | **0.08** | **0.04** | 101 | 132 | 162 | 0.006 |
| onyx + nicola, formant 0.97 | 0.10 | 0.16 | 101 | 132 | 155 | 0.005 |
| onyx + nicola, accent 0 st | 0.10 | 0.08 | 100 | 128 | 157 | 0.005 |
| onyx + nicola, accent 3.5 st | 0.08 | **0.02** | 102 | 135 | 171 | 0.005 |

Chosen: **am_onyx 70 % + am_michael 30 % timbre, im_nicola prosody, speed 0.9, accent 3.5 st.**
Onyx is the only voice with a genuinely deep, dark adult male timbre (F0 83 Hz raw); Michael adds
consonant clarity; Nicola's prosody gives Mediterranean vowels, lifts the speech pitch from 83 Hz
to ~100 Hz and widens the melody (breakfast line 5.3 -> 8.0 st p10-p90) without any pitch
shifting. A real pitch accent (3.5 st) made the
ancient words *more* intelligible (0.08 -> 0.02), not less. Making the vocal tract bigger by
resampling (formant 0.97) cost intelligibility (0.05 -> 0.16) and was dropped for Leonidas.

### Vocal effort for commands and cries (rounds E1-E2, Leonidas)

DNSMOS means over the same three phrases (molonLabe, warcry, holdTheLine) in every row.

| variant | ancient loose CER | F0 command | F0 cry | DNSMOS SIG | OVRL |
|---|---|---|---|---|---|
| E1, processing chain (pitch raise then +3 st command, about +7 st cry): | | | | | |
| full chain: effort EQ + saturation + compressor | 0.04 | 143 | 184 | 3.12 | 2.77 |
| no saturation | 0.04 | 143 | 183 | 3.05 | 2.66 |
| half saturation | 0.04 | 143 | 183 | 3.02 | 2.60 |
| no effort EQ | 0.04 | 141 | 186 | 2.93 | 2.61 |
| gentler compressor (2:1) | 0.04 | 143 | 184 | 3.07 | 2.75 |
| bare (none of the three) | 0.04 | 143 | 186 | 3.09 | 2.81 |
| no pitch raise (sounds like calm speech) | 0.04 | 116 | 124 | 3.33 | 2.96 |
| E2, pitch raise with the full chain: | | | | | |
| command +1.5 st, cry +3 st | 0.04 | 129 | 149 | 3.22 | 2.83 |
| **command +3 st, cry +5 st** (chosen) | **0.04** | **143** | **165** | **3.21** | 2.77 |
| command +4.5 st, cry +7 st | 0.12 | 155 | 184 | 3.02 | 2.64 |
| Dienekes with extra grit (saturation) | 0.11 (control 0.28) | 154 | 181 | 2.92 | 2.45 |
| Dienekes without grit (chosen) | 0.11 (control 0.06) | 152 | 181 | 3.02 | 2.60 |

The effort EQ (less chest, +4 to +6 dB at 2.2-2.5 kHz) and light saturation are what make a
raised voice read as effort rather than just higher pitch; taking either out alone lowered the
quality score a little (0.07-0.19). Raising pitch inside the model costs quality steadily; past
~3 st (command) / ~5 st (cry) it also costs intelligibility, so +3/+5 is the compromise. Extra
saturation on Dienekes cost intelligibility and quality and was dropped.

### The other four (round D1, then S1/S2 and T1-T3)

| speaker / candidate | control CER | ancient loose CER | F0 speech | F0 command | note |
|---|---|---|---|---|---|
| Dienekes: michael + alex prosody, -3 st, formant 0.95 | 0.06 | 0.14 | 105-120 | 152 | **chosen**: a second veteran, brighter and livelier than the king |
| Dienekes: george + psi, -4 st | 0.14 | 0.10 | 118 | 112 | too close to the herald's rhythm |
| Dienekes: fenrir + alex | 0.08 | 0.21 | 116 | 146 | |
| Lookout: **adam + pm_alex prosody, formant 1.02, speed 1.02** | 0.08 | 0.12-0.24 | 141 | 178 | **chosen**: younger, quick |
| Lookout: puck + alex | 0.06 | 0.55 | 140 | 170 | garbled cry |
| Lookout: eric + alex | 0.06 | 0.37 | 128 | 147 | |
| Herald: **hm_psi, -1.5 st, speed 0.88, nasal 1.1 kHz** | 0.12 | 0.04-0.13 | 137 | 139 | **chosen**: Indo-Iranian rhythm on Greek, slow and formal |
| Herald: hm_omega | 0.10 | 0.18 | 129 | 152 | |
| Herald: omega with a foreign phone map (ɛ->e, tʰ->θ ...) | 0.10 | 0.18 | 129 | 152 | no gain; the prosody does the work |
| Trachinian: am_echo + alex prosody | 0.06-0.23 | 0.12-0.14 | 127 | 167 | nearest voice to the king (MFCC 29-33) |
| Trachinian: echo, formant 1.03-1.05 | 0.21 | 0.17-0.25 | 124-131 | 159-167 | barely moves the distance |
| Trachinian: plain em_alex | 0.28 | 0.16 | 127 | 178 | lower quality (SIG 3.06) |
| Trachinian: **em_alex 60 / am_echo 40 + alex prosody, nasal EQ** | 0.28 | 0.15 | 127 | 175 | **chosen**: 36-51 from every other speaker |

## Result (R&D stand-in script, 29 clips, final recipes)

All 29 clips: -16.15 to -15.95 LUFS, true peak <= -1.51 dBTP, 0 clipped samples, 40 ms lead and
trail, longest inner pause 300 ms, no duration flags, DNSMOS SIG 2.96-3.69 (mean 3.35), 343 KB in
all. Whisper loose CER on the ancient lines: mean 0.11, median 0.07. Examples of what Whisper hears:
`ΜΟΛΩΝ ΛΑΒΕ` -> "Μολών, λαμπέ." (0.00), `ΑΣΠΙΔΑΣ ΑΝΩ` -> "Ασπίδας Ανό." (0.00), `ΑΛΑΛΑΙ` ->
"Αλαλάει." (0.00), the breakfast line -> "αριστοποίες θεώσεν χάιδο δεπνές σομμένοι." (0.03).
Applying the accent per word (so a line can mix tone marks and `ˈ`) took the long Anopaia alarm
from 0.17 to 0.07.

| speaker | F0 speech | F0 command | F0 shout/cry | loose CER | DNSMOS SIG | centroid |
|---|---|---|---|---|---|---|
| Leonidas | 100-114 Hz | 148 Hz | 152 Hz | 0.10 | 3.39 | 1014 Hz |
| Dienekes | 113-122 Hz | 146 Hz | 155 Hz | 0.11 | 3.24 | 1449 Hz |
| Lookout | ~141 Hz | 163 Hz | - | 0.14 | 3.57 | 1480 Hz |
| Herald | ~137 Hz | 146 Hz | - | 0.04 | 3.69 | 1103 Hz |
| Trachinian | ~127 Hz | 170 Hz | - | 0.22 | 3.47 | 1411 Hz |

Distinctness (MFCC distance; two lines of one speaker are typically 17.7 apart):

| pair | MFCC distance | F0 apart | compared on |
|---|---|---|---|
| Leonidas / Dienekes | 59.4 | 0.7 st | their 10 shared lines |
| Leonidas / Lookout | 56.3 | 1.7 st | commands |
| Leonidas / Herald | 43.2 | 0.2 st | commands |
| Leonidas / Trachinian | 38.9 | 2.4 st | commands |
| Dienekes / Herald | 52.0 | 0.1 st | commands |
| Dienekes / Lookout | 53.7 | 1.9 st | commands |
| Dienekes / Trachinian | 42.0 | 2.6 st | commands |
| Herald / Lookout | 43.1 | 2.0 st | commands |
| Herald / Trachinian | 44.5 | 2.7 st | commands |
| Lookout / Trachinian | 34.5 | 0.7 st | commands |

Leonidas and Dienekes, who share every battle bark, are the furthest apart in timbre. In calm
speech Leonidas sits 3-5 semitones under the lookout, herald and Trachinian.

## Final lines (lines.json, 13 keys, 19 lines, 29 clips)

The philologists' final script writes `ˈ` before the whole syllable (`ˈmenete`, `asˈpidas`), as
phoneticians do. The R&D lines had it right before the vowel (`mˈenete`, `aspˈidas`), the espeak
convention Kokoro was trained on. Rendered as written, Kokoro treated the mark as a stressed
consonant and Whisper did much worse. Scored with the loose error rate (median of three decodes
per clip, at 0 / 110 / 230 ms of lead-in):

| | onset `ˈ` (as written) | `ˈ` before the vowel |
|---|---|---|
| mean over 29 clips | 0.26 | 0.16 |
| median | 0.23 | 0.12 |
| ΜΟΛΩΝ ΛΑΒΕ | 0.44 ("Μόλις λάβος") | 0.00 ("Μολών, λαμπέ") |
| holdTheLine 3, Dienekes | 0.59 | 0.27 |
| swords | 0.42 | 0.22 |

`render.py` now moves every stress mark to its vowel (`kokoro_stress`) before tokenizing, so
lines.json keeps the editor's convention; `accent_plan()` gives the same accent peaks either
way (checked on all 19 lines) and the R&D lines are unchanged by it.

Other render decisions, all recorded in lines.json (`renderNotes` and the line notes):

* **Per-line `kind`.** The delivery word picks the recipe, and the script's words could not
  reach two intended ones: ΜΟΛΩΝ ΛΑΒΕ ("defiant: low, slow, ... not shouted") came out as a
  command, and ΑΛΑΛΑΙ ("shout: ... final diphthong held long") as a shout. With `kind`:
  ΜΟΛΩΝ ΛΑΒΕ as speech is F0 119 Hz instead of 145, DNSMOS SIG 3.35 instead of 2.94, still heard
  as "Μολών λαμπέ"; ΑΛΑΛΑΙ as a cry holds the final diphthong 0.93 s (Leonidas) / 0.73 s
  (Dienekes) at F0 ~160 Hz, SIG 3.01 / 2.89 (the cry's saturation costs ~0.3).
* **warcry 2 written `ˈkɔːroj`, not `ˈkɔːroi`.** The shout's final lengthening landed on the
  offglide: Leonidas held the `i` for 0.70 s and Whisper heard an extra syllable ("κόρου ειν").
  With `j` the hold is on the `o` and the clip is 0.3 s shorter. `breakfast` keeps its final
  `-oi` (calm speech: Whisper scores both spellings 0.17).
* **Duration clamp.** The cap on over-long phonemes rescaled by the rounded length and could end
  one 25 ms frame over the cap (a 0.725 s shouted final e); it now always lands at or under it.

Final QA (qa.py --asr --strict): 0 hard failures; loudness -16.13 to -15.94 LUFS, true peak
max -1.53 dBTP, 0 clipped samples, lead/trail silence 40/50 ms max, longest inner pause 300 ms,
no stuck-phoneme flags; DNSMOS SIG 2.89-3.65 (mean 3.40); loose CER mean 0.17, median 0.14
(single decode). Speaking rate inside each line (ms per phoneme, final word excluded) is within
0.89-1.14 of its speaker's median on every clip, so no line shows a garbled or dropped stretch.
The herald's clip reads well in halves (0.07-0.10 for the formula, 0.00 for "πέμψον τὰ ὅπλα")
but whole-clip greedy decoding often starts with a hallucinated "Αυτό είναι ο ..." (4 of 7 lead-in
offsets), so its single-decode score (0.79) is Whisper's, not the clip's. 29 clips, 400 KiB.

## Known limits

* **No true shouting.** Kokoro was trained on read speech. "Shout" and "cry" are raised pitch
  inside the model, longer and louder accented and final vowels, an effort EQ, saturation and
  compression: a projected, commanding voice, not a scream. Pushing pitch further made it worse.
* **Pitch accent approximated.** Ancient Greek had a pitch accent; Kokoro is a stress model. The
  renderer adds a real F0 peak (and a circumflex fall inside long vowels) on top of Kokoro's own
  stress, so the accent is pitch plus some stress, not pure pitch.
* **Phonology through a multilingual English-centred model.** Aspirates (`tʰ pʰ kʰ`) are rendered
  as aspirated stops (Whisper often writes them θ φ χ, which is the later Greek sound); `y`, long
  vowels and `ɛː`/`ɔː` depend on each voice; consonant clusters at word joins sometimes slur
  ("mˈenete en" heard as "μένα την"). Vowel length is honoured as duration only.
* **Measures are proxies.** Whisper hears Ancient Greek through Modern Greek; the loose CER only
  shows whether the sounds come through. DNSMOS is trained on natural speech, not TTS. MFCC
  distance is a coarse timbre measure. All of this was decided without listening.
* **Model limits.** One Kokoro pass takes at most ~500 phonemes; longer lines must be split. The
  int8 model is slightly noisier than the fp32 one, which is not available on the allowed
  registries.

## Licences and provenance

* **Kokoro-82M v1.0** (hexgrad), weights and voice packs: **Apache-2.0**. The int8 ONNX export,
  its 59 voice tensors and `tokenizer.json` are taken from the npm package `expo-kokoro@1.1.9`
  (danemadsen/expo-kokoro, MIT wrapper; only `build/kokoro-quantized.onnx`, `build/voices/*.bin`
  and `build/tokenizer.json` are extracted; tarball sha512 and model sha256
  `fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478` checked by `setup.sh`;
  nothing from the package is run). The rendered clips are the game's own assets; credit
  "Voices synthesized with Kokoro-82M (Apache-2.0)".
* **Whisper small** (OpenAI, MIT), int8 ONNX in the transformers.js layout from npm
  `sts-whisper-small@1.0.0` (Apache-2.0), run by `@huggingface/transformers@4.3.1` (Apache-2.0)
  with remote models disabled. QA only, never shipped.
* **DNSMOS P.835** `sig_bak_ovr.onnx` (Microsoft DNS Challenge) from npm `webmos@1.0.2` (ISC
  package). QA only, never shipped.
* Python: numpy, scipy, onnx, onnxruntime, soundfile, librosa (BSD/MIT/Apache), pinned in
  `requirements.txt`.

No model file is committed: everything lives in `~/.cache/thermopylae-voice` (or
`$THERMOPYLAE_VOICE_CACHE`). Hugging Face is not used (blocked); only the npm registry and PyPI.
