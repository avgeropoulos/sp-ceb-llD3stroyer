"""Choosing a course and fret for every note.

The same pitch can usually be played in two or three places. We pick the
sequence of positions with the least total effort (dynamic programming over
all choices), which favours how bouzouki players actually move: runs along
one course, small hand shifts, and open strings where they fit.
"""

from __future__ import annotations

from dataclasses import dataclass

from .instrument import Tuning
from .notes import Note

HAND_SPAN = 4  # frets the hand covers without shifting


@dataclass(frozen=True)
class Position:
    string: int  # course index, 0 = lowest course
    fret: int
    transposed: int = 0  # octaves moved to fit the instrument (0 if played as heard)


def fit_to_range(midi: int, tuning: Tuning) -> tuple[int, int]:
    """Move a pitch by octaves until it is playable. Returns (pitch, octaves moved)."""
    shift = 0
    while midi + 12 * shift < tuning.lowest:
        shift += 1
    while midi + 12 * shift > tuning.highest:
        shift -= 1
    return midi + 12 * shift, shift


def candidates(midi: int, tuning: Tuning) -> list[tuple[int, int]]:
    return [(s, midi - open_) for s, open_ in enumerate(tuning.strings) if 0 <= midi - open_ <= tuning.frets]


def _position_cost(fret: int) -> float:
    # Low positions are easiest; above the 12th fret things get more awkward.
    return 0.04 * fret + (0.08 * (fret - 12) if fret > 12 else 0.0)


def _move_cost(prev: tuple[int, int], cur: tuple[int, int], gap: float) -> float:
    (s1, f1), (s2, f2) = prev, cur
    cost = 0.35 * abs(s2 - s1)
    if f1 > 0 and f2 > 0:
        shift = abs(f2 - f1)
        cost += 0.1 * shift if shift <= HAND_SPAN else 0.4 * shift
    # A longer gap gives the hand time to move.
    return cost / (1.0 + 2.0 * max(gap, 0.0))


def assign_positions(notes: list[Note], tuning: Tuning) -> list[Position]:
    if not notes:
        return []
    options: list[list[tuple[int, int]]] = []
    shifts: list[int] = []
    for note in notes:
        midi, shift = fit_to_range(note.midi, tuning)
        options.append(candidates(midi, tuning))
        shifts.append(shift)

    cost = [_position_cost(f) for _, f in options[0]]
    back: list[list[int]] = [[0] * len(options[0])]
    for i in range(1, len(notes)):
        gap = notes[i].start - notes[i - 1].end
        new_cost, new_back = [], []
        for cur in options[i]:
            totals = [c + _move_cost(prev, cur, gap) for c, prev in zip(cost, options[i - 1])]
            best = min(range(len(totals)), key=totals.__getitem__)
            new_cost.append(totals[best] + _position_cost(cur[1]))
            new_back.append(best)
        cost, back = new_cost, back + [new_back]

    choice = min(range(len(cost)), key=cost.__getitem__)
    result: list[Position] = []
    for i in range(len(notes) - 1, -1, -1):
        s, f = options[i][choice]
        result.append(Position(s, f, shifts[i]))
        choice = back[i][choice]
    return result[::-1]
