"""Database access (adapted from musicme-songwriter) for SQLite (development) and PostgreSQL (production), with ordered migrations.

SQL is written once with '?' placeholders; the PostgreSQL connection converts them to '%s'.
"""
import contextlib
import os
import re
import sqlite3
import threading

MIGRATIONS_DIR = os.path.join(os.path.dirname(__file__), "migrations")


class Database:
    def __init__(self, url):
        self.url = url
        if url.startswith("sqlite:///"):
            self.dialect = "sqlite"
            self.path = url[len("sqlite:///"):]
            if not self.path or self.path == ":memory:":
                raise ValueError("Use a file-backed SQLite database so jobs survive restarts.")
            directory = os.path.dirname(os.path.abspath(self.path))
            os.makedirs(directory, exist_ok=True)
        elif url.startswith(("postgresql://", "postgres://")):
            self.dialect = "postgres"
            try:
                import psycopg2  # noqa: F401  (optional dependency, required only for PostgreSQL)
            except ImportError:
                raise SystemExit("PostgreSQL storage requires psycopg2 (see requirements.txt).")
        else:
            raise ValueError("MUSICME_DATABASE_URL must be sqlite:///<path> or postgresql://...")
        self._local = threading.local()

    # -- connections -----------------------------------------------------------------------

    def _connection(self):
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            return conn
        if self.dialect == "sqlite":
            new_file = not os.path.exists(self.path)
            conn = sqlite3.connect(self.path, timeout=30, isolation_level=None, check_same_thread=False)
            conn.execute("PRAGMA busy_timeout=30000")
            _enable_wal(conn)
            conn.execute("PRAGMA foreign_keys=ON")
            if new_file:
                os.chmod(self.path, 0o600)  # lyrics and account ids are personal data
        else:
            import psycopg2
            conn = psycopg2.connect(self.url)
            conn.autocommit = True
        self._local.conn = conn
        return conn

    @contextlib.contextmanager
    def transaction(self, exclusive=False):
        """Yields an executor. SQLite exclusive transactions serialise writers (BEGIN IMMEDIATE)."""
        conn = self._connection()
        if self.dialect == "sqlite":
            conn.execute("BEGIN IMMEDIATE" if exclusive else "BEGIN")
            executor = _Executor(conn, self.dialect)
            try:
                yield executor
                conn.execute("COMMIT")
            except BaseException:
                conn.execute("ROLLBACK")
                raise
        else:
            conn.autocommit = False
            executor = _Executor(conn, self.dialect)
            try:
                yield executor
                conn.commit()
            except BaseException:
                conn.rollback()
                raise
            finally:
                conn.autocommit = True

    def close(self):
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None

    def ping(self):
        with self.transaction() as tx:
            return tx.fetchone("SELECT 1")[0] == 1

    # -- migrations ------------------------------------------------------------------------

    def migrate(self):
        with self.transaction(exclusive=True) as tx:
            if self.dialect == "postgres":
                tx.execute("SELECT pg_advisory_xact_lock(7342101)")  # one migrator at a time
            tx.execute("CREATE TABLE IF NOT EXISTS audio_schema_migrations (version TEXT PRIMARY KEY, applied_at BIGINT NOT NULL)")
            applied = {row[0] for row in tx.fetchall("SELECT version FROM audio_schema_migrations")}
            for name in sorted(os.listdir(MIGRATIONS_DIR)):
                if not name.endswith(".sql") or name in applied:
                    continue
                with open(os.path.join(MIGRATIONS_DIR, name)) as f:
                    script = f.read()
                for statement in _statements(script):
                    tx.execute(statement)
                tx.execute("INSERT INTO audio_schema_migrations (version, applied_at) VALUES (?, ?)", (name, _now_ms()))


class _Executor:
    def __init__(self, conn, dialect):
        self.conn = conn
        self.dialect = dialect

    def _sql(self, sql):
        return sql.replace("?", "%s") if self.dialect == "postgres" else sql

    def execute(self, sql, params=()):
        cursor = self.conn.cursor()
        cursor.execute(self._sql(sql), params)
        return cursor

    def fetchone(self, sql, params=()):
        return self.execute(sql, params).fetchone()

    def fetchall(self, sql, params=()):
        return self.execute(sql, params).fetchall()


def _enable_wal(conn, attempts=100):
    """Switching a new file to WAL needs an exclusive lock and SQLite may report "locked" without waiting
    (e.g. several processes starting at once), so retry briefly instead of failing startup."""
    import time
    for attempt in range(attempts):
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            return
        except sqlite3.OperationalError as error:
            if "locked" not in str(error) or attempt == attempts - 1:
                raise
            time.sleep(0.02 * (1 + attempt % 5))


def _statements(script):
    script = re.sub(r"--[^\n]*", "", script)
    return [s.strip() for s in script.split(";") if s.strip()]


def _now_ms():
    import time
    return int(time.time() * 1000)
