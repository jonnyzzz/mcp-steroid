/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import java.io.File

fun IntelliJContainer.Companion.create(lifetime: CloseableStack, opts: IntelliJContainerOpts): IntelliJContainer = opts.run {
    val ideProduct = distribution.product
    val selectedDockerBase = if (dockerFileBase == "ide-agent") ideProduct.dockerImageBase else dockerFileBase
    val selectedProject = when {
        project != IntelliJProject.TestProject -> project
        ideProduct == IdeProduct.PyCharm -> IntelliJProject.PyCharmTestProject
        ideProduct == IdeProduct.GoLand -> IntelliJProject.GoLandTestProject
        ideProduct == IdeProduct.WebStorm -> IntelliJProject.WebStormTestProject
        ideProduct == IdeProduct.Rider -> IntelliJProject.RiderTestProject
        ideProduct == IdeProduct.CLion -> IntelliJProject.CLionTestProject
        else -> project
    }

    val (runDir, realConsoleTitle) = allocRunDirAndTitle(lifetime, consoleTitle)

    val imageId = run {
        val ideArchive = distribution.resolveAndDownload()
        buildIdeImage(selectedDockerBase, ideArchive)
    }

    val containerMountedPath = "/mcp-run-dir"

    val setupHostMappings = setupHostMappings(opts)

    val volumes = buildList {
        add(ContainerVolume(runDir, containerMountedPath, "rw"))
        add(ContainerVolume(IdeTestFolders.repoCacheDir, "/repo-cache", "ro"))

        addAll(setupHostMappings.volumes)
    }

    val containerEnv = buildMap {
        putAll(setupHostMappings.envOverride)
    }

    var container: ContainerDriver = startDockerContainerAndDispose(
        lifetime,
        StartContainerRequest()
            .image(imageId)
            .enableInit()
            .extraEnvVars(containerEnv)
            .volumes(volumes)
            .ports(
                buildList {
                    add(XcvbVideoDriver.VIDEO_STREAMING_PORT)
                    add(McpSteroidDriver.MCP_STEROID_PORT)
                    add(IDE_DEBUG_PORT)
                    // Publish devrig's JDWP range (one `-p 23900-23999`) only for devrig tests; devrig
                    // (DEVRIG_DEBUG) picks a free one. Skipped for non-devrig IDE containers.
                    if (aiMode == AiMode.AI_DEVRIG) add(DEVRIG_DEBUG_PORT_RANGE)
                },
            ),
    )

    setupHostMappings.applyToContainer(container, lifetime)

    val gui = setupGuiContainerServices(lifetime, container, layoutManager, containerMountedPath, realConsoleTitle)
    val console = gui.console
    container = gui.container

    console.writeInfo("Preparing ${ideProduct.displayName}...")

    val ijDriver = IntelliJDriver(
        lifetime,
        container,
        "$containerMountedPath/intellij",
        ideProduct,
        disableProjectTrustChecks = disableProjectTrustChecks,
        trustAllProjectPaths = trustAllProjectPaths,
        preloadJdkTable = preloadJdkTable,
    )

    fun writeSessionInfo(mcpUrl: String?) {
        val videoPort = container.mapGuestPortToHostPort(XcvbVideoDriver.VIDEO_STREAMING_PORT)
        val ideDebugPort = container.mapGuestPortToHostPort(IDE_DEBUG_PORT)
        val infoString = buildString {
            appendLine("=".repeat(20))
            appendLine("Use these parameters to debug the test")
            appendLine("RUN_DIR=$runDir")
            appendLine("CONTAINER_ID=${container.containerId}")
            appendLine("DISPLAY=${gui.xcvb.DISPLAY}")
            appendLine("VIDEO_DASHBOARD=http://localhost:$videoPort/")
            appendLine("VIDEO_STREAM=http://localhost:$videoPort/video.mp4")
            // Attach IntelliJ "Remote JVM Debug" to this host port to debug the in-container
            // IDE + MCP Steroid plugin live (suspend=n, so the IDE never waits for a debugger).
            appendLine("IDE_DEBUG_PORT=$ideDebugPort")
            // devrig (npx-kt) JVM debug — only when devrig runs as the agents' stdio bridge (AI_DEVRIG).
            // DEVRIG_DEBUG makes devrig pick a free port in the published 23900-23999 range (announced on
            // devrig's stderr/log); map it to the host with `docker port $CONTAINER_ID <that-port>`.
            if (aiMode == AiMode.AI_DEVRIG) appendLine("DEVRIG_DEBUG_PORT_RANGE=23900-23999 (published; devrig picks a free one)")
            if (mcpUrl != null) {
                appendLine("MCP_STEROID=$mcpUrl")
            }
            appendLine("=".repeat(20))
        }
        val infoFile = File(runDir, "session-info.txt")
        infoFile.writeText(infoString)
        // Surface the debug port on the host console in the SAME wording the JVM's own JDWP
        // agent prints — except with the HOST-mapped port, not the in-container 5005. The IDE's
        // own "Listening for transport ..." line carries the container port and is invisible/
        // useless from the host, so the harness re-emits the standard line with the mapped port.
        // Tooling (and humans) recognize this exact format and can attach a "Remote JVM Debug" to it.
        println("Listening for transport dt_socket at address: $ideDebugPort")
        println("[IDE-DEBUG] attach IntelliJ 'Remote JVM Debug' to localhost:$ideDebugPort (suspend=n)")
    }

    console.writeInfo("Deploying MCP Steroid plugin...")
    ijDriver.deployPluginToContainer(IdeTestFolders.pluginZip)


    // Warm project cache artifacts on host before deploying:
    // bare repos, IntelliJ clone ZIPs, etc. mounted at /repo-cache.
    selectedProject.warmRepoCache(IdeTestFolders.repoCacheDir)

    val ijProjectDriver = IntelliJProjectDriver(lifetime, container, ijDriver, console)
    ijProjectDriver.deployProject(selectedProject)

    // Pin the project JDK (and, for Gradle projects, the Gradle JVM) in the project's .idea XML
    // BEFORE the IDE starts, so project-open resolves the SDK / auto-import JVM instead of stalling
    // on awaitConfiguration. The post-open mcpSetProjectSdk / import still run as backup.
    if (ideProduct.hasJavaSdk) {
        selectedProject.jdkVersion?.let { jdk ->
            console.writeInfo("Setting project JDK to $jdk in misc.xml before IDE start...")
            ijDriver.configureProjectJdk(jdk)
            if (selectedProject.buildSystems.any { it.type == BuildSystem.GRADLE }) {
                console.writeInfo("Pinning Gradle JDK to $jdk before IDE start...")
                ijDriver.configureGradleJdk(jdk)
            }
        }
    }

    console.writeInfo("Starting ${ideProduct.displayName}...")
    val ijProcess = ijDriver.startIde(beforeIdeStart = beforeIdeStart)
    console.writeSuccess("${ideProduct.displayName} process started")

    require(ijProcess.isRunning()) { "${ideProduct.displayName} process finished" }

    // Wait for MCP server readiness
    val mcpSteroidDriver = McpSteroidDriver(container, ijDriver)
    console.writeInfo("Waiting for MCP Steroid server...")
    mcpSteroidDriver.waitForMcpReady()

    val resolvedMcpConnectionMode: McpConnectionMode = mcpConnectionMode ?: when (aiMode) {
        AiMode.NONE -> McpConnectionMode.None
        AiMode.AI_MCP -> McpConnectionMode.Http
        AiMode.AI_DEVRIG -> McpConnectionMode.Devrig(DevrigSteroidDriver.deploy(container, mcpSteroidDriver))
    }

    val aiAgentDriver = AiAgentDriver(
        container = container,
        intellijDriver = ijDriver,
        console = console,
        mcp = mcpSteroidDriver,
        mcpConnection = resolvedMcpConnectionMode,
        logDir = runDir,
    )

    console.writeSuccess("MCP Steroid server ready")

    // Write info file with all ports and URLs for external tools
    val mcpUrl = mcpSteroidDriver.hostMcpUrl
    writeSessionInfo(mcpUrl)

    val session = IntelliJContainer(
        opts = opts.copy(project = selectedProject),
        lifetime = lifetime.nestedStack("intellij-container"),
        gui = gui,
        runDirInContainer = runDir,
        scope = container,
        intellijDriver = ijDriver,
        mcpSteroid = mcpSteroidDriver,
        aiAgents = aiAgentDriver,
        intellij = ijProcess,
        openFileOnStart = selectedProject.openFileOnStart,
    )

    session.repositionIdeWindow()

    // After-start hooks: caller-provided steps once the session is fully built.
    afterIdeStart.forEach { it(session) }

    // JDK availability at project-open: when `preloadJdkTable` is on (default), the JDK table
    // was already pre-written into `options/jdk.table.xml` before the IDE launched (see
    // IntelliJDriver.writeJdkTable) — so it is populated before project-open's Gradle auto-import
    // / `SdkLookup` runs, and no post-open registration is needed.
    //
    // Only when pre-write is disabled (e.g. the generator-fidelity test) do we fall back to the
    // legacy path: register JDKs via the IntelliJ API as early as possible, racing the async
    // `SdkLookup`. If `findJdk(sdkName)` ran first it would propose a download and block the EDT
    // on a consent modal. `mcpRegisterJdks` imports `JavaSdk`, only on the classpath for Java IDEs.
    if (!preloadJdkTable && ideProduct.hasJavaSdk) {
        console.writeInfo("Registering JDKs early (racing project-open SdkLookup)...")
        try {
            mcpSteroidDriver.mcpRegisterJdks()
            console.writeSuccess("Early JDK registration complete")
        } catch (e: Throwable) {
            console.writeInfo("Early JDK registration failed: ${e.message} (will retry in waitForProjectReady)")
        }
    } else if (preloadJdkTable && ideProduct.hasJavaSdk) {
        console.writeInfo("JDK table pre-written before IDE start (preloadJdkTable=true) — skipping post-open registration")
    } else {
        console.writeInfo("Skipping JDK registration — ${ideProduct.displayName} has no Java plugin (IdeProduct.hasJavaSdk=false)")
    }

    println("[IDE-AGENT] Session ready: $runDir")
    return session
}

