package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * Trigger Maven or Gradle import and wait for it to complete.
 *
 * For Maven: calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`
 * For Gradle: configures the linked Gradle JVM, triggers refresh, and waits for import events
 * For NONE: only waits for `Observation.awaitConfiguration`
 *
 * Maven/NONE wait via `Observation.awaitConfiguration(project)` + `waitForSmartMode()`.
 * Gradle waits via `ProjectDataImportListener` + `waitForSmartMode()` because
 * `Observation.awaitConfiguration(project)` can stay suspended after Gradle sync finishes.
 */
/**
 * Pure: the Kotlin script that triggers the Maven import.
 *
 * [downloadSourcesAndDocs] controls auto-download of library sources + javadoc — ON gives agents API
 * docs in the editor, but on huge projects (Keycloak) with many absent `*:sources` artifacts it churns
 * the project roots and the import never settles. See jonnyzzz/mcp-steroid#169. Extracted as a pure
 * function so the source-download choice is unit-testable.
 */
internal fun mavenImportTriggerCode(downloadSourcesAndDocs: Boolean): String = $$"""
                try {
                    println("[IMPORT] Triggering Maven import...")
                    val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                    // Source + javadoc auto-download: ON gives agents full API docs in the IDE; OFF on huge
                    // projects whose deps publish no *:sources/*:javadoc, where the failed downloads churn
                    // roots so the import never settles (#169). PSI navigation uses compiled stubs anyway.
                    val importSettings = mavenManager.importingSettings
                    importSettings.isDownloadSourcesAutomatically = $$downloadSourcesAndDocs
                    importSettings.isDownloadDocsAutomatically = $$downloadSourcesAndDocs
                    println("[IMPORT] Maven source/doc download: sources=${importSettings.isDownloadSourcesAutomatically} docs=${importSettings.isDownloadDocsAutomatically}")
                    mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                    kotlinx.coroutines.delay(2_000L)
                } catch (e: Exception) {
                    println("[IMPORT] Maven trigger failed: ${e.message}")
                    throw e
                }
            """.trimIndent()

fun McpSteroidDriver.mcpTriggerImportAndWait(buildSystem: BuildSystem, downloadSourcesAndDocs: Boolean = true) {
    //TODO: move that to prompts and include it from there are resources
    val waitForConfigurationWithObservation = buildSystem != BuildSystem.GRADLE
    val triggerCode = when (buildSystem) {
        BuildSystem.MAVEN -> mavenImportTriggerCode(downloadSourcesAndDocs)

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

    val code = """
import com.intellij.platform.backend.observation.Observation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

println("[IMPORT] Build system: $buildSystem")
$triggerCode

if ($waitForConfigurationWithObservation) {
    println("[IMPORT] Waiting for project configuration...")
    val configured = withTimeoutOrNull(8 * 60 * 1000L) {
        Observation.awaitConfiguration(project)
    }
    println(if (configured == null) "[IMPORT] WARNING: Configuration timed out after 8 minutes"
            else "[IMPORT] Configuration complete")
} else {
    println("[IMPORT] Configuration complete")
}

waitForSmartMode()
println("[IMPORT] Smart mode reached — import + indexing complete")
"done"
""".trimIndent()

    try {
        mcpExecuteCode(
            code = code,
            reason = "Trigger $buildSystem import and wait for completion",
            timeout = 600,
        )
    } catch (e: Exception) {
        throw Error("[IMPORT] Import trigger failed: ${e.message}", e)
    }
}
