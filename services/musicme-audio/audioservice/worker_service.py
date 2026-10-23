"""Dedicated durable-job coordinator entry point for future horizontal scaling.

Samsung DEV deliberately runs the same WorkerPool inside the single audio web service to avoid another always-on
Render charge. Before public production generation is enabled, set RUN_WORKERS=false on every web instance and run
exactly one or more of these background-worker processes against the shared PostgreSQL database and private object
store. PostgreSQL leases and SKIP LOCKED prevent duplicate claims.
"""
import logging
import signal
import threading

from .config import load_settings
from .cost_guard import DatabaseCostLedger
from .db import Database
from .engines import engine_from_settings
from .storage import object_store_from_settings
from .store import JobStore
from .worker import WorkerPool

log = logging.getLogger("musicme.audio.coordinator")


class Coordinator:
    def __init__(self, settings):
        if settings.engine == "none":
            raise SystemExit("The coordinator requires an explicitly configured generation engine.")
        self.settings = settings
        self.db = Database(settings.database_url)
        self.db.migrate()
        ledger = None
        if settings.engine == "remote_gpu" and settings.gpu_ledger_backend == "database":
            ledger = DatabaseCostLedger(self.db, settings.gpu_price_per_hour_usd, settings.gpu_daily_budget_usd,
                                        settings.gpu_max_renders_per_day, settings.gpu_reserve_seconds,
                                        settings.gpu_idle_tail_seconds)
        self.engine = engine_from_settings(settings, ledger=ledger)
        self.storage = object_store_from_settings(settings)
        self.pool = WorkerPool(JobStore(self.db, settings), self.engine, self.storage, settings)
        self.stopped = threading.Event()

    def start(self):
        self.pool.start()

    def shutdown(self):
        if self.stopped.is_set():
            return
        self.stopped.set()
        self.pool.request_stop()
        self.engine.close()
        self.pool.join()
        self.db.close()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    coordinator = Coordinator(load_settings())
    coordinator.start()
    signal.signal(signal.SIGTERM, lambda *_: coordinator.shutdown())
    signal.signal(signal.SIGINT, lambda *_: coordinator.shutdown())
    log.info("coordinator_started engine=%s workers=%d", coordinator.settings.engine, coordinator.settings.workers)
    coordinator.stopped.wait()


if __name__ == "__main__":
    main()
