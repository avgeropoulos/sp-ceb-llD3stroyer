#!/usr/bin/env python3
"""
Times an exported Demucs graph on this machine: seconds per second of audio and peak memory.
One model per process, so ru_maxrss is that model's peak.

  bench.py MODEL.onnx [--seconds 60] [--threads 4] [--no-arena]
"""
import argparse
import os
import resource
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_onnx import SAMPLE_RATE, separate, test_track  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument("model")
    p.add_argument("--seconds", type=float, default=60)
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--no-arena", action="store_true", help="disable ORT's CPU memory arena")
    a = p.parse_args()
    import onnxruntime as ort
    wav = test_track(a.seconds)
    base = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    o = ort.SessionOptions()
    o.intra_op_num_threads = a.threads
    if a.no_arena:
        o.enable_cpu_mem_arena = False
    t0 = time.time()
    sess = ort.InferenceSession(a.model, o, providers=["CPUExecutionProvider"])
    load = time.time() - t0
    t0 = time.time()
    stems = separate(sess, wav)
    dt = time.time() - t0
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    assert np.isfinite(stems).all()
    print(f"{os.path.basename(a.model)}: {os.path.getsize(a.model) / 1e6:.1f} MB, load {load:.1f} s, "
          f"{dt / a.seconds:.3f} s per audio second ({a.threads} threads{', no arena' if a.no_arena else ''}), "
          f"peak RSS {peak / 1024:.0f} MB (before session {base / 1024:.0f} MB)")


if __name__ == "__main__":
    main()
