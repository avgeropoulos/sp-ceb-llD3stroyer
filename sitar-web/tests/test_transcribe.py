from difflib import SequenceMatcher

from synth import BHAIRAVI, G, P, SA, YAMAN, YAMAN_MIDI

from sitar_sitara.pipeline import Options, analyze


def test_clean_phrase_is_transcribed_exactly(phrase_wav):
    result = analyze(str(phrase_wav))
    notes = result["notes"]
    assert [(n["midi"], n["meend"], n["fast"]) for n in notes] == YAMAN
    assert result["sa"]["name"] == "C#" and result["sa"]["midi"] == SA
    assert result["raga"][0]["scale"] == "Kalyan thaat"
    assert "Yaman" in result["raga"][0]["ragas"]
    assert 110 <= result["tempo"] <= 130


def test_a_meend_leaves_no_passing_notes(phrase_wav):
    # Ga is pulled up to Pa through Ma. Neither Ma is a note: the pitch
    # never rests there.
    notes = analyze(str(phrase_wav))["notes"]
    k = next(i for i, n in enumerate(notes) if n["meend"] and n["midi"] == P)
    assert notes[k - 1]["midi"] == G and not notes[k - 1]["meend"]
    assert notes[k]["sargam"] == "P" and notes[k]["swara"] == "Pa"
    # Nothing in the phrase is komal, and shuddha Ma is never played.
    assert {n["sargam"].strip("',") for n in notes} == set("SRGMPDN")


def test_left_hand_work_on_a_sitar_tuned_off_the_piano(bhairavi_wav):
    result = analyze(str(bhairavi_wav), Options(taal="keherwa"))
    found = [("~" if n["meend"] else "") + n["sargam"] for n in result["notes"]]
    # A slow meend, a sway on one note, and a pull up and back are each read
    # as the notes they rest on, and nothing in between.
    assert found == BHAIRAVI
    assert result["sa"]["name"] == "D" and 25 <= result["tuning_cents"] <= 45
    assert result["raga"][0]["scale"] == "Bhairavi thaat"


def test_phrase_survives_tabla_tanpura_and_noise(mix_wav):
    notes = analyze(str(mix_wav))["notes"]
    found = [n["midi"] for n in notes]
    # Allow a dropped or extra note, but the melody must come through.
    matcher = SequenceMatcher(a=found, b=YAMAN_MIDI, autojunk=False)
    matched = sum(block.size for block in matcher.get_matching_blocks())
    assert matched >= len(YAMAN_MIDI) - 2, found


def test_start_and_end_trim_and_offset_times(phrase_wav):
    notes = analyze(str(phrase_wav), Options(start=1.0, end=2.6, sa=1))["notes"]
    # The 0:01-0:02.6 window holds Ga through taar Sa.
    assert [n["sargam"] for n in notes][:6] == ["G", "M", "P", "D", "N", "S'"]
    # Times are reported in the original recording's clock.
    assert abs(notes[0]["start"] - 1.0) < 0.05


def test_sa_can_be_set_by_the_player(phrase_wav):
    # Told that Sa is D, the same notes read a semitone lower.
    result = analyze(str(phrase_wav), Options(sa=2))
    assert result["sa"] == {"name": "D", "pitch_class": 2, "midi": 62, "chosen": True}
    assert result["notes"][0]["sargam"] == "N," and result["notes"][0]["string"] == "baj"
    assert all(r["sa"] == "D" for r in result["raga"])


def test_unknown_taal_is_an_error(phrase_wav):
    import pytest

    with pytest.raises(ValueError, match="Unknown taal"):
        analyze(str(phrase_wav), Options(taal="waltz"))
