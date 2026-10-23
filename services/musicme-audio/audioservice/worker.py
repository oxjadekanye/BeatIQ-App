"""Workers: claim a durable job, render each take, validate it, store it privately and publish atomically.

Logs carry job-id prefixes, account hashes, codes and timings only: never lyrics, captions, tokens or storage keys.
"""
import logging
import os
import threading
import time
import uuid

from .contract import MAX_SEED
from .engines import Cancelled, EngineError, build_render_request
from .storage import FORMATS, StorageError
from .validation import AudioValidationError, validate_wav

log = logging.getLogger("musicme.audio")

FRIENDLY = {
    "AUDIO_INVALID": "The song couldn't be created this time. Please try again.",
    "AUDIO_SILENT": "The song couldn't be created this time. Please try again.",
    "AUDIO_CLIPPED": "The song couldn't be created this time. Please try again.",
    "AUDIO_DURATION": "The song couldn't be created at the requested length. Please try again.",
    "INVALID_CONTENT": "The song couldn't be created this time. Please try again.",
    "INVALID_SIZE": "The song couldn't be created this time. Please try again.",
    "STORAGE_UNAVAILABLE": "Song storage is temporarily unavailable. Please try again.",
    "STORAGE_INTEGRITY": "Song storage could not verify the file. Please try again.",
    "PROVIDER_OUTPUT_INVALID": "The music provider returned an unreadable audio file.",
}


def take_seed(base_seed, attempt, take):
    """Each take gets its own seed and each retry a fresh one, all reproducible from the stored base seed."""
    return (base_seed + (attempt - 1) * 7919 + take * 104729) % MAX_SEED


class WorkerPool:
    def __init__(self, store, engine, storage, settings):
        self.store = store
        self.engine = engine
        self.storage = storage
        self.s = settings
        self._stop = threading.Event()
        self._threads = []

    def start(self):
        for index in range(self.s.workers):
            worker_id = f"audio-worker-{uuid.uuid4().hex[:8]}-{index}"
            thread = threading.Thread(target=self._loop, args=(worker_id,), name=worker_id, daemon=True)
            thread.start()
            self._threads.append(thread)
        thread = threading.Thread(target=self._maintenance_loop, name="audio-maintenance", daemon=True)
        thread.start()
        self._threads.append(thread)

    def stop(self, timeout=5):
        self.request_stop()
        self.join(timeout)

    def request_stop(self):
        """Prevent the pool from claiming another job before engine shutdown/cancellation begins."""
        self._stop.set()

    def join(self, timeout=5):
        for thread in self._threads:
            thread.join(timeout)

    def _loop(self, worker_id):
        while not self._stop.is_set():
            try:
                claimed = self.store.claim(worker_id)
            except Exception as error:
                log.error("claim_failed type=%s", type(error).__name__)
                claimed = None
            if claimed is None:
                self._stop.wait(self.s.worker_poll_seconds)
                continue
            self.process(claimed, worker_id)

    def process(self, claimed, worker_id):
        job_id = claimed.id
        deadline = time.monotonic() + self.s.render_timeout_seconds
        temps = []
        try:
            tracks, parameters, render_ms, engine_details = [], None, 0, {}
            for take in range(claimed.takes):
                base = 100 * take / claimed.takes
                share = 100 / claimed.takes

                def progress(fraction, base=base, share=share):
                    return self.store.heartbeat(job_id, worker_id, "rendering", base + share * 0.8 * max(0.0, min(1.0, fraction)))

                if not progress(0):
                    raise Cancelled()
                registered = self.store.add_staging_object(job_id, worker_id, claimed.account, take + 1, self.engine.output_format)
                if registered is None:
                    raise Cancelled()
                object_id, key = registered
                temp = self.storage.temp_path(key)
                temps.append(temp)
                request = build_render_request(
                    claimed.spec, take_seed(claimed.seed, claimed.attempts, take),
                    job_id=job_id, account=claimed.account, worker_id=worker_id,
                    provider_request_id=claimed.provider_request_id, provider_endpoint=claimed.provider_endpoint,
                )
                result = self.engine.render(request, temp, progress, deadline)
                render_ms += result.render_ms
                engine_details = dict(result.engine_parameters or {})  # model diagnostics only; never lyrics
                if not self.store.heartbeat(job_id, worker_id, "validating", base + share * 0.85):
                    raise Cancelled()
                info = validate_wav(temp, claimed.spec["target_duration_seconds"], self.s.duration_tolerance, self.s.max_audio_bytes)
                if hasattr(self.engine, "settle_cost"):
                    self.engine.settle_cost(job_id, info["duration_ms"] / 1000.0, claimed.spec["target_duration_seconds"])
                if not self.store.heartbeat(job_id, worker_id, "storing", base + share * 0.95):
                    raise Cancelled()
                if result.format != self.engine.output_format:
                    raise StorageError("INVALID_CONTENT", "The engine returned an unexpected format.")
                size, sha256 = self.storage.commit_file(temp, key, result.format)
                self.store.record_object(object_id, size, sha256, info["duration_ms"])
                parameters = parameters or {k: v for k, v in request.parameters().items() if k != "seed"}
                tracks.append({
                    "id": object_id, "take": take + 1, "format": result.format,
                    "content_type": FORMATS[result.format], "size_bytes": size, "duration_ms": info["duration_ms"],
                    "sample_rate": info["sample_rate"], "channels": info["channels"], "sha256": sha256, "seed": request.seed,
                })
            generation = {
                "engine": self.engine.kind, "model_id": self.engine.model_id, "model_version": self.engine.model_version,
                "model_revision": self.engine.model_revision, "interface_version": self.engine.interface_version,
                "mock": self.engine.is_mock, "real_audio": not self.engine.is_mock, "seed": claimed.seed,
                "attempt": claimed.attempts, "parameters": parameters, "render_ms": render_ms,
                "engine_details": engine_details,
            }
            outcome = self.store.complete(job_id, worker_id, claimed.account,
                                          {"title": claimed.spec.get("title", ""), "tracks": tracks, "generation": generation},
                                          {**generation, "parameters": parameters})
            if outcome == "ok":
                log.info("job_succeeded job=%s takes=%d ms=%d attempt=%d", job_id[:8], len(tracks), render_ms, claimed.attempts)
                return
            self._discard_staging(job_id)
            if outcome == "quota":
                self.store.fail(job_id, worker_id, "STORAGE_QUOTA_EXCEEDED", "Your song storage is full. Delete older songs to make room.")
                log.info("job_failed job=%s code=STORAGE_QUOTA_EXCEEDED", job_id[:8])
            else:
                log.info("result_discarded job=%s", job_id[:8])  # cancelled or lease lost: never published
        except Cancelled:
            self._discard_staging(job_id)
            if self._stop.is_set() and self.store.release(job_id, worker_id):
                log.info("job_released job=%s", job_id[:8])  # shutdown: another worker picks it up immediately
            else:
                log.info("job_cancelled job=%s", job_id[:8])
        except (AudioValidationError, StorageError) as error:
            # A bad render is worth one more try with a fresh seed; then the job fails with a friendly message.
            self._discard_staging(job_id)
            state = self.store.retry_or_fail(job_id, worker_id, error.code, FRIENDLY.get(error.code, FRIENDLY["AUDIO_INVALID"]))
            log.info("output_rejected job=%s code=%s state=%s", job_id[:8], error.code, state)
        except EngineError as error:
            self._discard_staging(job_id)
            if error.transient:
                state = self.store.retry_or_fail(job_id, worker_id, error.code, error.message)
                log.info("job_retry_or_fail job=%s code=%s state=%s", job_id[:8], error.code, state)
            else:
                self.store.fail(job_id, worker_id, error.code, error.message)
                log.info("job_failed job=%s code=%s", job_id[:8], error.code)
        except Exception as error:  # never log messages or tracebacks: they can contain user content
            self._discard_staging(job_id)
            log.error("job_crashed job=%s type=%s", job_id[:8], type(error).__name__)
            self.store.fail(job_id, worker_id, "UNKNOWN", "Something went wrong while creating your song.")
        finally:
            for temp in temps:
                try:
                    os.remove(temp)
                except OSError:
                    pass

    def _discard_staging(self, job_id):
        try:
            rows = self.store.staging_objects(job_id)
            for _, key in rows:
                self.storage.delete(key)
            self.store.delete_object_rows([r[0] for r in rows])
        except Exception as error:  # cleanup() retries anything left behind
            log.error("discard_failed job=%s type=%s", job_id[:8], type(error).__name__)

    def _maintenance_loop(self):
        while not self._stop.wait(self.s.maintenance_interval_seconds):
            self.run_maintenance()

    def run_maintenance(self):
        try:
            self.store.recover_expired_leases()
            self.store.cleanup(self.storage)
        except Exception as error:
            log.error("maintenance_failed type=%s", type(error).__name__)
