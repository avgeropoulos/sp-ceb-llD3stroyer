"""Web app: paste a YouTube link (or upload audio), get sitar sargam."""

from __future__ import annotations

import shutil
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse, Response
from pydantic import BaseModel, Field

from .fetch import FetchError, download_audio, video_id
from .pipeline import Options, analyze
from .sargam import SCRIPTS
from .taal import DEFAULT, TAALS

STATIC = Path(__file__).parent / "static"
MAX_UPLOAD = 60 * 1024 * 1024
JOB_TTL = 60 * 60  # forget finished jobs after an hour

app = FastAPI(title="Sitar Sitara")
_executor = ThreadPoolExecutor(max_workers=2)
_jobs: dict[str, dict] = {}
_lock = threading.Lock()


class JobRequest(BaseModel):
    url: str
    start: float | None = Field(None, ge=0)
    end: float | None = Field(None, gt=0)
    taal: str = DEFAULT
    # Sa as a pitch class (0 = C ... 11 = B); leave out to have it worked out.
    sa: int | None = Field(None, ge=0, le=11)
    separate: bool = True


def _new_job(**fields) -> str:
    job_id = uuid.uuid4().hex[:12]
    with _lock:
        cutoff = time.time() - JOB_TTL
        for old in [k for k, j in _jobs.items() if j["created"] < cutoff]:
            del _jobs[old]
        _jobs[job_id] = {
            "id": job_id,
            "status": "queued",
            "stage": "Waiting in line",
            "progress": 0.0,
            "created": time.time(),
            **fields,
        }
    return job_id


def _update(job_id: str, **fields) -> None:
    with _lock:
        if job_id in _jobs:
            _jobs[job_id].update(fields)


def _run(job_id: str, workdir: Path, source, options: Options) -> None:
    def progress(stage: str, frac: float) -> None:
        # Downloading takes the first 15%, analysis the rest.
        _update(job_id, stage=stage, progress=round(0.15 + 0.85 * frac, 3))

    try:
        _update(job_id, status="running")
        time_offset = 0.0
        title = ""
        if isinstance(source, str):
            _update(job_id, stage="Downloading audio from YouTube", progress=0.02)
            video = download_audio(source, workdir, options.start, options.end)
            path, title = video.path, video.title
            # Only the clip was downloaded, so it starts at options.start.
            time_offset = options.start
            options = replace(options, start=0.0, end=None)
            _update(job_id, title=title)
        else:
            path = source
        result = analyze(
            str(path), options, title=title, time_offset=time_offset, progress=progress
        )
        midi = result.pop("_midi")
        _update(job_id, status="done", stage="Done", progress=1.0, result=result, midi=midi)
    except (FetchError, ValueError) as e:
        _update(job_id, status="error", error=str(e))
    except Exception as e:  # keep the server alive; report what broke
        _update(job_id, status="error", error=f"Analysis failed: {e}")
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def _check_taal(taal: str) -> None:
    if taal not in TAALS:
        raise HTTPException(400, f"Unknown taal. Choose from: {', '.join(TAALS)}.")


def _get(job_id: str) -> dict:
    with _lock:
        job = _jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "No such job (it may have expired).")
        return dict(job)


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


@app.post("/api/jobs")
def create_job(req: JobRequest):
    try:
        vid = video_id(req.url)
    except FetchError as e:
        raise HTTPException(400, str(e))
    if req.start is not None and req.end is not None and req.end <= req.start:
        raise HTTPException(400, "The end time must be after the start time.")
    _check_taal(req.taal)
    options = Options(
        start=req.start or 0.0,
        end=req.end,
        taal=req.taal,
        sa=req.sa,
        separate=req.separate,
    )
    job_id = _new_job(video_id=vid, title="")
    workdir = Path(tempfile.mkdtemp(prefix="sitar-"))
    _executor.submit(_run, job_id, workdir, req.url, options)
    return {"id": job_id}


@app.post("/api/upload")
async def upload(
    file: UploadFile = File(...),
    start: float | None = Form(None),
    end: float | None = Form(None),
    taal: str = Form(DEFAULT),
    sa: int | None = Form(None),
    separate: bool = Form(True),
):
    workdir = Path(tempfile.mkdtemp(prefix="sitar-"))
    suffix = Path(file.filename or "audio").suffix[:8]
    path = workdir / f"upload{suffix}"
    size = 0
    with path.open("wb") as out:
        while chunk := await file.read(1 << 20):
            size += len(chunk)
            if size > MAX_UPLOAD:
                shutil.rmtree(workdir, ignore_errors=True)
                raise HTTPException(413, "That file is too big (60 MB max).")
            out.write(chunk)
    try:
        req = JobRequest(
            url="-", start=start, end=end, taal=taal, sa=sa, separate=separate,
        )
        _check_taal(req.taal)
    except ValueError as e:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(422, str(e))
    except HTTPException:
        shutil.rmtree(workdir, ignore_errors=True)
        raise
    options = Options(
        start=req.start or 0.0, end=req.end, taal=req.taal, sa=req.sa,
        separate=req.separate,
    )
    job_id = _new_job(video_id=None, title=file.filename or "Uploaded audio")
    _executor.submit(_run, job_id, workdir, path, options)
    return {"id": job_id}


@app.get("/api/jobs/{job_id}")
def job_status(job_id: str):
    job = _get(job_id)
    job.pop("midi", None)
    return job


@app.get("/api/taals")
def taals():
    return [{"key": t.key, "label": t.label} for t in TAALS.values()]


@app.get("/api/jobs/{job_id}/sargam.txt", response_class=PlainTextResponse)
def job_text(job_id: str, script: str = Query("latin")):
    job = _get(job_id)
    if job["status"] != "done":
        raise HTTPException(409, "Not finished yet.")
    if script not in SCRIPTS:
        raise HTTPException(400, f"Unknown script. Choose from: {', '.join(SCRIPTS)}.")
    return PlainTextResponse(
        job["result"]["text" if script == "latin" else "text_devanagari"],
        headers={"Content-Disposition": 'attachment; filename="sitar-sargam.txt"'},
    )


@app.get("/api/jobs/{job_id}/sargam.mid")
def job_midi(job_id: str):
    job = _get(job_id)
    if job["status"] != "done":
        raise HTTPException(409, "Not finished yet.")
    return Response(
        job["midi"],
        media_type="audio/midi",
        headers={"Content-Disposition": 'attachment; filename="sitar-sargam.mid"'},
    )


def main() -> None:
    import argparse

    import uvicorn

    parser = argparse.ArgumentParser(description="Run the Sitar Sitara web app.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
