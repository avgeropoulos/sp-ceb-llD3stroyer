"""Decoding audio with ffmpeg."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import numpy as np

SAMPLE_RATE = 22050


class AudioError(RuntimeError):
    pass


def parse_time(text: str | float | None) -> float | None:
    """Parse "83", "1:23" or "0:01:23.5" into seconds."""
    if text is None or text == "":
        return None
    if isinstance(text, (int, float)):
        return float(text)
    seconds = 0.0
    for part in str(text).strip().split(":"):
        seconds = seconds * 60 + float(part)
    return seconds


def format_time(seconds: float) -> str:
    minutes, secs = divmod(max(seconds, 0.0), 60)
    return f"{int(minutes)}:{secs:04.1f}"


def load_audio(
    path: str | Path, sr: int = SAMPLE_RATE, start: float | None = None, end: float | None = None
) -> np.ndarray:
    """Decode any file ffmpeg understands to mono float32 at ``sr``."""
    if shutil.which("ffmpeg") is None:
        raise AudioError("ffmpeg is not installed; get it from https://ffmpeg.org or your package manager")
    cmd = ["ffmpeg", "-v", "error", "-nostdin"]
    if start:
        cmd += ["-ss", f"{start:.3f}"]
    cmd += ["-i", str(path)]
    if end is not None:
        cmd += ["-t", f"{end - (start or 0.0):.3f}"]
    cmd += ["-vn", "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        raise AudioError(f"ffmpeg could not read {path}: {result.stderr.decode(errors='replace').strip()}")
    audio = np.frombuffer(result.stdout, dtype=np.float32).copy()
    if audio.size == 0:
        raise AudioError(f"no audio found in {path} for the chosen time range")
    return audio
