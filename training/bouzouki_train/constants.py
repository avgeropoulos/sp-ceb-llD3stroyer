"""Basic Pitch constants.

Imported from ``basic_pitch.constants`` (basic-pitch 0.4.0, Apache-2.0) when it is installed, so the
targets can never drift from the model's resolution. A verbatim fallback copy lets the light parts of
the pipeline (phrases, synth, targets) run without TensorFlow / basic-pitch installed; a test checks
that both agree.
"""
import importlib.util
import os

import numpy as np


def _load_basic_pitch_constants():
    """Loads basic_pitch/constants.py on its own: importing the ``basic_pitch`` package would import
    TensorFlow (its __init__ probes for every runtime), which the light steps do not need."""
    spec = importlib.util.find_spec("basic_pitch")
    if spec is None or not spec.submodule_search_locations:
        raise ImportError("basic_pitch not installed")
    path = os.path.join(list(spec.submodule_search_locations)[0], "constants.py")
    cspec = importlib.util.spec_from_file_location("_basic_pitch_constants", path)
    module = importlib.util.module_from_spec(cspec)
    cspec.loader.exec_module(module)
    return module


try:  # pragma: no cover - which branch runs depends on the environment
    _bp = _load_basic_pitch_constants()

    FFT_HOP = _bp.FFT_HOP
    AUDIO_SAMPLE_RATE = _bp.AUDIO_SAMPLE_RATE
    AUDIO_N_SAMPLES = _bp.AUDIO_N_SAMPLES
    AUDIO_WINDOW_LENGTH = _bp.AUDIO_WINDOW_LENGTH
    ANNOTATIONS_FPS = _bp.ANNOTATIONS_FPS
    ANNOTATION_HOP = _bp.ANNOTATION_HOP
    ANNOT_N_FRAMES = _bp.ANNOT_N_FRAMES
    ANNOTATIONS_BASE_FREQUENCY = _bp.ANNOTATIONS_BASE_FREQUENCY
    ANNOTATIONS_N_SEMITONES = _bp.ANNOTATIONS_N_SEMITONES
    CONTOURS_BINS_PER_SEMITONE = _bp.CONTOURS_BINS_PER_SEMITONE
    N_FREQ_BINS_NOTES = _bp.N_FREQ_BINS_NOTES
    N_FREQ_BINS_CONTOURS = _bp.N_FREQ_BINS_CONTOURS
    FREQ_BINS_NOTES = _bp.FREQ_BINS_NOTES
    FREQ_BINS_CONTOURS = _bp.FREQ_BINS_CONTOURS
    FROM_BASIC_PITCH = True
except Exception:  # basic-pitch not installed (or its import failed): same values, copied from 0.4.0
    FFT_HOP = 256
    AUDIO_SAMPLE_RATE = 22050
    AUDIO_WINDOW_LENGTH = 2
    AUDIO_N_SAMPLES = AUDIO_SAMPLE_RATE * AUDIO_WINDOW_LENGTH - FFT_HOP  # 43844
    ANNOTATIONS_FPS = AUDIO_SAMPLE_RATE // FFT_HOP  # 86 (note: not 22050/256 = 86.13)
    ANNOTATION_HOP = 1.0 / ANNOTATIONS_FPS
    ANNOT_N_FRAMES = ANNOTATIONS_FPS * AUDIO_WINDOW_LENGTH  # 172
    ANNOTATIONS_BASE_FREQUENCY = 27.5  # A0, MIDI 21
    ANNOTATIONS_N_SEMITONES = 88
    CONTOURS_BINS_PER_SEMITONE = 3
    N_FREQ_BINS_NOTES = ANNOTATIONS_N_SEMITONES
    N_FREQ_BINS_CONTOURS = ANNOTATIONS_N_SEMITONES * CONTOURS_BINS_PER_SEMITONE

    def _freq_bins(bins_per_semitone: int, base_frequency: float, n_semitones: int) -> np.ndarray:
        d = 2.0 ** (1.0 / (12 * bins_per_semitone))
        return base_frequency * d ** np.arange(bins_per_semitone * n_semitones)

    FREQ_BINS_NOTES = _freq_bins(1, ANNOTATIONS_BASE_FREQUENCY, ANNOTATIONS_N_SEMITONES)
    FREQ_BINS_CONTOURS = _freq_bins(CONTOURS_BINS_PER_SEMITONE, ANNOTATIONS_BASE_FREQUENCY, ANNOTATIONS_N_SEMITONES)
    FROM_BASIC_PITCH = False

#: MIDI number of note bin 0 (A0).
MIDI_OFFSET = 21

#: Input / output tensor names of the shipped icassp_2022 nmp.onnx, which the Android app looks up by
#: name (bouzouki-core .../song/BasicPitch.kt). An exported fine-tuned model must keep them.
ONNX_INPUT_NAME = "serving_default_input_2:0"
ONNX_OUTPUT_NAMES = {
    "contour": "StatefulPartitionedCall:0",
    "note": "StatefulPartitionedCall:1",
    "onset": "StatefulPartitionedCall:2",
}


def midi_to_hz(midi: float) -> float:
    """Equal-tempered frequency, A4 = 440 Hz (same as the app's midiToHz)."""
    return 440.0 * 2.0 ** ((midi - 69.0) / 12.0)
