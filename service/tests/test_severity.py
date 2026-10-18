"""Severity mapping (report 2.4)."""

from agentpulse_service.severity import (
    severity_for,
    severity_for_agent_status,
    severity_for_state,
)


def test_state_severity():
    assert severity_for_state("failed") == "critical"
    assert severity_for_state("needs-decision") == "critical"
    assert severity_for_state("blocked") == "warning"
    assert severity_for_state("held") == "warning"
    assert severity_for_state("working") == "info"
    assert severity_for_state("done") == "info"
    assert severity_for_state("queued") == "info"


def test_agent_status_severity():
    assert severity_for_agent_status("blocked") == "warning"
    assert severity_for_agent_status("working") == "info"
    assert severity_for_agent_status("idle") == "info"


def test_tool_result_is_error():
    assert severity_for("tool_result", {"isError": True}) == "error"
    assert severity_for("tool_result", {"isError": False}) == "info"


def test_tool_result_nonzero_exit_code():
    assert severity_for("tool_result", {"exit_code": 1}) == "error"
    assert severity_for("tool_result", {"exit_code": 0}) == "info"


def test_tool_call_is_info():
    assert severity_for("tool_call", {}) == "info"


def test_agent_status_changed_crash_is_critical():
    assert severity_for("agent_status_changed", {"exit": True}) == "critical"
    assert severity_for("agent_status_changed", {"current_status": "blocked"}) == "warning"
    assert severity_for("agent_status_changed", {"current_status": "working"}) == "info"


def test_task_failed_and_decision_are_critical():
    assert severity_for("task_failed", {}) == "critical"
    assert severity_for("decision_needed", {}) == "critical"
    assert severity_for("task_completed", {}) == "info"


def test_debug_kinds():
    assert severity_for("thinking_chunk", {}) == "debug"
    assert severity_for("pane_output", {}) == "debug"


def test_state_changed_uses_payload_state():
    assert severity_for("task_state_changed", {"state": "blocked"}) == "warning"
    assert severity_for("task_state_changed", {"state": "failed"}) == "critical"
