"""MusicMe GPU worker: serves musicme.gpu-job.v1 (see audioservice/gpu_protocol.py) on a GPU host. Standard library only.

It drives the committed, GPU-validated runner (audioservice/acestep/runner.py) through AceStepEngine, unchanged: the
same allow-listed environment, pinned-manifest handshake, float16 lyric-encoder fix and self-test, offline mode, no
reference audio. The same container runs on Modal, RunPod, Vast.ai or any GPU VM.

* One render at a time and a bounded queue (429 + Retry-After when full); a hard per-job deadline.
* Bearer-token auth on every route (constant-time compare); the token comes from a provider secret, never an image.
* Outputs live in a private directory (0700/0600) and are deleted when the client confirms receipt, when they expire
  (output TTL), and on shutdown. Job records expire too. Logs carry codes and short job ids only, never lyrics.
* SIGTERM (scale-down, pod stop) drains: new jobs are refused, the running render is cancelled (its runner process
  group killed), outputs are wiped and the runner shuts down. Optional idle exit for hosts that bill until stopped.

Run: python -I -m audioservice.gpu_worker.server     (configuration from MUSICME_WORKER_* environment variables)
"""
import hashlib
import hmac
import json
import logging
import os
import re
import shutil
import signal
import sys
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .. import gpu_protocol
from ..acestep import protocol as acestep_protocol
from ..engines import AceStepEngine, Cancelled, EngineError, EngineUnavailable, RunnerError
from ..validation import AudioValidationError, validate_wav

log = logging.getLogger("musicme.gpu_worker")
JOB_PATH = re.compile(r"^/v1/jobs/([0-9a-f]{32})(/audio|/cancel)?$")


@dataclass(frozen=True)
class WorkerSettings:
    token: str = field(repr=False)
    host: str = "127.0.0.1"
    port: int = 8080
    data_dir: str = ""
    acestep_python: str = sys.executable
    acestep_home: str = "/opt/acestep"
    acestep_runner: str = ""
    acestep_device: str = "cuda"
    acestep_startup_timeout_seconds: int = 900
    acestep_retry_nan: bool = False  # the audio service decides about retries; the worker never retries
    max_queue: int = 1
    max_job_seconds: int = 600
    output_ttl_seconds: int = 600
    record_ttl_seconds: int = 3600
    max_output_bytes: int = 64 * 1024 * 1024
    duration_tolerance: float = 0.25
    idle_exit_seconds: int = 0  # 0 = never (Modal scales to zero itself); e.g. 900 on a RunPod/Vast pod

    def __post_init__(self):
        if not isinstance(self.token, str) or len(self.token) < 32:
            raise ValueError("MUSICME_WORKER_TOKEN must be at least 32 characters.")
        if (not 0 <= self.max_queue <= 8 or not 30 <= self.max_job_seconds <= 1800
                or self.output_ttl_seconds < 60 or self.record_ttl_seconds < self.output_ttl_seconds
                or not 1 << 20 <= self.max_output_bytes <= 256 << 20 or not 0 <= self.duration_tolerance <= 0.5):
            raise ValueError("Worker limits are out of range.")


def load_settings(env=os.environ):
    token = env.get("MUSICME_WORKER_TOKEN", "")
    path = env.get("MUSICME_WORKER_TOKEN_FILE")
    if path:
        if not os.path.isfile(path) or os.path.islink(path):
            raise ValueError("MUSICME_WORKER_TOKEN_FILE must be a regular private file.")
        if os.stat(path).st_mode & 0o077:
            raise ValueError("MUSICME_WORKER_TOKEN_FILE must be private (chmod 600).")
        with open(path) as f:
            token = f.read().strip()
    return WorkerSettings(
        token=token, host=env.get("MUSICME_WORKER_HOST", "127.0.0.1"), port=int(env.get("MUSICME_WORKER_PORT", "8080")),
        data_dir=env.get("MUSICME_WORKER_DATA_DIR", ""), acestep_home=env.get("MUSICME_WORKER_ACESTEP_HOME", "/opt/acestep"),
        acestep_python=env.get("MUSICME_WORKER_ACESTEP_PYTHON", sys.executable),
        acestep_runner=env.get("MUSICME_WORKER_ACESTEP_RUNNER", ""),
        acestep_startup_timeout_seconds=int(env.get("MUSICME_WORKER_STARTUP_TIMEOUT_SECONDS", "900")),
        max_queue=int(env.get("MUSICME_WORKER_MAX_QUEUE", "1")), max_job_seconds=int(env.get("MUSICME_WORKER_MAX_JOB_SECONDS", "600")),
        output_ttl_seconds=int(env.get("MUSICME_WORKER_OUTPUT_TTL_SECONDS", "600")),
        record_ttl_seconds=int(env.get("MUSICME_WORKER_RECORD_TTL_SECONDS", "3600")),
        max_output_bytes=int(env.get("MUSICME_WORKER_MAX_OUTPUT_BYTES", str(64 * 1024 * 1024))),
        duration_tolerance=float(env.get("MUSICME_WORKER_DURATION_TOLERANCE", "0.25")),
        idle_exit_seconds=int(env.get("MUSICME_WORKER_IDLE_EXIT_SECONDS", "0")))


class _Job:
    def __init__(self, job_id, params, deadline_seconds):
        self.id, self.params, self.deadline_seconds = job_id, params, deadline_seconds
        self.submission_digest = hashlib.sha256(json.dumps(
            {"params": params, "deadline_seconds": deadline_seconds}, sort_keys=True,
            separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()
        self.state, self.progress, self.result, self.error = "queued", 0.0, None, None
        self.cancel_requested = False
        self.out_path = None
        self.finished_at = None

    def document(self):
        return {"job_id": self.id, "state": self.state, "progress": round(self.progress, 3),
                "result": self.result if self.state == "succeeded" else None,
                "error": {"code": self.error} if self.state == "failed" else None}


class GpuWorker:
    def __init__(self, settings, engine_factory=None, clock=time.monotonic):
        self.s = settings
        self.clock = clock
        self._engine_factory = engine_factory or (lambda: AceStepEngine(settings))
        self.engine = None
        self.state, self.failure = "starting", None
        self.jobs, self.queue = {}, deque()
        self.lock = threading.Condition()
        self.last_activity = clock()
        root = settings.data_dir or tempfile.gettempdir()
        os.makedirs(root, mode=0o700, exist_ok=True)
        self.outputs = tempfile.mkdtemp(prefix="musicme-outputs-", dir=root)
        os.chmod(self.outputs, 0o700)
        self.stopped = threading.Event()
        self.http = None

    # -- lifecycle ------------------------------------------------------------------------------

    def start(self):
        """Begins serving at once (so providers see the port) and loads the engine in the background."""
        threading.Thread(target=self._load_engine, name="engine-load", daemon=True).start()
        threading.Thread(target=self._run_jobs, name="render", daemon=True).start()
        threading.Thread(target=self._maintain, name="cleanup", daemon=True).start()

    def _load_engine(self):
        try:
            engine = self._engine_factory()
        except EngineUnavailable as error:
            log.error("worker_engine_failed")
            with self.lock:
                self.state, self.failure = "failed", _code_from(str(error))
                for job in self.queue:
                    self._finish(job, "failed", self.failure)
                self.queue.clear()
                self.lock.notify_all()
            return
        except Exception:
            log.error("worker_engine_failed")
            with self.lock:
                self.state, self.failure = "failed", "ENGINE_UNAVAILABLE"
                for job in self.queue:
                    self._finish(job, "failed", self.failure)
                self.queue.clear()
                self.lock.notify_all()
            return
        with self.lock:
            if self.state == "draining":
                engine.close()
                return
            self.engine, self.state = engine, "ready"
            self.lock.notify_all()
        log.info("worker_ready dtype=%s fp16_fix=%s", engine.hello.get("dtype"), engine.hello.get("fp16_fix"))

    def shutdown(self):
        """Drain: refuse new work, cancel the running render, wipe outputs, stop the runner and the HTTP server."""
        with self.lock:
            if self.state == "draining":
                return
            self.state = "draining"
            for job in self.jobs.values():
                if job.state in ("queued", "running"):
                    job.cancel_requested = True
                    if job.state == "queued":
                        self._finish(job, "cancelled")
            self.queue.clear()
            self.lock.notify_all()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and any(j.state == "running" for j in list(self.jobs.values())):
            time.sleep(0.05)
        if self.engine is not None:
            self.engine.close()
        shutil.rmtree(self.outputs, ignore_errors=True)
        self.stopped.set()
        if self.http is not None:
            threading.Thread(target=self.http.shutdown, daemon=True).start()
        log.info("worker_stopped")

    # -- jobs -----------------------------------------------------------------------------------

    def submit(self, job_id, params, deadline_seconds):
        with self.lock:
            self.last_activity = self.clock()
            existing = self.jobs.get(job_id)
            if existing is not None:
                candidate = hashlib.sha256(json.dumps(
                    {"params": params, "deadline_seconds": min(deadline_seconds, self.s.max_job_seconds)},
                    sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()
                if not hmac.compare_digest(existing.submission_digest, candidate):
                    return 409, {"detail": "job id already used"}
                return 202, existing.document()  # idempotent retry of the exact same submission
            if self.state == "draining":
                return 503, {"state": "draining"}
            if self.state == "failed":
                return 503, {"state": "failed", "code": self.failure}
            active = sum(1 for j in self.jobs.values() if j.state in ("queued", "running"))
            if active >= 1 + self.s.max_queue:
                return 429, {"detail": "busy"}
            job = _Job(job_id, params, min(deadline_seconds, self.s.max_job_seconds))
            self.jobs[job_id] = job
            self.queue.append(job)
            self.lock.notify_all()
            log.info("job_queued job=%s", job_id[:8])
            return 202, job.document()

    def cancel(self, job_id):
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                return None
            self.last_activity = self.clock()
            if job.state == "queued":
                self.queue.remove(job)
                self._finish(job, "cancelled")
            elif job.state == "running":
                job.cancel_requested = True
            return job.document()

    def delete(self, job_id):
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                return False
            if job.state in ("queued", "running"):
                job.cancel_requested = True
                if job.state == "queued":
                    self.queue.remove(job)
                    self._finish(job, "cancelled")
            self._remove_output(job)
            if job.state in gpu_protocol.FINAL_STATES:
                self.jobs.pop(job_id, None)
            return True

    def _finish(self, job, state, error=None):
        job.state, job.error, job.finished_at = state, error, self.clock()
        if state != "succeeded":
            self._remove_output(job)

    def _remove_output(self, job):
        if job.out_path:
            try:
                os.remove(job.out_path)
            except FileNotFoundError:
                pass
            job.out_path = None

    def _run_jobs(self):
        while not self.stopped.is_set():
            with self.lock:
                while not self.stopped.is_set() and (not self.queue or self.state != "ready"):
                    self.lock.wait(0.5)
                if self.stopped.is_set():
                    return
                job = self.queue.popleft()
                job.state = "running"
                job.out_path = os.path.join(self.outputs, f".tmp-{job.id}")
            self._render(job)

    def _render(self, job):
        started = time.monotonic()
        deadline = started + job.deadline_seconds

        def progress(fraction):
            with self.lock:
                job.progress = max(job.progress, min(1.0, float(fraction)))
                self.last_activity = self.clock()
                return not job.cancel_requested

        state, error, result = "failed", None, None
        try:
            message = self.engine.render_params(job.params, job.out_path, progress, deadline)
            info = validate_wav(job.out_path, job.params["duration"], self.s.duration_tolerance,
                                self.s.max_output_bytes)
            size = os.path.getsize(job.out_path)
            digest = hashlib.sha256()
            with open(job.out_path, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    digest.update(chunk)
            details = message.engine_parameters
            frames = _frames(job.out_path)
            if (message.sample_rate != info["sample_rate"] or message.channels != info["channels"]
                    or frames is None or frames <= 0):
                raise EngineError("OUTPUT_FAILED", "runner metadata did not match its WAV")
            result = {"sample_rate": message.sample_rate, "channels": message.channels, "render_ms": message.render_ms,
                      "clipped_before_pcm": details["clipped_before_pcm"], "dtype": details["dtype"],
                      "peak_vram_gb": details.get("peak_vram_gb"), "diagnostics": details.get("diagnostics") or {},
                      "frames": frames, "sha256": digest.hexdigest(), "size_bytes": size}
            result = {k: v for k, v in result.items() if v is not None}
            state = "succeeded"
        except Cancelled:
            state = "cancelled"
        except RunnerError as failure:
            error = failure.code
        except EngineError as failure:
            error = failure.code
        except AudioValidationError as failure:
            error = failure.code
        except Exception:  # never leak details; the audio service maps the code
            error = "GENERATION_FAILED"
        with self.lock:
            job.result = result
            if state == "succeeded":
                job.progress = 1.0
            self._finish(job, state, error)
            if self.state == "draining" and state == "succeeded":
                self._remove_output(job)
        log.info("job_finished job=%s state=%s code=%s ms=%d", job.id[:8], state, error or "-",
                 int((time.monotonic() - started) * 1000))

    def _maintain(self):
        while not self.stopped.wait(5):
            if self.maintain_once():
                log.info("worker_idle_exit")
                self.shutdown()
                return

    def maintain_once(self):
        """Expires outputs and job records; returns True when the worker has been idle long enough to exit."""
        now = self.clock()
        with self.lock:
            for job_id, job in list(self.jobs.items()):
                if job.finished_at is None:
                    continue
                if now - job.finished_at > self.s.output_ttl_seconds:
                    self._remove_output(job)
                if now - job.finished_at > self.s.record_ttl_seconds:
                    self.jobs.pop(job_id, None)
            busy = any(j.state in ("queued", "running") for j in self.jobs.values())
            return bool(self.s.idle_exit_seconds and not busy and now - self.last_activity > self.s.idle_exit_seconds)

    # -- HTTP -----------------------------------------------------------------------------------

    def handler(self):
        worker = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "musicme-gpu-worker"
            sys_version = ""

            def log_message(self, *args):  # codes only (see job logs); never paths with data or bodies
                pass

            def _send(self, status, payload=None, headers=None):
                data = json.dumps(payload).encode() if payload is not None else b""
                self.send_response(status)
                if payload is not None:
                    self.send_header("Content-Type", "application/json")
                self.send_header("Cache-Control", "no-store")
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _authorized(self):
                supplied = self.headers.get("Authorization", "")
                expected = "Bearer " + worker.s.token
                if hmac.compare_digest(supplied.encode(), expected.encode()):
                    return True
                self._send(401, {"detail": "unauthorized"})
                return False

            def _body(self):
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                except (TypeError, ValueError):
                    return None
                if not 0 < length <= gpu_protocol.MAX_BODY_BYTES:
                    return None
                try:
                    return json.loads(self.rfile.read(length))
                except ValueError:
                    return None

            def do_GET(self):
                if not self._authorized():
                    return
                if self.path == "/v1/health":
                    with worker.lock:
                        busy = any(j.state == "running" for j in worker.jobs.values())
                        return self._send(200, {"protocol": gpu_protocol.PROTOCOL, "state": worker.state, "busy": busy})
                if self.path == "/v1/hello":
                    if worker.state == "ready":
                        return self._send(200, {k: v for k, v in worker.engine.hello.items() if k != "type"})
                    if worker.state == "failed":
                        return self._send(503, {"state": "failed", "code": worker.failure})
                    return self._send(503, {"state": worker.state}, {"Retry-After": "5"})
                match = JOB_PATH.match(self.path)
                if not match or match.group(2) == "/cancel":
                    return self._send(404, {"detail": "not found"})
                with worker.lock:
                    job = worker.jobs.get(match.group(1))
                    document = job.document() if job else None
                    path = job.out_path if job and job.state == "succeeded" else None
                if document is None:
                    return self._send(404, {"detail": "not found"})
                if not match.group(2):
                    return self._send(200, document)
                if not path or not os.path.isfile(path):
                    return self._send(404, {"detail": "no audio"})
                with open(path, "rb") as f:
                    size = os.fstat(f.fileno()).st_size
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/wav")
                    self.send_header("Content-Length", str(size))
                    self.send_header("X-Content-SHA256", document["result"]["sha256"])
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    shutil.copyfileobj(f, self.wfile, 1 << 20)

            def do_POST(self):
                if not self._authorized():
                    return
                if self.path == "/v1/jobs":
                    body = self._body()
                    job_id = body.get("job_id") if isinstance(body, dict) else None
                    deadline = body.get("deadline_seconds") if isinstance(body, dict) else None
                    if (not isinstance(job_id, str) or not gpu_protocol.JOB_ID.match(job_id) or isinstance(deadline, bool)
                            or not isinstance(deadline, int) or not 10 <= deadline <= 3600 or set(body) - {"job_id", "params", "deadline_seconds"}):
                        return self._send(400, {"detail": "invalid job"})
                    try:  # the same strict schema the runner enforces; out_path is assigned by the worker
                        acestep_protocol.validate({"type": "render", "id": job_id, "params": body.get("params"),
                                                   "out_path": f"/x/.tmp-{job_id}"}, "to_runner")
                    except acestep_protocol.ProtocolError:
                        return self._send(400, {"detail": "invalid params"})
                    status, document = worker.submit(job_id, body["params"], deadline)
                    return self._send(status, document, {"Retry-After": "10"} if status in (429, 503) else None)
                match = JOB_PATH.match(self.path)
                if match and match.group(2) == "/cancel":
                    document = worker.cancel(match.group(1))
                    return self._send(200, document) if document else self._send(404, {"detail": "not found"})
                self._send(404, {"detail": "not found"})

            def do_DELETE(self):
                if not self._authorized():
                    return
                match = JOB_PATH.match(self.path)
                if not match or match.group(2):
                    return self._send(404, {"detail": "not found"})
                return self._send(204) if worker.delete(match.group(1)) else self._send(404, {"detail": "not found"})

        return Handler

    def serve(self):
        self.http = ThreadingHTTPServer((self.s.host, self.s.port), self.handler())
        self.http.daemon_threads = True
        self.start()
        return self.http


def _frames(path):
    import wave
    try:
        with wave.open(path, "rb") as w:
            return w.getnframes()
    except (wave.Error, EOFError, OSError):
        return None


def _code_from(text):
    match = re.search(r"\(([A-Z_]+)\)", text)
    return match.group(1) if match else "ENGINE_UNAVAILABLE"


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    worker = GpuWorker(load_settings())
    server = worker.serve()
    # Non-daemon: container exit must wait for runner termination and output cleanup.
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=worker.shutdown, name="shutdown").start())
    log.info("worker_listening port=%d protocol=%s", worker.s.port, gpu_protocol.PROTOCOL)
    try:
        server.serve_forever()
    finally:
        worker.shutdown()


if __name__ == "__main__":
    main()
