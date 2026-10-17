import subprocess

import numpy as np
import pytest

from bouzouki_tabs.analysis import analyze
from bouzouki_tabs.audio import format_time, parse_time
from bouzouki_tabs.dromos import detect_dromos
from bouzouki_tabs.fretboard import Position, assign_positions, fit_to_range
from bouzouki_tabs.instrument import TUNINGS, get_tuning, note_name, parse_note
from bouzouki_tabs.midi import write_midi
from bouzouki_tabs.notes import Note, segment_notes, track_melody
from bouzouki_tabs.pipeline import transcribe
from bouzouki_tabs.rhythm import Grid, estimate_tempo
from bouzouki_tabs.tab import render_tab
from bouzouki_tabs.youtube import is_url, video_id

from synth import SR, melody

TETRA = TUNINGS["tetrachordo"]

# A Hijaz phrase on D: quarter = 0.6 s (100 BPM).
Q, E, S = 0.6, 0.3, 0.15
HIJAZ = [
    (None, Q), (62, E), (63, E), (66, E), (67, E), (69, Q), (67, S), (66, S), (63, S), (62, S),
    (66, E), (63, E), (62, Q), (57, E), (62, E), (74, S), (72, S), (70, S), (69, S),
    (67, E), (66, E), (63, E), (62, E), (62, Q),
]
HIJAZ_PITCHES = [m for m, _ in HIJAZ if m is not None]


def _transcribe_frames(y):
    analysis = analyze(y, SR, TETRA.lowest - 3, TETRA.highest)
    return analysis, segment_notes(analysis, track_melody(analysis))


@pytest.mark.parametrize("noise,cents", [(0.0, 0.0), (0.05, 0.0), (0.05, -30.0), (0.05, 20.0)])
def test_notes_are_recovered(noise, cents):
    y, starts = melody(HIJAZ, noise=noise, cents=cents)
    analysis, notes = _transcribe_frames(y)
    assert [n.midi for n in notes] == HIJAZ_PITCHES
    assert analysis.tuning_cents == pytest.approx(cents, abs=6)
    assert np.max(np.abs(np.array([n.start for n in notes]) - starts)) < 0.04


def test_low_octave_courses_are_not_read_an_octave_up():
    seq = [(48, E), (53, E), (55, E), (57, E), (60, E), (53, E), (48, Q)]
    y, _ = melody(seq, noise=0.02)
    _, notes = _transcribe_frames(y)
    assert [n.midi for n in notes] == [m for m, _ in seq]


def test_tremolo_on_one_note_is_split_into_strokes():
    y, _ = melody([(69, 0.12)] * 8 + [(67, Q)], noise=0.02)
    _, notes = _transcribe_frames(y)
    assert [n.midi for n in notes] == [69] * 8 + [67]


def test_silence_gives_no_notes():
    y = np.zeros(SR * 2, dtype=np.float32)
    _, notes = _transcribe_frames(y)
    assert notes == []


def test_tempo_is_detected():
    y, _ = melody([(62 + (i % 5), 0.5) for i in range(24)], noise=0.02)
    analysis = analyze(y, SR, 45, 88)
    assert estimate_tempo(analysis.onset_env, analysis.frame_rate) == pytest.approx(120, rel=0.03)


def test_dromos_detection():
    notes = [Note(i * 0.3, i * 0.3 + 0.3, m) for i, m in enumerate(HIJAZ_PITCHES)]
    best = detect_dromos(notes)[0]
    assert (best.name, best.tonic) == ("Hijaz", "D")

    ousak = [62, 63, 65, 67, 69, 67, 65, 63, 62, 60, 62, 65, 63, 62]
    notes = [Note(i * 0.3, i * 0.3 + 0.3, m) for i, m in enumerate(ousak)]
    best = detect_dromos(notes)[0]
    assert (best.name, best.tonic) == ("Ousak", "D")


def test_positions_match_pitches_and_stay_close():
    notes = [Note(i * 0.2, i * 0.2 + 0.2, m) for i, m in enumerate([62, 64, 66, 67, 69, 71, 72, 74])]
    positions = assign_positions(notes, TETRA)
    for note, pos in zip(notes, positions):
        assert TETRA.strings[pos.string] + pos.fret == note.midi
    frets = [p.fret for p in positions if p.fret]
    assert max(frets) - min(frets) <= 12


def test_out_of_range_notes_move_by_octave():
    assert fit_to_range(36, TETRA) == (48, 1)
    assert fit_to_range(100, TETRA) == (88, -1)
    pos = assign_positions([Note(0, 1, 36)], TETRA)[0]
    assert pos == Position(0, 0, 1)


def test_tunings_and_note_names():
    assert parse_note("C3") == 48 and parse_note("F#3") == 54 and parse_note("Bb2") == 46
    assert note_name(62) == "D4"
    assert get_tuning("trichordo").strings == (50, 57, 62)
    assert get_tuning("C3, F3, A3, D4").strings == TETRA.strings
    with pytest.raises(ValueError):
        get_tuning("D4,A3")


def test_tab_rendering():
    notes = [Note(0.0, 0.3, 62), Note(0.3, 0.6, 63), Note(0.6, 0.9, 57), Note(0.9, 1.2, 74)]
    positions = assign_positions(notes, TETRA)
    tab = render_tab(notes, positions, TETRA, Grid(100, 0.0, 4, 4), width=120, time_offset=10)
    lines = tab.splitlines()
    assert lines[0] == "[0:10.0]  bar 1"
    assert [l[0] for l in lines[1:5]] == ["D", "A", "F", "C"]
    assert lines[1].startswith("D|0-----1-----")
    assert lines[2].startswith("A|------------0-----")
    assert "12" in lines[1]


def test_long_rests_are_collapsed():
    notes = [Note(0.0, 0.3, 62), Note(20.0, 20.3, 64)]
    positions = assign_positions(notes, TETRA)
    tab = render_tab(notes, positions, TETRA, Grid(120, 0.0, 4, 4))
    assert "bars without bouzouki" in tab
    assert tab.count("bar ") <= 4


def test_trichordo_labels():
    tri = TUNINGS["trichordo"]
    tab = render_tab([Note(0, 0.3, 62)], [Position(2, 0)], tri, Grid(120, 0.0, 4, 4))
    assert [l[0] for l in tab.splitlines()[1:4]] == ["d", "A", "D"]


def test_midi_file(tmp_path):
    path = tmp_path / "out.mid"
    write_midi([Note(0, 0.5, 62), Note(0.5, 1.0, 64)], path, 120)
    data = path.read_bytes()
    assert data.startswith(b"MThd") and b"MTrk" in data and data.endswith(b"\xff\x2f\x00")


def test_time_helpers_and_urls():
    assert parse_time("1:23") == 83 and parse_time("0:01:02.5") == 62.5 and parse_time("") is None
    assert format_time(83.25) == "1:23.2"
    assert video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1") == "dQw4w9WgXcQ"
    assert video_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert is_url("https://youtu.be/x") and not is_url("song.mp3")


def test_pipeline_on_a_file(tmp_path):
    y, _ = melody(HIJAZ, noise=0.02)
    raw, mp3 = tmp_path / "hijaz.raw", tmp_path / "hijaz.mp3"
    raw.write_bytes(y.tobytes())
    subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", str(raw), str(mp3)], check=True
    )
    result = transcribe(str(mp3), start=0.3)
    assert [n.midi for n in result.notes] == HIJAZ_PITCHES
    assert result.grid.bpm == pytest.approx(100, rel=0.05)
    assert str(result.dromoi[0]) == "Hijaz on D"
    data = result.to_dict()
    assert data["notes"][0]["time"] == pytest.approx(0.6, abs=0.04)
    assert data["notes"][0]["course"] == "D" and data["notes"][0]["fret"] == 0
    assert "[0:00.6]  bar 1" in result.tab
