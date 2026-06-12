# devrig naming — projects & IDEs

This is the canonical spec for how the **devrig** binary names projects
and IDEs (backends) in every output it produces (CLI text, CLI JSON,
devrig stdio MCP).

> **Reconciled 2026-06-12.** Earlier revisions of this document specified
> slug/`bootHash` exposed ids (`IntelliJ_IDEA_2025.3.3-AbC4Df01`). That
> scheme was never shipped; the implementation settled on the single
> `<PRODUCTCODE>-<hash8>` backend scheme and pid-salted project names
> described below, and this document now matches the code. There is no
> migration path because the old scheme was never published outside this
> spec.

## Scope

devrig is an **independent binary**. Its output is consumed by:

1. **Humans** reading `devrig backend` / `devrig project` text.
2. **Scripts** consuming `devrig backend --json` / `devrig project --json`.
3. **AI agents** calling `steroid_list_projects` / `steroid_list_windows`
   over the devrig stdio MCP server (`devrig mcp`).

All three surfaces share the names defined here. These names are
devrig-owned MCP/CLI output (`docs/PHILOSOPHY.md` § Tenet 5) — they
never cross the devrig↔IDE wire, so devrig is free to reshape them.
The one deliberate overlap: the in-IDE `steroid_list_projects`
self-describe computes its own `backend_name` with the **same shared
formula** (`backendNameForMarker` in
`mcp-steroid-server/.../BackendName.kt`), so the id an agent sees from
devrig and the id the IDE reports for itself agree.

## Vocabulary

| Term | Meaning |
|---|---|
| **backend_name** | The canonical backend id: `<PRODUCTCODE>-<hash8>`. Primary key of `backends[]` in `devrig backend --json`, `devrig project --json`, and `steroid_list_projects`; the value passed as `backend_name` to `steroid_open_project`. |
| **PRODUCTCODE** | The verbatim build-number prefix — the capital letters before the first dash of the IDE build string (`IU-261.25134.95` → `IU`, `IC` → `IC`, `GO` → `GO`, `PC` → `PC`). **Capitals as-is, never lowercased.** Managed rows take it from the install catalog (`product-info.json` `productCode`), which carries the same value the build prefix would. Fallback when no product code is determinable (null/blank build with no prefix): `IDE`. |
| **hash8** | THE one id hash: `SHA-256` over the canonical inputs, rendered as fixed-width 8-char base62 (alphanumeric `[0-9a-zA-Z]`). base62 is used deliberately over base64url so the suffix is identifier-safe and never contains — or ends with — `-`/`_`. The full 256-bit digest feeds the encoder; nothing is truncated before hashing. Implemented once as `hash8` in `mcp-steroid-server/.../BackendName.kt`; backend names and project names both terminate in this helper — there is no second hash implementation. |
| **sourceKey** | The hash8 input identifying a backend row: `"pid:<pid>"` (marker), `"port:<port>"` (port), `"managed:<managedId>"` (managed). |
| **project_name** | The canonical project id: `<name>-<hash8(canonicalProjectHome \0 idePid)>`. Primary key of `projects[]`; the value passed as `project_name` to project-targeted MCP tool calls. |
| **archiveSha256** | Hex-encoded SHA-256 of the IDE archive a `devrig backend download` would fetch, when the JetBrains products API publishes a checksum link. Nullable; informational — not part of any id. |
| **locator label** | The parenthesised "how do I reach this" hint in CLI **text** rendering only, e.g. `(build IU-261…, pid 24017)`. Not part of the JSON schema (#90 de-dup: `build`/`pid`/`port` are the canonical JSON fields). |

## Backend naming — the ONE scheme

```
backend_name = "<PRODUCTCODE>-<hash8(sourceKey)>"
```

Every backend kind — marker-discovered, port-discovered, and
devrig-managed — flows through the **single** formula
(`backendNameFor(productCode, sourceKey)` in
`mcp-steroid-server/.../BackendName.kt`). There are no per-source id
shapes, no `displayName`-derived slugs, no alternative casings.

| Source | PRODUCTCODE comes from | `sourceKey` |
|---|---|---|
| Marker (mcp-steroid plugin present) | build-string prefix of `IdeInfo.build` (`IU-261.…` → `IU`) | `"pid:<idePid>"` |
| Port (built-in HTTP server) | build-string prefix of the `/api/about` buildNumber, when prefixed | `"port:<port>"` |
| Managed (devrig-installed, not running) | catalog `productCode` (`product-info.json`), the same value the build prefix carries | `"managed:<managedId>"` |

A managed backend that is **running** with the plugin writes a marker
and surfaces as `source = "marker", managed = true` — its backend_name
is the marker form (`"pid:<pid>"`). The `managed` source is reserved
for installed-but-not-running rows.

Examples: `IU-9fk2a0xQ`, `GO-xgXVtRjX`, `IC-phhp9dDU`, `PC-2FlunefY`.

### Invariants

1. **Verbatim capitals.** The product segment is exactly the build
   prefix, including case (`IU`, never `iu`). One scheme, one casing,
   for every backend kind.
2. **Stable across rescans.** The hash inputs are stable for the same
   IDE: the same pid (marker), port (port), or managedId (managed)
   recomputes to the same backend_name on every discovery snapshot.
3. **IDE restart ⇒ (almost always) a different marker name** — a new
   JVM gets a new pid. Pid reuse by the OS would reproduce the old
   name; consumers needing restart detection should compare `pid` +
   marker `createdAt` fields, not the opaque id.
4. Two IDEs of the same product+version running concurrently ⇒
   distinct backend_names (different pids/ports).
5. An IDE moving from port-discovered to marker-discovered (the plugin
   gets installed) produces a **different** backend_name — the
   sourceKey changes. Intentional; consumers should refresh.
6. The pid/port/source/routability are published as their own
   `BackendInfo` fields — **never encoded into the id shape, never
   parsed back out of it**. All reverse lookups go through the
   in-memory map rebuilt from the current discovery snapshot
   (`DevrigProjectRoutingService.resolveBackend` recomputes names per
   discovered IDE).
7. Stale name lookup fails with the actionable refresh message
   (`"backend '<name>' …; call steroid_list_projects to refresh"`).

### Lifecycle command ids are NOT backend_names

`devrig backend download/start/stop` take a **catalog id**
(`<productKey>` or `<productKey>-<version>` / `<productKey>:<version>`,
e.g. `idea-community-2025.3.3` — see `parseBackendId`), because they
operate on installs that may not be discovered/running yet.
`devrig backend provision` takes a `port-<N>` target id. Discovery
backend_names identify *running/installed rows in a snapshot*; catalog
ids identify *what to install or start*. The `actions[].argv` arrays in
list-mode JSON always carry the correct id form for the command.

## Project naming

```
project_name = "<originalProjectName>-<hash8(canonicalProjectHome \0 idePid)>"
```

- `originalProjectName` is the unmodified `Project.name` reported by
  the running IntelliJ.
- `canonicalProjectHome = Path.of(projectPath).toRealPath()` — resolves
  symlinks, so two paths pointing at the same directory share a hash.
  When the directory no longer exists (`toRealPath()` throws), devrig
  falls back to the lexically-normalized absolute path so one vanished
  project cannot break routing for every other project.
- `idePid` is the OS PID of the IntelliJ JVM that owns the project. It
  appears in the hash AND is published as a data-model field, so an
  operator can trace a project back to its OS process. The pid in the
  hash is intentional debuggability — "two rows differ → look at their
  pids" — even though it makes the name change across IDE restarts.
- The `\0` (NUL) separator prevents ambiguity between the two inputs.
- Implemented in `DevrigProjectRoutingService.projectHash`, which
  delegates to the **same `hash8` helper** backend names use.

### Invariants

1. Same canonical project path + same IDE pid ⇒ same hash ⇒ same
   `project_name` across rescans.
2. Different IDE pid (same project) ⇒ different `project_name`. The
   same project open in two running IDEs publishes **two rows** with
   the same `path`, different `project_name`, different `backend_name`
   foreign keys — each routes independently.
3. Different canonical project path ⇒ different `project_name`.
4. IDE restart ⇒ new pid ⇒ new `project_name`. Scripts that need a
   value surviving restarts use the project's filesystem `path`.
5. Looking up a `project_name` in a fresh discovery snapshot uses the
   map directly. **No suffix parsing.**
6. A name that no longer maps fails with
   `ProjectRouteNotFoundException`:
   `"project_name '<name>' is no longer present; call steroid_list_projects to refresh"`.

## The only IDs

Every consumer-visible reference to a backend uses `backend_name`;
every reference to a project uses `project_name`. There are no alias
IDs and no historical `pid-N` / `<slug>-<hash>` forms (the `port-N`
provision target and the catalog install ids are command arguments,
not row ids — see above). The hash suffix is opaque and informational:
**it is never parsed**; reverse lookups go through the in-memory
`Map<exposed, route>` rebuilt from the current discovery snapshot.

### Trust model

Markers under `~/.mcp-steroid/markers/`, managed-backend descriptors
under `~/.mcp-steroid/backends/`, and the IntelliJ built-in HTTP server
endpoints devrig probes for port-discovered rows are all **trusted as
inputs from the user's own user account**. devrig does not validate
marker contents against an external authority; a marker pointing at an
attacker-controlled `mcpUrl` would route MCP calls to that endpoint.

This is acceptable because every input source lives inside the user's
own home directory (or, for port probes, inside `127.0.0.1`'s
loopback). Cross-user / privilege-boundary attack scenarios are out of
scope for this spec — if a different user can write to your
`~/.mcp-steroid/`, the attacker already controls your account.

A future hardening could add a marker signature / capability token.
That work is not part of this spec.

## Output surfaces

All three surfaces show the same ids. Human labels (`displayName`) and
locator labels stay available so humans can still see what's
underneath.

### `devrig backend` (text)

```
Discovered 2 backends:

  [1] IntelliJ IDEA 2025.3.3 (build IU-261.23567.138, pid 24017)
        MCP Steroid: 0.95.0-b14969e1
        myproject-XyZ01204     →  /Users/me/Work/myproject
        scratchpad-Pq89Rs05    →  /Users/me/Work/scratchpad

  [2] IntelliJ IDEA Ultimate (build 261.24374.151, port 63342)
        MCP Steroid: not installed
```

The text rendering leads with the human `displayName`; the locator
label stays parenthesised. The JSON surfaces are the id-bearing ones.

### `devrig backend --json` / `devrig project --json`

Both commands emit the **same document** (byte-for-byte for the same
discovery rows): a `tool` envelope, the shared `backends[]`
(`BackendInfo` schema, R3.4 — the same schema `steroid_list_projects`
returns), and the flat `projects[]` (`ListedProject`). Each fact
serializes exactly once (#90): backends carry no embedded project
list — join `projects[]` on `backend_name`.

```json
{
  "tool": { "name": "devrig", "version": "0.100.0" },
  "backends": [
    {
      "backend_name": "IU-9fk2a0xQ",
      "type": "intellij",
      "source": "marker",
      "displayName": "IntelliJ IDEA 2025.3.3",
      "routable": true,
      "reachable": true,
      "managed": false,
      "pid": 24017,
      "ideProductCode": "IU",
      "build": "IU-261.23567.138",
      "plugins": [
        { "id": "com.jonnyzzz.mcp-steroid", "name": "MCP Steroid", "version": "0.95.0", "kind": "mcp-steroid" }
      ]
    },
    {
      "backend_name": "IC-phhp9dDU",
      "type": "intellij",
      "source": "port",
      "displayName": "IntelliJ IDEA",
      "routable": false,
      "reachable": true,
      "managed": false,
      "port": 63342,
      "ideProductCode": "IC",
      "build": "IC-261.24374.151",
      "plugins": [],
      "portDetail": { "baseUrl": "http://127.0.0.1:63342", "edition": "Community", "baselineVersion": 261 }
    },
    {
      "backend_name": "IC-2FlunefY",
      "type": "intellij",
      "source": "managed",
      "displayName": "idea-community 2025.3.3",
      "routable": false,
      "reachable": false,
      "managed": true,
      "ideProductCode": "IC",
      "build": "261.23567.138",
      "plugins": [],
      "managedDetail": {
        "managedId": "idea-community-2025.3.3",
        "state": "installed",
        "installPath": "/Users/me/.mcp-steroid/backends/idea-community-2025.3.3",
        "cachePath": "/Users/me/.cache/mcp-steroid/backends/idea-community-2025.3.3"
      }
    }
  ],
  "projects": [
    {
      "project_name": "myproject-XyZ01204",
      "name": "myproject",
      "path": "/Users/me/Work/myproject",
      "backend_name": "IU-9fk2a0xQ"
    }
  ]
}
```

**Per-row schema** — the authoritative field list with per-field KDoc
is `BackendInfo` / `ListedProject` in
`mcp-steroid-server/.../ListProjectsTool.kt`; highlights:

`backends[]`:

| Field | Meaning |
|---|---|
| `backend_name` | The ONE uniform id (`<PRODUCTCODE>-<hash8>`). Primary key for JSON consumers; the same value the in-IDE `steroid_list_projects` self-describe computes for itself. |
| `type` | Backend family; today `"intellij"`. |
| `source` | `"marker"` / `"port"` / `"managed"`. |
| `displayName` | The ONE human label (NOT unique across two same-product IDEs — disambiguate via `pid`/`port`/`build`). |
| `routable` | True only when `steroid_open_project` can target this backend (a marker IDE with a live bridge). |
| `reachable` | True when discovery-reachable. |
| `managed` | True when this devrig instance owns the backend's lifecycle. |
| `pid` / `port` | Process/port reference, when known. Published for humans and correlation; NOT encoded into the id. |
| `ideProductCode` / `build` | Product code and the ONE build string, when known. |
| `plugins[]` | Observed plugins, each `{id, name, version, kind}`; `kind == "mcp-steroid"` marks our plugin. Marker rows only today. |
| `portDetail` / `managedDetail` | Source-specific extras (baseUrl/edition; managedId/state/installPath/cachePath). |

`projects[]`:

| Field | Meaning |
|---|---|
| `project_name` | The exposed project id. Primary key; the value MCP tool calls pass as `project_name`. |
| `name` | Raw `Project.name` from the running IntelliJ. Display only; preserved for `jq '.projects[].name'` consumers. |
| `path` | Project base path as reported by the IDE. The hash computes over its canonical (`toRealPath()`) form. |
| `backend_name` | Foreign key to `backends[].backend_name`. |

### devrig stdio MCP — `steroid_list_projects` / `steroid_list_windows`

`steroid_list_projects` over devrig returns the same `backends[]` +
flat `projects[]` shapes; `projects[].project_name` is always the
exposed project name. For `steroid_list_windows`,
`DevrigProjectRoutingService.rewriteWindow` rewrites **only**
`WindowInfo.projectName` to the exposed project name (or leaves it
`null` when the window has no project context).

`WindowInfo.windowId` is left **unchanged**. A `window_id` is never a
standalone argument — it always travels together with a `project_name`.
Follow-up `steroid_input` / `steroid_take_screenshot` calls resolve the
owning IDE via `project_name` and forward the original `window_id`
verbatim (it is unique within that IDE). There is no window-id
rewriting and no reverse map.

## Live routing model

See `docs/devrig-scanning-research.md` § "Decision" for the rationale
that led to on-demand scanning, and `docs/PHILOSOPHY.md` § "Tenet 3 —
devrig is stateless" for the top-level invariant this section
implements.

A single per-call routine (`rebuildSnapshot()`) constructs devrig's
live model on demand. It runs three pieces in parallel:

- read every marker file under `~/.mcp-steroid/markers/` and parse
  each as JSON;
- scan the IntelliJ built-in HTTP server port ranges
  (`63342..63361`, `64342..64361`) with a short connect timeout
  (≤ 3 s per probe, parallel);
- enumerate managed-backend installs under `~/.mcp-steroid/backends/`
  and read each one's descriptor.

For every marker, the routine opens a one-shot HTTP POST to
`/npx/v1/projects/stream`, awaits the first `snapshot` envelope, and
closes. The IDE never sees a long-lived connection from devrig. There
is no `StateFlow`, no background `Job`, no per-IDE persistent socket —
devrig is stateless across calls.

### Per-call routing

0. The caller MUST supply a non-empty `project_name` (for
   project-targeted calls) or backend id (for backend-targeted calls).
   Missing / empty / blank / null id fails immediately BEFORE any
   snapshot work.
1. The call's coroutine rebuilds the snapshot. The result is cached
   for the duration of the current call (no double-scan).
2. The caller's id is looked up in the snapshot's
   `Map<exposed, route>` — names are **recomputed** from the rows'
   stable inputs, never parsed.
3. A hit dispatches to that backend; a miss fails with
   `ProjectRouteNotFoundException` / the backend-not-found error
   carrying the actionable "call … to refresh" message.

### Collision handling

Every `backend_name` and `project_name` is unique within one snapshot
by construction (distinct pids/ports/managedIds ⇒ distinct hashes; a
collision needs a sha256 prefix collision). If a snapshot nevertheless
observes a duplicate id:

- The first occurrence (in iteration order) is kept.
- Subsequent rows with the same id are dropped from the map but
  **published verbatim** in the JSON arrays — so an operator running
  `devrig backend --json` can see the duplicate and diagnose.
- A warning is logged (`backendRowsWithStableIds` /
  `DevrigProjectRoutingService.discoveredBackends` — keep-first +
  WARN). No exception reaches the caller.

This policy is observability, not a security boundary — the inputs
already live inside the user's own home directory.

### No implicit routing

devrig does not silently pick a backend when the id is omitted. No
"only running IDE" shortcut, no fuzzy match, no most-recently-used
fallback. Project-targeted MCP tool calls require `project_name`;
backend-targeted CLI commands require their positional id (list mode
with no id prints the catalogue, it does not route).

`steroid_open_project` is the sole exception: it opens a project *not
yet open* anywhere, so it takes a filesystem `project_path` (plus, on
the devrig surface, the REQUIRED `backend_name`). When selecting a
default target devrig prefers a running devrig-managed backend, then
the newest discovered IDE — see
`DevrigProjectRoutingService.openProjectTargetIde()` and the
`mcp-steroid://open-project/managing-backends` recipe.

### In-flight calls are not re-routed

Once dispatched, a call runs on the chosen backend to completion. If
the backend dies or its id changes mid-call, the call surfaces the
transport error; the next call repeats routing against a fresh
snapshot.

## Non-goals

- **Not a stable cross-restart identifier (marker rows).** Marker rows
  hash the pid; a restarted IDE gets a new pid and therefore a new
  backend_name (and new project_names). Script against the project's
  filesystem `path` or the managed `managedId` when you need
  restart-stable references.
- **Port-discovered rows are stable across IDE restarts** that re-bind
  the same port — the sourceKey is just the port number.
- **Managed-installed rows are stable across restarts** by design —
  the sourceKey is the managed install id.
- **Not human-memorisable.** `hash8` is opaque. If you need to type a
  name, use shell completion against `devrig backend --json` /
  `devrig project --json`.
- **No alias IDs.** Exactly one id format per row kind; no migration
  path from the never-shipped slug/bootHash scheme.

## Test contract

The invariants above are pinned by:

- `BackendNameTest` (`:mcp-steroid-server`) — hash8 determinism,
  base62 alphabet, the verbatim-capitals product segment, and the
  `IDE` fallback.
- `BackendIdentityTest` (`:npx-kt`) — marker/port/managed rows all
  flow through the single `backendNameFor` formula; verbatim capital
  product code; 8-char base62 suffix; stable hash inputs across
  rescans; pid-keyed marker identity.
- `DevrigOpenProjectBackendRoutingTest` — `backend_name` resolves to
  the right discovered IDE; stale/unknown names miss.
- `DevrigListToolHandlersTest` / `DevrigToolBridgeClientTest` —
  `projects[]`/`backends[]` agreement, project-name round-trip across
  the tool bridge, `window_id` forwarded unchanged.
- `BackendCommandJsonRenderTest` — text and JSON renderers emit the
  uniform ids; keep-first de-duplication.
- `ListProjectsToolSpecSchemaTest` / `WirePristinenessTest`
  (`:mcp-steroid-server`) — the MCP/CLI schema carries `backend_name`
  while the devrig↔IDE wire DTOs stay pristine (Tenet 5).

## See also

- `docs/PHILOSOPHY.md` — the design tenets that gate every change in
  this repo (Tenet 5 scopes which shapes are frozen vs devrig-owned).
- `docs/ARCHITECTURE.md` — the broader request-flow picture.
- `docs/devrig-scanning-research.md` — on-demand scanning decision
  record.
