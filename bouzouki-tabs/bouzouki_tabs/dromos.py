"""Guessing the dromos (the Greek modal scale) of a passage.

Bouzouki tunes are built on dromoi such as Hijaz or Ousak rather than plain
major and minor. We compare how long each pitch class sounds with the scale
of every dromos on every tonic. This is a best guess: dromoi that share notes
can only be told apart by melodic behaviour, which we only roughly model by
favouring the tonic and the final note.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .instrument import NOTE_NAMES
from .notes import Note

# Semitones above the tonic, in 12-tone equal temperament as played on bouzouki.
DROMOI: dict[str, tuple[int, ...]] = {
    "Matzore (major)": (0, 2, 4, 5, 7, 9, 11),
    "Rast": (0, 2, 4, 5, 7, 9, 10),
    "Minore / Nihavent": (0, 2, 3, 5, 7, 8, 11),
    "Ousak": (0, 1, 3, 5, 7, 8, 10),
    "Hijaz": (0, 1, 4, 5, 7, 8, 10),
    "Hijazkiar": (0, 1, 4, 5, 7, 8, 11),
    "Sabah": (0, 2, 3, 4, 7, 8, 10),
    "Kartzigar": (0, 2, 3, 5, 6, 9, 10),
    "Pireotikos": (0, 1, 4, 6, 7, 8, 11),
}


@dataclass
class DromosMatch:
    name: str
    tonic: str
    score: float
    notes: list[str]

    def __str__(self) -> str:
        return f"{self.name} on {self.tonic}"


def pitch_class_profile(notes: list[Note]) -> np.ndarray:
    profile = np.zeros(12)
    for note in notes:
        profile[note.midi % 12] += min(note.duration, 1.0)
    total = profile.sum()
    return profile / total if total else profile


def detect_dromos(notes: list[Note], top: int = 3) -> list[DromosMatch]:
    if len(notes) < 4:
        return []
    profile = pitch_class_profile(notes)
    final = notes[-1].midi % 12
    lowest = min(notes, key=lambda n: n.midi).midi % 12
    matches = []
    for name, steps in DROMOI.items():
        for tonic in range(12):
            in_scale = np.zeros(12, dtype=bool)
            in_scale[[(tonic + s) % 12 for s in steps]] = True
            score = profile[in_scale].sum() - 2.0 * profile[~in_scale].sum()
            score += 0.6 * profile[tonic] + 0.25 * profile[(tonic + 7) % 12]
            score += 0.08 * (final == tonic) + 0.04 * (lowest == tonic)
            scale = [NOTE_NAMES[(tonic + s) % 12] for s in steps]
            matches.append(DromosMatch(name, NOTE_NAMES[tonic], float(score), scale))
    matches.sort(key=lambda m: m.score, reverse=True)
    return matches[:top]
