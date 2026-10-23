"""Parse the production Blueprint and feed its effective settings through the real loader."""
import os

import pytest

from audioservice.config import load_settings

yaml = pytest.importorskip("yaml")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
PATH = os.path.join(ROOT, "infra", "render", "musicme-production-fal.yaml")
SECRET_WORDS = ("SECRET", "TOKEN", "PASSWORD", "FAL_KEY", "ACCESS_KEY", "CREDENTIALS", "DATABASE_URL")
SOURCES = ("value", "fromDatabase", "fromService", "sync", "generateValue")
PLACEHOLDERS = {
    "FAL_KEY": "placeholder-fal-key-at-least-32-characters",
    "MUSICME_AUDIO_OBJECT_ENDPOINT": "https://placeholder.eu.r2.cloudflarestorage.com",
    "MUSICME_AUDIO_OBJECT_BUCKET": "beatiq-musicme-production-placeholder",
    "MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID": "placeholder-access-key",
    "MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY": "placeholder-secret-access-key",
    "MUSICME_AUDIO_PUBLIC_BASE_URL": "https://beatiq-musicme-audio.example.onrender.com",
    "MUSICME_AUDIO_JWKS_URL": "https://auth.example.test/.well-known/jwks.json",
    "MUSICME_AUDIO_JWT_ISSUER": "https://auth.example.test",
    "MUSICME_AUDIO_JWT_AUDIENCE": "musicme-audio-production",
    "MUSICME_AUDIO_INTERNAL_TEST_USER_IDS": "stable-test-user",
    "MUSICME_AUDIO_PUBLIC_ELIGIBLE_USER_IDS": "stable-public-user",
}


def load():
    with open(PATH) as source:
        return yaml.safe_load(source)


def audio_service(doc):
    environment = doc["projects"][0]["environments"][0]
    return environment, environment["services"][0]


def env_for(service):
    env = {}
    for var in service["envVars"]:
        key = var["key"]
        if "value" in var:
            env[key] = str(var["value"])
        elif "fromDatabase" in var:
            env[key] = "postgresql://user:placeholder@db.internal/musicme"
        elif var.get("generateValue"):
            env[key] = (key * 4)[:44]
        else:
            env[key] = PLACEHOLDERS[key]
    return env


def test_blueprint_is_structurally_safe_and_uses_no_literal_secrets():
    doc = load()
    assert doc["previews"]["generation"] == "off"
    environment, service = audio_service(doc)
    assert environment["networking"]["isolation"] == "enabled"
    assert environment["permissions"]["protection"] == "enabled"
    assert service["autoDeployTrigger"] == "off" and service["numInstances"] == 1
    assert service["region"] == "frankfurt" and service["healthCheckPath"] == "/readyz"
    keys = [var["key"] for var in service["envVars"]]
    assert len(keys) == len(set(keys))
    for var in service["envVars"]:
        assert sum(source in var for source in SOURCES) == 1, var["key"]
        if any(word in var["key"] for word in SECRET_WORDS):
            assert "value" not in var, f"literal secret value for {var['key']}"
    database = environment["databases"][0]
    assert database["ipAllowList"] == [] and database["storageAutoscalingEnabled"] is False


def test_disabled_production_environment_is_accepted_and_remains_fail_closed():
    _, service = audio_service(load())
    configured = load_settings(env_for(service))
    assert configured.engine == "fal" and configured.environment == "production"
    assert configured.generation_mode == "disabled" and not configured.enable_production_generation
    assert not configured.generation_allowed("stable-test-user")
    assert configured.storage_backend == "s3" and configured.object_force_path_style
    assert configured.object_endpoint.endswith(".eu.r2.cloudflarestorage.com")
    assert configured.render_timeout_seconds == 600 and configured.max_attempts == 1
    assert configured.fal_daily_budget_usd == 2.0 and configured.max_jobs_per_window == 3


def test_internal_test_requires_both_explicit_server_gate_and_stable_allowlist():
    _, service = audio_service(load())
    env = env_for(service)
    env.update({"MUSICME_AUDIO_GENERATION_MODE": "internal_test",
                "MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION": "true",
                "MUSICME_AUDIO_INTERNAL_TEST_USER_IDS": "stable-test-user"})
    configured = load_settings(env)
    assert configured.generation_allowed("stable-test-user")
    assert not configured.generation_allowed("someone-else")
