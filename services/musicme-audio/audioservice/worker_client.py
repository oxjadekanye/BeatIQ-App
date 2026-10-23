"""Client for a MusicMe GPU worker (protocol: audioservice/gpu_protocol.py). Standard library only.

Provider-neutral: Modal (https://<app>.modal.run), a RunPod HTTPS proxy, or any worker reached through an SSH tunnel on
loopback (Vast.ai, rented VMs). Credentials come from a private file on the audio service host and never leave it
except as request headers to the configured worker host:

    {"token": "<worker bearer token>", "headers": {"Modal-Key": "...", "Modal-Secret": "..."}}   (or just the token)

Redirects are never followed (a redirect could carry credentials elsewhere). Modal answers a request that waits more
than 150 s with a 303; while the worker is starting that is reported as "starting", like a 503.
"""
import hashlib
import json
import os
import re
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request

from . import gpu_protocol

HEADER_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9-]{0,63}$")
RESERVED_HEADERS = {"authorization", "host", "content-length", "content-type", "accept", "cookie", "transfer-encoding"}
MAX_CREDENTIAL_BYTES = 16 * 1024


class WorkerError(Exception):
    def __init__(self, code, retry_after=None, detail=""):
        super().__init__(code)
        self.code = code
        self.retry_after = retry_after
        self.detail = detail


def validate_url(url, allowed_suffixes, allow_loopback_http):
    parsed = urllib.parse.urlparse(url or "")
    host = (parsed.hostname or "").lower()
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise WorkerError("WORKER_URL_REJECTED")
    if parsed.scheme == "https" and host and any(host.endswith(s) and len(host) > len(s) for s in allowed_suffixes if s.startswith(".")):
        return url.rstrip("/")
    if parsed.scheme == "http" and host in ("127.0.0.1", "localhost", "::1") and allow_loopback_http:
        return url.rstrip("/")
    raise WorkerError("WORKER_URL_REJECTED")


def parse_credentials(raw):
    """Parses credentials from a private file or a provider secret value without ever returning/logging the raw text."""
    raw = (raw or "").strip()
    if not raw or len(raw.encode()) > MAX_CREDENTIAL_BYTES:
        raise WorkerError("WORKER_CREDENTIALS_INVALID")
    if raw.startswith("{"):
        try:
            data = json.loads(raw)
        except ValueError:
            raise WorkerError("WORKER_CREDENTIALS_INVALID")
        if not isinstance(data, dict) or set(data) - {"token", "headers"}:
            raise WorkerError("WORKER_CREDENTIALS_INVALID")
        token, headers = data.get("token"), data.get("headers") or {}
    else:
        token, headers = raw, {}
    if not isinstance(token, str) or len(token) < 32 or any(c.isspace() for c in token):
        raise WorkerError("WORKER_CREDENTIALS_INVALID")
    if not isinstance(headers, dict) or len(headers) > 16 or not all(
            isinstance(k, str) and HEADER_NAME.match(k) and k.lower() not in RESERVED_HEADERS
            and isinstance(v, str) and 0 < len(v) <= 4096 and "\n" not in v and "\r" not in v
            for k, v in headers.items()):
        raise WorkerError("WORKER_CREDENTIALS_INVALID")
    return token, dict(headers)


def load_credentials(path):
    """Returns (token, extra_headers) from a private file. Raises WorkerError on anything unsafe or malformed."""
    if not path or not os.path.isfile(path) or os.path.islink(path):
        raise WorkerError("WORKER_CREDENTIALS_MISSING")
    info = os.stat(path)
    if info.st_mode & 0o077:
        raise WorkerError("WORKER_CREDENTIALS_NOT_PRIVATE")
    if info.st_size > MAX_CREDENTIAL_BYTES:
        raise WorkerError("WORKER_CREDENTIALS_INVALID")
    with open(path) as f:
        raw = f.read().strip()
    return parse_credentials(raw)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class WorkerClient:
    def __init__(self, base_url, token, headers=None, opener=None, timeout=30):
        self.base = base_url
        self._token = token
        self._headers = dict(headers or {})
        self.timeout = timeout
        self._opener = opener or urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())

    def __repr__(self):
        return f"WorkerClient({self.base!r})"  # never the credentials

    def _open(self, method, path, body=None, timeout=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        for name, value in self._headers.items():
            request.add_header(name, value)
        request.add_header("Authorization", "Bearer " + self._token)
        request.add_header("Accept", "application/json, audio/wav")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            return self._opener.open(request, timeout=self.timeout if timeout is None else timeout)
        except urllib.error.HTTPError as error:
            retry = _retry_after(error.headers)
            try:
                payload = json.loads(error.read(4096) or b"{}")
            except ValueError:
                payload = {}
            error.close()
            if error.code in (401, 403):
                raise WorkerError("WORKER_UNAUTHORIZED")
            if error.code == 404:
                raise WorkerError("WORKER_NOT_FOUND")
            if error.code == 429:
                raise WorkerError("WORKER_BUSY", retry)
            if error.code == 409:
                raise WorkerError("WORKER_CONFLICT")
            if error.code == 503 and isinstance(payload, dict) and payload.get("state") == "failed":
                raise WorkerError("WORKER_FAILED", detail=str(payload.get("code", ""))[:40])
            if error.code in (502, 503, 504) or 300 <= error.code < 400:
                raise WorkerError("WORKER_STARTING", retry)
            raise WorkerError("WORKER_HTTP", detail=f"HTTP {error.code}")
        except (urllib.error.URLError, OSError, socket.timeout) as error:
            raise WorkerError("WORKER_UNREACHABLE", detail=type(error).__name__)

    def _json(self, method, path, body=None, timeout=None):
        with self._open(method, path, body, timeout=timeout) as response:
            if (response.headers.get("Content-Type") or "").split(";", 1)[0].strip() != "application/json":
                raise WorkerError("WORKER_PROTOCOL", detail="not json")
            raw = response.read(gpu_protocol.MAX_JSON_BYTES + 1)
        if len(raw) > gpu_protocol.MAX_JSON_BYTES:
            raise WorkerError("WORKER_PROTOCOL", detail="reply too large")
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            raise WorkerError("WORKER_PROTOCOL", detail="unreadable reply")

    def hello(self, timeout=None):
        hello = self._json("GET", "/v1/hello", timeout=timeout)
        if not isinstance(hello, dict):
            raise WorkerError("WORKER_PROTOCOL")
        return hello

    def submit(self, job_id, params, deadline_seconds, timeout=None):
        return self._job(self._json("POST", "/v1/jobs", {"job_id": job_id, "params": params,
                                                          "deadline_seconds": int(deadline_seconds)}, timeout), job_id)

    def status(self, job_id, timeout=None):
        return self._job(self._json("GET", f"/v1/jobs/{job_id}", timeout=timeout), job_id)

    def cancel(self, job_id, timeout=None):
        return self._job(self._json("POST", f"/v1/jobs/{job_id}/cancel", {}, timeout), job_id)

    def delete(self, job_id, timeout=None):
        with self._open("DELETE", f"/v1/jobs/{job_id}", timeout=timeout):
            pass

    def download(self, job_id, out_path, expected_sha256, expected_size, max_bytes, timeout=None):
        """Streams the job's WAV into a new private file; checks size and SHA-256 against the job result."""
        if not 44 < expected_size <= max_bytes:
            raise WorkerError("WORKER_PROTOCOL", detail="unexpected size")
        digest, total = hashlib.sha256(), 0
        fd = os.open(out_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            with os.fdopen(fd, "wb") as out, self._open("GET", f"/v1/jobs/{job_id}/audio", timeout=timeout) as response:
                if (response.headers.get("Content-Type") or "").split(";")[0].strip() != "audio/wav":
                    raise WorkerError("WORKER_PROTOCOL", detail="not audio")
                if not hmac_compare(response.headers.get("X-Content-SHA256", ""), expected_sha256):
                    raise WorkerError("WORKER_PROTOCOL", detail="audio header digest")
                while True:
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > expected_size:
                        raise WorkerError("WORKER_PROTOCOL", detail="audio larger than declared")
                    digest.update(chunk)
                    out.write(chunk)
        except BaseException:
            _remove(out_path)
            raise
        if total != expected_size or digest.hexdigest() != expected_sha256:
            _remove(out_path)
            raise WorkerError("WORKER_PROTOCOL", detail="audio failed its integrity check")

    @staticmethod
    def _job(job, job_id):
        try:
            return gpu_protocol.check_job(job, job_id)
        except gpu_protocol.ProtocolViolation as error:
            raise WorkerError("WORKER_PROTOCOL", detail=str(error))


def _retry_after(headers):
    try:
        return max(1, min(30, int((headers or {}).get("Retry-After", ""))))
    except (TypeError, ValueError):
        return None


def _remove(path):
    try:
        os.remove(path)
    except FileNotFoundError:
        pass


def hmac_compare(left, right):
    """Constant-time comparison keeps digest/header checks uniform with credential checks."""
    import hmac
    return hmac.compare_digest(str(left).encode(), str(right).encode())
