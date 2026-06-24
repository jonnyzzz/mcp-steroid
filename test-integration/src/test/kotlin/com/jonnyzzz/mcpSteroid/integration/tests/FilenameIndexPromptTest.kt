package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: verifies that Claude uses FilenameIndex.getVirtualFilesByName()
 * for file discovery instead of the Glob tool or Bash find.
 *
 * This test is part of the prompt improvement cycle for Group 1.
 * It is expected to FAIL on baseline (before prompt improvements)
 * and PASS after the prompts correctly guide agents toward FilenameIndex.
 *
 * The test gives Claude a straightforward file-discovery task and checks
 * whether it uses the IDE-indexed FilenameIndex API (O(1)) or falls back
 * to filesystem scanning via the Glob tool or Bash find (O(n)).
 */
class FilenameIndexPromptTest {

    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude uses FilenameIndex to find files instead of Glob or Bash find`() {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "FilenameIndex prompt test — Claude",
        )).waitForProjectReady()

        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep("Building prompt for FilenameIndex file discovery")

        val prompt = buildString {
            appendLine("# Task: Find a Kotlin file in the project by name")
            appendLine()
            appendLine("Use `steroid_execute_code` to find the file named 'DemoByJonnyzzz.kt' in the project.")
            appendLine("Print its full absolute path.")
            appendLine()
            appendLine("Read `mcp-steroid://skill/coding-with-intellij-vfs` for the correct file-discovery pattern.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("FILE_FOUND: yes")
            appendLine("FILE_PATH: <full absolute path to DemoByJonnyzzz.kt>")
            appendLine("DISCOVERY_METHOD: <brief description of how you found the file>")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        // If agent failed, check if required markers were still emitted
        val hasFileFoundMarker = hasAnyMarkerLine(output, "FILE_FOUND", "File found")
        if (result.exitCode != 0 && !hasFileFoundMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "FilenameIndex prompt test")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must have used steroid_execute_code
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        // Key check: agent must have used FilenameIndex.getVirtualFilesByName()
        // This is the PREFERRED IDE-indexed O(1) lookup.
        // If the agent used the Glob tool or ProcessBuilder("find") instead, the prompts
        // are not guiding agents correctly and need improvement.
        console.writeInfo("Checking: FilenameIndex usage in exec_code")
        val usedFilenameIndex = combined.contains("FilenameIndex", ignoreCase = false)
        val usedGlobTool = Regex(""""name"\s*:\s*"Glob"""").containsMatchIn(combined)
        val usedBashFind = combined.contains("ProcessBuilder(\"find\"") ||
                combined.contains("find . -name") ||
                combined.contains("find / -name")

        check(usedFilenameIndex) {
            buildString {
                appendLine("Agent must use FilenameIndex.getVirtualFilesByName() inside steroid_execute_code.")
                appendLine()
                appendLine("Expected: 'FilenameIndex' in exec_code body (O(1) IDE-indexed lookup).")
                appendLine("Got: FilenameIndex used = false")
                appendLine("     Glob tool used     = $usedGlobTool")
                appendLine("     Bash find used      = $usedBashFind")
                appendLine()
                appendLine("The prompts in coding-with-intellij-vfs.md need to explicitly state:")
                appendLine("  Do NOT use the Glob tool for file discovery.")
                appendLine("  Use FilenameIndex.getVirtualFilesByName() via exec_code instead.")
                appendLine()
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("FilenameIndex usage confirmed")

        // Verify the agent actually found the file
        console.writeInfo("Checking: FILE_PATH marker")
        val filePath = findMarkerValue(output, "FILE_PATH", "File path")
        check(filePath != null && filePath.contains("DemoByJonnyzzz.kt")) {
            "Agent must output FILE_PATH marker containing 'DemoByJonnyzzz.kt'.\n" +
                    "Got: $filePath\nOutput:\n$combined"
        }
        console.writeSuccess("FILE_PATH: $filePath")

        console.writeSuccess("Agent used FilenameIndex for file discovery")
        console.writeHeader("PASSED")

        println("[TEST] Claude used FilenameIndex.getVirtualFilesByName() to find DemoByJonnyzzz.kt")
    }
}
