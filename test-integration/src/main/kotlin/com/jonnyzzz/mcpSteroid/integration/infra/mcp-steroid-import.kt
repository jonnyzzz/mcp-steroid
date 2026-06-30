package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * Trigger Maven or Gradle import and wait for it to *actually* complete.
 *
 * Trigger phase (build-system specific):
 * - Maven: `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`.
 * - Gradle: configure the linked Gradle JVM, trigger refresh, and await the `ProjectDataImportListener`
 *   import-finished event (Gradle's `Observation.awaitConfiguration` can stay suspended after sync).
 * - NONE: nothing to trigger.
 *
 * Settle phase (shared): poll until the IDE stays out of dumb mode for a stable streak, re-running
 * `Observation.awaitConfiguration` each round. A single `awaitConfiguration` + `waitForSmartMode` snapshot
 * is NOT enough: import (and the always-on source/javadoc downloads) add library roots that re-trigger
 * indexing in waves, so a one-shot check can return in a gap between waves while indexing is still coming.
 */
/**
 * Pure: the Kotlin script that triggers the Maven import.
 *
 * Library sources + javadoc are always auto-downloaded — agents get full API docs in the editor, which is
 * a real advantage worth the extra import time. Extracted as a pure function so a test can assert the
 * download stays enabled.
 */
internal fun mavenImportTriggerCode(): String = $$"""
                try {
                    println("[IMPORT] Triggering Maven import...")
                    val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                    // Always auto-download library sources + javadoc so agents have full API docs in the IDE.
                    val importSettings = mavenManager.importingSettings
                    importSettings.isDownloadSourcesAutomatically = true
                    importSettings.isDownloadDocsAutomatically = true
                    println("[IMPORT] Maven source/doc download: sources=${importSettings.isDownloadSourcesAutomatically} docs=${importSettings.isDownloadDocsAutomatically}")
                    mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                    kotlinx.coroutines.delay(2_000L)
                } catch (e: Exception) {
                    println("[IMPORT] Maven trigger failed: ${e.message}")
                    throw e
                }
            """.trimIndent()

fun McpSteroidDriver.mcpTriggerImportAndWait(buildSystem: BuildSystem) {
    //TODO: move that to prompts and include it from there are resources
    val triggerCode = when (buildSystem) {
        BuildSystem.MAVEN -> mavenImportTriggerCode()

        BuildSystem.GRADLE -> $$"""
                println("[IMPORT] Gradle auto-import active from project open")
                // Enable source downloading for Gradle projects
                try {
                    val gradleSettings = org.jetbrains.plugins.gradle.settings.GradleSystemSettings.getInstance()
                    gradleSettings.isDownloadSources = true
                    println("[IMPORT] Gradle source download: enabled")
                } catch (e: Exception) {
                    println("[IMPORT] Gradle source download setting failed: ${e.message}")
                }

                val gradleProjectPath = project.basePath!!
                val projectSdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk
                    ?: error("Project SDK is not configured; cannot configure Gradle JVM")
                val gradleSettings = org.jetbrains.plugins.gradle.settings.GradleSettings.getInstance(project)
                val linkedGradleSettings = gradleSettings.getLinkedProjectSettings(gradleProjectPath)
                    ?: org.jetbrains.plugins.gradle.settings.GradleProjectSettings(gradleProjectPath).also { gradleSettings.linkProject(it) }
                val previousGradleJvm = linkedGradleSettings.gradleJvm
                if (previousGradleJvm != projectSdk.name) {
                    linkedGradleSettings.gradleJvm = projectSdk.name
                }
                println("[IMPORT] Gradle JVM: $previousGradleJvm -> ${linkedGradleSettings.gradleJvm} (${projectSdk.homePath})")

                val importDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                val importConnection = project.messageBus.connect(disposable)
                fun isCurrentGradleProject(path: String?): Boolean =
                    path == null || path == gradleProjectPath
                fun completeGradleImport(path: String?, event: String) {
                    if (isCurrentGradleProject(path) && importDone.complete(Unit)) {
                        println("[IMPORT] Gradle $event: $path")
                    }
                }
                importConnection.subscribe(
                    com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener.TOPIC,
                    object : com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener {
                        override fun onImportFinished(projectPath: String?) {
                            if (isCurrentGradleProject(projectPath)) {
                                println("[IMPORT] Gradle import finished: $projectPath")
                            }
                        }

                        override fun onFinalTasksFinished(projectPath: String?) =
                            completeGradleImport(projectPath, "final tasks finished")

                        override fun onImportFailed(projectPath: String?, t: Throwable) {
                            if (isCurrentGradleProject(projectPath) && importDone.completeExceptionally(t)) {
                                println("[IMPORT] Gradle import failed for $projectPath: ${t.message}")
                            }
                        }
                    }
                )
                importDone.invokeOnCompletion { importConnection.disconnect() }

                // Trigger Gradle refresh so source download and JVM settings take effect.
                try {
                    println("[IMPORT] Triggering Gradle refresh...")
                    com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject(
                        gradleProjectPath,
                        com.intellij.openapi.externalSystem.importing.ImportSpecBuilder(
                            project,
                            org.jetbrains.plugins.gradle.util.GradleConstants.SYSTEM_ID
                        ).build()
                    )
                    kotlinx.coroutines.delay(2_000L)
                } catch (e: Exception) {
                    println("[IMPORT] Gradle refresh failed: ${e.message}")
                    throw e
                }
                kotlinx.coroutines.withTimeout(8 * 60 * 1000L) {
                    importDone.await()
                }
            """.trimIndent()

        BuildSystem.NONE -> """
                println("[IMPORT] No build system — skipping import trigger")
            """.trimIndent()
    }

    // Cold imports with always-on source/javadoc download have a long indexing tail: each downloaded
    // *-sources.jar adds a library root and re-triggers indexing, so the project bounces in and out of
    // dumb mode in waves. The host-mounted ~/.m2 cache keeps re-runs fast.
    val settleTimeoutMs = 20 * 60 * 1000L

    val code = """
import com.intellij.platform.backend.observation.Observation
import com.intellij.openapi.project.DumbService
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

println("[IMPORT] Build system: $buildSystem")
$triggerCode

// Wait until the IDE is GENUINELY settled, not just a transient smart-mode gap. A single
// awaitConfiguration + waitForSmartMode can return in a gap between indexing waves while more indexing is
// still coming (the bug we hit: "Smart mode reached" fired mid-import while *-sources.jar roots were still
// being indexed). So poll until the project stays OUT of dumb mode for a stable streak, re-running
// awaitConfiguration each round to drain registered configuration activity. Bounded by a deadline.
val settleDeadline = System.currentTimeMillis() + ${settleTimeoutMs}L
val requiredIdleRounds = 10        // ~10s of continuous smart mode before the import is considered done
var idleRounds = 0
var round = 0
var dumb = true
while (System.currentTimeMillis() < settleDeadline) {
    withTimeoutOrNull(60_000L) { Observation.awaitConfiguration(project) }
    dumb = DumbService.getInstance(project).isDumb
    idleRounds = if (dumb) 0 else idleRounds + 1
    if (round++ % 15 == 0) println("[IMPORT] settling… dumb=" + dumb + " idleStreak=" + idleRounds + "/" + requiredIdleRounds)
    if (idleRounds >= requiredIdleRounds) break
    delay(1_000L)
}
if (idleRounds >= requiredIdleRounds) {
    println("[IMPORT] Settled — import + indexing complete (smart mode stable for " + requiredIdleRounds + "s)")
} else {
    println("[IMPORT] WARNING: project did not fully settle within ${settleTimeoutMs / 60_000} min (dumb=" + dumb + ") — still importing/indexing")
}
"done"
""".trimIndent()

    try {
        mcpExecuteCode(
            code = code,
            reason = "Trigger $buildSystem import and wait for completion",
            timeout = 600,
        )
    } catch (e: Exception) {
        throw RuntimeException("[IMPORT] Import trigger failed: ${e.message}", e)
    }

    if (buildSystem != BuildSystem.NONE) {
        reportProjectRedCode()
    }
}

/**
 * After a settled import, sanity-check that the project has no "red code" — resolve every reference in a
 * sample of project source files and count the ones that don't resolve. A clean import resolves them; a
 * large unresolved count means dependencies (or generated sources) didn't import and the agent would see
 * red everywhere. Sampling bounds the cost on huge projects (Keycloak). Reported via `[RED-CODE]` lines;
 * non-fatal (logged, not thrown) so it surfaces the state without failing the harness on a known-red repo.
 */
fun McpSteroidDriver.reportProjectRedCode(maxFiles: Int = 150) {
    val code = $$"""
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

val maxFiles = $$maxFiles
val sample = ArrayList<VirtualFile>()
readAction {
    val index = ProjectFileIndex.getInstance(project)
    for (root in ProjectRootManager.getInstance(project).contentSourceRoots) {
        if (sample.size >= maxFiles) break
        VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
            if (sample.size >= maxFiles) return@iterateChildrenRecursively false
            if (!vf.isDirectory && (vf.extension == "java" || vf.extension == "kt") && index.isInSourceContent(vf)) {
                sample.add(vf)
            }
            true
        }
    }
}

var unresolved = 0
var refs = 0
val examples = ArrayList<String>()
readAction {
    val psiManager = PsiManager.getInstance(project)
    for (vf in sample) {
        val psi = psiManager.findFile(vf) ?: continue
        psi.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                for (ref in element.references) {
                    if (ref.isSoft) continue
                    refs++
                    // Overloaded methods / static imports (Comparator.comparing, hamcrest is/assertThat,
                    // RestAssured.given, …) make resolve() return null (multiple candidates) even though the
                    // IDE shows no red. Count a poly-variant ref as resolved if multiResolve finds >=1
                    // candidate, so only genuinely-unresolved references (real red code) are reported.
                    val resolved =
                        if (ref is com.intellij.psi.PsiPolyVariantReference) ref.multiResolve(false).isNotEmpty()
                        else ref.resolve() != null
                    if (!resolved) {
                        unresolved++
                        if (examples.size < 15) examples.add(vf.name + ": '" + ref.canonicalText + "'")
                    }
                }
                super.visitElement(element)
            }
        })
    }
}

println("[RED-CODE] sampled " + sample.size + " source files, " + refs + " references; UNRESOLVED=" + unresolved)
for (e in examples) println("[RED-CODE]   - " + e)
"done"
""".trimIndent()

    try {
        mcpExecuteCode(code = code, reason = "Verify no red code after import", timeout = 300)
    } catch (e: Exception) {
        // The check is a diagnostic; never let it fail the import path.
        System.err.println("[RED-CODE] check failed to run: ${e.message}")
    }
}
