# AgentPulse

Turn an idle Android phone into a dedicated, read-only monitoring screen for the
**FirstMate + Herdr + Pi** AI coding workflow. AgentPulse is not a debug console
for a single agent — it is one global dashboard across task scheduling
(FirstMate), terminal panes (Herdr) and fine-grained agent execution (Pi).

The repository is split into stages (see the design report under
`firstmate/data/agentpulse-design/report.md`):

1. **Monitoring service** (`service/`) — a read-only service on WSL2 Debian that
   correlates the three data sources into one task → pane → session model and
   serves it over HTTP + WebSocket.
2. **Debian TUI** (`tui/`) — a terminal client (Rust + Ratatui).
3. **Android app** (`android/`) — a Kotlin + Compose dashboard reached over an
   SSH tunnel.

## Monitoring service

Install and run (Python 3.13; only FastAPI / uvicorn / websockets beyond the
standard library):

```bash
# Quick startup helper (creates .venv if missing and execs uvicorn)
./service/run.sh

# Bind to 0.0.0.0 for LAN access (WSL2 mirrored networking mode)
./service/run.sh --host 0.0.0.0 --port 7890

# Stop the service with Ctrl+C or kill <pid>
```

When bound to `0.0.0.0`, clients on the same Wi-Fi can reach the service directly at `http://<lan-ip>:7890` (e.g. `http://10.103.105.100:7890`).
The FirstMate home directory defaults to `~/agentpulse/firstmate` and can be configured with `--home <path>` or `AGENTPULSE_HOME` / `FIRSTMATE_HOME`.
Host and port can also be configured with `AGENTPULSE_HOST` / `HOST` and `AGENTPULSE_PORT` / `PORT`.

Verify:

```bash
curl -s http://127.0.0.1:7890/api/v1/health
curl -s http://127.0.0.1:7890/api/v1/overview
curl -s http://127.0.0.1:7890/api/v1/tasks
```

See [`service/README.md`](service/README.md) for the full API, layout and
degradation guarantees.

## Deployment and CLI management

Manage both the monitoring service (port 7890) and APK download server (port 7891) via the `pulse` command.

```bash
# Install pulse to ~/.local/bin/pulse
./deploy/install.sh

# Manage services
pulse start
pulse status
pulse stop
```

See [`deploy/README.md`](deploy/README.md) for full usage, subcommands, firewall management, and log viewing.

## Debian TUI

Read-only terminal dashboard (Rust + Ratatui) providing symmetric observability
across task scheduling, Herdr panes, and Pi activity directly in your terminal.
Connects to the monitoring service via local HTTP and WebSocket.

```bash
cd tui
cargo build --release
./target/release/agentpulse-tui --url http://127.0.0.1:7890
```

See [`tui/README.md`](tui/README.md) for keybindings, layout dimensions, and
product positioning.

## Android dashboard

Four screens (simplified Chinese UI, English paths / identifiers kept as-is):
**总览** (Overview), **任务** (Tasks), **动态** (Live), **告警** (Alerts).
Kotlin + Jetpack Compose + Material 3, single Activity, `android/` only.

Prerequisites: JDK 17+, the Android SDK (35), and Gradle 8.x. On this machine
the toolchain is at `~/gradle/bin/gradle` and `~/android-sdk`, and the Gradle /
Maven repositories are only reachable through a local HTTP proxy, so
`android/gradle.properties` carries the proxy settings. `android/local.properties`
is intentionally not committed (write `sdk.dir=<path>` there).

```bash
cd android
~/gradle/bin/gradle :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
~/gradle/bin/gradle :app:testDebugUnitTest
```

## SSH tunnel from the phone

The service binds `127.0.0.1:7890` only, so the phone reaches it through a local
port forward:

```bash
# USB debugging: the default settings work as-is
adb reverse tcp:7890 tcp:7890

# Wi-Fi / SSH: local port forward
ssh -N -L 7890:127.0.0.1:7890 <user>@<host>
```

Then point the Android app at `http://127.0.0.1:7890`.

## Tests

```bash
.venv/bin/python -m pytest -q
```

## Documentation

- Runbook: [`docs/runbook.md`](docs/runbook.md) (end-to-end operational guide covering deployment, daily `pulse` CLI management, network policies, and troubleshooting).
- Service: [`service/README.md`](service/README.md)
- TUI: [`tui/README.md`](tui/README.md)
- Design report: `firstmate/data/agentpulse-design/report.md` (authoritative
  spec for field names, endpoint shapes and correlation rules).
