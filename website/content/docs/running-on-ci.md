---
title: "Running devrig in CI"
description: "Run devrig and a real JetBrains IDE on CI — under Xvfb (a virtual X display) on Linux, or the normal GUI on macOS/Windows"
weight: 55
group: "Reference"
---

devrig drives a **real** JetBrains IDE, and a JetBrains IDE is a GUI application: it needs a
display to attach to. That is true in CI as well. There is no true headless mode — launching
the IDE with `-Djava.awt.headless=true` is unsupported (best-effort only; long blocking waits
and deadlocks in platform code have been observed — see
[#177](https://github.com/jonnyzzz/mcp-steroid/issues/177)).

So "running in CI" does not mean "running headless". It means giving the IDE a display:

- **macOS / Windows CI** — the runner already has a real GUI session. Install and run devrig
  exactly as you would locally; the IDE renders to the normal desktop.
- **Linux CI** — CI Linux boxes usually have no physical screen, so provide a **virtual** one
  with **Xvfb** (the X virtual framebuffer). The IDE renders into the virtual display and
  behaves as if a screen were attached. This is the approach the project's own integration
  tests use.

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
virtual display. Do **not** pass `-Djava.awt.headless=true` — headless mode is unsupported.

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
  display is available.

## Related

- [Getting Started](/docs/getting-started/) — install devrig and connect your agent
- [devrig CLI](/docs/devrig/) — the full command set, including managed backends
- [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177) — why true headless launches are unsupported

## Further reading

- [IntelliJ in Docker integration tests](https://jonnyzzz.com/blog/2026/07/05/intellij-in-docker-integration-tests/)
  — how the project runs a real IntelliJ inside a Docker container on Linux under Xvfb (with a
  window manager and a live screen feed) for its integration tests.
