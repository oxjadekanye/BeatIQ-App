# MusicMe fal.ai production runbook

Status: implementation complete locally; **not deployed**; real generation and commercial/public use remain blocked.

## Reviewed provider surface

Reviewed against fal.ai's official model pages and queue OpenAPI on 23 October 2026:

- Custom lyrics: `fal-ai/ace-step`. `tags` is required; `lyrics` is optional; structured lyric labels such as
  `[verse]`, `[chorus]` and `[bridge]` are supported.
- Prompt creation: `fal-ai/ace-step/prompt-to-audio`. `prompt` is required and `instrumental` is supported.
- Queue protocol: submit is `POST https://queue.fal.run/<endpoint id>`; status, result and cancel use only the
  owner/app (`fal-ai/ace-step/requests/<id>[/status|/cancel]`) for both endpoints — fal answers 405 when the
  `/prompt-to-audio` subpath is included. Cancel is best effort: `202 CANCELLATION_REQUESTED` means a running request
  may still finish and bill. Generation is polled; there is no webhook/callback endpoint to verify or abuse.
- Duration: 5–240 generated seconds (the model pages document no range; 240 s is MusicMe's own conservative cap). MusicMe sends one take and the reviewed defaults: 27 steps, `euler`, `apg`,
  granularity 10, guidance 15, minimum guidance 3, tag guidance 5 and lyric guidance 1.5.
- Current listed price: **$0.0002 per generated second** ($0.012 / 60 s, $0.024 / 120 s,
  $0.036 / 180 s, $0.048 / 240 s), excluding unrelated hosting/storage charges.
- fal.ai does not identify these hosted endpoints as ACE-Step 1.5 or publish an immutable model revision. Stored
  metadata therefore says `provider-managed-unpinned`; it must never be presented as ACE-Step 1.5.

References: [custom-lyrics API](https://fal.ai/models/fal-ai/ace-step/api),
[prompt-to-audio API](https://fal.ai/models/fal-ai/ace-step/prompt-to-audio/api),
[fal.ai pricing](https://fal.ai/pricing).

## Architecture and safety boundaries

Android authenticates with the existing BeatIQ account token and calls only the BeatIQ MusicMe audio API. The API
validates account ownership, reserves the accepted maximum output duration in PostgreSQL, submits one fal queue request, stores
the provider request id immediately, resumes that same request after a crash, validates the downloaded WAV, and
uploads it to a private EU-jurisdiction Cloudflare R2 bucket. Android never receives `FAL_KEY`, R2 credentials, or an
R2 signed URL; it downloads through a short-lived, account-bound BeatIQ URL.

The new execution path contains no Modal call and no temporary MusicMe DEV authentication service. The older
`remote_gpu`, local ACE-Step and Modal files remain only as isolated legacy/DEV tooling and are not referenced by
the production Blueprint.

Server modes are authoritative:

- `disabled`: the default Blueprint state. Authenticated capabilities work, but every submission is denied.
- `internal_test`: requires `MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION=true` and a comma-separated allowlist of
  stable authenticated user ids. It enforces three successful/active submissions per rolling 24 hours, one active
  job per account, one application attempt, and a $2 UTC-day application ledger. Sharing and commercial
  distribution remain false.
- `public`: additionally requires explicit commercial-rights, security, and activation approvals plus an eligible
  user-id allowlist. The implementation deliberately has no public sharing endpoint.

Possessing `FAL_KEY`, configuring the Android URL, or changing a client flag cannot enable generation. The gate is
checked at submission and again by the worker immediately before any new paid provider request, so jobs queued before
the kill switch or before an account left the allowlist never start spending; an already-saved provider request is only
resumed, never resubmitted. A job that reserved fal spend counts against the account's 3-per-24-hours limit even if it
is later cancelled, fails, or the account's data is deleted. Successful jobs settle the ledger at the larger of the
measured and requested duration.

## Production configuration

The prepared Blueprint is `infra/render/musicme-production-fal.yaml`. It creates a new Frankfurt MusicMe web
service and a new MusicMe PostgreSQL database. It does not reuse BeatIQ's production database, BioAegix, the Render
DEV service/database, Modal, or the temporary DEV auth service. Auto-deploy and preview resource generation are off.

Dashboard-only values (never place values in Git):

- `FAL_KEY`
- `MUSICME_AUDIO_PUBLIC_BASE_URL`
- `MUSICME_AUDIO_JWKS_URL`, `MUSICME_AUDIO_JWT_ISSUER`, `MUSICME_AUDIO_JWT_AUDIENCE`
- `MUSICME_AUDIO_OBJECT_ENDPOINT` (must be the R2 EU-jurisdiction S3 endpoint)
- `MUSICME_AUDIO_OBJECT_BUCKET` (new private production bucket)
- `MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID`, `MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY` (production bucket-scoped object
  read/write only)
- `MUSICME_AUDIO_INTERNAL_TEST_USER_IDS` and, later, `MUSICME_AUDIO_PUBLIC_ELIGIBLE_USER_IDS`

Render generates the separate download-link signing secret. The R2 bucket must have no public development URL,
custom public domain, anonymous policy, or cross-bucket permission. Enable provider-side fal spend controls as a
second, independent cap. `requirements.lock` is the reviewed Python 3.12 Linux runtime lock and the build uses
`pip --require-hashes --only-binary=:all:`.

## Migrations, readiness, restart and rollback

Startup applies the namespaced, idempotently recorded migrations before opening HTTP. `0003_fal_production.sql`
adds immutable provider-request recovery, the atomic fal ledger, and song metadata. `/healthz` is liveness;
`/readyz` requires PostgreSQL and private storage readiness and is the Render health check. A failed migration,
unreachable database, invalid auth configuration, unreadable R2 bucket, or missing provider key stops startup.

For rollback, first set `MUSICME_AUDIO_GENERATION_MODE=disabled` and restart. Do not roll back the database schema;
the added columns/tables are backward-compatible with the disabled service. Redeploy the prior application revision
only after the kill switch is confirmed. Provider requests with saved ids remain recoverable; never resubmit an
uncertain request. Preserve PostgreSQL and R2 until retention and incident review are complete.

Monitor without user content: readiness/restart count, HTTP status/code counts, queue age, running lease age,
provider errors by allow-listed code, daily request/output-second/USD ledger, validation rejection counts, R2
failures, storage use and deletion failures. Logs must not include tokens, prompts, lyrics, account ids, signed URLs
or storage keys.

## Minimal controlled internal-test activation

1. Obtain provider/legal confirmation for internal evaluation; public/commercial rights remain unapproved.
2. Create the private EU R2 production bucket and bucket-scoped credentials; verify public access is off.
3. Create/import the Blueprint manually and enter the dashboard-only values above. Keep mode `disabled`.
4. Confirm the new database/service identities and inspect the first migration logs. Verify `/readyz` only; make no
   generation request.
5. Set the provider-side fal spend cap, alerts and incident owner.
6. Enter stable tester user ids in `MUSICME_AUDIO_INTERNAL_TEST_USER_IDS`.
7. Change only `MUSICME_AUDIO_GENERATION_MODE=internal_test` and
   `MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION=true`, then restart.
8. Configure the Android build with `-Pmusicme.audio.url=https://<reviewed-service-host>/` (or the CI environment
   variable `MUSICME_AUDIO_URL`). No provider secret belongs in the APK.
9. Run the three controlled tests below one at a time. Stop immediately for budget, rights, auth, ownership,
   clipping, leakage or unexpected duplicate-request findings.

Public activation is a separate change and is blocked until commercial-rights approval and a security acceptance
record exist. `COMMERCIAL_RIGHTS_APPROVED`, `SECURITY_ACCEPTED`, and `PUBLIC_ACTIVATION_APPROVED` must remain false.

## Three-song real-audio acceptance procedure

Do not execute without explicit approval for chargeable generation. Use one allowlisted account, one idempotency key
per song and one take. Record the provider request id, measured duration, wall latency, ledger estimate and actual
listed cost without recording lyrics or credentials.

1. Contemporary Nigerian Afrobeats, 120 seconds: original English/Pidgin verses and a melodic chorus; polished
   syncopated percussion, warm bass, expressive arrangement.
2. Romantic Afro-R&B, 120 seconds: user-supplied original lyrics, intimate expressive vocal, warm electric keys,
   restrained percussion and spacious mix.
3. Instrumental Afrobeats, 120 seconds: no lyrics/vocals; rich percussion, warm bass, an original memorable motif
   and coherent verse-to-hook development.

Score each 1–5 (a score below 3 fails) and add factual notes:

| Dimension | Objective check | Listening check |
|---|---|---|
| Vocal intelligibility | words/sections present; no truncation | understandable phrasing and diction |
| Nigerian pronunciation | native-speaker review required | Pidgin/Yoruba stress and vowels; document errors |
| Melody and originality | no requested reference work | coherent, memorable, no obvious imitation |
| Groove | stable timing; no broken transitions | rhythmic pocket appropriate to style |
| Arrangement | requested duration within tolerance | intentional section development and ending |
| Audio integrity | valid WAV, no silence, clipping threshold passed | no clicks, warble, metallic or digital artifacts |
| Latency | submit-to-result wall seconds | acceptable for an accurately labelled queued flow |
| Cost | generated seconds × current official rate | matches application and provider ledgers |

Three reviewed outputs are evidence for an internal decision only. Do not claim release quality, exclusivity,
copyright ownership, guaranteed originality, commercial permission, or freedom from third-party claims.

## Resources to retire later (do not delete during this rollout)

After production acceptance and an explicit owner decision, inventory and separately retire the isolated Render DEV
audio/auth services and database, Modal `musicme-gpu-worker-dev`, its secret and volumes/images, and the DEV R2
bucket. Confirm retention/export requirements and billing first. None is deleted or modified by this implementation.

## Known baseline validation failures (not introduced by MusicMe)

`./gradlew :app:lintDebug` fails on six errors that predate this branch, in files it does not modify. They are
recorded here as baseline and intentionally left unchanged; MusicMe files have no lint errors or warnings.

| File | Check | Count |
|---|---|---|
| `app/src/main/java/com/beatiq/music/services/playback/BeatPlaybackService.kt` | `UnsafeOptInUsageError` (media3 `UnstableApi`) | 4 |
| `app/src/main/java/com/beatiq/music/MainActivity.kt` | `UnspecifiedRegisterReceiverFlag` (`DOWNLOAD_COMPLETE` receiver) | 1 |
| `app/src/main/java/com/beatiq/music/core/ui/premium/PremiumUiKit.kt` | `SuspiciousIndentation` | 1 |

Validated on 23 October 2026: backend pytest 266 passed (SQLite and PostgreSQL), Android unit tests 107 passed,
debug APK and release Kotlin compilation succeeded.
