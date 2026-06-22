# ij-plugin — IntelliJ Plugin Code

Guidance for working on the IntelliJ Platform plugin and standalone MCP server. Read this **in addition
to** the root `CLAUDE.md` whenever you change files under `ij-plugin/`.

## Architecture

IntelliJ Platform plugin with standalone MCP server (Kotlin MCP SDK + Ktor):

**Core flow**: `SteroidsMcpServer.kt` → `ExecuteCodeToolHandler.kt` → `ExecutionManager.kt` →
`CodeEvalManager.kt` (compile) → `ScriptExecutor.kt` (run with timeout)

**Key decisions**: Standalone MCP server (no built-in MCP dependency). Synchronous request-response.
Coroutines over blocking. Two-phase execution (compile then run). Fast failure on errors. Disposable
lifecycle for cleanup.

### Source structure

```
src/main/kotlin/com/jonnyzzz/mcpSteroid/
├── server/      # MCP server, tool handlers, skills
├── mcp/         # Core MCP protocol, tool registry
├── execution/   # ExecutionManager, CodeEvalManager, ScriptExecutor, McpScriptContext
├── storage/     # Append-only file storage
├── vision/      # Screenshot, input dispatch
├── demo/        # Demo mode overlay
├── ocr/         # External OCR process
├── koltinc/     # External kotlinc process
└── updates/     # Update checker
```

## IntelliJ Platform coding principles

### ⚠️ Public, stable API only — NEVER `@ApiStatus.Internal` (or `@ApiStatus.Experimental`)

**Every IntelliJ API this plugin calls — in production code AND in `mcp-steroid://` prompt recipes —
must be public and stable.** Before using a class/method/field/topic/extension point, confirm it is **not**
annotated `@ApiStatus.Internal` (and prefer not `@ApiStatus.Experimental`). Internal APIs break without
notice across IDE releases and are off-limits even when they look convenient.

- **Verify it.** Check the declaration in `~/Work/intellij` (`grep -n "@ApiStatus" <File>`), or via
  `steroid_execute_code` + PSI on the open `intellij` project. If it's `@ApiStatus.Internal`, find the
  public replacement; if none exists, that's a design constraint to surface, not to bypass.
- **Known traps:** `LaterInvocator.isInModalContext()` is `@ApiStatus.Internal` — use the public
  `ModalityState.current() != ModalityState.nonModal()` (Yuriy's `isModalEdt()`) instead. Prefer public
  message-bus topics like `ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED` over internal hooks.
- Reflection into private fields / `setAccessible(true)` to reach internals is **not** an escape hatch —
  it silently breaks on the next IDE release (see the reflection rule in `prompts/CLAUDE.md`).
- **Moving target — plugin enumeration / descriptor APIs.** Across `261 → 262`, IntelliJ is
  internalizing `PluginManagerCore.getPlugin` / `getLoadedPlugins` / `getPlugins` **and**
  `PluginManager.findEnabledPlugin` / `getPlugins`; the public successor `PluginDetailsService`
  exists only in `262+`, not `261`. So there is currently **no** typed public API that is clean on
  both targets — `PluginDescriptorProvider` and `ScriptClassLoader.orderedPluginDescriptors()`
  therefore keep using the `261`-public `PluginManagerCore` calls and accept the `262`-EAP
  internal-API verifier hits (261 — the shipping target — stays at 0). Full analysis, the per-build
  `@Internal` table, and the migration path: [`docs/262-plugin-manager-api-internalization.md`](../docs/262-plugin-manager-api-internalization.md) (reported upstream as IJPL-246183).

### Services

Use IntelliJ services instead of `object`/singletons:

```kotlin
inline val serviceX: ServiceX get() = service()
inline val Project.serviceY: ServiceY get() = service()

@Service(Service.Level.PROJECT)
class MyService(private val project: Project, coroutineScope: CoroutineScope)
```

Get services: `project.service<MyService>()` or `service<AppService>()`. Use `childScope()` for cleanup,
`job.cancelOnDispose(disposable)` for cancellation, `Disposer.register(parent, child)` for lifecycle.

### Threading

- Never block EDT. Use `Dispatchers.EDT` for UI, `Dispatchers.IO`/`Default` for background.
- PSI/VFS access needs read actions; modifications need write actions. Use `readAction { }` /
  `writeAction { }`.
- Dumb mode: `DumbService.isDumb(project)`; `waitForSmartMode()` handles this for scripts.

### Coroutines

- `runBlockingCancellable` — BGT, blocks thread, cancellation-aware.
- `runWithModalProgressBlocking` — EDT, shows modal progress.
- `RunSuspend` — low-level `Object.wait()` / `notifyAll()`.

### Error handling

- Never catch `ProcessCanceledException` — rethrow it.
- **Never log `CancellationException` (or any subclass) as an error.** On the
  hot script-execution path (`mcp-http/`, `ij-plugin/.../execution/`,
  `ij-plugin/.../server/`), every `catch (Throwable)` / `catch (Exception)`
  must match `CancellationException` first and rethrow without logging —
  this is the `c.i.openapi.diagnostic.Logger` Javadoc contract for
  control-flow exceptions. Audit completed in commit `efcd3400` (2026-05-19);
  see TASKS.md → "A2b" for the site-by-site list of fixed catches, sites
  already correct (do not retouch), and intentionally-deferred sites
  off the hot path. The user-visible failure from issue #46
  (`SEVERE: StandaloneCoroutine was cancelled` + dual 200/500 log lines)
  was driven by this rule being violated; A0's boundary catch-all
  (`McpHttpTransport.handlePost`, commit `3a4e7c13`) plus the A2b
  rethrows form the complete fix.
- One exception: `ScriptExecutor.kt:150` deliberately catches
  `TimeoutCancellationException` BEFORE the generic `CancellationException`
  catch and calls `reportFailed("Execution timed out after $timeout seconds")`.
  TCE is a CE subclass; the script-timeout case is a domain error that
  needs to surface to the agent, not a control-flow signal to propagate.
- Use `Logger.getInstance(MyClass::class.java)` for logging.

### Cancellation and the `kotlinc` subprocess

`KotlincProcessClient.kotlinc(args, workingDir)` is a regular (non-`suspend`)
`fun` that calls `ExecUtil.execAndGetOutput(commandLine, 120_000)`. The
blocking JVM call does NOT check `kotlinx.coroutines` cancellation, so a
cancelled caller coroutine will NOT terminate the in-flight kotlinc
subprocess — kotlinc runs to completion (or its 120 s upper bound). This
is **intentional**: killing kotlinc mid-compile is flaky on macOS+JDK21
and the saved cycles are small. Nothing in `ij-plugin/src/main` calls
`process.destroyForcibly()` on the kotlinc process, and adding such a
call would defeat the contract. After `kotlinc(...)` returns, the
CE-rethrow wrappers above ensure the cancellation propagates upstream
cleanly. (Cluster A's A3, verified-by-inspection 2026-05-19.)

## Build

Run Gradle tasks from the IDE via MCP, not shell. Key tasks: `build`, `test`, `buildPlugin`, `runIde`,
`verifyPlugin`, `deployPlugin`.

Gradle run-config recipe (paste into `steroid_execute_code`):

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType

val runManager = RunManager.getInstance(project)
val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration
runConfig.name = "Gradle test (MCP)"
runConfig.settings.externalProjectPath = project.basePath
runConfig.settings.taskNames = listOf("test")
// runConfig.settings.scriptParameters = "--tests \"*ExecutionManagerTest*\""
val settings = runManager.createConfiguration(runConfig, factory)
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

### Build notes

- IDE cached under `.intellijPlatform/ides/IU-2025.3`.
- Tests use `localPlugin(...)` for Kotlin/Java plugins to align with IDE classpath.
- **`com.intellij.java` + `org.jetbrains.kotlin` are declared via `testBundledPlugin(...)`** in the main `dependencies.intellijPlatform { }` block — they live on the project-level test classpath
  (`intellijPlatformTestBundledPlugins`), NOT on the main IDE classpath. Two safety nets
  enforce this: `NoForbiddenPluginImportsTest` (compile-time, scans every production Kotlin
  source root) and `PluginRuntimeCompatibilityTest.runtime compat pycharm*` (PyCharm
  doesn't bundle either plugin and the .zip still loads).
  - **Latent footgun**: the custom integrationTest source set wired via
    `intellijPlatformTesting.testIde.register("integrationTest")` does NOT inherit project-level
    `testBundledPlugin` declarations — IPGP creates a suffixed
    `intellijPlatformTestBundledPlugins_integrationTest` config that doesn't `extendsFrom` the
    project-level one. The Cli*IntegrationTest classes today don't import any Java/Kotlin plugin
    types so this is fine, but if a future integration test needs them, add them explicitly inside
    the `register("integrationTest") { ... }` block.

## Plugin deployment

```bash
./gradlew deployPlugin              # Hot-reload (requires Plugin Hot Reload plugin)
./gradlew deployPluginLocallyTo253  # Cold deploy (requires restart)
```

## Adding new MCP tools

**Don't, in almost every case.** Read [`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md)
(canonical) or `mcp-steroid://skill/design-philosophy` (runtime mirror)
first. The MCP tool surface is intentionally narrow (10 today); a new tool
ships only when **all three** of:

1. The need cannot be met by `steroid_execute_code` + a direct IntelliJ API
   call. **Document the specific IntelliJ API path you ruled out.**
2. It cannot be met by a richer `mcp-steroid://` recipe.
3. Three independent reviewers (`run-agent.sh codex` / `claude` / `gemini`)
   agree, after reading PHILOSOPHY.md. **One reviewer disagreeing kills
   the proposal** — propose a recipe instead.

If the gates pass, the wiring itself is two lines:

1. Create `@Service(Service.Level.APP)` handler with a `register(server: McpServerCore)` method.
2. Register in `SteroidsMcpServer.kt`: `service<MyToolHandler>().register(server)`.

Same gate applies to **adding methods on `McpScriptContext`** (Tenet 3 in
PHILOSOPHY.md): the IntelliJ API is the extension point; context methods
are last-resort. The `applyPatch { }` DSL on the context class is the
canonical example — it earned its place because composing multi-site
literal edits with surrounding PSI / inspections work in one read/write
cycle is genuinely worth the surface. New context methods must clear
the same bar.

## IDE control via execute_code

Invoke IDE actions programmatically:

```kotlin
val action = ActionManager.getInstance().getAction("RestartIde")
val dataContext = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
```

List actions: `ActionManager.getInstance().getActionIds("").filter { it.contains("restart", ignoreCase = true) }`.

## Testing

```kotlin
fun testExample(): Unit = timeoutRunBlocking(30.seconds) { /* coroutine test code */ }

private fun testExecParams(code: String, timeout: Int = 30) = ExecCodeParams(
    taskId = "test-task", code = code, reason = "test", timeout = timeout,
    rawParams = buildJsonObject { }
)
```

### Per-module test scoping

This repo's per-module split (`:ij-plugin`, `:prompts`, `:kotlin-cli`, `:prompts-api`, `:test-helper`,
`:test-integration`, `:test-experiments`) is intentional. Prefer per-project `:module:test` over root
`./gradlew test` (banned at root — see root CLAUDE.md):

- After touching `ij-plugin` production code: `./gradlew :ij-plugin:test`.
- After touching `prompts/src/main/prompts/**`: `./gradlew :prompts:test --tests '<KtBlocksCompilationTest>'`.
- After touching `kotlin-cli`: `./gradlew :kotlin-cli:test`.
- After touching `buildSrc` codegen: `./gradlew :prompt-generator:test :prompts:generatePrompts`.
- When a test fails under root `test`, re-run just that module first.

`./gradlew :ij-plugin:test` runs only unit/in-process tests; Docker CLI tests are excluded by default.
Run them explicitly: `--tests '*CliClaudeIntegrationTest*'` etc. (need Docker + API keys).

### Test naming

- **NO `@ParameterizedTest`** — create explicit `@Test` methods with descriptive names
  (e.g., `` `describeMcp claude`() ``, `` `describeMcp codex`() ``).
- Truly dynamic cases: `@TestFactory` with `DynamicTest.dynamicTest("descriptive-name") { ... }`.
- This ensures IDE test runner, `./gradlew --tests`, and CI reports work optimally.

### Key test files

- **McpServerIntegrationTest** — MCP protocol handshake, tool flows, session management (in-process).
- **CliClaudeIntegrationTest** / **CliCodexIntegrationTest** / **CliGeminiIntegrationTest** —
  Docker-isolated CLI tests; excluded from default `:ij-plugin:test`.
- **ScriptExecutorTest** — Fast failure semantics, compilation/runtime errors.
- **ExecutionManagerTest** — Execution with progress reporting.

## Build troubleshooting

### Test suite runtimes

- `./gradlew :ij-plugin:test` (full clean): ~13–14 min.
- `./gradlew :prompts:test --tests '*KtBlock*'` (KtBlocks only): ~7 min.
- **Suspiciously fast results** (e.g., 500 tests in 14 seconds): stale Gradle test cache. Use
  `--rerun-tasks` or delete `ij-plugin/build/test-results/`.

### IntelliJ index corruption

**Symptom**: many tests fail with `PersistentEnumerator storage corrupted` at
`.../system-test/index/stubs/Stubs.storage`. Failure is inside
`IndexDataInitializer$Companion$submitGenesisTask$2.invokeSuspend` on a `CoroutineScheduler$Worker` —
truncated write from a previous abrupt JVM kill.

**Fix:**
```bash
rm -rf ij-plugin/build/idea-sandbox/IU-2025.3/system-test/
# Or more broadly: rm -rf ij-plugin/build/idea-sandbox/
```

Next test run will rebuild indexes (~30–60s extra startup).

**Prevention**: avoid SIGKILL (`kill -9`) on the Gradle test JVM mid-run; SIGTERM lets IntelliJ flush
index files cleanly.

**Critical**: NEVER run two `:ij-plugin:test` tasks concurrently — both write to the same
`idea-sandbox/` and corrupt each other's `Stubs.storage`. Never delete `idea-sandbox/` while a test JVM
is running — file-handle corruption causes the same result.

### Live JVM thread/coroutine dumps

When a test run hangs, **do NOT stop it first** — collect a thread dump:

```bash
jps -l | grep GradleWorkerMain                  # find test JVM PID
jcmd <PID> Thread.print > /tmp/thread-dump.txt  # full thread dump (incl. coroutines via DebugProbes)
jcmd <PID> Thread.print -l > /tmp/dump-with-locks.txt
```

Look for: `BLOCKED` threads (deadlock), `DefaultDispatcher-worker-*` waiting (stuck coroutines),
`AWT-EventQueue-0` deep-blocked (EDT violation), the currently executing test method.

**When a dump isn't enough, attach a live debugger.** Docker integration tests always start the
IDE JVM with a JDWP agent open (`server=y,suspend=n`, `IDE_DEBUG_PORT`=5005), and the in-container
devrig JVM too (`DEVRIG_DEBUG_PORT`=5006) — both Docker-mapped to host ports printed on the host as
`Listening for transport dt_socket at address: <host-port>` (+ `session-info.txt`). Attach
IntelliJ's "Remote JVM Debug" (module `ij-plugin`) to step through plugin code (`DialogKiller`,
`DialogWindowsLookup`, `ScriptExecutor`) live. Recipe to attach programmatically:
`mcp-steroid://debugger/debug-attach-remote-jvm`; full workflow in `test-integration/AGENTS.md`
→ "Remote-debugging the Dockerized IDE". The modal-dialog/EDT hang (resolved 2026-05-31) is written
up in `docs/dialog-killer-modality-hang.md`.

### exec_code modality model — the `modal` enum (ScriptExecutor.executeWithProgress)

**The MCP surface is one `ModalMode` enum** (`ExecuteCodeTool.kt`), not booleans. The old
`dialog_killer` / `cancel_on_modal` / `allow_modal` params and the runtime
`doNotCancelOnModalityStateChange()` are GONE. Full design + behavior table + the 10-iteration review:
`docs/exec-code-options-redesign.md` (LOCKED). Modality-hang investigation that led here:
`docs/dialog-killer-modality-hang.md`.

**Why a stance is needed:** `commitAllDocuments` / `saveAllDocuments` / `VfsRefreshService.awaitRefresh`
run on the **write-intent** `Dispatchers.EDT` and **hang under a modal** (the dispatch is withheld), and
`waitForSmartMode()` / indexing **cannot complete while a modal is up**. So a script that touches PSI must
first guarantee a non-modal IDE. The single reliable modal check is **Yuriy's** `isModalEdt()`
(`DialogWindowsLookup`: `Dispatchers.EDT + ModalityState.any()`, `current() != nonModal()`; stable —
`LaterInvocator` is `@Internal`), shared by the gate and the dialog killer.

**`modal` values** (default `smart_non_modal`), each a `when` branch in `executeWithProgressImpl` that is
sugar over the `McpScriptContext` methods:

| `modal` | Pre-flight | During body | Post-flight |
|---|---|---|---|
| `smart_non_modal` *(default)* | `closeModalDialogs()` (sweep, deepest-first) → require non-modal (`requireNonModalOrFail` → fail + screenshot + thread dump) → `syncDocuments()` (commit+save+VFS, bounded 60s) → `waitForSmartMode()` (bounded 60s) → `monitorAndCloseModalDialogs()` | monitor polls ~1s; a modal dialog gets closed + the run FAILS (screenshot + thread dump) | re-`syncDocuments()` iff `!isModalEdt()` |
| `non_modal` | `requireNonModalOrFail` only (assert-only) | nothing | nothing |
| `unleashed` | nothing | nothing | nothing |

**Context methods** (`McpScriptContextImpl`, callable from any mode): `closeModalDialogs(): Int` (sweep;
thread-dump+screenshot side effects, **skipped on an empty sweep**; does not fail), `monitorAndCloseModalDialogs()`
(1s-poll watcher; close + FAIL; launched in the execution scope so throwing fails the run; `allowModalDialog()`
cancels it for the rest of the run), `syncDocuments()` (commit+save+VFS; asserts non-modal), `waitForSmartMode()`
(asserts non-modal; **bounded** by `WAIT_FOR_SMART_MODE_TIMEOUT`). All bounded EDT ops use
`withTimeout → ToolCallErrorException` so a stuck modal fails fast with a thread dump instead of hanging.
Stage markers (`[PRE]`/`[RUN]`/`[POST]`) localize a stall.

**Tests:** unit `ModalModeTest` (wire/default/parse) + `ExecutionManagerTest` profile-pipeline cases;
integration `DialogKillerIntegrationTest` (Docker) — Step-1 opens a modal with `modal=unleashed` (no
monitor, so it survives), explicit/screenshot/nested close it with `unleashed` + `closeModalDialogs()`,
the automatic test uses `smart_non_modal` (pre-flight sweep). The test helper's `mcpExecuteCode(modal=…)`
and `testExecParams(modal=…)` carry the wire string.

### Cleaning build artifacts

```bash
rm -rf ij-plugin/build/test-results/ ij-plugin/build/reports/  # force test re-run
rm -rf ij-plugin/build/idea-sandbox/                            # corrupted indexes
./gradlew :ij-plugin:clean                                      # full clean
```

### Common test failure patterns

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `PersistentEnumerator storage corrupted` | Corrupted index files | `rm -rf ij-plugin/build/idea-sandbox/` |
| 500+ failures in <30s | Stale Gradle test cache | `./gradlew :ij-plugin:test --rerun-tasks` |
| `KtCompilationTest` fails with `-Werror` | Deprecated API used in `.kt` section | Replace the deprecated call with the non-deprecated API (no `@Suppress("DEPRECATION")` — see root `CLAUDE.md`) |
| `KtBlocksCompilationTest` fails | Non-compilable code in ` ```kotlin ``` ` fence | Change fence to ` ```text ``` ` in `.md` |
| `MarkdownArticleContractTest` fails | Title >80 chars, desc >200 chars, or bare code outside fences | Fix the article header/body |
| `NoHardcodedMcpSteroidUriUsageTest` fails | Hardcoded `mcp-steroid://...` URI in production Kotlin | Replace with `XxxPromptArticle().uri` |
| `:test-integration` hangs with `Blocking modal dialog detected` | Stale `test-integration/src/test/docker/test-project/.idea/` pins `project-jdk-name`/`gradleJvm` to a name not in `ProjectJdkTable` | Sanitize `.idea/` (gitignored) or add the name to `mcpRegisterJdks` aliases — see `test-integration/AGENTS.md` ("Configuring the IDE") |
| `:test-integration` hangs with `MODAL DIALOG DETECTED — Resolving SDKs…` during `ProjectTaskManager.build()` | Missing `-Dunknown.sdk.modal.jps=false` (gates `CompilerDriverUnknownSdkTracker.fixSdkSettings`) | Add to `test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/intelliJ.kt` `generateVmOptions()` — see `test-integration/AGENTS.md` |
| `unresolved reference 'JavaSdk'` in PyCharm/GoLand/WebStorm/Rider tests | Factory's early-JDK hook fires for IDEs without `com.intellij.java` on script classpath | Verify `IdeProduct.hasJavaSdk` is true only for `IntelliJIdea` (see `test-integration/AGENTS.md` → "Non-Java IDEs skip JDK setup") |
| `ContentModuleClasspathTest` fails with "JAR(s) on filesystem but not in classpath" after IDE upgrade | New unloaded content module bundled (e.g. `tailwindcss.ruby.jar` in 2026.1.1) | Add JAR path to `UNLOADED_CONTENT_MODULES_IU_261` |

## Key types

- **ExecCodeParams**: `taskId`, `code`, `reason`, `timeout`, `rawParams`.
- **ToolCallResult**: `content` (`List<ContentItem>`), `isError`. Use `ToolCallResult.builder()`.
- **McpScriptContext**: in-script Kotlin runtime context (`project`, `disposable`, `printJson(...)`,
  `progress(...)`, `applyPatch { }`, `findProjectFile(...)`, `projectScope()`, the inspection /
  highlighting helpers, etc.). See
  `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContext.kt` for the
  current surface — not enumerated here on purpose, since growing the surface is gated by Tenet 3
  in `docs/PHILOSOPHY.md`.

### Inspections from `steroid_execute_code`: only the per-file driver works

`runInspectionsDirectly` (per-file `InspectionEngine.inspectEx`, the `mcp-steroid://ide/inspect-and-fix`
recipe) is the ONLY inspection driver that runs from `steroid_execute_code`. The whole-project drivers do
NOT: `InspectionEngine.runInspectionOnFile`, `DaemonCodeAnalyzerImpl.runMainPasses` (fails
`assertUnderDaemonProgress()` — no `DaemonProgressIndicator`), and `MainPassesRunner.runMainPasses` (pumps
the EDT internally while the suspend script holds resources that block forward progress — timed out at 240s
on 3 small files). For full-project inspection coverage, use the IDE's **Code → Inspect Code…** UI, not a
script.

## Configuration

Registry keys: `mcp.steroid.server.port`, `.host`, `.execution.timeout`, `.dialog.killer.enabled`,
`.demo.enabled`, `.storage.path`, `.kotlinc.parameters`, `.kotlinc.home`.

### WSL-hosted project on a Windows IDE — `mcp.steroid.storage.path` workaround ([#78](https://github.com/jonnyzzz/mcp-steroid/issues/78))

When a Windows-side IDE opens a project under `\\wsl$\…` / `\\wsl.localhost\…`, every
`steroid_execute_code` fails: the kotlinc working dir is the per-execution `compiled/` folder under
`{project}/.idea/mcp-steroid/` (on the WSL filesystem), so the eel/ijent layer derives the WSL
environment and routes `cmd.exe /c kotlinc.bat …` into the distro, where `cmd.exe` doesn't exist
(`os error 2`). **Workaround:** set `mcp.steroid.storage.path` (the storage override, empty =
`.idea/mcp-steroid`) to a native Windows path so the working dir leaves the `\\wsl$` volume and the
spawn targets Windows. Proper fix (pass an `EelDescriptor`/spawn request targeting the local Windows
env regardless of the working dir's filesystem) is still open.

### Kotlinc version-mismatch workaround

When the IDE bundles newer Kotlin than the plugin's compiler: set `mcp.steroid.kotlinc.home` to
`<IDE>/plugins/Kotlin/kotlinc` via Registry.

### Script preprocessing (CodeButcher)

`CodeButcher.wrapWithImports()`: extracts user imports, adds defaults, merges, wraps body into a suspend
method.

## devrig ↔ plugin wire contract (frozen, additive-only)

From the 0.96 release onward, the **devrig binary must stay compatible with every plugin version it talks
to** (and vice-versa). The wire surface is: the **JSON-RPC tool-call params** devrig sends
(`DevrigBridgeToolHandlers` `put(...)` blocks → `steroid_execute_code`, `_execute_feedback`,
`_take_screenshot`, `_input`, `_open_project`), the `/windows` + `/projects/stream` responses, and the
`@Serializable` DTOs they (de)serialize with (`mcp-steroid-server`: `NpxBridge*`, `NpxStream*`, `PidMarker`,
`McpProtocol` types; `mcp-core`: `ToolCallResult`).

**Rules — never break these:**
- **Additive only.** Never remove, rename, or retype an existing param/field. New params/fields **must be
  optional with a safe default** (`= null` / `= ""` / `= false` / a defaulted enum), so an older peer that
  omits them still decodes and a newer peer that ignores them still works. Graceful degradation is fine; the
  protocol must still function end-to-end.
- **Tolerant decode is the baseline, not a license to break.** `ignoreUnknownKeys = true` tolerates *unknown*
  keys (additive on the sender) but a **required field without a default still throws on a *missing* key** —
  so the additive-only rule is what actually guarantees forward/back compat. The current required fields
  (e.g. `NpxBridgeWindowsResponse`'s 8) are the v1 baseline: present in all versions, never to be removed.
- **Enums must not be parsed strictly** against the wire — an unknown future value must degrade, not throw.
- **Changing the contract requires a conscious, reviewed, additive-only edit + a contract-test update.**
  `DevrigToolBridgeClientTest` pins each tool's exact param names/types (golden-ish assertions); a diff
  fails the build. Add a pinned case for any new tool/param.

**Wire `ProjectInfo` is pristine `{name, path}`.** The wire DTO carries exactly two fields; there is **no**
`backend` field on it. (An earlier round added `ProjectInfo.backend: String? = null`; it was reverted — the
per-project backend reference now lives on the devrig-owned, MCP-surface-only `ListedProject.backendName`,
which never crosses the wire. `ProjectsStreamService` only ever builds `ProjectInfo(name, path)`, so the
reversion changed zero emitted bytes.) A **wire-pristineness guard test** (`WirePristinenessTest`,
`mcp-steroid-server`) asserts `NpxStreamEnvelope` (`/projects/stream`) and `NpxBridgeWindowsResponse`
(`/windows`) never serialize the devrig-only `backend_name` / `project_name` / `BackendInfo` / `ListedProject`
keys.

**MCP-surface-only, never wire-crossing:**
- `OpenProjectParams.backendName: String? = null` is **MCP-surface only** and is **NOT forwarded** to the
  IDE bridge. devrig resolves it locally to a target IDE and POSTs the byte-identical `steroid_open_project`
  body (`project_path` / `trust_project` / `task_id` / `reason`). The non-forwarding invariant is pinned in
  `DevrigToolBridgeClientTest` (forwarded `arguments["backend_name"] == null`).
- `ListProjectsResponse.backends` / `BackendInfo` / `ListedProject` are devrig-computed MCP/CLI output types
  (built from devrig's routing snapshot, never fetched from the IDE) — devrig-owned and outside the wire
  contract. See PHILOSOPHY.md Tenet 5.

Deferred (revisit with a baseline release): (a) a cross-version test (devrig HEAD ↔ an older plugin build);
(b) giving devrig its **own** copy of the marker/bridge DTOs so the two version independently and only the
JSON wire shape is shared (today devrig reuses `mcp-steroid-server`'s classes, decoding tolerantly).
