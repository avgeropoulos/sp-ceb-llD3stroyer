"""Spectral analysis: tuning reference, pitch salience and onset strength.

Pitch salience uses harmonic summation: for every semitone in the bouzouki's
range we add up the (compressed) spectral magnitude at its first few
harmonics. The bright, harmonic-rich sound of a picked bouzouki makes its
notes stand out well on this measure, even with other instruments around.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

HOP = 256
PITCH_FFT = 4096
ONSET_FFT = 1024
N_HARMONICS = 8
HARMONIC_DECAY = 0.8
MAX_HARMONIC_HZ = 5000.0
CHUNK = 512


@dataclass
class Analysis:
    sr: int
    hop: int
    midi: np.ndarray  # candidate MIDI pitches, one per salience column
    salience: np.ndarray  # frames x candidates, normalised so strong notes are near 1
    onset_env: np.ndarray  # frames, normalised onset strength
    rms: np.ndarray  # frames
    tuning_cents: float  # offset of the recording from A440 equal temperament

    @property
    def frame_rate(self) -> float:
        return self.sr / self.hop

    @property
    def n_frames(self) -> int:
        return len(self.onset_env)


def _frames(y: np.ndarray, n_fft: int, hop: int) -> np.ndarray:
    pad = n_fft // 2
    padded = np.pad(y, (pad, pad))
    n = 1 + len(y) // hop
    return np.lib.stride_tricks.sliding_window_view(padded, n_fft)[::hop][:n]


def _spectra(y: np.ndarray, n_fft: int, hop: int, step: int = 1):
    """Yield (first_frame, magnitudes) blocks so long songs stay light on memory."""
    frames = _frames(y, n_fft, hop)[::step]
    window = np.hanning(n_fft).astype(np.float32)
    for i in range(0, len(frames), CHUNK):
        block = frames[i : i + CHUNK] * window
        yield i, np.abs(np.fft.rfft(block, axis=1)).astype(np.float32)


def estimate_tuning(y: np.ndarray, sr: int) -> float:
    """Return how many cents the recording sits away from A440 (between -50 and 50).

    Older rebetiko records in particular are often not at concert pitch.
    """
    df = sr / PITCH_FFT
    lo, hi = int(250 / df), int(2500 / df)
    sin_sum = cos_sum = 0.0
    for _, mags in _spectra(y, PITCH_FFT, HOP, step=4):
        band = np.log(mags[:, lo - 1 : hi + 2] + 1e-9)
        a, b, c = band[:, :-2], band[:, 1:-1], band[:, 2:]
        frame_max = b.max(axis=1, keepdims=True)
        peaks = (b > a) & (b >= c) & (b > frame_max - np.log(20))
        rows, cols = np.nonzero(peaks)
        if rows.size == 0:
            continue
        pa, pb, pc = a[rows, cols], b[rows, cols], c[rows, cols]
        denom = pa - 2 * pb + pc
        offset = np.where(np.abs(denom) > 1e-9, 0.5 * (pa - pc) / np.where(denom == 0, 1, denom), 0.0)
        freqs = (lo + cols + offset) * df
        cents = 1200 * np.log2(freqs / 440.0)
        angle = cents / 100.0 * 2 * np.pi
        weight = np.exp(pb)
        sin_sum += float(np.sum(weight * np.sin(angle)))
        cos_sum += float(np.sum(weight * np.cos(angle)))
    if sin_sum == 0 and cos_sum == 0:
        return 0.0
    return float(np.arctan2(sin_sum, cos_sum) / (2 * np.pi) * 100)


def _salience_table(midi: np.ndarray, sr: int, tuning_cents: float):
    df = sr / PITCH_FFT
    nyquist_bin = PITCH_FFT // 2
    table = []
    for ci, m in enumerate(midi):
        f0 = 440.0 * 2 ** ((m - 69 + tuning_cents / 100) / 12)
        for h in range(1, N_HARMONICS + 1):
            f = h * f0
            if f > min(MAX_HARMONIC_HZ, 0.45 * sr):
                break
            lo = int(np.floor(f * 2 ** (-0.35 / 12) / df))
            hi = min(int(np.ceil(f * 2 ** (0.35 / 12) / df)), nyquist_bin)
            table.append((ci, HARMONIC_DECAY ** (h - 1), lo, hi + 1))
    return table


def onset_strength(y: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
    """Spectral-flux onset envelope and frame RMS, both one value per hop."""
    df = sr / ONSET_FFT
    lo, hi = int(60 / df), int(8000 / df)
    flux_parts, rms_parts = [], []
    previous = None
    for _, mags in _spectra(y, ONSET_FFT, HOP):
        logmag = np.log1p(10 * mags[:, lo:hi])
        if previous is None:
            previous = logmag[:1]
        diff = np.diff(np.vstack([previous, logmag]), axis=0)
        flux_parts.append(np.maximum(diff, 0).sum(axis=1))
        rms_parts.append(np.sqrt(np.mean(mags**2, axis=1)) / ONSET_FFT)
        previous = logmag[-1:]
    flux = np.concatenate(flux_parts)
    rms = np.concatenate(rms_parts)
    # Remove the slowly moving part so loud sections don't swamp quiet ones.
    kernel = np.ones(21) / 21
    flux = np.maximum(flux - np.convolve(flux, kernel, mode="same"), 0)
    scale = np.percentile(flux, 99) if np.any(flux > 0) else 1.0
    return np.clip(flux / (scale + 1e-9), 0, 2), rms


def analyze(
    y: np.ndarray, sr: int, midi_lo: int, midi_hi: int, tuning_cents: float | None = None
) -> Analysis:
    peak = float(np.max(np.abs(y))) if y.size else 0.0
    if peak > 0:
        y = y / peak
    if tuning_cents is None:
        tuning_cents = estimate_tuning(y, sr)

    midi = np.arange(midi_lo, midi_hi + 1)
    table = _salience_table(midi, sr, tuning_cents)
    blocks = []
    for _, mags in _spectra(y, PITCH_FFT, HOP):
        compressed = np.sqrt(mags)
        sal = np.zeros((len(mags), len(midi)), dtype=np.float32)
        for ci, weight, lo, hi in table:
            sal[:, ci] += weight * compressed[:, lo:hi].max(axis=1)
        blocks.append(sal)
    salience = np.vstack(blocks)
    reference = np.percentile(salience.max(axis=1), 95)
    salience /= reference + 1e-9

    onset_env, rms = onset_strength(y, sr)
    n = min(len(salience), len(onset_env))
    return Analysis(sr, HOP, midi, salience[:n], onset_env[:n], rms[:n], tuning_cents)


def pick_onsets(
    env: np.ndarray, frame_rate: float, delta: float = 0.08, threshold: float = 0.25, min_gap: float = 0.045
) -> np.ndarray:
    """Return onset times (seconds) from the peaks of an onset envelope."""
    if env.size == 0:
        return np.zeros(0)
    w = 3
    padded = np.pad(env, w, mode="edge")
    local_max = env >= np.lib.stride_tricks.sliding_window_view(padded, 2 * w + 1).max(axis=1)
    avg_w = int(0.1 * frame_rate)
    avg = np.convolve(env, np.ones(2 * avg_w + 1) / (2 * avg_w + 1), mode="same")
    candidates = np.nonzero(local_max & (env > avg + delta) & (env > threshold))[0]
    onsets: list[int] = []
    gap = int(min_gap * frame_rate)
    for idx in candidates:
        if onsets and idx - onsets[-1] < gap:
            if env[idx] > env[onsets[-1]]:
                onsets[-1] = idx
            continue
        onsets.append(idx)
    return np.asarray(onsets) / frame_rate
