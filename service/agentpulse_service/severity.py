"""Severity mapping and deduplication keys.

Severity follows report section 2.4 (分级阶梯); dedup keys follow the same
section's 幂等去重 rules.
"""

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

SEVERITIES = ("debug", "info", "warning", "error", "critical")
TIERS = ("info", "warning", "critical")

DEFAULT_TIER_MAPPING: dict[str, str] = {
    "critical": "critical",
    "error": "warning",
    "warning": "warning",
    "info": "info",
    "debug": "info",
}

ENV_TIER_MAPPING_PATH = "AGENTPULSE_TIER_MAPPING_PATH"


def load_tier_mapping(path: str | Path | None = None) -> dict[str, str]:
    """Load and validate tier mapping overrides from environment or file path.

    Falls back to built-in DEFAULT_TIER_MAPPING with a logged warning if the
    override file is missing, malformed, or contains unknown severities or tiers.
    """
    raw_path = path if path is not None else (
        os.environ.get(ENV_TIER_MAPPING_PATH) or os.environ.get("AGENTPULSE_TIER_CONFIG")
    )
    if not raw_path:
        return dict(DEFAULT_TIER_MAPPING)

    file_path = Path(raw_path).expanduser()
    if not file_path.is_file():
        logger.warning(
            "Tier mapping file not found at '%s'; falling back to default mapping",
            file_path,
        )
        return dict(DEFAULT_TIER_MAPPING)

    try:
        with file_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:
        logger.warning(
            "Failed to parse tier mapping JSON from '%s' (%s); falling back to default mapping",
            file_path,
            exc,
        )
        return dict(DEFAULT_TIER_MAPPING)

    if not isinstance(data, dict):
        logger.warning(
            "Tier mapping in '%s' is not a JSON object; falling back to default mapping",
            file_path,
        )
        return dict(DEFAULT_TIER_MAPPING)

    # Validate keys against SEVERITIES and values against TIERS
    for k, v in data.items():
        if k not in SEVERITIES or v not in TIERS:
            logger.warning(
                "Invalid severity '%s' or tier '%s' in '%s'; falling back to default mapping",
                k,
                v,
                file_path,
            )
            return dict(DEFAULT_TIER_MAPPING)

    merged = dict(DEFAULT_TIER_MAPPING)
    merged.update(data)
    return merged


def tier_for_severity(
    severity: str | None, mapping: dict[str, str] | None = None
) -> str:
    """Project a 5-level severity into a 3-tier presentation level."""
    m = mapping if mapping is not None else load_tier_mapping()
    sev = (severity or "").strip().lower()
    return m.get(sev, "info")


def tier_for(
    kind: str,
    payload: dict[str, Any] | None = None,
    mapping: dict[str, str] | None = None,
) -> str:
    """Direct tier resolution for an event kind and payload."""
    sev = severity_for(kind, payload)
    return tier_for_severity(sev, mapping)


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
