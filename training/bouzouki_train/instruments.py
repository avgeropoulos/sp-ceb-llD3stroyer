"""The two Greek bouzoukia and the dromoi, mirroring bouzouki-core (Instrument.kt, Dromos.kt).

A course is a pair of strings plucked together. On an octave-pair course the thin string sounds an
octave above the thick one; on a unison course both strings sound the same pitch (slightly detuned).
"""
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple


@dataclass(frozen=True)
class Course:
    open_midi: int  # pitch of the thick (or only distinct) string, open
    octave_pair: bool

    def sounding_offsets(self) -> Tuple[int, ...]:
        """Semitone offsets of the pitches the course sounds, relative to its fretted note."""
        return (0, 12) if self.octave_pair else (0,)


@dataclass(frozen=True)
class Instrument:
    name: str
    courses: Tuple[Course, ...]  # lowest (bass) to highest
    frets: int

    def midi_at(self, course: int, fret: int) -> int:
        return self.courses[course].open_midi + fret

    def position(self, midi: int, hand_fret: int = 0) -> Optional[Tuple[int, int]]:
        """(course, fret) for ``midi`` near ``hand_fret``, preferring frets close to the nut
        (same rule as Instrument.position in the app). None if out of range."""
        options = [(c, midi - co.open_midi) for c, co in enumerate(self.courses)]
        options = [(c, f) for c, f in options if 0 <= f <= self.frets]
        if not options:
            return None
        return min(options, key=lambda cf: abs(cf[1] - hand_fret) * 10 + cf[1])

    @property
    def lowest(self) -> int:
        return min(c.open_midi for c in self.courses)

    @property
    def highest(self) -> int:
        return max(c.open_midi for c in self.courses) + self.frets


# Tetrachordo C3/C4, F3/F4, A3/A3, D4/D4 ; trichordo D3/D4, A3/A3, D4/D4.
TETRACHORDO = Instrument(
    "tetrachordo",
    (Course(48, True), Course(53, True), Course(57, False), Course(62, False)),
    frets=27,
)
TRICHORDO = Instrument(
    "trichordo",
    (Course(50, True), Course(57, False), Course(62, False)),
    frets=26,
)
INSTRUMENTS: Dict[str, Instrument] = {i.name: i for i in (TETRACHORDO, TRICHORDO)}

#: Course names used in file names of the owner's recordings (lowest course first): the open
#: pitch of the course's thick string, with octave number, so names never differ only by case
#: (macOS and Windows file systems ignore case).
COURSE_NAMES: Dict[str, List[str]] = {
    "tetrachordo": ["C3", "F3", "A3", "D4"],
    "trichordo": ["D3", "A3", "D4"],
}

#: The seven degrees of each dromos above its root (Dromos.kt). Kiourdi has the same notes as Ousak.
DROMOI: Dict[str, Tuple[int, ...]] = {
    "matzore": (0, 2, 4, 5, 7, 9, 11),
    "minore": (0, 2, 3, 5, 7, 8, 10),
    "nihavent": (0, 2, 3, 5, 7, 8, 11),
    "hitzaz": (0, 1, 4, 5, 7, 8, 10),
    "hitzazkiar": (0, 1, 4, 5, 7, 8, 11),
    "ousak": (0, 1, 3, 5, 7, 8, 10),
    "kiourdi": (0, 1, 3, 5, 7, 8, 10),
    "rast": (0, 2, 4, 5, 7, 9, 10),
    "sabah": (0, 2, 3, 4, 7, 8, 10),
    "houzam": (0, 3, 4, 5, 7, 9, 11),
    "segiah": (0, 3, 4, 5, 7, 8, 11),
    "pireotikos": (0, 1, 4, 6, 7, 8, 11),
    "nikriz": (0, 2, 3, 6, 7, 9, 10),
    "tsinganikos": (0, 2, 3, 6, 7, 8, 11),
    "kartsigar": (0, 2, 3, 5, 6, 9, 10),
}


def get_instrument(name: str) -> Instrument:
    try:
        return INSTRUMENTS[name.lower()]
    except KeyError:
        raise ValueError(f"unknown instrument {name!r}; expected one of {sorted(INSTRUMENTS)}") from None
