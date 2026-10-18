"""Shared pytest fixtures.

Unit tests build their own FirstMate home and Pi session fixtures under
``tmp_path`` so they never touch the live home. The live home is only consulted
by the opt-in integration smoke test in ``test_integration.py``.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import pytest

# Safety net so ``python -m pytest service/tests`` works without the repo root
# on ``PYTHONPATH`` (pyproject.toml already sets pythonpath=service).
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from agentpulse_service.api import create_app  # noqa: E402
from agentpulse_service.bus import EventBus  # noqa: E402
from agentpulse_service.config import Settings  # noqa: E402
from agentpulse_service.monitor import Monitor  # noqa: E402
from agentpulse_service.store import EventStore  # noqa: E402
from agentpulse_service.world import World  # noqa: E402


@pytest.fixture
def make_home(tmp_path):
    """Factory writing a fake FirstMate home + optional Pi sessions under tmp_path."""

    def _make(
        meta: dict[str, dict[str, str]] | None = None,
        status: dict[str, list[str]] | None = None,
        summary: dict[str, Any] | None = None,
        fleet: dict[str, Any] | None = None,
        sessions: dict[str, list[dict[str, Any]]] | None = None,
    ) -> dict[str, Path]:
        root = tmp_path
        home = root / "firstmate"
        state = home / "state"
        state.mkdir(parents=True)
        if meta:
            for tid, kv in meta.items():
                (state / f"{tid}.meta").write_text(
                    "\n".join(f"{k}={v}" for k, v in kv.items()) + "\n",
                    encoding="utf-8",
                )
        if status:
            for tid, lines in status.items():
                (state / f"{tid}.status").write_text(
                    "\n".join(lines) + "\n", encoding="utf-8"
                )
        if summary is not None:
            (state / "home-summary.json").write_text(
                json.dumps(summary), encoding="utf-8"
            )
        if fleet is not None:
            bin_dir = home / "bin"
            bin_dir.mkdir(parents=True, exist_ok=True)
            script = bin_dir / "fm-fleet-snapshot.sh"
            script.write_text(
                "#!/usr/bin/env bash\ncat <<'__AGENTPULSE_EOF__'\n"
                + json.dumps(fleet)
                + "\n__AGENTPULSE_EOF__\n",
                encoding="utf-8",
            )
            script.chmod(0o755)

        sessions_root = root / "pi" / "agent" / "sessions"
        if sessions:
            sessions_root.mkdir(parents=True)
            for encoded_dir, records in sessions.items():
                directory = sessions_root / encoded_dir
                directory.mkdir(parents=True, exist_ok=True)
                (directory / "2026-10-18T08-01-04-248Z_01a14e07-4ef6-73d2-92d7-565b7426ee84.jsonl").write_text(
                    "\n".join(json.dumps(r) for r in records) + "\n",
                    encoding="utf-8",
                )
        return {"home": home, "state": state, "sessions_root": sessions_root}

    return _make


@pytest.fixture
def settings(make_home):
    paths = make_home()
    return Settings(
        home=paths["home"],
        db_path=":memory:",
        autostart=False,
        pi_sessions_root=paths["sessions_root"],
    )


@pytest.fixture
def make_monitor(settings):
    """Factory building a Monitor wired to in-memory store/bus and the fake home."""

    def _make():
        store = EventStore(":memory:")
        bus = EventBus()
        world = World(sessions_root=settings.pi_sessions_root)
        monitor = Monitor(settings, store, bus, world)
        return monitor, store, bus, world

    return _make


@pytest.fixture
def app(settings, make_monitor):
    monitor, _store, _bus, _world = make_monitor()
    return create_app(monitor), monitor
