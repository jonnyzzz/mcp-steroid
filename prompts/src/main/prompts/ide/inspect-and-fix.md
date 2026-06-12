IDE: Inspection + Quick Fix

Run a named inspection on a file and apply its quick fix. The tool is resolved from the current profile by short-name, so the same recipe works in every JetBrains IDE.

The inspection-driving machinery (`InspectionEngine`, `LocalInspectionToolWrapper`, the
inspection profile) is platform-level and identical in IDEA, PyCharm, WebStorm, Rider,
CLion, GoLand. Only the inspection *classes* are language-plugin-bound — resolve the tool
from the current profile by its short-name instead of importing a class, and the recipe
runs unchanged in any IDE.

Find the short-name first: `mcp-steroid://ide/inspection-summary` lists every enabled
inspection with its short-name (e.g. `UnusedDeclaration`, `RedundantCast`,
`SpellCheckingInspection`).

```kotlin
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.util.PairProcessor

data class ProblemInfo(
    val problem: ProblemDescriptor,
    val description: String,
    val fix: QuickFix<CommonProblemDescriptor>?,
    val fixName: String?
)

// Configuration - modify these for your use case
val filePath = "/path/to/your/File.kt" // TODO: Set your file path
val inspectionShortName = "SpellCheckingInspection" // TODO: short-name from mcp-steroid://ide/inspection-summary
val dryRun = true


val virtualFile = findFile(filePath)
    ?: return println("File not found: $filePath")

val (psiFile, wrapper) = readAction {
    val psi = PsiManager.getInstance(project).findFile(virtualFile)
    val toolWrapper = psi?.let {
        InspectionProjectProfileManager.getInstance(project).currentProfile
            .getInspectionTool(inspectionShortName, it) as? LocalInspectionToolWrapper
    }
    psi to toolWrapper
}

if (psiFile == null) {
    return println("Cannot parse file: $filePath")
}
if (wrapper == null) {
    return println(
        "Inspection '$inspectionShortName' not found or not a local inspection. " +
            "List enabled tools via mcp-steroid://ide/inspection-summary"
    )
}

val problems: List<ProblemDescriptor> = readAction {
    val map = InspectionEngine.inspectEx(
        listOf(wrapper),
        psiFile,
        psiFile.textRange,
        psiFile.textRange,
        false,
        false,
        true,
        EmptyProgressIndicator(),
        PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true }
    )
    map.values.flatten()
}

if (problems.isEmpty()) {
    return println("No '$inspectionShortName' problems found in $filePath")
}

val problemInfo = readAction {
    val firstProblem = problems.first()
    val description = firstProblem.descriptionTemplate
    val fix = firstProblem.fixes?.firstOrNull()
    ProblemInfo(firstProblem, description, fix, fix?.name)
}

println("Found ${problems.size} problem(s).")
println("First problem: ${problemInfo.description}")

val fix = problemInfo.fix
val fixName = problemInfo.fixName
if (fix == null || fixName == null) {
    return println("No quick fix available for the first problem.")
}

if (dryRun) {
    println("Quick fix available: $fixName")
    return println("Set dryRun=false to apply changes.")
}

writeAction {
    fix.applyFix(project, problemInfo.problem)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}

println("Applied quick fix: $fixName")
```

###_IF_IDE[IU]_###
# IDEA: instantiating a Java inspection class directly

In IntelliJ IDEA the Java-plugin inspection classes are on the script classpath, so a
specific tool can also be instantiated directly — useful when it is disabled in the
profile and the short-name lookup returns nothing:

```kotlin[IU]
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.redundantCast.RedundantCastInspection

val wrapper = LocalInspectionToolWrapper(RedundantCastInspection())
println("Wrapper ready: ${wrapper.shortName} (${wrapper.displayName})")
// Pass `wrapper` to InspectionEngine.inspectEx exactly as in the recipe above.
```
###_ELSE_###
# Language-plugin inspection classes

This IDE's language inspections (Python, C#, C++, Go, JavaScript, ...) live inside
language plugins — do not import them by class name in scripts. The profile lookup in the
recipe above resolves the plugin's tool at runtime from its short-name; enumerate the
candidates via `mcp-steroid://ide/inspection-summary`.
###_END_IF_###

# Inspect a file in ANOTHER open project

The script context's `project` is resolved from the `project_name` tool argument and is
normally already the project you want — prefer passing the right `project_name` over
switching projects in code. When you genuinely need to inspect a file that belongs to a
*different* open project, select it explicitly and pass it to every project-parameterized
call (`PsiManager`, the profile manager, `smartReadAction`):

```kotlin
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.util.PairProcessor

// Configuration - modify these for your use case
val targetProjectName = "other-project" // TODO: name of the other OPEN project
val filePath = "/path/in/other/project/File.kt" // TODO: file inside that project
val inspectionShortName = "SpellCheckingInspection" // TODO: short-name to run

val openProjects = ProjectManager.getInstance().openProjects
val target = openProjects.firstOrNull { it.name == targetProjectName }
    ?: return println(
        "Project '$targetProjectName' is not open. Open projects: " +
            openProjects.joinToString(", ") { it.name }
    )

val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
    ?: return println("File not found: $filePath")

val problems: List<ProblemDescriptor> = smartReadAction(target) {
    val psiFile = PsiManager.getInstance(target).findFile(virtualFile)
        ?: error("Cannot parse file in '${target.name}': $filePath")
    val wrapper = InspectionProjectProfileManager.getInstance(target).currentProfile
        .getInspectionTool(inspectionShortName, psiFile) as? LocalInspectionToolWrapper
        ?: error("Inspection '$inspectionShortName' not found in '${target.name}'")
    InspectionEngine.inspectEx(
        listOf(wrapper),
        psiFile,
        psiFile.textRange,
        psiFile.textRange,
        false,
        false,
        true,
        EmptyProgressIndicator(),
        PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true }
    ).values.flatten()
}

println("Found ${problems.size} problem(s) in ${target.name}:$filePath")
problems.take(10).forEach { println("- ${it.descriptionTemplate}") }
```

Applying a quick fix in the other project works exactly like the main recipe — substitute
`target` for `project` in the `writeAction { fix.applyFix(target, problem) }` step.

# See also

- [Inspection Summary](mcp-steroid://ide/inspection-summary) - List every enabled inspection with its short-name
- [Find Duplicate Code](mcp-steroid://ide/find-duplicates) - Run `DuplicatedCode` and walk every clone cluster typed (no reflection)
- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [Document Symbols](mcp-steroid://lsp/document-symbols) - List symbols in a document
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
