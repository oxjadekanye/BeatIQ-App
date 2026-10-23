-- Production fal.ai request recovery, conservative spend reservations and user-owned song metadata.
-- Provider request ids are persisted before polling so a restarted worker resumes the same paid request.
ALTER TABLE audio_jobs ADD COLUMN provider_request_id TEXT;
ALTER TABLE audio_jobs ADD COLUMN provider_endpoint TEXT;

CREATE UNIQUE INDEX audio_jobs_provider_request ON audio_jobs (provider_request_id);

CREATE TABLE audio_fal_daily_usage (
    day                    TEXT PRIMARY KEY,
    requests               INTEGER NOT NULL CHECK (requests >= 0),
    output_seconds         REAL NOT NULL CHECK (output_seconds >= 0),
    usd                    REAL NOT NULL CHECK (usd >= 0),
    updated_at             BIGINT NOT NULL
);

CREATE TABLE audio_fal_reservations (
    job_id                 TEXT PRIMARY KEY,
    account                TEXT NOT NULL,
    day                    TEXT NOT NULL,
    reserved_seconds       REAL NOT NULL CHECK (reserved_seconds > 0),
    reserved_usd           REAL NOT NULL CHECK (reserved_usd > 0),
    settled_seconds        REAL,
    settled_usd            REAL,
    state                  TEXT NOT NULL CHECK (state IN ('reserved', 'settled')),
    created_at             BIGINT NOT NULL,
    updated_at             BIGINT NOT NULL
);

CREATE INDEX audio_fal_reservations_day ON audio_fal_reservations (day, state);
CREATE INDEX audio_fal_reservations_account ON audio_fal_reservations (account, day);

CREATE TABLE audio_song_metadata (
    track_id               TEXT PRIMARY KEY,
    account                TEXT NOT NULL,
    title                  TEXT NOT NULL,
    favorite               INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
    saved                  INTEGER NOT NULL DEFAULT 0 CHECK (saved IN (0, 1)),
    created_at             BIGINT NOT NULL,
    updated_at             BIGINT NOT NULL
);

CREATE INDEX audio_song_metadata_account ON audio_song_metadata (account, created_at);
