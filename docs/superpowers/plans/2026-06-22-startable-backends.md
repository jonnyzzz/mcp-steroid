# Startable backends + backend-listing simplification — Implementation Plan

> **Historical plan.** Startable managed backends shipped; the later IU-262 Remote Development work added
> a frontendless launcher and separated backend reachability, project-path routing, and build-system import.
> Use [`docs/guides/AGENT-STEROID-GUIDE.md`](../../guides/AGENT-STEROID-GUIDE.md) and
> [`docs/devrig-remote-development-backend-e2e.md`](../../devrig-remote-development-backend-e2e.md) for the
> current behavior.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `steroid_open_project` start a not-yet-running devrig-managed IDE (blocking until reachable) then open the project, and simplify the backend model around `ideHome` identity — deleting `BackendRow`, `BackendInfo`, `ListedBackendInfo`, and the `backends[]` array on the list tools.

**Architecture:** A backend is identified by its **IDE install home folder** (`ideHome`), self-reported by the plugin into the `PidMarker`. devrig composes three *explicit* sources (running plugin IDEs = markers; running non-plugin IDEs = port scan, CLI-only; startable = installed-managed-not-running) instead of one sealed `BackendRow`. `open_project` resolves a candidate and hands it to `ensureBackendRunning(...)`, which starts it if needed and blocks until a marker with the matching `ideHome` appears.

**Tech Stack:** Kotlin 2.3.20 / Java 25 / Gradle / kotlinx.serialization / Ktor / JUnit5 (mcp-steroid-server, npx-kt) + JUnit3 `BasePlatformTestCase` (ij-plugin).

**Design doc:** `docs/startable-backends-design.md` (approved 2026-06-22).

## Global Constraints

- **Wire-contract additive-only rule is WAIVED for this change only** — devrig + plugin ship together; reshape/remove DTO and marker fields freely. (`docs/PHILOSOPHY.md` Tenet 5 + `ij-plugin/CLAUDE.md` wire-contract get updated to record the one-release waiver.)
- **Per-module test scoping** (root `./gradlew test` is banned): `:mcp-steroid-server:test`, `:npx-kt:test`, `:ij-plugin:test` (full ~13 min; scope with `--tests`). Never run `:ij-plugin:test` twice concurrently.
- **No `runCatching{}.onFailure{}`, no empty/`_`-swallowing catch, no `@Suppress("DEPRECATION")`, no `isUnitTestMode` branches.** Fix warnings; don't ignore them.
- **Commit messages:** what + why; **never** mention AI or add an AI co-author.
- `backend_name` is devrig-generated, an implementation detail — never a cross-process key.
- Only **managed** (devrig-installed under `~/.mcp-steroid/backends/`) backends are startable; other IDEs are detect-only.
- Marker fields stay **nullable** (a running IDE on an older plugin build may lack a new field) — pragmatic robustness, not a compat obligation.

---

## File Structure

**mcp-steroid-server (shared DTOs):**
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/PidMarker.kt` — add `ideHome`.
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsTool.kt` — drop `backends` from `ListProjectsResponse`; delete `ListedBackendInfo`.
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListWindowsTool.kt` — drop `backends` from `ListWindowsResponse`.

**ij-plugin (the IDE side):**
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt` — write `ideHome = PathManager.getHomePath()`.
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsToolHandler.kt` / `ListWindowsToolHandlerIJ` — stop emitting `backends[]`.
- `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/NpxBridgeRoutes.kt` — `/projects` payload already per-project; unaffected beyond compile.

**npx-kt (devrig):**
- `monitor/IdePidDiscovery.kt` — `DiscoveredIde.ideHome`; read from marker.
- `devrig/InstalledBackends.kt` *(new)* — enumerate installed managed IDEs (ide + ideHome + launcher).
- `devrig/server/DevrigBackendService.kt` *(new)* — `ensureBackendRunning(...)` + candidate listing.
- `devrig/server/DevrigProjectRoutingService.kt` — drop `listedBackends`/`newestOf`.
- `devrig/server/DevrigListProjectsToolHandler.kt` / `DevrigListWindowsToolHandler.kt` — drop `backends[]`.
- `devrig/server/DevrigOpenProjectToolHandler.kt` — candidate + `ensureBackendRunning` flow.
- `devrig/server/StubMcpSteroidTools.kt` — wiring.
- `devrig/BackendCommand.kt` / `ProjectCommand.kt` / `BackendInfoMapping.kt` — rewrite CLI to 3 explicit groups; **delete** `BackendRow`, `BackendInfo` mapping, `mergeRows`, `collectBackendInfos`.
- `devrig/BackendInventory.kt` — delete (merge/port-scan correlation no longer used).
- `devrig/ManagedBackend.kt` — `start` reused; expose a start-by-installed-entry path.

**Docs:** `prompts/src/main/prompts/open-project/managing-backends.md`, `docs/guides/AGENT-STEROID-GUIDE.md`, `docs/PHILOSOPHY.md`, `ij-plugin/CLAUDE.md`, `docs/devrig-naming.md`.

---

## Task 1: `PidMarker.ideHome` (shared DTO)

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/PidMarker.kt`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/PidMarkerTest.kt`

**Interfaces:**
- Produces: `PidMarker.ideHome: String?` (nullable, defaults `null`).

- [ ] **Step 1: Failing test** — add to `PidMarkerTest.kt`:

```kotlin
@Test
fun `ideHome is optional and decodes when present and absent`() {
    val withHome = PidMarkerJson.decode(
        """{"schema":1,"pid":7,"ide":{"name":"X","version":"1","build":"IU-1"},
           "plugin":{"id":"p","name":"P","version":"v"},"createdAt":"t",
           "ideHome":"/opt/idea"}""".trimIndent()
    )
    assertEquals("/opt/idea", withHome.ideHome)

    val withoutHome = PidMarkerJson.decode(
        """{"schema":1,"pid":7,"ide":{"name":"X","version":"1","build":"IU-1"},
           "plugin":{"id":"p","name":"P","version":"v"},"createdAt":"t"}""".trimIndent()
    )
    assertNull(withoutHome.ideHome)
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.PidMarkerTest'`
Expected: FAIL — `ideHome` unresolved.

- [ ] **Step 3: Add the field** — in `PidMarker.kt`, after `createdAt`:

```kotlin
    val createdAt: String,
    /** Absolute IDE install home (`PathManager.getHomePath()`); identifies the install across restarts. */
    val ideHome: String? = null,
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :mcp-steroid-server:test --tests 'com.jonnyzzz.mcpSteroid.PidMarkerTest'`
Expected: PASS. (The existing `roundtrip preserves all fields` test still passes — new field defaults `null`.)

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/PidMarker.kt \
        mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/PidMarkerTest.kt
git commit -m "PidMarker: add optional ideHome (IDE install home) field"
```

---

## Task 2: Plugin self-reports `ideHome`

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt` (add a marker-shape assertion) OR a focused `ServerUrlWriter` test if one exists.

**Interfaces:**
- Consumes: `PidMarker.ideHome` (Task 1).
- Produces: every marker the plugin writes carries `ideHome == PathManager.getHomePath()`.

- [ ] **Step 1: Failing test** — add to `McpServerIntegrationTest.kt` (it already drives the live server + reads markers). Add:

```kotlin
fun testMarkerCarriesIdeHome(): Unit = timeoutRunBlocking(30.seconds) {
    val server = SteroidsMcpServer.getInstance()
    server.startServerIfNeeded()
    val markerDir = com.jonnyzzz.mcpSteroid.PidMarker.markerDirectory(
        java.nio.file.Path.of(System.getProperty("user.home"))
    )
    val marker = com.jonnyzzz.mcpSteroid.PidMarker.markerFileNameFor(ProcessHandle.current().pid())
    val text = markerDir.resolve(marker).toFile().readText()
    val decoded = com.jonnyzzz.mcpSteroid.PidMarkerJson.decode(text)
    assertEquals(
        "marker must report the IDE install home",
        com.intellij.openapi.application.PathManager.getHomePath(),
        decoded.ideHome,
    )
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.McpServerIntegrationTest.testMarkerCarriesIdeHome'`
Expected: FAIL — `ideHome` is `null`.

- [ ] **Step 3: Set `ideHome` in `ServerUrlWriter.kt`** — in the `PidMarker(...)` construction, add:

```kotlin
            ideHome = com.intellij.openapi.application.PathManager.getHomePath(),
```

(Place it alongside the other required args; import `PathManager` at the top instead of FQN if the file's style prefers imports.)

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.McpServerIntegrationTest.testMarkerCarriesIdeHome'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt \
        ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt
git commit -m "ij-plugin: write ideHome (PathManager.getHomePath) into the marker"
```

---

## Task 3: devrig discovery reads `ideHome`

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/monitor/IdePidDiscovery.kt`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/monitor/IdePidDiscoveryServiceTest.kt`

**Interfaces:**
- Consumes: `PidMarker.ideHome`.
- Produces: `DiscoveredIde.ideHome: String?`.

- [ ] **Step 1: Failing test** — extend the discovery test's marker fixture with `"ideHome":"/opt/goland"` and assert `discovered.single().ideHome == "/opt/goland"`. (Follow the existing test's marker-writing helper; add `ideHome` to the JSON it writes and a new assertion.)

- [ ] **Step 2: Run, verify it fails** — `./gradlew :npx-kt:test --tests '*IdePidDiscoveryServiceTest'` → FAIL (`ideHome` unresolved).

- [ ] **Step 3: Implement** — in `IdePidDiscovery.kt`:
  - Add `val ideHome: String?` to `data class DiscoveredIde` (after `plugin` or alongside other fields).
  - In the `DiscoveredIde(...)` construction inside `stateSnapshot()`, add `ideHome = marker.ideHome,`.

- [ ] **Step 4: Run, verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/monitor/IdePidDiscovery.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/monitor/IdePidDiscoveryServiceTest.kt
git commit -m "devrig: carry ideHome from marker onto DiscoveredIde"
```

---

## Task 4: Installed-backend enumeration + startable filter

**Files:**
- Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/InstalledBackends.kt`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/InstalledBackendsTest.kt`

**Interfaces:**
- Consumes: `HomePaths`, `BackendManager`/descriptor read, `DiscoveredIde.ideHome` (Task 3).
- Produces:
  - `data class InstalledBackend(val id: String, val ide: IdeInfo, val ideHome: String, val launcher: java.nio.file.Path)`
  - `fun DevrigServices.installedBackends(): List<InstalledBackend>` — scans `~/.mcp-steroid/backends/` (reuse the descriptor read in `ManagedBackend`).
  - `fun startableBackends(installed: List<InstalledBackend>, running: List<DiscoveredIde>): List<InstalledBackend>` — `installed` minus any whose `ideHome` equals a running marker's `ideHome` (normalize paths with `Path.toString()` / `normalize()`).

- [ ] **Step 1: Failing test** — `InstalledBackendsTest.kt`:

```kotlin
@Test
fun `startable excludes installed backends already running by ideHome`() {
    val a = installed(id = "idea-community-2026.1", home = "/b/idea")
    val b = installed(id = "goland-2026.1", home = "/b/goland")
    val runningGoland = discoveredIde(ideHome = "/b/goland")
    val startable = startableBackends(listOf(a, b), listOf(runningGoland))
    assertEquals(listOf("idea-community-2026.1"), startable.map { it.id })
}
```
(Add small `installed(...)` / `discoveredIde(...)` builders in the test.)

- [ ] **Step 2: Run, verify it fails** — `./gradlew :npx-kt:test --tests '*InstalledBackendsTest'` → FAIL.

- [ ] **Step 3: Implement** `InstalledBackends.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import java.nio.file.Path

data class InstalledBackend(
    val id: String,
    val ide: IdeInfo,
    val ideHome: String,
    val launcher: Path,
)

private fun normalizeHome(p: String): String = Path.of(p).toAbsolutePath().normalize().toString()

fun startableBackends(
    installed: List<InstalledBackend>,
    running: List<DiscoveredIde>,
): List<InstalledBackend> {
    val runningHomes = running.mapNotNull { it.ideHome }.map(::normalizeHome).toSet()
    return installed.filter { normalizeHome(it.ideHome) !in runningHomes }
}
```

Then `DevrigServices.installedBackends()` — scan `homePaths.backendsDir()` (or the existing backends-dir accessor), and for each descriptor build `InstalledBackend(id, descriptor.ide, ideHome = bundleDir, launcher = bundleDir.resolve(descriptor.launcherPath))`. **Reuse** the descriptor read already in `ManagedBackend` (`loadDescriptor`/`readDescriptorOrNull`) — do not duplicate JSON parsing. The `ideHome` is the bundle dir (`homePaths.backendDir(id).resolve(descriptor.bundleDirName)`), the same path `PathManager.getHomePath()` reports for that managed IDE.

- [ ] **Step 4: Run, verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/InstalledBackends.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/InstalledBackendsTest.kt
git commit -m "devrig: enumerate installed backends + startable filter by ideHome"
```

---

## Task 5: `ensureBackendRunning` service

**Files:**
- Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBackendService.kt`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBackendServiceTest.kt`

**Interfaces:**
- Consumes: `DiscoveredIde` + `ideHome` (Task 3), `InstalledBackend`/`startableBackends` (Task 4), a starter (`ManagedBackendService.start`) and a discovery snapshot provider (`() -> List<DiscoveredIde>`).
- Produces:
  - `sealed interface OpenProjectCandidate { val backendName: String; val displayName: String }` with `Running(val ide: DiscoveredIde)` and `Startable(val installed: InstalledBackend)`.
  - `class DevrigBackendService(stateProvider, installedProvider, starter, clock-free poll)`:
    - `fun candidates(): List<OpenProjectCandidate>` — running (S1) then startable (S3), startable appended last.
    - `suspend fun ensureBackendRunning(candidate: OpenProjectCandidate, timeout: Duration = 120.seconds): DiscoveredIde` — running ⇒ its ide; startable ⇒ `starter.start(...)` then poll `stateProvider()` until a marker with matching `ideHome` (normalized), else throw `BackendStartTimeoutException`.

- [ ] **Step 1: Failing tests** — `DevrigBackendServiceTest.kt` (use a fake starter + a mutable state list; drive `withTimeout` via `kotlinx-coroutines-test` `runTest`):

```kotlin
@Test fun `running candidate is returned as-is without starting`() = runTest {
    val ide = discoveredIde(ideHome = "/b/idea")
    val svc = service(running = listOf(ide), installed = emptyList(), starter = failStarter())
    val out = svc.ensureBackendRunning(OpenProjectCandidate.Running(ide))
    assertSame(ide, out)
}

@Test fun `startable candidate is started then resolved by ideHome`() = runTest {
    val installed = installed(id = "goland", home = "/b/goland")
    val state = mutableListOf<DiscoveredIde>()
    val svc = service(stateProvider = { state.toList() }, installed = listOf(installed),
        starter = { state += discoveredIde(ideHome = "/b/goland") }) // simulate marker appearing
    val out = svc.ensureBackendRunning(OpenProjectCandidate.Startable(installed))
    assertEquals("/b/goland", out.ideHome)
}

@Test fun `startable times out with a clear error when no marker appears`() = runTest {
    val installed = installed(id = "goland", home = "/b/goland")
    val svc = service(stateProvider = { emptyList() }, installed = listOf(installed),
        starter = { /* never writes a marker */ })
    val e = assertFailsWith<BackendStartTimeoutException> {
        svc.ensureBackendRunning(OpenProjectCandidate.Startable(installed), timeout = 100.milliseconds)
    }
    assertTrue(e.message!!.contains("did not become reachable"))
}
```

- [ ] **Step 2: Run, verify they fail** — `./gradlew :npx-kt:test --tests '*DevrigBackendServiceTest'` → FAIL.

- [ ] **Step 3: Implement** `DevrigBackendService.kt`. Poll loop uses `withTimeoutOrNull(timeout) { while (true) { stateProvider().firstOrNull { sameHome(it.ideHome, target) }?.let { return@withTimeoutOrNull it }; delay(250.ms) } }`; on null → throw `BackendStartTimeoutException("started ${installed.id} but it did not become reachable within $timeout")`. `candidates()` = `running.map(::Running) + startable.map(::Startable)`. **No Java threading primitives** — use coroutines (`delay`, `withTimeoutOrNull`).

- [ ] **Step 4: Run, verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBackendService.kt \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigBackendServiceTest.kt
git commit -m "devrig: DevrigBackendService.candidates + ensureBackendRunning (start+wait by ideHome)"
```

---

## Task 6: devrig `open_project` rewrite

**Files:**
- Modify: `npx-kt/.../server/DevrigOpenProjectToolHandler.kt`, `StubMcpSteroidTools.kt`
- Modify: `npx-kt/.../server/DevrigProjectRoutingService.kt` (delete `newestOf`, `listedBackends`)
- Test: `npx-kt/.../server/DevrigToolBridgeClientTest.kt` (open-project cases)

**Interfaces:**
- Consumes: `DevrigBackendService.candidates()` + `ensureBackendRunning(...)`.
- Produces: `open_project(backendName?)` — no-arg: 1 candidate ⇒ use, else error listing candidates; with-arg: resolve by `backendName` ⇒ `ensureBackendRunning` ⇒ `bridge.callTool(ide, "steroid_open_project") { … }`. Unknown id ⇒ self-correcting error listing candidate `backend_name`s.

- [ ] **Step 1: Update the failing tests first.** In `DevrigToolBridgeClientTest.kt`, the open-project cases must reflect: no-arg with 2 running ⇒ error listing both (no `newestOf` auto-pick); no-arg with exactly 1 ⇒ routes to it; unknown backend ⇒ error listing candidates. Rewrite the `forwards to the newest ide when several are discovered` test into `lists candidates when several and no backend_name given` (assert `result.isError` and the message lists both `backend_name`s; assert NO bridge call — `receivedBody == null`).

- [ ] **Step 2: Run, verify they fail** — `./gradlew :npx-kt:test --tests '*DevrigToolBridgeClientTest'` → FAIL.

- [ ] **Step 3: Implement** the handler against `DevrigBackendService`:

```kotlin
class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val backends: DevrigBackendService,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(params: OpenProjectParams): ToolCallResult {
        val requested = params.backendName?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = backends.candidates()
        val chosen = when {
            requested != null -> candidates.firstOrNull { it.backendName == requested }
                ?: return errorResult(unknownBackendMessage(requested, candidates))
            candidates.size == 1 -> candidates.single()
            else -> return errorResult(chooseBackendMessage(candidates))
        }
        val ide = try { backends.ensureBackendRunning(chosen) }
                  catch (e: BackendStartTimeoutException) { return ToolCallResult.errorResult(e.message!!) }
        return bridge.callTool(ide, "steroid_open_project") {
            put("project_path", params.projectPath); put("trust_project", params.trustProject)
            put("task_id", "open-project"); put("reason", "Open project through devrig")
        }
    }
}
```
Write `unknownBackendMessage` / `chooseBackendMessage` to list each candidate's `backend_name` + `displayName` + `(startable)` marker. Delete `DevrigProjectRoutingService.newestOf` and `listedBackends` (and their imports/tests).

- [ ] **Step 4: Run, verify pass** — `./gradlew :npx-kt:test --tests '*DevrigToolBridgeClientTest' --tests '*DevrigProjectRoutingServiceTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/ \
        npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigToolBridgeClientTest.kt
git commit -m "devrig open_project: candidate list + ensureBackendRunning; drop newestOf auto-pick"
```

---

## Task 7: Drop `backends[]` from list tools (both surfaces)

**Files:**
- Modify: `mcp-steroid-server/.../server/ListProjectsTool.kt` (remove `ListProjectsResponse.backends` + delete `ListedBackendInfo`), `ListWindowsTool.kt` (remove `ListWindowsResponse.backends`).
- Modify: devrig `DevrigListProjectsToolHandler.kt`, `DevrigListWindowsToolHandler.kt` (stop building backends).
- Modify: in-IDE `ListProjectsToolHandler.kt` (`ListProjectsToolHandlerIJ`), `ListWindowsToolHandlerIJ`, `OpenProjectToolSpec` (already `includeBackendName = false`; confirm direct open_project ignores `backend_name`).
- Test: `McpServerIntegrationTest.kt`, `DevrigListToolHandlersTest.kt`, `WirePristinenessTest`.

**Interfaces:**
- Produces: `ListProjectsResponse(projects)`, `ListWindowsResponse(windows, backgroundTasks)` — no `backends`. Each item keeps `backend_name`.

- [ ] **Step 1: Update tests first** — in `McpServerIntegrationTest.kt` remove every `response.backends`/`selfBackend` assertion (keep per-project/window `backend_name` assertions). In `DevrigListToolHandlersTest.kt` drop the `backends[]` assertions; keep `projects[].backendName` / window `backendName`. In `WirePristinenessTest` drop `ListedBackendInfo` from the asserted key list (keep `ListedProject` / `backend_name` / `project_name`).

- [ ] **Step 2: Run, verify they fail to compile** — `./gradlew :mcp-steroid-server:test :npx-kt:compileTestKotlin` → FAIL.

- [ ] **Step 3: Implement** — delete `val backends` from both response data classes; delete `ListedBackendInfo` and `mcpSteroidPlugins`-only helpers it needed if now unused; update both devrig handlers to `ListProjectsResponse(projects = …)` / `ListWindowsResponse(windows = …, backgroundTasks = …)`; update the in-IDE handlers likewise. Remove `DevrigProjectRoutingService.listedBackends` usages.

- [ ] **Step 4: Run, verify pass** — `./gradlew :mcp-steroid-server:test :npx-kt:test --tests '*DevrigListToolHandlersTest' --tests '*DevrigToolBridgeClientTest'` → PASS. Then `:ij-plugin:test --tests '*McpServerIntegrationTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server npx-kt ij-plugin
git commit -m "Drop backends[] from list_projects/list_windows; delete ListedBackendInfo"
```

---

## Task 8: CLI `devrig backend` — 3 explicit groups; delete `BackendRow`/`BackendInfo`/`BackendInventory`

**Files:**
- Rewrite: `npx-kt/.../devrig/BackendCommand.kt`, `ProjectCommand.kt`, `BackendInfoMapping.kt`.
- Delete: `npx-kt/.../devrig/BackendInventory.kt`; the `BackendRow` sealed type; `BackendInfo`/`ListedBackendInfo` mapping; `mergeRows`, `collectBackendInfos`, `normaliseBuildForDedup`, `matchingManagedIds`, inventory process-scan.
- Test: `BackendCommandRenderTest.kt`, `BackendCommandJsonRenderTest.kt`, `ProjectCommandRenderTest.kt`, `BackendAndProjectJsonAreIdenticalTest.kt`, `BackendCommandPortDiscoveryTest.kt`, `BackendIdentityTest.kt`.

**Interfaces:**
- Consumes: S1 (`routing.discoveredBackends()`), S2 (`collectPortDiscoveredIdes(portDiscovery)`), S3 (`installedBackends()` + `startableBackends`).
- Produces: a 3-group render + `--json` with `{ tool, mcpSteroidBackends[], otherIdes[], startableBackends[] }` (exact field set finalized in this task; each entry carries `backend_name`, `displayName`, `build`, and source-specific extras).

- [ ] **Step 1: Rewrite the render tests** to expect the 3 labeled groups + install footer, and the new `--json` object (3 arrays). (Replace `BackendRow`/`BackendInfo` fixtures with builders over the explicit sources.) Tests for removed symbols (`BackendIdentityTest` against `backendNameForRow(BackendRow…)`) get repointed to the surviving `backendNameFor*` helpers or deleted if the behavior is gone.

- [ ] **Step 2: Run, verify they fail** — `./gradlew :npx-kt:compileTestKotlin` → FAIL (BackendRow gone).

- [ ] **Step 3: Implement** — `runBackendCommand` composes the three sources directly and renders three sections (`renderMcpSteroidBackends`, `renderOtherIdes`, `renderStartable`) + an install-hint footer; `--json` emits the three arrays. Delete `BackendInventory.kt`, `BackendRow`, the `BackendInfo` mapping path, `mergeRows`, `collectBackendInfos`, `newestOf` remnants, process-scan correlation. `ProjectCommand` renders projects from `routing.routes()` directly (it already does) and its skipped/unreachable footer reads S2/S3 explicitly. Keep `BackendManager.start/download/stop` for `devrig backend download/start/stop`.

- [ ] **Step 4: Run, verify pass** — `./gradlew :npx-kt:test` → PASS (full module).

- [ ] **Step 5: Commit**

```bash
git add npx-kt
git commit -m "devrig backend: 3 explicit source groups; delete BackendRow/BackendInfo/BackendInventory"
```

---

## Task 9: Documentation

**Files:**
- Rewrite: `prompts/src/main/prompts/open-project/managing-backends.md` (+ sweep sibling `open-project/*.md`).
- Modify: `docs/guides/AGENT-STEROID-GUIDE.md`, `docs/PHILOSOPHY.md` (Tenet 5), `ij-plugin/CLAUDE.md` (wire-contract), `docs/devrig-naming.md`, `README.md`, `website/content/docs/*`.

- [ ] **Step 1:** Rewrite `managing-backends.md` to the simplified view: startable backends appear as `open_project` candidates; `open_project` starts one and blocks until ready; no `backends[]`/`routable` polling; `devrig backend …` is for installing/managing IDEs (not a prerequisite to open); drop managed-preference/auto-pick guidance.
- [ ] **Step 2:** `AGENT-STEROID-GUIDE.md` — drop the `backends[]` description; each list item carries `backend_name`; discovery lives in `open_project`.
- [ ] **Step 3:** `PHILOSOPHY.md` Tenet 5 + `ij-plugin/CLAUDE.md` wire-contract — refresh stale type examples (`BackendInfo`/`ListedBackendInfo` deleted; `backends[]` removed); record the one-release additive-only waiver; keep the Tenet-5 principle.
- [ ] **Step 4:** Sweep `docs/devrig-naming.md`, `README.md`, `website/content/docs/*` for stray `backends[]`/`BackendInfo` references; fix.
- [ ] **Step 5: Run the prompt contract test** — `./gradlew :prompts:test --tests '*MarkdownArticleContract*'` → PASS (prose-only edits don't need the KtBlocks matrix).
- [ ] **Step 6: Commit**

```bash
git add prompts docs ij-plugin/CLAUDE.md README.md website
git commit -m "docs: reflect startable backends + dropped backends[]; record wire-contract waiver"
```

---

## Task 10: Full verification + deploy + live check

- [ ] **Step 1:** `./gradlew :mcp-steroid-server:test :npx-kt:test` → all green.
- [ ] **Step 2:** `./gradlew :ij-plugin:test` (full) → green except pre-existing unrelated `testInspectAndFixExampleExecutes` (SpellChecking sandbox) — confirm no NEW failures.
- [ ] **Step 3:** `./gradlew :npx-kt:integrationTest` → green.
- [ ] **Step 4:** Deploy: `:ij-plugin:deployPlugin` (hot-reload running IDE) + rebuild/install devrig (`installDist` → copy to `~/.mcp-steroid/devrig-<ver>/` → `devrig install devrig --install-script=…`); restart the `mcp-steroid` MCP server (it now points at the wrapper).
- [ ] **Step 5: Live check** via the MCP: `steroid_list_projects` (no `backends[]`; items carry `backend_name`); `open_project` with no arg lists candidates incl. a startable managed IDE; pick a startable one → it starts and the project opens. `devrig backend` shows the 3 groups.
- [ ] **Step 6: Commit** any fixups; report results.

---

## Self-Review

- **Spec coverage:** ideHome marker (T1–3) ✓; startable enumeration (T4) ✓; ensureBackendRunning/start-wait/timeout (T5) ✓; open_project no-arg list + with-arg start (T6) ✓; drop backends[] both surfaces + in-IDE ignores backend_name (T7) ✓; CLI 3 groups + --json + deletions (T8) ✓; docs incl. waiver (T9) ✓; verify/deploy (T10) ✓.
- **Placeholder scan:** none — exact files, real code/signatures per task. The CLI `--json` field set is finalized in T8 (flagged as the one shape decided in-task).
- **Type consistency:** `DiscoveredIde.ideHome: String?`, `PidMarker.ideHome: String?`, `InstalledBackend.ideHome: String`, `OpenProjectCandidate.{Running,Startable}`, `DevrigBackendService.{candidates,ensureBackendRunning}`, `BackendStartTimeoutException` — names used consistently across T3–T8.
- **Ordering safety:** DTO field add (T1) → producers (T2,T3) → enumeration (T4) → service (T5) → open_project (T6) → drop backends[] (T7) → CLI + deletions (T8). Build stays green between tasks (T8's deletions happen only after T6/T7 remove the MCP consumers).
