"""Developer tool for .github/workflows/analyze-song.yml: close-up spectrograms of a song without its voice.

usage: song_closeups.py <stem.wav> "<m:ss-m:ss>[, ...]" <out dir>
For each window writes closeup_<from>_<to>.npz: a constant-Q magnitude spectrogram in dB (36 bins per
octave from E2, 5.8 ms frames, float16) and a pick-attack curve (spectral rise above 2 kHz). Only these
numbers are kept, never the sound.
"""
import sys
import numpy as np
import soundfile as sf
import librosa

SR, HOP, BPO, FMIN_MIDI, OCTAVES = 44100, 256, 36, 40, 5


def seconds(t):
    m, s = t.strip().split(':')
    return int(m) * 60 + float(s)


def main(stem, windows, out):
    info = sf.info(stem)
    for w in [w for w in windows.split(',') if w.strip()]:
        a, b = (seconds(x) for x in w.split('-'))
        y, sr = sf.read(stem, start=int(a * info.samplerate), stop=int(b * info.samplerate), dtype='float32', always_2d=True)
        y = y.mean(axis=1)
        if sr != SR:
            y = librosa.resample(y, orig_sr=sr, target_sr=SR)
        c = np.abs(librosa.cqt(y, sr=SR, hop_length=HOP, fmin=librosa.midi_to_hz(FMIN_MIDI), n_bins=BPO * OCTAVES, bins_per_octave=BPO))
        db = librosa.amplitude_to_db(c, ref=np.max(c) + 1e-9).astype(np.float16)
        s = np.abs(librosa.stft(y, n_fft=1024, hop_length=HOP))
        hi = s[int(2000 / (SR / 1024)):]
        rise = np.maximum(0, np.diff(np.log1p(hi * 100), axis=1)).sum(axis=0)
        rise = np.concatenate([[0], rise]).astype(np.float16)
        name = f"closeup_{a:.0f}_{b:.0f}.npz"
        np.savez_compressed(f"{out}/{name}", cqt_db=db, rise=rise, start=a, hop_s=HOP / SR, fmin_midi=FMIN_MIDI, bins_per_octave=BPO)
        print(name, db.shape)


if __name__ == '__main__':
    main(*sys.argv[1:4])
