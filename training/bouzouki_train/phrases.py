"""Synthetic MIDI phrases for bouzouki, so no third-party note data is needed.

Kinds:
    scale      melodic lines in a random dromos (scale steps, leaps, ornaments), rhythmic values
    tremolo    held melody notes played as fast repeated picks (10-18 picks/s), sometimes doubled
    chromatic  chromatic runs along one course or across courses, up or down
    random     random notes, chords of 2-3 courses, rests and ringing notes
    everyfret  every fret of one course, one pick each (systematic coverage of the fretboard)

Usage::

    python -m bouzouki_train.phrases --out data/synth/midi --n 300 --seed 0
"""
import argparse
import os
from typing import Callable, Dict, List

import numpy as np

from .instruments import DROMOI, INSTRUMENTS, Instrument
from .notes import NoteEvent, validate, write_midi

LEAD_IN = (0.1, 0.5)  # seconds of silence before the first note
TAIL = 1.0  # seconds the last notes may ring


def _finish(notes: List[NoteEvent], rng: np.random.Generator) -> List[NoteEvent]:
    """Ends every note no later than the next pick on the same course (a string cannot sound two
    notes) and drops exact duplicates (same course, same start)."""
    out: List[NoteEvent] = []
    by_course: Dict[int, List[NoteEvent]] = {}
    for n in sorted(notes, key=lambda n: (n.start, n.course)):
        by_course.setdefault(n.course, []).append(n)
    for course_notes in by_course.values():
        for i, n in enumerate(course_notes):
            if i + 1 < len(course_notes):
                nxt = course_notes[i + 1]
                if nxt.start - n.start < 1e-3:
                    continue
                end = min(n.end, nxt.start)
            else:
                end = n.end
            out.append(NoteEvent(round(n.start, 4), round(end, 4), n.midi, n.course, float(np.clip(n.velocity, 0.05, 1.0))))
    out.sort(key=lambda n: (n.start, n.course))
    return out


def _note(inst: Instrument, midi: int, t: float, dur: float, vel: float, hand: int = 0):
    pos = inst.position(midi, hand)
    if pos is None:
        return None, hand
    course, fret = pos
    return NoteEvent(t, t + dur, midi, course, vel), fret


def _scale_pitches(inst: Instrument, rng: np.random.Generator) -> List[int]:
    """All pitches of a random dromos on a random root within the instrument's range."""
    steps = DROMOI[rng.choice(sorted(DROMOI))]
    root = int(rng.integers(0, 12))
    return [m for m in range(inst.lowest, inst.highest + 1) if (m - root) % 12 in steps]


def scale_phrase(inst: Instrument, rng: np.random.Generator) -> List[NoteEvent]:
    pitches = _scale_pitches(inst, rng)
    beat = 60.0 / rng.uniform(70, 200)
    unit = beat / rng.choice([2, 4])  # eighths or sixteenths
    idx = int(rng.integers(0, max(1, len(pitches) - 14)))
    t = rng.uniform(*LEAD_IN)
    hand, notes = 0, []
    for _ in range(int(rng.integers(12, 48))):
        r = rng.random()
        step = 1 if r < 0.4 else -1 if r < 0.75 else int(rng.choice([-4, -3, -2, 2, 3, 4, 7, -7]))
        idx = int(np.clip(idx + step, 0, len(pitches) - 1))
        dur = unit * rng.choice([1, 1, 1, 2, 3], p=[0.4, 0.2, 0.15, 0.2, 0.05])
        accent = 0.15 if rng.random() < 0.25 else 0.0
        n, hand = _note(inst, pitches[idx], t, dur * rng.uniform(0.6, 1.0), rng.uniform(0.55, 0.85) + accent, hand)
        if n:
            notes.append(n)
        t += dur
    return _finish(notes, rng)


def tremolo_phrase(inst: Instrument, rng: np.random.Generator) -> List[NoteEvent]:
    pitches = _scale_pitches(inst, rng)
    rate = rng.uniform(10, 18)  # picks per second
    t = rng.uniform(*LEAD_IN)
    idx = int(rng.integers(0, max(1, len(pitches) - 8)))
    hand, notes = 0, []
    for _ in range(int(rng.integers(2, 6))):
        idx = int(np.clip(idx + rng.integers(-3, 4), 0, len(pitches) - 1))
        hold = rng.uniform(0.5, 2.0)
        double = rng.random() < 0.3  # tremolo in thirds/sixths/octaves on a second course
        interval = int(rng.choice([3, 4, 8, 9, 12]))
        k = 0
        while k / rate < hold:
            tt = t + k / rate + rng.normal(0, 0.004)
            vel = (0.75 if k % 2 == 0 else 0.58) + rng.normal(0, 0.04)  # down / up strokes
            n, hand = _note(inst, pitches[idx], tt, 1.0 / rate, vel, hand)
            if n:
                notes.append(n)
                if double:
                    pos = inst.position(pitches[idx] + interval, hand)
                    if pos and pos[0] != n.course:
                        notes.append(NoteEvent(tt, tt + 1.0 / rate, pitches[idx] + interval, pos[0], vel * 0.9))
            k += 1
        t += hold + (rng.uniform(0.0, 0.3) if rng.random() < 0.3 else 0.0)
    return _finish(notes, rng)


def chromatic_run(inst: Instrument, rng: np.random.Generator) -> List[NoteEvent]:
    rate = rng.uniform(5, 16)
    t = rng.uniform(*LEAD_IN)
    notes = []
    if rng.random() < 0.5:  # along one course
        course = int(rng.integers(0, len(inst.courses)))
        a = int(rng.integers(0, inst.frets - 5))
        b = int(rng.integers(a + 5, inst.frets + 1))
        frets = list(range(a, b + 1))
        if rng.random() < 0.5:
            frets.reverse()
        for f in frets:
            notes.append(NoteEvent(t, t + 1.0 / rate, inst.midi_at(course, f), course, rng.uniform(0.55, 0.95)))
            t += 1.0 / rate
    else:  # across courses with the position rule
        a = int(rng.integers(inst.lowest, inst.highest - 12))
        b = int(rng.integers(a + 6, min(inst.highest, a + 30) + 1))
        seq = list(range(a, b + 1))
        if rng.random() < 0.5:
            seq.reverse()
        hand = 0
        for m in seq:
            n, hand = _note(inst, m, t, 1.0 / rate, rng.uniform(0.55, 0.95), hand)
            if n:
                notes.append(n)
            t += 1.0 / rate
    return _finish(notes, rng)


def random_pattern(inst: Instrument, rng: np.random.Generator) -> List[NoteEvent]:
    t = rng.uniform(*LEAD_IN)
    notes = []
    for _ in range(int(rng.integers(8, 40))):
        if rng.random() < 0.2:  # chord on 2-3 distinct courses, frets within a hand span
            courses = rng.choice(len(inst.courses), size=int(rng.integers(2, min(3, len(inst.courses)) + 1)), replace=False)
            base = int(rng.integers(0, inst.frets - 4))
            dur = rng.uniform(0.2, 1.5)
            for c in courses:
                f = base + int(rng.integers(0, 5))
                notes.append(NoteEvent(t + rng.uniform(0, 0.03), t + dur, inst.midi_at(int(c), f), int(c), rng.uniform(0.5, 0.95)))
        else:
            c = int(rng.integers(0, len(inst.courses)))
            f = int(rng.integers(0, inst.frets + 1))
            dur = rng.uniform(0.05, 1.5) if rng.random() < 0.8 else rng.uniform(1.5, 3.0)  # some ring long
            notes.append(NoteEvent(t, t + dur, inst.midi_at(c, f), c, rng.uniform(0.3, 1.0)))
        t += rng.uniform(0.05, 0.6) + (rng.uniform(0.3, 1.0) if rng.random() < 0.1 else 0.0)  # rests
    return _finish(notes, rng)


def every_fret(inst: Instrument, course: int, rng: np.random.Generator) -> List[NoteEvent]:
    t = rng.uniform(*LEAD_IN)
    notes = []
    for f in range(inst.frets + 1):
        dur = rng.uniform(0.4, 0.9)
        notes.append(NoteEvent(t, t + dur, inst.midi_at(course, f), course, rng.uniform(0.5, 0.95)))
        t += dur + rng.uniform(0.05, 0.3)
    return _finish(notes, rng)


GENERATORS: Dict[str, Callable[[Instrument, np.random.Generator], List[NoteEvent]]] = {
    "scale": scale_phrase,
    "tremolo": tremolo_phrase,
    "chromatic": chromatic_run,
    "random": random_pattern,
}


def phrase_duration(notes: List[NoteEvent]) -> float:
    return (max(n.end for n in notes) if notes else 0.0) + TAIL


def generate(out_dir: str, n: int, instruments: List[str], seed: int = 0, every_fret_passes: int = 1) -> List[str]:
    """Writes ``n`` random phrases per instrument (kinds round-robin) plus ``every_fret_passes``
    every-fret phrases per course. Returns the written paths."""
    rng = np.random.default_rng(seed)
    os.makedirs(out_dir, exist_ok=True)
    paths = []
    kinds = sorted(GENERATORS)
    for name in instruments:
        inst = INSTRUMENTS[name]
        for i in range(n):
            kind = kinds[i % len(kinds)]
            notes = GENERATORS[kind](inst, rng)
            if not notes:
                continue
            validate(inst, notes)
            path = os.path.join(out_dir, f"{kind}_{name}_{i:05d}.mid")
            write_midi(path, inst, notes)
            paths.append(path)
        for p in range(every_fret_passes):
            for c in range(len(inst.courses)):
                notes = every_fret(inst, c, rng)
                path = os.path.join(out_dir, f"everyfret_{name}_c{c}_{p:03d}.mid")
                write_midi(path, inst, notes)
                paths.append(path)
    return paths


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="directory for the .mid files")
    ap.add_argument("--n", type=int, default=100, help="random phrases per instrument")
    ap.add_argument("--instrument", choices=["tetrachordo", "trichordo", "both"], default="both")
    ap.add_argument("--every-fret-passes", type=int, default=1, help="every-fret phrases per course (0 = none)")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)
    names = ["tetrachordo", "trichordo"] if a.instrument == "both" else [a.instrument]
    paths = generate(a.out, a.n, names, a.seed, a.every_fret_passes)
    print(f"wrote {len(paths)} MIDI phrases to {a.out}")


if __name__ == "__main__":
    main()
