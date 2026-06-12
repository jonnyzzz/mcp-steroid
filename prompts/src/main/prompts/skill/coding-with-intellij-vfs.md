Coding with IntelliJ: Document, Editor & VFS Operations

Document and editor manipulation, VFS read/write, file creation, LocalFileSystem, VfsUtil, findProjectFile pitfall, and batch file reads.

## Document and Editor Operations

### Read Document Content
```kotlin
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val document = FileDocumentManager.getInstance().getDocument(vf!!)

    if (document != null) {
        println("Lines: ${document.lineCount}")
        println("Length: ${document.textLength}")

        // Get specific line
        val lineNum = 5
        if (lineNum < document.lineCount) {
            val startOffset = document.getLineStartOffset(lineNum)
            val endOffset = document.getLineEndOffset(lineNum)
            println("Line $lineNum: ${document.getText().substring(startOffset, endOffset)}")
        }
    }
}
```

### Modify Document

**CAUTION: This modifies files on disk!**
```kotlin
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
val document = FileDocumentManager.getInstance().getDocument(vf!!)

if (document != null) {
    WriteCommandAction.runWriteCommandAction(project) {
        // Insert at position (replaceString with equal start/end offsets = insert)
        document.replaceString(0, 0, "// Added comment\n")

        // Or replace text
        // document.replaceString(startOffset, endOffset, "new text")
    }
    println("Document modified")
}
```

### Access Current Editor
```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager

readAction {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor

    if (editor != null) {
        val document = editor.document
        val caretModel = editor.caretModel
        val selectionModel = editor.selectionModel

        println("Current file: ${editor.virtualFile?.name}")
        println("Caret offset: ${caretModel.offset}")
        println("Caret line: ${caretModel.logicalPosition.line}")

        if (selectionModel.hasSelection()) {
            println("Selected: ${selectionModel.selectedText}")
        }
    } else {
        println("No editor open")
    }
}
```

---

## VFS Operations

### Read File Content
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.charset.StandardCharsets

val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.txt")

if (vf != null && !vf.isDirectory) {
    val content = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
    println("File content (${content.length} chars):")
    println(content.take(500))
}
```

### Refresh a Specific File

Use this only when you know a file changed outside the IDE:
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

val path = "/path/to/file.txt"
val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
if (vf != null) {
    VfsUtil.markDirtyAndRefresh(false, false, false, vf)
}
```

### List Directory Contents
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

val dir = LocalFileSystem.getInstance().findFileByPath("/path/to/directory")

if (dir != null && dir.isDirectory) {
    dir.children.forEach { child ->
        val type = if (child.isDirectory) "DIR" else "FILE"
        println("[$type] ${child.name}")
    }
}
```

### Create File

**CAUTION: This modifies the filesystem!**
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem

writeAction {
    val parentDir = LocalFileSystem.getInstance().findFileByPath("/path/to/dir")
    if (parentDir != null) {
        val newFile = parentDir.createChildData(this, "newfile.txt")
        newFile.setBinaryContent("Hello, World!".toByteArray())
        println("Created: ${newFile.path}")
    }
}
```

### VFS Path Conflict Resolution (file exists where directory expected)

When VFS reports `'path/security' is not a directory`, a plain file occupies a path you expect to be a directory. Fix by checking `isDirectory`, deleting the blocking file, then recreating the directory:

```kotlin
// Safe directory creation — handles file/directory conflict
val basePath = project.basePath!!
writeAction {
    val parent = LocalFileSystem.getInstance()
        .findFileByPath("$basePath/src/main/java/eval/sample")
        ?: error("Parent not found")
    var dir = parent.findChild("security")
    if (dir != null && !dir.isDirectory) {
        dir.delete(this)  // remove blocking file
        dir = null
    }
    dir ?: parent.createChildDirectory(this, "security")
}
```

**Post-Bash VFS sync**: If you created/deleted files via `Bash` or `ProcessBuilder`, VFS may not reflect the changes. Refresh explicitly:
```kotlin
import com.intellij.openapi.vfs.VfsUtil
// Refresh entire project tree so VFS picks up externally created files:
VfsUtil.markDirtyAndRefresh(false, true, true,
    LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
)
```

---

### Create Java/Kotlin Source Files

> **⚠️ PERFORMANCE: Prefer the Write tool or Bash over VFS file creation inside steroid_execute_code.**
> Creating files via `writeAction { VfsUtil.createDirectoryIfMissing ... VfsUtil.saveText }` measured
> **+47% slower** than using the Write tool or Bash directly (A/B runs: 533s vs 361s for microshop-2).
> IntelliJ adds value for PSI navigation, Maven runner, compilation checks — NOT for writing files.
>
> **When to use VFS file creation (steroid_execute_code):**
> - You need to create a file AND immediately read it back with PSI in the SAME call
> - You need the VFS to register the file for an in-progress writeAction sequence
>
> **When to use Write tool / Bash (preferred for plain file creation):**
> - Creating new source files, config files, SQL migrations, test fixtures
> - Writing multiple files in bulk — Write tool has zero JVM overhead
> - Any file creation not immediately followed by PSI operations in the same call
>
> After creating files with the Write tool/Bash, refresh VFS before using them with PSI:

```kotlin
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
VfsUtil.markDirtyAndRefresh(false, true, true,
    LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
)
```

Use `VfsUtil.createDirectoryIfMissing` + `VfsUtil.saveText` only when creating files inside
steroid_execute_code is necessary (e.g., PSI operations follow immediately in the same call).

**⚠️ ALL VFS mutation ops need writeAction**: `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require writeAction. `createDirectoryIfMissing(VirtualFile parent, String relative)` also requires writeAction — use this overload inside writeAction. Note: `createDirectoryIfMissing(String absolutePath)` self-acquires a write lock internally (safe to call outside writeAction, but DO NOT call it inside writeAction). Put the ENTIRE create-directory-and-write sequence inside a SINGLE writeAction block using the VirtualFile overload:

```kotlin
// CORRECT — everything that creates or modifies files goes INSIDE writeAction:
val content = "package com.example.model;\npublic class Product { }"
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")  // ← writeAction required
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")   // ← writeAction required
    VfsUtil.saveText(f, content)                                                          // ← writeAction required
}
// WRONG:
// val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/...")  // OUTSIDE writeAction → throws!
// writeAction { VfsUtil.saveText(f, content) }                           // only saveText inside = WRONG
```

**⚠️ AVOID range-based VFS writes**: Never use hardcoded byte ranges when writing files (e.g., `setBinaryContent(bytes, 0, 2000)` when the file may be shorter). This causes `StringIndexOutOfBoundsException` when the range exceeds file length. Always use `VfsUtil.saveText(file, content)` for full-file replacement — it atomically replaces the entire content regardless of existing file size.

**⚠️ Import-in-strings pitfall**: The script preprocessor extracts `import foo.Bar;` lines from the top level of your script — including lines inside triple-quoted strings. This causes compilation failures (e.g., `unresolved reference 'jakarta'`) when you embed Java source in a `"""..."""` literal.

**⚠️ Char-literal pitfall in string-assembled Java**: When building Java source via Kotlin `joinToString()`, char literals like `'\''` cause silent escaping errors. The Kotlin string `"'\\''"` produces Java text `'\''` which is a Java syntax error (empty char literal followed by spurious `'`). For Java code containing char literals (e.g., `toString()` with `', '` separators), prefer `java.io.File.writeText()` with triple-quoted raw strings, or use `PsiFileFactory.createFileFromText()`:
```kotlin
// ✓ SAFE: Use java.io.File for Java source with char literals — not affected by import extraction
java.io.File("${project.basePath}/src/main/java/com/example/model/Product.java")
    .also { it.parentFile.mkdirs() }
    .writeText("""
        package com.example.model;
        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        @Entity
        public class Product {
            @Id private Long id;
            private String name;
            @Override public String toString() {
                return "Product{id=" + id + ", name='" + name + '\'' + "}";
            }
        }
    """.trimIndent())
LocalFileSystem.getInstance().refreshAndFindFileByPath("${project.basePath}/src/main/java/com/example/model/Product.java")
println("Created Product.java")
// Verify the write succeeded:
val vf = findProjectFile("src/main/java/com/example/model/Product.java")!!
check(String(vf.contentsToByteArray(), vf.charset).contains("class Product")) { "Write failed or file is empty" }
println("Verified: Product.java written correctly")
```

**Workaround for joinToString**: Use `joinToString()` or string concatenation for the Java source content:
```kotlin
writeAction {
    // Create package directories
    // DEPRECATED: project.baseDir — use LocalFileSystem instead:
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val srcDir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")

    // Build Java source using joinToString — avoids import-extraction bug
    val content = listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.GeneratedValue;",
        "import" + " jakarta.persistence.GenerationType;",
        "import" + " jakarta.persistence.Id;",
        "",
        "@Entity",
        "public class Product {",
        "    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)",
        "    private Long id;",
        "    private String name;",
        "    // getters/setters...",
        "}"
    ).joinToString("\n")

    val f = srcDir.findChild("Product.java") ?: srcDir.createChildData(this, "Product.java")
    VfsUtil.saveText(f, content)
    println("Created: ${f.path}")
}
```

Alternative: use `java.io.File` which is not affected by the preprocessor:
```kotlin
java.io.File("/path/to/project/src/main/java/com/example/Product.java").also { it.parentFile.mkdirs() }.writeText("""
    package com.example;
    import jakarta.persistence.Entity;
    @Entity public class Product { }
""".trimIndent())
// Then refresh the VFS so IntelliJ picks up the new file
LocalFileSystem.getInstance().refreshAndFindFileByPath("/path/to/project/src/main/java/com/example/Product.java")
println("File created and VFS refreshed")
```

---

## Read a Project File via findProjectFile

```kotlin
val vf = findProjectFile("src/main/resources/application.properties")!!
val text = String(vf.contentsToByteArray(), vf.charset)
println(text)
```

**⚠️ `findProjectFile()` pitfall for resource files**: requires the **FULL relative path** from the project root (e.g., `"src/main/resources/application.properties"`). Calling it with just a filename **always returns null** — causing NPE on `!!`. For files under `src/main/resources/`, use `FilenameIndex.getVirtualFilesByName()` which searches by filename:
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

---

## Read Multiple Files in One Call — Saves ~20s Per Call

> **⚠️ EXPLORATION RULE: Complete ALL exploration in AT MOST 2 steroid_execute_code calls.** (1) Test files + domain model in one batch. (2) Test infrastructure in a second batch only if needed. Do NOT issue one call per file group.
```kotlin
// Batch exploration: replace 5-8 sequential steroid_execute_code calls with 1
for (path in listOf(
    "pom.xml",
    "src/main/java/com/example/domain/CommentService.java",
    "src/main/java/com/example/domain/CommentRepository.java",
    "src/test/java/com/example/api/CommentControllerTest.java"
)) {
    val vf = findProjectFile(path) ?: run { println("NOT FOUND: $path"); continue }
    val content = String(vf.contentsToByteArray(), vf.charset)
    // IMPORTANT: distinguish three states:
    //   NOT FOUND  → file doesn't exist at all (test_patch may add it later)
    //   EMPTY      → file exists but has no content (patch not yet applied, or placeholder)
    //   HAS_CONTENT → readable; process normally
    if (content.isEmpty()) { println("EMPTY (file exists but no content — may be a new file from test_patch not yet applied): $path"); continue }
    println("\n=== $path ===")
    println(content)
}
```

> **No redundant re-reads**: Files you read this session remain in your conversation history. Do NOT re-read them when switching task phases or `task_id`. Only re-read a file if you explicitly modified it.

---
