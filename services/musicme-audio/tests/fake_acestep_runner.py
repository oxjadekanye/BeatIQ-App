"""TESTS ONLY: a stdlib stand-in for audioservice/acestep/runner.py that speaks the same JSON-lines protocol.

Tests write a small wrapper that sets MODE (and RECORD_DIR) and then executes this file, so no test-only switch exists
in the real engine, runner or configuration. Like the real runner it keeps the original stdout for protocol messages
and sends everything else to stderr.
"""
import hashlib
import json
import math
import os
import signal
import struct
import sys
import time
import wave

MODE = globals().get("MODE", "ok")
RECORD_DIR = globals().get("RECORD_DIR", "")

_PROTOCOL_FD = os.dup(1)
os.dup2(2, 1)
sys.stdout = sys.stderr
_OUT = os.fdopen(_PROTOCOL_FD, "wb", buffering=0)
from audioservice.acestep import manifest as pinned  # noqa: E402
from audioservice.acestep import protocol  # noqa: E402


def fatal(code):
    send({"type": "fatal", "code": code, "message": "fake runner refused"})
    sys.exit(2)


def send(message, raw=None):
    _OUT.write(raw if raw is not None else protocol.encode(message, "from_runner"))


def receive():
    line = sys.stdin.buffer.readline(protocol.MAX_LINE_BYTES + 1)
    return protocol.decode(line.rstrip(b"\n"), "to_runner") if line else None


def write_wav(path, seconds, amplitude=8000, rate=48000, junk=False):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as raw:
        if junk:
            raw.write(b"this is not a wav file" * 10)
            return 0
        with wave.open(raw, "wb") as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(rate)
            frames = int(seconds * rate)
            step = 2 * math.pi * 220 / rate
            w.writeframes(b"".join(struct.pack("<hh", int(amplitude * math.sin(step * n)), int(amplitude * math.sin(step * n)))
                                   for n in range(frames)))
            return frames


def record(name, value):
    if RECORD_DIR:
        with open(os.path.join(RECORD_DIR, name), "w") as f:
            json.dump(value, f)


def main():
    if MODE == "print_noise":
        print("library chatter on stdout must not corrupt the protocol")
    record("startup.json", {"argv": sys.argv, "env": sorted(os.environ), "env_values": dict(os.environ)})
    try:
        init = receive()
    except protocol.ProtocolError:
        fatal("PROTOCOL")
    # Same startup refusals as the real runner (before any model would load).
    if init["protocol"] != protocol.PROTOCOL_VERSION:
        fatal("PROTOCOL")
    if init["manifest"] != pinned.load():
        fatal("MANIFEST_MISMATCH")
    if init["device"] != "cuda":
        fatal("INVALID_MODEL_CONFIG")
    if os.path.exists(os.path.join(init["home"], "checkpoints", "acestep-5Hz-lm-1.7B")):
        fatal("FORBIDDEN_WEIGHTS")
    if MODE == "crash_start":
        os._exit(3)
    if MODE == "silent_start":
        time.sleep(3600)
    manifest = init["manifest"]
    if MODE == "fatal_manifest":
        send({"type": "fatal", "code": "MANIFEST_MISMATCH", "message": "checksum mismatch: vae/diffusion_pytorch_model.safetensors"})
        sys.exit(2)
    hello = {"type": "hello", "protocol": 1, "ok": True, "code_commit": manifest["code"]["commit"],
             "weights_revision": manifest["weights"]["revision"], "weights_sha256": dict(manifest["weights"]["files"]),
             "config_path": manifest["weights"]["config_path"], "dtype": "torch.float16", "fp16_fix": "applied",
             "selftest": {"passed": True}, "torch": "fake", "gpu": "Fake T4", "load_seconds": 0.1, "python": "3.12.13"}
    if MODE == "bad_commit":
        hello["code_commit"] = "0" * 40
    if MODE == "bad_hashes":
        hello["weights_sha256"]["vae/diffusion_pytorch_model.safetensors"] = "f" * 64
    if MODE == "bad_config":
        hello["config_path"] = "acestep-v15-xl-turbo"
    if MODE == "selftest_fail":
        hello["selftest"] = {"passed": False}
    if MODE == "fp16_without_fix":
        hello["fp16_fix"] = "not_needed"
        hello.pop("selftest")
    if MODE == "float32":
        hello["dtype"] = "torch.float32"
    if MODE == "bfloat16":
        hello.update(dtype="torch.bfloat16", fp16_fix="not_needed")
        hello.pop("selftest")
    if MODE == "garbage_hello":
        send(None, raw=b"{not json\n")
        time.sleep(3600)
    send(hello)
    renders = 0
    while True:
        message = receive()
        if message is None or message["type"] == "shutdown":
            record("shutdown.json", {"clean": message is not None})
            return
        renders += 1
        job, params, out = message["id"], message["params"], message["out_path"]
        record(f"render-{renders}.json", {"lyrics_sha256": hashlib.sha256(params["lyrics"].encode()).hexdigest(),
                                          "params_keys": sorted(params), "out_dir": os.path.dirname(out)})
        limits = pinned.load()["limits"]
        if (len(params["lyrics"]) > limits["max_lyric_chars"] or params["inference_steps"] != limits["inference_steps"]
                or not limits["min_duration_seconds"] <= params["duration"] <= limits["max_duration_seconds"]
                or params["vocal_language"] != "en" or not os.path.basename(out).startswith(".tmp-") or os.path.lexists(out)):
            send({"type": "error", "id": job, "code": "INVALID_PARAMS", "message": "outside the pinned limits"})
            continue
        send({"type": "progress", "id": job, "fraction": 0.1})
        if MODE == "crash_render":
            os._exit(4)
        if MODE in ("hang", "hang_ignore_term"):
            if MODE == "hang_ignore_term":
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
            record("pid.json", {"pid": os.getpid()})
            time.sleep(3600)
        if MODE == "slow":
            for i in range(12):
                time.sleep(0.25)
                send({"type": "progress", "id": job, "fraction": min(0.9, 0.1 + i * 0.07)})
        if MODE == "silent_slow":
            time.sleep(5)
        if MODE == "nan" or (MODE == "nan_once" and renders == 1):
            send({"type": "error", "id": job, "code": "NAN_LATENTS",
                  "message": "Generation produced NaN or Inf latents (shape=[1, 750, 64], dtype=torch.float16)"})
            continue
        if MODE == "oom":
            send({"type": "error", "id": job, "code": "CUDA_OOM", "message": "CUDA out of memory"})
            continue
        if MODE == "wrong_id":
            send({"type": "result", "id": "x" * 32, "ok": True, "sample_rate": 48000, "channels": 2, "frames": 1,
                  "clipped_before_pcm": 0})
            continue
        if MODE == "oversize":
            send(None, raw=b'{"type":"progress","id":"' + b"a" * (protocol.MAX_LINE_BYTES + 10) + b'"}\n')
            continue
        if MODE == "symlink_out":
            os.symlink("/etc/hosts", out)
            frames = 1
        elif MODE == "no_file":
            frames = 0
        else:
            seconds = {"short_wav": 3}.get(MODE, params["duration"])
            frames = write_wav(out, seconds, amplitude=0 if MODE == "silent_wav" else 8000, junk=MODE == "garbage_wav")
        send({"type": "progress", "id": job, "fraction": 0.9})
        send({"type": "result", "id": job, "ok": True, "sample_rate": 48000, "channels": 2, "frames": frames,
              "clipped_before_pcm": 0, "render_ms": 1234, "peak_vram_gb": 6.87, "dtype": "torch.float16",
              "diagnostics": {"lyric_tail_max_abs": 293783.9, "lyric_encoder_output_finite": True,
                              "lyric_encoder_output_dtype": "torch.float16"}})


main()
