#!/usr/bin/env python3
"""QA for the rendered voice clips (run after render.py).

Per clip: duration, F0 median/p10/p90 (pYIN), voiced ratio, sample peak and true peak, integrated
loudness, clipped samples, leading/trailing silence and the longest inner pause, spectral flatness
and centroid of voiced frames; DNSMOS P.835 SIG/BAK/OVRL (a non-intrusive speech-quality predictor,
used here as an artifact detector) when its model is installed; and, with --asr, a Whisper-small
transcript (Greek) scored three ways: against the line's IPA with a coarse phonetic character error
rate (Whisper hears Ancient Greek through Modern Greek spelling, so the score compares sound classes,
not spelling), against the line's monotonic Greek spelling (text CER: case, accents, punctuation and
spaces ignored) and against the Modern Greek sounds of that spelling (sound CER), plus Whisper's own
language identification of the clip (P(Greek) and its top guess). Per speaker: F0 and MFCC
centroids, and how far apart the five voices are.

    <venv>/bin/python -I spartan/tools/voice/qa.py [--root <game root>] [--asr] [--ipa-field ipa_el|ipa] [--report qa.json]

--ipa-field defaults to ipa_el, the Modern Greek reading the game's clips are rendered from; pass
--ipa-field ipa for clips of the reconstructed reading (render.py --reading reconstructed). Error
rates are capped at 1 per clip, so one looping transcript cannot swamp the means.
"""
from __future__ import annotations

import argparse
import importlib.util
import itertools
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location('voice_render', HERE / 'render.py')
R = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R)

# ----------------------------------------------------------------------------- coarse phonetic CER

_MN = lambda s: ''.join(c for c in unicodedata.normalize('NFD', s) if unicodedata.category(c) != 'Mn')
_GR_DIGRAPHS = [('ου', 'u'), ('αι', 'e'), ('ει', 'i'), ('οι', 'i'), ('υι', 'i'), ('μπ', 'b'), ('ντ', 'nt'),
                ('γκ', 'g'), ('γγ', 'g'), ('αυ', 'av'), ('ευ', 'ev'), ('τσ', 'ts'), ('τζ', 'dz')]
_GR = dict(zip('αβγδεζηθικλμνξοπρστυφχψως',
               ['a', 'v', 'g', 'd', 'e', 'z', 'i', 'T', 'i', 'k', 'l', 'm', 'n', 'ks', 'o', 'p', 'r', 's', 't', 'i',
                'f', 'x', 'ps', 'o', 's']))
_IPA = [('tʰ', 'T'), ('pʰ', 'f'), ('kʰ', 'x'), ('ð', 'd'), ('θ', 'T'), ('ʎ', 'li'), ('ɣ', 'g'), ('ɡ', 'g'),
        ('β', 'v'), ('ɸ', 'f'), ('ɛ', 'e'), ('ɔ', 'o'), ('y', 'i'), ('h', ''), ('ː', ''), ('ˈ', ''), ('ˌ', ''),
        ('ŋ', 'n'), ('zd', 'z'),
        # Modern Greek reading (ipa_el; none of these occur in the reconstructed ipa): palatals as their letters
        # (ç is c after accent stripping; κ, χ and γ are written alike before front vowels), tap r
        ('ʲ', ''), ('c', 'k'), ('ʝ', 'g'), ('ɟ', 'g'), ('ɲ', 'n'), ('ɾ', 'r')]
# sound classes Whisper's Modern Greek spelling cannot tell apart from the ancient sounds
_CLASSES = str.maketrans({'b': 'v', 'x': 'k'})


def greek_loose(s: str) -> str:
    s = unicodedata.normalize('NFD', s.lower())
    # an accent on the first vowel or a diaeresis on the second splits a would-be digraph (άι, αϊ = a-i)
    s = re.sub('([αεο])\u0301([ιυ])', r'\1|\2', s)
    s = re.sub('([αεο])([ιυ])\u0308', r'\1|\2', s)
    s = re.sub(r'[^\w |]', '', _MN(s))
    for a, b in _GR_DIGRAPHS:
        s = s.replace(a, b)
    s = ''.join(_GR.get(c, c if c.isascii() else '') for c in s).replace('|', '').replace(' ', '')
    return _coarse(s)


def ipa_loose(s: str) -> str:
    s = _MN(s)
    for a, b in _IPA:
        s = s.replace(a, b)
    s = re.sub(r'[^\w]', '', s)
    return _coarse(s.replace('ei', 'i').replace('oi', 'i').replace('ou', 'u'))


def _coarse(s: str) -> str:
    """Classes Whisper's Modern Greek spelling cannot keep apart: b/v/mb, d/nd, g/ng, k/x, ai/e,
    single/double letters."""
    s = re.sub(r'(.)\1+', r'\1', s.translate(_CLASSES))
    for a, b in (('mp', 'v'), ('mv', 'v'), ('nt', 'd'), ('nd', 'd'), ('nk', 'g'), ('ng', 'g'), ('ai', 'e')):
        s = s.replace(a, b)
    return s


def edit_rate(ref: str, hyp: str) -> float:
    d = list(range(len(hyp) + 1))
    for i, rc in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
    return d[len(hyp)] / max(1, len(ref))


def text_cer(ref: str, hyp: str) -> float:
    norm = lambda s: re.sub(r'\s+', ' ', re.sub(r'[^\w ]', '', _MN(s.lower()).replace('ς', 'σ'))).strip()
    return edit_rate(norm(ref), norm(hyp))


def spelling_cer(ref: str, hyp: str) -> float:
    """CER on the letters only (case, accents, punctuation and word spacing ignored): Whisper splits
    ancient phrases into words arbitrarily ("μενάνθρωποι"), which says nothing about the sounds."""
    norm = lambda s: re.sub(r'[\W_]', '', _MN(s.lower()).replace('ς', 'σ'))
    return edit_rate(norm(ref), norm(hyp))


def sound_cer(ref: str, hyp: str) -> float:
    """CER between the Modern Greek sounds of two Greek spellings (η/ι/υ/ει/οι = i, ω = o, αι = e ...)."""
    return edit_rate(greek_loose(ref), greek_loose(hyp))


def _monotonic():
    """render_google.monotonic: the polytonic line in modern monotonic spelling (the text-CER reference)."""
    spec = importlib.util.spec_from_file_location('voice_render_google', HERE / 'render_google.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.monotonic


# ----------------------------------------------------------------------------- ASR

def asr_paths(asr_dir: Path):
    entry = asr_dir / 'node' / 'node_modules' / '@huggingface' / 'transformers' / 'dist' / 'transformers.node.mjs'
    models = asr_dir / 'models'
    ok = entry.exists() and (models / 'Xenova' / 'whisper-small' / 'onnx' / 'encoder_model_quantized.onnx').exists()
    return (entry, models) if ok else None


def run_asr(items, asr_dir: Path):
    """items: [(id, float array at 48 kHz)] -> ({id: transcript}, {id: {language: probability}});
    ({}, {}) when ASR is not set up."""
    from scipy import signal
    paths = asr_paths(asr_dir)
    if not paths:
        print(f'ASR not set up in {asr_dir} (run setup.sh --asr); skipping intelligibility.', file=sys.stderr)
        return {}, {}
    with tempfile.TemporaryDirectory() as td:
        lst = []
        for i, (cid, y) in enumerate(items):
            f = Path(td) / f'{i}.f32'
            signal.resample_poly(np.asarray(y, np.float64), 1, 3).astype('<f4').tofile(f)
            lst.append({'id': cid, 'f32': str(f), 'lang': 'greek'})
        (Path(td) / 'list.json').write_text(json.dumps(lst))
        subprocess.run(['node', str(HERE / 'asr.mjs'), str(paths[0]), str(paths[1]), str(Path(td) / 'list.json'),
                        str(Path(td) / 'out.json'), str(Path(td) / 'lid.json')], check=True)
        return (json.loads((Path(td) / 'out.json').read_text(encoding='utf-8')),
                json.loads((Path(td) / 'lid.json').read_text(encoding='utf-8')))


# ----------------------------------------------------------------------------- DNSMOS

class DNSMOS:
    """DNSMOS P.835 (Reddy et al. 2022), the sig_bak_ovr.onnx model: 9.01 s windows at 16 kHz, short
    clips tiled (as Microsoft's dnsmos_local.py does), raw scores mapped by its published polynomials."""
    LEN = round(9.01 * 16000)

    def __init__(self, model: Path):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = 4
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(str(model), so, providers=['CPUExecutionProvider'])

    def __call__(self, y, sr=R.SR_OUT):
        from scipy import signal
        a = signal.resample_poly(np.asarray(y, np.float64), 1, sr // 16000).astype(np.float32)
        while len(a) < self.LEN:
            a = np.concatenate([a, a])
        n = len(a) // self.LEN
        raw = np.mean([self.sess.run(None, {'input_1': a[i * self.LEN:(i + 1) * self.LEN][None]})[0][0]
                       for i in range(n)], axis=0)
        s, b, o = (float(v) for v in raw)
        return {'mos_sig': round(-0.08397278 * s * s + 1.22083953 * s + 0.0052439, 2),
                'mos_bak': round(-0.13166888 * b * b + 1.60915514 * b - 0.39604546, 2),
                'mos_ovrl': round(-0.06766283 * o * o + 1.11546468 * o + 0.04602535, 2)}


# ----------------------------------------------------------------------------- main

def speaker_table(clips):
    """Per speaker F0 (overall and per delivery) and MFCC centroids, and how far apart each pair is.

    Pitch and spectrum depend on the delivery as much as on the voice, so a pair is compared like
    with like: on the lines both say (same key and variant) when there are any, else on the
    delivery kinds both use, else overall. MFCC distance is Euclidean over MFCC 1-12 means of the
    voiced frames; `within` is the typical distance between two lines of one speaker in one
    delivery, the yardstick for what 'clearly different' means."""
    sp = {}
    for c in clips:
        if c.get('mfcc_mean') and c.get('f0_median'):
            sp.setdefault(c['speaker'], []).append(c)
    vec = lambda cs: np.mean([c['mfcc_mean'] for c in cs], axis=0)
    f0 = lambda cs: float(np.median([c['f0_median'] for c in cs]))
    cent = {}
    for s, v in sp.items():
        kinds = {}
        for c in v:
            kinds.setdefault(c['delivery'], []).append(c)
        cent[s] = {'n': len(v), 'f0_median': round(f0(v), 1),
                   'f0_by_delivery': {k: round(f0(cs), 1) for k, cs in sorted(kinds.items())},
                   'centroid_hz': round(float(np.mean([c['centroid_hz'] for c in v])), 0), 'mfcc': vec(v)}
    within = [float(np.linalg.norm(np.array(a['mfcc_mean']) - np.array(b['mfcc_mean'])))
              for v in sp.values() for a, b in itertools.combinations(v, 2)
              if a['delivery'] == b['delivery'] and (a['key'], a['variant']) != (b['key'], b['variant'])]
    pairs = []
    for a, b in itertools.combinations(sorted(sp), 2):
        la = {(c['key'], c['variant']): c for c in sp[a]}
        lb = {(c['key'], c['variant']): c for c in sp[b]}
        shared = sorted(set(la) & set(lb))
        if shared:
            groups, basis = [([la[k]], [lb[k]]) for k in shared], f'{len(shared)} shared lines'
        else:
            ka = {c['delivery'] for c in sp[a]} & {c['delivery'] for c in sp[b]}
            if ka:
                groups = [([c for c in sp[a] if c['delivery'] == k], [c for c in sp[b] if c['delivery'] == k]) for k in sorted(ka)]
                basis = 'same delivery: ' + ', '.join(sorted(ka))
            else:
                groups, basis = [(sp[a], sp[b])], 'overall'
        pairs.append({'a': a, 'b': b, 'basis': basis,
                      'mfcc_dist': round(float(np.mean([np.linalg.norm(vec(x) - vec(y)) for x, y in groups])), 1),
                      'f0_st': round(float(np.mean([abs(12 * math.log2(f0(x) / f0(y))) for x, y in groups])), 1)})
    for v in cent.values():
        v['mfcc'] = [round(float(x), 2) for x in v['mfcc']]
    return cent, pairs, (round(float(np.median(within)), 1) if within else None)


def main(argv=None):
    ap = argparse.ArgumentParser(description='QA metrics for the rendered voice clips')
    ap.add_argument('--root', type=Path, default=R.GAME_ROOT, help='game root holding audio/voice (default: the repo)')
    ap.add_argument('--lines', type=Path, default=HERE / 'lines.json')
    ap.add_argument('--ipa-field', default='ipa_el', help="the pronunciation the clips were rendered from: 'ipa_el' "
                    "(Modern Greek reading, voices_el.json; the game's clips, default) or 'ipa' (reconstructed, voices.json)")
    ap.add_argument('--report', type=Path, default=None, help='metrics JSON (default <root>/voice-qa.json outside the repo, '
                                                                 'tools/voice/qa-report.json for the repo)')
    ap.add_argument('--asr', action='store_true', help='add Whisper-small transcripts (needs setup.sh --asr)')
    ap.add_argument('--asr-dir', type=Path, default=R.default_cache() / 'asr')
    ap.add_argument('--strict', action='store_true', help='exit 1 when a hard check fails')
    args = ap.parse_args(argv)

    keys, variants = R.load_lines(args.lines, args.ipa_field)
    monotonic = _monotonic()
    mos_model = args.asr_dir / 'dnsmos' / 'sig_bak_ovr.onnx'
    mos = DNSMOS(mos_model) if mos_model.exists() else None
    if not mos:
        print(f'DNSMOS model not found at {mos_model} (setup.sh --asr installs it); skipping the quality score.',
              file=sys.stderr)
    clips, items = [], []
    for key in keys:
        for ln in variants[key]:
            for sp in ln['speakers']:
                rel = R.clip_rel(key, ln['variant'], sp)
                path = args.root / rel
                if not path.exists():
                    print(f'missing {rel}', file=sys.stderr)
                    continue
                y = R.decode(path)
                m = R.clip_metrics(y)
                if mos:
                    m.update(mos(y))
                m.update(key=key, variant=ln['variant'], speaker=sp, file=rel, bytes=path.stat().st_size,
                         ipa_field=args.ipa_field, ipa=R.line_ipa(ln, sp, args.ipa_field), caps=ln['caps'],
                         text=monotonic(ln.get('spoken') or ln['polytonic']), delivery=R.line_kind(ln))
                if ln.get('asr_ref'):
                    m['asr_ref'] = ln['asr_ref']
                clips.append(m)
                items.append((rel, y))
    if args.asr:
        tr, lid = run_asr(items, args.asr_dir)
        for m in clips:
            if m['file'] in tr:
                # error rates are capped at 1: a transcript stuck in a loop ('Αλλά λεεεε...') can run to 15+,
                # and one such clip would swamp the means
                m['asr'] = tr[m['file']]
                m['asr_loose_cer'] = round(min(1.0, edit_rate(ipa_loose(m['ipa']), greek_loose(m['asr']))), 3)
                m['asr_text_cer'] = round(min(1.0, spelling_cer(m['text'], m['asr'])), 3)
                m['asr_sound_cer'] = round(min(1.0, sound_cer(m['text'], m['asr'])), 3)
                if m.get('asr_ref'):
                    m['asr_cer'] = round(min(1.0, text_cer(m['asr_ref'], m['asr'])), 3)
            if lid.get(m['file']):   # {} when the language identification failed for this clip
                m['asr_lang'] = lid[m['file']]
                m['asr_p_el'] = lid[m['file']].get('el', 0.0)

    hard = []
    for m in clips:
        tag = m['file']
        if abs(m['lufs'] - R.LOUDNESS_TARGET) > 0.5:
            hard.append(f'{tag}: loudness {m["lufs"]} LUFS')
        if m['true_peak_dbtp'] > R.TRUE_PEAK_MAX + 0.05:
            hard.append(f'{tag}: true peak {m["true_peak_dbtp"]} dBTP')
        if m['clipped_samples']:
            hard.append(f'{tag}: {m["clipped_samples"]} clipped samples')
        if (m['lead_silence_ms'] or 0) > 150 or (m['trail_silence_ms'] or 0) > 200:
            hard.append(f'{tag}: silence {m["lead_silence_ms"]}/{m["trail_silence_ms"]} ms')
        if m['max_gap_ms'] > 800:
            hard.append(f'{tag}: {m["max_gap_ms"]} ms pause inside the clip')
        if 'mos_sig' in m and m['mos_sig'] < 2.5:
            hard.append(f'{tag}: DNSMOS SIG {m["mos_sig"]} (audible distortion likely)')
    cent, pairs, within = speaker_table(clips)
    report = {'clips': clips, 'speakers': cent, 'speaker_pairs': pairs, 'mfcc_within_speaker': within,
              'failures': hard}
    out = args.report or (R.HERE / 'qa-report.json' if args.root.resolve() == R.GAME_ROOT.resolve()
                          else args.root / 'voice-qa.json')
    out.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')

    print(f'{"clip":40s} {"dur":>5s} {"LUFS":>6s} {"dBTP":>6s} {"F0med":>6s} {"p10":>5s} {"p90":>5s} {"voic":>5s} '
          f'{"flat":>6s} {"SIG":>5s} {"OVRL":>5s}  asr')
    for m in clips:
        print(f'{m["file"][12:]:40s} {m["dur_s"]:5.2f} {m["lufs"]:6.1f} {m["true_peak_dbtp"]:6.1f} '
              f'{m.get("f0_median", 0):6.0f} {m.get("f0_p10", 0):5.0f} {m.get("f0_p90", 0):5.0f} {m["voiced_ratio"]:5.2f} '
              f'{(m["flatness_voiced"] or 0):6.3f} {m.get("mos_sig", 0):5.2f} {m.get("mos_ovrl", 0):5.2f}  '
              f'{m.get("asr", "")} {("[" + str(m["asr_loose_cer"]) + "]") if "asr_loose_cer" in m else ""}'
              + (f' text {m["asr_text_cer"]} P(el) {m.get("asr_p_el", 0):.2f} ({next(iter(m["asr_lang"]))})' if "asr_lang" in m else ''))
    scored = [m for m in clips if 'asr_text_cer' in m]
    if scored:
        mean = lambda f, ms: sum(m.get(f, 0.0) for m in ms) / len(ms)
        print(f'\nWhisper, {len(scored)} clips: text CER mean {mean("asr_text_cer", scored):.3f} (median '
              f'{float(np.median([m["asr_text_cer"] for m in scored])):.3f}), sound CER '
              f'{mean("asr_sound_cer", scored):.3f}, loose CER vs {args.ipa_field} {mean("asr_loose_cer", scored):.3f}, '
              f'P(Greek) mean {mean("asr_p_el", scored):.2f}, Greek the top language on '
              f'{sum(1 for m in scored if m.get("asr_lang") and next(iter(m["asr_lang"])) == "el")}/{len(scored)}')
        for s in sorted({m['speaker'] for m in scored}):
            ms = [m for m in scored if m['speaker'] == s]
            print(f'  {s:10s} {len(ms):2d} clips  text CER {mean("asr_text_cer", ms):.3f}  sound CER '
                  f'{mean("asr_sound_cer", ms):.3f}  P(Greek) {mean("asr_p_el", ms):.2f}  '
                  f'LUFS {min(m["lufs"] for m in ms):.2f}..{max(m["lufs"] for m in ms):.2f}')
    print('\nspeakers (F0 median by delivery, spectral centroid):')
    for s, v in cent.items():
        print(f'  {s:10s} ' + ', '.join(f'{k} {f} Hz' for k, f in v['f0_by_delivery'].items())
              + f'; centroid {v["centroid_hz"]:.0f} Hz')
    print(f'pairs (MFCC distance between two lines of one speaker: median {within}):')
    for p in pairs:
        print(f'  {p["a"]:10s} vs {p["b"]:10s}  MFCC distance {p["mfcc_dist"]:5.1f}  F0 apart {p["f0_st"]:4.1f} st'
              f'  ({p["basis"]})')
    print(f'\n{len(clips)} clips, {len(hard)} hard-check failures' + ''.join('\n  ' + h for h in hard))
    print(f'wrote {out}')
    if args.strict and hard:
        sys.exit(1)


if __name__ == '__main__':
    main()
