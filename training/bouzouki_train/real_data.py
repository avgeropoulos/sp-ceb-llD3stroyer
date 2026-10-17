"""The owner's own recordings: file naming, labels, loading.

Layout (see README "Recording your own set")::

    <real_dir>/<instrument>/<course>-<fret>-<take>.wav        one picked note, e.g. tetrachordo/C3-5-2.wav
    <real_dir>/<instrument>/<course>-<fret>-<take>.csv        optional planned note (else detected)
    <real_dir>/<instrument>/<kind>-<name>-<take>.wav          a phrase, e.g. trichordo/chromatic-A3up-1.wav
    <real_dir>/<instrument>/<kind>-<name>-<take>.csv | .mid   REQUIRED planned notes, click-aligned
    <real_dir>/latency.json                                   optional {"<instrument>/<stem>": seconds,
                                                              "default": seconds} added to label times

Course names are the open pitch of the course's thick string: tetrachordo C3 F3 A3 D4, trichordo
D3 A3 D4. Label times are seconds from the first sample of the WAV.
"""
import glob
import json
import os
import re
from dataclasses import dataclass
from typing import List, Optional

import numpy as np

from .audio_io import read_wav
from .constants import AUDIO_SAMPLE_RATE
from .instruments import COURSE_NAMES, INSTRUMENTS, Instrument
from .notes import NoteEvent, read_csv, read_midi, shift_time

SINGLE_NOTE = re.compile(r"^(?P<course>[A-G][0-9])-(?P<fret>\d{1,2})-(?P<take>\d+)$")


@dataclass
class Recording:
    rec_id: str  # "<instrument>/<stem>"
    instrument: Instrument
    audio: np.ndarray
    notes: List[NoteEvent]


def detect_single_note(audio: np.ndarray, fs: int = AUDIO_SAMPLE_RATE, on_db: float = -30.0, off_db: float = -45.0):
    """(start, end) of the one note in a single-note take, from the RMS envelope relative to its peak."""
    hop = 128
    n = len(audio) // hop
    if n == 0:
        return None
    rms = np.sqrt(np.mean(audio[: n * hop].reshape(n, hop) ** 2, axis=1) + 1e-12)
    db = 20 * np.log10(rms / rms.max())
    on = int(np.argmax(db > on_db))
    peak = on + int(np.argmax(db[on:]))
    below = np.nonzero(db[peak:] < off_db)[0]
    off = peak + (int(below[0]) if len(below) else n - 1 - peak)
    return on * hop / fs, max(off * hop / fs, on * hop / fs + 0.05)


def parse_single_note_name(instrument: Instrument, stem: str):
    """(course, fret) for a '<course>-<fret>-<take>' stem, else None."""
    m = SINGLE_NOTE.match(stem)
    if not m:
        return None
    names = COURSE_NAMES[instrument.name]
    if m["course"] not in names:
        raise ValueError(f"{instrument.name}/{stem}: unknown course {m['course']!r}, expected one of {names}")
    fret = int(m["fret"])
    if fret > instrument.frets:
        raise ValueError(f"{instrument.name}/{stem}: fret {fret} > {instrument.frets}")
    return names.index(m["course"]), fret


def load_real_dir(real_dir: str, fs: int = AUDIO_SAMPLE_RATE, strict: bool = True) -> List[Recording]:
    latency = {}
    lat_path = os.path.join(real_dir, "latency.json")
    if os.path.exists(lat_path):
        with open(lat_path) as f:
            latency = json.load(f)
    recs: List[Recording] = []
    for inst_name, inst in INSTRUMENTS.items():
        for wav in sorted(glob.glob(os.path.join(real_dir, inst_name, "*.wav"))):
            stem = os.path.splitext(os.path.basename(wav))[0]
            rec_id = f"{inst_name}/{stem}"
            offset = float(latency.get(rec_id, latency.get("default", 0.0)))
            base = os.path.splitext(wav)[0]
            audio = read_wav(wav, fs)
            notes: Optional[List[NoteEvent]] = None
            if os.path.exists(base + ".csv"):
                notes = read_csv(base + ".csv", inst, offset)
            elif os.path.exists(base + ".mid"):
                notes = shift_time(read_midi(base + ".mid", inst)[1], offset=offset)
            else:
                cf = parse_single_note_name(inst, stem)
                if cf is not None:
                    span = detect_single_note(audio, fs)
                    if span:
                        notes = [NoteEvent(span[0], span[1], inst.midi_at(*cf), cf[0])]
            if notes is None:
                msg = f"{wav}: no labels (.csv/.mid next to it) and not a '<course>-<fret>-<take>' single note"
                if strict:
                    raise ValueError(msg)
                print("skipping:", msg)
                continue
            recs.append(Recording(rec_id, inst, audio, notes))
    return recs
