"""Build Basic Pitch training records: render + augment + targets -> TFRecords.

Synthetic (MIDI phrases from phrases.py, rendered on the fly)::

    python -m bouzouki_train.build_dataset synth --midi-dir data/synth/midi --out data/tfrecords \\
        --copies 3 --ir-dir data/ir --val-fraction 0.05

Real recordings (the owner's own; see real_data.py for the naming)::

    python -m bouzouki_train.build_dataset real --real-dir data/real --out data/tfrecords \\
        --split train --copies 4 --augment           # real training data, augmented
    # held-out validation recordings are read raw by finetune.py --val-real (no records needed)

Every example's metadata line (``<out>/<dataset>/metadata.jsonl``) lists the DATA_LICENSES.yaml
source ids it was made from; finetune.py refuses records whose sources are not approved.
"""
import argparse
import glob
import hashlib
import os
from typing import List, Optional

import numpy as np

from .audio_io import wav_bytes, write_wav
from .constants import AUDIO_N_SAMPLES, AUDIO_SAMPLE_RATE, FFT_HOP
from .instruments import Instrument
from .notes import NoteEvent, read_midi
from .phrases import phrase_duration
from .synth import make_renderer
from .targets import MAX_DETUNE_CENTS, build_sparse_targets


def _split_of(key: str, val_fraction: float) -> str:
    """Deterministic split by name, so all copies of one phrase land in the same split."""
    h = int(hashlib.sha1(key.encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "validation" if h < val_fraction else "train"


def make_record(
    writer,
    split: str,
    file_id: str,
    instrument: Instrument,
    audio: np.ndarray,
    notes: List[NoteEvent],
    cents: float,
    sources: List[str],
    extra: Optional[dict] = None,
    wav_dir: Optional[str] = None,
):
    from .tfrecords import make_example

    # basic_pitch's loader cuts random 2 s windows and drops shorter clips: pad with silence.
    min_len = AUDIO_N_SAMPLES + FFT_HOP * 4
    if len(audio) < min_len:
        audio = np.pad(audio, (0, min_len - len(audio)))
    duration = len(audio) / AUDIO_SAMPLE_RATE
    sparse = build_sparse_targets(instrument, notes, duration, cents)
    ex = make_example(file_id, sources[0], wav_bytes(audio), sparse)
    meta = {"file_id": file_id, "instrument": instrument.name, "cents": round(cents, 2), "sources": sources,
            "duration_s": round(duration, 3), "n_notes": len(notes), **(extra or {})}
    writer.write(split, ex, meta)
    if wav_dir:
        os.makedirs(wav_dir, exist_ok=True)
        write_wav(os.path.join(wav_dir, file_id.replace("/", "_") + ".wav"), audio)
    return sparse


def build_synth(args) -> int:
    from .augment import Augmenter
    from .tfrecords import ShardWriter

    paths = sorted(glob.glob(os.path.join(args.midi_dir, "*.mid")))
    if not paths:
        raise SystemExit(f"no .mid files in {args.midi_dir}")
    renderer = make_renderer(args.renderer, args.samples_dir, args.soundfont)
    aug = None if args.no_augment else Augmenter(ir_dir=args.ir_dir, room=not args.no_room, mp3=args.mp3, detune_cents=0.0)
    rng = np.random.default_rng(args.seed)
    sources = [args.source_id]  # the phrase generator + Karplus-Strong renderer are this repo's code
    if args.renderer != "ks":
        sources.append(args.renderer_source_id or "UNLISTED_RENDERER_DATA")
    if aug and args.ir_dir:
        sources.append(args.ir_source_id)
    n = 0
    with ShardWriter(args.out, args.dataset, args.per_shard) as w:
        for p in paths:
            inst, notes = read_midi(p)
            stem = os.path.splitext(os.path.basename(p))[0]
            split = _split_of(stem, args.val_fraction)
            for k in range(args.copies):
                # Detune at render time (strings tuned, timing unchanged); labels keep the semitone.
                cents = float(rng.uniform(-args.detune_cents, args.detune_cents)) if rng.random() < args.detune_prob else 0.0
                audio = renderer.render(inst, notes, phrase_duration(notes), cents, rng)
                augmented = False
                if aug is not None and (k > 0 or args.augment_first):
                    audio, notes_k, _ = aug(audio, notes, rng, already_detuned=True)
                    augmented = True
                else:
                    notes_k = notes
                make_record(w, split, f"{args.dataset}/{stem}/{k}", inst, audio, notes_k, cents, sources,
                            {"renderer": args.renderer, "augmented": augmented, "midi": os.path.basename(p)}, args.wav_dir)
                n += 1
    print(f"wrote {n} examples to {os.path.join(args.out, args.dataset)}")
    return n


def build_real(args) -> int:
    from .augment import Augmenter
    from .real_data import load_real_dir
    from .tfrecords import ShardWriter

    recs = load_real_dir(args.real_dir, strict=not args.skip_unlabelled)
    aug = Augmenter(ir_dir=args.ir_dir, room=not args.no_room, mp3=args.mp3, detune_cents=args.detune_cents) if args.augment else None
    rng = np.random.default_rng(args.seed)
    sources = [args.source_id] + ([args.ir_source_id] if aug and args.ir_dir else [])
    n = 0
    with ShardWriter(args.out, args.dataset, args.per_shard) as w:
        for r in recs:
            copies = args.copies if aug else 1
            for k in range(copies):
                audio, notes, cents = r.audio, r.notes, 0.0
                if aug is not None and k > 0:  # copy 0 stays clean
                    audio, notes, cents = aug(audio, notes, rng)
                make_record(w, args.split, f"{args.dataset}/{r.rec_id}/{k}", r.instrument, audio, notes, cents, sources,
                            {"augmented": bool(aug is not None and k > 0)}, args.wav_dir)
                n += 1
    print(f"wrote {n} examples from {len(recs)} recordings to {os.path.join(args.out, args.dataset)}")
    return n


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, dataset, source):
        p.add_argument("--out", required=True, help="TFRecord base directory")
        p.add_argument("--dataset", default=dataset, help="dataset name (sub-directory)")
        p.add_argument("--source-id", default=source, help="DATA_LICENSES.yaml id of this data")
        p.add_argument("--ir-dir", help="folder of impulse-response .wav files (ApplyImpulseResponse)")
        p.add_argument("--ir-source-id", default="impulse_responses", help="DATA_LICENSES.yaml id of the IRs")
        p.add_argument("--no-room", action="store_true", help="disable RoomSimulator")
        p.add_argument("--mp3", action="store_true", help="enable Mp3Compression (needs an LGPL encoder, see augment.py)")
        p.add_argument("--detune-cents", type=float, default=MAX_DETUNE_CENTS)
        p.add_argument("--copies", type=int, default=2)
        p.add_argument("--per-shard", type=int, default=64)
        p.add_argument("--wav-dir", help="also write every example as WAV here, for listening")
        p.add_argument("--seed", type=int, default=0)

    s = sub.add_parser("synth", help="render MIDI phrases")
    common(s, "bouzouki_synth", "bouzouki_synth")
    s.add_argument("--midi-dir", required=True)
    s.add_argument("--renderer", choices=["ks", "samples", "soundfont"], default="ks")
    s.add_argument("--samples-dir")
    s.add_argument("--soundfont")
    s.add_argument("--renderer-source-id", help="DATA_LICENSES.yaml id of the sample library / SoundFont")
    s.add_argument("--detune-prob", type=float, default=0.7)
    s.add_argument("--val-fraction", type=float, default=0.05)
    s.add_argument("--no-augment", action="store_true")
    s.add_argument("--augment-first", action="store_true", help="augment copy 0 too (default: copy 0 is clean)")

    r = sub.add_parser("real", help="the owner's recordings")
    common(r, "bouzouki_real", "owner_recordings")
    r.add_argument("--real-dir", required=True)
    r.add_argument("--split", choices=["train", "validation", "test"], default="train")
    r.add_argument("--augment", action="store_true")
    r.add_argument("--skip-unlabelled", action="store_true")

    a = ap.parse_args(argv)
    for d in (a.detune_cents,):
        if abs(d) > MAX_DETUNE_CENTS:
            raise SystemExit(f"--detune-cents must be <= {MAX_DETUNE_CENTS}")
    build_synth(a) if a.cmd == "synth" else build_real(a)


if __name__ == "__main__":
    main()
