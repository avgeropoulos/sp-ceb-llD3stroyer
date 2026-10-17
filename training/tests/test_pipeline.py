"""Tests of the bouzouki fine-tuning pipeline (no training). Run from training/:  python -m pytest -q"""
import os

import numpy as np
import pytest

from bouzouki_train import constants as C
from bouzouki_train.augment import varispeed
from bouzouki_train.instruments import COURSE_NAMES, TETRACHORDO, TRICHORDO
from bouzouki_train.license_gate import DEFAULT_YAML, LicenseGateError, check, scan_forbidden
from bouzouki_train.notes import NoteEvent, read_csv, read_midi, write_csv, write_midi
from bouzouki_train.phrases import GENERATORS, every_fret
from bouzouki_train.real_data import load_real_dir, parse_single_note_name
from bouzouki_train.synth import KarplusStrongRenderer, _schroeder_iir, ks_string, schroeder_reverb
from bouzouki_train.targets import build_sparse_targets, to_dense

FS = C.AUDIO_SAMPLE_RATE


def test_basic_pitch_resolution():
    assert (FS, C.FFT_HOP, C.AUDIO_N_SAMPLES, C.ANNOTATIONS_FPS, C.ANNOT_N_FRAMES) == (22050, 256, 43844, 86, 172)
    assert (C.N_FREQ_BINS_NOTES, C.N_FREQ_BINS_CONTOURS) == (88, 264)
    assert C.FREQ_BINS_NOTES[69 - 21] == pytest.approx(440.0)


def test_instruments_match_app():
    assert [(c.open_midi, c.octave_pair) for c in TETRACHORDO.courses] == [(48, True), (53, True), (57, False), (62, False)]
    assert [(c.open_midi, c.octave_pair) for c in TRICHORDO.courses] == [(50, True), (57, False), (62, False)]
    assert (TETRACHORDO.frets, TRICHORDO.frets) == (27, 26)
    assert TETRACHORDO.position(57) == (2, 0)  # open A course, not F course fret 4


@pytest.mark.parametrize("kind", sorted(GENERATORS))
def test_phrases_are_playable(kind):
    rng = np.random.default_rng(3)
    for inst in (TETRACHORDO, TRICHORDO):
        notes = GENERATORS[kind](inst, rng)
        assert notes
        for n in notes:
            assert 0 <= n.midi - inst.courses[n.course].open_midi <= inst.frets
            assert n.end > n.start >= 0
        for c in range(len(inst.courses)):  # one string sounds one note at a time
            cn = sorted((n for n in notes if n.course == c), key=lambda n: n.start)
            assert all(a.end <= b.start + 1e-9 for a, b in zip(cn, cn[1:]))


def test_every_fret_covers_the_course():
    notes = every_fret(TETRACHORDO, 1, np.random.default_rng(0))
    assert [n.midi - 53 for n in notes] == list(range(28))


def test_midi_and_csv_roundtrip(tmp_path):
    notes = [NoteEvent(0.5, 1.0, 50, 0, 0.8), NoteEvent(0.75, 1.25, 64, 3, 0.6)]
    write_midi(str(tmp_path / "a.mid"), TETRACHORDO, notes)
    inst, back = read_midi(str(tmp_path / "a.mid"))
    assert inst is TETRACHORDO and [(n.midi, n.course) for n in back] == [(50, 0), (64, 3)]
    write_csv(str(tmp_path / "a.csv"), TETRACHORDO, notes)
    back = read_csv(str(tmp_path / "a.csv"), TETRACHORDO, time_offset=0.1)
    assert back[0].start == pytest.approx(0.6) and back[1].midi == 64


def _f0(x, fmin=60.0, fmax=1500.0):
    """Fundamental from the autocorrelation peak, refined with a parabola."""
    x = x - x.mean()
    ac = np.correlate(x, x, "full")[len(x) - 1:]
    lo, hi = int(FS / fmax), int(FS / fmin)
    k = lo + int(np.argmax(ac[lo:hi]))
    a, b, c = ac[k - 1], ac[k], ac[k + 1]
    return FS / (k + 0.5 * (a - c) / (a - 2 * b + c))


@pytest.mark.parametrize("hz", [130.81, 220.0, 440.0, 880.0])
def test_ks_string_is_in_tune(hz):
    x = ks_string(hz, 0.85, 1.0, FS, np.random.default_rng(0))
    cents_off = 1200 * np.log2(_f0(x[2000:2000 + 8192]) / hz)
    assert abs(cents_off) < 5
    assert np.abs(x[-200:]).max() < np.abs(x[:2000]).max()  # it decays


def test_octave_pair_sounds_both_strings():
    r = KarplusStrongRenderer(humanize=False)
    y = r.render(TETRACHORDO, [NoteEvent(0.0, 1.5, 48 + 9, 0)], 1.5, 0.0, np.random.default_rng(0))  # A3 on the C course
    spec = np.abs(np.fft.rfft(y[2000:2000 + 16384] * np.hanning(16384)))
    f = np.fft.rfftfreq(16384, 1 / FS)
    band = lambda hz: spec[(f > hz * 0.97) & (f < hz * 1.03)].max()  # noqa: E731
    assert band(440.0) > 0.05 * spec.max()  # the octave string (and the 2nd harmonic)
    assert np.isfinite(y).all() and np.abs(y).max() <= 1.15


def test_fft_reverb_equals_iir():
    x = np.random.default_rng(1).normal(size=FS // 2)
    assert np.allclose(schroeder_reverb(x, FS), _schroeder_iir(x, FS), atol=1e-6)


def test_targets_octave_pair_labels_both_pitches():
    sp = build_sparse_targets(TETRACHORDO, [NoteEvent(0.5, 1.0, 48, 0)], 2.0)
    d = to_dense(sp)
    assert d["note"].shape == (len(np.arange(0, 2.0 + C.ANNOTATION_HOP, C.ANNOTATION_HOP)), 88)
    assert set(np.nonzero(d["note"].any(axis=0))[0]) == {48 - 21, 60 - 21}
    t0, t1 = int(round(0.5 * 86)), int(round(1.0 * 86))
    assert np.array_equal(np.nonzero(d["note"][:, 48 - 21])[0], np.arange(t0, t1 + 1))  # inclusive ends
    assert np.array_equal(np.nonzero(d["onset"][:, 48 - 21])[0], [t0])  # one onset frame
    assert set(np.nonzero(d["contour"].any(axis=0))[0]) == {3 * (48 - 21), 3 * (60 - 21)}


def test_targets_unison_and_detune():
    d0 = to_dense(build_sparse_targets(TETRACHORDO, [NoteEvent(0.2, 0.6, 62, 3)], 1.0))
    d40 = to_dense(build_sparse_targets(TETRACHORDO, [NoteEvent(0.2, 0.6, 62, 3)], 1.0, tuning_cents=40.0))
    assert set(np.nonzero(d0["note"].any(axis=0))[0]) == {62 - 21}  # unison: one pitch
    assert np.array_equal(d0["note"], d40["note"])  # notes keep the written semitone
    c0 = np.nonzero(d0["contour"].any(axis=0))[0]
    c40 = np.nonzero(d40["contour"].any(axis=0))[0]
    assert list(c40) == [c0[0] + 1]  # 40 cents = 1.2 contour bins
    with pytest.raises(ValueError):
        build_sparse_targets(TETRACHORDO, [NoteEvent(0.2, 0.6, 62, 3)], 1.0, tuning_cents=60.0)


def test_targets_dedupe_overlaps():
    # C course fret 12 (C4 + C5) and D course fret 10 (C5): C5 labelled once per frame.
    sp = build_sparse_targets(TETRACHORDO, [NoteEvent(0.1, 0.5, 60, 0), NoteEvent(0.1, 0.5, 72, 3)], 1.0)
    assert len(np.unique(sp["notes_indices"], axis=0)) == len(sp["notes_indices"])


def test_varispeed_moves_notes_with_audio():
    x = np.zeros(FS * 2, np.float32)
    y, notes = varispeed(x, [NoteEvent(1.0, 1.5, 60, 3)], 50.0)
    r = 2 ** (50 / 1200)
    assert len(y) == round(len(x) / r) and notes[0].start == pytest.approx(1.0 / r)


def test_real_dir_naming(tmp_path):
    from bouzouki_train.audio_io import write_wav

    assert parse_single_note_name(TETRACHORDO, "C3-5-2") == (0, 5)
    assert parse_single_note_name(TRICHORDO, "D4-26-1") == (2, 26)
    assert parse_single_note_name(TETRACHORDO, "chromatic-up-1") is None
    assert COURSE_NAMES["trichordo"] == ["D3", "A3", "D4"]
    d = tmp_path / "tetrachordo"
    d.mkdir()
    y = KarplusStrongRenderer().render(TETRACHORDO, [NoteEvent(0.3, 1.0, 60, 2)], 1.5, 0.0, np.random.default_rng(0))
    write_wav(str(d / "A3-3-1.wav"), y)
    recs = load_real_dir(str(tmp_path))
    assert len(recs) == 1 and recs[0].notes[0].midi == 60 and abs(recs[0].notes[0].start - 0.3) < 0.03


def test_license_gate_refuses_shipped_file():
    with pytest.raises(LicenseGateError, match="owner_approval.approved"):
        check(DEFAULT_YAML, ["bouzouki_synth", "basic_pitch_icassp_2022"], [])
    check(DEFAULT_YAML, ["bouzouki_synth"], [], require_approval=False)  # dry run: scan only


def _yaml(tmp_path, license="Own work", approved=True):
    p = tmp_path / "lic.yaml"
    p.write_text(
        "owner_approval: {approved: %s}\n"
        "sources:\n  - {id: own, license: '%s', approved: %s}\n" % (str(approved).lower(), license, str(approved).lower())
    )
    return str(p)


def test_license_gate_passes_when_approved(tmp_path):
    assert check(_yaml(tmp_path), ["own"], [])
    with pytest.raises(LicenseGateError, match="not listed"):
        check(_yaml(tmp_path), ["own", "other"], [])
    with pytest.raises(LicenseGateError, match="non-commercial"):
        check(_yaml(tmp_path, license="CC BY-NC 4.0"), ["own"], [])


def test_forbidden_sources_are_found_even_in_dry_run(tmp_path):
    bad = tmp_path / "data" / "Synth_Tab-v1"
    bad.mkdir(parents=True)
    assert scan_forbidden([str(tmp_path / "data")], environment=False, repo=False)
    assert scan_forbidden([], ["weights from MuScriptor"], environment=False, repo=False)
    assert scan_forbidden([], ["gtr-snipe fork"], environment=False, repo=False)
    with pytest.raises(LicenseGateError, match="SynthTab"):
        check(_yaml(tmp_path), ["own"], [str(tmp_path / "data")], require_approval=False)
    assert not scan_forbidden([], environment=True, repo=True)  # this repo and environment are clean


def test_tfrecord_matches_basic_pitch_reader(tmp_path):
    tf = pytest.importorskip("tensorflow")
    pytest.importorskip("basic_pitch")
    from basic_pitch.data.tf_example_deserialization import transcription_dataset

    from bouzouki_train.audio_io import wav_bytes
    from bouzouki_train.tfrecords import ShardWriter, make_example

    notes = [NoteEvent(0.2 + 0.3 * i, 0.5 + 0.3 * i, 50 + i, 0) for i in range(8)]
    audio = KarplusStrongRenderer().render(TETRACHORDO, notes, 3.5, 0.0, np.random.default_rng(0))
    sparse = build_sparse_targets(TETRACHORDO, notes, len(audio) / FS)
    with ShardWriter(str(tmp_path), "t") as w:
        w.write("train", make_example("t/0", "t", wav_bytes(audio), sparse), {"sources": ["own"]})
    files = [os.path.join(tmp_path, "t", "splits", "train", "00000.tfrecord")]
    ds = transcription_dataset(lambda: iter(files), 2, random_seed=True)
    n = 0
    for x, y, wts in ds:
        assert tuple(x.shape) == (C.AUDIO_N_SAMPLES, 1)
        assert tuple(y["note"].shape) == (172, 88) and tuple(y["contour"].shape) == (172, 264)
        assert float(tf.reduce_sum(y["onset"])) > 0
        n += 1
    assert n == 2
