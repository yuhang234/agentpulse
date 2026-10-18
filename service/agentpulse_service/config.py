"""Runtime configuration for the AgentPulse monitoring service."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from . import __version__


def _default_state_dir() -> Path:
    base = os.environ.get("XDG_STATE_HOME")
    if base:
        return Path(base) / "agentpulse"
    return Path.home() / ".local" / "state" / "agentpulse"


DEFAULT_FM_HOME = Path.home() / "agentpulse" / "firstmate"
DEFAULT_HERDR_SOCKET = Path.home() / ".config" / "herdr" / "herdr.sock"
DEFAULT_PI_SESSIONS = Path.home() / ".pi" / "agent" / "sessions"


@dataclass
class Settings:
    """Everything the service needs to know about its environment.

    ``home`` is the FirstMate home. It is opened read-only; the service never
    writes, moves or locks anything underneath it.
    """

    host: str = "127.0.0.1"
    port: int = 7890
    home: Path = field(default_factory=lambda: DEFAULT_FM_HOME)
    db_path: Path = field(default_factory=lambda: _default_state_dir() / "agentpulse.db")
    herdr_bin: str = "herdr"
    herdr_socket: Path = field(default_factory=lambda: DEFAULT_HERDR_SOCKET)
    pi_sessions_root: Path = field(default_factory=lambda: DEFAULT_PI_SESSIONS)
    version: str = __version__

    # Polling cadence (seconds). The Herdr socket event stream is push based;
    # the intervals below are the fallback polling cadence documented in
    # service/README.md.
    interval_firstmate: float = 3.0
    interval_fleet_snapshot: float = 15.0
    interval_herdr: float = 5.0
    interval_pane_output: float = 10.0
    interval_pi: float = 2.0
    interval_retention: float = 300.0

    # Event store
    ring_size: int = 1000
    retention_days: int = 7
    retention_max_rows: int = 50_000
    replay_batch: int = 500

    # When false the app starts without collector loops (used by tests).
    autostart: bool = True

    @property
    def home_summary_path(self) -> Path:
        return self.home / "state" / "home-summary.json"

    @property
    def state_dir(self) -> Path:
        return self.home / "state"

    @property
    def fleet_snapshot_script(self) -> Path:
        return self.home / "bin" / "fm-fleet-snapshot.sh"
