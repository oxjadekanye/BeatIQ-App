# BeatIQ MusicMe permanent cloud architecture and operations

Status: local infrastructure definition only, revised 21 October 2026. Nothing in this document has been
provisioned or deployed. Every action labelled **approval** can create or change an external resource.

## 1. Target architecture

```text
Samsung DEV debug app (isolated dev-* account; public URLs only)
       | HTTPS JWT
       v
Render protected project/environment: beatiq-musicme / samsung-dev
  +-- DEV auth web + 1 GiB encrypted persistent disk
  +-- audio API + one in-process durable-job coordinator (one paid instance)
  +-- private Render Postgres (jobs, ownership, idempotency, leases, quotas, GPU ledger)
  +-- optional songwriter web + its own Postgres (not in the minimum deployment)
       |
       +-- private Cloudflare R2/S3-compatible bucket (completed songs only)
       |     server-generated per-account keys; private; direct presigned GETs; 30-day retention
       |
       +-- authenticated HTTPS musicme.gpu-job.v1
              Modal T4: min 0, buffer 0, max 1, 120 s scale-down
              pinned ACE-Step runner + persistent verified model-weight volume
```

DEV combines the API and coordinator to avoid another always-on Render instance. The coordinator's state is not in
process: PostgreSQL leases recover queued/running jobs after a deploy or crash, the database GPU reservation remains
charged after a crash, and completed objects live outside the instance.

For future horizontal production scale, run web instances with `MUSICME_AUDIO_RUN_WORKERS=false` and one or more
`python3 -m audioservice.worker_service` Render background workers against the same dedicated production Postgres and
object bucket. `FOR UPDATE SKIP LOCKED` and leases prevent duplicate claims. This split adds at least one paid Render
worker, so it is deliberately dormant in Samsung DEV.

The prepared production Blueprint is a separate project, database, bucket namespace and asymmetric identity
configuration. It sets `MUSICME_AUDIO_ENGINE=none` and contains no GPU URL or credentials. Server code also requires
the absent `MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION=true` gate before **any** production generation engine (remote
GPU or a local ACE-Step runner) can start. The Android release build retains its independent generation-disable gate.

On a clean shutdown (deploy, scale-down, SIGTERM) the coordinator stops claiming work, cancels the paid GPU job, and
hands a job it still owns straight back to the queue without using up an attempt; a user's cancellation is never
overridden. A crash instead leaves the lease to expire, after which the job is retried as `WORKER_LOST`.

## 2. Isolation boundaries

- Never select, adopt, rename or attach an existing BioAegix or other application's service, database, disk,
  environment group, secret or domain.
- DEV and production use distinct Render projects/environments, Postgres instances, object buckets, object API
  tokens, JWT issuers/audiences, signing keys, Modal environments/proxy tokens and worker bearer tokens.
- Render network isolation and environment protection are enabled; previews and automatic deploys are disabled.
- Databases have an empty public IP allow-list. Application connections use Render's managed private connection.
- Buckets remain private with no `r2.dev` public URL or cached custom domain. Runtime credentials have object
  read/write/list/delete access to exactly one bucket, not bucket-administration or account-wide permissions.
- No reusable JWT, object-store, Render, Modal or worker credential is present in the APK or repository.

## 3. Durable data and ownership

PostgreSQL is authoritative for job state, user ownership, idempotency keys, attempts, leases, quotas, object metadata,
hashes, retention and daily GPU usage. Lyrics/style input is purged when a job reaches a terminal state. Another
account receives `404` for a foreign job or track.

Completed WAVs use `objects/<sha256(account)[:32]>/<random UUID>.wav`. Client input never forms a storage key. Before
upload the API validates regular-file status, size, magic bytes and WAV content. Each song is sent as one `PUT` with
`Content-MD5`, so the bucket recomputes the digest over the bytes it received and rejects a corrupted transfer. The
object also records SHA-256 metadata; the API performs a `HEAD` after upload and deletes/refuses any size or digest
mismatch. Database publication occurs only after verification, and the Android app re-checks the SHA-256 of every
download. boto3 (1.36+) runs with `request_checksum_calculation=when_required` because S3-compatible stores do not all
accept the SDK's default streaming checksums. Failed, cancelled, expired and orphaned objects are cleaned
automatically; keys are registered in PostgreSQL before upload, so the orphan sweep never removes an in-flight song.

Download flow:

1. The authenticated account requests a link for a track id.
2. PostgreSQL verifies that the committed, unexpired track belongs to that account.
3. The server creates a private provider-signed HTTPS GET URL valid for at most ten minutes.
4. The bucket stays private; the URL grants only that object's GET until expiry.

Account deletion removes rows and objects. If an object deletion transiently fails, the database row is already gone
and the orphan sweep removes the inaccessible object after the staging safety window. Production privacy review must
define the maximum acceptable orphan window and any backup-copy deletion workflow before launch.

## 4. Configuration and credentials

### Render Samsung DEV

Generated by Render:

- `MUSICME_DEV_AUTH_JWT_SECRET`
- `MUSICME_DEV_AUTH_INTERNAL_TOKEN`
- `MUSICME_AUDIO_SIGNING_SECRET`
- Render's private `MUSICME_AUDIO_DATABASE_URL`

Entered through Render's secret UI (`sync: false`):

- `MUSICME_AUDIO_PUBLIC_BASE_URL`
- `MUSICME_AUDIO_JWT_REVOCATION_URL`
- `MUSICME_AUDIO_WORKER_URL`
- `MUSICME_AUDIO_WORKER_CREDENTIALS_JSON`
- `MUSICME_AUDIO_OBJECT_ENDPOINT`
- `MUSICME_AUDIO_OBJECT_BUCKET`
- `MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID`
- `MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY`

The worker credential document is:

```json
{
  "token": "independent-random-worker-token-32+-characters",
  "headers": {
    "Modal-Key": "endpoint-proxy-key",
    "Modal-Secret": "endpoint-proxy-secret"
  }
}
```

The same worker bearer token is stored in Modal secret `musicme-gpu-worker-token` as
`MUSICME_WORKER_TOKEN`. Modal proxy credentials exist only in Render. Object-store credentials exist only in Render
and are scoped to the DEV bucket. Do not use an account-wide Cloudflare API token as runtime S3 credentials.

### Future production, generation disabled

- Use a reviewed production OIDC provider issuing RS256 tokens.
- Configure its HTTPS JWKS URL, exact issuer and `musicme-production` audience.
- Never reuse DEV auth or its HS256 secret. `musicme-dev-auth` is not a production identity service.
- Use a separate private production bucket/token and Render Postgres.
- Leave `MUSICME_AUDIO_ENGINE=none`, omit all worker variables and omit
  `MUSICME_AUDIO_ENABLE_PRODUCTION_GENERATION` until a separate launch approval.

## 5. Health, logs and monitoring

- `/healthz` is process liveness and never wakes Modal.
- `/readyz` checks PostgreSQL and the already-initialised storage client; Render uses it for the audio and songwriter
  health checks. Bucket access is verified once, synchronously, at process startup to avoid billable health-check
  requests on every probe.
- Application logs include short job ids, account hashes, safe error codes, durations and lifecycle events. They do
  not log request bodies, lyrics, JWTs, signed URLs, storage keys or credentials.
- Enable Render failure/unhealthy notifications before Samsung testing. Review CPU, memory, HTTP errors, Postgres
  connections/storage, deploy failures and `WORKER_LOST`, `STORAGE_UNAVAILABLE`, `gpu_budget_refused` log events.
- Set Modal workspace usage and spend limits before deployment. The PostgreSQL ledger is defence in depth: it reserves
  worst-case cold-start/render/tail cost before submission and also limits render count.
- Initial alerts: 50%/80%/100% of the approved Modal budget; any storage-integrity failure; three worker losses in one
  hour; five 5xx responses in five minutes; queue age above 15 minutes; Postgres storage above 70%.
- Before public production, connect a reviewed log/metric destination and establish an on-call recipient. Render's
  dashboard alone is acceptable for the bounded Samsung DEV test, not for an unattended public launch.

## 6. Backup and recovery

- Paid Render Postgres has continuous point-in-time recovery. Verify the dashboard window before launch (currently
  three days on Hobby and seven days on Pro+). Create a logical export before schema changes and at least monthly in
  DEV; for production, automate encrypted off-provider exports and perform a quarterly restore drill.
- PITR restores to a new billable database. Validate it in isolation, update the Blueprint/database reference, run
  `/readyz` and ownership/idempotency tests, then remove the old database only after explicit destructive approval.
- The DEV-auth disk receives Render's daily encrypted snapshot. Before auth upgrades, download an encrypted copy of
  the password-hash/revocation store; never export it into the repository.
- On Render the DEV JWT secret comes from `MUSICME_DEV_AUTH_JWT_SECRET`, so `devauth.py rotate-secret` does not rotate
  it. To invalidate every token, regenerate that variable in the dashboard and redeploy DEV auth and the audio service
  (**approval**); `devauth.py revoke --all` in a Render shell revokes all current tokens without rotation.
- R2/S3 is the durable primary for songs, not a Render disk. Keep DEV and production buckets separate. Use provider
  inventory/size metrics and periodically sample-verify the database SHA-256 metadata. Eleven-nines provider
  durability protects hardware failure, but is not protection from authorised deletion.
- Do not bucket-lock the primary song prefix: account deletion must work. A production backup copy, if legal/privacy
  review requires one, needs a separate credential and a deletion-tombstone process with a documented maximum purge
  delay. DEV keeps no second copy.
- Configure a provider lifecycle as a backstop slightly longer than the database retention (for example 35 days for
  the current 30-day policy). The application remains responsible for prompt account deletion.

## 7. Cost controls and cheapest safe baseline

Current list-price planning estimate, not a quote:

- Render DEV auth web: `$7/month`; its 1 GiB disk: `$0.25/month`.
- Render audio API/coordinator web: `$7/month`.
- Render audio Postgres 0.1 CPU / 256 MiB including 1 GiB: `$6/month`.
- Minimum permanent DEV Render baseline: **about `$20.25/month`** plus tax/overages.
- Optional songwriter plus isolated database: **about `$13/month`**, bringing DEV to **about `$33.25/month`**.
- Do not deploy the disabled production baseline yet; it would add about `$13/month` without enabling generation.
- R2 Standard currently includes 10 GB-month, one million Class A and ten million Class B operations monthly, then
  lists `$0.015/GB-month`, `$4.50/million` Class A and `$0.36/million` Class B, with no internet egress fee. Eligibility
  and billing-account state must be verified; never budget on free usage without checking.
- Modal's configured T4 + 2 cores + 16 GiB is approximately `$0.8126/hour` while running. The 6.34 GiB weight volume
  lists at about `$0.57/month`, but current Starter pricing says volumes include up to 1 TiB free; confirm that this
  workspace receives the allowance. Expected first-song compute is `$0.03–$0.15`, with about `$0.23` as the
  configured per-container upper bound (900 seconds plus 120-second idle tail). Keep the confirmed **$2 workspace
  usage and spend limits** unchanged and use a `$0.75/day` Render ledger limit for the first test.

The existing Render subscription is a workspace entitlement, not proof that service/database capacity is prepaid.
Before provisioning, inspect Billing and every existing resource read-only. Reuse only a documented, prepaid Render
credit; do not reuse an application resource. The protected MusicMe names must be new.

## 8. Provisioning checklist in exact order

Steps 1–3 are free/read-only or local and do not create cloud resources. Steps marked **approval** can create costs.

1. Re-run all local tests, YAML parsing, secret scanning and ACE-Step pin-diff checks.
2. In Render Billing, record workspace plan, unused credits, current monthly spend and the exact prices shown in the
   Blueprint preview. Inventory names only; confirm no `beatiq-musicme*` collision. Do not alter existing resources.
3. Choose unique DEV hostnames/bucket name and retain the existing `$2` Modal workspace usage and spend limits. Confirm
   synthetic-only Samsung test data.
4. **Approval — object storage:** create one private DEV R2/S3 bucket; keep public access/custom-domain caching off;
   create a bucket-scoped object read/write/list/delete S3 token; record endpoint, bucket, access id and secret.
5. **Approval — Modal isolation/storage/secrets:** create environment `musicme-dev`; create the
   `musicme-acestep-weights` volume and `musicme-gpu-worker-token` secret there; verify the existing $2 workspace
   usage and spend limits before compute.
6. **Approval — Modal preparation:** run `prepare_weights`. It downloads only the committed revision/folders and
   verifies all eight hashes. Stop if any hash, commit, CUDA/runtime or manifest check fails.
7. **Approval — Modal deployment:** deploy the T4 adapter, create an endpoint proxy token, record the HTTPS URL and
   verify unauthenticated requests are rejected without starting a container.
8. **Approval — Render:** first run Render's own Blueprint validation (CLI or API) on `infra/render/musicme-dev.yaml`.
   Three details aren't confirmed from the published reference: the nesting of `networking.isolation` and
   `permissions.protection`, `fromService.envVarKey` pointing at another service's generated secret, and an empty
   `ipAllowList` blocking all external access. Then import only that file. Review the preview for exactly two web
   services, one Postgres and one 1 GiB auth disk at roughly `$20.25/month`. Reject any adoption/match with an existing
   service.
9. Enter all `sync: false` values. Use the worker JSON above. Confirm previews/autodeploy off, project protection and
   network isolation on, database public IP list empty, one instance each, and `/readyz` healthy.
10. **Approval — DEV identity mutation:** use a one-off Render shell to create the isolated `dev-…` Samsung tester.
11. Put only DEV public URLs in untracked `local.properties`; build debug. **Approval — device mutation:** install only
    the debug APK on the Samsung.
12. Submit synthetic 30-second lyrics, test one cancellation, then one completion. Verify progress, WAV metadata,
    playback, restart persistence, cross-account 404, deletion, revocation and log redaction.
13. Confirm PostgreSQL rows and private object metadata agree; confirm Modal returns to zero containers after 120
    seconds; record billed seconds, cold start, render time and total spend.
14. Rotate the test JWT/worker/proxy credentials if exposed during debugging. Keep or delete the DEV resources only
    after an explicit cost/destructive decision.

Do not import `musicme-production-disabled.yaml` during the Samsung phase. It is a reviewed future boundary, not a
request to pay for an idle production service.

## 9. Emergency shutdown

In order of fastest cost containment:

1. Disable/suspend the Modal deployment or revoke its proxy token. This prevents new paid GPU starts immediately.
2. Set the Render audio service to `MUSICME_AUDIO_ENGINE=none` and redeploy, leaving PostgreSQL and objects intact.
3. Rotate the worker bearer token in Modal and Render. Do not reuse the compromised value.
4. Inspect PostgreSQL `audio_jobs` and the GPU daily ledger before cancelling rows. Cancellation/deletion is a data
   mutation and requires incident-owner approval; preserve an export first unless active abuse makes containment
   more urgent.
5. If the API itself is unsafe, enable Render maintenance mode or suspend only the isolated MusicMe service. Never
   suspend a workspace-wide or BioAegix resource.
6. Preserve safe logs, billed-usage evidence and object inventory; rotate object credentials if storage exposure is
   suspected. Restore PostgreSQL through PITR into a new isolated database when required.

## 10. Approval boundaries

Explicit approval is required for: creating any R2/S3 bucket/token, Modal secret/volume/proxy token/deployment,
downloading model weights into provider storage, importing/syncing either Blueprint, creating a Render user through a
shell, connecting or changing domains, installing the APK, changing spend limits, restoring a database, deleting or
suspending resources, rotating live credentials, enabling production generation, committing or pushing.

No approval is needed for local source edits, static validation, local fake-provider tests, YAML parsing, compilation,
secret scans, or read-only review of published pricing/documentation and the owner's dashboards.

## 11. Verification record (21 October 2026, local only)

| Check | Result |
|---|---|
| musicme-audio pytest (SQLite + throwaway local PostgreSQL, loopback HTTP only) | 228 passed |
| musicme-songwriter pytest | 157 passed |
| musicme-dev-auth pytest | 6 passed |
| Android unit tests | 128 passed; lint 0 errors; `assembleDebug` and `compileReleaseKotlin` succeed |
| Python compile, entrypoint `sh -n`, YAML parse of all three Blueprints | Pass |
| Credential scan of the diff and new files (with a positive control) | Clean |
| ACE-Step `runner.py`, `fp16_fix.py`, `manifest.json`, `protocol.py`, `manifest.py` | Byte-identical to the commit |

Fixed during the final review:

- The bucket now verifies upload bytes (`Content-MD5`), and invalid storage keys report `INVALID_KEY` rather than a
  storage outage.
- The production kill switch covers every generation engine, not only the remote GPU engine.
- A clean coordinator shutdown requeues its job at once instead of waiting about six minutes for the lease to expire
  and using up an attempt.
- The Blueprints quote `"off"`: unquoted `off` is a YAML 1.1 boolean, so its meaning would depend on the parser.

New tests:

- 12 parallel PostgreSQL/SQLite reservations against a 5-render limit grant exactly 5.
- A crashed reservation stays charged.
- Two coordinators never claim the same job.
- Shutdown cancels the GPU job and releases the durable job.
- The DEV Blueprint environment is accepted by the real configuration loader, and the production one refuses every
  engine.

Known limits:

- A worker crash mid-render (not a clean shutdown) can leave its GPU job running until its worker-side deadline while
  the retry submits a new one. Both are charged in the ledger, so the daily limits still hold, but real GPU spend for
  that day can include the abandoned render.
- The S3 scratch directory isn't swept at start-up. It lives on Render's ephemeral disk, which is cleared on restart.
