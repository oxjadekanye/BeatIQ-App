"""Audio engine interface (musicme.audio-engine.v1), a development-only mock and the real ACE-Step 1.5 engine.

MockToneEngine writes simple sine tones so jobs, storage and validation can be tested end to end; its output is not
music and is labelled "mock" everywhere it appears. AceStepEngine (Phase 2.3F) drives ACE-Step 1.5 through a separate
runner process (audioservice/acestep/runner.py) running under ACE-Step's own Python 3.12 environment; this process
never imports torch. Nothing an engine returns is published until the worker has validated the WAV.
"""
import array
import logging
import math
import os
import select
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid
import wave
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

from . import ENGINE_INTERFACE_VERSION
from .acestep import manifest as acestep_manifest
from .acestep import protocol as acestep_protocol
from . import gpu_protocol

log = logging.getLogger("musicme.audio")

_SECTION_TAGS = {
    "intro": "Intro", "verse": "Verse", "pre_chorus": "Pre-Chorus", "chorus": "Chorus", "hook": "Hook",
    "refrain": "Refrain", "bridge": "Bridge", "interlude": "Interlude", "instrumental": "Instrumental",
    "breakdown": "Breakdown", "outro": "Outro",
}


class EngineError(Exception):
    def __init__(self, code, message, transient=False):
        super().__init__(code)
        self.code = code
        self.message = message
        self.transient = transient


class EngineUnavailable(Exception):
    pass


class Cancelled(Exception):
    pass


@dataclass(frozen=True)
class RenderSection:
    tag: str        # canonical tag derived from the section type, never the user's label
    kind: str
    lyrics: str     # the user's (or songwriting service's) lyrics, unchanged


@dataclass(frozen=True)
class RenderRequest:
    """Everything an engine may use. Built only from validated, structured fields."""
    sections: Tuple[RenderSection, ...]
    caption: str
    language: str
    vocal_type: str
    vocal_descriptors: Tuple[str, ...]
    genres: Tuple[str, ...]
    moods: Tuple[str, ...]
    instrumentation: Tuple[str, ...]
    bpm: Optional[int]
    duration_seconds: int
    seed: int
    allow_explicit: bool
    prompt: str = ""
    job_id: str = ""
    account: str = ""
    worker_id: str = ""
    provider_request_id: Optional[str] = None
    provider_endpoint: Optional[str] = None

    def tagged_lyrics(self):
        return "\n\n".join(f"[{s.tag}]" + (f"\n{s.lyrics}" if s.lyrics else "") for s in self.sections)

    def parameters(self):
        """Reproducibility metadata: everything except the lyrics."""
        return {
            "caption": self.caption, "language": self.language, "vocal_type": self.vocal_type,
            "vocal_descriptors": list(self.vocal_descriptors), "genres": list(self.genres), "moods": list(self.moods),
            "instrumentation": list(self.instrumentation), "bpm": self.bpm, "duration_seconds": self.duration_seconds,
            "seed": self.seed, "section_tags": [s.tag for s in self.sections],
        }


@dataclass
class RenderResult:
    format: str
    sample_rate: int
    channels: int
    render_ms: int
    engine_parameters: Dict = field(default_factory=dict)


def build_render_request(spec, seed, job_id="", account="", worker_id="", provider_request_id=None,
                         provider_endpoint=None):
    counts, sections = {}, []
    for section in spec["sections"]:
        base = _SECTION_TAGS[section["type"]]
        counts[base] = counts.get(base, 0) + 1
        repeats = sum(1 for s in spec["sections"] if _SECTION_TAGS[s["type"]] == base)
        tag = f"{base} {counts[base]}" if base == "Verse" and repeats > 1 else base
        sections.append(RenderSection(tag=tag, kind=section["type"], lyrics=section["lyrics"]))
    style, vocals = spec["style"], spec["vocals"]
    parts = list(style["genres"]) + list(style["moods"]) + list(style["instrumentation"])
    if vocals["type"] == "none":
        parts.append("instrumental, no vocals")
    else:
        parts.append(" ".join(vocals["descriptors"] + [{"duet": "duet", "choir": "choir"}.get(vocals["type"], vocals["type"] + " vocal")]))
    if style["bpm"]:
        parts.append(f"{style['bpm']} bpm")
    return RenderRequest(
        sections=tuple(sections), caption=", ".join(p for p in parts if p), language=spec["language"],
        vocal_type=vocals["type"], vocal_descriptors=tuple(vocals["descriptors"]), genres=tuple(style["genres"]),
        moods=tuple(style["moods"]), instrumentation=tuple(style["instrumentation"]), bpm=style["bpm"],
        duration_seconds=spec["target_duration_seconds"], seed=seed, allow_explicit=spec["allow_explicit"],
        prompt=spec.get("prompt", ""), job_id=job_id, account=account, worker_id=worker_id,
        provider_request_id=provider_request_id, provider_endpoint=provider_endpoint,
    )


class AudioEngine:
    """Interface every engine implements. render() writes a WAV file to out_path.

    progress(fraction) must be called regularly; it returns False when the job was cancelled or its lease was lost,
    and the engine must then stop and raise Cancelled. Engines raise EngineError for failures (transient ones may
    be retried with a new seed).
    """
    kind = "abstract"
    model_id = ""
    model_version = ""
    model_revision = ""
    interface_version = ENGINE_INTERFACE_VERSION
    is_mock = False
    output_format = "wav"

    def render(self, request: RenderRequest, out_path: str, progress: Callable[[float], bool], deadline: float) -> RenderResult:
        raise NotImplementedError

    def check_spec(self, spec) -> Optional[str]:
        """A user-facing reason this engine can't take the request (checked at submit), or None."""
        return None

    def limits(self) -> Dict:
        return {}

    def close(self):
        pass


class MockToneEngine(AudioEngine):
    """DEVELOPMENT AND TESTS ONLY. Writes a sequence of sine tones (one pitch per section). Not music, no vocals."""
    kind = "mock"
    model_id = "mock-tone-generator"
    model_version = "mock-1"
    model_revision = "none"
    is_mock = True
    SAMPLE_RATE = 44100

    def __init__(self, fault: Optional[str] = None, delay_seconds: float = 0.0, fault_attempts: Optional[List[int]] = None):
        # fault: None | "silent" | "clipped" | "short" | "garbage" | "transient" | "fatal" | "crash" | "hang"
        self.fault = fault
        self.delay_seconds = delay_seconds
        self.fault_attempts = fault_attempts  # which render calls fault (1-based); None = all
        self.calls = 0

    def _faulty(self):
        return self.fault and (self.fault_attempts is None or self.calls in self.fault_attempts)

    def render(self, request, out_path, progress, deadline):
        self.calls += 1
        started = time.monotonic()
        fault = self.fault if self._faulty() else None
        if fault == "transient":
            raise EngineError("ENGINE_UNAVAILABLE", "The audio engine is temporarily unavailable.", transient=True)
        if fault == "fatal":
            raise EngineError("ENGINE_REJECTED", "The audio engine couldn't render this request.")
        if fault == "crash":
            raise RuntimeError("simulated engine crash")
        if fault == "hang":
            while time.monotonic() < deadline:
                if not progress(0.1):
                    raise Cancelled()
                time.sleep(0.01)
            raise EngineError("TIMEOUT", "The audio engine took too long.", transient=True)
        if fault == "garbage":
            with open(out_path, "wb") as f:
                f.write(b"this is not audio" * 64)
            return RenderResult("wav", self.SAMPLE_RATE, 1, 0)
        seconds = request.duration_seconds * (0.3 if fault == "short" else 1.0)
        total = int(seconds * self.SAMPLE_RATE)
        amplitude = 0 if fault == "silent" else (40000 if fault == "clipped" else 8000)
        per_section = max(1, total // max(1, len(request.sections)))
        with wave.open(out_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.SAMPLE_RATE)
            written = 0
            for index, _ in enumerate(request.sections):
                if self.delay_seconds:
                    time.sleep(self.delay_seconds / len(request.sections))
                if not progress((index + 1) / len(request.sections)):
                    raise Cancelled()
                count = per_section if index < len(request.sections) - 1 else total - written
                frequency = 220.0 * (2 ** (((request.seed + index * 3) % 12) / 12))
                step = 2 * math.pi * frequency / self.SAMPLE_RATE
                frames = array.array("h", (max(-32768, min(32767, int(amplitude * math.sin(step * n)))) for n in range(count)))
                if sys.byteorder == "big":
                    frames.byteswap()
                w.writeframes(frames.tobytes())
                written += count
        return RenderResult("wav", self.SAMPLE_RATE, 1, int((time.monotonic() - started) * 1000),
                            {"generator": "sine tones (mock)"})


# Environment variables the runner may inherit. Everything else (JWT and signing secrets, database URLs, tokens,
# cloud credentials, proxies) is dropped; offline and headless settings are forced.
RUNNER_ENV_ALLOWLIST = ("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "HOME", "CUDA_VISIBLE_DEVICES",
                        "NVIDIA_VISIBLE_DEVICES", "NVIDIA_DRIVER_CAPABILITIES", "LD_LIBRARY_PATH", "CUDA_HOME")
RUNNER_ENV_FORCED = {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1",
                     "GRADIO_ANALYTICS_ENABLED": "False", "ACESTEP_SAVE_MEMORY": "1", "MPLBACKEND": "Agg",
                     "PYTHONUNBUFFERED": "1", "PYTHONDONTWRITEBYTECODE": "1", "TOKENIZERS_PARALLELISM": "false"}
DEFAULT_RUNNER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "acestep", "runner.py")
LANGUAGE_CODES = {"english": "en"}
HEARTBEAT_SECONDS = 2.0


class RunnerError(Exception):
    """A failure the runner itself reported (one of acestep.protocol.ERROR_CODES)."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class _RunnerFailure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class AceStepEngine(AudioEngine):
    """ACE-Step 1.5 through a separate, verified runner process (JSON lines over stdin/stdout).

    * The runner gets an allow-listed environment, no arguments and its stderr is discarded: no secrets reach it and
      neither lyrics nor model output reach service logs.
    * Every runner start ends in a handshake the service checks independently against the pinned manifest (commit,
      weights revision, every file hash, config, dtype, and the float16 fix self-test).
    * Renders heartbeat at least every HEARTBEAT_SECONDS; cancellation, a lost lease, the deadline, a crash or any
      protocol violation terminate the runner's whole process group. The next render restarts it (with a handshake).
    * Restrictions kept from Phase 2.3A: text2music only; no reference audio, timbre cloning, cover, repaint or LoRA.
    """
    kind = "ace_step"
    model_id = "ace-step-1.5"
    is_mock = False
    output_format = "wav"
    DISABLED_FEATURES = ("reference_audio", "timbre_clone", "cover", "repaint_from_upload", "lora")

    def __init__(self, settings, start=True):
        self.s = settings
        self.manifest = acestep_manifest.load()
        self.pins = self.manifest["limits"]
        self.model_version = self.manifest["weights"]["config_path"]
        self.model_revision = (f"code {self.manifest['code']['commit'][:12]} / "
                               f"weights {self.manifest['weights']['revision'][:12]}")
        self.python = settings.acestep_python
        self.home = os.path.realpath(settings.acestep_home) if settings.acestep_home else ""
        self.runner = settings.acestep_runner or DEFAULT_RUNNER
        if not (self.python and os.path.isfile(self.python) and os.access(self.python, os.X_OK)):
            raise EngineUnavailable("ACE-Step runtime is not configured (MUSICME_AUDIO_ACESTEP_PYTHON).")
        if not (self.home and os.path.isdir(self.home)):
            raise EngineUnavailable("ACE-Step checkout is not configured (MUSICME_AUDIO_ACESTEP_HOME).")
        if not os.path.isfile(self.runner):
            raise EngineUnavailable("ACE-Step runner script is missing.")
        self._proc = None
        self._buffer = b""
        self._lock = threading.Lock()
        self.hello = None
        if start:
            try:
                self._start()
            except _RunnerFailure as failure:
                raise EngineUnavailable(f"ACE-Step runner refused to start ({failure.code}).")

    # -- limits and mapping -------------------------------------------------------------------

    def limits(self):
        return {"max_lyric_chars": self.pins["max_lyric_chars"], "max_caption_chars": self.pins["max_caption_chars"],
                "min_duration_seconds": self.pins["min_duration_seconds"],
                "max_duration_seconds": self.pins["max_duration_seconds"], "max_takes": self.pins["max_takes"]}

    def check_spec(self, spec):
        request = build_render_request(spec, 0)
        params = self.pipeline_parameters(request)
        if len(params["lyrics"]) > self.pins["max_lyric_chars"]:
            return f"The lyrics are too long for this song engine (at most {self.pins['max_lyric_chars']} characters including section tags)."
        if len(params["caption"]) > self.pins["max_caption_chars"]:
            return "Too many style details for this song engine. Choose fewer genres, moods or instruments."
        if not self.pins["min_duration_seconds"] <= spec["target_duration_seconds"] <= self.pins["max_duration_seconds"]:
            return "The requested song length isn't supported by this song engine."
        if spec["takes"] > self.pins["max_takes"]:
            return f"This song engine creates at most {self.pins['max_takes']} take per request."
        if spec["language"] not in LANGUAGE_CODES:
            return "Sung vocals aren't available in the chosen language yet."
        return None

    def pipeline_parameters(self, request: RenderRequest):
        """MusicMe request -> the runner's render params (the settings validated on a T4 in Phase 2.3E)."""
        instrumental = request.vocal_type == "none"
        return {
            "caption": request.caption,
            "lyrics": "[Instrumental]" if instrumental else request.tagged_lyrics(),
            "instrumental": instrumental,
            "vocal_language": LANGUAGE_CODES.get(request.language, "unsupported"),
            "bpm": request.bpm,
            "duration": request.duration_seconds,
            "seed": request.seed,
            "inference_steps": self.pins["inference_steps"],
            "timesignature": "4",
        }

    # -- runner process -----------------------------------------------------------------------

    def _environment(self):
        env = {k: os.environ[k] for k in RUNNER_ENV_ALLOWLIST if k in os.environ}
        env.update(RUNNER_ENV_FORCED)
        return env

    def _start(self):
        self._buffer = b""
        self._proc = subprocess.Popen(
            [self.python, "-I", self.runner], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            cwd=self.home, env=self._environment(), close_fds=True, start_new_session=True)
        try:
            self._send({"type": "init", "protocol": acestep_protocol.PROTOCOL_VERSION, "home": self.home,
                        "device": self.s.acestep_device, "manifest": self.manifest})
            reply = self._read(time.monotonic() + self.s.acestep_startup_timeout_seconds)
            if reply is None:
                raise _RunnerFailure("STARTUP_TIMEOUT")
            if reply["type"] == "fatal":
                raise _RunnerFailure(reply["code"])
            if reply["type"] != "hello":
                raise _RunnerFailure("PROTOCOL")
            acestep_manifest.check_hello(reply, self.manifest)
        except acestep_manifest.ManifestError as error:
            self._kill()
            raise _RunnerFailure(error.code)
        except _RunnerFailure:
            self._kill()
            raise
        self.hello = reply
        log.info("acestep_runner_ready dtype=%s fp16_fix=%s load_s=%s", reply["dtype"], reply["fp16_fix"],
                 reply.get("load_seconds"))

    def _send(self, message):
        try:
            self._proc.stdin.write(acestep_protocol.encode(message, "to_runner"))
            self._proc.stdin.flush()
        except (BrokenPipeError, OSError, ValueError):
            raise _RunnerFailure("RUNNER_EXITED")

    def _read(self, until):
        """Next validated message, or None when `until` (monotonic) passes. Raises _RunnerFailure on exit/violation."""
        fd = self._proc.stdout.fileno()
        while b"\n" not in self._buffer:
            if len(self._buffer) > acestep_protocol.MAX_LINE_BYTES:
                raise _RunnerFailure("PROTOCOL")
            remaining = until - time.monotonic()
            if remaining <= 0:
                return None
            ready, _, _ = select.select([fd], [], [], remaining)
            if not ready:
                return None
            chunk = os.read(fd, 65536)
            if not chunk:
                raise _RunnerFailure("RUNNER_EXITED")
            self._buffer += chunk
        line, self._buffer = self._buffer.split(b"\n", 1)
        try:
            return acestep_protocol.decode(line, "from_runner")
        except acestep_protocol.ProtocolError:
            raise _RunnerFailure("PROTOCOL")

    def _kill(self):
        proc, self._proc, self.hello, self._buffer = self._proc, None, None, b""
        if proc is None:
            return
        for sig, wait in ((signal.SIGTERM, 3), (signal.SIGKILL, 3)):
            if proc.poll() is not None:
                break
            try:
                os.killpg(proc.pid, sig)
            except (ProcessLookupError, PermissionError):
                break
            try:
                proc.wait(wait)
            except subprocess.TimeoutExpired:
                continue
        for stream in (proc.stdin, proc.stdout):
            try:
                stream.close()
            except Exception:
                pass

    def close(self):
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                try:
                    self._send({"type": "shutdown"})
                    self._proc.wait(5)
                except (_RunnerFailure, subprocess.TimeoutExpired):
                    pass
            self._kill()

    # -- rendering ----------------------------------------------------------------------------

    def render(self, request, out_path, progress, deadline):
        params = self.pipeline_parameters(request)
        self._check_params(params)
        try:
            return self.render_params(params, out_path, progress, deadline)
        except RunnerError as error:
            raise self._error_for(error.code)

    def _check_params(self, params):
        if (len(params["lyrics"]) > self.pins["max_lyric_chars"] or len(params["caption"]) > self.pins["max_caption_chars"]
                or params["vocal_language"] == "unsupported"):
            raise EngineError("INVALID_REQUEST", "This song request isn't supported by the song engine.")

    def render_params(self, params, out_path, progress, deadline):
        """Runs one render with the runner's own parameters. Runner-reported failures raise RunnerError(code) (the GPU
        worker passes those codes on unchanged); transport failures, the deadline and cancellation behave as render()."""
        with self._lock:
            if self._proc is None or self._proc.poll() is not None:
                self._kill()
                try:
                    self._start()
                except _RunnerFailure as failure:
                    log.error("acestep_runner_restart_failed code=%s", failure.code)
                    raise EngineError("ENGINE_UNAVAILABLE", "The song engine is unavailable right now.", transient=True)
            job = uuid.uuid4().hex
            started = time.monotonic()
            try:
                self._send({"type": "render", "id": job, "params": params, "out_path": out_path})
                fraction = 0.0
                while True:
                    if time.monotonic() >= deadline:
                        self._kill()
                        raise EngineError("TIMEOUT", "The song took too long to create.", transient=True)
                    message = self._read(min(deadline, time.monotonic() + HEARTBEAT_SECONDS))
                    if message is None:  # no news: still heartbeat (keeps the lease) and check for cancellation
                        if not progress(fraction):
                            self._kill()
                            raise Cancelled()
                        continue
                    if message.get("id") != job or message["type"] in ("hello", "fatal"):
                        raise _RunnerFailure("PROTOCOL")
                    if message["type"] == "progress":
                        fraction = max(fraction, float(message["fraction"]))
                        if not progress(fraction):
                            self._kill()
                            raise Cancelled()
                        continue
                    if message["type"] == "error":
                        if message["code"] == "CUDA_OOM":
                            self._kill()  # free the GPU; the next render restarts the runner
                        raise RunnerError(message["code"])
                    return self._result(message, out_path, started)
            except _RunnerFailure as failure:
                self._kill()
                log.error("acestep_runner_failed code=%s", failure.code)
                raise EngineError("ENGINE_UNAVAILABLE", "The song engine stopped unexpectedly.", transient=True)

    def _error_for(self, code):
        if code == "NAN_LATENTS":
            # Retrying with a new seed is off by default until safe retry behaviour is verified (Phase 2.3F).
            return EngineError("AUDIO_INVALID", "The song couldn't be created this time. Please try again later.",
                               transient=bool(self.s.acestep_retry_nan))
        if code == "CUDA_OOM":
            return EngineError("ENGINE_UNAVAILABLE", "The song engine is busy. Please try again shortly.", transient=True)
        if code == "INVALID_PARAMS":
            return EngineError("INVALID_REQUEST", "This song request isn't supported by the song engine.")
        return EngineError("ENGINE_FAILED", "The song couldn't be created this time.")

    def _result(self, message, out_path, started):
        try:
            info = os.lstat(out_path)
        except FileNotFoundError:
            raise EngineError("ENGINE_FAILED", "The song couldn't be created this time.")
        if not stat.S_ISREG(info.st_mode) or info.st_size <= 44:
            raise EngineError("ENGINE_FAILED", "The song couldn't be created this time.")
        details = {"dtype": message.get("dtype") or (self.hello or {}).get("dtype"),
                   "fp16_lyric_encoder_fix": self.hello.get("fp16_fix") if self.hello else None,
                   "peak_vram_gb": message.get("peak_vram_gb"), "clipped_before_pcm": message["clipped_before_pcm"],
                   "diagnostics": message.get("diagnostics") or {}}
        return RenderResult("wav", message["sample_rate"], message["channels"],
                            message.get("render_ms") or int((time.monotonic() - started) * 1000), details)


class RemoteWorkerEngine(AceStepEngine):
    """DEVELOPMENT ONLY: the pinned ACE-Step pipeline on a remote GPU worker (musicme.gpu-job.v1).

    Provider-neutral: Modal, RunPod, Vast.ai or any host running audioservice/gpu_worker. Same mapping, limits, error
    codes and handshake checks as AceStepEngine; the worker drives the same validated runner. Before every render the
    serving worker's handshake is verified against the pinned manifest, and the downloaded WAV must match the worker's
    SHA-256 before it goes through the usual validate_wav -> commit. Nothing contacts the GPU until a song is requested
    (scale to zero), and the DEV spend ledger refuses renders beyond the daily budget. Worker credentials are read from
    a private file and never logged.
    """
    kind = "remote_gpu"
    POLL_SECONDS = 2.0
    MAX_STATUS_FAILURES = 5
    MAX_WORKER_RESTARTS = 1

    def __init__(self, settings, start=True, client=None, ledger=None):
        from .cost_guard import CostLedger
        from .worker_client import WorkerClient, WorkerError, load_credentials, parse_credentials, validate_url
        self.s = settings
        self.manifest = acestep_manifest.load()
        self.pins = self.manifest["limits"]
        self.model_version = self.manifest["weights"]["config_path"]
        self.model_revision = (f"code {self.manifest['code']['commit'][:12]} / "
                               f"weights {self.manifest['weights']['revision'][:12]}")
        self.hello = None
        self._lock = threading.Lock()
        self._closing = threading.Event()
        self._active_job_id = None
        self._worker_error = WorkerError
        if client is None:
            try:
                url = validate_url(settings.worker_url, settings.worker_allowed_hosts,
                                   allow_loopback_http=settings.environment == "development")
            except WorkerError:
                raise EngineUnavailable("GPU worker URL must be HTTPS on an allowed host (or loopback through a tunnel).")
            try:
                token, headers = (load_credentials(settings.worker_credentials_file) if settings.worker_credentials_file
                                  else parse_credentials(settings.worker_credentials_json))
            except WorkerError as error:
                raise EngineUnavailable(f"GPU worker credentials refused ({error.code}); use a private file (chmod 600).")
            client = WorkerClient(url, token, headers)
        self._client = client
        self.ledger = ledger or CostLedger(
            settings.gpu_ledger_file or os.path.join(os.path.dirname(os.path.abspath(settings.storage_root)), "gpu-ledger.json"),
            settings.gpu_price_per_hour_usd, settings.gpu_daily_budget_usd, settings.gpu_max_renders_per_day,
            settings.gpu_reserve_seconds, settings.gpu_idle_tail_seconds)

    def close(self):
        self._closing.set()
        job_id = self._active_job_id
        if job_id:
            try:
                self._client.cancel(job_id, timeout=5)
            except self._worker_error:
                pass  # the worker-side deadline remains the final backstop
        self.hello = None  # the provider scales the worker to zero on its own

    def _kill(self):
        self.hello = None

    def render(self, request, out_path, progress, deadline):
        from .cost_guard import GpuBudgetExceeded
        params = self.pipeline_parameters(request)
        self._check_params(params)
        with self._lock:
            if self._closing.is_set():
                raise Cancelled()
            try:
                reserved = self.ledger.reserve()
            except GpuBudgetExceeded as error:
                log.warning("gpu_budget_refused reason=%s", error)
                raise EngineError("ENGINE_UNAVAILABLE", "Today's DEV song budget is used up. Try again tomorrow.")
            started = time.monotonic()
            try:
                return self._render_remote(params, out_path, progress, deadline, started)
            finally:
                try:
                    self.ledger.settle(reserved, time.monotonic() - started)
                except GpuBudgetExceeded:
                    # The pessimistic reservation remains committed. Do not throw away a valid WAV and trigger a
                    # second paid render merely because the post-render accounting update was unavailable.
                    log.error("gpu_budget_settlement_failed reservation_retained=true")

    # -- remote steps -------------------------------------------------------------------------

    def _wait(self, seconds, progress, fraction, deadline, on_cancel=None):
        """Sleeps in heartbeat-sized steps, keeping the job lease alive; honours cancellation and the deadline."""
        until = min(deadline, time.monotonic() + seconds)
        while True:
            if self._closing.is_set():
                if on_cancel:
                    on_cancel()
                raise Cancelled()
            if not progress(fraction):
                if on_cancel:
                    on_cancel()
                raise Cancelled()
            remaining = until - time.monotonic()
            if remaining <= 0:
                return
            time.sleep(min(HEARTBEAT_SECONDS, remaining))

    def _ensure_ready(self, progress, deadline):
        """Verifies the serving worker's handshake (waiting through a cold start, within the startup timeout)."""
        until = min(deadline, time.monotonic() + self.s.worker_startup_timeout_seconds)
        while True:
            if time.monotonic() >= until:
                raise EngineError("ENGINE_UNAVAILABLE", "The song engine took too long to start.", transient=True)
            try:
                hello = dict(self._client.hello(timeout=self._request_timeout(until)), type="hello")
                acestep_protocol.validate(hello, "from_runner")
                acestep_manifest.check_hello(hello, self.manifest)
                self.hello = hello
                return
            except self._worker_error as error:
                if error.code in ("WORKER_UNAUTHORIZED", "WORKER_FAILED", "WORKER_NOT_FOUND"):
                    log.error("gpu_worker_refused code=%s", error.code)
                    raise EngineError("ENGINE_UNAVAILABLE", "The song engine isn't set up correctly.")
                if time.monotonic() >= until:
                    log.error("gpu_worker_start_timeout code=%s", error.code)
                    raise EngineError("ENGINE_UNAVAILABLE", "The song engine took too long to start.", transient=True)
                self._wait(error.retry_after or 5, progress, 0.0, until)
            except acestep_protocol.ProtocolError:
                log.error("gpu_worker_refused code=PROTOCOL")
                raise EngineError("ENGINE_UNAVAILABLE", "The song engine isn't set up correctly.")
            except acestep_manifest.ManifestError as error:
                log.error("gpu_worker_refused code=%s", error.code)
                raise EngineError("ENGINE_UNAVAILABLE", "The song engine isn't set up correctly.")

    def _render_remote(self, params, out_path, progress, deadline, started):
        WorkerError = self._worker_error
        self.hello = None
        self._ensure_ready(progress, deadline)
        job_id = uuid.uuid4().hex
        self._active_job_id = job_id
        remote_deadline_seconds = max(10, int(deadline - time.monotonic()))

        def cancel_remote():
            try:
                self._client.cancel(job_id, timeout=5)
            except WorkerError:
                pass  # the worker's own deadline still stops it

        try:
            while True:  # submit (idempotent per job id), waiting while the single GPU slot is busy
                if self._closing.is_set():
                    raise Cancelled()
                if time.monotonic() >= deadline:
                    raise EngineError("TIMEOUT", "The song took too long to create.", transient=True)
                try:
                    job = self._client.submit(job_id, params, remote_deadline_seconds,
                                              timeout=self._request_timeout(deadline))
                    if self._closing.is_set():
                        cancel_remote()
                        raise Cancelled()
                    break
                except WorkerError as error:
                    if error.code not in ("WORKER_BUSY", "WORKER_STARTING", "WORKER_UNREACHABLE"):
                        log.error("gpu_worker_submit_failed code=%s", error.code)
                        raise EngineError("ENGINE_UNAVAILABLE", "The song engine stopped unexpectedly.", transient=True)
                    self._wait(error.retry_after or 5, progress, 0.0, deadline, on_cancel=cancel_remote)
            failures, restarts, fraction = 0, 0, 0.0
            while job["state"] not in gpu_protocol.FINAL_STATES:
                if time.monotonic() >= deadline:
                    cancel_remote()
                    raise EngineError("TIMEOUT", "The song took too long to create.", transient=True)
                self._wait(self.POLL_SECONDS, progress, fraction, deadline, on_cancel=cancel_remote)
                try:
                    job = self._client.status(job_id, timeout=self._request_timeout(deadline))
                    failures = 0
                except WorkerError as error:
                    if error.code == "WORKER_NOT_FOUND" and restarts < self.MAX_WORKER_RESTARTS:
                        # A scale-to-zero host can restart between polls. Re-verify the model and resubmit the same
                        # idempotency key once; a surviving worker returns the existing job, a fresh worker recreates it.
                        restarts += 1
                        self.hello = None
                        self._ensure_ready(progress, deadline)
                        try:
                            job = self._client.submit(job_id, params, remote_deadline_seconds,
                                                      timeout=self._request_timeout(deadline))
                            failures = 0
                            continue
                        except WorkerError as restart_error:
                            error = restart_error
                    failures += 1
                    if error.code == "WORKER_PROTOCOL" or failures > self.MAX_STATUS_FAILURES:
                        cancel_remote()
                        log.error("gpu_worker_status_failed code=%s", error.code)
                        raise EngineError("ENGINE_UNAVAILABLE", "The song engine stopped unexpectedly.", transient=True)
                    continue
                fraction = max(fraction, min(0.9, float(job["progress"]) * 0.9))
        finally:
            self._active_job_id = None
        if job["state"] == "failed":
            code = job["error"]["code"]
            log.error("gpu_worker_job_failed code=%s", code)
            if code in acestep_protocol.ERROR_CODES:
                raise self._error_for(code)
            if code == "TIMEOUT":
                raise EngineError("TIMEOUT", "The song took too long to create.", transient=True)
            if code in ("AUDIO_INVALID", "AUDIO_SILENT", "AUDIO_CLIPPED", "AUDIO_DURATION"):
                raise EngineError(code, "The song couldn't be created this time. Please try again.", transient=True)
            raise EngineError("ENGINE_UNAVAILABLE", "The song engine stopped unexpectedly.", transient=True)
        if job["state"] == "cancelled":  # not by us: the worker was shutting down
            raise EngineError("ENGINE_UNAVAILABLE", "The song engine stopped unexpectedly.", transient=True)
        result = job["result"]
        try:
            self._client.download(job_id, out_path, result["sha256"], result["size_bytes"], self.s.max_audio_bytes,
                                  timeout=self._request_timeout(deadline))
        except WorkerError as error:
            log.error("gpu_worker_download_failed code=%s", error.code)
            raise EngineError("ENGINE_FAILED", "The song couldn't be created this time.")
        finally:
            try:
                self._client.delete(job_id, timeout=5)  # the worker keeps no copy once we have it
            except WorkerError:
                pass  # its output TTL removes it anyway
        message = {k: v for k, v in result.items() if k not in ("sha256", "size_bytes")}
        try:
            acestep_protocol.validate(dict(message, type="result", id=job_id, ok=True), "from_runner")
        except acestep_protocol.ProtocolError:
            _discard(out_path)
            raise EngineError("ENGINE_UNAVAILABLE", "The song engine sent an unreadable reply.", transient=True)
        return self._result(message, out_path, started)

    @staticmethod
    def _request_timeout(deadline):
        return max(0.25, min(30.0, deadline - time.monotonic()))


def _discard(path):
    try:
        os.remove(path)
    except FileNotFoundError:
        pass


def engine_from_settings(settings, mock_fault=None, ledger=None, provider_state=None):
    if settings.engine == "none":
        return None
    if settings.engine == "mock":
        if settings.environment == "production":
            raise SystemExit("The mock engine is not available in production.")
        return MockToneEngine(fault=mock_fault)
    try:
        if settings.engine == "fal":
            from .fal_engine import FalAudioEngine
            return FalAudioEngine(settings, ledger=ledger, provider_state=provider_state)
        if settings.engine == "remote_gpu":
            return RemoteWorkerEngine(settings, ledger=ledger)  # contacts the GPU worker only when requested
        return AceStepEngine(settings)  # starts the runner and verifies its handshake before serving anything
    except EngineUnavailable as error:
        raise SystemExit(str(error))
