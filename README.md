# AgentPulse

AgentPulse turns an idle Android phone into a dedicated, read-only monitoring screen for the
FirstMate + Herdr + Pi AI coding workflow. This repository contains the Android native dashboard
(phase 3 of the [design report](firstmate/data/agentpulse-design/report.md)); the monitoring
service and the Debian TUI live in sibling branches.

The Android app is **read-only**: it consumes the monitoring service's REST + WebSocket surface
and never issues commands, edits the workflow, or controls any agent.

## Android 监控看板（Android dashboard）

Four screens, all in simplified Chinese UI with English paths / identifiers / log lines kept as-is:

- **总览** (Overview) — global metrics, active tasks, pending decisions, queued backlog.
- **任务** (Tasks) — the FirstMate task queue with state filters and per-task drill-down (worktree,
  branch, Herdr pane, ANSI terminal tail).
- **动态** (Live) — a virtualized event timeline that auto-scrolls and pauses on user scroll.
- **告警** (Alerts) — needs-decision / failures / disconnects, each with a copy-command affordance.

### Build

The app is Kotlin + Jetpack Compose + Material 3, single Activity, `android/` only
(compileSdk 35, minSdk 26, targetSdk 35, Kotlin 2.x with the Compose compiler plugin, version
catalog in `gradle/libs.versions.toml`).

Prerequisites on the build host: JDK 17+, the Android SDK (35), and Gradle 8.x. On this machine
the toolchain is at `~/gradle/bin/gradle` and `~/android-sdk`, and the Gradle/Maven repositories are
only reachable through a local HTTP proxy, so `android/gradle.properties` carries
`systemProp.{http,https}.proxy{Host,Port}=127.0.0.1:7897`.

`android/local.properties` is intentionally **not committed** (it is in `android/.gitignore`); write
`sdk.dir=<path-to-android-sdk>` there, or export the SDK via your IDE. There is no Gradle wrapper
JAR committed either — invoke your local Gradle directly:

```bash
cd android
~/gradle/bin/gradle :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
~/gradle/bin/gradle :app:testDebugUnitTest    # JVM tests: DTO deserialization, connection state, event merge
```

### Open the tunnel to the monitoring service

The monitoring service binds `127.0.0.1:7890` on the WSL2/Debian host. The app does **not** run an
SSH tunnel in v1 — establish the tunnel outside the app, then point the app's settings at the loop
endpoint (the default is already `127.0.0.1:7890`).

Over USB debugging (simplest — the default settings work as-is):

```bash
adb reverse tcp:7890 tcp:7890
```

Over Wi-Fi / adb-over-TCP, forward a local port on the phone via SSH to the host's service:

```bash
ssh -L 7890:127.0.0.1:7890 <user>@<host>
```

The host / port / WebSocket path / API prefix are all editable in-app (设置 / Settings → 连接设置),
and changes reconnect immediately.

REST endpoints and the WebSocket contract the app speaks are specified in report chapter 3:
`GET /api/v1/health`, `/api/v1/overview`, `/api/v1/tasks`, `/api/v1/tasks/{id}/terminal`,
`/api/v1/events?since_seq=`, and `ws://127.0.0.1:7890/ws` with heartbeat ping/pong.
