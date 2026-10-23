"""Verifies BeatIQ access tokens (adapted from musicme-songwriter/songwriter/auth.py; kept as an independent copy so
the two services share no runtime code or credentials) and returns the account id. Fails closed in every uncertain case.

* HS256 with a shared secret matches the current BeatIQ (SimpleJWT) setup.
* RS256 / ES256 against trusted public keys (JWKS file or HTTPS URL) is preferred: the service then
  holds no signing material at all. This service never creates or rotates signing keys.
* Exactly one family is accepted per deployment, so an attacker can't present an HS256 token signed
  with a public key (algorithm confusion). "none" is never accepted.
* The account id comes only from the verified token; client-supplied ids are never read.
"""
import base64
import hashlib
import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.request

from .errors import ServiceError

MIN_SECRET_LENGTH = 32
MIN_RSA_BITS = 2048


def _b64decode(segment):
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def _b64int(segment):
    return int.from_bytes(_b64decode(segment), "big")


def account_hash(account):
    """Stable, non-reversible label for logs."""
    return hashlib.sha256(str(account).encode()).hexdigest()[:12]


def _unauthorized():
    return ServiceError(401, "UNAUTHORIZED", "Sign in again to create songs with MusicMe.")


def _unavailable():
    return ServiceError(503, "UNAVAILABLE", "Audio service sign-in verification isn't configured on this server.")


class JwksKeySet:
    """Trusted public keys from a local JWKS file or an HTTPS URL (cached). Requires `cryptography`."""

    def __init__(self, file=None, url=None, cache_seconds=600, opener=urllib.request.urlopen, clock=time.time):
        try:
            from cryptography.hazmat.primitives.asymmetric import ec, rsa  # noqa: F401
        except ImportError:
            raise SystemExit("Asymmetric JWT verification requires the 'cryptography' package (see requirements.txt).")
        if url and not url.startswith("https://"):
            raise ValueError("JWKS URL must use HTTPS.")
        self.file, self.url, self.cache_seconds = file, url, cache_seconds
        self._opener, self._clock = opener, clock
        self._keys, self._loaded_at = None, 0.0
        self._lock = threading.Lock()

    def key(self, kid, alg):
        keys = self._load()
        candidates = [k for k in keys if k["alg_family"] == alg and (kid is None or k["kid"] == kid)]
        if kid is None and len(candidates) != 1:
            raise _unauthorized()  # ambiguous: tokens must name their key when several are trusted
        if not candidates:
            raise _unauthorized()
        return candidates[0]["key"]

    def _load(self):
        with self._lock:
            fresh = self._keys is not None and self._clock() - self._loaded_at < self.cache_seconds
            if fresh:
                return self._keys
            try:
                if self.file:
                    with open(self.file) as f:
                        document = json.load(f)
                else:
                    with self._opener(self.url, timeout=10) as response:
                        document = json.loads(response.read())
                self._keys = [k for k in (self._parse(jwk) for jwk in document.get("keys", [])) if k]
                self._loaded_at = self._clock()
            except Exception:
                if self._keys is None:
                    raise _unavailable()  # never fall back to accepting tokens without keys
            if not self._keys:
                raise _unavailable()
            return self._keys

    @staticmethod
    def _parse(jwk):
        from cryptography.hazmat.primitives.asymmetric import ec, rsa
        if jwk.get("use", "sig") != "sig":
            return None
        try:
            if jwk.get("kty") == "RSA":
                numbers = rsa.RSAPublicNumbers(_b64int(jwk["e"]), _b64int(jwk["n"]))
                if numbers.n.bit_length() < MIN_RSA_BITS:
                    return None
                return {"kid": jwk.get("kid"), "alg_family": "RS256", "key": numbers.public_key()}
            if jwk.get("kty") == "EC" and jwk.get("crv") == "P-256":
                numbers = ec.EllipticCurvePublicNumbers(_b64int(jwk["x"]), _b64int(jwk["y"]), ec.SECP256R1())
                return {"kid": jwk.get("kid"), "alg_family": "ES256", "key": numbers.public_key()}
        except (KeyError, ValueError):
            return None
        return None


class TokenVerifier:
    def __init__(self, settings, keyset=None, clock=time.time, revocation_opener=None):
        self.s = settings
        self.algorithms = tuple(settings.jwt_algorithms)
        self.clock = clock
        self._revocation_cache = None
        self._revocation_opener = revocation_opener or urllib.request.build_opener(_NoRedirect())
        if self.algorithms == ("HS256",):
            self.keyset = None
        else:
            self.keyset = keyset or JwksKeySet(settings.jwks_file, settings.jwks_url, settings.jwks_cache_seconds)

    def account(self, authorization_header):
        if self.algorithms == ("HS256",) and (not isinstance(self.s.jwt_secret, str) or len(self.s.jwt_secret) < MIN_SECRET_LENGTH):
            raise _unavailable()
        if not authorization_header or not authorization_header.startswith("Bearer "):
            raise _unauthorized()
        token = authorization_header[len("Bearer "):].strip()
        try:
            head_b64, payload_b64, signature_b64 = token.split(".")
            header = json.loads(_b64decode(head_b64))
            claims = json.loads(_b64decode(payload_b64))
            signature = _b64decode(signature_b64)
        except Exception:
            raise _unauthorized()
        alg = header.get("alg")
        if alg not in self.algorithms or not isinstance(claims, dict):
            raise _unauthorized()
        signed = f"{head_b64}.{payload_b64}".encode()
        if not self._signature_valid(alg, header.get("kid"), signed, signature):
            raise _unauthorized()
        return self._account_from_claims(claims)

    def _signature_valid(self, alg, kid, signed, signature):
        if alg == "HS256":
            expected = hmac.new(self.s.jwt_secret.encode(), signed, hashlib.sha256).digest()
            return hmac.compare_digest(expected, signature)
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec, padding
        from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
        key = self.keyset.key(kid, alg)
        try:
            if alg == "RS256":
                key.verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())
            else:  # ES256: JOSE signatures are raw r||s (64 bytes)
                if len(signature) != 64:
                    return False
                der = encode_dss_signature(int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big"))
                key.verify(der, signed, ec.ECDSA(hashes.SHA256()))
            return True
        except InvalidSignature:
            return False

    def _account_from_claims(self, claims):
        now = self.clock()
        leeway = self.s.jwt_leeway_seconds
        exp, nbf, iat = claims.get("exp"), claims.get("nbf"), claims.get("iat")
        if not isinstance(exp, (int, float)) or exp + leeway <= now:
            raise _unauthorized()
        if nbf is not None and (not isinstance(nbf, (int, float)) or nbf - leeway > now):
            raise _unauthorized()
        if iat is not None and (not isinstance(iat, (int, float)) or iat - leeway > now):
            raise _unauthorized()
        if claims.get("token_type") != "access":
            raise _unauthorized()
        if self.s.jwt_issuer is not None and claims.get("iss") != self.s.jwt_issuer:
            raise _unauthorized()
        if self.s.jwt_audience is not None:
            audience = claims.get("aud")
            audiences = audience if isinstance(audience, list) else [audience]
            if self.s.jwt_audience not in audiences:
                raise _unauthorized()
        account = claims.get(self.s.user_id_claim)
        if account is None or isinstance(account, (dict, list, bool)) or str(account).strip() == "":
            raise _unauthorized()
        if self.s.jwt_revocation_file or self.s.jwt_revocation_url:
            self._check_revocation(claims, str(account))
        return str(account)

    def _check_revocation(self, claims, account):
        """Optional revocation list (used with the DEV auth stub): tokens issued at or before a not_before time for
        the account (or "*") and listed jti values are rejected. A configured but unreadable file fails closed."""
        try:
            if self.s.jwt_revocation_file:
                path = self.s.jwt_revocation_file
                mtime = os.stat(path).st_mtime_ns
                if self._revocation_cache is None or self._revocation_cache[:2] != ("file", mtime):
                    with open(path) as f:
                        data = json.load(f)
                    self._revocation_cache = ("file", mtime, data)
            else:
                loaded = self._revocation_cache[1] if self._revocation_cache and self._revocation_cache[0] == "url" else 0
                if self.clock() - loaded >= self.s.jwt_revocation_cache_seconds:
                    request = urllib.request.Request(self.s.jwt_revocation_url,
                                                     headers={"Authorization": "Bearer " + self.s.jwt_revocation_token,
                                                              "Accept": "application/json"})
                    with self._revocation_opener.open(request, timeout=5) as response:
                        if (response.headers.get("Content-Type") or "").split(";", 1)[0] != "application/json":
                            raise ValueError("not json")
                        raw = response.read(64 * 1024 + 1)
                    if len(raw) > 64 * 1024:
                        raise ValueError("too large")
                    data = json.loads(raw)
                    self._revocation_cache = ("url", self.clock(), data)
            data = self._revocation_cache[2]
            not_before = data.get("not_before", {})
            cutoff = max(float(not_before.get("*", 0)), float(not_before.get(account, 0)))
            revoked = set(data.get("revoked_jti", []))
        except (OSError, ValueError, TypeError, AttributeError):
            raise _unavailable()
        iat = claims.get("iat")
        if not isinstance(iat, (int, float)) or iat <= cutoff or claims.get("jti") in revoked:
            raise _unauthorized()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None
