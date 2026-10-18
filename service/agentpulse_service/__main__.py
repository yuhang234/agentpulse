"""``python -m agentpulse_service`` entry point.

Runs the monitoring service bound to ``127.0.0.1:7890`` by default, serving the
REST + WebSocket surface from :mod:`agentpulse_service.api`. The FirstMate home
is opened strictly read-only.
"""

from __future__ import annotations

import argparse
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Sequence

import uvicorn

from .api import create_app
from .bus import EventBus
from .config import (
    DEFAULT_FM_HOME,
    Settings,
    _default_fm_home,
    _default_host,
    _default_port,
)
from .monitor import Monitor
from .store import EventStore
from .world import World


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="agentpulse_service",
        description="AgentPulse read-only monitoring service for FirstMate + Herdr + Pi.",
    )
    parser.add_argument("--host", default=_default_host(), help="bind address (default 127.0.0.1 or $AGENTPULSE_HOST)")
    parser.add_argument("--port", type=int, default=_default_port(), help="bind port (default 7890 or $AGENTPULSE_PORT)")
    parser.add_argument(
        "--home",
        type=Path,
        default=None,
        help="FirstMate home directory (default ~/agentpulse/firstmate or $AGENTPULSE_HOME)",
    )
    parser.add_argument(
        "--db-path",
        type=Path,
        default=None,
        help="SQLite event store path (default ~/.local/state/agentpulse/agentpulse.db or $AGENTPULSE_DB_PATH)",
    )
    return parser.parse_args(argv)


def build(args: argparse.Namespace) -> tuple[object, Settings]:
    home_dir = args.home if args.home else _default_fm_home()
    settings = Settings(
        host=args.host,
        port=args.port,
        home=home_dir,
    )
    if args.db_path:
        settings.db_path = args.db_path

    store = EventStore(
        settings.db_path,
        ring_size=settings.ring_size,
        retention_days=settings.retention_days,
        retention_max_rows=settings.retention_max_rows,
    )
    bus = EventBus()
    world = World(sessions_root=settings.pi_sessions_root)
    monitor = Monitor(settings, store, bus, world)

    @asynccontextmanager
    async def lifespan(app):  # noqa: ANN001 - FastAPI lifespan contract
        await monitor.start()
        try:
            yield
        finally:
            await monitor.stop()

    app = create_app(monitor, lifespan=lifespan)
    return app, settings


def app_factory() -> object:
    """FastAPI application factory for uvicorn --factory."""
    args = parse_args([])
    application, _ = build(args)
    return application


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    app, settings = build(args)
    uvicorn.run(app, host=settings.host, port=settings.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
