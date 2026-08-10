LSP: textDocument/references - Find All References

This example demonstrates how to find all references to a symbol,

The whole query — element resolution, the reference search, and the result snapshot — runs
inside `smartReadAction { }`: reference search reads indexes, and the IDE can re-enter dumb
mode at any moment, so a plain read action can fail nondeterministically with
`IndexNotReadyException` mid-search.

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt"  // TODO: Set your file path
val line = 10      // TODO: 1-based line number where symbol is defined
val column = 15    // TODO: 1-based column number


val result = smartReadAction {
    // Find the virtual file
    val virtualFile = findFile(filePath)
        ?: return@smartReadAction "File not found: $filePath"

    // Get PSI file
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@smartReadAction "Cannot parse file: $filePath"

    // Get document to convert line/column to offset
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@smartReadAction "Cannot get document for: $filePath"

    // Convert line/column to offset
    val offset = document.getLineStartOffset(line - 1) + (column - 1)

    // Find element at position
    val element = psiFile.findElementAt(offset)
        ?: return@smartReadAction "No element at position ($line:$column)"

    // Find the named element (declaration) at or around this position
    val namedElement = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
        ?: element.reference?.resolve() as? PsiNamedElement
        ?: return@smartReadAction "No named element found at position"

    // Search for all references
    val references = ReferencesSearch.search(namedElement, projectScope()).findAll()

    if (references.isEmpty()) {
        "No references found for: ${namedElement.name}"
    } else {
        buildString {
            appendLine("References to '${namedElement.name}' (${references.size} found):")
            appendLine()
            references.forEachIndexed { index, ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                val refDocument = refElement.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val refOffset = refElement.textOffset
                val refLine = refDocument?.getLineNumber(refOffset)?.plus(1) ?: -1
                val refCol = refDocument?.let {
                    refOffset - it.getLineStartOffset(refLine - 1) + 1
                } ?: -1

                appendLine("${index + 1}. $refFile:$refLine:$refCol")
                // Show context (the line of code)
                if (refDocument != null && refLine > 0) {
                    val lineStart = refDocument.getLineStartOffset(refLine - 1)
                    val lineEnd = refDocument.getLineEndOffset(refLine - 1)
                    val lineText = refDocument.charsSequence.subSequence(lineStart, lineEnd)
                        .toString().trim()
                    appendLine("   > $lineText")
                }
            }
        }
    }
}

println(result)
```

# See also

IDE power operations:
- [Call Hierarchy](mcp-steroid://ide/call-hierarchy) - Find method callers
- [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Find inheritors and overrides

Overview resources:
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
