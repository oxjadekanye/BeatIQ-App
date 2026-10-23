"""Durable, account-scoped audio jobs and their stored objects (pattern adapted from musicme-songwriter).

Job states (enforced by conditional UPDATEs, so concurrent workers and API calls can't corrupt them):

    queued ──claim──► running ──complete──► succeeded
       ▲                 │ └──fail─────────► failed
       └──retry/backoff──┘ └──lease expired─► queued (attempts left) | failed WORKER_LOST
    queued | running ──cancel──► cancelled          (a cancelled job can never publish audio)
    queued (too old) ──expire──► failed EXPIRED

Within 'running', stage/progress report rendering → validating → storing. Heartbeats renew the lease. Every worker
write (heartbeat, staging, publish, retry, fail) requires an unexpired lease held by that worker, so a worker whose
lease expired can never publish, even before the recovery sweep has run.

Objects: a row is inserted ('staging') before its file is written, and flips to 'committed' in the same transaction
that marks the job succeeded. Only committed objects can be downloaded. Cleanup removes staging objects of jobs
that are no longer running, expired tracks, old job rows and orphan files.
"""
import hashlib
import json
import logging
import random
import secrets
import time
import uuid

from .auth import account_hash
from .contract import MAX_SEED
from .errors import ServiceError
from .storage import FORMATS, new_storage_key

log = logging.getLogger("musicme.audio")

TERMINAL = ("succeeded", "failed", "cancelled")
_ACTIVE = "('queued', 'running')"
_REFUNDED = "('failed', 'cancelled')"  # don't count against the account's song quota


class ClaimedJob:
    def __init__(self, job_id, account, spec, seed, takes, attempts, max_attempts):
        self.id = job_id
        self.account = account
        self.spec = spec
        self.seed = seed
        self.takes = takes
        self.attempts = attempts
        self.max_attempts = max_attempts


class JobStore:
    def __init__(self, db, settings, clock=None, rng=random.random):
        self.db = db
        self.s = settings
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.rng = rng

    # -- API side ----------------------------------------------------------------------------

    def submit(self, account, idempotency_key, body_bytes, spec):
        """Returns (public_job, created). Idempotent per (account, key), across restarts."""
        body_hash = hashlib.sha256(body_bytes).hexdigest()
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            if self.db.dialect == "postgres":
                tx.execute("SELECT pg_advisory_xact_lock(hashtext(?))", ("audio:" + account,))  # serialise one account's submits
            existing = tx.fetchone("SELECT id, body_hash FROM audio_jobs WHERE account = ? AND idempotency_key = ?", (account, idempotency_key))
            if existing:
                if existing[1] != body_hash:
                    raise ServiceError(409, "INVALID_REQUEST", "This request id was already used for a different song.")
                return self._public(tx, existing[0]), False

            window_start = now - self.s.rate_window_seconds * 1000
            recent = tx.fetchone(
                f"SELECT COUNT(*), MIN(created_at) FROM audio_jobs WHERE account = ? AND created_at > ? AND state NOT IN {_REFUNDED}",
                (account, window_start))
            if recent[0] >= self.s.max_jobs_per_window:
                retry = max(1, (recent[1] + self.s.rate_window_seconds * 1000 - now) // 1000 + 1)
                raise ServiceError(429, "RATE_LIMITED", "You've reached today's song limit.", retry_after=int(retry))
            active = tx.fetchone(f"SELECT COUNT(*) FROM audio_jobs WHERE account = ? AND state IN {_ACTIVE}", (account,))[0]
            if active >= self.s.max_active_jobs_per_account:
                raise ServiceError(429, "RATE_LIMITED", "Wait for your current song to finish, or cancel it.", retry_after=30)
            if self._used_bytes(tx, account) >= self.s.account_storage_quota_bytes:
                raise ServiceError(429, "STORAGE_QUOTA_EXCEEDED", "Your song storage is full. Delete older songs to make room.")
            queued = tx.fetchone("SELECT COUNT(*) FROM audio_jobs WHERE state = 'queued'")[0]
            if queued >= self.s.max_queued_jobs:
                raise ServiceError(503, "UNAVAILABLE", "MusicMe is busy creating other songs. Please try again shortly.", retry_after=60)

            job_id = str(uuid.uuid4())
            seed = spec["seed"] if spec.get("seed") is not None else secrets.randbelow(MAX_SEED)
            inserted = tx.fetchone(
                "INSERT INTO audio_jobs (id, account, idempotency_key, body_hash, spec_json, state, stage, progress, takes, "
                "attempts, max_attempts, next_attempt_at, seed, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, 'queued', 'queued', 0, ?, 0, ?, ?, ?, ?, ?) "
                "ON CONFLICT (account, idempotency_key) DO NOTHING RETURNING id",
                (job_id, account, idempotency_key, body_hash, json.dumps(spec), spec["takes"], self.s.max_attempts, now, seed, now, now),
            )
            if inserted is None:  # lost a race with an identical submission
                row = tx.fetchone("SELECT id, body_hash FROM audio_jobs WHERE account = ? AND idempotency_key = ?", (account, idempotency_key))
                if row[1] != body_hash:
                    raise ServiceError(409, "INVALID_REQUEST", "This request id was already used for a different song.")
                return self._public(tx, row[0]), False
            job = self._public(tx, job_id)
        log.info("job_queued account=%s job=%s", account_hash(account), job_id[:8])
        return job, True

    def get(self, account, job_id):
        with self.db.transaction() as tx:
            return self._owned(tx, account, job_id)

    def cancel(self, account, job_id):
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            tx.execute(
                "UPDATE audio_jobs SET state = 'cancelled', stage = 'cancelled', cancel_requested = 1, result_json = NULL, "
                "spec_json = NULL, lease_owner = NULL, lease_expires_at = NULL, finished_at = ?, updated_at = ? "
                f"WHERE id = ? AND account = ? AND state IN {_ACTIVE}",
                (now, now, job_id, account),
            )
            return self._owned(tx, account, job_id)

    def track_for_link(self, account, track_id):
        """The committed, unexpired object owned by this account, else 404 (another account's track looks missing)."""
        now = self.clock()
        with self.db.transaction() as tx:
            row = tx.fetchone(
                "SELECT id, account, storage_key, format, content_type, size_bytes FROM audio_objects "
                "WHERE id = ? AND state = 'committed' AND (expires_at IS NULL OR expires_at > ?)", (track_id, now))
        if row is None or row[1] != account:
            raise ServiceError(404, "TRACK_NOT_FOUND", "This song is no longer available.")
        return {"id": row[0], "storage_key": row[2], "format": row[3], "content_type": row[4], "size_bytes": row[5]}

    def track_for_download(self, track_id):
        now = self.clock()
        with self.db.transaction() as tx:
            row = tx.fetchone(
                "SELECT id, account, storage_key, format, content_type, size_bytes FROM audio_objects "
                "WHERE id = ? AND state = 'committed' AND (expires_at IS NULL OR expires_at > ?)", (track_id, now))
        if row is None:
            return None
        return {"id": row[0], "account": row[1], "storage_key": row[2], "format": row[3], "content_type": row[4], "size_bytes": row[5]}

    def delete_track(self, account, track_id):
        """Removes the row and returns its storage key (the caller deletes the file). 404 if not this account's."""
        with self.db.transaction(exclusive=True) as tx:
            row = tx.fetchone("SELECT account, storage_key FROM audio_objects WHERE id = ? AND state = 'committed'", (track_id,))
            if row is None or row[0] != account:
                raise ServiceError(404, "TRACK_NOT_FOUND", "This song is no longer available.")
            tx.execute("DELETE FROM audio_objects WHERE id = ?", (track_id,))
        return row[1]

    def delete_account_data(self, account):
        """Cancels active jobs and removes every row for the account. Returns storage keys to delete."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            tx.execute(
                "UPDATE audio_jobs SET state = 'cancelled', cancel_requested = 1, spec_json = NULL, lease_owner = NULL, "
                f"lease_expires_at = NULL, finished_at = ?, updated_at = ? WHERE account = ? AND state IN {_ACTIVE}",
                (now, now, account))
            keys = [r[0] for r in tx.fetchall("SELECT storage_key FROM audio_objects WHERE account = ?", (account,))]
            tx.execute("DELETE FROM audio_objects WHERE account = ?", (account,))
            jobs = tx.execute("DELETE FROM audio_jobs WHERE account = ?", (account,)).rowcount
        log.info("account_audio_deleted account=%s objects=%d jobs=%d", account_hash(account), len(keys), jobs)
        return keys, jobs

    def usage(self, account):
        with self.db.transaction() as tx:
            return {"used_bytes": self._used_bytes(tx, account), "quota_bytes": self.s.account_storage_quota_bytes}

    # -- worker side ---------------------------------------------------------------------------

    def claim(self, worker_id):
        now = self.clock()
        lease_until = now + self.s.effective_lease_seconds * 1000
        lock = " FOR UPDATE SKIP LOCKED" if self.db.dialect == "postgres" else ""
        with self.db.transaction(exclusive=True) as tx:
            row = tx.fetchone(
                # The subquery locks one claimable row (other workers skip it); the outer conditions are
                # re-checked against the locked row, so a row that changed meanwhile is never claimed.
                "UPDATE audio_jobs SET state = 'running', stage = 'rendering', progress = 0, lease_owner = ?, "
                "lease_expires_at = ?, attempts = attempts + 1, updated_at = ? "
                "WHERE id = (SELECT id FROM audio_jobs WHERE state = 'queued' AND cancel_requested = 0 AND next_attempt_at <= ? "
                f"ORDER BY next_attempt_at, created_at LIMIT 1{lock}) "
                "AND state = 'queued' AND cancel_requested = 0 "
                "RETURNING id, account, spec_json, seed, takes, attempts, max_attempts",
                (worker_id, lease_until, now, now),
            )
        if row is None:
            return None
        return ClaimedJob(row[0], row[1], json.loads(row[2]), row[3], row[4], row[5], row[6])

    def heartbeat(self, job_id, worker_id, stage, progress):
        """Renews the lease and records progress. False means stop: cancelled, lease lost or job gone."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            cursor = tx.execute(
                "UPDATE audio_jobs SET stage = ?, progress = ?, lease_expires_at = ?, updated_at = ? "
                "WHERE id = ? AND state = 'running' AND lease_owner = ? AND lease_expires_at >= ? AND cancel_requested = 0",
                (stage, max(0, min(99, int(progress))), now + self.s.effective_lease_seconds * 1000, now, job_id, worker_id, now),
            )
            return cursor.rowcount == 1

    def release(self, job_id, worker_id):
        """Hands a job back to the queue at once during a clean shutdown, without using up an attempt.

        Only a job this worker still leases and nobody asked to cancel is released; otherwise nothing changes (a user
        cancellation stays cancelled, and a lease another worker has taken over stays with that worker)."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            cursor = tx.execute(
                "UPDATE audio_jobs SET state = 'queued', stage = 'queued', progress = 0, lease_owner = NULL, "
                "lease_expires_at = NULL, attempts = CASE WHEN attempts > 0 THEN attempts - 1 ELSE 0 END, "
                "next_attempt_at = ?, updated_at = ? "
                "WHERE id = ? AND state = 'running' AND lease_owner = ? AND cancel_requested = 0",
                (now, now, job_id, worker_id),
            )
            return cursor.rowcount == 1

    def add_staging_object(self, job_id, worker_id, account, take, fmt):
        """Registers a file about to be written. Returns (object_id, storage_key), or None if the job isn't ours."""
        object_id = str(uuid.uuid4())
        key = new_storage_key(account, object_id, fmt)
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            owned = tx.fetchone(
                "SELECT 1 FROM audio_jobs WHERE id = ? AND account = ? AND state = 'running' AND lease_owner = ? "
                "AND lease_expires_at >= ? AND cancel_requested = 0",
                (job_id, account, worker_id, now))
            if owned is None:
                return None
            tx.execute(
                "INSERT INTO audio_objects (id, account, job_id, take, storage_key, format, content_type, state, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'staging', ?)",
                (object_id, account, job_id, take, key, fmt, FORMATS[fmt], now))
        return object_id, key

    def record_object(self, object_id, size_bytes, sha256, duration_ms):
        with self.db.transaction(exclusive=True) as tx:
            tx.execute("UPDATE audio_objects SET size_bytes = ?, sha256 = ?, duration_ms = ? WHERE id = ? AND state = 'staging'",
                       (size_bytes, sha256, duration_ms, object_id))

    def staging_objects(self, job_id):
        with self.db.transaction() as tx:
            return tx.fetchall("SELECT id, storage_key FROM audio_objects WHERE job_id = ? AND state = 'staging'", (job_id,))

    def delete_object_rows(self, object_ids):
        if not object_ids:
            return
        with self.db.transaction(exclusive=True) as tx:
            for object_id in object_ids:
                tx.execute("DELETE FROM audio_objects WHERE id = ? AND state = 'staging'", (object_id,))

    def complete(self, job_id, worker_id, account, result, meta):
        """Publishes the job and commits its objects atomically. Returns "ok", "discarded" (cancelled, lease lost
        or gone: nothing is published) or "quota" (the account's storage quota would be exceeded)."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            owned = tx.fetchone(
                "SELECT 1 FROM audio_jobs WHERE id = ? AND state = 'running' AND lease_owner = ? AND lease_expires_at >= ? "
                "AND cancel_requested = 0", (job_id, worker_id, now))
            if owned is None:
                return "discarded"
            new_bytes = tx.fetchone("SELECT COALESCE(SUM(size_bytes), 0) FROM audio_objects WHERE job_id = ? AND state = 'staging'", (job_id,))[0]
            if self._used_bytes(tx, account) + new_bytes > self.s.account_storage_quota_bytes:
                return "quota"
            tx.execute(
                "UPDATE audio_objects SET state = 'committed', committed_at = ?, expires_at = ? WHERE job_id = ? AND state = 'staging'",
                (now, now + self.s.track_ttl_seconds * 1000, job_id))
            tx.execute(
                "UPDATE audio_jobs SET state = 'succeeded', stage = 'done', progress = 100, result_json = ?, spec_json = NULL, "
                "error_code = NULL, error_message = NULL, lease_owner = NULL, lease_expires_at = NULL, engine = ?, model_id = ?, "
                "model_version = ?, model_revision = ?, interface_version = ?, params_json = ?, render_ms = ?, finished_at = ?, "
                "updated_at = ? WHERE id = ? AND state = 'running' AND lease_owner = ? AND lease_expires_at >= ? AND cancel_requested = 0",
                (json.dumps(result), meta["engine"], meta["model_id"], meta["model_version"], meta["model_revision"],
                 meta["interface_version"], json.dumps(meta["parameters"]), meta["render_ms"], now, now, job_id, worker_id, now))
        return "ok"

    def retry_or_fail(self, job_id, worker_id, code, message):
        """After a transient failure: requeue with backoff (the next attempt uses a new seed), or fail."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            row = tx.fetchone("SELECT attempts, max_attempts FROM audio_jobs WHERE id = ? AND state = 'running' AND lease_owner = ? "
                              "AND lease_expires_at >= ?", (job_id, worker_id, now))
            if row is None:
                return None
            if row[0] < row[1]:
                tx.execute(
                    "UPDATE audio_jobs SET state = 'queued', stage = 'queued', progress = 0, lease_owner = NULL, "
                    "lease_expires_at = NULL, next_attempt_at = ?, error_code = ?, error_message = ?, updated_at = ? "
                    "WHERE id = ? AND state = 'running' AND lease_owner = ?",
                    (now + self.backoff_ms(row[0]), code, message, now, job_id, worker_id),
                )
                return "queued"
        self.fail(job_id, worker_id, code, message)
        return "failed"

    def fail(self, job_id, worker_id, code, message):
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            cursor = tx.execute(
                "UPDATE audio_jobs SET state = 'failed', stage = 'failed', spec_json = NULL, result_json = NULL, error_code = ?, "
                "error_message = ?, lease_owner = NULL, lease_expires_at = NULL, finished_at = ?, updated_at = ? "
                "WHERE id = ? AND state = 'running' AND lease_owner = ? AND lease_expires_at >= ?",
                (code, message, now, now, job_id, worker_id, now),
            )
            return cursor.rowcount == 1

    def backoff_ms(self, attempts):
        base = min(self.s.backoff_base_seconds * 1000 * (2 ** max(0, attempts - 1)), self.s.backoff_max_seconds * 1000)
        return int(base * (0.5 + self.rng() / 2))  # jitter avoids synchronised retries

    # -- maintenance -------------------------------------------------------------------------

    def recover_expired_leases(self):
        """Requeues jobs whose worker died (lease expired); fails them once attempts are exhausted."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            expired = tx.fetchall(
                "SELECT id, attempts, max_attempts FROM audio_jobs WHERE state = 'running' AND lease_expires_at < ?", (now,))
            for job_id, attempts, max_attempts in expired:
                if attempts < max_attempts:
                    tx.execute(
                        "UPDATE audio_jobs SET state = 'queued', stage = 'queued', progress = 0, lease_owner = NULL, "
                        "lease_expires_at = NULL, next_attempt_at = ?, error_code = 'WORKER_LOST', updated_at = ? "
                        "WHERE id = ? AND state = 'running' AND lease_expires_at < ?",
                        (now + self.backoff_ms(attempts), now, job_id, now),
                    )
                else:
                    tx.execute(
                        "UPDATE audio_jobs SET state = 'failed', stage = 'failed', spec_json = NULL, lease_owner = NULL, "
                        "lease_expires_at = NULL, error_code = 'WORKER_LOST', error_message = ?, finished_at = ?, updated_at = ? "
                        "WHERE id = ? AND state = 'running' AND lease_expires_at < ?",
                        ("MusicMe couldn't finish this song. Please try again.", now, now, job_id, now),
                    )
        if expired:
            log.info("leases_recovered count=%d", len(expired))
        return len(expired)

    def cleanup(self, storage):
        """Expires stale queued jobs, deletes expired tracks, abandoned staging files, old job rows and orphans."""
        now = self.clock()
        with self.db.transaction(exclusive=True) as tx:
            stale = tx.execute(
                "UPDATE audio_jobs SET state = 'failed', stage = 'failed', spec_json = NULL, error_code = 'EXPIRED', "
                "error_message = ?, finished_at = ?, updated_at = ? WHERE state = 'queued' AND created_at < ?",
                ("The request waited too long and expired. Please try again.", now, now, now - self.s.queue_ttl_seconds * 1000),
            ).rowcount
            doomed = tx.fetchall(
                "SELECT o.id, o.storage_key FROM audio_objects o LEFT JOIN audio_jobs j ON j.id = o.job_id "
                "WHERE (o.state = 'committed' AND o.expires_at <= ?) "
                "OR (o.state = 'staging' AND (j.id IS NULL OR j.state <> 'running' OR o.created_at < ?))",
                (now, now - self.s.staging_ttl_seconds * 1000))
            jobs = tx.execute("DELETE FROM audio_jobs WHERE state IN ('succeeded', 'failed', 'cancelled') AND finished_at < ?",
                              (now - self.s.job_ttl_seconds * 1000,)).rowcount
        for _, key in doomed:  # files first: a crash here leaves rows that the next sweep retries
            storage.delete(key)
        with self.db.transaction(exclusive=True) as tx:
            for object_id, _ in doomed:
                tx.execute("DELETE FROM audio_objects WHERE id = ?", (object_id,))
            known = {r[0] for r in tx.fetchall("SELECT storage_key FROM audio_objects")}
        orphans = storage.sweep_orphans(known, self.s.staging_ttl_seconds, now=now / 1000)
        if stale or doomed or jobs or orphans:
            log.info("cleanup expired=%d objects=%d jobs=%d orphans=%d", stale, len(doomed), jobs, orphans)
        return {"expired": stale, "objects_deleted": len(doomed), "jobs_deleted": jobs, "orphans_deleted": orphans}

    def counts(self):
        with self.db.transaction() as tx:
            return dict(tx.fetchall("SELECT state, COUNT(*) FROM audio_jobs GROUP BY state"))

    # -- helpers ---------------------------------------------------------------------------

    def _used_bytes(self, tx, account):
        return tx.fetchone("SELECT COALESCE(SUM(size_bytes), 0) FROM audio_objects WHERE account = ? AND state = 'committed'", (account,))[0]

    def _owned(self, tx, account, job_id):
        row = tx.fetchone("SELECT account FROM audio_jobs WHERE id = ?", (job_id,))
        # Another account's job is indistinguishable from a missing one.
        if row is None or row[0] != account:
            raise ServiceError(404, "JOB_NOT_FOUND", "This song request is no longer available.")
        return self._public(tx, job_id)

    def _public(self, tx, job_id):
        row = tx.fetchone(
            "SELECT id, state, stage, progress, result_json, error_code, error_message, attempts, created_at, next_attempt_at "
            "FROM audio_jobs WHERE id = ?", (job_id,))
        job_id, state, stage, progress, result_json, error_code, error_message, attempts, created_at, next_attempt_at = row
        job = {
            "id": job_id,
            "state": state,
            "stage": stage,
            "progress": progress,
            "queue_position": None,
            "attempts": attempts,
            # Only terminal failures are reported; a queued retry is still in progress.
            "error": {"code": error_code, "message": error_message} if state == "failed" else None,
            "result": None,
        }
        if state == "queued":
            job["queue_position"] = tx.fetchone(
                "SELECT COUNT(*) FROM audio_jobs WHERE state = 'queued' AND (next_attempt_at < ? OR (next_attempt_at = ? AND created_at <= ?))",
                (next_attempt_at, next_attempt_at, created_at))[0]
        if state == "succeeded" and result_json:
            result = json.loads(result_json)
            live = {r[0]: r[1] for r in tx.fetchall(
                "SELECT id, expires_at FROM audio_objects WHERE job_id = ? AND state = 'committed' AND (expires_at IS NULL OR expires_at > ?)",
                (job_id, self.clock()))}
            for track in result.get("tracks", []):
                track["available"] = track["id"] in live
                track["expires_at"] = live.get(track["id"])
            job["result"] = result
        return job
