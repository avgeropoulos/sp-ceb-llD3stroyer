"""Strip vocals, drums, bass and piano out of a mix with Demucs.

By default this uses htdemucs_6s, which splits a song into drums, bass,
vocals, piano, guitar and "other". It has no sitar stem: a sitar lands in
"guitar", "other" or both, so we keep those two and throw the rest away.
The tabla mostly goes out with the drums. The tanpura's drone stays in.
"""

from __future__ import annotations

import threading

import librosa
import numpy as np

MODEL_NAME = "htdemucs_6s"
KEEP = ("guitar", "other")

_model = None
_model_lock = threading.Lock()


class SeparationUnavailable(RuntimeError):
    pass


def available() -> bool:
    try:
        import demucs  # noqa: F401
        import torch  # noqa: F401
    except ImportError:
        return False
    return True


def _load_model():
    global _model
    with _model_lock:
        if _model is None:
            try:
                from demucs.pretrained import get_model
            except ImportError as e:
                raise SeparationUnavailable(
                    "Instrument separation needs Demucs: pip install demucs"
                ) from e
            try:
                # Downloads the weights (~80 MB) the first time.
                _model = get_model(MODEL_NAME)
            except Exception as e:
                raise SeparationUnavailable(
                    f"Couldn't load the {MODEL_NAME} separation model: {e}"
                ) from e
            _model.eval()
        return _model


def best_device() -> str:
    """An NVIDIA GPU if there is one, else an Apple Silicon GPU, else the CPU."""
    import torch

    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _apply(model, wav: np.ndarray) -> np.ndarray:
    """Run the model on a (channels, samples) mix; returns (stems, channels, samples)."""
    import torch
    from demucs.apply import apply_model

    device = best_device()
    mix = torch.from_numpy(wav)
    ref = mix.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    with torch.no_grad():
        out = apply_model(model, ((mix - mean) / std)[None], device=device, split=True,
                          overlap=0.25, progress=False)[0]
    return (out * std + mean).cpu().numpy()


def load_isolated(path: str, sr: int, start: float = 0.0,
                  end: float | None = None) -> np.ndarray:
    """Load a file and return just its guitar-like instruments, mono at sr."""
    model = _load_model()
    duration = None if end is None else max(0.0, end - start)
    wav, _ = librosa.load(path, sr=model.samplerate, mono=False, offset=start,
                          duration=duration)
    wav = np.atleast_2d(wav).astype(np.float32)
    if wav.shape[0] < model.audio_channels:
        wav = np.repeat(wav, model.audio_channels, axis=0)
    stems = dict(zip(model.sources, _apply(model, wav)))
    kept = sum(stems[name] for name in KEEP if name in stems)
    mono = np.asarray(kept).mean(axis=0)
    return librosa.resample(mono, orig_sr=model.samplerate, target_sr=sr).astype(np.float32)
