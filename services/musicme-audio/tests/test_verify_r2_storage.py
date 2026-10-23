import base64
import hashlib
import io

import pytest

from tools.verify_r2_storage import MinimalR2Client, S3RequestError, _print_diagnostic, _verify_isolation


class Client:
    def __init__(self, outcome=None):
        self.outcome = outcome
        self.calls = []

    def list_objects_v2(self, **kwargs):
        self.calls.append(kwargs)
        if self.outcome:
            raise self.outcome
        return {"Contents": []}


def test_isolation_is_explicitly_skipped_without_an_unrelated_bucket(capsys):
    client = Client()

    assert _verify_isolation(client, "") is False
    assert client.calls == []
    output = capsys.readouterr().out
    assert "SKIPPED isolation" in output
    assert "unverified" in output
    assert "PASS isolation" not in output


def test_isolation_passes_only_on_access_denied(capsys):
    client = Client(S3RequestError(403))

    assert _verify_isolation(client, "unrelated-bucket") is True
    assert client.calls == [{"Bucket": "unrelated-bucket", "MaxKeys": 1}]
    assert "PASS isolation" in capsys.readouterr().out


@pytest.mark.parametrize("outcome", [None, S3RequestError(404), S3RequestError(0)])
def test_isolation_does_not_pass_for_access_or_ambiguous_errors(outcome):
    client = Client(outcome)

    with pytest.raises(RuntimeError):
        _verify_isolation(client, "unrelated-bucket")


def test_minimal_client_matches_production_put_object_contract():
    client = MinimalR2Client("https://" + "a" * 32 + ".eu.r2.cloudflarestorage.com", "access", "secret")
    calls = []
    client._request = lambda *args, **kwargs: calls.append((args, kwargs)) or (200, {}, b"")
    body = b"RIFF" + b"\x00" * 16
    content_md5 = base64.b64encode(hashlib.md5(body).digest()).decode()

    client.put_object(Bucket="beatiq-musicme-dev", Key="dev/probe.wav", Body=io.BytesIO(body),
                      ContentLength=len(body), ContentType="audio/wav", ContentMD5=content_md5,
                      Metadata={"sha256": hashlib.sha256(body).hexdigest()})

    args, kwargs = calls[0]
    assert args == ("PUT", "beatiq-musicme-dev", "dev/probe.wav")
    assert kwargs["operation"] == "put_object" and kwargs["body"] == body
    assert kwargs["headers"]["content-md5"] == content_md5
    assert kwargs["headers"]["content-length"] == str(len(body))


def test_minimal_client_rejects_a_local_content_md5_mismatch_without_network():
    client = MinimalR2Client("https://" + "a" * 32 + ".eu.r2.cloudflarestorage.com", "access", "secret")
    client._request = lambda *args, **kwargs: pytest.fail("network must not be called")

    with pytest.raises(S3RequestError) as caught:
        client.put_object(Bucket="beatiq-musicme-dev", Key="dev/probe.wav", Body=io.BytesIO(b"data"),
                          ContentLength=4, ContentType="audio/wav",
                          ContentMD5=base64.b64encode(b"x" * 16).decode(), Metadata={})

    assert caught.value.category == "checksum"
    assert caught.value.safe_code == "ClientContentMD5Mismatch"


def test_diagnostic_output_is_bounded_and_sanitized(capsys):
    _print_diagnostic({"operation": "https://secret.example", "status": "403 secret", "code": "bad secret value",
                       "category": "not-safe"})

    output = capsys.readouterr().out
    assert output == "DIAGNOSTIC: operation=unknown category=service http_status=none code=Unknown\n"
