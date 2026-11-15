"""Turn audio into a monophonic list of notes.

A sitar melody is one note at a time on the main string, so we track one
pitch (pYIN). Two things set it apart from other plucked instruments, and
both are handled here:

* Meend. The player pulls the string sideways and the pitch glides to the
  next note without a new stroke. The pitches the glide passes through are
  not notes, and the note it arrives on was not plucked.
* Fast repeated strokes (diri, and the jhala that ends a piece) on one note,
  which read as one long note rather than a blizzard of short ones.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import librosa
import numpy as np
from scipy.ndimage import median_filter
from scipy.signal import butter, sosfilt

SR = 22050
HOP = 256  # ~11.6 ms per frame: fast strokes and glides need the resolution
# From the pancham string (around A2, 110 Hz) to past ati-taar Sa (C#6, 1109 Hz).
FMIN = 105.0
FMAX = 1400.0
# Strokes per second above which repeated notes count as one fast-stroked note.
FAST_RATE = 7.0
# A pitch moving faster than this (semitones per second) is on its way somewhere.
GLIDE_RATE = 3.0
# A pitch a glide passes through is a note only if it is held this long (seconds).
MIN_HOLD = 0.06
# How much louder (dB) the sound gets when the string is struck again.
STROKE_RISE_DB = 1.5


@dataclass
class Note:
    start: float
    end: float
    midi: int
    confidence: float
    fast: bool = False  # fast repeated strokes on this note
    meend: bool = False  # reached by gliding from the note before, not plucked

    @property
    def duration(self) -> float:
        return self.end - self.start


@dataclass
class Transcription:
    notes: list[Note]
    tempo: float
    beat_times: list[float]
    tuning_cents: float
    duration: float
    warnings: list[str] = field(default_factory=list)


def _runs(values: np.ndarray):
    """Yield (start, end, value) for runs of equal values."""
    start = 0
    for i in range(1, len(values) + 1):
        if i == len(values) or values[i] != values[start]:
            yield start, i, values[start]
            start = i


def _smooth_pitch(midi: np.ndarray, min_frames: int) -> np.ndarray:
    """Absorb pitch blips shorter than min_frames into their neighbours."""
    out = midi.copy()
    runs = list(_runs(out))
    for k, (s, e, v) in enumerate(runs):
        if v < 0 or e - s >= min_frames:
            continue
        prev = runs[k - 1][2] if k > 0 else -1
        nxt = runs[k + 1][2] if k + 1 < len(runs) else -1
        # Octave jumps and one-frame wobbles: take the surrounding pitch.
        if prev >= 0 and (prev == nxt or abs(v - prev) in (1, 12)):
            out[s:e] = prev
        elif nxt >= 0 and abs(v - nxt) in (1, 12):
            out[s:e] = nxt
    # The plectrum's click can read as a pitch of its own for an instant
    # before the string speaks. Out of silence, that sliver is the next note.
    runs = list(_runs(out))
    for k, (s, e, v) in enumerate(runs[:-1]):
        after_silence = k == 0 or runs[k - 1][2] < 0
        if v >= 0 and after_silence and e - s < 2 * min_frames and runs[k + 1][2] >= 0:
            out[s:e] = runs[k + 1][2]
    return out


def _absorb_glides(
    midi: np.ndarray, steady: np.ndarray, plucks: np.ndarray, hold_frames: int
) -> np.ndarray:
    """Hand the pitches a meend passes through to the note it arrives on.

    A run is a passing pitch when it lies between its neighbours (lower on
    one side, higher on the other), was not plucked, and the pitch never
    settles on it.
    """
    out = midi.copy()
    changed = True
    while changed:
        changed = False
        runs = list(_runs(out))
        for k in range(1, len(runs) - 1):
            s, e, v = runs[k]
            prev, nxt = runs[k - 1][2], runs[k + 1][2]
            if min(v, prev, nxt) < 0 or not (prev < v < nxt or prev > v > nxt):
                continue
            if plucks[s:e].any() or steady[s:e].sum() >= hold_frames:
                continue
            out[s:e] = nxt
            changed = True
            break
    return out


def _struck(plucks: np.ndarray, frame: int) -> bool:
    """Whether a stroke goes with the pitch change at this frame.

    The new pitch shows up a few frames before the stroke's onset peaks, so
    look further ahead than behind.
    """
    return bool(plucks[max(0, frame - 3) : frame + 8].any())


def transcribe(
    y: np.ndarray,
    sr: int = SR,
    min_note: float = 0.05,
    silence_db: float = 35.0,
    progress=lambda stage, frac: None,
) -> Transcription:
    duration = len(y) / sr
    warnings: list[str] = []
    frame_time = HOP / sr

    progress("Separating strokes from the ringing strings", 0.05)
    # The tabla's bass drum sits below the melody and drowns out the pitch
    # tracker. Cut it first.
    highpass = butter(8, FMIN * 0.9, btype="highpass", fs=sr, output="sos")
    filtered = sosfilt(highpass, y).astype(np.float32)
    harmonic = librosa.effects.harmonic(filtered, margin=2.0)

    progress("Tracking pitch", 0.15)
    f0, voiced, voiced_prob = librosa.pyin(
        harmonic, fmin=FMIN, fmax=FMAX, sr=sr, frame_length=2048, hop_length=HOP
    )
    n = len(f0)
    rms = librosa.feature.rms(y=harmonic, frame_length=2048, hop_length=HOP)[0][:n]
    rms_db = librosa.amplitude_to_db(rms, ref=np.max)
    voiced = voiced & (rms_db > -silence_db) & np.isfinite(f0)

    progress("Finding notes", 0.7)
    midi_f = np.where(voiced, librosa.hz_to_midi(np.where(voiced, f0, 1.0)), np.nan)
    # Sa is wherever the player tuned it, rarely on a piano's pitch; re-centre
    # the semitone grid on the recording.
    if voiced.any():
        dev = midi_f[voiced] - np.round(midi_f[voiced])
        tuning = float(np.median(dev))
    else:
        tuning = 0.0
        warnings.append("No pitched notes were found in this audio.")
    midi = np.where(voiced, np.round(midi_f - tuning), -1).astype(int)
    min_frames = max(1, int(round(min_note * sr / HOP)))
    midi = _smooth_pitch(midi, min_frames)

    onset_env = librosa.onset.onset_strength(y=y, sr=sr, hop_length=HOP)[:n]
    onset_frames = librosa.onset.onset_detect(
        onset_envelope=onset_env, sr=sr, hop_length=HOP, backtrack=False
    )
    # A stroke puts energy into the string. A fast glide also sets off the
    # onset detector, but the sound only goes on dying away through it, so an
    # onset counts as a stroke only where the level comes up.
    level = librosa.amplitude_to_db(
        librosa.feature.rms(y=filtered, frame_length=1024, hop_length=HOP)[0][:n], ref=np.max
    )
    plucks = np.zeros(n, dtype=bool)
    for o in onset_frames[onset_frames < n]:
        before = level[max(0, o - 10) : max(1, o - 3)].min()
        plucks[o] = level[max(0, o - 3) : o + 9].max() - before >= STROKE_RISE_DB

    # A pitch that is holding still, as opposed to one that is gliding.
    filled = np.where(voiced, midi_f, 0.0)
    slope = np.abs(np.gradient(median_filter(filled, size=5))) / frame_time
    steady = voiced & (slope < GLIDE_RATE)
    midi = _absorb_glides(
        midi, steady, plucks, max(1, int(round(MIN_HOLD / frame_time)))
    )

    # An onset right next to a pitch change is the same stroke, not a new one.
    changes = {s for s, _, _ in _runs(midi)}
    onsets = {int(o) for o in onset_frames if all(abs(o - c) > 3 for c in changes)}

    notes: list[Note] = []
    previous_end, previous_midi = -1, -1
    for s, e, v in _runs(midi):
        if v < 0:
            continue
        # The pitch moved with no stroke: the left hand did it.
        meend = previous_end == s and previous_midi != v and not _struck(plucks, s)
        previous_end, previous_midi = e, v
        cuts = [s] + sorted(o for o in onsets if s < o < e) + [e]
        # Pitch often shows up a few frames before the stroke's onset; that
        # sliver belongs to the note that follows, not a note of its own.
        if len(cuts) > 2 and cuts[1] - cuts[0] < 2 * min_frames:
            del cuts[1]
        # Likewise the noise of a string being damped is not a last short note.
        if len(cuts) > 2 and cuts[-1] - cuts[-2] < 2 * min_frames:
            del cuts[-2]
        # Fast repeated strokes on one pitch are one long note. Onset detection
        # misses some strokes, so judge by density over the whole run rather
        # than note by note.
        run_time = (e - s) * frame_time
        if run_time >= 0.3 and len(cuts) - 1 >= 4 and (len(cuts) - 1) / run_time >= FAST_RATE:
            notes.append(
                Note(
                    start=s * frame_time,
                    end=e * frame_time,
                    midi=int(v),
                    confidence=float(np.nanmean(voiced_prob[s:e])),
                    fast=True,
                    meend=meend,
                )
            )
            continue
        for a, b in zip(cuts, cuts[1:]):
            if b - a < min_frames:
                continue
            notes.append(
                Note(
                    start=a * frame_time,
                    end=b * frame_time,
                    midi=int(v),
                    confidence=float(np.nanmean(voiced_prob[a:b])),
                    meend=meend and a == s,
                )
            )

    progress("Finding the beat", 0.85)
    tempo, beats = librosa.beat.beat_track(
        onset_envelope=onset_env, sr=sr, hop_length=HOP, units="time"
    )
    tempo = float(np.atleast_1d(tempo)[0])
    beat_times = [float(t) for t in beats]
    if len(beat_times) < 4:
        warnings.append("Couldn't find a steady beat; the taal grid is approximate.")

    return Transcription(
        notes=notes,
        tempo=tempo,
        beat_times=beat_times,
        tuning_cents=round(tuning * 100, 1),
        duration=duration,
        warnings=warnings,
    )


def load_audio(path: str, start: float = 0.0, end: float | None = None) -> np.ndarray:
    duration = None if end is None else max(0.0, end - start)
    y, _ = librosa.load(path, sr=SR, mono=True, offset=start, duration=duration)
    return y
