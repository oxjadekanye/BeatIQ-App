"""Pinned ACE-Step code and weights for a GPU worker host, prepared BEFORE the worker serves anything.

The only network access in the worker's lifecycle happens here: the minimum pinned weights (no LM planner) are
downloaded at the pinned revision, then every file's SHA-256 is checked against manifest.json. The runner later
forces offline mode and re-verifies everything at its own start, so a tampered or partial cache is refused twice.

    python -I -m audioservice.gpu_worker.weights --home /opt/acestep --checkpoints /data/checkpoints [--verify-only]
"""
import argparse
import os
import sys

from ..acestep import manifest as pinned


def check_code(home, manifest):
    if pinned.read_git_head(home) != manifest["code"]["commit"]:
        raise pinned.ManifestError("CODE_MISMATCH", "ACE-Step checkout is not the pinned commit")


def download(checkpoints, manifest):
    from huggingface_hub import snapshot_download  # present only in the GPU image
    folders = sorted({rel.split("/")[0] for rel in manifest["weights"]["files"]})
    snapshot_download(manifest["weights"]["repository"], revision=manifest["weights"]["revision"], local_dir=checkpoints,
                      allow_patterns=[f"{folder}/*" for folder in folders] + ["config.json"])


def prepare(home, checkpoints, verify_only=False, manifest=None, downloader=download):
    """Returns the verified {file: sha256}. Downloads only what is missing or wrong (unless verify_only)."""
    manifest = manifest or pinned.load()
    check_code(home, manifest)
    os.makedirs(checkpoints, mode=0o755, exist_ok=True)
    try:
        return pinned.verify_checkpoints(checkpoints, manifest)
    except pinned.ManifestError as error:
        if verify_only or error.code == "FORBIDDEN_WEIGHTS":
            raise
    downloader(checkpoints, manifest)
    return pinned.verify_checkpoints(checkpoints, manifest)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Prepare and verify the pinned ACE-Step weights for a GPU worker")
    parser.add_argument("--home", default=os.environ.get("MUSICME_WORKER_ACESTEP_HOME", "/opt/acestep"))
    parser.add_argument("--checkpoints", default=None)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args(argv)
    checkpoints = args.checkpoints or os.path.join(args.home, "checkpoints")
    try:
        digests = prepare(args.home, checkpoints, verify_only=args.verify_only)
    except pinned.ManifestError as error:
        print(f"weights refused: {error.code}", file=sys.stderr)
        return 2
    print(f"verified {len(digests)} pinned files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
