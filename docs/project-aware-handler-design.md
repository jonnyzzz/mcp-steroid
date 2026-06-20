# Project-aware MCP handler layer — design proposal (v5-final)

**Status:** finalized after r5 (5 reviewers; convergent doc papercuts patched
in-place — 5-iteration budget consumed). Design substance is settled; the
patches below close the remaining surface-level inconsistencies.

**Convergent r5 patches (applied directly):**

1. `protected abstract val resolver` is the **only** form throughout; all
   `override fun resolver()` and `resolver().resolve(...)` callsites updated.
2. `projectScopedHandler` reverts to `Class<*>` + **one** localised
   `@Suppress("UNCHECKED_CAST")` inside the helper. The `Class<out H>` form
   does not compile at the intended generic call sites — Kotlin class
   literals for generic interfaces are star-projected (`Class<XxxHandler<*>>`),
   not `Class<out XxxHandler<P>>`. Verified by 3 reviewers with isolated
   Kotlin 2.2.21 probes.
3. ExecuteCode sketch drops the spurious `cancel_on_modal` extraction
   (today's `ExecuteCodeToolSpec` doesn't decode that key — it relies on
   the `ExecCodeParams.cancelOnModal = true` default).
4. §8.9 stale wording aligned with §8.3.
**Author:** working session 2026-05-24.
**Goal:** propose a generic abstraction that unifies how project-scoped MCP
tool handlers receive their project context across the IJ-plugin and devrig
(npx-kt) sides. **No code lands from this doc** — it is a design only.

## r4 issues addressed in v5

| # | r4 issue                                                                                  | Fix in v5                                                                                              |
|---|-------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| 1 | §4 `ExecuteCodeToolSpec` sketch had wrong `ExecCodeParams` constructor and dropped `reason ?: "No reason provided"`. | Sketch rewritten with **named args matching the current `ExecCodeParams(taskId, code, reason, timeout, cancelOnModal, dialogKiller)`**; preamble warns "do not copy literally — keep each Spec's decode block byte-for-byte". |
| 2 | Forwarded `project_name` over HTTP from devrig must be `ProjectRoute.originalProjectName`, not `scope.projectName`. | Explicit invariant added to §5.2 + §7 Phase 1..6.                                                       |
| 3 | Phase 0 understated its blast radius — actually a multi-module atomic commit.             | §7 Phase 0 now explicitly enumerates the 5+ files across 3 modules.                                     |
| 4 | Phase 8 was wishy-washy ("remove from production paths").                                 | §7 Phase 8 specifies: delete `DevrigProjectRoutingService.requireProject` or `@Deprecated` with `replaceWith` to `routeProject`. |
| 5 | `projectScopedHandler<H>(raw: Class<*>)` was too loose — accepted mismatched `<H>`/raw pairs. | Signature tightened to `Class<out H>`; mismatch is now a compile error.                                 |
| 6 | `resolver()` called per tool invocation; could be a `val`.                                | §4 changes to `protected abstract val resolver: ProjectResolver<P>`.                                    |
| 7 | Phase 7 didn't enumerate test renames after `FetchResourceToolHandler` → `FetchResourceToolSpec`. | §7 Phase 7 now lists `FetchResourceToolTest.kt`, `NoHardcodedMcpSteroidUriUsageTest.kt`, and any comment refs. |

## r3 issues addressed in v4

| # | r3 issue                                                                                                     | Fix in v4                                                                                                              |
|---|--------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| 1 | "Mixed-mode `registerAll` with two overloads per Spec" was impossible (FQN collision on interface name).     | **Dropped mixed-mode entirely.** Phase 0 only lands the 3 new types + makes `McpSteroidTools<P>` generic. Per-tool migration is atomic. |
| 2 | `handler<XxxToolHandler<P>>()` with outer non-reified `P` is broken.                                         | **Replaced the `inline reified` path** for generic interfaces with explicit raw-class + suppressed cast. Non-generic interfaces keep the reified helper. |
| 3 | Empty `fun interface FetchResourceToolHandler<P>` is invalid Kotlin.                                         | **Deleted.** FetchResource has no handler interface — its Spec consumes `PromptsContextHandler` directly. The Spec is generic on `P` only to receive the resolver. |
| 4 | `DevrigPromptsContextHandler.buildPromptsContext` second name-based lookup creates a TOCTOU race window.     | **Switched to `routeProject(...)` + `Generic` fallback** in the devrig impl. User-facing not-found is emitted at the Spec resolver step; race window only produces `Generic`. |
| 5 | `DevrigDescriptorParityTest.DirectDescriptorTools` would need to override `resolver()` too, doc didn't say.  | **§8.6 spells out the override** — no-op `ProjectResolver<Unit>` that returns `NotFound("", emptyList())`, never reached. |

## 1. Problem

`McpSteroidTools.registerAll` registers 8 tool specs. Of those, **4** carry
`projectName` as the first argument of their handler method
(`ExecuteCodeToolHandler`, `ExecuteFeedbackToolHandler`,
`VisionScreenshotToolHandler`, `VisionInputToolHandler`).
`FetchResourceToolHandler` is itself an `McpTool` that also requires
`project_name` and routes through
`PromptsContextHandler.buildPromptsContext(projectName)`, so it is the
**5th** project-scoped tool. (`ApplyPatchToolHandler` and
`ActionDiscoveryToolHandler` were removed in May 2026 — see TASKS.md C3 and C4.)

Three tools stay project-free (`ListProjects`, `ListWindows`, `OpenProject`)
and one helper interface stays app-level (`PromptsContextHandler` — keeps its
existing optional-projectName signature; consumed by FetchResource and by
`mcp-steroid://` resources).

### Existing error contract

`mcp-core/.../McpToolRegistry.kt:82-95` wraps every thrown `Exception` from
a tool call into `ToolCallResult(isError=true)`. Wire shape is JSON-RPC
`result` with `isError=true`, **not** a JSON-RPC `error` envelope.
`docs/devrig-naming.md:982-994` pins this contract: stale `project_name` MCP
calls must return a `ToolCallResult` error containing the refresh message.

v4 keeps this contract intact and improves the message quality on devrig
from a stacktrace-shaped string to the IJ-style
`"Project not found: 'X'. Available: [...]"` form.

## 2. Goal

Introduce a small, generic abstraction in `mcp-steroid-server` that:

1. Removes the projectName-resolution duplication on both sides.
2. Lets each project-scoped handler interface declare
   `(scope, params, [progress]) -> ToolCallResult`, parametrised by a
   project-handle type `P`.
3. Plugs in two concrete `P` values:
   * `P = com.intellij.openapi.project.Project` on IJ-plugin.
   * `P = com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute` on devrig.
4. Keeps the existing JSON schema unchanged — `project_name` stays in
   `inputSchema.properties` of every project-scoped tool, including
   `steroid_fetch_resource`.
5. Picks a **single** user-facing error model for not-found:
   `ToolCallResult.errorResult("Project not found: 'X'. Available: [a, b, c]")`
   on **both sides**, including FetchResource. Always emitted at the Spec
   resolver step; never inside a downstream handler.

## 3. Non-goals

* Unifying the actual implementations of project-scoped handlers (IJ runs
  code locally; devrig forwards over HTTP).
* Touching ListProjects / ListWindows / OpenProject. Not project-scoped.
* Rewriting the per-Spec JSON decoding. **Every Spec keeps its bespoke
  argument extraction** (ApplyPatch's tolerant `hunks` parsing, Feedback's
  rating validation, etc.).
* Sharing a single Kotlin parent `interface` across the project-scoped
  handlers. Each is a standalone `fun interface`; the shared **shape**
  `(ProjectScope<P>, params, [progress]) -> ToolCallResult` is a convention,
  not a Kotlin type.

## 4. Proposed shape (in `mcp-steroid-server`)

Three new types only:

```kotlin
/** Resolves an MCP-protocol projectName into a concrete project handle of type P. */
fun interface ProjectResolver<P> {
    suspend fun resolve(projectName: String): ProjectResolution<P>
}

sealed interface ProjectResolution<out P> {
    data class Resolved<P>(val project: P) : ProjectResolution<P>
    data class NotFound(val projectName: String, val available: List<String>) : ProjectResolution<Nothing>
}

/** Value object every project-scoped handler receives in place of a bare String. */
data class ProjectScope<out P>(
    val project: P,
    val projectName: String,
)
```

Each per-tool handler is its own standalone `fun interface`, parametrised
on `P`. There is no shared parent interface.

```kotlin
fun interface ExecuteCodeToolHandler<P> {
    suspend fun handle(
        scope: ProjectScope<P>,
        params: ExecCodeParams,
        progress: McpProgressReporter,
    ): ToolCallResult
}

fun interface ApplyPatchToolHandler<P> {
    suspend fun handle(scope: ProjectScope<P>, params: ApplyPatchRequest): ToolCallResult
}

// ExecuteFeedback, ActionDiscovery, VisionInput follow the 2-arg ApplyPatch shape.
// VisionScreenshot follows the 3-arg ExecuteCode shape.
//
// FetchResource has NO handler interface. Its Spec consumes
// PromptsContextHandler directly (interface unchanged).
```

The 3-arg vs 2-arg arity is preserved per tool. There is no SAM problem
because no class implements two `fun interface`s with conflicting `handle`
methods.

### `handler<T>()` and reified-generic interactions

`McpSteroidTools.handler<T>()` today is `inline fun <reified T : Any> handler(): T`.
Calling `handler<ExecuteCodeToolHandler<P>>()` from inside
`McpSteroidTools<P>.registerAll` uses the enclosing class's non-reified `P`
as a reified type argument — Kotlin rejects this.

v4 splits the helper into two:

```kotlin
abstract class McpSteroidTools<P> {
    protected abstract val resolver: ProjectResolver<P>
    abstract fun <T> handler(type: Class<T>): T

    /** Reified path for the **non-generic** handler interfaces. */
    inline fun <reified T : Any> handler(): T = handler(T::class.java)

    /**
     * Raw-class lookup for **generic** handler interfaces. Kotlin class
     * literals for generic interfaces are star-projected
     * (`XxxHandler::class.java` is `Class<XxxHandler<*>>`), so we accept
     * `Class<*>` and centralise one suppressed cast here. **Use only from
     * `registerAll`** — a mismatched (raw class, H) pair survives the cast
     * and only explodes at first invocation.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <H : Any> projectScopedHandler(raw: Class<*>): H =
        handler(raw) as H
}
```

`registerAll` body (after all 7 tools have migrated):

```kotlin
fun registerAll(server: McpServerCore) {
    val tools = server.toolRegistry
    // app-level — unchanged, reified path
    tools.registerTool(ListProjectsToolSpec    { handler<ListProjectsToolHandler>() })
    tools.registerTool(ListWindowsToolSpec     { handler<ListWindowsToolHandler>() })
    tools.registerTool(OpenProjectToolSpec     { handler<OpenProjectToolHandler>() })
    // project-scoped — raw-class path
    tools.registerTool(ExecuteCodeToolSpec(
        { resolver },
        { projectScopedHandler<ExecuteCodeToolHandler<P>>(ExecuteCodeToolHandler::class.java) },
    ))
    tools.registerTool(ApplyPatchToolSpec(
        { resolver },
        { projectScopedHandler<ApplyPatchToolHandler<P>>(ApplyPatchToolHandler::class.java) },
    ))
    // … same shape for ExecuteFeedback, ActionDiscovery, VisionScreenshot, VisionInput …
    tools.registerTool(FetchResourceToolSpec(
        { resolver },
        { handler<PromptsContextHandler>() }, // FetchResource has NO per-tool handler — uses PromptsContextHandler
    ))
}
```

### Spec rewrite (delta-only)

Each project-scoped `*ToolSpec` keeps its bespoke JSON decode block
**byte-for-byte verbatim**. Only the final invocation block changes.

**Important:** the sketch below is illustrative, not authoritative. The
canonical `ExecCodeParams` constructor is
`(taskId, code, reason, timeout, cancelOnModal, dialogKiller)` — slot 5 is
`cancelOnModal`, slot 6 is `dialogKiller`. The current Spec also defaults
`reason` to `"No reason provided"` rather than erroring on missing. Migration
commits must reproduce those exactly — copy the existing Spec's body, do
not retype from the sketch.

```kotlin
class ExecuteCodeToolSpec<P>(
    private val resolver: () -> ProjectResolver<P>,
    private val handler: () -> ExecuteCodeToolHandler<P>,
) : McpTool {
    override suspend fun call(context: McpToolCallContext): ToolCallResult {
        val args = context.params.arguments
        // ---- bespoke decode block — UNCHANGED from today's ExecuteCodeToolSpec.call() ----
        val projectName = args["project_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")
        val code = args["code"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: code")
        val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult.errorResult("Missing required parameter: task_id")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "No reason provided"
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull
        val dialogKiller = args["dialog_killer"]?.jsonPrimitive?.booleanOrNull
        val params = ExecCodeParams(
            taskId = taskId,
            code = code,
            reason = reason,
            timeout = timeout,
            // cancelOnModal stays at its constructor default (true) — current
            // Spec doesn't decode `cancel_on_modal` from args.
            dialogKiller = dialogKiller,
        )
        // ---- end unchanged block ----

        // NEW: resolver + invocation
        return when (val r = resolver().resolve(projectName)) { // NB: `resolver` here is the Spec's `() -> ProjectResolver<P>` constructor lambda, NOT McpSteroidTools.resolver. See §4 above.
            is ProjectResolution.NotFound ->
                ToolCallResult.errorResult(
                    "Project not found: '${r.projectName}'. Available: ${r.available}"
                )
            is ProjectResolution.Resolved ->
                handler().handle(
                    ProjectScope(r.project, projectName),
                    params,
                    context.mcpProgressReporter,
                )
        }
    }
}
```

The bespoke ApplyPatch decoding (tolerant `hunks: JsonArray | string`, strict
`dry_run` boolean, etc.) and the other Specs' decode blocks stay verbatim.

## 5. Per-side instantiation

> **Superseded by #92.** The `ProjectResolverIJ.resolve` sketch below matches on the raw
> `it.name == projectName`. The shipped resolver (`OpenProjectsService.resolveProject` /
> `ProjectNameService`) instead matches the **within-IDE-unique** `project_name` (`<name>-<hash>`)
> with **no raw-name fallback**, so two same-named projects (a checkout + its worktree) route
> correctly. Treat the raw-name matching here as historical.

### 5.1 IJ-plugin

```kotlin
@Service(Service.Level.APP)
class McpSteroidToolsIJ : McpSteroidTools<Project>() {
    override val resolver: ProjectResolver<Project> = ProjectResolverIJ
    override fun <T> handler(type: Class<T>) =
        ApplicationManager.getApplication().getService(type)
}

object ProjectResolverIJ : ProjectResolver<Project> {
    override suspend fun resolve(projectName: String): ProjectResolution<Project> {
        val open = readAction { ProjectManager.getInstance().openProjects.toList() }
        val hit = open.find { it.name == projectName }
        return if (hit != null) ProjectResolution.Resolved(hit)
        else ProjectResolution.NotFound(projectName, open.map { it.name })
    }
}

@Service(Service.Level.APP)
class ExecuteCodeToolHandlerIJ : ExecuteCodeToolHandler<Project> {
    override suspend fun handle(
        scope: ProjectScope<Project>,
        params: ExecCodeParams,
        progress: McpProgressReporter,
    ): ToolCallResult =
        scope.project.service<ExecutionManager>().executeWithProgress(scope, params, progress)
}
```

`plugin.xml` `applicationService` registrations stay as-is — `serviceInterface`
keeps the raw `…ExecuteCodeToolHandler` FQN. Service lookup is by raw class;
erasure makes the impl `<Project>` assignable.

### 5.2 Devrig (npx-kt)

```kotlin
class StubMcpSteroidTools(val services: DevrigServices) : McpSteroidTools<ProjectRoute>() {
    override val resolver: ProjectResolver<ProjectRoute> = ProjectResolverDevrig(services.projectRouting)
    override fun <T> handler(type: Class<T>): T = … // existing when-table
}

class ProjectResolverDevrig(private val routing: DevrigProjectRoutingService) :
    ProjectResolver<ProjectRoute> {
    override suspend fun resolve(projectName: String): ProjectResolution<ProjectRoute> {
        val r = routing.routeProject(projectName)
        return if (r != null) ProjectResolution.Resolved(r)
        else ProjectResolution.NotFound(projectName, routing.routes().keys.toList())
    }
}

class DevrigExecuteCodeToolHandler(private val bridge: DevrigToolBridgeClient) :
    ExecuteCodeToolHandler<ProjectRoute> {
    override suspend fun handle(
        scope: ProjectScope<ProjectRoute>,
        params: ExecCodeParams,
        progress: McpProgressReporter,
    ): ToolCallResult =
        bridge.callTool(scope.project, "steroid_execute_code") {
            // INVARIANT: forwarded `project_name` is the **original** name the
            // IDE knows, NOT scope.projectName (which is the devrig-exposed
            // `<name>-<hash8>` form). Existing handler code already keys off
            // `route.originalProjectName` — preserve that.
            put("project_name", scope.project.originalProjectName)
            // … rest of the arg forwarding …
        }
}
```

### 5.3 DevrigPromptsContextHandler — TOCTOU fix

Today's devrig impl throws `ProjectRouteNotFoundException` from
`requireProject(...)` when the named project is not in the routing table:

```kotlin
// CURRENT (npx-kt/.../StubMcpSteroidTools.kt:86-95)
override fun buildPromptsContext(projectName: String?): PromptsContext {
    val route = if (projectName.isNullOrBlank()) {
        routing.singleRouteOrNull() ?: return PromptsContext.Generic
    } else {
        routing.requireProject(projectName)   // throws on stale
    }
    return promptsContextFromBuild(route.ide.build)
}
```

v4 changes the supplied-name branch to nullable `routeProject(...)` with
`Generic` fallback:

```kotlin
override fun buildPromptsContext(projectName: String?): PromptsContext {
    val route = if (projectName.isNullOrBlank()) {
        routing.singleRouteOrNull() ?: return PromptsContext.Generic
    } else {
        routing.routeProject(projectName) ?: return PromptsContext.Generic
    }
    return promptsContextFromBuild(route.ide.build)
}
```

**Why this is correct:**

* User typos a `project_name` at call time → FetchResource Spec's resolver
  emits `errorResult("Project not found: '<typo>'. Available: …")`. Never
  reaches `buildPromptsContext`.
* Genuine TOCTOU race (route disappears between resolver `Resolved` and
  `buildPromptsContext`) → user gets `Generic` articles. Rare, benign,
  matches existing IJ behavior when no project context is available.
* The throw path is eliminated, so `McpToolRegistry`'s stacktrace-wrapping
  catch never fires for this code path. The §2(5) uniform-error promise
  holds even under the race.

## 6. FetchResource as the 7th project-scoped tool

Today, `FetchResourceToolHandler` (poorly named — it IS the `McpTool`)
extracts `uri` + `project_name`, calls
`handler<PromptsContextHandler>().buildPromptsContext(projectName)`, and
filters articles by the resulting context.

**v4 rename + reshape:**

* Rename the class from `FetchResourceToolHandler` to `FetchResourceToolSpec`
  (matches the naming of every other `*ToolSpec`). Done in the FetchResource
  migration commit.
* Make it generic on `P` so it can receive a `ProjectResolver<P>` from
  `McpSteroidTools<P>.registerAll`:

  ```kotlin
  class FetchResourceToolSpec<P>(
      private val resolver: () -> ProjectResolver<P>,
      private val promptsContextHandler: () -> PromptsContextHandler,
  ) : McpTool { /* … */ }
  ```

* Its `call(...)` runs `resolver.resolve(projectName)`. On `NotFound`:
  `ToolCallResult.errorResult("Project not found: '${name}'. Available: …")` —
  same format as the 6 other project-scoped tools.
* On `Resolved`: continues with the existing
  `promptsContextHandler().buildPromptsContext(projectName)` call. The
  signature passes `String`, not `P`, so `PromptsContextHandler` itself
  needs no change. The TOCTOU race is handled by §5.3.

`PromptsContextHandler` callers other than FetchResource — found in
`ResourceRegistrar.kt:37`, `ResourceRegistrar.kt:95`, and
`SteroidsMcpServer.kt:227-239` — all pass `null`/no-project. None of them
exercise the supplied-name branch. The change in §5.3 has no effect on them.

## 7. Migration phasing (revised after r3)

### Phase 0 — land the abstraction (multi-module atomic commit)

This phase is one atomic commit because making `McpSteroidTools` generic on
`P` is a breaking source change to every subclass. Files touched:

* **`mcp-steroid-server`** — add `ProjectResolver`, `ProjectResolution`,
  `ProjectScope` (3 types, < 30 LoC). Make `McpSteroidTools` generic on `P`.
  Add `val resolver: ProjectResolver<P>` and `projectScopedHandler(...)`
  helper. Existing `registerAll` body **unchanged** — every `*ToolSpec(...)`
  constructor still takes the same one-lambda shape.
* **`ij-plugin`** — `McpSteroidToolsIJ` becomes
  `McpSteroidTools<Project>()`. Provides `val resolver = ProjectResolverIJ`.
  Adds the `ProjectResolverIJ` object. No handler signatures change.
* **`npx-kt`** — `StubMcpSteroidTools` becomes
  `McpSteroidTools<ProjectRoute>()`. Provides
  `val resolver = ProjectResolverDevrig(services.projectRouting)`. Adds the
  `ProjectResolverDevrig` class. No handler signatures change.
* **`npx-kt` tests** — `DevrigDescriptorParityTest.DirectDescriptorTools`
  becomes `McpSteroidTools<Unit>()` with
  `val resolver = ProjectResolver<Unit> { _ -> ProjectResolution.NotFound("", emptyList()) }`.
  Still only overrides `handler()` for `PromptsContextHandler`.

**Build green, all tests green.** No Spec invokes the new infrastructure.

### Phase 1..6 — per-tool atomic migration

In each commit, exactly one tool is migrated end-to-end:

1. Change the per-tool `*Handler` interface to `XxxToolHandler<P>` with the
   new `(ProjectScope<P>, params, [progress])` signature. (This is a
   breaking source change — but only one tool at a time.)
2. Change the `*ToolSpec` from a one-lambda constructor to the new
   `(resolver, handler)` two-lambda constructor.
3. Update `McpSteroidTools.registerAll`'s line for this tool to the new
   shape (`projectScopedHandler<XxxToolHandler<P>>(XxxToolHandler::class.java)`).
4. Update the IJ-side impl (`XxxToolHandlerIJ`) to the new signature.
5. Update the devrig-side impl (`DevrigXxxToolHandler`) to the new signature.
6. Update the per-tool integration test(s) — add an assertion that a stale
   `project_name` yields `ToolCallResult(isError=true)` with the
   `"Project not found: 'X'. Available: [...]"` format on both sides.

Recommended order (least to most invasive):
ExecuteFeedback → ActionDiscovery → VisionInput → ApplyPatch →
VisionScreenshot → ExecuteCode. Each is independent.

### Phase 7 — FetchResource migration

The largest commit in the migration. Specific file changes:

1. Rename `mcp-steroid-server/.../FetchResourceToolHandler.kt` →
   `FetchResourceToolSpec.kt`. Rename the class itself; update package import
   in any caller.
2. Change its constructor from one-lambda `(handler: () -> PromptsContextHandler)`
   to two-lambda `(resolver: () -> ProjectResolver<P>, promptsContextHandler: () -> PromptsContextHandler)`.
3. Move the `resolver.resolve(projectName)` step ahead of the
   `buildPromptsContext(projectName)` call. On `NotFound` emit
   `ToolCallResult.errorResult("Project not found: '${name}'. Available: …")`;
   on `Resolved` continue with the existing filter logic.
4. Switch devrig's `DevrigPromptsContextHandler.buildPromptsContext`
   supplied-name branch from `requireProject` to `routeProject` + `Generic`
   fallback (§5.3).
5. Update `McpSteroidTools.registerAll`'s FetchResource line.
6. **Test renames:**
   * `ij-plugin/src/test/.../FetchResourceToolTest.kt` — update class name
     in imports / `KClass` references / test names if any.
   * `ij-plugin/src/test/.../NoHardcodedMcpSteroidUriUsageTest.kt:41` —
     update the textual reference if any.
   * Any test file that imports `FetchResourceToolHandler` — update.
7. **New tests:** stale `project_name` on `steroid_fetch_resource` returns
   `ToolCallResult(isError=true)` with the
   `"Project not found: 'X'. Available: [...]"` shape on both IJ and devrig
   sides. The devrig test specifically covers `DevrigPromptsContextHandler`'s
   `Generic` fallback when called with a stale name directly (TOCTOU).

### Phase 8 — cleanup

* **`DevrigProjectRoutingService.requireProject`:** either
  * delete it outright (`DevrigProjectRoutingServiceTest:119-130` —
    the single remaining caller — switches to
    `routeProject(name) ?: error(...)` for the throw assertion), OR
  * `@Deprecated("Use routeProject(...) and handle the null case.",
    ReplaceWith("routeProject(exposedProjectName)"))` if we want to leave a
    breadcrumb. **Recommend: delete.** The test is the only caller; one
    line to flip; no API surface in the rest of the repo.
* Confirm `PromptsContextHandler.buildPromptsContext` no longer reaches a
  `throw` path under any production call site (FetchResource is gated by
  resolver; the `ResourceRegistrar` callers all pass null).
* Confirm there are no remaining `find { it.name == projectName }` blocks
  inside IJ handler implementations (grep across `ij-plugin/src/main`).
* Devrig-side comment cleanup: search for stale comments referencing
  `FetchResourceToolHandler` (the now-renamed class).

## 8. Resolved questions

### 8.1 Error mapping — `ToolCallResult.errorResult` universally

Wire shape unchanged (`isError=true`, HTTP 200). Message format:
`"Project not found: '<name>'. Available: [<comma-list>]"` on both sides.
Always emitted at the Spec resolver step. Devrig's `PromptsContextHandler`
no longer throws (§5.3); the registry's stacktrace-wrapping catch is
unreachable for this category.

### 8.2 FetchResource is the 7th project-scoped tool

Goes through the resolver like the other six. `PromptsContextHandler`
interface unchanged. Devrig's impl changes its supplied-name branch from
`requireProject` to nullable `routeProject` + `Generic` fallback to remove
the TOCTOU throw window.

### 8.3 `@Service` resolution with generics

Works via erasure. `plugin.xml` `serviceInterface` stays as the raw FQN.
`projectScopedHandler<H>(raw: Class<*>)` takes `Class<*>` because Kotlin
class literals for generic interfaces are star-projected — a
`Class<out H>` upper bound would refuse the actual call sites. The helper
carries one localised `@Suppress("UNCHECKED_CAST")` inside; call sites
pass `XxxHandler::class.java` (a `Class<XxxHandler<*>>`) and supply the
expected `<H>` explicitly. Type-safety: low at the helper boundary, OK
at the call sites because each `XxxHandler::class.java`/`<XxxHandler<P>>`
pair is co-located in the `registerAll` body and rarely changes.

### 8.4 `fun interface` arity

Kotlin's `fun interface` SAM rule only constrains the abstract method
count (one), not arity. Three-arg `handle(scope, params, progress)` is
fine. No default arguments are introduced anywhere.

### 8.5 `McpProgressReporter` placement

Third positional argument on the two handlers that need it (ExecuteCode,
VisionScreenshot). Not a member of `ProjectScope`.

### 8.6 Test scaffolding

`DevrigDescriptorParityTest.DirectDescriptorTools` becomes
`McpSteroidTools<Unit>()`. It overrides:

* `val resolver: ProjectResolver<Unit>` → a no-op resolver that always
  returns `ProjectResolution.NotFound("", emptyList())`. Never reached
  because the test enumerates descriptors only; the stored
  `() -> Handler` lambdas in each Spec are never invoked during
  descriptor enumeration.
* `handler()` → unchanged from today (errors on anything other than
  `PromptsContextHandler`).

### 8.7 No shared parent for the handler interfaces

Rejected in r2 — `ProjectScopedHandler<P, PARAMS, OUT>` can't be implemented
by 3-arg handlers without splitting into a second parallel interface. v4
keeps it dropped. Each per-tool handler is a standalone `fun interface`.

### 8.8 No mixed-mode `registerAll`

v3's "two overloads per Spec during the migration window" is rejected as
unfeasible — adding `XxxToolHandler<P>` while preserving the non-generic
`XxxToolHandler` collides on the FQN. v4's Phase 0 leaves Specs alone;
each per-tool migration is one atomic commit.

### 8.9 `handler<T>()` helper split

v4 keeps the existing `inline fun <reified T : Any> handler(): T` for
non-generic interfaces (`ListProjectsToolHandler`, `OpenProjectToolHandler`,
`PromptsContextHandler`, etc.). It adds
`projectScopedHandler<H>(raw: Class<*>)` for the generic ones — see §8.3
for why `Class<*>` rather than `Class<out H>`. The outer `P` is never used
as a reified type argument.

### 8.10 No `FetchResourceToolHandler<P>` interface

Rejected in r3 — empty `fun interface` is invalid Kotlin and the existing
`FetchResourceToolHandler` class IS the `McpTool`. v4 renames it to
`FetchResourceToolSpec` (Phase 7); the class itself takes `P` as a class
type parameter only to thread `ProjectResolver<P>`. There is no
per-tool handler interface for FetchResource.

## 9. What "decent" means here

* Each MCP tool's user-visible behaviour is unchanged except for the
  improved not-found error message on devrig.
* Each project-scoped handler interface shrinks from
  `(String, X, …) -> Y` to `(ProjectScope<P>, X, [progress?]) -> Y`. No
  SAM conflicts.
* Each side's project resolution lives in **one** place
  (`ProjectResolverIJ`, `ProjectResolverDevrig`).
* The shared `mcp-steroid-server` code knows nothing about IntelliJ-platform
  types or about devrig.
* The IJ-plugin side sheds 6× copies of the `find { it.name == projectName }`
  block plus a drifting set of error strings.
* The devrig side gains type-safety on the project handle (no more
  stringly-typed `requireProject(projectName)` inside handlers).
* Each migration commit is atomic — no flag day, no half-migrated state.

## 10. Open questions / outstanding from r5

**Design substance: settled** across 5 rounds × 5 reviewers (codex × 3,
claude × 2 per round). The five rounds caught and fixed:

* r1: §7 `P = Unit` interim was impossible.
* r2: `decodeArgs` was fictional; `ProjectScopedHandler` couldn't be a
  shared parent.
* r3: mixed-mode `registerAll` was impossible; `handler<X<P>>()` reified
  problem; FetchResource needed a concrete rename plan.
* r4: §4 ExecuteCode sketch ctor wrong; devrig forwarding invariant
  (`originalProjectName`) missing; Phase 0 understated multi-module scope;
  Phase 8 wishy-washy.
* r5: `Class<out H>` doesn't compile when called with `XxxHandler::class.java`
  (a `Class<XxxHandler<*>>`); §5.1/§5.2/§8.6 still had `override fun resolver()`
  instead of `val`; sketch invented `cancel_on_modal` decode that isn't in
  the real Spec.

**v5-final patches** (this document) close every r5 issue in-place — see the
preamble. The remaining trade-off for reviewers of the eventual
implementation:

* The `projectScopedHandler` helper carries one suppressed cast inside.
  Per-call-site type safety relies on the `XxxHandler::class.java` and
  `<XxxHandler<P>>` pair being co-located in `registerAll`. If implementers
  want stricter safety, the alternative is a per-tool typed factory on
  each `*ToolSpec` — at the cost of N more typed methods on
  `McpSteroidTools<P>`.

Anything found during implementation that contradicts the design lands
back here as a follow-up.
