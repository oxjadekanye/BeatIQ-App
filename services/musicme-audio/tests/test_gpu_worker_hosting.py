"""GPU worker hosting: the real AceStepEngine + runner protocol behind the worker, shutdown, idle exit, output TTLs,
weights preparation and the provider deployment files (static: nothing is built, deployed or downloaded)."""
import ast
import json
import os
import re
import stat
import sys
import threading
import time
import types

import pytest

from conftest import A, body, settings
from fake_worker import TOKEN
from test_acestep_adapter import make_runner
from audioservice.acestep import manifest as pinned
from audioservice.cost_guard import CostLedger
from audioservice.gpu_worker import weights as worker_weights
from audioservice.gpu_worker.server import GpuWorker, WorkerSettings, load_settings
from audioservice.worker_client import WorkerClient, WorkerError

SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPLOY = os.path.join(SERVICE_ROOT, "deploy")


def _params(duration=10):
    return {"caption": "Afro-Soul, Romantic, male vocal, 84 bpm", "lyrics": "[Verse]\nHello\n\n[Chorus]\nWorld",
            "instrumental": False, "vocal_language": "en", "bpm": 84, "duration": duration, "seed": 1234,
            "inference_steps": 8, "timesignature": "4"}


@pytest.fixture
def real_worker(tmp_path):
    """The worker with the real AceStepEngine driving the fake runner over the validated JSON-lines protocol."""
    started = []

    def make(mode="ok", **overrides):
        runner, record = make_runner(tmp_path, mode)
        home = tmp_path / "acestep-home"
        home.mkdir(exist_ok=True)
        values = dict(token=TOKEN, port=0, data_dir=str(tmp_path / "worker-data"), acestep_python=sys.executable,
                      acestep_home=str(home), acestep_runner=runner, acestep_startup_timeout_seconds=10,
                      max_job_seconds=60, output_ttl_seconds=60, record_ttl_seconds=120)
        values.update(overrides)
        worker = GpuWorker(WorkerSettings(**values))
        server = worker.serve()
        threading.Thread(target=server.serve_forever, daemon=True).start()
        started.append(worker)
        deadline = time.monotonic() + 10
        while worker.state == "starting" and time.monotonic() < deadline:
            time.sleep(0.02)
        return worker, WorkerClient(f"http://127.0.0.1:{server.server_address[1]}", TOKEN), record

    yield make
    for worker in started:
        worker.shutdown()


def _wait(client, job_id, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.status(job_id)
        if job["state"] in ("succeeded", "failed", "cancelled"):
            return job
        time.sleep(0.05)
    raise AssertionError("job did not finish")


def test_worker_drives_the_validated_runner_protocol_and_serves_a_verified_wav(real_worker, tmp_path):
    worker, client, record = real_worker("ok")
    assert worker.state == "ready"
    hello = client.hello()
    pinned.check_hello(dict(hello, type="hello"), pinned.load())
    assert hello["dtype"] == "torch.float16" and hello["fp16_fix"] == "applied"
    job_id = "1" * 32
    client.submit(job_id, _params(), 30)
    job = _wait(client, job_id)
    assert job["state"] == "succeeded", job
    sent = json.load(open(record / "render-1.json"))
    assert sent["params_keys"] == sorted(_params()) and sent["out_dir"] == worker.outputs
    assert stat.S_IMODE(os.stat(worker.outputs).st_mode) == 0o700
    out = str(tmp_path / ".tmp-downloaded")
    client.download(job_id, out, job["result"]["sha256"], job["result"]["size_bytes"], 64 << 20)
    assert open(out, "rb").read(4) == b"RIFF"
    client.delete(job_id)
    assert os.listdir(worker.outputs) == []


def test_runner_errors_pass_through_as_codes(real_worker):
    _, client, _ = real_worker("nan")
    job_id = "2" * 32
    client.submit(job_id, _params(), 30)
    assert _wait(client, job_id)["error"] == {"code": "NAN_LATENTS"}


def test_shutdown_cancels_the_render_kills_the_runner_wipes_outputs_and_refuses_work(real_worker):
    worker, client, record = real_worker("hang")
    job_id = "3" * 32
    client.submit(job_id, _params(), 60)
    deadline = time.monotonic() + 10
    while not (record / "pid.json").exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    pid = json.load(open(record / "pid.json"))["pid"]
    worker.shutdown()
    assert worker.jobs[job_id].state == "cancelled"
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)
    assert not os.path.exists(worker.outputs)
    assert worker.submit("4" * 32, _params(), 30)[0] == 503


def test_bounded_queue_refuses_with_retry_after(real_worker):
    worker, client, _ = real_worker("hang", max_queue=0)
    client.submit("5" * 32, _params(), 60)
    with pytest.raises(WorkerError) as busy:
        client.submit("6" * 32, _params(), 60)
    assert busy.value.code == "WORKER_BUSY" and busy.value.retry_after == 10


def test_output_ttl_record_ttl_and_idle_exit_with_a_fake_clock(tmp_path):
    now = [1000.0]

    class Engine:
        hello = {"dtype": "torch.float16", "fp16_fix": "applied"}

        def close(self):
            pass

    worker = GpuWorker(WorkerSettings(token=TOKEN, data_dir=str(tmp_path), output_ttl_seconds=60,
                                      record_ttl_seconds=120, idle_exit_seconds=900),
                       engine_factory=Engine, clock=lambda: now[0])
    worker.submit("7" * 32, _params(), 30)
    job = worker.jobs["7" * 32]
    job.out_path = os.path.join(worker.outputs, ".tmp-x")
    open(job.out_path, "wb").write(b"RIFF" + b"\0" * 100)
    worker.queue.clear()
    worker._finish(job, "succeeded")
    now[0] += 61
    assert worker.maintain_once() is False and job.out_path is None and "7" * 32 in worker.jobs
    now[0] += 60
    worker.maintain_once()
    assert "7" * 32 not in worker.jobs
    now[0] += 900
    assert worker.maintain_once() is True


def test_worker_settings_refuse_weak_tokens_and_shared_token_files(tmp_path):
    with pytest.raises(ValueError):
        WorkerSettings(token="short")
    loose = tmp_path / "token"
    loose.write_text(TOKEN)
    os.chmod(loose, 0o644)
    with pytest.raises(ValueError, match="private"):
        load_settings({"MUSICME_WORKER_TOKEN_FILE": str(loose)})
    os.chmod(loose, 0o600)
    s = load_settings({"MUSICME_WORKER_TOKEN_FILE": str(loose)})
    assert s.host == "127.0.0.1" and TOKEN not in repr(s)


def test_weights_are_verified_downloaded_only_when_needed_and_refused_on_mismatch(tmp_path, monkeypatch):
    manifest = pinned.load()
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setattr(pinned, "read_git_head", lambda h: manifest["code"]["commit"])
    files = {rel: f"content-{rel}".encode() for rel in manifest["weights"]["files"]}
    fake = dict(manifest, weights=dict(manifest["weights"], files={rel: __import__("hashlib").sha256(data).hexdigest()
                                                                    for rel, data in files.items()}))
    calls = []

    def downloader(checkpoints, m):
        calls.append(checkpoints)
        for rel, data in files.items():
            os.makedirs(os.path.dirname(os.path.join(checkpoints, rel)), exist_ok=True)
            open(os.path.join(checkpoints, rel), "wb").write(data)

    checkpoints = str(tmp_path / "ckpt")
    with pytest.raises(pinned.ManifestError):
        worker_weights.prepare(str(home), checkpoints, verify_only=True, manifest=fake, downloader=downloader)
    assert len(worker_weights.prepare(str(home), checkpoints, manifest=fake, downloader=downloader)) == len(files)
    worker_weights.prepare(str(home), checkpoints, manifest=fake, downloader=downloader)
    assert len(calls) == 1  # already verified: no second download
    first = next(iter(files))
    open(os.path.join(checkpoints, first), "wb").write(b"tampered")
    calls.clear()
    worker_weights.prepare(str(home), checkpoints, manifest=fake, downloader=downloader)
    assert calls  # repaired from the pinned revision, then re-verified
    monkeypatch.setattr(pinned, "read_git_head", lambda h: "0" * 40)
    with pytest.raises(pinned.ManifestError, match="CODE_MISMATCH"):
        worker_weights.prepare(str(home), checkpoints, manifest=fake, downloader=downloader)


# ---- deployment files (static) -------------------------------------------------------------------------------

def _modal_constants():
    tree = ast.parse(open(os.path.join(DEPLOY, "modal", "musicme_worker.py")).read())
    return {t.id: n.value.value for n in tree.body if isinstance(n, ast.Assign) for t in n.targets
            if isinstance(t, ast.Name) and isinstance(n.value, ast.Constant)}, tree


def _load_modal_adapter_as_container(tmp_path, monkeypatch):
    """Execute the adapter as Modal does: uploaded alone at /root, with sources mounted at /app."""
    fake_app = tmp_path / "app"
    (fake_app / "audioservice" / "acestep").mkdir(parents=True)
    (fake_app / "deploy" / "worker").mkdir(parents=True)
    (fake_app / "audioservice" / "acestep" / "manifest.json").write_text(
        open(os.path.join(SERVICE_ROOT, "audioservice", "acestep", "manifest.json")).read()
    )
    (fake_app / "deploy" / "worker" / "requirements-gpu.txt").write_text("test-pin==1.0\n")

    real_path = type(__import__("pathlib").Path())

    def container_path(value):
        value = os.fspath(value)
        if value == "/app" or value.startswith("/app/"):
            return real_path(str(fake_app) + value[len("/app"):])
        return real_path(value)

    class Builder:
        def __init__(self):
            self.calls = []

        def __getattr__(self, name):
            def call(*args, **kwargs):
                self.calls.append((name, args, kwargs))
                return self
            return call

    class App:
        def __init__(self, *args, **kwargs):
            pass

        def function(self, **kwargs):
            return lambda target: target

        def cls(self, **kwargs):
            return lambda target: target

    class Volume:
        @staticmethod
        def from_name(*args, **kwargs):
            return Builder()

    class Secret:
        @staticmethod
        def from_name(*args, **kwargs):
            return object()

    fake_modal = types.ModuleType("modal")
    fake_modal.App = App
    fake_modal.Volume = Volume
    fake_modal.Secret = Secret
    fake_modal.Image = types.SimpleNamespace(debian_slim=lambda **kwargs: Builder())
    fake_modal.is_local = lambda: False
    fake_modal.concurrent = lambda **kwargs: (lambda target: target)
    fake_modal.web_server = lambda **kwargs: (lambda target: target)
    fake_config_module = types.ModuleType("modal.config")
    fake_config_module.config = {"environment": "musicme-dev"}

    source = open(os.path.join(DEPLOY, "modal", "musicme_worker.py")).read()
    module = types.ModuleType("container_musicme_worker")
    module.__file__ = "/root/musicme_worker.py"
    with monkeypatch.context() as patch:
        patch.setattr(__import__("pathlib"), "Path", container_path)
        patch.setitem(sys.modules, "modal", fake_modal)
        patch.setitem(sys.modules, "modal.config", fake_config_module)
        patch.setenv("MODAL_ENVIRONMENT", "musicme-dev")
        exec(compile(source, module.__file__, "exec"), module.__dict__)
    return module, fake_app


def test_modal_adapter_has_scale_to_zero_one_t4_proxy_auth_and_no_secrets():
    constants, tree = _modal_constants()
    assert constants["GPU"] == "T4" and constants["MIN_CONTAINERS"] == 0 and constants["BUFFER_CONTAINERS"] == 0
    assert constants["MAX_CONTAINERS"] == 1 and constants["SCALEDOWN_WINDOW_SECONDS"] <= 300
    assert constants["CONTAINER_TIMEOUT_SECONDS"] <= 1800 and constants["MAX_CONCURRENT_REQUESTS"] <= 16
    source = open(os.path.join(DEPLOY, "modal", "musicme_worker.py")).read()
    for needle in ("requires_proxy_auth=True", "min_containers=MIN_CONTAINERS", "max_containers=MAX_CONTAINERS",
                   "buffer_containers=BUFFER_CONTAINERS", "scaledown_window=SCALEDOWN_WINDOW_SECONDS",
                   "timeout=CONTAINER_TIMEOUT_SECONDS", "retries=0", "environment_name=MODAL_ENVIRONMENT",
                   "weights.with_mount_options(read_only=True)", 'python_version="3.12.12"',
                   '"MUSICME_WORKER_IDLE_EXIT_SECONDS": "0"',
                   "audioservice.gpu_worker.server"):
        assert needle in source, needle
    assert constants["MODAL_ENVIRONMENT"] == "musicme-dev"
    assert 'tags={"project": "beatiq-musicme", "environment": "samsung-dev"}' in source
    assert "volumes={CHECKPOINTS: read_only_weights}" in source
    assert 'os.environ.get("MODAL_ENVIRONMENT")' in source
    assert "modal.is_local()" in source
    assert "use --env musicme-dev" in source
    prepare = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "prepare_weights")
    assert "gpu" not in ast.dump(prepare.decorator_list[0])  # weights preparation is CPU-only
    assert not re.search(r"(hf_[A-Za-z0-9]{20,}|ak-[A-Za-z0-9]{10,}|as-[A-Za-z0-9]{10,})", source)


def test_modal_adapter_loads_from_root_and_cpu_prepare_uses_container_paths(tmp_path, monkeypatch):
    adapter, fake_app = _load_modal_adapter_as_container(tmp_path, monkeypatch)
    assert adapter.__file__ == "/root/musicme_worker.py"
    assert adapter.SOURCE_ROOT == fake_app
    assert adapter.CODE == pinned.load()["code"]
    image_calls = {name: (args, kwargs) for name, args, kwargs in adapter.image.calls}
    assert image_calls["pip_install_from_requirements"][0] == (
        str(fake_app / "deploy" / "worker" / "requirements-gpu.txt"),
    )
    assert image_calls["add_local_file"][0][1] == "/app/deploy/worker/requirements-gpu.txt"
    assert image_calls["add_local_dir"][0][1] == "/app/audioservice"

    calls = []
    monkeypatch.setattr(adapter.subprocess, "run", lambda *args, **kwargs: calls.append((args, kwargs)))
    adapter.prepare_weights()
    command = calls[0][0][0]
    assert command[:5] == [sys.executable, "-s", "-B", "-m", "audioservice.gpu_worker.weights"]
    assert command[5:] == ["--home", "/opt/acestep", "--checkpoints", "/data/checkpoints"]
    assert calls[0][1] == {"check": True, "cwd": "/app"}
    assert any(call[0] == "commit" for call in adapter.weights.calls)


def test_container_image_pins_match_the_manifest_and_bake_in_no_secrets():
    code = pinned.load()["code"]
    dockerfile = open(os.path.join(DEPLOY, "worker", "Dockerfile")).read()
    assert f"ARG ACESTEP_COMMIT={code['commit']}" in dockerfile and f"ARG ACESTEP_TAG={code['tag']}" in dockerfile
    assert f"ARG ACESTEP_REPO={code['repository']}.git" in dockerfile and "USER worker" in dockerfile
    assert "MUSICME_WORKER_HOST=127.0.0.1" in dockerfile and "TOKEN=" not in dockerfile
    entrypoint = open(os.path.join(DEPLOY, "worker", "entrypoint.sh")).read()
    assert entrypoint.index("gpu_worker.weights") < entrypoint.index("gpu_worker.server")
    pins = [l for l in open(os.path.join(DEPLOY, "worker", "requirements-gpu.txt")).read().splitlines()
            if l and not l.startswith("#")]
    assert "torch==2.10.0+cu128" in pins and "gradio==6.2.0" in pins and "huggingface-hub==0.36.0" in pins
    assert all("==" in p or p == "--extra-index-url https://download.pytorch.org/whl/cu128" for p in pins)
    assert not [p for p in pins if p.startswith(("flash-attn", "nano-vllm"))]


def test_cost_ledger_resets_each_utc_day_and_persists_privately(tmp_path):
    clock = [1_800_000_000]
    path = tmp_path / "ledger" / "gpu.json"
    ledger = CostLedger(str(path), 0.82, 0.25, 5, 420, 120, clock=lambda: clock[0])
    reserved = ledger.reserve()
    ledger.settle(reserved, 30)
    assert ledger.snapshot()["usd"] == pytest.approx(150 * 0.82 / 3600, abs=1e-6)
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    ledger.reserve()  # second render's reservation
    with pytest.raises(Exception):
        ledger.reserve()  # 0.034 settled + 0.123 + 0.123 > 0.25
    clock[0] += 86_400
    assert ledger.snapshot()["renders"] == 0
    ledger.reserve()
