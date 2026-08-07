IDE: Push Down Members
[IU]
This example pushes a member from a base class into its subclasses,

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo

// Configuration - modify these for your use case
val sourceClassFqn = "com.example.Base"  // TODO: Set base class FQN
val memberName = "memberToMove"          // TODO: Set member name
val dryRun = true


data class PushDownPlan(
    val summary: String,
    val sourceFqn: String,
    val member: PsiMember
)

// The plan phase runs inheritor discovery (index-backed) — smartReadAction, not readAction.
val (summary, plan) = smartReadAction {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.projectScope(project)

    val sourceClass = facade.findClass(sourceClassFqn, scope)
        ?: return@smartReadAction "Source class not found: $sourceClassFqn" to null

    val inheritors = ClassInheritorsSearch.search(sourceClass, scope, false).findAll()
    if (inheritors.isEmpty()) {
        return@smartReadAction "No inheritors found for $sourceClassFqn" to null
    }

    val member: PsiMember? =
        sourceClass.findMethodsByName(memberName, false).firstOrNull()
            ?: sourceClass.findFieldByName(memberName, false)

    if (member == null) {
        return@smartReadAction "Member not found: $memberName in $sourceClassFqn" to null
    }

    val memberType = when (member) {
        is PsiMethod -> "method"
        is PsiField -> "field"
        else -> "member"
    }
    val summary = "Prepared to push down $memberType '$memberName' from $sourceClassFqn to ${inheritors.size} inheritors"
    summary to PushDownPlan(summary, sourceClassFqn, member)
}

if (plan == null || dryRun) {
    println(summary)
    if (plan != null && dryRun) {
        println("Set dryRun=false to apply changes.")
    }
    return
}

writeIntentReadAction {
    val sourceClass = JavaPsiFacade.getInstance(project)
        .findClass(plan.sourceFqn, GlobalSearchScope.projectScope(project))!!
    val processor = PushDownProcessor(
        sourceClass,
        listOf(MemberInfo(plan.member)),
        DocCommentPolicy(DocCommentPolicy.MOVE),
        false
    )
    processor.run()
}

println("Pushed down member '${plan.member.name}' from ${plan.sourceFqn}")
```

# See also

- [Code Action](mcp-steroid://lsp/code-action) - Quick fixes and refactorings
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
