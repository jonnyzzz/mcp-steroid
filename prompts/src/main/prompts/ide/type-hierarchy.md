IDE: Type Hierarchy (Supertypes and Subtypes)
[AI,IC,IU]
Walks the supertype DAG and subtype tree of a class via PSI — the same APIs IntelliJ's Type Hierarchy view (Ctrl+H) uses. Project-scope subtypes; no lambdas.

**First-open readiness:** on a newly opened Maven or Gradle project, window
flags and `steroid_list_projects` can become ready before the external project
model is imported. Fetch `mcp-steroid://skill/execute-code-maven` or
`mcp-steroid://skill/execute-code-gradle`, trigger and await configuration exactly
as that recipe shows (the Maven recipe uses `Observation.awaitConfiguration(project)`),
then run this indexed query in `smartReadAction`. An unexpectedly tiny first
hierarchy is an import-readiness failure, not an exhaustive result.

The example caps output at 500 only to protect the response. For an exhaustive
task, raise the cap as needed and fail/report `subsTruncated=true`; never present
a truncated list as complete.

`ClassInheritorsSearch.search(base, scope, checkDeep=true)` crosses the whole graph
from an interface, through sub-interfaces, to implementing classes. It returns every inheriting `PsiClass`,
which mixes sub-interfaces, abstract classes, concrete classes, and possibly
anonymous/local classes whose `qualifiedName` is null. Match the user's noun:
for "classes that implement" exclude `isInterface`, but keep abstract classes
unless the user explicitly asks only for concrete implementations. The recipe
classifies named results and reports the unnamed count separately instead of
silently presenting a named-only list as the whole raw search result.

```kotlin
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

data class TypeHierarchyEntry(val depth: Int, val fqn: String, val kind: String)
data class SubtypeCounts(
    val total: Int,
    val named: Int,
    val unnamed: Int,
    val subInterfaces: Int,
    val abstractClasses: Int,
    val concreteClasses: Int,
)
data class TypeHierarchy(
    val base: String,
    val supers: List<TypeHierarchyEntry>,
    val subs: List<TypeHierarchyEntry>,
    val subsTruncated: Boolean,
    val subtypeCounts: SubtypeCounts,
)

// Configuration - modify these for your use case
val classFqn = "com.example.BaseType" // TODO: Set the class FQN
val maxSubtypes = 500

val hierarchy = smartReadAction {
    val scope = projectScope()
    val baseClass = JavaPsiFacade.getInstance(project)
        .findClass(classFqn, GlobalSearchScope.allScope(project))
        ?: return@smartReadAction null

    // Canonical exhaustive subtype query. The direct searches below reconstruct
    // tree depth; this set proves that traversal did not silently miss a branch.
    val expectedSubtypes = ClassInheritorsSearch.search(baseClass, scope, true).findAll()
    val expectedSubtypeFqns = expectedSubtypes.mapNotNullTo(HashSet<String>()) { it.qualifiedName }
    val namedImplementingClasses = expectedSubtypes.filter { it.qualifiedName != null && !it.isInterface }
    val subtypeCounts = SubtypeCounts(
        total = expectedSubtypes.size,
        named = expectedSubtypeFqns.size,
        unnamed = expectedSubtypes.count { it.qualifiedName == null },
        subInterfaces = expectedSubtypes.count { it.qualifiedName != null && it.isInterface },
        abstractClasses = namedImplementingClasses.count { it.hasModifierProperty(PsiModifier.ABSTRACT) },
        concreteClasses = namedImplementingClasses.count { !it.hasModifierProperty(PsiModifier.ABSTRACT) },
    )
    fun kindOf(cls: PsiClass) = when {
        cls.isInterface -> "interface"
        cls.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract class"
        else -> "concrete class"
    }

    val supers = mutableListOf<TypeHierarchyEntry>()
    val seenSupers = HashSet<String>()
    fun walkSupers(cls: PsiClass, depth: Int) {
        val parents = cls.supers.sortedBy { it.qualifiedName ?: it.name ?: "" }
        for (parent in parents) {
            // Mirror SupertypesHierarchyTreeStructure: skip Object for interfaces.
            if (baseClass.isInterface && parent.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) continue
            val fqn = parent.qualifiedName ?: parent.name ?: continue
            if (!seenSupers.add(fqn)) continue
            supers += TypeHierarchyEntry(depth, fqn, kindOf(parent))
            walkSupers(parent, depth + 1)
        }
    }
    walkSupers(baseClass, 1)

    val subs = mutableListOf<TypeHierarchyEntry>()
    val seenSubs = HashSet<String>()
    var truncated = false
    fun walkSubs(cls: PsiClass, depth: Int) {
        val direct = ClassInheritorsSearch.search(cls, scope, false).findAll()
            .sortedBy { it.qualifiedName ?: it.name ?: "" }
        for (child in direct) {
            if (subs.size >= maxSubtypes) {
                truncated = true
                return
            }
            // The public result is FQN-based. Anonymous/local classes have no qualifiedName;
            // subtypeCounts.unnamed makes that omission explicit.
            val fqn = child.qualifiedName ?: continue
            if (!seenSubs.add(fqn)) continue
            subs += TypeHierarchyEntry(depth, fqn, kindOf(child))
            walkSubs(child, depth + 1)
        }
    }
    walkSubs(baseClass, 1)
    check(truncated || seenSubs.containsAll(expectedSubtypeFqns)) {
        "Subtype traversal missed: " + (expectedSubtypeFqns - seenSubs).sorted().joinToString()
    }

    TypeHierarchy(
        baseClass.qualifiedName ?: baseClass.name ?: classFqn,
        supers,
        subs,
        truncated,
        subtypeCounts,
    )
}

if (hierarchy == null) {
    println("Class not found: $classFqn")
    return
}

println("Type hierarchy for ${hierarchy.base}")
println()
println("Supertypes (${hierarchy.supers.size}):")
if (hierarchy.supers.isEmpty()) {
    println("  (none — top of hierarchy)")
} else {
    for (entry in hierarchy.supers) {
        println("  " + "  ".repeat(entry.depth - 1) + "^ [${entry.kind}] " + entry.fqn)
    }
}
println()
val subsLabel = if (hierarchy.subsTruncated) "${hierarchy.subs.size}+ (truncated)" else "${hierarchy.subs.size}"
println("Subtypes in project scope ($subsLabel):")
if (hierarchy.subs.isEmpty()) {
    println("  (none — no inheritors found)")
} else {
    for (entry in hierarchy.subs) {
        println("  " + "  ".repeat(entry.depth - 1) + "v [${entry.kind}] " + entry.fqn)
    }
}
println()
val counts = hierarchy.subtypeCounts
println("Raw subtype search: total=${counts.total}, named=${counts.named}, anonymous/local=${counts.unnamed}")
println("Named categories: sub-interfaces=${counts.subInterfaces}, " +
    "abstract implementing classes=${counts.abstractClasses}, concrete implementing classes=${counts.concreteClasses}")
```

# See also

- [Hierarchy Search](mcp-steroid://ide/hierarchy-search) - Inheritors and method overrides
- [Call Hierarchy](mcp-steroid://ide/call-hierarchy) - Find callers of a method
- [Find References](mcp-steroid://lsp/find-references) - Find all usages of a symbol
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
