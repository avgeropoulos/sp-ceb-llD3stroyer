# Bouzouki fine-tuning of Basic Pitch (Phase 6, prepared, not run)

This folder fine-tunes Spotify's **Basic Pitch** (`icassp_2022` / `nmp`, Apache-2.0) on bouzouki
audio. The app runs the exported `nmp.onnx` (22050 Hz, 2 s windows of 43 844 samples; outputs:
note, onset and contour). Everything here can be built from **synthetic data only**: MIDI phrases
generated here, rendered by a Karplus-Strong bouzouki synth. Your own recordings (see below) and
licensed sample libraries can be added later. **No training has been run yet.**

```
training/
  DATA_LICENSES.yaml        every data source + license + approval  (ships with approved: false)
  requirements.txt          pinned direct dependencies (Apache/MIT/BSD/MPL/PSF)
  requirements-nodeps.txt   basic-pitch==0.4.0 (installed with --no-deps)
  constraints.txt           full tested lock, incl. transitive packages
  bouzouki_train/
    instruments.py   tetrachordo / trichordo courses, the dromoi (same as bouzouki-core)
    notes.py         NoteEvent, MIDI and CSV formats
    phrases.py       synthetic MIDI phrase generator                         (step 1)
    synth.py         MIDI -> audio: Karplus-Strong + pluggable samples/SoundFont (step 2)
    augment.py       audiomentations chain + label-consistent +-50 cent detune  (step 3)
    targets.py       Basic Pitch note/onset/contour targets                  (step 3)
    tfrecords.py     records in basic_pitch's exact training schema
    real_data.py     your recordings: naming, labels, loading
    build_dataset.py render + augment + targets -> TFRecords                 (step 3)
    license_gate.py  DATA_LICENSES.yaml gate + forbidden-source scan
    finetune.py      fine-tune, validate on real recordings, export .onnx/.tflite (step 4)
  tests/test_pipeline.py
```

## 0. Install

TensorFlow 2.15 (the newest basic-pitch 0.4.0 accepts) needs **Python 3.9-3.11**.

```sh
cd training
python3.11 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt -c constraints.txt
pip install --no-deps -r requirements-nodeps.txt
python -m pytest -q
```

## 1. Generate MIDI phrases

```sh
python -m bouzouki_train.phrases --out data/synth/midi --n 400 --every-fret-passes 3 --seed 0
```

Per instrument (tetrachordo and trichordo): `--n` random phrases, the kinds in turn:
`scale` (lines in a random dromos and root, steps/leaps, eighths/sixteenths, 70-200 BPM),
`tremolo` (held notes as 10-18 picks/s, alternating down/up-stroke strength, sometimes doubled
in thirds/sixths/octaves), `chromatic` (runs along one course or across courses), `random`
(random frets, 2-3-course chords, rests, long ringing notes); plus `everyfret` phrases: every fret
0..26/27 of every course, `--every-fret-passes` times. A course never sounds two notes at once
(a new pick ends the note before it). Each course is its own MIDI track `<instrument>/course<k>`.

## 2. Render (optional listening check)

```sh
python -m bouzouki_train.synth --midi data/synth/midi --out data/synth/wav
```

`KarplusStrongRenderer` ports `bouzouki-core/.../BouzoukiSynth.kt`: per string a Karplus-Strong
loop (3-tap loss filter, fractional-delay all-pass) excited by plectrum-filtered noise combed at the
picking point; **octave-pair courses sound both strings** (note + octave at 0.62, detuned -1.5/+2
cents), **unison courses two strings detuned -2.2/+2.2 cents**; a plectrum click; a new pick damps the
ringing string (t60 25 ms); body EQ, small Schroeder room and soft limiter. Each render randomises
pair detuning, octave-string level, pick position, body EQ and room level. Tremolo comes from the
MIDI. (Hammer-ons/slides of the app synth are not modelled.)

Licensed sample libraries / SoundFonts plug in through `Renderer` (none shipped):
`--renderer samples --samples-dir DIR` (files named like your recordings,
`<instrument>/<course>-<fret>-<take>.wav`) or `--renderer soundfont --soundfont X.sf2` (needs the
external `fluidsynth` program, LGPL, run as a separate process). Add them to DATA_LICENSES.yaml and
pass `--renderer-source-id`.

## 3. Build the training records (augmentation + targets)

```sh
python -m bouzouki_train.build_dataset synth --midi-dir data/synth/midi --out data/tfrecords \
    --copies 3 --ir-dir data/ir --val-fraction 0.05 [--wav-dir data/listen]
python -m bouzouki_train.build_dataset real --real-dir data/real --out data/tfrecords \
    --split train --augment --copies 4                                 # your training takes
```

Records go to `data/tfrecords/<dataset>/splits/{train,validation}/*.tfrecord` in basic_pitch's own
schema (read with `basic_pitch.data.tf_example_deserialization`), with `metadata.jsonl` per dataset
(source ids, cents offset, augmentation, instrument).

**Augmentation** (audiomentations, MIT; only effects that keep the timing): ApplyImpulseResponse
with your IR folder (`--ir-dir`; trim IR pre-delay), RoomSimulator (pyroomacoustics), 7-band EQ,
high/low-pass, a small compressor (+ Limiter if `cylimiter` is installed), colored/white noise, gain;
Mp3Compression only with `--mp3` (its encoders are LGPL/GPL, not in requirements).
Copy 0 of each synthetic phrase is clean unless `--augment-first`.

**Detune +-50 cents, consistently**: synthetic audio is rendered with the strings tuned `c` cents
off (timing unchanged); real audio is varispeed-resampled and its note times scaled with it. In both
cases notes/onsets stay on the written semitone, the contours follow the true pitch, and `cents` is
recorded in `metadata.jsonl`. |c| > 50 is refused (the label would change semitone).

**Targets** (`targets.py`): basic-pitch 0.4.0 contains training code (`basic_pitch/train.py`,
`basic_pitch/data/*`, TFRecord (de)serialization) but builds targets with mirdata's
`to_sparse_index` inside per-dataset Beam pipelines, so there is nothing stand-alone to import;
`targets.py` re-implements those semantics: 86 frames/s grid, note frames inclusive from the frame
nearest the start to the frame nearest the end, one onset frame, 88 note bins from A0, 264 contour
bins (3/semitone) at the nearest log frequency, binary values. Constants come from
`basic_pitch/constants.py`.

**Octave pairs are labelled with both pitches.** Basic Pitch is multi-pitch and an octave course
really sounds the note and its octave, so both are in the note, onset and contour targets (otherwise
the model would be taught that a clearly present octave is silence). The app's post-processing
merges an octave pair back into one tab note on one course. Unison courses get one pitch.

## 4. Fine-tune (needs the owner's license approval)

```sh
python -m bouzouki_train.license_gate --data data/tfrecords --packages     # what is missing?
python -m bouzouki_train.finetune --data data/tfrecords --val-real data/real_val --out runs/ft1 \
    --lr 1e-4 --freeze frontend --epochs 50 --steps-per-epoch 200 --batch-size 16
```

* Starts from the icassp_2022 weights inside the basic-pitch wheel (or `--pretrained DIR`).
* Low LR (1e-4, Adam), basic_pitch's own losses (label smoothing 0.2, `--weighted-onset-loss`).
* `--freeze none | frontend | contour`: frontend = the CQT batch-norm; contour = also the contour
  branch, so only the note/onset heads adapt (start there if you have little real data).
* `--val-real DIR`: held-out real recordings (raw files, same layout as below), cut into 2 s windows;
  drives checkpointing/early stopping and logs a note-frame F1. Without it the synthetic validation
  split is used. Never put validation takes in `data/real`.
* Exports `runs/ft1/<time>/export/{saved_model,nmp.onnx,nmp.tflite}`. The ONNX keeps the shipped
  model's tensor names (`serving_default_input_2:0` -> `StatefulPartitionedCall:0/1/2` = contour /
  note / onset) that `BasicPitch.kt` looks up; both exports are checked against Keras.
  Copy `nmp.onnx` over the app's model asset to ship it.

**Dry run** (no approval needed, no training): `--dry-run` builds the datasets, takes one batch,
checks shapes against the model (audio `(B, 43844, 1)`, note/onset `(B, 172, 88)`,
contour `(B, 172, 264)`), runs one forward pass and the losses, writes `dry_run.json`.
`--check-export` also exports the loaded model.

### The license gate

`finetune.py` refuses to train unless `DATA_LICENSES.yaml` has `owner_approval.approved: true`
and every source id the run uses (dataset metadata, `basic_pitch_icassp_2022`,
`owner_recordings_validation`) is listed with a filled-in license and `approved: true`.
Non-commercial licenses are always refused. The file ships with `approved: false` everywhere and a
checklist; only the owner flips the flags.

**Forbidden, always** (checked by name in the YAML sources, data/model paths, the repository's file
names, installed packages and imported modules, also in `--dry-run`):

| Name | What | Why |
|---|---|---|
| SynthTab | synthetic guitar-tab dataset | CC BY-NC 4.0 (non-commercial) |
| MuScriptor | transcription model weights | non-commercial license |
| gtrsnipe | guitar transcription code | non-commercial license |

Do not use them for training, validation, pseudo-labels or as a teacher, and do not copy code from them.

## Recording your own set

Record mono (or stereo, it is mixed down), 44.1/48 kHz WAV, to a click, close mic plus room/phone
takes. Tune to A = 440 Hz. Put held-out validation takes in a separate folder (`data/real_val`)
with the same layout; ideally different days/strings/rooms.

```
data/real/
  latency.json                         optional {"default": 0.012, "tetrachordo/random-07-1": 0.020}
  tetrachordo/
    C3-0-1.wav  C3-0-2.wav ... C3-27-3.wav   every fret of every course, 3 takes: <course>-<fret>-<take>
    F3-*.wav    A3-*.wav    D4-*.wav
    chromatic-C3up-1.wav   + chromatic-C3up-1.csv    phrases: <kind>-<name>-<take> + planned notes
    random-07-1.wav        + random-07-1.mid
    tremolo-hitzaz-1.wav   + tremolo-hitzaz-1.csv
  trichordo/
    D3-0-1.wav ... A3-*.wav  D4-*.wav  ...
```

* **Course names** = open pitch of the course's thick string: tetrachordo `C3 F3 A3 D4`, trichordo
  `D3 A3 D4` (never names that differ only by case).
* **Single notes** `<course>-<fret>-<take>.wav`: one pick, let it ring ~2 s, then damp. Labels are
  detected from the envelope; add a same-named `.csv` to override.
* **Phrases** (chromatic runs up/down each course, scales in dromoi, tremolo, random patterns,
  chords): plan them first as MIDI or CSV on the click grid (`phrases.py` can generate them:
  write CSV with `notes.write_csv`, or use its `.mid`), play them to the click, and keep the plan
  next to the WAV with the same stem.
* **CSV** columns `onset_s,offset_s,course,fret[,velocity]` (course 0 = bass course), times in
  seconds from the WAV's first sample. **MIDI**: one track per course named
  `<instrument>/course<k>` (as written by `phrases.py`); otherwise courses are guessed.
* Start the recording with the click's count-in; measure the click-to-pick latency once (or per
  take) and put it in `latency.json` (seconds added to the planned times).
* Then: `build_dataset real --split train` for training takes; `finetune --val-real data/real_val`.

## Licenses

* This code: written for this repository. basic-pitch (code + icassp_2022 weights): Apache-2.0.
* `requirements.txt` pins only Apache/MIT/BSD/MPL/PSF packages; TensorFlow, tf2onnx, onnx are
  Apache-2.0, onnxruntime MIT, audiomentations MIT, pyroomacoustics MIT, pretty_midi/mido MIT.
* Transitive, pulled in by audiomentations (for the owner to accept, see the checklist): librosa
  (ISC, MIT-equivalent), **soxr (LGPL-2.1+)**, soundfile (BSD-3, bundles **libsndfile, LGPL-2.1**),
  numba/llvmlite (BSD). They run only at training time and are not distributed with the app or
  embedded in the model; this pipeline's own audio I/O uses scipy only.
* `python -m bouzouki_train.license_gate --packages` lists installed packages outside
  Apache/MIT/BSD/MPL/PSF/ISC.

## Verified here / not verified

Verified (Python 3.11, tensorflow-cpu 2.15.1, CPU, no GPU): `pytest` (23 tests); a dry run of 3
phrases -> render -> augment (IRs, room, EQ, noise, detune) -> targets -> TFRecords -> basic_pitch's
reader -> `finetune --dry-run --check-export` (shapes OK, pretrained losses computed; the re-exported
ONNX equals the shipped `nmp.onnx` within 5e-7 with the same tensor names); the gate refuses the
shipped YAML. **Not verified:** an actual training run, GPU, the sample-library and SoundFont
renderers (no data shipped), Mp3Compression, and whether fine-tuning improves real bouzouki
transcription.
