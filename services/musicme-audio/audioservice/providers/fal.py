"""Minimal, dependency-free client for fal.ai's documented queue protocol.

Only reviewed ACE-Step endpoint ids are supplied by configuration. Responses are size-bounded, provider errors are
never echoed to users or logs, and output downloads are restricted to approved HTTPS media hosts.
"""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid


class FalProviderError(Exception):
    def __init__(self, code, transient=False):
        super().__init__(code)
        self.code = code
        self.transient = transient


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class FalProvider:
    MAX_JSON_BYTES = 1024 * 1024
    _ENDPOINT = re.compile(r"^[a-z0-9-]+(?:/[a-z0-9-]+)+$")
    _REQUEST_ID = re.compile(r"^[A-Za-z0-9_-]{8,128}$")
    _GCS_PREFIX = "/falserverless/"

    def __init__(self, key, queue_base_url="https://queue.fal.run", allowed_download_hosts=(), opener=None,
                 download_opener=None):
        if not isinstance(key, str) or len(key) < 20:
            raise ValueError("A server-side fal key is required.")
        if queue_base_url != "https://queue.fal.run":
            raise ValueError("Unexpected fal queue origin.")
        self._key = key
        self._base = queue_base_url
        self._allowed_download_hosts = tuple(allowed_download_hosts)
        self._opener = opener or urllib.request.build_opener(_NoRedirect())
        self._download_opener = download_opener or urllib.request.build_opener(_NoRedirect())

    def submit(self, endpoint, arguments, timeout=30):
        response = self._json("POST", self._endpoint_url(endpoint), arguments, timeout)
        request_id = response.get("request_id")
        if not isinstance(request_id, str) or not self._REQUEST_ID.fullmatch(request_id):
            raise FalProviderError("PROVIDER_RESPONSE_INVALID")
        return request_id

    def status(self, endpoint, request_id, timeout=15):
        response = self._json("GET", self._request_url(endpoint, request_id, "/status"), None, timeout)
        status = response.get("status")
        if status not in ("IN_QUEUE", "IN_PROGRESS", "COMPLETED"):
            raise FalProviderError("PROVIDER_RESPONSE_INVALID")
        return response

    def result(self, endpoint, request_id, timeout=30):
        return self._json("GET", self._request_url(endpoint, request_id), None, timeout)

    def cancel(self, endpoint, request_id, timeout=10):
        """Best effort. fal answers 200 {"status": "OK"} (removed from the queue) or 202
        {"status": "CANCELLATION_REQUESTED"} (already running; it may still finish and bill)."""
        try:
            response = self._json("PUT", self._request_url(endpoint, request_id, "/cancel"), {}, timeout)
            return response.get("status") in ("OK", "CANCELLATION_REQUESTED")
        except FalProviderError:
            return False

    def download_audio(self, url, destination, max_bytes, timeout=60):
        parsed = urllib.parse.urlsplit(url)
        host = (parsed.hostname or "").lower()
        allowed = any(host == rule or (rule.startswith(".") and host.endswith(rule))
                      for rule in self._allowed_download_hosts)
        if host == "storage.googleapis.com" and not parsed.path.startswith(self._GCS_PREFIX):
            allowed = False  # a shared host: only fal's own legacy output bucket is accepted
        if (parsed.scheme != "https" or not allowed or parsed.username or parsed.password or parsed.fragment
                or parsed.port not in (None, 443)):
            raise FalProviderError("PROVIDER_OUTPUT_URL_INVALID")
        request = urllib.request.Request(url, headers={"Accept": "audio/*", "User-Agent": "BeatIQ-MusicMe/1"})
        temp_fd = None
        complete = False
        try:
            with self._download_opener.open(request, timeout=timeout) as response:
                content_type = (response.headers.get("Content-Type") or "").split(";", 1)[0].lower()
                if content_type and not (content_type.startswith("audio/") or content_type == "application/octet-stream"):
                    raise FalProviderError("PROVIDER_OUTPUT_INVALID")
                temp_fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                total = 0
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > max_bytes:
                        raise FalProviderError("PROVIDER_OUTPUT_TOO_LARGE")
                    os.write(temp_fd, chunk)
                if total == 0:
                    raise FalProviderError("PROVIDER_OUTPUT_INVALID")
                os.fsync(temp_fd)
                os.close(temp_fd)
                temp_fd = None
                complete = True
                return total
        except FalProviderError:
            raise
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            raise FalProviderError("PROVIDER_DOWNLOAD_FAILED", transient=True)
        finally:
            if temp_fd is not None:
                os.close(temp_fd)
            if not complete:
                try:
                    os.remove(destination)
                except FileNotFoundError:
                    pass

    def _endpoint_url(self, endpoint):
        if not isinstance(endpoint, str) or not self._ENDPOINT.fullmatch(endpoint):
            raise FalProviderError("PROVIDER_ENDPOINT_INVALID")
        return f"{self._base}/{endpoint}"

    def _request_url(self, endpoint, request_id, suffix=""):
        if not isinstance(request_id, str) or not self._REQUEST_ID.fullmatch(request_id):
            raise FalProviderError("PROVIDER_REQUEST_INVALID")
        # Status, result and cancel URLs use only owner/app: fal answers 405 when the endpoint subpath is included
        # (e.g. fal-ai/ace-step/prompt-to-audio is polled at fal-ai/ace-step/requests/{id}).
        app = "/".join(self._endpoint_url(endpoint)[len(self._base) + 1:].split("/")[:2])
        return f"{self._base}/{app}/requests/{urllib.parse.quote(request_id, safe='')}{suffix}"

    def _json(self, method, url, payload, timeout):
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Authorization": "Key " + self._key, "Accept": "application/json", "User-Agent": "BeatIQ-MusicMe/1"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=timeout) as response:
                content_type = (response.headers.get("Content-Type") or "").split(";", 1)[0].lower()
                raw = response.read(self.MAX_JSON_BYTES + 1)
            if content_type and content_type != "application/json":
                raise FalProviderError("PROVIDER_RESPONSE_INVALID")
            if len(raw) > self.MAX_JSON_BYTES:
                raise FalProviderError("PROVIDER_RESPONSE_INVALID")
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise FalProviderError("PROVIDER_RESPONSE_INVALID")
            return value
        except FalProviderError:
            raise
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise FalProviderError("PROVIDER_UNAUTHORIZED")
            if error.code == 422:
                raise FalProviderError("PROVIDER_REJECTED")
            raise FalProviderError("PROVIDER_UNAVAILABLE", transient=error.code == 429 or error.code >= 500)
        except (urllib.error.URLError, TimeoutError, OSError):
            raise FalProviderError("PROVIDER_UNAVAILABLE", transient=True)
        except (ValueError, TypeError):
            raise FalProviderError("PROVIDER_RESPONSE_INVALID")
