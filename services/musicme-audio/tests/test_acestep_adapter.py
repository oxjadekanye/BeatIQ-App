"""Phase 2.3F: the real ACE-Step engine, exercised with a fake runner (no GPU, torch or model weights needed)."""
import ast
import dataclasses
import hashlib
import json
import logging
import os
import sys
import time

import pytest

from conftest import A, LYRIC_LINE, SECRET, SIGNING, body, settings
from audioservice.acestep import manifest as pinned
from audioservice.acestep import protocol
from audioservice.contract import parse_request
from audioservice.engines import (AceStepEngine, Cancelled, EngineError, EngineUnavailable, RUNNER_ENV_ALLOWLIST,
                                  RUNNER_ENV_FORCED, build_render_request, engine_from_settings)

TESTS = os.path.dirname(os.path.abspath(__file__))
SERVICE_ROOT = os.path.dirname(TESTS)
FAKE = os.path.join(TESTS, "fake_acestep_runner.py")
PACKAGE = os.path.join(SERVICE_ROOT, "audioservice", "acestep")


def make_runner(tmp_path, mode):
    record = tmp_path / f"record-{mode}"
    record.mkdir(exist_ok=True)
    runner = tmp_path / f"runner_{mode}.py"
    runner.write_text(f"MODE = {mode!r}\nRECORD_DIR = {str(record)!r}\nimport sys\nsys.path.insert(0, {SERVICE_ROOT!r})\n"
                      + open(FAKE).read())
    return str(runner), record


def ace_settings(tmp_path, mode="ok", **overrides):
    runner, record = make_runner(tmp_path, mode)
    home = tmp_path / "acestep-home"
    home.mkdir(exist_ok=True)
    values = dict(engine="ace_step", workers=1, acestep_python=sys.executable, acestep_home=str(home),
                  acestep_runner=runner, acestep_startup_timeout_seconds=10, render_timeout_seconds=20)
    values.update(overrides)
    return settings(**values), record


def fast_startup(s, seconds=1):
    copy = dataclasses.replace(s)
    object.__setattr__(copy, "acestep_startup_timeout_seconds", seconds)
    return copy


def render_request(seed=11, **overrides):
    return build_render_request(parse_request(body(**overrides), settings()), seed)


def out_path(tmp_path, name="take"):
    directory = tmp_path / "staging"
    directory.mkdir(exist_ok=True)
    return str(directory / f".tmp-{name}")


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


@pytest.fixture
def engines():
    created = []

    def make(s, **kwargs):
        engine = AceStepEngine(s, **kwargs)
        created.append(engine)
        return engine

    yield make
    for engine in created:
        engine.close()


# ---- pins and validated code carried over from Phase 2.3E ------------------------------------------------------

def test_manifest_contains_the_complete_audited_pin_set():
    manifest = pinned.load()
    assert manifest["code"]["commit"] == "dce621408bee8c31b4fcf4811682eb9359e1bc94"
    assert manifest["weights"]["revision"] == "19671f406d603126926c1b7e2adc169acbcade22"
    assert len(manifest["weights"]["files"]) == 8
    assert all(len(checksum) == 64 and all(c in "0123456789abcdef" for c in checksum)
               for checksum in manifest["weights"]["files"].values())
    assert manifest["weights"]["config_path"] == "acestep-v15-turbo" and manifest["limits"]["inference_steps"] == 8
    assert set(manifest["weights"]["forbidden_dirs"]) >= {"acestep-5Hz-lm-1.7B", "acestep-5Hz-lm-0.6B"}


def test_fp16_fix_is_the_audited_deployment_snapshot():
    names = ("Float32Linear", "_cast_to_weight_dtype", "_last_down_proj", "compute_lyric_encoder_tail_in_float32",
             "fp16_fix_selftest", "install_diagnostics")
    source = open(os.path.join(PACKAGE, "fp16_fix.py"), "rb").read()
    assert hashlib.sha256(source).hexdigest() == "e90c2ab0f0ebdca69e2d8b276dc2e3d78e699eda852673379a6a64b21d2da86d"
    module = {n.name: ast.dump(n) for n in ast.parse(source.decode()).body
              if getattr(n, "name", None) in names}
    assert set(module) == set(names)


def test_runner_keeps_the_validated_safeguards():
    runner = open(os.path.join(PACKAGE, "runner.py")).read()
    compile(runner, "runner.py", "exec")
    for needle in ('"MPLBACKEND": "Agg"', '"HF_HUB_OFFLINE": "1"', "os.dup2(2, 1)", "pinned.verify_checkpoints(",
                   "pinned.read_git_head(home)", 'fatal("SELFTEST_FAILED"', 'fatal("FIX_NOT_APPLICABLE"',
                   'fatal("DOWNLOAD_PATH_CHANGED"', "generate_music(dit, None, generation, config, save_dir=None)",
                   "reference_audio=None, src_audio=None", "thinking=False", "O_EXCL", "O_NOFOLLOW",
                   'init["manifest"] != manifest', "scrub("):
        assert needle in runner, needle
    assert runner.index("pinned.verify_checkpoints(") < runner.index("initialize_service(")
    assert runner.index("disable_downloads(checkpoints)") < runner.index("from acestep.handler import")
    tree = ast.parse(runner)
    printed = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and getattr(n.func, "id", "") == "print"]
    assert printed == []  # the runner never prints; only protocol messages leave it


# ---- protocol ------------------------------------------------------------------------------------------------

def _render_message():
    return {"type": "render", "id": "a" * 32, "out_path": "/tmp/.tmp-x", "params": {
        "caption": "c", "lyrics": "[Verse]\nla", "instrumental": False, "vocal_language": "en", "bpm": 96,
        "duration": 30, "seed": 1, "inference_steps": 8, "timesignature": "4"}}


def test_protocol_round_trip_and_strict_validation():
    message = _render_message()
    assert protocol.decode(protocol.encode(message, "to_runner").rstrip(b"\n"), "to_runner") == message
    bad = [
        ({"type": "launch"}, "to_runner"),
        (dict(_render_message(), extra=1), "to_runner"),
        ({**_render_message(), "params": {**_render_message()["params"], "reference_audio": "x.wav"}}, "to_runner"),
        ({**_render_message(), "params": {**_render_message()["params"], "seed": True}}, "to_runner"),
        ({**_render_message(), "params": {**_render_message()["params"], "duration": "30"}}, "to_runner"),
        (_render_message(), "from_runner"),
        ({"type": "progress", "id": "a", "fraction": 1.5}, "from_runner"),
        ({"type": "error", "id": "a", "code": "SOMETHING"}, "from_runner"),
        ({"type": "fatal", "code": "NOPE"}, "from_runner"),
        ([1, 2], "from_runner"),
    ]
    for message, direction in bad:
        with pytest.raises(protocol.ProtocolError):
            protocol.validate(message, direction)
    with pytest.raises(protocol.ProtocolError):
        protocol.decode(b"x" * (protocol.MAX_LINE_BYTES + 1), "from_runner")
    with pytest.raises(protocol.ProtocolError):
        protocol.decode(b"{not json", "from_runner")


# ---- manifest verification -----------------------------------------------------------------------------------

def _fake_manifest(tmp_path):
    ckpt = tmp_path / "checkpoints"
    (ckpt / "acestep-v15-turbo").mkdir(parents=True)
    (ckpt / "vae").mkdir()
    files = {"acestep-v15-turbo/model.safetensors": b"dit", "vae/diffusion_pytorch_model.safetensors": b"vae"}
    for rel, content in files.items():
        (ckpt / rel).write_bytes(content)
    manifest = json.loads(json.dumps(pinned.load()))
    manifest["weights"]["files"] = {rel: hashlib.sha256(c).hexdigest() for rel, c in files.items()}
    return ckpt, manifest


def test_checkpoint_verification_rejects_mismatches_missing_files_and_lm_weights(tmp_path):
    ckpt, manifest = _fake_manifest(tmp_path)
    assert pinned.verify_checkpoints(str(ckpt), manifest) == manifest["weights"]["files"]
    (ckpt / "vae" / "diffusion_pytorch_model.safetensors").write_bytes(b"tampered")
    with pytest.raises(pinned.ManifestError) as error:
        pinned.verify_checkpoints(str(ckpt), manifest)
    assert error.value.code == "MANIFEST_MISMATCH"
    (ckpt / "vae" / "diffusion_pytorch_model.safetensors").write_bytes(b"vae")
    (ckpt / "acestep-5Hz-lm-1.7B").mkdir()
    with pytest.raises(pinned.ManifestError) as error:
        pinned.verify_checkpoints(str(ckpt), manifest)
    assert error.value.code == "FORBIDDEN_WEIGHTS"
    (ckpt / "acestep-5Hz-lm-1.7B").rmdir()
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"dit")
    os.remove(ckpt / "acestep-v15-turbo" / "model.safetensors")
    os.symlink(outside, ckpt / "acestep-v15-turbo" / "model.safetensors")
    with pytest.raises(pinned.ManifestError):
        pinned.verify_checkpoints(str(ckpt), manifest)  # a symlink out of the checkpoint tree is not accepted


def test_git_head_resolution_detached_loose_and_packed(tmp_path):
    git = tmp_path / ".git"
    (git / "refs" / "heads").mkdir(parents=True)
    commit = pinned.load()["code"]["commit"]
    (git / "HEAD").write_text(commit + "\n")
    assert pinned.read_git_head(str(tmp_path)) == commit
    (git / "HEAD").write_text("ref: refs/heads/main\n")
    (git / "refs" / "heads" / "main").write_text(commit + "\n")
    assert pinned.read_git_head(str(tmp_path)) == commit
    os.remove(git / "refs" / "heads" / "main")
    (git / "packed-refs").write_text(f"# pack-refs\n{commit} refs/heads/main\n")
    assert pinned.read_git_head(str(tmp_path)) == commit


# ---- handshake -------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("mode, code", [
    ("bad_commit", "CODE_MISMATCH"), ("bad_hashes", "MANIFEST_MISMATCH"), ("bad_config", "INVALID_MODEL_CONFIG"),
    ("selftest_fail", "SELFTEST_FAILED"), ("fp16_without_fix", "SELFTEST_FAILED"), ("float32", "UNSUPPORTED_DTYPE"),
    ("fatal_manifest", "MANIFEST_MISMATCH"), ("crash_start", "RUNNER_EXITED"), ("garbage_hello", "PROTOCOL"),
])
def test_handshake_rejects_unverified_runners(tmp_path, mode, code):
    s, _ = ace_settings(tmp_path, mode)
    with pytest.raises(EngineUnavailable, match=code):
        AceStepEngine(s)
    with pytest.raises(SystemExit, match=code):
        engine_from_settings(s)  # the service refuses to start


def test_handshake_times_out_and_kills_a_silent_runner(tmp_path):
    s, record = ace_settings(tmp_path, "silent_start")
    started = time.monotonic()
    with pytest.raises(EngineUnavailable, match="STARTUP_TIMEOUT"):
        AceStepEngine(fast_startup(s))
    assert time.monotonic() - started < 8


@pytest.mark.parametrize("mode, dtype, fix", [("ok", "torch.float16", "applied"), ("bfloat16", "torch.bfloat16", "not_needed"),
                                              ("print_noise", "torch.float16", "applied")])
def test_handshake_accepts_verified_runners(tmp_path, engines, mode, dtype, fix):
    engine = engines(ace_settings(tmp_path, mode)[0])
    assert engine.hello["dtype"] == dtype and engine.hello["fp16_fix"] == fix
    assert engine.model_version == "acestep-v15-turbo" and engine.model_revision.startswith("code dce621408bee")


# ---- security boundaries ---------------------------------------------------------------------------------------

def test_runner_gets_no_secrets_arguments_or_lyrics_outside_stdin(tmp_path, engines, monkeypatch):
    for name, value in {"MUSICME_AUDIO_JWT_SECRET": SECRET, "MUSICME_AUDIO_SIGNING_SECRET": SIGNING,
                        "HF_TOKEN": "hf_supersecrettoken1234567890", "AWS_SECRET_ACCESS_KEY": "aws-secret",
                        "MUSICME_AUDIO_DATABASE_URL": "postgresql://user:pw@db/audio", "PYTHONPATH": "/evil",
                        "MPLBACKEND": "module://matplotlib_inline.backend_inline"}.items():
        monkeypatch.setenv(name, value)
    s, record = ace_settings(tmp_path, "ok")
    engine = engines(s)
    request = render_request()
    engine.render(request, out_path(tmp_path), lambda f: True, time.monotonic() + 10)
    startup = json.load(open(record / "startup.json"))
    assert startup["argv"] == [s.acestep_runner]
    assert set(startup["env"]) <= set(RUNNER_ENV_ALLOWLIST) | set(RUNNER_ENV_FORCED) | {"__CF_USER_TEXT_ENCODING", "LC_CTYPE"}
    values = json.dumps(startup["env_values"])
    for secret in (SECRET, SIGNING, "hf_supersecrettoken", "aws-secret", "postgresql://", "/evil", "matplotlib_inline"):
        assert secret not in values
    assert startup["env_values"]["MPLBACKEND"] == "Agg" and startup["env_values"]["HF_HUB_OFFLINE"] == "1"
    assert LYRIC_LINE not in json.dumps(startup)
    rendered = json.load(open(record / "render-1.json"))
    assert rendered["lyrics_sha256"] == hashlib.sha256(request.tagged_lyrics().encode()).hexdigest()  # arrived via stdin


def test_full_job_publishes_validated_real_audio_and_logs_no_lyrics(make_service, tmp_path, caplog):
    caplog.set_level(logging.INFO, logger="musicme.audio")
    s, record = ace_settings(tmp_path, "ok")
    client, service = make_service(**{f.name: getattr(s, f.name) for f in dataclasses.fields(s)
                                      if f.name.startswith("acestep_") or f.name in ("engine", "workers", "render_timeout_seconds")})
    caps = client.call("GET", "/v1/audio/capabilities", auth=A)[1]
    assert caps["engine"] == "ace_step" and caps["real_audio"] is True and caps["max_takes"] == 1
    assert caps["max_lyric_chars"] == 4096 and caps["max_caption_chars"] == 512
    job = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-ace-0001")[1]
    done = client.wait(job["id"], A, timeout=30)
    assert done["state"] == "succeeded", done
    generation = done["result"]["generation"]
    assert generation["engine"] == "ace_step" and generation["mock"] is False and generation["real_audio"] is True
    assert generation["model_id"] == "ace-step-1.5" and generation["model_version"] == "acestep-v15-turbo"
    assert generation["engine_details"]["fp16_lyric_encoder_fix"] == "applied"
    assert generation["engine_details"]["diagnostics"]["lyric_encoder_output_finite"] is True
    track = done["result"]["tracks"][0]
    assert track["sample_rate"] == 48000 and track["channels"] == 2 and abs(track["duration_ms"] - 10000) < 5
    assert LYRIC_LINE not in caplog.text and "Ada" not in caplog.text and SECRET not in caplog.text
    assert json.dumps(generation).find("Ada") == -1


# ---- request limits --------------------------------------------------------------------------------------------

@pytest.mark.parametrize("overrides, fragment", [
    (dict(takes=2), "at most 1 take"),
    (dict(sections=[{"id": f"v{i}", "type": "verse", "label": "Verse", "lyrics": "\n".join([("la " * 15) + "la"] * 24)} for i in range(4)]),
     "lyrics are too long"),
    (dict(style={"genres": ["G" * 39 + str(i) for i in range(3)], "moods": ["M" * 39 + str(i) for i in range(3)], "bpm": 96,
                 "instrumentation": ["I" * 39 + str(i) for i in range(8)]}), "Too many style details"),
])
def test_engine_limits_are_enforced_at_submit(make_service, tmp_path, overrides, fragment):
    s, _ = ace_settings(tmp_path, "ok")
    client, _ = make_service(engine="ace_step", workers=1, acestep_python=s.acestep_python, acestep_home=s.acestep_home,
                             acestep_runner=s.acestep_runner, acestep_startup_timeout_seconds=10)
    status, payload, _ = client.call("POST", "/v1/audio/jobs", body(**overrides), auth=A, key="key-limit-001")
    assert status == 422 and payload["error"]["code"] == "INVALID_REQUEST" and fragment in payload["error"]["message"]


def test_mapping_matches_the_parameters_validated_in_phase_2_3e(tmp_path, engines):
    engine = engines(ace_settings(tmp_path, "ok")[0], start=False)
    params = engine.pipeline_parameters(render_request(seed=20261021))
    assert params["inference_steps"] == 8 and params["timesignature"] == "4" and params["vocal_language"] == "en"
    assert params["seed"] == 20261021 and params["instrumental"] is False and params["lyrics"].startswith("[Intro]")
    sections = [{"id": "i", "type": "intro", "label": "Intro", "lyrics": ""}, {"id": "b", "type": "instrumental", "label": "B", "lyrics": ""}]
    instrumental = engine.pipeline_parameters(render_request(sections=sections, vocals={"type": "none", "descriptors": []}))
    assert instrumental["instrumental"] is True and instrumental["lyrics"] == "[Instrumental]"
    protocol.validate({"type": "render", "id": "x", "params": params, "out_path": "/tmp/.tmp-x"}, "to_runner")


# ---- failures, NaN policy, invalid outputs ---------------------------------------------------------------------

def _service_for(make_service, tmp_path, mode, **extra):
    s, record = ace_settings(tmp_path, mode, **extra)
    client, service = make_service(engine="ace_step", workers=1, acestep_python=s.acestep_python,
                                   acestep_home=s.acestep_home, acestep_runner=s.acestep_runner,
                                   acestep_startup_timeout_seconds=10, render_timeout_seconds=s.render_timeout_seconds,
                                   **{k: v for k, v in extra.items() if k == "acestep_retry_nan"})
    return client, service, record


def _no_objects(service):
    with service.db.transaction() as tx:
        rows = tx.fetchone("SELECT COUNT(*) FROM audio_objects")[0]
    files = [f for _, _, names in os.walk(service.settings.storage_root) for f in names]
    return rows == 0 and files == []


def test_nan_is_not_retried_by_default(make_service, tmp_path):
    client, service, record = _service_for(make_service, tmp_path, "nan")
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-nan-0001")[1]["id"], A, timeout=30)
    assert job["state"] == "failed" and job["error"]["code"] == "AUDIO_INVALID" and job["attempts"] == 1
    assert not (record / "render-2.json").exists() and _no_objects(service)
    assert "NaN" not in job["error"]["message"]  # friendly message only


def test_nan_retry_only_when_explicitly_enabled(make_service, tmp_path):
    client, service, record = _service_for(make_service, tmp_path, "nan_once", acestep_retry_nan=True)
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-nan-0002")[1]["id"], A, timeout=30)
    assert job["state"] == "succeeded" and job["attempts"] == 2
    assert job["result"]["tracks"][0]["seed"] != 1234  # the retry used a fresh seed


@pytest.mark.parametrize("mode, code", [("garbage_wav", "AUDIO_INVALID"), ("silent_wav", "AUDIO_SILENT"),
                                        ("short_wav", "AUDIO_DURATION")])
def test_invalid_runner_wavs_are_never_published(make_service, tmp_path, mode, code):
    client, service, _ = _service_for(make_service, tmp_path, mode)
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-wav-0001")[1]["id"], A, timeout=40)
    assert job["state"] == "failed" and job["error"]["code"] == code and job["result"] is None
    assert _no_objects(service)


@pytest.mark.parametrize("mode", ["symlink_out", "no_file"])
def test_missing_or_symlinked_outputs_fail_without_following_links(make_service, tmp_path, mode):
    client, service, _ = _service_for(make_service, tmp_path, mode)
    hosts_before = open("/etc/hosts", "rb").read()
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-out-0001")[1]["id"], A, timeout=30)
    assert job["state"] == "failed" and job["error"]["code"] == "ENGINE_FAILED" and _no_objects(service)
    assert open("/etc/hosts", "rb").read() == hosts_before


def test_runner_crash_is_retried_once_with_a_fresh_verified_runner(make_service, tmp_path):
    client, service, record = _service_for(make_service, tmp_path, "crash_render")
    job = client.wait(client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-crash-001")[1]["id"], A, timeout=40)
    assert job["state"] == "failed" and job["error"]["code"] == "ENGINE_UNAVAILABLE" and job["attempts"] == 2
    assert _no_objects(service)


@pytest.mark.parametrize("mode", ["wrong_id", "oversize", "oom"])
def test_protocol_violations_and_oom_kill_the_runner(tmp_path, engines, mode):
    engine = engines(ace_settings(tmp_path, mode)[0])
    pid = engine._proc.pid
    with pytest.raises(EngineError) as error:
        engine.render(render_request(), out_path(tmp_path), lambda f: True, time.monotonic() + 10)
    assert error.value.code == "ENGINE_UNAVAILABLE" and error.value.transient
    assert engine._proc is None
    time.sleep(0.2)
    assert not alive(pid)


# ---- deadlines, heartbeats, cancellation ------------------------------------------------------------------------

@pytest.mark.parametrize("mode", ["hang", "hang_ignore_term"])
def test_deadline_kills_the_runner_process_group(tmp_path, engines, mode):
    engine = engines(ace_settings(tmp_path, mode)[0])
    pid = engine._proc.pid
    started = time.monotonic()
    with pytest.raises(EngineError) as error:
        engine.render(render_request(), out_path(tmp_path), lambda f: True, time.monotonic() + 1.5)
    assert error.value.code == "TIMEOUT" and error.value.transient
    assert time.monotonic() - started < 10 and not alive(pid)


def test_heartbeats_continue_while_the_runner_is_silent(tmp_path, engines):
    engine = engines(ace_settings(tmp_path, "silent_slow")[0])
    beats = []
    result = engine.render(render_request(), out_path(tmp_path), lambda f: beats.append(time.monotonic()) or True,
                           time.monotonic() + 15)
    assert result.format == "wav" and len(beats) >= 3  # at least every 2 s across a 5 s silence


def test_cancellation_mid_render_kills_the_runner_and_the_next_render_restarts_it(tmp_path, engines):
    engine = engines(ace_settings(tmp_path, "slow")[0])
    pid = engine._proc.pid
    calls = []
    with pytest.raises(Cancelled):
        engine.render(render_request(), out_path(tmp_path, "a"), lambda f: calls.append(f) or len(calls) < 3,
                      time.monotonic() + 15)
    time.sleep(0.2)
    assert not alive(pid) and engine._proc is None
    result = engine.render(render_request(), out_path(tmp_path, "b"), lambda f: True, time.monotonic() + 15)
    assert result.sample_rate == 48000 and engine._proc.pid != pid and engine.hello["ok"] is True


def test_cancelling_a_job_through_the_api_publishes_nothing(make_service, tmp_path):
    client, service, _ = _service_for(make_service, tmp_path, "slow")
    job = client.call("POST", "/v1/audio/jobs", body(), auth=A, key="key-cancel-01")[1]
    deadline = time.time() + 10
    while client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=A)[1]["stage"] != "rendering" and time.time() < deadline:
        time.sleep(0.05)
    assert client.call("POST", f"/v1/audio/jobs/{job['id']}/cancel", auth=A)[1]["state"] == "cancelled"
    time.sleep(3.5)
    final = client.call("GET", f"/v1/audio/jobs/{job['id']}", auth=A)[1]
    assert final["state"] == "cancelled" and final["result"] is None and _no_objects(service)


def test_close_shuts_the_runner_down_cleanly(tmp_path):
    s, record = ace_settings(tmp_path, "ok")
    engine = AceStepEngine(s)
    pid = engine._proc.pid
    engine.close()
    time.sleep(0.2)
    assert json.load(open(record / "shutdown.json"))["clean"] is True and not alive(pid)


# ---- configuration and fail-closed production ---------------------------------------------------------------------

def test_configuration_rules_and_environment_loading(tmp_path):
    from audioservice.config import Settings, load_settings
    with pytest.raises(ValueError, match="cuda"):
        settings(engine="ace_step", workers=1, acestep_python=sys.executable, acestep_home=str(tmp_path), acestep_device="cpu")
    production = dict(environment="production", engine="ace_step", workers=1, acestep_python=sys.executable,
                      acestep_home=str(tmp_path), jwt_secret=SECRET, signing_secret=SIGNING,
                      database_url="postgresql://db/audio", jwt_issuer="https://auth.test", jwt_audience="audio",
                      allow_shared_secret_in_production=True, public_base_url="https://audio.test")
    with pytest.raises(ValueError, match="Production generation is disabled"):
        Settings(**production)  # the kill switch refuses every engine first
    with pytest.raises(ValueError, match="object-store"):
        Settings(**production, enable_production_generation=True)  # and local storage is still refused after it
    loaded = load_settings({"MUSICME_AUDIO_JWT_SECRET": SECRET, "MUSICME_AUDIO_SIGNING_SECRET": SIGNING,
                            "MUSICME_AUDIO_ENGINE": "ace_step", "MUSICME_AUDIO_WORKERS": "1",
                            "MUSICME_AUDIO_ACESTEP_PYTHON": sys.executable, "MUSICME_AUDIO_ACESTEP_HOME": str(tmp_path)})
    assert loaded.engine == "ace_step" and loaded.acestep_retry_nan is False and loaded.acestep_device == "cuda"
    assert repr(loaded).find(SECRET) == -1
