"""Hosted DEV-auth revocation remains authenticated, bounded, cached and fail closed."""
import json
import time

import pytest

from conftest import settings, token
from audioservice.auth import TokenVerifier
from audioservice.errors import ServiceError


class Response:
    def __init__(self, body, content_type="application/json"):
        self.body = body
        self.headers = {"Content-Type": content_type}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def read(self, _limit):
        return self.body


class Opener:
    def __init__(self, response, credential):
        self.response, self.credential, self.calls = response, credential, 0

    def open(self, request, timeout):
        assert request.full_url == "https://auth.example/internal/revocation"
        assert request.get_header("Authorization") == "Bearer " + self.credential
        assert request.get_header("Accept") == "application/json" and timeout == 5
        self.calls += 1
        return self.response


def test_remote_revocation_cache_and_errors():
    clock = [time.time()]
    credential = "audio-revocation-credential-32-chars"
    response = Response(json.dumps({"not_before": {}, "revoked_jti": []}).encode())
    opener = Opener(response, credential)
    verifier = TokenVerifier(settings(jwt_revocation_url="https://auth.example/internal/revocation",
                                      jwt_revocation_token=credential, jwt_revocation_cache_seconds=5),
                             clock=lambda: clock[0], revocation_opener=opener)
    access = token("dev-a", iat=int(clock[0]), jti="j-remote")
    assert verifier.account("Bearer " + access) == "dev-a"
    response.body = json.dumps({"not_before": {"dev-a": int(clock[0])}, "revoked_jti": []}).encode()
    assert verifier.account("Bearer " + access) == "dev-a" and opener.calls == 1
    clock[0] += 6
    with pytest.raises(ServiceError) as revoked:
        verifier.account("Bearer " + access)
    assert revoked.value.status == 401 and opener.calls == 2

    clock[0] += 6
    response.body = b"x" * (64 * 1024 + 1)
    with pytest.raises(ServiceError) as oversized:
        verifier.account("Bearer " + access)
    assert oversized.value.status == 503
