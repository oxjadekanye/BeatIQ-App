"""DEV GPU spend guard for the remote_gpu engine: a per-day ledger on the audio service host.

Before each render it reserves a pessimistic estimate (cold start + render + idle tail) and refuses once the daily
render count or dollar budget would be exceeded; afterwards it settles the reservation with the measured wall time
plus the provider's idle tail (e.g. Modal's scaledown window). It is an estimate that errs high. The provider's own
spend limit (set on the account) remains the hard cap.
"""
import datetime
import json
import os
import threading
import time
import uuid


class GpuBudgetExceeded(Exception):
    pass


class CostLedger:
    def __init__(self, path, price_per_hour_usd, daily_budget_usd, max_renders_per_day, reserve_seconds, tail_seconds,
                 clock=time.time):
        self.path = path
        self.price_per_second = price_per_hour_usd / 3600.0
        self.daily_budget = daily_budget_usd
        self.max_renders = max_renders_per_day
        self.reserve_seconds = reserve_seconds
        self.tail_seconds = tail_seconds
        self.clock = clock
        self._lock = threading.Lock()

    def _today(self):
        return datetime.datetime.fromtimestamp(self.clock(), datetime.timezone.utc).date().isoformat()

    def _load(self):
        try:
            if os.path.islink(self.path):
                raise GpuBudgetExceeded("The GPU spend ledger must not be a symlink.")
            with open(self.path) as f:
                data = json.load(f)
        except FileNotFoundError:
            data = {}
        except ValueError:
            raise GpuBudgetExceeded("The GPU spend ledger is unreadable; refusing to spend until it is fixed.")
        if data.get("day") != self._today():
            data = {"day": self._today(), "renders": 0, "gpu_seconds": 0.0, "usd": 0.0}
        for name in ("renders", "gpu_seconds", "usd"):
            value = data.get(name)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                raise GpuBudgetExceeded("The GPU spend ledger is invalid; refusing to spend until it is fixed.")
        return data

    def _save(self, data):
        directory = os.path.dirname(self.path) or "."
        os.makedirs(directory, mode=0o700, exist_ok=True)
        if os.path.islink(directory):
            raise GpuBudgetExceeded("The GPU spend ledger directory must not be a symlink.")
        temp = os.path.join(directory, f".ledger-{uuid.uuid4().hex}.tmp")
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(data, f)
        os.replace(temp, self.path)

    def snapshot(self):
        with self._lock:
            return dict(self._load(), budget_usd=self.daily_budget, max_renders=self.max_renders)

    def reserve(self):
        """Reserves one render's worst-case cost or raises GpuBudgetExceeded. Returns the reserved amount (USD)."""
        estimate = (self.reserve_seconds + self.tail_seconds) * self.price_per_second
        with self._lock:
            data = self._load()
            if data["renders"] >= self.max_renders:
                raise GpuBudgetExceeded("daily render limit")
            if data["usd"] + estimate > self.daily_budget + 1e-9:
                raise GpuBudgetExceeded("daily GPU budget")
            data["renders"] += 1
            data["usd"] = round(data["usd"] + estimate, 6)
            self._save(data)
            return estimate

    def settle(self, reserved_usd, elapsed_seconds):
        """Replaces the reservation with the measured cost (wall time on the worker plus the idle tail)."""
        billed = max(0.0, elapsed_seconds) + self.tail_seconds
        with self._lock:
            data = self._load()
            data["gpu_seconds"] = round(data["gpu_seconds"] + billed, 3)
            data["usd"] = round(max(0.0, data["usd"] - reserved_usd) + billed * self.price_per_second, 6)
            self._save(data)


class DatabaseCostLedger:
    """PostgreSQL/SQLite implementation used by hosted services so limits survive replacement and scale safely.

    Reservations are committed before GPU submission. If a process dies, its pessimistic reservation remains charged
    for that UTC day; this intentionally fails toward lower spend instead of permitting an unrecorded retry.
    """
    def __init__(self, database, price_per_hour_usd, daily_budget_usd, max_renders_per_day, reserve_seconds,
                 tail_seconds, clock=time.time):
        self.db = database
        self.price_per_second = price_per_hour_usd / 3600.0
        self.daily_budget = daily_budget_usd
        self.max_renders = max_renders_per_day
        self.reserve_seconds = reserve_seconds
        self.tail_seconds = tail_seconds
        self.clock = clock

    def _today(self):
        return datetime.datetime.fromtimestamp(self.clock(), datetime.timezone.utc).date().isoformat()

    def _get_locked(self, tx, day):
        tx.execute("INSERT INTO audio_gpu_daily_usage (day, renders, gpu_seconds, usd, updated_at) "
                   "VALUES (?, 0, 0, 0, ?) ON CONFLICT (day) DO NOTHING", (day, int(self.clock() * 1000)))
        lock = " FOR UPDATE" if self.db.dialect == "postgres" else ""
        row = tx.fetchone("SELECT renders, gpu_seconds, usd FROM audio_gpu_daily_usage WHERE day = ?" + lock, (day,))
        if row is None or any(isinstance(v, bool) or not isinstance(v, (int, float)) or v < 0 for v in row):
            raise GpuBudgetExceeded("The GPU spend ledger is invalid; refusing to spend until it is fixed.")
        return row

    def snapshot(self):
        day = self._today()
        try:
            with self.db.transaction(exclusive=True) as tx:
                renders, gpu_seconds, usd = self._get_locked(tx, day)
        except GpuBudgetExceeded:
            raise
        except Exception:
            raise GpuBudgetExceeded("The GPU spend ledger is unavailable; refusing to spend.")
        return {"day": day, "renders": renders, "gpu_seconds": gpu_seconds, "usd": usd,
                "budget_usd": self.daily_budget, "max_renders": self.max_renders}

    def reserve(self):
        estimate = (self.reserve_seconds + self.tail_seconds) * self.price_per_second
        day, now = self._today(), int(self.clock() * 1000)
        try:
            with self.db.transaction(exclusive=True) as tx:
                renders, _, usd = self._get_locked(tx, day)
                if renders >= self.max_renders:
                    raise GpuBudgetExceeded("daily render limit")
                if usd + estimate > self.daily_budget + 1e-9:
                    raise GpuBudgetExceeded("daily GPU budget")
                tx.execute("UPDATE audio_gpu_daily_usage SET renders = ?, usd = ?, updated_at = ? WHERE day = ?",
                           (renders + 1, round(usd + estimate, 6), now, day))
        except GpuBudgetExceeded:
            raise
        except Exception:
            raise GpuBudgetExceeded("The GPU spend ledger is unavailable; refusing to spend.")
        return estimate

    def settle(self, reserved_usd, elapsed_seconds):
        billed = max(0.0, elapsed_seconds) + self.tail_seconds
        day, now = self._today(), int(self.clock() * 1000)
        try:
            with self.db.transaction(exclusive=True) as tx:
                _, gpu_seconds, usd = self._get_locked(tx, day)
                tx.execute("UPDATE audio_gpu_daily_usage SET gpu_seconds = ?, usd = ?, updated_at = ? WHERE day = ?",
                           (round(gpu_seconds + billed, 3),
                            round(max(0.0, usd - reserved_usd) + billed * self.price_per_second, 6), now, day))
        except GpuBudgetExceeded:
            raise
        except Exception:
            raise GpuBudgetExceeded("The GPU spend ledger settlement failed; its reservation remains charged.")
