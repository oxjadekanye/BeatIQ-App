"""End-to-end HTTP tests with the development mock engine (sine tones, not music)."""
import io
import logging
import threading
import wave

import pytest

from conftest import A, B, LYRIC_LINE, SECRET, SIGNING, body, token
from audioservice.engines import MockToneEngine


class LinkClock:
    def __init__(self):
        import time
        self.now = time.time()

    def __call__(self):
        return self.now


def submit(client, auth=A, key="key-00000001", **overrides):
    return client.call("POST", "/v1/audio/jobs", body(**overrides), auth=auth, key=key)


def test_every_endpoint_requires_a_valid_token(make_service):
    client, _ = make_service()
    paths = [("GET", "/v1/audio/capabilities"), ("POST", "/v1/audio/jobs"),
             ("GET", "/v1/audio/jobs/00000000-0000-0000-0000-000000000000"),
             ("POST", "/v1/audio/jobs/00000000-0000-0000-0000-000000000000/cancel"),
             ("POST", "/v1/audio/tracks/00000000-0000-0000-0000-000000000000/link"),
             ("DELETE", "/v1/audio/tracks/00000000-0000-0000-0000-000000000000"), ("DELETE", "/v1/audio/account")]
    bad_tokens = [None, "garbage", token(exp_offset=-120), token(secret="another-secret-another-secret-another-secret"),
                  token(token_type="refresh"), token(alg="none")]
    for method, path in paths:
        for bad in bad_tokens:
            status, payload, _ = client.call(method, path, auth=bad)
            assert status == 401 and payload["error"]["code"] == "UNAUTHORIZED", (method, path, bad)
    assert client.call("GET", "/healthz")[0] == 200
    assert client.call("GET", "/readyz")[0] == 200


def test_capabilities_are_honest_about_the_mock(make_service):
    client, _ = make_service()
    status, caps, _ = client.call("GET", "/v1/audio/capabilities", auth=A)
    assert status == 200 and caps["engine"] == "mock" and caps["real_audio"] is False
    assert caps["reference_audio"] is False and caps["voice_cloning"] is False
    assert caps["languages"] == ["english"] and caps["storage"]["used_bytes"] == 0


def test_server_without_engine_fails_closed(make_service):
    client, _ = make_service(engine="none")
    assert client.call("GET", "/v1/audio/capabilities", auth=A)[0] == 503
    assert submit(client)[0] == 503


def test_full_flow_submit_render_link_download(make_service):
    client, service = make_service()
    status, job, _ = submit(client, takes=2)
    assert status == 202 and job["state"] == "queued"
    done = client.wait(job["id"], A)
    assert done["state"] == "succeeded" and done["progress"] == 100 and done["stage"] == "done"
    generation = done["result"]["generation"]
    assert generation["mock"] is True and generation["real_audio"] is False and generation["model_id"] == "mock-tone-generator"
    assert generation["seed"] == 1234 and generation["parameters"]["bpm"] == 84 and "seed" not in generation["parameters"]
    tracks = done["result"]["tracks"]
    assert len(tracks) == 2 and tracks[0]["seed"] != tracks[1]["seed"] and all(t["available"] for t in tracks)

    status, link, _ = client.call("POST", f"/v1/audio/tracks/{tracks[0]['id']}/link", auth=A)
    assert status == 200 and "/v1/audio/files/" in link["url"] and link["content_type"] == "audio/wav"
    assert link["url"].startswith(service.settings.public_base_url)
    local = client.base + link["url"][len(service.settings.public_base_url):]
    status, data, headers = client.fetch(local)
    assert status == 200 and headers["Content-Type"] == "audio/wav" and "no-store" in headers["Cache-Control"]
    assert headers["X-Content-Type-Options"] == "nosniff" and "attachment" in headers["Content-Disposition"]
    with wave.open(io.BytesIO(data)) as w:
        assert w.getframerate() == 44100 and abs(w.getnframes() / 44100 - 10) < 0.01
    assert len(data) == tracks[0]["size_bytes"]


def test_account_isolation_for_jobs_tracks_links_and_deletion(make_service):
    client, service = make_service()
    job = client.wait(submit(client)[1]["id"], A)
    track_id = job["result"]["tracks"][0]["id"]
    assert client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=B)[0] == 404
    assert client.call("POST", f"/v1/audio/jobs/{job['id']}/cancel", auth=B)[0] == 404
    assert client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=B)[0] == 404
    assert client.call("DELETE", f"/v1/audio/tracks/{track_id}", auth=B)[0] == 404
    status, deleted, _ = client.call("DELETE", "/v1/audio/account", auth=B)
    assert status == 200 and deleted == {"deleted_tracks": 0, "deleted_jobs": 0}
    assert client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=A)[0] == 200  # still A's


def test_duplicate_submissions_return_the_same_job(make_service):
    client, _ = make_service(start_workers=False)
    results = []
    threads = [threading.Thread(target=lambda: results.append(submit(client))) for _ in range(6)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert {r[1]["id"] for r in results} == {results[0][1]["id"]}
    assert sorted(r[0] for r in results) == [200] * 5 + [202]
    assert submit(client, title="Different")[0] == 409
    assert client.call("POST", "/v1/audio/jobs", body(), auth=A)[0] == 400  # Idempotency-Key required


def test_signed_links_expire_and_cannot_be_forged(make_service):
    clock = LinkClock()
    client, service = make_service(link_clock=clock)
    job = client.wait(submit(client)[1]["id"], A)
    track_id = job["result"]["tracks"][0]["id"]
    link = client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=A)[1]
    assert link["expires_at"] == (int(clock.now) + 600) * 1000  # exactly the configured lifetime (default 10 min)
    path = link["url"][len(service.settings.public_base_url):]
    assert client.fetch(client.base + path)[0] == 200
    # A validly signed link naming another account (as if B tried A's track) exposes nothing.
    foreign, _ = service.signer.sign(track_id, "account-b")
    assert client.fetch(client.base + "/v1/audio/files/" + foreign)[0] == 404
    clock.now += service.settings.download_link_seconds - 1
    assert client.fetch(client.base + path)[0] == 200
    clock.now -= service.settings.download_link_seconds - 1
    token_part = path.rsplit("/", 1)[1]
    forged = token_part[:-3] + ("AAA" if not token_part.endswith("AAA") else "BBB")
    assert client.fetch(client.base + "/v1/audio/files/" + forged)[0] == 403
    assert client.fetch(client.base + "/v1/audio/files/..%2F..%2Fetc%2Fpasswd")[0] == 403
    clock.now += service.settings.download_link_seconds + 1
    status, payload, _ = client.fetch(client.base + path)
    assert status == 410 and b"LINK_EXPIRED" in payload


def test_deleted_track_links_stop_working_and_files_are_removed(make_service):
    client, service = make_service()
    job = client.wait(submit(client)[1]["id"], A)
    track_id = job["result"]["tracks"][0]["id"]
    link = client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=A)[1]
    path = link["url"][len(service.settings.public_base_url):]
    assert client.call("DELETE", f"/v1/audio/tracks/{track_id}", auth=A)[0] == 200
    assert client.fetch(client.base + path)[0] == 404
    refreshed = client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=A)[1]
    assert refreshed["result"]["tracks"][0]["available"] is False
    import os
    files = [f for _, _, names in os.walk(service.settings.storage_root) for f in names]
    assert files == []


def test_account_deletion_removes_songs_and_files(make_service):
    client, service = make_service()
    client.wait(submit(client)[1]["id"], A)
    status, deleted, _ = client.call("DELETE", "/v1/audio/account", auth=A)
    assert status == 200 and deleted == {"deleted_tracks": 1, "deleted_jobs": 1}
    import os
    assert [f for _, _, names in os.walk(service.settings.storage_root) for f in names] == []


def test_cancellation_mid_render_publishes_nothing(make_service):
    client, service = make_service(engine_impl=MockToneEngine(delay_seconds=2.0))
    job = submit(client)[1]
    import time
    deadline = time.time() + 5
    while client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=A)[1]["stage"] != "rendering" and time.time() < deadline:
        time.sleep(0.01)
    status, cancelled, _ = client.call("POST", f"/v1/audio/jobs/{job['id']}/cancel", auth=A)
    assert status == 200 and cancelled["state"] == "cancelled"
    time.sleep(2.5)  # let the worker notice and stop
    final = client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=A)[1]
    assert final["state"] == "cancelled" and final["result"] is None
    with service.db.transaction() as tx:
        assert tx.fetchone("SELECT COUNT(*) FROM audio_objects")[0] == 0
    import os
    assert [f for _, _, names in os.walk(service.settings.storage_root) for f in names] == []
    assert client.call("POST", f"/v1/audio/jobs/{job['id']}/cancel", auth=A)[1]["state"] == "cancelled"  # idempotent


@pytest.mark.parametrize("fault, code", [
    ("silent", "AUDIO_SILENT"), ("clipped", "AUDIO_CLIPPED"), ("short", "AUDIO_DURATION"), ("garbage", "AUDIO_INVALID"),
])
def test_invalid_outputs_are_retried_once_then_fail_without_storing_anything(make_service, fault, code):
    engine = MockToneEngine(fault=fault)
    client, service = make_service(engine_impl=engine)
    job = client.wait(submit(client)[1]["id"], A)
    assert job["state"] == "failed" and job["error"]["code"] == code and job["attempts"] == 2 and engine.calls == 2
    assert "try again" in job["error"]["message"].lower()
    with service.db.transaction() as tx:
        assert tx.fetchone("SELECT COUNT(*) FROM audio_objects")[0] == 0
    import os
    assert [f for _, _, names in os.walk(service.settings.storage_root) for f in names] == []
    assert submit(client, key="key-00000002")[0] == 202  # failed jobs are refunded


def test_transient_failure_is_retried_with_a_new_seed(make_service):
    engine = MockToneEngine(fault="transient", fault_attempts=[1])
    client, _ = make_service(engine_impl=engine)
    job = client.wait(submit(client)[1]["id"], A)
    assert job["state"] == "succeeded" and job["attempts"] == 2
    assert job["result"]["generation"]["attempt"] == 2 and job["result"]["tracks"][0]["seed"] != 1234


@pytest.mark.parametrize("fault, code", [("fatal", "ENGINE_REJECTED"), ("crash", "UNKNOWN")])
def test_permanent_failures_and_crashes_fail_safely(make_service, fault, code):
    client, _ = make_service(engine_impl=MockToneEngine(fault=fault))
    job = client.wait(submit(client)[1]["id"], A)
    assert job["state"] == "failed" and job["error"]["code"] == code and job["attempts"] == 1
    assert "simulated" not in job["error"]["message"]


def test_policy_and_language_rejections_over_http(make_service):
    client, _ = make_service()
    payload = body()
    payload["request"]["reference_audio"] = "https://example.test/voice.wav"
    status, error, _ = client.call("POST", "/v1/audio/jobs", payload, auth=A, key="key-00000009")
    assert status == 422 and error["error"]["code"] == "POLICY_REJECTED"
    assert submit(client, language="yoruba")[1]["error"]["code"] == "UNSUPPORTED_LANGUAGE"
    big = client.call("POST", "/v1/audio/jobs", raw=b"{" + b" " * 70000 + b"}", auth=A, key="key-00000010")
    assert big[0] == 413


def test_logs_never_contain_lyrics_tokens_links_or_storage_paths(make_service, caplog):
    caplog.set_level(logging.INFO, logger="musicme.audio")
    client, service = make_service()
    job = client.wait(submit(client)[1]["id"], A)
    track_id = job["result"]["tracks"][0]["id"]
    link = client.call("POST", f"/v1/audio/tracks/{track_id}/link", auth=A)[1]
    client.fetch(client.base + link["url"][len(service.settings.public_base_url):])
    client.call("DELETE", f"/v1/audio/tracks/{track_id}", auth=A)
    text = caplog.text
    token_part = link["url"].rsplit("/", 1)[1]
    for secret in [LYRIC_LINE, "Ada", A, SECRET, SIGNING, token_part, "account-a", "objects/", service.settings.storage_root, track_id, job["id"]]:
        assert secret not in text, secret
    assert "job_succeeded" in text and "/files/<link>" in text
