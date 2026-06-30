package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * Wait for the IDE project to finish import and indexing.
 * Polls via MCP execute_code until DumbService reports smart mode.
 * Writes progress to the console.
 *
 * When a modal dialog is detected (e.g. NewUI Onboarding in IntelliJ 2025.3.3+),
 * actively kills it via steroid_execute_code so Gradle import can proceed.
 *
 * Wait for the project to be fully ready for agent work.
 *
 * Ordered steps:
 * 1. Wait for IDE window (projectInitialized=true, indexingInProgress=false)
 * 2. Reposition IDE window
 * 3. Register JDKs via IntelliJ API (earliest possible — before any import)
 * 4. Set project SDK (parameter-driven, skip for Rider/.NET)
 * 5. Trigger build tool import (Maven/Gradle/NONE)
 * 6. Wait for import + indexing to complete
 * 7. Install IDE plugins (after import so dependency detection works)
 * 8. Compile project (testClasses/test-compile) — optional
 * 9. Open file + show tool windows
 *
 * @param timeoutMillis Max time for initial IDE window wait
 * @param projectJdkVersion JDK version to set as project SDK ("21", "17", etc.), null = skip
 * @param buildSystem Build system for import/compile. NONE = IntelliJ auto-detect only
 * @param compileProject Whether to run compilation (testClasses/test-compile) before returning
 */
fun IntelliJContainer.waitForProjectReady(
    timeoutMillis: Long = System.getProperty("test.integration.project.ready.timeout.ms")?.toLongOrNull() ?: 600_000L,
    pollIntervalMillis: Long = 1_000L,
    requireIndexingComplete: Boolean = true,
    performPostSetup: Boolean = true,
    projectJdkVersion: String? = project.jdkVersion,
    /**
     * Build system to import. When null (default) the project's declared [IntelliJProject.buildSystems]
     * are imported (each with its own root); pass an explicit value to override.
     */
    buildSystem: BuildSystem? = null,
    compileProject: Boolean = false,
) : IntelliJContainer {
    // Step 1: Wait for IDE window
    val waitLabel = if (requireIndexingComplete) "project import and indexing" else "project initialization"
    console.writeStep(text =  "Waiting for $waitLabel...")
    val guestProjectDir = intellijDriver.getGuestProjectDir()
    waitForIdeWindow(guestProjectDir, timeoutMillis, pollIntervalMillis, requireIndexingComplete, waitLabel)

    if (!performPostSetup) return this

    // Step 3: Register JDKs (earliest — before any import)
    // Only for Java-capable IDEs: `mcpRegisterJdks` / `mcpSetProjectSdk` use
    // `JavaSdk` which isn't on the script classpath in PyCharm/GoLand/WebStorm/Rider.
    if (projectJdkVersion != null && intellijDriver.ideProduct.hasJavaSdk) {
        console.writeStep(text = "Registering JDKs via IntelliJ API...")
        mcpSteroid.mcpRegisterJdks()
        console.writeSuccess("JDK registration complete")

        // Step 4: Set project SDK
        console.writeStep(text =  "Setting project SDK to JDK $projectJdkVersion...")
        mcpSteroid.mcpSetProjectSdk(projectJdkVersion)
        console.writeSuccess("Project SDK set to $projectJdkVersion")
    } else if (projectJdkVersion != null) {
        console.writeStep(text =  "Skipping JDK setup — ${intellijDriver.ideProduct.displayName} has no Java plugin")
    } else {
        console.writeStep(text =  "Skipping JDK setup (projectJdkVersion=null)")
    }

    // Step 5+6: Import each declared build system and wait for completion.
    // An explicit buildSystem arg overrides; otherwise import the project's declared set.
    // No build systems -> nothing to import (and we must NOT call awaitConfiguration on a
    // project with an unconfigured external build, which is what stalled for ~8 min).
    val systemsToImport: List<BuildSystem> = when {
        buildSystem != null -> listOf(buildSystem).filter { it != BuildSystem.NONE }
        else -> project.buildSystems.map { it.type }.distinct()
    }
    if (systemsToImport.isEmpty()) {
        console.writeStep(text = "No build system to import — skipping import wait")
    } else {
        systemsToImport.forEach { bs ->
            console.writeStep(text = "Triggering $bs import and waiting...")
            mcpSteroid.mcpTriggerImportAndWait(bs)
        }
        console.writeSuccess("Import + indexing complete")
    }

    // Step 6b: Resolve unknown SDKs (prevents "Resolving SDKs..." false positive during build)
    console.writeStep(text = "Resolving unknown SDKs...")
    mcpSteroid.mcpResolveUnknownSdks()
    console.writeSuccess("SDK resolution complete")

    // Step 7: Install IDE plugins
    console.writeStep(text = "Installing required IDE plugins...")
    mcpSteroid.mcpInstallRequiredPlugins()
    console.writeSuccess("Plugin installation complete")

    // Step 8: Compile project (optional)
    if (compileProject) {
        val compileWith = systemsToImport.firstOrNull() ?: BuildSystem.NONE
        console.writeStep(text = "Compiling project ($compileWith)...")
        mcpSteroid.mcpCompileProject(compileWith, projectJdkVersion)
        console.writeSuccess("Compilation complete")
    }

    // Step 9: Open file + show tool windows
    console.writeStep(text = "Opening project file and build tool window...")
    mcpSteroid.mcpOpenFileAndBuildToolWindow(openFileOnStart)
    console.writeSuccess("Project UX ready")

    return this
}
