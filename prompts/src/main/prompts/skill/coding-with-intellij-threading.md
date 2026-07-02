Coding with IntelliJ: Threading and Read/Write Actions

IntelliJ threading model, read/write action patterns, smart mode, VFS mutation rules, modal dialogs, and ModalityState usage.

## Rules

### ⚠️ THREADING RULE — NEVER SKIP

Any PSI access (`JavaPsiFacade`, `PsiShortNamesCache`, `PsiManager.findFile`, `ProjectRootManager.contentSourceRoots`, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. **This applies to ALL PSI calls including your very first exploration call** (e.g. listing source roots). This is the most common first-attempt error.

### Quick decision — what wrap do I need?

| You're doing… | Wrap in |
|---|---|
| VFS write (`saveText`, `createChildData`, `delete`, `rename`, `move`) | `writeAction { }` |
| PSI read, `FilenameIndex`, references search | `readAction { }` (or `smartReadAction { }` if indexes may still be building) |
| Refactoring processor `.run()` (Rename / Move / SafeDelete / Inline / ChangeSignature / Extract*) | `writeIntentReadAction { }` (NOT `writeAction` — deadlocks) |
| VFS/PSI write that logs `Background write action is not permitted on this thread` | `backgroundWriteAction { }` — or `withContext(Dispatchers.EDT) { writeAction { … } }` if the API requires EDT |
| EDT-only API (UI action invocation, opening a file in editor, focusing a window) | `withContext(Dispatchers.EDT) { }` |
| Read first, then mutate based on the result | `readAction { /* read */ }` → outside-block compute → `writeAction { /* write */ }` (see "writeAction { } Is NOT a Coroutine Scope" below) |

### Failure → fix

When the script run logs one of these errors, the fix is mechanical:

- `Access is allowed from write thread only` → wrap the offending mutation in `writeAction { … }`.
- `Access is allowed from Event Dispatch Thread (EDT) only` → wrap the call in `withContext(Dispatchers.EDT) { … }`.
- `Background write action is not permitted on this thread. Consider using backgroundWriteAction, or switch to EDT` → use `backgroundWriteAction { … }`, or switch to `withContext(Dispatchers.EDT) { writeAction { … } }` if the API requires EDT.
- `suspension functions can only be called within coroutine body` → you called a `suspend` helper (`readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`) from inside a non-suspend lambda (often inside another `writeAction`). Move the inner call OUTSIDE the outer block, or make the enclosing function `suspend fun`. See "writeAction { } Is NOT a Coroutine Scope" below.

### API → wrap quick lookup

If you are about to write any of these in a `steroid_execute_code` script, you owe `readAction { }` (or `writeAction { }` for mutations). Each row is verified against IntelliJ 2025.3+ runtime threading assertions:

| API call | Wrap in |
|---|---|
| `FilenameIndex.getAllFilesByExt(...)` / `getVirtualFilesByName(...)` / `processAllFileNames(...)` | `readAction { }` (prefer `smartReadAction { }` if indexes were just rebuilt) |
| `JavaPsiFacade.getInstance(project).findClass(...)` / `findClasses(...)` / `findPackage(...)` | `readAction { }` (or `smartReadAction { }`) |
| `PsiManager.getInstance(project).findFile(vf)` / `findDirectory(vf)` | `readAction { }` |
| `psiFile.text` / `psiFile.firstChild` / `psiFile.children` / `psiFile.containingFile` | `readAction { }` |
| `PsiSearchHelper.*`, `ReferencesSearch.*`, `ClassInheritorsSearch.*`, `MethodReferencesSearch.*` | `readAction { }` (or `smartReadAction { }` for index-dependent searches) |
| `vf.children` / `vf.parent` / `vf.findChild(name)` / recursive `walk { }` over a `VirtualFile` | `readAction { }` |
| `FileDocumentManager.getInstance().getDocument(vf)` and reading `document.text` / `lineCount` | `readAction { }` |
| `ProjectRootManager.getInstance(project).contentRoots` / `contentSourceRoots` / `projectSdk` | `readAction { }` |
| `ModuleRootManager.getInstance(module).*` / `LibraryTable.*` | `readAction { }` |
| `ChangeListManager.getInstance(project).allChanges` | `readAction { }` |
| `vf.createChildData(...)` / `createChildDirectory(...)` / `delete(...)` / `rename(...)` / `move(...)` | `writeAction { }` |
| `VfsUtil.saveText(vf, text)` / `VfsUtil.createDirectoryIfMissing(parent, rel)` | `writeAction { }` |
| `PsiDocumentManager.getInstance(project).commitAllDocuments()` | `writeAction { }` |
| Refactoring processor `.run()` (Rename / Move / SafeDelete / Inline / ChangeSignature / Extract*) | `writeIntentReadAction { }` (NOT `writeAction` — deadlocks) |

**Safe outside any wrap:** `LocalFileSystem.getInstance().findFileByPath(path)` (resolution only), `findProjectFile(relPath)` helper, `vf.path` / `vf.name` / `vf.isDirectory` (cached metadata), and plain `java.io.File` / `Files.*` / `Path.*` operations. The wrap becomes mandatory the moment you read the file's *structure* or hand it to a PSI API. Do NOT call `findFile` / `findProjectFile` *inside* `readAction { }` "to be safe" — under a read action they silently skip their disk refresh and can return stale content; resolve at the top level, then wrap only the PSI/structure reads.

**Symptom of skipping the wrap:** the runtime emits `SEVERE` `ThreadingAssertions` log lines and — in stricter modes — throws `RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`. Even when assertions only log, the IDE may produce stale or partial results, so wrap eagerly.

### IntelliJ Threading Model

1. **EDT (Event Dispatch Thread)** — UI updates only
2. **Read actions** required for PSI/VFS reads
3. **Write actions** required for PSI/VFS writes
4. **Smart mode** required for index-dependent operations

**See**: [IntelliJ Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)

### Quick Start

Your code is a **suspend function body** (never use `runBlocking`):
- Use `readAction { }` for PSI/VFS reads, `writeAction { }` for modifications
- Use `smartReadAction { }` for index-dependent PSI reads
- Use `Observation.awaitConfiguration(project)` after project import/sync/configuration
- Available: `project`, `println()`, `printJson()`, `printException()`, `progress()`

**⚠️ Helper functions that call `readAction`/`writeAction` MUST be `suspend fun`** — a regular `fun` that calls these gets a compile error: `"suspension functions can only be called within coroutine body"`. This applies to ALL suspend-context APIs: `readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`, `runInspectionsDirectly`.

### writeAction { } Is NOT a Coroutine Scope

Calling `readAction { }` or ANY suspend function inside `writeAction { }` throws `suspension functions can only be called within coroutine body` at **runtime** (not compile time). **ALWAYS read first (outside), then write (inside)**. Use `edtWriteAction { }` if you genuinely need suspend calls inside a write block.

### ALL VFS Mutation Ops Need writeAction

`createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require `writeAction`. Put the ENTIRE create-directory-and-write sequence inside a SINGLE `writeAction` block.

### Smart Mode

During indexing, the IDE is in "dumb mode" — many APIs are unavailable. Use `smartReadAction`
when you need both smart mode and read access. `waitForSmartMode()` is called automatically before
your script starts under the default `modal=smart_non_modal` (skipped under `non_modal` / `unleashed`),
but it is only a point-in-time wait: IntelliJ may enter dumb mode again before
the next statement. For initial project import/sync/configuration, await `Observation.awaitConfiguration(project)`,
then keep the indexed query inside `smartReadAction`.

```kotlin[IU]
import com.intellij.platform.backend.observation.Observation
import com.intellij.psi.JavaPsiFacade

Observation.awaitConfiguration(project)
val psiClass = smartReadAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.MyService", allScope())
}
println("Class found: ${psiClass != null}")
```

### Modal Dialogs and ModalityState

When a modal dialog is open, the default EDT dispatcher (`Dispatchers.EDT`) will **not execute** your code. Use `ModalityState.any()` to run on EDT regardless of modal state.

**Use `ModalityState.any()` for:** enumerating windows/dialogs, taking screenshots, closing dialogs programmatically.
**Don't use it for:** normal UI operations (use plain `Dispatchers.EDT`), read/write actions (use `readAction`/`writeAction`).

---

## Examples

### readAction

```kotlin
val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
println("PSI file: ${psiFile?.name}")
```

### smartReadAction

```kotlin[IU]
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}
println("Found ${classes.size} classes")
```

### writeAction — Read Outside, Write Inside

```kotlin
val vf = findProjectFile("src/main/java/com/example/Foo.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read OUTSIDE writeAction

// ⚠️ BEFORE content.replace() — ALWAYS print the excerpt BEFORE THE FIRST ATTEMPT:
val idx = content.indexOf("methodName")
println("EXCERPT:\n" + content.substring(idx, (idx + 250).coerceAtMost(content.length)))

val updated = content.replace("oldString", "newString")
check(updated != content) { "content.replace matched nothing — whitespace mismatch!" }
writeAction { VfsUtil.saveText(vf, updated) }    // write INSIDE — no suspend calls allowed

// After bulk VFS edits, flush to disk before running git/shell subprocesses:
LocalFileSystem.getInstance().refresh(false)
```

### WriteCommandAction (Undo Stack)

```kotlin
val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")!!
val document = FileDocumentManager.getInstance().getDocument(vf)!!

import com.intellij.openapi.command.WriteCommandAction

WriteCommandAction.runWriteCommandAction(project) {
    document.replaceString(0, 0, "// Added comment\n")
}
```

### VFS Mutation — Directory + File Creation

```kotlin
val content = "package com.example.model;\npublic class Product { }"
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    VfsUtil.saveText(f, content)
}
// WRONG: createDirectoryIfMissing OUTSIDE writeAction → "Write access is allowed inside write-action only"
```

### Create/Write a Source File

One file per steroid_execute_code call when possible (makes error attribution trivial):
```kotlin
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    // Use joinToString() — NOT a triple-quoted string with 'import' at line start
    // ⚠️ VfsUtil.saveText() REPLACES THE ENTIRE FILE — for adding a single method,
    // use PSI writeCommandAction + factory.createMethodFromText() instead (see guide).
    VfsUtil.saveText(f, listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.Id;",
        "@Entity public class Product { @Id private Long id; }"
    ).joinToString("\n"))
}
println("File created")
// After bulk file creation: await configuration, then use smartReadAction for ReferencesSearch
```

### ModalityState Usage

```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Runs on EDT even when a modal dialog is showing
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    val isModal = ModalityState.current() != ModalityState.nonModal()
    println("Modal dialog showing: $isModal")
}
```

### DumbService Check

```kotlin
import com.intellij.openapi.project.DumbService

if (DumbService.isDumb(project)) {
    println("IDE is indexing - indices not available")
} else {
    println("Smart mode - all APIs available")
}
```

---

## Pitfalls

### PSI / `ProblemDescriptor` Returned from a Read Action Is Not a Snapshot

A `PsiElement`, `ProblemDescriptor`, `Document` reference returned from a `readAction { }` / `smartReadAction { }` block is a **live reference**, not a copy. Accessing its properties (`.text`, `.textRange`, `psiElement.containingFile`, `descriptor.descriptionTemplate.takeIf { … }.let { resolved.name }`) **outside** the read action throws `ReadAccessException`.

Common case: iterating `runInspectionsDirectly(file)` results. The Map is computed inside a read action, but the loop that walks `descs.forEach { p -> println(p.psiElement.text) }` runs *outside* — boom.

**Wrong** (`ReadAccessException` thrown when `p.psiElement.text` is touched outside the read lock):
> `val problems = runInspectionsDirectly(file)` — then `problems.forEach { (_, descs) -> descs.forEach { p -> println(p.psiElement.text) } }` outside any `readAction`.

**Right** — extract Strings inside the read action, iterate them outside; or re-enter `readAction { }` for the entire walk:

```kotlin
val file = findProjectFile("build.gradle.kts") ?: error("File not found")
val problems = runInspectionsDirectly(file)
val rendered = readAction {
    problems.flatMap { (tool, descs) -> descs.map { p -> "$tool: ${p.psiElement.text}" } }
}
rendered.forEach(::println)  // safe — Strings, no PSI

// Alternative — re-enter readAction { } for the entire walk
readAction {
    problems.forEach { (tool, descs) ->
        descs.forEach { p -> println("$tool: ${p.psiElement.text}") }
    }
}
```

### Import-in-Strings

Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

### Generating Java Code Inline — `.class` and Dollar-Sign

Java code often contains `.class` references and dollar-sign characters. In double-quoted Kotlin strings, `.class)` can be mis-parsed and a bare dollar sign triggers string interpolation. Use `java.io.File(path).writeText()` with string concatenation:
```kotlin
java.io.File("${project.basePath}/src/main/java/com/example/SecurityConfig.java").writeText(
    "package com.example;\n" +
    "import" + " org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n" +
    "public class SecurityConfig {\n" +
    "    public void configure(HttpSecurity http) throws Exception {\n" +
    "        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);\n" +
    "    }\n" +
    "}"
)
// For dollar signs in Java string literals: use "${'\$'}Bearer"
```
