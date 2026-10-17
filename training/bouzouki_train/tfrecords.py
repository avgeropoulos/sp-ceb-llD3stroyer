"""tf.train.Example records in basic_pitch 0.4.0's training schema.

Same features as ``basic_pitch.data.tf_example_serialization._to_transcription_tfex`` (file_id,
source, audio_wav, {notes,onsets,contours}_{indices,values}, notes_onsets_shape, contours_shape,
the arrays stored with ``tf.io.serialize_tensor``), so ``basic_pitch.data.tf_example_deserialization``
reads them unchanged. (That module is not imported here because it needs the ``sox`` binary.)

Layout, as basic_pitch expects::

    <out>/<dataset_name>/splits/<train|validation|test>/<shard>.tfrecord
    <out>/<dataset_name>/metadata.jsonl      # one line per example: source ids, cents, augmentation
"""
import json
import os
from typing import Dict, Optional

import numpy as np


def _bytes(value: bytes):
    import tensorflow as tf

    return tf.train.Feature(bytes_list=tf.train.BytesList(value=[value]))


def make_example(file_id: str, source: str, wav: bytes, sparse: Dict[str, np.ndarray]):
    import tensorflow as tf

    def ser(name: str, dtype) -> bytes:
        arr = np.asarray(sparse[name], dtype)
        if name.endswith("_indices") and arr.size == 0:
            arr = arr.reshape(0, 2)
        return tf.io.serialize_tensor(arr).numpy()

    feats = {
        "file_id": _bytes(file_id.encode()),
        "source": _bytes(source.encode()),
        "audio_wav": _bytes(wav),
        "notes_indices": _bytes(ser("notes_indices", np.int64)),
        "notes_values": _bytes(ser("notes_values", np.float32)),
        "onsets_indices": _bytes(ser("onsets_indices", np.int64)),
        "onsets_values": _bytes(ser("onsets_values", np.float32)),
        "contours_indices": _bytes(ser("contours_indices", np.int64)),
        "contours_values": _bytes(ser("contours_values", np.float32)),
        "notes_onsets_shape": _bytes(ser("notes_onsets_shape", np.int64)),
        "contours_shape": _bytes(ser("contours_shape", np.int64)),
    }
    return tf.train.Example(features=tf.train.Features(feature=feats))


class ShardWriter:
    """Writes examples to ``<out>/<dataset>/splits/<split>/`` in shards of ``per_shard`` examples."""

    def __init__(self, out_dir: str, dataset: str, per_shard: int = 64):
        self.root = os.path.join(out_dir, dataset)
        self.per_shard = per_shard
        self._writers: Dict[str, object] = {}
        self._counts: Dict[str, int] = {}
        self._shards: Dict[str, int] = {}
        os.makedirs(self.root, exist_ok=True)
        self._meta = open(os.path.join(self.root, "metadata.jsonl"), "a")

    def write(self, split: str, example, metadata: Optional[dict] = None) -> None:
        import tensorflow as tf

        if self._counts.get(split, 0) % self.per_shard == 0:
            if split in self._writers:
                self._writers[split].close()
            shard = self._shards.get(split, 0)
            d = os.path.join(self.root, "splits", split)
            os.makedirs(d, exist_ok=True)
            # Never overwrite shards from an earlier run: continue numbering.
            while os.path.exists(os.path.join(d, f"{shard:05d}.tfrecord")):
                shard += 1
            self._writers[split] = tf.io.TFRecordWriter(os.path.join(d, f"{shard:05d}.tfrecord"))
            self._shards[split] = shard + 1
        self._writers[split].write(example.SerializeToString())
        self._counts[split] = self._counts.get(split, 0) + 1
        if metadata is not None:
            self._meta.write(json.dumps({"split": split, **metadata}) + "\n")

    def close(self) -> None:
        for w in self._writers.values():
            w.close()
        self._meta.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
