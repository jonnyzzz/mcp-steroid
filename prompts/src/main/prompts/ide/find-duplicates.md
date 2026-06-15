IDE: Find Duplicate Code

Find duplicate code clusters: PSI body comparison (Kotlin/Java) or the bundled `DuplicatedCode` inspection (IDEA, PyCharm Pro, WebStorm, RubyMine) — typed access, no reflection.

# TL;DR for agents

###_IF_IDE[AI,IC,IU]_###
If the task is "find / refactor / scan for duplicate code" on a Kotlin/Java project: **scroll to "Primary recipe — PSI body comparison" below, copy the kotlin block into one `steroid_execute_code` call, done.** Output is `CLUSTERS_FOUND: <n>` plus a `path:startLine-endLine` line per fragment. For Python / JavaScript / Groovy / Ruby, the Cross-check (`DuplicatedCode` inspection) is the right tool instead. The rest of this article is reference material — read it only if the Primary recipe returns unexpected results or the user asks for near-duplicate / parameterized clone detection.
###_ELSE_IF_IDE[PY,RM,WS]_###
This IDE bundles the `DuplicatedCode` inspection (duplicates-detector module): **scroll to "Cross-check recipe — warm-index inspection" below and copy that kotlin block into one `steroid_execute_code` call.** Output is `CLUSTERS_FOUND: <n>` plus a `path:startLine-endLine` line per fragment. Caveat: the inspection queries the project-wide `HashFragmentIndex`, which is empty in fresh sessions / CI — a `CLUSTERS_FOUND: 0` right after IDE start means "index cold", not "no duplicates"; wait for indexing to finish (smart mode) and re-run before concluding.
###_ELSE_###
This IDE bundles neither the Kotlin/Java PSI the Primary recipe walks nor the `DuplicatedCode` duplicates-detector module, so the ready-made kotlin recipes in this article are omitted here. The *approach* still transfers: walk this IDE's PSI for body-bearing declarations, normalize each body's text (strip whitespace), and group identical bodies — use the extension probe below to see which file types the project has, and `PsiTreeUtil.collectElementsOfType` with this IDE's declaration PSI class. Rider caveat: C# analysis runs out-of-process in ReSharper — C# PSI is not reachable from `steroid_execute_code`; only IDE-frontend languages can be walked this way.
###_END_IF_###

# When to use this

Whenever an agent is asked to "find and refactor duplicate code", "extract a common helper for repeated logic", or "scan for clones". The `DuplicatedCode` inspection is the right tool where it ships — IntelliJ IDEA Ultimate, PyCharm Pro, WebStorm, RubyMine: it runs on Java, Kotlin, Python, Groovy, JavaScript, Ruby, and other supported languages via the same `DuplicateProblemDescriptor` payload, and the descriptor exposes a public `getTextClone()` getter so a script can enumerate every clone cluster typed.

**Pick println vs printJson before you start.** The base recipe ends with `println` for human-readable cluster reports. If you're an agent piping the result into a follow-up step (count check, file-hit assertion, summary generation), jump straight to the **Structured output (printJson)** section below — same dedup, machine-readable shape, no second exec_code pass to reshape verbose output.

###_IF_IDE[AI,IC,IU]_###
**Agent fast path.** **For Kotlin / Java, run the Primary recipe (PSI body comparison) below FIRST. Do NOT start with the warm-index Cross-check inspection path — it can legitimately return zero in fresh sessions.** The Primary recipe is the very next code block below; copy that. It works in fresh sessions, CI, test environments, AND fully-indexed projects — no warm-index prerequisite, no second pass to verify. The "Cross-check recipe — warm-index inspection" further down the article is OPTIONAL — only when the user explicitly wants near-duplicate / parameterized-clone detection AND the project's `HashFragmentIndex` is known to be warm. `CLUSTERS_FOUND: 0` from the cross-check path alone is ambiguous — it is NOT evidence that no duplicates exist until the Primary recipe has also run.

**Primary recipe language coverage**: Kotlin (`KtNamedFunction`) + Java (`PsiMethod`) only. For Python / JavaScript / Groovy / Ruby projects, the warm-index Cross-check recipe is the right tool — the Primary recipe will return 0 clusters even if there are obvious duplicates.
###_END_IF_###

**Clusters can be intra-file or cross-file.** Two methods inside one class with the same body are reported the same way as a method in file A duplicating a method in file B. **And the inspection emits the same logical cluster N times** (once per fragment-as-`main`), so a 2-fragment pair surfaces twice — the Cross-check recipe deduplicates by hashing the unordered set of `(path:startLine-endLine)` ranges. Skip the dedup and your `CLUSTERS_FOUND` count is roughly N× too large.

**Line numbers are 1-based.** `TextFragment.lines.first` and `TextFragment.lines.last` are 1-based and ready to show to a user (the IDE does `getLineNumber(offset) + 1` internally). `path:startLine-endLine` lines you print are clickable in IDE/editor consoles without conversion.

**`.kt` vs `.kts`.** A Kotlin/Gradle project usually has both — `.kt` for source and `.kts` for build scripts. The recipe scans whichever extensions you put in `targetExtensions`. If the user asks about *source* files, leave `.kts` out; for a full project audit, add it.

**Source-only path filter** — if your build pollutes `src/` with generated files or you want to skip tests, narrow with the `pathFilter` lambda already wired into the recipe. Common variants:

- Production source only: `pathFilter = { it.contains("/src/main/") }`
- Source + tests: `pathFilter = { "/src/main/" in it || "/src/test/" in it }`
- Skip `build/` and other generated trees: `pathFilter = { "/build/" !in it && "/.gradle/" !in it }`

> **Before submitting the recipe, ensure `steroid_execute_code` is callable in your session.** If your client lazy-loads MCP tool schemas (e.g. Claude Code's deferred tools), call `ToolSearch` (or the equivalent schema-load step for your client) for `mcp__mcp-steroid__steroid_execute_code` first. Without the schema loaded the call will fail with `InputValidationError` and you will lose a turn.

###_IF_IDE[AI,IC,IU]_###
# Primary recipe — PSI body comparison (no index needed)

**This is the recipe the "Agent fast path" callout points to.** Collect Kotlin / Java functions, **extract just the body block** (NOT the full declaration — the most common duplicate pattern is copy-paste-rename where names differ but bodies are identical), normalize (strip whitespace + line endings), and group identical bodies. Covers intra-file clones (two methods in one class with the same body) AND cross-file clones; works in fresh sessions, CI, test environments, AND fully-indexed projects with no warm-index prerequisite.

```kotlin[AI,IC,IU]
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction

data class CloneRange(val path: String, val startLine: Int, val endLine: Int, val name: String)

// Language coverage: this recipe walks `KtNamedFunction` (Kotlin) and `PsiMethod` (Java) only.
// For Python / JavaScript / Groovy / Ruby use the Cross-check recipe instead — this one will
// return 0 clusters on those languages even when obvious duplicates exist.
val targetExtensions = listOf("java", "kt")
val pathFilter: (String) -> Boolean = {
    "/build/" !in it && "/.gradle/" !in it && "/.idea/" !in it && "/out/" !in it
}
val scope = GlobalSearchScope.projectScope(project)

val clones = smartReadAction(project) {
    val files = targetExtensions.flatMap { ext ->
        FilenameIndex.getAllFilesByExt(project, ext, scope)
    }.filter { pathFilter(it.path) }

    val byBody = mutableMapOf<String, MutableList<CloneRange>>()
    for (vf in files) {
        val psi = PsiManager.getInstance(project).findFile(vf) ?: continue

        // Kotlin: KtNamedFunction.bodyBlockExpression skips fun + name + params.
        // Match copy-paste-rename patterns where the body matches but the name differs.
        PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).forEach { fn ->
            // Cover both block-body (`fun f() { ... }`) and expression-body
            // (`fun f() = expr`) forms — the second is common in idiomatic
            // Kotlin and would slip through if we only checked block bodies.
            val body = (fn.bodyBlockExpression?.text ?: fn.bodyExpression?.text) ?: return@forEach
            if (body.length < 60) return@forEach
            val normalized = body.replace(Regex("\\s+"), " ").trim()
            val doc = psi.viewProvider.document ?: return@forEach
            val startLine = doc.getLineNumber(fn.textRange.startOffset) + 1
            val endLine = doc.getLineNumber(fn.textRange.endOffset) + 1
            byBody.getOrPut(normalized) { mutableListOf() }
                .add(CloneRange(vf.path, startLine, endLine, fn.name ?: "<anon>"))
        }

        // Java: PsiMethod.body excludes signature + return type.
        PsiTreeUtil.collectElementsOfType(psi, PsiMethod::class.java).forEach { m ->
            val body = m.body?.text ?: return@forEach
            if (body.length < 60) return@forEach
            val normalized = body.replace(Regex("\\s+"), " ").trim()
            val doc = psi.viewProvider.document ?: return@forEach
            val startLine = doc.getLineNumber(m.textRange.startOffset) + 1
            val endLine = doc.getLineNumber(m.textRange.endOffset) + 1
            byBody.getOrPut(normalized) { mutableListOf() }
                .add(CloneRange(vf.path, startLine, endLine, m.name))
        }
    }
    byBody.values.filter { it.size >= 2 }
}

println("CLUSTERS_FOUND: ${clones.size} (PSI body comparison)")
clones.take(20).forEachIndexed { i, cluster ->
    println("Cluster ${i + 1} (${cluster.size} fragments):")
    cluster.forEach { println("  ${it.path}:${it.startLine}-${it.endLine}  ${it.name}") }
}
```

**Why bodies, not whole declarations**: copy-paste-rename is the dominant real-world duplicate pattern — two functions with identical bodies but different names. Comparing `KtNamedFunction.bodyBlockExpression` / `PsiMethod.body` (instead of `PsiNamedElement.text`) catches those; comparing the full element text misses them entirely. Whole-file or whole-class text comparisons are NOT meaningful duplicate-code clusters — the recipe deliberately walks body-bearing declarations only.

**Completeness note**: the Primary recipe finds **exact body duplicates only** (after whitespace normalization). It does NOT find near-duplicates, parameterized clones, or structurally similar code with different variable names. The Cross-check recipe below (bundled `DuplicatedCode` inspection, when the index is warm) catches a broader set of clone types. Tell the user explicitly what was scanned — if they asked about near-duplicates or fuzzy clones, the Primary recipe alone is insufficient and the Cross-check is the natural second pass.

**Body-length threshold (60 chars)** filters trivial getters/setters and one-liners that swamp results. Lower to ~40 for smaller codebases or if the user suspects short duplicate blocks; raise to ~120 for noisy monorepos.

## Structured output for the Primary recipe (printJson)

The Primary recipe above returns `clones: List<List<CloneRange>>` (every inner list is one duplicate cluster; each `CloneRange` carries `path`, `startLine`, `endLine`, `name`). For pipelines or follow-up code that consumes the result programmatically, replace the trailing `println` loop with `printJson`. **The data shape is different from the Cross-check recipe's `printJson` block further down — do NOT paste that block here; it will not compile against `List<List<CloneRange>>`.**

```kotlin[AI,IC,IU]
// Drop-in replacement for the Primary recipe's trailing println loop.
// `clones` comes from the Primary recipe above — the data class + val stubs
// here exist only so this block stands alone for KtBlock compilation.
data class CloneRange(val path: String, val startLine: Int, val endLine: Int, val name: String)
val clones: List<List<CloneRange>> = emptyList()  // populated by the Primary recipe

val payload: Map<String, Any> = mapOf(
    "clusterCount" to clones.size,
    "clusters" to clones.map { cluster ->
        mapOf(
            "occurrences" to cluster.size,
            "fragments" to cluster.map { r ->
                mapOf(
                    "path" to r.path,
                    "startLine" to r.startLine,
                    "endLine" to r.endLine,
                    "name" to r.name,
                )
            },
        )
    },
)
printJson(payload)
```
###_END_IF_###

###_IF_IDE[AI,IC,IU,PY,RM,WS]_###
# Why direct typed access works (no reflection needed) — for the Cross-check recipe

`steroid_execute_code` compiles your Kotlin against every loaded plugin's classloader files (`ScriptClassLoaderFactory.ideClasspath()` flattens `descriptor.pluginClassLoader.files` for every loaded plugin and its content modules). IDEA Ultimate, PyCharm Pro, WebStorm, and RubyMine bundle the duplicates-detector module, so `com.jetbrains.clones.DuplicateProblemDescriptor`, `com.jetbrains.clones.structures.TextClone`, and `com.jetbrains.clones.structures.TextFragment` are all on the script classpath. **Import them directly and cast.** Do **not** look the class up via `JavaPsiFacade` — that queries the *user project's* classpath, where the plugin classes never live, and a `null` result there is a known false negative that has historically led agents into reflection.

# Cross-check recipe — warm-index inspection (broader clone types)

> **Skip this section unless the Primary recipe has already run AND the user explicitly wants near-duplicate / parameterized-clone detection.** This is the OPTIONAL second pass; it is NOT the default. In fresh sessions / CI / test environments the `HashFragmentIndex` is empty and this recipe returns `CLUSTERS_FOUND: 0` even when duplicates exist — that zero is ambiguous, not authoritative. The Primary recipe (PSI body comparison) above is what you should reach for first.

Use the inspection-based recipe only when the user explicitly wants near-duplicate / parameterized-clone detection, AND you've already established the project has a populated `HashFragmentIndex`.

Submit this as a single `steroid_execute_code` call. Adjust `targetExtensions` to whatever your project uses. Everything else is fully self-contained.

```kotlin[AI,IC,IU,PY,RM,WS]
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PairProcessor
import com.jetbrains.clones.DuplicateInspection
import com.jetbrains.clones.DuplicateProblemDescriptor
import com.jetbrains.clones.structures.TextClone
import com.jetbrains.clones.structures.TextFragment

data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)

fun TextFragment.toRange() = CloneRange(file.path, lines.first, lines.last)

// Adjust to your task. The recipe reports file:line ranges only — to also capture
// the duplicated source text, see "Reporting the duplicated source text" below.
// For unfamiliar polyglot projects, run the extension-probe snippet (under
// "Discovering which file types exist") first to see what to scan.
val targetExtensions = listOf("java", "kt", "py")
val maxClustersToReport = 20
// Sane default: skip generated trees that are usually full of noise. Widen to `{ true }`
// for a full audit, or narrow to e.g. `{ "/src/main/" in it }` for production-only.
val pathFilter: (String) -> Boolean = {
    "/build/" !in it && "/.gradle/" !in it && "/out/" !in it && "/.idea/" !in it
}

val scope = GlobalSearchScope.projectScope(project)
val files = readAction {
    targetExtensions.flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }
        .filter { pathFilter(it.path) }
}
println("Scanning ${files.size} file(s) for clones")

val wrapper = LocalInspectionToolWrapper(DuplicateInspection())

// IMPORTANT — `DuplicatedCode` emits ONE descriptor per cluster *per file containing
// its main fragment*. A 2-fragment cluster surfaces twice — once with fragment A as
// `main` and B as a duplicate, then with the roles swapped (this happens for both
// cross-file pairs AND for two methods inside the same file). Deduplicate before
// reporting or you will over-count.
val seenKeys = mutableSetOf<String>()
val clusters = mutableListOf<CloneCluster>()

for (vf in files) {
    if (clusters.size >= maxClustersToReport) break
    val perFile = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@readAction emptyList<CloneCluster>()
        val raw: List<ProblemDescriptor> = InspectionEngine.inspectEx(
            listOf(wrapper),
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false,
            false,
            true,
            EmptyProgressIndicator(),
            PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
        ).values.flatten()

        raw.filterIsInstance<DuplicateProblemDescriptor>().mapNotNull { dpd ->
            val tc: TextClone = dpd.textClone
            val main = tc.main.toRange()
            val dups = tc.duplicates.map { it.toRange() }
            // Cluster identity = unordered set of all fragments. Sort the ranges, then join.
            val key = (listOf(main) + dups)
                .map { "${it.path}:${it.startLine}-${it.endLine}" }
                .sorted()
                .joinToString("|")
            if (seenKeys.add(key)) CloneCluster(main = main, duplicates = dups) else null
        }
    }
    clusters += perFile
}

println("CLUSTERS_FOUND: ${clusters.size}")
clusters.forEachIndexed { i, c ->
    println("Cluster #${i + 1} (${c.duplicates.size + 1} occurrences)")
    println("  main ${c.main.path}:${c.main.startLine}-${c.main.endLine}")
    c.duplicates.forEach { d -> println("  dup  ${d.path}:${d.startLine}-${d.endLine}") }
}
```

# Structured output (printJson)

For pipelines or follow-up code that needs to consume the result programmatically, swap the trailing `println` block for a single `printJson` call. Stable shape: `clusterCount`, `clusters[].occurrences`, `clusters[].fragments[].{path, startLine, endLine}`. Same dedup logic as the Cross-check recipe — only the final emission changes.

```kotlin[AI,IC,IU,PY,RM,WS]
// Drop into the Cross-check recipe — replaces the trailing println loop. The local
// data classes here mirror the ones in the Cross-check recipe; **delete the data
// class and `val clusters` stubs below when merging** — they exist only so this
// block stands alone for KtBlock compilation. The real `clusters` comes from the
// Cross-check recipe.
data class CloneRange(val path: String, val startLine: Int, val endLine: Int)
data class CloneCluster(val main: CloneRange, val duplicates: List<CloneRange>)
val clusters: List<CloneCluster> = emptyList()  // populated by the Cross-check recipe

val payload: Map<String, Any> = mapOf(
    "clusterCount" to clusters.size,
    "clusters" to clusters.map { c ->
        mapOf(
            "occurrences" to (c.duplicates.size + 1),
            "fragments" to (listOf(c.main) + c.duplicates).map { r ->
                mapOf("path" to r.path, "startLine" to r.startLine, "endLine" to r.endLine)
            },
        )
    },
)
printJson(payload)
```

# Reporting the duplicated source text

`CloneRange` only carries path + line numbers — useful for navigation, not for
showing the user *what* is duplicated. When the task is "summarize the
duplication for a human", read the snippet from the `Document` while you still
hold the read action:

```kotlin[AI,IC,IU,PY,RM,WS]
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.jetbrains.clones.structures.TextFragment

fun TextFragment.snippet(maxChars: Int = 300): String {
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return ""
    return range.substring(doc.text).take(maxChars)
}
// inside the same readAction { } that owns the descriptor:
//   val text = tc.main.snippet()
```

Combine with the Cross-check recipe above: replace `CloneRange` with a richer
record that also stores `snippet`, and call `tc.main.snippet()` / `it.snippet()`
while the read action is still open. (Touching `Document` outside `readAction { }`
will throw `ReadAccessException` — see
`mcp-steroid://skill/coding-with-intellij-threading`.)

When the user is asking *what* is duplicated (not just *where*), include the
snippet of each cluster's `main` fragment in your reply so they can judge
relevance without opening every file. For pure counting / triage tasks the
file:line markers from the Cross-check recipe are enough — skip the snippet helper.
###_END_IF_###

# Discovering which file types exist in the project

The default `targetExtensions = listOf("java", "kt", "py")` is a sensible
starting point. For an unfamiliar project, ask the IDE which extensions are
actually present before running a project-wide scan (this probe is pure
platform API and works in every JetBrains IDE):

```kotlin
import com.intellij.psi.search.GlobalSearchScope

val byExtCount = readAction {
    com.intellij.psi.search.FilenameIndex
        .processAllFileNames({ true }, GlobalSearchScope.projectScope(project), null)
}
// or simpler — just probe the candidates you care about:
val present = listOf("java", "kt", "kts", "py", "groovy", "js", "ts", "rb")
    .associateWith { ext ->
        readAction {
            com.intellij.psi.search.FilenameIndex
                .getAllFilesByExt(project, ext, GlobalSearchScope.projectScope(project)).size
        }
    }
println(present.filterValues { it > 0 })
```

Use the non-empty extensions to populate `targetExtensions` so the scan
visits files the project actually has. For an autonomous run where you
want a single round-trip, inline the probe into the recipe you run — replace
the hard-coded `targetExtensions` with the result of the probe, then continue
unchanged.

###_IF_IDE[AI,IC,IU,PY,RM,WS]_###
# How the Cross-check recipe works

- `DuplicateInspection` is a `LocalInspectionTool` (`shortName = "DuplicatedCode"`, registered with `runForWholeFile="true"`). Per-file `checkFile` looks up a `DuplicateScopeExtension` for the file's language, queries the project-wide `HashFragmentIndex`, and emits a `DuplicateProblemDescriptor` for each clone where the inspected file holds the cluster's `main` fragment.
- **Same logical cluster surfaces multiple times.** A 2-fragment cluster is reported twice — fragment A as `main` + B as duplicate, then B as `main` + A as duplicate. An N-fragment cluster appears N times, once per fragment-as-`main`. The Cross-check recipe deduplicates by hashing the unordered set of `(path:startLine-endLine)` ranges. Skip the dedup and your `CLUSTERS_FOUND` count is roughly 2× too large.
- `maxClustersToReport` caps **unique** clusters (post-dedup) — the `seenKeys` guard ensures the loop's break runs against deduped count, not raw descriptor count.
- `DuplicateProblemDescriptor.getTextClone()` returns a `TextClone(main: TextFragment, duplicates: List<TextFragment>)`. `TextFragment` exposes `file: VirtualFile`, `range: TextRange`, and `lines: IntRange` — everything you need to render `path:startLine-endLine` and pull the snippet text from the document.
- Indexing must be ready. Under the default `modal=smart_non_modal` the script's bootstrap calls `waitForSmartMode()` automatically (skipped under `non_modal` / `unleashed`); if you trigger any reindexing in the same call, await `com.intellij.platform.ide.observation.Observation.awaitConfiguration(project)` before the inspection runs. (`Observation` is in `com.intellij.platform.ide.observation` — fully qualify it; the unqualified name does not resolve.)
- **Runtime scales with project size.** A small fixture finishes in 2-5 s; a 500-file project takes 30-60 s; multi-thousand-file monorepos can take 2-5 minutes (the `HashFragmentIndex` query inside `checkFile` is project-wide). For large projects bump `steroid_execute_code` `timeout` (default 600 s, registry-configurable via `mcp.steroid.execution.timeout`) and narrow `pathFilter` to a target subtree.

# Language coverage for the Cross-check recipe (Java, Kotlin, Python, ...)

The same `DuplicatedCode` inspection works across every language that registers a `duplicateScope` extension:

| Language | Module providing the scope | IDE that bundles it |
|----------|---------------------------|---------------------|
| Java     | `intellij.java.duplicatesDetection`     | IDEA Ultimate |
| Kotlin   | `intellij.kotlin.duplicate.scope`       | IDEA Ultimate (Kotlin plugin) |
| Python   | `intellij.python.duplicatesDetection`   | PyCharm Pro / IDEA Ultimate with Python plugin |
| Groovy   | `intellij.groovy.duplicatesDetection`   | IDEA Ultimate |
| JS / TS  | `intellij.javascript.duplicatesDetection` | WebStorm / IDEA Ultimate |
| XML      | `intellij.xml.duplicatesDetection`      | IDEA Ultimate |
| Ruby     | `intellij.ruby.duplicatesDetection`     | RubyMine |

If `DuplicateScopeExtension.findDuplicateScope(fileType)` returns `null` for the file's language, `checkFile` returns no problems — the file is silently skipped. No language-specific code change to the Cross-check recipe above is required; just adjust `targetExtensions` to whatever the project uses.

# When the Cross-check returns zero clusters

If the Cross-check recipe reports `CLUSTERS_FOUND: 0` on a project you know contains duplicates (or on the standard `DemoDuplicates.kt` fixture with two byte-identical method bodies), the cross-check ran but the inspection emitted no `DuplicateProblemDescriptor`s. The most common root cause is an empty `HashFragmentIndex`; the per-file `checkFile` query returns no clones because no clones have been indexed yet. **On Kotlin/Java (IDEA) the Primary recipe (PSI body comparison) above is the answer — if you haven't run it yet, go back and run it; don't pivot to grep / Bash.** On Python / JavaScript / Ruby, wait for indexing to finish (smart mode), re-run the cross-check, and only then report a verdict — a cold-index zero is not evidence.

Skip the index probe — the safer signal is **the Cross-check recipe itself returning `CLUSTERS_FOUND: 0`**. If you saw zero, the index is either empty or the inspection path doesn't apply; handle it as in the previous paragraph. (Earlier guidance suggested probing `FileBasedIndex.getAllKeys(HashFragmentIndex.NAME, ...)` but the `HashFragmentIndex` package path is internal-only and changes across IDE versions — the class is not resolvable from the script classpath.)

# When the Cross-check direct import does not compile

The Cross-check recipe's typed imports work in **IDEA Ultimate, PyCharm Pro, RubyMine, and other commercial IDEs that bundle the duplicates-detector module** (the bulk of agent traffic). If `steroid_execute_code` reports `unresolved reference: DuplicateProblemDescriptor`, the module isn't loaded in the running IDE — most likely a Community / EAP build, or an unusual configuration. The fix is **not** reflection. Try in this order:

1. **Just retry without any preflight.** The `steroid_execute_code` script classpath is built from every loaded plugin's classloader files, and the module's classes appear there as soon as the IDE has loaded the plugin. The first compile failure can be transient (e.g. you fired the call before the IDE finished loading after a fresh restart).
2. **Confirm the module is loaded.** Use the "Find Plugin by ID" recipe in `mcp-steroid://skill/coding-with-intellij-patterns`. The relevant module is `com.intellij.modules.duplicatesDetector`. If it shows as loaded, the typed import will work — there is nothing to declare.
3. **`required_plugins` — last resort, with caveat.** `required_plugins` runs a preflight check that *can reject the module-style ID format* `com.intellij.modules.duplicatesDetector`. Try the typed import without `required_plugins` first; only if the class is genuinely missing AND the module isn't loaded should you declare it — and even then, expect to discover the right ID format empirically (the preflight error will name accepted IDs). Do not assume one specific value will pass.
4. **Do not switch to reflection.** Private-field renames silently break next IDE release. The public `getTextClone()` getter is the answer.

> **Reflection is for exploration, not for the recipe you ship.** If you reached for `Class.getDeclaredField("myTextClone")` + `setAccessible(true)` to extract the clone pair, stop — that path is brittle (private-field renames in the next IDE release silently break the script) and unnecessary. The public `getTextClone()` getter and the typed `import` above are the right answer. See the reflection-policy note in `mcp-steroid://skill/coding-with-intellij`.
###_END_IF_###

# See also

- [Inspection + Quick Fix](mcp-steroid://ide/inspect-and-fix) — single-file inspection + quick fix pattern this recipe extends
- [Inspection Summary](mcp-steroid://ide/inspection-summary) — list inspections enabled in the project
- [Apply Patch](mcp-steroid://ide/apply-patch) — atomic multi-site edits, the natural follow-up after locating a clone cluster
- [Common Patterns](mcp-steroid://skill/coding-with-intellij-patterns) — cross-classloader fallback and find-by-extension recipes used above
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) — top-level skill index
