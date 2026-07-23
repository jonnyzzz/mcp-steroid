# Native IntelliJ MCP tools via MCP Steroid — research record + devrig list-tools spec

**Status:** research complete and 3×-quorum validated (2026-07-22, live-tested on IU-261.25134.95);
implementation not started. The chosen first implementation step is the **devrig dedicated
list-tools API** (Scenario B below); the agent-facing index + dynamic per-tool resource pages
(Scenario A) are the specced companion follow-up.

## Goal

Let agents call the **native MCP tools** of IntelliJ's bundled MCP Server plugin
(`com.intellij.mcpServer`, `community/plugins/mcp-server` in the IntelliJ sources) through MCP
Steroid. Two consumption scenarios:

- **Scenario A — MCP Steroid's MCP**: an agent uses `steroid_execute_code` to enumerate and invoke
  native tools directly (tool named by string, arguments built with `buildJsonObject { }`), taught
  by a short `mcp-steroid://skill/native-mcp-tools` index plus dynamically generated
  `…/native-mcp-tools/<tool-name>` per-tool pages. No new `steroid_*` tool, no
  `McpScriptContext` method.
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
   `{name, description, inputSchemaJson, category}` using **only public non-impl types**
   (descriptor getters — `category` = `descriptor.category.fullyQualifiedName` — plus
   `McpToolSchema.prettyPrint()`; no `McpServerService`, no `McpCallInfo` —
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
     category: String? = null,    // descriptor.category.fullyQualifiedName — used by the
                                  // per-tool resource pages to attach category-keyed caveats
   )
   ```

   This endpoint is also the data source for devrig's dynamic
   `mcp-steroid://skill/native-mcp-tools/*` resource pages (see Scenario A) — one wire surface
   feeds the CLI and the resource pages.

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

## Spec — Scenario A: index article + dynamic per-tool pages (follow-up companion)

The article surface is split in two: a **short static index** at
`mcp-steroid://skill/native-mcp-tools` and **dynamically generated per-tool pages** at
`mcp-steroid://skill/native-mcp-tools/<tool-name>`. Per-tool pages cannot be static corpus files —
the tool set is live, per-IDE, and never cached — so they are rendered on every fetch from the
same data source as the devrig endpoint.

### Resolution seam

`FetchResourceToolHandler` (`mcp-steroid-server`) today resolves URIs by **exact match** against
the static `ResourcesIndex()` corpus. It gains a second constructor dependency alongside
`PromptsContextHandler` — a `NativeToolPagesHandler` interface (same `handler<T>()` factory
wiring pattern; mirror `PromptsContextHandler`'s shape, e.g.
`suspend fun toolsSnapshot(projectName: String): NativeMcpToolsResponse?` with `null` meaning
"surface cannot answer"), with two implementations:

- **In-IDE** (`McpSteroidToolsIJ` wiring): registered **unconditionally in the main
  `plugin.xml`** (like `PromptsContextHandler` → `PromptsContextHandlerIJ`) — NOT in
  `mcpServer-integration.xml`, because `McpSteroidToolsIJ.handler()` is a bare `getService()`
  and an optional-config registration would NPE on Android Studio instead of rendering the
  "not available" note. The impl consults `IntelliJMcpServerProbe.getInstanceOrNull()`
  internally (the Scenario B probe method); probe absent → `available=false`.
- **devrig** (`StubMcpSteroidTools` wiring): devrig's `steroid_fetch_resource` runs **locally**
  against the bundled static corpus (`DevrigPromptsContextHandler` only resolves the IDE build
  for conditionals — nothing is proxied), so the devrig implementation fetches
  `GET …/native-tools` (the Scenario B bridge endpoint) for the routed IDE and renders from the
  wire DTOs; an HTTP 404 (old plugin without the route) renders the same "plugin too old —
  update the MCP Steroid plugin" note as the CLI branch. **One data source feeds the CLI and
  both surfaces' resource pages.**

Rendering is a **shared pure function** in `mcp-steroid-server`
(`List<NativeMcpToolInfo> → markdown`), so IDE-served and devrig-served pages are identical.
To support per-tool caveat blocks, `NativeMcpToolInfo` carries
`category: String? = null` (additive; `descriptor.category.fullyQualifiedName`).

Handler resolution order: exact match on the index URI → static stub content + appended live
index; URI starts with `<index-uri>/` → per-tool page from the provider (tool-name segment
validated against `[a-zA-Z0-9_.-]+`, matched against `descriptor.name`; unknown name →
`isError=true` result — same shape as "Resource not found" — naming the index URI); everything
else → existing corpus lookup. The prefix deliberately shadows any future corpus article under
it; a guard test asserts no corpus article other than the stub has a URI equal to or prefixed
by `NativeMcpToolsPromptArticle().uri + "/"` (nested corpus folders would otherwise auto-mint a
colliding TOC article). Per-tool pages are **fetch-only** (not enumerated anywhere): consistent
with the corpus, which is already exposed solely through `steroid_fetch_resource`.

### The index page (short, static stub + live overlay)

Static corpus file `prompts/src/main/prompts/skill/native-mcp-tools.md` (generated class
`NativeMcpToolsPromptArticle`; production Kotlin references URIs only via
`NativeMcpToolsPromptArticle().uri`; per-tool URIs are built as `uri + "/" + name`, never as
literals — the implementation PR must also extend `NoHardcodedMcpSteroidUriUsageTest`'s
`sourceRoots` to `mcp-steroid-server/src/main/kotlin` and `npx-kt/src/main/kotlin`, since the
seam/renderer modules are not scanned today). The stub stays short:

1. One line: what native tools are + the plugin-enabled guard (must be
   `PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id)` — installed-but-
   disabled passes the install check while the script classpath only spans *loaded* plugins; both
   symbols public and unannotated on 261 and master), with the Android Studio note.
2. "Fetch `<index-uri>/<tool-name>` for any tool's full page."
3. The LIST fence as fallback (per-provider try/catch with the CE-first rethrow; "never cache").

Stub constraints: its kotlin fences stay **unannotated** (no `[IU,…]` product filters — fence
filters AND into the article's own filter, so an annotated fence would make the whole index
"Resource not found" on Android Studio instead of rendering the not-available note), and the
per-tool URI pattern appears in prose only, never in `# See also` (the See-also validator
checks links against the static corpus).

At fetch time the handler **appends the live index** when the provider is available, opening
with its own `#` heading (the overlay lands after the stub's generated `# See also`): one line
per tool — `name — first sentence of description — <per-tool URI>` (~59 lines) — computed
fresh per fetch. Provider unavailable → stub + an explicit "native tools not available in this
IDE (plugin missing/disabled)" note. Provider available but zero tools (e.g. every provider
threw) → the overlay heading + a "0 tools reported" line, distinct from the not-available note.
Discoverability follow-up: mention the index URI (via the generated class) from the
`steroid_fetch_resource` tool-description entry points — the arena finding is that resources
are rarely fetched unprompted.

### Per-tool pages (fully dynamic)

Each `mcp-steroid://skill/native-mcp-tools/<tool-name>` page renders, fresh per fetch:

1. `name`, full `description`, category.
2. The input schema (`inputSchemaJson` / `prettyPrint()` output) — schema-first: wrong param
   names throw a raw reflection `IllegalStateException`, so agents read this before calling.
3. A ready-to-paste **CALL fence with the tool name inlined** and args scaffolded from the
   schema's **required** properties only (`projectPath` is never required, so it never appears)
   — the scaffold emits type-correct, compilable, runnable placeholder literals: strings → a
   descriptive placeholder value, enums → the first enum value, numbers/booleans → a sensible
   literal, arrays/objects → empty `buildJsonArray { }` / `buildJsonObject { }`. The fence must
   run verbatim (the canary executes it). Shape: the validated named-args `McpCallInfo` +
   `McpCallAdditionalDataElement` recipe, CE-first catch discipline,
   `RESPECT_GLOBAL_SETTINGS`, distinct negative callId, and the `McpSessionOptions` drift note
   inline.
4. **Tool-specific caveats keyed off a single pinned list in the shared renderer** (name +
   category FQN, so both surfaces agree): consent-dialog × modal-watchdog warning on
   `execute_terminal_command` (`…toolsets.terminal.TerminalToolset`),
   `execute_run_configuration` (`…toolsets.general.ExecutionToolset`), and
   `xdebug_start_debugger_session` (`com.intellij.debuggerMcp.DebuggerToolset`); never
   `DONT_ASK`. The raw-vs-served schema note (implicit `projectPath`, `outputSchema`) goes on
   every page; router/elicitation degradation marked **2026.2+ only**. The pinned list is a
   maintained snapshot — upstream can add gated tools without our pages noticing; revisit on
   IDE-baseline bumps.

### Tests / CI

- The static stub is governed by the usual corpus contracts (`MarkdownArticleContractTest`;
  its kotlin fences compile in the KtBlock matrix — all 8 downloaded IDE distributions bundle
  `plugins/mcpserver/lib/mcpserver.jar`, verified on disk). CI cost honesty: any kotlin-fence
  change triggers the 60–120 min matrix; iterate prose with
  `./gradlew :prompts:test --tests '*MarkdownArticleContract*'`; one full matrix run before
  merge. The pre-existing `testNoNonKotlinFences` failure
  (`debugger/debug-attach-remote-jvm.md`) is unrelated debt already logged in `TODO.md`.
- The shared renderer gets unit tests over fixed `NativeMcpToolInfo` fixtures (index shape,
  per-tool shape, caveat attachment, unknown-name error).
- The `:test-integration` canary extends to: fetch the index (expect the live overlay), fetch
  one per-tool page, then execute its embedded CALL fence via `steroid_execute_code`
  (a **non-consent** tool — `find_files_by_glob`) — proving the generated fence compiles and
  runs against the live IDE, since dynamic pages are outside the KtBlock matrix. The canary
  covers **both surfaces**: index + page fetched through devrig's stdio MCP (exercising the
  bridge-backed provider) and through the in-IDE MCP server (probe-backed provider).
- A namespace-guard test (in `:prompts` or `mcp-steroid-server`) asserts no corpus article
  other than the stub has a URI equal to or prefixed by
  `NativeMcpToolsPromptArticle().uri + "/"`.
- Rejected: IntelliJ's own `McpToolsMarkdownExporter`. On **261** — the shipping floor — it has
  no per-tool API (only `generateMarkdown(Map<McpToolCategory, List<McpTool>>)` and
  `generateMarkdownForAllTools()`, each returning one monolithic String). Master's newer
  `generateMarkdownForTool` / `generateMarkdownTree` does emit an index + per-tool pages
  (convergent prior art for our index-line shape), but it is 262+-only, consumes live `McpTool`
  objects the devrig/wire-DTO side never has, and couples our page format to an unversioned
  upstream layout — we render ourselves from the wire DTOs.

Same PR as the article work: fix the stale `required_plugins` references (3 sites: lines 41,
54, 316) in `prompts/src/main/prompts/skill/coding-with-intellij-patterns.md`.

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
