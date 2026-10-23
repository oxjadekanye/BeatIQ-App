"""Static guardrails for the prepared (never automatically deployed) Render Blueprints."""
import os


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))


def test_render_control_plane_is_isolated_protected_manual_and_has_no_literal_secrets():
    path = os.path.join(ROOT, "infra", "render", "musicme-dev.yaml")
    source = open(path).read()
    for required in (
        "name: beatiq-musicme", "name: samsung-dev", "isolation: enabled", "protection: enabled",
        'autoDeployTrigger: "off"', 'generation: "off"', "numInstances: 1", "ipAllowList: []",
        "MUSICME_AUDIO_ENGINE", "value: remote_gpu", "MUSICME_AUDIO_WORKER_CREDENTIALS_JSON",
        "MUSICME_AUDIO_GPU_DAILY_BUDGET_USD", "MUSICME_AUDIO_GPU_MAX_RENDERS_PER_DAY",
        "MUSICME_AUDIO_JWT_REVOCATION_URL", "MUSICME_DEV_AUTH_INTERNAL_TOKEN",
        "MUSICME_AUDIO_STORAGE_BACKEND", "MUSICME_AUDIO_OBJECT_ENDPOINT", "MUSICME_AUDIO_OBJECT_BUCKET",
        "MUSICME_AUDIO_GPU_LEDGER_BACKEND", "healthCheckPath: /readyz",
        'buildCommand: "python3 -m pip install --require-hashes --only-binary=:all: -r requirements.lock"',
        'MUSICME_AUDIO_RENDER_TIMEOUT_SECONDS\n                value: "600"',
        'MUSICME_AUDIO_WORKER_STARTUP_TIMEOUT_SECONDS\n                value: "600"',
    ):
        assert required in source
    assert "BioAegix" not in source and "bioaegix" not in source
    assert "sk-" not in source and "hf_" not in source and "Bearer " not in source
    assert source.count("sync: false") >= 4 and source.count("generateValue: true") >= 3
    audio = source[source.index("name: beatiq-musicme-audio-dev"):]
    assert "musicme-dev-audio-data" not in audio  # completed songs and cost state are no longer instance-local
