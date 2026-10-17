#!/usr/bin/env python3
"""
Exports Demucs v4 (HTDemucs, MIT licence) to ONNX for the bouzouki app's Listen mode.

torch.stft / complex tensors do not export well, so the graph holds only the network:
the caller does the spectrogram (HTDemucs._spec + _magnitude) before it and the inverse
spectrogram (HTDemucs._mask + _ispec) plus the sum of both branches after it. Numpy versions
of those steps are in this file; the Kotlin app (bouzouki-core song/Demucs.kt) mirrors them.

Graph interface, for one segment of SEGMENT = 343980 samples (7.8 s at 44.1 kHz, the
training length of htdemucs / htdemucs_6s) and FRAMES = ceil(SEGMENT / 1024) = 336:

  inputs   spec  float32 [1, 4, 2048, 336]   CaC spectrogram of the mix: channels are
                                             (left re, left im, right re, right im)
           mix   float32 [1, 2, 343980]      the mix itself
  outputs  time  float32 [1, S, 2, 343980]   time-branch output per source, de-normalised
           zout  float32 [1, S, 4, 2048, 336] spectral-branch output (CaC, same channel
                                             order as spec), de-normalised

  stem = time + istft(zout)

Usage:
  export_onnx.py --model htdemucs_6s --out htdemucs_6s.onnx            # pretrained (downloads)
  export_onnx.py --model htdemucs_6s --weights 5c90dfd2-34c22ccb.th ...  # local checkpoint
  export_onnx.py --model htdemucs_6s --random --ref-dir DIR ...         # random weights, offline
  add --fp16 for a float16 graph (float32 inputs/outputs), --validate to compare against torch.
"""
import argparse
import copy
import math
import os
import sys
import time
from fractions import Fraction

import numpy as np
import torch
from torch import nn

from demucs.htdemucs import HTDemucs

SAMPLE_RATE = 44100
NFFT = 4096
HOP = 1024
SEGMENT = int(Fraction(39, 5) * SAMPLE_RATE)  # 343980
FRAMES = math.ceil(SEGMENT / HOP)  # 336
SOURCES = {
    "htdemucs": ["drums", "bass", "other", "vocals"],
    "htdemucs_6s": ["drums", "bass", "other", "vocals", "guitar", "piano"],
}


def random_model(name, seed=0):
    """
    An untrained HTDemucs with (what we believe are) the released hyperparameters.
    The real kwargs live in the checkpoint and are used whenever weights are loaded;
    these only matter for offline testing. They come from demucs' conf/config.yaml defaults
    plus the grids/mmi.py overrides (depth 4, dconv_mode 3, t_layers 5), with
    bottom_channels=512: that gives 41.99 M parameters, which matches the ~84 MB float16
    checkpoint of htdemucs. Segment 39/5 s is the released models' training segment.
    """
    torch.manual_seed(seed)
    return HTDemucs(SOURCES[name], channels=48, depth=4, dconv_mode=3, t_layers=5,
                    bottom_channels=512, t_dropout=0.02, segment=Fraction(39, 5),
                    samplerate=SAMPLE_RATE).eval()


def load_model(name, weights):
    if weights:
        from demucs.states import load_model as load
        model = load(weights)
    else:
        from demucs.pretrained import get_model
        model = get_model(name)
        if hasattr(model, "models"):  # a BagOfModels of one
            assert len(model.models) == 1, "only single-model bags are supported"
            model = model.models[0]
    print("model kwargs:", model._init_args_kwargs, file=sys.stderr)
    assert isinstance(model, HTDemucs) and model.cac and model.nfft == NFFT
    assert int(model.segment * model.samplerate) == SEGMENT, model.segment
    return model.eval()


class Core(nn.Module):
    """HTDemucs.forward between _magnitude and _mask, for one full segment."""

    def __init__(self, m: HTDemucs):
        super().__init__()
        self.m = m
        self.half_net = False

    def forward(self, spec, mix):
        m = self.m
        x = spec
        B, C, Fq, T = x.shape
        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)
        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)
        if self.half_net:
            x, xt = x.half(), xt.half()

        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)
        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_upsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_upsampler_t(xt)
            x, xt = m.crosstransformer(x, xt)
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_downsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_downsampler_t(xt)
        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)
        S = len(m.sources)
        x, xt = x.float(), xt.float()
        zout = x.view(B, S, -1, Fq, T) * std[:, None] + mean[:, None]
        xt = xt.view(B, S, -1, mix.shape[-1]) * stdt[:, None] + meant[:, None]
        return xt, zout


# ---- numpy STFT / iSTFT, exactly HTDemucs._spec / _ispec (and what Demucs.kt does) ----

def hann():
    return (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / NFFT)).astype(np.float64)


def spec(mix):
    """
    [C, L] -> CaC spectrogram [2C, 2048, ceil(L/1024)]. _spec reflect-pads by 1536 on the left
    and 1536 + (frames*1024 - L) on the right, takes a centred normalised STFT, drops the
    Nyquist bin and the two frames at each end. Net effect: frame t is the window over samples
    [t*1024 - 1536, t*1024 + 2560) with a single reflection at the edges, scaled by 1/64.
    """
    C, L = mix.shape
    le = math.ceil(L / HOP)
    idx = np.arange(le)[:, None] * HOP - 3 * HOP // 2 + np.arange(NFFT)[None, :]
    idx = np.where(idx < 0, -idx, idx)
    idx = np.where(idx >= L, 2 * (L - 1) - idx, idx)
    frames = mix.astype(np.float64)[:, idx] * hann()  # C, le, NFFT
    z = np.fft.rfft(frames, axis=-1)[..., :NFFT // 2] / math.sqrt(NFFT)  # C, le, 2048
    z = z.transpose(0, 2, 1)
    out = np.stack([z.real, z.imag], axis=1).reshape(2 * C, NFFT // 2, le)
    return out.astype(np.float32)


def ispec(zout, length):
    """
    CaC [S, 2C, 2048, T] -> waveforms [S, C, length]: _mask + _ispec. Nyquist bin set to 0,
    the imaginary DC part ignored (irfft), frames overlap-added at t*1024 - 1536, divided by
    the squared-window sum of the padded frame sequence (constant 1.5 inside), times 64.
    """
    S, C2, Fr, T = zout.shape
    z = zout.astype(np.float64).reshape(S, C2 // 2, 2, Fr, T)
    z = z[:, :, 0] + 1j * z[:, :, 1]  # S, C, Fr, T
    z = np.concatenate([z, np.zeros_like(z[:, :, :1])], axis=2).transpose(0, 1, 3, 2)
    frames = np.fft.irfft(z, n=NFFT, axis=-1) * math.sqrt(NFFT) * hann()  # S, C, T, NFFT
    w2 = hann() ** 2
    base = 3 * HOP // 2 + 2 * HOP  # leading padding frames make the buffer start earlier
    total = (T + 3) * HOP + NFFT
    ola = np.zeros(z.shape[:2] + (total,))
    env = np.zeros(total)
    for f in range(T + 4):
        env[f * HOP: f * HOP + NFFT] += w2
    for t in range(T):
        ola[..., (t + 2) * HOP:(t + 2) * HOP + NFFT] += frames[:, :, t]
    out = ola / np.where(env > 1e-11, env, 1.0)
    return out[..., base: base + length].astype(np.float32)


def run_segment(sess, seg):
    """One segment [2, SEGMENT] through the ONNX graph -> stems [S, 2, SEGMENT]."""
    s = spec(seg)[None]
    t, z = sess.run(["time", "zout"], {"spec": s, "mix": seg[None].astype(np.float32)})
    return t[0] + ispec(z[0], seg.shape[-1])


def separate(sess, wav, overlap=0.25):
    """
    A whole track [2, N] -> stems [S, 2, N]: demucs.api.Separator.separate_tensor with
    shifts=0, i.e. track normalisation plus demucs.apply.apply_model's overlapping segments
    with triangular transition weights. Short segments get centred context padding.
    """
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std(ddof=1) + 1e-8
    x = (wav - mean) / std
    n = x.shape[-1]
    stride = int((1 - overlap) * SEGMENT)
    half = SEGMENT // 2
    weight = np.concatenate([np.arange(1, half + 1), np.arange(SEGMENT - half, 0, -1)]).astype(np.float32)
    weight /= weight.max()
    out, total = None, np.zeros(n, np.float32)
    for off in range(0, n, stride):
        length = min(SEGMENT, n - off)
        start = off - (SEGMENT - length) // 2
        seg = np.zeros((2, SEGMENT), np.float32)
        a, b = max(0, start), min(n, start + SEGMENT)
        seg[:, a - start:b - start] = x[:, a:b]
        y = run_segment(sess, seg)
        d = SEGMENT - length
        y = y[..., d // 2: d // 2 + length]
        if out is None:
            out = np.zeros((y.shape[0], 2, n), np.float32)
        out[..., off:off + length] += weight[:length] * y
        total[off:off + length] += weight[:length]
    return out / total * std + mean


def test_track(seconds=12.0, seed=1):
    """A synthetic stereo 'song': plucked tones, a bass line, clicks and a little noise."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    x = np.zeros((2, n))
    for k in range(int(seconds * 4)):
        f = 220 * 2 ** (rng.integers(0, 24) / 12)
        s = int(k * SAMPLE_RATE / 4)
        env = np.exp(-np.maximum(t - t[s], 0) * 6) * (t >= t[s])
        tone = env * (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t))
        pan = rng.uniform(0.3, 0.7)
        x[0] += 0.2 * pan * tone
        x[1] += 0.2 * (1 - pan) * tone
    x += 0.15 * np.sin(2 * np.pi * 55 * t * (1 + 0.5 * (np.floor(t) % 2)))
    x += 0.01 * rng.standard_normal((2, n))
    return x.astype(np.float32)


def export(model, out, fp16):
    core = Core(model).eval()
    spec_in = torch.randn(1, 4, NFFT // 2, FRAMES)
    mix_in = torch.randn(1, 2, SEGMENT)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if fp16:
        # The network in float16; the (de)normalisation around it stays float32 (Core.half).
        import demucs.transformer as tr
        for name in ("create_sin_embedding", "create_2d_sin_embedding"):
            f = getattr(tr, name)
            setattr(tr, name, lambda *a, _f=f, **k: _f(*a, **k).half())
        core = Core(copy.deepcopy(model).half()).eval()
        core.half_net = True
    torch.backends.mha.set_fastpath_enabled(False)  # the fused attention kernel does not export
    with torch.no_grad():
        torch.onnx.export(core, (spec_in, mix_in), out, input_names=["spec", "mix"],
                          output_names=["time", "zout"], opset_version=17, dynamo=False,
                          do_constant_folding=True)
    print(f"wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB)", file=sys.stderr)


def session(path, threads=4):
    import onnxruntime as ort
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


def validate(model, path, tol):
    """Torch forward on one segment vs ONNX graph + numpy STFT; returns max abs error."""
    rng = np.random.default_rng(0)
    seg = test_track(SEGMENT / SAMPLE_RATE + 0.01)[:, :SEGMENT]
    seg = seg + 0.05 * rng.standard_normal(seg.shape).astype(np.float32)
    with torch.no_grad():
        ref = model(torch.from_numpy(seg)[None])[0].numpy()
        z = model._magnitude(model._spec(torch.from_numpy(seg)[None]))[0].numpy()
    spec_err = np.abs(spec(seg) - z).max()
    sess = session(path)
    t0 = time.time()
    got = run_segment(sess, seg)
    dt = time.time() - t0
    err = np.abs(got - ref).max()
    snr = 10 * np.log10((ref ** 2).sum() / ((got - ref) ** 2).sum())
    print(f"validate {os.path.basename(path)}: spec max|err| {spec_err:.2e}, stems max|err| {err:.2e} "
          f"(ref peak {np.abs(ref).max():.3f}), SNR {snr:.1f} dB, segment {dt:.2f} s", file=sys.stderr)
    if err > tol:
        raise SystemExit(f"validation failed: {err} > {tol}")
    return err


def write_reference(model, path, ref_dir, sources):
    """Saves a test vector for DemucsTest.kt: a 12 s track and torch apply_model's stems."""
    from demucs.apply import apply_model
    os.makedirs(ref_dir, exist_ok=True)
    wav = test_track()
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std(ddof=1) + 1e-8
    with torch.no_grad():
        stems = apply_model(model, torch.from_numpy((wav - mean) / std)[None], shifts=0,
                            split=True, overlap=0.25)[0].numpy() * std + mean
    got = separate(session(path), wav)
    snr = 10 * np.log10((stems ** 2).sum() / ((got - stems) ** 2).sum())
    print(f"reference: numpy+onnx track vs torch apply_model SNR {snr:.1f} dB", file=sys.stderr)
    wav.astype("<f4").tofile(os.path.join(ref_dir, "input.f32"))
    stems.astype("<f4").tofile(os.path.join(ref_dir, "stems.f32"))
    with open(os.path.join(ref_dir, "meta.txt"), "w") as f:
        f.write(f"samples={wav.shape[1]}\nsources={','.join(sources)}\nmodel={os.path.basename(path)}\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", choices=sorted(SOURCES), default="htdemucs_6s")
    p.add_argument("--weights", help="local .th checkpoint (default: demucs' pretrained download)")
    p.add_argument("--random", action="store_true", help="random weights, identical architecture (offline tests)")
    p.add_argument("--fp16", action="store_true", help="float16 weights and compute, float32 I/O")
    p.add_argument("--out", required=True)
    p.add_argument("--validate", action="store_true")
    p.add_argument("--tol", type=float, default=None, help="max abs error (default 1e-3 fp32, 5e-2 fp16)")
    p.add_argument("--ref-dir", help="also write a reference test vector here")
    a = p.parse_args()
    model = random_model(a.model) if a.random else load_model(a.model, a.weights)
    export(model, a.out, a.fp16)
    if a.validate:
        validate(model, a.out, a.tol or (5e-2 if a.fp16 else 1e-3))
    if a.ref_dir:
        write_reference(model, a.out, a.ref_dir, model.sources)


if __name__ == "__main__":
    main()
