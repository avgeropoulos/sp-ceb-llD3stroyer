"""A crude plucked-string synthesiser for tests."""

from __future__ import annotations

import numpy as np

SR = 22050


def pluck(midi: int, duration: float, sr: int = SR, octave_pair: bool = False, cents: float = 0.0) -> np.ndarray:
    t = np.arange(int(duration * sr)) / sr
    f0 = 440.0 * 2 ** ((midi - 69 + cents / 100) / 12)
    tone = np.zeros_like(t)
    for h in range(1, 13):
        if h * f0 > sr / 2:
            break
        tone += np.exp(-1.5 * h * t) * np.sin(2 * np.pi * h * f0 * t) / h
    if octave_pair:
        tone += 0.5 * np.exp(-3 * t) * np.sin(2 * np.pi * 2 * f0 * t)
    attack = np.minimum(t / 0.003, 1.0)
    return tone * attack * np.exp(-2.0 * t)


def melody(notes: list[tuple[int, float]], sr: int = SR, noise: float = 0.0, cents: float = 0.0, seed: int = 0):
    """Render (midi, duration) pairs; a midi of None is a rest. Returns audio and note start times."""
    parts, starts, t = [], [], 0.0
    for midi, duration in notes:
        if midi is None:
            parts.append(np.zeros(int(duration * sr)))
        else:
            starts.append(t)
            parts.append(pluck(midi, duration, sr, octave_pair=midi < 57, cents=cents))
        t += duration
    audio = np.concatenate(parts + [np.zeros(sr // 2)])
    if noise:
        audio = audio + noise * np.random.default_rng(seed).standard_normal(audio.size)
    return (0.5 * audio / np.max(np.abs(audio))).astype(np.float32), starts
