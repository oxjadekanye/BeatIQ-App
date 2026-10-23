"""Structural checks for the isolated Render DEV Blueprint and the real configuration loader."""
import os

import pytest

from audioservice.config import load_settings

yaml = pytest.importorskip("yaml")  # test-only dependency; the services themselves never parse YAML

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
RENDER = os.path.join(ROOT, "infra", "render")
BLUEPRINTS = ("musicme-dev.yaml",)
SECRET_WORDS = ("SECRET", "TOKEN", "PASSWORD", "API_KEY", "ACCESS_KEY", "CREDENTIALS", "DATABASE_URL")
SOURCES = ("value", "fromDatabase", "fromService", "sync", "generateValue")

# Placeholders standing in for dashboard-entered (sync: false) and Render-generated values. None are real.
PLACEHOLDERS = {
    "MUSICME_AUDIO_OBJECT_ENDPOINT": "https://account.r2.cloudflarestorage.com",
    "MUSICME_AUDIO_OBJECT_BUCKET": "beatiq-musicme-placeholder",
    "MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID": "placeholder-access-key",
    "MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY": "placeholder-secret-access-key",
    "MUSICME_AUDIO_PUBLIC_BASE_URL": "https://beatiq-musicme-audio.example.onrender.com",
    "MUSICME_AUDIO_JWT_REVOCATION_URL": "https://beatiq-musicme-dev-auth.example.onrender.com/internal/revocation",
    "MUSICME_AUDIO_WORKER_URL": "https://workspace--musicme-gpu-worker-dev-worker-serve.modal.run",
    "MUSICME_AUDIO_WORKER_CREDENTIALS_JSON": '{"token": "placeholder-worker-token-at-least-32-chars"}',
    "MUSICME_AUDIO_JWKS_URL": "https://auth.example.test/.well-known/jwks.json",
    "MUSICME_AUDIO_JWT_ISSUER": "https://auth.example.test",
}


def load(name):
    with open(os.path.join(RENDER, name)) as f:
        return yaml.safe_load(f)


def services(doc):
    for project in doc["projects"]:
        for environment in project["environments"]:
            for service in environment.get("services", []):
                yield environment, service


def env_for(service):
    env = {}
    for var in service["envVars"]:
        key = var["key"]
        if "value" in var:
            env[key] = str(var["value"])
        elif "fromDatabase" in var:
            env[key] = "postgresql://user:placeholder@db.internal/musicme"
        elif "fromService" in var or var.get("generateValue"):
            ref = var.get("fromService", {}).get("envVarKey", key)
            env[key] = (ref * 4)[:44]  # Render generates base64 256-bit values (44 characters), distinct per secret
        else:
            env[key] = PLACEHOLDERS.get(key, "placeholder-value")
    return env


@pytest.mark.parametrize("name", BLUEPRINTS)
def test_blueprints_are_valid_yaml_with_safe_manual_single_instance_services(name):
    doc = load(name)
    assert doc["previews"]["generation"] == "off"
    for environment, service in services(doc):
        assert environment["networking"]["isolation"] == "enabled"
        assert environment["permissions"]["protection"] == "enabled"
        assert service["autoDeployTrigger"] == "off" and service["numInstances"] == 1
        assert service["region"] == "frankfurt" and service["plan"] and service["healthCheckPath"].startswith("/")
        keys = [var["key"] for var in service["envVars"]]
        assert len(keys) == len(set(keys)), "duplicate env var"
        for var in service["envVars"]:
            assert sum(source in var for source in SOURCES) == 1, var["key"]
            if any(word in var["key"] for word in SECRET_WORDS):
                assert "value" not in var, f"literal secret value for {var['key']}"
    for environment, _ in services(doc):
        for database in environment.get("databases", []):
            assert database["ipAllowList"] == [] and database["storageAutoscalingEnabled"] is False


def test_dev_audio_environment_is_accepted_by_the_real_configuration_loader():
    _, audio = next(s for s in services(load("musicme-dev.yaml")) if s[1]["name"] == "beatiq-musicme-audio-dev")
    settings = load_settings(env_for(audio))
    assert settings.engine == "remote_gpu" and settings.environment == "development"
    assert settings.storage_backend == "s3" and settings.gpu_ledger_backend == "database"
    assert settings.allow_public_dev_service and settings.jwt_revocation_url.startswith("https://")
    assert settings.render_timeout_seconds == 600 and settings.worker_startup_timeout_seconds == 600
    assert settings.gpu_daily_budget_usd <= 2.0 and settings.gpu_max_renders_per_day <= 12
    assert not settings.enable_production_generation
