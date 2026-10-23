-- Durable, conservative DEV GPU spend accounting. A row is retained per UTC day for operational audit.
CREATE TABLE audio_gpu_daily_usage (
    day          TEXT PRIMARY KEY,
    renders      INTEGER NOT NULL CHECK (renders >= 0),
    gpu_seconds  REAL NOT NULL CHECK (gpu_seconds >= 0),
    usd          REAL NOT NULL CHECK (usd >= 0),
    updated_at   BIGINT NOT NULL
);
