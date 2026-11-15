import io
import shutil
import time

import mido
import pytest
from fastapi.testclient import TestClient

from sitar_sitara import server
from sitar_sitara.fetch import Video


@pytest.fixture
def client():
    return TestClient(server.app)


def _wait(client, job_id, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "error"):
            return job
        time.sleep(0.2)
    raise AssertionError("job timed out")


def test_index(client):
    res = client.get("/")
    assert res.status_code == 200 and "Sitar Sitara" in res.text


def test_upload_to_sargam(client, phrase_wav):
    with open(phrase_wav, "rb") as f:
        res = client.post("/api/upload", files={"file": ("song.wav", f, "audio/wav")},
                          data={"taal": "keherwa", "sa": "1"})
    assert res.status_code == 200
    job = _wait(client, res.json()["id"])
    assert job["status"] == "done", job.get("error")
    result = job["result"]
    assert len(result["notes"]) == 21 and result["taal"]["matras"] == 8
    assert result["sa"]["name"] == "C#" and result["sa"]["chosen"]
    # The page draws the notation from these cells.
    first = result["systems"][0]["vibhags"][0]
    assert first["mark"] == "X" and first["sam"]
    assert [p["degree"] for p in first["matras"][0]["parts"]] == [0, 2]

    text = client.get(f"/api/jobs/{job['id']}/sargam.txt")
    assert "Kalyan thaat" in text.text and "SR " in text.text
    deva = client.get(f"/api/jobs/{job['id']}/sargam.txt", params={"script": "devanagari"})
    assert "सारे" in deva.text
    assert client.get(f"/api/jobs/{job['id']}/sargam.txt", params={"script": "x"}).status_code == 400
    midi = client.get(f"/api/jobs/{job['id']}/sargam.mid")
    assert mido.MidiFile(file=io.BytesIO(midi.content)).length > 0


def test_upload_with_bad_options_is_rejected(client, phrase_wav):
    for data, status in (({"taal": "waltz"}, 400), ({"sa": "12"}, 422)):
        with open(phrase_wav, "rb") as f:
            res = client.post("/api/upload", files={"file": ("song.wav", f, "audio/wav")}, data=data)
        assert res.status_code == status


def test_taals_are_listed(client):
    taals = client.get("/api/taals").json()
    assert {"key": "teentaal", "label": "Teentaal (16 matras, 4+4+4+4)"} in taals


def test_youtube_job_reports_times_in_video_clock(client, phrase_wav, monkeypatch, tmp_path):
    calls = {}

    def fake_download(url, dest, start, end):
        calls.update(url=url, start=start, end=end)
        clip = dest / "audio.wav"
        shutil.copy(phrase_wav, clip)
        return Video(id="dQw4w9WgXcQ", title="Fake song", duration=300.0, path=clip)

    monkeypatch.setattr(server, "download_audio", fake_download)
    res = client.post(
        "/api/jobs", json={"url": "https://youtu.be/dQw4w9WgXcQ", "start": 60, "end": 70}
    )
    assert res.status_code == 200
    job = _wait(client, res.json()["id"])
    assert job["status"] == "done", job.get("error")
    assert calls == {"url": "https://youtu.be/dQw4w9WgXcQ", "start": 60.0, "end": 70.0}
    assert job["video_id"] == "dQw4w9WgXcQ" and job["title"] == "Fake song"
    # The clip starts at 1:00, and the first note is 0.5 s into the clip.
    assert abs(job["result"]["notes"][0]["start"] - 60.5) < 0.05


def test_download_errors_are_reported(client, monkeypatch):
    def failing(*a, **kw):
        raise server.FetchError("YouTube download failed: video unavailable")

    monkeypatch.setattr(server, "download_audio", failing)
    res = client.post("/api/jobs", json={"url": "https://youtu.be/dQw4w9WgXcQ"})
    job = _wait(client, res.json()["id"])
    assert job["status"] == "error" and "unavailable" in job["error"]


@pytest.mark.parametrize(
    "body",
    [
        {"url": "https://vimeo.com/1"},
        {"url": "https://youtu.be/dQw4w9WgXcQ", "start": 50, "end": 40},
        {"url": "https://youtu.be/dQw4w9WgXcQ", "taal": "waltz"},
    ],
)
def test_bad_requests(client, body):
    assert client.post("/api/jobs", json=body).status_code == 400


def test_unknown_job(client):
    assert client.get("/api/jobs/nope").status_code == 404
