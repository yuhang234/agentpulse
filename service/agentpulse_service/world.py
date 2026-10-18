"""Shared aggregate state (tasks, panes, sessions, alerts, collector health).

Collectors are the only writers; API handlers read linked copies.  Every
mutation bumps a generation counter, so Task <-> pane <-> session links
(report 2.3) are recomputed lazily and cached between mutations.
"""

from __future__ import annotations

import copy
import threading
import time
from pathlib import Path
from typing import Any, Iterable

from .correlation import newest_session, resolve_pane_id
from .models import Alert, Task, TerminalPane
from .util import clamp, now_epoch

STATE_ORDER = {
    "blocked": 0,
    "failed": 1,
    "held": 2,
    "in_flight": 3,
    "queued": 4,
    "done": 5,
}


class World:
    def __init__(self, *, sessions_root: Path, start_epoch: float | None = None) -> None:
        self._lock = threading.RLock()
        self._sessions_root = Path(sessions_root)
        self.start_epoch = start_epoch if start_epoch is not None else now_epoch()

        self._tasks: dict[str, Task] = {}
        self._panes: dict[str, TerminalPane] = {}
        self._session_ids: dict[str, str] = {}
        self._collectors: dict[str, dict[str, Any]] = {}
        self._alerts: dict[str, Alert] = {}
        self._decisions: list[dict[str, Any]] = []
        self._home_summary: dict[str, Any] = {}
        self._last_snapshot_generated: str | None = None

        self._gen = 0
        self._links_cache: dict[str, Task] = {}
        self._links_gen = -1

    # ------------------------------------------------------------------
    # writes
    # ------------------------------------------------------------------
    def set_tasks(self, tasks: Iterable[Task]) -> None:
        mapping = {task.id: task for task in tasks}
        with self._lock:
            self._tasks = mapping
            self._gen += 1

    def upsert_task(self, task: Task) -> None:
        with self._lock:
            self._tasks[task.id] = task
            self._gen += 1

    def upsert_pane(self, pane: TerminalPane) -> None:
        with self._lock:
            self._panes[pane.pane_id] = pane
            self._gen += 1

    def drop_missing_panes(self, keep: set[str]) -> None:
        with self._lock:
            for pane_id in list(self._panes):
                if pane_id not in keep:
                    del self._panes[pane_id]
            self._gen += 1

    def set_session(self, task_id: str, session_id: str | None) -> None:
        with self._lock:
            if session_id is None:
                self._session_ids.pop(task_id, None)
            else:
                self._session_ids[task_id] = session_id
            self._gen += 1

    def set_health(self, collector: str, status: str, **extra: Any) -> None:
        with self._lock:
            entry: dict[str, Any] = {"status": status}
            entry.update(extra)
            if status == "ok":
                entry.setdefault("last_sync_epoch", int(now_epoch()))
            self._collectors[collector] = entry

    def set_decisions(self, decisions: list[dict[str, Any]]) -> None:
        with self._lock:
            self._decisions = list(decisions)
            self._gen += 1

    def set_home_summary(self, summary: dict[str, Any] | None) -> None:
        with self._lock:
            self._home_summary = copy.deepcopy(summary or {})
            self._gen += 1

    def set_snapshot_generated(self, generated: str | None) -> None:
        with self._lock:
            self._last_snapshot_generated = generated

    def upsert_alert(self, alert: Alert) -> None:
        with self._lock:
            self._alerts[alert.alert_id] = alert

    def resolve_alert(self, alert_id: str, status: str = "resolved") -> bool:
        with self._lock:
            alert = self._alerts.get(alert_id)
            if alert is None or alert.status != "active":
                return False
            alert.status = status
            return True

    # ------------------------------------------------------------------
    # reads
    # ------------------------------------------------------------------
    def task(self, task_id: str) -> Task | None:
        with self._lock:
            self._refresh_links()
            linked = self._links_cache.get(task_id)
            return copy.deepcopy(linked) if linked else None

    def tasks(self) -> list[Task]:
        with self._lock:
            self._refresh_links()
            tasks = [copy.deepcopy(task) for task in self._links_cache.values()]
        tasks.sort(key=lambda t: (STATE_ORDER.get(t.state, 9), t.id))
        return tasks

    def panes(self) -> dict[str, TerminalPane]:
        with self._lock:
            return {pane_id: copy.deepcopy(pane) for pane_id, pane in self._panes.items()}

    def pane(self, pane_id: str) -> TerminalPane | None:
        with self._lock:
            pane = self._panes.get(pane_id)
            return copy.deepcopy(pane) if pane else None

    def session_ids(self) -> dict[str, str]:
        with self._lock:
            return dict(self._session_ids)

    def alerts(self, *, status: str | None = "active") -> list[Alert]:
        with self._lock:
            alerts = [
                copy.deepcopy(alert)
                for alert in self._alerts.values()
                if status is None or alert.status == status
            ]
        alerts.sort(key=lambda a: (a.severity, a.created_at), reverse=True)
        return alerts

    def decisions(self) -> list[dict[str, Any]]:
        with self._lock:
            return copy.deepcopy(self._decisions)

    def home_summary(self) -> dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._home_summary)

    def snapshot_generated(self) -> str | None:
        with self._lock:
            return self._last_snapshot_generated

    def collectors(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return copy.deepcopy(self._collectors)

    def uptime_seconds(self) -> int:
        return int(max(0.0, now_epoch() - self.start_epoch))

    def counts(self) -> dict[str, int]:
        tasks = self.tasks()
        alerts = self.alerts(status="active")
        return {
            "active_tasks": sum(1 for t in tasks if t.state == "in_flight"),
            "queued_tasks": sum(1 for t in tasks if t.state == "queued"),
            "completed_tasks": sum(1 for t in tasks if t.state == "done"),
            "active_alerts": len(alerts),
            "critical_alerts": sum(1 for a in alerts if a.severity == "critical"),
            "working_agents": sum(
                1
                for t in tasks
                if t.state == "in_flight" and (t.agent_status or "") == "working"
            ),
        }

    def task_terminal_lines_hint(self, limit: int, maximum: int) -> int:
        return clamp(limit, 1, maximum)

    # ------------------------------------------------------------------
    # linking (report 2.3)
    # ------------------------------------------------------------------
    def _refresh_links(self) -> None:
        if self._links_gen == self._gen:
            return
        panes = self._panes
        linked: dict[str, Task] = {}
        for task in self._tasks.values():
            clone = copy.deepcopy(task)
            pane_id = resolve_pane_id(
                task_id=clone.id,
                meta_pane_id=clone.meta_pane_id,
                worktree=clone.worktree,
                panes=panes,
            )
            clone.assigned_pane_id = pane_id
            if pane_id:
                pane = panes.get(pane_id)
                if pane is not None:
                    clone.agent = pane.agent or clone.harness or None
                    clone.agent_status = pane.agent_status or "unknown"
                else:
                    clone.agent_status = clone.agent_status or "unknown"
            # Explicit null for non-Pi harnesses; newest session file otherwise.
            if (clone.harness or "").strip().lower() == "pi":
                session_id = self._session_ids.get(clone.id)
                if not session_id and clone.worktree:
                    ref = newest_session(self._sessions_root, clone.worktree)
                    session_id = ref.session_id if ref else None
                clone.active_agent_session_id = session_id
            else:
                clone.active_agent_session_id = None
            linked[clone.id] = clone
        self._links_cache = linked
        self._links_gen = self._gen

    def alert_counts(self) -> dict[str, int]:
        alerts = self.alerts(status="active")
        by_category: dict[str, int] = {}
        for alert in alerts:
            by_category[alert.category] = by_category.get(alert.category, 0) + 1
        return by_category
