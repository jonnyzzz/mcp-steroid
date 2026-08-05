---
title: "Running devrig in CI"
description: "Run devrig with an attended IDE under Xvfb/GUI, or with the supported frontendless IDEA Ultimate Remote Development backend"
weight: 55
group: "Reference"
---

devrig drives a **real** JetBrains IDE. There are two supported CI shapes:

- **Attended desktop IDE** — use the normal GUI on macOS/Windows or Xvfb on Linux.
- **Frontendless Remote Development backend** — devrig-managed IntelliJ IDEA Ultimate 2026.2
  (baseline 262) can run without an attached Remote Development client window.

Support follows IntelliJ product mode, not the presence of a client window or the raw AWT-headless flag
alone. Remote Development backends are supported even when a backend command sets that flag. Plain
non-backend headless mode remains unsupported (best-effort only; long blocking waits and deadlocks in
platform code have been observed — see [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177)).

For an attended IDE, provide a display:

- **macOS / Windows CI** — the runner already has a real GUI session. Install and run devrig
  exactly as you would locally; the IDE renders to the normal desktop.
- **Linux CI** — CI Linux boxes usually have no physical screen, so provide a **virtual** one
  with **Xvfb** (the X virtual framebuffer). The IDE renders into the virtual display and
  behaves as if a screen were attached. This is the approach the project's own integration
  tests use.

## Frontendless Remote Development backend

For an unattended Java semantic task, download the verified IDEA Ultimate build:

```bash
devrig backend download idea-ultimate --version 2026.2.0.1
```

The agent can then call `steroid_open_project`. devrig starts the native Remote Development backend on
demand with MCP Steroid installed and opens the requested path. No separate `devrig backend start` and no
client window are required.

Wait for the path through `steroid_list_projects`, retain its `project_name`, and then trigger/await the
Maven or Gradle import before semantic work. Do not make `steroid_list_windows` or a screenshot mandatory;
use them only when an attended frontend actually exists.

The verified path is frontendless IU baseline 262. The native run logged Remote Development backend mode
with `headless=false`; the Docker E2E proves operation without a Remote Development client. This does not
make arbitrary plain headless desktop launches supported. On Linux, retain the normal Xvfb test environment
unless your exact IDE build and runner have separately proved a supported backend launch without it.

## Linux: run under Xvfb

Install Xvfb, start a virtual display, point `DISPLAY` at it, then install and run devrig as
usual:

```bash
# 1. Install Xvfb (Debian/Ubuntu shown; use your distro's package)
sudo apt-get update && sudo apt-get install -y xvfb

# 2. Start a virtual X display and export DISPLAY
Xvfb :99 -screen 0 1920x1080x24 &
export DISPLAY=:99

# 3. Install devrig (brings its own Java runtime) and register your agent
curl -fsSL https://devrig.dev/install.sh | sh
devrig install claude

# 4. Provision a managed IDE — it starts against the virtual display
devrig backend download idea-community
```

With `DISPLAY` set, the managed backend (whether started explicitly with
`devrig backend start`, or automatically by `steroid_open_project`) launches against the
virtual display. Do not use `-Djava.awt.headless=true` to force a standard desktop IDE into plain headless
mode; use a supported Remote Development backend when no client window is wanted.

## macOS / Windows: use the real GUI

On macOS and Windows runners there is a real desktop session, so no virtual display is needed.
Install devrig and provision or connect to an IDE exactly as on a developer machine — see
[Getting Started](/docs/getting-started/). The IDE runs with its normal GUI.

## Notes

- Size the virtual display large enough for the IDE (e.g. `1920x1080x24`); a too-small display
  can clip dialogs and screenshots.
- Under Xvfb, also run a lightweight window manager (e.g. `fluxbox`) before launching the IDE.
  Without one there is no focus management and popups can draw at `0,0`. In the project's own
  integration tests the IDE runs inside a Docker container on Linux under Xvfb with a window
  manager — see the post linked below.
- Everything else is unchanged from a local run: `devrig install <agent>` registration, the
  `devrig mcp` bridge, and `devrig backend download|start|stop` all work identically once a
  backend is reachable.

## Related

- [Getting Started](/docs/getting-started/) — install devrig and connect your agent
- [devrig CLI](/docs/devrig/) — the full command set, including managed backends
- [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177) — why plain non-backend headless mode is unsupported

## Further reading

- [IntelliJ in Docker integration tests](https://jonnyzzz.com/blog/2026/07/05/intellij-in-docker-integration-tests/)
  — how the project runs a real IntelliJ inside a Docker container on Linux under Xvfb (with a
  window manager and a live screen feed) for its integration tests.
