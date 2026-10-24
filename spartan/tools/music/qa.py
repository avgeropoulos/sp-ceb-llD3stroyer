#!/usr/bin/env python3
"""Thermopylae music pipeline, stage 3: QA of the encoded cues, i.e. of what the game ships.

    python -I qa.py [cue ...] [--music-dir DIR] [--bank FILE] [--report FILE] [--browser] [--target LUFS]

Every cue is decoded from its .ogg with ffmpeg (what a player hears) and measured as a LOOP (filters,
loudness blocks and windows wrap around the end):

  length       decoded samples vs. the LOOPLENGTH tag, the musicbank `dur` (within 1 ms) and `samples`
               (exact), and the brief in cues.json; the bar grid from the METER map ('4/4' or e.g.
               '4/4,5/4@21,4/4@29'), BARS, BEATS and BPM tags must add up to LOOPLENGTH
  loudness     BS.1770-4 integrated, LRA, max momentary/short-term, PLR; vs. the musicbank `lufs`, the
               target and the other cues (one common level)
  peaks        true peak (4x oversampled), sample peak, clipped samples, flat-topped runs; the
               TRUEPEAK_DBTP tag vs. the measured decoded true peak
  crest        peak-to-RMS of the whole loop and the median of 400 ms windows
  spectrum     centroid, 85 % roll-off, spread; energy shares: sub (<35 Hz), low (<120 Hz, the
               "low-end ratio"), mud (200-400 Hz), speech band (200 Hz-3.5 kHz), presence, air
  stereo       L/R correlation (whole loop and 5th percentile of 400 ms windows), side/mid ratio,
               loudness lost when a phone speaker sums to mono
  seam         musiclib.seam_metrics at the wrap (sample steps, curvature, HF burst, spectral flux vs.
               the median and the other bar lines, prediction-error spike, level step) and verdict
  fatigue      8-bar windows: loudness, centroid, onset density and speech-band level per window;
               their spread (contrast between sections), the share of time near the loudest level
               ("no breath"), and the quietest window's speech-band level (room for the voice)
  --browser    webcheck.mjs decodes the cues in headless Chromium (the Android WebView's engine) at
               48 kHz and 44.1 kHz and loops the 48 kHz buffer in a 44.1 kHz context: lengths and seams

Verdicts: FAIL (exit status 1) for a broken deliverable: wrong length, a bar grid that does not add up
to the loop, a click at the wrap, true peak over the -1.5 dBTP ceiling, clipping, loudness off target or
off the bank by more than 0.5 LU, no stereo. WARN for
things a person should judge by ear (the fatigue proxies, a cue outside its brief, a near-mono or
out-of-phase mix). These measurements cannot replace listening.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from scipy import ndimage, signal

HERE = Path(__file__).resolve().parent
SPARTAN = HERE.parent.parent
GAME = SPARTAN / 'src/main/assets/game'
CACHE = Path(os.environ.get('THERMOPYLAE_MUSIC_CACHE')
             or Path(os.environ.get('XDG_CACHE_HOME') or Path.home() / '.cache') / 'thermopylae-music')

sys.dont_write_bytecode = True   # no __pycache__ in the repo
_spec = importlib.util.spec_from_file_location('musiclib', HERE / 'musiclib.py')
ml = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ml)
_mspec = importlib.util.spec_from_file_location('master', HERE / 'master.py')
master = importlib.util.module_from_spec(_mspec)
_mspec.loader.exec_module(master)

SR = ml.SR
CUES = {k: v for k, v in json.loads((HERE / 'cues.json').read_text()).items() if not k.startswith('_')}
SPEECH = (200.0, 3500.0)


def r(x, d=2):
    return None if x is None or (isinstance(x, float) and not math.isfinite(x)) else round(float(x), d)


# ------------------------------------------------------------------------------------- musicbank

def read_bank(path: Path) -> dict:
    if not path.exists():
        return {}
    rows = {}
    pat = re.compile(r"(\w+): Object\.freeze\(\{ file: '([^']+)', dur: ([0-9.]+),(?: samples: ([0-9]+),)? bpm: ([0-9.]+), lufs: (-?[0-9.]+) \}\)")
    for m in pat.finditer(path.read_text()):
        rows[m.group(1)] = {'file': m.group(2), 'dur': float(m.group(3)), 'samples': int(m.group(4)) if m.group(4) else None,
                            'bpm': float(m.group(5)), 'lufs': float(m.group(6))}
    return rows


# -------------------------------------------------------------------------------------- analysis

def stft_mag(mono: np.ndarray, N=2048, hop=512):
    """Magnitude STFT of a loop (frames wrap around the end): (frames, bins), frame centres."""
    n = len(mono)
    centres = np.arange(0, n, hop)
    win = np.hanning(N)
    idx = (centres[:, None] - N // 2 + np.arange(N)[None, :]) % n
    return np.abs(np.fft.rfft(mono[idx] * win, axis=1)), centres


def onsets(mag: np.ndarray, hop: int, sr: int) -> np.ndarray:
    """Onset frames: peaks of the positive log-spectral flux over its moving median + 1.5 MAD."""
    lm = np.log1p(100.0 * mag)
    flux = np.maximum(0.0, lm - np.roll(lm, 1, axis=0)).sum(axis=1)
    w = max(3, int(round(0.5 * sr / hop)) | 1)
    med = ndimage.median_filter(flux, size=w, mode='wrap')
    mad = ndimage.median_filter(np.abs(flux - med), size=w, mode='wrap')
    peaks, _ = signal.find_peaks(flux, height=med + 1.5 * mad + 1e-9, distance=max(1, int(round(0.05 * sr / hop))))
    return peaks


def band_energy(P, f, lo, hi):
    m = (f >= lo) & (f < hi)
    return float(np.sum(P[m]))


def spectrum_stats(x: np.ndarray, sr: int) -> dict:
    # The periodogram of the whole loop: a loop is periodic, so its DFT has no leakage (exact band
    # shares down to 1/loop-length Hz, which matters for the share under 35 Hz).
    mono = x.mean(axis=1)
    P = np.abs(np.fft.rfft(mono)) ** 2
    f = np.arange(len(P)) * sr / len(mono)
    tot = float(np.sum(P[1:])) + 1e-30
    cen, ro = ml.centroid_rolloff(f, P)
    spread = math.sqrt(float(np.sum(((f - cen) ** 2) * P)) / tot)
    share = lambda lo, hi: band_energy(P, f, lo, hi) / tot   # noqa: E731
    return {'centroid_hz': r(cen, 0), 'rolloff85_hz': r(ro, 0), 'spread_hz': r(spread, 0),
            'sub_lt35_pct': r(100 * share(0.1, 35), 3), 'low_lt120_pct': r(100 * share(0.1, 120), 1),
            'mud_200_400_pct': r(100 * share(200, 400), 1), 'speech_200_3500_pct': r(100 * share(*SPEECH), 1),
            'presence_3500_8000_pct': r(100 * share(3500, 8000), 1), 'air_gt10k_pct': r(100 * share(10000, sr / 2), 2)}


def stereo_stats(x: np.ndarray, sr: int, xk=None) -> dict:
    L, R = x[:, 0], x[:, 1]
    corr = float(np.corrcoef(L, R)[0, 1]) if np.std(L) > 0 and np.std(R) > 0 else 1.0
    w = int(0.4 * sr)
    k = x.shape[0] // w
    Lw, Rw = L[:k * w].reshape(k, w), R[:k * w].reshape(k, w)
    num = np.sum((Lw - Lw.mean(1, keepdims=True)) * (Rw - Rw.mean(1, keepdims=True)), axis=1)
    den = np.sqrt(np.sum((Lw - Lw.mean(1, keepdims=True)) ** 2, axis=1) * np.sum((Rw - Rw.mean(1, keepdims=True)) ** 2, axis=1)) + 1e-20
    active = np.sum(Lw ** 2 + Rw ** 2, axis=1) > 1e-8 * w
    cw = (num / den)[active] if active.any() else np.array([1.0])
    mid, side = (L + R) / 2, (L - R) / 2
    sm = ml.db(math.sqrt(float(np.mean(side ** 2))) / (math.sqrt(float(np.mean(mid ** 2))) + 1e-20))
    mono = np.repeat(mid[:, None], 2, axis=1)
    drop = ml.integrated_lufs(mono, sr) - ml.integrated_lufs(x, sr, xk=xk)
    return {'correlation': r(corr, 3), 'correlation_p5_400ms': r(np.percentile(cw, 5), 3),
            'side_to_mid_db': r(sm, 1), 'mono_sum_loudness_change_lu': r(drop, 2)}


def peak_stats(x: np.ndarray) -> dict:
    a = np.abs(x)
    clipped = int(np.sum(a >= 0.9999))
    # Flat tops: 3+ consecutive equal samples above -6 dBFS (a hard clipper's signature).
    flat = 0
    for c in range(x.shape[1]):
        v = x[:, c]
        eq = (np.abs(np.diff(v)) < 1e-7) & (np.abs(v[:-1]) > 0.5)
        runs = np.diff(np.concatenate([[0], eq.astype(np.int8), [0]]))
        starts, ends = np.where(runs == 1)[0], np.where(runs == -1)[0]
        flat += int(np.sum(ends - starts >= 2))
    rms = math.sqrt(float(np.mean(x ** 2)))
    return {'true_peak_dbtp': r(ml.true_peak_db(x)), 'sample_peak_dbfs': r(ml.db(a.max())), 'clipped_samples': clipped,
            'flat_top_runs': flat, 'rms_dbfs': r(ml.db(rms)), 'crest_db': r(ml.db(a.max() / (rms + 1e-20)))}


def window_crest(x: np.ndarray, sr: int) -> float:
    w = int(0.4 * sr)
    k = x.shape[0] // w
    seg = x[:k * w].reshape(k, w, x.shape[1])
    pk = np.abs(seg).max(axis=(1, 2))
    rms = np.sqrt(np.mean(seg ** 2, axis=(1, 2)))
    ok = rms > 1e-5
    return float(np.median(ml.db(pk[ok] / rms[ok]))) if ok.any() else float('nan')


def fatigue(x: np.ndarray, sr: int, starts: list[int], xk: np.ndarray) -> dict:
    """Section contrast and breath over 8-bar windows (the loop wraps). For a cue heard for 20+
    minutes, windows that all sound alike (small spread) or a loop that sits near its loudest level
    most of the time are the warning signs; a quiet window whose speech band is low leaves the voice room.
    starts: the sample index of every bar start (ml.bar_lines), so mixed meters window on real bars."""
    n = x.shape[0]
    bars = max(1, len(starts))
    edge = list(starts) + [n]
    nwin = max(1, math.ceil(bars / 8))
    mono = x.mean(axis=1)
    N, hop = 2048, 512
    mag, centres = stft_mag(mono, N, hop)
    freqs = np.fft.rfftfreq(N, 1 / sr)
    pw = mag ** 2
    on = onsets(mag, hop, sr)
    z, starts = ml._block_power(xk, sr, 0.4, 0.1, True)
    sos = signal.butter(4, SPEECH, 'bandpass', fs=sr, output='sos')
    sp = ml.circ_sos(x, sos)
    sp_pow = np.sum(sp ** 2, axis=1)
    rows = []
    for k in range(nwin):
        a = edge[8 * k]
        b = edge[min(bars, 8 * (k + 1))]
        sel = (centres >= a) & (centres < b)
        P = pw[sel].sum(axis=0)
        cen = float(np.sum(freqs * P) / (np.sum(P) + 1e-30))
        zb = z[(starts >= a) & (starts < b)]
        rows.append({'bars': f'{8 * k + 1}-{min(bars, 8 * k + 8)}', 'lufs': r(ml.gated_lufs_of_power(zb), 1),
                     'centroid_hz': r(cen, 0),
                     'onsets_per_s': r(np.sum((centres[on] >= a) & (centres[on] < b)) / ((b - a) / sr), 2),
                     'speech_band_dbfs': r(10 * math.log10(float(np.mean(sp_pow[a:b])) / 2 + 1e-20) + 3.01, 1)})
    valid = [w for w in rows if w['lufs'] is not None]
    lu = np.array([w['lufs'] for w in valid])
    cens = np.array([w['centroid_hz'] for w in rows])
    ons = np.array([w['onsets_per_s'] for w in rows])
    # Breath: the loudest 4-bar stretch against the quietest (a breath before the build is shorter
    # than 8 bars and would average away in the 8-bar windows).
    four = []
    for k in range(max(1, math.ceil(bars / 4))):
        a = edge[4 * k]
        b = edge[min(bars, 4 * (k + 1))]
        v = ml.gated_lufs_of_power(z[(starts >= a) & (starts < b)])
        if math.isfinite(v):
            four.append((v, f'{4 * k + 1}-{min(bars, 4 * k + 4)}'))
    st, _ = ml.loudness_series(x, sr, 3.0, 0.1, True, xk)
    st = st[st > -70]
    near_max = float(np.mean(st > st.max() - 3.0)) if len(st) else 0.0
    quiet = min(valid, key=lambda w: w['lufs']) if valid else None
    return {
        'windows': rows,
        'loudness_span_lu': r(lu.max() - lu.min() if len(lu) else 0, 1),
        'loudness_std_lu': r(lu.std() if len(lu) else 0, 2),
        'centroid_cv': r(cens.std() / (cens.mean() + 1e-9), 3),
        'centroid_span_hz': r(cens.max() - cens.min(), 0),
        'onsets_per_s_min_max': [r(ons.min(), 2), r(ons.max(), 2)],
        'onsets_per_s_loop': r(len(on) / (n / sr), 2),
        'breath_depth_lu': r(max(four)[0] - min(four)[0], 1) if four else None,
        'breath_bars': min(four)[1] if four else None,
        'climax_bars': max(four)[1] if four else None,
        'short_term_near_max_pct': r(100 * near_max, 1),
        'quietest_window': quiet['bars'] if quiet else None,
        'quietest_window_speech_band_db_re_loudest': r(quiet['speech_band_dbfs'] - max(w['speech_band_dbfs'] for w in rows), 1) if quiet else None,
    }


# --------------------------------------------------------------------------------------- browser

def browser_check(cues, music_dir: Path, out_dir: Path, ref: dict) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = ['node', str(HERE / 'webcheck.mjs'), '--music-dir', str(music_dir), '--out', str(out_dir)] + list(cues)
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        return {'error': (p.stderr or p.stdout).strip()[-500:]}
    rep = json.loads((out_dir / 'webcheck.json').read_text())
    res = {'browser': rep['browser']}
    for cue, e in rep['cues'].items():
        n, bar_secs, dff = ref[cue]['n'], ref[cue]['bar_secs'], ref[cue]['decoded']
        load = lambda k: np.fromfile(e[k]['file'], dtype='<f4').reshape(-1, 2).astype(np.float64)   # noqa: E731
        c = {}
        d48 = load('decode48')
        c['decode48_samples'] = int(d48.shape[0])
        c['decode48_length_ok'] = d48.shape[0] == n
        if d48.shape[0] == n:
            c['decode48_max_diff_vs_ffmpeg'] = float(f'{np.abs(d48 - dff).max():.2e}')
        sm = ml.seam_metrics(d48, SR, bar_secs)
        c['decode48_seam'], notes = ml.seam_verdict(sm, lossy=not ref[cue]['packet_loop'])
        c['decode48_seam_notes'] = notes
        c['decode48_seam_metrics'] = {k: sm[k] for k in ('jump_local', 'curv_local', 'lpc_ratio', 'flux_vs_barlines') if k in sm}
        d44 = load('decode44')
        want44 = n * 44100 / SR
        c['decode44_samples'] = int(d44.shape[0])
        c['decode44_samples_short'] = r(want44 - d44.shape[0], 2)
        sm = ml.seam_metrics(d44, 44100, bar_secs)
        c['decode44_seam'], c['decode44_seam_notes'] = ml.seam_verdict(sm, lossy=True)
        c['decode44_seam_metrics'] = {k: sm[k] for k in ('jump_local', 'curv_local', 'lpc_ratio', 'flux_vs_barlines') if k in sm}
        # Looped 48 kHz buffer resampled while playing in a 44.1 kHz context: the wrap is mid-render.
        lp = load('loop44')
        at = int(round(e['loop44']['wrapAt']))
        jl, cl = ml.local_step_ratios(lp, at, half=220)
        lpc, _ = ml.lpc_residual_ratio(lp, 44100, at)
        bars = [int(round(t * 44100)) for t in (bar_secs or [])[1:]]
        bl = [ml.lpc_residual_ratio(lp, 44100, b)[0] for b in bars if b < lp.shape[0] - 22050]
        c['loop44_wrap'] = {'jump_local': r(jl, 3), 'curv_local': r(cl, 3), 'lpc_ratio': r(lpc, 3),
                            'lpc_barline_max': r(max(bl), 3) if bl else None}
        bad = jl > 3 or cl > 3 or lpc > max(8.0, 2 * (max(bl) if bl else 4.0))
        c['loop44_seam'] = 'FAIL' if bad else 'PASS'
        res[cue] = c
    return res


# ------------------------------------------------------------------------------------------- main

def qa_cue(cue: str, ogg: Path, bank: dict, target: float) -> dict:
    v, notes_fail, notes_warn = {}, [], []
    info = ml.ogg_opus_info(ogg)
    tags = info['tags']
    d = ml.ffmpeg_decode(ogg, SR, 2)
    n = d.shape[0]
    bpm = float(tags.get('BPM') or bank.get(cue, {}).get('bpm') or 0)
    meter = tags.get('METER', '4/4')
    tag_bars = int(tags['BARS']) if tags.get('BARS', '').isdigit() else None
    # The bar grid from the meter map and the tempo: it must add up to the loop (a 4/4 + 5/4 cue tagged
    # plain 4/4 would put every later bar line mid-bar, and the breath and seam measures with it).
    grid = ml.bar_lines(meter, bpm, n, SR, tag_bars) if bpm else None
    starts = grid['starts'] if grid else []
    bar_secs = [s_ / SR for s_ in starts] if grid else None
    mode = tags.get('LOOPMODE')
    if not mode:   # files from before the tag: a packet loop is whole 20 ms packets with no end trim
        mode = 'packet-loop' if n % 960 == 0 and info['pre_skip'] % 960 == 0 and info['end_trim'] == 0 else 'circular-prime'
    v['file'] = str(ogg)
    v['container'] = {'bytes': info['bytes'], 'kbps': r(info['bytes'] * 8 / (n / SR) / 1000, 1), 'pre_skip': info['pre_skip'],
                      'end_trim': info['end_trim'], 'loop_mode': mode, 'eos': info['eos'], 'channels': info['channels']}
    # Length.
    length = {'samples': n, 'seconds': r(n / SR, 6), 'container_samples': info['samples'], 'tag_looplength': int(tags.get('LOOPLENGTH', -1)),
              'bpm': bpm, 'meter': meter, 'bars': grid['bars'] if grid else None, 'tag_bars': tag_bars,
              'quarter_notes': grid['quarters'] if grid else None, 'tag_beats': tags.get('BEATS'),
              'grid_error_samples': grid['error_samples'] if grid else None}
    if grid:
        if abs(grid['error_samples']) > 0.001 * SR:
            notes_fail.append(f"{grid['bars']} bars of {meter} at {bpm:g} bpm = {grid['samples']} samples, the loop is {n} "
                              f"(BARS x beats per bar x 60 / BPM must equal LOOPLENGTH)")
        if tag_bars is not None and tag_bars != grid['bars']:
            notes_fail.append(f'BARS tag {tag_bars}, the meter map lays {grid["bars"]} bars')
        if tags.get('BEATS') and abs(float(tags['BEATS']) - grid['quarters']) > 1e-6:
            notes_fail.append(f"BEATS tag {tags['BEATS']}, the meter map holds {grid['quarters']:g} quarter notes")
    if cue in bank:
        length['bank_dur'] = bank[cue]['dur']
        length['bank_samples'] = bank[cue]['samples']
        length['bank_error_ms'] = r((n / SR - bank[cue]['dur']) * 1000, 3)
        if abs(length['bank_error_ms']) > 1.0:
            notes_fail.append(f"decoded length {n / SR:.6f} s, musicbank dur {bank[cue]['dur']}")
        if bank[cue]['samples'] is None:
            notes_warn.append('musicbank.js has no samples field (regenerate it: render.mjs --bank)')
        elif bank[cue]['samples'] != n:
            notes_fail.append(f"decoded {n} samples, musicbank samples {bank[cue]['samples']}")
    else:
        notes_warn.append('not in musicbank.js')
    if length['tag_looplength'] != n or info['samples'] != n:
        notes_fail.append(f"decoded {n} samples, container {info['samples']}, LOOPLENGTH tag {length['tag_looplength']}")
    lo, hi = CUES.get(cue, {}).get('loopSeconds', [0, 1e9])
    if not lo <= n / SR <= hi:
        notes_warn.append(f'loop {n / SR:.1f} s; the brief asks for {lo}-{hi} s')
    bb = CUES.get(cue, {}).get('bpm')
    if bb and not bb[0] <= bpm <= bb[1]:
        notes_warn.append(f'bpm {bpm:g}; the brief asks for {bb[0]}-{bb[1]}')
    v['length'] = length
    # Loudness.
    xk = ml.k_filtered(d, SR)
    I = ml.integrated_lufs(d, SR, xk=xk)
    mom, _ = ml.loudness_series(d, SR, 0.4, 0.1, True, xk)
    st, _ = ml.loudness_series(d, SR, 3.0, 0.1, True, xk)
    pk = peak_stats(d)
    v['loudness'] = {'integrated_lufs': r(I), 'lra_lu': r(ml.loudness_range(d, SR, xk=xk)), 'max_momentary_lufs': r(mom.max()),
                     'max_short_term_lufs': r(st.max()), 'plr_db': r(pk['true_peak_dbtp'] - I)}
    if abs(I - target) > 0.5:
        notes_fail.append(f'integrated loudness {I:.2f} LUFS, target {target}')
    if cue in bank and abs(I - bank[cue]['lufs']) > 0.5:
        notes_fail.append(f"integrated loudness {I:.2f} LUFS, musicbank says {bank[cue]['lufs']}")
    # Peaks and crest.
    pk['crest_400ms_median_db'] = r(window_crest(d, SR))
    v['peaks'] = pk
    if pk['true_peak_dbtp'] > master.CEILING_DBTP:
        notes_fail.append(f"true peak {pk['true_peak_dbtp']} dBTP over the {master.CEILING_DBTP} dBTP ceiling")
    tp_tag = tags.get('TRUEPEAK_DBTP')
    pk['tag_true_peak_dbtp'] = float(tp_tag) if tp_tag else None
    if tp_tag and abs(float(tp_tag) - pk['true_peak_dbtp']) > 0.05:
        notes_warn.append(f"TRUEPEAK_DBTP tag {tp_tag}, the decoded file measures {pk['true_peak_dbtp']} dBTP")
    if pk['clipped_samples']:
        notes_fail.append(f"{pk['clipped_samples']} samples at full scale")
    if pk['flat_top_runs'] > 10:
        notes_warn.append(f"{pk['flat_top_runs']} flat-topped runs (hard limiting?)")
    # Spectrum and stereo.
    v['spectrum'] = spectrum_stats(d, SR)
    if v['spectrum']['sub_lt35_pct'] > 1.0:
        notes_warn.append(f"{v['spectrum']['sub_lt35_pct']} % of the energy under 35 Hz")
    v['stereo'] = stereo_stats(d, SR, xk)
    if v['stereo']['correlation'] < 0.0:
        notes_fail.append(f"L/R correlation {v['stereo']['correlation']} (out of phase)")
    elif v['stereo']['correlation'] > 0.98:
        notes_warn.append(f"L/R correlation {v['stereo']['correlation']}: nearly mono")
    if v['stereo']['mono_sum_loudness_change_lu'] < -3.0:
        notes_warn.append(f"loses {-v['stereo']['mono_sum_loudness_change_lu']} LU summed to mono (phone speaker)")
    # Seam.
    sm = ml.seam_metrics(d, SR, bar_secs)
    verdict, notes = ml.seam_verdict(sm, lossy=mode != 'packet-loop')
    v['seam'] = {'verdict': verdict, 'notes': notes, **sm}
    (notes_fail if verdict == 'FAIL' else notes_warn).extend(f'seam: {t}' for t in notes)
    if mode != 'packet-loop':
        notes_warn.append(f'Opus loop mode {mode}: the codec noise splices at the wrap (loop not a multiple of 20 ms)')
    # Fatigue proxies.
    if starts:
        fa = fatigue(d, SR, starts, xk)
        v['fatigue'] = fa
        brief = CUES.get(cue, {})
        cmin = brief.get('contrastLU', [2.0, 15.0])
        if fa['loudness_span_lu'] < 1.0 and fa['centroid_cv'] < 0.05:
            notes_warn.append(f"8-bar windows differ by only {fa['loudness_span_lu']} LU and {fa['centroid_cv'] * 100:.1f} % in centroid: little contrast for a long loop")
        if fa['breath_depth_lu'] is not None and fa['breath_depth_lu'] < cmin[0]:
            notes_warn.append(f"the quietest 4 bars ({fa['breath_bars']}) are only {fa['breath_depth_lu']} LU under the loudest ({fa['climax_bars']}); "
                              f"cues.json asks for at least {cmin[0]} LU of breath")
        if fa['breath_depth_lu'] is not None and fa['breath_depth_lu'] > cmin[1]:
            notes_warn.append(f"the quietest 4 bars are {fa['breath_depth_lu']} LU under the loudest: they may vanish under the game's SFX")
        if fa['short_term_near_max_pct'] > 70:
            notes_warn.append(f"{fa['short_term_near_max_pct']} % of the loop within 3 LU of its loudest: no breath")
    v['verdict'] = 'FAIL' if notes_fail else 'WARN' if notes_warn else 'PASS'
    v['fail'] = notes_fail
    v['warn'] = notes_warn
    v['_ref'] = {'n': n, 'bar_secs': bar_secs, 'decoded': d, 'packet_loop': mode == 'packet-loop'}
    return v


def table(rep: dict) -> str:
    cols = [('cue', lambda c, v: c), ('verdict', lambda c, v: v['verdict']), ('dur s', lambda c, v: f"{v['length']['seconds']:.3f}"),
            ('LUFS', lambda c, v: f"{v['loudness']['integrated_lufs']:.1f}"), ('LRA', lambda c, v: f"{v['loudness']['lra_lu']:.1f}"),
            ('dBTP', lambda c, v: f"{v['peaks']['true_peak_dbtp']:.1f}"), ('crest', lambda c, v: f"{v['peaks']['crest_db']:.1f}"),
            ('cent Hz', lambda c, v: f"{v['spectrum']['centroid_hz']:.0f}"), ('roll Hz', lambda c, v: f"{v['spectrum']['rolloff85_hz']:.0f}"),
            ('<120%', lambda c, v: f"{v['spectrum']['low_lt120_pct']:.1f}"), ('corr', lambda c, v: f"{v['stereo']['correlation']:.2f}"),
            ('seam', lambda c, v: v['seam']['verdict']), ('8bar LU', lambda c, v: f"{v.get('fatigue', {}).get('loudness_span_lu', float('nan')):.1f}"),
            ('cent CV', lambda c, v: f"{v.get('fatigue', {}).get('centroid_cv', float('nan')):.2f}"), ('kb/s', lambda c, v: f"{v['container']['kbps']:.0f}")]
    rows = [[h for h, _ in cols]] + [[f(c, v) for _, f in cols] for c, v in rep['cues'].items()]
    wid = [max(len(row[i]) for row in rows) for i in range(len(cols))]
    return '\n'.join('  '.join(cell.rjust(wid[i]) if i else cell.ljust(wid[i]) for i, cell in enumerate(row)) for row in rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('cues', nargs='*')
    ap.add_argument('--music-dir', default=str(GAME / 'audio/music'))
    ap.add_argument('--bank', help='musicbank.js (default: the game\'s, or <music-dir>/musicbank.js if that exists)')
    ap.add_argument('--report', help='JSON report path (default: <cache>/build/qa-report.json)')
    ap.add_argument('--target', type=float, default=master.TARGET_LUFS)
    ap.add_argument('--browser', action='store_true', help='also decode in headless Chromium (webcheck.mjs)')
    a = ap.parse_args()
    music_dir = Path(a.music_dir)
    bank_path = Path(a.bank) if a.bank else (music_dir / 'musicbank.js' if (music_dir / 'musicbank.js').exists() else GAME / 'js/audio/musicbank.js')
    bank = read_bank(bank_path)
    cues = a.cues or [c for c in CUES if (music_dir / f'{c}.ogg').exists()]
    if not cues:
        raise SystemExit(f'no cues in {music_dir}')
    rep = {'music_dir': str(music_dir), 'bank': str(bank_path), 'target_lufs': a.target, 'cues': {}}
    for cue in cues:
        ogg = music_dir / f'{cue}.ogg'
        if not ogg.exists():
            raise SystemExit(f'{ogg} not found')
        print(f'[{cue}] analysing {ogg}', file=sys.stderr, flush=True)
        rep['cues'][cue] = qa_cue(cue, ogg, bank, a.target)
    for c in set(bank) - set(rep['cues']):
        if not a.cues:
            rep.setdefault('bank_problems', []).append(f'musicbank lists {c} but {music_dir}/{c}.ogg is missing')
    # One common level.
    lv = [v['loudness']['integrated_lufs'] for v in rep['cues'].values()]
    rep['loudness_spread_lu'] = r(max(lv) - min(lv))
    if len(lv) > 1 and rep['loudness_spread_lu'] > 0.5:
        for v in rep['cues'].values():
            v['warn'].append(f"the cues' integrated loudness spreads over {rep['loudness_spread_lu']} LU")
            v['verdict'] = 'WARN' if v['verdict'] == 'PASS' else v['verdict']
    if a.browser:
        ref = {c: v['_ref'] for c, v in rep['cues'].items()}
        out = (Path(a.report).parent if a.report else CACHE / 'build') / 'webcheck'
        br = browser_check(list(rep['cues']), music_dir, out, ref)
        rep['browser'] = br
        for c, b in br.items():
            if c not in rep['cues']:
                continue
            v = rep['cues'][c]
            if not b['decode48_length_ok']:
                v['fail'].append(f"Chromium decodes {b['decode48_samples']} samples at 48 kHz, the loop is {ref[c]['n']}")
            if b['decode48_seam'] == 'FAIL':
                v['fail'].append('Chromium 48 kHz decode: seam ' + '; '.join(b['decode48_seam_notes']))
            if b['decode44_seam'] != 'PASS' or b['decode44_samples_short']:
                v['warn'].append(f"decoded in a 44.1 kHz context the loop is {b['decode44_samples_short']} samples short, seam {b['decode44_seam']} "
                                 f"(decode at 48 kHz, see README)")
            if b['loop44_seam'] == 'FAIL':
                v['warn'].append('48 kHz buffer looped in a 44.1 kHz context: click at the wrap')
            v['verdict'] = 'FAIL' if v['fail'] else 'WARN' if v['warn'] else 'PASS'
    for v in rep['cues'].values():
        v.pop('_ref', None)
    report = Path(a.report) if a.report else CACHE / 'build' / 'qa-report.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(rep, indent=2) + '\n')
    print(table(rep))
    for c, v in rep['cues'].items():
        for t in v['fail']:
            print(f'FAIL {c}: {t}')
        for t in v['warn']:
            print(f'warn {c}: {t}')
    for t in rep.get('bank_problems', []):
        print(f'FAIL bank: {t}')
    print(f'report: {report}')
    if any(v['verdict'] == 'FAIL' for v in rep['cues'].values()) or rep.get('bank_problems'):
        sys.exit(1)


if __name__ == '__main__':
    main()
