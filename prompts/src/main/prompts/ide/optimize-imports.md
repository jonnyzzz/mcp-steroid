IDE: Optimize Imports

This example removes unused imports and sorts remaining ones,

```kotlin[IU]
import com.intellij.psi.codeStyle.JavaCodeStyleManager

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
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

val originalText = document.text

if (dryRun) {
    // Copy file in readAction, then optimize in writeAction (PSI modification needs write access)
    val copy = readAction { psiFile.copy() as PsiFile }
    writeAction { JavaCodeStyleManager.getInstance(project).optimizeImports(copy) }
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
    JavaCodeStyleManager.getInstance(project).optimizeImports(psiFile)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

println("Optimized imports for: $filePath")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Formatting](mcp-steroid://lsp/formatting) - Format entire document
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
