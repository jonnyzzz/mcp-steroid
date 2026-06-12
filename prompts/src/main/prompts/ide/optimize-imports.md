IDE: Optimize Imports

Remove unused imports and sort the remaining ones via the language-agnostic ImportOptimizer extension point — the same recipe works in every JetBrains IDE.

Import optimization is platform-level: the `Code | Optimize Imports` action dispatches through
the `com.intellij.lang.importOptimizer` extension point, which the Java, Kotlin, Python, Go,
JavaScript/TypeScript, and Ruby plugins all implement. Resolve the optimizers for a file via
`LanguageImportStatements.INSTANCE.forFile(psiFile)` and run them — no language-specific classes
needed. When the set is empty, the file's language has no import optimizer registered (e.g. plain
text, SQL in DataGrip) — report that honestly instead of pretending the imports were optimized.

```kotlin
import com.intellij.lang.LanguageImportStatements

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt" // TODO: Set your file path
val dryRun = true


val (psiFile, document) = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null to null
    val psi = PsiManager.getInstance(project).findFile(virtualFile)
    val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
    psi to doc
}

if (psiFile == null || document == null) {
    println("File not found or no document: $filePath")
    return
}

val optimizers = readAction { LanguageImportStatements.INSTANCE.forFile(psiFile) }
if (optimizers.isEmpty()) {
    println("No import optimizer registered for this file's language - nothing to optimize.")
    return
}

val originalText = document.text

if (dryRun) {
    // Copy file in readAction, then optimize in writeAction (PSI modification needs write access)
    val copy = readAction { psiFile.copy() as PsiFile }
    writeAction { optimizers.forEach { it.processFile(copy).run() } }
    val preview = readAction { copy.text }

    println("Optimize Imports Preview")
    println("=======================")
    println("File: $filePath")
    println()

    if (preview == originalText) {
        println("No import changes needed.")
    } else {
        val originalLines = originalText.lines()
        val newLines = preview.lines()
        println("Changes:")
        println("-".repeat(40))
        var changes = 0
        val maxLines = maxOf(originalLines.size, newLines.size)
        for (i in 0 until maxLines) {
            val origLine = originalLines.getOrNull(i) ?: ""
            val newLine = newLines.getOrNull(i) ?: ""
            if (origLine != newLine) {
                changes++
                if (changes <= 20) {
                    println("Line ${i + 1}:")
                    println("  - $origLine")
                    println("  + $newLine")
                }
            }
        }
        if (changes > 20) {
            println("... and ${changes - 20} more changes")
        }
        println()
        println("Total lines changed: $changes")
    }

    println()
    println("(Dry run - no changes made. Set dryRun=false to optimize imports)")
    return
}

WriteCommandAction.runWriteCommandAction(project) {
    optimizers.forEach { it.processFile(psiFile).run() }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

println("Optimized imports for: $filePath")
```

The interactive `Code | Optimize Imports` action runs `OptimizeImportsProcessor`
(`com.intellij.codeInsight.actions`), which wraps the same extension point in background-progress
machinery — in a script, driving the optimizers directly as above keeps the call synchronous.

###_IF_IDE[IU]_###
# IDEA: Java-specific entry point

In IntelliJ IDEA the Java optimizer can also be driven through `JavaCodeStyleManager` — the
classic Java-plugin API, equivalent to the generic recipe for `.java` files:

```kotlin[IU]
import com.intellij.psi.codeStyle.JavaCodeStyleManager

val filePath = "/path/to/your/File.java" // TODO: Set your file path

val psiFile = readAction {
    findFile(filePath)?.let { PsiManager.getInstance(project).findFile(it) }
} ?: return println("File not found: $filePath")

// Preview on a copy without touching the original; drop the copy step and pass
// `psiFile` inside a WriteCommandAction to apply for real.
val copy = readAction { psiFile.copy() as PsiFile }
writeAction { JavaCodeStyleManager.getInstance(project).optimizeImports(copy) }
val preview = readAction { copy.text }
println(preview)
```
###_END_IF_###

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Formatting](mcp-steroid://lsp/formatting) - Format entire document
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
