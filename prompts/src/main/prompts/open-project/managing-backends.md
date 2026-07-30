Managing IDE backends for open_project

How open_project starts a not-yet-running managed IDE and how devrig backend manages IDE installs.

# Managing IDE backends

Use this recipe when you need to open a project in a **specific
product/version** (Rider for .NET, GoLand for Go, a particular build,
etc.) or in a **not-yet-running IDE**.

## How `steroid_open_project` resolves a backend

When you call `steroid_open_project` through the devrig stdio MCP
server, devrig composes two candidate sources:

- **Running MCP Steroid IDEs** (S1) — IDEs running a **current** MCP
  Steroid plugin (they self-report their install home), discovered from
  pid markers. Ready to use immediately. An IDE running an
  **old/incompatible** plugin is *not* a candidate — it appears in the
  "Other IDEs" group of `devrig backend` instead.
- **Startable managed backends** (S3) — IDEs devrig downloaded under
  `~/.mcp-steroid/backends/` that are **not yet running**. devrig can
  start them on demand.

**Without `backend_name`**: if exactly one candidate exists across S1
and S3, `open_project` uses it automatically. If more than one
candidate exists, it returns the list grouped by kind and asks you to
call again with a chosen `backend_name`.

**With `backend_name`**: devrig resolves the name to a candidate. If
the candidate is a startable managed backend, devrig **starts the IDE
and waits (blocking, up to 5 minutes) until the IDE is reachable**,
reporting progress (e.g. "Starting <IDE>…") as it goes, then opens the
project — all in a single `open_project` call. You never need to run
`devrig backend start` first.

The command never branches on running-vs-startable — devrig handles
the lifecycle transparently.

## Choosing a `backend_name`

Every `projects[]`, `windows[]`, and `backgroundTasks[]` entry in
`steroid_list_projects` / `steroid_list_windows` carries a
`backend_name`. When you need to target a specific IDE:

1. Call `steroid_list_projects` (or `steroid_list_windows`) — each
   item carries a `backend_name`.
2. Call `steroid_open_project` with the `backend_name` of the IDE you
   want.

Each `projects[]` entry also carries `project_name` (the unique, opaque
routing KEY you pass to project-scoped tools), the human-readable folder
`name` (informational only), `path`, and a `backend_name` naming its
owning backend.

## Resolving a `backend_name` to the IDE's identity

Both list-tool responses carry a `backends` lookup table — the
resolution step for every `backend_name` referenced by that response's
entries. Each element is identity-only:

```
{ "backend_name": "iu-47qi79c1",
  "intellij": { "name": "IntelliJ IDEA 2026.1.3",
                "version": "2026.1.3",
                "build": "IU-261.25134.95" } }
```

- `intellij` means "the IntelliJ-Platform IDE" — a GoLand or PyCharm
  backend still nests under `intellij`; the product is identified by
  `name`/`build` (e.g. `"GO-261.24374.154"`).
- On a direct in-IDE connection, `backends` always contains exactly one
  element (the IDE itself), even with zero open projects — so probing a
  fresh IDE's identity is a single `steroid_list_projects` call.
- Via devrig, `backends` is derived from the same routing snapshot the
  entries come from: every referenced `backend_name` resolves, and a
  backend can appear with zero entries only in transient startup
  states. Backend *inventory* (zero-project, startable, and no-plugin
  IDEs) lives in `devrig backend --json`.
- `windows[].projectName` is the opaque routing key, not a display
  name — enrich the human-readable `name`/`path` via
  `steroid_list_projects` (which carries the same `backends` table, so
  one call resolves both the project and its IDE).

If the chosen `backend_name` belongs to a startable managed backend
(not yet running), `open_project` starts it and blocks until it is
reachable, then opens the project.

An unknown or stale `backend_name` returns a self-correcting error
that lists the currently available `backend_name`s (both running and
startable).

**A `backend_name` is not stable across IDE restarts** (it is derived
from the pid or the install path). Re-read `steroid_list_projects`
rather than caching a `backend_name` — its `backends` table is the
resolution step from a fresh key to the owning IDE's identity.

## The `devrig backend` CLI — for installing and managing IDEs

Use `devrig backend` when you need to **install** a new IDE, or to
explicitly control lifecycle. It is **not a prerequisite** to
`open_project` — if the IDE you need is already installed (even if
not running), `open_project` can start it.

- `devrig backend` — shows four groups:
  - **MCP Steroid backends** (running, compatible) — you can open
    projects here now.
  - **Other IDEs (incompatible or no MCP Steroid)** — running IDEs with
    an old/incompatible plugin (no self-reported install home) or none
    at all; detected only, devrig cannot drive them.
  - **Installed, not running (startable)** — startable via `open_project`
    or `devrig backend start`.
  - **Downloadable** — not listed individually; the footer points at the
    full-cycle install command `devrig backend download <product>`, which
    downloads + installs an IDE so it becomes startable.
- `devrig backend --json` — machine-readable:
  `{ tool, mcpSteroidBackends[], otherIdes[], startableBackends[] }`;
  each entry carries `compatible: <bool>`.
- `devrig backend download <id>` — fetch + install an IDE (may take
  minutes; cached and resumable). This is the full install cycle — the
  IDE then appears as startable.
- `devrig backend start <id>` / `devrig backend stop <id>` — explicit
  lifecycle control.

The `<id>` values appear in `devrig backend --json`.

## After opening — polling for readiness

`steroid_open_project` returns once the project-open request is
accepted. The project is not fully ready until indexing finishes:

1. Poll `steroid_list_windows` every 2-3 seconds until:
   - The project appears in the list.
   - `modalDialogShowing` is `false`.
   - `indexingInProgress` is `false`.
   - `projectInitialized` is `true`.
2. If `modalDialogShowing` is `true`, call `steroid_take_screenshot`
   to see the dialog and `steroid_input` to interact.

When several IDEs are running, each `windows[]` / `backgroundTasks[]`
entry carries a `backend_name` — use it to track the right IDE.

## Only managed backends are startable

Startable = devrig-installed under `~/.mcp-steroid/backends/`. Other
running IDEs (discovered by port scan, no MCP Steroid plugin) are
detected but cannot be driven or started by devrig. Install the MCP
Steroid plugin in them (`devrig backend provision`) to make them
routable.

# See also

- [Open Project Workflow Overview](mcp-steroid://open-project/overview)
- [Open Project (Trusted)](mcp-steroid://open-project/open-trusted)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Open Project via IntelliJ APIs](mcp-steroid://open-project/open-via-code)
