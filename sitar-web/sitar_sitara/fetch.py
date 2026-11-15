"""Download a song's audio from YouTube with yt-dlp."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, urlparse

MAX_DURATION = 15 * 60  # seconds; this is a song analyser, not a concert archive

_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"}


class FetchError(Exception):
    pass


@dataclass
class Video:
    id: str
    title: str
    duration: float
    path: Path


def video_id(url: str) -> str:
    """Pull the video ID out of a YouTube link (or accept a bare ID).

    Only YouTube links are accepted: yt-dlp will fetch from almost anywhere,
    and a server shouldn't be talked into downloading arbitrary URLs.
    """
    url = url.strip()
    if _ID.match(url):
        return url
    parsed = urlparse(url if "://" in url else "https://" + url)
    host = (parsed.hostname or "").lower()
    candidate = ""
    if host == "youtu.be":
        candidate = parsed.path.lstrip("/").split("/")[0]
    elif host in _HOSTS:
        if parsed.path == "/watch":
            candidate = parse_qs(parsed.query).get("v", [""])[0]
        else:
            parts = parsed.path.strip("/").split("/")
            if len(parts) >= 2 and parts[0] in ("shorts", "embed", "live", "v"):
                candidate = parts[1]
    if not _ID.match(candidate):
        raise FetchError("That doesn't look like a YouTube video link.")
    return candidate


def download_audio(
    url: str,
    dest: Path,
    start: float | None = None,
    end: float | None = None,
    max_duration: float = MAX_DURATION,
    sample_rate: int = 22050,
    channels: int = 1,
) -> Video:
    import yt_dlp
    from yt_dlp.utils import download_range_func

    vid = video_id(url)
    canonical = f"https://www.youtube.com/watch?v={vid}"
    dest.mkdir(parents=True, exist_ok=True)

    opts = {
        "format": "bestaudio/best",
        "outtmpl": str(dest / "audio.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "postprocessors": [
            {"key": "FFmpegExtractAudio", "preferredcodec": "wav"},
        ],
        "postprocessor_args": {
            "extractaudio": ["-ac", str(channels), "-ar", str(sample_rate)]
        },
    }
    if start is not None or end is not None:
        opts["download_ranges"] = download_range_func(
            None, [(start or 0.0, end if end is not None else float("inf"))]
        )
        opts["force_keyframes_at_cuts"] = True

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(canonical, download=False)
            duration = float(info.get("duration") or 0)
            clip = (end if end is not None else duration) - (start or 0.0)
            if clip > max_duration:
                raise FetchError(
                    f"That's {int(clip // 60)} minutes of audio; the limit is "
                    f"{int(max_duration // 60)}. Set a start and end time around the sitar part."
                )
            ydl.process_ie_result(info, download=True)
    except yt_dlp.utils.DownloadError as e:
        raise FetchError(f"YouTube download failed: {e}") from e

    path = dest / "audio.wav"
    if not path.exists():
        raise FetchError("YouTube download finished but produced no audio.")
    return Video(id=vid, title=info.get("title") or vid, duration=duration, path=path)
