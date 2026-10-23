"""Provider-neutral GPU worker integration tests. No GPU, provider account or network is used."""
import dataclasses
import json
import logging
import os
import stat
import threading
import time
import urllib.error
import urllib.request

import pytest

from conftest import A, LYRIC_LINE, SECRET, body, settings
from fake_worker import TOKEN, FakeWorker
from audioservice.acestep import manifest as pinned
from audioservice.cost_guard import CostLedger, GpuBudgetExceeded
from audioservice.engines import Cancelled, EngineError, EngineUnavailable, RemoteWorkerEngine
from audioservice.worker_client import WorkerClient, WorkerError, load_credentials, parse_credentials, validate_url


@pytest.fixture
def fake_worker():
    workers = []

    def make(mode="ok", **kwargs):
        worker = FakeWorker(mode, **kwargs).ready()
        workers.append(worker)
        return worker

    yield make
    for worker in workers:
        worker.close()


def worker_settings(tmp_path, url, **overrides):
    credentials = tmp_path / "worker.json"
    credentials.write_text(json.dumps({"token": TOKEN}))
    os.chmod(credentials, 0o600)
    values = dict(engine="remote_gpu", workers=1, worker_url=url, worker_credentials_file=str(credentials),
                  worker_startup_timeout_seconds=10, render_timeout_seconds=20,
                  gpu_ledger_file=str(tmp_path / "ledger.json"), gpu_price_per_hour_usd=0.6,
                  gpu_daily_budget_usd=2, gpu_max_renders_per_day=10, gpu_reserve_seconds=30,
                  gpu_idle_tail_seconds=0)
    values.update(overrides)
    return settings(**values)


def wait_job(client, job_id, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.status(job_id)
        if job["state"] in ("succeeded", "failed", "cancelled"):
            return job
        time.sleep(0.02)
    raise AssertionError("worker job did not finish")


def test_configuration_url_and_credentials_fail_closed(tmp_path, fake_worker):
    worker = fake_worker()
    valid = worker_settings(tmp_path, worker.url)
    with pytest.raises(ValueError, match="Production generation is disabled"):
        dataclasses.replace(valid, environment="production")
    with pytest.raises(ValueError, match="loopback"):
        dataclasses.replace(valid, host="0.0.0.0")
    with pytest.raises(ValueError, match="one worker"):
        dataclasses.replace(valid, workers=2)
    assert validate_url("https://team--musicme.modal.run/", (".modal.run",), False).endswith("modal.run")
    assert validate_url("https://pod-8080.proxy.runpod.net", (".proxy.runpod.net",), False).startswith("https")
    for bad in ("http://team.modal.run", "https://modal.run.evil.test", "https://user:pass@x.modal.run"):
        with pytest.raises(WorkerError):
            validate_url(bad, (".modal.run",), False)
    loose = tmp_path / "loose.token"
    loose.write_text(TOKEN)
    os.chmod(loose, 0o644)
    with pytest.raises(WorkerError, match="WORKER_CREDENTIALS_NOT_PRIVATE"):
        load_credentials(str(loose))
    with pytest.raises(WorkerError, match="WORKER_CREDENTIALS_INVALID"):
        parse_credentials(json.dumps({"token": TOKEN, "headers": {"Authorization": "replaced"}}))
    with pytest.raises(WorkerError, match="WORKER_CREDENTIALS_INVALID"):
        parse_credentials("x" * (16 * 1024 + 1))


def test_worker_auth_body_limit_idempotency_and_job_ownership(tmp_path, fake_worker):
    worker = fake_worker()
    good = WorkerClient(worker.url, TOKEN)
    bad = WorkerClient(worker.url, "wrong-worker-token-at-least-thirty-two-chars")
    with pytest.raises(WorkerError, match="WORKER_UNAUTHORIZED"):
        bad.hello()
    job_id = "a" * 32
    params = _params()
    first = good.submit(job_id, params, 30)
    repeated = good.submit(job_id, params, 30)
    assert first["job_id"] == repeated["job_id"] == job_id
    assert repeated["state"] in ("queued", "running", "succeeded")
    with pytest.raises(WorkerError, match="WORKER_CONFLICT"):
        good.submit(job_id, dict(params, seed=999), 30)
    with pytest.raises(WorkerError, match="WORKER_UNAUTHORIZED"):
        bad.status(job_id)
    request = urllib.request.Request(worker.url + "/v1/jobs", data=b"x" * (64 * 1024 + 1), method="POST",
                                     headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    with pytest.raises(urllib.error.HTTPError) as oversized:
        urllib.request.urlopen(request, timeout=2)
    assert oversized.value.code == 400


def test_success_progress_private_wav_and_cleanup(tmp_path, fake_worker):
    worker = fake_worker("slow")
    client = WorkerClient(worker.url, TOKEN)
    hello = client.hello()
    pinned.check_hello(dict(hello, type="hello"), pinned.load())
    job_id = "b" * 32
    client.submit(job_id, _params(), 30)
    observed = []
    while True:
        job = client.status(job_id)
        observed.append(job["progress"])
        if job["state"] in ("succeeded", "failed", "cancelled"):
            break
        time.sleep(0.03)
    assert job["state"] == "succeeded", job
    assert observed == sorted(observed) and any(0 < value < 1 for value in observed) and observed[-1] == 1
    path = str(tmp_path / ".tmp-song")
    result = job["result"]
    client.download(job_id, path, result["sha256"], result["size_bytes"], 64 * 1024 * 1024)
    assert open(path, "rb").read(4) == b"RIFF" and stat.S_IMODE(os.stat(path).st_mode) == 0o600
    client.delete(job_id)
    with pytest.raises(WorkerError, match="WORKER_NOT_FOUND"):
        client.status(job_id)


@pytest.mark.parametrize("mode, code", [("nan", "NAN_LATENTS"), ("oom", "CUDA_OOM"),
                                         ("silent", "AUDIO_SILENT"), ("short", "AUDIO_DURATION"),
                                         ("garbage", "AUDIO_INVALID")])
def test_worker_reports_safe_errors_and_never_serves_bad_output(tmp_path, fake_worker, mode, code):
    worker = fake_worker(mode)
    client = WorkerClient(worker.url, TOKEN)
    job_id = (mode[0] * 32).replace("g", "f").replace("n", "e").replace("o", "d").replace("s", "c")
    job = wait_job(client, client.submit(job_id, _params(), 30)["job_id"])
    assert job["state"] == "failed" and job["error"] == {"code": code} and job["result"] is None
    with pytest.raises(WorkerError, match="WORKER_NOT_FOUND"):
        client.download(job_id, str(tmp_path / ".tmp-refused"), "0" * 64, 45, 100)


def test_cancellation_and_deadline_are_bounded(fake_worker):
    worker = fake_worker("hang")
    client = WorkerClient(worker.url, TOKEN)
    cancelled_id = "c" * 32
    client.submit(cancelled_id, _params(), 30)
    deadline = time.monotonic() + 2
    while client.status(cancelled_id)["state"] == "queued" and time.monotonic() < deadline:
        time.sleep(0.01)
    client.cancel(cancelled_id)
    assert wait_job(client, cancelled_id, 4)["state"] == "cancelled"

    timed_id = "d" * 32
    started = time.monotonic()
    client.submit(timed_id, _params(), 10)
    timed = wait_job(client, timed_id, 12)
    assert timed["state"] == "failed" and timed["error"]["code"] == "TIMEOUT"
    assert time.monotonic() - started < 12


def test_shutdown_during_cold_start_never_submits_a_paid_job(tmp_path, fake_worker):
    worker = fake_worker("ok")
    base = WorkerClient(worker.url, TOKEN)

    class BlockingHelloClient:
        def __init__(self):
            self.started, self.release = threading.Event(), threading.Event()
            self.submitted = False

        def hello(self, **kwargs):
            self.started.set()
            assert self.release.wait(3)
            return base.hello(**kwargs)

        def submit(self, *args, **kwargs):
            self.submitted = True
            return base.submit(*args, **kwargs)

        def cancel(self, *args, **kwargs):
            return base.cancel(*args, **kwargs)

    client = BlockingHelloClient()
    engine = RemoteWorkerEngine(worker_settings(tmp_path, worker.url), client=client)
    failures = []

    def render():
        try:
            engine.render(_request(), str(tmp_path / ".tmp-shutdown"), lambda _: True, time.monotonic() + 5)
        except Exception as error:
            failures.append(error)

    thread = threading.Thread(target=render)
    thread.start()
    assert client.started.wait(2)
    engine.close()
    client.release.set()
    thread.join(5)
    assert not thread.is_alive() and len(failures) == 1 and isinstance(failures[0], Cancelled)
    assert client.submitted is False


class RestartingClient:
    """Routes to a fresh fake provider once, as if the original serverless container disappeared."""
    def __init__(self, first, second):
        self.current = WorkerClient(first.url, TOKEN)
        self.second = second
        self.restarted = False

    def hello(self, **kwargs):
        return self.current.hello(**kwargs)

    def submit(self, *args, **kwargs):
        return self.current.submit(*args, **kwargs)

    def status(self, *args, **kwargs):
        if not self.restarted:
            self.restarted = True
            self.current = WorkerClient(self.second.url, TOKEN)
            raise WorkerError("WORKER_NOT_FOUND")
        return self.current.status(*args, **kwargs)

    def cancel(self, *args, **kwargs):
        return self.current.cancel(*args, **kwargs)

    def download(self, *args, **kwargs):
        return self.current.download(*args, **kwargs)

    def delete(self, *args, **kwargs):
        return self.current.delete(*args, **kwargs)


def test_remote_engine_recovers_once_from_worker_restart(tmp_path, fake_worker):
    first, second = fake_worker("slow"), fake_worker("ok")
    s = worker_settings(tmp_path, first.url)
    engine = RemoteWorkerEngine(s, client=RestartingClient(first, second))
    engine.POLL_SECONDS = 0.01
    output = str(tmp_path / ".tmp-restart")
    result = engine.render(_request(), output, lambda _: True, time.monotonic() + 10)
    assert result.sample_rate == 48000 and os.path.getsize(output) > 44
    assert second.engine.calls and second.engine.calls[0]["inference_steps"] == 8


def test_remote_service_end_to_end_security_and_cost_limit(tmp_path, fake_worker, make_service, caplog):
    caplog.set_level(logging.INFO)
    worker = fake_worker("ok")
    s = worker_settings(tmp_path, worker.url, gpu_daily_budget_usd=0.01, gpu_max_renders_per_day=1)
    client, service = make_service(**{field.name: getattr(s, field.name) for field in dataclasses.fields(s)
                                     if field.name not in {"database_url", "storage_root", "port"}})
    caps = client.call("GET", "/v1/audio/capabilities", auth=A)[1]
    assert caps["engine"] == "remote_gpu" and caps["real_audio"] is True
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="remote-ok-1")[1]["id"], A)
    assert job["state"] == "succeeded" and job["result"]["generation"]["real_audio"] is True
    second = client.wait(client.call("POST", "/v1/audio/jobs", body(seed=4321), auth=A, key="remote-limit-2")[1]["id"], A)
    assert second["state"] == "failed" and second["error"]["code"] == "ENGINE_UNAVAILABLE"
    for secret in (TOKEN, LYRIC_LINE, SECRET):
        assert secret not in caplog.text


def test_cost_ledger_counts_reservations_and_fails_closed(tmp_path):
    ledger = CostLedger(str(tmp_path / "ledger.json"), 0.60, 0.01, 2, 30, 30, clock=lambda: 1_800_000_000)
    reserved = ledger.reserve()
    assert reserved == pytest.approx(0.01)
    with pytest.raises(GpuBudgetExceeded):
        ledger.reserve()
    ledger.settle(reserved, 0)
    assert ledger.snapshot()["renders"] == 1 and ledger.snapshot()["usd"] == pytest.approx(0.005)
    (tmp_path / "ledger.json").write_text("not-json")
    with pytest.raises(GpuBudgetExceeded):
        ledger.reserve()


def _params():
    return {
        "caption": "Afro-Soul, Romantic, Rhodes, male vocal, 84 bpm",
        "lyrics": "[Verse]\nHello\n\n[Chorus]\nWorld", "instrumental": False,
        "vocal_language": "en", "bpm": 84, "duration": 10, "seed": 1234,
        "inference_steps": 8, "timesignature": "4",
    }


def _request():
    from audioservice.contract import parse_request
    from audioservice.engines import build_render_request
    return build_render_request(parse_request(body(), settings()), 7)
