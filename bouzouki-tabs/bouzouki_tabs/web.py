"""A small local web app: paste a YouTube link, get a tab next to the video."""

from __future__ import annotations

import json
import threading
import traceback
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources

from .audio import parse_time
from .pipeline import transcribe
from .youtube import is_url

_jobs: dict[str, dict] = {}
_lock = threading.Lock()


def _run_job(job_id: str, params: dict) -> None:
    def progress(message: str) -> None:
        with _lock:
            _jobs[job_id]["message"] = message

    try:
        result = transcribe(
            params["url"],
            start=parse_time(params.get("start")),
            end=parse_time(params.get("end")),
            tuning=params.get("tuning") or "tetrachordo",
            meter=params.get("meter") or "4/4",
            bpm=float(params["bpm"]) if params.get("bpm") else None,
            separate=bool(params.get("separate")),
            width=int(params.get("width") or 100),
            progress=progress,
        )
        update = {"status": "done", "result": result.to_dict()}
    except Exception as exc:  # report anything to the browser rather than dying silently
        traceback.print_exc()
        update = {"status": "error", "message": str(exc)}
    with _lock:
        _jobs[job_id].update(update)


class Handler(BaseHTTPRequestHandler):
    server_version = "BouzoukiTabs/0.1"

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: dict) -> None:
        self._send(status, json.dumps(payload).encode(), "application/json")

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/index.html"):
            page = resources.files("bouzouki_tabs").joinpath("static/index.html").read_bytes()
            self._send(HTTPStatus.OK, page, "text/html; charset=utf-8")
        elif self.path.startswith("/api/jobs/"):
            with _lock:
                job = dict(_jobs.get(self.path.rsplit("/", 1)[-1], {}))
            if job:
                self._json(HTTPStatus.OK, job)
            else:
                self._json(HTTPStatus.NOT_FOUND, {"status": "error", "message": "unknown job"})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"message": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/api/transcribe":
            self._json(HTTPStatus.NOT_FOUND, {"message": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            params = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._json(HTTPStatus.BAD_REQUEST, {"message": "send JSON"})
            return
        # Only links: the browser must not be able to point the server at local files.
        if not is_url(str(params.get("url", "")).strip()):
            self._json(HTTPStatus.BAD_REQUEST, {"message": "paste a YouTube link first"})
            return
        params["url"] = params["url"].strip()
        job_id = uuid.uuid4().hex
        with _lock:
            _jobs[job_id] = {"status": "running", "message": "Starting..."}
        threading.Thread(target=_run_job, args=(job_id, params), daemon=True).start()
        self._json(HTTPStatus.ACCEPTED, {"job": job_id})

    def log_message(self, format: str, *args) -> None:  # keep the console quiet while polling
        if "/api/jobs/" not in (args[0] if args else ""):
            super().log_message(format, *args)


def serve(host: str = "127.0.0.1", port: int = 8000) -> None:
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"Bouzouki Tabs is running at http://{host}:{port}  (Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
