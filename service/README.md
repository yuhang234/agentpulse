# AgentPulse monitoring service

Read-only monitoring service for the FirstMate + Herdr + Pi AI coding workflow.
It turns the three data sources into one correlated task/pane/session model and
serves it over a local HTTP + WebSocket API, per the design report chapters 1-3
and 6.1 stage 1 (`firstmate/data/agentpulse-design/report.md`).

This is the **service core** (stage 1). The Debian TUI (stage 2) and the
Android app (stage 3) are out of scope here.

## What it is

- **Collectors** (all strictly read-only):

  | Source    | Reads                                                                                   |
  | --------- | --------------------------------------------------------------------------------------- |
  | FirstMate | `state/*.meta`, `state/*.status` tails, `state/home-summary.json`, `bin/fm-fleet-snapshot.sh --json` |
  | Herdr     | `api snapshot`, `agent list`, `agent get`, `pane process-info`, `pane read`, socket `events.subscribe` stream (falls back to polling) |
  | Pi        | newest `~/.pi/agent/sessions/--<encoded-cwd>--/*.jsonl` per task worktree               |

  Nothing under the FirstMate home is ever written, moved or locked, and no
  Herdr mutating/lifecycle command is ever issued.

- **Event store**: an in-memory ring buffer (1000 events per task) plus SQLite
  persistence with the report's dedup keys and a 7 day / 50 000 row retention
  sweep. Every event carries a strictly increasing global `seq`.

- **API**: `GET /api/v1/health`, `/overview`, `/tasks`, `/tasks/{id}/terminal`,
  `/events` and a `ws://…/ws` WebSocket (replay → `live_ready` → live push,
  ping/pong, per-client `since_seq` resume).

## Install & run

Install and run (Python 3.13, standard library except FastAPI/uvicorn/websockets).

```bash
# Repeatable startup helper (creates .venv if missing and execs uvicorn)
./service/run.sh

# Or bind 0.0.0.0 for direct LAN access across machines on the same Wi-Fi
./service/run.sh --host 0.0.0.0 --port 7890

# Or run module directly
PYTHONPATH=service .venv/bin/python -m agentpulse_service \
    --home /home/Yuhang/agentpulse/firstmate \
    --host 127.0.0.1 --port 7890

# Stop the service with Ctrl+C or kill <pid>
```

`--home` defaults to `~/agentpulse/firstmate` (override with `--home` or `$AGENTPULSE_HOME` / `$FIRSTMATE_HOME`).
The SQLite store defaults to `~/.local/state/agentpulse/agentpulse.db` (override with `--db-path` or `$AGENTPULSE_DB_PATH`).
Host and port can also be set via `AGENTPULSE_HOST` / `HOST` and `AGENTPULSE_PORT` / `PORT`.

## LAN binding & Android access

Under WSL2 mirrored networking mode, binding to `0.0.0.0` exposes the port directly to the local area network.
Clients on the same Wi-Fi network (such as the Android phone) can connect directly to `http://<lan-ip>:7890` (for example, `http://10.103.105.100:7890` on this host).
If a local proxy is active in your terminal, bypass it for LAN addresses (e.g. `curl --noproxy '*'`).

Alternatively, the phone can reach the local service through an SSH tunnel or ADB reverse port forward:

```bash
# USB debugging:
adb reverse tcp:7890 tcp:7890

# SSH local port forward:
ssh -N -L 7890:127.0.0.1:7890 <user>@<windows-or-wsl2-host>
```

The Android app then talks to `http://127.0.0.1:7890` over the tunnel.

## Tests

```bash
PYTHONPATH=service .venv/bin/python -m pytest service/tests -q
```

or from the repo root:

```bash
.venv/bin/python -m pytest -q
```

## Layout

```
service/
  agentpulse_service/
    collectors/       # firstmate.py, herdr.py, pi.py (read-only)
    api.py            # REST + WebSocket (report ch. 3)
    monitor.py        # async orchestrator + pure task/pane mapping
    store.py          # ring buffer + SQLite event store
    bus.py            # WebSocket fan-out
    world.py          # shared aggregate state + report 2.3 linking
    correlation.py    # task <-> pane <-> session join
    alerts.py         # report 2.2 D alert derivation
    severity.py       # report 2.4 severity ladder + dedup keys
    models.py         # Task / TerminalPane / Event / Alert entities
```

## Degradation guarantees

- **Non-Pi harnesses** (`opencode`, `agy`, `codex`, …): there is no Pi session
  file, so `active_agent_session_id` is an explicit `null`; tool-call/thinking
  events are simply absent, never fabricated.
- **Herdr socket unavailable**: the collector falls back to `api snapshot`
  polling at the documented interval (`interval_herdr`, default 5 s).
- **Missing fleet snapshot / meta**: the service degrades to whatever it can
  read and reports collector health as `degraded`/`error` rather than guessing.
