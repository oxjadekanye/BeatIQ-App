"""Configuration from MUSICME_AUDIO_* environment variables. Secrets are read here and never logged or returned.

Every variable is prefixed MUSICME_AUDIO_ so this service never picks up the songwriting service's database,
token secret or model settings by accident.
"""
import os
import re
from dataclasses import dataclass, field
from typing import FrozenSet, Optional, Tuple
from urllib.parse import urlsplit

# These are accepted input labels, not a provider quality guarantee. The client and capabilities response explicitly
# warn that Nigerian Pidgin and Yoruba pronunciation still require native-speaker listening review.
DEFAULT_LANGUAGES = frozenset({"english", "nigerian_pidgin", "yoruba"})

ASYMMETRIC_ALGORITHMS = ("RS256", "ES256")
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1")
ENGINES = ("none", "mock", "ace_step", "remote_gpu", "fal")
STORAGE_BACKENDS = ("local", "s3")
GPU_LEDGER_BACKENDS = ("file", "database")
GENERATION_MODES = ("disabled", "internal_test", "public")


@dataclass(frozen=True)
class Settings:
    # --- environment -------------------------------------------------------------------------
    environment: str = "development"  # "development" | "production"
    database_url: str = "sqlite:///./var/musicme-audio.db"
    generation_mode: str = "disabled"
    internal_test_user_ids: FrozenSet[str] = frozenset()
    public_eligible_user_ids: FrozenSet[str] = frozenset()
    commercial_rights_approved: bool = False
    security_accepted: bool = False
    public_activation_approved: bool = False

    # --- authentication (same rules as the songwriting service, separate credentials) ---------
    jwt_algorithms: Tuple[str, ...] = ("HS256",)
    jwt_secret: str = field(default="", repr=False)
    jwks_file: Optional[str] = None
    jwks_url: Optional[str] = None
    jwks_cache_seconds: int = 600
    jwt_issuer: Optional[str] = None
    jwt_audience: Optional[str] = None
    jwt_leeway_seconds: int = 30
    user_id_claim: str = "user_id"
    allow_shared_secret_in_production: bool = False
    jwt_revocation_file: Optional[str] = None  # optional revocation list (DEV auth stub); unreadable = fail closed
    jwt_revocation_url: Optional[str] = None  # isolated hosted DEV auth; HTTPS and bearer-authenticated
    jwt_revocation_token: str = field(default="", repr=False)
    jwt_revocation_cache_seconds: int = 5

    # --- engine --------------------------------------------------------------------------------
    engine: str = "none"  # "none" | "mock" (development tests only) | "ace_step" (real model via a separate runner)
    model_revision: str = ""
    model_sha256: str = ""
    render_timeout_seconds: int = 600
    # ACE-Step runner (Phase 2.3F). The pinned commit, revision and hashes live in audioservice/acestep/manifest.json,
    # not in configuration, so they can't be loosened by an environment variable.
    acestep_python: str = ""  # ACE-Step venv interpreter (Python 3.12)
    acestep_home: str = ""  # pinned ACE-Step checkout; weights in <home>/checkpoints
    acestep_runner: str = ""  # empty = audioservice/acestep/runner.py (tests point this at a fake runner)
    acestep_device: str = "cuda"
    acestep_startup_timeout_seconds: int = 600
    acestep_retry_nan: bool = False  # off until safe NaN retry behaviour is verified
    # Remote GPU worker (development only; musicme.gpu-job.v1 on Modal, RunPod, Vast.ai or any GPU host)
    worker_url: str = ""
    worker_credentials_file: str = ""  # private file (chmod 600) with the worker token; never env values or logs
    worker_credentials_json: str = field(default="", repr=False)  # hosted secret store alternative (Render)
    worker_allowed_hosts: Tuple[str, ...] = (".modal.run", ".proxy.runpod.net")  # HTTPS; loopback = SSH tunnel
    worker_startup_timeout_seconds: int = 900  # covers a cold start (image, weights, model load)
    # DEV GPU spend guard (estimates; the provider's spend limit is the hard cap)
    gpu_price_per_hour_usd: float = 0.82  # Modal T4 + 2 cores + 16 GiB, Oct 2026 list prices
    gpu_daily_budget_usd: float = 2.0
    gpu_max_renders_per_day: int = 12
    gpu_reserve_seconds: int = 420  # worst case reserved per render before it runs: cold start + render
    gpu_idle_tail_seconds: int = 120  # the provider keeps the worker warm this long after use (Modal scaledown window)
    gpu_ledger_file: str = ""  # default: gpu-ledger.json next to the storage root
    gpu_ledger_backend: str = "file"  # hosted services use PostgreSQL so budgets survive instance replacement
    allow_public_dev_service: bool = False  # explicit opt-in for an isolated HTTPS Render DEV service
    enable_production_generation: bool = False  # two-key gate; production Blueprint and Android release leave this off
    languages: FrozenSet[str] = DEFAULT_LANGUAGES

    # --- fal.ai hosted inference ---------------------------------------------------------------
    # Endpoint ids and the queue origin are intentionally fixed to the reviewed official API surface.
    fal_key: str = field(default="", repr=False)
    fal_queue_base_url: str = "https://queue.fal.run"
    fal_custom_lyrics_endpoint: str = "fal-ai/ace-step"
    fal_prompt_endpoint: str = "fal-ai/ace-step/prompt-to-audio"
    fal_price_per_second_usd: float = 0.0002
    fal_daily_budget_usd: float = 2.0
    fal_download_allowed_hosts: Tuple[str, ...] = (".fal.media", "storage.googleapis.com")

    # --- storage -------------------------------------------------------------------------------
    storage_backend: str = "local"
    storage_root: str = "./var/audio-objects"  # object-store mode: private ephemeral scratch, never durable content
    object_endpoint: str = ""
    object_bucket: str = ""
    object_region: str = "auto"
    object_access_key_id: str = field(default="", repr=False)
    object_secret_access_key: str = field(default="", repr=False)
    object_session_token: str = field(default="", repr=False)
    object_prefix: str = ""
    object_force_path_style: bool = False
    # Signs download links. Its own credential: never the JWT secret, never shared with the songwriting service.
    signing_secret: str = field(default="", repr=False)
    public_base_url: str = "http://127.0.0.1:8090"
    download_link_seconds: int = 600
    max_audio_bytes: int = 64 * 1024 * 1024
    account_storage_quota_bytes: int = 512 * 1024 * 1024

    # --- HTTP ----------------------------------------------------------------------------------
    host: str = "127.0.0.1"
    port: int = 8090
    max_body_bytes: int = 64 * 1024

    # --- songs ---------------------------------------------------------------------------------
    min_duration_seconds: int = 30
    max_duration_seconds: int = 240
    duration_tolerance: float = 0.25  # delivered length may differ from the target by this fraction
    max_takes: int = 2

    # --- jobs, quotas and workers -------------------------------------------------------------
    workers: int = 1  # one render per GPU; more workers only make sense with more GPUs
    run_workers: bool = True  # false on horizontally scaled API instances when a dedicated coordinator is deployed
    max_queued_jobs: int = 20
    max_jobs_per_window: int = 3  # songs per account per window; failed and cancelled jobs are refunded
    rate_window_seconds: int = 24 * 3600
    max_active_jobs_per_account: int = 1
    max_attempts: int = 2  # renders are expensive: one retry at most
    backoff_base_seconds: float = 5.0
    backoff_max_seconds: float = 120.0
    lease_seconds: int = 0  # 0 = derived from the render timeout; heartbeats extend it while rendering
    queue_ttl_seconds: int = 1800
    job_ttl_seconds: int = 30 * 24 * 3600
    track_ttl_seconds: int = 30 * 24 * 3600
    staging_ttl_seconds: int = 24 * 3600
    worker_poll_seconds: float = 0.5
    maintenance_interval_seconds: float = 30.0

    def __post_init__(self):
        if self.environment not in ("development", "production"):
            raise ValueError("MUSICME_AUDIO_ENVIRONMENT must be development or production.")
        if self.generation_mode not in GENERATION_MODES:
            raise ValueError("MUSICME_AUDIO_GENERATION_MODE must be disabled, internal_test or public.")
        if self.engine not in ENGINES:
            raise ValueError("MUSICME_AUDIO_ENGINE must be none, mock, ace_step, remote_gpu or fal.")
        if self.storage_backend not in STORAGE_BACKENDS:
            raise ValueError("MUSICME_AUDIO_STORAGE_BACKEND must be local or s3.")
        if self.gpu_ledger_backend not in GPU_LEDGER_BACKENDS:
            raise ValueError("MUSICME_AUDIO_GPU_LEDGER_BACKEND must be file or database.")
        algorithms = tuple(self.jwt_algorithms)
        if not algorithms or len(set(algorithms)) != len(algorithms):
            raise ValueError("Configure the accepted JWT algorithm(s).")
        if algorithms == ("HS256",):
            if not isinstance(self.jwt_secret, str) or len(self.jwt_secret) < 32:
                raise ValueError("A trusted JWT verification secret (32+ characters) is required.")
        elif all(a in ASYMMETRIC_ALGORITHMS for a in algorithms):
            if bool(self.jwks_file) == bool(self.jwks_url):
                raise ValueError("Asymmetric JWT verification needs exactly one of MUSICME_AUDIO_JWKS_FILE or MUSICME_AUDIO_JWKS_URL.")
            if self.jwks_url and not self.jwks_url.startswith("https://"):
                raise ValueError("MUSICME_AUDIO_JWKS_URL must use HTTPS.")
            if self.jwt_secret:
                raise ValueError("Don't configure a shared secret alongside asymmetric verification.")
        else:
            raise ValueError("JWT algorithms must be HS256 alone, or RS256/ES256.")
        if not isinstance(self.signing_secret, str) or len(self.signing_secret) < 32:
            raise ValueError("A download-link signing secret (32+ characters) is required.")
        if self.signing_secret == self.jwt_secret:
            raise ValueError("The download-link signing secret must differ from the JWT secret.")
        if self.jwt_revocation_file and self.jwt_revocation_url:
            raise ValueError("Configure only one JWT revocation source.")
        if self.jwt_revocation_url:
            if not self.jwt_revocation_url.startswith("https://") or len(self.jwt_revocation_token) < 32:
                raise ValueError("Hosted JWT revocation requires an HTTPS URL and a 32+ character service token.")
            if not 1 <= self.jwt_revocation_cache_seconds <= 60:
                raise ValueError("JWT revocation cache must be between 1 and 60 seconds.")
        if not 60 <= self.download_link_seconds <= 900:
            raise ValueError("Download links must expire within 1 to 15 minutes.")
        if not self.public_base_url.startswith(("https://", "http://127.0.0.1", "http://localhost", "http://[::1]")):
            raise ValueError("MUSICME_AUDIO_PUBLIC_BASE_URL must use HTTPS (plain HTTP is allowed on loopback only).")
        if not 5 <= self.min_duration_seconds <= self.max_duration_seconds <= 600:
            raise ValueError("Song duration limits must satisfy 5 <= min <= max <= 600 seconds.")
        if not 1 <= self.max_takes <= 4:
            raise ValueError("Takes per job must be between 1 and 4.")
        if self.engine == "ace_step":
            if not self.acestep_python or not self.acestep_home:
                raise ValueError("The ace_step engine needs MUSICME_AUDIO_ACESTEP_PYTHON and MUSICME_AUDIO_ACESTEP_HOME.")
            if self.workers != 1:
                raise ValueError("The ace_step engine runs one render per GPU: set MUSICME_AUDIO_WORKERS=1.")
            if self.acestep_device != "cuda":
                raise ValueError("The ace_step engine supports only the cuda device.")
            if not 10 <= self.acestep_startup_timeout_seconds <= 1800:
                raise ValueError("ACE-Step startup timeout must be between 10 and 1800 seconds.")
            if self.render_timeout_seconds < 10:
                raise ValueError("ACE-Step render timeout must be at least 10 seconds.")
        if self.engine == "remote_gpu":
            if self.environment == "production" and not self.enable_production_generation:
                raise ValueError("Production generation is disabled; explicit server approval is required.")
            if self.environment == "production" and self.gpu_ledger_backend != "database":
                raise ValueError("Production generation requires a database-backed GPU spend ledger.")
            if self.host not in LOOPBACK_HOSTS:
                if self.environment == "development" and not self.allow_public_dev_service:
                    raise ValueError("The remote_gpu engine must bind to loopback unless public DEV hosting is explicitly enabled.")
                if (not self.public_base_url.startswith("https://") or not self.jwt_issuer or not self.jwt_audience
                        or not self.jwt_revocation_url):
                    raise ValueError("Public DEV hosting requires HTTPS, issuer/audience checks and hosted revocation.")
                if self.gpu_ledger_backend != "database":
                    raise ValueError("Public DEV hosting requires a database-backed GPU spend ledger.")
            if not self.worker_url:
                raise ValueError("The remote_gpu engine needs MUSICME_AUDIO_WORKER_URL.")
            if self.workers != 1:
                raise ValueError("The remote_gpu engine uses one worker: set MUSICME_AUDIO_WORKERS=1.")
            if bool(self.worker_credentials_file) == bool(self.worker_credentials_json):
                raise ValueError("Configure exactly one GPU worker credentials source.")
            if not 10 <= self.worker_startup_timeout_seconds <= 1800:
                raise ValueError("GPU worker startup timeout must be between 10 and 1800 seconds.")
            if not 0 < self.gpu_daily_budget_usd <= 20:
                raise ValueError("The DEV GPU daily budget must be more than 0 and at most 20 USD.")
            if not 1 <= self.gpu_max_renders_per_day <= 100:
                raise ValueError("The DEV GPU render limit must be between 1 and 100 per day.")
            if not 0 < self.gpu_price_per_hour_usd <= 10 or self.gpu_reserve_seconds < 30 or self.gpu_idle_tail_seconds < 0:
                raise ValueError("The DEV GPU cost settings are out of range.")
        if self.engine == "fal":
            if len(self.fal_key) < 20:
                raise ValueError("The fal engine requires a server-side FAL_KEY.")
            if self.fal_queue_base_url != "https://queue.fal.run":
                raise ValueError("The fal queue origin must be the reviewed official https://queue.fal.run endpoint.")
            if self.fal_custom_lyrics_endpoint != "fal-ai/ace-step" or self.fal_prompt_endpoint != "fal-ai/ace-step/prompt-to-audio":
                raise ValueError("The configured fal ACE-Step endpoint ids are not approved.")
            if not 0 < self.fal_price_per_second_usd <= 0.01 or not 0 < self.fal_daily_budget_usd <= 20:
                raise ValueError("The fal cost settings are out of range.")
            if self.min_duration_seconds < 5 or self.max_duration_seconds > 240:
                raise ValueError("fal ACE-Step supports durations from 5 to 240 seconds.")
            if self.max_takes != 1 or self.max_attempts != 1:
                raise ValueError("The fal engine requires one take and one application attempt to prevent duplicate spend.")
            if self.workers != 1:
                raise ValueError("The fal engine requires one application worker for controlled spend.")
        if self.storage_backend == "s3":
            endpoint = urlsplit(self.object_endpoint)
            if endpoint.scheme != "https" or not endpoint.hostname or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment:
                raise ValueError("S3-compatible object storage requires a credential-free HTTPS endpoint.")
            if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.object_bucket or ""):
                raise ValueError("S3-compatible object storage requires a valid private bucket name.")
            if len(self.object_access_key_id) < 8 or len(self.object_secret_access_key) < 16:
                raise ValueError("S3-compatible object storage requires explicit server-side credentials.")
            if (self.object_prefix and (len(self.object_prefix) > 64 or self.object_prefix.startswith("/")
                    or self.object_prefix.endswith("/") or ".." in self.object_prefix
                    or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9/_-]*", self.object_prefix))):
                raise ValueError("The object-storage prefix is invalid.")
            if self.environment == "production" and self.engine == "fal":
                if not endpoint.hostname.endswith(".eu.r2.cloudflarestorage.com"):
                    raise ValueError("Production MusicMe fal storage must use a Cloudflare R2 EU-jurisdiction endpoint.")
                if not self.object_force_path_style:
                    raise ValueError("Production MusicMe R2 storage requires path-style addressing.")
        if self.engine == "mock" and self.host not in LOOPBACK_HOSTS:
            raise ValueError("The mock engine is for local development only and must bind to a loopback address.")
        if self.environment == "production":
            if self.engine == "mock":
                raise ValueError("The mock engine is not available in production.")
            # Kill switch: no engine may generate in production (local GPU or remote) without explicit approval.
            fal_boots_disabled = self.engine == "fal" and self.generation_mode == "disabled"
            if self.engine != "none" and not fal_boots_disabled and not self.enable_production_generation:
                raise ValueError("Production generation is disabled; explicit server approval is required.")
            if not self.database_url.startswith(("postgresql://", "postgres://")):
                raise ValueError("Production requires PostgreSQL storage.")
            if not self.jwt_issuer or not self.jwt_audience:
                raise ValueError("Production requires MUSICME_AUDIO_JWT_ISSUER and MUSICME_AUDIO_JWT_AUDIENCE.")
            if algorithms == ("HS256",) and not self.allow_shared_secret_in_production:
                raise ValueError("Production expects asymmetric (RS256/ES256) tokens; shared-secret HS256 needs an explicit opt-in.")
            if not self.public_base_url.startswith("https://"):
                raise ValueError("Production download links must use HTTPS.")
            if self.storage_backend == "local":
                raise ValueError("Production requires a private object-store backend; local storage is development-only.")
            if self.generation_mode == "internal_test" and not self.internal_test_user_ids:
                raise ValueError("Internal testing requires stable authenticated user ids in MUSICME_AUDIO_INTERNAL_TEST_USER_IDS.")
            if self.generation_mode == "public":
                if not (self.commercial_rights_approved and self.security_accepted and self.public_activation_approved):
                    raise ValueError("Public generation requires commercial-rights, security and activation approvals.")
                if not self.public_eligible_user_ids:
                    raise ValueError("Public generation requires an explicit eligible-user allowlist.")
        if self.lease_seconds and self.lease_seconds < 60:
            raise ValueError("A job lease shorter than 60 seconds can expire mid-render.")
        if self.max_attempts < 1 or self.workers < 1:
            raise ValueError("Attempts and workers must be at least 1.")

    @property
    def effective_lease_seconds(self):
        # Heartbeats renew the lease during long renders; this covers the gap between heartbeats plus a margin.
        return self.lease_seconds or min(self.render_timeout_seconds, 300) + 60

    def generation_allowed(self, account):
        """Server-enforced account gate. Possessing a provider key never enables generation."""
        if self.generation_mode == "disabled":
            return False
        if self.generation_mode == "internal_test":
            return str(account) in self.internal_test_user_ids
        return (self.commercial_rights_approved and self.security_accepted and self.public_activation_approved
                and str(account) in self.public_eligible_user_ids)


def _secret(env, name):
    """Reads NAME or the file named by NAME_FILE (for container secrets)."""
    path = env.get(name + "_FILE")
    if path:
        with open(path) as f:
            return f.read().strip()
    return env.get(name)


def load_settings(env=os.environ) -> Settings:
    database_url = env.get("MUSICME_AUDIO_DATABASE_URL", Settings.database_url)
    songwriter_db = env.get("MUSICME_DATABASE_URL")
    if songwriter_db and songwriter_db == database_url:
        raise SystemExit("Invalid audio service configuration: it must not share the songwriting service's database.")
    languages = env.get("MUSICME_AUDIO_LANGUAGES")
    algorithms = tuple(a.strip() for a in env.get("MUSICME_AUDIO_JWT_ALGORITHMS", "HS256").split(",") if a.strip())
    try:
        return Settings(
            environment=env.get("MUSICME_AUDIO_ENVIRONMENT", "development"),
            database_url=database_url,
            generation_mode=env.get("MUSICME_AUDIO_GENERATION_MODE", "disabled"),
            internal_test_user_ids=frozenset(v.strip() for v in env.get("MUSICME_AUDIO_INTERNAL_TEST_USER_IDS", "").split(",") if v.strip()),
            public_eligible_user_ids=frozenset(v.strip() for v in env.get("MUSICME_AUDIO_PUBLIC_ELIGIBLE_USER_IDS", "").split(",") if v.strip()),
            commercial_rights_approved=env.get("MUSICME_AUDIO_COMMERCIAL_RIGHTS_APPROVED") == "true",
            security_accepted=env.get("MUSICME_AUDIO_SECURITY_ACCEPTED") == "true",
            public_activation_approved=env.get("MUSICME_AUDIO_PUBLIC_ACTIVATION_APPROVED") == "true",
            jwt_algorithms=algorithms,
            jwt_secret=_secret(env, "MUSICME_AUDIO_JWT_SECRET") or "",
            jwks_file=env.get("MUSICME_AUDIO_JWKS_FILE"),
            jwks_url=env.get("MUSICME_AUDIO_JWKS_URL"),
            jwt_issuer=env.get("MUSICME_AUDIO_JWT_ISSUER"),
            jwt_audience=env.get("MUSICME_AUDIO_JWT_AUDIENCE"),
            user_id_claim=env.get("MUSICME_AUDIO_USER_ID_CLAIM", "user_id"),
            jwt_revocation_file=env.get("MUSICME_AUDIO_JWT_REVOCATION_FILE"),
            jwt_revocation_url=env.get("MUSICME_AUDIO_JWT_REVOCATION_URL"),
            jwt_revocation_token=_secret(env, "MUSICME_AUDIO_JWT_REVOCATION_TOKEN") or "",
            jwt_revocation_cache_seconds=int(env.get("MUSICME_AUDIO_JWT_REVOCATION_CACHE_SECONDS", "5")),
            allow_shared_secret_in_production=env.get("MUSICME_AUDIO_ALLOW_SHARED_SECRET_IN_PRODUCTION") == "true",
            engine=env.get("MUSICME_AUDIO_ENGINE", "none"),
            model_revision=env.get("MUSICME_AUDIO_MODEL_REVISION", ""),
            model_sha256=env.get("MUSICME_AUDIO_MODEL_SHA256", ""),
            render_timeout_seconds=int(env.get("MUSICME_AUDIO_RENDER_TIMEOUT_SECONDS", "600")),
            acestep_python=env.get("MUSICME_AUDIO_ACESTEP_PYTHON", ""),
            acestep_home=env.get("MUSICME_AUDIO_ACESTEP_HOME", ""),
            acestep_runner=env.get("MUSICME_AUDIO_ACESTEP_RUNNER", ""),
            acestep_device=env.get("MUSICME_AUDIO_ACESTEP_DEVICE", "cuda"),
            acestep_startup_timeout_seconds=int(env.get("MUSICME_AUDIO_ACESTEP_STARTUP_TIMEOUT_SECONDS", "600")),
            acestep_retry_nan=env.get("MUSICME_AUDIO_ACESTEP_RETRY_NAN") == "true",
            worker_url=env.get("MUSICME_AUDIO_WORKER_URL", ""),
            worker_credentials_file=env.get("MUSICME_AUDIO_WORKER_CREDENTIALS_FILE", ""),
            worker_credentials_json=env.get("MUSICME_AUDIO_WORKER_CREDENTIALS_JSON", ""),
            worker_allowed_hosts=tuple(h.strip() for h in env.get("MUSICME_AUDIO_WORKER_ALLOWED_HOSTS", ".modal.run,.proxy.runpod.net").split(",") if h.strip()),
            worker_startup_timeout_seconds=int(env.get("MUSICME_AUDIO_WORKER_STARTUP_TIMEOUT_SECONDS", "900")),
            gpu_price_per_hour_usd=float(env.get("MUSICME_AUDIO_GPU_PRICE_PER_HOUR_USD", "0.82")),
            gpu_daily_budget_usd=float(env.get("MUSICME_AUDIO_GPU_DAILY_BUDGET_USD", "2.0")),
            gpu_max_renders_per_day=int(env.get("MUSICME_AUDIO_GPU_MAX_RENDERS_PER_DAY", "12")),
            gpu_reserve_seconds=int(env.get("MUSICME_AUDIO_GPU_RESERVE_SECONDS", "420")),
            gpu_idle_tail_seconds=int(env.get("MUSICME_AUDIO_GPU_IDLE_TAIL_SECONDS", "120")),
            gpu_ledger_file=env.get("MUSICME_AUDIO_GPU_LEDGER_FILE", ""),
            gpu_ledger_backend=env.get("MUSICME_AUDIO_GPU_LEDGER_BACKEND", "file"),
            allow_public_dev_service=env.get("MUSICME_AUDIO_ALLOW_PUBLIC_DEV_SERVICE") == "true",
            enable_production_generation=env.get("MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION") == "true",
            languages=frozenset(l.strip() for l in languages.split(",") if l.strip()) if languages else DEFAULT_LANGUAGES,
            fal_key=_secret(env, "FAL_KEY") or "",
            fal_queue_base_url=env.get("MUSICME_AUDIO_FAL_QUEUE_BASE_URL", "https://queue.fal.run"),
            fal_custom_lyrics_endpoint=env.get("MUSICME_AUDIO_FAL_CUSTOM_LYRICS_ENDPOINT", "fal-ai/ace-step"),
            fal_prompt_endpoint=env.get("MUSICME_AUDIO_FAL_PROMPT_ENDPOINT", "fal-ai/ace-step/prompt-to-audio"),
            fal_price_per_second_usd=float(env.get("MUSICME_AUDIO_FAL_PRICE_PER_SECOND_USD", "0.0002")),
            fal_daily_budget_usd=float(env.get("MUSICME_AUDIO_FAL_DAILY_BUDGET_USD", "2.0")),
            storage_backend=env.get("MUSICME_AUDIO_STORAGE_BACKEND", "local"),
            storage_root=env.get("MUSICME_AUDIO_STORAGE_ROOT", Settings.storage_root),
            object_endpoint=env.get("MUSICME_AUDIO_OBJECT_ENDPOINT", ""),
            object_bucket=env.get("MUSICME_AUDIO_OBJECT_BUCKET", ""),
            object_region=env.get("MUSICME_AUDIO_OBJECT_REGION", "auto"),
            object_access_key_id=_secret(env, "MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID") or "",
            object_secret_access_key=_secret(env, "MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY") or "",
            object_session_token=_secret(env, "MUSICME_AUDIO_OBJECT_SESSION_TOKEN") or "",
            object_prefix=env.get("MUSICME_AUDIO_OBJECT_PREFIX", ""),
            object_force_path_style=env.get("MUSICME_AUDIO_OBJECT_FORCE_PATH_STYLE") == "true",
            signing_secret=_secret(env, "MUSICME_AUDIO_SIGNING_SECRET") or "",
            public_base_url=env.get("MUSICME_AUDIO_PUBLIC_BASE_URL", Settings.public_base_url),
            download_link_seconds=int(env.get("MUSICME_AUDIO_DOWNLOAD_LINK_SECONDS", "600")),
            max_audio_bytes=int(env.get("MUSICME_AUDIO_MAX_AUDIO_BYTES", str(64 * 1024 * 1024))),
            account_storage_quota_bytes=int(env.get("MUSICME_AUDIO_ACCOUNT_STORAGE_QUOTA_BYTES", str(512 * 1024 * 1024))),
            host=env.get("MUSICME_AUDIO_HOST", "127.0.0.1"),
            port=int(env.get("MUSICME_AUDIO_PORT", "8090")),
            workers=int(env.get("MUSICME_AUDIO_WORKERS", "1")),
            run_workers=env.get("MUSICME_AUDIO_RUN_WORKERS", "true") == "true",
            min_duration_seconds=int(env.get("MUSICME_AUDIO_MIN_DURATION_SECONDS", "30")),
            max_duration_seconds=int(env.get("MUSICME_AUDIO_MAX_DURATION_SECONDS", "240")),
            max_takes=int(env.get("MUSICME_AUDIO_MAX_TAKES", "1")),
            max_jobs_per_window=int(env.get("MUSICME_AUDIO_MAX_JOBS_PER_WINDOW", "3")),
            rate_window_seconds=int(env.get("MUSICME_AUDIO_RATE_WINDOW_SECONDS", str(24 * 3600))),
            max_active_jobs_per_account=int(env.get("MUSICME_AUDIO_MAX_ACTIVE_JOBS_PER_ACCOUNT", "1")),
            max_attempts=int(env.get("MUSICME_AUDIO_MAX_ATTEMPTS", "1")),
            max_queued_jobs=int(env.get("MUSICME_AUDIO_MAX_QUEUED_JOBS", "20")),
            queue_ttl_seconds=int(env.get("MUSICME_AUDIO_QUEUE_TTL_SECONDS", "1800")),
            job_ttl_seconds=int(env.get("MUSICME_AUDIO_JOB_TTL_SECONDS", str(30 * 24 * 3600))),
            track_ttl_seconds=int(env.get("MUSICME_AUDIO_TRACK_TTL_SECONDS", str(30 * 24 * 3600))),
            staging_ttl_seconds=int(env.get("MUSICME_AUDIO_STAGING_TTL_SECONDS", str(24 * 3600))),
        )
    except ValueError as error:
        raise SystemExit(f"Invalid audio service configuration: {error}")
