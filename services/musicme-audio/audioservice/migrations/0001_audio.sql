-- MusicMe audio generation jobs and stored audio objects. Portable SQL: runs unchanged on SQLite (development)
-- and PostgreSQL. Timestamps are epoch milliseconds (BIGINT). Flags are 0/1 integers. Table names are prefixed
-- audio_ so they can never collide with the songwriting service's tables, even if pointed at the same server.
CREATE TABLE audio_jobs (
    id                 TEXT PRIMARY KEY,
    account            TEXT NOT NULL,
    idempotency_key    TEXT NOT NULL,
    body_hash          TEXT NOT NULL,
    spec_json          TEXT,                       -- lyrics and style; purged when the job reaches a terminal state
    state              TEXT NOT NULL CHECK (state IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    stage              TEXT NOT NULL DEFAULT 'queued',
    progress           INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    takes              INTEGER NOT NULL DEFAULT 1,
    attempts           INTEGER NOT NULL DEFAULT 0,
    max_attempts       INTEGER NOT NULL,
    next_attempt_at    BIGINT NOT NULL,
    lease_owner        TEXT,
    lease_expires_at   BIGINT,
    cancel_requested   INTEGER NOT NULL DEFAULT 0 CHECK (cancel_requested IN (0, 1)),
    seed               BIGINT NOT NULL,
    result_json        TEXT,                       -- track ids and generation metadata only; never lyrics
    error_code         TEXT,
    error_message      TEXT,
    engine             TEXT,
    model_id           TEXT,
    model_version      TEXT,
    model_revision     TEXT,
    interface_version  TEXT,
    params_json        TEXT,                       -- render parameters without lyrics (reproducibility)
    render_ms          INTEGER,
    created_at         BIGINT NOT NULL,
    updated_at         BIGINT NOT NULL,
    finished_at        BIGINT,
    CONSTRAINT audio_jobs_account_idempotency UNIQUE (account, idempotency_key),
    CONSTRAINT audio_jobs_result_only_when_succeeded CHECK (result_json IS NULL OR state = 'succeeded')
);

CREATE INDEX audio_jobs_claimable ON audio_jobs (state, next_attempt_at, created_at);
CREATE INDEX audio_jobs_account_created ON audio_jobs (account, created_at);
CREATE INDEX audio_jobs_lease ON audio_jobs (state, lease_expires_at);
CREATE INDEX audio_jobs_finished ON audio_jobs (finished_at);

-- One row per stored file. The row is written before the file, so a crash always leaves a row that cleanup can
-- find. Only 'committed' objects can be downloaded; 'staging' objects belong to a render still in progress.
CREATE TABLE audio_objects (
    id                 TEXT PRIMARY KEY,
    account            TEXT NOT NULL,
    job_id             TEXT NOT NULL,
    take               INTEGER NOT NULL,
    storage_key        TEXT NOT NULL UNIQUE,       -- server-generated; contains no user input
    format             TEXT NOT NULL CHECK (format IN ('wav', 'm4a', 'mp3')),
    content_type       TEXT NOT NULL,
    size_bytes         BIGINT,
    sha256             TEXT,
    duration_ms        INTEGER,
    state              TEXT NOT NULL CHECK (state IN ('staging', 'committed')),
    created_at         BIGINT NOT NULL,
    committed_at       BIGINT,
    expires_at         BIGINT
);

CREATE INDEX audio_objects_account ON audio_objects (account, state);
CREATE INDEX audio_objects_job ON audio_objects (job_id);
CREATE INDEX audio_objects_expiry ON audio_objects (state, expires_at);
