"""BeatIQ DEV auth stub: an isolated identity provider for the DEV app and the local MusicMe services.

DEVELOPMENT ONLY. Standard library only. It never talks to production BeatIQ. It binds to loopback by default; an
explicit environment gate permits an isolated HTTPS-terminated Render DEV service. It implements just enough of the
BeatIQ auth API for the DEV app to sign in:

    POST /api/v1/auth/token/          {"email", "password"} -> {"access", "refresh"}   (SimpleJWT-shaped)
    POST /api/v1/auth/token/refresh/  {"refresh"}           -> {"access"}
    GET  /healthz
    anything else                     -> 503 (the DEV app's other BeatIQ features fail closed)

Tokens are short-lived HS256 JWTs (access 60 min, refresh 8 h) with iss/aud/jti/iat, signed with a DEV-only secret
that exists only on this Mac (never in the APK). Revocation: `revoke --email` or `revoke --all` writes a revocation
file the MusicMe services check on every request; `rotate-secret` invalidates every token at once.

Usage:
    python3 devauth.py init
    python3 devauth.py create-user --email tester@dev.local     # prints a random password once
    python3 devauth.py serve [--port 8000]
    python3 devauth.py revoke (--email EMAIL | --all)
    python3 devauth.py rotate-secret
    python3 devauth.py service-env                              # env lines for the MusicMe services (paths only)
"""
import argparse
import base64
import errno
import hashlib
import hmac
import json
import os
import secrets
import stat
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ISSUER = "beatiq-dev-auth"
AUDIENCE = "musicme-dev"
ACCESS_SECONDS = 60 * 60
REFRESH_SECONDS = 8 * 60 * 60
PBKDF2_ROUNDS = 200_000
LOOPBACK = ("127.0.0.1", "::1", "localhost")
MAX_FAILURES_PER_MINUTE = 5
MAX_FAILURE_KEYS = 1024
_HOME_LOCK = threading.Lock()
_VERIFIED_HOMES = set()


def _verify_private_writes(path):
    """Prove that the mounted directory supports writable, owner-only regular files."""
    probe = os.path.join(path, f".write-test-{uuid.uuid4().hex}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = None
    try:
        fd = os.open(probe, flags, 0o600)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077:
            raise PermissionError("DEV auth storage cannot create owner-only files.")
        os.write(fd, b"ok")
        os.fsync(fd)
    finally:
        if fd is not None:
            os.close(fd)
        try:
            os.unlink(probe)
        except FileNotFoundError:
            pass


def home():
    path = os.path.abspath(os.environ.get("MUSICME_DEV_AUTH_HOME") or os.path.expanduser("~/.cache/musicme-dev/auth"))
    with _HOME_LOCK:
        if path in _VERIFIED_HOMES:
            return path
        os.makedirs(path, mode=0o700, exist_ok=True)
        info = os.lstat(path)
        if not stat.S_ISDIR(info.st_mode):
            raise NotADirectoryError(f"DEV auth storage is not a real directory: {path}")
        try:
            os.chmod(path, 0o700)
        except OSError as error:
            # Render owns the persistent-disk mount point and may reject chmod even for the service user.
            # Accept only that known mount behavior, then verify that private file creation actually works.
            if error.errno != errno.EPERM:
                raise
        _verify_private_writes(path)
        _VERIFIED_HOMES.add(path)
    return path


def _path(name):
    return os.path.join(home(), name)


def _write_private(name, data):
    path = _path(name)
    tmp = path + f".{uuid.uuid4().hex}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = None
    try:
        fd = os.open(tmp, flags, 0o600)
        with os.fdopen(fd, "w") as f:
            fd = None  # fdopen owns the descriptor from here.
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        info = os.lstat(tmp)
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o077:
            raise PermissionError("DEV auth storage did not preserve owner-only file permissions.")
        os.replace(tmp, path)
    finally:
        if fd is not None:
            os.close(fd)
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass


def _open_private(name):
    path = _path(name)
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise PermissionError(f"DEV auth state is not a regular file: {name}")
        if stat.S_IMODE(info.st_mode) & 0o077:
            os.fchmod(fd, 0o600)
            if stat.S_IMODE(os.fstat(fd).st_mode) & 0o077:
                raise PermissionError(f"DEV auth state is not owner-only: {name}")
        return os.fdopen(fd)
    except Exception:
        os.close(fd)
        raise


def _read_json(name, default):
    try:
        with _open_private(name) as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def secret():
    configured = os.environ.get("MUSICME_DEV_AUTH_JWT_SECRET", "").strip()
    if configured:
        if len(configured) < 32:
            raise SystemExit("MUSICME_DEV_AUTH_JWT_SECRET must be at least 32 characters.")
        return configured
    with _open_private("jwt_secret") as f:
        value = f.read().strip()
    if len(value) < 64:
        raise SystemExit("DEV secret is missing or too short; run: devauth.py init")
    return value


def _b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def issue(user_id, token_type, seconds, key=None, now=None):
    now = int(now if now is not None else time.time())
    head = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = _b64(json.dumps({"token_type": token_type, "user_id": user_id, "iat": now, "exp": now + seconds,
                            "jti": uuid.uuid4().hex, "iss": ISSUER, "aud": AUDIENCE}, separators=(",", ":")).encode())
    signature = hmac.new((key or secret()).encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{_b64(signature)}"


def verify(token, token_type, key=None, now=None):
    """Returns claims for a valid, unexpired, unrevoked token of the given type, else None."""
    try:
        head, body, signature = token.split(".")
        expected = hmac.new((key or secret()).encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
        if json.loads(_unb64(head)).get("alg") != "HS256" or not hmac.compare_digest(expected, _unb64(signature)):
            return None
        claims = json.loads(_unb64(body))
    except Exception:
        return None
    now = time.time() if now is None else now
    if claims.get("token_type") != token_type or claims.get("iss") != ISSUER or claims.get("aud") != AUDIENCE:
        return None
    if not isinstance(claims.get("exp"), int) or claims["exp"] <= now:
        return None
    revocation = _read_json("revocation.json", {"not_before": {}, "revoked_jti": []})
    not_before = max(revocation.get("not_before", {}).get("*", 0), revocation.get("not_before", {}).get(claims.get("user_id"), 0))
    if claims.get("iat", 0) <= not_before or claims.get("jti") in revocation.get("revoked_jti", []):
        return None
    return claims


def _hash_password(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), PBKDF2_ROUNDS).hex()


def create_user(email):
    email = email.strip().lower()
    users = _read_json("users.json", {})
    if email in users:
        raise SystemExit("That DEV user already exists.")
    password = secrets.token_urlsafe(12)
    salt = secrets.token_hex(16)
    users[email] = {"user_id": f"dev-{uuid.uuid4().hex[:16]}", "salt": salt, "hash": _hash_password(password, salt),
                    "created": int(time.time())}
    _write_private("users.json", json.dumps(users, indent=2))
    return users[email]["user_id"], password


def authenticate(email, password):
    user = _read_json("users.json", {}).get((email or "").strip().lower())
    if not user or not isinstance(password, str):
        _hash_password("x", "00" * 16)  # similar timing for unknown users
        return None
    return user["user_id"] if hmac.compare_digest(_hash_password(password, user["salt"]), user["hash"]) else None


def revoke(email=None, everyone=False):
    revocation = _read_json("revocation.json", {"not_before": {}, "revoked_jti": []})
    now = int(time.time())
    if everyone:
        revocation["not_before"]["*"] = now
    else:
        user = _read_json("users.json", {}).get(email.strip().lower())
        if not user:
            raise SystemExit("No such DEV user.")
        revocation["not_before"][user["user_id"]] = now
    _write_private("revocation.json", json.dumps(revocation, indent=2))


def init(rotate=False):
    configured = os.environ.get("MUSICME_DEV_AUTH_JWT_SECRET", "").strip()
    created = False
    if configured:
        secret()  # validate the persistent Render-generated value before startup
    elif not os.path.exists(_path("jwt_secret")) or rotate:
        _write_private("jwt_secret", secrets.token_hex(48))
        created = True
    if not os.path.exists(_path("revocation.json")):
        _write_private("revocation.json", json.dumps({"not_before": {}, "revoked_jti": []}, indent=2))
    return created


class _Failures:
    def __init__(self):
        self.lock, self.events = threading.Lock(), {}

    def blocked(self, key):
        with self.lock:
            recent = [t for t in self.events.get(key, []) if t > time.time() - 60]
            self.events[key] = recent
            return len(recent) >= MAX_FAILURES_PER_MINUTE

    def record(self, key):
        with self.lock:
            if key not in self.events and len(self.events) >= MAX_FAILURE_KEYS:
                self.events.pop(next(iter(self.events)))
            self.events.setdefault(key, []).append(time.time())


def make_handler(failures):
    class Handler(BaseHTTPRequestHandler):
        server_version = "beatiq-dev-auth"
        sys_version = ""

        def log_message(self, fmt, *args):  # never log bodies (passwords, tokens)
            sys.stderr.write(f"devauth {self.command} {self.path.split('?')[0]}\n")

        def _send(self, status, payload):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self):
            try:
                length = int(self.headers.get("Content-Length") or 0)
                return json.loads(self.rfile.read(min(length, 8192))) if 0 < length <= 8192 else {}
            except ValueError:
                return {}

        def do_GET(self):
            if self.path == "/healthz":
                return self._send(200, {"status": "ok", "service": "beatiq-dev-auth"})
            if self.path == "/internal/revocation":
                supplied = self.headers.get("Authorization", "")
                expected = "Bearer " + os.environ.get("MUSICME_DEV_AUTH_INTERNAL_TOKEN", "")
                if len(expected) < 39 or not hmac.compare_digest(supplied.encode(), expected.encode()):
                    return self._send(401, {"detail": "unauthorized"})
                try:
                    data = _read_json("revocation.json", None)
                except (OSError, ValueError):
                    data = None
                if not isinstance(data, dict):
                    return self._send(503, {"detail": "revocation unavailable"})
                return self._send(200, data)
            self._send(503, {"detail": "Not available on the BeatIQ DEV auth stub."})

        def do_POST(self):
            path = self.path.split("?")[0]
            if path == "/api/v1/auth/token/":
                body = self._body()
                key = str(body.get("email", "")).strip().lower()
                if failures.blocked(key):
                    return self._send(429, {"detail": "Too many attempts. Wait a minute and try again."})
                user_id = authenticate(body.get("email"), body.get("password"))
                if not user_id:
                    failures.record(key)
                    return self._send(401, {"detail": "No active account found with the given credentials"})
                return self._send(200, {"access": issue(user_id, "access", ACCESS_SECONDS),
                                        "refresh": issue(user_id, "refresh", REFRESH_SECONDS)})
            if path == "/api/v1/auth/token/refresh/":
                claims = verify(str(self._body().get("refresh", "")), "refresh")
                if not claims:
                    return self._send(401, {"detail": "Token is invalid or expired"})
                return self._send(200, {"access": issue(claims["user_id"], "access", ACCESS_SECONDS)})
            self._send(503, {"detail": "Not available on the BeatIQ DEV auth stub."})

        do_PUT = do_PATCH = do_DELETE = do_POST

    return Handler


def serve(host="127.0.0.1", port=8000):
    if host not in LOOPBACK and os.environ.get("MUSICME_DEV_AUTH_ALLOW_PUBLIC") != "true":
        raise SystemExit("The DEV auth stub binds to loopback unless MUSICME_DEV_AUTH_ALLOW_PUBLIC=true.")
    if host not in LOOPBACK and len(os.environ.get("MUSICME_DEV_AUTH_INTERNAL_TOKEN", "")) < 32:
        raise SystemExit("Public DEV auth requires a 32+ character internal revocation token.")
    secret()  # fail early without a secret
    server = ThreadingHTTPServer((host, port), make_handler(_Failures()))
    server.daemon_threads = True
    return server


def main(argv=None):
    parser = argparse.ArgumentParser(description="BeatIQ DEV auth stub (development only)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    user = sub.add_parser("create-user"); user.add_argument("--email", required=True)
    run = sub.add_parser("serve"); run.add_argument("--port", type=int, default=8000); run.add_argument("--host", default="127.0.0.1")
    rev = sub.add_parser("revoke"); group = rev.add_mutually_exclusive_group(required=True)
    group.add_argument("--email"); group.add_argument("--all", action="store_true")
    sub.add_parser("rotate-secret")
    sub.add_parser("service-env")
    args = parser.parse_args(argv)
    if args.command == "init":
        print("DEV secret created." if init() else "DEV secret already exists (use rotate-secret to replace it).")
    elif args.command == "create-user":
        user_id, password = create_user(args.email)
        print(f"DEV user {args.email.strip().lower()} ({user_id}) created.\nPassword (shown once): {password}")
    elif args.command == "serve":
        server = serve(args.host, args.port)
        print(f"BeatIQ DEV auth stub on http://{args.host}:{args.port}/api/v1/ (loopback only)", flush=True)
        server.serve_forever()
    elif args.command == "revoke":
        revoke(email=args.email, everyone=args.all)
        print("Revoked: tokens issued until now are rejected by the auth stub and the MusicMe services.")
    elif args.command == "rotate-secret":
        init(rotate=True)
        print("DEV secret rotated. Restart the MusicMe services; every existing token is now invalid.")
    elif args.command == "service-env":
        print(f"MUSICME_JWT_SECRET_FILE={_path('jwt_secret')}")
        print(f"MUSICME_JWT_ISSUER={ISSUER}\nMUSICME_JWT_AUDIENCE={AUDIENCE}")
        print(f"MUSICME_JWT_REVOCATION_FILE={_path('revocation.json')}")
        print(f"MUSICME_AUDIO_JWT_SECRET_FILE={_path('jwt_secret')}")
        print(f"MUSICME_AUDIO_JWT_ISSUER={ISSUER}\nMUSICME_AUDIO_JWT_AUDIENCE={AUDIENCE}")
        print(f"MUSICME_AUDIO_JWT_REVOCATION_FILE={_path('revocation.json')}")


if __name__ == "__main__":
    main()
