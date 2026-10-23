"""Static guardrails for the prepared, never automatically deployed production Blueprint."""
import os


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
PATH = os.path.join(ROOT, "infra", "render", "musicme-production-fal.yaml")


def test_render_production_blueprint_is_isolated_manual_disabled_and_secret_free():
    source = open(PATH).read()
    for required in (
        "name: beatiq-musicme-production", "name: production", "isolation: enabled", "protection: enabled",
        'autoDeployTrigger: "off"', 'generation: "off"', "numInstances: 1", "ipAllowList: []",
        "name: beatiq-musicme-audio-production", "name: beatiq-musicme-audio-production-db",
        "MUSICME_AUDIO_ENGINE\n                value: fal",
        "MUSICME_AUDIO_GENERATION_MODE\n                value: disabled",
        "MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION\n                value: \"false\"",
        "MUSICME_AUDIO_COMMERCIAL_RIGHTS_APPROVED", "MUSICME_AUDIO_SECURITY_ACCEPTED",
        "MUSICME_AUDIO_PUBLIC_ACTIVATION_APPROVED", "FAL_KEY", "MUSICME_AUDIO_FAL_DAILY_BUDGET_USD",
        "MUSICME_AUDIO_STORAGE_BACKEND", "MUSICME_AUDIO_OBJECT_ENDPOINT", "MUSICME_AUDIO_OBJECT_BUCKET",
        "MUSICME_AUDIO_OBJECT_FORCE_PATH_STYLE", "healthCheckPath: /readyz",
        'buildCommand: "python3 -m pip install --require-hashes --only-binary=:all: -r requirements.lock"',
        'MUSICME_AUDIO_RENDER_TIMEOUT_SECONDS\n                value: "600"',
        'MUSICME_AUDIO_MAX_ATTEMPTS\n                value: "1"',
    ):
        assert required in source
    assert "musicme-dev-auth" not in source and "remote_gpu" not in source and "modal.run" not in source
    assert "BioAegix" not in source and "bioaegix" not in source
    assert "sk-" not in source and "fal-key" not in source and "Bearer " not in source
    assert source.count("sync: false") >= 10 and source.count("generateValue: true") == 1


def test_every_blueprint_build_and_start_path_exists():
    service = os.path.join(ROOT, "services", "musicme-audio")
    assert os.path.isfile(os.path.join(service, "requirements.lock"))
    assert os.path.isfile(os.path.join(service, "audioservice", "server.py"))
    assert os.path.isdir(os.path.join(service, "audioservice", "migrations"))
