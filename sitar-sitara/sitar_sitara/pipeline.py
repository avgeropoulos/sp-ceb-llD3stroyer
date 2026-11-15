"""Audio file in, sitar sargam out."""

from __future__ import annotations

from dataclasses import asdict, dataclass

from . import separate as separation
from .raga import detect_raga
from .render import TabNote, layout, to_midi, to_text
from .sargam import NOTE_NAMES, fit_to_range, midi_name, sa_midi, string_for, swara
from .taal import DEFAULT, TAALS
from .transcribe import SR, load_audio, transcribe

# Where most sitars are tuned, for when there are no notes to judge by.
DEFAULT_SA = 1  # C#


@dataclass
class Options:
    start: float = 0.0
    end: float | None = None
    taal: str = DEFAULT
    # Sa as a pitch class (0 = C ... 11 = B), or None to work it out.
    sa: int | None = None
    min_confidence: float = 0.1
    # Remove vocals, drums, bass and piano with Demucs before transcribing.
    separate: bool = True


LEGEND = {
    "latin": [
        "S R G m P D N = shuddha   r g d n = komal   M = tivra Ma",
        "' after a note = taar (upper) saptak   , after a note = mandra (lower) saptak",
    ],
    "devanagari": [
        "॒ (line below) = komal   ॑ (stroke above) = tivra Ma",
        "dot above = taar (upper) saptak   dot below = mandra (lower) saptak",
    ],
}


def analyze(path: str, options: Options = Options(), title: str = "",
            time_offset: float = 0.0, progress=lambda stage, frac: None) -> dict:
    """Transcribe the file at path.

    Note times are reported relative to the original video: options.start
    (where we started reading the file) plus time_offset (where the file
    itself starts, when only a clip was downloaded).
    """
    if options.taal not in TAALS:
        raise ValueError(f"Unknown taal {options.taal!r}. Choose from: {', '.join(TAALS)}.")
    taal = TAALS[options.taal]
    if options.separate:
        progress("Removing vocals, drums and bass (takes a minute or two)", 0.02)
        try:
            y = separation.load_isolated(path, SR, start=options.start, end=options.end)
        except separation.SeparationUnavailable as e:
            raise ValueError(
                f"{e}. You can turn instrument separation off, but singing and "
                "other instruments will then end up in the notation."
            ) from e
        # Separation is the slow part; give note-finding the second half of the bar.
        inner = progress
        progress = lambda stage, frac: inner(stage, 0.5 + 0.5 * frac)  # noqa: E731
    else:
        progress("Loading audio", 0.02)
        y = load_audio(path, start=options.start, end=options.end)
    if len(y) < SR // 2:
        raise ValueError("The audio is too short to analyse.")
    result = transcribe(y, progress=progress)
    warnings = list(result.warnings)

    progress("Finding Sa and the scale", 0.92)
    notes = [n for n in result.notes if n.confidence >= options.min_confidence]
    ragas = detect_raga([n.midi for n in notes], [n.duration for n in notes], options.sa)
    if options.sa is not None:
        sa_class = options.sa % 12
    else:
        sa_class = ragas[0]["sa_pitch_class"] if ragas else DEFAULT_SA
    sa = sa_midi(sa_class)

    folded = 0
    midis = []
    for n in notes:
        m, moved = fit_to_range(n.midi, sa)
        folded += moved
        midis.append(m)
    if folded:
        warnings.append(
            f"{folded} note(s) were outside the sitar's range and moved by an octave."
        )

    offset = options.start + time_offset
    tab_notes = [
        TabNote(n.start + offset, n.end + offset, m, swara(m, sa), string_for(m, sa).key,
                n.meend, n.fast)
        for n, m in zip(notes, midis)
    ]
    beats = [b + offset for b in result.beat_times]
    systems = layout(tab_notes, taal, beats, result.tempo)

    def header(script: str) -> list[str]:
        lines = [
            title or "Sitar sargam",
            f"Sa = {NOTE_NAMES[sa_class]}   Taal: {taal.label}   Tempo: ~{result.tempo:.0f} BPM",
        ]
        if ragas:
            best = ragas[0]
            family = f" (ragas such as {', '.join(best['ragas'])})" if "thaat" in best["scale"] else ""
            lines.append(f"Scale (best guess): {best['scale']}{family}")
        lines += LEGEND[script]
        marks = ["notes written together share one matra", "- = hold"]
        if any(n.meend for n in tab_notes):
            marks.append("~ = meend: glide into the note, no new stroke")
        if any(n.fast for n in tab_notes):
            marks.append("* = fast repeated strokes (diri)")
        lines.append("   ".join(marks))
        lines.append("X = sam   numbers = tali   0 = khali   | divides vibhags   || ends the cycle")
        return lines

    progress("Done", 1.0)
    return {
        "title": title,
        "sa": {"name": NOTE_NAMES[sa_class], "pitch_class": sa_class, "midi": sa,
               "chosen": options.sa is not None},
        "taal": {"key": taal.key, "name": taal.name, "label": taal.label,
                 "matras": taal.matras, "vibhags": list(taal.vibhags)},
        "tempo": round(result.tempo, 1),
        "tuning_cents": result.tuning_cents,
        "raga": ragas,
        "warnings": warnings,
        "notes": [
            {
                "start": round(t.start, 3),
                "end": round(t.end, 3),
                "midi": t.midi,
                "name": midi_name(t.midi),
                "sargam": t.swara.text(),
                "swara": t.swara.name,
                "string": t.string,
                "meend": t.meend,
                "fast": t.fast,
            }
            for t in tab_notes
        ],
        "systems": [asdict(s) for s in systems],
        "text": to_text(systems, header("latin")),
        "text_devanagari": to_text(systems, header("devanagari"), "devanagari"),
        "_midi": to_midi(tab_notes, result.tempo),
    }
