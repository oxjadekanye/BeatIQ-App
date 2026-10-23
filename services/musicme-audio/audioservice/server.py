"""HTTP API (musicme.audio.v1). Run: python3 -m audioservice.server (see README). Binds to localhost by default.

Endpoints (all JSON, all authenticated except /healthz and signed downloads):
    GET    /v1/audio/capabilities
    POST   /v1/audio/jobs                       (Idempotency-Key header required)
    GET    /v1/audio/jobs/{id}
    POST   /v1/audio/jobs/{id}/cancel
    POST   /v1/audio/tracks/{id}/link           -> short-lived signed download URL
    DELETE /v1/audio/tracks/{id}
    DELETE /v1/audio/account                    -> deletes every song and job for the signed-in account
    GET    /v1/audio/files/{signed-token}       -> the audio bytes (no bearer token; the link is the credential)
"""
import json
import logging
import re
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import CONTRACT_VERSION, ENGINE_INTERFACE_VERSION
from .auth import TokenVerifier
from .config import load_settings
from .contract import MAX_SECTIONS, VOCAL_TYPES, parse_request
from .cost_guard import DatabaseCostLedger
from .db import Database
from .engines import engine_from_settings
from .errors import ServiceError
from .storage import LinkSigner, StorageError, account_key, object_store_from_settings
from .store import JobStore
from .worker import WorkerPool

log = logging.getLogger("musicme.audio")
_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
_JOB_PATH = re.compile(rf"^/v1/audio/jobs/({_UUID})(/cancel)?$")
_TRACK_PATH = re.compile(rf"^/v1/audio/tracks/({_UUID})(/link)?$")
_FILE_PATH = re.compile(r"^/v1/audio/files/([^/?#]{1,512})$")
_KEY = re.compile(r"^[A-Za-z0-9-]{8,128}$")


def capabilities(settings, engine, usage):
    return {
        "contract_version": CONTRACT_VERSION,
        "engine_interface_version": ENGINE_INTERFACE_VERSION,
        "engine": engine.kind,
        "model_id": engine.model_id,
        "model_version": engine.model_version,
        "real_audio": not engine.is_mock,  # the mock engine writes test tones, not music
        "languages": sorted(settings.languages),
        "vocal_types": list(VOCAL_TYPES),
        "max_sections": MAX_SECTIONS,
        "min_duration_seconds": max(settings.min_duration_seconds, engine.limits().get("min_duration_seconds", 0)),
        "max_duration_seconds": min(settings.max_duration_seconds, engine.limits().get("max_duration_seconds", 10 ** 6)),
        "max_takes": min(settings.max_takes, engine.limits().get("max_takes", settings.max_takes)),
        "max_lyric_chars": engine.limits().get("max_lyric_chars"),
        "max_caption_chars": engine.limits().get("max_caption_chars"),
        "formats": [engine.output_format],
        "reference_audio": False,
        "voice_cloning": False,
        "songs_per_day": settings.max_jobs_per_window,
        "storage": usage,
    }


def _redact(path):
    path = path.split("?")[0]
    path = re.sub(r"/files/[^/]+", "/files/<link>", path)
    return re.sub(_UUID, "<id>", path)


def make_handler(settings, engine, store, storage, signer, verifier):
    class Handler(BaseHTTPRequestHandler):
        server_version = "musicme-audio"
        sys_version = ""

        def log_message(self, fmt, *args):
            # Paths are logged with ids and link tokens redacted; bodies and headers (tokens) are never logged.
            log.info("http %s %s", self.command, _redact(self.path))

        def do_GET(self):
            self._dispatch()

        def do_POST(self):
            self._dispatch()

        def do_DELETE(self):
            self._dispatch()

        def _dispatch(self):
            try:
                if self.path == "/healthz" and self.command == "GET":
                    return self._send(200, {"status": "ok", "engine": engine.kind if engine else "none"})
                if self.path == "/readyz" and self.command == "GET":
                    if not store.db.ping() or not storage.ready():
                        raise ServiceError(503, "UNAVAILABLE", "MusicMe is not ready.")
                    return self._send(200, {"status": "ready"})
                file_match = _FILE_PATH.match(self.path)
                if file_match and self.command == "GET":
                    return self._download(file_match.group(1))
                # Every other endpoint is authenticated first; the account comes only from the verified token.
                account = verifier.account(self.headers.get("Authorization", ""))
                if engine is None:
                    raise ServiceError(503, "UNAVAILABLE", "Song creation isn't enabled on this server.")
                if self.path == "/v1/audio/capabilities" and self.command == "GET":
                    return self._send(200, capabilities(settings, engine, store.usage(account)))
                if self.path == "/v1/audio/jobs" and self.command == "POST":
                    return self._submit(account)
                if self.path == "/v1/audio/account" and self.command == "DELETE":
                    keys, jobs = store.delete_account_data(account)
                    for key in keys:
                        storage.delete(key)
                    return self._send(200, {"deleted_tracks": len(keys), "deleted_jobs": jobs})
                match = _JOB_PATH.match(self.path)
                if match and self.command == "GET" and not match.group(2):
                    return self._send(200, store.get(account, match.group(1)))
                if match and self.command == "POST" and match.group(2):
                    return self._send(200, store.cancel(account, match.group(1)))
                match = _TRACK_PATH.match(self.path)
                if match and self.command == "POST" and match.group(2):
                    track = store.track_for_link(account, match.group(1))
                    filename = f"musicme-{track['id'][:8]}.{track['format']}"
                    direct = storage.presigned_download(track["storage_key"], track["content_type"], filename,
                                                        settings.download_link_seconds)
                    if direct:
                        expires = int(signer.clock()) + settings.download_link_seconds
                        return self._send(200, {"url": direct, "expires_at": expires * 1000,
                                                "content_type": track["content_type"], "size_bytes": track["size_bytes"]})
                    token, expires = signer.sign(track["id"], account)
                    return self._send(200, {"url": f"{settings.public_base_url.rstrip('/')}/v1/audio/files/{token}",
                                            "expires_at": expires * 1000, "content_type": track["content_type"],
                                            "size_bytes": track["size_bytes"]})
                if match and self.command == "DELETE" and not match.group(2):
                    storage.delete(store.delete_track(account, match.group(1)))
                    return self._send(200, {"deleted": True})
                raise ServiceError(404, "NOT_FOUND", "Unknown endpoint.")
            except ServiceError as error:
                headers = {"Retry-After": str(error.retry_after)} if error.retry_after else {}
                self._send(error.status, error.body(), headers)
            except StorageError:
                self._send(503, {"error": {"code": "STORAGE_UNAVAILABLE",
                                            "message": "Song storage is temporarily unavailable."}},
                           {"Retry-After": "30"})
            except Exception as error:
                log.error("unhandled_error type=%s", type(error).__name__)
                self._send(500, {"error": {"code": "UNKNOWN", "message": "Something went wrong."}})

        def _submit(self, account):
            key = self.headers.get("Idempotency-Key", "")
            if not _KEY.match(key):
                raise ServiceError(400, "INVALID_REQUEST", "A valid Idempotency-Key header is required.")
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                length = 0
            if length <= 0 or length > settings.max_body_bytes:
                raise ServiceError(413, "INVALID_REQUEST", "The request is empty or too large.")
            raw = self.rfile.read(length)
            try:
                body = json.loads(raw)
            except ValueError:
                raise ServiceError(400, "INVALID_REQUEST", "The request body isn't valid JSON.")
            spec = parse_request(body, settings)
            problem = engine.check_spec(spec)  # engine-specific limits (e.g. ACE-Step: 4,096 lyric chars, 1 take)
            if problem:
                raise ServiceError(422, "INVALID_REQUEST", problem)
            job, created = store.submit(account, key, raw, spec)
            self._send(202 if created else 200, job)

        def _download(self, token):
            try:
                object_id, owner_key = signer.verify(token)
            except StorageError as error:
                status = 410 if error.code == "LINK_EXPIRED" else 403
                raise ServiceError(status, error.code, error.message)
            track = store.track_for_download(object_id)
            # The link must belong to the object's owner; deleted, expired or foreign objects all look the same.
            if track is None or account_key(track["account"]) != owner_key:
                raise ServiceError(404, "TRACK_NOT_FOUND", "This song is no longer available.")
            try:
                f = storage.open(track["storage_key"])
            except FileNotFoundError:  # deleted between the lookup and the read
                raise ServiceError(404, "TRACK_NOT_FOUND", "This song is no longer available.")
            with f:
                self.send_response(200)
                self.send_header("Content-Type", track["content_type"])
                self.send_header("Content-Length", str(track["size_bytes"]))
                self.send_header("Content-Disposition", f'attachment; filename="musicme-{track["id"][:8]}.{track["format"]}"')
                self.send_header("Cache-Control", "private, no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                for chunk in iter(lambda: f.read(64 * 1024), b""):
                    self.wfile.write(chunk)

        def _send(self, status, payload, headers=None):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(data)))
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(data)

    return Handler


class Service:
    """The HTTP server plus its durable store, private storage and workers."""

    def __init__(self, settings, engine=None, verifier=None, start_workers=True, clock=None, link_clock=None):
        self.settings = settings
        self.db = Database(settings.database_url)
        self.db.migrate()
        ledger = None
        if settings.engine == "remote_gpu" and settings.gpu_ledger_backend == "database":
            ledger = DatabaseCostLedger(self.db, settings.gpu_price_per_hour_usd, settings.gpu_daily_budget_usd,
                                        settings.gpu_max_renders_per_day, settings.gpu_reserve_seconds,
                                        settings.gpu_idle_tail_seconds)
        self.engine = engine if engine is not None else engine_from_settings(settings, ledger=ledger)
        self.store = JobStore(self.db, settings, clock=clock)
        self.storage = object_store_from_settings(settings)
        self.signer = LinkSigner(settings.signing_secret, settings.download_link_seconds, **({"clock": link_clock} if link_clock else {}))
        self.verifier = verifier or TokenVerifier(settings)
        self.pool = WorkerPool(self.store, self.engine, self.storage, settings) if self.engine and settings.run_workers else None
        self.http = ThreadingHTTPServer((settings.host, settings.port),
                                        make_handler(settings, self.engine, self.store, self.storage, self.signer, self.verifier))
        if start_workers and self.pool:
            self.pool.start()

    @property
    def port(self):
        return self.http.server_address[1]

    def shutdown(self):
        self.http.shutdown()
        self.http.server_close()
        if self.pool:
            self.pool.request_stop()  # no new durable job may be claimed during engine teardown
        if self.engine is not None and self.engine.kind == "remote_gpu":
            self.engine.close()  # immediately asks the paid worker to cancel before waiting for the local thread
        if self.pool:
            self.pool.join()
        if self.engine is not None and self.engine.kind != "remote_gpu":
            self.engine.close()  # stops the ACE-Step runner process, if any
        self.db.close()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    settings = load_settings()
    service = Service(settings)
    log.info("listening host=%s port=%d engine=%s env=%s db=%s storage=%s", settings.host, settings.port, settings.engine,
             settings.environment, service.db.dialect, service.storage.kind)
    stopping = threading.Event()

    def stop(*_):
        if not stopping.is_set():
            stopping.set()
            # Non-daemon: after serve_forever returns, Python must still wait for GPU cancellation and cleanup.
            threading.Thread(target=service.shutdown, name="shutdown").start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    service.http.serve_forever()


if __name__ == "__main__":
    main()
