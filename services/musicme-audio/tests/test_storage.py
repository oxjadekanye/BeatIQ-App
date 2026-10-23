"""Private storage: key safety, content validation, permissions and signed links."""
import base64
import hashlib
import io
import os
import stat
import time
import uuid
import wave

import pytest

from audioservice.storage import LinkSigner, LocalObjectStore, S3ObjectStore, StorageError, account_key, new_storage_key
from audioservice.validation import AudioValidationError, validate_wav


def write_wav(path, seconds=1.0, amplitude=8000, rate=44100):
    import math
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(max(-32768, min(32767, int(amplitude * math.sin(n / 10)))).to_bytes(2, "little", signed=True) for n in range(int(seconds * rate))))


@pytest.fixture
def store(tmp_path):
    return LocalObjectStore(str(tmp_path / "root"), max_bytes=2 * 1024 * 1024)


def test_keys_are_server_generated_and_hide_the_account_id():
    key = new_storage_key("user-42@example.com", str(uuid.uuid4()), "wav")
    assert key.startswith("objects/" + account_key("user-42@example.com") + "/")
    assert "user-42" not in key and "example" not in key
    assert account_key("a") != account_key("b")


@pytest.mark.parametrize("key", [
    "../etc/passwd", "objects/../../etc/passwd", "/etc/passwd", "objects/abc/def.wav",
    "objects/" + "a" * 32 + "/../x.wav", "objects\\" + "a" * 32 + "\\x.wav", "objects/" + "a" * 32 + "/x.wav\x00.wav",
    "objects/" + "A" * 32 + "/" + str(uuid.uuid4()) + ".wav", "objects/" + "a" * 32 + "/" + str(uuid.uuid4()) + ".exe",
])
def test_path_traversal_and_malformed_keys_are_rejected(store, key):
    with pytest.raises(StorageError):
        store.open(key)
    with pytest.raises(StorageError):
        store.delete(key)


def test_symlinked_account_directory_cannot_escape_the_root(store, tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    directory = "a" * 32
    os.makedirs(os.path.join(store.root, "objects"), exist_ok=True)
    os.symlink(outside, os.path.join(store.root, "objects", directory))
    key = f"objects/{directory}/{uuid.uuid4()}.wav"
    with pytest.raises(StorageError):
        store.open(key)


def test_commit_validates_content_and_size_and_is_owner_only(store, tmp_path):
    key = new_storage_key("acct", str(uuid.uuid4()), "wav")
    temp = store.temp_path(key)
    write_wav(temp)
    size, digest = store.commit_file(temp, key, "wav")
    assert size > 0 and len(digest) == 64 and store.exists(key)
    path = os.path.join(store.root, key)
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode) == 0o700

    wrong = new_storage_key("acct", str(uuid.uuid4()), "mp3")
    temp = store.temp_path(wrong)
    write_wav(temp)
    with pytest.raises(StorageError, match="INVALID_CONTENT"):
        store.commit_file(temp, wrong, "mp3")  # WAV bytes declared as MP3

    big = new_storage_key("acct", str(uuid.uuid4()), "wav")
    temp = store.temp_path(big)
    write_wav(temp, seconds=30)  # ~2.6 MB > 2 MB limit
    with pytest.raises(StorageError, match="INVALID_SIZE"):
        store.commit_file(temp, big, "wav")

    elsewhere = tmp_path / "elsewhere.wav"
    write_wav(str(elsewhere))
    with pytest.raises(StorageError):
        store.commit_file(str(elsewhere), new_storage_key("acct", str(uuid.uuid4()), "wav"), "wav")


def test_delete_removes_the_file_and_orphans_are_swept(store):
    key = new_storage_key("acct", str(uuid.uuid4()), "wav")
    temp = store.temp_path(key)
    write_wav(temp)
    store.commit_file(temp, key, "wav")
    assert store.delete(key) and not store.exists(key) and not store.delete(key)

    orphan = new_storage_key("acct", str(uuid.uuid4()), "wav")
    write_wav(store.temp_path(orphan))  # abandoned temp file
    kept = new_storage_key("acct", str(uuid.uuid4()), "wav")
    t = store.temp_path(kept)
    write_wav(t)
    store.commit_file(t, kept, "wav")
    assert store.sweep_orphans({kept}, older_than_seconds=3600) == 0  # too recent
    assert store.sweep_orphans({kept}, older_than_seconds=0) == 1
    assert store.exists(kept)


class Clock:
    def __init__(self, now=1_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


def test_signed_links_are_bound_to_object_and_owner_and_expire():
    clock = Clock()
    signer = LinkSigner("link-secret-link-secret-link-secret-12", 600, clock=clock)
    object_id = str(uuid.uuid4())
    token, expires = signer.sign(object_id, "acct-a")
    assert expires == int(clock.now) + 600
    assert signer.verify(token) == (object_id, account_key("acct-a"))

    payload, signature = token.split(".")
    tampered = payload[:-2] + ("AA" if payload[-2:] != "AA" else "BB") + "." + signature
    for bad in [tampered, payload + "." + signature[::-1], "not-a-token", "", token + "x", "../../etc/passwd"]:
        with pytest.raises(StorageError) as error:
            signer.verify(bad)
        assert error.value.code == "LINK_INVALID"

    other = LinkSigner("another-secret-another-secret-another-1", 600, clock=clock)
    with pytest.raises(StorageError):
        other.verify(token)

    clock.now += 599.999  # valid until the last instant of the configured lifetime...
    assert signer.verify(token)[0] == object_id
    clock.now = expires  # ...and expired exactly when it ends
    with pytest.raises(StorageError) as error:
        signer.verify(token)
    assert error.value.code == "LINK_EXPIRED"


def test_default_link_lifetime_is_ten_minutes_and_never_more_than_fifteen():
    from conftest import settings
    assert settings().download_link_seconds == 600
    for bad in (59, 901, 3600):
        with pytest.raises(ValueError):
            settings(download_link_seconds=bad)


class FakeS3:
    def __init__(self):
        self.objects = {}

    def head_bucket(self, Bucket):
        assert Bucket == "beatiq-musicme-dev"
        return {}

    corrupt_in_transit = False
    drop_metadata = False

    def put_object(self, Bucket, Key, Body, ContentLength, ContentType, ContentMD5, Metadata):
        data = Body.read()
        if self.corrupt_in_transit:
            data = data[:-1] + bytes([data[-1] ^ 1])
        # Like S3/R2: the store recomputes Content-MD5 over the bytes it received and refuses a mismatch.
        if base64.b64encode(hashlib.md5(data).digest()).decode() != ContentMD5 or len(data) != ContentLength:
            error = RuntimeError("BadDigest")
            error.response = {"Error": {"Code": "BadDigest"}, "ResponseMetadata": {"HTTPStatusCode": 400}}
            raise error
        self.objects[Key] = {"body": data, "metadata": {} if self.drop_metadata else dict(Metadata),
                             "modified": time.time(), "content_type": ContentType}

    def head_object(self, Bucket, Key):
        if Key not in self.objects:
            error = RuntimeError("missing")
            error.response = {"Error": {"Code": "NoSuchKey"}, "ResponseMetadata": {"HTTPStatusCode": 404}}
            raise error
        obj = self.objects[Key]
        return {"ContentLength": len(obj["body"]), "Metadata": obj["metadata"]}

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            error = RuntimeError("missing")
            error.response = {"Error": {"Code": "NoSuchKey"}, "ResponseMetadata": {"HTTPStatusCode": 404}}
            raise error
        return {"Body": io.BytesIO(self.objects[Key]["body"])}

    def delete_object(self, Bucket, Key):
        self.objects.pop(Key, None)

    def generate_presigned_url(self, operation, Params, ExpiresIn):
        assert operation == "get_object" and ExpiresIn == 600
        return "https://objects.example.test/private-signed-url"

    def list_objects_v2(self, Bucket, Prefix, MaxKeys, ContinuationToken=None):
        return {"IsTruncated": False, "Contents": [
            {"Key": key, "LastModified": value["modified"]}
            for key, value in self.objects.items() if key.startswith(Prefix)
        ]}


def test_s3_store_uploads_verifies_presigns_and_cleans_private_objects(tmp_path):
    client = FakeS3()
    store = S3ObjectStore("https://account.r2.cloudflarestorage.com", "beatiq-musicme-dev", "auto",
                          "access-key", "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"),
                          2 * 1024 * 1024, client=client)
    key = new_storage_key("account-a", str(uuid.uuid4()), "wav")
    temp = store.temp_path(key)
    write_wav(temp)
    size, digest = store.commit_file(temp, key, "wav")
    assert size > 44 and len(digest) == 64 and store.exists(key)
    assert list(client.objects) == ["dev/" + key]
    with store.open(key) as audio:
        assert audio.read(4) == b"RIFF"
    assert store.presigned_download(key, "audio/wav", "musicme.wav", 600).startswith("https://")
    assert store.sweep_orphans({key}, 0) == 0
    assert store.sweep_orphans(set(), 0) == 1 and not store.exists(key)


def test_s3_store_rejects_non_scratch_files_and_wrong_content(tmp_path):
    store = S3ObjectStore("https://objects.example.test", "beatiq-musicme-dev", "auto", "access-key",
                          "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"), 2 * 1024 * 1024,
                          client=FakeS3())
    outside = tmp_path / "outside.wav"
    write_wav(str(outside))
    with pytest.raises(StorageError, match="INVALID_CONTENT"):
        store.commit_file(str(outside), new_storage_key("a", str(uuid.uuid4()), "wav"), "wav")
    key = new_storage_key("a", str(uuid.uuid4()), "mp3")
    temp = store.temp_path(key)
    write_wav(temp)
    with pytest.raises(StorageError, match="INVALID_CONTENT"):
        store.commit_file(temp, key, "mp3")


@pytest.mark.parametrize("kind, code", [
    ("silent", "AUDIO_SILENT"), ("clipped", "AUDIO_CLIPPED"), ("short", "AUDIO_DURATION"),
    ("garbage", "AUDIO_INVALID"), ("empty", "AUDIO_INVALID"), ("rate", "AUDIO_INVALID"),
])
def test_invalid_audio_is_rejected(tmp_path, kind, code):
    path = str(tmp_path / "x.wav")
    if kind == "silent":
        write_wav(path, seconds=10, amplitude=0)
    elif kind == "clipped":
        write_wav(path, seconds=10, amplitude=50000)
    elif kind == "short":
        write_wav(path, seconds=3)
    elif kind == "garbage":
        open(path, "wb").write(b"RIFF....WAVEnope" * 10)
    elif kind == "empty":
        open(path, "wb").close()
    else:
        write_wav(path, seconds=10, rate=8000)
    with pytest.raises(AudioValidationError) as error:
        validate_wav(path, 10, 0.25, 10 * 1024 * 1024)
    assert error.value.code == code


def test_valid_audio_passes(tmp_path):
    path = str(tmp_path / "ok.wav")
    write_wav(path, seconds=10)
    info = validate_wav(path, 10, 0.25, 10 * 1024 * 1024)
    assert info == {"duration_ms": 10000, "sample_rate": 44100, "channels": 1}


def test_s3_store_never_keeps_a_corrupted_or_unverified_upload(tmp_path):
    client = FakeS3()
    store = S3ObjectStore("https://objects.example.test", "beatiq-musicme-dev", "auto", "access-key",
                          "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"), 2 * 1024 * 1024,
                          client=client)
    client.corrupt_in_transit = True
    key = new_storage_key("a", str(uuid.uuid4()), "wav")
    temp = store.temp_path(key)
    write_wav(temp)
    with pytest.raises(StorageError, match="STORAGE_UNAVAILABLE"):
        store.commit_file(temp, key, "wav")
    assert client.objects == {}
    client.corrupt_in_transit, client.drop_metadata = False, True
    with pytest.raises(StorageError, match="STORAGE_INTEGRITY"):
        store.commit_file(temp, key, "wav")
    assert client.objects == {}  # an upload that can't be verified is deleted, never published
    client.drop_metadata = False
    store.commit_file(temp, key, "wav")
    assert client.objects["dev/" + key]["content_type"] == "audio/wav"


def test_s3_store_preserves_only_sanitized_operation_diagnostics(tmp_path):
    class MissingPutObject:
        def head_bucket(self, Bucket):
            return {}

        def delete_object(self, Bucket, Key):
            return {}

    store = S3ObjectStore("https://objects.example.test", "beatiq-musicme-dev", "auto", "access-key",
                          "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"), 2 * 1024 * 1024,
                          client=MissingPutObject())
    key = new_storage_key("a", str(uuid.uuid4()), "wav")
    temp = store.temp_path(key)
    write_wav(temp)

    with pytest.raises(StorageError, match="STORAGE_UNAVAILABLE") as caught:
        store.commit_file(temp, key, "wav")

    assert caught.value.diagnostic == {
        "operation": "put_object", "status": None, "code": "ClientMethodMissing", "category": "client"
    }
    assert "access-key" not in str(caught.value) and "secret-key" not in str(caught.value)


def test_s3_store_maps_provider_errors_without_retaining_messages(tmp_path):
    class Refused:
        def head_bucket(self, Bucket):
            error = RuntimeError("secret material and a signed URL must never survive")
            error.response = {"Error": {"Code": "SignatureDoesNotMatch"},
                              "ResponseMetadata": {"HTTPStatusCode": 403}}
            raise error

    with pytest.raises(StorageError, match="STORAGE_UNAVAILABLE") as caught:
        S3ObjectStore("https://objects.example.test", "beatiq-musicme-dev", "auto", "access-key",
                      "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"), 2 * 1024 * 1024,
                      client=Refused())

    assert caught.value.diagnostic == {
        "operation": "head_bucket", "status": 403, "code": "SignatureDoesNotMatch",
        "category": "authentication"
    }
    assert "secret material" not in repr(caught.value.diagnostic)


def test_s3_store_refuses_unsafe_keys_and_non_https_presigned_links(tmp_path):
    client = FakeS3()
    store = S3ObjectStore("https://objects.example.test", "beatiq-musicme-dev", "auto", "access-key",
                          "secret-key-at-least-sixteen", "", "dev", str(tmp_path / "scratch"), 2 * 1024 * 1024,
                          client=client)
    for bad in ("../escape", "objects/../../x", "", None):
        with pytest.raises(StorageError, match="INVALID_KEY"):
            store.exists(bad)
    client.generate_presigned_url = lambda *a, **k: "http://objects.example.test/plain"
    with pytest.raises(StorageError, match="STORAGE_UNAVAILABLE"):
        store.presigned_download(new_storage_key("a", str(uuid.uuid4()), "wav"), "audio/wav", "x.wav", 600)
