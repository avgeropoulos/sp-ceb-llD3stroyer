#!/usr/bin/env python3
"""Renders the Greek voice lines with Google Cloud Text-to-Speech's native Greek voices.

The ancient text is read the way Greek schools read Homer and Herodotus: in Modern Greek
pronunciation, by a native Greek voice. Each line's polytonic Greek (lines.json) is converted to
monotonic spelling, spoken by the speaker's Google voice (voices_google.json), then put through
render.py's own chain: EQ, the delivery's vocal effort (EQ, saturation, compression), an optional
formant-preserving pitch shift, trimming, loudness to -16 LUFS, Ogg Opus. It writes the same clips
and js/audio/voicebank.js that render.py does, so the game needs no change.

Needs the environment variable GOOGLE_TTS_API_KEY (a key with the Cloud Text-to-Speech API
enabled). The key is sent in a request header and never printed or written anywhere. Every
response is cached by its request (text, voice, rate) under the cache folder, so re-renders and
post-processing tweaks cost nothing.

  python3 render_google.py --list-voices                   # the el-GR voices this key can use
  python3 render_google.py --audition Leonidas --voices-try el-GR-Chirp3-HD-Charon,el-GR-Chirp3-HD-Fenrir
  python3 render_google.py                                  # render every line into the game
  python3 render_google.py --only molonLabe --out /tmp/x    # one key, elsewhere
"""
import argparse
import base64
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import render as R  # noqa: E402  (the Kokoro renderer: its post-processing, Opus encoding and voicebank writer)

API = 'https://texttospeech.googleapis.com/v1'
LANG = 'el-GR'
SR = R.SR_OUT  # ask Google for 48 kHz directly

# ----------------------------------------------------------------------------- spelling

GREEK_VOWELS = set('αεηιουωΑΕΗΙΟΥΩ')
_DROP = {'\u0313', '\u0314', '\u0345'}  # smooth and rough breathing, iota subscript
_TO_ACUTE = {'\u0300': '\u0301', '\u0342': '\u0301'}  # grave, circumflex -> the monotonic tonos
DIAERESIS = '\u0308'
ACUTE = '\u0301'
DIPHTHONGS = {'αι', 'ει', 'οι', 'υι', 'ου', 'αυ', 'ευ', 'ηυ'}


def _letters(word: str):
    """(base letter in lower case, its combining marks) for each letter of `word`."""
    out = []
    for ch in unicodedata.normalize('NFD', word):
        if unicodedata.combining(ch) and out:
            out[-1][1].add(ch)
        else:
            out.append((ch.lower(), set()))
    return out


def _syllables(word: str) -> int:
    """Syllable count: every vowel is a syllable unless it closes a diphthong with the one before
    (a diaeresis on the second vowel, as in Ἀΐδα, keeps them apart)."""
    n, prev = 0, ''
    for c, marks in _letters(word):
        if c not in GREEK_VOWELS:
            prev = ''
            continue
        if prev and prev + c in DIPHTHONGS and DIAERESIS not in marks:
            prev = ''  # the diphthong is closed: a third vowel starts a new syllable
            continue
        n += 1
        prev = c
    return n


def monotonic(text: str) -> str:
    """Polytonic -> modern monotonic spelling (what a Greek TTS reads naturally): no breathings or
    iota subscripts, every accent a tonos, and no tonos on monosyllables (τὰ -> τα, ἐν -> εν)."""
    out = []
    for tok in re.split(r'(\s+)', text):
        d = unicodedata.normalize('NFD', tok)
        d = ''.join(_TO_ACUTE.get(c, c) for c in d if c not in _DROP)
        if _syllables(d) <= 1:
            d = d.replace(ACUTE, '')
        out.append(unicodedata.normalize('NFC', d))
    return ''.join(out).replace('’', '\'').replace('ʼ', '\'')


# ----------------------------------------------------------------------------- Google API

def api_key() -> str:
    key = os.environ.get('GOOGLE_TTS_API_KEY', '').strip()
    if not key:
        raise SystemExit('GOOGLE_TTS_API_KEY is not set: add a Google Cloud API key with the Text-to-Speech API enabled '
                         'to the environment (see README.md).')
    return key


def _call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={'X-Goog-Api-Key': api_key(), 'Content-Type': 'application/json; charset=utf-8'})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode('utf-8', 'replace')[:400]
        raise RuntimeError(f'Google TTS {e.code}: {detail}') from None


def list_voices() -> list:
    return _call('GET', f'/voices?languageCode={LANG}').get('voices', [])


def cache_dir() -> Path:
    return Path(os.environ.get('THERMOPYLAE_TTS_CACHE', R.default_cache() / 'google-tts'))


def synthesize(text: str, voice: str, rate: float = 1.0) -> np.ndarray:
    """Mono float32 at 48 kHz for `text` in `voice` (cached by request)."""
    body = {'input': {'text': text}, 'voice': {'languageCode': LANG, 'name': voice},
            'audioConfig': {'audioEncoding': 'LINEAR16', 'sampleRateHertz': SR}}
    if abs(rate - 1.0) > 1e-3:
        body['audioConfig']['speakingRate'] = round(rate, 3)
    key = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:24]
    f = cache_dir() / f'{key}.wav'
    if not f.is_file():
        try:
            res = _call('POST', '/text:synthesize', body)
        except RuntimeError as e:
            if 'speakingRate' in body['audioConfig'] and ('speaking' in str(e).lower() or 'rate' in str(e).lower()):
                print(f'note: {voice} takes no speakingRate; rendering at its natural rate', file=sys.stderr)
                return synthesize(text, voice, 1.0)
            raise
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(base64.b64decode(res['audioContent']))
    import soundfile as sf
    x, sr = sf.read(io.BytesIO(f.read_bytes()), dtype='float32', always_2d=True)
    x = x.mean(axis=1)
    if sr != SR:
        import librosa
        x = librosa.resample(x, orig_sr=sr, target_sr=SR).astype(np.float32)
    return x


# ----------------------------------------------------------------------------- processing

def pitch_shift(x: np.ndarray, semitones: float) -> np.ndarray:
    """Formant-preserving pitch shift through ffmpeg's rubberband filter (0 = unchanged)."""
    if abs(semitones) < 0.05:
        return x
    ratio = 2 ** (semitones / 12)
    p = subprocess.run([R.ffmpeg_bin(), '-hide_banner', '-loglevel', 'error', '-f', 'f32le', '-ar', str(SR), '-ac', '1', '-i', 'pipe:0',
                        '-af', f'rubberband=pitch={ratio:.6f}:formant=preserved:pitchq=quality', '-f', 'f32le', '-ar', str(SR), '-ac', '1', 'pipe:1'],
                       input=x.astype(np.float32).tobytes(), capture_output=True, check=True)
    return np.frombuffer(p.stdout, dtype=np.float32).copy()


def process(x: np.ndarray, r: dict) -> np.ndarray:
    """render.py's chain after synthesis: pitch, EQ, delivery effort, saturation, compression, trim."""
    x = pitch_shift(x, float(r.get('pitch_st', 0.0)) + float(r.get('delivery_pitch_st', 0.0)))
    x = x.astype(np.float64)
    x = R.apply_eq(x, SR, (r.get('eq') or []) + (r.get('effort_eq') or []))
    x = R.normalize(x, R.PRE_LEVEL, -1.0)
    sat = r.get('saturation') or {}
    if float(sat.get('mix', 0)) > 0:
        x = R.saturate(x, float(sat.get('drive', 1.0)), float(sat.get('mix', 0.0)))
    c = r.get('compressor') or {}
    if c:
        x = R.compress(x, SR, threshold_db=float(c.get('threshold', -24)), ratio=float(c.get('ratio', 2.5)),
                       attack_ms=float(c.get('attack', 6)), release_ms=float(c.get('release', 90)), knee_db=float(c.get('knee', 6)))
    x = R.trim(x, SR)
    return x.astype(np.float32)


def resolve(voices: dict, speaker: str, kind: str) -> dict:
    d, s = voices.get('defaults', {}), voices['speakers'][speaker]
    r = R.deep_merge({k: v for k, v in d.items() if k != 'delivery'}, {k: v for k, v in s.items() if k != 'delivery'})
    r = R.deep_merge(r, d.get('delivery', {}).get(kind, {}))
    r = R.deep_merge(r, s.get('delivery', {}).get(kind, {}))
    r['kind'] = kind
    return r


def finish(x: np.ndarray, path: Path) -> dict:
    """Loudness and true peak checked on the decoded Opus, as render.render_clip does."""
    x0, ceiling, pre = x, -2.3, R.LOUDNESS_TARGET
    x = R.normalize(x0.astype(np.float64), pre, ceiling).astype(np.float32)
    for _ in range(6):
        R.encode_opus(x, path)
        y = R.decode(path)
        tp, lu = R.true_peak_db(y), R.loudness_lufs(y)
        if tp <= R.TRUE_PEAK_MAX and abs(lu - R.LOUDNESS_TARGET) <= 0.15:
            break
        if tp > R.TRUE_PEAK_MAX:
            ceiling -= (tp - R.TRUE_PEAK_MAX) + 0.2
        pre += R.LOUDNESS_TARGET - lu
        x = R.normalize(x0.astype(np.float64), pre, ceiling).astype(np.float32)
    return {'dur': len(y) / SR, 'lufs': lu, 'true_peak': tp}


def render_line(voices: dict, ln: dict, speaker: str, path: Path, voice_override: str | None = None) -> dict:
    kind = R.line_kind(ln)
    r = resolve(voices, speaker, kind)
    text = monotonic(ln.get('spoken') or ln['polytonic'])
    voice = voice_override or r['voice']
    rate = float(r.get('rate', 1.0)) * float(r.get('rate_mul', 1.0))
    x = process(synthesize(text, voice, rate), r)
    info = finish(x, path)
    info.update(text=text, voice=voice, kind=kind)
    return info


# ----------------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--lines', type=Path, default=HERE / 'lines.json')
    ap.add_argument('--voices', type=Path, default=HERE / 'voices_google.json')
    ap.add_argument('--out', type=Path, default=None, help='game root to write into (default: the repo game assets)')
    ap.add_argument('--only', action='append', default=[], help='render only this key (repeatable)')
    ap.add_argument('--list-voices', action='store_true', help='print the el-GR voices and exit')
    ap.add_argument('--audition', metavar='SPEAKER', help='render that speaker\'s lines with each voice in --voices-try into --out')
    ap.add_argument('--voices-try', default='', help='comma-separated Google voice names for --audition')
    ap.add_argument('--show-text', action='store_true', help='print the monotonic text of every line and exit (no API calls)')
    args = ap.parse_args(argv)

    keys, variants = R.load_lines(args.lines)
    if args.show_text:
        for k in keys:
            for ln in variants[k]:
                print(f'{k}-{ln["variant"]}: {monotonic(ln.get("spoken") or ln["polytonic"])}')
        return
    if args.list_voices:
        for v in sorted(list_voices(), key=lambda v: v['name']):
            print(v['name'], v.get('ssmlGender', ''), v.get('naturalSampleRateHertz', ''))
        return
    voices = json.loads(args.voices.read_text(encoding='utf-8'))
    root = (args.out or R.GAME_ROOT).resolve()

    if args.audition:
        sp = args.audition
        out = root / 'audition'
        out.mkdir(parents=True, exist_ok=True)
        report = []
        for name in [v for v in args.voices_try.split(',') if v]:
            for k in keys:
                for ln in variants[k]:
                    if sp not in ln['speakers']:
                        continue
                    path = out / f'{sp.lower()}-{name.split("-")[-1].lower()}-{k}-{ln["variant"]}.ogg'
                    info = render_line(voices, ln, sp, path, voice_override=name)
                    report.append({'file': path.name, **{k2: v for k2, v in info.items()}})
                    print(f'{path.name}: {info["dur"]:.2f} s')
        (out / 'audition.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
        return

    durations = {}
    old = None
    if args.only:
        bank = root / R.VOICEBANK_REL
        old = bank.read_text(encoding='utf-8') if bank.is_file() else None
    report = []
    for k in keys:
        for ln in variants[k]:
            for sp in ln['speakers']:
                path = root / R.clip_rel(k, ln['variant'], sp)
                if args.only and k not in args.only:
                    y = R.decode(path)
                    durations[(k, ln['variant'], sp)] = len(y) / SR
                    continue
                path.parent.mkdir(parents=True, exist_ok=True)
                info = render_line(voices, ln, sp, path)
                durations[(k, ln['variant'], sp)] = info['dur']
                report.append({'clip': path.name, **info})
                print(f'{path.name}: {info["dur"]:.2f} s, {info["lufs"]:.2f} LUFS, {info["true_peak"]:.2f} dBTP  [{info["voice"]}] {info["text"]}')
    R.write_voicebank(root / R.VOICEBANK_REL, keys, variants, durations)
    (HERE / 'google-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    if old is not None:
        print('voicebank.js regenerated (durations of the other keys read from their clips)')


if __name__ == '__main__':
    main()
