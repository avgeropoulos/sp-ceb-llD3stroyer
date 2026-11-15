"""Name pitches the way a sitar player reads them: as sargam, relative to Sa.

A sitar isn't read from fret numbers. Its frets are movable and are named
after the swara they sound, and the melody lives almost entirely on one
string, so the useful answer to "what do I play?" is the swara and its
octave. We also say which string a note sits on, because anything below
the open main string has to be played on the lower ones.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# Semitones above Sa. Lower case is komal (flat); "M" is tivra (sharp) Ma.
SWARAS = ["S", "r", "R", "g", "G", "m", "M", "P", "d", "D", "n", "N"]
SWARA_NAMES = [
    "Sa", "komal Re", "Re", "komal Ga", "Ga", "Ma",
    "tivra Ma", "Pa", "komal Dha", "Dha", "komal Ni", "Ni",
]

# Devanagari, as (consonant, vowel sign): octave dots attach to the consonant.
_DEVANAGARI = [
    ("स", "ा"), ("र", "े"), ("र", "े"), ("ग", ""), ("ग", ""), ("म", ""),
    ("म", ""), ("प", ""), ("ध", ""), ("ध", ""), ("न", "ि"), ("न", "ि"),
]
_KOMAL = {1, 3, 8, 10}
_TIVRA = 6
_ANUDATTA = "॒"  # line below: komal
_UDATTA = "॑"  # stroke above: tivra
_NUKTA = "़"  # dot below: mandra saptak
_ANUSVARA = "ं"  # dot above: taar saptak

SCRIPTS = ("latin", "devanagari")


def midi_name(midi: int) -> str:
    return f"{NOTE_NAMES[midi % 12]}{midi // 12 - 1}"


def sa_midi(pitch_class: int) -> int:
    """The madhya (middle) Sa for a sitar tuned to this pitch class.

    Sitars are tuned with Sa around C#4 or D4. For any other key, take the
    Sa between G3 and F#4, which keeps the open strings near where the
    instrument is built to sit.
    """
    pitch_class %= 12
    return (48 if pitch_class >= 7 else 60) + pitch_class


@dataclass(frozen=True)
class Swara:
    degree: int  # semitones above Sa, 0-11
    octave: int  # 0 = madhya saptak, -1 = mandra, +1 = taar

    @property
    def name(self) -> str:
        saptak = {0: "", -1: " (mandra)", 1: " (taar)"}.get(
            self.octave, " (ati-mandra)" if self.octave < 0 else " (ati-taar)"
        )
        return SWARA_NAMES[self.degree] + saptak

    def text(self, script: str = "latin") -> str:
        if script == "devanagari":
            consonant, vowel = _DEVANAGARI[self.degree]
            out = consonant + (_NUKTA if self.octave < 0 else "") + vowel
            if self.degree in _KOMAL:
                out += _ANUDATTA
            elif self.degree == _TIVRA:
                out += _UDATTA
            return out + (_ANUSVARA if self.octave > 0 else "")
        mark = "'" * self.octave if self.octave > 0 else "," * -self.octave
        return SWARAS[self.degree] + mark


def swara(midi: int, sa: int) -> Swara:
    return Swara((midi - sa) % 12, (midi - sa) // 12)


def width(text: str) -> int:
    """Columns a string takes in a monospace font, ignoring combining marks."""
    return sum(1 for ch in text if unicodedata.category(ch) != "Mn")


# The four playing strings of a kharaj-pancham sitar, as semitones from madhya Sa.
@dataclass(frozen=True)
class String:
    key: str
    name: str
    open: int


STRINGS = (
    String("baj", "baj tar (main string, open Ma)", -7),
    String("jod", "jod tar (open Sa)", -12),
    String("pancham", "pancham tar (open Pa)", -17),
    String("kharaj", "kharaj tar (open low Sa)", -24),
)
LOWEST = STRINGS[-1].open
# The top fret is around taar Ga or Ma; pulling the string (meend) reaches a
# fourth or so beyond it.
HIGHEST = 24


def string_for(midi: int, sa: int) -> String:
    """The string a note is played on: the main string whenever it can be."""
    rel = midi - sa
    for string in STRINGS:
        if rel >= string.open:
            return string
    raise ValueError(f"{midi_name(midi)} is below the sitar's range")


def fit_to_range(midi: int, sa: int) -> tuple[int, bool]:
    """Fold a pitch into the sitar's range by octaves. Returns (midi, moved)."""
    moved = False
    while midi < sa + LOWEST:
        midi += 12
        moved = True
    while midi > sa + HIGHEST:
        midi -= 12
        moved = True
    return midi, moved
