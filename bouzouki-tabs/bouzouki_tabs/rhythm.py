"""Tempo estimation and the time grid used to lay out the tab."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

# Pulses per bar and grid slots per pulse. For x/8 metres (zeibekiko 9/8,
# kalamatianos 7/8) the pulse is the eighth note; otherwise the quarter.
METERS = {
    "2/4": (2, 4),
    "3/4": (3, 4),
    "4/4": (4, 4),
    "5/8": (5, 2),
    "7/8": (7, 2),
    "9/8": (9, 2),
    "12/8": (12, 2),
}


@dataclass
class Grid:
    bpm: float  # pulses per minute
    origin: float  # time of the first pulse, seconds
    pulses_per_bar: int
    slots_per_pulse: int

    @property
    def slot_seconds(self) -> float:
        return 60.0 / self.bpm / self.slots_per_pulse

    @property
    def slots_per_bar(self) -> int:
        return self.pulses_per_bar * self.slots_per_pulse

    def slot(self, t: float) -> int:
        return int(round((t - self.origin) / self.slot_seconds))

    def time(self, slot: int) -> float:
        return self.origin + slot * self.slot_seconds


def parse_meter(meter: str) -> tuple[int, int]:
    if meter not in METERS:
        raise ValueError(f"unsupported time signature {meter!r}; choose from {', '.join(METERS)}")
    return METERS[meter]


def estimate_tempo(onset_env: np.ndarray, frame_rate: float, lo: float = 60.0, hi: float = 200.0) -> float:
    """Pulse rate in beats per minute, from the autocorrelation of the onset envelope."""
    env = onset_env - onset_env.mean()
    if env.size < 4 or not np.any(env):
        return 120.0
    n = len(env)
    spectrum = np.fft.rfft(env, 2 * n)
    acf = np.fft.irfft(spectrum * np.conj(spectrum))[:n]
    lags = np.arange(max(1, int(frame_rate * 60 / hi)), min(n - 1, int(frame_rate * 60 / lo)) + 1)
    if lags.size == 0:
        return 120.0
    bpms = 60 * frame_rate / lags
    # Prefer moderate tempos so we don't lock onto double or half speed.
    prior = np.exp(-0.5 * (np.log2(bpms / 110.0) / 0.9) ** 2)
    best = lags[np.argmax(acf[lags] * prior)]
    # Refine to a fractional lag with a parabola through the neighbours.
    if 1 <= best < n - 1:
        a, b, c = acf[best - 1], acf[best], acf[best + 1]
        denom = a - 2 * b + c
        if denom < 0:
            best = best + 0.5 * (a - c) / denom
    return float(60 * frame_rate / best)


def beat_origin(onset_env: np.ndarray, frame_rate: float, bpm: float, first_note: float) -> float:
    """Place the pulse grid so it lines up with the attacks, starting at or before the first note."""
    period = 60.0 / bpm * frame_rate
    best_phase, best_score = 0.0, -1.0
    for phase in np.arange(0, period, 0.5):
        idx = np.round(np.arange(phase, len(onset_env), period)).astype(int)
        idx = idx[idx < len(onset_env)]
        score = float(onset_env[idx].sum())
        if score > best_score:
            best_phase, best_score = phase, score
    origin = best_phase / frame_rate
    step = 60.0 / bpm
    # Shift by whole pulses so the grid starts on the last pulse before the first note
    # (with a little slack for notes picked a hair early).
    origin += np.floor((first_note + 0.05 - origin) / step) * step
    return float(origin)
