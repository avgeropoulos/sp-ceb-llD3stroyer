"""Basic Pitch training targets: note / onset / contour, at the model's resolution.

basic-pitch 0.4.0 ships training code (basic_pitch/train.py, basic_pitch/data/*) but builds its
targets with mirdata's ``to_sparse_index`` inside the per-dataset Beam pipelines; it has no
stand-alone target builder to import. This module re-implements exactly those semantics
(mirdata 1.0 ``NoteData.to_sparse_index`` / ``MultiF0Data.to_sparse_index``) in numpy:

* time grid  ``t = arange(0, duration + ANNOTATION_HOP, ANNOTATION_HOP)``, ANNOTATION_HOP = 1/86 s;
* notes      every frame from the frame nearest the note start to the frame nearest its end
             (inclusive), at the note bin nearest in log frequency (88 bins from A0);
* onsets     the single frame nearest the note start, same bin;
* contours   one entry per frame the note sounds, at the contour bin (3 per semitone) nearest the
             note's TRUE frequency;
* values     1.0 ("binary"); pitches outside A0..C8 are dropped, like mirdata.

Decision on octave-pair courses: Basic Pitch is a multi-pitch model and an octave-pair course really
sounds two pitches, so BOTH sounding pitches (fretted note and its octave) are labelled in all three
targets. Unison courses sound one pitch (the +-2 cent pair detuning is far below a contour bin).
The app's post-processing merges an octave pair back into one tab note on one course.

Detune: with a tuning offset of ``c`` cents (|c| <= 50), notes and onsets keep the nearest semitone
(the written note) while the contours follow the real, detuned frequency. ``c`` is recorded in the
dataset metadata.
"""
from typing import Dict, List, Tuple

import numpy as np

from .constants import (
    ANNOTATION_HOP,
    FREQ_BINS_CONTOURS,
    FREQ_BINS_NOTES,
    N_FREQ_BINS_CONTOURS,
    N_FREQ_BINS_NOTES,
    midi_to_hz,
)
from .instruments import Instrument
from .notes import NoteEvent

MAX_DETUNE_CENTS = 50.0

_LOG_NOTES = np.log(FREQ_BINS_NOTES)
_LOG_CONTOURS = np.log(FREQ_BINS_CONTOURS)


def time_scale(duration: float) -> np.ndarray:
    return np.arange(0, duration + ANNOTATION_HOP, ANNOTATION_HOP)


def _closest(values: np.ndarray, grid: np.ndarray) -> np.ndarray:
    """mirdata.annotations.closest_index for 1-D sorted grids: nearest index, -1 when outside."""
    idx = np.abs(values[:, None] - grid[None, :]).argmin(axis=1)
    idx[(values > grid.max()) | (values < grid.min())] = -1
    return idx


def sounding_pitches(instrument: Instrument, note: NoteEvent, tuning_cents: float = 0.0) -> List[Tuple[int, float]]:
    """(label MIDI pitch, true frequency in Hz) of every pitch the course sounds for ``note``."""
    if abs(tuning_cents) > MAX_DETUNE_CENTS:
        raise ValueError(f"tuning offset {tuning_cents} cents: keep |cents| <= {MAX_DETUNE_CENTS} so labels stay on the written semitone")
    out = []
    for off in instrument.courses[note.course].sounding_offsets():
        m = note.midi + off
        out.append((m, midi_to_hz(m + tuning_cents / 100.0)))
    return out


def _dedupe(idx: List[Tuple[int, int]], n_time: int, n_freq: int) -> np.ndarray:
    """Unique (time, freq) pairs, sorted, clipped to the matrix (overlapping notes, e.g. an octave
    string landing on another course's note, would otherwise give duplicate sparse entries)."""
    if not idx:
        return np.zeros((0, 2), dtype=np.int64)
    a = np.unique(np.asarray(idx, dtype=np.int64), axis=0)
    keep = (a[:, 0] >= 0) & (a[:, 0] < n_time) & (a[:, 1] >= 0) & (a[:, 1] < n_freq)
    return a[keep]


def build_sparse_targets(
    instrument: Instrument, notes: List[NoteEvent], duration: float, tuning_cents: float = 0.0
) -> Dict[str, np.ndarray]:
    """Sparse targets in basic_pitch's tf.train.Example layout (see tfrecords.py)."""
    ts = time_scale(duration)
    n_time = len(ts)
    max_idx = n_time - 1
    note_idx, onset_idx, contour_idx = [], [], []
    for note in notes:
        t0, t1 = _closest(np.array([note.start, note.end]), ts)
        if t0 == -1 and t1 == -1:
            continue
        for label_midi, hz in sounding_pitches(instrument, note, tuning_cents):
            f_note = _closest(np.array([np.log(midi_to_hz(label_midi))]), _LOG_NOTES)[0]
            f_cont = _closest(np.array([np.log(hz)]), _LOG_CONTOURS)[0]
            frames = range(max(t0, 0), (t1 if t1 != -1 else max_idx) + 1)
            if f_note != -1:
                if t0 != -1:
                    onset_idx.append((t0, f_note))
                note_idx.extend((t, f_note) for t in frames)
            if f_cont != -1:
                contour_idx.extend((t, f_cont) for t in frames)
    notes_i = _dedupe(note_idx, n_time, N_FREQ_BINS_NOTES)
    onsets_i = _dedupe(onset_idx, n_time, N_FREQ_BINS_NOTES)
    contours_i = _dedupe(contour_idx, n_time, N_FREQ_BINS_CONTOURS)
    return {
        "notes_indices": notes_i,
        "notes_values": np.ones(len(notes_i), np.float32),
        "onsets_indices": onsets_i,
        "onsets_values": np.ones(len(onsets_i), np.float32),
        "contours_indices": contours_i,
        "contours_values": np.ones(len(contours_i), np.float32),
        "notes_onsets_shape": np.array([n_time, N_FREQ_BINS_NOTES], np.int64),
        "contours_shape": np.array([n_time, N_FREQ_BINS_CONTOURS], np.int64),
    }


def to_dense(sparse: Dict[str, np.ndarray]) -> Dict[str, np.ndarray]:
    """Dense (n_frames, bins) float32 matrices: ``note``, ``onset``, ``contour``."""
    out = {}
    for key, name, shape in (
        ("notes", "note", "notes_onsets_shape"),
        ("onsets", "onset", "notes_onsets_shape"),
        ("contours", "contour", "contours_shape"),
    ):
        m = np.zeros(tuple(sparse[shape]), np.float32)
        idx = sparse[f"{key}_indices"]
        if len(idx):
            m[idx[:, 0], idx[:, 1]] = sparse[f"{key}_values"]
        out[name] = m
    return out
