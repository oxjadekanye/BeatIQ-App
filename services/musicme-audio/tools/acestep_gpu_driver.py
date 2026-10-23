"""Phase 2.3F real-GPU integration test: a standalone protocol driver for audioservice/acestep/runner.py.

Standard library only. It plays the service's side of the JSON-lines protocol against the REAL runner (ACE-Step's
venv Python) without uploading or importing the rest of the audio service: only audioservice/acestep/ is needed.
Each check is recorded separately in report.json:

  probe      download blocking, exercised against the real ACE-Step v0.1.8 modules (no model load)
  negative   runner refuses: tampered manifest, unsupported device, wrong protocol, garbage input, LM weights present
  startup    real startup: pinned commit, all 8 file hashes, model load, dtype, float16 fix + GPU self-test, handshake
  params     invalid render parameters and output paths are rejected without generating
  render     one 30 s render with the Phase 2.3E settings; WAV validated; diagnostics recorded
  kill       a render killed mid-way: whole process group gone, partial output removed
  restart    a fresh runner re-verifies and loads after the kill; clean shutdown

The report never contains lyrics (only their SHA-256), environment values, tokens or paths outside the test folder.
"""
import argparse
import array
import hashlib
import json
import operator
import os
import select
import shutil
import signal
import subprocess
import sys
import time
import wave

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _SERVICE_ROOT)
from audioservice.acestep import manifest as pinned  # noqa: E402
from audioservice.acestep import protocol  # noqa: E402

# Must equal audioservice.engines.RUNNER_ENV_ALLOWLIST / RUNNER_ENV_FORCED (checked by the local test).
RUNNER_ENV_ALLOWLIST = ("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "HOME", "CUDA_VISIBLE_DEVICES",
                        "NVIDIA_VISIBLE_DEVICES", "NVIDIA_DRIVER_CAPABILITIES", "LD_LIBRARY_PATH", "CUDA_HOME")
RUNNER_ENV_FORCED = {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1",
                     "GRADIO_ANALYTICS_ENABLED": "False", "ACESTEP_SAVE_MEMORY": "1", "MPLBACKEND": "Agg",
                     "PYTHONUNBUFFERED": "1", "PYTHONDONTWRITEBYTECODE": "1", "TOKENIZERS_PARALLELISM": "false"}
# The Phase 2.3E request (original, synthetic lyrics).
CAPTION = "acoustic pop, warm, hopeful, acoustic guitar, soft piano, female vocal, warm, 96 bpm"
LYRICS = ("[Verse]\nMorning light on the kitchen floor\nCoffee steam and an open door\n"
          "[Chorus]\nHere we are, here we stay\nLittle sparks that light the way")
PHASE_2_3E_SHA256 = "8c0f2954de9b70592ecd085b29782e0cd39ac63f3ed9a5bfa835c0a020ab177e"


class Failure(Exception):
    pass


class Runner:
    """One runner process, driven exactly as AceStepEngine drives it."""

    def __init__(self, python, runner, home):
        env = {k: os.environ[k] for k in RUNNER_ENV_ALLOWLIST if k in os.environ}
        env.update(RUNNER_ENV_FORCED)
        self.proc = subprocess.Popen([python, "-I", runner], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, cwd=home, env=env, close_fds=True, start_new_session=True)
        self.buffer = b""

    def send(self, message=None, raw=None):
        data = raw if raw is not None else protocol.encode(message, "to_runner")
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def read(self, timeout):
        until = time.monotonic() + timeout
        fd = self.proc.stdout.fileno()
        while b"\n" not in self.buffer:
            remaining = until - time.monotonic()
            if remaining <= 0:
                return None
            ready, _, _ = select.select([fd], [], [], remaining)
            if not ready:
                return None
            chunk = os.read(fd, 65536)
            if not chunk:
                raise Failure("runner exited")
            self.buffer += chunk
            if len(self.buffer) > protocol.MAX_LINE_BYTES:
                raise Failure("oversized line")
        line, self.buffer = self.buffer.split(b"\n", 1)
        return protocol.decode(line, "from_runner")

    def init(self, home, manifest, device="cuda", version=protocol.PROTOCOL_VERSION):
        message = {"type": "init", "protocol": version, "home": home, "device": device, "manifest": manifest}
        self.send(raw=(json.dumps(message, separators=(",", ":")) + "\n").encode())  # bypasses local validation on purpose

    def kill(self):
        if self.proc.poll() is None:
            for sig, wait in ((signal.SIGTERM, 3), (signal.SIGKILL, 5)):
                try:
                    os.killpg(self.proc.pid, sig)
                except ProcessLookupError:
                    break
                try:
                    self.proc.wait(wait)
                    break
                except subprocess.TimeoutExpired:
                    continue
        for stream in (self.proc.stdin, self.proc.stdout):
            try:
                stream.close()
            except Exception:
                pass

    def group_gone(self):
        try:
            os.killpg(self.proc.pid, 0)
            return False
        except ProcessLookupError:
            return True


def validate_wav(path, target_seconds=30.0, tolerance=0.25):
    """Same thresholds as audioservice.validation.validate_wav (re-implemented so validation.py isn't uploaded)."""
    with wave.open(path, "rb") as w:
        channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        data = array.array("h")
        data.frombytes(w.readframes(frames))
    problems = []
    if width != 2 or channels not in (1, 2) or rate not in (44100, 48000):
        problems.append("format")
    duration = frames / rate if rate else 0
    if not target_seconds * (1 - tolerance) <= duration <= target_seconds * (1 + tolerance):
        problems.append("duration")
    total = len(data) or 1
    rms = (sum(map(operator.mul, data, data)) / total) ** 0.5
    if rms < 100:
        problems.append("silent")
    clipped = (data.count(32767) + data.count(-32768)) / total
    if clipped > 0.01:
        problems.append("clipped")
    return {"channels": channels, "sample_rate": rate, "frames": frames, "duration_s": round(duration, 3), "rms": round(rms, 1),
            "clipped_fraction": round(clipped, 6), "problems": problems, "passed": not problems}


PROBE = r'''
import importlib, importlib.util, json, os, sys, tempfile
from pathlib import Path
cfg = json.loads(sys.stdin.readline())
spec = importlib.util.spec_from_file_location("musicme_runner", cfg["runner"])
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)          # runs only the runner's module setup (fd isolation, offline env); not main()
sys.path.insert(0, cfg["home"])
ckpt = os.path.join(cfg["home"], "checkpoints")
results = {"offline_env": os.environ.get("HF_HUB_OFFLINE") == "1" and os.environ.get("MPLBACKEND") == "Agg"}
runner.disable_downloads(ckpt)
dl = importlib.import_module("acestep.core.generation.handler.init_service_downloads")
md = importlib.import_module("acestep.model_downloader")
import huggingface_hub

def blocked(fn, *a, **k):
    try:
        fn(*a, **k)
        return False
    except RuntimeError as error:
        return "Auto-download is disabled" in str(error)
    except Exception:
        return False

results["hf_snapshot_download_blocked"] = blocked(huggingface_hub.snapshot_download, "ACE-Step/Ace-Step1.5")
results["hf_hub_download_blocked"] = blocked(huggingface_hub.hf_hub_download, "ACE-Step/Ace-Step1.5", "README.md")
results["ensure_main_model_blocked"] = blocked(dl.ensure_main_model, Path(ckpt)) and blocked(md.ensure_main_model, Path(ckpt))
results["ensure_dit_model_blocked"] = blocked(dl.ensure_dit_model, "acestep-v15-turbo", Path(ckpt))
results["ensure_vae_model_blocked"] = blocked(dl.ensure_vae_model, "vae", Path(ckpt))
results["internal_hf_download_blocked"] = blocked(md._download_from_huggingface_internal, "ACE-Step/Ace-Step1.5", ckpt, None)
try:
    import modelscope
    results["modelscope_blocked"] = blocked(modelscope.snapshot_download, "ACE-Step/Ace-Step1.5")
except ImportError:
    results["modelscope_blocked"] = "not installed"
results["presence_check_accepts_minimum_set"] = dl.check_main_model_exists(Path(ckpt)) is True
empty = tempfile.mkdtemp()
results["presence_check_rejects_empty_dir"] = dl.check_main_model_exists(Path(empty)) is False
mixin = dl.InitServiceDownloadsMixin()
results["precheck_minimum_set_needs_no_download"] = mixin._ensure_models_present(
    checkpoint_path=Path(ckpt), config_path="acestep-v15-turbo", prefer_source=None, vae_variant=None) is None
partial = tempfile.mkdtemp()
for comp, w in (("acestep-v15-turbo", "model.safetensors"), ("Qwen3-Embedding-0.6B", "model.safetensors")):
    os.makedirs(os.path.join(partial, comp)); open(os.path.join(partial, comp, w), "wb").close()
results["precheck_missing_vae_blocked"] = blocked(mixin._ensure_models_present, checkpoint_path=Path(partial),
                                                  config_path="acestep-v15-turbo", prefer_source=None, vae_variant=None)
original = dl.InitServiceDownloadsMixin._ensure_models_present
def changed(self, **kwargs):
    return None
dl.InitServiceDownloadsMixin._ensure_models_present = changed
try:
    runner.disable_downloads(ckpt)
    results["changed_upstream_refused"] = False
except SystemExit as exit:
    results["changed_upstream_refused"] = exit.code == 2
dl.InitServiceDownloadsMixin._ensure_models_present = original
with open(cfg["results"], "w") as f:
    json.dump(results, f)
'''


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", required=True, help="ACE-Step venv interpreter")
    parser.add_argument("--home", required=True, help="pinned ACE-Step checkout (weights in <home>/checkpoints)")
    parser.add_argument("--runner", default=os.path.join(_SERVICE_ROOT, "audioservice", "acestep", "runner.py"))
    parser.add_argument("--out", required=True, help="private output folder for report.json and the WAV")
    parser.add_argument("--skip", default="", help="comma-separated sections to skip (local fake-runner tests only)")
    parser.add_argument("--startup-timeout", type=float, default=900)
    parser.add_argument("--render-timeout", type=float, default=900)
    args = parser.parse_args(argv)
    skip = {s for s in args.skip.split(",") if s}
    os.makedirs(args.out, mode=0o700, exist_ok=True)
    home, manifest = os.path.realpath(args.home), pinned.load()
    checks, report = [], {"started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "lyrics_sha256":
                          hashlib.sha256(LYRICS.encode()).hexdigest(), "uploaded_modules": {}}
    for name in ("__init__.py", "manifest.json", "manifest.py", "protocol.py", "fp16_fix.py", "runner.py"):
        report["uploaded_modules"][name] = pinned.sha256_file(os.path.join(_SERVICE_ROOT, "audioservice", "acestep", name))

    def check(section, name, ok, detail=None):
        checks.append({"section": section, "check": name, "passed": bool(ok), "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'}  [{section}] {name}" + (f"  ({detail})" if detail not in (None, "") else ""), flush=True)

    def fatal_code(runner, timeout):
        try:
            message = runner.read(timeout)
        except (Failure, protocol.ProtocolError):
            return None
        return message.get("code") if message and message["type"] == "fatal" else None

    # 1. download blocking against the real ACE-Step modules ---------------------------------------------------
    if "probe" not in skip:
        results_path = os.path.join(args.out, "probe.json")
        probe_path = os.path.join(args.out, "probe.py")
        with open(probe_path, "w") as f:
            f.write(PROBE)
        env = {k: os.environ[k] for k in RUNNER_ENV_ALLOWLIST if k in os.environ}
        env.update(RUNNER_ENV_FORCED)
        proc = subprocess.run([args.python, "-I", probe_path], input=json.dumps(
            {"runner": args.runner, "home": home, "results": results_path}) + "\n", text=True, env=env, cwd=home,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
        probe = json.load(open(results_path)) if os.path.exists(results_path) else {}
        check("probe", "probe ran", proc.returncode == 0 and probe, f"exit {proc.returncode}")
        for key, value in probe.items():
            check("probe", key, value is True or value == "not installed", value if value is not True else None)

    # 2. negative startups (cheap ones fail before any weight is read) ----------------------------------------
    if "negative" not in skip:
        cases = [("tampered manifest refused", dict(manifest=dict(manifest, code=dict(manifest["code"], commit="0" * 40))), "MANIFEST_MISMATCH"),
                 ("non-cuda device refused", dict(device="cpu"), "INVALID_MODEL_CONFIG"),
                 ("wrong protocol version refused", dict(version=99), "PROTOCOL")]
        for name, kwargs, code in cases:
            runner = Runner(args.python, args.runner, home)
            try:
                runner.init(home, kwargs.get("manifest", manifest), kwargs.get("device", "cuda"),
                            kwargs.get("version", protocol.PROTOCOL_VERSION))
                got = fatal_code(runner, 120)
                runner.proc.wait(30)
                check("negative", name, got == code and runner.proc.returncode == 2, got)
            finally:
                runner.kill()
        runner = Runner(args.python, args.runner, home)
        try:
            runner.send(raw=b"{this is not json\n")
            got = fatal_code(runner, 120)
            runner.proc.wait(30)
            check("negative", "garbage input refused", got == "PROTOCOL" and runner.proc.returncode == 2, got)
        finally:
            runner.kill()
        lm_dir = os.path.join(home, "checkpoints", "acestep-5Hz-lm-1.7B")
        created = not os.path.exists(lm_dir)
        if created:
            os.makedirs(lm_dir)
        runner = Runner(args.python, args.runner, home)
        try:
            runner.init(home, manifest)
            got = fatal_code(runner, args.startup_timeout)
            check("negative", "LM planning weights present refused", got == "FORBIDDEN_WEIGHTS", got)
        finally:
            runner.kill()
            if created:
                os.rmdir(lm_dir)  # the empty folder this test created; never anything else

    # 3. real startup, handshake and float16 fix ----------------------------------------------------------------
    runner = Runner(args.python, args.runner, home)
    try:
        started = time.monotonic()
        runner.init(home, manifest)
        hello = runner.read(args.startup_timeout)
        report["startup_seconds"] = round(time.monotonic() - started, 1)
        ok = bool(hello) and hello["type"] == "hello"
        check("startup", "runner started and sent hello", ok, None if ok else (hello or {}).get("code", "timeout"))
        if not ok:
            raise Failure("startup failed")
        report["hello"] = {k: hello.get(k) for k in ("code_commit", "weights_revision", "config_path", "dtype", "fp16_fix",
                                                     "selftest", "torch", "gpu", "load_seconds", "python")}
        try:
            pinned.check_hello(hello, manifest)
            check("startup", "handshake matches the pinned manifest (service-side check)", True)
        except pinned.ManifestError as error:
            check("startup", "handshake matches the pinned manifest (service-side check)", False, error.code)
        check("startup", "pinned commit", hello["code_commit"] == manifest["code"]["commit"])
        check("startup", "all pinned file hashes verified", hello["weights_sha256"] == manifest["weights"]["files"],
              f"{len(hello['weights_sha256'])} files")
        check("startup", "turbo DiT configuration loaded", hello["config_path"] == "acestep-v15-turbo")
        if hello["dtype"] == "torch.float16":
            selftest = hello.get("selftest") or {}
            check("startup", "float16 lyric-encoder fix applied", hello["fp16_fix"] == "applied")
            check("startup", "GPU self-test passed (all sub-checks)", selftest.get("passed") is True and all(selftest.values()),
                  {k: v for k, v in selftest.items() if v is not True} or None)
        else:
            check("startup", "non-float16 dtype needs no fix", hello["fp16_fix"] == "not_needed", hello["dtype"])

        # 4. parameter and output-path rejection (no generation) -------------------------------------------------
        if "params" not in skip:
            base = {"caption": CAPTION, "lyrics": LYRICS, "instrumental": False, "vocal_language": "en", "bpm": 96,
                    "duration": 30, "seed": 20261021, "inference_steps": 8, "timesignature": "4"}
            out_dir = os.path.join(args.out, "staging")
            os.makedirs(out_dir, mode=0o700, exist_ok=True)
            for name, params, path in [("too-short duration rejected", dict(base, duration=5), ".tmp-a"),
                                       ("unpinned step count rejected", dict(base, inference_steps=50), ".tmp-b"),
                                       ("unsupported language rejected", dict(base, vocal_language="yo"), ".tmp-c"),
                                       ("over-long lyrics rejected", dict(base, lyrics="la " * 1400), ".tmp-d"),
                                       ("output path outside .tmp- naming rejected", base, "final.wav")]:
                runner.send({"type": "render", "id": hashlib.sha256(name.encode()).hexdigest()[:32], "params": params,
                             "out_path": os.path.join(out_dir, path)})
                reply = runner.read(60)
                while reply and reply["type"] == "progress":
                    reply = runner.read(60)
                check("params", name, bool(reply) and reply["type"] == "error" and reply["code"] == "INVALID_PARAMS"
                      and not os.path.exists(os.path.join(out_dir, path)), (reply or {}).get("code"))
            check("params", "runner still alive after rejections", runner.proc.poll() is None)

        # 5. one real render with the Phase 2.3E settings --------------------------------------------------------
        if "render" not in skip:
            out_dir = os.path.join(args.out, "staging")
            os.makedirs(out_dir, mode=0o700, exist_ok=True)
            temp = os.path.join(out_dir, ".tmp-render")
            params = {"caption": CAPTION, "lyrics": LYRICS, "instrumental": False, "vocal_language": "en", "bpm": 96,
                      "duration": 30, "seed": 20261021, "inference_steps": 8, "timesignature": "4"}
            started, progress = time.monotonic(), 0
            runner.send({"type": "render", "id": "render-0001", "params": params, "out_path": temp})
            reply = runner.read(args.render_timeout)
            while reply and reply["type"] == "progress":
                progress += 1
                reply = runner.read(args.render_timeout)
            report["render_wall_seconds"] = round(time.monotonic() - started, 1)
            ok = bool(reply) and reply["type"] == "result"
            check("render", "render completed", ok, None if ok else (reply or {}).get("code", "timeout"))
            if ok:
                report["render"] = {k: reply.get(k) for k in ("sample_rate", "channels", "frames", "clipped_before_pcm",
                                                              "render_ms", "peak_vram_gb", "dtype", "diagnostics")}
                diagnostics = reply.get("diagnostics") or {}
                check("render", "lyric encoder output finite", diagnostics.get("lyric_encoder_output_finite") is True,
                      f"tail max |x| = {diagnostics.get('lyric_tail_max_abs')}")
                check("render", "output written as a regular file (not a link)", os.path.isfile(temp) and not os.path.islink(temp))
                final = os.path.join(args.out, "smoke-2-3f-20261021.wav")
                os.replace(temp, final)
                os.chmod(final, 0o400)
                validation = validate_wav(final)
                report["wav_validation"] = validation
                report["wav_sha256"] = pinned.sha256_file(final)
                report["same_as_phase_2_3e"] = report["wav_sha256"] == PHASE_2_3E_SHA256  # informational
                check("render", "WAV passes validation (format, 22.5-37.5 s, not silent, <=1% clipped)",
                      validation["passed"], validation["problems"] or None)
                check("render", "runner header matches the file", reply["frames"] == validation["frames"]
                      and reply["channels"] == validation["channels"] and reply["sample_rate"] == validation["sample_rate"])

        # 6. kill mid-render -------------------------------------------------------------------------------------
        if "kill" not in skip:
            temp = os.path.join(args.out, "staging", ".tmp-killed")
            runner.send({"type": "render", "id": "render-0002", "params": {
                "caption": CAPTION, "lyrics": LYRICS, "instrumental": False, "vocal_language": "en", "bpm": 96,
                "duration": 30, "seed": 20261022, "inference_steps": 8, "timesignature": "4"}, "out_path": temp})
            reply = runner.read(60)
            check("kill", "render under way before the kill", bool(reply) and reply["type"] in ("progress", "result"))
            runner.kill()
            time.sleep(1)
            check("kill", "runner process group terminated", runner.group_gone())
            if os.path.lexists(temp):
                os.remove(temp)
            check("kill", "no partial output left behind", not os.path.lexists(temp))
    except Failure as error:
        check("startup", "runner session", False, str(error))
    finally:
        runner.kill()

    # 7. restart after the kill, then clean shutdown ------------------------------------------------------------
    if "restart" not in skip:
        runner = Runner(args.python, args.runner, home)
        try:
            runner.init(home, manifest)
            hello = runner.read(args.startup_timeout)
            ok = bool(hello) and hello["type"] == "hello"
            if ok:
                try:
                    pinned.check_hello(hello, manifest)
                except pinned.ManifestError:
                    ok = False
            check("restart", "fresh runner re-verified and loaded after the kill", ok)
            runner.send({"type": "shutdown"})
            runner.proc.wait(60)
            check("restart", "clean shutdown", runner.proc.returncode == 0, runner.proc.returncode)
        except (Failure, subprocess.TimeoutExpired) as error:
            check("restart", "restart and shutdown", False, type(error).__name__)
        finally:
            runner.kill()

    shutil.rmtree(os.path.join(args.out, "staging"), ignore_errors=True)
    report["checks"] = checks
    report["passed"] = all(c["passed"] for c in checks) and bool(checks)
    report["real_audio"] = None  # set only by local validation on the Mac
    with open(os.path.join(args.out, "report.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(f"\n{sum(c['passed'] for c in checks)}/{len(checks)} checks passed. Overall: {'PASS' if report['passed'] else 'FAIL'}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
