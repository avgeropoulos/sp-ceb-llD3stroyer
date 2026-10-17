"""Note events and their file formats (MIDI and CSV).

A phrase is a list of :class:`NoteEvent` played on one instrument. In MIDI files written here every
course is its own track named ``<instrument>/course<k>``, and a note's MIDI pitch is the pitch of the
course's thick string at that fret (the renderer and the targets add the octave string themselves).
MIDI files from elsewhere (one track, no course names) are accepted too: each note is assigned to a
course with the app's position rule.

CSV (for the owner's click-aligned recordings), one row per planned note, header required::

    onset_s,offset_s,course,fret[,velocity]

``course`` is 0-based from the bass course; ``midi`` is derived from instrument + course + fret.
"""
import csv
import os
from dataclasses import dataclass, replace
from typing import List, Optional, Tuple

from .instruments import Instrument, get_instrument


@dataclass(frozen=True)
class NoteEvent:
    start: float  # seconds
    end: float  # seconds (the string is damped here)
    midi: int  # fretted pitch of the course's main (thick) string
    course: int
    velocity: float = 0.85  # 0..1


def fret_of(instrument: Instrument, note: NoteEvent) -> int:
    return note.midi - instrument.courses[note.course].open_midi


def validate(instrument: Instrument, notes: List[NoteEvent]) -> None:
    for n in notes:
        if not 0 <= n.course < len(instrument.courses):
            raise ValueError(f"course {n.course} out of range for {instrument.name}: {n}")
        if not 0 <= fret_of(instrument, n) <= instrument.frets:
            raise ValueError(f"fret out of range on {instrument.name}: {n}")
        if n.end <= n.start:
            raise ValueError(f"note ends before it starts: {n}")


def shift_time(notes: List[NoteEvent], scale: float = 1.0, offset: float = 0.0) -> List[NoteEvent]:
    """Times multiplied by ``scale`` then shifted by ``offset`` (used by the varispeed detune)."""
    return [replace(n, start=n.start * scale + offset, end=n.end * scale + offset) for n in notes]


# ----------------------------------------------------------------------------------------- MIDI
def write_midi(path: str, instrument: Instrument, notes: List[NoteEvent], program: int = 25) -> None:
    import pretty_midi

    pm = pretty_midi.PrettyMIDI(initial_tempo=120.0)
    tracks = []
    for k in range(len(instrument.courses)):
        tr = pretty_midi.Instrument(program=program, name=f"{instrument.name}/course{k}")
        tracks.append(tr)
        pm.instruments.append(tr)
    for n in sorted(notes, key=lambda n: (n.start, n.course)):
        vel = int(round(max(1, min(127, n.velocity * 127))))
        tracks[n.course].notes.append(pretty_midi.Note(velocity=vel, pitch=n.midi, start=n.start, end=n.end))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    pm.write(path)


def read_midi(path: str, instrument: Optional[Instrument] = None) -> Tuple[Instrument, List[NoteEvent]]:
    """Reads a MIDI file written by :func:`write_midi` (or any MIDI file, given ``instrument``)."""
    import pretty_midi

    pm = pretty_midi.PrettyMIDI(path)
    notes: List[NoteEvent] = []
    for tr in pm.instruments:
        if tr.is_drum:
            continue
        course = None
        if "/course" in tr.name:
            name, k = tr.name.rsplit("/course", 1)
            if instrument is None:
                instrument = get_instrument(name)
            course = int(k)
        if instrument is None:
            raise ValueError(f"{path}: no '<instrument>/course<k>' track names; pass the instrument")
        for n in tr.notes:
            c = course
            if c is None:
                pos = instrument.position(n.pitch)
                if pos is None:
                    continue  # out of the instrument's range
                c = pos[0]
            notes.append(NoteEvent(float(n.start), float(n.end), int(n.pitch), c, n.velocity / 127.0))
    if instrument is None:
        raise ValueError(f"{path}: empty MIDI file and no instrument given")
    notes.sort(key=lambda n: (n.start, n.course))
    return instrument, notes


# ------------------------------------------------------------------------------------------ CSV
def write_csv(path: str, instrument: Instrument, notes: List[NoteEvent]) -> None:
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["onset_s", "offset_s", "course", "fret", "velocity"])
        for n in notes:
            w.writerow([f"{n.start:.4f}", f"{n.end:.4f}", n.course, fret_of(instrument, n), f"{n.velocity:.3f}"])


def read_csv(path: str, instrument: Instrument, time_offset: float = 0.0) -> List[NoteEvent]:
    """``time_offset`` is added to every time, e.g. the measured latency of the click track."""
    notes = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            course, fret = int(row["course"]), int(row["fret"])
            notes.append(
                NoteEvent(
                    float(row["onset_s"]) + time_offset,
                    float(row["offset_s"]) + time_offset,
                    instrument.midi_at(course, fret),
                    course,
                    float(row.get("velocity") or 0.85),
                )
            )
    validate(instrument, notes)
    return notes
