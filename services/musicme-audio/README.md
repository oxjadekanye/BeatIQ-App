# MusicMe audio generation service

The production execution path uses fal.ai's reviewed ACE-Step queue endpoints, PostgreSQL and private Cloudflare R2.
It boots with server-enforced generation disabled. See
[`docs/MUSICME_FAL_PRODUCTION.md`](../../docs/MUSICME_FAL_PRODUCTION.md) for deployment, activation, rights and
real-audio acceptance gates. No paid generation was run as part of this implementation.

> **Engines.** `mock` is a development-only sine-tone generator (not music, no vocals), labelled `"mock": true` /
> `"real_audio": false` everywhere. `ace_step` and `remote_gpu` preserve the separately pinned self-hosted
> ACE-Step 1.5 path. The production `fal` engine uses fal.ai's provider-managed ACE-Step endpoints and deliberately
> records the model version as unpinned: fal.ai does not identify that hosted model as ACE-Step 1.5. The production
> Blueprint uses `ENGINE=fal`, but boots with generation disabled until the documented server-side gates are met.

Design: [docs/MUSICME_PHASE_2_3A_AUDIO_ARCHITECTURE.md](../../docs/MUSICME_PHASE_2_3A_AUDIO_ARCHITECTURE.md).
Phase notes: [docs/MUSICME_PHASE_2_3B_AUDIO_FOUNDATION.md](../../docs/MUSICME_PHASE_2_3B_AUDIO_FOUNDATION.md).

## Isolation from the songwriting service

| | musicme-songwriter | musicme-audio |
|---|---|---|
| Process / package | `songwriter` | `audioservice` (no imports between them) |
| Environment prefix | `MUSICME_*` | `MUSICME_AUDIO_*` only |
| Database | `jobs` table | `audio_jobs`, `audio_objects`, `audio_gpu_daily_usage`, `audio_schema_migrations`; refuses to start on the songwriting DB URL |
| Credentials | its own JWT secret / JWKS | its own JWT secret / JWKS **and** a separate download-link signing secret (must differ from the JWT secret) |
| Default port | 8088 | 8090 |

Authentication, job leasing and the DB layer are adapted copies of the songwriting service's proven code.

## Run locally (development, mock engine)

```bash
cd services/musicme-audio
MUSICME_AUDIO_JWT_SECRET="$(openssl rand -hex 32)" \
MUSICME_AUDIO_SIGNING_SECRET="$(openssl rand -hex 32)" \
MUSICME_AUDIO_ENGINE=mock \
python3 -m audioservice.server
```

Data goes to `./var/` (git-ignored): `musicme-audio.db` (0600) and `audio-objects/` (0700 dirs, 0600 files).

## ACE-Step engine (`MUSICME_AUDIO_ENGINE=ace_step`, development only)

The service (Python 3.9, standard library) never imports torch. It starts `audioservice/acestep/runner.py` with
ACE-Step's own interpreter: `<venv python> -I runner.py`, no arguments, an allow-listed environment (no JWT, signing,
database or Hugging Face secrets), and stderr discarded. They talk in JSON lines over stdin/stdout
(`audioservice/acestep/protocol.py`). Lyrics travel only inside render messages on stdin.

| Setting | Meaning |
|---|---|
| `MUSICME_AUDIO_ACESTEP_PYTHON` | ACE-Step venv interpreter (Python 3.12, `uv sync --frozen` of v0.1.8) |
| `MUSICME_AUDIO_ACESTEP_HOME` | ACE-Step checkout at commit `dce6214`; weights in `<home>/checkpoints` |
| `MUSICME_AUDIO_WORKERS=1` | required: one render per GPU |
| `MUSICME_AUDIO_ACESTEP_STARTUP_TIMEOUT_SECONDS` | handshake limit (default 600) |
| `MUSICME_AUDIO_ACESTEP_RETRY_NAN` | `true` re-renders NaN latents once with a new seed; **off by default** |

The pins (commit, weights revision, SHA-256 of the 8 pinned files, LM directories that must be absent, limits) live
in `audioservice/acestep/manifest.json`, not in configuration.

On startup the runner verifies all of them, blocks every download route, loads the turbo DiT and, in float16, applies
the lyric-encoder overflow fix only after its GPU self-test passes. The service then checks the runner's handshake
against the manifest independently, and refuses to start on any mismatch.

Renders heartbeat at least every 2 s. Cancellation, the render deadline, a crash, CUDA OOM or any protocol violation
kills the runner's process group; the next render starts a fresh, re-verified runner. Every WAV still goes through
`validate_wav` before it is stored, and `real_audio: true` appears only on published, validated tracks.

Limits enforced at submit: lyrics ≤ 4,096 characters including section tags, caption ≤ 512, 10–240 s, 1 take,
English only.

## Remote GPU engine (`MUSICME_AUDIO_ENGINE=remote_gpu`)

`remote_gpu` moves the exact runner above behind the provider-neutral `musicme.gpu-job.v1` HTTP worker. Production
requires a separate explicit server gate that is absent from every prepared configuration. It uses one coordinator
and one remote render, verifies the pinned hello before
every render, and hash-checks the downloaded WAV before the normal validation/storage path. The worker supports
progress, cancellation, hard deadlines, a bounded queue, exact idempotency, one restart recovery, output TTLs and
graceful shutdown.

Credentials come from either a mode-0600 JSON file or a hosted secret value, never from the APK:

```json
{"token":"<worker bearer>","headers":{"Modal-Key":"<id>","Modal-Secret":"<secret>"}}
```

`headers` is optional for RunPod/Vast. HTTPS hosts are suffix-allow-listed (loopback HTTP is allowed for a tunnel).
The conservative cost ledger reserves cold start + render + idle tail before submitting and enforces daily USD and
render-count limits. Local development uses a mode-0600 file; hosted services use PostgreSQL so reservations survive
deploys and instance replacement. The provider's own spend cap is still the authoritative billing control.

Packaging and provider notes are in `deploy/README.md`. The provider-neutral NVIDIA image is
`deploy/worker/Dockerfile`; `deploy/modal/musicme_worker.py` supplies a T4 with `min_containers=0`, `max_containers=1`,
120-second scale-down and Modal proxy auth. The Modal DEV adapter is deployed in the isolated `musicme-dev`
environment; the provider-neutral image and Render services remain undeployed.

Public Render DEV hosting additionally requires HTTPS public/revocation URLs, issuer/audience verification and an
authenticated revocation token. See `infra/render/` and `docs/MUSICME_SAMSUNG_DEV_E2E_PLAN.md`. Production generation
remains disabled.

## API (contract `musicme.audio.v1`)

| Method | Path | Notes |
|---|---|---|
| GET | `/healthz` | unauthenticated liveness |
| GET | `/readyz` | unauthenticated PostgreSQL/storage readiness; used by Render |
| GET | `/v1/audio/capabilities` | engine, `real_audio`, languages, limits, `reference_audio: false`, `voice_cloning: false`, storage usage |
| POST | `/v1/audio/jobs` | `Idempotency-Key` header; body `{contract_version, request}` (below); 202 new / 200 replay / 409 key reused with different body |
| GET | `/v1/audio/jobs/{id}` | `state`, `stage`, `progress`, `queue_position`, `error`, `result.tracks[]`, `result.generation` |
| POST | `/v1/audio/jobs/{id}/cancel` | idempotent; a cancelled job never publishes audio |
| GET | `/v1/audio/tracks` | durable user-owned song history and metadata |
| PATCH | `/v1/audio/tracks/{id}` | rename, favourite or mark saved after ownership check |
| POST | `/v1/audio/tracks/{id}/link` | after ownership check, an account-bound API-signed URL, valid 10 min by default and never over 15 min; R2 credentials/URLs remain server-side |
| DELETE | `/v1/audio/tracks/{id}` | deletes row and file |
| DELETE | `/v1/audio/account` | deletes every job and track for the signed-in account |
| GET | `/v1/audio/files/{token}` | the audio bytes; the signed link is the credential (403 forged, 410 expired, 404 deleted) |

Another account's job or track always looks like 404. Request body:

```json
{"contract_version": "musicme.audio.v1",
 "request": {"draft_id": "d1", "title": "…", "prompt": "optional original direction", "language": "english",
   "sections": [{"id": "s1", "type": "verse", "label": "Verse 1", "lyrics": "…"}],
   "style": {"genres": ["Afro-Soul"], "moods": ["Romantic"], "bpm": 84, "instrumentation": ["Rhodes"]},
   "vocals": {"type": "female|male|duet|choir|none", "descriptors": ["warm"]},
   "target_duration_seconds": 150, "takes": 1, "seed": null, "allow_explicit": false}}
```

Unknown fields are refused. Fields such as `reference_audio`, `voice_sample`, `cover_of`, `lora`, `speaker_embedding`
or `recording_id` are refused by name with `POLICY_REJECTED`. Lyrics are never rewritten.

## Jobs

`queued → running (rendering → validating → storing) → succeeded | failed | cancelled`. Atomic claims
(`FOR UPDATE SKIP LOCKED` on PostgreSQL, `BEGIN IMMEDIATE` on SQLite), leases renewed by heartbeats, lease-expiry
recovery (`WORKER_LOST`), every worker write requires an unexpired lease (an expired or cancelled worker can never publish), at most 2 attempts (each retry uses a new, recorded seed), lyrics purged at terminal states.
Quotas: 1 active job and 3 songs per account per 24 h (failed and cancelled jobs are refunded), 512 MB storage per
account, 20 queued jobs globally.

## Storage and retention

Storage keys are server-generated (`objects/<sha256(account)[:32]>/<uuid>.<fmt>`). Local development validates paths
inside a private root. Hosted storage uses an explicitly credentialed private S3-compatible bucket with a separate
DEV/production bucket and prefix. Files are checked (regular file, size ≤ 64 MB, magic bytes, full WAV validation),
uploaded with SHA-256 metadata and verified with `HEAD` before their rows are committed. Retention: tracks and job
rows 30 days, abandoned staging objects at once (or 24 h at most), and orphan objects swept.

Samsung DEV combines API and coordinator in one process. A future horizontal deployment can set
`MUSICME_AUDIO_RUN_WORKERS=false` on web instances and runs `python3 -m audioservice.worker_service` as a Render
background worker against shared PostgreSQL/object storage.

## Tests

```bash
cd services/musicme-audio
python3 -m pip install --require-hashes --only-binary=:all: -r requirements.lock
python3 -m pip install -r requirements-dev.txt
python3 -m pytest -q -p no:cacheprovider
```

`requirements.txt` contains runtime intent, `requirements.lock` is the Python 3.12 / x86_64 manylinux2014
hash-locked Render input, and `requirements-dev.txt` contains test-only packages. Regenerate the runtime lock with
the exact command recorded in its header, then validate the Linux wheels and the full Python 3.12 suite before use.

Store tests run on SQLite and on a throwaway local PostgreSQL cluster when `initdb`/`pg_ctl` and `psycopg2` exist.
