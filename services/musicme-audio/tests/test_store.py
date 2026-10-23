"""Durable audio job store: every test runs against SQLite and a throwaway local PostgreSQL."""
import json
import threading

import pytest

from conftest import body, settings
from audioservice.contract import parse_request
from audioservice.cost_guard import DatabaseCostLedger, GpuBudgetExceeded
from audioservice.db import Database
from audioservice.errors import ServiceError
from audioservice.storage import LocalObjectStore
from audioservice.store import JobStore


class Clock:
    def __init__(self, now=1_000_000_000):
        self.now = now

    def __call__(self):
        return self.now


def make_store(url, tmp_path, clock=None, **overrides):
    s = settings(database_url=url, storage_root=str(tmp_path / "objects"), **overrides)
    db = Database(url)
    db.migrate()
    return JobStore(db, s, clock=clock or Clock(), rng=lambda: 1.0), db, LocalObjectStore(s.storage_root, s.max_audio_bytes)


def submit(store, account="acct-a", key="key-00000001", **overrides):
    payload = body(**overrides)
    raw = json.dumps(payload).encode()
    return store.submit(account, key, raw, parse_request(payload, store.s))


META = {"engine": "mock", "model_id": "mock-tone-generator", "model_version": "mock-1", "model_revision": "none",
        "interface_version": "musicme.audio-engine.v1", "parameters": {"bpm": 84}, "render_ms": 5}


def stage(store, storage, claimed, worker, size=1000):
    """Registers and writes a fake staged object the way a worker would."""
    object_id, key = store.add_staging_object(claimed.id, worker, claimed.account, 1, "wav")
    path = storage.temp_path(key)
    with open(path, "wb") as f:
        f.write(b"RIFF\x00\x00\x00\x00WAVE" + b"\x00" * (size - 12))
    storage.commit_file(path, key, "wav")
    store.record_object(object_id, size, "0" * 64, 10000)
    return object_id, key


def result_for(object_id):
    return {"tracks": [{"id": object_id, "take": 1, "format": "wav"}], "generation": {"mock": True}}


def test_migrations_are_idempotent_and_namespaced(database_url, tmp_path):
    store, db, _ = make_store(database_url, tmp_path)
    db.migrate()
    with db.transaction() as tx:
        assert [r[0] for r in tx.fetchall("SELECT version FROM audio_schema_migrations")] == [
            "0001_audio.sql", "0002_gpu_cost.sql"]


def test_database_gpu_ledger_survives_restart_and_fails_closed_at_limits(database_url):
    db = Database(database_url)
    db.migrate()
    clock = lambda: 1_800_000_000
    ledger = DatabaseCostLedger(db, 0.60, 0.011, 2, 30, 0, clock=clock)
    first = ledger.reserve()
    ledger.settle(first, 30)
    restarted = DatabaseCostLedger(db, 0.60, 0.011, 2, 30, 0, clock=clock)
    restarted.reserve()
    snapshot = restarted.snapshot()
    assert snapshot["renders"] == 2 and snapshot["usd"] == pytest.approx(0.01)
    with pytest.raises(GpuBudgetExceeded):
        restarted.reserve()


def test_idempotency_survives_a_restart_and_conflicting_reuse_is_refused(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    job, created = submit(store)
    assert created and job["state"] == "queued" and job["queue_position"] == 1
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1")
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "ok"
    db.close()

    restarted, db2, _ = make_store(database_url, tmp_path)
    again, created = submit(restarted)
    assert not created and again["id"] == job["id"] and again["state"] == "succeeded"
    assert again["result"]["tracks"][0]["available"] is True
    with pytest.raises(ServiceError) as conflict:
        submit(restarted, title="A different song")
    assert conflict.value.status == 409
    db2.close()


def test_concurrent_identical_submissions_create_one_job(database_url, tmp_path):
    store, db, _ = make_store(database_url, tmp_path)
    results, errors = [], []

    def run():
        try:
            results.append(submit(store))
        except Exception as error:  # pragma: no cover - surfaced below
            errors.append(error)

    threads = [threading.Thread(target=run) for _ in range(8)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert not errors
    assert len({job["id"] for job, _ in results}) == 1 and sum(created for _, created in results) == 1


def test_concurrent_workers_claim_each_job_exactly_once(database_url, tmp_path):
    store, db, _ = make_store(database_url, tmp_path, max_active_jobs_per_account=50, max_jobs_per_window=50)
    ids = {submit(store, account=f"acct-{i}", key=f"key-{i:08d}")[0]["id"] for i in range(12)}
    claimed, lock = [], threading.Lock()

    def worker(name):
        while True:
            job = store.claim(name)
            if job is None:
                return
            with lock:
                claimed.append(job.id)

    threads = [threading.Thread(target=worker, args=(f"w{i}",)) for i in range(6)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert sorted(claimed) == sorted(ids)


def test_jobs_and_tracks_are_invisible_to_other_accounts(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    job, _ = submit(store)
    for call in (lambda: store.get("acct-b", job["id"]), lambda: store.cancel("acct-b", job["id"])):
        with pytest.raises(ServiceError) as error:
            call()
        assert error.value.status == 404
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1")
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "ok"
    for call in (lambda: store.track_for_link("acct-b", object_id), lambda: store.delete_track("acct-b", object_id)):
        with pytest.raises(ServiceError) as error:
            call()
        assert error.value.code == "TRACK_NOT_FOUND"
    assert store.track_for_link("acct-a", object_id)["id"] == object_id


def test_staged_objects_cannot_be_linked_before_commit(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    submit(store)
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1")
    with pytest.raises(ServiceError):
        store.track_for_link("acct-a", object_id)
    assert store.track_for_download(object_id) is None


def test_cancel_prevents_publishing_and_cleanup_removes_staged_files(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    job, _ = submit(store)
    claimed = store.claim("w1")
    object_id, key = stage(store, storage, claimed, "w1")
    assert store.cancel("acct-a", job["id"])["state"] == "cancelled"
    assert not store.heartbeat(claimed.id, "w1", "rendering", 50)
    assert store.add_staging_object(claimed.id, "w1", "acct-a", 2, "wav") is None
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "discarded"
    assert store.get("acct-a", job["id"])["result"] is None
    report = store.cleanup(storage)
    assert report["objects_deleted"] == 1 and not storage.exists(key)


def test_crashed_worker_lease_is_recovered_then_fails_after_max_attempts(database_url, tmp_path):
    clock = Clock()
    store, db, storage = make_store(database_url, tmp_path, clock=clock)
    job, _ = submit(store)
    first = store.claim("dead-worker")
    _, key = stage(store, storage, first, "dead-worker")
    clock.now += store.s.effective_lease_seconds * 1000 + 1
    assert store.recover_expired_leases() == 1
    assert store.get("acct-a", job["id"])["state"] == "queued"
    store.cleanup(storage)
    assert not storage.exists(key)  # the dead worker's partial file is gone
    assert not store.heartbeat(first.id, "dead-worker", "rendering", 10)  # the old worker can't resume

    clock.now += 10_000_000
    second = store.claim("w2")
    assert second.id == job["id"] and second.attempts == 2
    clock.now += store.s.effective_lease_seconds * 1000 + 1
    store.recover_expired_leases()
    final = store.get("acct-a", job["id"])
    assert final["state"] == "failed" and final["error"]["code"] == "WORKER_LOST"


def test_heartbeats_keep_long_renders_alive(database_url, tmp_path):
    clock = Clock()
    store, db, _ = make_store(database_url, tmp_path, clock=clock)
    submit(store)
    claimed = store.claim("w1")
    for _ in range(5):
        clock.now += (store.s.effective_lease_seconds - 1) * 1000
        assert store.heartbeat(claimed.id, "w1", "rendering", 40)
        assert store.recover_expired_leases() == 0
    assert store.get("acct-a", claimed.id)["stage"] == "rendering"


def test_retry_limit_and_failed_jobs_are_refunded_from_the_daily_quota(database_url, tmp_path):
    store, db, _ = make_store(database_url, tmp_path, max_jobs_per_window=1)
    job, _ = submit(store)
    claimed = store.claim("w1")
    assert store.retry_or_fail(claimed.id, "w1", "AUDIO_SILENT", "x") == "queued"
    store.clock.now += 10_000_000
    claimed = store.claim("w1")
    assert claimed.attempts == 2
    assert store.retry_or_fail(claimed.id, "w1", "AUDIO_SILENT", "Try again.") == "failed"
    assert store.get("acct-a", job["id"])["error"] == {"code": "AUDIO_SILENT", "message": "Try again."}
    second, created = submit(store, key="key-00000002")  # the failed job doesn't use up the one-song quota
    assert created
    with pytest.raises(ServiceError) as error:
        submit(store, key="key-00000003")
    assert error.value.code == "RATE_LIMITED"


def test_one_active_job_per_account_and_global_queue_cap(database_url, tmp_path):
    store, db, _ = make_store(database_url, tmp_path, max_queued_jobs=2)
    submit(store)
    with pytest.raises(ServiceError) as error:
        submit(store, key="key-00000002")
    assert error.value.code == "RATE_LIMITED"
    submit(store, account="acct-b")
    with pytest.raises(ServiceError) as error:
        submit(store, account="acct-c")
    assert error.value.status == 503


def test_storage_quota_blocks_publishing_and_new_requests(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path, account_storage_quota_bytes=1500, max_jobs_per_window=10)
    submit(store)
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1", size=1000)
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "ok"
    submit(store, key="key-00000002")
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1", size=1000)
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "quota"
    assert store.usage("acct-a")["used_bytes"] == 1000  # the over-quota take was never committed


def test_full_storage_refuses_new_requests(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path, account_storage_quota_bytes=1000, max_jobs_per_window=10)
    submit(store)
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1", size=1000)
    assert store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META) == "ok"
    with pytest.raises(ServiceError) as error:
        submit(store, key="key-00000002")
    assert error.value.code == "STORAGE_QUOTA_EXCEEDED"
    store.delete_track("acct-a", object_id)
    assert submit(store, key="key-00000002")[1]


def test_retention_deletes_expired_tracks_old_jobs_and_orphans(database_url, tmp_path):
    clock = Clock()
    store, db, storage = make_store(database_url, tmp_path, clock=clock)
    job, _ = submit(store)
    claimed = store.claim("w1")
    object_id, key = stage(store, storage, claimed, "w1")
    store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META)
    assert store.cleanup(storage)["objects_deleted"] == 0

    clock.now += store.s.track_ttl_seconds * 1000 + 1
    report = store.cleanup(storage)
    assert report["objects_deleted"] == 1 and not storage.exists(key)
    assert report["jobs_deleted"] == 1  # job rows share the 30-day retention
    with pytest.raises(ServiceError):
        store.get("acct-a", job["id"])


def test_expired_track_is_reported_unavailable_before_cleanup(database_url, tmp_path):
    clock = Clock()
    store, db, storage = make_store(database_url, tmp_path, clock=clock, job_ttl_seconds=90 * 24 * 3600)
    job, _ = submit(store)
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1")
    store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META)
    clock.now += store.s.track_ttl_seconds * 1000 + 1
    assert store.get("acct-a", job["id"])["result"]["tracks"][0]["available"] is False
    with pytest.raises(ServiceError):
        store.track_for_link("acct-a", object_id)


def test_stale_queued_jobs_expire(database_url, tmp_path):
    clock = Clock()
    store, db, storage = make_store(database_url, tmp_path, clock=clock)
    job, _ = submit(store)
    clock.now += store.s.queue_ttl_seconds * 1000 + 1
    assert store.cleanup(storage)["expired"] == 1
    assert store.get("acct-a", job["id"])["error"]["code"] == "EXPIRED"


def test_account_deletion_removes_everything_for_that_account_only(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    submit(store)
    claimed = store.claim("w1")
    object_id, key = stage(store, storage, claimed, "w1")
    store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META)
    other, _ = submit(store, account="acct-b")
    keys, jobs = store.delete_account_data("acct-a")
    assert keys == [key] and jobs == 1
    assert store.get("acct-b", other["id"])["state"] == "queued"
    assert store.usage("acct-a")["used_bytes"] == 0


def test_terminal_jobs_purge_lyrics(database_url, tmp_path):
    store, db, storage = make_store(database_url, tmp_path)
    job, _ = submit(store)
    with db.transaction() as tx:
        assert "Ada laughed" in tx.fetchone("SELECT spec_json FROM audio_jobs WHERE id = ?", (job["id"],))[0]
    claimed = store.claim("w1")
    object_id, _ = stage(store, storage, claimed, "w1")
    store.complete(claimed.id, "w1", "acct-a", result_for(object_id), META)
    with db.transaction() as tx:
        spec_json, result_json, params = tx.fetchone("SELECT spec_json, result_json, params_json FROM audio_jobs WHERE id = ?", (job["id"],))
    assert spec_json is None and "Ada" not in result_json and "Ada" not in params


def test_worker_with_an_expired_lease_cannot_publish_even_before_recovery(database_url, tmp_path):
    clock = Clock()
    store, db, storage = make_store(database_url, tmp_path, clock=clock)
    job, _ = submit(store)
    claimed = store.claim("slow-worker")
    object_id, _ = stage(store, storage, claimed, "slow-worker")
    clock.now += store.s.effective_lease_seconds * 1000 + 1  # lease expired; the recovery sweep hasn't run yet
    assert store.complete(claimed.id, "slow-worker", "acct-a", result_for(object_id), META) == "discarded"
    assert not store.heartbeat(claimed.id, "slow-worker", "storing", 95)
    assert store.add_staging_object(claimed.id, "slow-worker", "acct-a", 2, "wav") is None
    assert not store.fail(claimed.id, "slow-worker", "UNKNOWN", "x")
    assert store.retry_or_fail(claimed.id, "slow-worker", "AUDIO_SILENT", "x") is None
    assert store.get("acct-a", job["id"])["result"] is None
    assert store.track_for_download(object_id) is None  # its staged file was never committed

    assert store.recover_expired_leases() == 1
    clock.now += 10_000_000
    replacement = store.claim("w2")
    assert replacement.id == job["id"]
    assert store.complete(job["id"], "slow-worker", "acct-a", result_for(object_id), META) == "discarded"
    fresh, _ = stage(store, storage, replacement, "w2")
    assert store.complete(job["id"], "w2", "acct-a", result_for(fresh), META) == "ok"


def test_database_gpu_ledger_never_overspends_under_concurrent_processes(database_url):
    """Twelve independent connections (separate processes/instances) race for five renders' worth of budget."""
    import threading
    Database(database_url).migrate()
    clock = lambda: 1_800_000_000
    results, barrier = [], threading.Barrier(12)

    def contender():
        db = Database(database_url)  # its own connection, like a separate API instance or coordinator
        ledger = DatabaseCostLedger(db, 0.60, 1.0, 5, 30, 0, clock=clock)
        barrier.wait()
        try:
            results.append(ledger.reserve())
        except GpuBudgetExceeded:
            results.append(None)
        finally:
            db.close()

    threads = [threading.Thread(target=contender) for _ in range(12)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(30)
    granted = [r for r in results if r is not None]
    assert len(results) == 12 and len(granted) == 5
    snapshot = DatabaseCostLedger(Database(database_url), 0.60, 1.0, 5, 30, 0, clock=clock).snapshot()
    assert snapshot["renders"] == 5 and snapshot["usd"] == pytest.approx(5 * 30 * 0.60 / 3600, abs=1e-6)


def test_database_gpu_ledger_keeps_a_crashed_reservation_charged_and_settles_each_retry_once(database_url):
    db = Database(database_url)
    db.migrate()
    clock = lambda: 1_800_000_000
    ledger = DatabaseCostLedger(db, 3.60, 0.10, 10, 30, 0, clock=clock)  # $0.001/s; each reservation $0.03
    ledger.reserve()  # the process "crashes" here: no settle ever arrives
    retry = DatabaseCostLedger(Database(database_url), 3.60, 0.10, 10, 30, 0, clock=clock)  # restarted worker
    reserved = retry.reserve()
    retry.settle(reserved, 5)  # the retry measured 5 s
    snapshot = retry.snapshot()
    assert snapshot["renders"] == 2 and snapshot["usd"] == pytest.approx(0.03 + 0.005, abs=1e-6)
    retry.reserve()
    retry.reserve()  # 0.035 + 0.03 + 0.03 = 0.095
    with pytest.raises(GpuBudgetExceeded):
        retry.reserve()  # 0.125 > 0.10: the crashed reservation still counts against the limit
