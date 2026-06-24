/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File

class XcvbScreenshotDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val screenshotDirInContainer: String,
) {
    /**
     * Start periodic screenshot capture (one PNG per second).
     * Screenshots are saved to the mounted volume directory for live inspection.
     * (scrot opens-writes-closes each file, so virtiofs flushes correctly.)
     */
    fun startScreenshotCapture() {
        driver.mkdirs(screenshotDirInContainer)

        println("[xcvb] Starting periodic screenshot capture to ${driver.mapGuestPathToHostPath(screenshotDirInContainer)}/...")
        val captureScript = buildString {
            appendLine("while true; do ")
            appendLine($$"  scrot $$screenshotDirInContainer/screen-$(date +%Y%m%d-%H%M%S).png")
            appendLine("  sleep 1")
            appendLine("done")
        }

        val proc = driver.runInContainerDetached(
            listOf("bash", "-c", captureScript),
        )

        lifetime.registerCleanupAction {
            proc.kill()
        }
    }

    /** Capture a rectangular region of the screen to a file inside the container. */
    fun screenshotRegion(filename: String = "screenshot-${System.currentTimeMillis()}.png") : File {
        val destPath = "$screenshotDirInContainer/$filename"
        driver.startProcessInContainer {
            this
                .args("scrot", destPath)
                .timeoutSeconds(10)
                .quietly()
                .description("scrot $filename")
        }.assertExitCode(0) { "scrot $destPath failed" }
        println("[xcvb] Screenshot captured to $destPath")

        return driver.mapGuestPathToHostPath(destPath)
    }
}
