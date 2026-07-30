Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

## Multi-site edits: one script, one write action

For similar edits across **two or more files** (same pattern, different paths), do them in ONE
`steroid_execute_code` call — read, replace, save each file, all writes inside a single
`writeAction { }`:

```kotlin
val edits = listOf(
    Triple("/abs/path/A.java", "oldA", "newA"),
    Triple("/abs/path/A.java", "oldA2", "newA2"),  // same-file edits are folded, in order
    Triple("/abs/path/B.java", "oldB", "newB"),
)
val resolved = edits.groupBy { it.first }.map { (path, fileEdits) ->
    val vf = findProjectFile(path) ?: error("not found: $path")
    var content = String(vf.contentsToByteArray(), vf.charset)
    for ((_, old, new) in fileEdits) {   // each edit sees the previous one's result
        val occurrences = content.split(old).size - 1
        check(occurrences == 1) { "$path: anchor occurs $occurrences times — expand it with surrounding context" }
        content = content.replace(old, new)
    }
    vf to content
}
writeAction { resolved.forEach { (vf, updated) -> VfsUtil.saveText(vf, updated) } }
println("edited: " + resolved.joinToString { it.first.path })
```

The pre-check loop validates every match before any write lands; `VfsUtil.saveText` keeps
VFS + PSI consistent; native `Edit` chains bypass the VFS and cost one tool call per site.

Escape hatch for COMPLEX changes only (an existing unified diff, or drifted files where
literal anchors keep failing): the IDE's tolerance-matching patch engine — fetch
`mcp-steroid://ide/apply-unified-diff`. This recipe stays primary.

## Decision tree — pick the IDE path before reaching for a native tool

| Task shape | One-line IDE call |
|---|---|
| **Two or more literal-text edits, same or different files** | one `steroid_execute_code` script: read + `replace` each file, pre-check every match, then save all inside a single `writeAction { }` (see "Multi-site edits" above) |
| **One literal-text edit, single file** | `val vf = findProjectFile(p) ?: error("not found: $p"); writeAction { VfsUtil.saveText(vf, String(vf.contentsToByteArray(), vf.charset).replace(OLD, NEW)) }` |
| **Find files by extension** | `readAction { FilenameIndex.getAllFilesByExt(project, "java", projectScope()) }` — not `Bash find … -name "*.java"` |
| **Find files by exact name** | `readAction { FilenameIndex.getVirtualFilesByName("UserService.java", projectScope()) }` |
| **Find all references to a symbol** | `readAction { ReferencesSearch.search(psiElement, projectScope()).findAll() }` — type-aware; Grep over source text is a fallback |
| **Read file content (any size)** | `String((findProjectFile(p) ?: error("not found: $p")).contentsToByteArray(), charset)` — accepts relative or absolute paths, always re-reads from disk when called at the script top level; the next semantic query sees what you read |
| **Grep content inside project files** | `readAction { FilenameIndex.getAllFilesByExt(project, ext, scope).flatMap { vf -> Regex(pat).findAll(String(vf.contentsToByteArray(), vf.charset)) … } }` in ONE call |
| **Run Maven / Gradle tests** | IDE runner — see `mcp-steroid://skill/execute-code-maven` and `mcp-steroid://skill/execute-code-gradle`; Bash is only for shell-level final verification or IDE-runner fallback |
| **IDE build aborted (`errors=false, aborted=true`)** | Fetch `mcp-steroid://skill/execute-code-gradle` or `mcp-steroid://skill/execute-code-maven` and run the matching sync pattern before Bash fallback. |
| **Verify after an edit** | Fetch `mcp-steroid://ide/verify-after-edit` first. For JPS/non-delegated builds use `mcp-steroid://ide/jps-build-errors`; do not treat an empty diagnostics list as clean unless `check_ran=true`. |
| **Find duplicate / cloned code across the project (DRY violations, copy-paste)** | **Fetch `mcp-steroid://ide/find-duplicates` FIRST — duplicate-code detection is an IDE/PSI task, not a text-search task; do not start with `grep` / `Bash` / `rg`.** The article's **Primary recipe (PSI body comparison)** is the default — exact-body duplicates for Kotlin/Java, runs in fresh sessions / CI / test environments with no warm-index prerequisite. The Cross-check recipe (bundled `DuplicatedCode` inspection: `com.jetbrains.clones.DuplicateInspection`) is OPTIONAL — only when the user explicitly wants near-duplicate / parameterized-clone detection AND the project's `HashFragmentIndex` is known to be warm. **`CLUSTERS_FOUND: 0` from the Cross-check alone is ambiguous** — it does not mean "no duplicates exist" until the Primary recipe has also run. No private-field reflection in either path. |
| **Run a single named inspection on a file (with quick-fix)** | Fetch `mcp-steroid://ide/inspect-and-fix`. For *all enabled* inspections, use the context-API helper `runInspectionsDirectly(file)` directly. |
| **Tabular output (array of records — find-references, call-hierarchy, project-search, document-symbols)** | `printCsv(headers: List<String>, rows: Iterable<List<Any?>>, dictColumns: Set<String> = emptySet())` — CSV with optional path-dictionary preamble. **OR** `printToon(value: Any?)` — TOON array-of-records (Token-Oriented Object Notation). **Signatures differ**: `printCsv` wants parallel `List<List<Any?>>` rows; `printToon` wants `List<Map<String, Any?>>` and infers column order from the first map. Do not pass `List<Map>` to `printCsv` (common compile error). |
| **Git / Docker CLI / shell** | native `Bash` — genuinely outside the IDE |

If your next instinct is a native `Read` / `Edit` / `Grep` / `Glob` / `Bash` call, check this table first. The IDE path keeps VFS + PSI consistent, reuses the warm JVM, and one call reliably replaces 3-5 chained native-tool calls.

**Before your first call, read the guide for your task** with `steroid_fetch_resource`:
- Building/testing → `mcp-steroid://prompt/test-skill`
- Debugging → `mcp-steroid://prompt/debugger-skill`
- Any IDE task → `mcp-steroid://prompt/skill`

**Quick Start:**
- Your code becomes the body of a `suspend McpScriptContext.() -> Unit` function (never use runBlocking)
- **Apart from the `execution_id:` header, the response contains ONLY what your script explicitly
  prints.** The last expression's value is IGNORED by the runtime — this is a function body, not a
  REPL, and there is no implicit return value. Print everything the caller needs (`println` /
  `printJson` / `printCsv` / `printToon`) before the script ends. Full rules in "Output rules" below.
- With the default `modal=smart_non_modal`, leftover modal dialogs are closed, the IDE is required
  non-modal, documents are committed/saved + VFS refreshed, and `waitForSmartMode()` runs — all before
  your script; then a monitor watches the run and **closes any modal that appears mid-script and FAILS the
  call** (call `allowModalDialog()` first if you open one on purpose). See "Modality (the `modal` option)" below.
- **Available in scope** (no imports needed): `project`, `readAction`, `writeAction`, `smartReadAction`, `writeIntentReadAction`, `findFile`, `findProjectFile`, `findProjectFiles`, `findPsiFile`, `findProjectPsiFile`, `runInspectionsDirectly`, `projectScope`, `allScope`, `waitForSmartMode`, `closeModalDialogs`, `monitorAndCloseModalDialogs`, `allowModalDialog`, `syncDocuments`, `println`, `printJson`, `printCsv`, `printToon`, `progress`, `printException`, `takeIdeScreenshot`, `disposable`. For tabular results (find-references, call-hierarchy, project-search, document-symbols) prefer `printCsv(headers, rows, dictColumns = setOf("path"))` over `printJson` — the `dictColumns` preamble dedupes repeated paths and the format is ~60% cheaper than the equivalent prose.
- **Use `project` directly** — not `context.project` (no `context.` prefix exists).
- **Do not invent helpers.** `buildProject()`, `compileProject()`, `createProjectFile()`, `projectDir`, `findProjectDir()`, top-level `readText(vf)` do not exist. For build use `ProjectTaskManager.getInstance(project).buildAllModules().await()` (needs `import com.intellij.task.ProjectTaskManager` + `import org.jetbrains.concurrency.await`); for new files create+write inside one `writeAction` using `VfsUtil.createDirectoryIfMissing` + `dir.createChildData` + `VfsUtil.saveText`; for the project root use `project.basePath` or `project.guessProjectDir()`; for file content use `String(vf.contentsToByteArray(), vf.charset)`. Full table: `mcp-steroid://skill/coding-with-intellij-context-api` → "Real helpers vs invented names".
- **Do not call daemon-highlighting internals** (`DaemonCodeAnalyzerImpl`, `DaemonProgressIndicator`, `HighlightingSession`) — they require state that does not exist in a script context. For inspection diagnostics use `runInspectionsDirectly(file)` or `mcp-steroid://ide/inspect-and-fix`.

## Modality (the `modal` option)

`modal` is a single enum that sets how the IDE's modal state is handled around your script. Default
`smart_non_modal` is right for almost everything (any PSI / editing / build / test work). Everything finer
is available as context methods you can call from any mode.

| `modal` | What it does | Use it for |
|---|---|---|
| `smart_non_modal` *(default)* | Close leftover modal dialogs (deepest-first), require a non-modal IDE (the call **fails with a screenshot + thread dump** if a modal survives), commit + save documents, refresh the VFS, wait for indexing (point-in-time — index-dependent reads still need `smartReadAction { }`) — then run, with a monitor that **closes any modal dialog that appears mid-run and fails the call** (thread dump + screenshot captured). If your script opens a dialog **on purpose**, call `allowModalDialog()` first so the monitor leaves it alone. Also re-syncs documents post-flight (when still non-modal). | PSI / code-editing / build / test scripts — **and read-only navigation** — the safe default. |
| `non_modal` | Require a non-modal IDE **at the start** (fail with a screenshot if modal); do **nothing** else — no sweep, no commit, no indexing wait, and **no during-run monitor** (modals appearing later are ignored unless you call `monitorAndCloseModalDialogs()`). **Not sufficient for PSI/editing** unless you call `syncDocuments()` / `waitForSmartMode()` yourself. | A non-PSI read that only needs a stable non-modal start — e.g. reading run-configuration or VCS-status state — where the default's commit + smart-mode wait would be wasted work. |
| `unleashed` | No sweep, no checks, no validation — run against whatever IDE state exists, modal dialogs included. | **Intentional modal-dialog workflows** (open / inspect / screenshot / close a dialog yourself) and trivial / hardcoded IDE actions. NOT for PSI or code-editing flows (no consistency guarantees). |

Context methods (callable from any mode — the profiles above are just sugar over these):

- `closeModalDialogs(): Int` — close all showing modal dialogs (deepest-first), capturing a screenshot +
  thread dump first; returns how many were closed. Does **not** fail the call.
- `monitorAndCloseModalDialogs()` — poll (~1s) for showing modal dialogs for the rest of the run; a modal
  still showing at a poll tick is closed and the call **fails** (screenshot + thread dump captured). It does
  **not** sweep dialogs already on screen — call `closeModalDialogs()` first for those. No-op if already
  active. `smart_non_modal` starts this for you.
- `allowModalDialog()` — **disable** that watcher for the **rest of the run** (it does not auto-resume) so a
  dialog your script opens **on purpose** is left alone; call it just before opening the dialog, and call
  `monitorAndCloseModalDialogs()` again to re-arm.
- `syncDocuments()` — commit PSI + save documents + refresh VFS; asserts non-modal (fails on a modal).
- `waitForSmartMode()` — wait for indexing; asserts non-modal (fails on a modal **or** if its
  deadlock-safety timeout is reached). Point-in-time only — still use `smartReadAction { }` for
  index-dependent reads, and `Observation.awaitConfiguration(project)` after a Gradle/Maven sync (the wait
  is dumb→smart-mode only, it does not await external-system configuration).

There is intentionally **no "close a mid-run dialog and keep going" mode** — `smart_non_modal` closes it and
fails. If a script must tolerate dialogs popping up while it runs, use `unleashed` and call
`closeModalDialogs()` yourself when needed (and accept no PSI-consistency guarantees).

When a call fails on a modal (gate or monitor), the screenshot + thread dump are written to the execution's
storage folder and their paths appear in the result text — read those before retrying. A separate
`steroid_take_screenshot` captures *current* state, not the failure state. Also note: under
`smart_non_modal` the call can FAIL **before your script body runs** (gate fail, or the bounded commit /
smart-mode pre-flight hitting its deadlock-safety timeout) — that's documented behavior, not a bug in your
Kotlin, so inspect the captured diagnostics first.

**Surface is fixed.** `McpScriptContext` won't grow new helpers — call IntelliJ APIs directly. See `mcp-steroid://skill/design-philosophy` Tenet 3.

**Output rules — the #1 reason agents think a call "returned empty":**
- **Everything you need back must be explicitly printed.** The last expression's value is IGNORED
  by the runtime — never auto-printed, never returned (the code compiles as a suspend function
  body, not a REPL). `return` only exits early; `return <value>` does not even compile (the
  generated function returns `Unit`), so nothing can be carried back to the caller.
- To surface anything to the caller, use a print helper: `println(value)` for plain text,
  `printJson(value)` for structured data, `printCsv(...)` / `printToon(...)` for tabular records.
- `progress(...)` is NOT output — it goes to MCP progress notifications and the IDE log, never
  into the result. The print-only rule describes successful runs; a failed run additionally
  carries the error text and diagnostic file paths (screenshot / thread dump).
- A script that ends with `myList` (or any bare expression) prints nothing — you will see only `execution_id: …` in the response, identical to a script that returned no value at all. Always end with an explicit `println(...)` or `printJson(...)` of what the agent needs to see.
- **For inspection / report tasks, print compact machine-readable lines on the first run.** Stable shapes like `KEY: value` per line or `printJson` parse cheaply on your end and let you build the user-facing summary without a second exec_code pass to reshape verbose IDE output. Recipes in `mcp-steroid://ide/find-duplicates`, `…/inspect-and-fix`, `…/inspection-summary` already follow this convention.
- **For `runInspectionsDirectly`, do not `printJson(result)` directly.** It is Map-compatible and contains live `ProblemDescriptor` PSI/VFS references. Snapshot descriptor fields inside `readAction { }`, print a DTO, and always include `result.failedTools`; a non-empty `failedTools` means the check is not clean even when the findings map is empty.

**Threading rules — apply preventively, not after an error:**

The wrap is required on EVERY new script — the IDE forgets the previous script's coroutine context. A `readAction { }` block in script #1 does not exempt the same API call in script #2.

| You are about to… | Wrap the call in… |
|---|---|
| Read any PSI element / walk a PSI tree / navigate references | `readAction { }` |
| Use `FilenameIndex.*` (`getAllFilesByExt`, `getVirtualFilesByName`, `processAllFileNames`) | `readAction { }` |
| Use `PsiSearchHelper.*`, `ReferencesSearch.*`, `ClassInheritorsSearch.*` | `readAction { }` |
| Walk a VFS tree — `vf.children`, `vf.parent`, `vf.findChild(name)`, recursive `walk { }` | `readAction { }` |
| Resolve `PsiManager.getInstance(project).findFile(vf)` / `findDirectory(vf)` and read `psiFile.text` / `firstChild` / `name` | `readAction { }` |
| Get a `Document` from a `VirtualFile` via `FileDocumentManager.getInstance().getDocument(vf)` and read its text | `readAction { }` |
| Read `ProjectRootManager.contentRoots` / `ModuleRootManager.*` / `LibraryTable.*` | `readAction { }` |
| Touch `ChangeListManager.allChanges` / VCS model | `readAction { }` |
| Write to a VFS file (`VfsUtil.saveText`, `vf.setBinaryContent`) | `writeAction { }` |
| Invoke a refactoring processor's `.run()` (Rename / Move / SafeDelete / Inline / ChangeSignature / Extract*) | `writeIntentReadAction { }` — NOT `writeAction`; the processor manages its own actions internally, and `writeAction` deadlocks |
| Commit pending document edits to PSI | `writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }` (usually as the line *after* the refactor) |
| Use a `CommandProcessor.executeCommand { … }` block (undo-grouping) | put the command inside the appropriate read/write action — `executeCommand` itself is *not* an action |

A correctly-wrapped call produces the right result on the first try. An incorrectly-wrapped call throws `Read access is allowed from inside read-action only` or hangs indefinitely — both waste a retry turn.

`LocalFileSystem.getInstance().findFileByPath(path)` itself is safe outside `readAction { }` — it just resolves the `VirtualFile`. The wrap is required as soon as you start reading the file's *structure* (children, document, PSI) or accessing the PSI of any other model.

**Inside `steroid_execute_code` always go through the IntelliJ API.** The following are NOT correct shortcuts — they bypass the IDE's VFS, leave subsequent semantic queries (PSI, indexes, inspections) seeing stale content, and are explicitly out of scope for this tool:

- `java.io.File("…").walk()` / `listFiles()` / `exists()` — use `FilenameIndex.*` (or `LocalFileSystem.findFileByPath` + `vf.children` inside `readAction { }`).
- `java.nio.file.Files.*`, `Path.toFile()`, `Files.walk(...)` — same reason, same replacement.
- Spawning external processes from inside the script (`ProcessBuilder("…").start()`, `Runtime.exec(...)`) — banned in `steroid_execute_code` for classpath/lock-isolation reasons.
- Reading file content via `FileReader` / `BufferedReader.readText()` — use `String(vf.contentsToByteArray(), vf.charset)` so the IDE's VFS stays the source of truth.

The correct shape for "list test files" inside `steroid_execute_code`:

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

val testFiles = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
        .filter { it.path.contains("/core/src/test/") && it.name.endsWith("Test.java") }
        .map { it.path }
        .take(20)
        .toList()
}
println(testFiles.joinToString("\n"))
```

**Compile check** (quick boolean only — for structured diagnostics fetch `mcp-steroid://ide/jps-build-errors`):

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")

// If aborted == true and errors == false, the IDE build runner did not start.
// In Maven/Gradle projects, first fetch mcp-steroid://skill/execute-code-gradle
// or mcp-steroid://skill/execute-code-maven and run Sync + Observation.awaitConfiguration(project).
// Use Bash only if sync fails or times out.
```

**Run tests via the IDE runner, not Bash.** `./mvnw test` / `./gradlew test` cold-start ~31 s per invocation. The IDE runner keeps the JVM warm and returns structured pass/fail:

```kotlin[IU]
// Maven — single test class or method via the IDE's Maven runner:
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.RunManager

val cfg = MavenRunConfigurationType.getInstance().configurationFactories.single()
    .createTemplateConfiguration(project) as org.jetbrains.idea.maven.execution.MavenRunConfiguration
cfg.name = "Run PetRestControllerTests"
cfg.runnerParameters.workingDirPath = project.basePath!!
cfg.runnerParameters.goals = listOf("test", "-Dtest=PetRestControllerTests", "-Dspotless.check.skip=true")
val settings = RunManager.getInstance(project).createConfiguration(cfg, cfg.factory!!)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
```

For deeper patterns (SMTRunner listeners that block until tests finish + emit structured JSON results) fetch `mcp-steroid://skill/coding-with-intellij-spring`. Bash `./mvnw test` is only OK as a last-resort when the IDE runner has genuinely failed for the scenario.

**After a compile error**: fix and retry. Common fixes:
- `suspension functions can only be called within coroutine body` → mark helper as `suspend fun`
- `unresolved reference` → add missing import
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap in `readAction { }`

**File discovery**: `readAction { FilenameIndex.getAllFilesByExt(project, ext, projectScope()) }` or `readAction { FilenameIndex.getVirtualFilesByName(name, projectScope()) }` inside `steroid_execute_code` — O(1) indexed lookup over the same VFS your next write will touch. The `readAction { }` wrap is mandatory; without it the call throws `Read access is allowed from inside read-action only` and the script aborts.
**File reading**: `String((findProjectFile(path) ?: error("not found: $path")).contentsToByteArray(), charset)` inside `steroid_execute_code` — `findProjectFile` accepts project-relative AND absolute paths, and (outside `readAction`/`writeAction`) always refreshes the file from disk before returning, so externally created or modified files are seen correctly. Prefer `?: error(...)` over `!!` so a missing file fails with the path in the message. Single call, stays inside the IDE so PSI is consistent if you read the same file again later. The native `Read` tool is a valid alternative but imposes the Read-before-Edit contract only it tracks; staying inside `steroid_execute_code` avoids that coupling entirely.
**In-place file editing (ANY size, 1–1000+ lines)**: use steroid_execute_code — do NOT use the native `Edit` tool. The native `Edit` writes to disk bypassing IntelliJ, leaving VFS + PSI stale; every following semantic query sees the old content until you force a refresh. The IDE-side recipe below is ~5 lines of real code, same payload shape as `Edit(old, new)`, reads+writes inside one call, and the VFS auto-refreshes so PSI stays consistent:

```kotlin
val path = "src/main/java/com/example/MyClass.java"   // relative or absolute both work
val vf = findProjectFile(path) ?: error("not found: $path")
val content = String(vf.contentsToByteArray(), vf.charset)  // read
val updated = content.replace("OLD_STRING", "NEW_STRING")
check(updated != content) { "no match for OLD_STRING — verify with Grep first" }
writeAction { VfsUtil.saveText(vf, updated) }               // write + VFS refresh
```

For exactly-one-occurrence replace: `.replace(OLD, NEW).also { check(… == 1 occurrence) }`. For regex: `Regex(pattern).replace(content, replacement)`. Do NOT pre-Read the file via the native tool before using this recipe — the `vf.contentsToByteArray()` read already covers that.

**Two or more edits in one or more files**: do them in one `steroid_execute_code` script — pre-check every match, then save all files inside a single `writeAction { }` (see "Multi-site edits" at the top of this description).

**VFS refresh before and after every call.** MCP Steroid schedules two refreshes for you:
- **Before** kotlinc compiles your script, the plugin **awaits** a `VfsUtil.markDirtyAndRefresh` on the project root so the compiler sees every on-disk change made by a peer process or the previous call. Blocking, capped at 30 s.
- **After** your script returns — from a `finally` block, so this runs on success AND failure paths — the plugin fires a non-blocking async refresh. The MCP response returns immediately; the next semantic query sees the up-to-date state on the `RefreshQueue` thread.

You do **not** need to schedule VFS refresh yourself. You still need `PsiDocumentManager.getInstance(project).commitAllDocuments()` inside your script if the same script both writes and reads back PSI — the tail auto-refresh runs _after_ your script finishes.

**Payload accounting for this recipe.** The `steroid_execute_code` tool input carries only the Kotlin **script source** (typically ~200–400 chars for an in-place edit — 5 lines of code + a path + OLD/NEW strings). The file bytes that `vf.contentsToByteArray()` reads and the `updated` content that `saveText(vf, updated)` writes live inside the IDE JVM and never cross the MCP boundary — do NOT double-count them against the payload budget. For a 1-line change in a 160-line file, the `Edit` tool ships old_string + new_string (~60 bytes) and the recipe ships ~300 bytes of script — roughly 5× on the script itself, but you save the otherwise-required pre-Read (~3600 bytes for that 160-line file) and keep the IDE's VFS consistent. Net payload is **smaller**, not larger.

💡 Call `steroid_execute_feedback` after execution to rate success.
