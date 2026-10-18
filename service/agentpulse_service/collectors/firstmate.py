"""FirstMate collector: task metadata, status logs, home summary and the fleet snapshot.

Strictly read-only. Nothing underneath the FirstMate home is ever written,
moved, locked, or otherwise mutated.
"""

from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

from ..clock import epoch_now
from ..config import Settings
from .base import CollectorHealth

# ``{state} [key=<slug>] [at=<epoch>]: {note}`` (report 1.1 B).
STATUS_LINE_RE = re.compile(
    r"^(?P<state>[A-Za-z][\w-]*)"
    r"\s*(?:\[key=(?P<key>[^\]]*)\]\s*)?"
    r"(?:\[at=(?P<at>\d+)\]\s*)?"
    r":\s?(?P<note>.*)$"
)

MAX_STATUS_READ = 256 * 1024


@dataclass
class StatusLine:
    """One parsed line of ``state/<task_id>.status``."""

    task_id: str
    state: str
    key: str | None
    at_epoch: int | None
    note: str
    raw: str


def parse_status_line(task_id: str, raw: str) -> StatusLine | None:
    raw = raw.strip()
    if not raw:
        return None
    match = STATUS_LINE_RE.match(raw)
    if not match:
        return None
    at = match.group("at")
    return StatusLine(
        task_id=task_id,
        state=match.group("state"),
        key=match.group("key") or None,
        at_epoch=int(at) if at else None,
        note=match.group("note").strip(),
        raw=raw,
    )


class FirstMateCollector:
    name = "firstmate"

    def __init__(
        self,
        settings: Settings,
        snapshot_runner: Callable[[], Awaitable[str]] | None = None,
    ) -> None:
        self.settings = settings
        self.health = CollectorHealth()
        self._snapshot_runner = snapshot_runner
        self._offsets: dict[str, int] = {}
        self._remainders: dict[str, str] = {}

    # -- metadata -------------------------------------------------------

    def read_meta(self) -> dict[str, dict[str, str]]:
        """All ``state/*.meta`` key/value records, keyed by task id."""
        records: dict[str, dict[str, str]] = {}
        state_dir = self.settings.state_dir
        if not state_dir.is_dir():
            self.health.mark_error(f"state directory missing: {state_dir}")
            return records
        for path in sorted(state_dir.glob("*.meta")):
            task_id = path.name[: -len(".meta")]
            values: dict[str, str] = {}
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                self.health.detail = f"{path}: {exc}"
                continue
            for line in text.splitlines():
                key, sep, value = line.partition("=")
                if sep:
                    values[key.strip()] = value.strip()
            records[task_id] = values
        return records

    # -- status log tails ----------------------------------------------

    def read_status_tail(self) -> list[StatusLine]:
        """New status lines appended since the previous call."""
        lines: list[StatusLine] = []
        state_dir = self.settings.state_dir
        if not state_dir.is_dir():
            return lines
        for path in sorted(state_dir.glob("*.status")):
            task_id = path.name[: -len(".status")]
            for chunk in self._read_new_text(path):
                parsed = parse_status_line(task_id, chunk)
                if parsed is not None:
                    lines.append(parsed)
        return lines

    def _read_new_text(self, path: Path) -> list[str]:
        key = str(path)
        try:
            size = path.stat().st_size
        except OSError:
            return []
        offset = self._offsets.get(key, 0)
        if size < offset:  # truncated or rotated
            offset = 0
            self._remainders.pop(key, None)
        if offset == 0 and size > MAX_STATUS_READ:
            offset = size - MAX_STATUS_READ
            self._remainders.pop(key, None)
        try:
            with open(path, "rb") as handle:
                handle.seek(offset)
                data = handle.read()
        except OSError:
            return []
        self._offsets[key] = offset + len(data)
        text = self._remainders.get(key, "") + data.decode("utf-8", "replace")
        parts = text.split("\n")
        self._remainders[key] = parts.pop()
        return [line for line in parts if line.strip()]

    # -- JSON surfaces --------------------------------------------------

    def read_home_summary(self) -> dict[str, Any] | None:
        path = self.settings.home_summary_path
        try:
            with open(path, "r", encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError) as exc:
            self.health.detail = f"home-summary.json: {exc}"
            return None
        return data if isinstance(data, dict) else None

    async def read_fleet_snapshot(self) -> dict[str, Any] | None:
        script = self.settings.fleet_snapshot_script
        if self._snapshot_runner is not None:
            text = await self._snapshot_runner()
        else:
            if not script.exists():
                self.health.mark_error(f"fleet snapshot script missing: {script}")
                return None
            if script.stat().st_mode & 0o111:
                argv = [str(script), "--json"]
            else:
                argv = ["bash", str(script), "--json"]
            try:
                proc = await asyncio.create_subprocess_exec(
                    *argv,
                    cwd=str(self.settings.home),
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
                stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=30)
            except (OSError, asyncio.TimeoutError) as exc:
                self.health.mark_error(f"fleet snapshot failed: {exc}")
                return None
            if proc.returncode != 0:
                detail = (stderr or b"").decode("utf-8", "replace").strip()
                self.health.mark_error(f"fleet snapshot rc={proc.returncode}: {detail}")
                return None
            text = stdout.decode("utf-8", "replace")
        try:
            data = json.loads(text)
        except ValueError as exc:
            self.health.mark_error(f"fleet snapshot not JSON: {exc}")
            return None
        if not isinstance(data, dict):
            self.health.mark_error("fleet snapshot JSON is not an object")
            return None
        return data

    # -- combined poll ---------------------------------------------------

    def mark_synced(self) -> None:
        self.health.mark_ok(epoch_now())
