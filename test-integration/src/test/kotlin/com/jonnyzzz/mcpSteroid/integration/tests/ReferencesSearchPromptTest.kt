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
 * Integration test: verifies that Claude uses ReferencesSearch.search() to find
 * usages of a method in the TestProject instead of grepping source files.
 *
 * The test gives Claude a find-usages task and checks whether it uses the proper
 * PSI ReferencesSearch API or falls back to file grepping.
 */
class ReferencesSearchPromptTest {

    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude uses ReferencesSearch to find usages of a method`() {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "PSI ReferencesSearch prompt test — Claude",
        )).waitForProjectReady()

        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(text = "Building prompt for ReferencesSearch find-usages")

        val prompt = buildString {
            appendLine("# Task: Find all usages of a function in the project using PSI")
            appendLine()
            appendLine("Use `steroid_execute_code` to find all usages of the `leaderboard` function")
            appendLine("defined in `DemoByJonnyzzz.kt` in package `com.jonnyzzz.mcpSteroid.demo`.")
            appendLine()
            appendLine("Read `mcp-steroid://skill/coding-with-intellij-psi` for the correct find-usages pattern.")
            appendLine("Use `ReferencesSearch.search()` — do NOT grep source files or use the Glob tool.")
            appendLine()
            appendLine("Steps:")
            appendLine("1. Find the `leaderboard` Kt named function using FilenameIndex to locate the file,")
            appendLine("   then navigate to it via PSI (KtFile → children → KtNamedFunction named 'leaderboard').")
            appendLine("2. Call `ReferencesSearch.search(leaderboardFn, GlobalSearchScope.projectScope(project)).findAll()` to get usages.")
            appendLine("3. Print the count and locations of usages found.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("USAGES_FOUND: yes")
            appendLine("SEARCH_API: <name of the search API you used, e.g. ReferencesSearch>")
            appendLine("USAGE_COUNT: <number of usages found>")
        }

        console.writeStep(text = "Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating agent output")

        // If agent failed, check if required markers were still emitted
        val hasUsagesFoundMarker = hasAnyMarkerLine(output, "USAGES_FOUND", "Usages found")
        if (result.exitCode != 0 && !hasUsagesFoundMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "ReferencesSearch prompt test")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must have used steroid_execute_code
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        // Key check: agent must have used ReferencesSearch
        console.writeInfo("Checking: ReferencesSearch usage in exec_code")
        val usedReferencesSearch = combined.contains("ReferencesSearch", ignoreCase = false)

        check(usedReferencesSearch) {
            buildString {
                appendLine("Agent must use ReferencesSearch.search() inside steroid_execute_code.")
                appendLine()
                appendLine("Expected: 'ReferencesSearch' in exec_code body.")
                appendLine("Got: ReferencesSearch used = $usedReferencesSearch")
                appendLine()
                appendLine("The prompts in coding-with-intellij-psi.md need to explicitly steer agents")
                appendLine("toward ReferencesSearch.search() instead of file grepping.")
                appendLine()
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("ReferencesSearch usage confirmed")

        // Verify the SEARCH_API marker was emitted
        console.writeInfo("Checking: SEARCH_API marker")
        val searchApi = findMarkerValue(output, "SEARCH_API", "Search API")
        check(searchApi != null) {
            "Agent must output SEARCH_API marker.\nGot: null\nOutput:\n$combined"
        }
        console.writeSuccess("SEARCH_API: $searchApi")

        // Verify usages were found
        console.writeInfo("Checking: USAGES_FOUND marker")
        val usagesFound = findMarkerValue(output, "USAGES_FOUND", "Usages found")
        check(usagesFound != null && usagesFound.contains("yes", ignoreCase = true)) {
            "Agent must output USAGES_FOUND: yes.\nGot: $usagesFound\nOutput:\n$combined"
        }
        console.writeSuccess("USAGES_FOUND: $usagesFound")

        console.writeSuccess("Agent used ReferencesSearch for find-usages")
        console.writeHeader("PASSED")

        println("[TEST] Claude used ReferencesSearch.search() to find usages of the leaderboard function")
    }
}
