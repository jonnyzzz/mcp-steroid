/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.runInContainerDetached
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

class XcvbDriver(
    private val lifetime: CloseableStack,
    innerDriver: ContainerDriver,
    private val layoutManager: LayoutManager,
) {

    val DISPLAY = ":99"
    private val driver = withDisplay(innerDriver)

    fun withDisplay(container: ContainerDriver): ContainerDriver {
        return container.withEnv("DISPLAY", DISPLAY)
    }

    fun wholeScreenAreal(): WindowRect {
        val size = layoutManager.displaySize
        require(size.x == 0 && size.y == 0) { "Incorrect screen size: $size" }
        return size
    }

    fun startDisplayServer() {
        println("[xcvb] Starting Xvfb...")
        val size = wholeScreenAreal()

        val proc = driver.runInContainerDetached(
            listOf("Xvfb", DISPLAY, "-screen", "0", "${size.width}x${size.height}x24", "-ac"),
        )

        println("[xcvb] Waiting for display $DISPLAY to be ready...")
        driver.startProcessInContainer {
            this
                .args(
                    "bash", "-c",
                    "for i in \$(seq 1 150); do xdpyinfo -display $DISPLAY >/dev/null 2>&1 && exit 0; sleep 0.1; done; exit 1",
                )
                .timeoutSeconds(20)
                .quietly()
                .description("wait for X display $DISPLAY")
        }.assertExitCode(0) { "[xcvb] Display $DISPLAY did not become ready within 15s" }

        println("[xcvb] Display $DISPLAY is ready")

        lifetime.registerCleanupAction {
            proc.kill()
        }
    }
}
