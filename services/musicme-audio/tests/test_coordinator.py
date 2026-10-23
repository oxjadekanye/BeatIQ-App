"""Split-process operation: an API with RUN_WORKERS=false plus dedicated coordinators sharing the durable job store."""
import json
import os
import threading
import time
import uuid

import pytest

from conftest import A, body, settings, token
from fake_worker import TOKEN, FakeWorker
from audioservice.db import Database
from audioservice.engines import RemoteWorkerEngine
from audioservice.worker_service import Coordinator


def shared(tmp_path, database_url=None, **overrides):
    values = dict(database_url=database_url or "sqlite:///" + str(tmp_path / "shared.db"),
                  storage_root=str(tmp_path / "objects"))
    values.update(overrides)
    return values


def job_row(database_url, job_id):
    db = Database(database_url)
    try:
        with db.transaction() as tx:
            return tx.fetchone("SELECT state, attempts, lease_owner FROM audio_jobs WHERE id = ?", (job_id,))
    finally:
        db.close()


def test_api_without_workers_queues_and_a_separate_coordinator_completes(tmp_path, make_service):
    values = shared(tmp_path, engine="mock", workers=1)
    client, _ = make_service(run_workers=False, **values)
    job_id = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="split-key-0001")[1]["id"]
    time.sleep(0.3)
    assert client.call("GET", f"/v1/audio/jobs/{job_id}", auth=A)[1]["state"] == "queued"  # the API never renders
    coordinator = Coordinator(settings(**values))
    coordinator.start()
    try:
        assert client.wait(job_id, A)["state"] == "succeeded"
    finally:
        coordinator.shutdown()


def test_two_coordinators_never_process_the_same_job_twice(tmp_path, make_service, database_url):
    account_ids = frozenset(f"account-multi-{n}" for n in range(6))
    values = shared(tmp_path, database_url, engine="mock", workers=2, internal_test_user_ids=account_ids)
    client, _ = make_service(run_workers=False, **values)
    owners = [token(f"account-multi-{n}") for n in range(6)]  # one active song per account is a product limit
    jobs = [client.call("POST", "/v1/audio/jobs", body(seed=100 + n), auth=owner, key=f"multi-{n}-{uuid.uuid4().hex[:6]}")[1]["id"]
            for n, owner in enumerate(owners)]
    coordinators = [Coordinator(settings(**values)) for _ in range(2)]
    for coordinator in coordinators:
        coordinator.start()
    try:
        for job_id, owner in zip(jobs, owners):
            assert client.wait(job_id, owner, timeout=30)["state"] == "succeeded"
    finally:
        for coordinator in coordinators:
            coordinator.shutdown()
    assert all(job_row(values["database_url"], job_id)[1] == 1 for job_id in jobs)  # each claimed exactly once


@pytest.fixture
def remote_values(tmp_path, monkeypatch):
    monkeypatch.setattr(RemoteWorkerEngine, "POLL_SECONDS", 0.05)
    credentials = tmp_path / "worker.json"
    credentials.write_text(json.dumps({"token": TOKEN}))
    os.chmod(credentials, 0o600)
    workers = []

    def make(mode):
        worker = FakeWorker(mode).ready()
        workers.append(worker)
        return worker, shared(tmp_path, engine="remote_gpu", workers=1, worker_url=worker.url,
                              worker_credentials_file=str(credentials), worker_startup_timeout_seconds=10,
                              render_timeout_seconds=60, gpu_ledger_file=str(tmp_path / "ledger.json"),
                              gpu_price_per_hour_usd=0.6, gpu_daily_budget_usd=2, gpu_max_renders_per_day=10,
                              gpu_reserve_seconds=30, gpu_idle_tail_seconds=0)

    yield make
    for worker in workers:
        worker.close()


def test_shutdown_mid_render_cancels_the_gpu_job_and_releases_the_durable_job(remote_values, make_service):
    hanging, values = remote_values("hang")
    client, _ = make_service(run_workers=False, **values)
    job_id = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="shutdown-key-0001")[1]["id"]
    coordinator = Coordinator(settings(**values))
    coordinator.start()
    deadline = time.monotonic() + 10
    while not any(j.state == "running" for j in list(hanging.worker.jobs.values())) and time.monotonic() < deadline:
        time.sleep(0.02)
    remote = next(iter(hanging.worker.jobs.values()))
    assert remote.state == "running"
    started = time.monotonic()
    stopper = threading.Thread(target=coordinator.shutdown)
    stopper.start()
    stopper.join(15)
    assert not stopper.is_alive() and time.monotonic() - started < 15
    deadline = time.monotonic() + 5
    while remote.state == "running" and time.monotonic() < deadline:
        time.sleep(0.02)
    assert remote.state == "cancelled"  # no paid GPU work keeps running after the coordinator leaves
    state, attempts, owner = job_row(values["database_url"], job_id)
    assert (state, attempts, owner) == ("queued", 0, None)  # handed back at once; no attempt used up

    healthy, next_values = remote_values("ok")
    replacement = Coordinator(settings(**dict(values, worker_url=healthy.url)))
    replacement.start()
    try:
        finished = client.wait(job_id, A, timeout=30)
        assert finished["state"] == "succeeded" and finished["result"]["generation"]["attempt"] == 1
    finally:
        replacement.shutdown()


def test_a_user_cancellation_during_shutdown_stays_cancelled(tmp_path, make_service):
    values = shared(tmp_path, engine="mock", workers=1)
    client, service = make_service(run_workers=False, **values)
    job_id = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="cancel-key-0001")[1]["id"]
    claimed = service.store.claim("worker-x")
    assert claimed.id == job_id
    client.call("POST", f"/v1/audio/jobs/{job_id}/cancel", auth=A)
    assert service.store.release(job_id, "worker-x") is False
    assert service.store.release(job_id, "someone-else") is False
    assert job_row(values["database_url"], job_id)[0] != "queued"


def test_the_coordinator_refuses_to_run_without_an_engine(tmp_path):
    with pytest.raises(SystemExit):
        Coordinator(settings(**shared(tmp_path, engine="none")))
