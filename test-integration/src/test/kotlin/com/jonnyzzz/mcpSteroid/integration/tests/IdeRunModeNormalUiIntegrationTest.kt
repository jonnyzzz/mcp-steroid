/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #212 integration coverage in the REAL IDE: the Docker container runs the IDE with a
 * real X display (see the xdotool-driven tests), i.e. in NORMAL UI mode — the primary
 * supported environment. Validates the run-mode handling end to end:
 *
 * 1. idea.log carries exactly one `IDE run mode:` INFO line naming `normal UI` with the
 *    raw flags, and NO headless-unsupported WARN.
 * 2. The live IDE classifies itself NORMAL_UI through the plugin's own detection
 *    (`detectIdeRunMode()` executed via `steroid_execute_code` inside that IDE), and the
 *    server-instructions builder is transparent for that mode — so the instructions a
 *    connected agent receives carry no headless notice.
 */
class IdeRunModeNormalUiIntegrationTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `normal UI mode is detected, logged once, and adds no warnings or notices`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "IDE Run Mode — Normal UI"
        ))
        val console = session.console

        // 1. idea.log: one mode line, normal UI, no WARN.
        console.writeStep("Checking the IDE run mode line in idea.log")
        val ideaLog = File(session.runDirInContainer, "intellij/ide-log/idea.log").readText()
        val modeLines = ideaLog.lineSequence().filter { it.contains("IDE run mode: ") }.toList()
        Assertions.assertEquals(1, modeLines.size,
            "exactly one 'IDE run mode:' line must be logged on startup; got: $modeLines")
        val modeLine = modeLines.single()
        Assertions.assertTrue(modeLine.contains("IDE run mode: normal UI"),
            "the container IDE runs with a real X display and must classify as normal UI: $modeLine")
        Assertions.assertTrue(modeLine.contains("headless=false"),
            "raw flags must show headless=false in the normal-UI container: $modeLine")
        Assertions.assertFalse(ideaLog.contains("Headless mode is unsupported"),
            "the headless-unsupported WARN must not appear in a normal-UI IDE")

        // 2. Live classification + instructions transparency inside the same IDE.
        console.writeStep("Verifying live detectIdeRunMode() and instructions transparency")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.jonnyzzz.mcpSteroid.server.detectIdeRunMode
                import com.jonnyzzz.mcpSteroid.server.serverInstructionsFor

                val mode = detectIdeRunMode()
                println("RUN_MODE: ${'$'}mode")
                val base = "BASE_INSTRUCTIONS"
                println("INSTRUCTIONS_TRANSPARENT: ${'$'}{serverInstructionsFor(mode, base) == base}")
            """.trimIndent(),
            taskId = "run-mode-normal-ui",
            reason = "#212 integration: live run-mode classification in a normal-UI IDE",
        ).assertExitCode(0, "run-mode probe script should succeed")
            .assertOutputContains("RUN_MODE: NORMAL_UI", message = "the live IDE must classify as NORMAL_UI")
            .assertOutputContains("INSTRUCTIONS_TRANSPARENT: true",
                message = "server instructions must be untouched in normal-UI mode")

        console.writeSuccess("Normal-UI run mode handled correctly: logged once, no WARN, transparent instructions")
    }
}
