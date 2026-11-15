"""Guess Sa and the scale family (thaat) from the notes that were played.

A raga is more than its scale: it has its own phrases, its own notes to
rest on, its own way up and down. None of that is looked at here. We match
the notes against the ten thaats and a few well-known five-note ragas, and
name some ragas that use the winning scale. It is a starting point for a
player, not an identification.
"""

from __future__ import annotations

import numpy as np

from .sargam import NOTE_NAMES

# name -> (semitones above Sa, ragas that use this scale)
SCALES: dict[str, tuple[tuple[int, ...], tuple[str, ...]]] = {
    "Bilawal thaat": ((0, 2, 4, 5, 7, 9, 11), ("Alhaiya Bilawal", "Bihag", "Deshkar")),
    "Kalyan thaat": ((0, 2, 4, 6, 7, 9, 11), ("Yaman", "Shuddha Kalyan", "Hindol")),
    "Khamaj thaat": ((0, 2, 4, 5, 7, 9, 10), ("Khamaj", "Desh", "Jhinjhoti", "Tilak Kamod")),
    "Kafi thaat": ((0, 2, 3, 5, 7, 9, 10), ("Kafi", "Bageshree", "Bhimpalasi", "Pilu")),
    "Asavari thaat": ((0, 2, 3, 5, 7, 8, 10), ("Asavari", "Jaunpuri", "Darbari Kanada")),
    "Bhairavi thaat": ((0, 1, 3, 5, 7, 8, 10), ("Bhairavi", "Bilaskhani Todi")),
    "Bhairav thaat": ((0, 1, 4, 5, 7, 8, 11), ("Bhairav", "Jogiya", "Kalingda")),
    "Poorvi thaat": ((0, 1, 4, 6, 7, 8, 11), ("Poorvi", "Puriya Dhanashree", "Shree")),
    "Marwa thaat": ((0, 1, 4, 6, 7, 9, 11), ("Marwa", "Puriya", "Sohini")),
    "Todi thaat": ((0, 1, 3, 6, 7, 8, 11), ("Miyan ki Todi", "Multani", "Gujari Todi")),
    "Raga Bhupali": ((0, 2, 4, 7, 9), ("Bhupali", "Deshkar")),
    "Raga Malkauns": ((0, 3, 5, 8, 10), ("Malkauns",)),
    "Raga Durga": ((0, 2, 5, 7, 9), ("Durga",)),
    "Raga Hamsadhwani": ((0, 2, 4, 7, 11), ("Hamsadhwani",)),
    "Raga Shivaranjani": ((0, 2, 3, 7, 9), ("Shivaranjani",)),
    "Raga Megh": ((0, 2, 5, 7, 10), ("Megh", "Madhmad Sarang")),
}


def _template(degrees: tuple[int, ...]) -> np.ndarray:
    t = np.zeros(12)
    t[list(degrees)] = 1.0
    t[0] = 2.0  # Sa is where phrases settle, and what the drone strings ring
    if 7 in degrees:
        t[7] = 1.3
    return t


def detect_raga(
    midis: list[int], durations: list[float], sa: int | None = None, top: int = 3
) -> list[dict]:
    """Rank (Sa, scale) pairs by how well they fit the played notes.

    Pass sa (a pitch class, 0 = C) when the player knows the tuning; then
    only the scale is guessed.
    """
    if not midis:
        return []
    hist = np.zeros(12)
    for m, d in zip(midis, durations):
        hist[m % 12] += d
    played = hist.copy()
    # Phrases come home to Sa, so weight the final note.
    hist[midis[-1] % 12] += 0.15 * hist.sum()
    if hist.std() == 0:
        return []

    tonics = range(12) if sa is None else [sa % 12]
    scores = []
    for name, (degrees, ragas) in SCALES.items():
        template = _template(degrees)
        for tonic in tonics:
            fit = np.roll(template, tonic)
            # A scale has to account for the notes that were played: time
            # spent outside it counts against it. Without this a five-note
            # raga wins whenever a seven-note one leans on the same five.
            outside = played[fit == 0].sum() / played.sum()
            r = np.corrcoef(hist, fit)[0, 1] - outside
            scores.append((r, tonic, name, ragas))
    scores.sort(key=lambda s: (-s[0], s[1], s[2]))
    return [
        {
            "sa": NOTE_NAMES[tonic],
            "sa_pitch_class": tonic,
            "scale": name,
            "ragas": list(ragas),
            "score": round(float(r), 3),
        }
        for r, tonic, name, ragas in scores[:top]
    ]
