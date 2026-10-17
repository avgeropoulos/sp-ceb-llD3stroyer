"""From a YouTube link (or audio file) to a bouzouki tab."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import youtube
from .analysis import analyze
from .audio import SAMPLE_RATE, format_time, load_audio
from .dromos import DromosMatch, detect_dromos
from .fretboard import Position, assign_positions
from .instrument import Tuning, get_tuning, note_name
from .notes import Note, segment_notes, track_melody
from .rhythm import Grid, beat_origin, estimate_tempo, parse_meter
from .tab import render_tab


@dataclass
class Transcription:
    title: str
    source: str
    video_id: str | None
    tuning: Tuning
    meter: str
    grid: Grid
    tuning_cents: float
    time_offset: float
    notes: list[Note] = field(default_factory=list)
    positions: list[Position] = field(default_factory=list)
    dromoi: list[DromosMatch] = field(default_factory=list)
    tab: str = ""

    def header(self) -> str:
        lines = [self.title, "=" * len(self.title)]
        if self.source != self.title:
            lines.append(f"Source:  {self.source}")
        lines.append(f"Tuning:  {self.tuning.describe()} (lowest course at the bottom)")
        lines.append(f"Tempo:   ~{self.grid.bpm:.0f} BPM, {self.meter}, one column per 16th note")
        if abs(self.tuning_cents) >= 5:
            direction = "sharp" if self.tuning_cents > 0 else "flat"
            lines.append(f"Pitch:   recording is {abs(self.tuning_cents):.0f} cents {direction} of A440")
        if self.dromoi:
            guesses = ", ".join(str(d) for d in self.dromoi)
            lines.append(f"Dromos:  {guesses} (best guess first)")
        moved = sum(1 for p in self.positions if p.transposed)
        if moved:
            lines.append(f"Note:    {moved} notes were out of range and moved by an octave")
        return "\n".join(lines)

    def text(self) -> str:
        return f"{self.header()}\n\n{self.tab}"

    def to_dict(self) -> dict:
        return {
            "title": self.title,
            "source": self.source,
            "video_id": self.video_id,
            "tuning": self.tuning.describe(),
            "meter": self.meter,
            "bpm": round(self.grid.bpm, 1),
            "tuning_cents": round(self.tuning_cents, 1),
            "dromoi": [
                {"name": d.name, "tonic": d.tonic, "scale": d.notes, "score": round(d.score, 3)} for d in self.dromoi
            ],
            "notes": [
                {
                    "time": round(n.start + self.time_offset, 3),
                    "duration": round(n.duration, 3),
                    "note": note_name(n.midi),
                    "midi": n.midi,
                    "course": self.tuning.labels[p.string],
                    "fret": p.fret,
                }
                for n, p in zip(self.notes, self.positions)
            ],
            "header": self.header(),
            "tab": self.tab,
        }


def transcribe(
    source: str,
    *,
    start: float | None = None,
    end: float | None = None,
    tuning: str | Tuning = "tetrachordo",
    meter: str = "4/4",
    bpm: float | None = None,
    separate: bool = False,
    width: int = 100,
    progress: Callable[[str], None] = lambda message: None,
) -> Transcription:
    tuning = get_tuning(tuning) if isinstance(tuning, str) else tuning
    pulses_per_bar, slots_per_pulse = parse_meter(meter)

    if youtube.is_url(source):
        progress("Downloading audio from YouTube...")
        video = youtube.download_audio(source)
        audio_path, title, vid, source_label = video.audio_path, video.title, video.id, video.url
    else:
        audio_path = Path(source)
        if not audio_path.exists():
            raise FileNotFoundError(f"no such file: {source}")
        title, vid, source_label = audio_path.stem, None, str(audio_path)

    if separate:
        progress("Separating the bouzouki from vocals, bass and drums (Demucs)...")
        audio_path = youtube.isolate_bouzouki(audio_path)

    progress("Decoding audio...")
    y = load_audio(audio_path, SAMPLE_RATE, start, end)
    offset = start or 0.0

    progress("Listening for the bouzouki...")
    # Allow a few semitones below the lowest course: detuned or transposed playing still lands in range.
    analysis = analyze(y, SAMPLE_RATE, tuning.lowest - 3, tuning.highest)
    notes = segment_notes(analysis, track_melody(analysis))

    progress(f"Found {len(notes)} notes; working out fingering and rhythm...")
    positions = assign_positions(notes, tuning)
    if bpm is None:
        bpm = estimate_tempo(analysis.onset_env, analysis.frame_rate)
    origin = beat_origin(analysis.onset_env, analysis.frame_rate, bpm, notes[0].start if notes else 0.0)
    grid = Grid(bpm, origin, pulses_per_bar, slots_per_pulse)

    result = Transcription(
        title=title,
        source=source_label,
        video_id=vid,
        tuning=tuning,
        meter=meter,
        grid=grid,
        tuning_cents=analysis.tuning_cents,
        time_offset=offset,
        notes=notes,
        positions=positions,
        dromoi=detect_dromos(notes),
    )
    result.tab = render_tab(notes, positions, tuning, grid, width=width, time_offset=offset)
    if start or end:
        span = f"{format_time(offset)} - {format_time(offset + len(y) / SAMPLE_RATE)}"
        result.title = f"{title} [{span}]"
    return result
