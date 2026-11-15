import pytest

from sitar_sitara.raga import SCALES, detect_raga
from sitar_sitara.sargam import (
    Swara,
    fit_to_range,
    midi_name,
    sa_midi,
    string_for,
    swara,
    width,
)
from sitar_sitara.taal import TAALS

SA = 61  # C#4


def test_sa_sits_where_a_sitar_is_tuned():
    assert midi_name(sa_midi(1)) == "C#4" and midi_name(sa_midi(2)) == "D4"
    assert all(55 <= sa_midi(pc) <= 66 for pc in range(12))


def test_swaras_are_named_from_sa():
    assert [swara(SA + d, SA).text() for d in range(12)] == list("SrRgGmMPdDnN")
    assert swara(SA + 12, SA).text() == "S'" and swara(SA - 1, SA).text() == "N,"
    assert swara(SA + 25, SA).text() == "r''" and swara(SA - 13, SA).text() == "N,,"
    assert swara(SA + 6, SA).name == "tivra Ma" and swara(SA - 2, SA).name == "komal Ni (mandra)"


def test_devanagari_marks_komal_tivra_and_octaves():
    assert Swara(0, 0).text("devanagari") == "सा"
    assert Swara(1, 0).text("devanagari") == "रे॒"  # komal Re: line below
    assert Swara(6, 0).text("devanagari") == "म॑"  # tivra Ma: stroke above
    assert Swara(0, 1).text("devanagari") == "सां"  # taar Sa: dot above
    assert Swara(11, -1).text("devanagari") == "न\u093cि"  # mandra Ni: dot below
    # Marks sit on their letter and take no column of their own.
    assert width(Swara(8, 1).text("devanagari")) == 1 and width("S'") == 2


def test_notes_below_the_open_main_string_move_to_the_lower_strings():
    assert string_for(SA - 7, SA).key == "baj"  # mandra Ma: the open main string
    assert string_for(SA + 19, SA).key == "baj"
    assert string_for(SA - 8, SA).key == "jod"  # mandra Ga
    assert string_for(SA - 12, SA).key == "jod"
    assert string_for(SA - 13, SA).key == "pancham"
    assert string_for(SA - 24, SA).key == "kharaj"
    with pytest.raises(ValueError):
        string_for(SA - 25, SA)


def test_fit_to_range_moves_by_octaves():
    assert fit_to_range(SA - 30, SA) == (SA - 18, True)
    assert fit_to_range(SA + 29, SA) == (SA + 17, True)
    assert fit_to_range(SA + 5, SA) == (SA + 5, False)


def test_taals_add_up():
    assert TAALS["teentaal"].matras == 16 and TAALS["rupak"].vibhags == (3, 2, 2)
    for taal in TAALS.values():
        assert len(taal.marks) == len(taal.vibhags) and taal.marks[0] == "X"
    assert TAALS["jhaptaal"].label == "Jhaptaal (10 matras, 2+3+2+3)"


def _phrase(degrees, sa=SA):
    midis = [sa + d for d in degrees]
    return midis, [0.25] * len(midis)


def test_bhairavi_on_d():
    # S r g m P d n, coming home to Sa.
    top = detect_raga(*_phrase([0, 1, 3, 5, 7, 8, 10, 12, 10, 8, 7, 5, 3, 1, 0], sa=62))[0]
    assert (top["sa"], top["scale"]) == ("D", "Bhairavi thaat")
    assert "Bhairavi" in top["ragas"]


def test_five_note_raga_beats_the_thaats_that_contain_it():
    top = detect_raga(*_phrase([0, 2, 4, 7, 9, 12, 9, 7, 4, 2, 0, 2, 0]))[0]
    assert (top["sa"], top["scale"]) == ("C#", "Raga Bhupali")


def test_seven_note_scale_is_not_mistaken_for_a_five_note_raga():
    # Yaman leaning hard on Sa, Re, Ga and Pa is still not Hamsadhwani.
    degrees = [0, 2, 4, 6, 7, 9, 11, 12, 11, 9, 7, 6, 4, 2, 0, 2, 4, 4, 7, 2, 0]
    assert detect_raga(*_phrase(degrees))[0]["scale"] == "Kalyan thaat"


def test_given_sa_only_the_scale_is_guessed():
    # These notes are Bhupali on C#, or Malkauns on F. Told Sa, there's no choice.
    midis, durations = _phrase([0, 2, 4, 7, 9, 7, 4, 2, 0])
    assert {r["sa"] for r in detect_raga(midis, durations, sa=5)} == {"F"}
    assert detect_raga(midis, durations, sa=5)[0]["scale"] == "Raga Malkauns"


def test_every_scale_starts_on_sa():
    assert all(degrees[0] == 0 for degrees, _ in SCALES.values())
    assert detect_raga([], []) == []
