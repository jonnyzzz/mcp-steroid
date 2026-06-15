IDE: Call Hierarchy (Find Callers)

Find every call site of the function or method at a position via ReferencesSearch — the platform core of Call Hierarchy; the same recipe works in every JetBrains IDE.

Finding callers is platform-level: resolve the PSI element at a position, then walk
`ReferencesSearch.search(element)` — it is language-agnostic and returns the references for
Java, Kotlin, Python, Go, JavaScript, and any other language whose PSI the IDE frontend owns.
The enclosing-declaration lookup uses `PsiNameIdentifierOwner`, which every language's named
declarations implement.

```kotlin
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
val maxResults = 20


val result = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath"
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@readAction "Cannot parse file: $filePath"
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath"

    val offset = document.getLineStartOffset(line - 1) + (column - 1)
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    // Position on a *usage*: resolve the reference to its declaration.
    // Position on the *declaration* itself: take the enclosing named declaration.
    val target = generateSequence(element) { it.parent }
        .mapNotNull { it.reference }
        .firstOrNull()
        ?.resolve()
        ?: PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false)
        ?: return@readAction "No declaration or reference at position ($line:$column)"

    val targetName = (target as? PsiNamedElement)?.name ?: target.text.take(40)
    val refs = ReferencesSearch.search(target, projectScope()).findAll()
    if (refs.isEmpty()) {
        "No callers found for $targetName"
    } else {
        buildString {
            appendLine("Callers of $targetName (${refs.size}):")
            appendLine()
            refs.take(maxResults).forEachIndexed { index, ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                val refDocument = refElement.containingFile?.let {
                    FileDocumentManager.getInstance().getDocument(it.virtualFile)
                }
                val refLine = refDocument?.getLineNumber(refElement.textOffset)?.plus(1) ?: -1
                val caller = PsiTreeUtil.getParentOfType(refElement, PsiNameIdentifierOwner::class.java, true)
                val callerName = caller?.name ?: "<top level>"

                appendLine("${index + 1}. $callerName - $refFile:$refLine")
            }
            if (refs.size > maxResults) {
                appendLine("... and ${refs.size - maxResults} more")
            }
        }
    }
}

println(result)
```

Caveats:

- `ReferencesSearch` returns **all** references, not only calls — imports, method references,
  and doc links are included. Filter by inspecting `ref.element.parent` if the task needs
  strict call sites only.
- It searches references to the *exact* element. For Java/Kotlin methods, IDEA's
  method-semantics-aware variant below additionally understands the override hierarchy
  and signatures.
- Rider caveat: C# PSI lives in the out-of-process ReSharper backend and is not reachable from
  `steroid_execute_code`; this recipe covers IDE-frontend languages only.

###_IF_IDE[AI,IC,IU]_###
# IDEA: method-aware variant for Java methods

`MethodReferencesSearch` (Java plugin) is what IDEA's own Find Usages uses for methods: it
understands Java method semantics — overriding hierarchy, constructors, property accessors.
The third argument is `strictSignatureSearch` (`true` = this exact signature only):

```kotlin[AI,IC,IU]
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val line = 10     // TODO: 1-based line number
val column = 15   // TODO: 1-based column number
val maxResults = 20


val result = readAction {
    val virtualFile = findFile(filePath)
        ?: return@readAction "File not found: $filePath"
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@readAction "Cannot parse file: $filePath"
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@readAction "Cannot get document for: $filePath"

    val offset = document.getLineStartOffset(line - 1) + (column - 1)
    val element = psiFile.findElementAt(offset)
        ?: return@readAction "No element at position ($line:$column)"

    val reference = generateSequence(element) { it.parent }
        .mapNotNull { it.reference }
        .firstOrNull()
    val method = (reference?.resolve() as? PsiMethod)
        ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        ?: return@readAction "No method found at position ($line:$column)"

    val refs = MethodReferencesSearch.search(method, projectScope(), true).findAll()
    if (refs.isEmpty()) {
        "No callers found for ${method.name}"
    } else {
        buildString {
            appendLine("Callers of ${method.name} (${refs.size}):")
            appendLine()
            refs.take(maxResults).forEachIndexed { index, ref ->
                val refElement = ref.element
                val refFile = refElement.containingFile?.virtualFile?.path ?: "unknown"
                val refDocument = refElement.containingFile?.let {
                    FileDocumentManager.getInstance().getDocument(it.virtualFile)
                }
                val refLine = refDocument?.getLineNumber(refElement.textOffset)?.plus(1) ?: -1
                val caller = PsiTreeUtil.getParentOfType(refElement, PsiMethod::class.java, false)
                val callerName = caller?.name ?: "<init>"

                appendLine("${index + 1}. $callerName - $refFile:$refLine")
            }
            if (refs.size > maxResults) {
                appendLine("... and ${refs.size - maxResults} more")
            }
        }
    }
}

println(result)
```
###_END_IF_###

# See also

- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [Go to Definition](mcp-steroid://lsp/go-to-definition) - Navigate to symbol definition
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Workspace Symbol](mcp-steroid://lsp/workspace-symbol) - Search symbols across workspace
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
