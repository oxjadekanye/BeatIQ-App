import base64
import copy
import getpass
import hashlib
import hmac
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from audioservice import CONTRACT_VERSION  # noqa: E402
from audioservice.config import Settings  # noqa: E402
from audioservice.server import Service  # noqa: E402

SECRET = "audio-test-jwt-secret-at-least-32-characters"
SIGNING = "audio-test-link-signing-secret-32-characters!"


def token(user_id="account-a", exp_offset=600, secret=SECRET, alg="HS256", **extra):
    def b64(data):
        return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()
    head = b64({"alg": alg, "typ": "JWT"})
    claims = {"user_id": user_id, "exp": int(time.time()) + exp_offset, "token_type": "access", **extra}
    body = b64(claims)
    sig = hmac.new(secret.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}." + base64.urlsafe_b64encode(sig).rstrip(b"=").decode()


A, B = token("account-a"), token("account-b")

LYRIC_LINE = "Ada laughed at my umbrella by the Lagos harbour"

BASE_REQUEST = {
    "draft_id": "draft-1",
    "title": "Ten Brave Years",
    "language": "english",
    "sections": [
        {"id": "s-intro", "type": "intro", "label": "Intro", "lyrics": ""},
        {"id": "s-v1", "type": "verse", "label": "Verse 1", "lyrics": LYRIC_LINE + "\nTen years later she still keeps the ticket"},
        {"id": "s-ch", "type": "chorus", "label": "Chorus", "lyrics": "Thank you for ten brave years\nThank you, Ada"},
        {"id": "s-inst", "type": "instrumental", "label": "Instrumental Break", "lyrics": ""},
        {"id": "s-out", "type": "outro", "label": "Outro", "lyrics": ""},
    ],
    "style": {"genres": ["Afro-Soul"], "moods": ["Romantic"], "bpm": 84, "instrumentation": ["Rhodes", "Strings"]},
    "vocals": {"type": "male", "descriptors": ["warm"]},
    "target_duration_seconds": 10,
    "takes": 1,
    "seed": 1234,
    "allow_explicit": False,
}


def request(**overrides):
    value = copy.deepcopy(BASE_REQUEST)
    value.update(overrides)
    return value


def body(**overrides):
    return {"contract_version": CONTRACT_VERSION, "request": request(**overrides)}


FAST = dict(backoff_base_seconds=0.01, backoff_max_seconds=0.05, worker_poll_seconds=0.01, maintenance_interval_seconds=3600)

_TMP = tempfile.TemporaryDirectory(prefix="musicme-audio-tests-")


def settings(**overrides):
    root = os.path.join(_TMP.name, f"run-{time.time_ns()}")
    values = dict(jwt_secret=SECRET, signing_secret=SIGNING, engine="mock", host="127.0.0.1", port=0, workers=2,
                  generation_mode="internal_test", internal_test_user_ids=frozenset({"account-a", "account-b"}),
                  min_duration_seconds=10, database_url="sqlite:///" + os.path.join(root, "audio.db"),
                  storage_root=os.path.join(root, "objects"), **FAST)
    values.update(overrides)
    return Settings(**values)


class Client:
    def __init__(self, base):
        self.base = base

    def call(self, method, path, payload=None, auth=None, key=None, raw=None):
        data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if auth:
            req.add_header("Authorization", "Bearer " + auth)
        if key:
            req.add_header("Idempotency-Key", key)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                return response.status, json.loads(response.read()), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read()), dict(error.headers)

    def fetch(self, url):
        """GET a signed download URL without any Authorization header. Returns (status, bytes, headers)."""
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers)

    def wait(self, job_id, auth, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            status, job, _ = self.call("GET", f"/v1/audio/jobs/{job_id}", auth=auth)
            if job.get("state") in ("succeeded", "failed", "cancelled"):
                return job
            time.sleep(0.02)
        raise AssertionError("job did not finish")


@pytest.fixture
def make_service():
    services = []

    def start(engine_impl=None, start_workers=True, link_clock=None, **setting_overrides):
        service = Service(settings(**setting_overrides), engine=engine_impl, start_workers=start_workers, link_clock=link_clock)
        threading.Thread(target=service.http.serve_forever, daemon=True).start()
        services.append(service)
        return Client(f"http://127.0.0.1:{service.port}"), service

    yield start
    for service in services:
        service.shutdown()


# ---- Databases: store tests run on SQLite and, when available, a throwaway local PostgreSQL ----

@pytest.fixture(scope="session")
def postgres_cluster():
    """A private PostgreSQL cluster in a temp dir, Unix socket only (no TCP), removed after the run."""
    initdb, pg_ctl = shutil.which("initdb"), shutil.which("pg_ctl")
    try:
        import psycopg2  # noqa: F401
    except ImportError:
        psycopg2 = None
    if not initdb or not pg_ctl or psycopg2 is None:
        pytest.skip("Local PostgreSQL binaries or psycopg2 not available")
    root = tempfile.mkdtemp(prefix="musicme-audio-pg-")
    data, sock = os.path.join(root, "data"), os.path.join(root, "sock")
    os.makedirs(sock)
    env = {**os.environ, "LC_ALL": "C", "LANG": "C"}  # macOS: avoids "postmaster became multithreaded during startup"
    port = 56432 + os.getpid() % 1000
    try:
        subprocess.run([initdb, "-D", data, "-A", "trust", "-U", getpass.getuser(), "--no-sync"], check=True, capture_output=True, env=env)
        subprocess.run([pg_ctl, "start", "-D", data, "-l", os.path.join(root, "log"), "-w", "-o",
                        f"-k {sock} -p {port} -c listen_addresses='' -c fsync=off"], check=True, capture_output=True, start_new_session=True, env=env)
    except subprocess.CalledProcessError:
        shutil.rmtree(root, ignore_errors=True)
        pytest.skip("Could not start a throwaway PostgreSQL cluster")
    try:
        yield f"postgresql:///postgres?host={sock}&port={port}&user={getpass.getuser()}", sock, port
    finally:
        subprocess.run([pg_ctl, "-D", data, "-m", "immediate", "stop"], capture_output=True, env=env)
        shutil.rmtree(root, ignore_errors=True)


@pytest.fixture(params=["sqlite", "postgres"])
def database_url(request, tmp_path):
    if request.param == "sqlite":
        return "sqlite:///" + str(tmp_path / "audio.db")
    admin_url, sock, port = request.getfixturevalue("postgres_cluster")
    import psycopg2
    name = "a_" + uuid.uuid4().hex[:12]
    conn = psycopg2.connect(admin_url)
    conn.autocommit = True
    conn.cursor().execute(f"CREATE DATABASE {name}")
    conn.close()
    return f"postgresql:///{name}?host={sock}&port={port}&user={getpass.getuser()}"
