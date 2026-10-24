#!/usr/bin/env python3
"""Thermopylae music pipeline, stage 2: masters a rendered loop and encodes it as a seamless Ogg Opus loop.

    python -I master.py <build>/<cue>.loop.wav [--cue NAME] [--bpm N] [--target LUFS] [--ceiling DBTP]
                        [--out-dir DIR] [--report-dir DIR] [--bitrate KBPS] [--bank-out FILE | --no-bank]

Writes <out-dir>/<cue>.ogg (default: the game's audio/music), <report-dir>/<cue>.master.{wav,json}
(default: next to the input) and, when it writes into the game, js/audio/musicbank.js. stdout is a
JSON summary; exit status 1 if a check fails.
    python -I master.py --measure-reference <dir with *.procedural.wav from `node render.mjs --reference`>

Chain (every stage treats the signal as a LOOP: filters run circularly, dynamics warm up on the loop's
own tail, so mastering cannot create a seam):
  1. bus EQ: 8th-order Butterworth high-pass at 35 Hz (-3 dB at 35 Hz, -11 dB at 30 Hz, -23 dB at 25 Hz,
     -0.3 dB at the contrabass's low E, 41 Hz: keeps the sub rumble out of phone speakers and the limiter),
     and a broad 300 Hz dip of 1-3 dB, sized by how far the 200-400 Hz band stands above the spectrum's
     own trend between 100-150 Hz and 600-1200 Hz (mud from stacked low strings, choir and drones);
  2. glue: stereo-linked 2:1 RMS bus compressor, about 2 dB of gain reduction on the loudest passages;
  3. gain + true-peak limiter, iterated until the integrated loudness (BS.1770-4) is on the target
     (default -16.0 LUFS, the level of the procedural cues, see TARGET_LUFS) and the DECODED file's true
     peak is at or under the ceiling (default -1.5 dBTP; the limiter's own ceiling is lowered by the
     codec's overshoot, typically 0.2-0.7 dB);
  4. Opus at ~80 kb/s (VBR, 20 ms frames), looped in the packet domain when the loop is a whole number
     of 20 ms frames (960 samples): the encoder is fed [pad | loop | loop | loop head] with the pad sized
     so that its frame grid starts exactly on the loop; pass 1 warms the encoder up, pass 2's packets are
     the loop. The file holds [pass 2's last PRIME_PACKETS packets | pass 2's packets], and the Ogg
     pre-skip hides the copied packets. A decoder therefore enters bar 1 in exactly the state in which it
     leaves the last bar, and the wrap is an ordinary Opus frame boundary of the sequence [last packet,
     first packet]: the decoded wrap equals a continuous decode across the pass end (checked), so the
     codec noise does not splice. Loops that are not a multiple of 960 samples fall back to circular
     priming ([loop tail | loop | loop head], pre-skip over the tail, the final granule trims the end):
     sample-exact, but the codec noise either side of the wrap comes from different frames (reported);
  5. checks on the decoded file: length (exactly the loop length), alignment, seam (against the loop's
     real bar lines, from the meter map in render.json), true peak (<= the ceiling), loudness. The tags
     then record the decoded file's true peak (TRUEPEAK_DBTP), the meter map (METER, e.g.
     '4/4,5/4@21,4/4@29'), BARS and BEATS (quarter notes);
  6. `node render.mjs --bank` regenerates js/audio/musicbank.js from the encoded cues.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

import numpy as np
from scipy import signal

HERE = Path(__file__).resolve().parent
SPARTAN = HERE.parent.parent
GAME = SPARTAN / 'src/main/assets/game'
MUSIC_DIR = GAME / 'audio/music'
BANK = GAME / 'js/audio/musicbank.js'
CACHE = Path(os.environ.get('THERMOPYLAE_MUSIC_CACHE')
             or Path(os.environ.get('XDG_CACHE_HOME') or Path.home() / '.cache') / 'thermopylae-music')

sys.dont_write_bytecode = True   # no __pycache__ in the repo
_spec = importlib.util.spec_from_file_location('musiclib', HERE / 'musiclib.py')
ml = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ml)

SR = ml.SR
# Loudness target, BS.1770-4 integrated, stereo. Measured on the procedural cues that these replace
# (js/audio/loops.js rendered by `node render.mjs --reference`, played as dual mono because WebAudio
# up-mixes the mono loop buffer to L = R): title -16.4, battle -16.0, lastStand -20.8 LUFS. The new
# cues all sit at the title/battle level (their power mean, -16.2, rounded to the 0.5 LU grid), so
# swapping them in keeps the game's music-bus balance against speech and SFX. lastStand rises 4.8 LU
# over its old level, as the brief asks for one common level.
TARGET_LUFS = -16.0
CEILING_DBTP = -1.5
FRAME = 960          # 20 ms Opus frames
PRIME_PACKETS = 50   # packet-loop mode: packets copied from the loop's end to before its start (1 s;
                     # the decoder state converges to the bit within about 25)
PRIME = 4800         # fallback mode: samples of the loop's tail fed to the encoder before the loop
TAIL_PAD = 9600      # samples of the loop's head fed after it (encoder look-ahead into bar 1)


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------------------------- EQ

def third_octave_levels(x, sr):
    f, P = ml.long_term_spectrum(x, sr, N=8192)
    centers = 1000 * 2.0 ** (np.arange(-17, 14) / 3)        # 20 Hz .. 20 kHz
    lv = []
    for c in centers:
        m = (f >= c * 2 ** (-1 / 6)) & (f < c * 2 ** (1 / 6))
        lv.append(10 * math.log10(float(np.sum(P[m])) + 1e-30))
    return centers, np.array(lv)


def design_eq(x, sr):
    """Returns (sos, info): HPF 35 Hz + a 300 Hz dip sized from the measured mud."""
    hp = signal.butter(8, 35.0, 'highpass', fs=sr, output='sos')
    centers, lv = third_octave_levels(x, sr)
    lo = (centers >= 100) & (centers <= 160)
    hi = (centers >= 600) & (centers <= 1300)
    mud = (centers >= 200) & (centers <= 400)
    # The spectrum's trend through the neighbours (a straight line in dB over log f), and the
    # 200-400 Hz bands' average excess over it.
    xs = np.log2(np.concatenate([centers[lo], centers[hi]]))
    ys = np.concatenate([lv[lo], lv[hi]])
    k, b = np.polyfit(xs, ys, 1)
    excess = float(np.mean(lv[mud] - (k * np.log2(centers[mud]) + b)))
    cut = float(np.clip(excess - 1.0, 1.0, 3.0))
    pk = ml.peaking(300.0, 0.8, -cut, sr)
    info = {'hpf_hz': 35.0, 'hpf_order': 8, 'mud_excess_db': round(excess, 2), 'mud_cut_db': round(cut, 2),
            'mud_center_hz': 300.0, 'mud_q': 0.8}
    return np.vstack([hp, pk]), info


# ---------------------------------------------------------------------------------------- loudness

def to_target(x, sr, target, ceiling, iters=6):
    """Gain + true-peak limiting until the integrated loudness lands on `target` (+-0.05 LU)."""
    gain_db = target - ml.integrated_lufs(x, sr)
    lim_stats, y, lufs = {}, x, None
    c = ceiling
    for _ in range(iters):
        y, lim_stats = ml.limit_true_peak(x * 10 ** (gain_db / 20), sr, ceiling_db=c)
        lufs = ml.integrated_lufs(y, sr)
        tp = ml.true_peak_db(y)
        if tp > ceiling + 0.02:          # the gain curve's own inter-sample overshoot: tighten
            c -= tp - ceiling + 0.02
            continue
        if abs(lufs - target) < 0.05:
            break
        gain_db += target - lufs
    return y, {'gain_db': round(gain_db, 2), 'limiter': lim_stats, 'limiter_ceiling_db': round(c, 3),
               'lufs': round(lufs, 2), 'true_peak_dbtp': round(ml.true_peak_db(y), 2)}


# ------------------------------------------------------------------------------------------- Opus

def _ffmpeg_opus(wav, ogg, bitrate_k):
    cmd = ['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(wav), '-c:a', 'libopus', '-b:a', f'{bitrate_k}k',
           '-vbr', 'on', '-compression_level', '10', '-frame_duration', str(FRAME // 48), '-application', 'audio',
           '-apply_phase_inv', '0',   # no intensity-stereo phase inversion: mono phone speakers sum L+R
           '-ac', '2', '-ar', '48000', '-map_metadata', '-1', str(ogg)]
    subprocess.run(cmd, check=True)
    pages, packets = ml.read_ogg(Path(ogg).read_bytes())
    head = bytearray(packets[0])
    if head[:8] != b'OpusHead' or head[9] != 2:
        raise SystemExit('ffmpeg did not produce a stereo Ogg Opus stream')
    return head, packets


def encode_opus_loop(y, sr, out_path, bitrate_k, tags, serial):
    """Seamless Ogg Opus loop. See the module docstring, step 4. Returns (encoder facts, continuous
    decode of the encoder's own stream across the end of the loop (packet-loop mode) or None)."""
    n = y.shape[0]
    if n < 2 * PRIME_PACKETS * FRAME:
        raise SystemExit(f'loop too short ({n} samples)')
    with tempfile.TemporaryDirectory(prefix='opus-') as td:
        wav, ogg = Path(td) / 'in.wav', Path(td) / 'enc.ogg'
        if n % FRAME == 0:
            K = n // FRAME
            delay = 312                                      # libopus at 48 kHz; checked below
            for _ in range(2):
                pad = (FRAME - delay % FRAME) % FRAME
                ext = np.concatenate([y[n - pad:], y, y, y[:TAIL_PAD]]) if pad else np.concatenate([y, y, y[:TAIL_PAD]])
                ml.write_wav(wav, ext, sr)
                head, packets = _ffmpeg_opus(wav, ogg, bitrate_k)
                got = int.from_bytes(head[10:12], 'little')
                if got == delay:
                    break
                delay = got
            else:
                raise SystemExit('the encoder delay keeps changing')
            # Decoded sample t of the stream (after the encoder's pre-skip) is input sample t; packet j
            # covers stream samples [j*FRAME - delay, (j+1)*FRAME - delay), so pass 1 starts with packet
            # p0 = (pad + delay) / FRAME and pass 2 with p0 + K.
            p0 = (pad + delay) // FRAME
            audio = packets[2:]
            if len(audio) < p0 + 2 * K or any(ml.opus_packet_samples(p) != FRAME for p in audio[:p0 + 2 * K]):
                raise SystemExit('the encoder did not produce fixed 20 ms packets')
            pass1, pass2 = audio[p0:p0 + K], audio[p0 + K:p0 + 2 * K]
            prime = pass2[-PRIME_PACKETS:]
            # The encoder's own continuous decode across the end of pass 2 (into the loop head), for
            # verify_decoded: the looped file must reproduce it at its wrap.
            full = ml.ffmpeg_decode(ogg, sr, 2)
            e2 = pad + 2 * n
            W = 4800
            cont = full[e2 - W:e2 + W] if full.shape[0] >= e2 + W else None
            pre_skip = len(prime) * FRAME
            head[10:12] = pre_skip.to_bytes(2, 'little')
            head[16:18] = (0).to_bytes(2, 'little')         # output gain 0
            vendor, _ = ml.parse_opus_tags(packets[1])
            body = list(prime) + list(pass2)
            granules = [FRAME * (i + 1) for i in range(len(body))]
            facts = {'mode': 'packet-loop', 'encoder_pre_skip': delay, 'grid_pad_samples': pad, 'pre_skip': pre_skip,
                     'prime_packets': len(prime), 'packets': len(pass2), 'end_trim': 0,
                     'identical_packets_pass1_pass2': int(sum(a == b for a, b in zip(pass1, pass2))),
                     'prime_equals_encoder_history': all(a == b for a, b in zip(pass1[-PRIME_PACKETS:], prime))}
        else:
            ext = np.concatenate([y[-PRIME:], y, y[:TAIL_PAD]])
            ml.write_wav(wav, ext, sr)
            head, packets = _ffmpeg_opus(wav, ogg, bitrate_k)
            delay = int.from_bytes(head[10:12], 'little')
            vendor, _ = ml.parse_opus_tags(packets[1])
            pre_skip = delay + PRIME
            if pre_skip > 65535:
                raise SystemExit('pre-skip overflow')
            head[10:12] = pre_skip.to_bytes(2, 'little')
            head[16:18] = (0).to_bytes(2, 'little')
            final = pre_skip + n
            body, granules, cum = [], [], 0
            for p in packets[2:]:
                cum += ml.opus_packet_samples(p)
                body.append(p)
                granules.append(cum)
                if cum >= final:
                    break
            if cum < final:
                raise SystemExit(f'encoder output too short ({cum} < {final} samples)')
            granules[-1] = final                          # end trimming: the stream ends on the loop's last sample
            cont = None
            facts = {'mode': 'circular-prime', 'encoder_pre_skip': delay, 'pre_skip': pre_skip, 'prime_samples': PRIME,
                     'packets': len(body), 'end_trim': cum - final,
                     'note': f'loop length {n} is not a multiple of {FRAME} samples: the codec noise splices at the wrap'}
    mux = {'head': bytes(head), 'vendor': vendor, 'body': body, 'granules': granules, 'serial': serial, 'mode': facts['mode']}
    data = write_tagged(out_path, mux, tags)
    facts.update({'bytes': len(data), 'kbps': round(len(data) * 8 / (n / sr) / 1000, 1), 'vendor': vendor})
    return facts, cont, mux


def write_tagged(out_path, mux, tags):
    """(Re)writes the Ogg Opus file from the encoded packets in `mux` with these tags: the audio packets,
    granules and pre-skip stay byte-identical, so the decode does not change."""
    tags = {**tags, 'LOOPMODE': mux['mode']}
    data = ml.write_ogg([mux['head'], ml.opus_tags(mux['vendor'], tags)] + list(mux['body']), [-1, -1] + list(mux['granules']), mux['serial'])
    Path(out_path).write_bytes(data)
    return data


def verify_decoded(path, y, sr, bar_seconds, cont=None):
    """Decodes the .ogg with ffmpeg and checks it against the master `y`, and (packet-loop mode) its
    wrap against `cont`, the encoder stream's own continuous decode across the end of the loop.
    bar_seconds: the bar length (one meter) or the loop's bar starts in seconds (ml.seam_metrics)."""
    info = ml.ogg_opus_info(path)
    d = ml.ffmpeg_decode(path, sr, 2)
    n = y.shape[0]
    res = {'container_samples': info['samples'], 'decoded_samples': int(d.shape[0]), 'loop_samples': n,
           'length_error_ms': round((d.shape[0] - n) / sr * 1000, 3)}
    m = min(n, d.shape[0])
    res['lag_samples'] = ml.best_lag(y[:m], d[:m], max_lag=2048)
    e = d[:m] - y[:m]
    res['codec_snr_db'] = round(10 * math.log10(float(np.sum(y[:m] ** 2)) / (float(np.sum(e ** 2)) + 1e-30)), 1)
    # Codec error around the wrap vs. the typical codec error: a cold encoder start or a missing
    # end would show up here.
    w = int(0.02 * sr)
    err = np.sum(e ** 2, axis=1)
    ref = np.sum(y[:m] ** 2, axis=1)
    wins = [(float(np.sum(err[s:s + w])) + 1e-20) / (float(np.sum(ref[s:s + w])) + 1e-20) for s in range(w, m - 2 * w, w)]
    edge = (float(np.sum(err[:w]) + np.sum(err[m - w:m])) + 1e-20) / (float(np.sum(ref[:w]) + np.sum(ref[m - w:m])) + 1e-20)
    res['wrap_codec_error_vs_median_db'] = round(10 * math.log10(edge / float(np.median(wins))), 2)
    res['seam'] = ml.seam_metrics(d, sr, bar_seconds)
    # In packet-loop mode the codec noise is continuous across the wrap, so the lossless limits apply.
    res['seam_verdict'], res['seam_notes'] = ml.seam_verdict(res['seam'], lossy=cont is None)
    if cont is not None and m == n:
        W = cont.shape[0] // 2
        wrap = np.concatenate([d[n - W:], d[:W]])
        diff = float(np.abs(wrap - cont).max())
        err_rms = math.sqrt(float(np.mean(e ** 2))) + 1e-20
        res['wrap_vs_continuous_decode'] = {'max_abs_diff': float(f'{diff:.3e}'),
                                            'db_re_codec_error_rms': round(float(ml.db(diff / err_rms, 1e-15)), 1)}
        if diff > 0.1 * err_rms:
            res['seam_verdict'] = 'FAIL'
            res['seam_notes'].append(f'the decoded wrap differs from a continuous decode by {diff:.2e} (codec error rms {err_rms:.2e})')
    if res['wrap_codec_error_vs_median_db'] > 3.0:
        res['seam_verdict'] = 'FAIL'
        res['seam_notes'].append(f"codec error at the wrap {res['wrap_codec_error_vs_median_db']} dB over the median (cold encoder start or trimmed end)")
    res['lufs'] = round(ml.integrated_lufs(d, sr), 2)
    res['true_peak_dbtp'] = round(ml.true_peak_db(d), 2)
    res['sample_peak_dbfs'] = round(float(ml.db(np.abs(d).max())), 2)
    return res, d


# ------------------------------------------------------------------------------------- reference

def measure_reference(ref_dir):
    out = {}
    for name in ['title', 'battle', 'lastStand']:
        f = Path(ref_dir) / f'{name}.procedural.wav'
        if not f.exists():
            continue
        x, sr = ml.read_wav(f)
        x2 = np.repeat(x, 2, axis=1) if x.shape[1] == 1 else x   # the game plays the mono loop as L = R
        out[name] = {'sr': sr, 'seconds': round(x.shape[0] / sr, 3), 'lufs_dual_mono': round(ml.integrated_lufs(x2, sr), 2),
                     'true_peak_dbtp': round(ml.true_peak_db(x2), 2), 'lra': round(ml.loudness_range(x2, sr), 2)}
    vals = [v['lufs_dual_mono'] for k, v in out.items() if k in ('title', 'battle')]
    if vals:
        pm = 10 * math.log10(np.mean([10 ** (v / 10) for v in vals]))
        out['_title_battle_power_mean'] = round(pm, 2)
        out['_suggested_target'] = round(pm * 2) / 2
    print(json.dumps(out, indent=2))


# ------------------------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('wav', nargs='?')
    ap.add_argument('--cue')
    ap.add_argument('--bpm', type=float)
    ap.add_argument('--target', type=float, default=TARGET_LUFS)
    ap.add_argument('--ceiling', type=float, default=CEILING_DBTP)
    ap.add_argument('--bitrate', type=int, default=80)
    ap.add_argument('--out-dir', default=str(MUSIC_DIR))
    ap.add_argument('--report-dir')
    ap.add_argument('--bank-out')
    ap.add_argument('--no-bank', action='store_true')
    ap.add_argument('--measure-reference', metavar='DIR')
    a = ap.parse_args()
    if a.measure_reference:
        return measure_reference(a.measure_reference)
    if not a.wav:
        ap.error('a rendered <cue>.loop.wav is required')
    wav = Path(a.wav)
    rj = wav.with_name(wav.name.replace('.loop.wav', '.render.json'))
    render = json.loads(rj.read_text()) if rj.exists() else {}
    cue = a.cue or render.get('cue') or wav.name.split('.')[0]
    bpm = a.bpm or render.get('bpm')
    if not bpm:
        raise SystemExit('bpm unknown: pass --bpm or keep the render.json next to the WAV')
    report_dir = Path(a.report_dir) if a.report_dir else wav.parent
    out_dir = Path(a.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)

    x, sr = ml.read_wav(wav)
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    if sr != SR:
        x, sr = ml.resample(x, sr, SR), SR
    if render.get('loopSamples') and render['loopSamples'] != x.shape[0]:
        raise SystemExit(f"{wav}: {x.shape[0]} samples, render.json says {render['loopSamples']}")
    n = x.shape[0]
    # The time-signature map ('4/4' or e.g. '4/4,5/4@21,4/4@29') and the bar starts (seconds from the
    # loop start) from render.json; without them, laid out from the map and the tempo.
    meter = render.get('meter', '4/4')
    grid = ml.bar_lines(meter, bpm, n, SR, render.get('bars'))
    bar_secs = render.get('barLinesSeconds') or [s / SR for s in grid['starts']]
    bars = render.get('bars') or grid['bars']
    quarters = render.get('quarterNotes') or grid['quarters']
    if abs(grid['error_samples']) > 0.001 * SR or grid['bars'] != bars or len(bar_secs) != bars:
        raise SystemExit(f'{wav}: meter {meter} at {bpm:g} bpm gives {grid["bars"]} bars = {grid["samples"]} samples, '
                         f'the loop is {bars} bars = {n} samples')
    rep = {'cue': cue, 'input': str(wav), 'loop_samples': n, 'loop_seconds': n / SR, 'bpm': bpm, 'meter': meter,
           'bars': bars, 'quarter_notes': quarters, 'target_lufs': a.target, 'ceiling_dbtp': a.ceiling}
    rep['input_stats'] = {'lufs': round(ml.integrated_lufs(x, sr), 2), 'true_peak_dbtp': round(ml.true_peak_db(x), 2),
                          'lra': round(ml.loudness_range(x, sr), 2)}
    log(f'[{cue}] {n} samples ({n / SR:.3f} s), input {rep["input_stats"]}')

    sos, rep['eq'] = design_eq(x, sr)
    y = ml.circ_sos(x, sos)
    y, rep['glue'] = ml.glue_compress(y, sr, ratio=2.0, gr_db=2.0, attack_ms=30.0, release_ms=300.0)
    pre = y
    serial = zlib.crc32(f'thermopylae-music-{cue}'.encode()) & 0x7FFFFFFF
    ogg_path = out_dir / f'{cue}.ogg'
    # The codec adds a few tenths of a dB of overshoot: the limiter's ceiling is lowered until the
    # DECODED file stays under --ceiling (at the same loudness).
    lim_ceiling = a.ceiling
    rep['encode_passes'] = []

    def tags_for(tp_dbtp):
        return {'TITLE': f'Thermopylae - {cue}', 'CUE': cue, 'LOOPSTART': 0, 'LOOPLENGTH': n, 'BPM': f'{bpm:g}',
                'METER': meter, 'BARS': bars, 'BEATS': f'{quarters:g}', 'LUFS': f"{rep['loudness']['lufs']:.1f}",
                'TRUEPEAK_DBTP': f'{tp_dbtp:.2f}',
                'COMMENT': 'Rendered with the GeneralUser GS v1.471 SoundFont by S. Christian Collins (spessasynth_core). Seamless loop.'}

    for attempt in range(5):
        y, rep['loudness'] = to_target(pre, sr, a.target, lim_ceiling)
        rep['opus'], cont, mux = encode_opus_loop(y, sr, ogg_path, a.bitrate, tags_for(rep['loudness']['true_peak_dbtp']), serial)
        rep['decoded'], _ = verify_decoded(ogg_path, y, sr, bar_secs, cont)
        tp = rep['decoded']['true_peak_dbtp']
        rep['encode_passes'].append({'limiter_ceiling_db': round(lim_ceiling, 2), 'master_tp_dbtp': rep['loudness']['true_peak_dbtp'],
                                     'decoded_tp_dbtp': tp})
        # The DECODED file must stay at or under the ceiling (the codec overshoots the limiter by a few tenths
        # of a dB); when it does not, the limiter's ceiling comes down by the excess plus 0.05 dB and the
        # loop is limited and encoded again.
        if tp <= a.ceiling:
            break
        lim_ceiling -= tp - a.ceiling + 0.05
        log(f'[{cue}] decoded true peak {tp} dBTP over {a.ceiling}: limiter ceiling down to {lim_ceiling:.2f} dB')
    # The tags record what ships: the decoded file's true peak (the audio packets are not touched).
    tags = tags_for(rep['decoded']['true_peak_dbtp'])
    data = write_tagged(ogg_path, mux, tags)
    _, packets = ml.read_ogg(data)
    if packets[2:] != list(mux['body']) or ml.ogg_opus_info(ogg_path)['samples'] != n:
        raise SystemExit(f'{ogg_path}: rewriting the tags changed the audio packets')
    rep['opus']['bytes'] = len(data)
    rep['opus']['kbps'] = round(len(data) * 8 / (n / sr) / 1000, 1)
    rep['tags'] = {k: str(v) for k, v in tags.items()}
    rep['master_seam'] = ml.seam_metrics(y, sr, bar_secs)
    rep['master_seam_verdict'], rep['master_seam_notes'] = ml.seam_verdict(rep['master_seam'])
    log(f'[{cue}] EQ {rep["eq"]}, glue {rep["glue"]}, loudness {rep["loudness"]}')
    ml.write_wav(report_dir / f'{cue}.master.wav', y, sr)
    dec = rep['decoded']
    problems = []
    if abs(dec['length_error_ms']) > 1.0:
        problems.append(f"decoded length {dec['decoded_samples']} samples, the loop is {n}")
    if dec['lag_samples'] != 0:
        problems.append(f"decoded audio is shifted by {dec['lag_samples']} samples")
    if rep['master_seam_verdict'] == 'FAIL':
        problems.append('master seam: ' + '; '.join(rep['master_seam_notes']))
    if dec['seam_verdict'] == 'FAIL':
        problems.append('decoded seam: ' + '; '.join(dec['seam_notes']))
    if dec['true_peak_dbtp'] > a.ceiling:
        problems.append(f"decoded true peak {dec['true_peak_dbtp']} dBTP over the {a.ceiling} dBTP ceiling")
    if abs(dec['lufs'] - a.target) > 0.5:
        problems.append(f"decoded loudness {dec['lufs']} LUFS vs target {a.target}")
    rep['problems'] = problems
    rep['warnings'] = list(dec['seam_notes']) if dec['seam_verdict'] == 'WARN' else []
    if rep['opus']['mode'] != 'packet-loop':
        rep['warnings'].append(rep['opus']['note'])
    (report_dir / f'{cue}.master.json').write_text(json.dumps(rep, indent=2) + '\n')
    log(f'[{cue}] wrote {ogg_path} ({rep["opus"]["bytes"]} bytes, {rep["opus"]["kbps"]} kb/s); decoded: '
        f"{dec['decoded_samples']} samples, lag {dec['lag_samples']}, {dec['lufs']} LUFS, {dec['true_peak_dbtp']} dBTP, "
        f"seam {dec['seam_verdict']}, {rep['opus']['mode']}, wrap vs continuous decode "
        f"{dec.get('wrap_vs_continuous_decode', {}).get('max_abs_diff', 'n/a')}")

    if not a.no_bank:
        bank_out = Path(a.bank_out) if a.bank_out else (BANK if out_dir.resolve() == MUSIC_DIR.resolve() else None)
        if bank_out is None:
            log(f'[{cue}] --out-dir is not the game directory and no --bank-out: musicbank.js not written')
        else:
            subprocess.run(['node', str(HERE / 'render.mjs'), '--bank', '--music-dir', str(out_dir), '--bank-out', str(bank_out)],
                           check=True, stdout=sys.stderr)   # stdout stays the JSON summary
    print(json.dumps({'cue': cue, 'ogg': str(ogg_path), 'loop_samples': n, 'lufs': dec['lufs'], 'true_peak_dbtp': dec['true_peak_dbtp'],
                      'limiter_gr_max_db': rep['loudness']['limiter']['gr_max_db'], 'seam': dec['seam_verdict'],
                      'opus_mode': rep['opus']['mode'], 'kbps': rep['opus']['kbps'], 'problems': problems,
                      'warnings': rep['warnings']}, indent=2))
    if problems:
        sys.exit(1)


if __name__ == '__main__':
    main()
