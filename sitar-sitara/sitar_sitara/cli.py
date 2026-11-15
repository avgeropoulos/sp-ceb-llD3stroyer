"""Command line: sitar-sitara <youtube-url-or-audio-file>"""

from __future__ import annotations

import argparse
import sys
import tempfile
from dataclasses import replace
from pathlib import Path

from .fetch import FetchError, download_audio
from .pipeline import Options, analyze
from .sargam import NOTE_NAMES, SCRIPTS
from .taal import DEFAULT, TAALS

_FLATS = {"DB": "C#", "EB": "D#", "GB": "F#", "AB": "G#", "BB": "A#"}


def parse_time(value: str) -> float:
    """Accept seconds ("75") or minutes:seconds ("1:15")."""
    parts = value.split(":")
    try:
        seconds = 0.0
        for part in parts:
            seconds = seconds * 60 + float(part)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a time: {value!r}")
    return seconds


def parse_sa(value: str) -> int:
    """Accept a note name: "C#", "Db", "d"."""
    name = value.strip().upper()
    name = _FLATS.get(name, name)
    if name not in NOTE_NAMES:
        raise argparse.ArgumentTypeError(f"not a note name: {value!r} (try C# or D)")
    return NOTE_NAMES.index(name)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="sitar-sitara",
        description="Transcribe the sitar in a song to sargam notation.",
    )
    parser.add_argument("source", help="YouTube link, or a local audio file")
    parser.add_argument("--start", type=parse_time, help="start time, e.g. 0:42")
    parser.add_argument("--end", type=parse_time, help="end time, e.g. 1:30")
    parser.add_argument("--taal", choices=sorted(TAALS), default=DEFAULT,
                        help="rhythm cycle to lay the notes out in (default: %(default)s)")
    parser.add_argument("--sa", type=parse_sa, metavar="NOTE",
                        help="the note Sa is tuned to, e.g. C# (default: work it out)")
    parser.add_argument("--script", choices=SCRIPTS, default="latin",
                        help="write swaras as S R G or in Devanagari")
    parser.add_argument(
        "--no-separate",
        action="store_true",
        help="skip removing vocals/drums/bass (faster, but they end up in the notation)",
    )
    parser.add_argument("-o", "--output", help="write the notation here instead of stdout")
    parser.add_argument("--midi", help="also write a MIDI file of the transcription")
    args = parser.parse_args(argv)

    def progress(stage: str, frac: float) -> None:
        print(f"\r{stage:<40} {frac:4.0%}", end="", file=sys.stderr, flush=True)

    options = Options(
        start=args.start or 0.0,
        end=args.end,
        taal=args.taal,
        sa=args.sa,
        separate=not args.no_separate,
    )
    try:
        with tempfile.TemporaryDirectory(prefix="sitar-") as tmp:
            if Path(args.source).exists():
                result = analyze(args.source, options, title=Path(args.source).name,
                                 progress=progress)
            else:
                print("Downloading from YouTube...", file=sys.stderr)
                video = download_audio(args.source, Path(tmp), args.start, args.end)
                clip = replace(options, start=0.0, end=None)
                result = analyze(str(video.path), clip, title=video.title,
                                 time_offset=options.start, progress=progress)
    except (FetchError, ValueError) as e:
        print(f"\nerror: {e}", file=sys.stderr)
        return 1
    print(file=sys.stderr)

    for warning in result["warnings"]:
        print(f"note: {warning}", file=sys.stderr)
    text = result["text" if args.script == "latin" else "text_devanagari"]
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
    else:
        print(text)
    if args.midi:
        Path(args.midi).write_bytes(result["_midi"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
