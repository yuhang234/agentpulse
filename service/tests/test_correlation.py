"""Correlation precedence (report 2.3) and task state mapping."""

from agentpulse_service.correlation import (
    join_pane,
    join_session,
    newest_session,
    resolve_pane_id,
)
from agentpulse_service.models import TerminalPane


def _pane(pane_id, workspace_id, foreground_cwd=None, focused=False):
    return TerminalPane(
        pane_id=pane_id,
        workspace_id=workspace_id,
        foreground_cwd=foreground_cwd or "",
        focused=focused,
        agent_status="working",
    )


def test_meta_pane_id_wins():
    panes = {"w1:p1": _pane("w1:p1", "w1")}
    pane_id, source = join_pane("t", "w1:p1", None, panes, None)
    assert pane_id == "w1:p1"
    assert source == "meta"


def test_workspace_label_fallback():
    panes = {
        "w2:p1": _pane("w2:p1", "w2", foreground_cwd="/w/t"),
        "w2:p2": _pane("w2:p2", "w2", focused=True),
    }
    workspaces = [{"workspace_id": "w2", "label": "└ t · ship"}]
    pane_id, source = join_pane("t", None, "/w/t", panes, workspaces)
    assert pane_id == "w2:p1"  # worktree cwd match inside the labelled workspace
    assert source == "workspace_label"


def test_workspace_label_fallback_no_cwd_uses_focused():
    panes = {
        "w2:p1": _pane("w2:p1", "w2", foreground_cwd="/other"),
        "w2:p2": _pane("w2:p2", "w2", focused=True),
    }
    workspaces = [{"workspace_id": "w2", "label": "└ t · ship"}]
    pane_id, source = join_pane("t", None, "/w/t", panes, workspaces)
    assert pane_id == "w2:p2"
    assert source == "workspace_label"


def test_foreground_cwd_fallback():
    panes = {
        "w3:p1": _pane("w3:p1", "w3", foreground_cwd="/w/x"),
        "w3:p2": _pane("w3:p2", "w3", foreground_cwd="/w/t"),
    }
    pane_id, source = join_pane("t", None, "/w/t", panes, None)
    assert pane_id == "w3:p2"
    assert source == "foreground_cwd"


def test_no_match_returns_none():
    panes = {"w4:p1": _pane("w4:p1", "w4", foreground_cwd="/elsewhere")}
    assert join_pane("t", None, "/w/t", panes, None) == (None, None)


def test_resolve_pane_id_wrapper():
    panes = {"w1:p1": _pane("w1:p1", "w1")}
    assert resolve_pane_id("t", "w1:p1", None, panes) == "w1:p1"
    assert resolve_pane_id("t", None, None, panes) is None


def test_join_session_non_pi_is_null(tmp_path):
    assert join_session("opencode", "/w/t", tmp_path) == (None, None)
    assert join_session(None, "/w/t", tmp_path) == (None, None)


def test_newest_session_for_pi(tmp_path):
    from agentpulse_service.util import encode_pi_cwd

    directory = tmp_path / encode_pi_cwd("/w/t")
    directory.mkdir(parents=True)
    older = directory / "2026-10-18T08-00-00-000Z_01a14e00-0000-0000-0000-000000000001.jsonl"
    newer = directory / "2026-10-18T09-00-00-000Z_01a14e00-0000-0000-0000-000000000002.jsonl"
    older.write_text("{}\n")
    newer.write_text("{}\n")

    ref = newest_session(tmp_path, "/w/t")
    assert ref is not None
    assert ref.session_id == "01a14e00-0000-0000-0000-000000000002"


def test_newest_session_no_dir(tmp_path):
    assert newest_session(tmp_path, "/w/t") is None


def test_session_id_from_filename():
    from agentpulse_service.util import pi_session_id_from_filename

    assert (
        pi_session_id_from_filename("2026-10-18T08-01-04-248Z_01a14e07-4ef6-73d2-92d7-565b7426ee84.jsonl")
        == "01a14e07-4ef6-73d2-92d7-565b7426ee84"
    )


def test_normalize_pane_id():
    from agentpulse_service.util import normalize_pane_id

    assert normalize_pane_id("w1Q:p2") == "w1Q:p2"
    assert normalize_pane_id("default:w1Q:p2") == "w1Q:p2"
    assert normalize_pane_id("default:w1F:p2") == "w1F:p2"
    assert normalize_pane_id("default:w16:p13") == "w16:p13"
    assert normalize_pane_id("default:w16:pB") == "w16:pB"
    assert normalize_pane_id("custom:session:w1P:p0") == "w1P:p0"
    assert normalize_pane_id("invalid") is None
    assert normalize_pane_id("") is None
    assert normalize_pane_id(None) is None

