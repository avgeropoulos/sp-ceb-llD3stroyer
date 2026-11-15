"""Lay notes out on a taal grid and write them as sargam (plus MIDI export).

The notation follows the Bhatkhande layout: one cell per matra, vibhags
divided by bars and marked with their clap. Notes that share a matra are
written together in its cell, and "-" holds the note before.
"""

from __future__ import annotations

import io
from dataclasses import dataclass, field

import mido
import numpy as np

from .sargam import SCRIPTS, Swara, width
from .taal import Taal


@dataclass
class TabNote:
    start: float
    end: float
    midi: int
    swara: Swara
    string: str
    meend: bool = False
    fast: bool = False

    def text(self, script: str = "latin") -> str:
        return ("~" if self.meend else "") + self.swara.text(script) + ("*" if self.fast else "")


@dataclass
class System:
    """One line of notation: a cycle or two, with the time it starts in the recording."""

    start: float
    end: float
    lines: list[str]
    lines_devanagari: list[str]
    # [{"mark": "X", "sam": True, "matras": [{"t": 12.5, "parts": [note or None]}]}]
    vibhags: list[dict] = field(default_factory=list)


def beat_grid(
    beat_times: list[float], tempo: float, first: float, last: float
) -> np.ndarray:
    """Beat times covering [first, last], extended at both ends if needed."""
    beats = np.asarray(beat_times, dtype=float)
    if len(beats) >= 2:
        period = float(np.median(np.diff(beats)))
    else:
        period = 60.0 / (tempo if tempo > 0 else 120.0)
        beats = np.array([first])
    before = np.arange(beats[0] - period, first - period, -period)[::-1]
    after = np.arange(beats[-1] + period, last + 2 * period, period)
    return np.concatenate([before, beats, after])


def _slot_times(beats: np.ndarray, subdivisions: int) -> np.ndarray:
    slots = [
        a + (b - a) * k / subdivisions
        for a, b in zip(beats, beats[1:])
        for k in range(subdivisions)
    ]
    return np.asarray(slots + [beats[-1]])


def layout(
    notes: list[TabNote],
    taal: Taal,
    beat_times: list[float],
    tempo: float,
    subdivisions: int = 4,
) -> list[System]:
    if not notes:
        return []
    beats = beat_grid(beat_times, tempo, notes[0].start, notes[-1].end)
    slot_times = _slot_times(beats, subdivisions)
    per_cycle = taal.matras * subdivisions

    # Snap each note to its nearest grid slot; if two land together, nudge the later one.
    placed: dict[int, TabNote] = {}
    for note in notes:
        slot = int(np.argmin(np.abs(slot_times - note.start)))
        while slot in placed and slot + 1 < len(slot_times):
            slot += 1
        placed.setdefault(slot, note)

    # The beat tracker can't hear where sam is, so count from the matra of the
    # first note.
    origin = min(placed) // subdivisions * subdivisions
    cycles: dict[int, dict[int, TabNote]] = {}
    for slot, note in placed.items():
        cycles.setdefault((slot - origin) // per_cycle, {})[(slot - origin) % per_cycle] = note

    # Keep a lone empty cycle as a rest, but skip long gaps (vocals, other solos).
    kept: list[list[int]] = [[]]
    for cycle in range(min(cycles), max(cycles) + 1):
        if cycle in cycles or (cycle - 1 in cycles and cycle + 1 in cycles):
            if kept[-1] and kept[-1][-1] != cycle - 1:
                kept.append([])
            kept[-1].append(cycle)

    def time_of(slot: int) -> float:
        return float(slot_times[min(origin + slot, len(slot_times) - 1)])

    per_line = max(1, 16 // taal.matras)
    systems: list[System] = []
    for section in kept:
        for i in range(0, len(section), per_line):
            chunk = section[i : i + per_line]
            systems.append(_draw(chunk, cycles, taal, time_of, subdivisions))
    return systems


def _parts(slots: list[TabNote | None]) -> list[TabNote | None]:
    """The notes of one matra, in as few equal parts as show them all."""
    step = len(slots)
    for k, note in enumerate(slots):
        if note is not None and k:
            step = int(np.gcd(step, k))
    return slots[::step]


def _draw(chunk, cycles, taal: Taal, time_of, subdivisions: int) -> System:
    per_cycle = taal.matras * subdivisions
    vibhags: list[dict] = []
    for cycle in chunk:
        matra = 0
        for v, (size, mark) in enumerate(zip(taal.vibhags, taal.marks)):
            matras = []
            for _ in range(size):
                first = matra * subdivisions
                slots = [cycles.get(cycle, {}).get(first + k) for k in range(subdivisions)]
                matras.append({"t": max(0.0, time_of(cycle * per_cycle + first)),
                               "parts": _parts(slots)})
                matra += 1
            vibhags.append({"mark": mark, "sam": v == 0, "matras": matras})

    system = System(
        start=max(0.0, time_of(chunk[0] * per_cycle)),
        end=time_of((chunk[-1] + 1) * per_cycle),
        lines=_rows(vibhags, "latin"),
        lines_devanagari=_rows(vibhags, "devanagari"),
    )
    # The web page draws the notation itself, from the same cells.
    system.vibhags = [
        {**v, "matras": [{"t": round(m["t"], 3), "parts": [_cell(p) for p in m["parts"]]}
                         for m in v["matras"]]}
        for v in vibhags
    ]
    return system


def _cell(note: TabNote | None) -> dict | None:
    if note is None:
        return None
    return {"degree": note.swara.degree, "octave": note.swara.octave, "name": note.swara.name,
            "string": note.string, "meend": note.meend, "fast": note.fast}


def _rows(vibhags: list[dict], script: str) -> list[str]:
    cells = [
        ["".join(p.text(script) if p else "-" for p in m["parts"]) for m in v["matras"]]
        for v in vibhags
    ]
    cell = max(3, 1 + max(width(text) for group in cells for text in group))
    marks, notes = "", ""
    for k, (vibhag, group) in enumerate(zip(vibhags, cells)):
        body = "".join(text + " " * (cell - width(text)) for text in group)
        last = k + 1 == len(vibhags) or vibhags[k + 1]["sam"]
        bar = "|| " if last else "| "
        marks += vibhag["mark"].ljust(len(group) * cell + len(bar))
        notes += body + bar
    return [marks.rstrip(), notes.rstrip()]


def format_time(seconds: float) -> str:
    seconds = max(0, int(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


def to_text(systems: list[System], header: list[str], script: str = "latin") -> str:
    if script not in SCRIPTS:
        raise ValueError(f"Unknown script {script!r}")
    out = list(header) + [""]
    for system in systems:
        out.append(f"[{format_time(system.start)}]")
        out.extend(system.lines if script == "latin" else system.lines_devanagari)
        out.append("")
    return "\n".join(out)


def to_midi(notes: list[TabNote], tempo: float) -> bytes:
    """A MIDI file of the transcription, handy for checking it by ear."""
    tempo = tempo if tempo > 0 else 120.0
    tpb = 480
    mid = mido.MidiFile(ticks_per_beat=tpb)
    track = mido.MidiTrack()
    mid.tracks.append(track)
    track.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(tempo)))
    track.append(mido.MetaMessage("track_name", name="Sitar"))
    # General MIDI instrument 105 is a sitar.
    track.append(mido.Message("program_change", program=104, time=0))

    def ticks(t: float) -> int:
        return int(round(t * tempo / 60.0 * tpb))

    events = []
    for note in notes:
        events.append((ticks(note.start), 1, note.midi))
        events.append((ticks(note.end), 0, note.midi))
    events.sort()
    now = 0
    for tick, on, midi in events:
        kind = "note_on" if on else "note_off"
        track.append(mido.Message(kind, note=midi, velocity=90 if on else 0, time=tick - now))
        now = tick
    buf = io.BytesIO()
    mid.save(file=buf)
    return buf.getvalue()
