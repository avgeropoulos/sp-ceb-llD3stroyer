"""Synthetic sitar: bright, buzzing plucked tones whose pitch can glide (meend)."""

import numpy as np

SR = 22050
SA = 61  # C#4, where most sitars put Sa


def hz(midi) -> np.ndarray:
    return 440.0 * 2 ** ((np.asarray(midi, dtype=float) - 69) / 12)


def stroke(pitch: np.ndarray, sr: int = SR, seed: int = 0) -> np.ndarray:
    """One stroke of the plectrum. pitch is the MIDI pitch at every sample.

    The curved bridge (jawari) keeps the upper harmonics buzzing long after
    a guitar's would have died, so they start strong and decay slowly.
    """
    rng = np.random.default_rng(seed)
    freq = hz(pitch)
    t = np.arange(len(freq)) / sr
    phase = 2 * np.pi * np.cumsum(freq) / sr
    out = np.zeros(len(freq))
    k = 1
    while k * freq.max() < 0.45 * sr and k <= 24:
        decay = np.exp(-t * (1.6 + 0.22 * k))
        out += decay * np.sin(k * phase + rng.uniform(0, 2 * np.pi)) / k**0.7
        k += 1
    # The mizrab hitting the string.
    click = rng.standard_normal(len(freq)) * np.exp(-t / 0.004)
    return out + 0.6 * click


def glide(points, dur: float, sr: int = SR, slide: float = 0.12) -> np.ndarray:
    """A pitch curve that holds each MIDI pitch in points and slides between them."""
    n = int(dur * sr)
    if len(points) == 1:
        return np.full(n, float(points[0]))
    hold = (dur - slide * (len(points) - 1)) / len(points)
    curve = []
    for a, b in zip(points, points[1:]):
        curve.append(np.full(int(hold * sr), float(a)))
        x = np.linspace(0, 1, int(slide * sr))
        curve.append(a + (b - a) * (3 * x**2 - 2 * x**3))
    curve = np.concatenate(curve)
    return np.concatenate([curve, np.full(max(0, n - len(curve)), float(points[-1]))])[:n]


def melody(strokes, sr: int = SR) -> np.ndarray:
    """strokes: list of (start_seconds, duration_seconds, pitch).

    pitch is a MIDI number, a list of them (one stroke that glides through
    them), or an array giving the pitch at every sample.
    """
    total = max(s + d for s, d, _ in strokes) + 0.5
    y = np.zeros(int(total * sr))
    for k, (start, dur, pitch) in enumerate(strokes):
        if isinstance(pitch, np.ndarray):
            curve = pitch
        else:
            curve = glide(pitch if isinstance(pitch, (list, tuple)) else [pitch], dur, sr)
        tone = stroke(curve, sr, seed=k)
        tone *= np.minimum(1.0, np.linspace(40, 0, len(tone)))  # damp at the end
        i = int(start * sr)
        y[i : i + len(tone)] += tone
    return (0.4 * y / np.abs(y).max()).astype(np.float32)


# A phrase in Yaman (Kalyan thaat: every note natural except tivra Ma) with
# Sa on C#, at 120 BPM: up and down the scale in half-matra notes, two
# meends, and fast strokes on Sa to finish.
S, R, G, M, P, D, N = (SA + d for d in (0, 2, 4, 6, 7, 9, 11))
RUN = [S, R, G, M, P, D, N, S + 12, N, D, P, M, G, R, S]
# What should be heard: (midi, reached by meend, fast strokes)
YAMAN = (
    [(m, False, False) for m in RUN]
    + [(R, False, False), (G, True, False)]  # Re pulled up to Ga
    + [(G, False, False), (P, True, False)]  # Ga pulled up through Ma to Pa
    + [(R, False, False), (S, False, True)]
)
YAMAN_MIDI = [m for m, _, _ in YAMAN]


def yaman_phrase():
    half = 0.25
    strokes = [(0.5 + i * half, half * 0.95, m) for i, m in enumerate(RUN)]
    t = 0.5 + len(RUN) * half
    strokes += [(t, 0.72, [R, G]), (t + 0.75, 0.72, [G, P]), (t + 1.5, 0.48, R)]
    t += 2.0
    strokes += [(t + i * 0.07, 0.065, S) for i in range(12)]  # diri
    return strokes


# A slower phrase in Bhairavi (every note komal that can be) on a sitar
# tuned to D and 35 cents sharp of it, with the things the left hand does:
# a slow meend down from komal Ni to Pa, andolan (a slow sway) on komal Ga,
# and Ma pulled up to Pa and let back down in one stroke.
BHAIRAVI_SA = 62.35
BHAIRAVI = "S r g m P d n S' n ~P g m ~P ~m g r S".split()


def bhairavi_phrase(sr: int = SR):
    def at(degree):
        return BHAIRAVI_SA + degree

    strokes = [(0.5 + i * 0.4, 0.38, at(d)) for i, d in enumerate([0, 1, 3, 5, 7, 8, 10, 12])]
    t = 0.5 + 8 * 0.4
    sway = at(3) + 0.3 * np.sin(2 * np.pi * 4 * np.arange(int(1.2 * sr)) / sr)
    strokes += [
        (t, 1.2, glide([at(10), at(7)], 1.2, sr, slide=0.35)),
        (t + 1.3, 1.2, sway),
        (t + 2.6, 1.2, glide([at(5), at(7), at(5)], 1.2, sr, slide=0.08)),
    ]
    t += 3.9
    strokes += [(t + i * 0.55, 0.5, at(d)) for i, d in enumerate([3, 1, 0])]
    return strokes


def singer(notes, total: float, sr: int = SR) -> np.ndarray:
    """A crude voice: sustained harmonic tones with vibrato.

    notes: list of (start_seconds, duration_seconds, midi).
    """
    y = np.zeros(int(total * sr))
    for start, dur, midi in notes:
        t = np.arange(int(dur * sr)) / sr
        freq = hz(midi) * (1 + 0.012 * np.sin(2 * np.pi * 5.5 * t))
        phase = 2 * np.pi * np.cumsum(freq) / sr
        tone = sum(np.sin(k * phase) / k for k in range(1, 6))
        tone *= np.minimum(1.0, np.minimum(t / 0.05, (dur - t) / 0.05))
        i = int(start * sr)
        y[i : i + len(tone)] += tone[: len(y) - i]
    return y


# A singer holding long notes over the sitar phrase, louder than it.
SINGER_NOTES = [(0.5, 1.2, 68), (1.8, 1.2, 64), (3.1, 1.6, 63), (4.9, 1.4, 67)]
