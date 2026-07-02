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
 * Settle phase (shared, [importSettleCode]): wait for progress to DRAIN — smart mode plus zero running
 * background progress indicators — and fail fast when progress FREEZES (stuck import). A one-shot
 * `awaitConfiguration` + `waitForSmartMode` snapshot is NOT enough: import (and the always-on source/javadoc
 * downloads) add library roots that re-trigger indexing in waves, so a dumb-flag-only check can return in a
 * gap between waves; the indicator check sees the still-running download/update tasks through those gaps.
 */
/**
 * Pure: the Kotlin script that triggers the Maven import.
 *
 * Library sources + javadoc are always auto-downloaded — agents get full API docs in the editor, which is
 * a real advantage worth the extra import time. Extracted as a pure function so a test can assert the
 * download stays enabled and that the import is awaited (not blindly delayed).
 *
 * Unified with the Gradle logic: instead of a blind `delay`, it subscribes to the project-level
 * `MavenImportListener` and awaits `importFinished` (bounded by a timeout), mirroring the Gradle path's
 * `ProjectDataImportListener` await.
 */
fun mavenImportTriggerCode(): String = $$"""
                try {
                    println("[IMPORT] Triggering Maven import...")
                    val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                    // Always auto-download library sources + javadoc so agents have full API docs in the IDE.
                    val importSettings = mavenManager.importingSettings
                    importSettings.isDownloadSourcesAutomatically = true
                    importSettings.isDownloadDocsAutomatically = true
                    println("[IMPORT] Maven source/doc download: sources=${importSettings.isDownloadSourcesAutomatically} docs=${importSettings.isDownloadDocsAutomatically}")

                    // Await import completion via the project-level MavenImportListener (subscribe BEFORE the
                    // trigger), mirroring the Gradle ProjectDataImportListener await — no blind delay.
                    val importDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val importConnection = project.messageBus.connect(disposable)
                    importConnection.subscribe(
                        org.jetbrains.idea.maven.project.MavenImportListener.TOPIC,
                        object : org.jetbrains.idea.maven.project.MavenImportListener {
                            override fun importFinished(
                                importedProjects: Collection<org.jetbrains.idea.maven.project.MavenProject>,
                                newModules: List<com.intellij.openapi.module.Module>,
                            ) {
                                if (importDone.complete(Unit)) {
                                    println("[IMPORT] Maven import finished: " + importedProjects.size + " project(s), " + newModules.size + " module(s)")
                                }
                            }
                        }
                    )
                    importDone.invokeOnCompletion { importConnection.disconnect() }

                    mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                    // A never-firing listener fails the trigger after 8 min (TimeoutCancellationException is
                    // rethrown below) — a bounded, visible failure instead of silently proceeding half-imported.
                    kotlinx.coroutines.withTimeout(8 * 60 * 1000L) { importDone.await() }
                } catch (e: Exception) {
                    println("[IMPORT] Maven trigger failed: ${e.message}")
                    throw e
                }
            """.trimIndent()

/**
 * Pure: the Kotlin script that triggers the Gradle import and awaits completion — symmetric with
 * [mavenImportTriggerCode].
 *
 * Configures the linked Gradle JVM, enables source auto-download (the full available parity with Maven:
 * IntelliJ's Gradle integration has no IDE-side javadoc auto-download, and *-sources.jar carries the API
 * docs), triggers a refresh, and awaits the project-level `ProjectDataImportListener` import-finished event
 * (Gradle's `Observation.awaitConfiguration` can stay suspended after sync). Extracted as a pure function so
 * a test can assert the source download stays enabled and the import is awaited.
 */
fun gradleImportTriggerCode(): String = $$"""
                println("[IMPORT] Gradle auto-import active from project open")
                // Enable source downloading for Gradle projects. Sources are the doc carrier here: unlike
                // Maven (isDownloadDocsAutomatically), IntelliJ's Gradle integration has NO IDE-side javadoc
                // auto-download — the only registered setting is "gradle.download.sources" (see
                // community/plugins/gradle/plugin-resources/intellij.gradle.xml; `downloadJavadoc` is a Gradle
                // build-script `idea`-plugin option, not an IDE setting). *-sources.jar gives agents the API
                // docs in the editor, so this is the full available parity with Maven.
                try {
                    val gradleSettings = org.jetbrains.plugins.gradle.settings.GradleSystemSettings.getInstance()
                    gradleSettings.isDownloadSources = true
                    println("[IMPORT] Gradle source download: enabled (no IDE-side javadoc auto-download exists for Gradle)")
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

/**
 * Pure: the Kotlin script that waits until the IDE is GENUINELY settled after an import trigger — by
 * watching whether progress is MOVING, not by a fixed idle streak.
 *
 * - **Settled**: `Observation.awaitConfiguration` completed within its bound (the PRIMARY signal —
 *   coroutine-based tracked work like the Maven source/javadoc download is invisible to progress
 *   indicators; a timed-out await means "still configuring" and the round is not quiet) AND smart mode AND
 *   zero running thread-under-indicator tasks (`CoreProgressManager.getCurrentIndicators()`, the secondary
 *   signal for old-style `Task.Backgroundable`/dumb tasks) — confirmed over a 10-round quiet window.
 * - **Stuck**: the observable state (configuring + dumb flags + indicator titles/text2/fractions) is FROZEN
 *   for a stuck budget while NOT configuring → fail fast naming the frozen state, instead of silently
 *   burning the whole deadline. A blocked awaitConfiguration is tracked activity in flight (liveness), so
 *   it never counts as stuck; a hung tracked activity is caught by the deadline.
 * - **Deadline**: an overall backstop for pathological still-changing-but-never-done states.
 */
fun importSettleCode(
    settleTimeoutMs: Long = 20 * 60 * 1000L,
    stuckTimeoutMs: Long = 3 * 60 * 1000L,
): String = $$"""
// Settle by PROGRESS, not by a fixed idle streak: done when configuration is drained + smart + no running
// background tasks (confirmed over a quiet window); fail fast when the observable state freezes.
val settleDeadline = System.currentTimeMillis() + $${settleTimeoutMs}L
val stuckTimeoutMs = $${stuckTimeoutMs}L
val requiredQuietRounds = 10
var quietRounds = 0
var round = 0
var lastSignature = ""
var lastChangeAt = System.currentTimeMillis()
var settled = false
while (System.currentTimeMillis() < settleDeadline) {
    // The PRIMARY busy signal: coroutine-based tracked work (Maven source/javadoc download runs via
    // launchTracked/withBackgroundProgress) is INVISIBLE to getCurrentIndicators() — only
    // Observation.awaitConfiguration sees it. A timed-out await (null) means "configuration still in
    // progress": that round is NOT quiet, no matter what the dumb flag and indicators say.
    val configuring = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
        com.intellij.platform.backend.observation.Observation.awaitConfiguration(project)
    } == null
    val dumb = com.intellij.openapi.project.DumbService.getInstance(project).isDumb
    // Secondary: old-style thread-under-indicator tasks (Task.Backgroundable, dumb tasks).
    val indicators = com.intellij.openapi.progress.impl.CoreProgressManager.getCurrentIndicators()
        .filter { it.isRunning }
    quietRounds = if (!configuring && !dumb && indicators.isEmpty()) quietRounds + 1 else 0
    if (quietRounds >= requiredQuietRounds) { settled = true; break }

    // Stuck detector: a healthy import keeps changing this signature (per-file indexing texts in text2,
    // task fractions). Frozen signature while busy = stuck -> fail fast naming the frozen state. A blocked
    // awaitConfiguration is tracked activity in flight — liveness by definition — so the stuck error only
    // fires when NOT configuring (a hung tracked activity is caught by the overall deadline instead).
    val signature = "configuring=" + configuring + " dumb=" + dumb + " tasks=" + indicators.joinToString("; ") {
        (it.text ?: "?") + " " + (it.text2 ?: "") +
            if (it.isIndeterminate) "" else " " + (it.fraction * 100).toInt() + "%"
    }
    if (signature != lastSignature) {
        lastSignature = signature
        lastChangeAt = System.currentTimeMillis()
    } else if (!configuring && System.currentTimeMillis() - lastChangeAt > stuckTimeoutMs) {
        error("[IMPORT] STUCK: no observable progress for " + (stuckTimeoutMs / 60_000) + " min: " + signature)
    }
    if (round++ % 15 == 0) println("[IMPORT] settling… " + signature + " quiet=" + quietRounds + "/" + requiredQuietRounds)
    kotlinx.coroutines.delay(1_000L)
}
if (settled) {
    println("[IMPORT] Settled — configuration drained + smart mode + no running background tasks")
} else {
    println("[IMPORT] WARNING: project did not fully settle within the deadline — last state: " + lastSignature)
}
""".trimIndent()

fun McpSteroidDriver.mcpTriggerImportAndWait(buildSystem: BuildSystem) {
    //TODO: move that to prompts and include it from there are resources
    val triggerCode = when (buildSystem) {
        BuildSystem.MAVEN -> mavenImportTriggerCode()

        BuildSystem.GRADLE -> gradleImportTriggerCode()

        BuildSystem.NONE -> """
                println("[IMPORT] No build system — skipping import trigger")
            """.trimIndent()
    }

    val code = """
println("[IMPORT] Build system: $buildSystem")
$triggerCode

${importSettleCode()}
"done"
""".trimIndent()

    // The exec timeout must CONTAIN the script's own budgets (up to 8 min trigger await + 20 min settle
    // deadline + slack) — with a smaller value the server kills the script mid-settle and the deadline/stuck
    // detectors never get to speak.
    val result = try {
        mcpExecuteCode(
            code = code,
            reason = "Trigger $buildSystem import and wait for completion",
            timeout = 30 * 60,
        )
    } catch (e: Exception) {
        throw RuntimeException("[IMPORT] Import trigger failed: ${e.message}", e)
    }
    // A script-level failure (the Maven trigger's rethrow, the settle STUCK error) comes back as a clean
    // isError result (exitCode 1), NOT an exception — ignoring the returned value would silently swallow it.
    if (result.exitCode != 0) {
        throw RuntimeException("[IMPORT] Import script failed:\n${result.stdout.trim().takeLast(1000)}")
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
