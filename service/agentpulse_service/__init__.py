"""AgentPulse monitoring service.

Read-only collectors for FirstMate, Herdr and Pi, correlated into one
task/pane/session model and served over HTTP + WebSocket.

See ``service/README.md`` and ``firstmate/data/agentpulse-design/report.md``
(chapters 1-3 and 6.1) for the specification this package implements.
"""

__version__ = "1.0.0"


def app() -> object:
    """FastAPI application factory for uvicorn --factory."""
    from .__main__ import app_factory
    return app_factory()

