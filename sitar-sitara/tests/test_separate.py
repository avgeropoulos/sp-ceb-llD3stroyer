from difflib import SequenceMatcher

import numpy as np
import pytest
from conftest import SOURCES, fake_apply
from synth import YAMAN_MIDI

from sitar_sitara import separate
from sitar_sitara.pipeline import Options, analyze

EXPECTED = YAMAN_MIDI


def _similarity(found):
    return SequenceMatcher(a=found, b=EXPECTED, autojunk=False).ratio()


def test_without_separation_the_singer_ends_up_in_the_notation(song):
    path, _ = song
    found = [n["midi"] for n in analyze(str(path), Options(separate=False))["notes"]]
    assert _similarity(found) < 0.8, found


def test_separation_drops_the_vocals_and_keeps_the_sitar(song, monkeypatch):
    path, vocals = song
    # A perfect separator: it knows exactly which part of the mix is the voice.
    monkeypatch.setattr(
        separate, "_apply", fake_apply(lambda wav: {"vocals": vocals, "other": wav - vocals})
    )
    found = [n["midi"] for n in analyze(str(path))["notes"]]
    assert found == EXPECTED


def test_guitar_and_other_stems_are_kept(song, monkeypatch):
    path, vocals = song
    rest = None

    def stems(wav):
        nonlocal rest
        rest = wav - vocals
        # Split the sitar across guitar and other, and put junk in the
        # stems that should be thrown away.
        return {"guitar": 0.5 * rest, "other": 0.5 * rest, "vocals": vocals,
                "drums": wav, "bass": wav, "piano": wav}

    monkeypatch.setattr(separate, "_apply", fake_apply(stems))
    found = [n["midi"] for n in analyze(str(path))["notes"]]
    assert found == EXPECTED


def test_missing_model_gives_a_clear_error(phrase_wav, monkeypatch):
    def broken():
        raise separate.SeparationUnavailable("Couldn't load the htdemucs_6s separation model")

    monkeypatch.setattr(separate, "_load_model", broken)
    with pytest.raises(ValueError, match="turn instrument separation off"):
        analyze(str(phrase_wav))


def test_real_demucs_network_runs_end_to_end(phrase_wav, monkeypatch):
    """Run the real Demucs code path on an untrained, tiny HTDemucs.

    The output is noise (no trained weights here), but this checks the
    shapes, sample rates and stem handling that a trained model goes through.
    """
    pytest.importorskip("torch")
    from demucs.htdemucs import HTDemucs

    model = HTDemucs(sources=SOURCES, channels=8, depth=4, t_layers=1, segment=4)
    model.eval()
    monkeypatch.setattr(separate, "_load_model", lambda: model)
    monkeypatch.setattr(separate, "_apply", _REAL_APPLY)

    y = separate.load_isolated(str(phrase_wav), 22050, start=0.5, end=2.5)
    assert y.dtype == np.float32
    assert abs(len(y) - 2 * 22050) <= 2
    assert np.isfinite(y).all()


_REAL_APPLY = separate._apply


def test_best_device_prefers_nvidia_then_apple_then_cpu(monkeypatch):
    import torch

    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)
    monkeypatch.setattr(torch.backends.mps, "is_available", lambda: True)
    assert separate.best_device() == "mps"
    monkeypatch.setattr(torch.cuda, "is_available", lambda: True)
    assert separate.best_device() == "cuda"
    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)
    monkeypatch.setattr(torch.backends.mps, "is_available", lambda: False)
    assert separate.best_device() == "cpu"


def test_mps_fallback_is_switched_on():
    import os

    import sitar_sitara  # noqa: F401

    assert os.environ.get("PYTORCH_ENABLE_MPS_FALLBACK") == "1"
