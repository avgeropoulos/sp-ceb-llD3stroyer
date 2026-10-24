# Ancient Greek voices: how they are made and why

Every in-world Greek (Leonidas, Dienekes, the Trachinian, the lookout) and the Persian herald
speaks the script's 5th-century Attic/Laconic Greek from pre-rendered clips, **read the way Greek
schools read ancient texts: in Modern Greek pronunciation** (see "Modern Greek reading"; the
reconstructed 5th-century pronunciation is still in the script and the tools, but no longer in the
game). The Narrator and the Agoge instructor keep the phone's English text-to-speech. This folder
is the offline pipeline that turns the script into those clips:

| file | what it is |
|---|---|
| `lines.json` | the script (written by the philologists): key, variant, speakers, Greek (polytonic and epigraphic capitals), English, IPA (`ipa`: reconstructed 5th-century; `ipa_el`: Modern Greek school reading), delivery |
| `voices_el.json` | one recipe per speaker (voice blend, pitch, tempo, tone) plus one per delivery (speech, command, shout, cry), for the Modern Greek reading: **the default, what the game ships** (see "Modern Greek reading" below) |
| `voices.json` | the same for the reconstructed pronunciation (`render.py --reading reconstructed`; the game's clips until 2026-10-24) |
| `render.py` | lines.json + a recipe file -> `audio/voice/<key>-<variant>-<speaker>.ogg` + `js/audio/voicebank.js` |
| `qa.py` | measures the rendered clips (loudness, peaks, silence, pitch, artifacts, speech quality, optional Whisper transcript and language identification, speaker distinctness) |
| `setup.sh` | fetches the model into a cache outside the repo and builds a pinned Python venv |
| `asr.mjs` | the Whisper batch transcriber (and language identifier) that `qa.py --asr` drives |
| `render_google.py` | the same lines read by **native Greek** Google Cloud voices in Modern Greek pronunciation (see below) |
| `voices_google.json` | one Google voice per speaker, plus the same post-processing per delivery |

## Modern Greek reading (offline, Kokoro)

The player, a native Greek speaker: "Why do the voices sound Indian and Russian? They should sound
Greek and be read with Greek fluency." Two causes: the reconstructed 5th-century pronunciation
(b for β, u for ου, aspirated stops, pitch accent) sounds foreign to a Greek ear, and none of the
voices was Greek (Kokoro has no Greek voice; English, Spanish and Italian voices read the IPA, and
the herald was the Hindi hm_psi). Whisper's own language identification agrees with the player:
the herald's clip is heard as Sinhala, Punjabi, Bengali or Kannada (P(Greek) 0.00), Leonidas as
English, Portuguese or Spanish; Greek is the top guess on 2 of the 29 clips.

The fix reads the ancient text the way Greek schools read it: Modern Greek pronunciation
(`ipa_el` in lines.json, made from the monotonic spelling by espeak-ng's Greek voice and corrected
word by word), read by the voices in `voices_el.json`. Subtitles do not change. The Google Cloud
path below does the same with native voices but needs a key, which the project does not have.

```sh
$PY -I spartan/tools/voice/render.py                  # the default: voices_el.json reading ipa_el, into the game
$PY -I spartan/tools/voice/render.py --out /tmp/el --qa
$PY -I spartan/tools/voice/qa.py --asr --strict       # QA of the game's clips (--ipa-field ipa_el is the default)
```

**The Modern Greek reading is the default** of both tools (since 2026-10-24, when it replaced the
game's clips): `render.py` with no `--reading` uses voices_el.json and `ipa_el`, and `qa.py` scores
against `ipa_el`. Before that, the default was the reconstructed reading, so a one-key re-render
(`render.py --only herald`) silently put the old Hindi-voiced herald back into the Modern Greek set.
`--reading reconstructed` (voices.json reading `ipa`) still renders the old clips byte-identically;
render it with `--out` somewhere else, and with `--only` into the game render.py now warns that the
set would mix two readings. A recipe file names the field it was tuned for (`"ipa_field": "ipa_el"`),
`--ipa-field` overrides it, and `<field>_by_speaker` gives a per-speaker pronunciation as `ipa_by_speaker`
did. render.py now also reads `ç` (Unicode NFD had split it into c + cedilla, which stopped the render).

### Measures

* **P(Greek)**: Whisper-small's language identification, the softmax over its 99 language tokens at
  the first decoding step (what Whisper does when no language is forced), and its top guess. It
  asks literally whether a recognizer that knows Greek hears Greek, and it names the accent it
  hears instead. qa.py reports it (`asr_p_el`, `asr_lang`) through asr.mjs.
* **Text CER** (main criterion): Whisper's transcript with Greek forced, against the line's monotonic
  spelling (`render_google.monotonic`), letters only (case, accents, punctuation and word spacing
  ignored: Whisper joins ancient words at random, "Πολύμεν").
* **Sound CER**: both sides reduced to Modern Greek sounds (`qa.greek_loose`: η ι υ ει οι = i, ω = o,
  αι = e, double letters single), so spellings Whisper cannot know (αλλάλοις / αλάλης) cost nothing.
* **θ**: the share of the reference's θ that Whisper writes back. θ is the weak sound (heard as σ).
* pYIN F0, DNSMOS P.835 SIG, MFCC 1-12 centroid distance between recipes on the same lines, and
  periodicity (median autocorrelation peak of the loud frames: lower = breathier or hoarser voice).

Test set: the 19 real lines, each with its own delivery. In the screening rounds every candidate
reads all 19 as one speaker (speed 0.95, the Modern Greek defaults: accent 1.5 st), rendered by
render.py's own pipeline (`render_clip`, Opus and back) into a scratch folder. The lab ran
Whisper-small int8 directly in onnxruntime (one encoder pass gives both the language and the
transcript; same model files as asr.mjs, transcripts matching transformers.js on the clips
compared). Noise over 19 lines is about +-0.09 on mean P(Greek) and +-0.03 on CER (one standard
error): smaller differences are ties. A transcript stuck in a loop counts as CER 1.

| candidate (timbre \| prosody) | text CER | sound CER | P(Greek) | Greek top | θ | F0 speech / command | SIG | note |
|---|---|---|---|---|---|---|---|---|
| *Baseline (29 clips)* | | | | | | | | |
| current clips: reconstructed IPA, voices.json (hm_psi herald, English/Italian/Spanish blends) | 0.289 | 0.187 | 0.07 | 2/29 | 0.60 | 116 / 141 | 3.40 |  |
| the same voices.json recipes reading `ipa_el` | 0.231 | 0.077 | 0.25 | 7/29 | 0.65 | 114 / 148 | 3.34 | the pronunciation alone |
| *M1: one voice, 19 lines* | | | | | | | | |
| em_santa | 0.212 | 0.081 | 0.52 | 13/19 | 0.43 | 139 / 162 | 3.21 | Spanish; Greek top on 13/19 |
| pm_santa | 0.245 | 0.084 | 0.55 | 12/19 | 0.21 | 146 / 172 | 3.19 | Portuguese |
| em_alex | 0.255 | 0.071 | 0.27 | 6/19 | 0.21 | 133 / 161 | 3.15 | Spanish; heard as Spanish (8/19) |
| pm_alex | 0.258 | 0.109 | 0.30 | 6/19 | 0.21 | 137 / 166 | 3.22 |  |
| im_nicola | 0.247 | 0.108 | 0.15 | 3/19 | 0.14 | 110 / 128 | 3.41 | Italian; heard as Spanish/Italian |
| am_onyx | 0.175 | 0.059 | 0.13 | 3/19 | 0.57 | 90 / 104 | 3.50 | clearest, but heard as Spanish/English |
| am_michael | 0.220 | 0.102 | 0.39 | 8/19 | 0.71 | 117 / 142 | 3.32 |  |
| bm_george | 0.253 | 0.077 | 0.33 | 7/19 | 0.64 | 146 / 174 | 3.33 | one looping transcript (capped at 1) |
| bm_lewis | 0.270 | 0.085 | 0.24 | 4/19 | 0.64 | 93 / 107 | 3.14 |  |
| *M2/M3: timbre \| prosody blends, 19 lines* | | | | | | | | |
| am_onyx \| em_santa | 0.227 | 0.066 | 0.17 | 4/19 | 0.29 | 147 / 167 | 3.30 | English timbre: Greek-ness lost |
| am_michael \| em_santa | 0.199 | 0.055 | 0.41 | 9/19 | 0.79 | 146 / 172 | 3.25 |  |
| michael 50 + em_santa 50 \| em_santa | 0.210 | 0.061 | 0.52 | 10/19 | 0.79 | 146 / 177 | 3.23 | clear θ, Greek kept |
| george 50 + em_santa 50 \| em_santa | 0.190 | 0.065 | 0.53 | 11/19 | 0.36 | 140 / 162 | 3.30 |  |
| lewis 50 + em_santa 50 \| em_santa | 0.190 | 0.071 | 0.50 | 12/19 | 0.71 | 154 / 165 | 3.24 |  |
| em_santa \| onyx 50 + em_santa 50 | 0.181 | 0.048 | 0.59 | 14/19 | 0.50 | 107 / 132 | 3.22 | deep without pitch shifting |
| pm_santa \| onyx 50 + pm_santa 50 | 0.211 | 0.057 | 0.63 | 13/19 | 0.14 | 112 / 135 | 3.21 | θ heard as σ |
| em_santa \| onyx 70 + em_santa 30 | 0.194 | 0.061 | 0.61 | 14/19 | 0.43 | 100 / 121 | 3.20 |  |
| em_santa \| onyx 30 + em_santa 70 | 0.175 | 0.044 | 0.58 | 14/19 | 0.50 | 118 / 146 | 3.25 |  |
| em_santa 70 + michael 30 \| onyx 50 + em_santa 50 | 0.226 | 0.033 | 0.64 | 13/19 | 0.64 | 112 / 134 | 3.28 |  |
| em_santa 70 + onyx 30 \| onyx 50 + em_santa 50 | 0.177 | 0.045 | 0.51 | 11/19 | 0.50 | 105 / 132 | 3.31 |  |
| george 50 + em_santa 50 \| onyx 50 + em_santa 50 | 0.214 | 0.060 | 0.60 | 13/19 | 0.57 | 112 / 134 | 3.39 |  |
| pm_santa \| george 50 + pm_santa 50 | 0.167 | 0.077 | 0.60 | 13/19 | 0.36 | 146 / 169 | 3.23 |  |
| em_santa 50 + pm_santa 50 (both halves) | 0.229 | 0.098 | 0.52 | 11/19 | 0.29 | 146 / 175 | 3.21 |  |
| *Per speaker (full recipe with EQ, 19 lines)* | | | | | | | | |
| Leonidas, em_santa \| onyx 50 + em_santa 50, speed 0.9 | 0.183 | 0.055 | 0.54 | 14/19 | 0.57 | 106 / 133 | 3.21 |  |
| ... speed 0.85 | 0.183 | 0.056 | 0.58 | 14/19 | 0.64 | 106 / 131 | 3.22 |  |
| ... accent 0 st | 0.183 | 0.060 | 0.59 | 13/19 | 0.50 | 102 / 129 | 3.20 |  |
| ... accent 3 st | 0.200 | 0.070 | 0.58 | 13/19 | 0.57 | 110 / 138 | 3.23 | strong accent: worse |
| ... θ held 1.4x | 0.179 | 0.059 | 0.55 | 13/19 | 0.50 | 107 / 133 | 3.21 | no gain |
| **Leonidas: em_santa 70 + onyx 30 \| onyx 50 + em_santa 50, 0.9** | 0.185 | 0.040 | 0.49 | 13/19 | 0.64 | 112 / 132 | 3.34 | chosen: steadier, more distinct |
| Dienekes, george 50 + em_santa 50 \| em_santa, -1.5 st | 0.186 | 0.059 | 0.50 | 12/19 | 0.50 | 127 / 150 | 3.34 |  |
| ... -3 st | 0.195 | 0.069 | 0.49 | 11/19 | 0.36 | 118 / 139 | 3.37 |  |
| **Dienekes: george 70 + em_santa 30 \| em_santa, -1.5 st** | 0.176 | 0.045 | 0.48 | 11/19 | 0.29 | 124 / 150 | 3.32 | chosen: 29 from the king (was 22) |
| ... george 50, formant 0.97 | 0.192 | 0.075 | 0.58 | 14/19 | 0.43 | 123 / 152 | 3.31 |  |
| ... fable 50 + pm_santa 50 | 0.302 | 0.125 | 0.26 | 5/19 | 0.14 | 136 / 153 | 3.38 | Greek-ness lost |
| **Lookout: michael 50 + em_santa 50 \| em_santa, speed 1.0** | 0.165 | 0.060 | 0.55 | 12/19 | 0.64 | 144 / 171 | 3.24 | chosen |
| ... formant 1.02 | 0.224 | 0.063 | 0.55 | 12/19 | 0.50 | 150 / 175 | 3.22 |  |
| Herald, pm_santa \| george 50 + pm_santa 50, 0.88 | 0.182 | 0.080 | 0.59 | 13/19 | 0.29 | 149 / 172 | 3.25 | too near Leonidas (MFCC 10) |
| ... -2 st | 0.207 | 0.099 | 0.58 | 14/19 | 0.29 | 132 / 153 | 3.26 |  |
| **Herald: george 50 + pm_santa 50 (both halves), 0.88** | 0.185 | 0.066 | 0.57 | 12/19 | 0.57 | 148 / 170 | 3.28 | chosen: 22 from Leonidas |
| ... fable 50 + pm_santa 50 | 0.244 | 0.090 | 0.19 | 3/19 | 0.36 | 157 / 180 | 3.30 | Greek-ness lost |
| Trachinian, lewis 50 + em_santa 50 (both halves), 1.0 | 0.194 | 0.053 | 0.49 | 11/19 | 0.79 | 137 / 150 | 3.28 | breathy (pYIN voiced 0.08) |
| **Trachinian: lewis 50 + em_santa 50 \| em_santa, 1.0** | 0.188 | 0.073 | 0.54 | 12/19 | 0.50 | 160 / 180 | 3.21 | chosen |
| ... echo 50 + em_santa 50 (both halves) | 0.189 | 0.053 | 0.34 | 6/19 | 0.29 | 131 / 152 | 3.44 | steadier, less Greek |
| ... adam 50 + em_santa 50 \| em_santa | 0.218 | 0.040 | 0.40 | 9/19 | 0.57 | 157 / 188 | 3.31 |  |

What the rounds showed:

1. **The pronunciation alone** helps: the old recipes reading `ipa_el` raise P(Greek) from 0.07 to
   0.25 and cut the sound CER from 0.19 to 0.08.
2. **Of the voices alone**, the Spanish and Portuguese "santa" voices are the ones Whisper hears as
   Greek (0.52 / 0.55; Greek on top for 13 and 12 of 19 lines). Every other voice scores 0.13-0.39.
   em_alex is heard as Spanish and im_nicola as Spanish or Italian. am_onyx is the clearest (text CER 0.175) but is heard as Spanish
   or English (0.13).
3. **The Greek quality lives mostly in the timbre half** (style dims 0-127, the decoder that makes
   the sounds): onyx timbre with em_santa prosody falls to 0.17, while em_santa timbre with half of
   onyx's prosody keeps it (0.59). A deep English **prosody** half lowers the pitch to a deep man's
   without any pitch shifting (em_santa 139 Hz -> 107 Hz) and steadies the rhythm. An English
   **timbre** share clears θ (Mediterranean timbres write back 14-43% of θ, English ones 57-71%, a half-michael
   blend 79%), steadies the breathy santa voice and gives each speaker his own timbre. Half
   michael, george or lewis costs no Greek-ness (0.50-0.53); onyx weighs more (half: 0.33, 30%:
   0.51); an all-English timbre loses it.
4. **Accent**: 3 st on the stressed vowel scored worse than 1.5 st (text CER 0.200 vs 0.183); 0 and
   1.5 tied. Modern Greek has a stress accent, so it stays moderate. Speed 0.85-0.95 made no
   difference; a smaller vocal tract (formant 1.02) cost clarity (0.165 -> 0.224); holding θ 1.4x
   longer did not help.
5. **Distinctness** is the price of one Greek voice family: with the santa timbre in every speaker,
   the first cast was 14-44 apart (MFCC, qa.py pairs) against 33-58 before. Each speaker therefore got a different English
   partner (onyx, george, michael, lewis) and Leonidas and Dienekes, who share ten lines, a
   different melody as well: final pairs 16.5-43.5 (median within one speaker 18), Leonidas / Dienekes
   43.5 and 2.7 semitones apart (before: 57 and 1.0 st). The two closest pairs (Herald / Dienekes,
   Herald / Lookout, 16.5) never speak in the same scene. The herald, whom Leonidas answers, is 34.5
   from him (an all-pm_santa herald was 10).
6. **The war cry** ΑΛΑΛΑΙ comes back as "Αλαλέ" or "Αλλά λες": the right sounds (alalˈe), but
   Whisper cannot know the spelling -αί, and the held vowel sometimes sends it into a loop. Its
   text CER is not a voice problem. Dienekes' cry needed a smaller rise and less saturation (DNSMOS
   SIG 2.35 -> 2.64).

### Chosen recipes (voices_el.json)

| speaker | timbre | prosody | speed, pitch | F0 speech / command / shout, cry | text CER (old clips) | P(Greek) | why |
|---|---|---|---|---|---|---|---|
| Leonidas | em_santa 70 + am_onyx 30 | am_onyx 50 + em_santa 50 | 0.9; shouts at half accent energy | 105 / 136 / 157, 149 Hz | 0.165 (0.230) | 0.41 | deep and grave; onyx steadies the voice and sets him apart |
| Dienekes | bm_george 70 + em_santa 30 | em_santa; shout and cry bm_george 50 + em_santa 50 | 0.95, -1.5 st | 128 / 153 / 181, 161 Hz | 0.165 (0.235) | 0.50 | livelier veteran: other timbre, other melody, 2.4 st higher; gentler cry |
| Lookout | am_michael 50 + em_santa 50 | em_santa | 1.0 | - / 167 Hz | 0.153 (0.379) | 0.81 | young and quick; clearest θ |
| Herald | bm_george 50 + pm_santa 50 | bm_george 50 + pm_santa 50 | 0.88 | - / 163 Hz | 0.122 (0.805) | 0.96 | formal and slow, now fluent Greek; slower, less raised proclamation |
| Trachinian | bm_lewis 50 + em_santa 50 | em_santa | 1.04 | - / 189 Hz | 0.238 (0.643) | 0.91 | ordinary man, alarmed and quick |

(F0, text CER and P(Greek) on each speaker's own clips in the game, qa.py --asr; in brackets the
text CER of the reconstructed clips the game had before, scored by the same qa.py against the same
monotonic text. Every clip is at -16.15 to -15.95 LUFS.)

### Review fixes (2026-10-24, verified and in the game)

Two reviews of the voice lab's set (pronunciation as a Greek hears it; audio quality and character)
filed eleven findings. Each was re-tested here before anything changed: the clip rendered through
render.py's own `render_clip` with and without the fix, decoded by Whisper-small at three lead-in
offsets (0, 150, 300 ms of silence, so "3/3" is one deterministic render heard alike three times),
plus DNSMOS, pYIN and Whisper's language ID. Then all 29 clips were rendered and scored by qa.py.

Applied:

| finding | fix | before -> after (Whisper, Greek forced) |
|---|---|---|
| Kokoro renders the palatal stop c as ts or t | `ipa_el` c -> kʲ: anopaia Φωκής, fightInShade σκιά | "τυφωτσίς" -> "τυφωκείς" 3/3 (sound CER 0.075 -> 0.038, P(Greek) 0.41 -> 0.64); "υπόστιάων" -> "υπόσκια/υπόσκυάω" 3/3 |
| ç between vowels comes out voiced: μάχεσθε heard as μάγεσθε | `ipa_el` mˈaçesθe -> mˈaxesθe | "ημάγεστε" -> "ημάχεστε" 3/3, sound CER 0.056 -> 0.028, SIG 3.49 -> 3.58. The review proposed xʲ: heard as "ημάχιεστε" 3/3 (an extra glide), sound CER 0.056, SIG 3.46, so plain x |
| the sandhi toŋ ɡolonˈon is heard as γολωνόν and swallows the p of πάντες | `ipa_el` ton kolonˈon (the careful reading) | "επιτώνγολο νών, άντε" -> "επιτών κολονών, πάντε" 3/3, text CER 0.219 -> 0.094 |
| δε in ολίγοι δε άνδρες heard as βε | `ipa_el` ðe -> ððe (a slightly longer ð) | Leonidas "ο Λύγης Βεάνδρες" -> "ολίγιδε άντρες" 2/3, text CER 0.258 -> 0.161; Dienekes 3/3, 0.290 -> 0.161 |
| Leonidas' λαβέ dies into a loud whispered tail ("λαβές", "λαβερ") | `ipa_el` without the final period | "Μολών, λαβε" 3/3, text CER 0.111 -> 0.000, unvoiced tail 180 -> 60 ms |
| shouts breathier and lower in quality than the old cast | Leonidas `shout.accent_energy` 0.5; Dienekes `shout.prosody` bm_george 50 + em_santa 50 | shout clips SIG 3.12 -> 3.35, OVRL 2.47 -> 2.90, voiced 0.42 -> 0.53, same transcripts; shieldsUp 1 Leonidas had no voiced frame (SIG 2.74), now 3.11 at 162 Hz; Leonidas' shouts +2.5 st over his commands (was +0.6) |
| Dienekes' war cry no higher than his commands | Dienekes `cry.prosody` bm_george 50 + em_santa 50 | 152 -> 161 Hz (0.8 st over his commands, was level), SIG 2.85 -> 3.18, OVRL 2.25 -> 2.70, voiced 0.49 -> 0.82 |
| the Trachinian's line on the edge of "Croatian" | speed 1.0 -> 1.04 | language ID at the three lead-ins: Greek 0.57 / Greek 0.77 / Croatian (Slavic 0.59) -> Greek 0.91 / 0.69 / 0.80 (Slavic <= 0.12); 1.03-1.05 render identically, 0.96, 0.98 and 1.06+ flip to Croatian. Text CER 0.214 -> 0.238 (one letter), SIG 3.35 -> 3.29 |
| render.py's default (reconstructed) brought the Hindi herald back on `--only` | Modern Greek is the default of render.py and qa.py; `--only` with another recipe warns | `render.py --only herald` now renders voices_el.json / ipa_el |
| qa.py error rates uncapped (a looping transcript scored 15+) | capped at 1 per clip; median printed | latent (no loop in the game's clips); one of three lead-ins of Dienekes' new cry does loop |
| a false "e:875ms" flag on the cry | the duration flags use the recipe's own vowel cap (cry 950 ms) | no flags on the 29 clips |

Each `ipa_el` respelling keeps the transcribed reading in `ipa_el_editor` (lines.json renderNotes
has the details). qa.py's loose CER also maps the Modern Greek palatals (ʲ c ʝ ɟ ɲ ɾ), which never
occur in the reconstructed `ipa`.

Rejected:

* **Dienekes' timbre to bm_george 50 + em_santa 50 for a clearer θ.** Re-tested on all 13 of his
  clips with the respellings above: θ written back on 19 of 30 decodes against 17 (a tie; the
  respelled σκιά line already gets μαχόμεθα back), but text CER 0.198 against 0.163, sound CER
  0.048 against 0.020, his cry worse (sound CER 0.40) and respite 2's δε back to βε (ðð does not
  survive that timbre); distance from Leonidas on their shared lines 36.4 against 40.0. Kept at 70/30.
* Not filed by the reviews and not changed: θέντες heard as σέντες, αγαθοί as αγκαθοί in
  Leonidas' voice, and the lookout's missing beat between his two sentences (rendering them apart
  makes the beat but costs P(Greek) 0.41 -> 0.1). These go to the listening pass.

### Result (the game's 29 clips)

| | reconstructed clips (before) | voice lab set (before review) | **in the game** |
|---|---|---|---|
| Whisper text CER, mean (median) | 0.276 (0.241) | 0.177 (0.176) | **0.165 (0.172)** |
| sound CER, mean | 0.187 | 0.039 | **0.024** |
| P(Greek), mean / median | 0.06 / 0.00 | 0.45 / 0.41 | **0.51 / 0.51** |
| Greek Whisper's top language | 2 of 29 | 17 of 29 | **18 of 29** |
| top languages heard | English 10, Portuguese 5, Spanish 4, Latin 3 (herald: Sinhala) | Greek 17, Spanish 7, English 2 | Greek 18, Spanish 6, one each Hebrew, Maori, English, Arabic |
| θ written back | 12 of 20 | 10 of 20 | 12 of 20 |
| DNSMOS SIG mean (min); shouts | 3.40 (2.89); 3.41 | 3.28 (2.67); 3.12 | 3.35 (2.67); 3.35 |
| periodicity Leonidas / Dienekes / others (this pass's measure) | 0.82 / 0.74 / 0.72-0.82 | 0.70 / 0.79 / 0.59-0.82 | 0.70 / 0.81 / 0.60-0.82 |
| MFCC distance Leonidas / Dienekes (within one speaker ~18) | 57.0 (1.0 st apart) | 43.5 (2.7 st apart) | 40.0 (2.4 st apart) |
| loudness, true peak, clipping | -16.13..-15.94 LUFS, <= -1.53 dBTP, 0 | -16.14..-15.95 LUFS, <= -1.73 dBTP, 0 | -16.15..-15.95 LUFS, <= -1.55 dBTP, 0 |
| qa.py --strict hard failures | 0 | 0 | 0 |
| size | 400 KiB | 396 KiB | 404 KiB |

Examples (Whisper, Greek forced): ΩΔΕ ΛΕΓΕΙ ... ΠΕΜΨΟΝ ΤΑ ΟΠΛΑ -> "ό, δεν λέγει Βασιλεύς ξέρξεις
Λεονίδη, πέμψον τα όπλα." (P(Greek) 0.96; before: "Αυτό είναι ο κατάστας της Ελλάδας.", Sinhala);
ΑΘΑΝΑΤΟΙ· ΥΔΑΡΝΑΣ ΑΡΧΕΙ -> "Αθάνατοι Ιδάρνας Άρχι." (0.98); ΜΕΝΕΤΕ ΕΝ ΤΑΙ ΤΑΞΙ -> "Μένετε εντά
ταξι." (text CER 0); ΜΟΛΩΝ ΛΑΒΕ -> "Μολών, λαβε." (0); ΠΟΛΛΟΙ ΜΕΝ ΑΝΘΡΩΠΟΙ, ΟΛΙΓΟΙ ΔΕ ΑΝΔΡΕΣ ->
"Πολύ μεν άνθρωποι ολίγιδε άντρες." (0.91 / 0.96); the breakfast line -> "Αριστοποιήστε ως εναίδα
διπνισόμενη." (0.99).

Limits of this reading: it is still not a native voice. The santa voices are older, breathier men
(DNSMOS SIG 0.05 under the old cast; Leonidas' cry 2.67 is the weakest clip). θ is often heard as σ
(αγαθά -> "αγασά") and ɣ as γκ. Language ID on a one-second clip is a coin toss (ΜΟΛΩΝ ΛΑΒΕ is
transcribed right but "Hebrew"). All of this was decided by measurement, without listening; a
Greek speaker's listening pass is the real test. Put first on it: the Trachinian's line (close to
the speed where Whisper hears Croatian), μάχεσθε (x, a velar χ, where Greek has a palatal one),
Φωκής / σκιά (kʲ for the palatal κ), ολίγοι δε (the doubled ð), θέντες and αγαθοί, and the
war cries.

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
$PY -I spartan/tools/voice/render.py                  # render every line (Modern Greek reading), rewrite voicebank.js, delete stale clips
$PY -I spartan/tools/voice/render.py --only warcry    # one key (the bank is rebuilt from the clips on disk)
$PY -I spartan/tools/voice/render.py --out /tmp/try --qa   # render elsewhere, with a metrics JSON
$PY -I spartan/tools/voice/qa.py --asr --strict       # QA the clips in the repo (scored against ipa_el); exit 1 on a hard failure
$PY -I spartan/tools/voice/render.py --reading reconstructed --out /tmp/old    # the old 5th-century reading (voices.json, ipa)
$PY -I spartan/tools/voice/qa.py --root /tmp/old --ipa-field ipa --asr         # QA for clips of that reading
```

The game ships the Modern Greek reading, so it is the default of both scripts; render the
reconstructed one elsewhere (`--out`), never with `--only` into the game (render.py warns: the set
would mix two readings).

`THERMOPYLAE_VOICE_CACHE` moves the cache; `--model-dir` points at any folder holding
`kokoro-quantized.onnx`, `voices/` and `tokenizer.json`. ffmpeg with libopus must be on PATH. A
full render of 29 clips takes about 1 min 45 s on 4 CPU threads (50 s on the 2026-10-24 machine).

`lines.json` fields read by the renderer: `key`, `variant` (1..n), `speakers`, `caps` (the
subtitle), `en`, `ipa_el` (or the field the recipe file names: `ipa_el` for voices_el.json, `ipa` for voices.json),
`delivery` (a free word; see below), and optionally `ipa_by_speaker` (`{"Dienekes": "..."}`, a
per-speaker pronunciation; `ipa_el_by_speaker` for the Modern Greek field) and `asr_ref` (a Greek
spelling Whisper should produce, for an exact CER in QA). qa.py scores every transcript against the
monotonic spelling of `polytonic` as well (`asr_text_cer`, `asr_sound_cer`). Keys appear in `voicebank.js` in the order of `lines.json`.
`ipa_editor` / `ipa_el_editor` are not read: they keep the reading as transcribed where `ipa` / `ipa_el`
was respelled for Kokoro.

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

Final QA of the reconstructed set (qa.py --ipa-field ipa --asr --strict; the game's clips until the
Modern Greek reading replaced them): 0 hard failures; loudness -16.13 to -15.94 LUFS, true peak
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
