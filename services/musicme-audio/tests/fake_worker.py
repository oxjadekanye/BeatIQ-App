"""TESTS ONLY: an in-process GPU worker with a fake engine and real musicme.gpu-job.v1 HTTP."""
import math
import os
import struct
import threading
import time
import wave

from audioservice.acestep import manifest as pinned
from audioservice.engines import Cancelled, EngineError, RenderResult, RunnerError
from audioservice.gpu_worker.server import GpuWorker, WorkerSettings

TOKEN = "fake-worker-token-at-least-thirty-two-characters"


class FakeGpuEngine:
    def __init__(self, mode="ok"):
        self.mode = mode
        self.calls = []
        manifest = pinned.load()
        self.hello = {
            "type": "hello", "protocol": 1, "ok": True, "code_commit": manifest["code"]["commit"],
            "weights_revision": manifest["weights"]["revision"],
            "weights_sha256": dict(manifest["weights"]["files"]),
            "config_path": manifest["weights"]["config_path"], "dtype": "torch.bfloat16",
            "fp16_fix": "not_needed", "torch": "fake", "gpu": "fake-t4", "load_seconds": 0.01,
            "python": "3.12",
        }

    def close(self):
        pass

    def render_params(self, params, out_path, progress, deadline):
        self.calls.append(dict(params))
        mode = self.mode
        if mode == "nan":
            raise RunnerError("NAN_LATENTS")
        if mode == "oom":
            raise RunnerError("CUDA_OOM")
        if mode == "crash":
            raise EngineError("ENGINE_UNAVAILABLE", "fake crash", transient=True)
        steps = 60 if mode == "hang" else (12 if mode == "slow" else 3)
        for index in range(steps):
            if time.monotonic() >= deadline:
                raise EngineError("TIMEOUT", "fake timeout", transient=True)
            if not progress(index / steps):
                raise Cancelled()
            time.sleep(0.08 if mode == "slow" else (0.25 if mode == "hang" else 0.005))
        seconds = 2 if mode == "short" else params["duration"]
        if mode == "garbage":
            with open(out_path, "wb") as out:
                out.write(b"not a wav" * 20)
        else:
            _write_wav(out_path, seconds, amplitude=0 if mode == "silent" else 8000)
        return RenderResult("wav", 48000, 2, 50, {
            "clipped_before_pcm": 0, "dtype": "torch.bfloat16", "peak_vram_gb": 6.8,
            "diagnostics": {"lyric_encoder_output_finite": True},
        })


def _write_wav(path, seconds, amplitude):
    rate = 48000
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as raw, wave.open(raw, "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        frames = bytearray()
        for sample in range(int(seconds * rate)):
            value = int(amplitude * math.sin(2 * math.pi * 220 * sample / rate))
            frames.extend(struct.pack("<hh", value, value))
        wav.writeframes(frames)


class FakeWorker:
    def __init__(self, mode="ok", token=TOKEN, max_queue=1, max_job_seconds=60):
        self.engine = FakeGpuEngine(mode)
        settings = WorkerSettings(token=token, host="127.0.0.1", port=0, max_queue=max_queue,
                                  max_job_seconds=max_job_seconds, output_ttl_seconds=60,
                                  record_ttl_seconds=120)
        self.worker = GpuWorker(settings, engine_factory=lambda: self.engine)
        self.server = self.worker.serve()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def ready(self, timeout=2):
        deadline = time.monotonic() + timeout
        while self.worker.state == "starting" and time.monotonic() < deadline:
            time.sleep(0.005)
        assert self.worker.state == "ready"
        return self

    def close(self):
        self.worker.shutdown()
        self.thread.join(2)
        self.server.server_close()
