"""MIDI -> bouzouki-like audio.

:class:`KarplusStrongRenderer` is a vectorised port of the app's BouzoukiSynth.kt (bouzouki-core):

* each string is a Karplus-Strong loop (delay n + 3-tap loss filter + fractional-delay all-pass),
  excited by low-passed noise combed at the picking point near the bridge (the nasal tone);
* a course sounds BOTH its strings: octave-pair courses the fretted note and its octave (thin string
  at 0.62 of the level, detuned -1.5 / +2 cents), unison courses two strings detuned -2.2 / +2.2 cents
  (the metallic jangle);
* a high-passed noise burst is the plectrum click; a new pick on a course damps the note ringing there
  (t60 25 ms), the end of a MIDI note damps it the same way;
* the bowl body (EQ biquads), a small Schroeder room and the soft limiter follow, as in the app.

The Kotlin synth runs sample by sample; here each string is one linear IIR filter
(``scipy.signal.lfilter``) whose transfer function is exactly the Kotlin loop's, so a 10 s phrase
renders in well under a second. Tremolo is in the MIDI (fast repeated picks, see phrases.py).

Other renderers (licensed sample libraries, SoundFonts) plug in through :class:`Renderer`; none is
shipped. Their data must be listed in DATA_LICENSES.yaml like any other source.
"""
import argparse
import glob
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from functools import lru_cache
from typing import Dict, List, Optional

import numpy as np
from scipy.signal import fftconvolve, lfilter

from .constants import AUDIO_SAMPLE_RATE, midi_to_hz
from .instruments import COURSE_NAMES, Instrument
from .notes import NoteEvent, fret_of, read_midi

DAMP_T60 = 0.025  # seconds: a string damped by the next pick on its course (Kotlin: v.damp(0.025))


def cents(c: float) -> float:
    return 2.0 ** (c / 1200.0)


def natural_t60(freq: float) -> float:
    """How long steel strings ring at ``freq`` Hz (high notes die sooner), as in the app."""
    return float(np.clip(7.5 * np.sqrt(130.0 / freq), 1.6, 9.0))


# ------------------------------------------------------------------------------------ filters
def _biquad(kind: str, fs: int, f: float, q: float, db: float = 0.0):
    """RBJ cookbook coefficients (b, a), same formulas as Biquad in BouzoukiSynth.kt."""
    w = 2 * np.pi * f / fs
    al = np.sin(w) / (2 * q)
    c = np.cos(w)
    if kind == "peak":
        amp = 10 ** (db / 40)
        b, a = [1 + al * amp, -2 * c, 1 - al * amp], [1 + al / amp, -2 * c, 1 - al / amp]
    elif kind == "low":
        b, a = [(1 - c) / 2, 1 - c, (1 - c) / 2], [1 + al, -2 * c, 1 - al]
    elif kind == "high":
        b, a = [(1 + c) / 2, -(1 + c), (1 + c) / 2], [1 + al, -2 * c, 1 - al]
    else:
        raise ValueError(kind)
    return np.array(b) / a[0], np.array(a) / a[0]


def body_filter(x: np.ndarray, fs: int, scale_db: float = 1.0) -> np.ndarray:
    """Staved-bowl body: high-pass 75 Hz, +3.5 dB air at 260 Hz, scooped mids, +3 dB jangle at
    3.2 kHz, low-pass 9.5 kHz. ``scale_db`` scales the peak gains (timbre variety)."""
    for args in (
        ("high", fs, 75.0, 0.7),
        ("peak", fs, 260.0, 0.9, 3.5 * scale_db),
        ("peak", fs, 1000.0, 1.2, -1.5 * scale_db),
        ("peak", fs, 3200.0, 1.2, 3.0 * scale_db),
        ("low", fs, min(9500.0, 0.45 * fs), 0.7),
    ):
        b, a = _biquad(*args)
        x = lfilter(b, a, x)
    return x


@lru_cache(maxsize=4)
def _reverb_ir(fs: int, seconds: float = 1.5) -> np.ndarray:
    """Impulse response of the room below (it is linear and time-invariant; it decays by 60 dB in
    well under a second), so long signals are reverberated with one FFT convolution."""
    imp = np.zeros(int(seconds * fs))
    imp[0] = 1.0
    return _schroeder_iir(imp, fs)


def schroeder_reverb(x: np.ndarray, fs: int) -> np.ndarray:
    """Wet output of the app's small Schroeder room (4 damped combs, 2 all-passes)."""
    return fftconvolve(x, _reverb_ir(fs))[: len(x)]


def _schroeder_iir(x: np.ndarray, fs: int) -> np.ndarray:
    """The room as IIR filters, exactly the Kotlin Reverb class (slow for long inputs: high order)."""
    out = np.zeros_like(x)
    for d in (1116, 1188, 1277, 1356):
        m = max(1, int(d * fs / 44100.0))
        # w = 0.25 x + 0.78 d,  d = 0.6 y + 0.4 d[-1],  y = w delayed by m
        a = np.zeros(m + 1)
        a[0], a[1] = 1.0, -0.4
        a[m] += -0.78 * 0.6
        b = np.zeros(m + 2)
        b[m], b[m + 1] = 0.25, -0.25 * 0.4
        out += lfilter(b, a, x)
    for d in (556, 441):
        m = max(1, int(d * fs / 44100.0))
        b = np.zeros(m + 1)
        b[0], b[m] = -1.0, 1.5
        a = np.zeros(m + 1)
        a[0], a[m] = 1.0, -0.5
        out = lfilter(b, a, out)
    return out


# ------------------------------------------------------------------------------------- string
def ks_string(
    freq: float,
    velocity: float,
    amp: float,
    n_samples: int,
    rng: np.random.Generator,
    fs: int = AUDIO_SAMPLE_RATE,
    damp_at: Optional[int] = None,
    damp_t60: float = DAMP_T60,
    pick_position: float = 0.14,
) -> np.ndarray:
    """One plucked steel string, ``n_samples`` long, damped from sample ``damp_at`` on.

    Kotlin loop: x[k] = g * AP(L(x))[k - n] with L(z) = a + (1-2a) z^-1 + a z^-2 the loss filter and
    AP(z) = (C + z^-1) / (1 + C z^-1) the fractional-delay all-pass, started from the excitation in
    the delay line. As a filter of the excitation e (placed at samples 0..n-1):
        X(z) (1 + C z^-1 - g z^-n L(z) (C + z^-1)) = E(z) (1 + C z^-1)
    """
    period = fs / freq
    n = max(2, int(period - 1.1))
    d = period - 1.0 - n
    c = (1 - d) / (1 + d)
    t60 = natural_t60(freq)
    g = 10.0 ** (-3.0 / (t60 * freq))  # loss per period
    a = 0.06 + 0.10 * (1 - velocity) + 0.04 * min(1.0, freq / 1200.0)

    # Excitation: plectrum-filtered noise, combed at the picking point near the bridge.
    lp = 0.12 + 0.38 * velocity
    ex = lfilter([lp], [1.0, -(1.0 - lp)], rng.uniform(-1, 1, n))
    pick = max(1, int(round(n * pick_position)))
    buf = ex - np.roll(ex, pick)
    buf -= buf.mean()
    buf *= amp * (0.35 + 0.65 * velocity) / max(1e-9, np.abs(buf).max())

    e = np.zeros(n_samples)
    e[: min(n, n_samples)] = buf[: min(n, n_samples)]
    den = np.zeros(n + 4)
    den[0], den[1] = 1.0, c
    den[n : n + 4] -= g * np.convolve([a, 1 - 2 * a, a], [c, 1.0])
    x = lfilter([1.0, c], den, e)
    if damp_at is not None and damp_at < n_samples:
        k = np.arange(n_samples - damp_at)
        x[damp_at:] *= 10.0 ** (-3.0 * k / (damp_t60 * fs))
    return x


@dataclass
class Humanize:
    """Random per-render variation of the instrument (all defaults = the app's synth)."""

    detune_scale: float = 1.0  # scales the pair detuning (cents)
    octave_level: float = 0.62  # thin string level on octave-pair courses
    pick_position: float = 0.14  # fraction of the string length from the bridge
    body_db: float = 1.0
    reverb: float = 0.16

    @staticmethod
    def random(rng: np.random.Generator) -> "Humanize":
        return Humanize(
            detune_scale=rng.uniform(0.4, 2.0),
            octave_level=rng.uniform(0.45, 0.8),
            pick_position=rng.uniform(0.08, 0.22),
            body_db=rng.uniform(0.5, 1.5),
            reverb=rng.uniform(0.0, 0.25),
        )


# ---------------------------------------------------------------------------------- renderers
class Renderer:
    """Turns notes into mono float audio at ``fs`` (22050 Hz for Basic Pitch).

    ``tuning_cents`` tunes every note away from A = 440 Hz (a record tuned so); the targets keep the
    nearest semitone and put the true pitch in the contours (see targets.py).
    """

    fs = AUDIO_SAMPLE_RATE

    def render(
        self,
        instrument: Instrument,
        notes: List[NoteEvent],
        duration: float,
        tuning_cents: float = 0.0,
        rng: Optional[np.random.Generator] = None,
    ) -> np.ndarray:
        raise NotImplementedError


class KarplusStrongRenderer(Renderer):
    def __init__(self, fs: int = AUDIO_SAMPLE_RATE, humanize: bool = True):
        self.fs = fs
        self.humanize = humanize

    def render(self, instrument, notes, duration, tuning_cents=0.0, rng=None):
        rng = rng or np.random.default_rng()
        fs = self.fs
        h = Humanize.random(rng) if self.humanize else Humanize()
        total = int(round(duration * fs))
        mix = np.zeros(total)
        tune = cents(tuning_cents)
        for note in notes:
            start = int(round(note.start * fs))
            if start >= total:
                continue
            course = instrument.courses[note.course]
            f = midi_to_hz(note.midi) * tune
            if course.octave_pair:
                strings = [(f * cents(-1.5 * h.detune_scale), 1.0), (2 * f * cents(2.0 * h.detune_scale), h.octave_level)]
            else:
                strings = [(f * cents(-2.2 * h.detune_scale), 0.8), (f * cents(2.2 * h.detune_scale), 0.8)]
            # Render until the string is damped and has died away (or the phrase ends).
            ring = int(round((note.end - note.start) * fs))
            length = min(total - start, ring + int(DAMP_T60 * fs * 3) + 1)
            vel = float(np.clip(note.velocity, 0.05, 1.0))
            voice = np.zeros(length)
            for freq, amp in strings:
                if freq >= fs / 2.2:
                    continue  # above what a delay line of >= 2 samples can play
                voice += ks_string(freq, vel, amp, length, rng, fs, damp_at=ring, pick_position=h.pick_position)
            # Plectrum click: high-passed noise burst, ~6 ms, decaying.
            nc = min(length, int(fs * 0.006))
            burst = rng.uniform(-1, 1, nc) * 0.22 * vel * (0.9993 ** (48000.0 / fs)) ** np.arange(nc)
            voice[:nc] += np.diff(np.concatenate([[0.0], burst]))
            mix[start : start + length] += voice
        s = body_filter(mix, fs, h.body_db)
        s = s + schroeder_reverb(s, fs) * h.reverb
        s *= 0.42
        return (s / (1.0 + np.abs(s)) * 1.15).astype(np.float32)  # soft limiter


class SampleFolderRenderer(Renderer):
    """Pluggable renderer for a licensed sample library (none is shipped).

    Expects ``<root>/<instrument>/<course>-<fret>[-<take>].wav`` (the same naming as the owner's
    recordings, see README), e.g. ``tetrachordo/C3-0-1.wav``. A missing fret is played from the
    nearest sampled fret of the same course, resampled. The note is faded out at its MIDI end.
    """

    def __init__(self, root: str, fs: int = AUDIO_SAMPLE_RATE):
        self.root, self.fs = root, fs
        self._cache: Dict[str, np.ndarray] = {}

    def _samples(self, instrument: Instrument, course: int) -> Dict[int, List[str]]:
        cname = COURSE_NAMES[instrument.name][course]
        found: Dict[int, List[str]] = {}
        for p in glob.glob(os.path.join(self.root, instrument.name, f"{cname}-*.wav")):
            parts = os.path.splitext(os.path.basename(p))[0].split("-")
            if len(parts) >= 2 and parts[0] == cname and parts[1].isdigit():
                found.setdefault(int(parts[1]), []).append(p)
        return found

    def _load(self, path: str) -> np.ndarray:
        if path not in self._cache:
            from .audio_io import read_wav

            self._cache[path] = read_wav(path, self.fs)
        return self._cache[path]

    def render(self, instrument, notes, duration, tuning_cents=0.0, rng=None):
        rng = rng or np.random.default_rng()
        total = int(round(duration * self.fs))
        mix = np.zeros(total)
        for note in notes:
            avail = self._samples(instrument, note.course)
            if not avail:
                raise FileNotFoundError(f"no samples for {instrument.name} course {note.course} under {self.root}")
            fret = fret_of(instrument, note)
            near = min(avail, key=lambda f: abs(f - fret))
            x = self._load(str(rng.choice(avail[near])))
            ratio = cents(100.0 * (fret - near) + tuning_cents)  # pitch ratio to apply
            if abs(ratio - 1.0) > 1e-6:  # resample (varispeed) to the wanted pitch
                pos = np.arange(0, len(x) - 1, ratio)
                x = np.interp(pos, np.arange(len(x)), x)
            start = int(round(note.start * self.fs))
            ring = int(round((note.end - note.start) * self.fs))
            x = x[: min(len(x), total - start, ring + int(DAMP_T60 * self.fs * 3))].copy()
            if ring < len(x):
                x[ring:] *= 10.0 ** (-3.0 * np.arange(len(x) - ring) / (DAMP_T60 * self.fs))
            mix[start : start + len(x)] += x * (0.3 + 0.7 * note.velocity)
        peak = np.abs(mix).max()
        return (mix / peak * 0.8 if peak > 0 else mix).astype(np.float32)


class SoundFontRenderer(Renderer):
    """Pluggable renderer for a licensed SoundFont (.sf2) through an external ``fluidsynth`` binary
    (FluidSynth is LGPL-2.1; it is run as a separate program, not bundled or imported).

    Octave-pair courses are written with both strings (note + octave) so the audio matches the
    targets; tuning is done by varispeed resampling after rendering.
    """

    def __init__(self, sf2_path: str, program: int = 25, fs: int = AUDIO_SAMPLE_RATE):
        if shutil.which("fluidsynth") is None:
            raise RuntimeError("fluidsynth binary not found on PATH")
        self.sf2, self.program, self.fs = sf2_path, program, fs

    def render(self, instrument, notes, duration, tuning_cents=0.0, rng=None):
        import pretty_midi

        from .audio_io import read_wav

        pm = pretty_midi.PrettyMIDI()
        tr = pretty_midi.Instrument(program=self.program)
        for n in notes:
            for off in instrument.courses[n.course].sounding_offsets():
                v = int(np.clip(n.velocity * 127 * (0.62 if off else 1.0), 1, 127))
                tr.notes.append(pretty_midi.Note(velocity=v, pitch=n.midi + off, start=n.start, end=n.end))
        pm.instruments.append(tr)
        with tempfile.TemporaryDirectory() as tmp:
            mid, wav = os.path.join(tmp, "in.mid"), os.path.join(tmp, "out.wav")
            pm.write(mid)
            subprocess.run(["fluidsynth", "-ni", "-g", "0.8", "-r", str(self.fs), "-F", wav, self.sf2, mid], check=True, capture_output=True)
            x = read_wav(wav, self.fs)
        if tuning_cents:
            pos = np.arange(0, len(x) - 1, cents(tuning_cents))
            x = np.interp(pos, np.arange(len(x)), x)
        total = int(round(duration * self.fs))
        x = np.pad(x, (0, max(0, total - len(x))))[:total]
        return x.astype(np.float32)


def make_renderer(kind: str, samples_dir: Optional[str] = None, soundfont: Optional[str] = None, humanize: bool = True) -> Renderer:
    if kind == "ks":
        return KarplusStrongRenderer(humanize=humanize)
    if kind == "samples":
        if not samples_dir:
            raise ValueError("--samples-dir is required for the sample renderer")
        return SampleFolderRenderer(samples_dir)
    if kind == "soundfont":
        if not soundfont:
            raise ValueError("--soundfont is required for the SoundFont renderer")
        return SoundFontRenderer(soundfont)
    raise ValueError(kind)


def main(argv=None) -> None:
    """Renders MIDI files to WAV for listening checks (build_dataset renders on the fly)."""
    from .audio_io import write_wav
    from .phrases import phrase_duration

    ap = argparse.ArgumentParser(description="Render bouzouki MIDI phrases to 22.05 kHz mono WAV")
    ap.add_argument("--midi", required=True, help="a .mid file or a directory of them")
    ap.add_argument("--out", required=True)
    ap.add_argument("--renderer", choices=["ks", "samples", "soundfont"], default="ks")
    ap.add_argument("--samples-dir")
    ap.add_argument("--soundfont")
    ap.add_argument("--tuning-cents", type=float, default=0.0)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)
    paths = sorted(glob.glob(os.path.join(a.midi, "*.mid"))) if os.path.isdir(a.midi) else [a.midi]
    r = make_renderer(a.renderer, a.samples_dir, a.soundfont)
    rng = np.random.default_rng(a.seed)
    os.makedirs(a.out, exist_ok=True)
    for p in paths:
        inst, notes = read_midi(p)
        audio = r.render(inst, notes, phrase_duration(notes), a.tuning_cents, rng)
        write_wav(os.path.join(a.out, os.path.splitext(os.path.basename(p))[0] + ".wav"), audio, r.fs)
    print(f"rendered {len(paths)} files to {a.out}")


if __name__ == "__main__":
    main()
