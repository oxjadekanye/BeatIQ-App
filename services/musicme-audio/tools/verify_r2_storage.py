#!/usr/bin/env python3
"""One-shot, credential-safe Cloudflare R2 verification for MusicMe DEV.

Run this interactively. Account ID, access key, secret key, and the optional unrelated bucket name are read with
terminal echo disabled and retained only in process memory. Output is deliberately sanitized: credentials, account
IDs, bucket names, object keys, response bodies, and signed URLs are never printed.

No third-party package is required. The tiny SigV4 client below exists only to exercise the production
S3ObjectStore without installing boto3 on an operator's machine.
"""
import datetime
import base64
import getpass
import hashlib
import hmac
import io
import os
import re
import socket
import ssl
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
import xml.etree.ElementTree as ET

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _SERVICE_ROOT)

from audioservice.storage import S3ObjectStore, StorageError, new_storage_key  # noqa: E402

TARGET_BUCKET = "beatiq-musicme-dev"
PREFIX = "dev"
REGION = "auto"
_ACCOUNT_ID = re.compile(r"^[0-9a-fA-F]{32}$")
_BUCKET = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")


class S3RequestError(Exception):
    """An intentionally body-free HTTP error compatible with storage._not_found()."""

    def __init__(self, status, safe_code=None, category=None, operation="request"):
        super().__init__("R2 request failed")
        self.status = int(status)
        self.safe_code = safe_code if re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", safe_code or "") else "Unknown"
        self.category = category if category in (
            "authentication", "access_denied", "dns", "tls", "timeout", "network", "checksum", "service", "client"
        ) else "service"
        self.operation = operation if re.fullmatch(r"[a-z0-9_]{1,40}", operation or "") else "request"
        self.response = {"ResponseMetadata": {"HTTPStatusCode": self.status},
                         "Error": {"Code": self.safe_code}}


def _local_name(tag):
    return tag.rsplit("}", 1)[-1]


def _response_error_code(body, status):
    try:
        root = ET.fromstring(body)
        for element in root.iter():
            if _local_name(element.tag) == "Code" and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", element.text or ""):
                return element.text
    except (ET.ParseError, TypeError):
        pass
    return f"HTTP{status}"


def _http_category(status, code):
    if status == 401 or code in ("InvalidAccessKeyId", "InvalidToken", "ExpiredToken", "SignatureDoesNotMatch",
                                 "AuthFailure"):
        return "authentication"
    if status == 403 or code in ("AccessDenied", "Unauthorized"):
        return "access_denied"
    if code in ("BadDigest", "InvalidDigest", "XAmzContentSHA256Mismatch"):
        return "checksum"
    return "service"


class MinimalR2Client:
    """Only the boto3-like methods S3ObjectStore and this verification need."""

    def __init__(self, endpoint, access_key, secret_key):
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query or parsed.fragment:
            raise ValueError("unsafe endpoint")
        self.endpoint = endpoint
        self.host = parsed.hostname
        self.access_key = access_key
        self.secret_key = secret_key

    def _request(self, method, bucket, key="", query=None, headers=None, body=b"", operation="request"):
        now = datetime.datetime.now(datetime.timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        date = now.strftime("%Y%m%d")
        payload_hash = hashlib.sha256(body).hexdigest()
        path = "/" + urllib.parse.quote(bucket, safe="-_.~")
        if key:
            path += "/" + urllib.parse.quote(key, safe="/-_.~")
        query = query or {}
        canonical_query = "&".join(
            f"{urllib.parse.quote(str(name), safe='-_.~')}={urllib.parse.quote(str(value), safe='-_.~')}"
            for name, value in sorted(query.items())
        )
        signed = {"host": self.host, "x-amz-content-sha256": payload_hash, "x-amz-date": amz_date}
        for name, value in (headers or {}).items():
            signed[name.strip().lower()] = " ".join(str(value).strip().split())
        signed_headers = ";".join(sorted(signed))
        canonical_headers = "".join(f"{name}:{signed[name]}\n" for name in sorted(signed))
        canonical_request = "\n".join(
            (method, path, canonical_query, canonical_headers, signed_headers, payload_hash)
        )
        scope = f"{date}/{REGION}/s3/aws4_request"
        string_to_sign = "\n".join(
            ("AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest())
        )

        def digest(key_bytes, value):
            return hmac.new(key_bytes, value.encode(), hashlib.sha256).digest()

        signing_key = digest(digest(digest(digest(("AWS4" + self.secret_key).encode(), date), REGION), "s3"),
                             "aws4_request")
        signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
        authorization = (f"AWS4-HMAC-SHA256 Credential={self.access_key}/{scope}, "
                         f"SignedHeaders={signed_headers}, Signature={signature}")
        url = self.endpoint + path + ("?" + canonical_query if canonical_query else "")
        request_headers = {name: value for name, value in signed.items() if name != "host"}
        request_headers["Authorization"] = authorization
        request = urllib.request.Request(url, data=body if method in ("PUT", "POST") else None,
                                         headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, {name.lower(): value for name, value in response.headers.items()}, response.read()
        except urllib.error.HTTPError as error:
            body = error.read(8192)
            code = _response_error_code(body, error.code)
            raise S3RequestError(error.code, code, _http_category(error.code, code), operation)
        except urllib.error.URLError as error:
            reason = error.reason
            if isinstance(reason, socket.gaierror):
                category, code = "dns", "DnsResolutionFailed"
            elif isinstance(reason, ssl.SSLError):
                category, code = "tls", "TlsFailed"
            elif isinstance(reason, (TimeoutError, socket.timeout)):
                category, code = "timeout", "ConnectionTimedOut"
            else:
                category, code = "network", "EndpointConnectionFailed"
            raise S3RequestError(0, code, category, operation)
        except (TimeoutError, socket.timeout):
            raise S3RequestError(0, "ConnectionTimedOut", "timeout", operation)
        except ssl.SSLError:
            raise S3RequestError(0, "TlsFailed", "tls", operation)
        except Exception:
            raise S3RequestError(0, "ClientRequestFailed", "client", operation)

    def head_bucket(self, Bucket):
        self._request("HEAD", Bucket, operation="head_bucket")
        return {}

    def put_object(self, Bucket, Key, Body, ContentLength, ContentType, ContentMD5, Metadata):
        body = Body.read() if hasattr(Body, "read") else bytes(Body)
        try:
            expected_md5 = base64.b64decode(ContentMD5, validate=True)
        except (ValueError, TypeError):
            raise S3RequestError(0, "InvalidContentMD5", "checksum", "put_object")
        if len(body) != ContentLength or not hmac.compare_digest(hashlib.md5(body).digest(), expected_md5):
            raise S3RequestError(0, "ClientContentMD5Mismatch", "checksum", "put_object")
        headers = {"content-type": ContentType, "content-length": str(ContentLength), "content-md5": ContentMD5}
        for name, value in (Metadata or {}).items():
            headers[f"x-amz-meta-{name}"] = value
        self._request("PUT", Bucket, Key, headers=headers, body=body, operation="put_object")

    def head_object(self, Bucket, Key):
        _, headers, _ = self._request("HEAD", Bucket, Key, operation="head_object")
        metadata = {name[len("x-amz-meta-"):]: value for name, value in headers.items()
                    if name.startswith("x-amz-meta-")}
        return {"ContentLength": int(headers.get("content-length", "-1")), "Metadata": metadata}

    def get_object(self, Bucket, Key):
        _, _, body = self._request("GET", Bucket, Key, operation="get_object")
        return {"Body": io.BytesIO(body)}

    def delete_object(self, Bucket, Key):
        self._request("DELETE", Bucket, Key, operation="delete_object")
        return {}

    def list_objects_v2(self, Bucket, Prefix=None, MaxKeys=1000, ContinuationToken=None):
        query = {"list-type": "2", "max-keys": str(MaxKeys)}
        if Prefix is not None:
            query["prefix"] = Prefix
        if ContinuationToken:
            query["continuation-token"] = ContinuationToken
        _, _, body = self._request("GET", Bucket, query=query, operation="list_objects_v2")
        try:
            root = ET.fromstring(body)
        except ET.ParseError:
            raise S3RequestError(0, "InvalidXmlResponse", "service", "list_objects_v2")
        result = {"Contents": [], "IsTruncated": False}
        for element in root.iter():
            name = _local_name(element.tag)
            if name == "Contents":
                item = {}
                for child in element:
                    if _local_name(child.tag) == "Key":
                        item["Key"] = child.text or ""
                result["Contents"].append(item)
            elif name == "IsTruncated":
                result["IsTruncated"] = (element.text or "").lower() == "true"
            elif name == "NextContinuationToken":
                result["NextContinuationToken"] = element.text or ""
        return result


def _prompt_secret(label):
    value = getpass.getpass(label).strip()
    if not value:
        raise ValueError("missing input")
    return value


def _write_probe(path):
    with wave.open(path, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(b"\x00\x00" * 800)
    os.chmod(path, 0o600)


def _verify_isolation(client, unrelated_bucket):
    """Perform a read-only denial check, or explicitly skip it when this account has no second bucket."""
    if not unrelated_bucket:
        print("SKIPPED isolation: no unrelated bucket exists; cross-bucket denial remains unverified.")
        return False
    try:
        client.list_objects_v2(Bucket=unrelated_bucket, MaxKeys=1)
    except S3RequestError as error:
        if error.status != 403:
            raise RuntimeError("unrelated bucket did not return AccessDenied")
    else:
        raise RuntimeError("token accessed unrelated bucket")
    print("PASS isolation: unrelated bucket returned AccessDenied to a read-only list request.")
    return True


def _print_diagnostic(diagnostic):
    """Print only fields already reduced to bounded allow-listed values."""
    diagnostic = diagnostic or {}
    operation = diagnostic.get("operation", "unknown")
    status = diagnostic.get("status")
    code = diagnostic.get("code", "Unknown")
    category = diagnostic.get("category", "service")
    if not re.fullmatch(r"[a-z0-9_]{1,40}", str(operation)):
        operation = "unknown"
    if not isinstance(status, int) or not 0 <= status <= 599:
        status = "none"
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", str(code)):
        code = "Unknown"
    if category not in ("authentication", "access_denied", "dns", "tls", "timeout", "network",
                        "checksum", "dependency", "service", "client"):
        category = "service"
    print(f"DIAGNOSTIC: operation={operation} category={category} http_status={status} code={code}")


def main():
    print("MusicMe R2 verification (inputs are hidden; nothing is saved).")
    account_id = _prompt_secret("Cloudflare account ID: ")
    access_key = _prompt_secret("R2 Access Key ID: ")
    secret_key = _prompt_secret("R2 Secret Access Key: ")
    unrelated_bucket = getpass.getpass(
        "Existing unrelated bucket name (optional; Return to skip read-only denial test): "
    ).strip()
    if not _ACCOUNT_ID.fullmatch(account_id):
        print("FAIL input: Cloudflare account ID is not a 32-character hexadecimal ID.")
        return 2
    if unrelated_bucket and (unrelated_bucket == TARGET_BUCKET or not _BUCKET.fullmatch(unrelated_bucket)):
        print("FAIL input: provide a valid, existing bucket other than the MusicMe DEV bucket.")
        return 2

    endpoint = f"https://{account_id.lower()}.eu.r2.cloudflarestorage.com"
    try:
        client = MinimalR2Client(endpoint, access_key, secret_key)
        with tempfile.TemporaryDirectory(prefix="musicme-r2-check-") as scratch:
            store = S3ObjectStore(endpoint, TARGET_BUCKET, REGION, access_key, secret_key, "", PREFIX, scratch,
                                  64 * 1024 * 1024, force_path_style=True, client=client)
            key = new_storage_key("phase-b-verifier", uuid.uuid4(), "wav")
            temp = store.temp_path(key)
            _write_probe(temp)
            with open(temp, "rb") as source:
                expected = source.read()
            expected_sha = hashlib.sha256(expected).hexdigest()

            size, committed_sha = store.commit_file(temp, key, "wav")
            if size != len(expected) or not hmac.compare_digest(committed_sha, expected_sha):
                raise RuntimeError("upload checksum mismatch")
            print("PASS upload: production storage adapter uploaded and HEAD-verified the private object.")

            page = client.list_objects_v2(Bucket=TARGET_BUCKET, Prefix=f"{PREFIX}/{key}", MaxKeys=2)
            if [item.get("Key") for item in page["Contents"]] != [f"{PREFIX}/{key}"]:
                raise RuntimeError("list mismatch")
            print("PASS list: scoped token listed the temporary object.")

            with store.open(key) as source:
                retrieved = source.read()
            retrieved_sha = hashlib.sha256(retrieved).hexdigest()
            if retrieved != expected or not hmac.compare_digest(retrieved_sha, expected_sha):
                raise RuntimeError("download checksum mismatch")
            print(f"PASS download: bytes and SHA-256 match ({retrieved_sha}).")

            store.delete(key)
            if store.exists(key):
                raise RuntimeError("object remains after delete")
            print("PASS delete: object was deleted and HEAD now returns not found.")

            isolation_verified = _verify_isolation(client, unrelated_bucket)

            remaining = client.list_objects_v2(Bucket=TARGET_BUCKET, MaxKeys=1)
            if remaining["Contents"]:
                raise RuntimeError("DEV bucket is not empty")
            print("PASS cleanup: MusicMe DEV bucket contains no objects.")
        if isolation_verified:
            print("RESULT: PASS")
            print("SECURITY: cross-bucket token isolation verified.")
        else:
            print("RESULT: PASS_WITH_SKIPPED_CHECK")
            print("SECURITY: INCOMPLETE — core storage passed, but cross-bucket token isolation was not tested.")
        print("CONFIG: EU jurisdiction endpoint, region=auto, path-style=true, private bucket.")
        return 0
    except StorageError as error:
        _print_diagnostic(error.diagnostic)
        print(f"RESULT: FAIL ({error.code})")
        return 1
    except S3RequestError as error:
        _print_diagnostic({"operation": error.operation, "status": error.status, "code": error.safe_code,
                           "category": error.category})
        print("RESULT: FAIL (R2_REQUEST_FAILED)")
        return 1
    except Exception as error:
        print(f"RESULT: FAIL ({type(error).__name__})")
        return 1
    finally:
        account_id = access_key = secret_key = unrelated_bucket = None


if __name__ == "__main__":
    raise SystemExit(main())
