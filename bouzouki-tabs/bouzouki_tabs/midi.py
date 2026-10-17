"""Minimal Standard MIDI File writer, so a transcription can be checked by ear."""

from __future__ import annotations

import struct
from pathlib import Path

from .notes import Note

TICKS_PER_BEAT = 480
BOUZOUKI_PROGRAM = 105  # General MIDI "Banjo": the closest plucked sound


def _vlq(value: int) -> bytes:
    out = [value & 0x7F]
    value >>= 7
    while value:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    return bytes(reversed(out))


def write_midi(notes: list[Note], path: str | Path, bpm: float = 120.0) -> None:
    ticks_per_second = TICKS_PER_BEAT * bpm / 60.0
    events: list[tuple[int, bytes]] = []
    for note in notes:
        pitch = max(0, min(127, note.midi))
        events.append((round(note.start * ticks_per_second), bytes([0x90, pitch, 96])))
        events.append((round(note.end * ticks_per_second), bytes([0x80, pitch, 0])))
    events.sort(key=lambda e: (e[0], e[1][0] == 0x90))

    tempo = round(60_000_000 / bpm)
    track = bytearray(_vlq(0) + b"\xff\x51\x03" + tempo.to_bytes(3, "big"))
    track += _vlq(0) + bytes([0xC0, BOUZOUKI_PROGRAM])
    now = 0
    for tick, message in events:
        track += _vlq(tick - now) + message
        now = tick
    track += _vlq(0) + b"\xff\x2f\x00"

    header = b"MThd" + struct.pack(">IHHH", 6, 0, 1, TICKS_PER_BEAT)
    Path(path).write_bytes(header + b"MTrk" + struct.pack(">I", len(track)) + bytes(track))
