"""BeatIQ DEV auth stub: tokens, sign-in, refresh, revocation and rotation, checked against the audio verifier."""
import errno
import importlib
import io
import json
import os
import stat
import sys
import threading
import time
import urllib.error
import urllib.request
from contextlib import redirect_stdout, redirect_stderr

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.dirname(HERE))
import devauth  # noqa: E402


@pytest.fixture
def auth_home(tmp_path, monkeypatch):
    home = tmp_path / "auth"
    monkeypatch.setenv("MUSICME_DEV_AUTH_HOME", str(home))
    assert devauth.init() is True and devauth.init() is False
    return home


@pytest.fixture
def server(auth_home):
    httpd = devauth.serve("127.0.0.1", 0)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown(); httpd.server_close()


def post(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def get(url, authorization=None):
    headers = {"Authorization": authorization} if authorization else {}
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def audio_verifier(revocation):
    """The real audio-service token verifier, configured exactly as `devauth.py service-env` says."""
    package = "audioservice"
    sys.path.insert(0, os.path.join(SERVICES, "musicme-audio"))
    try:
        config, auth = importlib.import_module(f"{package}.config"), importlib.import_module(f"{package}.auth")
        root = os.path.join(os.path.dirname(str(revocation)), "audio-data")
        fields = dict(jwt_secret=devauth.secret(), jwt_issuer=devauth.ISSUER, jwt_audience=devauth.AUDIENCE,
                      jwt_revocation_file=str(revocation), engine="mock", host="127.0.0.1", port=0, workers=1,
                      signing_secret="s" * 40, database_url="sqlite:///" + os.path.join(root, "audio.db"),
                      storage_root=os.path.join(root, "objects"))
        return auth.TokenVerifier(config.Settings(**fields))
    finally:
        sys.path.pop(0)
        for name in [n for n in sys.modules if n == package or n.startswith(package + ".")]:
            del sys.modules[name]


def test_files_are_private_and_the_secret_is_never_printed(auth_home, capsys):
    assert stat.S_IMODE(os.stat(auth_home).st_mode) == 0o700
    for name in ("jwt_secret", "revocation.json"):
        assert stat.S_IMODE(os.stat(auth_home / name).st_mode) == 0o600
    devauth.main(["service-env"])
    out = capsys.readouterr().out
    assert devauth.secret() not in out and "JWT_SECRET_FILE=" in out and "REVOCATION_FILE=" in out


def test_render_mount_chmod_eperm_still_requires_private_writable_persistent_files(tmp_path, monkeypatch):
    mounted = tmp_path / "render-disk"
    mounted.mkdir()
    monkeypatch.setenv("MUSICME_DEV_AUTH_HOME", str(mounted))
    monkeypatch.delenv("MUSICME_DEV_AUTH_JWT_SECRET", raising=False)
    real_chmod = os.chmod

    def render_chmod(path, mode):
        if os.path.abspath(path) == str(mounted):
            raise PermissionError(errno.EPERM, "Render owns the mount point", str(path))
        return real_chmod(path, mode)

    monkeypatch.setattr(os, "chmod", render_chmod)
    assert devauth.init() is True
    first_secret = devauth.secret()
    assert len(first_secret) == 96
    for name in ("jwt_secret", "revocation.json"):
        assert stat.S_IMODE(os.stat(mounted / name).st_mode) == 0o600

    # A fresh process verifies the mount again and reuses the persisted secret instead of rotating it.
    devauth._VERIFIED_HOMES.discard(str(mounted))
    assert devauth.init() is False
    assert devauth.secret() == first_secret


def test_render_generated_secret_is_validated_and_not_shadowed_by_a_disk_secret(tmp_path, monkeypatch):
    mounted = tmp_path / "render-disk"
    mounted.mkdir()
    configured = "render-generated-secret-that-persists-across-restarts"
    monkeypatch.setenv("MUSICME_DEV_AUTH_HOME", str(mounted))
    monkeypatch.setenv("MUSICME_DEV_AUTH_JWT_SECRET", configured)

    def render_chmod(path, mode):
        raise PermissionError(errno.EPERM, "Render owns the mount point", str(path))

    monkeypatch.setattr(os, "chmod", render_chmod)
    assert devauth.init() is False
    assert devauth.secret() == configured
    assert not (mounted / "jwt_secret").exists()
    assert stat.S_IMODE(os.stat(mounted / "revocation.json").st_mode) == 0o600
    devauth._VERIFIED_HOMES.discard(str(mounted))
    assert devauth.init() is False
    assert devauth.secret() == configured


def test_home_rejects_unexpected_chmod_and_unwritable_mount_failures(tmp_path, monkeypatch):
    mounted = tmp_path / "render-disk"
    mounted.mkdir()
    monkeypatch.setenv("MUSICME_DEV_AUTH_HOME", str(mounted))

    def denied_chmod(path, mode):
        raise PermissionError(errno.EACCES, "unexpected denial", str(path))

    monkeypatch.setattr(os, "chmod", denied_chmod)
    with pytest.raises(PermissionError) as caught:
        devauth.home()
    assert caught.value.errno == errno.EACCES

    def render_chmod(path, mode):
        raise PermissionError(errno.EPERM, "Render owns the mount point", str(path))

    monkeypatch.setattr(os, "chmod", render_chmod)
    real_open = os.open

    def denied_probe(path, flags, mode=0o777):
        if os.path.basename(path).startswith(".write-test-"):
            raise PermissionError(errno.EACCES, "mount is not writable", str(path))
        return real_open(path, flags, mode)

    monkeypatch.setattr(os, "open", denied_probe)
    with pytest.raises(PermissionError) as caught:
        devauth.home()
    assert caught.value.errno == errno.EACCES


def test_secret_initialization_fails_closed_on_persistence_error(tmp_path, monkeypatch):
    mounted = tmp_path / "render-disk"
    monkeypatch.setenv("MUSICME_DEV_AUTH_HOME", str(mounted))
    monkeypatch.delenv("MUSICME_DEV_AUTH_JWT_SECRET", raising=False)

    def failed_replace(source, destination):
        raise OSError(errno.EIO, "persistent disk write failed")

    monkeypatch.setattr(os, "replace", failed_replace)
    with pytest.raises(OSError) as caught:
        devauth.init()
    assert caught.value.errno == errno.EIO
    assert not (mounted / "jwt_secret").exists()


def test_tokens_verify_expire_and_reject_tampering(auth_home):
    access = devauth.issue("dev-1", "access", 60)
    claims = devauth.verify(access, "access")
    assert claims["user_id"] == "dev-1" and claims["iss"] == devauth.ISSUER and claims["aud"] == devauth.AUDIENCE
    assert claims["jti"] and claims["exp"] - claims["iat"] == 60
    assert devauth.verify(access, "refresh") is None
    assert devauth.verify(access, "access", now=time.time() + 61) is None
    head, body, sig = access.split(".")
    assert devauth.verify(f"{head}.{body}.{sig[:-2]}AA", "access") is None
    assert devauth.verify(devauth.issue("dev-1", "access", 60, key="x" * 96), "access") is None
    assert devauth.verify("garbage", "access") is None


def test_sign_in_refresh_and_revocation_end_to_end_with_audio_service(server, auth_home, capsys):
    user_id, password = devauth.create_user("Tester@Dev.Local")
    assert user_id.startswith("dev-") and password not in (auth_home / "users.json").read_text()
    assert stat.S_IMODE(os.stat(auth_home / "users.json").st_mode) == 0o600
    status, tokens = post(server + "/api/v1/auth/token/", {"email": "tester@dev.local", "password": password})
    assert status == 200 and set(tokens) == {"access", "refresh"}
    audio = audio_verifier(auth_home / "revocation.json")
    assert audio.account("Bearer " + tokens["access"]) == user_id
    status, refreshed = post(server + "/api/v1/auth/token/refresh/", {"refresh": tokens["refresh"]})
    assert status == 200 and devauth.verify(refreshed["access"], "access")["user_id"] == user_id
    assert post(server + "/api/v1/auth/token/refresh/", {"refresh": tokens["access"]})[0] == 401
    time.sleep(1.1)  # iat has one-second resolution; revocation cuts at "now"
    devauth.revoke(email="tester@dev.local")
    with pytest.raises(Exception):
        audio.account("Bearer " + tokens["access"])
    assert post(server + "/api/v1/auth/token/refresh/", {"refresh": tokens["refresh"]})[0] == 401
    time.sleep(1.1)
    status, again = post(server + "/api/v1/auth/token/", {"email": "tester@dev.local", "password": password})
    assert status == 200 and audio.account("Bearer " + again["access"]) == user_id
    devauth.init(rotate=True)
    assert devauth.verify(again["access"], "access") is None
    assert "Password" not in capsys.readouterr().err


def test_wrong_passwords_are_rate_limited_and_other_routes_fail_closed(server, auth_home):
    _, password = devauth.create_user("rate@dev.local")
    for _ in range(devauth.MAX_FAILURES_PER_MINUTE):
        assert post(server + "/api/v1/auth/token/", {"email": "rate@dev.local", "password": "wrong"})[0] == 401
    assert post(server + "/api/v1/auth/token/", {"email": "rate@dev.local", "password": password})[0] == 429
    assert post(server + "/api/v1/auth/token/", {"email": "nobody@dev.local", "password": "x"})[0] == 401
    assert post(server + "/api/v1/library/tracks/", {})[0] == 503
    with urllib.request.urlopen(server + "/healthz", timeout=5) as response:
        assert json.loads(response.read())["status"] == "ok"


def test_loopback_only_and_no_bodies_logged(auth_home, capfd):
    with pytest.raises(SystemExit):
        devauth.serve("0.0.0.0", 0)
    httpd = devauth.serve("127.0.0.1", 0)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        _, password = devauth.create_user("log@dev.local")
        post(f"http://127.0.0.1:{httpd.server_address[1]}/api/v1/auth/token/", {"email": "log@dev.local", "password": password})
    finally:
        httpd.shutdown(); httpd.server_close()
    err = capfd.readouterr().err
    assert "devauth POST /api/v1/auth/token/" in err and password not in err and "log@dev.local" not in err


def test_hosted_revocation_requires_an_independent_service_token(auth_home, monkeypatch):
    internal = "hosted-revocation-token-32-characters"
    monkeypatch.setenv("MUSICME_DEV_AUTH_INTERNAL_TOKEN", internal)
    httpd = devauth.serve("127.0.0.1", 0)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    try:
        assert get(base + "/internal/revocation")[0] == 401
        status, document = get(base + "/internal/revocation", "Bearer wrong")
        assert status == 401
        status, document = get(base + "/internal/revocation", "Bearer " + internal)
        assert status == 200 and document == {"not_before": {}, "revoked_jti": []}
        (auth_home / "revocation.json").write_text("{broken")
        assert get(base + "/internal/revocation", "Bearer " + internal)[0] == 503
    finally:
        httpd.shutdown(); httpd.server_close()

    monkeypatch.setenv("MUSICME_DEV_AUTH_ALLOW_PUBLIC", "true")
    monkeypatch.setenv("MUSICME_DEV_AUTH_JWT_SECRET", "render-generated-256-bit-secret-value")
    public = devauth.serve("0.0.0.0", 0)
    public.server_close()
    monkeypatch.setenv("MUSICME_DEV_AUTH_INTERNAL_TOKEN", "short")
    with pytest.raises(SystemExit, match="32"):
        devauth.serve("0.0.0.0", 0)
