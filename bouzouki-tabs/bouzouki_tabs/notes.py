"""Turning the salience map into a list of notes.

A hidden Markov model with one state per semitone plus a "silence" state picks
the most likely melody line; changing pitch or starting/stopping costs a
little, which keeps the line from flickering. The result is then cut at
picking attacks so repeated notes (bouzouki tremolo) come out as separate
notes.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .analysis import Analysis, pick_onsets

JUMP_PENALTY = 2.5
SILENCE_PENALTY = 1.5
VOICING_THRESHOLD = 0.3


@dataclass
class Note:
    start: float
    end: float
    midi: int
    strength: float = 1.0

    @property
    def duration(self) -> float:
        return self.end - self.start


def track_melody(analysis: Analysis, voicing: float = VOICING_THRESHOLD) -> np.ndarray:
    """Return the MIDI pitch for every frame, or -1 where nothing is playing."""
    sal = analysis.salience
    n_frames, n_pitches = sal.shape
    if n_frames == 0:
        return np.zeros(0, dtype=int)
    # State 0 is silence, states 1..n are the candidate pitches.
    emission = np.empty((n_frames, n_pitches + 1), dtype=np.float32)
    emission[:, 0] = np.log(voicing)
    emission[:, 1:] = np.log(np.clip(sal, 1e-3, None))

    n_states = n_pitches + 1
    transition = np.full((n_states, n_states), -JUMP_PENALTY, dtype=np.float32)
    transition[0, :] = -SILENCE_PENALTY
    transition[:, 0] = -SILENCE_PENALTY
    np.fill_diagonal(transition, 0.0)

    backpointers = np.empty((n_frames, n_states), dtype=np.int16)
    score = emission[0].copy()
    for t in range(1, n_frames):
        candidates = score[:, None] + transition
        backpointers[t] = np.argmax(candidates, axis=0)
        score = candidates[backpointers[t], np.arange(n_states)] + emission[t]

    path = np.empty(n_frames, dtype=int)
    path[-1] = int(np.argmax(score))
    for t in range(n_frames - 1, 0, -1):
        path[t - 1] = backpointers[t, path[t]]
    return np.where(path == 0, -1, analysis.midi[np.maximum(path - 1, 0)])


def segment_notes(
    analysis: Analysis,
    pitches: np.ndarray,
    min_duration: float = 0.06,
    snap_window: tuple[float, float] = (0.12, 0.06),
) -> list[Note]:
    """Group the frame-wise pitch track into notes, aligned to picking attacks."""
    rate = analysis.frame_rate
    onsets = pick_onsets(analysis.onset_env, rate)
    max_sal = analysis.salience.max(axis=1) if analysis.salience.size else np.zeros(0)

    runs: list[Note] = []
    start = 0
    for t in range(1, len(pitches) + 1):
        if t == len(pitches) or pitches[t] != pitches[start]:
            if pitches[start] >= 0:
                strength = float(np.mean(max_sal[start:t]))
                runs.append(Note(start / rate, t / rate, int(pitches[start]), strength))
            start = t

    notes: list[Note] = []
    for run in runs:
        # The long analysis window blurs where a note starts; trust the attack instead.
        near = onsets[(onsets >= run.start - snap_window[0]) & (onsets <= run.start + snap_window[1])]
        if near.size:
            run.start = float(near[np.argmin(np.abs(near - run.start))])
        # Re-attacks on the same pitch become separate notes.
        inside = onsets[(onsets > run.start + min_duration) & (onsets < run.end - min_duration)]
        bounds = [run.start, *inside.tolist(), run.end]
        for a, b in zip(bounds[:-1], bounds[1:]):
            notes.append(Note(a, b, run.midi, run.strength))

    notes.sort(key=lambda n: n.start)
    cleaned: list[Note] = []
    for note in notes:
        if cleaned and note.start < cleaned[-1].end:
            cleaned[-1].end = note.start
            if cleaned[-1].duration < min_duration:
                cleaned.pop()
        if note.duration >= min_duration:
            cleaned.append(note)
    return cleaned
