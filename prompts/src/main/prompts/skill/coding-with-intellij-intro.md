Coding with IntelliJ: Introduction & Execution Model

Introduction to steroid_execute_code, execution model, script structure, coroutine context, and helper function rules.

## Introduction

### What is steroid_execute_code?

`steroid_execute_code` is an MCP tool that executes Kotlin code directly inside IntelliJ IDEA's JVM. Your code runs with full access to:

- **Project model** - modules, dependencies, source roots
- **PSI (Program Structure Interface)** - parsed code representation
- **VFS (Virtual File System)** - file access layer
- **IntelliJ indices** - fast code search and navigation
- **Editor APIs** - document manipulation, caret position
- **Refactoring APIs** - automated code transformations
- **Inspection APIs** - code quality analysis

### Why Use IntelliJ APIs Over File Operations?

| Instead of... | Use IntelliJ API | Why? |
|--------------|------------------|------|
| `grep`, `find` | PSI search, Find Usages | Understands code structure, not just text |
| Reading files with `cat` | VFS and PSI APIs | Respects IDE's caching and encoding |
| Manual text replacement | Refactoring APIs | Maintains code correctness and formatting |
| Guessing code structure | Query project model | IDE has already indexed everything |

**The IDE knows the code better than any file search tool.**

### Learning Curve

**Important**: Writing IntelliJ API code may require several attempts. This is normal! The API surface is vast and powerful. Keep trying - each attempt teaches you more about the available APIs.

- Use `printException(msg, throwable)` to see full stack traces
- Check return types and nullability
- Use reflection to discover available methods
- Consult the [IntelliJ Platform SDK docs](https://plugins.jetbrains.com/docs/intellij/)

---

## Execution Model

### Script Structure

Your code is the **suspend function body**. You do NOT need an `execute { }` wrapper.
```kotlin
// ✓ CORRECT - This is your script
println("Hello from IntelliJ!")
val projectName = project.name
println("Project: $projectName")

// ✗ WRONG - Do not wrap in execute { }
execute {
    println("Hello")  // ERROR: execute is not defined
}
```

> **Early exit.** Use plain `return` to stop the script. There is no `@execute` (or `@script`) label to return to — the body runs as the wrapping suspend function, so any unlabeled `return` exits cleanly. `return@execute` does NOT compile.

### Output: Only What You Print Comes Back

Apart from the `execution_id:` header, the tool response contains ONLY what your script
explicitly prints — `println(...)`, `printJson(...)`, `printCsv(...)`, or `printToon(...)`.
The last expression's value is **ignored by the runtime**: the body compiles as a suspend
function body, not a REPL, so there is no implicit return value. `return` cannot carry one
either — a bare `return` only exits early, and `return <value>` does not even compile (the
generated function returns `Unit`). A script that computes a result but never prints it
responds with just `execution_id: …` — indistinguishable from a script that produced nothing.

```kotlin
val paths = findProjectFiles("**/*.kt").map { it.path }

// ✗ WRONG — ending the script with the bare expression `paths`:
// its value is silently discarded and the response shows no output at all.

// ✓ CORRECT — explicitly print everything the caller needs:
println(paths.joinToString("\n"))
```

### Script is a Coroutine

The script body runs as a **suspend function**. This means:

- Use coroutine APIs directly (no `runBlocking` needed)
- Call suspend functions without special wrappers
- Use `delay()` instead of `Thread.sleep()`
```kotlin
// ✓ CORRECT - Direct coroutine usage
delay(1000)
progress("Step 1 complete")

// ✗ WRONG - Never use runBlocking
runBlocking {  // ERROR: Causes deadlocks!
    delay(1000)
}
```

### ⚠️ Helper Functions Must Be `suspend` When Calling Suspend APIs

If you define a local helper function inside your script that calls any suspend API (`runInspectionsDirectly`, `readAction`, `writeAction`, `smartReadAction`, etc.), the helper **must be declared `suspend fun`**. Omitting `suspend` causes a compile error: `"suspension functions can only be called within coroutine body"`.

```kotlin
// ✗ WRONG — non-suspend helper calling a suspend API.
// Omitting "suspend" from the helper causes a compile error:
//   "suspension functions can only be called within coroutine body"
// The fix: declare the helper as "suspend fun" (see CORRECT version below).
println("Always use 'suspend fun' for helpers calling suspend APIs")
```

```kotlin
// ✓ CORRECT — declare the helper as suspend
suspend fun checkFileHelper(vf: VirtualFile) {
    val problems = runInspectionsDirectly(vf)  // OK: suspend call in suspend fun
    println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
}

val vf = findProjectFile("src/main/kotlin/MyClass.kt")!!
checkFileHelper(vf)
```

```kotlin
// ✓ ALTERNATIVE — inline the call directly in the script body (no helper needed):
val vf = findProjectFile("src/main/kotlin/MyClass.kt")!!
val problems = runInspectionsDirectly(vf)
println(if (problems.isEmpty()) "OK" else "ERRORS: $problems")
```

This applies to ALL suspend context APIs: `readAction { }`, `writeAction { }`, `smartReadAction { }`, `waitForSmartMode()`, `runInspectionsDirectly()`, `findPsiFile()`, `findProjectPsiFile()`.

### Automatic Smart Mode

`waitForSmartMode()` is called **automatically before your script starts** under the default
`modal=smart_non_modal` (skipped under `non_modal` / `unleashed`), but it is not a
stable lease on smart mode. IntelliJ may enter dumb mode again before the next statement. Use
`smartReadAction { }` for index-dependent PSI reads, and use `Observation.awaitConfiguration(project)`
after project import/sync/configuration.
```kotlin[IU]
// Index-dependent PSI query: keep the whole query inside smartReadAction
val classes = smartReadAction {
    JavaPsiFacade.getInstance(project)
        .findClass("com.example.MyClass", allScope())
}

// After import/sync/configuration:
// import com.intellij.platform.backend.observation.Observation
// Observation.awaitConfiguration(project)
// val result = smartReadAction { /* indexed PSI query */ }
```

> **Bulk file creation triggers re-indexing**: Writing new files via `writeAction { VfsUtil.saveText(...) }` causes IntelliJ to re-index those files.
> - **In a subsequent steroid_execute_code call**: usually safe, but keep index-dependent PSI queries inside `smartReadAction { }`.
> - **In the same steroid_execute_code call** (create files then immediately inspect them): call `Observation.awaitConfiguration(project)` after the `writeAction` block, then use `smartReadAction { }` for `ReferencesSearch` / `JavaPsiFacade.findClass()` calls on the new files.
>
```kotlin
import com.intellij.platform.backend.observation.Observation

// Pattern: create files AND inspect in the SAME steroid_execute_code call
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example")
    val f = dir.findChild("MyService.java") ?: dir.createChildData(this, "MyService.java")
    VfsUtil.saveText(f, "package com.example;\npublic class MyService {}")
}
Observation.awaitConfiguration(project)
val vf = findProjectFile("src/main/java/com/example/MyService.java")!!
val problems = runInspectionsDirectly(vf)
println(if (problems.isEmpty()) "OK" else problems.toString())
```
>
> **Best practice**: Create files in one steroid_execute_code call, then inspect in a separate steroid_execute_code call. Use `smartReadAction { }` for indexed PSI reads.
>
> **⚠️ Create one file per steroid_execute_code call** when possible. Bundling multiple file creations in a single call makes error attribution hard: if the call throws an exception midway, it's unclear which files were created and which failed. Create files one at a time, verify existence (`findProjectFile(path) != null`), then proceed to the next.

### Execution Flow

1. **Submit code** via `steroid_execute_code`
2. **Review phase** (if enabled) - human approval
3. **Compilation** - Kotlin script engine compiles your code
   - Fast failure if compilation errors occur
4. **Execution** - Your script body runs with timeout
   - Progress messages throttled to 1/second
   - Context disposed when complete
5. **Response** - Everything the script printed (and nothing else) returned to MCP client

### Fast Failure

Errors are reported immediately (no waiting for timeout):

- **Script engine not available** → ERROR immediately
- **Compilation errors** → ERROR with details immediately
- **Runtime errors** → ERROR with stack trace
- **Timeout** → Execution cancelled, resources cleaned up

---
