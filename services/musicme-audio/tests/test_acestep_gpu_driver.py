"""The Phase 2.3F real-GPU driver, exercised locally against the fake runner (the probe needs real ACE-Step)."""
import json
import os
import sys

import pytest

from audioservice import engines
from test_acestep_adapter import make_runner

TOOLS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools")
sys.path.insert(0, TOOLS)
import acestep_gpu_driver as driver  # noqa: E402


def run(tmp_path, mode, skip="probe"):
    runner, _ = make_runner(tmp_path, mode)
    home = tmp_path / "acestep-home"
    (home / "checkpoints").mkdir(parents=True, exist_ok=True)
    out = tmp_path / "gpu-test"
    code = driver.main(["--python", sys.executable, "--home", str(home), "--runner", runner, "--out", str(out),
                        "--skip", skip, "--startup-timeout", "15", "--render-timeout", "30"])
    report = json.load(open(out / "report.json"))
    return code, report, out, home


def test_driver_uses_the_engines_exact_runner_environment():
    assert driver.RUNNER_ENV_ALLOWLIST == engines.RUNNER_ENV_ALLOWLIST
    assert driver.RUNNER_ENV_FORCED == engines.RUNNER_ENV_FORCED


def test_probe_script_compiles_and_covers_every_download_route():
    compile(driver.PROBE, "probe", "exec")
    for route in ("huggingface_hub.snapshot_download", "huggingface_hub.hf_hub_download", "ensure_main_model",
                  "ensure_dit_model", "ensure_vae_model", "_download_from_huggingface_internal", "modelscope",
                  "changed_upstream_refused", "precheck_missing_vae_blocked", "presence_check_rejects_empty_dir"):
        assert route in driver.PROBE


def test_full_sequence_passes_against_a_conforming_runner(tmp_path):
    code, report, out, home = run(tmp_path, "ok")
    sections = {c["section"] for c in report["checks"]}
    assert code == 0 and report["passed"] is True, [c for c in report["checks"] if not c["passed"]]
    assert sections == {"negative", "startup", "params", "render", "kill", "restart"}
    names = {c["check"] for c in report["checks"]}
    assert {"tampered manifest refused", "non-cuda device refused", "wrong protocol version refused",
            "garbage input refused", "LM planning weights present refused", "float16 lyric-encoder fix applied",
            "GPU self-test passed (all sub-checks)", "lyric encoder output finite",
            "WAV passes validation (format, 22.5-37.5 s, not silent, <=1% clipped)", "runner process group terminated",
            "fresh runner re-verified and loaded after the kill", "clean shutdown"} <= names
    assert len([c for c in report["checks"] if c["section"] == "params"]) == 6
    assert report["real_audio"] is None and report["wav_validation"]["passed"] is True
    assert os.path.exists(out / "smoke-2-3f-20261021.wav") and not os.path.exists(out / "staging")
    assert not os.path.exists(home / "checkpoints" / "acestep-5Hz-lm-1.7B")  # the test folder it created is removed
    text = json.dumps(report)
    assert "Morning light" not in text and "kitchen floor" not in text  # lyrics only as a hash
    assert set(report["uploaded_modules"]) == {"__init__.py", "manifest.json", "manifest.py", "protocol.py", "fp16_fix.py", "runner.py"}


@pytest.mark.parametrize("mode, failing", [("selftest_fail", "runner started and sent hello"),
                                           ("bad_hashes", "runner started and sent hello"),
                                           ("nan", "render completed"),
                                           ("silent_wav", "WAV passes validation (format, 22.5-37.5 s, not silent, <=1% clipped)")])
def test_driver_reports_failures(tmp_path, mode, failing):
    code, report, _, _ = run(tmp_path, mode, skip="probe,negative,params,kill,restart")
    failed = {c["check"] for c in report["checks"] if not c["passed"]}
    assert code == 1 and report["passed"] is False
    if mode == "bad_hashes":  # the fake still says hello; the service-side handshake check must catch it
        assert "handshake matches the pinned manifest (service-side check)" in failed
        assert "all pinned file hashes verified" in failed
    elif mode == "selftest_fail":
        assert "handshake matches the pinned manifest (service-side check)" in failed
        assert "GPU self-test passed (all sub-checks)" in failed
    else:
        assert failing in failed
