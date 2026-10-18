"""Entity correlation (report section 2.3).

Join priority for a FirstMate task:

1. ``herdr_pane_id`` from ``state/<id>.meta`` -> the Herdr pane with that id.
2. Fallback when the key is missing: scan Herdr workspaces for a label
   matching ``└ <task_id> · ...``, then any pane whose ``foreground_cwd``
   equals the task worktree.
3. Pi session: only when the task harness is ``pi``. The worktree path is
   encoded to ``--<encoded-path>--`` and the newest ``*.jsonl`` in that
   directory is used. For any other harness ``active_agent_session_id`` is an
   explicit ``null`` -- the service never fabricates a session.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .models import TerminalPane


def encode_cwd(path: str) -> str:
    """Encode a cwd the way Pi names its session directories.

    ``/home/Yuhang/agentpulse/firstmate`` -> ``--home-Yuhang-agentpulse-firstmate--``

    The leading ``/`` is dropped before substitution, matching the on-disk
    layout (``--home-…``, never ``---home-…``).
    """
    text = str(path)
    if not text.startswith("/"):
        text = "/" + text
    return "--" + text.lstrip("/").replace("/", "-") + "--"


def session_id_from_path(path: str | Path) -> str | None:
    """Extract the session UUID from a Pi JSONL filename."""
    name = Path(path).name
    if not name.endswith(".jsonl"):
        return None
    stem = name[: -len(".jsonl")]
    _, _, tail = stem.partition("_")
    return tail or stem or None


def newest_pi_session(sessions_root: str | Path, worktree: str | None) -> Path | None:
    """Newest ``*.jsonl`` session file for a worktree, or ``None``."""
    if not worktree:
        return None
    directory = Path(sessions_root) / encode_cwd(worktree)
    if not directory.is_dir():
        return None
    candidates = [p for p in directory.glob("*.jsonl") if p.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda p: (p.stat().st_mtime, p.name))


def workspace_label_pattern(task_id: str) -> re.Pattern[str]:
    """The documented workspace label regex: ``└ <task_id> · .*``."""
    return re.compile(rf"└\s+{re.escape(task_id)}\s+·")


@dataclass
class Correlation:
    pane_id: str | None = None
    pane_source: str | None = None  # meta | workspace_label | foreground_cwd
    session_id: str | None = None
    session_path: str | None = None

    def pane_online(self, panes: dict[str, TerminalPane]) -> bool:
        return bool(self.pane_id and self.pane_id in panes)


def join_pane(
    task_id: str,
    herdr_pane_id: str | None,
    worktree: str | None,
    panes: dict[str, TerminalPane],
    workspaces: list[dict[str, str]] | None = None,
) -> tuple[str | None, str | None]:
    """Return ``(pane_id, join_source)`` for a task.

    ``workspaces`` entries need ``workspace_id`` and ``label`` keys.
    """
    # 1. Deterministic key straight from the task metadata.
    if herdr_pane_id:
        return herdr_pane_id, "meta"

    # 2. Fuzzy fallback: workspace label "└ <task_id> · ...".
    pattern = workspace_label_pattern(task_id)
    for workspace in workspaces or []:
        label = workspace.get("label") or ""
        if not pattern.search(label):
            continue
        workspace_id = workspace.get("workspace_id")
        members = [p for p in panes.values() if p.workspace_id == workspace_id]
        if not members:
            continue
        if worktree:
            for pane in members:
                if pane.foreground_cwd == worktree:
                    return pane.pane_id, "workspace_label"
        focused = [p for p in members if p.focused]
        pane = focused[0] if focused else members[0]
        return pane.pane_id, "workspace_label"

    # 3. Fuzzy fallback: foreground cwd equality with the task worktree.
    if worktree:
        for pane in panes.values():
            if pane.foreground_cwd == worktree:
                return pane.pane_id, "foreground_cwd"

    return None, None


def join_session(
    harness: str | None,
    worktree: str | None,
    sessions_root: str | Path,
) -> tuple[str | None, str | None]:
    """Return ``(session_id, session_path)``; ``(None, None)`` off-Pi."""
    if (harness or "").strip().lower() != "pi":
        return None, None
    path = newest_pi_session(sessions_root, worktree)
    if path is None:
        return None, None
    return session_id_from_path(path), str(path)


def correlate(
    task_id: str,
    herdr_pane_id: str | None,
    worktree: str | None,
    harness: str | None,
    panes: dict[str, TerminalPane],
    workspaces: list[dict[str, str]] | None,
    sessions_root: str | Path,
) -> Correlation:
    pane_id, pane_source = join_pane(task_id, herdr_pane_id, worktree, panes, workspaces)
    session_id, session_path = join_session(harness, worktree, sessions_root)
    return Correlation(
        pane_id=pane_id,
        pane_source=pane_source,
        session_id=session_id,
        session_path=session_path,
    )


@dataclass
class SessionRef:
    """A resolved Pi session file (id + path)."""

    session_id: str
    path: str


def newest_session(sessions_root: str | Path, worktree: str | None) -> SessionRef | None:
    """Newest ``*.jsonl`` Pi session for a worktree (report 2.3, step 2)."""
    path = newest_pi_session(sessions_root, worktree)
    if path is None:
        return None
    session_id = session_id_from_path(path)
    if not session_id:
        return None
    return SessionRef(session_id=session_id, path=str(path))


def resolve_pane_id(
    task_id: str,
    meta_pane_id: str | None,
    worktree: str | None,
    panes: dict[str, TerminalPane],
    workspaces: list[dict[str, str]] | None = None,
) -> str | None:
    """Report 2.3 pane join, reduced to its ``(pane_id, source)`` result."""
    pane_id, _source = join_pane(task_id, meta_pane_id, worktree, panes, workspaces)
    return pane_id
