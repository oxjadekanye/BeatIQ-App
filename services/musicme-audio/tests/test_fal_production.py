"""No-network regression coverage for the production fal.ai execution path."""
import json
import io
import os

import pytest

from conftest import A, B, body, settings, token
from audioservice.contract import parse_request
from audioservice.cost_guard import FalBudgetExceeded, FalCostLedger
from audioservice.db import Database
from audioservice.engines import EngineError, build_render_request
from audioservice.fal_engine import FalAudioEngine
from audioservice.providers.fal import FalProvider, FalProviderError
from audioservice.store import JobStore


class Clock:
    def __init__(self, now=1_900_000_000_000):
        self.now = now

    def __call__(self):
        return self.now


class FakeFal:
    def __init__(self):
        self.submissions = []
        self.statuses = []
        self.cancellations = []

    def submit(self, endpoint, arguments, timeout=30):
        self.submissions.append((endpoint, arguments, timeout))
        return "request_12345678"

    def status(self, endpoint, request_id, timeout=15):
        self.statuses.append((endpoint, request_id))
        return {"status": "COMPLETED"}

    def result(self, endpoint, request_id, timeout=30):
        return {"audio": {"url": "https://cdn.fal.media/song.wav"}, "seed": 99}

    def download_audio(self, url, destination, max_bytes, timeout=60):
        with open(destination, "xb") as output:
            output.write(b"provider-audio")

    def cancel(self, endpoint, request_id, timeout=10):
        self.cancellations.append((endpoint, request_id))
        return True


class ProviderState:
    def __init__(self, accept=True):
        self.accept = accept
        self.calls = []

    def set_provider_request(self, job_id, worker_id, request_id, endpoint):
        self.calls.append((job_id, worker_id, request_id, endpoint))
        return self.accept


class Ledger:
    def __init__(self):
        self.reservations = []
        self.settlements = []

    def reserve(self, job_id, account, seconds):
        self.reservations.append((job_id, account, seconds))
        return 0.002

    def settle(self, job_id, seconds):
        self.settlements.append((job_id, seconds))


class FakeResponse(io.BytesIO):
    def __init__(self, content, content_type):
        super().__init__(content)
        self.headers = {"Content-Type": content_type}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


class RecordingOpener:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return self.response


def fal_settings(**overrides):
    values = dict(engine="fal", workers=1, max_takes=1, max_attempts=1, min_duration_seconds=5,
                  max_duration_seconds=240, fal_key="test-fal-key-that-is-long-enough",
                  generation_mode="internal_test", internal_test_user_ids=frozenset({"account-a", "account-b"}))
    values.update(overrides)
    return settings(**values)


def request_for(s, **overrides):
    payload = body(**overrides)
    spec = parse_request(payload, s)
    return build_render_request(spec, 42, job_id="job-1", account="account-a", worker_id="worker-1")


def test_custom_lyrics_uses_only_reviewed_endpoint_and_explicit_defaults(tmp_path):
    s, client, state, ledger = fal_settings(), FakeFal(), ProviderState(), Ledger()
    engine = FalAudioEngine(s, ledger=ledger, provider_state=state, client=client)
    result = engine.render(request_for(s), str(tmp_path / "song.wav"), lambda _: True, engine.clock() + 60)

    endpoint, arguments, _ = client.submissions[0]
    assert endpoint == "fal-ai/ace-step"
    assert arguments["duration"] == 10 and arguments["number_of_steps"] == 27
    assert arguments["scheduler"] == "euler" and arguments["guidance_type"] == "apg"
    assert "[Verse]" in arguments["lyrics"] and "Ada laughed" in arguments["lyrics"]
    assert "prompt" not in arguments and "instrumental" not in arguments
    assert ledger.reservations == [("job-1", "account-a", 12.5)]
    assert state.calls[0][2:] == ("request_12345678", "fal-ai/ace-step")
    assert result.engine_parameters["model_version_pinned"] is False


def test_prompt_song_uses_prompt_endpoint_and_instrumental_flag(tmp_path):
    s, client = fal_settings(), FakeFal()
    sections = [{"id": "i", "type": "instrumental", "label": "Groove", "lyrics": ""}]
    request = request_for(s, sections=sections, prompt="An uplifting Lagos sunset groove",
                          vocals={"type": "none", "descriptors": []})
    FalAudioEngine(s, ledger=Ledger(), provider_state=ProviderState(), client=client).render(
        request, str(tmp_path / "song.wav"), lambda _: True, 10**12)
    endpoint, arguments, _ = client.submissions[0]
    assert endpoint == "fal-ai/ace-step/prompt-to-audio"
    assert arguments["instrumental"] is True and "lyrics" not in arguments and "tags" not in arguments
    assert "Lagos sunset groove" in arguments["prompt"]


def test_saved_provider_request_is_resumed_without_a_second_paid_submission(tmp_path):
    s, client = fal_settings(), FakeFal()
    original = request_for(s)
    resumed = build_render_request(parse_request(body(), s), 42, job_id=original.job_id, account=original.account,
                                   worker_id="worker-2", provider_request_id="request_12345678",
                                   provider_endpoint="fal-ai/ace-step")
    FalAudioEngine(s, ledger=Ledger(), provider_state=ProviderState(), client=client).render(
        resumed, str(tmp_path / "song.wav"), lambda _: True, 10**12)
    assert client.submissions == []
    assert client.statuses == [("fal-ai/ace-step", "request_12345678")]


def test_lost_lease_requeues_saved_provider_request_without_incrementing_attempt(database_url, tmp_path):
    clock = Clock()
    s = fal_settings(database_url=database_url, storage_root=str(tmp_path / "objects"))
    db = Database(database_url)
    db.migrate()
    store = JobStore(db, s, clock=clock)
    payload = body()
    job, _ = store.submit("account-a", "key-00000001", json.dumps(payload).encode(), parse_request(payload, s))
    claimed = store.claim("worker-1")
    assert claimed.attempts == 1
    assert store.set_provider_request(job["id"], "worker-1", "request_12345678", "fal-ai/ace-step")
    clock.now += s.effective_lease_seconds * 1000 + 1
    assert store.recover_expired_leases() == 1
    clock.now += 10_000_000
    resumed = store.claim("worker-2")
    assert resumed.provider_request_id == "request_12345678" and resumed.attempts == 1
    assert store.release(job["id"], "worker-2")
    clock.now += 1
    handed_off = store.claim("worker-3")
    assert handed_off.attempts == 1 and handed_off.provider_request_id == "request_12345678"


def test_fal_budget_is_atomic_idempotent_and_conservative(database_url, tmp_path):
    clock = Clock()
    s = fal_settings(database_url=database_url, storage_root=str(tmp_path / "objects"), fal_daily_budget_usd=0.003)
    db = Database(database_url)
    db.migrate()
    store = JobStore(db, s, clock=clock)
    payload = body()
    job, _ = store.submit("account-a", "key-00000001", json.dumps(payload).encode(), parse_request(payload, s))
    store.claim("worker-1")
    ledger = FalCostLedger(db, 0.0002, 0.003, clock=lambda: clock.now / 1000)
    assert ledger.reserve(job["id"], "account-a", 10) == pytest.approx(0.002)
    assert ledger.reserve(job["id"], "account-a", 10) == pytest.approx(0.002)
    assert ledger.snapshot()["requests"] == 1

    second, _ = store.submit("account-b", "key-00000002", json.dumps(payload).encode(), parse_request(payload, s))
    store.claim("worker-2")
    with pytest.raises(FalBudgetExceeded):
        ledger.reserve(second["id"], "account-b", 10)
    ledger.settle(job["id"], 5)
    snapshot = ledger.snapshot()
    assert snapshot["requests"] == 1 and snapshot["output_seconds"] == 5
    assert snapshot["usd"] == pytest.approx(0.001)


def test_cancellation_and_invalid_resumption_fail_closed(tmp_path):
    s, client = fal_settings(), FakeFal()
    engine = FalAudioEngine(s, ledger=Ledger(), provider_state=ProviderState(), client=client)
    request = request_for(s)
    with pytest.raises(Exception):
        engine.render(request, str(tmp_path / "cancel.wav"), lambda _: False, 10**12)
    assert client.cancellations == [("fal-ai/ace-step", "request_12345678")]

    invalid = build_render_request(parse_request(body(), s), 42, job_id="job-1", account="account-a",
                                   worker_id="worker", provider_request_id="request_12345678",
                                   provider_endpoint="fal-ai/other")
    with pytest.raises(EngineError, match="PROVIDER_STATE_INVALID"):
        engine.render(invalid, str(tmp_path / "never.wav"), lambda _: True, 10**12)


def test_provider_uses_key_header_and_rejects_unapproved_identifiers():
    opener = RecordingOpener(FakeResponse(b'{"request_id":"request_12345678"}', "application/json"))
    provider = FalProvider("test-fal-key-that-is-long-enough", opener=opener)
    assert provider.submit("fal-ai/ace-step", {"duration": 10}) == "request_12345678"
    request, timeout = opener.requests[0]
    assert request.full_url == "https://queue.fal.run/fal-ai/ace-step"
    assert request.get_header("Authorization") == "Key test-fal-key-that-is-long-enough"
    assert timeout == 30
    with pytest.raises(FalProviderError, match="PROVIDER_ENDPOINT_INVALID"):
        provider.submit("https://attacker.test/model", {})
    with pytest.raises(FalProviderError, match="PROVIDER_REQUEST_INVALID"):
        provider.status("fal-ai/ace-step", "../other")


def test_provider_download_is_host_bounded_private_and_cleans_partial_files(tmp_path):
    allowed = RecordingOpener(FakeResponse(b"RIFF-audio", "audio/wav"))
    provider = FalProvider("test-fal-key-that-is-long-enough", allowed_download_hosts=(".fal.media",),
                           download_opener=allowed)
    destination = tmp_path / "song.wav"
    assert provider.download_audio("https://cdn.fal.media/output.wav", str(destination), 100) == 10
    assert destination.read_bytes() == b"RIFF-audio"
    assert os.stat(destination).st_mode & 0o777 == 0o600

    for url in ("http://cdn.fal.media/output.wav", "https://fal.media/output.wav",
                "https://cdn.fal.media.evil.test/output.wav", "https://user@cdn.fal.media/output.wav"):
        with pytest.raises(FalProviderError, match="PROVIDER_OUTPUT_URL_INVALID"):
            provider.download_audio(url, str(tmp_path / ("blocked-" + str(len(url)))), 100)

    oversized = tmp_path / "oversized.wav"
    provider = FalProvider("test-fal-key-that-is-long-enough", allowed_download_hosts=(".fal.media",),
                           download_opener=RecordingOpener(FakeResponse(b"x" * 101, "audio/wav")))
    with pytest.raises(FalProviderError, match="PROVIDER_OUTPUT_TOO_LARGE"):
        provider.download_audio("https://cdn.fal.media/output.wav", str(oversized), 100)
    assert not oversized.exists()


def test_status_result_and_cancel_urls_use_owner_app_without_endpoint_subpath():
    # fal documents a 405 for request URLs that include the subpath (fal-ai/ace-step/prompt-to-audio/requests/...).
    responses = [FakeResponse(b'{"status":"IN_PROGRESS"}', "application/json"),
                 FakeResponse(b'{"audio":{"url":"https://v3.fal.media/a.wav"},"seed":1}', "application/json"),
                 FakeResponse(b'{"status":"CANCELLATION_REQUESTED"}', "application/json"),
                 FakeResponse(b'{"request_id":"request_12345678"}', "application/json")]

    class Sequence(RecordingOpener):
        def open(self, request, timeout):
            self.requests.append((request, timeout))
            return responses.pop(0)

    opener = Sequence(None)
    provider = FalProvider("test-fal-key-that-is-long-enough", opener=opener)
    provider.status("fal-ai/ace-step/prompt-to-audio", "request_12345678")
    provider.result("fal-ai/ace-step/prompt-to-audio", "request_12345678")
    assert provider.cancel("fal-ai/ace-step/prompt-to-audio", "request_12345678") is True
    provider.submit("fal-ai/ace-step/prompt-to-audio", {"prompt": "x"})
    urls = [(r.get_method(), r.full_url) for r, _ in opener.requests]
    assert urls == [
        ("GET", "https://queue.fal.run/fal-ai/ace-step/requests/request_12345678/status"),
        ("GET", "https://queue.fal.run/fal-ai/ace-step/requests/request_12345678"),
        ("PUT", "https://queue.fal.run/fal-ai/ace-step/requests/request_12345678/cancel"),
        ("POST", "https://queue.fal.run/fal-ai/ace-step/prompt-to-audio"),
    ]


@pytest.mark.parametrize("status,expected", [("OK", True), ("CANCELLATION_REQUESTED", True),
                                             ("ALREADY_COMPLETED", False), ("NOT_FOUND", False)])
def test_cancel_reports_documented_fal_outcomes(status, expected):
    opener = RecordingOpener(FakeResponse(json.dumps({"status": status}).encode(), "application/json"))
    provider = FalProvider("test-fal-key-that-is-long-enough", opener=opener)
    assert provider.cancel("fal-ai/ace-step", "request_12345678") is expected


def test_shared_gcs_host_is_limited_to_fal_output_bucket(tmp_path):
    provider = FalProvider("test-fal-key-that-is-long-enough", allowed_download_hosts=("storage.googleapis.com",),
                           download_opener=RecordingOpener(FakeResponse(b"RIFF-audio", "audio/wav")))
    with pytest.raises(FalProviderError, match="PROVIDER_OUTPUT_URL_INVALID"):
        provider.download_audio("https://storage.googleapis.com/attacker-bucket/a.wav", str(tmp_path / "x.wav"), 100)
    assert provider.download_audio("https://storage.googleapis.com/falserverless/out/a.wav",
                                   str(tmp_path / "ok.wav"), 100) == 10


@pytest.mark.parametrize("overrides", [dict(generation_mode="disabled"),
                                       dict(internal_test_user_ids=frozenset({"someone-else"}))])
def test_kill_switch_and_allowlist_are_rechecked_before_any_paid_submission(tmp_path, overrides):
    s, client, ledger = fal_settings(**overrides), FakeFal(), Ledger()
    engine = FalAudioEngine(s, ledger=ledger, provider_state=ProviderState(), client=client)
    with pytest.raises(EngineError, match="GENERATION_DISABLED"):
        engine.render(request_for(s), str(tmp_path / "song.wav"), lambda _: True, 10**12)
    assert client.submissions == [] and ledger.reservations == []

    # A request that was already paid for is still resumed (and never resubmitted) after the switch flips.
    resumed = build_render_request(parse_request(body(), s), 42, job_id="job-1", account="account-a",
                                   worker_id="worker-2", provider_request_id="request_12345678",
                                   provider_endpoint="fal-ai/ace-step")
    engine.render(resumed, str(tmp_path / "resumed.wav"), lambda _: True, 10**12)
    assert client.submissions == [] and client.statuses == [("fal-ai/ace-step", "request_12345678")]


def test_settlement_never_drops_below_requested_duration():
    ledger = Ledger()
    engine = FalAudioEngine(fal_settings(), ledger=ledger, provider_state=ProviderState(), client=FakeFal())
    engine.settle_cost("job-1", 8.5, 10)
    engine.settle_cost("job-2", 11.0, 10)
    assert ledger.settlements == [("job-1", 10), ("job-2", 11.0)]


def test_paid_jobs_count_against_quota_after_cancel_failure_or_account_deletion(database_url, tmp_path):
    clock = Clock()
    s = fal_settings(database_url=database_url, storage_root=str(tmp_path / "objects"), max_jobs_per_window=2)
    db = Database(database_url)
    db.migrate()
    store = JobStore(db, s, clock=clock)
    ledger = FalCostLedger(db, 0.0002, 2.0, clock=lambda: clock.now / 1000)
    payload = body()
    raw, spec = json.dumps(payload).encode(), parse_request(payload, s)

    def paid_then_cancelled(key):
        job, _ = store.submit("account-a", key, raw, spec)
        store.claim("worker-1")
        ledger.reserve(job["id"], "account-a", 12.5)
        assert store.cancel("account-a", job["id"])["state"] == "cancelled"
        clock.now += 1

    unpaid, _ = store.submit("account-a", "key-unpaid-1", raw, spec)
    store.cancel("account-a", unpaid["id"])  # never reached the provider: refunded
    clock.now += 1
    paid_then_cancelled("key-paid-0001")
    paid_then_cancelled("key-paid-0002")
    with pytest.raises(Exception) as limited:
        store.submit("account-a", "key-paid-0003", raw, spec)
    assert getattr(limited.value, "status", None) == 429

    store.delete_account_data("account-a")
    with pytest.raises(Exception) as still_limited:
        store.submit("account-a", "key-paid-0004", raw, spec)
    assert getattr(still_limited.value, "status", None) == 429

    clock.now += s.rate_window_seconds * 1000
    job, created = store.submit("account-a", "key-paid-0005", raw, spec)
    assert created and job["state"] == "queued"


class WavFal(FakeFal):
    """Fake provider that "generates" a valid 16-bit WAV of the requested length (no network)."""

    def submit(self, endpoint, arguments, timeout=30):
        self.duration = arguments["duration"]
        return super().submit(endpoint, arguments, timeout)

    def download_audio(self, url, destination, max_bytes, timeout=60):
        import math
        import struct
        import wave
        rate = 44100
        frames = b"".join(struct.pack("<hh", v, v) for v in
                          (int(8000 * math.sin(2 * math.pi * 440 * i / rate)) for i in range(int(rate * self.duration))))
        with wave.open(destination, "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(rate)
            output.writeframes(frames)


def test_http_lifecycle_with_fal_engine_ledger_and_account_isolation(database_url, tmp_path, make_service):
    outsider = token("account-outsider")
    client, service = make_service(
        start_workers=False, engine="fal", workers=1, max_takes=1, max_attempts=1, min_duration_seconds=5,
        max_duration_seconds=240, fal_key="test-fal-key-that-is-long-enough", database_url=database_url,
        storage_root=str(tmp_path / "objects"))
    fake = WavFal()
    service.engine.client = fake  # the real FalAudioEngine, real ledger and real JobStore; only HTTP is faked
    service.engine.sleep = lambda _seconds: None

    status, caps, _ = client.call("GET", "/v1/audio/capabilities", auth=A)
    assert status == 200 and caps["engine"] == "fal" and caps["real_audio"] is True
    assert caps["max_takes"] == 1 and caps["public_sharing"] is False and caps["commercial_distribution"] is False

    status, denied, _ = client.call("POST", "/v1/audio/jobs", body(), auth=outsider, key="outsider-key-1")
    assert status == 403 and denied["error"]["code"] == "GENERATION_DISABLED"
    assert client.call("POST", "/v1/audio/jobs", body(), key="anon-key-0001")[0] == 401

    status, job, _ = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="lifecycle-key-1")
    assert status == 202
    status, again, _ = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="lifecycle-key-1")
    assert status == 200 and again["id"] == job["id"]
    assert client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=B)[0] == 404

    service.pool.start()
    done = client.wait(job["id"], A, timeout=60)
    assert done["state"] == "succeeded", done
    assert len(fake.submissions) == 1 and fake.submissions[0][0] == "fal-ai/ace-step"
    generation = done["result"]["generation"]
    assert generation["engine"] == "fal" and generation["model_version"] == "provider-managed-unpinned"
    assert generation["engine_details"]["provider_request_id"] == "request_12345678"
    snapshot = service.engine.ledger.snapshot()
    assert snapshot["requests"] == 1 and snapshot["output_seconds"] == pytest.approx(10, abs=0.01)
    assert snapshot["usd"] == pytest.approx(0.002, abs=1e-6)

    track_id = done["result"]["tracks"][0]["id"]
    assert client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=B)[0] == 404
    status, link, _ = client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=A)
    assert status == 200 and "/v1/audio/files/" in link["url"] and "fal" not in link["url"]
    status, audio, headers = client.fetch(link["url"].replace(service.settings.public_base_url.rstrip("/"),
                                                              f"http://127.0.0.1:{service.port}"))
    assert status == 200 and audio[:4] == b"RIFF" and headers["Cache-Control"] == "private, no-store"
    tampered = link["url"][:-2] + ("AA" if not link["url"].endswith("AA") else "BB")
    assert client.fetch(tampered.replace(service.settings.public_base_url.rstrip("/"),
                                         f"http://127.0.0.1:{service.port}"))[0] == 403

    status, tracks, _ = client.call("GET", "/v1/audio/tracks", auth=A)
    assert status == 200 and [t["id"] for t in tracks["tracks"]] == [track_id]
    assert client.call("GET", "/v1/audio/tracks", auth=B)[1]["tracks"] == []
    assert client.call("DELETE", f"/v1/audio/tracks/{track_id}", auth=B)[0] == 404
    assert client.call("DELETE", f"/v1/audio/tracks/{track_id}", auth=A)[0] == 200
    assert client.fetch(link["url"].replace(service.settings.public_base_url.rstrip("/"),
                                            f"http://127.0.0.1:{service.port}"))[0] == 404


def test_migration_0003_upgrades_existing_data_and_reapplies_idempotently(database_url, tmp_path, monkeypatch):
    import shutil
    from audioservice import db as db_module

    legacy = tmp_path / "legacy-migrations"
    legacy.mkdir()
    for name in sorted(os.listdir(db_module.MIGRATIONS_DIR)):
        if name.endswith(".sql") and name < "0003":
            shutil.copy(os.path.join(db_module.MIGRATIONS_DIR, name), legacy / name)
    monkeypatch.setattr(db_module, "MIGRATIONS_DIR", str(legacy))
    clock = Clock()
    s = fal_settings(database_url=database_url, storage_root=str(tmp_path / "objects"))
    old = Database(database_url)
    old.migrate()
    with old.transaction(exclusive=True) as tx:
        tx.execute("INSERT INTO audio_jobs (id, account, idempotency_key, body_hash, spec_json, state, stage, progress, "
                   "takes, attempts, max_attempts, next_attempt_at, seed, created_at, updated_at) "
                   "VALUES ('legacy-job', 'account-a', 'legacy-key-1', 'h', '{}', 'succeeded', 'done', 100, 1, 1, 1, 0, 1, ?, ?)",
                   (clock.now, clock.now))
    old.close()

    monkeypatch.undo()
    upgraded = Database(database_url)
    upgraded.migrate()
    upgraded.migrate()  # recorded versions are skipped; a second startup changes nothing
    with upgraded.transaction() as tx:
        versions = [r[0] for r in tx.fetchall("SELECT version FROM audio_schema_migrations ORDER BY version")]
        legacy_row = tx.fetchone("SELECT state, provider_request_id FROM audio_jobs WHERE id = 'legacy-job'")
        assert tx.fetchone("SELECT COUNT(*) FROM audio_fal_reservations")[0] == 0
    assert versions == sorted(n for n in os.listdir(db_module.MIGRATIONS_DIR) if n.endswith(".sql"))
    assert "0003_fal_production.sql" in versions
    assert tuple(legacy_row) == ("succeeded", None)
    store = JobStore(upgraded, s, clock=clock)
    payload = body()
    job, created = store.submit("account-a", "post-upgrade-1", json.dumps(payload).encode(), parse_request(payload, s))
    assert created and job["state"] == "queued"
