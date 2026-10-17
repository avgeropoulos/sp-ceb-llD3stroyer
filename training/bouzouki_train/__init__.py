"""Bouzouki fine-tuning pipeline for Spotify's Basic Pitch (icassp_2022 / nmp model).

Steps (see training/README.md):
    phrases        -> synthetic MIDI phrases (dromoi, tremolo, chromatic runs, every fret ...)
    synth          -> MIDI -> bouzouki-like audio (Karplus-Strong, or a pluggable sample/SoundFont renderer)
    augment        -> audiomentations chain + consistent +-50 cent detune
    targets        -> Basic Pitch note / onset / contour targets (mirdata-compatible sparse indices)
    tfrecords      -> tf.train.Example records in basic_pitch's exact training schema
    build_dataset  -> CLI tying the above together, also for the owner's own recordings
    license_gate   -> DATA_LICENSES.yaml gate + forbidden-source scan
    finetune       -> fine-tune pretrained weights, validate on real recordings, export .onnx/.tflite
"""
