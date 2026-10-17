"""Bouzouki tunings and note names."""

from __future__ import annotations

import re
from dataclasses import dataclass

NOTE_NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]

_NOTE_RE = re.compile(r"^([A-Ga-g])([#b]?)(-?\d)$")
_LETTER_TO_PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def note_name(midi: int) -> str:
    return f"{NOTE_NAMES[midi % 12]}{midi // 12 - 1}"


def parse_note(text: str) -> int:
    """Turn a note like "C3", "F#3" or "Bb2" into a MIDI number."""
    match = _NOTE_RE.match(text.strip())
    if not match:
        raise ValueError(f"not a note name: {text!r} (expected something like C3 or F#3)")
    letter, accidental, octave = match.groups()
    pc = _LETTER_TO_PC[letter.upper()] + {"#": 1, "b": -1, "": 0}[accidental]
    return (int(octave) + 1) * 12 + pc


@dataclass(frozen=True)
class Tuning:
    name: str
    strings: tuple[int, ...]  # MIDI pitch of each course, lowest course first
    frets: int = 26

    @property
    def lowest(self) -> int:
        return self.strings[0]

    @property
    def highest(self) -> int:
        return self.strings[-1] + self.frets

    @property
    def labels(self) -> list[str]:
        """Course labels for the tab, lowest course first."""
        return [NOTE_NAMES[m % 12] for m in self.strings]

    def describe(self) -> str:
        return f"{self.name}: " + " ".join(note_name(m) for m in self.strings)


TUNINGS = {
    # Four-course (tetrachordo) bouzouki, the modern standard. The C and F courses
    # have octave pairs; tab follows the lower string of each course.
    "tetrachordo": Tuning("Tetrachordo", (48, 53, 57, 62), 26),
    # Three-course (trichordo) bouzouki of classic rebetiko.
    "trichordo": Tuning("Trichordo", (50, 57, 62), 26),
}


def get_tuning(spec: str) -> Tuning:
    """Look up a named tuning, or build one from notes like "C3,F3,A3,D4"."""
    key = spec.strip().lower()
    if key in TUNINGS:
        return TUNINGS[key]
    if key in ("4", "cfad"):
        return TUNINGS["tetrachordo"]
    if key in ("3", "dad"):
        return TUNINGS["trichordo"]
    notes = [n for n in re.split(r"[,\s]+", spec.strip()) if n]
    if len(notes) < 2:
        raise ValueError(
            f"unknown tuning {spec!r}: use tetrachordo, trichordo or a note list like C3,F3,A3,D4"
        )
    strings = tuple(parse_note(n) for n in notes)
    if list(strings) != sorted(strings):
        raise ValueError("list the courses from lowest to highest")
    return Tuning("Custom", strings, 26)
