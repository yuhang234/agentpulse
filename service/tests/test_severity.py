import json
import logging

from agentpulse_service.models import Alert, Event
from agentpulse_service.severity import (
    DEFAULT_TIER_MAPPING,
    ENV_TIER_MAPPING_PATH,
    load_tier_mapping,
    severity_for,
    severity_for_agent_status,
    severity_for_state,
    tier_for,
    tier_for_severity,
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


def test_default_tier_mapping():
    assert tier_for_severity("critical") == "critical"
    assert tier_for_severity("error") == "warning"
    assert tier_for_severity("warning") == "warning"
    assert tier_for_severity("info") == "info"
    assert tier_for_severity("debug") == "info"
    assert tier_for_severity("unknown") == "info"

    assert tier_for("task_failed") == "critical"
    assert tier_for("decision_needed") == "critical"
    assert tier_for("task_state_changed", {"state": "blocked"}) == "warning"
    assert tier_for("tool_result", {"isError": True}) == "warning"
    assert tier_for("task_completed") == "info"
    assert tier_for("thinking_chunk") == "info"


def test_valid_tier_override(tmp_path, monkeypatch):
    cfg = tmp_path / "tiers.json"
    cfg.write_text(json.dumps({"warning": "info", "error": "critical"}))
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(cfg))

    mapping = load_tier_mapping()
    assert mapping["warning"] == "info"
    assert mapping["error"] == "critical"
    assert mapping["critical"] == "critical"
    assert mapping["info"] == "info"
    assert mapping["debug"] == "info"
    assert tier_for_severity("warning") == "info"
    assert tier_for_severity("error") == "critical"


def test_missing_override_fallback(tmp_path, monkeypatch, caplog):
    nonexistent = tmp_path / "does_not_exist.json"
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(nonexistent))

    with caplog.at_level(logging.WARNING):
        mapping = load_tier_mapping()

    assert mapping == DEFAULT_TIER_MAPPING
    assert "not found" in caplog.text.lower()


def test_malformed_json_fallback(tmp_path, monkeypatch, caplog):
    bad = tmp_path / "malformed.json"
    bad.write_text("{not valid json")
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(bad))

    with caplog.at_level(logging.WARNING):
        mapping = load_tier_mapping()

    assert mapping == DEFAULT_TIER_MAPPING
    assert "failed to parse tier mapping json" in caplog.text.lower()


def test_non_dict_json_fallback(tmp_path, monkeypatch, caplog):
    non_dict = tmp_path / "array.json"
    non_dict.write_text(json.dumps(["critical", "warning"]))
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(non_dict))

    with caplog.at_level(logging.WARNING):
        mapping = load_tier_mapping()

    assert mapping == DEFAULT_TIER_MAPPING
    assert "not a json object" in caplog.text.lower()


def test_unknown_severity_fallback(tmp_path, monkeypatch, caplog):
    bad = tmp_path / "unknown_sev.json"
    bad.write_text(json.dumps({"unknown_level": "critical"}))
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(bad))

    with caplog.at_level(logging.WARNING):
        mapping = load_tier_mapping()

    assert mapping == DEFAULT_TIER_MAPPING
    assert "invalid severity" in caplog.text.lower()


def test_unknown_tier_fallback(tmp_path, monkeypatch, caplog):
    bad = tmp_path / "unknown_tier.json"
    bad.write_text(json.dumps({"critical": "emergency"}))
    monkeypatch.setenv(ENV_TIER_MAPPING_PATH, str(bad))

    with caplog.at_level(logging.WARNING):
        mapping = load_tier_mapping()

    assert mapping == DEFAULT_TIER_MAPPING
    assert "invalid severity 'critical' or tier 'emergency'" in caplog.text.lower()


def test_event_and_alert_models_tier():
    event = Event(
        event_id="e1",
        seq=1,
        source="herdr",
        kind="task_failed",
        severity="critical",
        timestamp="2026-10-18T09:00:00Z",
        payload={},
    )
    assert event.tier == "critical"
    d = event.to_dict()
    assert d["tier"] == "critical"
    assert d["severity"] == "critical"

    alert = Alert(
        alert_id="a1",
        category="task_failed",
        severity="critical",
        title="Failed",
        message="Failed",
        created_at="2026-10-18T09:00:00Z",
    )
    assert alert.tier == "critical"
    ad = alert.to_dict()
    assert ad["tier"] == "critical"
    assert ad["severity"] == "critical"

