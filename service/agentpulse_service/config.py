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


def _default_fm_home() -> Path:
    env = os.environ.get("AGENTPULSE_HOME") or os.environ.get("FIRSTMATE_HOME")
    if env:
        return Path(env).expanduser().resolve()
    return (Path.home() / "agentpulse" / "firstmate").resolve()


def _default_host() -> str:
    return os.environ.get("AGENTPULSE_HOST") or os.environ.get("HOST") or "127.0.0.1"


def _default_port() -> int:
    raw = os.environ.get("AGENTPULSE_PORT") or os.environ.get("PORT")
    if raw:
        try:
            return int(raw)
        except ValueError:
            pass
    return 7890


def _default_db_path() -> Path:
    env = os.environ.get("AGENTPULSE_DB_PATH")
    if env:
        return Path(env).expanduser().resolve()
    return _default_state_dir() / "agentpulse.db"


DEFAULT_FM_HOME = Path.home() / "agentpulse" / "firstmate"
DEFAULT_HERDR_SOCKET = Path.home() / ".config" / "herdr" / "herdr.sock"
DEFAULT_PI_SESSIONS = Path.home() / ".pi" / "agent" / "sessions"


@dataclass
class Settings:
    """Everything the service needs to know about its environment.

    ``home`` is the FirstMate home. It is opened read-only; the service never
    writes, moves or locks anything underneath it.
    """

    host: str = field(default_factory=_default_host)
    port: int = field(default_factory=_default_port)
    home: Path = field(default_factory=_default_fm_home)
    db_path: Path = field(default_factory=_default_db_path)
    herdr_bin: str = "herdr"
    herdr_socket: Path = field(default_factory=lambda: DEFAULT_HERDR_SOCKET)
    pi_sessions_root: Path = field(default_factory=lambda: DEFAULT_PI_SESSIONS)
    version: str = __version__

    def __post_init__(self) -> None:
        if isinstance(self.home, str):
            self.home = Path(self.home).expanduser().resolve()
        elif isinstance(self.home, Path):
            self.home = self.home.expanduser().resolve()
        if isinstance(self.db_path, str):
            self.db_path = Path(self.db_path).expanduser()
        elif isinstance(self.db_path, Path):
            self.db_path = self.db_path.expanduser()
        if isinstance(self.herdr_socket, str):
            self.herdr_socket = Path(self.herdr_socket).expanduser()
        elif isinstance(self.herdr_socket, Path):
            self.herdr_socket = self.herdr_socket.expanduser()
        if isinstance(self.pi_sessions_root, str):
            self.pi_sessions_root = Path(self.pi_sessions_root).expanduser()
        elif isinstance(self.pi_sessions_root, Path):
            self.pi_sessions_root = self.pi_sessions_root.expanduser()
        if isinstance(self.port, str):
            self.port = int(self.port)

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
