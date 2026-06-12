Coding with IntelliJ: McpScriptContext API Reference

Full API reference for McpScriptContext: project, output methods, read/write actions, search scopes, file helpers, and IDE utilities.

## McpScriptContext API Reference

The `McpScriptContext` is the receiver (`this`) of your script body. It provides access to the project, output methods, and utility functions.

**Source**: [`src/main/kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt`](../../kotlin/com/jonnyzzz/intellij/mcp/execution/McpScriptContext.kt)

### Real helpers vs invented names

These names exist on `McpScriptContext` (or among the default imports) — use them:
`readAction`, `writeAction`, `smartReadAction`, `writeIntentReadAction`,
`findFile`, `findPsiFile`, `findProjectFile`, `findProjectFiles`,
`findProjectPsiFile`, `runInspectionsDirectly`, `projectScope`, `allScope`,
`waitForSmartMode`, `project`, `println`, `printJson`, `printCsv`,
`printToon`, `progress`, `printException`, `takeIdeScreenshot`, `disposable`.

These names **do not exist** — do not write them. Use the replacement on the right:

| Invented (does not compile) | Use instead |
|---|---|
| `buildProject()`, `compileProject()` | `ProjectTaskManager.getInstance(project).buildAllModules().await()` — needs `import com.intellij.task.ProjectTaskManager` and `import org.jetbrains.concurrency.await` (neither is in the default imports) |
| `createProjectFile("path", text)` | `findProjectFile("path")` for an existing file. For a **new** file, do everything in one `writeAction { }` block — `LocalFileSystem` alone does not create files. Pattern: `writeAction { val dir = VfsUtil.createDirectoryIfMissing(project.guessProjectDir()!!, "rel/path"); val vf = dir.createChildData(this, "Name.kt"); VfsUtil.saveText(vf, text) }` |
| `context.project` | Just `project` — it is already in scope (no `context.` prefix) |
| `projectDir`, `findProjectDir()` | `project.basePath` or `project.guessProjectDir()` |
| `readText(vf)` (top-level function) | `String(vf.contentsToByteArray(), vf.charset)` — `vf.readText()` also exists as a `VirtualFile` extension but the byte-array form is what the rest of the corpus uses |

Each invented name appears verbatim in this table on purpose — if a script
fails with e.g. `unresolved reference 'buildProject'`, grep this article for
the name and the right-hand column is the recipe.

### Core Properties

```kotlin
// Core properties available on McpScriptContext (this):
//   project: Project         — IntelliJ Project instance
//   params: JsonElement       — Original tool execution parameters (JSON)
//   disposable: Disposable   — Parent Disposable for resource cleanup
//   isDisposed: Boolean      — Check if context is disposed
println("Project: ${project.name}, disposed: $isDisposed")
```

### Output Methods

```kotlin
// Output methods:
println("Hello", "World")               // Print space-separated values
printJson(mapOf("key" to "value"))       // Serialize to pretty JSON (uses Jackson)
// Token-efficient tabular emitters — use for flat array-of-records results
// from PSI / index searches (find-references, call-hierarchy, project-search,
// document-symbols). Same data shape, two output formats:
//
//   printCsv(headers: List<String>, rows: Iterable<List<Any?>>, dictColumns: Set<String> = emptySet())
//     CSV with RFC 4180 escaping. Caller supplies the column order explicitly
//     as `headers` and each row as a parallel `List<Any?>`. When a column
//     name appears in `dictColumns`, that column is emitted as an `@<col>:`
//     dictionary preamble (`p1=…`, `p2=…`) and each cell is replaced with
//     the short ID — repeated long values (e.g. absolute paths) cost their
//     tokens once instead of N times.
//
//   printToon(value: Any?)
//     TOON (Token-Oriented Object Notation — https://github.com/toon-format/toon).
//     Drop-in replacement for `printJson` when the data is array-of-records
//     shaped. Pass a `List<Map<String, Any?>>` — the COLUMN ORDER is taken
//     from the FIRST map's key order, and every subsequent map is checked
//     for the same key set. `printToon` does NOT take `headers` / `rows` /
//     `dictColumns` — that's `printCsv`'s shape; `printToon` infers
//     everything from the data.
printCsv(
    headers = listOf("idx", "path", "line"),
    rows = listOf(listOf(1, "/abs/A.kt", 17), listOf(2, "/abs/B.kt", 42)),
    dictColumns = setOf("path"),
)
printToon(
    listOf(
        mapOf("path" to "/abs/A.kt", "line" to 17),
        mapOf("path" to "/abs/B.kt", "line" to 42),
    ),
)
progress("Processing step 1...")         // Report progress (throttled to 1/sec)
val execId = takeIdeScreenshot()         // Capture IDE screenshot, returns execution_id
try { error("demo") } catch (e: Exception) { printException("oops", e) } // Report error without failing
```

### Tabular Output (`printCsv` / `printToon`) — array-of-records, token-efficient

Use these for flat record lists from PSI / index searches
(find-references, call-hierarchy, project-search, document-symbols, file
inventories). Same data, two formats — pick by the heuristic below.

**Signatures (exact types — common compile error: passing `List<Map>` to `printCsv`).**

| Helper      | Signature                                                                                | Row shape                                             |
|-------------|------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `printCsv`  | `printCsv(headers: List<String>, rows: Iterable<List<Any?>>, dictColumns: Set<String> = emptySet())` | `List<List<Any?>>` — **positional**, parallel to `headers` |
| `printToon` | `printToon(value: Any?)`                                                                 | `List<Map<String, Any?>>` — **keyed**, columns inferred from first map's key order |

**Choose by repetition shape:**

- Same long value (absolute paths, FQNs) repeated across rows → `printCsv` with `dictColumns = setOf("path")`. The preamble emits each distinct path once and the column collapses to `p1` / `p2` IDs.
- Mostly-unique values OR you want a JSON-like nested shape →  `printToon`. Simpler row construction (`mapOf(…)`), no parallel-list bookkeeping.

**Same data, two formats** — a typical recipe finishes by emitting both:

```kotlin
// Round-trip the same 5 records through both emitters. The CSV form pays
// the path tokens once in the preamble; the TOON form prints the JSON-like
// header + comma rows. Sample output below — keep as a code comment so
// the article passes the no-non-kotlin-fences contract.
//
//   CSV with dictColumns:
//     @path:
//       p1=/abs/src/main/kotlin/A.kt
//       p2=/abs/src/main/kotlin/B.kt
//     idx,path,line
//     1,p1,17
//     2,p2,42
//     3,p1,180
//
//   TOON array-of-records:
//     [3]{path,line}:
//       /abs/src/main/kotlin/A.kt,17
//       /abs/src/main/kotlin/B.kt,42
//       /abs/src/main/kotlin/A.kt,180
```

The CSV preamble form is RFC 4180-compliant for the rows themselves — a
cell containing `,` / `"` / newline / CR is double-quoted, embedded `"`
becomes `""`. The TOON form follows
[toon-format/toon](https://github.com/toon-format/toon); the array-of-
records shape (`[N]{cols}:` header + comma rows) is what `printToon`
emits when every map in the list has the same key set.

**End-to-end example** — discover `.kt` files once, emit the same record
list both ways. `findProjectFiles` is a `suspend` resolver; it stays
OUTSIDE `readAction { }` and the per-file metadata extraction goes
INSIDE the read action.

**Glob anchoring caveat (`findProjectFiles`).** The matcher is anchored
to the project root, but a leading `**/` segment in front of an
absolute-looking path (`**/src/main/**/*.kt`) can match zero files on
modules where the source root isn't at the project root level. The
robust pattern for "all `.kt` files anywhere in the project" is
`**/*.kt` plus a path-substring filter:

```kotlin
val ktFiles = findProjectFiles("**/*.kt")
    .filter { "/src/main/" in it.path }
    .take(5)
```

```kotlin
val files = findProjectFiles("src/main/**/*.kt").take(5)
val records = readAction {
    files.map { vf ->
        val text = String(vf.contentsToByteArray(), vf.charset)
        Triple(vf.path, text.lines().size, vf.name)
    }
}

printCsv(
    headers = listOf("idx", "path", "lines", "basename"),
    rows = records.mapIndexed { i, (path, lines, name) -> listOf(i + 1, path, lines, name) },
    dictColumns = setOf("path"),
)

printToon(records.map { (path, lines, name) -> mapOf("path" to path, "lines" to lines, "basename" to name) })
```

### Built-in Read/Write Actions (NO IMPORTS NEEDED!)

```kotlin
// Built-in read/write actions (no imports needed):
val text = readAction { "read under lock" }         // Execute under read lock (PSI/VFS reads)
writeAction { /* modify PSI/VFS under write lock */ }  // Execute under write lock
val smart = smartReadAction { "smart + read" }       // Wait for smart mode + read action
println("read=$text, smart=$smart")
```

**Important**: These are **built-in** - you do NOT need to import `readAction` or `writeAction` from IntelliJ Platform!

### Built-in Search Scopes (NO IMPORTS NEEDED!)

```kotlin
// Built-in search scopes (no imports needed):
val projScope = projectScope()  // Project files only (no libraries)
val everything = allScope()     // Project + libraries
println("Project scope: $projScope")
```

### File Access Helpers

```kotlin
// File access helpers — call from the script body or another suspend fun, NEVER inside readAction { }:
val vf = findFile("/tmp/test.txt")                   // VirtualFile by absolute path
val psi = findPsiFile("/tmp/test.txt")                // PsiFile by absolute path — SUSPEND
val projFile = findProjectFile("build.gradle.kts")    // VirtualFile relative to project
val projPsi = findProjectPsiFile("build.gradle.kts")  // PsiFile relative to project — SUSPEND
println("file=$vf, psi=$psi, projFile=$projFile, projPsi=$projPsi")
//
// DON'T:
//   readAction {
//       val psi = findProjectPsiFile("X.kt")        // compile error: findProjectPsiFile is a suspend fun;
//                                                   // the readAction lambda is not a coroutine builder.
//   }
// DO:
//   val psi = findProjectPsiFile("X.kt")            // call from the script body (which is a suspend fun)
//   readAction { /* now operate on `psi` here */ }  // wrap only the PSI/VFS reads, not the resolver call
```

> **⚠️ `findProjectFile()` pitfall for resource files**: This function requires the **full relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename (`findProjectFile("application.properties")`) **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename without requiring the full path:
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.projectScope(project)
val appProps = readAction {
    FilenameIndex.getVirtualFilesByName("application.properties", scope)
        .firstOrNull { it.path.contains("src/main/resources") }
} ?: error("application.properties not found in src/main/resources")
println(String(appProps.contentsToByteArray(), appProps.charset))
```

### IDE Utilities

```kotlin
// IDE utilities:
waitForSmartMode()                        // Wait for indexing; asserts non-modal (auto under modal=smart_non_modal)
closeModalDialogs()                       // Close leftover modal dialogs now (returns count; auto under smart_non_modal)
allowModalDialog()                        // About to open a dialog on purpose — don't let the monitor close/fail it
syncDocuments()                           // Commit + save documents + refresh VFS (auto under smart_non_modal)

// Check if editor highlighting has completed for a file:
val buildFile = findProjectFile("build.gradle.kts")
if (buildFile != null) {
    println("Highlighting done: ${isEditorHighlightingCompleted(buildFile)}")

    // Inspections on a file (RECOMMENDED — works regardless of window focus):
    //   runInspectionsDirectly(file: VirtualFile, includeInfoSeverity: Boolean = false)
    //     -> Map<inspectionShortName, List<ProblemDescriptor>>
    // Runs every ENABLED inspection from the project's current profile against
    // `file` and returns the descriptor list per inspection. By default skips
    // INFO severity; pass `includeInfoSeverity = true` to include them.
    val problems = runInspectionsDirectly(buildFile)
    for ((tool, descs) in problems) {
        println("$tool: ${descs.size} problems")
    }

    // To target a SPECIFIC inspection (e.g. DuplicatedCode), do not use
    // runInspectionsDirectly — it runs the full enabled-set. Construct the
    // inspection class directly and call InspectionEngine.inspectEx; see
    // mcp-steroid://ide/inspect-and-fix (single inspection + quick fix) or
    // mcp-steroid://ide/find-duplicates (DuplicatedCode across the project).
}
```

> **`ProblemDescriptor` results carry a live PSI reference, not a snapshot.** Accessing `.text`, `.textRange`, `psiElement.containingFile`, etc. on a returned descriptor *outside a `readAction { }` / `smartReadAction { }`* throws `ReadAccessException`. Consume the descriptors inside the same read action, or re-enter one in your post-processing loop.

### Daemon Analysis & Highlights

> **Do not call IntelliJ's daemon-highlighting internals directly from a script.**
> Symbols like `DaemonCodeAnalyzerImpl`, `DaemonCodeAnalyzerImpl.runMainPasses`,
> `DaemonProgressIndicator`, and `HighlightingSession` require running under a
> `DaemonProgressIndicator` with a stored `HighlightingSession` — neither
> exists in a `steroid_execute_code` script context. Typical failures:
> `must be run under DaemonProgressIndicator, but got: null` and
> `No HighlightingSession stored in …`.
>
> For inspection diagnostics, use the supported recipes:
> `runInspectionsDirectly(file)` (above) for the full enabled-inspection set,
> [Inspect and fix](mcp-steroid://ide/inspect-and-fix) for a single inspection
> with quick fix, or
> [Inspection summary](mcp-steroid://ide/inspection-summary) for the full
> project report.

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlin.time.Duration.Companion.seconds

val file = findProjectFile("src/Main.kt") ?: error("File not found")

// Open file in editor (required for daemon analysis)
withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(file, true)
}

// Wait for editor highlighting to complete (default timeout: 30s)
val completed = waitForEditorHighlighting(file, timeout = 30.seconds)
println("Highlighting completed: $completed")

// Get highlights (warnings/errors) when daemon finishes
// NOTE: may return stale results if IDE window is not focused — use runInspectionsDirectly() instead
val highlights = getHighlightsWhenReady(file)
highlights.forEach { info ->
    println("${info.severity}: ${info.description}")
}
```

### File Discovery by Glob Pattern

```kotlin
// Find project files matching a glob pattern (relative to project root)
val kotlinFiles = findProjectFiles("**/*.kt")
println("Found ${kotlinFiles.size} Kotlin files")

val testFiles = findProjectFiles("src/test/**/*Test.kt")
testFiles.forEach { println(it.path) }
```

### Disposable Hierarchy

The context provides a `disposable` property for resource cleanup:
```kotlin
import com.intellij.openapi.util.Disposer

// Access the execution's parent Disposable
val execDisposable = disposable

// Register your own cleanup
val myResource = Disposer.newDisposable("my-resource")
Disposer.register(execDisposable, myResource)

// myResource will be disposed when execution completes (success, error, or timeout)
```

---
