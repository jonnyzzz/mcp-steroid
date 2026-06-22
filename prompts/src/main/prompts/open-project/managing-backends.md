Managing IDE backends for open_project

Download, start, and stop devrig-managed IDEs to open a project in a fresh or specific IDE, and how open_project routes to that backend.

# Managing IDE backends

Use this recipe when the IDE you need is **not already running** — you
want a clean IDE for the project, or a **specific product/version** the
project requires (Rider for .NET, GoLand for Go, a particular platform
build, etc.).

Backend management is a rare, heavyweight operation (it can download an
IDE), so it is **not** an MCP tool. You drive it through the `devrig`
CLI directly; `steroid_open_project` then routes into the backend you
started. Every command supports `--json` (stable, pretty-printed) and
returns exit code `0` on success or `64` on a user/validation/lock
error — always pass `--json` and check the exit code.

## Running the devrig CLI

The CLI is the **same `devrig`** you are already running as your MCP
server, invoked with a `backend` subcommand:

- `devrig backend` — list installed + running backends.
- `devrig backend download <id>` — fetch an IDE.
- `devrig backend start <id>` / `devrig backend stop <id>` — run / stop one.
- `devrig backend provision <id>` — install the MCP Steroid plugin into
  a backend so it becomes routable.

The `<id>` values come from `devrig backend --json` (the
`backends[].backend_name` field — see Step 1).

**Launcher.** Run `devrig` — the stable launcher the devrig binary installs and
keeps current on your `PATH` (it lives under `~/.mcp-steroid/bin`). E.g.
`devrig backend --json` or `devrig backend download idea-ultimate --json`.

## Step 1 — See what IDEs are available

Two read-only commands give you the full decision context. Run them
first and decide what to open *before* mutating anything.

- Installed + running backends, and the projects open in each:
  `devrig backend --json`
- Stable IDEs you can download (latest stable per product, each with its
  `id`, `displayName`, `licenseTier`, `version`, and `releaseDate`), so
  you can pick the right one for the project:
  `devrig backend download --json`

A **backend id is the `id` field** from that list — `idea-ultimate`,
`idea-community`, `pycharm-pro`, `pycharm-community`, `goland`,
`webstorm`, `rider`, `clion`, or `android-studio`. Use the id bare for
the latest stable, or pin a version with `--version` (e.g.
`--version 2026.1`). Copy the `id` verbatim rather than guessing a
short code.

## Step 2 — Download the IDE (if it is not installed yet)

`devrig backend --json` shows installed backends. If the one you need is
absent, download it — bare for latest, or pinned:
`devrig backend download idea-ultimate --version 2026.1 --json`. This
can take minutes; downloads are cached and resumable.

## Step 3 — Start the backend

Start it with `devrig backend start idea-ultimate --json` (add the same
`--version` you downloaded to pin it). This spawns a
**detached** IDE process and returns its `pid` *before* the IDE has
finished starting. Only **one** managed backend runs at a time (a global
lock); if another is running, `start` fails with exit `64` — stop it
first with `devrig backend stop <id> --json`.

## Step 4 — Wait until the backend is discoverable, then open

The IDE is not routable until its plugin writes a marker (≈5–15 s after
start). The new backend may already show up in `backends[]` during that
window (managed rows are listed even before they are reachable), so poll
`steroid_list_projects` until its entry reports `routable: true`, then
call `steroid_open_project` with the absolute project path.

`steroid_open_project` prefers a **running devrig-managed backend** over
any other (even newer) IDE when you let it auto-pick — so once your
backend is discovered, the project opens there deterministically. To open
**several** projects in that one IDE, call `steroid_open_project` once per
path; the global lock guarantees they all land in the same managed
backend. To target a **specific** IDE among several running ones, pass
`backend_name` (see "Choosing a backend" below).

After opening, poll `steroid_list_windows` (watch `modalDialogShowing`,
`indexingInProgress`, `projectInitialized`) and use
`steroid_take_screenshot` + `steroid_input` for any dialogs, exactly as
in the normal open-project flow. When several IDEs are running, each
`windows[]` / `backgroundTasks[]` entry carries a `backend_name` — match
it against your backend's entry in `backends[]` to watch the right IDE.

## Choosing a backend (the `backend_name` parameter)

When more than one IDE is running, tell `steroid_open_project` exactly
which one to open the project in by passing `backend_name`. This is a
**devrig-only** parameter — it has no effect on a direct in-IDE MCP
connection (one MCP server == one IDE), where it is logged and ignored.

`steroid_list_projects` and `steroid_list_windows` **self-describe on
both surfaces**: neither response carries a top-level `ide`/`plugin`/
`pid` header — each carries `backends[]` instead, and every
`projects[]`, `windows[]`, and `backgroundTasks[]` entry names its
owning backend via `backend_name`. The devrig response lists one
`backends[]` entry per discovered backend (including non-routable ones
— see below); a direct in-IDE response lists exactly one entry (the IDE
you are connected to). Both have identical shape.

To pick a value:

1. Call `steroid_list_projects` and read `backends[]`. Each entry has:
   - `backend_name` — the value you pass as `backend_name`, an opaque id
     like `"iu-9fk2a0xQ"`.
   - `displayName` — human label, e.g. `"IntelliJ IDEA 2026.1"` (NOT
     unique across two same-product IDEs).
   - `locator` — disambiguator when two IDEs share a `displayName`
     (e.g. `"build IU-261.x, pid 1234"`).
   - `routable` — `true` only for IDEs you can actually open into.
   - `plugins[]` — the IDE's relevant plugins, each `{ id, name, version,
     kind }`. A `kind: "mcp-steroid"` entry means the MCP Steroid plugin
     is installed (required for a backend to be routable; check `routable`
     for the final answer — an unreachable IDE keeps the plugin entry but
     is not routable).
   - `openProjects[]` — `{ project_name, name, path, backend_name }` for
     every project already open in that backend.
   - `managed` — `true` if this is the devrig-managed sandbox.
2. **Prefer the backend that already has the same project — or another
   git worktree of the same repository — open.** Worktrees of one repo
   share build/index/VCS context; opening them in the same IDE keeps that
   context warm and avoids a redundant second indexing. Inspect
   `backends[].openProjects[].path`: if a backend already holds a sibling
   worktree of the repo you are about to open (same repo root / shared
   `.git`), choose that backend's `backend_name`. Otherwise prefer a
   `managed` backend, else any listed backend.
3. Pass the chosen `backend_name` to `steroid_open_project`.

Each `projects[]` entry also carries `project_name`, the raw folder
`name`, `path`, and a `backend_name` naming its owning backend.

**Only routable backends are valid — but `backends[]` lists ALL
backends.** The list includes marker IDEs (even with zero open
projects), port-only IDEs running **without** the MCP Steroid plugin,
and devrig-managed backends that are **not running**. Only entries with
`routable: true` (a running IDE with a live MCP Steroid bridge — a
`plugins[]` entry of `kind: "mcp-steroid"`) accept `open_project` —
**always check `routable` before passing a `backend_name`**.
Non-routable rows are provision/start targets: bring them up with
`devrig backend provision <id>` (install the plugin) and/or
`devrig backend start <id>`. Passing a non-routable or unknown
`backend_name` returns a self-correcting error that lists the
currently-routable `backend_name`s.

**A `backend_name` is not stable across IDE restarts** (it is derived
from the pid), exactly like the pid-salted `project_name`. **Re-read
`steroid_list_projects` each time rather than caching a `backend_name`.**

## Two common modes

- **Open everything in a fresh IDE:** Step 3 (start a clean backend) →
  Step 4, calling `steroid_open_project` for each project path.
- **Download the IDE a project needs:** inspect the project to pick the
  product/version, Step 2 (download it) → Step 3 → Step 4.

## Step 5 — Stop the backend when done

Stop it with `devrig backend stop idea-ultimate --json`. The reported
`outcome` is one of `stopped` (graceful), `killed` (forced),
`already stopped`, `not running`, or `stale`.

# See also

- [Open Project Workflow Overview](mcp-steroid://open-project/overview)
- [Open Project (Trusted)](mcp-steroid://open-project/open-trusted)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Open Project via IntelliJ APIs](mcp-steroid://open-project/open-via-code)
