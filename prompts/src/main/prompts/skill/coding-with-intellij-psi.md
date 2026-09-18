Coding with IntelliJ: PSI Operations & Code Analysis

PSI tree navigation, find usages, class hierarchy, Java/Kotlin PSI, code inspections, module-scoped search, and PSI vs file-read decision rules.

## When to Use PSI vs File Read

> **Rule**: If you're about to read a 3rd file just to trace code flow, use `ReferencesSearch.search()` or `JavaPsiFacade.findClass()` instead. PSI answers in 1 call what file reading needs 5-10 calls to reconstruct.
>
> - **PSI**: when you need structure (method signatures, field types, implemented interfaces, call sites)
> - **File read**: when you need full implementation details (method bodies, SQL queries, config file content)
>
> **Example**: `VfsUtil.loadText()` on a 200-line service file → you parse 200 lines to extract ~10 method signatures.
> `JavaPsiFacade.findClass() + .methods` → ~10 lines of compact signatures directly.

---

## Finding Classes

### Java — Find Class by FQN
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

readAction {
    val facade = JavaPsiFacade.getInstance(project)
    val psiClass = facade.findClass(
        "com.example.MyClass",
        GlobalSearchScope.allScope(project)
    )

    if (psiClass != null) {
        println("Found class: ${psiClass.qualifiedName}")
        println("File: ${psiClass.containingFile.virtualFile.path}")

        // List methods
        psiClass.methods.forEach { method ->
            val params = method.parameterList.parameters
                .joinToString { "${it.name}: ${it.type.presentableText}" }
            println("  ${method.name}($params): ${method.returnType?.presentableText}")
        }

        // List fields
        psiClass.fields.forEach { field ->
            println("  field ${field.name}: ${field.type.presentableText}")
        }

        // List implemented interfaces
        psiClass.implementsListTypes.forEach { t -> println("  implements: ${t.presentableText}") }
    }
}
```

### Kotlin — Find Class Methods
```kotlin[IU]
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

// smartReadAction runs the indexed read under IntelliJ's smart-mode read constraint
smartReadAction {
    val classes = KotlinClassShortNameIndex.get("MyService", project, projectScope())

    if (classes.isEmpty()) {
        println("No class named 'MyService' found")
        return@smartReadAction
    }

    classes.forEach { ktClass ->
        println("Class: ${ktClass.fqName}")
        println("File: ${ktClass.containingFile.virtualFile.path}")

        ktClass.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
            .forEach { method ->
                val params = method.valueParameters.joinToString { "${it.name}: ${it.typeReference?.text}" }
                val returnType = method.typeReference?.text ?: "Unit"
                println("  fun ${method.name}($params): $returnType")
            }
    }
}
```

### Check if a Class Exists
```kotlin[IU]
val existing = readAction {
    com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
        "com.example.MyClass",
        com.intellij.psi.search.GlobalSearchScope.projectScope(project)
    )
}
println(if (existing == null) "NOT_FOUND: safe to create" else "EXISTS: " + existing.containingFile.virtualFile.path)
```

### Discover Naming Conventions
Avoids naming mismatches like `CreateCommentPayload` vs `AddReplyPayload`. Always do this FIRST when the test doesn't import the class directly:
```kotlin[IU]
import com.intellij.psi.search.PsiShortNamesCache
val allNames = readAction { PsiShortNamesCache.getInstance(project).allClassNames.toList() }
allNames.filter { it.endsWith("Payload") || it.endsWith("Request") || it.endsWith("Dto") ||
    it.endsWith("Status") || it.endsWith("Type") || it.endsWith("Service") }
    .sorted().forEach { println(it) }
```

---

## PSI Tree Navigation

```kotlin
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)

    // Navigate parent chain
    val element = psiFile?.findElementAt(100) // element at offset 100
    var current: PsiElement? = element
    while (current != null) {
        println("${current.javaClass.simpleName}: ${current.text.take(50)}")
        current = current.parent
    }
}
```

### Find Elements by Type
```kotlin[IU]
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass

val psiFile = findPsiFile("/path/to/file.kt")

if (psiFile != null) {
    readAction {
        val functions = PsiTreeUtil.findChildrenOfType(psiFile, KtNamedFunction::class.java)
        functions.forEach { fn ->
            println("Function: ${fn.name} at line ${fn.textOffset}")
        }

        val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
        classes.forEach { cls ->
            println("Class: ${cls.name}")
        }
    }
}
```

---

## Find Usages & References

### Find All Callers/Usages — Java
```kotlin[IU]
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.GlobalSearchScope

// Class lookup and reference search both read indexes — keep the whole query
// and its result snapshot inside one smartReadAction block.
smartReadAction {
    val cls = JavaPsiFacade.getInstance(project).findClass(
        "com.example.domain.FeatureService",
        GlobalSearchScope.projectScope(project)
    )
    if (cls == null) {
        println("class not found")
        return@smartReadAction
    }
    ReferencesSearch.search(cls, projectScope()).findAll().forEach { ref ->
        val snippet = ref.element.parent.text.take(80)
        println("${ref.element.containingFile.name} → $snippet")
    }
}
```

**CRITICAL when adding new fields to command/DTO objects** — find every call site first so you can update all constructors:
```kotlin[IU]
import com.intellij.psi.search.searches.ReferencesSearch
smartReadAction {
    val cmdClass = JavaPsiFacade.getInstance(project).findClass("com.example.CreateReleaseCommand", projectScope())
    if (cmdClass != null) {
        ReferencesSearch.search(cmdClass, projectScope()).findAll().forEach { ref ->
            val file = ref.element.containingFile.virtualFile.path.substringAfterLast('/')
            println("$file → " + ref.element.parent.text.take(100))
        }
    } else println("class not found")
}
```

### Find Usages — Kotlin
```kotlin[IU]
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

smartReadAction {
    val classes = KotlinClassShortNameIndex.get("MyService", project, projectScope())
    val targetClass = classes.firstOrNull()

    if (targetClass == null) {
        println("Class not found")
        return@smartReadAction
    }

    val usages = ReferencesSearch.search(targetClass, projectScope()).findAll()

    println("Found ${usages.size} usages of ${targetClass.name}:")
    usages.forEach { ref ->
        val file = ref.element.containingFile.virtualFile.path
        val offset = ref.element.textOffset
        println("  $file:$offset")
    }
}
```

### Find Class Hierarchy
```kotlin[IU]
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

smartReadAction {
    val baseClass = JavaPsiFacade.getInstance(project)
        .findClass("java.util.List", GlobalSearchScope.allScope(project))

    if (baseClass != null) {
        val inheritors = ClassInheritorsSearch.search(
            baseClass,
            GlobalSearchScope.projectScope(project),
            true // include anonymous
        ).findAll()

        println("Found ${inheritors.size} implementations of ${baseClass.name}")
        inheritors.take(20).forEach { impl ->
            println("  ${impl.qualifiedName}")
        }
    }
}
```

---

## Annotation-Based Searches

### Find All @Entity Classes
```kotlin[IU]
import com.intellij.psi.search.searches.AnnotatedElementsSearch
smartReadAction {
    val entityAnnotation = JavaPsiFacade.getInstance(project).findClass("jakarta.persistence.Entity", allScope())
        ?: error("jakarta.persistence.Entity not on the project classpath")
    AnnotatedElementsSearch.searchPsiClasses(entityAnnotation, projectScope()).findAll()
        .forEach { println(it.qualifiedName) }
}
```

### Find @Repository Methods with @Query
```kotlin[IU]
val repo = readAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.ReleaseRepository", projectScope())
}
repo?.methods?.forEach { m ->
    val q = m.annotations.firstOrNull { it.qualifiedName?.endsWith("Query") == true }
    if (q != null) println("@Query ${m.name}: " + (q.findAttributeValue("value")?.text ?: ""))
}
```

### Discover REST Endpoint Mappings
PREFERRED over string-searching source — correctly handles class-level `@RequestMapping` + method-level `@GetMapping` combinations:
```kotlin[IU]
import com.intellij.psi.search.GlobalSearchScope
val controllerClass = readAction {
    JavaPsiFacade.getInstance(project).findClass(
        "com.example.api.controllers.FeatureReactionController",
        GlobalSearchScope.projectScope(project)
    )
}
readAction {
    controllerClass?.methods?.forEach { method ->
        val ann = method.annotations.firstOrNull { a ->
            listOf("GetMapping","PostMapping","DeleteMapping","PutMapping","PatchMapping","RequestMapping")
                .any { a.qualifiedName?.endsWith(it) == true }
        }
        if (ann != null) {
            val path = ann.findAttributeValue("value")?.text ?: ann.findAttributeValue("path")?.text ?: "\"\""
            println("${method.name}: ${ann.qualifiedName?.substringAfterLast('.')} $path")
        }
    }
}
```

---

## Module-Scoped Search

For projects with >5 modules, ALWAYS use `moduleWithDependenciesScope` instead of `projectScope()`. Project-wide search across many modules returns 10-50x more results.

```kotlin[IU]
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.JavaPsiFacade

// Step 1: Find the module containing your target file
// Option A: from a known VirtualFile
val vf = findProjectFile("ts-payment-service/src/main/java/PaymentService.java")!!
val module = readAction { ModuleUtilCore.findModuleForFile(vf, project) }
    ?: error("File not in any module")
// Option B: by module name
// val module = com.intellij.openapi.module.ModuleManager.getInstance(project)
//     .modules.firstOrNull { it.name.contains("payment", ignoreCase = true) }
//     ?: error("Module not found")
println("Module: ${module.name}")

// Step 2: Create a module-scoped search scope
val scope = GlobalSearchScope.moduleWithDependenciesScope(module)  // module + transitive deps
// val scope = GlobalSearchScope.moduleScope(module)               // module only (no deps)

// Use with FilenameIndex — only returns files in this module
val files = readAction { FilenameIndex.getVirtualFilesByName("PaymentService.java", scope) }
println("Found ${files.size} files in module scope")

// Step 3: Scope PSI/reference searches — index-backed, so one smartReadAction owns
// the class lookup, the search, and the result snapshot
val usages = smartReadAction {
    val targetClass = JavaPsiFacade.getInstance(project).findClass("com.example.PaymentService", scope)
        ?: error("class not found in module scope")
    ReferencesSearch.search(targetClass, scope).toList()
}
println("${usages.size} usages in module")
```

---

## Targeted File Operations

### Extract Only Relevant Lines
```kotlin
val testVf = findProjectFile("src/test/java/com/example/MyTest.java")!!
val testContent = String(testVf.contentsToByteArray(), testVf.charset)
testContent.lines()
    .filter { it.contains("assert") || it.contains("/api/") || it.contains("@Test") }
    .forEach { println(it) }
```

### Find Next Flyway Migration Version
Avoids `V5__` collision if `V5__` already exists:
```kotlin[IU]
val migDir = findProjectFile("src/main/resources/db/migration")!!
val nextVersion = readAction {
    migDir.children.map { it.name }
        .mapNotNull { Regex("V(\\d+)__").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        .maxOrNull()?.plus(1) ?: 1
}
println("NEXT_MIGRATION=V" + nextVersion)
```

---

## Code Analysis

### Run Inspections Directly (Recommended)

**WARNING**: The daemon code analyzer returns stale results if the IDE window is not focused. Always use `runInspectionsDirectly()` for reliable results.
```kotlin
// RECOMMENDED - Reliable regardless of window focus
val file = requireNotNull(findProjectFile("src/main/kotlin/MyClass.kt")) { "File not found" }

val problems = runInspectionsDirectly(file)

if (problems.isEmpty()) {
    println("No problems found!")
} else {
    problems.forEach { (inspectionId, descriptors) ->
        descriptors.forEach { problem ->
            val element = problem.psiElement
            val line = if (element != null) {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(element.containingFile)
                doc?.getLineNumber(element.textOffset)?.plus(1) ?: "?"
            } else "?"
            println("[$inspectionId] Line $line: ${problem.descriptionTemplate}")
        }
    }
}
```

**Parameters:**
- `file`: VirtualFile to inspect
- `includeInfoSeverity`: Include INFO-level problems (default: false, only WEAK_WARNING and above)

**Returns:** `Map<String, List<ProblemDescriptor>>` - inspection tool ID to problems found

### Get Errors and Warnings (Daemon-based, requires window focus)

**Note**: May return stale results if the IDE window is not focused. Prefer `runInspectionsDirectly()` for MCP use cases.

```kotlin
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem

readAction {
    val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")
    val psiFile = PsiManager.getInstance(project).findFile(vf!!)
    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)

    if (psiFile != null && document != null) {
        val highlights = DaemonCodeAnalyzerImpl.getHighlights(document, null, project)

        highlights.forEach { info: HighlightInfo ->
            println("[${info.severity}] ${info.description} at ${info.startOffset}")
        }
    }
}
```

---
