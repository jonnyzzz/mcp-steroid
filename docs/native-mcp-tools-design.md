# Native IntelliJ MCP tools via MCP Steroid — research record + devrig list-tools spec

**Status:** research complete and 3×-quorum validated (2026-07-22, live-tested on IU-261.25134.95);
implementation not started. The chosen first implementation step is the **devrig dedicated
list-tools API** (Scenario B below); the agent-facing prompt article (Scenario A) is the specced
companion follow-up.

## Goal

Let agents call the **native MCP tools** of IntelliJ's bundled MCP Server plugin
(`com.intellij.mcpServer`, `community/plugins/mcp-server` in the IntelliJ sources) through MCP
Steroid. Two consumption scenarios:

- **Scenario A — MCP Steroid's MCP**: an agent uses `steroid_execute_code` to enumerate and invoke
  native tools directly (tool named by string, arguments built with `buildJsonObject { }`), taught
  by a `mcp-steroid://` prompt article. No new `steroid_*` tool, no `McpScriptContext` method.
- **Scenario B — devrig**: a dedicated API to ask a running IDE for **all its native MCP tools**
  (name + description + input schema), over devrig's own transport, scoped by `project_name`
  as a routing key.

Constraints (all validated):

- Target **2026.1 (261) as the minimum**, source-compatible through master (262+).
- **Official APIs only** — no `@ApiStatus.Internal`, no Kotlin-`internal` members.
- The `com.intellij.mcpServer` plugin dependency **stays optional**
  (`ij-plugin/src/main/resources/META-INF/plugin.xml` → `<depends optional="true"
  config-file="mcpServer-integration.xml">com.intellij.mcpServer</depends>`).
- The tool list is **never cached** — the extension points are `dynamic="true"` and other plugins
  register into them at runtime.

## Research summary — how the mcp-server plugin works

All findings below were verified against `origin/261` (worktree `~/work/intellij-261`, commit
`183016f315e15`) **and** `master` of `~/Work/intellij`, then re-verified by three independent
adversarial reviewers. Paths are relative to `community/plugins/mcp-server/`.

### Tool model and enumeration

- Two public extension points in `resources/META-INF/plugin.xml`:
  `com.intellij.mcpServer.mcpToolsProvider` (`McpToolsProvider` — returns ready `McpTool`s) and
  `com.intellij.mcpServer.mcpToolset` (`McpToolset` — annotated Kotlin methods). Both
  `dynamic="true"` on both branches.
- The bundled `impl.ReflectionToolsProvider` is itself registered on the `mcpToolsProvider` EP and
  bridges `McpToolset.enabledToolsets` into `McpTool`s via kotlin-reflect. **Therefore enumerating
  `McpToolsProvider.EP` alone covers both EPs**, and already excludes toolsets whose
  `isEnabled()` returns false.
- The server's own enumerator (`impl/McpToolsListProvider` on master, `internal`) is exactly
  `McpToolsProvider.EP.extensionList.flatMap { it.getTools() }` with a per-provider try/catch,
  recomputed on EP-change listeners. There is **no public "effective served list" API** —
  `McpServerService.getMcpTools*` are Kotlin-`internal` on both branches.
- `McpTool` is a plain public interface: `val descriptor: McpToolDescriptor` +
  `suspend fun call(args: JsonObject): McpToolCallResult`.
- `McpToolDescriptor` (public, getters stable across branches): `name`, `description`,
  `category: McpToolCategory`, `fullyQualifiedName`, `inputSchema: McpToolSchema`,
  `outputSchema: McpToolSchema?`. Master inserts `title` as the **2nd positional** constructor
  parameter (plus `displayDescription`, `annotations`) — construction is branch-fragile, **reading
  getters is safe**.
- `McpToolSchema` is byte-identical across 261/master. The public JSON renderer is
  `prettyPrint(): String` (full `{type, properties, required, additionalProperties}` object);
  `propertiesSchema: JsonObject` + `requiredProperties: Set<String>` are public for compact forms.

### Filters: raw list vs served list

The HTTP-served `tools/list` applies a filter pipeline (`mcpToolFilterProvider` EP) after raw
enumeration. Verified on 261 sources **and** live IDE state: **at factory defaults, all four
registered filter providers are no-ops** (settings mask `""`, disallow-list empty, registry
`mcp.server.tools.filter` `""`, per-tool registry key map empty) — so the raw enumeration equals
the served DIRECT-mode list unless the user configured Settings | Tools | MCP Server or registry
masks. The filter pipeline was **redesigned between 261 and master** (261: `getFilters()` +
immutable context; master: `applyFilters()` + mutable context with `routerOnly`), so replicating
the effective list is version-fragile; the raw EP enumeration is the only listing recipe that is
source-stable across branches. Consequence: **our surfaces return the raw (enabled-toolset)
universe and must label it as computed before user filters.**

Two related wire-fidelity notes: on **2026.2+/master** a real served `tools/list` strips the
reflection-injected implicit `projectPath` property from each input schema when the project is
already known (261 serves the schema with `projectPath` intact), and `outputSchema` is served only
when registry `mcp.server.structured.tool.output` is on. Raw descriptors keep both; consumers must
not treat the raw schema as byte-identical to a served one.

### Calling a tool: the coroutine-context contract

- Tools resolve their project via the `CoroutineContext.project` / `projectOrNull` extensions
  (`McpCallInfo.kt`), which read `mcpCallInfo.project`; the `mcpCallInfo` getter **throws**
  (`error("mcpCallAdditionalData called outside of a MCP call")`) when no
  `McpCallAdditionalDataElement` is present — on both branches. Nearly every built-in tool also
  calls `reportToolActivity` (first line), which dereferences `mcpCallInfo`. **The context element
  is mandatory.**
- The real server (`impl/McpSessionHandler`) wraps `tool.call(args)` in nothing more essential
  than `withContext(McpCallAdditionalDataElement(McpCallInfo(...)))`. Everything else it adds
  (telemetry span, FUS, side-effect VFS tracking, progress forwarding, master's
  `McpSessionElement` for elicitation) is optional and degrades safely. JetBrains' own
  `UniversalToolset.execute_tool` router (master-only) invokes nested tools as bare
  `tool.call(jsonArgs)` inside the outer context — the in-tree precedent for direct invocation.
- `McpCallInfo`'s constructor is public; the single **impl-package** type it requires is
  `McpServerService.McpSessionOptions` (public nested class, not `@ApiStatus.Internal`; its
  constructor has drifted with defaulted additions — 261: `(mode, toolFilter = AllowAll,
  localAgentId = null)`; master: `(mode, toolFilter = null, localAgentId = null,
  invocationMode = null)`). `McpSessionOptions(commandExecutionMode = …)` with **named arguments
  only** is source-compatible across all of it. Same for `McpCallInfo` (master appends a defaulted
  `sessionId`).
- Fields tools actually read from `McpCallInfo` on 261 beyond `project`:
  `mcpSessionOptions.commandExecutionMode` (consent path, `util/execution.util.kt`),
  `clientInfo.name` (terminal session reuse key; code-provenance attribution), `callId`
  (`build_project` → `ProjectTaskContext(callId)` correlation). No 261 tool reads `headers`.
- Error contract: tools throw `McpExpectedError` (via `mcpFail`) for user-renderable errors — map
  it to `McpToolCallResult.error(e.mcpErrorText, e.mcpErrorStructureContent)`. A **wrong argument
  name** surfaces as a raw `IllegalStateException` from the reflection layer ("No argument is
  passed for required parameter …") — hence the schema-first workflow below. Results may also
  return `isError = true` directly.

### Version compatibility

| | 252 | 261 GA → 2026.1.x | master (262+) |
|---|---|---|---|
| Plugin present | yes (GA-era: different `McpCallInfo`, no `McpSessionOptions`) | yes, bundled in IDEA | yes |
| Recipe below compiles | **no** at GA-era 252.26199; later 252.x backports (brave-mode commit `bd3c12dec0078`, 2025-09) close the gap — moot either way: MCP Steroid `sinceBuild=261` | **yes — live-verified** | yes — kotlinc-verified against 262 EAP jars |

- Android Studio **never bundles** the plugin (`IdeaCommunityProperties`'s AS profile removes
  `intellij.mcpserver` from the bundled list). MCP Steroid runs on AS, so both scenarios must
  degrade explicitly there (guard fence / `available=false`).
- Tool **names churn across versions** (261 registers `TextToolset`; master deletes it, renames
  `get_file_text_by_path`-era tools to `read_file`, adds `UniversalToolset`/`DiagnosticsToolset`).
  Never hardcode a tool inventory — enumerate, then match by `descriptor.name`, and handle the
  not-found case with a clean message.
- 261 has **no** `UniversalToolset`/`execute_tool` and no elicitation — those degradation caveats
  are 262+ only.
- 262-only symbols to keep out of cross-version code: `McpToolDescriptor.title` /
  `displayDescription` / `annotations`, `McpCallInfo.sessionId`, `McpTool.isUserConfigurable`,
  `McpSessionInvocationMode`, the new `McpToolFilter` subtypes.

### MCP Steroid side: why no plugin code is needed for Scenario A

`steroid_execute_code` scripts compile against the **classpath of every loaded plugin**
(`ScriptClassLoaderFactory.ideClasspath()` enumerates `PluginManagerCore.loadedPlugins` and their
content modules; `koltinc/ScriptClassLoader.kt`) and run under a parent classloader that delegates
across the same set. So `import com.intellij.mcpserver.McpToolsProvider` compiles and resolves
whenever the plugin is enabled, and fails with a plain kotlinc `unresolved reference` when it is
not — the documented semantics the project standardized on when `required_plugins` was removed
(`release/notes/0.95.0.md`). Scripts execute on a background pool (never EDT), inside
`withTimeout` (`execution/ScriptExecutor.kt`), so calling suspend tools from a script body is a
safe call site.

## The validated recipes

These exact shapes were (a) executed live on IU-261.25134.95 via `steroid_execute_code`, and
(b) compiled with kotlinc 2.3.20 against four shipped IDE jars: IU-261.26222 (stable,
with `-Werror`), IU-262.8377 (EAP), CLion 2026.1.4, Rider 262.8377 (EAP).

**LIST** — recompute per request; per-provider isolation because third-party providers
(rider, database, debugger-mcp, jupyter, …) also register on the EP and may throw:

```kotlin
val tools = com.intellij.mcpserver.McpToolsProvider.EP.extensionList.flatMap { provider ->
    try {
        provider.getTools()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e  // ProcessCanceledException extends CancellationException — never swallow or log it
    } catch (e: Exception) {
        System.err.println("MCP tools provider $provider failed: $e")
        emptyList()
    }
}
// descriptor.name / .description / .inputSchema.prettyPrint()
```

Measured on the live IDE: 59 tools, ~90 ms per full recompute (warm JVM), ~101 KB of text for all
descriptions + pretty-printed schemas. Per-request recompute is viable; never cache.

**CALL** — named arguments only; distinct negative `callId` per call; `CancellationException`
must be rethrown (steroid wraps scripts in `withTimeout` — swallowing it would fake a tool result
inside a cancelled coroutine); unexpected exceptions propagate so the agent gets the full stack:

```kotlin
import com.intellij.mcpserver.*
import com.intellij.mcpserver.impl.McpServerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

val toolName = "find_files_by_glob"
val args = buildJsonObject { put("globPattern", "docs/*.md") }

val tool = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
    .firstOrNull { it.descriptor.name == toolName }
    ?: error("Native MCP tool '$toolName' not found — names vary per IDE version; list first")

val callInfo = McpCallInfo(
    callId = -System.nanoTime().toInt().and(0x7FFFFFFF) - 1,  // distinct negative id per call
    clientInfo = ClientInfo("mcp-steroid", "1.0"),
    project = project,
    mcpToolDescriptor = tool.descriptor,
    rawArguments = args,
    meta = JsonObject(emptyMap()),
    mcpSessionOptions = McpServerService.McpSessionOptions(
        commandExecutionMode = McpServerService.AskCommandExecutionMode.RESPECT_GLOBAL_SETTINGS,
    ),
)

val result = try {
    withContext(McpCallAdditionalDataElement(callInfo)) { tool.call(args) }
} catch (e: CancellationException) {
    throw e
} catch (e: McpExpectedError) {
    McpToolCallResult.error(e.mcpErrorText, e.mcpErrorStructureContent)
}
// result.isError / result.content / result.structuredContent
```

Live-test evidence (2026-07-22, IU-261.25134.95): `get_project_modules` (no args) returned the
real module list; `find_files_by_glob {globPattern: "docs/PHILOSOPHY.md"}` returned
`{"files":["docs/PHILOSOPHY.md"]}`; `read_file` on a nonexistent path returned `isError=true`
via `McpExpectedError` (no crash); a wrong parameter name produced the raw reflection
`IllegalStateException` (motivating schema-first + the catch discipline above).

### Known hazards (must be documented wherever the recipe ships)

- **Consent-gated tools** (`execute_terminal_command`, `execute_run_configuration`, debugger-run):
  under `RESPECT_GLOBAL_SETTINGS` with brave mode off (the factory default), the tool opens a
  **modal consent dialog on the EDT** (`util/execution.util.kt` → `askConfirmation`). Interaction
  with steroid's modal policy: under the default `smart_non_modal` the watchdog closes the dialog
  with CANCEL (→ `McpExpectedError("User rejected command execution")`) **and fails the whole
  execution**; under `non_modal`/`unleashed` the script blocks in a nested EDT event loop that
  `withTimeout` cannot interrupt — it hangs until a human closes the dialog. The honest path:
  brave mode is a user opt-in in Settings | Tools | MCP Server; without it, expect rejection.
  **Never use `DONT_ASK`** — it silently bypasses the user's consent for command execution.
- **`ClientInfo.name` is consumed downstream** (terminal session reuse key, code-provenance
  document attribution) — keep it a stable, truthful identifier (`"mcp-steroid"`).
- **Dynamic EP unload mid-call**: a script holding `McpTool` references across a plugin unload
  can hit disposed services; never-cache + short-lived calls bound this.

## Decision — devrig-side alternatives considered

For Scenario B, five shapes were evaluated (transport research, 2026-07-22):

| # | Shape | Verdict |
|---|---|---|
| 1 | **Bridge endpoint + CLI subcommand + internal client method** | **Chosen** |
| 2 | devrig-internal client method only | No user/agent-visible deliverable |
| 3 | New `steroid_*` MCP tool | Fails PHILOSOPHY Tenet 1's first gate (execute_code + direct API already covers it); would need the 3-reviewer consensus it cannot clear |
| 4 | devrig POSTs a canonical `steroid_execute_code` enumeration script over the existing `/tools/call/stream` | Zero wire change, but pays a kotlinc compile per invocation (seconds vs ~90 ms), bakes Kotlin source into the devrig binary, and couples a structured API to script stdout parsing |
| 5 | devrig speaks MCP `tools/list` directly to the native server's own endpoint (`PidMarker.intellijMcpServer`) | Native server is **off by default**; the marker field is written once at IDE startup (stale after toggles); adds a second protocol stack to devrig's one-endpoint design. Field stays for the future |

**Tenet-5 note ("prefer resolving new behavior inside devrig over extending the wire",
`docs/PHILOSOPHY.md`):** alternative 4 is the in-devrig option and loses on latency (kotlinc per
request vs ~90 ms enumeration), on robustness (structured data via script prints), and on
layering (devrig would carry IntelliJ-version-sensitive Kotlin source). A new **additive** bridge
endpoint is the smaller system: one GET, one DTO file, contract-pinned. Tenet 1's reviewer gate
governs `steroid_*` MCP tools only; bridge endpoints and CLI commands are governed by Tenets 3
(stateless — satisfied: fresh enumeration per request) and 5 (additive wire — satisfied by
construction).

## Spec — Scenario B: devrig list-tools API

### IDE side (`ij-plugin`)

1. Extend `IntelliJMcpServerProbe` (`server/IntelliJMcpServerProbe.kt`) with
   `listNativeTools(): List<NativeMcpToolInfo>` — returning the wire DTO directly follows the
   existing precedent (`probe()` already returns `IntelliJMcpServerInfo`, an `mcp-steroid-server`
   type; `ij-plugin` depends on `:mcp-steroid-server`). The route handler derives
   `available = (getInstanceOrNull() != null)`; the probe itself never encodes availability.
   The impl lives in `IntelliJMcpServerProbeImpl`, registered **only** via the existing
   `META-INF/mcpServer-integration.xml` optional-dependency config — the plugin-absent case keeps
   `getInstanceOrNull() == null`. Compile-time visibility already exists
   (`bundledPlugin("com.intellij.mcpServer")` in `ij-plugin/build.gradle.kts`).
2. The implementation enumerates `McpToolsProvider.EP.extensionList.flatMap { it.getTools() }`
   **fresh on every call** with the per-provider try/catch above — including the
   `CancellationException`-first rethrow: the probe lives on the `ij-plugin/.../server/` hot path,
   where every `catch (Exception)` must match CE/PCE first and rethrow without logging
   (`ij-plugin/CLAUDE.md` → Error handling). Each `descriptor` maps to
   `{name, description, inputSchema}` using **only public non-impl types**
   (descriptor getters + `McpToolSchema.prettyPrint()`; no `McpServerService`, no `McpCallInfo` —
   listing is independent of whether the native MCP server is enabled/running, and keeps the
   precompiled probe restricted to the binary-stable getter surface).
3. While touching the file: drop the `internal` modifier from `IntelliJMcpServerProbeImpl`
   (pre-existing violation of the root `CLAUDE.md` ban; registration comes from the XML, not
   visibility).

### Wire (bridge endpoint)

4. New route `GET ${DEVRIG_RPC_PATH_PREFIX}/native-tools` in
   `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/NpxBridgeRoutes.kt`, beside
   `GET /windows`, behind the same `requireNpxBridgeAuthorization()` bearer-token guard.
5. Response DTOs are `@Serializable` types in **`mcp-steroid-server`** (the wire-DTO home per the
   `ij-plugin/CLAUDE.md` wire table), additive-only with safe defaults (if the deferred
   "devrig gets its own DTO copies" item from the wire-contract section ever lands, these DTOs
   join the forked set):

   ```
   NativeMcpToolsResponse(
     available: Boolean,          // false => MCP Server plugin absent/disabled; tools empty
     unavailableReason: String? = null,
     unfiltered: Boolean = true,  // list is the enabled-toolset universe BEFORE user
                                  // Settings/registry filters — labeled on the wire, not just in docs
     tools: List<NativeMcpToolInfo> = emptyList(),
   )
   NativeMcpToolInfo(
     name: String,
     description: String,
     inputSchemaJson: String,     // McpToolSchema.prettyPrint() output
   )
   ```

   `backend_name` **never appears on the wire** — it is devrig-computed CLI output only (extend
   `WirePristinenessTest` to pin this for the new DTOs).
6. Probe service absent (plugin disabled / Android Studio) → HTTP 200 with
   `available=false` + reason (e.g. `"MCP Server plugin (com.intellij.mcpServer) is not installed
   or disabled in this IDE"`); never a crash, never a 500.
7. Old plugin without the route → devrig receives **404** and must render "plugin too old —
   update the MCP Steroid plugin" explicitly. This is **new client behavior**: today
   `DevrigToolBridgeClient.fetchWindows` fails with a bare `error("HTTP …")` on any non-2xx, so
   the 404 branch needs its own code path + test; do not inherit the fetchWindows shape blindly.

### devrig side (`npx-kt`)

8. `DevrigToolBridgeClient.fetchNativeTools(ide: DiscoveredIde)` mirroring `fetchWindows`'
   parameter shape (plus the explicit 404 branch); the CLI resolves
   `DevrigProjectRoutingService.requireProject(exposedName)` → `ProjectRoute` and passes its
   `DiscoveredIde` — `project_name` is a **routing key** that selects which IDE process to ask
   (native tool listing is app-level in the IDE; project binding matters only for future *calls*,
   which stay in `steroid_execute_code`).
9. CLI surface: `devrig project tools <project_name> [--json]`. `ProjectCommand` is a leaf
   command today; convert it to `invokeWithoutSubcommand = true` + `subcommands(...)` following
   the `BackendCommand` precedent so bare `devrig project` keeps working. `--json` comes free via
   `DevrigCliktCommand`. Human output: tool name + first description line. `--json` emits a
   **devrig-owned output type** (not a wire DTO): `{project_name, backend_name, available,
   unavailableReason, unfiltered, tools[]}` — `backend_name` lives only in this devrig-computed
   output, never on the bridge wire.
10. No caching anywhere (Tenet 3): every CLI/API invocation re-hits the IDE, which re-enumerates.
    Expected cost ≈ 100 ms + payload ≈ 110–120 KB JSON for ~59 tools — acceptable per request.

### Tests / acceptance criteria

- Contract pin for the new DTOs (`DevrigToolBridgeClientTest` style) + `WirePristinenessTest`
  extension (no `backend_name` on the wire; additive defaults decode when fields are missing).
- 404-path unit test (old plugin) and `available=false` path test (probe absent).
- `:test-integration` Docker case: `devrig project tools` against a live IDE returns >0 tools
  with non-empty schemas; plus one `steroid_execute_code` CALL canary using a **non-consent**
  tool (`find_files_by_glob`) — consent-gated tools would hang/fail headless (see hazards; the
  1-minute rule applies).
- Wire-table entry added to `ij-plugin/CLAUDE.md` → "devrig ↔ plugin wire contract" listing the
  new endpoint + DTOs.

## Spec — Scenario A: prompt article (follow-up companion)

File `prompts/src/main/prompts/skill/native-mcp-tools.md` → `mcp-steroid://skill/native-mcp-tools`
(generated class `NativeMcpToolsPromptArticle`; production Kotlin references it only via
`NativeMcpToolsPromptArticle().uri` — `NoHardcodedMcpSteroidUriUsageTest`). Content, in order:

1. Plugin-enabled guard fence first — `isPluginInstalled` alone passes for an
   installed-but-**disabled** plugin while the script classpath only spans *loaded* plugins, so
   the guard must be `PluginManagerCore.isPluginInstalled(id) &&
   !PluginManagerCore.isDisabled(id)` (both public and unannotated on 261 and master), with the
   Android Studio note — a deterministic "plugin missing/disabled" message beats a bare
   `unresolved reference: mcpserver` compile error.
2. The LIST fence (per-provider try/catch; "never cache; recompute per call").
3. Schema-first guidance: print `inputSchema.prettyPrint()` before calling; wrong param names
   throw raw `IllegalStateException`.
4. The CALL fence exactly as validated above (complete and compilable — the KtBlock matrix
   compiles every ` ```kotlin ` fence; all 8 downloaded IDE distributions bundle
   `plugins/mcpserver/lib/mcpserver.jar`, verified on disk).
5. Caveats: consent-dialog × modal-watchdog interaction (name the gated tools; never `DONT_ASK`);
   raw-vs-served list and schema differences (implicit `projectPath`, `outputSchema`); tool-name
   churn; router/elicitation degradation marked **2026.2+ only**; `ClientInfo.name` consumption;
   `McpSessionOptions` drift note inline ("if this line stops compiling, check its current
   constructor — params are added with defaults").
6. Same PR: fix the stale `required_plugins` references (3 sites: lines 41, 54, 316) in
   `prompts/src/main/prompts/skill/coding-with-intellij-patterns.md`.

CI cost honesty: any kotlin-fence change triggers the 60–120 min KtBlock matrix. Iterate prose
with `./gradlew :prompts:test --tests '*MarkdownArticleContract*'`; schedule exactly one full
matrix run before merge. The pre-existing `testNoNonKotlinFences` failure
(`debugger/debug-attach-remote-jvm.md`) is unrelated debt already logged in `TODO.md`.

## API stability tiers (referenced surface only)

| Tier | Symbols | Policy |
|---|---|---|
| Public, non-impl, unannotated | `McpTool`, `McpToolCallResult(+Content)`, `McpExpectedError`/`mcpFail`, `McpToolsProvider(+EP)`, `McpToolset(+EP)`, `McpToolDescriptor` getters, `McpToolSchema` (incl. `prettyPrint`), `McpCallInfo` (+ctor, named args), `ClientInfo`, `McpCallAdditionalDataElement`, `CoroutineContext.project/projectOrNull` | Use freely |
| Public but impl-package | `McpServerService.McpSessionOptions`, `AskCommandExecutionMode` | Scenario A only (runtime-compiled, self-heals on defaulted drift); **excluded from the precompiled probe** |
| Kotlin-`internal` | `McpServerService.getMcpTools*` / `callId`, `McpToolsListProvider`, `McpSessionHandler`, `McpSessionElement`, settings/filter impls | Never referenced |
| 262-only | `title`/`annotations`/`sessionId`/`isUserConfigurable`/`McpSessionInvocationMode`/new filter subtypes | Keep out of cross-version code |

## References

- IntelliJ sources: `community/plugins/mcp-server/` — `McpTool.kt`, `McpToolset.kt`,
  `McpToolsProvider.kt`, `McpToolDescriptor.kt`, `McpToolSchema.kt`, `McpCallInfo.kt`,
  `impl/McpServerService.kt`, `impl/McpSessionHandler.kt`, `impl/ReflectionToolsProvider.kt`,
  `util/execution.util.kt`, `toolsets/general/UniversalToolset.kt` (master), `README.md`.
- This repo: `docs/intellij-builtin-servers.md` (probe design; native-server endpoint background),
  `docs/PHILOSOPHY.md` (tenets), `ij-plugin/CLAUDE.md` (wire contract),
  `server/IntelliJMcpServerProbe*.kt`, `META-INF/mcpServer-integration.xml`,
  `koltinc/ScriptClassLoader.kt`, `server/NpxBridgeRoutes.kt`,
  `npx-kt` `Cli.kt` / `DevrigToolBridgeClient.kt`, `mcp-steroid-server` DTOs +
  `WirePristinenessTest`.
- Related backlog: issue #114 (built-in MCP coexistence — this design honors its guards: no new
  `steroid_*` tools, additive wire, no wrapper reimplementation); `TODO.md` "plugins[]
  enumeration" (complementary: the marker advertises the native server's existence; this endpoint
  enumerates live tools).
