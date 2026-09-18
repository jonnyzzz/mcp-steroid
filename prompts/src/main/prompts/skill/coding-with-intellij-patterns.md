Coding with IntelliJ: Common Patterns

Common patterns: project info, plugin discovery, file navigation, module inspection, readiness probes, FilenameIndex, text search, and VCS changes.

## Common Patterns

### Get Project Info
```kotlin
println("Project: ${project.name}")
println("Base path: ${project.basePath}")
```

### Get IDE Log Path
```kotlin
val logPath = com.intellij.openapi.application.PathManager.getSystemPath() + "/log"
println("Log: $logPath/idea.log")
```

### List Plugins
```kotlin
import com.intellij.ide.plugins.PluginManagerCore

// enabledPlugins lists all currently loaded plugins
PluginManagerCore.getPluginSet().enabledPlugins
    .forEach { println("${it.name}: ${it.version}") }
```

### Find Plugin by ID
```kotlin
import com.intellij.ide.plugins.PluginManagerCore

val plugin = PluginManagerCore.getPluginSet().enabledPlugins
    .find { it.pluginId.idString == "org.jetbrains.kotlin" }
println("Kotlin plugin: ${plugin?.version}")
```

### Check Plugin Installed (Before Using Plugin APIs)

> **⚠️ Do NOT call `PluginsAdvertiser.installAndEnable` or any programmatic plugin installer.**
> These APIs change signatures between IntelliJ versions and throw `IllegalArgumentException` /
> `IllegalAccessError` at runtime (2025.x+). Always check first; if not installed, use `required_plugins`
> parameter instead and let the tool system handle it.
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Check if a plugin is installed (and loaded) — use this BEFORE calling any plugin-specific API
val pluginId = PluginId.getId("com.intellij.database")  // replace with the plugin you need
val installed = PluginManagerCore.isPluginInstalled(pluginId)
val loaded = PluginManagerCore.getPlugin(pluginId) != null
println("Plugin $pluginId: installed=$installed loaded=$loaded")

// If not loaded: do NOT attempt installation. Instead, report the missing plugin ID and stop.
// The steroid_execute_code `required_plugins` parameter is the correct way to declare dependencies.
```

### Navigate Project Files
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

// ⚠️ contentRoots accesses the project model — must be inside readAction { }
val roots = readAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
roots.forEach { root ->
    println("Root: ${root.path}")
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (file.extension == "kt") println("  ${file.path}")
        true
    }
}
```

### Check File Type in Project
```kotlin
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")

if (vf != null) {
    val fileIndex = ProjectFileIndex.getInstance(project)

    println("Is in project: ${fileIndex.isInProject(vf)}")
    println("Is in source: ${fileIndex.isInSource(vf)}")
    println("Is in test source: ${fileIndex.isInTestSourceContent(vf)}")
    println("Is in library: ${fileIndex.isInLibraryClasses(vf)}")

    val module = fileIndex.getModuleForFile(vf)
    println("Module: ${module?.name}")
}
```

---

## First-Call Readiness Probe

Verify IDE + MCP connectivity before heavy ops:
```kotlin
println("IDE ready: ${project.name}")
println("Base path: ${project.basePath}")
println("Smart mode: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
```

> **Treat this as a connectivity probe, not an index-readiness lease.** Do not re-probe
> `waitForSmartMode()` before every operation. For index-dependent PSI queries, put the real
> work inside `smartReadAction { }`; after Maven/Gradle import or other project configuration,
> await `Observation.awaitConfiguration(project)` before the indexed read.

---

## When to SKIP the Initial Check Entirely

The first-call readiness probe costs **13-17s minimum** (steroid_list_projects + execute_code
round-trip). For short tasks (<3 min total), this is 10-15% overhead. **Skip it when ALL of:**

- Task is pure file editing (add field, add annotation, add method, add endpoint)
- No Docker/TestContainers in the test suite (`grep -r Testcontainers src/` → none)
- No compilation-unknown situation (you know the project structure from Glob/Read)
- Project is single-module or you already know which module to edit

**Skip pattern** — go straight to Read/Glob/Edit:
```
# CORRECT for simple tasks: no steroid call needed
# 1. Find files with Glob
# 2. Read with Read tool
# 3. Edit with Edit tool
# 4. Verify with: ./mvnw -pl <module> test -Dtest=<TestClass>
```

**Use initial check when:**
- Need Docker availability (TestContainers test)
- Need VCS diff to understand what test expects you to implement
- Need to verify project loads before long-running coding session
- Project structure is unknown and Glob alone can't identify the relevant modules

---

## Spring Boot / Maven Combined Startup Call

Combine readiness + Docker + VCS discovery in ONE call instead of 3 separate calls (saves ~60s). **Keep this call IDE-only**: Docker socket check + VCS modified files query. Do NOT batch file reads into this call — use Read/Glob tools for those (zero overhead). **Skip the Docker check if the scenario is pure file-creation (no @Testcontainers, no Docker in FAIL_TO_PASS tests)** — the check adds 10-15s with no benefit for those cases:
```kotlin
// Recommended FIRST steroid_execute_code call for any Spring Boot / Maven task:
println("Project: ${project.name}")
println("Smart: ${!com.intellij.openapi.project.DumbService.isDumb(project)}")
// Check Docker socket directly — no process spawn needed
// ❌ GeneralCommandLine("docker", ...) inside steroid_execute_code is BANNED — use Bash tool for docker inspect/exec
val dockerOk = java.io.File("/var/run/docker.sock").exists()
println("Docker: $dockerOk")
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println("VCS-modified files:\n" + changes.joinToString("\n"))
// Then read VCS-modified files + FAIL_TO_PASS test files in this SAME call or the next call.
// If dockerOk=false: still attempt to run FAIL_TO_PASS tests — many use H2 in-memory DB,
// no Docker needed. Only fall back to runInspectionsDirectly if the test fails with an
// explicit Docker connection error.
```

---

## Find Files — By Name OR By Extension (FilenameIndex)

> **INSIDE steroid_execute_code**: use `FilenameIndex` for file discovery — O(1) IDE-indexed lookup, always correct regardless of container path layout. Do NOT use `ProcessBuilder("find")` inside steroid_execute_code.
>
> **OUTSIDE steroid_execute_code** (agent level): use the native `Glob` and `Grep` tools — zero JVM overhead. Only use steroid_execute_code + FilenameIndex when you also need PSI or other IDE operations in the same call.

| What you want | ❌ Wrong | ✅ Right |
|---|---|---|
| All Java files | `Glob("src/**/*.java")` | `FilenameIndex.getAllFilesByExt(project, "java", scope)` |
| All SQL migrations | `Glob("src/**/*.sql")` | `FilenameIndex.getAllFilesByExt(project, "sql", scope)` |
| All YAML/yml files | `Glob("src/**/*.yaml")` | `FilenameIndex.getAllFilesByExt(project, "yaml", scope)` |
| Specific file by name | `Glob("**/UserService.java")` | `FilenameIndex.getVirtualFilesByName("UserService.java", scope)` |

```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope  // ← REQUIRED — missing this causes "unresolved reference"
val scope = GlobalSearchScope.projectScope(project)
// Find by exact filename — O(1) IDE index lookup, respects project scope
val byName = readAction { FilenameIndex.getVirtualFilesByName("UserServiceImpl.java", scope) }
byName.forEach { println(it.path) }
// Find all files by extension (replaces Glob "**/*.java"):
val byExt = readAction { FilenameIndex.getAllFilesByExt(project, "java", scope) }
byExt.forEach { println(it.path) }
// Find by extension + path filter (e.g., only migration SQL files):
val migrations = readAction {
    FilenameIndex.getAllFilesByExt(project, "sql", scope)
        .filter { it.path.contains("db/migration", ignoreCase = true) }
}
migrations.forEach { println(it.path) }
```

> **⚠️ Compile-error recovery**: If you get `unresolved reference 'GlobalSearchScope'`, add `import com.intellij.psi.search.GlobalSearchScope` and retry immediately. Do NOT abandon steroid_execute_code and fall back to Bash/grep after a compile error.

---

## Combined Discovery + Read in One Call

When you know target filenames from test imports — skip separate discovery step:
```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
val targets = listOf(
    "UserServiceImpl.java", "UserRestControllerTests.java",
    "ExceptionControllerAdvice.java", "User.java"
)
val files = readAction {
    targets.flatMap {
        FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.projectScope(project)).toList()
    }
}
files.forEach { vf ->
    println("\n=== ${vf.name} (${vf.path}) ===")
    println(String(vf.contentsToByteArray(), vf.charset))
}
```

---

## Search for Text Across Project Files (PsiSearchHelper)

> **Real-world benchmark** — finding all `thisLogger()` call sites in the full IntelliJ monorepo
> (~1,470 Kotlin files, measured on the live indexed project):
> - **PSI (`PsiSearchHelper`)**: **38 ms** — pre-built inverted word index
> - **`grep -r`**: **64,320 ms** — reads every `.kt` file from disk
> - **PSI is 1,692× faster**

Reproducible timing snippet — run against any indexed project:
```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
val startMs = System.currentTimeMillis()
// PsiSearchHelper reads the word index — smartReadAction, not plain readAction
smartReadAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord(
        "thisLogger", scope,
        { psiFile -> matchingFiles.add(psiFile.virtualFile.path); true },
        true
    )
}
println("PSI: ${matchingFiles.size} files in ${System.currentTimeMillis() - startMs}ms")
// Compare: bash `grep -r "thisLogger" ~/Work/intellij --include="*.kt" -l | wc -l` = 64,320ms
```

General pattern — search any word across project files:
```kotlin
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
val scope = GlobalSearchScope.projectScope(project)
val matchingFiles = mutableListOf<String>()
smartReadAction {
    PsiSearchHelper.getInstance(project).processAllFilesWithWord("/api/", scope, { psiFile ->
        matchingFiles.add(psiFile.virtualFile.path)
        true  // continue searching
    }, true)
}
matchingFiles.forEach { println(it) }
// For broader substring search, filter by content after getting candidates:
val containing = readAction {
    FilenameIndex.getAllFilesByExt(project, "java", scope)
        .filter { vf -> String(vf.contentsToByteArray(), vf.charset).contains("/api/v1") }
}
containing.forEach { println(it.path) }
```

---

## Multi-Agent Coordination — Check VCS Changes FIRST
```kotlin
// Run this at the start of your task to detect files already created/modified by parallel agents
val changes = readAction {
    com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        .allChanges.mapNotNull { it.virtualFile?.path }
}
println(if (changes.isEmpty()) "Clean slate — no prior agent changes" else "FILES ALREADY MODIFIED:\n" + changes.joinToString("\n"))
// If files are listed above: read them first before writing, to avoid overwriting work
```

**After VCS check: verify that changed files ACTUALLY solve the problem** (a prior agent may have created files in the WRONG package — modified files ≠ correct fix):
```kotlin[IU]
// Check whether required classes exist with correct FQN (not just any file)
val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
val required = listOf(
    "shop.api.core.product.Product",
    "shop.api.composite.product.ProductAggregate"
)
val missing = required.filter {
    readAction { com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(it, scope) } == null
}
println(if (missing.isEmpty()) "All required classes present — run tests to verify"
        else "STILL MISSING (must create): " + missing.joinToString(", "))
```

> **`JavaPsiFacade.findClass` is for *user-project* classes, not plugin classes.** Symbols owned by the running IDE (`com.intellij.*`, `com.jetbrains.*` from bundled plugins, etc.) are on the **script's compile classpath** but not in the user project's PSI model. A `null` from `JavaPsiFacade.findClass("com.jetbrains.clones.DuplicateProblemDescriptor", projectScope)` does **not** mean the class is unavailable — it means you asked the wrong question. For plugin classes, just `import` and use them directly.

---

## Accessing Third-Party Inspection ProblemDescriptor Subclasses

`InspectionEngine.inspectEx(...)` returns `ProblemDescriptor`s. Many bundled inspections store their extra payload on a *subclass* (e.g. `com.jetbrains.clones.DuplicateProblemDescriptor.getTextClone()`, custom Kotlin/Java DFA descriptors). The `steroid_execute_code` compile classpath already contains every loaded plugin's classes (`ScriptClassLoaderFactory.ideClasspath()` flattens `descriptor.pluginClassLoader.files` for every plugin and content module), so the **typed import + `filterIsInstance` cast** is the recipe — direct code, no reflection.

```kotlin[IU]
import com.intellij.codeInspection.ProblemDescriptor
import com.jetbrains.clones.DuplicateProblemDescriptor

// `problems` would come from InspectionEngine.inspectEx(...) — see mcp-steroid://ide/find-duplicates
val problems: List<ProblemDescriptor> = emptyList()
problems.filterIsInstance<DuplicateProblemDescriptor>().forEach { dpd ->
    val tc = dpd.textClone   // Kotlin property accessor for getTextClone()
    println("clone main = ${tc.main.file.path}:${tc.main.lines.first}")
}
```

If the import reports `unresolved reference`, the owning plugin is not loaded — declare it on the `steroid_execute_code` call via the `required_plugins` parameter (the relevant module here is `com.intellij.modules.duplicatesDetector`). Do not work around it with `Class.forName` + reflection, and never `setAccessible(true)` on a private field. Private-field renames in the next IDE release silently break the script, and there is a public getter for every payload that the platform exposes externally — find it via the bytecode (`unzip -p <plugin>.jar Foo.class | javap -p -`) instead of guessing.

See `mcp-steroid://ide/find-duplicates` for the full duplicate-code recipe and `mcp-steroid://skill/coding-with-intellij` (top of guide) for the broader reflection policy.

---
