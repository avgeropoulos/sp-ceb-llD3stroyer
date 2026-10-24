#!/usr/bin/env python3
"""Offline renderer for Thermopylae's Ancient Greek voice lines.

lines.json (what is said) + a recipe file (who says it, and how: voices_el.json by default, or
voices.json) -> one mono Ogg Opus clip per (line, speaker) under the game's audio/voice/, plus the
generated js/audio/voicebank.js.

Synthesis is Kokoro-82M v1.0 (Apache-2.0, int8 ONNX), driven straight from the IPA in
lines.json. The ONNX graph is patched in memory (never saved into the repo) so that the
renderer can scale every phoneme's duration, multiply the pitch (F0) contour and raise the
energy contour per phoneme: that is how the Ancient Greek pitch accent, the shouted commands and
the per-speaker pitch are made. Formants (vocal-tract size) are moved without artifacts by
synthesizing a little faster and higher and resampling the result. Post-processing (EQ,
saturation, compression, silence trim, EBU R128 loudness, true-peak limiting) is plain numpy /
scipy, so a render is bit-for-bit repeatable on the same machine.

Run tools/voice/setup.sh once (model + venv), then:
    <venv>/bin/python -I spartan/tools/voice/render.py              # everything (Modern Greek reading)
    ... render.py --only warcry                                     # one key (voicebank.js is rebuilt)
    ... render.py --out /tmp/x --qa                                 # render elsewhere + metrics JSON
    ... render.py --reading reconstructed --out /tmp/old            # the old 5th-century reading

Two readings share the script: the Modern Greek school reading (lines.json "ipa_el",
voices_el.json; the default and what the game ships) and the reconstructed 5th-century
pronunciation (lines.json "ipa", voices.json). A recipe file names the field it was tuned for
("ipa_field"); --ipa-field overrides it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

import numpy as np
from scipy import signal
from scipy.ndimage import minimum_filter1d

HERE = Path(__file__).resolve().parent
SPARTAN = HERE.parent.parent
GAME_ROOT = SPARTAN / 'src' / 'main' / 'assets' / 'game'
VOICE_DIR_REL = 'audio/voice'
VOICEBANK_REL = 'js/audio/voicebank.js'

MODEL_FILE = 'kokoro-quantized.onnx'
MODEL_SHA256 = 'fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478'
SR_MODEL = 24000          # Kokoro output rate
SR_OUT = 48000            # Opus native rate; all post-processing runs here
FRAME = 600               # samples (at 24 kHz) per Kokoro duration unit: 25 ms
ORT_THREADS = 4           # fixed so that renders repeat bit-for-bit on one machine

LOUDNESS_TARGET = -16.0   # LUFS integrated
PRE_LEVEL = -20.0         # LUFS the raw synthesis is set to before saturation and compression
TRUE_PEAK_MAX = -1.5      # dBTP, measured on the decoded Opus clip
OPUS_BITRATE = '48k'


# ----------------------------------------------------------------------------- paths

def default_cache() -> Path:
    env = os.environ.get('THERMOPYLAE_VOICE_CACHE')
    if env:
        return Path(env)
    base = os.environ.get('XDG_CACHE_HOME') or str(Path.home() / '.cache')
    return Path(base) / 'thermopylae-voice'


def default_model_dir() -> Path:
    return default_cache() / 'kokoro'


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


# ----------------------------------------------------------------------------- IPA -> Kokoro tokens

VOWELS = set('aeiouyɛɔɑɐəɪʊæœøɜɞʌɒɨʉɯɤ')
STRESS = {'ˈ', 'ˌ'}
PUNCT = set(';:,.!?—…')
# Combining tone marks on a vowel (Wiktionary-style Attic IPA): acute = high, circumflex = high
# then falling inside the long vowel or diphthong, caron = rising (acute on a long syllable),
# grave = the lowered accent of a final syllable (no rise).
TONE_MARKS = {'\u0301': 'acute', '\u0302': 'circ', '\u030c': 'rise', '\u0300': 'grave'}
# Characters outside Kokoro's 115-symbol vocabulary, with the explicit substitute used and why.
SUBSTITUTIONS = {
    'g': ('ɡ', 'ASCII g -> IPA ɡ (U+0261, the only g in the vocabulary)'),
    "'": ('ˈ', 'apostrophe -> primary stress'),
    'ʼ': ('', 'ejective/apostrophe mark dropped'),
    '\u0361': ('', 'tie bar dropped'),
    '\u035c': ('', 'tie bar dropped'),
    '\u032f': ('', 'non-syllabic mark dropped (diphthong offglide)'),
    '\u0325': ('', 'voiceless ring dropped (e.g. initial rho r̥ is rendered voiced)'),
    '\u030a': ('', 'voiceless ring dropped'),
    '\u0304': ('ː', 'macron -> length mark'),
    '\u0306': ('', 'breve (short) dropped'),
    '\u0303': ('̃', 'nasalization kept'),
    'ˑ': ('', 'half-long mark dropped'),
    '‿': ('', 'linking mark dropped'),
    '|': (',', 'minor prosodic break -> comma'),
    '‖': ('.', 'major prosodic break -> period'),
    'ɫ': ('l', 'dark l -> l'),
    'ɱ': ('m', 'labiodental nasal -> m'),
    'ʱ': ('ʰ', 'breathy release -> aspiration'),
    'ʍ': ('hw', 'voiceless w -> hw'),
    '-': ('', 'hyphen dropped'),
}


class IPAError(ValueError):
    pass


# Kokoro vocabulary symbols that Unicode NFD decomposes, as decomposed -> symbol (only ç in v1.0)
_NFD_SPLIT = {unicodedata.normalize('NFD', c): c for c in ('ç',)}


def parse_ipa(ipa: str, vocab: dict):
    """IPA -> list of tokens [{ch, id, vowel, tone}], plus the substitutions made.

    Raises IPAError listing every character that is neither in Kokoro's vocabulary nor in
    SUBSTITUTIONS, so nothing is silently dropped."""
    s = unicodedata.normalize('NFD', ipa.strip())
    # NFD splits the vocabulary's own precomposed symbols (ç = c + combining cedilla): put them back
    for dec, ch in _NFD_SPLIT.items():
        s = s.replace(dec, ch)
    # syllable dots between letters are syllable breaks, not full stops
    s = re.sub(r'(?<=[^\s.,;:!?…—])\.(?=[^\s.,;:!?…—])', '', s)
    s = re.sub(r'\s+', ' ', s)
    toks, subs, bad = [], [], []
    for ch in s:
        if ch in TONE_MARKS:
            for t in reversed(toks):   # attach to the nucleus it marks
                if t['vowel']:
                    t['tone'] = TONE_MARKS[ch]
                    break
            continue
        out = ch
        if ch not in vocab or ch == '$':
            if ch in SUBSTITUTIONS:
                out, why = SUBSTITUTIONS[ch]
                subs.append((ch, out, why))
            else:
                bad.append(ch)
                continue
        for c in out:
            if c.isupper():   # A I O Q S T W Y are misaki diphthong symbols, never IPA here
                bad.append(c)
                continue
            toks.append({'ch': c, 'id': vocab[c], 'vowel': c in VOWELS, 'tone': None})
    if bad:
        names = ', '.join(f'{c!r} U+{ord(c):04X} {unicodedata.name(c, "?")}' for c in dict.fromkeys(bad))
        raise IPAError(f'IPA outside the Kokoro vocabulary with no defined substitute: {names}')
    if len(toks) > 500:
        raise IPAError('line too long for one Kokoro pass (max ~500 phoneme symbols)')
    return toks, subs


def kokoro_stress(ipa: str) -> str:
    """Move each stress mark from the syllable onset to just before the vowel it stresses.

    Kokoro was trained on espeak-style IPA, where ˈ/ˌ stand right before the stressed vowel
    (mˈenete, aspˈidas). The phonetician's convention puts them before the whole syllable
    (ˈmenete, asˈpidas), which Kokoro reads as a stressed consonant: on the final lines Whisper's
    loose error rate fell from a mean of 0.26 to 0.16 (median 0.23 to 0.12) with the marks moved. The
    accent plan is the same either way: accent_plan() looks back from each vowel past its onset."""
    out, pending = [], ''
    for ch in unicodedata.normalize('NFD', ipa):
        if ch in STRESS:
            pending += ch
            continue
        if pending and (ch in VOWELS or ch.isspace() or ch in PUNCT):
            out.append(pending)   # before the vowel (or at the word's end if no vowel follows in it)
            pending = ''
        out.append(ch)
    return ''.join(out) + pending


def syllable_nuclei(toks):
    """Groups of token indices forming each vowel nucleus (vowel + length marks + offglides)."""
    nuclei, i = [], 0
    while i < len(toks):
        if toks[i]['vowel']:
            grp = [i]
            j = i + 1
            while j < len(toks) and (toks[j]['ch'] == 'ː' or (toks[j]['vowel'] and toks[j]['ch'] in 'iuy'
                                                                and toks[j - 1]['ch'] != 'ː' and len(grp) < 3
                                                                and toks[j]['tone'] is None)):
                grp.append(j)
                j += 1
            nuclei.append(grp)
            i = j
        else:
            i += 1
    return nuclei


def accent_plan(toks):
    """Which nuclei carry the (pitch) accent and with which contour.

    Per word, tone marks win; in a word without any, the nucleus after a primary-stress mark is
    accented (acute)."""
    nuclei = syllable_nuclei(toks)
    word, w = [], 0
    for t in toks:
        if t['ch'] == ' ' or t['ch'] in PUNCT:
            w += 1
        word.append(w)
    plan, toned = [], set()
    for grp in nuclei:
        tone = next((toks[k]['tone'] for k in grp if toks[k]['tone']), None)
        if tone:
            plan.append((grp, tone))
            toned.add(word[grp[0]])
    for grp in nuclei:
        if word[grp[0]] in toned:
            continue
        k = grp[0] - 1
        while k >= 0 and not toks[k]['vowel'] and toks[k]['ch'] != ' ' and toks[k]['ch'] not in PUNCT:
            if toks[k]['ch'] == 'ˈ':
                plan.append((grp, 'acute'))
                break
            k -= 1
    plan.sort(key=lambda p: p[0][0])
    return nuclei, plan


# ----------------------------------------------------------------------------- Kokoro (patched graph)

def patch_graph(model_path: Path, smooth_taps: int = 9) -> bytes:
    """Kokoro's graph + three per-token control inputs and the predicted durations as an output.

    dur_scale [1,N]: multiplies each token's predicted duration (before the speed division).
    f0_tok    [1,N]: multiplies the predicted F0 over the frames of each token (Hann-smoothed).
    n_tok     [1,N]: is added to the predicted energy (N) curve over each token (Hann-smoothed).
    With ones/ones/zeros the output is bit-identical to the original model."""
    import onnx
    from onnx import TensorProto as TP
    from onnx import helper as h
    from onnx import numpy_helper as nh

    m = onnx.load(str(model_path))
    g = m.graph
    byname = {n.name: n for n in g.node}
    need = ['/encoder/Div', '/encoder/Reshape', '/encoder/Reshape_1']
    for nm in need:
        if nm not in byname:
            raise RuntimeError(f'unexpected Kokoro graph: node {nm} not found')

    def const(name, arr):
        g.initializer.append(nh.from_array(np.asarray(arr), name))
        return name

    g.input.extend([h.make_tensor_value_info(n, TP.FLOAT, [1, 'N']) for n in ('dur_scale', 'f0_tok', 'n_tok')])
    new = [h.make_node('Mul', ['/encoder/predictor/ReduceSum_output_0', 'dur_scale'], ['vx/dur'], name='vx/dur_mul')]
    byname['/encoder/Div'].input[0] = 'vx/dur'
    const('vx/ax1', np.array([1], np.int64))
    const('vx/ax2', np.array([2], np.int64))
    const('vx/shape', np.array([1, -1], np.int64))
    p = smooth_taps // 2
    const('vx/pads', np.array([0, 0, p, 0, 0, p], np.int64))
    w = np.hanning(smooth_taps + 2)[1:-1]
    const('vx/hann', (w / w.sum()).astype(np.float32).reshape(1, 1, smooth_taps))

    def per_frame(tok, tag):
        o = f'vx/{tag}'
        new.extend([
            # token values -> frames through the alignment matrix [N, T]
            h.make_node('MatMul', [tok, '/encoder/Cast_4_output_0'], [o + '_fr'], name=o + '_mm'),
            # F0 and N run at twice the frame rate: repeat each frame value
            h.make_node('Unsqueeze', [o + '_fr', 'vx/ax2'], [o + '_u'], name=o + '_us'),
            h.make_node('Concat', [o + '_u', o + '_u'], [o + '_c'], axis=2, name=o + '_cat'),
            h.make_node('Reshape', [o + '_c', 'vx/shape'], [o + '_2x'], name=o + '_rs'),
            h.make_node('Unsqueeze', [o + '_2x', 'vx/ax1'], [o + '_3d'], name=o + '_us2'),
            h.make_node('Pad', [o + '_3d', 'vx/pads'], [o + '_pad'], mode='edge', name=o + '_padn'),
            h.make_node('Conv', [o + '_pad', 'vx/hann'], [o + '_sm3'], name=o + '_conv'),
            h.make_node('Reshape', [o + '_sm3', 'vx/shape'], [o + '_sm'], name=o + '_rs2'),
        ])
        return o + '_sm'

    new.append(h.make_node('Mul', ['/encoder/Squeeze_output_0', per_frame('f0_tok', 'f0')], ['vx/F0'], name='vx/f0_apply'))
    new.append(h.make_node('Add', ['/encoder/Squeeze_1_output_0', per_frame('n_tok', 'n')], ['vx/N'], name='vx/n_apply'))
    byname['/encoder/Reshape'].input[0] = 'vx/F0'
    byname['/encoder/Reshape_1'].input[0] = 'vx/N'
    new.append(h.make_node('Identity', ['/encoder/Cast_output_0'], ['pred_dur'], name='vx/pred_dur'))
    # topological order
    avail = {i.name for i in g.input} | {i.name for i in g.initializer} | {''}
    ordered, pending = [], list(g.node) + new
    while pending:
        rest = []
        for n in pending:
            if all(i in avail for i in n.input):
                ordered.append(n)
                avail.update(n.output)
            else:
                rest.append(n)
        if len(rest) == len(pending):
            raise RuntimeError('graph patch failed (unresolved inputs)')
        pending = rest
    del g.node[:]
    g.node.extend(ordered)
    g.output.extend([h.make_tensor_value_info('pred_dur', TP.INT64, None),
                     h.make_tensor_value_info('vx/F0', TP.FLOAT, None)])
    return m.SerializeToString()


class Kokoro:
    def __init__(self, model_dir: Path):
        import onnxruntime as ort
        model_dir = Path(model_dir)
        onnx_path = model_dir / MODEL_FILE
        if not onnx_path.exists():
            raise SystemExit(f'Kokoro model not found at {onnx_path}. Run spartan/tools/voice/setup.sh first '
                             f'(or pass --model-dir).')
        digest = sha256_file(onnx_path)
        if digest != MODEL_SHA256:
            raise SystemExit(f'{onnx_path}: sha256 {digest} != expected {MODEL_SHA256}')
        self.model_dir = model_dir
        tok = json.loads((model_dir / 'tokenizer.json').read_text(encoding='utf-8'))
        self.vocab = tok['model']['vocab']
        so = ort.SessionOptions()
        so.intra_op_num_threads = ORT_THREADS
        so.inter_op_num_threads = 1
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(patch_graph(onnx_path), so, providers=['CPUExecutionProvider'])
        self._voices = {}

    def voice(self, name: str) -> np.ndarray:
        if name not in self._voices:
            p = self.model_dir / 'voices' / f'{name}.bin'
            if not p.exists():
                raise SystemExit(f'voice {name} not found in {p.parent}')
            self._voices[name] = np.fromfile(p, dtype=np.float32).reshape(-1, 1, 256)
        return self._voices[name]

    def style(self, timbre: dict, prosody: dict, ntok: int) -> np.ndarray:
        """Weighted blend; dims 0-127 condition the decoder (timbre), 128-255 the prosody predictor."""
        row = min(ntok, 509)

        def mix(weights):
            tot = sum(weights.values())
            return sum(self.voice(v)[row, 0] * (w / tot) for v, w in sorted(weights.items()))
        st = np.empty((1, 256), np.float32)
        st[0, :128] = mix(timbre)[:128]
        st[0, 128:] = mix(prosody or timbre)[128:]
        return st

    def run(self, ids, style, speed, dur_scale, f0_tok, n_tok):
        n = len(ids)
        feeds = {'input_ids': np.array([ids], np.int64), 'style': style.astype(np.float32),
                 'speed': np.array([speed], np.float32),
                 'dur_scale': np.asarray(dur_scale, np.float32).reshape(1, n),
                 'f0_tok': np.asarray(f0_tok, np.float32).reshape(1, n),
                 'n_tok': np.asarray(n_tok, np.float32).reshape(1, n)}
        wav, dur, f0 = self.sess.run(['waveform', 'pred_dur', 'vx/F0'], feeds)
        return wav.reshape(-1).astype(np.float64), np.asarray(dur).reshape(-1), f0.reshape(-1)


# ----------------------------------------------------------------------------- recipes

DELIVERY_ALIASES = {
    'warcry': 'cry', 'cry': 'cry', 'battlecry': 'cry', 'ululate': 'cry', 'ululation': 'cry',
    'shout': 'shout', 'yell': 'shout', 'roar': 'shout', 'bellow': 'shout',
    'command': 'command', 'order': 'command', 'bark': 'command', 'call': 'command', 'urgent': 'command',
    'defiant': 'command', 'alarm': 'command', 'proclaim': 'command', 'proclamation': 'command',
    'speech': 'speech', 'calm': 'speech', 'say': 'speech', 'grave': 'speech', 'dry': 'speech', 'wry': 'speech',
    'quiet': 'speech', 'normal': 'speech', 'praise': 'speech', 'encourage': 'speech',
}


KINDS = ('speech', 'command', 'shout', 'cry')


def line_kind(ln: dict) -> str:
    """A line's delivery kind: its explicit "kind" when it has one, else read off its delivery text."""
    return ln['kind'] if ln.get('kind') else delivery_kind(ln.get('delivery'))


def delivery_kind(delivery: str | None) -> str:
    d = (delivery or 'speech').strip().lower()
    for word in re.findall(r'[a-z]+', d):
        if word in DELIVERY_ALIASES:
            return DELIVERY_ALIASES[word]
    return 'speech'


def deep_merge(a: dict, b: dict) -> dict:
    out = dict(a)
    for k, v in b.items():
        out[k] = deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) and k not in ('timbre', 'prosody') else v
    return out


def resolve_recipe(voices: dict, speaker: str, delivery: str) -> dict:
    """defaults <- speaker <- defaults.delivery[kind] <- speaker.delivery[kind].

    The speaker sets the voice (timbre, prosody, formant, f0_st, speed, eq, grit); a delivery sets
    the vocal effort (f0_delivery_st, speed_mul, accent/final lengthening, effort_eq, saturation,
    compressor). Dicts merge key by key; lists (EQ bands) and voice blends are replaced whole."""
    if speaker not in voices.get('speakers', {}):
        raise SystemExit(f'voices.json has no recipe for speaker {speaker!r}')
    kind = delivery_kind(delivery)
    d = voices.get('defaults', {})
    s = voices['speakers'][speaker]
    if kind == 'cry' and 'cry' not in d.get('delivery', {}):
        kind = 'shout'
    r = deep_merge({k: v for k, v in d.items() if k != 'delivery'}, {k: v for k, v in s.items() if k != 'delivery'})
    r = deep_merge(r, d.get('delivery', {}).get(kind, {}))
    r = deep_merge(r, s.get('delivery', {}).get(kind, {}))
    r['kind'] = kind
    r['speed'] = float(r.get('speed', 1.0)) * float(r.get('speed_mul', 1.0))
    sat, grit = dict(r.get('saturation') or {}), r.get('grit') or {}
    if grit:
        sat['drive'] = max(float(sat.get('drive', 0.0)), float(grit.get('drive', 0.0)))
        sat['mix'] = min(1.0, float(sat.get('mix', 0.0)) + float(grit.get('mix', 0.0)))
    r['saturation'] = sat
    return r


def controls(toks, nuclei, plan, r):
    """Per-token duration scales, F0 multipliers (semitones -> ratio) and energy offsets."""
    n = len(toks)
    st = np.zeros(n)                       # semitones
    dur = np.ones(n)
    en = np.zeros(n)
    A = float(r.get('accent_st', 0.0))
    post = float(r.get('post_accent_st', -0.25 * A))
    for grp, tone in plan:
        if tone == 'grave':                # the lowered accent of a final syllable: no peak
            continue
        first, last = grp[0], grp[-1]
        en[grp] += float(r.get('accent_energy', 0.0))
        dur[grp] *= float(r.get('accent_len', 1.0))
        if A == 0:
            continue
        if tone == 'rise' and len(grp) > 1:
            st[grp[0]] += 0.35 * A
            for k in grp[1:]:
                st[k] += A
        elif tone == 'circ' and len(grp) > 1:
            st[grp[0]] += A
            for k in grp[1:]:
                st[k] += 0.15 * A
        else:
            for k in grp:
                st[k] += A
        k = first - 1                      # onset consonants and stress mark rise into the peak
        while k >= 0 and not toks[k]['vowel'] and toks[k]['ch'] not in PUNCT and toks[k]['ch'] != ' ':
            st[k] += 0.5 * A
            k -= 1
        k = last + 1                       # and fall away after it, to a low next syllable
        while k < n and not toks[k]['vowel'] and toks[k]['ch'] not in PUNCT and toks[k]['ch'] != ' ':
            st[k] += 0.35 * A
            k += 1
        nxt = next((g for g in nuclei if g[0] > last), None)
        if nxt is not None and not any(toks[j]['ch'] in PUNCT for j in range(last + 1, nxt[0])):
            for j in nxt:
                st[j] += post
    # phrase-final nucleus before ! or . (or the end): lengthening and an optional pitch move
    final_len = float(r.get('final_len', 1.0))
    final_st = float(r.get('final_st', 0.0))
    for i, grp in enumerate(nuclei):
        nxt = nuclei[i + 1][0] if i + 1 < len(nuclei) else n
        if i + 1 == len(nuclei) or any(toks[j]['ch'] in '!.?;' for j in range(grp[-1] + 1, nxt)):
            dur[grp] *= final_len
            st[grp[-1]] += final_st
    st += float(r.get('f0_st', 0.0)) + float(r.get('f0_delivery_st', 0.0))
    return dur, st, en


# ----------------------------------------------------------------------------- DSP

def biquad(kind, f, fs, gain_db=0.0, q=0.7071):
    A = 10 ** (gain_db / 40)
    w0 = 2 * math.pi * f / fs
    c, s = math.cos(w0), math.sin(w0)
    al = s / (2 * q)
    if kind == 'highpass':
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]; a = [1 + al, -2 * c, 1 - al]
    elif kind == 'lowpass':
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]; a = [1 + al, -2 * c, 1 - al]
    elif kind == 'peak':
        b = [1 + al * A, -2 * c, 1 - al * A]; a = [1 + al / A, -2 * c, 1 - al / A]
    elif kind == 'lowshelf':
        sq = 2 * math.sqrt(A) * al
        b = [A * ((A + 1) - (A - 1) * c + sq), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sq)]
        a = [(A + 1) + (A - 1) * c + sq, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sq]
    elif kind == 'highshelf':
        sq = 2 * math.sqrt(A) * al
        b = [A * ((A + 1) + (A - 1) * c + sq), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sq)]
        a = [(A + 1) - (A - 1) * c + sq, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sq]
    else:
        raise SystemExit(f'unknown EQ band type {kind!r}')
    return np.array([b[0] / a[0], b[1] / a[0], b[2] / a[0], 1.0, a[1] / a[0], a[2] / a[0]])


def apply_eq(x, fs, bands):
    """bands: [{type, f, gain, q}]"""
    if not bands:
        return x
    sos = np.array([biquad(b['type'], b['f'], fs, b.get('gain', 0.0), b.get('q', 0.7071)) for b in bands])
    return signal.sosfilt(sos, x)


def saturate(x, drive, mix):
    """Soft tanh saturation at 2x oversampling, level-independent (peak-normalized), parallel mix."""
    if drive <= 0 or mix <= 0:
        return x
    pk = np.max(np.abs(x)) or 1.0
    up = signal.resample_poly(x / pk, 2, 1)
    wet = np.tanh(drive * up) / math.tanh(drive)
    y = signal.resample_poly((1 - mix) * up + mix * wet, 1, 2)[: len(x)]
    return y * pk


def compress(x, fs, threshold_db=-20.0, ratio=3.0, attack_ms=5.0, release_ms=80.0, knee_db=6.0):
    """Feed-forward compressor on a 1 ms RMS envelope (soft knee); gain interpolated per sample."""
    if ratio <= 1.0:
        return x
    blk = max(1, int(fs / 1000))
    nb = int(math.ceil(len(x) / blk))
    pad = np.zeros(nb * blk)
    pad[: len(x)] = x
    lvl = 10 * np.log10(np.mean(pad.reshape(nb, blk) ** 2, axis=1) + 1e-12)
    over = lvl - threshold_db
    gr = np.where(over <= -knee_db / 2, 0.0,
                  np.where(over >= knee_db / 2, over * (1 - 1 / ratio),
                           (1 - 1 / ratio) * (over + knee_db / 2) ** 2 / (2 * knee_db)))
    a_at = math.exp(-1.0 / max(attack_ms, 0.1))
    a_re = math.exp(-1.0 / max(release_ms, 0.1))
    sm = np.empty(nb)
    g = 0.0
    for i in range(nb):
        t = gr[i]
        g = a_at * g + (1 - a_at) * t if t > g else a_re * g + (1 - a_re) * t
        sm[i] = g
    centers = np.arange(nb) * blk + blk / 2
    gain_db = np.interp(np.arange(len(x)), centers, -sm)
    return x * 10 ** (gain_db / 20)


def envelope_db(x, fs, win_ms=10.0):
    w = max(1, int(fs * win_ms / 1000))
    nb = len(x) // w
    if nb == 0:
        return np.array([-120.0]), w
    e = 10 * np.log10(np.mean(x[: nb * w].reshape(nb, w) ** 2, axis=1) + 1e-12)
    return e, w


def trim(x, fs, rel_db=-40.0, abs_db=-60.0, pad_ms=40.0, fade_in_ms=5.0, fade_out_ms=20.0):
    e, w = envelope_db(x, fs)
    thr = max(e.max() + rel_db, abs_db)
    on = np.nonzero(e > thr)[0]
    if len(on) == 0:
        return x
    pad = int(fs * pad_ms / 1000)
    a = max(0, on[0] * w - pad)
    b = min(len(x), (on[-1] + 1) * w + pad)
    y = x[a:b].copy()
    fi, fo = int(fs * fade_in_ms / 1000), int(fs * fade_out_ms / 1000)
    if fi:
        y[:fi] *= 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, fi))
    if fo:
        y[-fo:] *= 0.5 + 0.5 * np.cos(np.linspace(0, math.pi, fo))
    return y


# BS.1770-4 K-weighting at 48 kHz
_KW_SOS = np.array([[1.53512485958697, -2.69169618940638, 1.19839281085285, 1.0, -1.69065929318241, 0.73248077421585],
                    [1.0, -2.0, 1.0, 1.0, -1.99004745483398, 0.99007225036621]])


def loudness_lufs(x, fs=SR_OUT):
    """ITU-R BS.1770-4 / EBU R128 integrated loudness (mono); short clips fall back to one block."""
    if fs != 48000:
        x = signal.resample_poly(x, 48000, fs)
    y = signal.sosfilt(_KW_SOS, x)
    blk, hop = 19200, 4800
    if len(y) < blk:
        ms = np.mean(y ** 2)
        return -0.691 + 10 * math.log10(ms + 1e-20)
    nb = 1 + (len(y) - blk) // hop
    z = np.array([np.mean(y[i * hop: i * hop + blk] ** 2) for i in range(nb)])
    l = -0.691 + 10 * np.log10(z + 1e-20)
    z = z[l > -70]
    if len(z) == 0:
        return -120.0
    rel = -0.691 + 10 * math.log10(np.mean(z)) - 10
    z2 = z[(-0.691 + 10 * np.log10(z + 1e-20)) > rel]
    return -0.691 + 10 * math.log10(np.mean(z2 if len(z2) else z))


def true_peak_db(x, fs=SR_OUT):
    up = signal.resample_poly(x, 4, 1) if fs <= 48000 else x
    return 20 * math.log10(np.max(np.abs(up)) + 1e-12)


def limit(x, ceiling_db, fs=SR_OUT, look_ms=5.0):
    """Look-ahead true-peak limiter: min-filtered gain smoothed by a Hann window of the same span
    (the smoothed gain never exceeds the gain each sample needs)."""
    up = np.abs(signal.resample_poly(x, 4, 1))[: 4 * len(x)]
    pk = np.max(up.reshape(-1, 4), axis=1)
    ceil = 10 ** (ceiling_db / 20)
    need = np.minimum(1.0, ceil / np.maximum(pk, 1e-12))
    if need.min() >= 1.0:
        return x
    L = max(1, int(fs * look_ms / 1000))
    g = minimum_filter1d(need, size=2 * L + 1, mode='nearest')
    w = np.hanning(2 * L + 3)[1:-1]
    g = np.convolve(np.pad(g, L, mode='edge'), w / w.sum(), mode='valid')
    return x * g[: len(x)]


def normalize(x, target=LOUDNESS_TARGET, ceiling_db=-2.3):
    y = x
    for _ in range(6):
        y = y * 10 ** ((target - loudness_lufs(y)) / 20)
        y = limit(y, ceiling_db)
        if abs(loudness_lufs(y) - target) < 0.05 and true_peak_db(y) <= ceiling_db + 0.05:
            break
    return y


def formant_resample(wav24, ratio):
    """Play the 24 kHz synthesis as if recorded at 24000*ratio Hz and resample to 48 kHz:
    every frequency (F0 and formants) scales by `ratio`, duration by 1/ratio."""
    src = int(round(SR_MODEL * ratio))
    g = math.gcd(SR_OUT, src)
    return signal.resample_poly(wav24, SR_OUT // g, src // g)


# ----------------------------------------------------------------------------- one clip

def synth_line(kok: Kokoro, ipa: str, r: dict):
    """IPA + resolved recipe -> 48 kHz float pre-encode clip and an info dict."""
    pm = r.get('phone_map') or {}
    if pm:   # a speaker's accent: phoneme substitutions, longest first
        ipa = unicodedata.normalize('NFD', ipa)
        for a in sorted(pm, key=len, reverse=True):
            ipa = ipa.replace(unicodedata.normalize('NFD', a), unicodedata.normalize('NFD', pm[a]))
    toks, subs = parse_ipa(kokoro_stress(ipa), kok.vocab)
    nuclei, plan = accent_plan(toks)
    dur, st, en = controls(toks, nuclei, plan, r)
    ratio = float(r.get('formant', 1.0))
    speed = float(r.get('speed', 1.0)) / ratio
    ids = [0] + [t['id'] for t in toks] + [0]
    dur = np.concatenate([[1.0], dur, [1.0]])
    f0m = np.concatenate([[1.0], 2 ** (st / 12), [1.0]]) / ratio
    f0m[0], f0m[-1] = f0m[1], f0m[-2]
    en = np.concatenate([[en[0] if len(en) else 0.0], en, [en[-1] if len(en) else 0.0]])
    style = kok.style(r['timbre'], r.get('prosody'), len(toks))
    wav, pdur, f0 = kok.run(ids, style, speed, dur, f0m, en)
    # Kokoro stretches some phonemes far too long (typically a sentence-final consonant: a
    # 450-650 ms "s"); cap them and synthesize again.
    lim = np.array([max_frames(t['ch'], r) for t in toks], float)
    got = pdur[1:-1].astype(float)
    over = got > lim
    clamped = [f'{toks[i]["ch"]}:{int(got[i]) * FRAME * 1000 // SR_MODEL}ms' for i in np.nonzero(over)[0]]
    if over.any():
        dur = dur.copy()
        # `got` is already rounded (the unrounded length lies within got +- 0.5 frames): scaling by
        # (lim + 0.45) / (got + 0.5) keeps the re-synthesized length at or under the cap after rounding
        dur[1:-1][over] *= (lim[over] + 0.45) / (got[over] + 0.5)
        wav, pdur, f0 = kok.run(ids, style, speed, dur, f0m, en)
    x = formant_resample(wav, ratio)
    x = apply_eq(x, SR_OUT, list(r.get('eq', [])) + list(r.get('effort_eq', [])))   # voice tone + vocal effort
    x = x * 10 ** ((PRE_LEVEL - loudness_lufs(x)) / 20)   # fixed level, so drive/threshold mean the same everywhere
    sat = r.get('saturation', {})
    x = saturate(x, float(sat.get('drive', 0.0)), float(sat.get('mix', 0.0)))
    c = r.get('compressor', {})
    if c:
        x = compress(x, SR_OUT, c.get('threshold', -20), c.get('ratio', 1), c.get('attack', 5), c.get('release', 80),
                     c.get('knee', 6))
    x = apply_eq(x, SR_OUT, r.get('post_eq', []))
    x = trim(x, SR_OUT, **r.get('trim', {}))
    x = normalize(x, LOUDNESS_TARGET, float(r.get('ceiling_db', -2.3)))
    frames = pdur.astype(int)
    info = {
        'tokens': ''.join(t['ch'] for t in toks), 'substitutions': [f'U+{ord(a):04X} {a!r} -> {b!r}: {why}' for a, b, why in subs],
        'accent': [[''.join(toks[k]['ch'] for k in grp), tone] for grp, tone in plan],
        'durations_ms': [[('$' if i in (0, len(ids) - 1) else toks[i - 1]['ch']), int(frames[i]) * FRAME * 1000 // SR_MODEL]
                         for i in range(len(ids))],
        'model_f0': f0,
        'clamped': clamped,
    }
    return x.astype(np.float32), info


def max_frames(ch, r):
    """Longest a phoneme may last, in Kokoro frames (25 ms) after the speed division."""
    lim = r.get('max_ms', {})
    if ch in VOWELS or ch == 'ː':
        ms = lim.get('vowel', 700)
    elif ch in PUNCT:
        ms = lim.get('pause', 600)
    elif ch == ' ':
        ms = lim.get('space', 300)
    elif ch in STRESS:
        ms = lim.get('stress', 150)
    else:
        ms = lim.get('consonant', 200)
    return max(1, int(ms * SR_MODEL / 1000 // FRAME))


# ----------------------------------------------------------------------------- Opus I/O

def ffmpeg_bin():
    exe = shutil.which('ffmpeg')
    if not exe:
        raise SystemExit('ffmpeg (with libopus) is required')
    return exe


def encode_opus(x, path: Path, bitrate=OPUS_BITRATE):
    import soundfile as sf
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        wav = Path(td) / 'in.wav'
        sf.write(wav, x, SR_OUT, subtype='FLOAT')
        cmd = [ffmpeg_bin(), '-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav),
               '-map_metadata', '-1', '-ac', '1', '-c:a', 'libopus', '-b:a', bitrate, '-vbr', 'on',
               '-compression_level', '10', '-application', 'audio', '-frame_duration', '20',
               '-fflags', '+bitexact', '-flags:a', '+bitexact', str(path)]
        subprocess.run(cmd, check=True)


def decode(path: Path, sr=SR_OUT) -> np.ndarray:
    cmd = [ffmpeg_bin(), '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', str(path), '-f', 'f32le', '-ac', '1',
           '-ar', str(sr), '-']
    out = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(out, dtype='<f4').astype(np.float64)


def render_clip(kok, ipa, recipe, path: Path, wav_dir: Path | None = None):
    """Synthesize, encode and verify one clip, measured on the decoded Opus: Opus at 48 kb/s sheds
    some high-frequency energy (up to -0.7 LU on bright voices) and can overshoot the peak, so the
    pre-encode loudness target and limiter ceiling are corrected until the decoded clip is within
    0.15 LU of the target and under the true-peak ceiling."""
    x0, info = synth_line(kok, ipa, recipe)
    x = x0
    ceiling = float(recipe.get('ceiling_db', -2.3))
    pre_target = LOUDNESS_TARGET
    for _ in range(6):
        encode_opus(x, path)
        y = decode(path)
        tp, lu = true_peak_db(y), loudness_lufs(y)
        if tp <= TRUE_PEAK_MAX and abs(lu - LOUDNESS_TARGET) <= 0.15:
            break
        if tp > TRUE_PEAK_MAX:
            ceiling -= (tp - TRUE_PEAK_MAX) + 0.2
        pre_target += LOUDNESS_TARGET - lu
        x = normalize(x0.astype(np.float64), pre_target, ceiling).astype(np.float32)
    else:
        print(f'warning: {path.name} ends at {lu:.2f} LUFS / {tp:.2f} dBTP (target {LOUDNESS_TARGET} LUFS, '
              f'<= {TRUE_PEAK_MAX} dBTP)', file=sys.stderr)
    info['lufs'], info['true_peak'] = lu, tp
    if wav_dir:
        import soundfile as sf
        wav_dir.mkdir(parents=True, exist_ok=True)
        sf.write(wav_dir / (path.stem + '.wav'), x, SR_OUT, subtype='PCM_24')
    info['dur'] = len(y) / SR_OUT
    info['decoded'] = y
    return info


# ----------------------------------------------------------------------------- QA metrics

def clip_metrics(y, sr=SR_OUT, f0_range=(50, 450)):
    import librosa
    m = {'dur_s': round(len(y) / sr, 3)}
    m['peak_dbfs'] = round(20 * math.log10(np.max(np.abs(y)) + 1e-12), 2)
    m['true_peak_dbtp'] = round(true_peak_db(y, sr), 2)
    m['lufs'] = round(loudness_lufs(y, sr), 2)
    m['clipped_samples'] = int(np.sum(np.abs(y) >= 0.999))
    e, w = envelope_db(y, sr)
    thr = e.max() - 40
    on = np.nonzero(e > thr)[0]
    m['lead_silence_ms'] = int(on[0] * w * 1000 / sr) if len(on) else None
    m['trail_silence_ms'] = int((len(y) - (on[-1] + 1) * w) * 1000 / sr) if len(on) else None
    gaps = np.diff(on) - 1 if len(on) > 1 else np.array([0])
    m['max_gap_ms'] = int(gaps.max() * w * 1000 / sr)   # longest pause inside the clip
    y16 = librosa.resample(y.astype(np.float32), orig_sr=sr, target_sr=16000)
    f0, vflag, _ = librosa.pyin(y16, fmin=f0_range[0], fmax=f0_range[1], sr=16000, frame_length=1024, hop_length=160)
    f = f0[~np.isnan(f0)]
    m['voiced_ratio'] = round(float(np.mean(vflag)), 3)
    if len(f):
        m['f0_median'] = round(float(np.median(f)), 1)
        m['f0_p10'] = round(float(np.percentile(f, 10)), 1)
        m['f0_p90'] = round(float(np.percentile(f, 90)), 1)
        m['f0_range_st'] = round(12 * math.log2(m['f0_p90'] / m['f0_p10']), 1)
    S = np.abs(librosa.stft(y16, n_fft=512, hop_length=160)) + 1e-10
    flat = librosa.feature.spectral_flatness(S=S)[0]
    n = min(len(flat), len(vflag))
    vf = flat[:n][vflag[:n]]
    m['flatness_voiced'] = round(float(np.mean(vf)), 4) if len(vf) else None
    m['centroid_hz'] = round(float(np.mean(librosa.feature.spectral_centroid(S=S, sr=16000)[0][:n][vflag[:n]])), 0) if len(vf) else None
    mf = librosa.feature.mfcc(y=y16, sr=16000, n_mfcc=20, n_fft=512, hop_length=160)
    m['mfcc_mean'] = [round(float(v), 2) for v in mf[1:13, :n][:, vflag[:n]].mean(axis=1)] if len(vf) else None
    return m


def token_flags(info, max_cons_ms=250, max_vowel_ms=700, recipe=None):
    """Phonemes with suspicious predicted durations (garbled or stuck stretches). A recipe that allows
    longer vowels on purpose (the cry's held final, max_ms.vowel 950) raises the vowel limit to its cap."""
    max_vowel_ms = max(max_vowel_ms, float(((recipe or {}).get('max_ms') or {}).get('vowel', 0)))
    flags = []
    for ch, ms in info['durations_ms'][1:-1]:
        lim = max_vowel_ms if (ch in VOWELS or ch == 'ː') else (600 if ch in PUNCT or ch == ' ' else max_cons_ms)
        if ms > lim:
            flags.append(f'{ch}:{ms}ms')
    return flags


# ----------------------------------------------------------------------------- voicebank.js

def js_str(s: str) -> str:
    return "'" + s.replace('\\', '\\\\').replace("'", "\\'").replace('\n', '\\n') + "'"


def write_voicebank(path: Path, keys, variants, durations):
    """variants: key -> [line dict]; durations: (key, variant, speaker) -> seconds."""
    out = ['// GENERATED by spartan/tools/voice/render.py from spartan/tools/voice/lines.json. Do not edit by hand.',
           '/** Ancient Greek voice lines: key -> variants (in order). Each variant has its Greek (epigraphic capitals), '
           'English subtitle and one clip per speaker. */',
           'export const VOICEBANK = Object.freeze({']
    for key in keys:
        out.append(f'  {key}: Object.freeze([')
        for ln in variants[key]:
            clips = ', '.join(
                f'{sp}: Object.freeze({{ file: {js_str(clip_rel(key, ln["variant"], sp))}, '
                f'dur: {durations[(key, ln["variant"], sp)]:.2f} }})' for sp in ln['speakers'])
            out.append(f'    Object.freeze({{ greek: {js_str(ln["caps"])}, en: {js_str(ln["en"])}, '
                       f'clips: Object.freeze({{ {clips} }}) }}),')
        out.append('  ]),')
    out.append('});')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(out) + '\n', encoding='utf-8')


def clip_rel(key, variant, speaker):
    return f'{VOICE_DIR_REL}/{key}-{variant}-{speaker.lower()}.ogg'


# ----------------------------------------------------------------------------- main

def line_ipa(ln: dict, speaker: str, field: str = 'ipa') -> str:
    """The pronunciation a speaker reads: `<field>_by_speaker[speaker]` when the line has one, else `<field>`."""
    return (ln.get(f'{field}_by_speaker') or {}).get(speaker, ln[field])


def load_lines(path: Path, ipa_field: str = 'ipa'):
    data = json.loads(path.read_text(encoding='utf-8'))
    lines = data['lines'] if isinstance(data, dict) else data
    keys, variants = [], {}
    for ln in lines:
        for f in ('key', 'variant', 'speakers', 'caps', 'en', ipa_field):
            if f not in ln:
                raise SystemExit(f'lines.json: line {ln.get("key")}/{ln.get("variant")} lacks {f!r}')
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', ln['key']):
            raise SystemExit(f'lines.json: bad key {ln["key"]!r}')
        if ln.get('kind') and ln['kind'] not in KINDS:
            raise SystemExit(f'lines.json: {ln["key"]}/{ln["variant"]}: kind {ln["kind"]!r} is not one of {KINDS}')
        if ln['key'] not in variants:
            keys.append(ln['key'])
            variants[ln['key']] = []
        variants[ln['key']].append(ln)
    for k in keys:
        variants[k].sort(key=lambda l: int(l['variant']))
        vs = [int(l['variant']) for l in variants[k]]
        if vs != list(range(1, len(vs) + 1)):
            raise SystemExit(f'lines.json: variants of {k} must be 1..n, got {vs}')
    return keys, variants


# Two readings of the same script: a recipe file (tuned for one pronunciation) names the lines.json
# IPA field it reads with "ipa_field" (default "ipa"). The game ships the Modern Greek reading, so it
# is the default: a one-key re-render (--only) must not slip the old voices back into the set.
READINGS = {
    'reconstructed': HERE / 'voices.json',   # 5th-century Attic/Laconic reconstruction (field ipa)
    'modern': HERE / 'voices_el.json',       # Modern Greek school reading of the ancient text (field ipa_el)
}
DEFAULT_READING = 'modern'


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--lines', type=Path, default=HERE / 'lines.json')
    ap.add_argument('--reading', choices=sorted(READINGS), default=None,
                    help="recipe preset: modern = voices_el.json (default, the game's clips), "
                         "reconstructed = voices.json")
    ap.add_argument('--voices', type=Path, default=None, help='recipe file (overrides --reading; default voices_el.json)')
    ap.add_argument('--ipa-field', default=None,
                    help="lines.json field holding the pronunciation (default: the recipe file's \"ipa_field\", else 'ipa'); "
                         "a line may override it per speaker with <field>_by_speaker")
    ap.add_argument('--model-dir', type=Path, default=None, help='folder with kokoro-quantized.onnx, voices/, tokenizer.json')
    ap.add_argument('--out', type=Path, default=None, help='game root to write into (default: the repo game assets)')
    ap.add_argument('--only', action='append', default=[], help='render only this key (repeatable)')
    ap.add_argument('--qa', nargs='?', const='', default=None, help='write per-clip metrics JSON (default <out>/voice-qa.json or tools/voice/qa-report.json)')
    ap.add_argument('--wav-dir', type=Path, default=None, help='also keep the pre-encode 48 kHz WAVs here')
    args = ap.parse_args(argv)

    root = (args.out or GAME_ROOT).resolve()
    voices_path = args.voices or READINGS[args.reading or DEFAULT_READING]
    voices = json.loads(voices_path.read_text(encoding='utf-8'))
    ipa_field = args.ipa_field or voices.get('ipa_field') or 'ipa'
    if voices.get('ipa_field', 'ipa') != ipa_field:
        print(f'note: {voices_path.name} was tuned for the field {voices.get("ipa_field", "ipa")!r}; reading {ipa_field!r}',
              file=sys.stderr)
    keys, variants = load_lines(args.lines, ipa_field)
    print(f'recipes {voices_path.name}, pronunciation field {ipa_field!r}')
    default_path = READINGS[DEFAULT_READING]
    if args.only and (voices_path.resolve() != default_path.resolve() or ipa_field != 'ipa_el'):
        print(f'warning: --only re-renders {args.only} with {voices_path.name} / {ipa_field!r}, but the other clips '
              f'under the output root are not touched: if they come from {default_path.name} / \'ipa_el\' (the game\'s '
              f'reading), the set will mix two readings.', file=sys.stderr)
    unknown = [k for k in args.only if k not in variants]
    if unknown:
        raise SystemExit(f'--only: unknown key(s) {unknown}; known: {keys}')
    todo = [k for k in keys if not args.only or k in args.only]
    kok = Kokoro(args.model_dir or default_model_dir())

    durations, report, problems, missing = {}, [], [], []
    for key in keys:
        for ln in variants[key]:
            for sp in ln['speakers']:
                path = root / clip_rel(key, ln['variant'], sp)
                if key not in todo:
                    if path.exists():
                        durations[(key, ln['variant'], sp)] = round(len(decode(path)) / SR_OUT, 2)
                    else:
                        missing.append(clip_rel(key, ln['variant'], sp))
                    continue
                recipe = resolve_recipe(voices, sp, line_kind(ln))
                ipa = line_ipa(ln, sp, ipa_field)
                try:
                    info = render_clip(kok, ipa, recipe, path, args.wav_dir)
                except IPAError as e:
                    raise SystemExit(f'{key}-{ln["variant"]} ({sp}): {e}')
                durations[(key, ln['variant'], sp)] = round(info['dur'], 2)
                flags = token_flags(info, recipe=recipe)
                for s in info['substitutions']:
                    problems.append(f'{key}-{ln["variant"]}: substituted {s}')
                print(f'{path.relative_to(root)}  {info["dur"]:.2f}s  [{recipe["kind"]}]'
                      + (f'  FLAGS {flags}' if flags else ''), flush=True)
                if args.qa is not None:
                    m = clip_metrics(info['decoded'])
                    m.update(key=key, variant=ln['variant'], speaker=sp, delivery=recipe['kind'], ipa_field=ipa_field, ipa=ipa,
                             tokens=info['tokens'], accent=info['accent'], duration_flags=flags,
                             substitutions=info['substitutions'], file=clip_rel(key, ln['variant'], sp),
                             bytes=path.stat().st_size)
                    report.append(m)
    if missing:   # a bank pointing at absent files would break the game: leave the old one alone
        print(f'{VOICEBANK_REL} NOT written: {len(missing)} clip(s) of other keys are missing under {root} '
              f'(first: {missing[0]}); render them (drop --only) to rebuild it.', file=sys.stderr)
    else:
        write_voicebank(root / VOICEBANK_REL, keys, variants, durations)
        print(f'wrote {root / VOICEBANK_REL}')
    if not args.only:   # clips of lines that no longer exist would only bloat the APK
        used = {Path(clip_rel(k, v, sp)).name for (k, v, sp) in durations}
        for f in sorted((root / VOICE_DIR_REL).glob('*.ogg')):
            if f.name not in used:
                f.unlink()
                print(f'removed stale {f.relative_to(root)}')
    for p in dict.fromkeys(problems):
        print('note:', p)
    if args.qa is not None:
        qa_path = Path(args.qa) if args.qa else (root / 'voice-qa.json' if args.out else HERE / 'qa-report.json')
        qa_path.write_text(json.dumps({'clips': report}, ensure_ascii=False, indent=1), encoding='utf-8')
        print(f'wrote {qa_path}')


if __name__ == '__main__':
    main()
