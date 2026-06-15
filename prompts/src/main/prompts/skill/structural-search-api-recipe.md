Structural search canonical API recipe for steroid_execute_code

Validation-first recipe, threading rules, scope and injected-code caveats, smart-pointer invalidation, and a worked end-to-end example.

# Canonical SSR recipe for `steroid_execute_code`

This is the load-bearing article. Any rewrite copied from elsewhere should be cross-checked against the rules below — they catch the most common ways an SSR script silently fails.

> **Read-only EP introspection is a different track.** If your task is just "list the registered profiles", "count predefined templates for Java", "check whether the Kotlin profile is loaded", you do NOT need `MatchOptions`, do NOT need to call `Matcher.validate`, and do NOT need to think about template syntax at all. Skip to [§ Lightweight introspection](#lightweight-introspection--do-you-need-the-full-pipeline) below — the rules in this article (validate-first, no-outer-readAction, smart-pointer invalidation, etc.) start to matter only once you construct `MatchOptions` or `Matcher`.

> **The most common SSR audit shape — "introspection + ONE search"** — is the [Mixed-mode example](#mixed-mode-example--introspection--one-validated-matcher) below. If your task is "is the Java profile loaded? if yes, count Optional.get() callsites", start there. Same for "count predefined templates for Java AND find every System.out.println".

> **Top failure modes** (ranked by how silent they are):
> 1. Missing `~` prefix on `exprtype(...)` containing regex metacharacters (`.*`, `<.*>`, `+`, `|`) — silent zero matches. See [syntax § Common pitfall](mcp-steroid://skill/structural-search-syntax) and §1 below.
> 2. Calling `fillSearchCriteria(...)` twice on the same `MatchOptions` — silently overwrites the search pattern. See §"What NOT to do" below.
> 3. Wrapping `Matcher.findMatches(...)` in an outer `readAction { }` / `smartReadAction { }` — deadlock. See §2 below.
> 4. Single-backslash `\.` inside a Kotlin `"…"` string — Kotlin escape eats the `\`, regex sees a literal `.` (matches any char) instead of an escaped dot. Use `\\.` in `"…"` strings, or use Kotlin triple-quoted `"""…"""` strings for SSR patterns and write `\.` directly.

## Single-file scope (LocalSearchScope) variant

When the search target is a known file or small set of files (debugging an SSR pattern, scoped audit), use `LocalSearchScope` instead of `GlobalSearchScope.projectScope(project)` — much faster, doesn't iterate the project index.

The recipe below works **for every supported language**. Swap `JavaFileType.INSTANCE` → `KotlinFileType.INSTANCE` (or any other `LanguageFileType.INSTANCE`) and the matching apostrophe-form pattern; the rest is identical.

```kotlin[AI,IC,IU]
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.search.LocalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

// Resolve the file BEFORE entering any readAction. findProjectPsiFile is a suspend fun.
val psi = findProjectPsiFile("src/main/java/com/example/Foo.java")    // or .kt, .py-not-supported (no SSR), …
    ?: error("file not in project")

val matchOptions = MatchOptions().apply {
    fillSearchCriteria("System.out.println('_msg);")                    // pattern; swap per language
    setFileType(JavaFileType.INSTANCE)                                  // OR KotlinFileType.INSTANCE, etc.
    setScope(LocalSearchScope(psi))
    setRecursiveSearch(true)
}

readAction { Matcher.validate(project, matchOptions) }
val sink = CollectingMatchResultSink()
Matcher(project, matchOptions).findMatches(sink)   // do NOT wrap; same threading rule as global scope
println("matches in ${psi.virtualFile.name}: ${sink.matches.size}")
```

Same rules apply: validate first, do NOT wrap `findMatches` in an outer `readAction`. If you need to gather multiple `PsiElement`s for the scope, do it inside a separate `readAction { }` block first, then pass the array to `LocalSearchScope(elements: Array<PsiElement>)`. Release the read action before calling `findMatches`.

## Minimal search-only recipe (count + marker)

For a small audit where you only need a count, this is the smallest correct recipe:

```kotlin[AI,IC,IU]
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

val opts = MatchOptions().apply {
    fillSearchCriteria("System.out.'_m('_args*);")     // any apostrophe-form Java pattern
    setFileType(JavaFileType.INSTANCE)
    setRecursiveSearch(true)
    setSearchInjectedCode(false)
    setScope(GlobalSearchScope.projectScope(project))   // or LocalSearchScope — see variant above
}
readAction { Matcher.validate(project, opts) }
val sink = CollectingMatchResultSink()
Matcher(project, opts).findMatches(sink)
println("MATCHES: ${sink.matches.size}")                // emit a single marker line
```

### Hierarchy-audit variant (single file, marker-only output)

For "find all implementors of an interface inside one file" tasks:

```kotlin[AI,IC,IU]
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.search.LocalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

val psi = findProjectPsiFile("src/main/java/com/example/SsrHierarchyDemo.java")
    ?: error("file not in project")

val opts = MatchOptions().apply {
    fillSearchCriteria("class '_C implements '_I:*Greeting {}")     // :*Greeting widens to the supertype chain
    setFileType(JavaFileType.INSTANCE)
    setScope(LocalSearchScope(psi))
    setRecursiveSearch(true)
    setSearchInjectedCode(false)
}
readAction { Matcher.validate(project, opts) }
val sink = CollectingMatchResultSink()
Matcher(project, opts).findMatches(sink)
println("IMPLEMENTORS: ${sink.matches.size}")
```

Notes: in Java SSR, `implements` in the pattern matches BOTH `implements` and `extends` reference lists in source — so this template captures direct implementors AND transitive subclasses in one query. See [syntax §"Java `implements` matches both"](mcp-steroid://skill/structural-search-syntax) and use-case [F1](mcp-steroid://skill/structural-search-use-cases).

No `Replacer`, no per-match reporting, no command processor. Use this when the maintenance task is just "tell me how many of X exist". Switch to the canonical recipe at the top of this article when you need per-match details, line numbers, or a replacement.

> **For large multi-module repos** (multi-module Maven, multi-module Gradle), prefer marker-only output as above. Per-match payloads (file paths, line numbers, snippet text) on a project-wide audit can produce thousands of lines that drown out the marker the test or maintainer needs. Print marker-only first; only iterate per-match details after the count looks plausible.

## Mixed-mode example — introspection + one validated matcher

This is the **default shape for real audits**: a profile/template enumeration plus one structural search in the same script (e.g. "is the Java profile loaded? if yes, count `Optional.get()` callsites"). Introspection doesn't need `Matcher.validate`; the matcher does. They coexist cleanly:

```kotlin[AI,IC,IU]
// Imports — full set; mixed-mode adds the StructuralSearchProfile class to the minimal set.
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.readAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.StructuralSearchProfile
import com.intellij.structuralsearch.StructuralSearchUtil
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

// 1. Lightweight introspection — no MatchOptions, no validate, no scope.
val profiles = StructuralSearchProfile.EP_NAME.extensionList
println("SSR_PROFILES: ${profiles.size}")
val javaProfile = StructuralSearchUtil.getProfileByFileType(JavaFileType.INSTANCE)
println("JAVA_PROFILE_FOUND: ${if (javaProfile != null) "yes" else "no"}")
if (javaProfile != null) {
    println("JAVA_PREDEFINED_COUNT: ${javaProfile.predefinedTemplates.size}")
}

// 2. Validated matcher — only if the profile we need is loaded.
if (javaProfile != null) {
    // Triple-quoted string so we can write \. directly without doubling backslashes.
    val pattern = """'_opt:[exprtype( ~java\.util\.Optional<.*> )].get()"""
    //                            ^^^                                  ^^^^^^
    //                            ⚠ ~ is REQUIRED — `<.*>` is regex.   ^ literal method name; expression form (no trailing ;)
    //                            Without ~, this silently matches 0.
    val opts = MatchOptions().apply {
        fillSearchCriteria(pattern)
        setFileType(JavaFileType.INSTANCE)
        setRecursiveSearch(true)
        setSearchInjectedCode(false)
        setScope(GlobalSearchScope.projectScope(project))
    }

    readAction { Matcher.validate(project, opts) }   // releases before findMatches; findMatches runs its own internal read action
    val sink = CollectingMatchResultSink()
    Matcher(project, opts).findMatches(sink)         // do NOT wrap; see §2

    // sink.matches is List<MatchResult> — empty when no hits, never null.
    println("OPTIONAL_GET_MATCHES: ${sink.matches.size}")
}
```

Two distinct contracts in one script: introspection runs unconditionally and is fast; the matcher runs gated on `javaProfile != null` and follows the validate-first / no-outer-readAction rules. **Marker-only output** (`SSR_PROFILES:`, `OPTIONAL_GET_MATCHES:`) is the recommended first pass — see "For large multi-module repos" above.

> **Helper resolution**: when the prompt names a specific project-relative path (e.g. "audit `src/main/java/com/example/Foo.java`"), use `findProjectPsiFile("<that path>")` to resolve the `PsiFile` BEFORE entering any read action — `findProjectPsiFile` is a suspend fun and cannot be called inside `readAction { }`. Then pass it to `LocalSearchScope(psi)`. See [coding-with-intellij-context-api § File Access Helpers](mcp-steroid://skill/coding-with-intellij-context-api) for the do/don't.

## Lightweight introspection — do you need the full pipeline?

If your task is *not* a search/replace — e.g. "enumerate registered profiles", "list predefined templates for Java", "check whether the Kotlin profile is loaded" — you do NOT need `MatchOptions` / `Matcher` / `Replacer`. Use the EP and util APIs directly:

```kotlin[AI,IC,IU]
import com.intellij.structuralsearch.StructuralSearchProfile
import com.intellij.structuralsearch.StructuralSearchUtil
import com.intellij.ide.highlighter.JavaFileType

// 1. Every registered profile, by FQN
StructuralSearchProfile.EP_NAME.extensionList.forEach { p ->
    println("${p.javaClass.name}  shortenFQN=${p.supportsShortenFQNames()} staticImport=${p.supportsUseStaticImports()}")
}

// 2. Resolve the profile for a given file type (returns null if not supported)
val javaProfile = StructuralSearchUtil.getProfileByFileType(JavaFileType.INSTANCE)
require(javaProfile != null) { "Java SSR profile not loaded" }

// 3. All shipped predefined templates, sorted by category/name
val all = StructuralSearchUtil.getPredefinedTemplates()
all.groupBy { it.category }.forEach { (cat, list) -> println("$cat: ${list.size}") }

// 4. Predefined templates for one profile only
javaProfile.predefinedTemplates.forEach { println("${it.category}/${it.name}") }
```

These calls are read-only and fast — they don't need `Matcher.validate`, don't need a write action, don't need a scope. See [coverage](mcp-steroid://skill/structural-search-coverage) for the full per-language matrix.

The rest of this article is for **search and replace** workloads.

## The recipe

```kotlin[AI,IC,IU]
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.replace.ReplaceOptions
import com.intellij.structuralsearch.plugin.replace.impl.Replacer
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

// ---- INPUTS — parameterise per task ----
val fileType: LanguageFileType = JavaFileType.INSTANCE
val searchPattern  = "System.out.println('_msg:[exprtype( java\\.lang\\.String )]);"
val replacePattern: String? = "LOG.info(\$msg\$);"   // null → search-only (always start here)
val scope: SearchScope = GlobalSearchScope.projectScope(project)
val reformat = true
val shortenFqNames = true
val searchInjectedCode = false                        // explicit; default true broadens scans

// ---- BUILD ----
val matchOptions = MatchOptions().apply {
    fillSearchCriteria(searchPattern)
    setFileType(fileType); dialect = fileType.language
    setRecursiveSearch(true); setScope(scope)
    setSearchInjectedCode(searchInjectedCode)
}
val replaceOptions = replacePattern?.let {
    ReplaceOptions(matchOptions).apply {
        replacement = it
        isToReformatAccordingToStyle = reformat
        isToShortenFQN = shortenFqNames
    }
}

// ---- VALIDATE FIRST — non-negotiable ----
readAction { Matcher.validate(project, matchOptions) }
if (replaceOptions != null) readAction { Replacer.checkReplacementPattern(project, replaceOptions) }

// ---- SEARCH — Matcher manages its own read action; do NOT wrap ----
val sink = CollectingMatchResultSink()
Matcher(project, matchOptions).findMatches(sink)

// ---- REPORT ----
readAction {
    sink.matches.forEach { m: MatchResult ->
        val el = m.match ?: return@forEach
        val pf = el.containingFile ?: return@forEach
        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
        val line = (doc?.getLineNumber(el.textRange.startOffset) ?: 0) + 1
        printJson(mapOf("path" to pf.virtualFile?.path, "line" to line,
                        "text" to el.text.lineSequence().first().take(160)))
    }
}
println("attempted=${sink.matches.size}")

// ---- REPLACE — only when applicable ----
if (replaceOptions != null) {
    val replacer = Replacer(project, replaceOptions)
    var replaced = 0; var skipped = 0
    val infos = readAction {
        sink.matches.mapNotNull { m ->
            val target = m.match ?: run { skipped++; return@mapNotNull null }
            val vf = target.containingFile?.virtualFile
            if (vf == null || !vf.isWritable) { skipped++; null } else replacer.buildReplacement(m)
        }
    }
    writeAction {
        CommandProcessor.getInstance().executeCommand(project, {
            infos.forEach { info ->
                try { replacer.replace(info); replaced++ }
                catch (e: Exception) { skipped++ }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }, "SSR Replace", null)
    }
    println("replaced=$replaced skipped=$skipped (of ${sink.matches.size} matches)")
}
```

## Why each step matters

### 1. Validate first — `Matcher.validate(project, options)`

`Matcher(project, options)` constructs the matcher with `checkForErrors=false`. Several `MalformedPatternException` paths inside `PatternCompiler` are silently swallowed when this flag is false. Skipping validation does not raise an error — it produces a quietly-empty match set that looks like a clean miss.

The empirical proof: `Matcher(project, malformed)` succeeds; `Matcher.validate(project, malformed)` raises `MalformedPatternException`. Same options, different code path. Always call `Matcher.validate(project, opts)` (a static method) **before** calling `findMatches(...)` — the constructor itself does not validate.

**Diagnostic for "silent zero matches"** — if `validate` passes but `findMatches` returns 0 against a scope you know contains the shape, dump the `MatchVariableConstraint` fields before assuming the matcher is broken. The most common cause is a missing `~` prefix on an `exprtype(...)` whose argument contains regex metacharacters (`.*`, `+`, `<.*>`, etc.) — without `~`, the constraint is an exact-FQN compare that can't match parameterized types. See the [syntax article](mcp-steroid://skill/structural-search-syntax) "Common pitfall" callout. Quick check:

```
opts.variableConstraintNames.forEach { name ->
    val c = opts.getVariableConstraint(name)
    println("'$name' regex='${c.regExp}' exprType='${c.expressionTypes}' nameOfExprType='${c.nameOfExprType}'")
}
```

If `expressionTypes` contains `<.*>` and `nameOfExprType` is empty, you forgot `~`.

### 2. Don't wrap `findMatches` in an outer `readAction` — same for `smartReadAction`, `WriteIntentReadAction`, etc.

`Matcher.findMatches(sink)` wraps its workload in `PsiManager.runInBatchFilesMode { … }` and runs the indexable-files iteration under `ReadAction.runBlocking` itself. An outer read action causes nested locks and, on `GlobalSearchScope`, can deadlock the indexed-files iteration. The same advice applies to `LocalSearchScope` in production code; only the `testFindMatches(...)` API skips the read-action scheduler. If you need a read action to *prepare* a `LocalSearchScope` (e.g. to collect `PsiElement`s), release it before calling `findMatches`.

> ⚠ **SSR is the documented exception to the general "wrap PSI/index reads in `smartReadAction`" guidance** in [coding-with-intellij-threading](mcp-steroid://skill/coding-with-intellij-threading). For SSR `Matcher.findMatches(...)` calls specifically, do NOT use an outer `smartReadAction { }`, `WriteIntentReadAction`, or any other read-action wrapper — `Matcher` runs its own `ReadAction.runBlocking().inSmartMode()` internally, and an outer wrap deadlocks. The general threading rules apply everywhere ELSE in your script (collecting PsiFiles, reading match results, writing replacements); just not around `findMatches` itself.

### 3. From a coroutine: prefer `Replacer.replace(info)` (singular) over `replaceAll`

`replaceAll` enters `runWriteActionWithCancellableProgressInDispatchThread` — it is designed for the modal SSR dialog and will deadlock the EDT when invoked from `mcpScript`. The singular `replace(info)` is what `SSBasedInspection`'s quick-fix uses and what the Kotlin K2 replace tests use.

### 4. One `CommandProcessor.executeCommand` block

Wrap the whole batch of `replacer.replace(info)` calls in a single `CommandProcessor.getInstance(project).executeCommand(project, { ... }, "SSR Replace", null)`. That gives the user a single Ctrl+Z that undoes the entire batch.

### 5. `searchInjectedCode = false` for bulk edits

The default is `true`. With it true, `Matcher` recursively enumerates injected PSI from each `PsiLanguageInjectionHost` — meaning a SQL/RegExp/Java SSR can run inside `@Language("SQL") String sql = "..."` fragments embedded in Java/Kotlin string hosts, not just over standalone files. This is great for finding bugs in injected fragments and almost always wrong for bulk identifier rewrites. Set it to false explicitly unless injected code is intended.

### 6. Match-lifetime safety — `SmartPsiElementPointer`s can invalidate

`MatchResult` and `ReplacementInfo` rely on `SmartPsiElementPointer`s. When the matched element is deleted (by an earlier replacement, or by an unrelated edit between search and replace), `m.match` returns null. `Replacer.doReplace` silently returns null for null/non-writable/invalid targets. A naive `replaced=${sink.matches.size}` tally is therefore optimistic. Build `ReplacementInfo`s before any write, filter out null/non-writable targets up front, and report attempted vs replaced vs skipped separately.

### 7. Kotlin FQN shortening is asynchronous

`KotlinStructuralReplaceHandler.postProcess` submits a non-blocking `analyze { collectPossibleReferenceShorteningsInElementForIde(...) }`, then `invokeShortening()` runs on the EDT under a separate write command. **A script that reads file contents immediately after `replace(info)` may still see fully-qualified names.** For deterministic Kotlin scripts, set `isToShortenFQN = false` and run shortening explicitly afterwards.

### 8. Scope sizing and dry-runs

`Matcher` queues one task per indexable file for global scopes. Before a project-wide rewrite, run the recipe with `replacePattern = null` to count matches; consider `LocalSearchScope`, module/production scope, or chunking by content root. Don't invent a hard threshold — a quick dry-run answers the question for the actual project.

## Knobs cheat-sheet

| Knob | Type | Default | Notes |
|---|---|---|---|
| `MatchOptions.looseMatching` | bool | true | Relaxes whitespace/structure matching |
| `MatchOptions.recursiveSearch` | bool | false | When true, `findMatches` also descends into matched subtrees |
| `MatchOptions.caseSensitiveMatch` | bool | false | Identifier case-sensitivity |
| `MatchOptions.searchInjectedCode` | bool | **true** | Set to false for bulk edits |
| `MatchOptions.dialect` | Language | fileType.language | Set explicitly for languages with dialects (Groovy, JS) |
| `ReplaceOptions.isToReformatAccordingToStyle` | bool | persisted-default true | Reformats a few lines around each replacement |
| `ReplaceOptions.isToShortenFQN` | bool | persisted-default true | Java/Kotlin only; K2 is async (see §7) |
| `ReplaceOptions.isToUseStaticImport` | bool | false | Java only |

## What NOT to do

```
// BROKEN — silently overwrites the search pattern
val cfg = SearchConfiguration().apply {
    matchOptions.fillSearchCriteria(search)        // searchPattern = compiled SEARCH
}
val replaceOptions = ReplaceConfiguration(cfg).replaceOptions.apply {
    StringToConstraintsTransformer.transformCriteria(replace, cfg.matchOptions)  // OVERWRITES
    replacement = cfg.matchOptions.searchPattern
}
val sink = CollectingMatchResultSink().also { Matcher(project, cfg.matchOptions).findMatches(it) }
//                                                              ^^^ now searching for the REPLACE template
```

`StringToConstraintsTransformer.transformCriteria` writes back into `options.searchPattern`. Calling it twice on the same options clobbers the first call. The empirical fingerprint: search returns zero matches, or the wrong matches. Always set `replacement` directly to dollar-form text on `ReplaceOptions` — never re-run the transformer on populated options.

## Cross-references

- [Template language and macros](mcp-steroid://skill/structural-search-syntax) — what to put in `searchPattern`
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage) — which `LanguageFileType.INSTANCE` to use
- [Use-case gallery](mcp-steroid://skill/structural-search-use-cases) — search/replace pairs grouped by intent
- [Kotlin SSR specifics](mcp-steroid://skill/structural-search-kotlin) — K2 async shortening, custom filters, limitations
