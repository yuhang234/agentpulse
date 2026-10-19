"""Unified data model entities (report section 2.2).

Serialisation keeps the documented field names; optional fields are emitted as
JSON ``null`` (or an empty list where the schema says array) so clients can
rely on a stable key set.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

TASK_STATES = ("queued", "in_flight", "done", "held", "failed", "blocked")
TASK_KINDS = ("scout", "ship", "secondmate", "custom")
AGENT_STATUSES = ("working", "idle", "blocked", "done", "unknown")
EVENT_SOURCES = ("firstmate", "herdr", "pi", "service")
EVENT_KINDS = (
    "task_state_changed",
    "agent_status_changed",
    "tool_call",
    "tool_result",
    "thinking_chunk",
    "pane_output",
    "decision_needed",
    "task_completed",
    "task_failed",
)

# Named event kinds (report 2.2 C), the single source of truth used by the
# alert engine and the collectors.
KIND_TASK_STATE_CHANGED = "task_state_changed"
KIND_AGENT_STATUS_CHANGED = "agent_status_changed"
KIND_TOOL_CALL = "tool_call"
KIND_TOOL_RESULT = "tool_result"
KIND_THINKING_CHUNK = "thinking_chunk"
KIND_PANE_OUTPUT = "pane_output"
KIND_DECISION_NEEDED = "decision_needed"
KIND_TASK_COMPLETED = "task_completed"
KIND_TASK_FAILED = "task_failed"
ALERT_CATEGORIES = (
    "decision_required",
    "task_failed",
    "agent_unresponsive",
    "pipeline_blocked",
    "service_disconnected",
)
ALERT_SEVERITIES = ("warning", "error", "critical")
ALERT_STATUSES = ("active", "acknowledged", "resolved")


@dataclass
class Task:
    """A FirstMate task joined to its Herdr pane and Pi session."""

    id: str
    title: str
    state: str
    kind: str
    repo: str
    created_at: str
    updated_at: str
    description: str | None = None
    worktree: str | None = None
    branch: str | None = None
    pr_url: str | None = None
    harness: str | None = None
    model: str | None = None
    blocked_by: str | None = None
    blocked_by_ids: list[str] = field(default_factory=list)
    hold_reason: str | None = None
    hold_kind: str | None = None
    assigned_pane_id: str | None = None
    active_agent_session_id: str | None = None

    # Runtime-only enrichment (not part of the Task schema, but used by
    # /api/v1/overview and by the collectors).
    agent: str | None = None
    agent_status: str | None = None
    since: str | None = None
    current_doing: str | None = None
    current_state_source: str | None = None
    pane_join_source: str | None = None
    session_path: str | None = None
    # Raw ``herdr_pane_id`` straight from ``state/<id>.meta`` (before the
    # report 2.3 correlation), used to detect an offline pane.
    meta_pane_id: str | None = None
    # Reconciled FirstMate lifecycle state (``working``/``blocked``/
    # ``needs-decision``/``failed``/``done``/...), distinct from the report
    # task ``state`` enum.
    crew_state: str | None = None
    # Suggested operator actions surfaced alongside alerts.
    suggested_actions: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        """Full Task entity (report 2.2 A)."""
        return {
            "id": self.id,
            "title": self.title,
            "description": self.description,
            "state": self.state,
            "kind": self.kind,
            "repo": self.repo,
            "worktree": self.worktree,
            "branch": self.branch,
            "pr_url": self.pr_url,
            "harness": self.harness,
            "model": self.model,
            "blocked_by": self.blocked_by,
            "blocked_by_ids": list(self.blocked_by_ids),
            "hold_reason": self.hold_reason,
            "hold_kind": self.hold_kind,
            "assigned_pane_id": self.assigned_pane_id,
            "active_agent_session_id": self.active_agent_session_id,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    def to_overview(self) -> dict[str, Any]:
        """Compact task row used by /api/v1/overview."""
        return {
            "id": self.id,
            "title": self.title,
            "kind": self.kind,
            "repo": self.repo,
            "state": self.state,
            "agent": self.agent,
            "agent_status": self.agent_status,
            "since": self.since,
            "current_doing": self.current_doing,
        }


@dataclass
class TerminalPane:
    """A Herdr terminal pane (report 2.2 B)."""

    pane_id: str
    workspace_id: str
    agent_status: str = "unknown"
    focused: bool = False
    tab_id: str | None = None
    terminal_title: str = ""
    agent: str | None = None
    cwd: str = ""
    foreground_cwd: str = ""
    foreground_processes: list[dict[str, Any]] = field(default_factory=list)
    recent_output_tail: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "pane_id": self.pane_id,
            "workspace_id": self.workspace_id,
            "tab_id": self.tab_id,
            "terminal_title": self.terminal_title,
            "agent": self.agent,
            "agent_status": self.agent_status,
            "cwd": self.cwd,
            "foreground_cwd": self.foreground_cwd,
            "focused": self.focused,
            "foreground_processes": list(self.foreground_processes),
            "recent_output_tail": list(self.recent_output_tail),
        }


@dataclass
class Event:
    """A unified event (report 2.2 C)."""

    event_id: str
    seq: int
    source: str
    kind: str
    severity: str
    timestamp: str
    task_id: str | None = None
    pane_id: str | None = None
    payload: dict[str, Any] = field(default_factory=dict)
    tier: str | None = None

    def __post_init__(self) -> None:
        if self.tier is None:
            from .severity import tier_for_severity

            self.tier = tier_for_severity(self.severity)

    def to_dict(self) -> dict[str, Any]:
        return {
            "event_id": self.event_id,
            "seq": self.seq,
            "task_id": self.task_id,
            "pane_id": self.pane_id,
            "source": self.source,
            "kind": self.kind,
            "severity": self.severity,
            "tier": self.tier,
            "timestamp": self.timestamp,
            "payload": dict(self.payload),
        }


@dataclass
class Alert:
    """An operator-facing alert (report 2.2 D)."""

    alert_id: str
    category: str
    severity: str
    title: str
    message: str
    created_at: str
    status: str = "active"
    task_id: str | None = None
    key: str | None = None
    suggested_actions: list[str] = field(default_factory=list)
    tier: str | None = None

    def __post_init__(self) -> None:
        if self.tier is None:
            from .severity import tier_for_severity

            self.tier = tier_for_severity(self.severity)

    def to_dict(self) -> dict[str, Any]:
        return {
            "alert_id": self.alert_id,
            "task_id": self.task_id,
            "category": self.category,
            "severity": self.severity,
            "tier": self.tier,
            "title": self.title,
            "message": self.message,
            "key": self.key,
            "suggested_actions": list(self.suggested_actions),
            "created_at": self.created_at,
            "status": self.status,
        }
