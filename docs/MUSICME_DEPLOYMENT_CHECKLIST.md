# MusicMe Samsung DEV deployment checklist (updated 23 Oct 2026)

Phase B infrastructure and Phase C have been provisioned; Phase B's lifecycle and cross-bucket isolation checks are
still pending, and Phase D (Render DEV) has **not** been deployed. Every remaining step marked
**APPROVAL** needs the owner's separate go-ahead. Steps run in order and each ends with a stop/go check; stop at the
first failure. Never paste a secret into chat, Git, an issue or the APK. Never select, adopt or modify an existing
Render application (including BioAegix).

Current infrastructure state (credential values are deliberately omitted):

- Modal workspace `oxjadekanye`, environment `musicme-dev`.
- Modal application `musicme-gpu-worker-dev` is deployed.
- All eight pinned ACE-Step 1.5 model files were verified before deployment.
- An unauthenticated request to the Modal worker's `/v1/health` returned HTTP 401.
- Modal workspace usage and spend limits are currently **$2 each**.
- Cloudflare R2 bucket `beatiq-musicme-dev` is private and uses the EU jurisdiction endpoint. Its bucket-scoped token
  passed upload, download, checksum and delete checks; cross-bucket denial is still unverified.
- Render DEV has not been deployed and no Render service or database has been created by this checklist.

| Phase | What | Creates cost? | Approval |
|---|---|---|---|
| A | Validate the Render Blueprints locally | No | Only to install the Render CLI on the Mac |
| B | Private R2 bucket and scoped token | Within R2's free tier for DEV | **PROVISIONED; CHECK PENDING** |
| C | Modal limits, secret, weights, endpoint | Provider usage, bounded by current $2 limits | **DEPLOYED; CHECKS PENDING** |
| D | Render import of `musicme-dev.yaml` | ≈ $20.25/month | **APPROVAL** |
| E | DEV tester account, debug APK, Samsung test | Per-song GPU only | **APPROVAL** (install) |

## Phase A — Render Blueprint validation (local, no cloud resources)

Validation answers the three points the published reference left open: the `networking.isolation` and
`permissions.protection` nesting, `fromService.envVarKey` pointing at another service's generated secret, and
`ipAllowList: []`.

**Result (21 Oct 2026): PASS for everything that can be checked without signing in to Render.**

1. Render CLI **v2.28.0** was already installed from Homebrew core (formula `render`, source
   `github.com/render-oss/cli`), which is above the 2.7.1 minimum. Nothing was installed or upgraded; v2.30.0 is
   current.
2. `render blueprints validate` is **not** offline. Its help lists "Conflict checking against existing resources" and
   a `--workspace` flag that defaults to the signed-in workspace. Run signed out (empty temporary HOME, no
   `RENDER_API_KEY`, `DO_NOT_TRACK=1`), all three files stop with *"no workspace specified and no default workspace
   set"* (exit 1) before any YAML checking. Running it means signing in to the Render workspace, which is a
   separate approval.
3. Offline substitute: Render's published JSON Schema (`https://render.com/schema/render.yaml.json`, draft 2020-12,
   SHA-256 `8a3fb8da…a494a` on 21 Oct 2026), checked with `jsonschema` 4.25.1. Result: **0 errors** for
   `musicme-dev.yaml`, `musicme-production-disabled.yaml` and `musicme-songwriter-addon.yaml`.
4. The schema is strict enough for that pass to mean something: it caught all 9 deliberate negative controls,
   including `isolation: on`, a misspelt `protection` key, an unknown environment key, an unquoted `off` (YAML
   boolean), a bogus plan, an unknown service key, a `fromService` without a name, a non-list `ipAllowList`, and
   `generation: false`. The `networking.isolation` / `permissions.protection` nesting and values are therefore
   **schema-confirmed**.
5. Local guardrail tests (also in the normal suite) pass:
   ```bash
   cd services/musicme-audio && python3 -m pytest -q tests/test_render_blueprint.py tests/test_render_blueprint_structure.py
   ```

**Still unverified** (the schema checks structure, not meaning; the CLI's semantic checks need a signed-in workspace):

- **`fromService.envVarKey` pointing at a `generateValue` secret on another service.** The schema allows it.
  Whether Render resolves it, and whether it resolves across Blueprints (the songwriter add-on is a separate project
  referring to `beatiq-musicme-dev-auth`), is unverified. Failure is fail-closed: an empty or short JWT or revocation
  secret is refused by the services' configuration, so they won't start rather than run unauthenticated.
- **`ipAllowList: []` blocking all public database access.** This is supported by Render's own CLI display text
  ("IP allow-list: empty (external connections blocked)"), but it isn't runtime-verified. Confirm it on the
  database's *Networking* page right after import, before any data exists.
- **Conflicts with existing workspace resources (name collisions or adoption).** Unverified until either the
  signed-in CLI validation (approval needed) or a careful review of the import preview in Phase D.

Optional, **APPROVAL** (signs in to Render, reads workspace metadata, creates nothing):
```bash
render login
```
```bash
DO_NOT_TRACK=1 render blueprints validate infra/render/musicme-dev.yaml --workspace <MusicMe workspace id> -o text
```

### Phase A.2 — signed-in validation (21 Oct 2026)

Setup:
- CLI v2.28.0, using its **existing** sign-in for `oxj.adekanye@gmail.com`. No new login, no credentials handled, and
  telemetry off (`DO_NOT_TRACK=1`).
- Workspace "My Workspace" (`tea-d7m8fdhkh4rs73aii650`) is the only one.
- Read-only inventory before and after: 5 projects (My project [BioAegix], BeatIQ Production, NPTTE-PharmaNG,
  Aurexus-Website, BioAegix-Staging) and 9 services/datastores. Nothing named `*musicme*`, and no changes made.

| Blueprint | Result | Plan the import would create |
|---|---|---|
| `musicme-dev.yaml` | **PASS** (valid, exit 0) | `beatiq-musicme-dev-auth`, `beatiq-musicme-audio-dev`, `beatiq-musicme-audio-dev-db` (+ the project): 4 actions |
| `musicme-production-disabled.yaml` | **PASS** (valid, exit 0) | `beatiq-musicme-audio-production-disabled`, `beatiq-musicme-audio-production-db` (+ the project): 3 actions. Do not import yet |
| `musicme-songwriter-addon.yaml` | **FAIL** (exit 1) | `MUSICME_JWT_SECRET` and `MUSICME_JWT_REVOCATION_TOKEN`: "env var depends on non-existent service: {web beatiq-musicme-dev-auth}" |

Findings:
- **No name conflicts:** the validator's conflict check reported none.
- **In-Blueprint references:** `fromService.envVarKey` to DEV auth's `generateValue` secrets resolve inside
  `musicme-dev.yaml`.
- **Cross-Blueprint reference (songwriter add-on):** unresolved until DEV auth exists. After Phase D, re-validate the
  add-on. If Render still refuses a reference across projects, move those two secrets into an environment group in
  the DEV project, or merge the add-on into the DEV Blueprint. Never copy the secret values by hand.
- **Not reported by the validator** (to be checked after import, in Phase D step 4): network isolation, environment
  protection, and the database's empty IP allow-list. In the dashboard:
  - Project → environment shows isolation and protection *enabled*.
  - Each database's *Access Control* lists no IPs.
  - From the Mac, `render psql <db id>` must be refused. It uses the external connection, so a refusal proves
    external access is blocked; no password is typed or shown.
- **Workspace scope:** the CLI token on this Mac can act on every project in the workspace (BioAegix and BeatIQ
  production included). Use only `validate` and read-only commands. Run `render logout` after deployment work if this
  Mac shouldn't keep workspace-wide access.

Note: the Blueprints live in `infra/render/`, not the repository root, so choose the file path explicitly at import
time (Phase D). Each resource must be managed by only one Blueprint.

## Phase B — Private R2 bucket for DEV songs (**PROVISIONED; ISOLATION CHECK PENDING**)

Recorded result:

- **Location:** EU jurisdiction, using `https://<ACCOUNT_ID>.eu.r2.cloudflarestorage.com`.
- **Bucket:** new private bucket `beatiq-musicme-dev`; it is not shared with production or BioAegix.
- **Credentials:** bucket-scoped Object Read & Write S3 credentials; values are held outside the repository.
- **Verification:** upload, download, checksum and delete passed. Cross-bucket denial remains unverified and must be
  completed before Render deployment.

Steps, in the Cloudflare dashboard, with the owner signed in:

1. Confirm that R2 billing is active and record the free-tier allowance shown for the account: 10 GB-month,
   1M Class A and 10M Class B operations. The DEV test needs well under 1 GB.
2. **APPROVAL:** R2 → *Create bucket*, using the name and location above.
3. Keep the bucket private:
   - *Settings → Public access*: `r2.dev` subdomain **disabled**, no custom domain.
   - No CORS rules. The Android app downloads with a plain presigned GET, not from a browser.
4. **APPROVAL:** *Lifecycle rules → Add rule*: delete objects under prefix `dev/` **35 days** after upload. This is a
   backstop just beyond the service's 30-day retention; the service still deletes on request.
5. **APPROVAL:** R2 → *Manage API tokens* → *Create API token*:
   - Prefer an **account token**; a user token stops working if that user leaves the account.
   - Permission: **Object Read & Write**, applied to **this bucket only** (not Admin, not all buckets).
   - TTL: as long as the DEV phase is planned to run, then rotate.
   - Copy the **Access Key ID** and **Secret Access Key** straight into a password manager. They are shown once.
     Record the S3 endpoint for the bucket's location.
6. **APPROVAL (one throwaway object):** confirm the token can upload, read, list **and delete**. Cloudflare's
   description of *Object Read & Write* doesn't explicitly list deletion, and the service needs it for account
   deletion and cleanup. Use any S3 client on the Mac with the token from the password manager, set through
   environment variables in that shell only:
   1. Upload a one-byte object to `dev/healthcheck/probe`.
   2. List the `dev/` prefix.
   3. Delete the object, then list again.
   4. Confirm a request to a **different** bucket is refused.

   If delete is refused, stop: either a token type that includes object deletion is chosen (and documented), or the
   design changes. Don't fall back to an account-wide Admin token.
7. Close the shell, then confirm the keys exist only in the password manager:
   ```bash
   history -c
   ```
8. **Stop/go:**
   - bucket is private (public access off, no custom domain);
   - lifecycle rule active;
   - token scoped to one bucket, with all four operations proven;
   - endpoint recorded.

Values entered later (Phase D, Render dashboard `sync: false`):

- `MUSICME_AUDIO_OBJECT_ENDPOINT` = the recorded endpoint (HTTPS, no bucket in the URL)
- `MUSICME_AUDIO_OBJECT_BUCKET` = the bucket name
- `MUSICME_AUDIO_OBJECT_ACCESS_KEY_ID` / `MUSICME_AUDIO_OBJECT_SECRET_ACCESS_KEY` = the token values

`MUSICME_AUDIO_OBJECT_PREFIX=dev`, region `auto` and path-style addressing are already set in the Blueprint.

**Rollback:**
- Delete the throwaway object if it survived.
- Revoke the token, then delete the empty bucket. Both are destructive and need their own approval.

## Phase C — Modal (**DEPLOYED; REMAINING CHECKS NOTED BELOW**)

1. Dashboard check completed 23 October 2026: workspace `oxjadekanye` has workspace **usage and spend limits of $2
   each**. Keep both limits unchanged unless the owner separately approves a change.
2. Modal SDK 1.6.2 was authenticated by the owner and the isolated `musicme-dev` environment was created:
   ```bash
   modal environment create musicme-dev
   ```
3. Generate a random worker token of at least 32 characters in the password manager. Do not print it in a terminal,
   paste it into chat or put it in a command argument.
4. In the `musicme-dev` environment, create the secret `musicme-gpu-worker-token` with key
   `MUSICME_WORKER_TOKEN` in the Modal dashboard. Never place it in a command argument, shell history or source.
   The worker volume and secret are explicitly scoped to this environment by the adapter.
5. Weight preparation completed on CPU and reported all eight pinned ACE-Step 1.5 files verified. Reference command:
   ```bash
   modal run --env musicme-dev services/musicme-audio/deploy/modal/musicme_worker.py::prepare_weights
   ```
6. Application `musicme-gpu-worker-dev` was deployed in `musicme-dev`; a dedicated Modal **proxy auth token** is
   required by the Render backend. Reference deployment command:
   ```bash
   modal deploy --env musicme-dev services/musicme-audio/deploy/modal/musicme_worker.py
   ```
7. **Recorded check:** an unauthenticated request to `/v1/health` returned HTTP 401.

   **Still to verify before Render deployment:**
   - the endpoint returns to 0 containers after the 120-second idle window;
   - the dashboard still shows no unexpected app, volume, secret, scheduled job or running container;
   - usage and spend remain below their current $2 limits.

Current public list rates are T4 `$0.000164/s`, CPU `$0.0000131/core/s`, memory `$0.00000222/GiB/s`.
The configured worker is about `$0.8126/h`; its 900-second container timeout plus a 120-second idle tail bounds one
container attempt at about `$0.23` before any regional multiplier. The 6.34-GiB volume lists at about `$0.57/month`,
but Modal's current Starter pricing says volumes include up to 1 TiB free. Treat applicability to this workspace as a
dashboard verification item; if it does not apply, retained storage can outlive the $2 compute limits.

## Phase D — Render import (**APPROVAL**, ≈ $20.25/month)

**Status: NOT DEPLOYED.** Do not start this phase until the remaining predeployment findings are closed and the owner
approves the Render cost and resource creation.

1. In Render Billing, record the plan, credits and current spend. Make a read-only inventory of names and confirm no
   `beatiq-musicme*` resource exists.
2. *New → Blueprint*, choosing this repository and the path `infra/render/musicme-dev.yaml` only. In the preview,
   confirm:
   - exactly 2 web services (`0.5c-512mb`), 1 Postgres (`0.1c-256mb`, 1 GB) and one 1 GB disk;
   - nothing matched or adopted from an existing app.
3. Enter every `sync: false` value: the R2 values from Phase B, plus:
   - `MUSICME_AUDIO_PUBLIC_BASE_URL` (the audio service's HTTPS URL);
   - `MUSICME_AUDIO_JWT_REVOCATION_URL` = `https://<dev-auth host>/internal/revocation`;
   - `MUSICME_AUDIO_WORKER_URL` (the Modal URL);
   - `MUSICME_AUDIO_WORKER_CREDENTIALS_JSON`: the worker token plus the Modal `Modal-Key`/`Modal-Secret` headers.
4. **Stop/go:**
   - DEV auth healthy on `/healthz` and the audio service healthy on `/readyz`;
   - previews and auto-deploy off, one instance each, project protection and network isolation on;
   - database public IP list empty;
   - logs show `storage=s3` and no secrets.

## Phase E — Samsung DEV test (**APPROVAL** for the account and the APK install)

1. In a Render shell on DEV auth, create the tester (the password is shown once; keep it in a password manager):
   ```bash
   python3 devauth.py create-user --email tester@dev.local
   ```
2. Put only the DEV public URLs in the untracked `local.properties` (`beatiq.api.base.url`, `musicme.audio.url`).
   Then build the debug APK and install it on the Samsung (`adb install -r`).
3. Run the checklist in `docs/MUSICME_CLOUD_OPERATIONS.md` §8 steps 12–13:
   - synthetic 30 s lyrics;
   - one cancellation, then one completion;
   - playback, restart persistence, download, delete;
   - another account gets 404; revocation works; logs are redacted.

   Record cold start, render time and billed seconds, and confirm Modal returns to 0 containers.

**Stop conditions at any phase:** any secret displayed outside the password manager or provider dashboards, any
unexpected resource in a preview, any spend alert, or any check above failing.
