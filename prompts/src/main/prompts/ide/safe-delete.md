IDE: Safe Delete

This example safely deletes a method or class, similar to "Refactor | Safe Delete".

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
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

val offset = document.getLineStartOffset(line - 1) + (column - 1)
val element = readAction { psiFile.findElementAt(offset) }
if (element == null) {
    println("No element found at $line:$column")
    return
}

val namedElement = readAction {
    PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
        ?: (element.reference?.resolve() as? PsiNamedElement)
}

if (namedElement == null) {
    println("No deletable element found at $line:$column")
    return
}

val targetName = readAction { namedElement.name ?: "<unnamed>" }
// Discovery snapshot under smartReadAction — the reference search is index-backed.
// The write-intent SafeDeleteProcessor phase below re-finds usages itself, so only
// this discovery step needs smart mode.
val usages = smartReadAction { ReferencesSearch.search(namedElement).findAll() }
if (dryRun) {
    println("Safe delete prepared for: $targetName")
    println("Usages found: ${usages.size}")
    println("Set dryRun=false to apply changes.")
    return
}

val processor = SafeDeleteProcessor.createInstance(
    project,
    null,
    arrayOf(namedElement),
    false,
    false
)

writeIntentReadAction { processor.run() }

writeAction { PsiDocumentManager.getInstance(project).commitAllDocuments() }
println("Safely deleted: $targetName")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
