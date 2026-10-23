# MusicMe Phase 2.3B — Audio generation service foundation (21 Oct 2026)

> **Status: infrastructure only. No real audio is generated.** No model was downloaded or run, no GPU provisioned,
> nothing deployed or installed. The only working engine is a development-only mock that writes sine tones so the
> job, storage and validation paths can be tested; it is labelled `mock` / `real_audio: false` in every response.

Implements the Phase 2.3A design ([MUSICME_PHASE_2_3A_AUDIO_ARCHITECTURE.md](MUSICME_PHASE_2_3A_AUDIO_ARCHITECTURE.md))
as `services/musicme-audio` plus a debug-only Android client boundary. ACE-Step 1.5 remains an evaluation candidate,
not an approved model.

## Architecture

```mermaid
flowchart LR
  App[Android debug build\nAudioGenerationService] -- "JWT + Idempotency-Key" --> API
  subgraph svc["services/musicme-audio (separate process, MUSICME_AUDIO_* config)"]
    API[HTTP API\nauth · contract · safety · quotas] --> DB[(audio_jobs / audio_objects\nSQLite dev · PostgreSQL)]
    W[Worker pool\nclaim · heartbeat · validate] --> DB
    W --> E{{Engine interface v1\nMockToneEngine (dev only)\nAceStepEngine (refuses to start)}}
    W --> S[(Private storage\nLocalObjectStore, 0700/0600)]
    API -- "signed link, 10 min" --> App
  end
  App -- "GET signed URL" --> API
  SW[musicme-songwriter] -. "no shared code, DB or secrets" .- svc
```

| Area | Implementation |
|---|---|
| Isolation | Own package (`audioservice`), env prefix `MUSICME_AUDIO_*`, tables prefixed `audio_`, own migrations table and advisory-lock id, own JWT and link-signing secrets (must differ), refuses to start on the songwriting DB URL |
| Auth | Adapted copy of the songwriting verifier: HS256 or RS256/ES256 via JWKS, issuer/audience, `token_type=access`, fails closed |
| Jobs | Durable state machine with conditional updates; atomic claims (`SKIP LOCKED` / `BEGIN IMMEDIATE`); heartbeats renew leases; expired leases requeue (`WORKER_LOST`); max 2 attempts with a new recorded seed per retry; every worker write needs an unexpired lease, so an expired or cancelled worker can never publish; cancellation at any point; lyrics purged at terminal states |
| Idempotency | Unique `(account, Idempotency-Key)` + body hash; replay returns the same job; different body → 409 |
| Progress | `stage` (queued, rendering, validating, storing, done/failed/cancelled), `progress` 0–100, `queue_position` |
| Metadata | Engine, model id/version/revision, interface version, base seed, per-take seeds, render parameters (no lyrics), render time, attempt |
| Quotas | 1 active job/account, 3 songs/account/24 h (failed and cancelled refunded), 512 MB storage/account (checked at submit and atomically at publish), 20 queued globally, 64 KB request bodies, 64 MB files |
| Engine adapter | `RenderRequest` from validated fields only: canonical section tags from section types (never user labels), unchanged lyrics, structured caption (genres, moods, instruments, vocal type/descriptors, BPM), language, duration, seed |
| Output validation | 16-bit PCM WAV at 44.1/48 kHz, duration within ±25% of target, not silent, ≤1% clipped samples, size limit, magic bytes match the format |
| Storage | Server-generated keys with hashed account namespace, strict key pattern + realpath containment, atomic rename after validation, owner-only permissions, only committed objects downloadable |
| Signed links | HMAC-SHA256 over object id + account namespace + expiry; valid for exactly the configured lifetime, 600 s (10 min) by default, configurable 60–900 s and never longer than 15 min; forged → 403, expired → 410, deleted/foreign-account → 404 |
| Retention | Tracks and job rows 30 days; staging files of jobs no longer running removed at the next sweep; orphans swept; per-track and whole-account deletion |
| Safety | Reference-audio/voice/cover/LoRA fields refused by name; unknown fields refused; copyright, voice-imitation and "in the style of <artist>"/cover prompts refused; English only |
| Logging | Job-id prefixes, account hashes, codes, timings; paths logged with ids and link tokens redacted; never lyrics, captions, tokens, links or storage paths (tested) |
| Production | Refuses to start: mock engine, local storage (no reviewed object store yet), SQLite, missing issuer/audience, HS256 without opt-in, non-HTTPS links |

## Android integration boundary (defined, not wired into any screen)

Package `com.beatiq.music.musicme.audiogeneration`:

* **`AudioGenerationService`** (`capabilities`, `submit`, `status`, `cancel`, `downloadLink`, `deleteTrack`) with
  `RemoteAudioGenerationService` (OkHttp, production-mutation guard, bearer token, idempotency key) and
  `UnconfiguredAudioGenerationService`.
* **`AudioGenerationDependencies`**: release builds always get the unconfigured service; debug builds only when
  `musicme.audio.url` in `local.properties` is HTTPS or loopback. `BuildConfig.MUSICME_AUDIO_URL` is empty by default.
* **`AudioGenerationRequestBuilder`**: builds requests from lyrics and musical choices only. The story, recipient,
  desired message and voice recording never leave the device. Ambiguous vocal arrangements (e.g. "Lead and
  Backing Vocals") require an explicit male/female/duet/choir choice instead of a guess.
* **States**: `AudioJob.displayStatus` maps onto MusicMe's existing `MusicMeGenerationStatus`
  (QUEUED → GENERATING while rendering → PROCESSING while validating/storing → COMPLETED / FAILED / CANCELLED), so the
  current screens' states can be reused without redesign.
* **Preview**: request a fresh signed link (`downloadLink`) and play it with the app's existing Media3/ExoPlayer, or
  play the saved file. Links are only accepted for the configured service host over HTTPS (loopback in development).
* **Save to creations**: `AudioTrackFiles.download` streams into `filesDir/musicme_audio/<account key>/<track id>.<fmt>`
  after checking content type, exact size and SHA-256; the file appears only after every check (atomic rename).
  The draft would record the track id. The folder is excluded from cloud backup, like voice notes.
* **Export**: `AudioTrackFiles.exportTo(OutputStream)` for a destination the user picks with the system file picker
  (`ACTION_CREATE_DOCUMENT`), which needs no storage permission on any supported Android version (minSdk 26).
* Mock output must never be presented as a song: the UI must check `generation.mock` / `generation.realAudio` /
  `capabilities.realAudio`; missing flags are treated as mock and not real.

## Mock versus real

| Works now (tested) | Not real yet |
|---|---|
| Auth, isolation, jobs, leases, recovery, cancellation, idempotency, quotas | Any music or singing: the mock writes sine tones |
| Private storage, signed links, retention, deletion | ACE-Step 1.5 adapter: interface and provisional parameter mapping only; refuses to start |
| Technical audio validation (format, length, silence, clipping) | Lyric intelligibility (ASR), musical quality, similarity screening |
| Android client, request building, downloads with integrity checks | Any MusicMe screen using it; production object storage; GPU workers |

## Verification (21 Oct 2026)

* `services/musicme-audio`: 124 tests passed. Store tests run on SQLite and a throwaway local PostgreSQL. Coverage:
  auth on every endpoint, account isolation, duplicate and concurrent submissions, concurrent workers, crashed-worker
  lease recovery, heartbeats, cancellation mid-render, private storage and path traversal, forged/expired links,
  retention and deletion, invalid outputs (silent, clipped, short, garbage), retries, quotas, fail-closed
  configuration, and log privacy.
* `services/musicme-songwriter`: 154 tests passed (unchanged).
* Android: 119 unit tests passed (110 existing + 9 new); lint 0 errors, with no lint issues in the new package;
  `assembleDebug` and `compileReleaseKotlin` succeeded. Nothing was installed on a device.

## Security review findings

Fixed during the phase:
* Malformed WAV headers raised `RuntimeError` inside Python's `wave` module and would have crashed validation as an
  unknown error; now reported as `AUDIO_INVALID` (found by the garbage-file test).
* Song titles were initially screened with the artist-style rules, which would have refused titles like "Shine Like
  Gold"; titles now get the copyright/voice rules only, while style fields get the artist rules.
* The vocal type defaulted to "female"; it is now required, so the service never guesses a singer's voice.
* (Final review) A worker whose lease had expired could still publish if the recovery sweep had not run yet; every
  worker write now also requires `lease_expires_at >= now`.
* (Final review) The Android client now treats missing `mock` / `real_audio` flags as mock / not real.

Accepted for this phase (documented):
* A signed link can be reused by anyone holding it until it expires: exactly the configured lifetime, 10 minutes by
  default (the configuration cannot exceed 15). Links are never logged.
* Python's `ThreadingHTTPServer` is for development; production needs a hardened front end (TLS, request limits).
* A real engine call may not be interruptible; the lease and render deadline bound the damage, as in songwriting.
* Local deletion is an unlink; secure erasure in production must come from the encrypted object store.
* HS256 shared secrets remain possible in development; production requires asymmetric tokens unless opted in.

## Outstanding risks

* No real model has run: quality, duration accuracy, lyric intelligibility, speed and GPU cost are unmeasured.
* The ACE-Step parameter mapping is provisional; the tag vocabulary and pipeline inputs need the local spike.
* Production object storage, CDN/egress controls and encryption at rest are not implemented (production refuses to start).
* No similarity/fingerprint screening of outputs; legal items from Phase 2.3A (training-data provenance, output
  ownership, voice likeness, labelling) remain open.
* Non-English vocals are disabled and unvalidated.
* Validation reads every sample in pure Python: fine for development, slow for long 48 kHz stereo masters.
