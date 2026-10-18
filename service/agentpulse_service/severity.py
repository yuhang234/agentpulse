"""Severity mapping and deduplication keys.

Severity follows report section 2.4 (分级阶梯); dedup keys follow the same
section's 幂等去重 rules.
"""

from __future__ import annotations

import hashlib
from typing import Any

SEVERITIES = ("debug", "info", "warning", "error", "critical")

# Task lifecycle states (FirstMate) that carry a severity of their own.
_STATE_SEVERITY: dict[str, str] = {
    "queued": "info",
    "in_flight": "info",
    "working": "info",
    "done": "info",
    "resolved": "info",
    "paused": "info",
    "blocked": "warning",
    "held": "warning",
    "stale": "warning",
    "pane_offline": "warning",
    "needs-decision": "critical",
    "failed": "critical",
}


def severity_for_state(state: str | None) -> str:
    """Severity for a FirstMate lifecycle state."""
    return _STATE_SEVERITY.get((state or "").strip(), "info")


def severity_for_agent_status(status: str | None) -> str:
    """Severity for a Herdr agent status transition."""
    if (status or "").strip() == "blocked":
        return "warning"
    return "info"


def severity_for(kind: str, payload: dict[str, Any] | None = None) -> str:
    """Central severity ladder for unified events.

    - critical: task failed, agent crash exit, human decision required
    - error:    failed tool call / non-zero exit code
    - warning:  agent blocked, stale heartbeat, degraded connection
    - info:     lifecycle transitions and tool-call starts
    - debug:    thinking token stream, fine-grained terminal output
    """
    payload = payload or {}

    if kind == "task_failed":
        return "critical"
    if kind == "decision_needed":
        return "critical"
    if kind == "task_completed":
        return "info"
    if kind == "task_state_changed":
        return severity_for_state(payload.get("state"))
    if kind == "agent_status_changed":
        if payload.get("exit") is True:
            return "critical"
        return severity_for_agent_status(payload.get("current_status"))
    if kind == "tool_call":
        return "info"
    if kind == "tool_result":
        if payload.get("isError") is True:
            return "error"
        exit_code = payload.get("exit_code")
        if isinstance(exit_code, int) and exit_code != 0:
            return "error"
        return "info"
    if kind in ("thinking_chunk", "pane_output"):
        return "debug"
    return "info"


# --------------------------------------------------------------------------
# Deduplication keys (report 2.4)
# --------------------------------------------------------------------------


def firstmate_status_key(task_id: str, state: str, at_epoch: int | None) -> str:
    """FirstMate status log lines: ``(task_id, state, at_epoch)``."""
    return f"firstmate:status:{task_id}:{state}:{at_epoch if at_epoch is not None else 'na'}"


def firstmate_current_key(task_id: str, state: str, observed_at: str | None) -> str:
    """Reconciled current-state transitions: ``(task_id, state, observed_at)``."""
    return f"firstmate:current:{task_id}:{state}:{observed_at or 'na'}"


def firstmate_decision_key(task_id: str, key: str | None) -> str:
    return f"firstmate:decision:{task_id}:{key or 'open'}"


def pi_message_key(session_id: str, message_id: str) -> str:
    """Pi events keyed by ``message.id``."""
    return f"pi:message:{session_id}:{message_id}"


def pi_toolcall_key(session_id: str, tool_call_id: str) -> str:
    """Pi events keyed by ``toolCall.id``."""
    return f"pi:toolcall:{session_id}:{tool_call_id}"


def herdr_status_key(pane_id: str, status: str, state_change_seq: int | None) -> str:
    return f"herdr:status:{pane_id}:{status}:{state_change_seq if state_change_seq is not None else 'na'}"


def digest_lines(lines: list[str]) -> str:
    """SHA-256 over the last few terminal lines (report 2.4, Herdr rule)."""
    payload = "\n".join(lines[-5:]).encode("utf-8", "replace")
    return hashlib.sha256(payload).hexdigest()


def herdr_lines_key(pane_id: str, digest: str) -> str:
    return f"herdr:lines:{pane_id}:{digest}"
