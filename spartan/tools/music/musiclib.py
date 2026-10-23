"""Shared DSP and Ogg Opus helpers for spartan/tools/music (master.py and qa.py load this file by path,
because `python -I` keeps the script's directory off sys.path). numpy + scipy only.

Every signal here is a LOOP: an array of shape (samples, channels) whose last sample is followed by
its first. Linear filters therefore run in their periodic steady state (circular convolution through
the FFT, see `circ_sos`), loudness blocks wrap around the end, and the nonlinear stages (compressor,
limiter) warm up on the loop's own tail, so mastering never introduces a seam.
"""
from __future__ import annotations

import math
import struct
import subprocess
from pathlib import Path

import numpy as np
from scipy import ndimage, signal
from scipy.linalg import solve_toeplitz
from scipy.io import wavfile

SR = 48000


# ----------------------------------------------------------------------------------------- WAV I/O

def read_wav(path) -> tuple[np.ndarray, int]:
    """(samples, channels) float64 in [-1, 1] and the sample rate."""
    sr, x = wavfile.read(str(path))
    if x.dtype.kind == 'i':
        x = x.astype(np.float64) / float(-np.iinfo(x.dtype).min)
    elif x.dtype.kind == 'u':
        x = (x.astype(np.float64) - 128.0) / 128.0
    else:
        x = x.astype(np.float64)
    if x.ndim == 1:
        x = x[:, None]
    return x, int(sr)


def write_wav(path, x: np.ndarray, sr: int = SR) -> None:
    """32-bit float WAV (no clipping, no dither: these are intermediate files)."""
    wavfile.write(str(path), sr, np.ascontiguousarray(x, dtype=np.float32))


def db(x, floor=1e-12):
    return 20.0 * np.log10(np.maximum(np.abs(x), floor))


def resample(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    """Polyphase resampling of a loop (circular padding, so the seam stays clean)."""
    if sr_in == sr_out:
        return x
    g = math.gcd(sr_in, sr_out)
    up, down = sr_out // g, sr_in // g
    pad = 256 * down
    xe = np.concatenate([x[-pad:], x, x[:pad]])
    y = signal.resample_poly(xe, up, down, axis=0)
    n_out = int(round(x.shape[0] * sr_out / sr_in))
    s = pad * up // down
    return y[s:s + n_out]


# ------------------------------------------------------------------------------ circular filtering

def circ_sos(x: np.ndarray, sos: np.ndarray) -> np.ndarray:
    """Filters a loop with an IIR cascade in its periodic steady state: the DFT of the loop is
    multiplied by the cascade's frequency response at the DFT bins, which is circular convolution
    with the impulse response folded modulo the loop length. Exact for a loop (the impulse response
    decays long before the loop ends), with no warm-up and no seam."""
    n = x.shape[0]
    X = np.fft.rfft(x, axis=0)
    w = 2.0 * np.pi * np.arange(X.shape[0]) / n
    _, h = signal.sosfreqz(sos, worN=w)
    return np.fft.irfft(X * h[:, None], n=n, axis=0)


def _sos(b, a):
    b = np.asarray(b, float) / a[0]
    a = np.asarray(a, float) / a[0]
    return np.concatenate([b, a])[None, :]


def peaking(f0, q, gain_db, sr=SR):
    """RBJ peaking EQ as one SOS row."""
    A = 10 ** (gain_db / 40)
    w0 = 2 * np.pi * f0 / sr
    al = np.sin(w0) / (2 * q)
    return _sos([1 + al * A, -2 * np.cos(w0), 1 - al * A], [1 + al / A, -2 * np.cos(w0), 1 - al / A])


def shelf(f0, q, gain_db, sr=SR, high=True):
    """RBJ high/low shelf as one SOS row."""
    A = 10 ** (gain_db / 40)
    w0 = 2 * np.pi * f0 / sr
    al = np.sin(w0) / (2 * q)
    c, s2 = np.cos(w0), 2 * np.sqrt(A) * al
    if high:
        b = [A * ((A + 1) + (A - 1) * c + s2), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - s2)]
        a = [(A + 1) - (A - 1) * c + s2, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - s2]
    else:
        b = [A * ((A + 1) - (A - 1) * c + s2), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - s2)]
        a = [(A + 1) + (A - 1) * c + s2, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - s2]
    return _sos(b, a)


def k_weighting(sr=SR):
    """ITU-R BS.1770-4 K-weighting (pre-filter shelf + RLB high-pass) designed for any sample rate
    with the analogue prototype parameters used by pyloudnorm; at 48 kHz it reproduces the
    standard's coefficients."""
    G, Q, fc = 3.999843853973347, 0.7071752369554196, 1681.974450955533
    A = 10 ** (G / 40)
    w0 = 2 * np.pi * fc / sr
    al = np.sin(w0) / (2 * Q)
    c, s2 = np.cos(w0), 2 * np.sqrt(A) * al
    st1 = _sos([A * ((A + 1) + (A - 1) * c + s2), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - s2)],
               [(A + 1) - (A - 1) * c + s2, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - s2])
    Q2, fc2 = 0.5003270373238773, 38.13547087602444
    w0 = 2 * np.pi * fc2 / sr
    al = np.sin(w0) / (2 * Q2)
    c = np.cos(w0)
    st2 = _sos([1.0, -2.0, 1.0], [1 + al, -2 * c, 1 - al])   # unity-gain-at-Nyquist form used by BS.1770
    return np.vstack([st1, st2])


# ----------------------------------------------------------------------------------------- loudness

def _block_power(xk: np.ndarray, sr: int, block_s: float, step_s: float, circular: bool):
    """Channel-summed mean square of each block (BS.1770 channel weights 1 for L/R/mono)."""
    n = xk.shape[0]
    b = int(round(block_s * sr))
    h = int(round(step_s * sr))
    p = np.sum(xk * xk, axis=1)
    if circular:
        reps = int(math.ceil(b / n)) + 1
        p = np.tile(p, reps)[: n + b]
        starts = np.arange(0, n, h)
    else:
        starts = np.arange(0, max(1, n - b + 1), h)
    cs = np.concatenate([[0.0], np.cumsum(p)])
    ends = np.minimum(starts + b, len(p))
    return (cs[ends] - cs[starts]) / b, starts


def k_filtered(x: np.ndarray, sr: int, circular=True) -> np.ndarray:
    return circ_sos(x, k_weighting(sr)) if circular else signal.sosfilt(k_weighting(sr), x, axis=0)


def _lufs(z):
    return -0.691 + 10 * np.log10(np.maximum(z, 1e-30))


def integrated_lufs(x: np.ndarray, sr: int, circular=True, xk=None) -> float:
    """BS.1770-4 / EBU R128 integrated loudness (400 ms blocks, 75 % overlap, absolute gate -70
    LUFS, relative gate -10 LU). For a loop the blocks wrap around the end."""
    xk = k_filtered(x, sr, circular) if xk is None else xk
    z, _ = _block_power(xk, sr, 0.4, 0.1, circular)
    l = _lufs(z)
    z1 = z[l > -70]
    if not len(z1):
        return float('-inf')
    gate = _lufs(np.mean(z1)) - 10
    z2 = z[(l > -70) & (l > gate)]
    return float(_lufs(np.mean(z2)))


def loudness_series(x: np.ndarray, sr: int, window_s: float, step_s=0.1, circular=True, xk=None):
    """Momentary (0.4 s) or short-term (3 s) loudness every `step_s`; returns (lufs, start times)."""
    xk = k_filtered(x, sr, circular) if xk is None else xk
    z, starts = _block_power(xk, sr, window_s, step_s, circular)
    return _lufs(z), starts / sr


def loudness_range(x: np.ndarray, sr: int, circular=True, xk=None) -> float:
    """EBU Tech 3342 LRA from 3 s short-term blocks (absolute gate -70, relative gate -20 LU)."""
    st, _ = loudness_series(x, sr, 3.0, 0.1, circular, xk)
    st = st[st > -70]
    if not len(st):
        return 0.0
    gate = 10 * np.log10(np.mean(10 ** (st / 10))) - 20
    st = st[st > gate]
    return float(np.percentile(st, 95) - np.percentile(st, 10))


def gated_lufs_of_power(z):
    """Integrated loudness from a set of block powers (for windows of a loop)."""
    l = _lufs(z)
    z1 = z[l > -70]
    if not len(z1):
        return float('-inf')
    gate = _lufs(np.mean(z1)) - 10
    z2 = z1[_lufs(z1) > gate]
    return float(_lufs(np.mean(z2)))


# --------------------------------------------------------------------------------------- true peak

def true_peak_env(x: np.ndarray, oversample=4, chunk=1 << 20) -> np.ndarray:
    """Per-sample true-peak envelope (max |x| over the `oversample` interpolated points that follow
    each sample), per channel, computed circularly in chunks."""
    n, ch = x.shape
    pad = 64
    out = np.empty((n, ch))
    for s in range(0, n, chunk):
        e = min(n, s + chunk)
        idx = np.arange(s - pad, e + pad) % n
        up = signal.resample_poly(x[idx], oversample, 1, axis=0)
        up = up[pad * oversample:(pad + e - s) * oversample]
        out[s:e] = np.abs(up).reshape(e - s, oversample, ch).max(axis=1)
    return np.maximum(out, np.abs(x))


def true_peak_db(x: np.ndarray) -> float:
    return float(db(true_peak_env(x).max()))


# ------------------------------------------------------------------------------- dynamics (loops)

def glue_compress(x: np.ndarray, sr: int, ratio=2.0, knee_db=6.0, gr_db=2.0, attack_ms=30.0, release_ms=300.0,
                  sc_hpf=90.0, threshold_db=None, hop=240):
    """Stereo-linked RMS bus compressor for a loop ("glue"): a 20 ms RMS detector on the side-chain
    (mid, high-passed so the timpani and bass do not pump), soft knee, ratio 2:1, slow attack (lets
    the drum transients through), program release. With no explicit threshold it sits `gr_db` below
    the 95th percentile of the detector, so loud passages get about `gr_db` of gain reduction and
    quiet ones none. The control loop warms up on the loop's tail, so the gain at sample 0 is the
    gain that follows the last sample."""
    n = x.shape[0]
    sos = signal.butter(2, sc_hpf, 'highpass', fs=sr, output='sos')
    sc = circ_sos(x.mean(axis=1, keepdims=True), sos)[:, 0]
    win = int(round(0.02 * sr))
    p = np.concatenate([sc[-win // 2:] ** 2, sc ** 2, sc[: win - win // 2] ** 2])
    cs = np.concatenate([[0.0], np.cumsum(p)])
    centers = np.arange(0, n, hop)
    level = 10 * np.log10(np.maximum((cs[centers + win] - cs[centers]) / win, 1e-20)) + 3.01  # dBFS of a sine
    active = level > -60
    if threshold_db is None:
        ref = np.percentile(level[active], 95) if active.any() else -20.0
        threshold_db = float(ref - gr_db / (1 - 1 / ratio))
    over = level - threshold_db
    slope = 1 - 1 / ratio
    gr = np.where(over <= -knee_db / 2, 0.0,
                  np.where(over >= knee_db / 2, slope * over, slope * (over + knee_db / 2) ** 2 / (2 * knee_db)))
    aa = math.exp(-hop / (attack_ms * 1e-3 * sr))
    ar = math.exp(-hop / (release_ms * 1e-3 * sr))
    sm = np.empty_like(gr)
    s = 0.0
    for _ in range(2):   # first lap: warm-up around the loop; second lap: the real values
        for i in range(len(gr)):
            g = gr[i]
            s = g + (s - g) * (aa if g > s else ar)
            sm[i] = s
    # Gain per sample: linear interpolation between control points, wrapping at the end.
    t = np.arange(n)
    gdb = np.interp(t, np.concatenate([centers, [n]]), np.concatenate([sm, sm[:1]]))
    y = x * (10 ** (-gdb / 20))[:, None]
    stats = {'threshold_db': round(threshold_db, 2), 'ratio': ratio, 'gr_max_db': round(float(sm.max()), 2),
             'gr_mean_db': round(float(sm.mean()), 2), 'gr_p95_db': round(float(np.percentile(sm, 95)), 2)}
    return y, stats


def limit_true_peak(x: np.ndarray, sr: int, ceiling_db=-1.5, lookahead_ms=1.5, release_ms=80.0, block=16):
    """Stereo-linked look-ahead true-peak limiter for a loop. The required gain r[n] =
    min(1, ceiling / truepeak[n]) is min-filtered over +-lookahead, given an exponential release in
    blocks, then smoothed by a Hann window as long as the look-ahead. Each smoothed gain is an average
    of values that are all <= r[n], so the output true peak stays under the ceiling (up to the small
    inter-sample change the gain curve itself causes; master.py checks and tightens)."""
    c = 10 ** (ceiling_db / 20)
    env = true_peak_env(x).max(axis=1)
    r = np.minimum(1.0, c / np.maximum(env, 1e-12))
    A = max(1, int(round(lookahead_ms * 1e-3 * sr)))
    h = ndimage.minimum_filter1d(r, size=2 * A + 1, mode='wrap')
    d = 1.0 - h
    n = len(d)
    nb = int(math.ceil(n / block))
    dp = np.concatenate([d, d[: nb * block - n]]).reshape(nb, block).max(axis=1)
    a = math.exp(-block / (release_ms * 1e-3 * sr))
    out = np.empty(nb)
    s = 0.0
    for _ in range(2):
        for i in range(nb):
            v = dp[i]
            s = v if v > s * a else s * a
            out[i] = s
    g_step = 1.0 - np.repeat(out, block)[:n]
    w = np.hanning(2 * A + 3)[1:-1]
    w /= w.sum()
    g = ndimage.convolve1d(g_step, w, mode='wrap')
    stats = {'ceiling_db': ceiling_db, 'gr_max_db': round(max(0.0, float(-db(g.min()))), 2),
             'limited_pct': round(float(np.mean(g < 0.9999) * 100), 3)}
    return x * g[:, None], stats


# ------------------------------------------------------------------------------------ seam metrics

def _hf_energy(x: np.ndarray, sr: int, fc=6000.0):
    sos = signal.butter(4, fc, 'highpass', fs=sr, output='sos')
    return np.sum(circ_sos(x, sos) ** 2, axis=1)


def local_step_ratios(x: np.ndarray, at: int = 0, half: int = 240, guard: int = 2) -> tuple[float, float]:
    """The first and second differences across sample `at` (circularly) as ratios to the largest
    first and second differences within +-`half` samples of it (excluding +-`guard`). A continuous
    signal gives values around or below 1 (a single sample rarely beats its ~480 neighbours); a
    discontinuity, a step out of nowhere, gives several times that. Sampled onsets (a drum on the
    downbeat) rise over a few samples, so their largest steps are inside the neighbourhood."""
    n = x.shape[0]
    idx = np.arange(at - half - 2, at + half + 2) % n
    seg = x[idx]
    c = half + 2                                  # position of `at` in seg
    d1 = np.abs(np.diff(seg, axis=0))             # d1[i] = |seg[i+1] - seg[i]|
    d2 = np.abs(np.diff(seg, n=2, axis=0))        # d2[i] = |seg[i+2] - 2 seg[i+1] + seg[i]|
    j = d1[c - 1]
    k = np.maximum(d2[c - 2], d2[c - 1])
    pos1 = np.arange(len(d1)) + 1                 # sample index (in seg) each step lands on
    m1 = np.abs(pos1 - c) > guard
    pos2 = np.arange(len(d2)) + 1                 # centre sample of each second difference
    m2 = np.abs(pos2 - c) > guard + 1
    r1 = j / (d1[m1].max(axis=0) + 1e-12)
    r2 = k / (d2[m2].max(axis=0) + 1e-12)
    return float(np.max(r1)), float(np.max(r2))


def seam_metrics(x: np.ndarray, sr: int, bar_seconds: float | None = None) -> dict:
    """How the wrap from the last sample to the first compares with the rest of the loop.

    jump_local/curv_local: the step and the second difference across the wrap vs. the largest ones
    within 5 ms of it (local_step_ratios; the primary click detector: about 1 or below is seamless).
    jump_ratio/curv_ratio: the same quantities as ratios to their 99.9th percentile over the loop.
    hf_db: energy above 6 kHz in the 1 ms around the wrap vs. the median 1 ms window of the 250 ms
    either side (a discontinuity is a broadband burst; so is a drum hit on the downbeat, hence the
    comparison with the loop's other bar lines).
    flux: positive log-spectral flux (below 16 kHz) of the 1024-sample frame centred on the wrap vs.
    the median frame, and vs. the frames on the loop's other bar lines (a downbeat at the loop start
    is music, not a click: it should look like the other downbeats).
    level_db: RMS of the first 20 ms vs. the last 20 ms."""
    n, ch = x.shape
    d1 = np.abs(np.diff(x, axis=0))
    d2 = np.abs(np.diff(x, n=2, axis=0))
    jump = np.abs(x[0] - x[-1])
    curv = np.maximum(np.abs(x[1] - 2 * x[0] + x[-1]), np.abs(x[0] - 2 * x[-1] + x[-2]))
    p1 = np.percentile(d1, 99.9, axis=0) + 1e-12
    p2 = np.percentile(d2, 99.9, axis=0) + 1e-12
    jl, cl = local_step_ratios(x, 0, half=int(round(0.005 * sr)))
    res = {
        'jump_local': round(jl, 3),
        'curv_local': round(cl, 3),
        'jump_ratio': round(float(np.max(jump / p1)), 3),
        'curv_ratio': round(float(np.max(curv / p2)), 3),
    }
    # HF burst around the wrap, against its neighbourhood.
    e = _hf_energy(x, sr)
    w = max(1, int(round(0.001 * sr)))
    ctx = int(round(0.25 * sr))

    def win_energy(center):
        idx = np.arange(center - w // 2, center - w // 2 + w) % n
        return float(np.sum(e[idx]))

    seam_e = win_energy(0)
    neigh = [win_energy(c) for c in range(-ctx, ctx + 1, w) if abs(c) > 2 * w]
    med = float(np.median(neigh)) + 1e-20
    res['hf_db'] = round(10 * math.log10((seam_e + 1e-20) / med), 2)
    res['hf_p99_db'] = round(10 * math.log10((seam_e + 1e-20) / (float(np.percentile(neigh, 99)) + 1e-20)), 2)
    # Spectral flux, below 16 kHz: a lossy codec leaves the top band empty, where the tiny step of
    # its quantisation noise across the wrap would otherwise dominate a log-magnitude flux.
    N, hop = 1024, 256
    mono = x.mean(axis=1)
    win = np.hanning(N)
    kmax = int(16000 * N / sr)

    def mag(center):
        idx = np.arange(center - N // 2, center + N // 2) % n
        return np.log1p(1000 * np.abs(np.fft.rfft(mono[idx] * win)[:kmax]))

    def flux(center):
        return float(np.sum(np.maximum(0.0, mag(center) - mag(center - hop))))

    seam_f = flux(0)
    grid = np.arange(0, n, max(hop, n // 4000))
    all_f = np.array([flux(int(c)) for c in grid])
    res['flux_ratio'] = round(seam_f / (float(np.median(all_f)) + 1e-12), 3)
    res['flux_pct'] = round(float(np.mean(all_f < seam_f) * 100), 1)
    if bar_seconds:
        bars = int(round(n / (bar_seconds * sr)))
        if bars >= 2:
            bf = np.array([flux(int(round(k * bar_seconds * sr))) for k in range(1, bars)])
            res['flux_vs_barlines'] = round(seam_f / (float(np.median(bf)) + 1e-12), 3)
            res['flux_barline_max'] = round(float(bf.max()) / (float(np.median(bf)) + 1e-12), 3)
            # HF burst at other bar lines, for the same reason.
            bh = []
            for k in range(1, bars):
                c = int(round(k * bar_seconds * sr))
                nb = [win_energy(c + o) for o in range(-ctx, ctx + 1, w) if abs(o) > 2 * w]
                bh.append(10 * math.log10((win_energy(c) + 1e-20) / (float(np.median(nb)) + 1e-20)))
            res['hf_barline_db_median'] = round(float(np.median(bh)), 2)
            res['hf_barline_db_max'] = round(float(np.max(bh)), 2)
    m = int(round(0.02 * sr))

    def level_step(c):   # RMS of the 20 ms after sample c over the 20 ms before it (dB)
        a = x[np.arange(c, c + m) % n]
        b = x[np.arange(c - m, c) % n]
        return float(20 * math.log10((np.sqrt(np.mean(a ** 2)) + 1e-12) / (np.sqrt(np.mean(b ** 2)) + 1e-12)))

    res['level_db'] = round(level_step(0), 2)   # first 20 ms over last 20 ms
    lp, lp_ref = lpc_residual_ratio(x, sr, 0)
    res['lpc_ratio'] = round(lp, 3)
    if bar_seconds:
        bars = int(round(n / (bar_seconds * sr)))
        if bars >= 2:
            cs = [int(round(k * bar_seconds * sr)) for k in range(1, bars)]
            lv = [level_step(c) for c in cs]
            res['level_barline_db_max'] = round(float(np.max(lv)), 2)
            lb = [lpc_residual_ratio(x, sr, c)[0] for c in cs]
            res['lpc_barline_median'] = round(float(np.median(lb)), 3)
            res['lpc_barline_max'] = round(float(np.max(lb)), 3)
    return res


def lpc_residual_ratio(x: np.ndarray, sr: int, at: int, order=32, ctx_s=0.25, win_s=0.001) -> tuple[float, float]:
    """Click detector: a 32-pole linear predictor fitted to the 0.5 s around sample `at` (mono)
    whitens the music; a discontinuity is unpredictable and shows as a spike in the prediction
    error. Returns (largest |error| within +-1 ms of `at` over the median of the per-1 ms maxima in
    the +-250 ms around it, that median). Music onsets also leave residual peaks, which is why
    seam_metrics compares the wrap with the loop's other bar lines."""
    n = x.shape[0]
    mono = x.mean(axis=1) if x.ndim == 2 else x
    h = int(round(ctx_s * sr))
    seg = mono[np.arange(at - h, at + h) % n]
    w = seg * np.hanning(len(seg))
    nfft = 1 << int(math.ceil(math.log2(2 * len(w))))
    r = np.fft.irfft(np.abs(np.fft.rfft(w, nfft)) ** 2)[:order + 1]
    if r[0] <= 1e-20:
        return 0.0, 0.0
    r[0] *= 1 + 1e-9
    a = solve_toeplitz(r[:order], r[1:order + 1])
    e = np.abs(signal.lfilter(np.concatenate([[1.0], -a]), [1.0], seg))
    e[:order] = 0.0
    k = max(1, int(round(win_s * sr)))
    seam = float(e[h - k:h + k + 1].max())
    blocks = e[order:order + ((len(e) - order) // k) * k].reshape(-1, k).max(axis=1)
    med = float(np.median(blocks)) + 1e-20
    return seam / med, med


def seam_verdict(m: dict, lossy: bool = False) -> tuple[str, list[str]]:
    """PASS / WARN / FAIL for a seam_metrics() dict. Only a step out of nowhere fails; the
    spectral measures can only warn, because a loud downbeat at the loop start is legitimate.

    lossy: the loop was decoded from Opus. The quantisation noise either side of the wrap comes
    from frames encoded 1-2 minutes apart, so it steps across the wrap (about the codec's noise
    level, some 20 dB under the music, with no burst above 6 kHz). That step shows in the second
    difference, so the curvature limits are wider (warn 4, fail 6); a real click (a rendering or
    cutting error) measured x7-x9 on the test loops."""
    bad, warn = [], []
    jl, cl = m.get('jump_local', 0.0), m.get('curv_local', 0.0)
    cw, cf = (4.0, 6.0) if lossy else (1.5, 3.0)
    if jl > 3 or cl > cf:
        bad.append(f"discontinuity at the wrap: step x{jl}, curvature x{cl} of the largest within 5 ms")
    elif jl > 1.5 or cl > cw:
        warn.append(f"large step at the wrap: x{jl} (curvature x{cl}) of the largest within 5 ms")
    hf_ref = max(6.0, m['hf_barline_db_median'] + 12.0) if 'hf_barline_db_median' in m else 6.0
    if m['hf_db'] > hf_ref:
        warn.append(f"HF burst at the wrap ({m['hf_db']} dB over its neighbourhood; bar lines: median "
                    f"{m.get('hf_barline_db_median', '?')}, max {m.get('hf_barline_db_max', '?')})")
    flux_ref = m['flux_barline_max'] * 1.5 if 'flux_vs_barlines' in m else 3.0
    fx = m.get('flux_vs_barlines', m['flux_ratio'])
    if fx > flux_ref:
        warn.append(f"spectral flux at the wrap x{fx} (bar lines reach x{m.get('flux_barline_max', '?')})")
    # A downbeat at the loop start is louder than the bar before it; so are the other downbeats.
    lv_ref = max(6.0, m['level_barline_db_max'] + 3.0) if 'level_barline_db_max' in m else 6.0
    if abs(m['level_db']) > lv_ref:
        warn.append(f"level jumps {m['level_db']} dB across the wrap (bar lines up to {m.get('level_barline_db_max', '?')} dB)")
    if 'lpc_ratio' in m:
        lp_ref = max(8.0, 2.0 * m['lpc_barline_max']) if 'lpc_barline_max' in m else 8.0
        if m['lpc_ratio'] > lp_ref:
            warn.append(f"prediction-error spike at the wrap x{m['lpc_ratio']} (bar lines up to x{m.get('lpc_barline_max', '?')})")
    return ('FAIL' if bad else 'WARN' if warn else 'PASS'), bad + warn


# -------------------------------------------------------------------------------------- Ogg / Opus

def _crc_table():
    t = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
        t.append(r & 0xFFFFFFFF)
    return t


_CRC = _crc_table()


def ogg_crc(data: bytes) -> int:
    crc = 0
    tab = _CRC
    for b in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ tab[((crc >> 24) ^ b) & 0xFF]
    return crc


def read_ogg(data: bytes):
    """Pages (dicts) and packets (bytes) of a single-stream Ogg file; checks every page CRC."""
    pos, pages, packets, cur = 0, [], [], b''
    while pos < len(data):
        if data[pos:pos + 4] != b'OggS':
            raise ValueError(f'no Ogg page at byte {pos}')
        _ver, flags, granule, serial, seq, crc, nseg = struct.unpack_from('<BBqIIIB', data, pos + 4)
        lace = data[pos + 27:pos + 27 + nseg]
        body0 = pos + 27 + nseg
        size = sum(lace)
        page = bytearray(data[pos:body0 + size])
        page[22:26] = b'\0\0\0\0'
        if ogg_crc(bytes(page)) != crc:
            raise ValueError(f'bad CRC in Ogg page {seq}')
        off, ends = 0, []
        for l in lace:
            cur += data[body0 + off:body0 + off + l]
            off += l
            if l < 255:
                packets.append(cur)
                cur = b''
                ends.append(len(packets) - 1)
        pages.append({'flags': flags, 'granule': granule, 'serial': serial, 'seq': seq, 'ends': ends})
        pos = body0 + size
    return pages, packets


def opus_packet_samples(pkt: bytes) -> int:
    """Samples at 48 kHz in an Opus packet (RFC 6716 TOC byte)."""
    toc = pkt[0]
    cfg = toc >> 3
    if cfg < 12:
        ms = (10, 20, 40, 60)[cfg % 4]
    elif cfg < 16:
        ms = (10, 20)[cfg % 2]
    else:
        ms = (2.5, 5, 10, 20)[cfg % 4]
    code = toc & 3
    frames = 1 if code == 0 else 2 if code in (1, 2) else pkt[1] & 0x3F
    return int(round(frames * ms * 48))


def opus_tags(vendor: str, comments: dict) -> bytes:
    v = vendor.encode()
    out = b'OpusTags' + struct.pack('<I', len(v)) + v + struct.pack('<I', len(comments))
    for k, val in comments.items():
        c = f'{k}={val}'.encode()
        out += struct.pack('<I', len(c)) + c
    return out


def parse_opus_tags(pkt: bytes) -> tuple[str, dict]:
    if pkt[:8] != b'OpusTags':
        raise ValueError('not an OpusTags packet')
    (vl,) = struct.unpack_from('<I', pkt, 8)
    vendor = pkt[12:12 + vl].decode('utf-8', 'replace')
    pos = 12 + vl
    (count,) = struct.unpack_from('<I', pkt, pos)
    pos += 4
    tags = {}
    for _ in range(count):
        (cl,) = struct.unpack_from('<I', pkt, pos)
        k, _, v = pkt[pos + 4:pos + 4 + cl].decode('utf-8', 'replace').partition('=')
        tags[k.upper()] = v
        pos += 4 + cl
    return vendor, tags


def write_ogg(packets: list[bytes], granules: list[int], serial: int, page_samples=48000) -> bytes:
    """Muxes Opus packets into Ogg pages: OpusHead alone on the first (BOS) page, OpusTags on the
    next, then audio pages of about `page_samples` each. `granules[i]` is the granule position after
    packet i (-1 for header packets). The last page carries EOS."""
    pages = []

    def flush(pkts, gran, flags):
        lace = bytearray()
        for p in pkts:
            lace += bytes([255] * (len(p) // 255) + [len(p) % 255])
        if len(lace) > 255:
            raise ValueError('page overflow')
        hdr = struct.pack('<4sBBqIIIB', b'OggS', 0, flags, gran, serial, len(pages), 0, len(lace)) + bytes(lace)
        page = bytearray(hdr + b''.join(pkts))
        page[22:26] = struct.pack('<I', ogg_crc(bytes(page)))
        pages.append(bytes(page))

    flush([packets[0]], 0, 0x02)
    if len(packets[1]) >= 255 * 255:
        raise ValueError('OpusTags too large for one page')
    flush([packets[1]], 0, 0)
    cur, segs, first_gran = [], 0, None
    last = len(packets) - 1
    for i in range(2, len(packets)):
        p = packets[i]
        need = len(p) // 255 + 1
        if cur and (segs + need > 255 or granules[i - 1] - first_gran >= page_samples):
            flush(cur, granules[i - 1], 0)
            cur, segs = [], 0
        if not cur:
            first_gran = granules[i - 1] if i > 2 else 0
        cur.append(p)
        segs += need
        if i == last:
            flush(cur, granules[i], 0x04)
    return b''.join(pages)


def ogg_opus_info(path) -> dict:
    """Channels, pre-skip, decoded length (final granule - pre-skip) and tags of an Ogg Opus file."""
    pages, packets = read_ogg(Path(path).read_bytes())
    head = packets[0]
    if head[:8] != b'OpusHead':
        raise ValueError('not Ogg Opus')
    ch = head[9]
    (pre_skip,) = struct.unpack_from('<H', head, 10)
    (in_rate,) = struct.unpack_from('<I', head, 12)
    (gain,) = struct.unpack_from('<h', head, 16)
    vendor, tags = parse_opus_tags(packets[1])
    final = pages[-1]['granule']
    total = sum(opus_packet_samples(p) for p in packets[2:])
    return {'channels': ch, 'pre_skip': pre_skip, 'input_rate': in_rate, 'output_gain': gain, 'vendor': vendor,
            'tags': tags, 'samples': final - pre_skip, 'packet_samples': total, 'end_trim': total - final,
            'pages': len(pages), 'packets': len(packets) - 2, 'eos': bool(pages[-1]['flags'] & 0x04),
            'bytes': Path(path).stat().st_size}


def ffmpeg_decode(path, sr=SR, channels=2) -> np.ndarray:
    """Decodes any file with ffmpeg to float (samples, channels) at `sr`."""
    r = subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(path), '-f', 'f32le', '-acodec', 'pcm_f32le',
                        '-ac', str(channels), '-ar', str(sr), '-'], capture_output=True, check=True)
    return np.frombuffer(r.stdout, dtype='<f4').reshape(-1, channels).astype(np.float64)


def best_lag(a: np.ndarray, b: np.ndarray, max_lag=2048, at=None, length=1 << 15) -> int:
    """Lag (in samples) that best aligns b to a around position `at` (b[i + lag] ~ a[i])."""
    n = min(a.shape[0], b.shape[0])
    at = n // 3 if at is None else at
    s = max(max_lag, min(at, n - length - max_lag))
    ref = a[s:s + length].mean(axis=1)
    seg = b[s - max_lag:s + length + max_lag].mean(axis=1)
    c = signal.correlate(seg, ref, mode='valid', method='fft')
    return int(np.argmax(c)) - max_lag


# ----------------------------------------------------------------------------------- spectral stats

def long_term_spectrum(x: np.ndarray, sr: int, N=4096):
    """Average power spectrum of the mono sum and the frequency axis."""
    mono = x.mean(axis=1)
    f, P = signal.welch(mono, fs=sr, nperseg=N, noverlap=N // 2, window='hann', scaling='spectrum')
    return f, P


def band_share(f, P, lo, hi):
    tot = float(np.sum(P[1:])) + 1e-30
    m = (f >= lo) & (f < hi)
    return float(np.sum(P[m])) / tot


def centroid_rolloff(f, P, roll=0.85):
    tot = float(np.sum(P)) + 1e-30
    cen = float(np.sum(f * P)) / tot
    c = np.cumsum(P) / tot
    ro = float(f[min(len(f) - 1, int(np.searchsorted(c, roll)))])
    return cen, ro
