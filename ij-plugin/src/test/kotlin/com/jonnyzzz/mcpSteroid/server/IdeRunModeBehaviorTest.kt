/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt

/**
 * Behavior coverage for the #212 run-mode handling through the real production functions
 * and the real server-instructions prompt, per mode — normal UI first: a normal desktop
 * IDE must see zero behavior difference (no warning, byte-identical instructions, a plain
 * mode line). The REAL-IDE end-to-end check lives in the test-integration module:
 * IdeRunModeNormalUiIntegrationTest (Docker IDE with an X display).
 */
class IdeRunModeBehaviorTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testNormalUiModeIsFullyTransparent() {
        val base = McpSteroidInfoPrompt().readPrompt()

        // No warning for a normal desktop IDE.
        assertNull(headlessWarningFor(IdeRunMode.NORMAL_UI))
        // Instructions are byte-identical — no notice, no whitespace drift.
        assertEquals(base, serverInstructionsFor(IdeRunMode.NORMAL_UI, base))
        // The INFO line names the mode plainly and carries the raw flags.
        val line = ideRunModeLogLine(IdeRunMode.NORMAL_UI)
        assertTrue("mode line must name normal UI: $line", line.startsWith("IDE run mode: normal UI "))
        assertTrue("mode line must carry raw flags: $line", line.contains("headless=") && line.contains("remoteDevBackend="))
    }

    fun testSupportedNonUiModesAreAlsoTransparent() {
        val base = McpSteroidInfoPrompt().readPrompt()
        for (mode in listOf(IdeRunMode.REMOTE_DEV_BACKEND, IdeRunMode.UNIT_TEST)) {
            assertNull("no warning for $mode", headlessWarningFor(mode))
            assertEquals("instructions untouched for $mode", base, serverInstructionsFor(mode, base))
        }
    }

    fun testHeadlessModeWarnsAndAppendsClientNoticeExactlyOnce() {
        val base = McpSteroidInfoPrompt().readPrompt()

        assertEquals(HEADLESS_UNSUPPORTED_WARNING, headlessWarningFor(IdeRunMode.HEADLESS))
        val instructions = serverInstructionsFor(IdeRunMode.HEADLESS, base)
        assertTrue("notice appended", instructions.endsWith(HEADLESS_MCP_CLIENT_NOTICE))
        assertTrue("base preserved", instructions.startsWith(base))
        assertEquals(
            "notice must appear exactly once",
            1, Regex(Regex.escape(HEADLESS_MCP_CLIENT_NOTICE)).findAll(instructions).count(),
        )
    }

    fun testRealServerPathInThisEnvironmentCarriesNoNotice() {
        // End-to-end: in the test process the detected mode is UNIT_TEST, so the exact
        // instructions the server would hand to a connected agent carry no headless notice.
        assertEquals(IdeRunMode.UNIT_TEST, detectIdeRunMode())
        val real = serverInstructionsFor(detectIdeRunMode(), McpSteroidInfoPrompt().readPrompt())
        assertFalse(real.contains(HEADLESS_MCP_CLIENT_NOTICE))
    }
}
