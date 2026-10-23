"""Fail-closed configuration, strict request contract and safety screening."""
import json

import sys

import pytest

from conftest import SECRET, SIGNING, body, settings
from audioservice.config import Settings, load_settings
from audioservice.contract import parse_request
from audioservice.engines import AceStepEngine, build_render_request, engine_from_settings
from audioservice.errors import ServiceError

PROD = dict(environment="production", engine="none", database_url="postgresql://db.internal/audio",
            jwt_issuer="https://auth.beatiq.test", jwt_audience="musicme-audio", allow_shared_secret_in_production=True,
            public_base_url="https://audio.beatiq.test")


def prod(**overrides):
    values = dict(jwt_secret=SECRET, signing_secret=SIGNING, **PROD)
    values.update(overrides)
    return Settings(**values)


# ---- production fail-closed ------------------------------------------------------------------

def test_production_refuses_local_storage():
    with pytest.raises(ValueError, match="object-store"):
        prod()


def test_production_accepts_private_s3_storage_but_generation_stays_disabled():
    configured = prod(storage_backend="s3", object_endpoint="https://account.r2.cloudflarestorage.com",
                      object_bucket="beatiq-musicme-production", object_region="auto",
                      object_access_key_id="production-access", object_secret_access_key="production-secret-at-least-16",
                      object_prefix="production")
    assert configured.engine == "none" and configured.storage_backend == "s3"


@pytest.mark.parametrize("override, message", [
    (dict(engine="mock"), "mock"),
    (dict(engine="remote_gpu"), "Production generation is disabled"),
    (dict(engine="ace_step", acestep_python=sys.executable, acestep_home="/opt/acestep"), "Production generation is disabled"),
    (dict(database_url="sqlite:///x.db"), "PostgreSQL"),
    (dict(jwt_issuer=None), "ISSUER"),
    (dict(allow_shared_secret_in_production=False), "asymmetric"),
    (dict(public_base_url="http://audio.beatiq.test"), "HTTPS"),
])
def test_production_rejects_unsafe_settings(override, message):
    with pytest.raises(ValueError, match=message):
        prod(**override)


@pytest.mark.parametrize("override, message", [
    (dict(jwt_secret="short"), "JWT verification secret"),
    (dict(signing_secret=""), "signing secret"),
    (dict(signing_secret=SECRET), "must differ"),
    (dict(download_link_seconds=3600), "expire"),
    (dict(engine="mock", host="0.0.0.0"), "loopback"),
    (dict(public_base_url="http://audio.example.com"), "HTTPS"),
    (dict(max_takes=9), "Takes"),
    (dict(storage_backend="s3"), "HTTPS endpoint"),
    (dict(storage_backend="s3", object_endpoint="http://objects.example.test", object_bucket="valid-bucket",
          object_access_key_id="access-key", object_secret_access_key="secret-key-at-least-16"), "HTTPS endpoint"),
])
def test_development_rejects_unsafe_settings(override, message):
    with pytest.raises(ValueError, match=message):
        settings(**override)


def test_environment_is_namespaced_and_never_shares_the_songwriting_database():
    env = {"MUSICME_AUDIO_JWT_SECRET": SECRET, "MUSICME_AUDIO_SIGNING_SECRET": SIGNING,
           "MUSICME_JWT_SECRET": "songwriter-secret-songwriter-secret-123", "MUSICME_ENGINE": "mock"}
    loaded = load_settings(env)
    assert loaded.jwt_secret == SECRET and loaded.engine == "none"  # songwriting variables are ignored
    assert loaded.languages == frozenset({"english"})
    with pytest.raises(SystemExit, match="songwriting service's database"):
        load_settings({**env, "MUSICME_DATABASE_URL": "sqlite:///shared.db", "MUSICME_AUDIO_DATABASE_URL": "sqlite:///shared.db"})


def test_secrets_never_appear_in_settings_repr():
    text = repr(settings(object_access_key_id="storage-access-key", object_secret_access_key="storage-secret-key"))
    assert SECRET not in text and SIGNING not in text and "storage-access-key" not in text and "storage-secret-key" not in text


def test_ace_step_engine_fails_closed_without_a_configured_runtime_and_disables_cloning(tmp_path):
    import sys
    with pytest.raises(ValueError, match="ACESTEP_PYTHON"):
        settings(engine="ace_step", workers=1)
    with pytest.raises(ValueError, match="one render per GPU"):
        settings(engine="ace_step", workers=2, acestep_python=sys.executable, acestep_home=str(tmp_path))
    with pytest.raises(SystemExit, match="runner script is missing"):
        engine_from_settings(settings(engine="ace_step", workers=1, acestep_python=sys.executable,
                                      acestep_home=str(tmp_path), acestep_runner=str(tmp_path / "missing.py")))
    with pytest.raises(SystemExit, match="runtime is not configured"):
        engine_from_settings(settings(engine="ace_step", workers=1, acestep_python=str(tmp_path / "no-python"),
                                      acestep_home=str(tmp_path)))
    assert {"reference_audio", "timbre_clone", "cover", "lora"} <= set(AceStepEngine.DISABLED_FEATURES)
    engine = AceStepEngine(settings(engine="ace_step", workers=1, acestep_python=sys.executable, acestep_home=str(tmp_path),
                                    acestep_runner=__file__), start=False)
    params = engine.pipeline_parameters(build_render_request(parse_request(body(), settings()), 7))
    assert params["seed"] == 7 and not {"reference_audio", "src_audio", "lora", "audio_url"} & set(params)


# ---- contract --------------------------------------------------------------------------------

def parse(**overrides):
    return parse_request(body(**overrides), settings())


def code_of(fn):
    with pytest.raises(ServiceError) as error:
        fn()
    return error.value.code


def test_valid_request_is_normalised_and_user_lyrics_are_untouched():
    spec = parse()
    assert spec["sections"][1]["lyrics"].startswith("Ada laughed at my umbrella")
    assert spec["target_duration_seconds"] == 10 and spec["takes"] == 1 and spec["seed"] == 1234
    request = build_render_request(spec, 99)
    # Tags come from section types, never from user labels; lyrics pass through unchanged.
    assert [s.tag for s in request.sections] == ["Intro", "Verse", "Chorus", "Instrumental", "Outro"]
    assert request.sections[1].lyrics == spec["sections"][1]["lyrics"]
    assert "[Instrumental]" in request.tagged_lyrics()
    assert "Afro-Soul" in request.caption and "84 bpm" in request.caption
    assert "Ada" not in json.dumps(request.parameters())  # reproducibility metadata never carries lyrics


@pytest.mark.parametrize("field", ["reference_audio", "voice_sample", "cover_of", "lora", "speaker_embedding", "recording_id"])
def test_reference_audio_covers_and_cloning_fields_are_refused_by_name(field):
    payload = body()
    payload["request"]["vocals"][field] = "anything"
    assert code_of(lambda: parse_request(payload, settings())) == "POLICY_REJECTED"
    payload = body()
    payload["request"][field] = {"url": "https://example.test/a.wav"}
    assert code_of(lambda: parse_request(payload, settings())) == "POLICY_REJECTED"


def test_unknown_fields_are_refused():
    payload = body()
    payload["request"]["temperature"] = 2
    assert code_of(lambda: parse_request(payload, settings())) == "INVALID_REQUEST"


@pytest.mark.parametrize("overrides", [
    dict(style={"genres": ["Pop in the style of Burna Boy"], "moods": [], "bpm": None, "instrumentation": []}),
    dict(style={"genres": ["Afrobeats like Wizkid"], "moods": [], "bpm": None, "instrumentation": []}),
    dict(style={"genres": ["Pop cover"], "moods": [], "bpm": None, "instrumentation": []}),
])
def test_artist_style_and_cover_prompts_are_refused(overrides):
    assert code_of(lambda: parse(**overrides)) == "POLICY_REJECTED"


def test_voice_imitation_and_copyright_requests_are_refused_but_ordinary_lyrics_pass():
    sections = [{"id": "v", "type": "verse", "label": "Verse", "lyrics": "Sing like Beyonce Knowles tonight"}]
    assert code_of(lambda: parse(sections=sections)) == "POLICY_REJECTED"
    sections = [{"id": "v", "type": "verse", "label": "Verse", "lyrics": "Use the exact original lyrics of that hit"}]
    assert code_of(lambda: parse(sections=sections)) == "POLICY_REJECTED"
    ok = parse(title="Shine Like Gold", sections=[{"id": "v", "type": "verse", "label": "Verse", "lyrics": "You shine like Ada in the morning"}])
    assert ok["title"] == "Shine Like Gold"


def test_unsupported_languages_stay_disabled():
    assert code_of(lambda: parse(language="yoruba")) == "UNSUPPORTED_LANGUAGE"
    assert code_of(lambda: parse(language="french")) == "UNSUPPORTED_LANGUAGE"


@pytest.mark.parametrize("overrides", [
    dict(sections=[{"id": "i", "type": "instrumental", "label": "Break", "lyrics": "words here"}]),
    dict(sections=[{"id": "a", "type": "verse", "label": "V", "lyrics": "x"}, {"id": "a", "type": "chorus", "label": "C", "lyrics": "y"}]),
    dict(sections=[{"id": "a", "type": "intro", "label": "Intro", "lyrics": ""}]),
    dict(sections=[{"id": "v", "type": "verse", "label": "V", "lyrics": "line\n" * 30}]),
    dict(sections=[{"id": "v", "type": "verse", "label": "V", "lyrics": "bad\x07bell"}]),
    dict(target_duration_seconds=5000),
    dict(takes=3),
    dict(seed=-1),
    dict(style={"genres": [], "moods": [], "bpm": None, "instrumentation": []}),
    dict(style={"genres": ["Pop"], "moods": [], "bpm": 400, "instrumentation": []}),
    dict(vocals={"type": "robot", "descriptors": []}),
    dict(vocals={"type": "none", "descriptors": []}),
    dict(vocals={"descriptors": ["warm"]}),
    dict(vocals=None),
])
def test_invalid_requests_are_refused(overrides):
    assert code_of(lambda: parse(**overrides)) == "INVALID_REQUEST"


def test_instrumental_song_needs_no_lyrics():
    sections = [{"id": "i", "type": "intro", "label": "Intro", "lyrics": ""}, {"id": "b", "type": "instrumental", "label": "Groove", "lyrics": ""}]
    spec = parse(sections=sections, vocals={"type": "none", "descriptors": []})
    assert "instrumental, no vocals" in build_render_request(spec, 1).caption


def test_wrong_contract_version_is_refused():
    payload = body()
    payload["contract_version"] = "musicme.audio.v0"
    assert code_of(lambda: parse_request(payload, settings())) == "INVALID_REQUEST"
