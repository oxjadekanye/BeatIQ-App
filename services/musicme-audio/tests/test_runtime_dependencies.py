"""Guard the split between human-maintained inputs and Render's reproducible runtime lock."""
import os
import re


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _text(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as source:
        return source.read()


def _requirement_names(source):
    names = set()
    for line in source.splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "-", "\\")):
            names.add(re.split(r"[<=>!~\s]", line, maxsplit=1)[0].lower())
    return names


def test_runtime_and_development_dependencies_are_separate():
    runtime = _requirement_names(_text("requirements.txt"))
    development = _requirement_names(_text("requirements-dev.txt"))
    assert runtime == {"boto3", "cryptography", "psycopg2-binary"}
    assert development == {"pytest", "pyyaml"}
    assert not runtime & development


def test_runtime_lock_is_fully_pinned_and_hashed():
    lock = _text("requirements.lock")
    names = _requirement_names(lock)
    assert {"boto3", "botocore", "cryptography", "psycopg2-binary", "s3transfer"} <= names
    assert "pytest" not in names and "pyyaml" not in names
    blocks = re.split(r"\n(?=[a-z0-9][a-z0-9._-]*==)", lock)
    package_blocks = [block for block in blocks if re.match(r"[a-z0-9][a-z0-9._-]*==", block)]
    assert package_blocks
    for block in package_blocks:
        first = block.splitlines()[0]
        assert re.fullmatch(r"[a-z0-9][a-z0-9._-]*==[^\\\s]+ \\", first), first
        assert "--hash=sha256:" in block, first


def test_render_uses_the_hash_locked_runtime_file():
    blueprint = _text(os.path.join("..", "..", "infra", "render", "musicme-production-fal.yaml"))
    assert 'buildCommand: "python3 -m pip install --require-hashes --only-binary=:all: -r requirements.lock"' in blueprint
    assert "buildCommand: pip install -r requirements.txt" not in blueprint
