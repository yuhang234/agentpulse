"""Task construction + state mapping (monitor.build_tasks / map_task_state)."""

from agentpulse_service.monitor import build_tasks, map_task_state


def _fleet(records, live_tasks=None):
    return {
        "generated": "2026-10-18T09:00:00Z",
        "backlog": {"records": records},
        "tasks": live_tasks or [],
    }


def test_map_task_state_queued_lifecycle():
    assert map_task_state("queued", None) == "queued"
    assert map_task_state("in_flight", None) == "in_flight"
    assert map_task_state("done", None) == "done"
    assert map_task_state("blocked", None) == "blocked"
    assert map_task_state("held", None) == "held"


def test_map_task_state_failed_overrides():
    assert map_task_state("in_flight", "failed") == "failed"


def test_map_task_state_needs_decision_becomes_held():
    assert map_task_state("in_flight", "needs-decision") == "held"


def test_map_task_state_captain_hold_becomes_held():
    assert map_task_state("queued", None, "captain") == "held"


def test_build_tasks_from_fleet():
    fleet = _fleet(
        [
            {
                "id": "t1",
                "title": "Task one",
                "state": "in_flight",
                "kind": "ship",
                "repo": "agentpulse",
                "blocked_by": None,
                "blocked_by_ids": [],
                "body_lines": ["do the thing"],
                "since": "2026-10-18",
            }
        ],
        live_tasks=[
            {
                "id": "t1",
                "harness": "opencode",
                "branch": "fm/t1",
                "paths": {"worktree": {"path": "/w/t1"}},
                "current_state": {"state": "working", "detail": "busy", "source": "pane"},
            }
        ],
    )
    meta = {
        "t1": {
            "herdr_pane_id": "w1:p1",
            "harness": "opencode",
            "model": "default",
            "worktree": "/w/t1",
        }
    }
    tasks = build_tasks(fleet, meta, now="2026-10-18T09:00:00Z")
    assert len(tasks) == 1
    t = tasks[0]
    assert t.id == "t1"
    assert t.state == "in_flight"
    assert t.kind == "ship"
    assert t.repo == "agentpulse"
    assert t.description == "do the thing"
    assert t.worktree == "/w/t1"
    assert t.meta_pane_id == "w1:p1"
    assert t.crew_state == "working"
    assert t.current_doing == "busy"
    assert t.model is None  # "default" is a placeholder, normalised away


def test_build_tasks_model_kept_when_real():
    fleet = _fleet([{"id": "t2", "title": "T2", "state": "queued", "kind": "scout", "repo": "r", "since": "2026-10-18"}])
    meta = {"t2": {"herdr_pane_id": "", "harness": "pi", "model": "deepseek-v4.1"}}
    tasks = build_tasks(fleet, meta, now="2026-10-18T09:00:00Z")
    assert tasks[0].model == "deepseek-v4.1"


def test_build_tasks_empty_fleet_yields_nothing():
    assert build_tasks(None, {}) == []
