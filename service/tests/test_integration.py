"""Integration smoke tests against this machine's live FirstMate home + Herdr.

These are opt-in: they skip cleanly (``pytest.skip``) when the live data sources
are absent, so ``python -m pytest`` still passes on any machine. They exercise
the *read-only* collector paths only and never mutate anything.
"""

from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

import pytest

from agentpulse_service.collectors.firstmate import FirstMateCollector, parse_status_line
from agentpulse_service.collectors.herdr import HerdrCollector
from agentpulse_service.config import Settings

LIVE_HOME = Path("/home/Yuhang/agentpulse/firstmate")


def test_live_firstmate_meta_readable():
    if not (LIVE_HOME / "state").is_dir():
        pytest.skip("live FirstMate home not present")
    collector = FirstMateCollector(Settings(home=LIVE_HOME))
    meta = collector.read_meta()
    if not meta:
        pytest.skip("no state/*.meta files in live home")
    assert all(isinstance(values, dict) for values in meta.values())
    # The herdr join key is the report 2.3 first-class key when present.
    keys = {v.get("herdr_pane_id") for v in meta.values() if v.get("herdr_pane_id")}
    assert keys  # at least one task is pinned to a pane


def test_live_firstmate_status_parse():
    if not (LIVE_HOME / "state").is_dir():
        pytest.skip("live FirstMate home not present")
    status_files = sorted((LIVE_HOME / "state").glob("*.status"))
    if not status_files:
        pytest.skip("no state/*.status files in live home")

    # Some FirstMate-internal files (e.g. ``.startup-network.status``) are not
    # task status logs; skip over them and find the first real status line.
    parsed = None
    for path in status_files:
        for raw in path.read_text().splitlines():
            candidate = parse_status_line("x", raw.strip())
            if candidate is not None:
                parsed = candidate
                break
        if parsed is not None:
            break
    if parsed is None:
        pytest.skip("no parseable status lines in live home")
    assert parsed.state in {
        "working", "needs-decision", "blocked", "paused", "done", "failed", "resolved",
    }


def test_live_herdr_agent_list():
    if shutil.which("herdr") is None:
        pytest.skip("herdr not on PATH")

    async def run():
        collector = HerdrCollector(Settings(home=LIVE_HOME))
        agents = await collector.agent_list()
        return collector, agents

    try:
        collector, agents = asyncio.run(run())
    except Exception as exc:  # noqa: BLE001 - socket unavailable, not a failure
        pytest.skip(f"herdr unavailable: {exc}")
    assert isinstance(agents, list)
    for agent in agents:
        assert "pane_id" in agent
        assert "agent_status" in agent


def test_live_pi_sessions_readable():
    from agentpulse_service.collectors.pi import PiCollector
    sessions_root = Path.home() / ".pi" / "agent" / "sessions"
    if not sessions_root.is_dir():
        pytest.skip("Pi sessions root not present")
    collector = PiCollector(Settings(home=LIVE_HOME))
    jsonl_files = list(sessions_root.glob("*/*.jsonl"))
    if not jsonl_files:
        pytest.skip("no Pi session files found")
    records = collector.read_new_records(jsonl_files[0])
    assert isinstance(records, list)
    if records:
        events = collector.ingest_records("test-session", records[:20])
        assert isinstance(events, list)


def test_settings_env_and_path_conversion(monkeypatch):
    monkeypatch.setenv("AGENTPULSE_HOME", str(LIVE_HOME))
    monkeypatch.setenv("AGENTPULSE_HOST", "0.0.0.0")
    monkeypatch.setenv("AGENTPULSE_PORT", "9999")
    settings = Settings(home=str(LIVE_HOME))
    assert isinstance(settings.home, Path)
    assert settings.home == LIVE_HOME
    assert settings.host == "0.0.0.0"
    assert settings.port == 9999

