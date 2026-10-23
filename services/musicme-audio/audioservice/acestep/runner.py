"""ACE-Step 1.5 runner: a separate process started by AceStepEngine with ACE-Step's own Python 3.12 environment.

Launched as `<venv python> -I runner.py` with an allow-listed environment and no arguments. Everything it needs (the
ACE-Step checkout, device, pinned manifest) and every render request arrives as JSON lines on stdin; replies go to
the original stdout. Library output (ACE-Step, loguru, tqdm, print) is redirected to stderr, which the service
discards, so it can never corrupt the protocol or leak lyrics into service logs.

Before loading anything it verifies: protocol version, the service's manifest equals the runner's own, the checkout
commit, the SHA-256 of every pinned file, that no LM planning model is present, and that ACE-Step's download path
looks exactly like v0.1.8 (then blocks every download route). After loading it requires an allowed dtype and, in
float16, applies the lyric-encoder fix only after its GPU self-test passes. Any failure is reported as a "fatal"
message and the runner exits without rendering.
"""
import os
import sys
import threading
import time

# 1. Protocol channel: keep the real stdout for JSON lines; send everything else (library prints) to stderr.
_PROTOCOL_FD = os.dup(1)
os.dup2(2, 1)
sys.stdout = sys.stderr
_OUT = os.fdopen(_PROTOCOL_FD, "wb", buffering=0)
_SEND_LOCK = threading.Lock()

# 2. Offline, headless runtime, set before torch, transformers or matplotlib are imported.
for _key, _value in {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1",
                     "GRADIO_ANALYTICS_ENABLED": "False", "ACESTEP_SAVE_MEMORY": "1", "MPLBACKEND": "Agg",
                     "TOKENIZERS_PARALLELISM": "false"}.items():
    os.environ[_key] = _value
os.environ.pop("ACESTEP_CHECKPOINTS_DIR", None)
os.environ.pop("HF_TOKEN", None)

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _SERVICE_ROOT)
from audioservice.acestep import manifest as pinned  # noqa: E402
from audioservice.acestep import protocol  # noqa: E402

# Names that ACE-Step v0.1.8's InitServiceDownloadsMixin._ensure_models_present reads from its module globals.
PATCHED_NAMES = ("check_main_model_exists", "ensure_main_model", "ensure_dit_model", "ensure_vae_model")
REQUIRED_COMPONENTS = ("acestep-v15-turbo", "vae", "Qwen3-Embedding-0.6B")
LANGUAGES = {"en"}


def send(message):
    line = protocol.encode(message, "from_runner")
    with _SEND_LOCK:
        _OUT.write(line)


def receive():
    line = sys.stdin.buffer.readline(protocol.MAX_LINE_BYTES + 1)
    if not line:
        return None
    return protocol.decode(line.rstrip(b"\n"), "to_runner")


def fatal(code, message=""):
    send({"type": "fatal", "code": code, "message": message[:300]})
    sys.exit(2)


def scrub(text, lyrics):
    """Error text may echo inputs; remove every lyric line before it leaves the runner."""
    text = str(text)[:600]
    for line in (lyrics or "").splitlines():
        line = line.strip()
        if len(line) >= 4:
            text = text.replace(line, "[lyrics]")
    return text[:300]


def no_downloads(*args, **kwargs):
    raise RuntimeError("Auto-download is disabled in the MusicMe runner (weights are pre-verified).")


def disable_downloads(checkpoints):
    """Fails closed unless ACE-Step's download path looks exactly like v0.1.8, then blocks every download route."""
    import importlib
    downloads = importlib.import_module("acestep.core.generation.handler.init_service_downloads")
    model_downloader = importlib.import_module("acestep.model_downloader")
    mixin = getattr(downloads, "InitServiceDownloadsMixin", None)
    method = getattr(mixin, "_ensure_models_present", None)
    used = set(getattr(getattr(method, "__code__", None), "co_names", ()))
    if method is None or [n for n in PATCHED_NAMES if n not in used or not hasattr(downloads, n)]:
        fatal("DOWNLOAD_PATH_CHANGED", "ACE-Step download path differs from v0.1.8")

    def minimum_set_present(checkpoints_dir=None):
        root = str(checkpoints_dir or checkpoints)
        return all(any(os.path.exists(os.path.join(root, c, w)) for w in ("model.safetensors", "diffusion_pytorch_model.safetensors"))
                   for c in REQUIRED_COMPONENTS)

    downloads.check_main_model_exists = minimum_set_present
    for name in ("ensure_main_model", "ensure_dit_model", "ensure_vae_model"):
        setattr(downloads, name, no_downloads)
        setattr(model_downloader, name, no_downloads)
    for name in ("_download_from_huggingface_internal", "_download_from_modelscope_internal", "_smart_download"):
        if hasattr(model_downloader, name):
            setattr(model_downloader, name, no_downloads)
    try:
        import huggingface_hub
        huggingface_hub.snapshot_download = huggingface_hub.hf_hub_download = no_downloads
    except ImportError:
        pass
    try:
        import modelscope
        modelscope.snapshot_download = no_downloads
    except ImportError:
        pass


def start():
    init = receive()
    if init is None or init["type"] != "init":
        fatal("PROTOCOL", "expected init")
    if init["protocol"] != protocol.PROTOCOL_VERSION:
        fatal("PROTOCOL", "protocol version mismatch")
    manifest = pinned.load()
    if init["manifest"] != manifest:
        fatal("MANIFEST_MISMATCH", "service and runner manifests differ")
    if init["device"] != "cuda":
        fatal("INVALID_MODEL_CONFIG", "only the cuda device is supported")
    home = os.path.realpath(init["home"])
    checkpoints = os.path.join(home, "checkpoints")
    try:
        commit = pinned.read_git_head(home)
    except (OSError, pinned.ManifestError):
        fatal("CODE_MISMATCH", "cannot read the ACE-Step checkout's commit")
    if commit != manifest["code"]["commit"]:
        fatal("CODE_MISMATCH", "ACE-Step checkout is not the pinned commit")
    try:
        digests = pinned.verify_checkpoints(checkpoints, manifest)
    except pinned.ManifestError as error:
        fatal(error.code, error.message)

    sys.path.insert(0, home)
    disable_downloads(checkpoints)
    import torch
    from audioservice.acestep import fp16_fix
    from acestep.handler import AceStepHandler

    started = time.time()
    dit = AceStepHandler()
    status, ok = dit.initialize_service(project_root=home, config_path=manifest["weights"]["config_path"], device="cuda",
                                        use_flash_attention=False, compile_model=False, quantization=None)
    if not ok:
        fatal("LOAD_FAILED", "initialize_service failed")
    dtype = str(dit.dtype)
    if dtype not in manifest["runtime"]["allowed_dtypes"]:
        fatal("UNSUPPORTED_DTYPE", f"unsupported dtype {dtype}")
    selftest, fix = None, "not_needed"
    if dit.dtype == torch.float16:
        selftest = fp16_fix.fp16_fix_selftest("cuda")
        if not selftest.get("passed"):
            fatal("SELFTEST_FAILED", "float16 lyric-encoder fix self-test failed")
        if not fp16_fix.compute_lyric_encoder_tail_in_float32(dit.model):
            fatal("FIX_NOT_APPLICABLE", "lyric encoder not recognised as v0.1.8")
        fix = "applied"
    state = {}
    fp16_fix.install_diagnostics(dit.model, state)
    hello = {"type": "hello", "protocol": protocol.PROTOCOL_VERSION, "ok": True, "code_commit": commit,
             "weights_revision": manifest["weights"]["revision"], "weights_sha256": digests,
             "config_path": manifest["weights"]["config_path"], "dtype": dtype, "fp16_fix": fix,
             "torch": torch.__version__, "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
             "load_seconds": round(time.time() - started, 1), "python": sys.version.split()[0]}
    if selftest is not None:
        hello["selftest"] = selftest
    send(hello)
    return manifest, dit, state


def check_params(params, out_path, limits):
    problems = []
    if len(params["lyrics"]) > limits["max_lyric_chars"] or len(params["caption"]) > limits["max_caption_chars"]:
        problems.append("text too long")
    if not limits["min_duration_seconds"] <= params["duration"] <= limits["max_duration_seconds"]:
        problems.append("duration out of range")
    if params["inference_steps"] != limits["inference_steps"] or params["seed"] < 0 or params["vocal_language"] not in LANGUAGES:
        problems.append("unsupported generation settings")
    parent = os.path.dirname(out_path)
    if (not os.path.isabs(out_path) or not os.path.basename(out_path).startswith(".tmp-") or not os.path.isdir(parent)
            or os.path.lexists(out_path)):
        problems.append("invalid output path")
    return problems


def render(message, manifest, dit, state):
    import numpy as np
    import torch
    import wave
    from acestep.inference import GenerationConfig, GenerationParams, generate_music

    job, params, out_path = message["id"], message["params"], message["out_path"]
    if check_params(params, out_path, manifest["limits"]):
        send({"type": "error", "id": job, "code": "INVALID_PARAMS", "message": "request outside the pinned limits"})
        return
    for key in list(state.get("diagnostics", {})):
        state["diagnostics"][key] = None
    done = threading.Event()
    started = time.monotonic()

    def heartbeat():
        while not done.wait(2.0):
            elapsed = time.monotonic() - started
            send({"type": "progress", "id": job, "fraction": round(min(0.95, elapsed / (elapsed + 20.0)), 3)})

    beat = threading.Thread(target=heartbeat, daemon=True)
    beat.start()
    try:
        generation = GenerationParams(task_type="text2music", caption=params["caption"], lyrics=params["lyrics"],
                                      instrumental=params["instrumental"], vocal_language=params["vocal_language"],
                                      bpm=params["bpm"], duration=params["duration"], timesignature=params["timesignature"],
                                      inference_steps=params["inference_steps"], seed=params["seed"], thinking=False,
                                      use_cot_caption=False, use_cot_metas=False, reference_audio=None, src_audio=None)
        if generation.task_type != "text2music" or generation.reference_audio is not None or generation.src_audio is not None:
            raise RuntimeError("cloning inputs are disabled")
        config = GenerationConfig(batch_size=1, use_random_seed=False, seeds=[params["seed"]])
        torch.cuda.reset_peak_memory_stats()
        result = generate_music(dit, None, generation, config, save_dir=None)
        if not result.success or not result.audios:
            raise RuntimeError(result.error or "generation failed")
    except Exception as error:  # report a code; the scrubbed message never contains lyrics
        done.set()
        text = str(error)
        code = ("NAN_LATENTS" if "NaN or Inf latents" in text else
                "CUDA_OOM" if "out of memory" in text.lower() else "GENERATION_FAILED")
        send({"type": "error", "id": job, "code": code, "message": scrub(text, params["lyrics"])})
        return
    done.set()
    try:
        audio = result.audios[0]
        tensor = audio["tensor"]
        samples = tensor.detach().float().cpu().numpy() if hasattr(tensor, "detach") else np.asarray(tensor, dtype=np.float32)
        if samples.ndim == 1:
            samples = samples[None, :]
        clipped = int(np.sum(np.abs(samples) >= 1.0))
        pcm = (np.clip(samples, -1.0, 1.0) * 32767.0).round().astype("<i2").T
        fd = os.open(out_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "wb") as raw, wave.open(raw, "wb") as w:
            w.setnchannels(pcm.shape[1]); w.setsampwidth(2); w.setframerate(int(audio["sample_rate"])); w.writeframes(pcm.tobytes())
    except Exception as error:
        send({"type": "error", "id": job, "code": "OUTPUT_FAILED", "message": scrub(error, params["lyrics"])})
        return
    send({"type": "result", "id": job, "ok": True, "sample_rate": int(audio["sample_rate"]), "channels": int(pcm.shape[1]),
          "frames": int(pcm.shape[0]), "clipped_before_pcm": clipped, "render_ms": int((time.monotonic() - started) * 1000),
          "peak_vram_gb": round(torch.cuda.max_memory_allocated() / 1e9, 2), "dtype": str(dit.dtype),
          "diagnostics": dict(state.get("diagnostics", {}))})


def main():
    manifest, dit, state = start()
    while True:
        message = receive()
        if message is None or message["type"] == "shutdown":
            return
        if message["type"] != "render":
            fatal("PROTOCOL", "unexpected message")
        render(message, manifest, dit, state)


if __name__ == "__main__":
    try:
        main()
    except protocol.ProtocolError:
        try:
            send({"type": "fatal", "code": "PROTOCOL", "message": "invalid message"})
        finally:
            sys.exit(2)
