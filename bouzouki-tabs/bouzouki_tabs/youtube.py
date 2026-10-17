"""Fetching audio from YouTube with yt-dlp."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

_YOUTUBE_ID = re.compile(r"(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})")


@dataclass
class Video:
    id: str
    title: str
    url: str
    audio_path: Path
    duration: float | None = None


class DownloadError(RuntimeError):
    pass


def is_url(source: str) -> bool:
    return source.startswith(("http://", "https://", "www.", "youtu.be/", "youtube.com/"))


def video_id(url: str) -> str | None:
    match = _YOUTUBE_ID.search(url)
    return match.group(1) if match else None


def cache_dir() -> Path:
    base = os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache"
    path = Path(base) / "bouzouki-tabs"
    path.mkdir(parents=True, exist_ok=True)
    return path


def download_audio(url: str, directory: Path | None = None) -> Video:
    """Download the audio track of a video, reusing an earlier download if there is one."""
    try:
        import yt_dlp
    except ImportError as exc:
        raise DownloadError("yt-dlp is not installed: pip install yt-dlp") from exc

    directory = directory or cache_dir()
    options = {
        "format": "bestaudio/best",
        "outtmpl": str(directory / "%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
            path = Path(ydl.prepare_filename(info))
            if not path.exists():
                info = ydl.extract_info(url, download=True)
                path = Path(ydl.prepare_filename(info))
    except yt_dlp.utils.DownloadError as exc:
        raise DownloadError(f"could not download {url}: {exc}") from exc
    return Video(info["id"], info.get("title") or info["id"], info.get("webpage_url") or url, path, info.get("duration"))


def isolate_bouzouki(audio_path: Path, directory: Path | None = None) -> Path:
    """Use Demucs (if installed) to strip vocals, bass and drums, keeping the "other" stem.

    The bouzouki ends up in that stem along with any guitars or baglamas.
    """
    try:
        import demucs  # noqa: F401
    except ImportError as exc:
        raise DownloadError("source separation needs Demucs: pip install demucs") from exc
    directory = directory or cache_dir() / "stems"
    model = "htdemucs"
    stem = directory / model / audio_path.stem / "other.wav"
    if stem.exists():
        return stem
    cmd = [sys.executable, "-m", "demucs", "-n", model, "--two-stems", "other", "-o", str(directory), str(audio_path)]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0 or not stem.exists():
        raise DownloadError(f"Demucs failed: {result.stderr.strip()[-500:]}")
    return stem
