"""Command line interface."""

from __future__ import annotations

import argparse
import json
import sys

from .audio import AudioError, parse_time
from .instrument import TUNINGS
from .midi import write_midi
from .pipeline import transcribe
from .rhythm import METERS
from .youtube import DownloadError


def _tab_command(args: argparse.Namespace) -> int:
    def progress(message: str) -> None:
        print(message, file=sys.stderr)

    result = transcribe(
        args.source,
        start=parse_time(args.start),
        end=parse_time(args.end),
        tuning=args.tuning,
        meter=args.meter,
        bpm=args.bpm,
        separate=args.separate,
        width=args.width,
        progress=progress,
    )
    text = result.text()
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
        progress(f"Tab written to {args.output}")
    else:
        print(text)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(result.to_dict(), fh, indent=2, ensure_ascii=False)
        progress(f"Notes written to {args.json}")
    if args.midi:
        write_midi(result.notes, args.midi, result.grid.bpm)
        progress(f"MIDI written to {args.midi}")
    return 0


def _serve_command(args: argparse.Namespace) -> int:
    from .web import serve

    serve(args.host, args.port)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="bouzouki-tabs",
        description="Listen to the bouzouki in a song on YouTube and write out a tab.",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    tab = commands.add_parser("tab", help="transcribe a YouTube video or audio file")
    tab.add_argument("source", help="YouTube URL or a local audio/video file")
    tab.add_argument("--start", help="where to start, e.g. 0:45 (the intro or solo works best)")
    tab.add_argument("--end", help="where to stop, e.g. 1:30")
    tab.add_argument(
        "--tuning",
        default="tetrachordo",
        help=f"{' or '.join(TUNINGS)} (default tetrachordo), or notes like C3,F3,A3,D4",
    )
    tab.add_argument("--meter", default="4/4", choices=list(METERS), help="time signature (zeibekiko is 9/8)")
    tab.add_argument("--bpm", type=float, help="set the tempo instead of detecting it (pulses per minute)")
    tab.add_argument(
        "--separate", action="store_true", help="isolate the bouzouki with Demucs first (slower, needs demucs)"
    )
    tab.add_argument("--width", type=int, default=100, help="maximum line width of the tab")
    tab.add_argument("-o", "--output", help="write the tab to this file instead of the screen")
    tab.add_argument("--json", help="also save every detected note as JSON")
    tab.add_argument("--midi", help="also save the notes as a MIDI file to check by ear")
    tab.set_defaults(func=_tab_command)

    web = commands.add_parser("serve", help="run the web app")
    web.add_argument("--host", default="127.0.0.1")
    web.add_argument("--port", type=int, default=8000)
    web.set_defaults(func=_serve_command)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (AudioError, DownloadError, FileNotFoundError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
