IntelliJ API Power User Guide

RECOMMENDED: Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to IntelliJ Platform APIs.


# MCP Steroid - IDE API Access for AI Agents

Execute Kotlin code directly in IntelliJ IDEA's runtime with full access to the IntelliJ Platform API.

## Important Notes for AI Agents

**Learning Curve**: Writing working code for IntelliJ APIs may require several attempts. This is normal! The API is vast and powerful. Keep trying - each attempt teaches you more about the available APIs. Use `printException()` to see stack traces when errors occur.

**Drop-in replacement for LSP**: This MCP server replaces LSP (Language Server Protocol) tools with IntelliJ's native APIs — same operations, deeper understanding:
- PSI (Program Structure Interface) instead of LSP document symbols — full semantic analysis
- IntelliJ inspections, refactorings, intentions instead of LSP code actions
- Full project model with module dependencies instead of workspace folders
- Platform-specific indices for O(1) code search instead of filesystem scans

## Common task → resource cheat sheet

Before reading further, if your task matches one of these, skip straight to the linked recipe:

| Task | Fetch this |
|---|---|
| Find duplicate / cloned / DRY-violation / copy-paste code | `mcp-steroid://ide/find-duplicates` |
| Run a single named inspection + apply quick-fix | `mcp-steroid://ide/inspect-and-fix` |
| List enabled inspections in the project | `mcp-steroid://ide/inspection-summary` |
| Multi-file literal-text edit | one `steroid_execute_code` script: read + replace + save all files in a single `writeAction { }` |
| Find usages of a symbol | `mcp-steroid://lsp/find-references` |
| Run / debug a test | `mcp-steroid://ide/demo-debug-test` |
| Run Maven / Gradle tests | `mcp-steroid://skill/execute-code-maven`, `mcp-steroid://skill/execute-code-gradle` |
| API discovery / exploration | continue reading this guide |

The full index is in the "MCP Resources (Use Them)" section below.

## Quickstart Flow

```
1. steroid_list_projects → get list of open projects
2. Pick the `project_name` of the one you want (the unique routing key — NOT the human-readable `name`)
3. steroid_execute_code → run Kotlin code with that project_name
4. steroid_execute_feedback → report success/failure for tracking
```

Each `steroid_list_projects` entry has TWO name fields: `project_name` (the within-IDE-unique,
opaque routing KEY you pass back to every project-scoped tool) and `name` (the human-readable
folder name, informational/display only). Always route by `project_name`. To find the right
project for a file or directory path, pick the project whose `path` is the longest prefix of your
target path (this disambiguates nested checkouts and git worktrees).

**Example session:**
```
→ steroid_list_projects
← {"projects":[{"project_name":"my-app-9fk2a0xq","name":"my-app","path":"/path/to/my-app","backend_name":"iu-9fk2a0xq"}],
   "backends":[{"backend_name":"iu-9fk2a0xq","intellij":{"name":"IntelliJ IDEA 2026.1.3","version":"2026.1.3","build":"IU-261.25134.95"}}]}

→ steroid_execute_code(project_name="my-app-9fk2a0xq", code="println(project.name)", ...)
← "my-app"

→ steroid_execute_feedback(project_name="my-app-9fk2a0xq", task_id="...", execution_id="...", success_rating=1.0, explanation="Got project name")
```

## When to Use This Skill

**ALWAYS prefer IntelliJ APIs over file-based operations:**

| Instead of...                   | Use IntelliJ API               |
|---------------------------------|--------------------------------|
| Reading files with `cat`/`read` | VFS and PSI APIs               |
| Searching with `grep`/`find`    | Find Usages, Structural Search |
| Manual text replacement         | Automated refactorings         |
| Guessing code structure         | Query project model directly   |

The IDE has indexed everything. It knows the code better than any file search.

## Available Tools

### `steroid_list_projects`
List all open projects. Each entry has `project_name` (the unique routing key to pass to
`steroid_execute_code` and the other project-scoped tools), `name` (the human-readable folder
name, informational only), and `path`. To map a file/dir path to a project, pick the project whose
`path` is the longest prefix of your target path.

### `steroid_list_windows`
List open IDE windows (and background tasks) and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the correct `window_id` for screenshot/input tools. Each
window and task entry references its project by `project_name` — the single routing key. Look up that
project's human-readable `name` and `path` via `steroid_list_projects` by that key (they are not
duplicated on window/task entries).

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): the `project_name` from `steroid_list_projects` (a unique routing key, NOT the raw folder name)
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

**Artifacts (saved under the execution folder):**
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

The response includes `window_id` (also stored in `screenshot-meta.json`); pass it to `steroid_input` to target the same window. `window_id` is also returned by `steroid_list_windows`.

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

**Parameters:**
- `project_name` (required): the `project_name` from `steroid_list_projects` (a unique routing key, NOT the raw folder name)
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `window_id` (required): Window id from `steroid_list_windows` (also returned by `steroid_take_screenshot`)
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

**Sequence examples:**
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

**Notes:**
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.

### `steroid_execute_code`
**Execute code with IntelliJ's brain, not just text files.**

Give your AI agent a senior developer's toolkit: semantic code understanding, automated refactorings, and IDE intelligence that LSP can't provide.

**Parameters:** `project_name`, `code` (Kotlin suspend function body), `task_id`, `reason`, `timeout` (optional)

**Returns:** `execution_id` plus ONLY what the script explicitly prints (`println` / `printJson` /
`printCsv` / `printToon`). The last expression's value is ignored by the runtime — a script that
computes but never prints returns no data, just a `HINT:` about the missing print.

**Complete guide:** `mcp-steroid://skill/coding-with-intellij` (API reference, patterns, examples, best practices)

### `steroid_execute_feedback`
Rate execution results. Use after `steroid_execute_code`.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: This tool does NOT wait for the project to fully open. The project opening process may show dialogs (such as "Trust Project", project type selection, etc.) that require interaction. Use `steroid_take_screenshot` and `steroid_input` tools to interact with any dialogs that appear.

**Parameters:**
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: true

**Workflow:**
1. Call `steroid_open_project` with the project path
2. If `trust_project=true`, the project will be trusted automatically (no trust dialog)
3. Call `steroid_take_screenshot` to see the current IDE state
4. If dialogs are shown, use `steroid_input` to interact with them
5. Call `steroid_list_projects` to verify the project is open

## MCP Resources (Use Them)

This server exposes built-in resources through the MCP resource APIs. These are the fastest way to load full examples and guides without guessing or copy/pasting from the web.

**How to access resources:**
1. Call `steroid_fetch_resource` with a `mcp-steroid://` URI to load the content.
2. Or use `list_mcp_resources` to browse all available resources.

**Key resources provided by this server:**
- `mcp-steroid://prompt/skill` - This guide as a resource.
- `mcp-steroid://skill/coding-with-intellij` - Comprehensive guide for writing IntelliJ API code (execution model, patterns, examples).
- `mcp-steroid://prompt/debugger-skill` - Debugger-focused skill guide (breakpoints, sessions, threads).
- `mcp-steroid://lsp/overview` - Overview of LSP-like examples and how to use them.
- `mcp-steroid://lsp/<id>` - Runnable Kotlin scripts (e.g., `go-to-definition`, `find-references`, `rename`, `code-action`, `signature-help`).
- `mcp-steroid://ide/overview` - Overview of IDE power operation examples (refactorings, inspections, generation).
- `mcp-steroid://ide/<id>` - Runnable Kotlin scripts (e.g., `extract-method`, `introduce-variable`, `change-signature`, `safe-delete`, `optimize-imports`, `pull-up-members`, `push-down-members`, `extract-interface`, `move-class`, `generate-constructor`, `call-hierarchy`, `project-dependencies`, `inspect-and-fix`, `inspection-summary`, `find-duplicates`, `project-search`, `run-configuration`).
- `mcp-steroid://debugger/overview` - Overview of debugger examples (breakpoints, sessions, threads).
- `mcp-steroid://debugger/<id>` - Runnable Kotlin scripts (e.g., `set-line-breakpoint`, `debug-run-configuration`, `debug-session-control`, `debug-list-threads`, `debug-thread-dump`).
- `mcp-steroid://open-project/overview` - Guide for opening projects via MCP.
- `mcp-steroid://open-project/<id>` - Project opening examples (e.g., `open-trusted`, `open-with-dialogs`, `open-via-code`).

These resources are designed to be plugged directly into `steroid_execute_code` after you configure file paths/positions.

## Critical Rules

These are the essential rules you must follow. For detailed examples and patterns, read `mcp-steroid://skill/coding-with-intellij`.

### 1. Script Body is a SUSPEND Function
```kotlin
// This is a coroutine - use suspend APIs!
// Under the default modal=smart_non_modal, waitForSmartMode() runs automatically before your script.
delay(1000)         // coroutine delay - works directly
```
**NEVER use `runBlocking`** - it causes deadlocks.

**NEVER re-probe `waitForSmartMode()` before every operation.** The automatic wait (under the default
`modal=smart_non_modal`; skipped under `non_modal` / `unleashed`) before script start is only a
point-in-time check; IntelliJ may enter dumb mode again before the next statement.
For index-dependent PSI queries, wrap the whole query in `smartReadAction { }`. After project
open/import/sync/configuration, first await `Observation.awaitConfiguration(project)`, then use
`smartReadAction { }`.

### 2. Imports Are Optional

Default imports are provided automatically. Add imports only when you need APIs outside the defaults.
Imports must be at the top of the script, never after code.

### 3. Read/Write Actions for PSI/VFS

> **THREADING RULE — NEVER SKIP**: Any PSI access **MUST** be inside `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately.

**Built-in helpers (no imports needed):**
```kotlin
// Reading PSI/VFS/indices
val data = readAction { project.name }

// Modifying PSI/VFS/documents
writeAction { /* modifications here */ }

// Runs index-dependent PSI work under IntelliJ's smart-mode read constraint
val smart = smartReadAction { /* PSI operations */ }
```

For detailed threading patterns, see `mcp-steroid://skill/coding-with-intellij-threading`.

### 4. Context API

Built-in helpers available in every script (no imports needed):

| Category | APIs |
|----------|------|
| **Properties** | `project`, `disposable`, `isDisposed` |
| **Output (prose / JSON)** | `println()`, `printJson()`, `progress()`, `printException()` |
| **Output (token-efficient tabular)** | `printCsv(headers, rows, dictColumns)` — CSV with optional path-dictionary preamble; `printToon(records)` — TOON (array-of-records) form |
| **Read/Write** | `readAction { }`, `writeAction { }`, `smartReadAction { }` |
| **Scopes** | `projectScope()`, `allScope()` |
| **File access** | `findFile()`, `findPsiFile()`, `findProjectFile()`, `findProjectFiles("src/main/**/*.kt")`, `findProjectPsiFile()` |
| **Analysis** | `runInspectionsDirectly()` |

The output methods are the only way to get data back to the agent — the script's
last-expression value is ignored by the runtime, so print everything you need.

**Tabular output cheat sheet** — for find-references, call-hierarchy, project-search, document-symbols, or any flat array-of-records result. Signatures are different on purpose; `printCsv` takes parallel lists (positional), `printToon` takes a list of maps (keyed). Mixing them up is the #1 first-try compile error.

```kotlin
// CSV — printCsv(headers: List<String>, rows: Iterable<List<Any?>>, dictColumns: Set<String> = emptySet())
// Best when one column has repeated long values (absolute paths, FQNs).
// `dictColumns` emits a per-column @col: preamble and replaces each cell with a short ID (`p1`, `p2`, ...).
printCsv(
    headers = listOf("idx", "path", "line"),
    rows = emptyList<List<Any?>>(),
    dictColumns = setOf("path"),
)

// TOON — Token-Oriented Object Notation; https://github.com/toon-format/toon.
// printToon(value: Any?) — drop-in for printJson on uniform-shape lists.
// Pass List<Map<String, Any?>>; column order comes from the FIRST map's keys, and every
// subsequent map must have the same key set. Do NOT pass headers / rows / dictColumns — that's printCsv.
printToon(listOf(mapOf("path" to "/abs/A.kt", "line" to 17), mapOf("path" to "/abs/B.kt", "line" to 42)))
```

**Same records, both formats** — most recipes finish by emitting one list of records twice:

```kotlin
val records: List<Triple<String, Int, String>> = emptyList()  // built once
printCsv(
    headers = listOf("idx", "path", "line", "snippet"),
    rows = records.mapIndexed { i, (p, l, s) -> listOf(i + 1, p, l, s) },
    dictColumns = setOf("path"),
)
printToon(records.map { (p, l, s) -> mapOf("path" to p, "line" to l, "snippet" to s) })
```

Full API reference with literal sample outputs and an end-to-end example:
`mcp-steroid://skill/coding-with-intellij-context-api` → "Tabular Output".

### 5. Running Inspections

The IDE has hundreds of inspections — `DuplicatedCode`, `RedundantCast`, `UnusedDeclaration`, language-specific DFA, etc. Two paths from a script:

| You want to… | Use |
|---|---|
| Run **all enabled** inspections on a file (warnings/errors style) | `runInspectionsDirectly(file)` — context-API helper, behaves like `Map<toolId, List<ProblemDescriptor>>` and also exposes `failedTools`. Works regardless of window focus. |
| Run **one named** inspection (e.g. `DuplicatedCode`) on a file | Construct the inspection class directly and pass it to `InspectionEngine.inspectEx(...)` via a `LocalInspectionToolWrapper`. See the `inspect-and-fix` and `find-duplicates` recipes. |
| List which inspections are enabled (to know what's available) | `mcp-steroid://ide/inspection-summary` |
| Find duplicate code clusters across the project | `mcp-steroid://ide/find-duplicates` (typed `DuplicateProblemDescriptor.textClone`, no reflection) |

**Pitfall — do not `printJson(result)` from `runInspectionsDirectly`.** The result is Map-compatible for legacy callers, but each `ProblemDescriptor` carries live PSI/VFS objects and can recurse through Jackson. Snapshot the descriptors under a read action, include `failedTools`, and compute an explicit status:

```kotlin
val vf = findProjectFile("src/main/java/com/example/Foo.java") ?: error("file not found")
val result = runInspectionsDirectly(vf)

val findings = readAction {
    result.entries.flatMap { (toolId, descriptors) ->
        descriptors.map { descriptor ->
            mapOf(
                "toolId" to toolId,
                "message" to descriptor.descriptionTemplate,
                "elementText" to (descriptor.psiElement?.text ?: "")
            )
        }
    }
}

val status = when {
    result.failedTools.isNotEmpty() -> "check_failed"
    findings.isNotEmpty() -> "findings"
    else -> "clean"
}

printJson(mapOf("status" to status, "findings" to findings, "failedTools" to result.failedTools))
```

`failedTools` is the guard against false clean results: when the sweep cannot run for an input file, or an inspection tool crashes, the findings map may be empty but `failedTools` is non-empty.

### 6. Running Tests

**Always prefer the IntelliJ IDE runner over `./mvnw test` or `./gradlew test`.**
The IDE runner returns a simple exit code (0 = all passed), shows structured results, and reuses the running JVM.

See `mcp-steroid://skill/coding-with-intellij` → **"Run Tests via IntelliJ IDE Runner"** for the complete pattern.

Only fall back to CLI test commands when the IDE runner cannot be used. Even then, **never print the full output** — always `take(30) + takeLast(30)` to avoid MCP token limit errors.

## Error Handling

Use `printException` for errors - it includes the stack trace in the output:

```kotlin
try {
    // risky operation
} catch (e: Exception) {
    printException("Operation failed", e)
}
```

## Troubleshooting

### Check if Server is Running
The MCP server runs inside IntelliJ. To verify:
1. Open IntelliJ IDEA with the MCP Steroid plugin installed
2. Open any project
3. Check `.idea/mcp-steroid.md` in the project folder for the server URL
4. The server port is configurable via `mcp.steroid.server.port`; read `.idea/mcp-steroid.md` for the active URL

### Endpoints
- `/` - Returns this SKILL.md content
- `/skill.md` - Same as above
- `/mcp` - MCP protocol endpoint for tool calls
- `/.well-known/mcp.json` - MCP server discovery

### Fetching mcp-steroid:// articles (preferred)
Use the `steroid_fetch_resource` MCP tool (it requires `project_name`
for correct IDE-conditional rendering) instead of HTTP fetching or
`ReadMcpResourceTool`. The articles are NOT exposed via `resources/list`
or `prompts/list` — the tool is the canonical discovery surface.

### Common Issues
- **"Project not found"** - Run `steroid_list_projects` first to get exact project names
- **No output from execute** - Only printed values come back; the last expression's value is ignored by the runtime. End the script with `println()` / `printJson()` of everything you need
- **Timeout** - Increase `timeout` parameter (default 60 seconds)
- **Script errors** - Check Kotlin syntax; imports are optional

## Detailed Guides

For API examples, patterns, and in-depth coverage, read the dedicated articles:

| Topic | Resource |
|-------|----------|
| **Full guide (start here)** | `mcp-steroid://skill/coding-with-intellij` |
| **Execution model & script structure** | `mcp-steroid://skill/coding-with-intellij-intro` |
| **PSI operations & code analysis** | `mcp-steroid://skill/coding-with-intellij-psi` |
| **Document, editor & VFS operations** | `mcp-steroid://skill/coding-with-intellij-vfs` |
| **Threading & read/write actions** | `mcp-steroid://skill/coding-with-intellij-threading` |
| **Common patterns & project info** | `mcp-steroid://skill/coding-with-intellij-patterns` |
| **Refactoring, completion & services** | `mcp-steroid://skill/coding-with-intellij-refactoring` |
| **McpScriptContext API reference** | `mcp-steroid://skill/coding-with-intellij-context-api` |
| **Java & Spring Boot patterns** | `mcp-steroid://skill/coding-with-intellij-spring` |

### Other Resources
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows and stateful execution
- [Test Runner Guide](mcp-steroid://prompt/test-skill) - Test execution patterns
- [LSP Examples](mcp-steroid://lsp/overview) - LSP-like operations (navigation, code intelligence, refactoring)
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations (refactorings, inspections, generation)
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugger workflows and API usage
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations (git blame, history)
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows

---

**This is like LSP, but more powerful.** IntelliJ APIs offer deeper code understanding and more features than standard LSP. Don't settle for file-level operations when you have IDE-level access.
