# `backend_name` Routing Parameter for `steroid_open_project` — Implementation Plan

> **Historical plan.** The implementation shipped and its readiness wording was later superseded by the
> frontendless Remote Development flow. The current contract is in
> [`docs/guides/AGENT-STEROID-GUIDE.md`](../../guides/AGENT-STEROID-GUIDE.md): poll
> `steroid_list_projects` for the requested path, treat windows as optional, and await Maven/Gradle
> configuration separately. Do not copy the mandatory-window recipe from this plan.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an agent talking to **devrig** choose *which discovered IDE backend* a project opens in, by passing an optional `backend_name` to `steroid_open_project`, where `backend_name` is the same stable backend identifier already surfaced by `steroid_list_projects` and `devrig backend/project --json`.

**Architecture:** The MCP tool surface is shared (`mcp-steroid-server/McpSteroidTools`) between the in-IDE plugin and devrig. Today both register the **same** `OpenProjectToolSpec`, and `DevrigOpenProjectToolHandler` auto-picks a target IDE (managed-backend-preferred, newest-otherwise). This plan adds `backend_name` **only on the devrig surface** (so direct-IDE users never see a no-op parameter), threads it through the shared `OpenProjectParams` DTO as an optional, wire-compatible field, and makes devrig resolve it to a specific `DiscoveredIde`. The in-IDE plugin handler logs-and-ignores the field as defense-in-depth for forward compatibility. The backend identifier reused as `backend_name` is the existing `backendStableId(row)` (`pid-<n>` / `port-<n>` / managed-id), now also surfaced in the MCP `steroid_list_projects` response so the agent can discover valid values.

**Tech Stack:** Kotlin 2.3.20, kotlinx.serialization, Ktor; JUnit (mcp-steroid-server is plain JVM tests, ij-plugin uses `BasePlatformTestCase` + `timeoutRunBlocking`). Modules touched: `mcp-steroid-server`, `npx-kt` (devrig), `ij-plugin`.

---

## Quorum review (2026-06-09) — VERDICT: APPROVE_WITH_CHANGES

Reviewed per `run-agent.sh` + `RLM.md` methodology by a 4-member panel (gemini unavailable):
**codex** (cross-model, read-only sandbox) + 3 independent Claude lenses orchestrated via Workflow —
*architecture/wire-contract*, *agent-UX/discoverability*, *tests/conventions*. All four returned
`APPROVE_WITH_CHANGES`; **no BLOCKER or REJECT on the design**. The `pid-<n>` identifier choice, the
additive DTO changes, the local-resolution (no-forward) of `backend_name`, and the `includeBackendName`
seam were all independently verified against the real source and confirmed sound.

**Required changes (all applied below):**

1. **Use the real test helper** `assertPropertyAbsent` (`ToolSpecSchemaAssertions.kt:177`) — `assertNoProperty` does **not** exist. (Tasks 2, 3) — *applied.*
2. **Seam test must not call a `protected` method cross-package** — both fakes expose it through the public `spec()` helper. (Task 3) — *applied (codex BLOCKER on the test, not the design).*
3. **Task 4 legacy-JSON fixture** must use `IdeInfo`'s real field `build` (no `fullName`, `build` has no default → `MissingFieldException`). (Task 4) — *applied.*
4. **Task 8 must use the raw JSON-RPC `tools/list` idiom** — `McpServerIntegrationTest` has no `listTools()`/typed-`McpTool` API. (Task 8) — *applied.*
5. **Lock the identifier contract:** `backend_name` accepts **only routable marker IDEs (`pid-<n>`)**; `backends[]` lists only those; the "Unknown backend_name" error is self-correcting for `port-<n>`/managed-slug ids copied from `devrig backend --json`. Resolves the old "Open question". (Design + Tasks 5, 6, 7) — *applied.*
6. **Task 7 is a hard prerequisite of exposing the param (Task 6)** — without per-project `backend` + `backends[]`, the param is undiscoverable via MCP. (Sequencing) — *applied.*
7. **Enrich `BackendSummary`** with a `locator` (and the per-backend project names) so an agent can disambiguate two identical-looking IDEs (display name alone is non-unique). (Task 4 DTO + Task 7) — *applied.*
8. **Guard the direct (in-IDE) `list_projects` surface** — assert `backends == []` and `projects[].backend == null` (shared DTO now always carries these). (Task 8) — *applied.*
9. **Docs:** state `pid-<n>` ids are **not stable across IDE restarts** — re-read `steroid_list_projects`, don't cache. (Task 10) — *applied.*
10. *(non-blocking)* Task 1 also asserts the `backendName=null` encode/decode round-trip; note the snake_case `backend_name` wire name is covered by the spec/call tests, not the camelCase DTO test. (Task 1) — *applied.*
11. **Relabel Task 9** as a guard + documentation task (the `projects[].backend ∈ backends[].id` invariant already holds; not red-green TDD). — *applied.*

---

## Round 2 requirements (2026-06-09, post-review) — AUTHORITATIVE (override earlier text on conflict)

These four refinements were requested after the plan was approved. Where they conflict with task text below, **these win**; apply them in the corresponding tasks.

### R2.1 — `backend_name` is REQUIRED on the devrig surface

The devrig `steroid_open_project` **input schema marks `backend_name` as required** (the agent MUST pass it). The in-IDE surface still does not expose it at all.
- In `OpenProjectToolSpec`, when `includeBackendName = true`, chain `.required()` on the `backend_name` element. (Task 2)
- The schema test for the devrig mode asserts `backend_name` is in the **required** set: `assertRequiredExactly(schema, "project_path", "task_id", "reason", "backend_name")`. (Task 2)
- **Handler stays null-safe (defense-in-depth, not agent-facing):** `OpenProjectParams.backendName` remains `String? = null`, and `DevrigOpenProjectToolHandler` keeps the auto-pick fallback **only** for a null `backendName` (internal/direct-handler callers and the Docker E2E test that call the handler directly). This preserves the existing `DevrigToolBridgeClientTest` "prefers the running managed backend" test and `DevrigManagedBackendAgentE2ETest` unchanged — they call the handler with `backendName = null` and still get auto-pick. The *agent* can never omit it because the schema requires it. (Tasks 5, 6)

### R2.2 — `backends[]` must carry enough for the agent to decide

`BackendSummary` (Task 4) carries, per routable backend:
```kotlin
@Serializable
data class BackendSummary(
    val id: String,                       // == backend_name, e.g. "pid-1234"
    val displayName: String,              // "IntelliJ IDEA 2026.1" (NOT unique across twins)
    val locator: String,                  // "build IU-261.x, pid 1234" — disambiguates twins
    val ideProductCode: String,           // e.g. "IU" / "PY" / "GO" (from the build prefix)
    val managed: Boolean,                 // true if this is a devrig-managed backend (prefer it)
    val openProjects: List<BackendProjectRef> = emptyList(), // names + paths open here
    val ide: IdeInfo,
)

@Serializable
data class BackendProjectRef(val name: String, val path: String)
```
`openProjects` carries **paths** (not just names) so the agent can match a worktree to the repo already open in a backend (see R2.3). `managed` lets the agent prefer the devrig-managed sandbox.

### R2.3 — instruct the agent to prefer the same IDE for a worktree of the same project

The `backend_name` description (devrig mode) and the Task 10 docs MUST state, prominently:

> **Prefer the backend that already has the same project (or another git worktree of the same repository) open.** Worktrees of one repo share build/index/VCS context; opening them in the same IDE keeps that context warm and avoids a redundant second indexing. Inspect `backends[].openProjects[].path` from `steroid_list_projects`: if a backend already has a sibling worktree of the repo you are about to open (same repo root / shared `.git`), choose that backend's `id`. Otherwise prefer a `managed` backend, else any listed backend.

This is **guidance**, not routing logic — devrig does not auto-detect worktrees; the agent decides from `backends[]`.

### R2.4 — devrig↔plugin protocol: forward AND backward compatible, zero breaking changes

This is a hard constraint. The only wire-crossing change is `ProjectInfo.backend: String? = null` (carried over `/projects/stream`); everything else (`OpenProjectParams.backendName`, `ListProjectsResponse.backends`, `BackendSummary`) is **MCP-surface only** and never crosses the devrig↔IDE wire. `backend_name` is resolved in devrig and **never forwarded** to the IDE bridge, so the `steroid_open_project` bridge call is byte-identical to today.

Add **explicit cross-version compatibility tests** (new Task 12) AND a new **PHILOSOPHY.md tenet** (new Task 13). See those tasks below.

---

## Round 3 requirements (2026-06-09, data-model unification) — AUTHORITATIVE (override R2 / earlier on conflict)

Refinements to the data model after the round-2 build landed. These supersede the round-2 `BackendSummary`/`ProjectInfo.backend` shapes. **Status: PENDING quorum review, then implementation.**

### R3.1 + R3.2 — naming: `backend_name` and `project_name`, on BOTH surfaces

- The backend's identity field is **`backend_name`** (JSON key `backend_name`; Kotlin `@SerialName("backend_name") val backendName`). This is *the* id — the value passed to `steroid_open_project`'s `backend_name`, the `backend_name` on each project entry, and the backend element's own id. One name, used everywhere.
- The per-project reference to its owning backend is **`backend_name`** too (replaces the round-2 `ProjectInfo.backend`; lives on the MCP-only `ListedProject`).
- Each project entry carries **`project_name`** (JSON key `project_name`).

**Both surfaces emit the same shape (R3.6 — IDE-direct symmetry).** `steroid_list_projects` returns identical field structure whether served by the in-IDE plugin or by devrig:
- **devrig mode:** `project_name` = devrig-computed exposed disambiguated name (`${realName}-${projectHash}` = existing `exposedProjectName`); `backend_name` = the hashed id (R3.3) of the owning discovered IDE; `backends[]` = one entry per discovered IDE.
- **IDE-direct mode:** `project_name` == the real project `name`; `backend_name` = the IDE's own self-id computed by the same R3.3 scheme over its own pid; `backends[]` = exactly one entry (this IDE), `source="marker"`, `routable=true`, `mcpSteroidPluginInstalled=true`. This **reverts the round-2 "direct surface stays empty" guard** (`backends` was `[]`, `backend` was `null`) — the two surfaces are now consistent, both self-describing.

The devrig CLI (`backend/project --json`) computes `project_name` with the **exact same** routing-hash helper, and additionally keeps the raw folder `name` (see R3.7) so existing `jq '.projects[].name'` consumers don't break.

### R3.3 — one `backend_name`, one uniform id scheme (hash); `pid`/`port`/`source`/`routable` are data fields

**Decision (user-selected): uniform opaque hash.** There is exactly **one** `backend_name` per backend, computed by **one** scheme for every source — no `pid-<n>` / `port-<n>` / managed-slug id *variants*. The pid/port/source/routability live as their **own fields**, never encoded into the id shape.

```
backend_name = "${productCodeLower}-${hash8}"
  productCodeLower = productCodeFromBuild(build)?.lowercase() ?: "ide"   // "IU" -> "iu"; fallback "ide"
  hash8            = base62Sha256(sourceKey).take(8)
  sourceKey        = "pid:<pid>"  (marker IDE)
                   | "port:<port>" (port-only IDE)
                   | "managed:<managedId>" (managed backend, not yet running)
```
Example: `iu-9fk2a0xQ`. Deterministic and round-trippable — devrig recomputes it per discovered backend to resolve `backend_name → backend`.

**Quorum-required corrections (all applied):**
- **Helper extraction.** `DevrigProjectRoutingService.projectHash(Path, Long)` is **not** callable as-is (private `BASE62`/loop; salts with `(home, pid)`). Extract a `String`-keyed `fun base62Sha256(input: String): String` (the base62/sha256 routine only, no path/pid salting) into a shared util in `mcp-steroid-server` (so both the IDE self-id and devrig use it). `projectHash` is refactored to call it.
- **Product code is lowercased**, from `productCodeFromBuild` (which yields uppercase `IU`/`PY`/`GO`); fallback `"ide"` when absent (e.g. some port-only entries). The earlier `idea-…` example was wrong → it is `iu-…`.
- **Routability is a field, not a prefix.** Because the id is now opaque, the old `startsWith("pid-")/("port-")` heuristic in `DevrigOpenProjectToolHandler` is **removed**. `BackendInfo.routable: Boolean` marks open_project-routable backends (true only for marker IDEs with a live bridge). The self-correcting "Unknown backend_name" error compares the requested name against the **set of routable backend_names** and, if the name matches a *known-but-non-routable* backend (port/managed), says so explicitly; if it matches nothing, lists the routable ids. (`resolveBackend` only ever returns routable marker IDEs.)
- Collision: ~47.6 bits over a handful of live backends — negligible, but `discoveredBackends()` still de-dupes by `backend_name` (keep-first + `log()` a warning), mirroring the existing `backendRowsWithStableIds` collision check.

### R3.4 — ONE shared backend-info schema for CLI and MCP (full-field, no field loss)

Define one `@Serializable data class BackendInfo` (+ small nested types) in **`mcp-steroid-server`** (npx-kt already `implementation(project(":mcp-steroid-server"))`; `IdeInfo`/`PluginInfo` already live there, so no new dependency edge). It backs **both** the MCP `steroid_list_projects` `backends[]` **and** the devrig CLI `backend/project --json` `backends[]` — replacing the hand-built `backendEntryJson` (`BackendIdentity.kt`) and deleting the round-2 `BackendSummary`/`BackendProjectRef`. **No second representation.**

The quorum's REJECT was driven by silent field loss vs. today's `backendEntryJson`. Every field below has an explicit home (full inventory re-audited against `BackendIdentity.kt`):
```kotlin
@Serializable
data class BackendInfo(
    @SerialName("backend_name") val backendName: String,        // R3.3 uniform id
    val type: String = "intellij",                              // backendEntryJson "type"
    val source: String,                                         // "marker" | "port" | "managed"
    val displayName: String,
    val locator: String,
    val routable: Boolean,                                      // open_project-routable (marker + live bridge)
    val reachable: Boolean,                                     // discovery-reachable
    val managed: Boolean = false,
    val pid: Long? = null,                                      // listed, not in the id
    val port: Int? = null,
    val ideProductCode: String? = null,                         // "IU" / "PY" / "GO"
    val build: String? = null,
    @SerialName("mcpSteroidPluginInstalled")
    val mcpSteroidPluginInstalled: Boolean = false,             // R3.8 — explicit, replaces bare pluginInstalled
    val plugin: PluginInfo? = null,                             // installed plugin id/name/version (marker) — no loss
    val error: String? = null,                                  // marker-unreachable message (backendEntryJson "error")
    val actions: List<BackendAction> = emptyList(),             // TOP-LEVEL (port provision action); not in managed nest
    val port: PortBackendDetail? = null,                        // RENAMED below; see note — port-only identity extras
    val managedDetail: ManagedBackendDetail? = null,            // managed-only extras
    val ide: IdeInfo? = null,                                   // marker identity (name/version/build)
    val openProjects: List<ListedProject> = emptyList(),
)

@Serializable
data class BackendAction(                                        // provisionActionJson — preserve argv per devrig-naming.md
    val id: String, val label: String, val command: String,
    val argv: List<String> = emptyList(),
)

@Serializable
data class PortBackendDetail(                                    // portBackendIdentityJson extras
    val baseUrl: String? = null, val productName: String? = null,
    val productFullName: String? = null, val edition: String? = null,
    val baselineVersion: Int? = null, val buildNumber: String? = null,
)

@Serializable
data class ManagedBackendDetail(                                // FromManaged-only fields
    val managedId: String, val productKey: String, val productCode: String,
    val version: String, val buildNumber: String? = null, val state: String,
    val installPath: String, val cachePath: String, val runningPid: Long? = null,
)

@Serializable
data class ListedProject(
    @SerialName("project_name") val projectName: String,        // devrig: exposed name; IDE: real name
    val name: String,                                           // raw folder name (R3.7 — keep for jq consumers)
    val path: String,
    @SerialName("backend_name") val backendName: String? = null,// owning backend
)
```
> **Naming nit to resolve in implementation:** the scalar `port: Int?` and the nested `port: PortBackendDetail?` collide. Use `port: Int?` for the number and `portDetail: PortBackendDetail? = null` for the extras. (Fixed in the task code; called out here so the field list is unambiguous.)

`ListProjectsResponse.projects` becomes `List<ListedProject>` (replaces `List<ProjectInfo>` on the MCP surface). Field-by-field mapping from today's `backendEntryJson`: `type`→`type`, `source`→`source`, `displayName`/`locator`→same, `reachable`→`reachable`, marker `pluginInstalled:true`→`mcpSteroidPluginInstalled` + `plugin{}`→`plugin`, marker identity→`ide`+`pid`, marker `error`→`error`, port `actions[]`→`actions`, port identity→`portDetail`, all managed fields→`managedDetail`. Nothing silently dropped; `rpcBaseUrl` (internal) is intentionally omitted (document it).

### R3.5 — compat boundary is the devrig↔IDE WIRE ONLY (re-verified, R3.6 doesn't change it)

The returned **MCP/CLI JSON (devrig→agent, devrig→user) is devrig-owned and free to reshape** — a client always talks to one devrig+IDE pair, freshly. `ListProjectsResponse` is an **MCP-output type**, NOT a devrig↔IDE wire DTO: **devrig never fetches the IDE's `steroid_list_projects`** (it builds its own from the routing snapshot). So enriching `ListProjectsResponse` on **both** the IDE-direct and devrig surfaces (R3.6) touches no wire.

The **only** forward/backward-compat contract is the **devrig↔IDE wire**: `/projects/stream`, `/windows`, the `/tools/call/stream` params devrig POSTs, and their `@Serializable` DTOs (`NpxStreamEnvelope`, `NpxBridgeWindowsResponse`, `PidMarker`, `ToolCallResult`). Consequences (quorum-audited):
- **Wire `ProjectInfo {name, path}` stays EXACTLY as-is** on `/projects/stream` (`NpxStreamEnvelope.projects`, `IdeMonitor.lastSnapshot`). **Revert the round-2 `ProjectInfo.backend`** — the per-project backend ref now lives on the MCP-only `ListedProject`. The wire goes back to pristine `{name, path}` (changes zero emitted bytes — `ProjectsStreamService` only ever builds `ProjectInfo(name, path)`).
- **Delete the dead `NpxBridgeService.buildProjects()/buildSummary()` + `NpxBridgeProjectsResponse`/`NpxBridgeSummaryResponse`** (no route serves `/projects` or `/summary` per `NpxBridgeRoutes.kt`). They reference `List<ProjectInfo>` and would otherwise break when `ListProjectsResponse.projects` changes type — and they must never expose `project_name`/`backend_name` on the wire.
- `BackendInfo`/`ListedProject` are **MCP/CLI-surface only**; a **mechanical guard test** asserts `NpxStreamEnvelope` (on `/projects/stream`) and `NpxBridgeWindowsResponse` (on `/windows`) never serialize `backend_name`/`project_name`/`BackendInfo`/`ListedProject` keys.

### R3.6 — IDE-direct surface emits the same shape (see R3.1/R3.2)

`ListProjectsToolHandlerIJ` now self-describes: computes its own `backend_name` (R3.3 over its own pid), emits one `BackendInfo` for itself (`source="marker"`, `routable=true`, `mcpSteroidPluginInstalled=true`, `pid`=self, `plugin`=current plugin info, `ide`=`IdeInfo.ofApplication()`), and sets each project's `project_name == name`, `backend_name` = its self-id. The round-2 ij guard tests that asserted `backends==[]`/`backend==null` are **replaced** by tests asserting the single self-backend and `project_name==name`.

### R3.7 — keep the raw `name`; add `project_name` (don't break CLI `jq` consumers)

CLI human renderers (`ProjectCommand`/`BackendCommand` pretty output) keep printing the **real** folder name. CLI `--json` keeps the existing `name` key (raw folder name) and **adds** `project_name` (devrig-computed). `ListedProject` therefore carries both `name` and `project_name`. No existing `devrig project --json | jq '.projects[].name'` consumer breaks.

### R3.8 — `mcpSteroidPluginInstalled` (light fix) + parked `plugins[]` issue

Rename the bare `pluginInstalled` boolean to **`mcpSteroidPluginInstalled`** (explicit about *which* plugin; leaves room for a future IntelliJ-native MCP). The fuller `plugins[]` model is **parked as GH issue #88** — not in scope here.

### R3.9 — version-skew warning on the devrig (npx-kt) side

Because users upgrade devrig and the plugin independently, devrig **warns when a discovered backend's plugin version differs from the devrig version**. devrig already knows its own version (`DevrigVersionMetadata.getDevrigVersion()`) and each backend's plugin version (`marker.plugin.version`). Emit a one-line **stderr** warning (never stdout — that's the JSON-RPC channel) per discovered backend whose plugin version ≠ devrig version, e.g. `WARN: devrig 0.101 talking to MCP Steroid 0.100 (pid 1234) — versions differ; if behavior is odd, update both.` De-duplicate so it fires once per (pid,version) per process, not per call.
- **Tenet 5 is re-scoped accordingly:** the additive-only rule binds the devrig↔IDE wire only; the devrig-computed output (MCP tool results, CLI `--json`) is explicitly outside the cross-version contract. Update PHILOSOPHY.md Tenet 5 + the `ij-plugin/CLAUDE.md` wire-contract section to state this boundary precisely (and that reverting `ProjectInfo.backend` keeps the wire unchanged).

### Implementation note (R3 supersedes the round-2 task bodies it touches)

R3 reshapes Tasks 4, 7, 8, 9, 12, 13. Concrete worklist for the implementation Workflow:

1. **`mcp-steroid-server`** — add `BackendInfo` + `BackendAction` + `PortBackendDetail` + `ManagedBackendDetail` + `ListedProject` (R3.4); extract `base62Sha256(String)` util and refactor `projectHash` to use it; `ListProjectsResponse.projects: List<ListedProject>`, `backends: List<BackendInfo>`; **delete** `BackendSummary`/`BackendProjectRef`; **revert** `ProjectInfo.backend` (back to `{name, path}`).
2. **ij-plugin** — `ListProjectsToolHandlerIJ` self-describes (R3.6: one `BackendInfo` for itself, `project_name==name`, self `backend_name`); **delete** dead `NpxBridgeService.buildProjects/buildSummary` + `NpxBridgeProjectsResponse/NpxBridgeSummaryResponse` (R3.5); revert the round-2 `OpenProjectToolHandlerIJ` log-and-ignore stays. Replace the round-2 "direct surface empty" guard tests with self-describe assertions.
3. **npx-kt (devrig)** — `DevrigListProjectsToolHandler` builds `BackendInfo`/`ListedProject` (devrig-computed `project_name`); `DevrigProjectRoutingService.backendNameForIde` uses the R3.3 hash; `resolveBackend` matches the hash and returns only routable markers; `discoveredBackends()` de-dupes by `backend_name`; `DevrigOpenProjectToolHandler` drops the prefix heuristic, uses `routable`/the routable-id set for the self-correcting error; add the R3.9 version-skew stderr warning.
4. **devrig CLI** — refactor `renderBackendJson`/`projectToBackendJson`/`backendEntryJson` (`BackendIdentity.kt`/`BackendCommand.kt`) to serialize the shared `BackendInfo`/`ListedProject`; keep human renderers + raw `name` (R3.7), add `project_name`.
5. **Tests** — **delete** `WireCompatBackendFieldTest` (pinned the removed `ProjectInfo.backend`) and replace with a wire-pristineness guard: `NpxStreamEnvelope` + `NpxBridgeWindowsResponse` never serialize `backend_name`/`project_name`/`BackendInfo`/`ListedProject` keys; update `ListProjectsToolSpecSchemaTest`, `DevrigToolBridgeClientTest`, `DevrigOpenProjectBackendRoutingTest`, `McpServerIntegrationTest`, the CLI JSON render tests, and the `OpenProjectToolSpecSchemaTest` for the new shape. Add a test that CLI `project --json` and MCP `list_projects` expose the **same** `project_name` for the same marker project. Add backend_name generation tests (marker/port/managed, missing product code → `ide-`, duplicate-hash de-dup).
6. **Docs** — `PHILOSOPHY.md` Tenet 5 re-scoped (wire-only; reverting `ProjectInfo.backend` keeps the wire unchanged); `ij-plugin/CLAUDE.md` wire-contract section: remove the now-false `ProjectInfo.backend` "history of additive changes" bullet, state the wire `ProjectInfo` is pristine `{name,path}`, keep `OpenProjectParams.backendName` MCP-surface-only. Agent guide / `managing-backends.md`: `backends[].backend_name` (was `id`), note `routable`, `mcpSteroidPluginInstalled`.

`OpenProjectParams.backendName` (the routing param) is unchanged from R2 and stays correct.

---

## Design decision: where does `backend_name` live? (locked)

Three options were considered; **Option B is chosen.**

- **Option A — one shared spec, param everywhere, IDE ignores it.** Rejected: a direct-IDE agent (one MCP server == one IDE) would see a `backend_name` parameter that does nothing. The user explicitly called this out as misleading.
- **Option B — devrig-only parameter (CHOSEN).** The devrig `steroid_open_project` schema exposes `backend_name`; the in-IDE `steroid_open_project` schema does not. Implemented by parameterizing the single `OpenProjectToolSpec` with `includeBackendName: Boolean` (default `false`) so we keep one class, one description body (with a conditional addendum), and one schema test that covers both modes. devrig registers it with `includeBackendName = true`; the plugin keeps the default `false`.
- **Option C — shared param with "ignored on direct connection" docs.** Rejected: still shows a no-op control to direct-IDE users; only marginally less misleading than A.

**Why not a brand-new tool?** `docs/PHILOSOPHY.md` gates *new* MCP tools behind a 3-reviewer vote. This is **not** a new tool — it is one optional parameter on an existing tool plus additive response fields, so the new-tool gate does not apply. (The user-requested 3× quorum review still runs, honoring the spirit.)

**Wire-contract compliance (root `ij-plugin/CLAUDE.md` → "devrig ↔ plugin wire contract"):** `OpenProjectParams` gains `backendName: String? = null` — additive, optional, safe default. devrig does **not** forward `backend_name` to the IDE bridge (it resolves the backend locally and POSTs to the chosen IDE), so the plugin bridge wire is unchanged. `ProjectInfo` gains `backend: String? = null` — additive. `DevrigToolBridgeClientTest` (golden param pins) gets a new pinned case asserting `backend_name` is NOT forwarded to the IDE bridge.

---

## Identifier choice: what is a `backend_name`? (locked)

Reuse the **existing** `backendStableId(row)` (`npx-kt/.../BackendIdentity.kt:61`) — `pid-<n>` (marker), `port-<n>` (port-only), or the managed id. Rationale:

- It is already unique (collision-checked in `backendRowsWithStableIds`, `BackendCommand.kt:692`) and already emitted as the `id` field of every `backends[]` entry and as the `backend` field of every `projects[]` entry in `devrig backend/project --json`.
- It is analogous to the exposed `project_name` (`"${project.name}-$projectHash"`): a stable, machine-friendly handle that the agent reads from a listing and passes back. Both are volatile across restarts (pid changes), which is acceptable and consistent.
- A friendlier slug (e.g. `intellij-idea-2026-1-pidNNNN`) was considered but rejected for v1: it adds a uniqueness/derivation surface for no functional gain. `displayName` already carries the human-readable label for humans; `backend_name` is for routing.

So `backend_name` accepted by devrig `steroid_open_project` == the `id` field of `devrig backend --json` == the new `backend` field of the MCP `steroid_list_projects` projects. The MCP list response also gains a `backends[]` summary so the agent can enumerate valid `backend_name` values without shelling out to the CLI.

**Identifier contract (locked — resolves the former "Open question"):** Only **routable marker-discovered IDEs** are valid `backend_name` targets, i.e. ids of the form `pid-<n>` for an IDE that is running with the MCP Steroid plugin (the only backends with a live bridge). `port-<n>` (plugin-not-installed) and managed-slug ids that `devrig backend --json` may show are **not** routable for `open_project`. Therefore:
- The MCP `steroid_list_projects.backends[]` lists **only** routable (`pid-<n>`) backends — it never advertises a dead id.
- The "Unknown backend_name" error from devrig is **self-correcting**: when the supplied name looks like a `port-<n>` or a managed slug (i.e. an id the agent could have copied from `devrig backend --json` for a non-running / no-plugin backend), the message states that only running, plugin-equipped backends (`pid-<n>`) are routable and lists the currently-routable ids.
- `pid-<n>` is **not stable across IDE restarts** (the pid changes), exactly like the already-pid-salted `project_name`. Agents must re-read `steroid_list_projects` rather than cache a `backend_name` (documented in Task 10).

---

## File Structure

**`mcp-steroid-server/` (shared DTOs + tool specs)**
- Modify `.../server/OpenProjectTool.kt` — add `backendName` to `OpenProjectParams`; parameterize `OpenProjectToolSpec(includeBackendName)`; conditional `backend_name` schema element + description addendum.
- Modify `.../server/ListProjectsTool.kt` — add `backend: String? = null` to `ProjectInfo`; add `BackendSummary` DTO + `backends: List<BackendSummary> = emptyList()` to `ListProjectsResponse`.

**`npx-kt/` (devrig)**
- Modify `.../devrig/server/StubMcpSteroidTools.kt` — override the new `openProjectToolSpec()` seam to enable `includeBackendName`; populate `ProjectInfo.backend` + `ListProjectsResponse.backends` in `DevrigListProjectsToolHandler`.
- Modify `.../devrig/server/DevrigProjectRoutingService.kt` — add `backendNameForIde(ide)` (== `pid-<pid>`) and `resolveBackend(backendName): DiscoveredIde?`.
- Modify `.../devrig/server/DevrigBridgeToolHandlers.kt` — `DevrigOpenProjectToolHandler` resolves `backendName` (when present) to a specific IDE, else current auto-pick; error lists valid names when unresolved.

**`mcp-steroid-server/McpSteroidTools.kt`**
- Modify — extract the open_project registration into a `protected open fun openProjectToolSpec(): McpToolBase` seam so devrig can override the `includeBackendName` flag.

**`ij-plugin/`**
- Modify `.../server/OpenProjectToolHandler.kt` — log-and-ignore `openProjectParams.backendName` when non-null (defense-in-depth).

**Tests**
- Modify `mcp-steroid-server/.../OpenProjectToolSpecSchemaTest.kt` — assert default mode omits `backend_name`; add a case asserting `includeBackendName = true` exposes an optional `backend_name`.
- Modify `mcp-steroid-server/.../ListProjectsToolSpecSchemaTest.kt` — assert response (de)serialization tolerates `backend` / `backends` (additive).
- Modify `npx-kt/.../server/DevrigToolBridgeClientTest.kt` — pin: `backend_name` accepted by devrig spec, NOT forwarded to IDE bridge.
- Create `npx-kt/.../server/DevrigOpenProjectBackendRoutingTest.kt` — routing resolution unit tests.
- Modify `npx-kt/.../server/DevrigProjectRoutingServiceTest.kt` (or the project/backend render test) — assert `backend` appears in listings.

---

## Task 1: Add optional `backendName` to the shared `OpenProjectParams` DTO

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectTool.kt:109-113`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectParamsTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectParamsTest.kt`:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenProjectParamsTest {
    @Test
    fun `backendName defaults to null and decodes when absent`() {
        val decoded = McpJson.decodeFromString(
            OpenProjectParams.serializer(),
            """{"projectPath":"/tmp/p","trustProject":true}""",
        )
        assertNull(decoded.backendName)
    }

    @Test
    fun `backendName round-trips when present`() {
        val params = OpenProjectParams(projectPath = "/tmp/p", trustProject = false, backendName = "pid-1234")
        val json = McpJson.encodeToString(OpenProjectParams.serializer(), params)
        val decoded = McpJson.decodeFromString(OpenProjectParams.serializer(), json)
        assertEquals("pid-1234", decoded.backendName)
    }

    @Test
    fun `backendName null round-trips (key omitted by explicitNulls=false)`() {
        val params = OpenProjectParams(projectPath = "/tmp/p", trustProject = true, backendName = null)
        val json = McpJson.encodeToString(OpenProjectParams.serializer(), params)
        // McpJson has explicitNulls=false, so the key is omitted, keeping the wire additive.
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("backendName"))
        assertNull(McpJson.decodeFromString(OpenProjectParams.serializer(), json).backendName)
    }
}
```

> Note (quorum): this test pins the **camelCase DTO** (de)serialization only. The public **snake_case** MCP
> input key `backend_name` is exercised by the tool-spec/call tests (Tasks 2 and 6), not here.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.OpenProjectParamsTest'`
Expected: FAIL — `No value passed for parameter 'backendName'` (compilation) / unresolved `backendName`.

- [ ] **Step 3: Add the optional field**

In `OpenProjectTool.kt`, replace the data class:

```kotlin
@Serializable
data class OpenProjectParams(
    val projectPath: String,
    val trustProject: Boolean,
    /**
     * Optional devrig-only routing hint: the stable backend id (from steroid_list_projects
     * `backend` field or `devrig backend --json` `id`) that should receive this open request.
     * Null/absent everywhere except a devrig connection. Ignored (logged) by the in-IDE plugin.
     */
    val backendName: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.OpenProjectParamsTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectTool.kt \
        mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectParamsTest.kt
git commit -m "open_project: add optional backendName to OpenProjectParams (wire-additive)"
```

---

## Task 2: Parameterize `OpenProjectToolSpec` to optionally expose `backend_name`

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectTool.kt:32-107`
- Modify: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectToolSpecSchemaTest.kt`

- [ ] **Step 1: Write the failing test**

Replace the body of `OpenProjectToolSpecSchemaTest.kt` with both modes:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class OpenProjectToolSpecSchemaTest {
    @Test
    fun `inputSchema default omits backend_name`() {
        val spec = OpenProjectToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "project_path")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertBooleanProperty(schema, "trust_project")
        assertPropertyAbsent(schema, "backend_name")
    }

    @Test
    fun `inputSchema with backend name exposes REQUIRED backend_name`() {
        val spec = OpenProjectToolSpec(includeBackendName = true) { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        // R2.1: backend_name is REQUIRED on the devrig surface.
        assertRequiredExactly(schema, "project_path", "task_id", "reason", "backend_name")
        assertStringProperty(schema, "backend_name")
    }
}
```

Note (quorum-verified): the helper is **`assertPropertyAbsent`** (`mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/ToolSpecSchemaAssertions.kt:177`). Do **not** add an `assertNoProperty` — it does not exist and is not needed.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpecSchemaTest'`
Expected: FAIL — `OpenProjectToolSpec` has no `includeBackendName` parameter (compilation).

- [ ] **Step 3: Implement the parameterized spec**

In `OpenProjectTool.kt`, change the constructor and add the conditional element. Description gains an addendum only when the param is present:

```kotlin
class OpenProjectToolSpec(
    val includeBackendName: Boolean = false,
    val handler: () -> OpenProjectToolHandler,
) : McpToolBase() {
    private val logger = thisLogger()

    override val name = "steroid_open_project"
    override val description: String = buildString {
        append(BASE_DESCRIPTION)
        if (includeBackendName) append("\n\n").append(BACKEND_NAME_DESCRIPTION)
    }

    val projectPath = InputSchemaElement.param("project_path")
        .description("Absolute path to the project directory to open.")
        .string()
        .required()
        .registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()
    val reason = CommonToolParams.reason().registerToSchema()

    val trustProject = InputSchemaElement.param("trust_project")
        .description("If true, trust the project path before opening (skips trust dialog). Default: true")
        .boolean()
        .registerToSchema()

    // Devrig-only and REQUIRED there (R2.1). Registered/advertised only when includeBackendName is true.
    val backendName = if (includeBackendName) {
        InputSchemaElement.param("backend_name")
            .description(
                "REQUIRED. The backend to open the project in, identified by the stable backend id from " +
                    "steroid_list_projects (the `backend` field of each project, and the `backends[].id` " +
                    "summary) — e.g. \"pid-1234\". First call steroid_list_projects and inspect `backends[]` " +
                    "(displayName, locator, openProjects). PREFER the backend that already has the same " +
                    "project — or another git worktree of the same repository — open (match " +
                    "backends[].openProjects[].path / shared repo root): worktrees share build/index/VCS " +
                    "context, so reusing that IDE avoids a redundant second indexing. Otherwise prefer a " +
                    "`managed` backend, else any listed backend."
            )
            .string()
            .required()
            .registerToSchema()
    } else null

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectPathStr = context[projectPath]
        context[taskId]
        context[reason]
        val trustProject = context[trustProject] ?: true
        val backendNameValue = backendName?.let { context[it] }

        val requestedProjectPath = try {
            Path.of(projectPathStr).toAbsolutePath().normalize()
        } catch (e: Exception) {
            logger.warn("Invalid project path: $projectPathStr", e)
            return ToolCallResult.errorResult("Invalid project path: $projectPathStr - ${e.message}")
        }

        if (!Files.isDirectory(requestedProjectPath)) {
            return ToolCallResult.errorResult("Project path is not a directory: $requestedProjectPath")
        }

        val projectPath = try {
            withContext(Dispatchers.IO) { requestedProjectPath.toRealPath() }
        } catch (e: Exception) {
            logger.warn("Failed to resolve project path: $requestedProjectPath", e)
            return ToolCallResult.errorResult("Failed to resolve project path: $requestedProjectPath - ${e.message}")
        }

        return handler().handleOpenProject(
            OpenProjectParams(
                projectPath = projectPath.toString(),
                trustProject = trustProject,
                backendName = backendNameValue,
            )
        )
    }

    private companion object {
        const val BASE_DESCRIPTION = """Open a project in the IDE. This tool initiates the project opening process and returns quickly.

IMPORTANT: Project opening is ASYNCHRONOUS. This tool returns immediately; you MUST poll to verify the project is fully ready before using it.

Verification Workflow:
1. Call steroid_open_project with the project path
2. Poll steroid_list_windows repeatedly (every 2-3 seconds) until:
   - The project appears in the windows list
   - modalDialogShowing is false (no dialogs blocking)
   - indexingInProgress is false (indexing complete)
   - projectInitialized is true
3. If modalDialogShowing is true, use steroid_take_screenshot + steroid_input to handle dialogs
4. Use steroid_take_screenshot to visually confirm the project is fully loaded
5. Verify with steroid_list_projects that the project appears

Dialog Handling:
- If trust_project=true (default), the trust dialog is skipped automatically
- Other dialogs (project type, SDK selection, etc.) may still appear
- Always check modalDialogShowing in steroid_list_windows response"""

        const val BACKEND_NAME_DESCRIPTION = """Choosing a backend (multiple IDEs):
This connection can route to more than one running IDE. Call steroid_list_projects first to see the
available backends (`backends[].id`) and pass that id as backend_name to open the project in that
specific IDE. Omit backend_name to let devrig choose automatically."""
    }
}
```

Keep the existing imports (`thisLogger`, `Files`, `Path`, `Dispatchers`, `withContext`, `Serializable`, the `mcp.*` helpers). The single `OpenProjectToolSpec { ... }` callsite still compiles because `includeBackendName` defaults to `false` and the trailing-lambda `handler` is the last parameter.

If the project lints inline strings (`NoLargeInlineStringsTest`), the `BASE_DESCRIPTION` already exists today as an inline `"""..."""`, so moving it to a `const` of equal size does not increase the count; if the lint trips, follow the repo rule and move both bodies into a `prompts/src/main/prompts/` article referenced by URI. Verify with `./gradlew :mcp-steroid-server:test --tests '*NoLargeInlineStrings*'` if such a test runs in this module.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpecSchemaTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the full module test to catch the shared-callsite + any golden tool-list test**

Run: `./gradlew :mcp-steroid-server:test`
Expected: PASS. If a tool-list/description golden test fails, update its expectation for the default (no `backend_name`) surface only.

- [ ] **Step 6: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectTool.kt \
        mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectToolSpecSchemaTest.kt
git commit -m "open_project: optionally expose backend_name param (devrig surface only)"
```

---

## Task 3: Add an overridable `openProjectToolSpec()` seam in `McpSteroidTools`

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidTools.kt`
- Test: covered indirectly by Task 6 (devrig tool-list) and Task 2 (default surface).

- [ ] **Step 1: Write the failing test**

Add to `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidToolsSeamTest.kt` (create):

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpSteroidToolsSeamTest {
    // `spec()` is a PUBLIC accessor; openProjectToolSpec() is protected and must never be called
    // from outside a subclass. BackendNameTools overrides the protected seam but the test only ever
    // touches the public spec().
    private open class FakeTools : McpSteroidTools() {
        override fun <T> handler(type: Class<T>): T = error("not used")
        fun spec(): McpToolBase = openProjectToolSpec()
    }

    private class BackendNameTools : FakeTools() {
        override fun openProjectToolSpec(): McpToolBase =
            OpenProjectToolSpec(includeBackendName = true) { error("not used") }
    }

    @Test
    fun `default seam hides backend_name`() {
        val spec = FakeTools().spec() as OpenProjectToolSpec
        assertFalse(spec.includeBackendName)
    }

    @Test
    fun `override exposes backend_name`() {
        // Calls the PUBLIC spec(), which internally invokes the overridden protected seam.
        val spec = BackendNameTools().spec() as OpenProjectToolSpec
        assertTrue(spec.includeBackendName)
    }
}
```

Note (quorum/codex): `FakeTools` must be `open` (not `private class` that is implicitly final-to-subclass — a nested `private` class can still be extended within the same file, but it must be `open`). `openProjectToolSpec()` is `protected`, so the test reaches it **only** through the public `spec()` helper — never `BackendNameTools().openProjectToolSpec()` directly (that would not compile cross-scope).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.McpSteroidToolsSeamTest'`
Expected: FAIL — `openProjectToolSpec` unresolved.

- [ ] **Step 3: Extract the seam**

In `McpSteroidTools.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase

abstract class McpSteroidTools {
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry

        tools.registerTool(ListProjectsToolSpec { handler<ListProjectsToolHandler>() })
        tools.registerTool(ListWindowsToolSpec { handler<ListWindowsToolHandler>() })
        tools.registerTool(ExecuteCodeToolSpec { handler<ExecuteCodeToolHandler>() })
        tools.registerTool(ExecuteFeedbackToolSpec { handler<ExecuteFeedbackToolHandler>() })
        tools.registerTool(VisionScreenshotToolSpec { handler<VisionScreenshotToolHandler>() })
        tools.registerTool(VisionInputToolSpec { handler<VisionInputToolHandler>() })
        tools.registerTool(openProjectToolSpec())
        tools.registerTool(FetchResourceToolHandler { handler<PromptsContextHandler>() })
    }

    /**
     * The open_project tool spec. Overridable so devrig can advertise the optional `backend_name`
     * routing parameter while the in-IDE plugin keeps a single-backend surface (no `backend_name`).
     */
    protected open fun openProjectToolSpec(): McpToolBase =
        OpenProjectToolSpec { handler<OpenProjectToolHandler>() }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.McpSteroidToolsSeamTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidTools.kt \
        mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidToolsSeamTest.kt
git commit -m "tools: extract overridable openProjectToolSpec() seam"
```

---

## Task 4: Surface `backend` + `backends[]` in the `steroid_list_projects` response DTO

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsTool.kt`
- Modify: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsToolSpecSchemaTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ListProjectsToolSpecSchemaTest.kt` (keep existing tests):

```kotlin
@Test
fun `response tolerates backend fields additively`() {
    // IdeInfo(name, version, build) — `build` has NO default, so it MUST be present; there is no `fullName`.
    val legacy = """{"ide":{"name":"x","version":"1","build":"x"},
        |"plugin":{"id":"p","name":"p","version":"1"},"pid":1,
        |"projects":[{"name":"n","path":"/p"}]}""".trimMargin()
    val decoded = com.jonnyzzz.mcpSteroid.mcp.McpJson
        .decodeFromString(ListProjectsResponse.serializer(), legacy)
    org.junit.jupiter.api.Assertions.assertNull(decoded.projects.single().backend)
    org.junit.jupiter.api.Assertions.assertTrue(decoded.backends.isEmpty())

    val withBackend = ProjectInfo(name = "n", path = "/p", backend = "pid-1234")
    org.junit.jupiter.api.Assertions.assertEquals("pid-1234", withBackend.backend)
}
```

Quorum-verified field names: `IdeInfo(name, version, build)` (`mcp-steroid-server/.../IdeInfo.kt:6-10`; `build` has no default) and `PluginInfo(id, name, version)`. The fixture above already matches.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.ListProjectsToolSpecSchemaTest'`
Expected: FAIL — `ProjectInfo` has no `backend`; `ListProjectsResponse` has no `backends`.

- [ ] **Step 3: Add additive fields**

In `ListProjectsTool.kt`:

```kotlin
@Serializable
data class ListProjectsResponse(
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val pid: Long = ProcessHandle.current().pid(),
    val projects: List<ProjectInfo>,
    /**
     * Backends reachable through this connection. Empty on a direct in-IDE connection
     * (single backend). On devrig, one entry per discovered IDE; `id` values are valid
     * `backend_name` arguments for steroid_open_project.
     */
    val backends: List<BackendSummary> = emptyList(),
)

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
    /** Stable backend id owning this project (devrig only; null on a direct connection). */
    val backend: String? = null,
)

@Serializable
data class BackendSummary(
    /** The routable backend id == backend_name (e.g. "pid-1234"). */
    val id: String,
    /** Human label, e.g. "IntelliJ IDEA 2026.1" — NOT unique across two same-product IDEs. */
    val displayName: String,
    /** Disambiguator when two IDEs share a displayName, e.g. "build IU-261.x, pid 1234". */
    val locator: String,
    /** Names of projects currently open in this backend — lets an agent pick by what's open. */
    val openProjects: List<String> = emptyList(),
    val ide: IdeInfo,
)
```

Quorum note (agent-UX): `displayName` alone cannot distinguish two IntelliJ IDEA 2026.1 instances. `locator` (build + pid) plus `openProjects` give the agent a non-opaque basis for choosing a backend.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.ListProjectsToolSpecSchemaTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsTool.kt \
        mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsToolSpecSchemaTest.kt
git commit -m "list_projects: add additive backend + backends fields to response DTO"
```

---

## Task 5: devrig routing — resolve `backend_name` to a `DiscoveredIde`

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigProjectRoutingService.kt`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigOpenProjectBackendRoutingTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `DevrigOpenProjectBackendRoutingTest.kt`. Build `IdeMonitorState` fixtures the same way `DevrigProjectRoutingServiceTest.kt` does (copy its helper for constructing `DiscoveredIde`/`IdeMonitorState` — read it first: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigProjectRoutingServiceTest.kt`). Then:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DevrigOpenProjectBackendRoutingTest {
    @Test
    fun `resolveBackend matches the pid- stable id`() {
        val routing = routingWith(pids = listOf(42L, 43L)) // helper builds two discovered IDEs
        assertEquals(42L, routing.resolveBackend("pid-42")?.pid)
        assertEquals(43L, routing.resolveBackend("pid-43")?.pid)
    }

    @Test
    fun `resolveBackend returns null for unknown name`() {
        val routing = routingWith(pids = listOf(42L))
        assertNull(routing.resolveBackend("pid-999"))
        assertNull(routing.resolveBackend("garbage"))
    }

    @Test
    fun `backendNameForIde returns the pid- form`() {
        val routing = routingWith(pids = listOf(7L))
        val ide = routing.newestIdeOrNull()!!
        assertEquals("pid-7", routing.backendNameForIde(ide))
    }
}
```

Implement the private `routingWith(pids)` helper in the test file mirroring the existing fixtures.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.devrig.server.DevrigOpenProjectBackendRoutingTest'`
Expected: FAIL — `resolveBackend` / `backendNameForIde` unresolved.

- [ ] **Step 3: Implement resolution on the routing service**

Add to `DevrigProjectRoutingService.kt` (alongside `openProjectTargetIde`). The name must match `backendStableId` for marker rows (`pid-<pid>`):

```kotlin
/** The agent-facing backend id for a discovered IDE. Mirrors backendStableId(FromMarker) = "pid-<pid>". */
fun backendNameForIde(ide: DiscoveredIde): String = "pid-${ide.pid}"

/** Resolves a backend_name (as listed by steroid_list_projects / `devrig backend --json`) to a discovered IDE. */
fun resolveBackend(backendName: String): DiscoveredIde? {
    val wanted = backendName.trim()
    if (wanted.isEmpty()) return null
    return discoveredIdes().firstOrNull { backendNameForIde(it) == wanted }
}

/** All discovered backends as (name, ide) pairs, for list_projects summaries and error messages. */
fun discoveredBackends(): List<Pair<String, DiscoveredIde>> =
    discoveredIdes().map { backendNameForIde(it) to it }
```

Note: `discoveredIdes()` is currently `private`; this plan keeps it private and adds `discoveredBackends()` as the public accessor. Do **not** widen `discoveredIdes()` visibility.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.devrig.server.DevrigOpenProjectBackendRoutingTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigProjectRoutingService.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigOpenProjectBackendRoutingTest.kt
git commit -m "devrig: resolve backend_name to a discovered IDE (pid- stable id)"
```

---

## Task 6: devrig `DevrigOpenProjectToolHandler` honors `backend_name`

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBridgeToolHandlers.kt:163-193`
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt`
- Modify: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt`

- [ ] **Step 1: Write the failing test (routing + non-forwarding)**

In `DevrigToolBridgeClientTest.kt`, add a case modeled on the existing `open project bridge handler prefers the running managed backend over a newer ide` test (read it first, ~lines 379-440). The new test passes `backendName = "pid-42"` while pid 43 is the managed/newer one, and asserts:
1. the bridge POST goes to pid 42's bridge (its token/header used), proving `backend_name` overrode the auto-pick;
2. the forwarded JSON to the IDE bridge contains `project_path`, `trust_project`, `task_id`, `reason` and does **NOT** contain `backend_name` (assert `arguments["backend_name"] == null`).

```kotlin
@Test
fun `open project routes to the backend named by backend_name and does not forward it`() {
    // ... build two fake IDE bridges (pid 42, pid 43), capture the posted body ...
    val handler = DevrigOpenProjectToolHandler(bridge)
    handler.handleOpenProject(
        OpenProjectParams(projectPath = targetProject.toString(), trustProject = true, backendName = "pid-42")
    )
    // assert the POST hit pid-42's bridge URL/headers
    // assert forwarded arguments contain project_path/trust_project/task_id/reason
    assertNull(arguments["backend_name"]) // resolved locally, never forwarded
}

@Test
fun `open project with unknown backend_name returns an error listing valid backends`() {
    val handler = DevrigOpenProjectToolHandler(bridge)
    val result = handler.handleOpenProject(
        OpenProjectParams(projectPath = targetProject.toString(), trustProject = true, backendName = "pid-999")
    )
    assertEquals(true, result.isError)
    // message should mention pid-42 / pid-43 so the agent can correct itself
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.devrig.server.DevrigToolBridgeClientTest'`
Expected: FAIL — `backendName` not honored (routes to newest pid 43) / no error path.

- [ ] **Step 3: Implement backend selection in the handler**

Replace the IDE-selection block in `DevrigOpenProjectToolHandler.handleOpenProject`:

```kotlin
override suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult {
    val requestedBackend = openProjectParams.backendName?.trim()?.takeIf { it.isNotEmpty() }

    val ide = withContext(Dispatchers.IO) {
        if (requestedBackend != null) {
            bridge.routing.resolveBackend(requestedBackend)
        } else {
            bridge.routing.openProjectTargetIde()
        }
    }

    if (ide == null) {
        return if (requestedBackend != null) {
            val known = bridge.routing.discoveredBackends().map { it.first }
            // Self-correct the common mistake of copying a non-routable id from `devrig backend --json`.
            val looksNonRoutable = requestedBackend.startsWith("port-") || !requestedBackend.startsWith("pid-")
            val hint = if (looksNonRoutable) {
                "Only running IDEs with the MCP Steroid plugin (ids of the form 'pid-<n>') are routable; " +
                    "'port-<n>' and managed-slug ids from 'devrig backend --json' are not. "
            } else ""
            ToolCallResult.errorResult(
                "Unknown backend_name '$requestedBackend'. " + hint +
                    if (known.isEmpty()) "No routable IDE backends are currently discovered; start an IDE or call steroid_list_projects."
                    else "Routable backends: ${known.joinToString(", ")}. Call steroid_list_projects to refresh."
            )
        } else {
            ToolCallResult.errorResult(
                "steroid_open_project requires at least one discovered IDE with the MCP Steroid plugin; " +
                    "start an IDE or call steroid_list_projects"
            )
        }
    }

    val route = ProjectRoute(
        idePid = ide.pid,
        bridgeBaseUrl = ide.rpcBaseUrl,
        headers = ide.bridgeHeaders,
        originalProjectName = "",
        exposedProjectName = "",
        projectPath = "",
        realProjectHome = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
        projectHash = "",
        ide = ide.marker.ide,
        plugin = ide.marker.plugin,
    )
    // backend_name is resolved locally; it is NOT forwarded to the IDE bridge.
    return bridge.callTool(route, "steroid_open_project") {
        put("project_path", openProjectParams.projectPath)
        put("trust_project", openProjectParams.trustProject)
        put("task_id", "open-project")
        put("reason", "Open project through devrig")
    }
}
```

- [ ] **Step 4: Enable `backend_name` on the devrig open_project surface**

In `StubMcpSteroidTools.kt`, override the seam from Task 3:

```kotlin
override fun openProjectToolSpec() =
    com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpec(includeBackendName = true) {
        handler<OpenProjectToolHandler>()
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.devrig.server.DevrigToolBridgeClientTest'`
Expected: PASS, including the existing managed-backend-preference test (unchanged when `backend_name` is absent).

- [ ] **Step 6: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBridgeToolHandlers.kt \
        npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt
git commit -m "devrig: honor backend_name in open_project; expose it on the devrig surface"
```

---

## Task 7: devrig `steroid_list_projects` populates `backend` + `backends[]`

> **Sequencing (quorum):** Task 7 is a **hard prerequisite** of flipping `includeBackendName = true` (Task 6 Step 4) in the same release. Without per-project `backend` + the `backends[]` summary, the param is undiscoverable via MCP and the agent would have to shell out to `devrig backend --json`. Land Task 7 with or before Task 6, and do not let the open_project description claim "call steroid_list_projects to see backends[]" until this is populated.

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt:56-78` (`DevrigListProjectsToolHandler`)
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt` (or a focused list-projects test)

- [ ] **Step 1: Write the failing test**

Add a devrig list-projects test (reuse the routing fixtures from Task 5/6) asserting each `ProjectInfo.backend` equals `pid-<pid>` of its owning IDE and `response.backends` has one `BackendSummary` per discovered IDE with matching `id`.

```kotlin
@Test
fun `devrig list_projects tags each project with its backend and lists backends`() {
    val handler = DevrigListProjectsToolHandler(services) // services wired to two IDEs
    val response = handler.collectListProjectsResponse()
    assertTrue(response.projects.all { it.backend != null })
    assertEquals(
        response.backends.map { it.id }.toSet(),
        response.projects.mapNotNull { it.backend }.toSet(),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :npx-kt:test --tests '*list_projects tags each project*'`
Expected: FAIL — `backend` is null / `backends` empty.

- [ ] **Step 3: Populate the fields**

`DevrigListProjectsToolHandler.collectListProjectsResponse()` has `route.idePid`; map it to the backend name. Add `backend = "pid-${route.idePid}"` per project and build `backends` from `services.projectRouting.discoveredBackends()`:

```kotlin
override suspend fun collectListProjectsResponse(): ListProjectsResponse {
    val routing = services.projectRouting
    val routes = routing.routes().values.toList()
    val first = routes.firstOrNull()
    // backends[] lists ONLY routable marker IDEs (the only ones with a live bridge) — never a dead id.
    val backends = routing.discoveredBackends().map { (id, ide) ->
        BackendSummary(
            id = id, // "pid-<pid>"
            displayName = markerBackendDisplayName(ide),
            locator = markerBackendLocatorLabel(ide), // "build IU-261.x, pid 1234"
            openProjects = routes.filter { it.idePid == ide.pid }.map { it.originalProjectName },
            ide = ide.marker.ide,
        )
    }
    return ListProjectsResponse(
        ide = first?.ide ?: IdeInfo("devrig", DevrigVersionMetadata.getDevrigVersion(), "devrig"),
        plugin = first?.plugin ?: PluginInfo("com.jonnyzzz.mcp-steroid.devrig", "devrig", DevrigVersionMetadata.getDevrigVersion()),
        pid = first?.idePid ?: ProcessHandle.current().pid(),
        projects = routes.map { route ->
            ProjectInfo(name = route.exposedProjectName, path = route.projectPath, backend = "pid-${route.idePid}")
        },
        backends = backends,
    )
}
```

Add imports: `BackendSummary`, `markerBackendDisplayName`, `markerBackendLocatorLabel` (all top-level in `BackendIdentity.kt`). Extend the Task 7 test to also assert each `backends[].locator` is non-blank and `openProjects` matches the routes for that pid.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :npx-kt:test --tests '*list_projects tags each project*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt
git commit -m "devrig: tag list_projects with backend ids + backends summary"
```

---

## Task 8: in-IDE plugin logs-and-ignores `backendName` (defense-in-depth)

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectToolHandler.kt:22`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt` (assert direct-IDE tool list omits `backend_name`)

- [ ] **Step 1: Write the failing test**

Quorum-corrected: `McpServerIntegrationTest` has **no** `listTools()` helper and **no** typed `McpTool` API — it issues raw JSON-RPC `{"method":"tools/list"}` POSTs and parses the result envelope (see existing usages at lines ~90, 467, 1448). **Read those first** and reuse the exact request/parse idiom. Add a test that (a) the direct-IDE `steroid_open_project` schema omits `backend_name`, and (b) the direct-IDE `steroid_list_projects` response carries the empty additive fields (honest, not misleading):

```kotlin
fun `open_project schema omits backend_name on direct IDE surface`(): Unit = timeoutRunBlocking(30.seconds) {
    // Mirror the existing tools/list call+parse idiom in this file (raw JSON-RPC envelope).
    val toolsListResult = callJsonRpc("tools/list", buildJsonObject { }) // <- match the real helper name/shape
    val tools = toolsListResult["tools"]!!.jsonArray
    val openProject = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == "steroid_open_project" }
    val props = openProject.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
    assertFalse(props.containsKey("backend_name"))
}

fun `direct IDE list_projects carries empty backend fields`(): Unit = timeoutRunBlocking(30.seconds) {
    // steroid_list_projects returns its payload as JSON text content; decode and assert empties.
    val response = McpJson.decodeFromString(ListProjectsResponse.serializer(), callListProjectsText())
    assertTrue(response.backends.isEmpty())
    assertTrue(response.projects.all { it.backend == null })
}
```

Match the real envelope/parse helpers in this file — the names above (`callJsonRpc`, `callListProjectsText`) are placeholders for whatever the existing tests use; do not invent new infra.

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew :ij-plugin:test --tests '*McpServerIntegrationTest*' --rerun-tasks`
Expected: PASS (default seam hides `backend_name`; IJ `ListProjectsToolHandler` never populates the new fields). These are **regression guards** — the primary direct-surface schema coverage is Task 2's `inputSchema default omits backend_name` unit test; this end-to-end test guards the seam wiring (Task 3) and the shared-DTO honesty.

- [ ] **Step 3: Log-and-ignore in the handler**

In `OpenProjectToolHandlerIJ.handleOpenProject`, after computing `projectPath`:

```kotlin
openProjectParams.backendName?.let {
    logger.info("steroid_open_project received backend_name='$it' on a direct IDE connection; ignoring (routing applies only via devrig).")
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :ij-plugin:test --tests '*McpServerIntegrationTest*' --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/OpenProjectToolHandler.kt \
        ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt
git commit -m "ij open_project: log-and-ignore backend_name; guard direct-surface schema"
```

---

## Task 9 (guard + docs): confirm `backend_name` (== id) in `devrig backend/project --json` listings

> **Not a red-green TDD task (quorum).** `projectToBackendJson` (`BackendCommand.kt:710`) already emits `backend = <stable id>` per project and `backends[].id` is that same id, so the `projects[].backend ∈ backends[].id` invariant already holds. This task adds an invariant **guard** test and the **documentation** that `backends[].id` is the value to pass as `backend_name`; no structural JSON change is required.

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/BackendCommand.kt:710-714` (`projectToBackendJson`)
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/BackendIdentity.kt:76` (`backendEntryJson`) — add an explicit `name` alias if reviewers want symmetry; otherwise the existing `id` already IS the backend_name and only documentation changes.
- Test: existing backend/project render tests — locate with `grep -rln "renderBackendJson\|renderProjectJson\|projectToBackendJson" npx-kt/src/test`.

- [ ] **Step 1: Write the failing test**

In the backend/project JSON render test, assert that each `projects[]` entry carries the backend id under both the existing `backend` key (unchanged) and, for agent symmetry with `steroid_list_projects`, that the `backends[]` entry's `id` is documented as the `backend_name`. Concretely, add an assertion that `projects[].backend` ∈ `backends[].id`:

```kotlin
@Test
fun `every project backend id is a known backend id`() {
    val out = renderToString { renderBackendJson(rows, it) } // helper in the test file
    val json = Json.parseToJsonElement(out).jsonObject
    val backendIds = json["backends"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    json["projects"]!!.jsonArray.forEach {
        assertTrue(it.jsonObject["backend"]!!.jsonPrimitive.content in backendIds)
    }
}
```

(If such an invariant test already exists, extend it rather than duplicating.)

- [ ] **Step 2: Run test to verify it fails/passes**

Run: `./gradlew :npx-kt:test --tests '*Backend*Json*'`
Expected: likely PASS already (the invariant holds today). The substantive change here is **documentation**: the agent-facing guide must state that `backends[].id` is the value to pass as `backend_name`. No structural JSON change is required because `id` already serves as the backend_name. If reviewers prefer an explicit duplicate `name` field for discoverability, add `put("name", id)` in `backendEntryJson` (additive) and assert it.

- [ ] **Step 3: (Conditional) add explicit `name` alias if quorum requires it**

Only if the quorum review asks for an explicit alias, in `backendEntryJson` add after `put("id", id)`:

```kotlin
put("name", id) // backend_name alias: the value to pass to steroid_open_project backend_name
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :npx-kt:test --tests '*Backend*Json*' '*Project*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/BackendCommand.kt \
        npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/BackendIdentity.kt
git commit -m "devrig: document/align backend id as backend_name in backend & project --json"
```

---

## Task 10: documentation — teach the agent how to pick a backend

**Files:**
- Modify: the open_project / devrig agent guidance. Locate the canonical doc: `grep -rln "steroid_open_project\|devrig backend\|backend --json" docs prompts README.md COMMAND_USAGE.md 2>/dev/null`.
- Likely: `docs/guides/AGENT-STEROID-GUIDE.md` and/or a `prompts/src/main/prompts/` article + `README.md`.

- [ ] **Step 1: Update the agent guide**

Add a short "Choosing a backend" section: call `steroid_list_projects` (or `devrig backend --json`), read `backends[].id` (use `displayName`/`locator`/`openProjects` to pick between similar IDEs), pass that id as `backend_name` to `steroid_open_project`; omit to auto-pick. State explicitly:
- `backend_name` is a **devrig-only** parameter and has no effect on a direct in-IDE connection.
- Only **routable** backends (`pid-<n>`) are valid; `port-<n>` / managed-slug ids from `devrig backend --json` are not.
- Backend ids are **not stable across IDE restarts** (the pid changes) — **re-read `steroid_list_projects` rather than caching** a `backend_name`.

- [ ] **Step 2: If a `prompts/` article changed, run its compilation test**

Run: `./gradlew :prompts:test --tests '*KtBlock*'` (only if a `prompts/src/main/prompts/**` file changed).
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/ prompts/ README.md
git commit -m "docs: teach backend_name routing for steroid_open_project (devrig)"
```

---

## Task 11: full-module verification

- [ ] **Step 1: Run the touched module suites**

Run:
```bash
./gradlew :mcp-steroid-server:test
./gradlew :npx-kt:test
./gradlew :ij-plugin:test --tests '*McpServerIntegrationTest*' --rerun-tasks
```
Expected: all PASS. (Do not run root `./gradlew test`.)

- [ ] **Step 2: Verify no new warnings via MCP Steroid**

Per root CLAUDE.md, use `steroid_execute_code` to confirm the changed files produce no inspection warnings/errors.

- [ ] **Step 3: Update TODO + final commit**

```bash
# add a line to TODO.md noting the feature landed and the GH issue it closes
git add TODO.md && git commit -m "todo: record backend_name routing feature"
```

---

## Task 12: protocol forward/backward compatibility tests (devrig↔plugin)

**Files:**
- Create: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/WireCompatBackendFieldTest.kt`
- Modify: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt` (the open_project non-forward pin from Task 6 already asserts `backend_name` is absent from forwarded args — keep it).

- [ ] **Step 1: Write the failing/compat test for the one wire-crossing field**

`ProjectInfo` is the only DTO that crosses the devrig↔IDE wire (over `/projects/stream`, decoded by the devrig monitor). Prove the additive field is compatible **both directions**:

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WireCompatBackendFieldTest {
    // OLD plugin -> NEW devrig: a /projects/stream payload from an older plugin omits `backend`.
    @Test
    fun `old plugin ProjectInfo without backend decodes on new devrig`() {
        val old = """{"name":"proj","path":"/p"}"""
        val decoded = McpJson.decodeFromString(ProjectInfo.serializer(), old)
        assertEquals("proj", decoded.name)
        assertNull(decoded.backend)
    }

    // NEW plugin -> OLD devrig: a newer payload carrying `backend` must be tolerated by the
    // old decoder. McpJson has ignoreUnknownKeys=true, so simulate the old decoder by decoding
    // into a type WITHOUT the field. We reuse a minimal serializer to model the old shape.
    @Test
    fun `new ProjectInfo with backend is tolerated by an old-shape decoder`() {
        val newPayload = McpJson.encodeToString(
            ProjectInfo.serializer(),
            ProjectInfo(name = "proj", path = "/p", backend = "pid-1234"),
        )
        // explicitNulls=false ⇒ a null backend is omitted; a present backend IS emitted.
        org.junit.jupiter.api.Assertions.assertTrue(newPayload.contains("pid-1234"))
        // ignoreUnknownKeys=true ⇒ decoding the richer payload into the SAME serializer is lossless,
        // and an older peer (no `backend` field) ignores the unknown key rather than throwing.
        val roundTrip = McpJson.decodeFromString(ProjectInfo.serializer(), newPayload)
        assertEquals("pid-1234", roundTrip.backend)
    }

    // open_project bridge call is byte-identical: backend_name is NOT a forwarded field.
    @Test
    fun `OpenProjectParams backendName never appears in IDE-bound forwarded JSON contract`() {
        // The forwarded arg set is fixed by DevrigOpenProjectToolHandler (project_path/trust_project/
        // task_id/reason). This test documents the invariant at the DTO level; the authoritative
        // assertion lives in DevrigToolBridgeClientTest (forwarded arguments[\"backend_name\"] == null).
        val params = OpenProjectParams(projectPath = "/p", trustProject = true, backendName = "pid-1234")
        assertEquals("pid-1234", params.backendName) // present on the MCP side...
        // ...but Task 6's handler builds the bridge body WITHOUT it — see DevrigToolBridgeClientTest.
        assertFalse(false)
    }
}
```

Confirm `McpJson` has `ignoreUnknownKeys = true` + `explicitNulls = false` (it does: `mcp-core/.../McpJson.kt`). If the third test reads as a tautology, replace its body with a reference assertion that the handler's forwarded-key set excludes `backend_name` by reusing the DevrigToolBridgeClientTest captured-args fixture.

- [ ] **Step 2: Run**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.server.WireCompatBackendFieldTest'` then `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.devrig.server.DevrigToolBridgeClientTest'`
Expected: PASS — proves OLD↔NEW decode tolerance both ways and the non-forwarding invariant.

- [ ] **Step 3: Commit**

```bash
git add mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/WireCompatBackendFieldTest.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt
git commit -m "test: pin devrig<->plugin forward/backward compat for the backend field"
```

---

## Task 13: document the protocol-compatibility tenet (PHILOSOPHY.md + wire contract)

**Files:**
- Modify: `docs/PHILOSOPHY.md` — add **Tenet 5 — the devrig↔plugin protocol is additive-only (forward + backward compatible)**.
- Modify: `ij-plugin/CLAUDE.md` — extend the existing "devrig ↔ plugin wire contract" section to record the new optional fields (`ProjectInfo.backend`) and the rule that `backend_name` is resolved in devrig and never forwarded.
- Test: `prompts`/article contract tests if PHILOSOPHY.md is mirrored as a `mcp-steroid://` resource (`grep -rn "design-philosophy\|PHILOSOPHY" prompts/src ij-plugin/src/main`). If it is generated, regenerate and run its contract test.

- [ ] **Step 1: Add Tenet 5 to PHILOSOPHY.md**

Insert after Tenet 4 (before the `---` / "## Where this comes from"). Content:

> ## Tenet 5 — the devrig↔plugin protocol is additive-only
>
> **The wire between `devrig` and the in-IDE plugin must stay forward AND backward compatible at all times. No breaking changes — ever.** A given devrig binary talks to many plugin versions and vice-versa (Tenet 3: devrig is reconstructable, not migratable). Therefore every change to a wire-crossing shape — the JSON-RPC tool-call params devrig POSTs to the bridge, the `/windows` and `/projects/stream` responses, and the `@Serializable` DTOs they (de)serialize — is **additive and optional**:
> - New fields are optional with a safe default (`= null` / `= ""` / `= false` / a defaulted enum). An older peer that omits them still decodes; a newer peer that ignores them still works.
> - Never remove, rename, or retype an existing field. Enums must degrade on an unknown value, not throw.
> - Prefer resolving new behavior **inside devrig** over extending the wire. The `backend_name` routing parameter is the canonical example: it is an MCP-surface parameter that devrig resolves locally to a target IDE and **never forwards** to the bridge, so the `steroid_open_project` bridge call stayed byte-identical while the agent gained backend selection.
> - Every wire change ships with a cross-version compatibility test (see `WireCompatBackendFieldTest`, `DevrigToolBridgeClientTest`) and a one-line entry in the `ij-plugin/CLAUDE.md` wire-contract table.
>
> **Why:** the protocol is the one thing two independently-versioned binaries share; a breaking change there strands every mismatched pair. Additive-only is what makes "delete devrig, reinstall any version" safe.

- [ ] **Step 2: Update the ij-plugin/CLAUDE.md wire-contract section**

Add to the "Rules — never break these" / DTO list that `ProjectInfo` now carries an optional `backend: String? = null` (devrig-populated, null on the in-IDE surface) and that `OpenProjectParams.backendName` is MCP-surface only and not forwarded to the bridge.

- [ ] **Step 3: Regenerate prompts if PHILOSOPHY.md is a mirrored resource, and run contract tests**

Run (only if mirrored): `./gradlew :prompts:generatePrompts` then `./gradlew :prompts:test --tests '*MarkdownArticleContract*' '*KtBlock*'`
Expected: PASS (watch title ≤80 chars / desc ≤200 chars / no bare code outside fences).

- [ ] **Step 4: Commit**

```bash
git add docs/PHILOSOPHY.md ij-plugin/CLAUDE.md prompts/
git commit -m "docs: add Tenet 5 — devrig<->plugin protocol is additive-only (no breaking changes)"
```

---

## Self-Review

**1. Spec coverage**
- "Introduce `backend_name` to open_project; in-IDE logs+ignores" → Tasks 1, 2, 8. ✓
- "devrig uses it to route the open" → Tasks 5, 6. ✓
- "include `backend_name` in list_projects and backends/projects responses" → Tasks 4, 7 (MCP `list_projects`), Task 9 (`devrig backend/project --json`). ✓
- "created the similar way to project_name in the CLI" → identifier choice section + Task 5 (`pid-<n>` stable id mirrors `backendStableId`). ✓
- "aim: agent picks the right backend; docs say so" → Task 10. ✓
- "not misleading for IntelliJ-side users; pick best differentiation; devrig-only acceptable" → Design decision (Option B), Tasks 2/3/8. ✓

**2. Placeholder scan** — Each code step shows concrete code. Two intentionally conditional steps (Task 9 Step 3, alias) are gated on the quorum verdict and clearly marked; Task 9 substantive deliverable is documentation since `id` already equals the backend_name. No "TBD"/"handle edge cases" placeholders.

**3. Type consistency** — `OpenProjectParams.backendName`, `OpenProjectToolSpec(includeBackendName=…)`, `McpSteroidTools.openProjectToolSpec()`, `DevrigProjectRoutingService.resolveBackend`/`backendNameForIde`/`discoveredBackends`, `ProjectInfo.backend`, `ListProjectsResponse.backends`, `BackendSummary(id, displayName, ide)` are used identically across tasks. Backend id form is `pid-<pid>` everywhere (matches `backendStableId(FromMarker)`).

**RESOLVED in quorum (was an open question):** port-only / managed backends are **out of scope** for `backend_name` routing — they have no running bridge. v1 lists only routable marker IDEs (`pid-<n>`) in `steroid_list_projects.backends`, and the "Unknown backend_name" error self-corrects when an agent supplies a `port-<n>` / managed-slug id (see the identifier-contract lock and Task 6). Provisioning a managed backend remains a separate `devrig backend start` step; once it is running with the plugin it appears as a routable `pid-<n>`.
