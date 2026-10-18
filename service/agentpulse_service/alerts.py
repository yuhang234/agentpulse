"""Alert derivation (report schema 2.2 D + severity ladder 2.4).

Alerts are derived from the unified event stream; they are never invented. Each
alert has a deterministic id, so re-raising the same condition updates the
existing alert instead of duplicating it.
"""

from __future__ import annotations

from typing import Any

from .models import (
    KIND_AGENT_STATUS_CHANGED,
    KIND_DECISION_NEEDED,
    KIND_TASK_COMPLETED,
    KIND_TASK_FAILED,
    KIND_TASK_STATE_CHANGED,
    Alert,
    Event,
)
from .util import iso_utc, stable_id
from .world import World

CATEGORY_DECISION = "decision_required"
CATEGORY_FAILED = "task_failed"
CATEGORY_UNRESPONSIVE = "agent_unresponsive"
CATEGORY_BLOCKED = "pipeline_blocked"
CATEGORY_DISCONNECTED = "service_disconnected"


def alert_id(category: str, task_id: str | None, key: str | None = None) -> str:
    return stable_id("alert_", category, task_id or "", key or "")


class AlertEngine:
    def __init__(self, world: World) -> None:
        self.world = world

    # ------------------------------------------------------------------
    # events
    # ------------------------------------------------------------------
    def observe(self, event: Event) -> None:
        payload = event.payload or {}
        if event.kind == KIND_DECISION_NEEDED:
            self._raise(
                CATEGORY_DECISION,
                severity="critical",
                task_id=event.task_id,
                key=payload.get("key"),
                title=f"Decision required: {event.task_id or 'fleet'}",
                message=payload.get("note") or "a crewmate is waiting for a decision",
            )
        elif event.kind == KIND_TASK_FAILED:
            self._raise(
                CATEGORY_FAILED,
                severity="critical",
                task_id=event.task_id,
                title=f"Task failed: {event.task_id or 'fleet'}",
                message=payload.get("note") or "task reported failed",
            )
        elif event.kind == KIND_TASK_STATE_CHANGED:
            state = payload.get("state")
            if state == "blocked":
                self._raise(
                    CATEGORY_BLOCKED,
                    severity="warning",
                    task_id=event.task_id,
                    title=f"Task blocked: {event.task_id or 'fleet'}",
                    message=payload.get("note") or "task is blocked",
                )
            elif state == "resolved":
                self._resolve_key(event.task_id, payload.get("key"))
        elif event.kind == KIND_AGENT_STATUS_CHANGED:
            if payload.get("current_status") == "blocked":
                self._raise(
                    CATEGORY_BLOCKED,
                    severity="warning",
                    task_id=event.task_id,
                    title=f"Agent blocked: {event.task_id or payload.get('pane_id') or 'fleet'}",
                    message="Herdr reported the pane agent as blocked",
                )
        elif event.kind == KIND_TASK_COMPLETED:
            self.resolve_task(event.task_id)

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------
    def _raise(
        self,
        category: str,
        *,
        severity: str,
        title: str,
        message: str,
        task_id: str | None = None,
        key: str | None = None,
        actions: list[str] | None = None,
    ) -> Alert:
        aid = alert_id(category, task_id, key)
        if actions is None and task_id:
            task = self.world.task(task_id)
            if task is not None:
                actions = task.suggested_actions
        existing = {a.alert_id: a for a in self.world.alerts(status=None)}.get(aid)
        if existing is not None and existing.status == "active":
            existing.message = message
            existing.title = title
            existing.severity = severity
            existing.suggested_actions = list(actions or [])
            self.world.upsert_alert(existing)
            return existing
        alert = Alert(
            alert_id=aid,
            category=category,
            severity=severity,
            title=title,
            message=message,
            created_at=iso_utc(),
            status="active",
            task_id=task_id,
            key=key,
            suggested_actions=list(actions or []),
        )
        self.world.upsert_alert(alert)
        return alert

    def resolve_task(self, task_id: str | None) -> int:
        if not task_id:
            return 0
        resolved = 0
        for alert in self.world.alerts(status="active"):
            if alert.task_id == task_id:
                if self.world.resolve_alert(alert.alert_id):
                    resolved += 1
        return resolved

    def _resolve_key(self, task_id: str | None, key: str | None) -> None:
        for category in (CATEGORY_DECISION,):
            aid = alert_id(category, task_id, key)
            self.world.resolve_alert(aid)

    def acknowledge(self, alert_id_value: str) -> bool:
        return self.world.resolve_alert(alert_id_value, status="acknowledged")

    def stats(self) -> dict[str, Any]:
        return {"active": len(self.world.alerts(status="active"))}
