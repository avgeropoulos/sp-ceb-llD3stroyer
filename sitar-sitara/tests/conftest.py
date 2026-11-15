import sys
from pathlib import Path

import librosa
import numpy as np
import pytest
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
from synth import (  # noqa: E402
    SA,
    SINGER_NOTES,
    SR,
    bhairavi_phrase,
    hz,
    melody,
    singer,
    yaman_phrase,
)

from sitar_sitara import separate  # noqa: E402

SOURCES = ["drums", "bass", "other", "vocals", "guitar", "piano"]


class FakeModel:
    """Stands in for the Demucs model, whose weights can't be downloaded in tests."""

    sources = SOURCES
    samplerate = 44100
    audio_channels = 2


def fake_apply(stems_for):
    """A separator that hands back known stems: stems_for(mix) -> {source: (ch, n)}."""

    def apply(model, wav):
        stems = stems_for(wav)
        return np.stack([stems.get(s, np.zeros_like(wav)) for s in model.sources])

    return apply


@pytest.fixture(autouse=True)
def no_real_separation(monkeypatch):
    """By default, "separation" passes everything through as the 'other' stem."""
    monkeypatch.setattr(separate, "_load_model", lambda: FakeModel())
    monkeypatch.setattr(separate, "_apply", fake_apply(lambda wav: {"other": wav}))


@pytest.fixture(scope="session")
def phrase_wav(tmp_path_factory) -> Path:
    path = tmp_path_factory.mktemp("audio") / "yaman.wav"
    sf.write(path, melody(yaman_phrase()), SR)
    return path


@pytest.fixture(scope="session")
def bhairavi_wav(tmp_path_factory) -> Path:
    path = tmp_path_factory.mktemp("audio") / "bhairavi.wav"
    sf.write(path, melody(bhairavi_phrase()), SR)
    return path


@pytest.fixture(scope="session")
def mix_wav(tmp_path_factory) -> Path:
    """The phrase over a tabla's bass drum, a tanpura drone and some hiss."""
    y = melody(yaman_phrase()).astype(float)
    t = np.arange(len(y)) / SR
    rng = np.random.default_rng(1)
    for beat in np.arange(0.5, t[-1], 0.5):
        i = int(beat * SR)
        n = min(len(y) - i, int(0.12 * SR))
        env = np.exp(-np.arange(n) / (0.02 * SR))
        y[i : i + n] += 0.5 * env * np.sin(2 * np.pi * 60 * np.arange(n) / SR)
    # Tanpura: low Sa and Pa, each a stack of harmonics, well under the sitar.
    for midi in (SA - 12, SA - 5):
        y += 0.012 * sum(np.sin(2 * np.pi * k * hz(midi) * t) / k for k in range(1, 9))
    y += 0.01 * rng.standard_normal(len(y))
    path = tmp_path_factory.mktemp("audio") / "mix.wav"
    sf.write(path, (0.5 * y / np.abs(y).max()).astype(np.float32), SR)
    return path


@pytest.fixture(scope="session")
def song(tmp_path_factory):
    """The phrase with a louder singer on top, at 44.1 kHz stereo like a real track.

    Returns (path, vocals) where vocals is the singer's part as it sits in the file.
    """
    sr = FakeModel.samplerate
    sitar = librosa.resample(melody(yaman_phrase()).astype(float), orig_sr=SR, target_sr=sr)
    voice = singer(SINGER_NOTES, len(sitar) / sr, sr)[: len(sitar)]
    voice *= 1.5 * np.abs(sitar).max() / np.abs(voice).max()
    scale = 0.5 / np.abs(sitar + voice).max()
    stereo = np.stack([sitar + voice, sitar + voice]) * scale
    path = tmp_path_factory.mktemp("audio") / "song.wav"
    sf.write(path, stereo.T.astype(np.float32), sr, subtype="FLOAT")
    return path, (np.stack([voice, voice]) * scale).astype(np.float32)
