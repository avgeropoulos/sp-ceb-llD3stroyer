"""Augmentation: audiomentations (MIT) chain plus a label-consistent +-50 cent detune.

Only effects that do not move notes in time are used, so the targets stay valid:

* ApplyImpulseResponse   convolution with the owner's IRs (``--ir-dir``: .wav files of rooms,
                         bouzouki bodies, phone mics...). Trim leading silence from IRs: a pre-delay
                         would shift every onset.
* RoomSimulator          pyroomacoustics shoebox rooms (MIT), length kept unchanged
* SevenBandParametricEQ, High/LowPassFilter   EQ, cheap mics and phone speakers
* Compressor             a small feed-forward compressor (below); audiomentations' Limiter is used
                         too when ``cylimiter`` (Apache-2.0) is installed
* AddGaussianSNR, AddColorNoise, Gain
* Mp3Compression         OPTIONAL (``--mp3``): its encoders (LAME via fast-mp3-augment / lameenc, or
                         pydub + ffmpeg) are LGPL/GPL tools, so it is off by default and not in
                         requirements.txt; list such a tool in DATA_LICENSES.yaml if you enable it.

Detune: :func:`varispeed` resamples the audio by ``2**(cents/1200)``; pitch AND tempo change
together, so the note times are scaled by the inverse ratio (exact for any audio, real or synthetic)
and the targets keep the nearest semitone while the contours follow the real pitch. For synthetic
audio the renderer can instead tune the strings directly (``tuning_cents``) without changing time.
"""
from typing import List, Optional, Tuple

import numpy as np
from scipy.signal import lfilter, resample

from .constants import AUDIO_SAMPLE_RATE
from .notes import NoteEvent, shift_time
from .targets import MAX_DETUNE_CENTS


def varispeed(audio: np.ndarray, notes: List[NoteEvent], cents: float) -> Tuple[np.ndarray, List[NoteEvent]]:
    """Plays ``audio`` ``cents`` sharper (and 2**(c/1200) times faster), moving the notes with it."""
    if abs(cents) > MAX_DETUNE_CENTS:
        raise ValueError(f"|cents| must be <= {MAX_DETUNE_CENTS}")
    if cents == 0:
        return audio, notes
    ratio = 2.0 ** (cents / 1200.0)
    n_out = int(round(len(audio) / ratio))
    out = resample(audio.astype(np.float64), n_out).astype(np.float32)
    return out, shift_time(notes, scale=1.0 / ratio)


def compress(
    x: np.ndarray, fs: int, threshold_db: float, ratio: float, attack: float = 0.005, release: float = 0.08, makeup: bool = True
) -> np.ndarray:
    """Feed-forward compressor on a one-pole envelope (attack/release in seconds)."""
    env = np.abs(x).astype(np.float64)
    # Peak-ish envelope: fast attack via max with a short smoothing, release via one-pole.
    a_att = np.exp(-1.0 / (attack * fs))
    a_rel = np.exp(-1.0 / (release * fs))
    sm = lfilter([1 - a_att], [1, -a_att], env)
    sm = np.maximum(sm, lfilter([1 - a_rel], [1, -a_rel], env))
    level_db = 20 * np.log10(np.maximum(sm, 1e-6))
    over = np.maximum(0.0, level_db - threshold_db)
    gain_db = -over * (1 - 1 / ratio)
    y = x * 10 ** (gain_db / 20)
    if makeup:
        peak_in, peak_out = np.abs(x).max(), np.abs(y).max()
        if peak_out > 0:
            y *= peak_in / peak_out
    return y.astype(np.float32)


class Augmenter:
    """Random augmentation of one clip. ``detune_cents`` is the maximum |offset| drawn."""

    def __init__(
        self,
        ir_dir: Optional[str] = None,
        room: bool = True,
        mp3: bool = False,
        detune_cents: float = 50.0,
        fs: int = AUDIO_SAMPLE_RATE,
    ):
        import audiomentations as A

        self.fs = fs
        self.detune_cents = min(abs(detune_cents), MAX_DETUNE_CENTS)
        stages = []
        if ir_dir:
            stages.append(A.ApplyImpulseResponse(ir_path=ir_dir, p=0.5, leave_length_unchanged=True))
        if room:
            stages.append(A.RoomSimulator(p=0.3, leave_length_unchanged=True, use_ray_tracing=False, max_order=3))
        stages += [
            A.SevenBandParametricEQ(min_gain_db=-6.0, max_gain_db=6.0, p=0.5),
            A.HighPassFilter(min_cutoff_freq=40.0, max_cutoff_freq=250.0, p=0.3),
            A.LowPassFilter(min_cutoff_freq=3500.0, max_cutoff_freq=10000.0, p=0.3),
        ]
        try:  # optional, Apache-2.0
            import cylimiter  # noqa: F401

            stages.append(A.Limiter(min_threshold_db=-18.0, max_threshold_db=-3.0, p=0.2))
        except ImportError:
            pass
        self.pre = A.Compose(stages, p=1.0)
        self.post = A.Compose(
            [
                A.AddColorNoise(min_snr_db=20.0, max_snr_db=50.0, p=0.3),
                A.AddGaussianSNR(min_snr_db=20.0, max_snr_db=50.0, p=0.3),
                A.Gain(min_gain_db=-12.0, max_gain_db=6.0, p=0.5),
            ]
            + ([A.Mp3Compression(min_bitrate=32, max_bitrate=192, p=0.3)] if mp3 else []),
            p=1.0,
        )

    def __call__(
        self, audio: np.ndarray, notes: List[NoteEvent], rng: np.random.Generator, already_detuned: bool = False
    ) -> Tuple[np.ndarray, List[NoteEvent], float]:
        """Returns (audio, notes moved with the audio, cents offset applied)."""
        cents = 0.0
        if not already_detuned and self.detune_cents > 0 and rng.random() < 0.7:
            cents = float(rng.uniform(-self.detune_cents, self.detune_cents))
            audio, notes = varispeed(audio, notes, cents)
        n = len(audio)
        y = self.pre(samples=audio.astype(np.float32), sample_rate=self.fs)
        if rng.random() < 0.3:
            y = compress(y, self.fs, threshold_db=float(rng.uniform(-30, -10)), ratio=float(rng.uniform(2, 8)))
        y = self.post(samples=y.astype(np.float32), sample_rate=self.fs)
        y = np.asarray(y, np.float32)[:n]
        if len(y) < n:
            y = np.pad(y, (0, n - len(y)))
        peak = np.abs(y).max()
        if peak > 1.0:
            y /= peak
        return y, notes, cents
