"""Fine-tune Basic Pitch (icassp_2022 / nmp) on bouzouki data and export .onnx / .tflite.

    python -m bouzouki_train.finetune --data data/tfrecords --val-real data/real_val --out runs/ft1
    python -m bouzouki_train.finetune --data data/tfrecords --out runs/dry --dry-run [--check-export]

* starts from the pretrained weights shipped in the basic-pitch 0.4.0 wheel (Apache-2.0), or
  ``--pretrained`` (a Keras SavedModel directory, e.g. an earlier run's ``best``);
* low learning rate (default 1e-4, Adam; basic-pitch trained from scratch at 1e-3), optional freezing
  of the early layers (``--freeze frontend``: CQT batch-norm; ``--freeze contour``: also the contour
  branch, so only the note/onset heads learn);
* basic_pitch's own losses (``basic_pitch.models.loss``, label smoothing 0.2, optional weighted onset
  loss) and its own TFRecord reader (``basic_pitch.data.tf_example_deserialization``);
* validation on HELD-OUT REAL recordings (``--val-real``, raw files, see real_data.py), cut into
  consecutive 2 s windows; the synthetic validation split is used when no real set is given;
* exports SavedModel + .onnx + .tflite with the same tensor names as the shipped nmp.onnx (the
  Android app looks them up by name), and checks the exports against Keras.

REFUSES TO START unless training/DATA_LICENSES.yaml approves every data source (license_gate.py).
``--dry-run`` skips that approval (never the forbidden-source scan), builds the datasets, checks one
batch's shapes against the model, runs one forward pass and the losses, and does NOT train.
"""
import argparse
import glob
import json
import logging
import os
import subprocess
import sys
import time
from typing import Dict, List, Optional, Tuple

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

from .constants import (  # noqa: E402
    ANNOT_N_FRAMES,
    ANNOTATIONS_FPS,
    AUDIO_N_SAMPLES,
    AUDIO_SAMPLE_RATE,
    AUDIO_WINDOW_LENGTH,
    N_FREQ_BINS_CONTOURS,
    N_FREQ_BINS_NOTES,
    ONNX_INPUT_NAME,
    ONNX_OUTPUT_NAMES,
)
from .license_gate import DEFAULT_YAML, LicenseGateError, check, dataset_source_ids  # noqa: E402

log = logging.getLogger("finetune")

#: --freeze groups: every layer up to and including this one (in the model's layer order) is frozen.
FREEZE_UNTIL = {"none": None, "frontend": "harmonic_stacking", "contour": "contours-reduced"}
PRETRAINED_SOURCE_ID = "basic_pitch_icassp_2022"
REAL_VAL_SOURCE_ID = "owner_recordings_validation"


def default_pretrained() -> str:
    import importlib.util

    spec = importlib.util.find_spec("basic_pitch")
    if spec is None:
        raise SystemExit("basic-pitch is not installed: pip install --no-deps basic-pitch==0.4.0")
    return os.path.join(list(spec.submodule_search_locations)[0], "saved_models", "icassp_2022", "nmp")


# ------------------------------------------------------------------------------------- data
def tfrecord_files(base: str, split: str, datasets: Optional[List[str]] = None) -> List[str]:
    names = datasets or sorted(d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d)))
    files = []
    for name in names:
        files += sorted(glob.glob(os.path.join(base, name, "splits", split, "*tfrecord")))
    return files


def record_dataset(files: List[str], windows_per_track: int, shuffle: bool, seed: int = 0):
    """basic_pitch's own reader: decode, densify the sparse targets, cut random 2 s windows, drop
    silent windows. Yields (audio (43844,1), {onset, contour, note}, {weights})."""
    import tensorflow as tf
    from basic_pitch.data.tf_example_deserialization import transcription_dataset

    rng = np.random.default_rng(seed)

    def gen():
        order = list(files)
        if shuffle:
            rng.shuffle(order)
        yield from order

    ds = transcription_dataset(gen, windows_per_track, random_seed=not shuffle)
    return ds.map(lambda x, y, w: (x, y), num_parallel_calls=tf.data.AUTOTUNE)


def real_windows(real_dir: str) -> Tuple[np.ndarray, Dict[str, np.ndarray], List[str]]:
    """Held-out real recordings as consecutive, non-overlapping 2 s windows (deterministic), cut the
    same way as basic_pitch's extract_window (audio from round(sr*t), frames from round(fps*t))."""
    from .real_data import load_real_dir
    from .targets import build_sparse_targets, to_dense

    xs, ys, ids = [], {"onset": [], "contour": [], "note": []}, []
    for rec in load_real_dir(real_dir):
        dur = len(rec.audio) / AUDIO_SAMPLE_RATE
        dense = to_dense(build_sparse_targets(rec.instrument, rec.notes, dur))
        n_win = max(1, int(np.ceil(dur / AUDIO_WINDOW_LENGTH)))
        for k in range(n_win):
            t = k * AUDIO_WINDOW_LENGTH
            a0, f0 = int(round(AUDIO_SAMPLE_RATE * t)), int(round(ANNOTATIONS_FPS * t))
            a = np.zeros(AUDIO_N_SAMPLES, np.float32)
            seg = rec.audio[a0 : a0 + AUDIO_N_SAMPLES]
            a[: len(seg)] = seg
            xs.append(a[:, None])
            for key in ys:
                m = np.zeros((ANNOT_N_FRAMES, dense[key].shape[1]), np.float32)
                seg = dense[key][f0 : f0 + ANNOT_N_FRAMES]
                m[: len(seg)] = seg
                ys[key].append(m)
            ids.append(f"{rec.rec_id}@{t:.0f}s")
    if not xs:
        raise SystemExit(f"no labelled recordings found in {real_dir}")
    return np.stack(xs), {k: np.stack(v) for k, v in ys.items()}, ids


def frame_f1(y_true: np.ndarray, y_pred: np.ndarray, threshold: float = 0.5) -> float:
    t, p = y_true > 0.5, y_pred > threshold
    tp = np.logical_and(t, p).sum()
    denom = t.sum() + p.sum()
    return float(2 * tp / denom) if denom else 1.0


# ------------------------------------------------------------------------------------ model
def load_model(path: str, freeze: str):
    import tensorflow as tf

    model = tf.keras.models.load_model(path, compile=False)
    until = FREEZE_UNTIL[freeze]
    names = [l.name for l in model.layers]
    if until is not None:
        if until not in names:
            raise SystemExit(f"--freeze {freeze}: layer {until!r} not in model; layers: {names}")
        for layer in model.layers:
            layer.trainable = False
            if layer.name == until:
                break
    return model


def compile_model(model, lr: float, label_smoothing: float, weighted_onset: bool, positive_onset_weight: float):
    import tensorflow as tf
    from basic_pitch import models as bp_models

    model.compile(
        loss=bp_models.loss(label_smoothing=label_smoothing, weighted=weighted_onset, positive_weight=positive_onset_weight),
        optimizer=tf.keras.optimizers.Adam(lr),
    )


def param_counts(model) -> Tuple[int, int]:
    tr = int(sum(np.prod(w.shape) for w in model.trainable_weights))
    fr = int(sum(np.prod(w.shape) for w in model.non_trainable_weights))
    return tr, fr


# ----------------------------------------------------------------------------------- export
def _rename_onnx_io(path: str, mapping: Dict[str, str]) -> None:
    """Renames graph inputs/outputs (and every node reference to them) per ``mapping``."""
    import onnx

    m = onnx.load(path)
    g = m.graph
    mapping = {k: v for k, v in mapping.items() if k != v and any(t.name == k for t in list(g.input) + list(g.output))}
    if not mapping:
        return
    for t in list(g.input) + list(g.output) + list(g.value_info):
        if t.name in mapping:
            t.name = mapping[t.name]
    for node in g.node:
        for i, name in enumerate(node.input):
            if name in mapping:
                node.input[i] = mapping[name]
        for i, name in enumerate(node.output):
            if name in mapping:
                node.output[i] = mapping[name]
    onnx.checker.check_model(m)
    onnx.save(m, path)


def export(model, out_dir: str, opset: int = 15) -> Dict[str, str]:
    """SavedModel + nmp.onnx (tf2onnx) + nmp.tflite, with the shipped model's tensor names; each
    export is run on random audio and compared with Keras."""
    import tensorflow as tf

    os.makedirs(out_dir, exist_ok=True)
    saved = os.path.join(out_dir, "saved_model")
    model.save(saved, include_optimizer=False)  # TF 2.15 / Keras 2: a directory -> SavedModel
    paths = {"saved_model": saved}

    x = np.random.default_rng(0).uniform(-0.5, 0.5, (1, AUDIO_N_SAMPLES, 1)).astype(np.float32)
    ref = {k: v.numpy() for k, v in model(x, training=False).items()}

    # TFLite: builtin ops only (the app has no Flex delegate).
    conv = tf.lite.TFLiteConverter.from_saved_model(saved)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tfl = os.path.join(out_dir, "nmp.tflite")
    with open(tfl, "wb") as f:
        f.write(conv.convert())
    interp = tf.lite.Interpreter(model_path=tfl)
    interp.allocate_tensors()
    interp.set_tensor(interp.get_input_details()[0]["index"], x)
    interp.invoke()
    tfl_names = {d["name"]: d["index"] for d in interp.get_output_details()}
    tfl_out = {key: interp.get_tensor(tfl_names[name]) for key, name in ONNX_OUTPUT_NAMES.items() if name in tfl_names}
    if len(tfl_out) != len(ONNX_OUTPUT_NAMES):
        raise RuntimeError(f"exported TFLite output names {sorted(tfl_names)} differ from {sorted(ONNX_OUTPUT_NAMES.values())}")
    paths["tflite"] = tfl

    # ONNX via the tf2onnx command line, the way the shipped nmp.onnx was made (tf2onnx 1.15, opset 15).
    onx = os.path.join(out_dir, "nmp.onnx")
    subprocess.run([sys.executable, "-m", "tf2onnx.convert", "--saved-model", saved, "--output", onx, "--opset", str(opset)],
                   check=True, capture_output=True)
    # tf2onnx >= 1.16 names the tensors after the signature keys (input_2 / contour, note, onset);
    # 1.15 kept the graph names the app uses. Rename to the app's names.
    _rename_onnx_io(onx, {"input_2": ONNX_INPUT_NAME, **ONNX_OUTPUT_NAMES})
    import onnxruntime as ort

    sess = ort.InferenceSession(onx, providers=["CPUExecutionProvider"])
    in_names = [i.name for i in sess.get_inputs()]
    out_names = [o.name for o in sess.get_outputs()]
    if in_names != [ONNX_INPUT_NAME] or sorted(out_names) != sorted(ONNX_OUTPUT_NAMES.values()):
        raise RuntimeError(f"exported ONNX names {in_names} -> {out_names} differ from the app's "
                           f"{ONNX_INPUT_NAME} -> {sorted(ONNX_OUTPUT_NAMES.values())}")
    res = dict(zip(out_names, sess.run(out_names, {ONNX_INPUT_NAME: x})))
    paths["onnx"] = onx

    report = {}
    for key, name in ONNX_OUTPUT_NAMES.items():
        report[f"onnx_{key}_max_abs_diff"] = float(np.abs(res[name] - ref[key]).max())
        if key in tfl_out:
            report[f"tflite_{key}_max_abs_diff"] = float(np.abs(tfl_out[key] - ref[key]).max())
    with open(os.path.join(out_dir, "export_check.json"), "w") as f:
        json.dump(report, f, indent=2)
    log.info("export check (max |export - keras|): %s", report)
    worst = max(report.values())
    if worst > 1e-3:
        raise RuntimeError(f"exported model differs from Keras by {worst}")
    return paths


# ------------------------------------------------------------------------------------- main
def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="TFRecord base dir (build_dataset.py --out)")
    ap.add_argument("--datasets", nargs="*", help="dataset sub-directories to use (default: all)")
    ap.add_argument("--val-real", help="held-out REAL recordings dir (raw, see real_data.py)")
    ap.add_argument("--out", required=True, help="run directory")
    ap.add_argument("--pretrained", help="Keras SavedModel to start from (default: basic-pitch's icassp_2022 nmp)")
    ap.add_argument("--pretrained-source-id", default=PRETRAINED_SOURCE_ID)
    ap.add_argument("--licenses", default=DEFAULT_YAML)
    ap.add_argument("--freeze", choices=sorted(FREEZE_UNTIL), default="frontend")
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--epochs", type=int, default=50)
    ap.add_argument("--steps-per-epoch", type=int, default=200)
    ap.add_argument("--batch-size", type=int, default=16)
    ap.add_argument("--windows-per-track", type=int, default=8, help="random 2 s windows drawn per record")
    ap.add_argument("--shuffle-size", type=int, default=512)
    ap.add_argument("--validation-steps", type=int, default=20, help="batches of synthetic validation (no --val-real)")
    ap.add_argument("--label-smoothing", type=float, default=0.2)
    ap.add_argument("--weighted-onset-loss", action="store_true")
    ap.add_argument("--positive-onset-weight", type=float, default=0.5)
    ap.add_argument("--patience", type=int, default=8)
    ap.add_argument("--no-export", action="store_true")
    ap.add_argument("--dry-run", action="store_true", help="build data + one batch shape check, no training")
    ap.add_argument("--check-export", action="store_true", help="with --dry-run: also export the loaded model")
    ap.add_argument("--seed", type=int, default=0)
    return ap.parse_args(argv)


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    a = parse_args(argv)
    pretrained = a.pretrained or default_pretrained()

    # 1. License gate (approval skipped only for --dry-run; the forbidden scan always runs).
    used = set(dataset_source_ids(a.data)) | {a.pretrained_source_id}
    if a.val_real:
        used.add(REAL_VAL_SOURCE_ID)
    try:
        for note in check(a.licenses, used, [a.data, a.val_real, pretrained], require_approval=not a.dry_run):
            log.info(note)
    except LicenseGateError as e:
        log.error("%s", e)
        log.error("refusing to start: fix %s (or use --dry-run to only check shapes)", a.licenses)
        return 2

    import tensorflow as tf

    tf.random.set_seed(a.seed)
    os.makedirs(a.out, exist_ok=True)

    # 2. Data.
    train_files = tfrecord_files(a.data, "train", a.datasets)
    val_files = tfrecord_files(a.data, "validation", a.datasets)
    if not train_files:
        log.error("no training records under %s/*/splits/train", a.data)
        return 1
    log.info("%d train / %d validation record files", len(train_files), len(val_files))
    train_ds = (record_dataset(train_files, a.windows_per_track, shuffle=True, seed=a.seed)
                .shuffle(a.shuffle_size).repeat().batch(a.batch_size).prefetch(tf.data.AUTOTUNE))
    real_val = None
    if a.val_real:
        vx, vy, vids = real_windows(a.val_real)
        log.info("real validation: %d windows from %s", len(vids), a.val_real)
        real_val = (vx, vy)
        val_ds = tf.data.Dataset.from_tensor_slices((vx, vy)).batch(a.batch_size)
        val_steps = None
    elif val_files:
        val_ds = (record_dataset(val_files, a.windows_per_track, shuffle=False)
                  .repeat().batch(a.batch_size).take(a.validation_steps).cache().repeat())
        val_steps = a.validation_steps
    else:
        val_ds, val_steps = None, None
        log.warning("no validation data: pass --val-real (recommended) or build a synthetic validation split")

    # 3. Model.
    model = load_model(pretrained, a.freeze)
    compile_model(model, a.lr, a.label_smoothing, a.weighted_onset_loss, a.positive_onset_weight)
    tr, fr = param_counts(model)
    log.info("model %s: input %s, outputs %s; %d trainable / %d frozen params (--freeze %s)",
             pretrained, model.input_shape, model.output_shape, tr, fr, a.freeze)

    if a.dry_run:
        return dry_run(a, model, train_ds, val_ds)

    # 4. Train.
    run = os.path.join(a.out, time.strftime("%Y%m%d-%H%M%S"))
    best = os.path.join(run, "best")
    callbacks = [
        tf.keras.callbacks.TensorBoard(log_dir=os.path.join(run, "tensorboard")),
        tf.keras.callbacks.ModelCheckpoint(best, save_best_only=val_ds is not None, monitor="val_loss" if val_ds is not None else "loss"),
        tf.keras.callbacks.ReduceLROnPlateau(monitor="val_loss" if val_ds is not None else "loss", factor=0.5, patience=max(1, a.patience // 2), verbose=1),
        tf.keras.callbacks.EarlyStopping(monitor="val_loss" if val_ds is not None else "loss", patience=a.patience, verbose=1),
        tf.keras.callbacks.CSVLogger(os.path.join(run, "history.csv")),
    ]
    if real_val is not None:
        callbacks.insert(0, RealF1Callback(*real_val, batch_size=a.batch_size))
    with open(os.path.join(run, "args.json"), "w") as f:
        json.dump(vars(a) | {"pretrained": pretrained, "sources": sorted(used)}, f, indent=2)
    model.fit(train_ds, epochs=a.epochs, steps_per_epoch=a.steps_per_epoch, validation_data=val_ds,
              validation_steps=val_steps, callbacks=callbacks)

    # 5. Export the best checkpoint.
    if not a.no_export:
        final = tf.keras.models.load_model(best, compile=False)
        paths = export(final, os.path.join(run, "export"))
        log.info("exported: %s", paths)
    return 0


def _make_real_f1_callback():
    import tensorflow as tf

    class RealF1(tf.keras.callbacks.Callback):
        """Frame-level note F1 (threshold 0.5) on the real validation windows, logged as val_real_note_f1."""

        def __init__(self, x, y, batch_size):
            super().__init__()
            self.x, self.y, self.bs = x, y, batch_size

        def on_epoch_end(self, epoch, logs=None):
            pred = self.model.predict(self.x, batch_size=self.bs, verbose=0)
            f1 = frame_f1(self.y["note"], pred["note"])
            if logs is not None:
                logs["val_real_note_f1"] = f1
            log.info("epoch %d: real validation note-frame F1 %.4f", epoch + 1, f1)

    return RealF1


def RealF1Callback(x, y, batch_size):  # noqa: N802 - factory so TF is imported lazily
    return _make_real_f1_callback()(x, y, batch_size)


def dry_run(a, model, train_ds, val_ds) -> int:
    """One batch: shapes against the model, a forward pass and the losses. No weights change."""
    import tensorflow as tf
    from basic_pitch import models as bp_models

    x, y = next(iter(train_ds))
    expect_in = (a.batch_size, AUDIO_N_SAMPLES, 1)
    expect_out = {"note": (ANNOT_N_FRAMES, N_FREQ_BINS_NOTES), "onset": (ANNOT_N_FRAMES, N_FREQ_BINS_NOTES),
                  "contour": (ANNOT_N_FRAMES, N_FREQ_BINS_CONTOURS)}
    problems = []
    if tuple(x.shape) != expect_in:
        problems.append(f"audio batch {tuple(x.shape)} != {expect_in}")
    if tuple(model.input_shape[1:]) != expect_in[1:]:
        problems.append(f"model input {model.input_shape} != (None, {AUDIO_N_SAMPLES}, 1)")
    for k, shp in expect_out.items():
        if tuple(y[k].shape[1:]) != shp:
            problems.append(f"target {k} {tuple(y[k].shape)} != (batch, {shp})")
        if tuple(model.output_shape[k][1:]) != shp:
            problems.append(f"model output {k} {model.output_shape[k]} != (None, {shp})")
    pred = model(x, training=False)
    losses = bp_models.loss(label_smoothing=a.label_smoothing)
    loss_vals = {k: float(tf.reduce_mean(losses[k](y[k], pred[k]))) for k in expect_out}
    stats = {k: {"shape": list(y[k].shape), "positives": int(tf.reduce_sum(tf.cast(y[k] > 0.5, tf.int32)))} for k in expect_out}
    report = {"audio_batch": list(x.shape), "targets": stats, "losses_pretrained": loss_vals,
              "pred_shapes": {k: list(v.shape) for k, v in pred.items()}, "problems": problems}
    if val_ds is not None:
        vx, vy = next(iter(val_ds))
        report["validation_batch"] = {"audio": list(vx.shape), **{k: list(v.shape) for k, v in vy.items()}}
    if a.check_export:
        report["export"] = export(model, os.path.join(a.out, "export_check"))
    with open(os.path.join(a.out, "dry_run.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))
    if problems:
        log.error("dry run found shape problems: %s", problems)
        return 1
    log.info("dry run OK (no training was run)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
