import io

import mido

from sitar_sitara.render import TabNote, layout, to_midi, to_text
from sitar_sitara.sargam import swara
from sitar_sitara.taal import TAALS

SA = 61
TEENTAAL, KEHERWA, RUPAK = TAALS["teentaal"], TAALS["keherwa"], TAALS["rupak"]


def _note(t, degree, meend=False, fast=False):
    return TabNote(t, t + 0.2, SA + degree, swara(SA + degree, SA), "baj", meend, fast)


BEATS = [i * 0.5 for i in range(80)]  # 120 BPM


def test_notes_land_in_their_matras():
    # Sa on the first matra, Re and Ga sharing the second, Ma on the third.
    notes = [_note(0.0, 0), _note(0.5, 2), _note(0.75, 4), _note(1.0, 5)]
    (system,) = layout(notes, TEENTAAL, BEATS, 120)
    marks, row = system.lines
    assert row.split() == ["S", "RG", "m"] + ["-"] + ["|"] + (["-"] * 4 + ["|"]) * 2 + ["-"] * 4 + ["||"]
    assert marks.split() == ["X", "2", "0", "3"]
    # Each mark sits over the start of its vibhag.
    assert [marks.index(m) for m in "X203"] == [0] + [i + 2 for i, c in enumerate(row) if c == "|"][:3]


def test_a_matra_is_split_only_as_finely_as_it_needs():
    notes = [_note(0.0, 0), _note(0.5, 2), _note(0.625, 4), _note(1.0, 5), _note(1.375, 7)]
    (system,) = layout(notes, TEENTAAL, BEATS, 120)
    assert system.lines[1].split()[:3] == ["S", "RG--", "m--P"]
    cells = system.vibhags[0]["matras"]
    assert [len(m["parts"]) for m in cells] == [1, 4, 4, 1]
    assert cells[1]["parts"][1]["degree"] == 4 and cells[1]["parts"][2] is None
    assert [m["t"] for m in cells] == [0.0, 0.5, 1.0, 1.5]


def test_meend_and_fast_strokes_are_marked():
    notes = [_note(0.0, 2), _note(0.5, 4, meend=True), _note(1.0, 12, fast=True)]
    (system,) = layout(notes, TEENTAAL, BEATS, 120)
    assert system.lines[1].split()[:3] == ["R", "~G", "S'*"]
    assert system.lines_devanagari[1].split()[:3] == ["रे", "~ग", "सां*"]
    cell = system.vibhags[0]["matras"][1]["parts"][0]
    assert cell["meend"] and cell["name"] == "Ga" and cell["string"] == "baj"


def test_the_count_starts_at_the_first_note():
    (system,) = layout([_note(7.0, 0), _note(7.5, 2)], KEHERWA, BEATS, 120)
    assert system.lines[1].split()[:2] == ["S", "R"] and abs(system.start - 7.0) < 0.01


def test_short_taals_get_two_cycles_a_line():
    notes = [_note(i * 0.5, d) for i, d in enumerate([0, 2, 4, 5, 7, 9, 11, 12, 11, 9, 7, 5, 4, 2])]
    (system,) = layout(notes, RUPAK, BEATS, 120)
    marks, row = system.lines
    assert marks.split() == ["X", "2", "3"] * 2
    assert row.split() == "S R G | m P | D N || S' N D | P m | G R ||".split()
    assert [v["sam"] for v in system.vibhags] == [True, False, False] * 2
    assert system.end == 7.0
    assert len(layout(notes, TEENTAAL, BEATS, 120)) == 1


def test_long_silences_are_skipped_and_split_into_new_lines():
    systems = layout([_note(0.0, 0), _note(30.0, 2)], KEHERWA, BEATS, 120)
    assert len(systems) == 2
    # 30 s is 60 matras in: the fourth matra of the eighth cycle.
    assert abs(systems[1].start - 28.0) < 0.01
    assert systems[1].lines[1].split()[:5] == ["-", "-", "-", "-", "|"]


def test_text_in_either_script():
    systems = layout([_note(0.0, 1), _note(0.5, 6)], KEHERWA, BEATS, 120)
    latin = to_text(systems, ["Title"])
    assert latin.startswith("Title\n\n[0:00]\nX") and "\nr  M  " in latin
    assert "\nरे\u0952  म\u0951  -" in to_text(systems, ["Title"], "devanagari")


def test_midi_round_trip():
    data = to_midi([_note(0.0, 0), _note(0.5, 5)], 120)
    mid = mido.MidiFile(file=io.BytesIO(data))
    ons = [m.note for m in mid.tracks[0] if m.type == "note_on" and m.velocity]
    assert ons == [61, 66]
    # General MIDI's sitar.
    assert [m.program for m in mid.tracks[0] if m.type == "program_change"] == [104]
