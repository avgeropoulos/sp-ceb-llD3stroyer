"""WAV input/output with scipy only (BSD), resampled to Basic Pitch's 22050 Hz mono.

soundfile/libsndfile is deliberately not needed here (libsndfile is LGPL); record and convert the
owner's takes to WAV (PCM 16/24/32-bit or float).
"""
import io
from math import gcd

import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

from .constants import AUDIO_SAMPLE_RATE


def to_float(x: np.ndarray) -> np.ndarray:
    if x.dtype == np.uint8:
        return (x.astype(np.float32) - 128.0) / 128.0
    if np.issubdtype(x.dtype, np.integer):
        return x.astype(np.float32) / float(np.iinfo(x.dtype).max)
    return x.astype(np.float32)


def resample(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out:
        return x
    g = gcd(int(sr_in), int(sr_out))
    return resample_poly(x, sr_out // g, sr_in // g).astype(np.float32)


def read_wav(path: str, sr: int = AUDIO_SAMPLE_RATE) -> np.ndarray:
    """Mono float32 at ``sr``."""
    sr_in, x = wavfile.read(path)
    x = to_float(x)
    if x.ndim == 2:
        x = x.mean(axis=1)
    return resample(x, sr_in, sr)


def pcm16(x: np.ndarray) -> np.ndarray:
    return (np.clip(x, -1.0, 1.0) * 32767.0).round().astype(np.int16)


def write_wav(path: str, x: np.ndarray, sr: int = AUDIO_SAMPLE_RATE) -> None:
    wavfile.write(path, sr, pcm16(x))


def wav_bytes(x: np.ndarray, sr: int = AUDIO_SAMPLE_RATE) -> bytes:
    """16-bit PCM mono WAV bytes, the encoding tf.audio.decode_wav reads in basic_pitch's loader."""
    buf = io.BytesIO()
    wavfile.write(buf, sr, pcm16(x))
    return buf.getvalue()
