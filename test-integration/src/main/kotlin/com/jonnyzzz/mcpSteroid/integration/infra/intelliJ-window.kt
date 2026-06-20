package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File

/**
 * Re-apply the IDE window layout by finding the IntelliJ window by PID and calling
 * [XcvbWindowDriver.updateLayout] with the target rect from [windowLayout].
 *
 * IntelliJ restores its own saved window bounds after project load, overriding the
 * initial xdotool positioning. This must be called after project initialization completes.
 */
fun IntelliJContainer.repositionIdeWindow() {
    console.writeStep(text = "Applying IDE window layout...")
    val ideWindow = windows.listWindows().firstOrNull { it.pid == pid }
    if (ideWindow == null) {
        println("[IDE-AGENT] repositionIdeWindow: no window found for PID=$pid, skipping")
        return
    }
    val targetRect = windowLayout.layoutIntelliJWindow()
    windows.updateLayout(ideWindow, targetRect)
    // Nudge the window size by 1px then restore: IntelliJ AWT may not notice the external
    // move and keeps rendering at the old position (50px gap at top, status bar clipped).
    // A second ConfigureNotify with a different size forces AWT to re-layout correctly.
    windows.forceRelayout(ideWindow, targetRect)
    console.writeSuccess("Window layout applied")
}


/**
 * Poll for IDE window readiness (extracted from the old waitForProjectReady).
 */
fun IntelliJContainer.waitForIdeWindow(
    guestProjectDir: String,
    timeoutMillis: Long,
    pollIntervalMillis: Long,
    requireIndexingComplete: Boolean,
    waitLabel: String,
) {
    val startedAt = System.currentTimeMillis()
    var lastStatus = "no project windows found"
    var projectReady = false
    var lastHeartbeatAt = startedAt

    // Surface poll status every ~10 s so silent multi-minute waits do not
    // look identical to a hung wait. CLAUDE.md's "1-minute investigate"
    // rule depends on operators seeing some output between polls.
    fun heartbeatIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatAt >= 10_000L) {
            console.writeInfo(
                "Still waiting for $waitLabel: $lastStatus (elapsed=${(now - startedAt) / 1000}s)"
            )
            lastHeartbeatAt = now
        }
    }

    while (System.currentTimeMillis() - startedAt < timeoutMillis) {
        val windows = try {
            mcpSteroid.mcpListWindows(timeoutSeconds = 120)
        } catch (e: Exception) {
            lastStatus = "mcpListWindows failed: ${e.message}"
            heartbeatIfDue()
            Thread.sleep(pollIntervalMillis)
            continue
        }
        // #92: window entries reference their project only by `project_name` (the routing key). Resolve
        // the guest project's key from its path via list_projects; during early startup list_projects may
        // momentarily be empty (project not yet registered) — fall back to "any project window" then.
        val guestProjectName = try {
            mcpSteroid.mcpListProjects().firstOrNull { it.path == guestProjectDir }?.projectName
        } catch (e: Exception) {
            null
        }
        val projectWindows = windows.filter {
            if (guestProjectName != null) it.projectName == guestProjectName else it.projectName != null
        }

        if (projectWindows.isEmpty()) {
            lastStatus = "no project windows found"
            heartbeatIfDue()
            Thread.sleep(pollIntervalMillis)
            continue
        }

        val modalDialogPresent = projectWindows.any { it.modalDialogShowing }
        if (modalDialogPresent) {
            // Fail fast — retrying past an unexpected modal hides real problems. Every
            // startup modal in our tests is an infrastructure bug (unknown SDK →
            // "download Corretto?" consent, "Open or Import?" prompt, etc.) that must
            // be fixed by pre-configuring the IDE so the dialog never fires in the
            // first place. `killStartupDialogs` was a workaround that let failures
            // linger; now the test surfaces them immediately with a screenshot.
            error(
                "Blocking modal dialog detected while waiting for $waitLabel. " +
                    "This is an infrastructure bug — modals must be prevented up-front (e.g. seed " +
                    "`ProjectJdkTable` via `mcpRegisterJdks` in the factory, pre-write trusted paths, " +
                    "suppress welcome dialogs), not killed reactively. " +
                    problemDetailsWithScreenshot("projectWindows=${projectWindows.size}")
            )
        }

        val readyWindow = projectWindows.any { window ->
            val initialized = window.projectInitialized == true
            val indexingDone = window.indexingInProgress == false
            initialized && (!requireIndexingComplete || indexingDone)
        }
        if (readyWindow) {
            console.writeSuccess(
                if (requireIndexingComplete) "Project import and indexing complete"
                else "Project initialized"
            )
            projectReady = true
            break
        }

        val initialized = projectWindows.any { it.projectInitialized == true }
        val indexing = projectWindows.any { it.indexingInProgress == true }
        lastStatus = "projectInitialized=$initialized, indexingInProgress=$indexing, windows=${projectWindows.size}"
        heartbeatIfDue()
        Thread.sleep(pollIntervalMillis)
    }

    val elapsed = System.currentTimeMillis() - startedAt
    require(projectReady) {
        "Failed waiting for $waitLabel after ${elapsed}ms. " +
            problemDetailsWithScreenshot("Last status: $lastStatus")
    }
}

private fun IntelliJContainer.problemDetailsWithScreenshot(baseDetails: String): String {
    val screenshot = latestScreenshotPath() ?: "<none>"
    return "$baseDetails; latestScreenshot=$screenshot\n${diagnosticsSummary()}"
}

private fun IntelliJContainer.latestScreenshotPath(): String? =
    File(runDirInContainer, "screenshot")
        .listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath

