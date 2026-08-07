Coding with IntelliJ: Refactoring, Services & Best Practices

Refactoring operations, code completion, IntelliJ services, error handling, best practices, quick reference, and special patterns.

## Refactoring Operations

### Rename Element

**CAUTION: This modifies code!**

```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

// First find the element to rename in a read action
val element = readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)
    psiFile?.findElementAt(100)?.parent  // find PsiElement at offset
}

if (element is PsiNamedElement) {
    WriteCommandAction.runWriteCommandAction(project) {
        element.setName("newName")
    }
    println("Renamed to: newName")
}
```

### Safe Refactoring with RefactoringFactory
```kotlin[IU]
import com.intellij.refactoring.RefactoringFactory
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val psiClass = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.OldName", GlobalSearchScope.projectScope(project))
}

if (psiClass != null) {
    val factory = RefactoringFactory.getInstance(project)
    val rename = factory.createRename(psiClass, "NewName")
    rename.run()
    println("Refactoring completed")
}
```
---

## Code Completion and Introspection

### Using PsiReference.getVariants() (Simplest)
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.lookup.LookupElement

val filePath = "/path/to/YourFile.kt"
val offset = 150  // Position where you want completions

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

val completions = readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    if (psiFile == null) return@readAction emptyArray<Any>()

    val element = psiFile.findElementAt(offset)
    val reference = element?.reference

    reference?.getVariants() ?: emptyArray()
}

println("Found ${completions.size} completions:")
completions.forEach { variant ->
    when (variant) {
        is LookupElement -> println("  - ${variant.lookupString}")
        is String -> println("  - $variant")
        else -> println("  - ${variant.javaClass.simpleName}: $variant")
    }
}
```
### Introspect a Class - Get All Methods and Fields
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val className = "com.intellij.openapi.project.Project"

readAction {
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(className, GlobalSearchScope.allScope(project))

    if (psiClass == null) {
        println("Class not found: $className")
        return@readAction
    }

    println("=== Class: ${psiClass.qualifiedName} ===\n")

    val methods = psiClass.allMethods
    println("Methods (${methods.size}):")
    methods
        .filter { !it.isConstructor }
        .sortedBy { it.name }
        .take(30)
        .forEach { method ->
            val params = method.parameterList.parameters
                .joinToString(", ") { "${it.name}: ${it.type.presentableText}" }
            val returnType = method.returnType?.presentableText ?: "void"
            println("  ${method.name}($params): $returnType")
        }

    val fields = psiClass.allFields
    println("\nFields (${fields.size}):")
    fields.sortedBy { it.name }.forEach { field ->
        println("  ${field.name}: ${field.type.presentableText}")
    }
}
```
### Resolve Reference - Find What a Symbol Points To
```kotlin[IU]
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiVariable

val filePath = "/path/to/YourFile.kt"
val offset = 150  // Put caret on a symbol you want to resolve

val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
if (vf == null) {
    println("File not found")
    return
}

readAction {
    val psiFile = PsiManager.getInstance(project).findFile(vf)
    val element = psiFile?.findElementAt(offset)
    val reference = element?.parent?.reference ?: element?.reference

    if (reference == null) {
        println("No reference at offset $offset")
        return@readAction
    }

    println("Reference text: ${reference.element.text}")

    val resolved = reference.resolve()
    if (resolved == null) {
        println("Could not resolve reference")
        return@readAction
    }

    println("Resolved to: ${resolved.javaClass.simpleName}")

    when (resolved) {
        is PsiMethod -> {
            println("Method: ${resolved.name}")
            println("Containing class: ${resolved.containingClass?.qualifiedName}")
            println("Return type: ${resolved.returnType?.presentableText}")
            println("Parameters:")
            resolved.parameterList.parameters.forEach { param ->
                println("  ${param.name}: ${param.type.presentableText}")
            }
        }
        is PsiField -> {
            println("Field: ${resolved.name}")
            println("Type: ${resolved.type.presentableText}")
            println("Containing class: ${resolved.containingClass?.qualifiedName}")
        }
        is PsiClass -> {
            println("Class: ${resolved.qualifiedName}")
            println("Is interface: ${resolved.isInterface}")
        }
        is PsiVariable -> {
            println("Variable: ${resolved.name}")
            println("Type: ${resolved.type.presentableText}")
        }
        else -> {
            println("Resolved element: ${resolved.text?.take(100)}")
        }
    }
}
```
## Services and Components
### Access Project Services
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.module.ModuleManager

// ⚠️ ProjectRootManager accesses the project model — must be inside readAction { }
val (sdkName, contentRootPaths, sourceRootPaths) = readAction {
    val rm = ProjectRootManager.getInstance(project)
    Triple(rm.projectSdk?.name, rm.contentRoots.map { it.path }, rm.contentSourceRoots.map { it.path })
}
println("SDK: $sdkName")

// Module manager
val moduleManager = ModuleManager.getInstance(project)
moduleManager.modules.forEach { module ->
    println("Module: ${module.name}")
}

// Content roots
contentRootPaths.forEach { println("Content root: $it") }

// Source roots
sourceRootPaths.forEach { println("Source root: $it") }
```
### Service Access Pattern

```kotlin
import com.intellij.openapi.module.ModuleManager

// Project-level service — use ModuleManager.getInstance(project) or project.getService()
val moduleManager = ModuleManager.getInstance(project)
println("Modules: ${moduleManager.modules.map { it.name }}")

// Application-level service
val app = com.intellij.openapi.application.ApplicationManager.getApplication()
println("Application: ${app.isHeadlessEnvironment}")
```

---

## Error Handling

### Use printException for Errors

```kotlin
try {
    // risky operation — e.g. accessing a file that might not exist
    val vf = findProjectFile("src/main/kotlin/MyClass.kt") ?: error("File not found")
    val content = String(vf.contentsToByteArray(), vf.charset)
    println("Content length: ${content.length}")
} catch (e: Exception) {
    // RECOMMENDED - includes stack trace in output
    printException("Operation failed", e)
}
```

### Never Catch ProcessCanceledException
```kotlin
import com.intellij.openapi.progress.ProcessCanceledException

try {
    // some operation
} catch (e: ProcessCanceledException) {
    // ✗ WRONG - Never catch this!
    throw e  // Always rethrow
}
```
---

## Best Practices

### 1. Use smartReadAction for PSI Operations

```kotlin[IU]
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// ✓ RECOMMENDED - combines wait + read in one call
val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}

// Instead of:
waitForSmartMode()
val classesViaReadAction = readAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope())
}
```

### 2. Use Built-in Helpers

No imports needed for:
- `readAction`, `writeAction`, `smartReadAction`
- `projectScope()`, `allScope()`
- `findPsiFile()`, `findProjectFile()`

### 3. Imports Are Optional

Default imports are provided automatically. Add imports only when needed:

```kotlin
// CORRECT - top-level imports when needed
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

### 4. Prefer IntelliJ APIs Over File Operations

The IDE has indexed everything - use it!

### 5. Use ONE Consistent task_id for the Entire Task

**Use the SAME `task_id` from your first call to your last.** Do NOT change `task_id` mid-task.

Changing `task_id` resets the MCP server's per-task feedback history and causes you to lose
context of what you've already done. After changing `task_id`, agents frequently re-read all
the same files already in their conversation history — wasting ~20s per redundant call.

**After discovering Docker is unavailable, a compile error, or any other constraint:**
continue with the SAME `task_id` and adapt your strategy (e.g., switch to `runInspectionsDirectly`
for compile verification). Do NOT restart exploration from scratch under a new `task_id`.

Group related executions with the same `task_id`.

### 6. Report Progress
```kotlin
import kotlinx.coroutines.delay

progress("Step 1 of 5...")
delay(1000)
progress("Step 2 of 5...")
```
### 7. Use printException for Errors

Includes full stack trace in output.

### 8. Keep Trying

The IntelliJ API has a learning curve - persistence pays off!

### 8a. Single Exploration Pass — Never Re-read Files Already in Context

Every file you read via `steroid_execute_code` is in your conversation history for the rest
of the session. Do NOT re-read a file you already read earlier:

- Files you read remain available in your conversation history.
- Only re-read a file if you **explicitly modified it** and need to verify the write succeeded.
- Do NOT restart exploration under a new `task_id` — you will re-read the same files and waste turns.
- Do NOT re-create a file because you forgot whether you created it — use `findProjectFile()` to check existence.

This rule is critical: each `steroid_execute_code` call takes ~20 seconds. Re-reading files already in
context wastes turns and time without adding information.

### 8b. Recovering from steroid_execute_code Compile Errors

When `steroid_execute_code` fails with a Kotlin compilation error (e.g. `unresolved reference 'GlobalSearchScope'`):

1. **Read the error message** — it names the exact unresolved symbol.
2. **Add the missing import** at the top of your script and resubmit.
3. **Do NOT switch to Bash/grep** after a compile error — one corrected steroid call is faster than 10 grep commands.

Common imports that are frequently missing:

```kotlin[IU]
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope   // ← most often missing
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VfsUtil
```

If the error is `suspension functions can only be called within coroutine body`, your helper
function is missing the `suspend` keyword — add `suspend fun myHelper()`.

### 9. Use runInspectionsDirectly for Code Analysis

More reliable than daemon-based analysis (works even when IDE window is not focused).

### 10. Never Use runBlocking

You're already in a coroutine context!

### 11. Verified Existing Implementation Is a Successful Outcome

If required behavior is already present, you still need explicit verification and explicit final
status output. "No code changes" is valid only after verification, not by assumption.
```kotlin
val requiredPaths = listOf(
    "src/main/java/com/example/api/MyController.java",
    "src/main/java/com/example/service/MyService.java",
    "src/test/java/com/example/api/MyControllerTest.java",
)

val missing = requiredPaths.filter { findProjectFile(it) == null }
if (missing.isNotEmpty()) {
    println("FINAL_STATUS=INCOMPLETE")
    println("MISSING_FILES=${missing.joinToString()}")
} else {
    val filesWithProblems = mutableListOf<String>()
    for (path in requiredPaths) {
        val vf = findProjectFile(path) ?: continue
        val problems = runInspectionsDirectly(vf)
        if (problems.isNotEmpty()) filesWithProblems += path
    }

    if (filesWithProblems.isEmpty()) {
        println("FINAL_STATUS=COMPLETE")
        println("FINAL_REASON=Verified existing implementation; no edits required")
    } else {
        println("FINAL_STATUS=INCOMPLETE")
        println("FILES_WITH_PROBLEMS=${filesWithProblems.joinToString()}")
    }
}
```
---
## Quick Reference Card

### Context Properties

```kotlin
import com.intellij.openapi.Disposable

// Available properties in script context:
val p: Project = project              // Current project
val d: Disposable = disposable        // For cleanup
val disposed: Boolean = isDisposed    // Check if disposed
println("Project: ${p.name}, disposed: $disposed")
```

### Output

```kotlin
// Output methods available in script context:
println("Hello", 42, "world")                        // Print values
printJson(mapOf("key" to "value"))                    // JSON output
progress("Step 1 of 3...")                            // Progress (throttled)
```

### Read/Write (No imports needed!)

```kotlin
readAction { project.name }           // Read PSI/VFS
writeAction { project.name }          // Write PSI/VFS
smartReadAction { project.name }      // Wait + read
```

### Scopes (No imports needed!)

```kotlin
val scope1 = projectScope()           // Project files only
val scope2 = allScope()               // Project + libraries
println("Project scope: $scope1, all scope: $scope2")
```

### File Access

```kotlin
val vf1 = findFile("/path/to/file.kt")                   // VirtualFile by absolute path
val psi1 = findPsiFile("/path/to/file.kt")                // PsiFile by absolute path
val vf2 = findProjectFile("src/main/App.kt")              // Relative to project
val psi2 = findProjectPsiFile("src/main/App.kt")          // Relative to project
println("vf1=$vf1 psi1=$psi1 vf2=$vf2 psi2=$psi2")
```

### Code Analysis (Recommended)

```kotlin
val file = findProjectFile("src/main/kotlin/MyClass.kt")
if (file != null) {
    val problems = runInspectionsDirectly(file, includeInfoSeverity = false)
    println("Problems: ${problems.size}")
}
```

### Common Imports

```kotlin[IU]
// PSI
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.searches.ReferencesSearch

// Java PSI
import com.intellij.psi.JavaPsiFacade

// Kotlin PSI
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// VFS
import com.intellij.openapi.vfs.LocalFileSystem

// Editor
import com.intellij.openapi.fileEditor.FileEditorManager

// Commands
import com.intellij.openapi.command.WriteCommandAction

println("Imports available")
```

### Thread Safety

```kotlin
readAction { project.name }    // For reading PSI/VFS
writeAction { project.name }   // For writing PSI/VFS
smartReadAction { project.name } // Wait for indexing + read
```

### Example: Find and Modify
```kotlin[IU]
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import com.intellij.openapi.command.WriteCommandAction

// Find
val ktClass = smartReadAction {
    KotlinClassShortNameIndex.get("MyClass", project, projectScope()).firstOrNull()
}

// Modify
if (ktClass != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        ktClass.setName("MyNewClass")
    }
}
```
---

## Refactoring: Find All Call Sites Before Adding Parameters

When adding a new field or parameter to a record, command, or DTO class, use `ReferencesSearch`
to locate every call site **before** editing. This prevents compile errors from undiscovered
constructors or factory calls in other files.
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

// Before adding a field to CreateReleaseCommand — find every constructor call site first.
// The class lookup and the reference search both read indexes, so one smartReadAction
// owns the whole query and its printed snapshot.
smartReadAction {
    val cmdClass = JavaPsiFacade.getInstance(project).findClass(
        "com.example.commands.CreateReleaseCommand",
        GlobalSearchScope.projectScope(project)
    )
    if (cmdClass != null) {
        ReferencesSearch.search(cmdClass, GlobalSearchScope.projectScope(project)).findAll().forEach { ref ->
            val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
            println("$file:${ref.element.textOffset} → " + ref.element.parent.text.take(120))
        }
    } else println("class not found — check FQN")
}
// Fix every listed call site BEFORE running the compiler.
// This 1 call replaces reading 3-5 files just to find constructors.
```
---

## Docker-Unavailable Fallback — ⚠️ EXCEPTION: ProcessBuilder Last Resort

> **⚠️ EXCEPTION — ProcessBuilder is used below ONLY because no IntelliJ API can detect a Docker socket failure.**
> For routine Maven test runs, use `MavenRunConfigurationType.runConfiguration()` or `MavenRunner` instead.
> See `mcp-steroid://skill/execute-code-maven` for the correct primary patterns.

When `./mvnw test` fails with `Could not find a valid Docker environment` or a `DockerException`,
Testcontainers cannot start the database container. Your code changes may be correct even though
no test passed. Use this two-step verification:

**Step 1 — Confirm it is a Docker-only failure** (not a compile or logic error):
```kotlin
// ⚠️ EXCEPTION: Docker-unavailable fallback only — use MavenRunConfigurationType.runConfiguration() for routine tests
val proc = ProcessBuilder("./mvnw", "test", "-Dtest=MyIntegrationTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
proc.waitFor()
val dockerError = lines.any { "Could not find a valid Docker" in it || "DockerException" in it }
println("Docker-only failure: $dockerError")
println(lines.takeLast(20).joinToString("\n"))
```
**Step 2 — Verify compilation separately** (only when step 1 confirms Docker is the sole blocker):
```kotlin
// ⚠️ EXCEPTION: Docker-unavailable fallback only — use MavenRunConfigurationType.runConfiguration() for routine tests
val proc = ProcessBuilder("./mvnw", "test-compile", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!)).redirectErrorStream(true).start()
val lines = proc.inputStream.bufferedReader().readLines()
val exitCode = proc.waitFor()
println("test-compile exit: $exitCode")
println(lines.filter { "ERROR" in it || "BUILD" in it || "FAILURE" in it }.joinToString("\n"))
// Exit code 0 = compilation success → safe to report ARENA_FIX_APPLIED: yes with caveat
// Exit code 1 = compile errors → fix errors first
```
**Reporting rule** (only when both conditions hold):
- `./mvnw test` failed with an explicit `DockerException` / `Could not find a valid Docker environment`
- `./mvnw test-compile` exits 0

→ Report: `ARENA_FIX_APPLIED: yes`
→ Note: `(tests blocked by Docker unavailability — compilation verified via test-compile)`

**Do NOT** apply this path when tests fail for any other reason (logic errors, missing methods,
Spring context startup failures). The Docker-unavailable path is ONLY valid when the error is
literally "cannot connect to Docker daemon."

---

**End of Guide**

For more examples, see the MCP resources:
- `mcp-steroid://lsp/overview` - LSP-like examples
- `mcp-steroid://ide/overview` - IDE power operations
- `mcp-steroid://debugger/overview` - Debugger examples

**IntelliJ Platform SDK**: https://plugins.jetbrains.com/docs/intellij/
**Source Code**: https://github.com/JetBrains/intellij-community
