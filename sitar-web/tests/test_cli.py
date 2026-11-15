import argparse

import pytest

from sitar_sitara.cli import main, parse_sa, parse_time


def test_parse_time():
    assert parse_time("75") == 75 and parse_time("1:15") == 75 and parse_time("1:01:00") == 3660


def test_parse_sa():
    assert parse_sa("C#") == 1 and parse_sa("db") == 1 and parse_sa(" d ") == 2
    with pytest.raises(argparse.ArgumentTypeError):
        parse_sa("H")


def test_cli_writes_sargam_and_midi(phrase_wav, tmp_path, capsys):
    out, mid = tmp_path / "sargam.txt", tmp_path / "sargam.mid"
    assert main([str(phrase_wav), "-o", str(out), "--midi", str(mid), "--taal", "keherwa"]) == 0
    text = out.read_text(encoding="utf-8")
    assert "Sa = C#" in text and "Keherwa (8 matras, 4+4)" in text and "Kalyan thaat" in text
    assert "SR " in text and "NS'" in text and mid.stat().st_size > 0


def test_cli_in_devanagari_with_sa_given(phrase_wav, capsys):
    assert main([str(phrase_wav), "--script", "devanagari", "--sa", "C#"]) == 0
    assert "सारे" in capsys.readouterr().out


def test_cli_rejects_bad_link(capsys):
    assert main(["https://vimeo.com/1"]) == 1
    assert "YouTube" in capsys.readouterr().err
