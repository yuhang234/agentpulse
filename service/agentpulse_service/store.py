"""Event store: in-memory ring buffer plus SQLite persistence.

Implements report 2.4's retention policy:

* 1000 events per task in an in-memory ring buffer for instant context restore;
* SQLite persistence with the documented dedup keys, a 7 day / 50000 row
  retention sweep and periodic compaction.

Every event gets a strictly increasing global ``seq``; the SQLite rowid is the
sequence, so it keeps increasing across service restarts.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

from .clock import epoch_now
from .models import Event

_SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    seq        INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id   TEXT NOT NULL UNIQUE,
    dedup_key  TEXT,
    task_id    TEXT,
    pane_id    TEXT,
    source     TEXT NOT NULL,
    kind       TEXT NOT NULL,
    severity   TEXT NOT NULL,
    timestamp  TEXT NOT NULL,
    payload    TEXT NOT NULL,
    created_at REAL NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedup ON events(dedup_key);
CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at);
"""


class EventStore:
    def __init__(
        self,
        db_path: str | Path,
        ring_size: int = 1000,
        retention_days: int = 7,
        retention_max_rows: int = 50_000,
    ) -> None:
        self.db_path = str(db_path)
        self.ring_size = ring_size
        self.retention_days = retention_days
        self.retention_max_rows = retention_max_rows
        self._lock = threading.RLock()
        self._rings: dict[str, deque[Event]] = defaultdict(lambda: deque(maxlen=self.ring_size))

        if self.db_path != ":memory:":
            parent = Path(self.db_path).expanduser()
            parent.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        if self.db_path != ":memory:":
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    # -- writes ---------------------------------------------------------

    def append(self, event: Event, dedup_key: str | None = None) -> bool:
        """Persist and buffer ``event``.

        Returns ``False`` when ``dedup_key`` was already seen (the event is
        dropped and no seq is assigned).
        """
        with self._lock:
            try:
                cursor = self._conn.execute(
                    """
                    INSERT INTO events
                        (event_id, dedup_key, task_id, pane_id, source, kind,
                         severity, timestamp, payload, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        event.event_id,
                        dedup_key,
                        event.task_id,
                        event.pane_id,
                        event.source,
                        event.kind,
                        event.severity,
                        event.timestamp,
                        json.dumps(event.payload, ensure_ascii=False, default=str),
                        epoch_now(),
                    ),
                )
            except sqlite3.IntegrityError:
                return False
            self._conn.commit()
            event.seq = int(cursor.lastrowid)
            self._rings[event.task_id or "__global__"].append(event)
            return True

    # -- reads ----------------------------------------------------------

    def max_seq(self) -> int:
        with self._lock:
            row = self._conn.execute("SELECT COALESCE(MAX(seq), 0) AS m FROM events").fetchone()
        return int(row["m"])

    def events_since(
        self,
        since_seq: int | None,
        limit: int = 100,
        task_id: str | None = None,
    ) -> list[Event]:
        """Events with ``seq > since_seq``, oldest first, filtered by task."""
        cursor = int(since_seq or 0)
        sql = [
            "SELECT seq, event_id, task_id, pane_id, source, kind, severity,",
            "       timestamp, payload FROM events WHERE seq > ?",
        ]
        params: list[Any] = [cursor]
        if task_id is not None:
            sql.append("AND task_id = ?")
            params.append(task_id)
        sql.append("ORDER BY seq ASC LIMIT ?")
        params.append(int(limit))
        with self._lock:
            rows = self._conn.execute(" ".join(sql), params).fetchall()
        return [self._row_to_event(row) for row in rows]

    def ring(self, task_id: str | None) -> list[Event]:
        """Snapshot of the in-memory ring buffer for a task."""
        with self._lock:
            return list(self._rings[task_id or "__global__"])

    def count(self) -> int:
        with self._lock:
            row = self._conn.execute("SELECT COUNT(*) AS c FROM events").fetchone()
        return int(row["c"])

    # -- retention ------------------------------------------------------

    def sweep(self) -> int:
        """Apply the 7 day / 50000 row retention policy. Returns rows removed."""
        removed = 0
        cutoff = epoch_now() - self.retention_days * 86400
        with self._lock:
            cur = self._conn.execute("DELETE FROM events WHERE created_at < ?", (cutoff,))
            removed += cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            cur = self._conn.execute(
                """
                DELETE FROM events WHERE seq <= (
                    SELECT seq FROM events ORDER BY seq DESC LIMIT 1 OFFSET ?
                )
                """,
                (self.retention_max_rows,),
            )
            removed += cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            self._conn.commit()
            if removed:
                try:
                    self._conn.execute("VACUUM")
                except sqlite3.Error:
                    pass
        return removed

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- helpers --------------------------------------------------------

    @staticmethod
    def _row_to_event(row: sqlite3.Row) -> Event:
        try:
            payload = json.loads(row["payload"])
        except (TypeError, ValueError):
            payload = {}
        return Event(
            event_id=row["event_id"],
            seq=int(row["seq"]),
            task_id=row["task_id"],
            pane_id=row["pane_id"],
            source=row["source"],
            kind=row["kind"],
            severity=row["severity"],
            timestamp=row["timestamp"],
            payload=payload,
        )
