"""Pinned ACE-Step manifest: loading, checkpoint verification and handshake checks (standard library only).

Used on both sides of the process boundary: the runner verifies the files on disk before loading anything, and the
service independently checks that the runner's handshake reports exactly the pinned commit, revision and hashes.
"""
import hashlib
import json
import os

DEFAULT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest.json")


class ManifestError(Exception):
    def __init__(self, code, message):
        super().__init__(code)
        self.code = code
        self.message = message


def load(path=DEFAULT_PATH):
    with open(path) as f:
        manifest = json.load(f)
    try:
        commit = manifest["code"]["commit"]
        files = manifest["weights"]["files"]
        revision = manifest["weights"]["revision"]
        limits = manifest["limits"]
    except (KeyError, TypeError):
        raise ManifestError("MANIFEST_MISMATCH", "manifest is missing required sections")
    hexes = [commit, revision] + list(files.values())
    if not files or not all(isinstance(h, str) and len(h) in (40, 64) and all(c in "0123456789abcdef" for c in h) for h in hexes):
        raise ManifestError("MANIFEST_MISMATCH", "manifest contains invalid pins")
    if any(os.path.isabs(p) or ".." in p.split("/") for p in files):
        raise ManifestError("MANIFEST_MISMATCH", "manifest file paths must be relative")
    for key in ("max_lyric_chars", "max_caption_chars", "min_duration_seconds", "max_duration_seconds", "max_takes", "inference_steps"):
        if not isinstance(limits.get(key), int):
            raise ManifestError("MANIFEST_MISMATCH", f"manifest limit {key} missing")
    return manifest


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_checkpoints(checkpoints_dir, manifest):
    """Checks every pinned file's SHA-256 and that no LM planning model is present. Returns {path: sha256}."""
    root = os.path.realpath(checkpoints_dir)
    digests = {}
    for rel, want in manifest["weights"]["files"].items():
        path = os.path.realpath(os.path.join(root, rel))
        if os.path.commonpath([root, path]) != root or not os.path.isfile(path):
            raise ManifestError("MANIFEST_MISMATCH", f"pinned file missing: {rel}")
        digests[rel] = sha256_file(path)
        if digests[rel] != want:
            raise ManifestError("MANIFEST_MISMATCH", f"checksum mismatch: {rel}")
    for name in manifest["weights"].get("forbidden_dirs", []):
        if os.path.exists(os.path.join(root, name)):
            raise ManifestError("FORBIDDEN_WEIGHTS", f"unexpected model directory present: {name}")
    return digests


def read_git_head(home):
    """Resolves the checked-out commit without running git (handles detached HEAD, loose and packed refs)."""
    git = os.path.join(home, ".git")
    with open(os.path.join(git, "HEAD")) as f:
        head = f.read().strip()
    if not head.startswith("ref:"):
        return head
    ref = head.split(":", 1)[1].strip()
    loose = os.path.join(git, *ref.split("/"))
    if os.path.isfile(loose):
        with open(loose) as f:
            return f.read().strip()
    packed = os.path.join(git, "packed-refs")
    if os.path.isfile(packed):
        with open(packed) as f:
            for line in f:
                parts = line.strip().split(" ")
                if len(parts) == 2 and parts[1] == ref:
                    return parts[0]
    raise ManifestError("CODE_MISMATCH", "cannot resolve the ACE-Step checkout's commit")


def check_hello(hello, manifest):
    """Service-side handshake check. Raises ManifestError unless the runner reports exactly the pinned model."""
    if not hello.get("ok"):
        raise ManifestError("MANIFEST_MISMATCH", "runner did not report a verified model")
    if hello["code_commit"] != manifest["code"]["commit"]:
        raise ManifestError("CODE_MISMATCH", "runner reports a different ACE-Step commit")
    if hello["weights_revision"] != manifest["weights"]["revision"]:
        raise ManifestError("MANIFEST_MISMATCH", "runner reports a different weights revision")
    if hello["weights_sha256"] != manifest["weights"]["files"]:
        raise ManifestError("MANIFEST_MISMATCH", "runner reports different weight hashes")
    if hello["config_path"] != manifest["weights"]["config_path"]:
        raise ManifestError("INVALID_MODEL_CONFIG", "runner loaded a different model configuration")
    if hello["dtype"] not in manifest["runtime"]["allowed_dtypes"]:
        raise ManifestError("UNSUPPORTED_DTYPE", f"runner dtype {hello['dtype']} is not allowed")
    if hello["dtype"] == "torch.float16" and manifest["runtime"].get("float16_requires_lyric_encoder_fix"):
        selftest = hello.get("selftest") or {}
        if hello["fp16_fix"] != "applied" or selftest.get("passed") is not True:
            raise ManifestError("SELFTEST_FAILED", "float16 model without a passing lyric-encoder fix self-test")
    return True
